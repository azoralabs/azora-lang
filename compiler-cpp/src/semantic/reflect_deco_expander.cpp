// Port of semantic/ReflectDecoExpander.kt. Kotlin's AstMapper subclasses
// (Expander, Substitution, ParamSub) are structs owning an AstRewriter whose
// hooks are their overrides.
#include "semantic/reflect_deco_expander.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/numbers.h"
#include "base/text.h"
#include "frontend/ast_rewriter.h"
#include "frontend/ast_util.h"
#include "semantic/decorator_metadata.h"

#include <unordered_map>

namespace azora {
namespace ReflectDecoExpander {

namespace {

/** The decorator name if [stmt] is `inline for X in reflect<*>.withAnnot<D>`, else none. */
std::optional<std::string> withAnnotName(const Stmt& stmt) {
    StmtInlineFor* loop = as<StmtInlineFor>(stmt);
    if (loop == nullptr) return std::nullopt;
    ExprCall* call = as<ExprCall>(loop->iterable);
    if (call == nullptr || call->callee != "__withAnnot" || call->typeArgs.size() != 1) return std::nullopt;
    TypeRefNamed* named = as<TypeRefNamed>(call->typeArgs[0]);
    if (named == nullptr) return std::nullopt;
    return named->name;
}

/** One unrolled iteration, in a scope of its own when it declares anything. */
std::vector<Stmt> ownScope(Arena& arena, std::vector<Stmt> iteration, const StmtInlineFor& loop) {
    for (const Stmt& s : iteration) {
        if (is<StmtFinDecl>(s) || is<StmtVarDecl>(s) || is<StmtLetDecl>(s)) {
            return {make(arena, StmtScope{std::move(iteration), loop.line, loop.column})};
        }
    }
    return iteration;
}

std::optional<std::string> singleTypeArgName(const std::vector<TypeRef>& typeArgs) {
    if (typeArgs.size() != 1) return std::nullopt;
    return displayName(typeArgs[0]);
}

// ---------------------------------------------------------------------------
// ParamSub: one unrolled parameter
// ---------------------------------------------------------------------------

struct ParamSub {
    Arena& arena;
    std::string from;
    Param param;
    const Program& program;
    AstRewriter r;

    ParamSub(Arena& arena, std::string from, Param param, const Program& program)
        : arena(arena), from(std::move(from)), param(std::move(param)), program(program), r(arena) {
        r.onTypeRef = [this](AstRewriter& self, const TypeRef& ref) -> TypeRef {
            TypeRefNamed* named = as<TypeRefNamed>(ref);
            if (named && (named->name == this->from || named->name == this->from + ".ActualType") && named->args.empty()) {
                return this->param.type;
            }
            return self.baseTypeRef(ref);
        };
        r.onExpr = [this](AstRewriter& self, const Expr& e) -> Expr { return expr(self, e); };
    }

    static const TypeRefNamed* base(const TypeRef& ref) {
        if (TypeRefNamed* named = as<TypeRefNamed>(ref)) return named;
        if (TypeRefReference* reference = as<TypeRefReference>(ref)) return base(reference->inner);
        if (TypeRefNullable* nullable = as<TypeRefNullable>(ref)) return base(nullable->inner);
        return nullptr;
    }

    bool onParam(const Expr& e) const {
        ExprIdentifier* id = as<ExprIdentifier>(e);
        return id && id->name == from;
    }

    std::optional<Annotation> annotationOn(const std::optional<std::string>& name) const {
        for (const Annotation& a : param.annotations) {
            if (name && a.name == *name) return a;
        }
        return std::nullopt;
    }

    /** The decorator [name] as this parameter sees it (its own defaults when not written), or none. */
    std::optional<DecoratorMetadata::Applied> applied(const std::optional<std::string>& name) const {
        for (const TopLevel& item : program.items) {
            TopLevelDeco* deco = as<TopLevelDeco>(item);
            if (deco && name && deco->name == *name) return DecoratorMetadata::Applied{deco, annotationOn(name)};
        }
        return std::nullopt;
    }

    /** Whether [e] is a reflect query [callee] about this parameter. */
    ExprCall* about(const Expr& e, const char* callee) const {
        ExprCall* call = as<ExprCall>(e);
        if (call == nullptr || call->callee != callee || call->args.size() != 1) return nullptr;
        ExprIdentifier* id = as<ExprIdentifier>(call->args[0]);
        return (id && id->name == from) ? call : nullptr;
    }

    Expr expr(AstRewriter& self, const Expr& e) {
        if (ExprMember* member = as<ExprMember>(e)) {
            // `P.annotMeta<D>.field` - what the parameter's decorator was given.
            if (ExprCall* query = about(member->target, "__annotMeta")) {
                std::optional<DecoratorMetadata::Applied> found = applied(singleTypeArgName(query->typeArgs));
                if (found) {
                    if (Expr value = DecoratorMetadata::fieldValue(arena, *found, member->name)) return value;
                }
                return e;
            }
            if (onParam(member->target)) return memberOf(*member, e);
            ExprMember c = *member;
            c.target = self.expr(c.target);
            return make(arena, std::move(c));
        }
        if (ExprCall* call = as<ExprCall>(e)) {
            // `P.hasAnnot<D>` - how the caller reads back what the parameter says it is.
            if (about(e, "__hasAnnot")) {
                return make(arena, ExprBoolLiteral{annotationOn(singleTypeArgName(call->typeArgs)).has_value(), call->line,
                                                   call->column});
            }
            const TypeRefNamed* named = base(param.type);
            std::string callee = call->callee;
            if (named && startsWith(call->callee, from + "__")) callee = named->name + call->callee.substr(from.size());
            // The applied arguments travel with the call (`Rows<Shape>::provide`).
            std::vector<TypeRef> typeArgs;
            if (callee != call->callee && call->typeArgs.empty()) {
                if (named) typeArgs = named->args;
            } else {
                for (const TypeRef& t : call->typeArgs) typeArgs.push_back(self.typeRef(t));
            }
            ExprCall c = *call;
            c.callee = callee;
            c.args = self.args(call->args);
            c.typeArgs = std::move(typeArgs);
            if (c.receiver) c.receiver = self.expr(c.receiver);
            return make(arena, std::move(c));
        }
        if (is<ExprIdentifier>(e)) return e;
        return self.baseExpr(e);
    }

    /** One member of the bound parameter, folded to what it stands for. */
    Expr memberOf(const ExprMember& m, const Expr& original) {
        if (m.name == "name") return make(arena, ExprStringLiteral{param.name, m.line, m.column});
        if (m.name == "hasDefault") return make(arena, ExprBoolLiteral{(bool)param.defaultValue, m.line, m.column});
        if (m.name == "isNullable") return make(arena, ExprBoolLiteral{is<TypeRefNullable>(param.type), m.line, m.column});
        if (m.name == "defaultValue") return param.defaultValue ? param.defaultValue : nullLiteral();
        if (m.name == "isVararg") return make(arena, ExprBoolLiteral{param.variadic, m.line, m.column});
        if (m.name == "mutability") {
            const char* variant = param.modifier == ParamModifier::SHARED      ? "Shared"
                                  : param.modifier == ParamModifier::EXCLUSIVE ? "Exclusive"
                                                                               : "Value";
            return make(arena, ExprMember{make(arena, ExprIdentifier{"ParamMutability", m.line, m.column, 15}), variant, m.line,
                                          m.column, m.length});
        }
        // `ActualType` is folded in type position only.
        return original;
    }
};

// ---------------------------------------------------------------------------
// Substitution: one unrolled iteration
// ---------------------------------------------------------------------------

struct Substitution {
    Arena& arena;
    std::string from;
    std::string to;
    const Program& program;
    AstRewriter r;

    Substitution(Arena& arena, std::string from, std::string to, const Program& program)
        : arena(arena), from(std::move(from)), to(std::move(to)), program(program), r(arena) {
        r.onExpandStmt = [this](AstRewriter& self, const Stmt& s) -> std::vector<Stmt> { return expandStmt(self, s); };
        r.onTypeRef = [this](AstRewriter& self, const TypeRef& ref) -> TypeRef {
            TypeRefNamed* named = as<TypeRefNamed>(ref);
            if (named && named->name == this->from) {
                TypeRefNamed c = *named;
                c.name = this->to;
                return self.baseTypeRef(make(this->arena, std::move(c)));
            }
            return self.baseTypeRef(ref);
        };
        r.onExpr = [this](AstRewriter& self, const Expr& e) -> Expr { return expr(self, e); };
        r.onArgs = [this](AstRewriter& self, const std::vector<Expr>& args) -> std::vector<Expr> {
            std::vector<Expr> out;
            for (const Expr& a : args) {
                std::vector<Expr> expanded = arg(self, a);
                out.insert(out.end(), expanded.begin(), expanded.end());
            }
            return out;
        };
    }

    std::string subName(const std::string& name) const {
        if (name == from) return to;
        if (startsWith(name, from + "__")) return to + name.substr(from.size());  // `S__run` → `Movement__run`
        return name;
    }

    /** A block, with any `inline for` over the parameters unrolled in place. */
    std::vector<Stmt> expandStmt(AstRewriter& self, const Stmt& s) {
        StmtInlineFor* loop = as<StmtInlineFor>(s);
        if (loop && boundParams(loop->iterable)) {
            std::vector<Stmt> out;
            for (const Param& param : params()) {
                ParamSub sub(arena, loop->name, param, program);
                std::vector<Stmt> iteration = self.stmts(sub.r.stmts(loop->body));
                for (Stmt& st : ownScope(arena, std::move(iteration), *loop)) out.push_back(st);
            }
            return out;
        }
        return self.baseExpandStmt(s);
    }

    Expr expr(AstRewriter& self, const Expr& e) {
        if (Expr folded = reflectQuery(e)) return folded;
        if (ExprIdentifier* id = as<ExprIdentifier>(e)) {
            if (id->name != from) return e;
            ExprIdentifier c = *id;
            c.name = to;
            return make(arena, std::move(c));
        }
        // The applied type arguments travel with the call.
        if (ExprCall* call = as<ExprCall>(e)) {
            ExprCall c = *call;
            c.callee = subName(call->callee);
            return self.baseExpr(make(arena, std::move(c)));
        }
        return self.baseExpr(e);
    }

    /** One argument, or one per parameter when it is an `inline for` over them. */
    std::vector<Expr> arg(AstRewriter& self, const Expr& e) {
        ExprInlineForArgs* loop = as<ExprInlineForArgs>(e);
        if (loop == nullptr || !boundParams(loop->iterable)) return {self.expr(e)};
        std::vector<Expr> out;
        for (const Param& param : params()) {
            ParamSub sub(arena, loop->name, param, program);
            out.push_back(self.expr(sub.r.expr(loop->body)));
        }
        return out;
    }

    /** Whether [e] is `reflect<X>.params` for the bound declaration. */
    bool boundParams(const Expr& e) const {
        ExprMember* member = as<ExprMember>(e);
        if (member == nullptr || member->name != "params") return false;
        ExprGrouping* grouping = as<ExprGrouping>(member->target);
        return boundReflect(grouping ? grouping->expr : member->target);
    }

    bool boundReflect(const Expr& e) const {
        ExprCall* call = as<ExprCall>(e);
        if (call == nullptr || call->callee != "__reflect" || call->args.size() != 1) return false;
        ExprIdentifier* id = as<ExprIdentifier>(call->args[0]);
        return id && id->name == from;
    }

    /** A parameter's type, with a type macro on it expanded (reflection runs before type macros do). */
    TypeRef expandedType(const TypeRef& ref) const {
        TypeRefNamed* named = as<TypeRefNamed>(ref);
        if (named == nullptr || !NamedTypeMacroCall::isCall(*named)) return ref;
        std::string name = NamedTypeMacroCall::name(*named);
        TypeFormKind shape;
        switch (NamedTypeMacroCall::form(*named)) {
            case NamedTypeMacroCall::Form::Prefix: shape = TypeFormKind::PREFIX; break;
            case NamedTypeMacroCall::Form::List: shape = TypeFormKind::PREFIX_LIST; break;
            default: shape = TypeFormKind::INFIX; break;
        }
        std::string modifier = NamedTypeMacroCall::modifier(*named);
        bool clauses = modifier.find('|') != std::string::npos;
        std::vector<std::string> keywords = clauses ? split(substringBefore(modifier, "|"), ",") : std::vector<std::string>{};
        std::vector<std::string> shapes = clauses ? split(substringAfter(modifier, "|"), ",") : std::vector<std::string>{};
        const TypeTypeArm* rule = nullptr;
        for (const TypeTypeArm& arm : program.typeMacroRules) {
            if (arm.name != name || arm.keywords != keywords) continue;
            bool matches;
            if (clauses) {
                std::vector<bool> listShapes;
                for (const std::string& sh : shapes) listShapes.push_back(startsWith(sh, "L"));
                matches = arm.holeIsList == listShapes;
            } else {
                matches = arm.kind == shape;
            }
            if (matches) {
                rule = &arm;
                break;
            }
        }
        if (rule == nullptr) return ref;
        size_t at = 0;
        OrderedMap<std::string, std::vector<TypeRef>> bindings;
        for (size_t index = 0; index < rule->holes.size(); index++) {
            size_t count;
            if (clauses) {
                std::optional<int32_t> n = toIntOrNull(azora::at(shapes, (int64_t)index).substr(std::min<size_t>(1, azora::at(shapes, (int64_t)index).size())), 10);
                count = n ? (size_t)*n : 1;
            } else {
                count = named->args.size();
            }
            size_t end = std::min(at + count, named->args.size());
            if (at > end) failArgument("fromIndex(" + str((int64_t)at) + ") > toIndex(" + str((int64_t)end) + ")");
            bindings.set(rule->holes[index], std::vector<TypeRef>(named->args.begin() + (std::ptrdiff_t)at, named->args.begin() + (std::ptrdiff_t)end));
            at += count;
        }
        std::function<TypeRef(const TypeRef&)> fill = [&](const TypeRef& tpl) -> TypeRef {
            TypeRefNamed* t = as<TypeRefNamed>(tpl);
            if (t == nullptr) return tpl;
            if (const std::vector<TypeRef>* bound = bindings.get(t->name); bound && bound->size() == 1) return (*bound)[0];
            TypeRefNamed c = *t;
            c.args.clear();
            for (const TypeRef& a : t->args) {
                TypeRefNamed* an = as<TypeRefNamed>(a);
                if (const std::vector<TypeRef>* bound = an ? bindings.get(an->name) : nullptr) {
                    c.args.insert(c.args.end(), bound->begin(), bound->end());
                } else {
                    c.args.push_back(fill(a));
                }
            }
            return make(arena, std::move(c));
        };
        return fill(rule->template_);
    }

    /** The bound declaration's parameters, in order. */
    std::vector<Param> params() const {
        for (const TopLevel& item : program.items) {
            TopLevelFunc* func = as<TopLevelFunc>(item);
            if (func == nullptr || func->decl.name != to) continue;
            std::vector<Param> out;
            for (const Param& p : func->decl.params) {
                Param c = p;
                c.type = expandedType(p.type);
                out.push_back(std::move(c));
            }
            return out;
        }
        return {};
    }

    std::optional<DecoratorMetadata::Site> site() const {
        return DecoratorMetadata::findSite(make(arena, ExprIdentifier{to, 0, 0, u16len(to)}), {}, program);
    }

    std::optional<DecoratorMetadata::Applied> appliedFor(const std::vector<TypeRef>& typeArgs) const {
        std::optional<std::string> deco = singleTypeArgName(typeArgs);
        std::optional<DecoratorMetadata::Site> s = site();
        if (!s || !deco) return std::nullopt;
        return DecoratorMetadata::findApplied(*s, *deco, program);
    }

    /** Folds `reflect<X>.declName`, `.hasAnnot<D>` and `.annotMeta<D>.field`; none when [e] is not such a query. */
    Expr reflectQuery(const Expr& e) const {
        if (ExprMember* member = as<ExprMember>(e)) {
            if (member->name == "declName" && boundReflect(member->target)) {
                return make(arena, ExprStringLiteral{to, member->line, member->column});
            }
            ExprCall* query = as<ExprCall>(member->target);
            if (query && query->callee == "__annotMeta" && query->args.size() == 1) {
                ExprIdentifier* id = as<ExprIdentifier>(query->args[0]);
                if (id && id->name == from) {
                    std::optional<DecoratorMetadata::Applied> applied = appliedFor(query->typeArgs);
                    if (!applied) return nullptr;
                    return DecoratorMetadata::fieldValue(arena, *applied, member->name);
                }
            }
            return nullptr;
        }
        if (ExprCall* call = as<ExprCall>(e); call && call->callee == "__hasAnnot" && call->args.size() == 1) {
            ExprIdentifier* id = as<ExprIdentifier>(call->args[0]);
            if (id && id->name == from) {
                return make(arena, ExprBoolLiteral{appliedFor(call->typeArgs).has_value(), call->line, call->column});
            }
        }
        return nullptr;
    }
};

// ---------------------------------------------------------------------------
// Expander: unrolls withAnnot loops wherever a statement can stand
// ---------------------------------------------------------------------------

struct Expander {
    Arena& arena;
    const std::unordered_map<std::string, std::vector<std::string>>& decorated;
    const Program& program;
    AstRewriter r;

    Expander(Arena& arena, const std::unordered_map<std::string, std::vector<std::string>>& decorated, const Program& program)
        : arena(arena), decorated(decorated), program(program), r(arena) {
        r.onExpandStmt = [this](AstRewriter& self, const Stmt& s) -> std::vector<Stmt> {
            std::optional<std::string> deco = withAnnotName(s);
            if (!deco) return self.baseExpandStmt(s);
            const StmtInlineFor& loop = cast<StmtInlineFor>(s);
            std::vector<Stmt> out;
            auto names = this->decorated.find(*deco);
            if (names == this->decorated.end()) return out;
            for (const std::string& name : names->second) {
                Substitution sub(this->arena, loop.name, name, this->program);
                std::vector<Stmt> iteration = sub.r.stmts(self.stmts(loop.body));
                for (Stmt& st : ownScope(this->arena, std::move(iteration), loop)) out.push_back(st);
            }
            return out;
        };
    }
};

}  // namespace

Program expand(Arena& arena, const Program& program) {
    std::unordered_map<std::string, std::vector<std::string>> decoratedByName;
    for (const TopLevel& item : program.items) {
        const std::string* name = nullptr;
        const std::vector<Annotation>* annotations = nullptr;
        if (TopLevelPack* p = as<TopLevelPack>(item)) {
            name = &p->name;
            annotations = &p->annotations;
        } else if (TopLevelEnum* e = as<TopLevelEnum>(item)) {
            name = &e->name;
            annotations = &e->annotations;
        } else if (TopLevelFail* f = as<TopLevelFail>(item)) {
            name = &f->name;
            annotations = &f->annotations;
        } else if (TopLevelFunc* fn = as<TopLevelFunc>(item)) {
            // A decorated free function enumerates under its own name.
            name = &fn->decl.name;
            annotations = &fn->decl.annotations;
        } else {
            continue;
        }
        for (const Annotation& a : *annotations) decoratedByName[a.name].push_back(*name);
    }
    if (decoratedByName.empty()) return program;
    Expander expander(arena, decoratedByName, program);
    Program out = program;
    out.items.clear();
    for (const TopLevel& item : program.items) {
        if (TopLevelFunc* func = as<TopLevelFunc>(item)) {
            TopLevelFunc c = *func;
            c.decl.body = expander.r.stmts(func->decl.body);
            out.items.push_back(make(arena, std::move(c)));
        } else if (TopLevelImpl* impl = as<TopLevelImpl>(item)) {
            TopLevelImpl c = *impl;
            for (FuncDecl& m : c.methods) m.body = expander.r.stmts(m.body);
            out.items.push_back(make(arena, std::move(c)));
        } else if (TopLevelTest* test = as<TopLevelTest>(item)) {
            TopLevelTest c = *test;
            c.body = expander.r.stmts(test->body);
            out.items.push_back(make(arena, std::move(c)));
        } else {
            out.items.push_back(item);
        }
    }
    return out;
}

}  // namespace ReflectDecoExpander
}  // namespace azora
