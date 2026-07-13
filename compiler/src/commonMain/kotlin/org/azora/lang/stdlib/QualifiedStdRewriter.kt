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

package org.azora.lang.stdlib

import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel

/**
 * Rewrites qualified bundled-library access into plain references:
 *
 * - `std::math::abs(x)` / `std::abs(x)` → `abs(x)`
 * - `math::abs(x)` after `use std.math` → `abs(x)`
 * - `std::math::PI` / `std::PI` → `PI`
 *
 * Every rewritten name is recorded in [required] so [StdlibInjector] injects
 * it without needing a `use` import. Only paths that actually resolve to a
 * known library symbol are rewritten — anything else (e.g. a user's own value
 * that happens to be named like a module) is left untouched, and the caller
 * disables shadowed roots when the user declares a top-level name such as `std`.
 */
internal class QualifiedStdRewriter(
    private val modules: Map<String, Map<String, TopLevel>>,
    private val roots: Set<String>,
    private val required: MutableSet<String>,
    private val moduleAliases: Map<String, String> = emptyMap(),
) {

    fun rewrite(program: Program): Program = program.copy(
        items = program.items.map { item ->
            when (item) {
                is TopLevel.Func -> item.copy(decl = item.decl.copy(body = item.decl.body.map(::stmt)))
                is TopLevel.Test -> item.copy(body = item.body.map(::stmt))
                is TopLevel.VarDecl -> item.copy(initializer = expr(item.initializer))
                is TopLevel.LetDecl -> item.copy(initializer = expr(item.initializer))
                is TopLevel.FinDecl -> item.copy(initializer = expr(item.initializer))
                else -> item
            }
        }
    )

    private fun resolveQualifiedName(name: String): String? {
        if ("__" !in name) return null
        val parts = name.split("__")
        if (parts.size < 2) return null

        val root = parts.first()
        if (root in roots) {
            if (parts.size == 2) {
                val item = parts[1]
                if (modules.values.any { item in it }) return item
            }
            for (itemStart in parts.lastIndex downTo 2) {
                val module = root + "." + parts.subList(1, itemStart).joinToString(".")
                val item = parts.subList(itemStart, parts.size).joinToString("__")
                if (modules[module]?.containsKey(item) == true) return item
            }
            return null
        }

        val aliasModule = moduleAliases[parts.first()] ?: return null
        for (itemStart in parts.lastIndex downTo 1) {
            val childPath = parts.subList(1, itemStart).joinToString(".")
            val module = if (childPath.isEmpty()) aliasModule else "$aliasModule.$childPath"
            val item = parts.subList(itemStart, parts.size).joinToString("__")
            if (modules[module]?.containsKey(item) == true) return item
        }
        return null
    }

    private fun resolvedQualifiedName(name: String): String? {
        val resolved = resolveQualifiedName(name) ?: return null
        required.add(resolved)
        return resolved
    }

    private fun stmt(s: Stmt): Stmt = when (s) {
        is Stmt.VarDecl -> s.copy(initializer = expr(s.initializer))
        is Stmt.LetDecl -> s.copy(initializer = expr(s.initializer))
        is Stmt.FinDecl -> s.copy(initializer = expr(s.initializer))
        is Stmt.Assignment -> s.copy(value = expr(s.value))
        is Stmt.IndexAssign -> s.copy(target = expr(s.target), index = expr(s.index), value = expr(s.value))
        is Stmt.MemberAssign -> s.copy(target = expr(s.target), value = expr(s.value))
        is Stmt.DerefAssign -> s.copy(target = expr(s.target), value = expr(s.value))
        is Stmt.Return -> s.copy(value = s.value?.let(::expr))
        is Stmt.ExprStmt -> s.copy(expr = expr(s.expr))
        is Stmt.Throw -> s.copy(value = expr(s.value))
        is Stmt.Yield -> s.copy(value = expr(s.value))
        is Stmt.Assert -> s.copy(condition = expr(s.condition), message = expr(s.message))
        is Stmt.Trace -> s.copy(message = expr(s.message))
        is Stmt.If -> s.copy(condition = expr(s.condition), thenBranch = s.thenBranch.map(::stmt), elseBranch = s.elseBranch?.map(::stmt))
        is Stmt.While -> s.copy(condition = expr(s.condition), body = s.body.map(::stmt))
        is Stmt.For -> s.copy(iterable = expr(s.iterable), step = s.step?.let(::expr), body = s.body.map(::stmt))
        is Stmt.Loop -> s.copy(body = s.body.map(::stmt))
        is Stmt.When -> s.copy(
            scrutinee = expr(s.scrutinee),
            branches = s.branches.map { it.copy(patterns = it.patterns.map(::expr), body = it.body.map(::stmt)) },
            elseBranch = s.elseBranch?.map(::stmt)
        )
        is Stmt.Try -> s.copy(body = s.body.map(::stmt), catchBody = s.catchBody?.map(::stmt))
        is Stmt.Defer -> s.copy(body = s.body.map(::stmt))
        is Stmt.Zone -> s.copy(body = s.body.map(::stmt))
        is Stmt.FriendZone -> s.copy(body = s.body.map(::stmt))
        else -> s
    }

    private fun expr(e: Expr): Expr = when (e) {
        is Expr.Identifier -> resolvedQualifiedName(e.name)?.let { e.copy(name = it) } ?: e
        is Expr.MethodCall -> e.copy(target = expr(e.target), args = e.args.map(::expr))
        is Expr.Member -> e.copy(target = expr(e.target))
        is Expr.Call -> {
            val args = e.args.map(::expr)
            resolvedQualifiedName(e.callee)?.let { e.copy(callee = it, args = args) } ?: e.copy(args = args)
        }
        is Expr.Binary -> e.copy(left = expr(e.left), right = expr(e.right))
        is Expr.Unary -> e.copy(operand = expr(e.operand))
        is Expr.Grouping -> e.copy(expr = expr(e.expr))
        is Expr.Index -> e.copy(target = expr(e.target), index = expr(e.index))
        is Expr.Range -> e.copy(from = expr(e.from), to = expr(e.to))
        is Expr.ArrayLiteral -> e.copy(elements = e.elements.map(::expr))
        is Expr.MapLit -> e.copy(entries = e.entries.map { (k, v) -> expr(k) to expr(v) })
        is Expr.TupleLit -> e.copy(elements = e.elements.map(::expr))
        is Expr.VariantLit -> e.copy(elements = e.elements.map(::expr))
        is Expr.TupleAccess -> e.copy(target = expr(e.target))
        is Expr.StringTemplate -> e.copy(parts = e.parts.map { part ->
            if (part is Expr.StringTemplatePart.Expr) Expr.StringTemplatePart.Expr(expr(part.expr)) else part
        })
        is Expr.Lambda -> e.copy(body = e.body.map(::stmt))
        is Expr.NamedArg -> e.copy(value = expr(e.value))
        is Expr.CatchExpr -> e.copy(expr = expr(e.expr), fallback = expr(e.fallback))
        is Expr.IfExpr -> e.copy(condition = expr(e.condition), thenExpr = expr(e.thenExpr), elseExpr = expr(e.elseExpr))
        is Expr.NullCoalesce -> e.copy(left = expr(e.left), right = expr(e.right))
        is Expr.Cast -> e.copy(expr = expr(e.expr))
        is Expr.IsCheck -> e.copy(expr = expr(e.expr))
        is Expr.SafeMember -> e.copy(target = expr(e.target))
        is Expr.Alloc -> e.copy(value = expr(e.value))
        is Expr.AllocBuffer -> e.copy(count = expr(e.count))
        is Expr.Deref -> e.copy(target = expr(e.target))
        is Expr.Isolated -> e.copy(value = expr(e.value))
        is Expr.Await -> e.copy(value = expr(e.value))
        is Expr.Spread -> e.copy(array = expr(e.array))
        else -> e
    }
}
