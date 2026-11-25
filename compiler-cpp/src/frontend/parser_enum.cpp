// Parser.kt lines 2985-3455: `enum`, `error` sets, impl target parameters,
// generic type arguments, callback spec helpers, contract names and impl
// target lists.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/kotlin_math.h"
#include "base/text.h"
#include "frontend/ast_util.h"
#include "frontend/use_as_template.h"

namespace azora {

/**
 * `enum Name { Var1; Var2; ... }` - variants, each optionally with trailing
 * annotations. `enum Name: Type { Var1(value) … }` gives every case a fixed
 * value of `Type`.
 */
TopLevelEnum* Parser::parseEnumDecl(const std::vector<Annotation>& annotations /*= {}*/) {
    Token start = peek();
    consume(TokenType::ENUM, "Expected 'enum'");
    std::string name = consume(TokenType::IDENTIFIER, "Expected enum name").lexeme;
    // `enum Name<T>` - parameters after the name they parametrize, as everywhere.
    TypeParams enumGenerics = parseTypeParams();
    std::vector<std::string> enumTypeParams = enumGenerics.names;
    TypeRef valueType;
    if (match(TokenType::COLON)) valueType = parseTypeName(false);
    consume(TokenType::L_BRACE, "Expected '{' after enum name");
    skipNewlines();
    std::vector<std::string> variants;
    std::vector<std::vector<Annotation>> variantAnns;
    std::vector<std::vector<Expr>> caseValues;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        while (true) {
            skipNewlines();
            std::string variant = consume(TokenType::IDENTIFIER, "Expected variant name").lexeme;
            if (check(TokenType::L_PAREN)) {
                // Without a value type, parentheses are a payload.
                if (!valueType) {
                    fail("case '" + variant + "' of enum '" + name + "' carries a payload; write 'variant enum " + name +
                         " { … }' for a tagged union, or 'enum " + name + ": Type { " + variant +
                         "(value) }' for a fixed value, at line " + str(peek().line));
                }
                advance();
                skipNewlines();
                std::vector<Expr> values;
                if (!check(TokenType::R_PAREN)) {
                    do {
                        skipNewlines();
                        values.push_back(parseExpr());
                        skipNewlines();
                    } while (match(TokenType::COMMA));
                }
                consume(TokenType::R_PAREN, "Expected ')' after the value of case '" + variant + "'");
                caseValues.push_back(values);
            } else if (valueType) {
                caseValues.push_back({});
            }
            variants.push_back(variant);
            variantAnns.push_back(parseAnnotations());  // trailing `@ann` on the same line
            if (!match(TokenType::COMMA)) break;
            skipNewlines();
            if (!check(TokenType::IDENTIFIER)) break;
        }
    }
    consume(TokenType::R_BRACE, "Expected '}' after enum variants");
    consumeNewline();
    declaredEnums->set(name, variants);
    return make(arena, TopLevelEnum{name, variants, start.line, start.column, annotations, variantAnns, enumTypeParams,
                                    enumGenerics.boundClause, valueType, caseValues});
}

/** `fail ErrSet { V1, V2 }` - an error-set declaration. */
TopLevelFail* Parser::parseFailDecl(const std::vector<Annotation>& annotations /*= {}*/) {
    Token start = peek();
    consume(TokenType::ERROR, "Expected 'fail'");
    std::string name = consume(TokenType::IDENTIFIER, "Expected error-set name").lexeme;
    // `error Name<T>` - an error set may be generic over what its cases carry.
    TypeParams failGenerics = parseTypeParams();
    std::vector<std::string> failTypeParams = failGenerics.names;
    consume(TokenType::L_BRACE, "Expected '{' after error-set name");
    skipNewlines();
    std::vector<std::string> variants;
    std::vector<std::vector<Annotation>> variantAnns;
    std::vector<std::vector<TypeRef>> variantPayloads;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        do {
            std::string variant = consume(TokenType::IDENTIFIER, "Expected variant name").lexeme;
            // A payload is what separates the two error forms.
            if (check(TokenType::L_PAREN)) {
                fail("case '" + variant + "' of error set '" + name + "' carries a payload; write 'variant error " + name +
                     " { … }' for a tagged union, at line " + str(peek().line));
            }
            variants.push_back(variant);
            variantPayloads.push_back({});
            variantAnns.push_back(parseAnnotations());  // trailing `@ann` on the same line
        } while (match(TokenType::COMMA) && check(TokenType::IDENTIFIER));
    }
    consume(TokenType::R_BRACE, "Expected '}' after error-set variants");
    consumeNewline();
    // A payload-carrying error set is also a slot.
    bool anyPayload = false;
    for (const auto& payload : variantPayloads) {
        if (!payload.empty()) anyPayload = true;
    }
    if (anyPayload) {
        std::vector<SlotVariant> slotVariants;
        for (size_t i = 0; i < variants.size(); i++) slotVariants.push_back(SlotVariant{variants[i], variantPayloads[i]});
        pendingTopLevels.push_back(make(arena, TopLevelSlot{name, slotVariants, start.line, start.column, annotations}));
    }
    return make(arena, TopLevelFail{name, variants, start.line, start.column, annotations, variantAnns, variantPayloads,
                                    failTypeParams, failGenerics.boundClause});
}

/**
 * `<T, U>` after a spec implementation's target - the parameters it declares,
 * kept in lastImplTypeParams and lastImplVariadicParam. A bound on the
 * target is refused.
 */
void Parser::parseImplTargetParams(const std::string& target) {
    lastImplTypeParams = {};
    lastImplVariadicParam = std::nullopt;
    if (!check(TokenType::LESS)) return;
    Token at = peek();
    std::vector<TypeRef> args = parseGenericTypeArgsIfPresent(true);
    Expr bounds = lastDeclaredBounds;
    lastDeclaredBounds = nullptr;
    if (bounds) {
        Expr walk = bounds;
        while (ExprBinary* binary = as<ExprBinary>(walk)) walk = binary->left;
        ExprIsCheck* bound = as<ExprIsCheck>(walk);
        std::string param = "T";
        if (bound) {
            if (ExprIdentifier* id = as<ExprIdentifier>(bound->expr)) param = id->name;
        }
        std::string spec = bound ? bound->typeName : "Spec";
        fail("a bound on an implementation's target would make the spec hold for some '" + param +
             "' and not others, which is not supported; bound the type itself ('pack " + target + "<" + param + ": " +
             spec + ">') or each member ('where " + param + ": " + spec + "') at line " + str(at.line));
    }
    lastImplTypeParams = implParamNames(target, args, at);
    for (const TypeRef& arg : args) {
        TypeRefNamed* named = as<TypeRefNamed>(arg);
        if (named && named->variadic) {
            lastImplVariadicParam = named->name;
            break;
        }
    }
}

/**
 * The names an implementation's target declares as its parameters, one per
 * argument. An argument that is a type rather than a name is refused.
 */
std::vector<std::string> Parser::implParamNames(const std::string& target, const std::vector<TypeRef>& args,
                                                const Token& at) {
    std::vector<std::string> names;
    for (const TypeRef& arg : args) {
        TypeRefNamed* named = as<TypeRefNamed>(arg);
        if (named && named->args.empty() && !named->qualifier && named->valueArgs.empty() && named->name != TYPE_HOLE) {
            names.push_back(named->name);
            continue;
        }
        std::vector<std::string> written;
        for (const TypeRef& a : args) written.push_back(toString(a));
        fail("'" + target + "<" + join(written, ", ") +
             ">': an implementation's target declares its parameters, one name each, as in '" + target + "<T>'; '" +
             toString(arg) + "' is " + (is<TypeRefConst>(arg) ? "a value" : "a type") +
             ", and an implementation for one kind of '" + target +
             "' is not supported (generics are erased) at line " + str(at.line));
    }
    return names;
}

/** Whether `N: <kind>` declares a const parameter (`N: Int`, `O: MatrixOrder`) rather than a bound. */
bool Parser::isConstParameterKind(const std::string& kind) { return kind == "Int" || declaredEnums->contains(kind); }

/**
 * Parses `<T, U>` generic type arguments at the current position. Where the
 * list [declaresParams], an entry may carry a bound, kept as the clause
 * lastDeclaredBounds; anywhere else a bound is refused.
 */
std::vector<TypeRef> Parser::parseGenericTypeArgsIfPresent(bool declaresParams /*= false*/) {
    if (declaresParams) lastDeclaredBounds = nullptr;
    if (!check(TokenType::LESS)) return {};
    advance();  // '<'
    std::vector<TypeRef> args;
    Expr bounds;
    if (!check(TokenType::GREATER)) {
        do {
            TypeRef arg = parseTypeArg();
            args.push_back(arg);
            if (check(TokenType::COLON)) {
                Token colon = advance();
                std::optional<std::string> param;
                if (TypeRefNamed* named = as<TypeRefNamed>(arg)) {
                    if (named->args.empty() && !named->qualifier && !named->variadic) param = named->name;
                }
                Token at = peek();
                TypeRef constraint = parseTypeName();
                std::optional<std::string> kind;
                if (TypeRefNamed* named = as<TypeRefNamed>(constraint)) kind = named->name;
                if (!declaresParams || !param) {
                    fail("'" + toString(arg) + ": " + toString(constraint) +
                         "' bounds a type parameter, and nothing is declared here; write the bound where '" +
                         toString(arg) + "' is declared at line " + str(colon.line));
                }
                if (!kind) {
                    fail("a bound names a spec, as in '" + *param + ": Shape', not '" + toString(constraint) +
                         "', at line " + str(colon.line));
                }
                if (!isConstParameterKind(*kind)) {
                    ExprIsCheck isCheck;
                    isCheck.expr = make(arena, ExprIdentifier{*param, at.line, at.column, u16len(*param)});
                    isCheck.typeName = *kind;
                    isCheck.line = at.line;
                    isCheck.column = at.column;
                    isCheck.typeArgs = cast<TypeRefNamed>(constraint).args;
                    Expr checkExpr = make(arena, isCheck);
                    if (bounds) {
                        bounds = make(arena, ExprBinary{.left = bounds, .op = TokenType::AND_AND, .right = checkExpr,
                                                        .line = at.line});
                    } else {
                        bounds = checkExpr;
                    }
                }
            }
        } while (matchTypeArgumentComma());
    }
    consumeTypeArgumentClose("Expected '>' after generic type arguments");
    if (declaresParams) lastDeclaredBounds = bounds;
    return args;
}

// A split `>>` still has a delimiter before the next physical token. Until its
// enclosing list consumes that delimiter, a comma belongs outside it.
bool Parser::matchTypeArgumentComma() { return !pendingGreater && match(TokenType::COMMA); }

void Parser::consumeTypeArgumentClose(const std::string& message) {
    if (pendingGreater) {
        pendingGreater = false;
    } else if (check(TokenType::SHIFT_RIGHT)) {
        advance();
        pendingGreater = true;
    } else {
        consume(TokenType::GREATER, message);
    }
}

/** `(a, b)` after a compile-time type call's type arguments. */
std::vector<Expr> Parser::parseTypeCallValueArgs() {
    consume(TokenType::L_PAREN, "Expected '(' before type call arguments");
    std::vector<Expr> args;
    if (!check(TokenType::R_PAREN)) {
        do {
            skipNewlines();
            args.push_back(parseExpr());
            skipNewlines();
        } while (match(TokenType::COMMA));
    }
    consume(TokenType::R_PAREN, "Expected ')' after type call arguments");
    return args;
}

/**
 * A single type argument: a type, or a const value (`3` in `Array<Int, 3>`).
 * An integer literal becomes a TypeRef.Const.
 */
TypeRef Parser::parseTypeArg() {
    if (check(TokenType::INT_LITERAL)) {
        Token t = advance();
        return make(arena, TypeRefConst{std::get<int64_t>(std::get<NumericLiteral>(t.literal).value)});
    }
    // `Mat<Double, 4, 4, .ColumnMajor>` - an enum variant as a const argument.
    if (check(TokenType::DOT) ||
        (check(TokenType::IDENTIFIER) && peekNextIs(TokenType::DOT) && declaredEnums->contains(peek().lexeme))) {
        return parseConstArgument(std::nullopt, "type argument");
    }
    if (check(TokenType::STAR)) {
        advance();
        TypeRefNamed star;
        star.name = "*";
        return make(arena, star);
    }
    // `add<Short, _, Long>(1, 2, 3)` - a hole says "work this one out".
    if (check(TokenType::IDENTIFIER) && peek().lexeme == TYPE_HOLE) {
        advance();
        TypeRefNamed hole;
        hole.name = TYPE_HOLE;
        return make(arena, hole);
    }
    // `tupleOf<Int, ...T>(…)` - a type pack spread among the type arguments.
    if (check(TokenType::ELLIPSIS)) {
        advance();
        TypeRef inner = parseTypeName();
        if (TypeRefNamed* named = as<TypeRefNamed>(inner)) {
            TypeRefNamed copy = *named;
            copy.variadic = true;
            return make(arena, copy);
        }
        fail("only a named type can be spread in type arguments at line " + str(peek().line));
    }
    return parseTypeName();
}

std::string Parser::typeMethodSuffix(const TypeRef& type) { return UseAsTemplate::typeMemberSuffix(type); }

std::string Parser::castMethodName(const TypeRef& type) { return "as" + typeMethodSuffix(type); }

std::string Parser::callbackTraitMethodName(const std::string& traitName, const std::vector<TypeRef>& traitArgs) {
    return callbackTraitMethodName(traitName, traitArgs, callbackSpecs.get(traitName));
}

std::string Parser::callbackTraitMethodName(const std::string& traitName, const std::vector<TypeRef>& traitArgs,
                                            const SpecCallback* callback) {
    if (callback && callback->useAsTemplate) {
        return UseAsTemplate::expand(*callback->useAsTemplate, callback->typeParams, traitArgs);
    }
    return callbackMethodBaseName(traitName);
}

TypeAnnotation Parser::callbackTraitReturnType(const std::string& traitName, const std::vector<TypeRef>& traitArgs) {
    const SpecCallback* callback = callbackSpecs.get(traitName);
    return explicitType(substituteCallbackType(callback ? callback->returnType : TypeRef(),
                                               callback ? callback->typeParams : std::vector<std::string>(), traitArgs));
}

std::vector<Param> Parser::callbackTraitParams(const std::string& traitName, const std::vector<TypeRef>& traitArgs) {
    const SpecCallback* callback = callbackSpecs.get(traitName);
    if (callback == nullptr) return {};
    return callbackTraitParams(*callback, traitArgs);
}

std::vector<Param> Parser::callbackTraitParams(const SpecCallback& callback, const std::vector<TypeRef>& traitArgs) {
    std::vector<Param> out;
    for (const Param& param : callback.params) {
        Param copy = param;
        copy.type = substituteCallbackType(param.type, callback.typeParams, traitArgs);
        out.push_back(copy);
    }
    return out;
}

MemberCallStyle Parser::callbackTraitCallStyle(const std::string& traitName) {
    const SpecCallback* callback = callbackSpecs.get(traitName);
    return callback && callback->requiresParens ? MemberCallStyle::METHOD : MemberCallStyle::PROPERTY;
}

TypeRef Parser::substituteCallbackType(const TypeRef& type, const std::vector<std::string>& typeParams,
                                       const std::vector<TypeRef>& traitArgs) {
    if (!type) {
        if (!traitArgs.empty()) return traitArgs[0];
        TypeRefNamed any;
        any.name = "Any";
        any.synthesized = true;
        return make(arena, any);
    }
    TypeRefNamed* named = as<TypeRefNamed>(type);
    if (named == nullptr) return type;
    for (size_t i = 0; i < typeParams.size(); i++) {
        if (typeParams[i] == named->name) return i < traitArgs.size() ? traitArgs[i] : type;
    }
    return type;
}

/**
 * Parses the name after `impl`, accepting a scope qualifier; the qualifier is
 * dropped once it has been read.
 */
Parser::ContractHead Parser::parseContractName(const std::string& message) {
    Token firstToken = consume(TokenType::IDENTIFIER, message);
    std::vector<std::string> path{firstToken.lexeme};
    Token writtenName = firstToken;
    while (check(TokenType::DOUBLE_COLON) && peekNextIs(TokenType::IDENTIFIER)) {
        advance();  // '::'
        writtenName = advance();
        path.push_back(writtenName.lexeme);
    }
    ContractHead head;
    head.name = path.back();
    if (path.size() > 1) head.qualifier = join(std::vector<std::string>(path.begin(), path.end() - 1), "::");
    head.line = writtenName.line;
    head.column = writtenName.column;
    head.length = u16len(writtenName.lexeme);
    return head;
}

/** Parses `Type`, `Type::member`, or `Type::*` into the semantic site identity. */
std::string Parser::parseImplTarget() {
    std::string owner = consume(TokenType::IDENTIFIER, "Expected implementation target").lexeme;
    if (!match(TokenType::DOUBLE_COLON)) {
        parseImplTargetParams(owner);
        return owner;
    }
    std::string member;
    if (match(TokenType::STAR)) member = "*";
    else member = consume(TokenType::IDENTIFIER, "Expected member name or '*' after '::'").lexeme;
    // A scope-qualified target carries its parameters like any other.
    parseImplTargetParams(member);
    return owner + "." + member;
}

/** Expands any compile-time type-list variable target into its member types. */
std::vector<std::string> Parser::expandTypeListTargets(const std::vector<std::string>& targets) {
    std::vector<std::string> out;
    for (const std::string& target : targets) {
        std::optional<std::vector<std::string>> list = comptimeList(target);
        if (list) out.insert(out.end(), list->begin(), list->end());
        else out.push_back(target);
    }
    return out;
}

/** True when `impl (A, B) {` opens here - a type tuple standing on its own. */
bool Parser::isImplTargetListAhead() {
    TokenType open = TokenType::L_PAREN;
    TokenType close = TokenType::R_PAREN;
    if (!check(open)) return false;
    int32_t i = current;
    int depth = 0;
    while (i < (int32_t)tokens.size()) {
        TokenType t = tokens[(size_t)i].type;
        if (t == open) {
            depth++;
        } else if (t == close) {
            depth--;
            if (depth == 0) {
                i++;
                break;
            }
        }
        i++;
    }
    while (typeIs(i, TokenType::NEWLINE)) i++;
    return typeIs(i, TokenType::L_BRACE);
}

std::vector<std::string> Parser::parseImplTargets() {
    std::vector<std::string> targets;
    bool isRow = parseParenthesizedList("implementation target", [&] { targets.push_back(parseImplTarget()); });
    if (!isRow) targets.push_back(parseImplTarget());
    return targets;
}

TopLevelImpl* Parser::queueExpandedImpls(const std::vector<ContractHead>& traits, const std::vector<std::string>& targets,
                                         const Token& start, const std::vector<Expr>& args,
                                         const std::vector<std::pair<std::string, Expr>>& namedArgs, bool isBridge,
                                         const std::vector<Annotation>& annotations,
                                         const std::vector<std::string>& typeParams,
                                         const std::optional<std::string>& variadicParam, bool hasBody) {
    std::vector<TopLevelImpl*> implementations;
    for (const std::string& target : targets) {
        for (const ContractHead& head : traits) {
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
            impl.decoratorArgs = args;
            impl.decoratorNamedArgs = namedArgs;
            impl.annotations = annotations;
            impl.isBridge = isBridge;
            impl.typeParams = typeParams;
            impl.variadicParam = variadicParam;
            impl.hasBody = hasBody;
            implDefaults(impl);
            implementations.push_back(make(arena, impl));
        }
    }
    for (size_t i = 1; i < implementations.size(); i++) pendingTopLevels.push_back(implementations[i]);
    return first(implementations);
}

}  // namespace azora
