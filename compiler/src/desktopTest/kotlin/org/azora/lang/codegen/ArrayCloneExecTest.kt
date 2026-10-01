/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `clone` on an array, natively. LLVM's copy of a value passed an array's
 * pointer through unchanged, so a clone shared its original's slots. It now
 * copies the buffer, and the arrays and packs in it. WebAssembly has no copy
 * of a pack or an array yet and says so, rather than emitting a call to
 * nothing that the assembler refuses.
 */
class ArrayCloneExecTest {
    private val program = """
        import std.io
        pack Box {
            var v: Int
        }
        func main() {
            var original = [1, 2, 3]
            var copy = original.clone()
            copy[0] = 99
            println(original[0])
            println(copy[0])
            var rows = [[1, 2], [3]]
            var rowsCopy = rows.clone()
            rowsCopy[0][0] = 7
            println(rows[0][0])
            println(rowsCopy[0][0])
            var boxes = [Box(2)]
            var boxesCopy = boxes.clone()
            boxesCopy[0].v = 8
            println(boxes[0].v)
            println(boxesCopy[0].v)
        }
    """.trimIndent()

    @Test fun llvmCopiesTheSlotsAndWhatTheyOwn() {
        for (optimized in listOf(false, true)) {
            assertEquals("1\n99\n1\n7\n2\n8", LlvmExec.run(program, optimized).trim(), "optimized=$optimized")
        }
    }

    @Test fun webAssemblySaysItCannotCopyYet() {
        val error = assertFailsWith<Throwable> { WasmExec.run(program) }
        assertTrue("cannot copy" in error.toString(), error.toString())
    }
}
