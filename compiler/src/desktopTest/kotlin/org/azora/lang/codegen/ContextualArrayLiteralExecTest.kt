package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextualArrayLiteralExecTest {
    @Test fun arraysRunOnLlvm() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for (optimized in listOf(false, true)) {
            assertEquals("", LlvmExec.runIr(LlvmCodegen().generate(ContextualArrayLiteralTest.compile(optimized = optimized))))
        }
    }

    @Test fun arraysRunOnWasm() {
        assumeTrue("Node.js and wat2wasm are required", WasmExec.available)
        for (optimized in listOf(false, true)) {
            assertEquals("", WasmExec.runWat(WasmCodegen().generate(ContextualArrayLiteralTest.compile(optimized = optimized))))
        }
    }
}
