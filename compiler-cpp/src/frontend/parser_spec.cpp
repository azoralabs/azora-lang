// Parser.kt lines 5500-5895: `spec` declarations and associated types.
#include "frontend/parser.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"

namespace azora {

/** `spec Name { func method(params): Ret ... }` or the compact callback form. */
TopLevelSpec* Parser::parseSpec(bool isBridge /*= false*/, bool isDirect /*= false*/) {
    Token start = peek();
    consume(TokenType::SPEC, "Expected 'spec'");
    std::string name = consume(TokenType::IDENTIFIER, "Expected spec name").lexeme;
    TypeParams typeParams = parseTypeParams();  // `spec Comparable<T>` - type parameters accepted (erased for now)
    bool hasCallParens = match(TokenType::L_PAREN);
    std::vector<Param> callbackParams;
    if (hasCallParens) {
        callbackParams = parseParams();
        consume(TokenType::R_PAREN, "Expected ')' after spec callback parameters");
    }
    const std::string removedUseRemedy =
        "a spec's call-site name is declared inside the spec: 'spec Into<T> { prop<T> into[self: Self&]: T; inline prop "
        "to${T.typeName}: T = into<T> }'";
    // `spec Into<T>[self: Self&]: T` - the compact callback form written with
    // the bracketed receiver.
    if (check(TokenType::L_BRACKET)) {
        PropReceiver recv = parsePropReceiver();
        consume(TokenType::COLON, "Expected ':' and the conversion's result type after a spec receiver");
        TypeRef returnType = parseTypeName();
        rejectRemovedUse(removedUseRemedy);
        consumeNewline();
        SpecCallback callback{returnType, hasCallParens, callbackParams, recv.modifier, recv.name, std::nullopt,
                              typeParams.names};
        callbackSpecs.set(name, callback);
        TopLevelSpec spec;
        spec.name = name;
        spec.line = start.line;
        spec.column = start.column;
        spec.callback = callback;
        spec.typeParams = typeParams.names;
        spec.isBridge = isBridge;
        spec.isDirect = isDirect;
        spec.typeDefaults = typeParams.typeDefaults;
        spec.whereClause = typeParams.boundClause;
        return make(arena, spec);
    }
    std::vector<TypeRef> parentSpecs;
    if (match(TokenType::COLON)) {
        TypeRef returnType = parseTypeName();
        // `spec MutableList<T>: List<T> { … }` - spec inheritance. A `(` instead
        // means this is a callback spec.
        if (!check(TokenType::L_PAREN)) {
            parentSpecs = {returnType};
        } else {
            // Callback receiver in parens: `spec Into<T>: T (ref self)`.
            consume(TokenType::L_PAREN, "Expected '(' after spec callback signature");
            skipNewlines();
            ReceiverBinding receiverBinding = parseReceiverBinding("spec callback receiver");
            ParamModifier receiverModifier = receiverBinding.modifier;
            consume(TokenType::R_PAREN, "Expected ')' after spec callback receiver");
            rejectRemovedUse(removedUseRemedy);
            consumeNewline();
            SpecCallback callback{returnType, hasCallParens, callbackParams, receiverModifier, receiverBinding.name,
                                  std::nullopt, typeParams.names};
            callbackSpecs.set(name, callback);
            TopLevelSpec spec;
            spec.name = name;
            spec.line = start.line;
            spec.column = start.column;
            spec.callback = callback;
            spec.typeParams = typeParams.names;
            spec.isDirect = isDirect;
            spec.whereClause = typeParams.boundClause;
            return make(arena, spec);
        }
    }
    if (hasCallParens) fail("Expected ':' after spec callback parameters at line " + str(peek().line));
    // `spec Copy requires (Clone, Other)` - capabilities an implementor must also carry.
    std::vector<TypeRef> requiredSpecs;
    if (check(TokenType::REQUIRES) && peekNextIs(TokenType::L_BRACKET)) {
        fail("requirements are listed in parentheses: write 'requires (A, B)', or 'requires A' for one, at line " +
             str(peek().line));
    }
    if (check(TokenType::REQUIRES) && (peekNextIs(TokenType::L_PAREN) || peekNextIs(TokenType::IDENTIFIER))) {
        advance();
        if (match(TokenType::L_PAREN)) {
            skipNewlines();
            if (!check(TokenType::R_PAREN)) {
                do {
                    skipNewlines();
                    requiredSpecs.push_back(parseTypeName());
                    skipNewlines();
                } while (match(TokenType::COMMA));
            }
            consume(TokenType::R_PAREN, "Expected ')' to close the 'requires' tuple");
            if (requiredSpecs.size() == 1) {
                fail("A single requirement is written 'requires " + displayName(first(requiredSpecs)) +
                     "', without parentheses, at line " + str(peek().line));
            }
        } else {
            requiredSpecs.push_back(parseTypeName());
        }
    }
    // `spec Iterator assoc Item` - names each implementation decides.
    std::vector<std::string> assocNames = parseAssocNames();
    auto bodyless = [&](std::vector<FuncDecl> methods) {
        TopLevelSpec spec;
        spec.name = name;
        spec.methods = std::move(methods);
        spec.line = start.line;
        spec.column = start.column;
        spec.typeParams = typeParams.names;
        spec.parents = parentSpecs;
        spec.isBridge = isBridge;
        spec.isDirect = isDirect;
        spec.requires_ = requiredSpecs;
        spec.typeDefaults = typeParams.typeDefaults;
        spec.assocNames = assocNames;
        spec.whereClause = typeParams.boundClause;
        return make(arena, spec);
    };
    if (!check(TokenType::L_BRACE)) {
        consumeNewline();
        return bodyless({});
    }
    consume(TokenType::L_BRACE, "Expected '{' after spec name");
    skipNewlines();
    // Inside a spec, `Self` is the type that will implement it.
    std::optional<std::string> savedImplTypeName = currentImplTypeName;
    currentImplTypeName = name;
    std::vector<FuncDecl> methods;
    while (!check(TokenType::R_BRACE) && !isAtEnd()) {
        skipNewlines();
        if (check(TokenType::R_BRACE)) break;
        // A requirement's decorators are its own.
        std::vector<Annotation> memberAnnotations = parseAnnotations();
        // `ctor .(...elements: T): Self { … }` - the spec's own constructor.
        if (check(TokenType::CTOR)) {
            FuncDecl factory = single(bindSelf({parseSpecBracketConstructor()}, name));
            pendingTopLevels.push_back(
                mangleTopLevel(withImplTypeParams(make(arena, TopLevelFunc{factory}), typeParams.names), name));
            skipNewlines();
            continue;
        }
        // `spec Clone { self& }` - a capability with a receiver but no members.
        if (check(TokenType::IDENTIFIER) && peek().lexeme == "self") {
            advance();
            if (match(TokenType::COLON)) parseTypeName();
            else parsePostfixReceiverModifier();
            consumeNewline();
            continue;
        }
        rejectRemovedUse("declare the alias at its own name instead: 'inline prop to${T.typeName}: T = into<T>'");
        // `oper<SYM> &.(operands): Ret` - an operator requirement.
        if (check(TokenType::OPER)) {
            Token operStart = advance();
            std::string opName = parseOperatorName();
            TypeParams operTypeParams = parseTypeParams();
            PropReceiver orecv = parseOperatorReceiver(opName, true);
            std::vector<Param> ooperands = parseOperatorOperands(opName);
            TypeAnnotation oret;
            if (match(TokenType::COLON)) {
                skipNewlines();
                oret = explicitType(parseTypeName());
            }
            refuseOperatorStep();
            consumeNewline();
            FuncDecl decl;
            decl.name = operatorMemberName(opName);
            decl.params = ooperands;
            decl.returnType = oret;
            decl.isInline = false;
            decl.typeParams = operTypeParams.names;
            decl.line = operStart.line;
            decl.column = operStart.column;
            decl.annotations = memberAnnotations;
            decl.receiverModifier = orecv.modifier;
            decl.receiverName = orecv.name;
            decl.whereClause = operTypeParams.boundClause;
            methods.push_back(decl);
            continue;
        }
        // `inline prop to${T.typeName}: T = into<T>` - a call-site alias for
        // another member of this spec.
        if (check(TokenType::INLINE) && peekNextIs(TokenType::PROP)) {
            Token aliasStart = advance();  // 'inline'
            advance();                     // 'prop'
            std::string templateText = parseSplicedMemberName();
            consume(TokenType::COLON, "Expected ':' and the alias's type");
            parseTypeName();
            consume(TokenType::EQUAL, "Expected '=' and the member this aliases");
            std::string target = consumeIdentifierLike("Expected the aliased member's name");
            refuseBounds(parseTypeParams(), "a member alias", aliasStart.line);
            consumeNewline();
            int aliased = -1;
            for (size_t i = 0; i < methods.size(); i++) {
                if (methods[i].name == target) {
                    aliased = (int)i;
                    break;
                }
            }
            if (aliased < 0) fail("'" + target + "' names no member of spec '" + name + "' at line " + str(aliasStart.line));
            methods[(size_t)aliased].useAsTemplate = templateText;
            continue;
        }
        // `prop name: Type` - a property requirement (a zero-arg getter).
        if (check(TokenType::PROP)) {
            advance();
            TypeParams ptypeParams = parseTypeParams();
            refuseOwnBounds(ptypeParams, nullptr, start.line);
            std::optional<PropReceiver> prefixReceiver = parseImplicitMemberReceiver();
            std::string pname = consumeMemberName("Expected property name in spec");
            // A receiver-less requirement is STATIC.
            PropReceiver preceiver;
            if (prefixReceiver) {
                preceiver = *prefixReceiver;
            } else if (check(TokenType::L_BRACKET)) {
                fail("spec property receivers no longer use brackets; write 'prop &." + pname + ": T' at line " +
                     str(peek().line));
            } else {
                preceiver = PropReceiver{"self", nullptr, ParamModifier::SHARED};
            }
            requireObservingReceiver(preceiver, pname, start.line);
            consume(TokenType::COLON, "Expected ':' after spec property name");
            TypeRef ptype = parseTypeName();
            consumeNewline();
            FuncDecl decl;
            decl.name = pname;
            decl.returnType = explicitType(ptype);
            decl.isInline = false;
            decl.typeParams = ptypeParams.names;
            decl.line = start.line;
            decl.column = start.column;
            decl.annotations = memberAnnotations;
            decl.receiverModifier = preceiver.modifier;
            decl.receiverName = preceiver.name;
            decl.declaresReceiver = (bool)preceiver.type;
            decl.memberCallStyle = preceiver.type ? MemberCallStyle::PROPERTY : MemberCallStyle::STATIC_PROPERTY;
            methods.push_back(decl);
            continue;
        }
        consume(TokenType::FUNC, "Expected 'func' or 'prop' in spec");
        TypeParams mtypeParams = parseTypeParams();
        std::optional<PropReceiver> prefixReceiver = parseImplicitMemberReceiver();
        std::string mname = consumeMemberName("Expected method name");
        // A receiver-less `func` is STATIC, matching the rule `prop` follows.
        std::vector<Param> mScopeReceivers;
        std::optional<PropReceiver> mreceiver = prefixReceiver;
        if (!mreceiver && check(TokenType::L_BRACKET)) {
            fail("spec function receivers no longer use brackets; write 'func &." + mname + "(…)' at line " +
                 str(peek().line));
        }
        consume(TokenType::L_PAREN, "Expected '('");
        std::vector<Param> params = concat(mScopeReceivers, parseParams());
        consume(TokenType::R_PAREN, "Expected ')'");
        TypeAnnotation returnType;
        if (match(TokenType::COLON)) returnType = explicitType(parseTypeName());
        // What the member needs of its own and its spec's type parameters.
        Expr memberWhere = withInlineBounds(mtypeParams.boundClause, parseWhereClause(), start.line);
        // A body makes the member *provided*.
        std::vector<Stmt> provided;
        if (match(TokenType::L_BRACE)) {
            provided = parseBlock();
            consume(TokenType::R_BRACE, "Expected '}' after a provided spec method");
        }
        consumeNewline();
        FuncDecl decl;
        decl.name = mname;
        decl.params = params;
        decl.returnType = returnType;
        decl.body = provided;
        decl.isInline = false;
        decl.typeParams = mtypeParams.names;
        decl.line = start.line;
        decl.column = start.column;
        decl.annotations = memberAnnotations;
        decl.receiverModifier = mreceiver ? mreceiver->modifier : ParamModifier::SHARED;
        decl.receiverName = mreceiver ? mreceiver->name : "self";
        decl.declaresReceiver = mreceiver.has_value();
        decl.whereClause = memberWhere;
        decl.contextualParams = (int32_t)mScopeReceivers.size();
        decl.memberCallStyle = mreceiver ? MemberCallStyle::METHOD : MemberCallStyle::STATIC_METHOD;
        methods.push_back(decl);
    }
    consume(TokenType::R_BRACE, "Expected '}' after spec methods");
    currentImplTypeName = savedImplTypeName;
    consumeNewline();
    return bodyless(methods);
}

/** `assoc Item` / `assoc (Scalar, Rows, Columns)` on a spec. */
std::vector<std::string> Parser::parseAssocNames() {
    if (!check(TokenType::ASSOC)) return {};
    advance();
    if (!match(TokenType::L_PAREN)) {
        return {consume(TokenType::IDENTIFIER, "Expected an associated type name after 'assoc'").lexeme};
    }
    std::vector<std::string> names;
    do {
        skipNewlines();
        names.push_back(consume(TokenType::IDENTIFIER, "Expected an associated type name").lexeme);
        skipNewlines();
    } while (match(TokenType::COMMA));
    consume(TokenType::R_PAREN, "Expected ')' after the associated type names");
    return names;
}

/** `assoc Item = String` / `assoc (Scalar = T, Rows = Int)` on an implementation. */
OrderedMap<std::string, TypeRef> Parser::parseAssocBindings() {
    if (!check(TokenType::ASSOC)) return {};
    advance();
    OrderedMap<std::string, TypeRef> bindings;
    auto one = [&]() {
        std::string name = consume(TokenType::IDENTIFIER, "Expected an associated type name").lexeme;
        consume(TokenType::EQUAL, "Expected '=' after the associated type '" + name + "'");
        bindings.set(name, parseTypeName());
    };
    if (!match(TokenType::L_PAREN)) {
        one();
        return bindings;
    }
    do {
        skipNewlines();
        one();
        skipNewlines();
    } while (match(TokenType::COMMA));
    consume(TokenType::R_PAREN, "Expected ')' after the associated types");
    return bindings;
}

}  // namespace azora
