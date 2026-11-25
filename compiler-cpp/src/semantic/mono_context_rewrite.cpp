// Port of VariadicMonomorphizer.kt's MonoContext, second half: rewriting every
// declaration, body and type onto the specializations, named type macros, and
// the argument-type inference that decides which specialization a call names.
#include "semantic/mono_context.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/numbers.h"
#include "base/text.h"

#include <algorithm>
#include <functional>

namespace azora {

using namespace mono;

namespace {

/** The pack an `inline for` iterates (`T` or `...T`), when it names one. */
std::optional<std::string> iteratedName(const Expr& iterable) {
    if (const ExprIdentifier* id = as<ExprIdentifier>(iterable)) return id->name;
    if (const ExprSpread* spread = as<ExprSpread>(iterable)) {
        if (const ExprIdentifier* id = as<ExprIdentifier>(spread->array)) return id->name;
    }
    return std::nullopt;
}

/** The parameter-separator position of a parser-mangled `Owner__member`, or none. */
std::optional<size_t> separatorOf(const std::string& name) {
    size_t separator = name.find("__");
    if (separator == std::string::npos || separator == 0) return std::nullopt;
    return separator;
}

}  // namespace

// ---------------------------------------------------------------------------
// Rewriting
// ---------------------------------------------------------------------------

TopLevel MonoContext::rewriteTopLevel(const TopLevel& item) {
    auto exprOpt = [&](const Expr& e) { return e ? rewriteExpr(e) : e; };
    auto typeOpt = [&](const TypeRef& t) { return t ? rewriteType(t) : t; };
    switch (item.kind()) {
        // Drop variadic templates - they are replaced by their monomorphized instances.
        case TopLevelKind::Pack: {
            if (cast<TopLevelPack>(item).variadicParam) return TopLevel{};
            return edit<TopLevelPack>(arena, item, [&](TopLevelPack& c) {
                for (PackField& f : c.fields) {
                    f.type = rewriteType(f.type);
                    f.default_ = exprOpt(f.default_);
                }
            });
        }
        case TopLevelKind::Func: {
            const FuncDecl& decl = cast<TopLevelFunc>(item).decl;
            if (funcTemplates.contains(decl.name) && decl.variadicParam) return TopLevel{};
            FuncDecl rewritten = rewriteFuncDecl(decl);
            return make(arena, TopLevelFunc{rewritten});
        }
        // An impl on a monomorphised pack is a template: expandImpls emits its copies.
        case TopLevelKind::Impl: {
            const TopLevelImpl& impl = cast<TopLevelImpl>(item);
            if (packTemplates.contains(impl.typeName)) return TopLevel{};
            return edit<TopLevelImpl>(arena, item, [&](TopLevelImpl& c) {
                std::vector<TypeRef> traitArgs;
                for (const TypeRef& t : c.traitArgs) traitArgs.push_back(rewriteType(t));
                c.traitArgs = std::move(traitArgs);
                std::vector<Expr> decoratorArgs;
                for (const Expr& e : c.decoratorArgs) decoratorArgs.push_back(rewriteExpr(e));
                c.decoratorArgs = std::move(decoratorArgs);
                for (auto& entry : c.decoratorNamedArgs) entry.second = rewriteExpr(entry.second);
                std::string owner = substringBefore(impl.typeName, "<");
                std::vector<FuncDecl> methods;
                for (const FuncDecl& m : c.methods) methods.push_back(rewriteFuncDecl(m, owner));
                c.methods = std::move(methods);
            });
        }
        // `typealias Vec2i = Vec<Int, 2>` names a specialization.
        case TopLevelKind::TypeAlias:
            if (!cast<TopLevelTypeAlias>(item).typeParams.empty()) return item;
            return edit<TopLevelTypeAlias>(arena, item, [&](TopLevelTypeAlias& c) { c.type = rewriteType(c.type); });
        case TopLevelKind::Test:
            return edit<TopLevelTest>(arena, item, [&](TopLevelTest& c) {
                std::vector<Stmt> body;
                for (const Stmt& s : c.body) body.push_back(rewriteStmt(s));
                c.body = std::move(body);
            });
        case TopLevelKind::VarDecl:
            return edit<TopLevelVarDecl>(arena, item, [&](TopLevelVarDecl& c) {
                c.type = typeOpt(c.type);
                c.initializer = rewriteExpr(c.initializer);
            });
        case TopLevelKind::LetDecl:
            return edit<TopLevelLetDecl>(arena, item, [&](TopLevelLetDecl& c) {
                c.type = typeOpt(c.type);
                c.initializer = rewriteExpr(c.initializer);
            });
        case TopLevelKind::FinDecl:
            return edit<TopLevelFinDecl>(arena, item, [&](TopLevelFinDecl& c) {
                c.type = typeOpt(c.type);
                c.initializer = rewriteExpr(c.initializer);
            });
        case TopLevelKind::InlineVar:
            return edit<TopLevelInlineVar>(arena, item, [&](TopLevelInlineVar& c) { c.initializer = rewriteExpr(c.initializer); });
        case TopLevelKind::InlineLet:
            return edit<TopLevelInlineLet>(arena, item, [&](TopLevelInlineLet& c) { c.initializer = rewriteExpr(c.initializer); });
        case TopLevelKind::InlineFin:
            return edit<TopLevelInlineFin>(arena, item, [&](TopLevelInlineFin& c) { c.initializer = rewriteExpr(c.initializer); });
        case TopLevelKind::InlineTrace:
            return edit<TopLevelInlineTrace>(arena, item, [&](TopLevelInlineTrace& c) {
                c.message = rewriteExpr(c.message);
                c.level = exprOpt(c.level);
            });
        default: return item;
    }
}

FuncDecl MonoContext::rewriteFuncDecl(const FuncDecl& decl, const std::optional<std::string>& owner /*= std::nullopt*/) {
    std::unordered_set<std::string> savedParams = unresolvedTypeParams;
    std::unordered_set<std::string> unresolved;
    for (const std::string& p : decl.typeParams) {
        if (!typeBindings.contains(p)) unresolved.insert(p);
    }
    unresolvedTypeParams = std::move(unresolved);
    Finally restore{[&] { unresolvedTypeParams = savedParams; }};
    bindings.clear();
    for (const Param& p : decl.params) bindings.set(p.name, p.type);
    // The receiver, so `self.field` can be typed.
    if (owner) bindings.set(decl.receiverName, named(arena, *owner));
    FuncDecl copy = decl;
    copy.params.clear();
    for (const Param& p : decl.params) copy.params.push_back(rewriteParam(p));
    copy.returnType = rewriteTypeAnnotation(decl.returnType);
    copy.body = rewriteBody(decl.body);
    return copy;
}

std::vector<Stmt> MonoContext::rewriteBody(const std::vector<Stmt>& body) {
    std::vector<Stmt> out;
    for (const Stmt& statement : body) {
        const StmtInlineFor* loop = as<StmtInlineFor>(statement);
        const std::vector<TypeRef>* pack = nullptr;
        if (loop) {
            if (std::optional<std::string> name = iteratedName(loop->iterable)) pack = typePackBindings.get(*name);
        }
        if (!pack) {
            out.push_back(rewriteStmt(statement));
            continue;
        }
        // `inline for Ty in ...T { … }` means once per type the specialization was given.
        std::vector<TypeRef> types = *pack;
        TypeMap saved = typeBindings;
        Finally restore{[&] { typeBindings = saved; }};
        for (const TypeRef& type : types) {
            TypeMap each = saved;
            each.set(loop->name, type);
            typeBindings = std::move(each);
            for (const Stmt& s : rewriteBody(loop->body)) out.push_back(s);
        }
    }
    return out;
}

Param MonoContext::rewriteParam(const Param& param) {
    TypeRef rewritten = rewriteType(param.type);
    const TypeRefReference* reference = as<TypeRefReference>(rewritten);
    // A default is an expression like any in the body.
    Expr defaultValue = param.defaultValue ? rewriteExpr(param.defaultValue) : param.defaultValue;
    Param copy = param;
    if (reference) {
        copy.type = reference->inner;
        if (param.modifier == ParamModifier::NONE) copy.modifier = paramModifier(reference->kind);
    } else {
        copy.type = rewritten;
    }
    copy.defaultValue = defaultValue;
    return copy;
}

TypeAnnotation MonoContext::rewriteTypeAnnotation(const TypeAnnotation& ann) {
    return ann.isExplicit() ? explicitType(rewriteType(ann.ref)) : ann;
}

TypeRef MonoContext::rewriteType(const TypeRef& ref) {
    if (const TypeRefNamed* n = as<TypeRefNamed>(ref)) {
        if (n->args.empty()) {
            if (const TypeRef* bound = typeBindings.get(n->name)) {
                TypeRef value = *bound;
                return rewriteType(value);
            }
        }
        if (NamedTypeMacroCall::isCall(*n)) return expandNamedTypeMacro(*n);
        if (packTemplates.contains(n->name) && !n->args.empty()) {
            return named(arena, instantiatePack(n->name, spreadPacks(n->args)));
        }
        if (!n->args.empty()) {
            std::vector<TypeRef> args;
            for (const TypeRef& arg : spreadPacks(n->args)) args.push_back(rewriteType(arg));
            return edit<TypeRefNamed>(arena, ref, [&](TypeRefNamed& c) {
                c.args = std::move(args);
                c.variadic = false;
            });
        }
        return ref;
    }
    // `...T` among a callable's parameters stands for every type the pack holds.
    return mapChildren(
        arena, ref, [&](const TypeRef& t) { return rewriteType(t); },
        [&](const std::vector<TypeRef>& ts) { return spreadPacks(ts); }, true);
}

std::vector<TypeRef> MonoContext::spreadPacks(const std::vector<TypeRef>& args) {
    std::vector<TypeRef> out;
    for (const TypeRef& arg : args) {
        const TypeRefNamed* n = as<TypeRefNamed>(arg);
        const std::vector<TypeRef>* pack = n && n->args.empty() ? typePackBindings.get(n->name) : nullptr;
        if (pack) {
            out.insert(out.end(), pack->begin(), pack->end());
        } else {
            out.push_back(arg);
        }
    }
    return out;
}

TypeRef MonoContext::expandNamedTypeMacro(const TypeRefNamed& call) {
    TypeFormKind expectedKind = TypeFormKind::PREFIX;
    switch (NamedTypeMacroCall::form(call)) {
        case NamedTypeMacroCall::Form::Prefix: expectedKind = TypeFormKind::PREFIX; break;
        case NamedTypeMacroCall::Form::List: expectedKind = TypeFormKind::PREFIX_LIST; break;
        case NamedTypeMacroCall::Form::Infix: expectedKind = TypeFormKind::INFIX; break;
    }
    std::string name = NamedTypeMacroCall::name(call);
    std::string modifier = NamedTypeMacroCall::modifier(call);
    // `with,without|L2,S1,S1` - the words the use site wrote, and the shape of each operand.
    bool clauses = contains(modifier, '|');
    std::vector<std::string> keywords = clauses ? split(substringBefore(modifier, "|"), ",") : std::vector<std::string>{};
    std::vector<std::string> shapes = clauses ? split(substringAfter(modifier, "|"), ",") : std::vector<std::string>{};
    std::string plain = clauses ? "" : modifier;
    std::vector<bool> shapeIsList;
    for (const std::string& shape : shapes) shapeIsList.push_back(startsWith(shape, "L"));
    std::vector<const TypeTypeArm*> matching;
    for (const TypeTypeArm& rule : typeMacroRules) {
        if (rule.name == name && rule.prefix == plain && rule.keywords == keywords && (!clauses || rule.holeIsList == shapeIsList) &&
            (clauses || rule.kind == expectedKind)) {
            matching.push_back(&rule);
        }
    }
    // A macro may have one arm per shape it accepts: an exact arity wins, the list arm takes the rest.
    std::vector<const TypeTypeArm*> matches;
    if (clauses) {
        matches = matching;
    } else {
        for (const TypeTypeArm* rule : matching) {
            if (rule->holes.size() == call.args.size() && !rule->listTail) matches.push_back(rule);
        }
        if (matches.empty()) {
            for (const TypeTypeArm* rule : matching) {
                if (rule->listTail && (int64_t)call.args.size() >= (int64_t)rule->holes.size() - 1) matches.push_back(rule);
            }
        }
        if (matches.empty()) matches = matching;
    }
    if (matches.empty()) {
        std::string spelling = modifier.empty() ? name : modifier + " " + name;
        fail("undefined type macro '" + spelling + "'; import the module that declares it");
    }
    if (matches.size() > 1) {
        fail("ambiguous type macro '" + name + "': " + str((int)matches.size()) + " matching rules are visible");
    }
    const TypeTypeArm& rule = *matches[0];
    std::vector<TypeRef> rewrittenArgs;
    for (const TypeRef& arg : call.args) rewrittenArgs.push_back(rewriteType(arg));
    OrderedMap<std::string, std::vector<TypeRef>> bound;
    if (rule.kind == TypeFormKind::PREFIX_LIST && rule.holes.size() == 1) {
        bound.set(rule.holes[0], rewrittenArgs);
    } else if (!rule.keywords.empty()) {
        // A clause sequence: each hole takes its own operand, sized by the shape the use site wrote.
        size_t at = 0;
        for (size_t index = 0; index < rule.holes.size(); index++) {
            const std::string& shape = azora::at(shapes, (int64_t)index);
            std::optional<int32_t> written = toIntOrNull(shape.size() > 0 ? shape.substr(1) : std::string(), 10);
            size_t count = written ? (size_t)*written : 1;
            size_t end = std::min(at + count, rewrittenArgs.size());
            if (at > end) {
                failWith(FailureKind::IllegalArgument, "fromIndex(" + str((long long)at) + ") > toIndex(" + str((long long)end) + ")");
            }
            bound.set(rule.holes[index], std::vector<TypeRef>(rewrittenArgs.begin() + (std::ptrdiff_t)at,
                                                              rewrittenArgs.begin() + (std::ptrdiff_t)end));
            at += count;
        }
    } else if (rule.listTail && (int64_t)rewrittenArgs.size() >= (int64_t)rule.holes.size() - 1) {
        size_t leading = rule.holes.size() - 1;
        for (size_t index = 0; index < leading; index++) bound.set(rule.holes[index], {rewrittenArgs[index]});
        bound.set(rule.holes.back(), std::vector<TypeRef>(rewrittenArgs.begin() + (std::ptrdiff_t)leading, rewrittenArgs.end()));
    } else if (rule.holes.size() == rewrittenArgs.size()) {
        for (size_t index = 0; index < rule.holes.size(); index++) bound.set(rule.holes[index], {rewrittenArgs[index]});
    } else {
        fail("type macro '" + name + "' expects " + str((int)rule.holes.size()) + " type argument(s), got " +
             str((int)rewrittenArgs.size()));
    }
    return rewriteType(substituteTypeMacroTemplate(rule.template_, bound));
}

TypeRef MonoContext::substituteTypeMacroTemplate(const TypeRef& type, const OrderedMap<std::string, std::vector<TypeRef>>& bound) {
    if (const TypeRefNamed* n = as<TypeRefNamed>(type)) {
        if (const std::vector<TypeRef>* values = bound.get(n->name)) {
            if (values->size() != 1) {
                fail("variadic type-macro hole '" + n->name + "' must be expanded inside '<..." + n->name + ">'");
            }
            return (*values)[0];
        }
        std::vector<TypeRef> args;
        for (const TypeRef& argument : n->args) {
            const TypeRefNamed* hole = as<TypeRefNamed>(argument);
            const std::vector<TypeRef>* spread = n->variadic && hole ? bound.get(hole->name) : nullptr;
            if (spread) {
                args.insert(args.end(), spread->begin(), spread->end());
            } else {
                args.push_back(substituteTypeMacroTemplate(argument, bound));
            }
        }
        return edit<TypeRefNamed>(arena, type, [&](TypeRefNamed& c) {
            c.args = std::move(args);
            c.variadic = false;
        });
    }
    return mapChildren(
        arena, type, [&](const TypeRef& t) { return substituteTypeMacroTemplate(t, bound); }, asWritten, true);
}

void MonoContext::resolveStaticAliases(const std::vector<TopLevel>& items) {
    for (const TopLevel& item : items) {
        const TopLevelTypeAlias* alias = as<TopLevelTypeAlias>(item);
        if (!alias || !alias->typeParams.empty()) continue;
        const TypeRefNamed* target = as<TypeRefNamed>(alias->type);
        if (!target) continue;
        // On a later pass the alias already names the specialization.
        bool specialized = false;
        for (const auto& [name, _] : packTemplates) {
            if (startsWith(target->name, "__" + name + "_")) {
                specialized = true;
                break;
            }
        }
        if (specialized) {
            staticAliases.set(alias->name, target->name);
            continue;
        }
        if (!packTemplates.contains(target->name)) continue;
        const TypeRefNamed* mangled = as<TypeRefNamed>(rewriteType(alias->type));
        if (!mangled) continue;
        if (mangled->name != target->name) staticAliases.set(alias->name, mangled->name);
    }
}

std::optional<std::string> MonoContext::throughGenericOwner(const std::string& callee, const std::vector<TypeRef>& typeArgs) {
    if (typeArgs.empty()) return std::nullopt;
    std::optional<size_t> separator = separatorOf(callee);
    if (!separator) return std::nullopt;
    std::string owner = callee.substr(0, *separator);
    if (!packTemplates.contains(owner)) return std::nullopt;
    std::vector<TypeRef> args;
    for (const TypeRef& t : typeArgs) args.push_back(rewriteType(t));
    return instantiatePack(owner, args) + callee.substr(*separator);
}

std::optional<std::string> MonoContext::throughBoundTypeParam(const std::string& callee) {
    std::optional<size_t> separator = separatorOf(callee);
    if (!separator) return std::nullopt;
    std::string owner = callee.substr(0, *separator);
    const TypeRef* binding = typeBindings.get(owner);
    const TypeRefNamed* bound = binding ? as<TypeRefNamed>(*binding) : nullptr;
    if (!bound) return std::nullopt;
    std::string name = bound->args.empty() ? bound->name : instantiatePack(bound->name, bound->args);
    if (name == owner) return std::nullopt;
    return name + callee.substr(*separator);
}

std::string MonoContext::throughStaticAlias(const std::string& name) {
    if (const std::string* direct = staticAliases.get(name)) return *direct;
    std::optional<size_t> separator = separatorOf(name);
    if (!separator) return name;
    const std::string* mangled = staticAliases.get(name.substr(0, *separator));
    if (!mangled) return name;
    return *mangled + name.substr(*separator);
}

Expr MonoContext::rewriteExpr(const Expr& e) {
    auto all = [&](const std::vector<Expr>& values) {
        std::vector<Expr> out;
        out.reserve(values.size());
        for (const Expr& v : values) out.push_back(rewriteExpr(v));
        return out;
    };
    switch (e.kind()) {
        case ExprKind::Member: {
            // `T.typeName` - the bound type's own name, as a string.
            const ExprMember& member = cast<ExprMember>(e);
            if (member.name == "typeName") {
                if (const ExprIdentifier* id = as<ExprIdentifier>(member.target)) {
                    if (const TypeRef* bound = typeBindings.get(id->name)) {
                        return make(arena, ExprStringLiteral{displayName(*bound), member.line, member.column});
                    }
                }
            }
            return edit<ExprMember>(arena, e, [&](auto& c) { c.target = rewriteExpr(c.target); });
        }
        // `N` reads as its value and `T` as its type.
        case ExprKind::Identifier: {
            const ExprIdentifier& id = cast<ExprIdentifier>(e);
            if (const int64_t* value = constBindings.get(id.name)) return make(arena, ExprIntLiteral{*value, id.line, id.column});
            if (const TypeRef* bound = typeBindings.get(id.name)) {
                const TypeRefNamed* n = as<TypeRefNamed>(*bound);
                if (n && n->args.empty()) {
                    std::string name = n->name;
                    return edit<ExprIdentifier>(arena, e, [&](auto& c) { c.name = name; });
                }
            }
            std::string name = throughStaticAlias(id.name);
            return edit<ExprIdentifier>(arena, e, [&](auto& c) { c.name = name; });
        }
        case ExprKind::Call: {
            // `Owner::member<A, B>(…)` - the arguments choose the specialization the member is on.
            const ExprCall& call = cast<ExprCall>(e);
            if (std::optional<std::string> callee = throughGenericOwner(call.callee, call.typeArgs)) {
                return edit<ExprCall>(arena, e, [&](auto& c) {
                    c.callee = *callee;
                    c.args = all(c.args);
                    c.typeArgs = {};
                });
            }
            return rewriteCall(call);
        }
        case ExprKind::Binary:
            return edit<ExprBinary>(arena, e, [&](auto& c) {
                c.left = rewriteExpr(c.left);
                c.right = rewriteExpr(c.right);
            });
        case ExprKind::InCheck:
            return edit<ExprInCheck>(arena, e, [&](auto& c) {
                c.value = rewriteExpr(c.value);
                c.collection = rewriteExpr(c.collection);
            });
        case ExprKind::InlineForArgs:
            return edit<ExprInlineForArgs>(arena, e, [&](auto& c) {
                c.iterable = rewriteExpr(c.iterable);
                c.body = rewriteExpr(c.body);
            });
        case ExprKind::Unary: return edit<ExprUnary>(arena, e, [&](auto& c) { c.operand = rewriteExpr(c.operand); });
        case ExprKind::IncDec: return edit<ExprIncDec>(arena, e, [&](auto& c) { c.target = rewriteExpr(c.target); });
        case ExprKind::Grouping: return edit<ExprGrouping>(arena, e, [&](auto& c) { c.expr = rewriteExpr(c.expr); });
        case ExprKind::Range:
            return edit<ExprRange>(arena, e, [&](auto& c) {
                c.from = rewriteExpr(c.from);
                c.to = rewriteExpr(c.to);
            });
        case ExprKind::ArrayLiteral: return edit<ExprArrayLiteral>(arena, e, [&](auto& c) { c.elements = all(c.elements); });
        case ExprKind::SetLiteral: return edit<ExprSetLiteral>(arena, e, [&](auto& c) { c.elements = all(c.elements); });
        case ExprKind::Index:
            return edit<ExprIndex>(arena, e, [&](auto& c) {
                c.target = rewriteExpr(c.target);
                c.index = rewriteExpr(c.index);
            });
        case ExprKind::MethodCall:
            return edit<ExprMethodCall>(arena, e, [&](auto& c) {
                c.target = rewriteExpr(c.target);
                c.args = all(c.args);
            });
        case ExprKind::StringTemplate:
            return edit<ExprStringTemplate>(arena, e, [&](auto& c) {
                for (StringTemplatePart& part : c.parts) {
                    if (!part.isLiteral) part = exprPart(rewriteExpr(part.expr));
                }
            });
        case ExprKind::TupleLit: return edit<ExprTupleLit>(arena, e, [&](auto& c) { c.elements = all(c.elements); });
        case ExprKind::VariantLit: return edit<ExprVariantLit>(arena, e, [&](auto& c) { c.elements = all(c.elements); });
        case ExprKind::TupleAccess: return edit<ExprTupleAccess>(arena, e, [&](auto& c) { c.target = rewriteExpr(c.target); });
        case ExprKind::CatchExpr:
            return edit<ExprCatchExpr>(arena, e, [&](auto& c) {
                c.expr = rewriteExpr(c.expr);
                c.fallback = rewriteExpr(c.fallback);
            });
        case ExprKind::TryPropagate: return edit<ExprTryPropagate>(arena, e, [&](auto& c) { c.expr = rewriteExpr(c.expr); });
        case ExprKind::IfExpr:
            return edit<ExprIfExpr>(arena, e, [&](auto& c) {
                c.condition = rewriteExpr(c.condition);
                c.thenExpr = rewriteExpr(c.thenExpr);
                c.elseExpr = rewriteExpr(c.elseExpr);
            });
        case ExprKind::Lambda:
            return edit<ExprLambda>(arena, e, [&](auto& c) {
                std::vector<Param> params;
                for (const Param& p : c.params) params.push_back(rewriteParam(p));
                std::vector<Param> receivers;
                for (const Param& p : c.receivers) receivers.push_back(rewriteParam(p));
                std::vector<Stmt> body;
                for (const Stmt& s : c.body) body.push_back(rewriteStmt(s));
                c.params = std::move(params);
                c.receivers = std::move(receivers);
                c.body = std::move(body);
            });
        case ExprKind::NamedArg: return edit<ExprNamedArg>(arena, e, [&](auto& c) { c.value = rewriteExpr(c.value); });
        case ExprKind::NullCoalesce:
            return edit<ExprNullCoalesce>(arena, e, [&](auto& c) {
                c.left = rewriteExpr(c.left);
                c.right = rewriteExpr(c.right);
            });
        case ExprKind::SafeMember: return edit<ExprSafeMember>(arena, e, [&](auto& c) { c.target = rewriteExpr(c.target); });
        case ExprKind::Cast:
            return edit<ExprCast>(arena, e, [&](auto& c) {
                c.expr = rewriteExpr(c.expr);
                c.targetType = rewriteType(c.targetType);
            });
        case ExprKind::IsCheck: return edit<ExprIsCheck>(arena, e, [&](auto& c) { c.expr = rewriteExpr(c.expr); });
        case ExprKind::MapLit:
            return edit<ExprMapLit>(arena, e, [&](auto& c) {
                for (auto& entry : c.entries) {
                    Expr key = rewriteExpr(entry.first);
                    Expr value = rewriteExpr(entry.second);
                    entry = {key, value};
                }
            });
        case ExprKind::Alloc: return edit<ExprAlloc>(arena, e, [&](auto& c) { c.value = rewriteExpr(c.value); });
        case ExprKind::Deref: return edit<ExprDeref>(arena, e, [&](auto& c) { c.target = rewriteExpr(c.target); });
        case ExprKind::Isolated: return edit<ExprIsolated>(arena, e, [&](auto& c) { c.value = rewriteExpr(c.value); });
        case ExprKind::Await: return edit<ExprAwait>(arena, e, [&](auto& c) { c.value = rewriteExpr(c.value); });
        case ExprKind::Spread: return edit<ExprSpread>(arena, e, [&](auto& c) { c.array = rewriteExpr(c.array); });
        // `.(t, Vec3(…))` - the arguments of an inferred construction or case.
        case ExprKind::InferredMember:
            return edit<ExprInferredMember>(arena, e, [&](auto& c) {
                if (c.ctorArgs) c.ctorArgs = all(*c.ctorArgs);
                if (c.scopeCall) {
                    std::vector<Expr> args = all(c.scopeCall->args);
                    c.scopeCall = edit<ExprCall>(arena, Expr(c.scopeCall), [&](ExprCall& call) { call.args = std::move(args); });
                }
            });
        default: return e;
    }
}

std::vector<Expr> MonoContext::expandTypePackArgument(const Expr& arg) {
    // One argument, or the several an `inline for` over a bound type pack stands for.
    const ExprInlineForArgs* loop = as<ExprInlineForArgs>(arg);
    if (!loop) return {rewriteExpr(arg)};
    std::optional<std::string> packName = iteratedName(loop->iterable);
    if (!packName) return {rewriteExpr(arg)};
    const std::vector<TypeRef>* found = typePackBindings.get(*packName);
    if (!found) return {rewriteExpr(arg)};
    std::vector<TypeRef> types = *found;
    TypeMap saved = typeBindings;
    Finally restore{[&] { typeBindings = saved; }};
    std::vector<Expr> out;
    for (const TypeRef& type : types) {
        TypeMap each = saved;
        each.set(loop->name, type);
        typeBindings = std::move(each);
        out.push_back(rewriteExpr(loop->body));
    }
    return out;
}

Expr MonoContext::rewriteCall(const ExprCall& original) {
    std::vector<Expr> expandedArgs;
    for (const Expr& arg : original.args) {
        for (const Expr& each : expandTypePackArgument(arg)) expandedArgs.push_back(each);
    }
    ExprCall e = original;
    e.args = std::move(expandedArgs);
    std::optional<std::vector<TypeRef>> elementTypes = resolveElementTypes(e);
    std::optional<std::string> mangled;
    if (funcTemplates.contains(e.callee) && elementTypes &&
        std::none_of(elementTypes->begin(), elementTypes->end(), [&](const TypeRef& t) { return hasUnboundType(t); })) {
        mangled = instantiateFunc(e.callee, *elementTypes);
    } else if (packTemplates.contains(e.callee) && staticAliases.get(e.callee)) {
        // Inside a specialization of this very pack, building it means building *this* one.
        mangled = *staticAliases.get(e.callee);
    } else if (packTemplates.contains(e.callee) && elementTypes) {
        mangled = instantiatePack(e.callee, *elementTypes);
    }
    // The arguments are already rewritten by the expansion above.
    if (mangled) {
        e.callee = *mangled;
        e.typeArgs = {};
        return make(arena, std::move(e));
    }
    // `Self(…)` in a specialized member builds that specialization.
    std::optional<std::string> callee;
    if (const TypeRef* bound = typeBindings.get(e.callee)) {
        const TypeRefNamed* n = as<TypeRefNamed>(*bound);
        if (n && n->args.empty()) callee = n->name;
    }
    if (!callee) callee = throughBoundTypeParam(e.callee);
    if (!callee) callee = throughStaticAlias(e.callee);
    e.callee = *callee;
    std::vector<TypeRef> typeArgs;
    for (const TypeRef& t : e.typeArgs) typeArgs.push_back(rewriteType(t));
    e.typeArgs = std::move(typeArgs);
    return make(arena, std::move(e));
}

bool MonoContext::hasUnboundType(const TypeRef& type) {
    auto any = [&](const std::vector<TypeRef>& types) {
        return std::any_of(types.begin(), types.end(), [&](const TypeRef& t) { return hasUnboundType(t); });
    };
    switch (type.kind()) {
        case TypeRefKind::Named: {
            const TypeRefNamed& n = cast<TypeRefNamed>(type);
            return unresolvedTypeParams.count(n.name) != 0 || any(n.args);
        }
        case TypeRefKind::Reference: return hasUnboundType(cast<TypeRefReference>(type).inner);
        case TypeRefKind::Pointer: return hasUnboundType(cast<TypeRefPointer>(type).inner);
        case TypeRefKind::Array: return hasUnboundType(cast<TypeRefArray>(type).element);
        case TypeRefKind::Nullable: return hasUnboundType(cast<TypeRefNullable>(type).inner);
        case TypeRefKind::Map: return hasUnboundType(cast<TypeRefMap>(type).key) || hasUnboundType(cast<TypeRefMap>(type).value);
        case TypeRefKind::Set: return hasUnboundType(cast<TypeRefSet>(type).element);
        case TypeRefKind::Tuple: return any(cast<TypeRefTuple>(type).elements);
        case TypeRefKind::Function: return any(cast<TypeRefFunction>(type).params) || hasUnboundType(cast<TypeRefFunction>(type).ret);
        case TypeRefKind::Failable: return hasUnboundType(cast<TypeRefFailable>(type).ok);
        default: return false;
    }
}

TypeRef MonoContext::fieldType(const TypeRef& target, const std::string& field) {
    // A receiver is written `self: Self&`, so the borrow is between the name and the pack.
    TypeRef unwrapped = target;
    while (const TypeRefReference* r = as<TypeRefReference>(unwrapped)) unwrapped = r->inner;
    const TypeRefNamed* owner = as<TypeRefNamed>(unwrapped);
    if (!owner) return TypeRef{};
    TopLevelPack* const* found = packTemplates.get(owner->name);
    const TopLevelPack* tpl = found ? *found : nullptr;
    const std::vector<PackField>* fields = plainPackFields.get(owner->name);
    if (!fields && tpl) fields = &tpl->fields;
    if (!fields) return TypeRef{};
    TypeRef declared;
    for (const PackField& f : *fields) {
        if (f.name == field) {
            declared = f.type;
            break;
        }
    }
    if (!declared) return TypeRef{};
    if (!tpl) {
        const std::vector<std::string>* params = constructibleTypes.get(owner->name);
        return substituteParams(declared, zipToMap(params ? *params : std::vector<std::string>{}, owner->args));
    }
    return substituteParams(declared, zipToMap(tpl->typeParams, owner->args));
}

std::optional<std::vector<TypeRef>> MonoContext::resolveElementTypes(const ExprCall& call) {
    // Inside a specialized member, `Vec<T, N>(0)` names that member's own specialization;
    // `tupleOf<Int, ...T>(…)` spreads.
    if (!call.typeArgs.empty()) {
        std::vector<TypeRef> out;
        for (const TypeRef& arg : call.typeArgs) {
            const TypeRefNamed* n = as<TypeRefNamed>(arg);
            const std::vector<TypeRef>* pack = n && n->variadic ? typePackBindings.get(n->name) : nullptr;
            if (pack) {
                out.insert(out.end(), pack->begin(), pack->end());
            } else {
                out.push_back(substituteParams(arg, typeBindings));
            }
        }
        return out;
    }
    std::vector<TypeRef> inferred;
    for (const Expr& arg : call.args) inferred.push_back(inferExprType(arg));
    const FuncDecl* function = nullptr;
    if (TopLevelFunc* const* found = funcTemplates.get(call.callee)) {
        if (!(*found)->decl.variadicParam) function = &(*found)->decl;
    }
    if (function) {
        OrderedMap<std::string, TypeRef> found;
        std::function<void(const TypeRef&, const TypeRef&)> bind = [&](const TypeRef& expected, const TypeRef& actual) {
            const TypeRefNamed* expectedNamed = as<TypeRefNamed>(expected);
            const TypeRefNamed* actualNamed = as<TypeRefNamed>(actual);
            if (const TypeRefReference* r = as<TypeRefReference>(expected)) {
                const TypeRefReference* a = as<TypeRefReference>(actual);
                bind(r->inner, a ? a->inner : actual);
            } else if (expectedNamed && expectedNamed->args.empty() && contains(function->typeParams, expectedNamed->name)) {
                if (!found.contains(expectedNamed->name)) found.set(expectedNamed->name, actual);
            } else if (expectedNamed && actualNamed && expectedNamed->name == actualNamed->name) {
                size_t n = std::min(expectedNamed->args.size(), actualNamed->args.size());
                for (size_t i = 0; i < n; i++) bind(expectedNamed->args[i], actualNamed->args[i]);
            } else if (is<TypeRefArray>(expected) && is<TypeRefArray>(actual)) {
                bind(cast<TypeRefArray>(expected).element, cast<TypeRefArray>(actual).element);
            } else if (is<TypeRefNullable>(expected) && is<TypeRefNullable>(actual)) {
                bind(cast<TypeRefNullable>(expected).inner, cast<TypeRefNullable>(actual).inner);
            }
        };
        size_t n = std::min(function->params.size(), inferred.size());
        for (size_t i = 0; i < n; i++) {
            if (inferred[i]) bind(function->params[i].type, inferred[i]);
        }
        std::vector<TypeRef> out;
        for (const std::string& p : function->typeParams) {
            const TypeRef* bound = found.get(p);
            if (!bound) return std::nullopt;
            out.push_back(*bound);
        }
        return out;
    }
    for (const TypeRef& t : inferred) {
        if (!t) return std::nullopt;
    }
    return inferred;
}

TypeRef MonoContext::inferExprType(const Expr& e) {
    switch (e.kind()) {
        case ExprKind::IntLiteral: return named(arena, Literals::DEFAULT_INT);
        case ExprKind::DoubleLiteral: return named(arena, Literals::DEFAULT_FLOAT);
        case ExprKind::StringLiteral: return named(arena, "String");
        case ExprKind::BoolLiteral: return named(arena, "Bool");
        case ExprKind::CharLiteral: return named(arena, "Char");
        case ExprKind::Identifier: {
            const TypeRef* bound = bindings.get(cast<ExprIdentifier>(e).name);
            return bound ? *bound : TypeRef{};
        }
        case ExprKind::Grouping: return inferExprType(cast<ExprGrouping>(e).expr);
        case ExprKind::Isolated: return inferExprType(cast<ExprIsolated>(e).value);
        case ExprKind::Binary: return inferBinaryType(cast<ExprBinary>(e));
        case ExprKind::TupleAccess: {
            TypeRef target = inferExprType(cast<ExprTupleAccess>(e).target);
            return target ? tupleElementType(target, cast<ExprTupleAccess>(e).index) : TypeRef{};
        }
        // Positional tuple access parses as a `Member` with a numeric field name.
        case ExprKind::Member: {
            const ExprMember& member = cast<ExprMember>(e);
            std::optional<int32_t> index = toIntOrNull(member.name, 10);
            TypeRef target = inferExprType(member.target);
            if (index) return target ? tupleElementType(target, *index) : TypeRef{};
            return fieldType(target, member.name);
        }
        case ExprKind::Call: {
            const ExprCall& call = cast<ExprCall>(e);
            if (TopLevelFunc* const* found = funcTemplates.get(call.callee)) {
                const FuncDecl& decl = (*found)->decl;
                std::optional<std::vector<TypeRef>> elementTypes = resolveElementTypes(call);
                if (!elementTypes) return TypeRef{};
                return named(arena, returnedVariadicPackName(decl).value_or(call.callee), *elementTypes);
            }
            if (packTemplates.contains(call.callee)) {
                std::optional<std::vector<TypeRef>> elementTypes = resolveElementTypes(call);
                return elementTypes ? TypeRef(named(arena, call.callee, *elementTypes)) : TypeRef{};
            }
            if (constructibleTypes.contains(call.callee)) return named(arena, call.callee, call.typeArgs);
            if (const CallableReturn* callable = functionReturns.get(call.callee)) return resolveCallableReturn(*callable, call.typeArgs);
            return TypeRef{};
        }
        case ExprKind::MethodCall: {
            const ExprMethodCall& call = cast<ExprMethodCall>(e);
            TypeRef target = inferExprType(call.target);
            const TypeRefNamed* receiver = as<TypeRefNamed>(target);
            if (!receiver) return TypeRef{};
            auto callable = methodReturns.find({substringBefore(receiver->name, "<"), call.name});
            if (callable == methodReturns.end()) return TypeRef{};
            return resolveCallableReturn(callable->second, receiver->args);
        }
        case ExprKind::StringTemplate: return named(arena, "String");
        default: return TypeRef{};
    }
}

TypeRef MonoContext::inferBinaryType(const ExprBinary& expr) {
    switch (expr.op) {
        case TokenType::EQUAL_EQUAL:
        case TokenType::BANG_EQUAL:
        case TokenType::LESS:
        case TokenType::LESS_EQUAL:
        case TokenType::GREATER:
        case TokenType::GREATER_EQUAL:
        case TokenType::AND_AND:
        case TokenType::OR_OR: return named(arena, "Bool");
        default: break;
    }
    TypeRef leftType = inferExprType(expr.left);
    const TypeRefNamed* left = as<TypeRefNamed>(leftType);
    if (!left) return TypeRef{};
    TypeRef rightType = inferExprType(expr.right);
    const TypeRefNamed* right = as<TypeRefNamed>(rightType);
    if (!right) return TypeRef{};
    if (kotlinEquals(leftType, rightType)) return leftType;
    if (expr.op == TokenType::PLUS && (left->name == "String" || right->name == "String")) return named(arena, "String");
    auto rank = [](const std::string& name) -> int {
        static const std::unordered_map<std::string, int> ranks = {
            {"Byte", 0}, {"UByte", 0}, {"Short", 1}, {"UShort", 1}, {"Int", 2},    {"UInt", 2},   {"Long", 3},
            {"ULong", 3}, {"Cent", 4}, {"UCent", 4}, {"Float", 5}, {"Double", 6}, {"Quad", 7},
        };
        auto found = ranks.find(name);
        return found == ranks.end() ? -1 : found->second;
    };
    int leftRank = rank(left->name);
    if (leftRank < 0) return TypeRef{};
    int rightRank = rank(right->name);
    if (rightRank < 0) return TypeRef{};
    return leftRank >= rightRank ? leftType : rightType;
}

TypeRef MonoContext::resolveCallableReturn(const CallableReturn& callable, const std::vector<TypeRef>& typeArgs) {
    if (callable.typeParams.empty() || typeArgs.empty()) return callable.returnType;
    return substituteTypeParams(callable.returnType, zipToMap(callable.typeParams, typeArgs));
}

TypeRef MonoContext::substituteTypeParams(const TypeRef& type, const TypeMap& substitutions) {
    if (const TypeRefNamed* n = as<TypeRefNamed>(type)) {
        if (const TypeRef* value = substitutions.get(n->name)) return *value;
        std::vector<TypeRef> args;
        for (const TypeRef& arg : n->args) args.push_back(substituteTypeParams(arg, substitutions));
        return edit<TypeRefNamed>(arena, type, [&](TypeRefNamed& c) { c.args = std::move(args); });
    }
    return mapChildren(
        arena, type, [&](const TypeRef& t) { return substituteTypeParams(t, substitutions); }, asWritten, true);
}

TypeRef MonoContext::tupleElementType(const TypeRef& type, int32_t index) {
    const std::vector<TypeRef>* elements = nullptr;
    if (const TypeRefTuple* tuple = as<TypeRefTuple>(type)) {
        elements = &tuple->elements;
    } else if (const TypeRefNamed* n = as<TypeRefNamed>(type)) {
        if (packTemplates.contains(n->name) && !n->args.empty()) elements = &n->args;
    }
    if (!elements || index < 0 || (size_t)index >= elements->size()) return TypeRef{};
    return (*elements)[(size_t)index];
}

void MonoContext::recordBinding(const std::string& name, const TypeAnnotation& type, const Expr& initializer) {
    TypeRef t = type.isExplicit() ? type.ref : inferExprType(initializer);
    if (t) bindings.set(name, t);
}

Stmt MonoContext::rewriteStmt(const Stmt& s) {
    auto exprOpt = [&](const Expr& e) { return e ? rewriteExpr(e) : e; };
    auto stmts = [&](const std::vector<Stmt>& body) {
        std::vector<Stmt> out;
        out.reserve(body.size());
        for (const Stmt& each : body) out.push_back(rewriteStmt(each));
        return out;
    };
    auto stmtsOpt = [&](const std::optional<std::vector<Stmt>>& body) {
        return body ? std::optional<std::vector<Stmt>>(stmts(*body)) : std::nullopt;
    };
    auto exprs = [&](const std::vector<Expr>& values) {
        std::vector<Expr> out;
        out.reserve(values.size());
        for (const Expr& v : values) out.push_back(rewriteExpr(v));
        return out;
    };
    auto declaration = [&](auto& c) {
        recordBinding(c.name, c.type, c.initializer);
        c.type = rewriteTypeAnnotation(c.type);
        c.initializer = rewriteExpr(c.initializer);
    };
    auto branch = [&](auto& c) {
        c.condition = rewriteExpr(c.condition);
        c.thenBranch = stmts(c.thenBranch);
        c.elseBranch = stmtsOpt(c.elseBranch);
    };
    switch (s.kind()) {
        case StmtKind::Import: return s;
        case StmtKind::VarDecl: return edit<StmtVarDecl>(arena, s, declaration);
        case StmtKind::FinDecl: return edit<StmtFinDecl>(arena, s, declaration);
        case StmtKind::LetDecl: return edit<StmtLetDecl>(arena, s, declaration);
        case StmtKind::InlineVar: return edit<StmtInlineVar>(arena, s, declaration);
        case StmtKind::InlineFin: return edit<StmtInlineFin>(arena, s, declaration);
        case StmtKind::InlineLet: return edit<StmtInlineLet>(arena, s, declaration);
        case StmtKind::RemDecl: return edit<StmtRemDecl>(arena, s, declaration);
        case StmtKind::Assignment: return edit<StmtAssignment>(arena, s, [&](auto& c) { c.value = rewriteExpr(c.value); });
        case StmtKind::InlineAssignment: return edit<StmtInlineAssignment>(arena, s, [&](auto& c) { c.value = rewriteExpr(c.value); });
        case StmtKind::Exchange:
            return edit<StmtExchange>(arena, s, [&](auto& c) {
                c.left = rewriteExpr(c.left);
                c.right = rewriteExpr(c.right);
            });
        case StmtKind::IndexAssign:
            return edit<StmtIndexAssign>(arena, s, [&](auto& c) {
                c.target = rewriteExpr(c.target);
                c.index = rewriteExpr(c.index);
                c.value = rewriteExpr(c.value);
            });
        case StmtKind::MemberAssign:
            return edit<StmtMemberAssign>(arena, s, [&](auto& c) {
                c.target = rewriteExpr(c.target);
                c.value = rewriteExpr(c.value);
            });
        case StmtKind::DerefAssign:
            return edit<StmtDerefAssign>(arena, s, [&](auto& c) {
                c.target = rewriteExpr(c.target);
                c.value = rewriteExpr(c.value);
            });
        case StmtKind::Return: return edit<StmtReturn>(arena, s, [&](auto& c) { c.value = exprOpt(c.value); });
        case StmtKind::ExprStmt: return edit<StmtExprStmt>(arena, s, [&](auto& c) { c.expr = rewriteExpr(c.expr); });
        case StmtKind::Throw: return edit<StmtThrow>(arena, s, [&](auto& c) { c.value = rewriteExpr(c.value); });
        case StmtKind::Panic: return edit<StmtPanic>(arena, s, [&](auto& c) { c.message = rewriteExpr(c.message); });
        case StmtKind::Yield: return edit<StmtYield>(arena, s, [&](auto& c) { c.value = rewriteExpr(c.value); });
        case StmtKind::Assert:
            return edit<StmtAssert>(arena, s, [&](auto& c) {
                c.condition = rewriteExpr(c.condition);
                c.message = rewriteExpr(c.message);
            });
        case StmtKind::InlineAssert:
            return edit<StmtInlineAssert>(arena, s, [&](auto& c) {
                c.condition = rewriteExpr(c.condition);
                c.message = rewriteExpr(c.message);
            });
        case StmtKind::Trace:
            return edit<StmtTrace>(arena, s, [&](auto& c) {
                c.message = rewriteExpr(c.message);
                c.level = exprOpt(c.level);
            });
        case StmtKind::InlineTrace:
            return edit<StmtInlineTrace>(arena, s, [&](auto& c) {
                c.message = rewriteExpr(c.message);
                c.level = exprOpt(c.level);
            });
        case StmtKind::If: return edit<StmtIf>(arena, s, branch);
        case StmtKind::InlineIf: return edit<StmtInlineIf>(arena, s, branch);
        case StmtKind::DeepInlineIf: return edit<StmtDeepInlineIf>(arena, s, branch);
        case StmtKind::While:
            return edit<StmtWhile>(arena, s, [&](auto& c) {
                c.condition = rewriteExpr(c.condition);
                c.body = stmts(c.body);
            });
        case StmtKind::For:
            return edit<StmtFor>(arena, s, [&](auto& c) {
                c.iterable = rewriteExpr(c.iterable);
                c.step = exprOpt(c.step);
                c.body = stmts(c.body);
            });
        case StmtKind::InlineFor:
            return edit<StmtInlineFor>(arena, s, [&](auto& c) {
                c.iterable = rewriteExpr(c.iterable);
                c.body = stmts(c.body);
            });
        case StmtKind::Loop: return edit<StmtLoop>(arena, s, [&](auto& c) { c.body = stmts(c.body); });
        case StmtKind::When:
            return edit<StmtWhen>(arena, s, [&](auto& c) {
                c.scrutinee = rewriteExpr(c.scrutinee);
                std::vector<WhenBranch> branches;
                for (const WhenBranch& b : c.branches) {
                    WhenBranch copy = b;
                    copy.patterns = exprs(b.patterns);
                    copy.body = stmts(b.body);
                    branches.push_back(std::move(copy));
                }
                c.branches = std::move(branches);
                c.elseBranch = stmtsOpt(c.elseBranch);
            });
        case StmtKind::Try:
            return edit<StmtTry>(arena, s, [&](auto& c) {
                c.body = stmts(c.body);
                c.catchBody = stmtsOpt(c.catchBody);
            });
        case StmtKind::Defer: return edit<StmtDefer>(arena, s, [&](auto& c) { c.body = stmts(c.body); });
        case StmtKind::Scope: return edit<StmtScope>(arena, s, [&](auto& c) { c.body = stmts(c.body); });
        case StmtKind::InlineBlock: return edit<StmtInlineBlock>(arena, s, [&](auto& c) { c.body = stmts(c.body); });
        case StmtKind::DeepInlineBlock: return edit<StmtDeepInlineBlock>(arena, s, [&](auto& c) { c.body = stmts(c.body); });
        case StmtKind::Effect:
            return edit<StmtEffect>(arena, s, [&](auto& c) {
                if (c.dependencies) c.dependencies = exprs(*c.dependencies);
                c.body = stmts(c.body);
            });
        case StmtKind::UsingContext:
            return edit<StmtUsingContext>(arena, s, [&](auto& c) {
                c.values = exprs(c.values);
                c.body = stmts(c.body);
            });
        case StmtKind::NoInline: return edit<StmtNoInline>(arena, s, [&](auto& c) { c.stmt = rewriteStmt(c.stmt); });
        case StmtKind::Break: return edit<StmtBreak>(arena, s, [&](auto& c) { c.value = exprOpt(c.value); });
        case StmtKind::Continue: return s;
        default: AZORA_UNREACHABLE();
    }
}

}  // namespace azora
