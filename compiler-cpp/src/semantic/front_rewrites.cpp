// Ports of ScopeQualifiedImplTargets.kt, SpecDefaults.kt, CastDeriver.kt and DisplayDeriver.kt.
#include "semantic/front_rewrites.h"

#include "base/ordered.h"
#include "base/text.h"

#include <unordered_map>

namespace azora {

// ---------------------------------------------------------------------------
// ScopeQualifiedImplTargets
// ---------------------------------------------------------------------------

namespace ScopeQualifiedImplTargets {

namespace {

std::optional<std::string> declaredTypeName(const TopLevel& item) {
    switch (item.kind()) {
        case TopLevelKind::Pack: return cast<TopLevelPack>(item).name;
        case TopLevelKind::Enum: return cast<TopLevelEnum>(item).name;
        case TopLevelKind::Fail: return cast<TopLevelFail>(item).name;
        case TopLevelKind::Slot: return cast<TopLevelSlot>(item).name;
        case TopLevelKind::Solo: return cast<TopLevelSolo>(item).name;
        case TopLevelKind::TypeAlias: return cast<TopLevelTypeAlias>(item).name;
        default: return std::nullopt;
    }
}

/** The type `target` names when it is a scope-qualified type; none for anything else. */
std::optional<std::string> scopeQualifiedType(const std::string& target, const OrderedSet<std::string>& declaredTypes,
                                              const std::unordered_map<std::string, OrderedSet<std::string>>& packFields) {
    size_t dot = target.find('.');
    if (dot == std::string::npos || dot == 0 || dot != target.rfind('.')) return std::nullopt;
    std::string owner = target.substr(0, dot);
    std::string member = target.substr(dot + 1);
    if (member == "*" || !declaredTypes.contains(member)) return std::nullopt;
    auto fields = packFields.find(owner);
    if (fields != packFields.end() && fields->second.contains(member)) return std::nullopt;
    return member;
}

}  // namespace

Program resolve(Arena& arena, const Program& program) {
    OrderedSet<std::string> declaredTypes;
    for (const TopLevel& item : program.items) {
        if (std::optional<std::string> name = declaredTypeName(item)) declaredTypes.add(*name);
    }
    if (declaredTypes.empty()) return program;
    // Kotlin `associate`: a later pack of the same name replaces an earlier one.
    std::unordered_map<std::string, OrderedSet<std::string>> packFields;
    for (const TopLevel& item : program.items) {
        if (TopLevelPack* pack = as<TopLevelPack>(item)) {
            OrderedSet<std::string> fields;
            for (const PackField& f : pack->fields) fields.add(f.name);
            packFields[pack->name] = std::move(fields);
        }
    }
    bool rewrote = false;
    std::vector<TopLevel> items;
    items.reserve(program.items.size());
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        std::optional<std::string> resolved = impl ? scopeQualifiedType(impl->typeName, declaredTypes, packFields) : std::nullopt;
        if (!resolved) {
            items.push_back(item);
            continue;
        }
        rewrote = true;
        TopLevelImpl c = *impl;
        c.typeName = *resolved;
        items.push_back(make(arena, std::move(c)));
    }
    if (!rewrote) return program;
    Program out = program;
    out.items = std::move(items);
    return out;
}

}  // namespace ScopeQualifiedImplTargets

// ---------------------------------------------------------------------------
// SpecDefaults
// ---------------------------------------------------------------------------

namespace SpecDefaults {

namespace {

using Bindings = OrderedMap<std::string, TypeRef>;

struct Binder {
    Arena& arena;
    const Bindings& bindings;

    TypeRef bind(const TypeRef& ref) {
        switch (ref.kind()) {
            case TypeRefKind::Named: {
                const TypeRefNamed& named = cast<TypeRefNamed>(ref);
                if (const TypeRef* bound = bindings.get(named.name)) return *bound;
                TypeRefNamed c = named;
                for (TypeRef& a : c.args) a = bind(a);
                return make(arena, std::move(c));
            }
            case TypeRefKind::Array: {
                TypeRefArray c = cast<TypeRefArray>(ref);
                c.element = bind(c.element);
                return make(arena, std::move(c));
            }
            case TypeRefKind::Nullable: {
                TypeRefNullable c = cast<TypeRefNullable>(ref);
                c.inner = bind(c.inner);
                return make(arena, std::move(c));
            }
            case TypeRefKind::Reference: {
                TypeRefReference c = cast<TypeRefReference>(ref);
                c.inner = bind(c.inner);
                return make(arena, std::move(c));
            }
            // A callable's own types are as much the member's signature as its parameters are.
            case TypeRefKind::Function: {
                TypeRefFunction c = cast<TypeRefFunction>(ref);
                for (TypeRef& p : c.params) p = bind(p);
                c.ret = bind(c.ret);
                for (TypeRef& r : c.receivers) r = bind(r);
                return make(arena, std::move(c));
            }
            case TypeRefKind::Failable: {
                TypeRefFailable c = cast<TypeRefFailable>(ref);
                c.ok = bind(c.ok);
                return make(arena, std::move(c));
            }
            case TypeRefKind::Pointer: {
                TypeRefPointer c = cast<TypeRefPointer>(ref);
                c.inner = bind(c.inner);
                return make(arena, std::move(c));
            }
            default:
                return ref;
        }
    }

    TypeAnnotation bindAnnotation(const TypeAnnotation& annotation) {
        return annotation.isExplicit() ? TypeAnnotation{bind(annotation.ref)} : annotation;
    }

    // A provided body names types too, so it is bound on the same terms as the signature.
    Expr bindExpr(const Expr& expr) {
        switch (expr.kind()) {
            case ExprKind::Call: {
                ExprCall c = cast<ExprCall>(expr);
                for (Expr& a : c.args) a = bindExpr(a);
                for (TypeRef& t : c.typeArgs) t = bind(t);
                if (c.receiver) c.receiver = bindExpr(c.receiver);
                return make(arena, std::move(c));
            }
            case ExprKind::MethodCall: {
                ExprMethodCall c = cast<ExprMethodCall>(expr);
                c.target = bindExpr(c.target);
                for (Expr& a : c.args) a = bindExpr(a);
                return make(arena, std::move(c));
            }
            case ExprKind::Member: {
                ExprMember c = cast<ExprMember>(expr);
                c.target = bindExpr(c.target);
                return make(arena, std::move(c));
            }
            case ExprKind::Binary: {
                ExprBinary c = cast<ExprBinary>(expr);
                c.left = bindExpr(c.left);
                c.right = bindExpr(c.right);
                return make(arena, std::move(c));
            }
            case ExprKind::Unary: {
                ExprUnary c = cast<ExprUnary>(expr);
                c.operand = bindExpr(c.operand);
                return make(arena, std::move(c));
            }
            case ExprKind::IncDec: {
                ExprIncDec c = cast<ExprIncDec>(expr);
                c.target = bindExpr(c.target);
                return make(arena, std::move(c));
            }
            case ExprKind::Grouping: {
                ExprGrouping c = cast<ExprGrouping>(expr);
                c.expr = bindExpr(c.expr);
                return make(arena, std::move(c));
            }
            case ExprKind::NamedArg: {
                ExprNamedArg c = cast<ExprNamedArg>(expr);
                c.value = bindExpr(c.value);
                return make(arena, std::move(c));
            }
            default:
                return expr;
        }
    }

    Stmt bindStmt(const Stmt& stmt) {
        switch (stmt.kind()) {
            case StmtKind::FinDecl: {
                StmtFinDecl c = cast<StmtFinDecl>(stmt);
                c.type = bindAnnotation(c.type);
                c.initializer = bindExpr(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::VarDecl: {
                StmtVarDecl c = cast<StmtVarDecl>(stmt);
                c.type = bindAnnotation(c.type);
                c.initializer = bindExpr(c.initializer);
                return make(arena, std::move(c));
            }
            case StmtKind::ExprStmt: {
                StmtExprStmt c = cast<StmtExprStmt>(stmt);
                c.expr = bindExpr(c.expr);
                return make(arena, std::move(c));
            }
            case StmtKind::Return: {
                StmtReturn c = cast<StmtReturn>(stmt);
                if (c.value) c.value = bindExpr(c.value);
                return make(arena, std::move(c));
            }
            default:
                return stmt;
        }
    }
};

/** An inherited member with the implementation's associated types filled in. */
FuncDecl bindAssoc(Arena& arena, const FuncDecl& method, const Bindings& bindings) {
    if (bindings.empty()) return method;
    Binder binder{arena, bindings};
    FuncDecl c = method;
    for (Param& p : c.params) p.type = binder.bind(p.type);
    c.returnType = binder.bindAnnotation(c.returnType);
    for (Stmt& s : c.body) s = binder.bindStmt(s);
    return c;
}

const TopLevelSpec* specFor(const std::unordered_map<std::string, const TopLevelSpec*>& specs, const TopLevelImpl& impl) {
    if (!impl.traitName) return nullptr;
    auto found = specs.find(*impl.traitName);
    if (found != specs.end()) return found->second;
    found = specs.find(substringAfterLast(*impl.traitName, "__"));
    return found != specs.end() ? found->second : nullptr;
}

/** `Self` is the implementing type; a spec parameter is the impl's argument or its default; then associated types. */
Bindings bindingsFor(Arena& arena, const TopLevelSpec& spec, const TopLevelImpl& impl) {
    TypeRef self = make(arena, TypeRefNamed{impl.typeName});
    Bindings out;
    for (size_t index = 0; index < spec.typeParams.size(); index++) {
        const std::string& param = spec.typeParams[index];
        TypeRef ref;
        if (index < impl.traitArgs.size()) {
            ref = impl.traitArgs[index];
        } else if (const TypeRef* def = spec.typeDefaults.get(param)) {
            ref = *def;
        } else {
            continue;
        }
        TypeRefNamed* named = as<TypeRefNamed>(ref);
        out.set(param, (named && named->name == "Self") ? self : ref);
    }
    for (const auto& [name, bound] : impl.assocBindings.entries) out.set(name, bound);
    out.set("Self", self);
    return out;
}

/**
 * An implementation's member that writes no return type takes the one its spec
 * member declares, as this implementation sees it.
 */
Program adoptReturnTypes(Arena& arena, const Program& program) {
    std::unordered_map<std::string, const TopLevelSpec*> specs;
    for (const TopLevel& item : program.items) {
        TopLevelSpec* spec = as<TopLevelSpec>(item);
        if (spec == nullptr) continue;
        bool anyExplicit = false;
        for (const FuncDecl& m : spec->methods) anyExplicit = anyExplicit || m.returnType.isExplicit();
        if (!anyExplicit) continue;
        specs[spec->name] = spec;
        specs[substringAfterLast(spec->name, "__")] = spec;
    }
    if (specs.empty()) return program;
    bool changed = false;
    std::vector<TopLevel> items;
    items.reserve(program.items.size());
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        const TopLevelSpec* spec = impl ? specFor(specs, *impl) : nullptr;
        if (spec == nullptr) {
            items.push_back(item);
            continue;
        }
        Bindings bindings = bindingsFor(arena, *spec, *impl);
        bool methodsChanged = false;
        std::vector<FuncDecl> methods;
        for (const FuncDecl& method : impl->methods) {
            if (!method.returnType.isInferred()) {
                methods.push_back(method);
                continue;
            }
            // An operator's name carries its operand (`oper<=>@Version`); the spec's member is the operator itself.
            const FuncDecl* requirement = nullptr;
            for (const FuncDecl& m : spec->methods) {
                if (substringBefore(m.name, "@") == substringBefore(method.name, "@") &&
                    m.params.size() == method.params.size() && m.returnType.isExplicit()) {
                    requirement = &m;
                    break;
                }
            }
            if (requirement == nullptr) {
                methods.push_back(method);
                continue;
            }
            changed = true;
            methodsChanged = true;
            FuncDecl bare = *requirement;
            bare.params = {};
            bare.body = {};
            FuncDecl c = method;
            c.returnType = bindAssoc(arena, bare, bindings).returnType;
            methods.push_back(std::move(c));
        }
        if (!methodsChanged) {
            items.push_back(item);
            continue;
        }
        TopLevelImpl c = *impl;
        c.methods = std::move(methods);
        items.push_back(make(arena, std::move(c)));
    }
    if (!changed) return program;
    Program out = program;
    out.items = std::move(items);
    return out;
}

Program provideMembers(Arena& arena, const Program& program) {
    std::vector<const TopLevelSpec*> declared;
    for (const TopLevel& item : program.items) {
        TopLevelSpec* spec = as<TopLevelSpec>(item);
        if (spec == nullptr) continue;
        for (const FuncDecl& m : spec->methods) {
            if (!m.body.empty()) {
                declared.push_back(spec);
                break;
            }
        }
    }
    if (declared.empty()) return program;
    // Keyed by the plain name as well as the mangled one.
    std::unordered_map<std::string, const TopLevelSpec*> specs;
    for (const TopLevelSpec* spec : declared) {
        specs[spec->name] = spec;
        specs[substringAfterLast(spec->name, "__")] = spec;
    }
    Program out = program;
    out.items.clear();
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        const TopLevelSpec* spec = impl ? specFor(specs, *impl) : nullptr;
        if (spec == nullptr) {
            out.items.push_back(item);
            continue;
        }
        OrderedSet<std::string> own;
        for (const FuncDecl& m : impl->methods) own.add(m.name);
        Bindings bindings = bindingsFor(arena, *spec, *impl);
        std::vector<FuncDecl> inherited;
        for (const FuncDecl& m : spec->methods) {
            if (!m.body.empty() && !own.contains(m.name)) inherited.push_back(bindAssoc(arena, m, bindings));
        }
        if (inherited.empty()) {
            out.items.push_back(item);
            continue;
        }
        TopLevelImpl c = *impl;
        c.methods.insert(c.methods.end(), inherited.begin(), inherited.end());
        out.items.push_back(make(arena, std::move(c)));
    }
    return out;
}

}  // namespace

Program apply(Arena& arena, const Program& program) { return provideMembers(arena, adoptReturnTypes(arena, program)); }

}  // namespace SpecDefaults

// ---------------------------------------------------------------------------
// CastDeriver
// ---------------------------------------------------------------------------

namespace CastDeriver {

namespace {

const char* castOperator(const std::optional<std::string>& trait) {
    if (!trait) return nullptr;
    if (*trait == "Cast") return "operas";
    if (*trait == "CheckedCast") return "operas?";
    if (*trait == "BitCast") return "operas*";
    return nullptr;
}

}  // namespace

Program rewrite(Arena& arena, const Program& program) {
    bool any = false;
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        if (impl && castOperator(impl->traitName)) {
            any = true;
            break;
        }
    }
    if (!any) return program;
    Program out = program;
    out.items.clear();
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        const char* op = impl ? castOperator(impl->traitName) : nullptr;
        TypeRefNamed* target = (op && !impl->traitArgs.empty()) ? as<TypeRefNamed>(impl->traitArgs[0]) : nullptr;
        if (target == nullptr) {
            out.items.push_back(item);
            continue;
        }
        TopLevelImpl c = *impl;
        for (FuncDecl& m : c.methods) m.name = std::string(op) + "@" + target->name;
        out.items.push_back(make(arena, std::move(c)));
    }
    return out;
}

}  // namespace CastDeriver

// ---------------------------------------------------------------------------
// DisplayDeriver
// ---------------------------------------------------------------------------

namespace DisplayDeriver {

namespace {

TopLevel renderImpl(Arena& arena, const std::string& typeName, const TopLevelPack& pack) {
    int32_t line = pack.line;
    Expr formatter = make(arena, ExprIdentifier{"__formatter", line, 0, 11});
    std::vector<Stmt> body;
    // `var __formatter = Formatter()`
    body.push_back(make(arena, StmtVarDecl{"__formatter", TypeAnnotation{make(arena, TypeRefNamed{"Formatter"})},
                                           make(arena, ExprCall{"Formatter", {}, line, 0}), line, 0}));
    // `self.display(__formatter)`
    body.push_back(make(
        arena,
        StmtExprStmt{make(arena, ExprMethodCall{make(arena, ExprIdentifier{"self", line, 0, 4}), "display", {formatter}, line, 0}),
                     line, 0}));
    // `return __formatter.rendered`
    body.push_back(make(arena, StmtReturn{make(arena, ExprMember{formatter, "rendered", line, 0}), line, 0}));
    FuncDecl member;
    member.name = MEMBER;
    member.returnType = TypeAnnotation{make(arena, TypeRefNamed{"String"})};
    member.body = std::move(body);
    member.isInline = false;
    member.line = line;
    member.column = 0;
    member.receiverModifier = ParamModifier::SHARED;
    member.receiverName = "self";
    member.memberCallStyle = MemberCallStyle::PROPERTY;
    TopLevelImpl impl;
    impl.typeName = typeName;
    impl.methods = {std::move(member)};
    impl.line = line;
    impl.column = 0;
    impl.typeParams = pack.typeParams;
    implDefaults(impl);
    return make(arena, std::move(impl));
}

}  // namespace

Program derive(Arena& arena, const Program& program) {
    // A type that implements `Display`, whatever else it implements.
    OrderedSet<std::string> implementors;
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        if (impl && impl->traitName && *impl->traitName == "Display") implementors.add(impl->typeName);
    }
    if (implementors.empty()) return program;
    // Never twice, and never over one the author wrote.
    OrderedSet<std::string> existing;
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        if (impl == nullptr) continue;
        for (const FuncDecl& m : impl->methods) {
            if (m.name == MEMBER) {
                existing.add(impl->typeName);
                break;
            }
        }
    }
    // Kotlin `associateBy`: the last pack of a name wins.
    std::unordered_map<std::string, const TopLevelPack*> packs;
    for (const TopLevel& item : program.items) {
        if (TopLevelPack* pack = as<TopLevelPack>(item)) packs[pack->name] = pack;
    }
    std::vector<TopLevel> generated;
    for (const std::string& typeName : implementors.items) {
        if (existing.contains(typeName)) continue;
        // A bridge pack renders as the backend prints it.
        auto pack = packs.find(typeName);
        if (pack == packs.end() || pack->second->isBridge) continue;
        generated.push_back(renderImpl(arena, typeName, *pack->second));
    }
    if (generated.empty()) return program;
    Program out = program;
    out.items.insert(out.items.end(), generated.begin(), generated.end());
    return out;
}

}  // namespace DisplayDeriver

}  // namespace azora
