// Port of SourceSymbolValidator.kt: validates names exactly as the author
// wrote them, before compiler synthesis.
#include "frontend/source_symbol_validator.h"

#include "base/failure.h"
#include "base/text.h"

namespace azora {
namespace SourceSymbolValidator {

namespace {

[[noreturn]] void sourceError(int32_t line, const std::string& message) { fail("line " + str(line) + ": " + message); }

/** Kotlin `name.trimStart('_')`. */
std::string trimUnderscores(const std::string& name) {
    size_t i = 0;
    while (i < name.size() && name[i] == '_') i++;
    return name.substr(i);
}

void declaration(const std::string& name, const std::string& kind, int32_t line, bool privateAllowed = false) {
    // A name that appears only now was synthesized by parser lowering.
    if (startsWith(name, "__")) return;
    // `int`, `uint` and `float` are the compiler's own primitives written
    // without their `__`; nothing may be called one.
    std::string bare = trimUnderscores(name);
    if (bare == "int" || bare == "uint" || bare == "float") {
        sourceError(line, "'" + name + "' is too close to the compiler's own '__" + bare + "'; a " + kind +
                              " cannot be called that");
    }
    if (!startsWith(name, "_")) return;
    // `_` is the name for a receiver or parameter deliberately not used.
    if (name == "_" && (kind == "receiver" || kind == "parameter")) return;
    if (name == "_") sourceError(line, "'_' is not a declaration name");
    if (!privateAllowed) {
        sourceError(line, "invalid " + kind + " '" + name + "': this declaration cannot be private with a leading '_'");
    }
}

std::string sourceMember(const std::string& name) {
    for (int64_t i = (int64_t)name.size() - 2; i >= 1; i--) {
        size_t k = (size_t)i;
        if (name[k] == '_' && name[k + 1] == '_' && name[k - 1] != '_') return name.substr(k + 2);
    }
    return name;
}

std::optional<std::string> scopePrefixOf(const std::string& name) {
    for (int64_t i = (int64_t)name.size() - 2; i >= 1; i--) {
        size_t k = (size_t)i;
        if (name[k] == '_' && name[k + 1] == '_' && name[k - 1] != '_') return name.substr(0, k);
    }
    return std::nullopt;
}

void storage(const std::string& name, int32_t line) {
    if (std::optional<std::string> prefix = scopePrefixOf(name)) {
        for (const std::string& part : split(*prefix, "__")) declaration(part, "scope", line);
    }
    declaration(sourceMember(name), "storage variable", line, true);
}

void scopeDeclaration(const std::string& name, const std::string& kind, int32_t line) {
    if (std::optional<std::string> prefix = scopePrefixOf(name)) {
        for (const std::string& part : split(*prefix, "__")) declaration(part, "scope", line);
    }
    declaration(sourceMember(name), kind, line, true);
}

void expression(const Expr& e);
void statement(const Stmt& s);

void parameter(const Param& p, int32_t line) {
    declaration(p.name, "parameter", line);
    if (p.defaultValue) expression(p.defaultValue);
}

void field(const PackField& f, int32_t line, bool privateAllowed) {
    declaration(f.name, "field", line, privateAllowed);
    if (f.default_) expression(f.default_);
    for (const FieldRepeat& r : f.repeats) {
        declaration(r.variable, "inline loop variable", line);
        expression(r.range);
    }
    if (f.condition) expression(f.condition);
}

void function(const FuncDecl& f) {
    scopeDeclaration(f.name, f.memberCallStyle == MemberCallStyle::PROPERTY ? "property" : "function", f.line);
    for (const std::string& t : f.typeParams) declaration(t, "type parameter", f.line);
    if (f.variadicParam) declaration(*f.variadicParam, "variadic type parameter", f.line);
    for (const Param& p : f.params) parameter(p, f.line);
    if (f.extensionReceiver) parameter(*f.extensionReceiver, f.line);
    declaration(f.receiverName, "receiver", f.line);
    for (const Stmt& s : f.body) statement(s);
    if (f.whereClause) expression(f.whereClause);
}

void statements(const std::vector<Stmt>& items) {
    for (const Stmt& s : items) statement(s);
}

void statement(const Stmt& s) {
    switch (s.kind()) {
        case StmtKind::None:
        case StmtKind::Import:
        case StmtKind::Continue:
            return;
        case StmtKind::VarDecl: {
            const StmtVarDecl& d = cast<StmtVarDecl>(s);
            declaration(d.name, "local variable", d.line);
            expression(d.initializer);
            return;
        }
        case StmtKind::FinDecl: {
            const StmtFinDecl& d = cast<StmtFinDecl>(s);
            declaration(d.name, "local variable", d.line);
            expression(d.initializer);
            return;
        }
        case StmtKind::LetDecl: {
            const StmtLetDecl& d = cast<StmtLetDecl>(s);
            declaration(d.name, "local variable", d.line);
            expression(d.initializer);
            return;
        }
        case StmtKind::InlineVar: {
            const StmtInlineVar& d = cast<StmtInlineVar>(s);
            declaration(d.name, "local compile-time variable", d.line);
            expression(d.initializer);
            return;
        }
        case StmtKind::InlineFin: {
            const StmtInlineFin& d = cast<StmtInlineFin>(s);
            declaration(d.name, "local compile-time variable", d.line);
            expression(d.initializer);
            return;
        }
        case StmtKind::InlineLet: {
            const StmtInlineLet& d = cast<StmtInlineLet>(s);
            declaration(d.name, "local compile-time variable", d.line);
            expression(d.initializer);
            return;
        }
        case StmtKind::RemDecl: {
            const StmtRemDecl& d = cast<StmtRemDecl>(s);
            declaration(d.name, "reactive local variable", d.line);
            expression(d.initializer);
            return;
        }
        case StmtKind::InlineAssignment: expression(cast<StmtInlineAssignment>(s).value); return;
        case StmtKind::Assignment: expression(cast<StmtAssignment>(s).value); return;
        case StmtKind::Return:
            if (cast<StmtReturn>(s).value) expression(cast<StmtReturn>(s).value);
            return;
        case StmtKind::ExprStmt: expression(cast<StmtExprStmt>(s).expr); return;
        case StmtKind::Scope: statements(cast<StmtScope>(s).body); return;
        case StmtKind::If: {
            const StmtIf& i = cast<StmtIf>(s);
            expression(i.condition);
            statements(i.thenBranch);
            if (i.elseBranch) statements(*i.elseBranch);
            return;
        }
        case StmtKind::InlineIf: {
            const StmtInlineIf& i = cast<StmtInlineIf>(s);
            expression(i.condition);
            statements(i.thenBranch);
            if (i.elseBranch) statements(*i.elseBranch);
            return;
        }
        case StmtKind::DeepInlineIf: {
            const StmtDeepInlineIf& i = cast<StmtDeepInlineIf>(s);
            expression(i.condition);
            statements(i.thenBranch);
            if (i.elseBranch) statements(*i.elseBranch);
            return;
        }
        case StmtKind::DeepInlineBlock: statements(cast<StmtDeepInlineBlock>(s).body); return;
        case StmtKind::NoInline: statement(cast<StmtNoInline>(s).stmt); return;
        case StmtKind::InlineBlock: statements(cast<StmtInlineBlock>(s).body); return;
        case StmtKind::Assert:
            expression(cast<StmtAssert>(s).condition);
            expression(cast<StmtAssert>(s).message);
            return;
        case StmtKind::Trace: {
            const StmtTrace& t = cast<StmtTrace>(s);
            if (t.level) expression(t.level);
            expression(t.message);
            return;
        }
        case StmtKind::InlineAssert:
            expression(cast<StmtInlineAssert>(s).condition);
            expression(cast<StmtInlineAssert>(s).message);
            return;
        case StmtKind::InlineTrace: {
            const StmtInlineTrace& t = cast<StmtInlineTrace>(s);
            if (t.level) expression(t.level);
            expression(t.message);
            return;
        }
        case StmtKind::While: {
            const StmtWhile& w = cast<StmtWhile>(s);
            if (w.label) declaration(*w.label, "loop label", w.line);
            expression(w.condition);
            statements(w.body);
            return;
        }
        case StmtKind::For: {
            const StmtFor& f = cast<StmtFor>(s);
            declaration(f.name, "loop variable", f.line);
            if (f.indexName) declaration(*f.indexName, "loop index", f.line);
            if (f.label) declaration(*f.label, "loop label", f.line);
            expression(f.iterable);
            if (f.step) expression(f.step);
            statements(f.body);
            return;
        }
        case StmtKind::Loop: {
            const StmtLoop& l = cast<StmtLoop>(s);
            if (l.label) declaration(*l.label, "loop label", l.line);
            if (l.iterable) expression(l.iterable);
            statements(l.body);
            return;
        }
        case StmtKind::InlineFor: {
            const StmtInlineFor& f = cast<StmtInlineFor>(s);
            declaration(f.name, "inline loop variable", f.line);
            if (f.indexName) declaration(*f.indexName, "inline loop index", f.line);
            expression(f.iterable);
            statements(f.body);
            return;
        }
        case StmtKind::Break:
            if (cast<StmtBreak>(s).value) expression(cast<StmtBreak>(s).value);
            return;
        case StmtKind::Exchange:
            expression(cast<StmtExchange>(s).left);
            expression(cast<StmtExchange>(s).right);
            return;
        case StmtKind::IndexAssign: {
            const StmtIndexAssign& a = cast<StmtIndexAssign>(s);
            expression(a.target);
            expression(a.index);
            expression(a.value);
            return;
        }
        case StmtKind::MemberAssign: {
            const StmtMemberAssign& a = cast<StmtMemberAssign>(s);
            expression(a.target);
            if (a.nameExpr) expression(a.nameExpr);
            expression(a.value);
            return;
        }
        case StmtKind::When: {
            const StmtWhen& w = cast<StmtWhen>(s);
            expression(w.scrutinee);
            for (const WhenBranch& branch : w.branches) {
                for (const Expr& p : branch.patterns) expression(p);
                statements(branch.body);
            }
            if (w.elseBranch) statements(*w.elseBranch);
            return;
        }
        case StmtKind::Throw: expression(cast<StmtThrow>(s).value); return;
        case StmtKind::Panic: expression(cast<StmtPanic>(s).message); return;
        case StmtKind::DerefAssign:
            expression(cast<StmtDerefAssign>(s).target);
            expression(cast<StmtDerefAssign>(s).value);
            return;
        case StmtKind::Yield: expression(cast<StmtYield>(s).value); return;
        case StmtKind::Try: {
            const StmtTry& t = cast<StmtTry>(s);
            if (t.catchName) declaration(*t.catchName, "catch variable", t.line);
            statements(t.body);
            if (t.catchBody) statements(*t.catchBody);
            return;
        }
        case StmtKind::Defer: statements(cast<StmtDefer>(s).body); return;
        case StmtKind::Effect: {
            const StmtEffect& e = cast<StmtEffect>(s);
            if (e.dependencies) {
                for (const Expr& d : *e.dependencies) expression(d);
            }
            statements(e.body);
            return;
        }
        case StmtKind::UsingContext: {
            const StmtUsingContext& u = cast<StmtUsingContext>(s);
            for (const Expr& v : u.values) expression(v);
            statements(u.body);
            return;
        }
    }
}

void expressions(const std::vector<Expr>& items) {
    for (const Expr& e : items) expression(e);
}

void expression(const Expr& e) {
    switch (e.kind()) {
        // `.Name` names nothing on its own; `.(args)` carries expressions.
        case ExprKind::InferredMember:
            if (cast<ExprInferredMember>(e).ctorArgs) expressions(*cast<ExprInferredMember>(e).ctorArgs);
            return;
        case ExprKind::MapEntryArg:
            expression(cast<ExprMapEntryArg>(e).key);
            expression(cast<ExprMapEntryArg>(e).value);
            return;
        case ExprKind::None:
        case ExprKind::IntLiteral:
        case ExprKind::DoubleLiteral:
        case ExprKind::CharLiteral:
        case ExprKind::StringLiteral:
        case ExprKind::BoolLiteral:
        case ExprKind::Identifier:
        case ExprKind::UpperScopeAccess:
        case ExprKind::NullLiteral:
        case ExprKind::Inject:
            return;
        case ExprKind::Binary:
            expression(cast<ExprBinary>(e).left);
            expression(cast<ExprBinary>(e).right);
            return;
        case ExprKind::Unary: expression(cast<ExprUnary>(e).operand); return;
        case ExprKind::IncDec: expression(cast<ExprIncDec>(e).target); return;
        case ExprKind::Call: {
            const ExprCall& c = cast<ExprCall>(e);
            expressions(c.args);
            if (c.receiver) expression(c.receiver);
            return;
        }
        case ExprKind::Grouping: expression(cast<ExprGrouping>(e).expr); return;
        case ExprKind::Range:
            expression(cast<ExprRange>(e).from);
            expression(cast<ExprRange>(e).to);
            return;
        case ExprKind::ArrayLiteral: expressions(cast<ExprArrayLiteral>(e).elements); return;
        case ExprKind::SetLiteral: expressions(cast<ExprSetLiteral>(e).elements); return;
        case ExprKind::Index:
            expression(cast<ExprIndex>(e).target);
            expression(cast<ExprIndex>(e).index);
            return;
        case ExprKind::Member: {
            const ExprMember& m = cast<ExprMember>(e);
            expression(m.target);
            if (m.nameExpr) expression(m.nameExpr);
            return;
        }
        case ExprKind::MethodCall:
            expression(cast<ExprMethodCall>(e).target);
            expressions(cast<ExprMethodCall>(e).args);
            return;
        case ExprKind::StringTemplate:
            for (const StringTemplatePart& p : cast<ExprStringTemplate>(e).parts) {
                if (!p.isLiteral) expression(p.expr);
            }
            return;
        case ExprKind::TupleLit: expressions(cast<ExprTupleLit>(e).elements); return;
        case ExprKind::VariantLit: expressions(cast<ExprVariantLit>(e).elements); return;
        case ExprKind::TupleAccess: expression(cast<ExprTupleAccess>(e).target); return;
        case ExprKind::CatchExpr:
            expression(cast<ExprCatchExpr>(e).expr);
            expression(cast<ExprCatchExpr>(e).fallback);
            return;
        case ExprKind::TryPropagate: expression(cast<ExprTryPropagate>(e).expr); return;
        case ExprKind::Seal: expression(cast<ExprSeal>(e).value); return;
        case ExprKind::IfExpr:
            expression(cast<ExprIfExpr>(e).condition);
            expression(cast<ExprIfExpr>(e).thenExpr);
            expression(cast<ExprIfExpr>(e).elseExpr);
            return;
        case ExprKind::Lambda: {
            const ExprLambda& l = cast<ExprLambda>(e);
            for (const std::string& t : l.typeParams) declaration(t, "lambda type parameter", l.line);
            if (l.variadicTypeParam) declaration(*l.variadicTypeParam, "lambda variadic type parameter", l.line);
            for (const Param& p : l.params) parameter(p, l.line);
            for (const Param& p : l.receivers) parameter(p, l.line);
            for (const Capture& c : l.captures) declaration(c.name, "lambda capture", c.line);
            statements(l.body);
            return;
        }
        case ExprKind::InlineForArgs: {
            const ExprInlineForArgs& f = cast<ExprInlineForArgs>(e);
            declaration(f.name, "inline loop variable", f.line);
            expression(f.iterable);
            expression(f.body);
            return;
        }
        case ExprKind::NamedArg: expression(cast<ExprNamedArg>(e).value); return;
        case ExprKind::NullCoalesce:
            expression(cast<ExprNullCoalesce>(e).left);
            expression(cast<ExprNullCoalesce>(e).right);
            return;
        case ExprKind::SafeMember: expression(cast<ExprSafeMember>(e).target); return;
        case ExprKind::Cast: expression(cast<ExprCast>(e).expr); return;
        case ExprKind::IsCheck: expression(cast<ExprIsCheck>(e).expr); return;
        case ExprKind::InCheck:
            expression(cast<ExprInCheck>(e).value);
            expression(cast<ExprInCheck>(e).collection);
            return;
        case ExprKind::MapLit:
            for (const auto& [k, v] : cast<ExprMapLit>(e).entries) {
                expression(k);
                expression(v);
            }
            return;
        case ExprKind::Alloc: expression(cast<ExprAlloc>(e).value); return;
        case ExprKind::Deref: expression(cast<ExprDeref>(e).target); return;
        case ExprKind::Isolated: expression(cast<ExprIsolated>(e).value); return;
        case ExprKind::Await: expression(cast<ExprAwait>(e).value); return;
        case ExprKind::Spread: expression(cast<ExprSpread>(e).array); return;
        case ExprKind::MetaInvoke: expressions(cast<ExprMetaInvoke>(e).args); return;
        case ExprKind::Slice: {
            const ExprSlice& s = cast<ExprSlice>(e);
            expression(s.target);
            if (s.start) expression(s.start);
            if (s.stop) expression(s.stop);
            if (s.step) expression(s.step);
            return;
        }
    }
}

void typeFunctionStatements(const std::vector<TypeFunctionStmt>& items, int32_t line) {
    for (const TypeFunctionStmt& s : items) {
        switch (s.kind()) {
            case TypeFunctionStmtKind::Binding:
                declaration(cast<TypeFunctionStmtBinding>(s).name, "type property variable", line);
                break;
            case TypeFunctionStmtKind::ForEach:
                declaration(cast<TypeFunctionStmtForEach>(s).name, "type property loop variable", line);
                typeFunctionStatements(cast<TypeFunctionStmtForEach>(s).body, line);
                break;
            case TypeFunctionStmtKind::If:
                typeFunctionStatements(cast<TypeFunctionStmtIf>(s).thenBody, line);
                typeFunctionStatements(cast<TypeFunctionStmtIf>(s).elseBody, line);
                break;
            default:
                break;
        }
    }
}

void topLevel(const TopLevel& item);

void topLevels(const std::vector<TopLevel>& items) {
    for (const TopLevel& item : items) topLevel(item);
}

void topLevel(const TopLevel& item) {
    switch (item.kind()) {
        case TopLevelKind::None: return;
        case TopLevelKind::Func: function(cast<TopLevelFunc>(item).decl); return;
        case TopLevelKind::VarDecl: storage(cast<TopLevelVarDecl>(item).name, cast<TopLevelVarDecl>(item).line); return;
        case TopLevelKind::FinDecl: storage(cast<TopLevelFinDecl>(item).name, cast<TopLevelFinDecl>(item).line); return;
        case TopLevelKind::LetDecl: storage(cast<TopLevelLetDecl>(item).name, cast<TopLevelLetDecl>(item).line); return;
        case TopLevelKind::InlineVar: storage(cast<TopLevelInlineVar>(item).name, cast<TopLevelInlineVar>(item).line); return;
        case TopLevelKind::InlineFin: storage(cast<TopLevelInlineFin>(item).name, cast<TopLevelInlineFin>(item).line); return;
        case TopLevelKind::InlineLet: storage(cast<TopLevelInlineLet>(item).name, cast<TopLevelInlineLet>(item).line); return;
        case TopLevelKind::InlineAssignment: expression(cast<TopLevelInlineAssignment>(item).value); return;
        case TopLevelKind::InlineIf: {
            const TopLevelInlineIf& i = cast<TopLevelInlineIf>(item);
            expression(i.condition);
            topLevels(i.thenBranch);
            if (i.elseBranch) topLevels(*i.elseBranch);
            return;
        }
        case TopLevelKind::InlineBlock: topLevels(cast<TopLevelInlineBlock>(item).body); return;
        case TopLevelKind::DeepInlineBlock: topLevels(cast<TopLevelDeepInlineBlock>(item).body); return;
        case TopLevelKind::DeepInlineIf: {
            const TopLevelDeepInlineIf& i = cast<TopLevelDeepInlineIf>(item);
            expression(i.condition);
            topLevels(i.thenBranch);
            if (i.elseBranch) topLevels(*i.elseBranch);
            return;
        }
        case TopLevelKind::Test: statements(cast<TopLevelTest>(item).body); return;
        case TopLevelKind::Pack: {
            const TopLevelPack& p = cast<TopLevelPack>(item);
            if (p.nameMacro == nullptr) declaration(p.name, "pack", p.line, true);
            else expression(p.nameMacro);
            for (const std::string& t : p.typeParams) declaration(t, "type parameter", p.line);
            if (p.variadicParam) declaration(*p.variadicParam, "variadic type parameter", p.line);
            for (const PackField& f : p.fields) field(f, p.line, true);
            if (p.fieldTemplate) {
                const VariadicFieldTemplate& t = *p.fieldTemplate;
                declaration(t.loopVar, "inline loop variable", p.line);
                declaration(t.packVar, "variadic type parameter", p.line);
                for (const TplField& f : t.fields) declaration(f.name, "generated field", p.line, true);
                for (ExprStringTemplate* mixin : t.mixins) expression(mixin);
            }
            if (p.whereClause) expression(p.whereClause);
            return;
        }
        case TopLevelKind::Deco: {
            const TopLevelDeco& d = cast<TopLevelDeco>(item);
            declaration(d.name, "decorator", d.line, true);
            for (const PackField& f : d.fields) field(f, d.line, true);
            return;
        }
        case TopLevelKind::Bridge: {
            const TopLevelBridge& b = cast<TopLevelBridge>(item);
            for (const BridgeSig& sig : b.funcs) {
                scopeDeclaration(sig.name, "bridge function", sig.line);
                if (sig.localName) scopeDeclaration(*sig.localName, "bridge function", sig.line);
                if (sig.nameMacro) expression(sig.nameMacro);
                for (const std::string& t : sig.typeParams) declaration(t, "type parameter", sig.line);
                for (const Param& p : sig.params) parameter(p, sig.line);
            }
            for (const BridgeValue& value : b.values) {
                scopeDeclaration(value.name, "bridge value", value.line);
                if (value.nameMacro) expression(value.nameMacro);
                expression(value.initializer);
            }
            return;
        }
        case TopLevelKind::Solo: {
            const TopLevelSolo& s = cast<TopLevelSolo>(item);
            declaration(s.name, "solo pack", s.line, true);
            for (const PackField& f : s.fields) field(f, s.line, true);
            for (const FuncDecl& m : s.methods) function(m);
            return;
        }
        case TopLevelKind::Graph: declaration(cast<TopLevelGraph>(item).name, "graph", cast<TopLevelGraph>(item).line, true); return;
        case TopLevelKind::UseImport: return;
        case TopLevelKind::Enum: {
            const TopLevelEnum& e = cast<TopLevelEnum>(item);
            declaration(e.name, "enum", e.line, true);
            for (const std::string& v : e.variants) declaration(v, "enum value", e.line, true);
            return;
        }
        case TopLevelKind::Fail: {
            const TopLevelFail& f = cast<TopLevelFail>(item);
            declaration(f.name, "error", f.line, true);
            for (const std::string& v : f.variants) declaration(v, "error value", f.line, true);
            return;
        }
        case TopLevelKind::Impl: {
            const TopLevelImpl& i = cast<TopLevelImpl>(item);
            for (const std::string& t : i.typeParams) declaration(t, "type parameter", i.line);
            if (i.variadicParam) declaration(*i.variadicParam, "variadic type parameter", i.line);
            for (const FuncDecl& m : i.methods) function(m);
            expressions(i.decoratorArgs);
            for (const auto& [name, value] : i.decoratorNamedArgs) expression(value);
            return;
        }
        case TopLevelKind::Spec: {
            const TopLevelSpec& s = cast<TopLevelSpec>(item);
            declaration(s.name, "spec", s.line, true);
            for (const std::string& t : s.typeParams) declaration(t, "type parameter", s.line);
            for (const FuncDecl& m : s.methods) function(m);
            if (s.callback) {
                declaration(s.callback->receiverName, "receiver", s.line);
                for (const Param& p : s.callback->params) parameter(p, s.line);
                for (const std::string& t : s.callback->typeParams) declaration(t, "type parameter", s.line);
            }
            return;
        }
        case TopLevelKind::TypeAlias: {
            const TopLevelTypeAlias& t = cast<TopLevelTypeAlias>(item);
            declaration(t.name, "type alias", t.line, true);
            for (const std::string& p : t.typeParams) declaration(p, "type parameter", t.line);
            return;
        }
        case TopLevelKind::Slot: {
            const TopLevelSlot& s = cast<TopLevelSlot>(item);
            declaration(s.name, s.isError ? "variant error" : "variant enum", s.line, true);
            for (const SlotVariant& v : s.variants) declaration(v.name, "variant", s.line, true);
            return;
        }
        case TopLevelKind::Meta: {
            const TopLevelMeta& m = cast<TopLevelMeta>(item);
            scopeDeclaration(m.name, "macro", m.line);
            if (m.parameter) declaration(*m.parameter, "macro parameter", m.line);
            for (const MacroArm& arm : m.arms) {
                switch (arm.pattern.kind) {
                    case MacroPatternKind::Empty: break;
                    case MacroPatternKind::SeqCapture:
                    case MacroPatternKind::TypedCapture:
                        declaration(arm.pattern.name, "macro capture", m.line);
                        break;
                    case MacroPatternKind::MapEntryCapture:
                        declaration(arm.pattern.keyName, "macro capture", m.line);
                        declaration(arm.pattern.valueName, "macro capture", m.line);
                        break;
                }
                expression(arm.template_);
                expressions(arm.templateTail);
            }
            return;
        }
        case TopLevelKind::InlineAssert:
            expression(cast<TopLevelInlineAssert>(item).condition);
            expression(cast<TopLevelInlineAssert>(item).message);
            return;
        case TopLevelKind::InlineTrace: {
            const TopLevelInlineTrace& t = cast<TopLevelInlineTrace>(item);
            if (t.level) expression(t.level);
            expression(t.message);
            return;
        }
    }
}

}  // namespace

void validateTokens(const std::vector<Token>& tokens) {
    for (const Token& token : tokens) {
        if (token.type != TokenType::IDENTIFIER) continue;
        const std::string& name = token.lexeme;
        if (startsWith(name, "__")) {
            sourceError(token.line, "symbol '" + name +
                                        "' is reserved for compiler-generated declarations; user symbols cannot start with '__'");
        }
        if (name.size() > 1 && name.find('_', 1) != std::string::npos) {
            sourceError(token.line, "invalid symbol '" + name +
                                        "': '_' is allowed only once, at the beginning of a private declaration");
        }
    }
}

void validateProgram(const Program& program) {
    if (program.moduleName) {
        for (const std::string& part : split(*program.moduleName, ".")) declaration(part, "module", 1);
    }
    for (const auto& [name, path] : program.scopeTypeNamespaces.entries) {
        for (const std::string& part : split(path, "::")) declaration(part, "scope", 1);
    }
    topLevels(program.items);
    for (const TypeFunctionDecl& f : program.typeFunctions) declaration(f.name, "type property", f.line);
    for (const TypeFunctionDecl& f : program.typeFunctions) {
        for (const TypeFunctionParam& p : f.params) declaration(p.name, "type parameter", f.line);
        typeFunctionStatements(f.body, f.line);
    }
}

}  // namespace SourceSymbolValidator
}  // namespace azora
