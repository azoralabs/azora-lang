// Port of semantic/DecoratorMetadata.kt.
#include "semantic/decorator_metadata.h"

#include "base/collections.h"
#include "base/ordered.h"
#include "base/text.h"

#include <functional>
#include <memory>

namespace azora {
namespace DecoratorMetadata {

namespace {

bool allows(const OrderedSet<DecoTarget>& targets, DecoTarget target) { return targets.empty() || targets.contains(target); }

std::optional<std::string> expressionIdentity(const Expr& expr) {
    if (ExprIdentifier* id = as<ExprIdentifier>(expr)) return id->name;
    if (ExprMember* member = as<ExprMember>(expr)) {
        std::optional<std::string> target = expressionIdentity(member->target);
        if (target) return *target + "." + member->name;
    }
    return std::nullopt;
}

bool implementationTargetsSite(const std::string& target, const Site& site) {
    if (target == site.identity) return true;
    if (!endsWith(target, ".*") || site.target != DecoTarget::Field) return false;
    return startsWith(site.identity, removeSuffix(target, ".*") + ".");
}

std::vector<Site> collectSites(const Program& program) {
    std::vector<Site> sites;
    auto addFunction = [&](const std::optional<std::string>& owner, const FuncDecl& function, bool isOperator,
                           const std::vector<Annotation>& enclosing) {
        std::string identity = owner ? *owner + "." + function.name : function.name;
        DecoTarget target;
        if (isOperator) target = DecoTarget::Oper;
        else if (function.isUniversalInfix) target = DecoTarget::Func;
        else if (function.memberCallStyle == MemberCallStyle::PROPERTY) target = DecoTarget::Prop;
        else if (function.name == "ctor") target = DecoTarget::Ctor;
        else if (function.name == "dtor") target = DecoTarget::Dtor;
        else if (function.isTask) target = DecoTarget::AsyncFunc;
        else target = DecoTarget::Func;
        std::vector<Annotation> annotations = enclosing;
        annotations.insert(annotations.end(), function.annotations.begin(), function.annotations.end());
        sites.push_back({identity, target, std::move(annotations)});
        for (const Param& p : function.params) sites.push_back({identity + "." + p.name, DecoTarget::Param, p.annotations});
    };
    for (const TopLevel& item : program.items) {
        switch (item.kind()) {
            case TopLevelKind::Pack: {
                const TopLevelPack& p = cast<TopLevelPack>(item);
                sites.push_back({p.name, DecoTarget::Pack, p.annotations});
                for (const PackField& f : p.fields) sites.push_back({p.name + "." + f.name, DecoTarget::Field, f.annotations});
                break;
            }
            case TopLevelKind::Enum: {
                const TopLevelEnum& e = cast<TopLevelEnum>(item);
                sites.push_back({e.name, DecoTarget::Enum, e.annotations});
                for (size_t i = 0; i < e.variants.size(); i++) {
                    sites.push_back({e.name + "." + e.variants[i], DecoTarget::EnumValue, at(e.variantAnnotations, (int64_t)i)});
                }
                break;
            }
            case TopLevelKind::Fail: {
                const TopLevelFail& f = cast<TopLevelFail>(item);
                sites.push_back({f.name, DecoTarget::Error, f.annotations});
                for (size_t i = 0; i < f.variants.size(); i++) {
                    sites.push_back({f.name + "." + f.variants[i], DecoTarget::ErrorValue, at(f.variantAnnotations, (int64_t)i)});
                }
                break;
            }
            case TopLevelKind::Deco: sites.push_back({cast<TopLevelDeco>(item).name, DecoTarget::Annot, cast<TopLevelDeco>(item).annotations}); break;
            case TopLevelKind::Func: addFunction(std::nullopt, cast<TopLevelFunc>(item).decl, false, {}); break;
            // An operator is a member of its type: its site is named with the type.
            case TopLevelKind::Impl: {
                const TopLevelImpl& impl = cast<TopLevelImpl>(item);
                for (const FuncDecl& method : impl.methods) {
                    bool isOperator = startsWith(method.name, "oper") || method.name == "slice" || method.name == "index" ||
                                      method.name == "indexSet";
                    if (isOperator) addFunction(impl.typeName, method, true, impl.annotations);
                    else addFunction(impl.typeName, method, false, {});
                }
                break;
            }
            case TopLevelKind::Solo: {
                const TopLevelSolo& s = cast<TopLevelSolo>(item);
                sites.push_back({s.name, DecoTarget::Pack, s.annotations});
                for (const PackField& f : s.fields) sites.push_back({s.name + "." + f.name, DecoTarget::Field, f.annotations});
                for (const FuncDecl& m : s.methods) addFunction(s.name, m, false, {});
                break;
            }
            case TopLevelKind::Slot: sites.push_back({cast<TopLevelSlot>(item).name, DecoTarget::VariantEnum, cast<TopLevelSlot>(item).annotations}); break;
            case TopLevelKind::TypeAlias:
                sites.push_back({cast<TopLevelTypeAlias>(item).name, DecoTarget::TypeAlias, cast<TopLevelTypeAlias>(item).annotations});
                break;
            case TopLevelKind::Test: sites.push_back({cast<TopLevelTest>(item).name, DecoTarget::Test, cast<TopLevelTest>(item).annotations}); break;
            case TopLevelKind::Bridge: sites.push_back({cast<TopLevelBridge>(item).target, DecoTarget::Bridge, cast<TopLevelBridge>(item).annotations}); break;
            case TopLevelKind::VarDecl: sites.push_back({cast<TopLevelVarDecl>(item).name, DecoTarget::Var, cast<TopLevelVarDecl>(item).annotations}); break;
            case TopLevelKind::FinDecl: sites.push_back({cast<TopLevelFinDecl>(item).name, DecoTarget::Fin, cast<TopLevelFinDecl>(item).annotations}); break;
            case TopLevelKind::LetDecl: sites.push_back({cast<TopLevelLetDecl>(item).name, DecoTarget::Let, cast<TopLevelLetDecl>(item).annotations}); break;
            default: break;
        }
    }
    return sites;
}

/**
 * What collectSites and the decorator index make of one program, kept for the
 * program they were made from (Kotlin compares the items list by identity;
 * here the items themselves are compared by identity).
 */
struct Index {
    std::vector<TopLevel> items;
    std::unordered_map<std::string, Site> sites;
    std::unordered_map<std::string, TopLevelDeco*> decorators;
    std::vector<TopLevelImpl*> impls;
};

std::unique_ptr<Index> lastIndex;

Index& index(const Program& program) {
    if (lastIndex && lastIndex->items.size() == program.items.size()) {
        bool sameItems = true;
        for (size_t i = 0; i < program.items.size() && sameItems; i++) sameItems = same(lastIndex->items[i], program.items[i]);
        if (sameItems) return *lastIndex;
    }
    auto made = std::make_unique<Index>();
    made->items = program.items;
    for (Site& site : collectSites(program)) made->sites[site.identity] = std::move(site);
    for (const TopLevel& item : program.items) {
        if (TopLevelDeco* deco = as<TopLevelDeco>(item)) made->decorators[deco->name] = deco;
        if (TopLevelImpl* impl = as<TopLevelImpl>(item)) made->impls.push_back(impl);
    }
    lastIndex = std::move(made);
    return *lastIndex;
}

/** A default once its branch has been picked: the value, and whether that branch sealed it. */
struct Chosen {
    Expr value;
    bool isSealed = false;
};

std::optional<std::string> constantKey(const Expr& expr, const std::unordered_map<std::string, Expr>& bindings) {
    switch (expr.kind()) {
        case ExprKind::Grouping: return constantKey(cast<ExprGrouping>(expr).expr, bindings);
        // `@Log(.Error)` keeps its leading dot; a named argument became an InferredMember.
        case ExprKind::Identifier: {
            const std::string& name = cast<ExprIdentifier>(expr).name;
            if (startsWith(name, ".")) return "variant:" + removePrefix(name, ".");
            auto bound = bindings.find(name);
            if (bound == bindings.end()) return std::nullopt;
            return constantKey(bound->second, bindings);
        }
        case ExprKind::InferredMember:
            if (cast<ExprInferredMember>(expr).ctorArgs) return std::nullopt;
            return "variant:" + cast<ExprInferredMember>(expr).name;
        case ExprKind::Member: return "variant:" + cast<ExprMember>(expr).name;
        case ExprKind::IntLiteral: return "int:" + str(cast<ExprIntLiteral>(expr).value);
        case ExprKind::StringLiteral: return "str:" + cast<ExprStringLiteral>(expr).value;
        case ExprKind::BoolLiteral: return std::string("bool:") + (cast<ExprBoolLiteral>(expr).value ? "true" : "false");
        case ExprKind::CharLiteral: return "char:" + str(cast<ExprCharLiteral>(expr).value);
        default: return std::nullopt;
    }
}

/** Whether [condition] holds, or none when it cannot be decided here. */
std::optional<bool> decide(const Expr& condition, const std::unordered_map<std::string, Expr>& bindings) {
    switch (condition.kind()) {
        case ExprKind::Grouping: return decide(cast<ExprGrouping>(condition).expr, bindings);
        case ExprKind::BoolLiteral: return cast<ExprBoolLiteral>(condition).value;
        case ExprKind::Binary: {
            const ExprBinary& b = cast<ExprBinary>(condition);
            if (b.op == TokenType::EQUAL_EQUAL || b.op == TokenType::BANG_EQUAL) {
                std::optional<std::string> left = constantKey(b.left, bindings);
                std::optional<std::string> right = constantKey(b.right, bindings);
                if (!left || !right) return std::nullopt;
                return (*left == *right) == (b.op == TokenType::EQUAL_EQUAL);
            }
            if (b.op == TokenType::OR_OR) {
                std::optional<bool> left = decide(b.left, bindings);
                std::optional<bool> right = decide(b.right, bindings);
                if (left == true || right == true) return true;
                if (left == false && right == false) return false;
                return std::nullopt;
            }
            if (b.op == TokenType::AND_AND) {
                std::optional<bool> left = decide(b.left, bindings);
                std::optional<bool> right = decide(b.right, bindings);
                if (left == false || right == false) return false;
                if (left == true && right == true) return true;
                return std::nullopt;
            }
            return std::nullopt;
        }
        default: return std::nullopt;
    }
}

/** Takes the branch [bindings] select, carrying the seal off the branch that won. */
Chosen choose(const Expr& expr, const std::unordered_map<std::string, Expr>& bindings) {
    switch (expr.kind()) {
        case ExprKind::Seal: return Chosen{choose(cast<ExprSeal>(expr).value, bindings).value, true};
        case ExprKind::Grouping: return choose(cast<ExprGrouping>(expr).expr, bindings);
        case ExprKind::IfExpr: {
            const ExprIfExpr& i = cast<ExprIfExpr>(expr);
            std::optional<bool> decided = decide(i.condition, bindings);
            if (!decided) return Chosen{expr, false};
            return choose(*decided ? i.thenExpr : i.elseExpr, bindings);
        }
        default: return Chosen{expr, false};
    }
}

std::optional<Chosen> chosenDefault(Arena& arena, const Applied& applied, size_t index) {
    const Expr& def = applied.declaration->fields[index].default_;
    if (!def) return std::nullopt;
    std::unordered_map<std::string, Expr> bindings;
    for (size_t earlier = 0; earlier < index; earlier++) {
        const std::string& name = applied.declaration->fields[earlier].name;
        if (Expr value = fieldValue(arena, applied, name)) bindings[name] = value;
    }
    return choose(def, bindings);
}

/** [value] with a variant shorthand spelled out against [type]: `.Center` → `Area.Center`. */
Expr qualified(Arena& arena, const Expr& value, const TypeRef& type) {
    // `.Every(5.seconds)` carries its payload and stays as written.
    if (ExprInferredMember* inferred = as<ExprInferredMember>(value); inferred && inferred->ctorArgs) return value;
    std::optional<std::string> member;
    if (ExprInferredMember* inferred = as<ExprInferredMember>(value)) {
        member = inferred->name;
    } else if (ExprIdentifier* id = as<ExprIdentifier>(value)) {
        if (startsWith(id->name, ".") && u16len(id->name) > 1) member = id->name.substr(1);
    }
    if (!member) return value;
    TypeRefNamed* named = as<TypeRefNamed>(type);
    if (named == nullptr || !named->args.empty()) return value;
    return make(arena, ExprMember{make(arena, ExprIdentifier{named->name, value.line(), value.column(), u16len(named->name)}),
                                  *member, value.line(), value.column(), value.length()});
}

}  // namespace

std::optional<Site> findSite(const Expr& receiver, const std::unordered_map<std::string, std::string>& typeBindings,
                             const Program& program) {
    const std::unordered_map<std::string, Site>& sites = index(program).sites;
    std::optional<std::string> rawIdentity = expressionIdentity(receiver);
    std::optional<std::string> identity = rawIdentity;
    if (rawIdentity && rawIdentity->find("__") != std::string::npos && !sites.count(*rawIdentity)) {
        identity = substringBeforeLast(*rawIdentity, "__") + "." + substringAfterLast(*rawIdentity, "__");
    }
    if (identity) {
        auto found = sites.find(*identity);
        if (found != sites.end()) return found->second;
    }
    if (ExprIdentifier* id = as<ExprIdentifier>(receiver)) {
        auto bound = typeBindings.find(id->name);
        if (bound != typeBindings.end()) {
            auto found = sites.find(bound->second);
            if (found != sites.end()) return found->second;
            return std::nullopt;
        }
    }
    if (ExprCall* call = as<ExprCall>(receiver)) {
        auto found = sites.find(call->callee);
        if (found != sites.end()) return found->second;
    }
    return std::nullopt;
}

std::optional<Applied> findApplied(const Site& site, const std::string& decoratorName, const Program& program) {
    Index& idx = index(program);
    auto requestedAt = idx.decorators.find(decoratorName);
    if (requestedAt == idx.decorators.end()) return std::nullopt;
    TopLevelDeco* requested = requestedAt->second;
    if (!allows(requested->targets, site.target)) return std::nullopt;

    std::function<bool(const std::string&, OrderedSet<std::string>&)> reaches = [&](const std::string& currentName,
                                                                                    OrderedSet<std::string>& seen) {
        if (currentName == decoratorName) return true;
        if (!seen.add(currentName)) return false;
        auto current = idx.decorators.find(currentName);
        if (current == idx.decorators.end()) return false;
        if (!allows(current->second->targets, site.target)) return false;
        for (const DecoratorBinding& binding : current->second->bindings) {
            if (allows(binding.targets, site.target) && idx.decorators.count(binding.name) && reaches(binding.name, seen)) {
                return true;
            }
        }
        return false;
    };

    struct RootApplication {
        std::string name;
        std::optional<Annotation> annotation;
    };
    std::vector<RootApplication> applications;
    for (const Annotation& a : site.annotations) applications.push_back({a.name, a});
    for (TopLevelImpl* impl : idx.impls) {
        if (!implementationTargetsSite(impl->typeName, site) || !impl->methods.empty()) continue;
        if (!impl->traitName || !idx.decorators.count(*impl->traitName)) continue;
        Annotation annotation;
        annotation.name = *impl->traitName;
        annotation.args = impl->decoratorArgs;
        annotation.line = impl->line;
        annotation.column = impl->column;
        annotation.namedArgs = impl->decoratorNamedArgs;
        applications.push_back({*impl->traitName, std::move(annotation)});
    }
    for (const RootApplication& application : applications) {
        OrderedSet<std::string> seen;
        if (!reaches(application.name, seen)) continue;
        Applied applied;
        applied.declaration = requested;
        if (application.name == decoratorName) applied.directApplication = application.annotation;
        return applied;
    }
    return std::nullopt;
}

Expr fieldValue(Arena& arena, const Applied& applied, const std::string& fieldName) {
    const std::vector<PackField>& fields = applied.declaration->fields;
    size_t index = fields.size();
    for (size_t i = 0; i < fields.size(); i++) {
        if (fields[i].name == fieldName) {
            index = i;
            break;
        }
    }
    if (index == fields.size()) return nullptr;
    Expr value;
    if (applied.directApplication) {
        for (const auto& [name, v] : applied.directApplication->namedArgs) {
            if (name == fieldName) {
                value = v;
                break;
            }
        }
        if (!value && index < applied.directApplication->args.size()) value = applied.directApplication->args[index];
    }
    if (!value) {
        if (std::optional<Chosen> chosen = chosenDefault(arena, applied, index)) value = chosen->value;
    }
    if (!value) return nullptr;
    return qualified(arena, value, fields[index].type);
}

bool isFieldSealed(Arena& arena, const Applied& applied, const std::string& fieldName) {
    const std::vector<PackField>& fields = applied.declaration->fields;
    for (size_t i = 0; i < fields.size(); i++) {
        if (fields[i].name != fieldName) continue;
        std::optional<Chosen> chosen = chosenDefault(arena, applied, i);
        return chosen && chosen->isSealed;
    }
    return false;
}

}  // namespace DecoratorMetadata
}  // namespace azora
