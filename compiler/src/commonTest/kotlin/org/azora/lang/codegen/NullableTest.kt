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
            func main() {
                var x: Int? = null
                println(x ?? 5)
            }
        """.trimIndent()))
    }

    @Test fun nullCoalesceRight() {
        assertEquals("10", run("""
            func main() {
                var x: Int? = 10
                println(x ?? 5)
            }
        """.trimIndent()))
    }

    @Test fun nullCoalesceChained() {
        assertEquals("default", run("""
            func main() {
                var a: String? = null
                var b: String? = null
                println(a ?? b ?? "default")
            }
        """.trimIndent()))
    }

    @Test fun safeMemberAccess() {
        assertEquals("0", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p: Point? = null
                println(p?.x ?? 0)
            }
        """.trimIndent()))
    }

    @Test fun safeMemberNonNull() {
        assertEquals("3", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p: Point? = Point(3, 4)
                println(p?.x ?? 0)
            }
        """.trimIndent()))
    }

    @Test fun nullableInParameter() {
        assertEquals("42", run("""
            func defaultIfNull(x: Int?, fallback: Int): Int {
                return x ?? fallback
            }
            func main() {
                println(defaultIfNull(null, 42))
            }
        """.trimIndent()))
    }
}
