package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for string interpolation: `"hello $name"` and `"value: ${expr}"`.
 */
class StringInterpolationTest {

    private fun run(source: String, release: Boolean = false): String {
        val result = Compiler().compile(source, release = release)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test
    fun dollarIdentifierInterpolation() {
        assertEquals("hello Azora", run("""
            import std.io
            func main() {
                var name = "Azora"
                std::println("hello ${'$'}name")
            }
        """.trimIndent()))
    }

    @Test
    fun braceExpressionInterpolation() {
        assertEquals("n+1 = 6", run("""
            import std.io
            func main() {
                var n = 5
                std::println("n+1 = ${'$'}{n + 1}")
            }
        """.trimIndent()))
    }

    @Test
    fun mixedInterpolation() {
        assertEquals("3 + 4 = 7", run("""
            import std.io
            func main() {
                var x = 3
                var y = 4
                std::println("${'$'}x + ${'$'}y = ${'$'}{x + y}")
            }
        """.trimIndent()))
    }

    @Test
    fun interpolationWithArithmetic() {
        assertEquals("count: 100", run("""
            import std.io
            func main() {
                std::println("count: ${'$'}{10 * 10}")
            }
        """.trimIndent()))
    }

    @Test
    fun interpolationWithStructField() {
        assertEquals("point x = 3", run("""
            import std.io
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(3, 4)
                std::println("point x = ${'$'}{p.x}")
            }
        """.trimIndent()))
    }

    @Test
    fun interpolationWithArrayIndex() {
        assertEquals("first = 10", run("""
            import std.io
            func main() {
                var a = [10, 20, 30]
                std::println("first = ${'$'}{a[0]}")
            }
        """.trimIndent()))
    }

    @Test
    fun plainStringWithoutDollarStillWorks() {
        assertEquals("just text", run("""
            import std.io
            func main() {
                std::println("just text")
            }
        """.trimIndent()))
    }

    @Test
    fun escapedDollarIsLiteral() {
        assertEquals("price is $5", run("""
            import std.io
            func main() {
                std::println("price is ${'$'}${'$'}5")
            }
        """.trimIndent()))
    }

    @Test
    fun interpolationSurvivesOptimization() {
        assertEquals("3 + 4 = 7", run("""
            import std.io
            func main() {
                var x = 3
                var y = 4
                std::println("${'$'}x + ${'$'}y = ${'$'}{x + y}")
            }
        """.trimIndent(), release = true))
    }

    @Test
    fun interpolationLoweredToBackends() {
        val result = Compiler().compile("""
            import std.io
            func main() {
                var name = "Azora"
                std::println("hello ${'$'}name")
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
        // JavaScript uses a template literal
        assertTrue("`hello" in result.javascript && "\${name}" in result.javascript,
            "JavaScript should emit a template literal, got:\n${result.javascript}")
    }
}
