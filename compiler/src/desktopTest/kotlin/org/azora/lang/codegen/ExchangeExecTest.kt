/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExchangeExecTest {
    @Test fun exchangeExecutesInLlvm() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for (optimized in listOf(false, true)) {
            assertEquals("", LlvmExec.runIr(LlvmCodegen().generate(ExchangeSemanticsTest.compilePublicProgram(optimized))))
            val ir = AssertionSemanticsTest.lower(ExchangeSemanticsTest.program, optimized)
            assertEquals("", LlvmExec.runIr(LlvmCodegen().generate(ir)))
            val bad = AssertionSemanticsTest.lower(ExchangeSemanticsTest.boundsProgram, optimized)
            val failure = assertFailsWith<AssertionError> { LlvmExec.runIr(LlvmCodegen().generate(bad)) }
            assertTrue("lli exited with code" in failure.message.orEmpty())
        }
    }

    @Test fun exchangeExecutesInWasm() {
        assumeTrue("Node.js and wat2wasm are required", WasmExec.available)
        for (optimized in listOf(false, true)) {
            assertEquals("", WasmExec.runWat(WasmCodegen().generate(ExchangeSemanticsTest.compilePublicProgram(optimized))))
            val ir = AssertionSemanticsTest.lower(ExchangeSemanticsTest.program, optimized)
            assertEquals("", WasmExec.runWat(WasmCodegen().generate(ir)))
            val bad = AssertionSemanticsTest.lower(ExchangeSemanticsTest.boundsProgram, optimized)
            val failure = assertFailsWith<AssertionError> { WasmExec.runWat(WasmCodegen().generate(bad)) }
            assertTrue("RuntimeError: unreachable" in failure.message.orEmpty())
        }
    }
}
