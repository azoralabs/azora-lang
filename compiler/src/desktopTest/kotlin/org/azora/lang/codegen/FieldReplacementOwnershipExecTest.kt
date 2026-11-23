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
 * Two ways a value used to be lost every time code ran (2026-11-22, found as
 * ~12 MB/s of growth in an Engine game):
 *
 * - `owner.field = new value` stored the new value over the old one and never
 *   freed the old: `motion.velocity = motion.velocity.add(push)` leaked a
 *   vector per step. The old value is now dropped once the new one is made -
 *   unless a local in scope may still share it (`fin seen = owner.field.&`).
 * - A function that may `return ::invalid` (a `fin` of a `Copy` type) hands
 *   back a copy, as every return of a `Copy` place does, yet its call sites
 *   counted the result as someone else's and never freed it: World's
 *   `getParent` leaked an Entity per call.
 */
class FieldReplacementOwnershipExecTest {
    private val program = """
        import std.io
        import std.traits
        pack Vec derives (Copy, Clone) {
            var x: Double = 0.0
            var y: Double = 0.0
        }
        impl Vec {
            func &.add(o: Vec&): Vec = .(self.x + o.x, self.y + o.y)
            func &.scale(k: Double): Vec = .(self.x * k, self.y * k)
        }
        pack Motion {
            var velocity: Vec = .()
            var trail: Array<Vec> = []
        }
        pack Ent derives (Copy, Clone, Equal) {
            var id: Int
            var generation: Int
        }
        impl Ent {
            fin invalid: Ent = .(-1, 0)
        }
        pack Tree {
            var parents: Array<Int> = [-1, 0, 0, 1]
        }
        impl Tree {
            func &.parentOf(e: Ent): Ent {
                if e.id < 0 || e.id >= self.parents.size then return ::invalid
                fin p = self.parents[e.id]
                return if p < 0 then ::invalid else .(p, 1)
            }
        }
        func push(m: Motion!, impulse: Vec&) {
            m.velocity = m.velocity.add(impulse.scale(0.5))
            m.trail = [m.velocity, impulse.scale(2.0)]
        }
        func main() {
            var m: Motion = .()
            fin kick: Vec = .(1.0, 2.0)
            var parented = 0
            fin tree: Tree = .()
            for i: Int in 0..<1000 {
                push(m, kick)
                if tree.parentOf(.(i % 5, 1)) == Ent(0, 1) then parented++
            }
            // A local that shares the field keeps it alive through the replacement.
            fin seen = m.trail.&
            m.trail = []
            println(seen.size == 2 && m.trail.size == 0)
            println(m.velocity.x == 500.0 && m.velocity.y == 1000.0)
            println(parented)
        }
    """.trimIndent()

    private val expected = "true\ntrue\n400"

    @Test fun theValuesAreRight() {
        for (release in listOf(false, true)) {
            val result = Compiler().compile(program, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter release=$release")
        }
        if (LlvmExec.available) for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "LLVM optimized=$optimized")
        }
        if (WasmExec.available) assertEquals(expected, WasmExec.run(program).trimEnd(), "WebAssembly")
    }

    /** Nothing is left behind (`leaks`), and nothing is freed under a reader (AddressSanitizer). */
    @Test fun llvmFreesEveryReplacedValue() {
        if (!LlvmExec.available) return
        val clang = SanitizerClang.find("Field replacement ownership") ?: return
        val leaks = File("/usr/bin/leaks")
        val directory = Files.createTempDirectory("azora-field-replacement-").toFile()
        try {
            for (optimized in listOf(false, true)) {
                val ir = directory.resolve("main.ll").apply { writeText(LlvmExec.compile(program, optimized)) }
                val sanitized = directory.resolve("sanitized")
                run(directory, clang, ir.path, "-Wno-override-module", "-fsanitize=address,undefined", "-o", sanitized.path)
                assertEquals(expected, run(directory, sanitized.path).trim(), "AddressSanitizer optimized=$optimized")
                if (!leaks.canExecute()) continue
                val plain = directory.resolve("plain")
                run(directory, clang, ir.path, "-Wno-override-module", "-o", plain.path)
                val report = run(directory, leaks.path, "--atExit", "--", plain.path, allowFailure = true)
                assertTrue("0 leaks for 0 total leaked bytes" in report, "optimized=$optimized\n$report")
            }
        } finally { directory.deleteRecursively() }
    }

    /** Output goes to files: a pipe nobody drains until exit would stall `leaks`. */
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
