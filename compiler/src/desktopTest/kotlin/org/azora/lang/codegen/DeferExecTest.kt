/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `defer` runs when its function exits, on every target.
 *
 * Only the interpreter ran deferred blocks: LLVM lowered `defer` to a comment
 * and WebAssembly to nothing, so natively no cleanup ever ran. Each exit - a
 * `return`, falling off the end, or failing - now runs the function's
 * `defer`s, the last reached first and once per time reached; an `error defer`
 * runs only on a failing exit. A `return`'s value is computed before them.
 */
class DeferExecTest {
    @Test fun anEarlyExitCanPrecedeALocalCapturedByALaterDefer() {
        val source = """
            import std.io
            func work(early: Bool): Int {
                if early { return 7 }
                fin value = 8
                defer { println(value) }
                return 9
            }
            func main() { println(work(true))
                println(work(false)) }
        """.trimIndent()
        val result = assertIs<CompilationResult.Success>(Compiler().compile(source))
        assertEquals("7\n8\n9", IrInterpreter().interpret(result.ir).trim())
        if (LlvmExec.available) for (release in listOf(false, true)) assertEquals("7\n8\n9", LlvmExec.run(source, release))
        if (WasmExec.available) assertEquals("7\n8\n9", WasmExec.run(source).trim())
    }
    private val program = """
        import std.io
        error E { Bad }
        func work(): Int {
            defer { println("cleanup") }
            println("work")
            return 42
        }
        func fails(x: Int): Int ?! E {
            defer { println("always") }
            error defer { println("only on fail") }
            if x < 0 { error E.Bad }
            return x
        }
        func looped() {
            for i in 0..<3 {
                defer { println("loop defer") }
            }
            println("loop end")
        }
        func main() {
            println("start")
            defer { println("main cleanup2") }
            defer { println("main cleanup1") }
            println(work())
            println(fails(5) catch -1)
            println(fails(-1) catch -1)
            looped()
            println("end")
        }
    """.trimIndent()

    private val expected = listOf(
        "start", "work", "cleanup", "42",
        "always", "5",
        "only on fail", "always", "-1",
        "loop end", "loop defer", "loop defer", "loop defer",
        "end", "main cleanup1", "main cleanup2",
    ).joinToString("\n")

    @Test fun theInterpreterRunsDeferredBlocksAtExit() {
        val result = Compiler().compile(program, release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(expected, IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun llvmRunsDeferredBlocksAtExit() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyRunsDeferredBlocksAtExit() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program).trimEnd())
    }
}
