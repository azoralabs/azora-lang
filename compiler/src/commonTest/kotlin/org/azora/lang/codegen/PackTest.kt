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
            import std.io
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(3, 4)
                std::io::println(p.x)
            }
        """.trimIndent()))
    }

    @Test
    fun structFieldSum() {
        assertEquals("7", run("""
            import std.io
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(3, 4)
                std::io::println(p.x + p.y)
            }
        """.trimIndent()))
    }

    @Test
    fun structFieldMutation() {
        assertEquals("10", run("""
            import std.io
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(3, 4)
                p.x = 10
                std::io::println(p.x)
            }
        """.trimIndent()))
    }

    @Test
    fun structWithImmutableField() {
        // fin field cannot be reassigned
        val errors = expectFailure("""
            import std.io
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
            import std.io
            pack Point {
                var x: Int
                var y: Int
            }
            func origin(): Point {
                return Point(4, 5)
            }
            func main() {
                var p = origin()
                std::io::println(p.x + p.y)
            }
        """.trimIndent()))
    }

    @Test
    fun structFieldUpdatedThroughCompoundAssign() {
        // p.y += 100  →  4 + 100 = 104
        assertEquals("104", run("""
            import std.io
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(3, 4)
                p.y += 100
                std::io::println(p.y)
            }
        """.trimIndent()))
    }

    @Test
    fun structStoredInArray() {
        // 10 + 30 = 40
        assertEquals("40", run("""
            import std.io
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var points = [Point(10, 20), Point(30, 40)]
                std::io::println(points[0].x + points[1].x)
            }
        """.trimIndent()))
    }

    @Test
    fun structOperationsSurviveOptimization() {
        assertEquals("7", run("""
            import std.io
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(3, 4)
                std::io::println(p.x + p.y)
            }
        """.trimIndent(), release = true))
    }

    @Test
    fun structLoweredToAllBackends() {
        val result = Compiler().compile("""
            import std.io
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(1, 2)
                std::io::println(p.x)
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
        // JavaScript backend emits a class with a constructor
        assertTrue("class Point {" in result.javascript, "JavaScript should emit a Point class, got:\n${result.javascript}")
        assertTrue("new Point(1, 2)" in result.javascript, "JavaScript should construct new Point, got:\n${result.javascript}")
    }
}
