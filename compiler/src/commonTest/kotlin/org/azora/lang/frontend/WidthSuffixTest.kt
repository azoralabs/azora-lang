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
 * A literal states no width.
 *
 * `4` is an `__int` and takes the width of wherever it lands, so nothing about
 * a literal carries a type. The suffixes that used to say one are still
 * recognised - only so the error can name what was meant.
 */
class WidthSuffixTest {

    private fun failureFor(source: String): String =
        assertFailsWith<IllegalStateException> { Lexer(source).tokenize() }.message.orEmpty()

    @Test fun aSuffixIsRefusedAndTheWidthIsNamed() {
        val message = failureFor("fin big = 4L")

        assertTrue("width suffix is not part of a literal" in message, message)
        assertTrue("write '4'" in message, message)
        assertTrue("Long(4)" in message, message)
    }

    @Test fun everySuffixThatUsedToExistIsStillRecognised() {
        for ((written, width) in listOf(
            "1b" to "Byte", "1ub" to "UByte", "1s" to "Short", "1us" to "UShort",
            "1u" to "UInt", "1L" to "Long", "1uL" to "ULong", "1c" to "Cent",
            "1uc" to "UCent", "1f" to "Float", "1D" to "Quad",
        )) {
            val message = failureFor("fin x = $written")
            assertTrue("$width(1)" in message, "'$written' should name $width, said: $message")
        }
    }

    @Test fun aNumberFollowedByAWordIsTwoThings() {
        // `4until` is `4` and a name; only a suffix that ends the number is one.
        val tokens = Lexer("for i in 4until").tokenize()

        assertEquals(TokenType.INT_LITERAL, tokens.first { it.type == TokenType.INT_LITERAL }.type)
        assertTrue(tokens.any { it.lexeme == "until" }, tokens.map { it.lexeme }.toString())
    }

    @Test fun theDigitsOfAHexLiteralAreNotSuffixes() {
        // `b`, `c` and `f` are hex digits, so `0xbeef` is one number.
        val tokens = Lexer("fin colour = 0xbeef").tokenize()

        assertEquals("0xbeef", tokens.single { it.type == TokenType.INT_LITERAL }.lexeme)
    }

    @Test fun aLiteralCarriesOnlyItsValueAndItsDigits() {
        val literal = Lexer("fin x = 42").tokenize()
            .single { it.type == TokenType.INT_LITERAL }.literal as NumericLiteral

        assertEquals(42L, literal.value)
        // The digits travel only when the value cannot hold them.
        assertEquals(null, literal.text)
    }
}
