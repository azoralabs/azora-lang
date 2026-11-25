// Parser.kt lines 3455-4160: `impl` blocks and their members.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"

namespace azora {

namespace {

TypeRef selfType(Arena& arena) {
    TypeRefNamed self;
    self.name = "Self";
    return make(arena, self);
}

TypeRef namedType(Arena& arena, const std::string& name, bool synthesized = false) {
    TypeRefNamed ref;
    ref.name = name;
    ref.synthesized = synthesized;
    return make(arena, ref);
}

}  // namespace

/** `impl Type { methods }` or `impl Trait for Type { methods }`. */
TopLevelImpl* Parser::parseImpl(bool isBridge /*= false*/, const std::vector<Annotation>& annotations /*= {}*/) {
    Token start = peek();
    consume(TokenType::IMPL, "Expected 'impl'");
    // Type parameters belong after the name they parametrize.
    if (check(TokenType::LESS)) {
        fail("Type parameters come after the type they belong to at line " + str(start.line) +
             ": write 'impl Type<T>' or 'impl Spec for Type<T>', not 'impl<T> …'");
    }
    if (isSpecStaticImplAhead()) {
        fail("the trailing '::' implementation form was removed at line " + str(start.line) +
             "; write 'impl Spec for Type { ... }' and declare receiver-free members normally");
    }
    if (isStaticImplAhead()) {
        std::string typeName = peek().lexeme;
        fail("the trailing '::' implementation form was removed at line " + str(start.line) + "; write 'impl " +
             typeName + " { ... }' and declare receiver-free members normally");
    }
    TypeParams implTypeParams;
    // `impl ctor() for T { self! -> … }` was removed.
    if (check(TokenType::CTOR) || check(TokenType::DTOR)) {
        Token removed = peek();
        std::string member = removed.type == TokenType::CTOR ? "ctor" : "dtor";
        std::string replacement = member == "ctor" ? "ctor .(…)" : "dtor .()";
        fail("'impl " + member + "(…) for T' was removed at line " + str(removed.line) + ": write '" + replacement +
             "' inside 'impl T { … }'");
    }
    // `impl as T for Type { self& -> … }` was removed; `Cast<To>` says the same.
    if (check(TokenType::AS)) {
        Token asStart = peek();
        fail("'impl as <Type> for <Type>' was removed at line " + str(asStart.line) +
             "; write 'impl Cast<Type> for <Type> { prop castValue[self: Self&]: Type { … } }' (or CheckedCast for "
             "'as?', BitCast for 'as*')");
    }
    // `impl oper… for Type { … }` was removed.
    if (check(TokenType::OPER)) {
        fail("'impl oper… for Type' was removed at line " + str(peek().line) +
             "; declare the operator beside its type, as 'oper[] Type&.(index: Int): T { … }'");
    }
    // `impl (Byte, UByte) { … }` - one body, given to each type named.
    if (isImplTargetListAhead()) {
        std::vector<std::string> targets = expandTypeListTargets(parseImplTargets());
        skipNewlines();
        consume(TokenType::L_BRACE, "Expected '{' after 'impl (<types>)'");
        skipNewlines();
        std::vector<Token> bodyTokens = captureBraceBody();
        consumeNewline();
        // Re-read the body as `impl <target> { … }` per target.
        std::vector<TopLevel> produced;
        for (const std::string& target : targets) {
            std::vector<Token> wrapper{
                Token{TokenType::IMPL, "impl", start.line, start.column, {}},
                Token{TokenType::IDENTIFIER, target, start.line, start.column, {}},
                Token{TokenType::L_BRACE, "{", start.line, start.column, {}},
            };
            wrapper.insert(wrapper.end(), bodyTokens.begin(), bodyTokens.end());
            wrapper.push_back(Token{TokenType::R_BRACE, "}", start.line, start.column, {}});
            wrapper.push_back(Token{TokenType::NEWLINE, "\n", start.line, start.column, {}});
            wrapper.push_back(Token{TokenType::EOF_, "", start.line, start.column, {}});
            Parser sub(arena, wrapper, typeListEnv, declaredEnums, false, typeListScope);
            Program parsed = sub.parse();
            produced.insert(produced.end(), parsed.items.begin(), parsed.items.end());
        }
        TopLevelImpl* firstImpl = nullptr;
        for (const TopLevel& item : produced) {
            if (TopLevelImpl* impl = as<TopLevelImpl>(item)) {
                firstImpl = impl;
                break;
            }
        }
        for (const TopLevel& item : produced) {
            if (!(firstImpl && same(item, TopLevel(firstImpl)))) pendingTopLevels.push_back(item);
        }
        if (firstImpl) return firstImpl;
        TopLevelImpl empty;
        empty.typeName = first(targets);
        empty.line = start.line;
        empty.column = start.column;
        implDefaults(empty);
        return make(arena, empty);
    }
    bool isPackImpl = match(TokenType::PACK);
    std::vector<ContractHead> traitHeads;
    bool isRow = parseParenthesizedList("implementation spec", [&] {
        ContractHead head = parseContractName("Expected spec or decorator name in implementation list");
        head.args = parseGenericTypeArgsIfPresent();
        traitHeads.push_back(head);
    });
    if (!isRow) {
        ContractHead head = parseContractName("Expected type or trait name after 'impl'");
        head.args = parseGenericTypeArgsIfPresent(true);
        traitHeads = {head};
    }
    // `impl Box<T: Shape> { … }` - the head declares `T` and bounds it.
    Expr headBounds = lastDeclaredBounds;
    lastDeclaredBounds = nullptr;
    ContractHead firstHead = first(traitHeads);
    std::string firstName = firstHead.name;
    std::vector<TypeRef> firstArgs = firstHead.args;
    std::vector<Expr> decoratorArgs;
    std::vector<std::pair<std::string, Expr>> decoratorNamedArgs;
    if (check(TokenType::L_PAREN)) {
        auto parsed = parseDecoratorArguments();
        decoratorArgs = parsed.first;
        decoratorNamedArgs = parsed.second;
    }
    if (traitHeads.size() > 1 && (!decoratorArgs.empty() || !decoratorNamedArgs.empty())) {
        fail("Decorator implementation values require a single decorator at line " + str(start.line));
    }
    std::string typeName = firstName;
    std::optional<std::string> traitName;
    std::vector<TypeRef> traitArgs;
    std::vector<std::string> implementationTargets{firstName};
    if (match(TokenType::FOR)) {
        if (isPackImpl) fail("'impl pack' cannot be used for prot implementations at line " + str(peek().line));
        // The spec's arguments name types; they declare nothing to bound.
        if (headBounds) {
            fail("'impl " + firstName +
                 "<…>' bounds a type parameter in the spec's arguments, which declare nothing; the target declares the "
                 "parameters at line " +
                 str(start.line));
        }
        traitName = firstName;
        traitArgs = firstArgs;
        implementationTargets = expandTypeListTargets(parseImplTargets());
        typeName = first(implementationTargets);
    } else if (traitHeads.size() > 1) {
        fail("Decorator implementation lists require 'for Target' at line " + str(start.line));
    } else if (!decoratorArgs.empty() || !decoratorNamedArgs.empty()) {
        fail("Decorator implementation arguments require 'for Type' at line " + str(start.line));
    }
    // The target's own arguments are its parameters.
    if (traitName) {
        implTypeParams.names = lastImplTypeParams;
        implTypeParams.variadic = lastImplVariadicParam;
    } else {
        implTypeParams.names = implParamNames(firstName, firstArgs, start);
        for (const TypeRef& arg : firstArgs) {
            TypeRefNamed* named = as<TypeRefNamed>(arg);
            if (named && named->variadic) {
                implTypeParams.variadic = named->name;
                break;
            }
        }
    }
    // `impl Iterator for Rows assoc Item = Int { … }`.
    OrderedMap<std::string, TypeRef> assocBindings = parseAssocBindings();
    if (!check(TokenType::L_BRACE)) {
        if (traitName) {
            fail("manual implementation 'impl " + *traitName + " for " + typeName +
                 "' requires a body; add '{ ... }' or request compiler generation with 'derive " + *traitName + " for " +
                 typeName + "' at line " + str(start.line));
        }
        consumeNewline();
        TopLevelImpl impl;
        impl.typeName = typeName;
        impl.traitName = traitName;
        impl.line = start.line;
        impl.column = start.column;
        impl.isPackImpl = isPackImpl;
        impl.traitArgs = traitArgs;
        impl.traitQualifier = firstHead.qualifier;
        impl.traitLine = firstHead.line;
        impl.traitColumn = firstHead.column;
        impl.traitLength = firstHead.length;
        impl.decoratorArgs = decoratorArgs;
        impl.decoratorNamedArgs = decoratorNamedArgs;
        impl.isBridge = isBridge;
        impl.typeParams = implTypeParams.names;
        impl.variadicParam = implTypeParams.variadic;
        impl.hasBody = false;
        impl.assocBindings = assocBindings;
        implDefaults(impl);
        return make(arena, impl);
    }
    consume(TokenType::L_BRACE, "Expected '{' after impl type");
    skipNewlines();
    // Decorator applications are manual implementations too; grouped
    // decorators and grouped targets expand to the cross-product.
    if (traitName && check(TokenType::R_BRACE) &&
        (traitHeads.size() > 1 || implementationTargets.size() > 1 || contains(typeName, '.'))) {
        consume(TokenType::R_BRACE, "Expected '}' after implementation body");
        consumeNewline();
        return queueExpandedImpls(traitHeads, implementationTargets, start, decoratorArgs, decoratorNamedArgs, isBridge,
                                  annotations, implTypeParams.names, implTypeParams.variadic, true);
    }
    // `impl Spec for scope::Type { members }` names a scope-qualified type.
    if (traitName && implementationTargets.size() == 1 && std::count(typeName.begin(), typeName.end(), '.') == 1 &&
        !endsWith(typeName, ".*")) {
        typeName = substringAfter(typeName, ".");
        implementationTargets = {typeName};
    }
    if (traitHeads.size() > 1 || implementationTargets.size() > 1 || contains(typeName, '.')) {
        fail("Grouped and member-target implementations must have an empty body at line " + str(start.line));
    }
    // The in-brace receiver the bracket redesign replaced is now an error.
    if (traitName && isSelfReceiverHeaderAhead()) {
        fail("an in-brace receiver is not a declaration at line " + str(start.line) +
             "; declare the member with its receiver in brackets, as 'func into[self: Self&](): T { … }'");
    }
    std::vector<FuncDecl> methods;
    // What `Self` names in this body, for a macro fragment that splices it.
    std::optional<std::string> savedImplTypeName = currentImplTypeName;
    currentImplTypeName = typeName;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        // A receiver-free compile-time function returning `Type` is a static type
        // computation owned by this impl.
        if (isTypePropAhead()) {
            if (traitName) {
                fail("a static type computation cannot satisfy an instance spec at line " + str(peek().line) +
                     "; declare it in 'impl " + typeName + " { ... }'");
            }
            parseTypeProp(typeName);
            continue;
        }
        std::vector<Annotation> memberAnnotations = parseAnnotations();
        Token methodStart = peek();
        // `inline for op in @arr["+", "-"] { … }` inside an impl generates MEMBERS.
        if (check(TokenType::INLINE) && peekNextIs(TokenType::FOR)) {
            std::vector<FuncDecl> generated = parseInlineMemberFor(typeName);
            methods.insert(methods.end(), generated.begin(), generated.end());
            continue;
        }
        bool isDeepInline = check(TokenType::DEEPINLINE);
        bool isInline = match(TokenType::INLINE) || match(TokenType::DEEPINLINE);
        bool isVirt = false;
        Visibility visibility = parseVisibility();
        // `react prop` / `react func` / `react async func` - a reactive member.
        bool isReactiveMember = match(TokenType::REACT);
        // `async prop` - a property whose body suspends.
        bool isAsyncProp = isAsyncPropAt(current);
        if (isAsyncProp) advance();
        if (isReactiveMember && !check(TokenType::PROP) && !check(TokenType::FUNC) && !check(TokenType::CTOR) &&
            !isAsyncFuncAt(current)) {
            fail("Expected 'prop', 'func', 'ctor', 'async prop', or 'async func' after 'react' at line " +
                 str(peek().line));
        }
        if (check(TokenType::PROP)) {
            advance();
            TypeParams propTypeParams = parseTypeParams();
            std::optional<PropReceiver> prefixReceiver = parseImplicitMemberReceiver();
            std::string propName = consumeMemberName("Expected property name");
            if (check(TokenType::L_BRACKET)) {
                fail("property receivers no longer use brackets; write 'prop &." + propName + ": T' at line " +
                     str(peek().line));
            }
            PropReceiver propReceiver = prefixReceiver ? *prefixReceiver
                                                       : PropReceiver{"self", selfType(arena), ParamModifier::SHARED, false};
            requireObservingReceiver(propReceiver, propName, start.line);
            bool propTypeDeclared = check(TokenType::COLON);
            // A property is part of its type's surface and must declare its type.
            if (!propTypeDeclared) {
                fail("property '" + propName + "' must declare its type at line " + str(methodStart.line) +
                     ": write 'prop " + propName + "[…]: T = …'");
            }
            TypeAnnotation propType;
            if (match(TokenType::COLON)) propType = explicitType(parseTypeName());
            // A property may carry its own constraint.
            Expr propWhere = parseWhereClause();
            refuseOwnBounds(propTypeParams, propWhere, methodStart.line);
            // A `?!` in the property's type binds the error set for
            // `return .Variant(payload)` in its body.
            std::vector<std::string> savedPropFailSets = currentFailSets;
            currentFailSets = {};
            if (TypeRefFailable* failable = as<TypeRefFailable>(propType.ref)) currentFailSets = failable->errSets;
            FuncDecl decl;
            decl.name = propName;
            decl.returnType = propType;
            decl.isInline = false;
            decl.typeParams = propTypeParams.names;
            decl.line = methodStart.line;
            decl.column = methodStart.column;
            decl.annotations = memberAnnotations;
            decl.isTask = isAsyncProp;
            decl.isReactive = isReactiveMember;
            decl.visibility = visibility;
            decl.returnTypeDeclared = propTypeDeclared;
            decl.receiverModifier = propReceiver.modifier;
            decl.receiverName = propReceiver.name;
            decl.declaresReceiver = propReceiver.declared;
            decl.whereClause = propWhere;
            decl.memberCallStyle = MemberCallStyle::PROPERTY;
            if (match(TokenType::EQUAL)) {
                // `prop name: T = expr` - expression-body property (returns the expression).
                Expr expr = parseExpr();
                consumeNewline();
                decl.body = {make(arena, StmtReturn{.value = expr, .line = expr.line(), .column = expr.column()})};
                methods.push_back(decl);
            } else {
                ContractClauses contracts = parseContractClauses();
                std::vector<Stmt> propBody = parseMemberBody("prop", "prop &." + propName + ": T { … }");
                decl.body = applyContracts(propBody, contracts);
                methods.push_back(decl);
            }
            currentFailSets = savedPropFailSets;
        } else if (check(TokenType::BRIDGE)) {
            // `bridge prop<D> name: T` / `bridge func<D> name(params): T` - a
            // bodyless, compiler-provided member.
            advance();
            bool bridgeDeep = check(TokenType::DEEPINLINE);
            bool bridgeInline = match(TokenType::INLINE) || match(TokenType::DEEPINLINE);
            if (check(TokenType::FIN)) {
                // `bridge fin size: Int` - a compiler-provided constant on the type.
                advance();
                std::string finName = consumeIdentifierLike("Expected name after 'bridge fin'");
                consume(TokenType::COLON, "A 'bridge fin' must declare its type: write 'bridge fin " + finName + ": Type'");
                TypeAnnotation finType = explicitType(parseTypeName());
                consumeNewline();
                FuncDecl decl;
                decl.name = finName;
                decl.returnType = finType;
                decl.isInline = false;
                decl.line = methodStart.line;
                decl.column = methodStart.column;
                decl.annotations = memberAnnotations;
                decl.visibility = visibility;
                decl.memberCallStyle = MemberCallStyle::PROPERTY;
                methods.push_back(decl);
            } else if (check(TokenType::PROP)) {
                advance();
                TypeParams tp = parseTypeParams();
                refuseBounds(tp, "a bridge property", methodStart.line);
                std::optional<PropReceiver> prefixReceiver = parseImplicitMemberReceiver();
                std::string propName = consumeIdentifierLike("Expected property name after 'bridge prop'");
                if (check(TokenType::L_BRACKET)) {
                    fail("bridge property receivers no longer use brackets; write 'bridge prop &." + propName +
                         ": T' at line " + str(peek().line));
                }
                PropReceiver bridgeReceiver =
                    prefixReceiver ? *prefixReceiver : PropReceiver{"self", selfType(arena), ParamModifier::SHARED, false};
                requireObservingReceiver(bridgeReceiver, propName, peek().line);
                TypeAnnotation propType;
                if (match(TokenType::COLON)) {
                    skipNewlines();
                    propType = explicitType(parseTypeName());
                }
                consumeNewline();
                FuncDecl decl;
                decl.name = propName;
                decl.returnType = propType;
                decl.isInline = bridgeInline;
                decl.typeParams = tp.names;
                decl.line = methodStart.line;
                decl.column = methodStart.column;
                decl.annotations = memberAnnotations;
                decl.visibility = visibility;
                decl.receiverModifier = bridgeReceiver.modifier;
                decl.receiverName = bridgeReceiver.name;
                decl.declaresReceiver = bridgeReceiver.declared;
                decl.memberCallStyle = MemberCallStyle::PROPERTY;
                decl.isDeepInline = bridgeDeep;
                methods.push_back(decl);
            } else if (check(TokenType::FUNC)) {
                advance();
                TypeParams tp = parseTypeParams();
                refuseBounds(tp, "a bridge function", methodStart.line);
                std::optional<PropReceiver> prefixReceiver = parseImplicitMemberReceiver();
                std::string fnName = consumeIdentifierLike("Expected function name after 'bridge func'");
                consume(TokenType::L_PAREN, "Expected '(' after bridge function name");
                std::vector<Param> params = parseParams();
                consume(TokenType::R_PAREN, "Expected ')' after bridge parameters");
                TypeAnnotation ret;
                if (match(TokenType::COLON)) ret = explicitType(parseTypeName());
                else ret = explicitType(namedType(arena, "Unit", true));
                consumeNewline();
                FuncDecl decl;
                decl.name = fnName;
                decl.params = params;
                decl.returnType = ret;
                decl.isInline = bridgeInline;
                decl.typeParams = tp.names;
                decl.line = methodStart.line;
                decl.column = methodStart.column;
                decl.annotations = memberAnnotations;
                decl.visibility = visibility;
                decl.receiverModifier = prefixReceiver ? prefixReceiver->modifier : ParamModifier::SHARED;
                decl.receiverName = prefixReceiver ? prefixReceiver->name : "self";
                decl.declaresReceiver = prefixReceiver.has_value();
                decl.isDeepInline = bridgeDeep;
                methods.push_back(decl);
            } else {
                fail("Expected 'fin', 'prop' or 'func' after 'bridge' in impl block at line " + str(peek().line));
            }
        } else if (check(TokenType::CTOR)) {
            // `ctor .(…) { … }` - a constructor is a member of the type it builds.
            Token ctorStart = advance();
            TypeParams ctorTypeParams = parseTypeParams();
            std::optional<PropReceiver> contextualPrefix = parseExplicitMemberReceiver();
            PropReceiver recv;
            std::vector<Param> scopeReceivers;
            if (contextualPrefix) {
                // The constructed value is always the implicit `self`; a prefix
                // tuple names the contexts in which this ctor builds a value.
                Param firstParam;
                firstParam.name = contextualPrefix->name;
                firstParam.type = contextualPrefix->type ? contextualPrefix->type : selfType(arena);
                firstParam.modifier = contextualPrefix->modifier;
                std::vector<Param> contexts{firstParam};
                contexts.insert(contexts.end(), pendingMemberContextReceivers.begin(), pendingMemberContextReceivers.end());
                pendingMemberContextReceivers = {};
                for (const Param& context : contexts) {
                    if (context.name == "self") {
                        fail("a constructor context must have its own name; 'self' is the constructed value at line " +
                             str(ctorStart.line));
                    }
                }
                recv = PropReceiver{"self", selfType(arena), ParamModifier::EXCLUSIVE};
                scopeReceivers = contexts;
            } else if (match(TokenType::DOT)) {
                recv = PropReceiver{"self", selfType(arena), ParamModifier::EXCLUSIVE};
            } else if (check(TokenType::L_BRACKET)) {
                fail("constructors no longer declare bracket receivers; write 'ctor .(…)' at line " + str(peek().line));
            } else if (check(TokenType::AMP) || check(TokenType::BANG)) {
                fail("constructors cannot borrow their result; use 'ctor .(…)' at line " + str(peek().line));
            } else {
                recv = PropReceiver{"self", selfType(arena), ParamModifier::SHARED, false};
            }
            std::vector<Param> ctorParams;
            if (match(TokenType::L_PAREN)) {
                if (!check(TokenType::R_PAREN)) ctorParams = parseParams();
                consume(TokenType::R_PAREN, "Expected ')' after ctor parameters");
            }
            // A ctor usually yields the value it fills, and says nothing.
            TypeAnnotation ctorReturn;
            if (match(TokenType::COLON)) {
                ctorReturn = explicitType(parseTypeName());
            } else if (check(TokenType::QMARK_BANG)) {
                // `ctor .(…) ?! E` - fills the value it is a member of, and may fail.
                ctorReturn = explicitType(parseTypeSuffixes(namedType(arena, "Unit", true)));
            }
            // `ctor .(…) * count { … }` - the ctor that `.(…) * n` selects.
            bool repeated = false;
            if (match(TokenType::STAR)) {
                std::string bound = consumeIdentifierLike("Expected 'count' after '*' in a ctor declaration");
                if (bound != REPEAT_COUNT_NAME) {
                    fail("a repeated ctor binds its repetition as '" + REPEAT_COUNT_NAME + "' at line " +
                         str(ctorStart.line) + ", not '" + bound + "'");
                }
                repeated = true;
            }
            // `where T is Keyed` - decided for the type arguments a construction chooses.
            Expr ctorWhere = withInlineBounds(ctorTypeParams.boundClause, parseWhereClause(), ctorStart.line);
            ContractClauses contracts = parseContractClauses();
            std::vector<Stmt> ctorBody = parseMemberBody("ctor", "ctor .(…) { … }");
            // A repetition of none builds nothing, so the count is checked ahead of the body.
            std::vector<Stmt> repeatContract;
            if (repeated) {
                Expr countRef = make(arena, ExprIdentifier{REPEAT_COUNT_NAME, ctorStart.line, ctorStart.column});
                Expr one = make(arena, ExprIntLiteral{.value = 1, .line = ctorStart.line, .column = ctorStart.column});
                Expr comparison = make(arena, ExprBinary{countRef, TokenType::GREATER_EQUAL, one, ctorStart.line,
                                                         ctorStart.column});
                Expr message = make(arena, ExprStringLiteral{REPEAT_COUNT_NAME + " must be at least 1", ctorStart.line,
                                                             ctorStart.column});
                repeatContract.push_back(make(arena, StmtAssert{comparison, message, ctorStart.line, ctorStart.column}));
            }
            std::vector<Param> repeatParam;
            if (repeated) {
                Param count;
                count.name = REPEAT_COUNT_NAME;
                count.type = namedType(arena, "Int");
                repeatParam.push_back(count);
            }
            FuncDecl decl;
            decl.name = "ctor";
            decl.params = concat(concat(scopeReceivers, ctorParams), repeatParam);
            decl.returnType = ctorReturn;
            decl.body = concat(repeatContract, applyContracts(ctorBody, contracts));
            decl.isInline = false;
            decl.typeParams = ctorTypeParams.names;
            decl.line = ctorStart.line;
            decl.column = ctorStart.column;
            decl.annotations = memberAnnotations;
            decl.isReactive = isReactiveMember;
            decl.visibility = visibility;
            decl.receiverModifier = recv.modifier;
            decl.receiverName = recv.name;
            decl.declaresReceiver = recv.declared;
            decl.variadicParam = ctorTypeParams.variadic;
            decl.whereClause = ctorWhere;
            decl.constParams = ctorTypeParams.constParams;
            decl.constDefaults = ctorTypeParams.constDefaults;
            decl.constEnums = ctorTypeParams.constEnums;
            decl.contextualParams = (int32_t)scopeReceivers.size();
            decl.isRepeated = repeated;
            methods.push_back(decl);
            // A variadic constructor is also what a `[…]` literal calls where
            // this type is expected.
            if (ctorParams.size() == 1 && ctorParams[0].variadic && !traitName && !repeated && scopeReceivers.empty() &&
                ctorTypeParams.names.empty()) {
                methods.push_back(bracketConstructorCall(typeName, implTypeParams.names, ctorParams[0], ctorStart,
                                                         visibility, ctorWhere, ctorReturn.ref));
            }
        } else if (check(TokenType::DTOR)) {
            // `dtor .() { … }` - instance teardown, with no parameters to take.
            Token dtorStart = advance();
            bool dotReceiver = match(TokenType::DOT);
            PropReceiver recv;
            if (dotReceiver) {
                consume(TokenType::L_PAREN, "Expected '()' after 'dtor .'");
                consume(TokenType::R_PAREN, "A destructor takes no ordinary parameters");
                recv = PropReceiver{"self", selfType(arena), ParamModifier::EXCLUSIVE};
            } else {
                if (check(TokenType::L_BRACKET) || check(TokenType::AMP) || check(TokenType::BANG)) {
                    fail("destructors cannot borrow their receiver; use 'dtor .()' at line " + str(peek().line));
                }
                consume(TokenType::L_PAREN, "Expected '()' for a static destructor or '.()' for an instance destructor");
                consume(TokenType::R_PAREN, "A destructor takes no parameters");
                recv = PropReceiver{"self", selfType(arena), ParamModifier::SHARED, false};
            }
            std::vector<Stmt> dtorBody = parseMemberBody("dtor", "dtor .() { … }");
            FuncDecl decl;
            decl.name = "dtor";
            decl.body = dtorBody;
            decl.isInline = false;
            decl.line = dtorStart.line;
            decl.column = dtorStart.column;
            decl.annotations = memberAnnotations;
            decl.visibility = visibility;
            decl.receiverModifier = recv.modifier;
            decl.receiverName = recv.name;
            decl.declaresReceiver = recv.declared;
            methods.push_back(decl);
        } else if (check(TokenType::OPER)) {
            Token operStart = advance();
            // `oper[] &.(i: Int): T` / `oper[]= !.` - the index operators.
            std::string opName;
            if (check(TokenType::L_BRACKET) && peekNextIs(TokenType::R_BRACKET)) {
                advance();
                advance();
                opName = match(TokenType::EQUAL) ? "indexSet" : "index";
            } else {
                opName = parseOperatorName();
            }
            // `oper as<U> &.` - an operator may take type parameters.
            TypeParams operTypeParams = parseTypeParams();
            std::string operSuffix;
            PropReceiver recv = parseOperatorReceiver(opName, true);
            std::vector<Param> operands = parseOperatorOperands(opName);
            // `Self` in an operand is the implementing type, and the overload key
            // has to say so.
            if (isBareLookupOperator(opName)) {
                operSuffix = "";
            } else {
                operSuffix = replaceAll(operatorOverloadSuffix(opName, recv.modifier, operands), "@Self", "@" + typeName);
            }
            TypeAnnotation ret;
            if (match(TokenType::COLON)) {
                skipNewlines();
                ret = explicitType(parseTypeName());
            }
            // `inline if <cond> { inline "?! MathError" }` - a fragment spliced into the signature.
            if (std::optional<std::vector<Token>> fragment = parseSignatureFragment()) {
                ret = applySignatureFragment(ret, *fragment, operStart);
            }
            // An operator may carry its own constraint, like any member.
            Expr operWhere = withInlineBounds(operTypeParams.boundClause, parseWhereClause(), operStart.line);
            // `return .Variant(payload)` in the body needs the error set.
            std::vector<std::string> savedOperFailSets = currentFailSets;
            currentFailSets = {};
            if (TypeRefFailable* failable = as<TypeRefFailable>(ret.ref)) currentFailSets = failable->errSets;
            skipNewlines();
            refuseOperatorStep();
            skipNewlines();
            FuncDecl decl;
            decl.name = operatorMemberName(opName) + operSuffix;
            decl.params = operands;
            decl.returnType = ret;
            decl.isInline = false;
            decl.typeParams = operTypeParams.names;
            decl.line = operStart.line;
            decl.column = operStart.column;
            decl.annotations = memberAnnotations;
            decl.visibility = visibility;
            decl.receiverModifier = recv.modifier;
            decl.receiverName = recv.name;
            decl.whereClause = operWhere;
            decl.constParams = operTypeParams.constParams;
            if (!check(TokenType::L_BRACE) && !check(TokenType::EQUAL)) {
                // Bodyless: the backend supplies it, like `bridge func`.
                consumeNewline();
                currentFailSets = savedOperFailSets;
                methods.push_back(decl);
                continue;
            }
            std::vector<Stmt> operBody;
            if (match(TokenType::EQUAL)) {
                skipNewlines();
                Expr value = parseExpr();
                operBody = {make(arena, StmtReturn{.value = value, .line = value.line(), .column = value.column()})};
                consumeNewline();
            } else {
                consume(TokenType::L_BRACE, "Expected '{' after operator declaration");
                skipNewlines();
                operBody = parseBlock();
                consume(TokenType::R_BRACE, "Expected '}' after operator body");
            }
            currentFailSets = savedOperFailSets;
            consumeNewline();
            decl.body = operBody;
            methods.push_back(decl);
        } else if (check(TokenType::FUNC)) {
            methods.push_back(parseFuncDecl({.isInline = isInline, .annotations = memberAnnotations, .isVirtual = isVirt,
                                             .isReactive = isReactiveMember, .visibility = visibility,
                                             .inImplBlock = true, .isDeepInline = isDeepInline}));
        } else if (isAsyncFuncAt(current)) {
            methods.push_back(parseFuncDecl({.isInline = isInline, .annotations = memberAnnotations, .isVirtual = isVirt,
                                             .isTask = true, .isReactive = isReactiveMember, .visibility = visibility,
                                             .inImplBlock = true, .isDeepInline = isDeepInline}));
        } else if (check(TokenType::FIN) || check(TokenType::VAR) || check(TokenType::VAL) || check(TokenType::LET)) {
            // `impl Byte { inline fin minValue: Int = -128 }` - a binding that
            // belongs to the type, leaving as `Byte__minValue`.
            pendingTopLevels.push_back(mangleTopLevel(parseTopLevelBlockItem(isInline), typeName));
        } else {
            fail("Expected 'prop', 'func', 'async prop', 'async func', 'oper', 'ctor', 'dtor', 'fin', 'var', 'val', "
                 "'let', or 'bridge' in impl block at line " +
                 str(peek().line));
        }
        skipNewlines();
    }
    currentImplTypeName = savedImplTypeName;
    consume(TokenType::R_BRACE, "Expected '}' after impl methods");
    consumeNewline();
    // The head's bounds are every member's.
    if (headBounds) {
        for (FuncDecl& member : methods) member.whereClause = withInlineBounds(headBounds, member.whereClause, member.line);
    }
    // A member of a plain `impl T { }` that wrote no receiver belongs to the
    // type, not to a value of it, and leaves as `Type__member`.
    std::vector<FuncDecl> statics;
    std::vector<FuncDecl> instanceOnly;
    for (const FuncDecl& method : methods) {
        if (!method.declaresReceiver) statics.push_back(method);
        else instanceOnly.push_back(method);
    }
    for (const FuncDecl& decl : bindSelf(statics, typeName)) {
        pendingTopLevels.push_back(
            mangleTopLevel(withImplTypeParams(make(arena, TopLevelFunc{decl}), implTypeParams.names), typeName));
    }
    // A spec impl retains its declarations for conformance checking.
    std::vector<FuncDecl> instanceMethods = traitName ? methods : instanceOnly;
    TopLevelImpl impl;
    impl.typeName = typeName;
    impl.methods = bindSelf(instanceMethods, typeName);
    impl.traitName = traitName;
    impl.line = start.line;
    impl.column = start.column;
    impl.isPackImpl = isPackImpl;
    impl.traitArgs = traitArgs;
    impl.decoratorArgs = decoratorArgs;
    impl.decoratorNamedArgs = decoratorNamedArgs;
    impl.annotations = annotations;
    impl.isBridge = isBridge;
    impl.typeParams = implTypeParams.names;
    impl.variadicParam = implTypeParams.variadic;
    impl.traitQualifier = firstHead.qualifier;
    impl.traitLine = firstHead.line;
    impl.traitColumn = firstHead.column;
    impl.traitLength = firstHead.length;
    impl.assocBindings = assocBindings;
    implDefaults(impl);
    return make(arena, impl);
}

}  // namespace azora
