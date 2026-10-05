package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoundationWideNumericExecTest {
    private fun native(expected: String, source: String) {
        if (!LlvmExec.available) return
        for (release in listOf(false, true)) assertEquals(expected, LlvmExec.run(source.trimIndent(), release), "release=$release")
    }

    @Test fun fullUnsignedMagnitudeSurvivesDivisionOrderingAndOptimization() = native(
        "2147483647\n9223372036854775807\ntrue\ntrue\n0",
        """
        import std.io
        func divideInt(value: UInt): UInt { return value / UInt(2) }
        func divideLong(value: ULong): ULong { return value / ULong(2) }
        func main() {
            println(divideInt(UInt(4294967295)))
            println(divideLong(ULong(18446744073709551615)))
            println(ULong(18446744073709551615) > ULong(1))
            println(UInt(4294967295) > UInt(1))
            println(ULong(18446744073709551615) + ULong(1))
        }
        """,
    )

    @Test fun native128BitArithmeticUsesEveryBit() = native(
        "true\ntrue\ntrue\ntrue\ntrue",
        """
        import std.io
        func doubleWide(value: UCent): UCent { return value + value }
        func divideWide(value: UCent): UCent { return value / UCent(2) }
        func main() {
            println(doubleWide(UCent(18446744073709551616)) == UCent(36893488147419103232))
            println(divideWide(UCent(36893488147419103232)) == UCent(18446744073709551616))
            println(UCent(340282366920938463463374607431768211455) > UCent(1))
            println(UCent(340282366920938463463374607431768211455) + UCent(1) == UCent(0))
            println(Cent(170141183460469231731687303715884105727) > Cent(18446744073709551616))
        }
        """,
    )

    @Test fun signedDivisionOverflowWrapsAndRemainderIsZero() = native(
        "-2147483648\n0\n-9223372036854775808\n0",
        """
        import std.io
        func intDivide(value: Int, divisor: Int): Int { return value / divisor }
        func intRemainder(value: Int, divisor: Int): Int { return value % divisor }
        func longDivide(value: Long, divisor: Long): Long { return value / divisor }
        func longRemainder(value: Long, divisor: Long): Long { return value % divisor }
        func main() {
            println(intDivide(-2147483648, -1))
            println(intRemainder(-2147483648, -1))
            println(longDivide(Long(-9223372036854775808), Long(-1)))
            println(longRemainder(Long(-9223372036854775808), Long(-1)))
        }
        """,
    )

    @Test fun divisionByZeroAndInvalidShiftCountsFailDeterministically() {
        if (!LlvmExec.available) return
        for ((expression, message) in listOf(
            "value / count" to "division by zero",
            "value % count" to "division by zero",
            "value << (count + 32)" to "shift count",
            "value >> (count - 1)" to "shift count",
        )) {
            val source = """
                import std.io
                func compute(value: Int, count: Int): Int { return $expression }
                func main() { println(compute(42, 0)) }
            """.trimIndent()
            for (release in listOf(false, true)) assertTrue(LlvmExec.runExpectingAbort(source, release).contains(message))
        }
    }
}
