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

/**
 * Release-mode (optimizer-on) execution tests for the LLVM backend.
 *
 * These guard against optimizer passes — particularly constant propagation —
 * silently changing a program's result. Each program is lowered from the
 * **optimized** IR and executed with `lli`; the output must match the
 * program's true semantics.
 *
 * A regression here previously caused loop- and branch-mutated variables to be
 * folded to their initial constant (e.g. a running sum collapsing to `0`).
 */
class LlvmOptimizedExecTest {

    private fun check(expected: String, source: String) {
        if (!LlvmExec.available) return
        assertEquals(expected, LlvmExec.run(source, optimized = true))
    }

    private fun main(body: String): String = "import std.io\nfunc main() {\n$body\n}"

    @Test fun loopMutatedSumIsNotFolded() =
        check("15", main("var sum = 0\nfor i in 1..5 { sum = sum + i }\nstd::println(sum)"))

    @Test fun whileMutatedProductIsNotFolded() =
        check("24", main("var p = 1\nvar k = 1\nwhile k <= 4 { p = p * k\nk = k + 1 }\nstd::println(p)"))

    @Test fun branchMutatedVariableIsNotFolded() = check(
        "99",
        main(
            """
            var x = 10
            if 1 < 2 { x = 99 }
            std::println(x)
            """.trimIndent()
        )
    )

    @Test fun loopVariableNotFoldedAfterLoop() =
        check("5", main("var last = 0\nfor i in 1..5 { last = i }\nstd::println(last)"))

    @Test fun constantsStillFoldWhenNotMutated() =
        check("30", main("let a = 10\nlet b = 20\nstd::println(a + b)"))

    @Test fun fizzBuzzOptimized() = check(
        listOf(
            "1", "2", "Fizz", "4", "Buzz", "Fizz", "7", "8",
            "Fizz", "Buzz", "11", "Fizz", "13", "14", "FizzBuzz"
        ).joinToString("\n"),
        """
        import std.io
        func main() {
            for i in 1..15 {
                if i % 15 == 0 {
                    std::println("FizzBuzz")
                } else if i % 3 == 0 {
                    std::println("Fizz")
                } else if i % 5 == 0 {
                    std::println("Buzz")
                } else {
                    std::println(i)
                }
            }
        }
        """.trimIndent()
    )

    @Test fun recursionThroughOptimizer() = check(
        "120",
        """
        import std.io
        func fact(n: Int): Int {
            if n <= 1 { return 1 }
            return n * fact(n - 1)
        }
        func main() { std::println(fact(5)) }
        """.trimIndent()
    )

    @Test fun nestedLoopCounterOptimized() = check(
        "9",
        main(
            """
            var count = 0
            for i in 0..<3 {
                for j in 0..<3 {
                    count = count + 1
                }
            }
            std::println(count)
            """.trimIndent()
        )
    )
}
