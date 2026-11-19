/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A 16-byte value (`Cent`, `UCent`, `Quad`) given to an erased `T` crosses in a
 * heap cell of its own, since the slot holds eight bytes. The call site makes
 * the cell and frees it as it frees anything it only lends: after the call, or,
 * when the callee may hand the value back, once the result has been read out
 * of it. A callee that keeps the value - in a pack, a closure, or by passing it
 * to a function value - keeps the cell. Before, every crossing leaked one
 * (`leaks`: 4,000 cells for 1,000 iterations of three generic calls).
 */
class WideCellOwnershipExecTest {
    /** Crossings of every kind the call site can free, and nothing that prints a 128-bit value (its text is a string, which native code does not free yet). */
    private val crossings = """
        import std.io
        func<T> id(x: T): T = x
        func<T> first(a: T, b: T): T = a
        func<T> pick(c: Bool, a: T, b: T): T = if c then a else b
        func<T> ignore(x: T): Int = 1
        func<T> maybe(x: T): T? = x
        func main() {
            fin seed: Cent = 4_611_686_018_427_387_904
            fin top: UCent = 18_446_744_073_709_551_616
            var total: Cent = 0
            var calls = 0
            var i = 0
            while i < 1000 {
                total = total + id(seed) + first(seed, seed) + pick(i % 2 == 0, seed, seed * 2) + (maybe(seed) ?? 0)
                calls = calls + ignore(seed) + ignore(top)
                i = i + 1
            }
            println(total == seed * 4_500)
            println(id(top) == top)
            println(calls)
        }
    """.trimIndent()

    private val crossingsExpected = "true\ntrue\n2000"

    @Test fun theValuesCrossWhole() {
        for (release in listOf(false, true)) {
            val result = Compiler().compile(crossings, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals(crossingsExpected, IrInterpreter().interpret(result.ir).trim(), "interpreter release=$release")
        }
        if (LlvmExec.available) for (optimized in listOf(false, true)) {
            assertEquals(crossingsExpected, LlvmExec.run(crossings, optimized), "LLVM optimized=$optimized")
        }
        if (WasmExec.available) assertEquals(crossingsExpected, WasmExec.run(crossings).trimEnd(), "WebAssembly")
    }

    /** Which arguments the call site lends, and which a callee keeps. */
    @Test fun onlyACellNobodyKeepsIsFreed() {
        val program = """
            import std.io
            pack Box<T> { var item: T }
            func<T> wrap(x: T): Box<T> = Box<T>(x)
            func<T> later(x: T): () -> T = [x] { x }
            func<T> hand(x: T, sink: (T) -> Unit) { sink(x) }
            func<T> id(x: T): T = x
            func<T> ignore(x: T): Int = 1
            func main() {
                fin seed: Cent = 4_611_686_018_427_387_904
                fin b = wrap(seed)
                fin f = later(seed)
                hand(seed, { v: Cent -> println(v) })
                println(id(seed) + b.item + f())
                println(ignore(seed))
            }
        """.trimIndent()
        val result = assertIs<CompilationResult.Success>(Compiler().compile(program, release = false))
        val main = result.ir.prettyPrint().substringAfter("func main()")
        assertTrue("id(__temporaryUnlessReturned((seed as Any)))" in main, main)
        assertTrue("ignore(__temporary((seed as Any)))" in main, main)
        // Kept in a pack, captured by a closure, passed on to a function value.
        for (kept in listOf("wrap(seed)", "later(seed)", "hand(seed,")) assertTrue(kept in main, "$kept in $main")
    }

    /** No cell outlives its call: `leaks` finds nothing, AddressSanitizer no use after free. */
    @Test fun llvmFreesEveryLentCell() {
        if (!LlvmExec.available) return
        val clang = SanitizerClang.find("Wide cell ownership") ?: return
        val leaks = File("/usr/bin/leaks")
        val directory = Files.createTempDirectory("azora-wide-cells-").toFile()
        try {
            for (optimized in listOf(false, true)) {
                val ir = directory.resolve("main.ll").apply { writeText(LlvmExec.compile(crossings, optimized)) }
                val sanitized = directory.resolve("sanitized")
                run(directory, clang, ir.path, "-Wno-override-module", "-fsanitize=address,undefined", "-o", sanitized.path)
                assertEquals(crossingsExpected, run(directory, sanitized.path).trim(), "AddressSanitizer optimized=$optimized")
                // `leaks` is macOS's; elsewhere AddressSanitizer is the check.
                if (!leaks.canExecute()) continue
                val plain = directory.resolve("plain")
                run(directory, clang, ir.path, "-Wno-override-module", "-o", plain.path)
                val report = run(directory, leaks.path, "--atExit", "--", plain.path, allowFailure = true)
                assertTrue("0 leaks for 0 total leaked bytes" in report, "optimized=$optimized\n$report")
            }
        } finally { directory.deleteRecursively() }
    }

    /**
     * WebAssembly's heap stays at its first 16 pages through 100,000 iterations
     * of five crossings each. Not `maybe`: there a
     * `Cent?` is the address of a cell, so the cell lent to a `T?` function is
     * the optional it returns, and an optional's cell is not freed yet.
     */
    @Test fun webAssemblyFreesEveryLentCell() {
        if (!WasmExec.available) return
        val program = """
            import std.io
            bridge .C {
                func heapPages(): Int
            }
            func<T> id(x: T): T = x
            func<T> first(a: T, b: T): T = a
            func<T> pick(c: Bool, a: T, b: T): T = if c then a else b
            func<T> ignore(x: T): Int = 1
            func main() {
                fin seed: Cent = 4_611_686_018_427_387_904
                var total: Cent = 0
                var i = 0
                while i < 100_000 {
                    total = total + id(seed) + first(seed, seed) + pick(i % 2 == 0, seed, seed * 2) + ignore(seed)
                    i = i + 1
                }
                println(total == seed * 350_000 + 100_000)
                println(heapPages())
            }
        """.trimIndent()
        val output = WasmExec.runWat(WasmExec.compile(program), "heapPages: () => mem.buffer.byteLength / 65536,")
        assertEquals("true\n16", output.trimEnd())
    }

    private fun run(directory: File, vararg arguments: String, allowFailure: Boolean = false): String {
        val output = directory.resolve("stdout")
        val errors = directory.resolve("stderr")
        val process = ProcessBuilder(*arguments).redirectOutput(output).redirectError(errors).start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            fail("${arguments.first()} timed out")
        }
        if (!allowFailure) assertEquals(0, process.exitValue(), output.readText() + errors.readText())
        return output.readText() + errors.readText()
    }
}
