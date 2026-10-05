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
 *   identity - a runtime-built string must still match a literal pattern.
 * - Unsigned integers must print with unsigned printf conversions
 *   (`%u` / `%llu`), not sign-interpreted ones.
 */
class LlvmRegressionExecTest {

    private fun check(expected: String, source: String) {
        if (!LlvmExec.available) return
        assertEquals(expected, LlvmExec.run(source), "debug IR")
        assertEquals(expected, LlvmExec.run(source, optimized = true), "optimized IR")
    }

    private fun main(body: String): String = "import std.io\nfunc main() {\n$body\n}"

    @Test fun namedTaskAwaitUsesPayloadAbi() = check(
        "42",
        """
        import std.io
        async func answer(): Int { return 42 }
        async func main() {
            fin value = await answer()
            println(value)
        }
        """.trimIndent()
    )

    @Test fun namedTaskParametersAreCopiedIntoTaskContext() = check(
        "42",
        """
        import std.io
        async func add(a: Int, b: Int): Int { return a + b }
        async func main() {
            fin value = await add(19, 23)
            println(value)
        }
        """.trimIndent()
    )

    @Test fun completedAsyncBlocksRemainValidLlvm() = check(
        "42",
        """
        import std.io
        async func main() {
            fin value = async { 42 }
            println(await value)
        }
        """.trimIndent()
    )

    @Test fun asyncBlocksCaptureLocalValuesOnSpawn() = check(
        "42",
        """
        import std.io
        async func main() {
            fin seed = 40
            fin value = async [seed.&] { seed + 2 }
            println(await value)
        }
        """.trimIndent()
    )

    @Test fun unawaitedChildTasksJoinAtScopeExit() = check(
        "42",
        """
        import std.io
        async func child(): Int {
            println(42)
            return 0
        }
        async func main() {
            child()
        }
        """.trimIndent()
    )

    @Test fun taskThreadsInitializeThreadLocalAggregates() = check(
        "42",
        """
        import std.io
        threadlocal var numbers = [41]
        async func read(): Int { return numbers[0] + 1 }
        async func main() {
            println(await read())
        }
        """.trimIndent()
    )

    @Test fun unsafeCancellationIsReportedWithoutEmittingNativeOutput() {
        val source = """
            import std.io
            import std.concurrency.async
            async func answer(): Int { return 42 }
            async func main() {
                fin value = answer()
                concurrency::cancel(value)
            }
        """.trimIndent()
        for (release in listOf(false, true)) {
            val result = org.azora.lang.Compiler().compile(source, release = release)
            kotlin.test.assertIs<org.azora.lang.CompilationResult.Success>(result)
            assertTrue(result.llvm.isEmpty())
            assertTrue(result.backendErrors["llvm"].orEmpty().contains("cancellation-safe ownership cleanup"))
        }
    }

    /** A concatenated string has a fresh pointer - only strcmp can match it. */
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
            import std.io
            threadlocal var counter = 0
            func main() {
                counter = 5
            }
        """.trimIndent()
        for (optimized in listOf(false, true)) {
            val ir = LlvmExec.compile(source, optimized)
            assertTrue("@__tl_counter = thread_local global i32 0" in ir)
            assertTrue("store i32 5, i32* @__tl_counter" in ir)
        }
    }

    @Test fun threadLocalAggregatesUseTlsAndRuntimeInitialization() {
        val source = """
            import std.io
            import std.container.set
            threadlocal var numbers = [1, 2, 3]
            threadlocal var names = ["first": 10, "second": 20]
            threadlocal var unique: Set<Int> = [1, 2, 2, 3]
            func main() {
                println(numbers[1])
                println(names["second"])
                println(unique.size)
            }
        """.trimIndent()
        // Each slot is per-thread and starts zeroed; the value is built when the
        // thread starts. A map literal builds a `LinkedHashMap`, whose slot is a
        // pointer to that pack rather than an untyped one, so the slot's type is
        // not what is checked - only that it is thread-local and filled at run time.
        fun slotOf(ir: String, name: String): String? =
            ir.lineSequence().firstOrNull { it.startsWith("@__tl_$name = thread_local global ") }
        for (optimized in listOf(false, true)) {
            val ir = LlvmExec.compile(source, optimized)
            for (name in listOf("numbers", "names", "unique")) {
                val slot = slotOf(ir, name)
                assertTrue(slot != null && slot.endsWith(" zeroinitializer"), "slot for $name: $slot")
                assertTrue(Regex("store .+\\* @__tl_$name\\b").containsMatchIn(ir), "no runtime store to $name")
            }
            assertTrue("define void @__azora_init_threadlocals()" in ir)
            assertTrue("call void @__azora_init_threadlocals()" in ir)
            assertTrue("define i8* @__emutls_get_address" in ir)
            if (LlvmExec.available) assertEquals("2\n20\n3", LlvmExec.runIr(ir), "optimized=$optimized")
        }
    }

    @Test fun threadLocalStdlibCollectionsInitializeObjects() = check(
        "3\n2\n3",
        """
        import std.io
        import std.container.list
        import std.container.map
        import std.container.set
        threadlocal var numbers: List<Int> = [1, 2, 3]
        threadlocal var names: Map<String, Int> = ["first": 10, "second": 20]
        threadlocal var unique: Set<Int> = [1, 2, 2, 3]
        func main() {
            println(numbers.size)
            println(names.size)
            println(unique.size)
        }
        """.trimIndent()
    )

    /**
     * A set is left out: its elements must be `Equal`, and a float is only
     * `PartialEqual` (GTC §8.4). The array and the map store the same `fp128`.
     */
    @Test fun decimalCollectionsUseExplicitPackedAlignment() {
        val ir = LlvmExec.compile(
            """
            import std.io
            import std.container.map
            func main() {
                var array = [Quad(1.5), Quad(2.5)]
                var map: Map<String, Quad> = ["value": Quad(3.5)]
            }
            """.trimIndent()
        )
        assertTrue("store fp128" in ir && "align 1" in ir)
    }

    @Test fun whenOnStringMultiPattern() = check(
        "vowel\nother",
        """
        import std.io
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
            var x: UInt = UInt(5)
            x = x - UInt(6)
            println(x)
            """.trimIndent()
        )
    )

    /** ULong underflow wraps to 2^64-1 and must print unsigned (`%llu`). */
    @Test fun ulongPrintsUnsigned() = check(
        "18446744073709551615",
        main(
            """
            var y: ULong = ULong(0)
            y = y - ULong(1)
            println(y)
            """.trimIndent()
        )
    )

    /** String interpolation of an unsigned value routes through the %llu helper. */
    @Test fun uintInterpolatesUnsigned() = check(
        "v = 4294967295",
        main(
            $$"""
            var x: UInt = UInt(0)
            x = x - UInt(1)
            println("v = $x")
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
