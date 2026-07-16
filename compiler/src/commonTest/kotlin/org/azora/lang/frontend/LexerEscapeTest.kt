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

class LexerEscapeTest {
    @Test fun stringControlEscapesAreLossless() {
        val token = Lexer("\"\\b\\f\\n\\r\\t\\0\"").tokenize().first()
        assertEquals("\b\u000C\n\r\t\u0000", token.literal)
    }

    @Test fun characterControlEscapesAreLossless() {
        val sources = listOf("'\\b'", "'\\f'", "'\\n'", "'\\r'", "'\\t'", "'\\0'")
        val expected = listOf('\b', '\u000C', '\n', '\r', '\t', '\u0000')
        assertEquals(expected, sources.map { Lexer(it).tokenize().first().literal })
    }

    @Test fun escapedQuoteSlashAndBackslashAreAccepted() {
        val token = Lexer("\"\\\"\\/\\\\\"").tokenize().first()
        assertEquals("\"/\\", token.literal)
    }

    @Test fun unknownStringEscapeIsRejected() {
        val error = assertFailsWith<IllegalStateException> { Lexer("\"\\x\"").tokenize() }
        assertTrue("Unknown escape sequence" in error.message.orEmpty(), error.message)
    }
}
