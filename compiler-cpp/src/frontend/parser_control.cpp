// Parser.kt lines 9729-10229: `try`, inline/deepinline statements, scopes,
// compile-time bindings, assert/trace, `if`, blocks and initializers.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"
#include "frontend/lexer.h"
#include "frontend/trace_semantics.h"

namespace azora {

/**
 * `try { … } catch { e -> … }`; either half that holds one statement can take
 * `then` instead of braces, and `catch e then report(e)` names the error.
 */
StmtTry* Parser::parseTry() {
    Token start = peek();
    consume(TokenType::TRY, "Expected 'try'");
    std::vector<Stmt> body;
    if (check(TokenType::THEN)) {
        // The statement's own `catch` operator would take the try's `catch`.
        int32_t saved = tryThenStart;
        tryThenStart = current + 1;
        Finally restore{[&] { tryThenStart = saved; }};
        body = parseThenBody("try");
    } else {
        consume(TokenType::L_BRACE, "Expected '{' or 'then' after 'try'");
        skipNewlines();
        body = parseBlock();
        consume(TokenType::R_BRACE, "Expected '}' after try body");
    }
    std::optional<std::string> catchName;
    std::optional<std::vector<Stmt>> catchBody;
    if (matchContinued(TokenType::CATCH)) {
        // `catch then s`, or `catch e then s` naming the error.
        bool named = check(TokenType::IDENTIFIER) && peekNextIs(TokenType::THEN);
        if (named || check(TokenType::THEN)) {
            if (named) catchName = advance().lexeme;
            catchBody = parseThenBody("catch");
            consumeNewline();
            return make(arena, StmtTry{body, catchName, catchBody, start.line, start.column});
        }
        consume(TokenType::L_BRACE, "Expected '{' or 'then' after 'catch'");
        skipNewlines();
        if (check(TokenType::IDENTIFIER) && peekNextIs(TokenType::ARROW)) {
            catchName = advance().lexeme;
            consume(TokenType::ARROW, "Expected '->'");
            skipNewlines();
        }
        catchBody = parseBlock();
        consume(TokenType::R_BRACE, "Expected '}' after catch body");
    }
    consumeNewline();
    return make(arena, StmtTry{body, catchName, catchBody, start.line, start.column});
}

Stmt Parser::parseInline() {
    std::optional<Token> next = peekNext();
    TokenType t = next ? next->type : TokenType::EOF_;
    if (next) {
        switch (t) {
            case TokenType::L_BRACE: return parseInlineBlock();
            case TokenType::SCOPE: return parseInlineScopeBlock();
            case TokenType::IF: return parseInlineIf();
            case TokenType::FOR: return parseInlineFor();
            case TokenType::ASSERT: return parseInlineAssertStmt();
            case TokenType::TRACE: return parseInlineTraceStmt();
            case TokenType::PANIC: return parsePanicStmt(true);
            case TokenType::FIN: return parseInlineFin();
            case TokenType::VAR:
            case TokenType::VAL: return parseInlineVar();
            case TokenType::LET: return parseInlineLet();
            case TokenType::IDENTIFIER: return parseInlineAssignment();
            case TokenType::STRING_LITERAL:
            case TokenType::INTERPOLATED_STRING: return parseInlineSplice();
            default: break;
        }
    }
    fail("Expected '{', 'scope', 'if', 'for', 'assert', 'trace', 'fin', 'var', 'let', a string splice, or identifier "
         "after 'inline' at line " +
         str(peek().line));
}

/** `inline for x in a..b { body }` - a compile-time unrolled loop. */
Stmt Parser::parseInlineFor() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::FOR, "Expected 'for'");
    std::string name = consume(TokenType::IDENTIFIER, "Expected loop variable name").lexeme;
    consume(TokenType::IN, "Expected 'in' after loop variable");
    // Compile-time list iterable: unroll the body per element at parse time.
    if ((peek().type == TokenType::IDENTIFIER && typeListEnv->contains(peek().lexeme)) ||
        peek().type == TokenType::L_BRACKET) {
        std::vector<std::string> list = parseComptimeForValues();
        std::optional<std::string> indexVar;
        if (matchWithKeyword()) indexVar = consumeIdentifierLike("Expected index variable after 'with'");
        std::vector<Token> bodyTokens = captureInlineForBody();
        consumeNewline();
        std::vector<Stmt> stmts;
        for (size_t i = 0; i < list.size(); i++) {
            std::vector<Token> rendered = bodyTokens;
            std::vector<std::pair<std::string, std::string>> bindings{{name, list[i]}};
            if (indexVar) bindings.emplace_back(*indexVar, str(i));
            rendered = foldBraceInterpolation(rendered, bindings);
            rendered = substituteLoopVar(rendered, name, list[i]);
            if (indexVar) rendered = substituteLoopVar(rendered, *indexVar, str(i));
            rendered = foldListIndexing(rendered);
            std::vector<Stmt> more = parseInlineForBodyStatements(rendered, start);
            stmts.insert(stmts.end(), more.begin(), more.end());
        }
        return make(arena, StmtScope{.body = stmts, .line = start.line, .column = start.column});
    }
    bool savedTrailing = allowTrailingLambda;
    allowTrailingLambda = false;
    Expr parsedIterable;
    {
        Finally restore{[&] { allowTrailingLambda = savedTrailing; }};
        parsedIterable = parseExpr();
    }
    // `items with index` initially has the shape `items.with(index)`; unwrap it.
    ExprMethodCall* infixWith = as<ExprMethodCall>(parsedIterable);
    bool hasInfixIndex = infixWith && infixWith->name == "with" && infixWith->args.size() == 1 &&
                         is<ExprIdentifier>(infixWith->args[0]);
    Expr iterable = hasInfixIndex ? infixWith->target : parsedIterable;
    std::optional<std::string> indexName;
    if (hasInfixIndex) indexName = cast<ExprIdentifier>(infixWith->args[0]).name;
    else if (matchWithKeyword()) indexName = consume(TokenType::IDENTIFIER, "Expected index binding after 'with'").lexeme;
    std::vector<Stmt> body = parseThenOrBracedBody("inline for");
    consumeNewline();
    return make(arena, StmtInlineFor{name, iterable, body, start.line, start.column, 0, indexName});
}

/**
 * Re-parses the rendered tokens of one unrolled `inline for` iteration as a
 * statement sequence, through a throwaway function.
 */
std::vector<Stmt> Parser::parseInlineForBodyStatements(const std::vector<Token>& rendered, const Token& start) {
    std::vector<Token> wrapped = Lexer("func __inline_for_body__() {\n").tokenize();
    wrapped.pop_back();  // drop trailing EOF
    wrapped.insert(wrapped.end(), rendered.begin(), rendered.end());
    std::vector<Token> suffix = Lexer("\n}\n").tokenize();  // includes EOF
    wrapped.insert(wrapped.end(), suffix.begin(), suffix.end());
    // The wrapper's name is the compiler's; the body's own names were checked.
    Parser sub(arena, wrapped, typeListEnv, declaredEnums, true, typeListScope);
    Program program = sub.parse();
    for (const TopLevel& item : program.items) {
        TopLevelFunc* func = as<TopLevelFunc>(item);
        if (func && func->decl.name == "__inline_for_body__") return func->decl.body;
    }
    fail("failed to expand 'inline for' body at line " + str(start.line));
}

/** `inline scope { ... }` - alias for `inline { ... }`. */
StmtInlineBlock* Parser::parseInlineScopeBlock() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::SCOPE, "Expected 'scope'");
    consume(TokenType::L_BRACE, "Expected '{' after 'scope'");
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}'");
    consumeNewline();
    return make(arena, StmtInlineBlock{body, start.line, start.column});
}

StmtInlineBlock* Parser::parseInlineBlock() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::L_BRACE, "Expected '{' after 'inline'");
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}'");
    consumeNewline();
    return make(arena, StmtInlineBlock{body, start.line, start.column});
}

Stmt Parser::parseDeepInlineStmt() {
    if (peekNextIs(TokenType::L_BRACE)) return parseDeepInlineBlock();
    if (peekNextIs(TokenType::SCOPE)) return parseDeepInlineScopeBlock();
    if (peekNextIs(TokenType::IF)) return parseDeepInlineIf();
    fail("Expected '{', 'scope', or 'if' after 'deepinline' at line " + str(peek().line));
}

/** `deepinline scope { ... }` - alias for `deepinline { ... }`. */
StmtDeepInlineBlock* Parser::parseDeepInlineScopeBlock() {
    Token start = peek();
    consume(TokenType::DEEPINLINE, "Expected 'deepinline'");
    consume(TokenType::SCOPE, "Expected 'scope'");
    consume(TokenType::L_BRACE, "Expected '{' after 'scope'");
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}'");
    consumeNewline();
    return make(arena, StmtDeepInlineBlock{body, start.line, start.column});
}

StmtDeepInlineBlock* Parser::parseDeepInlineBlock() {
    Token start = peek();
    consume(TokenType::DEEPINLINE, "Expected 'deepinline'");
    consume(TokenType::L_BRACE, "Expected '{' after 'deepinline'");
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}'");
    consumeNewline();
    return make(arena, StmtDeepInlineBlock{body, start.line, start.column});
}

StmtDeepInlineIf* Parser::parseDeepInlineIf() {
    Token start = peek();
    consume(TokenType::DEEPINLINE, "Expected 'deepinline'");
    consume(TokenType::IF, "Expected 'if' after 'deepinline'");
    Expr condition = withoutTrailingLambda([&] { return parseExpr(); });
    std::vector<Stmt> thenBranch = parseThenOrBracedBody("deepinline if condition");
    std::optional<std::vector<Stmt>> elseBranch;
    if (match(TokenType::ELSE)) elseBranch = parseElseBody("deepinline if");
    consumeNewline();
    return make(arena, StmtDeepInlineIf{condition, thenBranch, elseBranch, start.line, start.column});
}

StmtScope* Parser::parseScope() {
    Token start = peek();
    consume(TokenType::SCOPE, "Expected 'scope'");
    consume(TokenType::L_BRACE, "Expected '{' after 'scope'");
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}'");
    consumeNewline();
    return make(arena, StmtScope{.body = body, .line = start.line, .column = start.column, .shared = true});
}

StmtNoInline* Parser::parseNoInline() {
    Token start = peek();
    consume(TokenType::NOINLINE, "Expected 'noinline'");
    Stmt inner = parseSingleStmt("'noinline'");
    return make(arena, StmtNoInline{inner, start.line, start.column});
}

StmtInlineFin* Parser::parseInlineFin() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::FIN, "Expected 'fin' after 'inline'");
    std::string name = consumeIdentifierLike("Expected variable name");
    TypeAnnotation type;
    if (match(TokenType::COLON)) type = explicitType(parseTypeName());
    consume(TokenType::EQUAL, "Expected '=' in inline fin declaration");
    Expr init = parseInitializer(type);
    consumeNewline();
    return make(arena, StmtInlineFin{name, type, init, start.line, start.column});
}

/** `inline var name = …` / `inline val name = …` - the compile-time forms. */
StmtInlineVar* Parser::parseInlineVar() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    Token keyword = advance();  // 'var' or 'val'
    bool valueMutable = keyword.type == TokenType::VAR;
    std::string name = consumeIdentifierLike("Expected variable name");
    TypeAnnotation type;
    if (match(TokenType::COLON)) type = explicitType(parseTypeName());
    consume(TokenType::EQUAL, "Expected '=' in inline " + keyword.lexeme + " declaration");
    Expr init = parseInitializer(type);
    consumeNewline();
    return make(arena, StmtInlineVar{name, type, init, start.line, start.column, 0, valueMutable});
}

StmtInlineLet* Parser::parseInlineLet() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::LET, "Expected 'let' after 'inline'");
    std::string name = consumeIdentifierLike("Expected variable name");
    TypeAnnotation type;
    if (match(TokenType::COLON)) type = explicitType(parseTypeName());
    consume(TokenType::EQUAL, "Expected '=' in inline let declaration");
    Expr init = parseInitializer(type);
    consumeNewline();
    return make(arena, StmtInlineLet{name, type, init, start.line, start.column});
}

StmtInlineAssignment* Parser::parseInlineAssignment() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    std::string name = consume(TokenType::IDENTIFIER, "Expected variable name").lexeme;
    consume(TokenType::EQUAL, "Expected '=' in inline assignment");
    Expr value = parseExpr();
    consumeNewline();
    return make(arena, StmtInlineAssignment{name, value, start.line, start.column});
}

StmtAssert* Parser::parseAssertStmt() {
    Token start = peek();
    consume(TokenType::ASSERT, "Expected 'assert'");
    // An assertion condition ends before its required `panic` message.
    bool savedTrailing = allowTrailingLambda;
    allowTrailingLambda = false;
    Expr condition;
    {
        Finally restore{[&] { allowTrailingLambda = savedTrailing; }};
        condition = parseExpr();
    }
    consume(TokenType::PANIC, "Expected 'panic' after assert condition");
    skipNewlines();
    Expr message = parseExpr();
    consumeNewline();
    return make(arena, StmtAssert{condition, message, start.line, start.column});
}

StmtTrace* Parser::parseTraceStmt() {
    Token start = peek();
    consume(TokenType::TRACE, "Expected 'trace'");
    TracePayload payload = parseTracePayload(start, false);
    consumeNewline();
    return make(arena, StmtTrace{payload.message, start.line, start.column, 0, payload.level, payload.liftBody,
                                 payload.explicitLevel});
}

StmtInlineAssert* Parser::parseInlineAssertStmt() {
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
    return make(arena, StmtInlineAssert{condition, message, start.line, start.column});
}

StmtInlineTrace* Parser::parseInlineTraceStmt() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::TRACE, "Expected 'trace'");
    TracePayload payload = parseTracePayload(start, true);
    consumeNewline();
    return make(arena, StmtInlineTrace{payload.message, start.line, start.column, 0, payload.level});
}

/** Parses direct `trace expr` and lambda `[level] { message }` forms. */
Parser::TracePayload Parser::parseTracePayload(const Token& start, bool isInline) {
    std::string inlineWord = isInline ? "inline " : "";
    if (check(TokenType::L_BRACE)) {
        Expr level = defaultTraceLevel(start);
        consume(TokenType::L_BRACE, "Expected '{' after " + inlineWord + "trace");
        skipNewlines();
        Expr message = bindTraceReceiver(arena, parseExpr(), level);
        skipNewlines();
        consume(TokenType::R_BRACE, "Expected '}' after trace message");
        return TracePayload{level, message, true, false};
    }
    Expr shorthandLevel;
    if (match(TokenType::DOT)) {
        Token variant = consume(TokenType::IDENTIFIER, "Expected LogLevel variant after '.'");
        ExprMember member;
        member.target = make(arena, ExprIdentifier{"LogLevel", variant.line, variant.column, u16len(variant.lexeme)});
        member.name = variant.lexeme;
        member.line = variant.line;
        member.column = variant.column;
        member.length = u16len(variant.lexeme) + 1;
        shorthandLevel = make(arena, member);
    }
    Expr candidate = shorthandLevel;
    if (!candidate) {
        bool savedTrailing = allowTrailingLambda;
        allowTrailingLambda = false;
        Finally restore{[&] { allowTrailingLambda = savedTrailing; }};
        candidate = parseExpr();
    }
    if (!check(TokenType::L_BRACE)) {
        if (shorthandLevel) {
            Expr message = parseExpr();
            return TracePayload{shorthandLevel, message, false, true};
        }
        if (!check(TokenType::NEWLINE) && !check(TokenType::SEMICOLON) && !check(TokenType::R_BRACE) && !isAtEnd()) {
            Expr message = parseExpr();
            return TracePayload{candidate, message, false, true};
        }
        return TracePayload{defaultTraceLevel(start), candidate, false, false};
    }
    Expr level = candidate;
    consume(TokenType::L_BRACE, "Expected '{' after " + inlineWord + "trace level");
    skipNewlines();
    Expr message = bindTraceReceiver(arena, parseExpr(), level);
    skipNewlines();
    consume(TokenType::R_BRACE, "Expected '}' after trace message");
    return TracePayload{level, message, true, true};
}

Expr Parser::defaultTraceLevel(const Token& start) {
    ExprCall call;
    call.callee = "__defaultLogLevel";
    call.line = start.line;
    call.column = start.column;
    return make(arena, call);
}

StmtIf* Parser::parseIf() {
    Token start = peek();
    consume(TokenType::IF, "Expected 'if'");
    // The `{` after the condition is the branch body, never a trailing lambda.
    bool savedTrailing = allowTrailingLambda;
    allowTrailingLambda = false;
    Expr condition = parseExpr();
    allowTrailingLambda = savedTrailing;
    std::vector<Stmt> thenBranch = parseThenOrBracedBody("if condition");
    skipNewlines();  // tolerate newlines between } and else
    std::optional<std::vector<Stmt>> elseBranch;
    if (match(TokenType::ELSE)) elseBranch = parseElseBody("if");
    consumeNewline();
    return make(arena, StmtIf{condition, thenBranch, elseBranch, start.line, start.column});
}

StmtInlineIf* Parser::parseInlineIf() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::IF, "Expected 'if' after 'inline'");
    Expr condition = withoutTrailingLambda([&] { return parseExpr(); });
    std::vector<Stmt> thenBranch = parseThenOrBracedBody("inline if condition");
    std::optional<std::vector<Stmt>> elseBranch;
    if (match(TokenType::ELSE)) elseBranch = parseElseBody("inline if");
    consumeNewline();
    return make(arena, StmtInlineIf{condition, thenBranch, elseBranch, start.line, start.column});
}

std::vector<Stmt> Parser::parseBlock() {
    skipNewlines();
    std::vector<Stmt> stmts;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        std::vector<Stmt> more = parseStmts();
        stmts.insert(stmts.end(), more.begin(), more.end());
        skipNewlines();
    }
    return stmts;
}

/**
 * Parses an initializer with the declaration's expected type in scope:
 * `.(args)` builds the declared type, without naming it twice.
 */
Expr Parser::parseInitializer(const TypeRef& expectedType) {
    if (!check(TokenType::DOT)) return parseExpr();
    TypeRefNamed* named = as<TypeRefNamed>(expectedType);
    if (named == nullptr) {
        if (TypeRefNullable* nullable = as<TypeRefNullable>(expectedType)) named = as<TypeRefNamed>(nullable->inner);
    }
    if (named == nullptr) return parseExpr();
    // A type macro names its type only once it expands, which is after parsing.
    if (NamedTypeMacroCall::isCall(*named)) return parseExpr();
    // `.Variant` and `.Variant(payload)` are read against the stated type by the resolver.
    if (!peekNextIs(TokenType::L_PAREN)) return parseExpr();
    Token dot = advance();
    if (check(TokenType::L_PAREN)) {
        advance();
        std::vector<Expr> args = parseCallArgumentList();
        consume(TokenType::R_PAREN, "Expected ')' after '.(' constructor arguments");
        // The declared type's arguments travel with the call.
        ExprCall call;
        call.callee = named->name;
        call.args = args;
        call.line = dot.line;
        call.column = dot.column;
        call.length = u16len(named->name);
        call.typeArgs = named->args;
        Expr callExpr = make(arena, call);
        // `.() * count` - repetition; everything after the `*` is the count.
        if (check(TokenType::STAR)) {
            Token star = advance();
            Expr count = parseExpr();
            return make(arena, ExprBinary{callExpr, TokenType::STAR, count, star.line, star.column, u16len(star.lexeme)});
        }
        return callExpr;
    }
    fail("Expected '(' after '.' at line " + str(dot.line));
}

Expr Parser::parseInitializer(const TypeAnnotation& type) { return parseInitializer(type.ref); }

}  // namespace azora
