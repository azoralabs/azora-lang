/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Fixed-arity expansion works on the interpreter; native support is explicit. */
class FixedSpreadBackendDiagnosticTest {
    private fun check(generate: (org.azora.lang.ir.IrProgram) -> String) {
        for (optimized in listOf(false, true)) {
            val result = Compiler().compile("""
                import std.io
                func sum(a: Int, b: Int, c: Int): Int { return a + b + c }
                func main() { println(sum(...[1, 2, 3])) }
            """.trimIndent(), release = optimized)
            assertIs<CompilationResult.Success>(result)
            val ir = if (optimized) result.optimizedIr else result.ir
            assertEquals("6", IrInterpreter().interpret(ir).trim())
            val error = assertFailsWith<IllegalStateException> { generate(ir) }
            assertTrue("fixed call parameters" in error.message.orEmpty(), error.message)
        }
    }

    @Test fun llvmRejectsUnsupportedFixedArityExpansion() = check { LlvmCodegen().generate(it) }
    @Test fun wasmRejectsUnsupportedFixedArityExpansion() = check { WasmCodegen().generate(it) }
}
