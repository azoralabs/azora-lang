// Parser.kt lines 2034-2985: test scopes and tests, `pack`/`union`, derives,
// variadic field templates, compile-time field forms, `inline for` members,
// signature fragments, operator overload suffixes and pack fields.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"
#include "frontend/lexer.h"

namespace azora {

namespace {

std::vector<Token> lexWithoutEof(const std::string& text) {
    std::vector<Token> tokens = Lexer(text).tokenize();
    tokens.pop_back();
    return tokens;
}

/** Kotlin `removeSurrounding("\"")`. */
std::string removeSurroundingQuotes(const std::string& s) {
    if (s.size() >= 2 && s.front() == '"' && s.back() == '"') return s.substr(1, s.size() - 2);
    return s;
}

}  // namespace

bool Parser::isTestScopeAhead() {
    int32_t i = indexAfterAnnotations(current);
    while (typeIs(i, TokenType::EXPOSED) || typeIs(i, TokenType::PROTECTED) || typeIs(i, TokenType::CONFINED)) i++;
    // `test scope { … }`, and the `scope test` it replaces.
    if (typeIs(i, TokenType::TEST)) return typeIs(i + 1, TokenType::SCOPE);
    return false;
}

/**
 * `[exposed|confined] test scope { … }` - a scope of test-only declarations.
 * The members are returned flattened: a test scope is a visibility rule, not
 * a namespace, so it does not mangle the names it contains.
 */
std::vector<TopLevel> Parser::parseTestScope() {
    parseAnnotations();
    // A bare `scope test` is file-local, so the default reach is CONFINED.
    Visibility visibility;
    if (match(TokenType::PROTECTED)) {
        visibility = Visibility{Reach::PROTECTED};
    } else if (match(TokenType::CONFINED)) {
        visibility = Visibility{Reach::CONFINED};
    } else if (match(TokenType::EXPOSED)) {
        if (match(TokenType::PROTECTED)) visibility = Visibility{Reach::PROTECTED, true};
        else if (match(TokenType::CONFINED)) visibility = Visibility{Reach::CONFINED, true};
        else visibility = Visibility{Reach::PUBLIC, true};
    } else {
        visibility = Visibility{Reach::CONFINED};
    }
    consume(TokenType::TEST, "Expected 'test'");
    consume(TokenType::SCOPE, "Expected 'scope' after 'test'");
    consume(TokenType::L_BRACE, "Expected '{' to open a test scope");
    skipNewlines();
    std::vector<TopLevel> body;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        if (isTestScopeAhead()) {
            fail("A 'test scope' cannot nest inside another at line " + str(peek().line) +
                 " - its declarations are already test-only");
        } else if (isScopeNamespaceAhead()) {
            std::vector<TopLevel> inner = parseScopeNamespace();
            body.insert(body.end(), inner.begin(), inner.end());
        } else if (isScopeBlockAhead()) {
            std::vector<TopLevel> inner = parseScopeBlock();
            body.insert(body.end(), inner.begin(), inner.end());
        } else if (isTypePropAhead()) {
            parseTypeProp();
        } else {
            body.push_back(parseTopLevel());
        }
        skipNewlines();
    }
    consume(TokenType::R_BRACE, "Expected '}' to close a test scope");
    consumeNewline();
    for (const TopLevel& item : body) {
        std::optional<std::string> name = declaredTopLevelName(item);
        if (!name) continue;
        testScopeMembers.set(*name, visibility);
    }
    return body;
}

TopLevelTest* Parser::parseTestDecl(const std::vector<Annotation>& annotations /*= {}*/) {
    Token start = peek();
    consume(TokenType::TEST, "Expected 'test'");
    TestMethod method = TestMethod::This;
    if (match(TokenType::DOT)) {
        Token token = consume(TokenType::IDENTIFIER, "Expected 'This' or 'All' after 'test .'");
        if (token.lexeme == "This") method = TestMethod::This;
        else if (token.lexeme == "All") method = TestMethod::All;
        else fail("Unknown test method '." + token.lexeme + "' at line " + str(token.line) + "; expected '.This' or '.All'");
    }
    std::string name = literalString(consume(TokenType::STRING_LITERAL, "Expected test name string"));
    std::vector<Stmt> body;
    if (match(TokenType::L_BRACE)) {
        skipNewlines();
        body = parseBlock();
        consume(TokenType::R_BRACE, "Expected '}' after test body");
    } else {
        if (method != TestMethod::All) {
            fail("Expected '{' after test name; only 'test .All' may omit its body at line " + str(start.line));
        }
    }
    consumeNewline();
    return make(arena, TopLevelTest{name, body, start.line, start.column, annotations, method});
}

/**
 * True when a union declaration begins here: `unsafe union Name {`. A bare
 * `union Name {` is matched too so it can be rejected with an explanation.
 */
bool Parser::isUnionDeclAhead() {
    int32_t i = current;
    if (typeIs(i, TokenType::UNSAFE)) i++;
    if (!typeIs(i, TokenType::UNION)) return false;
    if (!typeIs(i + 1, TokenType::IDENTIFIER)) return false;
    return typeIs(i + 2, TokenType::L_BRACE) || typeIs(i + 2, TokenType::LESS);
}

TopLevelPack* Parser::parsePack(const std::vector<Annotation>& annotations /*= {}*/,
                                const Visibility& visibility /*= Visibility{}*/, bool isBridge /*= false*/,
                                bool isUnion /*= false*/) {
    Token start = peek();
    std::string keyword = isUnion ? "union" : "pack";
    if (isUnion) {
        // A union reinterprets its storage, so reading one cannot be checked -
        // which is exactly what `unsafe` marks.
        if (!match(TokenType::UNSAFE)) {
            std::optional<Token> next = peekNext();
            fail("a union reinterprets its storage and cannot be checked; declare it 'unsafe union " +
                 (next ? next->lexeme : std::string()) + "' at line " + str(peek().line));
        }
        consume(TokenType::UNION, "Expected 'union'");
    } else {
        consume(TokenType::PACK, "Expected 'pack'");
    }
    if (check(TokenType::LESS)) {
        fail("Type parameters go after the " + keyword + " name: write '" + keyword + " Name<…>', not '" + keyword +
             "<…> Name', at line " + str(peek().line));
    }
    // A bridge pack may derive its exact backend and Azora names through a
    // declaration-position macro.
    std::optional<ParsedBridgeName> parsedBridgeName;
    if (isBridge) parsedBridgeName = parseBridgeName();
    std::string name;
    if (parsedBridgeName && parsedBridgeName->localName) name = *parsedBridgeName->localName;
    else if (parsedBridgeName) name = parsedBridgeName->backendName;
    else name = consume(TokenType::IDENTIFIER, "Expected " + keyword + " name").lexeme;
    TypeParams tp = parseTypeParams();
    // `bridge pack Byte(IntLiteral)` - the literal this type is written as.
    std::optional<std::string> literalKind;
    if (check(TokenType::L_PAREN)) {
        if (!isBridge) {
            fail("'" + keyword + " " + name + "(…)' is a bridge form at line " + str(start.line) +
                 ": only the compiler's own types are written as literals");
        }
        advance();
        Token written = advance();
        std::string kind = written.lexeme;
        if (!Literals::isKind(kind)) {
            fail("'" + kind + "' is not a literal at line " + str(start.line) + ": write '" + Literals::INT + "' or '" +
                 Literals::UINT + "' or '" + Literals::REAL + "'");
        }
        consume(TokenType::R_PAREN, "Expected ')' after the literal a bridge pack is written as");
        literalKind = kind;
    }
    constParamEnums = tp.constEnums;
    std::vector<ContractHead> derives;
    if (matchContinued(TokenType::DERIVES)) {
        bool parenthesized = check(TokenType::L_PAREN);
        std::vector<ContractHead> heads = parseDeriveHeads();
        if (parenthesized && heads.size() == 1) {
            fail("A single derived spec does not use parentheses; write 'pack " + name + " derives " + single(heads).name +
                 "' at line " + str(peek().line));
        }
        if (matchContinued(TokenType::DERIVES)) {
            fail("Use one derives clause containing all specs: 'pack " + name + " derives (Spec, Other)' at line " +
                 str(peek().line));
        }
        derives = heads;
    }
    if (isUnion && !derives.empty()) {
        fail("an unsafe union cannot derive field-wise implementations at line " + str(start.line));
    }
    Expr whereClause = withInlineBounds(tp.boundClause, parseWhereClause(), start.line);
    std::optional<int32_t> minLen = variadicMinLengthOf(whereClause);
    bool enforceNumFields = false;
    for (const Annotation& a : annotations) {
        if (a.name == "EnforceNumFields") enforceNumFields = true;
    }
    enqueueDerives(name, derives, start, tp.names, tp.variadic);
    std::optional<std::string> foreignName;
    if (parsedBridgeName && parsedBridgeName->localName) foreignName = parsedBridgeName->backendName;
    ExprMetaInvoke* nameMacro = parsedBridgeName ? parsedBridgeName->macro : nullptr;
    if (!check(TokenType::L_BRACE)) {
        consumeNewline();
        TopLevelPack pack;
        pack.name = name;
        pack.typeParams = tp.names;
        pack.line = start.line;
        pack.column = start.column;
        pack.annotations = annotations;
        pack.visibility = visibility;
        pack.variadicParam = tp.variadic;
        pack.minVariadicLength = minLen;
        pack.whereClause = whereClause;
        pack.constParams = tp.constParams;
        pack.isBridge = isBridge;
        pack.literalKind = literalKind;
        pack.isUnion = isUnion;
        pack.foreignName = foreignName;
        pack.nameMacro = nameMacro;
        return make(arena, pack);
    }
    consume(TokenType::L_BRACE, "Expected '{' after " + keyword + " name");
    skipNewlines();
    // A variadic pack may mix fixed fields with one generated field template.
    std::optional<VariadicFieldTemplate> fieldTemplate;
    std::vector<PackField> fields;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        // `inline if/when/for/while/loop { … }` in a pack body - compile-time
        // control flow over the field list.
        if (isInlineFieldFormAhead()) {
            std::vector<PackField> more = parseInlineFieldForm(enforceNumFields);
            fields.insert(fields.end(), more.begin(), more.end());
        } else if (tp.variadic && check(TokenType::INLINE)) {
            if (fieldTemplate) {
                fail("A variadic pack may declare only one inline field template at line " + str(peek().line));
            }
            fieldTemplate = parseVariadicFieldTemplate();
        } else {
            TokenType t = peek().type;
            if (isUnion && (t == TokenType::VAR || t == TokenType::VAL || t == TokenType::LET || t == TokenType::FIN)) {
                std::optional<Token> next = peekNext();
                fail("a union member is written '" + (next ? next->lexeme : std::string("name")) +
                     ": Type' with no binding keyword - its members share one slot, so '" + peek().lexeme +
                     "' says nothing, at line " + str(peek().line));
            }
            fields.push_back(parsePackField({.enforceNumFields = enforceNumFields}));
            match(TokenType::COMMA);
        }
        skipNewlines();
    }
    consume(TokenType::R_BRACE, "Expected '}' after " + keyword + " fields");
    consumeNewline();
    if (isUnion) {
        if (fields.empty()) fail("union '" + name + "' must declare at least one member at line " + str(start.line));
        if (fieldTemplate) {
            fail("union '" + name + "' cannot generate its members from a variadic template at line " + str(start.line));
        }
        // Every member addresses the same storage; members are always writable.
        for (PackField& field : fields) field.mutable_ = true;
    }
    TopLevelPack pack;
    pack.name = name;
    pack.fields = fields;
    pack.typeParams = tp.names;
    pack.line = start.line;
    pack.column = start.column;
    pack.annotations = annotations;
    pack.visibility = visibility;
    pack.variadicParam = tp.variadic;
    pack.minVariadicLength = minLen;
    pack.whereClause = whereClause;
    pack.constParams = tp.constParams;
    pack.constDefaults = tp.constDefaults;
    pack.constEnums = tp.constEnums;
    pack.fieldTemplate = fieldTemplate;
    pack.isBridge = isBridge;
    pack.isUnion = isUnion;
    pack.foreignName = foreignName;
    pack.nameMacro = nameMacro;
    return make(arena, pack);
}

/** ContractHead's constructor with Kotlin's `length = name.length` default. */
Parser::ContractHead Parser::contractHead(ContractHead head) {
    if (head.length < 0) head.length = u16len(head.name);
    return head;
}

/** Parses a nonempty parenthesized syntax list; false when no `(` opens one. */
bool Parser::parseParenthesizedList(const std::string& description, const std::function<void()>& element) {
    if (!match(TokenType::L_PAREN)) return false;
    TokenType close = TokenType::R_PAREN;
    skipNewlines();
    if (check(close)) fail("Expected at least one " + description + " at line " + str(peek().line));
    while (!isAtEnd()) {
        element();
        const Token* previous = tokenOrNull(current - 1);
        int32_t lastLine = previous ? previous->line : peek().line;
        bool separated = check(TokenType::NEWLINE) || peek().line > lastLine;
        skipNewlines();
        if (match(TokenType::COMMA)) {
            skipNewlines();
            if (check(close)) break;
        } else if (check(close)) {
            break;
        } else if (separated) {
            // a new line separates entries
        } else {
            fail("Expected ',' or a new line between " + description + " entries at line " + str(peek().line));
        }
    }
    consume(close, "Expected ')' after " + description + " list");
    return true;
}

Parser::ContractHead Parser::parseDeriveHead() {
    ContractHead head = parseContractName("Expected a spec name to derive");
    head.args = parseGenericTypeArgsIfPresent();
    return head;
}

std::vector<Parser::ContractHead> Parser::parseDeriveHeads() {
    std::vector<ContractHead> heads;
    bool isRow = parseParenthesizedList("derived spec", [&] { heads.push_back(parseDeriveHead()); });
    if (!isRow) heads.push_back(parseDeriveHead());
    return heads;
}

TopLevelImpl* Parser::derivedImpl(const std::string& target, const ContractHead& head, const Token& start,
                                  const std::vector<std::string>& typeParams /*= {}*/,
                                  const std::optional<std::string>& variadicParam /*= std::nullopt*/,
                                  const std::vector<Expr>& decoratorArgs /*= {}*/,
                                  const std::vector<std::pair<std::string, Expr>>& decoratorNamedArgs /*= {}*/) {
    TopLevelImpl impl;
    impl.typeName = target;
    impl.traitName = head.name;
    impl.line = start.line;
    impl.column = start.column;
    impl.traitArgs = head.args;
    impl.traitQualifier = head.qualifier;
    impl.traitLine = head.line;
    impl.traitColumn = head.column;
    impl.traitLength = head.length;
    impl.typeParams = typeParams;
    impl.variadicParam = variadicParam;
    impl.isDerived = true;
    impl.hasBody = false;
    impl.decoratorArgs = decoratorArgs;
    impl.decoratorNamedArgs = decoratorNamedArgs;
    implDefaults(impl);
    return make(arena, impl);
}

void Parser::enqueueDerives(const std::string& target, const std::vector<ContractHead>& heads, const Token& start,
                            const std::vector<std::string>& typeParams /*= {}*/,
                            const std::optional<std::string>& variadicParam /*= std::nullopt*/) {
    for (const ContractHead& head : heads) pendingTopLevels.push_back(derivedImpl(target, head, start, typeParams, variadicParam));
}

/** `derive (Spec, Other) for (Target, OtherTarget)` expands independent derivations. */
TopLevelImpl* Parser::parseDeriveDecl() {
    Token start = consume(TokenType::DERIVE, "Expected 'derive'");
    std::vector<ContractHead> heads = parseDeriveHeads();
    std::vector<Expr> decoratorArgs;
    std::vector<std::pair<std::string, Expr>> decoratorNamedArgs;
    if (check(TokenType::L_PAREN)) {
        auto parsed = parseDecoratorArguments();
        decoratorArgs = parsed.first;
        decoratorNamedArgs = parsed.second;
    }
    if (heads.size() > 1 && (!decoratorArgs.empty() || !decoratorNamedArgs.empty())) {
        fail("Derived values require a single spec at line " + str(start.line));
    }
    consume(TokenType::FOR, "Expected 'for' after derived specs");
    std::vector<std::string> targets = expandTypeListTargets(parseImplTargets());
    consumeNewline();
    std::vector<TopLevelImpl*> implementations;
    for (const std::string& target : targets) {
        for (const ContractHead& head : heads) {
            implementations.push_back(derivedImpl(target, head, start, {}, std::nullopt, decoratorArgs, decoratorNamedArgs));
        }
    }
    for (size_t i = 1; i < implementations.size(); i++) pendingTopLevels.push_back(implementations[i]);
    return first(implementations);
}

/** `inline for <loopVar> in <packVar> with index { <fields> }` - a variadic pack's field template. */
VariadicFieldTemplate Parser::parseVariadicFieldTemplate() {
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::FOR, "Expected 'for'");
    std::string loopVar = consume(TokenType::IDENTIFIER, "Expected loop type variable").lexeme;
    consume(TokenType::IN, "Expected 'in' after loop variable");
    match(TokenType::ELLIPSIS);  // preferred spelling: `inline for Ty in ...T`
    std::string packVar = consume(TokenType::IDENTIFIER, "Expected variadic pack variable").lexeme;
    // Optional `with index` - declares that `$index` interpolates the position.
    if (matchWithKeyword()) consume(TokenType::IDENTIFIER, "Expected 'index' after 'with'");
    consume(TokenType::L_BRACE, "Expected '{' to open variadic field template");
    skipNewlines();
    std::vector<TplField> tplFields;
    std::vector<ExprStringTemplate*> mixins;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        if (check(TokenType::INLINE)) {
            advance();  // 'inline' splice
            Expr templateExpr = parsePrimary();
            ExprStringTemplate* str = as<ExprStringTemplate>(templateExpr);
            if (str == nullptr) fail("Expected interpolated string after 'inline' at line " + azora::str(peek().line));
            mixins.push_back(str);
            consumeNewline();
        } else {
            std::string fieldName = consumeIdentifierLike("Expected field name in variadic template");
            consume(TokenType::COLON, "Expected ':' after variadic template field name");
            TypeRef fieldType = parseTypeName();
            tplFields.push_back(TplField{fieldName, fieldType});
            match(TokenType::COMMA);
        }
        skipNewlines();
    }
    consume(TokenType::R_BRACE, "Expected '}' after variadic field template");
    consumeNewline();
    return VariadicFieldTemplate{loopVar, packVar, tplFields, mixins};
}

/** True when an `inline` in a pack body opens a compile-time field form. */
bool Parser::isInlineFieldFormAhead() {
    if (!check(TokenType::INLINE)) return false;
    std::optional<Token> next = peekNext();
    if (!next) return false;
    switch (next->type) {
        case TokenType::IF:
        case TokenType::WHEN:
        case TokenType::WHILE:
        case TokenType::LOOP:
            return true;
        // `inline for Ty in ...T` is the variadic field template, handled
        // separately; `inline for x in <range>` generates ordinary fields.
        case TokenType::FOR:
            return !isVariadicFieldTemplateAhead();
        default:
            return false;
    }
}

/** True for `inline for <v> in ...<pack>` - the variadic template, not a field loop. */
bool Parser::isVariadicFieldTemplateAhead() {
    int32_t i = current + 2;
    if (!typeIs(i, TokenType::IDENTIFIER)) return false;
    i++;
    if (!typeIs(i, TokenType::IN)) return false;
    i++;
    return typeIs(i, TokenType::ELLIPSIS);
}

/**
 * One `inline when` arm's pattern. `.RowMajor` names a variant of whatever
 * enum the subject ranges over, and becomes that variant's position.
 */
Expr Parser::parseArmPattern(const Expr& subject) {
    Token at = peek();
    if (!check(TokenType::DOT)) return parseExpr();
    std::optional<std::string> owner;
    if (ExprIdentifier* id = as<ExprIdentifier>(subject)) {
        if (const std::string* found = constParamEnums.get(id->name)) owner = *found;
    }
    if (!owner) {
        std::optional<Token> next = peekNext();
        fail("'." + (next ? next->lexeme : std::string("null")) + "' needs an enum-typed subject at line " + str(at.line));
    }
    advance();  // '.'
    std::string variant = consume(TokenType::IDENTIFIER, "Expected enum variant after '.'").lexeme;
    int32_t ordinal = -1;
    if (const std::vector<std::string>* variants = declaredEnums->get(*owner)) {
        for (size_t i = 0; i < variants->size(); i++) {
            if ((*variants)[i] == variant) {
                ordinal = (int32_t)i;
                break;
            }
        }
    }
    if (ordinal < 0) fail("'" + *owner + "' has no variant '" + variant + "', at line " + str(at.line));
    return make(arena, ExprIntLiteral{.value = ordinal, .line = at.line, .column = at.column});
}

/**
 * Parses one compile-time field form and returns the fields it contributes.
 * `if`/`when` guard their fields with a predicate; `for`/`while`/`loop` repeat
 * a body whose bounds are compile-time values.
 */
std::vector<PackField> Parser::parseInlineFieldForm(bool enforceNumFields) {
    consume(TokenType::INLINE, "Expected 'inline'");
    Token keyword = advance();
    std::vector<PackField> result;
    auto append = [&](const std::vector<PackField>& more) { result.insert(result.end(), more.begin(), more.end()); };
    switch (keyword.type) {
        case TokenType::IF: {
            Expr condition = withoutTrailingLambda([&] { return parseExpr(); });
            append(guardedFieldBlock(condition, enforceNumFields));
            skipNewlines();
            // `else` and `else inline if` chain, each guarded by the negation of
            // everything before it.
            Expr accumulated = condition;
            while (check(TokenType::ELSE)) {
                advance();
                Expr negated = make(arena, ExprUnary{.op = TokenType::BANG, .operand = accumulated, .line = keyword.line});
                if (check(TokenType::INLINE)) advance();
                if (check(TokenType::IF)) {
                    advance();
                    Expr next = withoutTrailingLambda([&] { return parseExpr(); });
                    append(guardedFieldBlock(conjoin(next, negated), enforceNumFields));
                    accumulated = make(arena, ExprBinary{.left = accumulated, .op = TokenType::OR_OR, .right = next,
                                                         .line = keyword.line});
                } else {
                    append(guardedFieldBlock(negated, enforceNumFields));
                    break;
                }
                skipNewlines();
            }
            break;
        }
        case TokenType::WHEN: {
            // `inline when <subject> { <pattern> -> { fields } … }`; each arm is
            // guarded by `subject == pattern`.
            Expr subject = withoutTrailingLambda([&] { return parseExpr(); });
            consume(TokenType::L_BRACE, "Expected '{' after 'inline when' subject");
            skipNewlines();
            while (!check(TokenType::R_BRACE) && !isAtEnd()) {
                skipNewlines();
                if (check(TokenType::R_BRACE)) break;
                Expr guard;
                if (check(TokenType::ELSE)) {
                    advance();
                } else {
                    Expr pattern = parseArmPattern(subject);
                    guard = make(arena, ExprBinary{.left = subject, .op = TokenType::EQUAL_EQUAL, .right = pattern,
                                                   .line = keyword.line});
                }
                if (!match(TokenType::FAT_ARROW) && !match(TokenType::ARROW)) {
                    fail("Expected '=>' in 'inline when' arm at line " + str(peek().line));
                }
                skipNewlines();
                std::vector<PackField> armFields = fieldBlock(enforceNumFields);
                for (PackField field : armFields) {
                    field.condition = conjoin(field.condition, guard);
                    result.push_back(field);
                }
                skipNewlines();
            }
            consume(TokenType::R_BRACE, "Expected '}' after 'inline when' arms");
            consumeNewline();
            break;
        }
        case TokenType::FOR: {
            // `inline for i in <range> { fields }` - the body repeats per value.
            std::string loopVar = consumeIdentifierLike("Expected loop variable in 'inline for'");
            consume(TokenType::IN, "Expected 'in' after 'inline for' variable");
            Expr iterable = withoutTrailingLambda([&] { return parseExpr(); });
            append(repeatedFieldBlock(loopVar, iterable, enforceNumFields));
            break;
        }
        case TokenType::WHILE:
        case TokenType::LOOP: {
            // Bounded by a compile-time condition; the body's fields are recorded
            // once and repeated during expansion.
            Expr condition;
            if (keyword.type == TokenType::WHILE) condition = withoutTrailingLambda([&] { return parseExpr(); });
            append(guardedFieldBlock(condition, enforceNumFields));
            break;
        }
        default:
            fail("Unsupported 'inline' form in a pack body at line " + str(keyword.line));
    }
    return result;
}

/** The fields of a `{ … }` block, with no guard applied. */
std::vector<PackField> Parser::fieldBlock(bool enforceNumFields) {
    consume(TokenType::L_BRACE, "Expected '{' to open inline field block");
    skipNewlines();
    std::vector<PackField> fields;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        // A block may hold further compile-time control flow.
        if (isInlineFieldFormAhead()) {
            std::vector<PackField> more = parseInlineFieldForm(enforceNumFields);
            fields.insert(fields.end(), more.begin(), more.end());
        } else {
            fields.push_back(parsePackField({.enforceNumFields = enforceNumFields}));
        }
        skipNewlines();
    }
    consume(TokenType::R_BRACE, "Expected '}' after inline field block");
    consumeNewline();
    return fields;
}

/** A field block whose fields all carry [guard] in addition to their own. */
std::vector<PackField> Parser::guardedFieldBlock(const Expr& guard, bool enforceNumFields) {
    std::vector<PackField> fields = fieldBlock(enforceNumFields);
    for (PackField& field : fields) field.condition = conjoin(field.condition, guard);
    return fields;
}

/** A field block repeated over a compile-time iterable. */
std::vector<PackField> Parser::repeatedFieldBlock(const std::string& loopVar, const Expr& iterable, bool enforceNumFields) {
    // The loop is recorded on each field it contains, outermost first.
    FieldRepeat repeat{loopVar, iterable};
    std::vector<PackField> fields = fieldBlock(enforceNumFields);
    for (PackField& field : fields) field.repeats.insert(field.repeats.begin(), repeat);
    return fields;
}

/**
 * `inline for <var> in <compile-time list> { members }` inside an impl. The
 * body is re-parsed per iteration through a synthetic impl, so members go
 * through exactly the same member parser.
 */
std::vector<FuncDecl> Parser::parseInlineMemberFor(const std::string& typeName) {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::FOR, "Expected 'for'");
    std::vector<std::string> loopVars;
    if (match(TokenType::L_BRACKET)) {
        do {
            loopVars.push_back(consumeIdentifierLike("Expected loop variable in 'inline for (...)'"));
        } while (match(TokenType::COMMA));
        consume(TokenType::R_BRACKET, "Expected ']' after 'inline for' variables");
    } else {
        loopVars.push_back(consumeIdentifierLike("Expected loop variable after 'inline for'"));
    }
    consume(TokenType::IN, "Expected 'in' after 'inline for' variable");
    std::vector<std::vector<std::string>> lists;
    if (loopVars.size() > 1) {
        consume(TokenType::L_BRACKET, "Expected '[' - a parallel 'inline for' iterates a list per variable");
        do {
            lists.push_back(parseComptimeForValues());
        } while (match(TokenType::COMMA));
        consume(TokenType::R_BRACKET, "Expected ']' after parallel 'inline for' lists");
    } else {
        lists.push_back(parseComptimeForValues());
    }
    std::optional<std::string> indexVar;
    if (matchWithKeyword()) indexVar = consumeIdentifierLike("Expected index variable after 'with'");
    std::vector<Token> bodyTokens = captureInlineForBody();
    consumeNewline();
    std::vector<FuncDecl> generated;
    size_t count = first(lists).size();
    for (size_t i = 0; i < count; i++) {
        std::vector<Token> rendered = bodyTokens;
        std::vector<std::pair<std::string, std::string>> bindings;
        for (size_t slot = 0; slot < loopVars.size(); slot++) bindings.emplace_back(loopVars[slot], at(lists.at(slot), (int64_t)i));
        if (indexVar) bindings.emplace_back(*indexVar, str(i));
        rendered = foldBraceInterpolation(rendered, bindings);
        for (size_t slot = 0; slot < loopVars.size(); slot++) {
            rendered = substituteLoopVar(rendered, loopVars[slot], at(lists.at(slot), (int64_t)i));
        }
        if (indexVar) rendered = substituteLoopVar(rendered, *indexVar, str(i));
        rendered = foldListIndexing(rendered);
        // `inline fin name = "…"` binds a compile-time name for the rest of the body.
        rendered = foldInlineBindings(rendered, bindings);
        // Re-parse through a synthetic impl so the ordinary member parser runs.
        std::vector<Token> wrapper{
            Token{TokenType::IMPL, "impl", start.line, start.column, {}},
            Token{TokenType::IDENTIFIER, typeName, start.line, start.column, {}},
            Token{TokenType::L_BRACE, "{", start.line, start.column, {}},
        };
        wrapper.insert(wrapper.end(), rendered.begin(), rendered.end());
        wrapper.push_back(Token{TokenType::R_BRACE, "}", start.line, start.column, {}});
        wrapper.push_back(Token{TokenType::EOF_, "", start.line, start.column, {}});
        Parser sub(arena, wrapper, typeListEnv, declaredEnums, false, typeListScope);
        Program parsed = sub.parse();
        for (const TopLevel& item : parsed.items) {
            if (TopLevelImpl* impl = as<TopLevelImpl>(item)) generated.insert(generated.end(), impl->methods.begin(), impl->methods.end());
        }
    }
    return generated;
}

/**
 * Folds `inline fin <name> = "<text>"` out of a compile-time body: the
 * declaration binds a name, so it is consumed and its value substituted into
 * everything after it.
 */
std::vector<Token> Parser::foldInlineBindings(const std::vector<Token>& body,
                                              const std::vector<std::pair<std::string, std::string>>& bindings) {
    std::vector<Token> result = body;
    std::vector<std::pair<std::string, std::string>> known = bindings;
    while (true) {
        std::optional<size_t> at;
        for (size_t i = 0; i < result.size(); i++) {
            if (result[i].type == TokenType::INLINE && i + 1 < result.size() && result[i + 1].type == TokenType::FIN &&
                i + 2 < result.size() && result[i + 2].type == TokenType::IDENTIFIER && i + 3 < result.size() &&
                result[i + 3].type == TokenType::EQUAL && i + 4 < result.size()) {
                at = i;
                break;
            }
        }
        if (!at) break;
        std::string name = result[*at + 2].lexeme;
        // The value runs to the end of the line.
        size_t end = *at + 4;
        while (end < result.size() && result[end].type != TokenType::NEWLINE) end++;
        std::vector<Token> valueTokens(result.begin() + (std::ptrdiff_t)(*at + 4), result.begin() + (std::ptrdiff_t)end);
        size_t afterValue = end < result.size() ? end + 1 : end;
        std::vector<Token> remaining(result.begin(), result.begin() + (std::ptrdiff_t)*at);
        remaining.insert(remaining.end(), result.begin() + (std::ptrdiff_t)afterValue, result.end());
        if (valueTokens.size() == 1 && (valueTokens[0].type == TokenType::STRING_LITERAL ||
                                        valueTokens[0].type == TokenType::INTERPOLATED_STRING)) {
            // A string binds a NAME: resolve its interpolations against the
            // bindings in scope and splice it wherever the name is used.
            const Token& single = valueTokens[0];
            std::string value;
            if (const std::string* s = std::get_if<std::string>(&single.literal)) value = *s;
            else value = removeSurroundingQuotes(single.lexeme);
            for (const auto& [k, v] : known) value = replaceAll(replaceAll(value, "${" + k + "}", v), "$" + k, v);
            known.emplace_back(name, value);
            result = substituteLoopVar(remaining, name, value);
        } else {
            // Anything else binds an EXPRESSION, substituted as-is.
            result = substituteTokenSequence(remaining, name, valueTokens);
        }
    }
    return result;
}

/** Replaces every bare use of [name] with [replacement], parenthesised. */
std::vector<Token> Parser::substituteTokenSequence(const std::vector<Token>& tokens, const std::string& name,
                                                   const std::vector<Token>& replacement) {
    if (replacement.empty()) return tokens;
    std::vector<Token> out;
    for (const Token& t : tokens) {
        if (t.type == TokenType::IDENTIFIER && (t.lexeme == name || t.lexeme == "$" + name)) {
            out.push_back(Token{TokenType::L_PAREN, "(", t.line, t.column, {}});
            out.insert(out.end(), replacement.begin(), replacement.end());
            out.push_back(Token{TokenType::R_PAREN, ")", t.line, t.column, {}});
        } else {
            out.push_back(t);
        }
    }
    return out;
}

/**
 * `inline if <cond> { inline "<fragment>" }` between a signature and its body.
 * Returns the tokens to splice, or null when nothing applies.
 */
std::optional<std::vector<Token>> Parser::parseSignatureFragment() {
    int32_t meaningful = nextMeaningfulIndex();
    if (!typeIs(meaningful, TokenType::INLINE)) return std::nullopt;
    if (!typeIs(meaningful + 1, TokenType::IF)) return std::nullopt;
    while (current < meaningful) advance();
    advance();  // 'inline'
    advance();  // 'if'
    Expr condition = withoutTrailingLambda([&] { return parseExpr(); });
    consume(TokenType::L_BRACE, "Expected '{' after 'inline if' signature condition");
    skipNewlines();
    consume(TokenType::INLINE, "Expected 'inline \"…\"' inside a signature fragment");
    Token text = consume(TokenType::STRING_LITERAL, "Expected a quoted fragment after 'inline'");
    skipNewlines();
    consume(TokenType::R_BRACE, "Expected '}' after signature fragment");
    // A condition that is already decidable folds now; anything mentioning a type
    // parameter cannot be, and conservatively does not apply the fragment.
    std::optional<bool> holds = staticTruth(condition);
    if (!holds || !*holds) return std::nullopt;
    std::string body;
    if (const std::string* s = std::get_if<std::string>(&text.literal)) body = *s;
    else body = removeSurroundingQuotes(text.lexeme);
    return lexWithoutEof(body);
}

/** The truth of a constant condition, or null when it is not decidable here. */
std::optional<bool> Parser::staticTruth(const Expr& expr) {
    if (ExprBoolLiteral* b = as<ExprBoolLiteral>(expr)) return b->value;
    if (ExprGrouping* g = as<ExprGrouping>(expr)) return staticTruth(g->expr);
    if (ExprBinary* bin = as<ExprBinary>(expr)) {
        switch (bin->op) {
            case TokenType::AND_AND: {
                std::optional<bool> l = staticTruth(bin->left);
                std::optional<bool> r = staticTruth(bin->right);
                if (l == false || r == false) return false;
                if (l == true && r == true) return true;
                return std::nullopt;
            }
            case TokenType::OR_OR: {
                std::optional<bool> l = staticTruth(bin->left);
                std::optional<bool> r = staticTruth(bin->right);
                if (l == true || r == true) return true;
                if (l == false && r == false) return false;
                return std::nullopt;
            }
            case TokenType::EQUAL_EQUAL:
            case TokenType::BANG_EQUAL: {
                std::optional<std::string> l = literalText(bin->left);
                std::optional<std::string> r = literalText(bin->right);
                if (!l || !r) return std::nullopt;
                return bin->op == TokenType::EQUAL_EQUAL ? *l == *r : *l != *r;
            }
            default:
                return std::nullopt;
        }
    }
    return std::nullopt;
}

/** The literal text an expression denotes, for constant comparison. */
std::optional<std::string> Parser::literalText(const Expr& expr) {
    if (ExprStringLiteral* s = as<ExprStringLiteral>(expr)) return s->value;
    if (ExprIntLiteral* i = as<ExprIntLiteral>(expr)) return str((long long)i->value);
    if (ExprGrouping* g = as<ExprGrouping>(expr)) return literalText(g->expr);
    return std::nullopt;
}

/**
 * Re-reads [ret] with [fragment] appended, so a spliced `?! E` becomes a real
 * failable return type rather than a special case.
 */
TypeAnnotation Parser::applySignatureFragment(const TypeAnnotation& ret, const std::vector<Token>& fragment,
                                              const Token& at) {
    if (ret.isInferred()) return ret;
    std::vector<Token> rendered = lexWithoutEof(toString(ret.ref));
    rendered.insert(rendered.end(), fragment.begin(), fragment.end());
    rendered.push_back(Token{TokenType::EOF_, "", at.line, at.column, {}});
    Parser sub(arena, rendered, typeListEnv, declaredEnums, false, typeListScope);
    return explicitType(sub.parseTypeNameForFragment());
}

/** Entry point for re-parsing a type built from a signature fragment. */
TypeRef Parser::parseTypeNameForFragment() { return parseTypeName(); }

/**
 * The suffix that distinguishes one operator overload from another on a type:
 * the operand it accepts, and - for operators that do not already imply
 * mutation - whether the receiver is borrowed exclusively.
 */
std::string Parser::operatorOverloadSuffix(const std::string& opName, ParamModifier receiver,
                                           const std::vector<Param>& operands) {
    // `+=` and `[]=` mutate by definition; their receiver says nothing extra.
    bool mutationImplied = opName == "indexSet" ||
                           (endsWith(opName, "=") && opName != "==" && opName != "!=" && opName != "<=" && opName != ">=");
    std::string exclusive = !mutationImplied && writable(receiver) ? "!" : "";
    std::string operand = operands.size() == 1 ? "@" + operandTypeKey(operands[0].type) : "";
    return exclusive + operand;
}

/** The operand-type key used to distinguish operator overloads. */
std::string Parser::operandTypeKey(const TypeRef& type) {
    if (TypeRefNamed* n = as<TypeRefNamed>(type)) return n->name;
    if (TypeRefReference* r = as<TypeRefReference>(type)) return operandTypeKey(r->inner);
    if (TypeRefPointer* p = as<TypeRefPointer>(type)) return operandTypeKey(p->inner);
    if (TypeRefNullable* n = as<TypeRefNullable>(type)) return operandTypeKey(n->inner);
    return "_";
}

PackField Parser::parsePackField(const PackFieldOptions& options /*= {}*/) {
    std::vector<Annotation> annotations = options.preparsedAnnotations ? *options.preparsedAnnotations : parseAnnotations();
    Visibility visibility = options.preparsedVisibility ? *options.preparsedVisibility : parseVisibility();
    if (options.requireFin && !check(TokenType::FIN)) {
        fail("Decorator fields must be declared with 'fin' at line " + str(peek().line));
    }
    // `unsafe fin data: T*` - the field is readable only inside an unsafe scope.
    bool isUnsafeField = match(TokenType::UNSAFE);
    // A field's mutability is the value axis: `var` fields can be written
    // through the pack, `val`/`fin`/`let` fields cannot.
    bool mutableField = false;
    if (check(TokenType::VAR)) {
        advance();
        mutableField = true;
    } else if (check(TokenType::VAL) || check(TokenType::FIN) || check(TokenType::LET)) {
        advance();
    }
    std::string name;
    if (options.enforceNumFields && check(TokenType::INT_LITERAL)) name = advance().lexeme;
    else name = consumeIdentifierLike("Expected field name");
    TypeRef type;
    Expr defaultValue;
    if (match(TokenType::COLON)) {
        type = parseTypeName();
        if (match(TokenType::EQUAL)) defaultValue = parseExpr();
    } else {
        consume(TokenType::EQUAL, "Expected ':' or '=' after pack field name");
        defaultValue = parseExpr();
        type = inferPackFieldType(defaultValue);
        if (!type) {
            fail("Cannot infer type of field '" + name + "' at line " + str(defaultValue.line()) +
                 "; add an explicit ': Type'");
        }
    }
    // `var w: T = 0 where N == 4` - the same predicate written on one field.
    Expr condition = parseWhereClause();
    consumeNewline();
    PackField field;
    field.name = name;
    field.type = type;
    field.mutable_ = mutableField;
    field.default_ = defaultValue;
    field.visibility = visibility;
    field.isUnsafe = isUnsafeField;
    field.annotations = annotations;
    field.condition = condition;
    return field;
}

/** Combines two field predicates; either may be absent. */
Expr Parser::conjoin(const Expr& inner, const Expr& outer) {
    if (!inner) return outer;
    if (!outer) return inner;
    return make(arena, ExprBinary{.left = outer, .op = TokenType::AND_AND, .right = inner, .line = outer.line()});
}

/**
 * Infers declaration types that are fully determined by syntax: literals,
 * constructor-shaped calls, and homogeneous collection literals only.
 */
TypeRef Parser::inferPackFieldType(const Expr& expr) {
    auto named = [&](const std::string& name, std::vector<TypeRef> args = {}) -> TypeRef {
        TypeRefNamed ref;
        ref.name = name;
        ref.args = std::move(args);
        return make(arena, ref);
    };
    switch (expr.kind()) {
        // A literal states no width, so a field inferred from one takes the default.
        case ExprKind::IntLiteral: return named(Literals::DEFAULT_INT);
        case ExprKind::DoubleLiteral: return named(Literals::DEFAULT_FLOAT);
        case ExprKind::StringLiteral:
        case ExprKind::StringTemplate: return named("String");
        case ExprKind::BoolLiteral: return named("Bool");
        case ExprKind::CharLiteral: return named("Char");
        case ExprKind::Grouping: return inferPackFieldType(cast<ExprGrouping>(expr).expr);
        case ExprKind::Call: {
            const ExprCall& call = cast<ExprCall>(expr);
            return named(call.callee, call.typeArgs);
        }
        case ExprKind::ArrayLiteral: {
            const ExprArrayLiteral& a = cast<ExprArrayLiteral>(expr);
            if (a.elements.empty()) return nullptr;
            TypeRef element = inferPackFieldType(a.elements[0]);
            if (!element) return nullptr;
            return make(arena, TypeRefArray{element});
        }
        case ExprKind::SetLiteral: {
            const ExprSetLiteral& s = cast<ExprSetLiteral>(expr);
            if (s.elements.empty()) return nullptr;
            TypeRef element = inferPackFieldType(s.elements[0]);
            if (!element) return nullptr;
            return make(arena, TypeRefSet{element});
        }
        case ExprKind::MapLit: {
            const ExprMapLit& m = cast<ExprMapLit>(expr);
            if (m.entries.empty()) return nullptr;
            TypeRef keyType = inferPackFieldType(m.entries[0].first);
            TypeRef valueType = inferPackFieldType(m.entries[0].second);
            if (keyType && valueType) return make(arena, TypeRefMap{keyType, valueType});
            return nullptr;
        }
        case ExprKind::TupleLit: {
            const ExprTupleLit& t = cast<ExprTupleLit>(expr);
            std::vector<TypeRef> elements;
            for (const Expr& element : t.elements) {
                TypeRef inferred = inferPackFieldType(element);
                if (inferred) elements.push_back(inferred);
            }
            TypeRefTuple* tuple = make(arena, TypeRefTuple{elements});
            if (tuple->elements.size() == t.elements.size()) return tuple;
            return nullptr;
        }
        default:
            return nullptr;
    }
}

}  // namespace azora
