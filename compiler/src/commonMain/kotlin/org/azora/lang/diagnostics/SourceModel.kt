/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.diagnostics

@JvmInline
value class SourceId(val value: String)

@JvmInline
value class DocumentVersion(val value: Long)

@JvmInline
value class TextOffset(val value: Int)

enum class SourceKind { USER, WORKSPACE_LIBRARY, STANDARD_LIBRARY, GENERATED, VIRTUAL }

data class SourceUnit(
    val id: SourceId,
    val uri: String,
    val displayPath: String,
    val text: String,
    val version: DocumentVersion? = null,
    val kind: SourceKind = SourceKind.USER,
)

sealed interface SourceOrigin {
    data object Written : SourceOrigin
    data class MacroExpansion(
        val callSite: SourceSpan,
        val definition: SourceSpan?,
        val expansionName: String,
    ) : SourceOrigin
    data class DerivedMember(val requestSite: SourceSpan, val derivedSpec: String) : SourceOrigin
    data class Monomorphized(val useSite: SourceSpan, val genericDeclaration: SourceSpan) : SourceOrigin
    data class CompilerGenerated(val nearestUserSpan: SourceSpan?, val reason: String) : SourceOrigin
}

data class SourceSpan(
    val source: SourceId,
    val start: TextOffset,
    val endExclusive: TextOffset,
    val origin: SourceOrigin = SourceOrigin.Written,
) {
    init {
        require(start.value >= 0) { "source span start must be non-negative" }
        require(endExclusive.value >= start.value) { "source span end must not precede its start" }
    }
}

data class SourcePosition(val line: Int, val character: Int) {
    init {
        require(line >= 0)
        require(character >= 0)
    }
}

data class SourceRange(val start: SourcePosition, val end: SourcePosition)

enum class PositionEncoding { UTF16, UTF8, UTF32 }

interface LineIndex {
    fun position(offset: TextOffset, encoding: PositionEncoding = PositionEncoding.UTF16): SourcePosition
    fun offset(position: SourcePosition, encoding: PositionEncoding = PositionEncoding.UTF16): TextOffset?
}

/** Immutable line index over one [String] snapshot. */
class StringLineIndex(private val text: String) : LineIndex {
    private val starts: IntArray = buildList {
        add(0)
        text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }.toIntArray()

    override fun position(offset: TextOffset, encoding: PositionEncoding): SourcePosition {
        val safe = offset.value.coerceIn(0, text.length)
        var low = 0
        var high = starts.lastIndex
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (starts[mid] <= safe) low = mid + 1 else high = mid - 1
        }
        val line = high.coerceAtLeast(0)
        val raw = text.substring(starts[line], safe)
        return SourcePosition(line, encodedLength(raw, encoding))
    }

    override fun offset(position: SourcePosition, encoding: PositionEncoding): TextOffset? {
        if (position.line !in starts.indices) return null
        val lineStart = starts[position.line]
        val lineEnd = (starts.getOrNull(position.line + 1)?.minus(1) ?: text.length)
            .let { if (it > lineStart && text[it - 1] == '\r') it - 1 else it }
        var cursor = lineStart
        var units = 0
        while (cursor < lineEnd && units < position.character) {
            val cp = codePointAt(text, cursor)
            val width = encodedCodePointLength(cp.first, encoding)
            if (units + width > position.character) return null
            units += width
            cursor += cp.second
        }
        return if (units == position.character) TextOffset(cursor) else null
    }

    private fun encodedLength(value: String, encoding: PositionEncoding): Int = when (encoding) {
        PositionEncoding.UTF16 -> value.length
        PositionEncoding.UTF8 -> value.encodeToByteArray().size
        PositionEncoding.UTF32 -> {
            var count = 0
            var index = 0
            while (index < value.length) {
                index += codePointAt(value, index).second
                count++
            }
            count
        }
    }

    private fun encodedCodePointLength(codePoint: Int, encoding: PositionEncoding): Int = when (encoding) {
        PositionEncoding.UTF16 -> if (codePoint > 0xffff) 2 else 1
        PositionEncoding.UTF8 -> when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }
        PositionEncoding.UTF32 -> 1
    }

    private fun codePointAt(value: String, index: Int): Pair<Int, Int> {
        val first = value[index]
        if (first.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) {
            val high = first.code - 0xd800
            val low = value[index + 1].code - 0xdc00
            return (0x10000 + (high shl 10) + low) to 2
        }
        return first.code to 1
    }
}

interface SourceManager {
    operator fun get(id: SourceId): SourceUnit?
    fun text(span: SourceSpan): String?
    fun range(span: SourceSpan, encoding: PositionEncoding = PositionEncoding.UTF16): SourceRange?
    fun resolveToUserSource(span: SourceSpan): SourceSpan
}

class ImmutableSourceManager(units: Iterable<SourceUnit>) : SourceManager {
    private val sources = units.associateBy { it.id }
    private val indexes = sources.mapValues { StringLineIndex(it.value.text) }

    override fun get(id: SourceId): SourceUnit? = sources[id]

    override fun text(span: SourceSpan): String? = sources[span.source]?.text?.let {
        if (span.endExclusive.value <= it.length) it.substring(span.start.value, span.endExclusive.value) else null
    }

    override fun range(span: SourceSpan, encoding: PositionEncoding): SourceRange? {
        val index = indexes[span.source] ?: return null
        return SourceRange(index.position(span.start, encoding), index.position(span.endExclusive, encoding))
    }

    override fun resolveToUserSource(span: SourceSpan): SourceSpan = when (val origin = span.origin) {
        SourceOrigin.Written -> span
        is SourceOrigin.MacroExpansion -> resolveToUserSource(origin.callSite)
        is SourceOrigin.DerivedMember -> resolveToUserSource(origin.requestSite)
        is SourceOrigin.Monomorphized -> resolveToUserSource(origin.useSite)
        is SourceOrigin.CompilerGenerated -> origin.nearestUserSpan?.let(::resolveToUserSource) ?: span
    }
}
