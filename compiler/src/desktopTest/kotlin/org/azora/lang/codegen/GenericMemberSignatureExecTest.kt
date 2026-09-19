package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class GenericMemberSignatureExecTest {
    @Test fun ownerTypesCrossTheLlvmCallBoundary() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for (optimized in listOf(false, true)) {
            assertEquals("", LlvmExec.runIr(LlvmCodegen().generate(GenericMemberSignatureTest.compile(optimized))))
        }
    }

    @Test fun inheritedMembersAndIndexingKeepTheirTypesOnLlvm() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for (optimized in listOf(false, true)) {
            val ir = GenericMemberSignatureTest.compileInherited(optimized)
            assertEquals(GenericMemberSignatureTest.inheritedOutput, LlvmExec.runIr(LlvmCodegen().generate(ir)), "optimized=$optimized")
        }
    }

    @Test fun inheritedMembersAndIndexingKeepTheirTypesOnWasm() {
        assumeTrue("Node.js and wat2wasm are required", WasmExec.available)
        for (optimized in listOf(false, true)) {
            val ir = GenericMemberSignatureTest.compileInherited(optimized)
            assertEquals(GenericMemberSignatureTest.inheritedOutput, WasmExec.runWat(WasmCodegen().generate(ir)), "optimized=$optimized")
        }
    }
}
