package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InfixFunctionsTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun basicInfixCall() {
        assertEquals("5", run("""
            pack Calc {
                var v: Int
            }
            impl Calc {
                func plus(other: Calc): Calc {
                    return Calc(self.v + other.v)
                }
            }
            func main() {
                var a = Calc(2)
                var b = Calc(3)
                var c = a plus b
                println(c.v)
            }
        """.trimIndent()))
    }

    @Test fun infixWithArithmetic() {
        assertEquals("11", run("""
            pack Calc {
                var v: Int
            }
            impl Calc {
                func plus(other: Calc): Calc {
                    return Calc(self.v + other.v)
                }
                func times(other: Calc): Calc {
                    return Calc(self.v * other.v)
                }
            }
            func main() {
                var a = Calc(2)
                var b = Calc(3)
                var c = Calc(5)
                // arithmetic binds tighter than infix: (a * b) plus c = 6 + 5 = 11
                var result = a times b plus c
                println(result.v)
            }
        """.trimIndent()))
    }

    @Test fun infixChained() {
        assertEquals("10", run("""
            pack Calc {
                var v: Int
            }
            impl Calc {
                func plus(other: Calc): Calc {
                    return Calc(self.v + other.v)
                }
            }
            func main() {
                var a = Calc(1)
                var b = Calc(2)
                var c = Calc(3)
                var d = Calc(4)
                // Left-associative: ((a plus b) plus c) plus d
                var result = a plus b plus c plus d
                println(result.v)
            }
        """.trimIndent()))
    }

    @Test fun infixWithComparison() {
        assertEquals("true", run("""
            pack Box {
                var v: Int
            }
            impl Box {
                func plus(other: Box): Box {
                    return Box(self.v + other.v)
                }
            }
            func main() {
                var a = Box(2)
                var b = Box(3)
                // (a plus b).v == 5
                var c = a plus b
                println(c.v == 5)
            }
        """.trimIndent()))
    }
}
