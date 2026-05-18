/*
 * Copyright 2026 AzoraTech
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

package org.azora.lang.frontend

/**
 * Lexer (tokenizer) for the Azora language.
 *
 * Converts raw source text into a list of [Token]s. The lexer handles:
 * - Keywords and identifiers
 * - Integer and floating-point literals
 * - String literals with escape sequences (`\n`, `\t`, `\r`, `\\`, `\"`)
 * - Single-line (`//`) and nested block (`/* */`) comments
 * - Significant newlines as statement terminators (suppressed inside brackets)
 * - All operator and delimiter tokens defined in [TokenType]
 *
 * @param source the complete source text to tokenize
 */
class Lexer(private val source: String) {

    private val tokens = mutableListOf<Token>()
    private var start = 0
    private var current = 0
    private var line = 1
    private var column = 1
    private var startColumn = 1
    private var bracketDepth = 0

    companion object {
        private val keywords = mapOf(
            "var" to TokenType.VAR,
            "fin" to TokenType.FIN,
            "let" to TokenType.LET,
            "func" to TokenType.FUNC,
            "return" to TokenType.RETURN,
            "package" to TokenType.PACKAGE,
            "if" to TokenType.IF,
            "else" to TokenType.ELSE,
            "inline" to TokenType.INLINE,
            "deepinline" to TokenType.DEEPINLINE,
            "noinline" to TokenType.NOINLINE,
            "zone" to TokenType.ZONE,
            "friend" to TokenType.FRIEND,
            "test" to TokenType.TEST,
            "assert" to TokenType.ASSERT,
            "trace" to TokenType.TRACE,
            "true" to TokenType.TRUE,
            "false" to TokenType.FALSE
        )
    }

    /**
     * Scans the entire source text and returns the resulting token list.
     *
     * The returned list always ends with a [TokenType.EOF] token.
     *
     * @return the list of tokens produced from the source
     */
    fun tokenize(): List<Token> {
        while (!isAtEnd()) {
            start = current
            startColumn = column
            scanToken()
        }
        tokens.add(Token(TokenType.EOF, "", line, column))
        return tokens
    }

    private fun scanToken() {
        when (val c = advance()) {
            '(' -> { bracketDepth++; addToken(TokenType.L_PAREN) }
            ')' -> { if (bracketDepth > 0) bracketDepth--; addToken(TokenType.R_PAREN) }
            '{' -> { bracketDepth++; addToken(TokenType.L_BRACE) }
            '}' -> { if (bracketDepth > 0) bracketDepth--; addToken(TokenType.R_BRACE) }
            ',' -> addToken(TokenType.COMMA)
            ':' -> addToken(if (match(':')) TokenType.DOUBLE_COLON else TokenType.COLON)
            '+' -> addToken(TokenType.PLUS)
            '*' -> addToken(TokenType.STAR)
            '%' -> addToken(TokenType.PERCENT)
            '!' -> addToken(if (match('=')) TokenType.BANG_EQUAL else TokenType.BANG)
            '=' -> addToken(if (match('=')) TokenType.EQUAL_EQUAL else TokenType.EQUAL)
            '<' -> addToken(if (match('=')) TokenType.LESS_EQUAL else TokenType.LESS)
            '>' -> addToken(if (match('=')) TokenType.GREATER_EQUAL else TokenType.GREATER)
            '&' -> if (match('&')) addToken(TokenType.AND_AND)
            '|' -> if (match('|')) addToken(TokenType.OR_OR)
            '-' -> addToken(if (match('>')) TokenType.ARROW else TokenType.MINUS)
            '/' -> {
                when {
                    match('/') -> while (!isAtEnd() && peek() != '\n') advance()
                    match('*') -> skipBlockComment()
                    else -> addToken(TokenType.SLASH)
                }
            }
            '"' -> scanString()
            '\'' -> scanCharLiteral()
            '\n' -> {
                if (bracketDepth <= 0) {
                    if (tokens.isEmpty() || tokens.last().type != TokenType.NEWLINE) {
                        addToken(TokenType.NEWLINE)
                    }
                }
                line++; column = 1
            }
            '\r' -> {
                if (!isAtEnd() && peek() == '\n') advance()
                if (bracketDepth <= 0) {
                    if (tokens.isEmpty() || tokens.last().type != TokenType.NEWLINE) {
                        addToken(TokenType.NEWLINE)
                    }
                }
                line++; column = 1
            }
            ' ', '\t' -> {}
            else -> when {
                c.isDigit() -> scanNumber()
                c.isLetter() || c == '_' -> scanIdentifier()
                else -> error("Unexpected character '$c' at line $line")
            }
        }
    }

    private fun scanString() {
        val sb = StringBuilder()
        while (!isAtEnd() && peek() != '"') {
            if (peek() == '\\') {
                advance()
                when (if (!isAtEnd()) advance() else '\u0000') {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    else -> sb.append('?')
                }
            } else {
                val ch = advance()
                if (ch == '\n') { line++; column = 1 }
                sb.append(ch)
            }
        }
        if (isAtEnd()) error("Unterminated string at line $line")
        advance() // closing "
        tokens.add(Token(TokenType.STRING_LITERAL, source.substring(start, current), line, startColumn, sb.toString()))
    }

    private fun scanCharLiteral() {
        if (isAtEnd()) error("Unterminated character literal at line $line")
        val ch: Char
        if (peek() == '\\') {
            advance() // consume '\'
            if (isAtEnd()) error("Unterminated character literal at line $line")
            ch = when (val esc = advance()) {
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                '\\' -> '\\'
                '\'' -> '\''
                '0' -> '\u0000'
                'u' -> {
                    val hex = StringBuilder()
                    repeat(4) {
                        if (isAtEnd()) error("Incomplete \\u escape in character literal at line $line")
                        hex.append(advance())
                    }
                    hex.toString().toInt(16).toChar()
                }
                else -> error("Unknown escape sequence '\\$esc' in character literal at line $line")
            }
        } else {
            ch = advance()
        }
        if (isAtEnd() || peek() != '\'') error("Unterminated character literal at line $line")
        advance() // closing '
        tokens.add(Token(TokenType.CHAR_LITERAL, source.substring(start, current), line, startColumn, ch))
    }

    private fun scanNumber() {
        var base = 10
        var isHex = false

        // Check for base prefix: 0x, 0o, 0b
        if (source[start] == '0' && !isAtEnd()) {
            when {
                !isAtEnd() && (peek() == 'x' || peek() == 'X') -> { advance(); base = 16; isHex = true }
                !isAtEnd() && (peek() == 'o' || peek() == 'O') -> { advance(); base = 8 }
                !isAtEnd() && (peek() == 'b' || peek() == 'B') -> { advance(); base = 2 }
            }
        }

        // Scan integer digits (with underscores)
        when (base) {
            16 -> while (!isAtEnd() && (peek().isHexDigit() || peek() == '_')) advance()
            8 -> while (!isAtEnd() && ((peek() in '0'..'7') || peek() == '_')) advance()
            2 -> while (!isAtEnd() && (peek() == '0' || peek() == '1' || peek() == '_')) advance()
            else -> while (!isAtEnd() && (peek().isDigit() || peek() == '_')) advance()
        }

        // Decimal point (only for base-10)
        var isFloat = false
        if (base == 10 && !isAtEnd() && peek() == '.' && !isAtEnd(1) && source[current + 1].isDigit()) {
            isFloat = true
            advance() // consume '.'
            while (!isAtEnd() && (peek().isDigit() || peek() == '_')) advance()
        }

        // Scientific notation (only for base-10 floats or integers)
        if (base == 10 && !isAtEnd() && (peek() == 'e' || peek() == 'E')) {
            isFloat = true
            advance() // consume 'e'/'E'
            if (!isAtEnd() && (peek() == '+' || peek() == '-')) advance()
            while (!isAtEnd() && (peek().isDigit() || peek() == '_')) advance()
        }

        // Scan type suffix
        val suffix = scanNumericSuffix(isHex)

        val text = source.substring(start, current)
        // Strip the suffix characters from the numeric text for parsing
        val numText = text.substringBefore(suffixLexeme(suffix, isHex)).replace("_", "")

        if (isFloat || suffix == NumericSuffix.FLOAT || suffix == NumericSuffix.DECIMAL) {
            // Parse as floating-point
            val numericText = numText.replace("_", "")
            val value = numericText.toDouble()
            tokens.add(Token(TokenType.REAL_LITERAL, text, line, startColumn, NumericLiteral(value, suffix)))
        } else {
            // Parse as integer
            val numericText = numText.replace("_", "")
            val value = when (base) {
                16 -> numericText.removePrefix("0x").removePrefix("0X").toLong(16)
                8 -> numericText.removePrefix("0o").removePrefix("0O").toLong(8)
                2 -> numericText.removePrefix("0b").removePrefix("0B").toLong(2)
                else -> numericText.toLong()
            }
            tokens.add(Token(TokenType.INT_LITERAL, text, line, startColumn, NumericLiteral(value, suffix)))
        }
    }

    private fun scanNumericSuffix(isHex: Boolean): NumericSuffix {
        if (isAtEnd()) return NumericSuffix.NONE
        // Order matters — check multi-char suffixes first
        // 'us' and 'uL' and 'uc' and 'ub'
        if (!isAtEnd() && peek() == 'u') {
            if (!isAtEnd(1)) {
                val next = source[current + 1]
                if (next == 's') { advance(); advance(); column++; return NumericSuffix.USHORT }
                if (next == 'L') { advance(); advance(); column++; return NumericSuffix.ULONG }
                // 'uc' only in non-hex mode (c is a hex digit)
                if (!isHex && next == 'c') { advance(); advance(); column++; return NumericSuffix.UCENT }
                // 'ub' only in non-hex mode (b is a hex digit)
                if (!isHex && next == 'b') { advance(); advance(); column++; return NumericSuffix.UBYTE }
            }
            // Standalone 'u' — must check after multi-char suffixes starting with 'u'
            // Only match if next char is NOT a letter/digit (i.e. end of number token)
            if (isAtEnd(1) || !source[current + 1].isLetterOrDigit()) {
                advance(); return NumericSuffix.UINT
            }
        }
        // Single char suffixes
        when {
            !isAtEnd() && peek() == 's' -> { advance(); return NumericSuffix.SHORT }
            !isAtEnd() && peek() == 'L' -> { advance(); return NumericSuffix.LONG }
            !isAtEnd() && peek() == 'D' -> { advance(); return NumericSuffix.DECIMAL }
            // 'b', 'c' and 'f' only in non-hex mode (they are hex digits)
            !isHex && !isAtEnd() && peek() == 'b' -> { advance(); return NumericSuffix.BYTE }
            !isHex && !isAtEnd() && peek() == 'c' -> { advance(); return NumericSuffix.CENT }
            !isHex && !isAtEnd() && peek() == 'f' -> { advance(); return NumericSuffix.FLOAT }
        }
        return NumericSuffix.NONE
    }

    private fun suffixLexeme(suffix: NumericSuffix, isHex: Boolean): String = when (suffix) {
        NumericSuffix.NONE -> "\u0000NONE" // sentinel that won't match
        NumericSuffix.BYTE -> "b"
        NumericSuffix.UBYTE -> "ub"
        NumericSuffix.SHORT -> "s"
        NumericSuffix.USHORT -> "us"
        NumericSuffix.UINT -> "u"
        NumericSuffix.LONG -> "L"
        NumericSuffix.ULONG -> "uL"
        NumericSuffix.CENT -> "c"
        NumericSuffix.UCENT -> "uc"
        NumericSuffix.FLOAT -> "f"
        NumericSuffix.DECIMAL -> "D"
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun scanIdentifier() {
        while (!isAtEnd() && (peek().isLetterOrDigit() || peek() == '_')) advance()
        val text = source.substring(start, current)
        addToken(keywords[text] ?: TokenType.IDENTIFIER)
    }

    private fun skipBlockComment() {
        var depth = 1
        while (!isAtEnd() && depth > 0) {
            when {
                peek() == '/' && !isAtEnd(1) && source[current + 1] == '*' -> { advance(); advance(); depth++ }
                peek() == '*' && !isAtEnd(1) && source[current + 1] == '/' -> { advance(); advance(); depth-- }
                peek() == '\n' -> { advance(); line++; column = 1 }
                else -> advance()
            }
        }
    }

    private fun addToken(type: TokenType) {
        tokens.add(Token(type, source.substring(start, current), line, startColumn))
    }

    private fun advance(): Char {
        val c = source[current++]
        column++
        return c
    }
    private fun peek(): Char = source[current]
    private fun isAtEnd(offset: Int = 0) = current + offset >= source.length
    private fun match(expected: Char): Boolean {
        if (isAtEnd() || source[current] != expected) return false
        current++
        return true
    }
}
