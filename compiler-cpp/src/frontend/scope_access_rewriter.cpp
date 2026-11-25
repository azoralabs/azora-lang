// Port of ScopeAccessRewriter.kt: resolves bare sibling access inside a
// scope (`floor()` → `std__math__floor`) and dotted access from outside
// (`math.PI` → `math__PI`), taking a name only when the program declares it.
#include "frontend/scope_access_rewriter.h"

#include "base/text.h"

#include <unordered_set>

namespace azora {

namespace {

using NameSet = std::unordered_set<std::string>;

struct Rewriter {
    Arena& arena;
    const NameSet& mangled;

    Stmt stmt(const Stmt& s, const std::optional<std::string>& prefix, const NameSet& shadowed);
    Expr expr(const Expr& e, const std::optional<std::string>& prefix, const NameSet& shadowed);
    Expr pattern(const Expr& p, const std::optional<std::string>& prefix, const NameSet& shadowed);
    std::vector<Stmt> stmts(const std::vector<Stmt>& items, const std::optional<std::string>& prefix, const NameSet& shadowed);
    std::vector<Expr> exprs(const std::vector<Expr>& items, const std::optional<std::string>& prefix, const NameSet& shadowed);
    std::optional<std::string> staticPath(const Expr& target, const std::string& member, const NameSet& shadowed);
    std::optional<std::string> maybe(const std::string& name, const std::optional<std::string>& prefix,
                                     const NameSet& shadowed);
};

std::optional<std::string> nameOf(const TopLevel& item) {
    switch (item.kind()) {
        case TopLevelKind::Func: return cast<TopLevelFunc>(item).decl.name;
        case TopLevelKind::FinDecl: return cast<TopLevelFinDecl>(item).name;
        case TopLevelKind::LetDecl: return cast<TopLevelLetDecl>(item).name;
        case TopLevelKind::VarDecl: return cast<TopLevelVarDecl>(item).name;
        // `impl Byte { inline fin limit = 7 }` hoists out as `Byte__limit`.
        case TopLevelKind::InlineFin: return cast<TopLevelInlineFin>(item).name;
        default: return std::nullopt;
    }
}

void collectExprNames(const Expr& e, NameSet& names);

void collectStmtNames(const Stmt& s, NameSet& names) {
    auto each = [&](const std::vector<Stmt>& body) {
        for (const Stmt& b : body) collectStmtNames(b, names);
    };
    switch (s.kind()) {
        case StmtKind::VarDecl: {
            const StmtVarDecl& d = cast<StmtVarDecl>(s);
            names.insert(d.name);
            collectExprNames(d.initializer, names);
            break;
        }
        case StmtKind::LetDecl: {
            const StmtLetDecl& d = cast<StmtLetDecl>(s);
            names.insert(d.name);
            collectExprNames(d.initializer, names);
            break;
        }
        case StmtKind::FinDecl: {
            const StmtFinDecl& d = cast<StmtFinDecl>(s);
            names.insert(d.name);
            collectExprNames(d.initializer, names);
            break;
        }
        case StmtKind::Assignment: collectExprNames(cast<StmtAssignment>(s).value, names); break;
        case StmtKind::Exchange:
            collectExprNames(cast<StmtExchange>(s).left, names);
            collectExprNames(cast<StmtExchange>(s).right, names);
            break;
        case StmtKind::IndexAssign: {
            const StmtIndexAssign& a = cast<StmtIndexAssign>(s);
            collectExprNames(a.target, names);
            collectExprNames(a.index, names);
            collectExprNames(a.value, names);
            break;
        }
        case StmtKind::MemberAssign:
            collectExprNames(cast<StmtMemberAssign>(s).target, names);
            collectExprNames(cast<StmtMemberAssign>(s).value, names);
            break;
        case StmtKind::DerefAssign:
            collectExprNames(cast<StmtDerefAssign>(s).target, names);
            collectExprNames(cast<StmtDerefAssign>(s).value, names);
            break;
        case StmtKind::Return:
            if (cast<StmtReturn>(s).value) collectExprNames(cast<StmtReturn>(s).value, names);
            break;
        case StmtKind::ExprStmt: collectExprNames(cast<StmtExprStmt>(s).expr, names); break;
        case StmtKind::Throw: collectExprNames(cast<StmtThrow>(s).value, names); break;
        case StmtKind::Yield: collectExprNames(cast<StmtYield>(s).value, names); break;
        case StmtKind::Assert:
            collectExprNames(cast<StmtAssert>(s).condition, names);
            collectExprNames(cast<StmtAssert>(s).message, names);
            break;
        case StmtKind::Trace: {
            const StmtTrace& t = cast<StmtTrace>(s);
            if (t.level) collectExprNames(t.level, names);
            collectExprNames(t.message, names);
            break;
        }
        case StmtKind::InlineTrace: {
            const StmtInlineTrace& t = cast<StmtInlineTrace>(s);
            if (t.level) collectExprNames(t.level, names);
            collectExprNames(t.message, names);
            break;
        }
        case StmtKind::If: {
            const StmtIf& i = cast<StmtIf>(s);
            collectExprNames(i.condition, names);
            each(i.thenBranch);
            if (i.elseBranch) each(*i.elseBranch);
            break;
        }
        case StmtKind::While:
            collectExprNames(cast<StmtWhile>(s).condition, names);
            each(cast<StmtWhile>(s).body);
            break;
        case StmtKind::For: {
            const StmtFor& f = cast<StmtFor>(s);
            names.insert(f.name);
            if (f.indexName) names.insert(*f.indexName);
            collectExprNames(f.iterable, names);
            if (f.step) collectExprNames(f.step, names);
            each(f.body);
            break;
        }
        case StmtKind::Loop: each(cast<StmtLoop>(s).body); break;
        case StmtKind::When: {
            const StmtWhen& w = cast<StmtWhen>(s);
            collectExprNames(w.scrutinee, names);
            for (const WhenBranch& branch : w.branches) {
                for (const Expr& p : branch.patterns) collectExprNames(p, names);
                each(branch.body);
            }
            if (w.elseBranch) each(*w.elseBranch);
            break;
        }
        case StmtKind::Try: {
            const StmtTry& t = cast<StmtTry>(s);
            each(t.body);
            if (t.catchBody) each(*t.catchBody);
            break;
        }
        case StmtKind::Defer: each(cast<StmtDefer>(s).body); break;
        case StmtKind::Scope: each(cast<StmtScope>(s).body); break;
        default: break;
    }
}

void collectExprNames(const Expr& e, NameSet& names) {
    auto each = [&](const std::vector<Expr>& items) {
        for (const Expr& item : items) collectExprNames(item, names);
    };
    switch (e.kind()) {
        case ExprKind::Lambda: {
            const ExprLambda& l = cast<ExprLambda>(e);
            for (const Param& p : l.params) names.insert(p.name);
            for (const Param& p : l.receivers) names.insert(p.name);
            for (const Stmt& s : l.body) collectStmtNames(s, names);
            break;
        }
        case ExprKind::MethodCall:
            collectExprNames(cast<ExprMethodCall>(e).target, names);
            each(cast<ExprMethodCall>(e).args);
            break;
        case ExprKind::Member: collectExprNames(cast<ExprMember>(e).target, names); break;
        case ExprKind::Call: each(cast<ExprCall>(e).args); break;
        case ExprKind::Binary:
            collectExprNames(cast<ExprBinary>(e).left, names);
            collectExprNames(cast<ExprBinary>(e).right, names);
            break;
        case ExprKind::Unary: collectExprNames(cast<ExprUnary>(e).operand, names); break;
        case ExprKind::IncDec: collectExprNames(cast<ExprIncDec>(e).target, names); break;
        case ExprKind::Grouping: collectExprNames(cast<ExprGrouping>(e).expr, names); break;
        case ExprKind::Index:
            collectExprNames(cast<ExprIndex>(e).target, names);
            collectExprNames(cast<ExprIndex>(e).index, names);
            break;
        case ExprKind::Range:
            collectExprNames(cast<ExprRange>(e).from, names);
            collectExprNames(cast<ExprRange>(e).to, names);
            break;
        case ExprKind::ArrayLiteral: each(cast<ExprArrayLiteral>(e).elements); break;
        case ExprKind::MapLit:
            for (const auto& [k, v] : cast<ExprMapLit>(e).entries) {
                collectExprNames(k, names);
                collectExprNames(v, names);
            }
            break;
        case ExprKind::TupleLit: each(cast<ExprTupleLit>(e).elements); break;
        case ExprKind::VariantLit: each(cast<ExprVariantLit>(e).elements); break;
        case ExprKind::TupleAccess: collectExprNames(cast<ExprTupleAccess>(e).target, names); break;
        case ExprKind::StringTemplate:
            for (const StringTemplatePart& p : cast<ExprStringTemplate>(e).parts) {
                if (!p.isLiteral) collectExprNames(p.expr, names);
            }
            break;
        case ExprKind::NamedArg: collectExprNames(cast<ExprNamedArg>(e).value, names); break;
        case ExprKind::CatchExpr:
            collectExprNames(cast<ExprCatchExpr>(e).expr, names);
            collectExprNames(cast<ExprCatchExpr>(e).fallback, names);
            break;
        case ExprKind::TryPropagate: collectExprNames(cast<ExprTryPropagate>(e).expr, names); break;
        case ExprKind::IfExpr:
            collectExprNames(cast<ExprIfExpr>(e).condition, names);
            collectExprNames(cast<ExprIfExpr>(e).thenExpr, names);
            collectExprNames(cast<ExprIfExpr>(e).elseExpr, names);
            break;
        case ExprKind::NullCoalesce:
            collectExprNames(cast<ExprNullCoalesce>(e).left, names);
            collectExprNames(cast<ExprNullCoalesce>(e).right, names);
            break;
        case ExprKind::Cast: collectExprNames(cast<ExprCast>(e).expr, names); break;
        case ExprKind::IsCheck: collectExprNames(cast<ExprIsCheck>(e).expr, names); break;
        case ExprKind::SafeMember: collectExprNames(cast<ExprSafeMember>(e).target, names); break;
        case ExprKind::Alloc: collectExprNames(cast<ExprAlloc>(e).value, names); break;
        case ExprKind::Deref: collectExprNames(cast<ExprDeref>(e).target, names); break;
        case ExprKind::Isolated: collectExprNames(cast<ExprIsolated>(e).value, names); break;
        case ExprKind::Await: collectExprNames(cast<ExprAwait>(e).value, names); break;
        case ExprKind::Spread: collectExprNames(cast<ExprSpread>(e).array, names); break;
        default: break;
    }
}

NameSet collectShadowed(const FuncDecl& func) {
    NameSet names;
    for (const Param& p : func.params) names.insert(p.name);
    for (const Stmt& s : func.body) collectStmtNames(s, names);
    return names;
}

/** Names declared inside [item] (parameters + local bindings) that shadow a scope sibling. */
NameSet collectShadowed(const TopLevel& item) {
    NameSet names;
    if (TopLevelFunc* f = as<TopLevelFunc>(item)) {
        NameSet inner = collectShadowed(f->decl);
        names.insert(inner.begin(), inner.end());
    } else if (TopLevelTest* t = as<TopLevelTest>(item)) {
        for (const Stmt& s : t->body) collectStmtNames(s, names);
    }
    return names;
}

/** `std.math` → `std__math`; anything that is not a plain dotted name → none. */
std::optional<std::string> dottedName(const Expr& e) {
    if (ExprIdentifier* id = as<ExprIdentifier>(e)) return id->name;
    if (ExprMember* member = as<ExprMember>(e)) {
        if (member->nameExpr) return std::nullopt;
        std::optional<std::string> head = dottedName(member->target);
        if (!head) return std::nullopt;
        return *head + "__" + member->name;
    }
    return std::nullopt;
}

/** The declaration `<target>.<member>` names, or none if it names none. */
std::optional<std::string> Rewriter::staticPath(const Expr& target, const std::string& member, const NameSet& shadowed) {
    std::optional<std::string> head = dottedName(target);
    if (!head) return std::nullopt;
    if (shadowed.count(substringBefore(*head, "__"))) return std::nullopt;
    std::string qualified = *head + "__" + member;
    if (mangled.count(qualified)) return qualified;
    return std::nullopt;
}

std::optional<std::string> Rewriter::maybe(const std::string& name, const std::optional<std::string>& prefix,
                                           const NameSet& shadowed) {
    if (!prefix) return std::nullopt;
    // Only a name that already starts with this member's complete scope prefix
    // is fully qualified here.
    if (startsWith(name, *prefix + "__")) return std::nullopt;
    if (!contains(name, "__") && shadowed.count(name)) return std::nullopt;  // parameter/local shadows the sibling
    std::string qualified = *prefix + "__" + name;
    if (mangled.count(qualified)) return qualified;
    return std::nullopt;
}

std::vector<Stmt> Rewriter::stmts(const std::vector<Stmt>& items, const std::optional<std::string>& prefix,
                                  const NameSet& shadowed) {
    std::vector<Stmt> out;
    for (const Stmt& s : items) out.push_back(stmt(s, prefix, shadowed));
    return out;
}

std::vector<Expr> Rewriter::exprs(const std::vector<Expr>& items, const std::optional<std::string>& prefix,
                                  const NameSet& shadowed) {
    std::vector<Expr> out;
    for (const Expr& e : items) out.push_back(expr(e, prefix, shadowed));
    return out;
}

Stmt Rewriter::stmt(const Stmt& s, const std::optional<std::string>& prefix, const NameSet& shadowed) {
    auto x = [&](const Expr& e) { return expr(e, prefix, shadowed); };
    auto xOpt = [&](const Expr& e) { return e ? x(e) : e; };
    auto body = [&](const std::vector<Stmt>& items) { return stmts(items, prefix, shadowed); };
    switch (s.kind()) {
        case StmtKind::VarDecl: {
            StmtVarDecl c = cast<StmtVarDecl>(s);
            c.initializer = x(c.initializer);
            return make(arena, c);
        }
        case StmtKind::LetDecl: {
            StmtLetDecl c = cast<StmtLetDecl>(s);
            c.initializer = x(c.initializer);
            return make(arena, c);
        }
        case StmtKind::FinDecl: {
            StmtFinDecl c = cast<StmtFinDecl>(s);
            c.initializer = x(c.initializer);
            return make(arena, c);
        }
        case StmtKind::Assignment: {
            StmtAssignment c = cast<StmtAssignment>(s);
            c.value = x(c.value);
            return make(arena, c);
        }
        case StmtKind::Exchange: {
            StmtExchange c = cast<StmtExchange>(s);
            c.left = x(c.left);
            c.right = x(c.right);
            return make(arena, c);
        }
        case StmtKind::IndexAssign: {
            StmtIndexAssign c = cast<StmtIndexAssign>(s);
            c.target = x(c.target);
            c.index = x(c.index);
            c.value = x(c.value);
            return make(arena, c);
        }
        case StmtKind::MemberAssign: {
            StmtMemberAssign c = cast<StmtMemberAssign>(s);
            c.target = x(c.target);
            c.value = x(c.value);
            return make(arena, c);
        }
        case StmtKind::DerefAssign: {
            StmtDerefAssign c = cast<StmtDerefAssign>(s);
            c.target = x(c.target);
            c.value = x(c.value);
            return make(arena, c);
        }
        case StmtKind::Return: {
            StmtReturn c = cast<StmtReturn>(s);
            c.value = xOpt(c.value);
            return make(arena, c);
        }
        case StmtKind::ExprStmt: {
            StmtExprStmt c = cast<StmtExprStmt>(s);
            c.expr = x(c.expr);
            return make(arena, c);
        }
        case StmtKind::Throw: {
            StmtThrow c = cast<StmtThrow>(s);
            c.value = x(c.value);
            return make(arena, c);
        }
        case StmtKind::Yield: {
            StmtYield c = cast<StmtYield>(s);
            c.value = x(c.value);
            return make(arena, c);
        }
        case StmtKind::Assert: {
            StmtAssert c = cast<StmtAssert>(s);
            c.condition = x(c.condition);
            c.message = x(c.message);
            return make(arena, c);
        }
        case StmtKind::Trace: {
            StmtTrace c = cast<StmtTrace>(s);
            c.message = x(c.message);
            c.level = xOpt(c.level);
            return make(arena, c);
        }
        case StmtKind::InlineTrace: {
            StmtInlineTrace c = cast<StmtInlineTrace>(s);
            c.message = x(c.message);
            c.level = xOpt(c.level);
            return make(arena, c);
        }
        case StmtKind::If: {
            StmtIf c = cast<StmtIf>(s);
            c.condition = x(c.condition);
            c.thenBranch = body(c.thenBranch);
            if (c.elseBranch) c.elseBranch = body(*c.elseBranch);
            return make(arena, c);
        }
        case StmtKind::While: {
            StmtWhile c = cast<StmtWhile>(s);
            c.condition = x(c.condition);
            c.body = body(c.body);
            return make(arena, c);
        }
        case StmtKind::For: {
            StmtFor c = cast<StmtFor>(s);
            c.iterable = x(c.iterable);
            c.step = xOpt(c.step);
            c.body = body(c.body);
            return make(arena, c);
        }
        case StmtKind::Loop: {
            StmtLoop c = cast<StmtLoop>(s);
            c.body = body(c.body);
            return make(arena, c);
        }
        case StmtKind::When: {
            StmtWhen c = cast<StmtWhen>(s);
            c.scrutinee = x(c.scrutinee);
            for (WhenBranch& branch : c.branches) {
                std::vector<Expr> patterns;
                for (const Expr& p : branch.patterns) patterns.push_back(pattern(p, prefix, shadowed));
                branch.patterns = patterns;
                branch.body = body(branch.body);
            }
            if (c.elseBranch) c.elseBranch = body(*c.elseBranch);
            return make(arena, c);
        }
        case StmtKind::Try: {
            StmtTry c = cast<StmtTry>(s);
            c.body = body(c.body);
            if (c.catchBody) c.catchBody = body(*c.catchBody);
            return make(arena, c);
        }
        case StmtKind::Defer: {
            StmtDefer c = cast<StmtDefer>(s);
            c.body = body(c.body);
            return make(arena, c);
        }
        case StmtKind::Scope: {
            StmtScope c = cast<StmtScope>(s);
            c.body = body(c.body);
            return make(arena, c);
        }
        default:
            return s;
    }
}

/** A `when` arm's pattern: only the case being matched is qualified. */
Expr Rewriter::pattern(const Expr& p, const std::optional<std::string>& prefix, const NameSet& shadowed) {
    if (is<ExprInferredMember>(p)) return p;
    return expr(p, prefix, shadowed);
}

Expr Rewriter::expr(const Expr& e, const std::optional<std::string>& prefix, const NameSet& shadowed) {
    auto x = [&](const Expr& inner) { return expr(inner, prefix, shadowed); };
    auto xs = [&](const std::vector<Expr>& items) { return exprs(items, prefix, shadowed); };
    switch (e.kind()) {
        case ExprKind::Identifier: {
            const ExprIdentifier& id = cast<ExprIdentifier>(e);
            std::optional<std::string> qualified = maybe(id.name, prefix, shadowed);
            if (!qualified) return e;
            ExprIdentifier c = id;
            c.name = *qualified;
            return make(arena, c);
        }
        case ExprKind::MethodCall: {
            const ExprMethodCall& m = cast<ExprMethodCall>(e);
            Expr target = x(m.target);
            std::vector<Expr> args = xs(m.args);
            if (std::optional<std::string> path = staticPath(target, m.name, shadowed)) {
                ExprCall call;
                call.callee = *path;
                call.args = args;
                call.line = m.line;
                call.column = m.column;
                call.length = m.length;
                return make(arena, call);
            }
            ExprMethodCall c = m;
            c.target = target;
            c.args = args;
            return make(arena, c);
        }
        case ExprKind::Member: {
            const ExprMember& m = cast<ExprMember>(e);
            Expr target = x(m.target);
            std::optional<std::string> path;
            if (!m.nameExpr) path = staticPath(target, m.name, shadowed);
            if (path) return make(arena, ExprIdentifier{*path, m.line, m.column, m.length});
            ExprMember c = m;
            c.target = target;
            return make(arena, c);
        }
        // `.(args)` - the arguments are ordinary expressions.
        case ExprKind::InferredMember: {
            const ExprInferredMember& m = cast<ExprInferredMember>(e);
            if (!m.ctorArgs) return e;
            ExprInferredMember c = m;
            c.ctorArgs = xs(*m.ctorArgs);
            return make(arena, c);
        }
        case ExprKind::Call: {
            const ExprCall& call = cast<ExprCall>(e);
            std::vector<Expr> args = xs(call.args);
            std::optional<std::string> calleeQual = maybe(call.callee, prefix, shadowed);
            ExprCall c = call;
            if (calleeQual) c.callee = *calleeQual;
            c.args = args;
            return make(arena, c);
        }
        case ExprKind::Binary: {
            ExprBinary c = cast<ExprBinary>(e);
            c.left = x(c.left);
            c.right = x(c.right);
            return make(arena, c);
        }
        case ExprKind::Unary: {
            ExprUnary c = cast<ExprUnary>(e);
            c.operand = x(c.operand);
            return make(arena, c);
        }
        case ExprKind::IncDec: {
            ExprIncDec c = cast<ExprIncDec>(e);
            c.target = x(c.target);
            return make(arena, c);
        }
        case ExprKind::Grouping: {
            ExprGrouping c = cast<ExprGrouping>(e);
            c.expr = x(c.expr);
            return make(arena, c);
        }
        case ExprKind::Index: {
            ExprIndex c = cast<ExprIndex>(e);
            c.target = x(c.target);
            c.index = x(c.index);
            return make(arena, c);
        }
        case ExprKind::Range: {
            ExprRange c = cast<ExprRange>(e);
            c.from = x(c.from);
            c.to = x(c.to);
            return make(arena, c);
        }
        case ExprKind::ArrayLiteral: {
            ExprArrayLiteral c = cast<ExprArrayLiteral>(e);
            c.elements = xs(c.elements);
            return make(arena, c);
        }
        case ExprKind::MapLit: {
            ExprMapLit c = cast<ExprMapLit>(e);
            for (auto& entry : c.entries) {
                Expr k = x(entry.first);
                Expr v = x(entry.second);
                entry = {k, v};
            }
            return make(arena, c);
        }
        case ExprKind::TupleLit: {
            ExprTupleLit c = cast<ExprTupleLit>(e);
            c.elements = xs(c.elements);
            return make(arena, c);
        }
        case ExprKind::VariantLit: {
            ExprVariantLit c = cast<ExprVariantLit>(e);
            c.elements = xs(c.elements);
            return make(arena, c);
        }
        case ExprKind::TupleAccess: {
            ExprTupleAccess c = cast<ExprTupleAccess>(e);
            c.target = x(c.target);
            return make(arena, c);
        }
        case ExprKind::StringTemplate: {
            ExprStringTemplate c = cast<ExprStringTemplate>(e);
            for (StringTemplatePart& part : c.parts) {
                if (!part.isLiteral) part = exprPart(x(part.expr));
            }
            return make(arena, c);
        }
        case ExprKind::Lambda: {
            ExprLambda c = cast<ExprLambda>(e);
            c.body = stmts(c.body, prefix, shadowed);
            return make(arena, c);
        }
        case ExprKind::NamedArg: {
            ExprNamedArg c = cast<ExprNamedArg>(e);
            c.value = x(c.value);
            return make(arena, c);
        }
        case ExprKind::CatchExpr: {
            ExprCatchExpr c = cast<ExprCatchExpr>(e);
            c.expr = x(c.expr);
            c.fallback = x(c.fallback);
            return make(arena, c);
        }
        case ExprKind::TryPropagate: {
            ExprTryPropagate c = cast<ExprTryPropagate>(e);
            c.expr = x(c.expr);
            return make(arena, c);
        }
        case ExprKind::IfExpr: {
            ExprIfExpr c = cast<ExprIfExpr>(e);
            c.condition = x(c.condition);
            c.thenExpr = x(c.thenExpr);
            c.elseExpr = x(c.elseExpr);
            return make(arena, c);
        }
        case ExprKind::NullCoalesce: {
            ExprNullCoalesce c = cast<ExprNullCoalesce>(e);
            c.left = x(c.left);
            c.right = x(c.right);
            return make(arena, c);
        }
        case ExprKind::Cast: {
            ExprCast c = cast<ExprCast>(e);
            c.expr = x(c.expr);
            return make(arena, c);
        }
        case ExprKind::IsCheck: {
            ExprIsCheck c = cast<ExprIsCheck>(e);
            c.expr = x(c.expr);
            return make(arena, c);
        }
        case ExprKind::SafeMember: {
            ExprSafeMember c = cast<ExprSafeMember>(e);
            c.target = x(c.target);
            return make(arena, c);
        }
        case ExprKind::Alloc: {
            ExprAlloc c = cast<ExprAlloc>(e);
            c.value = x(c.value);
            return make(arena, c);
        }
        case ExprKind::Deref: {
            ExprDeref c = cast<ExprDeref>(e);
            c.target = x(c.target);
            return make(arena, c);
        }
        case ExprKind::Isolated: {
            ExprIsolated c = cast<ExprIsolated>(e);
            c.value = x(c.value);
            return make(arena, c);
        }
        case ExprKind::Await: {
            ExprAwait c = cast<ExprAwait>(e);
            c.value = x(c.value);
            return make(arena, c);
        }
        case ExprKind::Spread: {
            ExprSpread c = cast<ExprSpread>(e);
            c.array = x(c.array);
            return make(arena, c);
        }
        case ExprKind::MetaInvoke: {
            const ExprMetaInvoke& m = cast<ExprMetaInvoke>(e);
            std::vector<Expr> args = xs(m.args);
            std::optional<std::string> qualified = maybe(m.name, prefix, shadowed);
            ExprMetaInvoke c = m;
            if (qualified) c.name = *qualified;
            c.args = args;
            return make(arena, c);
        }
        default:
            return e;
    }
}

}  // namespace

std::optional<std::string> scopePrefix(const std::string& name) {
    for (int64_t i = (int64_t)name.size() - 2; i >= 1; i--) {
        size_t k = (size_t)i;
        if (name[k] == '_' && name[k + 1] == '_' && name[k - 1] != '_') return name.substr(0, k);
    }
    return std::nullopt;
}

std::optional<std::string> scopeQualifiedSpelling(const std::string& name) {
    std::optional<std::string> prefix = scopePrefix(name);
    if (!prefix) return std::nullopt;
    return replaceAll(*prefix, "__", "::") + "::" + name.substr(prefix->size() + 2);
}

namespace ScopeAccessRewriter {

Program rewrite(Arena& arena, const Program& program) {
    NameSet mangled;
    for (const TopLevel& item : program.items) {
        if (TopLevelBridge* bridge = as<TopLevelBridge>(item)) {
            for (const BridgeSig& sig : bridge->funcs) mangled.insert(sig.localName ? *sig.localName : sig.name);
            for (const BridgeValue& value : bridge->values) mangled.insert(value.name);
            continue;
        }
        std::optional<std::string> name = nameOf(item);
        if (name && contains(*name, "__")) mangled.insert(*name);
    }
    if (mangled.empty()) return program;

    Rewriter r{arena, mangled};
    const NameSet empty;
    std::vector<TopLevel> rewrittenItems;
    for (const TopLevel& item : program.items) {
        if (TopLevelBridge* bridge = as<TopLevelBridge>(item)) {
            TopLevelBridge copy = *bridge;
            for (BridgeValue& value : copy.values) {
                std::optional<std::string> prefix = value.localScope ? value.localScope : scopePrefix(value.name);
                value.initializer = r.expr(value.initializer, prefix, empty);
            }
            rewrittenItems.push_back(make(arena, copy));
            continue;
        }
        if (TopLevelImpl* impl = as<TopLevelImpl>(item)) {
            TopLevelImpl copy = *impl;
            for (FuncDecl& method : copy.methods) {
                NameSet shadowed = collectShadowed(method);
                method.body = r.stmts(method.body, impl->scopePrefix, shadowed);
            }
            rewrittenItems.push_back(make(arena, copy));
            continue;
        }
        // Scope-scoped types keep their source-level name; their field defaults
        // still execute in that scope.
        std::optional<std::string> typeName;
        if (TopLevelPack* p = as<TopLevelPack>(item)) typeName = p->name;
        else if (TopLevelSolo* s = as<TopLevelSolo>(item)) typeName = s->name;
        else if (TopLevelDeco* d = as<TopLevelDeco>(item)) typeName = d->name;
        if (typeName) {
            std::optional<std::string> typePrefix;
            if (const std::string* ns = program.scopeTypeNamespaces.get(*typeName)) typePrefix = replaceAll(*ns, "::", "__");
            auto fields = [&](std::vector<PackField>& list) {
                for (PackField& field : list) {
                    if (field.default_) field.default_ = r.expr(field.default_, typePrefix, empty);
                }
            };
            if (TopLevelPack* p = as<TopLevelPack>(item)) {
                TopLevelPack copy = *p;
                fields(copy.fields);
                rewrittenItems.push_back(make(arena, copy));
            } else if (TopLevelSolo* s = as<TopLevelSolo>(item)) {
                TopLevelSolo copy = *s;
                fields(copy.fields);
                rewrittenItems.push_back(make(arena, copy));
            } else {
                TopLevelDeco copy = cast<TopLevelDeco>(item);
                fields(copy.fields);
                rewrittenItems.push_back(make(arena, copy));
            }
            continue;
        }
        std::optional<std::string> name = nameOf(item);
        std::optional<std::string> prefix = name ? scopePrefix(*name) : std::nullopt;
        NameSet shadowed = collectShadowed(item);
        switch (item.kind()) {
            case TopLevelKind::Func: {
                TopLevelFunc copy = cast<TopLevelFunc>(item);
                copy.decl.body = r.stmts(copy.decl.body, prefix, shadowed);
                rewrittenItems.push_back(make(arena, copy));
                break;
            }
            case TopLevelKind::Test: {
                TopLevelTest copy = cast<TopLevelTest>(item);
                copy.body = r.stmts(copy.body, prefix, shadowed);
                rewrittenItems.push_back(make(arena, copy));
                break;
            }
            case TopLevelKind::FinDecl: {
                TopLevelFinDecl copy = cast<TopLevelFinDecl>(item);
                copy.initializer = r.expr(copy.initializer, prefix, shadowed);
                rewrittenItems.push_back(make(arena, copy));
                break;
            }
            case TopLevelKind::LetDecl: {
                TopLevelLetDecl copy = cast<TopLevelLetDecl>(item);
                copy.initializer = r.expr(copy.initializer, prefix, shadowed);
                rewrittenItems.push_back(make(arena, copy));
                break;
            }
            case TopLevelKind::VarDecl: {
                TopLevelVarDecl copy = cast<TopLevelVarDecl>(item);
                copy.initializer = r.expr(copy.initializer, prefix, shadowed);
                rewrittenItems.push_back(make(arena, copy));
                break;
            }
            case TopLevelKind::InlineFin: {
                TopLevelInlineFin copy = cast<TopLevelInlineFin>(item);
                copy.initializer = r.expr(copy.initializer, prefix, shadowed);
                rewrittenItems.push_back(make(arena, copy));
                break;
            }
            default:
                rewrittenItems.push_back(item);
                break;
        }
    }
    Program out = program;
    out.items = rewrittenItems;
    return out;
}

}  // namespace ScopeAccessRewriter
}  // namespace azora
