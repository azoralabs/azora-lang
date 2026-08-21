/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.azora.lang.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The software integer the interpreter computes wide values with.
 *
 * Checked against `Long` where a `Long` can hold the answer - the hardware is
 * the specification for those widths - and against known values where it
 * cannot.
 */
class WideIntTest {

    private fun w(value: Long, bits: Int = 128, signed: Boolean = true) = WideInt.of(value, bits, signed)

    @Test fun itAgreesWithTheHardwareAtSixtyFourBits() {
        val cases = listOf(
            0L to 1L, 1L to 1L, -1L to 1L, 7L to 3L, -7L to 3L, 7L to -3L, -7L to -3L,
            Long.MAX_VALUE to 3L, Long.MIN_VALUE to 7L, 123_456_789L to 1_000L,
        )
        for ((a, b) in cases) {
            val left = w(a, 64)
            val right = w(b, 64)
            assertEquals(a + b, (left + right).toLong(), "$a + $b")
            assertEquals(a - b, (left - right).toLong(), "$a - $b")
            assertEquals(a * b, (left * right).toLong(), "$a * $b")
            assertEquals(a / b, (left / right).toLong(), "$a / $b")
            assertEquals(a % b, (left % right).toLong(), "$a % $b")
            assertEquals(a.compareTo(b), left.compareTo(right).coerceIn(-1, 1), "$a <=> $b")
        }
    }

    @Test fun itWrapsAtItsWidthLikeTheHardwareDoes() {
        // `Int<8>` is a byte: 127 + 1 is -128, and 255 unsigned is -1 signed.
        assertEquals(-128L, (w(127, 8) + w(1, 8)).toLong())
        assertEquals(255L, WideInt.of(-1, 8, signed = false).toLong())
        assertEquals("255", WideInt.of(-1, 8, signed = false).toString())
        assertEquals("-1", w(-1, 8).toString())
    }

    @Test fun itHoldsTheLimitsOfEveryNamedWidth() {
        val centMax = WideInt.parse("170141183460469231731687303715884105727", 128, signed = true)
        assertEquals("170141183460469231731687303715884105727", centMax.toString())
        assertEquals("-170141183460469231731687303715884105728", (centMax + w(1)).toString())

        val ucentMax = WideInt.parse("340282366920938463463374607431768211455", 128, signed = false)
        assertEquals("340282366920938463463374607431768211455", ucentMax.toString())
        assertEquals("0", (ucentMax + WideInt.of(1, 128, signed = false)).toString())
    }

    @Test fun itMultipliesPastWhatALongHolds() {
        val twoToThe64 = WideInt.parse("18446744073709551616", 256, signed = true)
        assertEquals("340282366920938463463374607431768211456", (twoToThe64 * twoToThe64).toString())
    }

    @Test fun itDividesPastWhatALongHolds() {
        val big = WideInt.parse("340282366920938463463374607431768211456", 256, signed = true)
        val small = WideInt.parse("18446744073709551616", 256, signed = true)
        assertEquals("18446744073709551616", (big / small).toString())
        assertEquals("0", (big % small).toString())
        assertEquals("1", ((big + WideInt.of(1, 256, true)) % small).toString())
    }

    @Test fun itShiftsWithTheSignItsTypeStates() {
        assertEquals(-1L, w(-1, 64).shiftedRight(10).toLong(), "an arithmetic shift keeps the sign")
        assertEquals("18446744073709551615", WideInt.of(-1, 64, signed = false).shiftedRight(0).toString())
        assertEquals("1", WideInt.parse("18446744073709551616", 128, true).shiftedRight(64).toString())
        assertEquals("18446744073709551616", WideInt.of(1, 128, true).shiftedLeft(64).toString())
    }

    @Test fun itMasksTheBitsAboveItsWidth() {
        // `Int<7>` holds -64 … 63.
        assertEquals(-64L, (w(63, 7) + w(1, 7)).toLong())
        assertEquals(63L, w(63, 7).toLong())
        assertTrue(w(-1, 7).isNegative())
    }

    @Test fun itParsesEveryBaseTheLexerAccepts() {
        assertEquals("255", WideInt.parse("0xFF", 128, true).toString())
        assertEquals("10", WideInt.parse("0b1010", 128, true).toString())
        assertEquals("63", WideInt.parse("0o77", 128, true).toString())
        assertEquals("1000000", WideInt.parse("1_000_000", 128, true).toString())
        assertEquals("-42", WideInt.parse("-42", 128, true).toString())
    }

    @Test fun aWidthCanBeReadAtAnother() {
        assertEquals("-1", w(-1, 8).at(128, signed = true).toString())
        assertEquals("255", w(-1, 8).at(128, signed = false).at(8, signed = false).toString())
    }
}
