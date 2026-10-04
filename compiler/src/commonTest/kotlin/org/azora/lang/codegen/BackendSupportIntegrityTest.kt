package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackendSupportIntegrityTest {
    @Test fun unsupportedTextTransformsHaveDiagnosticsInsteadOfWrongExecutables() {
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
        for (target in listOf("llvm", "wasm")) {
            assertTrue(r.backendErrors[target]?.contains("not implemented") == true, r.backendErrors.toString())
        }
        assertEquals("", r.llvm)
        assertEquals("", r.wasm)
    }

    @Test fun supportedScalarProgramHasExecutableOutputs() {
        val r = assertIs<CompilationResult.Success>(Compiler().compile("func main() {}"))
        assertTrue(r.backendErrors.isEmpty(), r.backendErrors.toString())
        assertTrue(r.llvm.contains("define"))
        assertTrue(r.wasm.contains("(module"))
    }
}
