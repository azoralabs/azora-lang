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
 * An integer of a stated width, in two's complement, computed in software.
 *
 * LLVM has an `iN` for every width Azora names; nothing else does. A `Cent` is
 * 128 bits and an `Int<10000>` is ten thousand, and a `Long` holds neither -
 * so the value is carried as 32-bit limbs, little-endian, and every operation
 * wraps to [bits] exactly as the hardware types do at their own widths.
 *
 * [signed] is the type's, not the value's: the same limbs are `-1` at `Int<8>`
 * and `255` at `UInt<8>`, and only the type says which. It decides comparison,
 * division, shifting right and how the value prints.
 *
 * Kotlin Multiplatform has no big integer, which is why this exists rather than
 * a call to one.
 */
class WideInt private constructor(
    val bits: Int,
    val signed: Boolean,
    private val limbs: IntArray,
) {

    init {
        require(bits > 0) { "a width is at least one bit" }
    }

    // ── Building ───────────────────────────────────────────────────────

    companion object {
        private const val LIMB = 32

        /** How many limbs a value of [bits] bits needs. */
        private fun limbCount(bits: Int): Int = (bits + LIMB - 1) / LIMB

        /** [value] at [bits] bits, sign-extended or truncated as needed. */
        fun of(value: Long, bits: Int, signed: Boolean): WideInt {
            val limbs = IntArray(limbCount(bits))
            if (limbs.isNotEmpty()) limbs[0] = value.toInt()
            if (limbs.size > 1) limbs[1] = (value ushr LIMB).toInt()
            // A negative `Long` is all ones above its 64 bits.
            if (value < 0) for (i in 2 until limbs.size) limbs[i] = -1
            return WideInt(bits, signed, limbs).masked()
        }

        /**
         * The decimal or based digits of [text] at [bits] bits.
         *
         * Accepts a leading `-`, the `0x` / `0b` / `0o` prefixes and `_`
         * separators - everything the lexer accepts, so a literal's own text
         * can be handed straight over.
         */
        fun parse(text: String, bits: Int, signed: Boolean): WideInt {
            var body = text.trim().replace("_", "")
            val negative = body.startsWith("-")
            if (negative || body.startsWith("+")) body = body.drop(1)
            val radix = when {
                body.startsWith("0x") || body.startsWith("0X") -> 16
                body.startsWith("0b") || body.startsWith("0B") -> 2
                body.startsWith("0o") || body.startsWith("0O") -> 8
                else -> 10
            }
            if (radix != 10) body = body.drop(2)
            var result = of(0, bits, signed)
            val base = of(radix.toLong(), bits, signed)
            for (c in body) {
                val digit = c.digitToIntOrNull(radix)
                    ?: error("'$c' is not a digit of base $radix in '$text'")
                result = result * base + of(digit.toLong(), bits, signed)
            }
            return if (negative) -result else result
        }
    }

    /**
     * The same value read at [bits] bits, as [signed] says.
     *
     * Widening a negative value carries the sign into the bits above it -
     * including the unused ones of its own top limb, which is where a value
     * masked at 8 bits keeps its zeroes.
     */
    fun at(bits: Int, signed: Boolean): WideInt {
        val out = IntArray(limbCount(bits))
        val negative = this.signed && isNegative()
        val fill = if (negative) -1 else 0
        for (i in out.indices) out[i] = if (i < limbs.size) limbs[i] else fill
        if (negative) {
            val spare = limbs.size * LIMB - this.bits
            if (spare > 0 && limbs.isNotEmpty() && limbs.lastIndex < out.size) {
                out[limbs.lastIndex] = out[limbs.lastIndex] or (-1 shl (LIMB - spare))
            }
        }
        return WideInt(bits, signed, out).masked()
    }

    // ── Reading ────────────────────────────────────────────────────────

    /** Whether the value is negative *as its type reads it*. */
    fun isNegative(): Boolean = signed && topBit()

    /** True when every limb is zero. */
    fun isZero(): Boolean = limbs.all { it == 0 }

    /**
     * The position of the highest set bit, counting from one, or `0` for zero.
     *
     * The bits as they are stored, so a signed negative value answers its
     * width. Callers that want a magnitude negate first.
     */
    fun bitLength(): Int {
        for (i in limbs.indices.reversed()) {
            if (limbs[i] != 0) return i * LIMB + (LIMB - limbs[i].countLeadingZeroBits())
        }
        return 0
    }

    /**
     * The low 64 bits, which is all a `Long` can carry.
     *
     * Read as the type reads it: `Int<8>`'s `0xFF` is `-1` here, because that
     * is the value, and a `Long` has room to say so.
     */
    fun toLong(): Long {
        val low = (limbs.getOrElse(0) { 0 }.toLong() and 0xFFFF_FFFFL)
        val high = (limbs.getOrElse(1) { 0 }.toLong() and 0xFFFF_FFFFL)
        val raw = (high shl LIMB) or low
        if (!isNegative() || bits >= 64) return raw
        // Sign-extend from the width: the bits above it are the sign.
        return raw or (-1L shl bits)
    }

    override fun toString(): String {
        if (isZero()) return "0"
        if (isNegative()) return "-" + (-this).magnitudeDecimal()
        return magnitudeDecimal()
    }

    override fun equals(other: Any?): Boolean =
        other is WideInt && bits == other.bits && limbs.contentEquals(other.limbs)

    override fun hashCode(): Int = 31 * bits + limbs.contentHashCode()

    // ── Arithmetic ─────────────────────────────────────────────────────

    operator fun plus(other: WideInt): WideInt {
        val out = IntArray(limbs.size)
        var carry = 0L
        for (i in out.indices) {
            val sum = word(i) + other.word(i) + carry
            out[i] = sum.toInt()
            carry = sum ushr LIMB
        }
        return WideInt(bits, signed, out).masked()
    }

    operator fun minus(other: WideInt): WideInt = this + (-other)

    operator fun unaryMinus(): WideInt {
        val out = IntArray(limbs.size) { limbs[it].inv() }
        return WideInt(bits, signed, out).masked() + of(1, bits, signed)
    }

    operator fun times(other: WideInt): WideInt {
        val out = IntArray(limbs.size)
        for (i in limbs.indices) {
            var carry = 0L
            for (j in 0 until limbs.size - i) {
                val at = i + j
                val product = word(i) * other.word(j) + (out[at].toLong() and 0xFFFF_FFFFL) + carry
                out[at] = product.toInt()
                carry = product ushr LIMB
            }
        }
        return WideInt(bits, signed, out).masked()
    }

    operator fun div(other: WideInt): WideInt = divideAndRemainder(other).first

    operator fun rem(other: WideInt): WideInt = divideAndRemainder(other).second

    /**
     * The quotient and remainder, both truncated toward zero.
     *
     * Signed division is done on magnitudes and signed afterwards, which is
     * what "toward zero" means and what every hardware divide does.
     */
    fun divideAndRemainder(other: WideInt): Pair<WideInt, WideInt> {
        if (other.isZero()) error("division by zero")
        val negativeQuotient = isNegative() != other.isNegative()
        val left = if (isNegative()) -this else this
        val right = if (other.isNegative()) -other else other

        var remainder = of(0, bits, signed)
        val quotient = IntArray(limbs.size)
        val one = of(1, bits, signed)
        for (bit in bits - 1 downTo 0) {
            remainder = remainder.shiftedLeft(1)
            if (left.bitAt(bit)) remainder = remainder + one
            if (remainder.unsignedCompare(right) >= 0) {
                remainder = remainder - right
                quotient[bit / LIMB] = quotient[bit / LIMB] or (1 shl (bit % LIMB))
            }
        }
        val q = WideInt(bits, signed, quotient).masked()
        val r = remainder
        return Pair(
            if (negativeQuotient) -q else q,
            if (isNegative()) -r else r,
        )
    }

    // ── Bits ───────────────────────────────────────────────────────────

    infix fun and(other: WideInt): WideInt =
        WideInt(bits, signed, IntArray(limbs.size) { limbs[it] and other.limbs[it] }).masked()

    infix fun or(other: WideInt): WideInt =
        WideInt(bits, signed, IntArray(limbs.size) { limbs[it] or other.limbs[it] }).masked()

    infix fun xor(other: WideInt): WideInt =
        WideInt(bits, signed, IntArray(limbs.size) { limbs[it] xor other.limbs[it] }).masked()

    fun inv(): WideInt = WideInt(bits, signed, IntArray(limbs.size) { limbs[it].inv() }).masked()

    /** `this shl count`, wrapping at [bits]. */
    fun shiftedLeft(count: Int): WideInt {
        if (count <= 0) return this
        if (count >= bits) return of(0, bits, signed)
        val out = IntArray(limbs.size)
        val whole = count / LIMB
        val part = count % LIMB
        for (i in limbs.indices.reversed()) {
            val from = i - whole
            if (from < 0) continue
            var value = limbs[from].toLong() and 0xFFFF_FFFFL
            value = value shl part
            if (part > 0 && from > 0) {
                value = value or ((limbs[from - 1].toLong() and 0xFFFF_FFFFL) ushr (LIMB - part))
            }
            out[i] = value.toInt()
        }
        return WideInt(bits, signed, out).masked()
    }

    /** `this shr count` - arithmetic when the type is signed, logical when it is not. */
    fun shiftedRight(count: Int): WideInt {
        if (count <= 0) return this
        val fill = if (isNegative()) -1 else 0
        if (count >= bits) return WideInt(bits, signed, IntArray(limbs.size) { fill }).masked()
        val out = IntArray(limbs.size) { fill }
        val whole = count / LIMB
        val part = count % LIMB
        for (i in limbs.indices) {
            val from = i + whole
            if (from >= limbs.size) continue
            var value = (limbs[from].toLong() and 0xFFFF_FFFFL) ushr part
            if (part > 0) {
                val above = if (from + 1 < limbs.size) limbs[from + 1].toLong() and 0xFFFF_FFFFL else (fill.toLong() and 0xFFFF_FFFFL)
                value = value or (above shl (LIMB - part))
            }
            out[i] = value.toInt()
        }
        return WideInt(bits, signed, out).masked()
    }

    // ── Comparison ─────────────────────────────────────────────────────

    operator fun compareTo(other: WideInt): Int {
        if (signed) {
            val leftNegative = isNegative()
            val rightNegative = other.isNegative()
            if (leftNegative != rightNegative) return if (leftNegative) -1 else 1
        }
        return unsignedCompare(other)
    }

    private fun unsignedCompare(other: WideInt): Int {
        for (i in limbs.indices.reversed()) {
            val left = word(i)
            val right = other.word(i)
            if (left != right) return if (left < right) -1 else 1
        }
        return 0
    }

    // ── Internals ──────────────────────────────────────────────────────

    /** Limb [index] as an unsigned 32-bit value in a `Long`, zero past the end. */
    private fun word(index: Int): Long =
        if (index < limbs.size) limbs[index].toLong() and 0xFFFF_FFFFL else 0L

    private fun bitAt(bit: Int): Boolean =
        bit < bits && (limbs[bit / LIMB] ushr (bit % LIMB)) and 1 == 1

    private fun topBit(): Boolean = bitAt(bits - 1)

    /** Clears whatever sits above [bits] in the top limb. */
    private fun masked(): WideInt {
        val spare = limbs.size * LIMB - bits
        if (spare > 0 && limbs.isNotEmpty()) {
            limbs[limbs.lastIndex] = limbs[limbs.lastIndex] and (-1 ushr spare)
        }
        return this
    }

    /**
     * The magnitude in decimal, by repeated division by a billion.
     *
     * Done at 64 bits or wider whatever this value's width is: a billion does
     * not fit in an `Int<8>`, and dividing by what it masks to would be
     * dividing by zero.
     */
    private fun magnitudeDecimal(): String {
        val room = if (bits >= 64) bits else 64
        var value = WideInt(bits, signed = false, limbs.copyOf()).at(room, signed = false)
        val billion = of(1_000_000_000L, room, signed = false)
        val chunks = mutableListOf<String>()
        while (!value.isZero()) {
            val (quotient, remainder) = value.divideAndRemainder(billion)
            chunks.add(remainder.toLong().toString())
            value = quotient
        }
        if (chunks.isEmpty()) return "0"
        return buildString {
            append(chunks.last())
            for (i in chunks.size - 2 downTo 0) append(chunks[i].padStart(9, '0'))
        }
    }
}
