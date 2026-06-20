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
            func main() {
                var name = "Azora"
                println("hello ${'$'}name")
            }
        """.trimIndent()))
    }

    @Test
    fun braceExpressionInterpolation() {
        assertEquals("n+1 = 6", run("""
            func main() {
                var n = 5
                println("n+1 = ${'$'}{n + 1}")
            }
        """.trimIndent()))
    }

    @Test
    fun mixedInterpolation() {
        assertEquals("3 + 4 = 7", run("""
            func main() {
                var x = 3
                var y = 4
                println("${'$'}x + ${'$'}y = ${'$'}{x + y}")
            }
        """.trimIndent()))
    }

    @Test
    fun interpolationWithArithmetic() {
        assertEquals("count: 100", run("""
            func main() {
                println("count: ${'$'}{10 * 10}")
            }
        """.trimIndent()))
    }

    @Test
    fun interpolationWithStructField() {
        assertEquals("point x = 3", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(3, 4)
                println("point x = ${'$'}{p.x}")
            }
        """.trimIndent()))
    }

    @Test
    fun interpolationWithArrayIndex() {
        assertEquals("first = 10", run("""
            func main() {
                var a = [10, 20, 30]
                println("first = ${'$'}{a[0]}")
            }
        """.trimIndent()))
    }

    @Test
    fun plainStringWithoutDollarStillWorks() {
        assertEquals("just text", run("""
            func main() {
                println("just text")
            }
        """.trimIndent()))
    }

    @Test
    fun escapedDollarIsLiteral() {
        assertEquals("price is $5", run("""
            func main() {
                println("price is ${'$'}${'$'}5")
            }
        """.trimIndent()))
    }

    @Test
    fun interpolationSurvivesOptimization() {
        assertEquals("3 + 4 = 7", run("""
            func main() {
                var x = 3
                var y = 4
                println("${'$'}x + ${'$'}y = ${'$'}{x + y}")
            }
        """.trimIndent(), release = true))
    }

    @Test
    fun interpolationLoweredToBackends() {
        val result = Compiler().compile("""
            func main() {
                var name = "Azora"
                println("hello ${'$'}name")
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
        // Kotlin uses ${name} interpolation
        assertTrue("\${name}" in result.kotlin, "Kotlin should interpolate \${name}, got:\n${result.kotlin}")
        // TypeScript uses a template literal
        assertTrue("`hello" in result.typescript && "\${name}" in result.typescript,
            "TypeScript should emit a template literal, got:\n${result.typescript}")
    }
}
