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

/**
 * The binary128 value nearest a decimal literal, read from the literal's own
 * digits.
 *
 * A float literal reaches the backends as a 64-bit `Double`, which is every bit
 * a `Double` has and nowhere near a `Quad`'s: `1.18973149535723176508575932662800702E4932`
 * is already infinity by the time it is a `Double`, and `0.1` arrives with 53
 * correct bits where binary128 stores 113. So a `Quad` is converted from the
 * digits that were written rather than from the `Double` they were parsed into.
 *
 * The result is correctly rounded, ties to even - the same value `strtof128`
 * returns for the same text. Getting that right means dividing exactly, which
 * is why this works in [WideInt] rather than in anything that rounds: the
 * hard cases are decided by digits far below the ones that survive.
 */
object Binary128 {

    /** binary128: 113 significand bits, a 15-bit exponent, bias 16383. */
    private const val PRECISION = 113
    private const val FRACTION_BITS = PRECISION - 1
    private const val MAX_EXPONENT = 16383
    private const val MIN_EXPONENT = -16382
    private const val BIAS = 16383

    /** The exponent field of an infinity or a NaN, all ones. */
    private const val INFINITE_EXPONENT = 0x7fffL

    /**
     * The scale of the smallest subnormal, `2^-16494`.
     *
     * Below the smallest normal every value is a multiple of this, so the
     * quotient is taken at this scale instead of at the value's own.
     */
    private const val SUBNORMAL_SHIFT = FRACTION_BITS - MIN_EXPONENT

    /**
     * Digits kept from a literal, far past the 35 that 113 bits can tell apart.
     *
     * Everything beyond is folded into one trailing digit that keeps the value
     * on the side of the truncation it was on, which is all rounding needs and
     * all that stops a pasted-in thousand-digit constant from sizing the
     * arithmetic.
     */
    private const val KEPT_DIGITS = 1200

    /**
     * Decimal magnitudes outside binary128 by a wide margin.
     *
     * `10^4932` is the largest finite and `10^-4966` the smallest subnormal, so
     * anything past these is infinity or zero without arithmetic - and the
     * margin is what keeps the widths below bounded.
     */
    private const val CERTAIN_OVERFLOW = 4940
    private const val CERTAIN_UNDERFLOW = -5010

    /** The two 64-bit words of a binary128 value. */
    data class Bits(val high: Long, val low: Long)

    /**
     * [text] as binary128, or `null` when it is not a decimal float literal.
     *
     * A `null` says only that this is not the reader for that text - a hex
     * literal, or something with a suffix - and leaves the caller its own way
     * of reading it.
     */
    fun encode(text: String): Bits? {
        val decimal = parse(text) ?: return null
        val sign = if (decimal.negative) Long.MIN_VALUE else 0L
        if (decimal.digits.isEmpty()) return Bits(sign, 0L)

        val (digits, exponent) = decimal.truncated()
        val magnitude = digits.length + exponent
        if (magnitude > CERTAIN_OVERFLOW) return Bits(sign or (INFINITE_EXPONENT shl 48), 0L)
        if (magnitude < CERTAIN_UNDERFLOW) return Bits(sign, 0L)

        // The value is `digits × 10^exponent`, so it is a ratio of two exact
        // integers - one of which is a power of ten and the other the digits.
        val power = if (exponent >= 0) exponent else -exponent
        val width = widthFor(digits.length, power)
        val ten = WideInt.of(10, width, false)
        val one = WideInt.of(1, width, false)
        val scale = ten.raisedTo(power, one)
        val numerator = if (exponent >= 0) WideInt.parse(digits, width, false) * scale
        else WideInt.parse(digits, width, false)
        val denominator = if (exponent >= 0) one else scale

        return round(numerator, denominator, one, sign)
    }

    /**
     * The quotient's top [PRECISION] bits, rounded to nearest and ties to even.
     *
     * The quotient is taken at a scale that puts the leading bit exactly where
     * the significand's is. That scale is known within one bit from the two
     * bit lengths, so the first division either lands or is one off, and being
     * one off is corrected by dividing again at the neighbouring scale.
     */
    private fun round(numerator: WideInt, denominator: WideInt, one: WideInt, sign: Long): Bits {
        var shift = FRACTION_BITS - (numerator.bitLength() - denominator.bitLength())
        val subnormal = shift > SUBNORMAL_SHIFT
        if (subnormal) shift = SUBNORMAL_SHIFT

        var divided = divide(numerator, denominator, shift)
        if (!subnormal) {
            // At most one step: the estimate is never more than a bit out.
            if (divided.quotient.bitLength() > PRECISION) divided = divide(numerator, denominator, --shift)
            else if (divided.quotient.bitLength() < PRECISION) divided = divide(numerator, denominator, ++shift)
        }

        var significand = divided.quotient
        val twiceRemainder = divided.remainder.shiftedLeft(1)
        val toward = twiceRemainder.compareTo(divided.divisor)
        val odd = !(significand and one).isZero()
        if (toward > 0 || (toward == 0 && odd)) significand = significand + one

        var exponent = FRACTION_BITS - shift
        // Rounding up can carry into a new leading bit - `1.111…1` becomes
        // `10.0`, which is the next exponent with a zero significand.
        if (!subnormal && significand.bitLength() > PRECISION) {
            significand = significand.shiftedRight(1)
            exponent++
        }
        if (!subnormal && exponent > MAX_EXPONENT) return Bits(sign or (INFINITE_EXPONENT shl 48), 0L)

        // A subnormal that rounded up to `2^112` *is* the smallest normal, and
        // says so by carrying an exponent of one - the bit that overflowed the
        // fraction is the implicit bit the encoding then supplies.
        val biased = when {
            !subnormal -> exponent + BIAS
            significand.bitLength() > FRACTION_BITS -> 1
            else -> 0
        }
        val fractionHigh = significand.shiftedRight(64).toLong() and 0xFFFF_FFFF_FFFFL
        return Bits(sign or (biased.toLong() shl 48) or fractionHigh, significand.toLong())
    }

    /** A division taken at a scale, keeping the divisor the remainder is against. */
    private class Divided(val quotient: WideInt, val remainder: WideInt, val divisor: WideInt)

    /**
     * `numerator × 2^shift / denominator`, exactly.
     *
     * A negative shift scales the denominator instead, which is the same ratio
     * and keeps both sides whole.
     */
    private fun divide(numerator: WideInt, denominator: WideInt, shift: Int): Divided {
        val scaledNumerator = if (shift > 0) numerator.shiftedLeft(shift) else numerator
        val divisor = if (shift < 0) denominator.shiftedLeft(-shift) else denominator
        val (quotient, remainder) = scaledNumerator.divideAndRemainder(divisor)
        return Divided(quotient, remainder, divisor)
    }

    /**
     * A width that holds every intermediate, rounded up to whole limbs.
     *
     * A decimal digit is under four bits and a power of ten under ten thirds of
     * one; the constant covers the significand's own bits, the scaling shift
     * and the room a carry needs.
     */
    private fun widthFor(digits: Int, power: Int): Int {
        val needed = digits * 4 + power * 10 / 3 + SUBNORMAL_SHIFT + PRECISION + 64
        return (needed + 31) / 32 * 32
    }

    /** A literal's sign, significant digits, and the power of ten they scale by. */
    private class Decimal(val negative: Boolean, val digits: String, val exponent: Int) {

        /**
         * The same value with at most [KEPT_DIGITS] digits.
         *
         * What is dropped becomes a single `1` appended below the last kept
         * digit: strictly above the truncation and strictly below its next
         * digit, which is where the dropped digits were. Rounding at bit 113
         * cannot tell the difference and ordering is preserved, which is the
         * only thing the value has to keep.
         */
        fun truncated(): Pair<String, Int> {
            if (digits.length <= KEPT_DIGITS) return digits to exponent
            val kept = digits.substring(0, KEPT_DIGITS)
            val dropped = digits.substring(KEPT_DIGITS)
            if (dropped.all { it == '0' }) return kept to exponent + dropped.length
            return kept + "1" to exponent + dropped.length - 1
        }
    }

    /**
     * The parts of a decimal float literal, or `null` for anything else.
     *
     * Accepts what the lexer accepts of one: a sign, digits around an optional
     * point, `_` separators, and an exponent. A trailing point (`65504.`) is a
     * literal and so is a leading one.
     */
    private fun parse(text: String): Decimal? {
        val body = text.trim().replace("_", "")
        if (body.isEmpty()) return null
        var index = 0
        var negative = false
        when (body[0]) {
            '-' -> { negative = true; index++ }
            '+' -> index++
        }

        val mantissa = StringBuilder()
        var fractionDigits = 0
        var sawPoint = false
        var sawDigit = false
        while (index < body.length) {
            val c = body[index]
            when {
                c.isDigit() -> {
                    mantissa.append(c)
                    if (sawPoint) fractionDigits++
                    sawDigit = true
                    index++
                }
                c == '.' && !sawPoint -> { sawPoint = true; index++ }
                else -> break
            }
        }
        if (!sawDigit) return null

        var exponent = 0L
        if (index < body.length && (body[index] == 'e' || body[index] == 'E')) {
            index++
            var exponentNegative = false
            if (index < body.length && (body[index] == '+' || body[index] == '-')) {
                exponentNegative = body[index] == '-'
                index++
            }
            if (index >= body.length || !body[index].isDigit()) return null
            while (index < body.length && body[index].isDigit()) {
                // An exponent past this is out of range whatever follows it,
                // and stopping keeps a pasted-in absurdity from overflowing.
                exponent = (exponent * 10 + (body[index] - '0')).coerceAtMost(1_000_000L)
                index++
            }
            if (exponentNegative) exponent = -exponent
        }
        // A suffix, a hex literal, anything else: not a decimal float.
        if (index != body.length) return null

        return Decimal(negative, mantissa.toString().trimStart('0'), exponent.toInt() - fractionDigits)
    }
}

/** `this` raised to [exponent], by squaring. [one] is the width's `1`. */
private fun WideInt.raisedTo(exponent: Int, one: WideInt): WideInt {
    var result = one
    var base = this
    var remaining = exponent
    while (remaining > 0) {
        if (remaining and 1 == 1) result = result * base
        remaining = remaining shr 1
        if (remaining > 0) base = base * base
    }
    return result
}
