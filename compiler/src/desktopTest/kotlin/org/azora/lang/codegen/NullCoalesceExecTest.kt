/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `a ?? b` is what `a` holds, or `b` when it holds nothing - and `b` runs only
 * then, as `&&` runs its right side only when it must.
 *
 * It was lowered to a call that only the interpreter implemented: LLVM
 * referenced an undefined `@__nullCoalesce` and the module did not assemble,
 * and since it was a call, `b` was evaluated whether or not it was needed.
 */
class NullCoalesceExecTest {
    private val program = """
        import std.io
        pack Counter { var n: Int }
        func fallback(c: Counter!): String {
            c.n += 1
            return "fallback"
        }
        func find(n: Int): String? {
            if n > 0 { return "found" }
            return null
        }
        func main() {
            var c = Counter(0)
            println(find(1) ?? fallback(c))
            println(find(0) ?? fallback(c))
            println(c.n)
            fin m: Long? = null
            println(m ?? Long(5))
            fin k: Long? = Long(9)
            println(k ?? Long(5))
        }
    """.trimIndent()

    private val expected = "found\nfallback\n1\n5\n9"

    @Test fun theInterpreterRunsTheFallbackOnlyWhenNeeded() {
        val result = Compiler().compile(program, release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(expected, IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun llvmRunsTheFallbackOnlyWhenNeeded() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyRunsTheFallbackOnlyWhenNeeded() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program).trimEnd())
    }
}
