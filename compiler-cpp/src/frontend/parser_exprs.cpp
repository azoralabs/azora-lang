// Parser.kt lines 11150-11680: expression precedence levels, infix calls,
// reflect targets, and unary operators.
#include "frontend/parser.h"

#include "base/chars.h"
#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"

namespace azora {

namespace {

/** Tokens that can open the right operand of an infix call. */
bool isInfixOperandStart(TokenType t) {
    switch (t) {
        case TokenType::IDENTIFIER: case TokenType::INT_LITERAL: case TokenType::DOUBLE_LITERAL:
        case TokenType::STRING_LITERAL: case TokenType::CHAR_LITERAL: case TokenType::TRUE_: case TokenType::FALSE_:
        case TokenType::NULL_: case TokenType::L_PAREN: case TokenType::L_BRACKET: case TokenType::L_BRACE:
        case TokenType::MINUS: case TokenType::BANG: case TokenType::TILDE:
            return true;
        default:
            return false;
    }
}

}  // namespace

/** Whether the `catch` at the current token is the `catch` operator. */
bool Parser::isPostfixCatch() {
    if (!check(TokenType::CATCH)) return false;
    if (tryThenStart < 0) return true;
    int depth = 0;
    for (int32_t i = tryThenStart; i < current; i++) {
        TokenType t = tokens[(size_t)i].type;
        if (t == TokenType::L_PAREN || t == TokenType::L_BRACKET || t == TokenType::L_BRACE) depth++;
        else if (t == TokenType::R_PAREN || t == TokenType::R_BRACKET || t == TokenType::R_BRACE) depth--;
    }
    return depth > 0;
}

Expr Parser::parseExpr() {
    Expr e = parseOr();
    // `??` - null-coalesce
    while (check(TokenType::QMARK_QMARK)) {
        advance();
        Expr right = parseOr();
        e = make(arena, ExprNullCoalesce{e, right, e.line()});
    }
    while (isPostfixCatch()) {
        advance();  // 'catch'
        Expr fallback = parseOr();
        e = make(arena, ExprCatchExpr{e, fallback, e.line(), e.column(), e.length()});
    }
    return e;
}

/**
 * Postfix type operators `as` / `is` / `is!`: tighter than the arithmetic,
 * comparison and logical operators, looser than member access and calls.
 */
Expr Parser::parseAsIs() {
    Expr e = parseUnary();
    while (check(TokenType::AS) || check(TokenType::IS)) {
        Token op = advance();
        if (op.type == TokenType::AS) {
            // `as` (static), `as?` (dynamic → T?), `as*` (reinterpret).
            CastKind kind = CastKind::STATIC;
            if (match(TokenType::QMARK)) kind = CastKind::DYNAMIC;
            else if (match(TokenType::STAR)) kind = CastKind::REINTERPRET;
            TypeRef targetType = parseTypeName();
            ExprCast castExpr;
            castExpr.expr = e;
            castExpr.targetType = targetType;
            castExpr.kind = kind;
            castExpr.line = e.line();
            e = make(arena, castExpr);
        } else {
            // `is` - optionally negated with `!`: `expr is! Type`
            bool negated = match(TokenType::BANG);
            std::string typeName = parseQualifiedTypeName("Expected type name after 'is'");
            ExprIsCheck isCheck;
            isCheck.expr = e;
            isCheck.typeName = typeName;
            isCheck.line = e.line();
            Expr checkExpr = make(arena, isCheck);
            if (negated) {
                e = make(arena, ExprUnary{TokenType::BANG, checkExpr, op.line, op.column, u16len(op.lexeme) + 1});
            } else {
                e = checkExpr;
            }
        }
    }
    return e;
}

Expr Parser::parseOr() {
    Expr left = parseAnd();
    while (match(TokenType::OR_OR)) {
        Expr right = parseAnd();
        left = make(arena, ExprBinary{left, TokenType::OR_OR, right, left.line()});
    }
    return left;
}

Expr Parser::parseAnd() {
    Expr left = parseBitwiseOr();
    while (match(TokenType::AND_AND)) {
        Expr right = parseBitwiseOr();
        left = make(arena, ExprBinary{left, TokenType::AND_AND, right, left.line()});
    }
    return left;
}

Expr Parser::parseBitwiseOr() {
    Expr left = parseBitwiseXor();
    while (check(TokenType::PIPE)) {
        TokenType op = advance().type;
        Expr right = parseBitwiseXor();
        left = make(arena, ExprBinary{left, op, right, left.line()});
    }
    return left;
}

Expr Parser::parseBitwiseXor() {
    Expr left = parseBitwiseAnd();
    while (match(TokenType::CARET)) {
        Expr right = parseBitwiseAnd();
        left = make(arena, ExprBinary{left, TokenType::CARET, right, left.line()});
    }
    return left;
}

Expr Parser::parseBitwiseAnd() {
    Expr left = parseEquality();
    while (match(TokenType::AMP)) {
        Expr right = parseEquality();
        left = make(arena, ExprBinary{left, TokenType::AMP, right, left.line()});
    }
    return left;
}

Expr Parser::parseEquality() {
    Expr left = parseComparison();
    while (check(TokenType::EQUAL_EQUAL) || check(TokenType::BANG_EQUAL)) {
        TokenType op = advance().type;
        Expr right = parseComparison();
        left = make(arena, ExprBinary{left, op, right, left.line()});
    }
    return left;
}

Expr Parser::parseComparison() {
    Expr left = parseMembership();
    while (check(TokenType::LESS) || check(TokenType::LESS_EQUAL) || check(TokenType::GREATER) ||
           check(TokenType::GREATER_EQUAL)) {
        TokenType op = advance().type;
        Expr right = parseMembership();
        left = make(arena, ExprBinary{left, op, right, left.line()});
    }
    return left;
}

/**
 * `value in collection` - looser than arithmetic, ranges and the infix levels,
 * tighter than comparison and logic. Off inside a `for` header.
 */
Expr Parser::parseMembership() {
    Expr e = parseSpaceship();
    while (inOperatorEnabled && check(TokenType::IN)) {
        Token inTok = advance();
        Expr collection = parseSpaceship();
        ExprInCheck inCheck;
        inCheck.value = e;
        inCheck.collection = collection;
        inCheck.line = inTok.line;
        inCheck.column = inTok.column;
        e = make(arena, inCheck);
    }
    return e;
}

/**
 * `a <=> b` - three-way comparison, tighter than the relational operators;
 * non-associative.
 */
Expr Parser::parseSpaceship() {
    Expr left = parseInfix();
    if (!check(TokenType::SPACESHIP)) return left;
    Token op = advance();
    Expr right = parseInfix();
    if (check(TokenType::SPACESHIP)) {
        fail("'<=>' does not chain at line " + str(peek().line) + "; use '(a <=> b).thenBy { b <=> c }'");
    }
    return make(arena, ExprBinary{left, op.type, right, left.line()});
}

/**
 * Infix method calls: `a plus b` → `a.plus(b)`; and `a @name b` infix source
 * fragments, which are spliced and parsed again as what they became.
 */
Expr Parser::parseInfix() {
    int32_t leftStart = current;
    Expr left = parseShift();
    while (true) {
        if (check(TokenType::AT)) {
            std::optional<Token> fragmentName = tokenCopy(peekNext());
            if (fragmentName && fragmentName->type == TokenType::IDENTIFIER &&
                fragmentTables().infixFragmentMacros.contains(fragmentName->lexeme) &&
                spliceInfixFragmentMacro(leftStart, *fragmentName)) {
                current = leftStart;
                return parseInfix();
            }
        }
        // A name opening the next line starts something new.
        const Token* previous = tokenOrNull(current - 1);
        if (previous != nullptr && peek().line > previous->line) break;
        bool atForm = check(TokenType::AT) && isAtInfixCandidate();
        if (!atForm && !(check(TokenType::IDENTIFIER) && isInfixCandidate())) break;
        // `a @to b` - the `@` leads on an infix macro's call.
        if (atForm) advance();
        std::string methodName = advance().lexeme;
        Expr right = parseShift();
        ExprMethodCall call;
        call.target = left;
        call.name = methodName;
        call.args = {right};
        call.line = left.line();
        call.column = left.column();
        left = make(arena, call);
    }
    return left;
}

/**
 * `@name` between two operands: an infix macro call written with its sigil.
 * A decorator name is capitalised, so a lowercase name after `@` is never one.
 */
bool Parser::isAtInfixCandidate() {
    std::optional<Token> name = tokenCopy(peekNext());
    if (!name || name->type != TokenType::IDENTIFIER) return false;
    std::u16string units16 = decodeWtf8(name->lexeme);
    if (units16.empty()) return false;
    char16_t head = units16[0];
    if (!isLowerCase(head) && head != u'_') return false;
    const Token* after = tokenOrNull(current + 2);
    if (after == nullptr) return false;
    return isInfixOperandStart(after->type);
}

bool Parser::isInfixCandidate() {
    std::optional<Token> next = tokenCopy(peekNext());
    if (!next) return false;
    return isInfixOperandStart(next->type);
}

Expr Parser::parseShift() {
    Expr left = parseRange();
    while (check(TokenType::SHIFT_LEFT) || check(TokenType::SHIFT_RIGHT)) {
        TokenType op = advance().type;
        Expr right = parseRange();
        left = make(arena, ExprBinary{left, op, right, left.line()});
    }
    return left;
}

Expr Parser::parseRange() {
    Expr left = parseAddition();
    while (check(TokenType::DOT_DOT) || check(TokenType::DOT_DOT_LESS) || check(TokenType::GREATER_DOT_DOT)) {
        Token op = advance();
        bool inclusive = op.type != TokenType::DOT_DOT_LESS;
        Expr right = parseAddition();
        // At the operator: two ranges on one line have different positions.
        left = make(arena, ExprRange{left, right, inclusive, op.line, op.column, u16len(op.lexeme),
                                     op.type == TokenType::GREATER_DOT_DOT});
    }
    return left;
}

Expr Parser::parseAddition() {
    Expr left = parseMultiplication();
    while (check(TokenType::PLUS) || check(TokenType::MINUS)) {
        TokenType op = advance().type;
        Expr right = parseMultiplication();
        left = make(arena, ExprBinary{left, op, right, left.line()});
    }
    return left;
}

Expr Parser::parseMultiplication() {
    Expr left = parseAsIs();
    while (check(TokenType::STAR) || check(TokenType::SLASH) || check(TokenType::PERCENT)) {
        TokenType op = advance().type;
        Expr right = parseAsIs();
        left = make(arena, ExprBinary{left, op, right, left.line()});
    }
    return left;
}

/** Whether [name] names the reflection handle. */
bool Parser::isReflectName(const std::string& name) { return name == "reflect" || endsWith(name, "__reflect"); }

/** After `<`, whether the tokens spell a reflect target `Ident(::Ident)*>` or the whole-program `*`. */
bool Parser::isReflectTargetAhead() {
    int32_t i = current + 1;
    // `reflect<*>` - the whole program.
    if (typeIs(i, TokenType::STAR)) return typeIs(i + 1, TokenType::GREATER);
    if (!typeIs(i, TokenType::IDENTIFIER)) return false;
    i++;
    while (typeIs(i, TokenType::DOUBLE_COLON)) {
        if (!typeIs(i + 1, TokenType::IDENTIFIER)) return false;
        i += 2;
    }
    // `reflect<Vec<U, N>>` - the target may be a generic application.
    if (typeIs(i, TokenType::LESS)) {
        int depth = 1;
        i++;
        while (depth > 0) {
            const Token* t = tokenOrNull(i);
            if (t == nullptr || t->type == TokenType::EOF_) return false;
            if (t->type == TokenType::LESS) depth++;
            else if (t->type == TokenType::GREATER) depth--;
            else if (t->type == TokenType::SHIFT_RIGHT) depth -= 2;
            i++;
        }
        // Depth below zero means the `>>` already closed `reflect<…>` too.
        if (depth < 0) return true;
    }
    return typeIs(i, TokenType::GREATER);
}

/**
 * Parses an error-set name in a failable type, accepting the scope-qualified
 * form; `Scope::Name` mangles to `Scope__Name`.
 */
std::string Parser::parseErrorSetName(const std::string& context) {
    std::string name = consume(TokenType::IDENTIFIER, "Expected error-set name " + context).lexeme;
    while (match(TokenType::DOUBLE_COLON)) {
        name += "__" + consume(TokenType::IDENTIFIER, "Expected error-set name after '::'").lexeme;
    }
    return name;
}

/** Parses `X` / `X::member` inside `reflect<…>` into the mangled identifier. */
Expr Parser::parseReflectTarget() {
    Token start = peek();
    // `reflect<*>` - a whole-program handle.
    if (match(TokenType::STAR)) return make(arena, ExprIdentifier{"*", start.line, start.column, 1});
    std::string name = consume(TokenType::IDENTIFIER, "Expected a name inside 'reflect<...>'").lexeme;
    while (match(TokenType::DOUBLE_COLON)) {
        name += "__" + consume(TokenType::IDENTIFIER, "Expected name after '::' in 'reflect<...>'").lexeme;
    }
    // A generic application keeps its arguments.
    reflectTargetArgs = parseGenericTypeArgsIfPresent();
    return make(arena, ExprIdentifier{name, start.line, start.column, u16len(name)});
}

Expr Parser::parseUnary() {
    // `...T` in expression position names the variadic pack itself.
    if (check(TokenType::ELLIPSIS)) {
        advance();
        return parseUnary();
    }
    if (check(TokenType::TRY)) {
        Token at = advance();
        Expr operand = parseUnary();
        return make(arena, ExprTryPropagate{operand, at.line, at.column, u16len(at.lexeme)});
    }
    // `alloc <expr>` - heap-allocate a single value; `alloc T*() * N` a buffer.
    if (check(TokenType::ALLOC)) {
        Token at = advance();
        bool mutableAlloc = false;
        if (match(TokenType::CARET)) mutableAlloc = true;
        else if (match(TokenType::STAR)) mutableAlloc = false;
        if (check(TokenType::IDENTIFIER) && (peekNextIs(TokenType::STAR) || peekNextIs(TokenType::CARET)) &&
            typeIs(current + 2, TokenType::L_PAREN)) {
            Token typeTok = advance();
            mutableAlloc = advance().type == TokenType::CARET;
            advance();  // '('
            std::vector<Expr> args = parseCallArgumentList();
            consume(TokenType::R_PAREN, "Expected ')' after 'alloc " + typeTok.lexeme + "*(' arguments");
            ExprCall call;
            call.callee = typeTok.lexeme;
            call.args = args;
            call.line = typeTok.line;
            call.column = typeTok.column;
            call.length = u16len(typeTok.lexeme);
            // A trailing `* count` is left to the caller's multiplicative loop.
            return make(arena, ExprAlloc{make(arena, call), at.line, at.column, u16len(at.lexeme), mutableAlloc});
        }
        Expr operand = parseUnary();
        if (ExprIndex* index = as<ExprIndex>(operand); index && is<ExprIdentifier>(index->target)) {
            std::string type = cast<ExprIdentifier>(index->target).name;
            fail("'alloc " + type + "[…]' was removed at line " + str(at.line) +
                 "; a buffer is a repeated allocation - write 'alloc " + type + "*() * <count>'");
        }
        return make(arena, ExprAlloc{operand, at.line, at.column, u16len(at.lexeme), mutableAlloc});
    }
    // `take <expr>` - transfer ownership out of the operand.
    if (check(TokenType::TAKE)) {
        Token at = advance();
        Expr operand = parseUnary();
        return make(arena, ExprIsolated{operand, at.line, at.column, u16len(at.lexeme), OwnershipOp::TAKE});
    }
    // `*ptr` - pointer or smart-pointer dereference.
    if (check(TokenType::STAR)) {
        Token at = advance();
        Expr operand = parseUnary();
        return make(arena, ExprDeref{operand, at.line, at.column, u16len(at.lexeme)});
    }
    // `lend x` - ownership the callee gives back (§13).
    if (check(TokenType::LEND) && peekNextIs(TokenType::IDENTIFIER)) {
        Token at = advance();
        Expr operand = parseUnary();
        return make(arena, ExprIsolated{operand, at.line, at.column, u16len(at.lexeme), OwnershipOp::LEND});
    }
    // `delay <ms>` - suspend the current task for that many milliseconds.
    if (check(TokenType::DELAY)) {
        Token at = advance();
        Expr operand = parseUnary();
        ExprCall call;
        call.callee = "__delay";
        call.args = {operand};
        call.line = at.line;
        call.column = at.column;
        call.length = u16len(at.lexeme);
        return make(arena, call);
    }
    // `await task` - suspend until the task completes.
    if (check(TokenType::AWAIT)) {
        Token at = advance();
        Expr operand = parseUnary();
        return make(arena, ExprAwait{operand, at.line, at.column, u16len(at.lexeme)});
    }
    // `inject Type` is an ordinary expression.
    if (check(TokenType::INJECT)) {
        Token at = advance();
        std::string typeName = consume(TokenType::IDENTIFIER, "Expected type name after 'inject'").lexeme;
        // Chain into parsePostfix so `inject Config.get()` works.
        return parsePostfix(make(arena, ExprInject{typeName, at.line, at.column, u16len(at.lexeme)}));
    }
    if (check(TokenType::LAZY)) {
        fail("'lazy' modifies a binding at line " + str(peek().line) +
             "; write 'lazy fin name = expression' or 'lazy let name = expression'");
    }
    if (check(TokenType::BANG) && peekNextIs(TokenType::L_BRACKET)) {
        fail("'![...]' set syntax was removed at line " + str(peek().line) + "; use a Set<T> context with '[...]'");
    }
    if (check(TokenType::PLUS_PLUS) || check(TokenType::MINUS_MINUS)) {
        Token op = advance();
        Expr target = parseUnary();
        return make(arena, ExprIncDec{target, op.type, true, op.line, op.column, u16len(op.lexeme)});
    }
    if (check(TokenType::BANG) || check(TokenType::MINUS) || check(TokenType::TILDE)) {
        Token op = advance();
        Expr operand = parseUnary();
        return make(arena, ExprUnary{op.type, operand, op.line, op.column, u16len(op.lexeme)});
    }
    return parsePostfix();
}

/**
 * True when the tokens at [index] open a `when` arm of leading-dot patterns:
 * `.Startup ->`, `.Rect(w, h) ->`, or several, `.North, .South ->`.
 */
bool Parser::opensWhenArmAt(int32_t index) {
    int32_t at = index;
    while (true) {
        if (!typeIs(at, TokenType::DOT)) return false;
        if (!typeIs(at + 1, TokenType::IDENTIFIER)) return false;
        at += 2;
        if (typeIs(at, TokenType::L_PAREN)) {
            int depth = 0;
            while (at < (int32_t)tokens.size()) {
                TokenType t = tokens[(size_t)at].type;
                if (t == TokenType::L_PAREN) {
                    depth++;
                } else if (t == TokenType::R_PAREN) {
                    if (--depth == 0) break;
                } else if (t == TokenType::NEWLINE || t == TokenType::EOF_) {
                    return false;
                }
                at++;
            }
            at++;
        }
        if (typeIs(at, TokenType::ARROW)) return true;
        if (typeIs(at, TokenType::COMMA)) {
            at++;
            // `.Start, else ->` - the group joins the fallback.
            if (typeIs(at, TokenType::ELSE)) return typeIs(at + 1, TokenType::ARROW);
            continue;
        }
        return false;
    }
}

}  // namespace azora
