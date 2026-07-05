package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LambdaTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun lambdaAssignedAndCalled() {
        assertEquals("10", run("""
            func main() {
                var double = { x: Int -> x * 2 }
                println(double(5))
            }
        """.trimIndent()))
    }

    @Test fun higherOrderFunction() {
        assertEquals("16", run("""
            func apply(f: (Int) -> Int, x: Int): Int {
                return f(x)
            }
            func main() {
                println(apply({ x: Int -> x * x }, 4))
            }
        """.trimIndent()))
    }

    @Test fun closureCapturesParameter() {
        assertEquals("15", run("""
            func makeAdder(n: Int): (Int) -> Int {
                return { x: Int -> x + n }
            }
            func main() {
                var add5 = makeAdder(5)
                println(add5(10))
            }
        """.trimIndent()))
    }

    @Test fun closureCapturesLocalVar() {
        assertEquals("7", run("""
            func main() {
                var offset = 3
                var add = { x: Int -> x + offset }
                println(add(4))
            }
        """.trimIndent()))
    }

    @Test fun lambdaInInterpolation() {
        assertEquals("25", run("""
            func apply(f: (Int) -> Int, x: Int): Int {
                return f(x)
            }
            func main() {
                println("${'$'}{apply({ x: Int -> x * x }, 5)}")
            }
        """.trimIndent()))
    }

    @Test fun noParamLambda() {
        assertEquals("hi", run("""
            func run(g: () -> String): String {
                return g()
            }
            func main() {
                println(run({ -> "hi" }))
            }
        """.trimIndent()))
    }
}
