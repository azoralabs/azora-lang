// Parser.kt lines 9415-9729: compile-time type property parameters, bodies,
// expressions and conditions.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/kotlin_math.h"
#include "base/text.h"

namespace azora {

/** `<T, U>` / `<...T>` - the type parameters of a `deepinline prop`. */
std::vector<TypeFunctionParam> Parser::parseTypePropParams(const std::string& owner) {
    std::vector<TypeFunctionParam> params;
    if (!match(TokenType::LESS)) {
        fail("'deepinline prop " + owner + "' must declare type parameters, e.g. '<...T>', at line " + str(peek().line));
    }
    do {
        bool variadic = match(TokenType::ELLIPSIS);
        std::string param = consume(TokenType::IDENTIFIER, "Expected a type-parameter name").lexeme;
        if (check(TokenType::ELLIPSIS)) {
            fail("Variadic type parameters use the prefix form '<..." + param + ">', not '<" + param + "...>', at line " +
                 str(peek().line));
        }
        params.push_back(TypeFunctionParam{param, variadic});
    } while (matchTypeArgumentComma());
    consumeTypeArgumentClose("Expected '>' to close the type parameters of '" + owner + "'");
    OrderedMap<std::string, int> counts;
    for (const TypeFunctionParam& p : params) counts.getOrPut(p.name, 0)++;
    for (const auto& [name, count] : counts.entries) {
        if (count > 1) fail("Duplicate type parameter '" + name + "' on 'deepinline prop " + owner + "'");
    }
    int variadicCount = 0;
    bool variadicNotLast = false;
    for (size_t i = 0; i < params.size(); i++) {
        if (params[i].variadic) {
            variadicCount++;
            if (i + 1 < params.size()) variadicNotLast = true;
        }
    }
    if (variadicCount > 1 || variadicNotLast) {
        fail("'deepinline prop " + owner + "' may have one variadic type parameter, and it must be last");
    }
    return params;
}

std::vector<TypeFunctionStmt> Parser::parseTypeFunctionBlock() {
    std::vector<TypeFunctionStmt> body;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        body.push_back(parseTypeFunctionStmt());
        skipNewlines();
    }
    return body;
}

TypeFunctionStmt Parser::parseTypeFunctionStmt() {
    if (match(TokenType::RETURN)) {
        TypeFunctionExpr value = parseTypeFunctionExpr();
        consumeNewline();
        return make(arena, TypeFunctionStmtReturn{value});
    }
    if (check(TokenType::LET) || check(TokenType::VAR) || check(TokenType::VAL) || check(TokenType::FIN)) {
        // Only `var` and `val` rebind; `let`/`fin` fix the binding.
        bool mutableBinding = check(TokenType::VAR) || check(TokenType::VAL);
        advance();
        std::string name = consume(TokenType::IDENTIFIER, "Expected type binding name").lexeme;
        consumeTypeAnnotationInTypeProp("binding '" + name + "'");
        consume(TokenType::EQUAL, "Expected '=' in type binding");
        TypeFunctionExpr value = parseTypeFunctionExpr();
        consumeNewline();
        return make(arena, TypeFunctionStmtBinding{name, value, mutableBinding});
    }
    if (match(TokenType::FOR)) {
        std::string name = consume(TokenType::IDENTIFIER, "Expected type loop variable").lexeme;
        consumeTypeAnnotationInTypeProp("loop variable '" + name + "'");
        consume(TokenType::IN, "Expected 'in' in type loop");
        std::string pack = consume(TokenType::IDENTIFIER, "Expected variadic type parameter").lexeme;
        consume(TokenType::L_BRACKET, "Expected '[' after variadic type parameter");
        Token start = consume(TokenType::INT_LITERAL, "Expected type-pack start index");
        int32_t startIndex = ktoInt(std::get<int64_t>(std::get<NumericLiteral>(start.literal).value));
        consume(TokenType::ELLIPSIS, "Expected '...' after type-pack start index");
        consume(TokenType::R_BRACKET, "Expected ']' after type-pack slice");
        consume(TokenType::L_BRACE, "Expected '{' before type loop body");
        skipNewlines();
        std::vector<TypeFunctionStmt> loopBody = parseTypeFunctionBlock();
        consume(TokenType::R_BRACE, "Expected '}' after type loop body");
        consumeNewline();
        return make(arena, TypeFunctionStmtForEach{name, pack, startIndex, loopBody});
    }
    if (match(TokenType::IF)) {
        TypeFunctionCondition condition = parseTypeFunctionCondition();
        consume(TokenType::L_BRACE, "Expected '{' after the condition of a type 'if'");
        skipNewlines();
        std::vector<TypeFunctionStmt> thenBody = parseTypeFunctionBlock();
        consume(TokenType::R_BRACE, "Expected '}' after a type 'if' body");
        skipNewlines();
        std::vector<TypeFunctionStmt> elseBody;
        if (match(TokenType::ELSE)) {
            consume(TokenType::L_BRACE, "Expected '{' after 'else'");
            skipNewlines();
            elseBody = parseTypeFunctionBlock();
            consume(TokenType::R_BRACE, "Expected '}' after an 'else' body");
        }
        consumeNewline();
        return make(arena, TypeFunctionStmtIf{condition, thenBody, elseBody});
    }
    if (check(TokenType::IDENTIFIER) && peekNextIs(TokenType::EQUAL)) {
        std::string name = advance().lexeme;
        advance();
        TypeFunctionExpr value = parseTypeFunctionExpr();
        consumeNewline();
        return make(arena, TypeFunctionStmtAssignment{name, value});
    }
    fail("Expected a 'deepinline prop' statement at line " + str(peek().line) + ", got '" + peek().lexeme + "'");
}

/** Consumes the mandatory `: Type` annotation on a `deepinline prop` binding. */
void Parser::consumeTypeAnnotationInTypeProp(const std::string& what) {
    if (!match(TokenType::COLON)) fail("The " + what + " needs an explicit ': Type' annotation at line " + str(peek().line));
    Token type = consume(TokenType::IDENTIFIER, "Expected 'Type' after ':'");
    if (type.lexeme != "Type") {
        fail("The " + what + " must be declared ': Type' - a type property binds types, not values, at line " +
             str(type.line));
    }
}

/** True when the condition here is a conjunction of `Bool` parameters rather than a type comparison. */
bool Parser::isValueFlagConditionAhead() {
    int32_t i = current;
    if (typeIs(i, TokenType::BANG)) i++;
    if (!typeIs(i, TokenType::IDENTIFIER)) return false;
    return typeIs(i + 1, TokenType::AND_AND) || typeIs(i + 1, TokenType::FAT_ARROW);
}

TypeFunctionExpr Parser::parseTypeFunctionExpr() {
    // `when { c => T; d => U; else => V }` - the arms are tried in order.
    if (match(TokenType::WHEN)) {
        // `when b { true => T; false => U }` - a scrutinee named before the brace.
        std::optional<std::pair<std::string, TypeFunctionExpr>> scrutinee;
        if (!check(TokenType::L_BRACE)) {
            std::string name = consumeIdentifierLike("Expected a name or '{' after 'when'");
            TypeFunctionExpr call = parseTypePropCall(name);
            scrutinee = std::make_pair(name, call ? call : TypeFunctionExpr(make(arena, TypeFunctionExprReference{name})));
        }
        consume(TokenType::L_BRACE, "Expected '{' after 'when' in a type computation");
        skipNewlines();
        struct Arm {
            TypeFunctionCondition condition;
            TypeFunctionExpr value;
            std::optional<bool> matched;
        };
        std::vector<Arm> arms;
        TypeFunctionExpr fallback;
        while (!check(TokenType::R_BRACE)) {
            if (match(TokenType::ELSE)) {
                consume(TokenType::FAT_ARROW, "Expected '=>' after 'else'");
                fallback = parseTypeFunctionExpr();
            } else {
                std::optional<bool> matched;
                if (scrutinee) matched = booleanArmLiteral();
                TypeFunctionCondition condition =
                    scrutinee ? typeFunctionArmCondition(*scrutinee, matched) : parseTypeFunctionCondition();
                consume(TokenType::FAT_ARROW, "Expected '=>' after a 'when' condition");
                TypeFunctionExpr value = parseTypeFunctionExpr();
                arms.push_back(Arm{condition, value, matched});
            }
            skipNewlines();
        }
        consume(TokenType::R_BRACE, "Expected '}' after 'when'");
        // `true` and `false` between them leave nothing over.
        bool sawTrue = false;
        bool sawFalse = false;
        for (const Arm& arm : arms) {
            if (arm.matched == true) sawTrue = true;
            if (arm.matched == false) sawFalse = true;
        }
        bool exhaustiveBooleans = !fallback && sawTrue && sawFalse;
        TypeFunctionExpr last;
        if (fallback) last = fallback;
        else if (exhaustiveBooleans && !arms.empty()) last = arms.back().value;
        else fail("A 'when' in a type computation must end with 'else' at line " + str(peek().line));
        size_t tested = exhaustiveBooleans ? arms.size() - 1 : arms.size();
        // Folded from the last arm outwards, so the first written arm is the outermost test.
        TypeFunctionExpr rest = last;
        for (size_t i = tested; i > 0; i--) {
            rest = make(arena, TypeFunctionExprConditional{arms[i - 1].condition, arms[i - 1].value, rest});
        }
        return rest;
    }
    if (match(TokenType::IF)) {
        TypeFunctionCondition condition = parseTypeFunctionCondition();
        consume(TokenType::L_BRACE, "Expected '{' after type condition");
        skipNewlines();
        TypeFunctionExpr thenValue = parseTypeFunctionExpr();
        skipNewlines();
        consume(TokenType::R_BRACE, "Expected '}' after type result");
        skipNewlines();
        consume(TokenType::ELSE, "Expected 'else' in conditional type expression");
        consume(TokenType::L_BRACE, "Expected '{' after 'else'");
        skipNewlines();
        TypeFunctionExpr elseValue = parseTypeFunctionExpr();
        skipNewlines();
        consume(TokenType::R_BRACE, "Expected '}' after alternative type result");
        return make(arena, TypeFunctionExprConditional{condition, thenValue, elseValue});
    }
    std::string name = consume(TokenType::IDENTIFIER, "Expected a type value").lexeme;
    if (TypeFunctionExpr call = parseTypePropCall(name)) {
        if (match(TokenType::QMARK)) return make(arena, TypeFunctionExprNullable{call});
        return call;
    }
    if (match(TokenType::DOT)) {
        Token index = consume(TokenType::INT_LITERAL, "Expected type-pack index after '.'");
        TypeFunctionExpr element = make(
            arena, TypeFunctionExprPackElement{name, ktoInt(std::get<int64_t>(std::get<NumericLiteral>(index.literal).value))});
        if (match(TokenType::QMARK)) return make(arena, TypeFunctionExprNullable{element});
        return element;
    }
    // `T?` - the computed type is the nullable form of another.
    if (match(TokenType::QMARK)) {
        return make(arena, TypeFunctionExprNullable{make(arena, TypeFunctionExprReference{name})});
    }
    return make(arena, TypeFunctionExprReference{name});
}

/**
 * `Name<A, B>` - one type property calling another. Returns none when [name]
 * is not followed by type arguments.
 */
TypeFunctionExpr Parser::parseTypePropCall(const std::string& name) {
    if (!match(TokenType::LESS)) return nullptr;
    std::vector<TypeFunctionExpr> args;
    if (!check(TokenType::GREATER)) {
        do {
            args.push_back(parseTypeFunctionExpr());
        } while (matchTypeArgumentComma());
    }
    consumeTypeArgumentClose("Expected '>' to close the arguments of '" + name + "'");
    std::string callName = typeFunctionNamespacePrefix.empty() ? name : typeFunctionNamespacePrefix + "__" + name;
    return make(arena, TypeFunctionExprCall{callName, args});
}

/** The boolean an arm of a scrutinee `when` matches, or none if it names a type. */
std::optional<bool> Parser::booleanArmLiteral() {
    if (match(TokenType::TRUE_)) return true;
    if (match(TokenType::FALSE_)) return false;
    return std::nullopt;
}

/** One arm of `when <scrutinee> { … }`, as the condition it stands for. */
TypeFunctionCondition Parser::typeFunctionArmCondition(const std::pair<std::string, TypeFunctionExpr>& scrutinee,
                                                       std::optional<bool> matched) {
    const std::string& name = scrutinee.first;
    const TypeFunctionExpr& expression = scrutinee.second;
    if (matched) {
        return TypeFunctionCondition{expression, TokenType::EQUAL_EQUAL, expression, false, {name}, {*matched}};
    }
    std::string armName =
        consume(TokenType::IDENTIFIER, "Expected a type, 'true', 'false', or 'else' in a 'when' arm").lexeme;
    TypeFunctionExpr arm = parseTypePropCall(armName);
    if (!arm) arm = make(arena, TypeFunctionExprReference{armName});
    TypeFunctionExpr right = match(TokenType::QMARK) ? TypeFunctionExpr(make(arena, TypeFunctionExprNullable{arm})) : arm;
    return TypeFunctionCondition{expression, TokenType::EQUAL_EQUAL, right, false};
}

TypeFunctionCondition Parser::parseTypeFunctionCondition() {
    // `hasDefault`, `!hasDefault`, `a && b` - the type function's own `Bool` parameters.
    if (isValueFlagConditionAhead()) {
        std::vector<std::string> names;
        std::vector<bool> expected;
        do {
            bool negated = match(TokenType::BANG);
            names.push_back(consume(TokenType::IDENTIFIER, "Expected a 'Bool' parameter in a type condition").lexeme);
            expected.push_back(!negated);
        } while (match(TokenType::AND_AND));
        TypeFunctionExpr left = make(arena, TypeFunctionExprReference{first(names)});
        TypeFunctionExpr right = make(arena, TypeFunctionExprReference{first(names)});
        return TypeFunctionCondition{left, TokenType::EQUAL_EQUAL, right, false, names, expected};
    }
    auto operand = [&]() -> std::pair<TypeFunctionExpr, bool> {
        std::string name = consume(TokenType::IDENTIFIER, "Expected a type value in type comparison").lexeme;
        TypeFunctionExpr expression = parseTypePropCall(name);
        if (!expression) expression = make(arena, TypeFunctionExprReference{name});
        bool rank = false;
        if (match(TokenType::DOT) || match(TokenType::DOUBLE_COLON)) {
            Token member = consume(TokenType::IDENTIFIER, "Expected 'rank' in type comparison");
            if (member.lexeme != "rank") {
                fail("Only '.rank'/'::rank' is supported in ranked type comparisons at line " + str(member.line));
            }
            rank = true;
        }
        return {expression, rank};
    };
    std::pair<TypeFunctionExpr, bool> left = operand();
    TokenType op;
    if (match(TokenType::GREATER_EQUAL)) op = TokenType::GREATER_EQUAL;
    else if (match(TokenType::LESS_EQUAL)) op = TokenType::LESS_EQUAL;
    else if (match(TokenType::GREATER)) op = TokenType::GREATER;
    else if (match(TokenType::LESS)) op = TokenType::LESS;
    else if (match(TokenType::EQUAL_EQUAL)) op = TokenType::EQUAL_EQUAL;
    else if (match(TokenType::BANG_EQUAL)) op = TokenType::BANG_EQUAL;
    else fail("Expected comparison operator in type condition at line " + str(peek().line));
    std::pair<TypeFunctionExpr, bool> right = operand();
    if (left.second != right.second) fail("Both sides of a type comparison must use '.rank', or neither side may use it");
    return TypeFunctionCondition{left.first, op, right.first, left.second};
}

}  // namespace azora
