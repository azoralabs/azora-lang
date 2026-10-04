/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/** `SpecSubscriptTest`'s programs natively: a spec's `oper[]` dispatches like its other members. */
class SpecSubscriptExecTest {
    private val program = """
        import std.io
        import std.container.list
        import std.container.map
        func total(xs: List<Int>&): Int {
            var t = 0
            for i in 0..<xs.size { t += xs[i] }
            return t
        }
        func main() {
            var xs: MutableList<Int> = [4, 5]
            println(total(xs))
            fin names: Map<Int, String> = [1: "one", 2: "two"]
            println(names[2])
            var values: MutableMap<Int, Int> = [1: 10]
            values[3] = 30
            println(values[3])
        }
    """.trimIndent()

    @Test fun llvmDispatchesTheSpecSubscript() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals("9\ntwo\n30", LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyDispatchesTheSpecSubscript() {
        if (!WasmExec.available) return
        assertEquals("9\ntwo\n30", WasmExec.run(program))
    }
}
