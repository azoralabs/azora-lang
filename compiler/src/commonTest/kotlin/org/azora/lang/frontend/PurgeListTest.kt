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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Releasing a type's buffers is one act, so it is one statement.
 *
 * `purge [a, b, c]` names the things released in the order they are released,
 * and `with self` supplies the receiver once instead of on every line:
 *
 * ```
 * with self purge [keys, values, hashes, occupied]
 * ```
 *
 * A `with` whose body is a single statement on the same line needs no braces -
 * the braces would only wrap the one line that says something.
 */
class PurgeListTest {

    private fun body(source: String): List<Stmt> =
        Parser(Lexer(source).tokenize()).parse().functions.single { it.name == "release" }.body

    /** Every `__purge` call in [statements], flattened out of any scope blocks. */
    private fun purged(statements: List<Stmt>): List<String> = statements.flatMap { statement ->
        when (statement) {
            is Stmt.Scope -> purged(statement.body)
            is Stmt.WithContext -> purged(statement.body)
            is Stmt.ExprStmt -> {
                val call = statement.expr as? Expr.Call
                if (call?.callee == "__purge") listOf(render(call.args.single())) else emptyList()
            }
            else -> emptyList()
        }
    }

    private fun render(expr: Expr): String = when (expr) {
        is Expr.Identifier -> expr.name
        is Expr.Member -> "${render(expr.target)}.${expr.name}"
        else -> expr.toString()
    }

    @Test fun aListReleasesEveryTargetInOrder() {
        val statements = body(
            """
            func release(self: Buffers!) {
                purge [self.keys, self.values, self.hashes]
            }
            """.trimIndent()
        )
        assertEquals(listOf("self.keys", "self.values", "self.hashes"), purged(statements))
    }

    @Test fun aSingleTargetStillParses() {
        val statements = body(
            """
            func release(self: Buffers!) {
                purge self.keys
            }
            """.trimIndent()
        )
        assertEquals(listOf("self.keys"), purged(statements))
    }

    @Test fun withSuppliesTheReceiverForABracelessStatement() {
        val statements = body(
            """
            func release(self: Buffers!) {
                with self purge [keys, values, hashes, occupied]
            }
            """.trimIndent()
        )
        val context = statements.single() as Stmt.WithContext
        assertEquals("self", render(context.values.single()))
        assertEquals(listOf("keys", "values", "hashes", "occupied"), purged(context.body))
    }

    @Test fun withStillTakesABracedBody() {
        val statements = body(
            """
            func release(self: Buffers!) {
                with self {
                    purge [keys, values]
                }
            }
            """.trimIndent()
        )
        val context = statements.single() as Stmt.WithContext
        assertEquals(listOf("keys", "values"), purged(context.body))
    }

    @Test fun aWithWithNeitherBracesNorAStatementSaysSo() {
        val failure = runCatching {
            body("func release(self: Buffers!) {\n    with self\n    purge self.keys\n}")
        }.exceptionOrNull()
        assertTrue(failure != null, "a `with` with nothing after it is an error")
        assertTrue("with <value>" in failure.message.orEmpty(), failure?.message.orEmpty())
    }
}
