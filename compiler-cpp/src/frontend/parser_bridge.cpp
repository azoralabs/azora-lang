// Parser.kt lines 4160-4876: `oper[]` methods, extensions, receiver bindings,
// `bridge` declarations, `solo`, `graph` and `variant` (slot) declarations.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"

namespace azora {

namespace {

TypeRef unitType(Arena& arena) {
    TypeRefNamed unit;
    unit.name = "Unit";
    unit.synthesized = true;
    return make(arena, unit);
}

const char* const BRIDGE_TARGET_MEMBERS[] = {"Compiler", "C", "ObjectiveC", "WebAssembly"};

bool isBridgeTargetMember(const std::string& name) {
    for (const char* member : BRIDGE_TARGET_MEMBERS) {
        if (name == member) return true;
    }
    return false;
}

}  // namespace

/**
 * `oper[] (i: T): R { … }` or `oper[]= (i: T, v: R) { … }` - index-operator
 * overloads inside an impl, registered as `index` / `indexSet`.
 */
FuncDecl Parser::parseOperMethod(const Token& start, const Visibility& visibility /*= Visibility{}*/) {
    consume(TokenType::OPER, "Expected 'oper'");
    consume(TokenType::L_BRACKET, "Expected '[' after 'oper'");
    consume(TokenType::R_BRACKET, "Expected ']' after 'oper['");
    std::string name = match(TokenType::EQUAL) ? "indexSet" : "index";
    consume(TokenType::L_PAREN, "Expected '(' after oper signature");
    std::vector<Param> params = parseParams();
    consume(TokenType::R_PAREN, "Expected ')' after oper parameters");
    TypeAnnotation returnType;
    if (match(TokenType::COLON)) returnType = explicitType(parseTypeName());
    consume(TokenType::L_BRACE, "Expected '{' before oper body");
    skipNewlines();
    std::vector<Stmt> body;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        std::vector<Stmt> stmts = parseStmts();
        body.insert(body.end(), stmts.begin(), stmts.end());
        skipNewlines();
    }
    consume(TokenType::R_BRACE, "Expected '}' after oper body");
    consumeNewline();
    FuncDecl decl;
    decl.name = name;
    decl.params = params;
    decl.returnType = returnType;
    decl.body = body;
    decl.isInline = false;
    decl.line = start.line;
    decl.column = start.column;
    decl.visibility = visibility;
    return decl;
}

/**
 * Wraps a parsed function as either a plain TopLevel.Func or, when it declares
 * a receiver, an extension TopLevel.Impl on the receiver's type.
 */
TopLevel Parser::funcOrExtension(const FuncDecl& decl) {
    if (!decl.extensionReceiver) return make(arena, TopLevelFunc{decl});
    const Param& recv = *decl.extensionReceiver;
    // A receiver already carries its borrow, so trust what the declaration says.
    std::string typeName;
    ParamModifier modifier;
    if (TypeRefReference* reference = as<TypeRefReference>(recv.type)) {
        typeName = namedTypeName(reference->inner);
        modifier = paramModifier(reference->kind);
    } else {
        typeName = namedTypeName(recv.type);
        modifier = decl.receiverModifier;
    }
    // `func<T> Box<T>.unwrap(): T` means what `impl Box<T> { func .unwrap(): T }`
    // means: the receiver's own arguments are the implementation's parameters.
    // Only a parameter the receiver binds completely moves.
    TypeRef targetRef = recv.type;
    if (TypeRefReference* reference = as<TypeRefReference>(recv.type)) targetRef = reference->inner;
    TypeRefNamed* target = as<TypeRefNamed>(targetRef);
    OrderedSet<std::string> bounded;
    std::function<void(const Expr&)> collectBounded = [&](const Expr& clause) {
        if (ExprIsCheck* isCheck = as<ExprIsCheck>(clause)) {
            if (ExprIdentifier* id = as<ExprIdentifier>(isCheck->expr)) bounded.add(id->name);
        } else if (ExprBinary* binary = as<ExprBinary>(clause)) {
            collectBounded(binary->left);
            collectBounded(binary->right);
        } else if (ExprGrouping* grouping = as<ExprGrouping>(clause)) {
            collectBounded(grouping->expr);
        }
    };
    collectBounded(decl.whereClause);
    std::vector<std::string> receiverParams;
    if (target) {
        std::vector<std::optional<std::string>> names;
        for (const TypeRef& arg : target->args) {
            TypeRefNamed* named = as<TypeRefNamed>(arg);
            if (named && named->args.empty() && !named->qualifier && !named->variadic) names.push_back(named->name);
            else names.push_back(std::nullopt);
        }
        bool ok = !names.empty();
        OrderedSet<std::string> unique;
        for (const auto& name : names) {
            if (!name || !contains(decl.typeParams, *name) || bounded.contains(*name)) ok = false;
            if (name) unique.add(*name);
        }
        if (ok && unique.size() == names.size()) {
            for (const auto& name : names) receiverParams.push_back(*name);
        }
    }
    FuncDecl method = decl;
    method.receiverModifier = modifier;
    method.receiverName = recv.name;
    method.extensionReceiver = std::nullopt;
    std::vector<std::string> remaining;
    for (const std::string& param : decl.typeParams) {
        if (!contains(receiverParams, param)) remaining.push_back(param);
    }
    method.typeParams = remaining;
    TopLevelImpl impl;
    impl.typeName = typeName;
    impl.methods = {method};
    impl.line = decl.line;
    impl.column = decl.column;
    impl.isExtension = true;
    impl.typeParams = receiverParams;
    return makeImpl(impl);
}

/** The bare type name of a (possibly generic) named type ref, for extension targets. */
std::string Parser::namedTypeName(const TypeRef& t) {
    if (TypeRefNamed* named = as<TypeRefNamed>(t)) return named->name;
    if (TypeRefReference* reference = as<TypeRefReference>(t)) return namedTypeName(reference->inner);
    fail("Extension receiver must be a named type at line " + str(peek().line));
}

/** An impl receiver is an exclusive borrow unless a postfix sigil says otherwise. */
ParamModifier Parser::parseImplReceiverModifier() { return ParamModifier::EXCLUSIVE; }

/** New postfix receiver/operand borrow: `self&`/`x&` → immutable, `self!`/`x!` → mutable. */
ParamModifier Parser::parsePostfixReceiverModifier() {
    if (match(TokenType::AMP)) return ParamModifier::SHARED;
    if (match(TokenType::BANG)) return ParamModifier::EXCLUSIVE;
    return ParamModifier::EXCLUSIVE;
}

/**
 * Parses the `self` receiver binding - `self&`, `self!` or plain `self` -
 * consuming through the receiver name. The receiver is always named `self`.
 */
Parser::ReceiverBinding Parser::parseReceiverBinding(const std::string& what /*= "receiver"*/) {
    std::string name = consumeIdentifierLike("Expected " + what + " name");
    if (name != "self") fail("Expected " + what + " to be named 'self' at line " + str(peek().line));
    return ReceiverBinding{"self", parsePostfixReceiverModifier()};
}

/**
 * Parses the operands that can follow the receiver in a `{ self&, … -> }` block:
 * `, name: Type` or `, name[&|!]`.
 */
void Parser::parseReceiverOperands(std::vector<Param>& params) {
    while (match(TokenType::COMMA)) {
        std::string name = consumeIdentifierLike("Expected operand name in receiver block");
        Param param;
        param.name = name;
        if (match(TokenType::COLON)) {
            param.type = parseTypeName();
        } else {
            TypeRefNamed any;
            any.name = "Any";
            any.synthesized = true;
            param.type = make(arena, any);
            param.modifier = parsePostfixReceiverModifier();
        }
        params.push_back(param);
    }
}

/** A spec callback receiver carries its borrow as a postfix sigil on `self`. */
ParamModifier Parser::parseSpecReceiverModifier() { return ParamModifier::EXCLUSIVE; }

/** After `bridge`, whether an optional target (`.C` or `Target.C`) is followed by `func`. */
bool Parser::isBridgeFuncAhead() {
    int32_t i = current + 1;
    if (typeIs(i, TokenType::DOT)) {
        i++;
        if (typeIs(i, TokenType::IDENTIFIER)) i++;
    } else if (typeIs(i, TokenType::IDENTIFIER) && typeIs(i + 1, TokenType::DOT)) {
        i += 2;
        if (typeIs(i, TokenType::IDENTIFIER)) i++;
    } else if (typeIs(i, TokenType::IDENTIFIER) && bridgeTargetConstants.contains(tokens[(size_t)i].lexeme)) {
        // A constant bound to a `Target` member is a target too.
        i++;
    }
    return typeIs(i, TokenType::FUNC);
}

/**
 * The target of a `bridge`, which is always a `Target` value: `.C`,
 * `Target.C`, or a compile-time constant already bound to one.
 */
std::string Parser::parseBridgeTarget() {
    Token at = peek();
    if (match(TokenType::DOT)) {
        std::string member =
            consume(TokenType::IDENTIFIER, "Expected bridge target after '.' (e.g. '.C', '.WebAssembly')").lexeme;
        return checkedBridgeTarget(member, at);
    }
    std::string firstName =
        consume(TokenType::IDENTIFIER, "Expected bridge target ('.C', 'Target.C', or a constant bound to one)").lexeme;
    // `Target.C` - the enum may be reached through its scope.
    if (check(TokenType::DOUBLE_COLON)) {
        while (match(TokenType::DOUBLE_COLON)) consume(TokenType::IDENTIFIER, "Expected a name after '::' in a bridge target");
        consume(TokenType::DOT, "Expected '.' after the Target enum in a bridge target");
        std::string member = consume(TokenType::IDENTIFIER, "Expected bridge target variant after '.'").lexeme;
        return checkedBridgeTarget(member, at);
    }
    // `Target.C` - the leading name is the enum qualifier; keep the member.
    if (match(TokenType::DOT)) {
        std::string member = consume(TokenType::IDENTIFIER, "Expected bridge target variant after '.'").lexeme;
        return checkedBridgeTarget(member, at);
    }
    // A lone identifier is only a target if it is a compile-time constant
    // already bound to a `Target` member.
    if (const std::string* bound = bridgeTargetConstants.get(firstName)) return *bound;
    fail("'" + firstName + "' is not a bridge target at line " + str(at.line) + " - write '." + firstName +
         "' for the Target member, 'Target." + firstName + "' in full, or bind it first with 'inline fin " + firstName +
         " = Target.<Variant>'");
}

/** [member] if it names a `Target`; otherwise a parse error naming the real ones. */
std::string Parser::checkedBridgeTarget(const std::string& member, const Token& at) {
    if (isBridgeTargetMember(member)) return member;
    std::vector<std::string> names;
    for (const char* m : BRIDGE_TARGET_MEMBERS) names.push_back(std::string(".") + m);
    fail("'" + member + "' is not a member of Target at line " + str(at.line) + " - a bridge target is one of " +
         join(names, ", "));
}

/**
 * A type name that may be reached through its scope, returned in the mangled
 * form the rest of the frontend uses (`std__Int`).
 */
std::string Parser::parseQualifiedTypeName(const std::string& message) {
    std::string name = consume(TokenType::IDENTIFIER, message).lexeme;
    while (match(TokenType::DOUBLE_COLON)) {
        name += "__";
        name += consume(TokenType::IDENTIFIER, message).lexeme;
    }
    return name;
}

/**
 * Parses one bridge declaration name: `clock`, `"clock_gettime" clockGettime`
 * (the post-expansion generated form), or `@foreignName("clock_gettime")`.
 */
Parser::ParsedBridgeName Parser::parseBridgeName() {
    if (check(TokenType::STRING_LITERAL)) {
        std::string backend = literalString(advance());
        std::string local = consume(TokenType::IDENTIFIER, "Expected local Azora name after foreign bridge symbol").lexeme;
        return ParsedBridgeName{backend, local};
    }
    if (check(TokenType::AT) && isMacroInvokeAhead()) {
        Token at = advance();
        std::string macroName = parseQualifiedMacroName();
        std::vector<Expr> args = parseMacroInvokeArgs();
        usedMetaInvoke = true;
        ParsedBridgeName parsed;
        parsed.backendName = "__bridgeName" + str(bridgeNamePlaceholder++);
        parsed.macro = make(arena, ExprMetaInvoke{macroName, args, at.line, at.column, u16len(macroName) + 1});
        return parsed;
    }
    std::string declared = consumeIdentifierLike("Expected bridge declaration name");
    while (check(TokenType::DOT) && peekNextIs(TokenType::IDENTIFIER)) {
        advance();
        declared += ".";
        declared += advance().lexeme;
    }
    return ParsedBridgeName{declared};
}

/**
 * An `inline fin`'s written type as its uses read it: `Self` inside an `impl`
 * is that impl's type.
 */
TypeRef Parser::inlineFinType(const TypeRef& type) {
    TypeRefNamed* named = as<TypeRefNamed>(type);
    if (named && named->name == "Self" && named->args.empty() && currentImplTypeName) {
        TypeRefNamed impl;
        impl.name = *currentImplTypeName;
        return make(arena, impl);
    }
    return type;
}

/** Records `fin`/`inline fin X = Target.C` so `bridge X { … }` can resolve. */
void Parser::noteBridgeTargetConstant(const std::string& name, const Expr& initializer) {
    ExprMember* member = as<ExprMember>(initializer);
    if (member == nullptr) return;
    ExprIdentifier* qualifier = as<ExprIdentifier>(member->target);
    if (qualifier == nullptr) return;
    // `Target.C` reaches here scope-mangled, so the enum is matched by its own name.
    std::string enumName = substringAfterLast(qualifier->name, "__");
    if (enumName == "Target" && isBridgeTargetMember(member->name)) bridgeTargetConstants.set(name, member->name);
}

/**
 * `bridge .Target func name(params): Ret` - a single bodyless extern function.
 * Shorthand for a one-entry `bridge` block; the default target is `Compiler`.
 */
TopLevelBridge* Parser::parseBridgeFunc(const std::vector<Annotation>& annotations /*= {}*/) {
    Token start = consume(TokenType::BRIDGE, "Expected 'bridge'");
    // Bare `bridge func …` defaults to the compiler-provided target.
    std::string target = check(TokenType::FUNC) ? "Compiler" : parseBridgeTarget();
    consume(TokenType::FUNC, "Expected 'func' after 'bridge'");
    TypeParams tpBefore = parseTypeParams();
    ParsedBridgeName parsedName = parseBridgeName();
    TypeParams tpAfter = parseTypeParams();
    refuseBounds(tpBefore, "a bridge function", start.line);
    refuseBounds(tpAfter, "a bridge function", start.line);
    consume(TokenType::L_PAREN, "Expected '(' after bridge function name");
    std::vector<Param> params = parseParams();
    consume(TokenType::R_PAREN, "Expected ')' after bridge parameters");
    TypeRef returnType = match(TokenType::COLON) ? parseTypeName() : unitType(arena);
    consumeNewline();
    BridgeSig sig;
    sig.name = parsedName.backendName;
    sig.params = params;
    sig.returnType = returnType;
    sig.line = start.line;
    sig.column = start.column;
    sig.typeParams = concat(tpBefore.names, tpAfter.names);
    sig.localName = parsedName.localName;
    sig.nameMacro = parsedName.macro;
    return make(arena, TopLevelBridge{target, {sig}, start.line, start.column, annotations});
}

/**
 * True for `bridge prop name[Receiver&]: T` - a member declared on a type
 * from outside it (no `:` inside the brackets).
 */
bool Parser::isBridgeReceiverPropAhead() {
    int32_t i = current + 1;
    while (typeIs(i, TokenType::INLINE) || typeIs(i, TokenType::DEEPINLINE)) i++;
    if (!typeIs(i, TokenType::PROP)) return false;
    i++;
    if (!typeIs(i, TokenType::IDENTIFIER)) return false;
    i++;
    if (!typeIs(i, TokenType::L_BRACKET)) return false;
    int depth = 0;
    while (i < (int32_t)tokens.size()) {
        TokenType t = tokens[(size_t)i].type;
        if (t == TokenType::L_BRACKET) {
            depth++;
        } else if (t == TokenType::R_BRACKET) {
            depth--;
            if (depth == 0) return true;
        } else if (t == TokenType::COLON) {
            if (depth == 1) return false;
        } else if (t == TokenType::EOF_) {
            return false;
        }
        i++;
    }
    return false;
}

/**
 * `bridge prop typeName[Type&]: String` - a compiler-provided member of the
 * type named in the brackets.
 */
TopLevel Parser::parseBridgeReceiverProp(const std::vector<Annotation>& annotations) {
    Token start = advance();  // 'bridge'
    bool isDeep = check(TokenType::DEEPINLINE);
    bool isInline = match(TokenType::INLINE) || match(TokenType::DEEPINLINE);
    consume(TokenType::PROP, "Expected 'prop' after 'bridge'");
    std::string name = consumeIdentifierLike("Expected the property's name");
    consume(TokenType::L_BRACKET, "Expected '[' before the receiver type");
    TypeRef receiver = parseTypeName();
    consume(TokenType::R_BRACKET, "Expected ']' after the receiver type");
    TypeAnnotation type;
    if (match(TokenType::COLON)) {
        skipNewlines();
        type = explicitType(parseTypeName());
    }
    consumeNewline();
    FuncDecl decl;
    decl.name = name;
    decl.returnType = type;
    decl.isInline = isInline;
    decl.line = start.line;
    decl.column = start.column;
    decl.annotations = annotations;
    decl.receiverModifier = receiverModifierOf(receiver);
    decl.memberCallStyle = MemberCallStyle::PROPERTY;
    decl.isDeepInline = isDeep;
    TopLevelImpl impl;
    impl.typeName = receiverTypeName(receiver);
    impl.methods = {decl};
    impl.line = start.line;
    impl.column = start.column;
    impl.isBridge = true;
    impl.hasBody = true;
    return makeImpl(impl);
}

/** The declared name of a receiver type, past any borrow it was written with. */
std::string Parser::receiverTypeName(const TypeRef& ref) {
    if (TypeRefNamed* named = as<TypeRefNamed>(ref)) return named->name;
    if (TypeRefReference* reference = as<TypeRefReference>(ref)) return receiverTypeName(reference->inner);
    fail("A bridge receiver must name a type");
}

/** The borrow a receiver type was written with. */
ParamModifier Parser::receiverModifierOf(const TypeRef& ref) {
    if (TypeRefReference* reference = as<TypeRefReference>(ref)) return paramModifier(reference->kind);
    return ParamModifier::NONE;
}

TopLevelBridge* Parser::parseBridge(const std::vector<Annotation>& annotations /*= {}*/) {
    Token start = consume(TokenType::BRIDGE, "Expected 'bridge'");
    // Target forms: `bridge { … }` (Compiler), `bridge .C { … }`,
    // `bridge (.C, .WebAssembly) { … }` (stored comma-joined).
    std::string target;
    if (match(TokenType::L_PAREN)) {
        std::vector<std::string> targets;
        do {
            targets.push_back(parseBridgeTarget());
        } while (match(TokenType::COMMA));
        consume(TokenType::R_PAREN, "Expected ')' after bridge targets");
        target = join(targets, ",");
    } else if (check(TokenType::L_BRACKET)) {
        fail("bridge targets are listed in parentheses at line " + str(peek().line) +
             " - write 'bridge (.C, .WebAssembly) { … }', not 'bridge [.C, .WebAssembly]'");
    } else if (check(TokenType::L_BRACE)) {
        target = "Compiler";  // bare `bridge { … }` → default target
    } else {
        target = parseBridgeTarget();
    }
    consume(TokenType::L_BRACE, "Expected '{' after bridge target");
    skipNewlines();
    std::vector<BridgeSig> funcs;
    std::vector<BridgeValue> values;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        if (match(TokenType::FUNC)) {
            ParsedBridgeName name = parseBridgeName();
            consume(TokenType::L_PAREN, "Expected '('");
            std::vector<Param> params = parseParams();
            consume(TokenType::R_PAREN, "Expected ')'");
            TypeRef returnType = match(TokenType::COLON) ? parseTypeName() : unitType(arena);
            consumeNewline();
            BridgeSig sig;
            sig.name = name.backendName;
            sig.params = params;
            sig.returnType = returnType;
            sig.line = start.line;
            sig.column = start.column;
            sig.localName = name.localName;
            sig.nameMacro = name.macro;
            funcs.push_back(sig);
        } else if (check(TokenType::FIN) || check(TokenType::LET) || check(TokenType::VAR)) {
            Token kind = advance();
            ParsedBridgeName name = parseBridgeName();
            consume(TokenType::COLON, "Expected ':' after bridge value name");
            TypeRef type = parseTypeName();
            consume(TokenType::EQUAL, "Expected '=' and an initializer for bridge value");
            Expr initializer = parseExpr();
            consumeNewline();
            BridgeValue value;
            value.name = name.localName ? *name.localName : name.backendName;
            value.type = type;
            value.initializer = initializer;
            value.mutable_ = kind.type == TokenType::VAR;
            value.isLet = kind.type == TokenType::LET;
            value.line = kind.line;
            value.column = kind.column;
            if (name.localName) value.foreignName = name.backendName;
            value.nameMacro = name.macro;
            values.push_back(value);
        } else {
            fail("Expected 'func', 'fin', 'let', or 'var' in bridge block at line " + str(peek().line));
        }
    }
    consume(TokenType::R_BRACE, "Expected '}' after bridge functions");
    consumeNewline();
    return make(arena, TopLevelBridge{target, funcs, start.line, start.column, annotations, values});
}

/** `solo pack Name { fields }` - a pack there is one of. */
TopLevelSolo* Parser::parseSolo(const Visibility& visibility /*= Visibility{}*/,
                                const std::vector<Annotation>& annotations /*= {}*/) {
    Token start = consume(TokenType::SOLO, "Expected 'solo'");
    // `solo` is a modifier on `pack`, not a declaration of its own.
    consume(TokenType::PACK, "Expected 'pack' after 'solo' - a singleton is written 'solo pack Name'");
    std::string name = consume(TokenType::IDENTIFIER, "Expected the pack's name").lexeme;
    std::vector<PackField> fields;
    std::vector<FuncDecl> methods;
    // `solo pack Alloc` - a singleton that carries no state.
    if (!check(TokenType::L_BRACE)) {
        consumeNewline();
        return make(arena, TopLevelSolo{name, fields, methods, start.line, start.column, visibility, annotations});
    }
    consume(TokenType::L_BRACE, "Expected '{' after solo name");
    skipNewlines();
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        std::vector<Annotation> memberAnnotations = parseAnnotations();
        Visibility memberVisibility = parseVisibility();
        // `solo pack` is a pack: it holds fields, and its members live in an `impl`.
        if (check(TokenType::FUNC)) {
            fail("A 'solo pack' holds fields; put its members in 'impl " + name + " { … }' at line " + str(peek().line));
        }
        fields.push_back(parsePackField({.preparsedVisibility = memberVisibility, .preparsedAnnotations = memberAnnotations}));
        skipNewlines();
    }
    consume(TokenType::R_BRACE, "Expected '}' after solo body");
    consumeNewline();
    return make(arena, TopLevelSolo{name, fields, methods, start.line, start.column, visibility, annotations});
}

/**
 * `graph Name { <lifetime> Type(args) [binds Spec] … }` - a module that wires
 * singletons with their construction arguments.
 */
TopLevelGraph* Parser::parseGraph() {
    Token start = consume(TokenType::GRAPH, "Expected 'graph'");
    std::string name = consume(TokenType::IDENTIFIER, "Expected graph name").lexeme;
    // `graph AppGraph includes (NetworkGraph, DataGraph) { … }` - composition.
    std::vector<std::string> included;
    if (match(TokenType::INCLUDES)) {
        if (match(TokenType::L_PAREN)) {
            do {
                included.push_back(consume(TokenType::IDENTIFIER, "Expected a graph name in the includes list").lexeme);
            } while (match(TokenType::COMMA));
            consume(TokenType::R_PAREN, "Expected ')' after the includes tuple");
        } else {
            included.push_back(consume(TokenType::IDENTIFIER, "Expected a graph name after 'includes'").lexeme);
        }
    }
    consume(TokenType::L_BRACE, "Expected '{' after graph name");
    skipNewlines();
    std::vector<GraphReg> registrations;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        // The lifetime is the entry's first word.
        Token entry = peek();
        ProviderLifetime lifetime;
        if (match(TokenType::SOLO)) {
            lifetime = ProviderLifetime::SOLO;
        } else if (match(TokenType::FACTORY)) {
            lifetime = ProviderLifetime::FACTORY;
        } else if (match(TokenType::SCOPED)) {
            lifetime = ProviderLifetime::SCOPED;
        } else if (check(TokenType::SCOPE)) {
            fail("A graph's per-scope lifetime is written 'scoped' at line " + str(entry.line) +
                 "; 'scope' declares a namespace");
        } else {
            fail("A 'graph' entry starts with its lifetime at line " + str(entry.line) +
                 " - 'solo' for one per graph, 'factory' for one per resolution, 'scoped' for one per active scope");
        }
        std::string typeName =
            consume(TokenType::IDENTIFIER, std::string("Expected a type name after '") + spelling(lifetime) + "'").lexeme;
        std::vector<Expr> args;
        if (match(TokenType::L_PAREN)) {
            if (!check(TokenType::R_PAREN)) {
                do {
                    args.push_back(parseExpr());
                } while (match(TokenType::COMMA));
            }
            consume(TokenType::R_PAREN, "Expected ')' after the registration's arguments");
        }
        // `binds Spec` / `binds (A, B)` - the specs this definition also answers.
        std::vector<std::string> bindSpecs;
        if (match(TokenType::BINDS)) {
            if (match(TokenType::L_PAREN)) {
                do {
                    bindSpecs.push_back(consume(TokenType::IDENTIFIER, "Expected a spec name in the binds list").lexeme);
                } while (match(TokenType::COMMA));
                consume(TokenType::R_PAREN, "Expected ')' after the binds tuple");
            } else {
                bindSpecs.push_back(consume(TokenType::IDENTIFIER, "Expected a spec name after 'binds'").lexeme);
            }
        }
        registrations.push_back(GraphReg{typeName, args, bindSpecs, lifetime, entry.line, entry.column});
        consumeNewline();
    }
    consume(TokenType::R_BRACE, "Expected '}' after graph body");
    consumeNewline();
    return make(arena, TopLevelGraph{name, registrations, start.line, start.column, included});
}

/** `variant enum Name { … }` / `variant error Name { … }` - a tagged union. */
TopLevelSlot* Parser::parseSlot(const std::vector<Annotation>& annotations /*= {}*/) {
    Token start = peek();
    consume(TokenType::VARIANT, "Expected 'variant'");
    std::string kind;
    if (match(TokenType::ENUM)) {
        kind = "enum";
    } else if (match(TokenType::ERROR)) {
        kind = "error";
    } else {
        fail("'variant' is a modifier: write 'variant enum " + peek().lexeme + "' for a tagged union, or 'variant error " +
             peek().lexeme + "' for one that can be thrown, at line " + str(peek().line));
    }
    std::string name = consume(TokenType::IDENTIFIER, "Expected name after 'variant " + kind + "'").lexeme;
    // `variant enum Option<T>` - the parameters go where every other declaration puts them.
    TypeParams slotGenerics = parseTypeParams();
    std::vector<std::string> slotTypeParams = slotGenerics.names;
    consume(TokenType::L_BRACE, "Expected '{' after 'variant " + kind + " " + name + "'");
    skipNewlines();
    std::vector<SlotVariant> variants;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        std::string vname = consume(TokenType::IDENTIFIER, "Expected case name").lexeme;
        std::vector<TypeRef> payloadTypes;
        if (match(TokenType::L_PAREN)) {
            if (!check(TokenType::R_PAREN)) {
                do {
                    // `Ok(value: Int)` - an optional field name before the type is accepted.
                    if (check(TokenType::IDENTIFIER) && peekNextIs(TokenType::COLON)) {
                        advance();
                        advance();
                    }
                    payloadTypes.push_back(parseTypeName());
                } while (match(TokenType::COMMA));
            }
            consume(TokenType::R_PAREN, "Expected ')' after payload types");
        }
        consumeNewline();
        variants.push_back(SlotVariant{vname, payloadTypes});
    }
    consume(TokenType::R_BRACE, "Expected '}' after the cases of '" + name + "'");
    consumeNewline();
    return make(arena, TopLevelSlot{name, variants, start.line, start.column, annotations, kind == "error",
                                    slotTypeParams, slotGenerics.boundClause});
}

}  // namespace azora
