/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * An integer and a float in one arithmetic operation promote to the float
 * type, as the resolver types them - `Int * Double` is a `Double` - and the
 * integer is converted before the operation, on every backend. The float
 * literal used to be cast to the integer's type instead (`(one - 1) * 1.0`
 * multiplied integers on the interpreter), and LLVM and WebAssembly were
 * handed an integer where a float was expected and did not assemble.
 */
class MixedArithmeticExecTest {
    private val program = """
        import std.io
        func main() {
            fin one: Int = 1
            fin two: Int = 2
            fin big: Long = 3
            fin d: Double = 0.5
            fin f: Float = 0.25
            println((one - 1) * 1.0)
            println(two * d)
            println(d + two)
            println(two / 4.0)
            println(f * two)
            println(one + 0.5)
            println(big * d)
            println(f + d)
            println(7 % 2.5)
        }
    """.trimIndent()

    private val expected = "0.0\n1.0\n2.5\n0.5\n0.5\n1.5\n1.5\n0.75\n2.0"

    /** A float `%` is C's `fmod` - exact, with the sign of the left side - on every backend. */
    private val remainders = """
        import std.io
        func main() {
            fin a: Double = -7.5
            println(a % 2.0)
            fin huge: Double = 1e22
            println(huge % 7.0)
            fin seven: Double = 7.0
            println(seven % -2.5)
            fin small: Float = 5.5
            println(small % 2.0)
            fin zero: Double = 0.0
            fin nan = seven % zero
            println(nan != nan)
        }
    """.trimIndent()

    private val remaindersExpected = "-1.5\n4.0\n2.0\n1.5\ntrue"

    @Test fun aFloatRemainderIsTheSameEverywhere() {
        val result = assertIs<CompilationResult.Success>(Compiler().compile(remainders, release = false))
        assertEquals(remaindersExpected, IrInterpreter().interpret(result.ir).trim(), "interpreter")
        if (LlvmExec.available) assertEquals(remaindersExpected, LlvmExec.run(remainders, false), "LLVM")
        if (WasmExec.available) assertEquals(remaindersExpected, WasmExec.run(remainders).trimEnd(), "WebAssembly")
    }

    @Test fun theInterpreterPromotesTheInteger() {
        for (release in listOf(false, true)) {
            val result = Compiler().compile(program, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "release=$release")
        }
    }

    @Test fun llvmPromotesTheInteger() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyPromotesTheInteger() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program).trimEnd())
    }
}
