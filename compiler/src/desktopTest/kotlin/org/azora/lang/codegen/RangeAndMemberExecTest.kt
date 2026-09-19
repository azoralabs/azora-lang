/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RangeAndMemberExecTest {
    @Test fun invalidStepsFailInNativeAndWasmExecution() {
        assumeTrue("LLVM, Node.js and wat2wasm are required", LlvmExec.available && WasmExec.available)
        for (step in listOf(0, -1)) {
            for (optimized in listOf(false, true)) {
                val ir = AssertionSemanticsTest.lower(RangeBoundaryTest.invalidStepProgram(step), optimized)
                val llvm = assertFailsWith<AssertionError> { LlvmExec.runIr(LlvmCodegen().generate(ir)) }
                assertTrue("lli exited with code" in llvm.message.orEmpty())
                val wasm = assertFailsWith<AssertionError> { WasmExec.runWat(WasmCodegen().generate(ir)) }
                assertTrue("RuntimeError: unreachable" in wasm.message.orEmpty())
            }
        }
    }

    @Test fun rangeBoundariesAndThenMembersExecuteInLlvm() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for (source in listOf(RangeBoundaryTest.program, RangeBoundaryTest.headerProgram, ThenMemberTest.program)) {
            for (optimized in listOf(false, true)) {
                val ir = AssertionSemanticsTest.lower(source, optimized)
                assertEquals("", LlvmExec.runIr(LlvmCodegen().generate(ir)))
            }
        }
    }

    @Test fun rangeBoundariesAndThenMembersExecuteInWasm() {
        assumeTrue("Node.js and wat2wasm are required", WasmExec.available)
        for (source in listOf(RangeBoundaryTest.program, RangeBoundaryTest.headerProgram, ThenMemberTest.program)) {
            for (optimized in listOf(false, true)) {
                val ir = AssertionSemanticsTest.lower(source, optimized)
                assertEquals("", WasmExec.runWat(WasmCodegen().generate(ir)))
            }
        }
    }
}
