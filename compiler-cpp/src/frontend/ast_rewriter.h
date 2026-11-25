// Port of AstMapper.kt (CPP_PORT_PLAN.md §6: `open class AstMapper` with
// subclasses becomes a struct of optional hooks).
//
// A structural copy of a function body that visits every statement,
// expression, parameter and written type - including the bodies of lambdas,
// `using`, `defer`, `try` and `effect` blocks.
//
// A pass sets the hooks it cares about. Each dispatcher (stmts(), expr(), ...)
// calls the pass's hook when set and AstMapper's own behaviour (base*())
// otherwise; a hook calls base*() where the Kotlin override calls `super`.
// The base walks recurse through the dispatchers, so a hook reaches code at
// any depth, exactly as a Kotlin override does.
#pragma once

#include "frontend/ast.h"

#include <functional>
#include <vector>

namespace azora {

struct AstRewriter {
    explicit AstRewriter(Arena& arena) : arena(arena) {}
    AstRewriter(const AstRewriter&) = delete;
    AstRewriter& operator=(const AstRewriter&) = delete;

    Arena& arena;

    // Hooks (Kotlin overrides). Unset means AstMapper's own behaviour.
    std::function<std::vector<Stmt>(AstRewriter&, const std::vector<Stmt>&)> onStmts;
    std::function<std::vector<Stmt>(AstRewriter&, const Stmt&)> onExpandStmt;
    std::function<TypeRef(AstRewriter&, const TypeRef&)> onTypeRef;
    std::function<TypeAnnotation(AstRewriter&, const TypeAnnotation&)> onTypeAnnotation;
    std::function<Param(AstRewriter&, const Param&)> onParam;
    std::function<Stmt(AstRewriter&, const Stmt&)> onStmt;
    std::function<std::vector<Expr>(AstRewriter&, const std::vector<Expr>&)> onArgs;
    std::function<Expr(AstRewriter&, const Expr&)> onExpr;

    // Dispatchers (Kotlin virtual calls).
    std::vector<Stmt> stmts(const std::vector<Stmt>& body) { return onStmts ? onStmts(*this, body) : baseStmts(body); }
    std::vector<Stmt> expandStmt(const Stmt& s) { return onExpandStmt ? onExpandStmt(*this, s) : baseExpandStmt(s); }
    TypeRef typeRef(const TypeRef& ref) { return onTypeRef ? onTypeRef(*this, ref) : baseTypeRef(ref); }
    TypeAnnotation typeAnnotation(const TypeAnnotation& annotation) {
        return onTypeAnnotation ? onTypeAnnotation(*this, annotation) : baseTypeAnnotation(annotation);
    }
    Param param(const Param& p) { return onParam ? onParam(*this, p) : baseParam(p); }
    Stmt stmt(const Stmt& s) { return onStmt ? onStmt(*this, s) : baseStmt(s); }
    std::vector<Expr> args(const std::vector<Expr>& a) { return onArgs ? onArgs(*this, a) : baseArgs(a); }
    Expr expr(const Expr& e) { return onExpr ? onExpr(*this, e) : baseExpr(e); }

    // AstMapper's own behaviour (Kotlin `super`).
    /** A block: each statement mapped, and possibly expanded in place. */
    std::vector<Stmt> baseStmts(const std::vector<Stmt>& body);
    /** One statement as a block entry; by default exactly one mapped statement. */
    std::vector<Stmt> baseExpandStmt(const Stmt& s);
    TypeRef baseTypeRef(const TypeRef& ref);
    TypeAnnotation baseTypeAnnotation(const TypeAnnotation& annotation);
    Param baseParam(const Param& p);
    Stmt baseStmt(const Stmt& s);
    /** One call argument; a pass may expand it into several. */
    std::vector<Expr> baseArgs(const std::vector<Expr>& a);
    Expr baseExpr(const Expr& e);

    /** `list.map { expr(it) }`. */
    std::vector<Expr> exprs(const std::vector<Expr>& items);
    /** `list.map { typeRef(it) }`. */
    std::vector<TypeRef> typeRefs(const std::vector<TypeRef>& items);
    /** `list.map { param(it) }`. */
    std::vector<Param> params(const std::vector<Param>& items);
};

}  // namespace azora
