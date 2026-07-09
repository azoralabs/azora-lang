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

    private fun main(body: String): String = "func main() {\n$body\n}"

    @Test fun printsHello() = check("hello", main("""println("hello")"""))
    @Test fun arithmetic() = check("14", main("""println(2 + 3 * 4)"""))

    @Test fun integerDivisionTruncates() = check(
        "3", main("var total = 0\nfor i in 1..17 {\ntotal = total + 1\n}\nprintln(total / 5)")
    )

    @Test fun negativeIntegerDivisionTruncatesTowardZero() = check(
        "-3", main("var n = 0\nwhile n > -7 {\nn = n - 1\n}\nprintln(n / 2)")
    )

    @Test fun realDivision() = check("3.5", main("var x = 7.0\nprintln(x / 2.0)"))
    @Test fun modulo() = check("2", main("var n = 17\nprintln(n % 5)"))

    @Test fun bitwiseOps() = check(
        "2\n11\n9\n16\n64\n-11",
        main("var a = 10\nprintln(a & 6)\nprintln(a | 1)\nprintln(a ^ 3)\nprintln(1 << 4)\nprintln(256 >> 2)\nprintln(~a)")
    )

    @Test fun stringConcatAndInterpolation() = check(
        "n = 5!", main("var n = 5\nprintln(\"n = \" + \"${'$'}n\" + \"!\")")
    )

    @Test fun stringRepeat() = check("ababab", main("var s = \"ab\"\nprintln(s * 3)"))

    @Test fun stringEquality() = check(
        "true\nfalse", main("var s = \"he\" + \"llo\"\nprintln(s == \"hello\")\nprintln(s == \"world\")")
    )

    @Test fun ifElseChain() = check(
        "positive",
        """
        func classify(n: Int): String {
            if n < 0 { return "negative" } else if n == 0 { return "zero" }
            return "positive"
        }
        func main() { var n = 3
            println(classify(n)) }
        """.trimIndent()
    )

    @Test fun forLoopSum() = check("15", main("var total = 0\nfor i in 1..5 {\ntotal = total + i\n}\nprintln(total)"))
    @Test fun whileLoop() = check("8", main("var x = 20\nwhile x > 10 {\nx = x - 4\n}\nprintln(x)"))

    @Test fun loopBreakContinue() = check(
        "1\n2\n4",
        main("var i = 0\nloop {\ni = i + 1\nif i == 3 { continue }\nif i > 4 { break }\nprintln(i)\n}")
    )

    @Test fun whenBranches() = check(
        "two or three",
        main(
            """
            var grade = 2
            when grade {
                1 -> { println("one") }
                2, 3 -> { println("two or three") }
                else -> { println("other") }
            }
            """.trimIndent()
        )
    )

    @Test fun recursion() = check(
        "120",
        """
        func fact(n: Int): Int {
            if n <= 1 { return 1 }
            return n * fact(n - 1)
        }
        func main() { println(fact(5)) }
        """.trimIndent()
    )

    @Test fun structFieldMutation() = check(
        "4\n7",
        """
        pack Point { var x: Int
            var y: Int }
        func main() { let p = Point(3, 4)
            p.x = p.x + 1
            println(p.x)
            println(p.x + p.y - 1) }
        """.trimIndent()
    )

    @Test fun arrayIndexAndLength() = check(
        "25\n3", main("let nums = [10, 20, 30]\nnums[1] = 25\nprintln(nums[1])\nprintln(nums.length)")
    )

    @Test fun functionCalls() = check(
        "25",
        """
        func square(n: Int): Int { return n * n }
        func main() { var n = 5
            println(square(n)) }
        """.trimIndent()
    )
}
