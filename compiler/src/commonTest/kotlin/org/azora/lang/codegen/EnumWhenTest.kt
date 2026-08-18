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
        assertEquals("Color.Red", run("""
            import std.io
            enum Color {
                Red
                Green
                Blue
            }
            func main() {
                println(Color.Red)
            }
        """.trimIndent()))
    }

    @Test fun typedDeclarationsAcceptContextualEnumVariants() {
        assertEquals("[WARN] LogLevel.Warn: contextual", run("""
            fin defaultLevel: LogLevel = .Warn
            func main() {
                var level3: LogLevel = .Warn
                fin level4: LogLevel = .Warn
                let level5: LogLevel = .Warn
                trace level3 { "${'$'}{it}: contextual" }
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
                println(c == Color.Green)
                println(c == Color.Red)
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
                    Light.Red -> { println("stop") }
                    Light.Green -> { println("go") }
                    Light.Yellow -> { println("slow") }
                    else -> { println("unknown") }
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
                    Light.Red -> { println("stop") }
                    Light.Green -> { println("go") }
                    Light.Yellow -> { println("slow") }
                    else -> { println("unknown") }
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
                    E.B -> { println("b") }
                    else -> { println("unknown") }
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
                    E.A -> { println("a") }
                    E.B -> { println("b") }
                    E.C -> { println("c") }
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
                    E.A -> { println("a") }
                    E.B -> { println("b") }
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
                    1 -> { println("one") }
                    2 -> { println("two") }
                    3 -> { println("three") }
                    else -> { println("other") }
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
                    0, 1, 2, 3 -> { println("small") }
                    else -> { println("big") }
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
                println(action(Light.Green))
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
                    Light.Red -> { println("stop") }
                    Light.Green -> { println("go") }
                    else -> { println("unknown") }
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
                    Color.Red -> { println("r") }
                    else -> { println("o") }
                }
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
    }
}
