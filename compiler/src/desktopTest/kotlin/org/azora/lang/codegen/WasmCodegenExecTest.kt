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
 * The suite covers what the MVP WASM target supports - scalar arithmetic,
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

    @Test fun printsHello() = check("hello", main("""std::println("hello")"""))
    @Test fun traceUsesRuntimeLevelAndImplicitReceiver() = check(
        "[WARN] LogLevel.Warn: wasm",
        main("fin level = LogLevel.Warn\ntrace level { \"${'$'}{it}: wasm\" }")
    )
    @Test fun traceAcceptsQualifiedDirectLevel() =
        check("[ERROR] Error", main("trace LogLevel.Error \"Error\""))
    @Test fun printWritesWithoutNewline() =
        check("Hello, 7!", main("std::print(\"Hello, \" )\nstd::print(7)\nstd::println(\"!\")"))
    @Test fun arithmetic() = check("14", main("""std::println(2 + 3 * 4)"""))

    @Test fun trailingLambdaExecutesThroughCallableParameter() = check(
        "12",
        """
            import std.io
            func apply(value: Int, action: (Int) -> Int): Int {
                return action(value)
            }
            func main() {
                std::println(apply(4) { value -> value * 3 })
            }
        """.trimIndent(),
    )

    @Test fun lazyFinInitializesOnFirstReadOnly() = check(
        "before\ninit\n42\n42",
        """
            import std.io
            func make(): Int {
                std::println("init")
                return 42
            }
            func main() {
                lazy fin answer = make()
                std::println("before")
                std::println(answer)
                std::println(answer)
            }
        """.trimIndent(),
    )

    @Test fun reactiveEffectRerunsAfterDependencyAssignment() = check(
        "1\n7",
        """
            import std.io
            react func main() {
                remember var value = 1
                effect { std::println(value) }
                value = 7
            }
        """.trimIndent(),
    )

    @Test fun rememberedStateSurvivesRepeatedReactiveCalls() = check(
        "1\n2\n3",
        """
            import std.io
            react func counter(): Int {
                remember var count = 0
                count = count + 1
                return count
            }
            react func main() {
                std::println(counter())
                std::println(counter())
                std::println(counter())
            }
        """.trimIndent(),
    )

    @Test fun reactiveEffectsObserveLazyDerivedBindings() = check(
        "2\n2\n6\n6",
        """
            import std.io
            react func main() {
                remember var value = 1
                lazy fin doubled = value * 2
                effect doubled { std::println(doubled) }
                effect { std::println(doubled) }
                value = 3
            }
        """.trimIndent(),
    )

    // A `Double` prints as a `Double` on every backend: an integral value keeps its `.0`.
    // WebAssembly has no sin/cos opcode, so the compiler supplies them in software
    // rather than importing them from the host. Cody-Waite reduction plus the odd/even
    // Taylor polynomials agrees with the interpreter to ~13 significant digits, which is
    // the accuracy this tier promises; `std::vha::sin` is where exactness belongs.
    @Test fun softwareSinAndCos() = check(
        "0.0\n0.841470984807901\n1.0\n-0.989992496600445",
        "import std.math\n" + main(
            "var a = 0.0\nvar b = 1.0\nvar c = 3.0\n" +
                "std::println(std::sin(a))\nstd::println(std::sin(b))\n" +
                "std::println(std::cos(a))\nstd::println(std::cos(c))"
        )
    )

    // exp/log and everything derived from them, also supplied in software.
    @Test fun softwareExpAndLog() = check(
        "2.718281828459045\n0.999999999999926\n2.0\n3.0\n8.0\n3.0",
        "import std.math\n" + main(
            "var one = 1.0\nvar e = 2.718281828459045\nvar four = 4.0\n" +
                "var thousand = 1000.0\nvar three = 3.0\nvar twentyseven = 27.0\n" +
                "std::println(std::exp(one))\nstd::println(std::log(e))\n" +
                "std::println(std::log2(four))\nstd::println(std::log10(thousand))\n" +
                "std::println(std::exp2(three))\nstd::println(std::cbrt(twentyseven))"
        )
    )

    // Inverse trigonometry and hypot. `atan` carries ~8 significant digits after its
    // double reduction, which is the float precision this tier promises; exactness is
    // what `std::vha::` is for. (`std::pow` is a generic `pow<T>`, not a bridge, so it
    // is not part of the software math.)
    @Test fun softwareInverseTrigAndHypot() = check(
        "0.785398131668175\n1.570796326794896\n0.0\n2.356194521921618\n5.0",
        "import std.math\n" + main(
            "var one = 1.0\nvar zero = 0.0\nvar neg = 0.0 - 1.0\n" +
                "var three = 3.0\nvar four = 4.0\nvar two = 2.0\n" +
                "std::println(std::atan(one))\nstd::println(std::asin(one))\n" +
                "std::println(std::acos(one))\nstd::println(std::atan2(one, neg))\n" +
                "std::println(std::hypot(three, four))"
        )
    )

    // `std::vha::sin` carries the series two terms further than `std::sin`, which shows
    // up once the range reduction is exact: against a true sin(1) of 0.841470984807897,
    // the default lands on ...901 and vha on ...896. cos(3) is exact in both tiers here,
    // the extra terms mattering only where the argument reduces less kindly.
    @Test fun vhaTrigIsMoreAccurateThanTheDefault() = check(
        "0.841470984807901\n0.841470984807896\n-0.989992496600445\n-0.989992496600445",
        "import std.math\n" + main(
            "var one = 1.0\nvar three = 3.0\n" +
                "std::println(std::sin(one))\nstd::println(std::vha::sin(one))\n" +
                "std::println(std::cos(three))\nstd::println(std::vha::cos(three))"
        )
    )

    @Test fun printsRealValues() = check(
        "1.5\n4.0\n0.25",
        main("std::println(1.5)\nstd::println(4.0)\nstd::println(0.25)")
    )

    @Test fun interpolatesRealAndLong() = check(
        "r=1.5 g=7",
        main("var r = 1.5\nvar g: Long = 7L\nstd::println(\"r=${'$'}{r} g=${'$'}{g}\")")
    )

    @Test fun integerDivisionTruncates() = check(
        "3", main("var total = 0\nfor i in 1..17 {\ntotal = total + 1\n}\nstd::println(total / 5)")
    )

    @Test fun negativeIntegerDivisionTruncatesTowardZero() = check(
        "-3", main("var n = 0\nwhile n > -7 {\nn = n - 1\n}\nstd::println(n / 2)")
    )

    @Test fun doubleDivision() = check("3.5", main("var x = 7.0\nstd::println(x / 2.0)"))
    @Test fun modulo() = check("2", main("var n = 17\nstd::println(n % 5)"))

    @Test fun bitwiseOps() = check(
        "2\n11\n9\n16\n64\n-11",
        main("var a = 10\nstd::println(a & 6)\nstd::println(a | 1)\nstd::println(a ^ 3)\nstd::println(1 << 4)\nstd::println(256 >> 2)\nstd::println(~a)")
    )

    @Test fun stringConcatAndInterpolation() = check(
        "n = 5!", main("var n = 5\nstd::println(\"n = \" + \"${'$'}n\" + \"!\")")
    )

    @Test fun stringRepeat() = check("ababab", main("var s = \"ab\"\nstd::println(s * 3)"))

    @Test fun stringEquality() = check(
        "true\nfalse", main("var s = \"he\" + \"llo\"\nstd::println(s == \"hello\")\nstd::println(s == \"world\")")
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
            std::println(classify(n)) }
        """.trimIndent()
    )

    @Test fun forLoopSum() = check("15", main("var total = 0\nfor i in 1..5 {\ntotal = total + i\n}\nstd::println(total)"))
    @Test fun whileLoop() = check("8", main("var x = 20\nwhile x > 10 {\nx = x - 4\n}\nstd::println(x)"))

    @Test fun loopBreakContinue() = check(
        "1\n2\n4",
        main("var i = 0\nloop {\ni = i + 1\nif i == 3 { continue }\nif i > 4 { break }\nstd::println(i)\n}")
    )

    @Test fun labeledBreakAndContinueUseColonSyntax() = check(
        "2",
        main(
            """
            var count = 0
            outer: for i in 0..<4 {
                for j in 0..<4 {
                    if i == 2 { break:outer }
                    count++
                    continue:outer
                }
            }
            std::println(count)
            """.trimIndent(),
        ),
    )

    @Test fun whenBranches() = check(
        "two or three",
        main(
            """
            var grade = 2
            when grade {
                1 -> { std::println("one") }
                2, 3 -> { std::println("two or three") }
                else -> { std::println("other") }
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
        func main() { std::println(fact(5)) }
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
            std::println(p.x)
            std::println(p.x + p.y - 1) }
        """.trimIndent()
    )

    @Test fun arrayIndexAndLength() = check(
        "25\n3", main("let nums = @arr[10, 20, 30]\nnums[1] = 25\nstd::println(nums[1])\nstd::println(nums.length)")
    )

    @Test fun functionCalls() = check(
        "25",
        """
        import std.io
        func square(n: Int): Int { return n * n }
        func main() { var n = 5
            std::println(square(n)) }
        """.trimIndent()
    )
}
