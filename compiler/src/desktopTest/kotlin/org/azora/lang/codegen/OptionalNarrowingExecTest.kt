/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An optional binding that cannot be rebound reads as its inner type where a
 * test proved it non-null (TYPE_DIP §2.1): `fin key = find(code); if key !=
 * null && pressed(key)` was refused with `expected KeyCode, got KeyCode?`.
 * The resolver and the IR generator apply one rule (`OptionalNarrowing`), so
 * each backend reads the value the optional holds.
 */
class OptionalNarrowingExecTest {
    private val program = """
        import std.io
        enum KeyCode { A, B }
        pack P {
            var v: Int
        }
        func find(i: Int): Int? = if i == 0 then 5 else null
        func key(i: Int): KeyCode? = if i == 0 then KeyCode.A else null
        func box(i: Int): P? = if i == 0 then P(7) else null
        func name(i: Int): String? = if i == 0 then "azora" else null
        func twice(v: Int): Int = v * 2
        func pressed(k: KeyCode): Bool = k == .A
        func early(i: Int): Int {
            fin a = find(i)
            if a == null { return -1 }
            return twice(a)
        }
        func either(i: Int): Int {
            fin a = find(i)
            if a == null || twice(a) > 100 { return 0 }
            return a + 1
        }
        func branches(i: Int): Int {
            fin a = find(i)
            if a == null { return 0 } else { return twice(a) + 1 }
        }
        func negated(i: Int): Int {
            fin a = find(i)
            return if !(a == null) then twice(a) else 0
        }
        func shadowed(i: Int): Int {
            fin a = find(i)
            if a != null {
                fin a = find(1)
                return a ?? 9
            }
            return 0
        }
        func loops(i: Int): Int {
            fin a = find(i)
            var n = 0
            while a != null && n < 3 {
                n = n + a
            }
            return n
        }
        func moved(i: Int): Int {
            fin b = box(i)
            if b != null {
                fin q = take b
                return q.v
            }
            return 0
        }
        func parameter(a: Int?): Int = if a != null then twice(a) else -3
        func main() {
            var z = 0
            z = z + 0
            fin k = key(z)
            if k != null && pressed(k) { println("pressed") }
            let b = box(z)
            if b != null {
                b.v = b.v + 1
                println(b.v)
            }
            println(if b != null then b.v else 0)
            fin s = name(z)
            if s != null { println(s.size) }
            println(early(z))
            println(early(z + 1))
            println(either(z))
            println(either(z + 1))
            println(branches(z))
            println(negated(z))
            println(negated(z + 1))
            println(shadowed(z))
            println(loops(z))
            println(moved(z))
            println(moved(z + 1))
            println(parameter(find(z)))
            println(parameter(find(z + 1)))
        }
    """.trimIndent()

    private val expected = listOf(
        "pressed", "8", "8", "5", "10", "-1", "6", "0", "11", "10", "0", "9", "5", "7", "0", "10", "-3",
    ).joinToString("\n")

    @Test fun aProvenOptionalReadsAsItsValueOnEveryBackend() {
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
     * A narrowed `Int?` reads as a copy of the `Int` it holds, so lending it to
     * be written would lose the write; a narrowed pack reads as the pointer
     * the optional holds, so writing through it is allowed (above).
     */
    @Test fun aNarrowedCopyCannotBeWrittenThrough() {
        val source = """
            import std.io
            func find(i: Int): Int? = if i == 0 then 5 else null
            func bump(v: Int!) { v = v + 1 }
            func main() {
                var z = 0
                z = z + 0
                let a = find(z)
                if a != null { bump(a) }
            }
        """.trimIndent()
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Failure>(result, "a write through a narrowed copy must be refused")
        assertTrue(
            result.errors.any { "through 'a' - it reads as 'Int' here because a test proved it non-null" in it },
            "expected the write to be refused, got: ${result.errors}",
        )
    }

    /**
     * Nothing narrows where the test does not hold: a `var` or a `val` (its
     * name can be rebound, so it can be set back to null), a lambda's body (a capture is a binding of its own), and
     * after an `if` that can fall through.
     */
    @Test fun nothingNarrowsWhereTheTestDoesNotHold() {
        val bodies = listOf(
            "var a = find(z)\n    if a != null { println(twice(a)) }",
            "fin a = find(z)\n    if a != null {\n        fin f = [a] { twice(a) }\n        println(f())\n    }",
            "fin a = find(z)\n    if a == null { println(\"none\") }\n    println(twice(a))",
            "fin a = find(z)\n    if a != null || z > 0 { println(twice(a)) }",
            "val a = find(z)\n    if a != null { println(twice(a)) }",
        )
        for (body in bodies) {
            val source = "import std.io\nfunc find(i: Int): Int? = if i == 0 then 5 else null\n" +
                "func twice(v: Int): Int = v * 2\nfunc main() {\n    var z = 0\n    z = z + 0\n    $body\n}"
            val result = Compiler().compile(source, release = false)
            assertIs<CompilationResult.Failure>(result, "must stay optional:\n$body")
            assertTrue(
                result.errors.any { "arg 1 of 'twice': expected Int, got Int?" in it },
                "expected the optional to be refused, got: ${result.errors}",
            )
        }
    }
}
