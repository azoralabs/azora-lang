// Parser.kt lines 10229-11166: tuple bindings and assignments, `var`/`fin`/
// `let`/`lazy`, `return` (with `return if/when/for`), the expression form of
// `when`, expression statements and call argument lists.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"

namespace azora {

/** `fin (x, y) = pair` - evaluate one tuple value and bind its positions. */
Stmt Parser::parseTupleBinding(const Token& start, const std::string& keyword) {
    consume(TokenType::L_PAREN, "Expected '(' to open a tuple destructuring pattern");
    std::vector<std::string> names;
    std::vector<TypeAnnotation> types;
    skipNewlines();
    while (!check(TokenType::R_PAREN) && !isAtEnd()) {
        names.push_back(consumeIdentifierLike("Expected a binding name in tuple destructuring"));
        TypeAnnotation type;
        if (match(TokenType::COLON)) type = explicitType(parseTypeName());
        types.push_back(type);
        skipNewlines();
        if (!match(TokenType::COMMA)) break;
        skipNewlines();
    }
    consume(TokenType::R_PAREN, "Expected ')' after tuple destructuring pattern");
    if (names.size() < 2) fail("tuple destructuring needs at least two bindings at line " + str(start.line));
    if (names.size() != distinct(names).size()) {
        fail("tuple destructuring binds a name more than once at line " + str(start.line));
    }
    consume(TokenType::EQUAL, "Expected '=' after tuple destructuring pattern");
    Expr value = parseExpr();
    consumeNewline();
    std::string tempName = "__tuple_value_" + str(tupleValueCounter++);
    StmtFinDecl temp;
    temp.name = tempName;
    temp.initializer = value;
    temp.line = start.line;
    temp.column = start.column;
    std::vector<Stmt> declarations;
    for (size_t index = 0; index < names.size(); index++) {
        Expr target = make(arena, ExprIdentifier{tempName, start.line, start.column, u16len(tempName)});
        Expr access = make(arena, ExprTupleAccess{target, (int32_t)index, start.line, start.column});
        const std::string& name = names[index];
        if (keyword == "var" || keyword == "val") {
            StmtVarDecl decl;
            decl.name = name;
            decl.type = types[index];
            decl.initializer = access;
            decl.line = start.line;
            decl.column = start.column;
            decl.valueMutable = keyword == "var";
            declarations.push_back(make(arena, decl));
        } else if (keyword == "let") {
            declarations.push_back(make(arena, StmtLetDecl{name, types[index], access, start.line, start.column}));
        } else {
            StmtFinDecl decl;
            decl.name = name;
            decl.type = types[index];
            decl.initializer = access;
            decl.line = start.line;
            decl.column = start.column;
            declarations.push_back(make(arena, decl));
        }
    }
    pendingStmts.insert(pendingStmts.end(), declarations.begin(), declarations.end());
    return make(arena, temp);
}

bool Parser::isTupleAssignmentAhead() {
    int32_t i = current;
    int depth = 0;
    while (i < (int32_t)tokens.size()) {
        TokenType t = tokens[(size_t)i].type;
        if (t == TokenType::L_PAREN) {
            depth++;
        } else if (t == TokenType::R_PAREN) {
            depth--;
            if (depth == 0) return typeIs(i + 1, TokenType::EQUAL);
        } else if (t == TokenType::NEWLINE || t == TokenType::EOF_) {
            if (depth == 0) return false;
        }
        i++;
    }
    return false;
}

/** `(x, obj.y) = pair` - one tuple evaluation followed by positional writes. */
Stmt Parser::parseTupleAssignment() {
    Token start = peek();
    consume(TokenType::L_PAREN, "Expected '(' to open tuple assignment");
    std::vector<Expr> targets;
    do {
        skipNewlines();
        targets.push_back(parseExpr());
        skipNewlines();
    } while (match(TokenType::COMMA));
    consume(TokenType::R_PAREN, "Expected ')' after tuple assignment targets");
    if (targets.size() < 2) fail("tuple assignment needs at least two targets at line " + str(start.line));
    consume(TokenType::EQUAL, "Expected '=' after tuple assignment targets");
    Expr value = parseExpr();
    consumeNewline();
    std::string tempName = "__tuple_value_" + str(tupleValueCounter++);
    StmtFinDecl temp;
    temp.name = tempName;
    temp.initializer = value;
    temp.line = start.line;
    temp.column = start.column;
    std::vector<Stmt> body{make(arena, temp)};
    for (size_t index = 0; index < targets.size(); index++) {
        Expr tempRead = make(arena, ExprIdentifier{tempName, start.line, start.column, u16len(tempName)});
        Expr access = make(arena, ExprTupleAccess{tempRead, (int32_t)index, start.line, start.column});
        body.push_back(buildAssignment(targets[index], access, start.line, start.column));
    }
    return make(arena, StmtScope{.body = body, .line = start.line, .column = start.column});
}

Stmt Parser::parseVarDecl() {
    Token start = peek();
    bool valueMutable = advance().type == TokenType::VAR;  // 'var' or 'val'
    if (check(TokenType::L_PAREN)) return parseTupleBinding(start, valueMutable ? "var" : "val");
    std::string name = consumeIdentifierLike("Expected variable name");
    TypeAnnotation type;
    if (match(TokenType::COLON)) type = explicitType(parseTypeName());
    consume(TokenType::EQUAL, "Expected '=' in declaration");
    Expr init = parseInitializer(type);
    consumeNewline();
    StmtVarDecl decl;
    decl.name = name;
    decl.type = type;
    decl.initializer = init;
    decl.line = start.line;
    decl.column = start.column;
    decl.valueMutable = valueMutable;
    return make(arena, decl);
}

Stmt Parser::parseFinDecl() {
    Token start = peek();
    advance();  // consume 'fin'
    if (check(TokenType::L_PAREN)) return parseTupleBinding(start, "fin");
    std::string name = consumeIdentifierLike("Expected variable name");
    TypeAnnotation type;
    if (match(TokenType::COLON)) type = explicitType(parseTypeName());
    consume(TokenType::EQUAL, "Expected '=' in declaration");
    Expr init = parseInitializer(type);
    consumeNewline();
    StmtFinDecl decl;
    decl.name = name;
    decl.type = type;
    decl.initializer = init;
    decl.line = start.line;
    decl.column = start.column;
    return make(arena, decl);
}

StmtLetDecl* Parser::parseLetDecl() {
    Token start = peek();
    advance();  // consume 'let'
    std::string name = consumeIdentifierLike("Expected variable name");
    TypeAnnotation type;
    if (match(TokenType::COLON)) type = explicitType(parseTypeName());
    consume(TokenType::EQUAL, "Expected '=' in let declaration");
    Expr init = parseInitializer(type);
    consumeNewline();
    return make(arena, StmtLetDecl{name, type, init, start.line, start.column});
}

/** `lazy fin` / `lazy let`: evaluate the initializer once, on first read. */
Stmt Parser::parseLazyDecl() {
    Token start = advance();  // lazy
    // A lazy binding is one name and one initializer.
    if (check(TokenType::FIN) && peekNextIs(TokenType::L_BRACE)) {
        fail("'lazy' takes a single binding at line " + str(start.line) + ", not a group");
    }
    if (check(TokenType::LET) && peekNextIs(TokenType::L_BRACE)) {
        fail("'lazy' takes a single binding at line " + str(start.line) + ", not a group");
    }
    if (check(TokenType::FIN)) {
        StmtFinDecl copy = cast<StmtFinDecl>(parseFinDecl());
        copy.line = start.line;
        copy.column = start.column;
        copy.lazy = true;
        return make(arena, copy);
    }
    if (check(TokenType::LET)) {
        StmtLetDecl copy = *parseLetDecl();
        copy.line = start.line;
        copy.column = start.column;
        copy.lazy = true;
        return make(arena, copy);
    }
    if (check(TokenType::VAR) || check(TokenType::VAL)) {
        fail("'lazy' requires 'fin' or 'let' at line " + str(start.line) +
             "; a rebindable lazy name has ambiguous initialization semantics");
    }
    fail("Expected 'fin' or 'let' after 'lazy' at line " + str(start.line));
}

Stmt Parser::parseReturn() {
    Token start = peek();
    consume(TokenType::RETURN, "Expected 'return'");
    if (!check(TokenType::WHEN) && !check(TokenType::IF) &&
        (check(TokenType::NEWLINE) || check(TokenType::R_BRACE) || isAtEnd())) {
        consumeNewline();
        return make(arena, StmtReturn{.line = start.line, .column = start.column});
    }
    return parseReturnTail(start);
}

/**
 * What a `return` returns. Also a failable function's `= expr` body, which is
 * `{ return expr }`.
 */
Stmt Parser::parseReturnTail(const Token& start) {
    // `return when …` / `return if …` - a branching construct in return position.
    if (check(TokenType::WHEN)) return parseReturnWhen(start);
    if (check(TokenType::IF)) return parseReturnIf(start);
    if (check(TokenType::FOR)) return parseReturnFor(start);
    Stmt stmt = parseReturnedValue(start);
    consumeNewline();
    return stmt;
}

/**
 * A declaration's `= expr` body: `{ return expr }`. A value whose reading put
 * statements before it keeps them in front of the `return`.
 */
std::vector<Stmt> Parser::parseExpressionBody() {
    if (!currentFailSets.empty() || check(TokenType::FOR)) return {parseReturnTail(peek())};
    Expr expr = parseExpr();
    consumeNewline();
    std::vector<Stmt> before = pendingPrelude;
    pendingPrelude.clear();
    before.push_back(make(arena, StmtReturn{.value = expr, .line = expr.line(), .column = expr.column()}));
    return before;
}

/**
 * `return for x in xs { … } else value`: each value an iteration ends in
 * becomes a `return` of it, and the `else` value the `return` after the loop.
 */
Stmt Parser::parseReturnFor(const Token& start) {
    Stmt loop = parseFor(std::nullopt, false);
    StmtFor* forLoop = as<StmtFor>(loop);
    if (forLoop == nullptr) fail("a 'for' used as a value has one loop and one 'else' at line " + str(start.line));
    skipNewlines();
    consume(TokenType::ELSE, "Expected 'else' - 'return for' needs one for when nothing is produced");
    // The `else` is a return branch like `return if`'s.
    std::vector<Stmt> fallback = parseReturnBranchBody("else");
    std::pair<std::vector<Stmt>, bool> rewritten = rewriteForReturns(forLoop->body);
    if (!rewritten.second) {
        fail("a 'for' used as a value must produce one: no iteration of the loop at line " + str(start.line) +
             " ends in a value, so the answer could only ever be the 'else'");
    }
    StmtFor copy = *forLoop;
    copy.body = rewritten.first;
    std::vector<Stmt> body{make(arena, copy)};
    body.insert(body.end(), fallback.begin(), fallback.end());
    return make(arena, StmtScope{.body = body, .line = start.line, .column = start.column});
}

/** rewriteForYields for `return for`: a produced value is returned. */
std::pair<std::vector<Stmt>, bool> Parser::rewriteForReturns(const std::vector<Stmt>& stmts) {
    bool produces = false;
    std::vector<Stmt> out;
    for (size_t index = 0; index < stmts.size(); index++) {
        std::pair<Stmt, bool> r = rewriteForReturn(stmts[index], index == stmts.size() - 1);
        produces = produces || r.second;
        out.push_back(r.first);
    }
    return {out, produces};
}

std::pair<Stmt, bool> Parser::rewriteForReturn(const Stmt& stmt, bool tail) {
    switch (stmt.kind()) {
        case StmtKind::ExprStmt: {
            const StmtExprStmt& s = cast<StmtExprStmt>(stmt);
            if (!tail) return {stmt, false};
            return {make(arena, StmtReturn{s.expr, s.line, s.column, s.length}), true};
        }
        case StmtKind::If: {
            const StmtIf& s = cast<StmtIf>(stmt);
            std::pair<std::vector<Stmt>, bool> thenBranch = rewriteForReturns(s.thenBranch);
            std::optional<std::vector<Stmt>> elseBranch;
            bool elseProduces = false;
            if (s.elseBranch) {
                std::pair<std::vector<Stmt>, bool> r = rewriteForReturns(*s.elseBranch);
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
            std::pair<std::vector<Stmt>, bool> r = rewriteForReturns(copy.body);
            copy.body = r.first;
            return {make(arena, copy), r.second};
        }
        case StmtKind::Break: {
            const StmtBreak& s = cast<StmtBreak>(stmt);
            if (s.value && !s.label) return {make(arena, StmtReturn{s.value, s.line, s.column, s.length}), true};
            return {stmt, false};
        }
        default:
            return {stmt, false};
    }
}

/**
 * The statement a `return` produces once its value has been read: `.Variant`
 * fails the function (where the signature can fail), anything else returns.
 */
Stmt Parser::parseReturnedValue(const Token& start) {
    if (check(TokenType::DOT) && peekNextIs(TokenType::IDENTIFIER) && !currentFailSets.empty()) {
        advance();  // '.'
        std::string variant = consume(TokenType::IDENTIFIER, "Expected error variant after 'return .'").lexeme;
        // `return .OutOfBounds(i, n)` - an error variant carrying data.
        if (check(TokenType::L_PAREN)) {
            if (currentFailSets.size() != 1) {
                fail("cannot tell which error set '" + variant + "' belongs to at line " + str(peek().line) +
                     "; a function returning a payload must declare exactly one '?!' set");
            }
            advance();  // '('
            std::vector<Expr> args;
            if (!check(TokenType::R_PAREN)) {
                do {
                    args.push_back(parseExpr());
                } while (match(TokenType::COMMA));
            }
            consume(TokenType::R_PAREN, "Expected ')' after error payload");
            std::string set = single(currentFailSets);
            ExprMethodCall call;
            call.target = make(arena, ExprIdentifier{set, start.line, start.column, u16len(set)});
            call.name = variant;
            call.args = args;
            call.line = start.line;
            call.column = start.column;
            return make(arena, StmtThrow{make(arena, call), start.line, start.column, 0, true});
        }
        return make(arena, StmtThrow{make(arena, ExprStringLiteral{variant, start.line}), start.line, start.column, 0, true});
    }
    Expr value = parseExpr();
    return make(arena, StmtReturn{.value = value, .line = start.line, .column = start.column});
}

/**
 * `return if cond { value } else { value }`: the statement form with every
 * branch body a `return`.
 */
Stmt Parser::parseReturnIf(const Token& start) {
    consume(TokenType::IF, "Expected 'if'");
    bool savedTrailing = allowTrailingLambda;
    allowTrailingLambda = false;
    Expr condition = parseExpr();
    allowTrailingLambda = savedTrailing;
    std::vector<Stmt> thenBranch = parseReturnBranchBody("if");
    skipNewlines();  // tolerate newlines between } and else
    if (!match(TokenType::ELSE)) {
        fail("'return if' needs an 'else' - every path has to produce a value, at line " + str(start.line));
    }
    std::vector<Stmt> elseBranch;
    if (check(TokenType::IF)) elseBranch = {parseReturnIf(start)};
    else elseBranch = parseReturnBranchBody("else");
    consumeNewline();
    return make(arena, StmtIf{condition, thenBranch, elseBranch, start.line, start.column});
}

/**
 * One `{ … }` branch of a `return if`, ending in the value it returns: a lone
 * value is tried first, then a block whose last statement is the value.
 */
std::vector<Stmt> Parser::parseReturnBranchBody(const std::string& what) {
    skipNewlines();
    if (match(TokenType::THEN)) {
        skipNewlines();
        Stmt value = parseReturnedValue(peek());
        consumeNewline();
        return {value};
    }
    if (what == "else" && !check(TokenType::L_BRACE)) {
        Stmt value = parseReturnedValue(peek());
        consumeNewline();
        return {value};
    }
    consume(TokenType::L_BRACE, "Expected '{' after " + what);
    skipNewlines();
    Token at = peek();
    // `.Variant` fails the function; it is not a value.
    if (check(TokenType::DOT) && peekNextIs(TokenType::IDENTIFIER)) {
        Stmt thrown = parseReturnedValue(at);
        skipNewlines();
        consume(TokenType::R_BRACE, "Expected '}' after " + what + " branch");
        return {thrown};
    }
    if (std::optional<Stmt> value = parseBranchValueOrNull(at)) {
        consume(TokenType::R_BRACE, "Expected '}' after " + what + " branch");
        return {*value};
    }
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}' after " + what + " branch");
    if (body.empty()) fail("an 'if' branch in return position must produce a value (line " + str(at.line) + ")");
    Stmt lastStmt = body.back();
    StmtExprStmt* tail = as<StmtExprStmt>(lastStmt);
    if (tail == nullptr) {
        // A branch that already returns, throws or panics is complete.
        if (branchBodyYields(lastStmt)) return body;
        fail("an 'if' branch in return position must end in a value at line " + str(lastStmt.line()) +
             " - assign the trailing '" + branchTailName(lastStmt) +
             "' to a `fin` and end with that, or return from inside it");
    }
    std::vector<Stmt> lifted(body.begin(), body.end() - 1);
    lifted.push_back(make(arena, StmtReturn{.value = tail->expr, .line = tail->line, .column = tail->column}));
    return lifted;
}

/**
 * The branch's value, if the whole branch is one expression; none - leaving
 * the position exactly as it found it - otherwise.
 */
std::optional<Stmt> Parser::parseBranchValueOrNull(const Token& at) {
    int32_t savedCurrent = current;
    bool savedGreater = pendingGreater;
    bool savedTrailing = allowTrailingLambda;
    auto rewind = [&]() -> std::optional<Stmt> {
        current = savedCurrent;
        pendingGreater = savedGreater;
        allowTrailingLambda = savedTrailing;
        return std::nullopt;
    };
    Expr value;
    try {
        value = parseExpr();
    } catch (const CompileFailure& failure) {
        // Kotlin catches IllegalStateException only.
        if (failure.kind != FailureKind::IllegalState) throw;
        return rewind();
    }
    skipNewlines();
    if (check(TokenType::R_BRACE)) return Stmt(make(arena, StmtReturn{.value = value, .line = at.line, .column = at.column}));
    return rewind();
}

/**
 * `return when scrutinee { pattern -> value … else -> value }`: the statement
 * form with every branch body a `return`.
 */
Stmt Parser::parseReturnWhen(const Token& start) {
    Expr scrutinee = parseWhenHead().scrutinee;
    WhenParts<std::vector<Stmt>> parts =
        parseWhenBranches<std::vector<Stmt>>(scrutinee, [&] { return parseWhenReturnValue(); });
    consumeNewline();
    return make(arena, StmtWhen{scrutinee, whenBranches(parts, start), parts.elseBody, start.line, start.column});
}

/** One `return when` branch body: `-> expr`, or `-> { … }` ending in the value. */
std::vector<Stmt> Parser::parseWhenReturnValue() {
    Token at = peek();
    if (!check(TokenType::L_BRACE)) return {parseReturnedValue(at)};
    advance();  // '{'
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}' after when branch body");
    if (body.empty()) fail("a `when` branch must produce a value (line " + str(at.line) + ")");
    Stmt lastStmt = body.back();
    StmtExprStmt* tail = as<StmtExprStmt>(lastStmt);
    if (tail == nullptr) {
        // A branch that already returns, throws or panics is complete.
        if (branchBodyYields(lastStmt)) return body;
        fail("a `when` branch block must end in a value at line " + str(lastStmt.line()) + " - assign the trailing '" +
             branchTailName(lastStmt) + "' to a `fin` and end with that, or return from inside it");
    }
    std::vector<Stmt> lifted(body.begin(), body.end() - 1);
    lifted.push_back(make(arena, StmtReturn{.value = tail->expr, .line = tail->line, .column = tail->column}));
    return lifted;
}

/** True when [stmt] already leaves the function, so a branch needs no value. */
bool Parser::branchBodyYields(const Stmt& stmt) {
    return is<StmtReturn>(stmt) || is<StmtThrow>(stmt) || is<StmtPanic>(stmt) || is<StmtYield>(stmt);
}

/** A human name for the statement a branch block wrongly ended on. */
std::string Parser::branchTailName(const Stmt& stmt) {
    if (is<StmtIf>(stmt)) return "if";
    if (is<StmtWhen>(stmt)) return "when";
    if (is<StmtWhile>(stmt)) return "while";
    if (is<StmtFor>(stmt)) return "for";
    return "statement";
}

/** One `when`-branch value: `-> expr` or `-> { expr }`. */
Expr Parser::parseWhenBranchValue() {
    if (match(TokenType::L_BRACE)) {
        skipNewlines();
        Expr value = parseSealableValue();
        skipNewlines();
        consume(TokenType::R_BRACE, "Expected '}' after when branch value");
        return value;
    }
    return parseSealableValue();
}

/** A branch value, optionally sealed: `seal "!! "`. */
Expr Parser::parseSealableValue() {
    std::optional<Token> next = peekNext();
    if (check(TokenType::SEAL) && startsExpression(next ? &*next : nullptr)) {
        Token start = advance();
        Expr value = parseExpr();
        return make(arena, ExprSeal{value, start.line, start.column, u16len(start.lexeme)});
    }
    return parseExpr();
}

/** Whether [token] can begin an expression. */
bool Parser::startsExpression(const Token* token) {
    if (token == nullptr) return false;
    switch (token->type) {
        case TokenType::DOT: case TokenType::IDENTIFIER: case TokenType::INT_LITERAL: case TokenType::DOUBLE_LITERAL:
        case TokenType::STRING_LITERAL: case TokenType::INTERPOLATED_STRING: case TokenType::CHAR_LITERAL:
        case TokenType::TRUE_: case TokenType::FALSE_: case TokenType::NULL_: case TokenType::L_PAREN:
        case TokenType::L_BRACKET: case TokenType::L_BRACE: case TokenType::IF: case TokenType::WHEN:
        case TokenType::MINUS: case TokenType::BANG:
            return true;
        default:
            // An infix operator means the name was the left operand, not a keyword.
            return false;
    }
}

/**
 * `when` in expression position, desugared to a chain of if-expressions. A
 * pattern becomes `==` against the scrutinee; `is T` stays a type check.
 */
Expr Parser::parseWhenExpr() {
    Token start = peek();
    if (!allowWhenExpressions) fail("line " + str(start.line) + ": use a GPU when statement or 'return when'");
    WhenHead head = parseWhenHead();
    Expr scrutinee = head.scrutinee;
    ExprBoolLiteral* boolScrutinee = as<ExprBoolLiteral>(scrutinee);
    bool guardForm = head.guard || (boolScrutinee && boolScrutinee->value);
    WhenParts<Expr> parts = parseWhenBranches<Expr>(scrutinee, [&] { return parseWhenBranchValue(); });
    const std::vector<Expr>& values = parts.bodies;
    if (values.empty()) fail("a `when` expression needs at least one branch (line " + str(start.line) + ")");
    // Arbitrary Boolean guards do not establish exhaustiveness.
    if (guardForm && !parts.elseBody) {
        fail("a guard `when` expression requires an 'else' branch at line " + str(start.line));
    }
    // Each branch's patterns become one condition on the scrutinee.
    std::vector<Expr> conditions;
    for (const std::vector<Expr>& group : parts.patterns) {
        Expr condition = whenExprCondition(scrutinee, group[0], start, guardForm);
        for (size_t p = 1; p < group.size(); p++) {
            Expr next = whenExprCondition(scrutinee, group[p], start, guardForm);
            condition = make(arena, ExprBinary{condition, TokenType::OR_OR, next, start.line, start.column});
        }
        conditions.push_back(condition);
    }
    // No `else`: the last branch is the fallback.
    Expr result = parts.elseBody ? *parts.elseBody : values.back();
    int64_t i = parts.elseBody ? (int64_t)conditions.size() - 1 : (int64_t)conditions.size() - 2;
    while (i >= 0) {
        result = make(arena, ExprIfExpr{conditions[(size_t)i], values[(size_t)i], result, start.line, start.column});
        i--;
    }
    return result;
}

/**
 * One already-parsed `when` pattern, as a condition on the scrutinee. A
 * pattern that destructures a slot payload is refused.
 */
Expr Parser::whenExprCondition(const Expr& scrutinee, const Expr& pattern, const Token& start, bool guardForm) {
    if (guardForm) return pattern;
    if (is<ExprIsCheck>(pattern)) return pattern;
    bool destructures = false;
    if (ExprMethodCall* call = as<ExprMethodCall>(pattern)) {
        destructures = !call->args.empty();
    } else if (ExprInferredMember* member = as<ExprInferredMember>(pattern)) {
        destructures = !member->name.empty() && member->ctorArgs && !member->ctorArgs->empty();
    }
    if (destructures) {
        fail("a `when` expression cannot destructure a slot payload at line " + str(start.line) +
             " - a binding has nowhere to live in an expression. Use `return when …` or a statement `when` instead.");
    }
    return make(arena, ExprBinary{scrutinee, TokenType::EQUAL_EQUAL, pattern, start.line, start.column});
}

Stmt Parser::parseExprStmt() {
    Token start = peek();
    Expr expr = parseExpr();
    // `arr[i]++` and `self.count--` as statements keep the assignment desugaring.
    if (ExprIncDec* incDec = as<ExprIncDec>(expr); incDec && !is<ExprIdentifier>(incDec->target)) {
        consumeNewline();
        TokenType op = incDec->op == TokenType::PLUS_PLUS ? TokenType::PLUS : TokenType::MINUS;
        Expr one = make(arena, ExprIntLiteral{.value = 1, .line = start.line, .column = start.column});
        Expr rhs = make(arena, ExprBinary{incDec->target, op, one, start.line, start.column});
        return buildAssignment(incDec->target, rhs, start.line, start.column);
    }
    if (match(TokenType::EXCHANGE)) {
        Expr right = parseExpr();
        auto location = [](const Expr& value) {
            return is<ExprIdentifier>(value) || is<ExprMember>(value) || is<ExprIndex>(value) || is<ExprDeref>(value);
        };
        if (!location(expr) || !location(right)) {
            fail("exchange operands must be assignable storage locations at line " + str(start.line));
        }
        consumeNewline();
        return make(arena, StmtExchange{expr, right, start.line, start.column});
    }
    Token opTok = peek();
    switch (opTok.type) {
        case TokenType::EQUAL: {
            advance();
            Expr value = parseExpr();
            consumeNewline();
            return buildAssignment(expr, value, start.line, start.column);
        }
        case TokenType::PLUS_EQUAL: case TokenType::MINUS_EQUAL: case TokenType::STAR_EQUAL:
        case TokenType::SLASH_EQUAL: case TokenType::PERCENT_EQUAL: case TokenType::AMP_EQUAL:
        case TokenType::PIPE_EQUAL: case TokenType::CARET_EQUAL: case TokenType::SHIFT_LEFT_EQUAL:
        case TokenType::SHIFT_RIGHT_EQUAL: {
            advance();
            TokenType op;
            switch (opTok.type) {
                case TokenType::PLUS_EQUAL: op = TokenType::PLUS; break;
                case TokenType::MINUS_EQUAL: op = TokenType::MINUS; break;
                case TokenType::STAR_EQUAL: op = TokenType::STAR; break;
                case TokenType::SLASH_EQUAL: op = TokenType::SLASH; break;
                case TokenType::PERCENT_EQUAL: op = TokenType::PERCENT; break;
                case TokenType::AMP_EQUAL: op = TokenType::AMP; break;
                case TokenType::PIPE_EQUAL: op = TokenType::PIPE; break;
                case TokenType::CARET_EQUAL: op = TokenType::CARET; break;
                case TokenType::SHIFT_LEFT_EQUAL: op = TokenType::SHIFT_LEFT; break;
                case TokenType::SHIFT_RIGHT_EQUAL: op = TokenType::SHIFT_RIGHT; break;
                default: fail("unreachable compound assignment");
            }
            Expr value = parseExpr();
            consumeNewline();
            // Desugar `target op= value` into `target = target op value`.
            Expr rhs = make(arena, ExprBinary{expr, op, value, start.line, start.column, u16len(start.lexeme)});
            return buildAssignment(expr, rhs, start.line, start.column, op);
        }
        // Null-conditional coalescing assignment: `target ?= value` → `target = target ?? value`.
        case TokenType::QMARK_EQUAL: {
            advance();
            Expr value = parseExpr();
            consumeNewline();
            Expr rhs = make(arena, ExprNullCoalesce{expr, value, start.line, start.column, u16len(start.lexeme)});
            return buildAssignment(expr, rhs, start.line, start.column);
        }
        // Null-conditional compound assignment: `target ?+= value`.
        case TokenType::QMARK_PLUS_EQUAL: case TokenType::QMARK_MINUS_EQUAL: case TokenType::QMARK_STAR_EQUAL:
        case TokenType::QMARK_SLASH_EQUAL: case TokenType::QMARK_PERCENT_EQUAL: {
            advance();
            TokenType op;
            switch (opTok.type) {
                case TokenType::QMARK_PLUS_EQUAL: op = TokenType::PLUS; break;
                case TokenType::QMARK_MINUS_EQUAL: op = TokenType::MINUS; break;
                case TokenType::QMARK_STAR_EQUAL: op = TokenType::STAR; break;
                case TokenType::QMARK_SLASH_EQUAL: op = TokenType::SLASH; break;
                case TokenType::QMARK_PERCENT_EQUAL: op = TokenType::PERCENT; break;
                default: fail("unreachable nullable compound assignment");
            }
            Expr value = parseExpr();
            consumeNewline();
            return nullConditionalCompound(expr, op, value, start);
        }
        // `target++` / `target--` - desugars to `target = target ± 1`.
        case TokenType::PLUS_PLUS:
        case TokenType::MINUS_MINUS: {
            advance();
            consumeNewline();
            TokenType op = opTok.type == TokenType::PLUS_PLUS ? TokenType::PLUS : TokenType::MINUS;
            Expr one = make(arena, ExprIntLiteral{.value = 1, .line = start.line, .column = start.column});
            Expr rhs = make(arena, ExprBinary{expr, op, one, start.line, start.column});
            return buildAssignment(expr, rhs, start.line, start.column);
        }
        case TokenType::QMARK_PLUS_PLUS:
            advance();
            consumeNewline();
            return nullConditionalIncDec(expr, TokenType::PLUS, start);
        case TokenType::QMARK_MINUS_MINUS:
            advance();
            consumeNewline();
            return nullConditionalIncDec(expr, TokenType::MINUS, start);
        default:
            consumeNewline();
            return make(arena, StmtExprStmt{expr, start.line, start.column});
    }
}

/**
 * `target ?<op>= value` - the compound assignment only when [target] is
 * non-null: `if (target != null) { target = target <op> value }`.
 */
Stmt Parser::nullConditionalCompound(const Expr& target, TokenType op, const Expr& value, const Token& start) {
    Expr rhs = make(arena, ExprBinary{target, op, value, start.line, start.column, u16len(start.lexeme)});
    Stmt assign = buildAssignment(target, rhs, start.line, start.column);
    Expr cond = make(arena, ExprBinary{target, TokenType::BANG_EQUAL, nullLiteral(), start.line, start.column,
                                       u16len(start.lexeme)});
    return make(arena, StmtIf{cond, {assign}, std::nullopt, start.line, start.column});
}

/** `target ?++` / `target ?--` - increment/decrement only when [target] is non-null. */
Stmt Parser::nullConditionalIncDec(const Expr& target, TokenType op, const Token& start) {
    Expr one = make(arena, ExprIntLiteral{.value = 1, .line = start.line, .column = start.column,
                                          .length = u16len(start.lexeme)});
    return nullConditionalCompound(target, op, one, start);
}

/**
 * The arguments of a call, up to but not including the closing `)`: `inline
 * for` arguments, spreads, `name: value` and `:name`, separated by a comma or
 * a line break.
 */
std::vector<Expr> Parser::parseCallArgumentList() {
    std::vector<Expr> args;
    skipNewlines();
    while (!check(TokenType::R_PAREN) && !isAtEnd()) {
        if (check(TokenType::INLINE) && peekNextIs(TokenType::FOR)) {
            args.push_back(parseInlineForArgument());
            match(TokenType::COMMA);
            skipNewlines();
            continue;
        }
        if (match(TokenType::ELLIPSIS)) {
            Expr spread = parseExpr();
            args.push_back(make(arena, ExprSpread{spread, spread.line(), spread.column(), spread.length()}));
        } else if (check(TokenType::COLON)) {
            Token colon = advance();
            Token name = consume(TokenType::IDENTIFIER, "Expected a binding name after ':' in an argument list");
            if (name.line != colon.line || name.column != colon.column + 1) {
                fail("Write a shorthand named argument as ':" + name.lexeme + "' at line " + str(colon.line));
            }
            ExprIdentifier* value = make(arena, ExprIdentifier{name.lexeme, name.line, name.column, u16len(name.lexeme)});
            args.push_back(make(arena, ExprNamedArg{value->name, value, value->line, value->column, value->length}));
        } else {
            Expr firstArg = parseExpr();
            ExprIdentifier* id = as<ExprIdentifier>(firstArg);
            if (id && check(TokenType::COLON) && !isNextLineNamedArgument(*id)) {
                advance();
                Expr value = parseExpr();
                args.push_back(make(arena, ExprNamedArg{id->name, value, id->line, id->column, id->length}));
            } else {
                args.push_back(firstArg);
            }
        }
        match(TokenType::COMMA);
        skipNewlines();
    }
    return args;
}

/** With comma-free arguments, a new line starting with `:name` starts a new argument. */
bool Parser::isNextLineNamedArgument(const ExprIdentifier& previous) {
    Token colon = peek();
    std::optional<Token> name = peekNext();
    if (!name) return false;
    return colon.line > previous.line && name->type == TokenType::IDENTIFIER && name->line == colon.line &&
           name->column == colon.column + 1;
}

/** `inline for x in xs { expr }` in argument position - one argument per iteration. */
Expr Parser::parseInlineForArgument() {
    Token forStart = advance();  // 'inline'
    advance();                   // 'for'
    std::string loopName = consumeIdentifierLike("Expected loop variable in argument 'inline for'");
    consume(TokenType::IN, "Expected 'in' after argument 'inline for' variable");
    // The `{` opens the loop body, not a trailing lambda on the iterable.
    Expr iterable = withoutTrailingLambda([&] { return parseExpr(); });
    // One expression per iteration needs no braces, as `if c then x` needs none.
    if (match(TokenType::THEN)) {
        skipNewlines();
        Expr bodyExpr = parseExpr();
        return make(arena, ExprInlineForArgs{loopName, iterable, bodyExpr, forStart.line, forStart.column});
    }
    consume(TokenType::L_BRACE, "Expected '{' or 'then' to open argument 'inline for' body");
    skipNewlines();
    Expr bodyExpr = parseExpr();
    skipNewlines();
    consume(TokenType::R_BRACE, "Expected '}' after argument 'inline for' body");
    return make(arena, ExprInlineForArgs{loopName, iterable, bodyExpr, forStart.line, forStart.column});
}

/** True when [expr] is a plain dotted name (`std.container.array`). */
bool Parser::isModulePath(const Expr& expr) {
    if (is<ExprIdentifier>(expr)) return true;
    if (ExprMember* member = as<ExprMember>(expr)) return !member->nameExpr && isModulePath(member->target);
    return false;
}

/**
 * Builds an assignment statement from a parsed lvalue expression: variables,
 * index targets, member targets and dereferences.
 */
Stmt Parser::buildAssignment(const Expr& target, const Expr& value, int32_t line, int32_t column,
                             std::optional<TokenType> compoundOp /*= std::nullopt*/) {
    switch (target.kind()) {
        case ExprKind::Identifier: {
            StmtAssignment a;
            a.name = cast<ExprIdentifier>(target).name;
            a.value = value;
            a.line = line;
            a.column = column;
            a.compoundOp = compoundOp;
            return make(arena, a);
        }
        case ExprKind::Index: {
            const ExprIndex& index = cast<ExprIndex>(target);
            StmtIndexAssign a;
            a.target = index.target;
            a.index = index.index;
            a.value = value;
            a.line = line;
            a.column = column;
            a.compoundOp = compoundOp;
            return make(arena, a);
        }
        case ExprKind::Member: {
            const ExprMember& member = cast<ExprMember>(target);
            StmtMemberAssign a;
            a.target = member.target;
            a.name = member.name;
            a.value = value;
            a.line = line;
            a.column = column;
            a.compoundOp = compoundOp;
            a.nameExpr = member.nameExpr;
            return make(arena, a);
        }
        case ExprKind::Deref:
            return make(arena, StmtDerefAssign{cast<ExprDeref>(target).target, value, line, column});
        default:
            fail("Invalid assignment target at line " + str(line));
    }
}

}  // namespace azora
