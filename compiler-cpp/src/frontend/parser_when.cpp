// Parser.kt lines 5895-6127: statement `when`, header helpers, top-level
// inline assert/trace, and module headers.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"

namespace azora {

/** `when scrutinee { patterns -> { body } ... else -> { body } }`. */
StmtWhen* Parser::parseWhen() {
    Token start = peek();
    Expr scrutinee = parseWhenHead().scrutinee;
    WhenParts<std::vector<Stmt>> parts =
        parseWhenBranches<std::vector<Stmt>>(scrutinee, [&] { return parseWhenStatementBody(); });
    consumeNewline();
    return make(arena, StmtWhen{scrutinee, whenBranches(parts, start), parts.elseBody, start.line, start.column});
}

/**
 * Parses an expression in a control-flow *header*, where the `{` that
 * follows opens the construct's body rather than a trailing lambda. Restores
 * the previous value rather than forcing `true`.
 */
Expr Parser::withoutTrailingLambda(const std::function<Expr()>& parse) {
    bool saved = allowTrailingLambda;
    allowTrailingLambda = false;
    Finally restore{[&] { allowTrailingLambda = saved; }};
    return parse();
}

/** withoutTrailingLambda for a header that parses several things. */
void Parser::withoutTrailingLambdaRun(const std::function<void()>& parse) {
    bool saved = allowTrailingLambda;
    allowTrailingLambda = false;
    Finally restore{[&] { allowTrailingLambda = saved; }};
    parse();
}

/** Delimiters inside a header keep lambda bodies separate from its own body. */
std::vector<Expr> Parser::withinExpressionDelimiter(const std::function<std::vector<Expr>()>& parse) {
    bool saved = allowTrailingLambda;
    allowTrailingLambda = true;
    Finally restore{[&] { allowTrailingLambda = saved; }};
    return parse();
}

Parser::WhenHead Parser::parseWhenHead() {
    consume(TokenType::WHEN, "Expected 'when'");
    // `when { condition -> value ... }` is the guard form, with `true` as its
    // implicit subject.
    if (check(TokenType::L_BRACE)) {
        Token at = peek();
        return WhenHead{make(arena, ExprBoolLiteral{true, at.line, at.column, 0}), true};
    }
    // A branch's `{` must not be mistaken for a trailing lambda on a scrutinee
    // that ends in a call.
    return WhenHead{withoutTrailingLambda([&] { return parseExpr(); }), false};
}

/** A statement-form branch body: `-> { statements }`, or `-> statement`. */
std::vector<Stmt> Parser::parseWhenStatementBody() {
    if (!check(TokenType::L_BRACE)) return parseStmts();
    consume(TokenType::L_BRACE, "Expected '{' after '->'");
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}'");
    return body;
}

/** The branches of a statement-shaped `when`, in AST form. */
std::vector<WhenBranch> Parser::whenBranches(const WhenParts<std::vector<Stmt>>& parts, const Token& start) {
    std::vector<WhenBranch> out;
    for (size_t i = 0; i < parts.patterns.size(); i++) {
        out.push_back(WhenBranch{parts.patterns[i], parts.bodies[i], start.line, start.column});
    }
    return out;
}

/**
 * Parses one `when` pattern. `is Type` becomes an IsCheck against the
 * [scrutinee]; anything else is a normal expression pattern.
 */
Expr Parser::parseWhenPattern(const Expr& scrutinee) {
    if (check(TokenType::IS)) {
        Token at = advance();  // 'is'
        std::string name = parseQualifiedTypeName("Expected type name after 'is'");
        ExprIsCheck isCheck;
        isCheck.expr = scrutinee;
        isCheck.typeName = name;
        isCheck.line = at.line;
        isCheck.column = at.column;
        isCheck.length = u16len(at.lexeme);
        return make(arena, isCheck);
    }
    return parseExpr();
}

TopLevelInlineAssert* Parser::parseTopLevelInlineAssert() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::ASSERT, "Expected 'assert'");
    bool savedTrailing = allowTrailingLambda;
    allowTrailingLambda = false;
    Expr condition;
    {
        Finally restore{[&] { allowTrailingLambda = savedTrailing; }};
        condition = parseExpr();
    }
    consume(TokenType::PANIC, "Expected 'panic' after inline assert condition");
    skipNewlines();
    Expr message = parseExpr();
    consumeNewline();
    return make(arena, TopLevelInlineAssert{condition, message, start.line, start.column});
}

TopLevelInlineTrace* Parser::parseTopLevelInlineTrace() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::TRACE, "Expected 'trace'");
    TracePayload payload = parseTracePayload(start, true);
    consumeNewline();
    return make(arena, TopLevelInlineTrace{payload.message, start.line, start.column, payload.level});
}

/** True when [index] holds a `module` that opens a module declaration. */
bool Parser::isModuleWordAt(int32_t index) {
    if (!typeIs(index, TokenType::MODULE)) return false;
    return typeIs(index + 1, TokenType::IDENTIFIER);
}

/**
 * True when a module header begins here: an optional `exposed`, an optional
 * `confined`, then `module`.
 */
bool Parser::isModuleHeaderAhead() {
    int32_t i = current;
    if (typeIs(i, TokenType::EXPOSED)) i++;
    // `exposed if COND \n module …` - the newline before `module` is mandatory.
    if (typeIs(i, TokenType::IF)) {
        int32_t j = i + 1;
        while (j < (int32_t)tokens.size() && tokens[(size_t)j].type != TokenType::NEWLINE) j++;
        // exactly one newline, then `module`
        return typeIs(j, TokenType::NEWLINE) && isModuleWordAt(j + 1);
    }
    if (typeIs(i, TokenType::CONFINED)) i++;
    return isModuleWordAt(i);
}

std::string Parser::parseModule() {
    consume(TokenType::MODULE, "Expected 'module'");
    // Qualified names are allowed: `module std.math`.
    std::string name = consumeIdentifierLike("Expected module name");
    while (match(TokenType::DOT)) {
        name += ".";
        name += consumeIdentifierLike("Expected name after '.' in module");
    }
    consumeNewline();
    return name;
}

}  // namespace azora
