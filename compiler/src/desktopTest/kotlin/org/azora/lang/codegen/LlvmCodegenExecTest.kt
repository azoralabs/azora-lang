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
 * End-to-end tests for the [org.azora.lang.backend.LlvmCodegen] backend.
 *
 * Each test compiles a small Azora program to LLVM IR, executes it with `lli`,
 * and asserts on the program's standard output. This validates that the
 * generated IR is both **well-formed** (it loads and runs) and **correct**
 * (it produces the expected result).
 *
 * Numeric output formatting follows C `printf`: integers via `%d`/`%lld`,
 * reals via `%g` (so `3.0` prints as `3`, `3.5` as `3.5`), booleans as
 * `true`/`false`, and chars as their glyph.
 *
 * Tests skip themselves when no `lli` toolchain is available so the suite
 * stays green on machines without LLVM.
 */
class LlvmCodegenExecTest {

    /** Runs [source] and asserts its stdout equals [expected] (skips without `lli`). */
    private fun check(expected: String, source: String) {
        if (!LlvmExec.available) return
        assertEquals(expected, LlvmExec.run(source))
    }

    private fun main(body: String): String = "import std.io\nfunc main() {\n$body\n}"

    // -----------------------------------------------------------------------
    // Literals & println
    // -----------------------------------------------------------------------

    @Test fun printsIntLiteral() =
        check("42", main("""std::println(42)"""))

    @Test fun printsNegativeInt() =
        check("-7", main("""std::println(-7)"""))

    @Test fun printsStringLiteral() =
        check("hello", main("""std::println("hello")"""))

    @Test fun printWritesWithoutNewline() =
        check("Hello, 7!", main("std::print(\"Hello, \" )\nstd::print(7)\nstd::println(\"!\")"))

    @Test fun printsBoolLiterals() =
        check("true\nfalse", main("std::println(true)\nstd::println(false)"))

    @Test fun printsChar() =
        check("Q", main("""std::println('Q')"""))

    @Test fun printsReal() =
        check("3.5", main("""std::println(3.5)"""))

    @Test fun printsWholeReal() =
        check("3", main("""std::println(3.0)"""))

    // -----------------------------------------------------------------------
    // Integer arithmetic
    // -----------------------------------------------------------------------

    @Test fun addition() =
        check("30", main("""std::println(10 + 20)"""))

    @Test fun subtraction() =
        check("8", main("""std::println(15 - 7)"""))

    @Test fun multiplication() =
        check("42", main("""std::println(6 * 7)"""))

    @Test fun integerDivision() =
        check("3", main("""std::println(17 / 5)"""))

    @Test fun modulo() =
        check("2", main("""std::println(17 % 5)"""))

    @Test fun precedence() =
        check("14", main("""std::println(2 + 3 * 4)"""))

    @Test fun parenthesizedPrecedence() =
        check("20", main("""std::println((2 + 3) * 4)"""))

    @Test fun negationExpression() =
        check("-15", main("var x = 15\nstd::println(-x)"))

    // -----------------------------------------------------------------------
    // Bitwise & shifts
    // -----------------------------------------------------------------------

    @Test fun bitwiseAnd() = check("2", main("""std::println(10 & 6)"""))
    @Test fun bitwiseOr() = check("11", main("""std::println(10 | 1)"""))
    @Test fun bitwiseXor() = check("9", main("""std::println(10 ^ 3)"""))
    @Test fun shiftLeft() = check("16", main("""std::println(1 << 4)"""))
    @Test fun shiftRight() = check("64", main("""std::println(256 >> 2)"""))

    // -----------------------------------------------------------------------
    // Comparisons & booleans
    // -----------------------------------------------------------------------

    @Test fun comparisons() = check(
        "true\nfalse\ntrue\nfalse",
        main(
            """
            std::println(3 > 2)
            std::println(3 < 2)
            std::println(2 <= 2)
            std::println(2 != 2)
            """.trimIndent()
        )
    )

    @Test fun shortCircuitAnd() = check(
        "false\ntrue",
        main(
            """
            std::println(1 > 2 && 3 > 0)
            std::println(1 < 2 && 3 > 0)
            """.trimIndent()
        )
    )

    @Test fun shortCircuitOr() = check(
        "true\nfalse",
        main(
            """
            std::println(1 > 2 || 3 > 0)
            std::println(1 > 2 || 3 < 0)
            """.trimIndent()
        )
    )

    @Test fun logicalNot() = check("false", main("""std::println(!(1 < 2))"""))

    // -----------------------------------------------------------------------
    // Variables
    // -----------------------------------------------------------------------

    @Test fun variableDeclarationAndUse() =
        check("25", main("var x = 5\nstd::println(x * x)"))

    @Test fun reassignment() =
        check("100", main("var x = 5\nx = 100\nstd::println(x)"))

    @Test fun letAndFinBindings() = check(
        "7",
        main(
            """
            let a = 3
            fin b = 4
            std::println(a + b)
            """.trimIndent()
        )
    )

    // -----------------------------------------------------------------------
    // Control flow: if / else
    // -----------------------------------------------------------------------

    @Test fun ifTrueBranch() =
        check("yes", main("""if 5 > 3 { std::println("yes") } else { std::println("no") }"""))

    @Test fun ifFalseBranch() =
        check("no", main("""if 1 > 3 { std::println("yes") } else { std::println("no") }"""))

    @Test fun ifWithoutElse() =
        check("hit", main("""if true { std::println("hit") }"""))

    @Test fun elseIfChain() = check(
        "Buzz",
        main(
            """
            let n = 10
            if n % 15 == 0 {
                std::println("FizzBuzz")
            } else if n % 3 == 0 {
                std::println("Fizz")
            } else if n % 5 == 0 {
                std::println("Buzz")
            } else {
                std::println(n)
            }
            """.trimIndent()
        )
    )

    // -----------------------------------------------------------------------
    // Loops
    // -----------------------------------------------------------------------

    @Test fun forInclusiveRange() =
        check("15", main("var s = 0\nfor i in 1..5 { s = s + i }\nstd::println(s)"))

    @Test fun forExclusiveRange() =
        check("10", main("var s = 0\nfor i in 0..<5 { s = s + i }\nstd::println(s)"))

    @Test fun whileLoop() =
        check("120", main("var f = 1\nvar i = 1\nwhile i <= 5 { f = f * i\ni = i + 1 }\nstd::println(f)"))

    @Test fun loopWithBreak() =
        check("3", main("var i = 0\nloop { i = i + 1\nif i == 3 { break } }\nstd::println(i)"))

    @Test fun forWithContinue() =
        check("8", main("var s = 0\nfor i in 0..<5 { if i == 2 { continue }\ns = s + i }\nstd::println(s)"))

    @Test fun nestedLoops() = check(
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

    @Test fun breakExitsOnlyInnerLoop() = check(
        "6",
        main(
            """
            var count = 0
            for i in 0..<3 {
                for j in 0..<5 {
                    if j == 2 { break }
                    count = count + 1
                }
            }
            std::println(count)
            """.trimIndent()
        )
    )

    // -----------------------------------------------------------------------
    // Functions & recursion
    // -----------------------------------------------------------------------

    @Test fun simpleFunction() = check(
        "7",
        """
        import std.io
        func add(a: Int, b: Int): Int { return a + b }
        func main() { std::println(add(3, 4)) }
        """.trimIndent()
    )

    @Test fun factorialRecursion() = check(
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

    @Test fun fibonacciRecursion() = check(
        "55",
        """
        import std.io
        func fib(n: Int): Int {
            if n < 2 { return n }
            return fib(n - 1) + fib(n - 2)
        }
        func main() { std::println(fib(10)) }
        """.trimIndent()
    )

    @Test fun mutualRecursion() = check(
        "true\ntrue",
        """
        import std.io
        func isEven(n: Int): Bool {
            if n == 0 { return true }
            return isOdd(n - 1)
        }
        func isOdd(n: Int): Bool {
            if n == 0 { return false }
            return isEven(n - 1)
        }
        func main() {
            std::println(isEven(10))
            std::println(isOdd(7))
        }
        """.trimIndent()
    )

    @Test fun earlyReturnInsideLoop() = check(
        "4",
        """
        import std.io
        func firstAt(limit: Int): Int {
            for i in 0..<limit {
                if i == 4 { return i }
            }
            return -1
        }
        func main() { std::println(firstAt(10)) }
        """.trimIndent()
    )

    @Test fun voidFunctionWithSideEffect() = check(
        "hi\nhi",
        """
        import std.io
        func greet() { std::println("hi") }
        func main() { greet()
        greet() }
        """.trimIndent()
    )

    // -----------------------------------------------------------------------
    // Strings
    // -----------------------------------------------------------------------

    @Test fun stringConcatenation() =
        check("Hello, World!", main("""std::println("Hello, " + "World" + "!")"""))

    @Test fun stringConcatVariable() = check(
        "Hello, Azora!",
        main(
            """
            let name = "Azora"
            std::println("Hello, " + name + "!")
            """.trimIndent()
        )
    )

    @Test fun stringRepetition() =
        check("ababab", main("""std::println("ab" * 3)"""))

    @Test fun stringEquality() = check(
        "true\nfalse",
        main(
            """
            std::println("abc" == "abc")
            std::println("abc" == "xyz")
            """.trimIndent()
        )
    )

    @Test fun stringInequality() =
        check("true", main("""std::println("abc" != "xyz")"""))

    // -----------------------------------------------------------------------
    // String interpolation
    // -----------------------------------------------------------------------

    @Test fun interpolateInt() = check(
        "n = 42",
        main("let n = 42\nstd::println(\"n = \$n\")")
    )

    @Test fun interpolateExpression() = check(
        "double is 84",
        main("let n = 42\nstd::println(\"double is \${n * 2}\")")
    )

    @Test fun interpolateBool() = check(
        "flag: true",
        main("let f = 1 < 2\nstd::println(\"flag: \$f\")")
    )

    @Test fun interpolateChar() = check(
        "letter Z",
        main("std::println(\"letter \${'Z'}\")")
    )

    @Test fun interpolateMultiple() = check(
        "a=1 b=2 sum=3",
        main("let a = 1\nlet b = 2\nstd::println(\"a=\$a b=\$b sum=\${a + b}\")")
    )

    // -----------------------------------------------------------------------
    // when expressions
    // -----------------------------------------------------------------------

    @Test fun whenSingleMatch() = check(
        "mid",
        """
        import std.io
        func grade(n: Int): String {
            when n {
                1 -> { return "low" }
                2 -> { return "mid" }
                else -> { return "high" }
            }
            return "?"
        }
        func main() { std::println(grade(2)) }
        """.trimIndent()
    )

    @Test fun whenMultiPattern() = check(
        "low\nlow\nhigh",
        """
        import std.io
        func grade(n: Int): String {
            when n {
                1, 2, 3 -> { return "low" }
                else -> { return "high" }
            }
            return "?"
        }
        func main() {
            std::println(grade(1))
            std::println(grade(3))
            std::println(grade(9))
        }
        """.trimIndent()
    )

    @Test fun whenElseBranch() = check(
        "other",
        main(
            """
            let x = 99
            when x {
                1 -> { std::println("one") }
                2 -> { std::println("two") }
                else -> { std::println("other") }
            }
            """.trimIndent()
        )
    )

    // -----------------------------------------------------------------------
    // Numeric types
    // -----------------------------------------------------------------------

    @Test fun longArithmetic() = check(
        "10000000002",
        main("let big: Long = 10000000000L\nstd::println(big + 2L)")
    )

    @Test fun realArithmetic() =
        check("7", main("""std::println(3.5 * 2.0)"""))

    @Test fun realDivision() =
        check("2.5", main("""std::println(5.0 / 2.0)"""))

    @Test fun mixedRealExpression() =
        check("6.28", main("let pi = 3.14\nstd::println(pi * 2.0)"))

    // -----------------------------------------------------------------------
    // assert / trace
    // -----------------------------------------------------------------------

    @Test fun assertPassProducesNoOutput() = check(
        "after",
        main(
            """
            assert 1 + 1 == 2 { "math is broken" }
            std::println("after")
            """.trimIndent()
        )
    )

    @Test fun traceEmitsMessage() = check(
        "[TRACE] hello",
        main("""trace { "hello" }""")
    )

    // -----------------------------------------------------------------------
    // Larger integration program
    // -----------------------------------------------------------------------

    @Test fun fizzBuzzToFifteen() = check(
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

    @Test fun sumOfSquares() = check(
        "55",
        """
        import std.io
        func square(x: Int): Int { return x * x }
        func main() {
            var total = 0
            for i in 1..5 {
                total = total + square(i)
            }
            std::println(total)
        }
        """.trimIndent()
    )

    @Test fun gcdAlgorithm() = check(
        "14",
        """
        import std.io
        func gcd(a: Int, b: Int): Int {
            var x = a
            var y = b
            while y != 0 {
                let t = y
                y = x % y
                x = t
            }
            return x
        }
        func main() { std::println(gcd(42, 56)) }
        """.trimIndent()
    )
}
