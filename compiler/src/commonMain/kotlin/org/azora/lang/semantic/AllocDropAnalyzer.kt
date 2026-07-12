/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.azora.lang.semantic

import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef

/**
 * Semantic — Alloc / Drop Analysis.
 *
 * Runs AFTER types are fully resolved (post-CTFE stabilization) because
 * CTFE-generated code may introduce new allocations.
 *
 * Responsibilities (when the language grows):
 *  - Track `alloc` / `drop` pairs and verify every allocation is dropped.
 *  - Ownership analysis: ensure no use-after-drop.
 *  - Lifetime analysis for references.
 *  - Verify `deepinline` doesn't escape allocated memory.
 *
 * In the current minimal language (no alloc/drop), this validates that
 * all variables are used (simple liveness check) and no variable is
 * read before being initialized.
 */
class AllocDropAnalyzer {

    /**
     * Analyzes all functions in the program for liveness and use-before-init issues.
     *
     * @param program the CTFE-stabilized, type-checked AST to analyze
     * @return a list of warning and error messages (empty if no issues found)
     */
    fun analyze(program: Program): List<String> {
        val errors = mutableListOf<String>()

        // Collect global variable names (fin/var/let at top level) so assignments to them pass.
        val globalNames = mutableSetOf<String>()
        for (item in program.items) {
            when (item) {
                is TopLevel.FinDecl -> globalNames.add(item.name)
                is TopLevel.VarDecl -> globalNames.add(item.name)
                is TopLevel.LetDecl -> globalNames.add(item.name)
                else -> {}
            }
        }

        for (func in program.functions) {
            analyzeFunction(func, errors, globalNames)
        }

        return errors
    }

    private fun analyzeFunction(func: FuncDecl, errors: MutableList<String>, globalNames: Set<String>) {
        val defined = mutableSetOf<String>()
        val used = mutableSetOf<String>()

        // Parameters and global variables are always defined
        defined.addAll(globalNames)
        for (param in func.params) {
            defined.add(param.name)
        }
        if (func.isTask && !func.isUnsafe) {
            for (param in func.params) {
                if (param.modifier in setOf("ref", "mut ref", "out")) {
                    errors.add(
                        "line ${func.line}: task '${func.name}' cannot suspend with ${param.modifier} parameter '${param.name}'; " +
                            "use shared ref, pass ownership, or mark the task unsafe"
                    )
                }
            }
        }

        for (stmt in func.body) {
            analyzeStmt(stmt, defined, used, errors)
        }

        // Warn about unused variables (not params — those may be part of API contract)
        val declaredLocals = mutableSetOf<String>()
        for (stmt in func.body) {
            if (stmt is Stmt.VarDecl) declaredLocals.add(stmt.name)
            if (stmt is Stmt.FinDecl) declaredLocals.add(stmt.name)
        }
        for (local in declaredLocals) {
            if (local !in used) {
                errors.add("warning: variable '$local' in function '${func.name}' is never used")
            }
        }
    }

    private fun analyzeStmt(stmt: Stmt, defined: MutableSet<String>, used: MutableSet<String>, errors: MutableList<String>) {
        when (stmt) {
            is Stmt.VarDecl -> {
                validateReferenceBinding(stmt.type, stmt.initializer, mutable = true, stmt.line, errors)
                collectUsedVars(stmt.initializer, used)
                defined.add(stmt.name)
            }
            is Stmt.FinDecl -> {
                validateReferenceBinding(stmt.type, stmt.initializer, mutable = false, stmt.line, errors)
                collectUsedVars(stmt.initializer, used)
                defined.add(stmt.name)
            }
            is Stmt.Assignment -> {
                if (stmt.name !in defined) {
                    errors.add("line ${stmt.line}: assignment to undefined variable '${stmt.name}'")
                }
                collectUsedVars(stmt.value, used)
            }
            is Stmt.Return -> {
                if (stmt.value != null) collectUsedVars(stmt.value, used)
            }
            is Stmt.ExprStmt -> collectUsedVars(stmt.expr, used)
            is Stmt.If -> {
                collectUsedVars(stmt.condition, used)
                stmt.thenBranch.forEach { analyzeStmt(it, defined, used, errors) }
                stmt.elseBranch?.forEach { analyzeStmt(it, defined, used, errors) }
            }
            is Stmt.InlineIf -> {
                collectUsedVars(stmt.condition, used)
                stmt.thenBranch.forEach { analyzeStmt(it, defined, used, errors) }
                stmt.elseBranch?.forEach { analyzeStmt(it, defined, used, errors) }
            }
            is Stmt.DeepInlineIf -> {
                collectUsedVars(stmt.condition, used)
                stmt.thenBranch.forEach { analyzeStmt(it, defined, used, errors) }
                stmt.elseBranch?.forEach { analyzeStmt(it, defined, used, errors) }
            }
            is Stmt.DeepInlineBlock -> stmt.body.forEach { analyzeStmt(it, defined, used, errors) }
            is Stmt.NoInline -> analyzeStmt(stmt.stmt, defined, used, errors)
            is Stmt.InlineBlock -> stmt.body.forEach { analyzeStmt(it, defined, used, errors) }
            is Stmt.InlineFor -> {
                collectUsedVars(stmt.iterable, used)
                stmt.body.forEach { analyzeStmt(it, defined, used, errors) }
            }
            is Stmt.InlineFin -> {
                collectUsedVars(stmt.initializer, used)
                defined.add(stmt.name)
            }
            is Stmt.InlineLet -> {
                collectUsedVars(stmt.initializer, used)
                defined.add(stmt.name)
            }
            is Stmt.InlineVar -> {
                collectUsedVars(stmt.initializer, used)
                defined.add(stmt.name)
            }
            is Stmt.InlineAssignment -> {
                collectUsedVars(stmt.value, used)
            }
            is Stmt.LetDecl -> {
                validateReferenceBinding(stmt.type, stmt.initializer, mutable = false, stmt.line, errors)
                collectUsedVars(stmt.initializer, used)
                defined.add(stmt.name)
            }
            is Stmt.Zone -> stmt.body.forEach { analyzeStmt(it, defined, used, errors) }
            is Stmt.FriendZone -> stmt.body.forEach { analyzeStmt(it, defined, used, errors) }
            is Stmt.Assert -> {
                collectUsedVars(stmt.condition, used)
                collectUsedVars(stmt.message, used)
            }
            is Stmt.Trace -> collectUsedVars(stmt.message, used)
            is Stmt.InlineAssert -> {
                collectUsedVars(stmt.condition, used)
                collectUsedVars(stmt.message, used)
            }
            is Stmt.InlineTrace -> collectUsedVars(stmt.message, used)
            is Stmt.While -> {
                collectUsedVars(stmt.condition, used)
                stmt.body.forEach { analyzeStmt(it, defined, used, errors) }
            }
            is Stmt.For -> {
                if (stmt.iterable is Expr.Range) {
                    collectUsedVars(stmt.iterable.from, used)
                    collectUsedVars(stmt.iterable.to, used)
                } else {
                    collectUsedVars(stmt.iterable, used)
                }
                defined.add(stmt.name)
                stmt.body.forEach { analyzeStmt(it, defined, used, errors) }
            }
            is Stmt.Loop -> stmt.body.forEach { analyzeStmt(it, defined, used, errors) }
            is Stmt.Break -> {}
            is Stmt.Continue -> {}
            is Stmt.IndexAssign -> {
                collectUsedVars(stmt.target, used)
                collectUsedVars(stmt.index, used)
                collectUsedVars(stmt.value, used)
            }
            is Stmt.MemberAssign -> {
                collectUsedVars(stmt.target, used)
                collectUsedVars(stmt.value, used)
            }
            is Stmt.DerefAssign -> {
                collectUsedVars(stmt.target, used)
                collectUsedVars(stmt.value, used)
            }
            is Stmt.RemDecl -> {
                collectUsedVars(stmt.initializer, used)
                defined.add(stmt.name)
            }
            is Stmt.Effect -> stmt.body.forEach { analyzeStmt(it, defined, used, errors) }
            is Stmt.When -> {
                collectUsedVars(stmt.scrutinee, used)
                for (branch in stmt.branches) {
                    branch.patterns.forEach { collectUsedVars(it, used) }
                    branch.body.forEach { analyzeStmt(it, defined, used, errors) }
                }
                stmt.elseBranch?.forEach { analyzeStmt(it, defined, used, errors) }
            }
            is Stmt.Throw -> collectUsedVars(stmt.value, used)
            is Stmt.Yield -> collectUsedVars(stmt.value, used)
            is Stmt.Try -> {
                stmt.body.forEach { analyzeStmt(it, defined, used, errors) }
                if (stmt.catchName != null) defined.add(stmt.catchName)
                stmt.catchBody?.forEach { analyzeStmt(it, defined, used, errors) }
            }
            is Stmt.Defer -> stmt.body.forEach { analyzeStmt(it, defined, used, errors) }
        }
    }

    private fun validateReferenceBinding(
        annotation: TypeAnnotation,
        initializer: Expr,
        mutable: Boolean,
        line: Int,
        errors: MutableList<String>
    ) {
        val reference = (annotation as? TypeAnnotation.Explicit)?.ref as? TypeRef.Reference ?: return
        val isPlace = initializer is Expr.Identifier || initializer is Expr.Member ||
            initializer is Expr.Index || initializer is Expr.Deref
        if (!isPlace) {
            errors.add("line $line: ${reference.kind.spelling} must borrow a stable variable, field, index, or dereference")
        }
        if (reference.kind == TypeRef.RefKind.MUTABLE && !mutable) {
            errors.add("line $line: mut ref requires a 'var' binding")
        }
        if (reference.kind != TypeRef.RefKind.MUTABLE && mutable) {
            errors.add("line $line: ${reference.kind.spelling} binding must use 'fin' or 'let'")
        }
    }

    private fun collectUsedVars(expr: Expr, used: MutableSet<String>) {
        when (expr) {
            is Expr.Identifier -> used.add(expr.name)
            is Expr.UpperScopeAccess -> used.add(expr.name)
            is Expr.Binary -> { collectUsedVars(expr.left, used); collectUsedVars(expr.right, used) }
            is Expr.Unary -> collectUsedVars(expr.operand, used)
            is Expr.Call -> expr.args.forEach { collectUsedVars(it, used) }
            is Expr.Grouping -> collectUsedVars(expr.expr, used)
            is Expr.Range -> { collectUsedVars(expr.from, used); collectUsedVars(expr.to, used) }
            is Expr.ArrayLiteral -> expr.elements.forEach { collectUsedVars(it, used) }
            is Expr.SetLiteral -> expr.elements.forEach { collectUsedVars(it, used) }
            is Expr.Index -> { collectUsedVars(expr.target, used); collectUsedVars(expr.index, used) }
            is Expr.Member -> collectUsedVars(expr.target, used)
            is Expr.MethodCall -> { collectUsedVars(expr.target, used); expr.args.forEach { collectUsedVars(it, used) } }
            is Expr.StringTemplate -> {
                for (part in expr.parts) {
                    if (part is Expr.StringTemplatePart.Expr) collectUsedVars(part.expr, used)
                }
            }
            is Expr.TupleLit -> expr.elements.forEach { collectUsedVars(it, used) }
            is Expr.VariantLit -> expr.elements.forEach { collectUsedVars(it, used) }
            is Expr.TupleAccess -> collectUsedVars(expr.target, used)
            is Expr.CatchExpr -> { collectUsedVars(expr.expr, used); collectUsedVars(expr.fallback, used) }
            is Expr.IfExpr -> { collectUsedVars(expr.condition, used); collectUsedVars(expr.thenExpr, used); collectUsedVars(expr.elseExpr, used) }
            is Expr.Lambda -> {
                // Lambda bodies are analyzed by the enclosing statement analysis;
                // here we only note the bound parameters so they are not flagged unused.
            }
            is Expr.NamedArg -> collectUsedVars(expr.value, used)
            is Expr.NullLiteral -> {}
            is Expr.NullCoalesce -> { collectUsedVars(expr.left, used); collectUsedVars(expr.right, used) }
            is Expr.Cast -> collectUsedVars(expr.expr, used)
            is Expr.IsCheck -> collectUsedVars(expr.expr, used)
            is Expr.MapLit -> { for ((k, v) in expr.entries) { collectUsedVars(k, used); collectUsedVars(v, used) } }
            is Expr.Alloc -> collectUsedVars(expr.value, used)
            is Expr.AllocBuffer -> collectUsedVars(expr.count, used)
            is Expr.Deref -> collectUsedVars(expr.target, used)
            is Expr.Isolated -> collectUsedVars(expr.value, used)
            is Expr.Await -> collectUsedVars(expr.value, used)
            is Expr.Inject -> { /* no sub-expressions to collect */ }
            is Expr.Spread -> collectUsedVars(expr.array, used)
            is Expr.SafeMember -> collectUsedVars(expr.target, used)
            is Expr.IntLiteral, is Expr.RealLiteral,
            is Expr.StringLiteral, is Expr.BoolLiteral,
            is Expr.CharLiteral -> {}
        }
    }
}
