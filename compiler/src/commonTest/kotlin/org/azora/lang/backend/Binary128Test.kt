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
import kotlin.test.assertNull

/**
 * A `Quad` literal is the binary128 nearest what was written.
 *
 * The expected encodings are what `strtof128` answers for the same text -
 * a literal is not "close enough" to a value, it is that value.
 */
class Binary128Test {

    private fun hex(text: String): String {
        val bits = Binary128.encode(text) ?: return "null"
        fun word(value: Long) = value.toULong().toString(16).uppercase().padStart(16, '0')
        return word(bits.high) + word(bits.low)
    }

    // -- the values std/primitive.az states --------------------------------

    @Test fun theLargestFiniteQuad() {
        // (2 - 2^-112) × 2^16383: every exponent bit but the last, every
        // fraction bit set.
        assertEquals("7FFEFFFFFFFFFFFFFFFFFFFFFFFFFFFF", hex("1.18973149535723176508575932662800702E4932"))
    }

    @Test fun theSmallestNormalQuad() {
        // 2^-16382: the lowest normal exponent and a zero fraction.
        assertEquals("00010000000000000000000000000000", hex("3.36210314311209350626267781732175260E-4932"))
    }

    @Test fun aValuePastTheLargestIsInfinite() {
        assertEquals("7FFF0000000000000000000000000000", hex("1.2E4932"))
        assertEquals("FFFF0000000000000000000000000000", hex("-2E5000"))
    }

    // -- ordinary values ---------------------------------------------------

    @Test fun oneIsExact() {
        assertEquals("3FFF0000000000000000000000000000", hex("1."))
        assertEquals("BFFF0000000000000000000000000000", hex("-1.0"))
        assertEquals("40000000000000000000000000000000", hex("2."))
    }

    @Test fun zeroKeepsItsSign() {
        assertEquals("00000000000000000000000000000000", hex("0."))
        assertEquals("80000000000000000000000000000000", hex("-0.0"))
        assertEquals("00000000000000000000000000000000", hex("0.000E12"))
    }

    @Test fun aTenthGetsAllOneHundredThirteenBits() {
        // The point of reading the digits: through a `Double` this would be
        // 53 correct bits and then zeroes.
        assertEquals("3FFB999999999999999999999999999A", hex("0.1"))
    }

    @Test fun anIntegerTooBigForADoubleIsStillExact() {
        // 2^64 + 1 - a `Double` cannot hold it, binary128 can. The one is
        // `2^-64` of the significand, which is fraction bit 48.
        assertEquals("403F0000000000000001000000000000", hex("18446744073709551617."))
    }

    @Test fun tiesGoToEven() {
        // 2^-16494 is the smallest subnormal; half of it rounds to zero
        // rather than up, because zero is the even neighbour.
        val smallest = "00000000000000000000000000000001"
        assertEquals(smallest, hex("6.475175119438025110924438958227646552E-4966"))
        assertEquals("00000000000000000000000000000000", hex("3.2E-4966"))
        assertEquals(smallest, hex("4.9E-4966"))
    }

    @Test fun aValueBelowEverySubnormalIsZero() {
        assertEquals("00000000000000000000000000000000", hex("1E-5000"))
        assertEquals("80000000000000000000000000000000", hex("-1E-99999"))
    }

    // -- what this does not read -------------------------------------------

    @Test fun somethingThatIsNotADecimalFloatIsLeftToItsOwnReader() {
        assertNull(Binary128.encode("0x1p4"))
        assertNull(Binary128.encode("12f"))
        assertNull(Binary128.encode(""))
        assertNull(Binary128.encode("1.0E"))
    }

    @Test fun separatorsAndAMissingIntegerPartAreLiteralsToo() {
        assertEquals(hex("1000000."), hex("1_000_000."))
        assertEquals(hex("0.5"), hex(".5"))
    }
}
