/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.frontend

/**
 * Azora's `Char` is a Unicode code point, and a `String` is counted and
 * indexed in code points (user decision, 2026-11-07). The compiler runs on
 * Kotlin, whose strings are UTF-16; these helpers move between the two in
 * common code, without the JVM-only `codePointAt` family.
 */
object CodePoints {
    /** The largest Unicode scalar value. */
    const val MAX = 0x10FFFF

    /** True for a code point a `Char` may hold: any scalar value, never a surrogate. */
    fun isScalar(cp: Int): Boolean = cp in 0..MAX && cp !in 0xD800..0xDFFF

    /** [cp] as Kotlin text: one UTF-16 unit, or a surrogate pair above U+FFFF. */
    fun toText(cp: Int): String {
        if (cp < 0x10000) return cp.toChar().toString()
        val v = cp - 0x10000
        return charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

    /** The code points of [text]. A lone surrogate is one code point of its own value. */
    fun of(text: String): IntArray {
        val out = IntArray(text.length)
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                out[n++] = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                i += 2
            } else {
                out[n++] = c.code
                i += 1
            }
        }
        return out.copyOf(n)
    }

    /** How many code points [text] holds. */
    fun count(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }

    /** The UTF-16 offset of code point [index] in [text]; [count] of it is its end. */
    fun offset(text: String, index: Int): Int {
        var i = 0
        var n = 0
        while (n < index && i < text.length) {
            i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return i
    }

    /** The code point index of UTF-16 offset [utf16] in [text]. */
    fun index(text: String, utf16: Int): Int = count(text.substring(0, utf16))

    /** [codePoints] as Kotlin text. */
    fun text(codePoints: Iterable<Int>): String = buildString { for (cp in codePoints) append(toText(cp)) }
}
