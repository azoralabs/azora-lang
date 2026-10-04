/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `expr catch fallback` gives the value a failable call succeeds with, or the
 * fallback when it fails - on every backend.
 *
 * This replaces `unwrapOr` from `std.result`, an `Int`-only `Result` the error
 * model made redundant and which was removed with it. The LLVM lowering was
 * asserted only by shape (`ErrorTransportExecTest`); these run it, and check
 * that a handled error does not leak into the next call.
 */
class FailableFallbackExecTest {
    private val program = """
        import std.io
        error Lookup { Missing, Negative }
        func find(x: Int): Int ?! Lookup {
            if x < 0 {
                return .Negative
            }
            if x == 0 {
                return .Missing
            }
            return x * 2
        }
        func label(x: Int): String ?! Lookup {
            fin n = find(x)
            return "n=${'$'}n"
        }
        func main() {
            println(find(21) catch 0)
            println(find(-1) catch 0)
            println(find(0) catch find(5) catch 0)
            println(find(3) catch -1)
            println(label(4) catch "none")
            println(label(-4) catch "none")
        }
    """.trimIndent()

    private val expected = "42\n0\n10\n6\nn=8\nnone"

    @Test fun theInterpreterSelectsTheValueOrTheFallback() {
        val result = Compiler().compile(program, release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(expected, IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun llvmSelectsTheValueOrTheFallback() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblySelectsTheValueOrTheFallback() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program))
    }
}
