// Port of DebugInstrumenter.kt.
#include "frontend/debug_instrumenter.h"

namespace azora {
namespace DebugInstrumenter {

namespace {

std::vector<Stmt> instrumentBody(Arena& arena, const std::vector<Stmt>& body);

Stmt dbgCall(Arena& arena, int32_t line) {
    return make(arena, StmtExprStmt{make(arena, ExprCall{"__dbg", {make(arena, ExprIntLiteral{(int64_t)line, line})}, line}),
                                    line});
}

Stmt markNested(Arena& arena, const Stmt& stmt) {
    switch (stmt.kind()) {
        case StmtKind::If: {
            StmtIf c = cast<StmtIf>(stmt);
            c.thenBranch = instrumentBody(arena, c.thenBranch);
            if (c.elseBranch) c.elseBranch = instrumentBody(arena, *c.elseBranch);
            return make(arena, std::move(c));
        }
        case StmtKind::While: {
            StmtWhile c = cast<StmtWhile>(stmt);
            c.body = instrumentBody(arena, c.body);
            return make(arena, std::move(c));
        }
        case StmtKind::For: {
            StmtFor c = cast<StmtFor>(stmt);
            c.body = instrumentBody(arena, c.body);
            return make(arena, std::move(c));
        }
        case StmtKind::Loop: {
            StmtLoop c = cast<StmtLoop>(stmt);
            c.body = instrumentBody(arena, c.body);
            return make(arena, std::move(c));
        }
        case StmtKind::When: {
            StmtWhen c = cast<StmtWhen>(stmt);
            for (WhenBranch& branch : c.branches) branch.body = instrumentBody(arena, branch.body);
            if (c.elseBranch) c.elseBranch = instrumentBody(arena, *c.elseBranch);
            return make(arena, std::move(c));
        }
        case StmtKind::Try: {
            StmtTry c = cast<StmtTry>(stmt);
            c.body = instrumentBody(arena, c.body);
            if (c.catchBody) c.catchBody = instrumentBody(arena, *c.catchBody);
            return make(arena, std::move(c));
        }
        case StmtKind::Scope: {
            StmtScope c = cast<StmtScope>(stmt);
            c.body = instrumentBody(arena, c.body);
            return make(arena, std::move(c));
        }
        case StmtKind::Defer: {
            StmtDefer c = cast<StmtDefer>(stmt);
            c.body = instrumentBody(arena, c.body);
            return make(arena, std::move(c));
        }
        default:
            return stmt;
    }
}

std::vector<Stmt> instrumentBody(Arena& arena, const std::vector<Stmt>& body) {
    std::vector<Stmt> out;
    out.reserve(body.size() * 2);
    for (const Stmt& stmt : body) {
        Stmt marked = markNested(arena, stmt);
        if (stmt.line() > 0) out.push_back(dbgCall(arena, stmt.line()));
        out.push_back(marked);
    }
    return out;
}

}  // namespace

Program instrument(Arena& arena, const Program& program) {
    Program out = program;
    for (TopLevel& item : out.items) {
        if (TopLevelFunc* func = as<TopLevelFunc>(item)) {
            TopLevelFunc c = *func;
            c.decl.body = instrumentBody(arena, c.decl.body);
            item = make(arena, std::move(c));
        } else if (TopLevelTest* test = as<TopLevelTest>(item)) {
            TopLevelTest c = *test;
            c.body = instrumentBody(arena, c.body);
            item = make(arena, std::move(c));
        }
    }
    return out;
}

}  // namespace DebugInstrumenter
}  // namespace azora
