/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Concrete and erased code share one layout for tuples and nullables, so a
 * value built by one is read correctly by the other (GENERICS_DIP §21.3: code
 * is shared only where the substituted types have a uniform ABI).
 *
 * - A tuple's components sit in erased eight-byte slots on every backend, so
 *   an `(Int, Int)` held in a variable or an array reads back through a
 *   generic `(K, V)` (`Int` is 32-bit, and a packed `(Int, Int)` had put its
 *   second component at offset 4 where `(K, V)` reads 8).
 * - A nullable whose value can be all-zero bits (a number, `Bool`, `Char`,
 *   an erased `V?`) carries a presence flag, so `0`, `false` and `0.0` are not
 *   null. A map holding `0` had answered `null` from `get`.
 * - A template renders a nullable as what it holds, or as `null`.
 *
 * Every value is computed at run time (`zero()`), so the backends, not
 * compile-time evaluation, produce the output.
 */
class ErasedLayoutExecTest {
    private fun everywhere(expected: String, source: String) {
        val program = source.trimIndent()
        val result = Compiler().compile(program)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter")
        if (LlvmExec.available) {
            assertEquals(expected, LlvmExec.run(program), "LLVM")
            assertEquals(expected, LlvmExec.run(program, optimized = true), "LLVM optimized")
        }
        if (WasmExec.available) assertEquals(expected, WasmExec.run(program), "WebAssembly")
    }

    /** A zero nothing can fold away: a loop sums 0..3 and takes 6 back off. */
    private val zero = """
        func zero(): Int {
            var n = 0
            for i in 0..3 { n = n + i }
            return n - 6
        }
    """.trimIndent()

    @Test fun aTupleKeptInAVariableReadsThroughErasedComponents() = everywhere(
        "20\n2\n40\n-1\n3\n-1\n2.5\nfive\n-5\n9",
        """
        import std.io
        $zero
        func<K, V> second(p: (K, V)): V = p.1
        func<K, V> first(p: (K, V)): K = p.0
        func main() {
            fin z = zero()
            fin q = (z + 2, z + 20)
            println(second(q))
            println(first(q))
            fin pairs = [(z + 3, z + 30), (z + 4, z + 40)]
            println(second(pairs[1]))
            fin negative = (z - 1, z + 3)
            println(first(negative))
            println(second(negative))
            fin nested = ((z - 1, z), (z, 2.5))
            println(first(first(nested)))
            println(second(second(nested)))
            println(second((z, "five")))
            var ps = [(z + 1, z - 5)]
            println(second(ps[0]))
            ps[0] = (z + 9, z)
            println(first(ps[0]))
        }
        """,
    )

    @Test fun aTupleBuiltByGenericCodeReadsAtItsConcreteTypes() = everywhere(
        "-1\n2.5\ntrue\n7\nseven\n1.25",
        """
        import std.io
        $zero
        func<A, B> pair(a: A, b: B): (A, B) = (a, b)
        func<A, B> swap(p: (A, B)): (B, A) = (p.1, p.0)
        func main() {
            fin z = zero()
            fin d: Double = 2.5
            fin made = pair(z - 1, d)
            println(made.0)
            println(made.1)
            fin flags = pair(z == 0, z)
            println(flags.0)
            fin swapped = swap(("seven", z + 7))
            println(swapped.0)
            println(swapped.1)
            fin f: Float = 1.25
            fin (x, y) = pair(z, f)
            println(y + (x as Float))
        }
        """,
    )

    @Test fun aTupleOfMixedWidthsKeepsEachComponent() = everywhere(
        "5\n2.5\ntext\ntrue\n-1\n1.25",
        """
        import std.io
        $zero
        func main() {
            fin z = zero()
            fin b: Byte = 5
            fin d: Double = 2.5
            fin f: Float = 1.25
            fin mixed = (b, d, "text", z == 0, z - 1, f)
            println(mixed.0)
            println(mixed.1)
            println(mixed.2)
            println(mixed.3)
            println(mixed.4)
            println(mixed.5)
        }
        """,
    )

    @Test fun aMapHoldingZeroAnswersItsValueNotNull() = everywhere(
        "1\n0\nnull\nfalse\ntrue\n0\n7\n0.0\nfalse\nnull",
        """
        import std.io
        import std.container.map::{LinkedHashMap, HashMap}
        $zero
        func main() {
            fin z = zero()
            var m = LinkedHashMap<String, Int>()
            m.put("a", z + 1)
            m.put("b", z)
            println(m.get("a"))
            println(m.get("b"))
            println(m.get("c"))
            println(m.get("b") == null)
            println(m.get("c") == null)
            println(m.get("b") ?? 7)
            println(m.get("c") ?? 7)
            var ds = HashMap<String, Double>()
            ds.put("zero", z as Double)
            println(ds.get("zero"))
            var bs = LinkedHashMap<String, Bool>()
            bs.put("no", z == 1)
            println(bs.get("no"))
            println(bs.get("missing"))
        }
        """,
    )

    @Test fun anErasedKeyOfZeroIsAKey() = everywhere(
        "0\n0\nnull",
        """
        import std.io
        import std.container.map::{TreeMap}
        $zero
        func main() {
            fin z = zero()
            var t = TreeMap<Int, String>()
            t.put(z + 3, "three")
            t.put(z, "zero")
            println(t.firstKey())
            fin previous = t.put(z, "again")
            println(if previous == null then 1 else 0)
            var empty = TreeMap<Int, String>()
            println(empty.firstKey())
        }
        """,
    )

    @Test fun aTemplateRendersANullableAsItsValueOrNull() = everywhere(
        "0 1 null\n0 null\nfalse null\nnull hi",
        """
        import std.io
        import std.container.map::{LinkedHashMap}
        $zero
        func label(on: Bool): String? = if on then "hi" else null
        func main() {
            fin z = zero()
            var m = LinkedHashMap<String, Int>()
            m.put("a", z + 1)
            m.put("b", z)
            println("${'$'}{m.get("b")} ${'$'}{m.get("a")} ${'$'}{m.get("c")}")
            var a: Int? = z
            var c: Int? = null
            println("${'$'}{a} ${'$'}{c}")
            var e: Bool? = z == 1
            var f: Bool? = null
            println("${'$'}{e} ${'$'}{f}")
            println("${'$'}{label(false)} ${'$'}{label(true)}")
        }
        """,
    )

    @Test fun aNullableScalarKeepsZeroApartFromNull() = everywhere(
        "0\nfalse\n0\nnull\n0.0\nfalse\nnull\n1\n-1\n5\ntrue\nfalse\ntrue\nfalse\n7\n0",
        """
        import std.io
        $zero
        func pick(b: Bool, v: Int): Int? = if b then v else null
        func bump(x: Int?): Int {
            if x != null {
                return x + 1
            }
            return -1
        }
        func same(x: Int?, y: Int?): Bool = x == y
        func main() {
            fin z = zero()
            var a: Int? = z
            println(a)
            println(a == null)
            println(pick(true, z))
            println(pick(false, z))
            var d: Double? = z as Double
            println(d)
            var e: Bool? = z == 1
            println(e)
            e = null
            println(e)
            println(bump(z))
            println(bump(null))
            var w: Int? = z
            if w != null { w = w + 5 }
            println(w)
            println(same(z, z))
            println(same(z, null))
            println(same(null, null))
            println(same(z, z + 1))
            println(pick(false, z) ?? 7)
            println(pick(true, z) ?? 7)
        }
        """,
    )

    @Test fun aGenericFieldOfNullableTypeHoldsZero() = everywhere(
        "null\n0\nnull",
        """
        import std.io
        $zero
        pack Cell<T> { var v: T? }
        func<T> fill(c: Cell<T>!, x: T) { c.v = x }
        func<T> clear(c: Cell<T>!) { c.v = null }
        func main() {
            var c = Cell<Int>(v: null)
            println(c.v)
            fill(c, zero())
            println(c.v)
            clear(c)
            println(c.v)
        }
        """,
    )
}
