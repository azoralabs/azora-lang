package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TupleTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun tupleLiteralAndAccess() {
        assertEquals("1\nhello", run("""
            func main() {
                fin p = (1, "hello")
                println(p.0)
                println(p.1)
            }
        """.trimIndent()))
    }

    @Test fun tupleFromFunction() {
        assertEquals("3\n2", run("""
            func divmod(a: Int, b: Int): (Int, Int) {
                return (a / b, a % b)
            }
            func main() {
                fin r = divmod(17, 5)
                println(r.0)
                println(r.1)
            }
        """.trimIndent()))
    }

    @Test fun nestedTuple() {
        assertEquals("2", run("""
            func main() {
                fin t = (1, (2, 3), "end")
                fin inner = t.1
                println(inner.0)
            }
        """.trimIndent()))
    }

    @Test fun tupleInInterpolation() {
        assertEquals("1 + hello", run("""
            func main() {
                fin p = (1, "hello")
                println("${'$'}{p.0} + ${'$'}{p.1}")
            }
        """.trimIndent()))
    }
}
