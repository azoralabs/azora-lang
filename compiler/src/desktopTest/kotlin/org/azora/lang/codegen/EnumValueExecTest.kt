/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * ENUMS_DIP §6 on every backend: a valued enum's `.value` - written in place,
 * through a binding, a parameter and a generated lookup - and `raw as? Key`,
 * for integer (including `Byte` and negative), floating-point, `Char`,
 * `String` and tuple values. A case still prints as its name and `when`
 * still matches it.
 */
class EnumValueExecTest {
    private val program = """
        import std.io
        enum Key: Int {
            Tab(9)
            W(87)
            Left(1001)
        }
        enum Small: Byte {
            Low(-128)
            High(127)
        }
        enum Pointer: Int {
            Mouse(0)
            Touch(1)
            None(-1)
        }
        enum Planet: (Int, String) {
            Mercury(1, "One")
            Venus(2, "Two")
        }
        enum Grade: Char {
            Top('A')
            Pass('C')
        }
        enum Measure: String {
            Meter("m")
            Second("s")
        }
        enum Scale: Double {
            Half(0.5)
            Whole(1)
        }
        func code(k: Key): Int = k.value
        func describe(p: Pointer): String = when p {
            .Mouse -> "mouse"
            .Touch -> "touch"
            .None -> "none"
        }
        func main() {
            println(Key.W.value)
            var k = Key.Tab
            println(code(k))
            k = .Left
            println(k.value + 1)
            println(Small.Low.value)
            fin s = Small.High
            println(s.value)
            println(Planet.Venus.value.1)
            fin (n, name) = Planet.Mercury.value
            println("${'$'}{n} ${'$'}{name}")
            fin pl = Planet.Venus
            println(pl.value.0)
            println(Grade.Pass.value)
            println(Measure.Second.value)
            println(Scale.Whole.value)
            fin raw = 1
            println(describe(raw as? Pointer ?? .Mouse))
            println(describe(7 as? Pointer ?? .Mouse))
            println(describe(-1 as? Pointer ?? .Mouse))
            fin found = (2, "Two") as? Planet
            println(found == Planet.Venus)
            println(((3, "Two") as? Planet) == null)
            println(("m" as? Measure) == Measure.Meter)
            println(k)
            println("${'$'}{Planet.Venus}")
        }
    """.trimIndent()

    private val expected = listOf(
        "87", "9", "1002", "-128", "127", "Two", "1 One", "2", "C", "s", "1.0",
        "touch", "mouse", "none", "true", "true", "true", "Key.Left", "Planet.Venus",
    ).joinToString("\n")

    @Test fun theInterpreterReadsAndFindsCaseValues() {
        for (release in listOf(false, true)) {
            val result = Compiler().compile(program, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "release=$release")
        }
    }

    @Test fun llvmReadsAndFindsCaseValues() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyReadsAndFindsCaseValues() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program).trimEnd())
    }
}
