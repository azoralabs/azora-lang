/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The WebAssembly heap allocator exercised directly, with the runtime text the
 * backend emits. These are the properties a program cannot observe: which
 * block an allocation reuses, what it contains, and which misuse traps.
 */
class WasmAllocatorExecTest {
    @Before fun requireWasm() = assumeTrue("Node.js and wat2wasm are required", WasmExec.available)

    /**
     * A module running [body] as `main`, with one page of memory unless
     * [memory] says otherwise. `%` stands for WAT's `$`, which Kotlin would
     * otherwise read as a template.
     */
    private fun module(body: String, memory: String = "1") = """
        (module
          (import "env" "print_i32" (func %print_i32 (param i32)))
          (memory (export "memory") $memory)
        ${WasmCodegen.heapGlobals(1024)}
        ${WasmCodegen.ALLOCATOR_RUNTIME}
          (func (export "main") (local %a i32) (local %b i32) (local %c i32) (local %i i32)
        $body)
        )
    """.trimIndent().replace('%', '$')

    private fun run(body: String, memory: String = "1") = WasmExec.runWat(module(body, memory))
    private fun trap(body: String, memory: String = "1") = WasmExec.runWatExpectingTrap(module(body, memory))

    @Test fun aFreedBlockIsReusedOnlyWithinItsSizeClass() {
        assertEquals("1\n1\n1", run("""
            (local.set %a (call %__alloc (i32.const 24)))
            (call %__free (local.get %a))
            (local.set %b (call %__alloc (i32.const 100)))
            (call %print_i32 (i32.ne (local.get %b) (local.get %a)))
            (local.set %b (call %__alloc (i32.const 20)))
            (call %print_i32 (i32.eq (local.get %b) (local.get %a)))
            (local.set %c (call %__alloc (i32.const 20)))
            (call %print_i32 (i32.ne (local.get %c) (local.get %a)))
        """))
    }

    @Test fun freedBlocksAreReusedMostRecentFirst() {
        assertEquals("1\n1", run("""
            (local.set %a (call %__alloc (i32.const 8)))
            (local.set %b (call %__alloc (i32.const 8)))
            (call %__free (local.get %a))
            (call %__free (local.get %b))
            (call %print_i32 (i32.eq (call %__alloc (i32.const 8)) (local.get %b)))
            (call %print_i32 (i32.eq (call %__alloc (i32.const 8)) (local.get %a)))
        """))
    }

    // Sizes 0 through 64 in turn: each block is 8-aligned and starts after the
    // previous payload, including the zero-size ones.
    @Test fun liveBlocksAreAlignedAndDisjoint() {
        assertEquals("0", run("""
            (local.set %b (i32.const 0))
            (block %done (loop %next
              (local.set %a (call %__alloc (local.get %i)))
              (if (i32.and (local.get %a) (i32.const 7)) (then (call %print_i32 (i32.const -1))))
              (if (i32.le_u (local.get %a) (local.get %b)) (then (call %print_i32 (i32.const -2))))
              (local.set %b (i32.add (local.get %a) (local.get %i)))
              (local.set %i (i32.add (local.get %i) (i32.const 1)))
              (br_if %next (i32.le_u (local.get %i) (i32.const 64)))))
            (call %print_i32 (i32.const 0))
        """))
    }

    @Test fun aReusedBlockIsZeroed() {
        assertEquals("1\n0\n0", run("""
            (local.set %a (call %__alloc (i32.const 16)))
            (i64.store (local.get %a) (i64.const -1))
            (i64.store offset=8 (local.get %a) (i64.const -1))
            (call %__free (local.get %a))
            (local.set %b (call %__alloc_buffer (i32.const 4) (i32.const 4)))
            (call %print_i32 (i32.eq (local.get %b) (local.get %a)))
            (call %print_i32 (i32.wrap_i64 (i64.load (local.get %b))))
            (call %print_i32 (i32.wrap_i64 (i64.load offset=8 (local.get %b))))
        """))
    }

    @Test fun allocationGrowsMemory() {
        assertEquals("1\n1\n77", run("""
            (call %print_i32 (i32.eq (memory.size) (i32.const 1)))
            (local.set %a (call %__alloc (i32.const 200000)))
            (call %print_i32 (i32.ge_u (memory.size) (i32.const 4)))
            (i32.store8 (i32.add (local.get %a) (i32.const 199999)) (i32.const 77))
            (call %print_i32 (i32.load8_u (i32.add (local.get %a) (i32.const 199999))))
        """))
    }

    @Test fun freeingNullDoesNothing() {
        assertEquals("1", run("""
            (call %__free (i32.const 0))
            (call %print_i32 (i32.const 1))
        """))
    }

    @Test fun aSecondPurgeTraps() {
        assertEquals("1", trap("""
            (local.set %a (call %__alloc (i32.const 8)))
            (call %__free (local.get %a))
            (call %print_i32 (i32.const 1))
            (call %__free (local.get %a))
        """))
    }

    @Test fun anInteriorPointerTraps() {
        trap("""
            (local.set %a (call %__alloc (i32.const 32)))
            (call %__free (i32.add (local.get %a) (i32.const 8)))
        """)
        trap("""
            (local.set %a (call %__alloc (i32.const 32)))
            (call %__free (i32.add (local.get %a) (i32.const 4)))
        """)
    }

    @Test fun memoryTheAllocatorNeverHandedOutTraps() {
        trap("(call %__free (i32.const 16))")
        trap("(call %__free (i32.const 1032))")
        trap("""
            (local.set %a (call %__alloc (i32.const 8)))
            (call %__free (i32.add (local.get %a) (i32.const 64)))
        """)
    }

    @Test fun anOversizedOrNegativeRequestTraps() {
        trap("(drop (call %__alloc (i32.const ${WasmCodegen.WASM_MAX_ALLOC + 1})))")
        trap("(drop (call %__alloc (i32.const -1)))")
        trap("(drop (call %__alloc_buffer (i32.const ${WasmCodegen.WASM_MAX_ALLOC / 4 + 1}) (i32.const 4)))")
        trap("(drop (call %__alloc_buffer (i32.const -1) (i32.const 8)))")
    }

    @Test fun exhaustingMemoryTraps() {
        assertEquals("1", trap("""
            (drop (call %__alloc (i32.const 60000)))
            (call %print_i32 (i32.const 1))
            (drop (call %__alloc (i32.const 60000)))
        """, memory = "1 2"))
    }
}
