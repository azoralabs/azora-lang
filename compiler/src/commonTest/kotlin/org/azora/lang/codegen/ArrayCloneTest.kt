/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `clone` on an array, set, map or tuple.
 *
 * A pack is clonable without any import when its fields are, and the compiler
 * supplies the body. An array was not: its `Clone` conformance lives on
 * `std.container.array`'s `Array` declaration, which a program that never
 * spells `Array` does not pull in. So `[1, 2, 3].clone()` reported
 * `no method 'clone' on array` with or without the import. A builtin aggregate
 * is now clonable by what it holds, the rule a pack's fields already follow.
 */
class ArrayCloneTest {
    private fun compile(source: String) = Compiler().compile(source.trimIndent(), release = false)

    private fun run(source: String): String {
        val result = compile(source)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun aCloneOwnsItsSlots() = assertEquals("1\n99\n[1, 2, 3]\n[1, 2, 3, 4]", run("""
        import std.io
        func main() {
            var original = [1, 2, 3]
            var copy = original.clone()
            copy[0] = 99
            println(original[0])
            println(copy[0])
            var grown = original.clone()
            grown.add(4)
            println(original)
            println(grown)
        }
    """))

    @Test fun nestedArraysAndPacksAreCopiedToo() = assertEquals("1\n7\n2\n8", run("""
        import std.io
        pack Box {
            var v: Int
        }
        func main() {
            var rows = [[1, 2], [3]]
            var rowsCopy = rows.clone()
            rowsCopy[0][0] = 7
            println(rows[0][0])
            println(rowsCopy[0][0])
            var boxes = [Box(2)]
            var boxesCopy = boxes.clone()
            boxesCopy[0].v = 8
            println(boxes[0].v)
            println(boxesCopy[0].v)
        }
    """))
}
