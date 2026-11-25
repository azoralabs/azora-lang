// Parser.kt lines 7727-8560: statement dispatch, loops (with else and
// destructuring), jumps, throw/panic, unsafe/purge/defer/rescue, reactive
// declarations and effects, `using`, and `threadlocal`.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"
#include "frontend/lexer.h"

namespace azora {

/** Parse one statement together with any tuple or expression prelude. */
std::vector<Stmt> Parser::parseStmts() {
    Stmt firstStmt = parseStmt();
    std::vector<Stmt> before = pendingPrelude;
    pendingPrelude.clear();
    std::vector<Stmt> rest = pendingStmts;
    pendingStmts.clear();
    std::vector<Stmt> out = before;
    out.push_back(firstStmt);
    out.insert(out.end(), rest.begin(), rest.end());
    return out;
}

/** One statement, where the grammar has room for exactly one. */
Stmt Parser::parseSingleStmt(const std::string& what) {
    (void)what;
    Stmt stmt = parseStmt();
    if (!pendingStmts.empty()) {
        std::vector<Stmt> rest = pendingStmts;
        pendingStmts.clear();
        std::vector<Stmt> before = pendingPrelude;
        pendingPrelude.clear();
        std::vector<Stmt> body = before;
        body.push_back(stmt);
        body.insert(body.end(), rest.begin(), rest.end());
        return make(arena, StmtScope{.body = body, .line = stmt.line(), .column = stmt.column()});
    }
    if (pendingPrelude.empty()) return stmt;
    // Room for one statement is room for one *scope*.
    std::vector<Stmt> body = pendingPrelude;
    pendingPrelude.clear();
    body.push_back(stmt);
    return make(arena, StmtScope{.body = body, .line = stmt.line(), .column = stmt.column()});
}

/** A body written with `then` contains exactly one statement. */
std::vector<Stmt> Parser::parseThenBody(const std::string& what) {
    consume(TokenType::THEN, "Expected 'then' after " + what + " header");
    skipNewlines();
    return {parseSingleStmt(what + " then body")};
}

/** A control-flow body: either the traditional block or one `then` statement. */
std::vector<Stmt> Parser::parseThenOrBracedBody(const std::string& what) {
    skipNewlines();
    if (check(TokenType::THEN)) return parseThenBody(what);
    consume(TokenType::L_BRACE, "Expected '{' or 'then' after " + what + " header");
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}' after " + what + " body");
    return body;
}

/** The body after `else`, accepting both `else { … }` and `else stmt`. */
std::vector<Stmt> Parser::parseElseBody(const std::string& what) {
    if (check(TokenType::IF)) return {parseIf()};
    if (check(TokenType::L_BRACE)) return parseThenOrBracedBody("if else");
    if (check(TokenType::THEN)) return parseThenOrBracedBody("if else");
    skipNewlines();
    return {parseSingleStmt(what + " else body")};
}

Stmt Parser::parseStmt() {
    // A fragment macro stands for source, and a statement is one of the places
    // it can stand for. Expanded before anything else reads the stream.
    spliceFragmentMacro();
    // `var(...)` is the variant constructor (an expression statement).
    if (check(TokenType::VAR) && peekNextIs(TokenType::L_PAREN)) return parseExprStmt();
    if (check(TokenType::VAR) || check(TokenType::VAL)) return parseVarDecl();
    if (check(TokenType::FIN)) return parseFinDecl();
    if (check(TokenType::LET) && peekNextIs(TokenType::L_PAREN)) {
        Token keywordToken = advance();
        return parseTupleBinding(keywordToken, "let");
    }
    if (check(TokenType::LET)) return parseLetDecl();
    if (check(TokenType::LAZY)) return parseLazyDecl();
    if (check(TokenType::RETURN)) return parseReturn();
    if (check(TokenType::ASSERT)) return parseAssertStmt();
    if (check(TokenType::TRACE)) return parseTraceStmt();
    if (check(TokenType::INLINE)) return parseInline();
    if (check(TokenType::DEEPINLINE)) return parseDeepInlineStmt();
    if (check(TokenType::NOINLINE)) return parseNoInline();
    if (check(TokenType::SCOPE)) return parseScope();
    if (check(TokenType::IF)) return parseIf();
    if (check(TokenType::IDENTIFIER) && peekNextIs(TokenType::COLON)) return parseLabeledStmt();
    if (check(TokenType::AT) && !isMacroInvokeAhead()) {
        fail("Loop labels use 'label: loop' syntax; replace '@label loop' with 'label: loop' at line " + str(peek().line));
    }
    if (check(TokenType::WHILE)) return parseWhile();
    if (check(TokenType::FOR)) return parseFor();
    if (check(TokenType::LOOP)) return parseLoop();
    if (check(TokenType::BREAK)) return parseBreak();
    if (check(TokenType::CONTINUE)) return parseContinue();
    if (check(TokenType::WHEN)) return parseWhen();
    if (check(TokenType::THROW)) return parseThrow();
    if (check(TokenType::PANIC)) return parsePanicStmt();
    if (check(TokenType::ERROR) && peekNextIs(TokenType::DEFER)) return parseFailDefer();
    if (check(TokenType::ERROR)) return parseFailThrow();
    if (check(TokenType::UNSAFE)) return parseUnsafe();
    if (check(TokenType::PURGE)) return parsePurge();
    if (check(TokenType::TRY) && (peekNextIs(TokenType::L_BRACE) || peekNextIs(TokenType::THEN))) return parseTry();
    if (check(TokenType::DEFER)) return parseDefer();
    if (check(TokenType::RESCUE)) return parseRescue();
    if (check(TokenType::REMEMBER)) return parseReactiveDecl(ReactiveKind::REMEMBER);
    if (check(TokenType::RETAIN)) return parseReactiveDecl(ReactiveKind::RETAIN);
    if (check(TokenType::PRESERVE)) return parseReactiveDecl(ReactiveKind::PRESERVE);
    if (check(TokenType::EFFECT)) return parseEffect();
    // A test opens with what it needs: the import binds only where it is written.
    if (check(TokenType::IMPORT)) {
        Token at = peek();
        TopLevelUseImport* use = &cast<TopLevelUseImport>(parseUse());
        return make(arena, StmtImport{use, at.line, at.column, u16len(at.lexeme)});
    }
    if (check(TokenType::USING)) return parseUsingContext();
    if (check(TokenType::WITH)) {
        fail("Context receivers use 'using' at line " + str(peek().line) +
             "; write 'using value { ... }' or 'using (a, b) { ... }'");
    }
    if (check(TokenType::L_PAREN) && isTupleAssignmentAhead()) return parseTupleAssignment();
    return parseExprStmt();
}

/** `label: while/for/loop …` - a loop targeted by `break:label`/`continue:label`. */
Stmt Parser::parseLabeledStmt() {
    std::string label = consume(TokenType::IDENTIFIER, "Expected loop label").lexeme;
    consume(TokenType::COLON, "Expected ':' after loop label '" + label + "'");
    if (check(TokenType::WHILE)) return parseWhile(label);
    if (check(TokenType::FOR)) return parseFor(label);
    if (check(TokenType::LOOP)) return parseLoop(label);
    fail("Expected 'for', 'while', or 'loop' after label '" + label + ":' at line " + str(peek().line));
}

/** `:label` suffix on `break`/`continue`; returns the label or null. */
std::optional<std::string> Parser::parseOptionalLabel(const std::string& jump) {
    if (check(TokenType::AT)) {
        fail("Labeled jumps use ':label'; replace '" + jump + " @label' with '" + jump + ":label' at line " +
             str(peek().line));
    }
    if (match(TokenType::COLON)) return consume(TokenType::IDENTIFIER, "Expected loop label after ':'").lexeme;
    return std::nullopt;
}

Stmt Parser::parseWhile(const std::optional<std::string>& label /*= std::nullopt*/) {
    Token start = peek();
    consume(TokenType::WHILE, "Expected 'while'");
    // As in parseIf: `while f() { … }` - the `{` is the loop body.
    bool savedTrailing = allowTrailingLambda;
    allowTrailingLambda = false;
    Expr condition = parseExpr();
    allowTrailingLambda = savedTrailing;
    std::vector<Stmt> body = parseThenOrBracedBody("while condition");
    skipNewlines();
    std::optional<std::vector<Stmt>> elseBranch = parseLoopElse();
    consumeNewline();
    StmtWhile loop;
    loop.condition = condition;
    loop.body = body;
    loop.line = start.line;
    loop.column = start.column;
    loop.label = label;
    Stmt loopStmt = make(arena, loop);
    if (elseBranch) return withLoopElse(loopStmt, *elseBranch, start.line, start.column);
    return loopStmt;
}

/** Parses an optional `else { body }` trailing a `while`/`for`/`loop`. */
std::optional<std::vector<Stmt>> Parser::parseLoopElse() {
    if (!match(TokenType::ELSE)) return std::nullopt;
    return parseElseBody("loop");
}

/**
 * Lowers `loop { body } else { elseBody }` to a scope with a hidden flag that
 * a real `break` clears, and an `if` over the flag after the loop.
 */
Stmt Parser::withLoopElse(const Stmt& loop, const std::vector<Stmt>& elseBody, int32_t line, int32_t column) {
    std::string flag = "__loop_else_" + str(loopElseCounter++);
    StmtVarDecl flagDecl;
    flagDecl.name = flag;
    flagDecl.initializer = make(arena, ExprBoolLiteral{true, line, column});
    flagDecl.line = line;
    flagDecl.column = column;
    Stmt rewritten = loop;
    if (StmtWhile* w = as<StmtWhile>(loop)) {
        StmtWhile copy = *w;
        copy.body = rewriteBreaksForElse(w->body, flag);
        rewritten = make(arena, copy);
    } else if (StmtFor* f = as<StmtFor>(loop)) {
        StmtFor copy = *f;
        copy.body = rewriteBreaksForElse(f->body, flag);
        rewritten = make(arena, copy);
    } else if (StmtLoop* l = as<StmtLoop>(loop)) {
        StmtLoop copy = *l;
        copy.body = rewriteBreaksForElse(l->body, flag);
        rewritten = make(arena, copy);
    }
    Stmt elseCheck = make(arena, StmtIf{make(arena, ExprIdentifier{flag, line, column}), elseBody, std::nullopt, line, column});
    return make(arena, StmtScope{.body = {make(arena, flagDecl), rewritten, elseCheck}, .line = line, .column = column});
}

/**
 * Rewrites `break` statements that belong to [flag]'s loop into
 * `scope { flag = false; break }`. Does not descend into nested loops.
 */
std::vector<Stmt> Parser::rewriteBreaksForElse(const std::vector<Stmt>& stmts, const std::string& flag) {
    std::vector<Stmt> out;
    for (const Stmt& stmt : stmts) out.push_back(rewriteBreakForElse(stmt, flag));
    return out;
}

Stmt Parser::rewriteBreakForElse(const Stmt& stmt, const std::string& flag) {
    switch (stmt.kind()) {
        case StmtKind::Break: {
            // Only an unlabeled `break` exits THIS loop and so trips the else flag.
            const StmtBreak& b = cast<StmtBreak>(stmt);
            if (b.label) return stmt;
            StmtAssignment assignment;
            assignment.name = flag;
            assignment.value = make(arena, ExprBoolLiteral{false, b.line, b.column, b.length});
            assignment.line = b.line;
            assignment.column = b.column;
            StmtBreak plain;
            plain.line = b.line;
            plain.column = b.column;
            plain.length = b.length;
            plain.value = b.value;
            return make(arena, StmtScope{.body = {make(arena, assignment), make(arena, plain)}, .line = b.line,
                                         .column = b.column});
        }
        // Nested loops own their own breaks - stop descending.
        case StmtKind::While:
        case StmtKind::For:
        case StmtKind::Loop:
            return stmt;
        case StmtKind::If: {
            const StmtIf& s = cast<StmtIf>(stmt);
            std::optional<std::vector<Stmt>> elseBranch;
            if (s.elseBranch) elseBranch = rewriteBreaksForElse(*s.elseBranch, flag);
            return make(arena, StmtIf{s.condition, rewriteBreaksForElse(s.thenBranch, flag), elseBranch, s.line, s.column,
                                      s.length});
        }
        case StmtKind::Scope: {
            const StmtScope& s = cast<StmtScope>(stmt);
            return make(arena, StmtScope{.body = rewriteBreaksForElse(s.body, flag), .line = s.line, .column = s.column,
                                         .length = s.length});
        }
        case StmtKind::Try: {
            const StmtTry& s = cast<StmtTry>(stmt);
            std::vector<Stmt> body = rewriteBreaksForElse(s.body, flag);
            std::optional<std::vector<Stmt>> catchBody;
            if (s.catchBody) catchBody = rewriteBreaksForElse(*s.catchBody, flag);
            return make(arena, StmtTry{body, s.catchName, catchBody, s.line, s.column, s.length});
        }
        case StmtKind::When: {
            StmtWhen copy = cast<StmtWhen>(stmt);
            for (WhenBranch& branch : copy.branches) branch.body = rewriteBreaksForElse(branch.body, flag);
            if (copy.elseBranch) copy.elseBranch = rewriteBreaksForElse(*copy.elseBranch, flag);
            return make(arena, copy);
        }
        default:
            return stmt;
    }
}

/**
 * Retargets unlabeled `break`s in [stmts] at [label], stopping at nested loops.
 */
std::vector<Stmt> Parser::retargetBreaks(const std::vector<Stmt>& stmts, const std::string& label) {
    std::vector<Stmt> out;
    for (const Stmt& stmt : stmts) out.push_back(retargetBreak(stmt, label));
    return out;
}

Stmt Parser::retargetBreak(const Stmt& stmt, const std::string& label) {
    switch (stmt.kind()) {
        case StmtKind::Break: {
            const StmtBreak& b = cast<StmtBreak>(stmt);
            if (b.label) return stmt;
            return make(arena, StmtBreak{label, b.line, b.column, b.length, b.value});
        }
        case StmtKind::While:
        case StmtKind::For:
        case StmtKind::Loop:
            return stmt;
        case StmtKind::If: {
            const StmtIf& s = cast<StmtIf>(stmt);
            std::optional<std::vector<Stmt>> elseBranch;
            if (s.elseBranch) elseBranch = retargetBreaks(*s.elseBranch, label);
            return make(arena, StmtIf{s.condition, retargetBreaks(s.thenBranch, label), elseBranch, s.line, s.column,
                                      s.length});
        }
        case StmtKind::Scope: {
            const StmtScope& s = cast<StmtScope>(stmt);
            return make(arena, StmtScope{.body = retargetBreaks(s.body, label), .line = s.line, .column = s.column,
                                         .length = s.length});
        }
        case StmtKind::Try: {
            const StmtTry& s = cast<StmtTry>(stmt);
            std::vector<Stmt> body = retargetBreaks(s.body, label);
            std::optional<std::vector<Stmt>> catchBody;
            if (s.catchBody) catchBody = retargetBreaks(*s.catchBody, label);
            return make(arena, StmtTry{body, s.catchName, catchBody, s.line, s.column, s.length});
        }
        case StmtKind::When: {
            StmtWhen copy = cast<StmtWhen>(stmt);
            for (WhenBranch& branch : copy.branches) branch.body = retargetBreaks(branch.body, label);
            if (copy.elseBranch) copy.elseBranch = retargetBreaks(*copy.elseBranch, label);
            return make(arena, copy);
        }
        default:
            return stmt;
    }
}

Stmt Parser::parseFor(const std::optional<std::string>& label /*= std::nullopt*/, bool allowElse /*= true*/) {
    Token start = peek();
    consume(TokenType::FOR, "Expected 'for'");
    // `for (a, b) in rows` - the row taken apart as it is bound.
    if (check(TokenType::L_BRACKET)) {
        fail("a loop takes its row apart with parentheses, as a binding does: write 'for (a, b) in …' at line " +
             str(peek().line));
    }
    std::optional<LoopPattern> destructured;
    if (check(TokenType::L_PAREN)) destructured = parseLoopDestructuring();
    std::string name;
    if (!destructured) name = consume(TokenType::IDENTIFIER, "Expected loop variable name").lexeme;
    else name = "__row" + str(start.line) + "_" + str(start.column);
    // `for i: Int in 0..mid` - the row's type, said rather than inferred.
    TypeRef declaredType;
    if (!destructured && match(TokenType::COLON)) declaredType = parseTypeName();
    consume(TokenType::IN, "Expected 'in' after loop variable");
    // The step is part of the header too: the `{` closes the header.
    Expr iterable;
    Expr step;
    withoutTrailingLambdaRun([&] {
        iterable = parseExpr();
        // Optional step, trailing the iterable it applies to: `for x in 0..<6 by 2`.
        if (match(TokenType::BY)) step = parseExpr();
    });
    // `with index` exposes the zero-based ordinal of the iteration.
    std::optional<std::string> indexName;
    if (match(TokenType::WITH)) indexName = consumeIdentifierLike("Expected index binding after 'with'");
    if (indexName == name) {
        fail("the loop value and its 'with' index binding must have different names at line " + str(start.line));
    }
    std::vector<Stmt> body = parseThenOrBracedBody("for iterable");
    skipNewlines();
    std::optional<std::vector<Stmt>> elseBranch;
    if (allowElse) elseBranch = parseLoopElse();
    if (allowElse) consumeNewline();
    std::vector<Stmt> fullBody;
    if (destructured) fullBody = destructuredPrologue(*destructured, name, start);
    fullBody.insert(fullBody.end(), body.begin(), body.end());
    StmtFor loop;
    loop.name = name;
    loop.iterable = iterable;
    loop.body = fullBody;
    loop.line = start.line;
    loop.column = start.column;
    loop.step = step;
    loop.label = label;
    loop.declaredType = declaredType;
    loop.indexName = indexName;
    Stmt loopStmt = make(arena, loop);
    if (elseBranch) return withLoopElse(loopStmt, *elseBranch, start.line, start.column);
    return loopStmt;
}

/** `(a, b)` or `((x, y), weight)` - at least two positions, as a tuple has. */
Parser::LoopPattern Parser::parseLoopDestructuring() {
    Token open = consume(TokenType::L_PAREN, "Expected '(' to open the loop's bindings");
    LoopPattern group;
    skipNewlines();
    do {
        skipNewlines();
        if (check(TokenType::L_PAREN)) {
            group.parts.push_back(parseLoopDestructuring());
        } else {
            LoopPattern nameOnly;
            nameOnly.name = consumeIdentifierLike("Expected a binding name in the loop's '(…)'");
            group.parts.push_back(nameOnly);
        }
        skipNewlines();
    } while (match(TokenType::COMMA));
    consume(TokenType::R_PAREN, "Expected ')' after the loop's bindings");
    if (group.parts.size() < 2) fail(oneElementTuple(open.line, "pattern"));
    return group;
}

/**
 * The bindings a destructuring loop header opens its body with: each name
 * takes the row element at its position; a nested group takes its own element
 * apart the same way.
 */
std::vector<Stmt> Parser::destructuredPrologue(const LoopPattern& pattern, const std::string& row, const Token& start) {
    std::vector<Stmt> out;
    for (size_t index = 0; index < pattern.parts.size(); index++) {
        const LoopPattern& part = pattern.parts[index];
        ExprMember element;
        element.target = make(arena, ExprIdentifier{row, start.line, start.column, u16len(row)});
        element.name = str(index);
        element.line = start.line;
        element.column = start.column;
        Expr elementExpr = make(arena, element);
        if (part.name) {
            StmtFinDecl binding;
            binding.name = *part.name;
            binding.initializer = elementExpr;
            binding.line = start.line;
            binding.column = start.column;
            binding.synthesized = true;
            out.push_back(make(arena, binding));
        } else {
            std::string inner = row + "_" + str(index);
            StmtFinDecl binding;
            binding.name = inner;
            binding.initializer = elementExpr;
            binding.line = start.line;
            binding.column = start.column;
            binding.synthesized = true;
            out.push_back(make(arena, binding));
            std::vector<Stmt> nested = destructuredPrologue(part, inner, start);
            out.insert(out.end(), nested.begin(), nested.end());
        }
    }
    return out;
}

Stmt Parser::parseLoop(const std::optional<std::string>& label /*= std::nullopt*/) {
    Token start = peek();
    consume(TokenType::LOOP, "Expected 'loop'");
    // `loop { }` repeats, and `loop by <seconds> { }` repeats with a wait.
    bool savedTrailing = allowTrailingLambda;
    allowTrailingLambda = false;
    if (!check(TokenType::L_BRACE) && !check(TokenType::THEN) && !check(TokenType::BY)) {
        fail("'loop <iterable>' was removed at line " + str(start.line) +
             ": write 'for row in <iterable> { … }' to walk something, or 'loop { … }' to repeat");
    }
    // `loop by <seconds> { … }` - repeat, waiting between passes.
    Expr everySeconds;
    if (match(TokenType::BY)) everySeconds = parseExpr();
    allowTrailingLambda = savedTrailing;
    std::vector<Stmt> body = parseThenOrBracedBody("loop");
    // do-while: `loop { body } while cond` - desugared to
    // `loop { body; if (!cond) { break } }`.
    if (match(TokenType::WHILE)) {
        Expr cond = parseExpr();
        Expr negated = make(arena, ExprUnary{TokenType::BANG, cond, start.line, start.column, u16len(start.lexeme)});
        Stmt exit = make(arena, StmtIf{negated, {make(arena, StmtBreak{})}, std::nullopt, start.line, start.column});
        body.push_back(exit);
    }
    skipNewlines();
    std::optional<std::vector<Stmt>> elseBranch = parseLoopElse();
    consumeNewline();
    // Kotlin's `loop iterable { … }` lowering follows here; its iterable is
    // always null since the form was removed, so it is unreachable and not
    // ported. `everySeconds` is parsed but only that lowering used it.
    (void)everySeconds;
    StmtLoop loop;
    loop.body = body;
    loop.line = start.line;
    loop.column = start.column;
    loop.label = label;
    Stmt loopStmt = make(arena, loop);
    if (elseBranch) return withLoopElse(loopStmt, *elseBranch, start.line, start.column);
    return loopStmt;
}

Stmt Parser::parseBreak() {
    Token start = consume(TokenType::BREAK, "Expected 'break'");
    std::optional<std::string> label = parseOptionalLabel("break");
    Expr value;
    if (!check(TokenType::NEWLINE) && !check(TokenType::SEMICOLON) && !check(TokenType::R_BRACE) &&
        !check(TokenType::ELSE) && !isAtEnd()) {
        value = parseExpr();
    }
    consumeNewline();
    return make(arena, StmtBreak{label, start.line, start.column, u16len(start.lexeme), value});
}

Stmt Parser::parseContinue() {
    Token start = consume(TokenType::CONTINUE, "Expected 'continue'");
    std::optional<std::string> label = parseOptionalLabel("continue");
    consumeNewline();
    return make(arena, StmtContinue{label, start.line, start.column, u16len(start.lexeme)});
}

StmtThrow* Parser::parseThrow() {
    Token start = peek();
    consume(TokenType::THROW, "Expected 'throw'");
    Expr value = parseExpr();
    consumeNewline();
    return make(arena, StmtThrow{value, start.line, start.column});
}

/** `panic "msg"` (runtime abort) or `inline panic "msg"` (compile-time abort when reached). */
StmtPanic* Parser::parsePanicStmt(bool inlinePanic /*= false*/) {
    Token start = peek();
    if (inlinePanic) consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::PANIC, "Expected 'panic'");
    Expr message = parseExpr();
    consumeNewline();
    return make(arena, StmtPanic{message, inlinePanic, start.line, start.column});
}

/** `fail <expr>` - sugar for `throw <expr>`. */
StmtThrow* Parser::parseFailThrow() {
    Token start = peek();
    consume(TokenType::ERROR, "Expected 'fail'");
    Expr value = parseExpr();
    consumeNewline();
    return make(arena, StmtThrow{value, start.line, start.column});
}

/**
 * `inline "<string>"` - converts a string into code at compile time, parsed
 * as Azora source and spliced in place.
 */
Stmt Parser::parseInlineSplice() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    Expr templateExpr = parsePrimary();
    std::string rendered;
    if (ExprStringLiteral* literal = as<ExprStringLiteral>(templateExpr)) {
        rendered = literal->value;
    } else if (ExprStringTemplate* tmpl = as<ExprStringTemplate>(templateExpr)) {
        for (const StringTemplatePart& part : tmpl->parts) {
            if (part.isLiteral) {
                rendered += part.text;
            } else {
                ExprIdentifier* id = as<ExprIdentifier>(part.expr);
                fail("inline splice '$" + (id ? id->name : std::string()) +
                     "' interpolation is only valid inside an `inline for … with index` comptime context at line " +
                     str(start.line));
            }
        }
    } else {
        fail("Expected string after 'inline' at line " + str(start.line));
    }
    Parser sub(arena, Lexer("func __mixin() {\n" + rendered + "\n}").tokenize(), nullptr, nullptr, true);
    Program wrapper = sub.parse();
    TopLevelFunc* func = wrapper.items.empty() ? nullptr : as<TopLevelFunc>(wrapper.items[0]);
    if (func == nullptr) fail("inline splice did not produce any statements at line " + str(start.line));
    consumeNewline();
    return make(arena, StmtInlineBlock{func->decl.body, start.line, start.column});
}

/** `unsafe { body }` or `unsafe statement` - the same explicit safety boundary. */
StmtScope* Parser::parseUnsafe() {
    Token start = peek();
    consume(TokenType::UNSAFE, "Expected 'unsafe'");
    skipNewlines();
    std::vector<Stmt> body;
    if (match(TokenType::L_BRACE)) {
        skipNewlines();
        body = parseBlock();
        consume(TokenType::R_BRACE, "Expected '}' after unsafe body");
    } else {
        body = {parseSingleStmt("unsafe body")};
    }
    consumeNewline();
    return make(arena, StmtScope{.body = body, .line = start.line, .column = start.column, .unsafe = true});
}

/** Releases one heap value or each value in `purge (a, b, c)`, in order. */
Stmt Parser::parsePurge() {
    Token start = consume(TokenType::PURGE, "Expected 'purge'");
    if (check(TokenType::L_BRACKET)) fail("Purge lists use parentheses; write 'purge (a, b)' at line " + str(start.line));
    std::vector<Expr> values;
    bool isRow = parseParenthesizedList("purge target", [&] { values.push_back(parseExpr()); });
    if (!isRow) values.push_back(parseExpr());
    consumeNewline();
    std::vector<Stmt> purges;
    for (const Expr& value : values) {
        purges.push_back(make(arena, StmtExprStmt{purgeCall(value, start), start.line, start.column}));
    }
    if (purges.size() == 1) return purges[0];
    return make(arena, StmtScope{.body = purges, .line = start.line, .column = start.column, .shared = true});
}

Expr Parser::purgeCall(const Expr& value, const Token& start) {
    ExprCall call;
    call.callee = "__purge";
    call.args = {value};
    call.line = start.line;
    call.column = start.column;
    call.length = u16len(start.lexeme);
    return make(arena, call);
}

StmtDefer* Parser::parseDefer() {
    Token start = peek();
    consume(TokenType::DEFER, "Expected 'defer'");
    consume(TokenType::L_BRACE, "Expected '{' after 'defer'");
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}'");
    consumeNewline();
    return make(arena, StmtDefer{body, start.line, start.column});
}

/** `fail defer { body }` - a defer that runs only when the function exits via an error. */
StmtDefer* Parser::parseFailDefer() {
    Token start = peek();
    consume(TokenType::ERROR, "Expected 'fail'");
    consume(TokenType::DEFER, "Expected 'defer' after 'fail'");
    consume(TokenType::L_BRACE, "Expected '{' after 'defer'");
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}'");
    consumeNewline();
    return make(arena, StmtDefer{.body = body, .line = start.line, .column = start.column, .onFail = true});
}

/** `rescue { body }` - catch-and-suppress: runs the handler on error, then swallows it. */
StmtDefer* Parser::parseRescue() {
    Token start = peek();
    consume(TokenType::RESCUE, "Expected 'rescue'");
    consume(TokenType::L_BRACE, "Expected '{' after 'rescue'");
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}'");
    consumeNewline();
    return make(arena, StmtDefer{.body = body, .line = start.line, .column = start.column, .onFail = true, .suppress = true});
}

/** The reactive lifetime keyword at [index], if there is one. */
std::optional<ReactiveKind> Parser::reactiveLifetimeAt(int32_t index) {
    if (typeIs(index, TokenType::REMEMBER)) return ReactiveKind::REMEMBER;
    if (typeIs(index, TokenType::RETAIN)) return ReactiveKind::RETAIN;
    if (typeIs(index, TokenType::PRESERVE)) return ReactiveKind::PRESERVE;
    return std::nullopt;
}

/** `remember|retain|preserve <var|val|let|fin> name: T = init`. */
StmtRemDecl* Parser::parseReactiveDecl(ReactiveKind kind) {
    Token start = peek();
    TokenType expected = kind == ReactiveKind::REMEMBER ? TokenType::REMEMBER
                         : kind == ReactiveKind::RETAIN ? TokenType::RETAIN
                                                        : TokenType::PRESERVE;
    std::string spelled = spelling(kind);
    consume(expected, "Expected '" + spelled + "'");
    if (check(TokenType::THREADLOCAL)) {
        fail("'" + spelled + "' cannot be combined with 'threadlocal' at line " + str(peek().line) +
             " - a reactive lifetime and thread-local storage are different answers to where a value lives");
    }
    BindingKind binding;
    if (match(TokenType::VAR)) binding = BindingKind::VAR;
    else if (match(TokenType::VAL)) binding = BindingKind::VAL;
    else if (match(TokenType::LET)) binding = BindingKind::LET;
    else if (match(TokenType::FIN)) binding = BindingKind::FIN;
    else {
        fail("'" + spelled + "' needs a binding form at line " + str(peek().line) + " - write '" + spelled + " var', '" +
             spelled + " val', '" + spelled + " let' or '" + spelled + " fin'");
    }
    std::string name = consume(TokenType::IDENTIFIER, "Expected reactive variable name").lexeme;
    TypeAnnotation type;
    if (match(TokenType::COLON)) type = explicitType(parseTypeName());
    consume(TokenType::EQUAL, "Expected '=' in reactive declaration");
    Expr init = parseExpr();
    consumeNewline();
    return make(arena, StmtRemDecl{name, type, init, start.line, start.column, 0, kind, binding});
}

/** Reactive effect with automatic, explicit, or disposal-only execution. */
StmtEffect* Parser::parseEffect() {
    Token start = peek();
    consume(TokenType::EFFECT, "Expected 'effect'");
    bool deferred = match(TokenType::DEFER);
    Expr bare;
    std::optional<std::vector<Expr>> dependencies;
    if (deferred || check(TokenType::L_BRACE)) {
        // automatic tracking
    } else if (match(TokenType::L_BRACKET)) {
        std::vector<Expr> values;
        if (!check(TokenType::R_BRACKET)) {
            do {
                values.push_back(parseExpr());
            } while (match(TokenType::COMMA));
        }
        consume(TokenType::R_BRACKET, "Expected ']' after effect dependencies");
        dependencies = values;
    } else {
        bool savedTrailing = allowTrailingLambda;
        allowTrailingLambda = false;
        Expr dependency = parseExpr();
        allowTrailingLambda = savedTrailing;
        bare = dependency;
        dependencies = std::vector<Expr>{dependency};
    }
    // A bare name is the dependency form; any other unbracketed expression is a
    // condition whose rising edge runs the body.
    Expr condition;
    if (bare && !is<ExprIdentifier>(bare)) condition = bare;
    consume(TokenType::L_BRACE, "Expected '{' after 'effect'");
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}'");
    consumeNewline();
    StmtEffect effect;
    effect.body = body;
    effect.line = start.line;
    effect.column = start.column;
    effect.dependencies = dependencies;
    effect.deferred = deferred;
    effect.condition = condition;
    return make(arena, effect);
}

/** `using value { ... }` / `using (a, b) { ... }` contextual receiver scope. */
StmtUsingContext* Parser::parseUsingContext() {
    Token start = consume(TokenType::USING, "Expected 'using'");
    std::vector<Expr> values;
    if (check(TokenType::L_BRACKET)) {
        fail("Context receiver lists use parentheses at line " + str(start.line) +
             "; replace 'using [a, b] { ... }' with 'using (a, b) { ... }'");
    }
    if (match(TokenType::L_PAREN)) {
        skipNewlines();
        if (check(TokenType::R_PAREN)) fail("A contextual receiver group cannot be empty at line " + str(start.line));
        while (!check(TokenType::R_PAREN) && !isAtEnd()) {
            bool savedTrailing = allowTrailingLambda;
            allowTrailingLambda = false;
            values.push_back(parseExpr());
            allowTrailingLambda = savedTrailing;
            const Token* previous = tokenOrNull(current - 1);
            int32_t previousLine = previous ? previous->line : peek().line;
            bool separatedByPhysicalLine = peek().line > previousLine;
            if (match(TokenType::COMMA)) {
                skipNewlines();
            } else if (check(TokenType::NEWLINE)) {
                skipNewlines();
            } else if (check(TokenType::R_PAREN)) {
            } else if (separatedByPhysicalLine) {
            } else {
                fail("Expected ',' or a new line between contextual receivers");
            }
        }
        consume(TokenType::R_PAREN, "Expected ')' after contextual receivers");
        if (values.size() < 2) {
            fail("A single contextual receiver is written without parentheses at line " + str(start.line) +
                 ": use 'using value { ... }'");
        }
    } else {
        bool savedTrailing = allowTrailingLambda;
        allowTrailingLambda = false;
        values.push_back(parseExpr());
        allowTrailingLambda = savedTrailing;
    }
    // `using value then statement` - one statement, as `if c then s` takes.
    if (match(TokenType::THEN)) {
        skipNewlines();
        std::vector<Stmt> body{parseSingleStmt("using body")};
        consumeNewline();
        return make(arena, StmtUsingContext{values, body, start.line, start.column});
    }
    consume(TokenType::L_BRACE, "Expected '{' or 'then' after contextual values");
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}' after using body");
    consumeNewline();
    return make(arena, StmtUsingContext{values, body, start.line, start.column});
}

/** `threadlocal var x: T = init` / `threadlocal fin y: T = init` - thread-local storage. */
TopLevel Parser::parseThreadLocal(const Visibility& visibility /*= Visibility{}*/) {
    Token start = consume(TokenType::THREADLOCAL, "Expected 'threadlocal'");
    if (std::optional<ReactiveKind> lifetime = reactiveLifetimeAt(current)) {
        fail(std::string("'threadlocal' cannot be combined with '") + spelling(*lifetime) + "' at line " +
             str(start.line) +
             " - a reactive lifetime and thread-local storage are different answers to where a value lives");
    }
    if (check(TokenType::VAR) || check(TokenType::VAL)) {
        bool valueMutable = advance().type == TokenType::VAR;
        std::string name = consume(TokenType::IDENTIFIER, "Expected variable name").lexeme;
        TypeRef type;
        if (match(TokenType::COLON)) type = parseTypeName();
        consume(TokenType::EQUAL, "Expected '='");
        Expr init = parseExpr();
        consumeNewline();
        return make(arena, TopLevelVarDecl{.name = name, .type = type, .initializer = init, .line = start.line,
                                           .column = start.column, .threadlocal = true, .visibility = visibility,
                                           .valueMutable = valueMutable});
    }
    if (check(TokenType::FIN)) {
        advance();
        std::string name = consume(TokenType::IDENTIFIER, "Expected variable name").lexeme;
        TypeRef type;
        if (match(TokenType::COLON)) type = parseTypeName();
        consume(TokenType::EQUAL, "Expected '='");
        Expr init = parseExpr();
        consumeNewline();
        return make(arena, TopLevelFinDecl{.name = name, .type = type, .initializer = init, .line = start.line,
                                           .column = start.column, .threadlocal = true, .visibility = visibility});
    }
    fail("Expected 'var' or 'fin' after 'threadlocal' at line " + str(peek().line));
}

}  // namespace azora
