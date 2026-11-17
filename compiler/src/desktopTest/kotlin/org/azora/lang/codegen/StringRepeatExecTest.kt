/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `"ab" * 3` repeats a string (BASE_SYNTAX_DIP §4), the same on every
 * backend; a negative count is a failure, not an empty or a garbage string.
 * The interpreter refused the operation, and LLVM called a `strcpy` it never
 * declared, so the module did not assemble.
 */
class StringRepeatExecTest {
    private val program = """
        import std.io
        func main() {
            fin s = "ab"
            fin n = 3
            println(s * n)
            println("x" * 0)
            println("-" * 2 + "|")
            println(2 * "yo")
            var t = "ok"
            t *= 2
            println(t)
        }
    """.trimIndent()

    private val expected = "ababab\n\n--|\nyoyo\nokok"

    private val negative = """
        import std.io
        func main() {
            fin n = 0 - 2
            println("ab" * n)
        }
    """.trimIndent()

    @Test fun theInterpreterRepeatsAString() {
        for (release in listOf(false, true)) {
            val result = Compiler().compile(program, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "release=$release")
        }
    }

    @Test fun llvmRepeatsAString() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyRepeatsAString() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program).trimEnd())
    }

    @Test fun aNegativeCountFailsEverywhere() {
        val result = assertIs<CompilationResult.Success>(Compiler().compile(negative, release = false))
        val failure = runCatching { IrInterpreter().interpret(result.ir) }.exceptionOrNull()
        assertTrue(failure != null && "a string repeated -2 times" in failure.toString(), "interpreter: $failure")
        if (LlvmExec.available) {
            val native = runCatching { LlvmExec.run(negative, false) }.exceptionOrNull()
            assertTrue(native != null && "a string repeated a negative number of times" in native.toString(), "LLVM: $native")
        }
        if (WasmExec.available) {
            val wasm = runCatching { WasmExec.run(negative) }.exceptionOrNull()
            assertTrue(wasm != null, "WebAssembly ran a negative repeat")
        }
    }
}
