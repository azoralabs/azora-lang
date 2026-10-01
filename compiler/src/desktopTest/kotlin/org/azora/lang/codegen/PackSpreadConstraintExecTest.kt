/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PackSpreadConstraintExecTest(private val name: String, private val optimized: Boolean, private val target: String) {
    @Test fun executeOrDiagnoseUnsupportedResize() {
        assumeTrue("$target tools are required", if (target == "LLVM") LlvmExec.available else WasmExec.available)
        val case = PackSpreadConstraintTest.cases.single { it.name == name }
        val ir = PackSizeConstraintTest.compile(case.source, optimized)
        if (target == "WASM" && name in setOf("resizedSource", "frozenResizedSource", "unprovenProducer")) {
            val failure = assertFailsWith<IllegalStateException> { WasmCodegen().generate(ir) }
            assertTrue("method 'add'" in failure.message.orEmpty() && "not supported" in failure.message.orEmpty(),
                failure.message)
            return
        }
        val actual = if (target == "LLVM") LlvmExec.runIr(LlvmCodegen().generate(ir))
            else WasmExec.runWat(WasmCodegen().generate(ir))
        assertEquals(case.expected, actual, "$name; optimized=$optimized; $target")
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}; optimized={1}; {2}")
        fun cases(): List<Array<Any>> = PackSpreadConstraintTest.cases.flatMap { case ->
            listOf(false, true).flatMap { optimized ->
                listOf("LLVM", "WASM").map { target -> arrayOf(case.name, optimized, target) }
            }
        }
    }
}
