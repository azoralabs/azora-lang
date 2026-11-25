// Port of AstMapper.kt. Every `copy(...)` keeps Kotlin's argument order, since
// a hook may count or record what it visits.
#include "frontend/ast_rewriter.h"

namespace azora {

std::vector<Expr> AstRewriter::exprs(const std::vector<Expr>& items) {
    std::vector<Expr> out;
    out.reserve(items.size());
    for (const Expr& item : items) out.push_back(expr(item));
    return out;
}

std::vector<TypeRef> AstRewriter::typeRefs(const std::vector<TypeRef>& items) {
    std::vector<TypeRef> out;
    out.reserve(items.size());
    for (const TypeRef& item : items) out.push_back(typeRef(item));
    return out;
}

std::vector<Param> AstRewriter::params(const std::vector<Param>& items) {
    std::vector<Param> out;
    out.reserve(items.size());
    for (const Param& item : items) out.push_back(param(item));
    return out;
}

std::vector<Stmt> AstRewriter::baseStmts(const std::vector<Stmt>& body) {
    std::vector<Stmt> out;
    out.reserve(body.size());
    for (const Stmt& s : body) {
        std::vector<Stmt> expanded = expandStmt(s);
        out.insert(out.end(), expanded.begin(), expanded.end());
    }
    return out;
}

std::vector<Stmt> AstRewriter::baseExpandStmt(const Stmt& s) { return {stmt(s)}; }

TypeRef AstRewriter::baseTypeRef(const TypeRef& ref) {
    switch (ref.kind()) {
        case TypeRefKind::Named: {
            TypeRefNamed c = cast<TypeRefNamed>(ref);
            c.args = typeRefs(c.args);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Array: {
            TypeRefArray c = cast<TypeRefArray>(ref);
            c.element = typeRef(c.element);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Map: {
            TypeRefMap c = cast<TypeRefMap>(ref);
            c.key = typeRef(c.key);
            c.value = typeRef(c.value);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Set: {
            TypeRefSet c = cast<TypeRefSet>(ref);
            c.element = typeRef(c.element);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Function: {
            TypeRefFunction c = cast<TypeRefFunction>(ref);
            c.params = typeRefs(c.params);
            c.ret = typeRef(c.ret);
            c.receivers = typeRefs(c.receivers);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Tuple: {
            TypeRefTuple c = cast<TypeRefTuple>(ref);
            c.elements = typeRefs(c.elements);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Nullable: {
            TypeRefNullable c = cast<TypeRefNullable>(ref);
            c.inner = typeRef(c.inner);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Failable: {
            TypeRefFailable c = cast<TypeRefFailable>(ref);
            c.ok = typeRef(c.ok);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Pointer: {
            TypeRefPointer c = cast<TypeRefPointer>(ref);
            c.inner = typeRef(c.inner);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Reference: {
            TypeRefReference c = cast<TypeRefReference>(ref);
            c.inner = typeRef(c.inner);
            return make(arena, std::move(c));
        }
        case TypeRefKind::None:
        case TypeRefKind::Const:
            return ref;
    }
    return ref;
}

TypeAnnotation AstRewriter::baseTypeAnnotation(const TypeAnnotation& annotation) {
    if (annotation.isExplicit()) return TypeAnnotation{typeRef(annotation.ref)};
    return annotation;
}

Param AstRewriter::baseParam(const Param& p) {
    Param c = p;
    c.type = typeRef(c.type);
    if (c.defaultValue) c.defaultValue = expr(c.defaultValue);
    return c;
}

Stmt AstRewriter::baseStmt(const Stmt& s) {
    switch (s.kind()) {
        case StmtKind::None:
        case StmtKind::Import:
        case StmtKind::Continue:
            return s;
        case StmtKind::VarDecl: {
            StmtVarDecl c = cast<StmtVarDecl>(s);
            c.type = typeAnnotation(c.type);
            c.initializer = expr(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::FinDecl: {
            StmtFinDecl c = cast<StmtFinDecl>(s);
            c.type = typeAnnotation(c.type);
            c.initializer = expr(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::LetDecl: {
            StmtLetDecl c = cast<StmtLetDecl>(s);
            c.type = typeAnnotation(c.type);
            c.initializer = expr(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineVar: {
            StmtInlineVar c = cast<StmtInlineVar>(s);
            c.initializer = expr(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineFin: {
            StmtInlineFin c = cast<StmtInlineFin>(s);
            c.initializer = expr(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineLet: {
            StmtInlineLet c = cast<StmtInlineLet>(s);
            c.initializer = expr(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::RemDecl: {
            StmtRemDecl c = cast<StmtRemDecl>(s);
            c.initializer = expr(c.initializer);
            return make(arena, std::move(c));
        }
        case StmtKind::Assignment: {
            StmtAssignment c = cast<StmtAssignment>(s);
            c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineAssignment: {
            StmtInlineAssignment c = cast<StmtInlineAssignment>(s);
            c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::Return: {
            StmtReturn c = cast<StmtReturn>(s);
            if (c.value) c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::ExprStmt: {
            StmtExprStmt c = cast<StmtExprStmt>(s);
            c.expr = expr(c.expr);
            return make(arena, std::move(c));
        }
        case StmtKind::Throw: {
            StmtThrow c = cast<StmtThrow>(s);
            c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::Panic: {
            StmtPanic c = cast<StmtPanic>(s);
            c.message = expr(c.message);
            return make(arena, std::move(c));
        }
        case StmtKind::Yield: {
            StmtYield c = cast<StmtYield>(s);
            c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::Assert: {
            StmtAssert c = cast<StmtAssert>(s);
            c.condition = expr(c.condition);
            c.message = expr(c.message);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineAssert: {
            StmtInlineAssert c = cast<StmtInlineAssert>(s);
            c.condition = expr(c.condition);
            c.message = expr(c.message);
            return make(arena, std::move(c));
        }
        case StmtKind::Trace: {
            StmtTrace c = cast<StmtTrace>(s);
            c.message = expr(c.message);
            if (c.level) c.level = expr(c.level);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineTrace: {
            StmtInlineTrace c = cast<StmtInlineTrace>(s);
            c.message = expr(c.message);
            if (c.level) c.level = expr(c.level);
            return make(arena, std::move(c));
        }
        case StmtKind::Exchange: {
            StmtExchange c = cast<StmtExchange>(s);
            c.left = expr(c.left);
            c.right = expr(c.right);
            return make(arena, std::move(c));
        }
        case StmtKind::IndexAssign: {
            StmtIndexAssign c = cast<StmtIndexAssign>(s);
            c.target = expr(c.target);
            c.index = expr(c.index);
            c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::MemberAssign: {
            StmtMemberAssign c = cast<StmtMemberAssign>(s);
            c.target = expr(c.target);
            c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::DerefAssign: {
            StmtDerefAssign c = cast<StmtDerefAssign>(s);
            c.target = expr(c.target);
            c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case StmtKind::If: {
            StmtIf c = cast<StmtIf>(s);
            c.condition = expr(c.condition);
            c.thenBranch = stmts(c.thenBranch);
            if (c.elseBranch) c.elseBranch = stmts(*c.elseBranch);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineIf: {
            StmtInlineIf c = cast<StmtInlineIf>(s);
            c.condition = expr(c.condition);
            c.thenBranch = stmts(c.thenBranch);
            if (c.elseBranch) c.elseBranch = stmts(*c.elseBranch);
            return make(arena, std::move(c));
        }
        case StmtKind::DeepInlineIf: {
            StmtDeepInlineIf c = cast<StmtDeepInlineIf>(s);
            c.condition = expr(c.condition);
            c.thenBranch = stmts(c.thenBranch);
            if (c.elseBranch) c.elseBranch = stmts(*c.elseBranch);
            return make(arena, std::move(c));
        }
        case StmtKind::While: {
            StmtWhile c = cast<StmtWhile>(s);
            c.condition = expr(c.condition);
            c.body = stmts(c.body);
            return make(arena, std::move(c));
        }
        case StmtKind::For: {
            StmtFor c = cast<StmtFor>(s);
            c.iterable = expr(c.iterable);
            if (c.step) c.step = expr(c.step);
            c.body = stmts(c.body);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineFor: {
            StmtInlineFor c = cast<StmtInlineFor>(s);
            c.iterable = expr(c.iterable);
            c.body = stmts(c.body);
            return make(arena, std::move(c));
        }
        case StmtKind::Loop: {
            StmtLoop c = cast<StmtLoop>(s);
            if (c.iterable) c.iterable = expr(c.iterable);
            c.body = stmts(c.body);
            return make(arena, std::move(c));
        }
        case StmtKind::Scope: {
            StmtScope c = cast<StmtScope>(s);
            c.body = stmts(c.body);
            return make(arena, std::move(c));
        }
        case StmtKind::InlineBlock: {
            StmtInlineBlock c = cast<StmtInlineBlock>(s);
            c.body = stmts(c.body);
            return make(arena, std::move(c));
        }
        case StmtKind::DeepInlineBlock: {
            StmtDeepInlineBlock c = cast<StmtDeepInlineBlock>(s);
            c.body = stmts(c.body);
            return make(arena, std::move(c));
        }
        case StmtKind::Effect: {
            StmtEffect c = cast<StmtEffect>(s);
            c.body = stmts(c.body);
            if (c.dependencies) c.dependencies = exprs(*c.dependencies);
            return make(arena, std::move(c));
        }
        case StmtKind::UsingContext: {
            StmtUsingContext c = cast<StmtUsingContext>(s);
            c.values = exprs(c.values);
            c.body = stmts(c.body);
            return make(arena, std::move(c));
        }
        case StmtKind::Defer: {
            StmtDefer c = cast<StmtDefer>(s);
            c.body = stmts(c.body);
            return make(arena, std::move(c));
        }
        case StmtKind::Try: {
            StmtTry c = cast<StmtTry>(s);
            c.body = stmts(c.body);
            if (c.catchBody) c.catchBody = stmts(*c.catchBody);
            return make(arena, std::move(c));
        }
        case StmtKind::When: {
            StmtWhen c = cast<StmtWhen>(s);
            c.scrutinee = expr(c.scrutinee);
            for (WhenBranch& branch : c.branches) {
                branch.patterns = exprs(branch.patterns);
                branch.body = stmts(branch.body);
            }
            if (c.elseBranch) c.elseBranch = stmts(*c.elseBranch);
            return make(arena, std::move(c));
        }
        case StmtKind::NoInline: {
            StmtNoInline c = cast<StmtNoInline>(s);
            c.stmt = stmt(c.stmt);
            return make(arena, std::move(c));
        }
        case StmtKind::Break: {
            StmtBreak c = cast<StmtBreak>(s);
            if (c.value) c.value = expr(c.value);
            return make(arena, std::move(c));
        }
    }
    return s;
}

std::vector<Expr> AstRewriter::baseArgs(const std::vector<Expr>& a) { return exprs(a); }

Expr AstRewriter::baseExpr(const Expr& e) {
    switch (e.kind()) {
        // The arguments of `.(…)`, `.Case(…)` and `::name(…)` are ordinary
        // expressions, renamed and rewritten as any call's are.
        case ExprKind::InferredMember: {
            ExprInferredMember c = cast<ExprInferredMember>(e);
            if (c.ctorArgs) c.ctorArgs = exprs(*c.ctorArgs);
            if (c.scopeCall) {
                ExprCall call = *c.scopeCall;
                call.args = exprs(call.args);
                c.scopeCall = make(arena, std::move(call));
            }
            return make(arena, std::move(c));
        }
        case ExprKind::MapEntryArg: {
            ExprMapEntryArg c = cast<ExprMapEntryArg>(e);
            c.key = expr(c.key);
            c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case ExprKind::None:
        case ExprKind::Identifier:
        case ExprKind::IntLiteral:
        case ExprKind::DoubleLiteral:
        case ExprKind::CharLiteral:
        case ExprKind::StringLiteral:
        case ExprKind::BoolLiteral:
        case ExprKind::NullLiteral:
        case ExprKind::UpperScopeAccess:
        case ExprKind::Inject:
            return e;
        case ExprKind::Unary: {
            ExprUnary c = cast<ExprUnary>(e);
            c.operand = expr(c.operand);
            return make(arena, std::move(c));
        }
        case ExprKind::IncDec: {
            ExprIncDec c = cast<ExprIncDec>(e);
            c.target = expr(c.target);
            return make(arena, std::move(c));
        }
        case ExprKind::Grouping: {
            ExprGrouping c = cast<ExprGrouping>(e);
            c.expr = expr(c.expr);
            return make(arena, std::move(c));
        }
        case ExprKind::Member: {
            ExprMember c = cast<ExprMember>(e);
            c.target = expr(c.target);
            return make(arena, std::move(c));
        }
        case ExprKind::SafeMember: {
            ExprSafeMember c = cast<ExprSafeMember>(e);
            c.target = expr(c.target);
            return make(arena, std::move(c));
        }
        case ExprKind::TupleAccess: {
            ExprTupleAccess c = cast<ExprTupleAccess>(e);
            c.target = expr(c.target);
            return make(arena, std::move(c));
        }
        case ExprKind::Index: {
            ExprIndex c = cast<ExprIndex>(e);
            c.target = expr(c.target);
            c.index = expr(c.index);
            return make(arena, std::move(c));
        }
        case ExprKind::Range: {
            ExprRange c = cast<ExprRange>(e);
            c.from = expr(c.from);
            c.to = expr(c.to);
            return make(arena, std::move(c));
        }
        case ExprKind::Binary: {
            ExprBinary c = cast<ExprBinary>(e);
            c.left = expr(c.left);
            c.right = expr(c.right);
            return make(arena, std::move(c));
        }
        case ExprKind::NullCoalesce: {
            ExprNullCoalesce c = cast<ExprNullCoalesce>(e);
            c.left = expr(c.left);
            c.right = expr(c.right);
            return make(arena, std::move(c));
        }
        case ExprKind::CatchExpr: {
            ExprCatchExpr c = cast<ExprCatchExpr>(e);
            c.expr = expr(c.expr);
            c.fallback = expr(c.fallback);
            return make(arena, std::move(c));
        }
        case ExprKind::Seal: {
            ExprSeal c = cast<ExprSeal>(e);
            c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case ExprKind::IfExpr: {
            ExprIfExpr c = cast<ExprIfExpr>(e);
            c.condition = expr(c.condition);
            c.thenExpr = expr(c.thenExpr);
            c.elseExpr = expr(c.elseExpr);
            return make(arena, std::move(c));
        }
        case ExprKind::Cast: {
            ExprCast c = cast<ExprCast>(e);
            c.expr = expr(c.expr);
            c.targetType = typeRef(c.targetType);
            return make(arena, std::move(c));
        }
        case ExprKind::InlineForArgs: {
            ExprInlineForArgs c = cast<ExprInlineForArgs>(e);
            c.iterable = expr(c.iterable);
            c.body = expr(c.body);
            return make(arena, std::move(c));
        }
        case ExprKind::IsCheck: {
            ExprIsCheck c = cast<ExprIsCheck>(e);
            c.expr = expr(c.expr);
            c.typeArgs = typeRefs(c.typeArgs);
            return make(arena, std::move(c));
        }
        case ExprKind::InCheck: {
            ExprInCheck c = cast<ExprInCheck>(e);
            c.value = expr(c.value);
            c.collection = expr(c.collection);
            return make(arena, std::move(c));
        }
        case ExprKind::Alloc: {
            ExprAlloc c = cast<ExprAlloc>(e);
            c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case ExprKind::Deref: {
            ExprDeref c = cast<ExprDeref>(e);
            c.target = expr(c.target);
            return make(arena, std::move(c));
        }
        case ExprKind::Isolated: {
            ExprIsolated c = cast<ExprIsolated>(e);
            c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case ExprKind::Await: {
            ExprAwait c = cast<ExprAwait>(e);
            c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case ExprKind::TryPropagate: {
            ExprTryPropagate c = cast<ExprTryPropagate>(e);
            c.expr = expr(c.expr);
            return make(arena, std::move(c));
        }
        case ExprKind::Spread: {
            ExprSpread c = cast<ExprSpread>(e);
            c.array = expr(c.array);
            return make(arena, std::move(c));
        }
        case ExprKind::NamedArg: {
            ExprNamedArg c = cast<ExprNamedArg>(e);
            c.value = expr(c.value);
            return make(arena, std::move(c));
        }
        case ExprKind::Call: {
            ExprCall c = cast<ExprCall>(e);
            c.args = args(c.args);
            if (c.receiver) c.receiver = expr(c.receiver);
            c.typeArgs = typeRefs(c.typeArgs);
            return make(arena, std::move(c));
        }
        case ExprKind::MethodCall: {
            ExprMethodCall c = cast<ExprMethodCall>(e);
            c.target = expr(c.target);
            c.args = args(c.args);
            return make(arena, std::move(c));
        }
        case ExprKind::ArrayLiteral: {
            ExprArrayLiteral c = cast<ExprArrayLiteral>(e);
            c.elements = exprs(c.elements);
            return make(arena, std::move(c));
        }
        case ExprKind::SetLiteral: {
            ExprSetLiteral c = cast<ExprSetLiteral>(e);
            c.elements = exprs(c.elements);
            return make(arena, std::move(c));
        }
        case ExprKind::TupleLit: {
            ExprTupleLit c = cast<ExprTupleLit>(e);
            c.elements = exprs(c.elements);
            return make(arena, std::move(c));
        }
        case ExprKind::VariantLit: {
            ExprVariantLit c = cast<ExprVariantLit>(e);
            c.elements = exprs(c.elements);
            return make(arena, std::move(c));
        }
        case ExprKind::MapLit: {
            ExprMapLit c = cast<ExprMapLit>(e);
            for (std::pair<Expr, Expr>& entry : c.entries) {
                entry.first = expr(entry.first);
                entry.second = expr(entry.second);
            }
            return make(arena, std::move(c));
        }
        case ExprKind::StringTemplate: {
            ExprStringTemplate c = cast<ExprStringTemplate>(e);
            for (StringTemplatePart& part : c.parts) {
                if (!part.isLiteral) part = exprPart(expr(part.expr));
            }
            return make(arena, std::move(c));
        }
        case ExprKind::Lambda: {
            ExprLambda c = cast<ExprLambda>(e);
            c.params = params(c.params);
            c.receivers = params(c.receivers);
            c.body = stmts(c.body);
            return make(arena, std::move(c));
        }
        case ExprKind::MetaInvoke: {
            ExprMetaInvoke c = cast<ExprMetaInvoke>(e);
            c.args = exprs(c.args);
            return make(arena, std::move(c));
        }
        case ExprKind::Slice: {
            ExprSlice c = cast<ExprSlice>(e);
            c.target = expr(c.target);
            if (c.start) c.start = expr(c.start);
            if (c.stop) c.stop = expr(c.stop);
            if (c.step) c.step = expr(c.step);
            return make(arena, std::move(c));
        }
    }
    return e;
}

}  // namespace azora
