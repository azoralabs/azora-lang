// Parser.kt lines 6807-7187: parameters, const arguments, type parameters,
// bounds and `where` clauses.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/numbers.h"
#include "base/text.h"
#include "frontend/ast_util.h"

#include <algorithm>

namespace azora {

std::vector<Param> Parser::parseParams(const std::optional<std::string>& variadicTypeParam /*= std::nullopt*/) {
    skipNewlines();
    if (check(TokenType::R_PAREN)) return {};
    std::vector<Param> params;
    while (!check(TokenType::R_PAREN)) {
        // A borrow is a postfix sigil on the name (`x&`, `x!`), not a prefix modifier.
        ParamModifier modifier = ParamModifier::NONE;
        // Optional `...name` spread marker for a variadic parameter.
        bool nameSpread = match(TokenType::ELLIPSIS);
        std::string name = consumeIdentifierLike("Expected parameter name");
        consume(TokenType::COLON, "Expected ':' after parameter name");
        // `name: return T&` - the borrow this function hands back (§13).
        bool returnsOwnership = match(TokenType::RETURN);
        // A parameter may carry a macro (`@vec`), which is lowercase, as well as a
        // decorator, which is not. Only the decorators are read here.
        std::vector<Annotation> annotations;
        std::optional<Token> next = peekNext();
        if (!(check(TokenType::AT) && next && firstIsLowerCase(next->lexeme))) annotations = parseAnnotations();
        // Type-side `...T` expands a declared variadic generic type pack.
        bool typeSpread = check(TokenType::ELLIPSIS);
        if (typeSpread) {
            std::optional<Token> after = peekNext();
            std::optional<std::string> spreadName;
            if (after && after->type == TokenType::IDENTIFIER) spreadName = after->lexeme;
            if (!variadicTypeParam || spreadName != variadicTypeParam) {
                std::string spread = spreadName ? *spreadName : "";
                fail("Type spread '..." + spread + "' requires a matching variadic generic declaration '<..." + spread +
                     ">' at line " + str(peek().line) + "; for homogeneous varargs use '..." + name + ": " + spread + "'");
            }
        }
        bool isVariadic = nameSpread || typeSpread;
        TypeRef parsedType = parseTypeName();
        TypeRefReference* reference = as<TypeRefReference>(parsedType);
        TypeRef unwrappedType = reference ? reference->inner : parsedType;
        TypeRef type = unwrappedType;
        if (nameSpread && !is<TypeRefArray>(unwrappedType)) type = make(arena, TypeRefArray{unwrappedType});
        ParamModifier normalizedModifier = reference ? paramModifier(reference->kind) : modifier;
        Expr defaultValue;
        if (match(TokenType::EQUAL)) defaultValue = parseExpr();
        Param param;
        param.name = name;
        param.type = type;
        param.defaultValue = defaultValue;
        param.modifier = normalizedModifier;
        param.variadic = isVariadic;
        param.annotations = annotations;
        param.returnsOwnership = returnsOwnership;
        params.push_back(param);
        const Token* previous = tokenOrNull(current - 1);
        int32_t previousLine = previous ? previous->line : peek().line;
        bool separatedByPhysicalLine = peek().line > previousLine;
        if (match(TokenType::COMMA)) {
            skipNewlines();
            if (check(TokenType::R_PAREN)) break;
        } else if (check(TokenType::NEWLINE)) {
            skipNewlines();
            if (check(TokenType::R_PAREN)) break;
        } else if (separatedByPhysicalLine) {
            continue;
        } else {
            break;
        }
    }
    return params;
}

/**
 * A const-generic value written as an argument or a default: an integer, or
 * `.RowMajor` for its position in the enum it belongs to (keeping the name).
 */
TypeRef Parser::parseConstArgument(const std::optional<std::string>& enumName, const std::string& parameter) {
    if (check(TokenType::INT_LITERAL)) {
        Token token = advance();
        return make(arena, TypeRefConst{std::get<int64_t>(std::get<NumericLiteral>(token.literal).value)});
    }
    std::optional<std::string> qualifier;
    if (check(TokenType::IDENTIFIER) && peekNextIs(TokenType::DOT)) qualifier = advance().lexeme;
    if (!match(TokenType::DOT)) fail("Expected a value for const parameter '" + parameter + "' at line " + str(peek().line));
    std::string variant = consume(TokenType::IDENTIFIER, "Expected enum variant after '.'").lexeme;
    // A library's enum may not have been parsed yet, so an unknown variant stays
    // symbolic and is resolved once the whole program is in view.
    std::string owner;
    if (qualifier) {
        owner = *qualifier;
    } else if (enumName) {
        owner = *enumName;
    } else {
        std::vector<std::string> owners;
        for (const auto& [key, variants] : declaredEnums->entries) {
            if (contains(variants, variant)) owners.push_back(key);
        }
        if (owners.size() == 1) {
            owner = owners[0];
        } else if (owners.empty()) {
            return make(arena, TypeRefConst{std::numeric_limits<int64_t>::min(), variant});
        } else {
            std::vector<std::string> sorted = owners;
            std::stable_sort(sorted.begin(), sorted.end(),
                             [](const std::string& a, const std::string& b) { return compareUtf16(a, b) < 0; });
            fail("'." + variant + "' is ambiguous between " + join(sorted, ", ") + "; qualify it, as in '" + sorted[0] +
                 "." + variant + "', at line " + str(peek().line));
        }
    }
    const std::vector<std::string>* variants = declaredEnums->get(owner);
    if (variants == nullptr) fail("'" + owner + "' is not a declared enum, at line " + str(peek().line));
    int64_t ordinal = -1;
    for (size_t i = 0; i < variants->size(); i++) {
        if ((*variants)[i] == variant) {
            ordinal = (int64_t)i;
            break;
        }
    }
    if (ordinal < 0) fail("'" + owner + "' has no variant '" + variant + "', at line " + str(peek().line));
    return make(arena, TypeRefConst{ordinal, variant});
}

/**
 * Refuses a bound where no use binds the parameter to a type a check could be
 * made for.
 */
void Parser::refuseBounds(const TypeParams& params, const std::string& what, int32_t line,
                          const std::string& why /*= "nothing checks a bound written there"*/) {
    if (!params.boundClause) return;
    fail(what + " cannot bound a type parameter: " + why + "; remove the ': Spec' at line " + str(line));
}

/**
 * Refuses a bound on a property's own type parameters, inline or in its
 * `where` clause: nothing could ever bind one to a type to check.
 */
void Parser::refuseOwnBounds(const TypeParams& params, const Expr& where, int32_t line) {
    refuseBounds(params, "a property", line, "no use writes a property's type argument, so nothing checks the bound");
    std::function<bool(const Expr&)> boundsOwn = [&](const Expr& expr) -> bool {
        if (ExprIsCheck* isCheck = as<ExprIsCheck>(expr)) {
            ExprIdentifier* id = as<ExprIdentifier>(isCheck->expr);
            return id != nullptr && contains(params.names, id->name);
        }
        if (ExprBinary* binary = as<ExprBinary>(expr)) return boundsOwn(binary->left) || boundsOwn(binary->right);
        if (ExprGrouping* grouping = as<ExprGrouping>(expr)) return boundsOwn(grouping->expr);
        if (ExprUnary* unary = as<ExprUnary>(expr)) return boundsOwn(unary->operand);
        return false;
    };
    if (where && boundsOwn(where)) {
        fail("a property cannot bound its own type parameter: no use writes a property's type argument, so nothing "
             "checks the bound; remove it from the 'where' clause at line " +
             str(line));
    }
}

/** [inlineBound] and [written] bounds as one where clause, inline ones first. */
Expr Parser::withInlineBounds(const Expr& inlineBound, const Expr& written, int32_t line) {
    if (!inlineBound) return written;
    if (!written) return inlineBound;
    return make(arena, ExprBinary{.left = inlineBound, .op = TokenType::AND_AND, .right = written, .line = line});
}

/** `<T, U>` type-parameter list: `...T` variadic, `N: Int` const, `T: Spec` bounds, defaults. */
Parser::TypeParams Parser::parseTypeParams() {
    if (!check(TokenType::LESS)) return TypeParams{};
    advance();
    TypeParams out;
    do {
        bool prefixVariadic = match(TokenType::ELLIPSIS);  // `...T` prefix form
        std::string name = consume(TokenType::IDENTIFIER, "Expected type parameter name").lexeme;
        // `spec From<T::>` - the member this parameter drives is a *static*.
        if (check(TokenType::DOUBLE_COLON)) {
            advance();
            out.staticParams.add(name);
        }
        if (check(TokenType::ELLIPSIS)) {
            fail("Variadic type parameters use the prefix form '..." + name + "', not '" + name + "...', at line " +
                 str(peek().line));
        }
        // Optional kind/constraint after `:`.
        std::optional<std::string> constEnum;
        if (match(TokenType::COLON)) {
            Token at = peek();
            TypeRef constraint = parseTypeName();
            TypeRefNamed* constraintNamed = as<TypeRefNamed>(constraint);
            if (constraintNamed && constraintNamed->name == "Int") {
                out.constParams.add(name);
            } else if (constraintNamed && declaredEnums->contains(constraintNamed->name)) {
                out.constParams.add(name);
                constEnum = constraintNamed->name;
                out.constEnums.set(name, constraintNamed->name);
            } else if (constraintNamed) {
                // A conformance bound means what `where T: Spec` means.
                ExprIsCheck isCheck;
                isCheck.expr = make(arena, ExprIdentifier{name, at.line, at.column, u16len(name)});
                isCheck.typeName = constraintNamed->name;
                isCheck.line = at.line;
                isCheck.column = at.column;
                isCheck.typeArgs = constraintNamed->args;
                Expr checkExpr = make(arena, isCheck);
                if (out.boundClause) {
                    out.boundClause = make(arena, ExprBinary{.left = out.boundClause, .op = TokenType::AND_AND,
                                                             .right = checkExpr, .line = at.line});
                } else {
                    out.boundClause = checkExpr;
                }
            } else {
                fail("a bound names a spec, as in '" + name + ": Shape', not '" + toString(constraint) + "', at line " +
                     str(at.line));
            }
        }
        // `= <default>` - a const value default or a type default.
        if (match(TokenType::EQUAL)) {
            bool isConstDefault = out.constParams.contains(name) || check(TokenType::INT_LITERAL) || check(TokenType::DOT);
            if (isConstDefault) {
                out.constDefaults.set(name, parseConstArgument(constEnum, name));
                out.constParams.add(name);
            } else {
                out.typeDefaults.set(name, parseTypeName());
            }
        }
        if (prefixVariadic) out.variadic = name;
        out.names.push_back(name);
    } while (matchTypeArgumentComma());
    // A declaration's parameter list has no enclosing generic application:
    // consume a close left by its bound/default, but reject a fresh `>>`.
    if (pendingGreater) pendingGreater = false;
    else consume(TokenType::GREATER, "Expected '>' after type parameters");
    return out;
}

/**
 * `where <expr>` - a declaration's constraints, parsed as an ordinary
 * expression (conformance bounds `T: Spec` first).
 */
Expr Parser::parseWhereClause() {
    // A clause may sit on its own line, after a signature or a spliced fragment.
    int32_t at = nextMeaningfulIndex();
    if (!typeIs(at, TokenType::WHERE)) return nullptr;
    while (current < at) advance();
    advance();  // 'where'
    bool savedTrailing = allowTrailingLambda;
    allowTrailingLambda = false;
    Finally restore{[&] { allowTrailingLambda = savedTrailing; }};
    Expr bounds = parseSpecBounds();
    if (bounds) return bounds;
    return parseExpr();
}

/**
 * `where T: Order`, `where T: PartialEqual<U>`, `where K: [Order, Clone]`,
 * `where T: Order, U: Clone` - conformance bounds, each an `is` predicate.
 * Returns null when what follows is not a bound.
 */
Expr Parser::parseSpecBounds() {
    if (!isSpecBoundAhead()) return nullptr;
    Expr clause;
    do {
        Token subject = peek();
        std::string name = consume(TokenType::IDENTIFIER, "Expected a type parameter before ':'").lexeme;
        consume(TokenType::COLON, "Expected ':' in a conformance bound");
        std::vector<std::pair<std::string, std::vector<TypeRef>>> specs;
        if (match(TokenType::L_BRACKET)) {
            do {
                std::string spec = consume(TokenType::IDENTIFIER, "Expected a spec name in the bound list").lexeme;
                std::vector<TypeRef> args = parseGenericTypeArgsIfPresent();
                specs.emplace_back(spec, args);
            } while (match(TokenType::COMMA));
            consume(TokenType::R_BRACKET, "Expected ']' after the bound list");
        } else {
            std::string spec = parseQualifiedTypeName("Expected a spec name after ':'");
            std::vector<TypeRef> args = parseGenericTypeArgsIfPresent();
            specs.emplace_back(spec, args);
        }
        for (const auto& [spec, args] : specs) {
            ExprIsCheck isCheck;
            isCheck.expr = make(arena, ExprIdentifier{name, subject.line, subject.column, u16len(name)});
            isCheck.typeName = spec;
            isCheck.line = subject.line;
            isCheck.column = subject.column;
            isCheck.typeArgs = args;
            Expr checkExpr = make(arena, isCheck);
            if (clause) {
                clause = make(arena, ExprBinary{.left = clause, .op = TokenType::AND_AND, .right = checkExpr,
                                                .line = subject.line});
            } else {
                clause = checkExpr;
            }
        }
    } while (match(TokenType::COMMA) && isSpecBoundAhead());
    return clause;
}

/** True when an `IDENTIFIER :` bound opens here rather than an expression. */
bool Parser::isSpecBoundAhead() { return check(TokenType::IDENTIFIER) && peekNextIs(TokenType::COLON); }

/**
 * The minimum length a `where` clause requires of a variadic pack, or null:
 * `(...T).length >= N` and the older `T.length >= N`, anywhere in the clause.
 */
std::optional<int32_t> Parser::variadicMinLengthOf(const Expr& clause) {
    if (!clause) return std::nullopt;
    if (ExprGrouping* grouping = as<ExprGrouping>(clause)) return variadicMinLengthOf(grouping->expr);
    ExprBinary* binary = as<ExprBinary>(clause);
    if (binary && binary->op == TokenType::AND_AND) {
        std::optional<int32_t> left = variadicMinLengthOf(binary->left);
        if (left) return left;
        return variadicMinLengthOf(binary->right);
    }
    if (binary == nullptr) return std::nullopt;
    if (binary->op != TokenType::GREATER_EQUAL) return std::nullopt;
    ExprMember* member = as<ExprMember>(binary->left);
    if (member == nullptr || member->name != "length") return std::nullopt;
    ExprIntLiteral* literal = as<ExprIntLiteral>(binary->right);
    if (literal == nullptr) return std::nullopt;
    return toIntOrNull(str((long long)literal->value), 10);
}

std::optional<int32_t> Parser::parseVariadicWhereClause() { return variadicMinLengthOf(parseWhereClause()); }

}  // namespace azora
