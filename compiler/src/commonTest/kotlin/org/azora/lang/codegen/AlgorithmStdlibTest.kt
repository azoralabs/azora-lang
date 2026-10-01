/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `std.algorithm`'s `mergeSort` and `sortBy`, on inputs long enough to need
 * several merge passes.
 *
 * `mergeSort` did not compile: its grouped binding's `.() * n` had no type to
 * build, and its halves were copied with inclusive ranges, one past each end.
 * `sortBy` stepped its merge windows by writing a `for` loop's variable, which
 * never advanced the loop, so it merged every overlapping window.
 */
class AlgorithmStdlibTest {
    private fun run(body: String): String {
        val result = Compiler().compile(
            """
            import std.io
            import std.algorithm
            import std.container.array

            func show(values: Array<Int>): String {
                var line = ""
                for i in 0..<values.size { line = line + "${'$'}{values[i]} " }
                return line
            }

            func main() {
            $body
            }
            """.trimIndent(),
            release = false,
        )
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun mergeSortOrdersAndLeavesTheInputAlone() = assertEquals(
        "1 2 3 5 5 8 9 \n0 1 2 3 4 5 6 7 8 9 10 11 12 \n5",
        run("""
            fin values: Array<Int> = [5, 3, 9, 1, 5, 8, 2]
            println(show(mergeSort<Int>(values)))
            fin longer: Array<Int> = [12, 3, 7, 0, 11, 5, 9, 1, 10, 2, 8, 4, 6]
            println(show(mergeSort<Int>(longer)))
            println(values[0])
        """),
    )

    @Test fun sortByOrdersByTheKeyAcrossSeveralPasses() = assertEquals(
        "12 11 10 9 8 7 6 5 5 4 3 2 1 0",
        run("""
            fin values: Array<Int> = [5, 3, 9, 1, 5, 8, 2, 7, 4, 6, 0, 12, 11, 10]
            println(show(sortBy<Int, Int>(values, { x -> 0 - x })))
        """),
    )

    /** Equal keys keep the order they were written in, whatever pass merges them. */
    @Test fun sortByIsStable() = assertEquals(
        "12 11 14 13 15 25 22 21 24 31 33 35 32",
        run("""
            fin values: Array<Int> = [31, 12, 25, 11, 33, 14, 22, 13, 35, 21, 32, 15, 24]
            println(show(sortBy<Int, Int>(values, { x -> x / 10 })))
        """),
    )
}
