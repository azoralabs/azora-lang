/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/** The standard collection literals of [StdCollectionLiteralTest] on the native targets. */
class StdCollectionLiteralExecTest {
    @Test fun collectionLiteralsRunOnLlvm() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for ((source, expected) in StdCollectionLiteralTest.programs) for (optimized in listOf(false, true)) {
            val ir = StdCollectionLiteralTest.compile(source, optimized)
            assertEquals(expected, LlvmExec.runIr(LlvmCodegen().generate(ir)), "optimized=$optimized\n$source")
        }
    }

    @Test fun collectionLiteralsRunOnWasm() {
        assumeTrue("Node.js and wat2wasm are required", WasmExec.available)
        for ((source, expected) in StdCollectionLiteralTest.programs) for (optimized in listOf(false, true)) {
            val ir = StdCollectionLiteralTest.compile(source, optimized)
            assertEquals(expected, WasmExec.runWat(WasmCodegen().generate(ir)), "optimized=$optimized\n$source")
        }
    }
}
