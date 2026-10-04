/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.azora.lang.ir.IrProgram
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Raw heap memory - `alloc`, dereference, buffers and `purge` - through the
 * public compiler on every backend. Allocator properties no program can observe
 * (block reuse, zeroing, traps) are covered by [WasmAllocatorExecTest].
 */
class RawPointerExecTest {
    private val singleValues = """
        import std.io
        unsafe func main() {
            var count: Int^ = alloc^ 5
            println(count.^)
            count.^ = 12
            println(count.^)
            purge count
            fin half: Double = 2.5
            var ratio: Double^ = alloc^ half
            println(ratio.^)
            purge ratio
        }
    """.trimIndent()

    private val buffers = """
        import std.io
        unsafe func main() {
            var values: Int^ = alloc Int^() * 4
            for i in 0..<4 { values[i] = i * 10 }
            println(values[1] + values[3])
            purge values
            fin big: Long = 5000000000
            var wide: Long^ = alloc Long^() * 3
            wide[2] = big
            wide[0] = 1
            println(wide[0] + wide[2])
            purge wide
        }
    """.trimIndent()

    // An allocated array owns a buffer of its own, so purging it releases
    // exactly what `alloc` produced.
    private val allocatedArray = """
        import std.io
        unsafe func main() {
            fin source = [3, 4, 5]
            var copy: Int^ = alloc^ source
            copy[0] = 30
            println(copy[0] + copy[2])
            purge copy
        }
    """.trimIndent()

    // 300,000 Ints exceed the 1 MiB the module starts with.
    private val growth = """
        import std.io
        unsafe func main() {
            fin count = 300000
            var big: Int^ = alloc Int^() * count
            big[0] = 1
            big[count - 1] = 7
            println(big[0] + big[count - 1])
            purge big
            var again: Int^ = alloc Int^() * count
            again[count - 1] = 9
            println(again[count - 1])
            purge again
        }
    """.trimIndent()

    // Memory a purge released comes back zeroed, not holding the old values.
    private val reuse = """
        import std.io
        unsafe func main() {
            var first: Long^ = alloc Long^() * 64
            for i in 0..<64 { first[i] = 7 }
            purge first
            var second: Long^ = alloc Long^() * 64
            var sum: Long = 0
            for i in 0..<64 { sum = sum + second[i] }
            println(sum)
            purge second
        }
    """.trimIndent()

    private val purgedTwice = """
        unsafe func main() {
            var values: Int^ = alloc Int^() * 4
            purge values
            purge values
        }
    """.trimIndent()

    private fun compile(source: String, optimized: Boolean): IrProgram {
        val result = Compiler().compile(source, release = optimized)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return result.ir
    }

    private fun assertRunsEverywhere(source: String, expected: String) {
        for (optimized in listOf(false, true)) {
            val ir = compile(source, optimized)
            assertEquals(expected, IrInterpreter().interpret(ir).trim(), "interpreter, optimized=$optimized")
            if (LlvmExec.available) {
                assertEquals(expected, LlvmExec.runIr(LlvmCodegen().generate(ir)), "LLVM, optimized=$optimized")
            }
            if (WasmExec.available) {
                assertEquals(expected, WasmExec.runWat(WasmCodegen().generate(ir)), "WASM, optimized=$optimized")
            }
        }
    }

    @Test fun aSingleValueIsStoredReadWrittenAndPurged() = assertRunsEverywhere(singleValues, "5\n12\n2.5")

    @Test fun buffersUseTheElementWidth() = assertRunsEverywhere(buffers, "40\n5000000001")

    @Test fun anAllocatedArrayCanBePurged() = assertRunsEverywhere(allocatedArray, "35")

    // The interpreter still fills a new buffer with null rather than each
    // element's zero; that difference is recorded rather than asserted here.
    // Some system allocators (macOS) zero memory on free, so there the LLVM run
    // cannot tell a zeroing allocation from a plain one; the generated call
    // says which one it is.
    @Test fun aBufferAfterAPurgeStartsZeroed() {
        for (optimized in listOf(false, true)) {
            val ir = compile(reuse, optimized)
            val llvm = LlvmCodegen().generate(ir)
            assertTrue("call i8* @__azora_alloc_zeroed(" in llvm, "buffers are allocated zeroed")
            if (LlvmExec.available) assertEquals("0", LlvmExec.runIr(llvm), "LLVM, optimized=$optimized")
            if (WasmExec.available) assertEquals("0", WasmExec.runWat(WasmCodegen().generate(ir)), "WASM, optimized=$optimized")
        }
    }

    // Ownership checking catches destruction twice before either backend can
    // emit a double free. Runtime allocator checks remain defense in depth.
    @Test fun aSecondPurgeIsRejectedBeforeCodeGeneration() {
        for (optimized in listOf(false, true)) {
            val r = assertIs<CompilationResult.Failure>(Compiler().compile(purgedTwice, release = optimized))
            assertTrue(r.errors.any { "use of taken value 'values'" in it }, r.errors.toString())
        }
    }

    @Test fun wasmMemoryGrowsForALargeBuffer() {
        assumeTrue("Node.js and wat2wasm are required", WasmExec.available)
        for (optimized in listOf(false, true)) {
            assertEquals("8\n9", WasmExec.runWat(WasmCodegen().generate(compile(growth, optimized))))
        }
    }

    // Scalars have no owned heap allocation to release.
    @Test fun nativeTargetsRejectPurgingAScalar() {
        val ir = compile(
            """
                func main() {
                    fin number = 1
                    purge number
                }
            """.trimIndent(),
            optimized = false,
        )
        val wasm = assertFailsWith<IllegalStateException> { WasmCodegen().generate(ir) }
        assertTrue("purge of Int is not supported by the WebAssembly target" in wasm.message.orEmpty(), wasm.message)
        val llvm = assertFailsWith<IllegalStateException> { LlvmCodegen().generate(ir) }
        assertTrue("purge of Int is not supported by the LLVM target" in llvm.message.orEmpty(), llvm.message)
    }
}
