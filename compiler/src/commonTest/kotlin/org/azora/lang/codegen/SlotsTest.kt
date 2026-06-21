package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SlotsTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun slotConstructionOnly() {
        // Test just construction — no when matching
        run("""
            slot Option {
                Some(Int)
                None
            }
            func main() {
                var o = Option.Some(42)
                println("ok")
            }
        """.trimIndent())
    }

    @Test fun slotWhenNoBindings() {
        // Test when matching without bindings (no-payload variant)
        assertEquals("nothing", run("""
            slot Option {
                Some(Int)
                None
            }
            func main() {
                var o = Option.None
                when o {
                    Option.None -> { println("nothing") }
                    else -> { println("some") }
                }
            }
        """.trimIndent()))
    }

    @Test fun slotSomeWithPayload() {
        assertEquals("42", run("""
            slot Option {
                Some(Int)
                None
            }
            func main() {
                var o = Option.Some(42)
                when o {
                    Option.Some(v) -> { println(v) }
                    Option.None -> { println("nothing") }
                }
            }
        """.trimIndent()))
    }

    @Test fun slotMultiplePayloads() {
        assertEquals("7", run("""
            slot Shape {
                Circle(Int)
                Rect(Int, Int)
                Point
            }
            func main() {
                var s = Shape.Rect(3, 4)
                when s {
                    Shape.Circle(r) -> { println(r) }
                    Shape.Rect(w, h) -> { println(w + h) }
                    Shape.Point -> { println("0") }
                }
            }
        """.trimIndent()))
    }

    @Test fun slotNoPayloadVariant() {
        assertEquals("0", run("""
            slot Shape {
                Circle(Int)
                Rect(Int, Int)
                Point
            }
            func main() {
                var s = Shape.Point
                when s {
                    Shape.Circle(r) -> { println(r) }
                    Shape.Rect(w, h) -> { println(w + h) }
                    Shape.Point -> { println("0") }
                }
            }
        """.trimIndent()))
    }
}
