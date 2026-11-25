// Port of semantic/ComparisonDeriver.kt.
#include "semantic/comparison_deriver.h"

#include "base/ordered.h"
#include "base/text.h"
#include "frontend/ast_util.h"

#include <algorithm>
#include <functional>
#include <unordered_map>

namespace azora {
namespace ComparisonDeriver {

namespace {

const char* const PARTIAL_EQUAL = "PartialEqual";
const char* const EQUAL = "Equal";
const char* const ORDER = "Order";
const char* const HASH = "Hash";

using Caps = OrderedSet<std::string>;

struct Builder {
    Arena& arena;

    Expr self(int32_t line) { return make(arena, ExprIdentifier{"self", line, 0, 4}); }
    Expr rhs(int32_t line) { return make(arena, ExprIdentifier{"rhs", line, 0, 3}); }
    Expr field(const Expr& target, const std::string& name, int32_t line) {
        return make(arena, ExprMember{target, name, line, 0});
    }
    Expr binary(const Expr& left, TokenType op, const Expr& right, int32_t line) {
        return make(arena, ExprBinary{left, op, right, line});
    }

    TypeRef selfRef(const TopLevelPack& pack) {
        std::vector<TypeRef> args;
        for (const std::string& t : pack.typeParams) args.push_back(make(arena, TypeRefNamed{t}));
        TypeRefNamed named{pack.name};
        named.args = std::move(args);
        return make(arena, std::move(named));
    }

    FuncDecl member(const std::string& name, std::vector<Param> params, const char* returns, std::vector<Stmt> body,
                    int32_t line) {
        FuncDecl decl;
        decl.name = name;
        decl.params = std::move(params);
        decl.returnType = TypeAnnotation{make(arena, TypeRefNamed{returns})};
        decl.body = std::move(body);
        decl.isInline = false;
        decl.line = line;
        decl.column = 0;
        decl.receiverModifier = ParamModifier::SHARED;
        decl.receiverName = "self";
        return decl;
    }

    Param rhsParam(const TopLevelPack& pack) {
        Param p;
        p.name = "rhs";
        p.type = selfRef(pack);
        p.modifier = ParamModifier::SHARED;
        return p;
    }

    TopLevel implOf(const TopLevelPack& pack, FuncDecl method) {
        TopLevelImpl impl;
        impl.typeName = pack.name;
        impl.methods = {std::move(method)};
        impl.line = pack.line;
        impl.column = 0;
        impl.typeParams = pack.typeParams;
        impl.isDerived = true;
        impl.hasBody = false;
        implDefaults(impl);
        return make(arena, std::move(impl));
    }

    TopLevel conformance(const TopLevelPack& pack, const std::string& typeName, const char* trait) {
        TopLevelImpl impl;
        impl.typeName = typeName;
        impl.traitName = trait;
        impl.line = pack.line;
        impl.column = 0;
        impl.typeParams = pack.typeParams;
        impl.isDerived = true;
        impl.hasBody = false;
        implDefaults(impl);
        return make(arena, std::move(impl));
    }

    /** `oper== P&.(rhs: P&): Bool { return self.a == rhs.a && … }` */
    TopLevel equalsImpl(const TopLevelPack& pack) {
        int32_t line = pack.line;
        Expr comparison;
        for (const PackField& f : pack.fields) {
            Expr next = binary(field(self(line), f.name, line), TokenType::EQUAL_EQUAL, field(rhs(line), f.name, line), line);
            comparison = comparison ? binary(comparison, TokenType::AND_AND, next, line) : next;
        }
        std::vector<Stmt> body{make(arena, StmtReturn{comparison, line, 0})};
        return implOf(pack, member("oper==", {rhsParam(pack)}, "Bool", std::move(body), line));
    }

    Stmt ifReturn(const Expr& condition, const Expr& value, int32_t line) {
        return make(arena, StmtIf{condition, {make(arena, StmtReturn{value, line, 0})}, std::nullopt, line, 0});
    }

    Expr compareVariant(const char* name, int32_t line) {
        return make(arena, ExprMember{make(arena, ExprIdentifier{"Compare", line, 0, 7}), name, line, 0});
    }

    /** Lexicographic `<=>`, in declaration order, returning at the first field that is not equal. */
    TopLevel orderImpl(const TopLevelPack& pack) {
        int32_t line = pack.line;
        std::vector<Stmt> body;
        for (const PackField& f : pack.fields) {
            Expr mine = field(self(line), f.name, line);
            Expr theirs = field(rhs(line), f.name, line);
            body.push_back(ifReturn(binary(mine, TokenType::LESS, theirs, line), compareVariant("Less", line), line));
            body.push_back(ifReturn(binary(theirs, TokenType::LESS, mine, line), compareVariant("Greater", line), line));
        }
        body.push_back(make(arena, StmtReturn{compareVariant("Equal", line), line, 0}));
        return implOf(pack, member("oper<=>", {rhsParam(pack)}, "Compare", std::move(body), line));
    }

    /** `prop hash[self: P&]: ULong` - the field-wise hash, mixed so that field order matters. */
    TopLevel hashImpl(const TopLevelPack& pack) {
        int32_t line = pack.line;
        Expr acc = make(arena, ExprIntLiteral{17, line, 0});
        for (const PackField& f : pack.fields) {
            Expr scaled = binary(acc, TokenType::STAR, make(arena, ExprIntLiteral{31, line, 0}), line);
            acc = binary(scaled, TokenType::PLUS, field(field(self(line), f.name, line), "hash", line), line);
        }
        std::vector<Stmt> body{make(arena, StmtReturn{acc, line, 0})};
        FuncDecl decl = member("hash", {}, "ULong", std::move(body), line);
        decl.memberCallStyle = MemberCallStyle::PROPERTY;
        return implOf(pack, std::move(decl));
    }
};

/**
 * §7.2: a field-wise `Equal`, `Order` or `Hash` holds only when every field's
 * type has it. Returns the first refusal, naming the field.
 */
std::optional<std::string> refusal(const TopLevelPack& pack, const Caps& caps,
                                   const OrderedMap<std::string, Caps>& requested, const OrderedMap<std::string, Caps>& stated,
                                   const OrderedSet<std::string>& programPacks, const OrderedSet<std::string>& enums,
                                   const std::function<int32_t(const std::string&)>& lineOf) {
    // true / false / unknown (nullopt)
    auto has = [&](const std::string& name, const std::string& cap) -> std::optional<bool> {
        if (std::find(pack.typeParams.begin(), pack.typeParams.end(), name) != pack.typeParams.end()) return std::nullopt;
        // A payload-free enum derives `Equal` and `Order` by position; whether it hashes is not decided here.
        if (enums.contains(name)) return cap == HASH ? std::nullopt : std::optional<bool>(true);
        Caps own;
        if (const Caps* s = stated.get(name)) for (const std::string& c : s->items) own.add(c);
        if (const Caps* r = requested.get(name)) for (const std::string& c : r->items) own.add(c);
        // A total order is an equivalence too.
        if (own.contains(cap) || (cap == EQUAL && own.contains(ORDER))) return true;
        if (!own.empty() || programPacks.contains(name)) return false;
        return std::nullopt;
    };
    std::function<std::optional<bool>(const TypeRef&, const std::string&)> provides =
        [&](const TypeRef& ref, const std::string& cap) -> std::optional<bool> {
        switch (ref.kind()) {
            case TypeRefKind::Named: return has(cast<TypeRefNamed>(ref).name, cap);
            case TypeRefKind::Nullable: return provides(cast<TypeRefNullable>(ref).inner, cap);
            // A tuple and an array compare and hash part by part, but have no order.
            case TypeRefKind::Array:
                if (cap == ORDER) return false;
                return provides(cast<TypeRefArray>(ref).element, cap);
            case TypeRefKind::Tuple: {
                if (cap == ORDER) return false;
                bool anyFalse = false;
                bool anyNull = false;
                for (const TypeRef& e : cast<TypeRefTuple>(ref).elements) {
                    std::optional<bool> p = provides(e, cap);
                    if (!p) anyNull = true;
                    else if (!*p) anyFalse = true;
                }
                if (anyFalse) return false;
                if (anyNull) return std::nullopt;
                return true;
            }
            case TypeRefKind::Function: return false;
            default: return std::nullopt;
        }
    };
    for (const char* cap : {ORDER, EQUAL, HASH}) {
        if (!caps.contains(cap)) continue;
        for (const PackField& f : pack.fields) {
            std::optional<bool> provided = provides(f.type, cap);
            if (!provided || *provided) continue;
            std::string why;
            TypeRefNamed* named = as<TypeRefNamed>(f.type);
            if (named && (named->name == "Double" || named->name == "Float" || named->name == "Quad" || named->name == "Half")) {
                std::string type = toString(f.type);
                if (std::string(cap) == ORDER) why = " - " + type + " is PartialOrder: NaN does not relate to anything";
                else if (std::string(cap) == EQUAL) why = " - " + type + " is PartialEqual: NaN is not equal to itself";
                else why = " - " + type + " is not Hash: equal values must hash equally, and NaN is not equal to itself";
            }
            return "line " + str(lineOf(cap)) + ": cannot derive '" + cap + "' for '" + pack.name + "': field '" + f.name +
                   ": " + toString(f.type) + "' is not " + cap + why + "; write 'impl " + cap + " for " + pack.name +
                   "' to define it yourself";
        }
    }
    return std::nullopt;
}

}  // namespace

Result derive(Arena& arena, const Program& program) {
    // Kotlin `associateBy`: the last pack of a name wins.
    std::unordered_map<std::string, const TopLevelPack*> packs;
    OrderedSet<std::string> packNames;
    for (const TopLevel& item : program.items) {
        TopLevelPack* pack = as<TopLevelPack>(item);
        if (pack == nullptr || pack->isBridge || pack->isUnion) continue;
        packs[pack->name] = pack;
        packNames.add(pack->name);
    }
    if (packs.empty()) return Result{program, {}};

    // Only nodes explicitly produced by `derive` / `derives` are requests.
    OrderedMap<std::string, Caps> requested;
    OrderedMap<std::string, Caps> written;
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        if (impl == nullptr || !impl->traitName) continue;
        const std::string& capability = *impl->traitName;
        if (capability != EQUAL && capability != ORDER && capability != HASH) continue;
        if (!packs.count(impl->typeName)) continue;
        if (impl->isDerived) requested.getOrPut(impl->typeName, {}).add(capability);
        else written.getOrPut(impl->typeName, {}).add(capability);
    }
    // `Equal` brings `Hash` (one contract); `Order` brings both.
    for (auto& [type, caps] : requested.entries) {
        if (caps.contains(EQUAL)) caps.add(HASH);
        if (caps.contains(ORDER)) {
            caps.add(EQUAL);
            caps.add(HASH);
        }
    }
    // Every conformance already stated.
    OrderedMap<std::string, Caps> stated;
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        if (impl && impl->traitName) stated.getOrPut(impl->typeName, {}).add(*impl->traitName);
    }
    std::vector<std::string> errors;
    OrderedSet<std::string> enums;
    for (const TopLevel& item : program.items) {
        if (TopLevelEnum* e = as<TopLevelEnum>(item)) enums.add(e->name);
    }
    // (typeName, traitName) → the request's line; a later request replaces an earlier one.
    std::unordered_map<std::string, int32_t> requestLines;
    auto requestKey = [](const std::string& type, const std::optional<std::string>& trait) {
        return type + '\x1f' + (trait ? "1" + *trait : std::string("0"));
    };
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        if (impl && impl->isDerived && packs.count(impl->typeName)) requestLines[requestKey(impl->typeName, impl->traitName)] = impl->line;
    }
    Builder builder{arena};
    std::vector<TopLevel> generated;
    const Caps none;
    for (const auto& [typeName, caps] : requested.entries) {
        const TopLevelPack& pack = *packs.at(typeName);
        if (pack.fields.empty()) continue;
        const Caps* writtenCaps = written.get(typeName);
        Caps missing;
        for (const std::string& c : caps.items) {
            if (!writtenCaps || !writtenCaps->contains(c)) missing.add(c);
        }
        auto lineOf = [&](const std::string& cap) -> int32_t {
            for (const std::string& key : {requestKey(typeName, cap), requestKey(typeName, std::string(ORDER)),
                                           requestKey(typeName, std::string(EQUAL))}) {
                auto found = requestLines.find(key);
                if (found != requestLines.end()) return found->second;
            }
            return pack.line;
        };
        std::optional<std::string> refused = refusal(pack, missing, requested, stated, packNames, enums, lineOf);
        if (refused) {
            errors.push_back(*refused);
            continue;
        }
        std::vector<std::string> sorted = caps.items;
        std::sort(sorted.begin(), sorted.end());
        for (const std::string& capability : sorted) {
            if (writtenCaps && writtenCaps->contains(capability)) continue;
            if (capability == EQUAL) generated.push_back(builder.equalsImpl(pack));
            else if (capability == ORDER) generated.push_back(builder.orderImpl(pack));
            else if (capability == HASH) generated.push_back(builder.hashImpl(pack));
        }
        // The generated `oper==` is `PartialEqual`, so the request satisfies the chain it sits on.
        const Caps* statedCaps = stated.get(typeName);
        const Caps& statedHere = statedCaps ? *statedCaps : none;
        if (!statedHere.contains(PARTIAL_EQUAL)) generated.push_back(builder.conformance(pack, typeName, PARTIAL_EQUAL));
        for (const char* implied : {EQUAL, HASH}) {
            if (caps.contains(implied) && !statedHere.contains(implied)) {
                generated.push_back(builder.conformance(pack, typeName, implied));
            }
        }
    }
    if (generated.empty()) return Result{program, std::move(errors)};
    Program out = program;
    out.items.insert(out.items.end(), generated.begin(), generated.end());
    return Result{std::move(out), std::move(errors)};
}

}  // namespace ComparisonDeriver
}  // namespace azora
