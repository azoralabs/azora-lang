// Parser.kt lines 1340-2034: top-level `inline for`, compile-time lists and
// their folding, top-level inline/deepinline blocks and conditionals.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/kotlin_math.h"
#include "base/text.h"
#include "frontend/ast_util.h"
#include "frontend/lexer.h"

namespace azora {

namespace {

/** Kotlin `Lexer(text).tokenize().dropLast(1)`. */
std::vector<Token> lexWithoutEof(const std::string& text) {
    std::vector<Token> tokens = Lexer(text).tokenize();
    tokens.pop_back();
    return tokens;
}

/**
 * The one arithmetic expression a splice may hold, read by precedence:
 * shifts loosest, then `+`/`-`, then `*`/`/`/`%`.
 */
struct ConstantFolder {
    const std::vector<Token>& tokens;
    size_t at = 0;

    std::optional<int64_t> evaluate() {
        std::optional<int64_t> value = shift();
        if (!value) return std::nullopt;
        return at == tokens.size() ? value : std::nullopt;
    }

    std::optional<int64_t> shift() {
        std::optional<int64_t> left = sum();
        if (!left) return std::nullopt;
        while (true) {
            const Token* op = peek();
            if (op == nullptr || (op->type != TokenType::SHIFT_LEFT && op->type != TokenType::SHIFT_RIGHT)) return left;
            TokenType type = op->type;
            at++;
            std::optional<int64_t> right = sum();
            if (!right) return std::nullopt;
            left = type == TokenType::SHIFT_LEFT ? kshl(*left, ktoInt(*right)) : kshr(*left, ktoInt(*right));
        }
    }

    std::optional<int64_t> sum() {
        std::optional<int64_t> left = product();
        if (!left) return std::nullopt;
        while (true) {
            const Token* op = peek();
            if (op == nullptr || (op->type != TokenType::PLUS && op->type != TokenType::MINUS)) return left;
            TokenType type = op->type;
            at++;
            std::optional<int64_t> right = product();
            if (!right) return std::nullopt;
            left = type == TokenType::PLUS ? kadd(*left, *right) : ksub(*left, *right);
        }
    }

    std::optional<int64_t> product() {
        std::optional<int64_t> left = atom();
        if (!left) return std::nullopt;
        while (true) {
            const Token* op = peek();
            if (op == nullptr ||
                (op->type != TokenType::STAR && op->type != TokenType::SLASH && op->type != TokenType::PERCENT)) {
                return left;
            }
            TokenType type = op->type;
            at++;
            std::optional<int64_t> right = atom();
            if (!right) return std::nullopt;
            if ((type == TokenType::SLASH || type == TokenType::PERCENT) && *right == 0) return std::nullopt;
            if (type == TokenType::STAR) left = kmul(*left, *right);
            else if (type == TokenType::SLASH) left = kdiv(*left, *right);
            else left = krem(*left, *right);
        }
    }

    std::optional<int64_t> atom() {
        const Token* token = peek();
        if (token == nullptr) return std::nullopt;
        switch (token->type) {
            case TokenType::MINUS: {
                at++;
                std::optional<int64_t> inner = atom();
                if (!inner) return std::nullopt;
                return kneg(*inner);
            }
            case TokenType::INT_LITERAL: {
                // A literal wider than a `Long` is not folded through one.
                at++;
                const NumericLiteral* n = std::get_if<NumericLiteral>(&token->literal);
                if (n == nullptr || n->magnitude || n->text) return std::nullopt;
                const int64_t* value = std::get_if<int64_t>(&n->value);
                if (value == nullptr) return std::nullopt;
                return *value;
            }
            case TokenType::L_PAREN: {
                at++;
                std::optional<int64_t> inner = shift();
                if (!inner) return std::nullopt;
                const Token* close = peek();
                if (close == nullptr || close->type != TokenType::R_PAREN) return std::nullopt;
                at++;
                return inner;
            }
            default:
                return std::nullopt;
        }
    }

    const Token* peek() { return at < tokens.size() ? &tokens[at] : nullptr; }
};

}  // namespace

/**
 * `inline for VAR in [<literals>] { <declarations> }` at top level - compile-time
 * code generation. The body is a template: each `$VAR` inside an identifier is
 * substituted with the current value and the result parsed as top-level
 * declarations, queued via pendingTopLevels.
 */
TopLevel Parser::parseTopLevelInlineFor() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::FOR, "Expected 'for'");
    if (check(TokenType::L_PAREN)) {
        fail("parallel 'inline for' uses brackets: write 'inline for [A, B] in [L1, L2]' at line " + str(start.line));
    }
    // `inline for [t, Ty] in [L1, L2]` - parallel lists, zipped by position.
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
        if (lists.size() != loopVars.size()) {
            fail("'inline for' binds " + str(loopVars.size()) + " variable(s) but iterates " + str(lists.size()) +
                 " list(s) at line " + str(start.line));
        }
    } else {
        lists.push_back(parseComptimeForValues());
    }
    const std::vector<std::string> list = first(lists);
    // Optional `with index` - binds a 0-based counter usable as `${list[index]}`.
    std::optional<std::string> indexVar;
    if (matchWithKeyword()) indexVar = consumeIdentifierLike("Expected index variable after 'with'");
    std::vector<Token> bodyTokens = captureInlineForBody();
    consumeNewline();
    size_t count = list.size();
    for (size_t i = 0; i < count; i++) {
        std::vector<Token> rendered = bodyTokens;
        // Fold `${…}` interpolations (loop var + index bound bare inside) first.
        std::vector<std::pair<std::string, std::string>> bindings;
        for (size_t slot = 0; slot < loopVars.size(); slot++) bindings.emplace_back(loopVars[slot], at(lists[slot], (int64_t)i));
        if (indexVar) bindings.emplace_back(*indexVar, str(i));
        rendered = foldBraceInterpolation(rendered, bindings);
        for (size_t slot = 0; slot < loopVars.size(); slot++) {
            rendered = substituteLoopVar(rendered, loopVars[slot], at(lists[slot], (int64_t)i));
        }
        if (indexVar) rendered = substituteLoopVar(rendered, *indexVar, str(i));
        rendered = foldListIndexing(rendered);
        rendered.push_back(Token{TokenType::EOF_, "", start.line, start.column, {}});
        Parser sub(arena, rendered, typeListEnv, nullptr, false, typeListScope);
        Program program = sub.parse();
        pendingTopLevels.insert(pendingTopLevels.end(), program.items.begin(), program.items.end());
    }
    return make(arena, TopLevelInlineBlock{{}, start.line, start.column});
}

/**
 * Folds `${ <expr> }` compile-time interpolations in an `inline for` body: the
 * loop variables (and the `with index` counter) are substituted bare inside the
 * braces, `list[<int>]` indexing is folded, and the braces are removed.
 */
std::vector<Token> Parser::foldBraceInterpolation(const std::vector<Token>& tokens,
                                                  const std::vector<std::pair<std::string, std::string>>& bindings) {
    std::vector<Token> result;
    size_t k = 0;
    while (k < tokens.size()) {
        const Token& t = tokens[k];
        if (t.type == TokenType::IDENTIFIER && t.lexeme == "$" && k + 1 < tokens.size() &&
            tokens[k + 1].type == TokenType::L_BRACE) {
            int depth = 1;
            size_t j = k + 2;
            std::vector<Token> inner;
            while (j < tokens.size() && depth > 0) {
                TokenType type = tokens[j].type;
                if (type == TokenType::L_BRACE) {
                    depth += 1;
                } else if (type == TokenType::R_BRACE) {
                    depth -= 1;
                    if (depth == 0) break;
                }
                if (depth > 0) inner.push_back(tokens[j]);
                j += 1;
            }
            // A splice that names none of this loop's variables belongs to an
            // inner loop and is left for it.
            bool anyName = false;
            bool namesBinding = false;
            for (const Token& token : inner) {
                if (token.type != TokenType::IDENTIFIER || token.lexeme == "$") continue;
                anyName = true;
                for (const auto& binding : bindings) {
                    if (binding.first == token.lexeme) namesBinding = true;
                }
            }
            if (anyName && !namesBinding) {
                size_t end = std::min(j + 1, tokens.size());
                result.insert(result.end(), tokens.begin() + (std::ptrdiff_t)k, tokens.begin() + (std::ptrdiff_t)end);
                k = j + 1;
                continue;
            }
            std::vector<Token> folded = inner;
            for (const auto& [name, value] : bindings) folded = substituteBareIdentifier(folded, name, value);
            folded = foldListIndexing(folded);
            folded = foldConstantArithmetic(folded);
            result.insert(result.end(), folded.begin(), folded.end());
            k = j + 1;
        } else {
            result.push_back(t);
            k += 1;
        }
    }
    return result;
}

/** Replaces every bare identifier `name` with the tokens of `value`. */
std::vector<Token> Parser::substituteBareIdentifier(const std::vector<Token>& tokens, const std::string& name,
                                                    const std::string& value) {
    std::vector<Token> valueTokens = lexWithoutEof(value);
    std::vector<Token> result;
    for (const Token& t : tokens) {
        if (t.type == TokenType::IDENTIFIER && t.lexeme == name) result.insert(result.end(), valueTokens.begin(), valueTokens.end());
        else result.push_back(t);
    }
    return result;
}

/**
 * Folds a splice that is arithmetic over integers into the number it is.
 * Anything that is not integer arithmetic is left exactly as written.
 */
std::vector<Token> Parser::foldConstantArithmetic(const std::vector<Token>& tokens) {
    // A lone token has nothing to fold, and rebuilding it from its value would
    // drop what the value cannot carry.
    if (tokens.size() <= 1) return tokens;
    std::optional<int64_t> value = ConstantFolder{tokens}.evaluate();
    if (!value) return tokens;
    const Token& firstToken = first(tokens);
    return {Token{TokenType::INT_LITERAL, str((long long)*value), firstToken.line, firstToken.column,
                  NumericLiteral{*value, {}, {}}}};
}

/** Folds `list[<int>]` on a compile-time list variable into the element value. */
std::vector<Token> Parser::foldListIndexing(const std::vector<Token>& tokens) {
    std::vector<Token> result;
    size_t i = 0;
    while (i < tokens.size()) {
        const Token& t = tokens[i];
        std::optional<std::vector<std::string>> list;
        if (t.type == TokenType::IDENTIFIER) list = comptimeList(t.lexeme);
        if (list && i + 3 < tokens.size() + 0 && tokens[i + 1].type == TokenType::L_BRACKET &&
            tokens[i + 2].type == TokenType::INT_LITERAL && tokens[i + 3].type == TokenType::R_BRACKET) {
            int32_t idx = ktoInt(std::get<int64_t>(std::get<NumericLiteral>(tokens[i + 2].literal).value));
            if (idx < 0 || idx >= (int32_t)list->size()) {
                fail("compile-time index " + str(idx) + " is out of bounds for list '" + t.lexeme + "'");
            }
            std::vector<Token> valueTokens = lexWithoutEof((*list)[(size_t)idx]);
            result.insert(result.end(), valueTokens.begin(), valueTokens.end());
            i += 4;
        } else {
            result.push_back(t);
            i += 1;
        }
    }
    return result;
}

/**
 * Compile-time values of an `inline for` iterable: an int range (`0..4`), a
 * list literal of string/int/type names, a type-list variable, or those
 * joined with `+`.
 */
std::vector<std::string> Parser::parseComptimeForValues() {
    if (check(TokenType::INT_LITERAL) && (typeIs(current + 1, TokenType::DOT_DOT) ||
                                          typeIs(current + 1, TokenType::DOT_DOT_LESS) ||
                                          typeIs(current + 1, TokenType::GREATER_DOT_DOT))) {
        // `inline for n in 2..4 { … }` - the `{` opens the loop body, so it must
        // not be read as a trailing lambda on the range's last operand.
        const ExprRange& expr = cast<ExprRange>(withoutTrailingLambda([&] { return parseExpr(); }));
        int64_t from = cast<ExprIntLiteral>(expr.from).value;
        int64_t to = cast<ExprIntLiteral>(expr.to).value;
        std::vector<std::string> out;
        for (int64_t value : constantProgression(expr, from, to)) out.push_back(str((long long)value));
        return out;
    }
    return parseComptimeListValue();
}

/** `term (+ term)*` - a compile-time list value (terms are `[…]` literals or list variables). */
std::vector<std::string> Parser::parseComptimeListValue() {
    std::vector<std::string> result = parseComptimeListTerm();
    while (match(TokenType::PLUS)) {
        std::vector<std::string> more = parseComptimeListTerm();
        result.insert(result.end(), more.begin(), more.end());
    }
    return result;
}

/** True for `typealias Name = [A, B, …]` - a compile-time list of types. */
bool Parser::isTypeListAliasAhead() {
    if (!check(TokenType::TYPEALIAS)) return false;
    if (!peekNextIs(TokenType::IDENTIFIER)) return false;
    int32_t i = current + 2;
    if (!typeIs(i, TokenType::EQUAL)) return false;
    i += 1;
    // A list literal, or a name that already denotes one (`Integers + …`).
    if (typeIs(i, TokenType::L_BRACKET)) return true;
    if (typeIs(i, TokenType::IDENTIFIER)) return typeListEnv->contains(tokens[(size_t)i].lexeme);
    return false;
}

/** `typealias Name = [A, B]` - records the list; emits no runtime item. */
TopLevel Parser::parseTypeListAlias() {
    Token start = peek();
    consume(TokenType::TYPEALIAS, "Expected 'typealias'");
    std::string name = consume(TokenType::IDENTIFIER, "Expected type-list name").lexeme;
    consume(TokenType::EQUAL, "Expected '=' in type-list alias");
    typeListEnv->set(name, parseComptimeListValue());
    typeListScope->set(name, typeFunctionNamespacePrefix);
    consumeNewline();
    return make(arena, TopLevelInlineBlock{{}, start.line, start.column});
}

bool Parser::isTypeListBindingAhead() {
    int32_t i = current;
    bool hasInline = typeIs(i, TokenType::INLINE);
    if (hasInline) i += 1;
    if (!(typeIs(i, TokenType::LET) || typeIs(i, TokenType::FIN) || typeIs(i, TokenType::VAR) || typeIs(i, TokenType::VAL))) {
        return false;
    }
    i += 1;
    if (!typeIs(i, TokenType::IDENTIFIER)) return false;
    i += 1;
    if (!typeIs(i, TokenType::COLON)) return false;
    i += 1;
    // `[ Type ]` - a type list (grouping types, e.g. `[Type]`, `[Int]`).
    if (typeIs(i, TokenType::L_BRACKET)) {
        i += 1;
        const Token* elem = tokenOrNull(i);
        if (elem == nullptr || elem->type != TokenType::IDENTIFIER) return false;
        i += 1;
        if (!typeIs(i, TokenType::R_BRACKET)) return false;
        return elem->lexeme == "Type" || hasInline;
    }
    // `Array<Elem>` - an inline compile-time value list.
    if (hasInline && typeIs(i, TokenType::IDENTIFIER)) {
        int32_t j = i;
        std::string last = tokens[(size_t)j].lexeme;
        while (typeIs(j + 1, TokenType::DOUBLE_COLON) && typeIs(j + 2, TokenType::IDENTIFIER)) {
            j += 2;
            last = tokens[(size_t)j].lexeme;
        }
        if (last == "Array") return true;
    }
    return false;
}

/**
 * `[inline] let X: [Elem] = <list>` / `inline fin X: Array<Elem> = @arr[…]` -
 * records a compile-time list; emits no runtime item.
 */
TopLevel Parser::parseTypeListBinding() {
    Token start = peek();
    match(TokenType::INLINE);  // optional `inline`
    advance();                 // let/fin/var
    std::string name = consume(TokenType::IDENTIFIER, "Expected list name").lexeme;
    consume(TokenType::COLON, "Expected ':'");
    if (check(TokenType::L_BRACKET)) {
        consume(TokenType::L_BRACKET, "Expected '['");
        consume(TokenType::IDENTIFIER, "Expected element type");
        consume(TokenType::R_BRACKET, "Expected ']'");
    } else {
        // `Array<Elem>` - a value list.
        TypeRef type = parseTypeName();
        TypeRefNamed* named = as<TypeRefNamed>(type);
        if (named == nullptr || named->name != "Array") {
            fail("Expected 'Array<…>' or '[Elem]' compile-time list type at line " + str(start.line));
        }
    }
    consume(TokenType::EQUAL, "Expected '=' in compile-time list binding");
    typeListEnv->set(name, parseComptimeListValue());
    typeListScope->set(name, typeFunctionNamespacePrefix);
    consumeNewline();
    return make(arena, TopLevelInlineBlock{{}, start.line, start.column});
}

std::vector<std::string> Parser::parseComptimeListTerm() {
    // `@arr[...]`: consume the macro prefix and parse the bracket body as
    // compile-time values.
    bool isMacro = isMacroInvokeAhead();
    if (isMacro) {
        advance();
        parseQualifiedMacroName();
    }
    if (match(TokenType::L_BRACKET)) {
        std::vector<std::string> values;
        if (!check(TokenType::R_BRACKET)) {
            do {
                if (check(TokenType::STRING_LITERAL) || check(TokenType::INT_LITERAL)) {
                    if (check(TokenType::STRING_LITERAL)) {
                        values.push_back(literalString(advance()));
                    } else {
                        Token number = advance();
                        values.push_back(numericValueText(std::get<NumericLiteral>(number.literal)));
                    }
                } else {
                    // A type in the list may be reached through its scope; the
                    // qualifier is part of how the type is named here and is kept.
                    std::string path = consumeIdentifierLike("Expected a literal or type name in compile-time list");
                    while (match(TokenType::DOUBLE_COLON)) {
                        path += "::";
                        path += consumeIdentifierLike("Expected a name after '::' in a compile-time list");
                    }
                    values.push_back(path);
                }
            } while (match(TokenType::COMMA));
        }
        consume(TokenType::R_BRACKET, "Expected ']' to close compile-time list");
        return values;
    }
    if (isMacro) fail("Expected '[...]' after macro prefix in compile-time list at line " + str(peek().line));
    std::string name = consumeIdentifierLike("Expected a compile-time list literal or variable");
    std::optional<std::vector<std::string>> list = comptimeList(name);
    if (!list) fail("Unknown compile-time list '" + name + "' at line " + str(peek().line));
    return *list;
}

/**
 * The tokens of a compile-time `inline for` body: `{ … }`, or `then` and one
 * statement, which ends at the end of its line.
 */
std::vector<Token> Parser::captureInlineForBody() {
    if (!match(TokenType::THEN)) {
        consume(TokenType::L_BRACE, "Expected '{' or 'then' to open 'inline for' body");
        return captureBraceBody();
    }
    skipNewlines();
    std::vector<Token> body;
    int depth = 0;
    while (!isAtEnd()) {
        TokenType type = peek().type;
        if (depth == 0 && type == TokenType::NEWLINE) break;
        if (type == TokenType::L_BRACE || type == TokenType::L_PAREN || type == TokenType::L_BRACKET) {
            depth++;
        } else if (type == TokenType::R_BRACE || type == TokenType::R_PAREN || type == TokenType::R_BRACKET) {
            // A closer with nothing open ends the enclosing construct.
            if (depth == 0) break;
            depth--;
        }
        body.push_back(advance());
    }
    if (body.empty()) fail("Expected a statement after 'then' in 'inline for' at line " + str(peek().line));
    return body;
}

std::vector<Token> Parser::captureBraceBody() {
    std::vector<Token> body;
    int depth = 1;
    while (!isAtEnd()) {
        TokenType type = peek().type;
        if (type == TokenType::L_BRACE) {
            depth++;
            body.push_back(advance());
        } else if (type == TokenType::R_BRACE) {
            depth--;
            if (depth == 0) {
                advance();
                return body;
            }
            body.push_back(advance());
        } else {
            body.push_back(advance());
        }
    }
    fail("Unterminated 'inline for' body at line " + str(peek().line));
}

/**
 * Substitutes an `inline for` loop variable in a body token stream, re-lexing
 * touched tokens. A bare identifier equal to [varName] and a `$VAR` inside an
 * identifier are both replaced.
 */
std::vector<Token> Parser::substituteLoopVar(const std::vector<Token>& tokens, const std::string& varName,
                                             const std::string& value) {
    std::string placeholder = "$" + varName;
    std::vector<Token> valueTokens = lexWithoutEof(value);
    // A bare use is replaced by the value itself when the value is a name or a
    // number; a scope-qualified type is several tokens but still one name.
    bool isQualifiedPath = valueTokens.size() >= 3 && valueTokens.size() % 2 == 1;
    if (isQualifiedPath) {
        for (size_t i = 0; i < valueTokens.size(); i++) {
            TokenType want = i % 2 == 0 ? TokenType::IDENTIFIER : TokenType::DOUBLE_COLON;
            if (valueTokens[i].type != want) {
                isQualifiedPath = false;
                break;
            }
        }
    }
    bool bareOk = isQualifiedPath || (valueTokens.size() == 1 && (valueTokens[0].type == TokenType::IDENTIFIER ||
                                                                 valueTokens[0].type == TokenType::INT_LITERAL ||
                                                                 valueTokens[0].type == TokenType::DOUBLE_LITERAL));
    std::vector<Token> result;
    for (const Token& t : tokens) {
        if (bareOk && t.type == TokenType::IDENTIFIER && t.lexeme == varName) {
            result.insert(result.end(), valueTokens.begin(), valueTokens.end());
        } else if (t.type == TokenType::IDENTIFIER && t.lexeme == varName) {
            // A bare `op` over strings reads it as the value it is.
            result.push_back(Token{TokenType::STRING_LITERAL, "\"" + value + "\"", t.line, t.column, value});
        } else if (t.type == TokenType::IDENTIFIER && contains(t.lexeme, placeholder)) {
            // Spliced *into* a name, only the declaration's own name can appear.
            std::vector<Token> spliced = lexWithoutEof(replaceAll(t.lexeme, placeholder, substringAfterLast(value, "::")));
            result.insert(result.end(), spliced.begin(), spliced.end());
        } else {
            result.push_back(t);
        }
    }
    return result;
}

TopLevelInlineBlock* Parser::parseTopLevelInlineBlock() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::L_BRACE, "Expected '{'");
    skipNewlines();
    std::vector<TopLevel> body = parseTopLevelBlock(true);
    consume(TokenType::R_BRACE, "Expected '}'");
    consumeNewline();
    return make(arena, TopLevelInlineBlock{body, start.line, start.column});
}

TopLevel Parser::parseTopLevelDeepInline() {
    std::optional<TokenType> next;
    if (std::optional<Token> n = peekNext()) next = n->type;
    if (next == TokenType::FUNC) {
        advance();
        return make(arena, TopLevelFunc{parseFuncDecl({.isInline = true})});
    }
    if (next == TokenType::L_BRACE) return parseTopLevelDeepInlineBlock();
    if (next == TokenType::IF) return parseTopLevelDeepInlineIf();
    fail("Expected '{' or 'if' after 'deepinline' at line " + str(peek().line));
}

TopLevelDeepInlineBlock* Parser::parseTopLevelDeepInlineBlock() {
    Token start = peek();
    consume(TokenType::DEEPINLINE, "Expected 'deepinline'");
    consume(TokenType::L_BRACE, "Expected '{'");
    skipNewlines();
    std::vector<TopLevel> body = parseTopLevelBlock(true);
    consume(TokenType::R_BRACE, "Expected '}'");
    consumeNewline();
    return make(arena, TopLevelDeepInlineBlock{body, start.line, start.column});
}

TopLevelDeepInlineIf* Parser::parseTopLevelDeepInlineIf() {
    Token start = peek();
    consume(TokenType::DEEPINLINE, "Expected 'deepinline'");
    consume(TokenType::IF, "Expected 'if'");
    Expr condition = withoutTrailingLambda([&] { return parseExpr(); });
    consume(TokenType::L_BRACE, "Expected '{'");
    skipNewlines();
    std::vector<TopLevel> thenBranch = parseTopLevelBlock(true);
    consume(TokenType::R_BRACE, "Expected '}'");
    std::optional<std::vector<TopLevel>> elseBranch;
    if (match(TokenType::ELSE)) {
        consume(TokenType::L_BRACE, "Expected '{'");
        skipNewlines();
        elseBranch = parseTopLevelBlock(true);
        consume(TokenType::R_BRACE, "Expected '}'");
    }
    consumeNewline();
    return make(arena, TopLevelDeepInlineIf{condition, thenBranch, elseBranch, start.line, start.column});
}

TopLevelInlineIf* Parser::parseTopLevelInlineIf() {
    Token start = peek();
    consume(TokenType::INLINE, "Expected 'inline'");
    consume(TokenType::IF, "Expected 'if'");
    Expr condition = withoutTrailingLambda([&] { return parseExpr(); });
    consume(TokenType::L_BRACE, "Expected '{'");
    skipNewlines();
    std::vector<TopLevel> thenBranch = parseTopLevelBlock();
    consume(TokenType::R_BRACE, "Expected '}'");
    std::optional<std::vector<TopLevel>> elseBranch;
    if (match(TokenType::ELSE)) {
        consume(TokenType::L_BRACE, "Expected '{'");
        skipNewlines();
        elseBranch = parseTopLevelBlock();
        consume(TokenType::R_BRACE, "Expected '}'");
    }
    consumeNewline();
    return make(arena, TopLevelInlineIf{condition, thenBranch, elseBranch, start.line, start.column});
}

/**
 * Parses top-level items inside an inline/deepinline block. Bare
 * `var`/`fin`/`let`/`if`/assignment are converted to their TopLevel.Inline*
 * equivalents (implicitly compile-time).
 */
std::vector<TopLevel> Parser::parseTopLevelBlock(bool deepInline /*= false*/) {
    std::vector<TopLevel> items;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        items.push_back(parseTopLevelBlockItem(deepInline));
        skipNewlines();
    }
    return items;
}

TopLevel Parser::parseTopLevelBlockItem(bool deepInline /*= false*/) {
    // Leading `@Deco(...)` annotations are permitted on declarations nested in
    // a compile-time block.
    std::vector<Annotation> annotations = parseAnnotations();
    Token start = peek();
    struct Binding {
        std::string name;
        TypeRef type;
        Expr init;
    };
    auto parseBinding = [&](bool noteConstant) -> Binding {
        std::string name = consume(TokenType::IDENTIFIER, "Expected name").lexeme;
        TypeRef type;
        if (match(TokenType::COLON)) type = parseTypeName();
        consume(TokenType::EQUAL, "Expected '='");
        Expr init = parseInitializer(type);
        consumeNewline();
        if (noteConstant) noteBridgeTargetConstant(name, init);
        return Binding{name, type, init};
    };
    if (check(TokenType::FUNC)) return make(arena, TopLevelFunc{parseFuncDecl({.isInline = deepInline, .annotations = annotations})});
    if (check(TokenType::TEST)) return parseTestDecl(annotations);
    if (check(TokenType::ENUM)) return parseEnumDecl(annotations);
    if (check(TokenType::PACK)) return parsePack(annotations);
    if (check(TokenType::ANNOT)) return parseDeco(annotations);
    if (check(TokenType::ERROR)) return parseFailDecl(annotations);
    if (check(TokenType::TYPEALIAS)) return parseTypeAlias(annotations);
    if (check(TokenType::VARIANT)) return parseSlot(annotations);
    if (check(TokenType::SPEC)) return withAnnotations(parseSpec(), annotations);
    if (check(TokenType::INLINE)) return parseTopLevelInline(annotations);
    if (check(TokenType::DEEPINLINE)) return parseTopLevelDeepInline();
    if (check(TokenType::ASSERT)) {
        // Bare assert inside inline block → InlineAssert at top level
        StmtAssert* assertStmt = parseAssertStmt();
        return make(arena, TopLevelInlineAssert{assertStmt->condition, assertStmt->message, assertStmt->line,
                                                assertStmt->column});
    }
    if (check(TokenType::TRACE)) {
        // Bare trace inside inline block → InlineTrace at top level
        StmtTrace* traceStmt = parseTraceStmt();
        return make(arena, TopLevelInlineTrace{traceStmt->message, traceStmt->line, traceStmt->column, traceStmt->level});
    }
    if (check(TokenType::NOINLINE)) {
        advance();  // consume 'noinline'
        if (check(TokenType::FUNC)) return make(arena, TopLevelFunc{parseFuncDecl({.isInline = false})});
        if (check(TokenType::FIN)) {
            advance();
            Binding b = parseBinding(true);
            return make(arena, TopLevelFinDecl{.name = b.name, .type = b.type, .initializer = b.init, .line = start.line,
                                               .column = start.column});
        }
        if (check(TokenType::VAR) || check(TokenType::VAL)) {
            bool valueMutable = advance().type == TokenType::VAR;
            Binding b = parseBinding(false);
            return make(arena, TopLevelVarDecl{.name = b.name, .type = b.type, .initializer = b.init,
                                               .line = start.line, .column = start.column, .valueMutable = valueMutable});
        }
        if (check(TokenType::LET)) {
            advance();
            Binding b = parseBinding(false);
            return make(arena, TopLevelLetDecl{.name = b.name, .type = b.type, .initializer = b.init, .line = start.line,
                                               .column = start.column});
        }
        fail("Expected 'func', 'fin', 'var', or 'let' after 'noinline' at line " + str(peek().line));
    }
    // Bare declarations: inline if deepInline, runtime otherwise
    if (check(TokenType::VAR) || check(TokenType::VAL)) {
        bool valueMutable = advance().type == TokenType::VAR;
        Binding b = parseBinding(false);
        if (deepInline) return make(arena, TopLevelInlineVar{b.name, b.init, start.line, start.column, valueMutable});
        return make(arena, TopLevelVarDecl{.name = b.name, .type = b.type, .initializer = b.init, .line = start.line,
                                           .column = start.column, .valueMutable = valueMutable});
    }
    if (check(TokenType::FIN)) {
        advance();
        Binding b = parseBinding(true);
        if (deepInline) return make(arena, TopLevelInlineFin{b.name, b.init, start.line, start.column, inlineFinType(b.type)});
        return make(arena, TopLevelFinDecl{.name = b.name, .type = b.type, .initializer = b.init, .line = start.line,
                                           .column = start.column});
    }
    if (check(TokenType::LET)) {
        advance();
        Binding b = parseBinding(false);
        if (deepInline) return make(arena, TopLevelInlineLet{b.name, b.init, start.line, start.column});
        return make(arena, TopLevelLetDecl{.name = b.name, .type = b.type, .initializer = b.init, .line = start.line,
                                           .column = start.column});
    }
    if (check(TokenType::IF)) {
        consume(TokenType::IF, "Expected 'if'");
        Expr condition = withoutTrailingLambda([&] { return parseExpr(); });
        consume(TokenType::L_BRACE, "Expected '{'");
        skipNewlines();
        std::vector<TopLevel> thenBranch = parseTopLevelBlock(deepInline);
        consume(TokenType::R_BRACE, "Expected '}'");
        std::optional<std::vector<TopLevel>> elseBranch;
        if (match(TokenType::ELSE)) {
            consume(TokenType::L_BRACE, "Expected '{'");
            skipNewlines();
            elseBranch = parseTopLevelBlock(deepInline);
            consume(TokenType::R_BRACE, "Expected '}'");
        }
        consumeNewline();
        return make(arena, TopLevelInlineIf{condition, thenBranch, elseBranch, start.line, start.column});
    }
    if (check(TokenType::IDENTIFIER) && peekNextIs(TokenType::EQUAL)) {
        std::string name = consume(TokenType::IDENTIFIER, "Expected name").lexeme;
        consume(TokenType::EQUAL, "Expected '='");
        Expr value = parseExpr();
        consumeNewline();
        return make(arena, TopLevelInlineAssignment{name, value, start.line, start.column});
    }
    fail("Unexpected '" + peek().lexeme + "' inside inline block at line " + str(peek().line));
}

}  // namespace azora
