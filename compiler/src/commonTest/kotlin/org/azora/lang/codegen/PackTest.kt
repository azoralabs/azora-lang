package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for `pack` (struct) declarations: construction, field access,
 * field mutation, immutability enforcement, and interplay with arrays/functions.
 */
class PackTest {

    private fun run(source: String, release: Boolean = false): String {
        val result = Compiler().compile(source, release = release)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun expectFailure(source: String): List<String> {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Failure>(result, "Expected compilation failure but got success")
        return result.errors
    }

    @Test
    fun structConstructionAndFieldAccess() {
        assertEquals("3", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(3, 4)
                println(p.x)
            }
        """.trimIndent()))
    }

    @Test
    fun structFieldSum() {
        assertEquals("7", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(3, 4)
                println(p.x + p.y)
            }
        """.trimIndent()))
    }

    @Test
    fun structFieldMutation() {
        assertEquals("10", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(3, 4)
                p.x = 10
                println(p.x)
            }
        """.trimIndent()))
    }

    @Test
    fun structWithImmutableField() {
        // fin field cannot be reassigned
        val errors = expectFailure("""
            pack Point {
                fin x: Int
                var y: Int
            }
            func main() {
                var p = Point(3, 4)
                p.x = 10
            }
        """.trimIndent())
        assertTrue(errors.any { it.contains("immutable") }, "Expected immutability error, got: $errors")
    }

    @Test
    fun structReturnedFromFunction() {
        assertEquals("9", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            func origin(): Point {
                return Point(4, 5)
            }
            func main() {
                var p = origin()
                println(p.x + p.y)
            }
        """.trimIndent()))
    }

    @Test
    fun structFieldUpdatedThroughCompoundAssign() {
        // p.y += 100  →  4 + 100 = 104
        assertEquals("104", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(3, 4)
                p.y += 100
                println(p.y)
            }
        """.trimIndent()))
    }

    @Test
    fun structStoredInArray() {
        // 10 + 30 = 40
        assertEquals("40", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var points = [Point(10, 20), Point(30, 40)]
                println(points[0].x + points[1].x)
            }
        """.trimIndent()))
    }

    @Test
    fun structOperationsSurviveOptimization() {
        assertEquals("7", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(3, 4)
                println(p.x + p.y)
            }
        """.trimIndent(), release = true))
    }

    @Test
    fun structLoweredToAllBackends() {
        val result = Compiler().compile("""
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(1, 2)
                println(p.x)
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
        // Kotlin backend emits a class with mutable fields
        assertTrue("class Point(var x: Int, var y: Int)" in result.kotlin, "Kotlin should emit a Point class, got:\n${result.kotlin}")
        assertTrue("Point(1, 2)" in result.kotlin, "Kotlin should construct Point(1, 2), got:\n${result.kotlin}")
        // TypeScript backend emits a class with a constructor
        assertTrue("class Point {" in result.typescript, "TypeScript should emit a Point class, got:\n${result.typescript}")
        assertTrue("new Point(1, 2)" in result.typescript, "TypeScript should construct new Point, got:\n${result.typescript}")
    }
}
