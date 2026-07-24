package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for array literals, indexing, mutation, member access (`.length`),
 * builtin array methods (`add`, `isEmpty`, `isNotEmpty`), compound assignment
 * to indices, and member access on strings.
 */
class ArrayTest {

    private fun run(source: String, release: Boolean = false): String {
        val result = Compiler().compile(source, release = release)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test
    fun arrayLiteralAndLength() {
        assertEquals("3", run("""
            import std.io
            func main() {
                var a = arr@[10, 20, 30]
                std::println(a.length)
            }
        """.trimIndent()))
    }

    @Test
    fun arrayIndexRead() {
        assertEquals("20", run("""
            import std.io
            func main() {
                var a = arr@[10, 20, 30]
                std::println(a[1])
            }
        """.trimIndent()))
    }

    @Test
    fun arrayIndexWrite() {
        assertEquals("99", run("""
            import std.io
            func main() {
                var a = arr@[10, 20, 30]
                a[0] = 99
                std::println(a[0])
            }
        """.trimIndent()))
    }

    @Test
    fun arrayTypedAnnotation() {
        assertEquals("42", run("""
            import std.io
            func main() {
                var a: Array<Int> = arr@[7, 42, 13]
                std::println(a[1])
            }
        """.trimIndent()))
    }

    @Test
    fun arrayIterationWithFor() {
        // 10 + 20 + 30 = 60
        assertEquals("60", run("""
            import std.io
            func main() {
                var a = arr@[10, 20, 30]
                var sum = 0
                for i in 0..<a.length {
                    sum = sum + a[i]
                }
                std::println(sum)
            }
        """.trimIndent()))
    }

    @Test
    fun arrayAddGrows() {
        assertEquals("4", run("""
            import std.io
            func main() {
                var a = arr@[1, 2, 3]
                a.add(4)
                std::println(a.length)
            }
        """.trimIndent()))
    }

    @Test
    fun arrayAddThenRead() {
        assertEquals("40", run("""
            import std.io
            func main() {
                var a = arr@[10, 20, 30]
                a.add(40)
                std::println(a[3])
            }
        """.trimIndent()))
    }

    @Test
    fun arrayIsEmpty() {
        assertEquals("false", run("""
            import std.io
            func main() {
                var a = arr@[1]
                std::println(a.isEmpty)
            }
        """.trimIndent()))
    }

    @Test
    fun arrayIsNotEmpty() {
        assertEquals("true", run("""
            import std.io
            func main() {
                var a = arr@[1]
                std::println(a.isNotEmpty)
            }
        """.trimIndent()))
    }

    @Test
    fun compoundIndexAssignment() {
        // a[1] += 5  →  20 + 5 = 25
        assertEquals("25", run("""
            import std.io
            func main() {
                var a = arr@[10, 20, 30]
                a[1] += 5
                std::println(a[1])
            }
        """.trimIndent()))
    }

    @Test
    fun nestedArrays() {
        assertEquals("2", run("""
            import std.io
            func main() {
                var m = [arr@[1, 2], arr@[3, 4]]
                std::println(m[0][1])
            }
        """.trimIndent()))
    }

    @Test
    fun stringLength() {
        assertEquals("5", run("""
            import std.io
            func main() {
                std::println("hello".length)
            }
        """.trimIndent()))
    }

    @Test
    fun arrayReturnedFromFunction() {
        assertEquals("30", run("""
            import std.io
            func makeThree(): Array<Int> {
                return arr@[10, 20, 30]
            }
            func main() {
                var a = makeThree()
                std::println(a[2])
            }
        """.trimIndent()))
    }

    @Test
    fun arrayOperationsSurviveOptimization() {
        assertEquals("60", run("""
            import std.io
            func main() {
                var a = arr@[10, 20, 30]
                var sum = 0
                for i in 0..<a.length {
                    sum = sum + a[i]
                }
                std::println(sum)
            }
        """.trimIndent(), release = true))
    }

    @Test
    fun arrayLoweredToAllBackends() {
        val result = Compiler().compile("""
            import std.io
            func main() {
                var a = arr@[1, 2, 3]
                std::println(a[0])
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
        // JavaScript backend emits an array literal and index access
        assertTrue("[1, 2, 3]" in result.javascript, "JavaScript should emit an array literal, got:\n${result.javascript}")
        assertTrue("a[0]" in result.javascript, "JavaScript should emit a[0], got:\n${result.javascript}")
    }
}
