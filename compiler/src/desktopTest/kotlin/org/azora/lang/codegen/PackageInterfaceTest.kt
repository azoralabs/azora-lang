/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.IrTypeCodec
import org.azora.lang.LibrarySource
import org.azora.lang.PackageBuild
import org.azora.lang.PackageInterface
import org.azora.lang.Sha256
import org.azora.lang.backend.LlvmUnits
import org.azora.lang.frontend.CallableKind
import org.azora.lang.ir.IrType
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A program built on its packages' interface ([PackageBuild]) runs as the whole
 * program does: the packages' functions are compiled once, without the program,
 * and linked with the program's own parts, which declare them.
 */
class PackageInterfaceTest {
    private val clang: String? by lazy {
        listOf("/usr/bin/clang", "/opt/homebrew/opt/llvm/bin/clang", "/usr/local/bin/clang")
            .firstOrNull { File(it).canExecute() }
            .also { check(it != null || System.getenv("AZORA_REQUIRE_NATIVE_TESTS") != "1") { "Native qualification requires clang" } }
    }

    /**
     * A package with what crosses the boundary: an owned array kept by its
     * callee, a fresh pack handed back, a closure, a global set by a call, and a
     * function that enumerates the program's `@Step` functions.
     */
    private val tally = LibrarySource("pkg/tally.az", """
        module pkg.tally

        import std.io

        annot @Step for (.Func) {
            fin order: Int = 0
        }

        pack Tally {
            var name: String
            var counts: Array<Int> = []
        }

        fin banner = "tally of " + label(12)

        func record(tally: Tally!, value: Int): Int {
            tally.counts.add(value)
            return value
        }

        func fresh(name: String): Tally = .(name: name)

        func absorb(tally: Tally!, values: Array<Int>&) {
            for v in values then tally.counts.add(v)
        }

        func describe(tally: Tally&): String = "${'$'}{tally.name}: ${'$'}{tally.counts.size} values"

        func apply(xs: Array<Int>&, action: (Int) -> Int): Int {
            var sum = 0
            for x in xs then sum = sum + action(x)
            return sum
        }

        func label(n: Int): String = if n > 10 then "large" else "small"

        func runSteps() {
            inline for S in reflect<*>.withAnnot<Step> {
                println(reflect<S>.declName)
                S()
            }
        }
    """.trimIndent())

    private fun program(extra: String = "") = """
        import std.io
        import pkg.tally

        @Step
        func first() { println("first ran") }

        @Step
        func second() { println("second ran") }

        func main() {
            var t = fresh("first")
            var total = 0
            for i: Int in 0..<5 then total = total + record(t, i * 3)
            println(banner)
            println(describe(t))
            fin offset = 4
            println(apply(t.counts, [offset] { value: Int -> value + offset }))
            println(label(total))
            runSteps()
            $extra
        }
    """.trimIndent()

    /** An interface cache in memory. */
    private class Memory : PackageBuild.Store {
        val files = sortedMapOf<String, String>()
        override fun read(path: String): String? = files[path]
        override fun write(path: String, text: String) { files[path] = text }
        override fun list(directory: String): List<String> {
            val prefix = if (directory.isEmpty()) "" else "$directory/"
            return files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
        }
        override fun remove(path: String) { files.keys.removeIf { it == path || it.startsWith("$path/") } }
    }

    private fun build(store: Memory, source: String): PackageBuild.Outcome {
        val (outcome, failure) = PackageBuild(store, "test") { it.path.startsWith("pkg/") }
            .compile(source, listOf(tally), parts = 4, defines = emptyMap(), release = true)
        assertNull(failure, failure?.errors?.joinToString("\n"))
        return assertNotNull(outcome)
    }

    /** Links [parts] and the interface parts [store] holds at [link], and returns what the program printed. */
    private fun run(parts: List<LlvmUnits.Part>, store: Memory, link: List<String>): String {
        val tool = clang ?: return ""
        val directory = Files.createTempDirectory("azora_interface").toFile()
        try {
            val sources = parts.map { it.name to it.text } + link.map { "interface-" + it.substringAfterLast('/') to store.files.getValue(it) }
            val objects = sources.map { (name, text) ->
                val source = File(directory, name).apply { writeText(text) }
                val objectFile = File(directory, name.removeSuffix(".ll") + ".o")
                exec(listOf(tool, "-O1", "-c", "-Wno-override-module", source.path, "-o", objectFile.path), "compiling $name:\n$text")
                objectFile.path
            }
            val executable = File(directory, "program").path
            exec(listOf(tool) + objects + listOf("-o", executable), "linking")
            return exec(listOf(executable), "running").trim()
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun exec(command: List<String>, what: String): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(LlvmExec.RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("timed out $what")
        }
        if (process.exitValue() != 0) fail("failed $what (exit ${process.exitValue()}):\n$output")
        return output
    }

    private fun whole(source: String): List<LlvmUnits.Part> {
        val result = Compiler(listOf(tally)).compile(source)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors?.joinToString("\n"))
        return LlvmUnits.split(result.llvm, 4)
    }

    @Test
    fun aProgramBuiltOnItsPackagesInterfaceRunsAsTheWholeProgramDoes() {
        val store = Memory()
        val first = build(store, program())
        assertTrue(first.notes.any { it.startsWith("package interface made") }, first.notes.toString())
        assertTrue(first.link.isEmpty(), "the first build is the whole program")
        assertTrue(first.prepare.isNotEmpty(), "and makes the interface for the next")

        val second = build(store, program())
        assertTrue(second.notes.any { it.startsWith("package interface used") }, second.notes.toString())
        assertEquals(first.prepare, second.link)
        val programText = second.parts.joinToString("\n") { it.text }
        // The package's own functions are declared, not defined, by the program...
        assertTrue(Regex("""(?m)^declare [^\n]*@describe\(""").containsMatchIn(programText), programText)
        assertTrue(!Regex("""(?m)^define [^\n]*@describe\(""").containsMatchIn(programText))
        // ...but what enumerates the program's declarations is the program's.
        assertTrue(Regex("""(?m)^define [^\n]*@runSteps\(""").containsMatchIn(programText), programText)

        if (clang == null || !LlvmExec.available) return
        val expected = run(whole(program()), store, emptyList())
        assertEquals("tally of large\nfirst: 5 values\n50\nlarge\nfirst\nfirst ran\nsecond\nsecond ran", expected)
        assertEquals(expected, run(first.parts, store, first.link))
        assertEquals(expected, run(second.parts, store, second.link))
    }

    @Test
    fun aDeclarationTheProgramStartsUsingIsCompiledByItAndThenAddedToTheInterface() {
        val store = Memory()
        build(store, program())
        val edited = program("absorb(t, [7, 8, 9])\n    println(describe(t))")
        val third = build(store, edited)
        assertTrue(third.notes.any { it.startsWith("package interface used") }, third.notes.toString())
        assertTrue(third.notes.any { it.startsWith("package interface extended") }, third.notes.toString())
        val programText = third.parts.joinToString("\n") { it.text }
        assertTrue(Regex("""(?m)^define [^\n]*@absorb\(""").containsMatchIn(programText), "compiled by the program this time")
        val fourth = build(store, edited)
        assertTrue(Regex("""(?m)^declare [^\n]*@absorb\(""").containsMatchIn(fourth.parts.joinToString("\n") { it.text }), "precompiled next time")

        if (clang == null || !LlvmExec.available) return
        val expected = run(whole(edited), store, emptyList())
        assertTrue(expected.endsWith("first: 8 values"), expected)
        assertEquals(expected, run(third.parts, store, third.link))
        assertEquals(expected, run(fourth.parts, store, fourth.link))
    }

    @Test
    fun anInterfaceThatDisagreesWithTheProgramIsNotUsed() {
        val store = Memory()
        build(store, program())
        val key = store.list("").single()
        val path = "$key/${PackageBuild.INTERFACE}"
        // The interface now says `label` takes a Double: the program's call
        // would not match the code linked for it.
        store.files[path] = store.files.getValue(path).replace(
            Regex("""(?m)^function\tlabel\tdeclare (\S+) @label\(i32\)$"""),
            "function\tlabel\tdeclare $1 @label(double)",
        )
        assertTrue("@label(double)" in store.files.getValue(path), "the declaration was rewritten")
        val refused = build(store, program())
        assertTrue(refused.notes.any { it.startsWith("package interface not used") && "@label" in it }, refused.notes.toString())
        assertTrue(refused.link.isEmpty(), "the whole program, linked alone")
        assertTrue(refused.prepare.isNotEmpty(), "its parts' objects are kept for later builds")
        if (clang == null || !LlvmExec.available) return
        assertEquals(run(whole(program()), store, emptyList()), run(refused.parts, store, refused.link))
    }

    @Test
    fun theInterfaceReadsBackAsItWasWritten() {
        val store = Memory()
        build(store, program())
        val key = store.list("").single()
        val text = store.files.getValue("$key/${PackageBuild.INTERFACE}")
        val iface = assertNotNull(PackageInterface.parse(text))
        assertEquals(key, iface.key)
        assertEquals(text, iface.render())
        assertNull(PackageInterface.parse(text.removeSuffix("end\n")), "an interface cut short is not one")
    }

    @Test
    fun typesRoundTripThroughTheirText() {
        val types = listOf(
            IrType.Int, IrType.Long, IrType.Integer(8, signed = false), IrType.String, IrType.Any, IrType.Quad,
            IrType.Array(IrType.Double), IrType.Array(IrType.Float, 4),
            IrType.Map(IrType.String, IrType.Nullable(IrType.Named("Box", listOf(IrType.Int)))),
            IrType.Set(IrType.Char), IrType.Task(IrType.Unit), IrType.Tuple(listOf(IrType.Bool, IrType.Half)),
            IrType.Variant(listOf(IrType.Int, IrType.String)), IrType.Pointer(IrType.Int, mutable = true),
            IrType.Named("Vec", listOf(IrType.Double), listOf(null, 3L)), IrType.Named("odd name%"),
            IrType.Function(listOf(IrType.Int), IrType.Bool, receivers = listOf(IrType.Named("Self")), kind = CallableKind.TASK, isEscaping = true),
        )
        for (type in types) {
            val decoded = IrTypeCodec.decode(IrTypeCodec.encode(type))
            assertEquals(type, decoded, IrTypeCodec.encode(type))
            assertEquals(type.toString(), decoded.toString())
            if (type is IrType.Named) assertEquals(type.args, (decoded as IrType.Named).args)
        }
    }

    @Test
    fun sha256MatchesTheStandardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", Sha256.hex("a".repeat(1_000_000)))
    }
}
