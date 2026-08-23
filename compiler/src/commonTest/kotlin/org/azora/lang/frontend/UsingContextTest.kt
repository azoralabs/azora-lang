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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UsingContextTest {

    private fun statement(source: String): Stmt =
        Parser(Lexer("func f() {\n$source\n}").tokenize()).parse()
            .items.filterIsInstance<TopLevel.Func>().single().decl.body.single()

    private fun failure(source: String): String =
        assertFailsWith<IllegalStateException> { statement(source) }.message.orEmpty()

    @Test
    fun oneReceiverIsUnparenthesized() {
        val context = assertIs<Stmt.UsingContext>(statement("using receiver { run() }"))
        assertEquals(1, context.values.size)
        assertEquals("receiver", assertIs<Expr.Identifier>(context.values.single()).name)
    }

    @Test
    fun severalReceiversUseParentheses() {
        val context = assertIs<Stmt.UsingContext>(statement("using (left, right) { run() }"))
        assertEquals(listOf("left", "right"), context.values.map { assertIs<Expr.Identifier>(it).name })
    }

    @Test
    fun physicalNewlinesSeparateReceiversWithoutCommas() {
        val context = assertIs<Stmt.UsingContext>(
            statement(
                """
                using (
                    left
                    right
                ) { run() }
                """.trimIndent(),
            ),
        )
        assertEquals(2, context.values.size)
    }

    @Test
    fun bracketsAreRejectedForContextReceiverLists() {
        val message = failure("using [left, right] { run() }")
        assertTrue("use parentheses" in message, message)
    }

    @Test
    fun aSingleReceiverGroupIsRejected() {
        assertTrue("without parentheses" in failure("using (receiver) { run() }"))
    }

    @Test
    fun anEmptyReceiverGroupIsRejected() {
        assertTrue("cannot be empty" in failure("using () { run() }"))
    }

    @Test
    fun bracesAreRequired() {
        val message = failure("using receiver\nrun()")
        assertTrue("Expected '{'" in message, message)
    }

    @Test
    fun oldWithSyntaxNamesUsing() {
        assertTrue("Context receivers use 'using'" in failure("with receiver { run() }"))
    }
}
