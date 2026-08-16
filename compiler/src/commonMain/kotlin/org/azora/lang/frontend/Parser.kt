/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.azora.lang.frontend

/**
 * Recursive-descent parser for the minimal Azora language.
 *
 * Grammar (simplified):
 *   program     → module? funcDecl*
 *   funcDecl    → "func" IDENTIFIER "(" params? ")" ":" type "{" stmt* "}"
 *   stmt        → varDecl | returnStmt | assignment | exprStmt
 *   varDecl     → "var" IDENTIFIER ":" type "=" expr
 *   returnStmt  → "return" expr?
 *   assignment  → IDENTIFIER "=" expr
 *   expr        → or
 *   or          → and ("||" and)*
 *   and         → equality ("&&" equality)*
 *   equality    → comparison (("==" | "!=") comparison)*
 *   comparison  → addition (("<" | "<=" | ">" | ">=") addition)*
 *   addition    → multiplication (("+" | "-") multiplication)*
 *   multiplication → unary (("*" | "/" | "%") unary)*
 *   unary       → ("!" | "-") unary | call
 *   call        → IDENTIFIER "(" args? ")" | primary
 *   primary     → INT | REAL | STRING | BOOL | IDENTIFIER | "(" expr ")"
 */
class Parser(
    initialTokens: List<Token>,
    /**
     * Compile-time lists of type names (`let X: [Type] = [A, B] ~ C`). Shared with
     * sub-parsers used to expand `inline for` bodies so nested loops resolve the
     * same lists. Purely a parse-time metaprogramming environment.
     */
    private val typeListEnv: MutableMap<String, List<String>> = mutableMapOf(),
    /**
     * Enum name → its variants, in declaration order. Shared for the same reason
     * [typeListEnv] is: an enum-typed const argument (`Mat<…, .ColumnMajor>`) is
     * resolved against the enums the whole compilation has seen, not only those in
     * the file being parsed.
     */
    private val declaredEnums: MutableMap<String, List<String>> = mutableMapOf(),
    /** Compiler-generated source may use reserved `__` names while being reparsed. */
    private val internalSource: Boolean = false,
    /**
     * Compile-time list name -> the named realm it was declared in ("std",
     * "std__container"), or "" for one declared outside any realm. Read by
     * [comptimeList] to qualify elements for a consumer in another realm.
     */
    private val typeListRealm: MutableMap<String, String> = mutableMapOf(),
) {

    /**
     * The token stream, mutable so a fragment macro can splice into it.
     *
     * `@name` standing for source text is replaced where it appears, which means
     * editing the stream rather than rewriting a node.
     */
    private var tokens: List<Token> = initialTokens

    init {
        if (!internalSource) SourceSymbolValidator.validateTokens(tokens)
    }

    /** Compile-time type functions are declarations, but never runtime top-level items. */
    private val typeFunctions = mutableListOf<TypeFunctionDecl>()
    /** Namespace used to qualify unqualified type-function calls inside a realm. */
    private var typeFunctionNamespacePrefix = ""

    /**
     * Extra top-level items synthesized while parsing another declaration:
     * bridge alias wrappers and normalized decorator/target list applications.
     * Drained into the item list after each declaration completes.
     */
    /**
     * The error set(s) the function currently being parsed declares with `?!`.
     *
     * `return .Variant(args)` needs to name the set that owns the variant, and the
     * return type is parsed before the body, so the parser knows it without waiting
     * for semantic analysis.
     */
    private var currentFailSets: List<String> = emptyList()

    /**
     * Suppresses `in` as a membership operator.
     *
     * A `for` header spells its iterable with `in`, so the step in `for x by 2 in xs`
     * must stop before it rather than reading `2 in xs` as a membership test.
     */
    private var inOperatorEnabled: Boolean = true

    private val pendingTopLevels = mutableListOf<TopLevel>()

    /** Type-macro arms collected during parsing, surfaced on [Program.typeMacroRules]. */
    private val pendingTypeMacroRules = mutableListOf<TypeTypeArm>()
    private val pendingInfixMacros = mutableListOf<InfixMacroRule>()

    /** Set whenever a `name@…` macro invocation is parsed; surfaced on [Program.usesMacros]. */
    private var usedMetaInvoke = false

    /** Tokens after which a trailing `&`/`!` is a call-site borrow, not a binary op. */
    private val BORROW_TERMINATORS = setOf(
        TokenType.R_PAREN, TokenType.COMMA, TokenType.R_BRACKET, TokenType.R_BRACE,
        TokenType.NEWLINE, TokenType.EOF,
    )

    /** Enclosing labeled/anonymous realms while parsing (outermost first). */
    private data class RealmFrame(val label: String?, val isInline: Boolean)
    private val realmStack = mutableListOf<RealmFrame>()
    /** Declaration name → its innermost realm, for `(reflect X).realm` reflection. */
    private val realmMetaByName = mutableMapOf<String, RealmMeta>()

    /**
     * One frame per lambda body being parsed; true once that body has mentioned a
     * free `it`. See the `it` case in [parsePrimary] for why the frames nest.
     */
    private val lambdaMentionsIt = mutableListOf<Boolean>()

    /** Declarations written inside a `realm test`, and how far each reaches. */
    private val testRealmMembers = mutableMapOf<String, Visibility>()
    /** Type declaration name → named namespace path (`std`, `std::container`, ...). */
    private val realmTypeNamespaces = linkedMapOf<String, String>()

    /**
     * The compile-time list [name] holds, as its elements must be written here.
     *
     * A list declares its elements in whatever realm it is written in - `std`'s
     * `Numbers` is `[Byte, Short, …]`, bare, because inside `realm std` that is
     * what those types are called. Read from another realm the same elements have
     * to carry the qualifier, or splicing one into type position yields a name
     * that resolves nowhere. Reading a list inside its own realm is left alone, so
     * the library's own `inline for Ty in Numbers` is unaffected.
     */
    private fun comptimeList(name: String): List<String>? {
        val values = typeListEnv[name] ?: return null
        val declaring = typeListRealm[name].orEmpty()
        if (declaring.isEmpty() || declaring == typeFunctionNamespacePrefix) return values
        val qualifier = declaring.replace("__", "::")
        return values.map { if ("::" in it) it else "$qualifier::$it" }
    }

    /** Builds the [RealmMeta] chain for the current [realmStack] (innermost outward). */
    private fun currentRealmMeta(): RealmMeta? {
        var meta: RealmMeta? = null
        for (frame in realmStack) meta = RealmMeta(frame.label, frame.isInline, meta)
        return meta
    }

    /** Records the current realm for each named declaration in [items] (innermost wins). */
    private fun recordRealmMembers(items: List<TopLevel>) {
        val meta = currentRealmMeta() ?: return
        for (item in items) {
            val name = declaredTopLevelName(item) ?: continue
            if (name !in realmMetaByName) realmMetaByName[name] = meta
        }
    }

    private fun declaredTopLevelName(item: TopLevel): String? = when (item) {
        is TopLevel.Func -> item.decl.name
        is TopLevel.FinDecl -> item.name
        is TopLevel.LetDecl -> item.name
        is TopLevel.VarDecl -> item.name
        is TopLevel.Pack -> item.name
        is TopLevel.Enum -> item.name
        is TopLevel.Fail -> item.name
        is TopLevel.Spec -> item.name
        is TopLevel.Deco -> item.name
        is TopLevel.Slot -> item.name
        is TopLevel.Solo -> item.name
        is TopLevel.TypeAlias -> item.name
        else -> null
    }

    /**
     * True when a labeled/anonymous realm block begins here: an optional
     * `inline`/`deepinline`, then `realm`, then a string label or `{`. Excludes the
     * identifier-named namespace form `realm Foo { … }` (handled separately) and
     * plain `inline { … }`/`deepinline if …`.
     */
    private fun isRealmBlockAhead(): Boolean {
        var i = current
        val t = tokens.getOrNull(i)?.type
        if (t == TokenType.INLINE || t == TokenType.DEEPINLINE) i++
        if (tokens.getOrNull(i)?.type != TokenType.REALM) return false
        val after = tokens.getOrNull(i + 1)?.type
        return after == TokenType.STRING_LITERAL || after == TokenType.L_BRACE
    }

    /**
     * `[inline|deepinline] realm ["label"] { … }` - a labeled or anonymous realm.
     * Unlike a namespace `realm Foo { … }`, members keep their top-level names;
     * the realm only attaches reflection metadata ([realmMetaByName]) and, for the
     * inline/deepinline forms, the usual compile-time block semantics. Inline-ness
     * is inherited by nested realms.
     */
    private fun parseRealmBlock(): List<TopLevel> {
        val start = peek()
        val ownInline = when {
            match(TokenType.DEEPINLINE) -> true
            match(TokenType.INLINE) -> true
            else -> false
        }
        val isDeep = start.type == TokenType.DEEPINLINE
        consume(TokenType.REALM, "Expected 'realm'")
        val label = if (check(TokenType.STRING_LITERAL)) advance().literal as String else null
        // Inline-ness is inherited: a plain realm nested in an inline/deepinline
        // realm is itself inline.
        val isInline = ownInline || (realmStack.lastOrNull()?.isInline == true)
        consume(TokenType.L_BRACE, "Expected '{' to open realm")
        skipNewlines()
        realmStack.add(RealmFrame(label, isInline))
        val body = mutableListOf<TopLevel>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            when {
                isRealmBlockAhead() -> body.addAll(parseRealmBlock())
                isRealmNamespaceAhead() -> body.addAll(parseRealmNamespace())
                isTypePropAhead() -> parseTypeProp()
                isInline -> body.add(parseTopLevelBlockItem(deepInline = true))
                else -> body.add(parseTopLevel())
            }
            skipNewlines()
        }
        recordRealmMembers(body)
        realmStack.removeAt(realmStack.size - 1)
        consume(TokenType.R_BRACE, "Expected '}' to close realm")
        consumeNewline()
        return when {
            !ownInline -> body
            isDeep -> listOf(TopLevel.DeepInlineBlock(body, start.line, start.column))
            else -> listOf(TopLevel.InlineBlock(body, start.line, start.column))
        }
    }

    private var current = 0

    /** When `>>` (SHIFT_RIGHT) is split for nested generics, this flag signals a pending `>` for the enclosing type. */
    private var pendingGreater = false

    private var contractResultCounter = 0

    private val callbackSpecs = mutableMapOf<String, SpecCallback>()

    private data class ContractClauses(
        val preconditions: List<Stmt> = emptyList(),
        val resultName: String? = null,
        val postconditions: List<Stmt> = emptyList(),
    )

    /**
     * When false, a call does NOT consume a following `{` as a trailing lambda.
     * Used while parsing a `for`-iterable or `when`-scrutinee so that
     * `for x in f(args) { … }` / `when f(args) { … }` keep the `{` as the body.
     */
    private var allowTrailingLambda = true

    /**
     * Parses the token stream into a [Program] AST.
     *
     * Consumes all tokens from the input list, starting with an optional
     * `module` declaration followed by zero or more top-level items
     * (function declarations and compile-time constructs).
     *
     * @return the parsed [Program] representing the complete source file
     * @throws IllegalStateException on syntax errors
     */
    fun parse(): Program {
        skipNewlines()
        var isExported = false
        var moduleVisibility = ModuleVisibility.PUBLIC
        var exportCondition: Expr? = null
        val moduleName = if (isModuleHeaderAhead()) {
            // `exposed module x` - auto-imported everywhere, rather than only where
            // it is asked for. It says nothing about visibility: a module is
            // reachable by default, and `confined` is what narrows it.
            if (match(TokenType.EXPOSE)) isExported = true
            if (check(TokenType.IF)) {
                // `exposed if COND \n module …` - comptime-conditional auto-import.
                advance() // 'if'
                exportCondition = parseExpr()
                consume(TokenType.NEWLINE, "Expected a newline after 'exposed if <condition>'")
            } else {
                moduleVisibility = when {
                    match(TokenType.CONFINE) -> ModuleVisibility.CONFINE
                    else -> ModuleVisibility.PUBLIC
                }
                if (isExported && moduleVisibility == ModuleVisibility.CONFINE) {
                    error("'exposed confined module' is contradictory: a confined module is package-private and cannot be auto-imported everywhere")
                }
            }
            parseModule()
        } else null
        val items = mutableListOf<TopLevel>()
        while (!isAtEnd()) {
            skipNewlines()
            if (isAtEnd()) break
            if (isTestRealmAhead()) {
                // `[exposed|protected|confined] realm test { … }` - declarations
                // that exist only for tests.
                items.addAll(parseTestRealm())
            } else if (isRealmNamespaceAhead()) {
                // `realm Name { … }` - a named namespace whose members are always
                // qualified outside that realm.
                items.addAll(parseRealmNamespace())
            } else if (isRealmBlockAhead()) {
                // `[inline|deepinline] realm ["label"] { … }` - a labeled/anonymous
                // realm; members stay top-level, the realm only adds reflection
                // metadata (and inline semantics for the inline forms).
                items.addAll(parseRealmBlock())
            } else if (isTypePropAhead()) {
                parseTypeProp()
            } else {
                items.add(parseTopLevel())
            }
            if (pendingTopLevels.isNotEmpty()) {
                items.addAll(pendingTopLevels)
                pendingTopLevels.clear()
            }
        }
        // Stamp each pack and impl with the module it was written in. A private
        // member is reachable only from its own module, and that is the only
        // place the two sides can be compared later.
        for (i in items.indices) {
            when (val item = items[i]) {
                is TopLevel.Pack -> items[i] = item.copy(declaringModule = moduleName)
                is TopLevel.Impl -> items[i] = item.copy(declaringModule = moduleName)
                else -> {}
            }
        }
        val localPackNames = items.filterIsInstance<TopLevel.Pack>().mapTo(mutableSetOf()) { it.name }
        val normalized = CallbackImplNormalizer.normalize(
            Program(
                moduleName,
                items,
                localPackNames,
                isExported,
                moduleVisibility,
                exportCondition,
                realmMetaByName.toMap(),
                typeFunctions = typeFunctions.toList(),
                typeMacroRules = pendingTypeMacroRules.toList(),
                infixMacros = pendingInfixMacros.toList(),
                usesMacros = usedMetaInvoke,
                realmTypeNamespaces = realmTypeNamespaces.toMap(),
                testRealmMembers = testRealmMembers.toMap(),
            )
        )
        val rewritten = IntraRealmRewriter.rewrite(normalized)
        if (!internalSource) SourceSymbolValidator.validateProgram(rewritten)
        return rewritten
    }

    private fun isRealmNamespaceAhead(): Boolean {
        if (tokens.getOrNull(current)?.type != TokenType.REALM) return false
        var i = current + 1
        if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return false
        i++
        while (tokens.getOrNull(i)?.type == TokenType.DOUBLE_COLON &&
            tokens.getOrNull(i + 1)?.type == TokenType.IDENTIFIER
        ) {
            i += 2
        }
        return tokens.getOrNull(i)?.type == TokenType.L_BRACE
    }

    /**
     * `realm Name (:: Name)* { items }` - a namespace contribution.
     *
     * Each member is mangled with the realm path (`realm std { realm math { fin PI } }`
     * → `std__math__PI`, reached as `std::PI`). The same realm path may be
     * opened as many times as it likes, in as many modules as it likes, and the
     * contributions merge - a realm is a name a package agrees on, not a block
     * one file owns.
     *
     * Realm members are always qualified outside their realm. Inside the same
     * realm, [IntraRealmRewriter] preserves ergonomic sibling access.
     */
    private fun parseRealmNamespace(outerPrefix: String = ""): List<TopLevel> {
        consume(TokenType.REALM, "Expected 'realm'")
        val first = consume(TokenType.IDENTIFIER, "Expected realm name after 'realm'").lexeme
        val realmPath = StringBuilder(first).apply {
            while (match(TokenType.DOUBLE_COLON)) {
                append("__").append(consume(TokenType.IDENTIFIER, "Expected realm name after '::' in realm path").lexeme)
            }
        }.toString()
        val prefix = if (outerPrefix.isEmpty()) realmPath else "${outerPrefix}__$realmPath"
        consume(TokenType.L_BRACE, "Expected '{' after realm name")
        skipNewlines()
        val items = parseRealmBody(prefix)
        consume(TokenType.R_BRACE, "Expected '}' after realm")
        consumeNewline()
        return items
    }

    private fun parseRealmBody(prefix: String): List<TopLevel> {
        val result = mutableListOf<TopLevel>()
        val previousTypeNamespace = typeFunctionNamespacePrefix
        typeFunctionNamespacePrefix = prefix
        try {
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                skipNewlines()
                if (check(TokenType.R_BRACE)) break
                when {
                    isRealmNamespaceAhead() -> result.addAll(parseRealmNamespace(prefix))
                    isTypePropAhead() -> parseTypeProp(prefix)
                    else -> {
                        val parsed = parseTopLevel()
                        declaredTypeName(parsed)?.let { realmTypeNamespaces[it] = prefix.replace("__", "::") }
                        result.add(mangleTopLevel(parsed, prefix))
                        if (pendingTopLevels.isNotEmpty()) {
                            result.addAll(pendingTopLevels.map { mangleTopLevel(it, prefix) })
                            pendingTopLevels.clear()
                        }
                    }
                }
            }
        } finally {
            typeFunctionNamespacePrefix = previousTypeNamespace
        }
        return result
    }

    private fun declaredTypeName(item: TopLevel): String? = when (item) {
        is TopLevel.Pack -> item.name
        is TopLevel.Enum -> item.name
        is TopLevel.Fail -> item.name
        is TopLevel.Spec -> item.name
        is TopLevel.Deco -> item.name
        is TopLevel.Slot -> item.name
        is TopLevel.Solo -> item.name
        is TopLevel.TypeAlias -> item.name
        else -> null
    }

    /** Qualifies a top-level item's name with [prefix] (e.g. `PI` → `Math__PI`). */
    /**
     * Puts the enclosing impl's type parameters back on a static member.
     *
     * A member of `impl Vec<T, N>:: { … }` becomes a free function, and a free
     * function only knows the parameters it declares itself - so `T` would be an
     * undefined type in `func axis<I: Int>(value: T)`. The impl's parameters come
     * first, ahead of the member's own.
     */
    private fun withImplTypeParams(item: TopLevel, implTypeParams: List<String>): TopLevel {
        if (implTypeParams.isEmpty()) return item
        return when (item) {
            is TopLevel.Func -> item.copy(
                decl = item.decl.copy(
                    typeParams = (implTypeParams + item.decl.typeParams).distinct(),
                ),
            )
            else -> item
        }
    }

    private fun mangleTopLevel(item: TopLevel, prefix: String): TopLevel = when (item) {
        is TopLevel.Func -> item.copy(decl = item.decl.copy(name = "${prefix}__${item.decl.name}"))
        is TopLevel.FinDecl -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.VarDecl -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.LetDecl -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.InlineFin -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.InlineLet -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.InlineVar -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.Impl -> item.copy(realmPrefix = prefix)
        is TopLevel.Meta -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.Pack -> if (item.nameMacro != null) item.copy(localRealm = prefix) else item
        // `bridge func` members of `impl Type:: { … }` are static intrinsics
        // reached as `Type::member`; mangle each to `Type__member` to match.
        is TopLevel.Bridge -> item.copy(
            funcs = item.funcs.map { sig ->
                when {
                    sig.nameMacro != null -> sig.copy(localRealm = prefix)
                    sig.localName != null -> sig.copy(localName = "${prefix}__${sig.localName}")
                    else -> sig.copy(name = "${prefix}__${sig.name}")
                }
            },
            values = item.values.map { value ->
                if (value.nameMacro != null) value.copy(localRealm = prefix)
                else value.copy(name = "${prefix}__${value.name}")
            },
        )
        else -> item
    }

    /** Reads an operator symbol after `oper` (for `impl oper<OP> …`). */
    private fun parseOperatorName(): String = when {
        // The index operators: `oper[]` (read), `oper[]=` (write), `oper[:]`
        // (slice). A `[` opens the operator's name only when `]` or `:` follows -
        // otherwise it is the bracketed receiver that comes next.
        check(TokenType.L_BRACKET) && peekNext()?.type == TokenType.R_BRACKET -> {
            advance() // '['
            advance() // ']'
            if (match(TokenType.EQUAL)) "indexSet" else "index"
        }
        check(TokenType.L_BRACKET) && peekNext()?.type == TokenType.COLON &&
            tokens.getOrNull(current + 2)?.type == TokenType.R_BRACKET -> {
            advance() // '['
            advance() // ':'
            advance() // ']'
            "slice"
        }
        // `reverse..` (two tokens) and the range operators `..`/`..<` share one name.
        check(TokenType.REVERSE) && peekNext()?.type == TokenType.DOT_DOT -> { advance(); advance(); "reverse.." }
        match(TokenType.DOT_DOT) -> ".."
        match(TokenType.DOT_DOT_LESS) -> ".."
        // `<=>` before `<=`, matching the lexer's munch order. The spec that owns
        // it (`Order`/`PartialOrder`) is what fixes its result type.
        match(TokenType.SPACESHIP) -> "<=>"
        match(TokenType.LESS_EQUAL) -> "<="
        match(TokenType.GREATER_EQUAL) -> ">="
        match(TokenType.EQUAL_EQUAL) -> "=="
        match(TokenType.BANG_EQUAL) -> "!="
        match(TokenType.SHIFT_LEFT) -> "<<"
        // `&=`, `|=`, `^=`, `<<=`, `>>=` are not single tokens, so the pair is
        // recognised here - an operator name is the one place they appear.
        match(TokenType.AMP_EQUAL) -> "&="
        match(TokenType.PIPE_EQUAL) -> "|="
        match(TokenType.CARET_EQUAL) -> "^="
        match(TokenType.SHIFT_LEFT_EQUAL) -> "<<="
        match(TokenType.SHIFT_RIGHT_EQUAL) -> ">>="
        match(TokenType.SHIFT_RIGHT) -> ">>"
        match(TokenType.LESS) -> "<"
        match(TokenType.GREATER) -> ">"
        match(TokenType.PLUS) -> "+"
        match(TokenType.MINUS) -> "-"
        match(TokenType.STAR) -> "*"
        match(TokenType.SLASH) -> "/"
        match(TokenType.PERCENT) -> "%"
        match(TokenType.AMP) -> "&"
        match(TokenType.PIPE) -> "|"
        match(TokenType.CARET) -> "^"
        match(TokenType.TILDE) -> "~"
        // `oper!` - logical negation. A unary operator is told apart from a
        // binary one of the same symbol by operand count, which the overload
        // suffix already handles, so `!` needs nothing but a name.
        match(TokenType.BANG) -> "!"
        // `oper$` - what `"$value"` calls. Named after the sigil you actually write.
        check(TokenType.IDENTIFIER) && peek().lexeme == "$" -> { advance(); "$" }
        // `oper.*` / `oper.^` - what `p.*` and `p.^` call. The surface keeps the
        // sigil; the method symbol spells it out, because a '.' in a symbol name
        // does not survive mangling.
        check(TokenType.DOT) && peekNext()?.type == TokenType.STAR -> { advance(); advance(); "Deref" }
        check(TokenType.DOT) && peekNext()?.type == TokenType.CARET -> { advance(); advance(); "DerefMut" }
        // `oper as<U>` - what `value as U` calls. The target type is a generic
        // parameter of the operator, so one declaration converts to every U.
        // `oper as?` and `oper as*` are the checked and reinterpreting forms, each
        // its own member so a type can say how it converts, how it checks, and how
        // it reinterprets independently.
        check(TokenType.AS) && peekNext()?.type == TokenType.QMARK -> { advance(); advance(); "as?" }
        check(TokenType.AS) && peekNext()?.type == TokenType.STAR -> { advance(); advance(); "as*" }
        match(TokenType.AS) -> "as"
        // Compound assignment: `oper +=` and friends, which a spliced operator name
        // produces (`inline fin assignOp = "${op}="`).
        match(TokenType.PLUS_EQUAL) -> "+="
        match(TokenType.MINUS_EQUAL) -> "-="
        match(TokenType.STAR_EQUAL) -> "*="
        match(TokenType.SLASH_EQUAL) -> "/="
        match(TokenType.PERCENT_EQUAL) -> "%="
        else -> error("Expected an operator after 'oper' at line ${peek().line}")
    }

    /**
     * The member name an operator is registered under.
     *
     * The index operators are looked up by their bare names, which is what
     * indexing, index-assignment and slicing resolve against; every other
     * operator is reached through its `oper<symbol>` symbol.
     */
    private fun operatorMemberName(opName: String): String =
        if (opName in setOf("index", "indexSet", "slice")) opName else "oper$opName"

    /**
     * Operators the compiler looks up by their bare member name.
     *
     * Indexing, slicing and the range operators are reached through a check that
     * asks for the name directly rather than through `lookupOperator`'s
     * operand-keyed fallback, so an overload suffix would hide them - a
     * suffixed `oper..` makes `for i in 0..<n` report that `Int` has no range
     * operator.
     */
    private val bareLookupOperators = setOf("index", "indexSet", "slice", "..", "reverse..")

    /**
     * `[bridge] impl oper<OP> for Type(params): Ret (mod self) [{ body }]`.
     * The receiver is declared in parens after the return type; the body, if any,
     * follows in braces (`{ body }`), or the older `{ mod self -> body }` form is
     * accepted. A `bridge` operator (e.g. the primitives') is compiler-provided:
     * the declaration is accepted but emits no method, so the backend's native
     * operator handling stands.
     */
    /**
     * `oper<OP> [self: Type&](operands): Ret { body }` at top level.
     *
     * The bracketed receiver names the type the operator belongs to, which is
     * what an `impl … for Type` clause used to say. Declaring it beside the type
     * rather than inside it is what lets a file add an operator to a type it
     * merely uses.
     */
    private fun parseFreeOperator(
        annotations: List<Annotation>,
        visibility: Visibility,
        isBridge: Boolean = false,
    ): TopLevel {
        val start = peek()
        consume(TokenType.OPER, "Expected 'oper'")
        val opName = parseOperatorName()
        // `oper as<U> [self: Vec2&]: U` - an operator may take its own type
        // parameters, which is what lets one `as` serve every target type.
        val operatorTypeParams = parseTypeParams()
        val recv = parsePropReceiver()
        val typeName = (recv.type as? TypeRef.Named)?.name
            ?: error("An operator's receiver must name a type, as in 'oper+ [self: Model&]', at line ${start.line}")
        val operands = if (match(TokenType.L_PAREN)) {
            val parsed = if (check(TokenType.R_PAREN)) emptyList() else parseParams()
            consume(TokenType.R_PAREN, "Expected ')' after operator operands")
            parsed
        } else emptyList()
        val ret: TypeAnnotation = if (match(TokenType.COLON)) {
            skipNewlines()
            TypeAnnotation.Explicit(parseTypeName())
        } else TypeAnnotation.Inferred
        // `inline if cond { inline "?! E" }` - a fragment spliced into the signature,
        // the same form an operator inside an impl accepts.
        var operRet = ret
        parseSignatureFragment()?.let { fragment ->
            operRet = applySignatureFragment(operRet, fragment, start)
        }
        // An operator may carry its own constraint.
        parseWhereClause()
        // The step is declarative metadata: parsed so the source states it, then
        // discarded, exactly as the old bracket form's `by` was.
        if (match(TokenType.BY)) parseExpr()
        // The same overload suffix an operator inside an `impl` gets: the operand
        // it accepts, and - for operators that do not already imply mutation -
        // the receiver's exclusivity. Without it a unary `oper-` and a binary
        // `oper-` are one member, and the second collides with the first.
        val memberName = operatorMemberName(opName) +
            if (opName in bareLookupOperators) {
                ""
            } else {
                operatorOverloadSuffix(opName, recv.modifier, operands)
            }
        // A declaration with no body is provided by the backend, and says so:
        // `bridge oper.. [self: Ty&](rhs: Ty&) by 1`. Requiring the keyword keeps
        // "the compiler implements this" from being spelled as an omission.
        if (!check(TokenType.L_BRACE)) {
            if (!isBridge) {
                error(
                    "operator '$opName' has no body, so it is compiler-provided - " +
                        "declare it 'bridge oper$opName' at line ${start.line}",
                )
            }
            consumeNewline()
            return TopLevel.Impl(
                typeName,
                listOf(
                    FuncDecl(
                        memberName, operands, operRet, emptyList(), false,
                        operatorTypeParams.names,
                        start.line, start.column, receiverModifier = recv.modifier,
                        receiverName = recv.name, visibility = visibility,
                        annotations = annotations,
                    ),
                ),
                null, start.line, start.column, isBridge = true,
            )
        }
        consume(TokenType.L_BRACE, "Expected '{' after operator declaration")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after operator body")
        consumeNewline()
        val method = FuncDecl(
            memberName, operands, operRet, body, false, operatorTypeParams.names,
            start.line, start.column,
            annotations = annotations, visibility = visibility,
            receiverModifier = recv.modifier, receiverName = recv.name,
            // `oper * <N: Int>` is generic over a value, and the member has to say so:
            // the specializations of whatever it mentions are what bind `N`.
            constParams = operatorTypeParams.constParams,
        )
        return TopLevel.Impl(typeName, listOf(method), null, start.line, start.column)
    }

    /**
     * True when `impl` is followed by a type (or a `[A, B]` list) and then `::` -
     * the type-scoped member form `impl Vec3:: { … }`.
     */
    /**
     * True for `impl Spec for Type:: {` - a spec-attributed static block.
     *
     * Distinguished from an ordinary `impl Spec for Type {` by the `::` that the
     * target carries before the body opens.
     */
    /** The name a reparsed static member declares, or null when it declares none. */
    private fun declaredMemberName(item: TopLevel): String? = when (item) {
        is TopLevel.FinDecl -> item.name
        is TopLevel.LetDecl -> item.name
        is TopLevel.VarDecl -> item.name
        is TopLevel.Func -> item.decl.name
        else -> null
    }

    private fun isSpecStaticImplAhead(): Boolean {
        if (tokens.getOrNull(current)?.type != TokenType.IDENTIFIER) return false
        // The spec may carry type arguments - `impl From<String> for Username::`
        // - so skip a balanced `<…>` between its name and `for`.
        var afterName = current + 1
        if (tokens.getOrNull(afterName)?.type == TokenType.LESS) {
            var generics = 0
            while (afterName < tokens.size) {
                when (tokens[afterName].type) {
                    TokenType.LESS -> generics++
                    TokenType.GREATER -> {
                        generics--
                        if (generics == 0) { afterName++; break }
                    }
                    TokenType.L_BRACE, TokenType.NEWLINE, TokenType.EOF -> return false
                    else -> {}
                }
                afterName++
            }
        }
        if (tokens.getOrNull(afterName)?.type != TokenType.FOR) return false
        var i = afterName + 1
        var depth = 0
        while (i < tokens.size) {
            when (tokens[i].type) {
                TokenType.L_BRACKET, TokenType.LESS -> depth++
                TokenType.R_BRACKET, TokenType.GREATER -> depth--
                TokenType.DOUBLE_COLON ->
                    if (depth == 0) return tokens.getOrNull(i + 1)?.type == TokenType.L_BRACE
                TokenType.L_BRACE, TokenType.NEWLINE, TokenType.EOF -> return false
                else -> {}
            }
            i++
        }
        return false
    }

    private fun isStaticImplAhead(): Boolean {
        var i = current
        if (tokens.getOrNull(i)?.type == TokenType.L_BRACKET) {
            var depth = 0
            while (i < tokens.size) {
                val t = tokens[i].type
                if (t == TokenType.L_BRACKET) depth++
                if (t == TokenType.R_BRACKET) { depth--; if (depth == 0) { i++; break } }
                i++
            }
        } else {
            if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return false
            i++
            if (tokens.getOrNull(i)?.type == TokenType.LESS) {
                var depth = 0
                while (i < tokens.size) {
                    val t = tokens[i].type
                    if (t == TokenType.LESS) depth++
                    if (t == TokenType.GREATER) { depth--; if (depth == 0) { i++; break } }
                    i++
                }
            }
        }
        if (tokens.getOrNull(i)?.type != TokenType.DOUBLE_COLON) return false
        // The block opens directly, or after a `where` clause narrowing the type's
        // own constraints: `impl Vec<T, N>:: where T is SignedNumber { … }`.
        val after = tokens.getOrNull(i + 1) ?: return false
        return after.type == TokenType.L_BRACE ||
            (after.type == TokenType.IDENTIFIER && after.lexeme == "where")
    }

    /**
     * `impl oper[spec, spec, ...] for Type` - declares several operators at once.
     * Each spec is `[reverse] (.. | ..<) [by <expr>]`. `..` and `..<` are one
     * operator (the inclusive/exclusive flag lives on the range node); `reverse..`
     * is the reverse-range operator. The optional `by <expr>` is declarative step
     * metadata (parsed, then discarded). The expansion produces one `TopLevel.Impl`
     * per spec via the `pendingTopLevels` queue (first returned, rest queued).
     * `oper` and the leading `[` have already been consumed by the caller.
     */

    /**
     * Parses an optional receiver + parameters + body for a member. The receiver
     * (and any parameters) are declared in parens after the return type:
     * `(mod self [, param: T]...)`, then an optional `{ body }`; alternatively a
     * `{ mod self -> body }` block, or just `{ body }`, or nothing. Returns the
     * receiver modifier (default `ref`), the parameters, and the body statements
     * (empty for a bodyless declaration).
     */
    private fun parseReceiverAndBody(): Triple<ParamModifier, List<Param>, List<Stmt>> {
        var receiverModifier = ParamModifier.SHARED
        val params = mutableListOf<Param>()
        if (match(TokenType.L_PAREN)) {
            receiverModifier = parseReceiverBinding().modifier
            if (match(TokenType.COMMA)) params.addAll(parseParams())
            consume(TokenType.R_PAREN, "Expected ')' after receiver")
        }
        val body = mutableListOf<Stmt>()
        if (match(TokenType.L_BRACE)) {
            skipNewlines()
            // In-brace receiver form: `{ self& -> body }` or, for operator overloads,
            // `{ self&, operand[: Type][, …] -> body }`.
            if (isSelfReceiverHeaderAhead()) {
                receiverModifier = parseReceiverBinding().modifier
                parseReceiverOperands(params)
                consume(TokenType.ARROW, "Expected '->' after in-brace receiver")
                skipNewlines()
            }
            while (!check(TokenType.R_BRACE) && !isAtEnd()) { body.add(parseStmt()); skipNewlines() }
            consume(TokenType.R_BRACE, "Expected '}' after body")
        }
        return Triple(receiverModifier, params, body)
    }

    /**
     * True when the current brace body opens with a `self` receiver, optionally
     * followed by operand params (`self, x -> …`) before the `->`.
     */
    private fun isInBraceReceiverAhead(): Boolean {
        var i = current
        if (tokens.getOrNull(i)?.lexeme == "self") {
            // `self& …` / `self! …` / `self …`.
            i++
            if (tokens.getOrNull(i)?.type in setOf(TokenType.AMP, TokenType.BANG)) i++
        } else return false
        // Skip `, operand` pairs (operator operands) before the `->`.
        while (tokens.getOrNull(i)?.type == TokenType.COMMA) {
            i++ // ','
            i++ // operand name
            if (tokens.getOrNull(i)?.type in setOf(TokenType.AMP, TokenType.BANG)) i++
        }
        return tokens.getOrNull(i)?.type == TokenType.ARROW
    }

    /**
     * `[exposed|protected|confined] <declaration>` - how far the declaration reaches.
     *
     * `exposed` is the default and is accepted so a declaration may state it, the
     * way a module does.
     */
    private fun parseVisibility(): Visibility = when {
        match(TokenType.CONFINE) -> Visibility.CONFINE
        match(TokenType.PROTECT) -> Visibility.PROTECT
        // `exposed confined` / `exposed protected` - the two axes together. `exposed`
        // publishes the declaration without an explicit import; the reach that
        // follows bounds how far that publication travels.
        check(TokenType.EXPOSE) && peekNext()?.type == TokenType.CONFINE -> {
            advance(); advance()
            Visibility(Visibility.Reach.CONFINE, isExposed = true)
        }
        check(TokenType.EXPOSE) && peekNext()?.type == TokenType.PROTECT -> {
            advance(); advance()
            Visibility(Visibility.Reach.PROTECT, isExposed = true)
        }
        // `exposed` states the default. It is only a visibility modifier when a
        // declaration follows: `exposed import`, `exposed if` and `exposed module`
        // are their own forms and are parsed further down.
        check(TokenType.EXPOSE) && !exposeStartsAnotherForm() -> {
            advance()
            Visibility(Visibility.Reach.PUBLIC, isExposed = true)
        }
        else -> Visibility.PUBLIC
    }

    /**
     * True when the `exposed` at the cursor begins `exposed import`, `exposed if`
     * or `exposed module` - forms of their own, parsed elsewhere, rather than a
     * visibility modifier on a declaration.
     */
    private fun exposeStartsAnotherForm(): Boolean {
        val next = peekNext() ?: return false
        return next.type == TokenType.IMPORT ||
            next.type == TokenType.IF ||
            (next.type == TokenType.IDENTIFIER && next.lexeme == "module")
    }

    // -----------------------------------------------------------------------
    // Declarations
    // -----------------------------------------------------------------------

    private fun parseTopLevel(): TopLevel {
        val annotations = parseAnnotations()
        // Optional visibility modifier; public unless `confined` narrows it to the package.
        val visibility = parseVisibility()
        val start = peek()
        return when {
            // Compile-time list bindings (`let X: [Type]`, `inline fin ranks: [Int]`)
            // must be recognised before the general `inline`/`fin`/`let` handlers.
            isTypeListAliasAhead() -> parseTypeListAlias()
            isTypeListBindingAhead() -> parseTypeListBinding()
            check(TokenType.UNSAFE) && (peekNext()?.type == TokenType.FUNC || isAsyncFuncAt(current + 1)) -> {
                advance()
                when {
                    isAsyncFuncAt(current) -> TopLevel.Func(parseFuncDecl(annotations = annotations, isTask = true, isUnsafe = true, visibility = visibility))
                    else -> TopLevel.Func(parseFuncDecl(annotations = annotations, isUnsafe = true, visibility = visibility))
                }
            }
            check(TokenType.FUNC) -> funcOrExtension(parseFuncDecl(annotations = annotations, visibility = visibility))
            isAsyncFuncAt(current) ->
                funcOrExtension(parseFuncDecl(annotations = annotations, isTask = true, visibility = visibility))
            // `react func` / `react async func` - a reactive owner. `react`
            // qualifies the declaration rather than replacing `func`, exactly as
            // `async` does, so both may be written and in that order.
            check(TokenType.REACT) -> {
                advance()
                val asyncProp = isAsyncPropAt(current)
                if (asyncProp) advance()
                if (asyncProp || check(TokenType.PROP)) {
                    parsePropAsFin(annotations, visibility, isReactive = true, isTask = asyncProp)
                } else {
                    val task = isAsyncFuncAt(current)
                    funcOrExtension(
                        parseFuncDecl(
                            annotations = annotations,
                            isTask = task,
                            isReactive = true,
                            visibility = visibility,
                        ),
                    )
                }
            }
            check(TokenType.INLINE) -> parseTopLevelInline()
            check(TokenType.DEEPINLINE) -> parseTopLevelDeepInline()
            check(TokenType.TEST) -> parseTestDecl(annotations)
            check(TokenType.ANNOT) -> parseDeco(annotations)
            check(TokenType.PACK) -> parsePack(annotations, visibility)
            check(TokenType.ENUM) -> parseEnumDecl(annotations)
            check(TokenType.ERROR) -> parseFailDecl(annotations)
            check(TokenType.DERIVE) -> parseDeriveDecl()
            check(TokenType.IMPL) -> parseImpl(annotations = annotations)
            check(TokenType.BRIDGE) && peekNext()?.type == TokenType.IMPL -> {
                advance(); parseImpl(isBridge = true, annotations = annotations)
            }
            // `bridge <decl>` marks a declaration as compiler-provided. `bridge pack`
            // and `bridge func` are bodyless (no struct / no body emitted); `bridge`
            // on the compile-time kinds (enum/deco/spec/typealias) is an accepted
            // marker that otherwise parses normally. Plain `bridge [.Target] { … }`
            // remains the FFI block form.
            check(TokenType.BRIDGE) && peekNext()?.type == TokenType.PACK -> {
                advance()
                parsePack(annotations, visibility, isBridge = true)
            }
            check(TokenType.BRIDGE) && peekNext()?.type == TokenType.ENUM -> {
                advance(); parseEnumDecl(annotations)
            }
            check(TokenType.BRIDGE) && peekNext()?.type == TokenType.ANNOT -> {
                advance(); parseDeco(annotations, isBridge = true)
            }
            check(TokenType.BRIDGE) && peekNext()?.type == TokenType.SPEC -> {
                advance(); parseSpec(isBridge = true)
            }
            check(TokenType.BRIDGE) && peekNext()?.type == TokenType.TYPEALIAS -> {
                advance(); parseTypeAlias(annotations)
            }
            check(TokenType.BRIDGE) && peekNext()?.type == TokenType.VARIANT -> {
                advance(); parseSlot(annotations)
            }
            check(TokenType.BRIDGE) && peekNext()?.type == TokenType.OPER -> {
                advance(); parseFreeOperator(annotations, visibility, isBridge = true)
            }
            // `bridge fin size: Int` - a compiler-provided constant. Inside
            // `impl Type:: { … }` the body is reparsed as top-level items, so this
            // is where a type's bridge constant lands.
            check(TokenType.BRIDGE) && peekNext()?.type == TokenType.FIN -> {
                val at = advance() // 'bridge'
                advance() // 'fin'
                val name = consumeIdentifierLike("Expected name after 'bridge fin'")
                consume(TokenType.COLON, "A 'bridge fin' must declare its type: write 'bridge fin $name: Type'")
                val type = parseTypeName()
                consumeNewline()
                TopLevel.Func(
                    FuncDecl(
                        name, emptyList(), TypeAnnotation.Explicit(type), emptyList(), false, emptyList(),
                        at.line, at.column,
                        annotations = annotations, visibility = visibility,
                        memberCallStyle = MemberCallStyle.PROPERTY,
                    ),
                )
            }
            check(TokenType.BRIDGE) && isBridgeReceiverPropAhead() -> parseBridgeReceiverProp(annotations)
            check(TokenType.BRIDGE) && isBridgeFuncAhead() -> parseBridgeFunc(annotations)
            check(TokenType.BRIDGE) -> parseBridge(annotations)
            check(TokenType.SOLO) -> parseSolo(visibility, annotations)
            check(TokenType.GRAPH) -> parseGraph()
            check(TokenType.THREADLOCAL) -> parseThreadLocal(visibility)
            check(TokenType.SPEC) -> parseSpec()
            isUnionDeclAhead() -> parsePack(annotations, visibility, isUnion = true)
            check(TokenType.VARIANT) -> parseSlot(annotations)
            check(TokenType.TYPEALIAS) -> parseTypeAlias(annotations)
            check(TokenType.MACRO) -> parseMeta()
            // `prop name: T = expr` at top level - accepted inside `impl … as realm
            // for Type` / `impl Type:: { … }` bodies (which reparse members as
            // top-level items); desugars to a `fin` so it mangles to `Type__name`.
            check(TokenType.PROP) -> parsePropAsFin(annotations, visibility)
            isAsyncPropAt(current) -> {
                advance()
                parsePropAsFin(annotations, visibility, isTask = true)
            }
            // `oper+ [self: Model&](rhs: Model): Model { … }` - an operator declared
            // beside the type rather than inside its impl. The receiver names the
            // type, so no `for` clause is needed.
            check(TokenType.OPER) -> parseFreeOperator(annotations, visibility)
            check(TokenType.IMPORT) -> parseUse()
            // `exposed import …` - the import travels on to whoever imports this
            // module, so a package can offer one entry point instead of making
            // every caller name its internals (e.g. std.macro and std.container).
            check(TokenType.EXPOSE) && peekNext()?.type == TokenType.IMPORT -> {
                advance() // 'exposed'
                parseUse(exported = true)
            }
            // `exposed if COND \n use …` - comptime-conditional. The newline
            // before `use` is mandatory.
            check(TokenType.EXPOSE) && peekNext()?.type == TokenType.IF -> {
                advance() // 'exposed'
                advance() // 'if'
                val cond = parseExpr()
                consume(TokenType.NEWLINE, "Expected a newline after 'exposed if <condition>'")
                parseUse(exported = true, condition = cond)
            }
            check(TokenType.FIN) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseInitializer(type); consumeNewline(); noteBridgeTargetConstant(name, init); TopLevel.FinDecl(name, type, init, start.line, start.column, annotations, visibility = visibility) }
            check(TokenType.VAR) || check(TokenType.VAL) -> { val valueMutable = advance().type == TokenType.VAR; val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseInitializer(type); consumeNewline(); TopLevel.VarDecl(name, type, init, start.line, start.column, annotations, visibility = visibility, valueMutable = valueMutable) }
            check(TokenType.LET) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseInitializer(type); consumeNewline(); TopLevel.LetDecl(name, type, init, start.line, start.column, annotations, visibility) }
            // A compile-time loop generates declarations, and a type alias is one
            // of them: `inline for n in 2..4 { typealias Mat$n = Mat<T, n, n> }`
            // is how a family of specializations gets its names.
            check(TokenType.TYPEALIAS) -> parseTypeAlias(annotations)
            check(TokenType.PACK) -> parsePack(annotations, visibility)
            check(TokenType.ENUM) -> parseEnumDecl(annotations)
            check(TokenType.SPEC) -> parseSpec()
            check(TokenType.IMPL) -> parseImpl(annotations = annotations)
            else -> error(
                "Expected 'func', 'fin', 'var', 'let', 'test', 'typealias', 'pack', 'enum', " +
                    "'spec', 'impl', 'inline', or 'deepinline' at top level, got " +
                    "'${peek().lexeme}' at line ${peek().line}",
            )
        }
    }

    /**
     * Parses zero or more leading decorator applications: `@Name` or `@Name(args)`.
     * These appear at top level before a declaration (labels use `@` too, but only
     * at statement level before a loop, so there's no clash).
     */
    private fun parseAnnotations(): List<Annotation> {
        val result = mutableListOf<Annotation>()
        while (check(TokenType.AT)) {
            val at = advance()
            // Grouped form: `@[A, B(args), C]` applies several decorators in one row,
            // equivalent to stacking `@A` / `@B(args)` / `@C`.
            if (match(TokenType.L_BRACKET)) {
                skipNewlines()
                if (!check(TokenType.R_BRACKET)) {
                    do {
                        skipNewlines()
                        result.add(parseOneAnnotation(at.line, at.column))
                        skipNewlines()
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_BRACKET, "Expected ']' after grouped decorators")
                skipNewlines()
                continue
            }
            result.add(parseOneAnnotation(at.line, at.column))
            skipNewlines()
        }
        return result
    }

    /** One decorator body (name + optional `(args)`), with the leading `@` already consumed. */
    private fun parseOneAnnotation(line: Int, column: Int): Annotation {
        val parts = mutableListOf(consume(TokenType.IDENTIFIER, "Expected decorator name").lexeme)
        while (match(TokenType.DOUBLE_COLON)) {
            parts += consume(TokenType.IDENTIFIER, "Expected decorator name after '::'").lexeme
        }
        val name = parts.last()
        val qualifier = parts.dropLast(1).takeIf { it.isNotEmpty() }?.joinToString("::")
        // A decorator is a declaration and names one, so it is capitalised. A
        // *macro* is lowercase - `@vec`, `@arr`, `@query` - and a parameter may
        // carry one, so the case rule applies only where a decorator is what is
        // being written.
        if (name.firstOrNull()?.isUpperCase() != true && !allowLowercaseAnnotation) {
            val prefix = qualifier?.let { "@$it::" } ?: "@"
            error("Decorator names must start with an uppercase letter: use '$prefix${name.replaceFirstChar { it.uppercase() }}' at line $line")
        }
        val (args, namedArgs) = if (check(TokenType.L_PAREN)) parseDecoratorArguments()
        else emptyList<Expr>() to emptyList<Pair<String, Expr>>()
        return Annotation(name, args, line, column, namedArgs = namedArgs, qualifier = qualifier)
    }

    private fun parseDecoratorArguments(): Pair<List<Expr>, List<Pair<String, Expr>>> {
        consume(TokenType.L_PAREN, "Expected '('")
        val positional = mutableListOf<Expr>()
        val named = mutableListOf<Pair<String, Expr>>()
        if (!check(TokenType.R_PAREN)) {
            do {
                // `.Native` shorthand is retained for built-in decorator arguments.
                if (check(TokenType.DOT) && peekNext()?.type == TokenType.IDENTIFIER) {
                    val dot = advance()
                    val member = advance().lexeme
                    positional.add(Expr.Identifier(".$member", dot.line, dot.column))
                } else if (check(TokenType.IDENTIFIER) && peekNext()?.type in setOf(TokenType.COLON, TokenType.EQUAL)) {
                    val key = advance().lexeme
                    advance() // ':' or '='
                    named.add(key to parseExpr())
                } else {
                    positional.add(parseExpr())
                }
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.R_PAREN, "Expected ')' after decorator arguments")
        return positional to named
    }

    private fun parseDecoTargets(): Set<DecoTarget> {
        val targets = linkedSetOf<DecoTarget>()
        fun parseOne() {
            consume(TokenType.DOT, "Expected '.' before decorator target")
            val token = advance()
            val target = DecoTarget.entries.firstOrNull { it.name == token.lexeme }
                ?: error("Unknown decorator target '.${token.lexeme}' at line ${token.line}")
            if (!targets.add(target)) {
                error("Duplicate decorator target '.${target.name}' at line ${token.line}")
            }
        }
        if (match(TokenType.L_BRACKET)) {
            if (check(TokenType.R_BRACKET)) error("Expected at least one decorator target at line ${peek().line}")
            do { parseOne() } while (match(TokenType.COMMA))
            consume(TokenType.R_BRACKET, "Expected ']' after decorator targets")
        } else {
            parseOne()
        }
        return targets
    }

    private fun parseDecoratorBinding(): DecoratorBinding {
        val name = consume(TokenType.IDENTIFIER, "Expected spec or decorator name after 'binds'").lexeme
        val typeArgs = parseGenericTypeArgsIfPresent()
        val targets = if (match(TokenType.FOR)) parseDecoTargets() else emptySet()
        return DecoratorBinding(name, typeArgs, targets)
    }

    /** Parses every `deco` target/binding combination into a normalized declaration. */
    private fun parseDeco(annotations: List<Annotation>, isBridge: Boolean = false): TopLevel.Deco {
        val start = peek()
        consume(TokenType.ANNOT, "Expected 'deco'")
        val name = consume(TokenType.IDENTIFIER, "Expected decorator name").lexeme
        if (name.firstOrNull()?.isUpperCase() != true) {
            error("Decorator names must start with an uppercase letter: use '${name.replaceFirstChar { it.uppercase() }}' at line ${start.line}")
        }
        val targets = if (match(TokenType.FOR)) parseDecoTargets() else emptySet()
        val bindings = if (matchContextual("binds")) {
            if (match(TokenType.L_BRACKET)) {
                val result = mutableListOf<DecoratorBinding>()
                if (check(TokenType.R_BRACKET)) error("Expected at least one decorator binding at line ${peek().line}")
                do { result.add(parseDecoratorBinding()) } while (match(TokenType.COMMA))
                consume(TokenType.R_BRACKET, "Expected ']' after decorator bindings")
                result
            } else {
                listOf(parseDecoratorBinding())
            }
        } else emptyList()

        if (!check(TokenType.L_BRACE)) {
            consumeNewline()
            return TopLevel.Deco(name, emptyList(), start.line, start.column, annotations, targets, bindings, isBridge)
        }
        consume(TokenType.L_BRACE, "Expected '{' after decorator declaration")
        skipNewlines()
        val fields = mutableListOf<PackField>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            fields.add(parsePackField(requireFin = true))
            match(TokenType.COMMA)
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after deco fields")
        consumeNewline()
        return TopLevel.Deco(name, fields, start.line, start.column, annotations, targets, bindings, isBridge)
    }

    private fun parseTopLevelInline(): TopLevel {
        return when (peekNext()?.type) {
            TokenType.FUNC -> { advance(); TopLevel.Func(parseFuncDecl(isInline = true)) }
            TokenType.FOR -> parseTopLevelInlineFor()
            TokenType.L_BRACE -> parseTopLevelInlineBlock()
            TokenType.REALM -> parseTopLevelInlineRealmBlock()
            TokenType.IF -> parseTopLevelInlineIf()
            TokenType.ASSERT -> parseTopLevelInlineAssert()
            TokenType.TRACE -> parseTopLevelInlineTrace()
            TokenType.FIN -> { val start = peek(); advance(); advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseInitializer(type); consumeNewline(); noteBridgeTargetConstant(name, init); TopLevel.InlineFin(name, init, start.line, start.column) }
            TokenType.LET -> { val start = peek(); advance(); advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseInitializer(type); consumeNewline(); TopLevel.InlineLet(name, init, start.line, start.column) }
            TokenType.VAR, TokenType.VAL -> { val start = peek(); advance(); val valueMutable = advance().type == TokenType.VAR; val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseInitializer(type); consumeNewline(); TopLevel.InlineVar(name, init, start.line, start.column, valueMutable = valueMutable) }
            TokenType.IDENTIFIER -> { val start = peek(); advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; consume(TokenType.EQUAL, "Expected '='"); val value = parseExpr(); consumeNewline(); TopLevel.InlineAssignment(name, value, start.line, start.column) }
            else -> error("Expected 'func', '{', 'realm', 'if', 'assert', 'trace', 'fin', 'var', 'let', or identifier after 'inline' at line ${peek().line}")
        }
    }

    /**
     * `inline for VAR in [<literals>] { <declarations> }` at top level - compile-time
     * code generation. The body is a template: each `$VAR` inside an identifier (e.g.
     * `oper$op`) is substituted with the current value and the result parsed as
     * top-level declarations. The generated items are queued via [pendingTopLevels].
     */
    private fun parseTopLevelInlineFor(): TopLevel {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.FOR, "Expected 'for'")
        if (check(TokenType.L_PAREN)) {
            error("parallel 'inline for' uses brackets: write 'inline for [A, B] in [L1, L2]' at line ${start.line}")
        }
        // `inline for [t, Ty] in [L1, L2]` - parallel lists, zipped by position. One
        // variable and one list is the common case and stays the same shape; the
        // bracketed form binds each variable to its own list at the same index.
        val loopVars = mutableListOf<String>()
        if (match(TokenType.L_BRACKET)) {
            do { loopVars.add(consumeIdentifierLike("Expected loop variable in 'inline for [...]'")) }
            while (match(TokenType.COMMA))
            consume(TokenType.R_BRACKET, "Expected ']' after 'inline for' variables")
        } else {
            loopVars.add(consumeIdentifierLike("Expected loop variable after 'inline for'"))
        }
        consume(TokenType.IN, "Expected 'in' after 'inline for' variable")
        val lists = mutableListOf<List<String>>()
        if (loopVars.size > 1) {
            consume(TokenType.L_BRACKET, "Expected '[' - a parallel 'inline for' iterates a list per variable")
            do { lists.add(parseComptimeForValues()) } while (match(TokenType.COMMA))
            consume(TokenType.R_BRACKET, "Expected ']' after parallel 'inline for' lists")
            if (lists.size != loopVars.size) {
                error(
                    "'inline for' binds ${loopVars.size} variable(s) but iterates ${lists.size} list(s) " +
                        "at line ${start.line}",
                )
            }
        } else {
            lists.add(parseComptimeForValues())
        }
        val loopVar = loopVars.first()
        val list = lists.first()
        // Optional `with index` - binds a 0-based counter usable as `${list[index]}`.
        val indexVar = if (matchWithKeyword()) {
            consumeIdentifierLike("Expected index variable after 'with'")
        } else null
        consume(TokenType.L_BRACE, "Expected '{' to open 'inline for' body")
        val bodyTokens = captureBraceBody()
        consumeNewline()
        val count = list.size
        for (i in 0 until count) {
            var rendered = bodyTokens
            // Fold `${…}` interpolations (loop var + index bound bare inside) first.
            val bindings = buildList {
                loopVars.forEachIndexed { slot, name -> add(name to lists[slot][i]) }
                if (indexVar != null) add(indexVar to i.toString())
            }
            rendered = foldBraceInterpolation(rendered, bindings)
            loopVars.forEachIndexed { slot, name ->
                rendered = substituteLoopVar(rendered, name, lists[slot][i])
            }
            if (indexVar != null) rendered = substituteLoopVar(rendered, indexVar, i.toString())
            rendered = foldListIndexing(rendered)
            pendingTopLevels.addAll(
                Parser(rendered + Token(TokenType.EOF, "", start.line, start.column), typeListEnv, typeListRealm = typeListRealm).parse().items
            )
        }
        return TopLevel.InlineBlock(emptyList(), start.line, start.column)
    }

    /**
     * Folds `${ <expr> }` compile-time interpolations in an `inline for` body: the
     * loop variables (and the `with index` counter) are substituted bare inside the
     * braces, `list[<int>]` indexing is folded, and the braces are removed - so
     * `${ranks[index]}` becomes the parallel list's element for this iteration.
     */
    private fun foldBraceInterpolation(
        tokens: List<Token>, bindings: List<Pair<String, String>>,
    ): List<Token> {
        val result = mutableListOf<Token>()
        var k = 0
        while (k < tokens.size) {
            val t = tokens[k]
            if (t.type == TokenType.IDENTIFIER && t.lexeme == "$" &&
                tokens.getOrNull(k + 1)?.type == TokenType.L_BRACE
            ) {
                var depth = 1
                var j = k + 2
                val inner = mutableListOf<Token>()
                while (j < tokens.size && depth > 0) {
                    when (tokens[j].type) {
                        TokenType.L_BRACE -> depth += 1
                        TokenType.R_BRACE -> { depth -= 1; if (depth == 0) break }
                        else -> {}
                    }
                    if (depth > 0) inner.add(tokens[j])
                    j += 1
                }
                var folded: List<Token> = inner
                for ((name, value) in bindings) folded = substituteBareIdentifier(folded, name, value)
                folded = foldListIndexing(folded)
                result.addAll(folded)
                k = j + 1
            } else {
                result.add(t)
                k += 1
            }
        }
        return result
    }

    /** Replaces every bare identifier `name` with the tokens of `value`. */
    private fun substituteBareIdentifier(tokens: List<Token>, name: String, value: String): List<Token> {
        val valueTokens = Lexer(value).tokenize().dropLast(1)
        val result = mutableListOf<Token>()
        for (t in tokens) {
            if (t.type == TokenType.IDENTIFIER && t.lexeme == name) result.addAll(valueTokens) else result.add(t)
        }
        return result
    }

    /** Folds `list[<int>]` on a compile-time list variable into the element value. */
    private fun foldListIndexing(tokens: List<Token>): List<Token> {
        val result = mutableListOf<Token>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val list = if (t.type == TokenType.IDENTIFIER) comptimeList(t.lexeme) else null
            if (list != null &&
                tokens.getOrNull(i + 1)?.type == TokenType.L_BRACKET &&
                tokens.getOrNull(i + 2)?.type == TokenType.INT_LITERAL &&
                tokens.getOrNull(i + 3)?.type == TokenType.R_BRACKET
            ) {
                val idx = ((tokens[i + 2].literal as NumericLiteral).value as Long).toInt()
                val value = list.getOrNull(idx)
                    ?: error("compile-time index $idx is out of bounds for list '${t.lexeme}'")
                result.addAll(Lexer(value).tokenize().dropLast(1))
                i += 4
            } else {
                result.add(t)
                i += 1
            }
        }
        return result
    }

    /**
     * Compile-time values of an `inline for` iterable: an int range (`0..4`), a
     * list literal of string/int/type names (`["+", "-"]`, `[Byte, Short]`), a
     * type-list variable (`Integers`), or those joined with `~`.
     */
    private fun parseComptimeForValues(): List<String> {
        if (check(TokenType.INT_LITERAL) &&
            tokens.getOrNull(current + 1)?.type in setOf(TokenType.DOT_DOT, TokenType.DOT_DOT_LESS)
        ) {
            // `inline for n in 2..4 { … }` - the `{` opens the loop body, so it must
            // not be read as a trailing lambda on the range's last operand.
            val expr = withoutTrailingLambda { parseExpr() } as Expr.Range
            val from = (expr.from as Expr.IntLiteral).value
            val to = (expr.to as Expr.IntLiteral).value
            val last = if (expr.inclusive) to else to - 1
            return (from..last).map { it.toString() }
        }
        return parseComptimeListValue()
    }

    /** `term (+ term)*` - a compile-time list value (terms are `[…]` literals or list variables). */
    private fun parseComptimeListValue(): List<String> {
        val result = parseComptimeListTerm().toMutableList()
        while (match(TokenType.PLUS)) result.addAll(parseComptimeListTerm())
        return result
    }

    /**
     * True at `[inline] let/fin/var NAME : [ Elem ] =` when it is a compile-time
     * list: any `[Type]` list, or an `inline` list of any element type (e.g.
     * `inline fin ranks: [Int] = […]`). A plain runtime `[Int]` binding is not one.
     */
    /**
     * True for `typealias Name = [A, B, …]` - a compile-time list of types.
     *
     * A list of types is a name for several types, which is what `typealias`
     * already means; the old `let Name: [Type] = […]` spelling put a *value*
     * keyword on something that never exists at runtime.
     */
    private fun isTypeListAliasAhead(): Boolean {
        if (!check(TokenType.TYPEALIAS)) return false
        if (peekNext()?.type != TokenType.IDENTIFIER) return false
        var i = current + 2
        if (tokens.getOrNull(i)?.type != TokenType.EQUAL) return false
        i += 1
        // A list literal, or a name that already denotes one (`Integers + …`).
        return when (tokens.getOrNull(i)?.type) {
            TokenType.L_BRACKET -> true
            TokenType.IDENTIFIER -> tokens.getOrNull(i)?.lexeme in typeListEnv
            else -> false
        }
    }

    /** `typealias Name = [A, B]` - records the list; emits no runtime item. */
    private fun parseTypeListAlias(): TopLevel {
        val start = peek()
        consume(TokenType.TYPEALIAS, "Expected 'typealias'")
        val name = consume(TokenType.IDENTIFIER, "Expected type-list name").lexeme
        consume(TokenType.EQUAL, "Expected '=' in type-list alias")
        typeListEnv[name] = parseComptimeListValue()
        typeListRealm[name] = typeFunctionNamespacePrefix
        consumeNewline()
        return TopLevel.InlineBlock(emptyList(), start.line, start.column)
    }

    private fun isTypeListBindingAhead(): Boolean {
        var i = current
        val hasInline = tokens.getOrNull(i)?.type == TokenType.INLINE
        if (hasInline) i += 1
        if (tokens.getOrNull(i)?.type !in setOf(TokenType.LET, TokenType.FIN, TokenType.VAR, TokenType.VAL)) return false
        i += 1
        if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return false
        i += 1
        if (tokens.getOrNull(i)?.type != TokenType.COLON) return false
        i += 1
        val t = tokens.getOrNull(i)
        // `[ Type ]` - a type list (grouping types, e.g. `[Type]`, `[Int]`).
        if (t?.type == TokenType.L_BRACKET) {
            i += 1
            val elem = tokens.getOrNull(i)
            if (elem?.type != TokenType.IDENTIFIER) return false
            i += 1
            if (tokens.getOrNull(i)?.type != TokenType.R_BRACKET) return false
            return elem.lexeme == "Type" || hasInline
        }
        // `Array<Elem>` / `std::Array<Elem>` - an inline compile-time value list.
        if (hasInline && t?.type == TokenType.IDENTIFIER) {
            var j = i
            var last = tokens.getOrNull(j)?.lexeme
            while (tokens.getOrNull(j + 1)?.type == TokenType.DOUBLE_COLON &&
                tokens.getOrNull(j + 2)?.type == TokenType.IDENTIFIER
            ) {
                j += 2
                last = tokens.getOrNull(j)?.lexeme
            }
            if (last == "Array") return true
        }
        return false
    }

    /**
     * `[inline] let X: [Elem] = <list>` / `inline fin X: Array<Elem> = @std::arr[…]` -
     * records a compile-time list; emits no runtime item.
     */
    private fun parseTypeListBinding(): TopLevel {
        val start = peek()
        match(TokenType.INLINE) // optional `inline`
        advance() // let/fin/var
        val name = consume(TokenType.IDENTIFIER, "Expected list name").lexeme
        consume(TokenType.COLON, "Expected ':'")
        if (check(TokenType.L_BRACKET)) {
            consume(TokenType.L_BRACKET, "Expected '['")
            consume(TokenType.IDENTIFIER, "Expected element type")
            consume(TokenType.R_BRACKET, "Expected ']'")
        } else {
            // `Array<Elem>` - a value list; `[Elem]` value spellings are reserved for
            // grouping types, so a value binding is written `Array<…>`.
            val type = parseTypeName()
            if (type !is TypeRef.Named || type.name != "Array") {
                error("Expected 'std::Array<…>' or '[Elem]' compile-time list type at line ${start.line}")
            }
        }
        consume(TokenType.EQUAL, "Expected '=' in compile-time list binding")
        typeListEnv[name] = parseComptimeListValue()
        typeListRealm[name] = typeFunctionNamespacePrefix
        consumeNewline()
        return TopLevel.InlineBlock(emptyList(), start.line, start.column)
    }

    private fun parseComptimeListTerm(): List<String> {
        // `@arr[...]` inside its declaring realm, or `@std::arr[...]` outside it:
        // consume the macro prefix and parse the bracket body as compile-time values.
        val isMacro = isMacroInvokeAhead()
        if (isMacro) {
            advance()
            parseQualifiedMacroName()
        }
        if (match(TokenType.L_BRACKET)) {
            val values = mutableListOf<String>()
            if (!check(TokenType.R_BRACKET)) {
                do {
                    values.add(when {
                        // Plain `[…]` groups *types* only; string/int value lists must
                        // be written `@std::arr[…]`.
                        check(TokenType.STRING_LITERAL) || check(TokenType.INT_LITERAL) -> {
                            if (!isMacro) error("value lists must use '@std::arr[…]'; plain '[…]' only groups types at line ${peek().line}")
                            if (check(TokenType.STRING_LITERAL)) advance().literal as String
                            else (advance().literal as NumericLiteral).value.toString()
                        }
                        // A type in the list may be reached through its realm -
                        // `[std::Int, std::Double]`. The qualifier is part of how
                        // the type is named here and is kept: the element is
                        // spliced back into type position, where a module outside
                        // `std` must still write `std::Int`. Dropping it would
                        // leave a bare `Int` that resolves only inside `std`.
                        else -> {
                            val path = StringBuilder(
                                consumeIdentifierLike("Expected a literal or type name in compile-time list")
                            )
                            while (match(TokenType.DOUBLE_COLON)) {
                                path.append("::")
                                    .append(consumeIdentifierLike("Expected a name after '::' in a compile-time list"))
                            }
                            path.toString()
                        }
                    })
                } while (match(TokenType.COMMA))
            }
            consume(TokenType.R_BRACKET, "Expected ']' to close compile-time list")
            return values
        }
        if (isMacro) error("Expected '[...]' after macro prefix in compile-time list at line ${peek().line}")
        val name = consumeIdentifierLike("Expected a compile-time list literal or variable")
        return comptimeList(name) ?: error("Unknown compile-time list '$name' at line ${peek().line}")
    }

    /** Collects the tokens of a brace-delimited body (the opening `{` already consumed). */
    private fun captureBraceBody(): List<Token> {
        val body = mutableListOf<Token>()
        var depth = 1
        while (!isAtEnd()) {
            when (peek().type) {
                TokenType.L_BRACE -> { depth++; body.add(advance()) }
                TokenType.R_BRACE -> {
                    depth--
                    if (depth == 0) { advance(); return body }
                    body.add(advance())
                }
                else -> body.add(advance())
            }
        }
        error("Unterminated 'inline for' body at line ${peek().line}")
    }

    /**
     * Substitutes an `inline for` loop variable in a body token stream, re-lexing
     * touched tokens. A bare identifier equal to [varName] (e.g. `Ty` → `Byte`) and
     * a `$VAR` inside an identifier (e.g. `oper$op` → `oper+`) are both replaced.
     */
    private fun substituteLoopVar(tokens: List<Token>, varName: String, value: String): List<Token> {
        val placeholder = "\$$varName"
        val valueTokens = Lexer(value).tokenize().dropLast(1)
        // A bare `Ty` is replaced only when the value is a type name (a single
        // identifier), so a value loop variable that collides with a real name (e.g.
        // `prop rank` with loop var `rank`) is untouched - use `$rank` for those.
        // A bare use is replaced by the value itself when the value is a name or a
        // number - the two things that mean something on their own in code. An
        // operator (`"+"`) does not, so a bare use of it reads as the string it is.
        // A realm-qualified type (`std::Int`, from a list written outside `std`)
        // is several tokens but still one name, and reads bare exactly as a plain
        // identifier does.
        val isQualifiedPath = valueTokens.size >= 3 && valueTokens.size % 2 == 1 &&
            valueTokens.withIndex().all { (i, t) ->
                if (i % 2 == 0) t.type == TokenType.IDENTIFIER else t.type == TokenType.DOUBLE_COLON
            }
        val bareOk = isQualifiedPath || (valueTokens.size == 1 && valueTokens[0].type in setOf(
            TokenType.IDENTIFIER,
            TokenType.INT_LITERAL,
            TokenType.DOUBLE_LITERAL,
        ))
        val result = mutableListOf<Token>()
        for (t in tokens) {
            when {
                bareOk && t.type == TokenType.IDENTIFIER && t.lexeme == varName -> result.addAll(valueTokens)
                // A loop over `@arr["+", "-"]` binds strings. `$op` splices one as
                // code; a bare `op` reads it as the value it is, which is what lets
                // `inline if op == "/"` decide which element the copy is for.
                t.type == TokenType.IDENTIFIER && t.lexeme == varName ->
                    result.add(Token(TokenType.STRING_LITERAL, "\"$value\"", t.line, t.column, value))
                // Spliced *into* a name (`hold$Ty` over `[std::Float, …]`), only the
                // declaration's own name can appear: a realm is a path to a symbol,
                // not part of its identifier, and `holdstd_Float` is not a name any
                // source may write. The qualifier still travels with the bare form
                // above, which lands in type position where it belongs.
                t.type == TokenType.IDENTIFIER && t.lexeme.contains(placeholder) ->
                    result.addAll(
                        Lexer(t.lexeme.replace(placeholder, value.substringAfterLast("::")))
                            .tokenize().dropLast(1)
                    )
                else -> result.add(t)
            }
        }
        return result
    }

    private fun parseTopLevelInlineBlock(): TopLevel.InlineBlock {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.L_BRACE, "Expected '{'")
        skipNewlines()
        val body = parseTopLevelBlock(deepInline = true)
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return TopLevel.InlineBlock(body, start.line, start.column)
    }

    /** `inline realm { ... }` at top level -- alias for `inline { ... }`. */
    private fun parseTopLevelInlineRealmBlock(): TopLevel.InlineBlock {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.REALM, "Expected 'realm'")
        consume(TokenType.L_BRACE, "Expected '{'")
        skipNewlines()
        val body = parseTopLevelBlock(deepInline = true)
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return TopLevel.InlineBlock(body, start.line, start.column)
    }

    private fun parseTopLevelDeepInline(): TopLevel {
        return when (peekNext()?.type) {
            TokenType.L_BRACE -> parseTopLevelDeepInlineBlock()
            TokenType.REALM -> parseTopLevelDeepInlineRealmBlock()
            TokenType.IF -> parseTopLevelDeepInlineIf()
            else -> error("Expected '{', 'realm', or 'if' after 'deepinline' at line ${peek().line}")
        }
    }

    /** `deepinline realm { ... }` at top level -- alias for `deepinline { ... }`. */
    private fun parseTopLevelDeepInlineRealmBlock(): TopLevel.DeepInlineBlock {
        val start = peek()
        consume(TokenType.DEEPINLINE, "Expected 'deepinline'")
        consume(TokenType.REALM, "Expected 'realm'")
        consume(TokenType.L_BRACE, "Expected '{'")
        skipNewlines()
        val body = parseTopLevelBlock(deepInline = true)
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return TopLevel.DeepInlineBlock(body, start.line, start.column)
    }

    private fun parseTopLevelDeepInlineBlock(): TopLevel.DeepInlineBlock {
        val start = peek()
        consume(TokenType.DEEPINLINE, "Expected 'deepinline'")
        consume(TokenType.L_BRACE, "Expected '{'")
        skipNewlines()
        val body = parseTopLevelBlock(deepInline = true)
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return TopLevel.DeepInlineBlock(body, start.line, start.column)
    }

    private fun parseTopLevelDeepInlineIf(): TopLevel.DeepInlineIf {
        val start = peek()
        consume(TokenType.DEEPINLINE, "Expected 'deepinline'")
        consume(TokenType.IF, "Expected 'if'")
        val condition = withoutTrailingLambda { parseExpr() }
        consume(TokenType.L_BRACE, "Expected '{'")
        skipNewlines()
        val thenBranch = parseTopLevelBlock(deepInline = true)
        consume(TokenType.R_BRACE, "Expected '}'")
        val elseBranch = if (match(TokenType.ELSE)) {
            consume(TokenType.L_BRACE, "Expected '{'")
            skipNewlines()
            val branch = parseTopLevelBlock(deepInline = true)
            consume(TokenType.R_BRACE, "Expected '}'")
            branch
        } else null
        consumeNewline()
        return TopLevel.DeepInlineIf(condition, thenBranch, elseBranch, start.line, start.column)
    }

    private fun parseTopLevelInlineIf(): TopLevel.InlineIf {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.IF, "Expected 'if'")
        val condition = withoutTrailingLambda { parseExpr() }
        consume(TokenType.L_BRACE, "Expected '{'")
        skipNewlines()
        val thenBranch = parseTopLevelBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        val elseBranch = if (match(TokenType.ELSE)) {
            consume(TokenType.L_BRACE, "Expected '{'")
            skipNewlines()
            val branch = parseTopLevelBlock()
            consume(TokenType.R_BRACE, "Expected '}'")
            branch
        } else null
        consumeNewline()
        return TopLevel.InlineIf(condition, thenBranch, elseBranch, start.line, start.column)
    }

    /**
     * Parses top-level items inside an inline/deepinline block.
     * Bare `var`/`fin`/`let`/`if`/assignment are accepted and converted
     * to their `TopLevel.Inline*` equivalents (implicitly compile-time).
     */
    private fun parseTopLevelBlock(deepInline: Boolean = false): List<TopLevel> {
        val items = mutableListOf<TopLevel>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            items.add(parseTopLevelBlockItem(deepInline))
            skipNewlines()
        }
        return items
    }

    private fun parseTopLevelBlockItem(deepInline: Boolean = false): TopLevel {
        // Leading `@Deco(...)` annotations are permitted on declarations nested in
        // a compile-time block (e.g. `std.config`'s `deepinline realm`). They apply
        // to type declarations; value bindings fold to compile-time constants and
        // carry no annotations, so any leading annotations there are metadata-only.
        val annotations = parseAnnotations()
        val start = peek()
        return when {
            check(TokenType.FUNC) -> TopLevel.Func(parseFuncDecl(isInline = deepInline, annotations = annotations))
            check(TokenType.TEST) -> parseTestDecl(annotations)
            check(TokenType.ENUM) -> parseEnumDecl(annotations)
            check(TokenType.PACK) -> parsePack(annotations)
            check(TokenType.ANNOT) -> parseDeco(annotations)
            check(TokenType.ERROR) -> parseFailDecl(annotations)
            check(TokenType.TYPEALIAS) -> parseTypeAlias(annotations)
            check(TokenType.VARIANT) -> parseSlot(annotations)
            check(TokenType.SPEC) -> parseSpec()
            check(TokenType.INLINE) -> parseTopLevelInline()
            check(TokenType.DEEPINLINE) -> parseTopLevelDeepInline()
            check(TokenType.ASSERT) -> {
                // Bare assert inside inline block → InlineAssert at top level
                val assertStmt = parseAssertStmt()
                TopLevel.InlineAssert(assertStmt.condition, assertStmt.message, assertStmt.line, assertStmt.column)
            }
            check(TokenType.TRACE) -> {
                // Bare trace inside inline block → InlineTrace at top level
                val traceStmt = parseTraceStmt()
                TopLevel.InlineTrace(traceStmt.message, traceStmt.line, traceStmt.column, traceStmt.level)
            }
            check(TokenType.NOINLINE) -> {
                advance() // consume 'noinline'
                when {
                    check(TokenType.FUNC) -> TopLevel.Func(parseFuncDecl(isInline = false))
                    check(TokenType.FIN) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseInitializer(type); consumeNewline(); noteBridgeTargetConstant(name, init); TopLevel.FinDecl(name, type, init, start.line, start.column) }
                    check(TokenType.VAR) || check(TokenType.VAL) -> { val valueMutable = advance().type == TokenType.VAR; val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseInitializer(type); consumeNewline(); TopLevel.VarDecl(name, type, init, start.line, start.column, valueMutable = valueMutable) }
                    check(TokenType.LET) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseInitializer(type); consumeNewline(); TopLevel.LetDecl(name, type, init, start.line, start.column) }
                    else -> error("Expected 'func', 'fin', 'var', or 'let' after 'noinline' at line ${peek().line}")
                }
            }
            // Bare declarations: inline if deepInline, runtime otherwise
            check(TokenType.VAR) || check(TokenType.VAL) -> { val valueMutable = advance().type == TokenType.VAR; val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseInitializer(type); consumeNewline(); if (deepInline) TopLevel.InlineVar(name, init, start.line, start.column, valueMutable = valueMutable) else TopLevel.VarDecl(name, type, init, start.line, start.column, valueMutable = valueMutable) }
            check(TokenType.FIN) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseInitializer(type); consumeNewline(); noteBridgeTargetConstant(name, init); if (deepInline) TopLevel.InlineFin(name, init, start.line, start.column) else TopLevel.FinDecl(name, type, init, start.line, start.column) }
            check(TokenType.LET) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseInitializer(type); consumeNewline(); if (deepInline) TopLevel.InlineLet(name, init, start.line, start.column) else TopLevel.LetDecl(name, type, init, start.line, start.column) }
            check(TokenType.IF) -> {
                consume(TokenType.IF, "Expected 'if'")
                val condition = withoutTrailingLambda { parseExpr() }
                consume(TokenType.L_BRACE, "Expected '{'")
                skipNewlines()
                val thenBranch = parseTopLevelBlock(deepInline)
                consume(TokenType.R_BRACE, "Expected '}'")
                val elseBranch = if (match(TokenType.ELSE)) {
                    consume(TokenType.L_BRACE, "Expected '{'")
                    skipNewlines()
                    val branch = parseTopLevelBlock(deepInline)
                    consume(TokenType.R_BRACE, "Expected '}'")
                    branch
                } else null
                consumeNewline()
                TopLevel.InlineIf(condition, thenBranch, elseBranch, start.line, start.column)
            }
            check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.EQUAL -> {
                val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme
                consume(TokenType.EQUAL, "Expected '='")
                val value = parseExpr()
                consumeNewline()
                TopLevel.InlineAssignment(name, value, start.line, start.column)
            }
            else -> error("Unexpected '${peek().lexeme}' inside inline block at line ${peek().line}")
        }
    }

    /**
     * True when `[exposed|protected|confined] realm test {` begins here.
     *
     * The visibility prefix is scanned past because the caller has to decide
     * which form this is before consuming it.
     */
    private fun isTestRealmAhead(): Boolean {
        var i = indexAfterAnnotations(current)
        while (tokens.getOrNull(i)?.type in setOf(TokenType.EXPOSE, TokenType.PROTECT, TokenType.CONFINE)) i++
        if (tokens.getOrNull(i)?.type != TokenType.REALM) return false
        return tokens.getOrNull(i + 1)?.type == TokenType.TEST
    }

    /**
     * `[exposed|protected|confined] realm test { … }` - a realm of test-only
     * declarations.
     *
     * Whatever is declared inside is ordinary code, and is emitted as such; what
     * the realm changes is who may refer to it. Only a `test` block, or another
     * declaration in a test realm, may - so a fixture cannot leak into the
     * program it exists to test.
     *
     * Reach is the ordinary ladder, and it bounds which tests can see it:
     *
     * - `realm test` (the default, [Visibility.Reach.CONFINE]) - tests in this
     *   file.
     * - `protected realm test` - tests within the declaring folder.
     * - `exposed realm test` - tests in any file, with no import.
     *
     * The members are returned flattened: a test realm is a visibility rule, not
     * a namespace, so it does not mangle the names it contains.
     */
    private fun parseTestRealm(): List<TopLevel> {
        val annotations = parseAnnotations()
        // A bare `realm test` is file-local, so the default reach is CONFINE
        // rather than the PUBLIC that an ordinary declaration defaults to.
        val visibility = when {
            match(TokenType.PROTECT) -> Visibility(Visibility.Reach.PROTECT)
            match(TokenType.CONFINE) -> Visibility(Visibility.Reach.CONFINE)
            match(TokenType.EXPOSE) -> when {
                match(TokenType.PROTECT) -> Visibility(Visibility.Reach.PROTECT, isExposed = true)
                match(TokenType.CONFINE) -> Visibility(Visibility.Reach.CONFINE, isExposed = true)
                else -> Visibility(Visibility.Reach.PUBLIC, isExposed = true)
            }
            else -> Visibility(Visibility.Reach.CONFINE)
        }
        consume(TokenType.REALM, "Expected 'realm'")
        consume(TokenType.TEST, "Expected 'test' after 'realm'")
        consume(TokenType.L_BRACE, "Expected '{' to open a test realm")
        skipNewlines()
        val body = mutableListOf<TopLevel>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            when {
                isTestRealmAhead() -> error(
                    "A 'realm test' cannot nest inside another at line ${peek().line} - " +
                        "its declarations are already test-only",
                )
                isRealmNamespaceAhead() -> body.addAll(parseRealmNamespace())
                isRealmBlockAhead() -> body.addAll(parseRealmBlock())
                isTypePropAhead() -> parseTypeProp()
                else -> body.add(parseTopLevel())
            }
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' to close a test realm")
        consumeNewline()
        for (item in body) {
            val name = declaredTopLevelName(item) ?: continue
            testRealmMembers[name] = visibility
        }
        return body
    }

    private fun parseTestDecl(annotations: List<Annotation> = emptyList()): TopLevel.Test {
        val start = peek()
        consume(TokenType.TEST, "Expected 'test'")
        val method = if (match(TokenType.DOT)) {
            val token = consume(TokenType.IDENTIFIER, "Expected 'This' or 'All' after 'test .'")
            TestMethod.entries.firstOrNull { it.name == token.lexeme }
                ?: error("Unknown test method '.${token.lexeme}' at line ${token.line}; expected '.This' or '.All'")
        } else {
            TestMethod.This
        }
        val name = consume(TokenType.STRING_LITERAL, "Expected test name string").literal as String
        val body = if (match(TokenType.L_BRACE)) {
            skipNewlines()
            val parsed = parseBlock()
            consume(TokenType.R_BRACE, "Expected '}' after test body")
            parsed
        } else {
            if (method != TestMethod.All) {
                error("Expected '{' after test name; only 'test .All' may omit its body at line ${start.line}")
            }
            emptyList()
        }
        consumeNewline()
        return TopLevel.Test(name, body, start.line, start.column, annotations, method)
    }

    /**
     * `pack Name { fields }` - a struct declaration. Fields are `[var|fin|let] name: Type [= default]`,
     * one per line.
     */
    /**
     * True when a `union` declaration begins here.
     *
     * `union` is contextual, not reserved: `Set.union(other)` and `fin union = …`
     * mean what they say. A declaration is the one place the word is followed by
     * a type name and then a body or type parameters.
     */
    /**
     * True when a union declaration begins here: `unsafe union Name {`.
     *
     * `union` is **contextual**, not reserved - `Set.union(other)` and
     * `fin union = …` mean what they say - so it only opens a declaration when a
     * type name and a body follow. The `unsafe` prefix is required (see
     * [parsePack]); a bare `union Name {` is matched here too so it can be
     * rejected with that explanation rather than a parse error.
     */
    private fun isUnionDeclAhead(): Boolean {
        var i = current
        if (tokens.getOrNull(i)?.type == TokenType.UNSAFE) i++
        val word = tokens.getOrNull(i) ?: return false
        if (word.type != TokenType.IDENTIFIER || word.lexeme != "union") return false
        if (tokens.getOrNull(i + 1)?.type != TokenType.IDENTIFIER) return false
        val after = tokens.getOrNull(i + 2)?.type
        return after == TokenType.L_BRACE || after == TokenType.LESS
    }

    private fun parsePack(
        annotations: List<Annotation> = emptyList(),
        visibility: Visibility = Visibility.PUBLIC,
        isBridge: Boolean = false,
        isUnion: Boolean = false,
    ): TopLevel.Pack {
        val start = peek()
        val keyword = if (isUnion) "union" else "pack"
        if (isUnion) {
            // A union reinterprets its storage, so nothing about reading one can
            // be checked - which is exactly what `unsafe` marks. Requiring it on
            // the declaration means the hazard is stated where the type is
            // defined, not only where it is used.
            if (!match(TokenType.UNSAFE)) {
                error(
                    "a union reinterprets its storage and cannot be checked; " +
                        "declare it 'unsafe union ${peekNext()?.lexeme ?: ""}' at line ${peek().line}",
                )
            }
            advance() // contextual `union`
        } else {
            consume(TokenType.PACK, "Expected 'pack'")
        }
        if (check(TokenType.LESS)) {
            error("Type parameters go after the $keyword name: write '$keyword Name<…>', not '$keyword<…> Name', at line ${peek().line}")
        }
        // Type parameters follow the name: `pack Box<T>`, `pack Map<K, V>`.
        // A bridge pack may derive its exact backend and Azora names through a
        // declaration-position macro, just like bridge functions and values.
        val parsedBridgeName = if (isBridge) parseBridgeName() else null
        val name = parsedBridgeName?.localName ?: parsedBridgeName?.backendName
            ?: consume(TokenType.IDENTIFIER, "Expected $keyword name").lexeme
        val tp = parseTypeParams()
        constParamEnums = tp.constEnums
        val derives = if (matchContextual("derives")) parseDeriveHeads() else emptyList()
        if (isUnion && derives.isNotEmpty()) {
            error("an unsafe union cannot derive field-wise implementations at line ${start.line}")
        }
        val whereClause = parseWhereClause()
        val minLen = variadicMinLengthOf(whereClause)
        val enforceNumFields = annotations.any { it.name == "EnforceNumFields" }
        enqueueDerives(name, derives, start, tp.names, tp.variadic)
        if (!check(TokenType.L_BRACE)) {
            consumeNewline()
            return TopLevel.Pack(
                name = name,
                fields = emptyList(),
                typeParams = tp.names,
                line = start.line,
                column = start.column,
                annotations = annotations,
                visibility = visibility,
                variadicParam = tp.variadic,
                minVariadicLength = minLen,
                whereClause = whereClause,
                constParams = tp.constParams,
                fieldTemplate = null,
                isBridge = isBridge,
                isUnion = isUnion,
                foreignName = parsedBridgeName?.backendName?.takeIf { parsedBridgeName.localName != null },
                nameMacro = parsedBridgeName?.macro,
            )
        }
        consume(TokenType.L_BRACE, "Expected '{' after $keyword name")
        skipNewlines()
        // A variadic pack may mix fixed fields with one generated field template:
        // `fin metadata: M; inline for Ty in ...T { mixin "$index: $Ty" }`.
        var fieldTemplate: VariadicFieldTemplate? = null
        val fields = mutableListOf<PackField>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            // `inline if/when/for/while/loop { … }` in a pack body - compile-time
            // control flow over the field list. Every form contributes fields to the
            // same flat list; a conditional one stamps its predicate on each field it
            // contributes, so the layout stays fields-with-predicates rather than a
            // tree of blocks.
            if (isInlineFieldFormAhead()) {
                fields.addAll(parseInlineFieldForm(enforceNumFields))
            } else if (tp.variadic != null && check(TokenType.INLINE)) {
                if (fieldTemplate != null) {
                    error("A variadic pack may declare only one inline field template at line ${peek().line}")
                }
                fieldTemplate = parseVariadicFieldTemplate()
            } else {
                if (isUnion && peek().type in setOf(TokenType.VAR, TokenType.VAL, TokenType.LET, TokenType.FIN)) {
                    error(
                        "a union member is written '${peekNext()?.lexeme ?: "name"}: Type' with no binding keyword - " +
                            "its members share one slot, so '${peek().lexeme}' says nothing, at line ${peek().line}",
                    )
                }
                fields.add(parsePackField(enforceNumFields = enforceNumFields))
                match(TokenType.COMMA)
            }
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after $keyword fields")
        consumeNewline()
        if (isUnion) {
            if (fields.isEmpty()) error("union '$name' must declare at least one member at line ${start.line}")
            if (fieldTemplate != null) {
                error("union '$name' cannot generate its members from a variadic template at line ${start.line}")
            }
            // Every member addresses the same storage, so "this one is mutable and
            // that one is not" cannot mean anything. Members are written as bare
            // `name: Type` and are always writable; whether a *binding* permits the
            // write is the separate question the value axis already answers.
            for (i in fields.indices) fields[i] = fields[i].copy(mutable = true)
        }
        return TopLevel.Pack(
            name = name,
            fields = fields,
            typeParams = tp.names,
            line = start.line,
            column = start.column,
            annotations = annotations,
            visibility = visibility,
            variadicParam = tp.variadic,
            minVariadicLength = minLen,
            whereClause = whereClause,
            constParams = tp.constParams,
            constDefaults = tp.constDefaults,
            constEnums = tp.constEnums,
            fieldTemplate = fieldTemplate,
            isBridge = isBridge,
            isUnion = isUnion,
            foreignName = parsedBridgeName?.backendName?.takeIf { parsedBridgeName.localName != null },
            nameMacro = parsedBridgeName?.macro,
        )
    }

    private data class ContractHead(
        val name: String,
        val args: List<TypeRef> = emptyList(),
        val qualifier: String? = null,
    )

    /** One spec or a bracketed list following `derives` / `derive`. */
    private fun parseDeriveHeads(): List<ContractHead> {
        fun one(): ContractHead {
            val (name, qualifier) = parseContractName("Expected a spec name to derive")
            return ContractHead(name, parseGenericTypeArgsIfPresent(), qualifier)
        }
        if (!match(TokenType.L_BRACKET)) return listOf(one())
        if (check(TokenType.R_BRACKET)) error("Expected at least one spec in a derives list at line ${peek().line}")
        val result = mutableListOf<ContractHead>()
        do { result.add(one()) } while (match(TokenType.COMMA))
        consume(TokenType.R_BRACKET, "Expected ']' after the derives list")
        return result
    }

    private fun derivedImpl(
        target: String,
        head: ContractHead,
        start: Token,
        typeParams: List<String> = emptyList(),
        variadicParam: String? = null,
    ): TopLevel.Impl = TopLevel.Impl(
        typeName = target,
        methods = emptyList(),
        traitName = head.name,
        line = start.line,
        column = start.column,
        traitArgs = head.args,
        traitQualifier = head.qualifier,
        typeParams = typeParams,
        variadicParam = variadicParam,
        isDerived = true,
        hasBody = false,
    )

    private fun enqueueDerives(
        target: String,
        heads: List<ContractHead>,
        start: Token,
        typeParams: List<String> = emptyList(),
        variadicParam: String? = null,
    ) {
        heads.forEach { pendingTopLevels.add(derivedImpl(target, it, start, typeParams, variadicParam)) }
    }

    /** `derive Clone for ExistingType` / `derive [Clone, Copy] for [A, B]`. */
    private fun parseDeriveDecl(): TopLevel.Impl {
        val start = consume(TokenType.DERIVE, "Expected 'derive'")
        val heads = parseDeriveHeads()
        consume(TokenType.FOR, "Expected 'for' after derived specs")
        val targets = expandTypeListTargets(parseImplTargets())
        consumeNewline()
        val implementations = targets.flatMap { target -> heads.map { derivedImpl(target, it, start) } }
        pendingTopLevels.addAll(implementations.drop(1))
        return implementations.first()
    }

    /** `inline for <loopVar> in <packVar> with index { <fields> }` - a variadic pack's field template. */
    private fun parseVariadicFieldTemplate(): VariadicFieldTemplate {
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.FOR, "Expected 'for'")
        val loopVar = consume(TokenType.IDENTIFIER, "Expected loop type variable").lexeme
        consume(TokenType.IN, "Expected 'in' after loop variable")
        match(TokenType.ELLIPSIS) // preferred spelling: `inline for Ty in ...T`
        val packVar = consume(TokenType.IDENTIFIER, "Expected variadic pack variable").lexeme
        // Optional `with index` - declares that `$index` interpolates the position.
        if (matchWithKeyword()) {
            consume(TokenType.IDENTIFIER, "Expected 'index' after 'with'").lexeme // 'index'
        }
        consume(TokenType.L_BRACE, "Expected '{' to open variadic field template")
        skipNewlines()
        val tplFields = mutableListOf<TplField>()
        val mixins = mutableListOf<Expr.StringTemplate>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            if (check(TokenType.INLINE)) {
                advance() // 'inline' splice
                val template = parsePrimary()
                val str = template as? Expr.StringTemplate
                    ?: error("Expected interpolated string after 'inline' at line ${peek().line}")
                mixins.add(str)
                consumeNewline()
            } else {
                val fieldName = consumeIdentifierLike("Expected field name in variadic template")
                consume(TokenType.COLON, "Expected ':' after variadic template field name")
                val fieldType = parseTypeName()
                tplFields.add(TplField(fieldName, fieldType))
                match(TokenType.COMMA)
            }
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after variadic field template")
        consumeNewline()
        return VariadicFieldTemplate(loopVar, packVar, tplFields, mixins)
    }


    /** True when an `inline` in a pack body opens a compile-time field form. */
    private fun isInlineFieldFormAhead(): Boolean {
        if (!check(TokenType.INLINE)) return false
        return when (peekNext()?.type) {
            TokenType.IF, TokenType.WHEN, TokenType.WHILE, TokenType.LOOP -> true
            // `inline for Ty in ...T` is the variadic field template, handled
            // separately; `inline for x in <range>` generates ordinary fields.
            TokenType.FOR -> !isVariadicFieldTemplateAhead()
            else -> false
        }
    }

    /** True for `inline for <v> in ...<pack>` - the variadic template, not a field loop. */
    private fun isVariadicFieldTemplateAhead(): Boolean {
        var i = current + 2
        if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return false
        i++
        if (tokens.getOrNull(i)?.type != TokenType.IN) return false
        i++
        return tokens.getOrNull(i)?.type == TokenType.ELLIPSIS
    }

    /**
     * One `inline when` arm's pattern.
     *
     * `.RowMajor` names a variant of whatever enum the subject ranges over, and
     * becomes that variant's position - which is what the subject is bound to in a
     * specialization, so the arm's guard is an ordinary comparison of two numbers.
     */
    private fun parseArmPattern(subject: Expr): Expr {
        val at = peek()
        if (!check(TokenType.DOT)) return parseExpr()
        val owner = (subject as? Expr.Identifier)?.name?.let { constParamEnums[it] }
            ?: error("'.${peekNext()?.lexeme}' needs an enum-typed subject at line ${at.line}")
        advance() // '.'
        val variant = consume(TokenType.IDENTIFIER, "Expected enum variant after '.'").lexeme
        val ordinal = declaredEnums[owner]?.indexOf(variant) ?: -1
        if (ordinal < 0) error("'$owner' has no variant '$variant', at line ${at.line}")
        return Expr.IntLiteral(ordinal.toLong(), at.line, at.column)
    }

    /**
     * Parses one compile-time field form and returns the fields it contributes.
     *
     * `if`/`when` guard their fields with a predicate; `for`/`while`/`loop` repeat a
     * body whose bounds are compile-time values. All of them flatten into the pack's
     * field list, because a pack's layout is a list of fields either way.
     */
    private fun parseInlineFieldForm(enforceNumFields: Boolean): List<PackField> {
        consume(TokenType.INLINE, "Expected 'inline'")
        val keyword = advance()
        val result = mutableListOf<PackField>()
        when (keyword.type) {
            TokenType.IF -> {
                val condition = withoutTrailingLambda { parseExpr() }
                result += guardedFieldBlock(condition, enforceNumFields)
                skipNewlines()
                // `else` and `else inline if` chain, each guarded by the negation of
                // everything before it.
                var accumulated: Expr = condition
                while (check(TokenType.ELSE)) {
                    advance()
                    val negated = Expr.Unary(TokenType.BANG, accumulated, keyword.line)
                    if (check(TokenType.INLINE)) advance()
                    if (check(TokenType.IF)) {
                        advance()
                        val next = withoutTrailingLambda { parseExpr() }
                        result += guardedFieldBlock(conjoin(next, negated), enforceNumFields)
                        accumulated = Expr.Binary(accumulated, TokenType.OR_OR, next, keyword.line)
                    } else {
                        result += guardedFieldBlock(negated, enforceNumFields)
                        break
                    }
                    skipNewlines()
                }
            }
            TokenType.WHEN -> {
                // `inline when <subject> { <pattern> -> { fields } … }`; each arm is
                // guarded by `subject == pattern`.
                val subject = withoutTrailingLambda { parseExpr() }
                consume(TokenType.L_BRACE, "Expected '{' after 'inline when' subject")
                skipNewlines()
                while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                    skipNewlines()
                    if (check(TokenType.R_BRACE)) break
                    val guard = if (check(TokenType.ELSE)) {
                        advance()
                        null
                    } else {
                        Expr.Binary(subject, TokenType.EQUAL_EQUAL, parseArmPattern(subject), keyword.line)
                    }
                    if (!match(TokenType.FAT_ARROW) && !match(TokenType.ARROW)) {
                        error("Expected '=>' in 'inline when' arm at line ${peek().line}")
                    }
                    skipNewlines()
                    val armFields = fieldBlock(enforceNumFields)
                    result += armFields.map { it.copy(condition = conjoin(it.condition, guard)) }
                    skipNewlines()
                }
                consume(TokenType.R_BRACE, "Expected '}' after 'inline when' arms")
                consumeNewline()
            }
            TokenType.FOR -> {
                // `inline for i in <range> { fields }` - the body repeats per value.
                val loopVar = consumeIdentifierLike("Expected loop variable in 'inline for'")
                consume(TokenType.IN, "Expected 'in' after 'inline for' variable")
                val iterable = withoutTrailingLambda { parseExpr() }
                result += repeatedFieldBlock(loopVar, iterable, enforceNumFields)
            }
            TokenType.WHILE, TokenType.LOOP -> {
                // Bounded by a compile-time condition; the body's fields are recorded
                // once and repeated during expansion.
                val condition =
                    if (keyword.type == TokenType.WHILE) withoutTrailingLambda { parseExpr() } else null
                result += guardedFieldBlock(condition, enforceNumFields)
            }
            else -> error("Unsupported 'inline' form in a pack body at line ${keyword.line}")
        }
        return result
    }

    /** The fields of a `{ … }` block, with no guard applied. */
    private fun fieldBlock(enforceNumFields: Boolean): List<PackField> {
        consume(TokenType.L_BRACE, "Expected '{' to open inline field block")
        skipNewlines()
        val fields = mutableListOf<PackField>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            // A block may hold further compile-time control flow - an `inline when`
            // arm containing `inline for`, a loop inside a loop - so the same field
            // forms are available here as at the top of a pack body.
            if (isInlineFieldFormAhead()) {
                fields.addAll(parseInlineFieldForm(enforceNumFields))
            } else {
                fields.add(parsePackField(enforceNumFields = enforceNumFields))
            }
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after inline field block")
        consumeNewline()
        return fields
    }

    /** A field block whose fields all carry [guard] in addition to their own. */
    private fun guardedFieldBlock(guard: Expr?, enforceNumFields: Boolean): List<PackField> =
        fieldBlock(enforceNumFields).map { it.copy(condition = conjoin(it.condition, guard)) }

    /**
     * A field block repeated over a compile-time iterable.
     *
     * The loop variable is recorded on each field's condition so expansion knows what
     * to substitute; until the pack is bound the body is kept once.
     */
    private fun repeatedFieldBlock(
        loopVar: String,
        iterable: Expr,
        enforceNumFields: Boolean,
    ): List<PackField> {
        // The loop is recorded on each field it contains, outermost first, and the
        // layout is expanded once the range is a known number of values.
        val repeat = FieldRepeat(loopVar, iterable)
        return fieldBlock(enforceNumFields).map { it.copy(repeats = listOf(repeat) + it.repeats) }
    }

    /**
     * `inline for <var> in <compile-time list> { members }` inside an impl.
     *
     * Generates one copy of the member block per value. The body is captured as
     * tokens and re-parsed per iteration through a synthetic impl, so members written
     * inside the loop go through exactly the same member parser as those written
     * outside it - there is no second notion of what an impl member is.
     */
    private fun parseInlineMemberFor(typeName: String): List<FuncDecl> {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.FOR, "Expected 'for'")
        val loopVars = mutableListOf<String>()
        if (match(TokenType.L_BRACKET)) {
            do { loopVars.add(consumeIdentifierLike("Expected loop variable in 'inline for [...]'")) }
            while (match(TokenType.COMMA))
            consume(TokenType.R_BRACKET, "Expected ']' after 'inline for' variables")
        } else {
            loopVars.add(consumeIdentifierLike("Expected loop variable after 'inline for'"))
        }
        consume(TokenType.IN, "Expected 'in' after 'inline for' variable")
        val lists = mutableListOf<List<String>>()
        if (loopVars.size > 1) {
            consume(TokenType.L_BRACKET, "Expected '[' - a parallel 'inline for' iterates a list per variable")
            do { lists.add(parseComptimeForValues()) } while (match(TokenType.COMMA))
            consume(TokenType.R_BRACKET, "Expected ']' after parallel 'inline for' lists")
        } else {
            lists.add(parseComptimeForValues())
        }
        val indexVar = if (matchWithKeyword()) {
            consumeIdentifierLike("Expected index variable after 'with'")
        } else null
        consume(TokenType.L_BRACE, "Expected '{' to open 'inline for' body")
        val bodyTokens = captureBraceBody()
        consumeNewline()
        val generated = mutableListOf<FuncDecl>()
        for (i in 0 until lists.first().size) {
            var rendered = bodyTokens
            val bindings = buildList {
                loopVars.forEachIndexed { slot, name -> add(name to lists[slot][i]) }
                if (indexVar != null) add(indexVar to i.toString())
            }
            rendered = foldBraceInterpolation(rendered, bindings)
            loopVars.forEachIndexed { slot, name ->
                rendered = substituteLoopVar(rendered, name, lists[slot][i])
            }
            if (indexVar != null) rendered = substituteLoopVar(rendered, indexVar, i.toString())
            rendered = foldListIndexing(rendered)
            // `inline fin name = "…"` binds a compile-time name for the rest of the
            // body. Its value is a string whose interpolations refer to the loop
            // bindings, so it is resolved here rather than left to a later stage.
            rendered = foldInlineBindings(rendered, bindings)
            // Re-parse through a synthetic impl so the ordinary member parser runs.
            val wrapper = listOf(
                Token(TokenType.IMPL, "impl", start.line, start.column),
                Token(TokenType.IDENTIFIER, typeName, start.line, start.column),
                Token(TokenType.L_BRACE, "{", start.line, start.column),
            ) + rendered + listOf(
                Token(TokenType.R_BRACE, "}", start.line, start.column),
                Token(TokenType.EOF, "", start.line, start.column),
            )
            val parsed = Parser(wrapper, typeListEnv, declaredEnums, typeListRealm = typeListRealm).parse().items
            parsed.filterIsInstance<TopLevel.Impl>().forEach { generated.addAll(it.methods) }
        }
        return generated
    }

    /**
     * Folds `inline fin <name> = "<text>"` out of a compile-time body.
     *
     * The declaration binds a name, not a member, so it is consumed and its value
     * substituted into everything after it - `inline fin assignOp = "${op}="` makes
     * `$assignOp` usable below. Interpolations in the value are resolved against
     * [bindings], because `substituteLoopVar` rewrites identifiers only and never
     * looks inside a string token.
     */
    private fun foldInlineBindings(body: List<Token>, bindings: List<Pair<String, String>>): List<Token> {
        var result = body
        val known = bindings.toMutableList()
        while (true) {
            val at = result.indices.firstOrNull { i ->
                result[i].type == TokenType.INLINE &&
                    result.getOrNull(i + 1)?.type == TokenType.FIN &&
                    result.getOrNull(i + 2)?.type == TokenType.IDENTIFIER &&
                    result.getOrNull(i + 3)?.type == TokenType.EQUAL &&
                    result.getOrNull(i + 4) != null
            } ?: break
            val name = result[at + 2].lexeme
            // The value runs to the end of the line.
            var end = at + 4
            while (end < result.size && result[end].type != TokenType.NEWLINE) end++
            val valueTokens = result.subList(at + 4, end)
            val afterValue = if (end < result.size) end + 1 else end
            val single = valueTokens.singleOrNull()
            if (single != null &&
                (single.type == TokenType.STRING_LITERAL || single.type == TokenType.INTERPOLATED_STRING)
            ) {
                // A string binds a NAME: resolve its interpolations against the
                // bindings in scope and splice it wherever the name is used.
                var value = (single.literal as? String) ?: single.lexeme.removeSurrounding("\"")
                for ((k, v) in known) value = value.replace("\${$k}", v).replace("\$$k", v)
                known.add(name to value)
                result = result.subList(0, at) + result.subList(afterValue, result.size)
                result = substituteLoopVar(result, name, value)
            } else {
                // Anything else binds an EXPRESSION. It may depend on a type parameter
                // (`T is Integer`), which is unknown until the pack is specialized, so
                // the tokens are substituted as-is and evaluated by whatever consumes
                // the name - the binding is a name for an expression, not a value.
                val expression = valueTokens.toList()
                result = result.subList(0, at) + result.subList(afterValue, result.size)
                result = substituteTokenSequence(result, name, expression)
            }
        }
        return result
    }

    /** Replaces every bare use of [name] with [replacement], parenthesised. */
    private fun substituteTokenSequence(
        tokens: List<Token>,
        name: String,
        replacement: List<Token>,
    ): List<Token> {
        if (replacement.isEmpty()) return tokens
        val out = mutableListOf<Token>()
        for (t in tokens) {
            if (t.type == TokenType.IDENTIFIER && (t.lexeme == name || t.lexeme == "\$$name")) {
                out.add(Token(TokenType.L_PAREN, "(", t.line, t.column))
                out.addAll(replacement)
                out.add(Token(TokenType.R_PAREN, ")", t.line, t.column))
            } else {
                out.add(t)
            }
        }
        return out
    }

    /**
     * `inline if <cond> { inline "<fragment>" }` between a signature and its body.
     *
     * The fragment is source text spliced into the signature when the condition
     * holds - `inline "?! MathError"` makes the declaration failable. The condition
     * may name a type parameter, so it is kept with the declaration and resolved
     * once the type is concrete; a condition that is statically decidable is folded
     * here instead.
     *
     * Returns the tokens to splice, or null when nothing applies.
     */
    private fun parseSignatureFragment(): List<Token>? {
        val meaningful = nextMeaningfulIndex()
        if (tokens.getOrNull(meaningful)?.type != TokenType.INLINE) return null
        if (tokens.getOrNull(meaningful + 1)?.type != TokenType.IF) return null
        while (current < meaningful) advance()
        advance() // 'inline'
        advance() // 'if'
        val condition = withoutTrailingLambda { parseExpr() }
        consume(TokenType.L_BRACE, "Expected '{' after 'inline if' signature condition")
        skipNewlines()
        consume(TokenType.INLINE, "Expected 'inline \"…\"' inside a signature fragment")
        val text = consume(TokenType.STRING_LITERAL, "Expected a quoted fragment after 'inline'")
        skipNewlines()
        consume(TokenType.R_BRACE, "Expected '}' after signature fragment")
        // A condition that is already decidable folds now; anything mentioning a type
        // parameter cannot be, and conservatively does not apply the fragment.
        val holds = staticTruth(condition) ?: return null
        if (!holds) return null
        val body = (text.literal as? String) ?: text.lexeme.removeSurrounding("\"")
        return Lexer(body).tokenize().dropLast(1)
    }

    /** The truth of a constant condition, or null when it is not decidable here. */
    private fun staticTruth(expr: Expr): Boolean? = when (expr) {
        is Expr.BoolLiteral -> expr.value
        is Expr.Grouping -> staticTruth(expr.expr)
        is Expr.Binary -> when (expr.op) {
            TokenType.AND_AND -> {
                val l = staticTruth(expr.left)
                val r = staticTruth(expr.right)
                when {
                    l == false || r == false -> false
                    l == true && r == true -> true
                    else -> null
                }
            }
            TokenType.OR_OR -> {
                val l = staticTruth(expr.left)
                val r = staticTruth(expr.right)
                when {
                    l == true || r == true -> true
                    l == false && r == false -> false
                    else -> null
                }
            }
            TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL -> {
                val l = literalText(expr.left)
                val r = literalText(expr.right)
                if (l == null || r == null) null
                else if (expr.op == TokenType.EQUAL_EQUAL) l == r else l != r
            }
            else -> null
        }
        else -> null
    }

    /** The literal text an expression denotes, for constant comparison. */
    private fun literalText(expr: Expr): String? = when (expr) {
        is Expr.StringLiteral -> expr.value
        is Expr.IntLiteral -> expr.value.toString()
        is Expr.Grouping -> literalText(expr.expr)
        else -> null
    }

    /**
     * Re-reads [ret] with [fragment] appended, so a spliced `?! E` becomes a real
     * failable return type rather than a special case.
     */
    private fun applySignatureFragment(
        ret: TypeAnnotation,
        fragment: List<Token>,
        at: Token,
    ): TypeAnnotation {
        val base = (ret as? TypeAnnotation.Explicit)?.ref ?: return ret
        val rendered = Lexer(base.toString()).tokenize().dropLast(1) + fragment +
            Token(TokenType.EOF, "", at.line, at.column)
        return TypeAnnotation.Explicit(Parser(rendered, typeListEnv, declaredEnums, typeListRealm = typeListRealm).parseTypeNameForFragment())
    }

    /** Entry point for re-parsing a type built from a signature fragment. */
    internal fun parseTypeNameForFragment(): TypeRef = parseTypeName()

    /**
     * The suffix that distinguishes one operator overload from another on a type.
     *
     * Two things separate overloads of the same operator: the operand it accepts
     * (`v + v` and `v + scalar`), and - for operators that do not already imply
     * mutation - whether the receiver is borrowed shared or exclusive (`v[i]` read
     * against `v[i]` written through). An operator declared once keeps the bare
     * name, so the common case is unaffected.
     */
    private fun operatorOverloadSuffix(
        opName: String,
        receiver: ParamModifier,
        operands: List<Param>,
    ): String {
        // `+=` and `[]=` mutate by definition; their receiver says nothing extra.
        val mutationImplied = opName == "indexSet" ||
            (opName.endsWith("=") && opName !in setOf("==", "!=", "<=", ">="))
        val exclusive = if (!mutationImplied && receiver.writable) "!" else ""
        val operand = operands.singleOrNull()?.let { "@" + operandTypeKey(it.type) } ?: ""
        return exclusive + operand
    }

    /** The operand-type key used to distinguish operator overloads. */
    private fun operandTypeKey(type: TypeRef): String = when (type) {
        is TypeRef.Named -> type.name
        is TypeRef.Reference -> operandTypeKey(type.inner)
        is TypeRef.Pointer -> operandTypeKey(type.inner)
        is TypeRef.Nullable -> operandTypeKey(type.inner)
        else -> "_"
    }

    private fun parsePackField(
        preparsedVisibility: Visibility? = null,
        enforceNumFields: Boolean = false,
        preparsedAnnotations: List<Annotation>? = null,
        requireFin: Boolean = false,
    ): PackField {
        val annotations = preparsedAnnotations ?: parseAnnotations()
        val visibility = preparsedVisibility ?: parseVisibility()
        if (requireFin && !check(TokenType.FIN)) {
            error("Decorator fields must be declared with 'fin' at line ${peek().line}")
        }
        // `unsafe fin data: T*` - the field is readable only inside an unsafe
        // scope, so a pack can carry a raw pointer without handing the obligation
        // to everyone who reads its safe members.
        val isUnsafeField = match(TokenType.UNSAFE)
        // A field's mutability is the value axis: `var` fields can be written
        // through the pack, `val`/`fin`/`let` fields cannot.
        val mutable = when {
            check(TokenType.VAR) -> { advance(); true }
            check(TokenType.VAL) -> { advance(); false }
            check(TokenType.FIN) -> { advance(); false }
            check(TokenType.LET) -> { advance(); false }
            else -> false
        }
        val name = when {
            enforceNumFields && check(TokenType.INT_LITERAL) -> advance().lexeme
            else -> consumeIdentifierLike("Expected field name")
        }
        val type: TypeRef
        val default: Expr?
        if (match(TokenType.COLON)) {
            type = parseTypeName()
            default = if (match(TokenType.EQUAL)) parseExpr() else null
        } else {
            consume(TokenType.EQUAL, "Expected ':' or '=' after pack field name")
            default = parseExpr()
            type = inferPackFieldType(default)
                ?: error("Cannot infer type of field '$name' at line ${default.line}; add an explicit ': Type'")
        }
        // `var w: T = 0 where N == 4` - the same predicate written on one field.
        val condition = parseWhereClause()
        consumeNewline()
        return PackField(name, type, mutable, default, visibility, isUnsafeField, annotations, condition = condition)
    }

    /** Combines two field predicates; either may be absent. */
    private fun conjoin(inner: Expr?, outer: Expr?): Expr? = when {
        inner == null -> outer
        outer == null -> inner
        else -> Expr.Binary(outer, TokenType.AND_AND, inner, outer.line)
    }

    /**
     * Infers declaration types that are fully determined by syntax.
     *
     * Field layouts must be known before semantic analysis, so this deliberately
     * accepts literals, constructor-shaped calls, and homogeneous collection
     * literals only. Expressions that require symbol lookup keep the diagnostic
     * local and ask for an explicit field type.
     */
    private fun inferPackFieldType(expr: Expr): TypeRef? = when (expr) {
        is Expr.IntLiteral -> TypeRef.Named(
            when (expr.suffix) {
                NumericSuffix.NONE -> "Int"
                NumericSuffix.BYTE -> "Byte"
                NumericSuffix.UBYTE -> "UByte"
                NumericSuffix.SHORT -> "Short"
                NumericSuffix.USHORT -> "UShort"
                NumericSuffix.UINT -> "UInt"
                NumericSuffix.LONG -> "Long"
                NumericSuffix.ULONG -> "ULong"
                NumericSuffix.CENT -> "Cent"
                NumericSuffix.UCENT -> "UCent"
                NumericSuffix.FLOAT -> "Float"
                NumericSuffix.DECIMAL -> "Decimal"
            },
        )
        is Expr.DoubleLiteral -> TypeRef.Named(
            when (expr.suffix) {
                NumericSuffix.FLOAT -> "Float"
                NumericSuffix.DECIMAL -> "Decimal"
                else -> "Double"
            },
        )
        is Expr.StringLiteral, is Expr.StringTemplate -> TypeRef.Named("String")
        is Expr.BoolLiteral -> TypeRef.Named("Bool")
        is Expr.CharLiteral -> TypeRef.Named("Char")
        is Expr.Grouping -> inferPackFieldType(expr.expr)
        is Expr.Call -> TypeRef.Named(expr.callee, expr.typeArgs)
        is Expr.ArrayLiteral -> expr.elements.firstOrNull()
            ?.let(::inferPackFieldType)
            ?.let(TypeRef::Array)
        is Expr.SetLiteral -> expr.elements.firstOrNull()
            ?.let(::inferPackFieldType)
            ?.let(TypeRef::Set)
        is Expr.MapLit -> expr.entries.firstOrNull()?.let { (key, value) ->
            val keyType = inferPackFieldType(key)
            val valueType = inferPackFieldType(value)
            if (keyType != null && valueType != null) TypeRef.Map(keyType, valueType) else null
        }
        is Expr.TupleLit -> TypeRef.Tuple(expr.elements.mapNotNull(::inferPackFieldType))
            .takeIf { it.elements.size == expr.elements.size }
        else -> null
    }

    /** `enum Name { Var1; Var2; ... }` - variants one per line, each optionally with trailing annotations. */
    private fun parseEnumDecl(annotations: List<Annotation> = emptyList()): TopLevel.Enum {
        val start = peek()
        consume(TokenType.ENUM, "Expected 'enum'")
        val name = consume(TokenType.IDENTIFIER, "Expected enum name").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after enum name")
        skipNewlines()
        val variants = mutableListOf<String>()
        val variantAnns = mutableListOf<List<Annotation>>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            do {
                val variant = consume(TokenType.IDENTIFIER, "Expected variant name").lexeme
                // A payload is what separates the two enum forms, so it is also
                // where the right one can be named.
                if (check(TokenType.L_PAREN)) {
                    error(
                        "case '$variant' of enum '$name' carries a payload; " +
                            "write 'variant enum $name { … }' for a tagged union, at line ${peek().line}",
                    )
                }
                variants.add(variant)
                variantAnns.add(parseAnnotations()) // trailing `@ann` on the same line
            } while (match(TokenType.COMMA) && check(TokenType.IDENTIFIER))
        }
        consume(TokenType.R_BRACE, "Expected '}' after enum variants")
        consumeNewline()
        declaredEnums[name] = variants
        return TopLevel.Enum(name, variants, start.line, start.column, annotations, variantAnns)
    }

    /** `fail ErrSet { V1, V2 }` - an error-set declaration (variants one per line, each optionally annotated). */
    private fun parseFailDecl(annotations: List<Annotation> = emptyList()): TopLevel.Fail {
        val start = peek()
        consume(TokenType.ERROR, "Expected 'fail'")
        val name = consume(TokenType.IDENTIFIER, "Expected error-set name").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after error-set name")
        skipNewlines()
        val variants = mutableListOf<String>()
        val variantAnns = mutableListOf<List<Annotation>>()
        val variantPayloads = mutableListOf<List<TypeRef>>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            do {
                val variant = consume(TokenType.IDENTIFIER, "Expected variant name").lexeme
                // A payload is what separates the two error forms, so it is also
                // where the right one can be named.
                if (check(TokenType.L_PAREN)) {
                    error(
                        "case '$variant' of error set '$name' carries a payload; " +
                            "write 'variant error $name { … }' for a tagged union, at line ${peek().line}",
                    )
                }
                variants.add(variant)
                variantPayloads.add(emptyList())
                variantAnns.add(parseAnnotations()) // trailing `@ann` on the same line
            } while (match(TokenType.COMMA) && check(TokenType.IDENTIFIER))
        }
        consume(TokenType.R_BRACE, "Expected '}' after error-set variants")
        consumeNewline()
        // A payload-carrying error set is also a slot: that is what already models a
        // named variant with fields, so construction and `when` matching come from the
        // machinery that exists rather than a parallel implementation.
        if (variantPayloads.any { it.isNotEmpty() }) {
            pendingTopLevels.add(
                TopLevel.Slot(
                    name,
                    variants.mapIndexed { i, v -> TopLevel.SlotVariant(v, variantPayloads[i]) },
                    start.line,
                    start.column,
                    annotations,
                ),
            )
        }
        return TopLevel.Fail(name, variants, start.line, start.column, annotations, variantAnns, variantPayloads)
    }

    /** Skips `<T, U>` generic type arguments at the current position (erased at IR). */
    /**
     * The type-parameter names of the impl target most recently parsed.
     *
     * `impl Vec<T, N>::` erases its arguments - the members it holds are ordinary
     * generic declarations - but `T` still has to name something inside them, so the
     * names are kept here for the caller to put back.
     */
    private var lastImplTypeParams: List<String> = emptyList()

    /** The variadic parameter of the last impl target, when it declared one. */
    private var lastImplVariadicParam: String? = null

    private fun skipGenericTypeArgs() {
        lastImplTypeParams = emptyList()
        lastImplVariadicParam = null
        if (!check(TokenType.LESS)) return
        advance() // '<'
        var depth = 1
        val names = mutableListOf<String>()
        while (depth > 0) {
            // At the top level of `<…>`, an identifier that is not part of a nested
            // application names a parameter; `N: Int` contributes `N`.
            if (depth == 1 && peek().type == TokenType.IDENTIFIER &&
                peekNext()?.type != TokenType.LESS &&
                tokens.getOrNull(current - 1)?.type != TokenType.COLON
            ) {
                names.add(peek().lexeme)
                // `Tuple<...T>` - the target declares a variadic parameter, and
                // the ellipsis is the only thing that says so.
                if (tokens.getOrNull(current - 1)?.type == TokenType.ELLIPSIS) {
                    lastImplVariadicParam = peek().lexeme
                }
            }
            when (peek().type) { TokenType.LESS -> depth++; TokenType.GREATER -> depth--; else -> {} }
            advance()
        }
        lastImplTypeParams = names
    }

    /** Parses `<T, U>` generic type arguments at the current position. */
    private fun parseGenericTypeArgsIfPresent(): List<TypeRef> {
        if (!check(TokenType.LESS)) return emptyList()
        advance() // '<'
        val args = mutableListOf<TypeRef>()
        if (!check(TokenType.GREATER)) {
            do {
                args.add(parseTypeArg())
                // `impl Array<T, N: Int>` - an impl target *declares* its parameters,
                // so an entry may carry a bound. The bound belongs to the type's own
                // declaration; here it only has to parse.
                if (match(TokenType.COLON)) parseTypeName()
            } while (match(TokenType.COMMA))
        }
        // `Q<T, N>>` lexes its tail as one `>>`, so closing here leaves a `>` for the
        // enclosing application to consume.
        when {
            pendingGreater -> pendingGreater = false
            check(TokenType.SHIFT_RIGHT) -> { advance(); pendingGreater = true }
            else -> consume(TokenType.GREATER, "Expected '>' after generic type arguments")
        }
        return args
    }

    /** `(a, b)` after a compile-time type call's type arguments. */
    private fun parseTypeCallValueArgs(): List<Expr> {
        consume(TokenType.L_PAREN, "Expected '(' before type call arguments")
        val args = mutableListOf<Expr>()
        if (!check(TokenType.R_PAREN)) {
            do {
                skipNewlines()
                args.add(parseExpr())
                skipNewlines()
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.R_PAREN, "Expected ')' after type call arguments")
        return args
    }

    /**
     * A single type argument: a type, or a const value (`3` in `Array<Int, 3>`).
     * An integer literal becomes a [TypeRef.Const] (a const-generic value arg).
     */
    private fun parseTypeArg(): TypeRef = when {
        check(TokenType.INT_LITERAL) -> {
            val t = advance()
            TypeRef.Const((t.literal as NumericLiteral).value as Long)
        }
        // `Mat<Double, 4, 4, .ColumnMajor>` - an enum variant as a const argument.
        check(TokenType.DOT) || (check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.DOT &&
            peek().lexeme in declaredEnums) -> parseConstArgument(null, "type argument")
        check(TokenType.STAR) -> {
            advance()
            TypeRef.Named("*")
        }
        // `std::tupleOf<Entity, ...T>(…)` - a type pack spread among the type
        // arguments. A call whose element types cannot be read back off its
        // arguments has to be able to state them, and `...T` is already how a
        // declaration spells "every type this pack stands for".
        check(TokenType.ELLIPSIS) -> {
            advance()
            when (val inner = parseTypeName()) {
                is TypeRef.Named -> inner.copy(variadic = true)
                else -> error("only a named type can be spread in type arguments at line ${peek().line}")
            }
        }
        else -> parseTypeName()
    }

    private fun typeMethodSuffix(type: TypeRef): String {
        return UseAsTemplate.typeMemberSuffix(type)
    }

    private fun castMethodName(type: TypeRef): String = "as${typeMethodSuffix(type)}"

    private fun callbackTraitMethodName(traitName: String, traitArgs: List<TypeRef>): String {
        return callbackTraitMethodName(traitName, traitArgs, callbackSpecs[traitName])
    }

    private fun callbackTraitMethodName(traitName: String, traitArgs: List<TypeRef>, callback: SpecCallback?): String {
        callback?.let {
            it.useAsTemplate?.let { template -> return UseAsTemplate.expand(template, it.typeParams, traitArgs) }
        }
        return if (traitName.isEmpty()) "callback" else traitName[0].lowercaseChar() + traitName.drop(1)
    }

    private fun callbackTraitReturnType(traitName: String, traitArgs: List<TypeRef>): TypeAnnotation {
        val callback = callbackSpecs[traitName]
        return TypeAnnotation.Explicit(substituteCallbackType(callback?.returnType, callback?.typeParams.orEmpty(), traitArgs))
    }

    private fun callbackTraitParams(traitName: String, traitArgs: List<TypeRef>): List<Param> {
        val callback = callbackSpecs[traitName] ?: return emptyList()
        return callbackTraitParams(callback, traitArgs)
    }

    private fun callbackTraitParams(callback: SpecCallback, traitArgs: List<TypeRef>): List<Param> {
        return callback.params.map { param ->
            param.copy(type = substituteCallbackType(param.type, callback.typeParams, traitArgs))
        }
    }

    private fun callbackTraitCallStyle(traitName: String): MemberCallStyle {
        val callback = callbackSpecs[traitName]
        return if (callback?.requiresParens == true) MemberCallStyle.METHOD else MemberCallStyle.PROPERTY
    }

    private fun substituteCallbackType(type: TypeRef?, typeParams: List<String>, traitArgs: List<TypeRef>): TypeRef {
        val ref = type ?: return traitArgs.firstOrNull() ?: TypeRef.Named("Any", synthesized = true)
        val named = ref as? TypeRef.Named ?: return ref
        val index = typeParams.indexOf(named.name)
        return if (index >= 0) traitArgs.getOrElse(index) { named } else named
    }

    /**
     * Parses the name after `impl`, accepting a realm qualifier.
     *
     * A spec declared inside `realm std` is only reachable from outside as
     * `std::Serializer`, so `impl std::Serializer<T> for MyCodec` has to be
     * writable or the spec cannot be implemented outside the standard library.
     * As in type position the realm is a path to the declaration rather than
     * part of its name, so the qualifier is dropped once it has been read.
     *
     * `impl Type:: { … }` is the type-scoped static block and keeps its `::`,
     * which is why a qualifier is only taken when a name follows it.
     */
    private fun parseContractName(message: String): Pair<String, String?> {
        val path = mutableListOf(consume(TokenType.IDENTIFIER, message).lexeme)
        while (check(TokenType.DOUBLE_COLON) && peekNext()?.type == TokenType.IDENTIFIER) {
            advance() // '::'
            path.add(advance().lexeme)
        }
        return path.last() to path.dropLast(1).takeIf { it.isNotEmpty() }?.joinToString("::")
    }

    /** Parses `Type`, `Type::member`, or `Type::*` into the semantic site identity. */
    private fun parseImplTarget(): String {
        val owner = consume(TokenType.IDENTIFIER, "Expected implementation target").lexeme
        // `impl Type:: { … }` - the `::` opens a type-scoped member block and
        // belongs to the caller, not to a `Type::member` site identity.
        if (check(TokenType.DOUBLE_COLON) && peekNext()?.type == TokenType.L_BRACE) {
            return owner
        }
        if (!match(TokenType.DOUBLE_COLON)) {
            skipGenericTypeArgs()
            return owner
        }
        val member = when {
            match(TokenType.STAR) -> "*"
            else -> consume(TokenType.IDENTIFIER, "Expected member name or '*' after '::'").lexeme
        }
        // A realm-qualified target carries its parameters like any other:
        // `impl PrettyPrint for std::Tuple<...T>`.
        skipGenericTypeArgs()
        return "$owner.$member"
    }

    /** Parses one implementation target or `[Target, Other::member]`. */
    /** Expands any compile-time type-list variable target into its member types. */
    private fun expandTypeListTargets(targets: List<String>): List<String> =
        targets.flatMap { comptimeList(it) ?: listOf(it) }

    private fun parseImplTargets(): List<String> {
        if (!match(TokenType.L_BRACKET)) return listOf(parseImplTarget())
        if (check(TokenType.R_BRACKET)) error("Expected at least one implementation target at line ${peek().line}")
        val targets = mutableListOf<String>()
        do { targets.add(parseImplTarget()) } while (match(TokenType.COMMA))
        consume(TokenType.R_BRACKET, "Expected ']' after implementation targets")
        return targets
    }

    private fun queueExpandedImpls(
        traits: List<ContractHead>,
        targets: List<String>,
        start: Token,
        args: List<Expr>,
        namedArgs: List<Pair<String, Expr>>,
        isBridge: Boolean = false,
        annotations: List<Annotation> = emptyList(),
        typeParams: List<String> = emptyList(),
        variadicParam: String? = null,
        hasBody: Boolean,
    ): TopLevel.Impl {
        val implementations = targets.flatMap { target ->
            traits.map { head ->
                TopLevel.Impl(
                    typeName = target,
                    methods = emptyList(),
                    traitName = head.name,
                    line = start.line,
                    column = start.column,
                    traitArgs = head.args,
                    traitQualifier = head.qualifier,
                    decoratorArgs = args,
                    decoratorNamedArgs = namedArgs,
                    annotations = annotations,
                    isBridge = isBridge,
                    typeParams = typeParams,
                    variadicParam = variadicParam,
                    hasBody = hasBody,
                )
            }
        }
        pendingTopLevels.addAll(implementations.drop(1))
        return implementations.first()
    }

    /** `impl Type { methods }` or `impl Trait for Type { methods }`. */
    private fun parseImpl(isBridge: Boolean = false, annotations: List<Annotation> = emptyList()): TopLevel.Impl {
        val start = peek()
        consume(TokenType.IMPL, "Expected 'impl'")
        // Type parameters belong after the name they parametrize, here as
        // everywhere else: `impl Reflected<T>`, `impl Iterator for QueryOf<...T>`.
        // The prefix form said nothing about *where* the parameter went, so it
        // could be written on an impl whose target never took one.
        if (check(TokenType.LESS)) {
            error(
                "Type parameters come after the type they belong to at line ${start.line}: " +
                    "write 'impl Type<T>' or 'impl Spec for Type<T>', not 'impl<T> …'",
            )
        }
        var implTypeParams = TypeParams(emptyList(), null)
        // `impl Spec for Type:: { members }` - type-scoped static members attributed
        // to a spec, which also records Type's conformance to it. Same desugaring as
        // `impl Type:: { … }`, with the spec naming what the members are *for*; the
        // `::` stays on the type because that is where the members are reached.
        if (isSpecStaticImplAhead()) {
            val specName = advance().lexeme
            // `impl From<String> for Username::` - the spec's type arguments say
            // *what* is being converted from, so they are read here rather than
            // being an error one token later.
            val specArgs = parseGenericTypeArgsIfPresent()
            consume(TokenType.FOR, "Expected 'for' after 'impl <spec>'")
            val targets = expandTypeListTargets(parseImplTargets())
            consume(TokenType.DOUBLE_COLON, "Expected '::' after 'impl <spec> for <type>'")
            consume(TokenType.L_BRACE, "Expected '{' after 'impl <spec> for <type>::'")
            skipNewlines()
            val bodyTokens = captureBraceBody()
            consumeNewline()
            for (target in targets) {
                // `Self` in a static impl is the type being implemented for, and
                // the body is re-parsed rather than resolved in place - so the
                // substitution happens on the tokens. Without it
                // `func from(value: String): Self` returns a type called
                // literally `Self`, which matches nothing.
                val targetBody = bodyTokens.map { token ->
                    if (token.type == TokenType.IDENTIFIER && token.lexeme == "Self") {
                        token.copy(lexeme = target)
                    } else {
                        token
                    }
                }
                val members = Parser(
                    targetBody + Token(TokenType.EOF, "", start.line, start.column),
                    typeListEnv,
                ).parse().items
                members.forEach { pendingTopLevels.add(mangleTopLevel(it, target)) }
                // The conformance itself, so `T is Spec` holds nominally. It names the
                // members the block supplies - they live as mangled statics, but the
                // conformance check asks what this impl provides, not where it put it.
                val supplied = members.mapNotNull { member ->
                    declaredMemberName(member)?.let { memberName ->
                        FuncDecl(
                            memberName, emptyList(), TypeAnnotation.Inferred, emptyList(),
                            false, emptyList(), start.line, start.column,
                            memberCallStyle = MemberCallStyle.STATIC_PROPERTY,
                        )
                    }
                }
                pendingTopLevels.add(
                    TopLevel.Impl(target, supplied, specName, start.line, start.column, isBridge = true),
                )
            }
            // The conformances were queued per target above; this return value is only
            // the statement's placeholder, so it carries no trait of its own - leaving
            // one here would ask the checker to verify an impl with no members.
            return TopLevel.Impl(targets.first(), emptyList(), null, start.line, start.column)
        }
        // `impl Type:: { members }` (or `impl [A, B]:: { … }`) - type-scoped static
        // members, reached as `Type::member`. The trailing `::` is the same one the
        // use site writes, so the declaration looks like what it produces. Members
        // desugar to mangled top-level items (`Type__member`) per target type.
        if (isStaticImplAhead()) {
            val targets = expandTypeListTargets(parseImplTargets())
            consume(TokenType.DOUBLE_COLON, "Expected '::' after 'impl <type>'")
            // `impl Vec<T, N>:: where T is SignedNumber { … }` - a static block may
            // narrow the type's own constraints, so its members exist only for the
            // specializations that satisfy it.
            parseWhereClause()
            skipNewlines()
            consume(TokenType.L_BRACE, "Expected '{' after 'impl <type>::'")
            skipNewlines()
            val bodyTokens = captureBraceBody()
            consumeNewline()
            val implTypeParams = lastImplTypeParams
            for (target in targets) {
                val members = Parser(bodyTokens + Token(TokenType.EOF, "", start.line, start.column), typeListEnv, declaredEnums, typeListRealm = typeListRealm).parse().items
                members.forEach {
                    pendingTopLevels.add(mangleTopLevel(withImplTypeParams(it, implTypeParams), target))
                }
            }
            return TopLevel.Impl(targets.first(), emptyList(), null, start.line, start.column)
        }
        // `impl oper<OP> for Type(…)` was removed; see the note on the bracketed
        // form below. Both are caught there, so nothing is needed here.
        if (check(TokenType.CTOR)) {
            val ctorStart = advance()
            val ctorParams = if (match(TokenType.L_PAREN)) {
                val parsed = parseParams()
                consume(TokenType.R_PAREN, "Expected ')' after ctor params")
                parsed
            } else {
                emptyList()
            }
            consume(TokenType.FOR, "Expected 'for' after 'impl ctor'")
            val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'for'").lexeme
            skipGenericTypeArgs() // `impl ctor(...) for Type<T>` - discard erased type args
            val contracts = parseContractClauses()
            run {
                val i = nextMeaningfulIndex()
                val tok = tokens.getOrNull(i)
                if (tok?.type == TokenType.SCOPE &&
                    tokens.getOrNull(i + 1)?.type == TokenType.L_BRACE
                ) {
                    while (current < i) advance() // skip newlines
                    advance() // 'scope' - the '{' that follows is the ctor body
                }
            }
            if (!check(TokenType.L_BRACE)) {
                consumeNewline()
                return TopLevel.Impl(typeName, emptyList(), null, ctorStart.line, ctorStart.column)
            }
            consume(TokenType.L_BRACE, "Expected '{' before ctor impl body")
            skipNewlines()
            val receiverModifier = parseReceiverBinding("ctor receiver").modifier
            consume(TokenType.ARROW, "Expected '->' after ctor receiver")
            skipNewlines()
            val body = mutableListOf<Stmt>()
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                body.add(parseStmt())
                skipNewlines()
            }
            consume(TokenType.R_BRACE, "Expected '}' after ctor impl body")
            consumeNewline()
            val method = FuncDecl(
                name = "ctor",
                params = ctorParams,
                returnType = TypeAnnotation.Inferred,
                body = applyContracts(body, contracts),
                line = ctorStart.line,
                column = ctorStart.column,
                receiverModifier = receiverModifier,
            )
            return TopLevel.Impl(typeName, listOf(method), null, ctorStart.line, ctorStart.column)
        }
        if (check(TokenType.DTOR)) {
            val dtorStart = advance()
            if (match(TokenType.L_PAREN)) {
                consume(TokenType.R_PAREN, "Expected ')' after dtor params")
            }
            consume(TokenType.FOR, "Expected 'for' after 'impl dtor'")
            val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'for'").lexeme
            skipGenericTypeArgs()
            if (!check(TokenType.L_BRACE)) {
                consumeNewline()
                return TopLevel.Impl(typeName, emptyList(), null, dtorStart.line, dtorStart.column)
            }
            consume(TokenType.L_BRACE, "Expected '{' before dtor impl body")
            skipNewlines()
            val receiverModifier = parseReceiverBinding("dtor receiver").modifier
            consume(TokenType.ARROW, "Expected '->' after dtor receiver")
            skipNewlines()
            val body = mutableListOf<Stmt>()
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                body.add(parseStmt())
                skipNewlines()
            }
            consume(TokenType.R_BRACE, "Expected '}' after dtor impl body")
            consumeNewline()
            val method = FuncDecl(
                name = "dtor",
                params = emptyList(),
                returnType = TypeAnnotation.Inferred,
                body = body,
                line = dtorStart.line,
                column = dtorStart.column,
                receiverModifier = receiverModifier,
            )
            return TopLevel.Impl(typeName, listOf(method), null, dtorStart.line, dtorStart.column)
        }
        // `impl as T for Type { self& -> … }` - a bespoke declaration with a
        // bespoke receiver, and the last surviving use of the in-brace receiver
        // the bracket redesign replaced everywhere else. `Cast<To>` says the
        // same thing as an ordinary spec impl, and unlike this form it can be a
        // bound (`where T: Cast<String>`).
        if (check(TokenType.AS)) {
            val asStart = peek()
            error(
                "'impl as <Type> for <Type>' was removed at line ${asStart.line}; " +
                    "write 'impl Cast<Type> for <Type> { prop castValue[self: Self&]: Type { … } }' " +
                    "(or CheckedCast for 'as?', BitCast for 'as*')",
            )
        }
        // `impl oper… for Type { … }` was removed: an operator is declared beside
        // its type, as `oper[] [self: Type&](index: Int): T { … }` (see parseFreeOperator).
        if (check(TokenType.OPER)) {
            error(
                "'impl oper… for Type' was removed at line ${peek().line}; declare the operator " +
                    "beside its type, as 'oper[] [self: Type&](index: Int): T { … }'",
            )
        }
        val isPackImpl = match(TokenType.PACK)
        val traitHeads = if (match(TokenType.L_BRACKET)) {
            if (check(TokenType.R_BRACKET)) error("Expected at least one decorator after 'impl [' at line ${peek().line}")
            val names = mutableListOf<ContractHead>()
            do {
                val (name, qualifier) = parseContractName("Expected decorator name in implementation list")
                if (check(TokenType.LESS)) {
                    error("Generic arguments are not supported inside decorator implementation lists at line ${peek().line}")
                }
                names.add(ContractHead(name, qualifier = qualifier))
            } while (match(TokenType.COMMA))
            consume(TokenType.R_BRACKET, "Expected ']' after decorator implementation list")
            names
        } else {
            val (name, qualifier) = parseContractName("Expected type or trait name after 'impl'")
            listOf(ContractHead(name, parseGenericTypeArgsIfPresent(), qualifier))
        }
        val firstHead = traitHeads.first()
        val first = firstHead.name
        val firstArgs = firstHead.args
        val (decoratorArgs, decoratorNamedArgs) = if (check(TokenType.L_PAREN)) parseDecoratorArguments()
        else emptyList<Expr>() to emptyList<Pair<String, Expr>>()
        if (traitHeads.size > 1 && (decoratorArgs.isNotEmpty() || decoratorNamedArgs.isNotEmpty())) {
            error("Decorator implementation values require a single decorator at line ${start.line}")
        }
        var typeName = first
        var traitName: String? = null
        var traitArgs = emptyList<TypeRef>()
        var implementationTargets = listOf(first)
        if (match(TokenType.FOR)) {
            if (isPackImpl) error("'impl pack' cannot be used for prot implementations at line ${peek().line}")
            traitName = first
            traitArgs = firstArgs
            implementationTargets = expandTypeListTargets(parseImplTargets())
            typeName = implementationTargets.first()
        } else if (traitHeads.size > 1) {
            error("Decorator implementation lists require 'for Target' at line ${start.line}")
        } else if (decoratorArgs.isNotEmpty() || decoratorNamedArgs.isNotEmpty()) {
            error("Decorator implementation arguments require 'for Type' at line ${start.line}")
        }
        // The target's own arguments are its parameters. With a `for` the target
        // was read by `parseImplTarget`; without one it is the head itself, whose
        // arguments were parsed above. Snapshotted here because parsing the body
        // below reuses the same scratch fields.
        implTypeParams = if (traitName != null) {
            TypeParams(lastImplTypeParams, lastImplVariadicParam)
        } else {
            TypeParams(
                firstArgs.mapNotNull { (it as? TypeRef.Named)?.takeIf { a -> a.args.isEmpty() && a.qualifier == null }?.name },
                firstArgs.filterIsInstance<TypeRef.Named>().firstOrNull { it.variadic }?.name,
            )
        }
        // `impl Iterator for Rows assoc Item = Entity { … }` - what this
        // implementation's associated types are.
        val assocBindings = parseAssocBindings()
        if (!check(TokenType.L_BRACE)) {
            if (traitName != null) {
                error(
                    "manual implementation 'impl $traitName for $typeName' requires a body; " +
                        "add '{ ... }' or request compiler generation with " +
                        "'derive $traitName for $typeName' at line ${start.line}",
                )
            }
            consumeNewline()
            return TopLevel.Impl(
                typeName, emptyList(), traitName, start.line, start.column,
                isPackImpl = isPackImpl,
                traitArgs = traitArgs,
                traitQualifier = firstHead.qualifier,
                decoratorArgs = decoratorArgs,
                decoratorNamedArgs = decoratorNamedArgs,
                isBridge = isBridge,
                typeParams = implTypeParams.names,
                variadicParam = implTypeParams.variadic,
                hasBody = false,
                assocBindings = assocBindings,
            )
        }
        consume(TokenType.L_BRACE, "Expected '{' after impl type")
        skipNewlines()
        // Decorator applications are manual implementations too, so they carry an
        // explicit body. A marker's body is empty; grouped decorators and grouped
        // targets expand to the cross-product while preserving that source body.
        if (traitName != null && check(TokenType.R_BRACE) &&
            (traitHeads.size > 1 || implementationTargets.size > 1 || typeName.contains('.'))
        ) {
            consume(TokenType.R_BRACE, "Expected '}' after implementation body")
            consumeNewline()
            return queueExpandedImpls(
                traitHeads,
                implementationTargets,
                start,
                decoratorArgs,
                decoratorNamedArgs,
                isBridge,
                annotations,
                implTypeParams.names,
                implTypeParams.variadic,
                hasBody = true,
            )
        }
        // `impl Spec for realm::Type { members }` names a realm-qualified type.
        // An explicitly empty body remains ambiguous until declarations are known,
        // and RealmQualifiedImplTargets resolves that case after parsing.
        if (traitName != null &&
            implementationTargets.size == 1 &&
            typeName.count { it == '.' } == 1 &&
            !typeName.endsWith(".*")
        ) {
            typeName = typeName.substringAfter('.')
            implementationTargets = listOf(typeName)
        }
        if (traitHeads.size > 1 || implementationTargets.size > 1 || typeName.contains('.')) {
            error("Grouped and member-target implementations must have an empty body at line ${start.line}")
        }
        // `impl Into<String> for ArrayList<T> { self& -> … }` - the in-brace
        // receiver the bracket redesign replaced. A receiver is declared where
        // every other member declares one, so this form is now an error naming
        // its replacement rather than a second spelling that still works.
        if (traitName != null && isSelfReceiverHeaderAhead()) {
            error(
                "an in-brace receiver is not a declaration at line ${start.line}; " +
                    "declare the member with its receiver in brackets, as " +
                    "'func into[self: Self&](): T { … }'",
            )
        }
        val methods = mutableListOf<FuncDecl>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            val memberAnnotations = parseAnnotations()
            val methodStart = peek()
            // `inline for op in @std::arr["+", "-"] { … }` inside an impl generates MEMBERS,
            // one copy of the body per value, exactly as the top-level form generates
            // declarations. Checked before `inline` is taken as a member modifier.
            if (check(TokenType.INLINE) && peekNext()?.type == TokenType.FOR) {
                methods.addAll(parseInlineMemberFor(typeName))
                continue
            }
            val isInline = match(TokenType.INLINE)
            val isVirt = false
            val visibility = parseVisibility()
            // `react prop` / `react func` / `react async func` - a reactive
            // member. `react` qualifies the declaration the way `async` does,
            // so it precedes the member keyword rather than replacing it, and
            // the two may be written together in that order.
            val isReactiveMember = match(TokenType.REACT)
            // `async prop` - a property whose body suspends. `async` is
            // contextual, so consuming it here leaves `prop` current and the
            // property parses by its ordinary rules.
            val isAsyncProp = isAsyncPropAt(current)
            if (isAsyncProp) advance()
            if (isReactiveMember && !check(TokenType.PROP) && !check(TokenType.FUNC) && !isAsyncFuncAt(current)) {
                error(
                    "Expected 'prop', 'func', 'async prop', or 'async func' after 'react' " +
                        "at line ${peek().line}",
                )
            }
            when {
                check(TokenType.PROP) -> {
                    advance()
                    val propName = consume(TokenType.IDENTIFIER, "Expected property name").lexeme
                    val propReceiver = parsePropReceiver()
                    val propTypeDeclared = check(TokenType.COLON)
                    val propType: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
                    // A property may carry its own constraint, as an operator or a
                    // function does - `prop normalized[…]: Self ?! E where T is …`.
                    val propWhere = parseWhereClause()
                    // A `?!` in the property's type binds the error set for
                    // `return .Variant(payload)` in its body.
                    val savedPropFailSets = currentFailSets
                    currentFailSets =
                        ((propType as? TypeAnnotation.Explicit)?.ref as? TypeRef.Failable)?.errSets.orEmpty()
                    if (match(TokenType.EQUAL)) {
                        // `prop name: T = expr` - expression-body property (returns the expression).
                        val expr = parseExpr()
                        consumeNewline()
                        methods.add(FuncDecl(propName, emptyList(), propType, listOf(Stmt.Return(expr, expr.line, expr.column)), false, emptyList(), methodStart.line, methodStart.column, annotations = memberAnnotations, visibility = visibility, receiverModifier = propReceiver.modifier, receiverName = propReceiver.name, memberCallStyle = MemberCallStyle.PROPERTY, returnTypeDeclared = propTypeDeclared, isReactive = isReactiveMember, isTask = isAsyncProp))
                    } else {
                        val contracts = parseContractClauses()
                        run {
                            val i = nextMeaningfulIndex()
                            if (
                                tokens.getOrNull(i)?.type == TokenType.SCOPE &&
                                tokens.getOrNull(i + 1)?.type == TokenType.L_BRACE
                            ) {
                                while (current < i) advance()
                                advance()
                            }
                        }
                        consume(TokenType.L_BRACE, "Expected '{' after prop type")
                        skipNewlines()
                        val propBody = parseBlock()
                        consume(TokenType.R_BRACE, "Expected '}' after prop body")
                        consumeNewline()
                        methods.add(FuncDecl(propName, emptyList(), propType, applyContracts(propBody, contracts), false, emptyList(), methodStart.line, methodStart.column, annotations = memberAnnotations, visibility = visibility, receiverModifier = propReceiver.modifier, receiverName = propReceiver.name, memberCallStyle = MemberCallStyle.PROPERTY, returnTypeDeclared = propTypeDeclared, isReactive = isReactiveMember, isTask = isAsyncProp))
                    }
                    currentFailSets = savedPropFailSets
                }
                // `bridge prop<D> name: T` / `bridge func<D> name(params): T` - a
                // bodyless, compiler-provided member (used by reflection handles).
                check(TokenType.BRIDGE) -> {
                    advance()
                    // `bridge inline prop type: Type` - a member that hands back a
                    // compile-time value has to be folded during expansion, which
                    // is what the marker promises.
                    val bridgeDeep = check(TokenType.DEEPINLINE)
                    val bridgeInline = match(TokenType.INLINE) || match(TokenType.DEEPINLINE)
                    when {
                        // `bridge fin size: Int` - a compiler-provided constant on the
                        // type. It takes no receiver, because a constant of the type
                        // does not vary per value.
                        check(TokenType.FIN) -> {
                            advance()
                            val finName = consumeIdentifierLike("Expected name after 'bridge fin'")
                            consume(TokenType.COLON, "A 'bridge fin' must declare its type: write 'bridge fin $finName: Type'")
                            val finType = TypeAnnotation.Explicit(parseTypeName())
                            consumeNewline()
                            methods.add(
                                FuncDecl(
                                    finName, emptyList(), finType, emptyList(), false, emptyList(),
                                    methodStart.line, methodStart.column,
                                    annotations = memberAnnotations, visibility = visibility,
                                    memberCallStyle = MemberCallStyle.PROPERTY,
                                ),
                            )
                        }
                        check(TokenType.PROP) -> {
                            advance()
                            if (check(TokenType.LESS)) {
                                error("Type parameters come after the name at line ${peek().line}: write 'bridge prop name<D>', not 'bridge prop<D> name'")
                            }
                            val propName = consumeIdentifierLike("Expected property name after 'bridge prop'")
                            val tp = parseTypeParams()
                            val bridgeReceiver = parsePropReceiver()
                            // A computed type can be long enough to want its own
                            // line; a `:` is never the end of a declaration, so
                            // what follows the break can only be the type.
                            val propType: TypeAnnotation = if (match(TokenType.COLON)) {
                                skipNewlines()
                                TypeAnnotation.Explicit(parseTypeName())
                            } else {
                                TypeAnnotation.Inferred
                            }
                            consumeNewline()
                            methods.add(FuncDecl(propName, emptyList(), propType, emptyList(), bridgeInline, tp.names, methodStart.line, methodStart.column, annotations = memberAnnotations, visibility = visibility, receiverModifier = bridgeReceiver.modifier, receiverName = bridgeReceiver.name, memberCallStyle = MemberCallStyle.PROPERTY, isDeepInline = bridgeDeep))
                        }
                        check(TokenType.FUNC) -> {
                            advance()
                            if (check(TokenType.LESS)) {
                                error("Type parameters come after the name at line ${peek().line}: write 'bridge func name<T>(…)', not 'bridge func<T> name(…)'")
                            }
                            val fnName = consumeIdentifierLike("Expected function name after 'bridge func'")
                            val tp = parseTypeParams()
                            consume(TokenType.L_PAREN, "Expected '(' after bridge function name")
                            val params = parseParams()
                            consume(TokenType.R_PAREN, "Expected ')' after bridge parameters")
                            val ret: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Explicit(TypeRef.Named("Unit", synthesized = true))
                            consumeNewline()
                            methods.add(FuncDecl(fnName, params, ret, emptyList(), bridgeInline, tp.names, methodStart.line, methodStart.column, annotations = memberAnnotations, visibility = visibility, receiverModifier = ParamModifier.SHARED, isDeepInline = bridgeDeep))
                        }
                        else -> error("Expected 'fin', 'prop' or 'func' after 'bridge' in impl block at line ${peek().line}")
                    }
                }
                // `ctor [self: Self!](…) { … }` - a constructor is a member of the
                // type it builds, so it lives in the impl beside everything else.
                check(TokenType.CTOR) -> {
                    val ctorStart = advance()
                    val recv = parsePropReceiver()
                    val ctorParams = if (match(TokenType.L_PAREN)) {
                        val parsed = if (check(TokenType.R_PAREN)) emptyList() else parseParams()
                        consume(TokenType.R_PAREN, "Expected ')' after ctor parameters")
                        parsed
                    } else emptyList()
                    val contracts = parseContractClauses()
                    consume(TokenType.L_BRACE, "Expected '{' after ctor")
                    skipNewlines()
                    val ctorBody = parseBlock()
                    consume(TokenType.R_BRACE, "Expected '}' after ctor body")
                    consumeNewline()
                    methods.add(FuncDecl(
                        "ctor", ctorParams, TypeAnnotation.Inferred, applyContracts(ctorBody, contracts),
                        false, emptyList(), ctorStart.line, ctorStart.column,
                        annotations = memberAnnotations, visibility = visibility,
                        receiverModifier = recv.modifier, receiverName = recv.name,
                    ))
                }
                // `dtor [self: Self&] { … }` - teardown, with no parameters to take.
                check(TokenType.DTOR) -> {
                    val dtorStart = advance()
                    val recv = parsePropReceiver()
                    consume(TokenType.L_BRACE, "Expected '{' after dtor")
                    skipNewlines()
                    val dtorBody = parseBlock()
                    consume(TokenType.R_BRACE, "Expected '}' after dtor body")
                    consumeNewline()
                    methods.add(FuncDecl(
                        "dtor", emptyList(), TypeAnnotation.Inferred, dtorBody,
                        false, emptyList(), dtorStart.line, dtorStart.column,
                        annotations = memberAnnotations, visibility = visibility,
                        receiverModifier = recv.modifier, receiverName = recv.name,
                    ))
                }
                check(TokenType.OPER) -> {
                    val operStart = advance()
                    // `oper[] [self: Self&](i: Int): T` / `oper[]=` - the index
                    // operators, written in an impl like any other member.
                    val opName = if (check(TokenType.L_BRACKET) && peekNext()?.type == TokenType.R_BRACKET) {
                        advance(); advance()
                        if (match(TokenType.EQUAL)) "indexSet" else "index"
                    } else {
                        parseOperatorName()
                    }
                    // `oper as<U> [self: …]` - an operator may take type parameters.
                    val operTypeParams = parseTypeParams()
                    var operSuffix = ""
                    if (!check(TokenType.L_BRACKET)) {
                        error("an operator declares its receiver, as in 'oper$opName [self: ${typeName}&]', at line ${peek().line}")
                    }
                    val recv = parsePropReceiver()
                    val operands = if (match(TokenType.L_PAREN)) {
                        val parsed = if (check(TokenType.R_PAREN)) emptyList() else parseParams()
                        consume(TokenType.R_PAREN, "Expected ')' after operator operands")
                        parsed
                    } else emptyList()
                    // One operator may be overloaded on its operand - `v + v` and
                    // `v + scalar` are different declarations - so the operand type
                    // joins the member name. Lookup falls back to the bare name, which
                    // keeps a single unambiguous overload spelled as it always was.
                    // `Self` in an operand is the implementing type, and the
                    // overload key has to say so: a member registered as
                    // `oper-@Self` is invisible to a lookup for `oper-@Vec2`,
                    // which then falls back to the bare name - the unary
                    // operator - and answers subtraction with negation.
                    operSuffix = operatorOverloadSuffix(opName, recv.modifier, operands)
                        .replace("@Self", "@$typeName")
                    var ret: TypeAnnotation = if (match(TokenType.COLON)) {
                        skipNewlines()
                        TypeAnnotation.Explicit(parseTypeName())
                    } else TypeAnnotation.Inferred
                    // `inline if <cond> { inline "?! MathError" }` - a fragment spliced
                    // into the signature. Applied by re-reading the return type with
                    // the fragment appended, so `?!` means exactly what it always does.
                    parseSignatureFragment()?.let { fragment ->
                        ret = applySignatureFragment(ret, fragment, operStart)
                    }
                    // An operator may carry its own constraint, like any member.
                    val operWhere = parseWhereClause()
                    // `return .Variant(payload)` in the body needs the error set the
                    // signature declares, exactly as it does for a `func`.
                    val savedOperFailSets = currentFailSets
                    currentFailSets =
                        ((ret as? TypeAnnotation.Explicit)?.ref as? TypeRef.Failable)?.errSets.orEmpty()
                    skipNewlines()
                    // `oper+ [self: Self&](rhs: Self&): Self = <expr>` - an expression
                    // body, the same shorthand a `func` has.
                    // A range operator's default step trails the declaration, as it
                    // does at top level; it is declarative metadata.
                    if (match(TokenType.BY)) parseExpr()
                    skipNewlines()
                    val operBody: List<Stmt>
                    if (!check(TokenType.L_BRACE) && !check(TokenType.EQUAL)) {
                        // Bodyless: the backend supplies it, like `bridge func`.
                        consumeNewline()
                        currentFailSets = savedOperFailSets
                        methods.add(
                            FuncDecl(
                                "oper$opName$operSuffix", operands, ret, emptyList(), false, operTypeParams.names,
                                operStart.line, operStart.column,
                                annotations = memberAnnotations, visibility = visibility,
                                receiverModifier = recv.modifier, receiverName = recv.name,
                                constParams = operTypeParams.constParams,
                            ),
                        )
                        continue
                    }
                    if (match(TokenType.EQUAL)) {
                        skipNewlines()
                        val value = parseExpr()
                        operBody = listOf(Stmt.Return(value, value.line, value.column))
                        consumeNewline()
                    } else {
                        consume(TokenType.L_BRACE, "Expected '{' after operator declaration")
                        skipNewlines()
                        operBody = parseBlock()
                        consume(TokenType.R_BRACE, "Expected '}' after operator body")
                    }
                    currentFailSets = savedOperFailSets
                    consumeNewline()
                    methods.add(FuncDecl(
                        "oper$opName$operSuffix", operands, ret, operBody, false, operTypeParams.names,
                        operStart.line, operStart.column,
                        annotations = memberAnnotations, visibility = visibility,
                        receiverModifier = recv.modifier, receiverName = recv.name,
                        constParams = operTypeParams.constParams,
                    ))
                }
                check(TokenType.FUNC) -> methods.add(parseFuncDecl(isInline, annotations = memberAnnotations, isVirtual = isVirt, visibility = visibility, inImplBlock = true, isReactive = isReactiveMember))
                isAsyncFuncAt(current) -> methods.add(parseFuncDecl(isInline, annotations = memberAnnotations, isVirtual = isVirt, isTask = true, visibility = visibility, inImplBlock = true, isReactive = isReactiveMember))
                else -> error(
                    "Expected 'prop', 'func', 'async prop', 'async func', 'oper', 'ctor', " +
                        "'dtor', or 'bridge' in impl block at line ${peek().line}",
                )
            }
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after impl methods")
        consumeNewline()
        return TopLevel.Impl(
            typeName, bindSelf(methods, typeName), traitName, start.line, start.column,
            isPackImpl = isPackImpl,
            traitArgs = traitArgs,
            traitQualifier = firstHead.qualifier,
            decoratorArgs = decoratorArgs,
            decoratorNamedArgs = decoratorNamedArgs,
            annotations = annotations,
            isBridge = isBridge,
            typeParams = implTypeParams.names,
            variadicParam = implTypeParams.variadic,
            assocBindings = assocBindings,
        )
    }
    /**
     * `oper[] (i: T): R { … }` or `oper[]= (i: T, v: R) { … }` - index-operator
     * overloads inside an impl. Registered as the methods `index` / `indexSet`, so
     * `target[i]` / `target[i] = v` resolve to `Type_index(self, i)` / `Type_indexSet(self, i, v)`.
     */
    private fun parseOperMethod(start: Token, visibility: Visibility = Visibility.PUBLIC): FuncDecl {
        consume(TokenType.OPER, "Expected 'oper'")
        consume(TokenType.L_BRACKET, "Expected '[' after 'oper'")
        consume(TokenType.R_BRACKET, "Expected ']' after 'oper['")
        val name = if (match(TokenType.EQUAL)) "indexSet" else "index"
        consume(TokenType.L_PAREN, "Expected '(' after oper signature")
        val params = parseParams()
        consume(TokenType.R_PAREN, "Expected ')' after oper parameters")
        val returnType: TypeAnnotation = if (match(TokenType.COLON)) {
            TypeAnnotation.Explicit(parseTypeName())
        } else {
            TypeAnnotation.Inferred
        }
        consume(TokenType.L_BRACE, "Expected '{' before oper body")
        skipNewlines()
        val body = mutableListOf<Stmt>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            body.add(parseStmt())
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after oper body")
        consumeNewline()
        return FuncDecl(name, params, returnType, body, false, emptyList(), start.line, start.column, visibility = visibility)
    }

    /**
     * Wraps a parsed function as either a plain `TopLevel.Func` or, when it declares
     * a bracketed receiver (`func m()[self: Type&]: R`), an extension `TopLevel.Impl`
     * on the receiver's type - the desugaring the old `func Type.m()` form used.
     */
    private fun funcOrExtension(decl: FuncDecl): TopLevel {
        val recv = decl.extensionReceiver ?: return TopLevel.Func(decl)
        // A bracketed receiver already carries its borrow (`[self: T!]` parsed the
        // sigil off the type), so trust what the declaration says rather than
        // re-deriving it and silently downgrading `!` to a shared borrow.
        val (typeName, modifier) = when (val t = recv.type) {
            is TypeRef.Reference -> namedTypeName(t.inner) to t.kind.paramModifier
            else -> namedTypeName(t) to decl.receiverModifier
        }
        val method = decl.copy(
            receiverModifier = modifier,
            receiverName = recv.name,
            extensionReceiver = null,
        )
        return TopLevel.Impl(typeName, listOf(method), null, decl.line, decl.column, isExtension = true)
    }

    /** The bare type name of a (possibly generic) named type ref, for extension targets. */
    private fun namedTypeName(t: TypeRef): String = when (t) {
        is TypeRef.Named -> t.name
        is TypeRef.Reference -> namedTypeName(t.inner)
        else -> error("Extension receiver must be a named type at line ${peek().line}")
    }

    /** An impl receiver is an exclusive borrow unless a postfix sigil says otherwise. */
    private fun parseImplReceiverModifier(): ParamModifier = ParamModifier.EXCLUSIVE

    /** New postfix receiver/operand borrow: `self&`/`x&` → immutable, `self!`/`x!` → mutable. */
    private fun parsePostfixReceiverModifier(): ParamModifier =
        when {
            match(TokenType.AMP) -> ParamModifier.SHARED
            match(TokenType.BANG) -> ParamModifier.EXCLUSIVE
            else -> ParamModifier.EXCLUSIVE
        }

    /** A receiver binding: the receiver's name (always `self`) and its borrow modifier. */
    private data class ReceiverBinding(val name: String, val modifier: ParamModifier)

    /**
     * Parses the `self` receiver binding in either the new postfix form (`self&` /
     * `self!` / `self`) or the old prefix form (`[mut] ref self`), consuming through
     * the receiver name. The receiver is always named `self`.
     */
    private fun parseReceiverBinding(what: String = "receiver"): ReceiverBinding {
        val name = consumeIdentifierLike("Expected $what name")
        if (name != "self") error("Expected $what to be named 'self' at line ${peek().line}")
        return ReceiverBinding("self", parsePostfixReceiverModifier())
    }

    /**
     * Parses the operands that can follow the receiver in a `{ self&, … -> }` block:
     * `, name: Type` (the type may carry a borrow, e.g. `Animal&`) or `, name[&|!]`.
     */
    private fun parseReceiverOperands(params: MutableList<Param>) {
        while (match(TokenType.COMMA)) {
            val name = consumeIdentifierLike("Expected operand name in receiver block")
            if (match(TokenType.COLON)) {
                params.add(Param(name, parseTypeName()))
            } else {
                params.add(Param(name, TypeRef.Named("Any", synthesized = true), modifier = parsePostfixReceiverModifier()))
            }
        }
    }

    /** A spec callback receiver carries its borrow as a postfix sigil on `self`. */
    private fun parseSpecReceiverModifier(): ParamModifier = ParamModifier.EXCLUSIVE

    /**
     * `bridge <target> { func sigs }` - declares extern functions for FFI.
     * Each signature is `func name(params): RetType` (no body).
     */
    /** After `bridge`, whether an optional target (`.C` or `Target.C`) is followed by `func`. */
    private fun isBridgeFuncAhead(): Boolean {
        var i = current + 1
        when {
            tokens.getOrNull(i)?.type == TokenType.DOT -> {
                i++
                if (tokens.getOrNull(i)?.type == TokenType.IDENTIFIER) i++
            }
            tokens.getOrNull(i)?.type == TokenType.IDENTIFIER && tokens.getOrNull(i + 1)?.type == TokenType.DOT -> {
                i += 2
                if (tokens.getOrNull(i)?.type == TokenType.IDENTIFIER) i++
            }
            // A constant bound to a `Target` member is a target too, so
            // `bridge Native func …` is the single-function form as well.
            tokens.getOrNull(i)?.type == TokenType.IDENTIFIER &&
                bridgeTargetConstants.containsKey(tokens.getOrNull(i)!!.lexeme) -> i++
        }
        return tokens.getOrNull(i)?.type == TokenType.FUNC
    }

    /**
     * The target of a `bridge`, which is always a `std::Target` value.
     *
     * Because the type is fixed, the enum is in context, so the target is
     * written as a leading-dot member (`.C`), as a fully qualified one
     * (`Target.C`), or as a compile-time constant already bound to one
     * (`inline fin Native = Target.C` … `bridge Native { … }`).
     *
     * A bare `C` is *not* accepted: an enum member is reached through its enum,
     * and a lone identifier is a variable reference - reading it as a variant
     * name would make `bridge Native` and `bridge C` mean different things for
     * no visible reason.
     */
    private fun parseBridgeTarget(): String {
        val at = peek()
        if (match(TokenType.DOT)) {
            val member = consume(TokenType.IDENTIFIER, "Expected bridge target after '.' (e.g. '.C', '.WebAssembly')").lexeme
            return checkedBridgeTarget(member, at)
        }
        val first = consume(TokenType.IDENTIFIER, "Expected bridge target ('.C', 'Target.C', or a constant bound to one)").lexeme
        // `std::Target.C` - the enum may be reached through its realm, exactly as
        // any other realm-scoped type is. The realm path is consumed and dropped:
        // what a bridge names is the member, and `Target` is the only enum that
        // can appear here whatever realm declares it.
        if (check(TokenType.DOUBLE_COLON)) {
            while (match(TokenType.DOUBLE_COLON)) {
                consume(TokenType.IDENTIFIER, "Expected a name after '::' in a bridge target")
            }
            consume(TokenType.DOT, "Expected '.' after the Target enum in a bridge target")
            val member = consume(TokenType.IDENTIFIER, "Expected bridge target variant after '.'").lexeme
            return checkedBridgeTarget(member, at)
        }
        // `Target.C` - the leading name is the enum qualifier; keep the member.
        if (match(TokenType.DOT)) {
            val member = consume(TokenType.IDENTIFIER, "Expected bridge target variant after '.'").lexeme
            return checkedBridgeTarget(member, at)
        }
        // A lone identifier is only a target if it is a compile-time constant
        // already bound to a `Target` member.
        val bound = bridgeTargetConstants[first]
        if (bound != null) {
            return bound
        }
        error(
            "'$first' is not a bridge target at line ${at.line} - write '.$first' for the " +
                "Target member, 'Target.$first' in full, or bind it first with " +
                "'inline fin $first = Target.<Variant>'"
        )
    }

    /**
     * The members of `std::Target`, which is what a `bridge` target must name.
     *
     * Mirrored here because a bridge is parsed long before the standard library
     * is available to consult; keep it in step with the `Target` enum in
     * `std/core.az`.
     */
    private val bridgeTargetMembers = setOf(
        "Compiler",
        "C",
        "ObjectiveC",
        "WebAssembly",
    )

    /** [member] if it names a `Target`; otherwise a parse error naming the real ones. */
    private fun checkedBridgeTarget(member: String, at: Token): String {
        if (member in bridgeTargetMembers) {
            return member
        }
        error(
            "'$member' is not a member of Target at line ${at.line} - a bridge target is one of " +
                bridgeTargetMembers.joinToString(", ") { ".$it" }
        )
    }

    /**
     * Compile-time constants bound to a `Target` member, for `bridge X { … }`.
     *
     * Filled as top-level declarations are parsed, so a constant has to be
     * declared before the bridge that names it - which is the same order the
     * rest of the file already reads in.
     */
    private val bridgeTargetConstants = mutableMapOf<String, String>()
    private var bridgeNamePlaceholder = 0

    /** A bridge name written directly, as generated syntax, or through a macro. */
    private data class ParsedBridgeName(
        val backendName: String,
        val localName: String? = null,
        val macro: Expr.MetaInvoke? = null,
    )

    /**
     * Parses one bridge declaration name.
     *
     * - `clock` uses the same Azora/backend name.
     * - `"clock_gettime" clockGettime` is the post-expansion generated form.
     * - `@foreignName("clock_gettime")` defers naming to a normal prefix macro.
     */
    /**
     * A type name that may be reached through its realm - `Int`, `std::Int`,
     * `a::b::Thing`.
     *
     * Returns the mangled form the rest of the frontend uses (`std::Int` becomes
     * `std__Int`), so a caller that only needs a name can take one without
     * knowing whether a realm was written.
     */
    private fun parseQualifiedTypeName(message: String): String {
        val name = StringBuilder(consume(TokenType.IDENTIFIER, message).lexeme)
        while (match(TokenType.DOUBLE_COLON)) {
            name.append("__").append(consume(TokenType.IDENTIFIER, message).lexeme)
        }
        return name.toString()
    }

    private fun parseBridgeName(): ParsedBridgeName {
        if (check(TokenType.STRING_LITERAL)) {
            val backend = advance().literal as String
            val local = consume(TokenType.IDENTIFIER, "Expected local Azora name after foreign bridge symbol").lexeme
            return ParsedBridgeName(backend, local)
        }
        if (check(TokenType.AT) && isMacroInvokeAhead()) {
            val at = advance()
            val macroName = parseQualifiedMacroName()
            val args = parseMacroInvokeArgs()
            usedMetaInvoke = true
            return ParsedBridgeName(
                backendName = "__bridgeName${bridgeNamePlaceholder++}",
                macro = Expr.MetaInvoke(macroName, args, at.line, at.column, macroName.length + 1),
            )
        }
        val declared = StringBuilder(consume(TokenType.IDENTIFIER, "Expected bridge declaration name").lexeme)
        while (check(TokenType.DOT) && peekNext()?.type == TokenType.IDENTIFIER) {
            advance()
            declared.append('.').append(advance().lexeme)
        }
        return ParsedBridgeName(declared.toString())
    }

    /** Records `fin`/`inline fin X = Target.C` so `bridge X { … }` can resolve. */
    private fun noteBridgeTargetConstant(name: String, initializer: Expr) {
        val member = initializer as? Expr.Member ?: return
        val qualifier = member.target as? Expr.Identifier ?: return
        // `std::Target.C` reaches here realm-mangled, so the enum is matched by its
        // own name rather than by the path that led to it.
        val enumName = qualifier.name.substringAfterLast("__")
        if (enumName == "Target" && member.name in bridgeTargetMembers) {
            bridgeTargetConstants[name] = member.name
        }
    }

    /**
     * `bridge [.Target] func name(params): Ret` - a single bodyless extern function
     * (the compiler or a backend provides the implementation, keyed by name). It is
     * shorthand for a one-entry `bridge` block; the default target is `Compiler`.
     * Type parameters are accepted but not retained (externs are monomorphic).
     */
    private fun parseBridgeFunc(annotations: List<Annotation> = emptyList()): TopLevel.Bridge {
        val start = consume(TokenType.BRIDGE, "Expected 'bridge'")
        // Bare `bridge func …` defaults to the compiler-provided target.
        val target = if (check(TokenType.FUNC)) "Compiler" else parseBridgeTarget()
        consume(TokenType.FUNC, "Expected 'func' after 'bridge'")
        // Type params may appear before or after the name (`bridge func<T> fill` or
        // `bridge func fill<T>`); capture them so a generic return type like
        // `Array<T>` erases correctly at registration.
        val tpBefore = parseTypeParams()
        val parsedName = parseBridgeName()
        val tpAfter = parseTypeParams()
        consume(TokenType.L_PAREN, "Expected '(' after bridge function name")
        val params = parseParams()
        consume(TokenType.R_PAREN, "Expected ')' after bridge parameters")
        val returnType = if (match(TokenType.COLON)) parseTypeName() else TypeRef.Named("Unit", synthesized = true)
        consumeNewline()
        return TopLevel.Bridge(
            target,
            listOf(TopLevel.BridgeSig(
                parsedName.backendName,
                params,
                returnType,
                start.line,
                start.column,
                tpBefore.names + tpAfter.names,
                localName = parsedName.localName,
                nameMacro = parsedName.macro,
            )),
            start.line, start.column, annotations,
        )
    }

    /**
     * True for `bridge prop name[Receiver&]: T` - a member declared on a type
     * from outside it.
     *
     * Told apart from `bridge prop name[self: Self&]` by what the brackets hold:
     * a receiver *type* rather than a named binding, so there is no `:` inside
     * them.
     */
    private fun isBridgeReceiverPropAhead(): Boolean {
        var i = current + 1
        while (tokens.getOrNull(i)?.type in setOf(TokenType.INLINE, TokenType.DEEPINLINE)) i++
        if (tokens.getOrNull(i)?.type != TokenType.PROP) return false
        i++
        if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return false
        i++
        if (tokens.getOrNull(i)?.type != TokenType.L_BRACKET) return false
        var depth = 0
        while (i < tokens.size) {
            when (tokens[i].type) {
                TokenType.L_BRACKET -> depth++
                TokenType.R_BRACKET -> { depth--; if (depth == 0) return true }
                TokenType.COLON -> if (depth == 1) return false
                TokenType.EOF -> return false
                else -> {}
            }
            i++
        }
        return false
    }

    /**
     * `bridge prop typeName[Type&]: String` - a compiler-provided member of the
     * type named in the brackets.
     *
     * The receiver is written as a type because there is no value to bind: the
     * member belongs to the type itself. It becomes an ordinary member of that
     * type, so everything downstream finds it exactly as it finds any other.
     */
    private fun parseBridgeReceiverProp(annotations: List<Annotation>): TopLevel {
        val start = advance() // 'bridge'
        val isDeep = check(TokenType.DEEPINLINE)
        val isInline = match(TokenType.INLINE) || match(TokenType.DEEPINLINE)
        consume(TokenType.PROP, "Expected 'prop' after 'bridge'")
        val name = consumeIdentifierLike("Expected the property's name")
        consume(TokenType.L_BRACKET, "Expected '[' before the receiver type")
        val receiver = parseTypeName()
        consume(TokenType.R_BRACKET, "Expected ']' after the receiver type")
        val type: TypeAnnotation = if (match(TokenType.COLON)) {
            skipNewlines()
            TypeAnnotation.Explicit(parseTypeName())
        } else {
            TypeAnnotation.Inferred
        }
        consumeNewline()
        return TopLevel.Impl(
            typeName = receiverTypeName(receiver),
            methods = listOf(
                FuncDecl(
                    name, emptyList(), type, emptyList(), isInline, emptyList(),
                    start.line, start.column,
                    annotations = annotations,
                    receiverModifier = receiverModifierOf(receiver),
                    memberCallStyle = MemberCallStyle.PROPERTY,
                    isDeepInline = isDeep,
                ),
            ),
            traitName = null,
            line = start.line,
            column = start.column,
            isBridge = true,
            hasBody = true,
        )
    }

    /** The declared name of a receiver type, past any borrow it was written with. */
    private fun receiverTypeName(ref: TypeRef): String = when (ref) {
        is TypeRef.Named -> ref.name
        is TypeRef.Reference -> receiverTypeName(ref.inner)
        else -> error("A bridge receiver must name a type")
    }

    /** The borrow a receiver type was written with. */
    private fun receiverModifierOf(ref: TypeRef): ParamModifier = when (ref) {
        is TypeRef.Reference -> ref.kind.paramModifier
        else -> ParamModifier.NONE
    }

    private fun parseBridge(annotations: List<Annotation> = emptyList()): TopLevel.Bridge {
        val start = consume(TokenType.BRIDGE, "Expected 'bridge'")
        // Target forms after `bridge`:
        //   `bridge { … }`                       → default `Compiler`
        //   `bridge .C { … }` / `bridge Target.C { … }`
        //   `bridge [.C, .WebAssembly] { … }` / `bridge [Target.C, .WebAssembly] { … }`
        // A bracketed list means the declaration is provided by several backends;
        // multiple targets are stored comma-joined.
        val target = when {
            match(TokenType.L_BRACKET) -> {
                val targets = mutableListOf<String>()
                do { targets.add(parseBridgeTarget()) } while (match(TokenType.COMMA))
                consume(TokenType.R_BRACKET, "Expected ']' after bridge targets")
                targets.joinToString(",")
            }
            check(TokenType.L_BRACE) -> "Compiler" // bare `bridge { … }` → default target
            else -> parseBridgeTarget()
        }
        consume(TokenType.L_BRACE, "Expected '{' after bridge target")
        skipNewlines()
        val funcs = mutableListOf<TopLevel.BridgeSig>()
        val values = mutableListOf<TopLevel.BridgeValue>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            when {
                match(TokenType.FUNC) -> {
                    val name = parseBridgeName()
                    consume(TokenType.L_PAREN, "Expected '('")
                    val params = parseParams()
                    consume(TokenType.R_PAREN, "Expected ')'")
                    val returnType = if (match(TokenType.COLON)) parseTypeName() else TypeRef.Named("Unit", synthesized = true)
                    consumeNewline()
                    funcs.add(TopLevel.BridgeSig(
                        name.backendName,
                        params,
                        returnType,
                        start.line,
                        start.column,
                        localName = name.localName,
                        nameMacro = name.macro,
                    ))
                }
                check(TokenType.FIN) || check(TokenType.LET) || check(TokenType.VAR) -> {
                    val kind = advance()
                    val name = parseBridgeName()
                    consume(TokenType.COLON, "Expected ':' after bridge value name")
                    val type = parseTypeName()
                    consume(TokenType.EQUAL, "Expected '=' and an initializer for bridge value")
                    val initializer = parseExpr()
                    consumeNewline()
                    values.add(TopLevel.BridgeValue(
                        name = name.localName ?: name.backendName,
                        type = type,
                        initializer = initializer,
                        mutable = kind.type == TokenType.VAR,
                        isLet = kind.type == TokenType.LET,
                        line = kind.line,
                        column = kind.column,
                        foreignName = name.backendName.takeIf { name.localName != null },
                        nameMacro = name.macro,
                    ))
                }
                else -> error("Expected 'func', 'fin', 'let', or 'var' in bridge block at line ${peek().line}")
            }
        }
        consume(TokenType.R_BRACE, "Expected '}' after bridge functions")
        consumeNewline()
        return TopLevel.Bridge(target, funcs, start.line, start.column, annotations, values)
    }

    /** `solo pack Name { fields; methods }` - a pack with one shared instance. */
    private fun parseSolo(visibility: Visibility = Visibility.PUBLIC, annotations: List<Annotation> = emptyList()): TopLevel.Solo {
        val start = consume(TokenType.SOLO, "Expected 'solo'")
        // `solo` is a modifier on `pack`, not a declaration of its own: a
        // singleton is a pack there is one of, and saying so keeps one way to
        // declare a type instead of two.
        consume(TokenType.PACK, "Expected 'pack' after 'solo' - a singleton is written 'solo pack Name { … }'")
        val name = consume(TokenType.IDENTIFIER, "Expected the pack's name").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after solo name")
        skipNewlines()
        val fields = mutableListOf<PackField>()
        val methods = mutableListOf<FuncDecl>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            val memberAnnotations = parseAnnotations()
            val memberVisibility = parseVisibility()
            // `solo pack` is a pack: it holds fields, and its members live in an
            // `impl` block like any other type's. Accepting a method here was a
            // leftover from when `solo` was a declaration of its own.
            if (check(TokenType.FUNC)) {
                error(
                    "A 'solo pack' holds fields; put its members in " +
                        "'impl $name { … }' at line ${peek().line}"
                )
            }
            fields.add(parsePackField(memberVisibility, preparsedAnnotations = memberAnnotations))
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after solo body")
        consumeNewline()
        return TopLevel.Solo(name, fields, methods, start.line, start.column, visibility, annotations)
    }

    /**
     * `[leaf] node Name(var|fin param: Type, ...) [: Parent(args)] { methods; fields }`
     * - an inheritable type. Ctor params are fields. `repl func` marks overrides.
     */
    /**
     * `graph Name { Type(args); … }` - a module that wires singletons with their
     * construction arguments. Each `Type(args)` generates a `__singleton_Type`
     * factory that constructs the type with those arguments.
     *
     * The entries carry no `solo`: everything a module registers is a singleton,
     * so the word said nothing the block had not already said.
     */
    private fun parseGraph(): TopLevel.Graph {
        val start = consume(TokenType.GRAPH, "Expected 'graph'")
        val name = consume(TokenType.IDENTIFIER, "Expected graph name").lexeme
        // `graph TestGraph replace AppGraph { … }` - substitution. `replace` is
        // contextual, not reserved: `std::replace` is the string function, and a
        // keyword would take that name from every program.
        val replaces = if (check(TokenType.IDENTIFIER) && peek().lexeme == "replace" &&
            peekNext()?.type == TokenType.IDENTIFIER
        ) {
            advance()
            advance().lexeme
        } else null
        // `graph AppGraph includes [NetworkGraph, DataGraph] { … }` - composition.
        // An included graph's definitions are part of this one. `includes` is
        // contextual for the same reason `replace` is: it is an ordinary word
        // that programs already use as a name, and reserving it would take that
        // name from every one of them.
        val included = mutableListOf<String>()
        if (matchContextual("includes")) {
            if (match(TokenType.L_BRACKET)) {
                do {
                    included.add(consume(TokenType.IDENTIFIER, "Expected a graph name in the includes list").lexeme)
                } while (match(TokenType.COMMA))
                consume(TokenType.R_BRACKET, "Expected ']' after the includes list")
            } else {
                included.add(consume(TokenType.IDENTIFIER, "Expected a graph name after 'includes'").lexeme)
            }
        }
        consume(TokenType.L_BRACE, "Expected '{' after graph name")
        skipNewlines()
        val registrations = mutableListOf<TopLevel.GraphReg>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            // The lifetime is the entry's first word, and it is the whole
            // difference between the forms.
            val entry = peek()
            val lifetime = when {
                match(TokenType.SOLO) -> TopLevel.ProviderLifetime.SOLO
                match(TokenType.FACTORY) -> TopLevel.ProviderLifetime.FACTORY
                match(TokenType.SCOPE) -> TopLevel.ProviderLifetime.SCOPE
                else -> error(
                    "A 'graph' entry starts with its lifetime at line ${entry.line} - " +
                        "'solo' for one per graph, 'factory' for one per resolution, " +
                        "'scope' for one per active scope"
                )
            }
            val typeName = consume(TokenType.IDENTIFIER, "Expected a type name after '${lifetime.spelling}'").lexeme
            val args = if (match(TokenType.L_PAREN)) {
                val a = mutableListOf<Expr>()
                if (!check(TokenType.R_PAREN)) {
                    do { a.add(parseExpr()) } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_PAREN, "Expected ')' after the registration's arguments")
                a
            } else emptyList()
            // `binds Spec` / `binds [A, B]` - the specs this definition also answers.
            val bindSpecs = mutableListOf<String>()
            if (matchContextual("binds")) {
                if (match(TokenType.L_BRACKET)) {
                    do {
                        bindSpecs.add(consume(TokenType.IDENTIFIER, "Expected a spec name in the binds list").lexeme)
                    } while (match(TokenType.COMMA))
                    consume(TokenType.R_BRACKET, "Expected ']' after the binds list")
                } else {
                    bindSpecs.add(consume(TokenType.IDENTIFIER, "Expected a spec name after 'binds'").lexeme)
                }
            }
            registrations.add(
                TopLevel.GraphReg(typeName, args, bindSpecs, lifetime, entry.line, entry.column),
            )
            consumeNewline()
        }
        consume(TokenType.R_BRACE, "Expected '}' after graph body")
        consumeNewline()
        return TopLevel.Graph(name, registrations, start.line, start.column, included, replaces)
    }

    /** `slot Name { Variant(Type); Variant2(Type1, Type2); Variant3 }` - a tagged union. */
    /**
     * `variant enum Name { … }` / `variant error Name { … }` - a tagged union.
     *
     * `variant` is a modifier, not a declaration of its own: it says that the
     * cases of the `enum` (or `error`) it precedes may carry payloads. A plain
     * `enum`/`error` is the payload-free Kotlin-style form, and rejects a
     * payload with a note pointing here.
     *
     * Both spellings produce the same tagged union; the only difference is that
     * an `error` one can be thrown and named in a `?!` set.
     */
    private fun parseSlot(annotations: List<Annotation> = emptyList()): TopLevel.Slot {
        val start = peek()
        consume(TokenType.VARIANT, "Expected 'variant'")
        val kind = when {
            match(TokenType.ENUM) -> "enum"
            match(TokenType.ERROR) -> "error"
            else -> error(
                "'variant' is a modifier: write 'variant enum ${peek().lexeme}' for a tagged union, " +
                    "or 'variant error ${peek().lexeme}' for one that can be thrown, at line ${peek().line}",
            )
        }
        val name = consume(TokenType.IDENTIFIER, "Expected name after 'variant $kind'").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after 'variant $kind $name'")
        skipNewlines()
        val variants = mutableListOf<TopLevel.SlotVariant>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            val vname = consume(TokenType.IDENTIFIER, "Expected case name").lexeme
            val payloadTypes = if (match(TokenType.L_PAREN)) {
                val types = mutableListOf<TypeRef>()
                if (!check(TokenType.R_PAREN)) {
                    do {
                        // `Ok(value: Int)` - an optional field name before the
                        // type is accepted (payloads remain positional).
                        if (check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.COLON) {
                            advance(); advance()
                        }
                        types.add(parseTypeName())
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_PAREN, "Expected ')' after payload types")
                types
            } else emptyList()
            consumeNewline()
            variants.add(TopLevel.SlotVariant(vname, payloadTypes))
        }
        consume(TokenType.R_BRACE, "Expected '}' after the cases of '$name'")
        consumeNewline()
        return TopLevel.Slot(name, variants, start.line, start.column, annotations, isError = kind == "error")
    }

    /**
     * `meta Name { arm; arm; … }` - a pattern-driven macro declaration.
     *
     * Each arm is `delim-pattern delim => template-expr`, where the written
     * delimiter (`()`/`[]`/`{}`) is stored for diagnostics but matching is
     * delimiter-agnostic. The pattern is `Empty` (`[]`/`()`/`{}`) or a spread
     * capture `...$name`. The template is an ordinary expression parsed via
     * [parseExpr] - `$name` is a normal identifier, `...$name` a normal spread.
     */
    companion object {
        /**
         * `macro @name => inline "…"` fragments, by name.
         *
         * Shared across the modules of one compilation: a fragment stands for
         * source text wherever the name appears, so a library that declares one
         * means it for its consumers too. Cleared per compilation so nothing
         * leaks between them.
         */
        private val fragmentMacros = mutableMapOf<String, String>()

        /**
         * The hole a parameterized fragment takes, by macro name.
         *
         * `macro @compose $w => inline "with composeIn($w)"` is a fragment that
         * names what it was handed, so `@compose world` can become
         * `with composeIn(world)` rather than only ever standing for one text.
         */
        private val fragmentMacroParams = mutableMapOf<String, String>()

        /** Drops fragment macros collected by a previous compilation. */
        fun resetFragmentMacros() {
            fragmentMacros.clear()
            fragmentMacroParams.clear()
        }
    }

    /**
     * Replaces `@name` with its fragment's tokens, when one is declared.
     *
     * Done on the token stream rather than as a template rewrite because a
     * fragment need not be an expression: `react [; &]` is a pair of markers a
     * following lambda carries, and there is no node for that on its own.
     */
    private fun spliceFragmentMacro(): Boolean {
        if (!check(TokenType.AT)) return false
        val nameToken = peekNext() ?: return false
        if (nameToken.type != TokenType.IDENTIFIER) return false
        val text = fragmentMacros[nameToken.lexeme] ?: return false
        // A fragment with a hole reads its argument off the token stream:
        // `@compose world { … }` hands `world` to the fragment and leaves the
        // block for whatever the fragment expanded into to take.
        var afterArgument = current + 2
        val body = fragmentMacroParams[nameToken.lexeme]?.let { hole ->
            val argument = fragmentArgumentTokens(afterArgument)
            if (argument.isEmpty()) {
                error("'@${nameToken.lexeme}' takes an argument at line ${nameToken.line}")
            }
            afterArgument += argument.size
            text.replace(hole, argument.joinToString(" ") { it.lexeme })
        } ?: text
        val expanded = Lexer(body).tokenize().filter { it.type != TokenType.EOF && it.type != TokenType.NEWLINE }
        val rest = tokens.drop(afterArgument)
        tokens = tokens.take(current) + expanded + rest
        return true
    }

    /**
     * The tokens of a fragment macro's single argument, starting at [from].
     *
     * Runs to the block or the line end that follows it, so `@compose world {`
     * takes `world` and leaves the `{`. Nesting is tracked, because a `{` inside
     * parentheses belongs to the argument rather than ending it.
     */
    private fun fragmentArgumentTokens(from: Int): List<Token> {
        val argument = mutableListOf<Token>()
        var depth = 0
        var i = from
        while (i < tokens.size) {
            val token = tokens[i]
            when (token.type) {
                TokenType.L_PAREN, TokenType.L_BRACKET -> depth++
                TokenType.R_PAREN, TokenType.R_BRACKET -> depth--
                else -> {}
            }
            if (depth <= 0 && (token.type == TokenType.L_BRACE || token.type == TokenType.NEWLINE || token.type == TokenType.EOF)) {
                break
            }
            argument.add(token)
            i++
        }
        return argument
    }

    private fun parseMeta(): TopLevel.Meta {
        val start = peek()
        consume(TokenType.MACRO, "Expected 'macro'")

        // `macro $a @to $b => TEMPLATE` - an infix macro. The `$a` before the `@`
        // is what distinguishes it from a prefix macro; nothing else has to be
        // written down, so the declaration reads like the call it enables.
        if (check(TokenType.IDENTIFIER) && peek().lexeme.startsWith("$")) {
            val left = advance().lexeme
            consume(TokenType.AT, "Expected '@' before the infix macro's name")
            val op = parseMacroName()
            // `[...$ITEMS]` - the right operand is a list, however long.
            val rightIsList = match(TokenType.L_BRACKET)
            if (rightIsList) consume(TokenType.ELLIPSIS, "A list operand captures the whole list: write '[...\$NAME]'")
            val right = consume(TokenType.IDENTIFIER, "Expected the right hole after an infix macro's name").lexeme
            if (rightIsList) consume(TokenType.R_BRACKET, "Expected ']' after the list operand of '@$op'")
            requireTypeMacroHole(left)
            requireTypeMacroHole(right)
            // With a `=>` it rewrites; without one it only registers the name, so
            // `a @op b` calls the free function `op(a, b)`. The bodyless form is
            // what a plain infix function wants - there is nothing to rewrite to.
            if (match(TokenType.FAT_ARROW)) {
                // Type holes on both sides mean the arm joins two *types*, so
                // its template is one too - `$Q @with $T => QueryWith<$Q, $T>`.
                // Read as an expression it would come apart at the first comma
                // inside the angle brackets.
                if (isTypeHole(left) && isTypeHole(right)) {
                    val template = parseTypeName()
                    pendingTypeMacroRules.add(
                        TypeTypeArm(
                            TypeFormKind.INFIX, listOf(left, right), template,
                            name = op, listTail = rightIsList,
                        ),
                    )
                } else {
                    val template = parseExpr()
                    pendingInfixMacros.add(InfixMacroRule(op, left, right, template))
                }
            }
            consumeNewline()
            // `__infix__op` is what SymbolCollector recognises to wire `op` as a
            // universal infix call.
            return TopLevel.Meta("__infix__$op", emptyList(), start.line, start.column)
        }

        // `macro @name { arms }` - a prefix macro, invoked `@name[…]`.
        consume(TokenType.AT, "Expected '@name' or '\$hole @name \$hole' after 'macro'")
        val name = parseMacroName()
        // `macro @name => inline "source"` - a source fragment, spliced where the
        // name appears. An arm rewrites one *expression* into another; a fragment
        // stands for text that need not be an expression at all - `react [; &]`
        // is two markers a lambda carries, which no template can be.
        // `macro @name $hole => inline "…"` - a fragment that names what it was
        // handed, so one declaration serves every argument: `@compose world`
        // and `@compose other` expand to different text.
        val fragmentHole = if (
            check(TokenType.IDENTIFIER) &&
            peek().lexeme.startsWith("$") &&
            peekNext()?.type == TokenType.FAT_ARROW
        ) {
            advance().lexeme
        } else null
        if (match(TokenType.FAT_ARROW)) {
            if (!match(TokenType.INLINE)) {
                error("A macro without arms expands to a source fragment: write 'macro @$name => inline \"…\"' at line ${start.line}")
            }
            // A fragment naming its hole (`"with composeIn($w)"`) lexes as an
            // interpolated string, but `$w` here is the macro's own hole and
            // not an expression to evaluate - so the *source* is what is kept,
            // and the substitution happens where the fragment is spliced.
            if (!check(TokenType.STRING_LITERAL) && !check(TokenType.INTERPOLATED_STRING)) {
                error("Expected a source fragment after 'inline', got '${peek().lexeme}' at line ${peek().line}")
            }
            val fragment = advance()
            fragmentMacros[name] = if (fragment.type == TokenType.STRING_LITERAL) {
                fragment.literal as? String ?: fragment.lexeme.trim('"')
            } else {
                fragment.lexeme.trim('"')
            }
            fragmentHole?.let { fragmentMacroParams[name] = it }
            consumeNewline()
            return TopLevel.Meta("__fragment__$name", emptyList(), start.line, start.column)
        }
        consume(TokenType.L_BRACE, "Expected '{' after 'macro @$name'")
        skipNewlines()
        val parameter = if (
            check(TokenType.IDENTIFIER) &&
            !peek().lexeme.startsWith("$") &&
            peekNext()?.type == TokenType.ARROW
        ) {
            val value = advance().lexeme
            advance() // '->'
            consumeNewline()
            skipNewlines()
            value
        } else null
        val arms = mutableListOf<MacroArm>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            // A hole's case says what it stands for: `$T` is a type, `$t` a value.
            // An arm whose hole is a type expands in type position, so it is
            // collected as a type rule rather than as an expression arm.
            val typeArm = parseTypeMacroArm(name)
            if (typeArm != null) pendingTypeMacroRules.add(typeArm) else arms.add(parseMacroArm())
        }
        consume(TokenType.R_BRACE, "Expected '}' after macro arms")
        consumeNewline()
        return TopLevel.Meta(name, arms, start.line, start.column, parameter)
    }

    /**
     * A macro's name: an identifier and an optional sigil.
     *
     * `@vec` and `@vec!` are different macros, not one macro with a modifier, so
     * the sigil is part of the name. Only `! ? & * ^` may follow, which keeps a
     * macro name from swallowing an operator that happens to sit next to it.
     */
    private fun parseMacroName(): String {
        // Any word may name a macro, including one that is a keyword elsewhere:
        // `@with`, `@to`, `@in`. The leading `@` has already said this is a name,
        // so nothing is ambiguous.
        var base = if (check(TokenType.IDENTIFIER)) advance().lexeme
            else if (peek().lexeme.isNotEmpty() && peek().lexeme.first().isLetter()) advance().lexeme
            else consumeIdentifierLike("Expected a macro name after '@'")
        val sigil = when {
            match(TokenType.BANG) -> "!"
            match(TokenType.QMARK) -> "?"
            match(TokenType.AMP) -> "&"
            match(TokenType.STAR) -> "*"
            match(TokenType.CARET) -> "^"
            else -> ""
        }
        return base + sigil
    }

    /** A macro invocation name after `@`: `name` or realm-qualified `std::name`. */
    private fun parseQualifiedMacroName(): String {
        val parts = mutableListOf(parseMacroName())
        while (match(TokenType.DOUBLE_COLON)) {
            // A name sigil belongs only to the final segment, so a qualified
            // prefix such as `std!::name` is rejected naturally here.
            if (parts.last().lastOrNull() in setOf('!', '?', '&', '*', '^')) {
                error("A macro-name sigil is only valid on the final qualified segment at line ${peek().line}")
            }
            parts += parseMacroName()
        }
        return parts.joinToString("__")
    }

    private fun requireTypeMacroHole(name: String) {
        if (!name.startsWith("\$") || name.length == 1) {
            error("Type-macro holes must start with '\$', got '$name' at line ${peek().line}")
        }
    }

    private fun parseTypeMacroPattern(): Pair<TypeFormKind, List<String>> {
        return when {
            check(TokenType.L_BRACKET) -> {
                advance() // '['
                val first = parseTypeName()
                when {
                    match(TokenType.SEMICOLON) -> {
                        // `[T; *]` or `[T;]` - an unsized array pattern (`*` / empty size
                        // are both wildcard holes); `[T; N]` binds the size hole `N`.
                        val n = when {
                            check(TokenType.R_BRACKET) -> "*"
                            match(TokenType.STAR) -> "*"
                            else -> holeName(parseTypeName())
                        }
                        consume(TokenType.R_BRACKET, "Expected ']' after '[T; N]'")
                        TypeFormKind.ARRAY_SIZED to listOf(holeName(first), n)
                    }
                    match(TokenType.COLON) -> {
                        val v = parseTypeName()
                        consume(TokenType.R_BRACKET, "Expected ']' after '[K: V]'")
                        TypeFormKind.MAP to listOf(holeName(first), holeName(v))
                    }
                    else -> {
                        consume(TokenType.R_BRACKET, "Expected ']' after '[T]'")
                        TypeFormKind.ARRAY to listOf(holeName(first))
                    }
                }
            }
            check(TokenType.L_BRACE) -> {
                advance() // '{'
                val t = parseTypeName()
                consume(TokenType.R_BRACE, "Expected '}' after '{T}'")
                TypeFormKind.SET to listOf(holeName(t))
            }
            check(TokenType.L_PAREN) -> {
                advance() // '('
                val holes = mutableListOf<String>()
                if (match(TokenType.ELLIPSIS)) {
                    val t = consume(TokenType.IDENTIFIER, "Expected name after '...' in '(...T)'").lexeme
                    holes.add(t)
                } else if (!check(TokenType.R_PAREN)) {
                    do {
                        skipNewlines()
                        if (check(TokenType.R_PAREN)) break
                        holes.add(holeName(parseTypeName()))
                        skipNewlines()
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_PAREN, "Expected ')' after tuple pattern")
                TypeFormKind.TUPLE to holes
            }
            else -> error("Expected a type pattern ('[T]', '[T; N]', '{T}', '[K: V]', or '(...T)') at line ${peek().line}")
        }
    }

    /** The type-variable name a pattern hole binds (e.g. `T` from `TypeRef.Named("T")`). */
    private fun holeName(ref: TypeRef): String = (ref as? TypeRef.Named)?.name ?: ref.displayName()

    /**
     * One arm of a type macro, or null when this arm is an ordinary one.
     *
     * ```
     * macro @query {
     *     $T          => QueryOf<$T>
     *     [...$ITEMS] => QueryOf<...$ITEMS>
     * }
     * ```
     *
     * The case of the hole is the whole distinction: `$T` stands for a type and
     * `$t` for a value, so an arm can say which it takes without a second
     * keyword to say it again. A type arm's template is a type, and the arm is
     * matched where a type is written rather than where a call is.
     */
    private fun parseTypeMacroArm(macroName: String): TypeTypeArm? {
        // A bare hole heads a type arm when what follows is the arrow, or a word
        // the arm's own grammar continues with: `$Q with $T => …`.
        val bare = check(TokenType.IDENTIFIER) && isTypeHole(peek().lexeme) &&
            peekNext()?.type.let {
                it == TokenType.FAT_ARROW || it == TokenType.WITH || it == TokenType.WITHOUT
            }
        val list = check(TokenType.L_BRACKET) &&
            peekNext()?.type == TokenType.ELLIPSIS &&
            tokens.getOrNull(current + 2)?.lexeme?.let(::isTypeHole) == true &&
            tokens.getOrNull(current + 3)?.type == TokenType.R_BRACKET
        if (!bare && !list) return null

        val holes = mutableListOf<String>()
        val holeIsList = mutableListOf<Boolean>()
        val keywords = mutableListOf<String>()
        readTypeMacroOperand(macroName, holes, holeIsList)
        // `$Q with $T without $S` - the whole clause sequence is one pattern, so
        // one arm decides the whole expansion rather than each clause rewriting
        // what the last one built.
        while (check(TokenType.WITH) || check(TokenType.WITHOUT)) {
            keywords.add(advance().lexeme)
            readTypeMacroOperand(macroName, holes, holeIsList)
        }
        consume(TokenType.FAT_ARROW, "Expected '=>' after the pattern of '@$macroName'")
        val template = parseTypeName()
        consumeNewline()
        return TypeTypeArm(
            kind = if (holeIsList.first()) TypeFormKind.PREFIX_LIST else TypeFormKind.PREFIX,
            holes = holes,
            template = template,
            name = macroName,
            keywords = keywords,
            holeIsList = holeIsList,
        )
    }

    /** One operand of a type-macro arm: `$NAME` or `[...$NAME]`. */
    private fun readTypeMacroOperand(
        macroName: String,
        holes: MutableList<String>,
        holeIsList: MutableList<Boolean>,
    ) {
        if (match(TokenType.L_BRACKET)) {
            consume(TokenType.ELLIPSIS, "A list operand captures the whole list: write '[...\$NAME]'")
            holes.add(consume(TokenType.IDENTIFIER, "Expected a '\$NAME' hole in '@$macroName'").lexeme)
            consume(TokenType.R_BRACKET, "Expected ']' after the list operand of '@$macroName'")
            holeIsList.add(true)
        } else {
            holes.add(consume(TokenType.IDENTIFIER, "Expected a '\$NAME' hole in '@$macroName'").lexeme)
            holeIsList.add(false)
        }
    }

    /** Whether [lexeme] is a `$NAME` hole standing for a type rather than a value. */
    private fun isTypeHole(lexeme: String): Boolean =
        lexeme.startsWith("\$") && lexeme.length > 1 && lexeme.drop(1).none { it.isLowerCase() }

    /**
     * One macro arm: `delim <pattern> delim => <template>`.
     *
     * Pattern forms include an empty argument list, a sequence capture, and a
     * typed single capture such as `($name: String)`. A declaration arm may
     * produce adjacent expression fragments; an `inline if` fragment is stored
     * as an [Expr.IfExpr] and selected while the macro is expanded.
     */
    private fun parseMacroArm(): MacroArm {
        val (delimiter, open, close) = when (peek().type) {
            TokenType.L_PAREN -> Triple(MacroDelimiter.PAREN, TokenType.L_PAREN, TokenType.R_PAREN)
            TokenType.L_BRACKET -> Triple(MacroDelimiter.BRACKET, TokenType.L_BRACKET, TokenType.R_BRACKET)
            TokenType.L_BRACE -> Triple(MacroDelimiter.BRACE, TokenType.L_BRACE, TokenType.R_BRACE)
            else -> error("Expected '(', '[', or '{' to start a macro arm at line ${peek().line}")
        }
        advance() // opening delimiter
        val pattern = when {
            check(close) -> MacroPattern.Empty
            check(TokenType.ELLIPSIS) -> {
                advance() // '...'
                val cap = consume(TokenType.IDENTIFIER, "Expected capture name after '...' in macro pattern").lexeme
                // `...${key: value}` - a key/value destructuring capture. The lexer
                // splits `${` into a lone `$` identifier followed by `{`.
                if (cap == "$" && check(TokenType.L_BRACE)) {
                    advance() // '{'
                    val key = consume(TokenType.IDENTIFIER, "Expected key name in '\${key: value}'").lexeme
                    consume(TokenType.COLON, "Expected ':' in '\${key: value}'")
                    val value = consume(TokenType.IDENTIFIER, "Expected value name in '\${key: value}'").lexeme
                    consume(TokenType.R_BRACE, "Expected '}' after '\${key: value}'")
                    MacroPattern.MapEntryCapture("\$$key", "\$$value")
                } else {
                    MacroPattern.SeqCapture(cap)
                }
            }
            check(TokenType.IDENTIFIER) && peek().lexeme.startsWith("$") -> {
                val cap = advance().lexeme
                consume(TokenType.COLON, "Expected ':' after typed macro capture '$cap'")
                MacroPattern.TypedCapture(cap, parseTypeName())
            }
            else -> error("Macro arm pattern must be empty, '...\$name', or '\$name: Type' at line ${peek().line}")
        }
        consume(close, "Expected matching delimiter after macro arm pattern")
        consume(TokenType.FAT_ARROW, "Expected '=>' after macro arm pattern")
        val template = parseMacroTemplateExpr()
        val tail = mutableListOf<Expr>()
        if (!check(TokenType.NEWLINE) && !check(TokenType.R_BRACE)) {
            tail += parseMacroTemplateExpr()
        } else if (check(TokenType.NEWLINE)) {
            val saved = current
            skipNewlines()
            if (check(TokenType.INLINE) && peekNext()?.type == TokenType.IF) {
                tail += parseMacroTemplateExpr()
            } else {
                current = saved
            }
        }
        consumeNewline()
        return MacroArm(delimiter, pattern, template, tail)
    }

    /** One expression fragment in a macro expansion template. */
    private fun parseMacroTemplateExpr(): Expr {
        if (match(TokenType.INLINE)) {
            if (!check(TokenType.IF)) {
                error("Only 'inline if' may select a macro template fragment at line ${peek().line}")
            }
            return parseIfExpr()
        }
        return parseExpr()
    }

    /** `spec Name { func method(params): Ret ... }` or compact callback `spec Into<T>: T { ref self } use as "..."`. */
    private fun parseSpec(isBridge: Boolean = false): TopLevel.Spec {
        val start = peek()
        consume(TokenType.SPEC, "Expected 'spec'")
        val name = consume(TokenType.IDENTIFIER, "Expected spec name").lexeme
        val typeParams = parseTypeParams() // `spec Comparable<T>` - type parameters accepted (erased for now)
        val hasCallParens = match(TokenType.L_PAREN)
        val callbackParams = if (hasCallParens) {
            val parsed = parseParams()
            consume(TokenType.R_PAREN, "Expected ')' after spec callback parameters")
            parsed
        } else {
            emptyList()
        }
        // `spec Into<T>[self: Self&]: T use as "to${T.typeName}"` - the compact
        // callback form written with the bracketed receiver every other member
        // uses, rather than the trailing `(self&)`. The receiver comes first
        // because that is the order a `func`, a `prop` and an `oper` all use.
        if (check(TokenType.L_BRACKET)) {
            val recv = parsePropReceiver()
            consume(TokenType.COLON, "Expected ':' and the conversion's result type after a spec receiver")
            val returnType = parseTypeName()
            // `spec Into<T>[self: Self&]: T use as "to${T.typeName}"` - the
            // compact one-line spec, whose whole declaration is a receiver, a
            // result and the name it answers to. This is *not* the member alias
            // that `inline prop to${T.typeName}: T = into<T>` replaced: there is
            // no member here to hang the alias off, so the name is written here.
            val useAsTemplate = if (check(TokenType.USE) && peekNext()?.type == TokenType.AS) {
                advance()
                advance()
                parseUseAsTemplate()
            } else {
                null
            }
            consumeNewline()
            val callback = SpecCallback(
                returnType = returnType,
                requiresParens = hasCallParens,
                params = callbackParams,
                receiverModifier = recv.modifier,
                receiverName = recv.name,
                useAsTemplate = useAsTemplate,
                typeParams = typeParams.names,
            )
            callbackSpecs[name] = callback
            return TopLevel.Spec(
                name,
                emptyList(),
                start.line,
                start.column,
                callback = callback,
                typeParams = typeParams.names,
                isBridge = isBridge,
                typeDefaults = typeParams.typeDefaults,
            )
        }
        var parentSpecs: List<TypeRef> = emptyList()
        if (match(TokenType.COLON)) {
            val returnType = parseTypeName()
            // `spec MutableList<T>: List<T> { … }` - spec inheritance: the child
            // includes every member of the parent. A `(` instead means this is a
            // callback spec (`spec Into<T>: T (ref self)`), handled below.
            if (!check(TokenType.L_PAREN)) {
                parentSpecs = listOf(returnType)
            } else {
            // Callback receiver in parens: `spec Into<T>: T (ref self) use as "…"`.
            consume(TokenType.L_PAREN, "Expected '(' after spec callback signature")
            skipNewlines()
            val receiverBinding = parseReceiverBinding("spec callback receiver")
            val receiverModifier = receiverBinding.modifier
            consume(TokenType.R_PAREN, "Expected ')' after spec callback receiver")
            // `spec Into<T>[self: Self&]: T use as "to${T.typeName}"` - the
            // compact one-line spec, whose whole declaration is a receiver, a
            // result and the name it answers to. This is *not* the member alias
            // that `inline prop to${T.typeName}: T = into<T>` replaced: there is
            // no member here to hang the alias off, so the name is written here.
            val useAsTemplate = if (check(TokenType.USE) && peekNext()?.type == TokenType.AS) {
                advance()
                advance()
                parseUseAsTemplate()
            } else {
                null
            }
            consumeNewline()
            val callback = SpecCallback(
                returnType = returnType,
                requiresParens = hasCallParens,
                params = callbackParams,
                receiverModifier = receiverModifier,
                receiverName = receiverBinding.name,
                useAsTemplate = useAsTemplate,
                typeParams = typeParams.names,
            )
            callbackSpecs[name] = callback
            return TopLevel.Spec(
                name,
                emptyList(),
                start.line,
                start.column,
                callback = callback,
                typeParams = typeParams.names,
            )
            }
        }
        if (hasCallParens) {
            error("Expected ':' after spec callback parameters at line ${peek().line}")
        }
        // `spec Copy requires [Clone]` - capabilities an
        // implementor must also carry. A precondition on the `impl`, not
        // inheritance: it adds no members and implies nothing on its own.
        val requiredSpecs = mutableListOf<TypeRef>()
        // `requires` is contextual: it opens the capability clause only here, in a
        // spec header. Everywhere else it is an ordinary name. One capability is
        // written bare (`requires Clone`); several take a list.
        if (check(TokenType.IDENTIFIER) && peek().lexeme == "requires" &&
            peekNext()?.type in setOf(TokenType.L_BRACKET, TokenType.IDENTIFIER)
        ) {
            advance()
            if (match(TokenType.L_BRACKET)) {
                skipNewlines()
                if (!check(TokenType.R_BRACKET)) {
                    do {
                        skipNewlines()
                        requiredSpecs.add(parseTypeName())
                        skipNewlines()
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_BRACKET, "Expected ']' to close the 'requires' list")
                if (requiredSpecs.size == 1) {
                    error("A single requirement is written 'requires ${requiredSpecs.first().displayName()}', without brackets, at line ${peek().line}")
                }
            } else {
                requiredSpecs.add(parseTypeName())
            }
        }
        // `spec Iterator assoc Item` / `spec Matrix assoc [Scalar, Rows]` - names
        // the spec's members may use as types and each implementation decides.
        // Read before the body opens, and before a bodyless spec ends.
        val assocNames = parseAssocNames()
        if (!check(TokenType.L_BRACE)) {
            consumeNewline()
            return TopLevel.Spec(name, emptyList(), start.line, start.column, typeParams = typeParams.names, parents = parentSpecs, requires = requiredSpecs, isBridge = isBridge, typeDefaults = typeParams.typeDefaults, assocNames = assocNames)
        }
        consume(TokenType.L_BRACE, "Expected '{' after spec name")
        skipNewlines()
        val methods = mutableListOf<FuncDecl>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            parseAnnotations() // trailing metadata on a requirement is accepted and dropped
            // `spec Clone { self& }` - a capability with a receiver but no
            // members. It states how the value is reached (`self&` / `self: Self&`)
            // without requiring anything of it, which is what a marker capability
            // like `Clone` needs: the operation is a language primitive, not a
            // method the implementor writes.
            if (check(TokenType.IDENTIFIER) && peek().lexeme == "self") {
                advance()
                // Both spellings are accepted: `self&` and the explicit
                // `self: Self&`. The receiver says how the value is reached; it
                // is not retained, because the capability requires no member -
                // the operation it enables is a language primitive.
                if (match(TokenType.COLON)) parseTypeName() else parsePostfixReceiverModifier()
                consumeNewline()
                continue
            }
            // `use into<T> as "to${T.typeName}"` - a member's call-site alias,
            // written as its own line so the declaration above stays a plain
            // declaration. Only valid inside a spec, and only for a member with
            // type parameters: the template exists to fold them into a name, so
            // without them there is nothing to fold and the member's own name
            // already is the call-site name.
            if (check(TokenType.USE) && peekNext()?.type == TokenType.IDENTIFIER) {
                error(
                    "'use … as \"…\"' is no longer how a spec aliases a member, at line ${peek().line}; " +
                        "declare the alias at its own name instead: " +
                        "'inline prop to\${T.typeName}: T = into<T>'",
                )
            }
            // `oper<SYM> [self: Self&](operands): Ret` - an operator requirement.
            // A spec is where an operator's contract lives (its receiver, its
            // operand, and above all its result type: `Order` fixing `Compare`
            // and `PartialOrder` fixing `PartialCompare` is what lets an `impl`
            // omit the return type entirely).
            if (check(TokenType.OPER)) {
                val operStart = advance()
                val opName = parseOperatorName()
                val operTypeParams = parseTypeParams()
                // The receiver is optional here: `oper# [self: Self&]: ULong` and
                // the terser `oper#: ULong` both state the same requirement, since
                // a spec member is always reached through `Self`.
                val orecv = if (check(TokenType.L_BRACKET)) {
                    parsePropReceiver()
                } else {
                    PropReceiver("self", null, ParamModifier.SHARED)
                }
                val ooperands = if (match(TokenType.L_PAREN)) {
                    val parsed = if (check(TokenType.R_PAREN)) emptyList() else parseParams()
                    consume(TokenType.R_PAREN, "Expected ')' after operator operands in spec")
                    parsed
                } else emptyList()
                val oret: TypeAnnotation = if (match(TokenType.COLON)) {
                    skipNewlines()
                    TypeAnnotation.Explicit(parseTypeName())
                } else TypeAnnotation.Inferred
                // A range operator's declarative step, as at every other site.
                if (match(TokenType.BY)) parseExpr()
                consumeNewline()
                methods.add(
                    FuncDecl(
                        operatorMemberName(opName), ooperands, oret, emptyList(), false,
                        operTypeParams.names, operStart.line, operStart.column,
                        receiverModifier = orecv.modifier, receiverName = orecv.name,
                    ),
                )
                continue
            }
            // `inline prop to${T.typeName}: T = into<T>` - a call-site alias for
            // another member of this spec, declared at the alias's own name
            // rather than trailing the member it renames.
            //
            // `inline` because nothing is generated: the name is expanded where
            // the spec is applied, so `Into<String>` makes `into` reachable as
            // `toString`, and there is no second member to dispatch to.
            if (check(TokenType.INLINE) && peekNext()?.type == TokenType.PROP) {
                val aliasStart = advance() // 'inline'
                advance() // 'prop'
                val template = parseSplicedMemberName()
                consume(TokenType.COLON, "Expected ':' and the alias's type")
                parseTypeName()
                consume(TokenType.EQUAL, "Expected '=' and the member this aliases")
                val target = consumeIdentifierLike("Expected the aliased member's name")
                parseTypeParams()
                consumeNewline()
                val aliased = methods.indexOfFirst { it.name == target }
                if (aliased < 0) {
                    error("'$target' names no member of spec '$name' at line ${aliasStart.line}")
                }
                methods[aliased] = methods[aliased].copy(useAsTemplate = template)
                continue
            }
            // `prop name: Type` - a property requirement (a zero-arg getter).
            if (check(TokenType.PROP)) {
                advance()
                val pname = consumeIdentifierLike("Expected property name in spec")
                // `prop into<T>[self: Self&]: T` - the member carries its own
                // type parameters, which is what lets a call site name the
                // target: `value.into<String>`.
                val ptypeParams = parseTypeParams()
                // A receiver-less requirement is STATIC: `prop rank: Int` asks for
                // `Type::rank`, satisfied by an `impl Spec for Type:: { … }` member,
                // where `prop rank[self: Self&]: Int` asks for an instance property.
                val preceiver = if (check(TokenType.L_BRACKET)) {
                    parsePropReceiver()
                } else {
                    PropReceiver("self", null, ParamModifier.SHARED)
                }
                consume(TokenType.COLON, "Expected ':' after spec property name")
                val ptype = parseTypeName()
                // A property's call-site alias is declared at its own name
                // (`inline prop to${T.typeName}: …`), not trailing the member.
                val propUseAs: String? = null
                consumeNewline()
                methods.add(FuncDecl(
                    pname, emptyList(), TypeAnnotation.Explicit(ptype), emptyList(), false,
                    ptypeParams.names,
                    start.line, start.column, receiverModifier = preceiver.modifier,
                    receiverName = preceiver.name,
                    memberCallStyle =
                        if (preceiver.type == null) MemberCallStyle.STATIC_PROPERTY else MemberCallStyle.PROPERTY,
                    useAsTemplate = propUseAs,
                ))
                continue
            }
            consume(TokenType.FUNC, "Expected 'func' or 'prop' in spec")
            val mname = consume(TokenType.IDENTIFIER, "Expected method name").lexeme
            // `func from<T>(value: T): Self` - the member's own type parameters,
            // read before the receiver exactly as on a `prop`.
            val mtypeParams = parseTypeParams()
            // A receiver-less `func` is STATIC, matching the rule `prop` already
            // follows: `func from(value: T): Self` asks for `Type::from`, built
            // by an `impl <Spec> for Type:: { … }`. It is the only shape a
            // constructing conversion can have - there is no `self` to convert.
            val mreceiver = if (check(TokenType.L_BRACKET)) parsePropReceiver() else null
            consume(TokenType.L_PAREN, "Expected '('")
            val params = parseParams()
            consume(TokenType.R_PAREN, "Expected ')'")
            val returnType: TypeAnnotation = if (match(TokenType.COLON)) {
                TypeAnnotation.Explicit(parseTypeName())
            } else {
                TypeAnnotation.Inferred
            }
            // `func into[self: Self&](): T use as "to${T.typeName}"` - the
            // member's call-site alias. The member keeps its own name; the
            // template only adds a second one.
            // As on a property: the alias is declared at its own name.
            val memberUseAs: String? = null
            // A body makes the member *provided*: an implementor that writes its
            // own gets that one, and one that does not gets this. What every
            // implementation would write identically belongs with the spec, not
            // copied into each of them.
            val provided = if (match(TokenType.L_BRACE)) {
                val statements = parseBlock()
                consume(TokenType.R_BRACE, "Expected '}' after a provided spec method")
                statements
            } else {
                emptyList()
            }
            consumeNewline()
            methods.add(FuncDecl(
                mname, params, returnType, provided, false, mtypeParams.names,
                start.line, start.column,
                receiverModifier = mreceiver?.modifier ?: ParamModifier.SHARED,
                receiverName = mreceiver?.name ?: "self",
                memberCallStyle =
                    if (mreceiver == null) MemberCallStyle.STATIC_METHOD else MemberCallStyle.METHOD,
                useAsTemplate = memberUseAs,
            ))
        }
        consume(TokenType.R_BRACE, "Expected '}' after spec methods")
        consumeNewline()
        return TopLevel.Spec(name, methods, start.line, start.column, typeParams = typeParams.names, parents = parentSpecs, requires = requiredSpecs, isBridge = isBridge, typeDefaults = typeParams.typeDefaults, assocNames = assocNames)
    }

    /** `assoc Item` / `assoc [Scalar, Rows, Columns]` on a spec. */
    private fun parseAssocNames(): List<String> {
        if (!(check(TokenType.IDENTIFIER) && peek().lexeme == "assoc")) return emptyList()
        advance()
        if (!match(TokenType.L_BRACKET)) {
            return listOf(consume(TokenType.IDENTIFIER, "Expected an associated type name after 'assoc'").lexeme)
        }
        val names = mutableListOf<String>()
        do {
            skipNewlines()
            names.add(consume(TokenType.IDENTIFIER, "Expected an associated type name").lexeme)
            skipNewlines()
        } while (match(TokenType.COMMA))
        consume(TokenType.R_BRACKET, "Expected ']' after the associated type names")
        return names
    }

    /** `assoc Item = String` / `assoc [Scalar = T, Rows = Int]` on an implementation. */
    private fun parseAssocBindings(): Map<String, TypeRef> {
        if (!(check(TokenType.IDENTIFIER) && peek().lexeme == "assoc")) return emptyMap()
        advance()
        val bindings = mutableMapOf<String, TypeRef>()
        fun one() {
            val name = consume(TokenType.IDENTIFIER, "Expected an associated type name").lexeme
            consume(TokenType.EQUAL, "Expected '=' after the associated type '$name'")
            bindings[name] = parseTypeName()
        }
        if (!match(TokenType.L_BRACKET)) {
            one()
            return bindings
        }
        do {
            skipNewlines()
            one()
            skipNewlines()
        } while (match(TokenType.COMMA))
        consume(TokenType.R_BRACKET, "Expected ']' after the associated types")
        return bindings
    }

    /** `when scrutinee { patterns -> { body } ... else -> { body } }`. */
    private fun parseWhen(): Stmt.When {
        val start = peek()
        val scrutinee = parseWhenHead()
        val parts = parseWhenBranches(scrutinee) { parseWhenStatementBody() }
        consumeNewline()
        return Stmt.When(
            scrutinee,
            parts.branches(start),
            parts.elseBody,
            start.line,
            start.column
        )
    }

    /** `when <scrutinee>` - the shared head of all three `when` forms. */
    /**
     * Parses an expression in a control-flow *header* - a condition, a scrutinee,
     * an iterable, a step - where the `{` that follows opens the construct's body
     * rather than a trailing lambda on the expression.
     *
     * Restoring the previous value rather than forcing `true` is the point: a
     * header can sit inside a context that already suppressed trailing lambdas
     * (`when x { … if ready() { … } … }`), and forcing it back on there would let
     * the outer construct's `{` be eaten instead.
     */
    private fun <T> withoutTrailingLambda(parse: () -> T): T {
        val saved = allowTrailingLambda
        allowTrailingLambda = false
        try {
            return parse()
        } finally {
            allowTrailingLambda = saved
        }
    }

    private fun parseWhenHead(): Expr {
        consume(TokenType.WHEN, "Expected 'when'")
        // A branch's `{` must not be mistaken for a trailing lambda on a
        // scrutinee that ends in a call.
        return withoutTrailingLambda { parseExpr() }
    }

    /**
     * A statement-form branch body: `-> { statements }`, or `-> statement`.
     *
     * Braces are for grouping, so a branch that does one thing needs none:
     * `actionRed -> tint = "red"` reads as the mapping it is, and a table of
     * them reads as a table. A branch that does several still writes the block,
     * because that is what a block is for.
     */
    private fun parseWhenStatementBody(): List<Stmt> {
        if (!check(TokenType.L_BRACE)) {
            return listOf(parseStmt())
        }
        consume(TokenType.L_BRACE, "Expected '{' after '->'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        return body
    }

    /**
     * The branches of a `when`, with each body read by [parseBody].
     *
     * The three `when` forms - statement, `return when`, and expression - differ
     * only in what a branch body *is* and what the branches are assembled into.
     * Everything else (patterns, `is` checks, comma-separated multi-pattern
     * branches, `else`, the newline handling) is identical, so it lives here
     * once rather than being re-scanned per form.
     */
    private fun <B> parseWhenBranches(scrutinee: Expr, parseBody: () -> B): WhenParts<B> {
        consume(TokenType.L_BRACE, "Expected '{' after when scrutinee")
        skipNewlines()
        val patterns = mutableListOf<List<Expr>>()
        val bodies = mutableListOf<B>()
        var elseBody: B? = null
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            if (match(TokenType.ELSE)) {
                consume(TokenType.ARROW, "Expected '->' after else")
                elseBody = parseBody()
                skipNewlines()
                break
            }
            val group = mutableListOf<Expr>()
            group.add(parseWhenPattern(scrutinee))
            while (match(TokenType.COMMA)) group.add(parseWhenPattern(scrutinee))
            consume(TokenType.ARROW, "Expected '->' after when patterns")
            patterns.add(group)
            bodies.add(parseBody())
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after when body")
        return WhenParts(patterns, bodies, elseBody)
    }

    /** One `when`'s parsed branches, before they are assembled into a form. */
    private class WhenParts<B>(
        val patterns: List<List<Expr>>,
        val bodies: List<B>,
        val elseBody: B?,
    )

    /** The branches of a statement-shaped `when`, in AST form. */
    private fun WhenParts<List<Stmt>>.branches(start: Token): List<Stmt.WhenBranch> {
        val out = mutableListOf<Stmt.WhenBranch>()
        var i = 0
        while (i < this.patterns.size) {
            out.add(Stmt.WhenBranch(this.patterns[i], this.bodies[i], start.line, start.column))
            i++
        }
        return out
    }

    /** Parses one `when` pattern. `is Type` becomes an [Expr.IsCheck] against the [scrutinee]
     *  (used to match a `Var<…>` by the held value's runtime type); anything else is a normal
     *  expression pattern (value / enum variant / slot destructuring). */
    private fun parseWhenPattern(scrutinee: Expr): Expr {
        if (check(TokenType.IS)) {
            val at = advance() // 'is'
            val name = parseQualifiedTypeName("Expected type name after 'is'")
            return Expr.IsCheck(scrutinee, name, at.line, at.column, at.lexeme.length)
        }
        return parseExpr()
    }

    private fun parseTopLevelInlineAssert(): TopLevel.InlineAssert {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.ASSERT, "Expected 'assert'")
        val savedTrailing = allowTrailingLambda
        allowTrailingLambda = false
        val condition = try {
            parseExpr()
        } finally {
            allowTrailingLambda = savedTrailing
        }
        consume(TokenType.L_BRACE, "Expected '{' after assert condition")
        skipNewlines()
        val message = parseExpr()
        skipNewlines()
        consume(TokenType.R_BRACE, "Expected '}' after assert message")
        consumeNewline()
        return TopLevel.InlineAssert(condition, message, start.line, start.column)
    }

    private fun parseTopLevelInlineTrace(): TopLevel.InlineTrace {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.TRACE, "Expected 'trace'")
        val (level, message) = parseTracePayload(start, inline = true)
        consumeNewline()
        return TopLevel.InlineTrace(message, start.line, start.column, level)
    }

    /**
     * True when [index] holds a `module` that opens a module declaration.
     *
     * `module` is contextual, not reserved: it lexes as an ordinary identifier,
     * so `func module()` and `var module = 7` mean what they say. A module
     * header is the one place the word is followed by another name, which is
     * what distinguishes it - and it is only ever looked for at the top of a
     * file, where a bare identifier could not begin a declaration anyway.
     */
    private fun isModuleWordAt(index: Int): Boolean {
        val word = tokens.getOrNull(index) ?: return false
        if (word.type != TokenType.IDENTIFIER || word.lexeme != "module") return false
        val next = tokens.getOrNull(index + 1) ?: return false
        return next.type == TokenType.IDENTIFIER
    }

    /**
     * True when a module header begins here: an optional `exposed`, an optional
     * `confined`, then `module`. Lets those words keep their ordinary meaning as
     * declaration modifiers everywhere else.
     */
    private fun isModuleHeaderAhead(): Boolean {
        var i = current
        if (tokens.getOrNull(i)?.type == TokenType.EXPOSE) i++
        // `exposed if COND \n module …` - the newline before `module` is mandatory.
        if (tokens.getOrNull(i)?.type == TokenType.IF) {
            var j = i + 1
            while (j < tokens.size && tokens[j].type != TokenType.NEWLINE) j++
            // exactly one newline, then `module`
            return tokens.getOrNull(j)?.type == TokenType.NEWLINE && isModuleWordAt(j + 1)
        }
        if (tokens.getOrNull(i)?.type == TokenType.CONFINE) i++
        return isModuleWordAt(i)
    }

    private fun parseModule(): String {
        consume(TokenType.IDENTIFIER, "Expected 'module'")
        // Qualified names are allowed: `module std.math`. Segments use
        // [consumeIdentifierLike] so soft keywords (e.g. `weak`) are accepted.
        val name = StringBuilder(consumeIdentifierLike("Expected module name"))
        while (match(TokenType.DOT)) {
            name.append('.').append(consumeIdentifierLike("Expected name after '.' in module"))
        }
        consumeNewline()
        return name.toString()
    }

    private fun parseFuncDecl(
        isInline: Boolean = false,
        annotations: List<Annotation> = emptyList(),
        isFlow: Boolean = false,
        isOverride: Boolean = false,
        isVirtual: Boolean = false,
        isTask: Boolean = false,
        isReactive: Boolean = false,
        isUnsafe: Boolean = false,
        visibility: Visibility = Visibility.PUBLIC,
        inImplBlock: Boolean = false,
    ): FuncDecl {
        val start = peek()
        // `async func name(…)` - `async` qualifies `func` rather than replacing
        // it, so an asynchronous declaration still reads as a function.
        if (isTask) {
            consume(TokenType.IDENTIFIER, "Expected 'async'")
            consume(TokenType.FUNC, "Expected 'func' after 'async'")
        } else {
            val keyword = TokenType.FUNC
            consume(keyword, "Expected '${keyword.name.lowercase()}'")
        }
        if (check(TokenType.LESS)) {
            error("Type parameters go after the function name: write 'func name<…>(…)', not 'func<…> name(…)', at line ${peek().line}")
        }
        val name = consumeIdentifierLike("Expected function name")
        // Type parameters follow the name: `func abs<T>(x: T)`.
        val typeParamsAfter = parseTypeParams()
        val typeParams = typeParamsAfter.names
        val variadicParam = typeParamsAfter.variadic
        val constParams = typeParamsAfter.constParams
        // Bracketed receiver: `func m[self: Type&](…): R`. It sits between the name
        // and the parameters, exactly where `prop`, `ctor`, `dtor` and `oper` put
        // theirs, so every member reads the same way. Naming a type makes the
        // function an extension on it, callable as `value.m()`.
        var extensionReceiver: Param? = null
        var bracketReceiver: PropReceiver? = null
        if (check(TokenType.L_BRACKET)) {
            bracketReceiver = parsePropReceiver()
            if (!inImplBlock) {
                bracketReceiver.type?.let { extensionReceiver = Param(bracketReceiver!!.name, it) }
            }
        }
        consume(TokenType.L_PAREN, "Expected '(' after function name")
        val params = parseParams(variadicParam).toMutableList()
        consume(TokenType.R_PAREN, "Expected ')' after parameters")
        // Whether the author wrote a return type at all. Both branches below that
        // do not write one still produce a type - the rule is that an omitted
        // return type *means* Unit - but the diagnostic needs to know which.
        var returnTypeDeclared = true
        val returnType: TypeAnnotation = if (match(TokenType.COLON)) {
            TypeAnnotation.Explicit(parseTypeName())
        } else if (check(TokenType.QMARK_BANG)) {
            // `func f() ?! E` - a function that yields nothing but may fail. The ok
            // type is Unit; only the error set is written.
            returnTypeDeclared = false
            TypeAnnotation.Explicit(parseTypeSuffixes(TypeRef.Named("Unit", synthesized = true)))
        } else {
            returnTypeDeclared = false
            TypeAnnotation.Inferred
        }
        val funcWhereClause = parseWhereClause()
        val minVariadicLength = variadicMinLengthOf(funcWhereClause)
        // A receiver is part of the declaration signature. Function bodies never
        // introduce receivers; `func read[self: Self&]()` is the sole spelling.
        val receiverModifier: ParamModifier = bracketReceiver?.modifier ?: ParamModifier.EXCLUSIVE
        val receiverName = bracketReceiver?.name ?: "self"

        // Optional contract clauses before the body: `in { ... }` preconditions and
        // `out { r -> ... }` postconditions. A contract-style declaration then
        // supplies its body as `scope { ... }`.
        val contracts = parseContractClauses()
        run {
            val i = nextMeaningfulIndex()
            val tok = tokens.getOrNull(i)
            if (tok?.type == TokenType.SCOPE &&
                tokens.getOrNull(i + 1)?.type == TokenType.L_BRACE
            ) {
                while (current < i) advance() // skip newlines
                advance() // 'scope' - the '{' that follows is the function body
            }
        }

        val savedFailSets = currentFailSets
        currentFailSets = ((returnType as? TypeAnnotation.Explicit)?.ref as? TypeRef.Failable)?.errSets.orEmpty()
        val body: List<Stmt>
        if (match(TokenType.EQUAL)) {
            // `= inline { … }` / `= deepinline { … }` blocks, or an
            // expression body (`func twice(x: Int): Int = x * 2`) which
            // desugars to a single return statement.
            body = when {
                check(TokenType.INLINE) -> listOf(parseInlineBlock())
                check(TokenType.DEEPINLINE) -> listOf(parseDeepInlineBlock())
                else -> {
                    val expr = parseExpr()
                    consumeNewline()
                    listOf(Stmt.Return(expr, expr.line, expr.column))
                }
            }
        } else {
            consume(TokenType.L_BRACE, "Expected '{' before function body")
            skipNewlines()
            if (isSelfReceiverHeaderAhead()) {
                error(
                    "Function receivers must be declared in the signature: " +
                        "write 'func $name[self: Self&](...)', not '{ self& -> ... }', " +
                        "at line ${peek().line}",
                )
            }
            // `func main() { ...args[: Type] -> body }` - bind CLI args to a
            // synthetic variadic param. Only main; only when declared with ().
            if (name == "main" && params.isEmpty() && check(TokenType.ELLIPSIS)) {
                advance() // '...'
                val argsName = consumeIdentifierLike("Expected args name after '...' in main")
                val elemType = if (match(TokenType.COLON)) parseTypeName() else TypeRef.Named("String")
                consume(TokenType.ARROW, "Expected '->' after main args binding")
                skipNewlines()
                params.add(Param(argsName, TypeRef.Array(elemType), variadic = true))
            }
            val stmts = mutableListOf<Stmt>()
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                stmts.add(parseStmt())
                skipNewlines()
            }
            consume(TokenType.R_BRACE, "Expected '}' after function body")
            consumeNewline()
            body = stmts
        }
        val contractedBody = applyContracts(body, contracts, rewriteYields = isFlow)
        currentFailSets = savedFailSets
        checkReturnedParams(name, params, start.line)
        return FuncDecl(
            name = name,
            params = params,
            returnType = returnType,
            body = contractedBody,
            isInline = isInline,
            typeParams = typeParams,
            line = start.line,
            column = start.column,
            length = start.lexeme.length,
            annotations = annotations,
            isFlow = isFlow,
            isOverride = isOverride,
            isVirtual = isVirtual,
            isTask = isTask,
            isReactive = isReactive,
            isUnsafe = isUnsafe,
            visibility = visibility,
            receiverModifier = receiverModifier,
            receiverName = receiverName,
            extensionReceiver = extensionReceiver,
            variadicParam = variadicParam,
            minVariadicLength = minVariadicLength,
            whereClause = funcWhereClause,
            constParams = constParams,
            returnTypeDeclared = returnTypeDeclared,
        )
    }

    /**
     * Checks `name: return T` - the parameter whose ownership goes back (§13).
     *
     * The marker says the caller gets the value back when the call ends, so it
     * only makes sense where ownership actually moved: a borrow already leaves
     * the caller owning it, and there would be nothing to return.
     */
    private fun checkReturnedParams(name: String, params: List<Param>, line: Int) {
        for (param in params.filter { it.returnsOwnership }) {
            if (param.modifier != ParamModifier.NONE) {
                error(
                    "line $line: '${param.name}' is a borrow, so '$name' never takes ownership of it and has " +
                        "none to give back; drop the 'return', or drop the borrow to take ownership",
                )
            }
        }
    }

    private fun isSelfReceiverHeaderAhead(): Boolean {
        fun type(j: Int): TokenType? = tokens.getOrNull(j)?.type
        fun lexeme(j: Int): String? = tokens.getOrNull(j)?.lexeme
        // The receiver is always named `self`. A `,` after it introduces operands
        // (`{ self&, x: Int, y: Animal& -> }`), so scan past `self[borrow]` and any
        // `, operand[: Type][borrow]` pairs, and require a terminating `->`.
        var j = current
        when {
            lexeme(j) == "self" -> {
                j += 1
                if (type(j) in setOf(TokenType.AMP, TokenType.BANG)) j += 1
            }
            else -> return false
        }
        while (type(j) == TokenType.COMMA) {
            j += 1
            if (type(j) != TokenType.IDENTIFIER) return false
            j += 1
            if (type(j) == TokenType.COLON) {
                // Skip the operand type; nested generics balance `<`/`>`.
                j += 1
                var depth = 0
                while (true) {
                    val t = type(j) ?: return false
                    if (t == TokenType.LESS) depth++
                    else if (t == TokenType.GREATER) depth--
                    else if (depth == 0 && (t == TokenType.COMMA || t == TokenType.ARROW)) break
                    j += 1
                }
            } else if (type(j) in setOf(TokenType.AMP, TokenType.BANG)) j += 1
        }
        return type(j) == TokenType.ARROW
    }

    /**
     * A member name written with a splice: `to${T.typeName}`.
     *
     * An identifier stops at `{`, so the name arrives in pieces - `to$`, a
     * brace, the placeholder's own tokens, and a closing brace. They are joined
     * back into the template text [UseAsTemplate] expands, which is the same
     * text the older `use … as "to${T.typeName}"` spelling wrote in quotes.
     */
    private fun parseSplicedMemberName(): String {
        val out = StringBuilder(consumeIdentifierLike("Expected the alias's name"))
        while (check(TokenType.L_BRACE)) {
            advance()
            out.append('{')
            var depth = 1
            while (depth > 0 && !isAtEnd()) {
                when (peek().type) {
                    TokenType.L_BRACE -> depth++
                    TokenType.R_BRACE -> depth--
                    else -> {}
                }
                out.append(advance().lexeme)
            }
            // `to${T}Name` - the name may continue past the placeholder.
            if (check(TokenType.IDENTIFIER)) out.append(advance().lexeme)
        }
        return out.toString()
    }

    /**
     * The name template of a compact one-line spec: `use as "to${T.typeName}"`.
     *
     * Only that form still writes one. A spec *member*'s alias is declared at
     * its own name (`inline prop to${T.typeName}: T = into<T>`).
     */
    private fun parseUseAsTemplate(): String {
        val tok = peek()
        return when (tok.type) {
            TokenType.STRING_LITERAL -> {
                advance()
                tok.literal as? String ?: tok.lexeme.trim('"')
            }
            TokenType.INTERPOLATED_STRING -> {
                advance()
                tok.lexeme.removeSurrounding("\"")
            }
            else -> error("Expected string literal after 'use as', got '${tok.lexeme}' (${tok.type}) at line ${tok.line}")
        }
    }

    /** Parses any `in { ... }` / `out { r -> ... }` contract clauses before a function body. */
    private fun parseContractClauses(): ContractClauses {
        val preconditions = mutableListOf<Stmt>()
        val postconditions = mutableListOf<Stmt>()
        var resultName: String? = null
        // A function states its requirement once and its promise once. Several
        // `assert`s go inside one clause; several clauses would only split one
        // idea across two places and raise the question of what order they run
        // in.
        var sawIn = false
        var sawOut = false
        while (true) {
            val i = nextMeaningfulIndex()
            val t = tokens.getOrNull(i) ?: break
            val isClause = (t.type == TokenType.IN || t.type == TokenType.OUT) &&
                tokens.getOrNull(i + 1)?.type == TokenType.L_BRACE
            if (!isClause) break
            while (current < i) advance() // skip newlines
            val keyword = advance()
            if (keyword.type == TokenType.IN) {
                if (sawIn) {
                    error(
                        "A function may have one 'in' contract at line ${keyword.line} - " +
                            "put every precondition in the same clause"
                    )
                }
                sawIn = true
            } else {
                if (sawOut) {
                    error(
                        "A function may have one 'out' contract at line ${keyword.line} - " +
                            "put every postcondition in the same clause"
                    )
                }
                sawOut = true
            }
            consume(TokenType.L_BRACE, "Expected '{' after '${keyword.lexeme}' contract")
            skipNewlines()
            if (keyword.type == TokenType.OUT) {
                // The result binding is optional: `out { r -> … }` names it, while
                // `out { assert it >= 1 … }` leaves it implicit as `it`.
                val named = check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.ARROW
                val name = if (named) {
                    val n = consumeIdentifierLike("Expected result name in out contract")
                    consume(TokenType.ARROW, "Expected '->' after out contract result name")
                    n
                } else {
                    "it"
                }
                if (resultName != null && resultName != name) {
                    error("All out contract clauses for a function must use the same result name at line ${keyword.line}")
                }
                resultName = name
                skipNewlines()
                postconditions += parseBlock()
            } else {
                preconditions += parseBlock()
            }
            consume(TokenType.R_BRACE, "Expected '}' after '${keyword.lexeme}' contract")
            skipNewlines()
        }
        return ContractClauses(preconditions, resultName, postconditions)
    }

    private fun applyContracts(
        body: List<Stmt>,
        contracts: ContractClauses,
        rewriteYields: Boolean = false,
    ): List<Stmt> {
        if (contracts.preconditions.isEmpty() && contracts.postconditions.isEmpty()) return body
        val withPosts = if (contracts.postconditions.isEmpty()) body else {
            body.map { rewriteContractResults(it, contracts, rewriteYields) }
        }
        return contracts.preconditions + withPosts
    }

    private fun rewriteContractResults(
        stmt: Stmt,
        contracts: ContractClauses,
        rewriteYields: Boolean,
    ): Stmt {
        return when (stmt) {
            is Stmt.Return -> rewriteContractReturn(stmt, contracts)
            is Stmt.Yield -> if (rewriteYields) rewriteContractYield(stmt, contracts) else stmt
            is Stmt.If -> stmt.copy(
                thenBranch = stmt.thenBranch.map { rewriteContractResults(it, contracts, rewriteYields) },
                elseBranch = stmt.elseBranch?.map { rewriteContractResults(it, contracts, rewriteYields) },
            )
            is Stmt.While -> stmt.copy(body = stmt.body.map { rewriteContractResults(it, contracts, rewriteYields) })
            is Stmt.For -> stmt.copy(body = stmt.body.map { rewriteContractResults(it, contracts, rewriteYields) })
            is Stmt.Loop -> stmt.copy(body = stmt.body.map { rewriteContractResults(it, contracts, rewriteYields) })
            is Stmt.When -> stmt.copy(
                branches = stmt.branches.map { branch ->
                    branch.copy(body = branch.body.map { rewriteContractResults(it, contracts, rewriteYields) })
                },
                elseBranch = stmt.elseBranch?.map { rewriteContractResults(it, contracts, rewriteYields) },
            )
            is Stmt.Try -> stmt.copy(
                body = stmt.body.map { rewriteContractResults(it, contracts, rewriteYields) },
                catchBody = stmt.catchBody?.map { rewriteContractResults(it, contracts, rewriteYields) },
            )
            is Stmt.Scope -> stmt.copy(body = stmt.body.map { rewriteContractResults(it, contracts, rewriteYields) })
            else -> stmt
        }
    }

    private fun rewriteContractReturn(stmt: Stmt.Return, contracts: ContractClauses): Stmt {
        val value = stmt.value ?: return stmt
        val resultName = contracts.resultName ?: "__az_contract_result_${contractResultCounter++}"
        val resultRef = Expr.Identifier(resultName, stmt.line, stmt.column, resultName.length)
        return Stmt.Scope(
            listOf(
                Stmt.FinDecl(resultName, TypeAnnotation.Inferred, value, stmt.line, stmt.column),
            ) + contracts.postconditions + Stmt.Return(resultRef, stmt.line, stmt.column),
            stmt.line,
            stmt.column,
        )
    }

    private fun rewriteContractYield(stmt: Stmt.Yield, contracts: ContractClauses): Stmt {
        val resultName = contracts.resultName ?: "__az_contract_result_${contractResultCounter++}"
        val resultRef = Expr.Identifier(resultName, stmt.line, stmt.column, resultName.length)
        return Stmt.Scope(
            listOf(
                Stmt.FinDecl(resultName, TypeAnnotation.Inferred, stmt.value, stmt.line, stmt.column),
            ) + contracts.postconditions + Stmt.Yield(resultRef, stmt.line, stmt.column),
            stmt.line,
            stmt.column,
        )
    }

    /**
     * Accepts an identifier, or one of a small set of soft keywords that are
     * unambiguous as names in declaration position (`func reverse`, `pow(base:`).
     */
    /**
     * Member name after `.`/`?.`. Like [consumeIdentifierLike] but also accepts
     * the `realm` keyword, so the reflection member `(reflect X).realm` parses.
     */
    private fun consumeMemberName(message: String): String =
        if (check(TokenType.REALM)) advance().lexeme else consumeIdentifierLike(message)

    /**
     * True when [index] starts `async func` - an asynchronous declaration.
     *
     * `async` is contextual, not reserved: on its own it is the builtin that
     * spawns a task (`async { … }`), and it only qualifies a declaration when a
     * `func` follows it.
     */
    private fun isAsyncFuncAt(index: Int): Boolean {
        val word = tokens.getOrNull(index) ?: return false
        if (word.type != TokenType.IDENTIFIER || word.lexeme != "async") return false
        return tokens.getOrNull(index + 1)?.type == TokenType.FUNC
    }

    /**
     * True when [index] starts `async prop` - an asynchronous property.
     *
     * `async` qualifies `prop` exactly as it qualifies `func`: the declaration is
     * still a property, and it is still read as `value.name`; the body is what
     * may suspend.
     */
    private fun isAsyncPropAt(index: Int): Boolean {
        val word = tokens.getOrNull(index) ?: return false
        if (word.type != TokenType.IDENTIFIER || word.lexeme != "async") return false
        return tokens.getOrNull(index + 1)?.type == TokenType.PROP
    }

    private fun consumeIdentifierLike(message: String): String {
        val t = peek()
        val soft = t.type == TokenType.REVERSE ||
            t.type == TokenType.PROP ||
            t.type == TokenType.PURGE || t.type == TokenType.REMEMBER || t.type == TokenType.RETAIN ||
            t.type == TokenType.PRESERVE ||
            t.type == TokenType.ALLOC || t.type == TokenType.TEST ||
            t.type == TokenType.MACRO ||
            // `func take(…)` / `self.take()` - `take` names a stdlib operation
            // as well as the ownership-transfer prefix.
            t.type == TokenType.TAKE ||
            // `module std.error` - a module path segment is a plain name, and
            // `error` reads best as the name of the module that declares the
            // error sets.
            t.type == TokenType.ERROR
        if (t.type == TokenType.IDENTIFIER || soft) {
            advance()
            return t.lexeme
        }
        error("$message, got '${t.lexeme}' (${t.type}) at line ${t.line}")
    }

    /**
     * True while parsing a parameter, where `@name` may be a macro.
     *
     * A decorator names a declaration and is capitalised; a macro is lowercase.
     * A parameter may carry either, so the case rule is relaxed there rather
     * than for a list of names the compiler would otherwise have to know.
     */
    private var allowLowercaseAnnotation = false

    private fun parseParams(variadicTypeParam: String? = null): List<Param> {
        skipNewlines()
        if (check(TokenType.R_PAREN)) return emptyList()
        val params = mutableListOf<Param>()
        while (!check(TokenType.R_PAREN)) {
            // A borrow is a postfix sigil on the name (`x&`, `x!`), not a prefix
            // modifier, so nothing precedes the parameter name.
            val modifier = ParamModifier.NONE
            // Optional `...name` spread marker for a variadic parameter.
            val nameSpread = match(TokenType.ELLIPSIS)
            val name = consumeIdentifierLike("Expected parameter name")
            consume(TokenType.COLON, "Expected ':' after parameter name")
            // `name: return T&` - the borrow this function hands back (§13).
            val returnsOwnership = match(TokenType.RETURN)
            // A parameter may carry a macro (`@query`), which is lowercase, as
            // well as a decorator, which is not. Only the decorators are read
            // here: a macro applies to the type that follows it, so it is left
            // for the type parser rather than taken as an annotation.
            val annotations = if (check(TokenType.AT) && peekNext()?.lexeme?.firstOrNull()?.isLowerCase() == true) {
                emptyList()
            } else {
                parseAnnotations()
            }
            // Type-side `...T` expands a declared variadic generic type pack. A
            // homogeneous vararg uses `...values: T`, never `values: ...T`.
            val typeSpread = check(TokenType.ELLIPSIS)
            if (typeSpread) {
                val spreadName = peekNext()?.takeIf { it.type == TokenType.IDENTIFIER }?.lexeme
                if (variadicTypeParam == null || spreadName != variadicTypeParam) {
                    error(
                        "Type spread '...${spreadName.orEmpty()}' requires a matching variadic generic " +
                            "declaration '<...${spreadName.orEmpty()}>' at line ${peek().line}; " +
                            "for homogeneous varargs use '...$name: ${spreadName.orEmpty()}'",
                    )
                }
            }
            val isVariadic = nameSpread || typeSpread
            val parsedType = parseTypeName()
            val reference = parsedType as? TypeRef.Reference
            val unwrappedType = reference?.inner ?: parsedType
            val type = if (nameSpread && unwrappedType !is TypeRef.Array) {
                TypeRef.Array(unwrappedType)
            } else {
                unwrappedType
            }
            val normalizedModifier = reference?.kind?.paramModifier ?: modifier
            val default = if (match(TokenType.EQUAL)) parseExpr() else null
            params.add(
                Param(
                    name, type, default, normalizedModifier,
                    variadic = isVariadic, annotations = annotations, returnsOwnership = returnsOwnership,
                ),
            )
            val previousLine = tokens.getOrNull(current - 1)?.line ?: peek().line
            val separatedByPhysicalLine = peek().line > previousLine
            when {
                match(TokenType.COMMA) -> {
                    skipNewlines()
                    if (check(TokenType.R_PAREN)) break
                }
                check(TokenType.NEWLINE) -> {
                    skipNewlines()
                    if (check(TokenType.R_PAREN)) break
                }
                separatedByPhysicalLine -> continue
                else -> break
            }
        }
        return params
    }

    /**
     * A const-generic value written as an argument or a default.
     *
     * An integer stands for itself; `.RowMajor` stands for its position in the enum
     * it belongs to, and keeps the name so the specialization reads as the author
     * wrote it. [enumName] is the declared type when there is one, which is what
     * lets `.RowMajor` be resolved without repeating `MatrixOrder`.
     */
    private fun parseConstArgument(enumName: String?, parameter: String): TypeRef {
        if (check(TokenType.INT_LITERAL)) {
            val token = advance()
            return TypeRef.Const((token.literal as NumericLiteral).value as Long)
        }
        val qualifier = if (check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.DOT) {
            advance().lexeme
        } else {
            null
        }
        if (!match(TokenType.DOT)) {
            error("Expected a value for const parameter '$parameter' at line ${peek().line}")
        }
        val variant = consume(TokenType.IDENTIFIER, "Expected enum variant after '.'").lexeme
        // At a use site (`Mat<Int, 2, 2, .ColumnMajor>`) the parameter's enum is not
        // in hand, so the variant names it - uniquely, or it must be qualified.
        // At a use site (`Mat<Int, 2, 2, .ColumnMajor>`) the parameter's enum is not
        // in hand, so the variant names it - uniquely when the enum has been seen.
        // A library's enum may not have been parsed yet, so an unknown variant stays
        // symbolic and is resolved once the whole program is in view.
        val owner = qualifier ?: enumName ?: run {
            val owners = declaredEnums.filterValues { variant in it }.keys
            when (owners.size) {
                1 -> owners.first()
                0 -> return TypeRef.Const(TypeRef.Const.UNRESOLVED, variant)
                else -> error(
                    "'.$variant' is ambiguous between ${owners.sorted().joinToString(", ")}; " +
                        "qualify it, as in '${owners.sorted().first()}.$variant', at line ${peek().line}",
                )
            }
        }
        val variants = declaredEnums[owner]
            ?: error("'$owner' is not a declared enum, at line ${peek().line}")
        val ordinal = variants.indexOf(variant)
        if (ordinal < 0) error("'$owner' has no variant '$variant', at line ${peek().line}")
        return TypeRef.Const(ordinal.toLong(), variant)
    }

    /** Result of [parseTypeParams]: the names, which (if any) is the variadic pack, and which are const value params. */
    /**
     * Const parameters of the declaration being parsed, and the enum each ranges over.
     *
     * `inline when O { .RowMajor => … }` names a variant without repeating the enum,
     * so the arm has to know what `O` is to resolve it.
     */
    private var constParamEnums: Map<String, String> = emptyMap()

    private data class TypeParams(
        val names: List<String>,
        val variadic: String?,
        val constParams: Set<String> = emptySet(),
        val constDefaults: Map<String, TypeRef> = emptyMap(),
        /** Const parameter → the enum whose variants it ranges over, when it has one. */
        val constEnums: Map<String, String> = emptyMap(),
        /** Type parameter → the type it stands for when the argument is omitted. */
        val typeDefaults: Map<String, TypeRef> = emptyMap(),
        /** Parameters written `T::` - the member they drive is a static. */
        val staticParams: Set<String> = emptySet(),
    )

    /** `<T, U>` type-parameter list. A parameter may be variadic via the `...T` prefix form, or a const value param via `N: Int`. */
    private fun parseTypeParams(): TypeParams {
        if (!check(TokenType.LESS)) return TypeParams(emptyList(), null)
        advance()
        val names = mutableListOf<String>()
        val constParams = mutableSetOf<String>()
        val constDefaults = mutableMapOf<String, TypeRef>()
        val typeDefaults = mutableMapOf<String, TypeRef>()
        val staticParams = mutableSetOf<String>()
        val constEnums = mutableMapOf<String, String>()
        var variadic: String? = null
        do {
            val prefixVariadic = match(TokenType.ELLIPSIS) // `...T` prefix form
            val name = consume(TokenType.IDENTIFIER, "Expected type parameter name").lexeme
            // `spec From<T::>` - the member this parameter drives is a *static*,
            // reached as `Type::fromString` rather than through a value. The
            // marker sits on the parameter because that is what decides it.
            if (check(TokenType.DOUBLE_COLON)) {
                advance()
                staticParams.add(name)
            }
            if (check(TokenType.ELLIPSIS)) {
                error("Variadic type parameters use the prefix form '...$name', not '$name...', at line ${peek().line}")
            }
            // Optional kind/constraint after `:`:
            //  - `N: Int` → a const-generic value parameter (supplied as a `TypeRef.Const` arg).
            //  - `O: MatrixOrder` → the same, over an enum's variants.
            //  - `T: Spec` → a conformance constraint (accepted, not yet enforced).
            var constEnum: String? = null
            if (match(TokenType.COLON)) {
                val constraint = parseTypeName()
                val constraintName = (constraint as? TypeRef.Named)?.name
                if (constraintName == "Int") {
                    constParams.add(name)
                } else if (constraintName != null && constraintName in declaredEnums) {
                    constParams.add(name)
                    constEnum = constraintName
                    constEnums[name] = constraintName
                }
            }
            // `= <default>` - the same slot holds either kind of default:
            //  - `N: Int = 4`, `O: MatrixOrder = .RowMajor` → a const *value*, so
            //    `Mat<Double, 4, 4>` means the same as spelling the order out;
            //  - `Rhs = Self` → a *type*, so `PartialEqual` means the same as
            //    `PartialEqual<Self>` and the homogeneous case stays unwritten.
            //
            // Which one it is follows from what was written rather than from a
            // separate syntax: a const default is an integer or an enum variant
            // (or the parameter was already declared const by its `:` kind), and
            // anything else names a type.
            if (match(TokenType.EQUAL)) {
                val isConstDefault = name in constParams ||
                    check(TokenType.INT_LITERAL) ||
                    check(TokenType.DOT)
                if (isConstDefault) {
                    constDefaults[name] = parseConstArgument(constEnum, name)
                    constParams.add(name)
                } else {
                    typeDefaults[name] = parseTypeName()
                }
            }
            if (prefixVariadic) variadic = name
            names.add(name)
        } while (match(TokenType.COMMA))
        consume(TokenType.GREATER, "Expected '>' after type parameters")
        return TypeParams(names, variadic, constParams, constDefaults, constEnums, typeDefaults, staticParams)
    }

    /**
     * Optional `where (...T).length >= <N>` constraint on a variadic pack/function.
     * The older `where T.length >= <N>` spelling is still accepted for existing
     * source files.
     * Returns the minimum length, or null if no clause is present.
     */
    /**
     * `where <expr>` - a declaration's constraints, parsed as an ordinary expression.
     *
     * There is no separate constraint grammar: `T is Number && N in 2..4` is an
     * `&&` over an [Expr.IsCheck] and an [Expr.InCheck], and the older
     * `(...T).length >= 2` is a comparison over a member access. Keeping one grammar
     * means a constraint can say anything an expression can, and new forms need no
     * parser work at all.
     */
    private fun parseWhereClause(): Expr? {
        // A clause may sit on its own line, after a signature or a spliced fragment.
        val at = nextMeaningfulIndex()
        val token = tokens.getOrNull(at) ?: return null
        if (token.type != TokenType.IDENTIFIER || token.lexeme != "where") return null
        while (current < at) advance()
        advance() // 'where'
        val savedTrailing = allowTrailingLambda
        allowTrailingLambda = false
        return try {
            parseSpecBounds() ?: parseExpr()
        } finally {
            allowTrailingLambda = savedTrailing
        }
    }

    /**
     * `where T: Order`, `where T: PartialEqual<U>`, `where K: [Order, Clone]`,
     * `where T: Order, U: Clone` - conformance bounds.
     *
     * A bound is a *declaration*, not an expression, which is why it needs its
     * own reading: `T: Spec` is not something `parseExpr` can produce. Each one
     * lowers to the `is` predicate the constraint evaluator already understands,
     * so nothing downstream has to learn a second shape, and several bounds
     * become a conjunction.
     *
     * The spec's type arguments are read and discarded here - conformance is
     * nominal (`SymbolTable.conformsTo`), so `PartialEqual<U>` and
     * `PartialEqual` check the same thing today. Reading them keeps the source
     * able to say what it means before the checker can act on it.
     *
     * Returns null when what follows is not a bound, so an ordinary constraint
     * expression (`T.length >= 2 && T is Number`) still parses as one.
     */
    private fun parseSpecBounds(): Expr? {
        if (!isSpecBoundAhead()) return null
        var clause: Expr? = null
        do {
            val subject = peek()
            val name = consume(TokenType.IDENTIFIER, "Expected a type parameter before ':'").lexeme
            consume(TokenType.COLON, "Expected ':' in a conformance bound")
            val specs = mutableListOf<String>()
            if (match(TokenType.L_BRACKET)) {
                do {
                    specs.add(consume(TokenType.IDENTIFIER, "Expected a spec name in the bound list").lexeme)
                    parseGenericTypeArgsIfPresent()
                } while (match(TokenType.COMMA))
                consume(TokenType.R_BRACKET, "Expected ']' after the bound list")
            } else {
                specs.add(parseQualifiedTypeName("Expected a spec name after ':'"))
                parseGenericTypeArgsIfPresent()
            }
            for (spec in specs) {
                val check = Expr.IsCheck(
                    Expr.Identifier(name, subject.line, subject.column, name.length),
                    spec,
                    subject.line,
                    subject.column,
                )
                clause = clause?.let { Expr.Binary(it, TokenType.AND_AND, check, subject.line) } ?: check
            }
        } while (match(TokenType.COMMA) && isSpecBoundAhead())
        return clause
    }

    /** True when an `IDENTIFIER :` bound opens here rather than an expression. */
    private fun isSpecBoundAhead(): Boolean =
        check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.COLON

    /**
     * The minimum length a `where` clause requires of a variadic pack, or null when
     * it constrains something else.
     *
     * Recognises `(...T).length >= N` and the older `T.length >= N` within the
     * general clause, so the variadic check keeps working while every other shape
     * flows through untouched.
     */
    private fun variadicMinLengthOf(clause: Expr?): Int? {
        val expr = clause ?: return null
        // The reading has to search the clause, not just its root: in
        // `T is Number && (...T).length >= 2` the root is `&&` and the comparison is
        // one conjunct down.
        if (expr is Expr.Grouping) return variadicMinLengthOf(expr.expr)
        if (expr is Expr.Binary && expr.op == TokenType.AND_AND) {
            return variadicMinLengthOf(expr.left) ?: variadicMinLengthOf(expr.right)
        }
        val cmp = expr as? Expr.Binary ?: return null
        if (cmp.op != TokenType.GREATER_EQUAL) return null
        val member = cmp.left as? Expr.Member ?: return null
        if (member.name != "length") return null
        val literal = (cmp.right as? Expr.IntLiteral)?.value ?: return null
        return literal.toString().toIntOrNull()
    }

    private fun parseVariadicWhereClause(): Int? = variadicMinLengthOf(parseWhereClause())

    private fun parseTypeName(): TypeRef {
        // Reference kinds are postfix now: `T&` (immutable) / `T!` (mutable), handled
        // by parseTypeSuffixes. The `ref`/`mut`/`shared`/`weak` prefix keywords were
        // removed (shared/weak reference kinds will return via std.memory).
        // `...T` - variadic type (prefix). Wraps the type in the internal array representation.
        if (match(TokenType.ELLIPSIS)) {
            val inner = parseTypeAtom()
            return TypeRef.Array(inner)
        }
        return parseTypeInfixes(parseTypeSuffixes(parseTypeAtom()))
    }

    /**
     * `[a]` / `[a, b]` after a borrow - the sources a returned borrow comes from.
     *
     * Only consumed where it unambiguously follows the borrow sigil, so an
     * `Array<T>&` followed by an unrelated `[` in a wider expression is not
     * mistaken for an origin list. Empty when nothing is written, which is the
     * common case: Azora infers borrow relationships and asks for them only
     * where a signature must state one.
     */
    /** The borrow an `&`/`!` sigil creates. */
    private fun borrowOp(sigil: TokenType): OwnershipOp =
        if (sigil == TokenType.AMP) OwnershipOp.SHARE else OwnershipOp.BORROW

    private fun parseBorrowOrigins(): List<String> {
        if (!check(TokenType.L_BRACKET)) return emptyList()
        // An origin list holds names; anything else is a different `[`.
        if (peekNext()?.type != TokenType.IDENTIFIER) return emptyList()
        val closer = tokens.getOrNull(current + 2)?.type
        if (closer != TokenType.R_BRACKET && closer != TokenType.COMMA) return emptyList()
        advance() // '['
        val origins = mutableListOf<String>()
        do {
            origins.add(consume(TokenType.IDENTIFIER, "Expected a borrow origin name").lexeme)
        } while (match(TokenType.COMMA))
        consume(TokenType.R_BRACKET, "Expected ']' after the borrow origin list")
        if (origins.distinct().size != origins.size) {
            error("Duplicate borrow origin at line ${peek().line}")
        }
        return origins
    }

    private fun parseTypeSuffixes(start: TypeRef): TypeRef {
        var base = start
        // Suffix type modifiers: `T?`, `T!Error`, and `T![A, B]`, in any order.
        while (true) {
            base = when {
                match(TokenType.QMARK) -> TypeRef.Nullable(base)
                check(TokenType.STAR) -> { advance(); TypeRef.Pointer(base) }
                check(TokenType.CARET) -> { advance(); TypeRef.Pointer(base, mutable = true) }
                // Failable (error) type: `T ?! E` or `T ?! [E1, E2]`.
                match(TokenType.QMARK_BANG) -> {
                    val errSets = if (match(TokenType.L_BRACKET)) {
                        val names = mutableListOf<String>()
                        do {
                            names.add(parseErrorSetName("in failable type"))
                        } while (match(TokenType.COMMA))
                        consume(TokenType.R_BRACKET, "Expected ']' after error-set list")
                        if (names.distinct().size != names.size) {
                            error("Duplicate error set in failable type at line ${peek().line}")
                        }
                        names
                    } else {
                        listOf(parseErrorSetName("after '?!'"))
                    }
                    TypeRef.Failable(base, errSets)
                }
                // Postfix borrows: `T&` (immutable), `T!` (mutable). Replace the old
                // `ref T` / `mut ref T` prefix forms. Errors are `T ?! E` now, so a
                // `!` in type position is always a mutable borrow.
                match(TokenType.AMP) -> TypeRef.Reference(TypeRef.RefKind.BORROWED, base, parseBorrowOrigins())
                match(TokenType.BANG) -> TypeRef.Reference(TypeRef.RefKind.MUTABLE, base, parseBorrowOrigins())
                else -> return base
            }
        }
    }

    /** Type-filter composition used by ECS queries: `A with B`, `A without C`. */
    private fun parseTypeInfixes(start: TypeRef): TypeRef {
        var base = start
        while (true) {
            val operator = when {
                match(TokenType.WITH) -> "with"
                match(TokenType.WITHOUT) -> "without"
                else -> return base
            }
            val right = parseTypeName()
            base = NamedTypeMacroCall.create(
                operator,
                listOf(base, right),
                form = NamedTypeMacroCall.Form.Infix,
            )
        }
    }

    private fun isNamedTypeMacroInvocationAhead(index: Int = current): Boolean {
        val macro = tokens.getOrNull(index) ?: return false
        if (macro.type != TokenType.IDENTIFIER || macro.lexeme.firstOrNull()?.isLowerCase() != true) return false
        // A borrow sigil binds to the macro name (`res& T`, `res! T`), so look
        // past it for the operand that makes this an invocation.
        var offset = index + 1
        if (tokens.getOrNull(offset)?.type in setOf(TokenType.AMP, TokenType.BANG)) offset++
        val next = tokens.getOrNull(offset)?.type ?: return false
        return next == TokenType.L_BRACKET || next in setOf(
            TokenType.IDENTIFIER,
            TokenType.L_PAREN,
            TokenType.ELLIPSIS,
        )
    }

    /**
     * Reads the rest of a type macro's own grammar - `… with C without D`.
     *
     * The whole clause sequence becomes one application, because one arm decides
     * the whole expansion. What travels with it is the words that were written
     * and the shape of each operand - how many types, and whether they were
     * bracketed - since neither can be recovered from a flat argument list.
     */
    private fun finishNamedTypeMacro(
        name: String,
        first: List<TypeRef>,
        firstIsList: Boolean,
        modifier: String,
        form: NamedTypeMacroCall.Form,
    ): TypeRef {
        val keywords = mutableListOf<String>()
        val shapes = mutableListOf(if (firstIsList) "L${first.size}" else "S1")
        val args = first.toMutableList()
        while (check(TokenType.WITH) || check(TokenType.WITHOUT)) {
            keywords.add(advance().lexeme)
            if (match(TokenType.L_BRACKET)) {
                val items = mutableListOf<TypeRef>()
                skipNewlines()
                if (!check(TokenType.R_BRACKET)) {
                    do {
                        skipNewlines()
                        items.add(parseTypeName())
                        skipNewlines()
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_BRACKET, "Expected ']' after the operand of '${keywords.last()}'")
                shapes.add("L${items.size}")
                args.addAll(items)
            } else {
                shapes.add("S1")
                args.add(parseTypeSuffixes(parseTypeAtom()))
            }
        }
        if (keywords.isEmpty()) return NamedTypeMacroCall.create(name, first, modifier, form)
        return NamedTypeMacroCall.create(
            name,
            args,
            keywords.joinToString(",") + "|" + shapes.joinToString(","),
            form,
        )
    }

    private fun parseNamedTypeMacroInvocation(modifier: String = ""): TypeRef {
        var name = consume(TokenType.IDENTIFIER, "Expected type-macro name").lexeme
        // Fold the borrow sigil into the name so `res& T` resolves against the
        // `res&` arm, exactly as the declaration spelled it.
        when {
            match(TokenType.BANG) -> name += "!"
            match(TokenType.AMP) -> name += "&"
        }
        if (match(TokenType.L_BRACKET)) {
            val args = mutableListOf<TypeRef>()
            skipNewlines()
            if (!check(TokenType.R_BRACKET)) {
                do {
                    skipNewlines()
                    args.add(parseTypeName())
                    skipNewlines()
                } while (match(TokenType.COMMA))
            }
            consume(TokenType.R_BRACKET, "Expected ']' after '$name' type arguments")
            return finishNamedTypeMacro(name, args, firstIsList = true, modifier, NamedTypeMacroCall.Form.List)
        }
        val single = listOf(parseTypeSuffixes(parseTypeAtom()))
        return finishNamedTypeMacro(name, single, firstIsList = false, modifier, NamedTypeMacroCall.Form.Prefix)
    }

    /**
     * Parses a structured type reference.
     *
     * Supports: `[T]` as sugar for `Array<T>`, `List<T>`, `Set<T>`, `Map<K, V>` as ordinary generic names,
     * `(A, B)` tuples, `(A, B) -> R` functions, `(A)` grouping, and nullable/failable suffixes.
     */
    private fun parseTypeAtom(): TypeRef {
        return when {
            check(TokenType.BANG) -> {
                if (peekNext()?.type == TokenType.L_BRACKET) {
                    error("Set type syntax '![T]' was removed; use 'Set<T>' at line ${peek().line}")
                }
                error("Expected type name at line ${peek().line}, got '${peek().lexeme}'")
            }
            // `inline (A) -> R` - the block is substituted where it is passed
            // rather than called through, so writing one costs what writing its
            // body there would.
            check(TokenType.INLINE) &&
                (peekNext()?.type == TokenType.L_PAREN || peekNext()?.type == TokenType.L_BRACKET) -> {
                advance()
                val callable = parseTypeAtom()
                (callable as? TypeRef.Function)?.copy(isInline = true)
                    ?: error("'inline' must be followed by a callable type at line ${peek().line}")
            }
            // `async (A) -> R` / `async [Ctx](A) -> R` - the callable an `async
            // func` produces. `async` is contextual, so it only reads as a prefix
            // when a callable type actually follows.
            isAsyncCallableTypeAhead() -> {
                advance() // 'async'
                parseCallableType(CallableKind.TASK)
            }
            // `react (A) -> R` / `react async [Ctx](A) -> R` - a callable that may
            // use reactive state. Contextual in the same way `async` is: it only
            // reads as a prefix when a callable type actually follows.
            isReactCallableTypeAhead() -> {
                advance() // 'react'
                if (check(TokenType.IDENTIFIER) && peek().lexeme == "async") {
                    advance()
                    parseCallableType(CallableKind.REACT_TASK)
                } else {
                    parseCallableType(CallableKind.REACT)
                }
            }
            // `escaping (Event) -> Unit` - contextual, exactly as `async` is above:
            // it only reads as a prefix when a callable type actually follows.
            check(TokenType.IDENTIFIER) && peek().lexeme == "escaping" &&
                (peekNext()?.type == TokenType.L_PAREN || peekNext()?.type == TokenType.L_BRACKET) ->
                parseCallableType()
            check(TokenType.L_BRACKET) && isCallableTypeAhead() -> parseCallableType()
            check(TokenType.L_BRACKET) -> {
                // Bracket type sugar is not a valid type spelling. `[…]` in type
                // position never means an array or map - brackets only group types in
                // a compile-time type list (`[Type]`, `[Int, Float]`), handled by
                // `parseTypeListBinding`. Arrays are `Array<T>`, maps are `Map<K, V>`.
                val start = peek()
                advance() // consume '['
                val keyOrElem = parseTypeName()
                if (match(TokenType.COLON)) {
                    parseTypeName()
                    consume(TokenType.R_BRACKET, "Expected ']' after map type")
                    error("Map type syntax '[K: V]' is not valid; use 'Map<K, V>' at line ${start.line}")
                } else {
                    consume(TokenType.R_BRACKET, "Expected ']' after type")
                    error("Array type syntax '[$keyOrElem]' is not valid; use 'Array<$keyOrElem>' at line ${start.line}")
                }
            }
            check(TokenType.L_PAREN) -> {
                val start = advance() // consume '('
                val elements = mutableListOf<TypeRef>()
                if (!check(TokenType.R_PAREN)) {
                    do { elements.add(parseCallableParamType()) } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_PAREN, "Expected ')' in grouped or function type")
                if (match(TokenType.ARROW)) {
                    val ret = parseTypeName()
                    TypeRef.Function(elements, ret)
                } else {
                    when (elements.size) {
                        0 -> error("Empty type '()' at line ${start.line}")
                        1 -> elements[0] // grouping
                        else -> TypeRef.Tuple(elements)
                    }
                }
            }
            // `@query Comp` - a macro applied to a type. Lowercase says macro:
            // a decorator names a declaration and is capitalised, so an `@` on a
            // lowercase name in type position can only be this.
            check(TokenType.AT) && isNamedTypeMacroInvocationAhead(current + 1) -> {
                advance()
                parseNamedTypeMacroInvocation()
            }
            check(TokenType.IDENTIFIER) -> {
                if (isNamedTypeMacroInvocationAhead()) {
                    return parseNamedTypeMacroInvocation()
                }
                if (peekNext()?.type == TokenType.L_BRACKET && peek().lexeme in setOf("arr", "vec", "set", "map")) {
                    error("'${peek().lexeme}[...]' type syntax was removed; use Array<T>, List<T>, Set<T>, or Map<K, V> at line ${peek().line}")
                }
                val name = advance().lexeme
                // `P.ActualType` - the type a reflected parameter has. Read before
                // the dotted path below, which would take the `.` for a module
                // boundary and then find no module.
                if (check(TokenType.DOT) && peekNext()?.lexeme == "ActualType") {
                    advance()
                    advance()
                    return TypeRef.Named("$name.ActualType")
                }
                // A fully qualified path combines a dotted owning module with a
                // realm path: `std.traits.std::promote!`. Realm-only access remains
                // `std::promote!` and requires the module to have been imported.
                val dottedPath = mutableListOf(name)
                while (match(TokenType.DOT)) {
                    dottedPath.add(consume(TokenType.IDENTIFIER, "Expected module path segment after '.'").lexeme)
                }
                val modulePath = dottedPath.takeIf { it.size > 1 }
                    ?.dropLast(1)
                    ?.joinToString(".")
                var typeName = dottedPath.last()
                var qualifiedName = typeName
                while (match(TokenType.DOUBLE_COLON)) {
                    typeName = consume(TokenType.IDENTIFIER, "Expected type name after '::' in type path").lexeme
                    qualifiedName += "__$typeName"
                }
                val realmQualifier = qualifiedName
                    .takeIf { it != typeName }
                    ?.substringBeforeLast("__")
                    ?.replace("__", "::")
                if (check(TokenType.AT) && peekNext()?.type == TokenType.L_PAREN) {
                    error(
                        "'${typeName}@(…)' type-function call syntax was removed; a 'deepinline prop' " +
                            "is called like the type it is - '$typeName<…>' - at line ${peek().line}",
                    )
                }
                if (match(TokenType.LESS)) {
                    val a = mutableListOf<TypeRef>()
                    var variadic = false
                    do {
                        val prefixVariadic = match(TokenType.ELLIPSIS)
                        if (prefixVariadic) variadic = true
                        a.add(parseTypeArg())
                    } while (match(TokenType.COMMA))
                    // `Name<...T>` - the variadic pack expands into this type's args.
                    // The legacy suffix spelling `Name<T...>` is rejected.
                    if (check(TokenType.ELLIPSIS)) {
                        error("Variadic type arguments use the prefix form '<...T>', not '<T...>', at line ${peek().line}")
                    }
                    // Accept '>', or '>>' (which closes this and one enclosing generic)
                    when {
                        pendingGreater -> { pendingGreater = false }
                        check(TokenType.GREATER) -> { advance() }
                        check(TokenType.SHIFT_RIGHT) -> { advance(); pendingGreater = true }
                        else -> consume(TokenType.GREATER, "Expected '>' to close generic type arguments")
                    }
                    // A module-qualified path can only name a type property - a
                    // generic pack is reached through its realm, not its file - so
                    // it resolves as a call straight away. Everything else stays a
                    // named type; the evaluator turns it into a call if the name
                    // turns out to belong to a `deepinline prop`.
                    // `Name<T>(flag, other)` - a compile-time type call whose
                    // answer depends on values as well as types. Only a `(`
                    // directly here can mean this: a type is otherwise never
                    // applied to anything.
                    // Not when a `>>` closed this application: the `(` then belongs
                    // to the call the type was an argument of - `fill<Map<K, V>>(n)`
                    // - and taking it here would eat that call's arguments.
                    val values = if (check(TokenType.L_PAREN) && !pendingGreater) parseTypeCallValueArgs() else emptyList()
                    if (modulePath != null) {
                        TypeFunctionCall.create(ModuleQualifiedSymbol.create(modulePath, qualifiedName), a)
                    } else {
                        TypeRef.Named(typeName, a, variadic, realmQualifier, valueArgs = values)
                    }
                } else {
                    if (modulePath != null) {
                        error("A module-qualified path must name a type property at line ${peek().line}")
                    }
                    TypeRef.Named(typeName, qualifier = realmQualifier)
                }
            }
            else -> error("Expected type name at line ${peek().line}, got '${peek().lexeme}'")
        }
    }

    /**
     * True when a `[` in type position opens a callable type's contextual list
     * (`[Ctx](A) -> R`) rather than a compile-time type list (`[Int, Float]`).
     *
     * Both start with `[` and hold a comma-separated type list, so they are told
     * apart by what follows the matching `]`: a callable type continues with its
     * parameter list or directly with `->`.
     */
    /** True when `async` here prefixes a callable type rather than naming a value. */
    private fun isAsyncCallableTypeAhead(): Boolean {
        if (!check(TokenType.IDENTIFIER) || peek().lexeme != "async") return false
        return when (peekNext()?.type) {
            TokenType.L_PAREN -> true
            TokenType.L_BRACKET -> isCallableTypeAhead(current + 1)
            else -> false
        }
    }

    private fun isReactCallableTypeAhead(): Boolean {
        if (!check(TokenType.REACT)) return false
        var next = current + 1
        if (tokens.getOrNull(next)?.type == TokenType.IDENTIFIER && tokens[next].lexeme == "async") next++
        return when (tokens.getOrNull(next)?.type) {
            TokenType.L_PAREN -> true
            TokenType.L_BRACKET -> isCallableTypeAhead(next)
            else -> false
        }
    }

    private fun isCallableTypeAhead(from: Int = current): Boolean {
        var depth = 0
        var i = from
        while (i < tokens.size) {
            when (tokens[i].type) {
                TokenType.L_BRACKET -> depth++
                TokenType.R_BRACKET -> {
                    depth--
                    if (depth == 0) {
                        val after = tokens.getOrNull(i + 1)?.type
                        return after == TokenType.L_PAREN || after == TokenType.ARROW
                    }
                }
                TokenType.EOF -> return false
                else -> {}
            }
            i++
        }
        return false
    }

    /**
     * A callable type: `(A, B) -> R`, `[Ctx](A) -> R`, `[Ctx]() -> R`, and each
     * prefixed `async`. Types in brackets are contextual receivers, which a
     * call site may pass explicitly or take from an enclosing `with` scope.
     *
     * A parameter may be variadic (`(...Double) -> Unit`). Parentheses are always
     * required, including the empty `()` after a receiver list.
     */
    private fun parseCallableType(kind: CallableKind = CallableKind.FUNC): TypeRef {
        // `escaping (Event) -> Unit` - contextual, so it only means anything
        // directly before a callable type and nothing has to be renamed to adopt
        // it. A callable is non-escaping by default: most higher-order functions
        // call their callable and are done with it.
        val escaping = check(TokenType.IDENTIFIER) && peek().lexeme == "escaping" &&
            (peekNext()?.type == TokenType.L_PAREN || peekNext()?.type == TokenType.L_BRACKET)
        if (escaping) advance()
        val receivers = mutableListOf<TypeRef>()
        if (match(TokenType.L_BRACKET)) {
            if (!check(TokenType.R_BRACKET)) {
                do { receivers.add(parseCallableParamType()) } while (match(TokenType.COMMA))
            }
            consume(TokenType.R_BRACKET, "Expected ']' after contextual callable parameters")
        }
        if (!match(TokenType.L_PAREN)) {
            error("Expected '(' after contextual callable receivers; write '()' when there are no ordinary parameters at line ${peek().line}")
        }
        val params = mutableListOf<TypeRef>()
        if (!check(TokenType.R_PAREN)) {
            do { params.add(parseCallableParamType()) } while (match(TokenType.COMMA))
        }
        consume(TokenType.R_PAREN, "Expected ')' after callable parameters")
        consume(TokenType.ARROW, "Expected '->' after callable parameters")
        return TypeRef.Function(params, parseTypeName(), receivers, kind, isEscaping = escaping)
    }

    /** One entry of a callable type's parameter list, variadic when spelled `...T`. */
    private fun parseCallableParamType(): TypeRef {
        if (!match(TokenType.ELLIPSIS)) return parseTypeName()
        return when (val inner = parseTypeName()) {
            is TypeRef.Named -> inner.copy(variadic = true)
            else -> error("only a named type can be variadic in a callable type at line ${peek().line}")
        }
    }

    // -----------------------------------------------------------------------
    // Statements
    // -----------------------------------------------------------------------

    private fun parseStmt(): Stmt {
        // A fragment macro stands for source, and a statement is one of the
        // places it can stand for: `@compose world { … }` becomes the `with`
        // that heads the block. Expanded before anything else reads the stream,
        // because until it is, `@compose` looks like a loop label.
        spliceFragmentMacro()
        return when {
            // `var(...)` is the variant constructor (an expression statement); `var name = …` is a declaration.
            check(TokenType.VAR) && peekNext()?.type == TokenType.L_PAREN -> parseExprStmt()
            check(TokenType.VAR) || check(TokenType.VAL) -> parseVarDecl()
            check(TokenType.FIN) -> parseFinDecl()
            check(TokenType.LET) -> parseLetDecl()
            check(TokenType.LAZY) -> parseLazyDecl()
            check(TokenType.RETURN) -> parseReturn()
            check(TokenType.ASSERT) -> parseAssertStmt()
            check(TokenType.TRACE) -> parseTraceStmt()
            check(TokenType.INLINE) -> parseInline()
            check(TokenType.DEEPINLINE) -> parseDeepInlineStmt()
            check(TokenType.NOINLINE) -> parseNoInline()
            check(TokenType.SCOPE) -> parseScope()
            check(TokenType.IF) -> parseIf()
            check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.COLON -> parseLabeledStmt()
            check(TokenType.AT) && !isMacroInvokeAhead() -> error(
                "Loop labels use 'label: loop' syntax; replace '@label loop' with 'label: loop' at line ${peek().line}",
            )
            check(TokenType.WHILE) -> parseWhile()
            check(TokenType.FOR) -> parseFor()
            check(TokenType.REVERSE) && peekNext()?.type == TokenType.FOR -> parseFor(reverse = true)
            check(TokenType.LOOP) -> parseLoop()
            check(TokenType.BREAK) -> parseBreak()
            check(TokenType.CONTINUE) -> parseContinue()
            check(TokenType.WHEN) -> parseWhen()
            check(TokenType.THROW) -> parseThrow()
            check(TokenType.PANIC) -> parsePanicStmt()
            check(TokenType.ERROR) && peekNext()?.type == TokenType.DEFER -> parseFailDefer()
            check(TokenType.ERROR) -> parseFailThrow()
            check(TokenType.UNSAFE) -> parseUnsafe()
            check(TokenType.PURGE) -> parsePurge()
            check(TokenType.TRY) && peekNext()?.type == TokenType.L_BRACE -> parseTry()
            check(TokenType.DEFER) -> parseDefer()
            check(TokenType.RESCUE) -> parseRescue()
            check(TokenType.REMEMBER) -> parseReactiveDecl(ReactiveKind.REMEMBER)
            check(TokenType.RETAIN) -> parseReactiveDecl(ReactiveKind.RETAIN)
            check(TokenType.PRESERVE) -> parseReactiveDecl(ReactiveKind.PRESERVE)
            check(TokenType.EFFECT) -> parseEffect()
            check(TokenType.WITH) -> parseWithContext()
            else -> parseExprStmt()
        }
    }

    /** `label: while/for/loop …` - a loop targeted by `break:label`/`continue:label`. */
    private fun parseLabeledStmt(): Stmt {
        val label = consume(TokenType.IDENTIFIER, "Expected loop label").lexeme
        consume(TokenType.COLON, "Expected ':' after loop label '$label'")
        return when {
            check(TokenType.WHILE) -> parseWhile(label)
            check(TokenType.FOR) -> parseFor(label = label)
            check(TokenType.REVERSE) && peekNext()?.type == TokenType.FOR -> parseFor(reverse = true, label = label)
            check(TokenType.LOOP) -> parseLoop(label)
            else -> error("Expected 'for', 'while', 'reverse for', or 'loop' after label '$label:' at line ${peek().line}")
        }
    }

    /** `:label` suffix on `break`/`continue`; returns the label or null. */
    private fun parseOptionalLabel(jump: String): String? {
        if (check(TokenType.AT)) {
            error(
                "Labeled jumps use ':label'; replace '$jump @label' with " +
                    "'$jump:label' at line ${peek().line}",
            )
        }
        return if (match(TokenType.COLON)) {
            consume(TokenType.IDENTIFIER, "Expected loop label after ':'").lexeme
        } else {
            null
        }
    }

    private fun parseWhile(label: String? = null): Stmt {
        val start = peek()
        consume(TokenType.WHILE, "Expected 'while'")
        // As in parseIf: `while f() { … }` - the `{` is the loop body.
        val savedTrailing = allowTrailingLambda
        allowTrailingLambda = false
        val condition = parseExpr()
        allowTrailingLambda = savedTrailing
        consume(TokenType.L_BRACE, "Expected '{' after while condition")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after while body")
        skipNewlines()
        val elseBranch = parseLoopElse()
        consumeNewline()
        val loop = Stmt.While(condition, body, start.line, start.column, label = label)
        return if (elseBranch != null) withLoopElse(loop, elseBranch, start.line, start.column) else loop
    }

    /**
     * Parses an optional `else { body }` trailing a `while`/`for`/`loop`.
     * Returns the else body, or null if no `else` is present.
     */
    private fun parseLoopElse(): List<Stmt>? {
        if (!match(TokenType.ELSE)) return null
        consume(TokenType.L_BRACE, "Expected '{' after 'else'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after else body")
        return body
    }

    /** Counter for unique hidden flag variables generated by [withLoopElse]. */
    private var loopElseCounter = 0

    /**
     * Lowers `loop { body } else { elseBody }` to:
     * `scope { var __loop_else_n = true; loop { body (break sets flag false) }; if (__loop_else_n) { elseBody } }`.
     * Uses only existing IR nodes, so all backends support it without changes.
     */
    private fun withLoopElse(loop: Stmt, elseBody: List<Stmt>, line: Int, column: Int): Stmt {
        val flag = "__loop_else_${loopElseCounter++}"
        val flagDecl = Stmt.VarDecl(flag, TypeAnnotation.Inferred, Expr.BoolLiteral(true, line, column), line, column)
        val rewritten = when (loop) {
            is Stmt.While -> loop.copy(body = rewriteBreaksForElse(loop.body, flag))
            is Stmt.For -> loop.copy(body = rewriteBreaksForElse(loop.body, flag))
            is Stmt.Loop -> loop.copy(body = rewriteBreaksForElse(loop.body, flag))
            else -> loop
        }
        val elseCheck = Stmt.If(Expr.Identifier(flag, line, column), elseBody, null, line, column)
        return Stmt.Scope(listOf(flagDecl, rewritten, elseCheck), line, column)
    }

    /**
     * Rewrites `break` statements that belong to [flag]'s loop into
     * `scope { flag = false; break }`, so the loop-else flag reflects a real break.
     * Does NOT descend into nested loops (their breaks belong to them).
     */
    private fun rewriteBreaksForElse(stmts: List<Stmt>, flag: String): List<Stmt> =
        stmts.map { rewriteBreakForElse(it, flag) }

    private fun rewriteBreakForElse(stmt: Stmt, flag: String): Stmt = when (stmt) {
        // Only an unlabeled `break` exits THIS loop and so should trip the else flag.
        // A labeled `break:outer` targets an enclosing loop and is left untouched.
        is Stmt.Break -> if (stmt.label != null) stmt else Stmt.Scope(listOf(
            Stmt.Assignment(flag, Expr.BoolLiteral(false, stmt.line, stmt.column, stmt.length), stmt.line, stmt.column),
            Stmt.Break()
        ), stmt.line, stmt.column)
        // Nested loops own their own breaks - stop descending.
        is Stmt.While, is Stmt.For, is Stmt.Loop -> stmt
        is Stmt.If -> Stmt.If(
            stmt.condition,
            rewriteBreaksForElse(stmt.thenBranch, flag),
            stmt.elseBranch?.let { rewriteBreaksForElse(it, flag) },
            stmt.line, stmt.column, stmt.length
        )
        is Stmt.Scope -> Stmt.Scope(rewriteBreaksForElse(stmt.body, flag), stmt.line, stmt.column, stmt.length)
        is Stmt.Try -> Stmt.Try(
            rewriteBreaksForElse(stmt.body, flag), stmt.catchName,
            stmt.catchBody?.let { rewriteBreaksForElse(it, flag) }, stmt.line, stmt.column, stmt.length
        )
        is Stmt.When -> stmt.copy(
            branches = stmt.branches.map { it.copy(body = rewriteBreaksForElse(it.body, flag)) },
            elseBranch = stmt.elseBranch?.let { rewriteBreaksForElse(it, flag) }
        )
        else -> stmt
    }

    /** Counter for the hidden label a `loop … by …` gives its repetition. */
    private var loopByCounter = 0

    /**
     * Retargets unlabeled `break`s in [stmts] at [label].
     *
     * In `loop xs by 5.seconds { … break … }` the break is written against the
     * whole construct, but the body sits inside the pass over `xs` - an
     * unlabeled break would end that pass and let the repetition continue
     * forever. Labelling it makes the written meaning the compiled one. Nested
     * loops own their own breaks, so the walk stops at them.
     */
    private fun retargetBreaks(stmts: List<Stmt>, label: String): List<Stmt> =
        stmts.map { retargetBreak(it, label) }

    private fun retargetBreak(stmt: Stmt, label: String): Stmt = when (stmt) {
        is Stmt.Break -> if (stmt.label != null) stmt else Stmt.Break(label, stmt.line, stmt.column, stmt.length)
        is Stmt.While, is Stmt.For, is Stmt.Loop -> stmt
        is Stmt.If -> Stmt.If(
            stmt.condition,
            retargetBreaks(stmt.thenBranch, label),
            stmt.elseBranch?.let { retargetBreaks(it, label) },
            stmt.line, stmt.column, stmt.length,
        )
        is Stmt.Scope -> Stmt.Scope(retargetBreaks(stmt.body, label), stmt.line, stmt.column, stmt.length)
        is Stmt.Try -> Stmt.Try(
            retargetBreaks(stmt.body, label), stmt.catchName,
            stmt.catchBody?.let { retargetBreaks(it, label) }, stmt.line, stmt.column, stmt.length,
        )
        is Stmt.When -> stmt.copy(
            branches = stmt.branches.map { it.copy(body = retargetBreaks(it.body, label)) },
            elseBranch = stmt.elseBranch?.let { retargetBreaks(it, label) },
        )
        else -> stmt
    }

    private fun parseFor(reverse: Boolean = false, label: String? = null): Stmt {
        val start = peek()
        if (reverse) consume(TokenType.REVERSE, "Expected 'reverse'")
        consume(TokenType.FOR, "Expected 'for'")
        val name = consume(TokenType.IDENTIFIER, "Expected loop variable name").lexeme
        consume(TokenType.IN, "Expected 'in' after loop variable")
        // The step is part of the header too: in `for x in 0..<6 by 2 { … }` the
        // `{` closes the header, so it must not be read as a trailing lambda on
        // the step expression either.
        val (iterable, step) = withoutTrailingLambda {
            val it = parseExpr()
            // Optional step, trailing the iterable it applies to: `for x in 0..<6 by 2`.
            it to (if (match(TokenType.BY)) parseExpr() else null)
        }
        consume(TokenType.L_BRACE, "Expected '{' after for iterable")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after for body")
        skipNewlines()
        val elseBranch = parseLoopElse()
        consumeNewline()
        val loop = Stmt.For(name, iterable, body, start.line, start.column, step = step, reverse = reverse, label = label)
        return if (elseBranch != null) withLoopElse(loop, elseBranch, start.line, start.column) else loop
    }

    private fun parseLoop(label: String? = null): Stmt {
        val start = peek()
        consume(TokenType.LOOP, "Expected 'loop'")
        // `loop { }` repeats, and `loop by <seconds> { }` repeats with a wait.
        // It never walks anything: iterating is `for row in rows`, which says
        // what it is doing, and leaves `loop` to mean one thing.
        val savedTrailing = allowTrailingLambda
        allowTrailingLambda = false
        if (!check(TokenType.L_BRACE) && !check(TokenType.BY)) {
            error(
                "'loop <iterable>' was removed at line ${start.line}: write " +
                    "'for row in <iterable> { … }' to walk something, or 'loop { … }' to repeat",
            )
        }
        val iterable: Expr? = null
        // `loop by <seconds> { … }` - repeat, waiting between passes.
        val everySeconds: Expr? = if (match(TokenType.BY)) parseExpr() else null
        val skipIteratorReset = false
        allowTrailingLambda = savedTrailing
        consume(TokenType.L_BRACE, "Expected '{' after 'loop'")
        skipNewlines()
        val body = parseBlock().toMutableList()
        consume(TokenType.R_BRACE, "Expected '}' after loop body")
        // do-while: `loop { body } while cond` - runs the body first, then repeats
        // while [cond] holds. Desugared to `loop { body; if (!cond) { break } }`.
        if (iterable == null && match(TokenType.WHILE)) {
            val cond = parseExpr()
            val exit = Stmt.If(
                Expr.Unary(TokenType.BANG, cond, start.line, start.column, start.lexeme.length),
                listOf(Stmt.Break()),
                null,
                start.line, start.column
            )
            body.add(exit)
        }
        skipNewlines()
        val elseBranch = parseLoopElse()
        consumeNewline()
        // `loop iterable { body }` desugars to `scope { iterable.reset(); while (iterable.hasNext()) { body } }`.
        // `loop iterable continue { body }` skips the generated reset and resumes the iterator in place.
        // Desugared at parse time so reset()/hasNext() go through the normal (user-type) method resolution.
        if (iterable != null) {
            val reset = Stmt.ExprStmt(
                Expr.MethodCall(iterable, "reset", emptyList(), start.line, start.column, start.lexeme.length),
                start.line, start.column
            )
            val cond = Expr.MethodCall(iterable, "hasNext", emptyList(), start.line, start.column, start.lexeme.length)
            val lowered = if (skipIteratorReset) {
                listOf(Stmt.While(cond, body, start.line, start.column, label = label))
            } else {
                listOf(reset, Stmt.While(cond, body, start.line, start.column, label = label))
            }
            // `loop xs by s { … }` is that pass, repeated, with a wait between:
            // the iterator is reset each time, so every pass reads the subject
            // again instead of walking a list captured once.
            if (everySeconds != null) {
                val repeatLabel = label ?: "__loop_by_${loopByCounter++}"
                val millis = Expr.Binary(
                    everySeconds,
                    TokenType.STAR,
                    Expr.DoubleLiteral(1000.0, start.line, start.column),
                    start.line, start.column,
                )
                val wait = Stmt.ExprStmt(
                    Expr.Call("__delay", listOf(millis), start.line, start.column, start.lexeme.length),
                    start.line, start.column,
                )
                // The `while` here is the pass this desugaring created, not a
                // loop the author wrote, so its body's breaks belong to the
                // repetition - reach inside it rather than stopping at it.
                val pass = lowered.map { stmt ->
                    if (stmt is Stmt.While) stmt.copy(body = retargetBreaks(stmt.body, repeatLabel))
                    else retargetBreak(stmt, repeatLabel)
                }
                return Stmt.Loop(
                    pass + wait,
                    start.line, start.column,
                    label = repeatLabel,
                    everySeconds = everySeconds,
                )
            }
            return Stmt.Scope(lowered, start.line, start.column)
        }
        val loop = Stmt.Loop(body, start.line, start.column, label = label)
        return if (elseBranch != null) withLoopElse(loop, elseBranch, start.line, start.column) else loop
    }

    private fun parseBreak(): Stmt {
        val start = consume(TokenType.BREAK, "Expected 'break'")
        val label = parseOptionalLabel("break")
        consumeNewline()
        return Stmt.Break(label, start.line, start.column, start.lexeme.length)
    }

    private fun parseContinue(): Stmt {
        val start = consume(TokenType.CONTINUE, "Expected 'continue'")
        val label = parseOptionalLabel("continue")
        consumeNewline()
        return Stmt.Continue(label, start.line, start.column, start.lexeme.length)
    }

    private fun parseThrow(): Stmt.Throw {
        val start = peek()
        consume(TokenType.THROW, "Expected 'throw'")
        val value = parseExpr()
        consumeNewline()
        return Stmt.Throw(value, start.line, start.column)
    }

    /** `panic "msg"` (runtime abort) or `inline panic "msg"` (compile-time abort when reached). */
    private fun parsePanicStmt(inlinePanic: Boolean = false): Stmt.Panic {
        val start = peek()
        if (inlinePanic) consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.PANIC, "Expected 'panic'")
        val message = parseExpr()
        consumeNewline()
        return Stmt.Panic(message, inlinePanic, start.line, start.column)
    }

    /** `fail <expr>` - sugar for `throw <expr>` (raise an error from a `T!E` function). */
    private fun parseFailThrow(): Stmt.Throw {
        val start = peek()
        consume(TokenType.ERROR, "Expected 'fail'")
        val value = parseExpr()
        consumeNewline()
        return Stmt.Throw(value, start.line, start.column)
    }

    /**
     * `mixin "<string>"` - converts a string into code at compile time. The string
     * is parsed as Azora source and spliced in place (wrapped in an inline block
     * that CTCE flattens). Constant strings are expanded here; `$var` interpolation
     * requires a comptime context (`inline for … with index`) and is handled by the
     * variadic-pack machinery instead.
     */
    private fun parseInlineSplice(): Stmt {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        val template = parsePrimary()
        val rendered = when (template) {
            is Expr.StringLiteral -> template.value
            is Expr.StringTemplate -> {
                val sb = StringBuilder()
                for (part in template.parts) {
                    when (part) {
                        is Expr.StringTemplatePart.Literal -> sb.append(part.text)
                        is Expr.StringTemplatePart.Expr -> error(
                            "inline splice '\$${(part.expr as? Expr.Identifier)?.name ?: ""}' interpolation is only valid inside an `inline for … with index` comptime context at line ${start.line}"
                        )
                    }
                }
                sb.toString()
            }
            else -> error("Expected string after 'inline' at line ${start.line}")
        }
        val wrapper = Parser(Lexer("func __mixin() {\n$rendered\n}").tokenize(), internalSource = true).parse()
        val body = (wrapper.items.firstOrNull() as? TopLevel.Func)?.decl?.body
            ?: error("inline splice did not produce any statements at line ${start.line}")
        consumeNewline()
        return Stmt.InlineBlock(body, start.line, start.column)
    }

    /** `unsafe { body }` - an explicit boundary for unchecked operations. */
    private fun parseUnsafe(): Stmt.Scope {
        val start = peek()
        consume(TokenType.UNSAFE, "Expected 'unsafe'")
        consume(TokenType.L_BRACE, "Expected '{' after 'unsafe'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after unsafe body")
        consumeNewline()
        return Stmt.Scope(body, start.line, start.column, unsafe = true)
    }

    /** `purge <expr>` - release a heap value; calls `__drop(value)` which triggers dtor if present. */
    private fun parsePurge(): Stmt {
        val start = peek()
        consume(TokenType.PURGE, "Expected 'purge'")
        val value = parseExpr()
        consumeNewline()
        return Stmt.ExprStmt(Expr.Call("__purge", listOf(value), start.line, start.column, start.lexeme.length), start.line, start.column)
    }

    /** `yield <expr>` - emit a value from a `flow` generator. */
    private fun parseDefer(): Stmt.Defer {
        val start = peek()
        consume(TokenType.DEFER, "Expected 'defer'")
        consume(TokenType.L_BRACE, "Expected '{' after 'defer'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.Defer(body, start.line, start.column)
    }

    /** `fail defer { body }` - a defer that runs only when the function exits via an error. */
    private fun parseFailDefer(): Stmt.Defer {
        val start = peek()
        consume(TokenType.ERROR, "Expected 'fail'")
        consume(TokenType.DEFER, "Expected 'defer' after 'fail'")
        consume(TokenType.L_BRACE, "Expected '{' after 'defer'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.Defer(body, start.line, start.column, onFail = true)
    }

    /** `rescue { body }` - catch-and-suppress: runs the handler on error, then swallows it. */
    private fun parseRescue(): Stmt.Defer {
        val start = peek()
        consume(TokenType.RESCUE, "Expected 'rescue'")
        consume(TokenType.L_BRACE, "Expected '{' after 'rescue'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.Defer(body, start.line, start.column, onFail = true, suppress = true)
    }

    /** The reactive lifetime keyword at [index], if there is one. */
    private fun reactiveLifetimeAt(index: Int): ReactiveKind? = when (tokens.getOrNull(index)?.type) {
        TokenType.REMEMBER -> ReactiveKind.REMEMBER
        TokenType.RETAIN -> ReactiveKind.RETAIN
        TokenType.PRESERVE -> ReactiveKind.PRESERVE
        else -> null
    }

    /**
     * `remember|retain|preserve <var|val|let|fin> name: T = init`.
     *
     * The lifetime says how long the value outlives its owner; the binding form
     * that follows says the same things it says anywhere else - whether the name
     * rebinds and whether the value can be written. Both are needed, so both are
     * written: a reactive binding is an ordinary binding that survives longer.
     */
    private fun parseReactiveDecl(kind: ReactiveKind): Stmt.RemDecl {
        val start = peek()
        val expected = when (kind) {
            ReactiveKind.REMEMBER -> TokenType.REMEMBER
            ReactiveKind.RETAIN -> TokenType.RETAIN
            ReactiveKind.PRESERVE -> TokenType.PRESERVE
        }
        consume(expected, "Expected '${kind.spelling}'")
        if (check(TokenType.THREADLOCAL)) {
            error(
                "'${kind.spelling}' cannot be combined with 'threadlocal' at line ${peek().line} - " +
                    "a reactive lifetime and thread-local storage are different answers " +
                    "to where a value lives"
            )
        }
        val binding = when {
            match(TokenType.VAR) -> BindingKind.VAR
            match(TokenType.VAL) -> BindingKind.VAL
            match(TokenType.LET) -> BindingKind.LET
            match(TokenType.FIN) -> BindingKind.FIN
            else -> error(
                "'${kind.spelling}' needs a binding form at line ${peek().line} - " +
                    "write '${kind.spelling} var', '${kind.spelling} val', " +
                    "'${kind.spelling} let' or '${kind.spelling} fin'"
            )
        }
        val name = consume(TokenType.IDENTIFIER, "Expected reactive variable name").lexeme
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in reactive declaration")
        val init = parseExpr()
        consumeNewline()
        return Stmt.RemDecl(name, type, init, start.line, start.column, kind = kind, binding = binding)
    }

    /** Reactive effect with automatic, explicit, or disposal-only execution. */
    private fun parseEffect(): Stmt.Effect {
        val start = peek()
        consume(TokenType.EFFECT, "Expected 'effect'")
        val deferred = match(TokenType.DEFER)
        var bare: Expr? = null
        val dependencies = when {
            deferred || check(TokenType.L_BRACE) -> null
            match(TokenType.L_BRACKET) -> {
                val values = mutableListOf<Expr>()
                if (!check(TokenType.R_BRACKET)) {
                    do { values.add(parseExpr()) } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_BRACKET, "Expected ']' after effect dependencies")
                values
            }
            else -> {
                val savedTrailing = allowTrailingLambda
                allowTrailingLambda = false
                val dependency = parseExpr()
                allowTrailingLambda = savedTrailing
                bare = dependency
                listOf(dependency)
            }
        }
        // A bare name says only *when to reconsider* - `effect name { … }` is the
        // dependency form, and stays exactly what it was. Any other unbracketed
        // expression is a question with an answer, so it is a condition and the
        // body runs on its rising edge. One spelling cannot be both: `effect
        // ready { }` would otherwise have to mean "whenever ready changes" and
        // "once ready becomes true" at the same time.
        val condition = bare?.takeIf { it !is Expr.Identifier }
        consume(TokenType.L_BRACE, "Expected '{' after 'effect'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.Effect(
            body,
            start.line,
            start.column,
            dependencies = dependencies,
            deferred = deferred,
            condition = condition,
        )
    }

    /** Contextual receiver scope for first-class callables. */
    private fun parseWithContext(): Stmt.WithContext {
        val start = consume(TokenType.WITH, "Expected 'with'")
        val values = mutableListOf<Expr>()
        if (match(TokenType.L_BRACKET)) {
            if (!check(TokenType.R_BRACKET)) {
                do { values.add(parseExpr()) } while (match(TokenType.COMMA))
            }
            consume(TokenType.R_BRACKET, "Expected ']' after contextual values")
        } else {
            val savedTrailing = allowTrailingLambda
            allowTrailingLambda = false
            values.add(parseExpr())
            allowTrailingLambda = savedTrailing
        }
        consume(TokenType.L_BRACE, "Expected '{' after contextual values")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after with body")
        consumeNewline()
        return Stmt.WithContext(values, body, start.line, start.column)
    }

    /**
     * `threadlocal var x: T = init` / `threadlocal fin y: T = init` - thread-local storage.
     * Each coroutine (main + each task/launch/flow) gets its own independent copy.
     * Desugars to a regular top-level VarDecl/FinDecl with a `__tl__` name prefix.
     */
    private fun parseThreadLocal(visibility: Visibility = Visibility.PUBLIC): TopLevel {
        val start = consume(TokenType.THREADLOCAL, "Expected 'threadlocal'")
        // `threadlocal` says the value is per-thread; a reactive lifetime says how
        // long it outlives its owner. Both answer "where does this live", and a
        // binding that claims both says nothing coherent about either.
        reactiveLifetimeAt(current)?.let {
            error(
                "'threadlocal' cannot be combined with '${it.spelling}' at line ${start.line} - " +
                    "a reactive lifetime and thread-local storage are different answers " +
                    "to where a value lives"
            )
        }
        return when {
            check(TokenType.VAR) || check(TokenType.VAL) -> {
                val valueMutable = advance().type == TokenType.VAR
                val name = consume(TokenType.IDENTIFIER, "Expected variable name").lexeme
                val type = if (match(TokenType.COLON)) parseTypeName() else null
                consume(TokenType.EQUAL, "Expected '='")
                val init = parseExpr()
                consumeNewline()
                TopLevel.VarDecl(name, type, init, start.line, start.column, threadlocal = true, visibility = visibility, valueMutable = valueMutable)
            }
            check(TokenType.FIN) -> {
                advance()
                val name = consume(TokenType.IDENTIFIER, "Expected variable name").lexeme
                val type = if (match(TokenType.COLON)) parseTypeName() else null
                consume(TokenType.EQUAL, "Expected '='")
                val init = parseExpr()
                consumeNewline()
                TopLevel.FinDecl(name, type, init, start.line, start.column, threadlocal = true, visibility = visibility)
            }
            else -> error("Expected 'var' or 'fin' after 'threadlocal' at line ${peek().line}")
        }
    }

    /** `guard condition else { body }` - sugar for `if !condition { body }`. */
    /**
     * `import path` - import a module/realm path.
     * `import path.*` - import all items below a path.
     * `import path.{child, other}` - import grouped child paths.
     * `import path.item` - import a dotted path; semantic passes decide whether the
     * path names a module or a selected item. `::` is only for realm access
     * expressions, never import syntax.
     */
    private fun parseUse(exported: Boolean = false, condition: Expr? = null): TopLevel {
        val start = consume(TokenType.IMPORT, "Expected 'import'")
        val imports = mutableListOf<Pair<String, String?>>() // (realmName, itemName or null for all)
        do {
            val base = StringBuilder(consumeIdentifierLike("Expected realm name after 'use'"))
            var completed = false
            usePath@ while (match(TokenType.DOT)) {
                when {
                    match(TokenType.STAR) -> {
                        // `import path.*` - wildcard. Marked with the "*" selector so
                        // it stays distinct from a plain `import path` (which must name
                        // an actual module file, not just a namespace/folder).
                        imports.add(base.toString() to "*")
                        completed = true
                        break@usePath
                    }
                    match(TokenType.L_BRACE) -> {
                        val basePath = base.toString()
                        parseImportGroup { child -> imports.add("$basePath.$child" to null) }
                        completed = true
                        break@usePath
                    }
                    else -> base.append('.').append(consumeIdentifierLike("Expected name after '.'"))
                }
            }
            if (completed) continue

            val realmName = base.toString()
            if (match(TokenType.DOUBLE_COLON)) {
                error("Use dotted paths such as 'import module.item' or 'import module.{a, b}'; '::' is for realm access expressions")
            } else {
                addDottedUsePath(realmName, imports)
            }
        } while (match(TokenType.COMMA) && check(TokenType.IDENTIFIER))
        consumeNewline()
        return TopLevel.UseImport(imports, start.line, start.column, exported = exported, condition = condition)
    }

    private fun addDottedUsePath(path: String, imports: MutableList<Pair<String, String?>>) {
        imports.add(path to null)
    }

    private fun parseImportGroup(add: (String) -> Unit) {
        if (check(TokenType.R_BRACE)) {
            error("Expected at least one name inside import group at line ${peek().line}")
        }
        do {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            add(consume(TokenType.IDENTIFIER, "Expected name in import group").lexeme)
            skipNewlines()
        } while (match(TokenType.COMMA))
        consume(TokenType.R_BRACE, "Expected '}' after import group")
    }

    /**
     * Binds `Self` to [typeName] throughout an impl block's members.
     *
     * Inside `impl Model`, `Self` means `Model` - the compiler supplies the
     * binding so nobody has to write `typealias Self = Model`, and it holds in
     * every impl for the type, including `impl Area for Model`.
     */
    private fun bindSelf(methods: List<FuncDecl>, typeName: String): List<FuncDecl> {
        if (methods.none { declMentionsSelf(it) }) return methods
        val target = TypeRef.Named(typeName)
        return methods.map { m ->
            m.copy(
                params = m.params.map { it.copy(type = selfTo(it.type, target)) },
                returnType = when (val rt = m.returnType) {
                    is TypeAnnotation.Explicit -> TypeAnnotation.Explicit(selfTo(rt.ref, target))
                    else -> rt
                },
                extensionReceiver = m.extensionReceiver?.let { it.copy(type = selfTo(it.type, target)) },
            )
        }
    }

    private fun declMentionsSelf(m: FuncDecl): Boolean =
        m.params.any { mentionsSelf(it.type) } ||
            (m.returnType as? TypeAnnotation.Explicit)?.let { mentionsSelf(it.ref) } == true ||
            m.extensionReceiver?.let { mentionsSelf(it.type) } == true

    // `Self` can appear wherever a type can, so both of these walk every composite
    // variant. Missing one does not fail loudly - it leaves a bare `Self` behind for
    // name resolution to report as an undefined type, from a line that never wrote it.
    private fun mentionsSelf(t: TypeRef): Boolean = when (t) {
        is TypeRef.Named -> t.name == "Self" || t.args.any { mentionsSelf(it) }
        is TypeRef.Array -> mentionsSelf(t.element)
        is TypeRef.Map -> mentionsSelf(t.key) || mentionsSelf(t.value)
        is TypeRef.Set -> mentionsSelf(t.element)
        is TypeRef.Tuple -> t.elements.any { mentionsSelf(it) }
        is TypeRef.Nullable -> mentionsSelf(t.inner)
        is TypeRef.Failable -> mentionsSelf(t.ok)
        is TypeRef.Pointer -> mentionsSelf(t.inner)
        is TypeRef.Reference -> mentionsSelf(t.inner)
        is TypeRef.Function ->
            t.params.any { mentionsSelf(it) } || t.receivers.any { mentionsSelf(it) } ||
                mentionsSelf(t.ret)
        else -> false
    }

    private fun selfTo(t: TypeRef, target: TypeRef.Named): TypeRef = when (t) {
        is TypeRef.Named -> if (t.name == "Self" && t.args.isEmpty()) target
            else t.copy(args = t.args.map { selfTo(it, target) })
        is TypeRef.Array -> t.copy(element = selfTo(t.element, target))
        is TypeRef.Map -> t.copy(key = selfTo(t.key, target), value = selfTo(t.value, target))
        is TypeRef.Set -> t.copy(element = selfTo(t.element, target))
        is TypeRef.Tuple -> t.copy(elements = t.elements.map { selfTo(it, target) })
        is TypeRef.Nullable -> t.copy(inner = selfTo(t.inner, target))
        is TypeRef.Failable -> t.copy(ok = selfTo(t.ok, target))
        is TypeRef.Pointer -> t.copy(inner = selfTo(t.inner, target))
        is TypeRef.Reference -> t.copy(inner = selfTo(t.inner, target))
        is TypeRef.Function -> t.copy(
            params = t.params.map { selfTo(it, target) },
            receivers = t.receivers.map { selfTo(it, target) },
            ret = selfTo(t.ret, target),
        )
        else -> t
    }

    /** A `prop` receiver: its borrow, and the type it extends (null inside an `impl`). */
    private data class PropReceiver(val name: String, val type: TypeRef?, val modifier: ParamModifier)

    /**
     * Parses a bracketed receiver: `[self: Model&]` inside `impl Model`, or
     * `[self: Int&]` on an extension. `Self` is available inside any `impl`.
     *
     * The type is always written. A receiver states what it receives and how it
     * borrows it, in one place, whether the member extends a type or sits inside
     * its `impl` - so every member reads the same way.
     */
    private fun parsePropReceiver(): PropReceiver {
        // A member inside an `impl` receives that type; writing `[self: Self&]`
        // says only what the `impl` already said. Omitting the bracket means the
        // obvious receiver - a shared borrow of `Self` - so `prop isValid: Bool
        // = self.id >= 0` reads as the fact it states. An extension still writes
        // its receiver, because there the type is not implied by anything.
        if (!check(TokenType.L_BRACKET)) {
            return PropReceiver("self", TypeRef.Named("Self"), ParamModifier.SHARED)
        }
        consume(TokenType.L_BRACKET, "Expected '[' - a member declares its receiver, as in '[self: Self&]'")
        val name = consumeIdentifierLike("Expected receiver name in '[…]'")
        if (!check(TokenType.COLON)) {
            error(
                "a receiver must name its type: write '[$name: Self&]' inside an impl, " +
                    "or '[$name: Type&]' on an extension, at line ${peek().line}",
            )
        }
        advance() // ':'
        val (type, modifier) = parseReceiverTypeAndModifier()
        consume(TokenType.R_BRACKET, "Expected ']' after receiver")
        return PropReceiver(name, type, modifier)
    }

    /**
     * The type of a receiver together with the borrow its sigil asks for.
     *
     * `Self&` reads, `Self!` mutates; a bare `Self` still borrows for reading, so
     * a receiver never silently takes ownership of what it was called on.
     */
    private fun parseReceiverTypeAndModifier(): Pair<TypeRef, ParamModifier> {
        val parsed = parseTypeName()
        return if (parsed is TypeRef.Reference) {
            parsed.inner to parsed.kind.paramModifier
        } else {
            parsed to ParamModifier.SHARED
        }
    }

    private fun parsePropAsFin(
        annotations: List<Annotation>,
        visibility: Visibility,
        isReactive: Boolean = false,
        isTask: Boolean = false,
    ): TopLevel {
        val start = peek()
        consume(TokenType.PROP, "Expected 'prop'")
        val name = consume(TokenType.IDENTIFIER, "Expected name after 'prop'").lexeme
        // The receiver is optional here: a top-level `prop name: T = …` with nothing
        // to extend is a constant, which is what a reparsed `impl Type:: { … }` or
        // `impl Spec:: for Type { … }` member is.
        val receiver = if (check(TokenType.L_BRACKET)) parsePropReceiver() else PropReceiver("self", null, ParamModifier.SHARED)
        val type = if (match(TokenType.COLON)) parseTypeName() else null
        // `prop name: T = expr` and `prop name: T { return expr }` are the same
        // declaration written two ways. The block form exists because a value
        // worth computing is often worth a line or two, and having to spell it
        // as a function only to read it without parentheses is the boilerplate
        // `prop` exists to remove.
        val init = if (check(TokenType.L_BRACE)) {
            advance()
            skipNewlines()
            val body = mutableListOf<Stmt>()
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                body.add(parseStmt())
                skipNewlines()
            }
            consume(TokenType.R_BRACE, "Expected '}' to close prop body")
            val returned = body.filterIsInstance<Stmt.Return>()
            if (body.size != 1 || returned.size != 1) {
                error(
                    "a 'prop' body is a single expression: write 'return <expr>' as its only " +
                        "statement, or declare a func at line ${start.line}",
                )
            }
            returned[0].value ?: error("'prop' body must return a value at line ${start.line}")
        } else {
            consume(TokenType.EQUAL, "Expected '=' or '{' in prop declaration")
            parseExpr()
        }
        consumeNewline()

        // `prop name[self: Type&]: T = …` - an extension property on Type, read as
        // `value.name`. Without a receiver type there is no type to extend, so the
        // declaration is a realm-mangled constant instead (the `impl … as realm`
        // bodies that reparse their members as top-level items rely on this).
        val receiverType = receiver.type
            ?: return TopLevel.FinDecl(name, type, init, start.line, start.column, annotations, visibility = visibility)

        val decl = FuncDecl(
            name,
            emptyList(),
            type?.let { TypeAnnotation.Explicit(it) } ?: TypeAnnotation.Inferred,
            listOf(Stmt.Return(init, init.line, init.column)),
            false,
            emptyList(),
            start.line,
            start.column,
            annotations = annotations,
            visibility = visibility,
            receiverModifier = receiver.modifier,
            receiverName = receiver.name,
            memberCallStyle = MemberCallStyle.PROPERTY,
            extensionReceiver = Param(receiver.name, receiverType),
            isReactive = isReactive,
            isTask = isTask,
        )
        return funcOrExtension(decl)
    }

    private fun parseTypeAlias(annotations: List<Annotation> = emptyList()): TopLevel.TypeAlias {
        val start = peek()
        consume(TokenType.TYPEALIAS, "Expected 'typealias'")
        val name = consume(TokenType.IDENTIFIER, "Expected type alias name").lexeme
        // Optional type parameters: `typealias Array<T> = …`
        val tp = parseTypeParams()
        consume(TokenType.EQUAL, "Expected '=' in typealias")
        val type = parseTypeName()
        consumeNewline()
        return TopLevel.TypeAlias(name, type, start.line, start.column, annotations, typeParams = tp.names)
    }

    /**
     * True when a compile-time type property begins here:
     * `[@Deco…] [visibility] deepinline prop Name<…>: Type … { … }`.
     *
     * The scan looks past decorators and a visibility modifier because those are
     * parsed by [parseAnnotations]/[parseVisibility] once the form is known, and
     * the caller has to decide before consuming either.
     */
    private fun isTypePropAhead(): Boolean {
        var i = indexAfterAnnotations(current)
        while (tokens.getOrNull(i)?.type in setOf(TokenType.EXPOSE, TokenType.PROTECT, TokenType.CONFINE)) i++
        if (tokens.getOrNull(i)?.type != TokenType.DEEPINLINE) return false
        if (tokens.getOrNull(i + 1)?.type == TokenType.PROP) return true
        // A type function that takes values is a `func`, because that is what
        // taking arguments makes it. Most `deepinline func`s are ordinary
        // compile-time functions, so what separates them is the only thing that
        // can: the result. Reached by stepping over the name, the type
        // parameters and the value parameters.
        if (tokens.getOrNull(i + 1)?.type != TokenType.FUNC) return false
        var j = i + 2
        if (tokens.getOrNull(j)?.type != TokenType.IDENTIFIER) return false
        j++
        if (tokens.getOrNull(j)?.type == TokenType.LESS) j = indexAfterBalanced(j, TokenType.LESS, TokenType.GREATER)
        if (tokens.getOrNull(j)?.type == TokenType.L_PAREN) j = indexAfterBalanced(j, TokenType.L_PAREN, TokenType.R_PAREN)
        if (tokens.getOrNull(j)?.type != TokenType.COLON) return false
        return tokens.getOrNull(j + 1)?.lexeme == "Type"
    }

    /** Index of the first token after the decorator list starting at [from]. */
    private fun indexAfterAnnotations(from: Int): Int {
        var i = from
        while (tokens.getOrNull(i)?.type == TokenType.AT) {
            i++ // '@'
            if (tokens.getOrNull(i)?.type == TokenType.L_BRACKET) {
                i = indexAfterBalanced(i, TokenType.L_BRACKET, TokenType.R_BRACKET)
                continue
            }
            if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return i
            i++ // decorator name
            if (tokens.getOrNull(i)?.type == TokenType.L_PAREN) {
                i = indexAfterBalanced(i, TokenType.L_PAREN, TokenType.R_PAREN)
            }
            while (tokens.getOrNull(i)?.type == TokenType.NEWLINE) i++
        }
        return i
    }

    /** Index just past the bracket pair that opens at [from]. */
    private fun indexAfterBalanced(from: Int, open: TokenType, close: TokenType): Int {
        var depth = 0
        var i = from
        while (i < tokens.size) {
            val t = tokens[i].type
            if (t == open) depth++
            if (t == close) { depth--; if (depth == 0) return i + 1 }
            i++
        }
        return i
    }

    /**
     * Parses a compile-time type property:
     *
     * ```azora
     * deepinline prop promote<...T>: Type where T.length >= 2 && T is Number {
     *     var result: Type = T.0
     *     for candidate: Type in T[1...] {
     *         if candidate::rank > result::rank { result = candidate }
     *     }
     *     return result
     * }
     * ```
     *
     * It is a `deepinline` declaration whose result is a *type*, so it is
     * evaluated entirely at compile time and used in type position as
     * `promote<T, U>` - the same spelling as a generic type, because to a caller
     * that is exactly what it is.
     */
    private fun parseTypeProp(realmPrefix: String = "") {
        val annotations = parseAnnotations()
        parseVisibility()
        val start = consume(TokenType.DEEPINLINE, "Expected 'deepinline'")
        val isFunc = check(TokenType.FUNC)
        if (isFunc) advance() else consume(TokenType.PROP, "Expected 'prop' or 'func' after 'deepinline'")
        val kind = if (isFunc) "func" else "prop"
        val nameToken = consume(TokenType.IDENTIFIER, "Expected type-property name")
        val localName = nameToken.lexeme
        val name = if (realmPrefix.isEmpty()) localName else "${realmPrefix}__$localName"

        val params = parseTypePropParams(localName)
        // `(hasDefault: Bool, isNullable: Bool)` - values the computed type
        // depends on, alongside the types it depends on.
        val valueParams = if (check(TokenType.L_PAREN)) {
            if (!isFunc) {
                error(
                    "'deepinline prop $localName' takes no value parameters at line ${peek().line}: " +
                        "a type computation that takes arguments is a 'deepinline func'",
                )
            }
            advance()
            val parsed = if (check(TokenType.R_PAREN)) emptyList() else parseParams()
            consume(TokenType.R_PAREN, "Expected ')' after the value parameters of 'deepinline func $localName'")
            parsed
        } else {
            emptyList()
        }
        consume(TokenType.COLON, "Expected ': Type' after 'deepinline $kind $localName'")
        val resultToken = consume(TokenType.IDENTIFIER, "Expected 'Type' as the result of 'deepinline $kind $localName'")
        if (resultToken.lexeme != "Type") {
            error("'deepinline $kind $localName' must declare ': Type' - it returns a type, not a value, at line ${resultToken.line}")
        }

        // One `where` grammar for every declaration: parsed as an expression and
        // handed to ConstraintEvaluator, so type properties enforce constraints
        // through the same implementation as packs and variadic functions.
        val tfWhereClause = parseWhereClause()
        val minimum = variadicMinLengthOf(tfWhereClause)

        consume(TokenType.L_BRACE, "Expected '{' before the body of 'deepinline $kind $localName'")
        skipNewlines()
        val body = parseTypeFunctionBlock()
        consume(TokenType.R_BRACE, "Expected '}' after the body of 'deepinline $kind $localName'")
        consumeNewline()
        if (body.none { it is TypeFunctionStmt.Return }) {
            error("'deepinline $kind $localName' must return a type at line ${start.line}")
        }
        val duplicate = typeFunctions.any {
            it.name == name && it.params.map(TypeFunctionParam::variadic) == params.map(TypeFunctionParam::variadic) &&
                (it.variadicParam != null || it.params.size == params.size)
        }
        if (duplicate) error("'deepinline $kind $localName' already has this overload at line ${start.line}")
        typeFunctions.add(
            TypeFunctionDecl(
                name, params, valueParams, body, minimum, tfWhereClause, start.line, start.column, annotations,
            ),
        )
    }

    /** `<T, U>` / `<...T>` - the type parameters of a `deepinline prop`. */
    private fun parseTypePropParams(owner: String): List<TypeFunctionParam> {
        val params = mutableListOf<TypeFunctionParam>()
        if (!match(TokenType.LESS)) {
            error("'deepinline prop $owner' must declare type parameters, e.g. '<...T>', at line ${peek().line}")
        }
        do {
            val variadic = match(TokenType.ELLIPSIS)
            val param = consume(TokenType.IDENTIFIER, "Expected a type-parameter name").lexeme
            if (check(TokenType.ELLIPSIS)) {
                error("Variadic type parameters use the prefix form '<...$param>', not '<$param...>', at line ${peek().line}")
            }
            params.add(TypeFunctionParam(param, variadic))
        } while (match(TokenType.COMMA))
        when {
            pendingGreater -> { pendingGreater = false }
            check(TokenType.SHIFT_RIGHT) -> { advance(); pendingGreater = true }
            else -> consume(TokenType.GREATER, "Expected '>' to close the type parameters of '$owner'")
        }
        val duplicateParam = params.groupingBy { it.name }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicateParam != null) {
            error("Duplicate type parameter '${duplicateParam.key}' on 'deepinline prop $owner'")
        }
        if (params.count { it.variadic } > 1 || params.dropLast(1).any { it.variadic }) {
            error("'deepinline prop $owner' may have one variadic type parameter, and it must be last")
        }
        return params
    }

    private fun parseTypeFunctionBlock(): List<TypeFunctionStmt> {
        val body = mutableListOf<TypeFunctionStmt>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            body.add(parseTypeFunctionStmt())
            skipNewlines()
        }
        return body
    }

    private fun parseTypeFunctionStmt(): TypeFunctionStmt = when {
        match(TokenType.RETURN) -> {
            val value = parseTypeFunctionExpr()
            consumeNewline()
            TypeFunctionStmt.Return(value)
        }
        check(TokenType.LET) || check(TokenType.VAR) || check(TokenType.VAL) || check(TokenType.FIN) -> {
            // Only `var` and `val` rebind; `let`/`fin` fix the binding (see the
            // four-keyword binding model in the Base Syntax DIP).
            val mutable = check(TokenType.VAR) || check(TokenType.VAL)
            advance()
            val name = consume(TokenType.IDENTIFIER, "Expected type binding name").lexeme
            consumeTypeAnnotationInTypeProp("binding '$name'")
            consume(TokenType.EQUAL, "Expected '=' in type binding")
            val value = parseTypeFunctionExpr()
            consumeNewline()
            TypeFunctionStmt.Binding(name, value, mutable)
        }
        match(TokenType.FOR) -> {
            val name = consume(TokenType.IDENTIFIER, "Expected type loop variable").lexeme
            consumeTypeAnnotationInTypeProp("loop variable '$name'")
            consume(TokenType.IN, "Expected 'in' in type loop")
            val pack = consume(TokenType.IDENTIFIER, "Expected variadic type parameter").lexeme
            consume(TokenType.L_BRACKET, "Expected '[' after variadic type parameter")
            val start = consume(TokenType.INT_LITERAL, "Expected type-pack start index")
            val startIndex = ((start.literal as NumericLiteral).value as Long).toInt()
            consume(TokenType.ELLIPSIS, "Expected '...' after type-pack start index")
            consume(TokenType.R_BRACKET, "Expected ']' after type-pack slice")
            consume(TokenType.L_BRACE, "Expected '{' before type loop body")
            skipNewlines()
            val loopBody = parseTypeFunctionBlock()
            consume(TokenType.R_BRACE, "Expected '}' after type loop body")
            consumeNewline()
            TypeFunctionStmt.ForEach(name, pack, startIndex, loopBody)
        }
        match(TokenType.IF) -> {
            val condition = parseTypeFunctionCondition()
            consume(TokenType.L_BRACE, "Expected '{' after the condition of a type 'if'")
            skipNewlines()
            val thenBody = parseTypeFunctionBlock()
            consume(TokenType.R_BRACE, "Expected '}' after a type 'if' body")
            skipNewlines()
            val elseBody = if (match(TokenType.ELSE)) {
                consume(TokenType.L_BRACE, "Expected '{' after 'else'")
                skipNewlines()
                val branch = parseTypeFunctionBlock()
                consume(TokenType.R_BRACE, "Expected '}' after an 'else' body")
                branch
            } else {
                emptyList()
            }
            consumeNewline()
            TypeFunctionStmt.If(condition, thenBody, elseBody)
        }
        check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.EQUAL -> {
            val name = advance().lexeme
            advance()
            val value = parseTypeFunctionExpr()
            consumeNewline()
            TypeFunctionStmt.Assignment(name, value)
        }
        else -> error("Expected a 'deepinline prop' statement at line ${peek().line}, got '${peek().lexeme}'")
    }

    /**
     * Consumes the mandatory `: Type` annotation on a `deepinline prop` binding.
     *
     * Every binding inside a type property is written out - the language never
     * infers a declaration's type, and `Type` is the only type a type property
     * can name.
     */
    private fun consumeTypeAnnotationInTypeProp(what: String) {
        if (!match(TokenType.COLON)) {
            error("The $what needs an explicit ': Type' annotation at line ${peek().line}")
        }
        val type = consume(TokenType.IDENTIFIER, "Expected 'Type' after ':'")
        if (type.lexeme != "Type") {
            error("The $what must be declared ': Type' - a type property binds types, not values, at line ${type.line}")
        }
    }

    /**
     * True when the condition here is a conjunction of `Bool` parameters rather
     * than a comparison between types.
     */
    private fun isValueFlagConditionAhead(): Boolean {
        var i = current
        if (tokens.getOrNull(i)?.type == TokenType.BANG) i++
        if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return false
        return tokens.getOrNull(i + 1)?.type in setOf(TokenType.AND_AND, TokenType.FAT_ARROW)
    }

    private fun parseTypeFunctionExpr(): TypeFunctionExpr {
        // `when { c => T; d => U; else => V }` - the arms are tried in order, so
        // it reads as the table of cases it is, rather than as nested `if`s.
        if (match(TokenType.WHEN)) {
            consume(TokenType.L_BRACE, "Expected '{' after 'when' in a type computation")
            skipNewlines()
            val arms = mutableListOf<Pair<TypeFunctionCondition, TypeFunctionExpr>>()
            var fallback: TypeFunctionExpr? = null
            while (!check(TokenType.R_BRACE)) {
                if (match(TokenType.ELSE)) {
                    consume(TokenType.FAT_ARROW, "Expected '=>' after 'else'")
                    fallback = parseTypeFunctionExpr()
                } else {
                    val condition = parseTypeFunctionCondition()
                    consume(TokenType.FAT_ARROW, "Expected '=>' after a 'when' condition")
                    arms.add(condition to parseTypeFunctionExpr())
                }
                skipNewlines()
            }
            consume(TokenType.R_BRACE, "Expected '}' after 'when'")
            val last = fallback
                ?: error("A 'when' in a type computation must end with 'else' at line ${peek().line}")
            // Folded from the last arm outwards, so the first written arm is the
            // outermost test and order of writing is order of trying.
            return arms.foldRight(last) { (condition, value), rest ->
                TypeFunctionExpr.Conditional(condition, value, rest)
            }
        }
        if (match(TokenType.IF)) {
            val condition = parseTypeFunctionCondition()
            consume(TokenType.L_BRACE, "Expected '{' after type condition")
            skipNewlines()
            val thenValue = parseTypeFunctionExpr()
            skipNewlines()
            consume(TokenType.R_BRACE, "Expected '}' after type result")
            skipNewlines()
            consume(TokenType.ELSE, "Expected 'else' in conditional type expression")
            consume(TokenType.L_BRACE, "Expected '{' after 'else'")
            skipNewlines()
            val elseValue = parseTypeFunctionExpr()
            skipNewlines()
            consume(TokenType.R_BRACE, "Expected '}' after alternative type result")
            return TypeFunctionExpr.Conditional(condition, thenValue, elseValue)
        }
        val name = consume(TokenType.IDENTIFIER, "Expected a type value").lexeme
        parseTypePropCall(name)?.let { return if (match(TokenType.QMARK)) TypeFunctionExpr.Nullable(it) else it }
        if (match(TokenType.DOT)) {
            val index = consume(TokenType.INT_LITERAL, "Expected type-pack index after '.'")
            val element = TypeFunctionExpr.PackElement(name, ((index.literal as NumericLiteral).value as Long).toInt())
            return if (match(TokenType.QMARK)) TypeFunctionExpr.Nullable(element) else element
        }
        // `T?` - the computed type is the nullable form of another.
        if (match(TokenType.QMARK)) {
            return TypeFunctionExpr.Nullable(TypeFunctionExpr.Reference(name))
        }
        return TypeFunctionExpr.Reference(name)
    }

    /**
     * `Name<A, B>` - one type property calling another, spelled the same way a
     * use site outside the body spells it. Returns null when [name] is not
     * followed by type arguments.
     */
    private fun parseTypePropCall(name: String): TypeFunctionExpr.Call? {
        if (!match(TokenType.LESS)) return null
        val args = mutableListOf<TypeFunctionExpr>()
        if (!check(TokenType.GREATER)) {
            do { args.add(parseTypeFunctionExpr()) } while (match(TokenType.COMMA))
        }
        when {
            pendingGreater -> { pendingGreater = false }
            check(TokenType.SHIFT_RIGHT) -> { advance(); pendingGreater = true }
            else -> consume(TokenType.GREATER, "Expected '>' to close the arguments of '$name'")
        }
        val callName = if (typeFunctionNamespacePrefix.isEmpty()) name else "${typeFunctionNamespacePrefix}__$name"
        return TypeFunctionExpr.Call(callName, args)
    }

    private fun parseTypeFunctionCondition(): TypeFunctionCondition {
        // `hasDefault`, `!hasDefault`, `a && b` - the type function's own `Bool`
        // parameters. Told apart from a type comparison by what follows: a bare
        // name reaching `&&` or `=>` is a flag, not the left side of a compare.
        if (isValueFlagConditionAhead()) {
            val names = mutableListOf<String>()
            val expected = mutableListOf<Boolean>()
            do {
                val negated = match(TokenType.BANG)
                names.add(consume(TokenType.IDENTIFIER, "Expected a 'Bool' parameter in a type condition").lexeme)
                expected.add(!negated)
            } while (match(TokenType.AND_AND))
            return TypeFunctionCondition(
                TypeFunctionExpr.Reference(names.first()),
                TokenType.EQUAL_EQUAL,
                TypeFunctionExpr.Reference(names.first()),
                compareRank = false,
                valueFlags = names,
                flagsExpected = expected,
            )
        }
        fun operand(): Pair<TypeFunctionExpr, Boolean> {
            val name = consume(TokenType.IDENTIFIER, "Expected a type value in type comparison").lexeme
            val expression = parseTypePropCall(name) ?: TypeFunctionExpr.Reference(name)
            val rank = if (match(TokenType.DOT) || match(TokenType.DOUBLE_COLON)) {
                val member = consume(TokenType.IDENTIFIER, "Expected 'rank' in type comparison")
                if (member.lexeme != "rank") error("Only '.rank'/'::rank' is supported in ranked type comparisons at line ${member.line}")
                true
            } else false
            return expression to rank
        }
        val (left, leftRank) = operand()
        val operator = when {
            match(TokenType.GREATER_EQUAL) -> TokenType.GREATER_EQUAL
            match(TokenType.LESS_EQUAL) -> TokenType.LESS_EQUAL
            match(TokenType.GREATER) -> TokenType.GREATER
            match(TokenType.LESS) -> TokenType.LESS
            match(TokenType.EQUAL_EQUAL) -> TokenType.EQUAL_EQUAL
            match(TokenType.BANG_EQUAL) -> TokenType.BANG_EQUAL
            else -> error("Expected comparison operator in type condition at line ${peek().line}")
        }
        val (right, rightRank) = operand()
        if (leftRank != rightRank) error("Both sides of a type comparison must use '.rank', or neither side may use it")
        return TypeFunctionCondition(left, operator, right, leftRank)
    }

    private fun parseTry(): Stmt.Try {
        val start = peek()
        consume(TokenType.TRY, "Expected 'try'")
        consume(TokenType.L_BRACE, "Expected '{' after 'try'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after try body")
        var catchName: String? = null
        var catchBody: List<Stmt>? = null
        if (match(TokenType.CATCH)) {
            consume(TokenType.L_BRACE, "Expected '{' after 'catch'")
            skipNewlines()
            if (check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.ARROW) {
                catchName = advance().lexeme
                consume(TokenType.ARROW, "Expected '->'")
                skipNewlines()
            }
            catchBody = parseBlock()
            consume(TokenType.R_BRACE, "Expected '}' after catch body")
        }
        consumeNewline()
        return Stmt.Try(body, catchName, catchBody, start.line, start.column)
    }

    private fun parseInline(): Stmt {
        return when (peekNext()?.type) {
            TokenType.L_BRACE -> parseInlineBlock()
            TokenType.SCOPE -> parseInlineScopeBlock()
            TokenType.IF -> parseInlineIf()
            TokenType.FOR -> parseInlineFor()
            TokenType.ASSERT -> parseInlineAssertStmt()
            TokenType.TRACE -> parseInlineTraceStmt()
            TokenType.PANIC -> parsePanicStmt(inlinePanic = true)
            TokenType.FIN -> parseInlineFin()
            TokenType.VAR, TokenType.VAL -> parseInlineVar()
            TokenType.LET -> parseInlineLet()
            TokenType.IDENTIFIER -> parseInlineAssignment()
            TokenType.STRING_LITERAL, TokenType.INTERPOLATED_STRING -> parseInlineSplice()
            else -> error("Expected '{', 'scope', 'if', 'for', 'assert', 'trace', 'fin', 'var', 'let', a string splice, or identifier after 'inline' at line ${peek().line}")
        }
    }

    /** `inline for x in a..b { body }` - a compile-time unrolled loop. */
    private fun parseInlineFor(): Stmt {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.FOR, "Expected 'for'")
        val name = consume(TokenType.IDENTIFIER, "Expected loop variable name").lexeme
        consume(TokenType.IN, "Expected 'in' after loop variable")
        // Compile-time list iterable (a type-list variable like `Systems`, a
        // `[A, B]` grouping, or `~`-joined lists): unroll the body per element at
        // parse time into a static statement block, exactly like the top-level
        // form. Generated calls stay named/static, so this lowers to every target.
        if ((peek().type == TokenType.IDENTIFIER && typeListEnv.containsKey(peek().lexeme)) ||
            peek().type == TokenType.L_BRACKET) {
            val list = parseComptimeForValues()
            val indexVar = if (matchWithKeyword()) {
                consumeIdentifierLike("Expected index variable after 'with'")
            } else null
            consume(TokenType.L_BRACE, "Expected '{' after inline for iterable")
            val bodyTokens = captureBraceBody()
            consumeNewline()
            val stmts = mutableListOf<Stmt>()
            for (i in list.indices) {
                var rendered = bodyTokens
                val bindings = buildList {
                    add(name to list[i])
                    if (indexVar != null) add(indexVar to i.toString())
                }
                rendered = foldBraceInterpolation(rendered, bindings)
                rendered = substituteLoopVar(rendered, name, list[i])
                if (indexVar != null) rendered = substituteLoopVar(rendered, indexVar, i.toString())
                rendered = foldListIndexing(rendered)
                stmts.addAll(parseInlineForBodyStatements(rendered, start))
            }
            return Stmt.Scope(stmts, start.line, start.column)
        }
        val savedTrailing = allowTrailingLambda
        allowTrailingLambda = false
        val parsedIterable = try {
            parseExpr()
        } finally {
            allowTrailingLambda = savedTrailing
        }
        // The general expression parser also supports free-form infix calls, so
        // `items with index` initially has the shape `items.with(index)`. Unwrap
        // that parser-level representation here because `with index` belongs to
        // the compile-time loop rather than the iterable expression.
        val infixWith = parsedIterable as? Expr.MethodCall
        val hasInfixIndex = infixWith?.name == "with" && infixWith.args.size == 1 &&
            infixWith.args.single() is Expr.Identifier
        val iterable = if (hasInfixIndex) infixWith!!.target else parsedIterable
        val indexName = if (hasInfixIndex) {
            (infixWith!!.args.single() as Expr.Identifier).name
        } else if (matchWithKeyword()) {
            consume(TokenType.IDENTIFIER, "Expected index binding after 'with'").lexeme
        } else null
        consume(TokenType.L_BRACE, "Expected '{' after inline for iterable")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after inline for body")
        consumeNewline()
        return Stmt.InlineFor(name, iterable, body, start.line, start.column, indexName = indexName)
    }


    /**
     * Re-parses the rendered tokens of one unrolled `inline for` iteration as a
     * statement sequence. The tokens are wrapped in a throwaway function so the
     * ordinary body parser can consume them, then that function's body is lifted.
     */
    private fun parseInlineForBodyStatements(rendered: List<Token>, start: Token): List<Stmt> {
        val prefix = Lexer("func __inline_for_body__() {\n").tokenize().dropLast(1) // drop trailing EOF
        val suffix = Lexer("\n}\n").tokenize() // includes EOF
        val tokens = prefix + rendered + suffix
        val program = Parser(tokens, typeListEnv, declaredEnums, typeListRealm = typeListRealm).parse()
        val fn = program.items.filterIsInstance<TopLevel.Func>()
            .firstOrNull { it.decl.name == "__inline_for_body__" }
            ?: error("failed to expand 'inline for' body at line ${start.line}")
        return fn.decl.body
    }

    /** `inline scope { ... }` - alias for `inline { ... }`. */
    private fun parseInlineScopeBlock(): Stmt.InlineBlock {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.SCOPE, "Expected 'scope'")
        consume(TokenType.L_BRACE, "Expected '{' after 'scope'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.InlineBlock(body, start.line, start.column)
    }

    private fun parseInlineBlock(): Stmt.InlineBlock {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.L_BRACE, "Expected '{' after 'inline'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.InlineBlock(body, start.line, start.column)
    }

    private fun parseDeepInlineStmt(): Stmt {
        return when (peekNext()?.type) {
            TokenType.L_BRACE -> parseDeepInlineBlock()
            TokenType.SCOPE -> parseDeepInlineScopeBlock()
            TokenType.IF -> parseDeepInlineIf()
            else -> error("Expected '{', 'scope', or 'if' after 'deepinline' at line ${peek().line}")
        }
    }

    /** `deepinline scope { ... }` - alias for `deepinline { ... }`. */
    private fun parseDeepInlineScopeBlock(): Stmt.DeepInlineBlock {
        val start = peek()
        consume(TokenType.DEEPINLINE, "Expected 'deepinline'")
        consume(TokenType.SCOPE, "Expected 'scope'")
        consume(TokenType.L_BRACE, "Expected '{' after 'scope'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.DeepInlineBlock(body, start.line, start.column)
    }

    private fun parseDeepInlineBlock(): Stmt.DeepInlineBlock {
        val start = peek()
        consume(TokenType.DEEPINLINE, "Expected 'deepinline'")
        consume(TokenType.L_BRACE, "Expected '{' after 'deepinline'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.DeepInlineBlock(body, start.line, start.column)
    }

    private fun parseDeepInlineIf(): Stmt.DeepInlineIf {
        val start = peek()
        consume(TokenType.DEEPINLINE, "Expected 'deepinline'")
        consume(TokenType.IF, "Expected 'if' after 'deepinline'")
        val condition = withoutTrailingLambda { parseExpr() }
        consume(TokenType.L_BRACE, "Expected '{' after deepinline if condition")
        skipNewlines()
        val thenBranch = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        val elseBranch = if (match(TokenType.ELSE)) {
            consume(TokenType.L_BRACE, "Expected '{' after else")
            skipNewlines()
            val branch = parseBlock()
            consume(TokenType.R_BRACE, "Expected '}'")
            branch
        } else null
        consumeNewline()
        return Stmt.DeepInlineIf(condition, thenBranch, elseBranch, start.line, start.column)
    }

    private fun parseScope(): Stmt.Scope {
        val start = peek()
        consume(TokenType.SCOPE, "Expected 'scope'")
        consume(TokenType.L_BRACE, "Expected '{' after 'scope'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.Scope(body, start.line, start.column, shared = true)
    }

    private fun parseNoInline(): Stmt.NoInline {
        val start = peek()
        consume(TokenType.NOINLINE, "Expected 'noinline'")
        val inner = parseStmt()
        return Stmt.NoInline(inner, start.line, start.column)
    }

    private fun parseInlineFin(): Stmt.InlineFin {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.FIN, "Expected 'fin' after 'inline'")
        val name = consumeIdentifierLike("Expected variable name")
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in inline fin declaration")
        val init = parseInitializer(type)
        consumeNewline()
        return Stmt.InlineFin(name, type, init, start.line, start.column)
    }

    /** `inline var name = …` / `inline val name = …` - the compile-time forms. */
    private fun parseInlineVar(): Stmt.InlineVar {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        val keyword = advance() // 'var' or 'val'
        val valueMutable = keyword.type == TokenType.VAR
        val name = consumeIdentifierLike("Expected variable name")
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in inline ${keyword.lexeme} declaration")
        val init = parseInitializer(type)
        consumeNewline()
        return Stmt.InlineVar(name, type, init, start.line, start.column, valueMutable = valueMutable)
    }

    private fun parseInlineLet(): Stmt.InlineLet {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.LET, "Expected 'let' after 'inline'")
        val name = consumeIdentifierLike("Expected variable name")
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in inline let declaration")
        val init = parseInitializer(type)
        consumeNewline()
        return Stmt.InlineLet(name, type, init, start.line, start.column)
    }

    private fun parseInlineAssignment(): Stmt.InlineAssignment {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        val name = consume(TokenType.IDENTIFIER, "Expected variable name").lexeme
        consume(TokenType.EQUAL, "Expected '=' in inline assignment")
        val value = parseExpr()
        consumeNewline()
        return Stmt.InlineAssignment(name, value, start.line, start.column)
    }

    private fun parseAssertStmt(): Stmt.Assert {
        val start = peek()
        consume(TokenType.ASSERT, "Expected 'assert'")
        // The `{ message }` block belongs to the assert, so a call in the condition
        // must not swallow it as a trailing lambda (`assert x.add(v) { "msg" }`).
        val savedTrailing = allowTrailingLambda
        allowTrailingLambda = false
        val condition = parseExpr()
        allowTrailingLambda = savedTrailing
        // The `{ message }` block is optional - a bare `assert cond` uses an empty message.
        val message = if (check(TokenType.L_BRACE)) {
            advance()
            skipNewlines()
            val msg = parseExpr()
            skipNewlines()
            consume(TokenType.R_BRACE, "Expected '}' after assert message")
            msg
        } else {
            Expr.StringLiteral("", start.line, start.column, 0)
        }
        consumeNewline()
        return Stmt.Assert(condition, message, start.line, start.column)
    }

    private fun parseTraceStmt(): Stmt.Trace {
        val start = peek()
        consume(TokenType.TRACE, "Expected 'trace'")
        val (level, message, liftBody, explicitLevel) = parseTracePayload(start, inline = false)
        consumeNewline()
        return Stmt.Trace(
            message,
            start.line,
            start.column,
            level = level,
            liftBody = liftBody,
            explicitLevel = explicitLevel,
        )
    }

    private fun parseInlineAssertStmt(): Stmt.InlineAssert {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.ASSERT, "Expected 'assert'")
        val savedTrailing = allowTrailingLambda
        allowTrailingLambda = false
        val condition = try {
            parseExpr()
        } finally {
            allowTrailingLambda = savedTrailing
        }
        consume(TokenType.L_BRACE, "Expected '{' after inline assert condition")
        skipNewlines()
        val message = parseExpr()
        skipNewlines()
        consume(TokenType.R_BRACE, "Expected '}' after inline assert message")
        consumeNewline()
        return Stmt.InlineAssert(condition, message, start.line, start.column)
    }

    private fun parseInlineTraceStmt(): Stmt.InlineTrace {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.TRACE, "Expected 'trace'")
        val (level, message) = parseTracePayload(start, inline = true)
        consumeNewline()
        return Stmt.InlineTrace(message, start.line, start.column, level = level)
    }

    private data class TracePayload(
        val level: Expr,
        val message: Expr,
        val liftBody: Boolean,
        val explicitLevel: Boolean,
    )

    /** Parses direct `trace expr` and lambda `[level] { message }` forms. */
    private fun parseTracePayload(start: Token, inline: Boolean): TracePayload {
        if (check(TokenType.L_BRACE)) {
            val level = defaultTraceLevel(start)
            consume(TokenType.L_BRACE, "Expected '{' after ${if (inline) "inline " else ""}trace")
            skipNewlines()
            val message = parseExpr().bindTraceReceiver(level)
            skipNewlines()
            consume(TokenType.R_BRACE, "Expected '}' after trace message")
            return TracePayload(level, message, liftBody = true, explicitLevel = false)
        }

        val shorthandLevel = if (match(TokenType.DOT)) {
            val variant = consume(TokenType.IDENTIFIER, "Expected LogLevel variant after '.'")
            Expr.Member(
                Expr.Identifier("LogLevel", variant.line, variant.column, variant.lexeme.length),
                variant.lexeme,
                variant.line,
                variant.column,
                variant.lexeme.length + 1,
            )
        } else null

        val candidate = shorthandLevel ?: run {
            val savedTrailing = allowTrailingLambda
            allowTrailingLambda = false
            try {
                parseExpr()
            } finally {
                allowTrailingLambda = savedTrailing
            }
        }

        if (!check(TokenType.L_BRACE)) {
            return if (shorthandLevel != null) {
                TracePayload(shorthandLevel, parseExpr(), liftBody = false, explicitLevel = true)
            } else if (
                !check(TokenType.NEWLINE) &&
                !check(TokenType.SEMICOLON) &&
                !check(TokenType.R_BRACE) &&
                !isAtEnd()
            ) {
                TracePayload(candidate, parseExpr(), liftBody = false, explicitLevel = true)
            } else {
                TracePayload(defaultTraceLevel(start), candidate, liftBody = false, explicitLevel = false)
            }
        }

        val level = candidate
        consume(TokenType.L_BRACE, "Expected '{' after ${if (inline) "inline " else ""}trace level")
        skipNewlines()
        val message = parseExpr().bindTraceReceiver(level)
        skipNewlines()
        consume(TokenType.R_BRACE, "Expected '}' after trace message")
        return TracePayload(level, message, liftBody = true, explicitLevel = true)
    }

    private fun defaultTraceLevel(start: Token): Expr =
        Expr.Call("__defaultLogLevel", emptyList(), start.line, start.column)

    private fun parseIf(): Stmt.If {
        val start = peek()
        consume(TokenType.IF, "Expected 'if'")
        // The `{` after the condition is the branch body, never a trailing
        // lambda of a call in the condition (`if f() { … }`).
        val savedTrailing = allowTrailingLambda
        allowTrailingLambda = false
        val condition = parseExpr()
        allowTrailingLambda = savedTrailing
        consume(TokenType.L_BRACE, "Expected '{' after if condition")
        skipNewlines()
        val thenBranch = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        skipNewlines()  // tolerate newlines between } and else
        val elseBranch = if (match(TokenType.ELSE)) {
            if (check(TokenType.IF)) {
                listOf(parseIf())
            } else {
                consume(TokenType.L_BRACE, "Expected '{' after else")
                skipNewlines()
                val branch = parseBlock()
                consume(TokenType.R_BRACE, "Expected '}'")
                branch
            }
        } else null
        consumeNewline()
        return Stmt.If(condition, thenBranch, elseBranch, start.line, start.column)
    }

    private fun parseInlineIf(): Stmt.InlineIf {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.IF, "Expected 'if' after 'inline'")
        val condition = withoutTrailingLambda { parseExpr() }
        consume(TokenType.L_BRACE, "Expected '{' after inline if condition")
        skipNewlines()
        val thenBranch = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        val elseBranch = if (match(TokenType.ELSE)) {
            consume(TokenType.L_BRACE, "Expected '{' after else")
            skipNewlines()
            val branch = parseBlock()
            consume(TokenType.R_BRACE, "Expected '}'")
            branch
        } else null
        consumeNewline()
        return Stmt.InlineIf(condition, thenBranch, elseBranch, start.line, start.column)
    }

    private fun parseBlock(): List<Stmt> {
        skipNewlines()
        val stmts = mutableListOf<Stmt>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            stmts.add(parseStmt())
            skipNewlines()
        }
        return stmts
    }

    /**
     * Parses an initializer with the declaration's expected type in scope.
     * A leading-dot value (`.Warn`) is expanded to a nominal member
     * (`LogLevel.Warn`) only when an explicit named type provides that context.
     */
    private fun parseInitializer(expectedType: TypeRef?): Expr {
        if (!check(TokenType.DOT)) return parseExpr()
        val named = when (expectedType) {
            is TypeRef.Named -> expectedType
            is TypeRef.Nullable -> expectedType.inner as? TypeRef.Named
            else -> null
        } ?: return parseExpr()
        val dot = advance()
        // `.(args)` - the declared type's constructor, without naming it twice.
        // `fin invalid: Entity = .(-1, 0)` says the type once, in the place that
        // already had to say it.
        if (check(TokenType.L_PAREN)) {
            advance()
            val args = mutableListOf<Expr>()
            skipNewlines()
            while (!check(TokenType.R_PAREN) && !isAtEnd()) {
                val arg = parseExpr()
                args.add(
                    if (arg is Expr.Identifier && match(TokenType.COLON)) {
                        Expr.NamedArg(arg.name, parseExpr(), arg.line, arg.column)
                    } else arg,
                )
                match(TokenType.COMMA)
                skipNewlines()
            }
            consume(TokenType.R_PAREN, "Expected ')' after '.(' constructor arguments")
            return Expr.Call(named.name, args, dot.line, dot.column, named.name.length)
        }
        val variant = consume(TokenType.IDENTIFIER, "Expected variant name after '.'")
        return Expr.Member(
            Expr.Identifier(named.name, dot.line, dot.column, named.name.length),
            variant.lexeme,
            dot.line,
            dot.column,
            variant.lexeme.length + 1,
        )
    }

    private fun parseInitializer(type: TypeAnnotation): Expr =
        parseInitializer((type as? TypeAnnotation.Explicit)?.ref)

    /** `var name = …` (mutable value) or `val name = …` (immutable value). */
    private fun parseVarDecl(): Stmt.VarDecl {
        val start = peek()
        val valueMutable = advance().type == TokenType.VAR // 'var' or 'val'
        val name = consumeIdentifierLike("Expected variable name")
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in declaration")
        val init = parseInitializer(type)
        consumeNewline()
        return Stmt.VarDecl(name, type, init, start.line, start.column, valueMutable = valueMutable)
    }

    private fun parseFinDecl(): Stmt.FinDecl {
        val start = peek()
        advance() // consume 'fin'
        val name = consumeIdentifierLike("Expected variable name")
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in declaration")
        val init = parseInitializer(type)
        consumeNewline()
        return Stmt.FinDecl(name, type, init, start.line, start.column)
    }

    private fun parseLetDecl(): Stmt.LetDecl {
        val start = peek()
        advance() // consume 'let'
        val name = consumeIdentifierLike("Expected variable name")
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in let declaration")
        val init = parseInitializer(type)
        consumeNewline()
        return Stmt.LetDecl(name, type, init, start.line, start.column)
    }

    /** `lazy fin` / `lazy let`: evaluate the initializer once, on first read. */
    private fun parseLazyDecl(): Stmt {
        val start = advance() // lazy
        return when {
            check(TokenType.FIN) -> parseFinDecl().copy(line = start.line, column = start.column, lazy = true)
            check(TokenType.LET) -> parseLetDecl().copy(line = start.line, column = start.column, lazy = true)
            check(TokenType.VAR) || check(TokenType.VAL) -> error(
                "'lazy' requires 'fin' or 'let' at line ${start.line}; " +
                    "a rebindable lazy name has ambiguous initialization semantics",
            )
            else -> error("Expected 'fin' or 'let' after 'lazy' at line ${start.line}")
        }
    }

    private fun parseReturn(): Stmt {
        val start = peek()
        consume(TokenType.RETURN, "Expected 'return'")
        // `return when …` / `return if …` - a branching construct in return
        // position, where every branch carries a value rather than a block.
        if (check(TokenType.WHEN)) {
            return parseReturnWhen(start)
        }
        if (check(TokenType.IF)) {
            return parseReturnIf(start)
        }
        if (check(TokenType.NEWLINE) || check(TokenType.R_BRACE) || isAtEnd()) {
            consumeNewline()
            return Stmt.Return(null, start.line, start.column)
        }
        val stmt = parseReturnedValue(start)
        consumeNewline()
        return stmt
    }

    /**
     * The statement a `return` produces once its value has been read.
     *
     * `.Variant` fails the function with an error-set variant - the only way to
     * return an error (the old `fail return .Variant` form is gone). Anything
     * else yields the success value. Both spellings are recognized here rather
     * than in [parseReturn] so that every *branch* in return position - a
     * `when` arm, an `if` branch - accepts the shorthand on the same terms.
     */
    private fun parseReturnedValue(start: Token): Stmt {
        if (check(TokenType.DOT) && peekNext()?.type == TokenType.IDENTIFIER) {
            advance() // '.'
            val variant = consume(TokenType.IDENTIFIER, "Expected error variant after 'return .'").lexeme
            // `return .OutOfBounds(i, n)` - an error variant carrying data. The error
            // set owning the variant is the one the signature declares, and a
            // payload-bearing set is also a slot, so this is that slot's construction:
            // the thrown value carries its fields instead of only its name.
            if (check(TokenType.L_PAREN)) {
                if (currentFailSets.size != 1) {
                    error(
                        "cannot tell which error set '$variant' belongs to at line ${peek().line}; " +
                            "a function returning a payload must declare exactly one '?!' set",
                    )
                }
                advance() // '('
                val args = mutableListOf<Expr>()
                if (!check(TokenType.R_PAREN)) {
                    do { args.add(parseExpr()) } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_PAREN, "Expected ')' after error payload")
                val set = currentFailSets.single()
                return Stmt.Throw(
                    Expr.MethodCall(
                        Expr.Identifier(set, start.line, start.column, set.length),
                        variant, args, start.line, start.column,
                    ),
                    start.line, start.column,
                )
            }
            return Stmt.Throw(Expr.StringLiteral(variant, start.line), start.line, start.column)
        }
        return Stmt.Return(parseExpr(), start.line, start.column)
    }

    /**
     * `return if cond { value } else { value }`.
     *
     * Desugars to the statement form with every branch body a `return`, exactly
     * as [parseReturnWhen] does - which is what lets a branch carry the
     * `.Variant` error shorthand (`return if y == 0 { .DivisionByZero } else { x / y }`).
     * As an *expression* an if-branch is a value, and `.Variant` is not one:
     * failing is something a function does, not a value it produces. Lowering
     * here keeps every later stage seeing the `if` it already handles.
     */
    private fun parseReturnIf(start: Token): Stmt {
        consume(TokenType.IF, "Expected 'if'")
        // The `{` after the condition is the branch body, never a trailing
        // lambda of a call in the condition (`if f() { … }`).
        val savedTrailing = allowTrailingLambda
        allowTrailingLambda = false
        val condition = parseExpr()
        allowTrailingLambda = savedTrailing
        val thenBranch = parseReturnBranchBody("if")
        skipNewlines() // tolerate newlines between } and else
        if (!match(TokenType.ELSE)) {
            error("'return if' needs an 'else' - every path has to produce a value, at line ${start.line}")
        }
        val elseBranch = if (check(TokenType.IF)) {
            listOf(parseReturnIf(start))
        } else {
            parseReturnBranchBody("else")
        }
        consumeNewline()
        return Stmt.If(condition, thenBranch, elseBranch, start.line, start.column)
    }

    /**
     * One `{ … }` branch of a `return if`, ending in the value it returns.
     *
     * A branch is usually just a value - including a nested if-expression, which
     * is how `return if a { 1 } else { if b { -1 } else { 0 } }` reads. It may
     * also open with statements and end in its value, so a branch that needs a
     * local or two to compute its answer does not have to be hoisted into a
     * helper. The two are told apart by trying the value first and falling back:
     * `if` means different things in expression and statement position, so no
     * amount of lookahead at the first token settles it.
     */
    private fun parseReturnBranchBody(what: String): List<Stmt> {
        consume(TokenType.L_BRACE, "Expected '{' after $what")
        skipNewlines()
        val at = peek()
        // `.Variant` fails the function; it is not a value, so it never reaches
        // the expression or block parsers below.
        if (check(TokenType.DOT) && peekNext()?.type == TokenType.IDENTIFIER) {
            val thrown = parseReturnedValue(at)
            skipNewlines()
            consume(TokenType.R_BRACE, "Expected '}' after $what branch")
            return listOf(thrown)
        }
        parseBranchValueOrNull(at)?.let { value ->
            consume(TokenType.R_BRACE, "Expected '}' after $what branch")
            return listOf(value)
        }
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after $what branch")
        if (body.isEmpty()) {
            error("an 'if' branch in return position must produce a value (line ${at.line})")
        }
        val last = body[body.size - 1]
        if (last !is Stmt.ExprStmt) {
            // A branch that already returns, throws or panics is complete.
            if (branchBodyYields(last)) return body
            error(
                "an 'if' branch in return position must end in a value at line ${last.line} - " +
                    "assign the trailing '${branchTailName(last)}' to a `fin` and end with that, " +
                    "or return from inside it",
            )
        }
        val lifted = body.subList(0, body.size - 1).toMutableList()
        lifted.add(Stmt.Return(last.expr, last.line, last.column))
        return lifted
    }

    /**
     * The branch's value, if the whole branch is one expression.
     *
     * Returns null - leaving the position exactly as it found it - when the
     * branch is not a lone expression, so the caller can parse it as a block.
     */
    private fun parseBranchValueOrNull(at: Token): Stmt? {
        val savedCurrent = current
        val savedGreater = pendingGreater
        val savedTrailing = allowTrailingLambda
        fun rewind(): Stmt? {
            current = savedCurrent
            pendingGreater = savedGreater
            allowTrailingLambda = savedTrailing
            return null
        }
        val value = try {
            parseExpr()
        } catch (_: IllegalStateException) {
            return rewind()
        }
        skipNewlines()
        if (check(TokenType.R_BRACE)) return Stmt.Return(value, at.line, at.column)
        return rewind()
    }

    /**
     * `return when scrutinee { pattern -> value … else -> value }`.
     *
     * Desugars to the statement form with every branch body a `return`, so
     * patterns, slot destructuring, multi-pattern branches and `else` behave
     * exactly as they do in a statement `when` - the only difference is that a
     * branch carries a value instead of a block. Lowering here rather than
     * adding an expression node keeps every later stage (semantics, IR and all
     * three backends) seeing the `when` they already handle.
     */
    private fun parseReturnWhen(start: Token): Stmt {
        val scrutinee = parseWhenHead()
        val parts = parseWhenBranches(scrutinee) { parseWhenReturnValue() }
        consumeNewline()
        return Stmt.When(
            scrutinee,
            parts.branches(start),
            parts.elseBody,
            start.line,
            start.column
        )
    }

    /**
     * One `return when` branch body.
     *
     * `-> expr` is the common single-value form. `-> { … }` is a full block
     * whose trailing expression is the value, so a branch that needs a couple
     * of locals to compute its answer does not have to be hoisted out into a
     * helper function.
     */
    private fun parseWhenReturnValue(): List<Stmt> {
        val at = peek()
        if (!check(TokenType.L_BRACE)) {
            return listOf(parseReturnedValue(at))
        }
        advance() // '{'
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after when branch body")
        if (body.isEmpty()) {
            error("a `when` branch must produce a value (line ${at.line})")
        }
        val last = body[body.size - 1]
        if (last !is Stmt.ExprStmt) {
            // A branch that already returns, throws or panics is complete.
            if (branchBodyYields(last)) {
                return body
            }
            // Anything else cannot produce the branch's value. `if` and `when`
            // are the traps here: in statement position they parse as
            // statements, so a branch ending in one would silently fall out of
            // the function with no value at all.
            error(
                "a `when` branch block must end in a value at line ${last.line} - " +
                    "assign the trailing '${branchTailName(last)}' to a `fin` and end with that, " +
                    "or return from inside it"
            )
        }
        val lifted = body.subList(0, body.size - 1).toMutableList()
        lifted.add(Stmt.Return(last.expr, last.line, last.column))
        return lifted
    }

    /** True when [stmt] already leaves the function, so a branch needs no value. */
    private fun branchBodyYields(stmt: Stmt): Boolean = when (stmt) {
        is Stmt.Return -> true
        is Stmt.Throw -> true
        is Stmt.Panic -> true
        is Stmt.Yield -> true
        else -> false
    }

    /** A human name for the statement a branch block wrongly ended on. */
    private fun branchTailName(stmt: Stmt): String = when (stmt) {
        is Stmt.If -> "if"
        is Stmt.When -> "when"
        is Stmt.While -> "while"
        is Stmt.For -> "for"
        else -> "statement"
    }

    /** One `when`-branch value: `-> expr` or `-> { expr }`. */
    private fun parseWhenBranchValue(): Expr {
        if (match(TokenType.L_BRACE)) {
            skipNewlines()
            val value = parseSealableValue()
            skipNewlines()
            consume(TokenType.R_BRACE, "Expected '}' after when branch value")
            return value
        }
        return parseSealableValue()
    }

    /**
     * A branch value, optionally sealed: `seal "!! "`.
     *
     * `seal` is contextual, so a binding may still be called that: the
     * keyword reading is taken only when something that can start an
     * expression follows it, which a bare name being read never has.
     */
    private fun parseSealableValue(): Expr {
        if (check(TokenType.IDENTIFIER) && peek().lexeme == "seal" && startsExpression(peekNext())) {
            val start = advance()
            return Expr.Seal(parseExpr(), start.line, start.column, start.lexeme.length)
        }
        return parseExpr()
    }

    /**
     * Whether [token] can begin an expression.
     *
     * Used to tell a contextual keyword from a name being read: `seal .Red`
     * seals a value, while `seal` alone, or `seal + 1`, is the binding.
     */
    private fun startsExpression(token: Token?): Boolean = when (token?.type) {
        null, TokenType.NEWLINE, TokenType.EOF, TokenType.R_BRACE, TokenType.R_PAREN,
        TokenType.R_BRACKET, TokenType.COMMA, TokenType.COLON, TokenType.SEMICOLON,
        -> false
        TokenType.DOT, TokenType.IDENTIFIER, TokenType.INT_LITERAL, TokenType.DOUBLE_LITERAL,
        TokenType.STRING_LITERAL, TokenType.INTERPOLATED_STRING, TokenType.CHAR_LITERAL,
        TokenType.TRUE, TokenType.FALSE, TokenType.NULL, TokenType.L_PAREN, TokenType.L_BRACKET,
        TokenType.L_BRACE, TokenType.IF, TokenType.WHEN, TokenType.MINUS, TokenType.BANG,
        -> true
        // An infix operator means the name was the left operand, not a keyword.
        else -> false
    }

    /**
     * `when` in expression position - `fin x = when k { A -> 1  else -> 2 }`.
     *
     * Desugars to a chain of if-expressions, so every later stage keeps seeing
     * the `IfExpr` it already handles. A pattern becomes an `==` against the
     * scrutinee (which covers enum members, literals, and the `when true { … }`
     * guard form), and `is T` stays a type check.
     *
     * A branch that *destructures* a slot payload cannot be written this way:
     * an expression has nowhere to put the binding. Those are rejected here
     * with that advice rather than silently comparing against a constructor
     * call - `return when` and the statement `when` both handle them.
     *
     * An `else` is optional. Without one the final branch becomes the fallback
     * and its test is dropped, which is what exhaustiveness means in practice:
     * when every case of an enum is listed, testing the last one is redundant,
     * and demanding a dead `else` after it only invites an unreachable branch
     * that later drifts out of step with the enum.
     */
    private fun parseWhenExpr(): Expr {
        val start = peek()
        val scrutinee = parseWhenHead()
        val parts = parseWhenBranches(scrutinee) { parseWhenBranchValue() }

        val values = parts.bodies
        val elseValue = parts.elseBody
        if (values.isEmpty()) {
            error("a `when` expression needs at least one branch (line ${start.line})")
        }

        // Each branch's patterns become one condition on the scrutinee.
        val conditions = mutableListOf<Expr>()
        for (group in parts.patterns) {
            var condition = whenExprCondition(scrutinee, group[0], start)
            var p = 1
            while (p < group.size) {
                condition = Expr.Binary(
                    condition,
                    TokenType.OR_OR,
                    whenExprCondition(scrutinee, group[p], start),
                    start.line,
                    start.column
                )
                p++
            }
            conditions.add(condition)
        }
        // No `else`: the last branch is the fallback, so an exhaustive `when`
        // over an enum needs no dead catch-all after its final case.
        var result: Expr = elseValue ?: values[values.size - 1]
        var i = if (elseValue == null) conditions.size - 2 else conditions.size - 1
        while (i >= 0) {
            result = Expr.IfExpr(conditions[i], values[i], result, start.line, start.column)
            i--
        }
        return result
    }

    /**
     * One already-parsed `when` pattern, as a condition on the scrutinee.
     *
     * `is T` is already a test against the scrutinee and passes through; every
     * other pattern becomes an equality. A pattern that *destructures* a slot
     * payload is refused: a binding has nowhere to live in an expression, and
     * comparing against the constructor call would quietly never match.
     */
    private fun whenExprCondition(scrutinee: Expr, pattern: Expr, start: Token): Expr {
        if (pattern is Expr.IsCheck) {
            return pattern
        }
        // `Shape.Circle(r)` parses as a method call on the slot, and a plain
        // `Circle(r)` as a call; either shape with arguments is a destructuring
        // pattern rather than a value to compare against.
        val destructures = when (pattern) {
            is Expr.MethodCall -> pattern.args.isNotEmpty()
            is Expr.Call -> pattern.args.isNotEmpty()
            else -> false
        }
        if (destructures) {
            error(
                "a `when` expression cannot destructure a slot payload at line ${start.line} - " +
                    "a binding has nowhere to live in an expression. Use `return when …` or a " +
                    "statement `when` instead."
            )
        }
        return Expr.Binary(scrutinee, TokenType.EQUAL_EQUAL, pattern, start.line, start.column)
    }

    private fun parseExprStmt(): Stmt {
        val start = peek()
        val expr = parseExpr()
        val opTok = peek()
        return when (opTok.type) {
            TokenType.EQUAL -> {
                advance()
                val value = parseExpr()
                consumeNewline()
                buildAssignment(expr, value, start.line, start.column)
            }
            TokenType.PLUS_EQUAL, TokenType.MINUS_EQUAL, TokenType.STAR_EQUAL,
            TokenType.SLASH_EQUAL, TokenType.PERCENT_EQUAL,
            TokenType.AMP_EQUAL, TokenType.PIPE_EQUAL, TokenType.CARET_EQUAL,
            TokenType.SHIFT_LEFT_EQUAL, TokenType.SHIFT_RIGHT_EQUAL -> {
                advance()
                val op = when (opTok.type) {
                    TokenType.PLUS_EQUAL -> TokenType.PLUS
                    TokenType.MINUS_EQUAL -> TokenType.MINUS
                    TokenType.STAR_EQUAL -> TokenType.STAR
                    TokenType.SLASH_EQUAL -> TokenType.SLASH
                    TokenType.PERCENT_EQUAL -> TokenType.PERCENT
                    TokenType.AMP_EQUAL -> TokenType.AMP
                    TokenType.PIPE_EQUAL -> TokenType.PIPE
                    TokenType.CARET_EQUAL -> TokenType.CARET
                    TokenType.SHIFT_LEFT_EQUAL -> TokenType.SHIFT_LEFT
                    TokenType.SHIFT_RIGHT_EQUAL -> TokenType.SHIFT_RIGHT
                    else -> error("unreachable compound assignment")
                }
                val value = parseExpr()
                consumeNewline()
                // Desugar `target op= value` into `target = target op value`
                val rhs = Expr.Binary(expr, op, value, start.line, start.column, start.lexeme.length)
                buildAssignment(expr, rhs, start.line, start.column, compoundOp = op)
            }
            // Null-conditional coalescing assignment: `target ?= value` → `target = target ?: value`
            TokenType.QMARK_EQUAL -> {
                advance()
                val value = parseExpr()
                consumeNewline()
                val rhs = Expr.NullCoalesce(expr, value, start.line, start.column, start.lexeme.length)
                buildAssignment(expr, rhs, start.line, start.column)
            }
            // Null-conditional compound assignment:
            // `target ?+= value` → `if (target != null) { target = target + value }`
            TokenType.QMARK_PLUS_EQUAL, TokenType.QMARK_MINUS_EQUAL, TokenType.QMARK_STAR_EQUAL,
            TokenType.QMARK_SLASH_EQUAL, TokenType.QMARK_PERCENT_EQUAL -> {
                advance()
                val op = when (opTok.type) {
                    TokenType.QMARK_PLUS_EQUAL -> TokenType.PLUS
                    TokenType.QMARK_MINUS_EQUAL -> TokenType.MINUS
                    TokenType.QMARK_STAR_EQUAL -> TokenType.STAR
                    TokenType.QMARK_SLASH_EQUAL -> TokenType.SLASH
                    TokenType.QMARK_PERCENT_EQUAL -> TokenType.PERCENT
                    else -> error("unreachable nullable compound assignment")
                }
                val value = parseExpr()
                consumeNewline()
                nullConditionalCompound(expr, op, value, start)
            }
            else -> {
                // ++ / -- on an identifier: desugar to x = x + 1 / x = x - 1
                if (expr is Expr.Identifier) {
                    when {
                        match(TokenType.PLUS_PLUS) -> {
                            consumeNewline()
                            val rhs = Expr.Binary(expr, TokenType.PLUS, Expr.IntLiteral(1, start.line), start.line)
                            return Stmt.Assignment(expr.name, rhs, start.line, start.column)
                        }
                        match(TokenType.MINUS_MINUS) -> {
                            consumeNewline()
                            val rhs = Expr.Binary(expr, TokenType.MINUS, Expr.IntLiteral(1, start.line), start.line)
                            return Stmt.Assignment(expr.name, rhs, start.line, start.column)
                        }
                    }
                }
                // Null-conditional inc/dec on any assignable target:
                // `target ?++` → `if (target != null) { target = target + 1 }`
                when (opTok.type) {
                    TokenType.QMARK_PLUS_PLUS -> {
                        advance()
                        consumeNewline()
                        nullConditionalIncDec(expr, TokenType.PLUS, start)
                    }
                    TokenType.QMARK_MINUS_MINUS -> {
                        advance()
                        consumeNewline()
                        nullConditionalIncDec(expr, TokenType.MINUS, start)
                    }
                    else -> {
                        consumeNewline()
                        Stmt.ExprStmt(expr, start.line, start.column)
                    }
                }
            }
        }
    }

    /**
     * `target ?<op>= value` - perform the compound assignment only when [target] is
     * non-null. Desugars to `if (target != null) { target = target <op> value }`,
     * which lowers to existing IR (If/Assignment/Binary), so all backends support it.
     */
    private fun nullConditionalCompound(target: Expr, op: TokenType, value: Expr, start: Token): Stmt {
        val rhs = Expr.Binary(target, op, value, start.line, start.column, start.lexeme.length)
        val assign = buildAssignment(target, rhs, start.line, start.column)
        val cond = Expr.Binary(target, TokenType.BANG_EQUAL, Expr.NullLiteral, start.line, start.column, start.lexeme.length)
        return Stmt.If(cond, listOf(assign), null, start.line, start.column)
    }

    /** `target ?++` / `target ?--` - increment/decrement only when [target] is non-null. */
    private fun nullConditionalIncDec(target: Expr, op: TokenType, start: Token): Stmt =
        nullConditionalCompound(target, op, Expr.IntLiteral(1, start.line, start.column, start.lexeme.length), start)

    /**
     * Builds an assignment statement from a parsed lvalue expression.
     * Supports simple variables (`x = v`), index targets (`a[i] = v`),
     * and member targets (`o.f = v`).
     */
    private fun buildAssignment(
        target: Expr,
        value: Expr,
        line: Int,
        column: Int,
        compoundOp: TokenType? = null,
    ): Stmt {
        return when (target) {
            is Expr.Identifier -> Stmt.Assignment(target.name, value, line, column, compoundOp = compoundOp)
            is Expr.Index ->
                Stmt.IndexAssign(target.target, target.index, value, line, column, compoundOp = compoundOp)
            is Expr.Member ->
                Stmt.MemberAssign(
                    target.target, target.name, value, line, column,
                    nameExpr = target.nameExpr, compoundOp = compoundOp,
                )
            is Expr.Deref -> Stmt.DerefAssign(target.target, value, line, column)
            else -> error("Invalid assignment target at line $line")
        }
    }

    // -----------------------------------------------------------------------
    // Expressions (precedence climbing)
    // -----------------------------------------------------------------------

    private fun parseExpr(): Expr {
        var e = parseOr()
        // `??` - null-coalesce
        while (check(TokenType.QMARK_QMARK)) {
            advance()
            val right = parseOr()
            e = Expr.NullCoalesce(e, right, e.line)
        }
        while (match(TokenType.CATCH)) {
            val fallback = parseOr()
            e = Expr.CatchExpr(e, fallback, e.line, e.column, e.length)
        }
        return e
    }

    /**
     * Postfix type operators `as` / `is` / `is!`. These bind tighter than the
     * arithmetic/comparison/logical operators (so `x is T && y is U` and
     * `!x is T` work as expected) but looser than member access and calls.
     */
    private fun parseAsIs(): Expr {
        var e = parseUnary()
        while (check(TokenType.AS) || check(TokenType.IS) || (inOperatorEnabled && check(TokenType.IN))) {
            if (check(TokenType.IN)) {
                val inTok = advance()
                // The collection is parsed at range precedence: `N in 2..4` asks about
                // the whole range, and `parseUnary` would stop at the `2`.
                e = Expr.InCheck(e, parseRange(), line = inTok.line, column = inTok.column)
                continue
            }
            val op = advance()
            if (op.type == TokenType.AS) {
                // `as` (static), `as?` (dynamic → T?), `as*` (reinterpret).
                val kind = when {
                    match(TokenType.QMARK) -> CastKind.DYNAMIC
                    match(TokenType.STAR) -> CastKind.REINTERPRET
                    else -> CastKind.STATIC
                }
                val targetType = parseTypeName()
                e = Expr.Cast(e, targetType, kind, line = e.line)
            } else {
                // `is` - optionally negated with `!`: `expr is! Type`
                val negated = match(TokenType.BANG)
                val typeName = parseQualifiedTypeName("Expected type name after 'is'")
                val check = Expr.IsCheck(e, typeName, e.line)
                e = if (negated) {
                    Expr.Unary(TokenType.BANG, check, op.line, op.column, op.lexeme.length + 1)
                } else {
                    check
                }
            }
        }
        return e
    }

    private fun parseOr(): Expr {
        var left = parseAnd()
        while (match(TokenType.OR_OR)) {
            val right = parseAnd()
            left = Expr.Binary(left, TokenType.OR_OR, right, left.line)
        }
        return left
    }

    private fun parseAnd(): Expr {
        var left = parseBitwiseOr()
        while (match(TokenType.AND_AND)) {
            val right = parseBitwiseOr()
            left = Expr.Binary(left, TokenType.AND_AND, right, left.line)
        }
        return left
    }

    private fun parseBitwiseOr(): Expr {
        var left = parseBitwiseXor()
        while (check(TokenType.PIPE)) {
            val op = advance().type
            val right = parseBitwiseXor()
            left = Expr.Binary(left, op, right, left.line)
        }
        return left
    }

    private fun parseBitwiseXor(): Expr {
        var left = parseBitwiseAnd()
        while (match(TokenType.CARET)) {
            val right = parseBitwiseAnd()
            left = Expr.Binary(left, TokenType.CARET, right, left.line)
        }
        return left
    }

    private fun parseBitwiseAnd(): Expr {
        var left = parseEquality()
        while (match(TokenType.AMP)) {
            val right = parseEquality()
            left = Expr.Binary(left, TokenType.AMP, right, left.line)
        }
        return left
    }

    private fun parseEquality(): Expr {
        var left = parseComparison()
        while (check(TokenType.EQUAL_EQUAL) || check(TokenType.BANG_EQUAL)) {
            val op = advance().type
            val right = parseComparison()
            left = Expr.Binary(left, op, right, left.line)
        }
        return left
    }

    private fun parseComparison(): Expr {
        var left = parseSpaceship()
        while (check(TokenType.LESS) || check(TokenType.LESS_EQUAL) ||
               check(TokenType.GREATER) || check(TokenType.GREATER_EQUAL)
        ) {
            val op = advance().type
            val right = parseSpaceship()
            left = Expr.Binary(left, op, right, left.line)
        }
        return left
    }

    /**
     * `a <=> b` - three-way comparison, binding tighter than the relational
     * operators and looser than the infix/shift levels. So `a <=> b == .Less`
     * groups as `(a <=> b) == .Less`, which is the reading the `<`/`<=`/`>`/`>=`
     * rewrites rely on.
     *
     * Non-associative: `a <=> b <=> c` is an error rather than a left fold,
     * because the result is a `Compare` and chaining one is `then`/`thenBy`.
     */
    private fun parseSpaceship(): Expr {
        val left = parseInfix()
        if (!check(TokenType.SPACESHIP)) return left
        val op = advance()
        val right = parseInfix()
        if (check(TokenType.SPACESHIP)) {
            error(
                "'<=>' does not chain at line ${peek().line}; " +
                    "use '(a <=> b).thenBy { b <=> c }'",
            )
        }
        return Expr.Binary(left, op.type, right, left.line)
    }

    /**
     * Infix method calls: `a plus b` → `a.plus(b)`.
     * Any IDENTIFIER followed by an expression-start token is treated as an infix method call.
     */
    private fun parseInfix(): Expr {
        var left = parseShift()
        // `where` introduces a clause wherever it appears - on a pack, an impl, a
        // function or a field - so it is never an infix method name. Without this,
        // `var w: Int = 4 where N == 4` parses its default as `4.where(N == 4)`.
        while (true) {
            val atForm = check(TokenType.AT) && isAtInfixCandidate()
            if (!atForm && !(check(TokenType.IDENTIFIER) && peek().lexeme != "where" && isInfixCandidate())) break
            // `a @to b` - the `@` leads on an infix macro's call just as it leads
            // on its declaration. It cannot be a loop label here: a label only
            // opens a statement, and this position already has a left operand.
            if (atForm) advance()
            val methodName = advance().lexeme
            val right = parseShift()
            left = Expr.MethodCall(left, methodName, listOf(right), left.line, left.column)
        }
        return left
    }

    /**
     * `@name` between two operands: an infix macro call written with its sigil.
     *
     * A decorator is spelled the same way, but its name must start with an
     * uppercase letter - the parser rejects `@arr` as a decorator and says so -
     * so a lowercase name after `@` is never one. Without that test,
     * `@Experimental(since: "0.1")` on the line after a property's value would
     * be swallowed as an infix call on it.
     */
    private fun isAtInfixCandidate(): Boolean {
        val name = peekNext()?.takeIf { it.type == TokenType.IDENTIFIER } ?: return false
        val head = name.lexeme.firstOrNull() ?: return false
        if (!head.isLowerCase() && head != '_') return false
        val after = tokens.getOrNull(current + 2)?.type ?: return false
        return after in infixOperandStarts
    }

    private fun isInfixCandidate(): Boolean {
        val next = peekNext()?.type ?: return false
        return next in infixOperandStarts
    }

    /** Tokens that can open the right operand of an infix call. */
    private val infixOperandStarts = setOf(
        TokenType.IDENTIFIER, TokenType.INT_LITERAL, TokenType.DOUBLE_LITERAL,
        TokenType.STRING_LITERAL, TokenType.CHAR_LITERAL, TokenType.TRUE, TokenType.FALSE,
        TokenType.NULL, TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE,
        TokenType.MINUS, TokenType.BANG, TokenType.TILDE,
    )

    private fun parseShift(): Expr {
        var left = parseRange()
        while (check(TokenType.SHIFT_LEFT) || check(TokenType.SHIFT_RIGHT)) {
            val op = advance().type
            val right = parseRange()
            left = Expr.Binary(left, op, right, left.line)
        }
        return left
    }

    private fun parseRange(): Expr {
        var left = parseAddition()
        while (check(TokenType.DOT_DOT) || check(TokenType.DOT_DOT_LESS)) {
            val inclusive = advance().type == TokenType.DOT_DOT
            val right = parseAddition()
            left = Expr.Range(left, right, inclusive, left.line)
        }
        return left
    }

    private fun parseAddition(): Expr {
        var left = parseMultiplication()
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            val op = advance().type
            val right = parseMultiplication()
            left = Expr.Binary(left, op, right, left.line)
        }
        return left
    }

    private fun parseMultiplication(): Expr {
        var left = parseAsIs()
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
            val op = advance().type
            val right = parseAsIs()
            left = Expr.Binary(left, op, right, left.line)
        }
        return left
    }

    private fun isReflectName(name: String): Boolean =
        name.endsWith("__reflect") || (typeFunctionNamespacePrefix.isNotEmpty() && name == "reflect")

    /** After `<`, whether the tokens spell a reflect target `Ident(::Ident)*>` or the whole-program `*`. */
    private fun isReflectTargetAhead(): Boolean {
        var i = current + 1
        // `reflect<*>` - the whole program, used with `.withAnnot<D>` enumeration.
        if (tokens.getOrNull(i)?.type == TokenType.STAR) {
            return tokens.getOrNull(i + 1)?.type == TokenType.GREATER
        }
        if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return false
        i++
        while (tokens.getOrNull(i)?.type == TokenType.DOUBLE_COLON) {
            if (tokens.getOrNull(i + 1)?.type != TokenType.IDENTIFIER) return false
            i += 2
        }
        // `reflect<Vec<U, N>>` - the target may be a generic application, so the
        // arguments are skipped over before looking for the closing `>`.
        if (tokens.getOrNull(i)?.type == TokenType.LESS) {
            // The inner application is skipped whole. A `>>` closes two levels, so it
            // can end the inner application and the `reflect<…>` around it at once.
            var depth = 1
            i++
            while (depth > 0) {
                when (tokens.getOrNull(i)?.type) {
                    TokenType.LESS -> depth++
                    TokenType.GREATER -> depth--
                    TokenType.SHIFT_RIGHT -> depth -= 2
                    null, TokenType.EOF -> return false
                    else -> {}
                }
                i++
            }
            // Depth below zero means the `>>` already closed `reflect<…>` too.
            if (depth < 0) return true
        }
        return tokens.getOrNull(i)?.type == TokenType.GREATER
    }

    /**
     * Parses an error-set name in a failable type, accepting the realm-qualified
     * form. A type declared inside `realm std` is only reachable from outside as
     * `std::SerializationError`, so an error set has to spell it that way too or
     * `impl Serializer<T> for MyCodec` cannot be written outside the standard
     * library. `Realm::Name` mangles to `Realm__Name`, matching type paths.
     */
    private fun parseErrorSetName(context: String): String {
        var name = consume(TokenType.IDENTIFIER, "Expected error-set name $context").lexeme
        while (match(TokenType.DOUBLE_COLON)) {
            name += "__" + consume(TokenType.IDENTIFIER, "Expected error-set name after '::'").lexeme
        }
        return name
    }

    /** Parses `X` / `X::member` inside `reflect<…>` into the mangled identifier. */
    private fun parseReflectTarget(): Expr {
        val start = peek()
        // `reflect<*>` - a whole-program handle for `.withAnnot<D>` enumeration.
        if (match(TokenType.STAR)) return Expr.Identifier("*", start.line, start.column, 1)
        var name = consume(TokenType.IDENTIFIER, "Expected a name inside 'reflect<...>'").lexeme
        while (match(TokenType.DOUBLE_COLON)) {
            name += "__" + consume(TokenType.IDENTIFIER, "Expected name after '::' in 'reflect<...>'").lexeme
        }
        // A generic application (`reflect<Vec<U, N>>`) keeps its arguments: the layout
        // being asked about is the one they choose, not the template's.
        reflectTargetArgs = parseGenericTypeArgsIfPresent()
        return Expr.Identifier(name, start.line, start.column, name.length)
    }

    /** The type arguments of the reflect target most recently parsed. */
    private var reflectTargetArgs: List<TypeRef> = emptyList()

    private fun parseUnary(): Expr {
        // `...T` in expression position names the variadic pack itself, so
        // `(...T).length` reads as a question about the pack rather than a spread.
        if (check(TokenType.ELLIPSIS)) {
            advance()
            return parseUnary()
        }
        if (check(TokenType.TRY)) {
            val at = advance()
            return Expr.TryPropagate(parseUnary(), at.line, at.column, at.lexeme.length)
        }
        // `alloc <expr>` - heap-allocate a single value (e.g. `alloc P(10)`, `alloc 42`).
        // `alloc T[N]` - allocate a buffer of N T's → T* (C++ `new T[N]`-style).
        if (check(TokenType.ALLOC)) {
            val at = advance()
            // `alloc* T(…)` yields a `T*`, `alloc^ T(…)` a `T^`. The sigil matches
            // the type it produces, so the allocation says which one it made.
            val mutable = when {
                match(TokenType.CARET) -> true
                match(TokenType.STAR) -> false
                else -> false
            }
            val operand = parseUnary()
            if (operand is Expr.Index && operand.target is Expr.Identifier) {
                return Expr.AllocBuffer(operand.target.name, operand.index, at.line, at.column, at.lexeme.length)
            }
            return Expr.Alloc(operand, at.line, at.column, at.lexeme.length, mutable = mutable)
        }
        // `take <expr>` - transfer ownership out of the operand.
        //
        // There is no move checker yet, so this carries no runtime effect: the
        // value is the operand's. The keyword is accepted now so ownership
        // transfer can be *written* where it happens, the way the call-site
        // borrow markers `&`/`!` already are.
        if (check(TokenType.TAKE)) {
            val at = advance()
            return Expr.Isolated(parseUnary(), at.line, at.column, at.lexeme.length, OwnershipOp.TAKE)
        }
        // `*ptr` / `deref ptr` - pointer or smart-pointer dereference.
        if (check(TokenType.STAR)) {
            val at = advance()
            return Expr.Deref(parseUnary(), at.line, at.column, at.lexeme.length)
        }
        // There is no `#expr` hash operator: hashing is `Hash`'s `prop hash`,
        // reached as `value.hash`. `#` survives only as the container-macro
        // sigil (`@std::set[…]`), which `parseMetaPrefix` reads.
        // `lend x` - ownership the callee gives back (§13). Contextual: the word
        // only means this when a name follows it, so `lend` stays usable as one.
        if (check(TokenType.IDENTIFIER) && peek().lexeme == "lend" && peekNext()?.type == TokenType.IDENTIFIER) {
            val at = advance()
            return Expr.Isolated(parseUnary(), at.line, at.column, at.lexeme.length, op = OwnershipOp.LEND)
        }
        // `delay <ms>` - suspend the current task for that many milliseconds.
        if (check(TokenType.DELAY)) {
            val at = advance()
            return Expr.Call("__delay", listOf(parseUnary()), at.line, at.column, at.lexeme.length)
        }
        // `await task` - suspend until the task completes.
        if (check(TokenType.AWAIT)) {
            val at = advance()
            return Expr.Await(parseUnary(), at.line, at.column, at.lexeme.length)
        }
        // `inject Type` is an ordinary expression. Deferral belongs to its
        // binding: `lazy fin service = inject Service`.
        if (check(TokenType.INJECT)) {
            val at = advance()
            val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'inject'").lexeme
            // Chain into parsePostfix so `inject Config.get()` works.
            return parsePostfix(Expr.Inject(typeName, at.line, at.column, at.lexeme.length))
        }
        if (check(TokenType.LAZY)) {
            error(
                "'lazy' modifies a binding at line ${peek().line}; " +
                    "write 'lazy fin name = expression' or 'lazy let name = expression'",
            )
        }
        if (check(TokenType.BANG) && peekNext()?.type == TokenType.L_BRACKET) {
            val at = advance()
            advance() // '['
            val elements = mutableListOf<Expr>()
            if (!check(TokenType.R_BRACKET)) {
                do { elements += parseExpr() } while (match(TokenType.COMMA))
            }
            consume(TokenType.R_BRACKET, "Expected ']' after set elements")
            return Expr.SetLiteral(elements, at.line, at.column, at.lexeme.length)
        }
        if (check(TokenType.BANG) || check(TokenType.MINUS) || check(TokenType.TILDE)) {
            val op = advance()
            val operand = parseUnary()
            return Expr.Unary(op.type, operand, op.line, op.column, op.lexeme.length)
        }
        return parsePostfix()
    }

    /**
     * Postfix chain: member access (`a.b`), indexing (`a[i]`), and calls (`f(...)`,
     * `a.m(...)`). Repeated left-associatively, e.g. `a.b[i].c()`.
     */
    private fun parsePostfix(initial: Expr? = null): Expr {
        var expr = initial ?: parsePrimary()
        var pendingCallTypeArgs: List<TypeRef> = emptyList()
        while (true) {
            // A fragment macro stands for source, so it is expanded before the
            // stream is read any further.
            if (spliceFragmentMacro()) continue
            // A chain may be broken across lines before each `.`:
            //
            //     return .()
            //         .weight(1.0)
            //         .fillMaxHeight()
            //
            // The newline is not the end of the expression when a `.` follows
            // it, because nothing else can begin with one - a statement opening
            // with a dot would have no receiver. Looking past the newlines here
            // keeps the reading unambiguous without a continuation marker.
            if (check(TokenType.NEWLINE)) {
                var ahead = current
                while (tokens.getOrNull(ahead)?.type == TokenType.NEWLINE) ahead++
                if (tokens.getOrNull(ahead)?.type == TokenType.DOT &&
                    tokens.getOrNull(ahead + 1)?.type != TokenType.AMP &&
                    tokens.getOrNull(ahead + 1)?.type != TokenType.BANG
                ) {
                    current = ahead
                }
            }
            when {
                // Call-site borrow markers, in either spelling: `x.&` / `x.!` (the
                // ownership model's) and the bare `x&` / `x!`. The bare form is only
                // a borrow when a delimiter ends the expression, so `a & b` stays
                // bitwise-and; the dotted form is unambiguous and needs no such test.
                // Borrows are erased - the language does no call-site borrow
                // checking yet - so both unwrap to the inner expression.
                check(TokenType.DOT) &&
                    (peekNext()?.type == TokenType.AMP || peekNext()?.type == TokenType.BANG) -> {
                    advance() // '.'
                    val sigil = advance() // '&' or '!'
                    expr = Expr.Isolated(expr, sigil.line, sigil.column, 2, borrowOp(sigil.type))
                }
                (check(TokenType.AMP) || check(TokenType.BANG)) &&
                    peekNext()?.type in BORROW_TERMINATORS -> {
                    val sigil = advance() // '&' or '!'
                    expr = Expr.Isolated(expr, sigil.line, sigil.column, 1, borrowOp(sigil.type))
                }
                check(TokenType.DOT) -> {
                    val dot = advance()
                    // `p.*` reads through a const pointer, `p.^` through a mutable
                    // one. The sigil matches the pointer type it came from, so the
                    // read says which kind of pointer it went through.
                    if (check(TokenType.STAR) || check(TokenType.CARET)) {
                        val sigil = advance()
                        expr = Expr.Deref(expr, expr.line, expr.column, dot.lexeme.length + sigil.lexeme.length)
                        continue
                    }
                    // `target.${ expr }` - the member name is computed. `$` lexes as an
                    // identifier, so the splice is `$` followed by a brace group.
                    if (check(TokenType.IDENTIFIER) && peek().lexeme == "$" &&
                        peekNext()?.type == TokenType.L_BRACE
                    ) {
                        advance() // '$'
                        advance() // '{'
                        val nameExpr = parseExpr()
                        consume(TokenType.R_BRACE, "Expected '}' to close a spliced member name")
                        expr = Expr.Member(expr, "", expr.line, expr.column, 0, nameExpr)
                        continue
                    }
                    if (check(TokenType.INT_LITERAL)) {
                        val idx = (advance().literal as NumericLiteral).value as Long
                        expr = Expr.Member(expr, idx.toString(), expr.line, expr.column, dot.lexeme.length + idx.toString().length)
                    } else {
                        val name = consumeMemberName("Expected member name after '.'")
                        expr = Expr.Member(expr, name, expr.line, expr.column, dot.lexeme.length + name.length)
                    }
                }
                check(TokenType.QMARK_DOT) -> {
                    advance()
                    val name = consumeMemberName("Expected member name after '?.'")
                    expr = Expr.SafeMember(expr, name, expr.line, expr.column)
                }
                // `Array<Int, 3>::size` - a static member of an *applied* type. The
                // arguments are part of which specialization is being asked, so they
                // are carried on the access rather than dropped.
                check(TokenType.LESS) && expr is Expr.Identifier && isGenericStaticAccessAhead() -> {
                    val args = parseGenericTypeArgsIfPresent()
                    consume(TokenType.DOUBLE_COLON, "Expected '::' after the type arguments")
                    val member = consumeIdentifierLike("Expected member name after '::'")
                    val owner = (expr as Expr.Identifier).name
                    expr = Expr.Identifier("${owner}__$member", expr.line, expr.column, owner.length + 2 + member.length)
                    pendingCallTypeArgs = args
                }
                check(TokenType.DOUBLE_COLON) -> {
                    // Namespace member access `Name::member` → mangled identifier `Name__member`.
                    advance() // '::'
                    val member = consumeIdentifierLike("Expected member name after '::'")
                    expr = when (expr) {
                        is Expr.Identifier -> Expr.Identifier("${expr.name}__$member", expr.line, expr.column, expr.length + 2 + member.length)
                        else -> error("'::' must follow a namespace name at line ${peek().line}")
                    }
                }
                allowTrailingLambda && check(TokenType.L_BRACKET) && isReceiverLambdaAhead() -> {
                    val lambda = parseReceiverLambda()
                    expr = appendTrailingLambda(expr, lambda, pendingCallTypeArgs)
                    pendingCallTypeArgs = emptyList()
                }
                check(TokenType.L_BRACKET) -> {
                    advance()
                    // `a[start:stop:step]` - a Python-style slice → oper[:]. An absent
                    // bound is `null` (open-ended). A bare `a[i]` (no colon) stays an Index.
                    if (check(TokenType.COLON)) {
                        val stop = parseExpr()
                        val step = if (match(TokenType.COLON)) parseExpr() else null
                        consume(TokenType.R_BRACKET, "Expected ']' after slice")
                        expr = Expr.Slice(expr, null, stop, step, expr.line, expr.column)
                    } else {
                        val first = parseExpr()
                        if (match(TokenType.COLON)) {
                            val stop = if (check(TokenType.R_BRACKET)) null else parseExpr()
                            val step = if (match(TokenType.COLON)) parseExpr() else null
                            consume(TokenType.R_BRACKET, "Expected ']' after slice")
                            expr = Expr.Slice(expr, first, stop, step, expr.line, expr.column)
                        } else {
                            consume(TokenType.R_BRACKET, "Expected ']' after index")
                            expr = Expr.Index(expr, first, expr.line, expr.column)
                        }
                    }
                }
                check(TokenType.LESS) && expr is Expr.Member && expr.name in setOf("hasAnnot", "annotMeta", "withAnnot") -> {
                    val innerTarget = (expr.target as? Expr.Grouping)?.expr ?: expr.target
                    // `std::reflect<Type>.field` accesses a member of the reflect handle;
                    // reflected declaration members must go inside the angle brackets
                    // with `::` (`std::reflect<Type::field>`).
                    if (innerTarget is Expr.Member &&
                        (innerTarget.target as? Expr.Call)?.callee == "__reflect"
                    ) {
                        error("Reflected declaration members use '::', for example 'std::reflect<Type::${innerTarget.name}>' at line ${expr.line}")
                    }
                    val reflected = (innerTarget as? Expr.Call)?.takeIf { it.callee == "__reflect" }
                        ?: error("'${expr.name}' requires an explicit std::reflect<receiver>, for example 'std::reflect<Type>.${expr.name}<Decorator>' at line ${expr.line}")
                    val typeArgs = parseGenericTypeArgsIfPresent()
                    if (typeArgs.size != 1) {
                        error("'${expr.name}' expects exactly one decorator type argument at line ${expr.line}")
                    }
                    val intrinsic = when (expr.name) {
                        "hasAnnot" -> "__hasAnnot"
                        "annotMeta" -> "__annotMeta"
                        else -> "__withAnnot"
                    }
                    expr = Expr.Call(intrinsic, reflected.args, expr.line, expr.column, expr.length, typeArgs)
                }
                check(TokenType.LESS) && expr is Expr.Identifier &&
                    isReflectName((expr as Expr.Identifier).name) && isReflectTargetAhead() -> {
                    // `std::reflect<X>` - compile-time reflection prop. Desugars to the
                    // internal `__reflect(X)` receiver used by `.hasAnnot`/`.annotMeta`/`.realm`.
                    val start = expr
                    advance() // '<'
                    val target = parseReflectTarget()
                    if (pendingGreater) {
                        pendingGreater = false
                    } else {
                        consume(TokenType.GREATER, "Expected '>' to close 'reflect<...>'")
                    }
                    expr = Expr.Call(
                        "__reflect",
                        listOf(target),
                        start.line,
                        start.column,
                        start.length,
                        reflectTargetArgs,
                    )
                }
                check(TokenType.LESS) && isGenericCallAhead() -> {
                    // `f<T, U>(args)` - capture explicit type arguments for the call
                    // (e.g. `tupleOf<Int, Double>(…)`) for monomorphization; the '(' is
                    // then handled by the call case below.
                    advance() // '<'
                    val tArgs = mutableListOf<TypeRef>()
                    if (!check(TokenType.GREATER) && !check(TokenType.SHIFT_RIGHT) && !pendingGreater) {
                        do {
                            tArgs.add(parseTypeArg())
                        } while (match(TokenType.COMMA))
                        if (check(TokenType.ELLIPSIS)) {
                            error("Variadic type arguments use the prefix form '<...T>', not '<T...>', at line ${peek().line}")
                        }
                    }
                    when {
                        pendingGreater -> { pendingGreater = false }
                        check(TokenType.GREATER) -> { advance() }
                        check(TokenType.SHIFT_RIGHT) -> { advance(); pendingGreater = true }
                        else -> consume(TokenType.GREATER, "Expected '>' to close call type arguments")
                    }
                    pendingCallTypeArgs = tArgs
                }
                check(TokenType.L_PAREN) -> {
                    advance()
                    val args = mutableListOf<Expr>()
                    // Arguments are separated by a comma or by a line break. A `(`
                    // suppresses newline tokens, so a line break leaves nothing between
                    // two arguments - the list therefore runs until `)` and treats the
                    // comma as optional, which is what makes the multi-line call form
                    // read the way it is written.
                    while (!check(TokenType.R_PAREN) && !isAtEnd()) {
                        // `inline for f in … { expr }` as an argument - one argument per
                        // iteration, expanded when the iteration is known.
                        if (check(TokenType.INLINE) && peekNext()?.type == TokenType.FOR) {
                            val forStart = advance()
                            advance() // 'for'
                            val loopName = consumeIdentifierLike("Expected loop variable in argument 'inline for'")
                            consume(TokenType.IN, "Expected 'in' after argument 'inline for' variable")
                            // The `{` opens the loop body, not a trailing lambda on
                            // the iterable - `inline for f in reflect<Self>.fields { … }`.
                            val iterable = withoutTrailingLambda { parseExpr() }
                            consume(TokenType.L_BRACE, "Expected '{' to open argument 'inline for' body")
                            skipNewlines()
                            val bodyExpr = parseExpr()
                            skipNewlines()
                            consume(TokenType.R_BRACE, "Expected '}' after argument 'inline for' body")
                            args.add(
                                Expr.InlineForArgs(loopName, iterable, bodyExpr, forStart.line, forStart.column),
                            )
                            match(TokenType.COMMA)
                            skipNewlines()
                            continue
                        }
                        // Spread: `...arr` - prefix splat of the array's elements into individual args.
                        val arg = if (match(TokenType.ELLIPSIS)) {
                            val first = parseExpr()
                            Expr.Spread(first, first.line, first.column, first.length)
                        } else {
                            val first = parseExpr()
                            if (first is Expr.Identifier && check(TokenType.COLON)) {
                                advance()
                                Expr.NamedArg(first.name, parseExpr(), first.line, first.column, first.length)
                            } else first
                        }
                        args.add(arg)
                        match(TokenType.COMMA)
                    }
                    consume(TokenType.R_PAREN, "Expected ')' after arguments")
                    // A fragment macro may stand where the block's markers go -
                    // `f(args) @children { … }` - so it is expanded here too,
                    // and not only at the top of the loop: the trailing block is
                    // read below without going back through it.
                    spliceFragmentMacro()
                    // Trailing lambda: `f(args) { x -> ... }` or
                    // `f(args) [context: Context&] { ... }`.
                    val trailingReact = (
                        allowTrailingLambda && check(TokenType.REACT) &&
                            (peekNext()?.type == TokenType.L_BRACE || peekNext()?.type == TokenType.L_BRACKET)
                        )
                    if (trailingReact) advance()
                    val trailingKind = if (trailingReact) CallableKind.REACT else CallableKind.FUNC
                    if (allowTrailingLambda && check(TokenType.L_BRACE)) {
                        val lb = peek()
                        val isAsync = expr is Expr.Identifier && expr.name == "async"
                        val lambda = parseLambda(lb.line, lb.column, implicitIt = !isAsync, kind = trailingKind)
                        args.add(lambda)
                    } else if (allowTrailingLambda && check(TokenType.L_BRACKET) && isReceiverLambdaAhead()) {
                        args.add(parseReceiverLambda(kind = trailingKind))
                    }
                    expr = when (expr) {
                        is Expr.Identifier -> {
                            val call = Expr.Call(expr.name, args, expr.line, expr.column, expr.length, pendingCallTypeArgs)
                            pendingCallTypeArgs = emptyList()
                            call
                        }
                        // `.Every(5.seconds())` - a variant of the expected type,
                        // with its payload. The arguments join the node so the
                        // resolver still sees one thing to give a type to.
                        is Expr.InferredMember -> expr.copy(ctorArgs = args)
                        is Expr.Member -> Expr.MethodCall(expr.target, expr.name, args, expr.line, expr.column)
                        // Calling an arbitrary expression value, e.g. `fs[0](x)` or
                        // `(getFn())(x)` - the receiver must be a function value.
                        else -> Expr.Call("", args, expr.line, expr.column, expr.length, receiver = expr)
                    }
                }
                // `f react { … }` / `f react [; &] { … }` without parentheses -
                // the same trailing block the parenthesised form accepts.
                allowTrailingLambda && check(TokenType.REACT) &&
                    (peekNext()?.type == TokenType.L_BRACE || peekNext()?.type == TokenType.L_BRACKET) -> {
                    advance() // 'react'
                    val lambda = if (check(TokenType.L_BRACKET) && isReceiverLambdaAhead()) {
                        parseReceiverLambda(kind = CallableKind.REACT)
                    } else {
                        val lb = peek()
                        parseLambda(lb.line, lb.column, kind = CallableKind.REACT)
                    }
                    expr = when (expr) {
                        is Expr.Identifier -> {
                            val call = Expr.Call(
                                expr.name, listOf(lambda), expr.line, expr.column, expr.length, pendingCallTypeArgs,
                            )
                            pendingCallTypeArgs = emptyList()
                            call
                        }
                        is Expr.Member -> Expr.MethodCall(expr.target, expr.name, listOf(lambda), expr.line, expr.column)
                        else -> Expr.Call("", listOf(lambda), expr.line, expr.column, expr.length, receiver = expr)
                    }
                }
                allowTrailingLambda && check(TokenType.L_BRACE) -> {
                    val lb = peek()
                    val isAsync = expr is Expr.Identifier && expr.name == "async"
                    val lambda = parseLambda(lb.line, lb.column, implicitIt = !isAsync)
                    expr = when (expr) {
                        is Expr.Identifier -> {
                            val call = Expr.Call(
                                expr.name,
                                listOf(lambda),
                                expr.line,
                                expr.column,
                                expr.length,
                                pendingCallTypeArgs,
                            )
                            pendingCallTypeArgs = emptyList()
                            call
                        }
                        is Expr.Member -> Expr.MethodCall(
                            expr.target,
                            expr.name,
                            listOf(lambda),
                            expr.line,
                            expr.column,
                            expr.length,
                        )
                        else -> Expr.Call(
                            "",
                            listOf(lambda),
                            expr.line,
                            expr.column,
                            expr.length,
                            receiver = expr,
                        )
                    }
                }
                else -> return expr
            }
        }
    }

    /** Adds a parenthesis-free trailing lambda to any callable expression. */
    private fun appendTrailingLambda(
        target: Expr,
        lambda: Expr.Lambda,
        typeArgs: List<TypeRef>,
    ): Expr = when (target) {
        is Expr.Identifier -> Expr.Call(
            target.name,
            listOf(lambda),
            target.line,
            target.column,
            target.length,
            typeArgs,
        )
        is Expr.Member -> Expr.MethodCall(
            target.target,
            target.name,
            listOf(lambda),
            target.line,
            target.column,
            target.length,
        )
        else -> Expr.Call(
            "",
            listOf(lambda),
            target.line,
            target.column,
            target.length,
            receiver = target,
        )
    }

    /**
     * Parses the argument list of a macro invocation `name!<delim> … <delim>`.
     * The opening delimiter (`(`/`[`/`{`) is the current token; arguments are a
     * comma-separated list of expressions with `...expr` spread support (mirrors
     * the call-argument grammar). The delimiter is consumed and not retained -
     * macro arms match delimiter-agnostically.
     */
    /**
     * True when an `@` opens a macro invocation rather than a decorator or label.
     *
     * `@name` followed by `(`, `[` or `{` - after an optional name sigil - is a
     * call; anything else is a decorator or a loop label.
     */
    private fun isMacroInvokeAhead(): Boolean {
        if (!check(TokenType.AT)) return false
        var i = current + 1
        if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return false
        i++
        while (tokens.getOrNull(i)?.type == TokenType.DOUBLE_COLON) {
            i++
            if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return false
            i++
        }
        if (tokens.getOrNull(i)?.type in setOf(
                TokenType.BANG, TokenType.QMARK, TokenType.AMP, TokenType.STAR, TokenType.CARET,
            )
        ) i++
        return tokens.getOrNull(i)?.type in setOf(
            TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE,
        )
    }

    private fun parseMacroInvokeArgs(): List<Expr> {
        val (close, closeStr) = when (peek().type) {
            TokenType.L_PAREN -> { advance(); TokenType.R_PAREN to ")" }
            TokenType.L_BRACKET -> { advance(); TokenType.R_BRACKET to "]" }
            TokenType.L_BRACE -> { advance(); TokenType.R_BRACE to "}" }
            else -> error("macro invocation expects '(', '[', or '{' at line ${peek().line}")
        }
        skipNewlines()
        val args = mutableListOf<Expr>()
        if (!check(close)) {
            do {
                skipNewlines()
                if (check(close)) break
                val arg = if (match(TokenType.ELLIPSIS)) {
                    val first = parseExpr()
                    Expr.Spread(first, first.line, first.column, first.length)
                } else {
                    val first = parseExpr()
                    // `@std::map[1: "one"]` - a keyed argument, for an arm written
                    // `[...${key: value}]`. The key is an expression, not a name, so
                    // this is not the `name = value` form a decorator takes.
                    if (match(TokenType.COLON)) {
                        val value = parseExpr()
                        Expr.MapEntryArg(first, value, first.line, first.column, first.length)
                    } else {
                        first
                    }
                }
                args.add(arg)
                skipNewlines()
            } while (match(TokenType.COMMA))
        }
        consume(close, "Expected '$closeStr' to close macro invocation")
        return args
    }

    /**
     * Bounded lookahead deciding whether `<` after a call target opens a type
     * argument list (`max<T>(…)`) rather than a comparison. True only when a
     * short run of type-ish tokens closes with `>` immediately followed by `(`.
     */
    /**
     * Whether the token after a closing `>` continues a generic application.
     *
     * `f<T>(args)` and `f<T> { body }` are calls; `reflect<T>.fields` reads a
     * member of one. All are applications of type arguments, so all keep `<`
     * from being read as less-than.
     */
    private fun closesGenericApplication(closingIndex: Int): Boolean =
        when (tokens.getOrNull(closingIndex + 1)?.type) {
            TokenType.L_PAREN, TokenType.L_BRACE, TokenType.DOT -> true
            TokenType.L_BRACKET -> isReceiverLambdaAhead(closingIndex + 1)
            else -> false
        }

    /**
     * True when `<…>::` follows - a static member reached through an applied type,
     * as in `Array<Int, 3>::size`.
     *
     * Distinguishes the application from a comparison by requiring the `::`, the
     * same way [isGenericCallAhead] requires a `(`.
     */
    private fun isGenericStaticAccessAhead(): Boolean {
        var i = current + 1
        var depth = 1
        var steps = 0
        while (i < tokens.size && steps < 48) {
            when (tokens[i].type) {
                TokenType.LESS -> depth++
                TokenType.GREATER -> {
                    depth--
                    if (depth == 0) return tokens.getOrNull(i + 1)?.type == TokenType.DOUBLE_COLON
                }
                TokenType.SHIFT_RIGHT -> {
                    depth -= 2
                    if (depth == 0) return tokens.getOrNull(i + 1)?.type == TokenType.DOUBLE_COLON
                    if (depth < 0) return false
                }
                TokenType.EOF -> return false
                else -> {}
            }
            i++
            steps++
        }
        return false
    }

    private fun isGenericCallAhead(): Boolean {
        var i = current + 1 // token after '<'
        var depth = 1
        // A type argument may itself be a callable type - `listOf<() -> Int>()` -
        // whose parentheses must balance before a `>` can close the argument list.
        var parens = 0
        var steps = 0
        while (i < tokens.size && steps < 48) {
            when (tokens[i].type) {
                TokenType.IDENTIFIER, TokenType.COMMA, TokenType.DOT,
                TokenType.L_BRACKET, TokenType.R_BRACKET, TokenType.QMARK,
                TokenType.COLON, TokenType.STAR, TokenType.INT_LITERAL,
                // A type argument may be realm-qualified: `ArrayList<std::SerialField>`.
                TokenType.DOUBLE_COLON,
                // `(A, B) -> R` as a type argument.
                TokenType.ARROW,
                // `tupleOf<Entity, ...T>(…)` - a type pack among the arguments.
                // Borrow sigils are deliberately *not* here: `a < b && c` would
                // read as a generic call, and a borrow in a type argument
                // already parses without help.
                TokenType.ELLIPSIS -> {}
                TokenType.L_PAREN -> parens++
                TokenType.R_PAREN -> {
                    parens--
                    if (parens < 0) return false
                }
                TokenType.LESS -> depth++
                TokenType.GREATER -> {
                    if (parens > 0) { i++; steps++; continue }
                    depth--
                    if (depth == 0) return closesGenericApplication(i)
                }
                // `>>` closes two nested generic levels at once (e.g. `f<G<T>>(args)`).
                TokenType.SHIFT_RIGHT -> {
                    if (parens > 0) { i++; steps++; continue }
                    depth -= 2
                    if (depth == 0) return closesGenericApplication(i)
                    if (depth < 0) return false
                }
                else -> return false
            }
            i++
            steps++
        }
        return false
    }

    /**
     * If-expression `if cond { a } else { b }` - used in expression position
     * (`return if …`, `let x = if …`, `func f() = if …`). Each branch holds a
     * single expression; `else if` chains nest naturally. Statement-position
     * `if` is unaffected (the statement parser checks for it first).
     */
    private fun parseIfExpr(): Expr {
        val start = consume(TokenType.IF, "Expected 'if'")
        // The branch's `{` must not be mistaken for a trailing lambda on a
        // condition that ends in a call - `if ready() { a } else { b }`.
        val condition = withoutTrailingLambda { parseExpr() }
        consume(TokenType.L_BRACE, "Expected '{' after if-expression condition")
        skipNewlines()
        val thenExpr = parseExpr()
        skipNewlines()
        consume(TokenType.R_BRACE, "Expected '}' after if-expression value")
        skipNewlines()
        consume(TokenType.ELSE, "Expected 'else' - an if-expression needs both branches")
        val elseExpr = if (check(TokenType.IF)) {
            parseIfExpr()
        } else {
            consume(TokenType.L_BRACE, "Expected '{' after 'else'")
            skipNewlines()
            val value = parseExpr()
            skipNewlines()
            consume(TokenType.R_BRACE, "Expected '}' after else-expression value")
            value
        }
        return Expr.IfExpr(condition, thenExpr, elseExpr, start.line, start.column)
    }

    private fun parsePrimary(): Expr {
        val tok = peek()
        // `.Name` - the expected type's member, named without repeating the
        // type. A primary expression never otherwise begins with a dot, so this
        // is unambiguous: a member access always has something on its left.
        if (tok.type == TokenType.DOT && peekNext()?.type == TokenType.IDENTIFIER) {
            advance()
            val name = advance()
            return Expr.InferredMember(name.lexeme, tok.line, tok.column, name.lexeme.length + 1)
        }
        // `.(args)` - the expected type's constructor, unnamed.
        if (tok.type == TokenType.DOT && peekNext()?.type == TokenType.L_PAREN) {
            advance()
            advance()
            val args = mutableListOf<Expr>()
            skipNewlines()
            while (!check(TokenType.R_PAREN) && !isAtEnd()) {
                val arg = parseExpr()
                args.add(
                    if (arg is Expr.Identifier && match(TokenType.COLON)) {
                        Expr.NamedArg(arg.name, parseExpr(), arg.line, arg.column)
                    } else arg,
                )
                match(TokenType.COMMA)
                skipNewlines()
            }
            consume(TokenType.R_PAREN, "Expected ')' after '.(' constructor arguments")
            return Expr.InferredMember("", tok.line, tok.column, 2, ctorArgs = args)
        }
        // `<T>[receivers; captures] { params -> body }` - a generic lambda.
        // A leading '<' has no other primary-expression meaning, so parsing it
        // here also gives a useful diagnostic for an incomplete generic lambda.
        if (tok.type == TokenType.LESS) {
            return parseGenericLambda()
        }
        // `@name[…]`, `@name(…)`, `@name{…}` - a local macro invocation. Realm
        // qualification follows the sigil: `@std::arr[…]`.
        // as it does on the declaration (`macro @name { … }`), so a call and the
        // thing it calls are spelled the same way round. A decorator is also
        // `@name`, but never followed by a delimiter in expression position.
        if (tok.type == TokenType.AT && isMacroInvokeAhead()) {
            val at = advance() // '@'
            val name = parseQualifiedMacroName()
            val args = parseMacroInvokeArgs()
            usedMetaInvoke = true
            return Expr.MetaInvoke(name, args, at.line, at.column, name.length + 1)
        }
        return when (tok.type) {
            TokenType.IF -> parseIfExpr()
            TokenType.WHEN -> parseWhenExpr()
            TokenType.INT_LITERAL -> {
                advance()
                val numLit = tok.literal as NumericLiteral
                Expr.IntLiteral(numLit.value as Long, tok.line, tok.column, tok.lexeme.length, numLit.suffix)
            }
            TokenType.DOUBLE_LITERAL -> {
                advance()
                val numLit = tok.literal as NumericLiteral
                Expr.DoubleLiteral(numLit.value as Double, tok.line, tok.column, tok.lexeme.length, numLit.suffix)
            }
            TokenType.STRING_LITERAL -> { advance(); Expr.StringLiteral(tok.literal as String, tok.line, tok.column, tok.lexeme.length) }
            TokenType.INTERPOLATED_STRING -> {
                advance()
                @Suppress("UNCHECKED_CAST")
                val parts = tok.literal as List<StringPart>
                val templateParts = parts.map { p ->
                    when (p) {
                        is StringPart.Literal -> Expr.StringTemplatePart.Literal(p.text)
                        is StringPart.Expr -> Expr.StringTemplatePart.Expr(parseSubExpr(p.source))
                    }
                }
                Expr.StringTemplate(templateParts, tok.line, tok.column, tok.lexeme.length)
            }
            TokenType.CHAR_LITERAL -> { advance(); Expr.CharLiteral(tok.literal as Char, tok.line, tok.column, tok.lexeme.length) }
            TokenType.TRUE -> { advance(); Expr.BoolLiteral(true, tok.line, tok.column, tok.lexeme.length) }
            TokenType.FALSE -> { advance(); Expr.BoolLiteral(false, tok.line, tok.column, tok.lexeme.length) }
            TokenType.NULL -> { advance(); Expr.NullLiteral }
            TokenType.FUNC -> error(
                "line ${tok.line}: lambdas do not use 'func'; write '{ ... }' or " +
                    "'{ params -> ... }'",
            )
            // A declaration may be `async func name`, but a lambda is simply
            // `async { ... }` or `async [receivers; captures] { ... }`.
            TokenType.IDENTIFIER if isAsyncFuncAt(current) -> error(
                "line ${tok.line}: async lambdas do not use 'func'; write " +
                    "'async { ... }' or 'async [; take] { ... }'",
            )
            TokenType.IDENTIFIER if tok.lexeme == "async" && peekNext()?.type == TokenType.LESS -> {
                advance()
                val lambda = parseGenericLambda()
                Expr.Call("async", listOf(lambda), tok.line, tok.column, tok.lexeme.length)
            }
            TokenType.IDENTIFIER,
            TokenType.REVERSE -> {
                advance()
                // `it` is the name a lambda gives its parameter when it has exactly
                // one and did not name it - so whether the innermost lambda body
                // mentions `it` is what decides whether it has that parameter at
                // all. Each lambda pushes its own frame, so a nested `{ it * 2 }`
                // marks the nested lambda and not the one around it.
                if (tok.lexeme == "it" && lambdaMentionsIt.isNotEmpty()) {
                    lambdaMentionsIt[lambdaMentionsIt.size - 1] = true
                }
                Expr.Identifier(tok.lexeme, tok.line, tok.column, tok.lexeme.length)
            }
            TokenType.DOUBLE_COLON -> {
                advance() // consume first '::'
                var depth = 1
                // ::_::_::x pattern - each _:: adds one depth level
                while (check(TokenType.IDENTIFIER) && peek().lexeme == "_" && peekNext()?.type == TokenType.DOUBLE_COLON) {
                    advance() // consume '_'
                    advance() // consume '::'
                    depth++
                }
                val name = consume(TokenType.IDENTIFIER, "Expected identifier after '::'")
                Expr.UpperScopeAccess(name.lexeme, depth, tok.line, tok.column)
            }
            TokenType.L_PAREN -> {
                advance()
                val first = parseExpr()
                if (check(TokenType.COMMA)) {
                    // Tuple-literal sugar `(a, b, …)` was removed: parentheses only
                    // group a single expression. Build tuples with `std::tupleOf(…)`
                    // or the `tup@` macro.
                    error("tuple literal '(a, b, …)' is not supported; use 'std::tupleOf(a, b, …)' or '@std::tup(a, b, …)' at line ${tok.line}")
                }
                consume(TokenType.R_PAREN, "Expected ')'")
                Expr.Grouping(first, tok.line, tok.column)
            }
            // `[self: Vec2&]{ … }` - a lambda binding named receivers.
            TokenType.L_BRACKET if isReceiverLambdaAhead() -> parseReceiverLambda()
            // `[2, 3].add()` - a receiver call supplying several contextual
            // receivers. The same list `with [2, 3] { … }` takes, written at the
            // call rather than around it.
            TokenType.L_BRACKET if isReceiverListCallAhead() -> {
                advance()
                val values = mutableListOf<Expr>()
                do { values.add(parseExpr()) } while (match(TokenType.COMMA))
                consume(TokenType.R_BRACKET, "Expected ']' after a receiver list")
                // Carried as an array literal: bare `[…]` is not a value anywhere
                // else, so the shape is free, and every pass already handles it.
                Expr.ArrayLiteral(values, tok.line, tok.column)
            }
            TokenType.L_BRACKET -> {
                advance()
                if (check(TokenType.R_BRACKET)) {
                    error("array literal '[]' is not valid; use '@std::arr[]' at line ${tok.line}")
                }
                val first = parseExpr()
                if (match(TokenType.COLON)) {
                    // Map literal: ["k": v, ...]
                    val entries = mutableListOf<Pair<Expr, Expr>>(first to parseExpr())
                    while (match(TokenType.COMMA)) {
                        val k = parseExpr()
                        consume(TokenType.COLON, "Expected ':' in map literal")
                        entries.add(k to parseExpr())
                    }
                    consume(TokenType.R_BRACKET, "Expected ']' after map literal")
                    Expr.MapLit(entries, tok.line, tok.column)
                } else {
                    // Value array literals are not a bracket sugar - `[…]` only groups
                    // types. Values use the `@std::arr` macro: `@std::arr[1, 2, 3]`.
                    error("array literal '[…]' is not valid; use '@std::arr[…]' at line ${tok.line}")
                }
            }
            TokenType.L_BRACE -> parseLambda(tok.line, tok.column)
            else -> error("Unexpected token '${tok.lexeme}' at line ${tok.line}")
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** `{ params -> body }`, `{ -> body }`, or `{ body }` (implicit `it`). */
    /**
     * True when a `[` opens a lambda's receiver binding list - `[self: Vec2&]{ … }`.
     *
     * Distinguished from a map literal by the `{` that must follow the matching
     * `]`, and from a type list by the `name:` shape of the first entry.
     */
    /**
     * True when a `[` opens a lambda's bracket list rather than an array literal.
     *
     * The list holds contextual receivers (`name: Type`) and captures (anything
     * else), so its entries cannot be told apart by their first token - what
     * decides it is the `{` after the matching `]`. Nothing valid parses that way
     * otherwise: a block is not a postfix operator on an array.
     */
    private fun isReceiverLambdaAhead(startIndex: Int = current): Boolean {
        var depth = 0
        var i = startIndex
        while (i < tokens.size) {
            when (tokens[i].type) {
                TokenType.L_BRACKET -> depth++
                TokenType.R_BRACKET -> {
                    depth--
                    if (depth == 0) return tokens.getOrNull(i + 1)?.type == TokenType.L_BRACE
                }
                TokenType.EOF -> return false
                else -> {}
            }
            i++
        }
        return false
    }

    /**
     * `[self: Vec2&]{ body }` / `[self: Vec2&]{ other: Vec2& -> body }` - a lambda
     * that binds named receivers, usable only where those contexts are supplied.
     *
     * The bracket list is the same receiver spelling a `func` uses, so a callable
     * value and a declaration name their receiver identically. Ordinary parameters
     * still go inside the braces before `->`.
     */
    /** `[receivers; captures] { ... }` - the semicolon is the unambiguous boundary. */
    private fun parseReceiverLambda(
        typeParams: List<String> = emptyList(),
        variadicTypeParam: String? = null,
        kind: CallableKind = CallableKind.FUNC,
    ): Expr.Lambda {
        val start = advance() // '['
        val receivers = mutableListOf<Param>()
        val captures = mutableListOf<Capture>()
        var default: CaptureMode? = null
        // Before `;`, every entry is a receiver. A bare receiver gets its type
        // from the expected contextual-function type.
        if (!check(TokenType.SEMICOLON) && !check(TokenType.R_BRACKET)) {
            do {
                val entry = peek()
                if (entry.type in setOf(TokenType.EQUAL, TokenType.AMP, TokenType.BANG, TokenType.TAKE)) {
                    error("line ${entry.line}: captures must follow ';' in a lambda bracket list; write '[; ${entry.lexeme}]'")
                }
                if (entry.type == TokenType.ELLIPSIS) {
                    error("line ${entry.line}: contextual receivers cannot be variadic")
                }
                val name = consumeIdentifierLike("Expected a contextual receiver before ';'")
                if (match(TokenType.COLON)) {
                    val (type, modifier) = parseReceiverTypeAndModifier()
                    receivers.add(Param(name, type, modifier = modifier))
                } else {
                    if (check(TokenType.DOT) || check(TokenType.AMP) || check(TokenType.BANG)) {
                        error(
                            "line ${entry.line}: capture '$name' must follow ';'; " +
                                "write '[; $name.&]', '[; $name.!]', or '[; $name.clone()]'",
                        )
                    }
                    receivers.add(Param(name, TypeRef.Named("Any", synthesized = true)))
                }
            } while (match(TokenType.COMMA) && !check(TokenType.SEMICOLON) && !check(TokenType.R_BRACKET))
        }
        if (match(TokenType.SEMICOLON)) {
            if (!check(TokenType.R_BRACKET)) {
                do {
                    if (check(TokenType.R_BRACKET)) break
                    val entry = peek()
                    if (isCaptureDefaultAhead()) {
                        val mode = when (advance().type) {
                            TokenType.EQUAL -> CaptureMode.COPY
                            TokenType.AMP -> CaptureMode.SHARED
                            TokenType.BANG -> CaptureMode.MUTABLE
                            else -> CaptureMode.MOVE
                        }
                        if (default != null) {
                            error("line ${entry.line}: a lambda has only one capture default")
                        }
                        default = mode
                    } else {
                        captures.add(parseCapture())
                    }
                } while (match(TokenType.COMMA))
            }
        }
        consume(TokenType.R_BRACKET, "Expected ']' after lambda bracket list")
        val lambda = parseLambda(
            start.line,
            start.column,
            implicitIt = false,
            typeParams = typeParams,
            variadicTypeParam = variadicTypeParam,
        )
        return lambda.copy(receivers = receivers, captures = captures, captureDefault = default, kind = kind)
    }

    /**
     * True when `[ … ]` here is a receiver list for a call - `[2, 3].add()`.
     *
     * The `.` after the matching `]` is what decides it, exactly as the `{` does
     * for a lambda's bracket list.
     */
    private fun isReceiverListCallAhead(startIndex: Int = current): Boolean {
        var depth = 0
        var i = startIndex
        while (i < tokens.size) {
            when (tokens[i].type) {
                TokenType.L_BRACKET -> depth++
                TokenType.R_BRACKET -> {
                    depth--
                    if (depth == 0) return tokens.getOrNull(i + 1)?.type == TokenType.DOT
                }
                TokenType.EOF -> return false
                else -> {}
            }
            i++
        }
        return false
    }

    /** True when the cursor is on a bare `=` / `&` / `!` / `take` default. */
    private fun isCaptureDefaultAhead(): Boolean {
        val t = peek().type
        if (t !in setOf(TokenType.EQUAL, TokenType.AMP, TokenType.BANG, TokenType.TAKE)) return false
        val after = peekNext()?.type
        return after == TokenType.COMMA || after == TokenType.R_BRACKET
    }

    /**
     * One entry after `;`: `value`, `value.&`, `value.!`, `value.clone()`,
     * `take value`, and each with an `alias = ` in front.
     */
    private fun parseCapture(): Capture {
        val start = peek()
        // `[owned = message.clone()]` - the alias is the name inside the closure.
        var alias: String? = null
        if (check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.EQUAL) {
            alias = advance().lexeme
            advance() // '='
        }
        // `[; take socket]` - ownership moves in. `take` is the prefix keyword it is
        // everywhere else.
        if (check(TokenType.TAKE)) {
            advance()
            val name = consumeIdentifierLike("Expected a name after 'take' in a capture list")
            return Capture(alias ?: name, name, CaptureMode.MOVE, start.line, start.column)
        }
        val name = consumeIdentifierLike("Expected a capture name in a lambda bracket list")
        if (check(TokenType.AMP) || check(TokenType.BANG)) {
            error(
                "line ${start.line}: capture borrows use the dotted ownership spelling; " +
                    "write '$name.${peek().lexeme}'",
            )
        }
        if (!match(TokenType.DOT)) return Capture(alias ?: name, name, CaptureMode.COPY, start.line, start.column)
        return when {
            match(TokenType.AMP) -> Capture(alias ?: name, name, CaptureMode.SHARED, start.line, start.column)
            match(TokenType.BANG) -> Capture(alias ?: name, name, CaptureMode.MUTABLE, start.line, start.column)
            check(TokenType.IDENTIFIER) && peek().lexeme == "clone" -> {
                advance()
                consume(TokenType.L_PAREN, "Expected '()' after 'clone' in a capture list")
                consume(TokenType.R_PAREN, "Expected '()' after 'clone' in a capture list")
                Capture(alias ?: name, name, CaptureMode.CLONE, start.line, start.column)
            }
            else -> error(
                "line ${start.line}: '$name.${peek().lexeme}' is not a capture - write " +
                    "'$name' to copy, '$name.&' or '$name.!' to reference, " +
                    "'$name.clone()' to clone, or 'take $name' to move",
            )
        }
    }

    private fun parseLambda(
        line: Int,
        column: Int,
        implicitIt: Boolean = true,
        typeParams: List<String> = emptyList(),
        variadicTypeParam: String? = null,
        kind: CallableKind = CallableKind.FUNC,
    ): Expr.Lambda {
        consume(TokenType.L_BRACE, "Expected '{'")
        skipNewlines()
        val params = mutableListOf<Param>()
        if ((check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.COLON) || check(TokenType.ELLIPSIS)) {
            do {
                val nameSpread = match(TokenType.ELLIPSIS)
                val name = consumeIdentifierLike("Expected lambda parameter name")
                consume(TokenType.COLON, "Expected ':' after lambda parameter name")
                val typeSpread = check(TokenType.ELLIPSIS)
                if (typeSpread) {
                    val spreadName = peekNext()?.takeIf { it.type == TokenType.IDENTIFIER }?.lexeme
                    if (variadicTypeParam == null || spreadName != variadicTypeParam) {
                        error(
                            "line ${peek().line}: type spread '...${spreadName.orEmpty()}' requires " +
                                "the matching variadic generic '<...${spreadName.orEmpty()}>'",
                        )
                    }
                }
                val parsedType = parseTypeName()
                val reference = parsedType as? TypeRef.Reference
                val rawType = reference?.inner ?: parsedType
                val parameterType = if (nameSpread && rawType !is TypeRef.Array) TypeRef.Array(rawType) else rawType
                params.add(
                    Param(
                        name,
                        parameterType,
                        modifier = reference?.kind?.paramModifier ?: ParamModifier.NONE,
                        variadic = nameSpread || typeSpread,
                    ),
                )
                if (params.dropLast(1).any { it.variadic }) {
                    error("line $line: a variadic lambda parameter must be last")
                }
            } while (match(TokenType.COMMA))
            consume(TokenType.ARROW, "Expected '->' in lambda")
        } else if (isUntypedLambdaParamsAhead()) {
            // Untyped params: `{ it -> … }`, `{ a, b -> … }`. The single-receiver
            // form lets a caller name the implicit `it` explicitly; multi-param
            // lambda values name each argument.
            do {
                val name = consumeIdentifierLike("Expected lambda parameter name")
                params.add(Param(name, TypeRef.Named("Any", synthesized = true)))
            } while (match(TokenType.COMMA))
            consume(TokenType.ARROW, "Expected '->' in lambda")
        } else if (check(TokenType.ARROW)) {
            // `{ -> body }` - a parameter list that is empty is already written by
            // not writing one, so the arrow has no job left. Accepting it as a
            // synonym would be the same thing said two ways.
            error(
                "line ${peek().line}: a lambda with no parameters writes no '->' - " +
                    "write '{ … }' rather than '{ -> … }'",
            )
        }
        // Whether a bare `{ body }` takes a parameter is decided by the body, so
        // the body is parsed before the parameter list is settled.
        val paramsWritten = params.isNotEmpty()
        skipNewlines()
        lambdaMentionsIt.add(false)
        val body = parseBlock().toMutableList()
        val mentionsIt = lambdaMentionsIt.removeAt(lambdaMentionsIt.size - 1)
        if (body.isNotEmpty() && body.last() is Stmt.ExprStmt) {
            val last = body.removeAt(body.size - 1) as Stmt.ExprStmt
            body.add(Stmt.Return(last.expr, last.line, last.column, last.length))
        }
        consume(TokenType.R_BRACE, "Expected '}' after lambda body")
        // `it` is not a property of the braces: a bare lambda takes the parameter
        // named `it` only when its body actually reads one. A body that reads
        // nothing takes no parameters, which is what makes `{ n = n + 1 }` a
        // zero-parameter lambda rather than a one-parameter one that is called
        // with none. Where an expected callable type supplies parameters, the
        // resolver puts them back (see `TypeResolver`).
        if (!paramsWritten && implicitIt && mentionsIt) {
            params.add(Param("it", TypeRef.Named("Any", synthesized = true)))
        }
        return Expr.Lambda(
            params,
            body,
            line,
            column,
            variadic = params.lastOrNull()?.variadic == true,
            kind = kind,
            paramsWritten = paramsWritten,
            typeParams = typeParams,
            variadicTypeParam = variadicTypeParam,
        )
    }

    /** Parses `<T, ...U>[receivers; captures] { parameters -> body }`. */
    private fun parseGenericLambda(): Expr.Lambda {
        val start = peek()
        val generic = parseTypeParams()
        if (generic.constParams.isNotEmpty()) {
            error("line ${start.line}: lambda generics are types; const generic parameters are not supported here")
        }
        return when {
            check(TokenType.L_BRACKET) && isReceiverLambdaAhead() ->
                parseReceiverLambda(generic.names, generic.variadic)
            check(TokenType.L_BRACE) -> parseLambda(
                start.line,
                start.column,
                typeParams = generic.names,
                variadicTypeParam = generic.variadic,
            )
            else -> error(
                "line ${start.line}: expected a receiver/capture list or lambda body after generic parameters",
            )
        }
    }

    /**
     * True when the tokens open an untyped lambda parameter list - `IDENT (','
     * IDENT)* '->'` - as in `{ it -> … }` or `{ a, b -> … }`. Requires the
     * trailing `->` so a body that merely begins with an identifier (`{ it }`,
     * `{ a + b }`) is not mistaken for a parameter list.
     */
    private fun isUntypedLambdaParamsAhead(): Boolean {
        var i = current
        if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return false
        while (true) {
            if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return false
            when (tokens.getOrNull(i + 1)?.type) {
                TokenType.ARROW -> return true
                TokenType.COMMA -> i += 2
                else -> return false
            }
        }
    }

    /**
     * Parses an expression from a raw source string (used to parse the embedded
     * expressions of an interpolated string).
     */
    private fun parseSubExpr(source: String): Expr {
        val subTokens = Lexer(source).tokenize()
        return Parser(subTokens).parseExpr()
    }

    private fun peek(): Token = tokens[current]
    private fun peekNext(): Token? = if (current + 1 < tokens.size) tokens[current + 1] else null

    /** Index of the next non-newline token at or after `current`. */
    private fun nextMeaningfulIndex(from: Int = current): Int {
        var i = from
        while (i < tokens.size && tokens[i].type == TokenType.NEWLINE) i++
        return i
    }
    private fun isAtEnd() = peek().type == TokenType.EOF
    private fun check(type: TokenType) = !isAtEnd() && peek().type == type
    private fun advance(): Token = tokens[current++]
    private fun skipNewlines() { while (check(TokenType.NEWLINE)) advance() }

    private fun consumeNewline() {
        if (check(TokenType.NEWLINE)) advance()
    }

    private fun match(type: TokenType): Boolean {
        if (!check(type)) return false
        advance()
        return true
    }

    private fun matchContextual(word: String): Boolean {
        if (!check(TokenType.IDENTIFIER) || peek().lexeme != word) return false
        advance()
        return true
    }

    private fun matchWithKeyword(): Boolean {
        if (match(TokenType.WITH)) return true
        if (check(TokenType.IDENTIFIER) && peek().lexeme == "with") {
            advance()
            return true
        }
        return false
    }

    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        error("$message, got '${peek().lexeme}' (${peek().type}) at line ${peek().line}")
    }
}
