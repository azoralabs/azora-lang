/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class PackSizeConstraintExecTest {
    @Test fun validSizesRunOnLlvm() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for (optimized in listOf(false, true)) {
            val ir = PackSizeConstraintTest.compile(PackSizeConstraintTest.valid, optimized)
            assertEquals("42\n7\n2", LlvmExec.runIr(LlvmCodegen().generate(ir)), "optimized=$optimized")
        }
    }

    @Test fun validSizesRunOnWasm() {
        assumeTrue("Node.js and wat2wasm are required", WasmExec.available)
        for (optimized in listOf(false, true)) {
            val ir = PackSizeConstraintTest.compile(PackSizeConstraintTest.valid, optimized)
            assertEquals("42\n7\n2", WasmExec.runWat(WasmCodegen().generate(ir)), "optimized=$optimized")
        }
    }
}
