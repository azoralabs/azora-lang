/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * An unsigned value prints and interpolates as the number it is on every
 * backend. `hash` is a `ULong`, so a string's hash printed as a negative
 * number on the interpreter and WebAssembly and as its unsigned value on LLVM:
 * the bits agreed and the text did not.
 */
class UnsignedDisplayExecTest {
    private fun everywhere(expected: String, source: String) {
        val program = source.trimIndent()
        val result = Compiler().compile(program)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter")
        if (LlvmExec.available) {
            assertEquals(expected, LlvmExec.run(program), "LLVM")
            assertEquals(expected, LlvmExec.run(program, optimized = true), "LLVM optimized")
        }
        if (WasmExec.available) assertEquals(expected, WasmExec.run(program), "WebAssembly")
    }

    @Test fun anUnsignedValuePrintsAsItsNumber() = everywhere(
        "18446744073709551615\n18446744073709551615\n4294967295\n4294967295\n255\n65535\n9223372036854775808",
        """
        import std.io
        func main() {
            var n: ULong = 0
            for i in 0..3 { n = n + 1 }
            n = n - 5
            println(n)
            println("${'$'}{n}")
            var u: UInt = 0
            for i in 0..3 { u = u + 1 }
            u = u - 5
            println(u)
            println("${'$'}{u}")
            var b: UByte = 0
            for i in 0..3 { b = b + 1 }
            b = b - 5
            println(b)
            var s: UShort = 0
            for i in 0..3 { s = s + 1 }
            s = s - 5
            println("${'$'}{s}")
            var top: ULong = 1
            for i in 0..62 { top = top * 2 }
            println(top)
        }
        """,
    )

    @Test fun aStringHashPrintsTheSameEverywhere() {
        val program = """
            import std.io
            func main() {
                fin s = "ab" + "c"
                println(s.hash)
                println("${'$'}{s.hash}")
                println(s.hash == "abc".hash)
            }
        """.trimIndent()
        val result = Compiler().compile(program)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        val lines = IrInterpreter().interpret(result.ir).trim().lines()
        assertEquals(3, lines.size, lines.toString())
        // A ULong is never written with a sign.
        assertEquals(lines[0], lines[1])
        assertEquals(false, lines[0].startsWith("-"), lines[0])
        assertEquals("true", lines[2])
        val expected = lines.joinToString("\n")
        if (LlvmExec.available) assertEquals(expected, LlvmExec.run(program), "LLVM")
        if (WasmExec.available) assertEquals(expected, WasmExec.run(program), "WebAssembly")
    }
}
