// Port of stdlib/DeclarationRenamer.kt. Every `copy(...)` keeps Kotlin's
// argument order: block() reports importing blocks, in order, to its caller.
#include "stdlib/declaration_renamer.h"

#include "frontend/ast_util.h"
#include "frontend/local_names.h"

namespace azora {

namespace {

/** The target a bare `bridge func` has: the compiler provides it (see `Parser.parseBridgeFunc`). */
const char* const COMPILER_BRIDGE_TARGET = "Compiler";

DeclarationRenamer::Scope withValues(const DeclarationRenamer::Scope& scope, const std::vector<std::string>& more) {
    DeclarationRenamer::Scope out = scope;
    out.values.insert(more.begin(), more.end());
    return out;
}

DeclarationRenamer::Scope withTypes(const DeclarationRenamer::Scope& scope, const std::vector<std::string>& more) {
    DeclarationRenamer::Scope out = scope;
    out.types.insert(more.begin(), more.end());
    return out;
}

}  // namespace

std::vector<TopLevelUseImport*> writtenImports(Arena& arena, const Program& program) {
    std::vector<TopLevelUseImport*> imports;
    for (const TopLevel& item : program.items) {
        if (TopLevelUseImport* use = as<TopLevelUseImport>(item)) imports.push_back(use);
    }
    DeclarationRenamer collector(arena, {}, {}, [&](const std::vector<StmtImport*>& found, const std::vector<Stmt>&) {
        for (StmtImport* import : found) imports.push_back(import->use);
    });
    for (const TopLevel& item : program.items) {
        if (!is<TopLevelUseImport>(item)) collector.item(item);
    }
    return imports;
}

std::optional<std::string> DeclarationRenamer::renamed(const std::string& name) const {
    if (const std::string* direct = names.get(name)) return *direct;
    size_t separator = name.find("__");
    if (separator == std::string::npos || separator == 0) return std::nullopt;
    const std::string* owner = names.get(name.substr(0, separator));
    if (owner == nullptr) return std::nullopt;
    return *owner + name.substr(separator);
}

std::string DeclarationRenamer::declared(const std::string& name) const {
    std::optional<std::string> r = renamed(name);
    return r ? *r : name;
}

std::string DeclarationRenamer::value(const std::string& name, const Scope& scope) const {
    if (scope.values.count(name)) return name;
    auto it = scope.renames.find(name);
    if (it != scope.renames.end()) return it->second;
    return declared(name);
}

std::string DeclarationRenamer::typeName(const std::string& name, const Scope& scope) const {
    if (scope.types.count(name)) return name;
    auto it = scope.renames.find(name);
    if (it != scope.renames.end()) return it->second;
    return declared(name);
}

TypeRef DeclarationRenamer::type(const TypeRef& ref) { return typeRef(ref, Scope{}); }

TopLevel DeclarationRenamer::item(const TopLevel& item) {
    const Scope top;
    switch (item.kind()) {
        case TopLevelKind::Func: {
            TopLevelFunc c = cast<TopLevelFunc>(item);
            c.decl = func(c.decl, top, declared(c.decl.name));
            return make(arena, std::move(c));
        }
        case TopLevelKind::VarDecl: {
            TopLevelVarDecl c = cast<TopLevelVarDecl>(item);
            c.name = declared(c.name);
            if (c.type) c.type = typeRef(c.type, top);
            c.initializer = expr(c.initializer, top);
            return make(arena, std::move(c));
        }
        case TopLevelKind::FinDecl: {
            TopLevelFinDecl c = cast<TopLevelFinDecl>(item);
            c.name = declared(c.name);
            if (c.type) c.type = typeRef(c.type, top);
            c.initializer = expr(c.initializer, top);
            return make(arena, std::move(c));
        }
        case TopLevelKind::LetDecl: {
            TopLevelLetDecl c = cast<TopLevelLetDecl>(item);
            c.name = declared(c.name);
            if (c.type) c.type = typeRef(c.type, top);
            c.initializer = expr(c.initializer, top);
            return make(arena, std::move(c));
        }
        case TopLevelKind::InlineFin: {
            TopLevelInlineFin c = cast<TopLevelInlineFin>(item);
            c.name = declared(c.name);
            c.initializer = expr(c.initializer, top);
            return make(arena, std::move(c));
        }
        case TopLevelKind::InlineLet: {
            TopLevelInlineLet c = cast<TopLevelInlineLet>(item);
            c.name = declared(c.name);
            c.initializer = expr(c.initializer, top);
            return make(arena, std::move(c));
        }
        case TopLevelKind::InlineVar: {
            TopLevelInlineVar c = cast<TopLevelInlineVar>(item);
            c.name = declared(c.name);
            c.initializer = expr(c.initializer, top);
            return make(arena, std::move(c));
        }
        case TopLevelKind::Test: {
            TopLevelTest c = cast<TopLevelTest>(item);
            c.body = block(c.body, withValues(top, localNamesDeclaredIn(c.body).items));
            return make(arena, std::move(c));
        }
        case TopLevelKind::Pack: {
            TopLevelPack c = cast<TopLevelPack>(item);
            std::vector<std::string> typeNames = c.typeParams;
            typeNames.insert(typeNames.end(), c.constParams.items.begin(), c.constParams.items.end());
            Scope scope = withTypes(top, typeNames);
            c.name = declared(c.name);
            for (PackField& f : c.fields) f = field(f, scope);
            c.annotations = annotations(c.annotations, scope);
            if (c.whereClause) c.whereClause = expr(c.whereClause, scope);
            return make(arena, std::move(c));
        }
        case TopLevelKind::Deco: {
            TopLevelDeco c = cast<TopLevelDeco>(item);
            c.name = declared(c.name);
            for (PackField& f : c.fields) f = field(f, top);
            c.annotations = annotations(c.annotations, top);
            for (DecoratorBinding& binding : c.bindings) {
                binding.name = typeName(binding.name, top);
                binding.trailingTypeArgs = typeRefs(binding.trailingTypeArgs, top);
            }
            return make(arena, std::move(c));
        }
        case TopLevelKind::Enum: {
            TopLevelEnum c = cast<TopLevelEnum>(item);
            c.name = declared(c.name);
            c.annotations = annotations(c.annotations, top);
            if (c.whereClause) c.whereClause = expr(c.whereClause, withTypes(top, c.typeParams));
            return make(arena, std::move(c));
        }
        case TopLevelKind::Fail: {
            TopLevelFail c = cast<TopLevelFail>(item);
            Scope scope = withTypes(top, c.typeParams);
            c.name = declared(c.name);
            for (std::vector<TypeRef>& payload : c.variantPayloads) payload = typeRefs(payload, scope);
            if (c.whereClause) c.whereClause = expr(c.whereClause, scope);
            return make(arena, std::move(c));
        }
        case TopLevelKind::Slot: {
            TopLevelSlot c = cast<TopLevelSlot>(item);
            Scope scope = withTypes(top, c.typeParams);
            c.name = declared(c.name);
            for (SlotVariant& variant : c.variants) variant.payloadTypes = typeRefs(variant.payloadTypes, scope);
            if (c.whereClause) c.whereClause = expr(c.whereClause, scope);
            return make(arena, std::move(c));
        }
        case TopLevelKind::Impl: {
            TopLevelImpl c = cast<TopLevelImpl>(item);
            Scope scope = withTypes(top, c.typeParams);
            c.typeName = typeName(c.typeName, scope);
            if (c.traitName) c.traitName = typeName(*c.traitName, scope);
            c.traitArgs = typeRefs(c.traitArgs, scope);
            for (FuncDecl& m : c.methods) m = func(m, scope, m.name);
            c.decoratorArgs = exprs(c.decoratorArgs, scope);
            for (auto& [name, value] : c.decoratorNamedArgs) value = expr(value, scope);
            c.annotations = annotations(c.annotations, scope);
            for (auto& [name, bound] : c.assocBindings.entries) bound = typeRef(bound, scope);
            return make(arena, std::move(c));
        }
        case TopLevelKind::Spec: {
            TopLevelSpec c = cast<TopLevelSpec>(item);
            std::vector<std::string> typeNames = c.typeParams;
            typeNames.insert(typeNames.end(), c.assocNames.begin(), c.assocNames.end());
            Scope scope = withTypes(top, typeNames);
            c.name = declared(c.name);
            for (FuncDecl& m : c.methods) m = func(m, scope, m.name);
            c.parents = typeRefs(c.parents, scope);
            c.requires_ = typeRefs(c.requires_, scope);
            for (auto& [name, def] : c.typeDefaults.entries) def = typeRef(def, scope);
            if (c.whereClause) c.whereClause = expr(c.whereClause, scope);
            return make(arena, std::move(c));
        }
        case TopLevelKind::TypeAlias: {
            TopLevelTypeAlias c = cast<TopLevelTypeAlias>(item);
            Scope scope = withTypes(top, c.typeParams);
            c.name = declared(c.name);
            c.type = typeRef(c.type, scope);
            if (c.whereClause) c.whereClause = expr(c.whereClause, scope);
            return make(arena, std::move(c));
        }
        // A compiler-provided bridge takes its module identity; a foreign
        // bridge's name is its link symbol and stays as written.
        case TopLevelKind::Bridge: {
            const TopLevelBridge& bridge = cast<TopLevelBridge>(item);
            if (bridge.target != COMPILER_BRIDGE_TARGET) return item;
            TopLevelBridge c = bridge;
            for (BridgeSig& sig : c.funcs) {
                if (!sig.localName) sig.name = declared(sig.name);
            }
            return make(arena, std::move(c));
        }
        case TopLevelKind::InlineAssert: {
            TopLevelInlineAssert c = cast<TopLevelInlineAssert>(item);
            c.condition = expr(c.condition, top);
            c.message = expr(c.message, top);
            return make(arena, std::move(c));
        }
        case TopLevelKind::InlineTrace: {
            TopLevelInlineTrace c = cast<TopLevelInlineTrace>(item);
            c.message = expr(c.message, top);
            if (c.level) c.level = expr(c.level, top);
            return make(arena, std::move(c));
        }
        default:
            return item;
    }
}

FuncDecl DeclarationRenamer::func(const FuncDecl& decl, const Scope& owner, const std::string& name) {
    std::vector<std::string> valueNames;
    for (const Param& p : decl.params) valueNames.push_back(p.name);
    valueNames.push_back(decl.receiverName);
    for (const std::string& local : localNamesDeclaredIn(decl.body).items) valueNames.push_back(local);
    Scope scope = withValues(withTypes(owner, decl.typeParams), valueNames);
    FuncDecl c = decl;
    c.name = name;
    for (Param& p : c.params) p = param(p, scope);
    c.returnType = annotation(c.returnType, scope);
    c.body = block(c.body, scope);
    c.annotations = annotations(c.annotations, scope);
    if (c.whereClause) c.whereClause = expr(c.whereClause, scope);
    return c;
}

Param DeclarationRenamer::param(const Param& p, const Scope& scope) {
    Param c = p;
    c.type = typeRef(c.type, scope);
    if (c.defaultValue) c.defaultValue = expr(c.defaultValue, scope);
    c.annotations = annotations(c.annotations, scope);
    return c;
}

PackField DeclarationRenamer::field(const PackField& f, const Scope& scope) {
    PackField c = f;
    c.type = typeRef(c.type, scope);
    if (c.default_) c.default_ = expr(c.default_, scope);
    c.annotations = annotations(c.annotations, scope);
    if (c.condition) c.condition = expr(c.condition, scope);
    return c;
}

std::vector<Annotation> DeclarationRenamer::annotations(const std::vector<Annotation>& list, const Scope& scope) {
    std::vector<Annotation> out;
    out.reserve(list.size());
    for (const Annotation& a : list) {
        Annotation c = a;
        if (!c.qualifier) c.name = typeName(c.name, scope);
        c.args = exprs(c.args, scope);
        for (auto& [name, value] : c.namedArgs) value = expr(value, scope);
        out.push_back(std::move(c));
    }
    return out;
}

TypeAnnotation DeclarationRenamer::annotation(const TypeAnnotation& a, const Scope& scope) {
    if (a.isExplicit()) return TypeAnnotation{typeRef(a.ref, scope)};
    return a;
}

/** A type macro's name is renamed only by a block import that binds it. */
TypeRef DeclarationRenamer::typeMacroCall(const TypeRefNamed& ref, const Scope& scope) {
    std::string name = NamedTypeMacroCall::name(ref);
    auto it = scope.renames.find(name);
    std::string bound = it != scope.renames.end() ? it->second : name;
    return NamedTypeMacroCall::create(arena, bound, typeRefs(ref.args, scope), NamedTypeMacroCall::modifier(ref),
                                      NamedTypeMacroCall::form(ref));
}

std::vector<TypeRef> DeclarationRenamer::typeRefs(const std::vector<TypeRef>& refs, const Scope& scope) {
    std::vector<TypeRef> out;
    out.reserve(refs.size());
    for (const TypeRef& r : refs) out.push_back(typeRef(r, scope));
    return out;
}

TypeRef DeclarationRenamer::typeRef(const TypeRef& ref, const Scope& scope) {
    switch (ref.kind()) {
        case TypeRefKind::Named: {
            const TypeRefNamed& named = cast<TypeRefNamed>(ref);
            if (NamedTypeMacroCall::isCall(named)) return typeMacroCall(named, scope);
            TypeRefNamed c = named;
            if (!c.qualifier) c.name = typeName(c.name, scope);
            c.args = typeRefs(c.args, scope);
            c.valueArgs = exprs(c.valueArgs, scope);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Array: {
            TypeRefArray c = cast<TypeRefArray>(ref);
            c.element = typeRef(c.element, scope);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Map: {
            TypeRefMap c = cast<TypeRefMap>(ref);
            c.key = typeRef(c.key, scope);
            c.value = typeRef(c.value, scope);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Set: {
            TypeRefSet c = cast<TypeRefSet>(ref);
            c.element = typeRef(c.element, scope);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Function: {
            TypeRefFunction c = cast<TypeRefFunction>(ref);
            c.params = typeRefs(c.params, scope);
            c.ret = typeRef(c.ret, scope);
            c.receivers = typeRefs(c.receivers, scope);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Tuple: {
            TypeRefTuple c = cast<TypeRefTuple>(ref);
            c.elements = typeRefs(c.elements, scope);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Nullable: {
            TypeRefNullable c = cast<TypeRefNullable>(ref);
            c.inner = typeRef(c.inner, scope);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Failable: {
            TypeRefFailable c = cast<TypeRefFailable>(ref);
            c.ok = typeRef(c.ok, scope);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Pointer: {
            TypeRefPointer c = cast<TypeRefPointer>(ref);
            c.inner = typeRef(c.inner, scope);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Reference: {
            TypeRefReference c = cast<TypeRefReference>(ref);
            c.inner = typeRef(c.inner, scope);
            return make(arena, std::move(c));
        }
        case TypeRefKind::None:
        case TypeRefKind::Const:
            return ref;
    }
    return ref;
}

/** A block's own imports bind for it and the blocks nested in it; the statements leave. */
std::vector<Stmt> DeclarationRenamer::block(const std::vector<Stmt>& body, const Scope& scope) {
    std::vector<StmtImport*> imports;
    for (const Stmt& s : body) {
        if (StmtImport* import = as<StmtImport>(s)) imports.push_back(import);
    }
    std::vector<Stmt> out;
    out.reserve(body.size());
    if (imports.empty()) {
        for (const Stmt& s : body) out.push_back(stmt(s, scope));
        return out;
    }
    if (onImportingBlock) onImportingBlock(imports, body);
    Scope inner = scope;
    for (StmtImport* import : imports) {
        auto it = bodyImports.find(import);
        if (it == bodyImports.end()) continue;
        for (const auto& [spelling, identity] : it->second.entries) inner.renames[spelling] = identity;
    }
    for (const Stmt& s : body) {
        if (!is<StmtImport>(s)) out.push_back(stmt(s, inner));
    }
    return out;
}

std::vector<Expr> DeclarationRenamer::exprs(const std::vector<Expr>& items, const Scope& scope) {
    std::vector<Expr> out;
    out.reserve(items.size());
    for (const Expr& e : items) out.push_back(expr(e, scope));
    return out;
}

Stmt DeclarationRenamer::stmt(const Stmt& s, const Scope& scope) {
    auto ex = [&](const Expr& e) { return expr(e, scope); };
    switch (s.kind()) {
        case StmtKind::VarDecl: {
            StmtVarDecl c = cast<StmtVarDecl>(s);
            c.type = annotation(c.type, scope);
            c.initializer = ex(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::FinDecl: {
            StmtFinDecl c = cast<StmtFinDecl>(s);
            c.type = annotation(c.type, scope);
            c.initializer = ex(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::LetDecl: {
            StmtLetDecl c = cast<StmtLetDecl>(s);
            c.type = annotation(c.type, scope);
            c.initializer = ex(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineFin: {
            StmtInlineFin c = cast<StmtInlineFin>(s);
            c.type = annotation(c.type, scope);
            c.initializer = ex(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineLet: {
            StmtInlineLet c = cast<StmtInlineLet>(s);
            c.type = annotation(c.type, scope);
            c.initializer = ex(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineVar: {
            StmtInlineVar c = cast<StmtInlineVar>(s);
            c.type = annotation(c.type, scope);
            c.initializer = ex(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::RemDecl: {
            StmtRemDecl c = cast<StmtRemDecl>(s);
            c.type = annotation(c.type, scope);
            c.initializer = ex(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineAssignment: {
            StmtInlineAssignment c = cast<StmtInlineAssignment>(s);
            c.name = value(c.name, scope);
            c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::Assignment: {
            StmtAssignment c = cast<StmtAssignment>(s);
            c.name = value(c.name, scope);
            c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::Exchange: {
            StmtExchange c = cast<StmtExchange>(s);
            c.left = ex(c.left);
            c.right = ex(c.right);
            return make(arena, std::move(c));
        }
        case StmtKind::IndexAssign: {
            StmtIndexAssign c = cast<StmtIndexAssign>(s);
            c.target = ex(c.target);
            c.index = ex(c.index);
            c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::MemberAssign: {
            StmtMemberAssign c = cast<StmtMemberAssign>(s);
            c.target = ex(c.target);
            c.value = ex(c.value);
            if (c.nameExpr) c.nameExpr = ex(c.nameExpr);
            return make(arena, std::move(c));
        }
        case StmtKind::DerefAssign: {
            StmtDerefAssign c = cast<StmtDerefAssign>(s);
            c.target = ex(c.target);
            c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::Return: {
            StmtReturn c = cast<StmtReturn>(s);
            if (c.value) c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::ExprStmt: {
            StmtExprStmt c = cast<StmtExprStmt>(s);
            c.expr = ex(c.expr);
            return make(arena, std::move(c));
        }
        case StmtKind::Throw: {
            StmtThrow c = cast<StmtThrow>(s);
            c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::Panic: {
            StmtPanic c = cast<StmtPanic>(s);
            c.message = ex(c.message);
            return make(arena, std::move(c));
        }
        case StmtKind::Yield: {
            StmtYield c = cast<StmtYield>(s);
            c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::Assert: {
            StmtAssert c = cast<StmtAssert>(s);
            c.condition = ex(c.condition);
            c.message = ex(c.message);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineAssert: {
            StmtInlineAssert c = cast<StmtInlineAssert>(s);
            c.condition = ex(c.condition);
            c.message = ex(c.message);
            return make(arena, std::move(c));
        }
        case StmtKind::Trace: {
            StmtTrace c = cast<StmtTrace>(s);
            c.message = ex(c.message);
            if (c.level) c.level = ex(c.level);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineTrace: {
            StmtInlineTrace c = cast<StmtInlineTrace>(s);
            c.message = ex(c.message);
            if (c.level) c.level = ex(c.level);
            return make(arena, std::move(c));
        }
        case StmtKind::Scope: {
            StmtScope c = cast<StmtScope>(s);
            c.body = block(c.body, scope);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineBlock: {
            StmtInlineBlock c = cast<StmtInlineBlock>(s);
            c.body = block(c.body, scope);
            return make(arena, std::move(c));
        }
        case StmtKind::DeepInlineBlock: {
            StmtDeepInlineBlock c = cast<StmtDeepInlineBlock>(s);
            c.body = block(c.body, scope);
            return make(arena, std::move(c));
        }
        case StmtKind::NoInline: {
            StmtNoInline c = cast<StmtNoInline>(s);
            c.stmt = stmt(c.stmt, scope);
            return make(arena, std::move(c));
        }
        case StmtKind::If: {
            StmtIf c = cast<StmtIf>(s);
            c.condition = ex(c.condition);
            c.thenBranch = block(c.thenBranch, scope);
            if (c.elseBranch) c.elseBranch = block(*c.elseBranch, scope);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineIf: {
            StmtInlineIf c = cast<StmtInlineIf>(s);
            c.condition = ex(c.condition);
            c.thenBranch = block(c.thenBranch, scope);
            if (c.elseBranch) c.elseBranch = block(*c.elseBranch, scope);
            return make(arena, std::move(c));
        }
        case StmtKind::DeepInlineIf: {
            StmtDeepInlineIf c = cast<StmtDeepInlineIf>(s);
            c.condition = ex(c.condition);
            c.thenBranch = block(c.thenBranch, scope);
            if (c.elseBranch) c.elseBranch = block(*c.elseBranch, scope);
            return make(arena, std::move(c));
        }
        case StmtKind::While: {
            StmtWhile c = cast<StmtWhile>(s);
            c.condition = ex(c.condition);
            c.body = block(c.body, scope);
            return make(arena, std::move(c));
        }
        case StmtKind::For: {
            StmtFor c = cast<StmtFor>(s);
            c.iterable = ex(c.iterable);
            c.body = block(c.body, scope);
            if (c.step) c.step = ex(c.step);
            if (c.declaredType) c.declaredType = typeRef(c.declaredType, scope);
            return make(arena, std::move(c));
        }
        case StmtKind::Loop: {
            StmtLoop c = cast<StmtLoop>(s);
            c.body = block(c.body, scope);
            if (c.iterable) c.iterable = ex(c.iterable);
            if (c.everySeconds) c.everySeconds = ex(c.everySeconds);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineFor: {
            StmtInlineFor c = cast<StmtInlineFor>(s);
            c.iterable = ex(c.iterable);
            c.body = block(c.body, scope);
            return make(arena, std::move(c));
        }
        case StmtKind::Break: {
            StmtBreak c = cast<StmtBreak>(s);
            if (c.value) c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::When: {
            StmtWhen c = cast<StmtWhen>(s);
            c.scrutinee = ex(c.scrutinee);
            for (WhenBranch& branch : c.branches) {
                // `.Some(v)` binds `v` rather than reading it.
                for (Expr& p : branch.patterns) {
                    if (!is<ExprInferredMember>(p)) p = ex(p);
                }
                branch.body = block(branch.body, scope);
            }
            if (c.elseBranch) c.elseBranch = block(*c.elseBranch, scope);
            return make(arena, std::move(c));
        }
        case StmtKind::Try: {
            StmtTry c = cast<StmtTry>(s);
            c.body = block(c.body, scope);
            if (c.catchBody) c.catchBody = block(*c.catchBody, scope);
            return make(arena, std::move(c));
        }
        case StmtKind::Defer: {
            StmtDefer c = cast<StmtDefer>(s);
            c.body = block(c.body, scope);
            return make(arena, std::move(c));
        }
        case StmtKind::Effect: {
            StmtEffect c = cast<StmtEffect>(s);
            c.body = block(c.body, scope);
            if (c.dependencies) c.dependencies = exprs(*c.dependencies, scope);
            if (c.condition) c.condition = ex(c.condition);
            return make(arena, std::move(c));
        }
        case StmtKind::UsingContext: {
            StmtUsingContext c = cast<StmtUsingContext>(s);
            c.values = exprs(c.values, scope);
            c.body = block(c.body, scope);
            return make(arena, std::move(c));
        }
        default:
            return s;
    }
}

Expr DeclarationRenamer::expr(const Expr& e, const Scope& scope) {
    auto ex = [&](const Expr& x) { return expr(x, scope); };
    switch (e.kind()) {
        case ExprKind::Identifier: {
            ExprIdentifier c = cast<ExprIdentifier>(e);
            c.name = value(c.name, scope);
            return make(arena, std::move(c));
        }
        case ExprKind::Call: {
            ExprCall c = cast<ExprCall>(e);
            c.callee = value(c.callee, scope);
            c.args = exprs(c.args, scope);
            c.typeArgs = typeRefs(c.typeArgs, scope);
            if (c.receiver) c.receiver = ex(c.receiver);
            return make(arena, std::move(c));
        }
        case ExprKind::Binary: {
            ExprBinary c = cast<ExprBinary>(e);
            c.left = ex(c.left);
            c.right = ex(c.right);
            return make(arena, std::move(c));
        }
        case ExprKind::Unary: {
            ExprUnary c = cast<ExprUnary>(e);
            c.operand = ex(c.operand);
            return make(arena, std::move(c));
        }
        case ExprKind::IncDec: {
            ExprIncDec c = cast<ExprIncDec>(e);
            c.target = ex(c.target);
            return make(arena, std::move(c));
        }
        case ExprKind::Grouping: {
            ExprGrouping c = cast<ExprGrouping>(e);
            c.expr = ex(c.expr);
            return make(arena, std::move(c));
        }
        case ExprKind::MapEntryArg: {
            ExprMapEntryArg c = cast<ExprMapEntryArg>(e);
            c.key = ex(c.key);
            c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case ExprKind::Range: {
            ExprRange c = cast<ExprRange>(e);
            c.from = ex(c.from);
            c.to = ex(c.to);
            return make(arena, std::move(c));
        }
        case ExprKind::ArrayLiteral: {
            ExprArrayLiteral c = cast<ExprArrayLiteral>(e);
            c.elements = exprs(c.elements, scope);
            return make(arena, std::move(c));
        }
        case ExprKind::SetLiteral: {
            ExprSetLiteral c = cast<ExprSetLiteral>(e);
            c.elements = exprs(c.elements, scope);
            return make(arena, std::move(c));
        }
        case ExprKind::MapLit: {
            ExprMapLit c = cast<ExprMapLit>(e);
            for (std::pair<Expr, Expr>& entry : c.entries) {
                entry.first = ex(entry.first);
                entry.second = ex(entry.second);
            }
            return make(arena, std::move(c));
        }
        case ExprKind::Index: {
            ExprIndex c = cast<ExprIndex>(e);
            c.target = ex(c.target);
            c.index = ex(c.index);
            return make(arena, std::move(c));
        }
        case ExprKind::InferredMember: {
            ExprInferredMember c = cast<ExprInferredMember>(e);
            if (c.ctorArgs) c.ctorArgs = exprs(*c.ctorArgs, scope);
            return make(arena, std::move(c));
        }
        case ExprKind::Member: {
            ExprMember c = cast<ExprMember>(e);
            c.target = ex(c.target);
            if (c.nameExpr) c.nameExpr = ex(c.nameExpr);
            return make(arena, std::move(c));
        }
        case ExprKind::MethodCall: {
            ExprMethodCall c = cast<ExprMethodCall>(e);
            c.target = ex(c.target);
            c.args = exprs(c.args, scope);
            return make(arena, std::move(c));
        }
        case ExprKind::StringTemplate: {
            ExprStringTemplate c = cast<ExprStringTemplate>(e);
            for (StringTemplatePart& part : c.parts) {
                if (!part.isLiteral) part = exprPart(ex(part.expr));
            }
            return make(arena, std::move(c));
        }
        case ExprKind::TupleLit: {
            ExprTupleLit c = cast<ExprTupleLit>(e);
            c.elements = exprs(c.elements, scope);
            return make(arena, std::move(c));
        }
        case ExprKind::VariantLit: {
            ExprVariantLit c = cast<ExprVariantLit>(e);
            c.elements = exprs(c.elements, scope);
            return make(arena, std::move(c));
        }
        case ExprKind::TupleAccess: {
            ExprTupleAccess c = cast<ExprTupleAccess>(e);
            c.target = ex(c.target);
            return make(arena, std::move(c));
        }
        case ExprKind::CatchExpr: {
            ExprCatchExpr c = cast<ExprCatchExpr>(e);
            c.expr = ex(c.expr);
            c.fallback = ex(c.fallback);
            return make(arena, std::move(c));
        }
        case ExprKind::TryPropagate: {
            ExprTryPropagate c = cast<ExprTryPropagate>(e);
            c.expr = ex(c.expr);
            return make(arena, std::move(c));
        }
        case ExprKind::IfExpr: {
            ExprIfExpr c = cast<ExprIfExpr>(e);
            c.condition = ex(c.condition);
            c.thenExpr = ex(c.thenExpr);
            c.elseExpr = ex(c.elseExpr);
            return make(arena, std::move(c));
        }
        case ExprKind::Seal: {
            ExprSeal c = cast<ExprSeal>(e);
            c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case ExprKind::Lambda: {
            const ExprLambda& lambda = cast<ExprLambda>(e);
            std::vector<std::string> valueNames;
            for (const Param& p : lambda.params) valueNames.push_back(p.name);
            for (const Param& p : lambda.receivers) valueNames.push_back(p.name);
            for (const std::string& local : localNamesDeclaredIn(lambda.body).items) valueNames.push_back(local);
            Scope inner = withValues(withTypes(scope, lambda.typeParams), valueNames);
            ExprLambda c = lambda;
            for (Param& p : c.params) p = param(p, inner);
            for (Param& p : c.receivers) p = param(p, inner);
            c.body = block(c.body, inner);
            return make(arena, std::move(c));
        }
        case ExprKind::InlineForArgs: {
            ExprInlineForArgs c = cast<ExprInlineForArgs>(e);
            c.iterable = ex(c.iterable);
            c.body = ex(c.body);
            return make(arena, std::move(c));
        }
        case ExprKind::NamedArg: {
            ExprNamedArg c = cast<ExprNamedArg>(e);
            c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case ExprKind::NullCoalesce: {
            ExprNullCoalesce c = cast<ExprNullCoalesce>(e);
            c.left = ex(c.left);
            c.right = ex(c.right);
            return make(arena, std::move(c));
        }
        case ExprKind::SafeMember: {
            ExprSafeMember c = cast<ExprSafeMember>(e);
            c.target = ex(c.target);
            return make(arena, std::move(c));
        }
        case ExprKind::Cast: {
            ExprCast c = cast<ExprCast>(e);
            c.expr = ex(c.expr);
            c.targetType = typeRef(c.targetType, scope);
            return make(arena, std::move(c));
        }
        case ExprKind::IsCheck: {
            ExprIsCheck c = cast<ExprIsCheck>(e);
            c.expr = ex(c.expr);
            c.typeName = typeName(c.typeName, scope);
            c.typeArgs = typeRefs(c.typeArgs, scope);
            return make(arena, std::move(c));
        }
        case ExprKind::InCheck: {
            ExprInCheck c = cast<ExprInCheck>(e);
            c.value = ex(c.value);
            c.collection = ex(c.collection);
            return make(arena, std::move(c));
        }
        case ExprKind::Alloc: {
            ExprAlloc c = cast<ExprAlloc>(e);
            c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case ExprKind::Deref: {
            ExprDeref c = cast<ExprDeref>(e);
            c.target = ex(c.target);
            return make(arena, std::move(c));
        }
        case ExprKind::Isolated: {
            ExprIsolated c = cast<ExprIsolated>(e);
            c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case ExprKind::Await: {
            ExprAwait c = cast<ExprAwait>(e);
            c.value = ex(c.value);
            return make(arena, std::move(c));
        }
        case ExprKind::Inject: {
            ExprInject c = cast<ExprInject>(e);
            c.typeName = typeName(c.typeName, scope);
            return make(arena, std::move(c));
        }
        case ExprKind::Spread: {
            ExprSpread c = cast<ExprSpread>(e);
            c.array = ex(c.array);
            return make(arena, std::move(c));
        }
        case ExprKind::MetaInvoke: {
            ExprMetaInvoke c = cast<ExprMetaInvoke>(e);
            c.args = exprs(c.args, scope);
            return make(arena, std::move(c));
        }
        case ExprKind::Slice: {
            ExprSlice c = cast<ExprSlice>(e);
            c.target = ex(c.target);
            if (c.start) c.start = ex(c.start);
            if (c.stop) c.stop = ex(c.stop);
            if (c.step) c.step = ex(c.step);
            return make(arena, std::move(c));
        }
        default:
            return e;
    }
}

}  // namespace azora
