/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Every integer type's `minValue` and `maxValue` is the number std writes,
 * on every backend. std splices `ULong::maxValue` from a list of decimal
 * strings; the splice folded the lone literal through a `Long` and wrote
 * back `-1`, so `ULong::maxValue - x` was refused (`integer literal -1 does
 * not fit ULong`).
 */
class PrimitiveLimitsExecTest {
    private val program = """
        import std.io
        func main() {
            fin x: ULong = 5
            println(ULong::maxValue - x)
            println(ULong::maxValue)
            println(UInt::maxValue)
            println(UByte::maxValue)
            println(Long::minValue)
            println(Long::maxValue)
            println(Byte::minValue)
            println(Short::maxValue)
        }
    """.trimIndent()

    private val expected = "18446744073709551610\n18446744073709551615\n4294967295\n255\n" +
        "-9223372036854775808\n9223372036854775807\n-128\n32767"

    @Test fun theInterpreterKnowsEveryLimit() {
        for (release in listOf(false, true)) {
            val result = Compiler().compile(program, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "release=$release")
        }
    }

    @Test fun llvmKnowsEveryLimit() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyKnowsEveryLimit() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program).trimEnd())
    }
}
