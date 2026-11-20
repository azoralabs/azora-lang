/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A container's accessors hand back a borrow of the value it stores (roadmap
 * decision R-11): reading never copies, works for any element type, and the
 * caller owns nothing it could free. `HashMap<String, Pt>.get` returned the
 * map's own `Pt` as a value the caller's binding owned, and LLVM freed it at
 * the end of the scope - a crash in `__azora_free` before the program exited.
 */
class ContainerBorrowExecTest {
    private val program = """
        import std.io
        import std.container.list::{ArrayList}
        import std.container.map::{HashMap, LinkedHashMap, TreeMap}
        import std.container.set::{LinkedHashSet}
        import std.container.deque::{Deque}
        pack Pt { var x: Int }
        func twice(v: Int): Int = v * 2
        func main() {
            var hm = HashMap<String, Pt>()
            hm.put("a", Pt(7))
            fin got = hm.get("a")
            if got != null { println(got.x) }
            println(hm.get("zz") == null)
            var lm = LinkedHashMap<String, Pt>()
            lm.put("b", Pt(8))
            fin lg = lm.get("b")
            if lg != null { println(lg.x) }
            var tm = TreeMap<Int, Pt>()
            tm.put(3, Pt(9))
            fin tg = tm.get(3)
            if tg != null { println(tg.x) }
            var counts = HashMap<String, Int>()
            counts.put("n", 20)
            fin n = counts.get("n")
            if n != null {
                println(n + 1)
                println(twice(n))
            }
            println((counts.get("m") ?? 0) * 2)
            var l = ArrayList<Pt>()
            l.add(Pt(4))
            l.add(Pt(5))
            println(l.get(1).x + l[0].x)
            hm.put("b", Pt(11))
            println(hm["b"].x)
            var names = TreeMap<Int, String>()
            names.put(5, "five")
            names.put(9, "nine")
            fin first = names.firstKey()
            if first != null { println(first) }
            fin floor = names.floorKey(7)
            if floor != null { println(floor) }
            println(names[9])
            var s = LinkedHashSet<String>()
            s.add("x")
            s.add("yz")
            println(s.get(1).size + s[0].size)
            var q = Deque<Pt>()
            q.pushBack(Pt(1))
            q.pushBack(Pt(2))
            println(q.peekFront().x + q.peekBack().x)
        }
    """.trimIndent()

    private val expected = listOf("7", "true", "8", "9", "21", "40", "0", "9", "11", "5", "5", "nine", "3", "3").joinToString("\n")

    @Test fun accessorsBorrowWhatTheContainerStores() {
        for (release in listOf(false, true)) {
            val result = Compiler().compile(program, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter release=$release")
        }
        if (LlvmExec.available) for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "LLVM optimized=$optimized")
        }
        if (WasmExec.available) assertEquals(expected, WasmExec.run(program).trimEnd(), "WebAssembly")
        sanitized()
    }

    /** The same program under AddressSanitizer: nothing a borrow names is freed. */
    private fun sanitized() {
        val tool = SanitizerClang.find("Container borrow qualification") ?: return
        val result = assertIs<CompilationResult.Success>(Compiler().compile(program, release = false))
        val directory = Files.createTempDirectory("azora-container-borrow-").toFile()
        try {
            val ir = directory.resolve("main.ll").apply { writeText(result.llvm) }
            val binary = directory.resolve("probe")
            val build = ProcessBuilder(tool, ir.path, "-Wno-override-module", "-fsanitize=address,undefined", "-o", binary.path)
                .redirectErrorStream(true).start()
            check(build.waitFor(120, TimeUnit.SECONDS)) { "clang timed out" }
            assertEquals(0, build.exitValue(), build.inputStream.bufferedReader().readText())
            val out = directory.resolve("stdout")
            val err = directory.resolve("stderr")
            val run = ProcessBuilder(binary.path).redirectOutput(out).redirectError(err).start()
            check(run.waitFor(60, TimeUnit.SECONDS)) { "the sanitized probe timed out" }
            assertEquals(0, run.exitValue(), out.readText() + err.readText())
            assertEquals("", err.readText(), "sanitizer diagnostics")
            assertEquals(expected, out.readText().trim(), "LLVM under AddressSanitizer")
        } finally {
            directory.deleteRecursively()
        }
    }
}
