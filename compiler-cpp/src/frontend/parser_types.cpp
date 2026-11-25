// Parser.kt lines 7187-7727: type references - names, suffixes and infixes,
// named type macros, atoms, and callable types.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"

namespace azora {

TypeRef Parser::parseTypeName(bool allowReceiverCallable /*= true*/) {
    // Reference kinds are postfix: `T&` (immutable) / `T!` (mutable).
    // `...T` - variadic type (prefix). Wraps the type in the internal array representation.
    if (match(TokenType::ELLIPSIS)) {
        TypeRef inner = parseTypeAtom();
        return make(arena, TypeRefArray{inner});
    }
    TypeRef base = parseTypeInfixes(parseTypeSuffixes(parseTypeAtom()));
    // `T&.(A) -> R` / `T!.(A) -> R` / `T.(A) -> R`: a receiver type is complete
    // before the dot, and the parenthesized list after it holds the parameters.
    if (!pendingGreater && allowReceiverCallable && check(TokenType::DOT) && peekNextIs(TokenType::L_PAREN)) {
        advance();
        return parseCallableTail({base});
    }
    return base;
}

/** The borrow an `&`/`!` sigil creates. */
OwnershipOp Parser::borrowOp(TokenType sigil) { return sigil == TokenType::AMP ? OwnershipOp::SHARE : OwnershipOp::BORROW; }

/**
 * `|a|` / `|a, b|` after a borrow - the sources a returned borrow comes from.
 * Empty when no fence is written; an explicitly empty fence is invalid.
 */
std::vector<std::string> Parser::parseBorrowOrigins() {
    if (check(TokenType::L_BRACKET)) {
        fail("Borrow-origin brackets were removed; replace '[source]' with '|source|' at line " + str(peek().line));
    }
    if (match(TokenType::OR_OR)) fail("A borrow-origin fence cannot be empty; omit '||' at line " + str(peek().line));
    if (!match(TokenType::PIPE)) return {};
    if (check(TokenType::PIPE)) fail("A borrow-origin fence cannot be empty; omit '||' at line " + str(peek().line));
    std::vector<std::string> origins;
    do {
        origins.push_back(consume(TokenType::IDENTIFIER, "Expected a borrow origin name").lexeme);
    } while (match(TokenType::COMMA));
    consume(TokenType::PIPE, "Expected '|' after the borrow origin list");
    if (distinct(origins).size() != origins.size()) fail("Duplicate borrow origin at line " + str(peek().line));
    return origins;
}

TypeRef Parser::parseTypeSuffixes(const TypeRef& start) {
    if (pendingGreater) return start;
    TypeRef base = start;
    // Suffix type modifiers: `T?`, `T ?! Error`, `T&|source|`, `T!|source|`, in any order.
    while (true) {
        if (match(TokenType::QMARK)) {
            base = make(arena, TypeRefNullable{base});
        } else if (check(TokenType::STAR)) {
            advance();
            base = make(arena, TypeRefPointer{base});
        } else if (check(TokenType::CARET)) {
            advance();
            base = make(arena, TypeRefPointer{base, true});
        } else if (match(TokenType::QMARK_BANG)) {
            // Failable (error) type: `T ?! E` or `T ?! [E1, E2]`.
            std::vector<std::string> errSets;
            if (match(TokenType::L_BRACKET)) {
                do {
                    errSets.push_back(parseErrorSetName("in failable type"));
                } while (match(TokenType::COMMA));
                consume(TokenType::R_BRACKET, "Expected ']' after error-set list");
                if (distinct(errSets).size() != errSets.size()) {
                    fail("Duplicate error set in failable type at line " + str(peek().line));
                }
            } else {
                errSets.push_back(parseErrorSetName("after '?!'"));
            }
            base = makeFailable(arena, base, errSets);
        } else if (match(TokenType::AMP)) {
            // Postfix borrows: `T&` (immutable), `T!` (mutable).
            TypeRef inner = base;
            base = make(arena, TypeRefReference{RefKind::BORROWED, inner, parseBorrowOrigins()});
        } else if (match(TokenType::BANG)) {
            TypeRef inner = base;
            base = make(arena, TypeRefReference{RefKind::MUTABLE, inner, parseBorrowOrigins()});
        } else {
            return base;
        }
    }
}

/** Type-filter composition used by ECS queries: `A with B`, `A without C`. */
TypeRef Parser::parseTypeInfixes(const TypeRef& start) {
    if (pendingGreater) return start;
    TypeRef base = start;
    while (true) {
        std::string op;
        if (match(TokenType::WITH)) op = "with";
        else if (match(TokenType::WITHOUT)) op = "without";
        else return base;
        TypeRef right = parseTypeName();
        base = NamedTypeMacroCall::create(arena, op, {base, right}, "", NamedTypeMacroCall::Form::Infix);
    }
}

bool Parser::isNamedTypeMacroInvocationAhead(int32_t index) {
    const Token* macro = tokenOrNull(index);
    if (macro == nullptr) return false;
    if (macro->type != TokenType::IDENTIFIER || !firstIsLowerCase(macro->lexeme)) return false;
    // A borrow sigil binds to the macro name (`@res& T`), so look past it.
    int32_t offset = index + 1;
    if (typeIs(offset, TokenType::AMP) || typeIs(offset, TokenType::BANG)) offset++;
    const Token* next = tokenOrNull(offset);
    if (next == nullptr) return false;
    return next->type == TokenType::L_BRACKET || next->type == TokenType::IDENTIFIER ||
           next->type == TokenType::L_PAREN || next->type == TokenType::ELLIPSIS;
}

bool Parser::isNamedTypeMacroInvocationAhead() { return isNamedTypeMacroInvocationAhead(current); }

/**
 * Reads the rest of a type macro's own grammar - `… with C without D` - as one
 * application, carrying the words written and the shape of each operand.
 */
TypeRef Parser::finishNamedTypeMacro(const std::string& name, const std::vector<TypeRef>& firstArgs, bool firstIsList,
                                     const std::string& modifier, NamedTypeMacroCall::Form form) {
    std::vector<std::string> keywords;
    std::vector<std::string> shapes{firstIsList ? "L" + str(firstArgs.size()) : "S1"};
    std::vector<TypeRef> args = firstArgs;
    while (check(TokenType::WITH) || check(TokenType::WITHOUT)) {
        keywords.push_back(advance().lexeme);
        if (match(TokenType::L_BRACKET)) {
            std::vector<TypeRef> items;
            skipNewlines();
            if (!check(TokenType::R_BRACKET)) {
                do {
                    skipNewlines();
                    items.push_back(parseTypeName());
                    skipNewlines();
                } while (match(TokenType::COMMA));
            }
            consume(TokenType::R_BRACKET, "Expected ']' after the operand of '" + keywords.back() + "'");
            shapes.push_back("L" + str(items.size()));
            args.insert(args.end(), items.begin(), items.end());
        } else {
            shapes.push_back("S1");
            args.push_back(parseTypeSuffixes(parseTypeAtom()));
        }
    }
    if (keywords.empty()) return NamedTypeMacroCall::create(arena, name, firstArgs, modifier, form);
    return NamedTypeMacroCall::create(arena, name, args, join(keywords, ",") + "|" + join(shapes, ","), form);
}

TypeRef Parser::parseNamedTypeMacroInvocation(const std::string& modifier /*= ""*/) {
    std::string name = consume(TokenType::IDENTIFIER, "Expected type-macro name").lexeme;
    // Fold the borrow sigil into the name so `@res& T` resolves against the `res&` arm.
    if (match(TokenType::BANG)) name += "!";
    else if (match(TokenType::AMP)) name += "&";
    if (match(TokenType::L_BRACKET)) {
        std::vector<TypeRef> args;
        skipNewlines();
        if (!check(TokenType::R_BRACKET)) {
            do {
                skipNewlines();
                args.push_back(parseTypeName());
                skipNewlines();
            } while (match(TokenType::COMMA));
        }
        consume(TokenType::R_BRACKET, "Expected ']' after '" + name + "' type arguments");
        return finishNamedTypeMacro(name, args, true, modifier, NamedTypeMacroCall::Form::List);
    }
    std::vector<TypeRef> singleArg{parseTypeSuffixes(parseTypeAtom())};
    return finishNamedTypeMacro(name, singleArg, false, modifier, NamedTypeMacroCall::Form::Prefix);
}

/**
 * Parses a structured type reference: names (with generic arguments and scope
 * paths), `(A, B)` tuples, `(A, B) -> R` functions, `(A)` grouping, callable
 * prefixes, and type-macro invocations.
 */
TypeRef Parser::parseTypeAtom() {
    // `__int`, `__uint`, `__float` - the compiler's own primitives.
    if (check(TokenType::PRIM_INT) || check(TokenType::PRIM_UINT) || check(TokenType::PRIM_FLOAT)) {
        Token token = advance();
        TypeRefNamed ref;
        ref.name = token.lexeme;
        ref.line = token.line;
        ref.column = token.column;
        ref.length = u16len(token.lexeme);
        return make(arena, ref);
    }
    if (check(TokenType::BANG)) {
        if (peekNextIs(TokenType::L_BRACKET)) fail("Set type syntax '![T]' was removed; use 'Set<T>' at line " + str(peek().line));
        fail("Expected type name at line " + str(peek().line) + ", got '" + peek().lexeme + "'");
    }
    // `inline (A) -> R` - the block is substituted where it is passed.
    if (check(TokenType::INLINE) &&
        (peekNextIs(TokenType::L_PAREN) || peekNextIs(TokenType::L_BRACKET) || peekNextIs(TokenType::IDENTIFIER) ||
         peekNextIs(TokenType::REACT) || peekNextIs(TokenType::ASYNC) || peekNextIs(TokenType::ESCAPING))) {
        advance();
        TypeRef callable = parseTypeAtom();
        TypeRefFunction* function = as<TypeRefFunction>(callable);
        if (function == nullptr) fail("'inline' must be followed by a callable type at line " + str(peek().line));
        TypeRefFunction copy = *function;
        copy.isInline = true;
        return make(arena, copy);
    }
    // `async (A) -> R` - the callable an `async func` produces.
    if (isAsyncCallableTypeAhead()) {
        advance();  // 'async'
        return parseCallableType(CallableKind::TASK);
    }
    // `react (A) -> R` / `react async Ctx&.(A) -> R` - a callable that may use
    // reactive state.
    if (isReactCallableTypeAhead()) {
        advance();  // 'react'
        if (check(TokenType::ASYNC)) {
            advance();
            return parseCallableType(CallableKind::REACT_TASK);
        }
        return parseCallableType(CallableKind::REACT);
    }
    // `foreign (String) -> Int` - a C function address.
    if (check(TokenType::IDENTIFIER) && peek().lexeme == "foreign" && peekNextIs(TokenType::L_PAREN)) {
        advance();  // 'foreign'
        TypeRef parsed = parseTypeName();
        TypeRefFunction* callable = as<TypeRefFunction>(parsed);
        if (callable == nullptr) fail("'foreign' must be followed by a callable type at line " + str(peek().line));
        if (!callable->receivers.empty()) fail("a foreign function has no receiver at line " + str(peek().line));
        TypeRefFunction copy = *callable;
        copy.isForeign = true;
        return make(arena, copy);
    }
    // `escaping (Event) -> Unit` - contextual, as `async` is above.
    if (check(TokenType::ESCAPING) &&
        (peekNextIs(TokenType::L_PAREN) || peekNextIs(TokenType::L_BRACKET) || peekNextIs(TokenType::IDENTIFIER))) {
        return parseCallableType();
    }
    if (check(TokenType::L_BRACKET) && isCallableTypeAhead()) {
        fail("callable receiver types use a dot receiver head; write 'Type&.(Args) -> Return' at line " + str(peek().line));
    }
    if (check(TokenType::L_BRACKET)) {
        // Bracket type sugar is not a valid type spelling.
        Token start = peek();
        advance();  // consume '['
        TypeRef keyOrElem = parseTypeName();
        if (match(TokenType::COLON)) {
            parseTypeName();
            consume(TokenType::R_BRACKET, "Expected ']' after map type");
            fail("Map type syntax '[K: V]' is not valid; use 'Map<K, V>' at line " + str(start.line));
        }
        consume(TokenType::R_BRACKET, "Expected ']' after type");
        fail("Array type syntax '[" + toString(keyOrElem) + "]' is not valid; use 'Array<" + toString(keyOrElem) +
             ">' at line " + str(start.line));
    }
    if (check(TokenType::L_PAREN)) {
        Token start = advance();  // consume '('
        std::vector<TypeRef> elements;
        if (!check(TokenType::R_PAREN)) {
            do {
                // `(A, B,)` may end in a comma; `(A,)` is no one-element tuple.
                if (check(TokenType::R_PAREN)) {
                    if (elements.size() == 1) fail(oneElementTuple(start.line, "type"));
                    break;
                }
                elements.push_back(parseCallableParamType());
            } while (match(TokenType::COMMA));
        }
        consume(TokenType::R_PAREN, "Expected ')' in grouped or function type");
        if (match(TokenType::DOT)) {
            if (elements.empty()) fail("A callable receiver group cannot be empty at line " + str(start.line));
            return parseCallableTail(elements);
        }
        if (match(TokenType::ARROW)) {
            TypeRef ret = parseTypeName();
            return make(arena, TypeRefFunction{elements, ret});
        }
        if (elements.empty()) fail("Empty type '()' at line " + str(start.line));
        if (elements.size() == 1) return elements[0];  // grouping
        return make(arena, TypeRefTuple{elements});
    }
    // `@rows Item` - a macro applied to a type.
    if (check(TokenType::AT) && isNamedTypeMacroInvocationAhead(current + 1)) {
        advance();
        return parseNamedTypeMacroInvocation();
    }
    if (check(TokenType::IDENTIFIER)) {
        // A removed collection spelling says what replaced it.
        std::string lexeme = peek().lexeme;
        if (peekNextIs(TokenType::L_BRACKET) && (lexeme == "arr" || lexeme == "vec" || lexeme == "set" || lexeme == "map")) {
            fail("'" + lexeme + "[...]' type syntax was removed; use Array<T>, List<T>, Set<T>, or Map<K, V> at line " +
                 str(peek().line));
        }
        // A type macro is invoked as any macro is, behind its `@`.
        if (isNamedTypeMacroInvocationAhead()) {
            Token macro = peek();
            fail("a type macro is invoked with '@': write '@" + macro.lexeme + " …' instead of '" + macro.lexeme +
                 " …' at line " + str(macro.line));
        }
        Token firstNameToken = advance();
        std::string name = firstNameToken.lexeme;
        // `P.ActualType` - the type a reflected parameter has.
        std::optional<Token> next = peekNext();
        if (check(TokenType::DOT) && next && next->lexeme == "ActualType") {
            advance();
            advance();
            TypeRefNamed ref;
            ref.name = name + ".ActualType";
            ref.line = firstNameToken.line;
            ref.column = firstNameToken.column;
            ref.length = u16len(firstNameToken.lexeme);
            return make(arena, ref);
        }
        // A fully qualified path combines a dotted owning module with a scope path.
        std::vector<std::string> dottedPath{name};
        Token writtenTypeToken = firstNameToken;
        while (check(TokenType::DOT) && peekNextIs(TokenType::IDENTIFIER)) {
            advance();
            writtenTypeToken = consume(TokenType::IDENTIFIER, "Expected module path segment after '.'");
            dottedPath.push_back(writtenTypeToken.lexeme);
        }
        std::optional<std::string> modulePath;
        if (dottedPath.size() > 1) {
            modulePath = join(std::vector<std::string>(dottedPath.begin(), dottedPath.end() - 1), ".");
        }
        std::string typeName = dottedPath.back();
        std::string qualifiedName = typeName;
        while (match(TokenType::DOUBLE_COLON)) {
            writtenTypeToken = consume(TokenType::IDENTIFIER, "Expected type name after '::' in type path");
            typeName = writtenTypeToken.lexeme;
            qualifiedName += "__" + typeName;
        }
        std::optional<std::string> scopeQualifier;
        if (qualifiedName != typeName) scopeQualifier = replaceAll(substringBeforeLast(qualifiedName, "__"), "__", "::");
        if (check(TokenType::AT) && peekNextIs(TokenType::L_PAREN)) {
            fail("'" + typeName +
                 "@(…)' type-function call syntax was removed; a 'deepinline prop' is called like the type it is - '" +
                 typeName + "<…>' - at line " + str(peek().line));
        }
        if (match(TokenType::LESS)) {
            std::vector<TypeRef> a;
            bool variadic = false;
            do {
                bool prefixVariadic = match(TokenType::ELLIPSIS);
                if (prefixVariadic) variadic = true;
                a.push_back(parseTypeArg());
            } while (matchTypeArgumentComma());
            // `Name<...T>` - the variadic pack expands into this type's args.
            if (check(TokenType::ELLIPSIS)) {
                fail("Variadic type arguments use the prefix form '<...T>', not '<T...>', at line " + str(peek().line));
            }
            consumeTypeArgumentClose("Expected '>' to close generic type arguments");
            // `Name<T>(flag, other)` - a compile-time type call; not when a `>>`
            // closed this application.
            std::vector<Expr> values;
            if (check(TokenType::L_PAREN) && !pendingGreater) values = parseTypeCallValueArgs();
            if (modulePath) return TypeFunctionCall::create(arena, ModuleQualifiedSymbol::create(*modulePath, qualifiedName), a);
            TypeRefNamed ref;
            ref.name = typeName;
            ref.args = a;
            ref.variadic = variadic;
            ref.qualifier = scopeQualifier;
            ref.valueArgs = values;
            ref.line = writtenTypeToken.line;
            ref.column = writtenTypeToken.column;
            ref.length = u16len(writtenTypeToken.lexeme);
            return make(arena, ref);
        }
        if (modulePath) fail("A module-qualified path must name a type property at line " + str(peek().line));
        TypeRefNamed ref;
        ref.name = typeName;
        ref.qualifier = scopeQualifier;
        ref.line = writtenTypeToken.line;
        ref.column = writtenTypeToken.column;
        ref.length = u16len(writtenTypeToken.lexeme);
        return make(arena, ref);
    }
    fail("Expected type name at line " + str(peek().line) + ", got '" + peek().lexeme + "'");
}

/** True when `async` here prefixes a callable type. */
bool Parser::isAsyncCallableTypeAhead() {
    if (!check(TokenType::ASYNC)) return false;
    if (peekNextIs(TokenType::L_PAREN) || peekNextIs(TokenType::IDENTIFIER)) return true;
    if (peekNextIs(TokenType::L_BRACKET)) return isCallableTypeAhead(current + 1);
    return false;
}

bool Parser::isReactCallableTypeAhead() {
    if (!check(TokenType::REACT)) return false;
    int32_t next = current + 1;
    if (typeIs(next, TokenType::ASYNC)) next++;
    if (typeIs(next, TokenType::L_PAREN) || typeIs(next, TokenType::IDENTIFIER)) return true;
    if (typeIs(next, TokenType::L_BRACKET)) return isCallableTypeAhead(next);
    return false;
}

bool Parser::isCallableTypeAhead(int32_t from) {
    int depth = 0;
    int32_t i = from;
    while (i < (int32_t)tokens.size()) {
        TokenType t = tokens[(size_t)i].type;
        if (t == TokenType::L_BRACKET) {
            depth++;
        } else if (t == TokenType::R_BRACKET) {
            depth--;
            if (depth == 0) return typeIs(i + 1, TokenType::L_PAREN) || typeIs(i + 1, TokenType::ARROW);
        } else if (t == TokenType::EOF_) {
            return false;
        }
        i++;
    }
    return false;
}

bool Parser::isCallableTypeAhead() { return isCallableTypeAhead(current); }

/** Reads a prefixed callable and applies the prefix to the parsed function. */
TypeRef Parser::parseCallableType(CallableKind kind /*= CallableKind::FUNC*/) {
    bool escaping = check(TokenType::ESCAPING);
    if (escaping) advance();
    if (check(TokenType::L_BRACKET) && isCallableTypeAhead()) {
        fail("Bracketed callable receivers were removed at line " + str(peek().line) +
             "; replace '[Context&](Args) -> Result' with 'Context&.(Args) -> Result'");
    }
    TypeRef parsed = parseTypeName();
    TypeRefFunction* function = as<TypeRefFunction>(parsed);
    if (function == nullptr) {
        fail(std::string("Expected a callable type after '") + (escaping ? "escaping" : surfaceName(kind)) + "' at line " +
             str(peek().line));
    }
    TypeRefFunction copy = *function;
    copy.kind = kind;
    copy.isEscaping = escaping;
    return make(arena, copy);
}

/** The `(parameters) -> result` tail following a receiver type and dot. */
TypeRefFunction* Parser::parseCallableTail(const std::vector<TypeRef>& receivers,
                                           CallableKind kind /*= CallableKind::FUNC*/, bool escaping /*= false*/) {
    consume(TokenType::L_PAREN, "Expected '(' after the callable receiver dot");
    std::vector<TypeRef> params;
    if (!check(TokenType::R_PAREN)) {
        do {
            params.push_back(parseCallableParamType());
        } while (match(TokenType::COMMA));
    }
    consume(TokenType::R_PAREN, "Expected ')' after callable parameters");
    consume(TokenType::ARROW, "Expected '->' after callable parameters");
    TypeRef ret = parseTypeName();
    return make(arena, TypeRefFunction{params, ret, receivers, kind, escaping});
}

/** One entry of a callable type's parameter list, variadic when spelled `...T`. */
TypeRef Parser::parseCallableParamType() {
    if (!match(TokenType::ELLIPSIS)) return parseTypeName();
    TypeRef inner = parseTypeName();
    if (TypeRefNamed* named = as<TypeRefNamed>(inner)) {
        TypeRefNamed copy = *named;
        copy.variadic = true;
        return make(arena, copy);
    }
    fail("only a named type can be variadic in a callable type at line " + str(peek().line));
}

}  // namespace azora
