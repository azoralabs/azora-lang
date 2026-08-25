/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/** Native ABI coverage for first-class Unit and bottom-typed control flow. */
class UnitNothingExecTest {
    private val source = """
        import std.io

        func completed(): Unit { return Unit }
        func stop(message: String): Nothing { panic message }

        func choose(available: Bool): Int {
            return if available { 7 } else { stop("missing") }
        }

        func main() {
            fin first: Unit = completed()
            fin second: Unit = Unit
            println(first == second)
            println(choose(true))
        }
    """.trimIndent()

    @Test
    fun llvmPreservesUnitAndNothingSemantics() {
        if (!LlvmExec.available) return
        assertEquals("true\n7", LlvmExec.run(source))
    }

    @Test
    fun wasmPreservesUnitAndNothingSemantics() {
        if (!WasmExec.available) return
        assertEquals("true\n7", WasmExec.run(source))
    }
}
