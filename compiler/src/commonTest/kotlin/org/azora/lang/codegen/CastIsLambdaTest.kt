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
        assertEquals("42", run("import std.io\nfunc main() { var x: Any = 42\n std::io::println(x as Int) }"))
    }

    @Test fun asCastString() {
        assertEquals("hello", run("import std.io\nfunc main() { var x: Any = \"hello\"\n std::io::println(x as String) }"))
    }

    // is checks
    @Test fun isCheckInt() {
        assertEquals("true\nfalse", run("""
            import std.io
            func main() {
                var x: Any = 42
                std::io::println(x is Int)
                std::io::println(x is String)
            }
        """.trimIndent()))
    }

    @Test fun isCheckString() {
        assertEquals("true", run("""
            import std.io
            func main() {
                var x: Any = "hello"
                std::io::println(x is String)
            }
        """.trimIndent()))
    }

    @Test fun isCheckWithIf() {
        assertEquals("number", run("""
            import std.io
            func describe(x: Any): String {
                if x is Int { return "number" }
                return "other"
            }
            func main() { std::io::println(describe(42)) }
        """.trimIndent()))
    }

    // typed lambdas still work
    @Test fun typedLambda() {
        assertEquals("6", run("""
            import std.io
            func apply(f: (Int) -> Int, x: Int): Int {
                return f(x)
            }
            func main() {
                std::io::println(apply({ x: Int -> x * 2 }, 3))
            }
        """.trimIndent()))
    }

    // implicit it (simple — no type-dependent ops)
    @Test fun implicitIt() {
        assertEquals("3", run("""
            import std.io
            func apply(f: (Any) -> Any, x: Any): Any {
                return f(x)
            }
            func main() {
                std::io::println(apply({ it }, 3))
            }
        """.trimIndent()))
    }
}
