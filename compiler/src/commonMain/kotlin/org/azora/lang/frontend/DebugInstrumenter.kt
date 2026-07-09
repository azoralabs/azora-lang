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

package org.azora.lang.frontend

/**
 * Debug-build instrumentation: inserts a `__dbg(<line>)` call before every
 * runtime statement in function/test bodies (recursively into nested blocks).
 *
 * `__dbg` is a compiler builtin — the interpreter forwards it to the attached
 * [org.azora.lang.backend.AzoraDebugHost] (which is where breakpoints pause),
 * and it costs nothing when no debugger is attached. Compile-time (`inline`)
 * constructs are left untouched: they never execute at runtime.
 */
object DebugInstrumenter {

    fun instrument(program: Program): Program = program.copy(
        items = program.items.map { item ->
            when (item) {
                is TopLevel.Func -> item.copy(decl = item.decl.copy(body = instrumentBody(item.decl.body)))
                is TopLevel.Test -> item.copy(body = instrumentBody(item.body))
                else -> item
            }
        }
    )

    private fun instrumentBody(body: List<Stmt>): List<Stmt> = body.flatMap { stmt ->
        val marked = markNested(stmt)
        if (stmt.line > 0) listOf(dbgCall(stmt.line), marked) else listOf(marked)
    }

    private fun dbgCall(line: Int): Stmt =
        Stmt.ExprStmt(Expr.Call("__dbg", listOf(Expr.IntLiteral(line.toLong(), line)), line), line)

    private fun markNested(stmt: Stmt): Stmt = when (stmt) {
        is Stmt.If -> stmt.copy(
            thenBranch = instrumentBody(stmt.thenBranch),
            elseBranch = stmt.elseBranch?.let { instrumentBody(it) }
        )
        is Stmt.While -> stmt.copy(body = instrumentBody(stmt.body))
        is Stmt.For -> stmt.copy(body = instrumentBody(stmt.body))
        is Stmt.Loop -> stmt.copy(body = instrumentBody(stmt.body))
        is Stmt.When -> stmt.copy(
            branches = stmt.branches.map { it.copy(body = instrumentBody(it.body)) },
            elseBranch = stmt.elseBranch?.let { instrumentBody(it) }
        )
        is Stmt.Try -> stmt.copy(
            body = instrumentBody(stmt.body),
            catchBody = stmt.catchBody?.let { instrumentBody(it) }
        )
        is Stmt.Zone -> stmt.copy(body = instrumentBody(stmt.body))
        is Stmt.FriendZone -> stmt.copy(body = instrumentBody(stmt.body))
        is Stmt.Defer -> stmt.copy(body = instrumentBody(stmt.body))
        else -> stmt
    }
}
