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
import org.azora.lang.frontend.TypeRef

/**
 * Substitutes a call that takes an `inline` block with the body it would run.
 *
 * `rows.forEach { row -> … }` becomes the loop `forEach` is written as, with the
 * block's statements where its call to the block was. That is what `inline` on a
 * callable parameter promises: writing the block costs what writing its body
 * there would, rather than a call for every row.
 *
 * Done on the tree rather than in a backend so every backend gets it, and done
 * only where the result is a statement - a call whose value is used has a place
 * to put the value that statements do not.
 */
object InlineCallables {

    fun apply(program: Program): Program {
        val inlinable = collect(program)
        if (inlinable.isEmpty()) return program
        val rewriter = Rewriter(inlinable)
        return program.copy(
            items = program.items.map { item ->
                when (item) {
                    is TopLevel.Func -> item.copy(decl = item.decl.copy(body = rewriter.body(item.decl.body)))
                    is TopLevel.Impl -> item.copy(
                        methods = item.methods.map { it.copy(body = rewriter.body(it.body)) },
                    )
                    else -> item
                }
            },
        )
    }

    /**
     * Members that take an `inline` block, by name.
     *
     * A name that belongs to more than one such member is left out: the call
     * site names the member and nothing here says which type it is on, and
     * substituting the wrong body would be worse than not substituting.
     */
    private fun collect(program: Program): Map<String, FuncDecl> {
        val found = mutableMapOf<String, FuncDecl>()
        val ambiguous = mutableSetOf<String>()
        fun consider(decl: FuncDecl) {
            if (decl.body.isEmpty()) return
            if (decl.params.none { (it.type as? TypeRef.Function)?.isInline == true }) return
            if (found.put(decl.name, decl) != null) ambiguous.add(decl.name)
        }
        for (item in program.items) {
            when (item) {
                is TopLevel.Func -> consider(item.decl)
                is TopLevel.Impl -> item.methods.forEach(::consider)
                else -> {}
            }
        }
        return found - ambiguous
    }

    private class Rewriter(val inlinable: Map<String, FuncDecl>) {

        fun body(statements: List<Stmt>): List<Stmt> = statements.flatMap { statement ->
            val spliced = splice(statement)
            spliced ?: listOf(recurse(statement))
        }

        /** The statements a call expands to, or null when it is not one that expands. */
        private fun splice(statement: Stmt): List<Stmt>? {
            val call = (statement as? Stmt.ExprStmt)?.expr as? Expr.MethodCall ?: return null
            val callee = inlinable[call.name] ?: return null
            val blockIndex = callee.params.indexOfFirst { (it.type as? TypeRef.Function)?.isInline == true }
            val block = call.args.getOrNull(blockIndex) as? Expr.Lambda ?: return null

            // The callee's own bindings: its receiver is what the call was made
            // on, and its value parameters are the arguments it was given.
            val bindings = mutableMapOf<String, Expr>()
            bindings[callee.receiverName ?: "self"] = call.target
            callee.params.forEachIndexed { index, param ->
                if (index != blockIndex) call.args.getOrNull(index)?.let { bindings[param.name] = it }
            }
            return Body(bindings, callee.params[blockIndex].name, block).body(callee.body)
        }

        private fun recurse(statement: Stmt): Stmt = when (statement) {
            is Stmt.If -> statement.copy(thenBranch = body(statement.thenBranch), elseBranch = statement.elseBranch?.let { body(it) })
            is Stmt.While -> statement.copy(body = body(statement.body))
            is Stmt.For -> statement.copy(body = body(statement.body))
            is Stmt.Loop -> statement.copy(body = body(statement.body))
            is Stmt.Scope -> statement.copy(body = body(statement.body))
            is Stmt.When -> statement.copy(
                branches = statement.branches.map { it.copy(body = body(it.body)) },
                elseBranch = statement.elseBranch?.let { body(it) },
            )
            else -> statement
        }
    }

    /**
     * One expansion: the callee's body with its bindings filled in and its call
     * to the block replaced by the block itself.
     */
    private class Body(
        val bindings: Map<String, Expr>,
        val blockName: String,
        val block: Expr.Lambda,
    ) {
        fun body(statements: List<Stmt>): List<Stmt> = statements.flatMap { statement ->
            val called = (statement as? Stmt.ExprStmt)?.expr as? Expr.Call
            if (called != null && called.callee == blockName) {
                // `body(row)` - the block runs here, with its parameters bound to
                // what the call passed.
                //
                // The arguments are still written in the *callee's* terms, so
                // they are substituted before being bound: `body(self.step)`
                // hands the block `r.step`, not `self.step`. Binding them raw
                // spliced the callee's `self` into the caller, where it names
                // nothing.
                val inner = block.params.mapIndexedNotNull { index, param ->
                    called.args.getOrNull(index)?.let { param.name to expr(it) }
                }.toMap()
                Body(bindings + inner, "", block).body(block.body)
            } else {
                listOf(stmt(statement))
            }
        }

        private fun stmt(s: Stmt): Stmt = when (s) {
            is Stmt.ExprStmt -> s.copy(expr = expr(s.expr))
            is Stmt.Return -> s.copy(value = s.value?.let { expr(it) })
            is Stmt.VarDecl -> s.copy(initializer = expr(s.initializer))
            is Stmt.FinDecl -> s.copy(initializer = expr(s.initializer))
            is Stmt.LetDecl -> s.copy(initializer = expr(s.initializer))
            is Stmt.Assignment -> s.copy(value = expr(s.value))
            is Stmt.MemberAssign -> s.copy(target = expr(s.target), value = expr(s.value))
            is Stmt.IndexAssign -> s.copy(target = expr(s.target), index = expr(s.index), value = expr(s.value))
            is Stmt.If -> s.copy(condition = expr(s.condition), thenBranch = body(s.thenBranch), elseBranch = s.elseBranch?.let { body(it) })
            is Stmt.While -> s.copy(condition = expr(s.condition), body = body(s.body))
            is Stmt.For -> s.copy(iterable = expr(s.iterable), body = body(s.body))
            is Stmt.Loop -> s.copy(body = body(s.body))
            is Stmt.Scope -> s.copy(body = body(s.body))
            is Stmt.When -> s.copy(
                scrutinee = expr(s.scrutinee),
                branches = s.branches.map { it.copy(body = body(it.body)) },
                elseBranch = s.elseBranch?.let { body(it) },
            )
            else -> s
        }

        private fun expr(e: Expr): Expr = when (e) {
            is Expr.Identifier -> bindings[e.name] ?: e
            is Expr.Call -> e.copy(args = e.args.map(::expr), receiver = e.receiver?.let(::expr))
            is Expr.MethodCall -> e.copy(target = expr(e.target), args = e.args.map(::expr))
            is Expr.Member -> e.copy(target = expr(e.target))
            is Expr.Index -> e.copy(target = expr(e.target), index = expr(e.index))
            is Expr.Binary -> e.copy(left = expr(e.left), right = expr(e.right))
            is Expr.Unary -> e.copy(operand = expr(e.operand))
            is Expr.Grouping -> e.copy(expr = expr(e.expr))
            is Expr.Cast -> e.copy(expr = expr(e.expr))
            is Expr.StringTemplate -> e.copy(parts = e.parts.map { part ->
                if (part is Expr.StringTemplatePart.Expr) Expr.StringTemplatePart.Expr(expr(part.expr)) else part
            })
            else -> e
        }
    }
}
