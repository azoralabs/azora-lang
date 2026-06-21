package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NamedArgsTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun namedArgsReordered() {
        assertEquals("3\n4", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(y: 4, x: 3)
                println(p.x)
                println(p.y)
            }
        """.trimIndent()))
    }

    @Test fun namedArgsInOrder() {
        assertEquals("1\n2", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            func main() {
                var p = Point(x: 1, y: 2)
                println(p.x)
                println(p.y)
            }
        """.trimIndent()))
    }
}
