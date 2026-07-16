package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TupleTest {
    @Test fun tupleOfAndTupleTypeRemainSupported() {
        val result = Compiler().compile("""
            import std.io
            import std.container.tuple
            import std

            func swap(value: Tuple<Int, String>): Tuple<String, Int> {
                return std::tupleOf(value.1, value.0)
            }

            func main() {
                fin result = swap(std::tupleOf(7, "ready"))
                std::io::println(result.0)
                std::io::println(result.1)
            }
        """.trimIndent(), release = false)

        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        assertEquals("ready\n7", IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun tupleLiteralSyntaxIsRejectedWithMigration() {
        val result = Compiler().compile("""
            func main() {
                fin pair = (1, "hello")
            }
        """.trimIndent(), release = false)

        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "tupleOf(a, b)" in it }, result.errors.toString())
    }

    @Test fun tupleTypeSyntaxIsRejectedWithMigration() {
        val result = Compiler().compile("""
            func pair(): (Int, String) {
                return tupleOf(1, "hello")
            }
        """.trimIndent(), release = false)

        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "Tuple<A, B>" in it }, result.errors.toString())
    }

    @Test fun groupingAndFunctionTypesAreNotTupleSyntax() {
        val result = Compiler().compile("""
            import std.io

            func apply(value: Int, transform: (Int) -> Int): Int {
                return transform((value))
            }

            func main() {
                std::io::println((20) + 22)
            }
        """.trimIndent(), release = false)

        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        assertEquals("42", IrInterpreter().interpret(result.ir).trim())
    }
}
