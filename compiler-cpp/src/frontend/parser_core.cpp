// Parser.kt lines 1-1000 (class header, parse(), scopes, namespaces, operator
// names, visibility) and 13020-13083 (token helpers).
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/callback_impl_normalizer.h"
#include "frontend/lexer.h"
#include "frontend/scope_access_rewriter.h"
#include "frontend/source_symbol_validator.h"

namespace azora {

// static
FragmentMacroTables& Parser::fragmentTables() {
    static FragmentMacroTables tables;
    return tables;
}

// static
void Parser::resetFragmentMacros() {
    FragmentMacroTables& t = fragmentTables();
    t.fragmentMacros.clear();
    t.fragmentMacroParams.clear();
    t.infixFragmentMacros.clear();
}

Parser::Parser(Arena& arena, std::vector<Token> initialTokens, TypeListEnv* typeListEnv, EnumEnv* declaredEnums,
               bool internalSource, TypeListScopes* typeListScope, bool allowWhenExpressions)
    : arena(arena),
      typeListEnv(typeListEnv ? typeListEnv : &ownTypeListEnv),
      declaredEnums(declaredEnums ? declaredEnums : &ownDeclaredEnums),
      internalSource(internalSource),
      typeListScope(typeListScope ? typeListScope : &ownTypeListScope),
      allowWhenExpressions(allowWhenExpressions),
      tokens(std::move(initialTokens)) {
    if (!internalSource) SourceSymbolValidator::validateTokens(tokens);
}

/**
 * The compile-time list [name] holds, as its elements must be written here.
 *
 * A list declares its elements in whatever scope it is written in. Read from
 * another scope the same elements have to carry the qualifier, or splicing one
 * into type position yields a name that resolves nowhere.
 */
std::optional<std::vector<std::string>> Parser::comptimeList(const std::string& name) {
    const std::vector<std::string>* values = typeListEnv->get(name);
    if (values == nullptr) return std::nullopt;
    const std::string* scope = typeListScope->get(name);
    std::string declaring = scope ? *scope : "";
    if (declaring.empty() || declaring == typeFunctionNamespacePrefix) return *values;
    std::string qualifier = replaceAll(declaring, "__", "::");
    std::vector<std::string> out;
    for (const std::string& it : *values) out.push_back(contains(it, "::") ? it : qualifier + "::" + it);
    return out;
}

/** Builds the ScopeMeta chain for the current scopeStack (innermost outward). */
const ScopeMeta* Parser::currentScopeMeta() {
    const ScopeMeta* meta = nullptr;
    for (const ScopeFrame& frame : scopeStack) meta = make(arena, ScopeMeta{frame.label, frame.isInline, meta});
    return meta;
}

/** Records the current scope for each named declaration in [items] (innermost wins). */
void Parser::recordScopeMembers(const std::vector<TopLevel>& items) {
    const ScopeMeta* meta = currentScopeMeta();
    if (meta == nullptr) return;
    for (const TopLevel& item : items) {
        std::optional<std::string> name = declaredTopLevelName(item);
        if (!name) continue;
        if (!scopeMetaByName.contains(*name)) scopeMetaByName.set(*name, *meta);
    }
}

std::optional<std::string> Parser::declaredTopLevelName(const TopLevel& item) {
    switch (item.kind()) {
        case TopLevelKind::Func: return cast<TopLevelFunc>(item).decl.name;
        case TopLevelKind::FinDecl: return cast<TopLevelFinDecl>(item).name;
        case TopLevelKind::LetDecl: return cast<TopLevelLetDecl>(item).name;
        case TopLevelKind::VarDecl: return cast<TopLevelVarDecl>(item).name;
        case TopLevelKind::Pack: return cast<TopLevelPack>(item).name;
        case TopLevelKind::Enum: return cast<TopLevelEnum>(item).name;
        case TopLevelKind::Fail: return cast<TopLevelFail>(item).name;
        case TopLevelKind::Spec: return cast<TopLevelSpec>(item).name;
        case TopLevelKind::Deco: return cast<TopLevelDeco>(item).name;
        case TopLevelKind::Slot: return cast<TopLevelSlot>(item).name;
        case TopLevelKind::Solo: return cast<TopLevelSolo>(item).name;
        case TopLevelKind::TypeAlias: return cast<TopLevelTypeAlias>(item).name;
        default: return std::nullopt;
    }
}

/**
 * True when a labeled/anonymous scope block begins here: an optional
 * `inline`/`deepinline`, then `scope`, then a string label or `{`.
 */
bool Parser::isScopeBlockAhead() {
    int32_t i = current;
    if (typeIs(i, TokenType::INLINE) || typeIs(i, TokenType::DEEPINLINE)) i++;
    if (!typeIs(i, TokenType::SCOPE)) return false;
    return typeIs(i + 1, TokenType::STRING_LITERAL) || typeIs(i + 1, TokenType::L_BRACE);
}

/**
 * `[inline|deepinline] scope ["label"] { … }` - a labeled or anonymous scope.
 * Members keep their top-level names; the scope only attaches reflection
 * metadata and, for the inline/deepinline forms, compile-time block semantics.
 */
std::vector<TopLevel> Parser::parseScopeBlock() {
    Token start = peek();
    bool ownInline;
    if (match(TokenType::DEEPINLINE)) ownInline = true;
    else if (match(TokenType::INLINE)) ownInline = true;
    else ownInline = false;
    bool isDeep = start.type == TokenType::DEEPINLINE;
    consume(TokenType::SCOPE, "Expected 'scope'");
    std::optional<std::string> label;
    if (check(TokenType::STRING_LITERAL)) label = literalString(advance());
    // Inline-ness is inherited: a plain scope nested in an inline/deepinline
    // scope is itself inline.
    bool isInline = ownInline || (!scopeStack.empty() && scopeStack.back().isInline);
    consume(TokenType::L_BRACE, "Expected '{' to open the scope");
    skipNewlines();
    scopeStack.push_back(ScopeFrame{label, isInline});
    std::vector<TopLevel> body;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        if (isScopeBlockAhead()) {
            std::vector<TopLevel> inner = parseScopeBlock();
            body.insert(body.end(), inner.begin(), inner.end());
        } else if (isScopeNamespaceAhead()) {
            std::vector<TopLevel> inner = parseScopeNamespace();
            body.insert(body.end(), inner.begin(), inner.end());
        } else if (isTypePropAhead()) {
            parseTypeProp();
        } else if (isInline) {
            body.push_back(parseTopLevelBlockItem(true));
        } else {
            body.push_back(parseTopLevel());
        }
        skipNewlines();
    }
    recordScopeMembers(body);
    scopeStack.pop_back();
    consume(TokenType::R_BRACE, "Expected '}' to close scope");
    consumeNewline();
    if (!ownInline) return body;
    if (isDeep) return {make(arena, TopLevelDeepInlineBlock{body, start.line, start.column})};
    return {make(arena, TopLevelInlineBlock{body, start.line, start.column})};
}

/**
 * Parses the token stream into a Program AST.
 *
 * Consumes all tokens from the input list, starting with an optional
 * `module` declaration followed by zero or more top-level items.
 */
Program Parser::parse() {
    skipNewlines();
    bool isExported = false;
    ModuleVisibility moduleVisibility = ModuleVisibility::PUBLIC;
    Expr exportCondition;
    // A decorator above the header decorates the *module* - the `.Module`
    // target. The same rows above anything else belong to the declaration
    // that follows, so they are read here only when a header really does
    // follow; otherwise the scan is rewound.
    int32_t beforeAnnotations = current;
    std::vector<Annotation> leadingAnnotations;
    if (check(TokenType::AT)) leadingAnnotations = parseAnnotations();
    std::vector<Annotation> moduleAnnotations;
    if (isModuleHeaderAhead()) moduleAnnotations = leadingAnnotations;
    if (!leadingAnnotations.empty() && moduleAnnotations.empty()) current = beforeAnnotations;
    std::optional<std::string> moduleName;
    if (isModuleHeaderAhead()) {
        // `exposed module x` - auto-imported everywhere, rather than only where
        // it is asked for.
        if (match(TokenType::EXPOSED)) isExported = true;
        if (check(TokenType::IF)) {
            // `exposed if COND \n module …` - comptime-conditional auto-import.
            advance();  // 'if'
            exportCondition = parseExpr();
            consume(TokenType::NEWLINE, "Expected a newline after 'exposed if <condition>'");
        } else {
            if (match(TokenType::CONFINED)) moduleVisibility = ModuleVisibility::CONFINED;
            else moduleVisibility = ModuleVisibility::PUBLIC;
            if (isExported && moduleVisibility == ModuleVisibility::CONFINED) {
                fail("'exposed confined module' is contradictory: a confined module is package-private and cannot be auto-imported everywhere");
            }
        }
        moduleName = parseModule();
    }
    std::vector<TopLevel> items;
    while (!isAtEnd()) {
        skipNewlines();
        if (isAtEnd()) break;
        if (isTestScopeAhead()) {
            // `[exposed|protected|confined] test scope { … }` - declarations
            // that exist only for tests.
            std::vector<TopLevel> inner = parseTestScope();
            items.insert(items.end(), inner.begin(), inner.end());
        } else if (isScopeNamespaceAhead()) {
            // `scope Name { … }` - a named namespace whose members are always
            // qualified outside that scope.
            std::vector<TopLevel> inner = parseScopeNamespace();
            items.insert(items.end(), inner.begin(), inner.end());
        } else if (isScopeBlockAhead()) {
            // `[inline|deepinline] scope ["label"] { … }`.
            std::vector<TopLevel> inner = parseScopeBlock();
            items.insert(items.end(), inner.begin(), inner.end());
        } else if (isTypePropAhead()) {
            parseTypeProp();
        } else {
            items.push_back(parseTopLevel());
        }
        if (!pendingTopLevels.empty()) {
            items.insert(items.end(), pendingTopLevels.begin(), pendingTopLevels.end());
            pendingTopLevels.clear();
        }
    }
    // `impl pack Name { … }` in a file that declares no `Name` declares it: a
    // pack with no fields, carrying the annotations written on the impl, so a
    // stateless type is said once (PACK_IMPL_DIP §2.4).
    {
        OrderedSet<std::string> declared;
        for (const TopLevel& item : items) {
            if (TopLevelPack* p = as<TopLevelPack>(item)) declared.add(p->name);
            else if (TopLevelEnum* e = as<TopLevelEnum>(item)) declared.add(e->name);
        }
        size_t index = 0;
        while (index < items.size()) {
            TopLevel item = items[index];
            TopLevelImpl* impl = as<TopLevelImpl>(item);
            if (impl && impl->isPackImpl && !impl->traitName && declared.add(impl->typeName)) {
                TopLevelImpl copy = *impl;
                copy.annotations = {};
                items[index] = make(arena, copy);
                TopLevelPack pack;
                pack.name = impl->typeName;
                pack.typeParams = impl->typeParams;
                pack.line = impl->line;
                pack.column = impl->column;
                pack.annotations = impl->annotations;
                pack.declaredByImpl = true;
                items.insert(items.begin() + (std::ptrdiff_t)index, TopLevel(make(arena, pack)));
                index++;
            }
            index++;
        }
    }
    // Stamp each pack and impl with the module it was written in. A private
    // member is reachable only from its own module.
    for (TopLevel& item : items) {
        if (TopLevelPack* p = as<TopLevelPack>(item)) {
            TopLevelPack copy = *p;
            copy.declaringModule = moduleName;
            item = make(arena, copy);
        } else if (TopLevelImpl* i = as<TopLevelImpl>(item)) {
            TopLevelImpl copy = *i;
            copy.declaringModule = moduleName;
            item = make(arena, copy);
        }
    }
    OrderedSet<std::string> localPackNames;
    for (const TopLevel& item : items) {
        if (TopLevelPack* p = as<TopLevelPack>(item)) localPackNames.add(p->name);
    }
    Program program;
    program.moduleName = moduleName;
    program.items = items;
    program.localPackNames = localPackNames;
    program.isExported = isExported;
    program.moduleVisibility = moduleVisibility;
    program.exportCondition = exportCondition;
    program.scopes = scopeMetaByName;
    program.typeFunctions = typeFunctions;
    program.typeMacroRules = pendingTypeMacroRules;
    program.infixMacros = pendingInfixMacros;
    program.usesMacros = usedMetaInvoke;
    program.scopeTypeNamespaces = scopeTypeNamespaces;
    program.testScopeMembers = testScopeMembers;
    program.moduleAnnotations = moduleAnnotations;
    Program normalized = CallbackImplNormalizer::normalize(arena, program);
    Program rewritten = ScopeAccessRewriter::rewrite(arena, normalized);
    if (!internalSource) SourceSymbolValidator::validateProgram(rewritten);
    return rewritten;
}

/**
 * The keyword opening a named namespace here, or null if none does.
 *
 * A namespace needs a name and a brace, which is what separates it from
 * `scope { … }` (a block) and `scope "label" { … }` (reflection metadata).
 */
std::optional<TokenType> Parser::namespaceKeywordAhead() {
    if (!typeIs(current, TokenType::SCOPE)) return std::nullopt;
    int32_t i = current + 1;
    if (!typeIs(i, TokenType::IDENTIFIER)) return std::nullopt;
    i++;
    while (typeIs(i, TokenType::DOUBLE_COLON) && typeIs(i + 1, TokenType::IDENTIFIER)) i += 2;
    if (typeIs(i, TokenType::L_BRACE)) return TokenType::SCOPE;
    return std::nullopt;
}

bool Parser::isScopeNamespaceAhead() { return namespaceKeywordAhead().has_value(); }

/**
 * `scope Name (:: Name)* { items }` - a namespace contribution.
 *
 * Each member is mangled with the scope path (`std__math__PI`). The same
 * scope path may be opened as many times as it likes, in as many modules as
 * it likes, and the contributions merge.
 */
std::vector<TopLevel> Parser::parseScopeNamespace(const std::string& outerPrefix /*= ""*/) {
    consume(TokenType::SCOPE, "Expected 'scope'");
    std::string scopePath = consume(TokenType::IDENTIFIER, "Expected a name after 'scope'").lexeme;
    while (match(TokenType::DOUBLE_COLON)) {
        scopePath += "__";
        scopePath += consume(TokenType::IDENTIFIER, "Expected a name after '::' in a scope path").lexeme;
    }
    std::string prefix = outerPrefix.empty() ? scopePath : outerPrefix + "__" + scopePath;
    consume(TokenType::L_BRACE, "Expected '{' after the scope's name");
    skipNewlines();
    // A scope holds names, not state: its bindings are `fin`.
    std::vector<TopLevel> items = parseScopeBody(prefix);
    consume(TokenType::R_BRACE, "Expected '}' after scope");
    consumeNewline();
    return items;
}

/** The keyword a binding was written with, for a diagnostic that quotes it back. */
std::optional<std::string> Parser::bindingKeywordOf(const TopLevel& item) {
    if (TopLevelVarDecl* v = as<TopLevelVarDecl>(item)) return std::string(v->valueMutable ? "var" : "val");
    if (is<TopLevelLetDecl>(item)) return std::string("let");
    return std::nullopt;
}

std::vector<TopLevel> Parser::parseScopeBody(const std::string& prefix) {
    std::vector<TopLevel> result;
    std::string previousTypeNamespace = typeFunctionNamespacePrefix;
    typeFunctionNamespacePrefix = prefix;
    Finally restore{[&] { typeFunctionNamespacePrefix = previousTypeNamespace; }};
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        if (isScopeNamespaceAhead()) {
            std::vector<TopLevel> inner = parseScopeNamespace(prefix);
            result.insert(result.end(), inner.begin(), inner.end());
        } else if (isTypePropAhead()) {
            parseTypeProp(prefix);
        } else {
            int32_t memberLine = peek().line;
            TopLevel parsed = parseTopLevel();
            if (std::optional<std::string> keyword = bindingKeywordOf(parsed)) {
                fail("a scope declares only 'fin' bindings, so '" + *keyword + "' is not one of its members at line " +
                     str(memberLine) +
                     "; a binding that can be rebound or mutated needs an owner - declare it in a 'solo' namespace");
            }
            if (std::optional<std::string> typeName = declaredTypeName(parsed)) {
                scopeTypeNamespaces.set(*typeName, replaceAll(prefix, "__", "::"));
            }
            result.push_back(mangleTopLevel(parsed, prefix));
            if (!pendingTopLevels.empty()) {
                for (const TopLevel& pending : pendingTopLevels) result.push_back(mangleTopLevel(pending, prefix));
                pendingTopLevels.clear();
            }
        }
    }
    return result;
}

std::optional<std::string> Parser::declaredTypeName(const TopLevel& item) {
    switch (item.kind()) {
        case TopLevelKind::Pack: return cast<TopLevelPack>(item).name;
        case TopLevelKind::Enum: return cast<TopLevelEnum>(item).name;
        case TopLevelKind::Fail: return cast<TopLevelFail>(item).name;
        case TopLevelKind::Spec: return cast<TopLevelSpec>(item).name;
        case TopLevelKind::Deco: return cast<TopLevelDeco>(item).name;
        case TopLevelKind::Slot: return cast<TopLevelSlot>(item).name;
        case TopLevelKind::Solo: return cast<TopLevelSolo>(item).name;
        case TopLevelKind::TypeAlias: return cast<TopLevelTypeAlias>(item).name;
        default: return std::nullopt;
    }
}

/**
 * Puts the enclosing impl's type parameters on a receiver-free member: the
 * impl's parameters come first, ahead of the member's own.
 */
TopLevel Parser::withImplTypeParams(const TopLevel& item, const std::vector<std::string>& implTypeParams) {
    if (implTypeParams.empty()) return item;
    if (TopLevelFunc* f = as<TopLevelFunc>(item)) {
        TopLevelFunc copy = *f;
        copy.decl.typeParams = distinct(concat(implTypeParams, f->decl.typeParams));
        return make(arena, copy);
    }
    return item;
}

TopLevel Parser::mangleTopLevel(const TopLevel& item, const std::string& prefix) {
    switch (item.kind()) {
        case TopLevelKind::Func: {
            TopLevelFunc copy = cast<TopLevelFunc>(item);
            copy.decl.name = prefix + "__" + copy.decl.name;
            return make(arena, copy);
        }
        case TopLevelKind::FinDecl: {
            TopLevelFinDecl copy = cast<TopLevelFinDecl>(item);
            copy.name = prefix + "__" + copy.name;
            return make(arena, copy);
        }
        case TopLevelKind::VarDecl: {
            TopLevelVarDecl copy = cast<TopLevelVarDecl>(item);
            copy.name = prefix + "__" + copy.name;
            return make(arena, copy);
        }
        case TopLevelKind::LetDecl: {
            TopLevelLetDecl copy = cast<TopLevelLetDecl>(item);
            copy.name = prefix + "__" + copy.name;
            return make(arena, copy);
        }
        case TopLevelKind::InlineFin: {
            TopLevelInlineFin copy = cast<TopLevelInlineFin>(item);
            copy.name = prefix + "__" + copy.name;
            return make(arena, copy);
        }
        case TopLevelKind::InlineLet: {
            TopLevelInlineLet copy = cast<TopLevelInlineLet>(item);
            copy.name = prefix + "__" + copy.name;
            return make(arena, copy);
        }
        case TopLevelKind::InlineVar: {
            TopLevelInlineVar copy = cast<TopLevelInlineVar>(item);
            copy.name = prefix + "__" + copy.name;
            return make(arena, copy);
        }
        case TopLevelKind::Impl: {
            TopLevelImpl copy = cast<TopLevelImpl>(item);
            copy.scopePrefix = prefix;
            return make(arena, copy);
        }
        case TopLevelKind::Meta: {
            TopLevelMeta copy = cast<TopLevelMeta>(item);
            copy.name = prefix + "__" + copy.name;
            return make(arena, copy);
        }
        case TopLevelKind::Pack: {
            const TopLevelPack& pack = cast<TopLevelPack>(item);
            if (pack.nameMacro == nullptr) return item;
            TopLevelPack copy = pack;
            copy.localScope = prefix;
            return make(arena, copy);
        }
        case TopLevelKind::Bridge: {
            // Receiver-free bridge functions are static intrinsics reached as
            // `Type::member`; mangle each to `Type__member` to match.
            TopLevelBridge copy = cast<TopLevelBridge>(item);
            for (BridgeSig& sig : copy.funcs) {
                if (sig.nameMacro != nullptr) sig.localScope = prefix;
                else if (sig.localName) sig.localName = prefix + "__" + *sig.localName;
                else sig.name = prefix + "__" + sig.name;
            }
            for (BridgeValue& value : copy.values) {
                if (value.nameMacro != nullptr) value.localScope = prefix;
                else value.name = prefix + "__" + value.name;
            }
            return make(arena, copy);
        }
        default:
            return item;
    }
}

/** Reads an operator symbol after `oper` (for `impl oper<OP> …`). */
std::string Parser::parseOperatorName() {
    // The index operators: `oper[]` (read), `oper[]=` (write), `oper[:]`
    // (slice). A `[` opens the operator's name only when `]` or `:` follows.
    if (check(TokenType::L_BRACKET) && peekNextIs(TokenType::R_BRACKET)) {
        advance();  // '['
        advance();  // ']'
        return match(TokenType::EQUAL) ? "indexSet" : "index";
    }
    if (check(TokenType::L_BRACKET) && peekNextIs(TokenType::COLON) && typeIs(current + 2, TokenType::R_BRACKET)) {
        advance();  // '['
        advance();  // ':'
        advance();  // ']'
        return "slice";
    }
    // A range is not an operator a type declares: every ordered type forms
    // one, and `Step` says how it walks (RANGE_DIP §4).
    if (check(TokenType::GREATER_DOT_DOT) || check(TokenType::DOT_DOT) || check(TokenType::DOT_DOT_LESS)) {
        Token written = advance();
        fail("'oper" + written.lexeme +
             "' is not declared: every ordered type forms ranges; to walk them, implement 'Step' (RANGE_DIP §5), at line " +
             str(written.line));
    }
    // `<=>` before `<=`, matching the lexer's munch order.
    if (match(TokenType::SPACESHIP)) return "<=>";
    if (match(TokenType::LESS_EQUAL)) return "<=";
    if (match(TokenType::GREATER_EQUAL)) return ">=";
    if (match(TokenType::EQUAL_EQUAL)) return "==";
    if (match(TokenType::BANG_EQUAL)) return "!=";
    if (match(TokenType::SHIFT_LEFT)) return "<<";
    // `&=`, `|=`, `^=`, `<<=`, `>>=` are recognised here - an operator name is
    // the one place they appear.
    if (match(TokenType::AMP_EQUAL)) return "&=";
    if (match(TokenType::PIPE_EQUAL)) return "|=";
    if (match(TokenType::CARET_EQUAL)) return "^=";
    if (match(TokenType::SHIFT_LEFT_EQUAL)) return "<<=";
    if (match(TokenType::SHIFT_RIGHT_EQUAL)) return ">>=";
    if (match(TokenType::SHIFT_RIGHT)) return ">>";
    if (match(TokenType::LESS)) return "<";
    if (match(TokenType::GREATER)) return ">";
    if (match(TokenType::PLUS)) return "+";
    if (match(TokenType::MINUS)) return "-";
    if (match(TokenType::STAR)) return "*";
    if (match(TokenType::SLASH)) return "/";
    if (match(TokenType::PERCENT)) return "%";
    if (match(TokenType::AMP)) return "&";
    if (match(TokenType::PIPE)) return "|";
    if (match(TokenType::CARET)) return "^";
    if (match(TokenType::TILDE)) return "~";
    // `oper!` - logical negation.
    if (match(TokenType::BANG)) return "!";
    // `oper$` - what `"$value"` calls.
    if (check(TokenType::IDENTIFIER) && peek().lexeme == "$") {
        advance();
        return "$";
    }
    // `oper.*` / `oper.^` - what `p.*` and `p.^` call.
    if (check(TokenType::DOT) && peekNextIs(TokenType::STAR)) {
        advance();
        advance();
        return "Deref";
    }
    if (check(TokenType::DOT) && peekNextIs(TokenType::CARET)) {
        advance();
        advance();
        return "DerefMut";
    }
    // `oper as<U>` - what `value as U` calls; `as?` and `as*` are the checked
    // and reinterpreting forms.
    if (check(TokenType::AS) && peekNextIs(TokenType::QMARK)) {
        advance();
        advance();
        return "as?";
    }
    if (check(TokenType::AS) && peekNextIs(TokenType::STAR)) {
        advance();
        advance();
        return "as*";
    }
    if (match(TokenType::AS)) return "as";
    // Compound assignment: `oper +=` and friends.
    if (match(TokenType::PLUS_EQUAL)) return "+=";
    if (match(TokenType::MINUS_EQUAL)) return "-=";
    if (match(TokenType::STAR_EQUAL)) return "*=";
    if (match(TokenType::SLASH_EQUAL)) return "/=";
    if (match(TokenType::PERCENT_EQUAL)) return "%=";
    fail("Expected an operator after 'oper' at line " + str(peek().line));
}

/**
 * The member name an operator is registered under: the index operators by
 * their bare names, every other operator through its `oper<symbol>` symbol.
 */
std::string Parser::operatorMemberName(const std::string& opName) {
    if (opName == "index" || opName == "indexSet" || opName == "slice") return opName;
    return "oper" + opName;
}

/** The spelling authors write, as opposed to the internal index/deref member name. */
std::string Parser::operatorSourceName(const std::string& opName) {
    if (opName == "index") return "[]";
    if (opName == "indexSet") return "[]=";
    if (opName == "slice") return "[:]";
    if (opName == "Deref") return ".*";
    if (opName == "DerefMut") return ".^";
    return opName;
}

/**
 * Reads the ordinary operands after an operator receiver. The parentheses
 * are never optional.
 */
std::vector<Param> Parser::parseOperatorOperands(const std::string& opName) {
    if (!match(TokenType::L_PAREN)) {
        fail("operator 'oper" + operatorSourceName(opName) +
             "' requires an explicit parameter list after its receiver; write '()' when it has no operands, at line " +
             str(peek().line));
    }
    std::vector<Param> operands;
    if (!check(TokenType::R_PAREN)) operands = parseParams();
    consume(TokenType::R_PAREN, "Expected ')' after operator operands");
    return operands;
}

/** Operators the compiler looks up by their bare member name. */
bool Parser::isBareLookupOperator(const std::string& opName) {
    return opName == "index" || opName == "indexSet" || opName == "slice";
}

/**
 * Refuses a `by` after an operator's signature. It was a range operator's
 * step, which no operator declares any more (RANGE_DIP §4).
 */
void Parser::refuseOperatorStep() {
    if (check(TokenType::BY)) {
        fail("'by' does not follow an operator declaration; a step is written in a 'for' header, at line " +
             str(peek().line));
    }
}

/**
 * `oper<OP> Type&.(operands): Ret { body }` at top level. The receiver names
 * the type the operator belongs to.
 */
TopLevel Parser::parseFreeOperator(const std::vector<Annotation>& annotations, const Visibility& visibility,
                                   bool isBridge /*= false*/) {
    Token start = peek();
    consume(TokenType::OPER, "Expected 'oper'");
    std::string opName = parseOperatorName();
    // `oper as<U> Vec2&.(): U` - an operator may take its own type parameters.
    TypeParams operatorTypeParams = parseTypeParams();
    PropReceiver recv = parseOperatorReceiver(opName, false);
    TypeRefNamed* recvNamed = as<TypeRefNamed>(recv.type);
    if (recvNamed == nullptr) {
        fail("An operator's receiver must name a type, as in 'oper+ Model&.', at line " + str(start.line));
    }
    std::string typeName = recvNamed->name;
    std::vector<Param> operands = parseOperatorOperands(opName);
    TypeAnnotation ret;
    if (match(TokenType::COLON)) {
        skipNewlines();
        ret = explicitType(parseTypeName());
    }
    // `inline if cond { inline "?! E" }` - a fragment spliced into the signature.
    TypeAnnotation operRet = ret;
    if (std::optional<std::vector<Token>> fragment = parseSignatureFragment()) {
        operRet = applySignatureFragment(operRet, *fragment, start);
    }
    // An operator may carry its own constraint.
    Expr operatorWhere = withInlineBounds(operatorTypeParams.boundClause, parseWhereClause(), start.line);
    refuseOperatorStep();
    // The same overload suffix an operator inside an `impl` gets.
    std::string memberName = operatorMemberName(opName) +
                             (isBareLookupOperator(opName) ? std::string()
                                                           : operatorOverloadSuffix(opName, recv.modifier, operands));
    // A declaration with no body is provided by the backend, and says so.
    if (!check(TokenType::L_BRACE)) {
        if (!isBridge) {
            fail("operator '" + opName + "' has no body, so it is compiler-provided - declare it 'bridge oper" + opName +
                 "' at line " + str(start.line));
        }
        consumeNewline();
        FuncDecl decl;
        decl.name = memberName;
        decl.params = operands;
        decl.returnType = operRet;
        decl.isInline = false;
        decl.typeParams = operatorTypeParams.names;
        decl.line = start.line;
        decl.column = start.column;
        decl.annotations = annotations;
        decl.visibility = visibility;
        decl.receiverModifier = recv.modifier;
        decl.receiverName = recv.name;
        decl.whereClause = operatorWhere;
        TopLevelImpl impl;
        impl.typeName = typeName;
        impl.methods = {decl};
        impl.line = start.line;
        impl.column = start.column;
        impl.isBridge = true;
        return makeImpl(impl);
    }
    consume(TokenType::L_BRACE, "Expected '{' after operator declaration");
    skipNewlines();
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}' after operator body");
    consumeNewline();
    FuncDecl method;
    method.name = memberName;
    method.params = operands;
    method.returnType = operRet;
    method.body = body;
    method.isInline = false;
    method.typeParams = operatorTypeParams.names;
    method.line = start.line;
    method.column = start.column;
    method.annotations = annotations;
    method.visibility = visibility;
    method.receiverModifier = recv.modifier;
    method.receiverName = recv.name;
    // `oper * <N: Int>` is generic over a value, and the member has to say so.
    method.constParams = operatorTypeParams.constParams;
    method.whereClause = operatorWhere;
    TopLevelImpl impl;
    impl.typeName = typeName;
    impl.methods = {method};
    impl.line = start.line;
    impl.column = start.column;
    return makeImpl(impl);
}

/**
 * Recognizes the removed `impl Spec for Type:: {` form solely so the parser
 * can issue a focused migration diagnostic.
 */
bool Parser::isSpecStaticImplAhead() {
    if (!typeIs(current, TokenType::IDENTIFIER)) return false;
    // The spec may carry type arguments - skip a balanced `<…>` between its
    // name and `for`.
    int32_t afterName = current + 1;
    if (typeIs(afterName, TokenType::LESS)) {
        int generics = 0;
        while (afterName < (int32_t)tokens.size()) {
            TokenType t = tokens[(size_t)afterName].type;
            if (t == TokenType::LESS) {
                generics++;
            } else if (t == TokenType::GREATER) {
                generics--;
                if (generics == 0) {
                    afterName++;
                    break;
                }
            } else if (t == TokenType::L_BRACE || t == TokenType::NEWLINE || t == TokenType::EOF_) {
                return false;
            }
            afterName++;
        }
    }
    if (!typeIs(afterName, TokenType::FOR)) return false;
    int32_t i = afterName + 1;
    int depth = 0;
    while (i < (int32_t)tokens.size()) {
        TokenType t = tokens[(size_t)i].type;
        if (t == TokenType::L_BRACKET || t == TokenType::LESS) {
            depth++;
        } else if (t == TokenType::R_BRACKET || t == TokenType::GREATER) {
            depth--;
        } else if (t == TokenType::DOUBLE_COLON) {
            if (depth == 0) return typeIs(i + 1, TokenType::L_BRACE);
        } else if (t == TokenType::L_BRACE || t == TokenType::NEWLINE || t == TokenType::EOF_) {
            return false;
        }
        i++;
    }
    return false;
}

/** Recognizes removed `impl Type:: {` declarations for a migration error. */
bool Parser::isStaticImplAhead() {
    int32_t i = current;
    if (typeIs(i, TokenType::L_BRACKET)) {
        int depth = 0;
        while (i < (int32_t)tokens.size()) {
            TokenType t = tokens[(size_t)i].type;
            if (t == TokenType::L_BRACKET) depth++;
            if (t == TokenType::R_BRACKET) {
                depth--;
                if (depth == 0) {
                    i++;
                    break;
                }
            }
            i++;
        }
    } else {
        if (!typeIs(i, TokenType::IDENTIFIER)) return false;
        i++;
        if (typeIs(i, TokenType::LESS)) {
            int depth = 0;
            while (i < (int32_t)tokens.size()) {
                TokenType t = tokens[(size_t)i].type;
                if (t == TokenType::LESS) depth++;
                if (t == TokenType::GREATER) {
                    depth--;
                    if (depth == 0) {
                        i++;
                        break;
                    }
                }
                i++;
            }
        }
    }
    if (!typeIs(i, TokenType::DOUBLE_COLON)) return false;
    // The block opens directly, or after a `where` clause.
    if (i + 1 >= (int32_t)tokens.size()) return false;
    TokenType after = tokens[(size_t)i + 1].type;
    return after == TokenType::L_BRACE || after == TokenType::WHERE;
}

/**
 * Parses an optional receiver + parameters + body for a member. Returns the
 * receiver modifier (default `ref`), the parameters, and the body statements.
 */
std::tuple<ParamModifier, std::vector<Param>, std::vector<Stmt>> Parser::parseReceiverAndBody() {
    ParamModifier receiverModifier = ParamModifier::SHARED;
    std::vector<Param> params;
    if (match(TokenType::L_PAREN)) {
        receiverModifier = parseReceiverBinding().modifier;
        if (match(TokenType::COMMA)) {
            std::vector<Param> more = parseParams();
            params.insert(params.end(), more.begin(), more.end());
        }
        consume(TokenType::R_PAREN, "Expected ')' after receiver");
    }
    std::vector<Stmt> body;
    if (match(TokenType::L_BRACE)) {
        skipNewlines();
        // In-brace receiver form: `{ self& -> body }` or, for operator overloads,
        // `{ self&, operand[: Type][, …] -> body }`.
        if (isSelfReceiverHeaderAhead()) {
            receiverModifier = parseReceiverBinding().modifier;
            parseReceiverOperands(params);
            consume(TokenType::ARROW, "Expected '->' after in-brace receiver");
            skipNewlines();
        }
        while (!check(TokenType::R_BRACE) && !isAtEnd()) {
            std::vector<Stmt> stmts = parseStmts();
            body.insert(body.end(), stmts.begin(), stmts.end());
            skipNewlines();
        }
        consume(TokenType::R_BRACE, "Expected '}' after body");
    }
    return {receiverModifier, params, body};
}

/**
 * True when the current brace body opens with a `self` receiver, optionally
 * followed by operand params (`self, x -> …`) before the `->`.
 */
bool Parser::isInBraceReceiverAhead() {
    int32_t i = current;
    if (lexemeIs(i, "self")) {
        // `self& …` / `self! …` / `self …`.
        i++;
        if (typeIs(i, TokenType::AMP) || typeIs(i, TokenType::BANG)) i++;
    } else {
        return false;
    }
    // Skip `, operand` pairs (operator operands) before the `->`.
    while (typeIs(i, TokenType::COMMA)) {
        i++;  // ','
        i++;  // operand name
        if (typeIs(i, TokenType::AMP) || typeIs(i, TokenType::BANG)) i++;
    }
    return typeIs(i, TokenType::ARROW);
}

/** `[exposed|protected|confined] <declaration>` - how far the declaration reaches. */
Visibility Parser::parseVisibility() {
    if (match(TokenType::CONFINED)) return Visibility{Reach::CONFINED};
    if (match(TokenType::PROTECTED)) return Visibility{Reach::PROTECTED};
    // `exposed confined` / `exposed protected` - the two axes together.
    if (check(TokenType::EXPOSED) && peekNextIs(TokenType::CONFINED)) {
        advance();
        advance();
        return Visibility{Reach::CONFINED, true};
    }
    if (check(TokenType::EXPOSED) && peekNextIs(TokenType::PROTECTED)) {
        advance();
        advance();
        return Visibility{Reach::PROTECTED, true};
    }
    // `exposed` states the default. It is only a visibility modifier when a
    // declaration follows: `exposed import`, `exposed if` and `exposed module`
    // are their own forms.
    if (check(TokenType::EXPOSED) && !exposeStartsAnotherForm()) {
        advance();
        return Visibility{Reach::PUBLIC, true};
    }
    return Visibility{};
}

/**
 * True when the `exposed` at the cursor begins `exposed import`, `exposed if`
 * or `exposed module` - forms of their own, parsed elsewhere.
 */
bool Parser::exposeStartsAnotherForm() {
    if (current + 1 >= (int32_t)tokens.size()) return false;
    TokenType next = tokens[(size_t)current + 1].type;
    return next == TokenType::IMPORT || next == TokenType::IF || next == TokenType::MODULE;
}

// ---------------------------------------------------------------------------
// Token helpers (Parser.kt 13015-13083)
// ---------------------------------------------------------------------------

/**
 * Parses an expression from a raw source string (used to parse the embedded
 * expressions of an interpolated string).
 */
Expr Parser::parseSubExpr(const std::string& source) {
    std::vector<Token> subTokens = Lexer(source).tokenize();
    Parser sub(arena, std::move(subTokens));
    return sub.parseExpr();
}

Token Parser::peek() { return at(tokens, current); }

std::optional<Token> Parser::peekNext() {
    if (current + 1 < (int32_t)tokens.size()) return tokens[(size_t)current + 1];
    return std::nullopt;
}

bool Parser::peekNextIs(TokenType type) {
    return current + 1 < (int32_t)tokens.size() && tokens[(size_t)current + 1].type == type;
}

bool Parser::typeIs(int32_t index, TokenType type) {
    return index >= 0 && index < (int32_t)tokens.size() && tokens[(size_t)index].type == type;
}

bool Parser::lexemeIs(int32_t index, const std::string& lexeme) {
    return index >= 0 && index < (int32_t)tokens.size() && tokens[(size_t)index].lexeme == lexeme;
}

const Token* Parser::tokenOrNull(int32_t index) {
    if (index < 0 || index >= (int32_t)tokens.size()) return nullptr;
    return &tokens[(size_t)index];
}

/** Index of the next non-newline token at or after [from]. */
int32_t Parser::nextMeaningfulIndex(int32_t from) {
    int32_t i = from;
    while (i < (int32_t)tokens.size() && tokens[(size_t)i].type == TokenType::NEWLINE) i++;
    return i;
}

int32_t Parser::nextMeaningfulIndex() { return nextMeaningfulIndex(current); }

bool Parser::isAtEnd() { return at(tokens, current).type == TokenType::EOF_; }

bool Parser::check(TokenType type) { return !isAtEnd() && tokens[(size_t)current].type == type; }

Token Parser::advance() {
    Token token = at(tokens, current);
    current++;
    return token;
}

void Parser::skipNewlines() {
    while (check(TokenType::NEWLINE)) advance();
}

void Parser::consumeNewline() {
    if (check(TokenType::NEWLINE)) advance();
}

bool Parser::match(TokenType type) {
    if (!check(type)) return false;
    advance();
    return true;
}

/**
 * match, allowing the token to open the following line. Newlines are
 * consumed when the word is there and left alone when it is not.
 */
bool Parser::matchContinued(TokenType type) {
    if (match(type)) return true;
    int32_t ahead = current;
    while (ahead < (int32_t)tokens.size() && tokens[(size_t)ahead].type == TokenType::NEWLINE) ahead++;
    if (ahead == current) return false;
    if (ahead >= (int32_t)tokens.size()) return false;
    if (tokens[(size_t)ahead].type != type) return false;
    current = ahead + 1;
    return true;
}

bool Parser::matchWithKeyword() {
    if (match(TokenType::WITH)) return true;
    if (check(TokenType::IDENTIFIER) && peek().lexeme == "with") {
        advance();
        return true;
    }
    return false;
}

Token Parser::consume(TokenType type, const std::string& message) {
    if (check(type)) return advance();
    Token found = peek();
    if (type == TokenType::IDENTIFIER) nameExpected(message, found);
    fail(message + ", got '" + found.lexeme + "' (" + tokenTypeName(found.type) + ") at line " + str(found.line));
}

/** Kotlin's TopLevel.Impl constructor with its computed defaults. */
TopLevel Parser::makeImpl(TopLevelImpl impl) {
    implDefaults(impl);
    return make(arena, impl);
}

}  // namespace azora
