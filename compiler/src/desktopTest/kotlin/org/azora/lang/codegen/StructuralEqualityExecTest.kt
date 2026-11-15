/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.azora.lang.semantic.StructuralEqualityTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/** [StructuralEqualityTest]'s programs on the native targets, which print what the interpreter does. */
class StructuralEqualityExecTest {
    @Test fun tuplesAndArraysCompareAndHashByTheirPartsOnLlvm() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for ((source, expected) in StructuralEqualityTest.programs) for (optimized in listOf(false, true)) {
            val ir = StructuralEqualityTest.compile(source, optimized)
            assertEquals(expected, LlvmExec.runIr(LlvmCodegen().generate(ir)), "optimized=$optimized\n$source")
        }
    }

    @Test fun tuplesAndArraysCompareAndHashByTheirPartsOnWasm() {
        assumeTrue("Node.js is required", WasmExec.available)
        for ((source, expected) in StructuralEqualityTest.programs) for (optimized in listOf(false, true)) {
            val ir = StructuralEqualityTest.compile(source, optimized)
            assertEquals(expected, WasmExec.runWat(WasmCodegen().generate(ir)), "optimized=$optimized\n$source")
        }
    }
}
