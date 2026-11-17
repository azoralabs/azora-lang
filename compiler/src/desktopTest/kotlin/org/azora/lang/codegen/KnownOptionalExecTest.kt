/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * An optional bound to a known value is still an optional: asking it for
 * null, or taking its value, reads it as one. The optimizer used to stand the
 * bare literal in for the binding, and LLVM was handed `icmp ne i32 7, null`,
 * which does not assemble.
 */
class KnownOptionalExecTest {
    private val program = """
        import std.io
        func main() {
            var held: Int? = 7
            println(held != null)
            fin y: Int? = 8
            if y != null { println(y.require()) }
            fin none: Int? = null
            println(none == null)
            var taken: Int? = 9
            fin x = taken.take()
            println(x)
            println(taken == null)
        }
    """.trimIndent()

    private val expected = "true\n8\ntrue\n9\ntrue"

    @Test fun theInterpreterReadsAKnownOptionalAsOne() {
        for (release in listOf(false, true)) {
            val result = Compiler().compile(program, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "release=$release")
        }
    }

    @Test fun llvmReadsAKnownOptionalAsOne() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyReadsAKnownOptionalAsOne() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program).trimEnd())
    }
}
