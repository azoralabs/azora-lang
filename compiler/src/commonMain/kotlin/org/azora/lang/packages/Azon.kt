/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.packages

/**
 * AZON - Azora Object Notation - as the package manager reads and edits it.
 *
 * A document is an implicit object: `key: value` members separated by
 * newlines, with no surrounding braces. Values are strings, numbers, objects
 * `{ … }`, arrays `[ … ]` and the barewords `true`, `false` and `null`. Commas
 * may separate members and elements, and `//`, `#` and `/* */` comments may
 * stand wherever whitespace may, so a JSON document - Studio writes its
 * `azora.azon` that way - is also AZON.
 *
 * Every value keeps the span of source it was read from, so a manifest can be
 * edited in place - `azora add` inserts one member, `azora remove` deletes one -
 * without rewriting the comments and layout around it.
 */
sealed class AzonValue {
    abstract val start: Int
    abstract val end: Int

    data class Obj(val members: List<Member>, override val start: Int, override val end: Int) : AzonValue() {
        operator fun get(key: String): AzonValue? = members.lastOrNull { it.key == key }?.value
        fun member(key: String): Member? = members.lastOrNull { it.key == key }
    }

    data class Arr(val elements: List<AzonValue>, override val start: Int, override val end: Int) : AzonValue()

    data class Str(val value: String, override val start: Int, override val end: Int) : AzonValue()

    data class Num(val raw: String, override val start: Int, override val end: Int) : AzonValue()

    data class Bool(val value: Boolean, override val start: Int, override val end: Int) : AzonValue()

    data class Null(override val start: Int, override val end: Int) : AzonValue()

    /** `key: value`, spanning from the key to the end of the value. */
    data class Member(val key: String, val value: AzonValue, val start: Int)

    /** The text of a string value, or null for anything else. */
    val text: String? get() = (this as? Str)?.value
}

/** A document that is not AZON; [offset] is where reading stopped. */
class AzonError(message: String, val offset: Int) : RuntimeException(message)

object Azon {
    /** Reads [source]: an explicit `{ … }` document, or an implicit top-level object. */
    fun parse(source: String): AzonValue.Obj = Reader(source).document()

    /** [text] as an AZON string literal. */
    fun quote(text: String): String = buildString {
        append('"')
        for (c in text) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            '\r' -> append("\\r")
            else -> append(c)
        }
        append('"')
    }

    /** [key] as written in a member: bare when it is a plain name, else quoted. */
    fun key(key: String): String =
        if (key.isNotEmpty() && (key[0].isLetter() || key[0] == '_') && key.all { it.isLetterOrDigit() || it in "_-." }) key else quote(key)

    private class Reader(private val s: String) {
        private var i = 0

        fun document(): AzonValue.Obj {
            skip()
            if (peek() == '{') {
                val obj = obj()
                skip()
                if (i < s.length) fail("unexpected '${s[i]}' after the document")
                return obj
            }
            val start = i
            val members = members(closing = null)
            return AzonValue.Obj(members, start, i)
        }

        private fun fail(message: String): Nothing {
            val line = s.substring(0, i.coerceAtMost(s.length)).count { it == '\n' } + 1
            throw AzonError("line $line: $message", i)
        }

        private fun peek(): Char? = s.getOrNull(i)

        /** Skips whitespace, commas and comments. */
        private fun skip() {
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() || c == ',' -> i++
                    c == '#' -> while (i < s.length && s[i] != '\n') i++
                    c == '/' && s.getOrNull(i + 1) == '/' -> while (i < s.length && s[i] != '\n') i++
                    c == '/' && s.getOrNull(i + 1) == '*' -> {
                        val close = s.indexOf("*/", i + 2)
                        if (close < 0) fail("unterminated comment")
                        i = close + 2
                    }
                    else -> return
                }
            }
        }

        private fun members(closing: Char?): List<AzonValue.Member> {
            val out = mutableListOf<AzonValue.Member>()
            while (true) {
                skip()
                val c = peek()
                if (c == null) {
                    if (closing != null) fail("missing '$closing'")
                    return out
                }
                if (c == closing) return out
                val start = i
                val key = if (c == '"') string().value else bareword()
                if (key.isEmpty()) fail("expected a member name, found '$c'")
                skip()
                if (peek() != ':') fail("expected ':' after '$key'")
                i++
                skip()
                out += AzonValue.Member(key, value(), start)
            }
        }

        private fun obj(): AzonValue.Obj {
            val start = i
            i++ // {
            val members = members(closing = '}')
            i++ // }
            return AzonValue.Obj(members, start, i)
        }

        private fun arr(): AzonValue.Arr {
            val start = i
            i++ // [
            val elements = mutableListOf<AzonValue>()
            while (true) {
                skip()
                when (peek()) {
                    null -> fail("missing ']'")
                    ']' -> {
                        i++
                        return AzonValue.Arr(elements, start, i)
                    }
                    else -> elements += value()
                }
            }
        }

        private fun value(): AzonValue {
            val start = i
            return when (val c = peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                null -> fail("expected a value")
                else -> if (c == '-' || c.isDigit()) number() else {
                    when (val word = bareword()) {
                        "true" -> AzonValue.Bool(true, start, i)
                        "false" -> AzonValue.Bool(false, start, i)
                        "null" -> AzonValue.Null(start, i)
                        "" -> fail("unexpected '$c'")
                        else -> AzonValue.Str(word, start, i)
                    }
                }
            }
        }

        private fun bareword(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "_-.")) i++
            return s.substring(start, i)
        }

        private fun number(): AzonValue.Num {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-_")) i++
            return AzonValue.Num(s.substring(start, i), start, i)
        }

        private fun string(): AzonValue.Str {
            val start = i
            i++ // "
            val out = StringBuilder()
            while (true) {
                val c = peek() ?: fail("unterminated string")
                i++
                when (c) {
                    '"' -> return AzonValue.Str(out.toString(), start, i)
                    '\\' -> {
                        val e = peek() ?: fail("unterminated string")
                        i++
                        when (e) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'u' -> {
                                val hex = s.substring(i, (i + 4).coerceAtMost(s.length))
                                val code = hex.toIntOrNull(16) ?: fail("'\\u$hex' is not a code unit")
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }
    }
}
