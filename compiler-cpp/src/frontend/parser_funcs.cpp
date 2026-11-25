// Parser.kt lines 6127-6807: function declarations, contract clauses, bracket
// constructors, member bodies and names.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"
#include "frontend/lexer.h"

namespace azora {

namespace {

TypeRef named(Arena& arena, const std::string& name, std::vector<TypeRef> args = {}, bool synthesized = false) {
    TypeRefNamed ref;
    ref.name = name;
    ref.args = std::move(args);
    ref.synthesized = synthesized;
    return make(arena, ref);
}

std::string trimQuotes(const std::string& text) {
    size_t begin = 0;
    size_t end = text.size();
    while (begin < end && text[begin] == '"') begin++;
    while (end > begin && text[end - 1] == '"') end--;
    return text.substr(begin, end - begin);
}

}  // namespace

FuncDecl Parser::parseFuncDecl(const FuncDeclOptions& o /*= {}*/) {
    Token start = peek();
    // `async func name(…)` - `async` qualifies `func` rather than replacing it.
    if (o.isTask) {
        consume(TokenType::ASYNC, "Expected 'async'");
        consume(TokenType::FUNC, "Expected 'func' after 'async'");
    } else {
        consume(TokenType::FUNC, "Expected 'func'");
    }
    // Generic parameters qualify the declaration itself, so they follow `func`
    // and precede an optional receiver.
    TypeParams typeParamsBefore = parseTypeParams();
    std::optional<PropReceiver> prefixReceiver;
    if (currentImplTypeName) {
        prefixReceiver = parseImplicitMemberReceiver();
        if (!prefixReceiver) prefixReceiver = parseExplicitMemberReceiver();
        if (!prefixReceiver) prefixReceiver = parseTypedMemberReceiver();
    } else {
        if (check(TokenType::AMP) || check(TokenType::BANG) || check(TokenType::DOT)) {
            // Show the spelling with the author's own sigil and member name.
            std::string sigil = check(TokenType::DOT) ? "" : peek().lexeme;
            const Token* memberToken = tokenOrNull(current + (sigil.empty() ? 1 : 2));
            std::string member =
                memberToken && memberToken->type == TokenType::IDENTIFIER ? memberToken->lexeme : "member";
            fail("line " + str(peek().line) +
                 ": '&.', '!.' and '.' receiver shorthands require an impl or spec; a free extension writes its receiver "
                 "type, for example 'func Type" +
                 sigil + "." + member + "()'");
        }
        prefixReceiver = parseExplicitMemberReceiver();
        if (!prefixReceiver) prefixReceiver = parseTypedMemberReceiver();
    }
    std::string name = prefixReceiver || o.inImplBlock ? consumeMemberName("Expected function name") : freeFunctionName();
    if (check(TokenType::LESS)) {
        fail("line " + str(start.line) + ": function type parameters follow 'func'; write 'func<T> " + name +
             "(…)', not 'func " + name + "<T>(…)'");
    }
    const TypeParams& parsedTypeParams = typeParamsBefore;
    std::vector<std::string> typeParams = parsedTypeParams.names;
    std::optional<std::string> variadicParam = parsedTypeParams.variadic;
    OrderedSet<std::string> constParams = parsedTypeParams.constParams;
    // Prefix receivers name the member's capability before its name; naming a
    // type makes the function an extension.
    std::optional<Param> extensionReceiver;
    std::optional<PropReceiver> bracketReceiver = prefixReceiver;
    // Receivers past the member's own lead the parameters.
    std::vector<Param> scopeReceivers = pendingMemberContextReceivers;
    pendingMemberContextReceivers = {};
    if (check(TokenType::L_BRACKET)) {
        fail("line " + str(peek().line) + ": function receivers no longer use brackets; write 'func &." + name +
             "(…)' in an impl or 'func Type&." + name + "(…)' for an extension");
    }
    if (!o.inImplBlock && bracketReceiver && bracketReceiver->type) {
        Param receiver;
        receiver.name = bracketReceiver->name;
        receiver.type = bracketReceiver->type;
        extensionReceiver = receiver;
    }
    consume(TokenType::L_PAREN, "Expected '(' after function name");
    std::vector<Param> params = concat(scopeReceivers, parseParams(variadicParam));
    consume(TokenType::R_PAREN, "Expected ')' after parameters");
    // Whether the author wrote a return type at all; an omitted return type
    // *means* Unit, but the diagnostic needs to know which.
    bool returnTypeDeclared = true;
    TypeAnnotation returnType;
    if (match(TokenType::COLON)) {
        returnType = explicitType(parseTypeName());
    } else if (check(TokenType::QMARK_BANG)) {
        // `func f() ?! E` - a function that yields nothing but may fail.
        returnTypeDeclared = false;
        returnType = explicitType(parseTypeSuffixes(named(arena, "Unit", {}, true)));
    } else {
        returnTypeDeclared = false;
    }
    Expr funcWhereClause = withInlineBounds(typeParamsBefore.boundClause, parseWhereClause(), start.line);
    std::optional<int32_t> minVariadicLength = variadicMinLengthOf(funcWhereClause);
    // A receiver is part of the declaration signature.
    ParamModifier receiverModifier = bracketReceiver ? bracketReceiver->modifier : ParamModifier::EXCLUSIVE;
    std::string receiverName = bracketReceiver ? bracketReceiver->name : "self";

    // Optional contract clauses before the body.
    ContractClauses contracts = parseContractClauses();
    skipNewlines();

    std::vector<std::string> savedFailSets = currentFailSets;
    currentFailSets = {};
    if (TypeRefFailable* failable = as<TypeRefFailable>(returnType.ref)) currentFailSets = failable->errSets;
    std::vector<Stmt> body;
    if (match(TokenType::EQUAL)) {
        // `= inline { … }` / `= deepinline { … }` blocks, or an expression body.
        if (check(TokenType::INLINE)) body = {parseInlineBlock()};
        else if (check(TokenType::DEEPINLINE)) body = {parseDeepInlineBlock()};
        else body = parseExpressionBody();
    } else {
        // For one instruction, `scope` may introduce a single statement without braces.
        bool singleScopeBody = false;
        if (check(TokenType::SCOPE)) {
            advance();
            skipNewlines();
            singleScopeBody = !check(TokenType::L_BRACE);
        }
        if (singleScopeBody && match(TokenType::EQUAL)) {
            // `scope = expr`: the expression body of a declaration with contracts.
            rejectInBraceReceiver("the function", "func " + name + "(…) scope = …");
            body = parseExpressionBody();
        } else if (singleScopeBody) {
            rejectInBraceReceiver("the function", "func " + name + "(…) scope …");
            body = {parseSingleStmt("scope body")};
            consumeNewline();
        } else {
            consume(TokenType::L_BRACE, "Expected '{' before function body");
            skipNewlines();
            if (isSelfReceiverHeaderAhead()) {
                fail("Function receivers must be declared in the signature: write 'func &." + name +
                     "(...)', not '{ self& -> ... }', at line " + str(peek().line));
            }
            // `func main() { ...args[: Type] -> body }` - bind CLI args to a
            // synthetic variadic param.
            if (name == "main" && params.empty() && check(TokenType::ELLIPSIS)) {
                advance();  // '...'
                std::string argsName = consumeIdentifierLike("Expected args name after '...' in main");
                TypeRef elemType = match(TokenType::COLON) ? parseTypeName() : named(arena, "String");
                consume(TokenType::ARROW, "Expected '->' after main args binding");
                skipNewlines();
                Param args;
                args.name = argsName;
                args.type = make(arena, TypeRefArray{elemType});
                args.variadic = true;
                params.push_back(args);
            }
            std::vector<Stmt> stmts;
            while (!check(TokenType::R_BRACE) && !isAtEnd()) {
                std::vector<Stmt> more = parseStmts();
                stmts.insert(stmts.end(), more.begin(), more.end());
                skipNewlines();
            }
            consume(TokenType::R_BRACE, "Expected '}' after function body");
            consumeNewline();
            body = stmts;
        }
    }
    std::vector<Stmt> contractedBody = applyContracts(body, contracts);
    currentFailSets = savedFailSets;
    checkReturnedParams(name, params, start.line);
    FuncDecl decl;
    decl.name = name;
    decl.params = params;
    decl.returnType = returnType;
    decl.body = contractedBody;
    decl.isInline = o.isInline;
    decl.typeParams = typeParams;
    decl.line = start.line;
    decl.column = start.column;
    decl.length = u16len(start.lexeme);
    decl.annotations = o.annotations;
    decl.isOverride = o.isOverride;
    decl.isVirtual = o.isVirtual;
    decl.isTask = o.isTask;
    decl.isReactive = o.isReactive;
    decl.isUnsafe = o.isUnsafe;
    decl.visibility = o.visibility;
    decl.returnTypeDeclared = returnTypeDeclared;
    decl.receiverModifier = receiverModifier;
    decl.receiverName = receiverName;
    decl.declaresReceiver = bracketReceiver.has_value();
    decl.extensionReceiver = extensionReceiver;
    decl.variadicParam = variadicParam;
    decl.minVariadicLength = minVariadicLength;
    decl.whereClause = funcWhereClause;
    decl.constParams = constParams;
    decl.constDefaults = parsedTypeParams.constDefaults;
    decl.constEnums = parsedTypeParams.constEnums;
    decl.contextualParams = (int32_t)scopeReceivers.size();
    decl.isDeepInline = o.isDeepInline;
    return decl;
}

/**
 * Checks `name: return T` - the parameter whose ownership goes back (§13).
 * It only makes sense where ownership actually moved.
 */
void Parser::checkReturnedParams(const std::string& name, const std::vector<Param>& params, int32_t line) {
    for (const Param& param : params) {
        if (!param.returnsOwnership) continue;
        if (param.modifier != ParamModifier::NONE) {
            fail("line " + str(line) + ": '" + param.name + "' is a borrow, so '" + name +
                 "' never takes ownership of it and has none to give back; drop the 'return', or drop the borrow to take "
                 "ownership");
        }
    }
}

bool Parser::isSelfReceiverHeaderAhead() {
    auto type = [&](int32_t j) -> std::optional<TokenType> {
        if (j < 0 || j >= (int32_t)tokens.size()) return std::nullopt;
        return tokens[(size_t)j].type;
    };
    // The receiver is always named `self`; scan past `self[borrow]` and any
    // `, operand[: Type][borrow]` pairs, and require a terminating `->`.
    int32_t j = current;
    if (lexemeIs(j, "self")) {
        j += 1;
        if (type(j) == TokenType::AMP || type(j) == TokenType::BANG) j += 1;
    } else {
        return false;
    }
    while (type(j) == TokenType::COMMA) {
        j += 1;
        if (type(j) != TokenType::IDENTIFIER) return false;
        j += 1;
        if (type(j) == TokenType::COLON) {
            // Skip the operand type; nested generics balance `<`/`>`.
            j += 1;
            int depth = 0;
            while (true) {
                std::optional<TokenType> t = type(j);
                if (!t) return false;
                if (*t == TokenType::LESS) depth++;
                else if (*t == TokenType::GREATER) depth--;
                else if (depth == 0 && (*t == TokenType::COMMA || *t == TokenType::ARROW)) break;
                j += 1;
            }
        } else if (type(j) == TokenType::AMP || type(j) == TokenType::BANG) {
            j += 1;
        }
    }
    return type(j) == TokenType::ARROW;
}

/**
 * Consumes the `scope` that introduces a contract-style body, when a `{`
 * follows it immediately.
 */
void Parser::skipScopeBodyIntroducer() {
    int32_t i = nextMeaningfulIndex();
    if (!typeIs(i, TokenType::SCOPE)) return;
    if (!typeIs(i + 1, TokenType::L_BRACE)) return;
    while (current < i) advance();  // newlines between the clause and `scope`
    advance();                      // `scope` - the `{` that follows is the body
}

/**
 * A spread has a runtime length, so a literal holding one cannot be a fixed
 * call to its target's factory.
 */
void Parser::rejectLiteralSpread() {
    if (!check(TokenType::ELLIPSIS)) return;
    fail("a spread in a collection literal at line " + str(peek().line) +
         " needs a builder, which collection literals do not have yet; build the collection explicitly");
}

/**
 * The member a `[…]` literal calls where [typeName] is expected, made from its
 * variadic constructor `ctor .(...elements: T)`.
 */
FuncDecl Parser::bracketConstructorCall(const std::string& typeName, const std::vector<std::string>& typeParams,
                                        const Param& variadic, const Token& at, const Visibility& visibility,
                                        const Expr& whereClause, const TypeRef& declared) {
    TypeRef element = variadic.type;
    if (TypeRefArray* array = as<TypeRefArray>(variadic.type)) element = array->element;
    std::vector<TypeRef> typeArgs;
    for (const std::string& param : typeParams) typeArgs.push_back(named(arena, param));
    TypeRef type = named(arena, typeName, typeArgs);
    TypeRefFailable* failsOnly = nullptr;
    if (TypeRefFailable* failable = as<TypeRefFailable>(declared)) {
        TypeRefNamed* ok = as<TypeRefNamed>(failable->ok);
        if (ok && ok->name == "Unit" && ok->synthesized) failsOnly = failable;
    }
    TypeRef built;
    if (failsOnly) built = makeFailable(arena, type, failsOnly->errSets);
    else if (declared) built = declared;
    else built = type;
    ExprCall construct;
    construct.callee = typeName;
    construct.args = {make(arena, ExprSpread{make(arena, ExprIdentifier{variadic.name, at.line, at.column}), at.line,
                                             at.column})};
    construct.line = at.line;
    construct.column = at.column;
    construct.typeArgs = typeArgs;
    Expr constructExpr = make(arena, construct);
    Param elements;
    elements.name = variadic.name;
    elements.type = make(arena, TypeRefArray{element});
    elements.variadic = true;
    FuncDecl decl;
    decl.name = BRACKET_CONSTRUCTOR;
    decl.params = {elements};
    decl.returnType = explicitType(built);
    decl.body = {make(arena, StmtReturn{.value = constructExpr, .line = at.line, .column = at.column})};
    decl.isInline = false;
    decl.line = at.line;
    decl.column = at.column;
    decl.visibility = visibility;
    decl.declaresReceiver = false;
    decl.whereClause = whereClause;
    return decl;
}

/**
 * `ctor .(...elements: T): Self { … }` in a spec - the spec's own constructor,
 * with an Azora body that builds the concrete value.
 */
FuncDecl Parser::parseSpecBracketConstructor() {
    Token start = advance();  // ctor
    std::string shape = "write 'ctor .(...elements: T): Self { … }'";
    consume(TokenType::DOT, "Expected '.' after 'ctor' in a spec at line " + str(start.line) + "; " + shape);
    consume(TokenType::L_PAREN, "Expected '(' after 'ctor .' at line " + str(start.line) + "; " + shape);
    if (!match(TokenType::ELLIPSIS)) {
        fail("a spec's constructor takes one variadic parameter at line " + str(start.line) + "; " + shape);
    }
    std::string name = consumeIdentifierLike("Expected the elements' name after '...' at line " + str(start.line));
    consume(TokenType::COLON, "Expected ':' and the element type after '..." + name + "' at line " + str(start.line));
    TypeRef element = parseTypeName();
    consume(TokenType::R_PAREN,
            "A spec's constructor takes one variadic parameter at line " + str(start.line) + "; " + shape);
    consume(TokenType::COLON, "Expected ':' and the type a spec's constructor builds at line " + str(start.line));
    TypeRef result = parseTypeName();
    // `where K: Hash` - decided for the target's arguments when a literal selects it.
    Expr whereClause = parseWhereClause();
    std::vector<Stmt> body = parseMemberBody("ctor", "ctor .(...elements: T): Self { … }");
    Param elements;
    elements.name = name;
    elements.type = make(arena, TypeRefArray{element});
    elements.variadic = true;
    FuncDecl decl;
    decl.name = BRACKET_CONSTRUCTOR;
    decl.params = {elements};
    decl.returnType = explicitType(result);
    decl.body = body;
    decl.isInline = false;
    decl.line = start.line;
    decl.column = start.column;
    decl.visibility = Visibility{};
    decl.declaresReceiver = false;
    decl.whereClause = whereClause;
    return decl;
}

/** A member body is a block, or one statement after an explicit `scope`. */
std::vector<Stmt> Parser::parseMemberBody(const std::string& kind, const std::string& signature) {
    skipNewlines();
    bool scoped = match(TokenType::SCOPE);
    skipNewlines();
    if (scoped && !check(TokenType::L_BRACE)) {
        rejectInBraceReceiver("the " + kind, signature);
        std::vector<Stmt> body{parseSingleStmt(kind + " scope body")};
        consumeNewline();
        return body;
    }
    consume(TokenType::L_BRACE, "Expected '{' after " + kind);
    skipNewlines();
    rejectInBraceReceiver("the " + kind, signature);
    std::vector<Stmt> body = parseBlock();
    consume(TokenType::R_BRACE, "Expected '}' after " + kind + " body");
    consumeNewline();
    return body;
}

/** Rejects an in-brace receiver at the head of a body just opened. */
void Parser::rejectInBraceReceiver(const std::string& declaration, const std::string& signature) {
    if (!isSelfReceiverHeaderAhead()) return;
    fail("an in-brace receiver is not a declaration at line " + str(peek().line) + "; declare " + declaration +
         " with its receiver before the parameter list, as '" + signature + "'");
}

/**
 * A member name written with a splice: `to${T.typeName}`, joined back into
 * the template text UseAsTemplate expands.
 */
std::string Parser::parseSplicedMemberName() {
    std::string out = consumeIdentifierLike("Expected the alias's name");
    while (check(TokenType::L_BRACE)) {
        advance();
        out += "{";
        int depth = 1;
        while (depth > 0 && !isAtEnd()) {
            TokenType t = peek().type;
            if (t == TokenType::L_BRACE) depth++;
            else if (t == TokenType::R_BRACE) depth--;
            out += advance().lexeme;
        }
        // `to${T}Name` - the name may continue past the placeholder.
        if (check(TokenType::IDENTIFIER)) out += advance().lexeme;
    }
    return out;
}

/** The name template of a compact one-line spec: `use as "to${T.typeName}"`. */
std::string Parser::parseUseAsTemplate() {
    Token tok = peek();
    if (tok.type == TokenType::STRING_LITERAL) {
        advance();
        const std::string* s = std::get_if<std::string>(&tok.literal);
        return s ? *s : trimQuotes(tok.lexeme);
    }
    if (tok.type == TokenType::INTERPOLATED_STRING) {
        advance();
        const std::string& l = tok.lexeme;
        if (l.size() >= 2 && l.front() == '"' && l.back() == '"') return l.substr(1, l.size() - 2);
        return l;
    }
    fail("Expected string literal after 'use as', got '" + tok.lexeme + "' (" + tokenTypeName(tok.type) + ") at line " +
         str(tok.line));
}

/** Contract clauses contain a block or a single statement; named results use `out { r -> … }`. */
Parser::ContractClauses Parser::parseContractClauses() {
    std::vector<Stmt> preconditions;
    std::vector<Stmt> postconditions;
    std::optional<std::string> resultName;
    // A function states its requirement once and its promise once.
    bool sawIn = false;
    bool sawOut = false;
    while (true) {
        int32_t i = nextMeaningfulIndex();
        const Token* t = tokenOrNull(i);
        if (t == nullptr) break;
        bool isClause = t->type == TokenType::IN || t->type == TokenType::OUT;
        if (!isClause) break;
        while (current < i) advance();  // skip newlines
        Token keyword = advance();
        if (keyword.type == TokenType::IN) {
            if (sawIn) {
                fail("A function may have one 'in' contract at line " + str(keyword.line) +
                     " - put every precondition in the same clause");
            }
            sawIn = true;
        } else {
            if (sawOut) {
                fail("A function may have one 'out' contract at line " + str(keyword.line) +
                     " - put every postcondition in the same clause");
            }
            sawOut = true;
        }
        skipNewlines();
        bool braced = match(TokenType::L_BRACE);
        skipNewlines();
        if (keyword.type == TokenType::OUT) {
            // The result binding is optional: `out { r -> … }` names it, while
            // `out { assert it >= 1 … }` leaves it implicit as `it`.
            bool namedResult = braced && check(TokenType::IDENTIFIER) && peekNextIs(TokenType::ARROW);
            std::string name;
            if (namedResult) {
                name = consumeIdentifierLike("Expected result name in out contract");
                consume(TokenType::ARROW, "Expected '->' after out contract result name");
            } else {
                name = "it";
            }
            if (resultName && *resultName != name) {
                fail("All out contract clauses for a function must use the same result name at line " +
                     str(keyword.line));
            }
            resultName = name;
            skipNewlines();
            std::vector<Stmt> more = braced ? parseBlock() : std::vector<Stmt>{parseSingleStmt("out contract")};
            postconditions.insert(postconditions.end(), more.begin(), more.end());
        } else {
            std::vector<Stmt> more = braced ? parseBlock() : std::vector<Stmt>{parseSingleStmt("in contract")};
            preconditions.insert(preconditions.end(), more.begin(), more.end());
        }
        if (braced) consume(TokenType::R_BRACE, "Expected '}' after '" + keyword.lexeme + "' contract");
        skipNewlines();
    }
    return ContractClauses{preconditions, resultName, postconditions};
}

std::vector<Stmt> Parser::applyContracts(const std::vector<Stmt>& body, const ContractClauses& contracts,
                                         bool rewriteYields /*= false*/) {
    if (contracts.preconditions.empty() && contracts.postconditions.empty()) return body;
    std::vector<Stmt> withPosts;
    if (contracts.postconditions.empty()) {
        withPosts = body;
    } else {
        for (const Stmt& stmt : body) withPosts.push_back(rewriteContractResults(stmt, contracts, rewriteYields));
    }
    return concat(contracts.preconditions, withPosts);
}

Stmt Parser::rewriteContractResults(const Stmt& stmt, const ContractClauses& contracts, bool rewriteYields) {
    auto each = [&](const std::vector<Stmt>& stmts) {
        std::vector<Stmt> out;
        for (const Stmt& s : stmts) out.push_back(rewriteContractResults(s, contracts, rewriteYields));
        return out;
    };
    auto eachOptional = [&](const std::optional<std::vector<Stmt>>& stmts) -> std::optional<std::vector<Stmt>> {
        if (!stmts) return std::nullopt;
        return each(*stmts);
    };
    switch (stmt.kind()) {
        case StmtKind::Return: return rewriteContractReturn(as<StmtReturn>(stmt), contracts);
        case StmtKind::Yield: return rewriteYields ? rewriteContractYield(as<StmtYield>(stmt), contracts) : stmt;
        case StmtKind::If: {
            StmtIf copy = cast<StmtIf>(stmt);
            copy.thenBranch = each(copy.thenBranch);
            copy.elseBranch = eachOptional(copy.elseBranch);
            return make(arena, copy);
        }
        case StmtKind::While: {
            StmtWhile copy = cast<StmtWhile>(stmt);
            copy.body = each(copy.body);
            return make(arena, copy);
        }
        case StmtKind::For: {
            StmtFor copy = cast<StmtFor>(stmt);
            copy.body = each(copy.body);
            return make(arena, copy);
        }
        case StmtKind::Loop: {
            StmtLoop copy = cast<StmtLoop>(stmt);
            copy.body = each(copy.body);
            return make(arena, copy);
        }
        case StmtKind::When: {
            StmtWhen copy = cast<StmtWhen>(stmt);
            for (WhenBranch& branch : copy.branches) branch.body = each(branch.body);
            copy.elseBranch = eachOptional(copy.elseBranch);
            return make(arena, copy);
        }
        case StmtKind::Try: {
            StmtTry copy = cast<StmtTry>(stmt);
            copy.body = each(copy.body);
            copy.catchBody = eachOptional(copy.catchBody);
            return make(arena, copy);
        }
        case StmtKind::Scope: {
            StmtScope copy = cast<StmtScope>(stmt);
            copy.body = each(copy.body);
            return make(arena, copy);
        }
        default:
            return stmt;
    }
}

Stmt Parser::rewriteContractReturn(StmtReturn* node, const ContractClauses& contracts) {
    const StmtReturn& stmt = *node;
    if (!stmt.value) return node;
    std::string resultName =
        contracts.resultName ? *contracts.resultName : "__az_contract_result_" + str(contractResultCounter++);
    Expr resultRef = make(arena, ExprIdentifier{resultName, stmt.line, stmt.column, u16len(resultName)});
    StmtFinDecl binding;
    binding.name = resultName;
    binding.initializer = stmt.value;
    binding.line = stmt.line;
    binding.column = stmt.column;
    binding.synthesized = true;
    std::vector<Stmt> body{make(arena, binding)};
    body.insert(body.end(), contracts.postconditions.begin(), contracts.postconditions.end());
    body.push_back(make(arena, StmtReturn{.value = resultRef, .line = stmt.line, .column = stmt.column}));
    return make(arena, StmtScope{.body = body, .line = stmt.line, .column = stmt.column});
}

Stmt Parser::rewriteContractYield(StmtYield* node, const ContractClauses& contracts) {
    const StmtYield& stmt = *node;
    std::string resultName =
        contracts.resultName ? *contracts.resultName : "__az_contract_result_" + str(contractResultCounter++);
    Expr resultRef = make(arena, ExprIdentifier{resultName, stmt.line, stmt.column, u16len(resultName)});
    StmtFinDecl binding;
    binding.name = resultName;
    binding.initializer = stmt.value;
    binding.line = stmt.line;
    binding.column = stmt.column;
    binding.synthesized = true;
    std::vector<Stmt> body{make(arena, binding)};
    body.insert(body.end(), contracts.postconditions.begin(), contracts.postconditions.end());
    body.push_back(make(arena, StmtYield{resultRef, stmt.line, stmt.column}));
    return make(arena, StmtScope{.body = body, .line = stmt.line, .column = stmt.column});
}

/** `then` may name a member; it remains reserved in unqualified positions. */
std::string Parser::consumeMemberName(const std::string& message) {
    if (check(TokenType::THEN)) return advance().lexeme;
    return consumeIdentifierLike(message);
}

/** True when [index] starts `async func` - an asynchronous declaration. */
bool Parser::isAsyncFuncAt(int32_t index) { return typeIs(index, TokenType::ASYNC) && typeIs(index + 1, TokenType::FUNC); }

/** True when [index] starts `async prop` - an asynchronous property. */
bool Parser::isAsyncPropAt(int32_t index) { return typeIs(index, TokenType::ASYNC) && typeIs(index + 1, TokenType::PROP); }

std::string Parser::consumeIdentifierLike(const std::string& message) {
    Token t = peek();
    bool soft = t.type == TokenType::PROP || t.type == TokenType::PURGE || t.type == TokenType::REMEMBER ||
                t.type == TokenType::RETAIN || t.type == TokenType::PRESERVE || t.type == TokenType::ALLOC ||
                t.type == TokenType::TEST || t.type == TokenType::MACRO ||
                // `func take(…)` / `self.take()` - `take` names a stdlib operation too.
                t.type == TokenType::TAKE ||
                // Dedicated keyword tokens that are also established API or module
                // path spellings in unambiguous name positions.
                t.type == TokenType::UNION || t.type == TokenType::ASYNC ||
                // `module std.error` - a module path segment is a plain name.
                t.type == TokenType::ERROR;
    if (t.type == TokenType::IDENTIFIER || soft) {
        advance();
        return t.lexeme;
    }
    nameExpected(message, t);
}

/** Why `(x,)` is not a tuple: a tuple has at least two elements. */
std::string Parser::oneElementTuple(int32_t line, const std::string& what) {
    return "a tuple has at least two elements, so '(x,)' is not a tuple " + what + "; write '(x)' or just 'x' at line " +
           str(line);
}

/**
 * The name of a function that is not a member: it is called by its bare name,
 * so a keyword cannot be one.
 */
std::string Parser::freeFunctionName() {
    Token found = peek();
    const TokenType* keyword = reservedKeywords().get(found.lexeme);
    if (found.type != TokenType::IDENTIFIER && keyword && *keyword == found.type) {
        fail("a function named '" + found.lexeme +
             "' could not be called by that name, which is a keyword; choose another name, or declare it as a member at "
             "line " +
             str(found.line));
    }
    return consumeIdentifierLike("Expected function name");
}

/** Reports that a name was wanted at [found], saying so when it is a keyword. */
[[noreturn]] void Parser::nameExpected(const std::string& message, const Token& found) {
    const TokenType* keyword = reservedKeywords().get(found.lexeme);
    if (keyword && *keyword == found.type) {
        fail(message + ", got the keyword '" + found.lexeme + "', which cannot be used as a name at line " +
             str(found.line));
    }
    fail(message + ", got '" + found.lexeme + "' (" + tokenTypeName(found.type) + ") at line " + str(found.line));
}

}  // namespace azora
