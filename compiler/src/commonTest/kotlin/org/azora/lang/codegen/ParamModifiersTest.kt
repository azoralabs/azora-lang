package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for parameter modifiers: `mut` (mutable param), `ref` (by-reference), `out` (output).
 */
class ParamModifiersTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun mutParamCanBeReassigned() {
        assertEquals("20", run("""
            func increment(mut n: Int): Int {
                n = n + 10
                return n
            }
            func main() {
                println(increment(10))
            }
        """.trimIndent()))
    }

    @Test fun refParamPropagatesBack() {
        assertEquals("10\n99", run("""
            func modify(ref x: Int) {
                x = 99
            }
            func main() {
                var v = 10
                println(v)
                modify(v)
                println(v)
            }
        """.trimIndent()))
    }

    @Test fun outParamSetsValue() {
        assertEquals("hello\n42", run("""
            func produce(out result: Int) {
                result = 42
            }
            func main() {
                var r = 0
                println("hello")
                produce(r)
                println(r)
            }
        """.trimIndent()))
    }

    @Test fun multipleRefParams() {
        // Swap two variables via ref params.
        assertEquals("70\n30", run("""
            func swap(ref a: Int, ref b: Int) {
                var tmp = a
                a = b
                b = tmp
            }
            func main() {
                var x = 30
                var y = 70
                swap(x, y)
                println(x)
                println(y)
            }
        """.trimIndent()))
    }
}
