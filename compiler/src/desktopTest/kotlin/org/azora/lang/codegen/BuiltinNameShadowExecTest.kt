/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A program's own function is called even when a compiler builtin has its name.
 *
 * Builtins were recognised by name alone. The interpreter replaced a program's
 * `contains(xs, x)` with the string builtin and crashed casting the array to a
 * `String`; LLVM and WebAssembly defined the program's `isDigit` and then the
 * runtime's `isDigit` under the same symbol, so the module did not link; the
 * type checker typed a program's `toString` as the builtin's `String`; and a
 * program's `println(Int)` had its argument rendered to text first.
 *
 * Each function below recurses, so neither the optimizer nor compile-time
 * evaluation can fold the call away before the backend sees it.
 */
class BuiltinNameShadowExecTest {
    private val functions = """
        func contains(xs: Array<Int>&, x: Int): Bool {
            for v in xs {
                if v == x then return true
            }
            return false
        }

        func isDigit(n: Int): Bool {
            if n > 1000 then return true
            if n < 0 then return false
            return isDigit(n * 2)
        }

        func toString(n: Int): Int {
            if n > 100 then return n
            return toString(n * 3)
        }
    """.trimIndent()

    private val program = """
        |import std.io
        |
        |$functions
        |
        |func main() {
        |    fin xs: Array<Int> = [1, 2, 3]
        |    println(contains(xs, 2))
        |    println(contains(xs, 7))
        |    println(isDigit(5))
        |    println(toString(2) + 1)
        |}
    """.trimMargin()

    private val expected = "true\nfalse\ntrue\n163"

    /** The string builtins of the same names still work beside them. */
    private val withStringBuiltins = """
        |import std.io
        |
        |$functions
        |
        |func main() {
        |    fin xs: Array<Int> = [4, 5]
        |    println(contains(xs, 5))
        |    fin word = "a1b"
        |    println(word.contains("1b"))
        |    println(word.startsWith("a"))
        |    println(toString(50))
        |}
    """.trimMargin()

    /** A program `println` that takes an `Int` gets the `Int`, not its text. */
    private val ownPrintln = """
        import std.io::print

        func println(n: Int): Int {
            if n > 50 then return n
            return println(n + n)
        }

        func main() {
            fin doubled = println(3) + 1
            print("${'$'}{doubled}\n")
        }
    """.trimIndent()

    @Test fun theInterpreterCallsTheProgramsOwnFunctions() {
        for (release in listOf(false, true)) {
            val result = Compiler().compile(program, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            val ir = if (release) result.optimizedIr ?: result.ir else result.ir
            assertEquals(expected, IrInterpreter().interpret(ir).trim(), "release=$release")
        }
    }

    @Test fun theStringBuiltinsStillWorkBesideThem() {
        val result = Compiler().compile(withStringBuiltins, release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("true\ntrue\ntrue\n150", IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun llvmLinksTheProgramsFunctionsBesideTheRuntimes() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized).trim(), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyDefinesEachFunctionOnce() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program).trimEnd())
    }

    /**
     * The program's `isDigit` and the library's own `isDigit` bridge, which
     * `std.char::charIsDigit` calls, are two declarations: the bridge takes its
     * module identity, and each call reaches its own. Both had kept the name
     * `isDigit`, and compilation stopped at "Function 'isDigit' already defined".
     */
    @Test fun aLibraryBridgeAndTheProgramsFunctionOfOneNameAreTwo() {
        val source = """
            import std.io
            import std.char::{charIsDigit}
            func isDigit(n: Int): Bool = n >= 0 && n < 10
            func main() {
                println(isDigit(5))
                println(isDigit(12))
                println(charIsDigit('7'))
                println(charIsDigit('x'))
            }
        """.trimIndent()
        val expected = "true\nfalse\ntrue\nfalse"
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter")
        if (LlvmExec.available) {
            for (optimized in listOf(false, true)) assertEquals(expected, LlvmExec.run(source, optimized).trim(), "LLVM optimized=$optimized")
        }
        if (WasmExec.available) assertEquals(expected, WasmExec.run(source).trimEnd(), "WebAssembly")
    }

    @Test fun aProgramsPrintlnIsAnOrdinaryFunction() {
        val result = Compiler().compile(ownPrintln, release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("97", IrInterpreter().interpret(result.ir).trim())
        if (LlvmExec.available) assertEquals("97", LlvmExec.run(ownPrintln, optimized = false).trim())
    }
}
