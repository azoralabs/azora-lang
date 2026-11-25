// Port of LocalNames.kt.
#include "frontend/local_names.h"

namespace azora {

namespace {

void visitAll(const std::vector<Stmt>& body, OrderedSet<std::string>& names);

void visit(const Stmt& s, OrderedSet<std::string>& names) {
    switch (s.kind()) {
        case StmtKind::VarDecl: names.add(cast<StmtVarDecl>(s).name); break;
        case StmtKind::FinDecl: names.add(cast<StmtFinDecl>(s).name); break;
        case StmtKind::LetDecl: names.add(cast<StmtLetDecl>(s).name); break;
        case StmtKind::InlineFin: names.add(cast<StmtInlineFin>(s).name); break;
        case StmtKind::InlineLet: names.add(cast<StmtInlineLet>(s).name); break;
        case StmtKind::InlineVar: names.add(cast<StmtInlineVar>(s).name); break;
        case StmtKind::RemDecl: names.add(cast<StmtRemDecl>(s).name); break;
        case StmtKind::For: {
            const StmtFor& f = cast<StmtFor>(s);
            names.add(f.name);
            if (f.indexName) names.add(*f.indexName);
            visitAll(f.body, names);
            break;
        }
        case StmtKind::InlineFor: {
            const StmtInlineFor& f = cast<StmtInlineFor>(s);
            names.add(f.name);
            if (f.indexName) names.add(*f.indexName);
            visitAll(f.body, names);
            break;
        }
        case StmtKind::Try: {
            const StmtTry& t = cast<StmtTry>(s);
            if (t.catchName) names.add(*t.catchName);
            visitAll(t.body, names);
            if (t.catchBody) visitAll(*t.catchBody, names);
            break;
        }
        case StmtKind::If:
            visitAll(cast<StmtIf>(s).thenBranch, names);
            if (cast<StmtIf>(s).elseBranch) visitAll(*cast<StmtIf>(s).elseBranch, names);
            break;
        case StmtKind::InlineIf:
            visitAll(cast<StmtInlineIf>(s).thenBranch, names);
            if (cast<StmtInlineIf>(s).elseBranch) visitAll(*cast<StmtInlineIf>(s).elseBranch, names);
            break;
        case StmtKind::DeepInlineIf:
            visitAll(cast<StmtDeepInlineIf>(s).thenBranch, names);
            if (cast<StmtDeepInlineIf>(s).elseBranch) visitAll(*cast<StmtDeepInlineIf>(s).elseBranch, names);
            break;
        case StmtKind::While: visitAll(cast<StmtWhile>(s).body, names); break;
        case StmtKind::Loop: visitAll(cast<StmtLoop>(s).body, names); break;
        case StmtKind::Scope: visitAll(cast<StmtScope>(s).body, names); break;
        case StmtKind::InlineBlock: visitAll(cast<StmtInlineBlock>(s).body, names); break;
        case StmtKind::DeepInlineBlock: visitAll(cast<StmtDeepInlineBlock>(s).body, names); break;
        case StmtKind::Defer: visitAll(cast<StmtDefer>(s).body, names); break;
        case StmtKind::Effect: visitAll(cast<StmtEffect>(s).body, names); break;
        case StmtKind::UsingContext: visitAll(cast<StmtUsingContext>(s).body, names); break;
        case StmtKind::When: {
            const StmtWhen& w = cast<StmtWhen>(s);
            for (const WhenBranch& b : w.branches) visitAll(b.body, names);
            if (w.elseBranch) visitAll(*w.elseBranch, names);
            break;
        }
        case StmtKind::NoInline: visit(cast<StmtNoInline>(s).stmt, names); break;
        default: break;
    }
}

void visitAll(const std::vector<Stmt>& body, OrderedSet<std::string>& names) {
    for (const Stmt& s : body) visit(s, names);
}

}  // namespace

OrderedSet<std::string> localNamesDeclaredIn(const std::vector<Stmt>& body) {
    OrderedSet<std::string> names;
    visitAll(body, names);
    return names;
}

}  // namespace azora
