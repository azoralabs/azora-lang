/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The inferred generic calls of [InferredGenericCallTest] on the native targets,
 * where an erased result printed `<value>` (LLVM) or a pointer's bits (WASM).
 */
class InferredGenericCallExecTest {
    @Test fun inferredCallsRunOnLlvm() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for ((source, expected) in InferredGenericCallTest.programs) for (optimized in listOf(false, true)) {
            val ir = InferredGenericCallTest.compile(source, optimized)
            assertEquals(expected, LlvmExec.runIr(LlvmCodegen().generate(ir)), "optimized=$optimized")
        }
    }

    @Test fun inferredCallsRunOnWasm() {
        assumeTrue("Node.js and wat2wasm are required", WasmExec.available)
        for ((source, expected) in InferredGenericCallTest.programs) for (optimized in listOf(false, true)) {
            val ir = InferredGenericCallTest.compile(source, optimized)
            assertEquals(expected, WasmExec.runWat(WasmCodegen().generate(ir)), "optimized=$optimized")
        }
    }
}
