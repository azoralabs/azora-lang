/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A label on `for x in array` names that loop.
 *
 * The IR for walking an array had no label, so `continue:outer` and
 * `break:outer` aimed at one named nothing: WebAssembly code generation threw
 * on it and took the whole compilation down, LLVM ended the wrong loop, and
 * the interpreter let `break:outer` stop only the inner one.
 */
class LabelledArrayLoopExecTest {
    private val program = """
        import std.io
        func main() {
            outer: for x in [1, 2, 3] {
                for y in [10, 20, 30] {
                    if y == 20 { continue:outer }
                    if x == 3 { break:outer }
                    print("${'$'}{x}-${'$'}{y} ")
                }
                print("| ")
            }
            println("end")
        }
    """.trimIndent()

    private val expected = "1-10 2-10 end"

    @Test fun theInterpreterLeavesTheNamedLoop() {
        val result = Compiler().compile(program, release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(expected, IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun llvmLeavesTheNamedLoop() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyLeavesTheNamedLoop() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program).trimEnd())
    }
}
