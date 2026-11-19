/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Arithmetic on literals alone is read as a lone literal is: at the type of the
 * place it lands in, every literal in it included. `fin x: Cent =
 * 4_611_686_018_427_387_904 * 8` was refused (both literals were read as `Int`
 * first), a global written that way folded to `0`, a function returning
 * literal arithmetic folded into a bare literal an `Int` had to hold, a
 * `Long` global held an `Int` on the interpreter, `b ?? 0` with `b: Cent?` was
 * an `Int` on LLVM, and `(if a then 1.0 else 0.0) - …` landing in a `Double`
 * was a `Float`. Expected values were computed independently.
 */
class LiteralArithmeticExecTest {
    private val integers = """
        import std.io
        fin wide: Cent = 4_611_686_018_427_387_904 * 8
        fin big: Long = 3_000_000_000
        fin product: Long = big * 3
        func widened(x: Cent): Cent = x
        func half(): Long = 3_000_000_000 * 3 / 2
        func main() {
            fin x: Cent = 4_611_686_018_427_387_904 * 8
            println(x)
            println(wide)
            println(product)
            fin a: Long = 2_000_000_000 * 4
            println(a)
            fin c: Byte = 100 + 27
            println(c)
            fin mask: UCent = (1 << 100) - 1
            println(mask)
            fin neg: Cent = -(4_611_686_018_427_387_904 * 4)
            println(neg)
            println(widened(4_611_686_018_427_387_904 * 16))
            println(half())
            fin seed: Cent = 3
            println(seed * (4_611_686_018_427_387_904 * 8))
            println((4_611_686_018_427_387_904 * 8) + seed)
            var total: Cent = 0
            total += 4_611_686_018_427_387_904 * 2
            println(total)
            fin small: Byte = 1
            println(small - (1 + 1))
            println(x == 4_611_686_018_427_387_904 * 8)
            fin held: Cent? = seed * 4_611_686_018_427_387_904
            println(held ?? 0)
        }
    """.trimIndent()

    private val integersExpected = listOf(
        "36893488147419103232",
        "36893488147419103232",
        "9000000000",
        "8000000000",
        "127",
        "1267650600228229401496703205375",
        "-18446744073709551616",
        "73786976294838206464",
        "4500000000",
        "110680464442257309696",
        "36893488147419103235",
        "9223372036854775808",
        "-1",
        "true",
        "13835058055282163712",
    ).joinToString("\n")

    /**
     * Reals compare rather than print: the backends do not yet print a
     * `Double` the same way. Each comparison is false when the left side is
     * computed in a `Float` and widened.
     */
    private val reals = """
        import std.io
        func axis(a: Bool, b: Bool): Double = (if a then 1.0 else 0.0) - (if b then 1.0 else 0.0)
        func tenth(): Double = 0.1 * 3
        func pick(a: Bool): Double = if a then 0.1 else 0.2
        func main() {
            var on = false
            on = !on
            println(axis(on, false))
            println(axis(false, on))
            println(axis(on, on))
            fin exact: Double = 0.1
            println(tenth() == exact * 3.0)
            fin sum: Double = 0.1 + 0.2
            println(sum == exact + 0.2)
            fin x: Double = 2.0
            println(x * (0.1 + 0.2) == x * sum)
            println(pick(on) == exact)
            fin third: Double = 1 / 3.0
            println(third == 1.0 / 3.0)
        }
    """.trimIndent()

    private val realsExpected = listOf("1.0", "-1.0", "0.0", "true", "true", "true", "true", "true").joinToString("\n")

    private fun everywhere(program: String, expected: String) {
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

    @Test fun integerLiteralArithmeticTakesTheWidthItLandsIn() = everywhere(integers, integersExpected)

    @Test fun realLiteralArithmeticTakesTheTypeItLandsIn() = everywhere(reals, realsExpected)
}
