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
            func greet(name: String, greeting: String = "Hello") {
                println(greeting + ", " + name + "!")
            }
            func main() {
                greet("World")
                greet("World", "Hi")
            }
        """.trimIndent()))
    }

    @Test fun defaultParamInt() {
        assertEquals("1\n42", run("""
            func power(base: Int, exp: Int = 0): Int {
                if exp == 0 { return 1 }
                return base
            }
            func main() {
                println(power(0))
                println(power(42, 1))
            }
        """.trimIndent()))
    }

    // Named function arguments
    @Test fun namedFunctionArgs() {
        assertEquals("A:30", run("""
            func create(label: String, value: Int): String {
                return label + ":" + value
            }
            func main() {
                println(create(value: 30, label: "A"))
            }
        """.trimIndent()))
    }

    // Exhaustiveness checking
    @Test fun exhaustiveWhenEnum() {
        assertEquals("red", run("""
            enum Color {
                Red
                Green
                Blue
            }
            func main() {
                fin c = Color.Red
                when c {
                    Color.Red -> { println("red") }
                    Color.Green -> { println("green") }
                    Color.Blue -> { println("blue") }
                }
            }
        """.trimIndent()))
    }

    @Test fun nonExhaustiveWhenErrors() {
        val result = Compiler().compile("""
            slot Opt {
                Some(Int)
                None
            }
            func main() {
                fin c = Opt.Some(1)
                when c {
                    Opt.Some(v) -> { println(v) }
                }
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { it.contains("non-exhaustive") }, "Expected non-exhaustive error, got: ${result.errors}")
    }

    // Set literals
    @Test fun setLiteral() {
        assertEquals("3", run("""
            func main() {
                var s = ![1, 2, 3]
                println(s.length)
            }
        """.trimIndent()))
    }
}
