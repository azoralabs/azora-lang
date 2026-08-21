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

/**
 * A splice that is arithmetic arrives as the number it is.
 *
 * A width has to reach a type argument as a value, not as the three tokens that
 * would add up to one. `std/primitive.az` writes every width this way.
 */
class SpliceArithmeticTest {

    private fun aliases(source: String): List<Pair<String, String>> =
        Parser(Lexer("$source\nfunc main() {}").tokenize()).parse().items
            .filterIsInstance<TopLevel.TypeAlias>()
            .map { it.name to it.type.toString() }

    @Test fun aWidthIsSplicedAsAValue() {
        val declared = aliases(
            """
            bridge pack Int<N: __uint = 32>(__int)

            inline for name in ["Byte", "Short", "Wide"] with index {
                typealias ${'$'}name = Int<${'$'}{8 << index}>
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Byte" to "Int<8>", "Short" to "Int<16>", "Wide" to "Int<32>"), declared)
    }

    @Test fun theUsualArithmeticIsFolded() {
        val declared = aliases(
            """
            bridge pack Int<N: __uint = 32>(__int)

            inline for name in ["A", "B"] with index {
                typealias ${'$'}name = Int<${'$'}{(index + 1) * 8}>
            }
            """.trimIndent(),
        )

        assertEquals(listOf("A" to "Int<8>", "B" to "Int<16>"), declared)
    }
}
