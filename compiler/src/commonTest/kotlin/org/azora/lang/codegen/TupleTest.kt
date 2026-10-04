package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TupleTest {
    @Test fun tupleLiteralsAndTupleTypesAreOneType() {
        // `(A, B)` and `Tuple<A, B>` are identical (GTC §6.3), so either spelling
        // is accepted where the other is written.
        val result = Compiler().compile("""
            import std.io

            func swap(value: Tuple<Int, String>): (String, Int) {
                return (value.1, value.0)
            }

            func main() {
                fin result: Tuple<String, Int> = swap((7, "ready"))
                println(result.0)
                println(result.1)
            }
        """.trimIndent(), release = false)

        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        assertEquals("ready\n7", IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun aOneElementTupleLiteralIsRejected() {
        // GTC §6.4: `(x)` groups, `(x,)` is no tuple.
        val errors = runCatching { Compiler().compile("func main() {\n    fin pair = (1,)\n}", release = false) }
            .fold({ (it as? CompilationResult.Failure)?.errors.orEmpty() }, { listOf(it.message.orEmpty()) })
        assertTrue(errors.any { "a tuple has at least two elements" in it }, errors.toString())
    }

    @Test fun aTupleTypeIsWrittenNatively() {
        val result = Compiler().compile("""
            import std.io
            func pair(): (Int, String) {
                return (1, "hello")
            }
            func main() {
                println(pair().1)
            }
        """.trimIndent(), release = false)

        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        assertEquals("hello", IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun groupingAndFunctionTypesAreNotTupleSyntax() {
        val result = Compiler().compile("""
            import std.io

            func apply(value: Int, transform: (Int) -> Int): Int {
                return transform((value))
            }

            func main() {
                println((20) + 22)
            }
        """.trimIndent(), release = false)

        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        assertEquals("42", IrInterpreter().interpret(result.ir).trim())
    }
}
