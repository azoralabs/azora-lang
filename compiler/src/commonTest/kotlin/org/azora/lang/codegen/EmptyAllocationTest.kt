/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `alloc .()` into a pointer to a primitive is the empty run of slots - a
 * placeholder a pack field can start with until it is given a real buffer, as
 * `std.allocator`'s `Pool` does.
 *
 * `.(a, b)` there is the run of values the pointer points at, and with no
 * values the run had no element type: "cannot infer element type of empty
 * sequence literal", although the pointer's type says what it holds.
 */
class EmptyAllocationTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun anEmptyRunTakesThePointersElementType() = assertEquals("7\n9", run(
        """
        import std.io
        pack Holder {
            var slots: Int^ = alloc^ .()
        }
        func main() {
            var h = Holder()
            h.slots = alloc^ .() * 3
            h.slots.^[2] = 7
            println(h.slots.^[2])
            var empty: Double* = alloc .()
            var n = 4
            var q: Int^ = alloc^ .() * n
            q.^[3] = 9
            println(q.^[3])
        }
        """,
    ))

    // The free stack is filled in reverse, so the lowest slot is handed out first.
    @Test fun anAllocatorPrintsItsSummary() = assertEquals("Pool(slots: 2, slotSize: 8, inUse: 1, free: 1, peak: 1)\n0", run(
        """
        import std.io
        import std.allocator
        func main() {
            var pool = Pool(8, 2)
            fin slot = pool.acquire() catch -1
            println(pool.diagnostic)
            println(slot)
        }
        """,
    ))
}
