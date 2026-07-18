package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolishTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    // Default param values
    @Test fun defaultParamValue() {
        assertEquals("Hello, World!\nHi, World!", run("""
            import std.io
            func greet(name: String, greeting: String = "Hello") {
                std::println(greeting + ", " + name + "!")
            }
            func main() {
                greet("World")
                greet("World", "Hi")
            }
        """.trimIndent()))
    }

    @Test fun defaultParamInt() {
        assertEquals("1\n42", run("""
            import std.io
            func power(val: Int, exp: Int = 0): Int {
                if exp == 0 { return 1 }
                return val
            }
            func main() {
                std::println(power(0))
                std::println(power(42, 1))
            }
        """.trimIndent()))
    }

    // Named function arguments
    @Test fun namedFunctionArgs() {
        assertEquals("A:30", run("""
            import std.io
            func create(label: String, value: Int): String {
                return label + ":" + value
            }
            func main() {
                std::println(create(value: 30, label: "A"))
            }
        """.trimIndent()))
    }

    // Exhaustiveness checking
    @Test fun exhaustiveWhenEnum() {
        assertEquals("red", run("""
            import std.io
            enum Color {
                Red
                Green
                Blue
            }
            func main() {
                fin c = Color.Red
                when c {
                    Color.Red -> { std::println("red") }
                    Color.Green -> { std::println("green") }
                    Color.Blue -> { std::println("blue") }
                }
            }
        """.trimIndent()))
    }

    @Test fun nonExhaustiveWhenErrors() {
        val result = Compiler().compile("""
            import std.io
            slot Opt {
                Some(Int)
                None
            }
            func main() {
                fin c = Opt.Some(1)
                when c {
                    Opt.Some(v) -> { std::println(v) }
                }
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { it.contains("non-exhaustive") }, "Expected non-exhaustive error, got: ${result.errors}")
    }
}
