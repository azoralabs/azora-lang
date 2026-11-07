/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.stdlib

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `std/unicodedata.az` is the Unicode character data std's text functions read
 * (`toUpper`, `toLower`, `trim`): generated here from the running JDK's Unicode
 * tables, never written by hand. The test fails when the committed file differs
 * from what this JDK generates; `AZORA_REGENERATE_UNICODE=1` rewrites it.
 *
 * - Case mappings are Unicode's full default mappings (SpecialCasing's
 *   unconditional entries included: `ß` uppercases to `SS`), read per code
 *   point from `String.toUpperCase(Locale.ROOT)` / `toLowerCase(Locale.ROOT)`.
 *   One-to-one mappings are stored as runs, the rest one by one.
 * - Cased and Case_Ignorable are the properties the Final_Sigma condition
 *   reads (Unicode §3.13). Case_Ignorable's Word_Break part (MidLetter,
 *   MidNumLet, Single_Quote) is listed below from UAX #29; the JDK has no
 *   Word_Break property.
 * - White_Space is what `trim` removes: Zs, Zl, Zp, U+0009..U+000D and U+0085.
 */
class UnicodeDataTest {
    @Test fun stdUnicodeDataIsWhatThisJdkGenerates() {
        val file = File("../std/unicodedata.az")
        val generated = UnicodeData.render()
        if (System.getenv("AZORA_REGENERATE_UNICODE") == "1") file.writeText(generated)
        assertEquals(
            generated,
            file.readText(),
            "std/unicodedata.az is not what JDK ${Runtime.version().feature()} generates; " +
                "regenerate it with AZORA_REGENERATE_UNICODE=1 and review the difference",
        )
    }
}

internal object UnicodeData {
    /** UAX #29 Word_Break MidLetter, MidNumLet and Single_Quote: the part of Case_Ignorable no JDK API answers. */
    private val WORD_BREAK_IGNORABLE = intArrayOf(
        0x0027, 0x002E, 0x003A, 0x00B7, 0x0387, 0x055F, 0x05F4, 0x2018, 0x2019, 0x2024, 0x2027,
        0xFE13, 0xFE52, 0xFE55, 0xFF07, 0xFF0E, 0xFF1A,
    )

    private fun scalars() = (0..0x10FFFF).asSequence().filter { it !in 0xD800..0xDFFF }

    private fun text(cp: Int) = String(Character.toChars(cp))

    private fun codePoints(s: String) = s.codePoints().toArray()

    /** One-to-one mappings as runs `[start, end, stride, delta]`; the rest as `cp -> code points`. */
    private class Mapping(val runs: List<IntArray>, val full: List<Pair<Int, IntArray>>)

    private fun mapping(map: (String) -> String): Mapping {
        val simple = mutableListOf<Pair<Int, Int>>()
        val full = mutableListOf<Pair<Int, IntArray>>()
        for (cp in scalars()) {
            val mapped = codePoints(map(text(cp)))
            when {
                mapped.size == 1 && mapped[0] == cp -> {}
                mapped.size == 1 -> simple += cp to mapped[0]
                else -> full += cp to mapped
            }
        }
        // A run continues while the gap and the delta stay the same.
        val runs = mutableListOf<IntArray>()
        var i = 0
        while (i < simple.size) {
            val (start, to) = simple[i]
            val delta = to - start
            var end = start
            var stride = 1
            var j = i + 1
            if (j < simple.size && simple[j].second - simple[j].first == delta) {
                stride = simple[j].first - start
                while (j < simple.size && simple[j].first - end == stride && simple[j].second - simple[j].first == delta) {
                    end = simple[j].first
                    j++
                }
            }
            if (end == start) stride = 1
            runs += intArrayOf(start, end, stride, delta)
            i = if (end == start) i + 1 else j
        }
        return Mapping(runs, full)
    }

    /** The scalars [test] holds, as inclusive ranges `[start, end]`. */
    private fun ranges(test: (Int) -> Boolean): List<IntArray> {
        val out = mutableListOf<IntArray>()
        var start = -1
        var last = -2
        for (cp in 0..0x10FFFF) {
            if (test(cp)) {
                if (cp != last + 1) {
                    if (start >= 0) out += intArrayOf(start, last)
                    start = cp
                }
                last = cp
            }
        }
        if (start >= 0) out += intArrayOf(start, last)
        return out
    }

    private fun isCased(cp: Int) = Character.isUpperCase(cp) || Character.isLowerCase(cp) || Character.isTitleCase(cp)

    private fun isCaseIgnorable(cp: Int): Boolean = cp in WORD_BREAK_IGNORABLE || when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT,
        Character.MODIFIER_LETTER, Character.MODIFIER_SYMBOL -> true
        else -> false
    }

    private fun isWhiteSpace(cp: Int): Boolean = cp in 0x09..0x0D || cp == 0x85 || when (Character.getType(cp).toByte()) {
        Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> false
    }

    private fun StringBuilder.array(name: String, doc: String, values: List<Int>) {
        appendLine("/** $doc */")
        appendLine("fin $name: Array<Int> = [")
        for (line in values.chunked(12)) appendLine("    " + line.joinToString(", ") + ",")
        appendLine("]")
        appendLine()
    }

    private fun StringBuilder.fullMapping(prefix: String, what: String, full: List<Pair<Int, IntArray>>) {
        array("${prefix}Keys", "Code points whose $what is more than one code point, ascending.", full.map { it.first })
        val starts = mutableListOf(0)
        for ((_, mapped) in full) starts += starts.last() + mapped.size
        array("${prefix}Starts", "Where each key's code points begin in `${prefix}Values`; one more entry ends the last.", starts)
        array("${prefix}Values", "The code points of each $what, one after another.", full.flatMap { it.second.toList() })
    }

    fun render(): String {
        val upper = mapping { it.uppercase(Locale.ROOT) }
        val lower = mapping { it.lowercase(Locale.ROOT) }
        return buildString {
            appendLine("/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */")
            appendLine()
            appendLine("/**")
            appendLine(" * @file std/unicodedata.az")
            appendLine(" *")
            appendLine(" * Unicode character data for std's text functions: case mappings and")
            appendLine(" * the Cased, Case_Ignorable and White_Space properties.")
            appendLine(" *")
            appendLine(" * Generated by `UnicodeDataTest` from JDK ${Runtime.version().feature()}'s Unicode tables; do not edit.")
            appendLine(" * Regenerate with `AZORA_REGENERATE_UNICODE=1` and review the difference.")
            appendLine(" *")
            appendLine(" * A run `[start, end, stride, delta]` maps every `stride`-th code point from")
            appendLine(" * `start` to `end` to itself plus `delta`; a range `[start, end]` is inclusive.")
            appendLine(" *")
            appendLine(" * @since 0.1")
            appendLine(" */")
            appendLine("@Supress(.Unused)")
            appendLine("exposed module std.unicodedata")
            appendLine()
            array("upperRuns", "One-to-one uppercase mappings as runs.", upper.runs.flatMap { it.toList() })
            fullMapping("upperFull", "uppercase", upper.full)
            array("lowerRuns", "One-to-one lowercase mappings as runs.", lower.runs.flatMap { it.toList() })
            fullMapping("lowerFull", "lowercase", lower.full)
            array("casedRanges", "The Cased property: Lowercase, Uppercase and Lt.", ranges(::isCased).flatMap { it.toList() })
            array(
                "caseIgnorableRanges",
                "The Case_Ignorable property: Mn, Me, Cf, Lm, Sk and Word_Break MidLetter, MidNumLet, Single_Quote.",
                ranges(::isCaseIgnorable).flatMap { it.toList() },
            )
            array("whiteSpaceRanges", "The White_Space property: Zs, Zl, Zp, U+0009..U+000D and U+0085.", ranges(::isWhiteSpace).flatMap { it.toList() })
        }.trimEnd() + "\n"
    }
}
