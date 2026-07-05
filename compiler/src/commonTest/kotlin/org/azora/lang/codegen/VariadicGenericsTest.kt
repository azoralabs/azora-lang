package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for variadic generics: `...T` type params, `args: ...T` variadic params, `...arr` spread.
 */
class VariadicGenericsTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun variadicFunctionCollectsArgs() {
        assertEquals("3\n10", run("""
            func<...T> variadicSum(first: Int, rest: ...T): Int {
                var total = first
                for x in rest {
                    total = total + x
                }
                return total
            }
            func main() {
                println(variadicSum(1, 2))
                println(variadicSum(1, 2, 3, 4))
            }
        """.trimIndent()))
    }

    @Test fun variadicWithNoExtraArgs() {
        assertEquals("42", run("""
            func<...T> variadic(first: Int, rest: ...T): Int {
                return first
            }
            func main() {
                println(variadic(42))
            }
        """.trimIndent()))
    }

    @Test fun spreadOperatorInCall() {
        assertEquals("6", run("""
            func sum3(a: Int, b: Int, c: Int): Int {
                return a + b + c
            }
            func main() {
                var nums = [1, 2, 3]
                println(sum3(...nums))
            }
        """.trimIndent()))
    }
}
