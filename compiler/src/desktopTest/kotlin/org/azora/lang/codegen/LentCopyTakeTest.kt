/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A by-value `Copy` parameter is lent by the call site: the callee sees the
 * caller's storage. `take` of one therefore copies it. Moving the pointer out
 * left the caller's field and the new value sharing one allocation, and the
 * second free of it took down the Engine's render queue the first time a
 * frame overwrote an item (`materialColor(renderable.color)`).
 */
class LentCopyTakeTest {
    private val program = """
        import std.io
        import std.traits

        pack Color {
            var r: Double
            var g: Double
        }

        pack Material {
            var color: Color
            var rough: Double
        }

        pack Prop {
            var color: Color
        }

        func materialColor(color: Color): Material = Material(take color, 0.8)

        func main() {
            var props: Array<Prop> = [Prop(Color(0.1, 0.2)), Prop(Color(0.3, 0.4))]
            var mats: Array<Material> = []
            for round in 0..<4 {
                for i in 0..<props.size {
                    fin piece = props[i].&
                    fin made = materialColor(piece.color)
                    if mats.size < 2 then mats.add(take made) else mats[i] = take made
                }
            }
            mats[1].color.g = 9.0
            println(props[1].color.g)
            println(mats[1].color.g)
        }
    """.trimIndent()

    @Test fun takingALentCopyParameterCopiesIt() {
        val result = Compiler().compile(program, release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        val ir = result.ir.prettyPrint()
        val body = ir.substringAfter("materialColor").substringBefore("\n}")
        assertTrue("__isolated(color)" in body, body)
        assertFalse("__take(color)" in body, body)
        assertEquals("0.4\n9.0", IrInterpreter().interpret(result.ir).trim())
        if (LlvmExec.available) for (optimized in listOf(false, true)) {
            assertEquals("0.4\n9.0", LlvmExec.run(program, optimized), "LLVM optimized=$optimized")
        }
    }

    /**
     * A constructed value, an array literal and a tuple own what they hold. A
     * `Copy` value a binding or a global already owns goes in as a copy:
     * `[Entity::invalid]` in a store's initialiser freed the constant itself
     * when the store was dropped.
     */
    @Test fun copyPlacesPutIntoValuesAreCopied() {
        val program = """
            import std.io
            import std.traits

            pack Color {
                var r: Double
                var g: Double
            }

            pack Holder {
                var c: Color
            }

            impl Color {
                fin black: Color = .(0.0, 0.25)
            }

            func make(): Array<Color> = [Color::black]

            func main() {
                var a = Color(0.5, 0.25)
                var h1 = Holder(a)
                fin h2 = Holder(a)
                fin xs: Array<Color> = [a, a]
                fin ys = make()
                fin zs = make()
                fin t = (a, 1)
                h1.c.g = 5.0
                a.g = 7.0
                println(h1.c.g + h2.c.g + xs[1].g + ys[0].g + zs[0].g + t.0.g)
            }
        """.trimIndent()
        val result = Compiler().compile(program, release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("6.25", IrInterpreter().interpret(result.ir).trim())
        if (LlvmExec.available) for (optimized in listOf(false, true)) {
            assertEquals("6.25", LlvmExec.run(program, optimized), "LLVM optimized=$optimized")
        }
    }
}
