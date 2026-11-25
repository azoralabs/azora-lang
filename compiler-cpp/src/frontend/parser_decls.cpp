// Parser.kt lines 1000-1340: top-level declarations, decorators, `annot`.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"

namespace azora {

TopLevel Parser::parseTopLevel() {
    std::vector<Annotation> annotations = parseAnnotations();
    // Optional visibility modifier; public unless `confined` narrows it to the package.
    Visibility visibility = parseVisibility();
    Token start = peek();

    // `fin name[: T] = init` and friends, shared by several branches below.
    struct Binding {
        std::string name;
        TypeRef type;
        Expr init;
    };
    auto parseBinding = [&]() -> Binding {
        std::string name = consume(TokenType::IDENTIFIER, "Expected name").lexeme;
        TypeRef type;
        if (match(TokenType::COLON)) type = parseTypeName();
        consume(TokenType::EQUAL, "Expected '='");
        Expr init = parseInitializer(type);
        consumeNewline();
        return Binding{name, type, init};
    };

    // Compile-time list bindings (`let X: [Type]`, `inline fin ranks: [Int]`)
    // must be recognised before the general `inline`/`fin`/`let` handlers.
    if (isTypeListAliasAhead()) return parseTypeListAlias();
    if (isTypeListBindingAhead()) return parseTypeListBinding();
    if (check(TokenType::UNSAFE) && (peekNextIs(TokenType::FUNC) || isAsyncFuncAt(current + 1))) {
        advance();
        if (isAsyncFuncAt(current)) {
            return make(arena, TopLevelFunc{parseFuncDecl({.annotations = annotations, .isTask = true, .isUnsafe = true,
                                                           .visibility = visibility})});
        }
        return make(arena, TopLevelFunc{parseFuncDecl({.annotations = annotations, .isUnsafe = true, .visibility = visibility})});
    }
    if (check(TokenType::FUNC)) return funcOrExtension(parseFuncDecl({.annotations = annotations, .visibility = visibility}));
    if (isAsyncFuncAt(current)) {
        return funcOrExtension(parseFuncDecl({.annotations = annotations, .isTask = true, .visibility = visibility}));
    }
    // `react func` / `react async func` - a reactive owner. `react` qualifies the
    // declaration rather than replacing `func`, exactly as `async` does.
    if (check(TokenType::REACT)) {
        advance();
        bool asyncProp = isAsyncPropAt(current);
        if (asyncProp) advance();
        if (asyncProp || check(TokenType::PROP)) return parsePropAsFin(annotations, visibility, true, asyncProp);
        bool task = isAsyncFuncAt(current);
        return funcOrExtension(
            parseFuncDecl({.annotations = annotations, .isTask = task, .isReactive = true, .visibility = visibility}));
    }
    if (check(TokenType::INLINE)) return parseTopLevelInline(annotations);
    if (check(TokenType::DEEPINLINE)) return parseTopLevelDeepInline();
    if (check(TokenType::TEST)) return parseTestDecl(annotations);
    if (check(TokenType::ANNOT)) return parseDeco(annotations);
    if (check(TokenType::PACK)) return parsePack(annotations, visibility);
    if (check(TokenType::ENUM)) return parseEnumDecl(annotations);
    if (check(TokenType::ERROR)) return parseFailDecl(annotations);
    if (check(TokenType::DERIVE)) return parseDeriveDecl();
    if (check(TokenType::IMPL)) return parseImpl(false, annotations);
    if (check(TokenType::BRIDGE) && peekNextIs(TokenType::IMPL)) {
        advance();
        return parseImpl(true, annotations);
    }
    // `bridge <decl>` marks a declaration as compiler-provided.
    if (check(TokenType::BRIDGE) && peekNextIs(TokenType::PACK)) {
        advance();
        return parsePack(annotations, visibility, true);
    }
    if (check(TokenType::BRIDGE) && peekNextIs(TokenType::ENUM)) {
        advance();
        return parseEnumDecl(annotations);
    }
    if (check(TokenType::BRIDGE) && peekNextIs(TokenType::ANNOT)) {
        advance();
        return parseDeco(annotations, true);
    }
    if (check(TokenType::BRIDGE) && peekNextIs(TokenType::SPEC)) {
        advance();
        return withAnnotations(parseSpec(true), annotations);
    }
    // `direct spec Number { prop rank: Int }` - a spec whose members belong to
    // the type rather than to its values.
    if (check(TokenType::DIRECT) && peekNextIs(TokenType::SPEC)) {
        advance();
        return withAnnotations(parseSpec(false, true), annotations);
    }
    if (check(TokenType::BRIDGE) && peekNextIs(TokenType::TYPEALIAS)) {
        advance();
        return parseTypeAlias(annotations);
    }
    if (check(TokenType::BRIDGE) && peekNextIs(TokenType::VARIANT)) {
        advance();
        return parseSlot(annotations);
    }
    if (check(TokenType::BRIDGE) && peekNextIs(TokenType::OPER)) {
        advance();
        return parseFreeOperator(annotations, visibility, true);
    }
    // `bridge fin size: Int` - a compiler-provided constant.
    if (check(TokenType::BRIDGE) && peekNextIs(TokenType::FIN)) {
        Token at = advance();  // 'bridge'
        advance();             // 'fin'
        std::string name = consumeIdentifierLike("Expected name after 'bridge fin'");
        consume(TokenType::COLON, "A 'bridge fin' must declare its type: write 'bridge fin " + name + ": Type'");
        TypeRef type = parseTypeName();
        consumeNewline();
        FuncDecl decl;
        decl.name = name;
        decl.returnType = explicitType(type);
        decl.isInline = false;
        decl.line = at.line;
        decl.column = at.column;
        decl.annotations = annotations;
        decl.visibility = visibility;
        decl.memberCallStyle = MemberCallStyle::PROPERTY;
        return make(arena, TopLevelFunc{decl});
    }
    if (check(TokenType::BRIDGE) && isBridgeReceiverPropAhead()) return parseBridgeReceiverProp(annotations);
    if (check(TokenType::BRIDGE) && isBridgeFuncAhead()) return parseBridgeFunc(annotations);
    if (check(TokenType::BRIDGE)) return parseBridge(annotations);
    if (check(TokenType::SOLO)) return parseSolo(visibility, annotations);
    if (check(TokenType::GRAPH)) return parseGraph();
    if (check(TokenType::THREADLOCAL)) return parseThreadLocal(visibility);
    if (check(TokenType::SPEC)) return withAnnotations(parseSpec(), annotations);
    if (isUnionDeclAhead()) return parsePack(annotations, visibility, false, true);
    if (check(TokenType::VARIANT)) return parseSlot(annotations);
    if (check(TokenType::TYPEALIAS)) return parseTypeAlias(annotations);
    if (check(TokenType::MACRO)) return parseMeta();
    // `prop name: T = expr` at top level - desugars to a `fin`.
    if (check(TokenType::PROP)) return parsePropAsFin(annotations, visibility);
    if (isAsyncPropAt(current)) {
        advance();
        return parsePropAsFin(annotations, visibility, false, true);
    }
    // `oper+ Model&.(rhs: Model): Model { … }` - an operator declared beside the type.
    if (check(TokenType::OPER)) return parseFreeOperator(annotations, visibility);
    if (check(TokenType::IMPORT)) return parseUse();
    // `exposed import …` - the import travels on to whoever imports this module.
    if (check(TokenType::EXPOSED) && peekNextIs(TokenType::IMPORT)) {
        advance();  // 'exposed'
        return parseUse(true);
    }
    // `exposed if COND \n use …` - comptime-conditional.
    if (check(TokenType::EXPOSED) && peekNextIs(TokenType::IF)) {
        advance();  // 'exposed'
        advance();  // 'if'
        Expr cond = parseExpr();
        consume(TokenType::NEWLINE, "Expected a newline after 'exposed if <condition>'");
        return parseUse(true, cond);
    }
    if (check(TokenType::FIN)) {
        advance();
        Binding b = parseBinding();
        noteBridgeTargetConstant(b.name, b.init);
        return make(arena, TopLevelFinDecl{.name = b.name, .type = b.type, .initializer = b.init, .line = start.line,
                                           .column = start.column, .annotations = annotations, .visibility = visibility});
    }
    if (check(TokenType::VAR) || check(TokenType::VAL)) {
        bool valueMutable = advance().type == TokenType::VAR;
        Binding b = parseBinding();
        return make(arena, TopLevelVarDecl{.name = b.name, .type = b.type, .initializer = b.init, .line = start.line,
                                           .column = start.column, .annotations = annotations, .visibility = visibility,
                                           .valueMutable = valueMutable});
    }
    if (check(TokenType::LET)) {
        advance();
        Binding b = parseBinding();
        return make(arena, TopLevelLetDecl{.name = b.name, .type = b.type, .initializer = b.init, .line = start.line,
                                           .column = start.column, .annotations = annotations, .visibility = visibility});
    }
    // A compile-time loop generates declarations, and a type alias is one of them.
    if (check(TokenType::TYPEALIAS)) return parseTypeAlias(annotations);
    if (check(TokenType::PACK)) return parsePack(annotations, visibility);
    if (check(TokenType::ENUM)) return parseEnumDecl(annotations);
    if (check(TokenType::SPEC)) return withAnnotations(parseSpec(), annotations);
    if (check(TokenType::IMPL)) return parseImpl(false, annotations);
    fail("Expected 'func', 'fin', 'var', 'let', 'test', 'typealias', 'pack', 'enum', 'spec', 'impl', 'inline', or "
         "'deepinline' at top level, got '" +
         peek().lexeme + "' at line " + str(peek().line));
}

/** `spec.copy(annotations = annotations)`. */
TopLevel Parser::withAnnotations(TopLevelSpec* spec, const std::vector<Annotation>& annotations) {
    TopLevelSpec copy = *spec;
    copy.annotations = annotations;
    return make(arena, copy);
}

/**
 * Parses zero or more leading decorator applications: `@Name` or `@Name(args)`.
 */
std::vector<Annotation> Parser::parseAnnotations() {
    std::vector<Annotation> result;
    while (check(TokenType::AT)) {
        Token at = advance();
        std::vector<Annotation> row;
        bool isRow = parseParenthesizedList("decorator", [&] { row.push_back(parseOneAnnotation(at.line, at.column)); });
        if (isRow) {
            result.insert(result.end(), row.begin(), row.end());
            skipNewlines();
            continue;
        }
        result.push_back(parseOneAnnotation(at.line, at.column));
        skipNewlines();
    }
    return result;
}

/** One decorator body (name + optional `(args)`), with the leading `@` already consumed. */
Annotation Parser::parseOneAnnotation(int32_t line, int32_t column) {
    std::vector<std::string> parts{consume(TokenType::IDENTIFIER, "Expected decorator name").lexeme};
    while (match(TokenType::DOUBLE_COLON)) {
        parts.push_back(consume(TokenType::IDENTIFIER, "Expected decorator name after '::'").lexeme);
    }
    std::string name = parts.back();
    std::optional<std::string> qualifier;
    if (parts.size() > 1) qualifier = join(std::vector<std::string>(parts.begin(), parts.end() - 1), "::");
    // A decorator is a declaration and names one, so it is capitalised. A
    // *macro* is lowercase, so the case rule applies only where a decorator is
    // what is being written.
    if (!firstIsUpperCase(name) && !allowLowercaseAnnotation) {
        std::string prefix = qualifier ? "@" + *qualifier + "::" : "@";
        fail("Decorator names must start with an uppercase letter: use '" + prefix + capitalizeFirst(name) +
             "' at line " + str(line));
    }
    std::vector<Expr> args;
    std::vector<std::pair<std::string, Expr>> namedArgs;
    if (check(TokenType::L_PAREN)) {
        auto parsed = parseDecoratorArguments();
        args = parsed.first;
        namedArgs = parsed.second;
    }
    return Annotation{name, args, line, column, namedArgs, qualifier};
}

std::pair<std::vector<Expr>, std::vector<std::pair<std::string, Expr>>> Parser::parseDecoratorArguments() {
    consume(TokenType::L_PAREN, "Expected '('");
    std::vector<Expr> positional;
    std::vector<std::pair<std::string, Expr>> named;
    if (!check(TokenType::R_PAREN)) {
        do {
            // `.Native` shorthand is retained for built-in decorator arguments.
            // A case with a payload, `.Every(5.seconds)`, is an ordinary value.
            if (check(TokenType::DOT) && peekNextIs(TokenType::IDENTIFIER) && !casePayloadAhead()) {
                Token dot = advance();
                std::string member = advance().lexeme;
                positional.push_back(make(arena, ExprIdentifier{"." + member, dot.line, dot.column}));
            } else if (check(TokenType::IDENTIFIER) && (peekNextIs(TokenType::COLON) || peekNextIs(TokenType::EQUAL))) {
                std::string key = advance().lexeme;
                advance();  // ':' or '='
                // A named argument takes the same shorthand its positional twin does.
                if (check(TokenType::DOT) && peekNextIs(TokenType::IDENTIFIER) && !casePayloadAhead()) {
                    Token dot = advance();
                    std::string member = advance().lexeme;
                    named.emplace_back(key, make(arena, ExprIdentifier{"." + member, dot.line, dot.column}));
                } else {
                    Expr value = parseExpr();
                    named.emplace_back(key, value);
                }
            } else {
                positional.push_back(parseExpr());
            }
        } while (match(TokenType::COMMA));
    }
    consume(TokenType::R_PAREN, "Expected ')' after decorator arguments");
    return {positional, named};
}

/** True at `.Name(` - a case written with its payload. */
bool Parser::casePayloadAhead() { return typeIs(current + 2, TokenType::L_PAREN); }

OrderedSet<DecoTarget> Parser::parseDecoTargets() {
    OrderedSet<DecoTarget> targets;
    // `for .*` is every target, which is what a decorator with no `for`
    // already means: an empty set.
    if (check(TokenType::DOT) && peekNextIs(TokenType::STAR)) {
        advance();
        advance();
        return {};
    }
    auto parseOne = [&]() {
        consume(TokenType::DOT, "Expected '.' before decorator target");
        Token token = advance();
        std::optional<DecoTarget> target;
        for (int i = 0; i <= (int)DecoTarget::Oper; i++) {
            if (enumName((DecoTarget)i) == token.lexeme) {
                target = (DecoTarget)i;
                break;
            }
        }
        if (!target) fail("Unknown decorator target '." + token.lexeme + "' at line " + str(token.line));
        if (!targets.add(*target)) {
            fail(std::string("Duplicate decorator target '.") + enumName(*target) + "' at line " + str(token.line));
        }
    };
    if (!parseParenthesizedList("decorator target", parseOne)) parseOne();
    return targets;
}

DecoratorBinding Parser::parseDecoratorBinding() {
    std::string name = consume(TokenType::IDENTIFIER, "Expected spec or decorator name after 'binds'").lexeme;
    std::vector<TypeRef> typeArgs = parseGenericTypeArgsIfPresent();
    OrderedSet<DecoTarget> targets;
    if (match(TokenType::FOR)) targets = parseDecoTargets();
    return DecoratorBinding{name, typeArgs, targets};
}

/** Parses every `deco` target/binding combination into a normalized declaration. */
TopLevelDeco* Parser::parseDeco(const std::vector<Annotation>& annotations, bool isBridge /*= false*/) {
    Token start = peek();
    consume(TokenType::ANNOT, "Expected 'annot'");
    // A decorator is declared as it is written: the `@` is what tells a reader
    // which of the two a name is.
    if (!check(TokenType::AT)) {
        Token written = peek();
        fail("Decorator declarations carry their '@': use 'annot @" + written.lexeme + "' at line " + str(written.line));
    }
    advance();
    std::string name = consume(TokenType::IDENTIFIER, "Expected decorator name after '@'").lexeme;
    if (!firstIsUpperCase(name)) {
        fail("Decorator names must start with an uppercase letter: use '" + capitalizeFirst(name) + "' at line " +
             str(start.line));
    }
    OrderedSet<DecoTarget> targets;
    if (match(TokenType::FOR)) targets = parseDecoTargets();
    std::vector<DecoratorBinding> bindings;
    if (match(TokenType::BINDS)) {
        bool isRow = parseParenthesizedList("decorator binding", [&] { bindings.push_back(parseDecoratorBinding()); });
        if (!isRow) bindings.push_back(parseDecoratorBinding());
    }

    if (!check(TokenType::L_BRACE)) {
        consumeNewline();
        return make(arena, TopLevelDeco{name, {}, start.line, start.column, annotations, targets, bindings, isBridge});
    }
    consume(TokenType::L_BRACE, "Expected '{' after decorator declaration");
    skipNewlines();
    std::vector<PackField> fields;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        fields.push_back(parsePackField({.requireFin = true}));
        match(TokenType::COMMA);
        skipNewlines();
    }
    consume(TokenType::R_BRACE, "Expected '}' after deco fields");
    consumeNewline();
    return make(arena, TopLevelDeco{name, fields, start.line, start.column, annotations, targets, bindings, isBridge});
}

TopLevel Parser::parseTopLevelInline(const std::vector<Annotation>& annotations /*= {}*/) {
    std::optional<TokenType> next;
    if (std::optional<Token> n = tokenCopy(peekNext())) next = n->type;
    if (next == TokenType::FUNC) {
        // The decorators written above `inline func` belong to the function.
        advance();
        return make(arena, TopLevelFunc{parseFuncDecl({.isInline = true, .annotations = annotations})});
    }
    if (next == TokenType::FOR) return parseTopLevelInlineFor();
    if (next == TokenType::L_BRACE) return parseTopLevelInlineBlock();
    if (next == TokenType::IF) return parseTopLevelInlineIf();
    if (next == TokenType::ASSERT) return parseTopLevelInlineAssert();
    if (next == TokenType::TRACE) return parseTopLevelInlineTrace();
    if (next == TokenType::FIN) {
        Token start = peek();
        advance();
        advance();
        std::string name = consume(TokenType::IDENTIFIER, "Expected name").lexeme;
        TypeRef type;
        if (match(TokenType::COLON)) type = parseTypeName();
        consume(TokenType::EQUAL, "Expected '='");
        Expr init = parseInitializer(type);
        consumeNewline();
        noteBridgeTargetConstant(name, init);
        return make(arena, TopLevelInlineFin{name, init, start.line, start.column, inlineFinType(type)});
    }
    if (next == TokenType::LET) {
        Token start = peek();
        advance();
        advance();
        std::string name = consume(TokenType::IDENTIFIER, "Expected name").lexeme;
        TypeRef type;
        if (match(TokenType::COLON)) type = parseTypeName();
        consume(TokenType::EQUAL, "Expected '='");
        Expr init = parseInitializer(type);
        consumeNewline();
        return make(arena, TopLevelInlineLet{name, init, start.line, start.column});
    }
    if (next == TokenType::VAR || next == TokenType::VAL) {
        Token start = peek();
        advance();
        bool valueMutable = advance().type == TokenType::VAR;
        std::string name = consume(TokenType::IDENTIFIER, "Expected name").lexeme;
        TypeRef type;
        if (match(TokenType::COLON)) type = parseTypeName();
        consume(TokenType::EQUAL, "Expected '='");
        Expr init = parseInitializer(type);
        consumeNewline();
        return make(arena, TopLevelInlineVar{name, init, start.line, start.column, valueMutable});
    }
    if (next == TokenType::IDENTIFIER) {
        Token start = peek();
        advance();
        std::string name = consume(TokenType::IDENTIFIER, "Expected name").lexeme;
        consume(TokenType::EQUAL, "Expected '='");
        Expr value = parseExpr();
        consumeNewline();
        return make(arena, TopLevelInlineAssignment{name, value, start.line, start.column});
    }
    fail("Expected 'func', '{', 'scope', 'if', 'assert', 'trace', 'fin', 'var', 'let', or identifier after 'inline' at line " +
         str(peek().line));
}

}  // namespace azora
