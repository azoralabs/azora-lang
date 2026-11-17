/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `Cent` and `UCent` are 128 bits on every backend: literals, arithmetic
 * (wrapping modulo 2^128, division truncating toward zero), comparison,
 * bitwise operations and shifts, conversions, and printing. The interpreter
 * held them in a `Long`, LLVM printed their low 64 bits, and WebAssembly
 * computed them in an `i64`. Expected values were computed independently,
 * with arbitrary-precision integers.
 */
class WideIntegerExecTest {
    private val program = """
        import std.io
        pack Ledger { var total: Cent }
        func doubled(x: Cent): Cent { return x * 2 }
        func main() {
            fin big: Cent = 4_611_686_018_427_387_904
            println(big * 4)
            println(big * big)
            fin uu: UCent = 18_446_744_073_709_551_616
            println(uu + uu)
            println(uu * uu - 1)
            fin neg: Cent = -7
            println(neg / 2)
            println(neg % 3)
            println(big > neg)
            println(Cent::maxValue)
            println(Cent::minValue)
            println(UCent::maxValue)
            fin top: Cent = Cent::maxValue
            println(top + 1)
            fin zero: UCent = 0
            println(zero - 1)
            println(top / 100_000_000_000_000_000_000)
            fin far: Cent = -123_456_789_012_345_678_901_234_567_890
            println(far % 1_000_000_007)
            println(big << 10)
            println((0 - big) >> 3)
            println((uu - 1) & 4_294_967_295)
            fin five: Cent = 5
            println(~five)
            println(big ^ 12345)
            fin ledger = Ledger(big * 3)
            println(0 - ledger.total)
            println("${'$'}{doubled(big)} and ${'$'}{big * 4 > big * 3}")
            fin small: Int = 42
            fin widened: Cent = small as Cent
            println(widened * big)
            println((big * 4 + 7) as Long)
            println(big == 4_611_686_018_427_387_904)
            println(-big)
        }
    """.trimIndent()

    private val expected = listOf(
        "18446744073709551616",
        "21267647932558653966460912964485513216",
        "36893488147419103232",
        "340282366920938463463374607431768211455",
        "-3",
        "-1",
        "true",
        "170141183460469231731687303715884105727",
        "-170141183460469231731687303715884105728",
        "340282366920938463463374607431768211455",
        "-170141183460469231731687303715884105728",
        "340282366920938463463374607431768211455",
        "1701411834604692317",
        "-197434842",
        "4722366482869645213696",
        "-576460752303423488",
        "4294967295",
        "-6",
        "4611686018427400249",
        "-13835058055282163712",
        "9223372036854775808 and true",
        "193690812773950291968",
        "7",
        "true",
        "-4611686018427387904",
    ).joinToString("\n")

    /**
     * Generic code, optionals and floats: an erased slot is eight bytes, so a
     * 128-bit value crosses it in a cell of its own (it was cut to its low 64
     * bits on LLVM), and a conversion to a float rounds once.
     */
    private val crossing = """
        import std.io
        pack Box<T> { var item: T }
        func<T> same(x: T): T { return x }
        func main() {
            fin seed: Cent = 4_611_686_018_427_387_904
            fin big = seed * 8
            fin b = Box<Cent>(big)
            println(b.item)
            println(same(big))
            fin r = big..(big + 5)
            println(r.end)
            println(big + 3 in r)
            fin maybe: Cent? = big
            println(maybe ?? seed)
            fin top: UCent = UCent::maxValue
            println((top as Double) > 3.4e38)
            println(((big as Double) as Cent) == big)
            fin d: Double = -1.5e30
            println(d as Cent)
            fin f: Float = 3.0e20
            println(f as UCent)
            fin edge: Cent = Cent::maxValue
            println((edge as Double) == (Cent::minValue as Double) * -1.0)
        }
    """.trimIndent()

    private val crossingExpected = listOf(
        "36893488147419103232",
        "36893488147419103232",
        "36893488147419103237",
        "true",
        "36893488147419103232",
        "true",
        "true",
        "-1499999999999999889089448902656",
        "300000006012263202816",
        "true",
    ).joinToString("\n")

    @Test fun aWideValueCrossesGenericCodeWhole() {
        val result = assertIs<CompilationResult.Success>(Compiler().compile(crossing, release = false))
        assertEquals(crossingExpected, IrInterpreter().interpret(result.ir).trim(), "interpreter")
        if (LlvmExec.available) for (optimized in listOf(false, true)) {
            assertEquals(crossingExpected, LlvmExec.run(crossing, optimized), "LLVM optimized=$optimized")
        }
        if (WasmExec.available) assertEquals(crossingExpected, WasmExec.run(crossing).trimEnd(), "WebAssembly")
    }

    @Test fun theInterpreterComputesIn128Bits() {
        for (release in listOf(false, true)) {
            val result = Compiler().compile(program, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "release=$release")
        }
    }

    @Test fun llvmComputesIn128Bits() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyComputesIn128Bits() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program).trimEnd())
    }
}
