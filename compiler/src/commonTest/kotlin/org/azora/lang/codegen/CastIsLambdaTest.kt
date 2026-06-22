package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CastIsLambdaTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    // as casts
    @Test fun asCastInt() {
        assertEquals("42", run("func main() { var x: Any = 42\n println(x as Int) }"))
    }

    @Test fun asCastString() {
        assertEquals("hello", run("func main() { var x: Any = \"hello\"\n println(x as String) }"))
    }

    // is checks
    @Test fun isCheckInt() {
        assertEquals("true\nfalse", run("""
            func main() {
                var x: Any = 42
                println(x is Int)
                println(x is String)
            }
        """.trimIndent()))
    }

    @Test fun isCheckString() {
        assertEquals("true", run("""
            func main() {
                var x: Any = "hello"
                println(x is String)
            }
        """.trimIndent()))
    }

    @Test fun isCheckWithIf() {
        assertEquals("number", run("""
            func describe(x: Any): String {
                if x is Int { return "number" }
                return "other"
            }
            func main() { println(describe(42)) }
        """.trimIndent()))
    }

    // typed lambdas still work
    @Test fun typedLambda() {
        assertEquals("6", run("""
            func apply(f: (Int) -> Int, x: Int): Int {
                return f(x)
            }
            func main() {
                println(apply({ x: Int -> x * 2 }, 3))
            }
        """.trimIndent()))
    }

    // implicit it (simple — no type-dependent ops)
    @Test fun implicitIt() {
        assertEquals("3", run("""
            func apply(f: (Any) -> Any, x: Any): Any {
                return f(x)
            }
            func main() {
                println(apply({ it }, 3))
            }
        """.trimIndent()))
    }
}
