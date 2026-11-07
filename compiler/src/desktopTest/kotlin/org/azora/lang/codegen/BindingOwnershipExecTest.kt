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
import kotlin.test.fail

/**
 * What decision D-7 asks a binding from a place to say - `take`, `.clone()` or
 * a borrow - runs the same in the interpreter and natively, and natively under
 * AddressSanitizer: a moved value is destroyed once, by its new owner; a clone
 * and its source are destroyed separately; a borrow destroys nothing.
 */
class BindingOwnershipExecTest {
    @Test fun takeCloneAndBorrowBindingsOwnWhatTheySay() = sanitized("""
        import std.io
        import std.traits
        pack Box {
            var items: Array<Int>
        }
        pack Holder {
            var box: Box
            var boxes: Array<Box>
        }
        func total(b: Box&): Int {
            var sum = 0
            for v in b.items { sum = sum + v }
            return sum
        }
        func main() {
            var a = Box([1, 2, 3])
            var moved = take a
            println(total(moved))
            var c = moved.clone()
            c.items.add(4)
            println("${'$'}{total(moved)} ${'$'}{total(c)}")
            if true {
                let r: Box& = c.&
                println(total(r))
            }
            var h = Holder(Box([5]), [Box([6]), Box([7, 8])])
            fin inner = take h.box
            println(total(inner))
            let second: Box& = h.boxes[1].&
            println(total(second))
            fin copy = h.boxes[0].clone()
            println(total(copy))
            if true {
                var m: Box! = c.!
                m.items.add(10)
            }
            println(total(c))
        }
    """, "6\n6 10\n10\n5\n15\n6\n20")

    private fun sanitized(source: String, expected: String) {
        val program = source.trimIndent()
        for (release in listOf(false, true)) {
            val compiled = Compiler().compile(program, release = release)
            val result = assertIs<CompilationResult.Success>(compiled, (compiled as? CompilationResult.Failure)?.errors.toString())
            assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter")
            val tool = SanitizerClang.find("Binding ownership qualification") ?: continue
            val directory = Files.createTempDirectory("azora-binding-ownership-").toFile()
            try {
                val ir = directory.resolve("main.ll").apply { writeText(result.llvm) }
                val binary = directory.resolve("probe")
                run(directory, tool, ir.path, "-Wno-override-module", "-fsanitize=address,undefined", "-o", binary.path)
                assertEquals(expected, run(directory, binary.path).trim(), "LLVM release=$release")
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun run(directory: File, vararg arguments: String): String {
        val output = directory.resolve("stdout")
        val errors = directory.resolve("stderr")
        val process = ProcessBuilder(*arguments).redirectOutput(output).redirectError(errors).start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            fail("native binding probe timed out")
        }
        assertEquals(0, process.exitValue(), output.readText() + errors.readText())
        if (arguments.size == 1) assertEquals("", errors.readText(), "sanitizer diagnostics")
        return output.readText()
    }
}
