// Recursive-descent parser for the Azora language. Port of Parser.kt.
//
// Parser.kt is split by area into parser_*.cpp; each part names the Kotlin
// line range it ports. Member functions keep Kotlin's names and order. The
// member declarations between the METHODS markers are regenerated from the
// .cpp files by tools/parser-decls.py (default arguments are written in the
// .cpp as `/*= value*/` and moved here), so the two never disagree.
#pragma once

#include "base/arena.h"
#include "base/ordered.h"
#include "frontend/ast.h"
#include "frontend/ast_util.h"
#include "frontend/token.h"

#include <functional>
#include <optional>
#include <string>
#include <tuple>
#include <utility>
#include <vector>

namespace azora {

using TypeListEnv = OrderedMap<std::string, std::vector<std::string>>;
using EnumEnv = OrderedMap<std::string, std::vector<std::string>>;
using TypeListScopes = OrderedMap<std::string, std::string>;

/** A source fragment written between two operands, with the holes it names. */
struct InfixFragment {
    std::string text;
    std::string leftHole;
    std::string rightHole;
};

/**
 * Parser.kt's companion object: `macro @name => inline "…"` fragments, by name.
 *
 * Shared across the modules of one compilation - a fragment stands for source
 * text wherever the name appears - and process-wide, as the Kotlin companion
 * object is. Cleared per compilation by resetFragmentMacros().
 */
struct FragmentMacroTables {
    OrderedMap<std::string, std::string> fragmentMacros;
    /** The hole a parameterized fragment takes, by macro name. */
    OrderedMap<std::string, std::string> fragmentMacroParams;
    OrderedMap<std::string, InfixFragment> infixFragmentMacros;
};

/** parseFuncDecl's named arguments, in Kotlin's order (namespace scope so
 * `= {}` default arguments inside Parser see their member initializers). */
struct FuncDeclOptions {
    bool isInline = false;
    std::vector<Annotation> annotations;
    bool isOverride = false;
    bool isVirtual = false;
    bool isTask = false;
    bool isReactive = false;
    bool isUnsafe = false;
    Visibility visibility;
    bool inImplBlock = false;
    bool isDeepInline = false;
};

/** parsePackField's named arguments, in Kotlin's order (namespace scope so
 * `= {}` default arguments inside Parser see their member initializers). */
struct PackFieldOptions {
    std::optional<Visibility> preparsedVisibility;
    bool enforceNumFields = false;
    std::optional<std::vector<Annotation>> preparsedAnnotations;
    bool requireFin = false;
};

struct Parser {
    /** The receiver's name, everywhere it is written. */
    static constexpr const char* SELF_RECEIVER = "self";
    /** `[&]` - a receiver the member takes and does not use. */
    static constexpr const char* IGNORED_RECEIVER = "_";

    /**
     * [typeListEnv], [declaredEnums] and [typeListScope] are shared with the
     * caller when given (Kotlin passes the same MutableMap); null means a fresh
     * map owned by this parser, Kotlin's `= mutableMapOf()` default.
     */
    Parser(Arena& arena, std::vector<Token> initialTokens, TypeListEnv* typeListEnv = nullptr,
           EnumEnv* declaredEnums = nullptr, bool internalSource = false, TypeListScopes* typeListScope = nullptr,
           bool allowWhenExpressions = true);
    Parser(const Parser&) = delete;
    Parser& operator=(const Parser&) = delete;

    Arena& arena;

    // Kotlin constructor properties -----------------------------------------
    TypeListEnv ownTypeListEnv;
    EnumEnv ownDeclaredEnums;
    TypeListScopes ownTypeListScope;
    /**
     * Compile-time lists of type names (`let X: [Type] = [A, B] ~ C`). Shared with
     * sub-parsers used to expand `inline for` bodies so nested loops resolve the
     * same lists. Purely a parse-time metaprogramming environment.
     */
    TypeListEnv* typeListEnv;
    /** Enum name → its variants, in declaration order. */
    EnumEnv* declaredEnums;
    /** Compiler-generated source may use reserved `__` names while being reparsed. */
    bool internalSource;
    /** Compile-time list name -> the named scope it was declared in, or "". */
    TypeListScopes* typeListScope;
    bool allowWhenExpressions;

    /** The token stream, mutable so a fragment macro can splice into it. */
    std::vector<Token> tokens;

    /** Compile-time type functions are declarations, but never runtime top-level items. */
    std::vector<TypeFunctionDecl> typeFunctions;
    /** Namespace used to qualify unqualified type-function calls inside a scope. */
    std::string typeFunctionNamespacePrefix;
    /** The error set(s) the function currently being parsed declares with `?!`. */
    std::vector<std::string> currentFailSets;
    /** Suppresses `in` as a membership operator (a `for` header's step). */
    bool inOperatorEnabled = true;
    std::vector<TopLevel> pendingTopLevels;
    /** Type-macro arms collected during parsing, surfaced on Program.typeMacroRules. */
    std::vector<TypeTypeArm> pendingTypeMacroRules;
    std::vector<InfixMacroRule> pendingInfixMacros;
    /** The type an `impl` body is on, or null outside one. What `Self` names. */
    std::optional<std::string> currentImplTypeName;
    /** Additional statements produced by tuple destructuring. */
    std::vector<Stmt> pendingStmts;
    /** Statements that must run before the statement being parsed. */
    std::vector<Stmt> pendingPrelude;
    /** Unique names for parser-created tuple destructuring values. */
    int32_t tupleValueCounter = 0;
    /** Extra receivers parsed by a parenthesized member receiver head. */
    std::vector<Param> pendingMemberContextReceivers;
    /** What a repeated ctor calls its repetition: `ctor .() * count`. */
    const std::string REPEAT_COUNT_NAME = "count";
    /** Set whenever a `name@…` macro invocation is parsed; surfaced on Program.usesMacros. */
    bool usedMetaInvoke = false;

    /** Enclosing labeled/anonymous scopes while parsing (outermost first). */
    struct ScopeFrame {
        std::optional<std::string> label;
        bool isInline;
    };
    std::vector<ScopeFrame> scopeStack;
    /** Declaration name → its innermost scope, for `(reflect X).scope` reflection. */
    OrderedMap<std::string, ScopeMeta> scopeMetaByName;
    /** One frame per lambda body being parsed; true once that body has mentioned a free `it`. */
    std::vector<bool> lambdaMentionsIt;
    /** Declarations written inside a `test scope`, and how far each reaches. */
    OrderedMap<std::string, Visibility> testScopeMembers;
    /** Type declaration name → named namespace path (`std`, `container`, ...). */
    OrderedMap<std::string, std::string> scopeTypeNamespaces;

    int32_t current = 0;
    /** When `>>` (SHIFT_RIGHT) is split for nested generics, a pending `>` for the enclosing type. */
    bool pendingGreater = false;
    int32_t contractResultCounter = 0;
    OrderedMap<std::string, SpecCallback> callbackSpecs;

    struct ContractClauses {
        std::vector<Stmt> preconditions;
        std::optional<std::string> resultName;
        std::vector<Stmt> postconditions;
    };

    /** When false, a call does NOT consume a following `{` as a trailing lambda. */
    bool allowTrailingLambda = true;

    struct ContractHead {
        std::string name;
        std::vector<TypeRef> args;
        std::optional<std::string> qualifier;
        int32_t line = 0;
        int32_t column = 0;
        /** Kotlin default: name.length; see contractHead(). */
        int32_t length = -1;
    };

    std::vector<std::string> lastImplTypeParams;
    std::optional<std::string> lastImplVariadicParam;
    Expr lastDeclaredBounds;

    struct ReceiverBinding {
        std::string name;
        ParamModifier modifier;
    };

    OrderedMap<std::string, std::string> bridgeTargetConstants;
    int32_t bridgeNamePlaceholder = 0;

    /** A bridge name written directly, as generated syntax, or through a macro. */
    struct ParsedBridgeName {
        std::string backendName;
        std::optional<std::string> localName;
        ExprMetaInvoke* macro = nullptr;
    };

    struct WhenHead {
        Expr scrutinee;
        bool guard;
    };

    template <class B>
    struct WhenParts {
        std::vector<std::vector<Expr>> patterns;
        std::vector<B> bodies;
        std::optional<B> elseBody;
    };

    bool allowLowercaseAnnotation = false;
    OrderedMap<std::string, std::string> constParamEnums;

    struct TypeParams {
        std::vector<std::string> names;
        std::optional<std::string> variadic;
        OrderedSet<std::string> constParams;
        OrderedMap<std::string, TypeRef> constDefaults;
        /** Const parameter → the enum whose variants it ranges over, when it has one. */
        OrderedMap<std::string, std::string> constEnums;
        /** Type parameter → the type it stands for when the argument is omitted. */
        OrderedMap<std::string, TypeRef> typeDefaults;
        /** Parameters written `T::` - the member they drive is a static. */
        OrderedSet<std::string> staticParams;
        /** `<T: Clone>` - the inline form of `where T: Clone`, as the clause it means. */
        Expr boundClause;
    };

    /** A loop row's pattern: a name, or a tuple's positions taken apart in turn. */
    struct LoopPattern {
        /** Name(name) when set; Group(parts) otherwise. */
        std::optional<std::string> name;
        std::vector<LoopPattern> parts;
    };

    int32_t loopElseCounter = 0;
    int32_t loopByCounter = 0;

    struct PropReceiver {
        std::string name;
        TypeRef type;
        ParamModifier modifier;
        bool declared = true;
        /** Whether a borrow sigil was written. */
        bool borrowWritten = true;
    };

    struct TracePayload {
        Expr level;
        Expr message;
        bool liftBody;
        bool explicitLevel;
    };

    using FuncDeclOptions = azora::FuncDeclOptions;
    using PackFieldOptions = azora::PackFieldOptions;

    int32_t tryThenStart = -1;
    std::vector<TypeRef> reflectTargetArgs;
    int32_t forExprCounter = 0;

    /**
     * The branches of a `when`, with each body read by [parseBody] (Kotlin's
     * generic `parseWhenBranches<B>`). The three `when` forms differ only in
     * what a branch body is; patterns, `is` checks, comma-separated
     * multi-pattern branches, `else` and newlines are handled here once.
     */
    template <class B>
    WhenParts<B> parseWhenBranches(const Expr& scrutinee, const std::function<B()>& parseBody) {
        consume(TokenType::L_BRACE, "Expected '{' after when scrutinee");
        skipNewlines();
        WhenParts<B> parts;
        while (!check(TokenType::R_BRACE) && !isAtEnd()) {
            skipNewlines();
            if (check(TokenType::R_BRACE)) break;
            if (match(TokenType::ELSE)) {
                consume(TokenType::ARROW, "Expected '->' after else");
                parts.elseBody = parseBody();
                skipNewlines();
                break;
            }
            std::vector<Expr> group;
            group.push_back(parseWhenPattern(scrutinee));
            // `.Start, else -> 0.0`: the named cases share the fallback.
            bool joinsElse = false;
            while (match(TokenType::COMMA)) {
                if (match(TokenType::ELSE)) {
                    joinsElse = true;
                    break;
                }
                group.push_back(parseWhenPattern(scrutinee));
            }
            consume(TokenType::ARROW, joinsElse ? "Expected '->' after else" : "Expected '->' after when patterns");
            parts.patterns.push_back(group);
            B body = parseBody();
            parts.bodies.push_back(body);
            skipNewlines();
            if (joinsElse) {
                parts.elseBody = body;
                break;
            }
        }
        consume(TokenType::R_BRACE, "Expected '}' after when body");
        return parts;
    }

    // METHODS BEGIN (generated by tools/parser-decls.py)
    // parser_core.cpp
    static FragmentMacroTables& fragmentTables();
    static void resetFragmentMacros();
    std::optional<std::vector<std::string>> comptimeList(const std::string& name);
    const ScopeMeta* currentScopeMeta();
    void recordScopeMembers(const std::vector<TopLevel>& items);
    std::optional<std::string> declaredTopLevelName(const TopLevel& item);
    bool isScopeBlockAhead();
    std::vector<TopLevel> parseScopeBlock();
    Program parse();
    std::optional<TokenType> namespaceKeywordAhead();
    bool isScopeNamespaceAhead();
    std::vector<TopLevel> parseScopeNamespace(const std::string& outerPrefix = "");
    std::optional<std::string> bindingKeywordOf(const TopLevel& item);
    std::vector<TopLevel> parseScopeBody(const std::string& prefix);
    std::optional<std::string> declaredTypeName(const TopLevel& item);
    TopLevel withImplTypeParams(const TopLevel& item, const std::vector<std::string>& implTypeParams);
    TopLevel mangleTopLevel(const TopLevel& item, const std::string& prefix);
    std::string parseOperatorName();
    std::string operatorMemberName(const std::string& opName);
    std::string operatorSourceName(const std::string& opName);
    std::vector<Param> parseOperatorOperands(const std::string& opName);
    bool isBareLookupOperator(const std::string& opName);
    void refuseOperatorStep();
    TopLevel parseFreeOperator(const std::vector<Annotation>& annotations, const Visibility& visibility, bool isBridge = false);
    bool isSpecStaticImplAhead();
    bool isStaticImplAhead();
    std::tuple<ParamModifier, std::vector<Param>, std::vector<Stmt>> parseReceiverAndBody();
    bool isInBraceReceiverAhead();
    Visibility parseVisibility();
    bool exposeStartsAnotherForm();
    Expr parseSubExpr(const std::string& source);
    Token peek();
    std::optional<Token> peekNext();
    bool peekNextIs(TokenType type);
    bool typeIs(int32_t index, TokenType type);
    bool lexemeIs(int32_t index, const std::string& lexeme);
    const Token* tokenOrNull(int32_t index);
    int32_t nextMeaningfulIndex(int32_t from);
    int32_t nextMeaningfulIndex();
    bool isAtEnd();
    bool check(TokenType type);
    Token advance();
    void skipNewlines();
    void consumeNewline();
    bool match(TokenType type);
    bool matchContinued(TokenType type);
    bool matchWithKeyword();
    Token consume(TokenType type, const std::string& message);
    TopLevel makeImpl(TopLevelImpl impl);
    // parser_decls.cpp
    TopLevel parseTopLevel();
    TopLevel withAnnotations(TopLevelSpec* spec, const std::vector<Annotation>& annotations);
    std::vector<Annotation> parseAnnotations();
    Annotation parseOneAnnotation(int32_t line, int32_t column);
    std::pair<std::vector<Expr>, std::vector<std::pair<std::string, Expr>>> parseDecoratorArguments();
    bool casePayloadAhead();
    OrderedSet<DecoTarget> parseDecoTargets();
    DecoratorBinding parseDecoratorBinding();
    TopLevelDeco* parseDeco(const std::vector<Annotation>& annotations, bool isBridge = false);
    TopLevel parseTopLevelInline(const std::vector<Annotation>& annotations = {});
    // parser_inline.cpp
    TopLevel parseTopLevelInlineFor();
    std::vector<Token> foldBraceInterpolation(const std::vector<Token>& tokens, const std::vector<std::pair<std::string, std::string>>& bindings);
    std::vector<Token> substituteBareIdentifier(const std::vector<Token>& tokens, const std::string& name, const std::string& value);
    std::vector<Token> foldConstantArithmetic(const std::vector<Token>& tokens);
    std::vector<Token> foldListIndexing(const std::vector<Token>& tokens);
    std::vector<std::string> parseComptimeForValues();
    std::vector<std::string> parseComptimeListValue();
    bool isTypeListAliasAhead();
    TopLevel parseTypeListAlias();
    bool isTypeListBindingAhead();
    TopLevel parseTypeListBinding();
    std::vector<std::string> parseComptimeListTerm();
    std::vector<Token> captureInlineForBody();
    std::vector<Token> captureBraceBody();
    std::vector<Token> substituteLoopVar(const std::vector<Token>& tokens, const std::string& varName, const std::string& value);
    TopLevelInlineBlock* parseTopLevelInlineBlock();
    TopLevel parseTopLevelDeepInline();
    TopLevelDeepInlineBlock* parseTopLevelDeepInlineBlock();
    TopLevelDeepInlineIf* parseTopLevelDeepInlineIf();
    TopLevelInlineIf* parseTopLevelInlineIf();
    std::vector<TopLevel> parseTopLevelBlock(bool deepInline = false);
    TopLevel parseTopLevelBlockItem(bool deepInline = false);
    // parser_pack.cpp
    bool isTestScopeAhead();
    std::vector<TopLevel> parseTestScope();
    TopLevelTest* parseTestDecl(const std::vector<Annotation>& annotations = {});
    bool isUnionDeclAhead();
    TopLevelPack* parsePack(const std::vector<Annotation>& annotations = {}, const Visibility& visibility = Visibility{}, bool isBridge = false, bool isUnion = false);
    Parser::ContractHead contractHead(ContractHead head);
    bool parseParenthesizedList(const std::string& description, const std::function<void()>& element);
    Parser::ContractHead parseDeriveHead();
    std::vector<Parser::ContractHead> parseDeriveHeads();
    TopLevelImpl* derivedImpl(const std::string& target, const ContractHead& head, const Token& start, const std::vector<std::string>& typeParams = {}, const std::optional<std::string>& variadicParam = std::nullopt, const std::vector<Expr>& decoratorArgs = {}, const std::vector<std::pair<std::string, Expr>>& decoratorNamedArgs = {});
    void enqueueDerives(const std::string& target, const std::vector<ContractHead>& heads, const Token& start, const std::vector<std::string>& typeParams = {}, const std::optional<std::string>& variadicParam = std::nullopt);
    TopLevelImpl* parseDeriveDecl();
    VariadicFieldTemplate parseVariadicFieldTemplate();
    bool isInlineFieldFormAhead();
    bool isVariadicFieldTemplateAhead();
    Expr parseArmPattern(const Expr& subject);
    std::vector<PackField> parseInlineFieldForm(bool enforceNumFields);
    std::vector<PackField> fieldBlock(bool enforceNumFields);
    std::vector<PackField> guardedFieldBlock(const Expr& guard, bool enforceNumFields);
    std::vector<PackField> repeatedFieldBlock(const std::string& loopVar, const Expr& iterable, bool enforceNumFields);
    std::vector<FuncDecl> parseInlineMemberFor(const std::string& typeName);
    std::vector<Token> foldInlineBindings(const std::vector<Token>& body, const std::vector<std::pair<std::string, std::string>>& bindings);
    std::vector<Token> substituteTokenSequence(const std::vector<Token>& tokens, const std::string& name, const std::vector<Token>& replacement);
    std::optional<std::vector<Token>> parseSignatureFragment();
    std::optional<bool> staticTruth(const Expr& expr);
    std::optional<std::string> literalText(const Expr& expr);
    TypeAnnotation applySignatureFragment(const TypeAnnotation& ret, const std::vector<Token>& fragment, const Token& at);
    TypeRef parseTypeNameForFragment();
    std::string operatorOverloadSuffix(const std::string& opName, ParamModifier receiver, const std::vector<Param>& operands);
    std::string operandTypeKey(const TypeRef& type);
    PackField parsePackField(const PackFieldOptions& options = {});
    Expr conjoin(const Expr& inner, const Expr& outer);
    TypeRef inferPackFieldType(const Expr& expr);
    // parser_impl.cpp
    TopLevelImpl* parseImpl(bool isBridge = false, const std::vector<Annotation>& annotations = {});
    // parser_bridge.cpp
    FuncDecl parseOperMethod(const Token& start, const Visibility& visibility = Visibility{});
    TopLevel funcOrExtension(const FuncDecl& decl);
    std::string namedTypeName(const TypeRef& t);
    ParamModifier parseImplReceiverModifier();
    ParamModifier parsePostfixReceiverModifier();
    Parser::ReceiverBinding parseReceiverBinding(const std::string& what = "receiver");
    void parseReceiverOperands(std::vector<Param>& params);
    ParamModifier parseSpecReceiverModifier();
    bool isBridgeFuncAhead();
    std::string parseBridgeTarget();
    std::string checkedBridgeTarget(const std::string& member, const Token& at);
    std::string parseQualifiedTypeName(const std::string& message);
    Parser::ParsedBridgeName parseBridgeName();
    TypeRef inlineFinType(const TypeRef& type);
    void noteBridgeTargetConstant(const std::string& name, const Expr& initializer);
    TopLevelBridge* parseBridgeFunc(const std::vector<Annotation>& annotations = {});
    bool isBridgeReceiverPropAhead();
    TopLevel parseBridgeReceiverProp(const std::vector<Annotation>& annotations);
    std::string receiverTypeName(const TypeRef& ref);
    ParamModifier receiverModifierOf(const TypeRef& ref);
    TopLevelBridge* parseBridge(const std::vector<Annotation>& annotations = {});
    TopLevelSolo* parseSolo(const Visibility& visibility = Visibility{}, const std::vector<Annotation>& annotations = {});
    TopLevelGraph* parseGraph();
    TopLevelSlot* parseSlot(const std::vector<Annotation>& annotations = {});
    // parser_macros.cpp
    bool spliceFragmentMacro();
    bool spliceInfixFragmentMacro(int32_t leftStart, const Token& nameToken);
    std::string expandSelfSplices(const std::string& body, int32_t line);
    std::vector<Token> fragmentArgumentTokens(int32_t from);
    TopLevelMeta* parseMeta();
    std::string macroArmSigil();
    void rejectRemovedUse(const std::string& remedy);
    std::string parseMacroName();
    std::string parseQualifiedMacroName();
    void requireTypeMacroHole(const std::string& name);
    std::pair<TypeFormKind, std::vector<std::string>> parseTypeMacroPattern();
    std::string holeName(const TypeRef& ref);
    std::optional<TypeTypeArm> parseTypeMacroArm(const std::string& macroName);
    void readTypeMacroOperand(const std::string& macroName, std::vector<std::string>& holes, std::vector<bool>& holeIsList);
    bool isTypeHole(const std::string& lexeme);
    MacroArm parseMacroArm();
    Expr parseMacroTemplateExpr();
    // parser_spec.cpp
    TopLevelSpec* parseSpec(bool isBridge = false, bool isDirect = false);
    std::vector<std::string> parseAssocNames();
    OrderedMap<std::string, TypeRef> parseAssocBindings();
    // parser_funcs.cpp
    FuncDecl parseFuncDecl(const FuncDeclOptions& o = {});
    void checkReturnedParams(const std::string& name, const std::vector<Param>& params, int32_t line);
    bool isSelfReceiverHeaderAhead();
    void skipScopeBodyIntroducer();
    void rejectLiteralSpread();
    FuncDecl bracketConstructorCall(const std::string& typeName, const std::vector<std::string>& typeParams, const Param& variadic, const Token& at, const Visibility& visibility, const Expr& whereClause, const TypeRef& declared);
    FuncDecl parseSpecBracketConstructor();
    std::vector<Stmt> parseMemberBody(const std::string& kind, const std::string& signature);
    void rejectInBraceReceiver(const std::string& declaration, const std::string& signature);
    std::string parseSplicedMemberName();
    std::string parseUseAsTemplate();
    Parser::ContractClauses parseContractClauses();
    std::vector<Stmt> applyContracts(const std::vector<Stmt>& body, const ContractClauses& contracts, bool rewriteYields = false);
    Stmt rewriteContractResults(const Stmt& stmt, const ContractClauses& contracts, bool rewriteYields);
    Stmt rewriteContractReturn(StmtReturn* node, const ContractClauses& contracts);
    Stmt rewriteContractYield(StmtYield* node, const ContractClauses& contracts);
    std::string consumeMemberName(const std::string& message);
    bool isAsyncFuncAt(int32_t index);
    bool isAsyncPropAt(int32_t index);
    std::string consumeIdentifierLike(const std::string& message);
    std::string oneElementTuple(int32_t line, const std::string& what);
    std::string freeFunctionName();
    [[noreturn]] void nameExpected(const std::string& message, const Token& found);
    // parser_types.cpp
    TypeRef parseTypeName(bool allowReceiverCallable = true);
    OwnershipOp borrowOp(TokenType sigil);
    std::vector<std::string> parseBorrowOrigins();
    TypeRef parseTypeSuffixes(const TypeRef& start);
    TypeRef parseTypeInfixes(const TypeRef& start);
    bool isNamedTypeMacroInvocationAhead(int32_t index);
    bool isNamedTypeMacroInvocationAhead();
    TypeRef finishNamedTypeMacro(const std::string& name, const std::vector<TypeRef>& firstArgs, bool firstIsList, const std::string& modifier, NamedTypeMacroCall::Form form);
    TypeRef parseNamedTypeMacroInvocation(const std::string& modifier = "");
    TypeRef parseTypeAtom();
    bool isAsyncCallableTypeAhead();
    bool isReactCallableTypeAhead();
    bool isCallableTypeAhead(int32_t from);
    bool isCallableTypeAhead();
    TypeRef parseCallableType(CallableKind kind = CallableKind::FUNC);
    TypeRefFunction* parseCallableTail(const std::vector<TypeRef>& receivers, CallableKind kind = CallableKind::FUNC, bool escaping = false);
    TypeRef parseCallableParamType();
    // parser_stmts.cpp
    std::vector<Stmt> parseStmts();
    Stmt parseSingleStmt(const std::string& what);
    std::vector<Stmt> parseThenBody(const std::string& what);
    std::vector<Stmt> parseThenOrBracedBody(const std::string& what);
    std::vector<Stmt> parseElseBody(const std::string& what);
    Stmt parseStmt();
    Stmt parseLabeledStmt();
    std::optional<std::string> parseOptionalLabel(const std::string& jump);
    Stmt parseWhile(const std::optional<std::string>& label = std::nullopt);
    std::optional<std::vector<Stmt>> parseLoopElse();
    Stmt withLoopElse(const Stmt& loop, const std::vector<Stmt>& elseBody, int32_t line, int32_t column);
    std::vector<Stmt> rewriteBreaksForElse(const std::vector<Stmt>& stmts, const std::string& flag);
    Stmt rewriteBreakForElse(const Stmt& stmt, const std::string& flag);
    std::vector<Stmt> retargetBreaks(const std::vector<Stmt>& stmts, const std::string& label);
    Stmt retargetBreak(const Stmt& stmt, const std::string& label);
    Stmt parseFor(const std::optional<std::string>& label = std::nullopt, bool allowElse = true);
    Parser::LoopPattern parseLoopDestructuring();
    std::vector<Stmt> destructuredPrologue(const LoopPattern& pattern, const std::string& row, const Token& start);
    Stmt parseLoop(const std::optional<std::string>& label = std::nullopt);
    Stmt parseBreak();
    Stmt parseContinue();
    StmtThrow* parseThrow();
    StmtPanic* parsePanicStmt(bool inlinePanic = false);
    StmtThrow* parseFailThrow();
    Stmt parseInlineSplice();
    StmtScope* parseUnsafe();
    Stmt parsePurge();
    Expr purgeCall(const Expr& value, const Token& start);
    StmtDefer* parseDefer();
    StmtDefer* parseFailDefer();
    StmtDefer* parseRescue();
    std::optional<ReactiveKind> reactiveLifetimeAt(int32_t index);
    StmtRemDecl* parseReactiveDecl(ReactiveKind kind);
    StmtEffect* parseEffect();
    StmtUsingContext* parseUsingContext();
    TopLevel parseThreadLocal(const Visibility& visibility = Visibility{});
    // parser_bindings.cpp
    Stmt parseTupleBinding(const Token& start, const std::string& keyword);
    bool isTupleAssignmentAhead();
    Stmt parseTupleAssignment();
    Stmt parseVarDecl();
    Stmt parseFinDecl();
    StmtLetDecl* parseLetDecl();
    Stmt parseLazyDecl();
    Stmt parseReturn();
    Stmt parseReturnTail(const Token& start);
    std::vector<Stmt> parseExpressionBody();
    Stmt parseReturnFor(const Token& start);
    std::pair<std::vector<Stmt>, bool> rewriteForReturns(const std::vector<Stmt>& stmts);
    std::pair<Stmt, bool> rewriteForReturn(const Stmt& stmt, bool tail);
    Stmt parseReturnedValue(const Token& start);
    Stmt parseReturnIf(const Token& start);
    std::vector<Stmt> parseReturnBranchBody(const std::string& what);
    std::optional<Stmt> parseBranchValueOrNull(const Token& at);
    Stmt parseReturnWhen(const Token& start);
    std::vector<Stmt> parseWhenReturnValue();
    bool branchBodyYields(const Stmt& stmt);
    std::string branchTailName(const Stmt& stmt);
    Expr parseWhenBranchValue();
    Expr parseSealableValue();
    bool startsExpression(const Token* token);
    Expr parseWhenExpr();
    Expr whenExprCondition(const Expr& scrutinee, const Expr& pattern, const Token& start, bool guardForm);
    Stmt parseExprStmt();
    Stmt nullConditionalCompound(const Expr& target, TokenType op, const Expr& value, const Token& start);
    Stmt nullConditionalIncDec(const Expr& target, TokenType op, const Token& start);
    std::vector<Expr> parseCallArgumentList();
    bool isNextLineNamedArgument(const ExprIdentifier& previous);
    Expr parseInlineForArgument();
    bool isModulePath(const Expr& expr);
    Stmt buildAssignment(const Expr& target, const Expr& value, int32_t line, int32_t column, std::optional<TokenType> compoundOp = std::nullopt);
    // parser_control.cpp
    StmtTry* parseTry();
    Stmt parseInline();
    Stmt parseInlineFor();
    std::vector<Stmt> parseInlineForBodyStatements(const std::vector<Token>& rendered, const Token& start);
    StmtInlineBlock* parseInlineScopeBlock();
    StmtInlineBlock* parseInlineBlock();
    Stmt parseDeepInlineStmt();
    StmtDeepInlineBlock* parseDeepInlineScopeBlock();
    StmtDeepInlineBlock* parseDeepInlineBlock();
    StmtDeepInlineIf* parseDeepInlineIf();
    StmtScope* parseScope();
    StmtNoInline* parseNoInline();
    StmtInlineFin* parseInlineFin();
    StmtInlineVar* parseInlineVar();
    StmtInlineLet* parseInlineLet();
    StmtInlineAssignment* parseInlineAssignment();
    StmtAssert* parseAssertStmt();
    StmtTrace* parseTraceStmt();
    StmtInlineAssert* parseInlineAssertStmt();
    StmtInlineTrace* parseInlineTraceStmt();
    Parser::TracePayload parseTracePayload(const Token& start, bool isInline);
    Expr defaultTraceLevel(const Token& start);
    StmtIf* parseIf();
    StmtInlineIf* parseInlineIf();
    std::vector<Stmt> parseBlock();
    Expr parseInitializer(const TypeRef& expectedType);
    Expr parseInitializer(const TypeAnnotation& type);
    // parser_exprs.cpp
    bool isPostfixCatch();
    Expr parseExpr();
    Expr parseAsIs();
    Expr parseOr();
    Expr parseAnd();
    Expr parseBitwiseOr();
    Expr parseBitwiseXor();
    Expr parseBitwiseAnd();
    Expr parseEquality();
    Expr parseComparison();
    Expr parseMembership();
    Expr parseSpaceship();
    Expr parseInfix();
    bool isAtInfixCandidate();
    bool isInfixCandidate();
    Expr parseShift();
    Expr parseRange();
    Expr parseAddition();
    Expr parseMultiplication();
    bool isReflectName(const std::string& name);
    bool isReflectTargetAhead();
    std::string parseErrorSetName(const std::string& context);
    Expr parseReflectTarget();
    Expr parseUnary();
    bool opensWhenArmAt(int32_t index);
    // parser_primary.cpp
    Expr parseIfExpr();
    Expr parseForExpr();
    std::pair<std::vector<Stmt>, bool> rewriteForYields(const std::vector<Stmt>& stmts, const std::string& temp);
    std::pair<Stmt, bool> rewriteForYield(const Stmt& stmt, const std::string& temp);
    Expr parsePrimary();
    Expr withinExpressionDelimiterExpr(const std::function<Expr()>& parse);
    bool startsNextElementLine();
    bool isReceiverLambdaAhead(int32_t startIndex);
    bool isReceiverLambdaAhead();
    ExprLambda* parseReceiverLambda(const std::vector<std::string>& typeParams = {}, const std::optional<std::string>& variadicTypeParam = std::nullopt, CallableKind kind = CallableKind::FUNC);
    ExprLambda* parseReceiverLambda(CallableKind kind);
    Param parseLambdaReceiverName();
    std::vector<CaptureExclusion> parseLambdaCaptureExclusions();
    bool isCaptureDefaultAhead();
    Capture parseCapture();
    ExprLambda* parseLambda(int32_t line, int32_t column, bool implicitIt = true, CallableKind kind = CallableKind::FUNC, const std::vector<std::string>& typeParams = {}, const std::optional<std::string>& variadicTypeParam = std::nullopt);
    ExprLambda* parseGenericLambda();
    bool isUntypedLambdaParamsAhead();
    // parser_enum.cpp
    TopLevelEnum* parseEnumDecl(const std::vector<Annotation>& annotations = {});
    TopLevelFail* parseFailDecl(const std::vector<Annotation>& annotations = {});
    void parseImplTargetParams(const std::string& target);
    std::vector<std::string> implParamNames(const std::string& target, const std::vector<TypeRef>& args, const Token& at);
    bool isConstParameterKind(const std::string& kind);
    std::vector<TypeRef> parseGenericTypeArgsIfPresent(bool declaresParams = false);
    bool matchTypeArgumentComma();
    void consumeTypeArgumentClose(const std::string& message);
    std::vector<Expr> parseTypeCallValueArgs();
    TypeRef parseTypeArg();
    std::string typeMethodSuffix(const TypeRef& type);
    std::string castMethodName(const TypeRef& type);
    std::string callbackTraitMethodName(const std::string& traitName, const std::vector<TypeRef>& traitArgs);
    std::string callbackTraitMethodName(const std::string& traitName, const std::vector<TypeRef>& traitArgs, const SpecCallback* callback);
    TypeAnnotation callbackTraitReturnType(const std::string& traitName, const std::vector<TypeRef>& traitArgs);
    std::vector<Param> callbackTraitParams(const std::string& traitName, const std::vector<TypeRef>& traitArgs);
    std::vector<Param> callbackTraitParams(const SpecCallback& callback, const std::vector<TypeRef>& traitArgs);
    MemberCallStyle callbackTraitCallStyle(const std::string& traitName);
    TypeRef substituteCallbackType(const TypeRef& type, const std::vector<std::string>& typeParams, const std::vector<TypeRef>& traitArgs);
    Parser::ContractHead parseContractName(const std::string& message);
    std::string parseImplTarget();
    std::vector<std::string> expandTypeListTargets(const std::vector<std::string>& targets);
    bool isImplTargetListAhead();
    std::vector<std::string> parseImplTargets();
    TopLevelImpl* queueExpandedImpls(const std::vector<ContractHead>& traits, const std::vector<std::string>& targets, const Token& start, const std::vector<Expr>& args, const std::vector<std::pair<std::string, Expr>>& namedArgs, bool isBridge, const std::vector<Annotation>& annotations, const std::vector<std::string>& typeParams, const std::optional<std::string>& variadicParam, bool hasBody);
    // parser_params.cpp
    std::vector<Param> parseParams(const std::optional<std::string>& variadicTypeParam = std::nullopt);
    TypeRef parseConstArgument(const std::optional<std::string>& enumName, const std::string& parameter);
    void refuseBounds(const TypeParams& params, const std::string& what, int32_t line, const std::string& why = "nothing checks a bound written there");
    void refuseOwnBounds(const TypeParams& params, const Expr& where, int32_t line);
    Expr withInlineBounds(const Expr& inlineBound, const Expr& written, int32_t line);
    Parser::TypeParams parseTypeParams();
    Expr parseWhereClause();
    Expr parseSpecBounds();
    bool isSpecBoundAhead();
    std::optional<int32_t> variadicMinLengthOf(const Expr& clause);
    std::optional<int32_t> parseVariadicWhereClause();
    // parser_postfix.cpp
    Expr parsePostfix(const Expr& initial = nullptr);
    Expr appendTrailingLambda(const Expr& target, ExprLambda* lambda, const std::vector<TypeRef>& typeArgs);
    bool isMacroInvokeAhead();
    std::vector<Expr> parseMacroInvokeArgs();
    bool closesGenericApplication(int32_t closingIndex);
    bool isGenericStaticAccessAhead();
    bool isGenericCallAhead();
    // parser_receivers.cpp
    TopLevel parseUse(bool exported = false, const Expr& condition = nullptr);
    ImportSpec parseImportSpec(const std::string& prefix = "");
    ImportSpec parseImportGroup(const std::string& base, const Token& clauseStart);
    ImportSpec parseWildcardImportGroup(const std::string& base, const Token& clauseStart, TokenType closer, const std::string& closerText);
    std::vector<std::string> parseImportExclusionNames();
    std::vector<FuncDecl> bindSelf(const std::vector<FuncDecl>& methods, const std::string& typeName);
    bool declMentionsSelf(const FuncDecl& m);
    bool mentionsSelf(const TypeRef& t);
    TypeRef selfTo(const TypeRef& t, TypeRefNamed* target);
    void requireObservingReceiver(const PropReceiver& receiver, const std::string& name, int32_t line);
    Parser::PropReceiver parsePropReceiver();
    std::pair<Parser::PropReceiver, std::vector<Param>> parseReceiverList();
    std::optional<Parser::PropReceiver> parseSelfReceiverShorthand();
    std::tuple<TypeRef, ParamModifier, bool> parseReceiverTypeAndModifier();
    std::optional<Parser::PropReceiver> parseImplicitMemberReceiver();
    std::optional<Parser::PropReceiver> parseExplicitMemberReceiver();
    std::optional<Parser::PropReceiver> parseTypedMemberReceiver(bool operatorHead = false);
    Parser::PropReceiver parseOperatorReceiver(const std::string& opName, bool allowImplicit);
    std::optional<Parser::PropReceiver> parseOwnedOperatorTypeReceiver();
    TopLevel parsePropAsFin(const std::vector<Annotation>& annotations, const Visibility& visibility, bool isReactive = false, bool isTask = false);
    TopLevelTypeAlias* parseTypeAlias(const std::vector<Annotation>& annotations = {});
    bool isTypePropAhead();
    int32_t indexAfterAnnotations(int32_t from);
    int32_t indexAfterBalanced(int32_t from, TokenType open, TokenType close);
    void parseTypeProp(const std::string& scopePrefix = "");
    // parser_typefn.cpp
    std::vector<TypeFunctionParam> parseTypePropParams(const std::string& owner);
    std::vector<TypeFunctionStmt> parseTypeFunctionBlock();
    TypeFunctionStmt parseTypeFunctionStmt();
    void consumeTypeAnnotationInTypeProp(const std::string& what);
    bool isValueFlagConditionAhead();
    TypeFunctionExpr parseTypeFunctionExpr();
    TypeFunctionExpr parseTypePropCall(const std::string& name);
    std::optional<bool> booleanArmLiteral();
    TypeFunctionCondition typeFunctionArmCondition(const std::pair<std::string, TypeFunctionExpr>& scrutinee, std::optional<bool> matched);
    TypeFunctionCondition parseTypeFunctionCondition();
    // parser_when.cpp
    StmtWhen* parseWhen();
    Expr withoutTrailingLambda(const std::function<Expr()>& parse);
    void withoutTrailingLambdaRun(const std::function<void()>& parse);
    std::vector<Expr> withinExpressionDelimiter(const std::function<std::vector<Expr>()>& parse);
    Parser::WhenHead parseWhenHead();
    std::vector<Stmt> parseWhenStatementBody();
    std::vector<WhenBranch> whenBranches(const WhenParts<std::vector<Stmt>>& parts, const Token& start);
    Expr parseWhenPattern(const Expr& scrutinee);
    TopLevelInlineAssert* parseTopLevelInlineAssert();
    TopLevelInlineTrace* parseTopLevelInlineTrace();
    bool isModuleWordAt(int32_t index);
    bool isModuleHeaderAhead();
    std::string parseModule();
    // METHODS END
};

}  // namespace azora
