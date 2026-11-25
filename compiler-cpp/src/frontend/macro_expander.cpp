// Port of MacroExpander.kt. Every `copy(...)` keeps Kotlin's argument order:
// the first failure reported must be the same one.
#include "frontend/macro_expander.h"

#include "base/failure.h"
#include "base/ordered.h"
#include "base/text.h"
#include "frontend/lexer.h"

#include <algorithm>

namespace azora {
namespace MacroExpander {

namespace {

/** Guards against infinite macro recursion (a macro expanding into itself). */
constexpr int32_t MAX_DEPTH = 64;

struct MacroDefinition {
    std::optional<std::string> parameter;
    std::vector<MacroArm> arms;
};

/** Per-name macro definitions captured from TopLevelMeta declarations. */
using MacroTable = OrderedMap<std::string, MacroDefinition>;

using Bindings = OrderedMap<std::string, std::vector<Expr>>;

/** Value-infix macros active during the current expansion (op → rule). Reset per expand(). */
OrderedMap<std::string, InfixMacroRule> activeInfix;

/**
 * Capture names bound to parallel lists by the arm being expanded:
 * `[...${key: value}]` binds two, and a template spreading an expression over
 * them is expanded once per index.
 */
std::vector<std::string> zipCaptures;

[[noreturn]] void macroFail(int32_t line, const std::string& message) {
    fail(line != 0 ? message + " at line " + str(line) : message);
}

/** Kotlin `MutableMap.putIfAbsentCompat`: first writer wins. */
void putIfAbsent(OrderedMap<std::string, std::optional<std::string>>& map, const std::string& key,
                 const std::optional<std::string>& value) {
    if (!map.contains(key)) map.set(key, value);
}

bool isAsciiLetter(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
bool isAsciiDigit(char c) { return c >= '0' && c <= '9'; }
bool isAsciiWordChar(char c) { return isAsciiLetter(c) || isAsciiDigit(c); }

char asciiLower(char c) { return (c >= 'A' && c <= 'Z') ? (char)(c - 'A' + 'a') : c; }
char asciiUpper(char c) { return (c >= 'a' && c <= 'z') ? (char)(c - 'a' + 'A') : c; }

/** General macro-time String projection: `"API_VERSION".lowerCamel`. */
std::string lowerCamel(const std::string& value) {
    // Regex("([a-z0-9])([A-Z])") → "$1 $2", left to right, non-overlapping.
    std::string spaced;
    size_t i = 0;
    while (i < value.size()) {
        char c = value[i];
        bool lowerOrDigit = (c >= 'a' && c <= 'z') || isAsciiDigit(c);
        if (lowerOrDigit && i + 1 < value.size() && value[i + 1] >= 'A' && value[i + 1] <= 'Z') {
            spaced += c;
            spaced += ' ';
            spaced += value[i + 1];
            i += 2;
            continue;
        }
        spaced += c;
        i++;
    }
    // split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    std::vector<std::string> words;
    std::string word;
    for (char c : spaced) {
        if (isAsciiWordChar(c)) {
            word += c;
        } else if (!word.empty()) {
            words.push_back(word);
            word.clear();
        }
    }
    if (!word.empty()) words.push_back(word);
    if (words.empty()) return "";
    std::string out;
    for (char c : words[0]) out += asciiLower(c);
    for (size_t w = 1; w < words.size(); w++) {
        std::string lowered;
        for (char c : words[w]) lowered += asciiLower(c);
        if (!lowered.empty()) lowered[0] = asciiUpper(lowered[0]);
        out += lowered;
    }
    return out;
}

/** General macro-time String projection: `"native_HTTP_client".upperCamel`. */
std::string upperCamel(const std::string& value) {
    std::string out = lowerCamel(value);
    if (!out.empty()) out[0] = asciiUpper(out[0]);
    return out;
}

void validateMacroLocalName(const std::string& foreignName, const std::string& localName, int32_t line) {
    // Regex("_?[A-Za-z][A-Za-z0-9]*").matches(localName)
    size_t i = 0;
    if (i < localName.size() && localName[i] == '_') i++;
    bool matches = i < localName.size() && isAsciiLetter(localName[i]);
    if (matches) {
        for (size_t k = i + 1; k < localName.size(); k++) {
            if (!isAsciiWordChar(localName[k])) {
                matches = false;
                break;
            }
        }
    }
    if (!matches) macroFail(line, "foreign name '" + foreignName + "' produces invalid Azora identifier '" + localName + "'");
    if (reservedKeywords().contains(localName)) {
        macroFail(line, "foreign name '" + foreignName + "' produces reserved Azora keyword '" + localName + "'");
    }
}

/** Type checking available while selecting a typed macro arm. */
bool macroArgumentMatches(const Expr& argument, const TypeRef& expected) {
    TypeRefNamed* named = as<TypeRefNamed>(expected);
    if (named == nullptr) return false;
    std::string name = substringAfterLast(named->name, "__");
    if (name == "String") return is<ExprStringLiteral>(argument);
    if (name == "Bool") return is<ExprBoolLiteral>(argument);
    if (name == "Int" || name == "Long" || name == "Byte" || name == "Short" || name == "UInt" || name == "ULong" ||
        name == "UByte" || name == "UShort") {
        return is<ExprIntLiteral>(argument);
    }
    if (name == "Real" || name == "Double" || name == "Float") {
        return is<ExprDoubleLiteral>(argument) || is<ExprIntLiteral>(argument);
    }
    if (name == "Char") return is<ExprCharLiteral>(argument);
    return false;
}

struct ArmMatch {
    const MacroArm* arm;
    Bindings bindings;
};

/** Matches [args] against the first compatible [arms]. */
std::optional<ArmMatch> matchArm(const std::vector<MacroArm>& arms, const std::vector<Expr>& args) {
    for (const MacroArm& arm : arms) {
        switch (arm.pattern.kind) {
            case MacroPatternKind::Empty:
                if (args.empty()) return ArmMatch{&arm, {}};
                break;
            case MacroPatternKind::SeqCapture: {
                // A `k: v` argument belongs to a `[...${key: value}]` arm.
                bool anyEntry = std::any_of(args.begin(), args.end(), [](const Expr& a) { return is<ExprMapEntryArg>(a); });
                if (!args.empty() && !anyEntry) {
                    Bindings bindings;
                    bindings.set(arm.pattern.name, args);
                    return ArmMatch{&arm, std::move(bindings)};
                }
                break;
            }
            case MacroPatternKind::TypedCapture: {
                if (args.size() != 1) break;
                if (macroArgumentMatches(args[0], arm.pattern.type)) {
                    Bindings bindings;
                    bindings.set(arm.pattern.name, {args[0]});
                    return ArmMatch{&arm, std::move(bindings)};
                }
                break;
            }
            case MacroPatternKind::MapEntryCapture: {
                // Every argument must be a `k: v` pair; keys and values bind as two parallel lists.
                std::vector<Expr> keys;
                std::vector<Expr> values;
                for (const Expr& a : args) {
                    if (ExprMapEntryArg* entry = as<ExprMapEntryArg>(a)) {
                        keys.push_back(entry->key);
                        values.push_back(entry->value);
                    }
                }
                if (!keys.empty() && keys.size() == args.size()) {
                    Bindings bindings;
                    bindings.set(arm.pattern.keyName, std::move(keys));
                    bindings.set(arm.pattern.valueName, std::move(values));
                    return ArmMatch{&arm, std::move(bindings)};
                }
                break;
            }
        }
    }
    return std::nullopt;
}

struct Expander {
    Arena& arena;

    // ------------------------------------------------------------------
    // Infix substitution
    // ------------------------------------------------------------------

    /** Replaces the hole identifiers of an infix macro (`$a`, `$b`) with the operands. */
    Expr substituteInfix(const Expr& tpl, const Bindings& holes) {
        switch (tpl.kind()) {
            case ExprKind::Identifier: {
                const std::vector<Expr>* hole = holes.get(cast<ExprIdentifier>(tpl).name);
                return hole ? (*hole)[0] : tpl;
            }
            case ExprKind::Call: {
                ExprCall c = cast<ExprCall>(tpl);
                for (Expr& a : c.args) a = substituteInfix(a, holes);
                if (c.receiver) c.receiver = substituteInfix(c.receiver, holes);
                return make(arena, std::move(c));
            }
            case ExprKind::MethodCall: {
                ExprMethodCall c = cast<ExprMethodCall>(tpl);
                c.target = substituteInfix(c.target, holes);
                for (Expr& a : c.args) a = substituteInfix(a, holes);
                return make(arena, std::move(c));
            }
            case ExprKind::Member: {
                ExprMember c = cast<ExprMember>(tpl);
                c.target = substituteInfix(c.target, holes);
                return make(arena, std::move(c));
            }
            case ExprKind::Binary: {
                ExprBinary c = cast<ExprBinary>(tpl);
                c.left = substituteInfix(c.left, holes);
                c.right = substituteInfix(c.right, holes);
                return make(arena, std::move(c));
            }
            case ExprKind::Unary: {
                ExprUnary c = cast<ExprUnary>(tpl);
                c.operand = substituteInfix(c.operand, holes);
                return make(arena, std::move(c));
            }
            case ExprKind::Index: {
                ExprIndex c = cast<ExprIndex>(tpl);
                c.target = substituteInfix(c.target, holes);
                c.index = substituteInfix(c.index, holes);
                return make(arena, std::move(c));
            }
            case ExprKind::Grouping: {
                ExprGrouping c = cast<ExprGrouping>(tpl);
                c.expr = substituteInfix(c.expr, holes);
                return make(arena, std::move(c));
            }
            case ExprKind::Cast: {
                ExprCast c = cast<ExprCast>(tpl);
                c.expr = substituteInfix(c.expr, holes);
                return make(arena, std::move(c));
            }
            case ExprKind::NamedArg: {
                ExprNamedArg c = cast<ExprNamedArg>(tpl);
                c.value = substituteInfix(c.value, holes);
                return make(arena, std::move(c));
            }
            default:
                return tpl;
        }
    }

    // ------------------------------------------------------------------
    // Declaration names
    // ------------------------------------------------------------------

    TopLevel bridgeWrapper(const std::string& localName, const std::string& foreignName, const std::vector<Param>& params,
                           const TypeRef& returnType, const std::vector<std::string>& typeParams, int32_t line) {
        std::vector<Expr> args;
        for (const Param& p : params) args.push_back(make(arena, ExprIdentifier{p.name, line}));
        Expr call = make(arena, ExprCall{foreignName, std::move(args), line});
        TypeRefNamed* named = as<TypeRefNamed>(returnType);
        std::vector<Stmt> body;
        if (named && substringAfterLast(named->name, "__") == "Unit") {
            body.push_back(make(arena, StmtExprStmt{call, line}));
        } else {
            body.push_back(make(arena, StmtReturn{call, line}));
        }
        FuncDecl decl;
        decl.name = localName;
        decl.params = params;
        decl.returnType = TypeAnnotation{returnType};
        decl.body = std::move(body);
        decl.typeParams = typeParams;
        decl.line = line;
        return make(arena, TopLevelFunc{std::move(decl)});
    }

    /** Expands the two adjacent name fragments produced by a declaration macro: (backend name, Azora name). */
    std::pair<std::string, std::string> expandDeclarationName(const ExprMetaInvoke& invocation, const MacroTable& macros,
                                                              int32_t line, bool isType) {
        const MacroDefinition* definition = macros.get(invocation.name);
        if (definition == nullptr) macroFail(line, "macro '" + invocation.name + "' is not defined");
        std::optional<ArmMatch> match = matchArm(definition->arms, invocation.args);
        if (!match) {
            macroFail(line, "no matching arm in macro '" + invocation.name + "' for " + str((int64_t)invocation.args.size()) +
                                " argument(s)");
        }
        const MacroArm& arm = *match->arm;
        std::vector<Expr> parts{arm.template_};
        parts.insert(parts.end(), arm.templateTail.begin(), arm.templateTail.end());
        if (parts.size() != 2) {
            macroFail(line,
                      "a bridge declaration-name macro must expand to exactly two String fragments: backend name then "
                      "Azora name");
        }
        Bindings bindings = match->bindings;
        if (definition->parameter) {
            bindings.set(*definition->parameter, {make(arena, ExprBoolLiteral{isType, line})});
        }
        std::vector<Expr> expanded;
        for (const Expr& part : parts) expanded.push_back(rewriteExpr(substitute(part, bindings, line), macros, 1));
        ExprStringLiteral* foreign = as<ExprStringLiteral>(expanded[0]);
        if (foreign == nullptr) macroFail(line, "a bridge declaration-name macro's backend-name fragment must be String");
        std::string foreignName = foreign->value;
        ExprStringLiteral* local = as<ExprStringLiteral>(expanded[1]);
        if (local == nullptr) macroFail(line, "a bridge declaration-name macro's Azora-name fragment must be String");
        std::string localName = local->value;
        validateMacroLocalName(foreignName, localName, line);
        return {foreignName, localName};
    }

    static std::string scoped(const std::optional<std::string>& localScope, const std::string& local) {
        return localScope ? *localScope + "__" + local : local;
    }

    /** Resolves one declaration's name macro (expandDeclarationNames). */
    std::vector<TopLevel> rewriteDeclarationName(const TopLevel& item, const MacroTable& macros) {
        if (TopLevelPack* pack = as<TopLevelPack>(item); pack && pack->nameMacro) {
            auto [foreignName, expandedLocal] = expandDeclarationName(*pack->nameMacro, macros, pack->line, true);
            TopLevelPack c = *pack;
            c.name = scoped(pack->localScope, expandedLocal);
            c.foreignName = foreignName;
            c.nameMacro = nullptr;
            c.localScope = std::nullopt;
            return {make(arena, std::move(c))};
        }
        TopLevelBridge* bridge = as<TopLevelBridge>(item);
        if (bridge == nullptr) return {item};

        std::vector<TopLevel> wrappers;
        std::vector<BridgeSig> funcs;
        for (const BridgeSig& signature : bridge->funcs) {
            if (signature.nameMacro == nullptr) {
                funcs.push_back(signature);
                continue;
            }
            auto [foreignName, expandedLocal] = expandDeclarationName(*signature.nameMacro, macros, signature.line, false);
            std::string localName = scoped(signature.localScope, expandedLocal);
            if (localName != foreignName) {
                wrappers.push_back(bridgeWrapper(localName, foreignName, signature.params, signature.returnType,
                                                 signature.typeParams, signature.line));
            }
            BridgeSig c = signature;
            c.name = foreignName;
            c.localName = std::nullopt;
            c.nameMacro = nullptr;
            c.localScope = std::nullopt;
            funcs.push_back(std::move(c));
        }
        std::vector<BridgeValue> values;
        for (const BridgeValue& value : bridge->values) {
            if (value.nameMacro == nullptr) {
                values.push_back(value);
                continue;
            }
            auto [foreignName, expandedLocal] = expandDeclarationName(*value.nameMacro, macros, value.line, false);
            BridgeValue c = value;
            c.name = scoped(value.localScope, expandedLocal);
            c.foreignName = foreignName;
            c.nameMacro = nullptr;
            c.localScope = std::nullopt;
            values.push_back(std::move(c));
        }
        TopLevelBridge c = *bridge;
        c.funcs = std::move(funcs);
        c.values = std::move(values);
        std::vector<TopLevel> out{make(arena, std::move(c))};
        out.insert(out.end(), wrappers.begin(), wrappers.end());
        return out;
    }

    // ------------------------------------------------------------------
    // Item / function / parameter / annotation rewriting
    // ------------------------------------------------------------------

    /** A bridge name macro may add an ordinary local wrapper beside its extern. */
    std::vector<TopLevel> rewriteItems(const TopLevel& item, const MacroTable& macros, int32_t depth,
                                       OrderedMap<std::string, std::string>& renamedTypes) {
        if (TopLevelPack* pack = as<TopLevelPack>(item); pack && pack->nameMacro) {
            auto [foreignName, expandedLocal] = expandDeclarationName(*pack->nameMacro, macros, pack->line, true);
            std::string localName = scoped(pack->localScope, expandedLocal);
            renamedTypes.set(pack->name, localName);
            TopLevelPack c = *pack;
            c.name = localName;
            c.foreignName = foreignName;
            c.nameMacro = nullptr;
            c.localScope = std::nullopt;
            return {rewriteItem(make(arena, std::move(c)), macros, depth)};
        }
        TopLevelBridge* bridge = as<TopLevelBridge>(item);
        if (bridge == nullptr) return {rewriteItem(item, macros, depth)};

        std::vector<TopLevel> wrappers;
        std::vector<BridgeSig> funcs;
        for (const BridgeSig& signature : bridge->funcs) {
            std::string foreignName;
            std::optional<std::string> expandedLocal;
            if (signature.nameMacro == nullptr) {
                foreignName = signature.name;
                expandedLocal = signature.localName;
            } else {
                auto expanded = expandDeclarationName(*signature.nameMacro, macros, signature.line, false);
                foreignName = expanded.first;
                expandedLocal = expanded.second;
            }
            std::optional<std::string> localName;
            if (expandedLocal) localName = scoped(signature.localScope, *expandedLocal);
            if (localName && *localName != foreignName) {
                wrappers.push_back(bridgeWrapper(*localName, foreignName, signature.params, signature.returnType,
                                                 signature.typeParams, signature.line));
            }
            BridgeSig c = signature;
            c.name = foreignName;
            c.localName = std::nullopt;
            c.nameMacro = nullptr;
            c.localScope = std::nullopt;
            for (Param& p : c.params) p = rewriteParam(p, macros, depth);
            funcs.push_back(std::move(c));
        }
        std::vector<BridgeValue> values;
        for (const BridgeValue& value : bridge->values) {
            std::string foreignName;
            std::string expandedLocal;
            if (value.nameMacro == nullptr) {
                foreignName = value.foreignName ? *value.foreignName : value.name;
                expandedLocal = value.name;
            } else {
                auto expanded = expandDeclarationName(*value.nameMacro, macros, value.line, false);
                foreignName = expanded.first;
                expandedLocal = expanded.second;
            }
            BridgeValue c = value;
            c.name = scoped(value.localScope, expandedLocal);
            c.foreignName = foreignName;
            c.nameMacro = nullptr;
            c.localScope = std::nullopt;
            c.initializer = rewriteExpr(value.initializer, macros, depth);
            values.push_back(std::move(c));
        }
        TopLevelBridge c = *bridge;
        c.funcs = std::move(funcs);
        c.values = std::move(values);
        for (Annotation& a : c.annotations) a = rewriteAnnotation(a, macros, depth);
        std::vector<TopLevel> out{make(arena, std::move(c))};
        out.insert(out.end(), wrappers.begin(), wrappers.end());
        return out;
    }

    std::vector<TopLevel> rewriteItemList(const std::vector<TopLevel>& items, const MacroTable& macros, int32_t depth) {
        std::vector<TopLevel> out;
        out.reserve(items.size());
        for (const TopLevel& item : items) out.push_back(rewriteItem(item, macros, depth));
        return out;
    }

    void rewriteAnnotations(std::vector<Annotation>& annotations, const MacroTable& macros, int32_t depth) {
        for (Annotation& a : annotations) a = rewriteAnnotation(a, macros, depth);
    }

    TopLevel rewriteItem(const TopLevel& item, const MacroTable& macros, int32_t depth) {
        switch (item.kind()) {
            case TopLevelKind::Func: {
                TopLevelFunc c = cast<TopLevelFunc>(item);
                c.decl = rewriteFunc(c.decl, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::VarDecl: {
                TopLevelVarDecl c = cast<TopLevelVarDecl>(item);
                c.initializer = rewriteExpr(c.initializer, macros, depth);
                rewriteAnnotations(c.annotations, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::FinDecl: {
                TopLevelFinDecl c = cast<TopLevelFinDecl>(item);
                c.initializer = rewriteExpr(c.initializer, macros, depth);
                rewriteAnnotations(c.annotations, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::LetDecl: {
                TopLevelLetDecl c = cast<TopLevelLetDecl>(item);
                c.initializer = rewriteExpr(c.initializer, macros, depth);
                rewriteAnnotations(c.annotations, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::InlineVar: {
                TopLevelInlineVar c = cast<TopLevelInlineVar>(item);
                c.initializer = rewriteExpr(c.initializer, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::InlineFin: {
                TopLevelInlineFin c = cast<TopLevelInlineFin>(item);
                c.initializer = rewriteExpr(c.initializer, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::InlineLet: {
                TopLevelInlineLet c = cast<TopLevelInlineLet>(item);
                c.initializer = rewriteExpr(c.initializer, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::InlineAssignment: {
                TopLevelInlineAssignment c = cast<TopLevelInlineAssignment>(item);
                c.value = rewriteExpr(c.value, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::InlineTrace: {
                TopLevelInlineTrace c = cast<TopLevelInlineTrace>(item);
                c.message = rewriteExpr(c.message, macros, depth);
                if (c.level) c.level = rewriteExpr(c.level, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::InlineIf: {
                TopLevelInlineIf c = cast<TopLevelInlineIf>(item);
                c.condition = rewriteExpr(c.condition, macros, depth);
                c.thenBranch = rewriteItemList(c.thenBranch, macros, depth);
                if (c.elseBranch) c.elseBranch = rewriteItemList(*c.elseBranch, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::DeepInlineIf: {
                TopLevelDeepInlineIf c = cast<TopLevelDeepInlineIf>(item);
                c.condition = rewriteExpr(c.condition, macros, depth);
                c.thenBranch = rewriteItemList(c.thenBranch, macros, depth);
                if (c.elseBranch) c.elseBranch = rewriteItemList(*c.elseBranch, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::InlineBlock: {
                TopLevelInlineBlock c = cast<TopLevelInlineBlock>(item);
                c.body = rewriteItemList(c.body, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::DeepInlineBlock: {
                TopLevelDeepInlineBlock c = cast<TopLevelDeepInlineBlock>(item);
                c.body = rewriteItemList(c.body, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::Test: {
                TopLevelTest c = cast<TopLevelTest>(item);
                c.body = rewriteStmts(c.body, macros, depth);
                rewriteAnnotations(c.annotations, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::Pack: {
                TopLevelPack c = cast<TopLevelPack>(item);
                if (c.nameMacro) {
                    auto [foreignName, localName] = expandDeclarationName(*c.nameMacro, macros, c.line, true);
                    c.name = localName;
                    c.foreignName = foreignName;
                    c.nameMacro = nullptr;
                    c.localScope = std::nullopt;
                }
                for (PackField& f : c.fields) f = rewriteField(f, macros, depth);
                rewriteAnnotations(c.annotations, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::Deco: {
                TopLevelDeco c = cast<TopLevelDeco>(item);
                for (PackField& f : c.fields) f = rewriteField(f, macros, depth);
                rewriteAnnotations(c.annotations, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::Solo: {
                TopLevelSolo c = cast<TopLevelSolo>(item);
                for (PackField& f : c.fields) f = rewriteField(f, macros, depth);
                for (FuncDecl& m : c.methods) m = rewriteFunc(m, macros, depth);
                rewriteAnnotations(c.annotations, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::Impl: {
                TopLevelImpl c = cast<TopLevelImpl>(item);
                for (FuncDecl& m : c.methods) m = rewriteFunc(m, macros, depth);
                for (Expr& a : c.decoratorArgs) a = rewriteExpr(a, macros, depth);
                for (auto& [key, value] : c.decoratorNamedArgs) value = rewriteExpr(value, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::Spec: {
                TopLevelSpec c = cast<TopLevelSpec>(item);
                for (FuncDecl& m : c.methods) m = rewriteFunc(m, macros, depth);
                if (c.callback) {
                    for (Param& p : c.callback->params) p = rewriteParam(p, macros, depth);
                }
                return make(arena, std::move(c));
            }
            case TopLevelKind::Graph: {
                TopLevelGraph c = cast<TopLevelGraph>(item);
                for (GraphReg& reg : c.registrations) {
                    for (Expr& a : reg.args) a = rewriteExpr(a, macros, depth);
                }
                return make(arena, std::move(c));
            }
            // Declarations whose only Expr surface is their annotations.
            case TopLevelKind::Enum: {
                TopLevelEnum c = cast<TopLevelEnum>(item);
                rewriteAnnotations(c.annotations, macros, depth);
                for (std::vector<Annotation>& annotations : c.variantAnnotations) rewriteAnnotations(annotations, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::Fail: {
                TopLevelFail c = cast<TopLevelFail>(item);
                rewriteAnnotations(c.annotations, macros, depth);
                for (std::vector<Annotation>& annotations : c.variantAnnotations) rewriteAnnotations(annotations, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::Slot: {
                TopLevelSlot c = cast<TopLevelSlot>(item);
                rewriteAnnotations(c.annotations, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::TypeAlias: {
                TopLevelTypeAlias c = cast<TopLevelTypeAlias>(item);
                rewriteAnnotations(c.annotations, macros, depth);
                return make(arena, std::move(c));
            }
            case TopLevelKind::Bridge: {
                TopLevelBridge c = cast<TopLevelBridge>(item);
                rewriteAnnotations(c.annotations, macros, depth);
                return make(arena, std::move(c));
            }
            // Nested-only or macro-free declarations: nothing to expand.
            default:
                return item;
        }
    }

    FuncDecl rewriteFunc(const FuncDecl& decl, const MacroTable& macros, int32_t depth) {
        FuncDecl c = decl;
        for (Param& p : c.params) p = rewriteParam(p, macros, depth);
        c.body = rewriteStmts(c.body, macros, depth);
        rewriteAnnotations(c.annotations, macros, depth);
        return c;
    }

    Param rewriteParam(const Param& p, const MacroTable& macros, int32_t depth) {
        Param c = p;
        if (c.defaultValue) c.defaultValue = rewriteExpr(c.defaultValue, macros, depth);
        rewriteAnnotations(c.annotations, macros, depth);
        return c;
    }

    PackField rewriteField(const PackField& field, const MacroTable& macros, int32_t depth) {
        PackField c = field;
        if (c.default_) c.default_ = rewriteExpr(c.default_, macros, depth);
        rewriteAnnotations(c.annotations, macros, depth);
        return c;
    }

    Annotation rewriteAnnotation(const Annotation& a, const MacroTable& macros, int32_t depth) {
        Annotation c = a;
        for (Expr& arg : c.args) arg = rewriteExpr(arg, macros, depth);
        for (auto& [key, value] : c.namedArgs) value = rewriteExpr(value, macros, depth);
        return c;
    }

    // ------------------------------------------------------------------
    // Statement rewriting
    // ------------------------------------------------------------------

    std::vector<Stmt> rewriteStmts(const std::vector<Stmt>& stmts, const MacroTable& macros, int32_t depth) {
        std::vector<Stmt> out;
        out.reserve(stmts.size());
        for (const Stmt& s : stmts) out.push_back(rewriteStmt(s, macros, depth));
        return out;
    }

    std::vector<Expr> rewriteExprs(const std::vector<Expr>& items, const MacroTable& macros, int32_t depth) {
        std::vector<Expr> out;
        out.reserve(items.size());
        for (const Expr& e : items) out.push_back(rewriteExpr(e, macros, depth));
        return out;
    }

    Stmt rewriteStmt(const Stmt& stmt, const MacroTable& macros, int32_t depth) {
        auto rx = [&](const Expr& e) { return rewriteExpr(e, macros, depth); };
        auto rs = [&](const std::vector<Stmt>& b) { return rewriteStmts(b, macros, depth); };
        switch (stmt.kind()) {
            case StmtKind::None:
            case StmtKind::Import:
            case StmtKind::Continue:
                return stmt;
            case StmtKind::VarDecl: {
                StmtVarDecl c = cast<StmtVarDecl>(stmt);
                c.initializer = rx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::FinDecl: {
                StmtFinDecl c = cast<StmtFinDecl>(stmt);
                c.initializer = rx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::LetDecl: {
                StmtLetDecl c = cast<StmtLetDecl>(stmt);
                c.initializer = rx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineVar: {
                StmtInlineVar c = cast<StmtInlineVar>(stmt);
                c.initializer = rx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineFin: {
                StmtInlineFin c = cast<StmtInlineFin>(stmt);
                c.initializer = rx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineLet: {
                StmtInlineLet c = cast<StmtInlineLet>(stmt);
                c.initializer = rx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::RemDecl: {
                StmtRemDecl c = cast<StmtRemDecl>(stmt);
                c.initializer = rx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::Assignment: {
                StmtAssignment c = cast<StmtAssignment>(stmt);
                c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineAssignment: {
                StmtInlineAssignment c = cast<StmtInlineAssignment>(stmt);
                c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::Return: {
                StmtReturn c = cast<StmtReturn>(stmt);
                if (c.value) c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::ExprStmt: {
                StmtExprStmt c = cast<StmtExprStmt>(stmt);
                c.expr = rx(c.expr);
                return make(arena, std::move(c));
            }
            case StmtKind::Throw: {
                StmtThrow c = cast<StmtThrow>(stmt);
                c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::Panic: {
                StmtPanic c = cast<StmtPanic>(stmt);
                c.message = rx(c.message);
                return make(arena, std::move(c));
            }
            case StmtKind::Yield: {
                StmtYield c = cast<StmtYield>(stmt);
                c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::Assert: {
                StmtAssert c = cast<StmtAssert>(stmt);
                c.condition = rx(c.condition);
                c.message = rx(c.message);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineAssert: {
                StmtInlineAssert c = cast<StmtInlineAssert>(stmt);
                c.condition = rx(c.condition);
                c.message = rx(c.message);
                return make(arena, std::move(c));
            }
            case StmtKind::Trace: {
                StmtTrace c = cast<StmtTrace>(stmt);
                c.message = rx(c.message);
                if (c.level) c.level = rx(c.level);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineTrace: {
                StmtInlineTrace c = cast<StmtInlineTrace>(stmt);
                c.message = rx(c.message);
                if (c.level) c.level = rx(c.level);
                return make(arena, std::move(c));
            }
            case StmtKind::Exchange: {
                StmtExchange c = cast<StmtExchange>(stmt);
                c.left = rx(c.left);
                c.right = rx(c.right);
                return make(arena, std::move(c));
            }
            case StmtKind::IndexAssign: {
                StmtIndexAssign c = cast<StmtIndexAssign>(stmt);
                c.target = rx(c.target);
                c.index = rx(c.index);
                c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::MemberAssign: {
                StmtMemberAssign c = cast<StmtMemberAssign>(stmt);
                c.target = rx(c.target);
                c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::DerefAssign: {
                StmtDerefAssign c = cast<StmtDerefAssign>(stmt);
                c.target = rx(c.target);
                c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::If: {
                StmtIf c = cast<StmtIf>(stmt);
                c.condition = rx(c.condition);
                c.thenBranch = rs(c.thenBranch);
                if (c.elseBranch) c.elseBranch = rs(*c.elseBranch);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineIf: {
                StmtInlineIf c = cast<StmtInlineIf>(stmt);
                c.condition = rx(c.condition);
                c.thenBranch = rs(c.thenBranch);
                if (c.elseBranch) c.elseBranch = rs(*c.elseBranch);
                return make(arena, std::move(c));
            }
            case StmtKind::DeepInlineIf: {
                StmtDeepInlineIf c = cast<StmtDeepInlineIf>(stmt);
                c.condition = rx(c.condition);
                c.thenBranch = rs(c.thenBranch);
                if (c.elseBranch) c.elseBranch = rs(*c.elseBranch);
                return make(arena, std::move(c));
            }
            case StmtKind::While: {
                StmtWhile c = cast<StmtWhile>(stmt);
                c.condition = rx(c.condition);
                c.body = rs(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::For: {
                StmtFor c = cast<StmtFor>(stmt);
                c.iterable = rx(c.iterable);
                if (c.step) c.step = rx(c.step);
                c.body = rs(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineFor: {
                StmtInlineFor c = cast<StmtInlineFor>(stmt);
                c.iterable = rx(c.iterable);
                c.body = rs(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::Loop: {
                StmtLoop c = cast<StmtLoop>(stmt);
                if (c.iterable) c.iterable = rx(c.iterable);
                c.body = rs(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::Scope: {
                StmtScope c = cast<StmtScope>(stmt);
                c.body = rs(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineBlock: {
                StmtInlineBlock c = cast<StmtInlineBlock>(stmt);
                c.body = rs(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::DeepInlineBlock: {
                StmtDeepInlineBlock c = cast<StmtDeepInlineBlock>(stmt);
                c.body = rs(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::Effect: {
                StmtEffect c = cast<StmtEffect>(stmt);
                c.body = rs(c.body);
                if (c.dependencies) c.dependencies = rewriteExprs(*c.dependencies, macros, depth);
                return make(arena, std::move(c));
            }
            case StmtKind::UsingContext: {
                StmtUsingContext c = cast<StmtUsingContext>(stmt);
                c.values = rewriteExprs(c.values, macros, depth);
                c.body = rs(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::Defer: {
                StmtDefer c = cast<StmtDefer>(stmt);
                c.body = rs(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::Try: {
                StmtTry c = cast<StmtTry>(stmt);
                c.body = rs(c.body);
                if (c.catchBody) c.catchBody = rs(*c.catchBody);
                return make(arena, std::move(c));
            }
            case StmtKind::When: {
                StmtWhen c = cast<StmtWhen>(stmt);
                c.scrutinee = rx(c.scrutinee);
                for (WhenBranch& b : c.branches) {
                    b.patterns = rewriteExprs(b.patterns, macros, depth);
                    b.body = rs(b.body);
                }
                if (c.elseBranch) c.elseBranch = rs(*c.elseBranch);
                return make(arena, std::move(c));
            }
            case StmtKind::NoInline: {
                StmtNoInline c = cast<StmtNoInline>(stmt);
                c.stmt = rewriteStmt(c.stmt, macros, depth);
                return make(arena, std::move(c));
            }
            case StmtKind::Break: {
                StmtBreak c = cast<StmtBreak>(stmt);
                if (c.value) c.value = rx(c.value);
                return make(arena, std::move(c));
            }
        }
        return stmt;
    }

    // ------------------------------------------------------------------
    // Expression rewriting (the workhorse)
    // ------------------------------------------------------------------

    Expr rewriteExpr(const Expr& expr, const MacroTable& macros, int32_t depth) {
        if (depth > MAX_DEPTH) {
            macroFail(expr.line(), "macro expansion exceeded depth cap (" + str(MAX_DEPTH) +
                                       "); possible infinite macro recursion");
        }
        // A MetaInvoke: rewrite its args, expand to the matched template, then
        // re-run on the result (templates may themselves contain MetaInvoke).
        if (ExprMetaInvoke* invoke = as<ExprMetaInvoke>(expr)) {
            ExprMetaInvoke node = *invoke;
            node.args = rewriteExprs(invoke->args, macros, depth);
            Expr expanded = expandOne(node, macros);
            return rewriteExpr(expanded, macros, depth + 1);
        }
        auto rx = [&](const Expr& e) { return rewriteExpr(e, macros, depth); };
        switch (expr.kind()) {
            case ExprKind::InferredMember: {
                ExprInferredMember c = cast<ExprInferredMember>(expr);
                if (c.ctorArgs) c.ctorArgs = rewriteExprs(*c.ctorArgs, macros, depth);
                if (c.scopeCall) {
                    ExprCall call = *c.scopeCall;
                    call.args = rewriteExprs(call.args, macros, depth);
                    c.scopeCall = make(arena, std::move(call));
                }
                return make(arena, std::move(c));
            }
            case ExprKind::MapEntryArg: {
                ExprMapEntryArg c = cast<ExprMapEntryArg>(expr);
                c.key = rx(c.key);
                c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case ExprKind::None:
            case ExprKind::Identifier:
            case ExprKind::IntLiteral:
            case ExprKind::DoubleLiteral:
            case ExprKind::CharLiteral:
            case ExprKind::StringLiteral:
            case ExprKind::BoolLiteral:
            case ExprKind::NullLiteral:
            case ExprKind::UpperScopeAccess:
            case ExprKind::Inject:
            case ExprKind::MetaInvoke:
                return expr;
            case ExprKind::Unary: {
                ExprUnary c = cast<ExprUnary>(expr);
                c.operand = rx(c.operand);
                return make(arena, std::move(c));
            }
            case ExprKind::IncDec: {
                ExprIncDec c = cast<ExprIncDec>(expr);
                c.target = rx(c.target);
                return make(arena, std::move(c));
            }
            case ExprKind::Grouping: {
                ExprGrouping c = cast<ExprGrouping>(expr);
                c.expr = rx(c.expr);
                return make(arena, std::move(c));
            }
            case ExprKind::Member: {
                ExprMember c = cast<ExprMember>(expr);
                c.target = rx(c.target);
                return make(arena, std::move(c));
            }
            case ExprKind::SafeMember: {
                ExprSafeMember c = cast<ExprSafeMember>(expr);
                c.target = rx(c.target);
                return make(arena, std::move(c));
            }
            case ExprKind::TupleAccess: {
                ExprTupleAccess c = cast<ExprTupleAccess>(expr);
                c.target = rx(c.target);
                return make(arena, std::move(c));
            }
            case ExprKind::Index: {
                ExprIndex c = cast<ExprIndex>(expr);
                c.target = rx(c.target);
                c.index = rx(c.index);
                return make(arena, std::move(c));
            }
            case ExprKind::Range: {
                ExprRange c = cast<ExprRange>(expr);
                c.from = rx(c.from);
                c.to = rx(c.to);
                return make(arena, std::move(c));
            }
            case ExprKind::Binary: {
                ExprBinary c = cast<ExprBinary>(expr);
                c.left = rx(c.left);
                c.right = rx(c.right);
                return make(arena, std::move(c));
            }
            case ExprKind::NullCoalesce: {
                ExprNullCoalesce c = cast<ExprNullCoalesce>(expr);
                c.left = rx(c.left);
                c.right = rx(c.right);
                return make(arena, std::move(c));
            }
            case ExprKind::CatchExpr: {
                ExprCatchExpr c = cast<ExprCatchExpr>(expr);
                c.expr = rx(c.expr);
                c.fallback = rx(c.fallback);
                return make(arena, std::move(c));
            }
            case ExprKind::Seal: {
                ExprSeal c = cast<ExprSeal>(expr);
                c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case ExprKind::IfExpr: {
                ExprIfExpr c = cast<ExprIfExpr>(expr);
                c.condition = rx(c.condition);
                c.thenExpr = rx(c.thenExpr);
                c.elseExpr = rx(c.elseExpr);
                return make(arena, std::move(c));
            }
            case ExprKind::Cast: {
                ExprCast c = cast<ExprCast>(expr);
                c.expr = rx(c.expr);
                return make(arena, std::move(c));
            }
            case ExprKind::InlineForArgs: {
                ExprInlineForArgs c = cast<ExprInlineForArgs>(expr);
                c.iterable = rx(c.iterable);
                c.body = rx(c.body);
                return make(arena, std::move(c));
            }
            case ExprKind::IsCheck: {
                ExprIsCheck c = cast<ExprIsCheck>(expr);
                c.expr = rx(c.expr);
                return make(arena, std::move(c));
            }
            case ExprKind::InCheck: {
                ExprInCheck c = cast<ExprInCheck>(expr);
                c.value = rx(c.value);
                c.collection = rx(c.collection);
                return make(arena, std::move(c));
            }
            case ExprKind::Alloc: {
                ExprAlloc c = cast<ExprAlloc>(expr);
                c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case ExprKind::Deref: {
                ExprDeref c = cast<ExprDeref>(expr);
                c.target = rx(c.target);
                return make(arena, std::move(c));
            }
            case ExprKind::Isolated: {
                ExprIsolated c = cast<ExprIsolated>(expr);
                c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case ExprKind::Await: {
                ExprAwait c = cast<ExprAwait>(expr);
                c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case ExprKind::TryPropagate: {
                ExprTryPropagate c = cast<ExprTryPropagate>(expr);
                c.expr = rx(c.expr);
                return make(arena, std::move(c));
            }
            case ExprKind::Spread: {
                ExprSpread c = cast<ExprSpread>(expr);
                c.array = rx(c.array);
                return make(arena, std::move(c));
            }
            case ExprKind::NamedArg: {
                ExprNamedArg c = cast<ExprNamedArg>(expr);
                c.value = rx(c.value);
                return make(arena, std::move(c));
            }
            case ExprKind::Call: {
                ExprCall c = cast<ExprCall>(expr);
                c.args = rewriteExprs(c.args, macros, depth);
                return make(arena, std::move(c));
            }
            case ExprKind::MethodCall: {
                const ExprMethodCall& call = cast<ExprMethodCall>(expr);
                // Value-infix macro: `a op b` (parsed as `a.op(b)`) rewrites to the
                // `meta .Infix("op") { $a $b => … }` template.
                const InfixMacroRule* infix = activeInfix.get(call.name);
                if (infix != nullptr && call.args.size() == 1) {
                    Expr target = rx(call.target);
                    Expr arg = rx(call.args[0]);
                    Bindings holes;
                    holes.set(infix->left, {target});
                    holes.set(infix->right, {arg});
                    return rewriteExpr(substituteInfix(infix->template_, holes), macros, depth);
                }
                ExprMethodCall c = call;
                c.target = rx(c.target);
                c.args = rewriteExprs(c.args, macros, depth);
                return make(arena, std::move(c));
            }
            case ExprKind::ArrayLiteral: {
                ExprArrayLiteral c = cast<ExprArrayLiteral>(expr);
                c.elements = rewriteExprs(c.elements, macros, depth);
                return make(arena, std::move(c));
            }
            case ExprKind::SetLiteral: {
                ExprSetLiteral c = cast<ExprSetLiteral>(expr);
                c.elements = rewriteExprs(c.elements, macros, depth);
                return make(arena, std::move(c));
            }
            case ExprKind::TupleLit: {
                ExprTupleLit c = cast<ExprTupleLit>(expr);
                c.elements = rewriteExprs(c.elements, macros, depth);
                return make(arena, std::move(c));
            }
            case ExprKind::VariantLit: {
                ExprVariantLit c = cast<ExprVariantLit>(expr);
                c.elements = rewriteExprs(c.elements, macros, depth);
                return make(arena, std::move(c));
            }
            case ExprKind::MapLit: {
                ExprMapLit c = cast<ExprMapLit>(expr);
                for (std::pair<Expr, Expr>& entry : c.entries) {
                    entry.first = rx(entry.first);
                    entry.second = rx(entry.second);
                }
                return make(arena, std::move(c));
            }
            case ExprKind::StringTemplate: {
                ExprStringTemplate c = cast<ExprStringTemplate>(expr);
                for (StringTemplatePart& part : c.parts) {
                    if (!part.isLiteral) part = exprPart(rx(part.expr));
                }
                return make(arena, std::move(c));
            }
            case ExprKind::Lambda: {
                ExprLambda c = cast<ExprLambda>(expr);
                for (Param& p : c.params) p = rewriteParam(p, macros, depth);
                for (Param& p : c.receivers) p = rewriteParam(p, macros, depth);
                c.body = rewriteStmts(c.body, macros, depth);
                return make(arena, std::move(c));
            }
            case ExprKind::Slice: {
                ExprSlice c = cast<ExprSlice>(expr);
                c.target = rx(c.target);
                if (c.start) c.start = rx(c.start);
                if (c.stop) c.stop = rx(c.stop);
                if (c.step) c.step = rx(c.step);
                return make(arena, std::move(c));
            }
        }
        return expr;
    }

    // ------------------------------------------------------------------
    // Expansion
    // ------------------------------------------------------------------

    /** Resolves an ExprMetaInvoke to its expanded template expression. */
    Expr expandOne(const ExprMetaInvoke& node, const MacroTable& macros) {
        const MacroDefinition* definition = macros.get(node.name);
        if (definition == nullptr) macroFail(node.line, "macro '" + node.name + "' is not defined");
        std::optional<ArmMatch> match = matchArm(definition->arms, node.args);
        if (!match) {
            macroFail(node.line, "no matching arm in macro '" + node.name + "' for " + str((int64_t)node.args.size()) +
                                     " argument(s)");
        }
        if (definition->parameter) {
            macroFail(node.line, "macro '" + node.name + "' has declaration parameter '" + *definition->parameter +
                                     "' and can only be applied to a declaration");
        }
        const MacroArm& arm = *match->arm;
        if (!arm.templateTail.empty()) {
            macroFail(node.line, "macro '" + node.name + "' expands to declaration fragments and cannot be used as an expression");
        }
        // An arm binding parallel captures makes a spread over them a zip; a
        // nested macro restores whatever its own arm needs on the way out.
        std::vector<std::string> savedZip = zipCaptures;
        if (arm.pattern.kind == MacroPatternKind::MapEntryCapture) {
            zipCaptures = {arm.pattern.keyName, arm.pattern.valueName};
        } else {
            zipCaptures = {};
        }
        try {
            Expr result = substitute(arm.template_, match->bindings, node.line);
            zipCaptures = savedZip;
            return result;
        } catch (...) {
            zipCaptures = savedZip;
            throw;
        }
    }

    // ------------------------------------------------------------------
    // Template substitution (splice-aware)
    // ------------------------------------------------------------------

    std::vector<Expr> substituteEach(const std::vector<Expr>& items, const Bindings& bindings, int32_t invokeLine) {
        std::vector<Expr> out;
        out.reserve(items.size());
        for (const Expr& e : items) out.push_back(substitute(e, bindings, invokeLine));
        return out;
    }

    std::vector<Stmt> substituteStmts(const std::vector<Stmt>& items, const Bindings& bindings, int32_t invokeLine) {
        std::vector<Stmt> out;
        out.reserve(items.size());
        for (const Stmt& s : items) out.push_back(substituteStmt(s, bindings, invokeLine));
        return out;
    }

    /** A fresh Expr from [tpl] with macro [bindings] substituted. */
    Expr substitute(const Expr& tpl, const Bindings& bindings, int32_t invokeLine) {
        auto sx = [&](const Expr& e) { return substitute(e, bindings, invokeLine); };
        switch (tpl.kind()) {
            case ExprKind::InferredMember:
                return tpl;
            case ExprKind::MapEntryArg: {
                ExprMapEntryArg c = cast<ExprMapEntryArg>(tpl);
                c.key = sx(c.key);
                c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            case ExprKind::Identifier: {
                const ExprIdentifier& id = cast<ExprIdentifier>(tpl);
                if (const std::vector<Expr>* captured = bindings.get(id.name)) {
                    if (captured->size() == 1) return (*captured)[0];
                    macroFail(id.line != 0 ? id.line : invokeLine,
                              "macro capture '$" + id.name + "' must appear under '...' when it binds multiple expressions");
                }
                return tpl;
            }
            // Leaves - no Expr children.
            case ExprKind::None:
            case ExprKind::IntLiteral:
            case ExprKind::DoubleLiteral:
            case ExprKind::CharLiteral:
            case ExprKind::StringLiteral:
            case ExprKind::BoolLiteral:
            case ExprKind::NullLiteral:
            case ExprKind::UpperScopeAccess:
            case ExprKind::Inject:
                return tpl;
            case ExprKind::Unary: {
                ExprUnary c = cast<ExprUnary>(tpl);
                c.operand = sx(c.operand);
                return make(arena, std::move(c));
            }
            case ExprKind::IncDec: {
                ExprIncDec c = cast<ExprIncDec>(tpl);
                c.target = sx(c.target);
                return make(arena, std::move(c));
            }
            case ExprKind::Grouping: {
                ExprGrouping c = cast<ExprGrouping>(tpl);
                c.expr = sx(c.expr);
                return make(arena, std::move(c));
            }
            case ExprKind::Member: {
                const ExprMember& member = cast<ExprMember>(tpl);
                Expr target = sx(member.target);
                ExprStringLiteral* text = as<ExprStringLiteral>(target);
                if (text && member.name == "lowerCamel") {
                    return make(arena, ExprStringLiteral{lowerCamel(text->value), member.line, member.column});
                }
                if (text && member.name == "upperCamel") {
                    return make(arena, ExprStringLiteral{upperCamel(text->value), member.line, member.column});
                }
                if (is<ExprBoolLiteral>(target) && member.name == "isType") return target;
                ExprMember c = member;
                c.target = target;
                return make(arena, std::move(c));
            }
            case ExprKind::SafeMember: {
                ExprSafeMember c = cast<ExprSafeMember>(tpl);
                c.target = sx(c.target);
                return make(arena, std::move(c));
            }
            case ExprKind::TupleAccess: {
                ExprTupleAccess c = cast<ExprTupleAccess>(tpl);
                c.target = sx(c.target);
                return make(arena, std::move(c));
            }
            case ExprKind::Index: {
                ExprIndex c = cast<ExprIndex>(tpl);
                c.target = sx(c.target);
                c.index = sx(c.index);
                return make(arena, std::move(c));
            }
            case ExprKind::Range: {
                ExprRange c = cast<ExprRange>(tpl);
                c.from = sx(c.from);
                c.to = sx(c.to);
                return make(arena, std::move(c));
            }
            case ExprKind::Binary: {
                ExprBinary c = cast<ExprBinary>(tpl);
                c.left = sx(c.left);
                c.right = sx(c.right);
                return make(arena, std::move(c));
            }
            case ExprKind::NullCoalesce: {
                ExprNullCoalesce c = cast<ExprNullCoalesce>(tpl);
                c.left = sx(c.left);
                c.right = sx(c.right);
                return make(arena, std::move(c));
            }
            case ExprKind::CatchExpr: {
                ExprCatchExpr c = cast<ExprCatchExpr>(tpl);
                c.expr = sx(c.expr);
                c.fallback = sx(c.fallback);
                return make(arena, std::move(c));
            }
            case ExprKind::Seal: {
                ExprSeal c = cast<ExprSeal>(tpl);
                c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            case ExprKind::IfExpr: {
                const ExprIfExpr& ifExpr = cast<ExprIfExpr>(tpl);
                Expr condition = sx(ifExpr.condition);
                if (ExprBoolLiteral* known = as<ExprBoolLiteral>(condition)) {
                    return sx(known->value ? ifExpr.thenExpr : ifExpr.elseExpr);
                }
                ExprIfExpr c = ifExpr;
                c.condition = condition;
                c.thenExpr = sx(c.thenExpr);
                c.elseExpr = sx(c.elseExpr);
                return make(arena, std::move(c));
            }
            case ExprKind::Cast: {
                ExprCast c = cast<ExprCast>(tpl);
                c.expr = sx(c.expr);
                return make(arena, std::move(c));
            }
            case ExprKind::InlineForArgs: {
                ExprInlineForArgs c = cast<ExprInlineForArgs>(tpl);
                c.iterable = sx(c.iterable);
                c.body = sx(c.body);
                return make(arena, std::move(c));
            }
            case ExprKind::IsCheck: {
                ExprIsCheck c = cast<ExprIsCheck>(tpl);
                c.expr = sx(c.expr);
                return make(arena, std::move(c));
            }
            case ExprKind::InCheck: {
                ExprInCheck c = cast<ExprInCheck>(tpl);
                c.value = sx(c.value);
                c.collection = sx(c.collection);
                return make(arena, std::move(c));
            }
            case ExprKind::Alloc: {
                ExprAlloc c = cast<ExprAlloc>(tpl);
                c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            case ExprKind::Deref: {
                ExprDeref c = cast<ExprDeref>(tpl);
                c.target = sx(c.target);
                return make(arena, std::move(c));
            }
            case ExprKind::Isolated: {
                ExprIsolated c = cast<ExprIsolated>(tpl);
                c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            case ExprKind::Await: {
                ExprAwait c = cast<ExprAwait>(tpl);
                c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            case ExprKind::TryPropagate: {
                ExprTryPropagate c = cast<ExprTryPropagate>(tpl);
                c.expr = sx(c.expr);
                return make(arena, std::move(c));
            }
            case ExprKind::Spread: {
                ExprSpread c = cast<ExprSpread>(tpl);
                c.array = sx(c.array);
                return make(arena, std::move(c));
            }
            case ExprKind::NamedArg: {
                ExprNamedArg c = cast<ExprNamedArg>(tpl);
                c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            // Sequence containers - splice-aware.
            case ExprKind::Call: {
                ExprCall c = cast<ExprCall>(tpl);
                c.args = substituteSeq(c.args, bindings, invokeLine);
                return make(arena, std::move(c));
            }
            case ExprKind::MethodCall: {
                ExprMethodCall c = cast<ExprMethodCall>(tpl);
                c.target = sx(c.target);
                c.args = substituteSeq(c.args, bindings, invokeLine);
                return make(arena, std::move(c));
            }
            case ExprKind::ArrayLiteral: {
                ExprArrayLiteral c = cast<ExprArrayLiteral>(tpl);
                c.elements = substituteSeq(c.elements, bindings, invokeLine);
                return make(arena, std::move(c));
            }
            case ExprKind::SetLiteral: {
                ExprSetLiteral c = cast<ExprSetLiteral>(tpl);
                c.elements = substituteSeq(c.elements, bindings, invokeLine);
                return make(arena, std::move(c));
            }
            case ExprKind::TupleLit: {
                ExprTupleLit c = cast<ExprTupleLit>(tpl);
                c.elements = substituteSeq(c.elements, bindings, invokeLine);
                return make(arena, std::move(c));
            }
            case ExprKind::VariantLit: {
                ExprVariantLit c = cast<ExprVariantLit>(tpl);
                c.elements = substituteSeq(c.elements, bindings, invokeLine);
                return make(arena, std::move(c));
            }
            case ExprKind::MapLit: {
                ExprMapLit c = cast<ExprMapLit>(tpl);
                for (std::pair<Expr, Expr>& entry : c.entries) {
                    entry.first = sx(entry.first);
                    entry.second = sx(entry.second);
                }
                return make(arena, std::move(c));
            }
            case ExprKind::StringTemplate: {
                ExprStringTemplate c = cast<ExprStringTemplate>(tpl);
                for (StringTemplatePart& part : c.parts) {
                    if (!part.isLiteral) part = exprPart(sx(part.expr));
                }
                return make(arena, std::move(c));
            }
            case ExprKind::Lambda: {
                ExprLambda c = cast<ExprLambda>(tpl);
                for (Param& p : c.params) {
                    if (p.defaultValue) p.defaultValue = sx(p.defaultValue);
                }
                for (Param& p : c.receivers) {
                    if (p.defaultValue) p.defaultValue = sx(p.defaultValue);
                }
                c.body = substituteStmts(c.body, bindings, invokeLine);
                return make(arena, std::move(c));
            }
            // A nested MetaInvoke surviving into a template: splice-substitute its
            // args and leave the node for the outer rewriteExpr loop to expand.
            case ExprKind::MetaInvoke: {
                ExprMetaInvoke c = cast<ExprMetaInvoke>(tpl);
                c.args = substituteSeq(c.args, bindings, invokeLine);
                return make(arena, std::move(c));
            }
            case ExprKind::Slice: {
                ExprSlice c = cast<ExprSlice>(tpl);
                c.target = sx(c.target);
                if (c.start) c.start = sx(c.start);
                if (c.stop) c.stop = sx(c.stop);
                if (c.step) c.step = sx(c.step);
                return make(arena, std::move(c));
            }
        }
        return tpl;
    }

    /** Mirrors substitute() for the Stmt children of a lambda body. */
    Stmt substituteStmt(const Stmt& stmt, const Bindings& bindings, int32_t invokeLine) {
        auto sx = [&](const Expr& e) { return substitute(e, bindings, invokeLine); };
        auto ss = [&](const std::vector<Stmt>& b) { return substituteStmts(b, bindings, invokeLine); };
        switch (stmt.kind()) {
            case StmtKind::None:
            case StmtKind::Import:
            case StmtKind::Continue:
                return stmt;
            case StmtKind::VarDecl: {
                StmtVarDecl c = cast<StmtVarDecl>(stmt);
                c.initializer = sx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::FinDecl: {
                StmtFinDecl c = cast<StmtFinDecl>(stmt);
                c.initializer = sx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::LetDecl: {
                StmtLetDecl c = cast<StmtLetDecl>(stmt);
                c.initializer = sx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineVar: {
                StmtInlineVar c = cast<StmtInlineVar>(stmt);
                c.initializer = sx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineFin: {
                StmtInlineFin c = cast<StmtInlineFin>(stmt);
                c.initializer = sx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineLet: {
                StmtInlineLet c = cast<StmtInlineLet>(stmt);
                c.initializer = sx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::RemDecl: {
                StmtRemDecl c = cast<StmtRemDecl>(stmt);
                c.initializer = sx(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::Assignment: {
                StmtAssignment c = cast<StmtAssignment>(stmt);
                c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineAssignment: {
                StmtInlineAssignment c = cast<StmtInlineAssignment>(stmt);
                c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::Return: {
                StmtReturn c = cast<StmtReturn>(stmt);
                if (c.value) c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::ExprStmt: {
                StmtExprStmt c = cast<StmtExprStmt>(stmt);
                c.expr = sx(c.expr);
                return make(arena, std::move(c));
            }
            case StmtKind::Throw: {
                StmtThrow c = cast<StmtThrow>(stmt);
                c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::Panic: {
                StmtPanic c = cast<StmtPanic>(stmt);
                c.message = sx(c.message);
                return make(arena, std::move(c));
            }
            case StmtKind::Yield: {
                StmtYield c = cast<StmtYield>(stmt);
                c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::Assert: {
                StmtAssert c = cast<StmtAssert>(stmt);
                c.condition = sx(c.condition);
                c.message = sx(c.message);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineAssert: {
                StmtInlineAssert c = cast<StmtInlineAssert>(stmt);
                c.condition = sx(c.condition);
                c.message = sx(c.message);
                return make(arena, std::move(c));
            }
            case StmtKind::Trace: {
                StmtTrace c = cast<StmtTrace>(stmt);
                c.message = sx(c.message);
                if (c.level) c.level = sx(c.level);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineTrace: {
                StmtInlineTrace c = cast<StmtInlineTrace>(stmt);
                c.message = sx(c.message);
                if (c.level) c.level = sx(c.level);
                return make(arena, std::move(c));
            }
            case StmtKind::Exchange: {
                StmtExchange c = cast<StmtExchange>(stmt);
                c.left = sx(c.left);
                c.right = sx(c.right);
                return make(arena, std::move(c));
            }
            case StmtKind::IndexAssign: {
                StmtIndexAssign c = cast<StmtIndexAssign>(stmt);
                c.target = sx(c.target);
                c.index = sx(c.index);
                c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::MemberAssign: {
                StmtMemberAssign c = cast<StmtMemberAssign>(stmt);
                c.target = sx(c.target);
                c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::DerefAssign: {
                StmtDerefAssign c = cast<StmtDerefAssign>(stmt);
                c.target = sx(c.target);
                c.value = sx(c.value);
                return make(arena, std::move(c));
            }
            case StmtKind::If: {
                StmtIf c = cast<StmtIf>(stmt);
                c.condition = sx(c.condition);
                c.thenBranch = ss(c.thenBranch);
                if (c.elseBranch) c.elseBranch = ss(*c.elseBranch);
                return make(arena, std::move(c));
            }
            case StmtKind::While: {
                StmtWhile c = cast<StmtWhile>(stmt);
                c.condition = sx(c.condition);
                c.body = ss(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::For: {
                StmtFor c = cast<StmtFor>(stmt);
                c.iterable = sx(c.iterable);
                if (c.step) c.step = sx(c.step);
                c.body = ss(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::Loop: {
                StmtLoop c = cast<StmtLoop>(stmt);
                if (c.iterable) c.iterable = sx(c.iterable);
                c.body = ss(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::Scope: {
                StmtScope c = cast<StmtScope>(stmt);
                c.body = ss(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineBlock: {
                StmtInlineBlock c = cast<StmtInlineBlock>(stmt);
                c.body = ss(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::DeepInlineBlock: {
                StmtDeepInlineBlock c = cast<StmtDeepInlineBlock>(stmt);
                c.body = ss(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::Effect: {
                StmtEffect c = cast<StmtEffect>(stmt);
                c.body = ss(c.body);
                if (c.dependencies) c.dependencies = substituteEach(*c.dependencies, bindings, invokeLine);
                return make(arena, std::move(c));
            }
            case StmtKind::UsingContext: {
                StmtUsingContext c = cast<StmtUsingContext>(stmt);
                c.values = substituteEach(c.values, bindings, invokeLine);
                c.body = ss(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::Defer: {
                StmtDefer c = cast<StmtDefer>(stmt);
                c.body = ss(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::Try: {
                StmtTry c = cast<StmtTry>(stmt);
                c.body = ss(c.body);
                if (c.catchBody) c.catchBody = ss(*c.catchBody);
                return make(arena, std::move(c));
            }
            case StmtKind::When: {
                StmtWhen c = cast<StmtWhen>(stmt);
                c.scrutinee = sx(c.scrutinee);
                for (WhenBranch& b : c.branches) {
                    b.patterns = substituteEach(b.patterns, bindings, invokeLine);
                    b.body = ss(b.body);
                }
                if (c.elseBranch) c.elseBranch = ss(*c.elseBranch);
                return make(arena, std::move(c));
            }
            case StmtKind::NoInline: {
                StmtNoInline c = cast<StmtNoInline>(stmt);
                c.stmt = substituteStmt(c.stmt, bindings, invokeLine);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineIf: {
                StmtInlineIf c = cast<StmtInlineIf>(stmt);
                c.condition = sx(c.condition);
                c.thenBranch = ss(c.thenBranch);
                if (c.elseBranch) c.elseBranch = ss(*c.elseBranch);
                return make(arena, std::move(c));
            }
            case StmtKind::DeepInlineIf: {
                StmtDeepInlineIf c = cast<StmtDeepInlineIf>(stmt);
                c.condition = sx(c.condition);
                c.thenBranch = ss(c.thenBranch);
                if (c.elseBranch) c.elseBranch = ss(*c.elseBranch);
                return make(arena, std::move(c));
            }
            case StmtKind::InlineFor: {
                StmtInlineFor c = cast<StmtInlineFor>(stmt);
                c.iterable = sx(c.iterable);
                c.body = ss(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::Break: {
                StmtBreak c = cast<StmtBreak>(stmt);
                if (c.value) c.value = sx(c.value);
                return make(arena, std::move(c));
            }
        }
        return stmt;
    }

    /** Rebuilds a sequence-position list, splicing any bound `...$capture` inline. */
    std::vector<Expr> substituteSeq(const std::vector<Expr>& children, const Bindings& bindings, int32_t invokeLine) {
        if (children.empty()) return children;
        std::vector<Expr> out;
        out.reserve(children.size());
        for (const Expr& child : children) {
            if (std::optional<std::vector<Expr>> zipped = tryZipSpread(child, bindings, invokeLine)) {
                out.insert(out.end(), zipped->begin(), zipped->end());
                continue;
            }
            if (const std::vector<Expr>* spliced = trySpread(child, bindings)) {
                for (const Expr& element : *spliced) out.push_back(substitute(element, bindings, invokeLine));
            } else {
                out.push_back(substitute(child, bindings, invokeLine));
            }
        }
        return out;
    }

    /**
     * Expands `...<expression>` over the parallel captures in zipCaptures, one
     * element per index; none when [child] is not that form.
     */
    std::optional<std::vector<Expr>> tryZipSpread(const Expr& child, const Bindings& bindings, int32_t invokeLine) {
        ExprSpread* spread = as<ExprSpread>(child);
        if (spread == nullptr || is<ExprIdentifier>(spread->array)) return std::nullopt;
        std::vector<std::string> names;
        for (const std::string& name : zipCaptures) {
            if (bindings.contains(name)) names.push_back(name);
        }
        if (names.size() < 2) return std::nullopt;
        size_t length = bindings.get(names[0])->size();
        for (const std::string& name : names) {
            if (bindings.get(name)->size() != length) {
                macroFail(invokeLine, "parallel macro captures must bind the same number of arguments");
            }
        }
        std::vector<Expr> out;
        for (size_t index = 0; index < length; index++) {
            Bindings perIndex;
            for (const auto& [name, values] : bindings.entries) {
                if (std::find(names.begin(), names.end(), name) != names.end()) {
                    perIndex.set(name, {values[index]});
                } else {
                    perIndex.set(name, values);
                }
            }
            out.push_back(substitute(spread->array, perIndex, invokeLine));
        }
        return out;
    }

    /** The captured list if [child] is `Spread(Identifier(name))` with [name] bound; else none. */
    const std::vector<Expr>* trySpread(const Expr& child, const Bindings& bindings) {
        ExprSpread* spread = as<ExprSpread>(child);
        if (spread == nullptr) return nullptr;
        ExprIdentifier* inner = as<ExprIdentifier>(spread->array);
        if (inner == nullptr) return nullptr;
        return bindings.get(inner->name);
    }
};

bool declaresNameMacro(const TopLevel& item) {
    if (TopLevelPack* pack = as<TopLevelPack>(item)) return pack->nameMacro != nullptr;
    if (TopLevelBridge* bridge = as<TopLevelBridge>(item)) {
        for (const BridgeSig& f : bridge->funcs) {
            if (f.nameMacro) return true;
        }
        for (const BridgeValue& v : bridge->values) {
            if (v.nameMacro) return true;
        }
    }
    return false;
}

void addArms(OrderedMap<std::string, std::vector<MacroArm>>& macroArms, const TopLevelMeta& meta) {
    std::vector<MacroArm>* arms = macroArms.get(meta.name);
    if (arms == nullptr) {
        macroArms.set(meta.name, {});
        arms = macroArms.get(meta.name);
    }
    arms->insert(arms->end(), meta.arms.begin(), meta.arms.end());
}

MacroTable tableOf(const OrderedMap<std::string, std::vector<MacroArm>>& macroArms,
                   const OrderedMap<std::string, std::optional<std::string>>& macroParameters) {
    MacroTable table;
    for (const auto& [name, arms] : macroArms.entries) {
        const std::optional<std::string>* parameter = macroParameters.get(name);
        table.set(name, MacroDefinition{parameter ? *parameter : std::nullopt, arms});
    }
    return table;
}

}  // namespace

std::vector<Program> expandDeclarationNames(Arena& arena, const std::vector<Program>& programs) {
    OrderedMap<std::string, std::vector<MacroArm>> macroArms;
    OrderedMap<std::string, std::optional<std::string>> macroParameters;
    for (const Program& program : programs) {
        for (const TopLevel& item : program.items) {
            TopLevelMeta* meta = as<TopLevelMeta>(item);
            if (meta == nullptr || startsWith(meta->name, "__infix__")) continue;
            addArms(macroArms, *meta);
            putIfAbsent(macroParameters, meta->name, meta->parameter);
        }
    }
    if (macroArms.empty()) return programs;
    MacroTable table = tableOf(macroArms, macroParameters);
    Expander expander{arena};
    std::vector<Program> out;
    out.reserve(programs.size());
    for (const Program& program : programs) {
        bool named = std::any_of(program.items.begin(), program.items.end(), declaresNameMacro);
        if (!named) {
            out.push_back(program);
            continue;
        }
        Program c = program;
        c.items.clear();
        for (const TopLevel& item : program.items) {
            if (declaresNameMacro(item)) {
                std::vector<TopLevel> rewritten = expander.rewriteDeclarationName(item, table);
                c.items.insert(c.items.end(), rewritten.begin(), rewritten.end());
            } else {
                c.items.push_back(item);
            }
        }
        out.push_back(std::move(c));
    }
    return out;
}

Program expand(Arena& arena, const Program& program) {
    OrderedMap<std::string, std::vector<MacroArm>> macroArms;
    OrderedMap<std::string, std::optional<std::string>> macroParameters;
    std::vector<TopLevel> nonMacros;
    OrderedSet<std::string> infixOps;
    for (const TopLevel& item : program.items) {
        if (TopLevelMeta* meta = as<TopLevelMeta>(item)) {
            // `meta .Infix("op")` is encoded as a meta named `__infix__op`; it
            // carries no invocable arms, only the infix-operator registration.
            std::string op = removePrefix(meta->name, "__infix__");
            if (op != meta->name) {
                infixOps.add(op);
            } else {
                addArms(macroArms, *meta);
                putIfAbsent(macroParameters, meta->name, meta->parameter);
            }
        } else {
            nonMacros.push_back(item);
        }
    }
    auto withInfix = [&](Program p) {
        if (infixOps.empty()) return p;
        for (const std::string& op : infixOps.items) p.infixOperators.add(op);
        return p;
    };
    // Fast path: no macro declarations, no infix macros, and no `name!` use.
    activeInfix = {};
    for (const InfixMacroRule& rule : program.infixMacros) activeInfix.set(rule.op, rule);
    if (macroArms.empty() && activeInfix.empty() && !program.usesMacros) return withInfix(program);
    MacroTable table = tableOf(macroArms, macroParameters);
    OrderedMap<std::string, std::string> renamedTypes;
    Expander expander{arena};
    std::vector<TopLevel> rewritten;
    for (const TopLevel& item : nonMacros) {
        std::vector<TopLevel> items = expander.rewriteItems(item, table, 0, renamedTypes);
        rewritten.insert(rewritten.end(), items.begin(), items.end());
    }
    Program c = program;
    c.items = std::move(rewritten);
    OrderedSet<std::string> localPackNames;
    for (const std::string& name : program.localPackNames.items) {
        const std::string* renamed = renamedTypes.get(name);
        localPackNames.add(renamed ? *renamed : name);
    }
    c.localPackNames = std::move(localPackNames);
    OrderedMap<std::string, std::string> scopeTypeNamespaces;
    for (const auto& [name, path] : program.scopeTypeNamespaces.entries) {
        const std::string* renamed = renamedTypes.get(name);
        scopeTypeNamespaces.set(renamed ? *renamed : name, path);
    }
    c.scopeTypeNamespaces = std::move(scopeTypeNamespaces);
    return withInfix(std::move(c));
}

}  // namespace MacroExpander
}  // namespace azora
