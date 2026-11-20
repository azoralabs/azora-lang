/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A `when` *expression* reads a plain call pattern as a value, as a statement
 * `when` always has (`SlotPatterns`): `Long(53) ->` compares the scrutinee
 * with the converted literal. The expression form used to decide in the parser
 * that any call with arguments destructures a payload and refused it with
 * `cannot destructure a slot payload`. `Slot.Variant(x)` and `.Variant(x)`
 * still destructure, and stay refused in an expression
 * (`VariantReturnAndPatternTest`).
 */
class WhenValuePatternExecTest {
    private val program = """
        import std.io
        func label(x: Long): String = when x {
            Long(53) -> "fifty-three"
            Long(7), Long(8) -> "small"
            else -> "other"
        }
        func width(b: UByte): Int = when b {
            UByte(255) -> 8
            UByte(1) -> 1
            else -> 0
        }
        func main() {
            var x: Long = 50
            var i = 0
            while i < 3 {
                x = x + 1
                i = i + 1
            }
            println(label(x))
            println(label(x - 46))
            println(label(x - 45))
            println(label(x + 1))
            fin hit = when x { Long(53) -> 1  else -> 0 }
            println(hit)
            var b: UByte = 254
            b = b + 1
            println(width(b))
            println(width(b - 254))
            println(width(b - 1))
        }
    """.trimIndent()

    private val expected = listOf("fifty-three", "small", "small", "other", "1", "8", "1", "0").joinToString("\n")

    @Test fun aConversionCallInAWhenExpressionIsAValue() {
        for (release in listOf(false, true)) {
            val result = Compiler().compile(program, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter release=$release")
        }
        if (LlvmExec.available) for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "LLVM optimized=$optimized")
        }
        if (WasmExec.available) assertEquals(expected, WasmExec.run(program).trimEnd(), "WebAssembly")
    }
}
