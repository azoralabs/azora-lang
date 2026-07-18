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
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef
import org.azora.lang.frontend.ZoneMeta
import org.azora.lang.ir.IrType

/**
 * Semantic Pass 3 — Compile-Time Function Execution (CTCE).
 *
 * Evaluates functions and expressions that are marked (or inferred) as
 * compile-time. The results are folded back into the AST, replacing
 * CTCE call sites with their computed values.
 *
 * Design:
 *  - Shares the compiler's own type system ([IrType]) — no separate evaluator types.
 *  - After CTCE, the AST may contain new/mutated nodes that require re-running
 *    type resolution. The [SemanticPipeline] handles this via a fixed-point loop.
 *  - Maximum iteration count prevents infinite metaprogramming loops.
 *
 * In the current minimal language (no `inline` keyword), this pass detects
 * calls to functions whose arguments are all compile-time constants and
 * evaluates them.
 */
/**
 * @param table the symbol table used to look up function signatures during evaluation
 */
class CtfeEvaluator(private val table: SymbolTable) {

    /**
     * Result of a CTCE pass.
     *
     * @property program The (potentially modified) AST with CTCE results folded in.
     * @property changed Whether any AST nodes were modified (drives the fixed-point loop).
     * @property errors Any errors encountered during evaluation.
     */
    data class CtfeResult(
        val program: Program,
        val changed: Boolean,
        val errors: List<String>
    )

    /** Compile-time bindings from `inline fin` declarations. */
    private val inlineEnv = mutableMapOf<String, Expr>()

    /**
     * Compile-time constants folded by the top-level pass ([evaluateTopLevel]).
     * These come from top-level `deepinline`/`inline` bindings — including those
     * auto-imported from an `export module` such as `std.config` — and are exposed
     * so the fixed-point pass can seed them into every function body via
     * [seedConstants]. Without this, top-level compile-time constants are removed
     * before symbol collection and would be invisible inside functions.
     */
    var topLevelConstants: Map<String, Expr> = emptyMap()
        private set

    /**
     * Top-level compile-time constants injected into each function body's fold
     * environment so `deepinline if DEBUG { … }` and value reads of exported
     * config constants resolve inside functions, not only at the top level.
     */
    var seedConstants: Map<String, Expr> = emptyMap()

    private val reflectionTypes = mutableMapOf<String, String>()
    private var compileTimeDepth = 0
    private var activeErrors: MutableList<String>? = null

    private fun foldCompileTimeExpr(expr: Expr, program: Program): Pair<Expr, Boolean> {
        compileTimeDepth++
        return try {
            foldExpr(expr, program)
        } finally {
            compileTimeDepth--
        }
    }

    /**
     * Phase 0: Resolves only top-level inline items.
     *
     * Runs before [SymbolCollector] so that conditional function declarations
     * (inside `inline if` or `deepinline` blocks) are visible during symbol collection.
     *
     * @param program the raw AST before symbol collection
     * @return a [CtfeResult] with the flattened program and any errors
     */
    fun evaluateTopLevel(program: Program): CtfeResult {
        inlineEnv.clear()
        val errors = mutableListOf<String>()
        activeErrors = errors
        val (resolvedItems, changed) = resolveTopLevelItems(program.items, program, errors)
        topLevelConstants = inlineEnv.toMap()
        return CtfeResult(program.copy(items = resolvedItems), changed, errors)
    }

    /**
     * Evaluates all compile-time constructs in the program.
     *
     * This includes both top-level inline items and inline constructs inside
     * function bodies (`inline if`, `inline fin`, `inline { }`, etc.). Constant
     * expressions are folded and inline function calls are substituted.
     *
     * @param program the AST to evaluate (may contain unresolved inline constructs)
     * @return a [CtfeResult] with the transformed program, change flag, and any errors
     */
    fun evaluate(program: Program): CtfeResult {
        var changed = false
        val errors = mutableListOf<String>()
        activeErrors = errors

        // Phase A: Resolve top-level inline items
        inlineEnv.clear()
        val (resolvedItems, topChanged) = resolveTopLevelItems(program.items, program, errors)
        if (topChanged) changed = true

        // Phase B: Resolve inline constructs inside function bodies
        val newItems = resolvedItems.map { item ->
            if (item is TopLevel.Func) {
                inlineEnv.clear()
                // Seed top-level compile-time constants (e.g. exported `std.config`
                // flags) so they resolve inside function bodies too.
                inlineEnv.putAll(seedConstants)
                reflectionTypes.clear()
                item.decl.params.forEach { reflectionTypes[it.name] = it.type.displayName() }
                val (newBody, bodyChanged) = foldBody(item.decl.body, program, errors)
                if (bodyChanged) changed = true
                TopLevel.Func(item.decl.copy(body = newBody))
            } else item
        }

        return CtfeResult(
            program.copy(items = newItems),
            changed,
            errors
        )
    }

    // -- Top-level inline resolution ----------------------------------------

    private fun resolveTopLevelItems(
        items: List<TopLevel>, program: Program, errors: MutableList<String>
    ): Pair<List<TopLevel>, Boolean> {
        var changed = false
        val result = mutableListOf<TopLevel>()
        for (item in items) {
            val (resolved, wasChanged) = resolveTopLevelItem(item, program, errors)
            if (wasChanged) changed = true
            result.addAll(resolved)
        }
        return Pair(result, changed)
    }

    private fun resolveTopLevelItem(
        item: TopLevel, program: Program, errors: MutableList<String>
    ): Pair<List<TopLevel>, Boolean> = when (item) {
        is TopLevel.Func -> Pair(listOf(item), false)
        // Runtime top-level declarations pass through unchanged
        is TopLevel.VarDecl -> Pair(listOf(item), false)
        is TopLevel.FinDecl -> Pair(listOf(item), false)
        is TopLevel.LetDecl -> Pair(listOf(item), false)
        is TopLevel.Pack -> Pair(listOf(item), false)
        is TopLevel.Deco -> Pair(listOf(item), false)
        is TopLevel.Enum -> Pair(listOf(item), false)
        is TopLevel.Fail -> Pair(listOf(item), false)
        is TopLevel.Bridge -> Pair(listOf(item), false)
        is TopLevel.Solo -> Pair(listOf(item), false)
        is TopLevel.Wrap -> Pair(listOf(item), false)
        is TopLevel.Node -> Pair(listOf(item), false)
        is TopLevel.View -> Pair(listOf(item), false)
        is TopLevel.Hook -> Pair(listOf(item), false)
        is TopLevel.UseImport -> Pair(listOf(item), false)
        is TopLevel.Impl -> Pair(listOf(item), false)
        is TopLevel.Spec -> Pair(listOf(item), false)
        is TopLevel.TypeAlias -> Pair(listOf(item), false)
        is TopLevel.Slot -> Pair(listOf(item), false)
        is TopLevel.InlineVar -> {
            val (folded, _) = foldCompileTimeExpr(item.initializer, program)
            if (isConstant(folded)) { inlineEnv[item.name] = folded; Pair(emptyList(), true) }
            else Pair(listOf(item), false)
        }
        is TopLevel.InlineFin -> {
            val (folded, _) = foldCompileTimeExpr(item.initializer, program)
            if (isConstant(folded)) { inlineEnv[item.name] = folded; Pair(emptyList(), true) }
            else Pair(listOf(item), false)
        }
        is TopLevel.InlineLet -> {
            val (folded, _) = foldCompileTimeExpr(item.initializer, program)
            if (isConstant(folded)) { inlineEnv[item.name] = folded; Pair(emptyList(), true) }
            else Pair(listOf(item), false)
        }
        is TopLevel.InlineAssignment -> {
            if (item.name !in inlineEnv) {
                errors.add("line ${item.line}: inline assignment to undefined inline variable '${item.name}'")
                Pair(emptyList(), false)
            } else {
                val (folded, _) = foldCompileTimeExpr(item.value, program)
                if (isConstant(folded)) { inlineEnv[item.name] = folded; Pair(emptyList(), true) }
                else Pair(listOf(item), false)
            }
        }
        is TopLevel.InlineIf -> {
            val (foldedCond, _) = foldCompileTimeExpr(item.condition, program)
            if (foldedCond is Expr.BoolLiteral) {
                val branch = if (foldedCond.value) item.thenBranch else (item.elseBranch ?: emptyList())
                val (resolved, _) = resolveTopLevelItems(branch, program, errors)
                Pair(resolved, true)
            } else {
                Pair(listOf(item), false)
            }
        }
        is TopLevel.InlineBlock -> {
            val (resolved, _) = resolveTopLevelInlineBlock(item.body, program, errors)
            Pair(resolved, true)
        }
        is TopLevel.DeepInlineBlock -> {
            val (resolved, _) = resolveTopLevelDeepInlineBlock(item.body, program, errors)
            Pair(resolved, true)
        }
        is TopLevel.DeepInlineIf -> {
            val (foldedCond, _) = foldCompileTimeExpr(item.condition, program)
            if (foldedCond is Expr.BoolLiteral) {
                val branch = if (foldedCond.value) item.thenBranch else (item.elseBranch ?: emptyList())
                val (resolved, _) = resolveTopLevelDeepInlineBlock(branch, program, errors)
                Pair(resolved, true)
            } else {
                Pair(listOf(item), false)
            }
        }
        is TopLevel.Test -> Pair(listOf(item), false)
        is TopLevel.InlineAssert -> {
            val (foldedCond, _) = foldCompileTimeExpr(item.condition, program)
            if (foldedCond is Expr.BoolLiteral) {
                if (!foldedCond.value) {
                    val (foldedMsg, _) = foldExpr(item.message, program)
                    val msgText = if (foldedMsg is Expr.StringLiteral) foldedMsg.value else "inline assert failed"
                    errors.add("line ${item.line}: $msgText")
                }
                Pair(emptyList(), true)
            } else {
                Pair(listOf(item), false)
            }
        }
        is TopLevel.InlineTrace -> {
            val (foldedMsg, _) = foldExpr(item.message, program)
            if (foldedMsg is Expr.StringLiteral) {
                errors.add("warning: [TRACE] ${foldedMsg.value}")
                Pair(emptyList(), true)
            } else {
                Pair(listOf(item), false)
            }
        }
        // Macros are expanded (and removed) by MacroExpander before CTCE.
        is TopLevel.Meta -> Pair(listOf(item), false)
    }

    private fun resolveTopLevelInlineBlock(
        body: List<TopLevel>, program: Program, errors: MutableList<String>
    ): Pair<List<TopLevel>, Boolean> {
        val result = mutableListOf<TopLevel>()
        for (item in body) {
            val resolved = when (item) {
                is TopLevel.Func -> Pair(listOf(item), false)
                else -> resolveTopLevelItem(item, program, errors)
            }
            result.addAll(resolved.first)
        }
        return Pair(result, true)
    }

    private fun resolveTopLevelDeepInlineBlock(
        body: List<TopLevel>, program: Program, errors: MutableList<String>
    ): Pair<List<TopLevel>, Boolean> {
        val result = mutableListOf<TopLevel>()
        for (item in body) {
            when (item) {
                // noinline func / runtime decls pass through
                is TopLevel.Func -> result.add(item)
                is TopLevel.VarDecl -> result.add(item)
                is TopLevel.FinDecl -> result.add(item)
                is TopLevel.LetDecl -> result.add(item)
                is TopLevel.Pack -> result.add(item)
                is TopLevel.Deco -> result.add(item)
                is TopLevel.Enum -> result.add(item)
                is TopLevel.Fail -> result.add(item)
                is TopLevel.Bridge -> result.add(item)
                is TopLevel.Solo -> result.add(item)
                is TopLevel.Wrap -> result.add(item)
                is TopLevel.Node -> result.add(item)
                is TopLevel.View -> result.add(item)
                is TopLevel.Hook -> result.add(item)
                is TopLevel.UseImport -> result.add(item)
                is TopLevel.Impl -> result.add(item)
                is TopLevel.Spec -> result.add(item)
                is TopLevel.TypeAlias -> result.add(item)
                is TopLevel.Slot -> result.add(item)
                is TopLevel.InlineVar, is TopLevel.InlineFin, is TopLevel.InlineLet,
                is TopLevel.InlineAssignment -> {
                    val (resolved, _) = resolveTopLevelItem(item, program, errors)
                    result.addAll(resolved)
                }
                is TopLevel.InlineIf -> {
                    val (resolved, _) = resolveTopLevelItem(item, program, errors)
                    for (r in resolved) {
                        val (deep, _) = resolveTopLevelItem(r, program, errors)
                        result.addAll(deep)
                    }
                }
                is TopLevel.InlineBlock, is TopLevel.DeepInlineBlock, is TopLevel.DeepInlineIf -> {
                    val (resolved, _) = resolveTopLevelItem(item, program, errors)
                    result.addAll(resolved)
                }
                is TopLevel.Test -> result.add(item)
                is TopLevel.InlineAssert -> {
                    val (resolved, _) = resolveTopLevelItem(item, program, errors)
                    result.addAll(resolved)
                }
                is TopLevel.InlineTrace -> {
                    val (resolved, _) = resolveTopLevelItem(item, program, errors)
                    result.addAll(resolved)
                }
                // Macros are expanded (and removed) by MacroExpander before CTCE;
                // passthrough keeps the node intact if ever observed pre-expansion.
                is TopLevel.Meta -> result.add(item)
            }
        }
        return Pair(result, true)
    }

    // -- Body-level folding (handles inline if/fin expansion) ---------------

    private fun foldBody(body: List<Stmt>, program: Program, errors: MutableList<String>): Pair<List<Stmt>, Boolean> {
        var changed = false
        val result = mutableListOf<Stmt>()
        for (stmt in body) {
            val (stmts, wasChanged) = foldStmt(stmt, program, errors)
            if (wasChanged) changed = true
            result.addAll(stmts)
        }
        return Pair(result, changed)
    }

    private fun foldScopedBody(
        body: List<Stmt>,
        program: Program,
        errors: MutableList<String>,
    ): Pair<List<Stmt>, Boolean> {
        val savedInline = inlineEnv.toMap()
        val savedReflectionTypes = reflectionTypes.toMap()
        return try {
            foldBody(body, program, errors)
        } finally {
            inlineEnv.clear()
            inlineEnv.putAll(savedInline)
            reflectionTypes.clear()
            reflectionTypes.putAll(savedReflectionTypes)
        }
    }

    // -- Statement-level folding --------------------------------------------

    /**
     * Folds a statement. Returns a list because `inline if` can expand to
     * 0 or N statements (the taken branch's body replaces the inline if).
     */
    private fun foldStmt(stmt: Stmt, program: Program, errors: MutableList<String>): Pair<List<Stmt>, Boolean> {
        return when (stmt) {
            is Stmt.DeepInlineIf -> foldDeepInlineIf(stmt, program, errors)
            is Stmt.DeepInlineBlock -> foldDeepInlineBlock(stmt, program, errors)
            is Stmt.NoInline -> foldNoInline(stmt, program)
            is Stmt.InlineBlock -> foldInlineBlock(stmt, program, errors)
            is Stmt.InlineIf -> foldInlineIf(stmt, program, errors)
            is Stmt.InlineFor -> foldInlineFor(stmt, program, errors)
            is Stmt.InlineFin -> foldInlineConst(stmt.name, stmt.initializer, program, errors)
            is Stmt.InlineLet -> foldInlineConst(stmt.name, stmt.initializer, program, errors)
            is Stmt.InlineVar -> foldInlineConst(stmt.name, stmt.initializer, program, errors)
            is Stmt.InlineAssignment -> foldInlineAssignment(stmt, program, errors)
            is Stmt.If -> {
                val (newCond, condChanged) = foldExpr(stmt.condition, program)
                val (newThen, thenChanged) = foldScopedBody(stmt.thenBranch, program, errors)
                val (newElse, elseChanged) = if (stmt.elseBranch != null)
                    foldScopedBody(stmt.elseBranch, program, errors) else Pair(null, false)
                val changed = condChanged || thenChanged || elseChanged
                Pair(listOf(stmt.copy(condition = newCond, thenBranch = newThen, elseBranch = newElse)), changed)
            }
            is Stmt.VarDecl -> {
                recordReflectionType(stmt.name, stmt.type, stmt.initializer)
                val (newInit, changed) = foldExpr(stmt.initializer, program)
                Pair(listOf(stmt.copy(initializer = newInit)), changed)
            }
            is Stmt.FinDecl -> {
                recordReflectionType(stmt.name, stmt.type, stmt.initializer)
                val (newInit, changed) = foldExpr(stmt.initializer, program)
                Pair(listOf(stmt.copy(initializer = newInit)), changed)
            }
            is Stmt.LetDecl -> {
                recordReflectionType(stmt.name, stmt.type, stmt.initializer)
                val (newInit, changed) = foldExpr(stmt.initializer, program)
                Pair(listOf(stmt.copy(initializer = newInit)), changed)
            }
            is Stmt.Assignment -> {
                val (newValue, changed) = foldExpr(stmt.value, program)
                Pair(listOf(stmt.copy(value = newValue)), changed)
            }
            is Stmt.Return -> {
                if (stmt.value != null) {
                    val (newValue, changed) = foldExpr(stmt.value, program)
                    Pair(listOf(stmt.copy(value = newValue)), changed)
                } else {
                    Pair(listOf(stmt), false)
                }
            }
            is Stmt.ExprStmt -> {
                // Try inline function body substitution
                val inlined = tryInlineFuncCall(stmt.expr, program)
                if (inlined != null) return Pair(inlined, true)

                val (newExpr, changed) = foldExpr(stmt.expr, program)
                Pair(listOf(stmt.copy(expr = newExpr)), changed)
            }
            is Stmt.Zone -> {
                val (newBody, changed) = foldScopedBody(stmt.body, program, errors)
                Pair(listOf(stmt.copy(body = newBody)), changed)
            }
            is Stmt.FriendZone -> {
                val (newBody, changed) = foldScopedBody(stmt.body, program, errors)
                Pair(listOf(stmt.copy(body = newBody)), changed)
            }
            is Stmt.Assert -> {
                val (newCond, condChanged) = foldExpr(stmt.condition, program)
                val (newMsg, msgChanged) = foldExpr(stmt.message, program)
                Pair(listOf(stmt.copy(condition = newCond, message = newMsg)), condChanged || msgChanged)
            }
            is Stmt.Trace -> {
                val (newMsg, changed) = foldExpr(stmt.message, program)
                Pair(listOf(stmt.copy(message = newMsg)), changed)
            }
            is Stmt.InlineAssert -> {
                val (foldedCond, _) = foldCompileTimeExpr(stmt.condition, program)
                if (foldedCond is Expr.BoolLiteral) {
                    if (!foldedCond.value) {
                        val (foldedMsg, _) = foldExpr(stmt.message, program)
                        val msgText = if (foldedMsg is Expr.StringLiteral) foldedMsg.value else "inline assert failed"
                        errors.add("line ${stmt.line}: $msgText")
                    }
                    Pair(emptyList(), true)
                } else {
                    Pair(listOf(stmt), false)
                }
            }
            is Stmt.InlineTrace -> {
                val (foldedMsg, _) = foldExpr(stmt.message, program)
                if (foldedMsg is Expr.StringLiteral) {
                    errors.add("warning: [TRACE] ${foldedMsg.value}")
                    Pair(emptyList(), true)
                } else {
                    Pair(listOf(stmt), false)
                }
            }
            is Stmt.While -> {
                val (newCond, condChanged) = foldExpr(stmt.condition, program)
                val (newBody, bodyChanged) = foldScopedBody(stmt.body, program, errors)
                val changed = condChanged || bodyChanged
                Pair(listOf(if (changed) stmt.copy(condition = newCond, body = newBody) else stmt), changed)
            }
            is Stmt.For -> {
                val (newIter, iterChanged) = foldExpr(stmt.iterable, program)
                val (newBody, bodyChanged) = foldScopedBody(stmt.body, program, errors)
                val changed = iterChanged || bodyChanged
                Pair(listOf(if (changed) stmt.copy(iterable = newIter, body = newBody) else stmt), changed)
            }
            is Stmt.Loop -> {
                val (newBody, changed) = foldScopedBody(stmt.body, program, errors)
                Pair(listOf(if (changed) stmt.copy(body = newBody) else stmt), changed)
            }
            is Stmt.Break -> Pair(listOf(stmt), false)
            is Stmt.Continue -> Pair(listOf(stmt), false)
            is Stmt.IndexAssign -> {
                val (newTarget, tc) = foldExpr(stmt.target, program)
                val (newIndex, ic) = foldExpr(stmt.index, program)
                val (newValue, vc) = foldExpr(stmt.value, program)
                val changed = tc || ic || vc
                Pair(listOf(if (changed) stmt.copy(target = newTarget, index = newIndex, value = newValue) else stmt), changed)
            }
            is Stmt.DerefAssign -> {
                val (newTarget, tc) = foldExpr(stmt.target, program)
                val (newValue, vc) = foldExpr(stmt.value, program)
                val changed = tc || vc
                Pair(listOf(if (changed) stmt.copy(target = newTarget, value = newValue) else stmt), changed)
            }
            is Stmt.MemberAssign -> {
                val (newTarget, tc) = foldExpr(stmt.target, program)
                val (newValue, vc) = foldExpr(stmt.value, program)
                val changed = tc || vc
                Pair(listOf(if (changed) stmt.copy(target = newTarget, value = newValue) else stmt), changed)
            }
            is Stmt.When -> {
                var changed = false
                val (newScrut, sc) = foldExpr(stmt.scrutinee, program)
                if (sc) changed = true
                val newBranches = stmt.branches.map { b ->
                    val newPats = b.patterns.map { foldExpr(it, program) }
                    if (newPats.any { it.second }) changed = true
                    val (newBody, bc) = foldScopedBody(b.body, program, errors)
                    if (bc) changed = true
                    Stmt.WhenBranch(newPats.map { it.first }, newBody, b.line, b.column)
                }
                val (newElse, ec) = if (stmt.elseBranch != null) foldScopedBody(stmt.elseBranch, program, errors) else Pair(null, false)
                if (ec) changed = true
                Pair(listOf(if (changed) Stmt.When(newScrut, newBranches, newElse, stmt.line, stmt.column, stmt.length) else stmt), changed)
            }
            is Stmt.Throw -> {
                val (v, c) = foldExpr(stmt.value, program)
                Pair(listOf(if (c) Stmt.Throw(v, stmt.line, stmt.column, stmt.length) else stmt), c)
            }
            is Stmt.Panic -> {
                val (msg, c) = foldExpr(stmt.message, program)
                // `inline panic` is a compile-time abort: if reached during CTCE, stop the compiler.
                if (stmt.inlinePanic) {
                    val text = (msg as? Expr.StringLiteral)?.value ?: msg.toString()
                    error("inline panic: $text")
                }
                Pair(listOf(if (c) Stmt.Panic(msg, false, stmt.line, stmt.column, stmt.length) else stmt), c)
            }
            is Stmt.Yield -> {
                val (v, c) = foldExpr(stmt.value, program)
                Pair(listOf(if (c) Stmt.Yield(v, stmt.line, stmt.column, stmt.length) else stmt), c)
            }
            is Stmt.RemDecl -> {
                val (newInit, changed) = foldExpr(stmt.initializer, program)
                Pair(listOf(stmt.copy(initializer = newInit)), changed)
            }
            is Stmt.Effect -> {
                val (newBody, changed) = foldScopedBody(stmt.body, program, errors)
                Pair(listOf(stmt.copy(body = newBody)), changed)
            }
            is Stmt.Try -> {
                var changed = false
                val (newBody, bc) = foldScopedBody(stmt.body, program, errors)
                if (bc) changed = true
                val (newCatch, cc) = if (stmt.catchBody != null) foldScopedBody(stmt.catchBody, program, errors) else Pair(null, false)
                if (cc) changed = true
                Pair(listOf(if (changed) Stmt.Try(newBody, stmt.catchName, newCatch, stmt.line, stmt.column, stmt.length) else stmt), changed)
            }
            is Stmt.Defer -> {
                val (newBody, changed) = foldScopedBody(stmt.body, program, errors)
                Pair(listOf(if (changed) Stmt.Defer(newBody, stmt.line, stmt.column, stmt.length) else stmt), changed)
            }
        }
    }

    // -- inline var / fin -----------------------------------------------------

    /**
     * Evaluates `inline fin` or `inline var` at compile time.
     *
     * The initializer must fold to a compile-time constant. The binding is
     * removed from the AST and all references are substituted with the value.
     */
    // -- inline { ... } block ------------------------------------------------

    /**
     * Processes an `inline { ... }` block. Inside the block, all statements
     * are implicitly compile-time:
     *  - `var` / `fin` / `let` → stored in inlineEnv, removed from AST
     *  - `if` → evaluated at compile time, only taken branch survives
     *  - `x = expr` → updates inlineEnv
     *  - Runtime statements (e.g. ExprStmt) pass through to the output
     */
    // -- deepinline { ... } block ---------------------------------------------

    /**
     * Processes a `deepinline { ... }` block. Like `inline { }` but recursive:
     * nested `if` branches are also inlined. `noinline` escapes back to runtime.
     */
    private fun foldDeepInlineBlock(stmt: Stmt.DeepInlineBlock, program: Program, errors: MutableList<String>): Pair<List<Stmt>, Boolean> {
        val result = mutableListOf<Stmt>()
        for (inner in stmt.body) {
            val (stmts, _) = foldDeepInlineStmt(inner, program, errors)
            result.addAll(stmts)
        }
        return Pair(result, true)
    }

    /**
     * Folds a single statement inside a `deepinline { }` block.
     * Everything is compile-time. `if` branches are recursively deep-inlined.
     * `noinline` marks a statement as runtime.
     */
    private fun foldDeepInlineStmt(stmt: Stmt, program: Program, errors: MutableList<String>): Pair<List<Stmt>, Boolean> {
        return when (stmt) {
            // noinline cancels the inline — pass through with substitutions only
            is Stmt.NoInline -> {
                val (stmts, changed) = foldStmt(stmt.stmt, program, errors)
                Pair(stmts, changed)
            }

            // var/fin/let → compile-time binding
            is Stmt.VarDecl -> foldInlineConst(stmt.name, stmt.initializer, program, errors)
            is Stmt.FinDecl -> foldInlineConst(stmt.name, stmt.initializer, program, errors)
            is Stmt.LetDecl -> foldInlineConst(stmt.name, stmt.initializer, program, errors)

            // assignment → compile-time reassignment
            is Stmt.Assignment -> foldInlineAssignment(
                Stmt.InlineAssignment(stmt.name, stmt.value, stmt.line, stmt.column, stmt.length),
                program, errors
            )

            // if → evaluate condition, then deep-inline the taken branch
            is Stmt.If -> {
                val (foldedCond, _) = foldExpr(stmt.condition, program)
                if (foldedCond !is Expr.BoolLiteral) {
                    return Pair(listOf(stmt.copy(condition = foldedCond)), false)
                }
                if (foldedCond.value) {
                    val result = mutableListOf<Stmt>()
                    for (s in stmt.thenBranch) {
                        val (stmts, _) = foldDeepInlineStmt(s, program, errors)
                        result.addAll(stmts)
                    }
                    Pair(result, true)
                } else {
                    if (stmt.elseBranch != null) {
                        val result = mutableListOf<Stmt>()
                        for (s in stmt.elseBranch) {
                            val (stmts, _) = foldDeepInlineStmt(s, program, errors)
                            result.addAll(stmts)
                        }
                        Pair(result, true)
                    } else {
                        Pair(emptyList(), true)
                    }
                }
            }

            // Explicit inline nodes work as-is
            is Stmt.DeepInlineIf -> foldDeepInlineIf(stmt, program, errors)
            is Stmt.InlineIf -> foldInlineIf(stmt, program, errors)
            is Stmt.InlineFin -> foldInlineConst(stmt.name, stmt.initializer, program, errors)
            is Stmt.InlineLet -> foldInlineConst(stmt.name, stmt.initializer, program, errors)
            is Stmt.InlineVar -> foldInlineConst(stmt.name, stmt.initializer, program, errors)
            is Stmt.InlineAssignment -> foldInlineAssignment(stmt, program, errors)
            is Stmt.InlineBlock -> foldInlineBlock(stmt, program, errors)
            is Stmt.DeepInlineBlock -> foldDeepInlineBlock(stmt, program, errors)

            // Assert/Trace in deepinline → treated as inline assert/trace
            is Stmt.Assert -> foldStmt(Stmt.InlineAssert(stmt.condition, stmt.message, stmt.line, stmt.column, stmt.length), program, errors)
            is Stmt.Trace -> foldStmt(Stmt.InlineTrace(stmt.message, stmt.line, stmt.column, stmt.length), program, errors)
            is Stmt.InlineAssert -> foldStmt(stmt, program, errors)
            is Stmt.InlineTrace -> foldStmt(stmt, program, errors)

            // Everything else → runtime with substitutions applied
            else -> foldStmt(stmt, program, errors)
        }
    }

    // -- noinline --------------------------------------------------------------

    /**
     * `noinline` outside a deepinline block — just unwrap and process normally.
     */
    private fun foldNoInline(stmt: Stmt.NoInline, program: Program): Pair<List<Stmt>, Boolean> {
        return Pair(listOf(stmt.stmt), false)
    }

    private fun foldInlineBlock(stmt: Stmt.InlineBlock, program: Program, errors: MutableList<String>): Pair<List<Stmt>, Boolean> {
        val result = mutableListOf<Stmt>()
        var changed = false

        for (inner in stmt.body) {
            val (stmts, wasChanged) = foldInlineStmt(inner, program, errors)
            if (wasChanged) changed = true
            result.addAll(stmts)
        }

        return Pair(result, true) // always changed — the block itself is removed
    }

    /**
     * Folds a single statement inside an `inline { }` block.
     * Declarations and control flow are compile-time; other statements pass through.
     */
    private fun foldInlineStmt(stmt: Stmt, program: Program, errors: MutableList<String>): Pair<List<Stmt>, Boolean> {
        return when (stmt) {
            // var/fin/let inside inline block → compile-time binding
            is Stmt.VarDecl -> foldInlineConst(stmt.name, stmt.initializer, program, errors)
            is Stmt.FinDecl -> foldInlineConst(stmt.name, stmt.initializer, program, errors)
            is Stmt.LetDecl -> foldInlineConst(stmt.name, stmt.initializer, program, errors)

            // if inside inline block → compile-time conditional
            is Stmt.If -> foldInlineIf(
                Stmt.InlineIf(stmt.condition, stmt.thenBranch, stmt.elseBranch, stmt.line, stmt.column, stmt.length),
                program, errors
            )

            // assignment inside inline block → compile-time reassignment
            is Stmt.Assignment -> foldInlineAssignment(
                Stmt.InlineAssignment(stmt.name, stmt.value, stmt.line, stmt.column, stmt.length),
                program, errors
            )

            // Explicit inline statements work as-is
            is Stmt.InlineIf -> foldInlineIf(stmt, program, errors)
            is Stmt.InlineFin -> foldInlineConst(stmt.name, stmt.initializer, program, errors)
            is Stmt.InlineLet -> foldInlineConst(stmt.name, stmt.initializer, program, errors)
            is Stmt.InlineVar -> foldInlineConst(stmt.name, stmt.initializer, program, errors)
            is Stmt.InlineAssignment -> foldInlineAssignment(stmt, program, errors)
            is Stmt.InlineBlock -> foldInlineBlock(stmt, program, errors)

            // Assert/Trace inside inline block → treated as inline assert/trace
            is Stmt.Assert -> foldStmt(Stmt.InlineAssert(stmt.condition, stmt.message, stmt.line, stmt.column, stmt.length), program, errors)
            is Stmt.Trace -> foldStmt(Stmt.InlineTrace(stmt.message, stmt.line, stmt.column, stmt.length), program, errors)
            is Stmt.InlineAssert -> foldStmt(stmt, program, errors)
            is Stmt.InlineTrace -> foldStmt(stmt, program, errors)

            // Everything else (ExprStmt, Return, etc.) passes through as runtime code
            // but with inline substitutions applied
            else -> foldStmt(stmt, program, errors)
        }
    }

    private fun foldInlineConst(name: String, initializer: Expr, program: Program, errors: MutableList<String>): Pair<List<Stmt>, Boolean> {
        val (foldedInit, _) = foldCompileTimeExpr(initializer, program)

        if (!isConstant(foldedInit)) {
            return Pair(emptyList(), false)
        }

        inlineEnv[name] = foldedInit
        return Pair(emptyList(), true)
    }

    /**
     * Compile-time reassignment (`inline x = expr`).
     *
     * Updates the inline environment so subsequent `inline if` conditions
     * see the new value. Removed from the final AST.
     */
    private fun foldInlineAssignment(stmt: Stmt.InlineAssignment, program: Program, errors: MutableList<String>): Pair<List<Stmt>, Boolean> {
        if (stmt.name !in inlineEnv) {
            errors.add("line ${stmt.line}: inline assignment to undefined inline variable '${stmt.name}'")
            return Pair(emptyList(), false)
        }

        val (foldedValue, _) = foldCompileTimeExpr(stmt.value, program)

        if (!isConstant(foldedValue)) {
            errors.add("line ${stmt.line}: inline assignment value must be a compile-time constant")
            return Pair(emptyList(), false)
        }

        inlineEnv[stmt.name] = foldedValue
        return Pair(emptyList(), true)
    }

    // -- inline function body substitution -----------------------------------

    /**
     * If the expression is a call to an inline function, substitute the
     * function body at the call site (replacing parameters with arguments).
     * Returns the substituted statements, or null if not an inline call.
     */
    private fun tryInlineFuncCall(expr: Expr, program: Program): List<Stmt>? {
        if (expr !is Expr.Call) return null
        val funcDecl = program.functions.find { it.name == expr.callee && it.isInline } ?: return null

        // Substitute parameters with arguments in the function body
        val paramMap = mutableMapOf<String, Expr>()
        for (i in funcDecl.params.indices) {
            val arg = if (i < expr.args.size) expr.args[i] else return null
            paramMap[funcDecl.params[i].name] = arg
        }

        val substituted = funcDecl.body.map { substituteInStmt(it, paramMap) }
        // Wrap in a zone (scope block) to avoid variable name collisions
        return listOf(Stmt.Zone(substituted, expr.line))
    }

    private fun substituteInStmt(stmt: Stmt, paramMap: Map<String, Expr>): Stmt = when (stmt) {
        is Stmt.VarDecl -> stmt.copy(initializer = substituteInExpr(stmt.initializer, paramMap))
        is Stmt.FinDecl -> stmt.copy(initializer = substituteInExpr(stmt.initializer, paramMap))
        is Stmt.LetDecl -> stmt.copy(initializer = substituteInExpr(stmt.initializer, paramMap))
        is Stmt.Assignment -> stmt.copy(value = substituteInExpr(stmt.value, paramMap))
        is Stmt.Return -> stmt.copy(value = stmt.value?.let { substituteInExpr(it, paramMap) })
        is Stmt.ExprStmt -> stmt.copy(expr = substituteInExpr(stmt.expr, paramMap))
        is Stmt.If -> stmt.copy(
            condition = substituteInExpr(stmt.condition, paramMap),
            thenBranch = stmt.thenBranch.map { substituteInStmt(it, paramMap) },
            elseBranch = stmt.elseBranch?.map { substituteInStmt(it, paramMap) }
        )
        is Stmt.Assert -> stmt.copy(
            condition = substituteInExpr(stmt.condition, paramMap),
            message = substituteInExpr(stmt.message, paramMap)
        )
        is Stmt.Trace -> stmt.copy(message = substituteInExpr(stmt.message, paramMap))
        else -> stmt // inline constructs shouldn't appear in inline function bodies
    }

    private fun substituteInExpr(expr: Expr, paramMap: Map<String, Expr>): Expr = when (expr) {
        is Expr.Identifier -> paramMap[expr.name] ?: expr
        is Expr.Binary -> expr.copy(
            left = substituteInExpr(expr.left, paramMap),
            right = substituteInExpr(expr.right, paramMap)
        )
        is Expr.Unary -> expr.copy(operand = substituteInExpr(expr.operand, paramMap))
        is Expr.Call -> expr.copy(args = expr.args.map { substituteInExpr(it, paramMap) })
        is Expr.TryPropagate -> expr.copy(expr = substituteInExpr(expr.expr, paramMap))
        is Expr.Grouping -> expr.copy(expr = substituteInExpr(expr.expr, paramMap))
        else -> expr
    }

    // -- inline if (compile-time conditional) ---------------------------------

    /**
     * Evaluates `inline if` at compile time.
     *
     * The condition must be a compile-time constant.
     * Only the taken branch survives — the other is completely removed from
     * the AST (not even type-checked).
     */
    /**
     * `deepinline if` — evaluates the condition at compile time, then
     * deep-inlines the taken branch (all nested statements become compile-time).
     */
    private fun foldDeepInlineIf(stmt: Stmt.DeepInlineIf, program: Program, errors: MutableList<String>): Pair<List<Stmt>, Boolean> {
        val (foldedCond, _) = foldCompileTimeExpr(stmt.condition, program)

        if (foldedCond !is Expr.BoolLiteral) {
            return Pair(listOf(stmt.copy(condition = foldedCond)), false)
        }

        val branch = if (foldedCond.value) stmt.thenBranch else (stmt.elseBranch ?: emptyList())
        // Deep-inline the taken branch
        val result = mutableListOf<Stmt>()
        for (s in branch) {
            val (stmts, _) = foldDeepInlineStmt(s, program, errors)
            result.addAll(stmts)
        }
        return Pair(result, true)
    }

    private fun foldInlineIf(stmt: Stmt.InlineIf, program: Program, errors: MutableList<String>): Pair<List<Stmt>, Boolean> {
        // First, try to fold the condition to a constant
        val (foldedCond, _) = foldCompileTimeExpr(stmt.condition, program)

        if (foldedCond !is Expr.BoolLiteral) {
            // Condition is not (yet) a compile-time constant.
            // Return it unchanged — the fixed-point loop may resolve it
            // on a later iteration. If it never resolves, TypeResolver
            // will report the error.
            return Pair(listOf(stmt.copy(condition = foldedCond)), false)
        }

        // Condition resolved! Take the appropriate branch.
        return if (foldedCond.value) {
            // Condition is true — splice in the then-branch
            Pair(stmt.thenBranch, true)
        } else {
            // Condition is false — splice in the else-branch (or nothing)
            Pair(stmt.elseBranch ?: emptyList(), true)
        }
    }

    /**
     * Compile-time loop unrolling (`inline for x in a..b { body }`).
     *
     * The range bounds must fold to integer constants. For each value, the loop
     * variable is bound in [inlineEnv] (so `foldExpr` substitutes it) and the
     * body is folded and spliced into the enclosing scope. The node never survives
     * into semantic analysis.
     */
    private fun foldInlineFor(stmt: Stmt.InlineFor, program: Program, errors: MutableList<String>): Pair<List<Stmt>, Boolean> {
        val range = stmt.iterable as? Expr.Range
        if (range == null) {
            errors.add("line ${stmt.line}: inline for requires a literal range, got ${stmt.iterable::class.simpleName}")
            return Pair(emptyList(), false)
        }
        val (startExpr, _) = foldCompileTimeExpr(range.from, program)
        val (endExpr, _) = foldCompileTimeExpr(range.to, program)
        val start = (startExpr as? Expr.IntLiteral)?.value
        val end = (endExpr as? Expr.IntLiteral)?.value
        if (start == null || end == null) {
            // Bounds not yet constant — leave for the fixed-point loop / TypeResolver.
            return Pair(listOf(stmt), false)
        }
        val savedEnv = inlineEnv.toMap()
        val savedReflectionTypes = reflectionTypes.toMap()
        val result = mutableListOf<Stmt>()
        var i = start
        while (if (range.inclusive) i <= end else i < end) {
            inlineEnv[stmt.name] = Expr.IntLiteral(i, stmt.line)
            val (folded, _) = foldBody(stmt.body, program, errors)
            result.addAll(folded)
            i++
        }
        // Restore the CTCE environment: drop the loop variable and any inline
        // bindings local to the body so they don't leak past the unrolled loop.
        inlineEnv.clear()
        inlineEnv.putAll(savedEnv)
        reflectionTypes.clear()
        reflectionTypes.putAll(savedReflectionTypes)
        return Pair(result, true)
    }

    // -- Expression-level folding -------------------------------------------

    private fun recordReflectionType(name: String, annotation: TypeAnnotation, initializer: Expr) {
        val explicit = (annotation as? TypeAnnotation.Explicit)?.ref
        val type = when (explicit) {
            is TypeRef.Reference -> explicit.inner.displayName()
            null -> (initializer as? Expr.Call)?.callee
            else -> explicit.displayName()
        }
        if (type != null) reflectionTypes[name] = type
    }

    private fun foldExpr(expr: Expr, program: Program): Pair<Expr, Boolean> {
        return when (expr) {
            is Expr.Binary -> {
                val (left, lc) = foldExpr(expr.left, program)
                val (right, rc) = foldExpr(expr.right, program)
                val folded = tryFoldBinary(left, expr.op, right, expr.line)
                if (folded != null) Pair(folded, true)
                else Pair(expr.copy(left = left, right = right), lc || rc)
            }
            is Expr.Unary -> {
                val (operand, changed) = foldExpr(expr.operand, program)
                val folded = tryFoldUnary(expr.op, operand, expr.line)
                if (folded != null) Pair(folded, true)
                else Pair(expr.copy(operand = operand), changed)
            }
            is Expr.SetLiteral -> {
                val folded = expr.elements.map { foldExpr(it, program) }
                Pair(expr.copy(elements = folded.map { it.first }), folded.any { it.second })
            }
            is Expr.Call -> {
                if (compileTimeDepth > 0 && expr.callee == "__hasDeco") {
                    val decoratorName = expr.typeArgs.singleOrNull()?.displayName()
                    val receiver = expr.args.singleOrNull()
                    if (decoratorName == null || receiver == null) {
                        activeErrors?.add("line ${expr.line}: hasDeco requires exactly one receiver and decorator type")
                        return Pair(Expr.BoolLiteral(false, expr.line, expr.column), true)
                    }
                    val site = DecoratorMetadata.findSite(receiver, reflectionTypes, program)
                    if (site == null) {
                        activeErrors?.add("line ${expr.line}: hasDeco receiver is not compile-time declaration metadata")
                        return Pair(Expr.BoolLiteral(false, expr.line, expr.column), true)
                    }
                    val knownDecorator = program.items.filterIsInstance<TopLevel.Deco>().any { it.name == decoratorName }
                    if (!knownDecorator) {
                        activeErrors?.add("line ${expr.line}: unknown decorator '$decoratorName' in hasDeco")
                        return Pair(Expr.BoolLiteral(false, expr.line, expr.column), true)
                    }
                    val applied = DecoratorMetadata.findApplied(site, decoratorName, program) != null
                    return Pair(Expr.BoolLiteral(applied, expr.line, expr.column), true)
                }
                val foldedArgs = expr.args.map { foldExpr(it, program) }
                val anyChanged = foldedArgs.any { it.second }
                val newArgs = foldedArgs.map { it.first }

                // Try to evaluate the call at compile time if all args are constants
                val allConst = newArgs.all { isConstant(it) }
                if (allConst) {
                    val result = tryEvalCall(expr.callee, newArgs, program, expr.line)
                    if (result != null) return Pair(result, true)
                }

                Pair(expr.copy(args = newArgs), anyChanged)
            }
            is Expr.Grouping -> {
                val (inner, changed) = foldExpr(expr.expr, program)
                if (isConstant(inner)) Pair(inner, true)
                else Pair(expr.copy(expr = inner), changed)
            }
            // Literals and identifiers are already folded
            is Expr.Identifier -> {
                // Substitute inline fin bindings
                val inlineValue = inlineEnv[expr.name]
                if (inlineValue != null) Pair(inlineValue, true)
                else Pair(expr, false)
            }
            is Expr.UpperScopeAccess -> Pair(expr, false)
            // Literals are already folded
            is Expr.IntLiteral, is Expr.RealLiteral,
            is Expr.StringLiteral, is Expr.BoolLiteral,
            is Expr.CharLiteral,
            is Expr.TupleLit, is Expr.TupleAccess, is Expr.VariantLit -> Pair(expr, false)
            is Expr.IfExpr -> {
                val (condition, cc) = foldExpr(expr.condition, program)
                val (thenExpr, tc) = foldExpr(expr.thenExpr, program)
                val (elseExpr, ec) = foldExpr(expr.elseExpr, program)
                if (condition is Expr.BoolLiteral) {
                    Pair(if (condition.value) thenExpr else elseExpr, true)
                } else {
                    Pair(expr.copy(condition = condition, thenExpr = thenExpr, elseExpr = elseExpr), cc || tc || ec)
                }
            }
            is Expr.CatchExpr, is Expr.Lambda -> Pair(expr, false)
            is Expr.SafeMember -> {
                // `(reflect X).zone?.label` / `?.isInline` — fold the safe terminal.
                foldZoneTerminal(expr.target, expr.name, program, expr.line)?.let { return Pair(it, true) }
                Pair(expr, false)
            }
            is Expr.NullCoalesce -> {
                // Fold `a ?? b` when the left side is a known constant (e.g. a folded
                // reflection query): a non-null constant short-circuits to itself; a
                // literal null falls through to the right.
                val (left, lc) = foldExpr(expr.left, program)
                when {
                    left is Expr.NullLiteral -> { val (right, _) = foldExpr(expr.right, program); Pair(right, true) }
                    isConstant(left) -> Pair(left, true)
                    else -> Pair(if (lc) expr.copy(left = left) else expr, lc)
                }
            }
            is Expr.NamedArg, is Expr.NullLiteral, is Expr.Cast, is Expr.IsCheck, is Expr.MapLit, is Expr.Alloc, is Expr.AllocBuffer, is Expr.Deref, is Expr.Isolated, is Expr.Await, is Expr.Inject, is Expr.Spread -> Pair(expr, false)
            is Expr.TryPropagate -> {
                val (inner, changed) = foldExpr(expr.expr, program)
                Pair(if (changed) expr.copy(expr = inner) else expr, changed)
            }
            is Expr.Range -> {
                val (from, fc) = foldExpr(expr.from, program)
                val (to, tc) = foldExpr(expr.to, program)
                val changed = fc || tc
                Pair(if (changed) expr.copy(from = from, to = to) else expr, changed)
            }
            is Expr.ArrayLiteral -> {
                val folded = expr.elements.map { foldExpr(it, program) }
                val changed = folded.any { it.second }
                Pair(if (changed) expr.copy(elements = folded.map { it.first }) else expr, changed)
            }
            is Expr.Index -> {
                val (t, tc) = foldExpr(expr.target, program)
                val (i, ic) = foldExpr(expr.index, program)
                val changed = tc || ic
                Pair(if (changed) expr.copy(target = t, index = i) else expr, changed)
            }
            is Expr.Member -> {
                foldZoneTerminal(expr.target, expr.name, program, expr.line)?.let { return Pair(it, true) }
                val metadataQuery = expr.target as? Expr.Call
                if (compileTimeDepth > 0 && metadataQuery?.callee == "__decoMeta") {
                    val decoratorName = metadataQuery.typeArgs.singleOrNull()?.displayName()
                    val receiver = metadataQuery.args.singleOrNull()
                    val site = receiver?.let { DecoratorMetadata.findSite(it, reflectionTypes, program) }
                    val applied = if (site != null && decoratorName != null) {
                        DecoratorMetadata.findApplied(site, decoratorName, program)
                    } else null
                    if (applied == null) {
                        activeErrors?.add("line ${expr.line}: decorator '$decoratorName' is not applied to this declaration")
                        return Pair(Expr.BoolLiteral(false, expr.line, expr.column), true)
                    }
                    val value = DecoratorMetadata.fieldValue(applied, expr.name)
                    if (value == null) {
                        activeErrors?.add("line ${expr.line}: decorator '$decoratorName' has no field '${expr.name}' or the field has no value")
                        return Pair(Expr.BoolLiteral(false, expr.line, expr.column), true)
                    }
                    val (foldedValue, _) = foldCompileTimeExpr(value, program)
                    return Pair(foldedValue, true)
                }
                val (t, tc) = foldExpr(expr.target, program)
                Pair(if (tc) expr.copy(target = t) else expr, tc)
            }
            is Expr.MethodCall -> {
                val (t, tc) = foldExpr(expr.target, program)
                val foldedArgs = expr.args.map { foldExpr(it, program) }
                val changed = tc || foldedArgs.any { it.second }
                Pair(if (changed) expr.copy(target = t, args = foldedArgs.map { it.first }) else expr, changed)
            }
            is Expr.StringTemplate -> {
                var changed = false
                val newParts = expr.parts.map { part ->
                    if (part is Expr.StringTemplatePart.Expr) {
                        val (e, c) = foldExpr(part.expr, program)
                        if (c) changed = true
                        if (c) Expr.StringTemplatePart.Expr(e) else part
                    } else part
                }
                Pair(if (changed) expr.copy(parts = newParts) else expr, changed)
            }
            // Macros are expanded before CTCE; unreachable.
            is Expr.MetaInvoke -> Pair(expr, false)
        }
    }

    // -- Constant folding helpers -------------------------------------------

    private fun isConstant(expr: Expr): Boolean = when (expr) {
        is Expr.IntLiteral, is Expr.RealLiteral,
        is Expr.StringLiteral, is Expr.BoolLiteral,
        is Expr.CharLiteral -> true
        else -> false
    }

    // -- Zone reflection (`(reflect X).zone.label` / `.isInline` / `.zone`) ---

    /** The implicit top-level scope reported for globally-declared names. */
    private val globalZone = ZoneMeta("global", isInline = false, parent = null)

    /** A partially-evaluated `reflect` chain node used to fold zone queries. */
    private sealed interface ReflectNode {
        data class Decl(val name: String) : ReflectNode
        data class Zone(val meta: ZoneMeta?) : ReflectNode
    }

    /**
     * Evaluates a `reflect`-rooted expression to a [ReflectNode], or null if it is
     * not a reflection chain. `reflect X` → [ReflectNode.Decl]; `.zone` advances to
     * the declaration's zone (global by default) or an enclosing zone's parent.
     */
    private fun evalReflectNode(expr: Expr, program: Program): ReflectNode? = when (expr) {
        is Expr.Grouping -> evalReflectNode(expr.expr, program)
        is Expr.Call ->
            if (expr.callee == "__reflect")
                (expr.args.singleOrNull() as? Expr.Identifier)?.let { ReflectNode.Decl(it.name) }
            else null
        is Expr.Member -> stepReflectZone(evalReflectNode(expr.target, program), expr.name, program)
        is Expr.SafeMember -> stepReflectZone(evalReflectNode(expr.target, program), expr.name, program)
        else -> null
    }

    private fun stepReflectZone(base: ReflectNode?, member: String, program: Program): ReflectNode? {
        if (member != "zone") return null
        return when (base) {
            is ReflectNode.Decl -> ReflectNode.Zone(program.zones[base.name] ?: globalZone)
            is ReflectNode.Zone -> ReflectNode.Zone(base.meta?.parent)
            null -> null
        }
    }

    /**
     * Folds a terminal zone query `<zone>.label` / `<zone>.isInline` to a constant.
     * A missing zone (e.g. the global scope's parent, or a safe step off null)
     * yields `null` so `?:` fallbacks work. Returns null if [target] is not a zone
     * chain, so ordinary members named `label`/`isInline` are left untouched.
     */
    private fun foldZoneTerminal(target: Expr, member: String, program: Program, line: Int): Expr? {
        if (member != "label" && member != "isInline") return null
        val node = evalReflectNode(target, program) as? ReflectNode.Zone ?: return null
        return when (member) {
            "label" -> node.meta?.label?.let { Expr.StringLiteral(it, line) } ?: Expr.NullLiteral
            else -> node.meta?.let { Expr.BoolLiteral(it.isInline, line) } ?: Expr.NullLiteral
        }
    }

    private fun tryFoldBinary(left: Expr, op: TokenType, right: Expr, line: Int): Expr? {
        // Int op Int
        if (left is Expr.IntLiteral && right is Expr.IntLiteral) {
            return when (op) {
                TokenType.PLUS -> Expr.IntLiteral(left.value + right.value, line)
                TokenType.MINUS -> Expr.IntLiteral(left.value - right.value, line)
                TokenType.STAR -> Expr.IntLiteral(left.value * right.value, line)
                TokenType.SLASH -> if (right.value != 0L) Expr.IntLiteral(left.value / right.value, line) else null
                TokenType.PERCENT -> if (right.value != 0L) Expr.IntLiteral(left.value % right.value, line) else null
                TokenType.EQUAL_EQUAL -> Expr.BoolLiteral(left.value == right.value, line)
                TokenType.BANG_EQUAL -> Expr.BoolLiteral(left.value != right.value, line)
                TokenType.LESS -> Expr.BoolLiteral(left.value < right.value, line)
                TokenType.LESS_EQUAL -> Expr.BoolLiteral(left.value <= right.value, line)
                TokenType.GREATER -> Expr.BoolLiteral(left.value > right.value, line)
                TokenType.GREATER_EQUAL -> Expr.BoolLiteral(left.value >= right.value, line)
                else -> null
            }
        }
        // Real op Real
        if (left is Expr.RealLiteral && right is Expr.RealLiteral) {
            return when (op) {
                TokenType.PLUS -> Expr.RealLiteral(left.value + right.value, line)
                TokenType.MINUS -> Expr.RealLiteral(left.value - right.value, line)
                TokenType.STAR -> Expr.RealLiteral(left.value * right.value, line)
                TokenType.SLASH -> Expr.RealLiteral(left.value / right.value, line)
                TokenType.EQUAL_EQUAL -> Expr.BoolLiteral(left.value == right.value, line)
                TokenType.BANG_EQUAL -> Expr.BoolLiteral(left.value != right.value, line)
                TokenType.LESS -> Expr.BoolLiteral(left.value < right.value, line)
                TokenType.LESS_EQUAL -> Expr.BoolLiteral(left.value <= right.value, line)
                TokenType.GREATER -> Expr.BoolLiteral(left.value > right.value, line)
                TokenType.GREATER_EQUAL -> Expr.BoolLiteral(left.value >= right.value, line)
                else -> null
            }
        }
        // Bool op Bool
        if (left is Expr.BoolLiteral && right is Expr.BoolLiteral) {
            return when (op) {
                TokenType.AND_AND -> Expr.BoolLiteral(left.value && right.value, line)
                TokenType.OR_OR -> Expr.BoolLiteral(left.value || right.value, line)
                TokenType.EQUAL_EQUAL -> Expr.BoolLiteral(left.value == right.value, line)
                TokenType.BANG_EQUAL -> Expr.BoolLiteral(left.value != right.value, line)
                else -> null
            }
        }
        // String op String
        if (left is Expr.StringLiteral && right is Expr.StringLiteral) {
            return when (op) {
                TokenType.PLUS -> Expr.StringLiteral(left.value + right.value, line)
                TokenType.EQUAL_EQUAL -> Expr.BoolLiteral(left.value == right.value, line)
                TokenType.BANG_EQUAL -> Expr.BoolLiteral(left.value != right.value, line)
                else -> null
            }
        }
        // String * Int
        if (left is Expr.StringLiteral && right is Expr.IntLiteral && op == TokenType.STAR) {
            return Expr.StringLiteral(left.value.repeat(right.value.toInt()), line)
        }
        // Int * String
        if (left is Expr.IntLiteral && right is Expr.StringLiteral && op == TokenType.STAR) {
            return Expr.StringLiteral(right.value.repeat(left.value.toInt()), line)
        }
        return null
    }

    private fun tryFoldUnary(op: TokenType, operand: Expr, line: Int): Expr? {
        if (op == TokenType.MINUS && operand is Expr.IntLiteral) return Expr.IntLiteral(-operand.value, line)
        if (op == TokenType.MINUS && operand is Expr.RealLiteral) return Expr.RealLiteral(-operand.value, line)
        if (op == TokenType.BANG && operand is Expr.BoolLiteral) return Expr.BoolLiteral(!operand.value, line)
        return null
    }

    /**
     * Try to evaluate a function call at compile time by interpreting its body.
     * Only works for simple functions with constant arguments.
     */
    private fun tryEvalCall(name: String, args: List<Expr>, program: Program, line: Int): Expr? {
        val funcDecl = program.functions.find { it.name == name } ?: return null
        // Tasks/flows are execution boundaries, not pure value expressions. Folding
        // them would erase scheduling, cancellation, and Task<T> from the type graph.
        if (funcDecl.isTask || funcDecl.isFlow || funcDecl.isUnsafe) return null
        // Runtime intrinsics now live in std (std::println, std::convert::toString,
        // std::concurrency::async/channel/cancel). Their .az bodies are dead placeholders
        // (each backend/interpreter intercepts the call by mangled name); folding them at
        // compile time would either infinite-recurse (channel/async call themselves) or
        // silently substitute the placeholder result (toString -> ""). Never CTCE them.
        if (name in RUNTIME_INTRINSICS) return null
        if (args.size != funcDecl.params.size) return null // arg count mismatch — let TypeResolver report it

        // Build a compile-time environment: param name → constant value
        val env = mutableMapOf<String, Expr>()
        for (i in funcDecl.params.indices) {
            env[funcDecl.params[i].name] = args[i]
        }

        // Interpret the function body
        return interpretBody(funcDecl.body, env, program, line)
    }

    private fun interpretBody(body: List<Stmt>, env: MutableMap<String, Expr>, program: Program, line: Int): Expr? {
        for (stmt in body) {
            when (stmt) {
                is Stmt.VarDecl -> {
                    val value = evalExpr(stmt.initializer, env, program) ?: return null
                    env[stmt.name] = value
                }
                is Stmt.RemDecl -> return null // reactive state, not CTCE-evaluable
                is Stmt.Effect -> return null // effects are not CTCE-evaluable
                is Stmt.FinDecl -> {
                    val value = evalExpr(stmt.initializer, env, program) ?: return null
                    env[stmt.name] = value
                }
                is Stmt.Assignment -> {
                    val value = evalExpr(stmt.value, env, program) ?: return null
                    env[stmt.name] = value
                }
                is Stmt.Return -> {
                    return if (stmt.value != null) evalExpr(stmt.value, env, program) else null
                }
                is Stmt.ExprStmt -> {
                    // Side-effecting expressions can't be CTCE'd
                    return null
                }
                is Stmt.DerefAssign -> return null // pointer store is not CTCE-evaluable
                is Stmt.Yield -> return null // generators are not CTCE-evaluable
                is Stmt.If -> {
                    val cond = evalExpr(stmt.condition, env, program) ?: return null
                    if (cond !is Expr.BoolLiteral) return null
                    val branch = if (cond.value) stmt.thenBranch else (stmt.elseBranch ?: continue)
                    val result = interpretBody(branch, env, program, line)
                    if (result != null) return result
                }
                is Stmt.InlineIf -> {
                    val cond = evalExpr(stmt.condition, env, program) ?: return null
                    if (cond !is Expr.BoolLiteral) return null
                    val branch = if (cond.value) stmt.thenBranch else (stmt.elseBranch ?: continue)
                    val result = interpretBody(branch, env, program, line)
                    if (result != null) return result
                }
                is Stmt.InlineFor -> {
                    val range = stmt.iterable as? Expr.Range ?: return null
                    val s = (evalExpr(range.from, env, program) as? Expr.IntLiteral)?.value ?: return null
                    val e = (evalExpr(range.to, env, program) as? Expr.IntLiteral)?.value ?: return null
                    var i = s
                    while (if (range.inclusive) i <= e else i < e) {
                        env[stmt.name] = Expr.IntLiteral(i, stmt.line)
                        val result = interpretBody(stmt.body, env, program, line)
                        if (result != null) return result
                        i++
                    }
                }
                is Stmt.DeepInlineIf -> {
                    val cond = evalExpr(stmt.condition, env, program) ?: return null
                    if (cond !is Expr.BoolLiteral) return null
                    val branch = if (cond.value) stmt.thenBranch else (stmt.elseBranch ?: continue)
                    val result = interpretBody(branch, env, program, line)
                    if (result != null) return result
                }
                is Stmt.InlineFin -> {
                    val value = evalExpr(stmt.initializer, env, program) ?: return null
                    env[stmt.name] = value
                }
                is Stmt.InlineLet -> {
                    val value = evalExpr(stmt.initializer, env, program) ?: return null
                    env[stmt.name] = value
                }
                is Stmt.InlineVar -> {
                    val value = evalExpr(stmt.initializer, env, program) ?: return null
                    env[stmt.name] = value
                }
                is Stmt.InlineAssignment -> {
                    val value = evalExpr(stmt.value, env, program) ?: return null
                    env[stmt.name] = value
                }
                is Stmt.InlineBlock -> {
                    val result = interpretBody(stmt.body, env, program, line)
                    if (result != null) return result
                }
                is Stmt.DeepInlineBlock -> {
                    val result = interpretBody(stmt.body, env, program, line)
                    if (result != null) return result
                }
                is Stmt.NoInline -> {
                    // In CTCE interpretation, noinline is just pass-through
                    val result = interpretBody(listOf(stmt.stmt), env, program, line)
                    if (result != null) return result
                }
                is Stmt.LetDecl -> {
                    val value = evalExpr(stmt.initializer, env, program) ?: return null
                    env[stmt.name] = value
                }
                is Stmt.Zone -> {
                    val result = interpretBody(stmt.body, env, program, line)
                    if (result != null) return result
                }
                is Stmt.FriendZone -> {
                    val result = interpretBody(stmt.body, env, program, line)
                    if (result != null) return result
                }
                is Stmt.Assert -> return null // side-effecting — can't CTCE
                is Stmt.Trace -> return null // side-effecting — can't CTCE
                is Stmt.InlineAssert -> {
                    val cond = evalExpr(stmt.condition, env, program) ?: return null
                    if (cond is Expr.BoolLiteral && !cond.value) return null
                }
                is Stmt.InlineTrace -> {} // no-op during interpretation
                is Stmt.While, is Stmt.For, is Stmt.Loop,
                is Stmt.Break, is Stmt.Continue,
                is Stmt.IndexAssign, is Stmt.MemberAssign,
                is Stmt.When,
                is Stmt.Throw, is Stmt.Try, is Stmt.Panic,
                is Stmt.Defer -> return null // can't be evaluated at compile time
            }
        }
        return null
    }

    private fun evalExpr(expr: Expr, env: Map<String, Expr>, program: Program): Expr? {
        return when (expr) {
            is Expr.IntLiteral, is Expr.RealLiteral,
            is Expr.StringLiteral, is Expr.BoolLiteral,
            is Expr.CharLiteral -> expr
            is Expr.Identifier -> env[expr.name]
            is Expr.UpperScopeAccess -> env[expr.name]
            is Expr.Grouping -> evalExpr(expr.expr, env, program)
            is Expr.Range -> null // ranges are not CTCE-evaluable values
            is Expr.ArrayLiteral, is Expr.SetLiteral, is Expr.Index, is Expr.Member, is Expr.MethodCall -> null // not CTCE-evaluable
            is Expr.StringTemplate -> null // not CTCE-evaluable
            is Expr.TupleLit, is Expr.TupleAccess, is Expr.VariantLit -> null // not CTCE-evaluable
            is Expr.CatchExpr -> null // not CTCE-evaluable
            is Expr.TryPropagate -> evalExpr(expr.expr, env, program)
            is Expr.IfExpr -> {
                val condition = evalExpr(expr.condition, env, program) ?: return null
                if (condition !is Expr.BoolLiteral) return null
                if (condition.value) evalExpr(expr.thenExpr, env, program)
                else evalExpr(expr.elseExpr, env, program)
            }
            is Expr.Lambda -> null // not CTCE-evaluable
            is Expr.NamedArg -> null // not CTCE-evaluable
            is Expr.Unary -> {
                val operand = evalExpr(expr.operand, env, program) ?: return null
                tryFoldUnary(expr.op, operand, expr.line)
            }
            is Expr.Binary -> {
                val left = evalExpr(expr.left, env, program) ?: return null
                val right = evalExpr(expr.right, env, program) ?: return null
                tryFoldBinary(left, expr.op, right, expr.line)
            }
            is Expr.Call -> {
                val evalArgs = expr.args.map { evalExpr(it, env, program) ?: return null }
                tryEvalCall(expr.callee, evalArgs, program, expr.line)
            }
            is Expr.NullLiteral, is Expr.NullCoalesce, is Expr.SafeMember,
            is Expr.Cast, is Expr.IsCheck,
            is Expr.MapLit -> null
            is Expr.Alloc, is Expr.AllocBuffer, is Expr.Deref, is Expr.Isolated, is Expr.Await, is Expr.Inject, is Expr.Spread -> null // runtime ops, not CTCE-evaluable
            // Macros are expanded to concrete expressions by MacroExpander before
            // CTCE; a MetaInvoke reaching here means expansion was skipped.
            is Expr.MetaInvoke -> null
        }
    }

    companion object {
        /**
         * Functions declared in std whose real behaviour is supplied by the
         * backends/interpreter (call interception by mangled name). Their stdlib
         * bodies are placeholders and must never be compile-time evaluated.
         */
        val RUNTIME_INTRINSICS = setOf(
            "std__println",
            "std__convert__toString",
            "std__concurrency__async",
            "std__concurrency__channel",
            "std__concurrency__cancel",
        )
    }
}
