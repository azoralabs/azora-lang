/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A safe index names one of the array's own elements, natively too. LLVM and
 * WebAssembly addressed `xs[i]` without looking at the array's size, so an
 * index past the end read and wrote whatever memory followed it. Both now stop
 * the program, as the interpreter does (`IndexBoundsTest`); LLVM says why, and
 * keeps what the program printed before it, which `abort` alone threw away
 * whenever the output was not a terminal.
 */
class IndexBoundsExecTest {
    private val readPastTheEnd = """
        import std.io
        func main() {
            fin xs = [10, 20]
            println(xs[1])
            fin at = 2
            println(xs[at])
            println("unreachable")
        }
    """.trimIndent()

    private val negativeWrite = """
        import std.io
        func main() {
            var xs = [10, 20]
            println("before")
            fin at = -1
            xs[at] = 0
            println("unreachable")
        }
    """.trimIndent()

    private val inBounds = """
        import std.io
        func main() {
            var xs = [10, 20, 30]
            xs[2] = xs[0] + xs[1]
            for i in 0..<3 {
                println(xs[i])
            }
        }
    """.trimIndent()

    @Test fun llvmStopsAtAnIndexPastTheEnd() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(
                "20\npanic: index 2 out of bounds for size 2",
                LlvmExec.runExpectingAbort(readPastTheEnd, optimized),
                "optimized=$optimized",
            )
        }
    }

    @Test fun llvmStopsAtANegativeIndexOnAWrite() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(
                "before\npanic: index -1 out of bounds for size 2",
                LlvmExec.runExpectingAbort(negativeWrite, optimized),
                "optimized=$optimized",
            )
        }
    }

    @Test fun llvmRunsIndicesInsideTheArray() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals("10\n20\n30", LlvmExec.run(inBounds, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyTrapsOutsideTheArray() {
        if (!WasmExec.available) return
        assertEquals("20", WasmExec.runExpectingTrap(readPastTheEnd))
        assertEquals("before", WasmExec.runExpectingTrap(negativeWrite))
        assertEquals("10\n20\n30", WasmExec.run(inBounds))
    }
}
