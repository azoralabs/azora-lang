package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for parameter modifiers: `mut` (mutable param), `ref` (by-reference), `out` (output).
 */
class ParamModifiersTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun mutParamCanBeReassigned() {
        assertEquals("20", run("""
            use std.io
            func increment(n: Int!): Int {
                n = n + 10
                return n
            }
            func main() {
                std::println(increment(10))
            }
        """.trimIndent()))
    }

    @Test fun refParamPropagatesBack() {
        assertEquals("10\n99", run("""
            use std.io
            func modify(x: Int!) {
                x = 99
            }
            func main() {
                var v = 10
                std::println(v)
                modify(v)
                std::println(v)
            }
        """.trimIndent()))
    }

    @Test fun aWriteOnlyOutputParameterIsRejected() {
        // There is no `out`: a function that produces a value returns it, and a
        // function that updates its caller's variable takes a `!` borrow. A
        // parameter you may only write to is neither.
        val result = Compiler().compile("""
            use std.io
            func produce(out result: Int) {
                result = 42
            }
            func main() {
                var r = 0
                produce(r)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result, "`out` must not be a parameter modifier")
    }

    @Test fun aMutableBorrowReplacesTheOutParameter() {
        assertEquals("hello\n42", run("""
            use std.io
            func produce(result: Int!) {
                result = 42
            }
            func main() {
                var r = 0
                std::println("hello")
                produce(r)
                std::println(r)
            }
        """.trimIndent()))
    }

    @Test fun multipleRefParams() {
        // Swap two variables via ref params.
        assertEquals("70\n30", run("""
            use std.io
            func swap(a: Int!, b: Int!) {
                var tmp = a
                a = b
                b = tmp
            }
            func main() {
                var x = 30
                var y = 70
                swap(x, y)
                std::println(x)
                std::println(y)
            }
        """.trimIndent()))
    }
}
