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
 * End-to-end tests for the [org.azora.lang.backend.DartCodegen] backend.
 *
 * Each test compiles a small Azora program to Dart, executes it with the `dart`
 * runtime, and asserts on the program's standard output — validating that the
 * generated Dart is both syntactically valid and semantically matches the
 * interpreter.
 *
 * Tests skip themselves when no `dart` is available.
 */
class DartCodegenExecTest {

    /** Runs [source] and asserts its stdout equals [expected] (skips without `dart`). */
    private fun check(expected: String, source: String) {
        if (!DartExec.available) return
        assertEquals(expected, DartExec.run(source))
    }

    private fun main(body: String): String = "func main() {\n$body\n}"

    // -----------------------------------------------------------------------
    // Basics
    // -----------------------------------------------------------------------

    @Test fun printsHello() =
        check("hello", main("""println("hello")"""))

    @Test fun arithmetic() =
        check("14", main("""println(2 + 3 * 4)"""))

    /** Integer division must truncate via `~/` (Dart `/` yields 3.4). */
    @Test fun integerDivisionTruncates() = check(
        "3",
        main(
            """
            var total = 0
            for i in 1..17 {
                total = total + 1
            }
            println(total / 5)
            """.trimIndent()
        )
    )

    /** Negative integer division truncates toward zero. */
    @Test fun negativeIntegerDivisionTruncatesTowardZero() = check(
        "-3",
        main(
            """
            var n = 0
            while n > -7 {
                n = n - 1
            }
            println(n / 2)
            """.trimIndent()
        )
    )

    @Test fun realDivision() = check(
        "3.5",
        main(
            """
            var x = 7.0
            println(x / 2.0)
            """.trimIndent()
        )
    )

    @Test fun modulo() = check(
        "2",
        main(
            """
            var n = 17
            println(n % 5)
            """.trimIndent()
        )
    )

    @Test fun bitwiseOps() = check(
        "2\n11\n9\n16\n64\n-11",
        main(
            """
            var a = 10
            println(a & 6)
            println(a | 1)
            println(a ^ 3)
            println(1 << 4)
            println(256 >> 2)
            println(~a)
            """.trimIndent()
        )
    )

    // -----------------------------------------------------------------------
    // Strings
    // -----------------------------------------------------------------------

    @Test fun stringConcatAndInterpolation() = check(
        "n = 5!",
        main(
            """
            var n = 5
            println("n = " + "${'$'}n" + "!")
            """.trimIndent()
        )
    )

    @Test fun stringRepeat() = check(
        "ababab",
        main(
            """
            var s = "ab"
            println(s * 3)
            """.trimIndent()
        )
    )

    @Test fun stringEquality() = check(
        "true\nfalse",
        main(
            """
            var s = "he" + "llo"
            println(s == "hello")
            println(s == "world")
            """.trimIndent()
        )
    )

    // -----------------------------------------------------------------------
    // Control flow
    // -----------------------------------------------------------------------

    @Test fun ifElseChain() = check(
        "positive",
        """
        func classify(n: Int): String {
            if n < 0 {
                return "negative"
            } else if n == 0 {
                return "zero"
            }
            return "positive"
        }
        func main() {
            var n = 3
            println(classify(n))
        }
        """.trimIndent()
    )

    @Test fun forLoopSum() = check(
        "15",
        main(
            """
            var total = 0
            for i in 1..5 {
                total = total + i
            }
            println(total)
            """.trimIndent()
        )
    )

    @Test fun whileLoop() = check(
        "8",
        main(
            """
            var x = 20
            while x > 10 {
                x = x - 4
            }
            println(x)
            """.trimIndent()
        )
    )

    @Test fun loopBreakContinue() = check(
        "1\n2\n4",
        main(
            """
            var i = 0
            loop {
                i = i + 1
                if i == 3 {
                    continue
                }
                if i > 4 {
                    break
                }
                println(i)
            }
            """.trimIndent()
        )
    )

    /** Each `when` branch declares a binding — exercises switch-case block scoping. */
    @Test fun whenBranchesWithLocalDeclarations() = check(
        "two or three",
        main(
            """
            var grade = 2
            when grade {
                1 -> {
                    let msg = "one"
                    println(msg)
                }
                2, 3 -> {
                    let msg = "two or three"
                    println(msg)
                }
                else -> {
                    let msg = "other"
                    println(msg)
                }
            }
            """.trimIndent()
        )
    )

    // -----------------------------------------------------------------------
    // Aggregates
    // -----------------------------------------------------------------------

    @Test fun structFieldMutation() = check(
        "4\n7",
        """
        pack Point {
            var x: Int
            var y: Int
        }
        func main() {
            let p = Point(3, 4)
            p.x = p.x + 1
            println(p.x)
            println(p.x + p.y - 1)
        }
        """.trimIndent()
    )

    @Test fun arrayIndexAndLength() = check(
        "25\n3",
        main(
            """
            let nums = [10, 20, 30]
            nums[1] = 25
            println(nums[1])
            println(nums.length)
            """.trimIndent()
        )
    )

    // -----------------------------------------------------------------------
    // Functions & lambdas
    // -----------------------------------------------------------------------

    @Test fun functionCalls() = check(
        "25",
        """
        func square(n: Int): Int {
            return n * n
        }
        func main() {
            var n = 5
            println(square(n))
        }
        """.trimIndent()
    )

    @Test fun lambdaValue() = check(
        "10",
        main(
            """
            var double = { x: Int -> x * 2 }
            println(double(5))
            """.trimIndent()
        )
    )

    @Test fun higherOrderFunction() = check(
        "16",
        """
        func apply(f: (Int) -> Int, x: Int): Int {
            return f(x)
        }
        func main() {
            println(apply({ x: Int -> x * x }, 4))
        }
        """.trimIndent()
    )

    // -----------------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------------

    @Test fun tryCatch() = check(
        "caught: boom\nafter",
        main(
            """
            try {
                throw "boom"
                println("unreachable")
            } catch { e ->
                println("caught: ${'$'}e")
            }
            println("after")
            """.trimIndent()
        )
    )

    // -----------------------------------------------------------------------
    // Structured concurrency
    // -----------------------------------------------------------------------

    /** Awaited spawned tasks yield deterministic results. */
    @Test fun structuredTasksAndAsyncBlocks() = check(
        "42",
        """
        task left(): Int { return 19 }
        task main() {
            fin a = left()
            fin b = async { 23 }
            fin av = await a
            fin bv = await b
            println(av + bv)
        }
        """.trimIndent()
    )

    @Test fun taskMainJoinsUnawaitedChildren() = check(
        "main\nchild",
        """
        task child() { println("child") }
        task main() {
            child()
            println("main")
        }
        """.trimIndent()
    )
}
