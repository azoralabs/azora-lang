package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NullableTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun nullCoalesceLeft() {
        assertEquals("5", run("""
            import std.io
            func main() {
                var x: Int? = null
                std::println(x ?? 5)
            }
        """.trimIndent()))
    }

    @Test fun nullCoalesceRight() {
        assertEquals("10", run("""
            import std.io
            func main() {
                var x: Int? = 10
                std::println(x ?? 5)
            }
        """.trimIndent()))
    }

    @Test fun nullCoalesceChained() {
        assertEquals("default", run("""
            import std.io
            func main() {
                var a: String? = null
                var b: String? = null
                std::println(a ?? b ?? "default")
            }
        """.trimIndent()))
    }

    @Test fun safeMemberAccess() {
        assertEquals("0", run("""
            import std.io
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p: Point? = null
                std::println(p?.x ?? 0)
            }
        """.trimIndent()))
    }

    @Test fun safeMemberNonNull() {
        assertEquals("3", run("""
            import std.io
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p: Point? = Point(3, 4)
                std::println(p?.x ?? 0)
            }
        """.trimIndent()))
    }

    @Test fun nullableInParameter() {
        assertEquals("42", run("""
            import std.io
            func defaultIfNull(x: Int?, fallback: Int): Int {
                return x ?? fallback
            }
            func main() {
                std::println(defaultIfNull(null, 42))
            }
        """.trimIndent()))
    }
}
