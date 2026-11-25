// Parser.kt lines 11680-12226: the postfix chain (members, borrows, indexing,
// slices, reflection, generic calls, calls, trailing lambdas), macro
// invocation arguments, and generic-application lookahead.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"

namespace azora {

namespace {

/** Tokens after which a trailing `&`/`!` is a call-site borrow, not a binary op. */
bool isBorrowTerminator(TokenType t) {
    return t == TokenType::R_PAREN || t == TokenType::COMMA || t == TokenType::R_BRACKET || t == TokenType::R_BRACE ||
           t == TokenType::NEWLINE || t == TokenType::EOF_;
}

}  // namespace

/**
 * Postfix chain: member access (`a.b`), indexing (`a[i]`), and calls (`f(...)`,
 * `a.m(...)`), repeated left-associatively.
 */
Expr Parser::parsePostfix(const Expr& initial /*= nullptr*/) {
    Expr expr = initial ? initial : parsePrimary();
    std::vector<TypeRef> pendingCallTypeArgs;
    auto callOn = [&](const Expr& target, std::vector<Expr> args, bool consumeTypeArgs) -> Expr {
        if (ExprIdentifier* id = as<ExprIdentifier>(target)) {
            ExprCall call;
            call.callee = id->name;
            call.args = std::move(args);
            call.line = id->line;
            call.column = id->column;
            call.length = id->length;
            call.typeArgs = pendingCallTypeArgs;
            if (consumeTypeArgs) pendingCallTypeArgs.clear();
            return make(arena, call);
        }
        ExprCall call;
        call.args = std::move(args);
        call.line = target.line();
        call.column = target.column();
        call.length = target.length();
        call.receiver = target;
        return make(arena, call);
    };
    while (true) {
        // A fragment macro stands for source, so it is expanded first.
        if (spliceFragmentMacro()) continue;
        // A chain may be broken across lines before each `.`, unless the `.`
        // opens a `when` arm.
        if (check(TokenType::NEWLINE)) {
            int32_t ahead = current;
            while (typeIs(ahead, TokenType::NEWLINE)) ahead++;
            bool opensWhenArm = opensWhenArmAt(ahead);
            if (typeIs(ahead, TokenType::DOT) && !typeIs(ahead + 1, TokenType::AMP) && !typeIs(ahead + 1, TokenType::BANG) &&
                !opensWhenArm) {
                current = ahead;
            }
        }
        // A `(` or `[` opening a line starts the next value.
        if (check(TokenType::L_PAREN) || check(TokenType::L_BRACKET)) {
            const Token* previous = tokenOrNull(current - 1);
            int32_t previousLine = previous ? previous->line : peek().line;
            if (peek().line > previousLine) return expr;
        }
        if (check(TokenType::PLUS_PLUS) || check(TokenType::MINUS_MINUS)) {
            Token op = advance();
            expr = make(arena, ExprIncDec{expr, op.type, false, op.line, op.column, u16len(op.lexeme)});
        } else if (check(TokenType::DOT) && (peekNextIs(TokenType::AMP) || peekNextIs(TokenType::BANG))) {
            // Call-site borrow markers: `x.&` / `x.!`.
            advance();  // '.'
            Token sigil = advance();  // '&' or '!'
            expr = make(arena, ExprIsolated{expr, sigil.line, sigil.column, 2, borrowOp(sigil.type)});
        } else if ((check(TokenType::AMP) || check(TokenType::BANG)) && peekNext() && isBorrowTerminator(peekNext()->type)) {
            // The bare `x&` / `x!` is a borrow only when a delimiter ends the expression.
            Token sigil = advance();
            expr = make(arena, ExprIsolated{expr, sigil.line, sigil.column, 1, borrowOp(sigil.type)});
        } else if (check(TokenType::DOT)) {
            Token dot = advance();
            // `p.*` reads through a const pointer, `p.^` through a mutable one.
            if (check(TokenType::STAR) || check(TokenType::CARET)) {
                Token sigil = advance();
                expr = make(arena, ExprDeref{expr, expr.line(), expr.column(), u16len(dot.lexeme) + u16len(sigil.lexeme)});
                continue;
            }
            // `target.${ expr }` - the member name is computed.
            if (check(TokenType::IDENTIFIER) && peek().lexeme == "$" && peekNextIs(TokenType::L_BRACE)) {
                advance();  // '$'
                advance();  // '{'
                Expr nameExpr = parseExpr();
                consume(TokenType::R_BRACE, "Expected '}' to close a spliced member name");
                expr = make(arena, ExprMember{expr, "", expr.line(), expr.column(), 0, nameExpr});
                continue;
            }
            if (check(TokenType::INT_LITERAL)) {
                int64_t idx = std::get<int64_t>(std::get<NumericLiteral>(advance().literal).value);
                std::string text = str((long long)idx);
                expr = make(arena, ExprMember{expr, text, expr.line(), expr.column(), u16len(dot.lexeme) + u16len(text)});
            } else {
                std::string name = consumeMemberName("Expected member name after '.'");
                expr = make(arena, ExprMember{expr, name, expr.line(), expr.column(), u16len(dot.lexeme) + u16len(name)});
            }
        } else if (check(TokenType::QMARK_DOT)) {
            advance();
            std::string name = consumeMemberName("Expected member name after '?.'");
            expr = make(arena, ExprSafeMember{expr, name, expr.line(), expr.column()});
        } else if (check(TokenType::LESS) && is<ExprIdentifier>(expr) && isGenericStaticAccessAhead()) {
            // `Array<Int, 3>::size` - a static member of an *applied* type.
            std::vector<TypeRef> args = parseGenericTypeArgsIfPresent();
            consume(TokenType::DOUBLE_COLON, "Expected '::' after the type arguments");
            std::string member = consumeMemberName("Expected member name after '::'");
            const ExprIdentifier& owner = cast<ExprIdentifier>(expr);
            expr = make(arena, ExprIdentifier{owner.name + "__" + member, owner.line, owner.column,
                                              u16len(owner.name) + 2 + u16len(member)});
            pendingCallTypeArgs = args;
        } else if (check(TokenType::DOUBLE_COLON)) {
            // Namespace member access `Name::member` → mangled identifier `Name__member`.
            advance();  // '::'
            std::string member = consumeMemberName("Expected member name after '::'");
            if (ExprIdentifier* id = as<ExprIdentifier>(expr)) {
                expr = make(arena, ExprIdentifier{id->name + "__" + member, id->line, id->column, id->length + 2 + u16len(member)});
            } else if (isModulePath(expr)) {
                // `std.container.array::Array` - a dotted module path, then the symbol.
                expr = make(arena, ExprIdentifier{member, expr.line(), expr.column(), u16len(member)});
            } else {
                fail("'::' must follow a namespace name at line " + str(peek().line));
            }
        } else if (allowTrailingLambda && check(TokenType::L_BRACKET) && isReceiverLambdaAhead()) {
            ExprLambda* lambda = parseReceiverLambda();
            expr = appendTrailingLambda(expr, lambda, pendingCallTypeArgs);
            pendingCallTypeArgs.clear();
        } else if (check(TokenType::L_BRACKET)) {
            advance();
            // `a[start:stop:step]` - a Python-style slice → oper[:].
            if (check(TokenType::COLON)) {
                Expr stop = parseExpr();
                Expr step;
                if (match(TokenType::COLON)) step = parseExpr();
                consume(TokenType::R_BRACKET, "Expected ']' after slice");
                expr = make(arena, ExprSlice{expr, nullptr, stop, step, expr.line(), expr.column()});
            } else {
                Expr firstIndex = parseExpr();
                if (match(TokenType::COLON)) {
                    Expr stop;
                    if (!check(TokenType::R_BRACKET)) stop = parseExpr();
                    Expr step;
                    if (match(TokenType::COLON)) step = parseExpr();
                    consume(TokenType::R_BRACKET, "Expected ']' after slice");
                    expr = make(arena, ExprSlice{expr, firstIndex, stop, step, expr.line(), expr.column()});
                } else {
                    consume(TokenType::R_BRACKET, "Expected ']' after index");
                    expr = make(arena, ExprIndex{expr, firstIndex, expr.line(), expr.column()});
                }
            }
        } else if (check(TokenType::LESS) && is<ExprMember>(expr) &&
                   (cast<ExprMember>(expr).name == "hasAnnot" || cast<ExprMember>(expr).name == "annotMeta" ||
                    cast<ExprMember>(expr).name == "withAnnot")) {
            const ExprMember& member = cast<ExprMember>(expr);
            Expr innerTarget = member.target;
            if (ExprGrouping* grouping = as<ExprGrouping>(member.target)) innerTarget = grouping->expr;
            // Reflected declaration members go inside the angle brackets with `::`.
            if (ExprMember* innerMember = as<ExprMember>(innerTarget)) {
                ExprCall* innerCall = as<ExprCall>(innerMember->target);
                if (innerCall && innerCall->callee == "__reflect") {
                    fail("Reflected declaration members use '::', for example 'reflect<Type::" + innerMember->name +
                         ">' at line " + str(member.line));
                }
            }
            ExprCall* reflected = as<ExprCall>(innerTarget);
            if (reflected == nullptr || reflected->callee != "__reflect") {
                fail("'" + member.name + "' requires an explicit reflect<receiver>, for example 'reflect<Type>." +
                     member.name + "<Decorator>' at line " + str(member.line));
            }
            std::vector<TypeRef> typeArgs = parseGenericTypeArgsIfPresent();
            if (typeArgs.size() != 1) {
                fail("'" + member.name + "' expects exactly one decorator type argument at line " + str(member.line));
            }
            std::string intrinsic = member.name == "hasAnnot" ? "__hasAnnot"
                                    : member.name == "annotMeta" ? "__annotMeta"
                                                                 : "__withAnnot";
            ExprCall call;
            call.callee = intrinsic;
            call.args = reflected->args;
            call.line = member.line;
            call.column = member.column;
            call.length = member.length;
            call.typeArgs = typeArgs;
            expr = make(arena, call);
        } else if (check(TokenType::LESS) && is<ExprIdentifier>(expr) && isReflectName(cast<ExprIdentifier>(expr).name) &&
                   isReflectTargetAhead()) {
            // `reflect<X>` - desugars to the internal `__reflect(X)` receiver.
            Expr start = expr;
            advance();  // '<'
            Expr target = parseReflectTarget();
            if (pendingGreater) pendingGreater = false;
            else consume(TokenType::GREATER, "Expected '>' to close 'reflect<...>'");
            ExprCall call;
            call.callee = "__reflect";
            call.args = {target};
            call.line = start.line();
            call.column = start.column();
            call.length = start.length();
            call.typeArgs = reflectTargetArgs;
            expr = make(arena, call);
        } else if (check(TokenType::LESS) && isGenericCallAhead()) {
            // `f<T, U>(args)` - explicit type arguments for the call.
            advance();  // '<'
            std::vector<TypeRef> tArgs;
            if (!check(TokenType::GREATER) && !check(TokenType::SHIFT_RIGHT) && !pendingGreater) {
                do {
                    tArgs.push_back(parseTypeArg());
                } while (matchTypeArgumentComma());
                if (check(TokenType::ELLIPSIS)) {
                    fail("Variadic type arguments use the prefix form '<...T>', not '<T...>', at line " + str(peek().line));
                }
            }
            consumeTypeArgumentClose("Expected '>' to close call type arguments");
            pendingCallTypeArgs = tArgs;
        } else if (check(TokenType::L_PAREN)) {
            advance();
            std::vector<Expr> args = withinExpressionDelimiter([&] { return parseCallArgumentList(); });
            consume(TokenType::R_PAREN, "Expected ')' after arguments");
            // A fragment macro may stand where the block's markers go.
            spliceFragmentMacro();
            // Trailing lambda: `f(args) { x -> ... }` or `f(args) [context: Context&] { ... }`.
            bool trailingReact = allowTrailingLambda && check(TokenType::REACT) &&
                                 (peekNextIs(TokenType::L_BRACE) || peekNextIs(TokenType::L_BRACKET));
            if (trailingReact) advance();
            CallableKind trailingKind = trailingReact ? CallableKind::REACT : CallableKind::FUNC;
            if (allowTrailingLambda && check(TokenType::L_BRACE)) {
                Token lb = peek();
                ExprIdentifier* id = as<ExprIdentifier>(expr);
                bool isAsync = id && id->name == "async";
                args.push_back(parseLambda(lb.line, lb.column, !isAsync, trailingKind));
            } else if (allowTrailingLambda && check(TokenType::L_BRACKET) && isReceiverLambdaAhead()) {
                args.push_back(parseReceiverLambda(trailingKind));
            }
            switch (expr.kind()) {
                case ExprKind::Identifier:
                    expr = callOn(expr, args, true);
                    break;
                case ExprKind::InferredMember: {
                    // `.Every(5.seconds())` / `::fillMaxWidth()`.
                    const ExprInferredMember& inferred = cast<ExprInferredMember>(expr);
                    if (inferred.scopeRead) {
                        ExprCall scopeCall;
                        scopeCall.args = args;
                        scopeCall.line = inferred.line;
                        scopeCall.column = inferred.column;
                        scopeCall.length = inferred.length;
                        scopeCall.receiver = inferred.scopeRead;
                        ExprInferredMember fresh{.name = inferred.name,
                                                 .line = inferred.line,
                                                 .column = inferred.column,
                                                 .length = inferred.length,
                                                 .ctorArgs = args,
                                                 .scopeCall = make(arena, scopeCall)};
                        expr = make(arena, fresh);
                    } else {
                        ExprInferredMember copy = inferred;
                        copy.ctorArgs = args;
                        expr = make(arena, copy);
                    }
                    break;
                }
                case ExprKind::UpperScopeAccess: {
                    ExprCall call;
                    call.args = args;
                    call.line = expr.line();
                    call.column = expr.column();
                    call.length = expr.length();
                    call.receiver = expr;
                    expr = make(arena, call);
                    break;
                }
                case ExprKind::Member: {
                    // `target.name<T>(args)` keeps the type arguments it wrote.
                    const ExprMember& member = cast<ExprMember>(expr);
                    ExprMethodCall call;
                    call.target = member.target;
                    call.name = member.name;
                    call.args = args;
                    call.line = member.line;
                    call.column = member.column;
                    call.typeArgs = pendingCallTypeArgs;
                    pendingCallTypeArgs.clear();
                    expr = make(arena, call);
                    break;
                }
                default:
                    // Calling an arbitrary expression value, e.g. `fs[0](x)`.
                    expr = callOn(expr, args, false);
                    break;
            }
        } else if (allowTrailingLambda && check(TokenType::REACT) &&
                   (peekNextIs(TokenType::L_BRACE) || peekNextIs(TokenType::L_BRACKET))) {
            // `f react { … }` / `f react [&] { … }` without parentheses.
            advance();  // 'react'
            ExprLambda* lambda;
            if (check(TokenType::L_BRACKET) && isReceiverLambdaAhead()) {
                lambda = parseReceiverLambda(CallableKind::REACT);
            } else {
                Token lb = peek();
                lambda = parseLambda(lb.line, lb.column, true, CallableKind::REACT);
            }
            if (ExprMember* member = as<ExprMember>(expr)) {
                ExprMethodCall call;
                call.target = member->target;
                call.name = member->name;
                call.args = {lambda};
                call.line = member->line;
                call.column = member->column;
                call.typeArgs = pendingCallTypeArgs;
                pendingCallTypeArgs.clear();
                expr = make(arena, call);
            } else {
                expr = callOn(expr, {lambda}, true);
            }
        } else if (allowTrailingLambda && check(TokenType::L_BRACE)) {
            Token lb = peek();
            ExprIdentifier* id = as<ExprIdentifier>(expr);
            bool isAsync = id && id->name == "async";
            ExprLambda* lambda = parseLambda(lb.line, lb.column, !isAsync);
            if (ExprMember* member = as<ExprMember>(expr)) {
                ExprMethodCall call;
                call.target = member->target;
                call.name = member->name;
                call.args = {lambda};
                call.line = member->line;
                call.column = member->column;
                call.length = member->length;
                call.typeArgs = pendingCallTypeArgs;
                pendingCallTypeArgs.clear();
                expr = make(arena, call);
            } else {
                expr = callOn(expr, {lambda}, true);
            }
        } else {
            return expr;
        }
    }
}

/** Adds a parenthesis-free trailing lambda to any callable expression. */
Expr Parser::appendTrailingLambda(const Expr& target, ExprLambda* lambda, const std::vector<TypeRef>& typeArgs) {
    if (ExprIdentifier* id = as<ExprIdentifier>(target)) {
        ExprCall call;
        call.callee = id->name;
        call.args = {lambda};
        call.line = id->line;
        call.column = id->column;
        call.length = id->length;
        call.typeArgs = typeArgs;
        return make(arena, call);
    }
    if (ExprMember* member = as<ExprMember>(target)) {
        ExprMethodCall call;
        call.target = member->target;
        call.name = member->name;
        call.args = {lambda};
        call.line = member->line;
        call.column = member->column;
        call.length = member->length;
        call.typeArgs = typeArgs;
        return make(arena, call);
    }
    ExprCall call;
    call.args = {lambda};
    call.line = target.line();
    call.column = target.column();
    call.length = target.length();
    call.receiver = target;
    return make(arena, call);
}

/**
 * True when an `@` opens a macro invocation rather than a decorator or label:
 * `@name` followed by `(`, `[` or `{` after an optional name sigil.
 */
bool Parser::isMacroInvokeAhead() {
    if (!check(TokenType::AT)) return false;
    int32_t i = current + 1;
    if (!typeIs(i, TokenType::IDENTIFIER)) return false;
    i++;
    while (typeIs(i, TokenType::DOUBLE_COLON)) {
        i++;
        if (!typeIs(i, TokenType::IDENTIFIER)) return false;
        i++;
    }
    if (typeIs(i, TokenType::BANG) || typeIs(i, TokenType::QMARK) || typeIs(i, TokenType::AMP) ||
        typeIs(i, TokenType::STAR) || typeIs(i, TokenType::CARET)) {
        i++;
    }
    return typeIs(i, TokenType::L_PAREN) || typeIs(i, TokenType::L_BRACKET) || typeIs(i, TokenType::L_BRACE);
}

std::vector<Expr> Parser::parseMacroInvokeArgs() {
    TokenType close;
    std::string closeStr;
    switch (peek().type) {
        case TokenType::L_PAREN:
            advance();
            close = TokenType::R_PAREN;
            closeStr = ")";
            break;
        case TokenType::L_BRACKET:
            advance();
            close = TokenType::R_BRACKET;
            closeStr = "]";
            break;
        case TokenType::L_BRACE:
            advance();
            close = TokenType::R_BRACE;
            closeStr = "}";
            break;
        default:
            fail("macro invocation expects '(', '[', or '{' at line " + str(peek().line));
    }
    skipNewlines();
    std::vector<Expr> args;
    if (!check(close)) {
        do {
            skipNewlines();
            if (check(close)) break;
            Expr arg;
            if (match(TokenType::ELLIPSIS)) {
                Expr firstArg = parseExpr();
                arg = make(arena, ExprSpread{firstArg, firstArg.line(), firstArg.column(), firstArg.length()});
            } else {
                Expr firstArg = parseExpr();
                // `@map[1: "one"]` - a keyed argument.
                if (match(TokenType::COLON)) {
                    Expr value = parseExpr();
                    arg = make(arena, ExprMapEntryArg{firstArg, value, firstArg.line(), firstArg.column(), firstArg.length()});
                } else {
                    arg = firstArg;
                }
            }
            args.push_back(arg);
            skipNewlines();
        } while (match(TokenType::COMMA));
    }
    consume(close, "Expected '" + closeStr + "' to close macro invocation");
    return args;
}

/** Whether the token after a closing `>` continues a generic application. */
bool Parser::closesGenericApplication(int32_t closingIndex) {
    if (typeIs(closingIndex + 1, TokenType::L_PAREN) || typeIs(closingIndex + 1, TokenType::L_BRACE) ||
        typeIs(closingIndex + 1, TokenType::DOT)) {
        return true;
    }
    if (typeIs(closingIndex + 1, TokenType::L_BRACKET)) return isReceiverLambdaAhead(closingIndex + 1);
    return false;
}

/** True when `<…>::` follows - a static member reached through an applied type. */
bool Parser::isGenericStaticAccessAhead() {
    int32_t i = current + 1;
    int depth = 1;
    int steps = 0;
    while (i < (int32_t)tokens.size() && steps < 48) {
        TokenType t = tokens[(size_t)i].type;
        if (t == TokenType::LESS) {
            depth++;
        } else if (t == TokenType::GREATER) {
            depth--;
            if (depth == 0) return typeIs(i + 1, TokenType::DOUBLE_COLON);
        } else if (t == TokenType::SHIFT_RIGHT) {
            depth -= 2;
            if (depth == 0) return typeIs(i + 1, TokenType::DOUBLE_COLON);
            if (depth < 0) return false;
        } else if (t == TokenType::EOF_) {
            return false;
        }
        i++;
        steps++;
    }
    return false;
}

bool Parser::isGenericCallAhead() {
    int32_t i = current + 1;  // token after '<'
    int depth = 1;
    // A type argument may itself be a callable type whose parentheses must balance.
    int parens = 0;
    int steps = 0;
    while (i < (int32_t)tokens.size() && steps < 48) {
        TokenType t = tokens[(size_t)i].type;
        switch (t) {
            case TokenType::IDENTIFIER: case TokenType::COMMA: case TokenType::DOT: case TokenType::L_BRACKET:
            case TokenType::R_BRACKET: case TokenType::QMARK: case TokenType::COLON: case TokenType::STAR:
            case TokenType::INT_LITERAL: case TokenType::DOUBLE_COLON: case TokenType::ARROW: case TokenType::ELLIPSIS:
                break;
            case TokenType::L_PAREN:
                parens++;
                break;
            case TokenType::R_PAREN:
                parens--;
                if (parens < 0) return false;
                break;
            case TokenType::LESS:
                depth++;
                break;
            case TokenType::GREATER:
                if (parens > 0) {
                    i++;
                    steps++;
                    continue;
                }
                depth--;
                if (depth == 0) return closesGenericApplication(i);
                break;
            // `>>` closes two nested generic levels at once.
            case TokenType::SHIFT_RIGHT:
                if (parens > 0) {
                    i++;
                    steps++;
                    continue;
                }
                depth -= 2;
                if (depth == 0) return closesGenericApplication(i);
                if (depth < 0) return false;
                break;
            default:
                return false;
        }
        i++;
        steps++;
    }
    return false;
}

}  // namespace azora
