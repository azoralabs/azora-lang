/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Each case/mode/target reports independently, so one defect cannot hide later checks. */
@RunWith(Parameterized::class)
class VariadicSpreadExecTest(
    private val name: String,
    private val optimized: Boolean,
    private val target: String,
) {
    @Test fun execute() {
        assumeTrue("$target tools are required", if (target == "LLVM") LlvmExec.available else WasmExec.available)
        val case = VariadicSpreadTest.cases.single { it.name == name }
        val result = Compiler().compile(case.source, release = optimized)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        val ir = if (optimized) result.optimizedIr else result.ir
        val actual = if (target == "LLVM") LlvmExec.runIr(LlvmCodegen().generate(ir))
            else WasmExec.runWat(WasmCodegen().generate(ir))
        assertEquals(case.expected, actual, "$name; optimized=$optimized; $target")
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}; optimized={1}; {2}")
        fun cases(): List<Array<Any>> = VariadicSpreadTest.cases.flatMap { case ->
            listOf(false, true).flatMap { optimized ->
                listOf("LLVM", "WASM").map { target -> arrayOf(case.name, optimized, target) }
            }
        }
    }
}
