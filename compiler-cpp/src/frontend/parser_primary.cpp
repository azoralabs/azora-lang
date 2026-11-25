// Parser.kt lines 12226-13015: if/for expressions, primary expressions,
// collection literals, and lambdas (receivers, captures, generics).
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"

namespace azora {

namespace {

TypeRef anyType(Arena& arena) {
    TypeRefNamed any;
    any.name = "Any";
    any.synthesized = true;
    return make(arena, any);
}

}  // namespace

/**
 * If-expression `if cond { a } else { b }` - each branch holds a single
 * expression; `else if` chains nest.
 */
Expr Parser::parseIfExpr() {
    Token start = consume(TokenType::IF, "Expected 'if'");
    // The branch's `{` must not be mistaken for a trailing lambda.
    Expr condition = withoutTrailingLambda([&] { return parseExpr(); });
    skipNewlines();
    Expr thenExpr;
    if (match(TokenType::THEN)) {
        skipNewlines();
        thenExpr = parseExpr();
    } else {
        consume(TokenType::L_BRACE, "Expected '{' or 'then' after if-expression condition");
        skipNewlines();
        thenExpr = parseExpr();
        skipNewlines();
        consume(TokenType::R_BRACE, "Expected '}' after if-expression value");
    }
    skipNewlines();
    consume(TokenType::ELSE, "Expected 'else' - an if-expression needs both branches");
    skipNewlines();
    Expr elseExpr;
    if (check(TokenType::IF)) {
        elseExpr = parseIfExpr();
    } else if (match(TokenType::THEN)) {
        skipNewlines();
        elseExpr = parseExpr();
    } else if (check(TokenType::L_BRACE)) {
        consume(TokenType::L_BRACE, "Expected '{' or 'then' after 'else'");
        skipNewlines();
        elseExpr = parseExpr();
        skipNewlines();
        consume(TokenType::R_BRACE, "Expected '}' after else-expression value");
    } else {
        elseExpr = parseExpr();
    }
    return make(arena, ExprIfExpr{condition, thenExpr, elseExpr, start.line, start.column});
}

/**
 * `for x in xs { … } else { value }` in expression position, lowered into a
 * binding the loop writes, placed in front of the statement that reads it.
 */
Expr Parser::parseForExpr() {
    Token start = peek();
    Stmt loop = parseFor(std::nullopt, false);
    StmtFor* forLoop = as<StmtFor>(loop);
    if (forLoop == nullptr) fail("a 'for' used as a value has one loop and one 'else' at line " + str(start.line));
    skipNewlines();
    consume(TokenType::ELSE, "Expected 'else' - a 'for' used as a value needs one for when nothing is produced");
    Expr fallback;
    if (match(TokenType::L_BRACE)) {
        skipNewlines();
        fallback = parseExpr();
        skipNewlines();
        consume(TokenType::R_BRACE, "Expected '}' after the else value");
    } else {
        // `... else -1` is equivalent to `... else { -1 }`.
        fallback = parseExpr();
    }
    std::string temp = "__for_value_" + str(forExprCounter++);
    std::pair<std::vector<Stmt>, bool> rewritten = rewriteForYields(forLoop->body, temp);
    if (!rewritten.second) {
        fail("a 'for' used as a value must produce one: no iteration of the loop at line " + str(start.line) +
             " ends in a value, so the answer could only ever be the 'else'");
    }
    StmtVarDecl binding;
    binding.name = temp;
    binding.initializer = fallback;
    binding.line = start.line;
    binding.column = start.column;
    pendingPrelude.push_back(make(arena, binding));
    StmtFor copy = *forLoop;
    copy.body = rewritten.first;
    pendingPrelude.push_back(make(arena, copy));
    return make(arena, ExprIdentifier{temp, start.line, start.column, u16len(temp)});
}

/**
 * Rewrites each value an iteration ends in into a write of [temp]; only a
 * *tail* expression is a value.
 */
std::pair<std::vector<Stmt>, bool> Parser::rewriteForYields(const std::vector<Stmt>& stmts, const std::string& temp) {
    if (stmts.empty()) return {stmts, false};
    std::pair<Stmt, bool> tail = rewriteForYield(stmts.back(), temp);
    std::vector<Stmt> out(stmts.begin(), stmts.end() - 1);
    out.push_back(tail.first);
    return {out, tail.second};
}

std::pair<Stmt, bool> Parser::rewriteForYield(const Stmt& stmt, const std::string& temp) {
    switch (stmt.kind()) {
        case StmtKind::ExprStmt: {
            const StmtExprStmt& s = cast<StmtExprStmt>(stmt);
            StmtAssignment assignment;
            assignment.name = temp;
            assignment.value = s.expr;
            assignment.line = s.line;
            assignment.column = s.column;
            StmtBreak stop;
            stop.line = s.line;
            stop.column = s.column;
            return {make(arena, StmtScope{.body = {make(arena, assignment), make(arena, stop)}, .line = s.line,
                                          .column = s.column, .length = s.length}),
                    true};
        }
        case StmtKind::If: {
            const StmtIf& s = cast<StmtIf>(stmt);
            std::pair<std::vector<Stmt>, bool> thenBranch = rewriteForYields(s.thenBranch, temp);
            std::optional<std::vector<Stmt>> elseBranch;
            bool elseProduces = false;
            if (s.elseBranch) {
                std::pair<std::vector<Stmt>, bool> r = rewriteForYields(*s.elseBranch, temp);
                elseBranch = r.first;
                elseProduces = r.second;
            }
            StmtIf copy = s;
            copy.thenBranch = thenBranch.first;
            copy.elseBranch = elseBranch;
            return {make(arena, copy), thenBranch.second || elseProduces};
        }
        case StmtKind::Scope: {
            StmtScope copy = cast<StmtScope>(stmt);
            std::pair<std::vector<Stmt>, bool> r = rewriteForYields(copy.body, temp);
            copy.body = r.first;
            return {make(arena, copy), r.second};
        }
        case StmtKind::Break: {
            const StmtBreak& s = cast<StmtBreak>(stmt);
            if (!s.value) return {stmt, false};
            StmtAssignment assignment;
            assignment.name = temp;
            assignment.value = s.value;
            assignment.line = s.line;
            assignment.column = s.column;
            StmtBreak stop;
            stop.label = s.label;
            stop.line = s.line;
            stop.column = s.column;
            stop.length = s.length;
            return {make(arena, StmtScope{.body = {make(arena, assignment), make(arena, stop)}, .line = s.line,
                                          .column = s.column, .length = s.length}),
                    true};
        }
        default:
            return {stmt, false};
    }
}

Expr Parser::parsePrimary() {
    // A fragment stands where its expansion stands.
    if (spliceFragmentMacro()) return parseExpr();
    Token tok = peek();
    if (tok.type == TokenType::IDENTIFIER && tok.lexeme == "reverse" && peekNextIs(TokenType::FOR)) {
        fail("the reverse loop modifier was removed; write 'for i in upper>..lower' at line " + str(tok.line));
    }
    // `.Name` - the expected type's member, named without repeating the type.
    if (tok.type == TokenType::DOT && peekNextIs(TokenType::IDENTIFIER)) {
        advance();
        Token name = advance();
        return make(arena, ExprInferredMember{.name = name.lexeme, .line = tok.line, .column = tok.column,
                                              .length = u16len(name.lexeme) + 1});
    }
    // `.(args)` - the expected type's constructor, unnamed.
    if (tok.type == TokenType::DOT && peekNextIs(TokenType::L_PAREN)) {
        advance();
        advance();
        std::vector<Expr> args = parseCallArgumentList();
        consume(TokenType::R_PAREN, "Expected ')' after '.(' constructor arguments");
        return make(arena, ExprInferredMember{.name = "", .line = tok.line, .column = tok.column, .length = 2, .ctorArgs = args});
    }
    // `<T>[receivers; captures] { params -> body }` - a generic lambda.
    if (tok.type == TokenType::LESS) return parseGenericLambda();
    // `@name[…]`, `@name(…)`, `@name{…}` - a macro invocation.
    if (tok.type == TokenType::AT && isMacroInvokeAhead()) {
        Token at = advance();  // '@'
        std::string name = parseQualifiedMacroName();
        std::vector<Expr> args = parseMacroInvokeArgs();
        usedMetaInvoke = true;
        return make(arena, ExprMetaInvoke{name, args, at.line, at.column, u16len(name) + 1});
    }
    switch (tok.type) {
        case TokenType::IF: return parseIfExpr();
        case TokenType::WHEN: return parseWhenExpr();
        // A `for` reached here is one in value position.
        case TokenType::FOR: return parseForExpr();
        case TokenType::INT_LITERAL: {
            advance();
            const NumericLiteral& numLit = std::get<NumericLiteral>(tok.literal);
            return make(arena, ExprIntLiteral{std::get<int64_t>(numLit.value), tok.line, tok.column, u16len(tok.lexeme),
                                              numLit.text, numLit.magnitude});
        }
        case TokenType::DOUBLE_LITERAL: {
            advance();
            const NumericLiteral& numLit = std::get<NumericLiteral>(tok.literal);
            return make(arena, ExprDoubleLiteral{std::get<double>(numLit.value), tok.line, tok.column, u16len(tok.lexeme),
                                                 numLit.text ? numLit.text : tok.lexeme});
        }
        case TokenType::STRING_LITERAL:
            advance();
            return make(arena, ExprStringLiteral{literalString(tok), tok.line, tok.column, u16len(tok.lexeme)});
        case TokenType::INTERPOLATED_STRING: {
            advance();
            const std::vector<StringPart>& parts = std::get<std::vector<StringPart>>(tok.literal);
            std::vector<StringTemplatePart> templateParts;
            for (const StringPart& p : parts) {
                if (p.kind == StringPart::Kind::Literal) templateParts.push_back(literalPart(p.text));
                else templateParts.push_back(exprPart(parseSubExpr(p.text)));
            }
            return make(arena, ExprStringTemplate{templateParts, tok.line, tok.column, u16len(tok.lexeme)});
        }
        case TokenType::CHAR_LITERAL:
            advance();
            return make(arena, ExprCharLiteral{std::get<int32_t>(tok.literal), tok.line, tok.column, u16len(tok.lexeme)});
        case TokenType::TRUE_:
            advance();
            return make(arena, ExprBoolLiteral{true, tok.line, tok.column, u16len(tok.lexeme)});
        case TokenType::FALSE_:
            advance();
            return make(arena, ExprBoolLiteral{false, tok.line, tok.column, u16len(tok.lexeme)});
        case TokenType::NULL_:
            advance();
            return nullLiteral();
        case TokenType::FUNC:
            fail("line " + str(tok.line) + ": lambdas do not use 'func'; write '{ ... }' or '{ params -> ... }'");
        default:
            break;
    }
    // A declaration may be `async func name`, but a lambda is simply `async { ... }`.
    if (tok.type == TokenType::ASYNC && isAsyncFuncAt(current)) {
        fail("line " + str(tok.line) + ": async lambdas do not use 'func'; write 'async { ... }' or 'async [take] { ... }'");
    }
    if (tok.type == TokenType::ASYNC && peekNextIs(TokenType::LESS)) {
        advance();
        ExprLambda* lambda = parseGenericLambda();
        ExprCall call;
        call.callee = "async";
        call.args = {lambda};
        call.line = tok.line;
        call.column = tok.column;
        call.length = u16len(tok.lexeme);
        return make(arena, call);
    }
    if (tok.type == TokenType::IDENTIFIER || tok.type == TokenType::ASYNC) {
        advance();
        // `it` - whether the innermost lambda body mentions it decides whether
        // that lambda has the parameter at all.
        if (tok.lexeme == "it" && !lambdaMentionsIt.empty()) lambdaMentionsIt.back() = true;
        return make(arena, ExprIdentifier{tok.lexeme, tok.line, tok.column, u16len(tok.lexeme)});
    }
    if (tok.type == TokenType::DOUBLE_COLON) {
        advance();  // consume first '::'
        int32_t depth = 1;
        // ::_::_::x pattern - each _:: adds one depth level
        while (check(TokenType::IDENTIFIER) && peek().lexeme == "_" && peekNextIs(TokenType::DOUBLE_COLON)) {
            advance();  // consume '_'
            advance();  // consume '::'
            depth++;
        }
        Token name = consume(TokenType::IDENTIFIER, "Expected identifier after '::'");
        ExprUpperScopeAccess* access = make(arena, ExprUpperScopeAccess{name.lexeme, depth, tok.line, tok.column});
        // `::invalid` - the expected type's static, as `.Name` is its member.
        if (depth == 1) {
            return make(arena, ExprInferredMember{.name = name.lexeme, .line = tok.line, .column = tok.column,
                                                  .length = u16len(name.lexeme) + 2, .scopeRead = access});
        }
        return access;
    }
    if (tok.type == TokenType::L_PAREN) {
        advance();
        if (check(TokenType::R_PAREN)) fail("empty tuple literal '()' is not a value; use Unit at line " + str(tok.line));
        Expr firstElement = withinExpressionDelimiterExpr([&] { return parseExpr(); });
        if (match(TokenType::COMMA)) {
            std::vector<Expr> elements{firstElement};
            skipNewlines();
            while (!check(TokenType::R_PAREN)) {
                elements.push_back(withinExpressionDelimiterExpr([&] { return parseExpr(); }));
                skipNewlines();
                if (!match(TokenType::COMMA)) break;
                skipNewlines();
            }
            consume(TokenType::R_PAREN, "Expected ')' after tuple literal");
            if (elements.size() == 1) fail(oneElementTuple(tok.line, "value"));
            return make(arena, ExprTupleLit{elements, tok.line, tok.column});
        }
        consume(TokenType::R_PAREN, "Expected ')'");
        return make(arena, ExprGrouping{firstElement, tok.line, tok.column});
    }
    // `[self: Vec2&]{ … }` - a lambda binding named receivers.
    if (tok.type == TokenType::L_BRACKET && allowTrailingLambda && isReceiverLambdaAhead()) return parseReceiverLambda();
    if (tok.type == TokenType::L_BRACKET) {
        advance();
        skipNewlines();
        if (check(TokenType::R_BRACKET)) {
            advance();
            return make(arena, ExprArrayLiteral{{}, tok.line, tok.column});
        }
        if (match(TokenType::COLON)) {
            skipNewlines();
            consume(TokenType::R_BRACKET, "Expected ']' after empty associative literal ':'");
            return make(arena, ExprMapLit{{}, tok.line, tok.column});
        }
        rejectLiteralSpread();
        Expr firstElement = withinExpressionDelimiterExpr([&] { return parseExpr(); });
        if (match(TokenType::COLON)) {
            // Associative collection literal: [key: value, ...]
            Expr firstValue = withinExpressionDelimiterExpr([&] { return parseExpr(); });
            std::vector<std::pair<Expr, Expr>> entries{{firstElement, firstValue}};
            skipNewlines();
            while (match(TokenType::COMMA) || startsNextElementLine()) {
                skipNewlines();
                if (check(TokenType::R_BRACKET)) break;
                Expr k = withinExpressionDelimiterExpr([&] { return parseExpr(); });
                consume(TokenType::COLON, "Expected ':' in associative collection literal");
                Expr v = withinExpressionDelimiterExpr([&] { return parseExpr(); });
                entries.emplace_back(k, v);
                skipNewlines();
            }
            consume(TokenType::R_BRACKET, "Expected ']' after associative collection literal");
            return make(arena, ExprMapLit{entries, tok.line, tok.column});
        }
        // Sequence collection literal: [value, ...]
        std::vector<Expr> elements{firstElement};
        skipNewlines();
        while (match(TokenType::COMMA) || startsNextElementLine()) {
            skipNewlines();
            if (check(TokenType::R_BRACKET)) break;
            rejectLiteralSpread();
            elements.push_back(withinExpressionDelimiterExpr([&] { return parseExpr(); }));
            skipNewlines();
        }
        consume(TokenType::R_BRACKET, "Expected ']' after collection literal");
        return make(arena, ExprArrayLiteral{elements, tok.line, tok.column});
    }
    if (tok.type == TokenType::L_BRACE) return parseLambda(tok.line, tok.column);
    fail("Unexpected token '" + tok.lexeme + "' at line " + str(tok.line));
}

/** withinExpressionDelimiter for one expression. */
Expr Parser::withinExpressionDelimiterExpr(const std::function<Expr()>& parse) {
    bool saved = allowTrailingLambda;
    allowTrailingLambda = true;
    Finally restore{[&] { allowTrailingLambda = saved; }};
    return parse();
}

/** True when the next element of a collection literal opens a line of its own. */
bool Parser::startsNextElementLine() {
    if (check(TokenType::R_BRACKET) || isAtEnd()) return false;
    const Token* previous = tokenOrNull(current - 1);
    if (previous == nullptr) return false;
    return peek().line > previous->line;
}

/**
 * True when a `[` opens a lambda's bracket list rather than an array literal:
 * what decides it is the `{` after the matching `]`.
 */
bool Parser::isReceiverLambdaAhead(int32_t startIndex) {
    int depth = 0;
    int32_t i = startIndex;
    while (i < (int32_t)tokens.size()) {
        TokenType t = tokens[(size_t)i].type;
        if (t == TokenType::L_BRACKET) {
            depth++;
        } else if (t == TokenType::R_BRACKET) {
            depth--;
            if (depth == 0) {
                int32_t after = i + 1;
                while (typeIs(after, TokenType::NEWLINE)) after++;
                // A receiver is written beside the list it follows.
                bool sameLine = after == i + 1;
                const Token* afterToken = tokenOrNull(after);
                if (afterToken == nullptr) return false;
                switch (afterToken->type) {
                    case TokenType::L_BRACE:
                        return true;
                    case TokenType::L_BRACKET:
                        return isReceiverLambdaAhead(after);
                    // `[&] value { … }` and `[&] value: Context { … }`.
                    case TokenType::IDENTIFIER: {
                        if (!sameLine) return false;
                        int32_t j = after + 1;
                        int nested = 0;
                        while (j < (int32_t)tokens.size()) {
                            TokenType tj = tokens[(size_t)j].type;
                            if (tj == TokenType::L_PAREN || tj == TokenType::LESS) {
                                nested++;
                            } else if (tj == TokenType::R_PAREN || tj == TokenType::GREATER) {
                                if (nested > 0) nested--;
                            } else if (tj == TokenType::L_BRACE) {
                                if (nested == 0) return true;
                            } else if (tj == TokenType::NEWLINE || tj == TokenType::EOF_) {
                                if (nested == 0) return false;
                            }
                            j++;
                        }
                        return false;
                    }
                    // `[&, !] (source, destination) { … }`.
                    case TokenType::L_PAREN: {
                        int groupDepth = 0;
                        int32_t j = after;
                        while (j < (int32_t)tokens.size()) {
                            TokenType tj = tokens[(size_t)j].type;
                            if (tj == TokenType::L_PAREN) {
                                groupDepth++;
                            } else if (tj == TokenType::R_PAREN) {
                                groupDepth--;
                                if (groupDepth == 0) {
                                    int32_t tail = j + 1;
                                    while (typeIs(tail, TokenType::NEWLINE)) tail++;
                                    return typeIs(tail, TokenType::L_BRACE) ||
                                           (typeIs(tail, TokenType::L_BRACKET) && isReceiverLambdaAhead(tail));
                                }
                            } else if (tj == TokenType::EOF_) {
                                return false;
                            }
                            j++;
                        }
                        return false;
                    }
                    default:
                        return false;
                }
            }
        } else if (t == TokenType::EOF_) {
            return false;
        }
        i++;
    }
    return false;
}

bool Parser::isReceiverLambdaAhead() { return isReceiverLambdaAhead(current); }

/** The single ownership header: `[modes, captures, without names] receivers? { … }`. */
ExprLambda* Parser::parseReceiverLambda(const std::vector<std::string>& typeParams /*= {}*/,
                                        const std::optional<std::string>& variadicTypeParam /*= std::nullopt*/,
                                        CallableKind kind /*= CallableKind::FUNC*/) {
    Token start = advance();  // '['
    std::vector<Capture> captures;
    std::vector<CaptureExclusion> exclusions;
    std::vector<std::pair<CaptureMode, Token>> defaults;
    skipNewlines();
    while (!check(TokenType::R_BRACKET) && !isAtEnd()) {
        if (check(TokenType::SEMICOLON)) {
            fail("line " + str(peek().line) +
                 ": the '[receivers; captures]' lambda form was removed; use one ownership header, for example '[&] "
                 "receiver { … }' or '[value.&] { … }'");
        }
        if (isCaptureDefaultAhead()) {
            Token token = advance();
            CaptureMode mode;
            if (token.type == TokenType::EQUAL) mode = CaptureMode::COPY;
            else if (token.type == TokenType::AMP) mode = CaptureMode::SHARED;
            else if (token.type == TokenType::BANG) mode = CaptureMode::MUTABLE;
            else mode = CaptureMode::MOVE;
            defaults.emplace_back(mode, token);
        } else if (check(TokenType::WITHOUT)) {
            std::vector<CaptureExclusion> more = parseLambdaCaptureExclusions();
            exclusions.insert(exclusions.end(), more.begin(), more.end());
        } else {
            captures.push_back(parseCapture());
        }
        const Token* previous = tokenOrNull(current - 1);
        int32_t previousLine = previous ? previous->line : peek().line;
        bool separatedByNewline = check(TokenType::NEWLINE) || peek().line > previousLine;
        if (check(TokenType::NEWLINE)) skipNewlines();
        if (match(TokenType::COMMA)) {
            skipNewlines();
        } else if (check(TokenType::R_BRACKET)) {
        } else if (separatedByNewline) {
        } else {
            fail("Expected ',' or a new line between lambda ownership entries at line " + str(peek().line));
        }
    }
    consume(TokenType::R_BRACKET, "Expected ']' after lambda bracket list");
    skipNewlines();

    std::vector<Param> receivers;
    if (check(TokenType::IDENTIFIER)) {
        receivers.push_back(parseLambdaReceiverName());
    } else if (match(TokenType::L_PAREN)) {
        skipNewlines();
        while (true) {
            receivers.push_back(parseLambdaReceiverName());
            skipNewlines();
            if (!match(TokenType::COMMA)) break;
            skipNewlines();
        }
        consume(TokenType::R_PAREN, "Expected ')' after lambda receiver names");
        if (receivers.size() < 2) {
            fail("A single lambda receiver name is written without parentheses at line " + str(start.line));
        }
        skipNewlines();
    }
    if (check(TokenType::L_BRACKET)) {
        fail("line " + str(peek().line) +
             ": a lambda has only one ownership header; merge capture overrides into the first '[…]' header");
    }
    std::optional<CaptureMode> captureDefault;
    if (defaults.size() <= 1) {
        if (defaults.size() == 1) captureDefault = defaults[0].first;
    } else if (receivers.empty()) {
        fail("line " + str(defaults[1].second.line) + ": a lambda has only one capture default");
    } else if (defaults.size() != receivers.size()) {
        fail("line " + str(start.line) + ": " + str(defaults.size()) + " receiver modes cannot describe " +
             str(receivers.size()) + " receivers");
    } else {
        for (const auto& d : defaults) {
            if (d.first == CaptureMode::COPY) {
                fail("line " + str(start.line) + ": '=' is a capture default, not a receiver capability");
            }
        }
        // positional mixed receiver modes have no capture default
    }
    std::vector<CaptureMode> receiverModes;
    if (receivers.empty() || defaults.empty()) {
    } else if (defaults.size() == 1) {
        receiverModes.assign(receivers.size(), defaults[0].first);
    } else {
        for (const auto& d : defaults) receiverModes.push_back(d.first);
    }
    for (size_t index = 0; index < receiverModes.size(); index++) {
        CaptureMode mode = receiverModes[index];
        if (mode == CaptureMode::SHARED) receivers[index].modifier = ParamModifier::SHARED;
        else if (mode == CaptureMode::MUTABLE) receivers[index].modifier = ParamModifier::EXCLUSIVE;
        else if (mode == CaptureMode::MOVE) receivers[index].modifier = ParamModifier::NONE;
    }
    if (!exclusions.empty() && !captureDefault) {
        fail("line " + str(first(exclusions).line) + ": 'without' requires a capture default such as '[&, without name]'");
    }
    OrderedSet<std::string> excludedSources;
    for (const CaptureExclusion& e : exclusions) excludedSources.add(e.source);
    for (const Capture& c : captures) {
        if (excludedSources.contains(c.source)) {
            fail("line " + str(c.line) + ": '" + c.source + "' cannot be both captured and excluded with 'without'");
        }
    }
    if (captureDefault) {
        for (const Capture& c : captures) {
            if (c.mode == *captureDefault) {
                fail("line " + str(c.line) + ": '" + c.source + "' repeats the '" + spelling(*captureDefault) +
                     "' capture default; remove the redundant explicit capture");
            }
        }
    }
    ExprLambda* lambda = parseLambda(start.line, start.column, true, CallableKind::FUNC, typeParams, variadicTypeParam);
    ExprLambda copy = *lambda;
    copy.receivers = receivers;
    copy.captures = captures;
    copy.captureDefault = captureDefault;
    copy.captureExclusions = exclusions;
    copy.kind = kind;
    return make(arena, copy);
}

/** parseReceiverLambda(kind = …) - Kotlin's named-argument call. */
ExprLambda* Parser::parseReceiverLambda(CallableKind kind) { return parseReceiverLambda({}, std::nullopt, kind); }

/** One inferred receiver alias after a lambda ownership header. */
Param Parser::parseLambdaReceiverName() {
    if (check(TokenType::ELLIPSIS)) fail("line " + str(peek().line) + ": contextual receivers cannot be variadic");
    std::string name = consumeIdentifierLike("Expected a lambda receiver name");
    Param param;
    param.name = name;
    if (!match(TokenType::COLON)) {
        param.type = anyType(arena);
        return param;
    }
    TypeRef annotated = parseTypeName();
    if (TypeRefReference* reference = as<TypeRefReference>(annotated)) {
        fail("line " + str(peek().line) + ": receiver ownership belongs in the lambda header; write '[" +
             (reference->kind == RefKind::MUTABLE ? "!" : "&") + "] " + name + ": " + toString(reference->inner) + "'");
    }
    param.type = annotated;
    return param;
}

/** `without name` or an explicit list inside a lambda ownership header. */
std::vector<CaptureExclusion> Parser::parseLambdaCaptureExclusions() {
    Token keyword = consume(TokenType::WITHOUT, "Expected 'without'");
    std::vector<CaptureExclusion> out;
    bool isRow = parseParenthesizedList("excluded capture", [&] {
        Token token = peek();
        std::string name = consumeIdentifierLike("Expected an excluded binding name");
        out.push_back(CaptureExclusion{name, token.line, token.column});
    });
    if (!isRow) {
        std::string name = consumeIdentifierLike("Expected a binding name after 'without'");
        out.push_back(CaptureExclusion{name, keyword.line, keyword.column});
    }
    return out;
}

/** True when the cursor is on a bare `=` / `&` / `!` / `take` default. */
bool Parser::isCaptureDefaultAhead() {
    TokenType t = peek().type;
    if (t != TokenType::EQUAL && t != TokenType::AMP && t != TokenType::BANG && t != TokenType::TAKE) return false;
    return peekNextIs(TokenType::COMMA) || peekNextIs(TokenType::R_BRACKET);
}

/**
 * One ownership-header entry: `value`, `value.&`, `value.!`, `value.clone()`,
 * `take value`, and each with an `alias = ` in front.
 */
Capture Parser::parseCapture() {
    Token start = peek();
    std::optional<std::string> alias;
    if (check(TokenType::IDENTIFIER) && peekNextIs(TokenType::EQUAL)) {
        alias = advance().lexeme;
        advance();  // '='
    }
    // `[take socket]` - ownership moves in.
    if (check(TokenType::TAKE)) {
        advance();
        std::string name = consumeIdentifierLike("Expected a name after 'take' in a capture list");
        return Capture{alias ? *alias : name, name, CaptureMode::MOVE, start.line, start.column};
    }
    std::string name = consumeIdentifierLike("Expected a capture name in a lambda bracket list");
    if (check(TokenType::BANG)) {
        fail("line " + str(start.line) + ": mutable capture borrows use the dotted ownership spelling; write '" + name +
             ".!'");
    }
    if (check(TokenType::AMP)) {
        fail("line " + str(start.line) + ": capture borrows use the dotted ownership spelling; write '" + name + "." +
             peek().lexeme + "'");
    }
    std::string used = alias ? *alias : name;
    if (!match(TokenType::DOT)) return Capture{used, name, CaptureMode::COPY, start.line, start.column};
    if (match(TokenType::AMP)) return Capture{used, name, CaptureMode::SHARED, start.line, start.column};
    if (match(TokenType::BANG)) return Capture{used, name, CaptureMode::MUTABLE, start.line, start.column};
    if (check(TokenType::IDENTIFIER) && peek().lexeme == "clone") {
        advance();
        consume(TokenType::L_PAREN, "Expected '()' after 'clone' in a capture list");
        consume(TokenType::R_PAREN, "Expected '()' after 'clone' in a capture list");
        return Capture{used, name, CaptureMode::CLONE, start.line, start.column};
    }
    fail("line " + str(start.line) + ": '" + name + "." + peek().lexeme + "' is not a capture - write '" + name +
         "' to copy, '" + name + ".&' or '" + name + ".!' to reference, '" + name + ".clone()' to clone, or 'take " + name +
         "' to move");
}

ExprLambda* Parser::parseLambda(int32_t line, int32_t column, bool implicitIt /*= true*/,
                                CallableKind kind /*= CallableKind::FUNC*/,
                                const std::vector<std::string>& typeParams /*= {}*/,
                                const std::optional<std::string>& variadicTypeParam /*= std::nullopt*/) {
    consume(TokenType::L_BRACE, "Expected '{'");
    skipNewlines();
    std::vector<Param> params;
    if ((check(TokenType::IDENTIFIER) && peekNextIs(TokenType::COLON)) || check(TokenType::ELLIPSIS)) {
        do {
            bool nameSpread = match(TokenType::ELLIPSIS);
            std::string name = consumeIdentifierLike("Expected lambda parameter name");
            consume(TokenType::COLON, "Expected ':' after lambda parameter name");
            bool typeSpread = check(TokenType::ELLIPSIS);
            if (typeSpread) {
                std::optional<Token> after = peekNext();
                std::optional<std::string> spreadName;
                if (after && after->type == TokenType::IDENTIFIER) spreadName = after->lexeme;
                if (!variadicTypeParam || spreadName != variadicTypeParam) {
                    std::string spread = spreadName ? *spreadName : "";
                    fail("line " + str(peek().line) + ": type spread '..." + spread +
                         "' requires the matching variadic generic '<..." + spread + ">'");
                }
            }
            TypeRef parsedType = parseTypeName();
            TypeRefReference* reference = as<TypeRefReference>(parsedType);
            TypeRef rawType = reference ? reference->inner : parsedType;
            TypeRef parameterType = rawType;
            if (nameSpread && !is<TypeRefArray>(rawType)) parameterType = make(arena, TypeRefArray{rawType});
            Param param;
            param.name = name;
            param.type = parameterType;
            param.modifier = reference ? paramModifier(reference->kind) : ParamModifier::NONE;
            param.variadic = nameSpread || typeSpread;
            params.push_back(param);
            for (size_t k = 0; k + 1 < params.size(); k++) {
                if (params[k].variadic) fail("line " + str(line) + ": a variadic lambda parameter must be last");
            }
        } while (match(TokenType::COMMA));
        consume(TokenType::ARROW, "Expected '->' in lambda");
    } else if (isUntypedLambdaParamsAhead()) {
        // Untyped params: `{ it -> … }`, `{ a, b -> … }`.
        do {
            std::string name = consumeIdentifierLike("Expected lambda parameter name");
            Param param;
            param.name = name;
            param.type = anyType(arena);
            params.push_back(param);
        } while (match(TokenType::COMMA));
        consume(TokenType::ARROW, "Expected '->' in lambda");
    } else if (check(TokenType::ARROW)) {
        fail("line " + str(peek().line) + ": a lambda with no parameters writes no '->' - write '{ … }' rather than '{ -> … }'");
    }
    // Whether a bare `{ body }` takes a parameter is decided by the body.
    bool paramsWritten = !params.empty();
    skipNewlines();
    lambdaMentionsIt.push_back(false);
    std::vector<Stmt> body = parseBlock();
    bool mentionsIt = lambdaMentionsIt.back();
    lambdaMentionsIt.pop_back();
    if (!body.empty()) {
        if (StmtExprStmt* lastStmt = as<StmtExprStmt>(body.back())) {
            body.pop_back();
            body.push_back(make(arena, StmtReturn{lastStmt->expr, lastStmt->line, lastStmt->column, lastStmt->length, true}));
        }
    }
    consume(TokenType::R_BRACE, "Expected '}' after lambda body");
    // A bare lambda takes the parameter named `it` only when its body reads one.
    if (!paramsWritten && implicitIt && mentionsIt) {
        Param it;
        it.name = "it";
        it.type = anyType(arena);
        params.push_back(it);
    }
    ExprLambda lambda;
    lambda.params = params;
    lambda.body = body;
    lambda.line = line;
    lambda.column = column;
    lambda.variadic = !params.empty() && params.back().variadic;
    lambda.kind = kind;
    lambda.paramsWritten = paramsWritten;
    lambda.typeParams = typeParams;
    lambda.variadicTypeParam = variadicTypeParam;
    return make(arena, lambda);
}

/** Parses `<T, ...U>[receivers; captures] { parameters -> body }`. */
ExprLambda* Parser::parseGenericLambda() {
    Token start = peek();
    TypeParams generic = parseTypeParams();
    refuseBounds(generic, "a lambda", start.line);
    if (!generic.constParams.empty()) {
        fail("line " + str(start.line) + ": lambda generics are types; const generic parameters are not supported here");
    }
    if (check(TokenType::L_BRACKET) && isReceiverLambdaAhead()) {
        return parseReceiverLambda(generic.names, generic.variadic);
    }
    if (check(TokenType::L_BRACE)) {
        return parseLambda(start.line, start.column, true, CallableKind::FUNC, generic.names, generic.variadic);
    }
    fail("line " + str(start.line) + ": expected a receiver/capture list or lambda body after generic parameters");
}

/**
 * True when the tokens open an untyped lambda parameter list -
 * `IDENT (',' IDENT)* '->'`.
 */
bool Parser::isUntypedLambdaParamsAhead() {
    int32_t i = current;
    if (!typeIs(i, TokenType::IDENTIFIER)) return false;
    while (true) {
        if (!typeIs(i, TokenType::IDENTIFIER)) return false;
        if (typeIs(i + 1, TokenType::ARROW)) return true;
        if (typeIs(i + 1, TokenType::COMMA)) {
            i += 2;
            continue;
        }
        return false;
    }
}

}  // namespace azora
