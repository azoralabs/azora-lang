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

    @Test fun enumEquality() {
        assertEquals("true\nfalse", run("""
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

    @Test fun whenMatchesInteger() {
        assertEquals("two", run("""
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
        // Kotlin: enum value is a string literal, when on a string
        assertTrue("\"Red\"" in result.kotlin, result.kotlin)
        assertTrue("when (" in result.kotlin, result.kotlin)
        // TypeScript: switch on a string
        assertTrue("switch" in result.typescript, result.typescript)
    }
}
