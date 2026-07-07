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

package org.azora.lang.stdlib

import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel

/**
 * Injects standard-library declarations into a user compilation unit.
 *
 * The stdlib sources embedded in [AzStdlib] are parsed once and indexed by
 * top-level name. [inject] walks the user program for referenced names and
 * appends only the stdlib items actually used (functions, constants, packs,
 * enums, and the extern `bridge` signatures their bodies call), following
 * references transitively — so `println(abs(-5))` pulls in `abs` and nothing
 * else, and programs that never touch the stdlib compile exactly as before.
 *
 * A user declaration always shadows the stdlib item of the same name; the
 * stdlib version is simply not injected.
 */
object StdlibInjector {

    private class Index {
        /** name → the item providing it (function, binding, pack, or enum). */
        val items = LinkedHashMap<String, TopLevel>()
        /** extern name → single-signature bridge declaring it. */
        val externs = LinkedHashMap<String, TopLevel.Bridge>()
    }

    private val index: Index by lazy { buildIndex() }

    private fun buildIndex(): Index {
        val idx = Index()
        for (program in AzStdlib.loadPrograms()) {
            for (item in program.items) {
                when (item) {
                    is TopLevel.Func -> idx.items.putIfAbsent(item.decl.name, item)
                    is TopLevel.FinDecl -> idx.items.putIfAbsent(item.name, item)
                    is TopLevel.LetDecl -> idx.items.putIfAbsent(item.name, item)
                    is TopLevel.VarDecl -> idx.items.putIfAbsent(item.name, item)
                    is TopLevel.Pack -> idx.items.putIfAbsent(item.name, item)
                    is TopLevel.Enum -> idx.items.putIfAbsent(item.name, item)
                    is TopLevel.Fail -> idx.items.putIfAbsent(item.name, item)
                    is TopLevel.Bridge -> for (sig in item.funcs) {
                        idx.externs.putIfAbsent(sig.name, TopLevel.Bridge(item.target, listOf(sig), item.line, item.column))
                    }
                    else -> {}
                }
            }
        }
        return idx
    }

    /** Names declared at the top level of the user [program] (these shadow the stdlib). */
    private fun userDeclaredNames(program: Program): Set<String> {
        val names = mutableSetOf<String>()
        for (item in program.items) {
            when (item) {
                is TopLevel.Func -> names.add(item.decl.name)
                is TopLevel.FinDecl -> names.add(item.name)
                is TopLevel.LetDecl -> names.add(item.name)
                is TopLevel.VarDecl -> names.add(item.name)
                is TopLevel.Pack -> names.add(item.name)
                is TopLevel.Enum -> names.add(item.name)
                is TopLevel.Fail -> names.add(item.name)
                is TopLevel.Bridge -> item.funcs.forEach { names.add(it.name) }
                else -> {}
            }
        }
        return names
    }

    /**
     * Returns [program] with every stdlib item it (transitively) references
     * appended. Returns the program unchanged when nothing is referenced.
     */
    fun inject(program: Program): Program {
        if (index.items.isEmpty() && index.externs.isEmpty()) return program

        val shadowed = userDeclaredNames(program)
        val referenced = mutableSetOf<String>()
        for (item in program.items) collectNamesFromItem(item, referenced)

        val injected = LinkedHashMap<String, TopLevel>()
        val injectedExterns = LinkedHashMap<String, TopLevel>()
        var frontier = referenced.toList()
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<String>()
            for (name in frontier) {
                if (name in shadowed || name in injected) continue
                val item = index.items[name]
                if (item != null) {
                    injected[name] = item
                    val transitive = mutableSetOf<String>()
                    collectNamesFromItem(item, transitive)
                    next.addAll(transitive)
                    continue
                }
                if (name !in injectedExterns) {
                    index.externs[name]?.let { injectedExterns[name] = it }
                }
            }
            frontier = next
        }

        if (injected.isEmpty() && injectedExterns.isEmpty()) return program
        return program.copy(items = program.items + injected.values + injectedExterns.values)
    }

    // -----------------------------------------------------------------
    // Reference collection
    // -----------------------------------------------------------------

    private fun collectNamesFromItem(item: TopLevel, names: MutableSet<String>) {
        when (item) {
            is TopLevel.Func -> item.decl.body.forEach { collectNamesFromStmt(it, names) }
            is TopLevel.FinDecl -> collectNamesFromExpr(item.initializer, names)
            is TopLevel.LetDecl -> collectNamesFromExpr(item.initializer, names)
            is TopLevel.VarDecl -> collectNamesFromExpr(item.initializer, names)
            is TopLevel.Test -> item.body.forEach { collectNamesFromStmt(it, names) }
            is TopLevel.Solo -> item.methods.forEach { m -> m.body.forEach { collectNamesFromStmt(it, names) } }
            is TopLevel.Impl -> item.methods.forEach { m -> m.body.forEach { collectNamesFromStmt(it, names) } }
            else -> {}
        }
    }

    private fun collectNamesFromStmt(stmt: Stmt, names: MutableSet<String>) {
        when (stmt) {
            is Stmt.VarDecl -> collectNamesFromExpr(stmt.initializer, names)
            is Stmt.FinDecl -> collectNamesFromExpr(stmt.initializer, names)
            is Stmt.LetDecl -> collectNamesFromExpr(stmt.initializer, names)
            is Stmt.InlineVar -> collectNamesFromExpr(stmt.initializer, names)
            is Stmt.InlineFin -> collectNamesFromExpr(stmt.initializer, names)
            is Stmt.InlineLet -> collectNamesFromExpr(stmt.initializer, names)
            is Stmt.RemDecl -> collectNamesFromExpr(stmt.initializer, names)
            is Stmt.Assignment -> collectNamesFromExpr(stmt.value, names)
            is Stmt.InlineAssignment -> collectNamesFromExpr(stmt.value, names)
            is Stmt.IndexAssign -> {
                collectNamesFromExpr(stmt.target, names)
                collectNamesFromExpr(stmt.index, names)
                collectNamesFromExpr(stmt.value, names)
            }
            is Stmt.MemberAssign -> {
                collectNamesFromExpr(stmt.target, names)
                collectNamesFromExpr(stmt.value, names)
            }
            is Stmt.DerefAssign -> {
                collectNamesFromExpr(stmt.target, names)
                collectNamesFromExpr(stmt.value, names)
            }
            is Stmt.Return -> stmt.value?.let { collectNamesFromExpr(it, names) }
            is Stmt.ExprStmt -> collectNamesFromExpr(stmt.expr, names)
            is Stmt.Throw -> collectNamesFromExpr(stmt.value, names)
            is Stmt.Yield -> collectNamesFromExpr(stmt.value, names)
            is Stmt.Assert -> {
                collectNamesFromExpr(stmt.condition, names)
                collectNamesFromExpr(stmt.message, names)
            }
            is Stmt.InlineAssert -> {
                collectNamesFromExpr(stmt.condition, names)
                collectNamesFromExpr(stmt.message, names)
            }
            is Stmt.Trace -> collectNamesFromExpr(stmt.message, names)
            is Stmt.InlineTrace -> collectNamesFromExpr(stmt.message, names)
            is Stmt.If -> {
                collectNamesFromExpr(stmt.condition, names)
                stmt.thenBranch.forEach { collectNamesFromStmt(it, names) }
                stmt.elseBranch?.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.InlineIf -> {
                collectNamesFromExpr(stmt.condition, names)
                stmt.thenBranch.forEach { collectNamesFromStmt(it, names) }
                stmt.elseBranch?.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.DeepInlineIf -> {
                collectNamesFromExpr(stmt.condition, names)
                stmt.thenBranch.forEach { collectNamesFromStmt(it, names) }
                stmt.elseBranch?.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.While -> {
                collectNamesFromExpr(stmt.condition, names)
                stmt.body.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.For -> {
                collectNamesFromExpr(stmt.iterable, names)
                stmt.step?.let { collectNamesFromExpr(it, names) }
                stmt.body.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.InlineFor -> {
                collectNamesFromExpr(stmt.iterable, names)
                stmt.body.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.Loop -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.When -> {
                collectNamesFromExpr(stmt.scrutinee, names)
                stmt.branches.forEach { b ->
                    b.patterns.forEach { collectNamesFromExpr(it, names) }
                    b.body.forEach { collectNamesFromStmt(it, names) }
                }
                stmt.elseBranch?.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.Try -> {
                stmt.body.forEach { collectNamesFromStmt(it, names) }
                stmt.catchBody?.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.Defer -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.Zone -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.FriendZone -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.InlineBlock -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.DeepInlineBlock -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.Effect -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.NoInline -> collectNamesFromStmt(stmt.stmt, names)
            is Stmt.Break, is Stmt.Continue -> {}
        }
    }

    private fun collectNamesFromExpr(expr: Expr, names: MutableSet<String>) {
        when (expr) {
            is Expr.Identifier -> {
                val item = index.items[expr.name]
                if (item != null && item !is TopLevel.Func) names.add(expr.name)
            }
            is Expr.Call -> {
                names.add(expr.callee)
                expr.args.forEach { collectNamesFromExpr(it, names) }
            }
            is Expr.MethodCall -> {
                collectNamesFromExpr(expr.target, names)
                expr.args.forEach { collectNamesFromExpr(it, names) }
            }
            is Expr.Binary -> {
                collectNamesFromExpr(expr.left, names)
                collectNamesFromExpr(expr.right, names)
            }
            is Expr.Unary -> collectNamesFromExpr(expr.operand, names)
            is Expr.Grouping -> collectNamesFromExpr(expr.expr, names)
            is Expr.Member -> collectNamesFromExpr(expr.target, names)
            is Expr.SafeMember -> collectNamesFromExpr(expr.target, names)
            is Expr.Index -> {
                collectNamesFromExpr(expr.target, names)
                collectNamesFromExpr(expr.index, names)
            }
            is Expr.Range -> {
                collectNamesFromExpr(expr.from, names)
                collectNamesFromExpr(expr.to, names)
            }
            is Expr.ArrayLiteral -> expr.elements.forEach { collectNamesFromExpr(it, names) }
            is Expr.MapLit -> expr.entries.forEach { (k, v) ->
                collectNamesFromExpr(k, names)
                collectNamesFromExpr(v, names)
            }
            is Expr.TupleLit -> expr.elements.forEach { collectNamesFromExpr(it, names) }
            is Expr.TupleAccess -> collectNamesFromExpr(expr.target, names)
            is Expr.StringTemplate -> expr.parts.forEach { part ->
                if (part is Expr.StringTemplatePart.Expr) collectNamesFromExpr(part.expr, names)
            }
            is Expr.Lambda -> expr.body.forEach { collectNamesFromStmt(it, names) }
            is Expr.NamedArg -> collectNamesFromExpr(expr.value, names)
            is Expr.CatchExpr -> {
                collectNamesFromExpr(expr.expr, names)
                collectNamesFromExpr(expr.fallback, names)
            }
            is Expr.IfExpr -> {
                collectNamesFromExpr(expr.condition, names)
                collectNamesFromExpr(expr.thenExpr, names)
                collectNamesFromExpr(expr.elseExpr, names)
            }
            is Expr.NullCoalesce -> {
                collectNamesFromExpr(expr.left, names)
                collectNamesFromExpr(expr.right, names)
            }
            is Expr.Cast -> collectNamesFromExpr(expr.expr, names)
            is Expr.IsCheck -> collectNamesFromExpr(expr.expr, names)
            is Expr.Alloc -> collectNamesFromExpr(expr.value, names)
            is Expr.Deref -> collectNamesFromExpr(expr.target, names)
            is Expr.Isolated -> collectNamesFromExpr(expr.value, names)
            is Expr.Await -> collectNamesFromExpr(expr.value, names)
            is Expr.Spread -> collectNamesFromExpr(expr.array, names)
            else -> {}
        }
    }
}
