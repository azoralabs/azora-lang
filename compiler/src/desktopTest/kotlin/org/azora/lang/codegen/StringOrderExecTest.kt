/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Strings order by code point on every backend (RANGE_DIP: a `Range<String>`
 * needs it). LLVM refused `<` on strings before ("Unsupported string op: LT").
 * `"｡" < "😀"` is the case UTF-16 order gets wrong: U+FF61 is below U+1F600,
 * but its UTF-16 unit is above the emoji's high surrogate.
 */
class StringOrderExecTest {
    private val program = """
        import std.io
        import std.traits
        /** [text] when [n] is 1: a string the compiler cannot fold. */
        func pick(n: Int, text: String): String = if n == 1 then text else "?"
        func main() {
            var n = 0
            for i in 1..1 { n += i }
            fin a = pick(n, "a")
            fin b = pick(n, "b")
            fin ab = pick(n, "ab")
            fin acute = pick(n, "é")
            fin z = pick(n, "z")
            fin empty = pick(n, "")
            fin upper = pick(n, "Z")
            fin halfwidth = pick(n, "｡")
            fin emoji = pick(n, "😀")
            print(a < b)
            print(" ")
            print(ab < a)
            print(" ")
            print(a <= a)
            print(" ")
            print(acute > z)
            print(" ")
            print(empty < a)
            print(" ")
            print(upper < a)
            print(" ")
            print(halfwidth < emoji)
            print(" ")
            print(b >= ab)
            print(" ")
            print((n == 0) < (n == 1))
            print(" ")
            print("${'$'}{b <=> a}")
        }
    """.trimIndent()

    private val expected = "true false true true true true true true true Compare.Greater"

    private fun compile(optimized: Boolean) =
        Compiler().compile(program, release = optimized).let { result ->
            val success = assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            if (optimized) success.optimizedIr else success.ir
        }

    @Test fun stringsOrderByCodePointOnTheInterpreter() {
        assertEquals(expected, IrInterpreter().interpret(compile(false)).trim())
    }

    @Test fun stringsOrderByCodePointOnLlvm() {
        assumeTrue("LLVM lli is required", LlvmExec.available)
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.runIr(LlvmCodegen().generate(compile(optimized))).trim(), "optimized=$optimized")
        }
    }

    @Test fun stringsOrderByCodePointOnWasm() {
        assumeTrue("Node.js is required", WasmExec.available)
        for (optimized in listOf(false, true)) {
            assertEquals(expected, WasmExec.runWat(WasmCodegen().generate(compile(optimized))).trim(), "optimized=$optimized")
        }
    }
}
