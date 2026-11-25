// Parser.kt lines 8560-9450: imports, `Self` binding, member receivers,
// operator receivers, `prop` as a binding, type aliases, and the head of
// compile-time type properties.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"

namespace azora {

namespace {

TypeRef namedType(Arena& arena, const std::string& name) {
    TypeRefNamed ref;
    ref.name = name;
    return make(arena, ref);
}

}  // namespace

/**
 * `import a, b::*, std.x::{A, f::*}` - one import statement, one ImportSpec
 * per clause.
 */
TopLevel Parser::parseUse(bool exported /*= false*/, const Expr& condition /*= nullptr*/) {
    Token start = consume(TokenType::IMPORT, "Expected 'import'");
    std::vector<ImportSpec> specs;
    do {
        specs.push_back(parseImportSpec());
    } while (match(TokenType::COMMA) && check(TokenType::IDENTIFIER));
    consumeNewline();
    return make(arena, TopLevelUseImport{specs, start.line, start.column, exported, condition});
}

/** One import clause, joined to [prefix] when it is a member of a group. */
ImportSpec Parser::parseImportSpec(const std::string& prefix /*= ""*/) {
    Token clauseStart = peek();
    std::string path;
    if (!prefix.empty()) path = prefix + ".";
    path += consumeIdentifierLike("Expected a name after 'import'");
    while (match(TokenType::DOT)) {
        if (match(TokenType::STAR)) {
            fail("a '.' walks down the module path and '::' reaches inside a module, so write 'import " + path +
                 "::*' at line " + str(clauseStart.line));
        } else if (check(TokenType::L_BRACKET) || check(TokenType::L_BRACE)) {
            fail("import selector groups use '::{' after the module path; write 'import " + path + "::{…}' at line " +
                 str(peek().line));
        } else {
            path += ".";
            path += consumeIdentifierLike("Expected a name after '.'");
        }
    }
    auto spec = [&](SelectorKind kind) {
        ImportSpec out;
        out.path = path;
        out.selector.kind = kind;
        out.line = clauseStart.line;
        out.column = clauseStart.column;
        return out;
    };
    if (match(TokenType::DOUBLE_COLON)) {
        // `path::*` - every symbol the module declares.
        if (match(TokenType::STAR)) return spec(SelectorKind::All);
        // `path::{A, B}` - several symbols out of one module.
        if (check(TokenType::L_BRACE)) return parseImportGroup(path, clauseStart);
        if (check(TokenType::L_BRACKET)) {
            fail("import selector groups use braces; write 'import " + path + "::{…}' at line " + str(peek().line));
        }
        path += ".";
        path += consumeIdentifierLike("Expected a name after '::'");
        return spec(SelectorKind::Path);
    }
    return spec(SelectorKind::Path);
}

/**
 * `base::{a, b::*}` - a group of clauses under one module. Members are
 * separated by a comma or by a line break.
 */
ImportSpec Parser::parseImportGroup(const std::string& base, const Token& clauseStart) {
    consume(TokenType::L_BRACE, "Expected '{' to open an import selector group");
    TokenType closer = TokenType::R_BRACE;
    std::string closerText = "}";
    std::vector<ImportSpec> members;
    skipNewlines();
    if (check(closer)) fail("Expected at least one name inside the import group at line " + str(peek().line));
    if (check(TokenType::STAR)) return parseWildcardImportGroup(base, clauseStart, closer, closerText);
    if (check(TokenType::WITHOUT)) {
        fail("'without' in an import group requires '*' first at line " + str(peek().line) + "; write 'import " + base +
             "::{*, without name}'");
    }
    while (!check(closer) && !isAtEnd()) {
        if (check(TokenType::STAR)) fail("'*' must be the first member of a wildcard import group at line " + str(peek().line));
        if (check(TokenType::WITHOUT)) {
            fail("'without' is only valid after '*' in an import group at line " + str(peek().line));
        }
        members.push_back(parseImportSpec(base));
        const Token* previous = tokenOrNull(current - 1);
        int32_t previousLine = previous ? previous->line : peek().line;
        bool separatedByNewline = check(TokenType::NEWLINE) || peek().line > previousLine;
        skipNewlines();
        if (match(TokenType::COMMA)) {
            skipNewlines();
        } else if (!check(closer) && !isAtEnd() && !separatedByNewline) {
            fail("Expected ',' or a new line after an import group member at line " + str(peek().line));
        }
    }
    consume(closer, "Expected '" + closerText + "' after the import group");
    ImportSpec out;
    out.path = base;
    out.selector.kind = SelectorKind::Group;
    out.selector.members = members;
    out.line = clauseStart.line;
    out.column = clauseStart.column;
    return out;
}

/** `base::{*, without x, without (y, z)}` - wildcard selection with exclusions. */
ImportSpec Parser::parseWildcardImportGroup(const std::string& base, const Token& clauseStart, TokenType closer,
                                            const std::string& closerText) {
    Token star = consume(TokenType::STAR, "Expected '*' in wildcard import group");
    std::vector<std::string> excluded;
    OrderedSet<std::string> seen;
    int32_t previousLine = star.line;
    while (!check(closer) && !isAtEnd()) {
        bool separatedByNewline = check(TokenType::NEWLINE) || peek().line > previousLine;
        if (check(TokenType::NEWLINE)) skipNewlines();
        if (check(closer)) break;
        if (match(TokenType::COMMA)) {
            skipNewlines();
        } else if (!separatedByNewline) {
            fail("Expected ',' or a new line after '*' or an import exclusion at line " + str(peek().line));
        }
        consume(TokenType::WITHOUT, "A wildcard import group may contain only 'without name' after '*'");
        std::vector<std::string> names = parseImportExclusionNames();
        for (const std::string& name : names) {
            if (!seen.add(name)) fail("Import exclusion '" + name + "' is written more than once at line " + str(peek().line));
            excluded.push_back(name);
        }
        const Token* previous = tokenOrNull(current - 1);
        previousLine = previous ? previous->line : peek().line;
    }
    consume(closer, "Expected '" + closerText + "' after the wildcard import group");
    ImportSpec out;
    out.path = base;
    out.selector.kind = SelectorKind::All;
    out.without = excluded;
    out.line = clauseStart.line;
    out.column = clauseStart.column;
    return out;
}

/** The name or parenthesized name list following `without` in an import group. */
std::vector<std::string> Parser::parseImportExclusionNames() {
    if (!match(TokenType::L_PAREN)) return {consumeIdentifierLike("Expected a symbol name after 'without'")};
    skipNewlines();
    if (check(TokenType::R_PAREN)) fail("An import exclusion group cannot be empty at line " + str(peek().line));
    std::vector<std::string> names;
    while (!check(TokenType::R_PAREN) && !isAtEnd()) {
        names.push_back(consumeIdentifierLike("Expected a symbol name in the 'without' group"));
        const Token* previous = tokenOrNull(current - 1);
        int32_t previousLine = previous ? previous->line : peek().line;
        bool separatedByNewline = check(TokenType::NEWLINE) || peek().line > previousLine;
        if (check(TokenType::NEWLINE)) skipNewlines();
        if (match(TokenType::COMMA)) {
            skipNewlines();
        } else if (check(TokenType::R_PAREN)) {
        } else if (separatedByNewline) {
        } else {
            fail("Expected ',' or a new line between excluded import names at line " + str(peek().line));
        }
    }
    consume(TokenType::R_PAREN, "Expected ')' after excluded import names");
    if (names.size() < 2) fail("A single import exclusion is written 'without " + single(names) + "', without parentheses");
    return names;
}

/**
 * Binds `Self` to [typeName] throughout an impl block's members: inside
 * `impl Model`, `Self` means `Model`.
 */
std::vector<FuncDecl> Parser::bindSelf(const std::vector<FuncDecl>& methods, const std::string& typeName) {
    bool any = false;
    for (const FuncDecl& m : methods) {
        if (declMentionsSelf(m)) any = true;
    }
    if (!any) return methods;
    TypeRefNamed* target = make(arena, TypeRefNamed{});
    target->name = typeName;
    std::vector<FuncDecl> out;
    for (const FuncDecl& m : methods) {
        FuncDecl copy = m;
        for (Param& param : copy.params) param.type = selfTo(param.type, target);
        if (m.returnType.isExplicit()) copy.returnType = explicitType(selfTo(m.returnType.ref, target));
        if (copy.extensionReceiver) copy.extensionReceiver->type = selfTo(copy.extensionReceiver->type, target);
        out.push_back(copy);
    }
    return out;
}

bool Parser::declMentionsSelf(const FuncDecl& m) {
    for (const Param& param : m.params) {
        if (mentionsSelf(param.type)) return true;
    }
    if (m.returnType.isExplicit() && mentionsSelf(m.returnType.ref)) return true;
    return m.extensionReceiver && mentionsSelf(m.extensionReceiver->type);
}

// `Self` can appear wherever a type can, so both of these walk every composite variant.
bool Parser::mentionsSelf(const TypeRef& t) {
    auto anyOf = [&](const std::vector<TypeRef>& types) {
        for (const TypeRef& type : types) {
            if (mentionsSelf(type)) return true;
        }
        return false;
    };
    switch (t.kind()) {
        case TypeRefKind::Named: {
            const TypeRefNamed& n = cast<TypeRefNamed>(t);
            return n.name == "Self" || anyOf(n.args);
        }
        case TypeRefKind::Array: return mentionsSelf(cast<TypeRefArray>(t).element);
        case TypeRefKind::Map: return mentionsSelf(cast<TypeRefMap>(t).key) || mentionsSelf(cast<TypeRefMap>(t).value);
        case TypeRefKind::Set: return mentionsSelf(cast<TypeRefSet>(t).element);
        case TypeRefKind::Tuple: return anyOf(cast<TypeRefTuple>(t).elements);
        case TypeRefKind::Nullable: return mentionsSelf(cast<TypeRefNullable>(t).inner);
        case TypeRefKind::Failable: return mentionsSelf(cast<TypeRefFailable>(t).ok);
        case TypeRefKind::Pointer: return mentionsSelf(cast<TypeRefPointer>(t).inner);
        case TypeRefKind::Reference: return mentionsSelf(cast<TypeRefReference>(t).inner);
        case TypeRefKind::Function: {
            const TypeRefFunction& f = cast<TypeRefFunction>(t);
            return anyOf(f.params) || anyOf(f.receivers) || mentionsSelf(f.ret);
        }
        default: return false;
    }
}

TypeRef Parser::selfTo(const TypeRef& t, TypeRefNamed* target) {
    auto each = [&](const std::vector<TypeRef>& types) {
        std::vector<TypeRef> out;
        for (const TypeRef& type : types) out.push_back(selfTo(type, target));
        return out;
    };
    switch (t.kind()) {
        case TypeRefKind::Named: {
            const TypeRefNamed& n = cast<TypeRefNamed>(t);
            if (n.name == "Self" && n.args.empty()) return target;
            TypeRefNamed copy = n;
            copy.args = each(n.args);
            return make(arena, copy);
        }
        case TypeRefKind::Array: return make(arena, TypeRefArray{selfTo(cast<TypeRefArray>(t).element, target)});
        case TypeRefKind::Map: {
            TypeRef key = selfTo(cast<TypeRefMap>(t).key, target);
            TypeRef value = selfTo(cast<TypeRefMap>(t).value, target);
            return make(arena, TypeRefMap{key, value});
        }
        case TypeRefKind::Set: return make(arena, TypeRefSet{selfTo(cast<TypeRefSet>(t).element, target)});
        case TypeRefKind::Tuple: return make(arena, TypeRefTuple{each(cast<TypeRefTuple>(t).elements)});
        case TypeRefKind::Nullable: return make(arena, TypeRefNullable{selfTo(cast<TypeRefNullable>(t).inner, target)});
        case TypeRefKind::Failable: {
            TypeRefFailable copy = cast<TypeRefFailable>(t);
            copy.ok = selfTo(copy.ok, target);
            return make(arena, copy);
        }
        case TypeRefKind::Pointer: {
            TypeRefPointer copy = cast<TypeRefPointer>(t);
            copy.inner = selfTo(copy.inner, target);
            return make(arena, copy);
        }
        case TypeRefKind::Reference: {
            TypeRefReference copy = cast<TypeRefReference>(t);
            copy.inner = selfTo(copy.inner, target);
            return make(arena, copy);
        }
        case TypeRefKind::Function: {
            TypeRefFunction copy = cast<TypeRefFunction>(t);
            copy.params = each(copy.params);
            copy.receivers = each(copy.receivers);
            copy.ret = selfTo(copy.ret, target);
            return make(arena, copy);
        }
        default: return t;
    }
}

/**
 * A `prop` is observational, so `[self&]` is the only receiver it takes:
 * `[self!]` writes and `[self]` consumes, which is what a `func` is for.
 */
void Parser::requireObservingReceiver(const PropReceiver& receiver, const std::string& name, int32_t line) {
    if (!receiver.declared) return;
    if (receiver.modifier == ParamModifier::SHARED && receiver.borrowWritten) return;
    std::string written = receiver.modifier == ParamModifier::EXCLUSIVE ? "!" : "";
    std::string does = receiver.modifier == ParamModifier::EXCLUSIVE ? "writes through" : "consumes";
    fail("a 'prop' only observes, so '" + name + "' takes '[self&]' rather than '[self" + written + "]' at line " +
         str(line) + ": declare a 'func' for one that " + does + " its receiver");
}

Parser::PropReceiver Parser::parsePropReceiver() {
    // A member that writes no receiver belongs to the *type*; the shared borrow
    // of `Self` is still filled in, and `declared` tells the two apart.
    if (!check(TokenType::L_BRACKET)) return PropReceiver{"self", namedType(arena, "Self"), ParamModifier::SHARED, false};
    consume(TokenType::L_BRACKET, "Expected '[' - a member declares its receiver, as in '[self: Self&]'");
    std::pair<PropReceiver, std::vector<Param>> parsed = parseReceiverList();
    if (!parsed.second.empty()) fail("only a 'ctor' takes a receiver beyond its own, at line " + str(peek().line));
    return parsed.first;
}

/** The receivers in a `[…]` list: the member's own, then any it reads from scope. */
std::pair<Parser::PropReceiver, std::vector<Param>> Parser::parseReceiverList() {
    std::optional<PropReceiver> shorthand = parseSelfReceiverShorthand();
    std::string name;
    TypeRef type;
    ParamModifier modifier;
    bool sigilWritten = true;
    if (shorthand) {
        name = shorthand->name;
        type = shorthand->type ? shorthand->type : namedType(arena, "Self");
        modifier = shorthand->modifier;
        sigilWritten = shorthand->borrowWritten;
    } else {
        name = consumeIdentifierLike("Expected receiver name in '[…]'");
        if (!check(TokenType::COLON)) {
            fail("a receiver must name its type: write '[" + name + ": Self&]' inside an impl, or '[" + name +
                 ": Type&]' on an extension, at line " + str(peek().line));
        }
        advance();  // ':'
        std::tuple<TypeRef, ParamModifier, bool> parsed = parseReceiverTypeAndModifier();
        type = std::get<0>(parsed);
        modifier = std::get<1>(parsed);
        sigilWritten = std::get<2>(parsed);
    }
    std::vector<Param> extra;
    while (match(TokenType::COMMA)) {
        skipNewlines();
        std::string extraName = consumeIdentifierLike("Expected receiver name in '[…]'");
        if (!check(TokenType::COLON)) {
            fail("a receiver must name its type: write '[" + extraName + ": Type&]', at line " + str(peek().line));
        }
        advance();  // ':'
        std::tuple<TypeRef, ParamModifier, bool> parsed = parseReceiverTypeAndModifier();
        Param param;
        param.name = extraName;
        param.type = std::get<0>(parsed);
        param.modifier = std::get<1>(parsed);
        extra.push_back(param);
    }
    consume(TokenType::R_BRACKET, "Expected ']' after receiver");
    return {PropReceiver{name, type, modifier, true, sigilWritten}, extra};
}

/**
 * `[self&]`, `[self!]`, `[self]`, `[&]`, `[!]` - the receiver, without its
 * type, where `Self` is settled. Returns none elsewhere.
 */
std::optional<Parser::PropReceiver> Parser::parseSelfReceiverShorthand() {
    if (!currentImplTypeName) return std::nullopt;
    bool named = check(TokenType::IDENTIFIER) && peek().lexeme == SELF_RECEIVER;
    int32_t sigilAt = named ? 1 : 0;
    ParamModifier modifier;
    if (typeIs(current + sigilAt, TokenType::AMP)) {
        modifier = ParamModifier::SHARED;
    } else if (typeIs(current + sigilAt, TokenType::BANG)) {
        modifier = ParamModifier::EXCLUSIVE;
    } else {
        // `[self]` alone consumes; `[]` names no receiver at all.
        if (!named) return std::nullopt;
        modifier = ParamModifier::NONE;
    }
    int32_t width = sigilAt + (modifier == ParamModifier::NONE ? 0 : 1);
    if (!typeIs(current + width, TokenType::R_BRACKET) && !typeIs(current + width, TokenType::COMMA)) return std::nullopt;
    for (int32_t i = 0; i < width; i++) advance();
    return PropReceiver{named ? SELF_RECEIVER : IGNORED_RECEIVER, namedType(arena, "Self"), modifier, true,
                        modifier != ParamModifier::NONE};
}

/**
 * The type of a receiver together with the borrow its sigil asks for; a bare
 * `Self` still borrows for reading.
 */
std::tuple<TypeRef, ParamModifier, bool> Parser::parseReceiverTypeAndModifier() {
    TypeRef parsed = parseTypeName();
    if (TypeRefReference* reference = as<TypeRefReference>(parsed)) {
        return {reference->inner, paramModifier(reference->kind), true};
    }
    return {parsed, ParamModifier::SHARED, false};
}

/** `&.member`, `!.member`, or `.member` inside an impl/spec body. */
std::optional<Parser::PropReceiver> Parser::parseImplicitMemberReceiver() {
    ParamModifier modifier;
    if (match(TokenType::AMP)) {
        consume(TokenType::DOT, "Expected '.' after '&' in a shared receiver");
        modifier = ParamModifier::SHARED;
    } else if (match(TokenType::BANG)) {
        consume(TokenType::DOT, "Expected '.' after '!' in a mutable receiver");
        modifier = ParamModifier::EXCLUSIVE;
    } else if (match(TokenType::DOT)) {
        modifier = ParamModifier::NONE;
    } else {
        return std::nullopt;
    }
    return PropReceiver{"self", namedType(arena, "Self"), modifier, true, modifier != ParamModifier::NONE};
}

/** `(name: Type&, other: Other&).member` or `(Type&, Other&).member`. */
std::optional<Parser::PropReceiver> Parser::parseExplicitMemberReceiver() {
    if (!check(TokenType::L_PAREN)) return std::nullopt;
    int32_t closing = current;
    int depth = 0;
    while (closing < (int32_t)tokens.size()) {
        TokenType t = tokens[(size_t)closing].type;
        if (t == TokenType::L_PAREN) {
            depth++;
        } else if (t == TokenType::R_PAREN) {
            depth--;
            if (depth == 0) break;
        }
        closing++;
    }
    if (!typeIs(closing + 1, TokenType::DOT)) return std::nullopt;
    advance();
    struct ParsedReceiver {
        std::string name;
        TypeRef type;
        ParamModifier modifier;
        bool borrowWritten;
    };
    std::vector<ParsedReceiver> receivers;
    bool unnamed = false;
    int index = 0;
    do {
        skipNewlines();
        bool named = check(TokenType::IDENTIFIER) && peekNextIs(TokenType::COLON);
        std::string name;
        if (named) {
            name = consumeIdentifierLike("Expected receiver name");
            consume(TokenType::COLON, "Expected ':' after receiver name");
        } else {
            unnamed = true;
            name = "__receiver" + str(index);
        }
        TypeRef parsed = parseTypeName();
        if (TypeRefReference* reference = as<TypeRefReference>(parsed)) {
            receivers.push_back(ParsedReceiver{name, reference->inner, paramModifier(reference->kind), true});
        } else {
            receivers.push_back(ParsedReceiver{name, parsed, ParamModifier::NONE, false});
        }
        index++;
        skipNewlines();
    } while (match(TokenType::COMMA));
    bool anyUnnamed = false;
    bool anyNamed = false;
    for (const ParsedReceiver& r : receivers) {
        if (startsWith(r.name, "__receiver")) anyUnnamed = true;
        else anyNamed = true;
    }
    if (anyUnnamed && anyNamed) fail("a receiver tuple is either fully named or fully unnamed at line " + str(peek().line));
    // groupBy display name; the first group (in first-occurrence order) with more than one.
    OrderedMap<std::string, int> counts;
    for (const ParsedReceiver& r : receivers) counts.getOrPut(displayName(r.type), 0)++;
    for (const auto& [typeName, count] : counts.entries) {
        if (count > 1) fail("context receivers cannot repeat type '" + typeName + "' at line " + str(peek().line));
    }
    consume(TokenType::R_PAREN, "Expected ')' after extension receiver");
    consume(TokenType::DOT, "Expected '.' between extension receiver and member name");
    if (receivers.empty()) fail("a receiver tuple cannot be empty at line " + str(peek().line));
    const ParsedReceiver& firstReceiver = receivers[0];
    pendingMemberContextReceivers.clear();
    for (size_t i = 1; i < receivers.size(); i++) {
        Param param;
        param.name = receivers[i].name;
        param.type = receivers[i].type;
        param.modifier = receivers[i].modifier;
        pendingMemberContextReceivers.push_back(param);
    }
    return PropReceiver{unnamed && receivers.size() == 1 ? "self" : firstReceiver.name, firstReceiver.type,
                        firstReceiver.modifier, true, firstReceiver.borrowWritten};
}

/** `Type&.member` / `Type!.member` with an implicit receiver named `self`. */
std::optional<Parser::PropReceiver> Parser::parseTypedMemberReceiver(bool operatorHead /*= false*/) {
    // The owned shorthand `T.member` is restricted to an unqualified name.
    if (check(TokenType::IDENTIFIER) && peekNextIs(TokenType::DOT) && typeIs(current + 2, TokenType::IDENTIFIER)) {
        TypeRef type = namedType(arena, advance().lexeme);
        advance();  // '.'
        return PropReceiver{"self", type, ParamModifier::NONE, true, false};
    }
    // `Box<T>.member` - the owned receiver of a generic type.
    if (check(TokenType::IDENTIFIER) && peekNextIs(TokenType::LESS)) {
        int32_t close = current + 1;
        int depth = 0;
        while (close < (int32_t)tokens.size()) {
            TokenType t = tokens[(size_t)close].type;
            if (t == TokenType::LESS) {
                depth++;
            } else if (t == TokenType::GREATER) {
                depth--;
            } else if (t == TokenType::SHIFT_RIGHT) {
                depth -= 2;
            } else if (t == TokenType::L_PAREN || t == TokenType::L_BRACE || t == TokenType::NEWLINE ||
                       t == TokenType::EOF_) {
                break;
            }
            if (depth <= 0) break;
            close++;
        }
        if (depth == 0 && typeIs(close + 1, TokenType::DOT) && typeIs(close + 2, TokenType::IDENTIFIER)) {
            TypeRef type = parseTypeName(false);
            consume(TokenType::DOT, "Expected '.' between extension receiver and member name");
            return PropReceiver{"self", type, ParamModifier::NONE, true, false};
        }
    }
    int32_t i = current;
    int angleDepth = 0;
    bool found = false;
    while (i < (int32_t)tokens.size()) {
        TokenType t = tokens[(size_t)i].type;
        if (t == TokenType::LESS) {
            angleDepth++;
        } else if (t == TokenType::GREATER) {
            if (angleDepth > 0) angleDepth--;
        } else if (t == TokenType::SHIFT_RIGHT) {
            angleDepth = angleDepth > 2 ? angleDepth - 2 : 0;
        } else if (t == TokenType::AMP || t == TokenType::BANG) {
            if (angleDepth == 0 && typeIs(i + 1, TokenType::DOT)) found = true;
            break;
        } else if (t == TokenType::L_PAREN || t == TokenType::NEWLINE || t == TokenType::EOF_) {
            break;
        }
        i++;
    }
    if (!found) return std::nullopt;
    // In an operator head, `Type&.(rhs)` is a declaration receiver followed by operands.
    TypeRef parsedType = parseTypeName(!operatorHead);
    TypeRefReference* parsed = as<TypeRefReference>(parsedType);
    if (parsed == nullptr) fail("Expected '&' or '!' on the extension receiver type at line " + str(peek().line));
    consume(TokenType::DOT, "Expected '.' between extension receiver and member name");
    return PropReceiver{"self", parsed->inner, paramModifier(parsed->kind), true, true};
}

/**
 * Reads the receiver that follows an operator symbol: `oper+ &.(rhs)` in an
 * impl/spec, or `oper+ Number&.(rhs)` at top level.
 */
Parser::PropReceiver Parser::parseOperatorReceiver(const std::string& opName, bool allowImplicit) {
    std::string sourceName = operatorSourceName(opName);
    if (check(TokenType::L_BRACKET)) {
        std::string replacement = allowImplicit ? "oper" + sourceName + " &.(…)" : "oper" + sourceName + " Type&.(…)";
        fail("operator receivers no longer use brackets; write '" + replacement + "', not '[self: …]', at line " +
             str(peek().line));
    }
    std::optional<PropReceiver> receiver;
    if (allowImplicit) {
        receiver = parseImplicitMemberReceiver();
        if (!receiver) receiver = parseExplicitMemberReceiver();
        if (!receiver) receiver = parseOwnedOperatorTypeReceiver();
        if (!receiver) receiver = parseTypedMemberReceiver(true);
    } else {
        if (check(TokenType::AMP) || check(TokenType::BANG) || check(TokenType::DOT)) {
            fail("line " + str(peek().line) +
                 ": '&.', '!.' and '.' operator receivers require an impl or spec; a free operator names its receiver, "
                 "for example 'oper" +
                 sourceName + " Type&.(…)'");
        }
        receiver = parseExplicitMemberReceiver();
        if (!receiver) receiver = parseOwnedOperatorTypeReceiver();
        if (!receiver) receiver = parseTypedMemberReceiver(true);
    }
    if (!pendingMemberContextReceivers.empty()) {
        pendingMemberContextReceivers.clear();
        fail("an operator has exactly one receiver at line " + str(peek().line));
    }
    if (!receiver) {
        if (allowImplicit) {
            fail("an operator declares its receiver before its operands; write 'oper" + sourceName + " &.(…)' at line " +
                 str(peek().line));
        }
        fail("a free operator names its receiver before its operands; write 'oper" + sourceName + " Type&.(…)' at line " +
             str(peek().line));
    }
    return *receiver;
}

/** `Type.` as an owned free-operator receiver; `&.` and `!.` use the common parser. */
std::optional<Parser::PropReceiver> Parser::parseOwnedOperatorTypeReceiver() {
    if (!check(TokenType::IDENTIFIER) || !peekNextIs(TokenType::DOT)) return std::nullopt;
    TypeRef type = namedType(arena, advance().lexeme);
    advance();  // receiver/member separator
    return PropReceiver{"self", type, ParamModifier::NONE, true, false};
}

TopLevel Parser::parsePropAsFin(const std::vector<Annotation>& annotations, const Visibility& visibility,
                                bool isReactive /*= false*/, bool isTask /*= false*/) {
    Token start = peek();
    consume(TokenType::PROP, "Expected 'prop'");
    TypeParams typeParams = parseTypeParams();
    refuseOwnBounds(typeParams, nullptr, start.line);
    std::optional<PropReceiver> prefixReceiver = parseExplicitMemberReceiver();
    if (!prefixReceiver) prefixReceiver = parseTypedMemberReceiver();
    if (!pendingMemberContextReceivers.empty()) {
        pendingMemberContextReceivers.clear();
        fail("a property has one observational receiver; use a function for multiple context receivers at line " +
             str(start.line));
    }
    std::string name = consume(TokenType::IDENTIFIER, "Expected name after 'prop'").lexeme;
    if (check(TokenType::L_BRACKET)) {
        fail("property receivers no longer use brackets; write 'prop &." + name + ": T' or 'prop Type&." + name +
             ": T' at line " + str(peek().line));
    }
    PropReceiver receiver = prefixReceiver ? *prefixReceiver : PropReceiver{"self", nullptr, ParamModifier::SHARED};
    requireObservingReceiver(receiver, name, start.line);
    TypeRef type;
    if (match(TokenType::COLON)) type = parseTypeName();
    // `prop name: T = expr` and `prop name: T { return expr }` are the same declaration.
    Expr init;
    if (check(TokenType::L_BRACE)) {
        advance();
        skipNewlines();
        std::vector<Stmt> body;
        while (!check(TokenType::R_BRACE) && !isAtEnd()) {
            std::vector<Stmt> more = parseStmts();
            body.insert(body.end(), more.begin(), more.end());
            skipNewlines();
        }
        consume(TokenType::R_BRACE, "Expected '}' to close prop body");
        std::vector<StmtReturn*> returned;
        for (const Stmt& stmt : body) {
            if (StmtReturn* r = as<StmtReturn>(stmt)) returned.push_back(r);
        }
        if (body.size() != 1 || returned.size() != 1) {
            fail("a 'prop' body is a single expression: write 'return <expr>' as its only statement, or declare a func at "
                 "line " +
                 str(start.line));
        }
        if (!returned[0]->value) fail("'prop' body must return a value at line " + str(start.line));
        init = returned[0]->value;
    } else {
        consume(TokenType::EQUAL, "Expected '=' or '{' in prop declaration");
        init = parseExpr();
    }
    consumeNewline();
    // Without a receiver type there is no type to extend, so the declaration is
    // a scope-mangled constant instead.
    if (!receiver.type) {
        return make(arena, TopLevelFinDecl{.name = name, .type = type, .initializer = init, .line = start.line,
                                           .column = start.column, .annotations = annotations, .visibility = visibility});
    }
    Param extension;
    extension.name = receiver.name;
    extension.type = receiver.type;
    FuncDecl decl;
    decl.name = name;
    decl.returnType = type ? explicitType(type) : TypeAnnotation{};
    decl.body = {make(arena, StmtReturn{.value = init, .line = init.line(), .column = init.column()})};
    decl.isInline = false;
    decl.typeParams = typeParams.names;
    decl.line = start.line;
    decl.column = start.column;
    decl.annotations = annotations;
    decl.isTask = isTask;
    decl.isReactive = isReactive;
    decl.visibility = visibility;
    decl.receiverModifier = receiver.modifier;
    decl.receiverName = receiver.name;
    decl.extensionReceiver = extension;
    decl.memberCallStyle = MemberCallStyle::PROPERTY;
    return funcOrExtension(decl);
}

TopLevelTypeAlias* Parser::parseTypeAlias(const std::vector<Annotation>& annotations /*= {}*/) {
    Token start = peek();
    consume(TokenType::TYPEALIAS, "Expected 'typealias'");
    std::string name = consume(TokenType::IDENTIFIER, "Expected type alias name").lexeme;
    // Optional type parameters: `typealias Array<T> = …`
    TypeParams tp = parseTypeParams();
    consume(TokenType::EQUAL, "Expected '=' in typealias");
    TypeRef type = parseTypeName();
    consumeNewline();
    return make(arena, TopLevelTypeAlias{name, type, start.line, start.column, annotations, tp.names, tp.boundClause});
}

/**
 * True when a compile-time type property begins here:
 * `[@Deco…] [visibility] deepinline prop<…> Name: Type … { … }`, or a
 * `deepinline func` whose result is `: Type`.
 */
bool Parser::isTypePropAhead() {
    int32_t i = indexAfterAnnotations(current);
    while (typeIs(i, TokenType::EXPOSED) || typeIs(i, TokenType::PROTECTED) || typeIs(i, TokenType::CONFINED)) i++;
    if (!typeIs(i, TokenType::DEEPINLINE)) return false;
    if (typeIs(i + 1, TokenType::PROP)) return true;
    // A type function that takes values is a `func`; what separates it from an
    // ordinary compile-time function is its result.
    if (!typeIs(i + 1, TokenType::FUNC)) return false;
    int32_t j = i + 2;
    if (typeIs(j, TokenType::LESS)) j = indexAfterBalanced(j, TokenType::LESS, TokenType::GREATER);
    if (!typeIs(j, TokenType::IDENTIFIER)) return false;
    j++;
    if (typeIs(j, TokenType::L_PAREN)) j = indexAfterBalanced(j, TokenType::L_PAREN, TokenType::R_PAREN);
    if (!typeIs(j, TokenType::COLON)) return false;
    return lexemeIs(j + 1, "Type");
}

/** Index of the first token after the decorator list starting at [from]. */
int32_t Parser::indexAfterAnnotations(int32_t from) {
    int32_t i = from;
    while (typeIs(i, TokenType::AT)) {
        i++;  // '@'
        if (typeIs(i, TokenType::L_BRACKET)) {
            i = indexAfterBalanced(i, TokenType::L_BRACKET, TokenType::R_BRACKET);
            continue;
        }
        if (!typeIs(i, TokenType::IDENTIFIER)) return i;
        i++;  // decorator name
        if (typeIs(i, TokenType::L_PAREN)) i = indexAfterBalanced(i, TokenType::L_PAREN, TokenType::R_PAREN);
        while (typeIs(i, TokenType::NEWLINE)) i++;
    }
    return i;
}

/** Index just past the bracket pair that opens at [from]. */
int32_t Parser::indexAfterBalanced(int32_t from, TokenType open, TokenType close) {
    int depth = 0;
    int32_t i = from;
    while (i < (int32_t)tokens.size()) {
        TokenType t = tokens[(size_t)i].type;
        if (t == open) depth++;
        if (t == close) {
            depth--;
            if (depth == 0) return i + 1;
        }
        i++;
    }
    return i;
}

/**
 * Parses a compile-time type property (`deepinline prop<...T> promote: Type
 * where … { … }`), a `deepinline` declaration whose result is a *type*.
 */
void Parser::parseTypeProp(const std::string& scopePrefix /*= ""*/) {
    std::vector<Annotation> annotations = parseAnnotations();
    parseVisibility();
    Token start = consume(TokenType::DEEPINLINE, "Expected 'deepinline'");
    bool isFunc = check(TokenType::FUNC);
    if (isFunc) advance();
    else consume(TokenType::PROP, "Expected 'prop' or 'func' after 'deepinline'");
    std::string kind = isFunc ? "func" : "prop";
    if (!check(TokenType::LESS)) {
        fail("type parameters follow '" + kind + "': write 'deepinline " + kind + "<T> name', at line " + str(peek().line));
    }
    int32_t afterParams = indexAfterBalanced(current, TokenType::LESS, TokenType::GREATER);
    const Token* upcoming = tokenOrNull(afterParams);
    std::string upcomingName = upcoming ? upcoming->lexeme : "<type-property>";
    std::vector<TypeFunctionParam> params = parseTypePropParams(upcomingName);
    Token nameToken = consume(TokenType::IDENTIFIER, "Expected type-property name");
    std::string localName = nameToken.lexeme;
    std::string name = scopePrefix.empty() ? localName : scopePrefix + "__" + localName;
    // `(hasDefault: Bool, isNullable: Bool)` - values the computed type depends on.
    std::vector<Param> valueParams;
    if (check(TokenType::L_PAREN)) {
        if (!isFunc) {
            fail("'deepinline prop " + localName + "' takes no value parameters at line " + str(peek().line) +
                 ": a type computation that takes arguments is a 'deepinline func'");
        }
        advance();
        if (!check(TokenType::R_PAREN)) valueParams = parseParams();
        consume(TokenType::R_PAREN, "Expected ')' after the value parameters of 'deepinline func " + localName + "'");
    }
    consume(TokenType::COLON, "Expected ': Type' after 'deepinline " + kind + " " + localName + "'");
    Token resultToken = consume(TokenType::IDENTIFIER, "Expected 'Type' as the result of 'deepinline " + kind + " " + localName + "'");
    if (resultToken.lexeme != "Type") {
        fail("'deepinline " + kind + " " + localName + "' must declare ': Type' - it returns a type, not a value, at line " +
             str(resultToken.line));
    }
    // One `where` grammar for every declaration.
    Expr tfWhereClause = parseWhereClause();
    std::optional<int32_t> minimum = variadicMinLengthOf(tfWhereClause);
    consume(TokenType::L_BRACE, "Expected '{' before the body of 'deepinline " + kind + " " + localName + "'");
    skipNewlines();
    std::vector<TypeFunctionStmt> body = parseTypeFunctionBlock();
    consume(TokenType::R_BRACE, "Expected '}' after the body of 'deepinline " + kind + " " + localName + "'");
    consumeNewline();
    bool returns = false;
    for (const TypeFunctionStmt& stmt : body) {
        if (is<TypeFunctionStmtReturn>(stmt)) returns = true;
    }
    if (!returns) fail("'deepinline " + kind + " " + localName + "' must return a type at line " + str(start.line));
    auto variadicParamOf = [](const std::vector<TypeFunctionParam>& ps) -> std::optional<std::string> {
        std::optional<std::string> found;
        int count = 0;
        for (const TypeFunctionParam& p : ps) {
            if (p.variadic) {
                found = p.name;
                count++;
            }
        }
        return count == 1 ? found : std::nullopt;
    };
    for (const TypeFunctionDecl& existing : typeFunctions) {
        if (existing.name != name) continue;
        bool sameShape = existing.params.size() == params.size();
        if (sameShape) {
            for (size_t k = 0; k < params.size(); k++) {
                if (existing.params[k].variadic != params[k].variadic) sameShape = false;
            }
        }
        if (sameShape && (variadicParamOf(existing.params) || existing.params.size() == params.size())) {
            fail("'deepinline " + kind + " " + localName + "' already has this overload at line " + str(start.line));
        }
    }
    typeFunctions.push_back(
        TypeFunctionDecl{name, params, valueParams, body, minimum, tfWhereClause, start.line, start.column, annotations});
}

}  // namespace azora
