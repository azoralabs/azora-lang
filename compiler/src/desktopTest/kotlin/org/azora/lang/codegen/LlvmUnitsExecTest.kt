/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.LlvmUnits
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A program compiled as [LlvmUnits] parts - each part compiled on its own,
 * the objects linked - runs as the one module does, and an edit leaves the
 * text of every part that does not hold what changed as it was, which is what
 * lets a build keep those parts' objects.
 */
class LlvmUnitsExecTest {
    private val clang: String? by lazy {
        listOf("/usr/bin/clang", "/opt/homebrew/opt/llvm/bin/clang", "/usr/local/bin/clang")
            .firstOrNull { File(it).canExecute() }
            .also { check(it != null || System.getenv("AZORA_REQUIRE_NATIVE_TESTS") != "1") { "Native qualification requires clang" } }
    }

    /** Strings, a pack with an owned array (drop functions), a closure with a capture, and a global every part shares. */
    private fun program(extra: String = "") = """
        import std.io

        pack Tally {
            var name: String
            var counts: Array<Int> = []
        }

        fin banner = "tally of " + label(12)

        func record(tally: Tally!, value: Int): Int {
            tally.counts.add(value)
            return value
        }

        func describe(tally: Tally&): String = "${'$'}{tally.name}: ${'$'}{tally.counts.size} values"

        func apply(xs: Array<Int>&, action: (Int) -> Int): Int {
            var sum = 0
            for x in xs then sum = sum + action(x)
            return sum
        }

        func label(n: Int): String = if n > 10 then "large" else "small"

        func main() {
            var tally: Tally = .(name: "first")
            var total = 0
            for i: Int in 0..<5 then total = total + record(tally, i * 3)
            println(banner)
            println(describe(tally))
            fin offset = 4
            println(apply(tally.counts, [offset] { value: Int -> value + offset }))
            println(label(total))
            println("total ${'$'}{total}")
            $extra
        }
    """.trimIndent()

    /** Builds [module] as [parts] parts, links them and returns what the program printed. */
    private fun runParts(module: String, parts: Int): String {
        val tool = clang ?: return ""
        val directory = Files.createTempDirectory("azora_parts").toFile()
        try {
            val objects = LlvmUnits.split(module, parts).map { part ->
                val source = File(directory, part.name).apply { writeText(part.text) }
                val objectFile = File(directory, part.name.removeSuffix(".ll") + ".o")
                run(listOf(tool, "-O1", "-c", "-Wno-override-module", source.path, "-o", objectFile.path), "compiling ${part.name}:\n${part.text}")
                objectFile.path
            }
            val executable = File(directory, "program").path
            run(listOf(tool) + objects + listOf("-o", executable), "linking")
            return run(listOf(executable), "running").trim()
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun run(command: List<String>, what: String): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(LlvmExec.RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("timed out $what")
        }
        if (process.exitValue() != 0) fail("failed $what (exit ${process.exitValue()}):\n$output")
        return output
    }

    @Test
    fun aProgramBuiltAsPartsRunsAsTheWholeModuleDoes() {
        if (clang == null || !LlvmExec.available) return
        val source = program()
        val whole = LlvmExec.run(source, optimized = true)
        assertEquals("tally of large\nfirst: 5 values\n50\nlarge\ntotal 30", whole)
        for (parts in listOf(1, 3, 8)) {
            assertEquals(whole, runParts(LlvmExec.compile(source, optimized = true), parts), "$parts parts")
        }
    }

    @Test
    fun anEditChangesOnlyThePartHoldingWhatItChanged() {
        val parts = 8
        // A new string and a new closure in `main` renumber the module's string
        // constants and closure bodies; every part but `main`'s keeps its text.
        val before = LlvmUnits.split(LlvmExec.compile(program(), optimized = true), parts)
        val after = LlvmUnits.split(
            LlvmExec.compile(program("println(apply(tally.counts, { value: Int -> value * 2 }))\n    println(\"edited\")"), optimized = true),
            parts,
        )
        assertEquals(before.map { it.name }, after.map { it.name })
        val edited = "part-${LlvmUnits.partOf("main", parts).toString().padStart(2, '0')}.ll"
        val changed = before.zip(after).filter { (a, b) -> a.text != b.text }.map { it.first.name }
        assertEquals(listOf(edited), changed, "only main's part changes")
        if (clang != null && LlvmExec.available) {
            val whole = LlvmExec.run(program("println(apply(tally.counts, { value: Int -> value * 2 }))\n    println(\"edited\")"), optimized = true)
            assertTrue(whole.endsWith("60\nedited"), whole)
            assertEquals(whole, runParts(LlvmExec.compile(program("println(apply(tally.counts, { value: Int -> value * 2 }))\n    println(\"edited\")"), optimized = true), parts))
        }
    }

    @Test
    fun everyFunctionIsDefinedInExactlyOnePartAndTheVariablesInTheirOwn() {
        val module = LlvmExec.compile(program(), optimized = true)
        val parts = LlvmUnits.split(module, 4)
        assertEquals(LlvmUnits.GLOBALS, parts.last().name)
        val defined = parts.flatMap { part ->
            Regex("""(?m)^define (?!internal |linkonce_odr )[^@]*@([A-Za-z0-9_.$-]+)\(""").findAll(part.text).map { it.groupValues[1] }.toList()
        }
        assertEquals(defined.distinct(), defined, "a function defined in two parts")
        val inModule = Regex("""(?m)^define [^@]*@([A-Za-z0-9_.$-]+)\(""").findAll(module).map { it.groupValues[1] }
            .filterNot { it.startsWith("__azora_") }.toSet()
        assertEquals(inModule, defined.filterNot { it.startsWith("__azora_") }.toSet())
        assertTrue(Regex("""(?m)^@banner = global""").containsMatchIn(parts.last().text), parts.last().text)
        assertTrue(parts.dropLast(1).any { "@banner" in it.text })
        for (part in parts.dropLast(1)) {
            if ("@banner" in part.text) assertTrue("@banner = external global i8*" in part.text, part.text)
        }
    }
}
