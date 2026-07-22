package org.azora.lang.codegen

import org.azora.lang.Compiler
import org.azora.lang.CompilationResult
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VariadicLambdaTest {

    private fun run(source: String): String {
        val r = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(r, "Compile failed: ${(r as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(r.ir).trim()
    }

    @Test fun variadicLambdaLength() {
        assertEquals("0\n1\n3", run("""
            import std.io
            func main() {
                fin len = <...T>{ it.length }
                std::println(len())
                std::println(len(1))
                std::println(len(1, 2, 3))
            }
        """.trimIndent()))
    }

    @Test fun variadicLambdaFirst() {
        assertEquals("42\n10", run("""
            import std.io
            func main() {
                fin first = <...T>{ it[0] }
                std::println(first(42))
                std::println(first(10, 20, 30))
            }
        """.trimIndent()))
    }

    @Test fun variadicLambdaSum() {
        // A `for` body inside a lambda now sees the lambda's locals.
        assertEquals("6\n100", run("""
            import std.io
            func main() {
                fin sum = <...T>{
                    var total = 0
                    for x in it { total = total + x }
                    total
                }
                std::println(sum(1, 2, 3))
                std::println(sum(10, 20, 30, 40))
            }
        """.trimIndent()))
    }

    @Test fun regularLambdaForLocalScoping() {
        // Regression: a non-variadic lambda with a for loop + local accumulator.
        assertEquals("6", run("""
            import std.io
            func main() {
                fin f = { xs: Array<Int> ->
                    var total = 0
                    for x in xs { total = total + x }
                    total
                }
                std::println(f(arr![1, 2, 3]))
            }
        """.trimIndent()))
    }
}
