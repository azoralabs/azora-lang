package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
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
}
