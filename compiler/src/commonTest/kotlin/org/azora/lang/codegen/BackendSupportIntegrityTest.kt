package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackendSupportIntegrityTest {
    /**
     * The text transforms are Azora code in std since A4 (2026-11-07), so every
     * target builds them; they had been refused natively. What each target
     * prints is checked in `TextCodePointExecTest`.
     */
    @Test fun textTransformsBuildForEveryTarget() {
        val source = """
            import std.io
            import std.string
            func main() {
                println(strToUpper("Azora"))
                println(strTrim("  x  "))
                println(strReplace("abc", "b", "X"))
            }
        """.trimIndent()
        val r = assertIs<CompilationResult.Success>(Compiler().compile(source))
        assertEquals("AZORA\nx\naXc", IrInterpreter().interpret(r.ir).trim())
        assertTrue(r.backendErrors.isEmpty(), r.backendErrors.toString())
        assertTrue(r.llvm.contains("define"), "LLVM output")
        assertTrue(r.wasm.contains("(module"), "WebAssembly output")
    }

    @Test fun supportedScalarProgramHasExecutableOutputs() {
        val r = assertIs<CompilationResult.Success>(Compiler().compile("func main() {}"))
        assertTrue(r.backendErrors.isEmpty(), r.backendErrors.toString())
        assertTrue(r.llvm.contains("define"))
        assertTrue(r.wasm.contains("(module"))
    }
}
