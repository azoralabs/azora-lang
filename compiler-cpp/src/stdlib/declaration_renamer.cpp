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

std::vector<TopLevelUseImport*> writtenImports(Arena&, const Program& program) {
    std::vector<TopLevelUseImport*> imports;
    for (const TopLevel& item : program.items) {
        if (TopLevelUseImport* use = as<TopLevelUseImport>(item)) imports.push_back(use);
    }
    auto collect = [&](const std::vector<StmtImport*>& found, const std::vector<Stmt>&) {
        for (StmtImport* import : found) imports.push_back(import->use);
    };
    for (const TopLevel& item : program.items) {
        if (!is<TopLevelUseImport>(item)) forEachImportingBlock(item, collect);
    }
    return imports;
}

// ---------------------------------------------------------------------------
// The read-only walk: DeclarationRenamer's visiting order, nothing built
// ---------------------------------------------------------------------------

namespace {

struct ImportingBlockWalker {
    const std::function<void(const std::vector<StmtImport*>&, const std::vector<Stmt>&)>& onImportingBlock;

    void exprs(const std::vector<Expr>& items) {
        for (const Expr& e : items) expr(e);
    }
    void typeRefs(const std::vector<TypeRef>& refs) {
        for (const TypeRef& r : refs) typeRef(r);
    }
    void annotations(const std::vector<Annotation>& list) {
        for (const Annotation& a : list) {
            exprs(a.args);
            for (const auto& [name, value] : a.namedArgs) expr(value);
        }
    }
    void annotation(const TypeAnnotation& a) {
        if (a.isExplicit()) typeRef(a.ref);
    }
    void param(const Param& p) {
        typeRef(p.type);
        if (p.defaultValue) expr(p.defaultValue);
        annotations(p.annotations);
    }
    void field(const PackField& f) {
        typeRef(f.type);
        if (f.default_) expr(f.default_);
        annotations(f.annotations);
        if (f.condition) expr(f.condition);
    }
    void func(const FuncDecl& decl) {
        for (const Param& p : decl.params) param(p);
        annotation(decl.returnType);
        block(decl.body);
        annotations(decl.annotations);
        if (decl.whereClause) expr(decl.whereClause);
    }

    void item(const TopLevel& item) {
        switch (item.kind()) {
            case TopLevelKind::Func: func(cast<TopLevelFunc>(item).decl); return;
            case TopLevelKind::VarDecl:
                if (cast<TopLevelVarDecl>(item).type) typeRef(cast<TopLevelVarDecl>(item).type);
                expr(cast<TopLevelVarDecl>(item).initializer);
                return;
            case TopLevelKind::FinDecl:
                if (cast<TopLevelFinDecl>(item).type) typeRef(cast<TopLevelFinDecl>(item).type);
                expr(cast<TopLevelFinDecl>(item).initializer);
                return;
            case TopLevelKind::LetDecl:
                if (cast<TopLevelLetDecl>(item).type) typeRef(cast<TopLevelLetDecl>(item).type);
                expr(cast<TopLevelLetDecl>(item).initializer);
                return;
            case TopLevelKind::InlineFin: expr(cast<TopLevelInlineFin>(item).initializer); return;
            case TopLevelKind::InlineLet: expr(cast<TopLevelInlineLet>(item).initializer); return;
            case TopLevelKind::InlineVar: expr(cast<TopLevelInlineVar>(item).initializer); return;
            case TopLevelKind::Test: block(cast<TopLevelTest>(item).body); return;
            case TopLevelKind::Pack: {
                const TopLevelPack& p = cast<TopLevelPack>(item);
                for (const PackField& f : p.fields) field(f);
                annotations(p.annotations);
                if (p.whereClause) expr(p.whereClause);
                return;
            }
            case TopLevelKind::Deco: {
                const TopLevelDeco& d = cast<TopLevelDeco>(item);
                for (const PackField& f : d.fields) field(f);
                annotations(d.annotations);
                for (const DecoratorBinding& b : d.bindings) typeRefs(b.trailingTypeArgs);
                return;
            }
            case TopLevelKind::Enum:
                annotations(cast<TopLevelEnum>(item).annotations);
                if (cast<TopLevelEnum>(item).whereClause) expr(cast<TopLevelEnum>(item).whereClause);
                return;
            case TopLevelKind::Fail:
                for (const std::vector<TypeRef>& payload : cast<TopLevelFail>(item).variantPayloads) typeRefs(payload);
                if (cast<TopLevelFail>(item).whereClause) expr(cast<TopLevelFail>(item).whereClause);
                return;
            case TopLevelKind::Slot:
                for (const SlotVariant& v : cast<TopLevelSlot>(item).variants) typeRefs(v.payloadTypes);
                if (cast<TopLevelSlot>(item).whereClause) expr(cast<TopLevelSlot>(item).whereClause);
                return;
            case TopLevelKind::Impl: {
                const TopLevelImpl& impl = cast<TopLevelImpl>(item);
                typeRefs(impl.traitArgs);
                for (const FuncDecl& m : impl.methods) func(m);
                exprs(impl.decoratorArgs);
                for (const auto& [name, value] : impl.decoratorNamedArgs) expr(value);
                annotations(impl.annotations);
                for (const auto& [name, bound] : impl.assocBindings.entries) typeRef(bound);
                return;
            }
            case TopLevelKind::Spec: {
                const TopLevelSpec& spec = cast<TopLevelSpec>(item);
                for (const FuncDecl& m : spec.methods) func(m);
                typeRefs(spec.parents);
                typeRefs(spec.requires_);
                for (const auto& [name, def] : spec.typeDefaults.entries) typeRef(def);
                if (spec.whereClause) expr(spec.whereClause);
                return;
            }
            case TopLevelKind::TypeAlias:
                typeRef(cast<TopLevelTypeAlias>(item).type);
                if (cast<TopLevelTypeAlias>(item).whereClause) expr(cast<TopLevelTypeAlias>(item).whereClause);
                return;
            case TopLevelKind::InlineAssert:
                expr(cast<TopLevelInlineAssert>(item).condition);
                expr(cast<TopLevelInlineAssert>(item).message);
                return;
            case TopLevelKind::InlineTrace:
                expr(cast<TopLevelInlineTrace>(item).message);
                if (cast<TopLevelInlineTrace>(item).level) expr(cast<TopLevelInlineTrace>(item).level);
                return;
            default:
                return;
        }
    }

    void typeRef(const TypeRef& ref) {
        switch (ref.kind()) {
            case TypeRefKind::Named: {
                const TypeRefNamed& named = cast<TypeRefNamed>(ref);
                typeRefs(named.args);
                if (!NamedTypeMacroCall::isCall(named)) exprs(named.valueArgs);
                return;
            }
            case TypeRefKind::Array: typeRef(cast<TypeRefArray>(ref).element); return;
            case TypeRefKind::Map: typeRef(cast<TypeRefMap>(ref).key); typeRef(cast<TypeRefMap>(ref).value); return;
            case TypeRefKind::Set: typeRef(cast<TypeRefSet>(ref).element); return;
            case TypeRefKind::Function:
                typeRefs(cast<TypeRefFunction>(ref).params);
                typeRef(cast<TypeRefFunction>(ref).ret);
                typeRefs(cast<TypeRefFunction>(ref).receivers);
                return;
            case TypeRefKind::Tuple: typeRefs(cast<TypeRefTuple>(ref).elements); return;
            case TypeRefKind::Nullable: typeRef(cast<TypeRefNullable>(ref).inner); return;
            case TypeRefKind::Failable: typeRef(cast<TypeRefFailable>(ref).ok); return;
            case TypeRefKind::Pointer: typeRef(cast<TypeRefPointer>(ref).inner); return;
            case TypeRefKind::Reference: typeRef(cast<TypeRefReference>(ref).inner); return;
            default: return;
        }
    }

    void block(const std::vector<Stmt>& body) {
        std::vector<StmtImport*> imports;
        for (const Stmt& s : body) {
            if (StmtImport* import = as<StmtImport>(s)) imports.push_back(import);
        }
        if (imports.empty()) {
            for (const Stmt& s : body) stmt(s);
            return;
        }
        onImportingBlock(imports, body);
        for (const Stmt& s : body) {
            if (!is<StmtImport>(s)) stmt(s);
        }
    }

    void stmt(const Stmt& s) {
        switch (s.kind()) {
            case StmtKind::VarDecl: annotation(cast<StmtVarDecl>(s).type); expr(cast<StmtVarDecl>(s).initializer); return;
            case StmtKind::FinDecl: annotation(cast<StmtFinDecl>(s).type); expr(cast<StmtFinDecl>(s).initializer); return;
            case StmtKind::LetDecl: annotation(cast<StmtLetDecl>(s).type); expr(cast<StmtLetDecl>(s).initializer); return;
            case StmtKind::InlineFin: annotation(cast<StmtInlineFin>(s).type); expr(cast<StmtInlineFin>(s).initializer); return;
            case StmtKind::InlineLet: annotation(cast<StmtInlineLet>(s).type); expr(cast<StmtInlineLet>(s).initializer); return;
            case StmtKind::InlineVar: annotation(cast<StmtInlineVar>(s).type); expr(cast<StmtInlineVar>(s).initializer); return;
            case StmtKind::RemDecl: annotation(cast<StmtRemDecl>(s).type); expr(cast<StmtRemDecl>(s).initializer); return;
            case StmtKind::InlineAssignment: expr(cast<StmtInlineAssignment>(s).value); return;
            case StmtKind::Assignment: expr(cast<StmtAssignment>(s).value); return;
            case StmtKind::Exchange: expr(cast<StmtExchange>(s).left); expr(cast<StmtExchange>(s).right); return;
            case StmtKind::IndexAssign:
                expr(cast<StmtIndexAssign>(s).target);
                expr(cast<StmtIndexAssign>(s).index);
                expr(cast<StmtIndexAssign>(s).value);
                return;
            case StmtKind::MemberAssign:
                expr(cast<StmtMemberAssign>(s).target);
                expr(cast<StmtMemberAssign>(s).value);
                if (cast<StmtMemberAssign>(s).nameExpr) expr(cast<StmtMemberAssign>(s).nameExpr);
                return;
            case StmtKind::DerefAssign: expr(cast<StmtDerefAssign>(s).target); expr(cast<StmtDerefAssign>(s).value); return;
            case StmtKind::Return:
                if (cast<StmtReturn>(s).value) expr(cast<StmtReturn>(s).value);
                return;
            case StmtKind::ExprStmt: expr(cast<StmtExprStmt>(s).expr); return;
            case StmtKind::Throw: expr(cast<StmtThrow>(s).value); return;
            case StmtKind::Panic: expr(cast<StmtPanic>(s).message); return;
            case StmtKind::Yield: expr(cast<StmtYield>(s).value); return;
            case StmtKind::Assert: expr(cast<StmtAssert>(s).condition); expr(cast<StmtAssert>(s).message); return;
            case StmtKind::InlineAssert: expr(cast<StmtInlineAssert>(s).condition); expr(cast<StmtInlineAssert>(s).message); return;
            case StmtKind::Trace:
                expr(cast<StmtTrace>(s).message);
                if (cast<StmtTrace>(s).level) expr(cast<StmtTrace>(s).level);
                return;
            case StmtKind::InlineTrace:
                expr(cast<StmtInlineTrace>(s).message);
                if (cast<StmtInlineTrace>(s).level) expr(cast<StmtInlineTrace>(s).level);
                return;
            case StmtKind::Scope: block(cast<StmtScope>(s).body); return;
            case StmtKind::InlineBlock: block(cast<StmtInlineBlock>(s).body); return;
            case StmtKind::DeepInlineBlock: block(cast<StmtDeepInlineBlock>(s).body); return;
            case StmtKind::NoInline: stmt(cast<StmtNoInline>(s).stmt); return;
            case StmtKind::If:
                expr(cast<StmtIf>(s).condition);
                block(cast<StmtIf>(s).thenBranch);
                if (cast<StmtIf>(s).elseBranch) block(*cast<StmtIf>(s).elseBranch);
                return;
            case StmtKind::InlineIf:
                expr(cast<StmtInlineIf>(s).condition);
                block(cast<StmtInlineIf>(s).thenBranch);
                if (cast<StmtInlineIf>(s).elseBranch) block(*cast<StmtInlineIf>(s).elseBranch);
                return;
            case StmtKind::DeepInlineIf:
                expr(cast<StmtDeepInlineIf>(s).condition);
                block(cast<StmtDeepInlineIf>(s).thenBranch);
                if (cast<StmtDeepInlineIf>(s).elseBranch) block(*cast<StmtDeepInlineIf>(s).elseBranch);
                return;
            case StmtKind::While: expr(cast<StmtWhile>(s).condition); block(cast<StmtWhile>(s).body); return;
            case StmtKind::For: {
                const StmtFor& f = cast<StmtFor>(s);
                expr(f.iterable);
                block(f.body);
                if (f.step) expr(f.step);
                if (f.declaredType) typeRef(f.declaredType);
                return;
            }
            case StmtKind::Loop: {
                const StmtLoop& l = cast<StmtLoop>(s);
                block(l.body);
                if (l.iterable) expr(l.iterable);
                if (l.everySeconds) expr(l.everySeconds);
                return;
            }
            case StmtKind::InlineFor: expr(cast<StmtInlineFor>(s).iterable); block(cast<StmtInlineFor>(s).body); return;
            case StmtKind::Break:
                if (cast<StmtBreak>(s).value) expr(cast<StmtBreak>(s).value);
                return;
            case StmtKind::When: {
                const StmtWhen& w = cast<StmtWhen>(s);
                expr(w.scrutinee);
                for (const WhenBranch& branch : w.branches) {
                    for (const Expr& p : branch.patterns) {
                        if (!is<ExprInferredMember>(p)) expr(p);
                    }
                    block(branch.body);
                }
                if (w.elseBranch) block(*w.elseBranch);
                return;
            }
            case StmtKind::Try:
                block(cast<StmtTry>(s).body);
                if (cast<StmtTry>(s).catchBody) block(*cast<StmtTry>(s).catchBody);
                return;
            case StmtKind::Defer: block(cast<StmtDefer>(s).body); return;
            case StmtKind::Effect: {
                const StmtEffect& e = cast<StmtEffect>(s);
                block(e.body);
                if (e.dependencies) exprs(*e.dependencies);
                if (e.condition) expr(e.condition);
                return;
            }
            case StmtKind::UsingContext: exprs(cast<StmtUsingContext>(s).values); block(cast<StmtUsingContext>(s).body); return;
            default: return;
        }
    }

    void expr(const Expr& e) {
        switch (e.kind()) {
            case ExprKind::Call: {
                const ExprCall& c = cast<ExprCall>(e);
                exprs(c.args);
                typeRefs(c.typeArgs);
                if (c.receiver) expr(c.receiver);
                return;
            }
            case ExprKind::Binary: expr(cast<ExprBinary>(e).left); expr(cast<ExprBinary>(e).right); return;
            case ExprKind::Unary: expr(cast<ExprUnary>(e).operand); return;
            case ExprKind::IncDec: expr(cast<ExprIncDec>(e).target); return;
            case ExprKind::Grouping: expr(cast<ExprGrouping>(e).expr); return;
            case ExprKind::MapEntryArg: expr(cast<ExprMapEntryArg>(e).key); expr(cast<ExprMapEntryArg>(e).value); return;
            case ExprKind::Range: expr(cast<ExprRange>(e).from); expr(cast<ExprRange>(e).to); return;
            case ExprKind::ArrayLiteral: exprs(cast<ExprArrayLiteral>(e).elements); return;
            case ExprKind::SetLiteral: exprs(cast<ExprSetLiteral>(e).elements); return;
            case ExprKind::MapLit:
                for (const auto& [key, value] : cast<ExprMapLit>(e).entries) {
                    expr(key);
                    expr(value);
                }
                return;
            case ExprKind::Index: expr(cast<ExprIndex>(e).target); expr(cast<ExprIndex>(e).index); return;
            case ExprKind::InferredMember:
                if (cast<ExprInferredMember>(e).ctorArgs) exprs(*cast<ExprInferredMember>(e).ctorArgs);
                return;
            case ExprKind::Member:
                expr(cast<ExprMember>(e).target);
                if (cast<ExprMember>(e).nameExpr) expr(cast<ExprMember>(e).nameExpr);
                return;
            case ExprKind::MethodCall: expr(cast<ExprMethodCall>(e).target); exprs(cast<ExprMethodCall>(e).args); return;
            case ExprKind::StringTemplate:
                for (const StringTemplatePart& part : cast<ExprStringTemplate>(e).parts) {
                    if (!part.isLiteral) expr(part.expr);
                }
                return;
            case ExprKind::TupleLit: exprs(cast<ExprTupleLit>(e).elements); return;
            case ExprKind::VariantLit: exprs(cast<ExprVariantLit>(e).elements); return;
            case ExprKind::TupleAccess: expr(cast<ExprTupleAccess>(e).target); return;
            case ExprKind::CatchExpr: expr(cast<ExprCatchExpr>(e).expr); expr(cast<ExprCatchExpr>(e).fallback); return;
            case ExprKind::TryPropagate: expr(cast<ExprTryPropagate>(e).expr); return;
            case ExprKind::IfExpr:
                expr(cast<ExprIfExpr>(e).condition);
                expr(cast<ExprIfExpr>(e).thenExpr);
                expr(cast<ExprIfExpr>(e).elseExpr);
                return;
            case ExprKind::Seal: expr(cast<ExprSeal>(e).value); return;
            case ExprKind::Lambda: {
                const ExprLambda& l = cast<ExprLambda>(e);
                for (const Param& p : l.params) param(p);
                for (const Param& p : l.receivers) param(p);
                block(l.body);
                return;
            }
            case ExprKind::InlineForArgs: expr(cast<ExprInlineForArgs>(e).iterable); expr(cast<ExprInlineForArgs>(e).body); return;
            case ExprKind::NamedArg: expr(cast<ExprNamedArg>(e).value); return;
            case ExprKind::NullCoalesce: expr(cast<ExprNullCoalesce>(e).left); expr(cast<ExprNullCoalesce>(e).right); return;
            case ExprKind::SafeMember: expr(cast<ExprSafeMember>(e).target); return;
            case ExprKind::Cast: expr(cast<ExprCast>(e).expr); typeRef(cast<ExprCast>(e).targetType); return;
            case ExprKind::IsCheck: expr(cast<ExprIsCheck>(e).expr); typeRefs(cast<ExprIsCheck>(e).typeArgs); return;
            case ExprKind::InCheck: expr(cast<ExprInCheck>(e).value); expr(cast<ExprInCheck>(e).collection); return;
            case ExprKind::Alloc: expr(cast<ExprAlloc>(e).value); return;
            case ExprKind::Deref: expr(cast<ExprDeref>(e).target); return;
            case ExprKind::Isolated: expr(cast<ExprIsolated>(e).value); return;
            case ExprKind::Await: expr(cast<ExprAwait>(e).value); return;
            case ExprKind::Spread: expr(cast<ExprSpread>(e).array); return;
            case ExprKind::MetaInvoke: exprs(cast<ExprMetaInvoke>(e).args); return;
            case ExprKind::Slice: {
                const ExprSlice& sl = cast<ExprSlice>(e);
                expr(sl.target);
                if (sl.start) expr(sl.start);
                if (sl.stop) expr(sl.stop);
                if (sl.step) expr(sl.step);
                return;
            }
            default:
                return;
        }
    }
};

}  // namespace

void forEachImportingBlock(const TopLevel& item,
                           const std::function<void(const std::vector<StmtImport*>&, const std::vector<Stmt>&)>& onImportingBlock) {
    ImportingBlockWalker walker{onImportingBlock};
    walker.item(item);
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
