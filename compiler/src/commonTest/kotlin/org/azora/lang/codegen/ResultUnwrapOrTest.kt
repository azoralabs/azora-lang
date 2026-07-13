package org.azora.lang.codegen

import org.azora.lang.Compiler
import org.azora.lang.CompilationResult
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ResultUnwrapOrTest {
    private fun run(source: String): String {
        val r = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(r, "compile failed: ${(r as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(r.ir).trim()
    }

    @Test fun unwrapOrReturnsOkValue() {
        assertEquals("42", run("""
            use std.result
            func main() {
                fin r = ok(42)
                println(unwrapOr(r, 0))
            }
        """.trimIndent()))
    }

    @Test fun unwrapOrReturnsDefaultOnErr() {
        assertEquals("0", run("""
            use std.result
            func main() {
                fin r = err(1, "boom")
                println(unwrapOr(r, 0))
            }
        """.trimIndent()))
    }
}
