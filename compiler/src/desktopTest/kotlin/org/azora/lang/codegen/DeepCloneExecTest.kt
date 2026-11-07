/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `clone` copies what a value owns on every backend. WebAssembly copied a pack
 * only when it owned nothing but plain fields, and could not copy an array at
 * all ("cannot recursively copy … yet"); it now generates a deep copy per type,
 * as LLVM's does: owned packs, arrays and their owned elements are copied, so
 * writing through the copy leaves the original as it was.
 */
class DeepCloneExecTest {
    private fun everywhere(expected: String, source: String) {
        val program = source.trimIndent()
        val result = Compiler().compile(program)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter")
        if (LlvmExec.available) {
            assertEquals(expected, LlvmExec.run(program), "LLVM")
            assertEquals(expected, LlvmExec.run(program, optimized = true), "LLVM optimized")
        }
        if (WasmExec.available) assertEquals(expected, WasmExec.run(program), "WebAssembly")
    }

    @Test fun aCloneCopiesThePacksAndArraysItOwns() = everywhere(
        "1\n99\n1\n42\n20\n77\na",
        """
        import std.io
        pack Inner {
            var n: Int
        }
        pack Outer {
            var inner: Inner
            var items: Array<Int>
            var parts: Array<Inner>
            var label: String
        }
        func main() {
            var a = Outer(Inner(1), [1, 2, 3], [Inner(10), Inner(20)], "a")
            var b = a.clone()
            b.inner.n = 99
            b.items[0] = 42
            b.parts[1].n = 77
            println(a.inner.n)
            println(b.inner.n)
            println(a.items[0])
            println(b.items[0])
            println(a.parts[1].n)
            println(b.parts[1].n)
            println(b.label)
        }
        """,
    )

    @Test fun anArrayOfPacksClonesEachElement() = everywhere(
        "5\n50\n2\n3",
        """
        import std.io
        pack Inner {
            var n: Int
        }
        func main() {
            var xs = [Inner(5), Inner(6)]
            var ys = xs.clone()
            ys[0].n = 50
            println(xs[0].n)
            println(ys[0].n)
            var grid = [[1, 2], [3, 4]]
            var copy = grid.clone()
            copy[0][1] = 9
            println(grid[0][1])
            println(copy[1][0])
        }
        """,
    )
}
