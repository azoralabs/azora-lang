package org.azora.lang.semantic

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.ir.IrProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RANGE_DIP §3-§5: a range is a value of std's `Range<T>`, formed by every
 * ordered type. The programs that run are listed in [programs] so
 * `RangeExecTest` runs them on LLVM and WebAssembly too.
 */
class RangeValueTest {
    private fun output(source: String): String {
        val result = Compiler().compile(source.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun rejected(source: String): List<String> {
        val result = Compiler().compile(source.trimIndent())
        return assertIs<CompilationResult.Failure>(result, "the program compiled").errors
    }

    private fun assertRejected(errors: List<String>, expected: String) =
        assertTrue(errors.any { expected in it }, "expected \"$expected\" in $errors")

    private fun assertRuns(name: String) {
        val (source, expected) = programs.getValue(name)
        assertEquals(expected, output(source), source)
    }

    // ── Task 1: an order is a partial order ───────────────────────

    /** The bound holds for every `Order` type; comparing through it is Task 3's witness (WitnessTest). */
    @Test fun everyOrderTypeIsAPartialOrder() {
        val (source, _) = programs.getValue("everyOrderTypeIsAPartialOrder")
        val result = Compiler().compile(source.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
    }

    // ── Task 4: the type ──────────────────────────────────────────

    @Test fun aRangeHoldsItsLowerBoundAndMaybeItsUpper() = assertRuns("aRangeHoldsItsLowerBoundAndMaybeItsUpper")

    @Test fun emptyAndNaN() = assertRuns("emptyAndNaN")

    @Test fun reversedFlipsTheDirection() = assertRuns("reversedFlipsTheDirection")

    @Test fun rangesCompareByTheirBounds() = assertRuns("rangesCompareByTheirBounds")

    @Test fun sizeNeedsAStepType() = assertRejected(
        rejected("""
            import std.io
            func main() {
                fin r = Range<String>("a", "z")
                println(r.size)
            }
        """),
        "'size' does not satisfy its 'where' clause here: 'T is Step': String does not implement Step",
    )

    // ── Task 5: literals are values ───────────────────────────────

    @Test fun aRangeIsAValue() = assertRuns("aRangeIsAValue")

    @Test fun everyOrderedTypeFormsARange() = assertRuns("everyOrderedTypeFormsARange")

    @Test fun literalsAdoptTheOtherBound() = assertRuns("literalsAdoptTheOtherBound")

    @Test fun aDescendingRangeIsAValueToo() = assertRuns("aDescendingRangeIsAValueToo")

    @Test fun boundsMustHaveOneType() = assertRejected(
        rejected("""
            import std.io
            func main() {
                fin a: Int = 1
                fin b: Long = 2
                fin r = a..b
            }
        """),
        "range bounds must have one type, got Int and Long; convert one, as in 'a as Long'",
    )

    @Test fun aRangeNeedsAnOrder() = assertRejected(
        rejected("""
            import std.io
            pack Vec2 { var x: Int var y: Int }
            func main() {
                fin r = Vec2(0, 0)..Vec2(1, 1)
            }
        """),
        "'Vec2' has no order, so 'a..b' cannot be a range of it; a range needs 'PartialOrder'",
    )

    @Test fun genericCodePassesTheBoundOn() {
        assertRejected(
            rejected("""
                import std.io
                func<U> make(a: U, b: U): Range<U> = a..b
                func main() {}
            """),
            "add 'where U: PartialOrder'",
        )
        assertRuns("genericCodeFormsARangeWithTheBound")
    }

    @Test fun compileTimeRangesAreUnchanged() = assertRuns("compileTimeRangesAreUnchanged")

    // ── Task 6: std's types step ──────────────────────────────────

    @Test fun stepsOfTheStdTypes() = assertRuns("stepsOfTheStdTypes")

    @Test fun sizeCountsTheWalk() = assertRuns("sizeCountsTheWalk")

    @Test fun aSizePastIntFails() {
        val failure = kotlin.runCatching {
            output("""
                import std.io
                func main() {
                    var big: Long = 0
                    for i in 1..4 { big += 1000000000 }
                    println(Range<Long>(0, big).size)
                }
            """)
        }.exceptionOrNull()
        assertTrue(failure != null && "a range of more than 2147483647 rows has no Int size" in failure.toString(), failure.toString())
    }

    @Test fun theWidestFloatIsNotWalked() = assertRejected(
        rejected("""
            import std.io
            func main() {
                fin a: Quad = 0.0
                fin b: Quad = 3.0
                println(Range<Quad>(a, b).size)
            }
        """),
        "'T is Step': Quad does not implement Step",
    )

    /** `in` binds looser than arithmetic and ranges, tighter than comparison and logic. */
    @Test fun membershipBindsLooserThanArithmetic() = assertRuns("membershipBindsLooserThanArithmetic")

    // ── Task 9: no operator declares a range ─────────────────────

    /** RANGE_DIP §4: every ordered type forms ranges, so no type declares how. */
    @Test fun rangeOperatorsAreNotDeclared() {
        val guidance = "is not declared: every ordered type forms ranges; to walk them, implement 'Step' (RANGE_DIP §5)"
        assertRejected(
            rejected("""
                pack P { var n: Int }
                bridge oper.. P&.(rhs: P&) by 1
                func main() {}
            """),
            "'oper..' $guidance",
        )
        assertRejected(
            rejected("""
                pack P { var n: Int }
                impl P {
                    oper>.. &.(rhs: P&) { }
                }
                func main() {}
            """),
            "'oper>..' $guidance",
        )
        assertRejected(
            rejected("""
                pack P { var n: Int }
                oper..< P&.(rhs: P&) { }
                func main() {}
            """),
            "'oper..<' $guidance",
        )
        assertRejected(
            rejected("""
                import std.traits
                spec Spans {
                    oper.. &.(rhs: Self&)
                }
                func main() {}
            """),
            "'oper..' $guidance",
        )
        assertRejected(
            rejected("""
                pack P { var n: Int }
                oper+ P&.(rhs: P&): P by 2 {
                    return P(self.n + rhs.n)
                }
                func main() {}
            """),
            "'by' does not follow an operator declaration",
        )
        assertRejected(
            rejected("""
                pack P { var n: Int }
                impl P {
                    oper- &.(rhs: P&): P by 2 = P(self.n - rhs.n)
                }
                func main() {}
            """),
            "'by' does not follow an operator declaration",
        )
    }

    companion object {
        /** Each runnable program, by test name, with what it prints. */
        val programs: Map<String, Pair<String, String>> = linkedMapOf(
            "membershipBindsLooserThanArithmetic" to ("""
                import std.io
                func main() {
                    fin x = 2
                    fin r = 0..<5
                    println(x + 3 in r)
                    println(x + 2 in r)
                    println(x * 2 in 0..4)
                    println(x in 0..4 && x + 10 in r)
                    println(x - 3 in -1..1 == true)
                    println(!(x in r))
                    println(x + 1 in x..x + 1)
                }
            """ to "false\ntrue\ntrue\nfalse\ntrue\nfalse\ntrue"),
            "everyOrderTypeIsAPartialOrder" to ("""
                import std.io
                import std.traits
                pack P { var v: Int }
                derive Order for P
                func<T: PartialOrder> lesser(a: T, b: T): T = if a < b then a else b
                func main() {
                    var one = 0
                    for i in 1..1 { one += i }
                    print(lesser(one, one + 1))
                    print(" ")
                    fin three: Long = 3
                    print(lesser(three, Long(4)))
                    print(" ")
                    print(lesser('a', 'b'))
                    print(" ")
                    print(lesser("b", "c"))
                    print(" ")
                    print(lesser(P(one), P(2)).v)
                    print(" ")
                    print(lesser(1.5, 2.5))
                }
            """ to "1 3 a b 1 1.5"),
            "aRangeHoldsItsLowerBoundAndMaybeItsUpper" to ("""
                import std.io
                func main() {
                    var ten = 0
                    for i in 1..10 { ten += 1 }
                    fin r = Range<Int>(0, ten, false)
                    println("${'$'}{r.contains(0)} ${'$'}{r.contains(ten)} ${'$'}{r.contains(-1)} ${'$'}{r.contains(9)}")
                    println("${'$'}{r.start} ${'$'}{r.end} ${'$'}{r.isEmpty}")
                    fin closed = Range<Int>(0, ten)
                    println(closed.contains(ten))
                }
            """ to "true false false true\n0 10 false\ntrue"),
            "emptyAndNaN" to ("""
                import std.io
                func main() {
                    var three = 0
                    for i in 1..3 { three += 1 }
                    println(Range<Int>(three + 2, 0).isEmpty)
                    println(Range<Int>(three, three, false).isEmpty)
                    println(Range<Int>(three, three).isEmpty)
                    var zero: Double = 0.0
                    for i in 1..three { zero = zero + 0.0 }
                    fin nan = zero / zero
                    println(Range<Double>(0.0, nan).isEmpty)
                    println(Range<Double>(nan, 1.0).contains(0.5))
                    println(Range<Double>(0.0, 1.0).contains(nan))
                    println(Range<Double>(0.0, 1.0).contains(0.5))
                }
            """ to "true\ntrue\nfalse\ntrue\nfalse\nfalse\ntrue"),
            "reversedFlipsTheDirection" to ("""
                import std.io
                func main() {
                    var five = 0
                    for i in 1..5 { five += 1 }
                    fin r = Range<Int>(0, five, false)
                    fin back = r.reversed
                    println("${'$'}{back.start} ${'$'}{back.end} ${'$'}{back.descending} ${'$'}{back.upperIncluded}")
                    fin again = back.reversed
                    println("${'$'}{again.start} ${'$'}{again.end} ${'$'}{again.descending}")
                    println(back.contains(five))
                    println(back.contains(0))
                }
            """ to "5 0 true false\n0 5 false\nfalse\ntrue"),
            "rangesCompareByTheirBounds" to ("""
                import std.io
                func main() {
                    var five = 0
                    for i in 1..5 { five += 1 }
                    println(Range<Int>(0, five) == Range<Int>(0, 5))
                    println(Range<Int>(0, five) == Range<Int>(0, five, false))
                    println(Range<Int>(0, five) != Range<Int>(0, five).reversed)
                    var zero: Double = 0.0
                    for i in 1..five { zero = zero + 0.0 }
                    fin nan = zero / zero
                    println(Range<Double>(nan, 1.0) == Range<Double>(nan, 1.0))
                    println(Range<String>("a", "m") == Range<String>("a", "m"))
                }
            """ to "true\nfalse\ntrue\nfalse\ntrue"),
            "aRangeIsAValue" to ("""
                import std.io
                pack Holder { var r: Range<Int> }
                func inside(r: Range<Int>, x: Int): Bool = x in r
                func digits(): Range<Int> = 0..9
                func main() {
                    var ten = 0
                    for i in 1..10 { ten += 1 }
                    fin r = 0..<ten
                    println(inside(r, 3))
                    println(inside(r, ten))
                    println(Holder(0..<3).r.contains(2))
                    println("${'$'}{digits()}")
                    println("${'$'}r")
                }
            """ to "true\nfalse\ntrue\n0..9\n0..<10"),
            "everyOrderedTypeFormsARange" to ("""
                import std.io
                import std.traits
                pack P { var v: Int }
                derive Order for P
                func main() {
                    fin three: Long = 3
                    println(three in Long(0)..Long(5))
                    println('q' in 'a'..'z')
                    println("${'$'}{UInt(0)..<UInt(9)}")
                    fin half: Double = 0.5
                    println(half in 0.0..1.0)
                    println("m" in "a".."z")
                    println(!(5 in 0..<5))
                    println(P(2) in P(1)..P(3))
                    println("${'$'}{'a'..'z'} ${'$'}{"a".."z"}")
                }
            """ to "true\ntrue\n0..<9\ntrue\ntrue\ntrue\ntrue\n'a'..'z' \"a\"..\"z\""),
            "literalsAdoptTheOtherBound" to ("""
                import std.io
                func main() {
                    fin n: Long = 7
                    fin r: Range<Long> = 0..n
                    println("${'$'}r")
                    println(r.contains(n))
                }
            """ to "0..7\ntrue"),
            "aDescendingRangeIsAValueToo" to ("""
                import std.io
                func bound(name: String, value: Int): Int {
                    print(name)
                    return value
                }
                func main() {
                    fin r = bound("a", 5)>..bound("b", 0)
                    println("")
                    println("${'$'}r ${'$'}{r.contains(5)} ${'$'}{r.contains(0)} ${'$'}{r.lower} ${'$'}{r.upper}")
                    println("${'$'}{(0..3).reversed}")
                }
            """ to "ab\n5>..0 false true 0 5\n(0..3).reversed"),
            "genericCodeFormsARangeWithTheBound" to ("""
                import std.io
                import std.traits
                func<U: PartialOrder> make(a: U, b: U): Range<U> = a..b
                func main() {
                    println(make(1, 3).contains(2))
                    println(make("a", "c").contains("d"))
                }
            """ to "true\nfalse"),
            "stepsOfTheStdTypes" to ("""
                import std.io
                func shown(v: Byte?): String = if v == null then "null" else "${'$'}{v.require()}"
                func shownU(v: UInt?): String = if v == null then "null" else "${'$'}{v.require()}"
                func shownL(v: Long?): String = if v == null then "null" else "${'$'}{v.require()}"
                func shownUL(v: ULong?): String = if v == null then "null" else "${'$'}{v.require()}"
                func shownD(v: Double?): String = if v == null then "null" else "${'$'}{v.require()}"
                func code(v: Char?): String = if v == null then "null" else "${'$'}{ord(v.require())}"
                func main() {
                    var one = 0
                    for i in 1..1 { one += 1 }
                    fin b: Byte = (121 + one) as Byte
                    println(shown(b.offset(1, 5)))
                    println(shown(b.offset(1, 6)))
                    println(shown(b.offset(-2, 3)))
                    fin u: UInt = (2 + one) as UInt
                    println(shownU(u.offset(1, -3)))
                    println(shownU(u.offset(1, -4)))
                    fin l: Long = Long::maxValue - ((one - 1) as Long)
                    println(shownL(l.offset(1, 1)))
                    println(shownL(l.offset(-1, 1)))
                    fin least: Long = Long::minValue + ((one - 1) as Long)
                    fin quarter: Long = 4611686018427387904
                    println(shownL(least.offset(quarter, 2)))
                    println(shownL(least.offset(quarter, 4)))
                    fin most: ULong = 18446744073709551615 - ((one - 1) as ULong)
                    println(shownUL(most.offset(1, 1)))
                    println(shownUL(most.offset(1, -1)))
                    fin d: Double = (one - 1) as Double
                    println(shownD(d.offset(0.1, 10)))
                    println(shownD(d.offset(0.25, -2)))
                    fin c = chr(55294 + one)
                    println(code(c.offset(1, 1)))
                    println(code(c.offset(1, 2)))
                    println(code(chr(57343 + one).offset(1, -1)))
                    println(code(chr(1114110 + one).offset(1, 1)))
                    println(code(chr(one - 1).offset(1, -1)))
                    println("${'$'}{b.unit} ${'$'}{d.unit} ${'$'}{c.unit}")
                }
            """ to "127\nnull\n116\n0\nnull\nnull\n9223372036854775806\n0\nnull\nnull\n18446744073709551614\n1.0\n-0.5\n57344\n57345\n55295\nnull\nnull\n1 1.0 1"),
            "sizeCountsTheWalk" to ("""
                import std.io
                func main() {
                    var ten = 0
                    for i in 1..10 { ten += 1 }
                    println(Range<Int>(0, ten, false).size)
                    println(Range<Int>(ten, 0, false, true).size)
                    println(Range<Int>(ten - 5, 0).size)
                    fin low: Double = 0.5
                    println(Range<Double>(low, 2.2).size)
                    println(Range<Char>('a', 'e').size)
                    println(Range<Char>(chr(55284 + ten), chr(57335 + ten)).size)
                    fin top: UByte = (245 + ten) as UByte
                    println(Range<UByte>((250 as UByte), top).size)
                }
            """ to "10\n10\n0\n2\n5\n4\n6"),
            "compileTimeRangesAreUnchanged" to ("""
                import std.io
                pack Small<N: Int> where N in 1..3 { var x: Int }
                func main() {
                    inline for i in 0..<3 { print(i) }
                    println("")
                    println(Small<2>(7).x)
                }
            """ to "012\n7"),
        )

        fun compile(source: String, optimized: Boolean): IrProgram {
            val result = Compiler().compile(source.trimIndent(), release = optimized)
            val success = assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            return if (optimized) success.optimizedIr else success.ir
        }
    }
}
