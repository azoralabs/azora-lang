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
import kotlin.test.assertTrue

/**
 * A constructor and a destructor are members of their type, so they are written
 * where every other member is: inside `impl T { }`, with the receiver declared
 * in brackets on the signature.
 *
 * `impl ctor() for T { self! -> … }` was the last form that named its type from
 * the outside and its receiver inside the braces. It does not parse - the error
 * names the spelling that replaces it, so a file written against the old form
 * says what to do rather than only that something is wrong.
 */
class ImplCtorFormRemovedTest {

    private fun parse(source: String): List<TopLevel> =
        Parser(Lexer(source).tokenize()).parse().items

    private fun rejection(source: String): String =
        assertFailsWith<Exception> { parse(source) }.message.orEmpty()

    @Test fun theTopLevelCtorFormIsRejected() {
        val message = rejection(
            """
            impl ctor() for Queue<T> { self! ->
                self.size = 0
            }
            """.trimIndent()
        )
        assertTrue("was removed" in message, message)
        assertTrue("ctor[self: Self!](…)" in message, "the error names the replacement: $message")
    }

    @Test fun theTopLevelDtorFormIsRejected() {
        val message = rejection(
            """
            impl dtor() for Queue<T> { self& ->
                purge self.data
            }
            """.trimIndent()
        )
        assertTrue("was removed" in message, message)
        assertTrue("dtor[self: Self!]" in message, "the error names the replacement: $message")
    }

    @Test fun theFormIsRejectedEvenWithoutTheInBraceReceiver() {
        assertTrue("was removed" in rejection("impl ctor() for Queue<T> {\n    self.size = 0\n}"))
    }

    @Test fun theInImplSpellingStillParses() {
        val methods = parse(
            """
            impl Queue<T> {
                ctor[self: Self!]() {
                    self.size = 0
                }

                dtor[self: Self&] {
                    purge self.data
                }
            }
            """.trimIndent()
        ).filterIsInstance<TopLevel.Impl>().flatMap { it.methods }

        assertEquals(listOf("ctor", "dtor"), methods.map { it.name })
    }
}
