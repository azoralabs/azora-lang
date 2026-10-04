/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `for x in xs` over the standard collections.
 *
 * `List` and `Set` became packs behind specs, and `for` walked only ranges,
 * arrays and iterators, so neither could be looped over at all - `std` itself
 * counted `for i in 0..<xs.size`. A collection that is `Indexed` (a `size` and
 * a `get(index)`) is now walked by position: `size` is read before every row,
 * and nested walks share nothing. A `Map` keyed by `Int` has members of the
 * same spelling and is not walked, because it does not say it is `Indexed`.
 *
 * `continue:outer` inside an inner loop also advances the outer walk; an
 * indexed iterator loop skipped that, and repeated its row forever.
 */
class IndexedWalkExecTest {
    private val program = """
        import std.io
        import std.container.set
        import std.container.list
        func total(xs: List<Int>&): Int {
            var t = 0
            for x in xs { t += x }
            return t
        }
        func main() {
            var values: MutableSet<Int> = [1, 2, 3, 2]
            var sum = 0
            for value in values {
                sum = sum + value
            }
            println(sum)
            var names = ArrayList<String>()
            names.add("a")
            names.add("b")
            names.add("c")
            outer: for n in names {
                for m in names {
                    if m == "b" { continue:outer }
                    if n == "c" { continue }
                    print("${'$'}{n}${'$'}{m} ")
                }
            }
            println("")
            for n in names with i {
                print("${'$'}{i}=${'$'}{n} ")
            }
            println("")
            var shrinking: MutableList<Int> = [1, 2, 3, 4]
            for x in shrinking {
                if x == 2 { shrinking.removeAt(0) }
                print("${'$'}{x} ")
            }
            println("")
            println(total([5, 6]))
        }
    """.trimIndent()

    private val expected = "6\naa ba \n0=a 1=b 2=c \n1 2 4 \n11"

    @Test fun theInterpreterWalksByPosition() {
        val result = Compiler().compile(program, release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(expected, IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun llvmWalksByPosition() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyWalksByPosition() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program).trimEnd())
    }

    @Test fun aMapIsNotWalkedByItsKeys() {
        val result = Compiler().compile(
            """
            import std.container.map
            func main() {
                fin m = hashMapOf(0 to "a", 1 to "b")
                for v in m { }
            }
            """.trimIndent(),
        )
        val errors = assertIs<CompilationResult.Failure>(result).errors
        assertTrue(
            errors.any { "for loop iterable must be a range, an array, an iterator, or an Indexed collection" in it },
            errors.toString(),
        )
    }
}
