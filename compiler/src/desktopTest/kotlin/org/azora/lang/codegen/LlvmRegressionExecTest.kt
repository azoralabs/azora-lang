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

package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for specific [org.azora.lang.backend.LlvmCodegen] fixes,
 * executed with `lli` (skipped when no LLVM toolchain is available).
 *
 * - `when` over a String must compare **contents** (strcmp), not pointer
 *   identity — a runtime-built string must still match a literal pattern.
 * - Unsigned integers must print with unsigned printf conversions
 *   (`%u` / `%llu`), not sign-interpreted ones.
 */
class LlvmRegressionExecTest {

    private fun check(expected: String, source: String) {
        if (!LlvmExec.available) return
        assertEquals(expected, LlvmExec.run(source))
    }

    private fun main(body: String): String = "func main() {\n$body\n}"

    /** A concatenated string has a fresh pointer — only strcmp can match it. */
    @Test fun whenOnRuntimeString() = check(
        "H",
        main(
            """
            let s = "he" + "llo"
            when s {
                "world" -> { println("W") }
                "hello" -> { println("H") }
                else -> { println("?") }
            }
            """.trimIndent()
        )
    )

    @Test fun whenOnStringMultiPattern() = check(
        "vowel\nother",
        """
        func kind(s: String): String {
            when s {
                "a", "e" -> { return "vowel" }
                else -> { return "other" }
            }
            return "unreachable"
        }
        func main() {
            println(kind("e" + ""))
            println(kind("z" + ""))
        }
        """.trimIndent()
    )

    @Test fun whenOnStringElseBranch() = check(
        "?",
        main(
            """
            let s = "x" + "y"
            when s {
                "ab" -> { println("AB") }
                else -> { println("?") }
            }
            """.trimIndent()
        )
    )

    /** UInt underflow wraps to 2^32-1 and must print unsigned (`%u`). */
    @Test fun uintPrintsUnsigned() = check(
        "4294967295",
        main(
            """
            var x: UInt = 5u
            x = x - 6u
            println(x)
            """.trimIndent()
        )
    )

    /** ULong underflow wraps to 2^64-1 and must print unsigned (`%llu`). */
    @Test fun ulongPrintsUnsigned() = check(
        "18446744073709551615",
        main(
            """
            var y: ULong = 0uL
            y = y - 1uL
            println(y)
            """.trimIndent()
        )
    )

    /** String interpolation of an unsigned value routes through the %llu helper. */
    @Test fun uintInterpolatesUnsigned() = check(
        "v = 4294967295",
        main(
            """
            var x: UInt = 0u
            x = x - 1u
            println("v = ${'$'}x")
            """.trimIndent()
        )
    )

    /** Signed printing is unchanged. */
    @Test fun signedIntStillPrintsSigned() = check(
        "-6",
        main(
            """
            var x = 0
            x = x - 6
            println(x)
            """.trimIndent()
        )
    )
}
