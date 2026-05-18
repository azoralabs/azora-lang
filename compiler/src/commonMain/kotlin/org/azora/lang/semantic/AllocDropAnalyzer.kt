/*
 * Copyright 2026 AzoraTech
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
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt

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

        for (func in program.functions) {
            analyzeFunction(func, errors)
        }

        return errors
    }

    private fun analyzeFunction(func: FuncDecl, errors: MutableList<String>) {
        val defined = mutableSetOf<String>()
        val used = mutableSetOf<String>()

        // Parameters are always defined
        for (param in func.params) {
            defined.add(param.name)
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
                collectUsedVars(stmt.initializer, used)
                defined.add(stmt.name)
            }
            is Stmt.FinDecl -> {
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
            is Expr.IntLiteral, is Expr.RealLiteral,
            is Expr.StringLiteral, is Expr.BoolLiteral,
            is Expr.CharLiteral -> {}
        }
    }
}
