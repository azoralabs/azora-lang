// Parser.kt lines 4876-5500: fragment macros (splicing), `macro`
// declarations, macro names, type-macro arms and expression arms.
#include "frontend/parser.h"

#include "base/chars.h"
#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"
#include "frontend/lexer.h"

#include <algorithm>

namespace azora {

namespace {

/** Kotlin `text.trim('"')`: drops every leading and trailing quote. */
std::string trimQuotes(const std::string& text) {
    size_t begin = 0;
    size_t end = text.size();
    while (begin < end && text[begin] == '"') begin++;
    while (end > begin && text[end - 1] == '"') end--;
    return text.substr(begin, end - begin);
}

std::string joinLexemes(const std::vector<Token>& tokens) {
    std::vector<std::string> parts;
    for (const Token& t : tokens) parts.push_back(t.lexeme);
    return join(parts, " ");
}

/** Lexes a fragment, dropping EOF and newlines, stamped with [at]'s position. */
std::vector<Token> lexFragment(const std::string& text, const Token& at) {
    std::vector<Token> out;
    for (Token token : Lexer(text).tokenize()) {
        if (token.type == TokenType::EOF_ || token.type == TokenType::NEWLINE) continue;
        token.line = at.line;
        token.column = at.column;
        out.push_back(token);
    }
    return out;
}

}  // namespace

/**
 * Replaces `@name` with its fragment's tokens, when one is declared. Done on
 * the token stream because a fragment need not be an expression.
 */
bool Parser::spliceFragmentMacro() {
    if (!check(TokenType::AT)) return false;
    std::optional<Token> nameToken = tokenCopy(peekNext());
    if (!nameToken) return false;
    if (nameToken->type != TokenType::IDENTIFIER) return false;
    FragmentMacroTables& tables = fragmentTables();
    const std::string* text = tables.fragmentMacros.get(nameToken->lexeme);
    if (text == nullptr) return false;
    // A fragment with a hole reads its argument off the token stream.
    int32_t afterArgument = current + 2;
    std::string body = *text;
    if (const std::string* hole = tables.fragmentMacroParams.get(nameToken->lexeme)) {
        std::vector<Token> argument = fragmentArgumentTokens(afterArgument);
        if (argument.empty()) fail("'@" + nameToken->lexeme + "' takes an argument at line " + str(nameToken->line));
        afterArgument += (int32_t)argument.size();
        body = replaceAll(*text, *hole, joinLexemes(argument));
    }
    std::string spliced = expandSelfSplices(body, nameToken->line);
    // The fragment is lexed from text, so its tokens start over at line 1; they
    // are stamped with the position of the `@name` they stand for.
    std::vector<Token> expanded = lexFragment(spliced, *nameToken);
    std::vector<Token> result(tokens.begin(), tokens.begin() + std::min<size_t>((size_t)current, tokens.size()));
    result.insert(result.end(), expanded.begin(), expanded.end());
    if ((size_t)afterArgument < tokens.size()) result.insert(result.end(), tokens.begin() + afterArgument, tokens.end());
    tokens = std::move(result);
    return true;
}

/**
 * Expands `a @name b` where `@name` is an infix source fragment; the result
 * replaces the whole `a @name b`.
 */
bool Parser::spliceInfixFragmentMacro(int32_t leftStart, const Token& nameToken) {
    const InfixFragment* fragment = fragmentTables().infixFragmentMacros.get(nameToken.lexeme);
    if (fragment == nullptr) return false;
    std::vector<Token> leftTokens;
    for (int32_t i = leftStart; i < current; i++) {
        if (tokens[(size_t)i].type != TokenType::NEWLINE) leftTokens.push_back(tokens[(size_t)i]);
    }
    if (leftTokens.empty()) return false;
    int32_t rightFrom = current + 2;
    std::vector<Token> rightTokens = fragmentArgumentTokens(rightFrom);
    if (rightTokens.empty()) {
        fail("'@" + nameToken.lexeme + "' takes an operand on both sides at line " + str(nameToken.line));
    }
    // Longest hole first, so `$key` is not eaten by a `$k` declared beside it.
    std::vector<std::pair<std::string, std::string>> holes{
        {fragment->leftHole, joinLexemes(leftTokens)},
        {fragment->rightHole, joinLexemes(rightTokens)},
    };
    std::stable_sort(holes.begin(), holes.end(),
                     [](const auto& a, const auto& b) { return u16len(a.first) > u16len(b.first); });
    std::string body = fragment->text;
    for (const auto& [hole, value] : holes) body = replaceAll(body, hole, value);
    body = expandSelfSplices(body, nameToken.line);
    std::vector<Token> expanded = lexFragment(body, nameToken);
    std::vector<Token> result(tokens.begin(), tokens.begin() + leftStart);
    result.insert(result.end(), expanded.begin(), expanded.end());
    size_t resume = (size_t)rightFrom + rightTokens.size();
    if (resume < tokens.size()) result.insert(result.end(), tokens.begin() + (std::ptrdiff_t)resume, tokens.end());
    tokens = std::move(result);
    return true;
}

/**
 * Replaces `${Self.typename}` in a fragment with the type it expands inside;
 * `.lowercase` and `.uppercase` case the name. The result is a string literal.
 */
std::string Parser::expandSelfSplices(const std::string& body, int32_t line) {
    if (!contains(body, "${Self.")) return body;
    std::string out = body;
    while (true) {
        size_t start = out.find("${Self.");
        if (start == std::string::npos) break;
        size_t end = out.find('}', start);
        if (end == std::string::npos) fail("unterminated '${Self.…}' in a macro fragment at line " + str(line));
        if (!currentImplTypeName) {
            fail("'${Self.…}' names the type it expands inside, and there is none at line " + str(line));
        }
        std::string name = *currentImplTypeName;
        size_t from = start + std::string("${Self.").size();
        std::vector<std::string> path = split(out.substr(from, end - from), ".");
        if (lowercase(path[0]) != "typename") {
            fail("'Self." + path[0] + "' is not a name a macro fragment can splice at line " + str(line));
        }
        std::string cased = name;
        for (size_t i = 1; i < path.size(); i++) {
            if (path[i] == "lowercase") cased = lowercase(cased);
            else if (path[i] == "uppercase") cased = uppercase(cased);
            else fail("'" + path[i] + "' is not a case a macro fragment can apply at line " + str(line));
        }
        out = out.substr(0, start) + "\"" + cased + "\"" + out.substr(end + 1);
    }
    return out;
}

/**
 * The tokens of a fragment macro's single argument, starting at [from]: runs
 * to the block or the line end that follows it, tracking nesting.
 */
std::vector<Token> Parser::fragmentArgumentTokens(int32_t from) {
    std::vector<Token> argument;
    int depth = 0;
    int32_t i = from;
    while (i < (int32_t)tokens.size()) {
        const Token& token = tokens[(size_t)i];
        if (token.type == TokenType::L_PAREN || token.type == TokenType::L_BRACKET) depth++;
        else if (token.type == TokenType::R_PAREN || token.type == TokenType::R_BRACKET) depth--;
        if (depth <= 0 &&
            (token.type == TokenType::L_BRACE || token.type == TokenType::NEWLINE || token.type == TokenType::EOF_)) {
            break;
        }
        argument.push_back(token);
        i++;
    }
    return argument;
}

TopLevelMeta* Parser::parseMeta() {
    Token start = peek();
    consume(TokenType::MACRO, "Expected 'macro'");
    FragmentMacroTables& tables = fragmentTables();

    // `macro $a @to $b => TEMPLATE` - an infix macro.
    if (check(TokenType::IDENTIFIER) && startsWith(peek().lexeme, "$")) {
        std::string left = advance().lexeme;
        consume(TokenType::AT, "Expected '@' before the infix macro's name");
        std::string op = parseMacroName();
        // `[...$ITEMS]` - the right operand is a list, however long.
        bool rightIsList = match(TokenType::L_BRACKET);
        if (rightIsList) consume(TokenType::ELLIPSIS, "A list operand captures the whole list: write '[...$NAME]'");
        std::string right = consume(TokenType::IDENTIFIER, "Expected the right hole after an infix macro's name").lexeme;
        if (rightIsList) consume(TokenType::R_BRACKET, "Expected ']' after the list operand of '@" + op + "'");
        requireTypeMacroHole(left);
        requireTypeMacroHole(right);
        // With a `=>` it rewrites; without one it only registers the name.
        if (match(TokenType::FAT_ARROW)) {
            // `$a @op $b => inline "…"` - a source fragment.
            if (match(TokenType::INLINE)) {
                if (!check(TokenType::STRING_LITERAL) && !check(TokenType::INTERPOLATED_STRING)) {
                    fail("Expected a source fragment after 'inline', got '" + peek().lexeme + "' at line " +
                         str(peek().line));
                }
                Token fragment = advance();
                std::string text;
                if (fragment.type == TokenType::STRING_LITERAL) {
                    const std::string* s = std::get_if<std::string>(&fragment.literal);
                    text = s ? *s : trimQuotes(fragment.lexeme);
                } else {
                    text = trimQuotes(fragment.lexeme);
                }
                tables.infixFragmentMacros.set(op, InfixFragment{text, left, right});
                consumeNewline();
                return make(arena, TopLevelMeta{"__fragment__" + op, {}, start.line, start.column});
            }
            // Type holes on both sides mean the arm joins two *types*.
            if (isTypeHole(left) && isTypeHole(right)) {
                TypeRef templateType = parseTypeName();
                TypeTypeArm arm;
                arm.kind = TypeFormKind::INFIX;
                arm.holes = {left, right};
                arm.template_ = templateType;
                arm.name = op;
                arm.listTail = rightIsList;
                pendingTypeMacroRules.push_back(arm);
            } else {
                Expr templateExpr = parseExpr();
                pendingInfixMacros.push_back(InfixMacroRule{op, left, right, templateExpr});
            }
        }
        consumeNewline();
        // `__infix__op` is what SymbolCollector recognises to wire `op` as a
        // universal infix call.
        return make(arena, TopLevelMeta{"__infix__" + op, {}, start.line, start.column});
    }

    // `macro @name { arms }` - a prefix macro, invoked `@name[…]`.
    consume(TokenType::AT, "Expected '@name' or '$hole @name $hole' after 'macro'");
    std::string name = parseMacroName();
    // `macro @name $hole => inline "…"` - a fragment that names what it was handed.
    std::optional<std::string> fragmentHole;
    if (check(TokenType::IDENTIFIER) && startsWith(peek().lexeme, "$") && peekNextIs(TokenType::FAT_ARROW)) {
        fragmentHole = advance().lexeme;
    }
    if (match(TokenType::FAT_ARROW)) {
        if (!match(TokenType::INLINE)) {
            fail("A macro without arms expands to a source fragment: write 'macro @" + name +
                 " => inline \"…\"' at line " + str(start.line));
        }
        // A fragment naming its hole lexes as an interpolated string; the
        // *source* is what is kept.
        if (!check(TokenType::STRING_LITERAL) && !check(TokenType::INTERPOLATED_STRING)) {
            fail("Expected a source fragment after 'inline', got '" + peek().lexeme + "' at line " + str(peek().line));
        }
        Token fragment = advance();
        std::string text;
        if (fragment.type == TokenType::STRING_LITERAL) {
            const std::string* s = std::get_if<std::string>(&fragment.literal);
            text = s ? *s : trimQuotes(fragment.lexeme);
        } else {
            text = trimQuotes(fragment.lexeme);
        }
        tables.fragmentMacros.set(name, text);
        if (fragmentHole) tables.fragmentMacroParams.set(name, *fragmentHole);
        consumeNewline();
        return make(arena, TopLevelMeta{"__fragment__" + name, {}, start.line, start.column});
    }
    // `macro @name <pattern> => <template>` - one arm, written where it is.
    if (!check(TokenType::L_BRACE)) {
        if (std::optional<TypeTypeArm> typeArm = parseTypeMacroArm(name)) {
            pendingTypeMacroRules.push_back(*typeArm);
            return make(arena, TopLevelMeta{name, {}, start.line, start.column});
        }
        MacroArm arm = parseMacroArm();
        consumeNewline();
        return make(arena, TopLevelMeta{name, {arm}, start.line, start.column});
    }
    consume(TokenType::L_BRACE, "Expected '{' after 'macro @" + name + "'");
    skipNewlines();
    std::optional<std::string> parameter;
    if (check(TokenType::IDENTIFIER) && !startsWith(peek().lexeme, "$") && peekNextIs(TokenType::ARROW)) {
        std::string value = advance().lexeme;
        advance();  // '->'
        consumeNewline();
        skipNewlines();
        parameter = value;
    }
    // Arms are grouped by the sigil they answer to - "" for the bare form.
    OrderedMap<std::string, std::vector<MacroArm>> armsBySigil;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        // A hole's case says what it stands for: `$T` is a type, `$t` a value.
        if (std::optional<TypeTypeArm> typeArm = parseTypeMacroArm(name)) {
            pendingTypeMacroRules.push_back(*typeArm);
            continue;
        }
        std::string sigil = macroArmSigil();
        MacroArm arm = parseMacroArm();
        armsBySigil.getOrPut(sigil, {}).push_back(arm);
    }
    consume(TokenType::R_BRACE, "Expected '}' after macro arms");
    consumeNewline();
    // `@vec` and `@vec!` are still different macros - the sigil is part of the name.
    std::vector<MacroArm> bare;
    if (std::vector<MacroArm>* found = armsBySigil.get("")) bare = *found;
    armsBySigil.remove("");
    std::vector<TopLevelMeta*> sigilled;
    for (const auto& [sigil, sigilArms] : armsBySigil.entries) {
        sigilled.push_back(make(arena, TopLevelMeta{name + sigil, sigilArms, start.line, start.column, parameter}));
    }
    if (bare.empty() && !sigilled.empty()) {
        for (size_t i = 1; i < sigilled.size(); i++) pendingTopLevels.push_back(sigilled[i]);
        return sigilled[0];
    }
    for (TopLevelMeta* meta : sigilled) pendingTopLevels.push_back(meta);
    return make(arena, TopLevelMeta{name, bare, start.line, start.column, parameter});
}

/** The sigil an arm answers to, consumed if present (`![…] => …` is `@vec!`'s arm). */
std::string Parser::macroArmSigil() {
    std::string sigil;
    switch (peek().type) {
        case TokenType::BANG: sigil = "!"; break;
        case TokenType::QMARK: sigil = "?"; break;
        case TokenType::AMP: sigil = "&"; break;
        case TokenType::STAR: sigil = "*"; break;
        case TokenType::CARET: sigil = "^"; break;
        default: return "";
    }
    bool opensArm = peekNextIs(TokenType::L_PAREN) || peekNextIs(TokenType::L_BRACKET) || peekNextIs(TokenType::L_BRACE);
    if (!opensArm) return "";
    advance();
    return sigil;
}

/** Rejects `use`, which the language no longer has, where it used to be read. */
void Parser::rejectRemovedUse(const std::string& remedy) {
    if (!check(TokenType::IDENTIFIER) || peek().lexeme != "use") return;
    fail("'use' is not a keyword at line " + str(peek().line) + "; " + remedy);
}

/** A macro's name: an identifier and an optional sigil (`! ? & * ^`). */
std::string Parser::parseMacroName() {
    // Any word may name a macro, including one that is a keyword elsewhere.
    std::string base;
    if (check(TokenType::IDENTIFIER)) {
        base = advance().lexeme;
    } else {
        std::string lexeme = peek().lexeme;
        std::u16string units16 = decodeWtf8(lexeme);
        if (!units16.empty() && isLetter(units16[0])) base = advance().lexeme;
        else base = consumeIdentifierLike("Expected a macro name after '@'");
    }
    std::string sigil;
    if (match(TokenType::BANG)) sigil = "!";
    else if (match(TokenType::QMARK)) sigil = "?";
    else if (match(TokenType::AMP)) sigil = "&";
    else if (match(TokenType::STAR)) sigil = "*";
    else if (match(TokenType::CARET)) sigil = "^";
    return base + sigil;
}

/** A macro invocation name after `@`: `name` or scope-qualified `name`. */
std::string Parser::parseQualifiedMacroName() {
    std::vector<std::string> parts{parseMacroName()};
    while (match(TokenType::DOUBLE_COLON)) {
        // A name sigil belongs only to the final segment.
        const std::string& lastPart = parts.back();
        if (!lastPart.empty() && std::string("!?&*^").find(lastPart.back()) != std::string::npos) {
            fail("A macro-name sigil is only valid on the final qualified segment at line " + str(peek().line));
        }
        parts.push_back(parseMacroName());
    }
    return join(parts, "__");
}

void Parser::requireTypeMacroHole(const std::string& name) {
    if (!startsWith(name, "$") || u16len(name) == 1) {
        fail("Type-macro holes must start with '$', got '" + name + "' at line " + str(peek().line));
    }
}

std::pair<TypeFormKind, std::vector<std::string>> Parser::parseTypeMacroPattern() {
    if (check(TokenType::L_BRACKET)) {
        advance();  // '['
        TypeRef firstType = parseTypeName();
        if (match(TokenType::SEMICOLON)) {
            // `[T; *]` or `[T;]` - an unsized array pattern; `[T; N]` binds the size hole `N`.
            std::string n;
            if (check(TokenType::R_BRACKET)) n = "*";
            else if (match(TokenType::STAR)) n = "*";
            else n = holeName(parseTypeName());
            consume(TokenType::R_BRACKET, "Expected ']' after '[T; N]'");
            return {TypeFormKind::ARRAY_SIZED, {holeName(firstType), n}};
        }
        if (match(TokenType::COLON)) {
            TypeRef v = parseTypeName();
            consume(TokenType::R_BRACKET, "Expected ']' after '[K: V]'");
            return {TypeFormKind::MAP, {holeName(firstType), holeName(v)}};
        }
        consume(TokenType::R_BRACKET, "Expected ']' after '[T]'");
        return {TypeFormKind::ARRAY, {holeName(firstType)}};
    }
    if (check(TokenType::L_BRACE)) {
        advance();  // '{'
        TypeRef t = parseTypeName();
        consume(TokenType::R_BRACE, "Expected '}' after '{T}'");
        return {TypeFormKind::SET, {holeName(t)}};
    }
    if (check(TokenType::L_PAREN)) {
        advance();  // '('
        std::vector<std::string> holes;
        if (match(TokenType::ELLIPSIS)) {
            holes.push_back(consume(TokenType::IDENTIFIER, "Expected name after '...' in '(...T)'").lexeme);
        } else if (!check(TokenType::R_PAREN)) {
            do {
                skipNewlines();
                if (check(TokenType::R_PAREN)) break;
                holes.push_back(holeName(parseTypeName()));
                skipNewlines();
            } while (match(TokenType::COMMA));
        }
        consume(TokenType::R_PAREN, "Expected ')' after tuple pattern");
        return {TypeFormKind::TUPLE, holes};
    }
    fail("Expected a type pattern ('[T]', '[T; N]', '{T}', '[K: V]', or '(...T)') at line " + str(peek().line));
}

/** The type-variable name a pattern hole binds (e.g. `T` from `TypeRef.Named("T")`). */
std::string Parser::holeName(const TypeRef& ref) {
    if (TypeRefNamed* named = as<TypeRefNamed>(ref)) return named->name;
    return displayName(ref);
}

/**
 * One arm of a type macro, or none when this arm is an ordinary one. The case
 * of the hole is the whole distinction: `$T` stands for a type, `$t` a value.
 */
std::optional<TypeTypeArm> Parser::parseTypeMacroArm(const std::string& macroName) {
    // A bare hole heads a type arm when what follows is the arrow, or a word
    // the arm's own grammar continues with: `$Q with $T => …`.
    bool bare = check(TokenType::IDENTIFIER) && isTypeHole(peek().lexeme) &&
                (peekNextIs(TokenType::FAT_ARROW) || peekNextIs(TokenType::WITH) || peekNextIs(TokenType::WITHOUT));
    const Token* third = tokenOrNull(current + 2);
    bool list = check(TokenType::L_BRACKET) && peekNextIs(TokenType::ELLIPSIS) && third != nullptr &&
                isTypeHole(third->lexeme) && typeIs(current + 3, TokenType::R_BRACKET);
    if (!bare && !list) return std::nullopt;

    std::vector<std::string> holes;
    std::vector<bool> holeIsList;
    std::vector<std::string> keywords;
    readTypeMacroOperand(macroName, holes, holeIsList);
    // `$Q with $T without $S` - the whole clause sequence is one pattern.
    while (check(TokenType::WITH) || check(TokenType::WITHOUT)) {
        keywords.push_back(advance().lexeme);
        readTypeMacroOperand(macroName, holes, holeIsList);
    }
    consume(TokenType::FAT_ARROW, "Expected '=>' after the pattern of '@" + macroName + "'");
    TypeRef templateType = parseTypeName();
    consumeNewline();
    TypeTypeArm arm;
    arm.kind = first(holeIsList) ? TypeFormKind::PREFIX_LIST : TypeFormKind::PREFIX;
    arm.holes = holes;
    arm.template_ = templateType;
    arm.name = macroName;
    arm.keywords = keywords;
    arm.holeIsList = holeIsList;
    return arm;
}

/** One operand of a type-macro arm: `$NAME` or `[...$NAME]`. */
void Parser::readTypeMacroOperand(const std::string& macroName, std::vector<std::string>& holes,
                                  std::vector<bool>& holeIsList) {
    if (match(TokenType::L_BRACKET)) {
        consume(TokenType::ELLIPSIS, "A list operand captures the whole list: write '[...$NAME]'");
        holes.push_back(consume(TokenType::IDENTIFIER, "Expected a '$NAME' hole in '@" + macroName + "'").lexeme);
        consume(TokenType::R_BRACKET, "Expected ']' after the list operand of '@" + macroName + "'");
        holeIsList.push_back(true);
    } else {
        holes.push_back(consume(TokenType::IDENTIFIER, "Expected a '$NAME' hole in '@" + macroName + "'").lexeme);
        holeIsList.push_back(false);
    }
}

/** Whether [lexeme] is a `$NAME` hole standing for a type rather than a value. */
bool Parser::isTypeHole(const std::string& lexeme) {
    if (!startsWith(lexeme, "$") || u16len(lexeme) <= 1) return false;
    std::u16string units16 = decodeWtf8(lexeme);
    for (size_t i = 1; i < units16.size(); i++) {
        if (isLowerCase(units16[i])) return false;
    }
    return true;
}

/**
 * One macro arm: `delim <pattern> delim => <template>`. A declaration arm may
 * produce adjacent expression fragments.
 */
MacroArm Parser::parseMacroArm() {
    MacroDelimiter delimiter;
    TokenType close;
    switch (peek().type) {
        case TokenType::L_PAREN:
            delimiter = MacroDelimiter::PAREN;
            close = TokenType::R_PAREN;
            break;
        case TokenType::L_BRACKET:
            delimiter = MacroDelimiter::BRACKET;
            close = TokenType::R_BRACKET;
            break;
        case TokenType::L_BRACE:
            delimiter = MacroDelimiter::BRACE;
            close = TokenType::R_BRACE;
            break;
        default:
            fail("Expected '(', '[', or '{' to start a macro arm at line " + str(peek().line));
    }
    advance();  // opening delimiter
    MacroPattern pattern;
    if (check(close)) {
        pattern.kind = MacroPatternKind::Empty;
    } else if (check(TokenType::ELLIPSIS)) {
        advance();  // '...'
        std::string cap = consume(TokenType::IDENTIFIER, "Expected capture name after '...' in macro pattern").lexeme;
        // `...${key: value}` - a key/value destructuring capture.
        if (cap == "$" && check(TokenType::L_BRACE)) {
            advance();  // '{'
            std::string key = consume(TokenType::IDENTIFIER, "Expected key name in '${key: value}'").lexeme;
            consume(TokenType::COLON, "Expected ':' in '${key: value}'");
            std::string value = consume(TokenType::IDENTIFIER, "Expected value name in '${key: value}'").lexeme;
            consume(TokenType::R_BRACE, "Expected '}' after '${key: value}'");
            pattern.kind = MacroPatternKind::MapEntryCapture;
            pattern.keyName = "$" + key;
            pattern.valueName = "$" + value;
        } else {
            pattern.kind = MacroPatternKind::SeqCapture;
            pattern.name = cap;
        }
    } else if (check(TokenType::IDENTIFIER) && startsWith(peek().lexeme, "$")) {
        std::string cap = advance().lexeme;
        consume(TokenType::COLON, "Expected ':' after typed macro capture '" + cap + "'");
        pattern.kind = MacroPatternKind::TypedCapture;
        pattern.name = cap;
        pattern.type = parseTypeName();
    } else {
        fail("Macro arm pattern must be empty, '...$name', or '$name: Type' at line " + str(peek().line));
    }
    consume(close, "Expected matching delimiter after macro arm pattern");
    consume(TokenType::FAT_ARROW, "Expected '=>' after macro arm pattern");
    Expr templateExpr = parseMacroTemplateExpr();
    std::vector<Expr> tail;
    // An arm ends at a newline or at the `}` closing the arm list.
    if (!check(TokenType::NEWLINE) && !check(TokenType::R_BRACE) && !isAtEnd()) {
        tail.push_back(parseMacroTemplateExpr());
    } else if (check(TokenType::NEWLINE)) {
        int32_t saved = current;
        skipNewlines();
        if (check(TokenType::INLINE) && peekNextIs(TokenType::IF)) tail.push_back(parseMacroTemplateExpr());
        else current = saved;
    }
    consumeNewline();
    return MacroArm{delimiter, pattern, templateExpr, tail};
}

/** One expression fragment in a macro expansion template. */
Expr Parser::parseMacroTemplateExpr() {
    if (match(TokenType::INLINE)) {
        if (!check(TokenType::IF)) fail("Only 'inline if' may select a macro template fragment at line " + str(peek().line));
        return parseIfExpr();
    }
    return parseExpr();
}

}  // namespace azora
