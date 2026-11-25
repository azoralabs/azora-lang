// The Azora AST. Port of Ast.kt (CPP_PORT_PLAN.md §6).
//
// One struct per node kind with the Kotlin class's constructor fields in
// Kotlin's order; one handle per category (Expr, Stmt, TypeRef, TopLevel,
// TypeFunctionStmt, TypeFunctionExpr) holding a std::variant of pointers to
// the kind structs, index 0 being "none" (Kotlin null). Kinds are tested with
// is<K>(h) / as<K>(h) (Kotlin `is` + smart cast) or a switch over h.kind()
// (Kotlin `when`). Nodes live in a compilation's Arena and are made with
// make(arena, ExprCall{...}); Kotlin's copy() is a struct copy plus make().
//
// Generated once from Ast.kt by a script and then kept by hand; the dump
// (ast_dump.cpp) prints every field, so the oracle comparison catches any
// field that is missing, misordered or mistyped.
#pragma once

#include "base/arena.h"
#include "base/dump.h"
#include "base/ordered.h"
#include "frontend/token.h"

#include <cstdint>
#include <optional>
#include <string>
#include <utility>
#include <variant>
#include <vector>

namespace azora {

/**
 * The method names that read a value out of an optional and drop the `?` from
 * its type: the primitive `take opt.require()` and the shorthand `opt.take()`.
 */
inline bool isOptionalUnwrap(const std::string& name) { return name == "require" || name == "take"; }

/**
 * The type-scoped member a `[…]` literal calls where its type is expected.
 * A `.` cannot appear in an Azora name, so no source can name or declare it.
 */
inline const char* const BRACKET_CONSTRUCTOR = "ctor.brackets";

/** Hands out ExprInferredMember::site ids, unique within a process (Kotlin `object InferredSites`). */
struct InferredSites {
    static int32_t next();
    /** Sets the last id handed out; the differential jobs start both compilers at 0. */
    static void reset(int32_t last);
};

// ---------------------------------------------------------------------------
// Enums (Kotlin enum classes; names reach dumps through enumName())
// ---------------------------------------------------------------------------

enum class OwnershipOp : uint8_t { TAKE, LEND, SHARE, BORROW };
const char* enumName(OwnershipOp value);
enum class RefKind : uint8_t { BORROWED, MUTABLE, SHARED, WEAK };
const char* enumName(RefKind value);
enum class CastKind : uint8_t { STATIC, DYNAMIC, REINTERPRET };
const char* enumName(CastKind value);
enum class BindingKind : uint8_t { VAR, VAL, LET, FIN };
const char* enumName(BindingKind value);
enum class ReactiveKind : uint8_t { REMEMBER, RETAIN, PRESERVE };
const char* enumName(ReactiveKind value);
enum class CaptureMode : uint8_t { COPY, SHARED, MUTABLE, CLONE, MOVE };
const char* enumName(CaptureMode value);
enum class CallableKind : uint8_t { FUNC, TASK, REACT, REACT_TASK };
const char* enumName(CallableKind value);
enum class TestMethod : uint8_t { This, All };
const char* enumName(TestMethod value);
enum class ParamModifier : uint8_t { NONE, SHARED, EXCLUSIVE };
const char* enumName(ParamModifier value);
enum class MemberCallStyle : uint8_t { NORMAL, PROPERTY, METHOD, STATIC_PROPERTY, STATIC_METHOD };
const char* enumName(MemberCallStyle value);
enum class MacroDelimiter : uint8_t { PAREN, BRACKET, BRACE };
const char* enumName(MacroDelimiter value);
enum class TypeFormKind : uint8_t { ARRAY, ARRAY_SIZED, SET, MAP, TUPLE, PREFIX, PREFIX_LIST, INFIX };
const char* enumName(TypeFormKind value);
enum class DecoTarget : uint8_t { Pack, Func, AsyncFunc, Prop, AsyncProp, Enum, VariantEnum, EnumValue, Error, VariantError, ErrorValue, UnsafeUnion, UnionValue, Annot, Field, Param, Var, Let, Val, Fin, Test, Module, Ctor, Dtor, TypeAlias, Bridge, Oper };
const char* enumName(DecoTarget value);
enum class ModuleVisibility : uint8_t { PUBLIC, CONFINED };
const char* enumName(ModuleVisibility value);
enum class Reach : uint8_t { PUBLIC, PROTECTED, CONFINED };
const char* enumName(Reach value);
enum class ProviderLifetime : uint8_t { SOLO, FACTORY, SCOPED };
const char* enumName(ProviderLifetime value);

/** OwnershipOp.spelling: how it is written in source, for diagnostics. */
const char* spelling(OwnershipOp op);
/** True for the two forms that borrow rather than duplicate or transfer. */
inline bool isBorrow(OwnershipOp op) { return op == OwnershipOp::SHARE || op == OwnershipOp::BORROW; }
const char* spelling(RefKind kind);
const char* spelling(BindingKind kind);
const char* spelling(ReactiveKind kind);
const char* spelling(CaptureMode mode);
const char* spelling(ProviderLifetime lifetime);
/** CallableKind.surfaceName. */
const char* surfaceName(CallableKind kind);
/** ParamModifier.sigil: `&`, `!`, or nothing. */
const char* sigil(ParamModifier modifier);
inline bool writable(ParamModifier modifier) { return modifier == ParamModifier::EXCLUSIVE; }
/** RefKind.paramModifier. */
inline ParamModifier paramModifier(RefKind kind) {
    return kind == RefKind::MUTABLE ? ParamModifier::EXCLUSIVE : ParamModifier::SHARED;
}
inline bool nameRebindable(BindingKind kind) { return kind == BindingKind::VAR || kind == BindingKind::VAL; }
inline bool valueMutable(BindingKind kind) { return kind == BindingKind::VAR || kind == BindingKind::LET; }

// ---------------------------------------------------------------------------
// Node kinds and category handles
// ---------------------------------------------------------------------------

struct ExprIntLiteral; struct ExprDoubleLiteral; struct ExprCharLiteral; struct ExprStringLiteral; struct ExprBoolLiteral; struct ExprIdentifier; struct ExprBinary; struct ExprUnary; struct ExprIncDec; struct ExprCall; struct ExprGrouping; struct ExprMapEntryArg; struct ExprUpperScopeAccess; struct ExprRange; struct ExprArrayLiteral; struct ExprSetLiteral; struct ExprIndex; struct ExprInferredMember; struct ExprMember; struct ExprMethodCall; struct ExprStringTemplate; struct ExprTupleLit; struct ExprVariantLit; struct ExprTupleAccess; struct ExprCatchExpr; struct ExprTryPropagate; struct ExprIfExpr; struct ExprSeal; struct ExprLambda; struct ExprInlineForArgs; struct ExprNamedArg; struct ExprNullLiteral; struct ExprNullCoalesce; struct ExprSafeMember; struct ExprCast; struct ExprIsCheck; struct ExprInCheck; struct ExprMapLit; struct ExprAlloc; struct ExprDeref; struct ExprIsolated; struct ExprAwait; struct ExprInject; struct ExprSpread; struct ExprMetaInvoke; struct ExprSlice;
struct StmtImport; struct StmtExchange; struct StmtVarDecl; struct StmtFinDecl; struct StmtLetDecl; struct StmtInlineFin; struct StmtDeepInlineBlock; struct StmtNoInline; struct StmtInlineBlock; struct StmtInlineLet; struct StmtInlineVar; struct StmtInlineAssignment; struct StmtAssignment; struct StmtReturn; struct StmtExprStmt; struct StmtScope; struct StmtIf; struct StmtInlineIf; struct StmtDeepInlineIf; struct StmtAssert; struct StmtTrace; struct StmtInlineAssert; struct StmtInlineTrace; struct StmtWhile; struct StmtFor; struct StmtLoop; struct StmtInlineFor; struct StmtBreak; struct StmtContinue; struct StmtIndexAssign; struct StmtMemberAssign; struct StmtWhen; struct StmtThrow; struct StmtPanic; struct StmtDerefAssign; struct StmtYield; struct StmtTry; struct StmtDefer; struct StmtRemDecl; struct StmtEffect; struct StmtUsingContext;
struct TypeRefNamed; struct TypeRefArray; struct TypeRefMap; struct TypeRefSet; struct TypeRefFunction; struct TypeRefTuple; struct TypeRefNullable; struct TypeRefFailable; struct TypeRefPointer; struct TypeRefConst; struct TypeRefReference;
struct TopLevelFunc; struct TopLevelVarDecl; struct TopLevelFinDecl; struct TopLevelLetDecl; struct TopLevelInlineVar; struct TopLevelInlineFin; struct TopLevelInlineLet; struct TopLevelInlineAssignment; struct TopLevelInlineIf; struct TopLevelInlineBlock; struct TopLevelDeepInlineBlock; struct TopLevelDeepInlineIf; struct TopLevelTest; struct TopLevelPack; struct TopLevelDeco; struct TopLevelBridge; struct TopLevelSolo; struct TopLevelGraph; struct TopLevelUseImport; struct TopLevelEnum; struct TopLevelFail; struct TopLevelImpl; struct TopLevelSpec; struct TopLevelTypeAlias; struct TopLevelSlot; struct TopLevelMeta; struct TopLevelInlineAssert; struct TopLevelInlineTrace;
struct TypeFunctionStmtBinding; struct TypeFunctionStmtAssignment; struct TypeFunctionStmtForEach; struct TypeFunctionStmtIf; struct TypeFunctionStmtReturn;
struct TypeFunctionExprReference; struct TypeFunctionExprNullable; struct TypeFunctionExprPackElement; struct TypeFunctionExprCall; struct TypeFunctionExprConditional;

enum class ExprKind : uint8_t {
    None,
    IntLiteral,
    DoubleLiteral,
    CharLiteral,
    StringLiteral,
    BoolLiteral,
    Identifier,
    Binary,
    Unary,
    IncDec,
    Call,
    Grouping,
    MapEntryArg,
    UpperScopeAccess,
    Range,
    ArrayLiteral,
    SetLiteral,
    Index,
    InferredMember,
    Member,
    MethodCall,
    StringTemplate,
    TupleLit,
    VariantLit,
    TupleAccess,
    CatchExpr,
    TryPropagate,
    IfExpr,
    Seal,
    Lambda,
    InlineForArgs,
    NamedArg,
    NullLiteral,
    NullCoalesce,
    SafeMember,
    Cast,
    IsCheck,
    InCheck,
    MapLit,
    Alloc,
    Deref,
    Isolated,
    Await,
    Inject,
    Spread,
    MetaInvoke,
    Slice,
};

struct Expr {
    std::variant<std::monostate, ExprIntLiteral*, ExprDoubleLiteral*, ExprCharLiteral*, ExprStringLiteral*, ExprBoolLiteral*, ExprIdentifier*, ExprBinary*, ExprUnary*, ExprIncDec*, ExprCall*, ExprGrouping*, ExprMapEntryArg*, ExprUpperScopeAccess*, ExprRange*, ExprArrayLiteral*, ExprSetLiteral*, ExprIndex*, ExprInferredMember*, ExprMember*, ExprMethodCall*, ExprStringTemplate*, ExprTupleLit*, ExprVariantLit*, ExprTupleAccess*, ExprCatchExpr*, ExprTryPropagate*, ExprIfExpr*, ExprSeal*, ExprLambda*, ExprInlineForArgs*, ExprNamedArg*, ExprNullLiteral*, ExprNullCoalesce*, ExprSafeMember*, ExprCast*, ExprIsCheck*, ExprInCheck*, ExprMapLit*, ExprAlloc*, ExprDeref*, ExprIsolated*, ExprAwait*, ExprInject*, ExprSpread*, ExprMetaInvoke*, ExprSlice*> v;

    Expr() = default;
    Expr(std::nullptr_t) {}
    template <class T>
    Expr(T* node) {
        if (node) v = node;
    }
    explicit operator bool() const { return v.index() != 0; }
    ExprKind kind() const { return (ExprKind)v.index(); }

    /** Kotlin's abstract `line`/`column`/`length`. */
    int32_t line() const;
    int32_t column() const;
    int32_t length() const;
};

/** The Kotlin class name of a kind, e.g. "Expr.IntLiteral". */
const char* kindName(ExprKind kind);

enum class StmtKind : uint8_t {
    None,
    Import,
    Exchange,
    VarDecl,
    FinDecl,
    LetDecl,
    InlineFin,
    DeepInlineBlock,
    NoInline,
    InlineBlock,
    InlineLet,
    InlineVar,
    InlineAssignment,
    Assignment,
    Return,
    ExprStmt,
    Scope,
    If,
    InlineIf,
    DeepInlineIf,
    Assert,
    Trace,
    InlineAssert,
    InlineTrace,
    While,
    For,
    Loop,
    InlineFor,
    Break,
    Continue,
    IndexAssign,
    MemberAssign,
    When,
    Throw,
    Panic,
    DerefAssign,
    Yield,
    Try,
    Defer,
    RemDecl,
    Effect,
    UsingContext,
};

struct Stmt {
    std::variant<std::monostate, StmtImport*, StmtExchange*, StmtVarDecl*, StmtFinDecl*, StmtLetDecl*, StmtInlineFin*, StmtDeepInlineBlock*, StmtNoInline*, StmtInlineBlock*, StmtInlineLet*, StmtInlineVar*, StmtInlineAssignment*, StmtAssignment*, StmtReturn*, StmtExprStmt*, StmtScope*, StmtIf*, StmtInlineIf*, StmtDeepInlineIf*, StmtAssert*, StmtTrace*, StmtInlineAssert*, StmtInlineTrace*, StmtWhile*, StmtFor*, StmtLoop*, StmtInlineFor*, StmtBreak*, StmtContinue*, StmtIndexAssign*, StmtMemberAssign*, StmtWhen*, StmtThrow*, StmtPanic*, StmtDerefAssign*, StmtYield*, StmtTry*, StmtDefer*, StmtRemDecl*, StmtEffect*, StmtUsingContext*> v;

    Stmt() = default;
    Stmt(std::nullptr_t) {}
    template <class T>
    Stmt(T* node) {
        if (node) v = node;
    }
    explicit operator bool() const { return v.index() != 0; }
    StmtKind kind() const { return (StmtKind)v.index(); }

    /** Kotlin's abstract `line`/`column`/`length`. */
    int32_t line() const;
    int32_t column() const;
    int32_t length() const;
};

/** The Kotlin class name of a kind, e.g. "Stmt.Import". */
const char* kindName(StmtKind kind);

enum class TypeRefKind : uint8_t {
    None,
    Named,
    Array,
    Map,
    Set,
    Function,
    Tuple,
    Nullable,
    Failable,
    Pointer,
    Const,
    Reference,
};

struct TypeRef {
    std::variant<std::monostate, TypeRefNamed*, TypeRefArray*, TypeRefMap*, TypeRefSet*, TypeRefFunction*, TypeRefTuple*, TypeRefNullable*, TypeRefFailable*, TypeRefPointer*, TypeRefConst*, TypeRefReference*> v;

    TypeRef() = default;
    TypeRef(std::nullptr_t) {}
    template <class T>
    TypeRef(T* node) {
        if (node) v = node;
    }
    explicit operator bool() const { return v.index() != 0; }
    TypeRefKind kind() const { return (TypeRefKind)v.index(); }
};

/** The Kotlin class name of a kind, e.g. "TypeRef.Named". */
const char* kindName(TypeRefKind kind);

enum class TopLevelKind : uint8_t {
    None,
    Func,
    VarDecl,
    FinDecl,
    LetDecl,
    InlineVar,
    InlineFin,
    InlineLet,
    InlineAssignment,
    InlineIf,
    InlineBlock,
    DeepInlineBlock,
    DeepInlineIf,
    Test,
    Pack,
    Deco,
    Bridge,
    Solo,
    Graph,
    UseImport,
    Enum,
    Fail,
    Impl,
    Spec,
    TypeAlias,
    Slot,
    Meta,
    InlineAssert,
    InlineTrace,
};

struct TopLevel {
    std::variant<std::monostate, TopLevelFunc*, TopLevelVarDecl*, TopLevelFinDecl*, TopLevelLetDecl*, TopLevelInlineVar*, TopLevelInlineFin*, TopLevelInlineLet*, TopLevelInlineAssignment*, TopLevelInlineIf*, TopLevelInlineBlock*, TopLevelDeepInlineBlock*, TopLevelDeepInlineIf*, TopLevelTest*, TopLevelPack*, TopLevelDeco*, TopLevelBridge*, TopLevelSolo*, TopLevelGraph*, TopLevelUseImport*, TopLevelEnum*, TopLevelFail*, TopLevelImpl*, TopLevelSpec*, TopLevelTypeAlias*, TopLevelSlot*, TopLevelMeta*, TopLevelInlineAssert*, TopLevelInlineTrace*> v;

    TopLevel() = default;
    TopLevel(std::nullptr_t) {}
    template <class T>
    TopLevel(T* node) {
        if (node) v = node;
    }
    explicit operator bool() const { return v.index() != 0; }
    TopLevelKind kind() const { return (TopLevelKind)v.index(); }
};

/** The Kotlin class name of a kind, e.g. "TopLevel.Func". */
const char* kindName(TopLevelKind kind);

enum class TypeFunctionStmtKind : uint8_t {
    None,
    Binding,
    Assignment,
    ForEach,
    If,
    Return,
};

struct TypeFunctionStmt {
    std::variant<std::monostate, TypeFunctionStmtBinding*, TypeFunctionStmtAssignment*, TypeFunctionStmtForEach*, TypeFunctionStmtIf*, TypeFunctionStmtReturn*> v;

    TypeFunctionStmt() = default;
    TypeFunctionStmt(std::nullptr_t) {}
    template <class T>
    TypeFunctionStmt(T* node) {
        if (node) v = node;
    }
    explicit operator bool() const { return v.index() != 0; }
    TypeFunctionStmtKind kind() const { return (TypeFunctionStmtKind)v.index(); }
};

/** The Kotlin class name of a kind, e.g. "TypeFunctionStmt.Binding". */
const char* kindName(TypeFunctionStmtKind kind);

enum class TypeFunctionExprKind : uint8_t {
    None,
    Reference,
    Nullable,
    PackElement,
    Call,
    Conditional,
};

struct TypeFunctionExpr {
    std::variant<std::monostate, TypeFunctionExprReference*, TypeFunctionExprNullable*, TypeFunctionExprPackElement*, TypeFunctionExprCall*, TypeFunctionExprConditional*> v;

    TypeFunctionExpr() = default;
    TypeFunctionExpr(std::nullptr_t) {}
    template <class T>
    TypeFunctionExpr(T* node) {
        if (node) v = node;
    }
    explicit operator bool() const { return v.index() != 0; }
    TypeFunctionExprKind kind() const { return (TypeFunctionExprKind)v.index(); }
};

/** The Kotlin class name of a kind, e.g. "TypeFunctionExpr.Reference". */
const char* kindName(TypeFunctionExprKind kind);

/** Kotlin `h is K`. */
template <class K, class H>
bool is(const H& h) {
    return std::holds_alternative<K*>(h.v);
}

/** Kotlin `h as? K`: the kind's node, or nullptr. */
template <class K, class H>
K* as(const H& h) {
    K* const* node = std::get_if<K*>(&h.v);
    return node ? *node : nullptr;
}

[[noreturn]] void failCast(const char* from);

/** Kotlin `h as K` where the kind is known; fails like a ClassCastException otherwise. */
template <class K, class H>
K& cast(const H& h) {
    K* const* node = std::get_if<K*>(&h.v);
    if (node == nullptr) failCast(kindName(h.kind()));
    return **node;
}

/** Identity (Kotlin `===`). */
template <class H>
bool same(const H& a, const H& b) {
    return a.v == b.v;
}

// ---------------------------------------------------------------------------
// Values that are not node categories
// ---------------------------------------------------------------------------

/**
 * A type annotation on a variable or return type: Kotlin's sealed
 * TypeAnnotation. Explicit(ref) has a ref; Inferred (the object) has none.
 */
struct TypeAnnotation {
    TypeRef ref;
    bool isInferred() const { return !ref; }
    bool isExplicit() const { return (bool)ref; }
};
inline TypeAnnotation explicitType(TypeRef ref) { return TypeAnnotation{ref}; }
inline TypeAnnotation inferredType() { return TypeAnnotation{}; }

/** One segment of a string-interpolation template (Expr.StringTemplatePart). */
struct StringTemplatePart {
    /** Literal(text) when true, Expr(expr) when false. */
    bool isLiteral = true;
    std::string text;
    Expr expr;
};
inline StringTemplatePart literalPart(std::string text) { return StringTemplatePart{true, std::move(text), {}}; }
inline StringTemplatePart exprPart(Expr expr) { return StringTemplatePart{false, {}, expr}; }

/** The left-hand pattern of a macro arm (Kotlin's sealed MacroPattern). */
enum class MacroPatternKind : uint8_t { Empty, SeqCapture, TypedCapture, MapEntryCapture };
struct MacroPattern {
    MacroPatternKind kind = MacroPatternKind::Empty;
    /** SeqCapture, TypedCapture. */
    std::string name;
    /** TypedCapture. */
    TypeRef type;
    /** MapEntryCapture. */
    std::string keyName;
    std::string valueName;
};

struct ImportSpec;
/** What an ImportSpec takes from its path (Kotlin's sealed ImportSpec.Selector). */
enum class SelectorKind : uint8_t { All, Path, Group };
struct ImportSpecSelector {
    SelectorKind kind = SelectorKind::Path;
    /** Group: each member carries its own full path. */
    std::vector<ImportSpec> members;
};

struct Visibility {
    Reach reach = Reach::PUBLIC;
    bool isExposed = false;
    bool operator==(const Visibility&) const = default;
};

struct ScopeMeta {
    std::optional<std::string> label;
    bool isInline = {};
    const ScopeMeta* parent = nullptr;
};

struct Capture {
    std::string name;
    std::string source;
    CaptureMode mode = {};
    int32_t line = {};
    int32_t column = 0;
};

struct CaptureExclusion {
    std::string source;
    int32_t line = {};
    int32_t column = 0;
};

struct Annotation {
    std::string name;
    std::vector<Expr> args;
    int32_t line = 0;
    int32_t column = 0;
    std::vector<std::pair<std::string, Expr>> namedArgs;
    std::optional<std::string> qualifier;
};

struct Param {
    std::string name;
    TypeRef type;
    Expr defaultValue;
    ParamModifier modifier = ParamModifier::NONE;
    bool variadic = false;
    std::vector<Annotation> annotations;
    bool returnsOwnership = false;
};

struct FieldRepeat {
    std::string variable;
    Expr range;
};

struct PackField {
    std::string name;
    TypeRef type;
    bool mutable_ = {};
    Expr default_;
    Visibility visibility = Visibility{};
    bool isUnsafe = false;
    std::vector<Annotation> annotations;
    std::vector<FieldRepeat> repeats;
    Expr condition;
};

struct TplField {
    std::string name;
    TypeRef type;
};

struct VariadicFieldTemplate {
    std::string loopVar;
    std::string packVar;
    std::vector<TplField> fields;
    std::vector<ExprStringTemplate*> mixins;
};

struct TypeFunctionParam {
    std::string name;
    bool variadic = false;
};

struct TypeFunctionCondition {
    TypeFunctionExpr left;
    TokenType operator_ = {};
    TypeFunctionExpr right;
    bool compareRank = {};
    std::vector<std::string> valueFlags;
    std::vector<bool> flagsExpected;
};

struct TypeFunctionDecl {
    std::string name;
    std::vector<TypeFunctionParam> params;
    std::vector<Param> valueParams;
    std::vector<TypeFunctionStmt> body;
    std::optional<int32_t> minVariadicLength;
    Expr whereClause;
    int32_t line = {};
    int32_t column = 0;
    std::vector<Annotation> annotations;
};

struct FuncDecl {
    std::string name;
    std::vector<Param> params;
    TypeAnnotation returnType;
    std::vector<Stmt> body;
    bool isInline = false;
    std::vector<std::string> typeParams;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::vector<Annotation> annotations;
    bool isOverride = false;
    bool isVirtual = false;
    bool isTask = false;
    bool isReactive = false;
    bool isUnsafe = false;
    Visibility visibility = Visibility{};
    bool returnTypeDeclared = true;
    ParamModifier receiverModifier = ParamModifier::EXCLUSIVE;
    std::string receiverName = "self";
    bool declaresReceiver = true;
    std::optional<Param> extensionReceiver;
    std::optional<std::string> variadicParam;
    std::optional<int32_t> minVariadicLength;
    Expr whereClause;
    OrderedSet<std::string> constParams;
    OrderedMap<std::string, TypeRef> constDefaults;
    OrderedMap<std::string, std::string> constEnums;
    int32_t contextualParams = 0;
    bool isRepeated = false;
    MemberCallStyle memberCallStyle = MemberCallStyle::NORMAL;
    std::optional<std::string> useAsTemplate;
    bool isUniversalInfix = false;
    bool isDeepInline = false;
    bool precompiled = false;
};

struct MacroArm {
    MacroDelimiter delimiter = {};
    MacroPattern pattern;
    Expr template_;
    std::vector<Expr> templateTail;
};

struct InfixMacroRule {
    std::string op;
    std::string left;
    std::string right;
    Expr template_;
};

struct TypeTypeArm {
    TypeFormKind kind = {};
    std::vector<std::string> holes;
    TypeRef template_;
    std::string prefix = "";
    std::optional<std::string> name;
    bool listTail = false;
    std::vector<std::string> keywords;
    std::vector<bool> holeIsList;
};

struct DecoratorBinding {
    std::string name;
    std::vector<TypeRef> trailingTypeArgs;
    OrderedSet<DecoTarget> targets;
};

struct SpecCallback {
    TypeRef returnType;
    bool requiresParens = {};
    std::vector<Param> params;
    ParamModifier receiverModifier = {};
    std::string receiverName;
    std::optional<std::string> useAsTemplate;
    std::vector<std::string> typeParams;
};

struct ImportSpec {
    std::string path;
    ImportSpecSelector selector;
    std::vector<std::string> without;
    std::optional<std::string> alias;
    int32_t line = 0;
    int32_t column = 0;
};

struct WhenBranch {
    std::vector<Expr> patterns;
    std::vector<Stmt> body;
    int32_t line = {};
    int32_t column = 0;
};

struct BridgeSig {
    std::string name;
    std::vector<Param> params;
    TypeRef returnType;
    int32_t line = {};
    int32_t column = 0;
    std::vector<std::string> typeParams;
    std::optional<std::string> localName;
    ExprMetaInvoke* nameMacro = nullptr;
    std::optional<std::string> localScope;
};

struct BridgeValue {
    std::string name;
    TypeRef type;
    Expr initializer;
    bool mutable_ = {};
    bool isLet = {};
    int32_t line = {};
    int32_t column = 0;
    std::optional<std::string> foreignName;
    ExprMetaInvoke* nameMacro = nullptr;
    std::optional<std::string> localScope;
};

struct GraphReg {
    std::string typeName;
    std::vector<Expr> args;
    std::vector<std::string> bindSpecs;
    ProviderLifetime lifetime = ProviderLifetime::SOLO;
    int32_t line = 0;
    int32_t column = 0;
};

struct SlotVariant {
    std::string name;
    std::vector<TypeRef> payloadTypes;
};

// ---------------------------------------------------------------------------
// Kind structs
// ---------------------------------------------------------------------------

struct ExprIntLiteral {
    int64_t value = {};
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::optional<std::string> text;
    std::optional<std::string> magnitude;
};

struct ExprDoubleLiteral {
    double value = {};
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::optional<std::string> text;
};

struct ExprCharLiteral {
    int32_t value = {};
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprStringLiteral {
    std::string value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprBoolLiteral {
    bool value = {};
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprIdentifier {
    std::string name;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprBinary {
    Expr left;
    TokenType op = {};
    Expr right;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprUnary {
    TokenType op = {};
    Expr operand;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprIncDec {
    Expr target;
    TokenType op = {};
    bool prefix = {};
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprCall {
    std::string callee;
    std::vector<Expr> args;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::vector<TypeRef> typeArgs;
    Expr receiver;
    std::optional<std::vector<TypeRef>> inferredTypeArgs;  // Kotlin `var`: set by a later pass
};

struct ExprGrouping {
    Expr expr;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprMapEntryArg {
    Expr key;
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprUpperScopeAccess {
    std::string name;
    int32_t depth = 1;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprRange {
    Expr from;
    Expr to;
    bool inclusive = {};
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    bool descending = false;
};

struct ExprArrayLiteral {
    std::vector<Expr> elements;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    TypeRef contextualType;  // Kotlin `var`: set by a later pass
    std::optional<std::string> literalFactory;  // Kotlin `var`: set by a later pass
};

struct ExprSetLiteral {
    std::vector<Expr> elements;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprIndex {
    Expr target;
    Expr index;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprInferredMember {
    std::string name;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::optional<std::vector<Expr>> ctorArgs;
    ExprCall* scopeCall = nullptr;
    ExprUpperScopeAccess* scopeRead = nullptr;
    int32_t site = InferredSites::next();  // Kotlin default InferredSites.next(); a copy keeps it
};

struct ExprMember {
    Expr target;
    std::string name;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    Expr nameExpr;
};

struct ExprMethodCall {
    Expr target;
    std::string name;
    std::vector<Expr> args;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::vector<TypeRef> typeArgs;
};

struct ExprStringTemplate {
    std::vector<StringTemplatePart> parts;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprTupleLit {
    std::vector<Expr> elements;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    TypeRef contextualType;  // Kotlin `var`: set by a later pass
};

struct ExprVariantLit {
    std::vector<Expr> elements;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprTupleAccess {
    Expr target;
    int32_t index = {};
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprCatchExpr {
    Expr expr;
    Expr fallback;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprTryPropagate {
    Expr expr;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprIfExpr {
    Expr condition;
    Expr thenExpr;
    Expr elseExpr;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprSeal {
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprLambda {
    std::vector<Param> params;
    std::vector<Stmt> body;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    bool variadic = false;
    std::vector<Param> receivers;
    CallableKind kind = CallableKind::FUNC;
    bool paramsWritten = true;
    std::vector<Capture> captures;
    std::optional<CaptureMode> captureDefault;
    std::vector<CaptureExclusion> captureExclusions;
    std::vector<std::string> typeParams;
    std::optional<std::string> variadicTypeParam;
};

struct ExprInlineForArgs {
    std::string name;
    Expr iterable;
    Expr body;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprNamedArg {
    std::string name;
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprNullLiteral {
    int32_t line = 0;
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprNullCoalesce {
    Expr left;
    Expr right;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprSafeMember {
    Expr target;
    std::string name;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprCast {
    Expr expr;
    TypeRef targetType;
    CastKind kind = CastKind::STATIC;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprIsCheck {
    Expr expr;
    std::string typeName;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::vector<TypeRef> typeArgs;
};

struct ExprInCheck {
    Expr value;
    Expr collection;
    bool negated = false;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprMapLit {
    std::vector<std::pair<Expr, Expr>> entries;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    TypeRef contextualType;  // Kotlin `var`: set by a later pass
    std::optional<std::string> literalFactory;  // Kotlin `var`: set by a later pass
};

struct ExprAlloc {
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    bool mutable_ = false;
};

struct ExprDeref {
    Expr target;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprIsolated {
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    OwnershipOp op = {};
};

struct ExprAwait {
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprInject {
    std::string typeName;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprSpread {
    Expr array;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprMetaInvoke {
    std::string name;
    std::vector<Expr> args;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct ExprSlice {
    Expr target;
    Expr start;
    Expr stop;
    Expr step;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtImport {
    TopLevelUseImport* use = nullptr;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtExchange {
    Expr left;
    Expr right;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 2;
};

struct StmtVarDecl {
    std::string name;
    TypeAnnotation type;
    Expr initializer;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    bool valueMutable = true;
    bool lazy = false;
};

struct StmtFinDecl {
    std::string name;
    TypeAnnotation type;
    Expr initializer;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    bool lazy = false;
    bool synthesized = false;
};

struct StmtLetDecl {
    std::string name;
    TypeAnnotation type;
    Expr initializer;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    bool lazy = false;
};

struct StmtInlineFin {
    std::string name;
    TypeAnnotation type;
    Expr initializer;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtDeepInlineBlock {
    std::vector<Stmt> body;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtNoInline {
    Stmt stmt;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtInlineBlock {
    std::vector<Stmt> body;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtInlineLet {
    std::string name;
    TypeAnnotation type;
    Expr initializer;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtInlineVar {
    std::string name;
    TypeAnnotation type;
    Expr initializer;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    bool valueMutable = true;
};

struct StmtInlineAssignment {
    std::string name;
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtAssignment {
    std::string name;
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::optional<TokenType> compoundOp;
};

struct StmtReturn {
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    bool implicit = false;
};

struct StmtExprStmt {
    Expr expr;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtScope {
    std::vector<Stmt> body;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    bool unsafe = false;
    bool shared = false;
};

struct StmtIf {
    Expr condition;
    std::vector<Stmt> thenBranch;
    std::optional<std::vector<Stmt>> elseBranch;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtInlineIf {
    Expr condition;
    std::vector<Stmt> thenBranch;
    std::optional<std::vector<Stmt>> elseBranch;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtDeepInlineIf {
    Expr condition;
    std::vector<Stmt> thenBranch;
    std::optional<std::vector<Stmt>> elseBranch;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtAssert {
    Expr condition;
    Expr message;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtTrace {
    Expr message;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    Expr level;
    bool liftBody = true;
    bool explicitLevel = true;
};

struct StmtInlineAssert {
    Expr condition;
    Expr message;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtInlineTrace {
    Expr message;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    Expr level;
};

struct StmtWhile {
    Expr condition;
    std::vector<Stmt> body;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::optional<std::string> label;
};

struct StmtFor {
    std::string name;
    Expr iterable;
    std::vector<Stmt> body;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    Expr step;
    std::optional<std::string> label;
    TypeRef declaredType;
    std::optional<std::string> indexName;
};

struct StmtLoop {
    std::vector<Stmt> body;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::optional<std::string> label;
    Expr iterable;
    Expr everySeconds;
};

struct StmtInlineFor {
    std::string name;
    Expr iterable;
    std::vector<Stmt> body;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::optional<std::string> indexName;
};

struct StmtBreak {
    std::optional<std::string> label;
    int32_t line = 0;
    int32_t column = 0;
    int32_t length = 0;
    Expr value;
};

struct StmtContinue {
    std::optional<std::string> label;
    int32_t line = 0;
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtIndexAssign {
    Expr target;
    Expr index;
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::optional<TokenType> compoundOp;
};

struct StmtMemberAssign {
    Expr target;
    std::string name;
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::optional<TokenType> compoundOp;
    Expr nameExpr;
};

struct StmtWhen {
    Expr scrutinee;
    std::vector<WhenBranch> branches;
    std::optional<std::vector<Stmt>> elseBranch;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtThrow {
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    bool returned = false;
};

struct StmtPanic {
    Expr message;
    bool inlinePanic = {};
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtDerefAssign {
    Expr target;
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtYield {
    Expr value;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtTry {
    std::vector<Stmt> body;
    std::optional<std::string> catchName;
    std::optional<std::vector<Stmt>> catchBody;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct StmtDefer {
    std::vector<Stmt> body;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    bool onFail = false;
    bool suppress = false;
};

struct StmtRemDecl {
    std::string name;
    TypeAnnotation type;
    Expr initializer;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    ReactiveKind kind = ReactiveKind::REMEMBER;
    BindingKind binding = BindingKind::VAR;
};

struct StmtEffect {
    std::vector<Stmt> body;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
    std::optional<std::vector<Expr>> dependencies;
    bool deferred = false;
    Expr condition;
};

struct StmtUsingContext {
    std::vector<Expr> values;
    std::vector<Stmt> body;
    int32_t line = {};
    int32_t column = 0;
    int32_t length = 0;
};

struct TypeRefNamed {
    std::string name;
    std::vector<TypeRef> args;
    bool variadic = false;
    std::optional<std::string> qualifier;
    bool synthesized = false;
    std::vector<Expr> valueArgs;
    int32_t line = 0;
    int32_t column = 0;
    int32_t length = 0;
};

struct TypeRefArray {
    TypeRef element;
};

struct TypeRefMap {
    TypeRef key;
    TypeRef value;
};

struct TypeRefSet {
    TypeRef element;
};

struct TypeRefFunction {
    std::vector<TypeRef> params;
    TypeRef ret;
    std::vector<TypeRef> receivers;
    CallableKind kind = CallableKind::FUNC;
    bool isEscaping = false;
    bool isInline = false;
    bool isForeign = false;
};

struct TypeRefTuple {
    std::vector<TypeRef> elements;
};

struct TypeRefNullable {
    TypeRef inner;
};

struct TypeRefFailable {
    TypeRef ok;
    std::vector<std::string> errSets;
};

struct TypeRefPointer {
    TypeRef inner;
    bool mutable_ = false;
};

struct TypeRefConst {
    int64_t value = {};
    std::optional<std::string> label;
};

struct TypeRefReference {
    RefKind kind = {};
    TypeRef inner;
    std::vector<std::string> origins;
};

struct TopLevelFunc {
    FuncDecl decl;
};

struct TopLevelVarDecl {
    std::string name;
    TypeRef type;
    Expr initializer;
    int32_t line = {};
    int32_t column = 0;
    std::vector<Annotation> annotations;
    bool threadlocal = false;
    Visibility visibility = Visibility{};
    bool valueMutable = true;
};

struct TopLevelFinDecl {
    std::string name;
    TypeRef type;
    Expr initializer;
    int32_t line = {};
    int32_t column = 0;
    std::vector<Annotation> annotations;
    bool threadlocal = false;
    Visibility visibility = Visibility{};
};

struct TopLevelLetDecl {
    std::string name;
    TypeRef type;
    Expr initializer;
    int32_t line = {};
    int32_t column = 0;
    std::vector<Annotation> annotations;
    Visibility visibility = Visibility{};
};

struct TopLevelInlineVar {
    std::string name;
    Expr initializer;
    int32_t line = {};
    int32_t column = 0;
    bool valueMutable = true;
};

struct TopLevelInlineFin {
    std::string name;
    Expr initializer;
    int32_t line = {};
    int32_t column = 0;
    TypeRef type;
};

struct TopLevelInlineLet {
    std::string name;
    Expr initializer;
    int32_t line = {};
    int32_t column = 0;
};

struct TopLevelInlineAssignment {
    std::string name;
    Expr value;
    int32_t line = {};
    int32_t column = 0;
};

struct TopLevelInlineIf {
    Expr condition;
    std::vector<TopLevel> thenBranch;
    std::optional<std::vector<TopLevel>> elseBranch;
    int32_t line = {};
    int32_t column = 0;
};

struct TopLevelInlineBlock {
    std::vector<TopLevel> body;
    int32_t line = {};
    int32_t column = 0;
};

struct TopLevelDeepInlineBlock {
    std::vector<TopLevel> body;
    int32_t line = {};
    int32_t column = 0;
};

struct TopLevelDeepInlineIf {
    Expr condition;
    std::vector<TopLevel> thenBranch;
    std::optional<std::vector<TopLevel>> elseBranch;
    int32_t line = {};
    int32_t column = 0;
};

struct TopLevelTest {
    std::string name;
    std::vector<Stmt> body;
    int32_t line = {};
    int32_t column = 0;
    std::vector<Annotation> annotations;
    TestMethod method = TestMethod::This;
};

struct TopLevelPack {
    std::string name;
    std::vector<PackField> fields;
    std::vector<std::string> typeParams;
    int32_t line = {};
    int32_t column = 0;
    std::vector<Annotation> annotations;
    Visibility visibility = Visibility{};
    std::optional<std::string> variadicParam;
    std::optional<int32_t> minVariadicLength;
    OrderedSet<std::string> constParams;
    OrderedMap<std::string, TypeRef> constDefaults;
    OrderedMap<std::string, std::string> constEnums;
    Expr whereClause;
    std::optional<VariadicFieldTemplate> fieldTemplate;
    bool isBridge = false;
    std::optional<std::string> literalKind;
    bool isUnion = false;
    bool declaredByImpl = false;
    std::optional<std::string> declaringModule;
    std::optional<std::string> foreignName;
    ExprMetaInvoke* nameMacro = nullptr;
    std::optional<std::string> localScope;
};

struct TopLevelDeco {
    std::string name;
    std::vector<PackField> fields;
    int32_t line = {};
    int32_t column = 0;
    std::vector<Annotation> annotations;
    OrderedSet<DecoTarget> targets;
    std::vector<DecoratorBinding> bindings;
    bool isBridge = false;
};

struct TopLevelBridge {
    std::string target;
    std::vector<BridgeSig> funcs;
    int32_t line = {};
    int32_t column = 0;
    std::vector<Annotation> annotations;
    std::vector<BridgeValue> values;
};

struct TopLevelSolo {
    std::string name;
    std::vector<PackField> fields;
    std::vector<FuncDecl> methods;
    int32_t line = {};
    int32_t column = 0;
    Visibility visibility = Visibility{};
    std::vector<Annotation> annotations;
};

struct TopLevelGraph {
    std::string name;
    std::vector<GraphReg> registrations;
    int32_t line = {};
    int32_t column = 0;
    std::vector<std::string> included;
};

struct TopLevelUseImport {
    std::vector<ImportSpec> specs;
    int32_t line = {};
    int32_t column = 0;
    bool exported = false;
    Expr condition;
};

struct TopLevelEnum {
    std::string name;
    std::vector<std::string> variants;
    int32_t line = {};
    int32_t column = 0;
    std::vector<Annotation> annotations;
    std::vector<std::vector<Annotation>> variantAnnotations;
    std::vector<std::string> typeParams;
    Expr whereClause;
    TypeRef valueType;
    std::vector<std::vector<Expr>> caseValues;
};

struct TopLevelFail {
    std::string name;
    std::vector<std::string> variants;
    int32_t line = {};
    int32_t column = 0;
    std::vector<Annotation> annotations;
    std::vector<std::vector<Annotation>> variantAnnotations;
    std::vector<std::vector<TypeRef>> variantPayloads;
    std::vector<std::string> typeParams;
    Expr whereClause;
};

struct TopLevelImpl {
    std::string typeName;
    std::vector<FuncDecl> methods;
    std::optional<std::string> traitName;
    int32_t line = {};
    int32_t column = 0;
    bool isPackImpl = false;
    bool isExtension = false;
    std::vector<TypeRef> traitArgs;
    std::vector<Expr> decoratorArgs;
    std::vector<std::pair<std::string, Expr>> decoratorNamedArgs;
    std::vector<Annotation> annotations;
    bool isBridge = false;
    std::vector<std::string> typeParams;
    std::optional<std::string> variadicParam;
    std::optional<std::string> scopePrefix;
    std::optional<std::string> declaringModule;
    std::optional<std::string> traitQualifier;
    int32_t traitLine = -1;  // Kotlin default: line (see implDefaults)
    int32_t traitColumn = -1;  // Kotlin default: column (see implDefaults)
    int32_t traitLength = -1;  // Kotlin default: traitName?.length ?: 0 (see implDefaults)
    bool isDerived = false;
    bool hasBody = true;
    OrderedMap<std::string, TypeRef> assocBindings;
};

struct TopLevelSpec {
    std::string name;
    std::vector<FuncDecl> methods;
    int32_t line = {};
    int32_t column = 0;
    std::optional<SpecCallback> callback;
    std::vector<std::string> typeParams;
    std::vector<TypeRef> parents;
    bool isBridge = false;
    bool isDirect = false;
    std::vector<TypeRef> requires_;
    OrderedMap<std::string, TypeRef> typeDefaults;
    std::vector<std::string> assocNames;
    std::vector<Annotation> annotations;
    Expr whereClause;
};

struct TopLevelTypeAlias {
    std::string name;
    TypeRef type;
    int32_t line = {};
    int32_t column = 0;
    std::vector<Annotation> annotations;
    std::vector<std::string> typeParams;
    Expr whereClause;
};

struct TopLevelSlot {
    std::string name;
    std::vector<SlotVariant> variants;
    int32_t line = {};
    int32_t column = 0;
    std::vector<Annotation> annotations;
    bool isError = false;
    std::vector<std::string> typeParams;
    Expr whereClause;
};

struct TopLevelMeta {
    std::string name;
    std::vector<MacroArm> arms;
    int32_t line = {};
    int32_t column = 0;
    std::optional<std::string> parameter;
};

struct TopLevelInlineAssert {
    Expr condition;
    Expr message;
    int32_t line = {};
    int32_t column = 0;
};

struct TopLevelInlineTrace {
    Expr message;
    int32_t line = {};
    int32_t column = 0;
    Expr level;
};

struct TypeFunctionStmtBinding {
    std::string name;
    TypeFunctionExpr value;
    bool mutable_ = {};
};

struct TypeFunctionStmtAssignment {
    std::string name;
    TypeFunctionExpr value;
};

struct TypeFunctionStmtForEach {
    std::string name;
    std::string packName;
    int32_t startIndex = {};
    std::vector<TypeFunctionStmt> body;
};

struct TypeFunctionStmtIf {
    TypeFunctionCondition condition;
    std::vector<TypeFunctionStmt> thenBody;
    std::vector<TypeFunctionStmt> elseBody;
};

struct TypeFunctionStmtReturn {
    TypeFunctionExpr value;
};

struct TypeFunctionExprReference {
    std::string name;
};

struct TypeFunctionExprNullable {
    TypeFunctionExpr inner;
};

struct TypeFunctionExprPackElement {
    std::string packName;
    int32_t index = {};
};

struct TypeFunctionExprCall {
    std::string name;
    std::vector<TypeFunctionExpr> args;
};

struct TypeFunctionExprConditional {
    TypeFunctionCondition condition;
    TypeFunctionExpr thenValue;
    TypeFunctionExpr elseValue;
};

/** The `null` literal: Kotlin's `object NullLiteral`, one shared instance. */
Expr nullLiteral();

struct Program {
    std::optional<std::string> moduleName;
    std::vector<TopLevel> items;
    OrderedSet<std::string> localPackNames;
    bool isExported = false;
    ModuleVisibility moduleVisibility = ModuleVisibility::PUBLIC;
    Expr exportCondition;
    OrderedMap<std::string, ScopeMeta> scopes;
    std::vector<TypeFunctionDecl> typeFunctions;
    std::vector<TypeTypeArm> typeMacroRules;
    OrderedSet<std::string> infixOperators;
    std::vector<InfixMacroRule> infixMacros;
    bool usesMacros = false;
    OrderedMap<std::string, std::string> scopeTypeNamespaces;
    OrderedMap<std::string, Visibility> testScopeMembers;
    std::vector<Annotation> moduleAnnotations;
    OrderedSet<std::string> injectedNames;
    std::vector<TopLevelImpl*> tupleTemplates;
    OrderedMap<std::string, std::vector<TypeRef>> tupleShapes;
};

/** Kotlin's TopLevel.Impl defaults: traitLine = line, traitColumn = column, traitLength = traitName?.length ?: 0. */
void implDefaults(TopLevelImpl& impl);

// ---------------------------------------------------------------------------
// Dumps (ast_dump.cpp)
// ---------------------------------------------------------------------------

void dumpValue(Dumper& d, const Expr& v, int ind);
void dumpValue(Dumper& d, const Stmt& v, int ind);
void dumpValue(Dumper& d, const TypeRef& v, int ind);
void dumpValue(Dumper& d, const TopLevel& v, int ind);
void dumpValue(Dumper& d, const TypeFunctionStmt& v, int ind);
void dumpValue(Dumper& d, const TypeFunctionExpr& v, int ind);
void dumpValue(Dumper& d, const TypeAnnotation& v, int ind);
void dumpValue(Dumper& d, const StringTemplatePart& v, int ind);
void dumpValue(Dumper& d, const MacroPattern& v, int ind);
void dumpValue(Dumper& d, const ImportSpecSelector& v, int ind);
void dumpValue(Dumper& d, const Program& v, int ind);
void dumpValue(Dumper& d, const Visibility& v, int ind);
void dumpValue(Dumper& d, const ScopeMeta& v, int ind);
void dumpValue(Dumper& d, const Capture& v, int ind);
void dumpValue(Dumper& d, const CaptureExclusion& v, int ind);
void dumpValue(Dumper& d, const Annotation& v, int ind);
void dumpValue(Dumper& d, const Param& v, int ind);
void dumpValue(Dumper& d, const FieldRepeat& v, int ind);
void dumpValue(Dumper& d, const PackField& v, int ind);
void dumpValue(Dumper& d, const TplField& v, int ind);
void dumpValue(Dumper& d, const VariadicFieldTemplate& v, int ind);
void dumpValue(Dumper& d, const TypeFunctionParam& v, int ind);
void dumpValue(Dumper& d, const TypeFunctionCondition& v, int ind);
void dumpValue(Dumper& d, const TypeFunctionDecl& v, int ind);
void dumpValue(Dumper& d, const FuncDecl& v, int ind);
void dumpValue(Dumper& d, const MacroArm& v, int ind);
void dumpValue(Dumper& d, const InfixMacroRule& v, int ind);
void dumpValue(Dumper& d, const TypeTypeArm& v, int ind);
void dumpValue(Dumper& d, const DecoratorBinding& v, int ind);
void dumpValue(Dumper& d, const SpecCallback& v, int ind);
void dumpValue(Dumper& d, const ImportSpec& v, int ind);
void dumpValue(Dumper& d, const WhenBranch& v, int ind);
void dumpValue(Dumper& d, const BridgeSig& v, int ind);
void dumpValue(Dumper& d, const BridgeValue& v, int ind);
void dumpValue(Dumper& d, const GraphReg& v, int ind);
void dumpValue(Dumper& d, const SlotVariant& v, int ind);
void dumpValue(Dumper& d, OwnershipOp v, int ind);
void dumpValue(Dumper& d, RefKind v, int ind);
void dumpValue(Dumper& d, CastKind v, int ind);
void dumpValue(Dumper& d, BindingKind v, int ind);
void dumpValue(Dumper& d, ReactiveKind v, int ind);
void dumpValue(Dumper& d, CaptureMode v, int ind);
void dumpValue(Dumper& d, CallableKind v, int ind);
void dumpValue(Dumper& d, TestMethod v, int ind);
void dumpValue(Dumper& d, ParamModifier v, int ind);
void dumpValue(Dumper& d, MemberCallStyle v, int ind);
void dumpValue(Dumper& d, MacroDelimiter v, int ind);
void dumpValue(Dumper& d, TypeFormKind v, int ind);
void dumpValue(Dumper& d, DecoTarget v, int ind);
void dumpValue(Dumper& d, ModuleVisibility v, int ind);
void dumpValue(Dumper& d, Reach v, int ind);
void dumpValue(Dumper& d, ProviderLifetime v, int ind);
void dumpValue(Dumper& d, TokenType v, int ind);
void dumpValue(Dumper& d, ExprCall* v, int ind);
void dumpValue(Dumper& d, ExprUpperScopeAccess* v, int ind);
void dumpValue(Dumper& d, ExprMetaInvoke* v, int ind);
void dumpValue(Dumper& d, ExprStringTemplate* v, int ind);
void dumpValue(Dumper& d, TopLevelUseImport* v, int ind);
void dumpValue(Dumper& d, TopLevelImpl* v, int ind);
void dumpValue(Dumper& d, const ScopeMeta* v, int ind);

}  // namespace azora
