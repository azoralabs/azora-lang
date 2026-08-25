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

import org.azora.lang.frontend.AzoraSyntaxVocabulary

/**
 * Error-tolerant syntax highlighter for azora-lang.
 *
 * Deliberately independent from the compiler's [org.azora.lang.frontend.Lexer]:
 * an editor colorizer must survive half-typed code (unterminated strings,
 * stray characters) and must classify comments, which the lexer discards.
 * The scanner never throws - any unrecognized character is simply skipped.
 *
 * Span types (see [HighlightSpan.type]): `keyword`, `string`,
 * `interpolation-punctuation`, `number`, `comment`, `doc`, `docTag`,
 * `docTagValue`, `function`, `variable`, `parameter`, `contextParameter`,
 * `property`, `type`, `generic`, `label`, `scope`, `modulePath`, `annotation`,
 * `macro`, `associatedType`, `enumMember`, `errorMember`, `char`.
 *
 * `contextParameter` is a receiver a call site must supply (`[self: Self&]`),
 * told apart from the parameters a declaration invents for itself. `property`
 * covers a `prop` at its declaration and where it is read. `modulePath` is the
 * part of an `import` that says where to look, as opposed to what to take -
 * which keeps whatever colour the declaration it names has.
 */
object AzHighlighter {

    /** The compiler's reserved words, also offered by code completion. */
    val KEYWORDS: Set<String> = AzoraSyntaxVocabulary.reservedKeywords.keys

    private val RESERVED_KEYWORDS = AzoraSyntaxVocabulary.reservedKeywords.keys

    /** The closed set of sigils a macro name may end in, as part of the name. */
    private val MACRO_NAME_SIGILS = setOf('!', '?', '&', '*', '^')

    fun highlight(
        source: String,
        visibleFunctions: Set<String> = emptySet(),
        visibleTypes: Set<String> = emptySet(),
        visibleSpecTypes: Set<String> = emptySet(),
        visibleProperties: Set<String> = emptySet(),
        visibleEnumCases: Set<String> = emptySet(),
        visibleErrorCases: Set<String> = emptySet(),
    ): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val semantics = semanticNames(
            source, visibleFunctions, visibleTypes, visibleSpecTypes,
            visibleProperties, visibleEnumCases, visibleErrorCases,
        )
        var i = 0
        val n = source.length

        fun peek(offset: Int = 0): Char = if (i + offset < n) source[i + offset] else '\u0000'

        fun addInterpolationExpression(start: Int, end: Int) {
            var cursor = start
            while (cursor < end) {
                when {
                    source[cursor].isWhitespace() -> cursor++
                    source[cursor].isDigit() -> {
                        val tokenStart = cursor
                        cursor = numberEnd(source, cursor, end)
                        spans.add(HighlightSpan(tokenStart, cursor, "number"))
                    }
                    source[cursor].isIdentStart() -> {
                        val tokenStart = cursor++
                        while (cursor < end && source[cursor].isIdentPart()) cursor++
                        val word = source.substring(tokenStart, cursor)
                        var next = cursor
                        while (next < end && source[next].isWhitespace()) next++
                        val type = when {
                            word in RESERVED_KEYWORDS -> "keyword"
                            tokenStart in semantics.overrideFunctionDeclarations -> "overrideFunction"
                            tokenStart in semantics.specFunctionDeclarations -> "specFunction"
                            tokenStart in semantics.functionDeclarations -> "functionDeclaration"
                            tokenStart in semantics.modulePathOffsets -> "modulePath"
                            tokenStart in semantics.labelOffsets -> "label"
                            tokenStart in semantics.scopeOffsets -> "scope"
                            semantics.isGeneric(word, tokenStart) -> "generic"
                            semantics.isAssociatedType(word) -> "associatedType"
                            semantics.isContextParameter(word, tokenStart) -> "contextParameter"
                            tokenStart in semantics.enumCaseDeclarations -> "enumMember"
                            tokenStart in semantics.errorCaseDeclarations -> "errorMember"
                            semantics.isEnumCase(word) && isQualifiedMemberAt(source, tokenStart) -> "enumMember"
                            semantics.isErrorCase(word) && isQualifiedMemberAt(source, tokenStart) -> "errorMember"
                            tokenStart in semantics.overridePropertyDeclarations -> "overrideProperty"
                            tokenStart in semantics.specPropertyDeclarations -> "specProperty"
                            tokenStart in semantics.propertyDeclarations -> "propertyDeclaration"
                            semantics.isProperty(word) && isQualifiedMemberAt(source, tokenStart) -> "property"
                            word == "self" || word == "it" -> "parameter"
                            semantics.isParameter(word, tokenStart) && !isQualifiedMemberAt(source, tokenStart) -> "parameter"
                            next < end && source[next] in setOf('(', '<') &&
                                semantics.isFunction(source, word, tokenStart) -> "function"
                            tokenStart in semantics.specTypeDeclarations || semantics.isSpecType(word) -> "specType"
                            tokenStart in semantics.typeDeclarations -> "typeDeclaration"
                            semantics.isType(source, word, tokenStart) -> "type"
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
                // Doc comment. Split into prose, tags, and the names those tags
                // document, so `@param capacity` reads as three things.
                c == '/' && peek(1) == '*' && peek(2) == '*' -> {
                    val start = i
                    i += 3
                    while (i < n && !(source[i] == '*' && peek(1) == '/')) i++
                    i = minOf(i + 2, n)
                    addDocComment(source, start, i, spans)
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
                // Number. The tolerant scanner consumes the same base prefixes,
                // separators, fractions and exponents as the compiler so one
                // literal always produces one semantic-token span.
                c.isDigit() -> {
                    val start = i
                    i = numberEnd(source, i, n)
                    spans.add(HighlightSpan(start, i, "number"))
                }
                // Macro invocation `@name` / `@scope::name` (lowercase final
                // segment) or decorator `@Name` / `@scope::Name`.
                c == '@' && peek(1).isIdentStart() -> {
                    val start = i
                    i++
                    var finalHead = source[i]
                    while (true) {
                        while (i < n && source[i].isIdentPart()) i++
                        if (i + 2 >= n || source[i] != ':' || source[i + 1] != ':' || !source[i + 2].isIdentStart()) break
                        i += 2
                        finalHead = source[i]
                    }
                    val isMacro = finalHead.isLowerCase() || finalHead == '_'
                    // A macro's name may end in one of `! ? & * ^`, and the
                    // sigil is part of the name.
                    if (isMacro && i < n && source[i] in MACRO_NAME_SIGILS) i++
                    spans.add(HighlightSpan(start, i, if (isMacro) "macro" else "annotation"))
                }
                // Identifier / keyword / callable / type
                c.isIdentStart() -> {
                    val start = i
                    while (i < n && source[i].isIdentPart()) i++
                    val word = source.substring(start, i)
                    // Look ahead past spaces for a call parenthesis.
                    var j = i
                    while (j < n && source[j] == ' ') j++
                    val type = when {
                        word in RESERVED_KEYWORDS -> "keyword"
                        start in semantics.overrideFunctionDeclarations -> "overrideFunction"
                        start in semantics.specFunctionDeclarations -> "specFunction"
                        start in semantics.functionDeclarations -> "functionDeclaration"
                        start in semantics.modulePathOffsets -> "modulePath"
                        start in semantics.labelOffsets -> "label"
                        start in semantics.scopeOffsets -> "scope"
                        semantics.isGeneric(word, start) -> "generic"
                        semantics.isAssociatedType(word) -> "associatedType"
                        // A receiver is named `self`, so it has to answer before
                        // the implicit-parameter branch below claims the word.
                        semantics.isContextParameter(word, start) -> "contextParameter"
                        start in semantics.enumCaseDeclarations -> "enumMember"
                        start in semantics.errorCaseDeclarations -> "errorMember"
                        semantics.isEnumCase(word) && isQualifiedMemberAt(source, start) -> "enumMember"
                        semantics.isErrorCase(word) && isQualifiedMemberAt(source, start) -> "errorMember"
                        start in semantics.overridePropertyDeclarations -> "overrideProperty"
                        start in semantics.specPropertyDeclarations -> "specProperty"
                        start in semantics.propertyDeclarations -> "propertyDeclaration"
                        semantics.isProperty(word) && isQualifiedMemberAt(source, start) -> "property"
                        word == "self" || word == "it" -> "parameter"
                        semantics.isParameter(word, start) && !isQualifiedMemberAt(source, start) -> "parameter"
                        j < n && source[j] in setOf('(', '<') && semantics.isFunction(source, word, start) -> "function"
                        start in semantics.specTypeDeclarations || semantics.isSpecType(word) -> "specType"
                        start in semantics.typeDeclarations -> "typeDeclaration"
                        semantics.isType(source, word, start) -> "type"
                        else -> "variable"
                    }
                    spans.add(HighlightSpan(start, i, type))
                }
                else -> i++
            }
        }
        return spans
    }

    private fun Char.isIdentStart(): Boolean = isLetter() || this == '_'
    private fun Char.isIdentPart(): Boolean = isLetterOrDigit() || this == '_'

    private fun isContextualKeyword(source: String, start: Int, end: Int): Boolean {
        val word = source.substring(start, end)
        fun nextWord(): String? {
            var index = end
            while (index < source.length && source[index].isWhitespace()) index++
            if (index >= source.length || !source[index].isIdentStart()) return null
            val wordStart = index++
            while (index < source.length && source[index].isIdentPart()) index++
            return source.substring(wordStart, index)
        }
        val lineStart = source.lastIndexOf('\n', start - 1) + 1
        val prefix = source.substring(lineStart, start).trim()
        return when (word) {
            "module" -> nextWord() != null && prefix in setOf("", "exposed", "confined")
            "union" -> nextWord()?.firstOrNull()?.isUpperCase() == true
            "async" -> nextWord() == "func"
            "where" -> {
                val before = source.substring(lineStart, start)
                val function = Regex("""\b(?:async\s+)?func\b|\binfx\b""").find(before)
                if (function != null) {
                    before.indexOf(')', function.range.last + 1) >= 0
                } else {
                    Regex("""\b(?:pack|enum|spec|annot|impl|prop|typealias|variant)\b""")
                        .containsMatchIn(before)
                }
            }
            "assoc" -> isContextualAssoc(source, start)
            "escaping" -> isContextualEscaping(source, start, end)
            "replace", "includes" -> isContextualGraphClause(source, start, end)
            "derives" -> isContextualDerives(source, start, end)
            "binds" -> isContextualBinds(source, start, end)
            "requires" -> isContextualRequires(source, start, end)
            "lend" -> isContextualLend(source, start, end)
            "seal" -> isContextualSeal(source, start, end)
            else -> false
        }
    }

    /** A pack declaration, whole, that a `derives` clause may follow. */
    private val DERIVES_DECLARATION = Regex(
        """^\s*(?:(?:exposed|protected|confined|bridge)\s+)*pack\s+[A-Za-z_$][A-Za-z0-9_$]*""" +
            """(?:\s*<[^>{}\n]*>)?(?:\s*\([^)\n]*\))?\s*$""",
    )

    /**
     * `derives` is grammar only after a pack or union declaration.
     *
     * The declaration is named, optionally generic, and may say which literal
     * it is written as - `bridge pack Int<N: __uint = 32>(__int)` is all three
     * at once. A long one puts the clause on the next line, so an empty prefix
     * looks at the line above.
     */
    private fun isContextualDerives(source: String, start: Int, end: Int): Boolean {
        var next = end
        while (next < source.length && source[next] in setOf(' ', '\t', '\r')) next++
        if (next >= source.length || (!source[next].isIdentStart() && source[next] != '[')) return false
        return DERIVES_DECLARATION.matches(headPrefix(source, start))
    }

    /** The declaration head a clause at [start] continues; see [isContextualDerives]. */
    private fun headPrefix(source: String, start: Int): String {
        var lineStart = source.lastIndexOf('\n', start - 1) + 1
        if (source.substring(lineStart, start).isNotBlank()) return source.substring(lineStart, start)
        while (lineStart > 0) {
            val aboveEnd = lineStart - 1
            val aboveStart = source.lastIndexOf('\n', aboveEnd - 1) + 1
            val above = source.substring(aboveStart, aboveEnd)
            if (above.isNotBlank()) return above.trimEnd()
            lineStart = aboveStart
        }
        return ""
    }

    /** `escaping` is contextual only directly before a callable type. */
    private fun isContextualEscaping(source: String, start: Int, end: Int): Boolean {
        if (isAfterMemberSeparator(source, start)) return false
        val lineEnd = source.indexOf('\n', end).takeIf { it >= 0 } ?: source.length
        val tail = source.substring(end, lineEnd).trimStart()
        return Regex("""^(?:\([^)]*\)|\[[^]]*])\s*->""").containsMatchIn(tail)
    }

    /** `replace` and `includes` are clauses on a graph declaration header. */
    private fun isContextualGraphClause(source: String, start: Int, end: Int): Boolean {
        if (isAfterMemberSeparator(source, start)) return false
        val prefix = linePrefix(source, start)
        if (!Regex(
                """^\s*(?:(?:exposed|protected|confined)\s+)*graph\s+[A-Za-z_][A-Za-z0-9_]*(?:\s+replace\s+[A-Za-z_][A-Za-z0-9_]*)?\s*$""",
            ).matches(prefix)
        ) return false
        var next = end
        while (next < source.length && source[next] in setOf(' ', '\t', '\r')) next++
        return source.getOrNull(next)?.let { it.isIdentStart() || it == '[' } == true
    }

    /** `binds` belongs to an annotation header or one graph registration. */
    private fun isContextualBinds(source: String, start: Int, end: Int): Boolean {
        if (isAfterMemberSeparator(source, start)) return false
        val prefix = linePrefix(source, start)
        val annotation = Regex(
            """^\s*(?:(?:exposed|protected|confined|bridge)\s+)*annot\s+@[A-Za-z_][A-Za-z0-9_]*(?:\s+for\s+.+)?\s*$""",
        ).matches(prefix)
        val registration = Regex(
            """^\s*(?:solo|factory|scope)\s+[A-Za-z_][A-Za-z0-9_]*(?:\s*\([^\n]*\))?\s*$""",
        ).matches(prefix)
        if (!annotation && !registration) return false
        var next = end
        while (next < source.length && source[next] in setOf(' ', '\t', '\r')) next++
        return source.getOrNull(next)?.let { it.isIdentStart() || it == '[' } == true
    }

    /** `requires` is a capability clause on a spec header. */
    private fun isContextualRequires(source: String, start: Int, end: Int): Boolean {
        if (isAfterMemberSeparator(source, start)) return false
        val prefix = linePrefix(source, start)
        if (!Regex(
                """^\s*(?:(?:exposed|protected|confined|bridge)\s+)*spec\s+[A-Za-z_][A-Za-z0-9_]*(?:\s*<[^>{}\n]*>)?(?:\s*:\s*.+)?\s*$""",
            ).matches(prefix)
        ) return false
        var next = end
        while (next < source.length && source[next] in setOf(' ', '\t', '\r')) next++
        return source.getOrNull(next)?.let { it.isIdentStart() || it == '[' } == true
    }

    /** `lend name` is an ownership expression; declarations/member names are not keywords. */
    private fun isContextualLend(source: String, start: Int, end: Int): Boolean {
        if (isAfterMemberSeparator(source, start)) return false
        var next = end
        while (next < source.length && source[next] in setOf(' ', '\t', '\r')) next++
        return source.getOrNull(next)?.isIdentStart() == true
    }

    /** `seal` has grammar meaning only as the first expression after a branch arrow. */
    private fun isContextualSeal(source: String, start: Int, end: Int): Boolean {
        if (isAfterMemberSeparator(source, start)) return false
        var previous = start - 1
        while (previous >= 0 && source[previous].isWhitespace()) previous--
        if (source.getOrNull(previous) == '{') {
            previous--
            while (previous >= 0 && source[previous].isWhitespace()) previous--
        }
        val arrow = previous >= 1 && source[previous] == '>' && source[previous - 1] in setOf('-', '=')
        if (!arrow) return false
        var next = end
        while (next < source.length && source[next].isWhitespace()) next++
        val value = source.getOrNull(next) ?: return false
        return value.isIdentStart() || value.isDigit() || value in setOf('.', '"', '\'', '(', '[', '{', '-', '!')
    }

    private fun linePrefix(source: String, start: Int): String {
        val lineStart = source.lastIndexOf('\n', start - 1) + 1
        return source.substring(lineStart, start)
    }

    private fun isAfterMemberSeparator(source: String, start: Int): Boolean {
        var previous = start - 1
        while (previous >= 0 && source[previous] in setOf(' ', '\t', '\r')) previous--
        return source.getOrNull(previous) == '.' ||
            (source.getOrNull(previous) == ':' && source.getOrNull(previous - 1) == ':')
    }

    private fun isQualifiedMemberAt(source: String, start: Int): Boolean =
        isAfterMemberSeparator(source, start)

    /**
     * `assoc` is a keyword only after a complete spec/implementation subject:
     * `spec Iterator assoc Item` and
     * `impl Iterator for Rows assoc Item = Entity`.
     *
     * The compiler consumes this clause on the same header line. Walking the
     * line backwards keeps declaration names such as `spec assoc`, function
     * names, bindings, and member calls ordinary identifiers.
     */
    private fun isContextualAssoc(source: String, start: Int): Boolean {
        val lineStart = source.lastIndexOf('\n', start - 1) + 1
        var index = start - 1
        var parenDepth = 0
        var bracketDepth = 0
        var angleDepth = 0
        var sawSubject = false

        while (index >= lineStart) {
            when (val c = source[index]) {
                ' ', '\t', '\r' -> index--
                ')' -> {
                    parenDepth++
                    index--
                }
                ']' -> {
                    bracketDepth++
                    index--
                }
                '>' -> {
                    angleDepth++
                    index--
                }
                '(' -> {
                    if (parenDepth == 0) return false
                    parenDepth--
                    index--
                }
                '[' -> {
                    if (bracketDepth == 0) return false
                    bracketDepth--
                    if (parenDepth == 0 && bracketDepth == 0 && angleDepth == 0) sawSubject = true
                    index--
                }
                '<' -> {
                    if (angleDepth == 0) return false
                    angleDepth--
                    index--
                }
                '{', '}', ';', '=' -> {
                    if (parenDepth == 0 && bracketDepth == 0 && angleDepth == 0) return false
                    index--
                }
                else -> {
                    if (c.isIdentPart()) {
                        val wordEnd = index + 1
                        while (index >= lineStart && source[index].isIdentPart()) index--
                        if (parenDepth == 0 && bracketDepth == 0 && angleDepth == 0) {
                            val previous = source.substring(index + 1, wordEnd)
                            when (previous) {
                                "spec", "impl" -> return sawSubject
                                "for" -> if (!sawSubject) return false
                                else -> sawSubject = true
                            }
                        }
                    } else {
                        index--
                    }
                }
            }
        }
        return false
    }

    private data class ParameterScope(
        val names: Set<String>,
        val start: Int,
        val end: Int,
    )

    private data class GenericScope(
        val names: Set<String>,
        val start: Int,
        val end: Int,
    )

    private data class SemanticNames(
        val functions: Set<String>,
        val functionDeclarations: Set<Int>,
        val specFunctionDeclarations: Set<Int>,
        val overrideFunctionDeclarations: Set<Int>,
        val parameterScopes: List<ParameterScope>,
        val genericScopes: List<GenericScope>,
        val types: Set<String>,
        val typeDeclarations: Set<Int>,
        val specTypes: Set<String>,
        val specTypeDeclarations: Set<Int>,
        val labelOffsets: Set<Int>,
        val scopeOffsets: Set<Int>,
        /** Segments of an `import`/`module` path - the "where", not the "what". */
        val modulePathOffsets: Set<Int>,
        val contextParameterOffsets: Set<Int>,
        val contextParameterScopes: List<ParameterScope>,
        val properties: Set<String>,
        val propertyDeclarations: Set<Int>,
        val specPropertyDeclarations: Set<Int>,
        val overridePropertyDeclarations: Set<Int>,
        val associatedTypes: Set<String>,
        val enumCases: Set<String>,
        val errorCases: Set<String>,
        val enumCaseDeclarations: Set<Int>,
        val errorCaseDeclarations: Set<Int>,
    ) {
        fun isParameter(name: String, offset: Int): Boolean =
            parameterScopes.any { offset in it.start..it.end && name in it.names }

        fun isProperty(name: String): Boolean = name in properties

        fun isSpecType(name: String): Boolean = name in specTypes

        fun isContextParameter(name: String, offset: Int): Boolean =
            offset in contextParameterOffsets ||
                contextParameterScopes.any { offset in it.start..it.end && name in it.names }

        fun isAssociatedType(name: String): Boolean = name in associatedTypes

        fun isEnumCase(name: String): Boolean = name in enumCases

        fun isErrorCase(name: String): Boolean = name in errorCases

        fun isFunction(source: String, name: String, offset: Int): Boolean =
            name in functions || qualifiedNameAt(source, name, offset) in functions

        fun isType(source: String, name: String, offset: Int): Boolean =
            name in types || qualifiedNameAt(source, name, offset) in types

        fun isGeneric(name: String, offset: Int): Boolean =
            genericScopes.any { offset in it.start..it.end && name in it.names }
    }

    /**
     * A tolerant declaration pre-pass. It intentionally understands only the
     * stable callable header shape and balanced braces; malformed code simply
     * produces a shorter scope instead of losing all highlighting.
     */
    private fun semanticNames(
        source: String,
        visibleFunctions: Set<String>,
        visibleTypes: Set<String>,
        visibleSpecTypes: Set<String>,
        visibleProperties: Set<String>,
        visibleEnumCases: Set<String>,
        visibleErrorCases: Set<String>,
    ): SemanticNames {
        val declarationsSource = codeOnly(source)
        val functions = visibleFunctions.toMutableSet()
        val declarations = mutableSetOf<Int>()
        val scopes = mutableListOf<ParameterScope>()
        val contextScopes = mutableListOf<ParameterScope>()
        val genericScopes = mutableListOf<GenericScope>()
        val declaredTypes = visibleTypes.toMutableSet()
        val typeDeclarationOffsets = linkedSetOf<Int>()
        val specTypes = visibleSpecTypes.toMutableSet()
        val specTypeDeclarationOffsets = linkedSetOf<Int>()
        val associatedTypes = linkedSetOf<String>()
        val callable = Regex(
            """\bfunc\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:<([^>{}\n]*)>)?\s*(?:\[([^\]\n]*)])?\s*\(([^)]*)\)""",
        )
        val canonicalCallable = Regex(
            """\bfunc(?:\s*<([^>{}\n]*)>)?\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""",
        )
        val implicitReceiverCallable = Regex(
            """\bfunc(?:\s*<([^>{}\n]*)>)?\s+(?:[A-Za-z_][A-Za-z0-9_]*(?:\s*<[^>{}\n]*>)?\s*)?(?:[&!])?\.([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""",
        )
        val explicitReceiverCallable = Regex(
            """\bfunc(?:\s*<([^>{}\n]*)>)?\s+\(([^)]*)\)\.([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""",
        )
        val parameter = Regex("""(?:\.\.\.)?([A-Za-z_][A-Za-z0-9_]*)\s*:""")
        // `prop name[receiver]: Type` - a property states its type where a
        // function states its parameters.
        val property = Regex(
            """\bprop\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:<([^>{}\n]*)>)?\s*(?:\[([^\]\n]*)])?\s*:""",
        )
        val canonicalProperty = Regex(
            """\bprop(?:\s*<([^>{}\n]*)>)?\s+&\.([A-Za-z_][A-Za-z0-9_]*)\s*:""",
        )
        val contextParameterOffsets = linkedSetOf<Int>()
        val properties = visibleProperties.toMutableSet()
        val propertyDeclarations = linkedSetOf<Int>()
        val typeDeclaration = Regex(
            // `annot @Name` carries its sigil into the declaration, so the
            // name a decorator declares sits one character further on.
            """\b(pack|enum|error|spec|annot|union|typealias)\s+@?([A-Za-z_][A-Za-z0-9_]*)(?:\s*<([^>{}\n]*)>)?""",
        )

        for (match in callable.findAll(declarationsSource)) {
            val nameGroup = match.groups[1] ?: continue
            val receiverGroup = match.groups[3]
            val paramsGroup = match.groups[4] ?: continue
            functions += nameGroup.value
            declarations += nameGroup.range.first

            val names = sequenceOf(receiverGroup?.value.orEmpty(), paramsGroup.value)
                .flatMap { parameter.findAll(it) }
                .mapNotNull { it.groups[1]?.value }
                .toSet()
            val bodyOpen = source.indexOf('{', match.range.last + 1)
            val bodyEnd = if (bodyOpen >= 0) matchingBrace(source, bodyOpen) else match.range.last
            // A receiver is supplied by the call site, so its declaration is
            // marked apart from the parameters the function invents for itself.
            receiverGroup?.let { group ->
                val receiverNames = parameter.findAll(group.value).mapNotNull { it.groups[1]?.value }.toSet()
                parameter.findAll(group.value).mapNotNullTo(contextParameterOffsets) {
                    it.groups[1]?.range?.first?.plus(group.range.first)
                }
                if (receiverNames.isNotEmpty()) {
                    contextScopes += ParameterScope(receiverNames, match.range.first, bodyEnd)
                }
            }
            if (names.isNotEmpty()) scopes += ParameterScope(names, match.range.first, bodyEnd)
            match.groups[2]?.value?.let { genericText ->
                val generics = genericNames(genericText)
                if (generics.isNotEmpty()) genericScopes += GenericScope(generics, match.range.first, bodyEnd)
            }
        }
        for (match in canonicalCallable.findAll(declarationsSource)) {
            val nameGroup = match.groups[2] ?: continue
            val paramsGroup = match.groups[3] ?: continue
            functions += nameGroup.value
            declarations += nameGroup.range.first
            val names = parameter.findAll(paramsGroup.value).mapNotNull { it.groups[1]?.value }.toSet()
            val bodyOpen = source.indexOf('{', match.range.last + 1)
            val bodyEnd = if (bodyOpen >= 0) matchingBrace(source, bodyOpen) else match.range.last
            if (names.isNotEmpty()) scopes += ParameterScope(names, match.range.first, bodyEnd)
            match.groups[1]?.value?.let { genericText ->
                genericNames(genericText).takeIf { it.isNotEmpty() }
                    ?.let { genericScopes += GenericScope(it, match.range.first, bodyEnd) }
            }
        }
        for (match in implicitReceiverCallable.findAll(declarationsSource)) {
            val nameGroup = match.groups[2] ?: continue
            val paramsGroup = match.groups[3] ?: continue
            functions += nameGroup.value
            declarations += nameGroup.range.first
            val bodyOpen = source.indexOf('{', match.range.last + 1)
            val bodyEnd = if (bodyOpen >= 0) matchingBrace(source, bodyOpen) else match.range.last
            val names = parameter.findAll(paramsGroup.value).mapNotNull { it.groups[1]?.value }.toMutableSet()
            names += "self"
            scopes += ParameterScope(names, match.range.first, bodyEnd)
            contextScopes += ParameterScope(setOf("self"), match.range.first, bodyEnd)
            Regex("""\bself\b""").findAll(declarationsSource.substring(match.range.first, bodyEnd.coerceAtMost(declarationsSource.length)))
                .mapTo(contextParameterOffsets) { match.range.first + it.range.first }
            match.groups[1]?.value?.let { genericText ->
                genericNames(genericText).takeIf { it.isNotEmpty() }
                    ?.let { genericScopes += GenericScope(it, match.range.first, bodyEnd) }
            }
        }
        for (match in explicitReceiverCallable.findAll(declarationsSource)) {
            val receiverGroup = match.groups[2] ?: continue
            val nameGroup = match.groups[3] ?: continue
            val paramsGroup = match.groups[4] ?: continue
            functions += nameGroup.value
            declarations += nameGroup.range.first
            val receiverNames = parameter.findAll(receiverGroup.value).mapNotNull { it.groups[1]?.value }.toSet()
            val names = receiverNames + parameter.findAll(paramsGroup.value).mapNotNull { it.groups[1]?.value }
            parameter.findAll(receiverGroup.value).mapNotNullTo(contextParameterOffsets) {
                it.groups[1]?.range?.first?.plus(receiverGroup.range.first)
            }
            val bodyOpen = source.indexOf('{', match.range.last + 1)
            val bodyEnd = if (bodyOpen >= 0) matchingBrace(source, bodyOpen) else match.range.last
            if (names.isNotEmpty()) scopes += ParameterScope(names, match.range.first, bodyEnd)
            if (receiverNames.isNotEmpty()) {
                contextScopes += ParameterScope(receiverNames, match.range.first, bodyEnd)
            }
            match.groups[1]?.value?.let { genericText ->
                genericNames(genericText).takeIf { it.isNotEmpty() }
                    ?.let { genericScopes += GenericScope(it, match.range.first, bodyEnd) }
            }
        }
        for (match in property.findAll(declarationsSource)) {
            val nameGroup = match.groups[1] ?: continue
            properties += nameGroup.value
            propertyDeclarations += nameGroup.range.first
            val receiverGroup = match.groups[3] ?: continue
            val names = parameter.findAll(receiverGroup.value).mapNotNull { it.groups[1]?.value }.toSet()
            parameter.findAll(receiverGroup.value).mapNotNullTo(contextParameterOffsets) {
                it.groups[1]?.range?.first?.plus(receiverGroup.range.first)
            }
            val bodyOpen = source.indexOf('{', match.range.last + 1)
            val lineEnd = source.indexOf('\n', match.range.last + 1).takeIf { it >= 0 } ?: source.length
            val end = if (bodyOpen in (match.range.last + 1)..lineEnd) matchingBrace(source, bodyOpen) else lineEnd
            if (names.isNotEmpty()) scopes += ParameterScope(names, match.range.first, end)
            if (names.isNotEmpty()) contextScopes += ParameterScope(names, match.range.first, end)
        }
        for (match in canonicalProperty.findAll(declarationsSource)) {
            val nameGroup = match.groups[2] ?: continue
            properties += nameGroup.value
            propertyDeclarations += nameGroup.range.first
            val bodyOpen = source.indexOf('{', match.range.last + 1)
            val lineEnd = source.indexOf('\n', match.range.last + 1).takeIf { it >= 0 } ?: source.length
            val end = if (bodyOpen in (match.range.last + 1)..lineEnd) matchingBrace(source, bodyOpen) else lineEnd
            scopes += ParameterScope(setOf("self"), match.range.first, end)
            contextScopes += ParameterScope(setOf("self"), match.range.first, end)
            Regex("""\bself\b""").findAll(declarationsSource.substring(match.range.first, end.coerceAtMost(declarationsSource.length)))
                .mapTo(contextParameterOffsets) { match.range.first + it.range.first }
        }
        for (match in typeDeclaration.findAll(declarationsSource)) {
            val kind = match.groups[1]?.value ?: continue
            val nameGroup = match.groups[2] ?: continue
            declaredTypes += nameGroup.value
            typeDeclarationOffsets += nameGroup.range.first
            if (kind == "spec") {
                specTypes += nameGroup.value
                specTypeDeclarationOffsets += nameGroup.range.first
            }
            val genericText = match.groups[3]?.value ?: continue
            val generics = genericNames(genericText)
            if (generics.isEmpty()) continue
            val bodyOpen = source.indexOf('{', match.range.last + 1)
            val lineEnd = source.indexOf('\n', match.range.last + 1).takeIf { it >= 0 } ?: source.length
            val scopeEnd = if (bodyOpen in (match.range.last + 1)..lineEnd) matchingBrace(source, bodyOpen) else lineEnd
            genericScopes += GenericScope(generics, match.range.first, scopeEnd)
        }
        // `spec Iterator assoc Item` declares `Item` as a type. The same name
        // in an implementation clause and every use in the declaring source
        // keeps that semantic role instead of falling back to a value name.
        Regex("""\bassoc\s+([A-Za-z_][A-Za-z0-9_]*)""")
            .findAll(declarationsSource)
            .mapNotNullTo(associatedTypes) { it.groups[1]?.value }
        declaredTypes += associatedTypes

        val localCases = caseNames(declarationsSource)
        val cases = CaseNames(
            enumNames = localCases.enumNames + visibleEnumCases,
            errorNames = localCases.errorNames + visibleErrorCases,
            enumDeclarations = localCases.enumDeclarations,
            errorDeclarations = localCases.errorDeclarations,
        )

        val specBodies = declarationBodyRanges(declarationsSource, "spec")
        val overrideBodies = declarationBodyRanges(declarationsSource, "impl", requireFor = true)
        val specFunctionDeclarations = declarations.filterTo(linkedSetOf()) { offset -> specBodies.any { offset in it } }
        val overrideFunctionDeclarations = declarations.filterTo(linkedSetOf()) { offset -> overrideBodies.any { offset in it } }
        val specPropertyDeclarations = propertyDeclarations.filterTo(linkedSetOf()) { offset -> specBodies.any { offset in it } }
        val overridePropertyDeclarations = propertyDeclarations.filterTo(linkedSetOf()) { offset -> overrideBodies.any { offset in it } }

        val labelOffsets = linkedSetOf<Int>()
        Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?:for|while|loop)\b""")
            .findAll(declarationsSource).mapNotNullTo(labelOffsets) { it.groups[1]?.range?.first }
        Regex("""\b(?:break|continue)\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\b""")
            .findAll(declarationsSource).mapNotNullTo(labelOffsets) { it.groups[1]?.range?.first }

        // An `import` clause is a path saying where to look and a selection
        // saying what to take, and the separator says which is which: a `.`
        // walks down the module tree, a `::` reaches inside the module it lands
        // on. Only the path is a module chain, so only the path wears the module
        // colour; what is selected is a declaration and reads as one.
        val modulePathOffsets = importPathOffsets(declarationsSource)

        val scopeOffsets = linkedSetOf<Int>()
        Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*::""")
            .findAll(declarationsSource).mapNotNullTo(scopeOffsets) { it.groups[1]?.range?.first }
        Regex("""\bscope\s+((?:[A-Za-z_][A-Za-z0-9_]*\s*::\s*)*[A-Za-z_][A-Za-z0-9_]*)""")
            .findAll(declarationsSource).forEach { scope ->
                val path = scope.groups[1] ?: return@forEach
                Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(path.value)
                    .mapTo(scopeOffsets) { path.range.first + it.range.first }
            }

        return SemanticNames(
            functions, declarations, specFunctionDeclarations, overrideFunctionDeclarations,
            scopes, genericScopes, declaredTypes, typeDeclarationOffsets, specTypes, specTypeDeclarationOffsets,
            labelOffsets, scopeOffsets, modulePathOffsets,
            contextParameterOffsets, contextScopes, properties, propertyDeclarations,
            specPropertyDeclarations, overridePropertyDeclarations,
            associatedTypes, cases.enumNames, cases.errorNames,
            cases.enumDeclarations, cases.errorDeclarations,
        )
    }

    /** Braced bodies of one declaration kind, used only to retain member style. */
    private fun declarationBodyRanges(source: String, keyword: String, requireFor: Boolean = false): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (head in Regex("""\b${Regex.escape(keyword)}\b""").findAll(source)) {
            val open = source.indexOf('{', head.range.last + 1)
            if (open < 0) continue
            val nextDeclaration = Regex(
                """\b(?:func|prop|pack|enum|error|spec|impl|annot|union|typealias)\b""",
            ).find(source, head.range.last + 1)?.range?.first
            if (nextDeclaration != null && nextDeclaration < open) continue
            val header = source.substring(head.range.last + 1, open)
            if (requireFor && !Regex("""\bfor\b""").containsMatchIn(header)) continue
            val close = matchingBrace(source, open)
            if (close > open + 1) ranges += (open + 1) until (close - 1)
        }
        return ranges
    }

    private data class CaseNames(
        val enumNames: Set<String>,
        val errorNames: Set<String>,
        val enumDeclarations: Set<Int>,
        val errorDeclarations: Set<Int>,
    )

    /**
     * Finds the names declared directly by `enum` and `error` bodies while
     * tolerating incomplete payloads and decorators. A case begins an entry at
     * body depth zero; identifiers inside `(payload: Type)` or nested blocks
     * are never cases.
     */
    private fun caseNames(source: String): CaseNames {
        val enumNames = linkedSetOf<String>()
        val errorNames = linkedSetOf<String>()
        val enumDeclarations = linkedSetOf<Int>()
        val errorDeclarations = linkedSetOf<Int>()
        val declaration = Regex("""\b(enum|error|fail)\s+[A-Za-z_][A-Za-z0-9_]*(?:\s*<[^>{}\n]*>)?[^\n{]*\{""")

        for (match in declaration.findAll(source)) {
            val open = source.indexOf('{', match.range.first)
            if (open < 0) continue
            val close = matchingBrace(source, open).coerceAtMost(source.length)
            val isError = match.groups[1]?.value != "enum"
            var index = open + 1
            var depth = 0
            var entryStart = true
            while (index < close - 1) {
                val character = source[index]
                when {
                    character == '\n' && depth == 0 -> {
                        entryStart = true
                        index++
                    }
                    character in setOf(',', ';') && depth == 0 -> {
                        entryStart = true
                        index++
                    }
                    character == '(' || character == '[' || character == '{' -> {
                        depth++
                        index++
                    }
                    character == ')' || character == ']' || character == '}' -> {
                        depth = (depth - 1).coerceAtLeast(0)
                        index++
                    }
                    character == '@' && depth == 0 && entryStart -> {
                        index++
                        while (index < close && (source[index].isIdentPart() || source[index] == ':')) index++
                    }
                    character.isIdentStart() -> {
                        val start = index++
                        while (index < close && source[index].isIdentPart()) index++
                        val name = source.substring(start, index)
                        if (depth == 0 && entryStart && name !in RESERVED_KEYWORDS) {
                            if (isError) {
                                errorNames += name
                                errorDeclarations += start
                            } else {
                                enumNames += name
                                enumDeclarations += start
                            }
                        }
                        if (depth == 0) entryStart = false
                    }
                    else -> index++
                }
            }
        }
        return CaseNames(enumNames, errorNames, enumDeclarations, errorDeclarations)
    }

    /**
     * The offsets of every module-path segment in [source]'s import clauses.
     *
     * A clause reads left to right in one of two modes. It starts in *path*
     * mode, where each `.` steps down the module tree; the first `::` switches
     * it to *selection* mode, where the names are declarations inside the module
     * the path reached. A `::{...}` group keeps each member in selection mode;
     * nested `::` qualifiers are refined separately as scopes.
     */
    private fun importPathOffsets(source: String): Set<Int> {
        val result = linkedSetOf<Int>()
        for (clause in Regex("""(?m)^[ \t]*(?:export[ \t]+|exposed[ \t]+)?(?:import|use|module)\b""").findAll(source)) {
            var i = clause.range.last + 1
            var depth = 0
            // The mode each open group was in, so a comma can put the next
            // member back where the group's own separator left it.
            val opened = ArrayDeque<Boolean>()
            var selecting = false
            while (i < source.length) {
                when {
                    source[i] == '\n' && depth == 0 -> break
                    source.startsWith("::", i) -> { selecting = true; i += 2 }
                    source[i] == '.' -> { selecting = false; i++ }
                    source[i] == '[' || source[i] == '{' -> { opened.addLast(selecting); depth++; i++ }
                    source[i] == ']' || source[i] == '}' -> {
                        selecting = opened.removeLastOrNull() ?: false
                        depth--
                        i++
                        if (depth <= 0) break
                    }
                    source[i] == ',' || source[i] == '\n' -> {
                        selecting = opened.lastOrNull() ?: false
                        i++
                    }
                    source[i].isIdentStart() -> {
                        val start = i
                        while (i < source.length && source[i].isIdentPart()) i++
                        if (!selecting) result.add(start)
                    }
                    else -> i++
                }
            }
        }
        return result
    }

    /**
     * Doc tags whose first word names something the signature also names.
     *
     * `@param capacity` documents a parameter, so `capacity` is a reference and
     * reads as one. `@return`, `@file` and `@since` are followed by prose, and
     * singling out its first word would only suggest a link that is not there.
     */
    private val DOC_NAMING_TAGS = setOf("param", "member", "generic", "receiver", "throws")

    /**
     * Splits `/** … */` between [start] and [end] into `doc`, `docTag` and
     * `docTagValue` spans.
     *
     * A doc comment is one lexical thing but three things to read: the sentence,
     * the `@param` that introduces a clause of it, and the `capacity` that says
     * which parameter the clause is about. Everything not recognized stays
     * `doc`, so the spans tile the comment exactly and no character loses its
     * colour.
     */
    private fun addDocComment(source: String, start: Int, end: Int, spans: MutableList<HighlightSpan>) {
        var run = start
        var i = start

        fun flush(upTo: Int) {
            if (upTo > run) spans.add(HighlightSpan(run, upTo, "doc"))
            run = upTo
        }

        while (i < end) {
            // A tag opens a clause, so it only counts at the start of one: after
            // the `*` that opens a line, never inside a word or an address.
            if (source[i] != '@' || (i > start && !source[i - 1].isWhitespace() && source[i - 1] != '*')) {
                i++
                continue
            }
            var j = i + 1
            while (j < end && source[j].isLetter()) j++
            val tag = source.substring(i + 1, j)
            if (tag.isEmpty()) { i++; continue }
            flush(i)
            spans.add(HighlightSpan(i, j, "docTag"))
            run = j
            i = j
            if (tag !in DOC_NAMING_TAGS) continue
            var k = j
            while (k < end && (source[k] == ' ' || source[k] == '\t')) k++
            var m = k
            while (m < end && source[m].isIdentPart()) m++
            if (m > k) {
                flush(k)
                spans.add(HighlightSpan(k, m, "docTagValue"))
                run = m
                i = m
            }
        }
        flush(end)
    }

    /** Declared names only; bounds/default types in `T: Copy` are not generics. */
    private fun genericNames(text: String): Set<String> = text
        .split(',', '\n')
        .mapNotNull { part ->
            Regex("""^\s*(?:\.\.\.)?([A-Za-z_][A-Za-z0-9_]*)""")
                .find(part)?.groupValues?.get(1)
        }
        .toSet()

    /**
     * End of a tolerant numeric literal beginning at [start].
     *
     * This mirrors the compiler's lexical boundaries without parsing a value:
     * malformed, half-written input must still be highlightable. In particular,
     * `0xFF`, `0o77`, and `0b1010` stay single spans rather than letting their
     * prefix or digits be recolored independently.
     */
    private fun numberEnd(source: String, start: Int, limit: Int): Int {
        var index = start
        // A digit immediately after `.` is a positional member (`tuple.0`),
        // not the start of a real literal. Keep the same boundary as Lexer so
        // `tuple.0.0` remains two member accesses rather than one `0.0` span.
        val afterMemberAccess = start > 0 && source[start - 1] == '.'
        val prefix = source.getOrNull(index + 1)
        val base = if (source[index] == '0') {
            when (prefix) {
                'x', 'X' -> 16
                'o', 'O' -> 8
                'b', 'B' -> 2
                else -> 10
            }
        } else {
            10
        }

        if (base != 10) index += 2
        fun digit(character: Char): Boolean = when (base) {
            16 -> character.isDigit() || character in 'a'..'f' || character in 'A'..'F'
            8 -> character in '0'..'7'
            2 -> character == '0' || character == '1'
            else -> character.isDigit()
        }
        while (index < limit && (digit(source[index]) || source[index] == '_')) index++

        if (base == 10 && !afterMemberAccess) {
            if (index + 1 < limit && source[index] == '.' && source[index + 1].isDigit()) {
                index++
                while (index < limit && (source[index].isDigit() || source[index] == '_')) index++
            } else if (
                index < limit && source[index] == '.' &&
                (index + 1 >= limit || (
                    source[index + 1] != '.' &&
                        !source[index + 1].isLetter() &&
                        source[index + 1] != '_'
                    ))
            ) {
                // `2.` is a real literal, while `2..5` is a range and
                // `2.toString` is integer member access.
                index++
            }
            if (index < limit && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < limit && (source[index] == '+' || source[index] == '-')) index++
                while (index < limit && (source[index].isDigit() || source[index] == '_')) index++
            }
        }
        return index
    }

    /** `Int` at the end of `Int` becomes the compiler's `std__Int` key. */
    private fun qualifiedNameAt(source: String, name: String, offset: Int): String {
        val parts = mutableListOf(name)
        var cursor = offset
        while (cursor >= 2 && source[cursor - 1] == ':' && source[cursor - 2] == ':') {
            var end = cursor - 2
            var start = end
            while (start > 0 && source[start - 1].isIdentPart()) start--
            if (start == end) break
            parts.add(0, source.substring(start, end))
            cursor = start
        }
        return parts.joinToString("__")
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
