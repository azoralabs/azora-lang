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
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import org.azora.lang.ir.sourceSymbol

/**
 * Keeps `@std::SignatureOnly` out of the bodies that must declare their access.
 *
 * A decorator marked `@std::DeclaresAccess` promises that everything a function
 * carrying it touches is written in its parameters. A `@std::SignatureOnly`
 * function breaks that promise: it hands back something the signature never
 * mentioned. So one may not be reached from the other, and what it returns is
 * asked for as a parameter instead.
 *
 * The reach is *transitive*. A rule that stopped at direct calls would be one
 * helper deep - `func grab(w: World&) = resource<Time>(w)` - and a reader of the
 * signature, or a scheduler deciding from signatures which functions may run
 * beside each other, would still be told the wrong thing.
 *
 * Both decorators are std's. The compiler learns no library's names: a library
 * says which of *its* functions are signature-only and which of its decorators
 * mark a boundary, and this pass only knows the two std markers that carry the
 * rule.
 *
 * Runs before the compile-time evaluation loop, because a `SignatureOnly`
 * function is typically `inline`: once its body has been folded into the caller
 * there is no call left to refuse.
 */
object SignatureAccessChecker {

    /** One function's outgoing calls, with the line each was written on. */
    private class Calls(val decl: FuncDecl, val out: List<Pair<String, Int>>)

    fun check(program: Program): List<String> {
        val boundaryDecorators = program.items.filterIsInstance<TopLevel.Deco>()
            .filter { deco -> deco.annotations.any { named(it.name) == "DeclaresAccess" } }
            .mapTo(mutableSetOf()) { it.name }
        if (boundaryDecorators.isEmpty()) return emptyList()

        val boundaryNames = boundaryDecorators.mapTo(mutableSetOf(), ::named)
        // Indexed by the *declared* name and by its tail. A call written in one
        // module names a function declared in another by the spelling that
        // module sees, and the two need not be the same string.
        val functions = mutableMapOf<String, Calls>()
        val signatureOnly = mutableSetOf<String>()
        val provides = mutableSetOf<String>()
        val boundaries = mutableListOf<FuncDecl>()

        fun consider(decl: FuncDecl) {
            val outgoing = Calls(decl, calls(decl.body))
            functions[decl.name] = outgoing
            functions.putIfAbsent(named(decl.name), outgoing)
            if (decl.annotations.any { named(it.name) == "SignatureOnly" }) {
                signatureOnly.add(decl.name)
                signatureOnly.add(named(decl.name))
            }
            if (decl.annotations.any { named(it.name) == "ProvidesAccess" }) {
                provides.add(decl.name)
                provides.add(named(decl.name))
            }
            if (decl.annotations.any { named(it.name) in boundaryNames }) {
                boundaries.add(decl)
            }
        }
        for (item in program.items) {
            when (item) {
                is TopLevel.Func -> consider(item.decl)
                is TopLevel.Impl -> item.methods.forEach(::consider)
                is TopLevel.Solo -> item.methods.forEach(::consider)
                else -> {}
            }
        }
        if (signatureOnly.isEmpty() || boundaries.isEmpty()) return emptyList()

        val errors = mutableListOf<String>()
        for (boundary in boundaries) {
            reach(boundary, functions, signatureOnly, provides)?.let { errors.add(describe(boundary, it)) }
        }
        return errors
    }

    /** One route from a boundary to a signature-only function, nearest first. */
    private class Route(val target: String, val line: Int, val through: List<String>)

    /**
     * The first route from [boundary] to a signature-only function, or null.
     *
     * Breadth-first, so the route reported is the shortest one - the reader is
     * pointed at the nearest place the access enters rather than at the deepest.
     */
    private fun reach(
        boundary: FuncDecl,
        functions: Map<String, Calls>,
        signatureOnly: Set<String>,
        provides: Set<String>,
    ): Route? {
        val seen = mutableSetOf(boundary.name)
        // Each entry: the function to expand, and how it was reached.
        var frontier = listOf<Pair<String, List<String>>>(boundary.name to emptyList())
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Pair<String, List<String>>>()
            for ((name, through) in frontier) {
                val calls = functions[name] ?: continue
                for ((callee, line) in calls.out) {
                    // Serving a declared parameter is the declaration being
                    // honoured, so the reach ends rather than following it in.
                    if (callee in provides || named(callee) in provides) continue
                    if (callee in signatureOnly || named(callee) in signatureOnly) {
                        return Route(callee, line, through)
                    }
                    if (seen.add(callee)) next.add(callee to (through + callee))
                }
            }
            frontier = next
        }
        return null
    }

    private fun describe(boundary: FuncDecl, route: Route): String {
        val what = sourceSymbol(route.target)
        val who = sourceSymbol(boundary.name)
        val ask = "ask for what it returns as a parameter of '$who' instead"
        return if (route.through.isEmpty()) {
            "line ${route.line}: '$what' cannot be called inside '$who', which declares " +
                "what it touches in its signature - $ask"
        } else {
            val path = route.through.joinToString(" -> ") { sourceSymbol(it) }
            "line ${route.line}: '$who' reaches '$what' through $path, and declares what it " +
                "touches in its signature - $ask"
        }
    }

    /** A decorator's name without the realm it was declared in. */
    private fun named(name: String): String = name.substringAfterLast("__")

    /** Every call written in [body], with the line it was written on. */
    private fun calls(body: List<Stmt>): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        walkStmts(body, out)
        return out
    }

    private fun walkStmts(body: List<Stmt>, out: MutableList<Pair<String, Int>>) =
        body.forEach { walkStmt(it, out) }

    private fun walkStmt(s: Stmt, out: MutableList<Pair<String, Int>>) {
        when (s) {
            is Stmt.ExprStmt -> walkExpr(s.expr, out)
            is Stmt.Return -> s.value?.let { walkExpr(it, out) }
            is Stmt.VarDecl -> walkExpr(s.initializer, out)
            is Stmt.FinDecl -> walkExpr(s.initializer, out)
            is Stmt.LetDecl -> walkExpr(s.initializer, out)
            is Stmt.RemDecl -> walkExpr(s.initializer, out)
            is Stmt.Assignment -> walkExpr(s.value, out)
            is Stmt.MemberAssign -> { walkExpr(s.target, out); walkExpr(s.value, out) }
            is Stmt.IndexAssign -> { walkExpr(s.target, out); walkExpr(s.index, out); walkExpr(s.value, out) }
            is Stmt.If -> { walkExpr(s.condition, out); walkStmts(s.thenBranch, out); s.elseBranch?.let { walkStmts(it, out) } }
            is Stmt.While -> { walkExpr(s.condition, out); walkStmts(s.body, out) }
            is Stmt.For -> { walkExpr(s.iterable, out); walkStmts(s.body, out) }
            is Stmt.Loop -> walkStmts(s.body, out)
            is Stmt.Scope -> walkStmts(s.body, out)
            is Stmt.WithContext -> { s.values.forEach { walkExpr(it, out) }; walkStmts(s.body, out) }
            is Stmt.Effect -> walkStmts(s.body, out)
            is Stmt.Defer -> walkStmts(s.body, out)
            is Stmt.Try -> { walkStmts(s.body, out); s.catchBody?.let { walkStmts(it, out) } }
            is Stmt.When -> {
                walkExpr(s.scrutinee, out)
                s.branches.forEach { walkStmts(it.body, out) }
                s.elseBranch?.let { walkStmts(it, out) }
            }
            is Stmt.Throw -> walkExpr(s.value, out)
            is Stmt.Yield -> walkExpr(s.value, out)
            else -> {}
        }
    }

    private fun walkExpr(e: Expr, out: MutableList<Pair<String, Int>>) {
        when (e) {
            is Expr.Call -> {
                out.add(e.callee to e.line)
                e.args.forEach { walkExpr(it, out) }
                e.receiver?.let { walkExpr(it, out) }
            }
            is Expr.MethodCall -> {
                out.add(e.name to e.line)
                walkExpr(e.target, out)
                e.args.forEach { walkExpr(it, out) }
            }
            is Expr.Member -> walkExpr(e.target, out)
            is Expr.Index -> { walkExpr(e.target, out); walkExpr(e.index, out) }
            is Expr.Binary -> { walkExpr(e.left, out); walkExpr(e.right, out) }
            is Expr.Unary -> walkExpr(e.operand, out)
            is Expr.Grouping -> walkExpr(e.expr, out)
            is Expr.Cast -> walkExpr(e.expr, out)
            is Expr.IfExpr -> { walkExpr(e.condition, out); walkExpr(e.thenExpr, out); walkExpr(e.elseExpr, out) }
            is Expr.Isolated -> walkExpr(e.value, out)
            is Expr.Seal -> walkExpr(e.value, out)
            is Expr.Await -> walkExpr(e.value, out)
            is Expr.Spread -> walkExpr(e.array, out)
            is Expr.NamedArg -> walkExpr(e.value, out)
            is Expr.InlineForArgs -> { walkExpr(e.iterable, out); walkExpr(e.body, out) }
            // A block written inside the body is part of it: what it reaches,
            // the function reaches.
            is Expr.Lambda -> walkStmts(e.body, out)
            is Expr.StringTemplate -> e.parts.forEach { part ->
                if (part is Expr.StringTemplatePart.Expr) walkExpr(part.expr, out)
            }
            is Expr.ArrayLiteral -> e.elements.forEach { walkExpr(it, out) }
            is Expr.SetLiteral -> e.elements.forEach { walkExpr(it, out) }
            is Expr.MapLit -> e.entries.forEach { (k, v) -> walkExpr(k, out); walkExpr(v, out) }
            is Expr.TupleLit -> e.elements.forEach { walkExpr(it, out) }
            else -> {}
        }
    }
}
