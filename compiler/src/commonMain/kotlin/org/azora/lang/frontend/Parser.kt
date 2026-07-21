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
    private val tokens: List<Token>,
    /**
     * Compile-time lists of type names (`let X: [Type] = [A, B] ~ C`). Shared with
     * sub-parsers used to expand `inline for` bodies so nested loops resolve the
     * same lists. Purely a parse-time metaprogramming environment.
     */
    private val typeListEnv: MutableMap<String, List<String>> = mutableMapOf(),
) {

    /** Compile-time type functions are declarations, but never runtime top-level items. */
    private val typeFunctions = mutableListOf<TypeFunctionDecl>()
    /** Namespace used to qualify unqualified type-function calls inside a zone. */
    private var typeFunctionNamespacePrefix = ""

    /**
     * Extra top-level items synthesized while parsing another declaration:
     * bridge alias wrappers and normalized decorator/target list applications.
     * Drained into the item list after each declaration completes.
     */
    private val pendingTopLevels = mutableListOf<TopLevel>()

    /** `meta type { … }` arms collected during parsing, surfaced on [Program.typeMacroRules]. */
    private val pendingTypeMacroRules = mutableListOf<TypeTypeArm>()

    /** Set whenever a `name!…` macro invocation is parsed; surfaced on [Program.usesMacros]. */
    private var usedMetaInvoke = false

    /**
     * Non-friend `zone X` declarations seen in this program (name -> count).
     * A non-friend zone is exclusive: declaring `zone X` twice (even across
     * concatenated modules) is a redeclaration. `friend zone X` is exempt (it
     * merges across blocks/modules). Checked at the end of [parse].
     */
    private val namedZoneDeclarations = mutableMapOf<String, Int>()

    /** Enclosing labeled/anonymous zones while parsing (outermost first). */
    private data class ZoneFrame(val label: String?, val isInline: Boolean)
    private val zoneStack = mutableListOf<ZoneFrame>()
    /** Declaration name → its innermost zone, for `(reflect X).zone` reflection. */
    private val zoneMetaByName = mutableMapOf<String, ZoneMeta>()

    /** Builds the [ZoneMeta] chain for the current [zoneStack] (innermost outward). */
    private fun currentZoneMeta(): ZoneMeta? {
        var meta: ZoneMeta? = null
        for (frame in zoneStack) meta = ZoneMeta(frame.label, frame.isInline, meta)
        return meta
    }

    /** Records the current zone for each named declaration in [items] (innermost wins). */
    private fun recordZoneMembers(items: List<TopLevel>) {
        val meta = currentZoneMeta() ?: return
        for (item in items) {
            val name = declaredTopLevelName(item) ?: continue
            if (name !in zoneMetaByName) zoneMetaByName[name] = meta
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
        is TopLevel.Node -> item.name
        is TopLevel.TypeAlias -> item.name
        else -> null
    }

    /**
     * True when a labeled/anonymous zone block begins here: an optional
     * `inline`/`deepinline`, then `zone`, then a string label or `{`. Excludes the
     * identifier-named namespace form `zone Foo { … }` (handled separately) and
     * plain `inline { … }`/`deepinline if …`.
     */
    private fun isZoneBlockAhead(): Boolean {
        var i = current
        val t = tokens.getOrNull(i)?.type
        if (t == TokenType.INLINE || t == TokenType.DEEPINLINE) i++
        if (tokens.getOrNull(i)?.type != TokenType.ZONE) return false
        val after = tokens.getOrNull(i + 1)?.type
        return after == TokenType.STRING_LITERAL || after == TokenType.L_BRACE
    }

    /**
     * `[inline|deepinline] zone ["label"] { … }` — a labeled or anonymous zone.
     * Unlike a namespace `zone Foo { … }`, members keep their top-level names;
     * the zone only attaches reflection metadata ([zoneMetaByName]) and, for the
     * inline/deepinline forms, the usual compile-time block semantics. Inline-ness
     * is inherited by nested zones.
     */
    private fun parseZoneBlock(): List<TopLevel> {
        val start = peek()
        val ownInline = when {
            match(TokenType.DEEPINLINE) -> true
            match(TokenType.INLINE) -> true
            else -> false
        }
        val isDeep = start.type == TokenType.DEEPINLINE
        consume(TokenType.ZONE, "Expected 'zone'")
        val label = if (check(TokenType.STRING_LITERAL)) advance().literal as String else null
        // Inline-ness is inherited: a plain zone nested in an inline/deepinline
        // zone is itself inline.
        val isInline = ownInline || (zoneStack.lastOrNull()?.isInline == true)
        consume(TokenType.L_BRACE, "Expected '{' to open zone")
        skipNewlines()
        zoneStack.add(ZoneFrame(label, isInline))
        val body = mutableListOf<TopLevel>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            when {
                isZoneBlockAhead() -> body.addAll(parseZoneBlock())
                check(TokenType.ZONE) && peekNext()?.type == TokenType.IDENTIFIER -> body.addAll(parseNamedZone())
                check(TokenType.TYPE) -> parseTypeFunction()
                isInline -> body.add(parseTopLevelBlockItem(deepInline = true))
                else -> body.add(parseTopLevel())
            }
            skipNewlines()
        }
        recordZoneMembers(body)
        zoneStack.removeAt(zoneStack.size - 1)
        consume(TokenType.R_BRACE, "Expected '}' to close zone")
        consumeNewline()
        return when {
            !ownInline -> body
            isDeep -> listOf(TopLevel.DeepInlineBlock(body, start.line, start.column))
            else -> listOf(TopLevel.InlineBlock(body, start.line, start.column))
        }
    }

    /** `func sin as az_sin(x: Real): Real` → wrapper `func sin(x) { return az_sin(x) }`. */
    private fun makeBridgeWrapper(name: String, externName: String, params: List<Param>, returnType: TypeRef, line: Int) {
        val args = params.map { Expr.Identifier(it.name, line) }
        val call = Expr.Call(externName, args, line)
        val body: List<Stmt> =
            if ((returnType as? TypeRef.Named)?.name == "Unit") listOf(Stmt.ExprStmt(call, line))
            else listOf(Stmt.Return(call, line))
        pendingTopLevels.add(TopLevel.Func(FuncDecl(name, params, TypeAnnotation.Explicit(returnType), body, line = line)))
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
        var moduleVisibility = ModuleVisibility.EXPOSE
        var exportCondition: Expr? = null
        val moduleName = if (isModuleHeaderAhead()) {
            if (match(TokenType.EXPORT)) isExported = true
            if (check(TokenType.IF)) {
                // `export if COND \n module …` — comptime-conditional export.
                advance() // 'if'
                exportCondition = parseExpr()
                consume(TokenType.NEWLINE, "Expected a newline after 'export if <condition>'")
            } else {
                moduleVisibility = when {
                    match(TokenType.EXPOSE) -> ModuleVisibility.EXPOSE
                    match(TokenType.INTERN) -> ModuleVisibility.INTERN
                    match(TokenType.PROTECT) -> ModuleVisibility.PROTECT
                    match(TokenType.CONFINE) -> ModuleVisibility.CONFINE
                    else -> ModuleVisibility.EXPOSE
                }
                if (isExported && moduleVisibility == ModuleVisibility.CONFINE) {
                    error("'export confine module' is contradictory: a confined module is private and cannot be exported")
                }
            }
            parseModule()
        } else null
        val items = mutableListOf<TopLevel>()
        while (!isAtEnd()) {
            skipNewlines()
            if (isAtEnd()) break
            if (isNamedZoneNamespaceAhead()) {
                // `[use] zone Name { … }` — a named zone is a namespace. Plain
                // declarations use qualified names; `use zone` keeps members bare.
                items.addAll(parseNamedZone())
            } else if (isZoneBlockAhead()) {
                // `[inline|deepinline] zone ["label"] { … }` — a labeled/anonymous
                // zone; members stay top-level, the zone only adds reflection
                // metadata (and inline semantics for the inline forms).
                items.addAll(parseZoneBlock())
            } else if (isFriendZoneNamespaceAhead()) {
                items.addAll(parseFriendZoneNamespace())
            } else if (check(TokenType.TYPE)) {
                parseTypeFunction()
            } else {
                items.add(parseTopLevel())
            }
            if (pendingTopLevels.isNotEmpty()) {
                items.addAll(pendingTopLevels)
                pendingTopLevels.clear()
            }
        }
        namedZoneDeclarations.entries.firstOrNull { it.value > 1 }?.let { (name, _) ->
            error("zone '$name' is declared more than once; use 'friend zone $name' to merge the same zone across blocks/modules")
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
                zoneMetaByName.toMap(),
                typeFunctions = typeFunctions.toList(),
                typeMacroRules = pendingTypeMacroRules.toList(),
                usesMacros = usedMetaInvoke,
            )
        )
        return IntraZoneRewriter.rewrite(normalized)
    }

    private fun isNamedZoneNamespaceAhead(): Boolean {
        val offset = if (check(TokenType.USE)) 1 else 0
        return tokens.getOrNull(current + offset)?.type == TokenType.ZONE &&
            tokens.getOrNull(current + offset + 1)?.type == TokenType.IDENTIFIER
    }

    /**
     * `[use] zone Name { items }` — a named zone acts as a namespace. Desugared at
     * parse time: a plain zone member becomes `Name__member` (and is accessed as
     * `Name::member`), while `use zone` deliberately keeps member names bare.
     */
    private fun parseNamedZone(): List<TopLevel> {
        val useBare = match(TokenType.USE)
        consume(TokenType.ZONE, "Expected 'zone'")
        val name = consume(TokenType.IDENTIFIER, "Expected zone name").lexeme
        namedZoneDeclarations[name] = (namedZoneDeclarations[name] ?: 0) + 1
        consume(TokenType.L_BRACE, "Expected '{' after zone name")
        skipNewlines()
        val items = parseZoneBody(name, mangle = !useBare)
        consume(TokenType.R_BRACE, "Expected '}' after zone")
        consumeNewline()
        return items
    }

    private fun isFriendZoneNamespaceAhead(): Boolean {
        // Optional `use` prefix (`use friend zone …`) — members stay bare-accessible.
        val f0 = if (check(TokenType.USE)) current + 1 else current
        if (tokens.getOrNull(f0)?.type != TokenType.FRIEND) return false
        if (tokens.getOrNull(f0 + 1)?.type != TokenType.ZONE) return false
        var i = f0 + 2
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
     * `friend zone Name (:: Name)* { items }` — a shared namespace contribution.
     * Like a named `zone`, it mangles each member with the zone path
     * (`friend zone std { friend zone math { fin PI } }` → `std__math__PI`, reached
     * as `std::math::PI`). Unlike a named `zone`, the same zone path may be
     * declared across multiple modules and the contributions merge (the
     * "friend" grant); the exclusivity check lives in semantic analysis.
     *
     * The `use` prefix (`use friend zone std { … }`) keeps members at their bare
     * short names (no mangling), so importing the module makes them callable
     * without the `Zone::` qualifier — e.g. `vec![…]` rather than `std::vec![…]`.
     */
    private fun parseFriendZoneNamespace(outerPrefix: String = ""): List<TopLevel> {
        val useBare = match(TokenType.USE) // optional 'use' → members stay bare (unmangled)
        consume(TokenType.FRIEND, "Expected 'friend'")
        consume(TokenType.ZONE, "Expected 'zone' after 'friend'")
        val first = consume(TokenType.IDENTIFIER, "Expected zone name after 'friend zone'").lexeme
        val friendPath = StringBuilder(first).apply {
            while (match(TokenType.DOUBLE_COLON)) {
                append("__").append(consume(TokenType.IDENTIFIER, "Expected zone name after '::' in friend zone path").lexeme)
            }
        }.toString()
        val prefix = if (outerPrefix.isEmpty()) friendPath else "${outerPrefix}__$friendPath"
        consume(TokenType.L_BRACE, "Expected '{' after friend zone name")
        skipNewlines()
        val items = parseZoneBody(prefix, mangle = !useBare)
        consume(TokenType.R_BRACE, "Expected '}' after friend zone")
        consumeNewline()
        return items
    }

    private fun parseZoneBody(prefix: String, mangle: Boolean = true): List<TopLevel> {
        val result = mutableListOf<TopLevel>()
        val previousTypeNamespace = typeFunctionNamespacePrefix
        typeFunctionNamespacePrefix = prefix
        try {
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                skipNewlines()
                if (check(TokenType.R_BRACE)) break
                when {
                    isFriendZoneNamespaceAhead() -> result.addAll(parseFriendZoneNamespace(prefix))
                    check(TokenType.ZONE) && peekNext()?.type == TokenType.IDENTIFIER -> {
                        consume(TokenType.ZONE, "Expected 'zone'")
                        val inner = consume(TokenType.IDENTIFIER, "Expected zone name").lexeme
                        consume(TokenType.L_BRACE, "Expected '{' after zone name")
                        skipNewlines()
                        result.addAll(parseZoneBody("${prefix}__$inner", mangle))
                        consume(TokenType.R_BRACE, "Expected '}' after nested zone")
                        skipNewlines()
                    }
                    check(TokenType.TYPE) -> parseTypeFunction(prefix)
                    else -> {
                        val parsed = parseTopLevel()
                        result.add(if (mangle) mangleTopLevel(parsed, prefix) else parsed)
                        if (pendingTopLevels.isNotEmpty()) {
                            result.addAll(pendingTopLevels)
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

    /** Qualifies a top-level item's name with [prefix] (e.g. `PI` → `Math__PI`). */
    private fun mangleTopLevel(item: TopLevel, prefix: String): TopLevel = when (item) {
        is TopLevel.Func -> item.copy(decl = item.decl.copy(name = "${prefix}__${item.decl.name}"))
        is TopLevel.FinDecl -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.VarDecl -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.LetDecl -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.InlineFin -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.InlineLet -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.InlineVar -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.Impl -> item.copy(zonePrefix = prefix)
        // `bridge func` members of `impl zone for Type` are static intrinsics
        // reached as `Type::member`; mangle each to `Type__member` to match.
        is TopLevel.Bridge -> item.copy(funcs = item.funcs.map { it.copy(name = "${prefix}__${it.name}") })
        else -> item
    }

    /** Reads an operator symbol after `oper` (for `impl oper<OP> …`). */
    private fun parseOperatorName(): String = when {
        // `reverse..` (two tokens) and the range operators `..`/`..<` share one name.
        check(TokenType.REVERSE) && peekNext()?.type == TokenType.DOT_DOT -> { advance(); advance(); "reverse.." }
        match(TokenType.DOT_DOT) -> ".."
        match(TokenType.DOT_DOT_LESS) -> ".."
        match(TokenType.LESS_EQUAL) -> "<="
        match(TokenType.GREATER_EQUAL) -> ">="
        match(TokenType.EQUAL_EQUAL) -> "=="
        match(TokenType.BANG_EQUAL) -> "!="
        match(TokenType.SHIFT_LEFT) -> "<<"
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
        match(TokenType.HASH) -> "#"
        else -> error("Expected an operator after 'oper' at line ${peek().line}")
    }

    /**
     * `[bridge] impl oper<OP> for Type(params): Ret (mod self) [{ body }]`.
     * The receiver is declared in parens after the return type; the body, if any,
     * follows in braces (`{ body }`), or the older `{ mod self -> body }` form is
     * accepted. A `bridge` operator (e.g. the primitives') is compiler-provided:
     * the declaration is accepted but emits no method, so the backend's native
     * operator handling stands.
     */
    private fun parseOperatorImpl(start: Token, isBridge: Boolean, annotations: List<Annotation> = emptyList()): TopLevel.Impl {
        consume(TokenType.OPER, "Expected 'oper'")
        val opName = parseOperatorName()
        // `impl oper<OP> by <Spec> for Type` — an optional spec/operand-type clause.
        val bySpec = if (match(TokenType.BY)) consume(TokenType.IDENTIFIER, "Expected spec name after 'by'").lexeme else null
        skipGenericTypeArgs()
        consume(TokenType.FOR, "Expected 'for' after 'impl oper$opName'")
        val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'for'").lexeme
        skipGenericTypeArgs()
        val returnType = if (match(TokenType.COLON)) {
            skipNewlines() // the return type may sit on a continuation line (e.g. `promote!(…)`)
            TypeAnnotation.Explicit(parseTypeName())
        } else TypeAnnotation.Inferred
        val (receiverModifier, params, body) = parseReceiverAndBody()
        consumeNewline()
        val methodName = "oper$opName"
        // A bridge/bodyless oper impl carries a bodyless marker method named
        // `oper$opName` so the operator is REGISTERED in the symbol table (e.g.
        // enabling the range-operator gate), even though no IR body is emitted.
        if (isBridge || body.isEmpty()) {
            val marker = FuncDecl(
                methodName, params, returnType, emptyList(), false, emptyList(),
                start.line, start.column, receiverModifier = receiverModifier,
            )
            return TopLevel.Impl(
                typeName, listOf(marker), bySpec, start.line, start.column,
                annotations = annotations, isBridge = true,
            )
        }
        val method = FuncDecl(
            methodName, params, returnType, body, false, emptyList(),
            start.line, start.column, receiverModifier = receiverModifier,
        )
        return TopLevel.Impl(
            typeName, listOf(method), bySpec, start.line, start.column,
            annotations = annotations, isBridge = isBridge,
        )
    }

    /**
     * `impl oper[spec, spec, ...] for Type` — declares several operators at once.
     * Each spec is `[reverse] (.. | ..<) [by <expr>]`. `..` and `..<` are one
     * operator (the inclusive/exclusive flag lives on the range node); `reverse..`
     * is the reverse-range operator. The optional `by <expr>` is declarative step
     * metadata (parsed, then discarded). The expansion produces one `TopLevel.Impl`
     * per spec via the `pendingTopLevels` queue (first returned, rest queued).
     * `oper` and the leading `[` have already been consumed by the caller.
     */
    private fun parseMultiOperImpl(start: Token, isBridge: Boolean, annotations: List<Annotation>): TopLevel.Impl {
        data class OpSpec(val methodName: String)
        val specs = mutableListOf<OpSpec>()
        do {
            val reversed = match(TokenType.REVERSE)
            val opName = when {
                match(TokenType.DOT_DOT) -> ".."
                match(TokenType.DOT_DOT_LESS) -> ".."
                else -> error("Expected '..' or 'reverse..' in oper spec at line ${peek().line}")
            }
            // `by <expr>` — declarative default-step metadata; parsed then discarded.
            if (match(TokenType.BY)) parseExpr()
            specs.add(OpSpec("oper" + (if (reversed) "reverse" else "") + opName))
        } while (match(TokenType.COMMA))
        consume(TokenType.R_BRACKET, "Expected ']' after oper spec list")
        consume(TokenType.FOR, "Expected 'for' after 'impl oper[...]'")
        val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'for'").lexeme
        skipGenericTypeArgs()
        val (receiverModifier, params, body) = parseReceiverAndBody()
        consumeNewline()
        val bridge = isBridge || body.isEmpty()
        val impls = specs.mapIndexed { idx, sp ->
            // Distinct columns per operator so downstream position-keyed dedup (e.g.
            // the stdlib injector) does not collapse the expanded impls into one.
            val col = start.column + idx
            val method = FuncDecl(
                sp.methodName, params, TypeAnnotation.Inferred, body, false, emptyList(),
                start.line, col, receiverModifier = receiverModifier,
            )
            TopLevel.Impl(
                typeName, listOf(method), null, start.line, col,
                annotations = annotations, isBridge = bridge,
            )
        }
        if (impls.size > 1) pendingTopLevels.addAll(impls.drop(1))
        return impls.first()
    }

    /**
     * Parses an optional receiver + parameters + body for a member. The receiver
     * (and any parameters) are declared in parens after the return type:
     * `(mod self [, param: T]...)`, then an optional `{ body }`; alternatively a
     * `{ mod self -> body }` block, or just `{ body }`, or nothing. Returns the
     * receiver modifier (default `ref`), the parameters, and the body statements
     * (empty for a bodyless declaration).
     */
    private fun parseReceiverAndBody(): Triple<String, List<Param>, List<Stmt>> {
        var receiverModifier = "ref"
        val params = mutableListOf<Param>()
        if (match(TokenType.L_PAREN)) {
            receiverModifier = parseImplReceiverModifier()
            val recv = consumeIdentifierLike("Expected receiver name")
            if (recv != "self") error("Expected receiver to be named 'self' at line ${peek().line}")
            if (match(TokenType.COMMA)) params.addAll(parseParams())
            consume(TokenType.R_PAREN, "Expected ')' after receiver")
        }
        val body = mutableListOf<Stmt>()
        if (match(TokenType.L_BRACE)) {
            skipNewlines()
            // In-brace receiver form: `{ mod self -> body }` or, for operator
            // overloads, `{ mod self, operand[, operand] -> body }`.
            if (isInBraceReceiverAhead()) {
                receiverModifier = parseImplReceiverModifier()
                consumeIdentifierLike("Expected receiver name")
                while (match(TokenType.COMMA)) {
                    val pmod = parseImplReceiverModifier()
                    params.add(Param(consumeIdentifierLike("Expected parameter name"), TypeRef.Named("Any"), modifier = pmod))
                }
                consume(TokenType.ARROW, "Expected '->' after in-brace receiver")
                skipNewlines()
            }
            while (!check(TokenType.R_BRACE) && !isAtEnd()) { body.add(parseStmt()); skipNewlines() }
            consume(TokenType.R_BRACE, "Expected '}' after body")
        }
        return Triple(receiverModifier, params, body)
    }

    /**
     * True when the current brace body opens with a `[mut] ref/mut self` receiver,
     * optionally followed by operand params (`self, x -> …`) before the `->`.
     */
    private fun isInBraceReceiverAhead(): Boolean {
        var i = current
        if (tokens.getOrNull(i)?.type in setOf(TokenType.REF, TokenType.MUT)) {
            if (tokens.getOrNull(i)?.type == TokenType.MUT && tokens.getOrNull(i + 1)?.type == TokenType.REF) i++
            i++
        } else return false
        if (tokens.getOrNull(i)?.lexeme != "self") return false
        i++
        // Skip `, operand` pairs (operator operands) before the `->`.
        while (tokens.getOrNull(i)?.type == TokenType.COMMA) {
            i += 2 // `, name` (the name token; modifiers are rare in this position)
        }
        return tokens.getOrNull(i)?.type == TokenType.ARROW
    }

    private fun parseVisibility(): Visibility = when {
        match(TokenType.EXPOSE) -> Visibility.EXPOSE
        match(TokenType.INTERN) -> Visibility.INTERN
        match(TokenType.CONFINE) -> Visibility.CONFINE
        match(TokenType.PROTECT) -> Visibility.PROTECT
        match(TokenType.SHIELD) -> Visibility.SHIELD
        else -> Visibility.EXPOSE
    }

    // -----------------------------------------------------------------------
    // Declarations
    // -----------------------------------------------------------------------

    private fun parseTopLevel(): TopLevel {
        val annotations = parseAnnotations()
        // Optional visibility modifier: expose (default), confine (private), protect/protected.
        val visibility = parseVisibility()
        val start = peek()
        return when {
            // Compile-time list bindings (`let X: [Type]`, `inline fin ranks: [Int]`)
            // must be recognised before the general `inline`/`fin`/`let` handlers.
            isTypeListBindingAhead() -> parseTypeListBinding()
            check(TokenType.UNSAFE) && peekNext()?.type in setOf(TokenType.FUNC, TokenType.TASK, TokenType.FLOW) -> {
                advance()
                when {
                    check(TokenType.TASK) -> TopLevel.Func(parseFuncDecl(annotations = annotations, isTask = true, isUnsafe = true, visibility = visibility))
                    check(TokenType.FLOW) -> TopLevel.Func(parseFuncDecl(annotations = annotations, isFlow = true, isUnsafe = true, visibility = visibility))
                    else -> TopLevel.Func(parseFuncDecl(annotations = annotations, isUnsafe = true, visibility = visibility))
                }
            }
            isExtensionFuncAhead() -> parseExtensionFunc(visibility)
            check(TokenType.FUNC) -> TopLevel.Func(parseFuncDecl(annotations = annotations, visibility = visibility))
            check(TokenType.TASK) -> TopLevel.Func(parseFuncDecl(annotations = annotations, isTask = true, visibility = visibility))
            check(TokenType.FLOW) -> TopLevel.Func(parseFuncDecl(annotations = annotations, isFlow = true, visibility = visibility))
            check(TokenType.INLINE) -> parseTopLevelInline()
            check(TokenType.DEEPINLINE) -> parseTopLevelDeepInline()
            check(TokenType.TEST) -> parseTestDecl(annotations)
            check(TokenType.DECO) -> parseDeco(annotations)
            check(TokenType.PACK) -> parsePack(annotations, visibility)
            // `opaque pack X { … }` — every field is forced to confine; no field
            // visibility modifier may be written.
            check(TokenType.OPAQUE) && peekNext()?.type == TokenType.PACK -> {
                advance()
                parsePack(annotations, visibility, isOpaque = true)
            }
            check(TokenType.ENUM) -> parseEnumDecl(annotations)
            check(TokenType.FAIL) -> parseFailDecl(annotations)
            check(TokenType.IMPL) -> parseImpl(annotations = annotations)
            check(TokenType.INFX) -> parseInfx()
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
            check(TokenType.BRIDGE) && peekNext()?.type == TokenType.DECO -> {
                advance(); parseDeco(annotations)
            }
            check(TokenType.BRIDGE) && peekNext()?.type == TokenType.SPEC -> {
                advance(); parseSpec()
            }
            check(TokenType.BRIDGE) && peekNext()?.type == TokenType.TYPEALIAS -> {
                advance(); parseTypeAlias(annotations)
            }
            check(TokenType.BRIDGE) && isBridgeFuncAhead() -> parseBridgeFunc(annotations)
            check(TokenType.BRIDGE) -> parseBridge(annotations)
            check(TokenType.SOLO) -> parseSolo(visibility, annotations)
            check(TokenType.WRAP) -> parseWrap()
            check(TokenType.NODE) -> parseNode(visibility = visibility, annotations = annotations)
            check(TokenType.LEAF) -> parseNode(isLeaf = true, visibility = visibility, annotations = annotations)
            // `abstract node Name` / `abstract Name` — cannot be instantiated directly.
            check(TokenType.ABSTRACT) && peekNext()?.type == TokenType.NODE -> {
                advance()
                parseNode(isAbstract = true, visibility = visibility, annotations = annotations)
            }
            check(TokenType.ABSTRACT) -> {
                advance()
                parseNode(isAbstract = true, visibility = visibility, annotations = annotations)
            }
            check(TokenType.VIEW) -> parseView(annotations)
            check(TokenType.HOOK) -> parseHook(annotations)
            check(TokenType.THREADLOCAL) -> parseThreadLocal(visibility)
            check(TokenType.SPEC) -> parseSpec()
            check(TokenType.SLOT) -> parseSlot(annotations)
            check(TokenType.TYPEALIAS) -> parseTypeAlias(annotations)
            check(TokenType.META) -> parseMeta()
            // `prop name: T = expr` at top level — accepted inside `impl … as zone
            // for Type` / `impl zone for Type` bodies (which reparse members as
            // top-level items); desugars to a `fin` so it mangles to `Type__name`.
            check(TokenType.PROP) -> parsePropAsFin(annotations, visibility)
            check(TokenType.IMPORT) -> parseImport()
            // `export import …` — re-export the import so importers of this module
            // also receive it transitively (e.g. std.macro re-exporting std.container).
            check(TokenType.EXPORT) && peekNext()?.type == TokenType.IMPORT -> {
                advance() // 'export'
                parseImport(exported = true)
            }
            // `export if COND \n import …` — comptime-conditional re-export. The
            // newline before `import` is mandatory.
            check(TokenType.EXPORT) && peekNext()?.type == TokenType.IF -> {
                advance() // 'export'
                advance() // 'if'
                val cond = parseExpr()
                consume(TokenType.NEWLINE, "Expected a newline after 'export if <condition>'")
                parseImport(exported = true, condition = cond)
            }
            check(TokenType.FIN) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.FinDecl(name, type, init, start.line, start.column, annotations, visibility = visibility) }
            check(TokenType.VAR) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.VarDecl(name, type, init, start.line, start.column, annotations, visibility = visibility) }
            check(TokenType.LET) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.LetDecl(name, type, init, start.line, start.column, annotations, visibility) }
            else -> error("Expected 'func', 'fin', 'var', 'let', 'test', 'inline', or 'deepinline' at top level, got '${peek().lexeme}' at line ${peek().line}")
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
            val at = advance() // '@'
            val name = consume(TokenType.IDENTIFIER, "Expected name after '@'").lexeme
            if (name.firstOrNull()?.isUpperCase() != true) {
                error("Decorator names must start with an uppercase letter: use '@${name.replaceFirstChar { it.uppercase() }}' at line ${at.line}")
            }
            val (args, namedArgs) = if (check(TokenType.L_PAREN)) parseDecoratorArguments()
            else emptyList<Expr>() to emptyList<Pair<String, Expr>>()
            result.add(Annotation(name, args, at.line, at.column, namedArgs = namedArgs))
            skipNewlines()
        }
        return result
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
        val name = consume(TokenType.IDENTIFIER, "Expected spec or decorator name after 'bind'").lexeme
        val typeArgs = parseGenericTypeArgsIfPresent()
        val targets = if (match(TokenType.FOR)) parseDecoTargets() else emptySet()
        return DecoratorBinding(name, typeArgs, targets)
    }

    /** Parses every `deco` target/binding combination into a normalized declaration. */
    private fun parseDeco(annotations: List<Annotation>): TopLevel.Deco {
        val start = peek()
        consume(TokenType.DECO, "Expected 'deco'")
        val name = consume(TokenType.IDENTIFIER, "Expected decorator name").lexeme
        if (name.firstOrNull()?.isUpperCase() != true) {
            error("Decorator names must start with an uppercase letter: use '${name.replaceFirstChar { it.uppercase() }}' at line ${start.line}")
        }
        val targets = if (match(TokenType.FOR)) parseDecoTargets() else emptySet()
        val bindings = if (match(TokenType.BIND)) {
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
            return TopLevel.Deco(name, emptyList(), start.line, start.column, annotations, targets, bindings)
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
        return TopLevel.Deco(name, fields, start.line, start.column, annotations, targets, bindings)
    }

    private fun parseTopLevelInline(): TopLevel {
        return when (peekNext()?.type) {
            TokenType.FUNC -> { advance(); TopLevel.Func(parseFuncDecl(isInline = true)) }
            TokenType.FOR -> parseTopLevelInlineFor()
            TokenType.L_BRACE -> parseTopLevelInlineBlock()
            TokenType.ZONE -> parseTopLevelInlineZoneBlock()
            TokenType.IF -> parseTopLevelInlineIf()
            TokenType.ASSERT -> parseTopLevelInlineAssert()
            TokenType.TRACE -> parseTopLevelInlineTrace()
            TokenType.FIN -> { val start = peek(); advance(); advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; if (match(TokenType.COLON)) parseTypeName(); consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.InlineFin(name, init, start.line, start.column) }
            TokenType.LET -> { val start = peek(); advance(); advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; if (match(TokenType.COLON)) parseTypeName(); consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.InlineLet(name, init, start.line, start.column) }
            TokenType.VAR -> { val start = peek(); advance(); advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; if (match(TokenType.COLON)) parseTypeName(); consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.InlineVar(name, init, start.line, start.column) }
            TokenType.IDENTIFIER -> { val start = peek(); advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; consume(TokenType.EQUAL, "Expected '='"); val value = parseExpr(); consumeNewline(); TopLevel.InlineAssignment(name, value, start.line, start.column) }
            else -> error("Expected 'func', '{', 'zone', 'if', 'assert', 'trace', 'fin', 'var', 'let', or identifier after 'inline' at line ${peek().line}")
        }
    }

    /**
     * `inline for VAR in [<literals>] { <declarations> }` at top level — compile-time
     * code generation. The body is a template: each `$VAR` inside an identifier (e.g.
     * `oper$op`) is substituted with the current value and the result parsed as
     * top-level declarations. The generated items are queued via [pendingTopLevels].
     */
    private fun parseTopLevelInlineFor(): TopLevel {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.FOR, "Expected 'for'")
        // A single loop variable. Parallel lists are iterated by binding one list and
        // reading the others with `${otherList[index]}` under `with index` — the
        // tuple form `inline for (A, B) in (L1, L2)` is not supported.
        if (check(TokenType.L_PAREN)) {
            error("tuple 'inline for (A, B) in (L1, L2)' is not supported; use 'inline for A in L1 with index' and index the parallel list as '\${L2[index]}' at line ${start.line}")
        }
        val loopVar = consumeIdentifierLike("Expected loop variable after 'inline for'")
        consume(TokenType.IN, "Expected 'in' after 'inline for' variable")
        val list = parseComptimeForValues()
        // Optional `with index` — binds a 0-based counter usable as `${list[index]}`.
        val indexVar = if (peek().type == TokenType.IDENTIFIER && peek().lexeme == "with") {
            advance()
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
                add(loopVar to list[i])
                if (indexVar != null) add(indexVar to i.toString())
            }
            rendered = foldBraceInterpolation(rendered, bindings)
            rendered = substituteLoopVar(rendered, loopVar, list[i])
            if (indexVar != null) rendered = substituteLoopVar(rendered, indexVar, i.toString())
            rendered = foldListIndexing(rendered)
            pendingTopLevels.addAll(
                Parser(rendered + Token(TokenType.EOF, "", start.line, start.column), typeListEnv).parse().items
            )
        }
        return TopLevel.InlineBlock(emptyList(), start.line, start.column)
    }

    /**
     * Folds `${ <expr> }` compile-time interpolations in an `inline for` body: the
     * loop variables (and the `with index` counter) are substituted bare inside the
     * braces, `list[<int>]` indexing is folded, and the braces are removed — so
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
            val list = if (t.type == TokenType.IDENTIFIER) typeListEnv[t.lexeme] else null
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
            val expr = parseExpr() as Expr.Range
            val from = (expr.from as Expr.IntLiteral).value
            val to = (expr.to as Expr.IntLiteral).value
            val last = if (expr.inclusive) to else to - 1
            return (from..last).map { it.toString() }
        }
        return parseComptimeListValue()
    }

    /** `term (~ term)*` — a compile-time list value (terms are `[…]` literals or list variables). */
    private fun parseComptimeListValue(): List<String> {
        val result = parseComptimeListTerm().toMutableList()
        while (match(TokenType.TILDE)) result.addAll(parseComptimeListTerm())
        return result
    }

    /**
     * True at `[inline] let/fin/var NAME : [ Elem ] =` when it is a compile-time
     * list: any `[Type]` list, or an `inline` list of any element type (e.g.
     * `inline fin ranks: [Int] = […]`). A plain runtime `[Int]` binding is not one.
     */
    private fun isTypeListBindingAhead(): Boolean {
        var i = current
        val hasInline = tokens.getOrNull(i)?.type == TokenType.INLINE
        if (hasInline) i += 1
        if (tokens.getOrNull(i)?.type !in setOf(TokenType.LET, TokenType.FIN, TokenType.VAR)) return false
        i += 1
        if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return false
        i += 1
        if (tokens.getOrNull(i)?.type != TokenType.COLON) return false
        i += 1
        val t = tokens.getOrNull(i)
        // `[ Type ]` — a type list (grouping types, e.g. `[Type]`, `[Int]`).
        if (t?.type == TokenType.L_BRACKET) {
            i += 1
            val elem = tokens.getOrNull(i)
            if (elem?.type != TokenType.IDENTIFIER) return false
            i += 1
            if (tokens.getOrNull(i)?.type != TokenType.R_BRACKET) return false
            return elem.lexeme == "Type" || hasInline
        }
        // `Array<Elem>` — an `inline` compile-time value list (`inline fin ranks: Array<Int> = arr![…]`).
        if (t?.type == TokenType.IDENTIFIER && t.lexeme == "Array") return hasInline
        return false
    }

    /**
     * `[inline] let X: [Elem] = <list>` / `inline fin X: Array<Elem> = arr![…]` —
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
            // `Array<Elem>` — a value list; `[Elem]` value spellings are reserved for
            // grouping types, so a value binding is written `Array<…>`.
            consume(TokenType.IDENTIFIER, "Expected 'Array<…>' or '[Elem]' compile-time list type")
            skipGenericTypeArgs()
        }
        consume(TokenType.EQUAL, "Expected '=' in compile-time list binding")
        typeListEnv[name] = parseComptimeListValue()
        consumeNewline()
        return TopLevel.InlineBlock(emptyList(), start.line, start.column)
    }

    private fun parseComptimeListTerm(): List<String> {
        // `arr![...]` — the array-literal macro used as a compile-time list; accept the
        // `Name !` prefix and parse the bracket body as a list literal of values.
        val isMacro = check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.BANG
        if (isMacro) { advance(); advance() }
        if (match(TokenType.L_BRACKET)) {
            val values = mutableListOf<String>()
            if (!check(TokenType.R_BRACKET)) {
                do {
                    values.add(when {
                        // Plain `[…]` groups *types* only; string/int value lists must
                        // be written `arr![…]`.
                        check(TokenType.STRING_LITERAL) || check(TokenType.INT_LITERAL) -> {
                            if (!isMacro) error("value lists must use 'arr![…]'; plain '[…]' only groups types at line ${peek().line}")
                            if (check(TokenType.STRING_LITERAL)) advance().literal as String
                            else (advance().literal as NumericLiteral).value.toString()
                        }
                        else -> consumeIdentifierLike("Expected a literal or type name in compile-time list")
                    })
                } while (match(TokenType.COMMA))
            }
            consume(TokenType.R_BRACKET, "Expected ']' to close compile-time list")
            return values
        }
        if (isMacro) error("Expected '[...]' after macro prefix in compile-time list at line ${peek().line}")
        val name = consumeIdentifierLike("Expected a compile-time list literal or variable")
        return typeListEnv[name] ?: error("Unknown compile-time list '$name' at line ${peek().line}")
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
        // `prop rank` with loop var `rank`) is untouched — use `$rank` for those.
        val bareOk = valueTokens.size == 1 && valueTokens[0].type == TokenType.IDENTIFIER
        val result = mutableListOf<Token>()
        for (t in tokens) {
            when {
                bareOk && t.type == TokenType.IDENTIFIER && t.lexeme == varName -> result.addAll(valueTokens)
                t.type == TokenType.IDENTIFIER && t.lexeme.contains(placeholder) ->
                    result.addAll(Lexer(t.lexeme.replace(placeholder, value)).tokenize().dropLast(1))
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

    /** `inline zone { ... }` at top level -- alias for `inline { ... }`. */
    private fun parseTopLevelInlineZoneBlock(): TopLevel.InlineBlock {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.ZONE, "Expected 'zone'")
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
            TokenType.ZONE -> parseTopLevelDeepInlineZoneBlock()
            TokenType.IF -> parseTopLevelDeepInlineIf()
            else -> error("Expected '{', 'zone', or 'if' after 'deepinline' at line ${peek().line}")
        }
    }

    /** `deepinline zone { ... }` at top level -- alias for `deepinline { ... }`. */
    private fun parseTopLevelDeepInlineZoneBlock(): TopLevel.DeepInlineBlock {
        val start = peek()
        consume(TokenType.DEEPINLINE, "Expected 'deepinline'")
        consume(TokenType.ZONE, "Expected 'zone'")
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
        val condition = parseExpr()
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
        val condition = parseExpr()
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
        // a compile-time block (e.g. `std.config`'s `deepinline zone`). They apply
        // to type declarations; value bindings fold to compile-time constants and
        // carry no annotations, so any leading annotations there are metadata-only.
        val annotations = parseAnnotations()
        val start = peek()
        return when {
            check(TokenType.FUNC) -> TopLevel.Func(parseFuncDecl(isInline = deepInline, annotations = annotations))
            check(TokenType.TEST) -> parseTestDecl(annotations)
            check(TokenType.ENUM) -> parseEnumDecl(annotations)
            check(TokenType.PACK) -> parsePack(annotations)
            check(TokenType.DECO) -> parseDeco(annotations)
            check(TokenType.FAIL) -> parseFailDecl(annotations)
            check(TokenType.TYPEALIAS) -> parseTypeAlias(annotations)
            check(TokenType.SLOT) -> parseSlot(annotations)
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
                TopLevel.InlineTrace(traceStmt.message, traceStmt.line, traceStmt.column)
            }
            check(TokenType.NOINLINE) -> {
                advance() // consume 'noinline'
                when {
                    check(TokenType.FUNC) -> TopLevel.Func(parseFuncDecl(isInline = false))
                    check(TokenType.FIN) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.FinDecl(name, type, init, start.line, start.column) }
                    check(TokenType.VAR) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.VarDecl(name, type, init, start.line, start.column) }
                    check(TokenType.LET) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.LetDecl(name, type, init, start.line, start.column) }
                    else -> error("Expected 'func', 'fin', 'var', or 'let' after 'noinline' at line ${peek().line}")
                }
            }
            // Bare declarations: inline if deepInline, runtime otherwise
            check(TokenType.VAR) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); if (deepInline) TopLevel.InlineVar(name, init, start.line, start.column) else TopLevel.VarDecl(name, type, init, start.line, start.column) }
            check(TokenType.FIN) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); if (deepInline) TopLevel.InlineFin(name, init, start.line, start.column) else TopLevel.FinDecl(name, type, init, start.line, start.column) }
            check(TokenType.LET) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); if (deepInline) TopLevel.InlineLet(name, init, start.line, start.column) else TopLevel.LetDecl(name, type, init, start.line, start.column) }
            check(TokenType.IF) -> {
                consume(TokenType.IF, "Expected 'if'")
                val condition = parseExpr()
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
     * `pack Name { fields }` — a struct declaration. Fields are `[var|fin|let] name: Type [= default]`,
     * one per line.
     */
    private fun parsePack(
        annotations: List<Annotation> = emptyList(),
        visibility: Visibility = Visibility.EXPOSE,
        isBridge: Boolean = false,
        isOpaque: Boolean = false,
    ): TopLevel.Pack {
        val start = peek()
        consume(TokenType.PACK, "Expected 'pack'")
        // Type parameters precede the name: `pack<T> Box`, `pack<K, V> Map`.
        val tp = parseTypeParams()
        val name = consume(TokenType.IDENTIFIER, "Expected pack name").lexeme
        if (check(TokenType.LESS)) {
            error("Type parameters go before the pack name: write 'pack<…> $name', not 'pack $name<…>', at line ${peek().line}")
        }
        val minLen = parseVariadicWhereClause()
        val enforceNumFields = annotations.any { it.name == "EnforceNumFields" }
        val effectiveVisibility = if (visibility == Visibility.SHIELD) Visibility.EXPOSE else visibility
        if (!check(TokenType.L_BRACE)) {
            consumeNewline()
            return TopLevel.Pack(
                name = name,
                fields = emptyList(),
                typeParams = tp.names,
                line = start.line,
                column = start.column,
                annotations = annotations,
                visibility = effectiveVisibility,
                shielded = visibility == Visibility.SHIELD,
                variadicParam = tp.variadic,
                minVariadicLength = minLen,
                constParams = tp.constParams,
                fieldTemplate = null,
                isBridge = isBridge,
                isOpaque = isOpaque,
            )
        }
        consume(TokenType.L_BRACE, "Expected '{' after pack name")
        skipNewlines()
        // A variadic pack's body is a field-generating template:
        // `inline for Ty in ...T with index { mixin "$index: $Ty" }`.
        val fieldTemplate = if (tp.variadic != null && check(TokenType.INLINE)) parseVariadicFieldTemplate() else null
        val fields = mutableListOf<PackField>()
        if (fieldTemplate == null) {
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                skipNewlines()
                if (check(TokenType.R_BRACE)) break
                fields.add(parsePackField(enforceNumFields = enforceNumFields, forceConfine = isOpaque))
                match(TokenType.COMMA)
                skipNewlines()
            }
        }
        consume(TokenType.R_BRACE, "Expected '}' after pack fields")
        consumeNewline()
        return TopLevel.Pack(
            name = name,
            fields = fields,
            typeParams = tp.names,
            line = start.line,
            column = start.column,
            annotations = annotations,
            visibility = effectiveVisibility,
            shielded = visibility == Visibility.SHIELD,
            variadicParam = tp.variadic,
            minVariadicLength = minLen,
            constParams = tp.constParams,
            fieldTemplate = fieldTemplate,
            isOpaque = isOpaque,
        )
    }

    /** `inline for <loopVar> in <packVar> with index { <fields> }` — a variadic pack's field template. */
    private fun parseVariadicFieldTemplate(): VariadicFieldTemplate {
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.FOR, "Expected 'for'")
        val loopVar = consume(TokenType.IDENTIFIER, "Expected loop type variable").lexeme
        consume(TokenType.IN, "Expected 'in' after loop variable")
        match(TokenType.ELLIPSIS) // preferred spelling: `inline for Ty in ...T`
        val packVar = consume(TokenType.IDENTIFIER, "Expected variadic pack variable").lexeme
        // Optional `with index` — declares that `$index` interpolates the position.
        if (peek().type == TokenType.IDENTIFIER && peek().lexeme == "with") {
            advance() // 'with'
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

    private fun parsePackField(
        preparsedVisibility: Visibility? = null,
        enforceNumFields: Boolean = false,
        preparsedAnnotations: List<Annotation>? = null,
        requireFin: Boolean = false,
        forceConfine: Boolean = false,
    ): PackField {
        val annotations = preparsedAnnotations ?: parseAnnotations()
        val wroteVisibility = peek().type in setOf(
            TokenType.EXPOSE, TokenType.INTERN, TokenType.CONFINE, TokenType.PROTECT, TokenType.SHIELD,
        )
        val parsedVisibility = preparsedVisibility ?: parseVisibility()
        if (forceConfine) {
            if (wroteVisibility && parsedVisibility != Visibility.CONFINE) {
                error("opaque pack fields are confine; remove the visibility modifier at line ${peek().line}")
            }
        }
        val visibility = if (forceConfine) Visibility.CONFINE else parsedVisibility
        if (requireFin && !check(TokenType.FIN)) {
            error("Decorator fields must be declared with 'fin' at line ${peek().line}")
        }
        val mutable = when {
            check(TokenType.VAR) -> { advance(); true }
            check(TokenType.FIN) -> { advance(); false }
            check(TokenType.LET) -> { advance(); false }
            else -> false
        }
        val name = when {
            enforceNumFields && check(TokenType.INT_LITERAL) -> advance().lexeme
            else -> consumeIdentifierLike("Expected field name")
        }
        consume(TokenType.COLON, "Expected ':' after pack field name")
        val type = parseTypeName()
        val default = if (match(TokenType.EQUAL)) parseExpr() else null
        consumeNewline()
        return PackField(name, type, mutable, default, visibility, annotations)
    }

    /** `enum Name { Var1; Var2; ... }` — variants one per line, each optionally with trailing annotations. */
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
                variants.add(consume(TokenType.IDENTIFIER, "Expected variant name").lexeme)
                variantAnns.add(parseAnnotations()) // trailing `@ann` on the same line
            } while (match(TokenType.COMMA) && check(TokenType.IDENTIFIER))
        }
        consume(TokenType.R_BRACE, "Expected '}' after enum variants")
        consumeNewline()
        return TopLevel.Enum(name, variants, start.line, start.column, annotations, variantAnns)
    }

    /** `fail ErrSet { V1, V2 }` — an error-set declaration (variants one per line, each optionally annotated). */
    private fun parseFailDecl(annotations: List<Annotation> = emptyList()): TopLevel.Fail {
        val start = peek()
        consume(TokenType.FAIL, "Expected 'fail'")
        val name = consume(TokenType.IDENTIFIER, "Expected error-set name").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after error-set name")
        skipNewlines()
        val variants = mutableListOf<String>()
        val variantAnns = mutableListOf<List<Annotation>>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            do {
                variants.add(consume(TokenType.IDENTIFIER, "Expected variant name").lexeme)
                variantAnns.add(parseAnnotations()) // trailing `@ann` on the same line
            } while (match(TokenType.COMMA) && check(TokenType.IDENTIFIER))
        }
        consume(TokenType.R_BRACE, "Expected '}' after error-set variants")
        consumeNewline()
        return TopLevel.Fail(name, variants, start.line, start.column, annotations, variantAnns)
    }

    /** Skips `<T, U>` generic type arguments at the current position (erased at IR). */
    private fun skipGenericTypeArgs() {
        if (!check(TokenType.LESS)) return
        advance() // '<'
        var depth = 1
        while (depth > 0) {
            when (peek().type) { TokenType.LESS -> depth++; TokenType.GREATER -> depth--; else -> {} }
            advance()
        }
    }

    /** Parses `<T, U>` generic type arguments at the current position. */
    private fun parseGenericTypeArgsIfPresent(): List<TypeRef> {
        if (!check(TokenType.LESS)) return emptyList()
        advance() // '<'
        val args = mutableListOf<TypeRef>()
        if (!check(TokenType.GREATER)) {
            do {
                args.add(parseTypeArg())
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.GREATER, "Expected '>' after generic type arguments")
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
        check(TokenType.STAR) -> {
            advance()
            TypeRef.Named("*")
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
        val ref = type ?: return traitArgs.firstOrNull() ?: TypeRef.Named("Any")
        val named = ref as? TypeRef.Named ?: return ref
        val index = typeParams.indexOf(named.name)
        return if (index >= 0) traitArgs.getOrElse(index) { named } else named
    }

    /** Parses `Type`, `Type::member`, or `Type::*` into the semantic site identity. */
    private fun parseImplTarget(): String {
        val owner = consume(TokenType.IDENTIFIER, "Expected implementation target").lexeme
        if (!match(TokenType.DOUBLE_COLON)) {
            skipGenericTypeArgs()
            return owner
        }
        val member = when {
            match(TokenType.STAR) -> "*"
            else -> consume(TokenType.IDENTIFIER, "Expected member name or '*' after '::'").lexeme
        }
        return "$owner.$member"
    }

    /** Parses one implementation target or `[Target, Other::member]`. */
    /** Expands any compile-time type-list variable target into its member types. */
    private fun expandTypeListTargets(targets: List<String>): List<String> =
        targets.flatMap { typeListEnv[it] ?: listOf(it) }

    private fun parseImplTargets(): List<String> {
        if (!match(TokenType.L_BRACKET)) return listOf(parseImplTarget())
        if (check(TokenType.R_BRACKET)) error("Expected at least one implementation target at line ${peek().line}")
        val targets = mutableListOf<String>()
        do { targets.add(parseImplTarget()) } while (match(TokenType.COMMA))
        consume(TokenType.R_BRACKET, "Expected ']' after implementation targets")
        return targets
    }

    private fun queueBodylessImpls(
        traits: List<Pair<String, List<TypeRef>>>,
        targets: List<String>,
        start: Token,
        args: List<Expr>,
        namedArgs: List<Pair<String, Expr>>,
    ): TopLevel.Impl {
        val implementations = targets.flatMap { target ->
            traits.map { (trait, traitArgs) ->
                TopLevel.Impl(
                    typeName = target,
                    methods = emptyList(),
                    traitName = trait,
                    line = start.line,
                    column = start.column,
                    traitArgs = traitArgs,
                    decoratorArgs = args,
                    decoratorNamedArgs = namedArgs,
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
        val implTypeParams = parseTypeParams()
        // `impl <Spec> as zone for Type { members }` — type-scoped static members
        // attributed to a spec/category (e.g. `impl Number as zone for Ty`). Like
        // `impl zone for Type`, members desugar to mangled top-level items
        // (`Type__member`) per target, reached as `Type::member`. The spec name is
        // informational (parsed and dropped).
        if (check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.AS &&
            tokens.getOrNull(current + 2)?.type == TokenType.ZONE
        ) {
            advance() // spec name (e.g. Number)
            advance() // 'as'
            advance() // 'zone'
            consume(TokenType.FOR, "Expected 'for' after 'impl <spec> as zone'")
            val targets = expandTypeListTargets(parseImplTargets())
            consume(TokenType.L_BRACE, "Expected '{' after 'impl <spec> as zone for …'")
            skipNewlines()
            val bodyTokens = captureBraceBody()
            consumeNewline()
            for (target in targets) {
                val members = Parser(bodyTokens + Token(TokenType.EOF, "", start.line, start.column), typeListEnv).parse().items
                members.forEach { pendingTopLevels.add(mangleTopLevel(it, target)) }
            }
            return TopLevel.Impl(targets.first(), emptyList(), null, start.line, start.column)
        }
        // `impl zone for Type { members }` (or `for [A, B] { … }`) — type-scoped
        // static members, reached as `Type::member`. Desugars to mangled top-level
        // items (`Type__member`) per target type, like a named zone.
        if (check(TokenType.ZONE)) {
            advance()
            consume(TokenType.FOR, "Expected 'for' after 'impl zone'")
            val targets = expandTypeListTargets(parseImplTargets())
            consume(TokenType.L_BRACE, "Expected '{' after 'impl zone for …'")
            skipNewlines()
            val bodyTokens = captureBraceBody()
            consumeNewline()
            for (target in targets) {
                val members = Parser(bodyTokens + Token(TokenType.EOF, "", start.line, start.column), typeListEnv).parse().items
                members.forEach { pendingTopLevels.add(mangleTopLevel(it, target)) }
            }
            return TopLevel.Impl(targets.first(), emptyList(), null, start.line, start.column)
        }
        // `[bridge] impl oper<OP> for Type(params): Ret { … }` — an operator overload
        // for any operator (comparison/arithmetic/bitwise). `oper[]`/`oper[]=` keep
        // their dedicated block form below; every other operator uses this form.
        if (check(TokenType.OPER) && peekNext()?.type != TokenType.L_BRACKET) {
            return parseOperatorImpl(start, isBridge, annotations)
        }
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
            skipGenericTypeArgs() // `impl ctor(...) for Type<T>` — discard erased type args
            val contracts = parseContractClauses()
            run {
                val i = nextMeaningfulIndex()
                val tok = tokens.getOrNull(i)
                if (tok?.type == TokenType.ZONE &&
                    tokens.getOrNull(i + 1)?.type == TokenType.L_BRACE
                ) {
                    while (current < i) advance() // skip newlines
                    advance() // 'zone' — the '{' that follows is the ctor body
                }
            }
            if (!check(TokenType.L_BRACE)) {
                consumeNewline()
                return TopLevel.Impl(typeName, emptyList(), null, ctorStart.line, ctorStart.column)
            }
            consume(TokenType.L_BRACE, "Expected '{' before ctor impl body")
            skipNewlines()
            val receiverModifier = parseImplReceiverModifier()
            val receiverName = consumeIdentifierLike("Expected ctor receiver name")
            if (receiverName != "self") error("Expected ctor receiver to be named 'self' at line ${peek().line}")
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
            val receiverModifier = parseImplReceiverModifier()
            val receiverName = consumeIdentifierLike("Expected dtor receiver name")
            if (receiverName != "self") error("Expected dtor receiver to be named 'self' at line ${peek().line}")
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
        if (check(TokenType.AS)) {
            val asStart = advance()
            val targetType = parseTypeName()
            consume(TokenType.FOR, "Expected 'for' after 'impl as <Type>'")
            val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'for'").lexeme
            skipGenericTypeArgs()
            val methodName = castMethodName(targetType)
            consume(TokenType.L_BRACE, "Expected '{' before cast impl body")
            skipNewlines()
            val receiverModifier = parseImplReceiverModifier()
            if (receiverModifier != "ref") {
                error("impl cast receivers are always 'ref self' at line ${asStart.line}")
            }
            val receiverName = consumeIdentifierLike("Expected cast receiver name")
            if (receiverName != "self") error("Expected cast receiver to be named 'self' at line ${peek().line}")
            consume(TokenType.ARROW, "Expected '->' after cast receiver")
            skipNewlines()
            val body = mutableListOf<Stmt>()
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                body.add(parseStmt())
                skipNewlines()
            }
            consume(TokenType.R_BRACE, "Expected '}' after cast impl body")
            consumeNewline()
            val method = FuncDecl(
                name = methodName,
                params = emptyList(),
                returnType = TypeAnnotation.Explicit(targetType),
                body = body,
                line = asStart.line,
                column = asStart.column,
                receiverModifier = "ref",
            )
            return TopLevel.Impl(typeName, listOf(method), null, asStart.line, asStart.column)
        }
        // `impl oper[] for Type { params -> body }` / `impl oper[]= for Type { params -> body }`
        // — a standalone operator overload (params are untyped: self, index[, element]).
        if (check(TokenType.OPER)) {
            val operStart = peek()
            advance() // 'oper'
            consume(TokenType.L_BRACKET, "Expected '[' after 'oper'")
            // Multi-oper form: `impl oper[.. by 1, reverse.. by 1] for Ty` expands to one
            // impl per spec. Routed here (before oper[]/oper[:]/oper[]=) only when the
            // bracket content is an operator spec, so the index/slice forms stay intact.
            if (peek().type in setOf(TokenType.DOT_DOT, TokenType.DOT_DOT_LESS, TokenType.REVERSE)) {
                return parseMultiOperImpl(operStart, isBridge, annotations)
            }
            // `oper[:]` — the Python-style slice overload (`a[1:3]`, `a[:]`, …).
            val isSlice = match(TokenType.COLON)
            consume(TokenType.R_BRACKET, "Expected ']' after 'oper['")
            // `oper[:] by <Spec>` — an optional spec providing the slicing logic.
            val bySpec = if (match(TokenType.BY)) consume(TokenType.IDENTIFIER, "Expected spec name after 'by'").lexeme else null
            val isSet = match(TokenType.EQUAL)
            val methodName = when {
                isSlice -> "slice"
                isSet -> "indexSet"
                else -> "index"
            }
            consume(TokenType.FOR, "Expected 'for' after 'impl oper[]' / 'impl oper[]=']")
            val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'for'").lexeme
            val typeArgs = parseGenericTypeArgsIfPresent()
            consume(TokenType.L_BRACE, "Expected '{' after impl oper[] for Type")
            skipNewlines()
            // Untyped params: `mut ref self, index[, element]` (or `slice` for oper[:]).
            val indexType = if (typeName == "Map" && typeArgs.isNotEmpty()) typeArgs[0] else TypeRef.Named("Int")
            val valueType = when {
                typeName == "Map" && typeArgs.size >= 2 -> typeArgs[1]
                typeArgs.isNotEmpty() -> typeArgs[0]
                else -> TypeRef.Named("Any")
            }
            val paramTypes = when {
                // `oper[:] by MapSlice` names the operand type; default to `Slice`.
                isSlice -> listOf(TypeRef.Named(typeName), TypeRef.Named(bySpec ?: "Slice"))
                isSet -> listOf(TypeRef.Named(typeName), indexType, valueType)
                else -> listOf(TypeRef.Named(typeName), indexType)
            }
            val params = mutableListOf<Param>()
            var pidx = 0
            do {
                val modifier = when {
                    check(TokenType.MUT) && peekNext()?.type == TokenType.REF -> { advance(); advance(); "mut ref" }
                    match(TokenType.REF) -> "ref"
                    match(TokenType.MUT) -> "mut"
                    else -> ""
                }
                val name = consumeIdentifierLike("Expected parameter name in oper impl")
                val explicitType = if (match(TokenType.COLON)) parseTypeName() else null
                params.add(Param(name, explicitType ?: paramTypes.getOrElse(pidx) { TypeRef.Named("Any") }, modifier = modifier))
                pidx++
            } while (match(TokenType.COMMA))
            consume(TokenType.ARROW, "Expected '->' after oper parameters")
            // The first param is `self` (injected by SymbolCollector); drop it from the FuncDecl.
            if (params.isNotEmpty()) params.removeAt(0)
            skipNewlines()
            val body = mutableListOf<Stmt>()
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                body.add(parseStmt())
                skipNewlines()
            }
            consume(TokenType.R_BRACE, "Expected '}' after oper body")
            consumeNewline()
            val returnType = TypeAnnotation.Explicit(if (isSet) TypeRef.Named("Unit") else valueType)
            return TopLevel.Impl(
                typeName,
                listOf(FuncDecl(methodName, params, returnType, body, false, emptyList(), operStart.line, operStart.column)),
                bySpec,
                operStart.line,
                operStart.column,
                annotations = annotations,
                isBridge = isBridge,
            )
        }
        if (check(TokenType.DEREF)) {
            val derefStart = advance()
            consume(TokenType.FOR, "Expected 'for' after 'impl deref'")
            val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'for'").lexeme
            skipGenericTypeArgs() // `impl deref for Ptr<T>` — discard erased type args
            consume(TokenType.L_BRACE, "Expected '{' after impl deref for Type")
            skipNewlines()
            val params = mutableListOf<Param>()
            do {
                val modifier = when {
                    check(TokenType.MUT) && peekNext()?.type == TokenType.REF -> { advance(); advance(); "mut ref" }
                    match(TokenType.REF) -> "ref"
                    match(TokenType.MUT) -> "mut"
                    else -> ""
                }
                val name = consumeIdentifierLike("Expected receiver name in deref impl")
                params.add(Param(name, TypeRef.Named(typeName), modifier = modifier))
            } while (match(TokenType.COMMA))
            consume(TokenType.ARROW, "Expected '->' after deref receiver")
            if (params.size != 1) error("'impl deref' expects exactly one receiver parameter at line ${derefStart.line}")
            skipNewlines()
            val body = mutableListOf<Stmt>()
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                body.add(parseStmt())
                skipNewlines()
            }
            consume(TokenType.R_BRACE, "Expected '}' after deref body")
            consumeNewline()
            return TopLevel.Impl(
                typeName,
                listOf(FuncDecl("deref", emptyList(), TypeAnnotation.Explicit(TypeRef.Named("Any")), body, false, emptyList(), derefStart.line, derefStart.column)),
                null,
                derefStart.line,
                derefStart.column,
            )
        }
        val isPackImpl = match(TokenType.PACK)
        val traitHeads = if (match(TokenType.L_BRACKET)) {
            if (check(TokenType.R_BRACKET)) error("Expected at least one decorator after 'impl [' at line ${peek().line}")
            val names = mutableListOf<Pair<String, List<TypeRef>>>()
            do {
                val name = consume(TokenType.IDENTIFIER, "Expected decorator name in implementation list").lexeme
                if (check(TokenType.LESS)) {
                    error("Generic arguments are not supported inside decorator implementation lists at line ${peek().line}")
                }
                names.add(name to emptyList())
            } while (match(TokenType.COMMA))
            consume(TokenType.R_BRACKET, "Expected ']' after decorator implementation list")
            names
        } else {
            val name = consume(TokenType.IDENTIFIER, "Expected type or trait name after 'impl'").lexeme
            listOf(name to parseGenericTypeArgsIfPresent())
        }
        val (first, firstArgs) = traitHeads.first()
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
            if (isPackImpl) error("'impl pack' cannot be used for spec implementations at line ${peek().line}")
            traitName = first
            traitArgs = firstArgs
            implementationTargets = expandTypeListTargets(parseImplTargets())
            typeName = implementationTargets.first()
        } else if (traitHeads.size > 1) {
            error("Decorator implementation lists require 'for Target' at line ${start.line}")
        } else if (decoratorArgs.isNotEmpty() || decoratorNamedArgs.isNotEmpty()) {
            error("Decorator implementation arguments require 'for Type' at line ${start.line}")
        }
        if (!check(TokenType.L_BRACE)) {
            consumeNewline()
            if (traitName != null && (traitHeads.size > 1 || implementationTargets.size > 1)) {
                return queueBodylessImpls(
                    traitHeads,
                    implementationTargets,
                    start,
                    decoratorArgs,
                    decoratorNamedArgs,
                )
            }
            return TopLevel.Impl(
                typeName, emptyList(), traitName, start.line, start.column,
                isPackImpl = isPackImpl,
                traitArgs = traitArgs,
                decoratorArgs = decoratorArgs,
                decoratorNamedArgs = decoratorNamedArgs,
                typeParams = implTypeParams.names,
                variadicParam = implTypeParams.variadic,
            )
        }
        if (traitHeads.size > 1 || implementationTargets.size > 1 || typeName.contains('.')) {
            error("Grouped and member-target implementations must be bodyless at line ${start.line}")
        }
        consume(TokenType.L_BRACE, "Expected '{' after impl type")
        skipNewlines()
        if (traitName != null && isSelfReceiverHeaderAhead()) {
            val receiverModifier = parseImplReceiverModifier()
            if (receiverModifier != "ref") {
                error("spec callback impl receivers are always 'ref self' at line ${start.line}")
            }
            val receiverName = consumeIdentifierLike("Expected callback receiver name")
            if (receiverName != "self") error("Expected callback receiver to be named 'self' at line ${peek().line}")
            consume(TokenType.ARROW, "Expected '->' after callback receiver")
            skipNewlines()
            val body = mutableListOf<Stmt>()
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                body.add(parseStmt())
                skipNewlines()
            }
            consume(TokenType.R_BRACE, "Expected '}' after spec callback impl body")
            consumeNewline()
            // The callback's declared `ref self` would type `self` as the spec's
            // (erased) self type. Drop it so SymbolCollector injects `self` with the
            // impl's concrete type (e.g. Int), matching the oper/cast impl convention.
            val callbackParams = callbackTraitParams(traitName, traitArgs).toMutableList()
            if (callbackParams.firstOrNull()?.name == "self") callbackParams.removeAt(0)
            val method = FuncDecl(
                name = callbackTraitMethodName(traitName, traitArgs),
                params = callbackParams,
                returnType = callbackTraitReturnType(traitName, traitArgs),
                body = body,
                line = start.line,
                column = start.column,
                receiverModifier = "ref",
                memberCallStyle = callbackTraitCallStyle(traitName),
            )
            return TopLevel.Impl(
                typeName, listOf(method), traitName, start.line, start.column,
                traitArgs = traitArgs,
                decoratorArgs = decoratorArgs,
                decoratorNamedArgs = decoratorNamedArgs,
            )
        }
        val methods = mutableListOf<FuncDecl>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            val memberAnnotations = parseAnnotations()
            val methodStart = peek()
            val isInline = match(TokenType.INLINE)
            val isVirt = match(TokenType.VIRT)
            val visibility = parseVisibility()
            when {
                check(TokenType.PROP) -> {
                    advance()
                    val propName = consume(TokenType.IDENTIFIER, "Expected property name").lexeme
                    val propType: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
                    if (match(TokenType.EQUAL)) {
                        // `prop name: T = expr` — expression-body property (returns the expression).
                        val expr = parseExpr()
                        consumeNewline()
                        methods.add(FuncDecl(propName, emptyList(), propType, listOf(Stmt.Return(expr, expr.line, expr.column)), false, emptyList(), methodStart.line, methodStart.column, annotations = memberAnnotations, visibility = visibility, receiverModifier = "ref", memberCallStyle = MemberCallStyle.PROPERTY))
                    } else {
                        consume(TokenType.L_BRACE, "Expected '{' after prop type")
                        skipNewlines()
                        val propBody = parseBlock()
                        consume(TokenType.R_BRACE, "Expected '}' after prop body")
                        consumeNewline()
                        methods.add(FuncDecl(propName, emptyList(), propType, propBody, false, emptyList(), methodStart.line, methodStart.column, annotations = memberAnnotations, visibility = visibility, receiverModifier = "ref", memberCallStyle = MemberCallStyle.PROPERTY))
                    }
                }
                check(TokenType.OPER) -> error("oper[] overloads are only allowed as standalone 'impl oper[] for ${typeName} { ... }' at line ${peek().line}")
                check(TokenType.FUNC) -> methods.add(parseFuncDecl(isInline, annotations = memberAnnotations, isVirtual = isVirt, visibility = visibility))
                check(TokenType.TASK) -> methods.add(parseFuncDecl(isInline, annotations = memberAnnotations, isVirtual = isVirt, isTask = true, visibility = visibility))
                check(TokenType.FLOW) -> methods.add(parseFuncDecl(isInline, annotations = memberAnnotations, isVirtual = isVirt, isFlow = true, visibility = visibility))
                else -> error("Expected 'prop', 'func', 'task', or 'flow' in impl block at line ${peek().line}")
            }
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after impl methods")
        consumeNewline()
        return TopLevel.Impl(
            typeName, methods, traitName, start.line, start.column,
            isPackImpl = isPackImpl,
            traitArgs = traitArgs,
            decoratorArgs = decoratorArgs,
            decoratorNamedArgs = decoratorNamedArgs,
            typeParams = implTypeParams.names,
            variadicParam = implTypeParams.variadic,
        )
    }

    /**
     * `oper[] (i: T): R { … }` or `oper[]= (i: T, v: R) { … }` — index-operator
     * overloads inside an impl. Registered as the methods `index` / `indexSet`, so
     * `target[i]` / `target[i] = v` resolve to `Type_index(self, i)` / `Type_indexSet(self, i, v)`.
     */
    private fun parseOperMethod(start: Token, visibility: Visibility = Visibility.EXPOSE): FuncDecl {
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
     * `infx Type.method(params): Ret { body }` — an extension method invokable as an
     * infix call (`receiver method arg` => `Type_method(receiver, arg)`).
     *
     * Desugars to an [TopLevel.Impl] with a single method. The impl machinery injects
     * the implicit `self` parameter and registers `Type_method`, and the parser already
     * turns `a method b` into `a.method(b)`, so no semantic/IR/backend changes are needed.
     */
    private fun parseInfx(): TopLevel {
        val start = peek()
        consume(TokenType.INFX, "Expected 'infx'")
        // Optional type params: `infx<K, V> K.to(v)` — a generic infix whose
        // receiver is a type parameter, so it applies to any receiver type.
        val typeParams = parseTypeParams()
        val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'infx'").lexeme
        consume(TokenType.DOT, "Expected '.' between type and method name")
        val methodName = consume(TokenType.IDENTIFIER, "Expected method name").lexeme
        consume(TokenType.L_PAREN, "Expected '(' after infx method name")
        val params = parseParams()
        consume(TokenType.R_PAREN, "Expected ')' after infx parameters")
        val returnType: TypeAnnotation = if (match(TokenType.COLON)) {
            TypeAnnotation.Explicit(parseTypeName())
        } else {
            TypeAnnotation.Inferred
        }
        val universal = typeName in typeParams.names
        consume(TokenType.L_BRACE, "Expected '{' before infx method body")
        skipNewlines()
        // A universal infix binds its receiver through an explicit header
        // (`{ ref self -> … }`), like an extension function, so `self` is the
        // receiver value inside the body.
        val receiverModifier = if (universal) {
            val mod = parseExtensionReceiverModifier()
            val receiverName = consumeIdentifierLike("Expected infx receiver name")
            if (receiverName != "self") error("Expected infx receiver to be named 'self' at line ${peek().line}")
            consume(TokenType.ARROW, "Expected '->' after infx receiver")
            skipNewlines()
            mod
        } else "mut ref"
        val body = mutableListOf<Stmt>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            body.add(parseStmt())
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after infx method body")
        consumeNewline()
        // When the receiver is a type parameter, this is a universal infix
        // (`a to b` works for any `a`). Desugar to a generic top-level function
        // `method(self: Recv, params...)`; method resolution falls back to it for
        // any receiver type.
        if (universal) {
            val self = Param("self", TypeRef.Named(typeName))
            val fn = FuncDecl(
                methodName, listOf(self) + params, returnType, body, false, typeParams.names,
                start.line, start.column, isUniversalInfix = true, receiverModifier = receiverModifier,
            )
            return TopLevel.Func(fn)
        }
        val method = FuncDecl(methodName, params, returnType, body, false, emptyList(), start.line, start.column)
        return TopLevel.Impl(typeName, listOf(method), null, start.line, start.column)
    }

    private fun isExtensionFuncAhead(): Boolean =
        check(TokenType.FUNC) &&
            peekNext()?.type == TokenType.IDENTIFIER &&
            tokens.getOrNull(current + 2)?.type == TokenType.DOT &&
            tokens.getOrNull(current + 3)?.type in setOf(TokenType.IDENTIFIER, TokenType.REM, TokenType.MEM, TokenType.RET)

    /**
     * `func Type.method(args): Ret { ref self -> body }` — external extension method.
     * The receiver is explicit inside the body header so extensions can choose
     * `ref self` or `mut ref self`; shielded packs reject the mutable form later.
     */
    private fun parseExtensionFunc(visibility: Visibility = Visibility.EXPOSE): TopLevel.Impl {
        val start = peek()
        consume(TokenType.FUNC, "Expected 'func'")
        val typeName = consume(TokenType.IDENTIFIER, "Expected extension receiver type").lexeme
        skipGenericTypeArgs()
        consume(TokenType.DOT, "Expected '.' between extension type and method name")
        val methodName = consumeIdentifierLike("Expected extension method name")
        consume(TokenType.L_PAREN, "Expected '(' after extension method name")
        val params = parseParams()
        consume(TokenType.R_PAREN, "Expected ')' after extension parameters")
        val returnType: TypeAnnotation = if (match(TokenType.COLON)) {
            TypeAnnotation.Explicit(parseTypeName())
        } else {
            TypeAnnotation.Inferred
        }
        consume(TokenType.L_BRACE, "Expected '{' before extension body")
        skipNewlines()
        val receiverModifier = parseExtensionReceiverModifier()
        val receiverName = consumeIdentifierLike("Expected extension receiver name")
        if (receiverName != "self") error("Expected extension receiver to be named 'self' at line ${peek().line}")
        consume(TokenType.ARROW, "Expected '->' after extension receiver")
        skipNewlines()
        val body = mutableListOf<Stmt>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            body.add(parseStmt())
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after extension body")
        consumeNewline()
        val method = FuncDecl(
            name = methodName,
            params = params,
            returnType = returnType,
            body = body,
            line = start.line,
            column = start.column,
            visibility = visibility,
            receiverModifier = receiverModifier,
        )
        return TopLevel.Impl(typeName, listOf(method), null, start.line, start.column, isExtension = true)
    }

    private fun parseExtensionReceiverModifier(): String =
        when {
            check(TokenType.MUT) && peekNext()?.type == TokenType.REF -> {
                advance()
                advance()
                "mut ref"
            }
            match(TokenType.REF) -> "ref"
            else -> error("Expected 'ref self' or 'mut ref self' extension receiver at line ${peek().line}")
        }

    private fun parseImplReceiverModifier(): String =
        when {
            check(TokenType.MUT) && peekNext()?.type == TokenType.REF -> {
                advance()
                advance()
                "mut ref"
            }
            match(TokenType.REF) -> "ref"
            match(TokenType.MUT) -> "mut ref"
            else -> "mut ref"
        }

    private fun parseSpecReceiverModifier(): String =
        when {
            check(TokenType.MUT) && peekNext()?.type == TokenType.REF -> {
                advance()
                advance()
                "mut ref"
            }
            match(TokenType.REF) -> "ref"
            match(TokenType.MUT) -> "mut"
            else -> error("Expected 'ref self' or 'mut ref self' in spec callback at line ${peek().line}")
        }

    /**
     * `bridge <target> { func sigs }` — declares extern functions for FFI.
     * Each signature is `func name(params): RetType` (no body).
     */
    /** After `bridge`, whether an optional `.Target` is followed by `func`. */
    private fun isBridgeFuncAhead(): Boolean {
        var i = current + 1
        if (tokens.getOrNull(i)?.type == TokenType.DOT) {
            i++
            if (tokens.getOrNull(i)?.type == TokenType.IDENTIFIER) i++
        }
        return tokens.getOrNull(i)?.type == TokenType.FUNC
    }

    /**
     * `bridge [.Target] func name(params): Ret` — a single bodyless extern function
     * (the compiler or a backend provides the implementation, keyed by name). It is
     * shorthand for a one-entry `bridge` block; the default target is `Compiler`.
     * Type parameters are accepted but not retained (externs are monomorphic).
     */
    private fun parseBridgeFunc(annotations: List<Annotation> = emptyList()): TopLevel.Bridge {
        val start = consume(TokenType.BRIDGE, "Expected 'bridge'")
        val target = if (match(TokenType.DOT)) {
            consume(TokenType.IDENTIFIER, "Expected bridge target (e.g. Compiler, C, JS)").lexeme
        } else "Compiler"
        consume(TokenType.FUNC, "Expected 'func' after 'bridge'")
        // Type params may appear before or after the name (`bridge func<T> fill` or
        // `bridge func fill<T>`); capture them so a generic return type like
        // `Array<T>` erases correctly at registration.
        val tpBefore = parseTypeParams()
        val name = consumeIdentifierLike("Expected bridge function name")
        val tpAfter = parseTypeParams()
        consume(TokenType.L_PAREN, "Expected '(' after bridge function name")
        val params = parseParams()
        consume(TokenType.R_PAREN, "Expected ')' after bridge parameters")
        val returnType = if (match(TokenType.COLON)) parseTypeName() else TypeRef.Named("Unit")
        consumeNewline()
        return TopLevel.Bridge(
            target,
            listOf(TopLevel.BridgeSig(name, params, returnType, start.line, start.column, tpBefore.names + tpAfter.names)),
            start.line, start.column, annotations,
        )
    }

    private fun parseBridge(annotations: List<Annotation> = emptyList()): TopLevel.Bridge {
        val start = consume(TokenType.BRIDGE, "Expected 'bridge'")
        match(TokenType.DOT) // `bridge .C { … }` — the leading dot is optional
        val target = consume(TokenType.IDENTIFIER, "Expected bridge target (e.g. C, JS)").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after bridge target")
        skipNewlines()
        val funcs = mutableListOf<TopLevel.BridgeSig>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            consume(TokenType.FUNC, "Expected 'func' in bridge block")
            val declaredBase = StringBuilder(consume(TokenType.IDENTIFIER, "Expected extern function name").lexeme)
            while (check(TokenType.DOT) && peekNext()?.type == TokenType.IDENTIFIER) {
                advance()
                declaredBase.append('.').append(advance().lexeme)
            }
            val declaredName = declaredBase.toString()
            val alias = if (match(TokenType.USE)) {
                consume(TokenType.AS, "Expected 'as' after 'use'")
                consume(TokenType.IDENTIFIER, "Expected extern symbol after 'use as'").lexeme
            } else null
            consume(TokenType.L_PAREN, "Expected '('")
            val params = parseParams()
            consume(TokenType.R_PAREN, "Expected ')'")
            val returnType = if (match(TokenType.COLON)) parseTypeName() else TypeRef.Named("Unit")
            consumeNewline()
            val fname = alias ?: declaredName
            funcs.add(TopLevel.BridgeSig(fname, params, returnType, start.line, start.column))
            if (alias != null && '.' !in declaredName) makeBridgeWrapper(declaredName, alias, params, returnType, start.line)
        }
        consume(TokenType.R_BRACE, "Expected '}' after bridge functions")
        consumeNewline()
        return TopLevel.Bridge(target, funcs, start.line, start.column, annotations)
    }

    /** `solo Name { fields; methods }` — a singleton struct with one shared instance. */
    private fun parseSolo(visibility: Visibility = Visibility.EXPOSE, annotations: List<Annotation> = emptyList()): TopLevel.Solo {
        val start = consume(TokenType.SOLO, "Expected 'solo'")
        val name = consume(TokenType.IDENTIFIER, "Expected solo name").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after solo name")
        skipNewlines()
        val fields = mutableListOf<PackField>()
        val methods = mutableListOf<FuncDecl>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            val memberAnnotations = parseAnnotations()
            val memberVisibility = parseVisibility()
            if (check(TokenType.FUNC)) {
                methods.add(parseFuncDecl(annotations = memberAnnotations, visibility = memberVisibility))
            } else {
                fields.add(parsePackField(memberVisibility, preparsedAnnotations = memberAnnotations))
            }
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after solo body")
        consumeNewline()
        return TopLevel.Solo(name, fields, methods, start.line, start.column, visibility, annotations)
    }

    /**
     * `[leaf] node Name(var|fin param: Type, ...) [: Parent(args)] { methods; fields }`
     * — an inheritable type. Ctor params are fields. `repl func` marks overrides.
     */
    private fun parseNode(
        isLeaf: Boolean = false,
        visibility: Visibility = Visibility.EXPOSE,
        annotations: List<Annotation> = emptyList(),
        isAbstract: Boolean = false,
    ): TopLevel.Node {
        val start = peek()
        if (isLeaf) consume(TokenType.LEAF, "Expected 'leaf'")
        if (isAbstract) consume(TokenType.ABSTRACT, "Expected 'abstract'")
        match(TokenType.NODE) // `leaf Name`, `abstract node Name`, or `abstract Name` — node is optional
        val name = consume(TokenType.IDENTIFIER, "Expected node name").lexeme
        // Ctor params: (var|fin name: Type, ...)
        val params = mutableListOf<TopLevel.NodeParam>()
        consume(TokenType.L_PAREN, "Expected '(' after node name")
        if (!check(TokenType.R_PAREN)) {
            do {
                val mutable = when {
                    check(TokenType.VAR) -> { advance(); true }
                    check(TokenType.FIN) -> { advance(); false }
                    else -> true
                }
                val pname = consume(TokenType.IDENTIFIER, "Expected ctor param name").lexeme
                consume(TokenType.COLON, "Expected ':' after ctor param name")
                val ptype = parseTypeName()
                params.add(TopLevel.NodeParam(pname, ptype, mutable))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.R_PAREN, "Expected ')' after ctor params")
        // Optional parent: : Parent(args)
        var parent: String? = null
        var parentArgs = emptyList<Expr>()
        if (match(TokenType.COLON)) {
            parent = consume(TokenType.IDENTIFIER, "Expected parent node name").lexeme
            if (match(TokenType.L_PAREN)) {
                parentArgs = mutableListOf()
                if (!check(TokenType.R_PAREN)) {
                    do { parentArgs.add(parseExpr()) } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_PAREN, "Expected ')' after parent args")
                parentArgs = parentArgs.toList()
            }
        }
        // Body: methods (func / repl func) and extra fields
        consume(TokenType.L_BRACE, "Expected '{' after node header")
        skipNewlines()
        val methods = mutableListOf<FuncDecl>()
        val extraFields = mutableListOf<PackField>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            val memberAnnotations = parseAnnotations()
            val isRepl = match(TokenType.REPL)
            val isVirt = match(TokenType.VIRT)
            val memberVisibility = parseVisibility()
            when {
                check(TokenType.FUNC) -> {
                    val method = parseFuncDecl(annotations = memberAnnotations, isOverride = isRepl, isVirtual = isVirt || isRepl, visibility = memberVisibility)
                    methods.add(method)
                }
                check(TokenType.PROP) -> {
                    // `prop name: T { body }` — computed property. Lowered as a zero-arg method.
                    advance()
                    val propName = consume(TokenType.IDENTIFIER, "Expected property name").lexeme
                    val propType: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
                    consume(TokenType.L_BRACE, "Expected '{' after prop type")
                    skipNewlines()
                    val propBody = parseBlock()
                    consume(TokenType.R_BRACE, "Expected '}' after prop body")
                    consumeNewline()
                    methods.add(FuncDecl(propName, emptyList(), propType, propBody, false, emptyList(), peek().line, peek().column, annotations = memberAnnotations, visibility = memberVisibility, receiverModifier = "ref", memberCallStyle = MemberCallStyle.PROPERTY))
                }
                check(TokenType.CTOR) -> {
                    // `ctor(params) { body }` — secondary constructor. Lowered as `ctor__<type>` function.
                    advance()
                    consume(TokenType.L_PAREN, "Expected '(' after ctor")
                    val ctorParams = parseParams()
                    consume(TokenType.R_PAREN, "Expected ')' after ctor params")
                    consume(TokenType.L_BRACE, "Expected '{' after ctor header")
                    skipNewlines()
                    val ctorBody = parseBlock()
                    consume(TokenType.R_BRACE, "Expected '}' after ctor body")
                    consumeNewline()
                    methods.add(FuncDecl("ctor", ctorParams, TypeAnnotation.Inferred, ctorBody, false, emptyList(), peek().line, peek().column, visibility = memberVisibility))
                }
                check(TokenType.DTOR) -> {
                    // `dtor { body }` — destructor. Lowered as `dtor__<type>` function.
                    advance()
                    consume(TokenType.L_BRACE, "Expected '{' after dtor")
                    skipNewlines()
                    val dtorBody = parseBlock()
                    consume(TokenType.R_BRACE, "Expected '}' after dtor body")
                    consumeNewline()
                    methods.add(FuncDecl("dtor", emptyList(), TypeAnnotation.Inferred, dtorBody, false, emptyList(), peek().line, peek().column, visibility = memberVisibility))
                }
                check(TokenType.VAR) || check(TokenType.FIN) || check(TokenType.LET) -> {
                    extraFields.add(parsePackField(memberVisibility, preparsedAnnotations = memberAnnotations))
                }
                else -> error("Expected 'func', 'repl func', 'virt func', 'prop', 'ctor', 'dtor', or field in node body at line ${peek().line}")
            }
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after node body")
        consumeNewline()
        return TopLevel.Node(name, params, methods, parent, parentArgs, isLeaf, isAbstract, extraFields, start.line, start.column, visibility, annotations)
    }

    /**
     * `wrap Name { solo Type(args); … }` — a DI container that wires singletons with
     * construction args. Each `solo Type(args)` generates a `__singleton_Type` factory
     * that constructs the type with the given arguments.
     */
    private fun parseWrap(): TopLevel.Wrap {
        val start = consume(TokenType.WRAP, "Expected 'wrap'")
        val name = consume(TokenType.IDENTIFIER, "Expected wrap name").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after wrap name")
        skipNewlines()
        val registrations = mutableListOf<TopLevel.WrapReg>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            if (match(TokenType.SOLO)) {
                val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'solo'").lexeme
                val args = if (match(TokenType.L_PAREN)) {
                    val a = mutableListOf<Expr>()
                    if (!check(TokenType.R_PAREN)) {
                        do { a.add(parseExpr()) } while (match(TokenType.COMMA))
                    }
                    consume(TokenType.R_PAREN, "Expected ')' after solo args")
                    a
                } else emptyList()
                registrations.add(TopLevel.WrapReg(typeName, args, null, start.line, start.column))
            } else {
                error("Expected 'solo' in wrap block at line ${peek().line}")
            }
            consumeNewline()
        }
        consume(TokenType.R_BRACE, "Expected '}' after wrap body")
        consumeNewline()
        return TopLevel.Wrap(name, registrations, start.line, start.column)
    }

    /** `slot Name { Variant(Type); Variant2(Type1, Type2); Variant3 }` — a tagged union. */
    private fun parseSlot(annotations: List<Annotation> = emptyList()): TopLevel.Slot {
        val start = peek()
        consume(TokenType.SLOT, "Expected 'slot'")
        val name = consume(TokenType.IDENTIFIER, "Expected slot name").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after slot name")
        skipNewlines()
        val variants = mutableListOf<TopLevel.SlotVariant>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            val vname = consume(TokenType.IDENTIFIER, "Expected variant name").lexeme
            val payloadTypes = if (match(TokenType.L_PAREN)) {
                val types = mutableListOf<TypeRef>()
                if (!check(TokenType.R_PAREN)) {
                    do {
                        // `Ok(value: Int)` — an optional field name before the
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
        consume(TokenType.R_BRACE, "Expected '}' after slot variants")
        consumeNewline()
        return TopLevel.Slot(name, variants, start.line, start.column, annotations)
    }

    /**
     * `meta Name { arm; arm; … }` — a pattern-driven macro declaration.
     *
     * Each arm is `delim-pattern delim => template-expr`, where the written
     * delimiter (`()`/`[]`/`{}`) is stored for diagnostics but matching is
     * delimiter-agnostic. The pattern is `Empty` (`[]`/`()`/`{}`) or a spread
     * capture `...$name`. The template is an ordinary expression parsed via
     * [parseExpr] — `$name` is a normal identifier, `...$name` a normal spread.
     */
    private fun parseMeta(): TopLevel.Meta {
        val start = peek()
        consume(TokenType.META, "Expected 'meta'")
        // `meta type { pattern => template; … }` — a type-level macro declaration.
        // Patterns are type-sugar forms (`[T]`, `[T; N]`, `{T}`, `[K: V]`, `(...T)`);
        // templates are types. Rules are stored on the Program (rewriting pass is
        // scaffolded, not yet active — see [TypeTypeArm]); the declaration itself
        // emits no runtime item, so return a no-op block.
        if (check(TokenType.TYPE)) {
            advance() // 'type'
            consume(TokenType.L_BRACE, "Expected '{' after 'meta type'")
            skipNewlines()
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                skipNewlines()
                if (check(TokenType.R_BRACE)) break
                pendingTypeMacroRules.add(parseTypeMacroArm())
                skipNewlines()
            }
            consume(TokenType.R_BRACE, "Expected '}' after 'meta type' arms")
            consumeNewline()
            return TopLevel.Meta("__type_macro__", emptyList(), start.line, start.column)
        }
        // Optional sigil prefix: `meta mut vec`, `meta # map`, `meta !# set`,
        // `meta ^ map`. The prefix is folded into the macro's name (e.g. `# map`)
        // so it matches the equivalently-prefixed invocation.
        val prefix = parseMetaPrefix()
        val bareName = consume(TokenType.IDENTIFIER, "Expected macro name after 'meta'").lexeme
        val name = if (prefix.isEmpty()) bareName else "$prefix $bareName"
        consume(TokenType.L_BRACE, "Expected '{' after macro name")
        skipNewlines()
        val arms = mutableListOf<MacroArm>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            arms.add(parseMacroArm())
        }
        consume(TokenType.R_BRACE, "Expected '}' after macro arms")
        consumeNewline()
        return TopLevel.Meta(name, arms, start.line, start.column)
    }

    /**
     * A sigil prefix used by container macros to select a concrete implementation:
     * `mut`, `#` (hash), `!#` (linked-hash), `^` (tree). Returns "" when absent.
     */
    private fun parseMetaPrefix(): String = when {
        match(TokenType.MUT) -> "mut"
        match(TokenType.CARET) -> "^"
        check(TokenType.BANG) && peekNext()?.type == TokenType.HASH -> { advance(); advance(); "!#" }
        match(TokenType.HASH) -> "#"
        else -> ""
    }

    /**
     * One `meta type` arm: an optional sigil prefix, a type-sugar pattern, `=>`,
     * and a type template. The pattern kind ([TypeFormKind]), its hole names, and
     * the prefix are captured for the (staged) rewriting pass.
     */
    private fun parseTypeMacroArm(): TypeTypeArm {
        val prefix = parseMetaPrefix()
        val (kind, holes) = parseTypeMacroPattern()
        consume(TokenType.FAT_ARROW, "Expected '=>' after 'meta type' pattern")
        val template = parseTypeName()
        return TypeTypeArm(kind, holes, template, prefix)
    }

    private fun parseTypeMacroPattern(): Pair<TypeFormKind, List<String>> {
        return when {
            check(TokenType.L_BRACKET) -> {
                advance() // '['
                val first = parseTypeName()
                when {
                    match(TokenType.SEMICOLON) -> {
                        // `[T; *]` or `[T;]` — an unsized array pattern (`*` / empty size
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
     * One macro arm: `delim <pattern> delim => <template>`.
     *
     * Pattern forms (MVP): `[]`/`()`/`{}` → [MacroPattern.Empty]; `[...$name]`
     * → [MacroPattern.SeqCapture]. The closing delimiter must match the opening
     * one. The template is a single expression (the arm's expansion).
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
                // `...${key: value}` — a key/value destructuring capture. The lexer
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
            else -> error("Macro arm pattern must be empty or '...\$name' at line ${peek().line}")
        }
        consume(close, "Expected matching delimiter after macro arm pattern")
        consume(TokenType.FAT_ARROW, "Expected '=>' after macro arm pattern")
        val template = parseExpr()
        consumeNewline()
        return MacroArm(delimiter, pattern, template)
    }

    /** `spec Name { func method(params): Ret ... }` or compact callback `spec Into<T>: T { ref self } use as "..."`. */
    private fun parseSpec(): TopLevel.Spec {
        val start = peek()
        consume(TokenType.SPEC, "Expected 'spec'")
        val name = consume(TokenType.IDENTIFIER, "Expected spec name").lexeme
        val typeParams = parseTypeParams() // `spec Comparable<T>` — type parameters accepted (erased for now)
        val hasCallParens = match(TokenType.L_PAREN)
        val callbackParams = if (hasCallParens) {
            val parsed = parseParams()
            consume(TokenType.R_PAREN, "Expected ')' after spec callback parameters")
            parsed
        } else {
            emptyList()
        }
        var parentSpec: TypeRef? = null
        if (match(TokenType.COLON)) {
            val returnType = parseTypeName()
            // `spec MutableList<T>: List<T> { … }` — spec inheritance: the child
            // includes every member of the parent. A `(` instead means this is a
            // callback spec (`spec Into<T>: T (ref self)`), handled below.
            if (!check(TokenType.L_PAREN)) {
                parentSpec = returnType
            } else {
            // Callback receiver in parens: `spec Into<T>: T (ref self) use as "…"`.
            consume(TokenType.L_PAREN, "Expected '(' after spec callback signature")
            skipNewlines()
            val receiverModifier = parseSpecReceiverModifier()
            val receiverName = consumeIdentifierLike("Expected spec callback receiver name")
            consume(TokenType.R_PAREN, "Expected ')' after spec callback receiver")
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
                receiverName = receiverName,
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
        if (!check(TokenType.L_BRACE)) {
            consumeNewline()
            return TopLevel.Spec(name, emptyList(), start.line, start.column, typeParams = typeParams.names, parent = parentSpec)
        }
        consume(TokenType.L_BRACE, "Expected '{' after spec name")
        skipNewlines()
        val methods = mutableListOf<FuncDecl>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            parseAnnotations() // trailing metadata on a requirement is accepted and dropped
            // `prop name: Type` — a property requirement (a zero-arg getter).
            if (check(TokenType.PROP)) {
                advance()
                val pname = consumeIdentifierLike("Expected property name in spec")
                consume(TokenType.COLON, "Expected ':' after spec property name")
                val ptype = parseTypeName()
                consumeNewline()
                methods.add(FuncDecl(
                    pname, emptyList(), TypeAnnotation.Explicit(ptype), emptyList(), false, emptyList(),
                    start.line, start.column, receiverModifier = "ref", memberCallStyle = MemberCallStyle.PROPERTY,
                ))
                continue
            }
            consume(TokenType.FUNC, "Expected 'func' or 'prop' in spec")
            val mname = consume(TokenType.IDENTIFIER, "Expected method name").lexeme
            consume(TokenType.L_PAREN, "Expected '('")
            val params = parseParams()
            consume(TokenType.R_PAREN, "Expected ')'")
            val returnType: TypeAnnotation = if (match(TokenType.COLON)) {
                TypeAnnotation.Explicit(parseTypeName())
            } else {
                TypeAnnotation.Inferred
            }
            consumeNewline()
            methods.add(FuncDecl(mname, params, returnType, emptyList(), false, emptyList(), start.line, start.column))
        }
        consume(TokenType.R_BRACE, "Expected '}' after spec methods")
        consumeNewline()
        return TopLevel.Spec(name, methods, start.line, start.column, typeParams = typeParams.names, parent = parentSpec)
    }

    /** `when scrutinee { patterns -> { body } ... else -> { body } }`. */
    private fun parseWhen(): Stmt.When {
        val start = peek()
        consume(TokenType.WHEN, "Expected 'when'")
        allowTrailingLambda = false
        val scrutinee = parseExpr()
        allowTrailingLambda = true
        consume(TokenType.L_BRACE, "Expected '{' after when scrutinee")
        skipNewlines()
        val branches = mutableListOf<Stmt.WhenBranch>()
        var elseBranch: List<Stmt>? = null
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            if (match(TokenType.ELSE)) {
                consume(TokenType.ARROW, "Expected '->' after else")
                consume(TokenType.L_BRACE, "Expected '{' after '->'")
                skipNewlines()
                elseBranch = parseBlock()
                consume(TokenType.R_BRACE, "Expected '}'")
                skipNewlines()
                break
            }
            val patterns = mutableListOf<Expr>()
            patterns.add(parseWhenPattern(scrutinee))
            while (match(TokenType.COMMA)) patterns.add(parseWhenPattern(scrutinee))
            consume(TokenType.ARROW, "Expected '->' after when patterns")
            consume(TokenType.L_BRACE, "Expected '{' after '->'")
            skipNewlines()
            val body = parseBlock()
            consume(TokenType.R_BRACE, "Expected '}'")
            skipNewlines()
            branches.add(Stmt.WhenBranch(patterns, body, start.line, start.column))
        }
        consume(TokenType.R_BRACE, "Expected '}' after when body")
        consumeNewline()
        return Stmt.When(scrutinee, branches, elseBranch, start.line, start.column)
    }

    /** Parses one `when` pattern. `is Type` becomes an [Expr.IsCheck] against the [scrutinee]
     *  (used to match a `Var<…>` by the held value's runtime type); anything else is a normal
     *  expression pattern (value / enum variant / slot destructuring). */
    private fun parseWhenPattern(scrutinee: Expr): Expr {
        if (check(TokenType.IS)) {
            val at = advance() // 'is'
            val name = consume(TokenType.IDENTIFIER, "Expected type name after 'is'").lexeme
            return Expr.IsCheck(scrutinee, name, at.line, at.column, at.lexeme.length)
        }
        return parseExpr()
    }

    private fun parseTopLevelInlineAssert(): TopLevel.InlineAssert {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.ASSERT, "Expected 'assert'")
        val condition = parseExpr()
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
        consume(TokenType.L_BRACE, "Expected '{' after trace")
        skipNewlines()
        val message = parseExpr()
        skipNewlines()
        consume(TokenType.R_BRACE, "Expected '}' after trace message")
        consumeNewline()
        return TopLevel.InlineTrace(message, start.line, start.column)
    }

    /**
     * True when a module header begins here: an optional `export`, an optional
     * `expose`/`intern`/`protect`/`confine` visibility, then `module`. Lets those
     * words keep their ordinary meaning as declaration modifiers everywhere else.
     */
    private fun isModuleHeaderAhead(): Boolean {
        var i = current
        if (tokens.getOrNull(i)?.type == TokenType.EXPORT) i++
        // `export if COND \n module …` — the newline before `module` is mandatory.
        if (tokens.getOrNull(i)?.type == TokenType.IF) {
            var j = i + 1
            while (j < tokens.size && tokens[j].type != TokenType.NEWLINE) j++
            // exactly one newline, then `module`
            return tokens.getOrNull(j)?.type == TokenType.NEWLINE &&
                tokens.getOrNull(j + 1)?.type == TokenType.MODULE
        }
        if (tokens.getOrNull(i)?.type in setOf(
                TokenType.EXPOSE, TokenType.INTERN, TokenType.PROTECT, TokenType.CONFINE
            )
        ) i++
        return tokens.getOrNull(i)?.type == TokenType.MODULE
    }

    private fun parseModule(): String {
        consume(TokenType.MODULE, "Expected 'module'")
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
        isUnsafe: Boolean = false,
        visibility: Visibility = Visibility.EXPOSE,
    ): FuncDecl {
        val start = peek()
        val keyword = when {
            isFlow -> TokenType.FLOW
            isTask -> TokenType.TASK
            else -> TokenType.FUNC
        }
        consume(keyword, "Expected '${keyword.name.lowercase()}'")
        val typeParamsBefore = parseTypeParams()
        val name = consumeIdentifierLike("Expected function name")
        // Type parameters may follow the name (`func abs<T>(x: T)`) or the
        // keyword (`func<T> abs(x: T)`) — both spellings are accepted.
        val typeParamsAfter = parseTypeParams()
        val typeParams = typeParamsBefore.names + typeParamsAfter.names
        val variadicParam = typeParamsBefore.variadic ?: typeParamsAfter.variadic
        val constParams = typeParamsBefore.constParams + typeParamsAfter.constParams
        consume(TokenType.L_PAREN, "Expected '(' after function name")
        val params = parseParams(variadicParam).toMutableList()
        consume(TokenType.R_PAREN, "Expected ')' after parameters")
        val returnType: TypeAnnotation = if (match(TokenType.COLON)) {
            TypeAnnotation.Explicit(parseTypeName())
        } else {
            TypeAnnotation.Inferred
        }
        val minVariadicLength = parseVariadicWhereClause()
        var receiverModifier: ParamModifier = "mut ref"

        // Optional contract clauses before the body: `in { ... }` preconditions and
        // `out { r -> ... }` postconditions. A contract-style declaration then
        // supplies its body as `zone { ... }`.
        val contracts = parseContractClauses()
        run {
            val i = nextMeaningfulIndex()
            val tok = tokens.getOrNull(i)
            if (tok?.type == TokenType.ZONE &&
                tokens.getOrNull(i + 1)?.type == TokenType.L_BRACE
            ) {
                while (current < i) advance() // skip newlines
                advance() // 'zone' — the '{' that follows is the function body
            }
        }

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
                receiverModifier = parseImplReceiverModifier()
                val receiverName = consumeIdentifierLike("Expected receiver name")
                if (receiverName != "self") error("Expected receiver to be named 'self' at line ${peek().line}")
                consume(TokenType.ARROW, "Expected '->' after receiver")
                skipNewlines()
            }
            // `func main() { ...args[: Type] -> body }` — bind CLI args to a
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
        val contractedBody = applyContracts(body, contracts)
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
            isUnsafe = isUnsafe,
            visibility = visibility,
            receiverModifier = receiverModifier,
            variadicParam = variadicParam,
            minVariadicLength = minVariadicLength,
            constParams = constParams,
        )
    }

    private fun isSelfReceiverHeaderAhead(): Boolean {
        val i = current
        fun token(offset: Int): TokenType? = tokens.getOrNull(i + offset)?.type
        fun lexeme(offset: Int): String? = tokens.getOrNull(i + offset)?.lexeme
        return when {
            token(0) == TokenType.REF && lexeme(1) == "self" && token(2) == TokenType.ARROW -> true
            token(0) == TokenType.MUT && token(1) == TokenType.REF && lexeme(2) == "self" && token(3) == TokenType.ARROW -> true
            token(0) == TokenType.MUT && lexeme(1) == "self" && token(2) == TokenType.ARROW -> true
            lexeme(0) == "self" && token(1) == TokenType.ARROW -> true
            else -> false
        }
    }

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
        while (true) {
            val i = nextMeaningfulIndex()
            val t = tokens.getOrNull(i) ?: break
            val isClause = (t.type == TokenType.IN || t.type == TokenType.OUT) &&
                tokens.getOrNull(i + 1)?.type == TokenType.L_BRACE
            if (!isClause) break
            while (current < i) advance() // skip newlines
            val keyword = advance()
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

    private fun applyContracts(body: List<Stmt>, contracts: ContractClauses): List<Stmt> {
        if (contracts.preconditions.isEmpty() && contracts.postconditions.isEmpty()) return body
        val withPosts = if (contracts.postconditions.isEmpty()) body else body.map { rewriteContractReturns(it, contracts) }
        return contracts.preconditions + withPosts
    }

    private fun rewriteContractReturns(stmt: Stmt, contracts: ContractClauses): Stmt {
        return when (stmt) {
            is Stmt.Return -> rewriteContractReturn(stmt, contracts)
            is Stmt.If -> stmt.copy(
                thenBranch = stmt.thenBranch.map { rewriteContractReturns(it, contracts) },
                elseBranch = stmt.elseBranch?.map { rewriteContractReturns(it, contracts) },
            )
            is Stmt.While -> stmt.copy(body = stmt.body.map { rewriteContractReturns(it, contracts) })
            is Stmt.For -> stmt.copy(body = stmt.body.map { rewriteContractReturns(it, contracts) })
            is Stmt.Loop -> stmt.copy(body = stmt.body.map { rewriteContractReturns(it, contracts) })
            is Stmt.When -> stmt.copy(
                branches = stmt.branches.map { branch ->
                    branch.copy(body = branch.body.map { rewriteContractReturns(it, contracts) })
                },
                elseBranch = stmt.elseBranch?.map { rewriteContractReturns(it, contracts) },
            )
            is Stmt.Try -> stmt.copy(
                body = stmt.body.map { rewriteContractReturns(it, contracts) },
                catchBody = stmt.catchBody?.map { rewriteContractReturns(it, contracts) },
            )
            is Stmt.Zone -> stmt.copy(body = stmt.body.map { rewriteContractReturns(it, contracts) })
            is Stmt.FriendZone -> stmt.copy(body = stmt.body.map { rewriteContractReturns(it, contracts) })
            else -> stmt
        }
    }

    private fun rewriteContractReturn(stmt: Stmt.Return, contracts: ContractClauses): Stmt {
        val value = stmt.value ?: return stmt
        val resultName = contracts.resultName ?: "__az_contract_result_${contractResultCounter++}"
        val resultRef = Expr.Identifier(resultName, stmt.line, stmt.column, resultName.length)
        return Stmt.Zone(
            listOf(
                Stmt.FinDecl(resultName, TypeAnnotation.Inferred, value, stmt.line, stmt.column),
            ) + contracts.postconditions + Stmt.Return(resultRef, stmt.line, stmt.column),
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
     * the `zone` keyword, so the reflection member `(reflect X).zone` parses.
     */
    private fun consumeMemberName(message: String): String =
        if (check(TokenType.ZONE)) advance().lexeme else consumeIdentifierLike(message)

    private fun consumeIdentifierLike(message: String): String {
        val t = peek()
        val soft = t.type == TokenType.REVERSE || t.type == TokenType.BASE ||
            t.type == TokenType.TASK || t.type == TokenType.LEAF || t.type == TokenType.PROP ||
            t.type == TokenType.DROP || t.type == TokenType.MEM || t.type == TokenType.REM || t.type == TokenType.RET ||
            t.type == TokenType.FLIP || t.type == TokenType.FLOP ||
            t.type == TokenType.ALLOC || t.type == TokenType.DEREF || t.type == TokenType.TEST ||
            t.type == TokenType.SHARED || t.type == TokenType.WEAK || t.type == TokenType.META
        if (t.type == TokenType.IDENTIFIER || soft) {
            advance()
            return t.lexeme
        }
        error("$message, got '${t.lexeme}' (${t.type}) at line ${t.line}")
    }

    private fun parseParams(variadicTypeParam: String? = null): List<Param> {
        if (check(TokenType.R_PAREN)) return emptyList()
        val params = mutableListOf<Param>()
        do {
            // Optional modifier: ref/out/mut before the name.
            val modifier = when {
                match(TokenType.REF) -> "ref"
                match(TokenType.OUT) -> "out"
                match(TokenType.MUT) -> "mut"
                else -> ""
            }
            // Optional `...name` spread marker for a variadic parameter.
            val nameSpread = match(TokenType.ELLIPSIS)
            val name = consumeIdentifierLike("Expected parameter name")
            consume(TokenType.COLON, "Expected ':' after parameter name")
            val annotations = parseAnnotations()
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
            val normalizedModifier = reference?.kind?.spelling ?: modifier
            val default = if (match(TokenType.EQUAL)) parseExpr() else null
            params.add(Param(name, type, default, normalizedModifier, variadic = isVariadic, annotations = annotations))
        } while (match(TokenType.COMMA))
        return params
    }

    /** Result of [parseTypeParams]: the names, which (if any) is the variadic pack, and which are const value params. */
    private data class TypeParams(val names: List<String>, val variadic: String?, val constParams: Set<String> = emptySet())

    /** `<T, U>` type-parameter list. A parameter may be variadic via the `...T` prefix form, or a const value param via `N: Int`. */
    private fun parseTypeParams(): TypeParams {
        if (!check(TokenType.LESS)) return TypeParams(emptyList(), null)
        advance()
        val names = mutableListOf<String>()
        val constParams = mutableSetOf<String>()
        var variadic: String? = null
        do {
            val prefixVariadic = match(TokenType.ELLIPSIS) // `...T` prefix form
            val name = consume(TokenType.IDENTIFIER, "Expected type parameter name").lexeme
            if (check(TokenType.ELLIPSIS)) {
                error("Variadic type parameters use the prefix form '...$name', not '$name...', at line ${peek().line}")
            }
            // Optional kind/constraint after `:`:
            //  - `N: Int` → a const-generic value parameter (supplied as a `TypeRef.Const` arg).
            //  - `T: Spec` → a conformance constraint (accepted, not yet enforced).
            if (match(TokenType.COLON)) {
                val constraint = parseTypeName()
                if ((constraint as? TypeRef.Named)?.name == "Int") constParams.add(name)
            }
            if (prefixVariadic) variadic = name
            names.add(name)
        } while (match(TokenType.COMMA))
        consume(TokenType.GREATER, "Expected '>' after type parameters")
        return TypeParams(names, variadic, constParams)
    }

    /**
     * Optional `where (...T).length >= <N>` constraint on a variadic pack/function.
     * The older `where T.length >= <N>` spelling is still accepted for existing
     * source files.
     * Returns the minimum length, or null if no clause is present.
     */
    private fun parseVariadicWhereClause(): Int? {
        if (peek().type != TokenType.IDENTIFIER || peek().lexeme != "where") return null
        advance() // 'where'
        if (match(TokenType.L_PAREN)) {
            match(TokenType.ELLIPSIS)
            consume(TokenType.IDENTIFIER, "Expected variadic param name after 'where (...'").lexeme
            consume(TokenType.R_PAREN, "Expected ')' after variadic param in 'where'")
        } else {
            consume(TokenType.IDENTIFIER, "Expected variadic param name after 'where'").lexeme
        }
        consume(TokenType.DOT, "Expected '.' after variadic param in 'where'")
        consume(TokenType.IDENTIFIER, "Expected 'length' after variadic param '.'") // 'length'
        consume(TokenType.GREATER_EQUAL, "Expected '>=' in 'where' length constraint")
        val n = consume(TokenType.INT_LITERAL, "Expected integer minimum length in 'where'").literal
        val minLen = (n as? NumericLiteral)?.value?.toString()?.toIntOrNull()
        return minLen
    }

    private fun parseTypeName(): TypeRef {
        val referenceKind = when {
            match(TokenType.REF) -> TypeRef.RefKind.BORROWED
            check(TokenType.MUT) && peekNext()?.type == TokenType.REF -> {
                advance()
                advance()
                TypeRef.RefKind.MUTABLE
            }
            match(TokenType.SHARED) -> {
                consume(TokenType.REF, "Expected 'ref' after 'shared' in reference type")
                TypeRef.RefKind.SHARED
            }
            match(TokenType.WEAK) -> {
                consume(TokenType.REF, "Expected 'ref' after 'weak' in reference type")
                TypeRef.RefKind.WEAK
            }
            else -> null
        }
        if (referenceKind != null) {
            return TypeRef.Reference(referenceKind, parseTypeName())
        }
        if (match(TokenType.MUT)) {
            return parseMutableTypeName()
        }
        // `...T` — variadic type (prefix). Wraps the type in the internal array representation.
        if (match(TokenType.ELLIPSIS)) {
            val inner = parseTypeAtom()
            return TypeRef.Array(inner)
        }
        return parseTypeSuffixes(parseTypeAtom())
    }

    private fun parseMutableTypeName(): TypeRef =
        error("'mut' in a type is only valid as 'mut ref T' at line ${peek().line}")

    private fun parseTypeSuffixes(start: TypeRef): TypeRef {
        var base = start
        // Suffix type modifiers: `T?`, `T!Error`, and `T![A, B]`, in any order.
        while (true) {
            base = when {
                match(TokenType.QMARK) -> TypeRef.Nullable(base)
                check(TokenType.STAR) -> { advance(); TypeRef.Pointer(base) }
                check(TokenType.BANG) && peekNext()?.type in setOf(TokenType.IDENTIFIER, TokenType.L_BRACKET) -> {
                    advance() // '!'
                    val errSets = if (match(TokenType.L_BRACKET)) {
                        val names = mutableListOf<String>()
                        do {
                            names.add(consume(TokenType.IDENTIFIER, "Expected error-set name in failable type").lexeme)
                        } while (match(TokenType.COMMA))
                        consume(TokenType.R_BRACKET, "Expected ']' after error-set list")
                        if (names.distinct().size != names.size) {
                            error("Duplicate error set in failable type at line ${peek().line}")
                        }
                        names
                    } else {
                        listOf(consume(TokenType.IDENTIFIER, "Expected error-set name after '!'").lexeme)
                    }
                    TypeRef.Failable(base, errSets)
                }
                else -> return base
            }
        }
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
            check(TokenType.L_BRACKET) -> {
                // `[T]` is type sugar for the canonical stdlib `Array<T>` type.
                // `[K: V]` remains the map-type spelling.
                advance() // consume '['
                val keyOrElem = parseTypeName()
                if (match(TokenType.COLON)) {
                    val value = parseTypeName()
                    consume(TokenType.R_BRACKET, "Expected ']' after map type")
                    TypeRef.Map(keyOrElem, value)
                } else {
                    consume(TokenType.R_BRACKET, "Expected ']' after array element type")
                    TypeRef.Named("Array", listOf(keyOrElem))
                }
            }
            check(TokenType.L_PAREN) -> {
                advance() // consume '('
                val elements = mutableListOf<TypeRef>()
                if (!check(TokenType.R_PAREN)) {
                    do { elements.add(parseTypeName()) } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_PAREN, "Expected ')' in grouped or function type")
                if (match(TokenType.ARROW)) {
                    val ret = parseTypeName()
                    TypeRef.Function(elements, ret)
                } else {
                    when (elements.size) {
                        0 -> error("Empty type '()' at line ${peek().line}")
                        1 -> elements[0] // grouping
                        else -> error("Tuple type syntax '(A, B)' was removed; use 'Tuple<A, B>' at line ${peek().line}")
                    }
                }
            }
            check(TokenType.IDENTIFIER) -> {
                if (peekNext()?.type == TokenType.L_BRACKET && peek().lexeme in setOf("arr", "vec", "set", "map")) {
                    error("'${peek().lexeme}[...]' type syntax was removed; use [T], List<T>, Set<T>, or Map<K, V> at line ${peek().line}")
                }
                val name = advance().lexeme
                // A fully qualified path combines a dotted owning module with a
                // zone path: `std.traits.std::promote!`. Zone-only access remains
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
                // `Type!(args)` — a compile-time type-function (macro) call. A `!`
                // NOT followed by `(` is a failable type (`Type!ErrSet`), handled by
                // parseTypeSuffixes; leave it for that pass.
                if (check(TokenType.BANG) && peekNext()?.type == TokenType.L_PAREN) {
                    advance() // '!'
                    consume(TokenType.L_PAREN, "Expected '(' after '$typeName!'")
                    val args = mutableListOf<TypeRef>()
                    if (!check(TokenType.R_PAREN)) {
                        do { args.add(parseTypeName()) } while (match(TokenType.COMMA))
                    }
                    consume(TokenType.R_PAREN, "Expected ')' after type-function arguments")
                    val callName = when {
                        modulePath != null -> ModuleQualifiedSymbol.create(modulePath, qualifiedName)
                        qualifiedName == name && typeFunctionNamespacePrefix.isNotEmpty() ->
                            "${typeFunctionNamespacePrefix}__$qualifiedName"
                        else -> qualifiedName
                    }
                    TypeFunctionCall.create(callName, args)
                } else if (match(TokenType.LESS)) {
                    if (modulePath != null) {
                        error("Module-qualified generic type paths are not supported at line ${peek().line}")
                    }
                    val a = mutableListOf<TypeRef>()
                    var variadic = false
                    do {
                        val prefixVariadic = match(TokenType.ELLIPSIS)
                        if (prefixVariadic) variadic = true
                        a.add(parseTypeArg())
                    } while (match(TokenType.COMMA))
                    // `Name<...T>` — the variadic pack expands into this type's args.
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
                    TypeRef.Named(typeName, a, variadic)
                } else {
                    if (modulePath != null) {
                        error("A module-qualified path must name a callable type function at line ${peek().line}")
                    }
                    TypeRef.Named(typeName)
                }
            }
            else -> error("Expected type name at line ${peek().line}, got '${peek().lexeme}'")
        }
    }

    // -----------------------------------------------------------------------
    // Statements
    // -----------------------------------------------------------------------

    private fun parseStmt(): Stmt {
        return when {
            // `var(...)` is the variant constructor (an expression statement); `var name = …` is a declaration.
            check(TokenType.VAR) && peekNext()?.type == TokenType.L_PAREN -> parseExprStmt()
            check(TokenType.VAR) -> parseVarDecl()
            check(TokenType.FIN) -> parseFinDecl()
            check(TokenType.LET) -> parseLetDecl()
            check(TokenType.RETURN) -> parseReturn()
            check(TokenType.ASSERT) -> parseAssertStmt()
            check(TokenType.TRACE) -> parseTraceStmt()
            check(TokenType.INLINE) -> parseInline()
            check(TokenType.DEEPINLINE) -> parseDeepInlineStmt()
            check(TokenType.NOINLINE) -> parseNoInline()
            check(TokenType.ZONE) -> parseZone()
            check(TokenType.FRIEND) -> parseFriendZone()
            check(TokenType.IF) -> parseIf()
            check(TokenType.AT) -> parseLabeledStmt()
            check(TokenType.WHILE) -> parseWhile()
            check(TokenType.FOR) -> parseFor()
            check(TokenType.REVERSE) && peekNext()?.type == TokenType.FOR -> parseFor(reverse = true)
            check(TokenType.LOOP) -> parseLoop()
            check(TokenType.BREAK) -> parseBreak()
            check(TokenType.CONTINUE) -> parseContinue()
            check(TokenType.WHEN) -> parseWhen()
            check(TokenType.THROW) -> parseThrow()
            check(TokenType.PANIC) -> parsePanicStmt()
            check(TokenType.FAIL) && peekNext()?.type == TokenType.DEFER -> parseFailDefer()
            check(TokenType.FAIL) && peekNext()?.type == TokenType.RETURN -> parseFailReturn()
            check(TokenType.FAIL) -> parseFailThrow()
            check(TokenType.UNSAFE) -> parseUnsafe()
            check(TokenType.DROP) -> parseDrop()
            check(TokenType.YIELD) -> parseYield()
            check(TokenType.TRY) && peekNext()?.type == TokenType.L_BRACE -> parseTry()
            check(TokenType.DEFER) -> parseDefer()
            check(TokenType.RESCUE) -> parseRescue()
            check(TokenType.MEM) -> parseReactiveDecl(ReactiveKind.MEM)
            check(TokenType.REM) -> parseReactiveDecl(ReactiveKind.REM)
            check(TokenType.RET) -> parseReactiveDecl(ReactiveKind.RET)
            check(TokenType.EFFECT) -> parseEffect()
            check(TokenType.FLIP) -> parseFlipFlop()
            check(TokenType.GUARD) -> parseGuard()
            else -> parseExprStmt()
        }
    }

    /** `@label while/for/loop …` — a labeled loop for `break @label`/`continue @label`. */
    private fun parseLabeledStmt(): Stmt {
        consume(TokenType.AT, "Expected '@'")
        val label = consume(TokenType.IDENTIFIER, "Expected label name after '@'").lexeme
        return when {
            check(TokenType.WHILE) -> parseWhile(label)
            check(TokenType.FOR) -> parseFor(label = label)
            check(TokenType.REVERSE) && peekNext()?.type == TokenType.FOR -> parseFor(reverse = true, label = label)
            check(TokenType.LOOP) -> parseLoop(label)
            else -> error("Expected a loop after '@$label' at line ${peek().line}")
        }
    }

    /** `@label` suffix on `break`/`continue`; returns the label or null. */
    private fun parseOptionalLabel(): String? =
        if (match(TokenType.AT)) consume(TokenType.IDENTIFIER, "Expected label name after '@'").lexeme else null

    private fun parseWhile(label: String? = null): Stmt {
        val start = peek()
        consume(TokenType.WHILE, "Expected 'while'")
        // As in parseIf: `while f() { … }` — the `{` is the loop body.
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
     * `zone { var __loop_else_n = true; loop { body (break sets flag false) }; if (__loop_else_n) { elseBody } }`.
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
        return Stmt.Zone(listOf(flagDecl, rewritten, elseCheck), line, column)
    }

    /**
     * Rewrites `break` statements that belong to [flag]'s loop into
     * `zone { flag = false; break }`, so the loop-else flag reflects a real break.
     * Does NOT descend into nested loops (their breaks belong to them).
     */
    private fun rewriteBreaksForElse(stmts: List<Stmt>, flag: String): List<Stmt> =
        stmts.map { rewriteBreakForElse(it, flag) }

    private fun rewriteBreakForElse(stmt: Stmt, flag: String): Stmt = when (stmt) {
        // Only an unlabeled `break` exits THIS loop and so should trip the else flag.
        // A labeled `break @outer` targets an enclosing loop and is left untouched.
        is Stmt.Break -> if (stmt.label != null) stmt else Stmt.Zone(listOf(
            Stmt.Assignment(flag, Expr.BoolLiteral(false, stmt.line, stmt.column, stmt.length), stmt.line, stmt.column),
            Stmt.Break()
        ), stmt.line, stmt.column)
        // Nested loops own their own breaks — stop descending.
        is Stmt.While, is Stmt.For, is Stmt.Loop -> stmt
        is Stmt.If -> Stmt.If(
            stmt.condition,
            rewriteBreaksForElse(stmt.thenBranch, flag),
            stmt.elseBranch?.let { rewriteBreaksForElse(it, flag) },
            stmt.line, stmt.column, stmt.length
        )
        is Stmt.Zone -> Stmt.Zone(rewriteBreaksForElse(stmt.body, flag), stmt.line, stmt.column, stmt.length)
        is Stmt.FriendZone -> Stmt.FriendZone(rewriteBreaksForElse(stmt.body, flag), stmt.line, stmt.column, stmt.length)
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

    private fun parseFor(reverse: Boolean = false, label: String? = null): Stmt {
        val start = peek()
        if (reverse) consume(TokenType.REVERSE, "Expected 'reverse'")
        consume(TokenType.FOR, "Expected 'for'")
        val name = consume(TokenType.IDENTIFIER, "Expected loop variable name").lexeme
        // Optional step: `for x by N in ...`
        val step = if (match(TokenType.BY)) parseExpr() else null
        consume(TokenType.IN, "Expected 'in' after loop variable")
        allowTrailingLambda = false
        val iterable = parseExpr()
        allowTrailingLambda = true
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
        // `loop iterable { }` iterates anything exposing reset()/hasNext(); bare `loop { }` is infinite.
        val savedTrailing = allowTrailingLambda
        allowTrailingLambda = false
        val iterable: Expr? = if (!check(TokenType.L_BRACE)) parseExpr() else null
        val skipIteratorReset = iterable != null && match(TokenType.CONTINUE)
        allowTrailingLambda = savedTrailing
        consume(TokenType.L_BRACE, "Expected '{' after 'loop'")
        skipNewlines()
        val body = parseBlock().toMutableList()
        consume(TokenType.R_BRACE, "Expected '}' after loop body")
        // do-while: `loop { body } while cond` — runs the body first, then repeats
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
        // `loop iterable { body }` desugars to `zone { iterable.reset(); while (iterable.hasNext()) { body } }`.
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
            return Stmt.Zone(lowered, start.line, start.column)
        }
        val loop = Stmt.Loop(body, start.line, start.column, label = label)
        return if (elseBranch != null) withLoopElse(loop, elseBranch, start.line, start.column) else loop
    }

    private fun parseBreak(): Stmt {
        val start = consume(TokenType.BREAK, "Expected 'break'")
        val label = parseOptionalLabel()
        consumeNewline()
        return Stmt.Break(label, start.line, start.column, start.lexeme.length)
    }

    private fun parseContinue(): Stmt {
        val start = consume(TokenType.CONTINUE, "Expected 'continue'")
        val label = parseOptionalLabel()
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

    /** `fail <expr>` — sugar for `throw <expr>` (raise an error from a `T!E` function). */
    private fun parseFailThrow(): Stmt.Throw {
        val start = peek()
        consume(TokenType.FAIL, "Expected 'fail'")
        val value = parseExpr()
        consumeNewline()
        return Stmt.Throw(value, start.line, start.column)
    }

    /** `fail return .Variant` — return by failing the function with an error variant. */
    private fun parseFailReturn(): Stmt.Throw {
        val start = peek()
        consume(TokenType.FAIL, "Expected 'fail'")
        consume(TokenType.RETURN, "Expected 'return' after 'fail'")
        match(TokenType.DOT)
        val variant = consume(TokenType.IDENTIFIER, "Expected error variant after 'fail return'").lexeme
        consumeNewline()
        return Stmt.Throw(Expr.StringLiteral(variant, start.line), start.line, start.column)
    }

    /**
     * `mixin "<string>"` — converts a string into code at compile time. The string
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
        val wrapper = Parser(Lexer("func __mixin() {\n$rendered\n}").tokenize()).parse()
        val body = (wrapper.items.firstOrNull() as? TopLevel.Func)?.decl?.body
            ?: error("inline splice did not produce any statements at line ${start.line}")
        consumeNewline()
        return Stmt.InlineBlock(body, start.line, start.column)
    }

    /** `unsafe { body }` — an explicit boundary for unchecked operations. */
    private fun parseUnsafe(): Stmt.Zone {
        val start = peek()
        consume(TokenType.UNSAFE, "Expected 'unsafe'")
        consume(TokenType.L_BRACE, "Expected '{' after 'unsafe'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after unsafe body")
        consumeNewline()
        return Stmt.Zone(body, start.line, start.column, unsafe = true)
    }

    /** `drop <expr>` — release a heap value; calls `__drop(value)` which triggers dtor if present. */
    private fun parseDrop(): Stmt {
        val start = peek()
        consume(TokenType.DROP, "Expected 'drop'")
        val value = parseExpr()
        consumeNewline()
        return Stmt.ExprStmt(Expr.Call("__drop", listOf(value), start.line, start.column, start.lexeme.length), start.line, start.column)
    }

    /** `yield <expr>` — emit a value from a `flow` generator. */
    private fun parseYield(): Stmt.Yield {
        val start = peek()
        consume(TokenType.YIELD, "Expected 'yield'")
        val value = if (check(TokenType.NEWLINE) || check(TokenType.R_BRACE) || isAtEnd()) null else parseExpr()
        consumeNewline()
        // A bare `yield` (no value) yields Unit; the common form is `yield <expr>`.
        return Stmt.Yield(value ?: Expr.TupleLit(emptyList(), start.line, start.column), start.line, start.column)
    }

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

    /** `fail defer { body }` — a defer that runs only when the function exits via an error. */
    private fun parseFailDefer(): Stmt.Defer {
        val start = peek()
        consume(TokenType.FAIL, "Expected 'fail'")
        consume(TokenType.DEFER, "Expected 'defer' after 'fail'")
        consume(TokenType.L_BRACE, "Expected '{' after 'defer'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.Defer(body, start.line, start.column, onFail = true)
    }

    /** `rescue { body }` — catch-and-suppress: runs the handler on error, then swallows it. */
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

    /** `mem`/`rem`/`ret x: T = init` — reactive state declaration. */
    private fun parseReactiveDecl(kind: ReactiveKind): Stmt.RemDecl {
        val start = peek()
        val expected = when (kind) {
            ReactiveKind.MEM -> TokenType.MEM
            ReactiveKind.REM -> TokenType.REM
            ReactiveKind.RET -> TokenType.RET
        }
        consume(expected, "Expected '${expected.name.lowercase()}'")
        val name = consume(TokenType.IDENTIFIER, "Expected reactive variable name").lexeme
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in reactive declaration")
        val init = parseExpr()
        consumeNewline()
        return Stmt.RemDecl(name, type, init, start.line, start.column, kind = kind)
    }

    /** `effect { body }` — reactive side-effect block. */
    private fun parseEffect(): Stmt.Effect {
        val start = peek()
        consume(TokenType.EFFECT, "Expected 'effect'")
        consume(TokenType.L_BRACE, "Expected '{' after 'effect'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.Effect(body, start.line, start.column)
    }

    /** `view Name(params) { body }` — a reactive UI component. */
    private fun parseView(annotations: List<Annotation> = emptyList()): TopLevel.View {
        val start = peek()
        consume(TokenType.VIEW, "Expected 'view'")
        val name = consume(TokenType.IDENTIFIER, "Expected view name").lexeme
        consume(TokenType.L_PAREN, "Expected '(' after view name")
        val params = parseParams()
        consume(TokenType.R_PAREN, "Expected ')' after view params")
        consume(TokenType.L_BRACE, "Expected '{' after view header")
        skipNewlines()
        val body = mutableListOf<Stmt>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            body.add(parseStmt())
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after view body")
        consumeNewline()
        return TopLevel.View(name, params, body, start.line, start.column, annotations)
    }

    /** `hook name { body }` — a lifecycle callback. */
    private fun parseHook(annotations: List<Annotation> = emptyList()): TopLevel.Hook {
        val start = consume(TokenType.HOOK, "Expected 'hook'")
        val name = consume(TokenType.IDENTIFIER, "Expected hook name").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after hook name")
        skipNewlines()
        val body = mutableListOf<Stmt>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            body.add(parseStmt())
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after hook body")
        consumeNewline()
        return TopLevel.Hook(name, body, start.line, start.column, annotations)
    }

    /**
     * `threadlocal var x: T = init` / `threadlocal fin y: T = init` — thread-local storage.
     * Each coroutine (main + each task/launch/flow) gets its own independent copy.
     * Desugars to a regular top-level VarDecl/FinDecl with a `__tl__` name prefix.
     */
    private fun parseThreadLocal(visibility: Visibility = Visibility.EXPOSE): TopLevel {
        val start = consume(TokenType.THREADLOCAL, "Expected 'threadlocal'")
        return when {
            check(TokenType.VAR) -> {
                advance()
                val name = consume(TokenType.IDENTIFIER, "Expected variable name").lexeme
                val type = if (match(TokenType.COLON)) parseTypeName() else null
                consume(TokenType.EQUAL, "Expected '='")
                val init = parseExpr()
                consumeNewline()
                TopLevel.VarDecl(name, type, init, start.line, start.column, threadlocal = true, visibility = visibility)
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

    /**
     * `flip { body } flop { body }` — alternating execution. On the first encounter
     * the flip body runs; on the next, the flop body; then flip again, etc.
     *
     * By default (no label), alternates on each encounter — works inside loops
     * (alternates per iteration) and inside function bodies (alternates per call).
     *
     * With a label — `flip@label { } flop@label { }` — the toggle is scoped to
     * that specific labeled loop, so multiple labeled loops each have independent toggles.
     *
     * Desugars to `if (__flipflop(id)) { flipBody } else { flopBody }`.
     */
    private fun parseFlipFlop(): Stmt {
        val start = consume(TokenType.FLIP, "Expected 'flip'")
        // Optional label: `flip@label` — scopes the toggle to a specific labeled loop.
        val label = if (match(TokenType.AT)) {
            consume(TokenType.IDENTIFIER, "Expected label name after 'flip@'").lexeme
        } else null
        consume(TokenType.L_BRACE, "Expected '{' after 'flip'")
        skipNewlines()
        val flipBody = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after flip body")
        val flopBody = if (match(TokenType.FLOP)) {
            // Optional label on flop too (must match flip's label if present).
            if (match(TokenType.AT)) consume(TokenType.IDENTIFIER, "Expected label name after 'flop@'").lexeme
            consume(TokenType.L_BRACE, "Expected '{' after 'flop'")
            skipNewlines()
            val body = parseBlock()
            consume(TokenType.R_BRACE, "Expected '}' after flop body")
            body
        } else emptyList()
        consumeNewline()
        // Unique ID: incorporate the label (if any) so labeled loops get independent toggles.
        val baseId = start.line * 10000 + start.column
        val id = if (label != null) {
            // Hash the label into the id to make it unique per (location, label) pair.
            baseId xor label.hashCode()
        } else baseId
        val cond = Expr.Call("__flipflop", listOf(Expr.IntLiteral(id.toLong(), start.line)), start.line, start.column, start.lexeme.length)
        return Stmt.If(cond, flipBody, flopBody, start.line, start.column)
    }

    /** `guard condition else { body }` — sugar for `if !condition { body }`. */
    private fun parseGuard(): Stmt {
        val start = peek()
        consume(TokenType.GUARD, "Expected 'guard'")
        val condition = parseExpr()
        consume(TokenType.ELSE, "Expected 'else' after guard condition")
        consume(TokenType.L_BRACE, "Expected '{' after 'else'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        // Desugar to: if !(condition) { body }
        val negated = Expr.Unary(TokenType.BANG, condition, start.line, start.column)
        return Stmt.If(negated, body, null, start.line, start.column)
    }

    /**
     * `import path` — import a module/zone path.
     * `import zone path` — same import form with an explicit namespace marker.
     * `import path.*` — import all items below a path.
     * `import path.{child, other}` — import grouped child paths.
     * `import path.item` — import a dotted path; semantic passes decide whether the
     * path names a module or a selected item. `::` is only for zone access
     * expressions, never import syntax.
     */
    private fun parseImport(exported: Boolean = false, condition: Expr? = null): TopLevel {
        val start = consume(TokenType.IMPORT, "Expected 'import'")
        // `import zone std` — the optional marker reads naturally and is skipped.
        if (check(TokenType.ZONE) && peekNext()?.type == TokenType.IDENTIFIER) {
            advance()
        }
        val imports = mutableListOf<Pair<String, String?>>() // (zoneName, itemName or null for all)
        do {
            val base = StringBuilder(consumeIdentifierLike("Expected zone name after 'import'"))
            var completed = false
            usePath@ while (match(TokenType.DOT)) {
                when {
                    match(TokenType.STAR) -> {
                        // `import path.*` — wildcard. Marked with the "*" selector so
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

            val zoneName = base.toString()
            if (match(TokenType.DOUBLE_COLON)) {
                error("Use dotted import paths such as 'import module.item' or 'import module.{a, b}'; '::' is for zone access expressions")
            } else {
                addDottedUsePath(zoneName, imports)
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
     * `prop name: T = expr` at top level — desugars to a `fin` binding. This form
     * appears inside `impl … as zone for Type` / `impl zone for Type` bodies,
     * which reparse their members as top-level items; mangling then turns the
     * name into `Type__name`, reachable as `Type::name`.
     */
    private fun parsePropAsFin(annotations: List<Annotation>, visibility: Visibility): TopLevel.FinDecl {
        val start = peek()
        consume(TokenType.PROP, "Expected 'prop'")
        val name = consume(TokenType.IDENTIFIER, "Expected name after 'prop'").lexeme
        val type = if (match(TokenType.COLON)) parseTypeName() else null
        consume(TokenType.EQUAL, "Expected '=' in prop declaration")
        val init = parseExpr()
        consumeNewline()
        return TopLevel.FinDecl(name, type, init, start.line, start.column, annotations, visibility = visibility)
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

    /** Parses `type name(param: Type, pack: ...Type) where pack.length >= N { ... }`. */
    private fun parseTypeFunction(namespacePrefix: String = "") {
        val start = consume(TokenType.TYPE, "Expected 'type'")
        val localName = consume(TokenType.IDENTIFIER, "Expected type-function name").lexeme
        val name = if (namespacePrefix.isEmpty()) localName else "${namespacePrefix}__$localName"
        consume(TokenType.L_PAREN, "Expected '(' after type-function name")
        val params = mutableListOf<TypeFunctionParam>()
        if (!check(TokenType.R_PAREN)) {
            do {
                val param = consume(TokenType.IDENTIFIER, "Expected type-function parameter name").lexeme
                consume(TokenType.COLON, "Expected ':' after type-function parameter")
                val prefixVariadic = match(TokenType.ELLIPSIS)
                val typeToken = consume(TokenType.IDENTIFIER, "Expected 'Type' for type-function parameter")
                if (typeToken.lexeme != "Type") {
                    error("Type-function parameter '$param' must have type Type at line ${typeToken.line}")
                }
                val suffixVariadic = match(TokenType.ELLIPSIS)
                if (prefixVariadic && suffixVariadic) {
                    error("Type-function parameter '$param' cannot repeat '...' at line ${typeToken.line}")
                }
                params.add(TypeFunctionParam(param, prefixVariadic || suffixVariadic))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.R_PAREN, "Expected ')' after type-function parameters")
        val duplicateParam = params.groupingBy { it.name }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicateParam != null) {
            error("Duplicate type-function parameter '${duplicateParam.key}' at line ${start.line}")
        }
        if (params.count { it.variadic } > 1 || params.dropLast(1).any { it.variadic }) {
            error("A type function may have one variadic parameter, and it must be last, at line ${start.line}")
        }

        // `where <var>.length >= N` and/or `<var> is Spec`, joined with `&&`.
        var minimum: Int? = null
        if (check(TokenType.IDENTIFIER) && peek().lexeme == "where") {
            advance()
            val variadic = params.lastOrNull()?.takeIf { it.variadic }?.name
            do {
                val constrained = consume(TokenType.IDENTIFIER, "Expected parameter name in 'where' constraint").lexeme
                when {
                    // `<var> is Spec` — a conformance constraint (accepted; enforced by the evaluator).
                    match(TokenType.IS) -> consume(TokenType.IDENTIFIER, "Expected spec name after 'is'")
                    match(TokenType.DOT) -> {
                        val property = consume(TokenType.IDENTIFIER, "Expected 'length' in type-function constraint")
                        if (property.lexeme != "length") error("Expected 'length' in type-function constraint at line ${property.line}")
                        if (constrained != variadic) {
                            error("Type-function length constraints require the variadic parameter '$variadic' at line ${start.line}")
                        }
                        consume(TokenType.GREATER_EQUAL, "Expected '>=' in type-function constraint")
                        val value = consume(TokenType.INT_LITERAL, "Expected minimum argument count")
                        minimum = ((value.literal as NumericLiteral).value as Long).toInt()
                    }
                    else -> error("Expected '.length >= N' or 'is Spec' in 'where' constraint at line ${peek().line}")
                }
            } while (match(TokenType.AND_AND))
        }

        consume(TokenType.L_BRACE, "Expected '{' before type-function body")
        skipNewlines()
        val body = parseTypeFunctionBlock()
        consume(TokenType.R_BRACE, "Expected '}' after type-function body")
        consumeNewline()
        if (body.none { it is TypeFunctionStmt.Return }) {
            error("Type function '$localName' must return a type at line ${start.line}")
        }
        val duplicate = typeFunctions.any {
            it.name == name && it.params.map(TypeFunctionParam::variadic) == params.map(TypeFunctionParam::variadic) &&
                (it.variadicParam != null || it.params.size == params.size)
        }
        if (duplicate) error("Type function '$localName' already has this overload at line ${start.line}")
        typeFunctions.add(TypeFunctionDecl(name, params, body, minimum, start.line, start.column))
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
        check(TokenType.LET) || check(TokenType.VAR) || check(TokenType.FIN) -> {
            val mutable = check(TokenType.VAR)
            advance()
            val name = consume(TokenType.IDENTIFIER, "Expected type binding name").lexeme
            if (match(TokenType.COLON)) {
                val type = consume(TokenType.IDENTIFIER, "Expected 'Type' after ':'")
                if (type.lexeme != "Type") error("Type-function bindings must have type Type at line ${type.line}")
            }
            consume(TokenType.EQUAL, "Expected '=' in type binding")
            val value = parseTypeFunctionExpr()
            consumeNewline()
            TypeFunctionStmt.Binding(name, value, mutable)
        }
        match(TokenType.FOR) -> {
            val name = consume(TokenType.IDENTIFIER, "Expected type loop variable").lexeme
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
        check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.EQUAL -> {
            val name = advance().lexeme
            advance()
            val value = parseTypeFunctionExpr()
            consumeNewline()
            TypeFunctionStmt.Assignment(name, value)
        }
        else -> error("Expected type-function statement at line ${peek().line}, got '${peek().lexeme}'")
    }

    private fun parseTypeFunctionExpr(): TypeFunctionExpr {
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
        if (match(TokenType.BANG)) {
            consume(TokenType.L_PAREN, "Expected '(' after '$name!'")
            val args = mutableListOf<TypeFunctionExpr>()
            if (!check(TokenType.R_PAREN)) {
                do { args.add(parseTypeFunctionExpr()) } while (match(TokenType.COMMA))
            }
            consume(TokenType.R_PAREN, "Expected ')' after type-function arguments")
            val callName = if (typeFunctionNamespacePrefix.isEmpty()) name else "${typeFunctionNamespacePrefix}__$name"
            return TypeFunctionExpr.Call(callName, args)
        }
        if (match(TokenType.DOT)) {
            val index = consume(TokenType.INT_LITERAL, "Expected type-pack index after '.'")
            return TypeFunctionExpr.PackElement(name, ((index.literal as NumericLiteral).value as Long).toInt())
        }
        return TypeFunctionExpr.Reference(name)
    }

    private fun parseTypeFunctionCondition(): TypeFunctionCondition {
        fun operand(): Pair<TypeFunctionExpr, Boolean> {
            val name = consume(TokenType.IDENTIFIER, "Expected a type value in type comparison").lexeme
            val expression = if (match(TokenType.BANG)) {
                consume(TokenType.L_PAREN, "Expected '(' after '$name!'")
                val args = mutableListOf<TypeFunctionExpr>()
                if (!check(TokenType.R_PAREN)) {
                    do { args.add(parseTypeFunctionExpr()) } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_PAREN, "Expected ')' after type-function arguments")
                val callName = if (typeFunctionNamespacePrefix.isEmpty()) name else "${typeFunctionNamespacePrefix}__$name"
                TypeFunctionExpr.Call(callName, args)
            } else {
                TypeFunctionExpr.Reference(name)
            }
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
            TokenType.ZONE -> parseInlineZoneBlock()
            TokenType.IF -> parseInlineIf()
            TokenType.FOR -> parseInlineFor()
            TokenType.ASSERT -> parseInlineAssertStmt()
            TokenType.TRACE -> parseInlineTraceStmt()
            TokenType.PANIC -> parsePanicStmt(inlinePanic = true)
            TokenType.FIN -> parseInlineFin()
            TokenType.VAR -> parseInlineVar()
            TokenType.LET -> parseInlineLet()
            TokenType.IDENTIFIER -> parseInlineAssignment()
            TokenType.STRING_LITERAL, TokenType.INTERPOLATED_STRING -> parseInlineSplice()
            else -> error("Expected '{', 'zone', 'if', 'for', 'assert', 'trace', 'fin', 'var', 'let', a string splice, or identifier after 'inline' at line ${peek().line}")
        }
    }

    /** `inline for x in a..b { body }` — a compile-time unrolled loop. */
    private fun parseInlineFor(): Stmt.InlineFor {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.FOR, "Expected 'for'")
        val name = consume(TokenType.IDENTIFIER, "Expected loop variable name").lexeme
        consume(TokenType.IN, "Expected 'in' after loop variable")
        val parsedIterable = parseExpr()
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
        } else if (peek().type == TokenType.IDENTIFIER && peek().lexeme == "with") {
            advance()
            consume(TokenType.IDENTIFIER, "Expected index binding after 'with'").lexeme
        } else null
        consume(TokenType.L_BRACE, "Expected '{' after inline for iterable")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after inline for body")
        consumeNewline()
        return Stmt.InlineFor(name, iterable, body, start.line, start.column, indexName = indexName)
    }


    /** `inline zone { ... }` — alias for `inline { ... }`. */
    private fun parseInlineZoneBlock(): Stmt.InlineBlock {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.ZONE, "Expected 'zone'")
        consume(TokenType.L_BRACE, "Expected '{' after 'zone'")
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
            TokenType.ZONE -> parseDeepInlineZoneBlock()
            TokenType.IF -> parseDeepInlineIf()
            else -> error("Expected '{', 'zone', or 'if' after 'deepinline' at line ${peek().line}")
        }
    }

    /** `deepinline zone { ... }` — alias for `deepinline { ... }`. */
    private fun parseDeepInlineZoneBlock(): Stmt.DeepInlineBlock {
        val start = peek()
        consume(TokenType.DEEPINLINE, "Expected 'deepinline'")
        consume(TokenType.ZONE, "Expected 'zone'")
        consume(TokenType.L_BRACE, "Expected '{' after 'zone'")
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
        val condition = parseExpr()
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

    private fun parseZone(): Stmt.Zone {
        val start = peek()
        consume(TokenType.ZONE, "Expected 'zone'")
        val isAlloc = match(TokenType.ALLOC) // `zone alloc { }` — scoped allocation arena
        consume(TokenType.L_BRACE, "Expected '{' after 'zone'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.Zone(body, start.line, start.column, alloc = isAlloc)
    }

    private fun parseFriendZone(): Stmt.FriendZone {
        val start = peek()
        consume(TokenType.FRIEND, "Expected 'friend'")
        consume(TokenType.ZONE, "Expected 'zone' after 'friend'")
        val isAlloc = match(TokenType.ALLOC) // `friend zone alloc { }`
        consume(TokenType.L_BRACE, "Expected '{' after 'friend zone'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.FriendZone(body, start.line, start.column, alloc = isAlloc)
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
        val init = parseExpr()
        consumeNewline()
        return Stmt.InlineFin(name, type, init, start.line, start.column)
    }

    private fun parseInlineVar(): Stmt.InlineVar {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.VAR, "Expected 'var' after 'inline'")
        val name = consumeIdentifierLike("Expected variable name")
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in inline var declaration")
        val init = parseExpr()
        consumeNewline()
        return Stmt.InlineVar(name, type, init, start.line, start.column)
    }

    private fun parseInlineLet(): Stmt.InlineLet {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.LET, "Expected 'let' after 'inline'")
        val name = consumeIdentifierLike("Expected variable name")
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in inline let declaration")
        val init = parseExpr()
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
        // The `{ message }` block is optional — a bare `assert cond` uses an empty message.
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
        consume(TokenType.L_BRACE, "Expected '{' after trace")
        skipNewlines()
        val message = parseExpr()
        skipNewlines()
        consume(TokenType.R_BRACE, "Expected '}' after trace message")
        consumeNewline()
        return Stmt.Trace(message, start.line, start.column)
    }

    private fun parseInlineAssertStmt(): Stmt.InlineAssert {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.ASSERT, "Expected 'assert'")
        val condition = parseExpr()
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
        consume(TokenType.L_BRACE, "Expected '{' after inline trace")
        skipNewlines()
        val message = parseExpr()
        skipNewlines()
        consume(TokenType.R_BRACE, "Expected '}' after inline trace message")
        consumeNewline()
        return Stmt.InlineTrace(message, start.line, start.column)
    }

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
        val condition = parseExpr()
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

    private fun parseVarDecl(): Stmt.VarDecl {
        val start = peek()
        advance() // consume 'var'
        val name = consumeIdentifierLike("Expected variable name")
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in declaration")
        val init = parseExpr()
        consumeNewline()
        return Stmt.VarDecl(name, type, init, start.line, start.column)
    }

    private fun parseFinDecl(): Stmt.FinDecl {
        val start = peek()
        advance() // consume 'fin'
        val name = consumeIdentifierLike("Expected variable name")
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in declaration")
        val init = parseExpr()
        consumeNewline()
        return Stmt.FinDecl(name, type, init, start.line, start.column)
    }

    private fun parseLetDecl(): Stmt.LetDecl {
        val start = peek()
        advance() // consume 'let'
        val name = consumeIdentifierLike("Expected variable name")
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in let declaration")
        val init = parseExpr()
        consumeNewline()
        return Stmt.LetDecl(name, type, init, start.line, start.column)
    }

    private fun parseReturn(): Stmt {
        val start = peek()
        consume(TokenType.RETURN, "Expected 'return'")
        val value = if (check(TokenType.NEWLINE) || check(TokenType.R_BRACE) || isAtEnd()) null
                    else parseExpr()
        consumeNewline()
        return Stmt.Return(value, start.line, start.column)
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
            TokenType.TILDE_EQUAL,
            TokenType.SLASH_EQUAL, TokenType.PERCENT_EQUAL -> {
                advance()
                val op = when (opTok.type) {
                    TokenType.PLUS_EQUAL -> TokenType.PLUS
                    TokenType.MINUS_EQUAL -> TokenType.MINUS
                    TokenType.STAR_EQUAL -> TokenType.STAR
                    TokenType.SLASH_EQUAL -> TokenType.SLASH
                    TokenType.PERCENT_EQUAL -> TokenType.PERCENT
                    TokenType.TILDE_EQUAL -> TokenType.PLUS
                    else -> error("unreachable compound assignment")
                }
                val value = parseExpr()
                consumeNewline()
                // Desugar `target op= value` into `target = target op value`
                val operand = if (opTok.type == TokenType.TILDE_EQUAL) {
                    Expr.Cast(value, TypeRef.Named("String"), CastKind.STATIC, value.line, value.column, value.length)
                } else value
                val rhs = Expr.Binary(expr, op, operand, start.line, start.column, start.lexeme.length)
                buildAssignment(expr, rhs, start.line, start.column)
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
     * `target ?<op>= value` — perform the compound assignment only when [target] is
     * non-null. Desugars to `if (target != null) { target = target <op> value }`,
     * which lowers to existing IR (If/Assignment/Binary), so all backends support it.
     */
    private fun nullConditionalCompound(target: Expr, op: TokenType, value: Expr, start: Token): Stmt {
        val rhs = Expr.Binary(target, op, value, start.line, start.column, start.lexeme.length)
        val assign = buildAssignment(target, rhs, start.line, start.column)
        val cond = Expr.Binary(target, TokenType.BANG_EQUAL, Expr.NullLiteral, start.line, start.column, start.lexeme.length)
        return Stmt.If(cond, listOf(assign), null, start.line, start.column)
    }

    /** `target ?++` / `target ?--` — increment/decrement only when [target] is non-null. */
    private fun nullConditionalIncDec(target: Expr, op: TokenType, start: Token): Stmt =
        nullConditionalCompound(target, op, Expr.IntLiteral(1, start.line, start.column, start.lexeme.length), start)

    /**
     * Builds an assignment statement from a parsed lvalue expression.
     * Supports simple variables (`x = v`), index targets (`a[i] = v`),
     * and member targets (`o.f = v`).
     */
    private fun buildAssignment(target: Expr, value: Expr, line: Int, column: Int): Stmt {
        return when (target) {
            is Expr.Identifier -> Stmt.Assignment(target.name, value, line, column)
            is Expr.Index -> Stmt.IndexAssign(target.target, target.index, value, line, column)
            is Expr.Member -> Stmt.MemberAssign(target.target, target.name, value, line, column)
            is Expr.Deref -> Stmt.DerefAssign(target.target, value, line, column)
            else -> error("Invalid assignment target at line $line")
        }
    }

    // -----------------------------------------------------------------------
    // Expressions (precedence climbing)
    // -----------------------------------------------------------------------

    private fun parseExpr(): Expr {
        var e = parseOr()
        // `??` — null-coalesce
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
        while (check(TokenType.AS) || check(TokenType.IS)) {
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
                // `is` — optionally negated with `!`: `expr is! Type`
                val negated = match(TokenType.BANG)
                val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'is'").lexeme
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
        while (check(TokenType.PIPE) || check(TokenType.TILDE)) {
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
        var left = parseInfix()
        while (check(TokenType.LESS) || check(TokenType.LESS_EQUAL) ||
               check(TokenType.GREATER) || check(TokenType.GREATER_EQUAL)
        ) {
            val op = advance().type
            val right = parseInfix()
            left = Expr.Binary(left, op, right, left.line)
        }
        return left
    }

    /**
     * Infix method calls: `a plus b` → `a.plus(b)`.
     * Any IDENTIFIER followed by an expression-start token is treated as an infix method call.
     */
    private fun parseInfix(): Expr {
        var left = parseShift()
        while (check(TokenType.IDENTIFIER) && isInfixCandidate()) {
            val methodName = advance().lexeme
            val right = parseShift()
            left = Expr.MethodCall(left, methodName, listOf(right), left.line, left.column)
        }
        return left
    }

    private fun isInfixCandidate(): Boolean {
        val next = peekNext()?.type ?: return false
        return next in setOf(
            TokenType.IDENTIFIER, TokenType.INT_LITERAL, TokenType.REAL_LITERAL,
            TokenType.STRING_LITERAL, TokenType.CHAR_LITERAL, TokenType.TRUE, TokenType.FALSE,
            TokenType.NULL, TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE,
            TokenType.MINUS, TokenType.BANG, TokenType.TILDE
        )
    }

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

    private fun isReflectName(name: String): Boolean = name == "reflect" || name.endsWith("__reflect")

    /** After `<`, whether the tokens spell a reflect target `Ident(::Ident)*>`. */
    private fun isReflectTargetAhead(): Boolean {
        var i = current + 1
        if (tokens.getOrNull(i)?.type != TokenType.IDENTIFIER) return false
        i++
        while (tokens.getOrNull(i)?.type == TokenType.DOUBLE_COLON) {
            if (tokens.getOrNull(i + 1)?.type != TokenType.IDENTIFIER) return false
            i += 2
        }
        return tokens.getOrNull(i)?.type == TokenType.GREATER
    }

    /** Parses `X` / `X::member` inside `reflect<…>` into the mangled identifier. */
    private fun parseReflectTarget(): Expr {
        val start = peek()
        var name = consume(TokenType.IDENTIFIER, "Expected a name inside 'reflect<...>'").lexeme
        while (match(TokenType.DOUBLE_COLON)) {
            name += "__" + consume(TokenType.IDENTIFIER, "Expected name after '::' in 'reflect<...>'").lexeme
        }
        return Expr.Identifier(name, start.line, start.column, name.length)
    }

    /**
     * Rewrites a call to a cast intrinsic (`std::cast`/`dyncast`/`bitcast`, mangled
     * `std__cast` etc.) into the corresponding [Expr.Cast]; returns null otherwise.
     * The single type argument is the target type and the single value argument the
     * operand.
     */
    private fun rewriteCastIntrinsic(call: Expr.Call): Expr.Cast? {
        val kind = when (call.callee.substringAfterLast("__")) {
            "cast" -> CastKind.STATIC
            "dyncast" -> CastKind.DYNAMIC
            "bitcast" -> CastKind.REINTERPRET
            else -> return null
        }
        val target = call.typeArgs.singleOrNull() ?: return null
        val value = call.args.singleOrNull() ?: return null
        return Expr.Cast(value, target, kind, call.line, call.column, call.length)
    }

    private fun parseUnary(): Expr {
        if (check(TokenType.TRY)) {
            val at = advance()
            return Expr.TryPropagate(parseUnary(), at.line, at.column, at.lexeme.length)
        }
        // `alloc <expr>` — heap-allocate a single value (e.g. `alloc P(10)`, `alloc 42`).
        // `alloc T[N]` — allocate a buffer of N T's → T* (C++ `new T[N]`-style).
        if (check(TokenType.ALLOC)) {
            val at = advance()
            val operand = parseUnary()
            if (operand is Expr.Index && operand.target is Expr.Identifier) {
                return Expr.AllocBuffer(operand.target.name, operand.index, at.line, at.column, at.lexeme.length)
            }
            return Expr.Alloc(operand, at.line, at.column, at.lexeme.length)
        }
        // `isolated(expr)` — explicit deep copy.
        if (check(TokenType.ISOLATED)) {
            val at = advance()
            consume(TokenType.L_PAREN, "Expected '(' after 'isolated'")
            val value = parseExpr()
            consume(TokenType.R_PAREN, "Expected ')' after 'isolated(...'")
            return Expr.Isolated(value, at.line, at.column, at.lexeme.length)
        }
        // `*ptr` / `deref ptr` — pointer or smart-pointer dereference.
        if (check(TokenType.STAR)) {
            val at = advance()
            return Expr.Deref(parseUnary(), at.line, at.column, at.lexeme.length)
        }
        // `#expr` — hash: invokes the operand type's `oper#` overload (a `toHash`).
        if (check(TokenType.HASH)) {
            val at = advance()
            val operand = parseUnary()
            return Expr.MethodCall(operand, "oper#", emptyList(), at.line, at.column)
        }
        if (check(TokenType.DEREF)) {
            val at = advance()
            return Expr.Deref(parseUnary(), at.line, at.column, at.lexeme.length)
        }
        // `await task` — suspend until the task completes.
        if (check(TokenType.AWAIT)) {
            val at = advance()
            return Expr.Await(parseUnary(), at.line, at.column, at.lexeme.length)
        }
        // `inject Type` — resolve the singleton instance.
        if (check(TokenType.INJECT)) {
            val at = advance()
            val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'inject'").lexeme
            // Chain into parsePostfix so `inject Config.get()` works.
            return parsePostfix(Expr.Inject(typeName, at.line, at.column, at.lexeme.length))
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
            when {
                // Macro invocation `name!(…)`, `name![…]`, `name!{…}`. `!=` is its
                // own token, so a lone `BANG` immediately after an identifier and a
                // delimiter is unambiguously a macro call. The delimiter is ergonomic
                // only; all three forms feed args to the same arms (dropped at parse).
                expr is Expr.Identifier && check(TokenType.BANG) && peekNext()?.type in setOf(
                    TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE,
                ) -> {
                    val bang = advance() // '!'
                    val args = parseMacroInvokeArgs()
                    usedMetaInvoke = true
                    expr = Expr.MetaInvoke(expr.name, args, expr.line, expr.column, bang.column + 1)
                }
                check(TokenType.DOT) -> {
                    val dot = advance()
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
                check(TokenType.DOUBLE_COLON) -> {
                    // Namespace member access `Name::member` → mangled identifier `Name__member`.
                    advance() // '::'
                    val member = consumeIdentifierLike("Expected member name after '::'")
                    expr = when (expr) {
                        is Expr.Identifier -> Expr.Identifier("${expr.name}__$member", expr.line, expr.column, expr.length + 2 + member.length)
                        else -> error("'::' must follow a namespace name at line ${peek().line}")
                    }
                }
                check(TokenType.L_BRACKET) -> {
                    advance()
                    // `a[start:stop:step]` — a Python-style slice → oper[:]. An absent
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
                check(TokenType.LESS) && expr is Expr.Member && expr.name in setOf("hasDeco", "decoMeta") -> {
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
                    val intrinsic = if (expr.name == "hasDeco") "__hasDeco" else "__decoMeta"
                    expr = Expr.Call(intrinsic, reflected.args, expr.line, expr.column, expr.length, typeArgs)
                }
                check(TokenType.LESS) && expr is Expr.Identifier &&
                    isReflectName((expr as Expr.Identifier).name) && isReflectTargetAhead() -> {
                    // `std::reflect<X>` — compile-time reflection prop. Desugars to the
                    // internal `__reflect(X)` receiver used by `.hasDeco`/`.decoMeta`/`.zone`.
                    val start = expr
                    advance() // '<'
                    val target = parseReflectTarget()
                    consume(TokenType.GREATER, "Expected '>' to close 'reflect<...>'")
                    expr = Expr.Call("__reflect", listOf(target), start.line, start.column, start.length)
                }
                check(TokenType.LESS) && isGenericCallAhead() -> {
                    // `f<T, U>(args)` — capture explicit type arguments for the call
                    // (e.g. `tupleOf<Int, Real>(…)`) for monomorphization; the '(' is
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
                    if (!check(TokenType.R_PAREN)) {
                        do {
                            // Spread: `...arr` — prefix splat of the array's elements into individual args.
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
                        } while (match(TokenType.COMMA))
                    }
                    consume(TokenType.R_PAREN, "Expected ')' after arguments")
                    // Trailing lambda: f(args) { x -> ... }
                    if (allowTrailingLambda && check(TokenType.L_BRACE)) {
                        val lb = peek()
                        val isAsync = expr is Expr.Identifier && expr.name == "async"
                        args.add(parseLambda(lb.line, lb.column, implicitIt = !isAsync))
                    }
                    expr = when (expr) {
                        is Expr.Identifier -> {
                            val call = Expr.Call(expr.name, args, expr.line, expr.column, expr.length, pendingCallTypeArgs)
                            pendingCallTypeArgs = emptyList()
                            rewriteCastIntrinsic(call) ?: call
                        }
                        is Expr.Member -> Expr.MethodCall(expr.target, expr.name, args, expr.line, expr.column)
                        else -> error("Invalid call target at line ${peek().line}")
                    }
                }
                allowTrailingLambda && check(TokenType.L_BRACE) && expr is Expr.Identifier && expr.name == "async" -> {
                    val lb = peek()
                    expr = Expr.Call(
                        "async",
                        listOf(parseLambda(lb.line, lb.column, implicitIt = false)),
                        expr.line,
                        expr.column,
                        expr.length
                    )
                }
                else -> return expr
            }
        }
    }

    /**
     * Parses the argument list of a macro invocation `name!<delim> … <delim>`.
     * The opening delimiter (`(`/`[`/`{`) is the current token; arguments are a
     * comma-separated list of expressions with `...expr` spread support (mirrors
     * the call-argument grammar). The delimiter is consumed and not retained —
     * macro arms match delimiter-agnostically.
     */
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
                    parseExpr()
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
    private fun isGenericCallAhead(): Boolean {
        var i = current + 1 // token after '<'
        var depth = 1
        var steps = 0
        while (i < tokens.size && steps < 24) {
            when (tokens[i].type) {
                TokenType.IDENTIFIER, TokenType.COMMA, TokenType.DOT,
                TokenType.L_BRACKET, TokenType.R_BRACKET, TokenType.QMARK,
                TokenType.COLON, TokenType.STAR, TokenType.INT_LITERAL -> {}
                TokenType.LESS -> depth++
                TokenType.GREATER -> {
                    depth--
                    if (depth == 0) return tokens.getOrNull(i + 1)?.type == TokenType.L_PAREN
                }
                // `>>` closes two nested generic levels at once (e.g. `f<G<T>>(args)`).
                TokenType.SHIFT_RIGHT -> {
                    depth -= 2
                    if (depth == 0) return tokens.getOrNull(i + 1)?.type == TokenType.L_PAREN
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
     * If-expression `if cond { a } else { b }` — used in expression position
     * (`return if …`, `let x = if …`, `func f() = if …`). Each branch holds a
     * single expression; `else if` chains nest naturally. Statement-position
     * `if` is unaffected (the statement parser checks for it first).
     */
    private fun parseIfExpr(): Expr {
        val start = consume(TokenType.IF, "Expected 'if'")
        val condition = parseExpr()
        consume(TokenType.L_BRACE, "Expected '{' after if-expression condition")
        skipNewlines()
        val thenExpr = parseExpr()
        skipNewlines()
        consume(TokenType.R_BRACE, "Expected '}' after if-expression value")
        skipNewlines()
        consume(TokenType.ELSE, "Expected 'else' — an if-expression needs both branches")
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
        // `<...T>{ … }` — variadic lambda (implicit `it` is the packed array of all args).
        if (tok.type == TokenType.LESS && isVariadicLambdaAhead()) {
            advance() // '<'
            consume(TokenType.ELLIPSIS, "Expected '...' in variadic lambda type parameter")
            consume(TokenType.IDENTIFIER, "Expected variadic type parameter").lexeme // T
            consume(TokenType.GREATER, "Expected '>' after variadic lambda type parameter")
            val lb = peek()
            return parseLambda(lb.line, lb.column, implicitIt = true, variadic = true)
        }
        return when (tok.type) {
            TokenType.IF -> parseIfExpr()
            TokenType.INT_LITERAL -> {
                advance()
                val numLit = tok.literal as NumericLiteral
                Expr.IntLiteral(numLit.value as Long, tok.line, tok.column, tok.lexeme.length, numLit.suffix)
            }
            TokenType.REAL_LITERAL -> {
                advance()
                val numLit = tok.literal as NumericLiteral
                Expr.RealLiteral(numLit.value as Double, tok.line, tok.column, tok.lexeme.length, numLit.suffix)
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
            TokenType.IDENTIFIER, TokenType.SHARED, TokenType.WEAK,
            TokenType.REVERSE -> {
                advance()
                Expr.Identifier(tok.lexeme, tok.line, tok.column, tok.lexeme.length)
            }
            TokenType.BASE -> {
                advance()
                // `base` parses as a synthetic identifier the IrGenerator intercepts for parent dispatch.
                Expr.Identifier("__base__", tok.line, tok.column, tok.lexeme.length)
            }
            TokenType.DOUBLE_COLON -> {
                advance() // consume first '::'
                var depth = 1
                // ::_::_::x pattern — each _:: adds one depth level
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
                    // or the `tup!` macro.
                    error("tuple literal '(a, b, …)' is not supported; use 'std::tupleOf(a, b, …)' or 'tup!(a, b, …)' at line ${tok.line}")
                }
                consume(TokenType.R_PAREN, "Expected ')'")
                Expr.Grouping(first, tok.line, tok.column)
            }
            TokenType.L_BRACKET -> {
                advance()
                if (check(TokenType.R_BRACKET)) {
                    advance()
                    Expr.ArrayLiteral(emptyList(), tok.line, tok.column)
                } else {
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
                        // Array literal: [1, 2, 3]
                        val elements = mutableListOf(first)
                        while (match(TokenType.COMMA)) { elements.add(parseExpr()) }
                        consume(TokenType.R_BRACKET, "Expected ']' after array elements")
                        Expr.ArrayLiteral(elements, tok.line, tok.column)
                    }
                }
            }
            TokenType.L_BRACE -> parseLambda(tok.line, tok.column)
            // `task { body }` — a no-argument thunk (a lambda), awaited later.
            TokenType.TASK -> {
                val t = advance() // 'task'
                consume(TokenType.L_BRACE, "Expected '{' after 'task'")
                skipNewlines()
                val body = parseBlock().toMutableList()
                if (body.isNotEmpty() && body.last() is Stmt.ExprStmt) {
                    val last = body.removeAt(body.size - 1) as Stmt.ExprStmt
                    body.add(Stmt.Return(last.expr, last.line, last.column, last.length))
                }
                consume(TokenType.R_BRACE, "Expected '}' after task body")
                Expr.Lambda(emptyList(), body, t.line, t.column)
            }
            // `launch { body }` — fire-and-forget task; desugars to a __launch(thunk) call.
            TokenType.LAUNCH -> {
                val t = advance() // 'launch'
                consume(TokenType.L_BRACE, "Expected '{' after 'launch'")
                skipNewlines()
                val body = parseBlock().toMutableList()
                if (body.isNotEmpty() && body.last() is Stmt.ExprStmt) {
                    val last = body.removeAt(body.size - 1) as Stmt.ExprStmt
                    body.add(Stmt.Return(last.expr, last.line, last.column, last.length))
                }
                consume(TokenType.R_BRACE, "Expected '}' after launch body")
                Expr.Call("__launch", listOf(Expr.Lambda(emptyList(), body, t.line, t.column)), t.line, t.column)
            }
            else -> error("Unexpected token '${tok.lexeme}' at line ${tok.line}")
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** `{ params -> body }`, `{ -> body }`, or `{ body }` (implicit `it`). */
    private fun parseLambda(line: Int, column: Int, implicitIt: Boolean = true, variadic: Boolean = false): Expr.Lambda {
        consume(TokenType.L_BRACE, "Expected '{'")
        skipNewlines()
        val params = mutableListOf<Param>()
        if (variadic) {
            // `<...T>{ … }` — `it` is the array of all call arguments.
            params.add(Param("it", TypeRef.Array(TypeRef.Named("Any"))))
        } else if (check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.COLON) {
            // Detect typed params: IDENT COLON type (, ...)? ->
            do {
                val name = consume(TokenType.IDENTIFIER, "Expected lambda parameter name").lexeme
                consume(TokenType.COLON, "Expected ':' after lambda parameter name")
                val type = parseTypeName()
                params.add(Param(name, type))
            } while (match(TokenType.COMMA))
            consume(TokenType.ARROW, "Expected '->' in lambda")
        } else if (!check(TokenType.ARROW) && implicitIt) {
            // No params, no arrow → implicit `it`
            params.add(Param("it", TypeRef.Named("Any")))
        } else if (check(TokenType.ARROW)) {
            consume(TokenType.ARROW, "Expected '->' in lambda")
        }
        skipNewlines()
        val body = parseBlock().toMutableList()
        if (body.isNotEmpty() && body.last() is Stmt.ExprStmt) {
            val last = body.removeAt(body.size - 1) as Stmt.ExprStmt
            body.add(Stmt.Return(last.expr, last.line, last.column, last.length))
        }
        consume(TokenType.R_BRACE, "Expected '}' after lambda body")
        return Expr.Lambda(params, body, line, column, variadic = variadic)
    }

    /** True when `<...T>{` opens a variadic lambda (vs. a less-than comparison). */
    private fun isVariadicLambdaAhead(): Boolean {
        return tokens.getOrNull(current + 1)?.type == TokenType.ELLIPSIS &&
            tokens.getOrNull(current + 2)?.type == TokenType.IDENTIFIER &&
            tokens.getOrNull(current + 3)?.type == TokenType.GREATER &&
            tokens.getOrNull(current + 4)?.type == TokenType.L_BRACE
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

    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        error("$message, got '${peek().lexeme}' (${peek().type}) at line ${peek().line}")
    }
}
