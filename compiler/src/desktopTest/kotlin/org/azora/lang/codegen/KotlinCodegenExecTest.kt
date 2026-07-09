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
 * End-to-end tests for the [org.azora.lang.backend.KotlinCodegen] backend.
 *
 * Each test compiles an Azora program to Kotlin, builds it with `kotlinc`,
 * runs the jar, and asserts on standard output. Because `kotlinc` start-up
 * costs seconds per compile, the coverage is packed into two broad programs
 * instead of many small ones.
 *
 * Tests skip themselves when no `kotlinc` is available.
 */
class KotlinCodegenExecTest {

    private fun check(expected: String, source: String) {
        if (!KotlinExec.available) return
        assertEquals(expected, KotlinExec.run(source))
    }

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

    /** Scalars: arithmetic, bitwise (incl. `~` → `.inv()`), control flow, strings, lambdas. */
    @Test fun scalarFeatures() = check(
        "sum = 15\n8\n2\n-11\n16\nababab\ntwo or three\n10\npositive",
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
            var total = 0
            for i in 1..5 {
                total = total + i
            }
            println("sum = ${'$'}total")
            while total > 10 {
                total = total - 7
            }
            println(total)
            println(total / 3)
            println(~10)
            println(1 << 4)
            var s = "ab"
            println(s * 3)
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
            var double = { x: Int -> x * 2 }
            println(double(5))
            println(classify(total))
        }
        """.trimIndent()
    )

    /** Aggregates & errors: structs, arrays, try/catch/throw. */
    @Test fun aggregateAndErrorFeatures() = check(
        "4\n7\n25\n3\ncaught: boom\nafter",
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
            let nums = [10, 20, 30]
            nums[1] = 25
            println(nums[1])
            println(nums.length)
            try {
                throw "boom"
                println("unreachable")
            } catch { e ->
                println("caught: ${'$'}e")
            }
            println("after")
        }
        """.trimIndent()
    )
}
