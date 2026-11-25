// Port of Lexer.kt and AzoraSyntaxVocabulary.kt.
#pragma once

#include "base/ordered.h"
#include "frontend/token.h"

#include <string>
#include <string_view>
#include <vector>

namespace azora {

/** AzoraSyntaxVocabulary.reservedKeywords: every reserved word and its token. */
const OrderedMap<std::string, TokenType>& reservedKeywords();

/**
 * Lexer (tokenizer) for the Azora language.
 *
 * Converts raw source text into a list of Tokens. The lexer handles:
 * - Keywords and identifiers
 * - Integer and floating-point literals
 * - String literals with escape sequences
 * - Single-line (`//`) and nested block comments
 * - Significant newlines as statement terminators (suppressed inside brackets)
 * - All operator and delimiter tokens defined in TokenType
 *
 * Kotlin scans a UTF-16 string; so does this, so that columns count UTF-16
 * units. Lexemes and literal text are stored as UTF-8.
 */
struct Lexer {
    explicit Lexer(std::string_view sourceUtf8);

    std::vector<Token> tokenize();

    std::u16string source;
    std::vector<Token> tokens;
    int32_t start = 0;
    int32_t current = 0;
    int32_t line = 1;
    int32_t column = 1;
    int32_t startColumn = 1;
    int32_t bracketDepth = 0;

    /** Bracket depth saved at each open brace, restored at its match. */
    std::vector<int32_t> enclosingBracketDepths;

    bool shouldEmitNewline() const;
    void scanToken();
    void scanRawString();
    void scanString();
    void scanNullableOp();
    void scanCharLiteral();
    void scanNumber();

    /** A suffix the language used to have, and what it used to mean. */
    struct WidthSuffix {
        const char16_t* lexeme;
        const char* width;
        bool hexDigit = false;
    };
    const WidthSuffix* scanWidthSuffix(bool isHex);
    bool matchesAhead(std::u16string_view lexeme) const;
    void scanIdentifier();
    void skipBlockComment();
    void addToken(TokenType type);
    std::string text(int32_t from, int32_t to) const;
    char16_t advance();
    char16_t peek() const { return source[(size_t)current]; }
    bool isAtEnd(int32_t offset = 0) const { return current + offset >= (int32_t)source.size(); }
    bool match(char16_t expected);
};

/** CodePoints.isScalar: a Unicode scalar value. */
inline bool isScalar(int32_t cp) { return cp >= 0 && cp <= 0x10FFFF && !(cp >= 0xD800 && cp <= 0xDFFF); }

}  // namespace azora
