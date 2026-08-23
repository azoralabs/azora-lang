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

package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/** Native execution coverage for the LLVM closure ABI. */
class LlvmLambdaExecTest {
    private fun check(expected: String, source: String) {
        if (!LlvmExec.available) return
        assertEquals(expected, LlvmExec.run(source), "debug IR")
        assertEquals(expected, LlvmExec.run(source, optimized = true), "optimized IR")
    }

    @Test
    fun storedLambdaCallsNatively() = check(
        "10",
        """
        import std.io
        func main() {
            fin double = { value: Int -> return value * 2 }
            println(double(5))
        }
        """.trimIndent(),
    )

    @Test
    fun closureCapturesLocalByValue() = check(
        "7",
        """
        import std.io
        func main() {
            fin offset = 3
            fin add = [offset.&] { value: Int -> return value + offset }
            println(add(4))
        }
        """.trimIndent(),
    )

    @Test
    fun contextualCallablePackField() = check(
        "5",
        """
        import std.io
        pack Left { fin value: Int }
        pack Right { fin value: Int }
        pack Calculator {
            fin add: (Left&, Right&).() -> Int =
                [&] (left: Left, right: Right) { left.value + right.value }
        }
        func main() {
            fin calculator = Calculator()
            using (Left(2), Right(3)) {
                println(calculator.add())
            }
        }
        """.trimIndent(),
    )
}
