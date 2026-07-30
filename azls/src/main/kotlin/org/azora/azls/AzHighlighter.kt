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

package org.azora.azls

/**
 * Error-tolerant syntax highlighter for azora-lang.
 *
 * Deliberately independent from the compiler's [org.azora.lang.frontend.Lexer]:
 * an editor colorizer must survive half-typed code (unterminated strings,
 * stray characters) and must classify comments, which the lexer discards.
 * The scanner never throws — any unrecognized character is simply skipped.
 *
 * Span types (see [HighlightSpan.type]): `keyword`, `string`,
 * `interpolation-punctuation`, `number`, `comment`, `function`, `variable`,
 * `parameter`, `type`, `annotation`, `macro`, `char`.
 */
object AzHighlighter {

    /** Every azora keyword, mirroring the compiler lexer's keyword table. */
    val KEYWORDS: Set<String> = setOf(
        "var", "fin", "let", "func", "return", "package", "if", "else",
        "inline", "deepinline", "noinline", "zone", "test", "assert", "trace",
        "for", "while", "loop", "in", "break", "continue", "by", "reverse",
        "infx", "oper", "deco", "fail", "alloc", "drop", "unsafe", "isolated",
        "flow", "yield", "task", "await", "launch", "bridge", "solo", "inject", "wrap",
        "rescue", "node", "leaf", "repl", "virt", "base", "rem", "effect", "view",
        "hook", "prop", "ctor", "dtor", "ref", "out", "mut",
        "expose", "confine", "mod", "threadlocal",
        "pack", "enum", "when", "throw", "try", "catch", "impl", "prot",
        "defer", "typealias", "slot", "as", "guard", "is", "null", "use",
        "true", "false"
    )

    /** Built-in type names colored as types even without declarations in scope. */
    private val BUILTIN_TYPES = setOf(
        "Int", "UInt", "Real", "String", "Bool", "Unit", "Char", "Any",
        "Byte", "UByte", "Short", "UShort", "Long", "ULong", "Cent", "UCent",
        "Float", "Decimal"
    )

    fun highlight(source: String, visibleFunctions: Set<String> = emptySet()): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val semantics = semanticNames(source, visibleFunctions)
        var i = 0
        val n = source.length

        fun peek(offset: Int = 0): Char = if (i + offset < n) source[i + offset] else '\u0000'

        fun addInterpolationExpression(start: Int, end: Int) {
            var cursor = start
            while (cursor < end) {
                when {
                    source[cursor].isWhitespace() -> cursor++
                    source[cursor].isDigit() -> {
                        val tokenStart = cursor++
                        while (cursor < end &&
                            (source[cursor].isDigit() || source[cursor] == '_' || source[cursor] == '.')
                        ) {
                            cursor++
                        }
                        spans.add(HighlightSpan(tokenStart, cursor, "number"))
                    }
                    source[cursor].isIdentStart() -> {
                        val tokenStart = cursor++
                        while (cursor < end && source[cursor].isIdentPart()) cursor++
                        val word = source.substring(tokenStart, cursor)
                        var next = cursor
                        while (next < end && source[next].isWhitespace()) next++
                        val type = when {
                            word in KEYWORDS -> "keyword"
                            word == "self" || word == "it" -> "parameter"
                            semantics.isParameter(word, tokenStart) -> "parameter"
                            next < end && source[next] == '(' && word in semantics.functions -> "function"
                            word in BUILTIN_TYPES || word in semantics.types -> "type"
                            else -> "variable"
                        }
                        spans.add(HighlightSpan(tokenStart, cursor, type))
                    }
                    else -> cursor++
                }
            }
        }

        while (i < n) {
            val c = source[i]
            when {
                // Line comment
                c == '/' && peek(1) == '/' -> {
                    val start = i
                    while (i < n && source[i] != '\n') i++
                    spans.add(HighlightSpan(start, i, "comment"))
                }
                // Block comment (tolerated even if the language is line-comment only)
                c == '/' && peek(1) == '*' -> {
                    val start = i
                    i += 2
                    while (i < n && !(source[i] == '*' && peek(1) == '/')) i++
                    i = minOf(i + 2, n)
                    spans.add(HighlightSpan(start, i, "comment"))
                }
                // String literal with $interpolation
                c == '"' -> {
                    var segStart = i
                    i++
                    while (i < n && source[i] != '"' && source[i] != '\n') {
                        when {
                            source[i] == '\\' && i + 1 < n -> i += 2
                            source[i] == '$' && i + 1 < n && (source[i + 1] == '{' || source[i + 1].isIdentStart()) -> {
                                if (segStart < i) spans.add(HighlightSpan(segStart, i, "string"))
                                spans.add(HighlightSpan(i, i + 1, "interpolation-punctuation"))
                                i++
                                if (i < n && source[i] == '{') {
                                    spans.add(HighlightSpan(i, i + 1, "interpolation-punctuation"))
                                    var depth = 1
                                    i++
                                    val expressionStart = i
                                    while (i < n && depth > 0 && source[i] != '\n') {
                                        when {
                                            source[i] == '\\' && i + 1 < n -> i += 2
                                            source[i] == '"' || source[i] == '\'' -> {
                                                val quote = source[i++]
                                                while (i < n && source[i] != quote && source[i] != '\n') {
                                                    if (source[i] == '\\' && i + 1 < n) i += 2 else i++
                                                }
                                                if (i < n && source[i] == quote) i++
                                            }
                                            source[i] == '{' -> {
                                                depth++
                                                i++
                                            }
                                            source[i] == '}' -> {
                                                depth--
                                                if (depth > 0) i++
                                            }
                                            else -> i++
                                        }
                                    }
                                    addInterpolationExpression(expressionStart, i)
                                    if (i < n && source[i] == '}') {
                                        spans.add(HighlightSpan(i, i + 1, "interpolation-punctuation"))
                                        i++
                                    }
                                } else {
                                    val expressionStart = i
                                    while (i < n && source[i].isIdentPart()) i++
                                    addInterpolationExpression(expressionStart, i)
                                }
                                segStart = i
                            }
                            else -> i++
                        }
                    }
                    if (i < n && source[i] == '"') i++
                    if (i > segStart) spans.add(HighlightSpan(segStart, i, "string"))
                }
                // Char literal
                c == '\'' -> {
                    val start = i
                    i++
                    while (i < n && source[i] != '\'' && source[i] != '\n') {
                        if (source[i] == '\\' && i + 1 < n) i += 2 else i++
                    }
                    if (i < n && source[i] == '\'') i++
                    spans.add(HighlightSpan(start, i, "char"))
                }
                // Number (decimal / hex, with type suffixes)
                c.isDigit() -> {
                    val start = i
                    if (c == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
                        i += 2
                        while (i < n && (source[i].isLetterOrDigit())) i++
                    } else {
                        while (i < n && source[i].isDigit()) i++
                        if (i < n && source[i] == '.' && i + 1 < n && source[i + 1].isDigit()) {
                            i++
                            while (i < n && source[i].isDigit()) i++
                        }
                        // suffix letters (b, ub, s, us, u, L, uL, c, uc, f, D)
                        while (i < n && source[i].isLetter()) i++
                    }
                    spans.add(HighlightSpan(start, i, "number"))
                }
                // Annotation
                c == '@' && peek(1).isIdentStart() -> {
                    val start = i
                    i++
                    while (i < n && source[i].isIdentPart()) i++
                    spans.add(HighlightSpan(start, i, "annotation"))
                }
                // Identifier / keyword / callable / type
                c.isIdentStart() -> {
                    val start = i
                    while (i < n && source[i].isIdentPart()) i++
                    val word = source.substring(start, i)
                    val isMacro = i < n &&
                        source[i] == '@' &&
                        (source[start].isLowerCase() || source[start] == '_')
                    if (isMacro) i++
                    // Look ahead past spaces for a call parenthesis.
                    var j = i
                    while (j < n && source[j] == ' ') j++
                    val type = when {
                        isMacro -> "macro"
                        word in KEYWORDS -> "keyword"
                        start in semantics.functionDeclarations -> "function"
                        word == "self" || word == "it" -> "parameter"
                        semantics.isParameter(word, start) -> "parameter"
                        j < n && source[j] == '(' && word in semantics.functions -> "function"
                        word in BUILTIN_TYPES || word in semantics.types -> "type"
                        else -> "variable"
                    }
                    if (type != null) spans.add(HighlightSpan(start, i, type))
                }
                else -> i++
            }
        }
        return spans
    }

    private fun Char.isIdentStart(): Boolean = isLetter() || this == '_'
    private fun Char.isIdentPart(): Boolean = isLetterOrDigit() || this == '_'

    private data class ParameterScope(
        val names: Set<String>,
        val start: Int,
        val end: Int,
    )

    private data class SemanticNames(
        val functions: Set<String>,
        val functionDeclarations: Set<Int>,
        val parameterScopes: List<ParameterScope>,
        val types: Set<String>,
    ) {
        fun isParameter(name: String, offset: Int): Boolean =
            parameterScopes.any { offset in it.start..it.end && name in it.names }
    }

    /**
     * A tolerant declaration pre-pass. It intentionally understands only the
     * stable callable header shape and balanced braces; malformed code simply
     * produces a shorter scope instead of losing all highlighting.
     */
    private fun semanticNames(source: String, visibleFunctions: Set<String>): SemanticNames {
        val declarationsSource = codeOnly(source)
        val functions = visibleFunctions.toMutableSet()
        val declarations = mutableSetOf<Int>()
        val scopes = mutableListOf<ParameterScope>()
        val declaredTypes = mutableSetOf<String>()
        val callable = Regex("""\b(?:func|task|flow|hook)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:<[^>{}\n]*>)?\s*\(([^)]*)\)""")
        val parameter = Regex("""(?:\.\.\.)?([A-Za-z_][A-Za-z0-9_]*)\s*:""")
        val typeDeclaration = Regex("""\b(?:pack|enum|spec|solo|node|slot)\s+([A-Za-z_][A-Za-z0-9_]*)""")

        for (match in callable.findAll(declarationsSource)) {
            val nameGroup = match.groups[1] ?: continue
            val paramsGroup = match.groups[2] ?: continue
            functions += nameGroup.value
            declarations += nameGroup.range.first

            val names = parameter.findAll(paramsGroup.value)
                .mapNotNull { it.groups[1]?.value }
                .toSet()
            if (names.isEmpty()) continue

            val bodyOpen = source.indexOf('{', match.range.last + 1)
            val bodyEnd = if (bodyOpen >= 0) matchingBrace(source, bodyOpen) else match.range.last
            scopes += ParameterScope(names, match.range.first, bodyEnd)
        }
        for (match in typeDeclaration.findAll(declarationsSource)) {
            match.groups[1]?.value?.let(declaredTypes::add)
        }
        return SemanticNames(functions, declarations, scopes, declaredTypes)
    }

    private fun codeOnly(source: String): String {
        val masked = source.toCharArray()
        var index = 0

        fun mask(position: Int) {
            if (masked[position] != '\n' && masked[position] != '\r') {
                masked[position] = ' '
            }
        }

        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    mask(index++)
                    mask(index++)
                    while (index < source.length && source[index] != '\n') mask(index++)
                }
                source.startsWith("/*", index) -> {
                    mask(index++)
                    mask(index++)
                    while (index < source.length && !source.startsWith("*/", index)) mask(index++)
                    if (index < source.length) {
                        mask(index++)
                        mask(index++)
                    }
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index]
                    mask(index++)
                    while (index < source.length) {
                        val character = source[index]
                        mask(index++)
                        if (character == '\\' && index < source.length) {
                            mask(index++)
                        } else if (character == quote) {
                            break
                        }
                    }
                }
                else -> index++
            }
        }
        return masked.concatToString()
    }

    private fun matchingBrace(source: String, open: Int): Int {
        var depth = 1
        var index = open + 1
        while (index < source.length && depth > 0) {
            when {
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index++]
                    while (index < source.length && source[index] != quote) {
                        index += if (source[index] == '\\' && index + 1 < source.length) 2 else 1
                    }
                    if (index < source.length) index++
                }
                source[index] == '/' && source.getOrNull(index + 1) == '/' -> {
                    while (index < source.length && source[index] != '\n') index++
                }
                source[index] == '/' && source.getOrNull(index + 1) == '*' -> {
                    index += 2
                    while (index + 1 < source.length && !(source[index] == '*' && source[index + 1] == '/')) index++
                    index = minOf(source.length, index + 2)
                }
                source[index] == '{' -> { depth++; index++ }
                source[index] == '}' -> { depth--; index++ }
                else -> index++
            }
        }
        return index
    }
}
