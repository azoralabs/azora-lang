/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A member's bound on its type's parameter (`func &.getOrDefault(…): V where
 * V: Clone` in `impl Map<K, V> for HashMap<K, V>`) gets its descriptor from
 * the call site, read off the receiver's type, as a function's own bounds do;
 * through a spec-typed value the dispatch passes it on (R-8, R-10). A pack
 * carries descriptors only for its own declaration's bounds, so the clone had
 * nothing to dispatch on: `no Clone witness for this generic value` on the
 * interpreter and LLVM, and a refusal on WebAssembly, which could not copy a
 * `String` or a number through one either.
 */
class MemberWitnessExecTest {
    private val program = """
        import std.io
        import std.container.map::{HashMap, LinkedHashMap, Map}
        pack Pt { var x: Int }
        func viaSpec(m: Map<String, Pt>&): Int = m.getOrDefault("a", Pt(0)).x
        func<K, V> generic(m: HashMap<K, V>&, k: K&, d: V): V where K: Hash, V: Clone = m.getOrDefault(k, d)
        func main() {
            var hm = HashMap<String, Pt>()
            hm.put("a", Pt(7))
            fin d = hm.getOrDefault("a", Pt(0))
            fin e = hm.getOrDefault("q", Pt(3))
            println(d.x + e.x)
            println(viaSpec(hm))
            println(generic(hm, "zz", Pt(5)).x)
            println(generic(hm, "a", Pt(5)).x)
            var lm = LinkedHashMap<String, Int>()
            lm.put("n", 2)
            println(lm.getOrDefault("n", 0) + lm.getOrDefault("m", 40))
            var names = HashMap<Int, String>()
            names.put(1, "azora")
            println(names.getOrDefault(1, "").size + names.getOrDefault(2, "xy").size)
        }
    """.trimIndent()

    private val expected = listOf("10", "7", "5", "7", "42", "7").joinToString("\n")

    @Test fun aMembersBoundOnItsTypesParameterIsDescribedByTheCaller() {
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

    /**
     * `getOrPut` stores the value and hands back a borrow of it (R-11), cloning
     * the key it needs to find it again (`where K: Clone`). Not on WebAssembly
     * yet: a closure with a pack result called through a generic function type
     * traps there (`function signature mismatch`), roadmap §1.1's closure item.
     */
    @Test fun getOrPutReturnsABorrowOfWhatItStored() {
        val source = """
            import std.io
            import std.container.map::{HashMap, TreeMap}
            pack Pt { var x: Int }
            func main() {
                var hm = HashMap<String, Pt>()
                hm.put("a", Pt(7))
                fin made = hm.getOrPut("b") { Pt(11) }
                println(made.x)
                println(hm.getOrPut("a") { Pt(99) }.x)
                println(hm.size)
                var tm = TreeMap<Int, String>()
                println(tm.getOrPut(4) { "four" }.size)
            }
        """.trimIndent()
        val want = listOf("11", "7", "2", "4").joinToString("\n")
        for (release in listOf(false, true)) {
            val result = Compiler().compile(source, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals(want, IrInterpreter().interpret(result.ir).trim(), "interpreter release=$release")
        }
        if (LlvmExec.available) for (optimized in listOf(false, true)) {
            assertEquals(want, LlvmExec.run(source, optimized), "LLVM optimized=$optimized")
        }
    }
}
