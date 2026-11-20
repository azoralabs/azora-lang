/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `.() * count` fills an array with the element's default. Natively the slots
 * came straight from `malloc`, so an array made where a freed one had been read
 * back that one's values: a mesh simplifier counting cluster members with
 * `var members: Array<Int> = .() * size` saw 14, then 28, then 42 members in the
 * same cluster on successive calls (2026-11-20). The slots are zeroed now.
 */
class ArrayFillDefaultExecTest {
    private val program = """
        import std.io
        func dirty(size: Int): Int {
            var scratch: Array<Int> = .() * size
            var i = 0
            while i < size {
                scratch[i] = 7 + i
                i += 1
            }
            return scratch[size - 1]
        }
        func dirtyReals(size: Int): Double {
            var scratch: Array<Double> = .() * size
            var i = 0
            while i < size {
                scratch[i] = 2.5
                i += 1
            }
            return scratch[0]
        }
        func counted(size: Int): Int {
            var counts: Array<Int> = .() * size
            var total = 0
            var i = 0
            while i < size {
                counts[i] += 1
                total += counts[i]
                i += 1
            }
            return total
        }
        func summed(size: Int): Double {
            var sums: Array<Double> = .() * size
            var total: Double = 0.0
            var i = 0
            while i < size {
                total += sums[i]
                i += 1
            }
            return total
        }
        func main() {
            var seen = 0
            var round = 0
            while round < 3 {
                seen += dirty(64)
                println(counted(64))
                seen += dirtyReals(32) as Int
                println(summed(32))
                round += 1
            }
            println(seen > 0)
        }
    """.trimIndent()

    @Test fun llvmFillsWithTheDefault() {
        for (optimized in listOf(false, true)) {
            assertEquals("64\n0.0\n64\n0.0\n64\n0.0\ntrue", LlvmExec.run(program, optimized).trim(), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyFillsWithTheDefault() {
        if (!WasmExec.available) return
        assertEquals("64\n0.0\n64\n0.0\n64\n0.0\ntrue", WasmExec.run(program).trim())
    }
}
