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

    @Test fun printsHello() = check("hello", main("""println("hello")"""))
    @Test fun traceUsesRuntimeLevelAndImplicitReceiver() = check(
        "[WARN] LogLevel.Warn: wasm",
        main("fin level = LogLevel.Warn\ntrace level { \"${'$'}{it}: wasm\" }")
    )
    @Test fun traceAcceptsQualifiedDirectLevel() =
        check("[ERROR] Error", main("trace LogLevel.Error \"Error\""))
    @Test fun printWritesWithoutNewline() =
        check("Hello, 7!", main("print(\"Hello, \" )\nprint(7)\nprintln(\"!\")"))
    @Test fun arithmetic() = check("14", main("""println(2 + 3 * 4)"""))

    @Test fun trailingLambdaExecutesThroughCallableParameter() = check(
        "12",
        """
            import std.io
            func apply(value: Int, action: (Int) -> Int): Int {
                return action(value)
            }
            func main() {
                println(apply(4) { value -> value * 3 })
            }
        """.trimIndent(),
    )

    @Test fun lazyFinInitializesOnFirstReadOnly() = check(
        "before\ninit\n42\n42",
        """
            import std.io
            func make(): Int {
                println("init")
                return 42
            }
            func main() {
                lazy fin answer = make()
                println("before")
                println(answer)
                println(answer)
            }
        """.trimIndent(),
    )

    @Test fun reactiveEffectRerunsAfterDependencyAssignment() = check(
        "1\n7",
        """
            import std.io
            react func main() {
                remember var value = 1
                effect { println(value) }
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
                println(counter())
                println(counter())
                println(counter())
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
                effect doubled { println(doubled) }
                effect { println(doubled) }
                value = 3
            }
        """.trimIndent(),
    )

    // A `Double` prints as a `Double` on every backend: an integral value keeps its `.0`.
    // WebAssembly has no sin/cos opcode, so the compiler supplies them in software
    // rather than importing them from the host. Cody-Waite reduction plus the odd/even
    // Taylor polynomials agrees with the interpreter to ~13 significant digits, which is
    // the accuracy this tier promises; `vha::sin` is where exactness belongs.
    @Test fun softwareSinAndCos() = check(
        "0.0\n0.841470984807901\n1.0\n-0.989992496600445",
        "import std.math\n" + main(
            "var a = 0.0\nvar b = 1.0\nvar c = 3.0\n" +
                "println(sin(a))\nprintln(sin(b))\n" +
                "println(cos(a))\nprintln(cos(c))"
        )
    )

    // exp/log and everything derived from them, also supplied in software.
    @Test fun softwareExpAndLog() = check(
        "2.718281828459045\n0.999999999999926\n2.0\n3.0\n8.0\n3.0",
        "import std.math\n" + main(
            "var one = 1.0\nvar e = 2.718281828459045\nvar four = 4.0\n" +
                "var thousand = 1000.0\nvar three = 3.0\nvar twentyseven = 27.0\n" +
                "println(exp(one))\nprintln(log(e))\n" +
                "println(log2(four))\nprintln(log10(thousand))\n" +
                "println(exp2(three))\nprintln(cbrt(twentyseven))"
        )
    )

    // Inverse trigonometry and hypot. `atan` carries ~8 significant digits after its
    // double reduction, which is the float precision this tier promises; exactness is
    // what `vha::` is for. (`pow` is a generic `pow<T>`, not a bridge, so it
    // is not part of the software math.)
    @Test fun softwareInverseTrigAndHypot() = check(
        "0.785398131668175\n1.570796326794896\n0.0\n2.356194521921618\n5.0",
        "import std.math\n" + main(
            "var one = 1.0\nvar zero = 0.0\nvar neg = 0.0 - 1.0\n" +
                "var three = 3.0\nvar four = 4.0\nvar two = 2.0\n" +
                "println(atan(one))\nprintln(asin(one))\n" +
                "println(acos(one))\nprintln(atan2(one, neg))\n" +
                "println(hypot(three, four))"
        )
    )

    // `vha::sin` carries the series two terms further than `sin`, which shows
    // up once the range reduction is exact: against a true sin(1) of 0.841470984807897,
    // the default lands on ...901 and vha on ...896. cos(3) is exact in both tiers here,
    // the extra terms mattering only where the argument reduces less kindly.
    @Test fun vhaTrigIsMoreAccurateThanTheDefault() = check(
        "0.841470984807901\n0.841470984807896\n-0.989992496600445\n-0.989992496600445",
        "import std.math\n" + main(
            "var one = 1.0\nvar three = 3.0\n" +
                "println(sin(one))\nprintln(vha::sin(one))\n" +
                "println(cos(three))\nprintln(vha::cos(three))"
        )
    )

    @Test fun printsRealValues() = check(
        "1.5\n4.0\n0.25",
        main("println(1.5)\nprintln(4.0)\nprintln(0.25)")
    )

    @Test fun interpolatesRealAndLong() = check(
        "r=1.5 g=7",
        main("var r = 1.5\nvar g: Long = Long(7)\nprintln(\"r=${'$'}{r} g=${'$'}{g}\")")
    )

    @Test fun integerDivisionTruncates() = check(
        "3", main("var total = 0\nfor i in 1..17 {\ntotal = total + 1\n}\nprintln(total / 5)")
    )

    @Test fun negativeIntegerDivisionTruncatesTowardZero() = check(
        "-3", main("var n = 0\nwhile n > -7 {\nn = n - 1\n}\nprintln(n / 2)")
    )

    @Test fun doubleDivision() = check("3.5", main("var x = 7.0\nprintln(x / 2.0)"))
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
        import std.io
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
            println(count)
            """.trimIndent(),
        ),
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
        import std.io
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
        import std.io
        pack Point { var x: Int
            var y: Int }
        func main() { let p = Point(3, 4)
            p.x = p.x + 1
            println(p.x)
            println(p.x + p.y - 1) }
        """.trimIndent()
    )

    @Test fun arrayIndexAndLength() = check(
        "25\n3", main("let nums = @arr[10, 20, 30]\nnums[1] = 25\nprintln(nums[1])\nprintln(nums.size)")
    )

    @Test fun functionCalls() = check(
        "25",
        """
        import std.io
        func square(n: Int): Int { return n * n }
        func main() { var n = 5
            println(square(n)) }
        """.trimIndent()
    )
}
