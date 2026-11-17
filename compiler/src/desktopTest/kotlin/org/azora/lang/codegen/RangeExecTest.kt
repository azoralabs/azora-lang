/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.azora.lang.semantic.RangeLoopTest
import org.azora.lang.semantic.RangeValueTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RANGE_DIP's programs on the native targets, which print what the
 * interpreter does (`RangeValueTest`). `everyOrderTypeIsAPartialOrder` is a
 * compile-only check there and runs as `WitnessTest`'s `partialOrder`.
 */
class RangeExecTest {
    private val programs = RangeValueTest.programs.filterKeys { it != "everyOrderTypeIsAPartialOrder" } + RangeLoopTest.programs

    @Test fun rangeProgramsRunOnLlvm() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for ((name, program) in programs) for (optimized in listOf(false, true)) {
            val (source, expected) = program
            val ir = RangeValueTest.compile(source, optimized)
            val out = runCatching { LlvmExec.runIr(LlvmCodegen().generate(ir)).trim() }
                .getOrElse { throw AssertionError("$name optimized=$optimized: ${it.message?.take(3000)}", it) }
            assertEquals(expected, out, "$name optimized=$optimized")
        }
    }

    @Test fun rangeProgramsRunOnWasm() {
        assumeTrue("Node.js is required", WasmExec.available)
        for ((name, program) in programs) for (optimized in listOf(false, true)) {
            val (source, expected) = program
            val ir = RangeValueTest.compile(source, optimized)
            val out = runCatching { WasmExec.runWat(WasmCodegen().generate(ir)).trim() }
                .getOrElse { throw AssertionError("$name optimized=$optimized: ${it.message?.take(3000)}", it) }
            assertEquals(expected, out, "$name optimized=$optimized")
        }
    }
}
