// The canonical dump of the AST (CPP_PORT_PLAN.md §8): every field of every
// node in Kotlin's order, matching the oracle's reflective dump.
#include "frontend/ast.h"

#include "base/failure.h"
#include "base/text.h"

#include <type_traits>

namespace azora {

namespace {
int32_t lastInferredSite = 0;
ExprNullLiteral theNullLiteral;
}  // namespace

int32_t InferredSites::next() { return ++lastInferredSite; }
void InferredSites::reset(int32_t last) { lastInferredSite = last; }

Expr nullLiteral() { return Expr(&theNullLiteral); }

void failCast(const char* from) {
    failWith(FailureKind::ClassCast, std::string("class ") + from + " cannot be cast to the expected kind");
}

void implDefaults(TopLevelImpl& impl) {
    if (impl.traitLine < 0) impl.traitLine = impl.line;
    if (impl.traitColumn < 0) impl.traitColumn = impl.column;
    if (impl.traitLength < 0) impl.traitLength = impl.traitName ? u16len(*impl.traitName) : 0;
}

const char* enumName(OwnershipOp value) {
    static const char* const names[] = {"TAKE", "LEND", "SHARE", "BORROW"};
    return names[(int)value];
}
void dumpValue(Dumper& d, OwnershipOp v, int) { d.raw(enumName(v)); }
const char* enumName(RefKind value) {
    static const char* const names[] = {"BORROWED", "MUTABLE", "SHARED", "WEAK"};
    return names[(int)value];
}
void dumpValue(Dumper& d, RefKind v, int) { d.raw(enumName(v)); }
const char* enumName(CastKind value) {
    static const char* const names[] = {"STATIC", "DYNAMIC", "REINTERPRET"};
    return names[(int)value];
}
void dumpValue(Dumper& d, CastKind v, int) { d.raw(enumName(v)); }
const char* enumName(BindingKind value) {
    static const char* const names[] = {"VAR", "VAL", "LET", "FIN"};
    return names[(int)value];
}
void dumpValue(Dumper& d, BindingKind v, int) { d.raw(enumName(v)); }
const char* enumName(ReactiveKind value) {
    static const char* const names[] = {"REMEMBER", "RETAIN", "PRESERVE"};
    return names[(int)value];
}
void dumpValue(Dumper& d, ReactiveKind v, int) { d.raw(enumName(v)); }
const char* enumName(CaptureMode value) {
    static const char* const names[] = {"COPY", "SHARED", "MUTABLE", "CLONE", "MOVE"};
    return names[(int)value];
}
void dumpValue(Dumper& d, CaptureMode v, int) { d.raw(enumName(v)); }
const char* enumName(CallableKind value) {
    static const char* const names[] = {"FUNC", "TASK", "REACT", "REACT_TASK"};
    return names[(int)value];
}
void dumpValue(Dumper& d, CallableKind v, int) { d.raw(enumName(v)); }
const char* enumName(TestMethod value) {
    static const char* const names[] = {"This", "All"};
    return names[(int)value];
}
void dumpValue(Dumper& d, TestMethod v, int) { d.raw(enumName(v)); }
const char* enumName(ParamModifier value) {
    static const char* const names[] = {"NONE", "SHARED", "EXCLUSIVE"};
    return names[(int)value];
}
void dumpValue(Dumper& d, ParamModifier v, int) { d.raw(enumName(v)); }
const char* enumName(MemberCallStyle value) {
    static const char* const names[] = {"NORMAL", "PROPERTY", "METHOD", "STATIC_PROPERTY", "STATIC_METHOD"};
    return names[(int)value];
}
void dumpValue(Dumper& d, MemberCallStyle v, int) { d.raw(enumName(v)); }
const char* enumName(MacroDelimiter value) {
    static const char* const names[] = {"PAREN", "BRACKET", "BRACE"};
    return names[(int)value];
}
void dumpValue(Dumper& d, MacroDelimiter v, int) { d.raw(enumName(v)); }
const char* enumName(TypeFormKind value) {
    static const char* const names[] = {"ARRAY", "ARRAY_SIZED", "SET", "MAP", "TUPLE", "PREFIX", "PREFIX_LIST", "INFIX"};
    return names[(int)value];
}
void dumpValue(Dumper& d, TypeFormKind v, int) { d.raw(enumName(v)); }
const char* enumName(DecoTarget value) {
    static const char* const names[] = {"Pack", "Func", "AsyncFunc", "Prop", "AsyncProp", "Enum", "VariantEnum", "EnumValue", "Error", "VariantError", "ErrorValue", "UnsafeUnion", "UnionValue", "Annot", "Field", "Param", "Var", "Let", "Val", "Fin", "Test", "Module", "Ctor", "Dtor", "TypeAlias", "Bridge", "Oper"};
    return names[(int)value];
}
void dumpValue(Dumper& d, DecoTarget v, int) { d.raw(enumName(v)); }
const char* enumName(ModuleVisibility value) {
    static const char* const names[] = {"PUBLIC", "CONFINED"};
    return names[(int)value];
}
void dumpValue(Dumper& d, ModuleVisibility v, int) { d.raw(enumName(v)); }
const char* enumName(Reach value) {
    static const char* const names[] = {"PUBLIC", "PROTECTED", "CONFINED"};
    return names[(int)value];
}
void dumpValue(Dumper& d, Reach v, int) { d.raw(enumName(v)); }
const char* enumName(ProviderLifetime value) {
    static const char* const names[] = {"SOLO", "FACTORY", "SCOPED"};
    return names[(int)value];
}
void dumpValue(Dumper& d, ProviderLifetime v, int) { d.raw(enumName(v)); }
void dumpValue(Dumper& d, TokenType v, int) { d.raw(tokenTypeName(v)); }

const char* spelling(OwnershipOp op) {
    switch (op) {
        case OwnershipOp::TAKE: return "take";
        case OwnershipOp::LEND: return "lend";
        case OwnershipOp::SHARE: return "&";
        case OwnershipOp::BORROW: return "!";
    }
    AZORA_UNREACHABLE();
}
const char* spelling(RefKind kind) {
    switch (kind) {
        case RefKind::BORROWED: return "ref";
        case RefKind::MUTABLE: return "mut ref";
        case RefKind::SHARED: return "shared ref";
        case RefKind::WEAK: return "weak ref";
    }
    AZORA_UNREACHABLE();
}
const char* spelling(BindingKind kind) {
    switch (kind) {
        case BindingKind::VAR: return "var";
        case BindingKind::VAL: return "val";
        case BindingKind::LET: return "let";
        case BindingKind::FIN: return "fin";
    }
    AZORA_UNREACHABLE();
}
const char* spelling(ReactiveKind kind) {
    switch (kind) {
        case ReactiveKind::REMEMBER: return "remember";
        case ReactiveKind::RETAIN: return "retain";
        case ReactiveKind::PRESERVE: return "preserve";
    }
    AZORA_UNREACHABLE();
}
const char* spelling(CaptureMode mode) {
    switch (mode) {
        case CaptureMode::COPY: return "=";
        case CaptureMode::SHARED: return "&";
        case CaptureMode::MUTABLE: return "!";
        case CaptureMode::CLONE: return "clone()";
        case CaptureMode::MOVE: return "take";
    }
    AZORA_UNREACHABLE();
}
const char* spelling(ProviderLifetime lifetime) {
    switch (lifetime) {
        case ProviderLifetime::SOLO: return "solo";
        case ProviderLifetime::FACTORY: return "factory";
        case ProviderLifetime::SCOPED: return "scoped";
    }
    AZORA_UNREACHABLE();
}
const char* surfaceName(CallableKind kind) {
    switch (kind) {
        case CallableKind::FUNC: return "";
        case CallableKind::TASK: return "async";
        case CallableKind::REACT: return "react";
        case CallableKind::REACT_TASK: return "react async";
    }
    AZORA_UNREACHABLE();
}
const char* sigil(ParamModifier modifier) {
    switch (modifier) {
        case ParamModifier::NONE: return "";
        case ParamModifier::SHARED: return "&";
        case ParamModifier::EXCLUSIVE: return "!";
    }
    AZORA_UNREACHABLE();
}

const char* kindName(ExprKind kind) {
    static const char* const names[] = {"none", "Expr.IntLiteral", "Expr.DoubleLiteral", "Expr.CharLiteral", "Expr.StringLiteral", "Expr.BoolLiteral", "Expr.Identifier", "Expr.Binary", "Expr.Unary", "Expr.IncDec", "Expr.Call", "Expr.Grouping", "Expr.MapEntryArg", "Expr.UpperScopeAccess", "Expr.Range", "Expr.ArrayLiteral", "Expr.SetLiteral", "Expr.Index", "Expr.InferredMember", "Expr.Member", "Expr.MethodCall", "Expr.StringTemplate", "Expr.TupleLit", "Expr.VariantLit", "Expr.TupleAccess", "Expr.CatchExpr", "Expr.TryPropagate", "Expr.IfExpr", "Expr.Seal", "Expr.Lambda", "Expr.InlineForArgs", "Expr.NamedArg", "Expr.NullLiteral", "Expr.NullCoalesce", "Expr.SafeMember", "Expr.Cast", "Expr.IsCheck", "Expr.InCheck", "Expr.MapLit", "Expr.Alloc", "Expr.Deref", "Expr.Isolated", "Expr.Await", "Expr.Inject", "Expr.Spread", "Expr.MetaInvoke", "Expr.Slice"};
    return names[(int)kind];
}
const char* kindName(StmtKind kind) {
    static const char* const names[] = {"none", "Stmt.Import", "Stmt.Exchange", "Stmt.VarDecl", "Stmt.FinDecl", "Stmt.LetDecl", "Stmt.InlineFin", "Stmt.DeepInlineBlock", "Stmt.NoInline", "Stmt.InlineBlock", "Stmt.InlineLet", "Stmt.InlineVar", "Stmt.InlineAssignment", "Stmt.Assignment", "Stmt.Return", "Stmt.ExprStmt", "Stmt.Scope", "Stmt.If", "Stmt.InlineIf", "Stmt.DeepInlineIf", "Stmt.Assert", "Stmt.Trace", "Stmt.InlineAssert", "Stmt.InlineTrace", "Stmt.While", "Stmt.For", "Stmt.Loop", "Stmt.InlineFor", "Stmt.Break", "Stmt.Continue", "Stmt.IndexAssign", "Stmt.MemberAssign", "Stmt.When", "Stmt.Throw", "Stmt.Panic", "Stmt.DerefAssign", "Stmt.Yield", "Stmt.Try", "Stmt.Defer", "Stmt.RemDecl", "Stmt.Effect", "Stmt.UsingContext"};
    return names[(int)kind];
}
const char* kindName(TypeRefKind kind) {
    static const char* const names[] = {"none", "TypeRef.Named", "TypeRef.Array", "TypeRef.Map", "TypeRef.Set", "TypeRef.Function", "TypeRef.Tuple", "TypeRef.Nullable", "TypeRef.Failable", "TypeRef.Pointer", "TypeRef.Const", "TypeRef.Reference"};
    return names[(int)kind];
}
const char* kindName(TopLevelKind kind) {
    static const char* const names[] = {"none", "TopLevel.Func", "TopLevel.VarDecl", "TopLevel.FinDecl", "TopLevel.LetDecl", "TopLevel.InlineVar", "TopLevel.InlineFin", "TopLevel.InlineLet", "TopLevel.InlineAssignment", "TopLevel.InlineIf", "TopLevel.InlineBlock", "TopLevel.DeepInlineBlock", "TopLevel.DeepInlineIf", "TopLevel.Test", "TopLevel.Pack", "TopLevel.Deco", "TopLevel.Bridge", "TopLevel.Solo", "TopLevel.Graph", "TopLevel.UseImport", "TopLevel.Enum", "TopLevel.Fail", "TopLevel.Impl", "TopLevel.Spec", "TopLevel.TypeAlias", "TopLevel.Slot", "TopLevel.Meta", "TopLevel.InlineAssert", "TopLevel.InlineTrace"};
    return names[(int)kind];
}
const char* kindName(TypeFunctionStmtKind kind) {
    static const char* const names[] = {"none", "TypeFunctionStmt.Binding", "TypeFunctionStmt.Assignment", "TypeFunctionStmt.ForEach", "TypeFunctionStmt.If", "TypeFunctionStmt.Return"};
    return names[(int)kind];
}
const char* kindName(TypeFunctionExprKind kind) {
    static const char* const names[] = {"none", "TypeFunctionExpr.Reference", "TypeFunctionExpr.Nullable", "TypeFunctionExpr.PackElement", "TypeFunctionExpr.Call", "TypeFunctionExpr.Conditional"};
    return names[(int)kind];
}
int32_t Expr::line() const {
    return std::visit([](const auto& node) -> int32_t {
        if constexpr (std::is_same_v<std::decay_t<decltype(node)>, std::monostate>) return 0;
        else return node->line;
    }, v);
}
int32_t Expr::column() const {
    return std::visit([](const auto& node) -> int32_t {
        if constexpr (std::is_same_v<std::decay_t<decltype(node)>, std::monostate>) return 0;
        else return node->column;
    }, v);
}
int32_t Expr::length() const {
    return std::visit([](const auto& node) -> int32_t {
        if constexpr (std::is_same_v<std::decay_t<decltype(node)>, std::monostate>) return 0;
        else return node->length;
    }, v);
}
int32_t Stmt::line() const {
    return std::visit([](const auto& node) -> int32_t {
        if constexpr (std::is_same_v<std::decay_t<decltype(node)>, std::monostate>) return 0;
        else return node->line;
    }, v);
}
int32_t Stmt::column() const {
    return std::visit([](const auto& node) -> int32_t {
        if constexpr (std::is_same_v<std::decay_t<decltype(node)>, std::monostate>) return 0;
        else return node->column;
    }, v);
}
int32_t Stmt::length() const {
    return std::visit([](const auto& node) -> int32_t {
        if constexpr (std::is_same_v<std::decay_t<decltype(node)>, std::monostate>) return 0;
        else return node->length;
    }, v);
}

static void dumpExprIntLiteral(Dumper& d, const ExprIntLiteral& v, int ind) {
    d.open("Expr.IntLiteral");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("text", ind);
    dumpValue(d, v.text, ind + 2);
    d.field("magnitude", ind);
    dumpValue(d, v.magnitude, ind + 2);
    d.close(ind);
}
static void dumpExprDoubleLiteral(Dumper& d, const ExprDoubleLiteral& v, int ind) {
    d.open("Expr.DoubleLiteral");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("text", ind);
    dumpValue(d, v.text, ind + 2);
    d.close(ind);
}
static void dumpExprCharLiteral(Dumper& d, const ExprCharLiteral& v, int ind) {
    d.open("Expr.CharLiteral");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprStringLiteral(Dumper& d, const ExprStringLiteral& v, int ind) {
    d.open("Expr.StringLiteral");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprBoolLiteral(Dumper& d, const ExprBoolLiteral& v, int ind) {
    d.open("Expr.BoolLiteral");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprIdentifier(Dumper& d, const ExprIdentifier& v, int ind) {
    d.open("Expr.Identifier");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprBinary(Dumper& d, const ExprBinary& v, int ind) {
    d.open("Expr.Binary");
    d.field("left", ind);
    dumpValue(d, v.left, ind + 2);
    d.field("op", ind);
    dumpValue(d, v.op, ind + 2);
    d.field("right", ind);
    dumpValue(d, v.right, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprUnary(Dumper& d, const ExprUnary& v, int ind) {
    d.open("Expr.Unary");
    d.field("op", ind);
    dumpValue(d, v.op, ind + 2);
    d.field("operand", ind);
    dumpValue(d, v.operand, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprIncDec(Dumper& d, const ExprIncDec& v, int ind) {
    d.open("Expr.IncDec");
    d.field("target", ind);
    dumpValue(d, v.target, ind + 2);
    d.field("op", ind);
    dumpValue(d, v.op, ind + 2);
    d.field("prefix", ind);
    dumpValue(d, v.prefix, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprCall(Dumper& d, const ExprCall& v, int ind) {
    d.open("Expr.Call");
    d.field("callee", ind);
    dumpValue(d, v.callee, ind + 2);
    d.field("args", ind);
    dumpValue(d, v.args, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("typeArgs", ind);
    dumpValue(d, v.typeArgs, ind + 2);
    d.field("receiver", ind);
    dumpValue(d, v.receiver, ind + 2);
    d.field("inferredTypeArgs", ind);
    dumpValue(d, v.inferredTypeArgs, ind + 2);
    d.close(ind);
}
static void dumpExprGrouping(Dumper& d, const ExprGrouping& v, int ind) {
    d.open("Expr.Grouping");
    d.field("expr", ind);
    dumpValue(d, v.expr, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprMapEntryArg(Dumper& d, const ExprMapEntryArg& v, int ind) {
    d.open("Expr.MapEntryArg");
    d.field("key", ind);
    dumpValue(d, v.key, ind + 2);
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprUpperScopeAccess(Dumper& d, const ExprUpperScopeAccess& v, int ind) {
    d.open("Expr.UpperScopeAccess");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("depth", ind);
    dumpValue(d, v.depth, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprRange(Dumper& d, const ExprRange& v, int ind) {
    d.open("Expr.Range");
    d.field("from", ind);
    dumpValue(d, v.from, ind + 2);
    d.field("to", ind);
    dumpValue(d, v.to, ind + 2);
    d.field("inclusive", ind);
    dumpValue(d, v.inclusive, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("descending", ind);
    dumpValue(d, v.descending, ind + 2);
    d.close(ind);
}
static void dumpExprArrayLiteral(Dumper& d, const ExprArrayLiteral& v, int ind) {
    d.open("Expr.ArrayLiteral");
    d.field("elements", ind);
    dumpValue(d, v.elements, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("contextualType", ind);
    dumpValue(d, v.contextualType, ind + 2);
    d.field("literalFactory", ind);
    dumpValue(d, v.literalFactory, ind + 2);
    d.close(ind);
}
static void dumpExprSetLiteral(Dumper& d, const ExprSetLiteral& v, int ind) {
    d.open("Expr.SetLiteral");
    d.field("elements", ind);
    dumpValue(d, v.elements, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprIndex(Dumper& d, const ExprIndex& v, int ind) {
    d.open("Expr.Index");
    d.field("target", ind);
    dumpValue(d, v.target, ind + 2);
    d.field("index", ind);
    dumpValue(d, v.index, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprInferredMember(Dumper& d, const ExprInferredMember& v, int ind) {
    d.open("Expr.InferredMember");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("ctorArgs", ind);
    dumpValue(d, v.ctorArgs, ind + 2);
    d.field("scopeCall", ind);
    dumpValue(d, v.scopeCall, ind + 2);
    d.field("scopeRead", ind);
    dumpValue(d, v.scopeRead, ind + 2);
    d.field("site", ind);
    dumpValue(d, v.site, ind + 2);
    d.close(ind);
}
static void dumpExprMember(Dumper& d, const ExprMember& v, int ind) {
    d.open("Expr.Member");
    d.field("target", ind);
    dumpValue(d, v.target, ind + 2);
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("nameExpr", ind);
    dumpValue(d, v.nameExpr, ind + 2);
    d.close(ind);
}
static void dumpExprMethodCall(Dumper& d, const ExprMethodCall& v, int ind) {
    d.open("Expr.MethodCall");
    d.field("target", ind);
    dumpValue(d, v.target, ind + 2);
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("args", ind);
    dumpValue(d, v.args, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("typeArgs", ind);
    dumpValue(d, v.typeArgs, ind + 2);
    d.close(ind);
}
static void dumpExprStringTemplate(Dumper& d, const ExprStringTemplate& v, int ind) {
    d.open("Expr.StringTemplate");
    d.field("parts", ind);
    dumpValue(d, v.parts, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprTupleLit(Dumper& d, const ExprTupleLit& v, int ind) {
    d.open("Expr.TupleLit");
    d.field("elements", ind);
    dumpValue(d, v.elements, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("contextualType", ind);
    dumpValue(d, v.contextualType, ind + 2);
    d.close(ind);
}
static void dumpExprVariantLit(Dumper& d, const ExprVariantLit& v, int ind) {
    d.open("Expr.VariantLit");
    d.field("elements", ind);
    dumpValue(d, v.elements, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprTupleAccess(Dumper& d, const ExprTupleAccess& v, int ind) {
    d.open("Expr.TupleAccess");
    d.field("target", ind);
    dumpValue(d, v.target, ind + 2);
    d.field("index", ind);
    dumpValue(d, v.index, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprCatchExpr(Dumper& d, const ExprCatchExpr& v, int ind) {
    d.open("Expr.CatchExpr");
    d.field("expr", ind);
    dumpValue(d, v.expr, ind + 2);
    d.field("fallback", ind);
    dumpValue(d, v.fallback, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprTryPropagate(Dumper& d, const ExprTryPropagate& v, int ind) {
    d.open("Expr.TryPropagate");
    d.field("expr", ind);
    dumpValue(d, v.expr, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprIfExpr(Dumper& d, const ExprIfExpr& v, int ind) {
    d.open("Expr.IfExpr");
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.field("thenExpr", ind);
    dumpValue(d, v.thenExpr, ind + 2);
    d.field("elseExpr", ind);
    dumpValue(d, v.elseExpr, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprSeal(Dumper& d, const ExprSeal& v, int ind) {
    d.open("Expr.Seal");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprLambda(Dumper& d, const ExprLambda& v, int ind) {
    d.open("Expr.Lambda");
    d.field("params", ind);
    dumpValue(d, v.params, ind + 2);
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("variadic", ind);
    dumpValue(d, v.variadic, ind + 2);
    d.field("receivers", ind);
    dumpValue(d, v.receivers, ind + 2);
    d.field("kind", ind);
    dumpValue(d, v.kind, ind + 2);
    d.field("paramsWritten", ind);
    dumpValue(d, v.paramsWritten, ind + 2);
    d.field("captures", ind);
    dumpValue(d, v.captures, ind + 2);
    d.field("captureDefault", ind);
    dumpValue(d, v.captureDefault, ind + 2);
    d.field("captureExclusions", ind);
    dumpValue(d, v.captureExclusions, ind + 2);
    d.field("typeParams", ind);
    dumpValue(d, v.typeParams, ind + 2);
    d.field("variadicTypeParam", ind);
    dumpValue(d, v.variadicTypeParam, ind + 2);
    d.close(ind);
}
static void dumpExprInlineForArgs(Dumper& d, const ExprInlineForArgs& v, int ind) {
    d.open("Expr.InlineForArgs");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("iterable", ind);
    dumpValue(d, v.iterable, ind + 2);
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprNamedArg(Dumper& d, const ExprNamedArg& v, int ind) {
    d.open("Expr.NamedArg");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprNullCoalesce(Dumper& d, const ExprNullCoalesce& v, int ind) {
    d.open("Expr.NullCoalesce");
    d.field("left", ind);
    dumpValue(d, v.left, ind + 2);
    d.field("right", ind);
    dumpValue(d, v.right, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprSafeMember(Dumper& d, const ExprSafeMember& v, int ind) {
    d.open("Expr.SafeMember");
    d.field("target", ind);
    dumpValue(d, v.target, ind + 2);
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprCast(Dumper& d, const ExprCast& v, int ind) {
    d.open("Expr.Cast");
    d.field("expr", ind);
    dumpValue(d, v.expr, ind + 2);
    d.field("targetType", ind);
    dumpValue(d, v.targetType, ind + 2);
    d.field("kind", ind);
    dumpValue(d, v.kind, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprIsCheck(Dumper& d, const ExprIsCheck& v, int ind) {
    d.open("Expr.IsCheck");
    d.field("expr", ind);
    dumpValue(d, v.expr, ind + 2);
    d.field("typeName", ind);
    dumpValue(d, v.typeName, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("typeArgs", ind);
    dumpValue(d, v.typeArgs, ind + 2);
    d.close(ind);
}
static void dumpExprInCheck(Dumper& d, const ExprInCheck& v, int ind) {
    d.open("Expr.InCheck");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("collection", ind);
    dumpValue(d, v.collection, ind + 2);
    d.field("negated", ind);
    dumpValue(d, v.negated, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprMapLit(Dumper& d, const ExprMapLit& v, int ind) {
    d.open("Expr.MapLit");
    d.field("entries", ind);
    dumpValue(d, v.entries, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("contextualType", ind);
    dumpValue(d, v.contextualType, ind + 2);
    d.field("literalFactory", ind);
    dumpValue(d, v.literalFactory, ind + 2);
    d.close(ind);
}
static void dumpExprAlloc(Dumper& d, const ExprAlloc& v, int ind) {
    d.open("Expr.Alloc");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("mutable", ind);
    dumpValue(d, v.mutable_, ind + 2);
    d.close(ind);
}
static void dumpExprDeref(Dumper& d, const ExprDeref& v, int ind) {
    d.open("Expr.Deref");
    d.field("target", ind);
    dumpValue(d, v.target, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprIsolated(Dumper& d, const ExprIsolated& v, int ind) {
    d.open("Expr.Isolated");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("op", ind);
    dumpValue(d, v.op, ind + 2);
    d.close(ind);
}
static void dumpExprAwait(Dumper& d, const ExprAwait& v, int ind) {
    d.open("Expr.Await");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprInject(Dumper& d, const ExprInject& v, int ind) {
    d.open("Expr.Inject");
    d.field("typeName", ind);
    dumpValue(d, v.typeName, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprSpread(Dumper& d, const ExprSpread& v, int ind) {
    d.open("Expr.Spread");
    d.field("array", ind);
    dumpValue(d, v.array, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprMetaInvoke(Dumper& d, const ExprMetaInvoke& v, int ind) {
    d.open("Expr.MetaInvoke");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("args", ind);
    dumpValue(d, v.args, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpExprSlice(Dumper& d, const ExprSlice& v, int ind) {
    d.open("Expr.Slice");
    d.field("target", ind);
    dumpValue(d, v.target, ind + 2);
    d.field("start", ind);
    dumpValue(d, v.start, ind + 2);
    d.field("stop", ind);
    dumpValue(d, v.stop, ind + 2);
    d.field("step", ind);
    dumpValue(d, v.step, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const Expr& v, int ind) {
    switch (v.kind()) {
        case ExprKind::None: d.nil(); return;
        case ExprKind::IntLiteral: dumpExprIntLiteral(d, cast<ExprIntLiteral>(v), ind); return;
        case ExprKind::DoubleLiteral: dumpExprDoubleLiteral(d, cast<ExprDoubleLiteral>(v), ind); return;
        case ExprKind::CharLiteral: dumpExprCharLiteral(d, cast<ExprCharLiteral>(v), ind); return;
        case ExprKind::StringLiteral: dumpExprStringLiteral(d, cast<ExprStringLiteral>(v), ind); return;
        case ExprKind::BoolLiteral: dumpExprBoolLiteral(d, cast<ExprBoolLiteral>(v), ind); return;
        case ExprKind::Identifier: dumpExprIdentifier(d, cast<ExprIdentifier>(v), ind); return;
        case ExprKind::Binary: dumpExprBinary(d, cast<ExprBinary>(v), ind); return;
        case ExprKind::Unary: dumpExprUnary(d, cast<ExprUnary>(v), ind); return;
        case ExprKind::IncDec: dumpExprIncDec(d, cast<ExprIncDec>(v), ind); return;
        case ExprKind::Call: dumpExprCall(d, cast<ExprCall>(v), ind); return;
        case ExprKind::Grouping: dumpExprGrouping(d, cast<ExprGrouping>(v), ind); return;
        case ExprKind::MapEntryArg: dumpExprMapEntryArg(d, cast<ExprMapEntryArg>(v), ind); return;
        case ExprKind::UpperScopeAccess: dumpExprUpperScopeAccess(d, cast<ExprUpperScopeAccess>(v), ind); return;
        case ExprKind::Range: dumpExprRange(d, cast<ExprRange>(v), ind); return;
        case ExprKind::ArrayLiteral: dumpExprArrayLiteral(d, cast<ExprArrayLiteral>(v), ind); return;
        case ExprKind::SetLiteral: dumpExprSetLiteral(d, cast<ExprSetLiteral>(v), ind); return;
        case ExprKind::Index: dumpExprIndex(d, cast<ExprIndex>(v), ind); return;
        case ExprKind::InferredMember: dumpExprInferredMember(d, cast<ExprInferredMember>(v), ind); return;
        case ExprKind::Member: dumpExprMember(d, cast<ExprMember>(v), ind); return;
        case ExprKind::MethodCall: dumpExprMethodCall(d, cast<ExprMethodCall>(v), ind); return;
        case ExprKind::StringTemplate: dumpExprStringTemplate(d, cast<ExprStringTemplate>(v), ind); return;
        case ExprKind::TupleLit: dumpExprTupleLit(d, cast<ExprTupleLit>(v), ind); return;
        case ExprKind::VariantLit: dumpExprVariantLit(d, cast<ExprVariantLit>(v), ind); return;
        case ExprKind::TupleAccess: dumpExprTupleAccess(d, cast<ExprTupleAccess>(v), ind); return;
        case ExprKind::CatchExpr: dumpExprCatchExpr(d, cast<ExprCatchExpr>(v), ind); return;
        case ExprKind::TryPropagate: dumpExprTryPropagate(d, cast<ExprTryPropagate>(v), ind); return;
        case ExprKind::IfExpr: dumpExprIfExpr(d, cast<ExprIfExpr>(v), ind); return;
        case ExprKind::Seal: dumpExprSeal(d, cast<ExprSeal>(v), ind); return;
        case ExprKind::Lambda: dumpExprLambda(d, cast<ExprLambda>(v), ind); return;
        case ExprKind::InlineForArgs: dumpExprInlineForArgs(d, cast<ExprInlineForArgs>(v), ind); return;
        case ExprKind::NamedArg: dumpExprNamedArg(d, cast<ExprNamedArg>(v), ind); return;
        case ExprKind::NullLiteral: d.raw("Expr.NullLiteral"); return;
        case ExprKind::NullCoalesce: dumpExprNullCoalesce(d, cast<ExprNullCoalesce>(v), ind); return;
        case ExprKind::SafeMember: dumpExprSafeMember(d, cast<ExprSafeMember>(v), ind); return;
        case ExprKind::Cast: dumpExprCast(d, cast<ExprCast>(v), ind); return;
        case ExprKind::IsCheck: dumpExprIsCheck(d, cast<ExprIsCheck>(v), ind); return;
        case ExprKind::InCheck: dumpExprInCheck(d, cast<ExprInCheck>(v), ind); return;
        case ExprKind::MapLit: dumpExprMapLit(d, cast<ExprMapLit>(v), ind); return;
        case ExprKind::Alloc: dumpExprAlloc(d, cast<ExprAlloc>(v), ind); return;
        case ExprKind::Deref: dumpExprDeref(d, cast<ExprDeref>(v), ind); return;
        case ExprKind::Isolated: dumpExprIsolated(d, cast<ExprIsolated>(v), ind); return;
        case ExprKind::Await: dumpExprAwait(d, cast<ExprAwait>(v), ind); return;
        case ExprKind::Inject: dumpExprInject(d, cast<ExprInject>(v), ind); return;
        case ExprKind::Spread: dumpExprSpread(d, cast<ExprSpread>(v), ind); return;
        case ExprKind::MetaInvoke: dumpExprMetaInvoke(d, cast<ExprMetaInvoke>(v), ind); return;
        case ExprKind::Slice: dumpExprSlice(d, cast<ExprSlice>(v), ind); return;
    }
}
static void dumpStmtImport(Dumper& d, const StmtImport& v, int ind) {
    d.open("Stmt.Import");
    d.field("use", ind);
    dumpValue(d, v.use, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtExchange(Dumper& d, const StmtExchange& v, int ind) {
    d.open("Stmt.Exchange");
    d.field("left", ind);
    dumpValue(d, v.left, ind + 2);
    d.field("right", ind);
    dumpValue(d, v.right, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtVarDecl(Dumper& d, const StmtVarDecl& v, int ind) {
    d.open("Stmt.VarDecl");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("valueMutable", ind);
    dumpValue(d, v.valueMutable, ind + 2);
    d.field("lazy", ind);
    dumpValue(d, v.lazy, ind + 2);
    d.close(ind);
}
static void dumpStmtFinDecl(Dumper& d, const StmtFinDecl& v, int ind) {
    d.open("Stmt.FinDecl");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("lazy", ind);
    dumpValue(d, v.lazy, ind + 2);
    d.field("synthesized", ind);
    dumpValue(d, v.synthesized, ind + 2);
    d.close(ind);
}
static void dumpStmtLetDecl(Dumper& d, const StmtLetDecl& v, int ind) {
    d.open("Stmt.LetDecl");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("lazy", ind);
    dumpValue(d, v.lazy, ind + 2);
    d.close(ind);
}
static void dumpStmtInlineFin(Dumper& d, const StmtInlineFin& v, int ind) {
    d.open("Stmt.InlineFin");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtDeepInlineBlock(Dumper& d, const StmtDeepInlineBlock& v, int ind) {
    d.open("Stmt.DeepInlineBlock");
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtNoInline(Dumper& d, const StmtNoInline& v, int ind) {
    d.open("Stmt.NoInline");
    d.field("stmt", ind);
    dumpValue(d, v.stmt, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtInlineBlock(Dumper& d, const StmtInlineBlock& v, int ind) {
    d.open("Stmt.InlineBlock");
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtInlineLet(Dumper& d, const StmtInlineLet& v, int ind) {
    d.open("Stmt.InlineLet");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtInlineVar(Dumper& d, const StmtInlineVar& v, int ind) {
    d.open("Stmt.InlineVar");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("valueMutable", ind);
    dumpValue(d, v.valueMutable, ind + 2);
    d.close(ind);
}
static void dumpStmtInlineAssignment(Dumper& d, const StmtInlineAssignment& v, int ind) {
    d.open("Stmt.InlineAssignment");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtAssignment(Dumper& d, const StmtAssignment& v, int ind) {
    d.open("Stmt.Assignment");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("compoundOp", ind);
    dumpValue(d, v.compoundOp, ind + 2);
    d.close(ind);
}
static void dumpStmtReturn(Dumper& d, const StmtReturn& v, int ind) {
    d.open("Stmt.Return");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("implicit", ind);
    dumpValue(d, v.implicit, ind + 2);
    d.close(ind);
}
static void dumpStmtExprStmt(Dumper& d, const StmtExprStmt& v, int ind) {
    d.open("Stmt.ExprStmt");
    d.field("expr", ind);
    dumpValue(d, v.expr, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtScope(Dumper& d, const StmtScope& v, int ind) {
    d.open("Stmt.Scope");
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("unsafe", ind);
    dumpValue(d, v.unsafe, ind + 2);
    d.field("shared", ind);
    dumpValue(d, v.shared, ind + 2);
    d.close(ind);
}
static void dumpStmtIf(Dumper& d, const StmtIf& v, int ind) {
    d.open("Stmt.If");
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.field("thenBranch", ind);
    dumpValue(d, v.thenBranch, ind + 2);
    d.field("elseBranch", ind);
    dumpValue(d, v.elseBranch, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtInlineIf(Dumper& d, const StmtInlineIf& v, int ind) {
    d.open("Stmt.InlineIf");
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.field("thenBranch", ind);
    dumpValue(d, v.thenBranch, ind + 2);
    d.field("elseBranch", ind);
    dumpValue(d, v.elseBranch, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtDeepInlineIf(Dumper& d, const StmtDeepInlineIf& v, int ind) {
    d.open("Stmt.DeepInlineIf");
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.field("thenBranch", ind);
    dumpValue(d, v.thenBranch, ind + 2);
    d.field("elseBranch", ind);
    dumpValue(d, v.elseBranch, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtAssert(Dumper& d, const StmtAssert& v, int ind) {
    d.open("Stmt.Assert");
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.field("message", ind);
    dumpValue(d, v.message, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtTrace(Dumper& d, const StmtTrace& v, int ind) {
    d.open("Stmt.Trace");
    d.field("message", ind);
    dumpValue(d, v.message, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("level", ind);
    dumpValue(d, v.level, ind + 2);
    d.field("liftBody", ind);
    dumpValue(d, v.liftBody, ind + 2);
    d.field("explicitLevel", ind);
    dumpValue(d, v.explicitLevel, ind + 2);
    d.close(ind);
}
static void dumpStmtInlineAssert(Dumper& d, const StmtInlineAssert& v, int ind) {
    d.open("Stmt.InlineAssert");
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.field("message", ind);
    dumpValue(d, v.message, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtInlineTrace(Dumper& d, const StmtInlineTrace& v, int ind) {
    d.open("Stmt.InlineTrace");
    d.field("message", ind);
    dumpValue(d, v.message, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("level", ind);
    dumpValue(d, v.level, ind + 2);
    d.close(ind);
}
static void dumpStmtWhile(Dumper& d, const StmtWhile& v, int ind) {
    d.open("Stmt.While");
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("label", ind);
    dumpValue(d, v.label, ind + 2);
    d.close(ind);
}
static void dumpStmtFor(Dumper& d, const StmtFor& v, int ind) {
    d.open("Stmt.For");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("iterable", ind);
    dumpValue(d, v.iterable, ind + 2);
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("step", ind);
    dumpValue(d, v.step, ind + 2);
    d.field("label", ind);
    dumpValue(d, v.label, ind + 2);
    d.field("declaredType", ind);
    dumpValue(d, v.declaredType, ind + 2);
    d.field("indexName", ind);
    dumpValue(d, v.indexName, ind + 2);
    d.close(ind);
}
static void dumpStmtLoop(Dumper& d, const StmtLoop& v, int ind) {
    d.open("Stmt.Loop");
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("label", ind);
    dumpValue(d, v.label, ind + 2);
    d.field("iterable", ind);
    dumpValue(d, v.iterable, ind + 2);
    d.field("everySeconds", ind);
    dumpValue(d, v.everySeconds, ind + 2);
    d.close(ind);
}
static void dumpStmtInlineFor(Dumper& d, const StmtInlineFor& v, int ind) {
    d.open("Stmt.InlineFor");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("iterable", ind);
    dumpValue(d, v.iterable, ind + 2);
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("indexName", ind);
    dumpValue(d, v.indexName, ind + 2);
    d.close(ind);
}
static void dumpStmtBreak(Dumper& d, const StmtBreak& v, int ind) {
    d.open("Stmt.Break");
    d.field("label", ind);
    dumpValue(d, v.label, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.close(ind);
}
static void dumpStmtContinue(Dumper& d, const StmtContinue& v, int ind) {
    d.open("Stmt.Continue");
    d.field("label", ind);
    dumpValue(d, v.label, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtIndexAssign(Dumper& d, const StmtIndexAssign& v, int ind) {
    d.open("Stmt.IndexAssign");
    d.field("target", ind);
    dumpValue(d, v.target, ind + 2);
    d.field("index", ind);
    dumpValue(d, v.index, ind + 2);
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("compoundOp", ind);
    dumpValue(d, v.compoundOp, ind + 2);
    d.close(ind);
}
static void dumpStmtMemberAssign(Dumper& d, const StmtMemberAssign& v, int ind) {
    d.open("Stmt.MemberAssign");
    d.field("target", ind);
    dumpValue(d, v.target, ind + 2);
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("compoundOp", ind);
    dumpValue(d, v.compoundOp, ind + 2);
    d.field("nameExpr", ind);
    dumpValue(d, v.nameExpr, ind + 2);
    d.close(ind);
}
static void dumpStmtWhen(Dumper& d, const StmtWhen& v, int ind) {
    d.open("Stmt.When");
    d.field("scrutinee", ind);
    dumpValue(d, v.scrutinee, ind + 2);
    d.field("branches", ind);
    dumpValue(d, v.branches, ind + 2);
    d.field("elseBranch", ind);
    dumpValue(d, v.elseBranch, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtThrow(Dumper& d, const StmtThrow& v, int ind) {
    d.open("Stmt.Throw");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("returned", ind);
    dumpValue(d, v.returned, ind + 2);
    d.close(ind);
}
static void dumpStmtPanic(Dumper& d, const StmtPanic& v, int ind) {
    d.open("Stmt.Panic");
    d.field("message", ind);
    dumpValue(d, v.message, ind + 2);
    d.field("inlinePanic", ind);
    dumpValue(d, v.inlinePanic, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtDerefAssign(Dumper& d, const StmtDerefAssign& v, int ind) {
    d.open("Stmt.DerefAssign");
    d.field("target", ind);
    dumpValue(d, v.target, ind + 2);
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtYield(Dumper& d, const StmtYield& v, int ind) {
    d.open("Stmt.Yield");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtTry(Dumper& d, const StmtTry& v, int ind) {
    d.open("Stmt.Try");
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("catchName", ind);
    dumpValue(d, v.catchName, ind + 2);
    d.field("catchBody", ind);
    dumpValue(d, v.catchBody, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpStmtDefer(Dumper& d, const StmtDefer& v, int ind) {
    d.open("Stmt.Defer");
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("onFail", ind);
    dumpValue(d, v.onFail, ind + 2);
    d.field("suppress", ind);
    dumpValue(d, v.suppress, ind + 2);
    d.close(ind);
}
static void dumpStmtRemDecl(Dumper& d, const StmtRemDecl& v, int ind) {
    d.open("Stmt.RemDecl");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("kind", ind);
    dumpValue(d, v.kind, ind + 2);
    d.field("binding", ind);
    dumpValue(d, v.binding, ind + 2);
    d.close(ind);
}
static void dumpStmtEffect(Dumper& d, const StmtEffect& v, int ind) {
    d.open("Stmt.Effect");
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("dependencies", ind);
    dumpValue(d, v.dependencies, ind + 2);
    d.field("deferred", ind);
    dumpValue(d, v.deferred, ind + 2);
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.close(ind);
}
static void dumpStmtUsingContext(Dumper& d, const StmtUsingContext& v, int ind) {
    d.open("Stmt.UsingContext");
    d.field("values", ind);
    dumpValue(d, v.values, ind + 2);
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const Stmt& v, int ind) {
    switch (v.kind()) {
        case StmtKind::None: d.nil(); return;
        case StmtKind::Import: dumpStmtImport(d, cast<StmtImport>(v), ind); return;
        case StmtKind::Exchange: dumpStmtExchange(d, cast<StmtExchange>(v), ind); return;
        case StmtKind::VarDecl: dumpStmtVarDecl(d, cast<StmtVarDecl>(v), ind); return;
        case StmtKind::FinDecl: dumpStmtFinDecl(d, cast<StmtFinDecl>(v), ind); return;
        case StmtKind::LetDecl: dumpStmtLetDecl(d, cast<StmtLetDecl>(v), ind); return;
        case StmtKind::InlineFin: dumpStmtInlineFin(d, cast<StmtInlineFin>(v), ind); return;
        case StmtKind::DeepInlineBlock: dumpStmtDeepInlineBlock(d, cast<StmtDeepInlineBlock>(v), ind); return;
        case StmtKind::NoInline: dumpStmtNoInline(d, cast<StmtNoInline>(v), ind); return;
        case StmtKind::InlineBlock: dumpStmtInlineBlock(d, cast<StmtInlineBlock>(v), ind); return;
        case StmtKind::InlineLet: dumpStmtInlineLet(d, cast<StmtInlineLet>(v), ind); return;
        case StmtKind::InlineVar: dumpStmtInlineVar(d, cast<StmtInlineVar>(v), ind); return;
        case StmtKind::InlineAssignment: dumpStmtInlineAssignment(d, cast<StmtInlineAssignment>(v), ind); return;
        case StmtKind::Assignment: dumpStmtAssignment(d, cast<StmtAssignment>(v), ind); return;
        case StmtKind::Return: dumpStmtReturn(d, cast<StmtReturn>(v), ind); return;
        case StmtKind::ExprStmt: dumpStmtExprStmt(d, cast<StmtExprStmt>(v), ind); return;
        case StmtKind::Scope: dumpStmtScope(d, cast<StmtScope>(v), ind); return;
        case StmtKind::If: dumpStmtIf(d, cast<StmtIf>(v), ind); return;
        case StmtKind::InlineIf: dumpStmtInlineIf(d, cast<StmtInlineIf>(v), ind); return;
        case StmtKind::DeepInlineIf: dumpStmtDeepInlineIf(d, cast<StmtDeepInlineIf>(v), ind); return;
        case StmtKind::Assert: dumpStmtAssert(d, cast<StmtAssert>(v), ind); return;
        case StmtKind::Trace: dumpStmtTrace(d, cast<StmtTrace>(v), ind); return;
        case StmtKind::InlineAssert: dumpStmtInlineAssert(d, cast<StmtInlineAssert>(v), ind); return;
        case StmtKind::InlineTrace: dumpStmtInlineTrace(d, cast<StmtInlineTrace>(v), ind); return;
        case StmtKind::While: dumpStmtWhile(d, cast<StmtWhile>(v), ind); return;
        case StmtKind::For: dumpStmtFor(d, cast<StmtFor>(v), ind); return;
        case StmtKind::Loop: dumpStmtLoop(d, cast<StmtLoop>(v), ind); return;
        case StmtKind::InlineFor: dumpStmtInlineFor(d, cast<StmtInlineFor>(v), ind); return;
        case StmtKind::Break: dumpStmtBreak(d, cast<StmtBreak>(v), ind); return;
        case StmtKind::Continue: dumpStmtContinue(d, cast<StmtContinue>(v), ind); return;
        case StmtKind::IndexAssign: dumpStmtIndexAssign(d, cast<StmtIndexAssign>(v), ind); return;
        case StmtKind::MemberAssign: dumpStmtMemberAssign(d, cast<StmtMemberAssign>(v), ind); return;
        case StmtKind::When: dumpStmtWhen(d, cast<StmtWhen>(v), ind); return;
        case StmtKind::Throw: dumpStmtThrow(d, cast<StmtThrow>(v), ind); return;
        case StmtKind::Panic: dumpStmtPanic(d, cast<StmtPanic>(v), ind); return;
        case StmtKind::DerefAssign: dumpStmtDerefAssign(d, cast<StmtDerefAssign>(v), ind); return;
        case StmtKind::Yield: dumpStmtYield(d, cast<StmtYield>(v), ind); return;
        case StmtKind::Try: dumpStmtTry(d, cast<StmtTry>(v), ind); return;
        case StmtKind::Defer: dumpStmtDefer(d, cast<StmtDefer>(v), ind); return;
        case StmtKind::RemDecl: dumpStmtRemDecl(d, cast<StmtRemDecl>(v), ind); return;
        case StmtKind::Effect: dumpStmtEffect(d, cast<StmtEffect>(v), ind); return;
        case StmtKind::UsingContext: dumpStmtUsingContext(d, cast<StmtUsingContext>(v), ind); return;
    }
}
static void dumpTypeRefNamed(Dumper& d, const TypeRefNamed& v, int ind) {
    d.open("TypeRef.Named");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("args", ind);
    dumpValue(d, v.args, ind + 2);
    d.field("variadic", ind);
    dumpValue(d, v.variadic, ind + 2);
    // Kotlin's TypeRef.Named.equals: a qualifier is access metadata, not identity.
    if (d.equalityKey) {
        d.close(ind);
        return;
    }
    d.field("qualifier", ind);
    dumpValue(d, v.qualifier, ind + 2);
    d.field("synthesized", ind);
    dumpValue(d, v.synthesized, ind + 2);
    d.field("valueArgs", ind);
    dumpValue(d, v.valueArgs, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.close(ind);
}
static void dumpTypeRefArray(Dumper& d, const TypeRefArray& v, int ind) {
    d.open("TypeRef.Array");
    d.field("element", ind);
    dumpValue(d, v.element, ind + 2);
    d.close(ind);
}
static void dumpTypeRefMap(Dumper& d, const TypeRefMap& v, int ind) {
    d.open("TypeRef.Map");
    d.field("key", ind);
    dumpValue(d, v.key, ind + 2);
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.close(ind);
}
static void dumpTypeRefSet(Dumper& d, const TypeRefSet& v, int ind) {
    d.open("TypeRef.Set");
    d.field("element", ind);
    dumpValue(d, v.element, ind + 2);
    d.close(ind);
}
static void dumpTypeRefFunction(Dumper& d, const TypeRefFunction& v, int ind) {
    d.open("TypeRef.Function");
    d.field("params", ind);
    dumpValue(d, v.params, ind + 2);
    d.field("ret", ind);
    dumpValue(d, v.ret, ind + 2);
    d.field("receivers", ind);
    dumpValue(d, v.receivers, ind + 2);
    d.field("kind", ind);
    dumpValue(d, v.kind, ind + 2);
    d.field("isEscaping", ind);
    dumpValue(d, v.isEscaping, ind + 2);
    d.field("isInline", ind);
    dumpValue(d, v.isInline, ind + 2);
    d.field("isForeign", ind);
    dumpValue(d, v.isForeign, ind + 2);
    d.close(ind);
}
static void dumpTypeRefTuple(Dumper& d, const TypeRefTuple& v, int ind) {
    d.open("TypeRef.Tuple");
    d.field("elements", ind);
    dumpValue(d, v.elements, ind + 2);
    d.close(ind);
}
static void dumpTypeRefNullable(Dumper& d, const TypeRefNullable& v, int ind) {
    d.open("TypeRef.Nullable");
    d.field("inner", ind);
    dumpValue(d, v.inner, ind + 2);
    d.close(ind);
}
static void dumpTypeRefFailable(Dumper& d, const TypeRefFailable& v, int ind) {
    d.open("TypeRef.Failable");
    d.field("ok", ind);
    dumpValue(d, v.ok, ind + 2);
    d.field("errSets", ind);
    dumpValue(d, v.errSets, ind + 2);
    d.close(ind);
}
static void dumpTypeRefPointer(Dumper& d, const TypeRefPointer& v, int ind) {
    d.open("TypeRef.Pointer");
    d.field("inner", ind);
    dumpValue(d, v.inner, ind + 2);
    d.field("mutable", ind);
    dumpValue(d, v.mutable_, ind + 2);
    d.close(ind);
}
static void dumpTypeRefConst(Dumper& d, const TypeRefConst& v, int ind) {
    d.open("TypeRef.Const");
    // Kotlin's TypeRef.Const.equals: by label when there is one, else by value.
    if (d.equalityKey) {
        d.field(v.label ? "label" : "value", ind);
        if (v.label) dumpValue(d, *v.label, ind + 2);
        else dumpValue(d, v.value, ind + 2);
        d.close(ind);
        return;
    }
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("label", ind);
    dumpValue(d, v.label, ind + 2);
    d.close(ind);
}
static void dumpTypeRefReference(Dumper& d, const TypeRefReference& v, int ind) {
    d.open("TypeRef.Reference");
    d.field("kind", ind);
    dumpValue(d, v.kind, ind + 2);
    d.field("inner", ind);
    dumpValue(d, v.inner, ind + 2);
    d.field("origins", ind);
    dumpValue(d, v.origins, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const TypeRef& v, int ind) {
    switch (v.kind()) {
        case TypeRefKind::None: d.nil(); return;
        case TypeRefKind::Named: dumpTypeRefNamed(d, cast<TypeRefNamed>(v), ind); return;
        case TypeRefKind::Array: dumpTypeRefArray(d, cast<TypeRefArray>(v), ind); return;
        case TypeRefKind::Map: dumpTypeRefMap(d, cast<TypeRefMap>(v), ind); return;
        case TypeRefKind::Set: dumpTypeRefSet(d, cast<TypeRefSet>(v), ind); return;
        case TypeRefKind::Function: dumpTypeRefFunction(d, cast<TypeRefFunction>(v), ind); return;
        case TypeRefKind::Tuple: dumpTypeRefTuple(d, cast<TypeRefTuple>(v), ind); return;
        case TypeRefKind::Nullable: dumpTypeRefNullable(d, cast<TypeRefNullable>(v), ind); return;
        case TypeRefKind::Failable: dumpTypeRefFailable(d, cast<TypeRefFailable>(v), ind); return;
        case TypeRefKind::Pointer: dumpTypeRefPointer(d, cast<TypeRefPointer>(v), ind); return;
        case TypeRefKind::Const: dumpTypeRefConst(d, cast<TypeRefConst>(v), ind); return;
        case TypeRefKind::Reference: dumpTypeRefReference(d, cast<TypeRefReference>(v), ind); return;
    }
}
static void dumpTopLevelFunc(Dumper& d, const TopLevelFunc& v, int ind) {
    d.open("TopLevel.Func");
    d.field("decl", ind);
    dumpValue(d, v.decl, ind + 2);
    d.close(ind);
}
static void dumpTopLevelVarDecl(Dumper& d, const TopLevelVarDecl& v, int ind) {
    d.open("TopLevel.VarDecl");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("threadlocal", ind);
    dumpValue(d, v.threadlocal, ind + 2);
    d.field("visibility", ind);
    dumpValue(d, v.visibility, ind + 2);
    d.field("valueMutable", ind);
    dumpValue(d, v.valueMutable, ind + 2);
    d.close(ind);
}
static void dumpTopLevelFinDecl(Dumper& d, const TopLevelFinDecl& v, int ind) {
    d.open("TopLevel.FinDecl");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("threadlocal", ind);
    dumpValue(d, v.threadlocal, ind + 2);
    d.field("visibility", ind);
    dumpValue(d, v.visibility, ind + 2);
    d.close(ind);
}
static void dumpTopLevelLetDecl(Dumper& d, const TopLevelLetDecl& v, int ind) {
    d.open("TopLevel.LetDecl");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("visibility", ind);
    dumpValue(d, v.visibility, ind + 2);
    d.close(ind);
}
static void dumpTopLevelInlineVar(Dumper& d, const TopLevelInlineVar& v, int ind) {
    d.open("TopLevel.InlineVar");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("valueMutable", ind);
    dumpValue(d, v.valueMutable, ind + 2);
    d.close(ind);
}
static void dumpTopLevelInlineFin(Dumper& d, const TopLevelInlineFin& v, int ind) {
    d.open("TopLevel.InlineFin");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.close(ind);
}
static void dumpTopLevelInlineLet(Dumper& d, const TopLevelInlineLet& v, int ind) {
    d.open("TopLevel.InlineLet");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.close(ind);
}
static void dumpTopLevelInlineAssignment(Dumper& d, const TopLevelInlineAssignment& v, int ind) {
    d.open("TopLevel.InlineAssignment");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.close(ind);
}
static void dumpTopLevelInlineIf(Dumper& d, const TopLevelInlineIf& v, int ind) {
    d.open("TopLevel.InlineIf");
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.field("thenBranch", ind);
    dumpValue(d, v.thenBranch, ind + 2);
    d.field("elseBranch", ind);
    dumpValue(d, v.elseBranch, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.close(ind);
}
static void dumpTopLevelInlineBlock(Dumper& d, const TopLevelInlineBlock& v, int ind) {
    d.open("TopLevel.InlineBlock");
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.close(ind);
}
static void dumpTopLevelDeepInlineBlock(Dumper& d, const TopLevelDeepInlineBlock& v, int ind) {
    d.open("TopLevel.DeepInlineBlock");
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.close(ind);
}
static void dumpTopLevelDeepInlineIf(Dumper& d, const TopLevelDeepInlineIf& v, int ind) {
    d.open("TopLevel.DeepInlineIf");
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.field("thenBranch", ind);
    dumpValue(d, v.thenBranch, ind + 2);
    d.field("elseBranch", ind);
    dumpValue(d, v.elseBranch, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.close(ind);
}
static void dumpTopLevelTest(Dumper& d, const TopLevelTest& v, int ind) {
    d.open("TopLevel.Test");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("method", ind);
    dumpValue(d, v.method, ind + 2);
    d.close(ind);
}
static void dumpTopLevelPack(Dumper& d, const TopLevelPack& v, int ind) {
    d.open("TopLevel.Pack");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("fields", ind);
    dumpValue(d, v.fields, ind + 2);
    d.field("typeParams", ind);
    dumpValue(d, v.typeParams, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("visibility", ind);
    dumpValue(d, v.visibility, ind + 2);
    d.field("variadicParam", ind);
    dumpValue(d, v.variadicParam, ind + 2);
    d.field("minVariadicLength", ind);
    dumpValue(d, v.minVariadicLength, ind + 2);
    d.field("constParams", ind);
    dumpValue(d, v.constParams, ind + 2);
    d.field("constDefaults", ind);
    dumpValue(d, v.constDefaults, ind + 2);
    d.field("constEnums", ind);
    dumpValue(d, v.constEnums, ind + 2);
    d.field("whereClause", ind);
    dumpValue(d, v.whereClause, ind + 2);
    d.field("fieldTemplate", ind);
    dumpValue(d, v.fieldTemplate, ind + 2);
    d.field("isBridge", ind);
    dumpValue(d, v.isBridge, ind + 2);
    d.field("literalKind", ind);
    dumpValue(d, v.literalKind, ind + 2);
    d.field("isUnion", ind);
    dumpValue(d, v.isUnion, ind + 2);
    d.field("declaredByImpl", ind);
    dumpValue(d, v.declaredByImpl, ind + 2);
    d.field("declaringModule", ind);
    dumpValue(d, v.declaringModule, ind + 2);
    d.field("foreignName", ind);
    dumpValue(d, v.foreignName, ind + 2);
    d.field("nameMacro", ind);
    dumpValue(d, v.nameMacro, ind + 2);
    d.field("localScope", ind);
    dumpValue(d, v.localScope, ind + 2);
    d.close(ind);
}
static void dumpTopLevelDeco(Dumper& d, const TopLevelDeco& v, int ind) {
    d.open("TopLevel.Deco");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("fields", ind);
    dumpValue(d, v.fields, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("targets", ind);
    dumpValue(d, v.targets, ind + 2);
    d.field("bindings", ind);
    dumpValue(d, v.bindings, ind + 2);
    d.field("isBridge", ind);
    dumpValue(d, v.isBridge, ind + 2);
    d.close(ind);
}
static void dumpTopLevelBridge(Dumper& d, const TopLevelBridge& v, int ind) {
    d.open("TopLevel.Bridge");
    d.field("target", ind);
    dumpValue(d, v.target, ind + 2);
    d.field("funcs", ind);
    dumpValue(d, v.funcs, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("values", ind);
    dumpValue(d, v.values, ind + 2);
    d.close(ind);
}
static void dumpTopLevelSolo(Dumper& d, const TopLevelSolo& v, int ind) {
    d.open("TopLevel.Solo");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("fields", ind);
    dumpValue(d, v.fields, ind + 2);
    d.field("methods", ind);
    dumpValue(d, v.methods, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("visibility", ind);
    dumpValue(d, v.visibility, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.close(ind);
}
static void dumpTopLevelGraph(Dumper& d, const TopLevelGraph& v, int ind) {
    d.open("TopLevel.Graph");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("registrations", ind);
    dumpValue(d, v.registrations, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("included", ind);
    dumpValue(d, v.included, ind + 2);
    d.close(ind);
}
static void dumpTopLevelUseImport(Dumper& d, const TopLevelUseImport& v, int ind) {
    d.open("TopLevel.UseImport");
    d.field("specs", ind);
    dumpValue(d, v.specs, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("exported", ind);
    dumpValue(d, v.exported, ind + 2);
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.close(ind);
}
static void dumpTopLevelEnum(Dumper& d, const TopLevelEnum& v, int ind) {
    d.open("TopLevel.Enum");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("variants", ind);
    dumpValue(d, v.variants, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("variantAnnotations", ind);
    dumpValue(d, v.variantAnnotations, ind + 2);
    d.field("typeParams", ind);
    dumpValue(d, v.typeParams, ind + 2);
    d.field("whereClause", ind);
    dumpValue(d, v.whereClause, ind + 2);
    d.field("valueType", ind);
    dumpValue(d, v.valueType, ind + 2);
    d.field("caseValues", ind);
    dumpValue(d, v.caseValues, ind + 2);
    d.close(ind);
}
static void dumpTopLevelFail(Dumper& d, const TopLevelFail& v, int ind) {
    d.open("TopLevel.Fail");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("variants", ind);
    dumpValue(d, v.variants, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("variantAnnotations", ind);
    dumpValue(d, v.variantAnnotations, ind + 2);
    d.field("variantPayloads", ind);
    dumpValue(d, v.variantPayloads, ind + 2);
    d.field("typeParams", ind);
    dumpValue(d, v.typeParams, ind + 2);
    d.field("whereClause", ind);
    dumpValue(d, v.whereClause, ind + 2);
    d.close(ind);
}
static void dumpTopLevelImpl(Dumper& d, const TopLevelImpl& v, int ind) {
    d.open("TopLevel.Impl");
    d.field("typeName", ind);
    dumpValue(d, v.typeName, ind + 2);
    d.field("methods", ind);
    dumpValue(d, v.methods, ind + 2);
    d.field("traitName", ind);
    dumpValue(d, v.traitName, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("isPackImpl", ind);
    dumpValue(d, v.isPackImpl, ind + 2);
    d.field("isExtension", ind);
    dumpValue(d, v.isExtension, ind + 2);
    d.field("traitArgs", ind);
    dumpValue(d, v.traitArgs, ind + 2);
    d.field("decoratorArgs", ind);
    dumpValue(d, v.decoratorArgs, ind + 2);
    d.field("decoratorNamedArgs", ind);
    dumpValue(d, v.decoratorNamedArgs, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("isBridge", ind);
    dumpValue(d, v.isBridge, ind + 2);
    d.field("typeParams", ind);
    dumpValue(d, v.typeParams, ind + 2);
    d.field("variadicParam", ind);
    dumpValue(d, v.variadicParam, ind + 2);
    d.field("scopePrefix", ind);
    dumpValue(d, v.scopePrefix, ind + 2);
    d.field("declaringModule", ind);
    dumpValue(d, v.declaringModule, ind + 2);
    d.field("traitQualifier", ind);
    dumpValue(d, v.traitQualifier, ind + 2);
    d.field("traitLine", ind);
    dumpValue(d, v.traitLine, ind + 2);
    d.field("traitColumn", ind);
    dumpValue(d, v.traitColumn, ind + 2);
    d.field("traitLength", ind);
    dumpValue(d, v.traitLength, ind + 2);
    d.field("isDerived", ind);
    dumpValue(d, v.isDerived, ind + 2);
    d.field("hasBody", ind);
    dumpValue(d, v.hasBody, ind + 2);
    d.field("assocBindings", ind);
    dumpValue(d, v.assocBindings, ind + 2);
    d.close(ind);
}
static void dumpTopLevelSpec(Dumper& d, const TopLevelSpec& v, int ind) {
    d.open("TopLevel.Spec");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("methods", ind);
    dumpValue(d, v.methods, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("callback", ind);
    dumpValue(d, v.callback, ind + 2);
    d.field("typeParams", ind);
    dumpValue(d, v.typeParams, ind + 2);
    d.field("parents", ind);
    dumpValue(d, v.parents, ind + 2);
    d.field("isBridge", ind);
    dumpValue(d, v.isBridge, ind + 2);
    d.field("isDirect", ind);
    dumpValue(d, v.isDirect, ind + 2);
    d.field("requires", ind);
    dumpValue(d, v.requires_, ind + 2);
    d.field("typeDefaults", ind);
    dumpValue(d, v.typeDefaults, ind + 2);
    d.field("assocNames", ind);
    dumpValue(d, v.assocNames, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("whereClause", ind);
    dumpValue(d, v.whereClause, ind + 2);
    d.close(ind);
}
static void dumpTopLevelTypeAlias(Dumper& d, const TopLevelTypeAlias& v, int ind) {
    d.open("TopLevel.TypeAlias");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("typeParams", ind);
    dumpValue(d, v.typeParams, ind + 2);
    d.field("whereClause", ind);
    dumpValue(d, v.whereClause, ind + 2);
    d.close(ind);
}
static void dumpTopLevelSlot(Dumper& d, const TopLevelSlot& v, int ind) {
    d.open("TopLevel.Slot");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("variants", ind);
    dumpValue(d, v.variants, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("isError", ind);
    dumpValue(d, v.isError, ind + 2);
    d.field("typeParams", ind);
    dumpValue(d, v.typeParams, ind + 2);
    d.field("whereClause", ind);
    dumpValue(d, v.whereClause, ind + 2);
    d.close(ind);
}
static void dumpTopLevelMeta(Dumper& d, const TopLevelMeta& v, int ind) {
    d.open("TopLevel.Meta");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("arms", ind);
    dumpValue(d, v.arms, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("parameter", ind);
    dumpValue(d, v.parameter, ind + 2);
    d.close(ind);
}
static void dumpTopLevelInlineAssert(Dumper& d, const TopLevelInlineAssert& v, int ind) {
    d.open("TopLevel.InlineAssert");
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.field("message", ind);
    dumpValue(d, v.message, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.close(ind);
}
static void dumpTopLevelInlineTrace(Dumper& d, const TopLevelInlineTrace& v, int ind) {
    d.open("TopLevel.InlineTrace");
    d.field("message", ind);
    dumpValue(d, v.message, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("level", ind);
    dumpValue(d, v.level, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const TopLevel& v, int ind) {
    switch (v.kind()) {
        case TopLevelKind::None: d.nil(); return;
        case TopLevelKind::Func: dumpTopLevelFunc(d, cast<TopLevelFunc>(v), ind); return;
        case TopLevelKind::VarDecl: dumpTopLevelVarDecl(d, cast<TopLevelVarDecl>(v), ind); return;
        case TopLevelKind::FinDecl: dumpTopLevelFinDecl(d, cast<TopLevelFinDecl>(v), ind); return;
        case TopLevelKind::LetDecl: dumpTopLevelLetDecl(d, cast<TopLevelLetDecl>(v), ind); return;
        case TopLevelKind::InlineVar: dumpTopLevelInlineVar(d, cast<TopLevelInlineVar>(v), ind); return;
        case TopLevelKind::InlineFin: dumpTopLevelInlineFin(d, cast<TopLevelInlineFin>(v), ind); return;
        case TopLevelKind::InlineLet: dumpTopLevelInlineLet(d, cast<TopLevelInlineLet>(v), ind); return;
        case TopLevelKind::InlineAssignment: dumpTopLevelInlineAssignment(d, cast<TopLevelInlineAssignment>(v), ind); return;
        case TopLevelKind::InlineIf: dumpTopLevelInlineIf(d, cast<TopLevelInlineIf>(v), ind); return;
        case TopLevelKind::InlineBlock: dumpTopLevelInlineBlock(d, cast<TopLevelInlineBlock>(v), ind); return;
        case TopLevelKind::DeepInlineBlock: dumpTopLevelDeepInlineBlock(d, cast<TopLevelDeepInlineBlock>(v), ind); return;
        case TopLevelKind::DeepInlineIf: dumpTopLevelDeepInlineIf(d, cast<TopLevelDeepInlineIf>(v), ind); return;
        case TopLevelKind::Test: dumpTopLevelTest(d, cast<TopLevelTest>(v), ind); return;
        case TopLevelKind::Pack: dumpTopLevelPack(d, cast<TopLevelPack>(v), ind); return;
        case TopLevelKind::Deco: dumpTopLevelDeco(d, cast<TopLevelDeco>(v), ind); return;
        case TopLevelKind::Bridge: dumpTopLevelBridge(d, cast<TopLevelBridge>(v), ind); return;
        case TopLevelKind::Solo: dumpTopLevelSolo(d, cast<TopLevelSolo>(v), ind); return;
        case TopLevelKind::Graph: dumpTopLevelGraph(d, cast<TopLevelGraph>(v), ind); return;
        case TopLevelKind::UseImport: dumpTopLevelUseImport(d, cast<TopLevelUseImport>(v), ind); return;
        case TopLevelKind::Enum: dumpTopLevelEnum(d, cast<TopLevelEnum>(v), ind); return;
        case TopLevelKind::Fail: dumpTopLevelFail(d, cast<TopLevelFail>(v), ind); return;
        case TopLevelKind::Impl: dumpTopLevelImpl(d, cast<TopLevelImpl>(v), ind); return;
        case TopLevelKind::Spec: dumpTopLevelSpec(d, cast<TopLevelSpec>(v), ind); return;
        case TopLevelKind::TypeAlias: dumpTopLevelTypeAlias(d, cast<TopLevelTypeAlias>(v), ind); return;
        case TopLevelKind::Slot: dumpTopLevelSlot(d, cast<TopLevelSlot>(v), ind); return;
        case TopLevelKind::Meta: dumpTopLevelMeta(d, cast<TopLevelMeta>(v), ind); return;
        case TopLevelKind::InlineAssert: dumpTopLevelInlineAssert(d, cast<TopLevelInlineAssert>(v), ind); return;
        case TopLevelKind::InlineTrace: dumpTopLevelInlineTrace(d, cast<TopLevelInlineTrace>(v), ind); return;
    }
}
static void dumpTypeFunctionStmtBinding(Dumper& d, const TypeFunctionStmtBinding& v, int ind) {
    d.open("TypeFunctionStmt.Binding");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.field("mutable", ind);
    dumpValue(d, v.mutable_, ind + 2);
    d.close(ind);
}
static void dumpTypeFunctionStmtAssignment(Dumper& d, const TypeFunctionStmtAssignment& v, int ind) {
    d.open("TypeFunctionStmt.Assignment");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.close(ind);
}
static void dumpTypeFunctionStmtForEach(Dumper& d, const TypeFunctionStmtForEach& v, int ind) {
    d.open("TypeFunctionStmt.ForEach");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("packName", ind);
    dumpValue(d, v.packName, ind + 2);
    d.field("startIndex", ind);
    dumpValue(d, v.startIndex, ind + 2);
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.close(ind);
}
static void dumpTypeFunctionStmtIf(Dumper& d, const TypeFunctionStmtIf& v, int ind) {
    d.open("TypeFunctionStmt.If");
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.field("thenBody", ind);
    dumpValue(d, v.thenBody, ind + 2);
    d.field("elseBody", ind);
    dumpValue(d, v.elseBody, ind + 2);
    d.close(ind);
}
static void dumpTypeFunctionStmtReturn(Dumper& d, const TypeFunctionStmtReturn& v, int ind) {
    d.open("TypeFunctionStmt.Return");
    d.field("value", ind);
    dumpValue(d, v.value, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const TypeFunctionStmt& v, int ind) {
    switch (v.kind()) {
        case TypeFunctionStmtKind::None: d.nil(); return;
        case TypeFunctionStmtKind::Binding: dumpTypeFunctionStmtBinding(d, cast<TypeFunctionStmtBinding>(v), ind); return;
        case TypeFunctionStmtKind::Assignment: dumpTypeFunctionStmtAssignment(d, cast<TypeFunctionStmtAssignment>(v), ind); return;
        case TypeFunctionStmtKind::ForEach: dumpTypeFunctionStmtForEach(d, cast<TypeFunctionStmtForEach>(v), ind); return;
        case TypeFunctionStmtKind::If: dumpTypeFunctionStmtIf(d, cast<TypeFunctionStmtIf>(v), ind); return;
        case TypeFunctionStmtKind::Return: dumpTypeFunctionStmtReturn(d, cast<TypeFunctionStmtReturn>(v), ind); return;
    }
}
static void dumpTypeFunctionExprReference(Dumper& d, const TypeFunctionExprReference& v, int ind) {
    d.open("TypeFunctionExpr.Reference");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.close(ind);
}
static void dumpTypeFunctionExprNullable(Dumper& d, const TypeFunctionExprNullable& v, int ind) {
    d.open("TypeFunctionExpr.Nullable");
    d.field("inner", ind);
    dumpValue(d, v.inner, ind + 2);
    d.close(ind);
}
static void dumpTypeFunctionExprPackElement(Dumper& d, const TypeFunctionExprPackElement& v, int ind) {
    d.open("TypeFunctionExpr.PackElement");
    d.field("packName", ind);
    dumpValue(d, v.packName, ind + 2);
    d.field("index", ind);
    dumpValue(d, v.index, ind + 2);
    d.close(ind);
}
static void dumpTypeFunctionExprCall(Dumper& d, const TypeFunctionExprCall& v, int ind) {
    d.open("TypeFunctionExpr.Call");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("args", ind);
    dumpValue(d, v.args, ind + 2);
    d.close(ind);
}
static void dumpTypeFunctionExprConditional(Dumper& d, const TypeFunctionExprConditional& v, int ind) {
    d.open("TypeFunctionExpr.Conditional");
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.field("thenValue", ind);
    dumpValue(d, v.thenValue, ind + 2);
    d.field("elseValue", ind);
    dumpValue(d, v.elseValue, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const TypeFunctionExpr& v, int ind) {
    switch (v.kind()) {
        case TypeFunctionExprKind::None: d.nil(); return;
        case TypeFunctionExprKind::Reference: dumpTypeFunctionExprReference(d, cast<TypeFunctionExprReference>(v), ind); return;
        case TypeFunctionExprKind::Nullable: dumpTypeFunctionExprNullable(d, cast<TypeFunctionExprNullable>(v), ind); return;
        case TypeFunctionExprKind::PackElement: dumpTypeFunctionExprPackElement(d, cast<TypeFunctionExprPackElement>(v), ind); return;
        case TypeFunctionExprKind::Call: dumpTypeFunctionExprCall(d, cast<TypeFunctionExprCall>(v), ind); return;
        case TypeFunctionExprKind::Conditional: dumpTypeFunctionExprConditional(d, cast<TypeFunctionExprConditional>(v), ind); return;
    }
}
void dumpValue(Dumper& d, const Visibility& v, int ind) {
    d.open("Visibility");
    d.field("reach", ind);
    dumpValue(d, v.reach, ind + 2);
    d.field("isExposed", ind);
    dumpValue(d, v.isExposed, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const ScopeMeta& v, int ind) {
    d.open("ScopeMeta");
    d.field("label", ind);
    dumpValue(d, v.label, ind + 2);
    d.field("isInline", ind);
    dumpValue(d, v.isInline, ind + 2);
    d.field("parent", ind);
    dumpValue(d, v.parent, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const Capture& v, int ind) {
    d.open("Capture");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("source", ind);
    dumpValue(d, v.source, ind + 2);
    d.field("mode", ind);
    dumpValue(d, v.mode, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const CaptureExclusion& v, int ind) {
    d.open("CaptureExclusion");
    d.field("source", ind);
    dumpValue(d, v.source, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const Annotation& v, int ind) {
    d.open("Annotation");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("args", ind);
    dumpValue(d, v.args, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("namedArgs", ind);
    dumpValue(d, v.namedArgs, ind + 2);
    d.field("qualifier", ind);
    dumpValue(d, v.qualifier, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const Param& v, int ind) {
    d.open("Param");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("defaultValue", ind);
    dumpValue(d, v.defaultValue, ind + 2);
    d.field("modifier", ind);
    dumpValue(d, v.modifier, ind + 2);
    d.field("variadic", ind);
    dumpValue(d, v.variadic, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("returnsOwnership", ind);
    dumpValue(d, v.returnsOwnership, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const FieldRepeat& v, int ind) {
    d.open("FieldRepeat");
    d.field("variable", ind);
    dumpValue(d, v.variable, ind + 2);
    d.field("range", ind);
    dumpValue(d, v.range, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const PackField& v, int ind) {
    d.open("PackField");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("mutable", ind);
    dumpValue(d, v.mutable_, ind + 2);
    d.field("default", ind);
    dumpValue(d, v.default_, ind + 2);
    d.field("visibility", ind);
    dumpValue(d, v.visibility, ind + 2);
    d.field("isUnsafe", ind);
    dumpValue(d, v.isUnsafe, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("repeats", ind);
    dumpValue(d, v.repeats, ind + 2);
    d.field("condition", ind);
    dumpValue(d, v.condition, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const TplField& v, int ind) {
    d.open("TplField");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const VariadicFieldTemplate& v, int ind) {
    d.open("VariadicFieldTemplate");
    d.field("loopVar", ind);
    dumpValue(d, v.loopVar, ind + 2);
    d.field("packVar", ind);
    dumpValue(d, v.packVar, ind + 2);
    d.field("fields", ind);
    dumpValue(d, v.fields, ind + 2);
    d.field("mixins", ind);
    dumpValue(d, v.mixins, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const TypeFunctionParam& v, int ind) {
    d.open("TypeFunctionParam");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("variadic", ind);
    dumpValue(d, v.variadic, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const TypeFunctionCondition& v, int ind) {
    d.open("TypeFunctionCondition");
    d.field("left", ind);
    dumpValue(d, v.left, ind + 2);
    d.field("operator", ind);
    dumpValue(d, v.operator_, ind + 2);
    d.field("right", ind);
    dumpValue(d, v.right, ind + 2);
    d.field("compareRank", ind);
    dumpValue(d, v.compareRank, ind + 2);
    d.field("valueFlags", ind);
    dumpValue(d, v.valueFlags, ind + 2);
    d.field("flagsExpected", ind);
    dumpValue(d, v.flagsExpected, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const TypeFunctionDecl& v, int ind) {
    d.open("TypeFunctionDecl");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("params", ind);
    dumpValue(d, v.params, ind + 2);
    d.field("valueParams", ind);
    dumpValue(d, v.valueParams, ind + 2);
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("minVariadicLength", ind);
    dumpValue(d, v.minVariadicLength, ind + 2);
    d.field("whereClause", ind);
    dumpValue(d, v.whereClause, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const FuncDecl& v, int ind) {
    d.open("FuncDecl");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("params", ind);
    dumpValue(d, v.params, ind + 2);
    d.field("returnType", ind);
    dumpValue(d, v.returnType, ind + 2);
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("isInline", ind);
    dumpValue(d, v.isInline, ind + 2);
    d.field("typeParams", ind);
    dumpValue(d, v.typeParams, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("length", ind);
    dumpValue(d, v.length, ind + 2);
    d.field("annotations", ind);
    dumpValue(d, v.annotations, ind + 2);
    d.field("isOverride", ind);
    dumpValue(d, v.isOverride, ind + 2);
    d.field("isVirtual", ind);
    dumpValue(d, v.isVirtual, ind + 2);
    d.field("isTask", ind);
    dumpValue(d, v.isTask, ind + 2);
    d.field("isReactive", ind);
    dumpValue(d, v.isReactive, ind + 2);
    d.field("isUnsafe", ind);
    dumpValue(d, v.isUnsafe, ind + 2);
    d.field("visibility", ind);
    dumpValue(d, v.visibility, ind + 2);
    d.field("returnTypeDeclared", ind);
    dumpValue(d, v.returnTypeDeclared, ind + 2);
    d.field("receiverModifier", ind);
    dumpValue(d, v.receiverModifier, ind + 2);
    d.field("receiverName", ind);
    dumpValue(d, v.receiverName, ind + 2);
    d.field("declaresReceiver", ind);
    dumpValue(d, v.declaresReceiver, ind + 2);
    d.field("extensionReceiver", ind);
    dumpValue(d, v.extensionReceiver, ind + 2);
    d.field("variadicParam", ind);
    dumpValue(d, v.variadicParam, ind + 2);
    d.field("minVariadicLength", ind);
    dumpValue(d, v.minVariadicLength, ind + 2);
    d.field("whereClause", ind);
    dumpValue(d, v.whereClause, ind + 2);
    d.field("constParams", ind);
    dumpValue(d, v.constParams, ind + 2);
    d.field("constDefaults", ind);
    dumpValue(d, v.constDefaults, ind + 2);
    d.field("constEnums", ind);
    dumpValue(d, v.constEnums, ind + 2);
    d.field("contextualParams", ind);
    dumpValue(d, v.contextualParams, ind + 2);
    d.field("isRepeated", ind);
    dumpValue(d, v.isRepeated, ind + 2);
    d.field("memberCallStyle", ind);
    dumpValue(d, v.memberCallStyle, ind + 2);
    d.field("useAsTemplate", ind);
    dumpValue(d, v.useAsTemplate, ind + 2);
    d.field("isUniversalInfix", ind);
    dumpValue(d, v.isUniversalInfix, ind + 2);
    d.field("isDeepInline", ind);
    dumpValue(d, v.isDeepInline, ind + 2);
    d.field("precompiled", ind);
    dumpValue(d, v.precompiled, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const MacroArm& v, int ind) {
    d.open("MacroArm");
    d.field("delimiter", ind);
    dumpValue(d, v.delimiter, ind + 2);
    d.field("pattern", ind);
    dumpValue(d, v.pattern, ind + 2);
    d.field("template", ind);
    dumpValue(d, v.template_, ind + 2);
    d.field("templateTail", ind);
    dumpValue(d, v.templateTail, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const InfixMacroRule& v, int ind) {
    d.open("InfixMacroRule");
    d.field("op", ind);
    dumpValue(d, v.op, ind + 2);
    d.field("left", ind);
    dumpValue(d, v.left, ind + 2);
    d.field("right", ind);
    dumpValue(d, v.right, ind + 2);
    d.field("template", ind);
    dumpValue(d, v.template_, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const TypeTypeArm& v, int ind) {
    d.open("TypeTypeArm");
    d.field("kind", ind);
    dumpValue(d, v.kind, ind + 2);
    d.field("holes", ind);
    dumpValue(d, v.holes, ind + 2);
    d.field("template", ind);
    dumpValue(d, v.template_, ind + 2);
    d.field("prefix", ind);
    dumpValue(d, v.prefix, ind + 2);
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("listTail", ind);
    dumpValue(d, v.listTail, ind + 2);
    d.field("keywords", ind);
    dumpValue(d, v.keywords, ind + 2);
    d.field("holeIsList", ind);
    dumpValue(d, v.holeIsList, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const DecoratorBinding& v, int ind) {
    d.open("DecoratorBinding");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("trailingTypeArgs", ind);
    dumpValue(d, v.trailingTypeArgs, ind + 2);
    d.field("targets", ind);
    dumpValue(d, v.targets, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const SpecCallback& v, int ind) {
    d.open("SpecCallback");
    d.field("returnType", ind);
    dumpValue(d, v.returnType, ind + 2);
    d.field("requiresParens", ind);
    dumpValue(d, v.requiresParens, ind + 2);
    d.field("params", ind);
    dumpValue(d, v.params, ind + 2);
    d.field("receiverModifier", ind);
    dumpValue(d, v.receiverModifier, ind + 2);
    d.field("receiverName", ind);
    dumpValue(d, v.receiverName, ind + 2);
    d.field("useAsTemplate", ind);
    dumpValue(d, v.useAsTemplate, ind + 2);
    d.field("typeParams", ind);
    dumpValue(d, v.typeParams, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const ImportSpec& v, int ind) {
    d.open("ImportSpec");
    d.field("path", ind);
    dumpValue(d, v.path, ind + 2);
    d.field("selector", ind);
    dumpValue(d, v.selector, ind + 2);
    d.field("without", ind);
    dumpValue(d, v.without, ind + 2);
    d.field("alias", ind);
    dumpValue(d, v.alias, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const WhenBranch& v, int ind) {
    d.open("Stmt.WhenBranch");
    d.field("patterns", ind);
    dumpValue(d, v.patterns, ind + 2);
    d.field("body", ind);
    dumpValue(d, v.body, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const BridgeSig& v, int ind) {
    d.open("TopLevel.BridgeSig");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("params", ind);
    dumpValue(d, v.params, ind + 2);
    d.field("returnType", ind);
    dumpValue(d, v.returnType, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("typeParams", ind);
    dumpValue(d, v.typeParams, ind + 2);
    d.field("localName", ind);
    dumpValue(d, v.localName, ind + 2);
    d.field("nameMacro", ind);
    dumpValue(d, v.nameMacro, ind + 2);
    d.field("localScope", ind);
    dumpValue(d, v.localScope, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const BridgeValue& v, int ind) {
    d.open("TopLevel.BridgeValue");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("type", ind);
    dumpValue(d, v.type, ind + 2);
    d.field("initializer", ind);
    dumpValue(d, v.initializer, ind + 2);
    d.field("mutable", ind);
    dumpValue(d, v.mutable_, ind + 2);
    d.field("isLet", ind);
    dumpValue(d, v.isLet, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.field("foreignName", ind);
    dumpValue(d, v.foreignName, ind + 2);
    d.field("nameMacro", ind);
    dumpValue(d, v.nameMacro, ind + 2);
    d.field("localScope", ind);
    dumpValue(d, v.localScope, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const GraphReg& v, int ind) {
    d.open("TopLevel.GraphReg");
    d.field("typeName", ind);
    dumpValue(d, v.typeName, ind + 2);
    d.field("args", ind);
    dumpValue(d, v.args, ind + 2);
    d.field("bindSpecs", ind);
    dumpValue(d, v.bindSpecs, ind + 2);
    d.field("lifetime", ind);
    dumpValue(d, v.lifetime, ind + 2);
    d.field("line", ind);
    dumpValue(d, v.line, ind + 2);
    d.field("column", ind);
    dumpValue(d, v.column, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const SlotVariant& v, int ind) {
    d.open("TopLevel.SlotVariant");
    d.field("name", ind);
    dumpValue(d, v.name, ind + 2);
    d.field("payloadTypes", ind);
    dumpValue(d, v.payloadTypes, ind + 2);
    d.close(ind);
}
void dumpValue(Dumper& d, const Program& v, int ind) {
    d.open("Program");
    d.field("moduleName", ind);
    dumpValue(d, v.moduleName, ind + 2);
    d.field("items", ind);
    dumpValue(d, v.items, ind + 2);
    d.field("localPackNames", ind);
    dumpValue(d, v.localPackNames, ind + 2);
    d.field("isExported", ind);
    dumpValue(d, v.isExported, ind + 2);
    d.field("moduleVisibility", ind);
    dumpValue(d, v.moduleVisibility, ind + 2);
    d.field("exportCondition", ind);
    dumpValue(d, v.exportCondition, ind + 2);
    d.field("scopes", ind);
    dumpValue(d, v.scopes, ind + 2);
    d.field("typeFunctions", ind);
    dumpValue(d, v.typeFunctions, ind + 2);
    d.field("typeMacroRules", ind);
    dumpValue(d, v.typeMacroRules, ind + 2);
    d.field("infixOperators", ind);
    dumpValue(d, v.infixOperators, ind + 2);
    d.field("infixMacros", ind);
    dumpValue(d, v.infixMacros, ind + 2);
    d.field("usesMacros", ind);
    dumpValue(d, v.usesMacros, ind + 2);
    d.field("scopeTypeNamespaces", ind);
    dumpValue(d, v.scopeTypeNamespaces, ind + 2);
    d.field("testScopeMembers", ind);
    dumpValue(d, v.testScopeMembers, ind + 2);
    d.field("moduleAnnotations", ind);
    dumpValue(d, v.moduleAnnotations, ind + 2);
    d.field("injectedNames", ind);
    dumpValue(d, v.injectedNames, ind + 2);
    d.field("tupleTemplates", ind);
    dumpValue(d, v.tupleTemplates, ind + 2);
    d.field("tupleShapes", ind);
    dumpValue(d, v.tupleShapes, ind + 2);
    d.close(ind);
}

void dumpValue(Dumper& d, const TypeAnnotation& v, int ind) {
    if (v.isInferred()) {
        d.raw("TypeAnnotation.Inferred");
        return;
    }
    d.open("TypeAnnotation.Explicit");
    d.field("ref", ind);
    dumpValue(d, v.ref, ind + 2);
    d.close(ind);
}

void dumpValue(Dumper& d, const StringTemplatePart& v, int ind) {
    if (v.isLiteral) {
        d.open("Expr.StringTemplatePart.Literal");
        d.field("text", ind);
        dumpValue(d, v.text, ind + 2);
    } else {
        d.open("Expr.StringTemplatePart.Expr");
        d.field("expr", ind);
        dumpValue(d, v.expr, ind + 2);
    }
    d.close(ind);
}

void dumpValue(Dumper& d, const MacroPattern& v, int ind) {
    switch (v.kind) {
        case MacroPatternKind::Empty:
            d.raw("MacroPattern.Empty");
            return;
        case MacroPatternKind::SeqCapture:
            d.open("MacroPattern.SeqCapture");
            d.field("name", ind);
            dumpValue(d, v.name, ind + 2);
            break;
        case MacroPatternKind::TypedCapture:
            d.open("MacroPattern.TypedCapture");
            d.field("name", ind);
            dumpValue(d, v.name, ind + 2);
            d.field("type", ind);
            dumpValue(d, v.type, ind + 2);
            break;
        case MacroPatternKind::MapEntryCapture:
            d.open("MacroPattern.MapEntryCapture");
            d.field("keyName", ind);
            dumpValue(d, v.keyName, ind + 2);
            d.field("valueName", ind);
            dumpValue(d, v.valueName, ind + 2);
            break;
    }
    d.close(ind);
}

void dumpValue(Dumper& d, const ImportSpecSelector& v, int ind) {
    switch (v.kind) {
        case SelectorKind::All: d.raw("ImportSpec.Selector.All"); return;
        case SelectorKind::Path: d.raw("ImportSpec.Selector.Path"); return;
        case SelectorKind::Group:
            d.open("ImportSpec.Selector.Group");
            d.field("members", ind);
            dumpValue(d, v.members, ind + 2);
            d.close(ind);
            return;
    }
}

void dumpValue(Dumper& d, ExprCall* v, int ind) { dumpValue(d, Expr(v), ind); }
void dumpValue(Dumper& d, ExprUpperScopeAccess* v, int ind) { dumpValue(d, Expr(v), ind); }
void dumpValue(Dumper& d, ExprMetaInvoke* v, int ind) { dumpValue(d, Expr(v), ind); }
void dumpValue(Dumper& d, ExprStringTemplate* v, int ind) { dumpValue(d, Expr(v), ind); }
void dumpValue(Dumper& d, TopLevelUseImport* v, int ind) { dumpValue(d, TopLevel(v), ind); }
void dumpValue(Dumper& d, TopLevelImpl* v, int ind) { dumpValue(d, TopLevel(v), ind); }
void dumpValue(Dumper& d, const ScopeMeta* v, int ind) {
    if (v == nullptr) d.nil();
    else dumpValue(d, *v, ind);
}

}  // namespace azora
