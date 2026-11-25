// Ports of ReturnedVariants.kt, Witnesses.kt and InferredTypeArgs.kt.
#include "semantic/program_passes.h"

#include "base/text.h"
#include "frontend/ast_rewriter.h"

#include <algorithm>
#include <functional>
#include <unordered_map>
#include <unordered_set>

namespace azora {

// ---------------------------------------------------------------------------
// ReturnedVariants
// ---------------------------------------------------------------------------

namespace ReturnedVariants {

namespace {

using VariantSets = std::unordered_map<std::string, OrderedSet<std::string>>;

struct Context {
    Arena& arena;
    const std::vector<std::string>& sets;
    std::optional<std::string> ok;
    const VariantSets& errorVariants;
    const OrderedSet<std::string>& okVariants;
    std::vector<std::string>& errors;

    std::vector<Stmt> body(const std::vector<Stmt>& stmts) {
        std::vector<Stmt> out;
        out.reserve(stmts.size());
        for (const Stmt& s : stmts) out.push_back(stmt(s));
        return out;
    }

    Stmt stmt(const Stmt& s) {
        switch (s.kind()) {
            case StmtKind::Throw: return cast<StmtThrow>(s).returned ? returned(cast<StmtThrow>(s), s) : s;
            case StmtKind::If: {
                StmtIf c = cast<StmtIf>(s);
                c.thenBranch = body(c.thenBranch);
                if (c.elseBranch) c.elseBranch = body(*c.elseBranch);
                return make(arena, std::move(c));
            }
            case StmtKind::While: {
                StmtWhile c = cast<StmtWhile>(s);
                c.body = body(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::For: {
                StmtFor c = cast<StmtFor>(s);
                c.body = body(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::Loop: {
                StmtLoop c = cast<StmtLoop>(s);
                c.body = body(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::When: {
                StmtWhen c = cast<StmtWhen>(s);
                for (WhenBranch& b : c.branches) b.body = body(b.body);
                if (c.elseBranch) c.elseBranch = body(*c.elseBranch);
                return make(arena, std::move(c));
            }
            case StmtKind::Try: {
                StmtTry c = cast<StmtTry>(s);
                c.body = body(c.body);
                if (c.catchBody) c.catchBody = body(*c.catchBody);
                return make(arena, std::move(c));
            }
            case StmtKind::Scope: {
                StmtScope c = cast<StmtScope>(s);
                c.body = body(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::Defer: {
                StmtDefer c = cast<StmtDefer>(s);
                c.body = body(c.body);
                return make(arena, std::move(c));
            }
            case StmtKind::UsingContext: {
                StmtUsingContext c = cast<StmtUsingContext>(s);
                c.body = body(c.body);
                return make(arena, std::move(c));
            }
            default:
                return s;
        }
    }

    Stmt returned(const StmtThrow& t, const Stmt& original) {
        std::string variant;
        const std::vector<Expr>* args = nullptr;
        if (ExprStringLiteral* text = as<ExprStringLiteral>(t.value)) {
            variant = text->value;
        } else if (ExprMethodCall* call = as<ExprMethodCall>(t.value)) {
            variant = call->name;
            args = &call->args;
        } else {
            return original;
        }
        bool inError = false;
        for (const std::string& set : sets) {
            auto found = errorVariants.find(set);
            if (found != errorVariants.end() && found->second.contains(variant)) {
                inError = true;
                break;
            }
        }
        bool inOk = okVariants.contains(variant);
        std::string okText = ok ? *ok : "null";
        if (inError && inOk) {
            errors.push_back("line " + str(t.line) + ": 'return ." + variant + "' is ambiguous - both '" + okText + "' and '" +
                             join(sets, ", ") + "' have '" + variant + "'; write '" + okText + "." + variant +
                             "' to return it or '" + sets.at(0) + "." + variant + "' to fail with it");
            return original;
        }
        bool allKnown = true;
        for (const std::string& set : sets) allKnown = allKnown && errorVariants.count(set);
        if (!inError && !inOk && allKnown) {
            std::string alsoOk = !okVariants.empty() ? " or '" + okText + "'" : "";
            errors.push_back("line " + str(t.line) + ": 'return ." + variant + "' names no variant of '" + join(sets, ", ") +
                             "'" + alsoOk);
            return original;
        }
        if (inError || !inOk || !ok) return original;
        Expr owner = make(arena, ExprIdentifier{*ok, t.line, t.column, u16len(*ok)});
        Expr value;
        if (args == nullptr) {
            value = make(arena, ExprMember{owner, variant, t.line, t.column});
        } else {
            value = make(arena, ExprMethodCall{owner, variant, *args, t.line, t.column});
        }
        return make(arena, StmtReturn{value, t.line, t.column});
    }
};

}  // namespace

Result resolve(Arena& arena, const Program& program) {
    VariantSets errorVariants;
    VariantSets valueVariants;
    for (const TopLevel& item : program.items) {
        if (TopLevelFail* fail = as<TopLevelFail>(item)) {
            OrderedSet<std::string> set;
            for (const std::string& v : fail->variants) set.add(v);
            errorVariants[fail->name] = std::move(set);
        } else if (TopLevelEnum* e = as<TopLevelEnum>(item)) {
            OrderedSet<std::string> set;
            for (const std::string& v : e->variants) set.add(v);
            valueVariants[e->name] = std::move(set);
        } else if (TopLevelSlot* slot = as<TopLevelSlot>(item); slot && !slot->isError) {
            OrderedSet<std::string> set;
            for (const SlotVariant& v : slot->variants) set.add(v.name);
            valueVariants[slot->name] = std::move(set);
        }
    }
    if (valueVariants.empty()) return Result{program, {}};
    std::vector<std::string> errors;
    const OrderedSet<std::string> none;
    auto func = [&](const FuncDecl& decl, const std::optional<std::string>& self) -> FuncDecl {
        TypeRefFailable* failable = decl.returnType.isExplicit() ? as<TypeRefFailable>(decl.returnType.ref) : nullptr;
        if (failable == nullptr) return decl;
        std::optional<std::string> ok;
        if (TypeRefNamed* named = as<TypeRefNamed>(failable->ok)) ok = (named->name == "Self" && self) ? *self : named->name;
        auto okSet = ok ? valueVariants.find(*ok) : valueVariants.end();
        Context context{arena, failable->errSets, ok, errorVariants, okSet != valueVariants.end() ? okSet->second : none, errors};
        FuncDecl c = decl;
        c.body = context.body(decl.body);
        return c;
    };
    Program out = program;
    out.items.clear();
    for (const TopLevel& item : program.items) {
        switch (item.kind()) {
            case TopLevelKind::Func: {
                TopLevelFunc c = cast<TopLevelFunc>(item);
                c.decl = func(c.decl, std::nullopt);
                out.items.push_back(make(arena, std::move(c)));
                break;
            }
            case TopLevelKind::Impl: {
                TopLevelImpl c = cast<TopLevelImpl>(item);
                std::string self = substringBefore(c.typeName, "<");
                for (FuncDecl& m : c.methods) m = func(m, self);
                out.items.push_back(make(arena, std::move(c)));
                break;
            }
            case TopLevelKind::Spec: {
                TopLevelSpec c = cast<TopLevelSpec>(item);
                for (FuncDecl& m : c.methods) m = func(m, std::nullopt);
                out.items.push_back(make(arena, std::move(c)));
                break;
            }
            default:
                out.items.push_back(item);
                break;
        }
    }
    return Result{std::move(out), std::move(errors)};
}

}  // namespace ReturnedVariants

// ---------------------------------------------------------------------------
// Witnesses
// ---------------------------------------------------------------------------

namespace Witnesses {

std::string named(const OrderedSet<std::string>& needs) {
    for (const char* spec : {HASH, ORDER, PARTIAL_ORDER, EQUAL, CLONE, DISPLAY, STEP}) {
        if (needs.contains(spec)) return spec;
    }
    return EQUAL;
}

std::string slot(const std::string& param) { return "__witness_" + param; }

OrderedMap<std::string, OrderedSet<std::string>> bounds(const Expr& clause, const std::vector<std::string>& typeParams) {
    OrderedMap<std::string, OrderedSet<std::string>> found;
    if (!clause || typeParams.empty()) return found;
    auto add = [&](const std::string& param, std::initializer_list<const char*> specs) {
        OrderedSet<std::string>& set = found.getOrPut(param, {});
        for (const char* spec : specs) set.add(spec);
    };
    std::function<void(const Expr&)> visit = [&](const Expr& expr) {
        if (ExprBinary* binary = as<ExprBinary>(expr)) {
            if (binary->op == TokenType::AND_AND) {
                visit(binary->left);
                visit(binary->right);
            }
        } else if (ExprIsCheck* check = as<ExprIsCheck>(expr)) {
            ExprIdentifier* id = as<ExprIdentifier>(check->expr);
            if (id == nullptr) return;
            const std::string& param = id->name;
            if (std::find(typeParams.begin(), typeParams.end(), param) == typeParams.end()) return;
            std::string spec = substringAfterLast(check->typeName, "__");
            if (spec == HASH) add(param, {HASH, EQUAL});
            else if (spec == ORDER) add(param, {ORDER, EQUAL});
            else if (spec == EQUAL) add(param, {EQUAL});
            // An erased slot is a shared pointer; only the concrete type can copy it.
            else if (spec == CLONE) add(param, {CLONE});
            // `where T: Copy` duplicates implicitly, so erased code needs the copy `.clone()` makes.
            else if (spec == COPY) add(param, {CLONE});
            else if (spec == PARTIAL_ORDER) add(param, {PARTIAL_ORDER});
            else if (spec == DISPLAY) add(param, {DISPLAY});
            // A walk compares its rows as well as moving them.
            else if (spec == STEP) add(param, {STEP, PARTIAL_ORDER});
        } else if (ExprGrouping* grouping = as<ExprGrouping>(expr)) {
            visit(grouping->expr);
        }
    };
    visit(clause);
    return found;
}

OrderedMap<std::string, OrderedSet<std::string>> bounds(const TopLevelPack& pack) { return bounds(pack.whereClause, pack.typeParams); }

Program addDescriptorFields(Arena& arena, const Program& program) {
    bool changed = false;
    std::vector<TopLevel> items;
    items.reserve(program.items.size());
    for (const TopLevel& item : program.items) {
        TopLevelPack* pack = as<TopLevelPack>(item);
        if (pack == nullptr || pack->isBridge) {
            items.push_back(item);
            continue;
        }
        std::vector<std::string> bounded;
        for (const auto& [param, needs] : bounds(*pack).entries) {
            bool present = false;
            for (const PackField& f : pack->fields) present = present || f.name == slot(param);
            if (!present) bounded.push_back(param);
        }
        if (bounded.empty()) {
            items.push_back(item);
            continue;
        }
        changed = true;
        TopLevelPack c = *pack;
        for (const std::string& param : bounded) {
            PackField field;
            field.name = slot(param);
            field.type = make(arena, TypeRefNamed{"Int"});
            field.mutable_ = true;
            field.default_ = make(arena, ExprIntLiteral{-1, pack->line, 0});
            c.fields.push_back(std::move(field));
        }
        items.push_back(make(arena, std::move(c)));
    }
    if (!changed) return program;
    Program out = program;
    out.items = std::move(items);
    return out;
}

}  // namespace Witnesses

// ---------------------------------------------------------------------------
// InferredTypeArgs
// ---------------------------------------------------------------------------

namespace InferredTypeArgs {

namespace {

using Stated = OrderedMap<std::string, TypeRef>;

struct Rewriter {
    Arena& arena;
    const std::unordered_map<std::string, const FuncDecl*>& generic;
    const std::unordered_map<std::string, std::vector<const FuncDecl*>>& members;
    AstRewriter mapper;

    Rewriter(Arena& arena, const std::unordered_map<std::string, const FuncDecl*>& generic,
             const std::unordered_map<std::string, std::vector<const FuncDecl*>>& members)
        : arena(arena), generic(generic), members(members), mapper(arena) {
        mapper.onExpr = [this](AstRewriter& r, const Expr& e) -> Expr {
            Expr mapped = r.baseExpr(e);
            if (ExprCall* call = as<ExprCall>(mapped)) return filled(*call, mapped);
            if (ExprMethodCall* call = as<ExprMethodCall>(mapped)) return filledMember(*call, mapped);
            return mapped;
        };
    }

    FuncDecl decl(const FuncDecl& d) {
        FuncDecl c = d;
        c.body = mapper.stmts(d.body);
        return c;
    }

    const FuncDecl* genericFor(const std::string& callee) const {
        auto found = generic.find(callee);
        if (found != generic.end()) return found->second;
        found = generic.find(substringAfterLast(callee, "__"));
        return found != generic.end() ? found->second : nullptr;
    }

    /** [call] with each argument's type arguments inferred from those the member call wrote. */
    Expr filledMember(const ExprMethodCall& call, const Expr& original) {
        if (call.typeArgs.empty()) return original;
        std::vector<const FuncDecl*> candidates;
        if (auto found = members.find(call.name); found != members.end()) {
            for (const FuncDecl* m : found->second) {
                if (m->typeParams.size() == call.typeArgs.size()) candidates.push_back(m);
            }
        }
        if (candidates.empty()) return original;
        ExprMethodCall c = call;
        for (size_t i = 0; i < c.args.size(); i++) {
            const Expr argument = call.args[i];
            std::vector<TypeRef> expected;
            bool missing = false;
            for (const FuncDecl* member : candidates) {
                Stated stated = zipStated(member->typeParams, call.typeArgs);
                size_t at = (size_t)member->contextualParams + i;
                if (at >= member->params.size()) {
                    missing = true;
                    break;
                }
                expected.push_back(substitute(member->params[at].type, stated));
            }
            if (missing) continue;
            // `.distinct().singleOrNull()`
            std::vector<TypeRef> distinct;
            std::unordered_set<std::string> seen;
            for (const TypeRef& t : expected) {
                if (seen.insert(structuralKey(t)).second) distinct.push_back(t);
            }
            if (distinct.size() != 1) continue;
            c.args[i] = stateTypeArgs(argument, distinct[0]);
        }
        return make(arena, std::move(c));
    }

    /** [call] with each argument's own type arguments inferred from this one's. */
    Expr filled(const ExprCall& call, const Expr& original) {
        if (call.typeArgs.empty()) return original;
        const FuncDecl* outer = genericFor(call.callee);
        if (outer == nullptr) return original;
        Stated stated = zipStated(outer->typeParams, call.typeArgs);
        if (stated.empty()) return original;
        ExprCall c = call;
        for (size_t i = 0; i < c.args.size(); i++) {
            if (i >= outer->params.size()) continue;
            c.args[i] = stateTypeArgs(call.args[i], substitute(outer->params[i].type, stated));
        }
        return make(arena, std::move(c));
    }

    static Stated zipStated(const std::vector<std::string>& params, const std::vector<TypeRef>& args) {
        Stated stated;
        for (size_t i = 0; i < params.size() && i < args.size(); i++) stated.set(params[i], args[i]);
        return stated;
    }

    /** [argument] with its type arguments stated, when [expected] settles them. */
    Expr stateTypeArgs(const Expr& argument, const TypeRef& expected) {
        ExprCall* call = as<ExprCall>(argument);
        if (call == nullptr || !call->typeArgs.empty()) return argument;
        const FuncDecl* callee = genericFor(call->callee);
        if (callee == nullptr) return argument;
        if (!callee->returnType.isExplicit()) return argument;
        const TypeRef& declared = callee->returnType.ref;
        OrderedSet<std::string> params;
        for (const std::string& p : callee->typeParams) params.add(p);
        Stated bound;
        if (!unify(declared, expected, params, bound)) return argument;
        std::vector<TypeRef> complete;
        for (const std::string& p : callee->typeParams) {
            const TypeRef* b = bound.get(p);
            if (b == nullptr) return argument;
            complete.push_back(*b);
        }
        ExprCall c = *call;
        c.typeArgs = std::move(complete);
        return make(arena, std::move(c));
    }

    /** Matches [pattern] against [actual], binding any of [params] it meets. */
    bool unify(const TypeRef& pattern, const TypeRef& actual, const OrderedSet<std::string>& params, Stated& bound) {
        // A borrow is how a parameter is passed, not what it is.
        if (TypeRefReference* ref = as<TypeRefReference>(pattern)) return unify(ref->inner, actual, params, bound);
        if (TypeRefReference* ref = as<TypeRefReference>(actual)) return unify(pattern, ref->inner, params, bound);
        TypeRefNamed* named = as<TypeRefNamed>(pattern);
        if (named && params.contains(named->name) && named->args.empty()) {
            const TypeRef* previous = bound.get(named->name);
            if (previous && !kotlinEquals(*previous, actual)) return false;
            bound.set(named->name, actual);
            return true;
        }
        if (named) {
            if (TypeRefNamed* other = as<TypeRefNamed>(actual)) {
                if (named->name != other->name || named->args.size() != other->args.size()) return false;
                for (size_t i = 0; i < named->args.size(); i++) {
                    if (!unify(named->args[i], other->args[i], params, bound)) return false;
                }
                return true;
            }
        }
        if (is<TypeRefArray>(pattern) && is<TypeRefArray>(actual)) {
            return unify(cast<TypeRefArray>(pattern).element, cast<TypeRefArray>(actual).element, params, bound);
        }
        if (is<TypeRefNullable>(pattern) && is<TypeRefNullable>(actual)) {
            return unify(cast<TypeRefNullable>(pattern).inner, cast<TypeRefNullable>(actual).inner, params, bound);
        }
        if (is<TypeRefPointer>(pattern) && is<TypeRefPointer>(actual)) {
            return unify(cast<TypeRefPointer>(pattern).inner, cast<TypeRefPointer>(actual).inner, params, bound);
        }
        return kotlinEquals(pattern, actual);
    }

    TypeRef substitute(const TypeRef& ref, const Stated& stated) {
        switch (ref.kind()) {
            case TypeRefKind::Named: {
                const TypeRefNamed& named = cast<TypeRefNamed>(ref);
                if (const TypeRef* found = stated.get(named.name)) return *found;
                TypeRefNamed c = named;
                for (TypeRef& a : c.args) a = substitute(a, stated);
                return make(arena, std::move(c));
            }
            case TypeRefKind::Array: {
                TypeRefArray c = cast<TypeRefArray>(ref);
                c.element = substitute(c.element, stated);
                return make(arena, std::move(c));
            }
            case TypeRefKind::Nullable: {
                TypeRefNullable c = cast<TypeRefNullable>(ref);
                c.inner = substitute(c.inner, stated);
                return make(arena, std::move(c));
            }
            case TypeRefKind::Reference: {
                TypeRefReference c = cast<TypeRefReference>(ref);
                c.inner = substitute(c.inner, stated);
                return make(arena, std::move(c));
            }
            case TypeRefKind::Pointer: {
                TypeRefPointer c = cast<TypeRefPointer>(ref);
                c.inner = substitute(c.inner, stated);
                return make(arena, std::move(c));
            }
            case TypeRefKind::Function: {
                TypeRefFunction c = cast<TypeRefFunction>(ref);
                for (TypeRef& p : c.params) p = substitute(p, stated);
                c.ret = substitute(c.ret, stated);
                for (TypeRef& r : c.receivers) r = substitute(r, stated);
                return make(arena, std::move(c));
            }
            default:
                return ref;
        }
    }
};

}  // namespace

Program apply(Arena& arena, const Program& program) {
    std::unordered_map<std::string, const FuncDecl*> generic;
    // Members by their own name: before types are resolved, every generic member of that name is a candidate.
    std::unordered_map<std::string, std::vector<const FuncDecl*>> members;
    auto remember = [&](const FuncDecl& decl) {
        if (decl.typeParams.empty()) return;
        generic[decl.name] = &decl;
        // A library's declaration carries its scope; a call names it plainly.
        generic.emplace(substringAfterLast(decl.name, "__"), &decl);
    };
    for (const TopLevel& item : program.items) {
        if (TopLevelFunc* func = as<TopLevelFunc>(item)) {
            remember(func->decl);
        } else if (TopLevelImpl* impl = as<TopLevelImpl>(item)) {
            for (const FuncDecl& method : impl->methods) {
                remember(method);
                if (!method.typeParams.empty()) members[method.name].push_back(&method);
            }
        }
    }
    if (generic.empty()) return program;
    Rewriter rewriter(arena, generic, members);
    Program out = program;
    out.items.clear();
    for (const TopLevel& item : program.items) {
        if (TopLevelFunc* func = as<TopLevelFunc>(item)) {
            out.items.push_back(make(arena, TopLevelFunc{rewriter.decl(func->decl)}));
        } else if (TopLevelImpl* impl = as<TopLevelImpl>(item)) {
            TopLevelImpl c = *impl;
            for (FuncDecl& m : c.methods) m = rewriter.decl(m);
            out.items.push_back(make(arena, std::move(c)));
        } else {
            out.items.push_back(item);
        }
    }
    return out;
}

}  // namespace InferredTypeArgs

}  // namespace azora
