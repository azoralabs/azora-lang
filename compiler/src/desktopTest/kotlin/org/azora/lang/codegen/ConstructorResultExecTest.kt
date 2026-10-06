package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

class ConstructorResultExecTest {
    @Test fun returnedConstructorTreeRunsOnLlvm() {
        if (!LlvmExec.available) return
        for (release in listOf(false, true)) assertEquals("parent\nchild\n2", LlvmExec.run(ConstructorResultTest.returnedTree, release))
    }
    @Test fun contextualConstructorTreeRunsOnLlvm() {
        if (!LlvmExec.available) return
        for (release in listOf(false, true)) assertEquals("1\n3\nplain value\n42", LlvmExec.run(ConstructorResultTest.tree, release))
    }
    @Test fun contextualConstructorTreeRunsOnWasm() {
        if (!WasmExec.available) return
        for (release in listOf(false, true)) {
            val result = org.azora.lang.Compiler().compile(ConstructorResultTest.tree, release = release)
            kotlin.test.assertIs<org.azora.lang.CompilationResult.Success>(result)
            val ir = if (release) result.optimizedIr else result.ir
            assertEquals("1\n3\nplain value\n42", WasmExec.runWat(org.azora.lang.backend.WasmCodegen().generate(ir)))
        }
    }
}
