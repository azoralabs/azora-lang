/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/** The factory-built literals of [LiteralFactoryTest] on the native targets. */
class LiteralFactoryExecTest {
    private val programs = listOf(
        LiteralFactoryTest.bag to "3\n8\n25\n2.5",
        LiteralFactoryTest.digits to "123\n123\n0",
        LiteralFactoryTest.stack to "3\n3\n2.5\n1.25\n2",
        LiteralFactoryTest.ledger to "2\ntea\n3.25\n15",
        LiteralFactoryTest.pairs to "1234\n1234\n0",
        LiteralFactoryTest.pairedBag to "2\n2",
        LiteralFactoryTest.keyed to "2\n1",
        LiteralFactoryTest.owned to "7",
    )

    @Test fun factoryLiteralsRunOnLlvm() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for ((source, expected) in programs) for (optimized in listOf(false, true)) {
            val ir = LiteralFactoryTest.compile(source, optimized)
            assertEquals(expected, LlvmExec.runIr(LlvmCodegen().generate(ir)), "optimized=$optimized")
        }
    }

    // A failing factory is a failing call: the fallback catches it on every
    // target. (WebAssembly used to trap here, having no error transport.)
    @Test fun aFailingFactoryFailsLikeACallOnEachTarget() {
        for (optimized in listOf(false, true)) {
            val ir = LiteralFactoryTest.compile(LiteralFactoryTest.bounded, optimized)
            if (LlvmExec.available) assertEquals("2\n0", LlvmExec.runIr(LlvmCodegen().generate(ir)), "optimized=$optimized")
            if (WasmExec.available) assertEquals("2\n0", WasmExec.runWat(WasmCodegen().generate(ir)), "optimized=$optimized")
        }
    }

    @Test fun factoryLiteralsRunOnWasm() {
        assumeTrue("Node.js and wat2wasm are required", WasmExec.available)
        for ((source, expected) in programs) for (optimized in listOf(false, true)) {
            val ir = LiteralFactoryTest.compile(source, optimized)
            assertEquals(expected, WasmExec.runWat(WasmCodegen().generate(ir)), "optimized=$optimized")
        }
    }
}
