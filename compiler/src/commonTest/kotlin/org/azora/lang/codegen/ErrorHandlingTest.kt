package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class ErrorHandlingTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun tryCatchCatchesThrow() {
        assertEquals("caught: boom", run("""
            func main() {
                try {
                    throw "boom"
                } catch { e ->
                    println("caught: " + e)
                }
            }
        """.trimIndent()))
    }

    @Test fun noThrowRunsBody() {
        assertEquals("ok", run("""
            func main() {
                try {
                    println("ok")
                } catch { e ->
                    println("error")
                }
            }
        """.trimIndent()))
    }

    @Test fun catchWithoutBinding() {
        assertEquals("recovered", run("""
            func main() {
                try {
                    throw "anything"
                } catch {
                    println("recovered")
                }
            }
        """.trimIndent()))
    }

    @Test fun catchExprFallback() {
        assertEquals("-1", run("""
            func safeDiv(a: Int, b: Int): Int {
                if b == 0 { throw "div0" }
                return a / b
            }
            func main() {
                println(safeDiv(10, 0) catch -1)
            }
        """.trimIndent()))
    }

    @Test fun catchExprSuccess() {
        assertEquals("5", run("""
            func safeDiv(a: Int, b: Int): Int {
                if b == 0 { throw "div0" }
                return a / b
            }
            func main() {
                println(safeDiv(10, 2) catch -1)
            }
        """.trimIndent()))
    }

    @Test fun throwEscapesUncaught() {
        val result = Compiler().compile("""
            func main() {
                throw "uncaught"
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
        try {
            IrInterpreter().interpret(result.ir)
            fail("Expected an uncaught throw to surface")
        } catch (e: Throwable) {
            assertTrue(e.toString().contains("uncaught"), e.toString())
        }
    }
}
