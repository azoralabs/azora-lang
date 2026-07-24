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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * End-to-end tests for the arr@[org.azora.lang.backend.JavaScriptCodegen] backend.
 *
 * Each test compiles a small Azora program to JavaScript, executes it with
 * Node.js, and asserts on the program's standard output — validating that the
 * generated JavaScript is both syntactically valid and semantically matches
 * the interpreter.
 *
 * Tests skip themselves when no `node` is available.
 */
class JavaScriptExecTest {

    /** Runs arr@[source] and asserts its stdout equals arr@[expected] (skips without `node`). */
    private fun check(expected: String, source: String) {
        if (!NodeExec.available) return
        assertEquals(expected, NodeExec.run(source))
    }

    private fun main(body: String): String = "import std.io\nfunc main() {\n$body\n}"

    @Test fun traceUsesLogLevel() = check(
        "[WARN] LogLevel.Warn: browser ready",
        main("fin level = LogLevel.Warn\ntrace level { \"${'$'}{it}: browser ready\" }")
    )

    @Test fun structuredTasksAndAsyncBlocks() = check(
        "42",
        """
        import std.io
        task left(): Int { return 19 }
        task main() {
            fin a = left()
            fin b = async { 23 }
            fin av = await a
            fin bv = await b
            std::println(av + bv)
        }
        """.trimIndent()
    )

    @Test fun taskMainJoinsUnawaitedChildren() = check(
        "main\nchild",
        """
        import std.io
        task child() { std::println("child") }
        task main() {
            child()
            std::println("main")
        }
        """.trimIndent()
    )

    // -----------------------------------------------------------------------
    // Basics
    // -----------------------------------------------------------------------

    @Test fun printsHello() =
        check("hello", main("""std::println("hello")"""))

    @Test fun printWritesWithoutNewline() =
        check("Hello, 7!", main("std::print(\"Hello, \" )\nstd::print(7)\nstd::println(\"!\")"))

    @Test fun tuplePrintMatchesAzoraStructuralFormat() = check(
        "Tuple<String, String>(\"Hello from Azora!\", \":)\")",
        """
        module playground
        import std.io
        import std.container.tuple

        pack App { var name: String }

        impl App {
            func greet(): String { self& ->
                return "Hello from ${'$'}{self.name}!"
            }
        }

        func main() {
            fin app = App("Azora")
            std::println(std::tupleOf(app.greet(), ":)"))
        }
        """.trimIndent(),
    )

    @Test fun packConstructorsCannotShadowJavaScriptGlobals() {
        val javascript = NodeExec.compile(
            """
            import std.io

            pack HostValue { fin value: Int }

            func main() {
                fin value = HostValue(7)
                std::println(value.value)
            }
            """.trimIndent(),
        )

        assertContains(javascript, "class __azoraPack_HostValue")
        assertContains(javascript, "new __azoraPack_HostValue(7)")
        assertFalse("class Array {" in javascript)
    }

    @Test fun arithmetic() =
        check("14", main("""std::println(2 + 3 * 4)"""))

    /** Integer division must truncate (JS `/` would print 3.4). */
    @Test fun integerDivisionTruncates() = check(
        "3",
        main(
            """
            var total = 0
            for i in 1..17 {
                total = total + 1
            }
            std::println(total / 5)
            """.trimIndent()
        )
    )

    /** Negative integer division truncates toward zero (Math.trunc, not floor). */
    @Test fun negativeIntegerDivisionTruncatesTowardZero() = check(
        "-3",
        main(
            """
            var n = 0
            while n > -7 {
                n = n - 1
            }
            std::println(n / 2)
            """.trimIndent()
        )
    )

    @Test fun realDivision() = check(
        "3.5",
        main(
            """
            var x = 7.0
            std::println(x / 2.0)
            """.trimIndent()
        )
    )

    @Test fun modulo() = check(
        "2",
        main(
            """
            var n = 17
            std::println(n % 5)
            """.trimIndent()
        )
    )

    @Test fun bitwiseOps() = check(
        "2\n11\n9\n16\n64\n-11",
        main(
            """
            var a = 10
            std::println(a & 6)
            std::println(a | 1)
            std::println(a ^ 3)
            std::println(1 << 4)
            std::println(256 >> 2)
            std::println(~a)
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
            std::println("n = " + "${'$'}n" + "!")
            """.trimIndent()
        )
    )

    @Test fun stringRepeat() = check(
        "ababab",
        main(
            """
            var s = "ab"
            std::println(s * 3)
            """.trimIndent()
        )
    )

    @Test fun stringEquality() = check(
        "true\nfalse",
        main(
            """
            var s = "he" + "llo"
            std::println(s == "hello")
            std::println(s == "world")
            """.trimIndent()
        )
    )

    // -----------------------------------------------------------------------
    // Control flow
    // -----------------------------------------------------------------------

    @Test fun ifElseChain() = check(
        "positive",
        """
        import std.io
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
            std::println(classify(n))
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
            std::println(total)
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
            std::println(x)
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
                std::println(i)
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
                    std::println(msg)
                }
                2, 3 -> {
                    let msg = "two or three"
                    std::println(msg)
                }
                else -> {
                    let msg = "other"
                    std::println(msg)
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
        import std.io
        pack Point {
            var x: Int
            var y: Int
        }
        func main() {
            let p = Point(3, 4)
            p.x = p.x + 1
            std::println(p.x)
            std::println(p.x + p.y - 1)
        }
        """.trimIndent()
    )

    @Test fun arrayIndexAndLength() = check(
        "25\n3",
        main(
            """
            let nums = arr@[10, 20, 30]
            nums[1] = 25
            std::println(nums[1])
            std::println(nums.length)
            """.trimIndent()
        )
    )

    // -----------------------------------------------------------------------
    // Functions & lambdas
    // -----------------------------------------------------------------------

    @Test fun functionCalls() = check(
        "25",
        """
        import std.io
        func square(n: Int): Int {
            return n * n
        }
        func main() {
            var n = 5
            std::println(square(n))
        }
        """.trimIndent()
    )

    @Test fun lambdaValue() = check(
        "10",
        main(
            """
            var double = { x: Int -> x * 2 }
            std::println(double(5))
            """.trimIndent()
        )
    )

    @Test fun higherOrderFunction() = check(
        "16",
        """
        import std.io
        func apply(f: (Int) -> Int, x: Int): Int {
            return f(x)
        }
        func main() {
            std::println(apply({ x: Int -> x * x }, 4))
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
                std::println("unreachable")
            } catch { e ->
                std::println("caught: ${'$'}e")
            }
            std::println("after")
            """.trimIndent()
        )
    )
}
