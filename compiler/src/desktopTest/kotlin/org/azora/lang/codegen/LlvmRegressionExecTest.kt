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
import kotlin.test.assertTrue

/**
 * Regression tests for specific [org.azora.lang.backend.LlvmCodegen] fixes,
 * executed with `lli` (skipped when no LLVM toolchain is available).
 *
 * - `when` over a String must compare **contents** (strcmp), not pointer
 *   identity — a runtime-built string must still match a literal pattern.
 * - Unsigned integers must print with unsigned printf conversions
 *   (`%u` / `%llu`), not sign-interpreted ones.
 */
class LlvmRegressionExecTest {

    private fun check(expected: String, source: String) {
        if (!LlvmExec.available) return
        assertEquals(expected, LlvmExec.run(source), "debug IR")
        assertEquals(expected, LlvmExec.run(source, optimized = true), "optimized IR")
    }

    private fun main(body: String): String = "func main() {\n$body\n}"

    @Test fun namedTaskAwaitUsesPayloadAbi() = check(
        "42",
        """
        task answer(): Int { return 42 }
        task main() {
            fin value = await answer()
            println(value)
        }
        """.trimIndent()
    )

    @Test fun namedTaskParametersAreCopiedIntoTaskContext() = check(
        "42",
        """
        task add(a: Int, b: Int): Int { return a + b }
        task main() {
            fin value = await add(19, 23)
            println(value)
        }
        """.trimIndent()
    )

    @Test fun completedAsyncBlocksRemainValidLlvm() = check(
        "42",
        """
        task main() {
            fin value = async { 42 }
            println(await value)
        }
        """.trimIndent()
    )

    @Test fun asyncBlocksCaptureLocalValuesOnSpawn() = check(
        "42",
        """
        task main() {
            fin seed = 40
            fin value = async { seed + 2 }
            println(await value)
        }
        """.trimIndent()
    )

    @Test fun unawaitedChildTasksJoinAtScopeExit() = check(
        "42",
        """
        task child(): Int {
            println(42)
            return 0
        }
        task main() {
            child()
        }
        """.trimIndent()
    )

    @Test fun taskThreadsInitializeThreadLocalAggregates() = check(
        "42",
        """
        threadlocal var numbers = [41]
        task read(): Int { return numbers[0] + 1 }
        task main() {
            println(await read())
        }
        """.trimIndent()
    )

    @Test fun zoneAllocWaitsForChildTasksBeforeFreeingArena() = check(
        "42",
        """
        task main() {
            zone alloc {
                var p: Int* = alloc 41
                fin value = async { *p + 1 }
                println(await value)
            }
        }
        """.trimIndent()
    )

    @Test fun cancelCallsNativeTaskCancellationRuntime() = check(
        "42",
        """
        task answer(): Int { return 42 }
        task main() {
            fin value = answer()
            println(await value)
            cancel(value)
        }
        """.trimIndent()
    )

    @Test fun cancelLoweringIncludesPthreadCancel() {
        val ir = LlvmExec.compile(
            """
            task answer(): Int { return 42 }
            task main() {
                fin value = answer()
                cancel(value)
            }
            """.trimIndent()
        )
        assertTrue("declare i32 @pthread_cancel" in ir)
        assertTrue("call void @__azora_task_cancel" in ir)
    }

    /** A concatenated string has a fresh pointer — only strcmp can match it. */
    @Test fun whenOnRuntimeString() = check(
        "H",
        main(
            """
            let s = "he" + "llo"
            when s {
                "world" -> { println("W") }
                "hello" -> { println("H") }
                else -> { println("?") }
            }
            """.trimIndent()
        )
    )

    @Test fun ifBranchShadowDoesNotLeakPastBranch() = check(
        "2\n1",
        main(
            """
            var x = 1
            if true {
                var x = 2
                println(x)
            }
            println(x)
            """.trimIndent()
        )
    )

    @Test fun whenBranchShadowDoesNotLeakPastBranch() = check(
        "5\n1",
        main(
            """
            var x = 1
            when x {
                1 -> {
                    var x = 5
                    println(x)
                }
                else -> { println(0) }
            }
            println(x)
            """.trimIndent()
        )
    )

    @Test fun threadLocalAssignmentUsesLlvmTlsStorage() {
        val source = """
            threadlocal var counter = 0
            func main() {
                counter = 5
            }
        """.trimIndent()
        for (optimized in listOf(false, true)) {
            val ir = LlvmExec.compile(source, optimized)
            assertTrue("@__tl__counter = thread_local global i32 0" in ir)
            assertTrue("store i32 5, i32* @__tl__counter" in ir)
        }
    }

    @Test fun threadLocalAggregatesUseTlsAndRuntimeInitialization() {
        val source = """
            threadlocal var numbers = [1, 2, 3]
            threadlocal var names = ["first": 10, "second": 20]
            threadlocal var unique = ![1, 2, 2, 3]
            func main() {
                println(numbers[1])
                println(names["second"])
                println(unique.length)
            }
        """.trimIndent()
        for (optimized in listOf(false, true)) {
            val ir = LlvmExec.compile(source, optimized)
            assertTrue("@__tl__numbers = thread_local global i8* zeroinitializer" in ir)
            assertTrue("@__tl__names = thread_local global i8* zeroinitializer" in ir)
            assertTrue("@__tl__unique = thread_local global i8* zeroinitializer" in ir)
            assertTrue("define void @__azora_init_threadlocals()" in ir)
            assertTrue("store i8*" in ir && "i8** @__tl__numbers" in ir)
            assertTrue("store i8*" in ir && "i8** @__tl__names" in ir)
            assertTrue("store i8*" in ir && "i8** @__tl__unique" in ir)
            assertTrue("call void @__azora_init_threadlocals()" in ir)
            assertTrue("define i8* @__emutls_get_address" in ir)
        }
    }

    @Test fun decimalCollectionsUseExplicitPackedAlignment() {
        val ir = LlvmExec.compile(
            """
            func main() {
                var array = [1.5D, 2.5D]
                var map = ["value": 3.5D]
                var set = ![1.5D, 2.5D]
                array[0] = map["value"]
                println(set.length)
            }
            """.trimIndent()
        )
        assertTrue("store fp128" in ir && "align 1" in ir)
        assertTrue("load fp128" in ir && "align 1" in ir)
    }

    @Test fun whenOnStringMultiPattern() = check(
        "vowel\nother",
        """
        func kind(s: String): String {
            when s {
                "a", "e" -> { return "vowel" }
                else -> { return "other" }
            }
            return "unreachable"
        }
        func main() {
            println(kind("e" + ""))
            println(kind("z" + ""))
        }
        """.trimIndent()
    )

    @Test fun whenOnStringElseBranch() = check(
        "?",
        main(
            """
            let s = "x" + "y"
            when s {
                "ab" -> { println("AB") }
                else -> { println("?") }
            }
            """.trimIndent()
        )
    )

    /** UInt underflow wraps to 2^32-1 and must print unsigned (`%u`). */
    @Test fun uintPrintsUnsigned() = check(
        "4294967295",
        main(
            """
            var x: UInt = 5u
            x = x - 6u
            println(x)
            """.trimIndent()
        )
    )

    /** ULong underflow wraps to 2^64-1 and must print unsigned (`%llu`). */
    @Test fun ulongPrintsUnsigned() = check(
        "18446744073709551615",
        main(
            """
            var y: ULong = 0uL
            y = y - 1uL
            println(y)
            """.trimIndent()
        )
    )

    /** String interpolation of an unsigned value routes through the %llu helper. */
    @Test fun uintInterpolatesUnsigned() = check(
        "v = 4294967295",
        main(
            """
            var x: UInt = 0u
            x = x - 1u
            println("v = ${'$'}x")
            """.trimIndent()
        )
    )

    /** Signed printing is unchanged. */
    @Test fun signedIntStillPrintsSigned() = check(
        "-6",
        main(
            """
            var x = 0
            x = x - 6
            println(x)
            """.trimIndent()
        )
    )
}
