#include "frontend/token.h"

namespace azora {

static const char* const TOKEN_TYPE_NAMES[TOKEN_TYPE_COUNT] = {
    "INT_LITERAL",
    "DOUBLE_LITERAL",
    "STRING_LITERAL",
    "CHAR_LITERAL",
    "TRUE",
    "FALSE",
    "INTERPOLATED_STRING",
    "IDENTIFIER",
    "VAR",
    "FIN",
    "LET",
    "VAL",
    "FUNC",
    "RETURN",
    "IF",
    "ELSE",
    "INLINE",
    "DEEPINLINE",
    "NOINLINE",
    "SCOPE",
    "TEST",
    "ASSERT",
    "TRACE",
    "PANIC",
    "MACRO",
    "FOR",
    "WHILE",
    "LOOP",
    "IN",
    "BREAK",
    "CONTINUE",
    "THEN",
    "PACK",
    "ENUM",
    "WHEN",
    "THROW",
    "TRY",
    "CATCH",
    "IMPL",
    "SPEC",
    "DEFER",
    "TYPEALIAS",
    "VARIANT",
    "MODULE",
    "UNION",
    "ASYNC",
    "WHERE",
    "ESCAPING",
    "DERIVES",
    "INCLUDES",
    "BINDS",
    "REQUIRES",
    "ASSOC",
    "LEND",
    "SEAL",
    "PLUS",
    "MINUS",
    "STAR",
    "SLASH",
    "PERCENT",
    "EQUAL",
    "EQUAL_EQUAL",
    "BANG_EQUAL",
    "LESS",
    "LESS_EQUAL",
    "GREATER",
    "GREATER_EQUAL",
    "SPACESHIP",
    "EXCHANGE",
    "AND_AND",
    "OR_OR",
    "BANG",
    "DOT",
    "DOT_DOT",
    "DOT_DOT_LESS",
    "GREATER_DOT_DOT",
    "PLUS_EQUAL",
    "MINUS_EQUAL",
    "STAR_EQUAL",
    "SLASH_EQUAL",
    "PERCENT_EQUAL",
    "AMP_EQUAL",
    "PIPE_EQUAL",
    "CARET_EQUAL",
    "SHIFT_LEFT_EQUAL",
    "SHIFT_RIGHT_EQUAL",
    "PLUS_PLUS",
    "MINUS_MINUS",
    "AMP",
    "PIPE",
    "CARET",
    "TILDE",
    "SHIFT_LEFT",
    "SHIFT_RIGHT",
    "AS",
    "IS",
    "QMARK",
    "QMARK_QMARK",
    "QMARK_DOT",
    "QMARK_EQUAL",
    "QMARK_BANG",
    "QMARK_PLUS_EQUAL",
    "QMARK_MINUS_EQUAL",
    "QMARK_STAR_EQUAL",
    "QMARK_SLASH_EQUAL",
    "QMARK_PERCENT_EQUAL",
    "QMARK_PLUS_PLUS",
    "QMARK_MINUS_MINUS",
    "NULL",
    "IMPORT",
    "USING",
    "BY",
    "AT",
    "WITH",
    "WITHOUT",
    "OPER",
    "ERROR",
    "ALLOC",
    "PURGE",
    "UNSAFE",
    "TAKE",
    "AWAIT",
    "DELAY",
    "BRIDGE",
    "SOLO",
    "INJECT",
    "GRAPH",
    "SCOPED",
    "DIRECT",
    "LAZY",
    "FACTORY",
    "DERIVE",
    "RESCUE",
    "ELLIPSIS",
    "REACT",
    "REMEMBER",
    "RETAIN",
    "PRESERVE",
    "EFFECT",
    "PROP",
    "CTOR",
    "DTOR",
    "OUT",
    "EXPOSED",
    "PROTECTED",
    "CONFINED",
    "THREADLOCAL",
    "ANNOT",
    "BIND",
    "PRIM_INT",
    "PRIM_UINT",
    "PRIM_FLOAT",
    "L_PAREN",
    "R_PAREN",
    "L_BRACE",
    "R_BRACE",
    "L_BRACKET",
    "R_BRACKET",
    "COMMA",
    "COLON",
    "DOUBLE_COLON",
    "ARROW",
    "FAT_ARROW",
    "SEMICOLON",
    "NEWLINE",
    "EOF",
};

const char* tokenTypeName(TokenType type) { return TOKEN_TYPE_NAMES[(int)type]; }

static void dumpNumeric(Dumper& d, const NumericLiteral& n, int ind) {
    d.open("NumericLiteral");
    d.field("value", ind);
    if (const int64_t* i = std::get_if<int64_t>(&n.value)) dumpValue(d, *i, ind + 2);
    else dumpValue(d, std::get<double>(n.value), ind + 2);
    d.field("text", ind);
    dumpValue(d, n.text, ind + 2);
    d.field("magnitude", ind);
    dumpValue(d, n.magnitude, ind + 2);
    d.close(ind);
}

static void dumpPart(Dumper& d, const StringPart& part, int ind) {
    bool literal = part.kind == StringPart::Kind::Literal;
    d.open(literal ? "StringPart.Literal" : "StringPart.Expr");
    d.field(literal ? "text" : "source", ind);
    dumpValue(d, part.text, ind + 2);
    d.close(ind);
}

void dumpValue(Dumper& d, const Token& token, int ind) {
    d.open("Token");
    d.field("type", ind);
    d.raw(tokenTypeName(token.type));
    d.field("lexeme", ind);
    dumpValue(d, token.lexeme, ind + 2);
    d.field("line", ind);
    dumpValue(d, token.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, token.column, ind + 2);
    d.field("literal", ind);
    const TokenLiteral& literal = token.literal;
    if (std::holds_alternative<std::monostate>(literal)) {
        d.nil();
    } else if (const std::string* s = std::get_if<std::string>(&literal)) {
        dumpValue(d, *s, ind + 2);
    } else if (const int32_t* cp = std::get_if<int32_t>(&literal)) {
        dumpValue(d, *cp, ind + 2);
    } else if (const NumericLiteral* n = std::get_if<NumericLiteral>(&literal)) {
        dumpNumeric(d, *n, ind + 2);
    } else {
        const std::vector<StringPart>& parts = std::get<std::vector<StringPart>>(literal);
        d.raw("[");
        for (const StringPart& part : parts) {
            d.out.push_back('\n');
            d.indent(ind + 4);
            dumpPart(d, part, ind + 4);
        }
        if (!parts.empty()) {
            d.out.push_back('\n');
            d.indent(ind + 2);
        }
        d.raw("]");
    }
    d.close(ind);
}

}  // namespace azora
