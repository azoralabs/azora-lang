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
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt

/**
 * Semantic — Effect Checking.
 *
 * Runs AFTER types are fully resolved (post-CTFE stabilization) because
 * CTFE-generated functions carry effects too.
 *
 * Responsibilities (when the language grows):
 *  - Validate `effect` annotations: ensure functions only perform
 *    declared side effects.
 *  - Purity analysis: a function marked pure must not call impure functions.
 *  - Effect propagation: if `f` calls `g`, and `g` has effect `IO`,
 *    then `f` must also declare `IO` (or be inferred to have it).
 *
 * In the current minimal language (no effect system), this pass:
 *  - Detects functions that call unknown/external functions (potential side effects).
 *  - Classifies functions as pure (only calls known pure functions with no
 *    side-effecting expressions) or impure.
 */
class EffectChecker {

    /**
     * Classifies a function's side-effect status.
     *
     * - [PURE] -- the function only calls known pure functions
     * - [IMPURE] -- the function calls external or impure functions
     */
    enum class Effect { PURE, IMPURE }

    /**
     * Associates a function with its inferred effect classification.
     *
     * @property functionName the name of the function
     * @property effect the inferred effect classification
     */
    data class EffectInfo(
        val functionName: String,
        val effect: Effect
    )

    /**
     * Result of the effect checking pass.
     *
     * @property effects the per-function effect classifications
     * @property errors any errors encountered during analysis
     */
    data class EffectResult(
        val effects: List<EffectInfo>,
        val errors: List<String>
    )

    /**
     * Performs effect analysis on all functions in the program.
     *
     * Uses a two-pass algorithm: first identifies functions with external calls
     * as impure, then propagates impurity through the call graph until stable.
     *
     * @param program the CTFE-stabilized, type-checked AST to analyze
     * @return an [EffectResult] containing per-function effect info and any errors
     */
    fun check(program: Program): EffectResult {
        val errors = mutableListOf<String>()
        val knownFunctions = program.functions.map { it.name }.toSet()
        val effectMap = mutableMapOf<String, Effect>()

        // First pass: identify functions that call external (unknown) functions
        for (func in program.functions) {
            val calls = collectCalls(func.body)
            val hasExternalCall = calls.any { it !in knownFunctions }
            effectMap[func.name] = if (hasExternalCall) Effect.IMPURE else Effect.PURE
        }

        // Second pass: propagate impurity — if a pure function calls an impure one,
        // it becomes impure too. Iterate until stable.
        var changed = true
        while (changed) {
            changed = false
            for (func in program.functions) {
                if (effectMap[func.name] == Effect.IMPURE) continue
                val calls = collectCalls(func.body)
                if (calls.any { effectMap[it] == Effect.IMPURE }) {
                    effectMap[func.name] = Effect.IMPURE
                    changed = true
                }
            }
        }

        val effects = effectMap.map { (name, effect) -> EffectInfo(name, effect) }
        return EffectResult(effects, errors)
    }

    private fun collectCalls(body: List<Stmt>): Set<String> {
        val calls = mutableSetOf<String>()
        for (stmt in body) {
            collectCallsFromStmt(stmt, calls)
        }
        return calls
    }

    private fun collectCallsFromStmt(stmt: Stmt, calls: MutableSet<String>) {
        when (stmt) {
            is Stmt.VarDecl -> collectCallsFromExpr(stmt.initializer, calls)
            is Stmt.RemDecl -> collectCallsFromExpr(stmt.initializer, calls)
            is Stmt.Effect -> stmt.body.forEach { collectCallsFromStmt(it, calls) }
            is Stmt.FinDecl -> collectCallsFromExpr(stmt.initializer, calls)
            is Stmt.Assignment -> collectCallsFromExpr(stmt.value, calls)
            is Stmt.Return -> stmt.value?.let { collectCallsFromExpr(it, calls) }
            is Stmt.ExprStmt -> collectCallsFromExpr(stmt.expr, calls)
            is Stmt.If -> {
                collectCallsFromExpr(stmt.condition, calls)
                stmt.thenBranch.forEach { collectCallsFromStmt(it, calls) }
                stmt.elseBranch?.forEach { collectCallsFromStmt(it, calls) }
            }
            is Stmt.InlineIf -> {
                collectCallsFromExpr(stmt.condition, calls)
                stmt.thenBranch.forEach { collectCallsFromStmt(it, calls) }
                stmt.elseBranch?.forEach { collectCallsFromStmt(it, calls) }
            }
            is Stmt.DeepInlineIf -> {
                collectCallsFromExpr(stmt.condition, calls)
                stmt.thenBranch.forEach { collectCallsFromStmt(it, calls) }
                stmt.elseBranch?.forEach { collectCallsFromStmt(it, calls) }
            }
            is Stmt.DeepInlineBlock -> stmt.body.forEach { collectCallsFromStmt(it, calls) }
            is Stmt.NoInline -> collectCallsFromStmt(stmt.stmt, calls)
            is Stmt.InlineBlock -> stmt.body.forEach { collectCallsFromStmt(it, calls) }
            is Stmt.InlineFor -> {
                collectCallsFromExpr(stmt.iterable, calls)
                stmt.body.forEach { collectCallsFromStmt(it, calls) }
            }
            is Stmt.InlineFin -> collectCallsFromExpr(stmt.initializer, calls)
            is Stmt.InlineLet -> collectCallsFromExpr(stmt.initializer, calls)
            is Stmt.InlineVar -> collectCallsFromExpr(stmt.initializer, calls)
            is Stmt.InlineAssignment -> collectCallsFromExpr(stmt.value, calls)
            is Stmt.LetDecl -> {
                collectCallsFromExpr(stmt.initializer, calls)
            }
            is Stmt.Zone -> stmt.body.forEach { collectCallsFromStmt(it, calls) }
            is Stmt.FriendZone -> stmt.body.forEach { collectCallsFromStmt(it, calls) }
            is Stmt.Assert -> {
                collectCallsFromExpr(stmt.condition, calls)
                collectCallsFromExpr(stmt.message, calls)
            }
            is Stmt.Trace -> collectCallsFromExpr(stmt.message, calls)
            is Stmt.InlineAssert -> {
                collectCallsFromExpr(stmt.condition, calls)
                collectCallsFromExpr(stmt.message, calls)
            }
            is Stmt.InlineTrace -> collectCallsFromExpr(stmt.message, calls)
            is Stmt.While -> {
                collectCallsFromExpr(stmt.condition, calls)
                stmt.body.forEach { collectCallsFromStmt(it, calls) }
            }
            is Stmt.For -> {
                if (stmt.iterable is Expr.Range) {
                    collectCallsFromExpr(stmt.iterable.from, calls)
                    collectCallsFromExpr(stmt.iterable.to, calls)
                } else {
                    collectCallsFromExpr(stmt.iterable, calls)
                }
                stmt.body.forEach { collectCallsFromStmt(it, calls) }
            }
            is Stmt.Loop -> stmt.body.forEach { collectCallsFromStmt(it, calls) }
            is Stmt.Break -> {}
            is Stmt.Continue -> {}
            is Stmt.IndexAssign -> {
                collectCallsFromExpr(stmt.target, calls)
                collectCallsFromExpr(stmt.index, calls)
                collectCallsFromExpr(stmt.value, calls)
            }
            is Stmt.MemberAssign -> {
                collectCallsFromExpr(stmt.target, calls)
                collectCallsFromExpr(stmt.value, calls)
            }
            is Stmt.DerefAssign -> {
                collectCallsFromExpr(stmt.target, calls)
                collectCallsFromExpr(stmt.value, calls)
            }
            is Stmt.When -> {
                collectCallsFromExpr(stmt.scrutinee, calls)
                for (branch in stmt.branches) {
                    branch.patterns.forEach { collectCallsFromExpr(it, calls) }
                    branch.body.forEach { collectCallsFromStmt(it, calls) }
                }
                stmt.elseBranch?.forEach { collectCallsFromStmt(it, calls) }
            }
            is Stmt.Throw -> collectCallsFromExpr(stmt.value, calls)
            is Stmt.Yield -> collectCallsFromExpr(stmt.value, calls)
            is Stmt.Try -> {
                stmt.body.forEach { collectCallsFromStmt(it, calls) }
                stmt.catchBody?.forEach { collectCallsFromStmt(it, calls) }
            }
            is Stmt.Defer -> stmt.body.forEach { collectCallsFromStmt(it, calls) }
        }
    }

    private fun collectCallsFromExpr(expr: Expr, calls: MutableSet<String>) {
        when (expr) {

            is Expr.Call -> {
                calls.add(expr.callee)
                expr.args.forEach { collectCallsFromExpr(it, calls) }
            }
            is Expr.Binary -> { collectCallsFromExpr(expr.left, calls); collectCallsFromExpr(expr.right, calls) }
            is Expr.Unary -> collectCallsFromExpr(expr.operand, calls)
            is Expr.Grouping -> collectCallsFromExpr(expr.expr, calls)
            is Expr.Range -> { collectCallsFromExpr(expr.from, calls); collectCallsFromExpr(expr.to, calls) }
            is Expr.ArrayLiteral -> expr.elements.forEach { collectCallsFromExpr(it, calls) }
            is Expr.Index -> { collectCallsFromExpr(expr.target, calls); collectCallsFromExpr(expr.index, calls) }
            is Expr.Member -> collectCallsFromExpr(expr.target, calls)
            is Expr.MethodCall -> { collectCallsFromExpr(expr.target, calls); expr.args.forEach { collectCallsFromExpr(it, calls) } }
            is Expr.StringTemplate -> {
                for (part in expr.parts) {
                    if (part is Expr.StringTemplatePart.Expr) collectCallsFromExpr(part.expr, calls)
                }
            }
            is Expr.TupleLit -> expr.elements.forEach { collectCallsFromExpr(it, calls) }
            is Expr.TupleAccess -> collectCallsFromExpr(expr.target, calls)
            is Expr.CatchExpr -> { collectCallsFromExpr(expr.expr, calls); collectCallsFromExpr(expr.fallback, calls) }
            is Expr.IfExpr -> { collectCallsFromExpr(expr.condition, calls); collectCallsFromExpr(expr.thenExpr, calls); collectCallsFromExpr(expr.elseExpr, calls) }
            is Expr.Lambda -> {
                for (s in expr.body) collectCallsFromStmt(s, calls)
            }
            is Expr.NamedArg -> collectCallsFromExpr(expr.value, calls)
            is Expr.NullLiteral -> {}
            is Expr.NullCoalesce -> { collectCallsFromExpr(expr.left, calls); collectCallsFromExpr(expr.right, calls) }
            is Expr.Cast -> collectCallsFromExpr(expr.expr, calls)
            is Expr.IsCheck -> collectCallsFromExpr(expr.expr, calls)
            is Expr.MapLit -> { for ((k, v) in expr.entries) { collectCallsFromExpr(k, calls); collectCallsFromExpr(v, calls) } }
            is Expr.Alloc -> collectCallsFromExpr(expr.value, calls)
            is Expr.Deref -> collectCallsFromExpr(expr.target, calls)
            is Expr.Isolated -> collectCallsFromExpr(expr.value, calls)
            is Expr.Await -> collectCallsFromExpr(expr.value, calls)
            is Expr.Inject -> { /* no sub-expressions to collect */ }
            is Expr.Spread -> collectCallsFromExpr(expr.array, calls)
            is Expr.SafeMember -> collectCallsFromExpr(expr.target, calls)
            is Expr.IntLiteral, is Expr.RealLiteral,
            is Expr.StringLiteral, is Expr.BoolLiteral,
            is Expr.CharLiteral,
            is Expr.Identifier, is Expr.UpperScopeAccess -> {}
        }
    }
}
