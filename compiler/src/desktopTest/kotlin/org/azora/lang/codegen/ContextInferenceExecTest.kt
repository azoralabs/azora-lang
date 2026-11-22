/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.LibrarySource
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What the surrounding code already states is not written again, and the
 * compiler reads it from there:
 *
 * - an `inline fin` read inside a named argument or a `catch` fallback folds as
 *   it does anywhere else;
 * - a nested generic call takes its type argument from the outer call, also
 *   when the outer function is bounded (`func<T: Clone>`), and also inside an
 *   `if` value or a block passed to a function.
 */
class ContextInferenceExecTest {
    private val shapes = LibrarySource("lib/shapes.az", """
        module lib.shapes
        import std.traits

        inline fin defaultSize = 7

        pack Box derives Clone {
            var size: Int
        }

        error ShapeError {
            Missing
        }

        func sized(): Box = .(size: defaultSize)
        func found(ok: Bool): Int ?! ShapeError = if ok then 1 else .Missing
        func sizeOr(ok: Bool): Int = found(ok) catch defaultSize
    """.trimIndent())

    private val program = """
        import std.io
        import std.traits
        import lib.shapes

        pack Slot<T> {
            var name: String
        }

        func<T> named(name: String): Slot<T> = .(name)
        inline func<T> slotOf(tag: Int): Slot<T> = named<T>(T.typeName)
        func<T: Clone> nameOr(slot: Slot<T>&, fallback: T&): String = slot.name
        func<T> nameOf(slot: Slot<T>&): String = slot.name
        func run(block: () -> Unit) { block() }

        func main() {
            println(sized().size)
            println(sizeOr(false))
            println(nameOr<Box>(slotOf(1), sized()))
            fin picked = if sized().size > 3 then nameOf<Box>(slotOf(2)) else "none"
            println(picked)
            run { println(nameOf<Box>(slotOf(3))) }
        }
    """.trimIndent()

    @Test fun whatTheContextStatesIsReadFromIt() {
        val result = Compiler(listOf(shapes)).compile(program)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("7\n7\nBox\nBox\nBox", IrInterpreter().interpret(result.ir).trim())
    }
}
