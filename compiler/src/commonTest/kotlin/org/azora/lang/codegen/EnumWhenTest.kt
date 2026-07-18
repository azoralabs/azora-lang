package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnumWhenTest {

    private fun run(source: String, release: Boolean = false): String {
        val result = Compiler().compile(source, release = release)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun enumValuePrints() {
        assertEquals("Red", run("""
            import std.io
            enum Color {
                Red
                Green
                Blue
            }
            func main() {
                std::println(Color.Red)
            }
        """.trimIndent()))
    }

    @Test fun enumEquality() {
        assertEquals("true\nfalse", run("""
            import std.io
            enum Color {
                Red
                Green
                Blue
            }
            func main() {
                fin c = Color.Green
                std::println(c == Color.Green)
                std::println(c == Color.Red)
            }
        """.trimIndent()))
    }

    @Test fun whenMatchesEnum() {
        assertEquals("stop", run("""
            import std.io
            enum Light {
                Red
                Yellow
                Green
            }
            func main() {
                fin l = Light.Red
                when l {
                    Light.Red -> { std::println("stop") }
                    Light.Green -> { std::println("go") }
                    Light.Yellow -> { std::println("slow") }
                    else -> { std::println("unknown") }
                }
            }
        """.trimIndent()))
    }

    @Test fun whenMatchesEnumClean() {
        assertEquals("slow", run("""
            import std.io
            enum Light {
                Red
                Yellow
                Green
            }
            func main() {
                fin l = Light.Yellow
                when l {
                    Light.Red -> { std::println("stop") }
                    Light.Green -> { std::println("go") }
                    Light.Yellow -> { std::println("slow") }
                    else -> { std::println("unknown") }
                }
            }
        """.trimIndent()))
    }

    @Test fun whenElseFallback() {
        assertEquals("unknown", run("""
            import std.io
            enum E {
                A
                B
            }
            func main() {
                fin x = E.A
                when x {
                    E.B -> { std::println("b") }
                    else -> { std::println("unknown") }
                }
            }
        """.trimIndent()))
    }

    @Test fun enumWhenExhaustiveNoElse() {
        assertEquals("a", run("""
            import std.io
            enum E {
                A
                B
                C
            }
            func main() {
                fin x = E.A
                when x {
                    E.A -> { std::println("a") }
                    E.B -> { std::println("b") }
                    E.C -> { std::println("c") }
                }
            }
        """.trimIndent()))
    }

    @Test fun enumWhenNonExhaustiveErrors() {
        val result = Compiler().compile("""
            import std.io
            enum E {
                A
                B
                C
            }
            func main() {
                fin x = E.A
                when x {
                    E.A -> { std::println("a") }
                    E.B -> { std::println("b") }
                }
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result, "Expected non-exhaustive error")
        val errors = (result as CompilationResult.Failure).errors.joinToString()
        assertTrue("non-exhaustive" in errors || "C" in errors, "Expected exhaustiveness error, got: $errors")
    }

    @Test fun whenMatchesInteger() {
        assertEquals("two", run("""
            import std.io
            func main() {
                var n = 2
                when n {
                    1 -> { std::println("one") }
                    2 -> { std::println("two") }
                    3 -> { std::println("three") }
                    else -> { std::println("other") }
                }
            }
        """.trimIndent()))
    }

    @Test fun whenMultiPattern() {
        assertEquals("small", run("""
            import std.io
            func main() {
                var n = 2
                when n {
                    0, 1, 2, 3 -> { std::println("small") }
                    else -> { std::println("big") }
                }
            }
        """.trimIndent()))
    }

    @Test fun enumPassedToFunction() {
        assertEquals("go", run("""
            import std.io
            enum Light {
                Red
                Yellow
                Green
            }
            func action(l: String): String {
                when l {
                    Light.Red -> { return "stop" }
                    Light.Green -> { return "go" }
                    else -> { return "unknown" }
                }
            }
            func main() {
                std::println(action(Light.Green))
            }
        """.trimIndent()))
    }

    @Test fun enumWhenSurvivesOptimization() {
        assertEquals("stop", run("""
            import std.io
            enum Light {
                Red
                Yellow
                Green
            }
            func main() {
                fin l = Light.Red
                when l {
                    Light.Red -> { std::println("stop") }
                    Light.Green -> { std::println("go") }
                    else -> { std::println("unknown") }
                }
            }
        """.trimIndent(), release = true))
    }

    @Test fun enumLoweredToBackends() {
        val result = Compiler().compile("""
            import std.io
            enum Color {
                Red
                Green
            }
            func main() {
                fin c = Color.Red
                when c {
                    Color.Red -> { std::println("r") }
                    else -> { std::println("o") }
                }
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
        // JavaScript: enum value is a string literal, switch on a string
        assertTrue("\"Red\"" in result.javascript, result.javascript)
        assertTrue("switch" in result.javascript, result.javascript)
    }
}
