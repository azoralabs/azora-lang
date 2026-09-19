/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class IncrementExecTest {
    @Test fun incrementValuesAndMutationsAgreeInLlvm() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for (source in listOf(IncrementSemanticsTest.program, IncrementSemanticsTest.globalProgram)) {
            for (optimized in listOf(false, true)) {
                val ir = AssertionSemanticsTest.lower(source, optimized)
                assertEquals("", LlvmExec.runIr(LlvmCodegen().generate(ir)))
            }
        }
    }

    @Test fun incrementValuesAndMutationsAgreeInWasm() {
        assumeTrue("Node.js and wat2wasm are required", WasmExec.available)
        for (source in listOf(IncrementSemanticsTest.program, IncrementSemanticsTest.globalProgram)) {
            for (optimized in listOf(false, true)) {
                val ir = AssertionSemanticsTest.lower(source, optimized)
                assertEquals("", WasmExec.runWat(WasmCodegen().generate(ir)))
            }
        }
    }
}
