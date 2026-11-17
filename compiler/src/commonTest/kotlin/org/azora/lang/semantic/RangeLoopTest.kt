package org.azora.lang.semantic

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RANGE_DIP §6: `for` walks a range of every `Step` type, from its first
 * row by the step, ending at the bound or the type's limit and never
 * wrapping. Runnable programs are in [programs], which `RangeExecTest` also
 * runs on LLVM and WebAssembly.
 */
class RangeLoopTest {
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

    private fun assertFailsWith(source: String, message: String) {
        val failure = kotlin.runCatching { output(source) }.exceptionOrNull()
        assertTrue(failure != null && message in failure.toString(), "expected a failure with \"$message\", got $failure")
    }

    @Test fun everyIntegerWidthWalks() = assertRuns("everyIntegerWidthWalks")

    /** `Cent` and `UCent` step too, ending at their limits as every width does. */
    @Test fun theWidestIntegersWalk() = assertRuns("theWidestIntegersWalk")

    @Test fun charsSkipTheSurrogates() = assertRuns("charsSkipTheSurrogates")

    @Test fun floatsDoNotDrift() = assertRuns("floatsDoNotDrift")

    @Test fun todaysIntLoopsAreUnchanged() = assertRuns("todaysIntLoopsAreUnchanged")

    @Test fun aNonIntWalkContinuesAndCounts() = assertRuns("aNonIntWalkContinuesAndCounts")

    @Test fun stepsAreCheckedEvenWhenEmpty() {
        assertFailsWith(
            """
                import std.io
                func main() {
                    var zero = 0
                    for i in 1..1 { zero = 0 }
                    for i in 5..0 by zero { println(i) }
                }
            """,
            "the step 'by zero' does not move the range forward",
        )
        assertFailsWith(
            """
                import std.io
                func main() {
                    fin low: Long = 0
                    for i in low..Long(9) by Long(-1) { println(i) }
                }
            """,
            "does not move the range forward",
        )
    }

    @Test fun aStepOfAnotherTypeIsRefused() = assertRejected(
        rejected("""
            import std.io
            func main() {
                for i in 0..10 by 0.5 { println(i) }
            }
        """),
        "'by 0.5' is a Float, but a 'Range<Int>' steps by Int",
    )

    @Test fun aTypeWithoutStepIsNotWalked() = assertRejected(
        rejected("""
            import std.io
            func main() {
                for s in "a".."c" { println(s) }
            }
        """),
        "cannot walk a 'Range<String>': 'String' does not implement 'Step'",
    )

    // ── Task 8: values, user types, generic code ──────────────────

    @Test fun aRangeValueWalksLikeItsLiteral() = assertRuns("aRangeValueWalksLikeItsLiteral")

    @Test fun aUserTypeSteps() = assertRuns("aUserTypeSteps")

    @Test fun genericCodeWalksARange() = assertRuns("genericCodeWalksARange")

    @Test fun aBrokenOffsetCannotLoopForever() = assertFailsWith(
        dayPrelude + """
            pack Stuck { var n: Int }
            derive Order for Stuck
            impl Step<Int> for Stuck {
                prop &.unit: Int = 1
                // Moves at first, then stalls at 3 inside a range running to 5.
                func &.offset(step: Int&, count: Long): Stuck? {
                    fin moved = self.n + step * (count as Int)
                    return Stuck(if moved > 3 then 3 else moved)
                }
            }
            func main() {
                for s in Stuck(1)..Stuck(5) { print(s.n) }
            }
        """,
        "'offset' of 'Stuck' did not move past",
    )

    @Test fun aBrokenOffsetFailsGenericCodeToo() = assertFailsWith(
        dayPrelude + """
            pack Stuck { var n: Int }
            derive Order for Stuck
            impl Step<Int> for Stuck {
                prop &.unit: Int = 1
                func &.offset(step: Int&, count: Long): Stuck? {
                    fin moved = self.n + step * (count as Int)
                    return Stuck(if moved > 3 then 3 else moved)
                }
            }
            func<T: Step> rows(r: Range<T>): Int {
                var n = 0
                for x in r { n += 1 }
                return n
            }
            func main() {
                println(rows(Stuck(1)..Stuck(5)))
            }
        """,
        "'offset' of 'Stuck' did not move past",
    )

    @Test fun genericCodeWalksOnlyAStepBound() = assertRejected(
        rejected("""
            import std.io
            import std.traits
            func<T: PartialOrder> rows(r: Range<T>): Int {
                var n = 0
                for x in r { n += 1 }
                return n
            }
            func main() {}
        """),
        "cannot walk a 'Range<T>': 'T' is not bounded by 'Step'; write 'where T: Step'",
    )

    @Test fun aTypeStepsOneWay() = assertRejected(
        rejected(dayPrelude + """
            impl Step<Long> for Day {
                prop &.unit: Long = 1
                func &.offset(step: Long&, count: Long): Day? = Day(self.n + ((step * count) as Int))
            }
            func main() {}
        """),
        "'Day' implements Step already; a type steps one way",
    )

    @Test fun genericCodeNamesTheStepItWalksBy() = assertRejected(
        rejected("""
            import std.io
            func<T: Step> skip(r: Range<T>, s: Int): Int {
                var n = 0
                for x in r by s { n += 1 }
                return n
            }
            func main() {}
        """),
        "where T: Step<",
    )

    companion object {
        /** A user type that steps by a whole number of days. */
        val dayPrelude = """
            import std.io
            import std.traits
            pack Day { var n: Int }
            derive Order for Day
            impl Step<Int> for Day {
                prop &.unit: Int = 1
                func &.offset(step: Int&, count: Long): Day? {
                    fin moved: Long = (self.n as Long) + (step as Long) * count
                    if moved < 0 || moved > 365 {
                        return null
                    }
                    return Day(moved as Int)
                }
            }
        """.trimIndent() + "\n"

        val programs: Map<String, Pair<String, String>> = linkedMapOf(
            "everyIntegerWidthWalks" to ("""
                import std.io
                func main() {
                    var ten = 0
                    for i in 1..10 { ten += 1 }
                    fin high: UByte = (245 + ten) as UByte
                    for b in (250 as UByte)..high { print("${'$'}b ") }
                    println("")
                    fin hi: UInt = (ten - 7) as UInt
                    for u in hi>..0 { print("${'$'}u ") }
                    println("")
                    for x: UInt in 0..2 { print("${'$'}x ") }
                    println("")
                    fin top: Long = 9223372036854775807 - ((ten - 10) as Long)
                    var rows = 0
                    for l in (top - 2)..top { rows += 1 }
                    println(rows)
                    for s: Short in -2..<1 { print("${'$'}s ") }
                    println("")
                    fin small: Byte = (117 + ten) as Byte
                    for b in (small - 2)..small by 1 { print("${'$'}b ") }
                    println("")
                }
            """ to "250 251 252 253 254 255 \n2 1 0 \n0 1 2 \n3\n-2 -1 0 \n125 126 127"),
            "theWidestIntegersWalk" to ("""
                import std.io
                func main() {
                    fin top: Cent = Cent::maxValue
                    for c in (top - 2)..top { print("${'$'}{c - top} ") }
                    println("")
                    fin seed: UCent = 18_446_744_073_709_551_615
                    for u in seed..(seed + 2) by 1 { print("${'$'}u ") }
                    println("")
                    fin zero: UCent = 0
                    for u in (zero + 3)>..zero { print("${'$'}u ") }
                    println("")
                    fin a: Cent = -3
                    fin b: Cent = 3
                    println(Range<Cent>(a, b).size)
                    fin far: UCent = UCent::maxValue
                    println(Range<UCent>(far - 4, far).size)
                    for c in a..b by 2 { print("${'$'}c ") }
                    println("")
                }
            """ to "-2 -1 0 \n18446744073709551615 18446744073709551616 18446744073709551617 \n2 1 0 \n7\n5\n-3 -1 1 3"),
            "charsSkipTheSurrogates" to ("""
                import std.io
                func main() {
                    var ten = 0
                    for i in 1..10 { ten += 1 }
                    for c in chr(55284 + ten)..chr(57335 + ten) { print("${'$'}{ord(c)} ") }
                    println("")
                    for c in 'a'..'e' by 2 { print(c) }
                    println("")
                    for c in 'e'>..'a' { print(c) }
                    println("")
                }
            """ to "55294 55295 57344 57345 \nace\ndcba"),
            "floatsDoNotDrift" to ("""
                import std.io
                func main() {
                    var rows = 0
                    var last: Double = -1.0
                    fin start: Double = 0.0
                    for x in start..1.0 by 0.1 {
                        rows += 1
                        last = x
                    }
                    println("${'$'}rows ${'$'}last")
                    for x in start..<1.0 by 0.25 { print("${'$'}x ") }
                    println("")
                    fin five: Double = 5.0
                    for x in five>..0.0 by 2.0 { print("${'$'}x ") }
                    println("")
                    var zero: Double = 0.0
                    for i in 1..1 { zero = zero + 0.0 }
                    fin nan = zero / zero
                    var none = 0
                    for x in start..nan { none += 1 }
                    for x in nan..five { none += 1 }
                    println(none)
                }
            """ to "11 1.0\n0.0 0.25 0.5 0.75 \n4.0 2.0 0.0 \n0"),
            "todaysIntLoopsAreUnchanged" to ("""
                import std.io
                func main() {
                    for i in 5>..0 by 2 { print("${'$'}i ") }
                    println("")
                    for i in 0..<6 by 2 with index { print("${'$'}i:${'$'}index ") }
                    println("")
                    outer: for i in 0..2 {
                        for j in 0..2 {
                            if j == 1 { continue }
                            if i == 2 { break:outer }
                            print("${'$'}i${'$'}j ")
                        }
                    }
                    println("")
                }
            """ to "4 2 0 \n0:0 2:1 4:2 \n00 02 10 12"),
            "aNonIntWalkContinuesAndCounts" to ("""
                import std.io
                func main() {
                    fin low: Long = 0
                    for i in low..<Long(6) with index {
                        if i == Long(2) { continue }
                        if i == Long(5) { break }
                        print("${'$'}i:${'$'}index ")
                    }
                    println("")
                    outer: for c in 'a'..'c' {
                        for d in low..Long(3) {
                            if d == Long(1) { continue:outer }
                            print("${'$'}c${'$'}d ")
                        }
                    }
                    println("")
                }
            """ to "0:0 1:1 3:3 4:4 \na0 b0 c0"),
            "aRangeValueWalksLikeItsLiteral" to ("""
                import std.io
                pack Holder { var r: Range<Char> }
                func main() {
                    var five = 0
                    for i in 1..5 { five += 1 }
                    fin r = 0..<five
                    for x in r { print(x) }
                    println("")
                    var pairs = 0
                    for x in r { for y in r { pairs += 1 } }
                    println(pairs)
                    for x in r.reversed { print(x) }
                    println("")
                    fin h = Holder('a'..'f')
                    for c in h.r by 2 { print(c) }
                    println("")
                    fin down = five>..0
                    for x in down by 2 { print(x) }
                    println("")
                    for x in r with index { if x == 3 { break } print("${'$'}x:${'$'}index ") }
                    println("")
                }
            """ to "01234\n25\n43210\nace\n420\n0:0 1:1 2:2"),
            "aUserTypeSteps" to (dayPrelude + """
                func main() {
                    for d in Day(1)..Day(7) by 3 { print("${'$'}{d.n} ") }
                    println("")
                    for d in Day(7)>..Day(1) { print("${'$'}{d.n} ") }
                    println("")
                    println((Day(1)..Day(5)).size)
                    for d in Day(363)..Day(400) { print("${'$'}{d.n} ") }
                    println("")
                    fin span = Day(10)..<Day(13)
                    for d in span { print("${'$'}{d.n} ") }
                    println("")
                }
            """ to "1 4 7 \n6 5 4 3 2 1 \n5\n363 364 365 \n10 11 12"),
            "genericCodeWalksARange" to (dayPrelude + """
                func<T: Step> rows(r: Range<T>): Int {
                    var n = 0
                    for x in r { n += 1 }
                    return n
                }
                func<T: Step<Int>> everyOther(r: Range<T>): Int {
                    var n = 0
                    for x in r by 2 { n += 1 }
                    return n
                }
                func<T: Step> between(a: T, b: T): Int {
                    var n = 0
                    for x in a..<b { n += 1 }
                    return n
                }
                func main() {
                    println(rows(0..<4))
                    fin start: Double = 0.0
                    println(rows(start..1.0))
                    println(rows('a'..'c'))
                    println(everyOther('a'..'e'))
                    fin low: Long = 3
                    println(between(low, Long(7)))
                    println(rows(Day(1)..Day(4)))
                    println(everyOther(Day(10)>..Day(1)))
                }
            """ to "4\n2\n3\n3\n4\n4\n5"),
        )
    }
}
