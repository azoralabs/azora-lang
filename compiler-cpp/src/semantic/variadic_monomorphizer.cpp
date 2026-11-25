// Port of semantic/VariadicMonomorphizer.kt: the object's own functions and
// MonoNames. MonoContext (mono_context*.cpp) does the work of one run.
#include "semantic/variadic_monomorphizer.h"

#include "base/chars.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_rewriter.h"
#include "frontend/ast_util.h"
#include "semantic/mono_context.h"

#include <algorithm>
#include <unordered_map>
#include <unordered_set>

namespace azora {

using namespace mono;

namespace {

/** Intrinsics.TUPLE: the compiler's structural tuple. */
const char* const TUPLE = "Tuple";

}  // namespace

// ---------------------------------------------------------------------------
// MonoNames
// ---------------------------------------------------------------------------

namespace MonoNames {

namespace {

std::string sanitize(const std::string& name) {
    std::u16string chars = units(name);
    auto kept = [](char16_t c) { return isLetterOrDigit(c) || c == u'_'; };
    if (std::all_of(chars.begin(), chars.end(), kept)) return name;
    std::u16string out;
    for (char16_t c : chars) {
        if (kept(c)) out.push_back(c);
    }
    return encodeUtf8(out);
}

std::string joined(const std::vector<TypeRef>& types) {
    std::string out;
    for (size_t i = 0; i < types.size(); i++) {
        if (i > 0) out += "_";
        out += mangleType(types[i]);
    }
    return out;
}

}  // namespace

std::string mangle(const std::string& templateName, const std::vector<TypeRef>& args) {
    std::string raw = templateName;
    for (const TypeRef& arg : args) raw += "_" + mangleType(arg);
    return canonicalSymbol(raw);
}

std::string canonicalSymbol(const std::string& raw) {
    size_t start = 0;
    while (start < raw.size() && raw[start] == '_') start++;
    std::string out = "__";
    for (size_t i = start; i < raw.size();) {
        if (raw[i] != '_') {
            out += raw[i++];
            continue;
        }
        out += '_';
        while (i < raw.size() && raw[i] == '_') i++;
    }
    return out;
}

std::string mangleType(const TypeRef& type) {
    switch (type.kind()) {
        case TypeRefKind::Named: {
            const TypeRefNamed& n = cast<TypeRefNamed>(type);
            return sanitize(n.name) + (n.args.empty() ? std::string() : "_" + joined(n.args));
        }
        case TypeRefKind::Array: return "Array_" + mangleType(cast<TypeRefArray>(type).element);
        case TypeRefKind::Map: return "Map_" + mangleType(cast<TypeRefMap>(type).key) + "_" + mangleType(cast<TypeRefMap>(type).value);
        case TypeRefKind::Set: return "Set_" + mangleType(cast<TypeRefSet>(type).element);
        case TypeRefKind::Function: {
            const TypeRefFunction& f = cast<TypeRefFunction>(type);
            return std::string(surfaceName(f.kind)) + "_P_" + joined(f.params) + "_R_" + joined(f.receivers) + "_" + mangleType(f.ret);
        }
        case TypeRefKind::Tuple: return "Tup_" + joined(cast<TypeRefTuple>(type).elements);
        case TypeRefKind::Nullable: return mangleType(cast<TypeRefNullable>(type).inner) + "_N";
        case TypeRefKind::Failable: return mangleType(cast<TypeRefFailable>(type).ok) + "_F";
        case TypeRefKind::Pointer: return mangleType(cast<TypeRefPointer>(type).inner) + "_P";
        case TypeRefKind::Reference: return mangleType(cast<TypeRefReference>(type).inner) + "_R";
        case TypeRefKind::Const: return str((long long)cast<TypeRefConst>(type).value);
        default: AZORA_UNREACHABLE();
    }
}

}  // namespace MonoNames

// ---------------------------------------------------------------------------
// VariadicMonomorphizer
// ---------------------------------------------------------------------------

namespace VariadicMonomorphizer {

namespace {

/** The library's `bridge pack Tuple<...T>`, which names the compiler's structural tuple. */
bool isStructuralTuple(const TopLevelPack& pack) { return pack.isBridge && pack.variadicParam && pack.name == TUPLE; }

bool requiresClone(const Expr& clause) {
    switch (clause.kind()) {
        case ExprKind::IsCheck: return substringAfterLast(cast<ExprIsCheck>(clause).typeName, "__") == "Clone";
        case ExprKind::Binary: return requiresClone(cast<ExprBinary>(clause).left) || requiresClone(cast<ExprBinary>(clause).right);
        case ExprKind::Grouping: return requiresClone(cast<ExprGrouping>(clause).expr);
        default: return false;
    }
}

// A method with a compile-time branch needs the pack's concrete arguments.
bool hasCompileTimeChoice(const Stmt& stmt);

bool anyChoice(const std::vector<Stmt>& body) { return std::any_of(body.begin(), body.end(), hasCompileTimeChoice); }

bool hasCompileTimeChoice(const Stmt& stmt) {
    switch (stmt.kind()) {
        case StmtKind::InlineIf:
        case StmtKind::DeepInlineIf: return true;
        case StmtKind::If: {
            const StmtIf& s = cast<StmtIf>(stmt);
            return anyChoice(s.thenBranch) || (s.elseBranch && anyChoice(*s.elseBranch));
        }
        case StmtKind::Scope: return anyChoice(cast<StmtScope>(stmt).body);
        case StmtKind::While: return anyChoice(cast<StmtWhile>(stmt).body);
        case StmtKind::For: return anyChoice(cast<StmtFor>(stmt).body);
        case StmtKind::Try: {
            const StmtTry& s = cast<StmtTry>(stmt);
            return anyChoice(s.body) || (s.catchBody && anyChoice(*s.catchBody));
        }
        default: return false;
    }
}

TypeRef explicitReturnType(const FuncDecl& decl) { return decl.returnType.ref; }

/** The name of the variadic pack a function template returns (`Tuple<T…>` -> "Tuple"). */
std::optional<std::string> returnedVariadicPackName(const FuncDecl& decl) {
    const TypeRefNamed* ref = as<TypeRefNamed>(decl.returnType.ref);
    if (ref && ref->variadic) return ref->name;
    return std::nullopt;
}

/** Whether any body reads `reflect<X>.fields` - such a loop needs this pass even with nothing generic. */
bool reflectsFields(Arena& arena, const Program& program) {
    bool found = false;
    AstRewriter finder(arena);
    finder.onExpr = [&](AstRewriter& self, const Expr& e) -> Expr {
        if (const ExprMember* member = as<ExprMember>(e); member && member->name == "fields") {
            Expr call = is<ExprGrouping>(member->target) ? cast<ExprGrouping>(member->target).expr : member->target;
            if (const ExprCall* c = as<ExprCall>(call); c && c->callee == "__reflect") found = true;
        }
        return found ? e : self.baseExpr(e);
    };
    for (const TopLevel& item : program.items) {
        if (found) break;
        switch (item.kind()) {
            case TopLevelKind::Func: finder.stmts(cast<TopLevelFunc>(item).decl.body); break;
            case TopLevelKind::Impl:
                for (const FuncDecl& m : cast<TopLevelImpl>(item).methods) finder.stmts(m.body);
                break;
            case TopLevelKind::Test: finder.stmts(cast<TopLevelTest>(item).body); break;
            default: break;
        }
    }
    return found;
}

std::string shapeKey(const std::vector<TypeRef>& shape) {
    std::string key;
    for (const TypeRef& t : shape) {
        key += structuralKey(t);
        key += '\x1f';
    }
    return key;
}

}  // namespace

std::string tupleMemberOwner(const std::vector<TypeRef>& shape) { return MonoNames::mangle(TUPLE, shape); }

Program specializeTuples(Arena& arena, const Program& program, const std::vector<std::vector<TypeRef>>& shapes) {
    if (shapes.empty() || program.tupleTemplates.empty()) return program;
    // The family is the `bridge pack Tuple<...T>`; what a specialization needs of it is its variadic parameter.
    std::optional<std::string> variadic;
    for (const TopLevelImpl* tpl : program.tupleTemplates) {
        if (tpl->variadicParam) {
            variadic = tpl->variadicParam;
            break;
        }
    }
    if (!variadic) return program;
    TopLevelPack family;
    family.name = TUPLE;
    family.typeParams = {*variadic};
    family.line = 0;
    family.variadicParam = variadic;
    MonoContext ctx(arena);
    ctx.typeNamespaces = program.scopeTypeNamespaces;
    std::unordered_set<std::string> existing;
    for (const TopLevel& item : program.items) {
        if (const TopLevelImpl* impl = as<TopLevelImpl>(item)) existing.insert(impl->typeName);
    }
    std::vector<std::vector<TypeRef>> fresh;
    std::unordered_set<std::string> seen;
    for (const std::vector<TypeRef>& shape : shapes) {
        if (!seen.insert(shapeKey(shape)).second) continue;
        if (!existing.count(tupleMemberOwner(shape))) fresh.push_back(shape);
    }
    Program out = program;
    for (const std::vector<TypeRef>& shape : fresh) {
        std::vector<PackField> fields;
        for (size_t index = 0; index < shape.size(); index++) fields.push_back(PackField{str((int)index), shape[index], false, Expr{}});
        TypeRef self = make(arena, TypeRefTuple{shape});
        for (TopLevelImpl* impl :
             ctx.specializeImpls(TUPLE, tupleMemberOwner(shape), self, fields, shape, &family, program.tupleTemplates)) {
            out.items.push_back(impl);
        }
    }
    for (const std::vector<TypeRef>& shape : fresh) out.tupleShapes.set(tupleMemberOwner(shape), shape);
    return out;
}

Program monomorphize(Arena& arena, const Program& program) {
    std::unordered_set<std::string> compileTimeOwners;
    for (const TopLevel& item : program.items) {
        const TopLevelImpl* impl = as<TopLevelImpl>(item);
        if (!impl) continue;
        if (std::any_of(impl->methods.begin(), impl->methods.end(), [](const FuncDecl& m) { return anyChoice(m.body); })) {
            compileTimeOwners.insert(impl->typeName);
        }
    }
    OrderedMap<std::string, TopLevelPack*> packTemplates;
    OrderedMap<std::string, TopLevelFunc*> funcTemplates;
    OrderedMap<std::string, std::vector<TopLevelImpl*>> implTemplates;
    OrderedMap<std::string, std::vector<std::string>> constructibleTypes;
    OrderedMap<std::string, CallableReturn> functionReturns;
    for (const TopLevel& item : program.items) {
        switch (item.kind()) {
            case TopLevelKind::Pack: {
                TopLevelPack* pack = as<TopLevelPack>(item);
                constructibleTypes.set(pack->name, pack->typeParams);
                // A pack is monomorphised when its layout depends on its arguments.
                bool conditional = std::any_of(pack->fields.begin(), pack->fields.end(), [](const PackField& f) { return (bool)f.condition; });
                if (!isStructuralTuple(*pack) &&
                    (pack->variadicParam || conditional ||
                     (!pack->typeParams.empty() && compileTimeOwners.count(pack->name) && !pack->isBridge))) {
                    packTemplates.set(pack->name, pack);
                }
                break;
            }
            case TopLevelKind::Func: {
                TopLevelFunc* func = as<TopLevelFunc>(item);
                const FuncDecl& decl = func->decl;
                if (TypeRef ret = explicitReturnType(decl)) functionReturns.set(decl.name, CallableReturn{decl.typeParams, ret});
                // Only a variadic function that RETURNS a variadic pack is a template.
                if ((decl.variadicParam && returnedVariadicPackName(decl)) ||
                    (!decl.typeParams.empty() && requiresClone(decl.whereClause))) {
                    funcTemplates.set(decl.name, func);
                }
                break;
            }
            case TopLevelKind::Solo: constructibleTypes.set(cast<TopLevelSolo>(item).name, {}); break;
            default: break;
        }
    }
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        if (impl && packTemplates.contains(impl->typeName)) implTemplates.getOrPut(impl->typeName, {}).push_back(impl);
    }
    // `impl … for Tuple<...T>` waits for the shapes type resolution finds.
    std::unordered_set<std::string> families;
    for (const TopLevel& item : program.items) {
        const TopLevelPack* pack = as<TopLevelPack>(item);
        if (pack && isStructuralTuple(*pack)) families.insert(pack->name);
    }
    std::vector<TopLevelImpl*> heldBack;
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        if (impl && families.count(impl->typeName)) heldBack.push_back(impl);
    }
    if (!heldBack.empty()) {
        Program next = program;
        next.items.clear();
        for (const TopLevel& item : program.items) {
            const TopLevelImpl* impl = as<TopLevelImpl>(item);
            if (!(impl && families.count(impl->typeName))) next.items.push_back(item);
        }
        // Monomorphization runs once more after re-injection, which brings the same impls back.
        next.tupleTemplates.clear();
        std::unordered_set<std::string> seen;
        for (const std::vector<TopLevelImpl*>* list : {(const std::vector<TopLevelImpl*>*)&program.tupleTemplates, (const std::vector<TopLevelImpl*>*)&heldBack}) {
            for (TopLevelImpl* impl : *list) {
                if (seen.insert(structuralKey(TopLevel(impl))).second) next.tupleTemplates.push_back(impl);
            }
        }
        return monomorphize(arena, next);
    }
    if (packTemplates.empty() && funcTemplates.empty() && program.typeMacroRules.empty() && !reflectsFields(arena, program)) {
        return program;
    }

    std::map<std::pair<std::string, std::string>, CallableReturn> methodReturns;
    for (const TopLevel& item : program.items) {
        std::string owner;
        const std::vector<FuncDecl>* methods = nullptr;
        if (const TopLevelImpl* impl = as<TopLevelImpl>(item)) {
            owner = substringBefore(impl->typeName, "<");
            methods = &impl->methods;
        } else if (const TopLevelSolo* solo = as<TopLevelSolo>(item)) {
            owner = solo->name;
            methods = &solo->methods;
        } else {
            continue;
        }
        std::vector<std::string> ownerTypeParams;
        if (const std::vector<std::string>* params = constructibleTypes.get(owner)) ownerTypeParams = *params;
        for (const FuncDecl& method : *methods) {
            if (TypeRef ret = explicitReturnType(method)) {
                std::vector<std::string> typeParams = ownerTypeParams;
                typeParams.insert(typeParams.end(), method.typeParams.begin(), method.typeParams.end());
                methodReturns[{owner, method.name}] = CallableReturn{typeParams, ret};
            }
        }
    }

    MonoContext ctx(arena);
    ctx.packTemplates = packTemplates;
    ctx.funcTemplates = funcTemplates;
    ctx.constructibleTypes = constructibleTypes;
    ctx.functionReturns = functionReturns;
    ctx.methodReturns = methodReturns;
    ctx.implTemplates = implTemplates;
    ctx.typeMacroRules = program.typeMacroRules;
    ctx.typeNamespaces = program.scopeTypeNamespaces;
    std::unordered_map<std::string, TopLevelPack*> firstPacks;
    for (const TopLevel& item : program.items) {
        if (TopLevelPack* pack = as<TopLevelPack>(item)) {
            ctx.plainPackFields.set(pack->name, pack->fields);
            firstPacks.emplace(pack->name, pack);
        } else if (const TopLevelEnum* e = as<TopLevelEnum>(item)) {
            ctx.enumVariants.set(e->name, e->variants);
        } else if (TopLevelDeco* deco = as<TopLevelDeco>(item)) {
            ctx.decorators.set(deco->name, deco);
        }
    }
    // A `typealias Vec2i = Vec<Int, 2>` names one specialization; resolving the aliases first
    // gives every later rewrite the mangled name to use.
    ctx.resolveStaticAliases(program.items);
    std::vector<TopLevel> expanded;
    expanded.reserve(program.items.size());
    for (const TopLevel& item : program.items) expanded.push_back(ctx.expandPlainImplReflection(item, firstPacks));
    for (TopLevel& item : expanded) item = ctx.expandConstGenericMembers(item);
    // A static block's members are templates for a monomorphised pack, re-emitted per specialization.
    std::vector<TopLevel> staticTemplates;
    std::vector<TopLevel> rest;
    for (const TopLevel& item : expanded) {
        if (ctx.isStaticTemplateMember(item)) {
            staticTemplates.push_back(item);
        } else {
            rest.push_back(item);
        }
    }
    std::vector<TopLevel> finalItems;
    for (const TopLevel& item : rest) {
        if (TopLevel rewritten = ctx.rewriteTopLevel(item)) finalItems.push_back(rewritten);
    }
    // Expanded before the specialization tables are read: expanding is what fills them.
    std::vector<TopLevelImpl*> impls = ctx.expandImpls();
    std::vector<TopLevel> statics = ctx.expandStaticMembers(staticTemplates);
    for (const auto& [_, pack] : ctx.packs) finalItems.push_back(pack);
    for (const auto& [_, func] : ctx.funcs) finalItems.push_back(func);
    for (TopLevelImpl* impl : impls) finalItems.push_back(impl);
    for (const TopLevel& item : statics) finalItems.push_back(item);
    Program out = program;
    out.items = std::move(finalItems);
    for (const auto& [name, ns] : ctx.generatedPackNamespaces) out.scopeTypeNamespaces.set(name, ns);
    return out;
}

}  // namespace VariadicMonomorphizer

}  // namespace azora
