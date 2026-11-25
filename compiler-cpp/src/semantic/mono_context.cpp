// Port of VariadicMonomorphizer.kt's MonoContext, first half: instantiation of
// packs and functions, const-generic and static members, impl specialization,
// and the expansion of loops over reflected fields.
#include "semantic/mono_context.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/lexer.h"
#include "frontend/parser.h"
#include "semantic/constraints.h"
#include "semantic/decorator_metadata.h"
#include "semantic/variadic_monomorphizer.h"

#include <algorithm>

namespace azora {

using namespace mono;

namespace mono {

TypeMap zipToMap(const std::vector<std::string>& names, const std::vector<TypeRef>& types) {
    TypeMap out;
    size_t n = std::min(names.size(), types.size());
    for (size_t i = 0; i < n; i++) out.set(names[i], types[i]);
    return out;
}

TypeMap plus(TypeMap a, const TypeMap& b) {
    for (const auto& [name, ref] : b) a.set(name, ref);
    return a;
}

OrderedMap<std::string, int64_t> constsOf(const TypeMap& bindings) {
    OrderedMap<std::string, int64_t> out;
    for (const auto& [name, ref] : bindings) {
        if (const TypeRefConst* c = as<TypeRefConst>(ref)) out.set(name, c->value);
    }
    return out;
}

}  // namespace mono

namespace {

/** TypeRef.Const.unresolved: a variant whose enum has not been seen yet. */
bool isUnresolved(const TypeRef& ref) {
    const TypeRefConst* c = as<TypeRefConst>(ref);
    return c && c->value == INT64_MIN && c->label;
}

using Entries = std::vector<std::pair<std::string, std::vector<TypeRef>>>;

/** The specializations of [owner] made so far (Kotlin's filtered snapshot of packArguments). */
Entries specializationsOf(const OrderedMap<std::string, std::vector<TypeRef>>& packArguments, const std::string& owner) {
    Entries out;
    std::string prefix = "__" + owner + "_";
    for (const auto& [mangled, arguments] : packArguments) {
        if (startsWith(mangled, prefix)) out.emplace_back(mangled, arguments);
    }
    return out;
}

/** Kotlin `params.map { it.copy(type = f(it.type)) }`. */
template <class F>
std::vector<Param> mapParamTypes(const std::vector<Param>& params, F&& f) {
    std::vector<Param> out;
    out.reserve(params.size());
    for (const Param& p : params) {
        Param c = p;
        c.type = f(p.type);
        out.push_back(std::move(c));
    }
    return out;
}

/** The variadic parameter of [typeParams] bound to the arguments it stands for, when there are enough. */
OrderedMap<std::string, std::vector<TypeRef>> tailPack(const std::vector<std::string>& typeParams,
                                                       const std::vector<TypeRef>& arguments) {
    OrderedMap<std::string, std::vector<TypeRef>> out;
    if (!typeParams.empty() && arguments.size() >= typeParams.size()) {
        out.set(typeParams.back(), std::vector<TypeRef>(arguments.begin() + (std::ptrdiff_t)(typeParams.size() - 1), arguments.end()));
    }
    return out;
}

void append(std::vector<Stmt>& out, const std::vector<Stmt>& more) { out.insert(out.end(), more.begin(), more.end()); }

}  // namespace

// ---------------------------------------------------------------------------
// Instantiation
// ---------------------------------------------------------------------------

std::vector<TypeRef> MonoContext::resolveVariantArguments(const std::vector<TypeRef>& arguments) {
    if (std::none_of(arguments.begin(), arguments.end(), isUnresolved)) return arguments;
    std::vector<TypeRef> out;
    out.reserve(arguments.size());
    for (const TypeRef& argument : arguments) {
        if (!isUnresolved(argument)) {
            out.push_back(argument);
            continue;
        }
        const std::string& variant = *cast<TypeRefConst>(argument).label;
        std::vector<std::string> owners;
        const std::vector<std::string>* only = nullptr;
        for (const auto& [name, variants] : enumVariants) {
            if (contains(variants, variant)) {
                owners.push_back(name);
                if (!only) only = &variants;
            }
        }
        if (owners.size() == 1) {
            int64_t position = std::find(only->begin(), only->end(), variant) - only->begin();
            out.push_back(make(arena, TypeRefConst{position, variant}));
        } else if (owners.empty()) {
            fail("no declared enum has a variant '." + variant + "'");
        } else {
            std::sort(owners.begin(), owners.end(),
                      [](const std::string& a, const std::string& b) { return compareUtf16(a, b) < 0; });
            fail("'." + variant + "' is ambiguous between " + join(owners, ", ") + "; qualify it, as in '" + owners[0] + "." +
                 variant + "'");
        }
    }
    return out;
}

void MonoContext::checkConstraints(const std::string& what, const Expr& clause, const std::optional<std::string>& variadicParam,
                                   const std::vector<TypeRef>& args, const TopLevelPack* declaration /*= nullptr*/) {
    if (!clause) return;
    std::vector<std::string> params;
    if (declaration) {
        params = declaration->typeParams;
    } else if (variadicParam) {
        params = {*variadicParam};
    }
    ConstraintEvaluator::Bindings bound = ConstraintEvaluator::bindingsFor(arena, params, variadicParam, args);
    if (bound.empty()) return;
    ConstraintEvaluator::Outcome outcome = ConstraintEvaluator::evaluate(arena, clause, bound, nullptr);
    if (outcome.kind == ConstraintEvaluator::Outcome::Kind::Violated) {
        fail(what + " does not satisfy its 'where' clause: " + outcome.text);
    }
}

std::string MonoContext::instantiatePack(const std::string& templateName, const std::vector<TypeRef>& supplied) {
    TopLevelPack* const* found = packTemplates.get(templateName);
    if (!found) return templateName;
    const TopLevelPack& tpl = **found;
    // A const parameter the use site omitted takes its declared default.
    std::vector<TypeRef> args = resolveVariantArguments(specializer.withDefaults(tpl, supplied));
    // `Vec<T, N>` inside another generic has chosen no layout, so it stays on the template.
    if (!tpl.variadicParam && !specializer.keyForRefs(tpl, args)) return templateName;
    std::string mangled = MonoNames::mangle(templateName, args);
    // A specialization emitted by an earlier run is already a declared pack.
    if (!packs.contains(mangled) && !plainPackFields.contains(mangled)) {
        checkConstraints(tpl.variadicParam ? "variadic pack '" + templateName + "'" : "'" + templateName + "'", tpl.whereClause,
                         tpl.variadicParam, args, &tpl);
        std::vector<TypeRef> concreteArgs;
        concreteArgs.reserve(args.size());
        for (const TypeRef& arg : args) concreteArgs.push_back(rewriteType(arg));
        packArguments.set(mangled, concreteArgs);
        TopLevelPack* pack = expandPack(mangled, tpl, concreteArgs);
        packs.set(mangled, pack);
        if (const std::string* ns = typeNamespaces.get(templateName)) generatedPackNamespaces.set(mangled, *ns);
    }
    return mangled;
}

std::string MonoContext::instantiateFunc(const std::string& templateName, const std::vector<TypeRef>& elementTypes) {
    TopLevelFunc* const* found = funcTemplates.get(templateName);
    if (!found) return templateName;
    const FuncDecl& decl = (*found)->decl;
    if (!decl.variadicParam && elementTypes.size() != decl.typeParams.size()) return templateName;
    std::string mangled = MonoNames::mangle(templateName, elementTypes);
    if (!funcs.contains(mangled)) {
        if (!decl.variadicParam) {
            TypeMap savedTypes = typeBindings;
            OrderedMap<std::string, int64_t> savedConsts = constBindings;
            TypeMap savedValues = bindings;
            TypeMap substitutions = zipToMap(decl.typeParams, elementTypes);
            FuncDecl concrete = decl;
            concrete.name = mangled;
            concrete.params = mapParamTypes(decl.params, [&](const TypeRef& t) { return substituteParams(t, substitutions); });
            concrete.returnType = substituteParams(decl.returnType, substitutions);
            concrete.typeParams = {};
            concrete.whereClause = Expr{};
            FuncDecl placeholder = concrete;
            placeholder.body = {};
            funcs.set(mangled, make(arena, TopLevelFunc{placeholder}));
            {
                Finally restore{[&] {
                    typeBindings = savedTypes;
                    constBindings = savedConsts;
                    bindings = savedValues;
                }};
                typeBindings = plus(savedTypes, substitutions);
                constBindings = constsOf(substitutions);
                FuncDecl rewritten = rewriteFuncDecl(concrete);
                funcs.set(mangled, make(arena, TopLevelFunc{rewritten}));
            }
            return mangled;
        }
        checkConstraints("variadic function '" + templateName + "'", decl.whereClause, decl.variadicParam, elementTypes);
        std::string packName = returnedVariadicPackName(decl).value_or(templateName);
        std::string packMangled = instantiatePack(packName, elementTypes);
        TopLevelFunc* func = expandFunc(mangled, decl, elementTypes, packMangled);
        funcs.set(mangled, func);
    }
    return mangled;
}

std::optional<std::string> MonoContext::returnedVariadicPackName(const FuncDecl& decl) const {
    const TypeRefNamed* ref = as<TypeRefNamed>(decl.returnType.ref);
    if (ref && ref->variadic && packTemplates.contains(ref->name)) return ref->name;
    return std::nullopt;
}

TypeRef MonoContext::substituteParams(const TypeRef& ref, const TypeMap& bound) {
    // A variadic parameter is replaced by every argument it stands for.
    if (const TypeRefNamed* n = as<TypeRefNamed>(ref)) {
        if (const TypeRef* value = bound.get(n->name)) return *value;
        std::vector<TypeRef> args;
        for (const TypeRef& arg : spreadPacks(n->args)) args.push_back(substituteParams(arg, bound));
        return edit<TypeRefNamed>(arena, ref, [&](TypeRefNamed& c) { c.args = std::move(args); });
    }
    return mapChildren(
        arena, ref, [&](const TypeRef& t) { return substituteParams(t, bound); },
        [&](const std::vector<TypeRef>& ts) { return spreadPacks(ts); }, false);
}

TypeAnnotation MonoContext::substituteParams(const TypeAnnotation& annotation, const TypeMap& bound) {
    return annotation.isExplicit() ? explicitType(substituteParams(annotation.ref, bound)) : annotation;
}

TopLevelPack* MonoContext::expandPack(const std::string& mangled, const TopLevelPack& tpl, const std::vector<TypeRef>& args) {
    bool enforce = std::any_of(tpl.annotations.begin(), tpl.annotations.end(),
                               [](const Annotation& a) { return a.name == "EnforceNumFields"; });
    // A variadic pack generates its fields from the element types; a pack with
    // conditional fields selects from its own, through PackSpecializer.
    std::vector<PackField> fields;
    if (tpl.variadicParam) {
        fields = expandFields(tpl, args);
    } else {
        std::optional<PackSpecializer::Key> key = specializer.keyForRefs(tpl, args);
        if (!key) {
            for (const PackField& f : tpl.fields) {
                PackField c = f;
                c.type = rewriteType(f.type);
                fields.push_back(std::move(c));
            }
        } else {
            TypeMap substitution = zipToMap(tpl.typeParams, args);
            std::vector<PackField> specialized = specializer.specializeForRefs(*key, tpl, args).fields;
            for (const PackField& f : specialized) {
                PackField c = f;
                c.type = rewriteType(substituteParams(f.type, substitution));
                c.condition = Expr{};
                fields.push_back(std::move(c));
            }
        }
    }
    TopLevelPack pack;
    pack.name = mangled;
    pack.fields = std::move(fields);
    pack.line = tpl.line;
    pack.column = tpl.column;
    if (enforce) {
        Annotation annotation;
        annotation.name = "EnforceNumFields";
        pack.annotations = {annotation};
    } else {
        pack.annotations = tpl.annotations;
    }
    pack.visibility = tpl.visibility;
    return make(arena, std::move(pack));
}

std::vector<PackField> MonoContext::expandFields(const TopLevelPack& tpl, const std::vector<TypeRef>& args) {
    std::vector<PackField> out;
    for (const PackField& f : tpl.fields) {
        PackField c = f;
        c.type = rewriteType(f.type);
        out.push_back(std::move(c));
    }
    if (!tpl.fieldTemplate) return out;
    const VariadicFieldTemplate& t = *tpl.fieldTemplate;
    for (size_t i = 0; i < args.size(); i++) {
        const TypeRef& argType = args[i];
        for (const TplField& f : t.fields) {
            std::string name = startsWith(f.name, "$index") ? str((int)i) : f.name;
            TypeRef type = rewriteType(substituteLoopVar(f.type, t.loopVar, argType));
            out.push_back(PackField{name, type, true, Expr{}});
        }
        // `mixin "$index: $Ty"` - interpolate the comptime bindings, parse as a field.
        for (const ExprStringTemplate* mixin : t.mixins) {
            PackField field = parseMixinField(renderMixin(*mixin, t, (int32_t)i, argType));
            TypeRef type = rewriteType(field.type);
            out.push_back(PackField{field.name, type, true, Expr{}});
        }
    }
    return out;
}

std::string MonoContext::renderMixin(const ExprStringTemplate& tpl, const VariadicFieldTemplate& fieldTemplate, int32_t index,
                                     const TypeRef& argType) {
    std::string out;
    for (const StringTemplatePart& part : tpl.parts) {
        if (part.isLiteral) {
            out += part.text;
            continue;
        }
        const ExprIdentifier* id = as<ExprIdentifier>(part.expr);
        if (id && id->name == "index") {
            out += str(index);
        } else if (id && id->name == fieldTemplate.loopVar) {
            out += renderType(argType);
        } else {
            fail("mixin references unknown comptime '$" + (id ? id->name : std::string("null")) + "' in variadic template");
        }
    }
    return out;
}

std::string MonoContext::renderType(const TypeRef& type) {
    auto joined = [&](const std::vector<TypeRef>& types, const char* separator) {
        std::string out;
        for (size_t i = 0; i < types.size(); i++) {
            if (i > 0) out += separator;
            out += renderType(types[i]);
        }
        return out;
    };
    switch (type.kind()) {
        case TypeRefKind::Named: {
            const TypeRefNamed& n = cast<TypeRefNamed>(type);
            return n.args.empty() ? n.name : n.name + "<" + joined(n.args, ", ") + ">";
        }
        case TypeRefKind::Array: return "[" + renderType(cast<TypeRefArray>(type).element) + "]";
        case TypeRefKind::Map: {
            const TypeRefMap& m = cast<TypeRefMap>(type);
            return "Map<" + renderType(m.key) + ", " + renderType(m.value) + ">";
        }
        case TypeRefKind::Set: return "Set<" + renderType(cast<TypeRefSet>(type).element) + ">";
        case TypeRefKind::Tuple: return "(" + joined(cast<TypeRefTuple>(type).elements, ", ") + ")";
        case TypeRefKind::Nullable: return renderType(cast<TypeRefNullable>(type).inner) + "?";
        case TypeRefKind::Pointer: return renderType(cast<TypeRefPointer>(type).inner) + "*";
        case TypeRefKind::Function: {
            const TypeRefFunction& f = cast<TypeRefFunction>(type);
            std::string out = surfaceName(f.kind);
            if (!f.receivers.empty()) out += "[" + joined(f.receivers, ", ") + "]";
            if (!f.params.empty()) out += "(" + joined(f.params, ", ") + ")";
            out += " -> ";
            out += renderType(f.ret);
            return out;
        }
        case TypeRefKind::Failable: {
            const TypeRefFailable& f = cast<TypeRefFailable>(type);
            if (f.errSets.size() == 1) return renderType(f.ok) + "!" + f.errSets[0];
            return renderType(f.ok) + "![" + join(f.errSets, ", ") + "]";
        }
        // Re-parsed, so a borrow is written the way the language spells one.
        case TypeRefKind::Reference: {
            const TypeRefReference& r = cast<TypeRefReference>(type);
            std::string out = renderType(r.inner);
            out += r.kind == RefKind::MUTABLE ? '!' : '&';
            if (!r.origins.empty()) out += "|" + join(r.origins, ", ") + "|";
            return out;
        }
        case TypeRefKind::Const: return str((long long)cast<TypeRefConst>(type).value);
        default: AZORA_UNREACHABLE();
    }
}

PackField MonoContext::parseMixinField(const std::string& rendered) {
    std::string source = "@EnforceNumFields\npack __mixin { " + rendered + " }";
    Parser parser(arena, Lexer(source).tokenize(), nullptr, nullptr, true);
    Program program = parser.parse();
    for (const TopLevel& item : program.items) {
        if (const TopLevelPack* pack = as<TopLevelPack>(item)) return first(pack->fields);
    }
    fail("mixin '" + rendered + "' did not produce a field");
}

TypeRef MonoContext::substituteLoopVar(const TypeRef& type, const std::string& loopVar, const TypeRef& replacement) {
    if (const TypeRefNamed* n = as<TypeRefNamed>(type)) {
        if (n->name == loopVar && n->args.empty()) return replacement;
        std::vector<TypeRef> args;
        for (const TypeRef& arg : n->args) args.push_back(substituteLoopVar(arg, loopVar, replacement));
        return edit<TypeRefNamed>(arena, type, [&](TypeRefNamed& c) { c.args = std::move(args); });
    }
    return mapChildren(
        arena, type, [&](const TypeRef& t) { return substituteLoopVar(t, loopVar, replacement); }, asWritten, true);
}

TopLevelFunc* MonoContext::expandFunc(const std::string& mangled, const FuncDecl& tpl, const std::vector<TypeRef>& elementTypes,
                                      const std::string& packMangled) {
    std::vector<Param> params;
    for (size_t i = 0; i < elementTypes.size(); i++) {
        Param p;
        p.name = "_" + str((int)i);
        p.type = rewriteType(elementTypes[i]);
        params.push_back(std::move(p));
    }
    std::vector<Expr> ctorArgs;
    for (size_t i = 0; i < elementTypes.size(); i++) {
        ctorArgs.push_back(make(arena, ExprIdentifier{"_" + str((int)i), tpl.line, tpl.column, 2}));
    }
    ExprCall call;
    call.callee = packMangled;
    call.args = std::move(ctorArgs);
    call.line = tpl.line;
    call.column = tpl.column;
    call.length = 1;
    StmtReturn ret;
    ret.value = make(arena, std::move(call));
    ret.line = tpl.line;
    ret.column = tpl.column;
    ret.length = 0;
    FuncDecl decl;
    decl.name = mangled;
    decl.params = std::move(params);
    decl.returnType = explicitType(named(arena, packMangled));
    decl.body = {make(arena, std::move(ret))};
    decl.isInline = false;
    decl.line = tpl.line;
    decl.column = tpl.column;
    return make(arena, TopLevelFunc{decl});
}

// ---------------------------------------------------------------------------
// Reflection on ordinary packs; const-generic members
// ---------------------------------------------------------------------------

TopLevel MonoContext::expandPlainImplReflection(const TopLevel& item,
                                                const std::unordered_map<std::string, TopLevelPack*>& firstPacks) {
    // A free function or a test has no `Self`, but `reflect<Point>.fields` names its pack outright.
    if (const TopLevelFunc* func = as<TopLevelFunc>(item)) {
        if (funcTemplates.contains(func->decl.name)) return item;
        std::vector<Stmt> body = expandReflectedFields(func->decl.body, {});
        return edit<TopLevelFunc>(arena, item, [&](TopLevelFunc& c) { c.decl.body = std::move(body); });
    }
    if (is<TopLevelTest>(item)) {
        std::vector<Stmt> body = expandReflectedFields(cast<TopLevelTest>(item).body, {});
        return edit<TopLevelTest>(arena, item, [&](TopLevelTest& c) { c.body = std::move(body); });
    }
    const TopLevelImpl* impl = as<TopLevelImpl>(item);
    if (!impl) return item;
    if (packTemplates.contains(impl->typeName)) return item;
    auto pack = firstPacks.find(impl->typeName);
    if (pack == firstPacks.end()) return item;
    const std::vector<PackField>& fields = pack->second->fields;
    if (fields.empty()) return item;
    std::vector<FuncDecl> methods;
    for (const FuncDecl& method : impl->methods) {
        FuncDecl c = method;
        c.body = expandReflectedFields(method.body, fields);
        methods.push_back(std::move(c));
    }
    return edit<TopLevelImpl>(arena, item, [&](TopLevelImpl& c) { c.methods = std::move(methods); });
}

TopLevel MonoContext::expandConstGenericMembers(const TopLevel& item) {
    const TopLevelImpl* impl = as<TopLevelImpl>(item);
    if (!impl) return item;
    // A member of a monomorphised pack is expanded by expandImpls instead.
    if (packTemplates.contains(impl->typeName)) return item;
    if (std::none_of(impl->methods.begin(), impl->methods.end(), [](const FuncDecl& m) { return !m.constParams.empty(); })) {
        return item;
    }
    std::vector<FuncDecl> methods;
    for (const FuncDecl& method : impl->methods) {
        if (method.constParams.empty()) {
            methods.push_back(method);
            continue;
        }
        TypeRefNamed* application = nullptr;
        for (TypeRefNamed* candidate : signatureApplications(method)) {
            if (!packTemplates.contains(candidate->name)) continue;
            bool mentions = std::any_of(candidate->args.begin(), candidate->args.end(), [&](const TypeRef& arg) {
                const TypeRefNamed* n = as<TypeRefNamed>(arg);
                return n && method.constParams.contains(n->name);
            });
            if (mentions) {
                application = candidate;
                break;
            }
        }
        if (!application) {
            methods.push_back(method);
            continue;
        }
        const TopLevelPack& tpl = **packTemplates.get(application->name);
        // With no specialization to bind it, the member cannot be emitted at all.
        for (const auto& [mangled, concrete] : specializationsOf(packArguments, application->name)) {
            std::optional<TypeMap> bound = matchApplication(*application, tpl, concrete);
            if (!bound) continue;
            bound->set(application->name, named(arena, mangled));
            methods.push_back(specializeConstGenericMember(method, *bound, mangled));
        }
    }
    return edit<TopLevelImpl>(arena, item, [&](TopLevelImpl& c) { c.methods = std::move(methods); });
}

std::vector<TypeRefNamed*> MonoContext::signatureApplications(const FuncDecl& method) {
    std::vector<TypeRefNamed*> found;
    std::function<void(const TypeRef&)> visit = [&](const TypeRef& ref) {
        switch (ref.kind()) {
            case TypeRefKind::Named: {
                TypeRefNamed* n = as<TypeRefNamed>(ref);
                if (!n->args.empty()) found.push_back(n);
                for (const TypeRef& arg : n->args) visit(arg);
                break;
            }
            case TypeRefKind::Reference: visit(cast<TypeRefReference>(ref).inner); break;
            case TypeRefKind::Pointer: visit(cast<TypeRefPointer>(ref).inner); break;
            case TypeRefKind::Nullable: visit(cast<TypeRefNullable>(ref).inner); break;
            case TypeRefKind::Failable: visit(cast<TypeRefFailable>(ref).ok); break;
            case TypeRefKind::Array: visit(cast<TypeRefArray>(ref).element); break;
            default: break;
        }
    };
    for (const Param& p : method.params) visit(p.type);
    if (method.returnType.isExplicit()) visit(method.returnType.ref);
    return found;
}

std::optional<TypeMap> MonoContext::matchApplication(const TypeRefNamed& application, const TopLevelPack& tpl,
                                                     const std::vector<TypeRef>& concrete) {
    if (application.args.size() != concrete.size()) return std::nullopt;
    TypeMap bound;
    for (size_t index = 0; index < application.args.size(); index++) {
        const TypeRef& argument = application.args[index];
        const TypeRef& actual = concrete[index];
        const TypeRefNamed* n = as<TypeRefNamed>(argument);
        std::optional<std::string> name;
        if (n && n->args.empty()) name = n->name;
        if (name && tpl.constParams.contains(*name)) continue;
        if (name) {
            const TypeRef* existing = bound.get(*name);
            if (!existing || kotlinEquals(*existing, actual)) {
                bound.set(*name, actual);
                continue;
            }
        }
        if (toString(argument) != toString(actual)) return std::nullopt;
    }
    // A const parameter of the *member* takes its value from the same position.
    for (size_t index = 0; index < tpl.typeParams.size(); index++) {
        if (!tpl.constParams.contains(tpl.typeParams[index])) continue;
        if (index >= application.args.size()) continue;
        const TypeRefNamed* argument = as<TypeRefNamed>(application.args[index]);
        if (!argument) continue;
        if (argument->args.empty()) bound.set(argument->name, concrete[index]);
    }
    return bound;
}

FuncDecl MonoContext::specializeConstGenericMember(const FuncDecl& method, const TypeMap& bound, const std::string& mangled) {
    constBindings = constsOf(bound);
    typeBindings = bound;
    Finally reset{[&] {
        constBindings = {};
        typeBindings = {};
    }};
    FuncDecl copy = method;
    copy.params = mapParamTypes(method.params, [&](const TypeRef& t) { return substituteParams(t, bound); });
    copy.returnType = substituteParams(method.returnType, bound);
    copy.typeParams.clear();
    for (const std::string& p : method.typeParams) {
        if (!bound.contains(p)) copy.typeParams.push_back(p);
    }
    copy.constParams = {};
    for (const std::string& p : method.constParams) {
        if (!bound.contains(p)) copy.constParams.add(p);
    }
    std::vector<PackField> fields;
    if (TopLevelPack* const* pack = packs.get(mangled)) fields = (*pack)->fields;
    copy.body = expandReflectedFields(method.body, fields);
    // Operand-keyed, so the copies coexist on the same receiver.
    return rekeyOperator(rewriteFuncDecl(copy, mangled));
}

FuncDecl MonoContext::rekeyOperator(const FuncDecl& method) {
    if (!startsWith(method.name, "oper") || !contains(method.name, '@')) return method;
    if (method.params.size() != 1) return method;
    std::optional<std::string> key = operandKey(method.params[0].type);
    if (!key) return method;
    FuncDecl copy = method;
    copy.name = substringBefore(method.name, "@") + "@" + *key;
    return copy;
}

std::optional<std::string> MonoContext::operandKey(const TypeRef& type) {
    switch (type.kind()) {
        case TypeRefKind::Named: return cast<TypeRefNamed>(type).name;
        case TypeRefKind::Reference: return operandKey(cast<TypeRefReference>(type).inner);
        case TypeRefKind::Pointer: return operandKey(cast<TypeRefPointer>(type).inner);
        case TypeRefKind::Nullable: return operandKey(cast<TypeRefNullable>(type).inner);
        default: return std::nullopt;
    }
}

// ---------------------------------------------------------------------------
// Static members
// ---------------------------------------------------------------------------

std::optional<std::string> MonoContext::staticMemberOwner(const TopLevel& item) {
    const std::string* name = nullptr;
    switch (item.kind()) {
        case TopLevelKind::Func: name = &cast<TopLevelFunc>(item).decl.name; break;
        case TopLevelKind::FinDecl: name = &cast<TopLevelFinDecl>(item).name; break;
        case TopLevelKind::VarDecl: name = &cast<TopLevelVarDecl>(item).name; break;
        case TopLevelKind::LetDecl: name = &cast<TopLevelLetDecl>(item).name; break;
        default: return std::nullopt;
    }
    for (const auto& [owner, _] : packTemplates) {
        if (startsWith(*name, owner + "__")) return owner;
    }
    return std::nullopt;
}

bool MonoContext::isStaticTemplateMember(const TopLevel& item) { return staticMemberOwner(item).has_value(); }

TopLevel MonoContext::renameStaticMember(const TopLevel& item, const std::string& owner, const std::string& mangled) {
    auto rename = [&](const std::string& name) { return mangled + removePrefix(name, owner); };
    switch (item.kind()) {
        case TopLevelKind::Func: return edit<TopLevelFunc>(arena, item, [&](TopLevelFunc& c) { c.decl.name = rename(c.decl.name); });
        case TopLevelKind::FinDecl: return edit<TopLevelFinDecl>(arena, item, [&](TopLevelFinDecl& c) { c.name = rename(c.name); });
        case TopLevelKind::VarDecl: return edit<TopLevelVarDecl>(arena, item, [&](TopLevelVarDecl& c) { c.name = rename(c.name); });
        case TopLevelKind::LetDecl: return edit<TopLevelLetDecl>(arena, item, [&](TopLevelLetDecl& c) { c.name = rename(c.name); });
        default: return item;
    }
}

std::vector<TopLevel> MonoContext::expandStaticMembers(const std::vector<TopLevel>& templates) {
    std::vector<TopLevel> out;
    for (const TopLevel& tpl : templates) {
        std::optional<std::string> owner = staticMemberOwner(tpl);
        if (!owner) continue;
        TopLevelPack* const* found = packTemplates.get(*owner);
        if (!found) continue;
        const TopLevelPack& declaration = **found;
        for (const auto& [mangled, arguments] : specializationsOf(packArguments, *owner)) {
            TypeMap bound = zipToMap(declaration.typeParams, arguments);
            // A variadic parameter stands for *all* the remaining arguments.
            typePackBindings = tailPack(declaration.typeParams, arguments);
            TopLevel copy = renameStaticMember(substituteStaticMember(tpl, bound), *owner, mangled);
            constBindings = constsOf(bound);
            typeBindings = bound;
            // Inside a copy, the template's static names mean this specialization's members.
            std::optional<std::string> previousAlias;
            if (const std::string* previous = staticAliases.get(*owner)) previousAlias = *previous;
            staticAliases.set(*owner, mangled);
            TopLevel rewritten;
            {
                Finally reset{[&] {
                    constBindings = {};
                    typeBindings = {};
                    typePackBindings = {};
                    if (!previousAlias) {
                        staticAliases.remove(*owner);
                    } else {
                        staticAliases.set(*owner, *previousAlias);
                    }
                }};
                rewritten = rewriteTopLevel(copy);
            }
            if (rewritten) out.push_back(rewritten);
        }
    }
    return out;
}

TopLevel MonoContext::substituteStaticMember(const TopLevel& item, const TypeMap& bound) {
    auto type = [&](const TypeRef& t) { return t ? substituteParams(t, bound) : t; };
    switch (item.kind()) {
        case TopLevelKind::Func:
            return edit<TopLevelFunc>(arena, item, [&](TopLevelFunc& c) {
                c.decl.params = mapParamTypes(c.decl.params, [&](const TypeRef& t) { return substituteParams(t, bound); });
                c.decl.returnType = substituteParams(c.decl.returnType, bound);
                std::vector<std::string> typeParams;
                for (const std::string& p : c.decl.typeParams) {
                    if (!bound.contains(p)) typeParams.push_back(p);
                }
                c.decl.typeParams = std::move(typeParams);
            });
        case TopLevelKind::FinDecl: return edit<TopLevelFinDecl>(arena, item, [&](TopLevelFinDecl& c) { c.type = type(c.type); });
        case TopLevelKind::VarDecl: return edit<TopLevelVarDecl>(arena, item, [&](TopLevelVarDecl& c) { c.type = type(c.type); });
        case TopLevelKind::LetDecl: return edit<TopLevelLetDecl>(arena, item, [&](TopLevelLetDecl& c) { c.type = type(c.type); });
        default: return item;
    }
}

// ---------------------------------------------------------------------------
// Impls
// ---------------------------------------------------------------------------

std::vector<TopLevelImpl*> MonoContext::expandImpls() {
    // A snapshot at a time: expanding a member can specialize something new.
    std::vector<TopLevelImpl*> expanded;
    std::unordered_set<std::string> done;
    while (true) {
        Entries pending;
        for (const auto& [mangled, arguments] : packArguments) {
            if (!done.count(mangled)) pending.emplace_back(mangled, arguments);
        }
        if (pending.empty()) return expanded;
        for (const auto& entry : pending) done.insert(entry.first);
        for (const auto& [mangled, arguments] : pending) {
            for (TopLevelImpl* impl : expandImplsOf(mangled, arguments)) expanded.push_back(impl);
        }
    }
}

std::vector<TopLevelImpl*> MonoContext::expandImplsOf(const std::string& mangled, const std::vector<TypeRef>& arguments) {
    std::optional<std::string> templateName;
    for (const auto& [name, _] : packTemplates) {
        if (startsWith(mangled, "__" + name + "_")) {
            templateName = name;
            break;
        }
    }
    if (!templateName) return {};
    TopLevelPack* const* found = packs.get(mangled);
    if (!found) return {};
    TopLevelPack* pack = *found;
    std::vector<TopLevelImpl*> templates;
    if (const std::vector<TopLevelImpl*>* impls = implTemplates.get(*templateName)) templates = *impls;
    return specializeImpls(*templateName, mangled, named(arena, mangled), pack->fields, arguments, *packTemplates.get(*templateName),
                           templates);
}

std::vector<TopLevelImpl*> MonoContext::specializeImpls(const std::string& templateName, const std::string& owner,
                                                        const TypeRef& selfType, const std::vector<PackField>& fields,
                                                        const std::vector<TypeRef>& arguments, const TopLevelPack* packTemplate,
                                                        const std::vector<TopLevelImpl*>& templates) {
    const std::string& mangled = owner;
    std::vector<std::string> packParams = packTemplate ? packTemplate->typeParams : std::vector<std::string>{};
    // The specialization binds the pack's parameters, its own name and `Self`.
    TypeMap argumentBindings = zipToMap(packParams, arguments);
    argumentBindings.set(templateName, selfType);
    argumentBindings.set("Self", selfType);
    // A member's own `where` narrows which specializations have it.
    ConstraintEvaluator::Bindings constraintBindings = ConstraintEvaluator::bindingsFor(
        arena, packParams, packTemplate ? packTemplate->variadicParam : std::nullopt, arguments);
    std::vector<TopLevelImpl*> out;
    for (TopLevelImpl* tpl : templates) {
        std::vector<const FuncDecl*> kept;
        for (const FuncDecl& method : tpl->methods) {
            if (ConstraintEvaluator::evaluate(arena, method.whereClause, constraintBindings, nullptr).kind !=
                ConstraintEvaluator::Outcome::Kind::Violated) {
                kept.push_back(&method);
            }
        }
        std::vector<FuncDecl> methods;
        for (const FuncDecl* m : kept) {
            const FuncDecl& method = *m;
            // A member's own type parameters shadow the pack's - only the ones it really declares.
            std::unordered_set<std::string> ownTypeParams(method.typeParams.begin(), method.typeParams.end());
            for (const std::string& p : tpl->typeParams) ownTypeParams.erase(p);
            for (const std::string& p : packParams) ownTypeParams.erase(p);
            TypeMap bound;
            for (const auto& [name, ref] : argumentBindings) {
                if (!ownTypeParams.count(name)) bound.set(name, ref);
            }
            for (const auto& [name, ref] : tpl->assocBindings) bound.set(name, ref);
            constBindings = constsOf(argumentBindings);
            typeBindings = bound;
            typePackBindings = packTemplate ? tailPack(packParams, arguments) : OrderedMap<std::string, std::vector<TypeRef>>{};
            FuncDecl specialized = method;
            specialized.params = mapParamTypes(
                method.params, [&](const TypeRef& t) { return substituteParams(substituteSelf(t, selfType), bound); });
            specialized.returnType = substituteParams(substituteSelf(method.returnType, selfType), bound);
            specialized.body = expandReflectedFields(method.body, fields);
            specialized.typeParams.clear();
            for (const std::string& p : method.typeParams) {
                if ((tpl->variadicParam && p == *tpl->variadicParam) || argumentBindings.contains(p)) continue;
                specialized.typeParams.push_back(p);
            }
            if (method.variadicParam && tpl->variadicParam && *method.variadicParam == *tpl->variadicParam) {
                specialized.variadicParam = std::nullopt;
            }
            {
                Finally reset{[&] {
                    constBindings = {};
                    typeBindings = {};
                }};
                methods.push_back(rekeyOperator(rewriteFuncDecl(specialized, mangled)));
            }
        }
        TopLevelImpl copy = *tpl;
        copy.typeName = mangled;
        copy.methods = std::move(methods);
        copy.traitArgs.clear();
        for (const TypeRef& arg : tpl->traitArgs) copy.traitArgs.push_back(substituteSelf(arg, selfType));
        copy.typeParams = {};
        copy.variadicParam = std::nullopt;
        out.push_back(make(arena, std::move(copy)));
    }
    return out;
}

TypeAnnotation MonoContext::substituteSelf(const TypeAnnotation& annotation, const TypeRef& selfType) {
    return annotation.isExplicit() ? explicitType(substituteSelf(annotation.ref, selfType)) : annotation;
}

TypeRef MonoContext::substituteSelf(const TypeRef& type, const TypeRef& selfType) {
    if (const TypeRefNamed* n = as<TypeRefNamed>(type)) {
        if (n->name == "Self" && n->args.empty()) return selfType;
        std::vector<TypeRef> args;
        for (const TypeRef& arg : n->args) args.push_back(substituteSelf(arg, selfType));
        return edit<TypeRefNamed>(arena, type, [&](TypeRefNamed& c) { c.args = std::move(args); });
    }
    return mapChildren(
        arena, type, [&](const TypeRef& t) { return substituteSelf(t, selfType); }, asWritten, true);
}

// ---------------------------------------------------------------------------
// Reflected fields
// ---------------------------------------------------------------------------

bool MonoContext::reflectedSelfFields(const Expr& expr) { return reflectedFieldsOf(expr) != nullptr; }

ExprCall* MonoContext::reflectedFieldsOf(const Expr& expr) {
    const ExprMember* member = as<ExprMember>(expr);
    if (!member || member->name != "fields") return nullptr;
    Expr target = is<ExprGrouping>(member->target) ? cast<ExprGrouping>(member->target).expr : member->target;
    ExprCall* call = as<ExprCall>(target);
    if (!call || call->callee != "__reflect") return nullptr;
    const ExprIdentifier* id = call->args.size() == 1 ? as<ExprIdentifier>(call->args[0]) : nullptr;
    if (!id) return nullptr;
    const std::string& name = id->name;
    return (name == "Self" || packTemplates.contains(name) || plainPackFields.contains(name)) ? call : nullptr;
}

std::vector<PackField> MonoContext::reflectedFields(const Expr& expr, const std::vector<PackField>& fallback) {
    ExprCall* call = reflectedFieldsOf(expr);
    if (!call) return fallback;
    const ExprIdentifier* id = call->args.size() == 1 ? as<ExprIdentifier>(call->args[0]) : nullptr;
    if (!id) return fallback;
    if (id->name == "Self") return fallback;
    TopLevelPack* const* found = packTemplates.get(id->name);
    if (!found) {
        if (const std::vector<PackField>* plain = plainPackFields.get(id->name)) return *plain;
        return fallback;
    }
    const TopLevelPack& tpl = **found;
    std::vector<TypeRef> arguments;
    for (const TypeRef& arg : call->typeArgs) arguments.push_back(substituteParams(arg, typeBindings));
    ConstraintEvaluator::Bindings bound;
    size_t n = std::min(tpl.typeParams.size(), arguments.size());
    for (size_t i = 0; i < n; i++) {
        if (std::optional<ConstraintEvaluator::Binding> binding = ConstraintEvaluator::bindingOf(arena, arguments[i])) {
            bound.set(tpl.typeParams[i], *binding);
        }
    }
    std::vector<PackField> out;
    for (const PackField& f : tpl.fields) {
        if (ConstraintEvaluator::evaluate(arena, f.condition, bound, nullptr).kind != ConstraintEvaluator::Outcome::Kind::Violated) {
            out.push_back(f);
        }
    }
    return out;
}

std::vector<Stmt> MonoContext::expandReflectedFields(const std::vector<Stmt>& body, std::vector<PackField> fields) {
    currentFields = fields;
    return expandReflectedFieldsInner(body, fields);
}

std::vector<Stmt> MonoContext::expandReflectedFieldsInner(const std::vector<Stmt>& body, const std::vector<PackField>& fields) {
    std::vector<Stmt> out;
    for (const Stmt& stmt : body) {
        const StmtInlineFor* loop = as<StmtInlineFor>(stmt);
        if (!loop || !reflectedSelfFields(loop->iterable)) {
            append(out, expandReflectedStmt(stmt, fields, nullptr));
            continue;
        }
        std::vector<PackField> reflected = reflectedFields(loop->iterable, fields);
        for (size_t index = 0; index < reflected.size(); index++) {
            FieldBinding binding{loop->name, loop->indexName, (int32_t)index, reflected[index]};
            std::vector<Stmt> iteration;
            for (const Stmt& s : loop->body) append(iteration, expandReflectedStmt(s, fields, &binding));
            // Each iteration is its own scope, as a runtime loop body would be.
            bool declares = std::any_of(iteration.begin(), iteration.end(), [](const Stmt& s) {
                return is<StmtFinDecl>(s) || is<StmtVarDecl>(s) || is<StmtLetDecl>(s);
            });
            if (declares) {
                StmtScope scope;
                scope.body = std::move(iteration);
                scope.line = loop->line;
                scope.column = loop->column;
                out.push_back(make(arena, std::move(scope)));
            } else {
                append(out, iteration);
            }
        }
    }
    return out;
}

std::vector<Stmt> MonoContext::expandReflectedStmt(const Stmt& stmt, const std::vector<PackField>& fields, const FieldBinding* binding) {
    auto expr = [&](const Expr& value) { return substituteReflectedExpr(value, binding); };
    auto optional = [&](const Expr& value) { return value ? expr(value) : value; };
    auto exprs = [&](const std::vector<Expr>& values) {
        std::vector<Expr> out;
        out.reserve(values.size());
        for (const Expr& v : values) out.push_back(expr(v));
        return out;
    };
    auto nested = [&](const std::vector<Stmt>& items) {
        std::vector<Stmt> out;
        for (const Stmt& s : items) append(out, expandReflectedStmt(s, fields, binding));
        return out;
    };
    auto nestedOpt = [&](const std::optional<std::vector<Stmt>>& items) {
        return items ? std::optional<std::vector<Stmt>>(nested(*items)) : std::nullopt;
    };
    auto declaration = [&](auto& c) {
        TypeRef self = named(arena, "Self");
        c.type = substituteSelf(c.type, self);
        c.initializer = expr(c.initializer);
    };
    auto initializer = [&](auto& c) { c.initializer = expr(c.initializer); };
    auto branch = [&](auto& c) {
        c.condition = expr(c.condition);
        c.thenBranch = nested(c.thenBranch);
        c.elseBranch = nestedOpt(c.elseBranch);
    };
    Stmt rewritten;
    switch (stmt.kind()) {
        case StmtKind::Import: rewritten = stmt; break;
        case StmtKind::VarDecl: rewritten = edit<StmtVarDecl>(arena, stmt, declaration); break;
        case StmtKind::FinDecl: rewritten = edit<StmtFinDecl>(arena, stmt, declaration); break;
        case StmtKind::LetDecl: rewritten = edit<StmtLetDecl>(arena, stmt, declaration); break;
        case StmtKind::InlineVar: rewritten = edit<StmtInlineVar>(arena, stmt, initializer); break;
        case StmtKind::InlineFin: rewritten = edit<StmtInlineFin>(arena, stmt, initializer); break;
        case StmtKind::InlineLet: rewritten = edit<StmtInlineLet>(arena, stmt, initializer); break;
        case StmtKind::RemDecl: rewritten = edit<StmtRemDecl>(arena, stmt, initializer); break;
        case StmtKind::Assignment: rewritten = edit<StmtAssignment>(arena, stmt, [&](auto& c) { c.value = expr(c.value); }); break;
        case StmtKind::InlineAssignment:
            rewritten = edit<StmtInlineAssignment>(arena, stmt, [&](auto& c) { c.value = expr(c.value); });
            break;
        case StmtKind::Exchange:
            rewritten = edit<StmtExchange>(arena, stmt, [&](auto& c) {
                c.left = expr(c.left);
                c.right = expr(c.right);
            });
            break;
        case StmtKind::IndexAssign:
            rewritten = edit<StmtIndexAssign>(arena, stmt, [&](auto& c) {
                c.target = expr(c.target);
                c.index = expr(c.index);
                c.value = expr(c.value);
            });
            break;
        case StmtKind::MemberAssign: {
            const StmtMemberAssign& assign = cast<StmtMemberAssign>(stmt);
            std::optional<std::string> folded;
            if (assign.nameExpr && binding) folded = foldFieldNameExpr(assign.nameExpr, *binding);
            rewritten = edit<StmtMemberAssign>(arena, stmt, [&](auto& c) {
                c.target = expr(c.target);
                c.value = expr(c.value);
                if (folded) {
                    c.name = *folded;
                    c.nameExpr = Expr{};
                }
            });
            break;
        }
        case StmtKind::DerefAssign:
            rewritten = edit<StmtDerefAssign>(arena, stmt, [&](auto& c) {
                c.target = expr(c.target);
                c.value = expr(c.value);
            });
            break;
        case StmtKind::Return: rewritten = edit<StmtReturn>(arena, stmt, [&](auto& c) { c.value = optional(c.value); }); break;
        case StmtKind::ExprStmt: rewritten = edit<StmtExprStmt>(arena, stmt, [&](auto& c) { c.expr = expr(c.expr); }); break;
        case StmtKind::Throw: rewritten = edit<StmtThrow>(arena, stmt, [&](auto& c) { c.value = expr(c.value); }); break;
        case StmtKind::Panic: rewritten = edit<StmtPanic>(arena, stmt, [&](auto& c) { c.message = expr(c.message); }); break;
        case StmtKind::Yield: rewritten = edit<StmtYield>(arena, stmt, [&](auto& c) { c.value = expr(c.value); }); break;
        case StmtKind::Assert:
            rewritten = edit<StmtAssert>(arena, stmt, [&](auto& c) {
                c.condition = expr(c.condition);
                c.message = expr(c.message);
            });
            break;
        case StmtKind::InlineAssert:
            rewritten = edit<StmtInlineAssert>(arena, stmt, [&](auto& c) {
                c.condition = expr(c.condition);
                c.message = expr(c.message);
            });
            break;
        case StmtKind::Trace:
            rewritten = edit<StmtTrace>(arena, stmt, [&](auto& c) {
                c.message = expr(c.message);
                c.level = optional(c.level);
            });
            break;
        case StmtKind::InlineTrace:
            rewritten = edit<StmtInlineTrace>(arena, stmt, [&](auto& c) {
                c.message = expr(c.message);
                c.level = optional(c.level);
            });
            break;
        case StmtKind::If: rewritten = edit<StmtIf>(arena, stmt, branch); break;
        case StmtKind::InlineIf: rewritten = edit<StmtInlineIf>(arena, stmt, branch); break;
        case StmtKind::DeepInlineIf: rewritten = edit<StmtDeepInlineIf>(arena, stmt, branch); break;
        case StmtKind::While:
            rewritten = edit<StmtWhile>(arena, stmt, [&](auto& c) {
                c.condition = expr(c.condition);
                c.body = nested(c.body);
            });
            break;
        case StmtKind::For:
            rewritten = edit<StmtFor>(arena, stmt, [&](auto& c) {
                c.iterable = expr(c.iterable);
                c.step = optional(c.step);
                c.body = nested(c.body);
            });
            break;
        case StmtKind::InlineFor: {
            if (reflectedSelfFields(cast<StmtInlineFor>(stmt).iterable)) return expandReflectedFields({stmt}, fields);
            rewritten = edit<StmtInlineFor>(arena, stmt, [&](auto& c) {
                c.iterable = expr(c.iterable);
                c.body = nested(c.body);
            });
            break;
        }
        case StmtKind::Loop:
            rewritten = edit<StmtLoop>(arena, stmt, [&](auto& c) {
                c.iterable = optional(c.iterable);
                c.body = nested(c.body);
            });
            break;
        case StmtKind::When:
            rewritten = edit<StmtWhen>(arena, stmt, [&](auto& c) {
                c.scrutinee = expr(c.scrutinee);
                std::vector<WhenBranch> branches;
                for (const WhenBranch& b : c.branches) {
                    WhenBranch copy = b;
                    copy.patterns = exprs(b.patterns);
                    copy.body = nested(b.body);
                    branches.push_back(std::move(copy));
                }
                c.branches = std::move(branches);
                c.elseBranch = nestedOpt(c.elseBranch);
            });
            break;
        case StmtKind::Try:
            rewritten = edit<StmtTry>(arena, stmt, [&](auto& c) {
                c.body = nested(c.body);
                c.catchBody = nestedOpt(c.catchBody);
            });
            break;
        case StmtKind::Defer: rewritten = edit<StmtDefer>(arena, stmt, [&](auto& c) { c.body = nested(c.body); }); break;
        case StmtKind::Scope: rewritten = edit<StmtScope>(arena, stmt, [&](auto& c) { c.body = nested(c.body); }); break;
        case StmtKind::InlineBlock: rewritten = edit<StmtInlineBlock>(arena, stmt, [&](auto& c) { c.body = nested(c.body); }); break;
        case StmtKind::DeepInlineBlock:
            rewritten = edit<StmtDeepInlineBlock>(arena, stmt, [&](auto& c) { c.body = nested(c.body); });
            break;
        case StmtKind::Effect:
            rewritten = edit<StmtEffect>(arena, stmt, [&](auto& c) {
                if (c.dependencies) c.dependencies = exprs(*c.dependencies);
                c.body = nested(c.body);
            });
            break;
        case StmtKind::UsingContext:
            rewritten = edit<StmtUsingContext>(arena, stmt, [&](auto& c) {
                c.values = exprs(c.values);
                c.body = nested(c.body);
            });
            break;
        case StmtKind::NoInline:
            rewritten = edit<StmtNoInline>(arena, stmt, [&](auto& c) { c.stmt = single(expandReflectedStmt(c.stmt, fields, binding)); });
            break;
        case StmtKind::Break:
            rewritten = edit<StmtBreak>(arena, stmt, [&](auto& c) { c.value = c.value ? rewriteExpr(c.value) : c.value; });
            break;
        case StmtKind::Continue: rewritten = stmt; break;
        default: AZORA_UNREACHABLE();
    }
    return {rewritten};
}

std::optional<std::string> MonoContext::foldFieldNameExpr(const Expr& nameExpr, const FieldBinding& binding) {
    const ExprMember* member = as<ExprMember>(nameExpr);
    if (!member || member->name != "name") return std::nullopt;
    const ExprIdentifier* target = as<ExprIdentifier>(member->target);
    if (!target || target->name != binding.variable) return std::nullopt;
    return binding.field.name;
}

std::vector<Expr> MonoContext::expandArgument(const Expr& arg, const FieldBinding* binding) {
    const ExprInlineForArgs* loop = as<ExprInlineForArgs>(arg);
    if (!loop || !reflectedSelfFields(loop->iterable)) return {substituteReflectedExpr(arg, binding)};
    std::vector<Expr> out;
    std::vector<PackField> reflected = reflectedFields(loop->iterable, currentFields);
    for (size_t index = 0; index < reflected.size(); index++) {
        FieldBinding each{loop->name, std::nullopt, (int32_t)index, reflected[index]};
        out.push_back(substituteReflectedExpr(loop->body, &each));
    }
    return out;
}

Expr MonoContext::substituteReflectedExpr(const Expr& expr, const FieldBinding* binding) {
    if (binding) {
        if (const ExprIdentifier* id = as<ExprIdentifier>(expr); id && binding->indexName && id->name == *binding->indexName) {
            return make(arena, ExprIntLiteral{(int64_t)binding->index, id->line, id->column, id->length});
        }
        // `self.${f.name}` - a spliced member name folds to the field it names.
        if (const ExprMember* member = as<ExprMember>(expr); member && member->nameExpr) {
            if (std::optional<std::string> folded = foldFieldNameExpr(member->nameExpr, *binding)) {
                Expr target = substituteReflectedExpr(member->target, binding);
                return make(arena, ExprMember{target, *folded, member->line, member->column, member->length});
            }
        }
        // `reflect<f>.hasAnnot<D>` / `reflect<f>.annotMeta<D>.x` - what the field was annotated with.
        if (const ExprCall* call = as<ExprCall>(expr); call && call->callee == "__hasAnnot" && call->args.size() == 1) {
            const ExprIdentifier* arg = as<ExprIdentifier>(call->args[0]);
            if (arg && arg->name == binding->variable) {
                std::optional<std::string> wanted;
                if (call->typeArgs.size() == 1) wanted = displayName(call->typeArgs[0]);
                bool has = wanted && std::any_of(binding->field.annotations.begin(), binding->field.annotations.end(),
                                                 [&](const Annotation& a) { return a.name == *wanted; });
                return make(arena, ExprBoolLiteral{has, call->line, call->column});
            }
        }
        if (const ExprMember* member = as<ExprMember>(expr)) {
            const ExprCall* target = as<ExprCall>(member->target);
            if (target && target->callee == "__annotMeta" && target->args.size() == 1) {
                const ExprIdentifier* arg = as<ExprIdentifier>(target->args[0]);
                if (arg && arg->name == binding->variable) {
                    std::optional<std::string> wanted;
                    if (target->typeArgs.size() == 1) wanted = displayName(target->typeArgs[0]);
                    TopLevelDeco* const* declaration = wanted ? decorators.get(*wanted) : nullptr;
                    if (declaration) {
                        DecoratorMetadata::Applied applied;
                        applied.declaration = *declaration;
                        for (const Annotation& a : binding->field.annotations) {
                            if (a.name == *wanted) {
                                applied.directApplication = a;
                                break;
                            }
                        }
                        if (Expr value = DecoratorMetadata::fieldValue(arena, applied, member->name)) return value;
                    }
                }
            }
        }
        // `f.name` / `f.typeName` - the field's declared name and type, as text.
        if (const ExprMember* member = as<ExprMember>(expr)) {
            const ExprIdentifier* target = as<ExprIdentifier>(member->target);
            if (target && target->name == binding->variable) {
                if (member->name == "name") return make(arena, ExprStringLiteral{binding->field.name, member->line, member->column});
                if (member->name == "typeName") {
                    return make(arena, ExprStringLiteral{displayName(binding->field.type), member->line, member->column});
                }
            }
            if (target && member->name == "value" && target->name == binding->variable) {
                Expr self = make(arena, ExprIdentifier{"self", member->line, member->column, 4});
                return make(arena, ExprMember{self, binding->field.name, member->line, member->column, member->length});
            }
        }
    }
    auto sub = [&](const Expr& e) { return substituteReflectedExpr(e, binding); };
    auto subAll = [&](const std::vector<Expr>& values) {
        std::vector<Expr> out;
        out.reserve(values.size());
        for (const Expr& v : values) out.push_back(sub(v));
        return out;
    };
    switch (expr.kind()) {
        case ExprKind::Binary:
            return edit<ExprBinary>(arena, expr, [&](auto& c) {
                c.left = sub(c.left);
                c.right = sub(c.right);
            });
        // `Self(inline for f in reflect<Self>.fields { … })` - one argument per field.
        case ExprKind::Call:
            return edit<ExprCall>(arena, expr, [&](auto& c) {
                std::vector<Expr> args;
                for (const Expr& arg : c.args) {
                    for (const Expr& each : expandArgument(arg, binding)) args.push_back(each);
                }
                c.args = std::move(args);
            });
        case ExprKind::Unary: return edit<ExprUnary>(arena, expr, [&](auto& c) { c.operand = sub(c.operand); });
        case ExprKind::Grouping: return edit<ExprGrouping>(arena, expr, [&](auto& c) { c.expr = sub(c.expr); });
        case ExprKind::Range:
            return edit<ExprRange>(arena, expr, [&](auto& c) {
                c.from = sub(c.from);
                c.to = sub(c.to);
            });
        case ExprKind::ArrayLiteral: return edit<ExprArrayLiteral>(arena, expr, [&](auto& c) { c.elements = subAll(c.elements); });
        case ExprKind::SetLiteral: return edit<ExprSetLiteral>(arena, expr, [&](auto& c) { c.elements = subAll(c.elements); });
        case ExprKind::Index:
            return edit<ExprIndex>(arena, expr, [&](auto& c) {
                c.target = sub(c.target);
                c.index = sub(c.index);
            });
        case ExprKind::Member: return edit<ExprMember>(arena, expr, [&](auto& c) { c.target = sub(c.target); });
        case ExprKind::MethodCall:
            return edit<ExprMethodCall>(arena, expr, [&](auto& c) {
                c.target = sub(c.target);
                c.args = subAll(c.args);
            });
        case ExprKind::StringTemplate:
            return edit<ExprStringTemplate>(arena, expr, [&](auto& c) {
                for (StringTemplatePart& part : c.parts) {
                    if (!part.isLiteral) part = exprPart(sub(part.expr));
                }
            });
        case ExprKind::TupleLit: return edit<ExprTupleLit>(arena, expr, [&](auto& c) { c.elements = subAll(c.elements); });
        case ExprKind::VariantLit: return edit<ExprVariantLit>(arena, expr, [&](auto& c) { c.elements = subAll(c.elements); });
        case ExprKind::TupleAccess: return edit<ExprTupleAccess>(arena, expr, [&](auto& c) { c.target = sub(c.target); });
        case ExprKind::CatchExpr:
            return edit<ExprCatchExpr>(arena, expr, [&](auto& c) {
                c.expr = sub(c.expr);
                c.fallback = sub(c.fallback);
            });
        case ExprKind::TryPropagate: return edit<ExprTryPropagate>(arena, expr, [&](auto& c) { c.expr = sub(c.expr); });
        case ExprKind::IfExpr:
            return edit<ExprIfExpr>(arena, expr, [&](auto& c) {
                c.condition = sub(c.condition);
                c.thenExpr = sub(c.thenExpr);
                c.elseExpr = sub(c.elseExpr);
            });
        case ExprKind::Lambda:
            return edit<ExprLambda>(arena, expr, [&](auto& c) {
                std::vector<Stmt> body;
                for (const Stmt& s : c.body) append(body, expandReflectedStmt(s, {}, binding));
                c.body = std::move(body);
            });
        case ExprKind::NamedArg: return edit<ExprNamedArg>(arena, expr, [&](auto& c) { c.value = sub(c.value); });
        case ExprKind::NullCoalesce:
            return edit<ExprNullCoalesce>(arena, expr, [&](auto& c) {
                c.left = sub(c.left);
                c.right = sub(c.right);
            });
        case ExprKind::SafeMember: return edit<ExprSafeMember>(arena, expr, [&](auto& c) { c.target = sub(c.target); });
        case ExprKind::Cast: return edit<ExprCast>(arena, expr, [&](auto& c) { c.expr = sub(c.expr); });
        case ExprKind::IsCheck: return edit<ExprIsCheck>(arena, expr, [&](auto& c) { c.expr = sub(c.expr); });
        case ExprKind::MapLit:
            return edit<ExprMapLit>(arena, expr, [&](auto& c) {
                for (auto& entry : c.entries) {
                    Expr key = sub(entry.first);
                    Expr value = sub(entry.second);
                    entry = {key, value};
                }
            });
        case ExprKind::Alloc: return edit<ExprAlloc>(arena, expr, [&](auto& c) { c.value = sub(c.value); });
        case ExprKind::Deref: return edit<ExprDeref>(arena, expr, [&](auto& c) { c.target = sub(c.target); });
        case ExprKind::Isolated: return edit<ExprIsolated>(arena, expr, [&](auto& c) { c.value = sub(c.value); });
        case ExprKind::Await: return edit<ExprAwait>(arena, expr, [&](auto& c) { c.value = sub(c.value); });
        case ExprKind::Spread: return edit<ExprSpread>(arena, expr, [&](auto& c) { c.array = sub(c.array); });
        default: return expr;
    }
}

}  // namespace azora
