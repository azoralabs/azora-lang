// Port of Lexer.kt (and AzoraSyntaxVocabulary.kt's keyword table).
#include "frontend/lexer.h"

#include "base/chars.h"
#include "base/failure.h"
#include "base/numbers.h"
#include "base/text.h"

namespace azora {

const OrderedMap<std::string, TokenType>& reservedKeywords() {
    static const OrderedMap<std::string, TokenType> keywords = [] {
        OrderedMap<std::string, TokenType> m;
        const std::pair<const char*, TokenType> entries[] = {
            {"var", TokenType::VAR},
            {"fin", TokenType::FIN},
            {"let", TokenType::LET},
            {"val", TokenType::VAL},
            {"func", TokenType::FUNC},
            {"return", TokenType::RETURN},
            {"if", TokenType::IF},
            {"else", TokenType::ELSE},
            {"inline", TokenType::INLINE},
            {"deepinline", TokenType::DEEPINLINE},
            {"noinline", TokenType::NOINLINE},
            {"scope", TokenType::SCOPE},
            {"test", TokenType::TEST},
            {"assert", TokenType::ASSERT},
            {"trace", TokenType::TRACE},
            {"panic", TokenType::PANIC},
            {"macro", TokenType::MACRO},
            {"for", TokenType::FOR},
            {"while", TokenType::WHILE},
            {"loop", TokenType::LOOP},
            {"in", TokenType::IN},
            {"break", TokenType::BREAK},
            {"continue", TokenType::CONTINUE},
            {"then", TokenType::THEN},
            {"by", TokenType::BY},
            {"with", TokenType::WITH},
            {"without", TokenType::WITHOUT},
            {"oper", TokenType::OPER},
            {"annot", TokenType::ANNOT},
            {"__int", TokenType::PRIM_INT},
            {"__uint", TokenType::PRIM_UINT},
            {"__float", TokenType::PRIM_FLOAT},
            {"bind", TokenType::BIND},
            {"error", TokenType::ERROR},
            {"alloc", TokenType::ALLOC},
            {"purge", TokenType::PURGE},
            {"unsafe", TokenType::UNSAFE},
            {"take", TokenType::TAKE},
            {"await", TokenType::AWAIT},
            {"delay", TokenType::DELAY},
            {"bridge", TokenType::BRIDGE},
            {"solo", TokenType::SOLO},
            {"scoped", TokenType::SCOPED},
            {"direct", TokenType::DIRECT},
            {"inject", TokenType::INJECT},
            {"lazy", TokenType::LAZY},
            {"factory", TokenType::FACTORY},
            {"derive", TokenType::DERIVE},
            {"graph", TokenType::GRAPH},
            {"rescue", TokenType::RESCUE},
            {"preserve", TokenType::PRESERVE},
            {"react", TokenType::REACT},
            {"remember", TokenType::REMEMBER},
            {"retain", TokenType::RETAIN},
            {"effect", TokenType::EFFECT},
            {"prop", TokenType::PROP},
            {"ctor", TokenType::CTOR},
            {"dtor", TokenType::DTOR},
            {"out", TokenType::OUT},
            {"exposed", TokenType::EXPOSED},
            {"protected", TokenType::PROTECTED},
            {"confined", TokenType::CONFINED},
            {"threadlocal", TokenType::THREADLOCAL},
            {"pack", TokenType::PACK},
            {"enum", TokenType::ENUM},
            {"when", TokenType::WHEN},
            {"throw", TokenType::THROW},
            {"try", TokenType::TRY},
            {"catch", TokenType::CATCH},
            {"impl", TokenType::IMPL},
            {"spec", TokenType::SPEC},
            {"defer", TokenType::DEFER},
            {"typealias", TokenType::TYPEALIAS},
            {"variant", TokenType::VARIANT},
            {"module", TokenType::MODULE},
            {"union", TokenType::UNION},
            {"async", TokenType::ASYNC},
            {"where", TokenType::WHERE},
            {"escaping", TokenType::ESCAPING},
            {"derives", TokenType::DERIVES},
            {"includes", TokenType::INCLUDES},
            {"binds", TokenType::BINDS},
            {"requires", TokenType::REQUIRES},
            {"assoc", TokenType::ASSOC},
            {"lend", TokenType::LEND},
            {"seal", TokenType::SEAL},
            {"as", TokenType::AS},
            {"is", TokenType::IS},
            {"null", TokenType::NULL_},
            {"import", TokenType::IMPORT},
            {"using", TokenType::USING},
            {"true", TokenType::TRUE_},
            {"false", TokenType::FALSE_},
        };
        for (const auto& [word, type] : entries) m.set(word, type);
        return m;
    }();
    return keywords;
}

namespace {

/** Longest first, so `uL` is never read as `u` followed by a name. */
const Lexer::WidthSuffix WIDTH_SUFFIXES[] = {
    {u"ub", "UByte", true},
    {u"us", "UShort"},
    {u"uL", "ULong"},
    {u"uc", "UCent", true},
    {u"u", "UInt"},
    {u"b", "Byte", true},
    {u"s", "Short"},
    {u"L", "Long"},
    {u"c", "Cent", true},
    {u"f", "Float", true},
    {u"D", "Quad"},
};

/** Token types that make a trailing newline a continuation, not a terminator. */
bool isContinuationType(TokenType t) {
    switch (t) {
        case TokenType::PLUS: case TokenType::MINUS: case TokenType::STAR: case TokenType::SLASH:
        case TokenType::PERCENT: case TokenType::AND_AND: case TokenType::OR_OR:
        case TokenType::EQUAL_EQUAL: case TokenType::BANG_EQUAL:
        case TokenType::LESS: case TokenType::LESS_EQUAL: case TokenType::GREATER: case TokenType::GREATER_EQUAL:
        case TokenType::AMP: case TokenType::PIPE: case TokenType::CARET:
        case TokenType::SHIFT_LEFT: case TokenType::SHIFT_RIGHT:
        case TokenType::EQUAL: case TokenType::COMMA: case TokenType::DOT: case TokenType::ARROW:
        case TokenType::FAT_ARROW: case TokenType::QMARK_QMARK:
            return true;
        default:
            return false;
    }
}

bool isHexDigitChar(char16_t c) { return (c >= u'0' && c <= u'9') || (c >= u'a' && c <= u'f') || (c >= u'A' && c <= u'F'); }

/** Kotlin `"$c"` for one Char. */
std::string charText(char16_t c) { return encodeUtf8(std::u16string_view(&c, 1)); }

}  // namespace

Lexer::Lexer(std::string_view sourceUtf8) : source(decodeUtf8(sourceUtf8)) {}

bool Lexer::shouldEmitNewline() const {
    if (tokens.empty()) return false;
    TokenType last = tokens.back().type;
    // `x.&` is a complete shared borrow, never a bitwise-and waiting for its
    // right side: `let r: T& = x.&` ends its line like any other statement.
    if (last == TokenType::AMP && tokens.size() >= 2 && tokens[tokens.size() - 2].type == TokenType::DOT) return true;
    return last != TokenType::NEWLINE && !isContinuationType(last);
}

std::vector<Token> Lexer::tokenize() {
    while (!isAtEnd()) {
        start = current;
        startColumn = column;
        scanToken();
    }
    tokens.push_back(Token{TokenType::EOF_, "", line, column, {}});
    return std::move(tokens);
}

void Lexer::scanToken() {
    char16_t c = advance();
    switch (c) {
        case u'(': bracketDepth++; addToken(TokenType::L_PAREN); break;
        case u')': if (bracketDepth > 0) bracketDepth--; addToken(TokenType::R_PAREN); break;
        // A brace opens a statement block, where newlines separate statements
        // again even inside an enclosing `(`/`[`. Without resetting the depth a
        // lambda written inline as an argument - `run({ a() \n b() })` - would
        // have its newlines swallowed by the call's parentheses and its
        // statements joined into one expression.
        case u'{': enclosingBracketDepths.push_back(bracketDepth); bracketDepth = 0; addToken(TokenType::L_BRACE); break;
        case u'}':
            if (enclosingBracketDepths.empty()) {
                bracketDepth = 0;
            } else {
                bracketDepth = enclosingBracketDepths.back();
                enclosingBracketDepths.pop_back();
            }
            addToken(TokenType::R_BRACE);
            break;
        case u'[': bracketDepth++; addToken(TokenType::L_BRACKET); break;
        case u']': if (bracketDepth > 0) bracketDepth--; addToken(TokenType::R_BRACKET); break;
        case u',': addToken(TokenType::COMMA); break;
        case u';': addToken(TokenType::SEMICOLON); break;
        case u'@': addToken(TokenType::AT); break;
        case u'.':
            if (match(u'.')) {
                if (match(u'.')) addToken(TokenType::ELLIPSIS);
                else addToken(match(u'<') ? TokenType::DOT_DOT_LESS : TokenType::DOT_DOT);
            } else {
                addToken(TokenType::DOT);
            }
            break;
        case u':': addToken(match(u':') ? TokenType::DOUBLE_COLON : TokenType::COLON); break;
        case u'+': addToken(match(u'=') ? TokenType::PLUS_EQUAL : match(u'+') ? TokenType::PLUS_PLUS : TokenType::PLUS); break;
        case u'*': addToken(match(u'=') ? TokenType::STAR_EQUAL : TokenType::STAR); break;
        case u'%': addToken(match(u'=') ? TokenType::PERCENT_EQUAL : TokenType::PERCENT); break;
        case u'!': addToken(match(u'=') ? TokenType::BANG_EQUAL : TokenType::BANG); break;
        case u'=':
            if (match(u'=')) addToken(TokenType::EQUAL_EQUAL);
            else if (match(u'>')) addToken(TokenType::FAT_ARROW);
            else addToken(TokenType::EQUAL);
            break;
        case u'<':
            // `<=>` is read before `<=`: maximal munch. Nothing else in the
            // grammar produces `<`, `=`, `>` in sequence, so this takes no
            // existing spelling away.
            if (match(u'>')) addToken(TokenType::EXCHANGE);
            else if (match(u'=')) addToken(match(u'>') ? TokenType::SPACESHIP : TokenType::LESS_EQUAL);
            else if (match(u'<')) addToken(match(u'=') ? TokenType::SHIFT_LEFT_EQUAL : TokenType::SHIFT_LEFT);
            else addToken(TokenType::LESS);
            break;
        case u'>':
            if (!isAtEnd() && source[(size_t)current] == u'.' && !isAtEnd(1) && source[(size_t)current + 1] == u'.') {
                advance();
                advance();
                addToken(TokenType::GREATER_DOT_DOT);
            } else if (match(u'=')) {
                addToken(TokenType::GREATER_EQUAL);
            } else if (match(u'>')) {
                addToken(match(u'=') ? TokenType::SHIFT_RIGHT_EQUAL : TokenType::SHIFT_RIGHT);
            } else {
                addToken(TokenType::GREATER);
            }
            break;
        case u'&':
            if (match(u'&')) addToken(TokenType::AND_AND);
            else if (match(u'=')) addToken(TokenType::AMP_EQUAL);
            else addToken(TokenType::AMP);
            break;
        case u'|':
            if (match(u'|')) addToken(TokenType::OR_OR);
            else if (match(u'=')) addToken(TokenType::PIPE_EQUAL);
            else addToken(TokenType::PIPE);
            break;
        case u'^': addToken(match(u'=') ? TokenType::CARET_EQUAL : TokenType::CARET); break;
        case u'~': addToken(TokenType::TILDE); break;  // unary bitwise complement; `+` concatenates
        case u'?': scanNullableOp(); break;
        case u'-':
            if (match(u'>')) addToken(TokenType::ARROW);
            else if (match(u'=')) addToken(TokenType::MINUS_EQUAL);
            else if (match(u'-')) addToken(TokenType::MINUS_MINUS);
            else addToken(TokenType::MINUS);
            break;
        case u'/':
            if (match(u'/')) {
                while (!isAtEnd() && peek() != u'\n') advance();
            } else if (match(u'*')) {
                skipBlockComment();
            } else if (match(u'=')) {
                addToken(TokenType::SLASH_EQUAL);
            } else {
                addToken(TokenType::SLASH);
            }
            break;
        case u'"':
            // Triple-quoted raw string `"""…"""` vs. regular `"…"`.
            if (!isAtEnd(1) && source[(size_t)current] == u'"' && !isAtEnd(2) && source[(size_t)current + 1] == u'"') {
                scanRawString();
            } else {
                scanString();
            }
            break;
        case u'\'': scanCharLiteral(); break;
        case u'\n':
            if (bracketDepth <= 0 && shouldEmitNewline()) addToken(TokenType::NEWLINE);
            line++;
            column = 1;
            break;
        case u'\r':
            if (!isAtEnd() && peek() == u'\n') advance();
            if (bracketDepth <= 0 && shouldEmitNewline()) addToken(TokenType::NEWLINE);
            line++;
            column = 1;
            break;
        case u' ':
        case u'\t':
            break;
        default:
            if (isDigit(c)) {
                scanNumber();
            } else if (isLetter(c) || c == u'_' || c == u'$') {
                scanIdentifier();
            } else {
                fail("Unexpected character '" + charText(c) + "' at line " + str(line));
            }
    }
}

/**
 * Scans a triple-quoted raw string `"""…"""`. The content is taken literally -
 * backslash escapes are NOT processed and newlines are preserved - until the
 * closing `"""`. Emitted as a normal STRING_LITERAL so the parser and all
 * backends (which already escape string values) handle it unchanged.
 *
 * The opening `"` has already been consumed by scanToken; this consumes the
 * remaining two `"` of the opening delimiter.
 */
void Lexer::scanRawString() {
    advance();  // second '"' of opening delimiter
    advance();  // third '"' of opening delimiter
    std::u16string sb;
    while (!isAtEnd()) {
        if (peek() == u'"' && !isAtEnd(1) && source[(size_t)current + 1] == u'"' && !isAtEnd(2) &&
            source[(size_t)current + 2] == u'"') {
            advance();
            advance();
            advance();  // closing """
            tokens.push_back(Token{TokenType::STRING_LITERAL, text(start, current), line, startColumn, encodeUtf8(sb)});
            return;
        }
        char16_t c = advance();
        if (c == u'\n') {
            line++;
            column = 1;
        }
        sb.push_back(c);
    }
    fail("Unterminated raw string at line " + str(line));
}

void Lexer::scanString() {
    std::u16string sb;
    std::vector<StringPart> parts;
    bool hasInterpolation = false;

    auto flushLiteral = [&]() {
        if (!sb.empty()) {
            parts.push_back(StringPart{StringPart::Kind::Literal, encodeUtf8(sb)});
            sb.clear();
        }
    };

    while (!isAtEnd() && peek() != u'"') {
        if (peek() == u'\\') {
            advance();  // consume backslash
            char16_t escaped = !isAtEnd() ? advance() : u' ';
            switch (escaped) {
                case u'b': sb.push_back(u'\b'); break;
                case u'f': sb.push_back(u'\u000C'); break;
                case u'n': sb.push_back(u'\n'); break;
                case u't': sb.push_back(u'\t'); break;
                case u'r': sb.push_back(u'\r'); break;
                case u'0': sb.push_back(u'\0'); break;
                case u'\\': sb.push_back(u'\\'); break;
                case u'"': sb.push_back(u'"'); break;
                case u'/': sb.push_back(u'/'); break;
                case u'$': sb.push_back(u'$'); break;
                default:
                    fail("Unknown escape sequence '\\" + charText(escaped) + "' in string literal at line " + str(line));
            }
        } else if (peek() == u'$') {
            hasInterpolation = true;
            flushLiteral();
            advance();  // consume '$'
            if (!isAtEnd() && peek() == u'{') {
                advance();  // consume '{'
                std::u16string exprSrc;
                int depth = 1;
                while (!isAtEnd() && depth > 0) {
                    char16_t c = advance();
                    if (c == u'\n') {
                        line++;
                        column = 1;
                    }
                    if (c == u'{') {
                        depth++;
                    } else if (c == u'}') {
                        depth--;
                        if (depth == 0) break;
                    }
                    if (depth > 0) exprSrc.push_back(c);
                }
                if (depth > 0) fail("Unterminated interpolation in string at line " + str(line));
                parts.push_back(StringPart{StringPart::Kind::Expr, encodeUtf8(exprSrc)});
            } else {
                // $identifier
                std::u16string ident;
                while (!isAtEnd() && (isLetterOrDigit(peek()) || peek() == u'_')) ident.push_back(advance());
                if (ident.empty()) {
                    sb.push_back(u'$');  // lone '$' - treat as literal
                } else {
                    parts.push_back(StringPart{StringPart::Kind::Expr, encodeUtf8(ident)});
                }
            }
        } else {
            char16_t ch = advance();
            if (ch == u'\n') {
                line++;
                column = 1;
            }
            sb.push_back(ch);
        }
    }
    if (isAtEnd()) fail("Unterminated string at line " + str(line));
    advance();  // closing "
    std::string lexeme = text(start, current);
    if (hasInterpolation) {
        flushLiteral();
        tokens.push_back(Token{TokenType::INTERPOLATED_STRING, lexeme, line, startColumn, std::move(parts)});
    } else {
        tokens.push_back(Token{TokenType::STRING_LITERAL, lexeme, line, startColumn, encodeUtf8(sb)});
    }
}

/**
 * Scans a `?`-led operator. Beyond the plain nullable marker (`?`), this
 * recognizes the null-conditional compound assignment operators
 * (`?=` `?+=` `?-=` `?*=` `?/=` `?%=`) and the null-conditional
 * increment/decrement operators (`?++` `?--`), as well as `??` and `?.`.
 */
void Lexer::scanNullableOp() {
    if (match(u'?')) {
        addToken(TokenType::QMARK_QMARK);
    } else if (match(u'.')) {
        addToken(TokenType::QMARK_DOT);
    } else if (match(u'=')) {
        addToken(TokenType::QMARK_EQUAL);
    } else if (match(u'!')) {
        addToken(TokenType::QMARK_BANG);
    } else if (!isAtEnd() && std::u16string_view(u"+-*/%").find(peek()) != std::u16string_view::npos) {
        char16_t opChar = peek();
        char16_t afterOp = !isAtEnd(1) ? source[(size_t)current + 1] : u' ';
        if (afterOp == u'=') {
            // ?+= ?-= ?*= ?/= ?%=  - compound null-conditional assignment
            advance();  // op char
            advance();  // '='
            switch (opChar) {
                case u'+': addToken(TokenType::QMARK_PLUS_EQUAL); break;
                case u'-': addToken(TokenType::QMARK_MINUS_EQUAL); break;
                case u'*': addToken(TokenType::QMARK_STAR_EQUAL); break;
                case u'/': addToken(TokenType::QMARK_SLASH_EQUAL); break;
                case u'%': addToken(TokenType::QMARK_PERCENT_EQUAL); break;
                default: fail("unreachable nullable compound op");
            }
        } else if (opChar == u'+' && afterOp == u'+') {
            // ?++ / ?-- - null-conditional inc/dec
            advance();
            advance();
            addToken(TokenType::QMARK_PLUS_PLUS);
        } else if (opChar == u'-' && afterOp == u'-') {
            advance();
            advance();
            addToken(TokenType::QMARK_MINUS_MINUS);
        } else {
            // Lone '?' followed by an operator char that isn't part of a
            // null-conditional op (e.g. `T?` then `+`).
            addToken(TokenType::QMARK);
        }
    } else {
        addToken(TokenType::QMARK);
    }
}

/**
 * `'x'` - one Unicode code point. A character above U+FFFF is written as
 * itself (two UTF-16 units in the source text); a surrogate, written as
 * `'\uD800'`, is not a code point a `Char` can hold.
 */
void Lexer::scanCharLiteral() {
    if (isAtEnd()) fail("Unterminated character literal at line " + str(line));
    int32_t cp;
    if (peek() == u'\\') {
        advance();  // consume '\'
        if (isAtEnd()) fail("Unterminated character literal at line " + str(line));
        char16_t esc = advance();
        switch (esc) {
            case u'b': cp = 0x08; break;
            case u'f': cp = 0x0C; break;
            case u'n': cp = 0x0A; break;
            case u't': cp = 0x09; break;
            case u'r': cp = 0x0D; break;
            case u'\\': cp = '\\'; break;
            case u'\'': cp = '\''; break;
            case u'"': cp = '"'; break;
            case u'/': cp = '/'; break;
            case u'0': cp = 0; break;
            case u'u': {
                std::u16string hex;
                for (int i = 0; i < 4; i++) {
                    if (isAtEnd()) fail("Incomplete \\u escape in character literal at line " + str(line));
                    hex.push_back(advance());
                }
                cp = javaParseInt(encodeUtf8(hex), 16);
                break;
            }
            default:
                fail("Unknown escape sequence '\\" + charText(esc) + "' in character literal at line " + str(line));
        }
    } else {
        char16_t first = advance();
        if (isHighSurrogate(first) && !isAtEnd() && isLowSurrogate(peek())) {
            cp = 0x10000 + ((first - 0xD800) << 10) + (advance() - 0xDC00);
        } else {
            cp = first;
        }
    }
    if (!isScalar(cp)) {
        std::string hex = uppercase(toStringRadix(cp, 16));
        while (u16len(hex) < 4) hex = "0" + hex;
        fail("character literal U+" + hex + " at line " + str(line) + " is not a Unicode scalar value");
    }
    if (isAtEnd() || peek() != u'\'') fail("Unterminated character literal at line " + str(line));
    advance();  // closing '
    tokens.push_back(Token{TokenType::CHAR_LITERAL, text(start, current), line, startColumn, cp});
}

void Lexer::scanNumber() {
    int base = 10;
    bool isHex = false;

    // Check for base prefix: 0x, 0o, 0b
    if (source[(size_t)start] == u'0' && !isAtEnd()) {
        if (!isAtEnd() && (peek() == u'x' || peek() == u'X')) {
            advance();
            base = 16;
            isHex = true;
        } else if (!isAtEnd() && (peek() == u'o' || peek() == u'O')) {
            advance();
            base = 8;
        } else if (!isAtEnd() && (peek() == u'b' || peek() == u'B')) {
            advance();
            base = 2;
        }
    }

    // Scan integer digits (with underscores)
    switch (base) {
        case 16: while (!isAtEnd() && (isHexDigitChar(peek()) || peek() == u'_')) advance(); break;
        case 8: while (!isAtEnd() && ((peek() >= u'0' && peek() <= u'7') || peek() == u'_')) advance(); break;
        case 2: while (!isAtEnd() && (peek() == u'0' || peek() == u'1' || peek() == u'_')) advance(); break;
        default: while (!isAtEnd() && (isDigit(peek()) || peek() == u'_')) advance(); break;
    }

    // Quad point (only for base-10). In member-access position (`obj.0.0`)
    // the previous token is DOT - scan an integer so the `.0` splits into two
    // tuple accesses instead of being swallowed as a DOUBLE_LITERAL `0.0`.
    bool isFloat = false;
    bool afterMemberAccess = !tokens.empty() && tokens.back().type == TokenType::DOT;
    if (base == 10 && !afterMemberAccess && !isAtEnd() && peek() == u'.' && !isAtEnd(1) &&
        isDigit(source[(size_t)current + 1])) {
        isFloat = true;
        advance();  // consume '.'
        while (!isAtEnd() && (isDigit(peek()) || peek() == u'_')) advance();
    } else if (base == 10 && !afterMemberAccess && !isAtEnd() && peek() == u'.' &&
               (isAtEnd(1) || (source[(size_t)current + 1] != u'.' && !isLetter(source[(size_t)current + 1]) &&
                               source[(size_t)current + 1] != u'_'))) {
        // Trailing-dot real (`1.`, `0.`) - `.` not followed by a digit, a second
        // `.` (range `1..5`), or an identifier char (member access `5.foo`).
        isFloat = true;
        advance();  // consume '.'
    }

    // Scientific notation (only for base-10 floats or integers)
    if (base == 10 && !afterMemberAccess && !isAtEnd() && (peek() == u'e' || peek() == u'E')) {
        isFloat = true;
        advance();  // consume 'e'/'E'
        if (!isAtEnd() && (peek() == u'+' || peek() == u'-')) advance();
        while (!isAtEnd() && (isDigit(peek()) || peek() == u'_')) advance();
    }

    // Scan type suffix. The language has no width suffixes: a literal is
    // an `IntLiteral` or a `FloatLiteral` and is read at whatever width the
    // place it lands in states. They are still *scanned* so the one that
    // used to be written can be named in the error.
    if (const WidthSuffix* suffix = scanWidthSuffix(isHex)) {
        std::string written = text(start, current);
        std::string bare = removeSuffix(written, encodeUtf8(suffix->lexeme));
        fail("a width suffix is not part of a literal at line " + str(line) + ": write '" + bare +
             "', or name the width - '" + suffix->width + "(" + bare + ")'");
    }

    std::string lexeme = text(start, current);
    std::string numText = replaceAll(lexeme, "_", "");

    if (isFloat) {
        // Parse as floating-point
        std::string numericText = replaceAll(numText, "_", "");
        double value = javaParseDouble(numericText);
        tokens.push_back(Token{TokenType::DOUBLE_LITERAL, lexeme, line, startColumn, NumericLiteral{value, {}, {}}});
    } else {
        // Values in [Long.MAX+1, ULong.MAX] (a `ULong` maximum, say) do not
        // fit a signed Long, and are carried as the bit pattern. Wider
        // still - a 128-bit `Cent` - fits nothing here at all, so the exact
        // digits travel with the literal and the value carries the low 64
        // bits for the readers that only ever look at small numbers.
        std::string numericText = replaceAll(numText, "_", "");
        std::optional<std::string> digits;
        std::optional<std::string> magnitude;
        auto digitOf = [](char16_t c, int radix) -> int {
            int d = digitValue(c, radix);
            if (d < 0) {
                failArgument("Char " + charText(c) + " is not a digit in the given radix=" + str(radix));
            }
            return d;
        };
        auto parseBits = [&](const std::string& bitsText, int radix) -> int64_t {
            std::optional<int64_t> signedValue = toLongOrNull(bitsText, radix);
            if (signedValue) return *signedValue;
            // Preserve the magnitude even when its low bits fit ULong.
            // Semantic range checks cannot infer it from a negative Long.
            std::u16string units16 = decodeUtf8(bitsText);
            std::string decimal = "0";
            for (char16_t digit : units16) {
                int carry = digitOf(digit, radix);
                // decimal.reversed().map { ... }, then the carry's digits, then reversed.
                std::string low;
                for (size_t i = decimal.size(); i > 0; i--) {
                    int next = (decimal[i - 1] - '0') * radix + carry;
                    carry = next / 10;
                    low.push_back((char)('0' + next % 10));
                }
                std::string high;
                while (carry != 0) {
                    high.push_back((char)('0' + carry % 10));
                    carry /= 10;
                }
                std::string reversedDigits = low + high;
                decimal.assign(reversedDigits.rbegin(), reversedDigits.rend());
            }
            size_t firstNonZero = decimal.find_first_not_of('0');
            magnitude = firstNonZero == std::string::npos ? "0" : decimal.substr(firstNonZero);
            if (!toULongOrNull(bitsText, radix)) digits = numericText;
            uint64_t acc = 0;
            for (char16_t c : units16) acc = acc * (uint64_t)radix + (uint64_t)(int64_t)digitOf(c, radix);
            return (int64_t)acc;
        };
        int64_t value;
        switch (base) {
            case 16: value = parseBits(removePrefix(removePrefix(numericText, "0x"), "0X"), 16); break;
            case 8: value = parseBits(removePrefix(removePrefix(numericText, "0o"), "0O"), 8); break;
            case 2: value = parseBits(removePrefix(removePrefix(numericText, "0b"), "0B"), 2); break;
            default: value = parseBits(numericText, 10); break;
        }
        tokens.push_back(Token{TokenType::INT_LITERAL, lexeme, line, startColumn, NumericLiteral{value, digits, magnitude}});
    }
}

/**
 * The width suffix written after a number, or null when there is none.
 *
 * `b`, `c` and `f` are hex digits, so in a hex literal they are part of the
 * number rather than a suffix.
 */
const Lexer::WidthSuffix* Lexer::scanWidthSuffix(bool isHex) {
    const WidthSuffix* found = nullptr;
    for (const WidthSuffix& candidate : WIDTH_SUFFIXES) {
        if (isHex && candidate.hexDigit) continue;
        std::u16string_view lexeme(candidate.lexeme);
        if (!matchesAhead(lexeme)) continue;
        // `4uL` ends the number; `4until` is `4` and a name.
        int32_t after = current + (int32_t)lexeme.size();
        if (after >= (int32_t)source.size() || !isLetterOrDigit(source[(size_t)after])) {
            found = &candidate;
            break;
        }
    }
    if (found == nullptr) return nullptr;
    int32_t length = (int32_t)std::u16string_view(found->lexeme).size();
    for (int32_t i = 0; i < length; i++) advance();
    column += length - 1;
    return found;
}

bool Lexer::matchesAhead(std::u16string_view lexeme) const {
    if (current + (int32_t)lexeme.size() > (int32_t)source.size()) return false;
    for (size_t i = 0; i < lexeme.size(); i++) {
        if (source[(size_t)current + i] != lexeme[i]) return false;
    }
    return true;
}

void Lexer::scanIdentifier() {
    while (!isAtEnd() && (isLetterOrDigit(peek()) || peek() == u'_' || peek() == u'$')) advance();
    std::string word = text(start, current);
    const TokenType* keyword = reservedKeywords().get(word);
    addToken(keyword ? *keyword : TokenType::IDENTIFIER);
}

void Lexer::skipBlockComment() {
    int depth = 1;
    while (!isAtEnd() && depth > 0) {
        if (peek() == u'/' && !isAtEnd(1) && source[(size_t)current + 1] == u'*') {
            advance();
            advance();
            depth++;
        } else if (peek() == u'*' && !isAtEnd(1) && source[(size_t)current + 1] == u'/') {
            advance();
            advance();
            depth--;
        } else if (peek() == u'\n') {
            advance();
            line++;
            column = 1;
        } else {
            advance();
        }
    }
}

void Lexer::addToken(TokenType type) { tokens.push_back(Token{type, text(start, current), line, startColumn, {}}); }

std::string Lexer::text(int32_t from, int32_t to) const {
    return encodeUtf8(std::u16string_view(source).substr((size_t)from, (size_t)(to - from)));
}

char16_t Lexer::advance() {
    char16_t c = source[(size_t)current++];
    column++;
    return c;
}

bool Lexer::match(char16_t expected) {
    if (isAtEnd() || source[(size_t)current] != expected) return false;
    current++;
    column++;
    return true;
}

}  // namespace azora
