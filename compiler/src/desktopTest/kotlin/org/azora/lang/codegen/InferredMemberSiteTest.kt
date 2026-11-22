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
 * What the resolver decides for a `.(…)` or `.Name` is recorded per node, not
 * per position. The modules of one program share line and column numbers, so
 * `return .(buffer, count)` in one file and `return .(x, y)` at the same place
 * in another used to read each other's answer: the Engine's cylinder mesh was
 * built as a `Vec2`-shaped value and its buffer handle came back as a Double.
 * Library modules also see inline constants and aliases inside `.(…)`, and a
 * `take` inside a returned `.(…)` is read once.
 */
class InferredMemberSiteTest {
    private val meshes = LibrarySource("lib/meshes.az", """
        module lib.meshes
        inline fin defaultCount = 36
        pack Mesh {
            var buffer: Long
            var count: Int
        }
        func cube(buffer: Long): Mesh {
            return .(buffer, defaultCount)
        }
        func sized(buffer: Long): Mesh = .(buffer: buffer, count: defaultCount)
    """.trimIndent())

    private val points = LibrarySource("lib/points.az", """
        module lib.points
        /** At the same line and column as `cube`'s `.(…)`. */
        pack Point {
            var x: Double
            var y: Double
        }
        func origin(x: Double): Point {
            return .(x, 2.5)
        }
        pack Held {
            var items: Array<Int>
        }
        func held(xs: Array<Int>): Held {
            var items = take xs
            return .(take items)
        }
    """.trimIndent())

    private val program = """
        import std.io
        import lib.meshes
        import lib.points
        func main() {
            fin m = cube(4096)
            fin p = origin(1.5)
            println(m.buffer)
            println(m.count)
            println(p.y)
            println(sized(7).count)
            println(held([1, 2, 3]).items.size)
        }
    """.trimIndent()

    @Test fun inferredConstructionsInDifferentModulesAtOnePositionStayApart() {
        val result = Compiler(listOf(meshes, points)).compile(program)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("4096\n36\n2.5\n36\n3", IrInterpreter().interpret(result.ir).trim())
    }
}
