// Token types and tokens. Port of Token.kt.
//
// TokenType keeps Kotlin's order and names: an enum's name reaches dumps and
// messages (`$type`), and its ordinal decides nothing but must still match.
#pragma once

#include "base/dump.h"

#include <cstdint>
#include <optional>
#include <string>
#include <variant>
#include <vector>

namespace azora {

// NULL, EOF, TRUE and FALSE are C macros, so those four enumerators carry a
// trailing underscore; tokenTypeName() gives the Kotlin names.
enum class TokenType : uint8_t {
    INT_LITERAL, DOUBLE_LITERAL, STRING_LITERAL, CHAR_LITERAL, TRUE_, FALSE_, INTERPOLATED_STRING,
    IDENTIFIER, VAR, FIN, LET, VAL, FUNC, RETURN, IF, ELSE, INLINE, DEEPINLINE, NOINLINE, SCOPE,
    TEST, ASSERT, TRACE, PANIC, MACRO, FOR, WHILE, LOOP, IN, BREAK, CONTINUE, THEN, PACK, ENUM, WHEN,
    THROW, TRY, CATCH, IMPL, SPEC, DEFER, TYPEALIAS, VARIANT, MODULE, UNION, ASYNC, WHERE, ESCAPING,
    DERIVES, INCLUDES, BINDS, REQUIRES, ASSOC, LEND, SEAL, PLUS, MINUS, STAR, SLASH, PERCENT, EQUAL,
    EQUAL_EQUAL, BANG_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, SPACESHIP, EXCHANGE, AND_AND,
    OR_OR, BANG, DOT, DOT_DOT, DOT_DOT_LESS, GREATER_DOT_DOT, PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL,
    SLASH_EQUAL, PERCENT_EQUAL, AMP_EQUAL, PIPE_EQUAL, CARET_EQUAL, SHIFT_LEFT_EQUAL, SHIFT_RIGHT_EQUAL,
    PLUS_PLUS, MINUS_MINUS, AMP, PIPE, CARET, TILDE, SHIFT_LEFT, SHIFT_RIGHT, AS, IS, QMARK, QMARK_QMARK,
    QMARK_DOT, QMARK_EQUAL, QMARK_BANG, QMARK_PLUS_EQUAL, QMARK_MINUS_EQUAL, QMARK_STAR_EQUAL, QMARK_SLASH_EQUAL,
    QMARK_PERCENT_EQUAL, QMARK_PLUS_PLUS, QMARK_MINUS_MINUS, NULL_, IMPORT, USING, BY, AT, WITH, WITHOUT,
    OPER, ERROR, ALLOC, PURGE, UNSAFE, TAKE, AWAIT, DELAY, BRIDGE, SOLO, INJECT, GRAPH, SCOPED, DIRECT,
    LAZY, FACTORY, DERIVE, RESCUE, ELLIPSIS, REACT, REMEMBER, RETAIN, PRESERVE, EFFECT, PROP, CTOR,
    DTOR, OUT, EXPOSED, PROTECTED, CONFINED, THREADLOCAL, ANNOT, BIND, PRIM_INT, PRIM_UINT, PRIM_FLOAT,
    L_PAREN, R_PAREN, L_BRACE, R_BRACE, L_BRACKET, R_BRACKET, COMMA, COLON, DOUBLE_COLON, ARROW,
    FAT_ARROW, SEMICOLON, NEWLINE, EOF_,
};

constexpr int TOKEN_TYPE_COUNT = 166;

/** The Kotlin enum constant's name, e.g. "IDENTIFIER". */
const char* tokenTypeName(TokenType type);

/**
 * A number as it was written.
 *
 * A literal states no width - `4` is an `__int` and takes the width of
 * wherever it lands - so all that travels with it is its value and, when the
 * digits are wider than a `Long` holds, the digits themselves.
 */
struct NumericLiteral {
    std::variant<int64_t, double> value;
    /** The exact digits, when they are wider than [value] can carry (a 128-bit literal). */
    std::optional<std::string> text;
    /** Decimal magnitude for range checks when signed Long carries only low bits. */
    std::optional<std::string> magnitude;
};

/**
 * One segment of an interpolated string literal.
 *
 * - Literal is a chunk of literal text (escapes already resolved).
 * - Expr is an embedded expression given as raw source text, parsed later
 *   in the surrounding expression context.
 */
struct StringPart {
    enum class Kind : uint8_t { Literal, Expr };
    Kind kind;
    /** Literal: the text; Expr: the source. */
    std::string text;
};

/** Kotlin's `literal: Any?`, narrowed to what the lexer stores. */
using TokenLiteral = std::variant<std::monostate, std::string, int32_t, NumericLiteral, std::vector<StringPart>>;

/**
 * A single lexical token produced by the Lexer.
 *
 * type: the TokenType that classifies this token
 * lexeme: the raw source text that was matched
 * line: the 1-based line number where the token starts
 * column: the 1-based column (UTF-16 units) where the token starts
 * literal: the parsed literal value for literal tokens, or none
 */
struct Token {
    TokenType type;
    std::string lexeme;
    int32_t line;
    int32_t column = 0;
    TokenLiteral literal;
};

/** The canonical dump of a token, as the oracle writes Kotlin's `Token` data class. */
void dumpValue(Dumper& d, const Token& token, int ind);

/** Kotlin `token.literal as String`. */
inline const std::string& literalString(const Token& token) { return std::get<std::string>(token.literal); }

}  // namespace azora
