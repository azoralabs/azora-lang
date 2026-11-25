// Port of stdlib/StdlibInjector.kt, part 4: reference collection - every
// name a declaration, statement, expression or type refers to, in the order
// it first names them.
#include "stdlib/stdlib_injector.h"

#include "base/text.h"
#include "frontend/ast_util.h"

namespace azora {

namespace {

const char* const INTRINSIC_RANGE = "Range";
const char* const INTRINSIC_TUPLE = "Tuple";

/**
 * Declarations a compiler-synthesized builtin needs, keyed by the builtin's
 * callee name: dependencies no source text states.
 */
const char* builtinTypeDependency(const std::string& callee) {
    if (callee == "__defaultLogLevel") return "LogLevel";
    return nullptr;
}

/** Declarations a member access needs, keyed by the member's name (a variadic parameter is an `Array`). */
const char* builtinMemberDependency(const std::string& member) {
    if (member == "size" || member == "length" || member == "isEmpty" || member == "isNotEmpty") return "Array";
    return nullptr;
}

}  // namespace

void StdlibInjector::collectNamesFromItem(const TopLevel& item, OrderedSet<std::string>& names) {
    switch (item.kind()) {
        case TopLevelKind::Func:
            collectNamesFromAnnotations(cast<TopLevelFunc>(item).decl.annotations, names);
            collectNamesFromFunc(cast<TopLevelFunc>(item).decl, names);
            return;
        case TopLevelKind::FinDecl: {
            const TopLevelFinDecl& d = cast<TopLevelFinDecl>(item);
            collectNamesFromAnnotations(d.annotations, names);
            if (d.type) collectNamesFromTypeRef(d.type, names);
            collectNamesFromExpr(d.initializer, names);
            return;
        }
        case TopLevelKind::LetDecl: {
            const TopLevelLetDecl& d = cast<TopLevelLetDecl>(item);
            collectNamesFromAnnotations(d.annotations, names);
            if (d.type) collectNamesFromTypeRef(d.type, names);
            collectNamesFromExpr(d.initializer, names);
            return;
        }
        case TopLevelKind::InlineFin: collectNamesFromExpr(cast<TopLevelInlineFin>(item).initializer, names); return;
        case TopLevelKind::InlineLet: collectNamesFromExpr(cast<TopLevelInlineLet>(item).initializer, names); return;
        case TopLevelKind::InlineVar: collectNamesFromExpr(cast<TopLevelInlineVar>(item).initializer, names); return;
        case TopLevelKind::InlineTrace: {
            const TopLevelInlineTrace& t = cast<TopLevelInlineTrace>(item);
            if (t.level) collectNamesFromExpr(t.level, names);
            collectNamesFromExpr(t.message, names);
            return;
        }
        case TopLevelKind::VarDecl: {
            const TopLevelVarDecl& d = cast<TopLevelVarDecl>(item);
            collectNamesFromAnnotations(d.annotations, names);
            if (d.type) collectNamesFromTypeRef(d.type, names);
            collectNamesFromExpr(d.initializer, names);
            return;
        }
        case TopLevelKind::Test: {
            const TopLevelTest& t = cast<TopLevelTest>(item);
            collectNamesFromAnnotations(t.annotations, names);
            for (const Stmt& s : t.body) collectNamesFromStmt(s, names);
            return;
        }
        case TopLevelKind::Pack: {
            const TopLevelPack& p = cast<TopLevelPack>(item);
            collectNamesFromAnnotations(p.annotations, names);
            if (p.nameMacro) collectNamesFromExpr(p.nameMacro, names);
            // `O: MatrixOrder` is part of the pack's signature; a use site never writes the enum's name.
            for (const auto& [param, enumName] : p.constEnums.entries) names.add(enumName);
            for (const PackField& field : p.fields) {
                collectNamesFromAnnotations(field.annotations, names);
                collectNamesFromTypeAnnotation(TypeAnnotation{field.type}, names);
                if (field.default_) collectNamesFromExpr(field.default_, names);
            }
            return;
        }
        case TopLevelKind::Enum: {
            const TopLevelEnum& e = cast<TopLevelEnum>(item);
            std::vector<Annotation> all = e.annotations;
            for (const std::vector<Annotation>& a : e.variantAnnotations) all.insert(all.end(), a.begin(), a.end());
            collectNamesFromAnnotations(all, names);
            return;
        }
        case TopLevelKind::Fail: {
            const TopLevelFail& f = cast<TopLevelFail>(item);
            std::vector<Annotation> all = f.annotations;
            for (const std::vector<Annotation>& a : f.variantAnnotations) all.insert(all.end(), a.begin(), a.end());
            collectNamesFromAnnotations(all, names);
            return;
        }
        case TopLevelKind::Solo: {
            const TopLevelSolo& s = cast<TopLevelSolo>(item);
            collectNamesFromAnnotations(s.annotations, names);
            for (const PackField& field : s.fields) {
                collectNamesFromAnnotations(field.annotations, names);
                collectNamesFromTypeRef(field.type, names);
                if (field.default_) collectNamesFromExpr(field.default_, names);
            }
            for (const FuncDecl& m : s.methods) collectNamesFromFunc(m, names);
            return;
        }
        case TopLevelKind::Impl: {
            const TopLevelImpl& impl = cast<TopLevelImpl>(item);
            names.add(impl.typeName);
            if (impl.traitName) names.add(*impl.traitName);
            for (const TypeRef& t : impl.traitArgs) collectNamesFromTypeRef(t, names);
            for (const Expr& e : impl.decoratorArgs) collectNamesFromExpr(e, names);
            for (const auto& [name, value] : impl.decoratorNamedArgs) collectNamesFromExpr(value, names);
            for (const FuncDecl& m : impl.methods) collectNamesFromFunc(m, names);
            return;
        }
        case TopLevelKind::Spec: {
            const TopLevelSpec& spec = cast<TopLevelSpec>(item);
            if (spec.callback) {
                collectNamesFromTypeRef(spec.callback->returnType, names);
                for (const Param& p : spec.callback->params) collectNamesFromTypeRef(p.type, names);
            }
            for (const FuncDecl& m : spec.methods) collectNamesFromFunc(m, names);
            return;
        }
        case TopLevelKind::Deco: {
            const TopLevelDeco& deco = cast<TopLevelDeco>(item);
            collectNamesFromAnnotations(deco.annotations, names);
            for (const PackField& field : deco.fields) {
                collectNamesFromAnnotations(field.annotations, names);
                collectNamesFromTypeRef(field.type, names);
                if (field.default_) collectNamesFromExpr(field.default_, names);
            }
            for (const DecoratorBinding& binding : deco.bindings) {
                names.add(binding.name);
                for (const TypeRef& t : binding.trailingTypeArgs) collectNamesFromTypeRef(t, names);
            }
            return;
        }
        case TopLevelKind::InlineIf: {
            const TopLevelInlineIf& i = cast<TopLevelInlineIf>(item);
            collectNamesFromExpr(i.condition, names);
            for (const TopLevel& t : i.thenBranch) collectNamesFromItem(t, names);
            if (i.elseBranch) {
                for (const TopLevel& t : *i.elseBranch) collectNamesFromItem(t, names);
            }
            return;
        }
        case TopLevelKind::DeepInlineIf: {
            const TopLevelDeepInlineIf& i = cast<TopLevelDeepInlineIf>(item);
            collectNamesFromExpr(i.condition, names);
            for (const TopLevel& t : i.thenBranch) collectNamesFromItem(t, names);
            if (i.elseBranch) {
                for (const TopLevel& t : *i.elseBranch) collectNamesFromItem(t, names);
            }
            return;
        }
        case TopLevelKind::InlineBlock:
            for (const TopLevel& t : cast<TopLevelInlineBlock>(item).body) collectNamesFromItem(t, names);
            return;
        case TopLevelKind::DeepInlineBlock:
            for (const TopLevel& t : cast<TopLevelDeepInlineBlock>(item).body) collectNamesFromItem(t, names);
            return;
        case TopLevelKind::Slot: collectNamesFromAnnotations(cast<TopLevelSlot>(item).annotations, names); return;
        case TopLevelKind::TypeAlias:
            collectNamesFromAnnotations(cast<TopLevelTypeAlias>(item).annotations, names);
            collectNamesFromTypeRef(cast<TopLevelTypeAlias>(item).type, names);
            return;
        case TopLevelKind::Bridge: {
            const TopLevelBridge& bridge = cast<TopLevelBridge>(item);
            collectNamesFromAnnotations(bridge.annotations, names);
            for (const BridgeSig& signature : bridge.funcs) {
                if (signature.nameMacro) collectNamesFromExpr(signature.nameMacro, names);
                for (const Param& p : signature.params) collectNamesFromTypeRef(p.type, names);
                collectNamesFromTypeRef(signature.returnType, names);
            }
            for (const BridgeValue& value : bridge.values) {
                if (value.nameMacro) collectNamesFromExpr(value.nameMacro, names);
                collectNamesFromTypeRef(value.type, names);
                collectNamesFromExpr(value.initializer, names);
            }
            return;
        }
        // A `meta` is registered by name, but its arm templates are not dependencies.
        case TopLevelKind::Meta: names.add(cast<TopLevelMeta>(item).name); return;
        default: return;
    }
}

void StdlibInjector::collectNamesFromFunc(const FuncDecl& func, OrderedSet<std::string>& names) {
    collectNamesFromAnnotations(func.annotations, names);
    for (const Param& p : func.params) {
        collectNamesFromAnnotations(p.annotations, names);
        collectNamesFromTypeAnnotation(TypeAnnotation{p.type}, names);
        // A default value runs at every call site that omits the argument.
        if (p.defaultValue) collectNamesFromExpr(p.defaultValue, names);
    }
    collectNamesFromTypeAnnotation(func.returnType, names);
    for (const Stmt& s : func.body) collectNamesFromStmt(s, names);
}

void StdlibInjector::collectNamesFromAnnotations(const std::vector<Annotation>& annotations, OrderedSet<std::string>& names) {
    for (const Annotation& annotation : annotations) {
        names.add(annotation.name);
        for (const Expr& a : annotation.args) collectNamesFromExpr(a, names);
        for (const auto& [name, value] : annotation.namedArgs) collectNamesFromExpr(value, names);
    }
}

void StdlibInjector::collectNamesFromStmt(const Stmt& stmt, OrderedSet<std::string>& names) {
    auto ex = [&](const Expr& e) { collectNamesFromExpr(e, names); };
    auto st = [&](const std::vector<Stmt>& body) {
        for (const Stmt& s : body) collectNamesFromStmt(s, names);
    };
    switch (stmt.kind()) {
        case StmtKind::Import: return;  // a path, not a reference
        case StmtKind::VarDecl:
            collectNamesFromTypeAnnotation(cast<StmtVarDecl>(stmt).type, names);
            ex(cast<StmtVarDecl>(stmt).initializer);
            return;
        case StmtKind::FinDecl:
            collectNamesFromTypeAnnotation(cast<StmtFinDecl>(stmt).type, names);
            ex(cast<StmtFinDecl>(stmt).initializer);
            return;
        case StmtKind::LetDecl:
            collectNamesFromTypeAnnotation(cast<StmtLetDecl>(stmt).type, names);
            ex(cast<StmtLetDecl>(stmt).initializer);
            return;
        case StmtKind::InlineVar:
            collectNamesFromTypeAnnotation(cast<StmtInlineVar>(stmt).type, names);
            ex(cast<StmtInlineVar>(stmt).initializer);
            return;
        case StmtKind::InlineFin:
            collectNamesFromTypeAnnotation(cast<StmtInlineFin>(stmt).type, names);
            ex(cast<StmtInlineFin>(stmt).initializer);
            return;
        case StmtKind::InlineLet:
            collectNamesFromTypeAnnotation(cast<StmtInlineLet>(stmt).type, names);
            ex(cast<StmtInlineLet>(stmt).initializer);
            return;
        case StmtKind::RemDecl:
            collectNamesFromTypeAnnotation(cast<StmtRemDecl>(stmt).type, names);
            ex(cast<StmtRemDecl>(stmt).initializer);
            return;
        // The target is a reference too, when it names a library value (not a function).
        case StmtKind::Assignment: {
            const StmtAssignment& a = cast<StmtAssignment>(stmt);
            const TopLevel* target = index().items.get(a.name);
            if (target && !is<TopLevelFunc>(*target)) names.add(a.name);
            ex(a.value);
            return;
        }
        case StmtKind::InlineAssignment: ex(cast<StmtInlineAssignment>(stmt).value); return;
        case StmtKind::Exchange: ex(cast<StmtExchange>(stmt).left); ex(cast<StmtExchange>(stmt).right); return;
        case StmtKind::IndexAssign:
            ex(cast<StmtIndexAssign>(stmt).target);
            ex(cast<StmtIndexAssign>(stmt).index);
            ex(cast<StmtIndexAssign>(stmt).value);
            return;
        case StmtKind::MemberAssign: ex(cast<StmtMemberAssign>(stmt).target); ex(cast<StmtMemberAssign>(stmt).value); return;
        case StmtKind::DerefAssign: ex(cast<StmtDerefAssign>(stmt).target); ex(cast<StmtDerefAssign>(stmt).value); return;
        case StmtKind::Return:
            if (cast<StmtReturn>(stmt).value) ex(cast<StmtReturn>(stmt).value);
            return;
        case StmtKind::ExprStmt: ex(cast<StmtExprStmt>(stmt).expr); return;
        case StmtKind::Throw: ex(cast<StmtThrow>(stmt).value); return;
        case StmtKind::Panic: ex(cast<StmtPanic>(stmt).message); return;
        case StmtKind::Yield: ex(cast<StmtYield>(stmt).value); return;
        case StmtKind::Assert: ex(cast<StmtAssert>(stmt).condition); ex(cast<StmtAssert>(stmt).message); return;
        case StmtKind::InlineAssert: ex(cast<StmtInlineAssert>(stmt).condition); ex(cast<StmtInlineAssert>(stmt).message); return;
        case StmtKind::Trace:
            if (cast<StmtTrace>(stmt).level) ex(cast<StmtTrace>(stmt).level);
            ex(cast<StmtTrace>(stmt).message);
            return;
        case StmtKind::InlineTrace:
            if (cast<StmtInlineTrace>(stmt).level) ex(cast<StmtInlineTrace>(stmt).level);
            ex(cast<StmtInlineTrace>(stmt).message);
            return;
        case StmtKind::If:
            ex(cast<StmtIf>(stmt).condition);
            st(cast<StmtIf>(stmt).thenBranch);
            if (cast<StmtIf>(stmt).elseBranch) st(*cast<StmtIf>(stmt).elseBranch);
            return;
        case StmtKind::InlineIf:
            ex(cast<StmtInlineIf>(stmt).condition);
            st(cast<StmtInlineIf>(stmt).thenBranch);
            if (cast<StmtInlineIf>(stmt).elseBranch) st(*cast<StmtInlineIf>(stmt).elseBranch);
            return;
        case StmtKind::DeepInlineIf:
            ex(cast<StmtDeepInlineIf>(stmt).condition);
            st(cast<StmtDeepInlineIf>(stmt).thenBranch);
            if (cast<StmtDeepInlineIf>(stmt).elseBranch) st(*cast<StmtDeepInlineIf>(stmt).elseBranch);
            return;
        case StmtKind::While: ex(cast<StmtWhile>(stmt).condition); st(cast<StmtWhile>(stmt).body); return;
        case StmtKind::For: {
            const StmtFor& f = cast<StmtFor>(stmt);
            if (f.declaredType) collectNamesFromTypeRef(f.declaredType, names);
            // A range written in the header walks without becoming a value.
            if (ExprRange* range = as<ExprRange>(f.iterable)) {
                ex(range->from);
                ex(range->to);
            } else {
                ex(f.iterable);
            }
            if (f.step) ex(f.step);
            st(f.body);
            return;
        }
        case StmtKind::InlineFor: ex(cast<StmtInlineFor>(stmt).iterable); st(cast<StmtInlineFor>(stmt).body); return;
        case StmtKind::Loop: st(cast<StmtLoop>(stmt).body); return;
        case StmtKind::When: {
            const StmtWhen& w = cast<StmtWhen>(stmt);
            ex(w.scrutinee);
            for (const WhenBranch& b : w.branches) {
                for (const Expr& p : b.patterns) ex(p);
                st(b.body);
            }
            if (w.elseBranch) st(*w.elseBranch);
            return;
        }
        case StmtKind::Try:
            st(cast<StmtTry>(stmt).body);
            if (cast<StmtTry>(stmt).catchBody) st(*cast<StmtTry>(stmt).catchBody);
            return;
        case StmtKind::Defer: st(cast<StmtDefer>(stmt).body); return;
        case StmtKind::Scope: st(cast<StmtScope>(stmt).body); return;
        case StmtKind::InlineBlock: st(cast<StmtInlineBlock>(stmt).body); return;
        case StmtKind::DeepInlineBlock: st(cast<StmtDeepInlineBlock>(stmt).body); return;
        case StmtKind::Effect:
            if (cast<StmtEffect>(stmt).dependencies) {
                for (const Expr& d : *cast<StmtEffect>(stmt).dependencies) ex(d);
            }
            st(cast<StmtEffect>(stmt).body);
            return;
        case StmtKind::UsingContext:
            for (const Expr& v : cast<StmtUsingContext>(stmt).values) ex(v);
            st(cast<StmtUsingContext>(stmt).body);
            return;
        case StmtKind::NoInline: collectNamesFromStmt(cast<StmtNoInline>(stmt).stmt, names); return;
        default: return;
    }
}

/** Every declaration named by [ref], so a type used only as an argument survives. */
void StdlibInjector::collectNamesFromType(const TypeRef& ref, OrderedSet<std::string>& names) {
    switch (ref.kind()) {
        case TypeRefKind::Named: {
            const TypeRefNamed& named = cast<TypeRefNamed>(ref);
            names.add(named.name);
            if (named.name.find("__") != std::string::npos) names.add(substringAfterLast(named.name, "__"));
            for (const TypeRef& a : named.args) collectNamesFromType(a, names);
            return;
        }
        case TypeRefKind::Array: collectNamesFromType(cast<TypeRefArray>(ref).element, names); return;
        case TypeRefKind::Nullable: collectNamesFromType(cast<TypeRefNullable>(ref).inner, names); return;
        case TypeRefKind::Reference: collectNamesFromType(cast<TypeRefReference>(ref).inner, names); return;
        case TypeRefKind::Pointer: collectNamesFromType(cast<TypeRefPointer>(ref).inner, names); return;
        case TypeRefKind::Function: {
            const TypeRefFunction& f = cast<TypeRefFunction>(ref);
            for (const TypeRef& p : f.params) collectNamesFromType(p, names);
            collectNamesFromType(f.ret, names);
            for (const TypeRef& r : f.receivers) collectNamesFromType(r, names);
            return;
        }
        default: return;
    }
}

void StdlibInjector::collectNamesFromExpr(const Expr& expr, OrderedSet<std::string>& names) {
    auto ex = [&](const Expr& e) { collectNamesFromExpr(e, names); };
    switch (expr.kind()) {
        // A literal names the default width.
        case ExprKind::IntLiteral: names.add(Literals::DEFAULT_INT); return;
        case ExprKind::DoubleLiteral: names.add(Literals::DEFAULT_FLOAT); return;
        case ExprKind::CharLiteral: names.add("Char"); return;
        case ExprKind::StringLiteral: names.add("String"); return;
        case ExprKind::BoolLiteral: names.add("Bool"); return;
        case ExprKind::Identifier: {
            const std::string& name = cast<ExprIdentifier>(expr).name;
            const TopLevel* item = index().items.get(name);
            if (item && !is<TopLevelFunc>(*item)) names.add(name);
            // `Vec3f::zero` arrives as `Vec3f__zero`: the base is pulled in too,
            // and a scope-qualified type keeps its bare tail.
            if (name.find("__") != std::string::npos) {
                names.add(substringBeforeLast(name, "__"));
                names.add(substringAfterLast(name, "__"));
            }
            return;
        }
        // Kotlin's when has two `is Expr.Member` branches; the first one wins,
        // so a plain member access never reaches the member sink.
        case ExprKind::Member: {
            const ExprMember& m = cast<ExprMember>(expr);
            if (const char* dependency = builtinMemberDependency(m.name)) names.add(dependency);
            ex(m.target);
            if (m.nameExpr) ex(m.nameExpr);
            return;
        }
        case ExprKind::Call: {
            const ExprCall& call = cast<ExprCall>(expr);
            names.add(call.callee);
            // A synthesized builtin can depend on a declaration the source never names.
            if (const char* dependency = builtinTypeDependency(call.callee)) names.add(dependency);
            // A `Type::member` static call mangles to `Type__member`: the base type is referenced too.
            if (call.callee.find("__") != std::string::npos) {
                names.add(substringBeforeLast(call.callee, "__"));
                names.add(substringAfterLast(call.callee, "__"));
            }
            // A type argument is a use.
            for (const TypeRef& t : call.typeArgs) collectNamesFromType(t, names);
            for (const Expr& a : call.args) ex(a);
            return;
        }
        case ExprKind::MethodCall: {
            const ExprMethodCall& call = cast<ExprMethodCall>(expr);
            ex(call.target);
            // The method may be a universal infix (`a to b`), a top-level function.
            names.add(call.name);
            for (const Expr& a : call.args) ex(a);
            return;
        }
        case ExprKind::Binary: ex(cast<ExprBinary>(expr).left); ex(cast<ExprBinary>(expr).right); return;
        case ExprKind::Unary: ex(cast<ExprUnary>(expr).operand); return;
        case ExprKind::Grouping: ex(cast<ExprGrouping>(expr).expr); return;
        case ExprKind::SafeMember: {
            const ExprSafeMember& m = cast<ExprSafeMember>(expr);
            if (memberSink) memberSink->add(m.name);
            ex(m.target);
            return;
        }
        case ExprKind::Index: ex(cast<ExprIndex>(expr).target); ex(cast<ExprIndex>(expr).index); return;
        // A range is a `Range` value, whose members come with it.
        case ExprKind::Range:
            names.add(INTRINSIC_RANGE);
            ex(cast<ExprRange>(expr).from);
            ex(cast<ExprRange>(expr).to);
            return;
        // A sequence literal builds an `Array` unless its context names another target.
        case ExprKind::ArrayLiteral:
            names.add("Array");
            for (const Expr& e : cast<ExprArrayLiteral>(expr).elements) ex(e);
            return;
        // An associative literal builds the standard map unless its context names another target.
        case ExprKind::MapLit:
            names.add("LinkedHashMap");
            for (const auto& [key, value] : cast<ExprMapLit>(expr).entries) {
                ex(key);
                ex(value);
            }
            return;
        // `.(args)` - its arguments are references like any call's.
        case ExprKind::InferredMember:
            if (cast<ExprInferredMember>(expr).ctorArgs) {
                for (const Expr& a : *cast<ExprInferredMember>(expr).ctorArgs) ex(a);
            }
            return;
        case ExprKind::TupleLit:
            names.add(INTRINSIC_TUPLE);
            for (const Expr& e : cast<ExprTupleLit>(expr).elements) ex(e);
            return;
        case ExprKind::VariantLit: for (const Expr& e : cast<ExprVariantLit>(expr).elements) ex(e); return;
        case ExprKind::TupleAccess: ex(cast<ExprTupleAccess>(expr).target); return;
        case ExprKind::StringTemplate:
            names.add("String");
            for (const StringTemplatePart& part : cast<ExprStringTemplate>(expr).parts) {
                if (!part.isLiteral) ex(part.expr);
            }
            return;
        case ExprKind::Lambda: {
            const ExprLambda& lambda = cast<ExprLambda>(expr);
            for (const Param& p : lambda.params) collectNamesFromTypeAnnotation(TypeAnnotation{p.type}, names);
            for (const Param& p : lambda.receivers) collectNamesFromTypeAnnotation(TypeAnnotation{p.type}, names);
            for (const Stmt& s : lambda.body) collectNamesFromStmt(s, names);
            return;
        }
        case ExprKind::NamedArg: ex(cast<ExprNamedArg>(expr).value); return;
        case ExprKind::CatchExpr: ex(cast<ExprCatchExpr>(expr).expr); ex(cast<ExprCatchExpr>(expr).fallback); return;
        case ExprKind::TryPropagate: ex(cast<ExprTryPropagate>(expr).expr); return;
        case ExprKind::IfExpr:
            ex(cast<ExprIfExpr>(expr).condition);
            ex(cast<ExprIfExpr>(expr).thenExpr);
            ex(cast<ExprIfExpr>(expr).elseExpr);
            return;
        case ExprKind::NullCoalesce: ex(cast<ExprNullCoalesce>(expr).left); ex(cast<ExprNullCoalesce>(expr).right); return;
        case ExprKind::Cast:
            ex(cast<ExprCast>(expr).expr);
            collectNamesFromTypeRef(cast<ExprCast>(expr).targetType, names);
            return;
        case ExprKind::IsCheck: {
            const ExprIsCheck& check = cast<ExprIsCheck>(expr);
            ex(check.expr);
            names.add(check.typeName);
            for (const TypeRef& t : check.typeArgs) collectNamesFromTypeRef(t, names);
            return;
        }
        case ExprKind::Alloc: ex(cast<ExprAlloc>(expr).value); return;
        case ExprKind::Deref: ex(cast<ExprDeref>(expr).target); return;
        case ExprKind::Isolated: ex(cast<ExprIsolated>(expr).value); return;
        case ExprKind::Await: ex(cast<ExprAwait>(expr).value); return;
        case ExprKind::Inject: names.add(cast<ExprInject>(expr).typeName); return;
        case ExprKind::Spread: ex(cast<ExprSpread>(expr).array); return;
        case ExprKind::MetaInvoke:
            names.add(cast<ExprMetaInvoke>(expr).name);
            for (const Expr& a : cast<ExprMetaInvoke>(expr).args) ex(a);
            return;
        default: return;
    }
}

void StdlibInjector::collectNamesFromTypeAnnotation(const TypeAnnotation& annotation, OrderedSet<std::string>& names) {
    if (annotation.isExplicit()) collectNamesFromTypeRef(annotation.ref, names);
}

void StdlibInjector::collectNamesFromTypeRef(const TypeRef& ref, OrderedSet<std::string>& names) {
    switch (ref.kind()) {
        case TypeRefKind::Named: {
            const TypeRefNamed& named = cast<TypeRefNamed>(ref);
            names.add(TypeFunctionCall::isCall(named) ? TypeFunctionCall::name(named) : named.name);
            for (const TypeRef& a : named.args) collectNamesFromTypeRef(a, names);
            return;
        }
        case TypeRefKind::Array: collectNamesFromTypeRef(cast<TypeRefArray>(ref).element, names); return;
        case TypeRefKind::Map:
            names.add("Map");
            collectNamesFromTypeRef(cast<TypeRefMap>(ref).key, names);
            collectNamesFromTypeRef(cast<TypeRefMap>(ref).value, names);
            return;
        case TypeRefKind::Set:
            names.add("Set");
            collectNamesFromTypeRef(cast<TypeRefSet>(ref).element, names);
            return;
        case TypeRefKind::Function: {
            const TypeRefFunction& f = cast<TypeRefFunction>(ref);
            for (const TypeRef& p : f.params) collectNamesFromTypeRef(p, names);
            for (const TypeRef& r : f.receivers) collectNamesFromTypeRef(r, names);
            collectNamesFromTypeRef(f.ret, names);
            return;
        }
        // `(A, B)` is the `Tuple` the library declares.
        case TypeRefKind::Tuple:
            names.add(INTRINSIC_TUPLE);
            for (const TypeRef& e : cast<TypeRefTuple>(ref).elements) collectNamesFromTypeRef(e, names);
            return;
        case TypeRefKind::Nullable: collectNamesFromTypeRef(cast<TypeRefNullable>(ref).inner, names); return;
        case TypeRefKind::Failable:
            collectNamesFromTypeRef(cast<TypeRefFailable>(ref).ok, names);
            for (const std::string& e : cast<TypeRefFailable>(ref).errSets) names.add(e);
            return;
        case TypeRefKind::Pointer: collectNamesFromTypeRef(cast<TypeRefPointer>(ref).inner, names); return;
        case TypeRefKind::Reference: collectNamesFromTypeRef(cast<TypeRefReference>(ref).inner, names); return;
        default: return;
    }
}

}  // namespace azora
