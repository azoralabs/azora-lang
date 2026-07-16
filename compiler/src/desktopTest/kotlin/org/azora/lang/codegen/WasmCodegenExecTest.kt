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
 * End-to-end tests for the [org.azora.lang.backend.WasmCodegen] backend: each
 * compiles a small Azora program to WAT, assembles it with `wat2wasm`, and runs
 * it under Node.js, asserting on stdout.
 *
 * The suite covers what the MVP WASM target supports — scalar arithmetic,
 * control flow, functions/recursion, strings (concat/interpolation/repeat/
 * equality), packs and arrays. Higher-level features (lambdas, tasks,
 * exceptions, sets/maps/tuples) are out of scope for this backend. Skips itself
 * when Node.js / the `wat2wasm` assembler are unavailable.
 */
class WasmCodegenExecTest {

    private fun check(expected: String, source: String) {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(source))
    }

    private fun main(body: String): String = "import std.io\nfunc main() {\n$body\n}"

    @Test fun printsHello() = check("hello", main("""std::io::println("hello")"""))
    @Test fun arithmetic() = check("14", main("""std::io::println(2 + 3 * 4)"""))

    @Test fun integerDivisionTruncates() = check(
        "3", main("var total = 0\nfor i in 1..17 {\ntotal = total + 1\n}\nstd::io::println(total / 5)")
    )

    @Test fun negativeIntegerDivisionTruncatesTowardZero() = check(
        "-3", main("var n = 0\nwhile n > -7 {\nn = n - 1\n}\nstd::io::println(n / 2)")
    )

    @Test fun realDivision() = check("3.5", main("var x = 7.0\nstd::io::println(x / 2.0)"))
    @Test fun modulo() = check("2", main("var n = 17\nstd::io::println(n % 5)"))

    @Test fun bitwiseOps() = check(
        "2\n11\n9\n16\n64\n-11",
        main("var a = 10\nstd::io::println(a & 6)\nstd::io::println(a | 1)\nstd::io::println(a ^ 3)\nstd::io::println(1 << 4)\nstd::io::println(256 >> 2)\nstd::io::println(~a)")
    )

    @Test fun stringConcatAndInterpolation() = check(
        "n = 5!", main("var n = 5\nstd::io::println(\"n = \" + \"${'$'}n\" + \"!\")")
    )

    @Test fun stringRepeat() = check("ababab", main("var s = \"ab\"\nstd::io::println(s * 3)"))

    @Test fun stringEquality() = check(
        "true\nfalse", main("var s = \"he\" + \"llo\"\nstd::io::println(s == \"hello\")\nstd::io::println(s == \"world\")")
    )

    @Test fun ifElseChain() = check(
        "positive",
        """
        import std.io
        func classify(n: Int): String {
            if n < 0 { return "negative" } else if n == 0 { return "zero" }
            return "positive"
        }
        func main() { var n = 3
            std::io::println(classify(n)) }
        """.trimIndent()
    )

    @Test fun forLoopSum() = check("15", main("var total = 0\nfor i in 1..5 {\ntotal = total + i\n}\nstd::io::println(total)"))
    @Test fun whileLoop() = check("8", main("var x = 20\nwhile x > 10 {\nx = x - 4\n}\nstd::io::println(x)"))

    @Test fun loopBreakContinue() = check(
        "1\n2\n4",
        main("var i = 0\nloop {\ni = i + 1\nif i == 3 { continue }\nif i > 4 { break }\nstd::io::println(i)\n}")
    )

    @Test fun whenBranches() = check(
        "two or three",
        main(
            """
            var grade = 2
            when grade {
                1 -> { std::io::println("one") }
                2, 3 -> { std::io::println("two or three") }
                else -> { std::io::println("other") }
            }
            """.trimIndent()
        )
    )

    @Test fun recursion() = check(
        "120",
        """
        import std.io
        func fact(n: Int): Int {
            if n <= 1 { return 1 }
            return n * fact(n - 1)
        }
        func main() { std::io::println(fact(5)) }
        """.trimIndent()
    )

    @Test fun structFieldMutation() = check(
        "4\n7",
        """
        import std.io
        pack Point { var x: Int
            var y: Int }
        func main() { let p = Point(3, 4)
            p.x = p.x + 1
            std::io::println(p.x)
            std::io::println(p.x + p.y - 1) }
        """.trimIndent()
    )

    @Test fun arrayIndexAndLength() = check(
        "25\n3", main("let nums = [10, 20, 30]\nnums[1] = 25\nstd::io::println(nums[1])\nstd::io::println(nums.length)")
    )

    @Test fun functionCalls() = check(
        "25",
        """
        import std.io
        func square(n: Int): Int { return n * n }
        func main() { var n = 5
            std::io::println(square(n)) }
        """.trimIndent()
    )
}
