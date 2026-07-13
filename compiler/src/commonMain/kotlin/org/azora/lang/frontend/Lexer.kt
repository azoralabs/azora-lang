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
            "mixin" to TokenType.MIXIN,
            "panic" to TokenType.PANIC,
            "for" to TokenType.FOR,
            "while" to TokenType.WHILE,
            "loop" to TokenType.LOOP,
            "in" to TokenType.IN,
            "break" to TokenType.BREAK,
            "continue" to TokenType.CONTINUE,
            "by" to TokenType.BY,
            "reverse" to TokenType.REVERSE,
            "infx" to TokenType.INFX,
            "oper" to TokenType.OPER,
            "deco" to TokenType.DECO,
            "fail" to TokenType.FAIL,
            "alloc" to TokenType.ALLOC,
            "drop" to TokenType.DROP,
            "deref" to TokenType.DEREF,
            "unsafe" to TokenType.UNSAFE,
            "isolated" to TokenType.ISOLATED,
            "flow" to TokenType.FLOW,
            "yield" to TokenType.YIELD,
            "task" to TokenType.TASK,
            "await" to TokenType.AWAIT,
            "launch" to TokenType.LAUNCH,
            "bridge" to TokenType.BRIDGE,
            "solo" to TokenType.SOLO,
            "inject" to TokenType.INJECT,
            "wrap" to TokenType.WRAP,
            "rescue" to TokenType.RESCUE,
            "node" to TokenType.NODE,
            "leaf" to TokenType.LEAF,
            "repl" to TokenType.REPL,
            "virt" to TokenType.VIRT,
            "base" to TokenType.BASE,
            "mem" to TokenType.MEM,
            "rem" to TokenType.REM,
            "ret" to TokenType.RET,
            "effect" to TokenType.EFFECT,
            "view" to TokenType.VIEW,
            "hook" to TokenType.HOOK,
            "prop" to TokenType.PROP,
            "ctor" to TokenType.CTOR,
            "dtor" to TokenType.DTOR,
            "flip" to TokenType.FLIP,
            "flop" to TokenType.FLOP,
            "ref" to TokenType.REF,
            "out" to TokenType.OUT,
            "mut" to TokenType.MUT,
            "shared" to TokenType.SHARED,
            "weak" to TokenType.WEAK,
            "expose" to TokenType.EXPOSE,
            "confine" to TokenType.CONFINE,
            "protect" to TokenType.PROTECT,
            "shield" to TokenType.SHIELD,
            "protected" to TokenType.PROTECT,
            "module" to TokenType.MODULE,
            "threadlocal" to TokenType.THREADLOCAL,
            "pack" to TokenType.PACK,
            "enum" to TokenType.ENUM,
            "when" to TokenType.WHEN,
            "throw" to TokenType.THROW,
            "try" to TokenType.TRY,
            "catch" to TokenType.CATCH,
            "impl" to TokenType.IMPL,
            "spec" to TokenType.SPEC,
            "defer" to TokenType.DEFER,
            "typealias" to TokenType.TYPEALIAS,
            "slot" to TokenType.SLOT,
            "as" to TokenType.AS,
            "guard" to TokenType.GUARD,
            "is" to TokenType.IS,
            "null" to TokenType.NULL,
            "use" to TokenType.USE,
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

    /** Token types that make a trailing newline a continuation, not a terminator. */
    private val continuationTypes = setOf(
        TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH, TokenType.PERCENT,
        TokenType.AND_AND, TokenType.OR_OR,
        TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL,
        TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL,
        TokenType.AMP, TokenType.PIPE, TokenType.CARET,
        TokenType.SHIFT_LEFT, TokenType.SHIFT_RIGHT,
        TokenType.EQUAL, TokenType.COMMA, TokenType.DOT, TokenType.ARROW,
        TokenType.QMARK_QMARK
    )

    private fun shouldEmitNewline(): Boolean {
        if (tokens.isEmpty()) return false
        val last = tokens.last().type
        return last != TokenType.NEWLINE && last !in continuationTypes
    }

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
            '{' -> addToken(TokenType.L_BRACE)  // braces don't suppress newlines (statement blocks)
            '}' -> addToken(TokenType.R_BRACE)
            '[' -> { bracketDepth++; addToken(TokenType.L_BRACKET) }
            ']' -> { if (bracketDepth > 0) bracketDepth--; addToken(TokenType.R_BRACKET) }
            ',' -> addToken(TokenType.COMMA)
            '@' -> addToken(TokenType.AT)
            '.' -> when {
                match('.') -> if (match('.')) addToken(TokenType.ELLIPSIS) else addToken(if (match('<')) TokenType.DOT_DOT_LESS else TokenType.DOT_DOT)
                else -> addToken(TokenType.DOT)
            }
            ':' -> addToken(if (match(':')) TokenType.DOUBLE_COLON else TokenType.COLON)
            '+' -> addToken(if (match('=')) TokenType.PLUS_EQUAL else if (match('+')) TokenType.PLUS_PLUS else TokenType.PLUS)
            '*' -> addToken(if (match('=')) TokenType.STAR_EQUAL else TokenType.STAR)
            '%' -> addToken(if (match('=')) TokenType.PERCENT_EQUAL else TokenType.PERCENT)
            '!' -> addToken(if (match('=')) TokenType.BANG_EQUAL else TokenType.BANG)
            '=' -> addToken(if (match('=')) TokenType.EQUAL_EQUAL else TokenType.EQUAL)
            '<' -> when {
                match('=') -> addToken(TokenType.LESS_EQUAL)
                match('<') -> addToken(TokenType.SHIFT_LEFT)
                else -> addToken(TokenType.LESS)
            }
            '>' -> when {
                match('=') -> addToken(TokenType.GREATER_EQUAL)
                match('>') -> addToken(TokenType.SHIFT_RIGHT)
                else -> addToken(TokenType.GREATER)
            }
            '&' -> if (match('&')) addToken(TokenType.AND_AND) else addToken(TokenType.AMP)
            '|' -> if (match('|')) addToken(TokenType.OR_OR) else addToken(TokenType.PIPE)
            '^' -> addToken(TokenType.CARET)
            '~' -> addToken(TokenType.TILDE)
            '?' -> scanNullableOp()
            '-' -> when {
                match('>') -> addToken(TokenType.ARROW)
                match('=') -> addToken(TokenType.MINUS_EQUAL)
                match('-') -> addToken(TokenType.MINUS_MINUS)
                else -> addToken(TokenType.MINUS)
            }
            '/' -> {
                when {
                    match('/') -> while (!isAtEnd() && peek() != '\n') advance()
                    match('*') -> skipBlockComment()
                    match('=') -> addToken(TokenType.SLASH_EQUAL)
                    else -> addToken(TokenType.SLASH)
                }
            }
            '"' -> {
                // Triple-quoted raw string `"""…"""` vs. regular `"…"`.
                if (!isAtEnd(1) && source[current] == '"' && !isAtEnd(2) && source[current + 1] == '"') {
                    scanRawString()
                } else {
                    scanString()
                }
            }
            '\'' -> scanCharLiteral()
            '\n' -> {
                if (bracketDepth <= 0 && shouldEmitNewline()) {
                    addToken(TokenType.NEWLINE)
                }
                line++; column = 1
            }
            '\r' -> {
                if (!isAtEnd() && peek() == '\n') advance()
                if (bracketDepth <= 0 && shouldEmitNewline()) {
                    addToken(TokenType.NEWLINE)
                }
                line++; column = 1
            }
            ' ', '\t' -> {}
            else -> when {
                c.isDigit() -> scanNumber()
                c.isLetter() || c == '_' || c == '$' -> scanIdentifier()
                else -> error("Unexpected character '$c' at line $line")
            }
        }
    }

    /**
     * Scans a triple-quoted raw string `"""…"""`. The content is taken literally —
     * backslash escapes are NOT processed and newlines are preserved — until the
     * closing `"""`. Emitted as a normal [TokenType.STRING_LITERAL] so the parser
     * and all backends (which already escape string values) handle it unchanged.
     *
     * The opening `"` has already been consumed by [scanToken]; this consumes the
     * remaining two `"` of the opening delimiter.
     */
    private fun scanRawString() {
        advance() // second '"' of opening delimiter
        advance() // third '"' of opening delimiter
        val sb = StringBuilder()
        while (!isAtEnd()) {
            if (peek() == '"' && !isAtEnd(1) && source[current + 1] == '"' &&
                !isAtEnd(2) && source[current + 2] == '"') {
                advance(); advance(); advance() // closing """
                tokens.add(Token(TokenType.STRING_LITERAL, source.substring(start, current), line, startColumn, sb.toString()))
                return
            }
            val c = advance()
            if (c == '\n') { line++; column = 1 }
            sb.append(c)
        }
        error("Unterminated raw string at line $line")
    }

    private fun scanString() {
        val sb = StringBuilder()
        val parts = mutableListOf<StringPart>()
        var hasInterpolation = false

        fun flushLiteral() {
            if (sb.isNotEmpty()) {
                parts.add(StringPart.Literal(sb.toString()))
                sb.clear()
            }
        }

        while (!isAtEnd() && peek() != '"') {
            when {
                peek() == '\\' -> {
                    advance() // consume backslash
                    when (if (!isAtEnd()) advance() else ' ') {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        '$' -> sb.append('$')
                        else -> sb.append('?')
                    }
                }
                peek() == '$' -> {
                    hasInterpolation = true
                    flushLiteral()
                    advance() // consume '$'
                    if (!isAtEnd() && peek() == '{') {
                        advance() // consume '{'
                        val exprSrc = StringBuilder()
                        var depth = 1
                        while (!isAtEnd() && depth > 0) {
                            val c = advance()
                            if (c == '\n') { line++; column = 1 }
                            when {
                                c == '{' -> depth++
                                c == '}' -> { depth--; if (depth == 0) break }
                            }
                            if (depth > 0) exprSrc.append(c)
                        }
                        if (depth > 0) error("Unterminated interpolation in string at line $line")
                        parts.add(StringPart.Expr(exprSrc.toString()))
                    } else {
                        // $identifier
                        val ident = StringBuilder()
                        while (!isAtEnd() && (peek().isLetterOrDigit() || peek() == '_')) {
                            ident.append(advance())
                        }
                        if (ident.isEmpty()) {
                            sb.append('$') // lone '$' — treat as literal
                        } else {
                            parts.add(StringPart.Expr(ident.toString()))
                        }
                    }
                }
                else -> {
                    val ch = advance()
                    if (ch == '\n') { line++; column = 1 }
                    sb.append(ch)
                }
            }
        }
        if (isAtEnd()) error("Unterminated string at line $line")
        advance() // closing "
        val lexeme = source.substring(start, current)
        if (hasInterpolation) {
            flushLiteral()
            tokens.add(Token(TokenType.INTERPOLATED_STRING, lexeme, line, startColumn, parts))
        } else {
            tokens.add(Token(TokenType.STRING_LITERAL, lexeme, line, startColumn, sb.toString()))
        }
    }

    /**
     * Scans a `?`-led operator. Beyond the plain nullable marker (`?`), this
     * recognizes the null-conditional compound assignment operators
     * (`?=` `?+=` `?-=` `?*=` `?/=` `?%=`) and the null-conditional
     * increment/decrement operators (`?++` `?--`), as well as `??` and `?.`.
     *
     * The leading `?` has already been consumed by [scanToken] when this is
     * called, so [start] points at it and [current] is just past it.
     */
    private fun scanNullableOp() {
        when {
            match('?') -> addToken(TokenType.QMARK_QMARK)
            match('.') -> addToken(TokenType.QMARK_DOT)
            match('=') -> addToken(TokenType.QMARK_EQUAL)
            !isAtEnd() && peek() in "+-*/%" -> {
                val opChar = peek()
                val afterOp = if (!isAtEnd(1)) source[current + 1] else ' '
                when {
                    // ?+= ?-= ?*= ?/= ?%=  — compound null-conditional assignment
                    afterOp == '=' -> {
                        advance() // op char
                        advance() // '='
                        addToken(when (opChar) {
                            '+' -> TokenType.QMARK_PLUS_EQUAL
                            '-' -> TokenType.QMARK_MINUS_EQUAL
                            '*' -> TokenType.QMARK_STAR_EQUAL
                            '/' -> TokenType.QMARK_SLASH_EQUAL
                            '%' -> TokenType.QMARK_PERCENT_EQUAL
                            else -> error("unreachable nullable compound op")
                        })
                    }
                    // ?++ / ?-- — null-conditional inc/dec
                    opChar == '+' && afterOp == '+' -> { advance(); advance(); addToken(TokenType.QMARK_PLUS_PLUS) }
                    opChar == '-' && afterOp == '-' -> { advance(); advance(); addToken(TokenType.QMARK_MINUS_MINUS) }
                    // Lone '?' followed by an operator char that isn't part of a
                    // null-conditional op (e.g. `a ? b : c` style, or `T?` then `+`).
                    else -> addToken(TokenType.QMARK)
                }
            }
            else -> addToken(TokenType.QMARK)
        }
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

        // Decimal point (only for base-10). In member-access position (`obj.0.0`)
        // the previous token is DOT — scan an integer so the `.0` splits into two
        // tuple accesses instead of being swallowed as a REAL_LITERAL `0.0`.
        var isFloat = false
        val afterMemberAccess = tokens.lastOrNull()?.type == TokenType.DOT
        if (base == 10 && !afterMemberAccess && !isAtEnd() && peek() == '.' && !isAtEnd(1) && source[current + 1].isDigit()) {
            isFloat = true
            advance() // consume '.'
            while (!isAtEnd() && (peek().isDigit() || peek() == '_')) advance()
        }

        // Scientific notation (only for base-10 floats or integers)
        if (base == 10 && !afterMemberAccess && !isAtEnd() && (peek() == 'e' || peek() == 'E')) {
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
        while (!isAtEnd() && (peek().isLetterOrDigit() || peek() == '_' || peek() == '$')) advance()
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
