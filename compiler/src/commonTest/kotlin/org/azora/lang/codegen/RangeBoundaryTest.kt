/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RangeBoundaryTest {
    companion object {
        internal val headerProgram = """
            bridge oper.. Int&.(rhs: Int&) by 1
            bridge oper>.. Int&.(rhs: Int&) by 1
            pack Calls { var order: Int }
            func upper(calls: Calls!): Int { calls.order = calls.order * 10 + 1
                return 5 }
            func lower(calls: Calls!): Int { calls.order = calls.order * 10 + 2
                return 0 }
            func stride(calls: Calls!): Int { calls.order = calls.order * 10 + 3
                return 2 }
            func main() {
                var calls = Calls(0)
                var digits = 0
                for i in upper(calls)>..lower(calls) by stride(calls) with ordinal {
                    assert calls.order == 123 panic "header evaluation count or order"
                    assert i == 4 - ordinal * 2 panic "ordinal or descending step"
                    if i == 2 then continue
                    digits = digits * 10 + i
                }
                assert digits == 40 panic "continue skipped advancement"
                var limit = 5
                var step = 2
                digits = 0
                for i in limit>..0 by step {
                    limit = 9
                    step = 1
                    digits = digits * 10 + i
                }
                assert digits == 420 panic "loop header was reevaluated"
                var total = 0
                outer: for i in 3>..0 {
                    for j in 2>..0 {
                        if j == 1 then continue:outer
                        total = total + 1
                    }
                }
                assert total == 0 panic "labeled continue escaped the wrong loop"
                digits = 0
                inline for i in 3>..0 { digits = digits * 10 + i }
                assert digits == 210 panic "compile-time descending expansion"
                inline for i in 0>..0 { assert false panic "empty compile-time range" }
            }
        """.trimIndent()

        internal fun invalidStepProgram(step: Int) = """
            bridge oper>.. Int&.(rhs: Int&) by 1
            func main() { for i in 0>..0 by $step {} }
        """.trimIndent()

        internal val program = """
            bridge oper.. Int&.(rhs: Int&) by 1
            bridge oper>.. Int&.(rhs: Int&) by 1
            pack Visits { var count: Int }
            func last(size: Int, target: Int, visits: Visits!): Int {
                return for i: Int in size>..0 {
                    assert i >= 0 && i < size panic "index outside array bounds"
                    visits.count = visits.count + 1
                    if i <= target then break i
                } else -1
            }
            func main() {
                var visits = Visits(0)
                assert last(0, 0, visits) == -1 panic "empty search"
                assert visits.count == 0 panic "empty range visited an index"
                assert last(1, 0, visits) == 0 panic "singleton search"
                assert last(5, 3, visits) == 3 panic "last matching index"
                assert visits.count == 3 panic "search order or short circuit"
                assert last(5, -1, visits) == -1 panic "no match"
                assert visits.count == 8 panic "search skipped an index"
                var edgeCount = 0
                for i in -2147483647>..-2147483648 {
                    assert i == -2147483648 panic "minimum row"
                    edgeCount = edgeCount + 1
                }
                for i in -2147483648>..-2147483648 { assert false panic "minimum empty bounds" }
                for i in 2147483647..2147483647 { edgeCount = edgeCount + 1 }
                assert edgeCount == 2 panic "integer edge termination"
                var digits = 0
                for i in 6>..1 by 2 { digits = digits * 10 + i }
                assert digits == 531 panic "inclusive reverse step"
                digits = 0
                for i in 4>..1 by 2 { digits = digits * 10 + i }
                assert digits == 31 panic "exclusive reverse step"
                digits = 0
                for i in 4>..3 { digits = digits + 1 }
                assert digits == 1 panic "inclusive equal bounds"
                for i in 3>..3 { assert false panic "exclusive equal bounds" }
                for i in 3>..4 { assert false panic "inverted inclusive bounds" }
                for i in -3>..0 { assert false panic "inverted exclusive bounds" }
                for i: Int in 0..<3 then digits = digits + i
                assert digits == 4 panic "typed forward loop"
            }
        """.trimIndent()
    }

    @Test fun descendingSearchAndRangeBoundariesExecute() {
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(AssertionSemanticsTest.lower(program, optimized)))
        }
    }

    @Test fun headersExecuteOnceAndContinueRetainsItsTarget() {
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(AssertionSemanticsTest.lower(headerProgram, optimized)))
        }
    }

    @Test fun invalidStepsFailEvenOnEmptyRanges() {
        for (step in listOf(0, -1)) {
            for (optimized in listOf(false, true)) {
                val ir = AssertionSemanticsTest.lower(invalidStepProgram(step), optimized)
                val error = assertFailsWith<IllegalStateException> { IrInterpreter().interpret(ir) }
                assertTrue("range step must be positive" in error.message.orEmpty())
            }
        }
    }
}
