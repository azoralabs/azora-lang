package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class ListConstructionExecTest {
    @Test fun concreteListRunsOnLlvm() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for (optimized in listOf(false, true)) {
            assertEquals("", LlvmExec.runIr(LlvmCodegen().generate(ListConstructionTest.compile(optimized))))
        }
    }

    @Test fun concreteListRunsOnWasm() {
        assumeTrue("Node.js and wat2wasm are required", WasmExec.available)
        for (optimized in listOf(false, true)) {
            assertEquals("", WasmExec.runWat(WasmCodegen().generate(ListConstructionTest.compile(optimized))))
        }
    }
}
