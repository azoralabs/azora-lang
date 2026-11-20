/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A local name declared again at another type once the first binding's scope
 * has ended: `fin needed: Double` in a block, `fin needed = Vec3(…)` after it.
 * Nothing is shadowed, so the IR kept one name for both, and the backends keep
 * one local per name. LLVM emits every `defer` at every exit, including an exit
 * before the defer is registered, and resolved the vector's `purge` there to the
 * `Double` slot - "cannot empty owned storage of double" in a vehicle
 * simulation (2026-11-20). WebAssembly gave both bindings one `i32` local and
 * the module failed validation. Each now has a name of its own.
 */
class ReusedLocalNameExecTest {
    private val program = """
        import std.io

        pack Box {
            var x: Double = 0.0
            var y: Double = 0.0
        }

        func total(flag: Bool, k: Double): Double {
            var sum: Double = 0.0
            if flag {
                fin needed: Double = k * 2.0
                sum += needed
            }
            if k > 5.0 then return sum
            fin needed = Box(k, 2.0)
            sum += needed.x + needed.y
            return sum
        }

        func walk(count: Int, stop: Int): Double {
            var sum: Double = 0.0
            if count > 0 {
                fin point = Box(1.0, 1.0)
                sum += point.x
            }
            var point = 0
            while point < count {
                if point == stop then return sum
                sum += point as Double
                point += 1
            }
            return sum
        }

        func main() {
            println(total(true, 1.0))
            println(total(true, 6.0))
            println(total(false, 3.0))
            println(walk(4, 9))
            println(walk(4, 2))
        }
    """.trimIndent()

    private val expected = "5.0\n12.0\n5.0\n7.0\n2.0"

    @Test fun llvmKeepsEachBindingInItsOwnSlot() {
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized).trim(), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyKeepsEachBindingInItsOwnLocal() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program).trim())
    }
}
