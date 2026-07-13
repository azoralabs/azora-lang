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
 *   program     → package? funcDecl*
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
class Parser(private val tokens: List<Token>) {

    /**
     * Extra top-level items synthesized while parsing another declaration
     * (currently: azora-facing wrappers for aliased bridge signatures).
     * Drained into the item list after each declaration completes.
     */
    private val aliasWrappers = mutableListOf<TopLevel>()

    /** `func sin as az_sin(x: Real): Real` → wrapper `func sin(x) { return az_sin(x) }`. */
    private fun makeBridgeWrapper(name: String, externName: String, params: List<Param>, returnType: TypeRef, line: Int) {
        val args = params.map { Expr.Identifier(it.name, line) }
        val call = Expr.Call(externName, args, line)
        val body: List<Stmt> =
            if ((returnType as? TypeRef.Named)?.name == "Unit") listOf(Stmt.ExprStmt(call, line))
            else listOf(Stmt.Return(call, line))
        aliasWrappers.add(TopLevel.Func(FuncDecl(name, params, TypeAnnotation.Explicit(returnType), body, line = line)))
    }

    private var current = 0

    /** When `>>` (SHIFT_RIGHT) is split for nested generics, this flag signals a pending `>` for the enclosing type. */
    private var pendingGreater = false

    private var contractResultCounter = 0

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
     * `package` declaration followed by zero or more top-level items
     * (function declarations and compile-time constructs).
     *
     * @return the parsed [Program] representing the complete source file
     * @throws IllegalStateException on syntax errors
     */
    fun parse(): Program {
        skipNewlines()
        // `@file:...` annotations are file-level metadata (e.g. `@file:experimental`)
        // and may precede the package declaration — consume and discard them.
        while (check(TokenType.AT) && peekNext()?.lexeme == "file") {
            advance() // '@'
            advance() // 'file'
            consume(TokenType.COLON, "Expected ':' after '@file'")
            consume(TokenType.IDENTIFIER, "Expected annotation name after '@file:'")
            if (match(TokenType.L_PAREN)) {
                while (!check(TokenType.R_PAREN) && !isAtEnd()) advance()
                consume(TokenType.R_PAREN, "Expected ')' after '@file:' annotation arguments")
            }
            skipNewlines()
        }
        val packageName = when {
            check(TokenType.PACKAGE) -> parsePackage()
            check(TokenType.MODULE) -> { advance(); consume(TokenType.IDENTIFIER, "Expected module name").lexeme.also { consumeNewline() } }
            else -> null
        }
        val items = mutableListOf<TopLevel>()
        while (!isAtEnd()) {
            skipNewlines()
            if (isAtEnd()) break
            if (check(TokenType.ZONE) && peekNext()?.type == TokenType.IDENTIFIER) {
                // `zone Name { … }` — a named zone is a namespace; it desugars to
                // mangled top-level items (`Name__member`). Anonymous `zone { … }`
                // (no name) keeps its block-scope meaning inside function bodies.
                items.addAll(parseNamedZone())
            } else if (isFriendZoneNamespaceAhead()) {
                items.addAll(parseFriendZoneNamespace())
            } else {
                items.add(parseTopLevel())
            }
            if (aliasWrappers.isNotEmpty()) {
                items.addAll(aliasWrappers)
                aliasWrappers.clear()
            }
        }
        val localPackNames = items.filterIsInstance<TopLevel.Pack>().mapTo(mutableSetOf()) { it.name }
        return Program(packageName, items, localPackNames)
    }

    /**
     * `zone Name { items }` — a named zone acts as a namespace. Desugared at parse
     * time: each member becomes a top-level item with a mangled name `Name__member`
     * (so `Name::member` resolves to it). Nested named zones nest the prefix.
     * Produces no dedicated AST node, so no downstream changes are needed.
     */
    private fun parseNamedZone(): List<TopLevel> {
        consume(TokenType.ZONE, "Expected 'zone'")
        val name = consume(TokenType.IDENTIFIER, "Expected zone name").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after zone name")
        skipNewlines()
        val items = parseZoneBody(name)
        consume(TokenType.R_BRACE, "Expected '}' after zone")
        consumeNewline()
        return items
    }

    private fun isFriendZoneNamespaceAhead(): Boolean {
        if (!check(TokenType.FRIEND) || peekNext()?.type != TokenType.ZONE) return false
        var i = current + 2
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
     * `friend zone Name { items }` — a shared namespace contribution. Unlike a
     * named `zone`, it does not mangle declarations with [Name]; stdlib modules
     * use package names for import/qualified lookup, and the friend-zone wrapper
     * only marks that multiple files may contribute to the same logical zone.
     */
    private fun parseFriendZoneNamespace(): List<TopLevel> {
        consume(TokenType.FRIEND, "Expected 'friend'")
        consume(TokenType.ZONE, "Expected 'zone' after 'friend'")
        consume(TokenType.IDENTIFIER, "Expected zone name after 'friend zone'")
        while (match(TokenType.DOUBLE_COLON)) {
            consume(TokenType.IDENTIFIER, "Expected zone name after '::' in friend zone path")
        }
        consume(TokenType.L_BRACE, "Expected '{' after friend zone name")
        skipNewlines()
        val items = mutableListOf<TopLevel>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            when {
                isFriendZoneNamespaceAhead() -> items.addAll(parseFriendZoneNamespace())
                check(TokenType.ZONE) && peekNext()?.type == TokenType.IDENTIFIER -> items.addAll(parseNamedZone())
                else -> items.add(parseTopLevel())
            }
            if (aliasWrappers.isNotEmpty()) {
                items.addAll(aliasWrappers)
                aliasWrappers.clear()
            }
        }
        consume(TokenType.R_BRACE, "Expected '}' after friend zone")
        consumeNewline()
        return items
    }

    private fun parseZoneBody(prefix: String): List<TopLevel> {
        val result = mutableListOf<TopLevel>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            if (check(TokenType.ZONE) && peekNext()?.type == TokenType.IDENTIFIER) {
                consume(TokenType.ZONE, "Expected 'zone'")
                val inner = consume(TokenType.IDENTIFIER, "Expected zone name").lexeme
                consume(TokenType.L_BRACE, "Expected '{' after zone name")
                skipNewlines()
                result.addAll(parseZoneBody("${prefix}__$inner"))
                consume(TokenType.R_BRACE, "Expected '}' after nested zone")
                skipNewlines()
            } else {
                result.add(mangleTopLevel(parseTopLevel(), prefix))
            }
        }
        return result
    }

    /** Qualifies a top-level item's name with [prefix] (e.g. `PI` → `Math__PI`). */
    private fun mangleTopLevel(item: TopLevel, prefix: String): TopLevel = when (item) {
        is TopLevel.Func -> item.copy(decl = item.decl.copy(name = "${prefix}__${item.decl.name}"))
        is TopLevel.FinDecl -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.VarDecl -> item.copy(name = "${prefix}__${item.name}")
        is TopLevel.LetDecl -> item.copy(name = "${prefix}__${item.name}")
        else -> item
    }

    private fun parseVisibility(): Visibility = when {
        match(TokenType.EXPOSE) -> Visibility.EXPOSE
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
            check(TokenType.TEST) -> parseTestDecl()
            check(TokenType.DECO) -> parseDeco()
            check(TokenType.PACK) -> parsePack(annotations, visibility)
            check(TokenType.ENUM) -> parseEnumDecl(annotations)
            check(TokenType.FAIL) -> parseFailDecl(annotations)
            check(TokenType.IMPL) -> parseImpl()
            check(TokenType.INFX) -> parseInfx()
            check(TokenType.BRIDGE) -> parseBridge()
            check(TokenType.SOLO) -> parseSolo(visibility)
            check(TokenType.WRAP) -> parseWrap()
            check(TokenType.NODE) -> parseNode(visibility = visibility)
            check(TokenType.LEAF) -> parseNode(isLeaf = true, visibility = visibility)
            check(TokenType.VIEW) -> parseView()
            check(TokenType.HOOK) -> parseHook()
            check(TokenType.THREADLOCAL) -> parseThreadLocal(visibility)
            check(TokenType.SPEC) -> parseSpec()
            check(TokenType.SLOT) -> parseSlot()
            check(TokenType.TYPEALIAS) -> parseTypeAlias()
            check(TokenType.USE) -> parseUse()
            check(TokenType.FIN) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.FinDecl(name, type, init, start.line, start.column, annotations, visibility = visibility) }
            check(TokenType.VAR) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.VarDecl(name, type, init, start.line, start.column, annotations, visibility = visibility) }
            check(TokenType.LET) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.LetDecl(name, type, init, start.line, start.column, annotations, visibility) }
            else -> error("Expected 'func', 'fin', 'var', 'let', 'test', 'inline', or 'deepinline' at top level, got '${peek().lexeme}' at line ${peek().line}")
        }
    }

    /**
     * Parses zero or more leading decorator applications: `@Name`, `@Name(args)`,
     * or `@target:Name`. These appear at top level before a declaration (labels
     * use `@` too, but only at statement level before a loop, so there's no clash).
     */
    private fun parseAnnotations(): List<Annotation> {
        val result = mutableListOf<Annotation>()
        while (check(TokenType.AT)) {
            val at = advance() // '@'
            val first = consume(TokenType.IDENTIFIER, "Expected name after '@'").lexeme
            val (target, name) = if (match(TokenType.COLON)) {
                val n = consume(TokenType.IDENTIFIER, "Expected decorator name after ':'").lexeme
                first to n
            } else {
                null to first
            }
            val (args, namedArgs) = if (match(TokenType.L_PAREN)) {
                val positional = mutableListOf<Expr>()
                val named = mutableListOf<Pair<String, Expr>>()
                if (!check(TokenType.R_PAREN)) {
                    do {
                        // `.Native` — dotted member shorthand used in annotation
                        // arguments (`@target(.Native)`); recorded as an identifier.
                        if (check(TokenType.DOT) && peekNext()?.type == TokenType.IDENTIFIER) {
                            val dot = advance()
                            val member = advance().lexeme
                            positional.add(Expr.Identifier(".$member", dot.line, dot.column))
                        } else if (check(TokenType.IDENTIFIER) && peekNext()?.type in setOf(TokenType.COLON, TokenType.EQUAL)) {
                            // Named argument: `key: value` or `key = value` (e.g. `@experimental(since: "0.0.1")`).
                            val key = advance().lexeme
                            advance() // ':' or '='
                            named.add(key to parseExpr())
                        } else {
                            positional.add(parseExpr())
                        }
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_PAREN, "Expected ')' after annotation arguments")
                positional to named
            } else emptyList<Expr>() to emptyList<Pair<String, Expr>>()
            result.add(Annotation(name, args, target, at.line, at.column, namedArgs = namedArgs))
            skipNewlines()
        }
        return result
    }

    /** `deco Name { fields }` — declares a decorator/annotation type. */
    private fun parseDeco(): TopLevel.Deco {
        val start = peek()
        consume(TokenType.DECO, "Expected 'deco'")
        val name = consume(TokenType.IDENTIFIER, "Expected decorator name").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after deco name")
        skipNewlines()
        val fields = mutableListOf<PackField>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            fields.add(parsePackField())
            match(TokenType.COMMA)
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after deco fields")
        consumeNewline()
        return TopLevel.Deco(name, fields, start.line, start.column)
    }

    private fun parseTopLevelInline(): TopLevel {
        return when (peekNext()?.type) {
            TokenType.FUNC -> { advance(); TopLevel.Func(parseFuncDecl(isInline = true)) }
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
        val start = peek()
        return when {
            check(TokenType.FUNC) -> TopLevel.Func(parseFuncDecl(isInline = deepInline))
            check(TokenType.TEST) -> parseTestDecl()
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

    private fun parseTestDecl(): TopLevel.Test {
        val start = peek()
        consume(TokenType.TEST, "Expected 'test'")
        val name = consume(TokenType.STRING_LITERAL, "Expected test name string").literal as String
        consume(TokenType.L_BRACE, "Expected '{' after test name")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after test body")
        consumeNewline()
        return TopLevel.Test(name, body, start.line, start.column)
    }

    /**
     * `pack Name { fields }` — a struct declaration. Fields are `[var|fin|let] name: Type [= default]`,
     * one per line.
     */
    private fun parsePack(
        annotations: List<Annotation> = emptyList(),
        visibility: Visibility = Visibility.EXPOSE,
    ): TopLevel.Pack {
        val start = peek()
        consume(TokenType.PACK, "Expected 'pack'")
        val name = consume(TokenType.IDENTIFIER, "Expected pack name").lexeme
        val tp = parseTypeParams()
        val minLen = parseVariadicWhereClause()
        val enforceNumFields = annotations.any { it.name == "enforceNumFields" }
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
                fieldTemplate = null,
            )
        }
        consume(TokenType.L_BRACE, "Expected '{' after pack name")
        skipNewlines()
        // A variadic pack's body is a field-generating template:
        // `inline for Ty in T with index { $index: Ty }`.
        val fieldTemplate = if (tp.variadic != null && check(TokenType.INLINE)) parseVariadicFieldTemplate() else null
        val fields = mutableListOf<PackField>()
        if (fieldTemplate == null) {
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                skipNewlines()
                if (check(TokenType.R_BRACE)) break
                fields.add(parsePackField(enforceNumFields = enforceNumFields))
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
            fieldTemplate = fieldTemplate,
        )
    }

    /** `inline for <loopVar> in <packVar> with index { <fields> }` — a variadic pack's field template. */
    private fun parseVariadicFieldTemplate(): VariadicFieldTemplate {
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.FOR, "Expected 'for'")
        val loopVar = consume(TokenType.IDENTIFIER, "Expected loop type variable").lexeme
        consume(TokenType.IN, "Expected 'in' after loop variable")
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
            if (check(TokenType.MIXIN)) {
                advance() // 'mixin'
                val template = parsePrimary()
                val str = template as? Expr.StringTemplate
                    ?: error("Expected interpolated string after 'mixin' at line ${peek().line}")
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

    private fun parsePackField(preparsedVisibility: Visibility? = null, enforceNumFields: Boolean = false): PackField {
        val visibility = preparsedVisibility ?: parseVisibility()
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
        return PackField(name, type, mutable, default, visibility)
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
                args.add(parseTypeName())
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.GREATER, "Expected '>' after generic type arguments")
        return args
    }

    private fun typeMethodSuffix(type: TypeRef): String {
        val raw = when (type) {
            is TypeRef.Named -> type.name
            else -> type.toString()
        }.filter { it.isLetterOrDigit() }
        val cleaned = raw.ifEmpty { "Value" }
        return cleaned[0].uppercaseChar() + cleaned.drop(1)
    }

    private fun castMethodName(type: TypeRef): String = "as${typeMethodSuffix(type)}"

    private fun callbackTraitMethodName(traitName: String, traitArgs: List<TypeRef>): String {
        val suffix = traitArgs.firstOrNull()?.let { typeMethodSuffix(it) } ?: "Value"
        return when (traitName) {
            "Into" -> "to$suffix"
            "From" -> "from$suffix"
            else -> if (traitName.isEmpty()) "callback" else traitName[0].lowercaseChar() + traitName.drop(1)
        }
    }

    private fun callbackTraitReturnType(traitArgs: List<TypeRef>): TypeAnnotation =
        TypeAnnotation.Explicit(traitArgs.firstOrNull() ?: TypeRef.Named("Any"))

    /** `impl Type { methods }` or `impl Trait for Type { methods }`. */
    private fun parseImpl(): TopLevel.Impl {
        val start = peek()
        consume(TokenType.IMPL, "Expected 'impl'")
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
            consume(TokenType.R_BRACKET, "Expected ']' after 'oper['")
            val isSet = match(TokenType.EQUAL)
            val methodName = if (isSet) "indexSet" else "index"
            consume(TokenType.FOR, "Expected 'for' after 'impl oper[]' / 'impl oper[]='")
            val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'for'").lexeme
            val typeArgs = parseGenericTypeArgsIfPresent()
            consume(TokenType.L_BRACE, "Expected '{' after impl oper[] for Type")
            skipNewlines()
            // Untyped params: `mut ref self, index[, element]` — types inferred from the oper kind.
            val indexType = if (typeName == "Map" && typeArgs.isNotEmpty()) typeArgs[0] else TypeRef.Named("Int")
            val valueType = when {
                typeName == "Map" && typeArgs.size >= 2 -> typeArgs[1]
                typeArgs.isNotEmpty() -> typeArgs[0]
                else -> TypeRef.Named("Any")
            }
            val paramTypes = if (isSet) listOf(TypeRef.Named(typeName), indexType, valueType)
                else listOf(TypeRef.Named(typeName), indexType)
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
                null,
                operStart.line,
                operStart.column,
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
        val first = consume(TokenType.IDENTIFIER, "Expected type or trait name after 'impl'").lexeme
        val firstArgs = parseGenericTypeArgsIfPresent()
        var typeName = first
        var traitName: String? = null
        var traitArgs = emptyList<TypeRef>()
        if (match(TokenType.FOR)) {
            if (isPackImpl) error("'impl pack' cannot be used for spec implementations at line ${peek().line}")
            traitName = first
            traitArgs = firstArgs
            typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'for'").lexeme
            skipGenericTypeArgs() // `… for Type<T>` — discard erased type args on the type name
        }
        if (!check(TokenType.L_BRACE)) {
            consumeNewline()
            return TopLevel.Impl(typeName, emptyList(), traitName, start.line, start.column, isPackImpl = isPackImpl, traitArgs = traitArgs)
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
            val method = FuncDecl(
                name = callbackTraitMethodName(traitName, traitArgs),
                params = emptyList(),
                returnType = callbackTraitReturnType(traitArgs),
                body = body,
                line = start.line,
                column = start.column,
                receiverModifier = "ref",
            )
            return TopLevel.Impl(typeName, listOf(method), traitName, start.line, start.column, traitArgs = traitArgs)
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
                        methods.add(FuncDecl(propName, emptyList(), propType, listOf(Stmt.Return(expr, expr.line, expr.column)), false, emptyList(), methodStart.line, methodStart.column, annotations = memberAnnotations, visibility = visibility, receiverModifier = "ref"))
                    } else {
                        consume(TokenType.L_BRACE, "Expected '{' after prop type")
                        skipNewlines()
                        val propBody = parseBlock()
                        consume(TokenType.R_BRACE, "Expected '}' after prop body")
                        consumeNewline()
                        methods.add(FuncDecl(propName, emptyList(), propType, propBody, false, emptyList(), methodStart.line, methodStart.column, annotations = memberAnnotations, visibility = visibility, receiverModifier = "ref"))
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
        return TopLevel.Impl(typeName, methods, traitName, start.line, start.column, isPackImpl = isPackImpl, traitArgs = traitArgs)
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
    private fun parseInfx(): TopLevel.Impl {
        val start = peek()
        consume(TokenType.INFX, "Expected 'infx'")
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
        consume(TokenType.L_BRACE, "Expected '{' before infx method body")
        skipNewlines()
        val body = mutableListOf<Stmt>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            body.add(parseStmt())
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after infx method body")
        consumeNewline()
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
    private fun parseBridge(): TopLevel.Bridge {
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
            val alias = if (match(TokenType.AS)) consume(TokenType.IDENTIFIER, "Expected extern symbol after 'as'").lexeme else null
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
        return TopLevel.Bridge(target, funcs, start.line, start.column)
    }

    /** `solo Name { fields; methods }` — a singleton struct with one shared instance. */
    private fun parseSolo(visibility: Visibility = Visibility.EXPOSE): TopLevel.Solo {
        val start = consume(TokenType.SOLO, "Expected 'solo'")
        val name = consume(TokenType.IDENTIFIER, "Expected solo name").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after solo name")
        skipNewlines()
        val fields = mutableListOf<PackField>()
        val methods = mutableListOf<FuncDecl>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            val memberVisibility = parseVisibility()
            if (check(TokenType.FUNC)) {
                methods.add(parseFuncDecl(visibility = memberVisibility))
            } else {
                fields.add(parsePackField(memberVisibility))
            }
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after solo body")
        consumeNewline()
        return TopLevel.Solo(name, fields, methods, start.line, start.column, visibility)
    }

    /**
     * `[leaf] node Name(var|fin param: Type, ...) [: Parent(args)] { methods; fields }`
     * — an inheritable type. Ctor params are fields. `repl func` marks overrides.
     */
    private fun parseNode(isLeaf: Boolean = false, visibility: Visibility = Visibility.EXPOSE): TopLevel.Node {
        val start = peek()
        if (isLeaf) consume(TokenType.LEAF, "Expected 'leaf'")
        match(TokenType.NODE) // `leaf Name` or `leaf node Name` — node is optional
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
            val isRepl = match(TokenType.REPL)
            val isVirt = match(TokenType.VIRT)
            val memberVisibility = parseVisibility()
            when {
                check(TokenType.FUNC) -> {
                    val method = parseFuncDecl(isOverride = isRepl, isVirtual = isVirt || isRepl, visibility = memberVisibility)
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
                    methods.add(FuncDecl(propName, emptyList(), propType, propBody, false, emptyList(), peek().line, peek().column, visibility = memberVisibility, receiverModifier = "ref"))
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
                    extraFields.add(parsePackField(memberVisibility))
                }
                else -> error("Expected 'func', 'repl func', 'virt func', 'prop', 'ctor', 'dtor', or field in node body at line ${peek().line}")
            }
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after node body")
        consumeNewline()
        return TopLevel.Node(name, params, methods, parent, parentArgs, isLeaf, extraFields, start.line, start.column, visibility)
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
    private fun parseSlot(): TopLevel.Slot {
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
        return TopLevel.Slot(name, variants, start.line, start.column)
    }

    /** `spec Name { func method(params): Ret ... }` — a trait declaration (signatures only). */
    private fun parseSpec(): TopLevel.Spec {
        val start = peek()
        consume(TokenType.SPEC, "Expected 'spec'")
        val name = consume(TokenType.IDENTIFIER, "Expected spec name").lexeme
        parseTypeParams() // `spec Comparable<T>` — type parameters accepted (erased for now)
        if (match(TokenType.COLON)) {
            val returnType = parseTypeName()
            val getter = match(TokenType.GET)
            consume(TokenType.L_BRACE, "Expected '{' after spec callback signature")
            skipNewlines()
            val receiverModifier = parseSpecReceiverModifier()
            val receiverName = consumeIdentifierLike("Expected spec callback receiver name")
            consume(TokenType.R_BRACE, "Expected '}' after spec callback receiver")
            consumeNewline()
            return TopLevel.Spec(
                name,
                emptyList(),
                start.line,
                start.column,
                callback = SpecCallback(returnType, getter, receiverModifier, receiverName),
            )
        }
        if (!check(TokenType.L_BRACE)) {
            consumeNewline()
            return TopLevel.Spec(name, emptyList(), start.line, start.column)
        }
        consume(TokenType.L_BRACE, "Expected '{' after spec name")
        skipNewlines()
        val methods = mutableListOf<FuncDecl>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            consume(TokenType.FUNC, "Expected 'func' in spec")
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
        return TopLevel.Spec(name, methods, start.line, start.column)
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

    private fun parsePackage(): String {
        consume(TokenType.PACKAGE, "Expected 'package'")
        // Qualified names are allowed: `package std.math`.
        val name = StringBuilder(consume(TokenType.IDENTIFIER, "Expected package name").lexeme)
        while (match(TokenType.DOT)) {
            name.append('.').append(consume(TokenType.IDENTIFIER, "Expected name after '.' in package").lexeme)
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
        consume(TokenType.L_PAREN, "Expected '(' after function name")
        val params = parseParams()
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
    private fun consumeIdentifierLike(message: String): String {
        val t = peek()
        val soft = t.type == TokenType.REVERSE || t.type == TokenType.BASE ||
            t.type == TokenType.TASK || t.type == TokenType.LEAF || t.type == TokenType.PROP ||
            t.type == TokenType.GET || t.type == TokenType.SET ||
            t.type == TokenType.DROP || t.type == TokenType.MEM || t.type == TokenType.REM || t.type == TokenType.RET ||
            t.type == TokenType.FLIP || t.type == TokenType.FLOP ||
            t.type == TokenType.ALLOC || t.type == TokenType.DEREF || t.type == TokenType.TEST ||
            t.type == TokenType.SHARED || t.type == TokenType.WEAK
        if (t.type == TokenType.IDENTIFIER || soft) {
            advance()
            return t.lexeme
        }
        error("$message, got '${t.lexeme}' (${t.type}) at line ${t.line}")
    }

    private fun parseParams(): List<Param> {
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
            val name = consumeIdentifierLike("Expected parameter name")
            consume(TokenType.COLON, "Expected ':' after parameter name")
            // `...T` marks the (last) parameter variadic; parseTypeName wraps it in Array<T>.
            val isVariadic = check(TokenType.ELLIPSIS)
            val parsedType = parseTypeName()
            val reference = parsedType as? TypeRef.Reference
            val type = reference?.inner ?: parsedType
            val normalizedModifier = reference?.kind?.spelling ?: modifier
            val default = if (match(TokenType.EQUAL)) parseExpr() else null
            params.add(Param(name, type, default, normalizedModifier, variadic = isVariadic))
        } while (match(TokenType.COMMA))
        return params
    }

    /** Result of [parseTypeParams]: the names plus which one (if any) is the variadic pack. */
    private data class TypeParams(val names: List<String>, val variadic: String?)

    /** `<T, U>` type-parameter list. A parameter may be variadic via `...T` (prefix) or `T...` (suffix). */
    private fun parseTypeParams(): TypeParams {
        if (!check(TokenType.LESS)) return TypeParams(emptyList(), null)
        advance()
        val names = mutableListOf<String>()
        var variadic: String? = null
        do {
            val prefixVariadic = match(TokenType.ELLIPSIS) // `...T` prefix form
            val name = consume(TokenType.IDENTIFIER, "Expected type parameter name").lexeme
            val suffixVariadic = match(TokenType.ELLIPSIS) // `T...` suffix form
            if (prefixVariadic || suffixVariadic) variadic = name
            names.add(name)
        } while (match(TokenType.COMMA))
        consume(TokenType.GREATER, "Expected '>' after type parameters")
        return TypeParams(names, variadic)
    }

    /**
     * Optional `where <var>.length >= <N>` constraint on a variadic pack/function.
     * Returns the minimum length, or null if no clause is present.
     */
    private fun parseVariadicWhereClause(): Int? {
        if (peek().type != TokenType.IDENTIFIER || peek().lexeme != "where") return null
        advance() // 'where'
        consume(TokenType.IDENTIFIER, "Expected variadic param name after 'where'").lexeme // pack var
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
        // `...T` — variadic type (prefix). Wraps the type in an array.
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
        // Suffix type modifiers: `T?` (nullable) and `T!ErrSet` (failable), in any order.
        while (true) {
            base = when {
                match(TokenType.QMARK) -> TypeRef.Nullable(base)
                check(TokenType.STAR) -> { advance(); TypeRef.Pointer(base) }
                check(TokenType.BANG) && peekNext()?.type == TokenType.IDENTIFIER -> {
                    advance() // '!'
                    val errSet = consume(TokenType.IDENTIFIER, "Expected error-set name after '!'").lexeme
                    TypeRef.Failable(base, errSet)
                }
                else -> return base
            }
        }
    }

    /**
     * Parses a structured type reference.
     *
     * Supports: `Array<T>`, `List<T>`, `Set<T>`, `Map<K, V>` as ordinary generic names,
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
                // `[T]` array type, or `[K: V]` map type.
                advance() // consume '['
                val keyOrElem = parseTypeName()
                if (match(TokenType.COLON)) {
                    val value = parseTypeName()
                    consume(TokenType.R_BRACKET, "Expected ']' after map type")
                    TypeRef.Map(keyOrElem, value)
                } else {
                    consume(TokenType.R_BRACKET, "Expected ']' after array element type")
                    TypeRef.Array(keyOrElem)
                }
            }
            check(TokenType.L_PAREN) -> {
                advance() // consume '('
                val elements = mutableListOf<TypeRef>()
                if (!check(TokenType.R_PAREN)) {
                    do { elements.add(parseTypeName()) } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_PAREN, "Expected ')' in function or tuple type")
                if (match(TokenType.ARROW)) {
                    val ret = parseTypeName()
                    TypeRef.Function(elements, ret)
                } else {
                    when (elements.size) {
                        0 -> error("Empty type '()' at line ${peek().line}")
                        1 -> elements[0] // grouping
                        else -> TypeRef.Tuple(elements)
                    }
                }
            }
            check(TokenType.IDENTIFIER) -> {
                if (peekNext()?.type == TokenType.L_BRACKET && peek().lexeme in setOf("arr", "vec", "set", "map")) {
                    error("'${peek().lexeme}[...]' type syntax was removed; use Array<T>, List<T>, Set<T>, or Map<K, V> at line ${peek().line}")
                }
                val name = advance().lexeme
                if (match(TokenType.LESS)) {
                    val a = mutableListOf<TypeRef>()
                    do { a.add(parseTypeName()) } while (match(TokenType.COMMA))
                    // `Name<T...>` — the variadic pack expands into this type's args.
                    val variadic = match(TokenType.ELLIPSIS)
                    // Accept '>', or '>>' (which closes this and one enclosing generic)
                    when {
                        pendingGreater -> { pendingGreater = false }
                        check(TokenType.GREATER) -> { advance() }
                        check(TokenType.SHIFT_RIGHT) -> { advance(); pendingGreater = true }
                        else -> consume(TokenType.GREATER, "Expected '>' to close generic type arguments")
                    }
                    TypeRef.Named(name, a, variadic)
                } else {
                    TypeRef.Named(name)
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
            check(TokenType.MIXIN) -> parseMixinStmt()
            check(TokenType.UNSAFE) -> parseUnsafe()
            check(TokenType.DROP) -> parseDrop()
            check(TokenType.YIELD) -> parseYield()
            check(TokenType.TRY) -> parseTry()
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
     * that CTFE flattens). Constant strings are expanded here; `$var` interpolation
     * requires a comptime context (`inline for … with index`) and is handled by the
     * variadic-pack machinery instead.
     */
    private fun parseMixinStmt(): Stmt {
        val start = peek()
        consume(TokenType.MIXIN, "Expected 'mixin'")
        val template = parsePrimary()
        val rendered = when (template) {
            is Expr.StringLiteral -> template.value
            is Expr.StringTemplate -> {
                val sb = StringBuilder()
                for (part in template.parts) {
                    when (part) {
                        is Expr.StringTemplatePart.Literal -> sb.append(part.text)
                        is Expr.StringTemplatePart.Expr -> error(
                            "mixin '\$${(part.expr as? Expr.Identifier)?.name ?: ""}' interpolation is only valid inside an `inline for … with index` comptime context at line ${start.line}"
                        )
                    }
                }
                sb.toString()
            }
            else -> error("Expected string after 'mixin' at line ${start.line}")
        }
        val wrapper = Parser(Lexer("func __mixin() {\n$rendered\n}").tokenize()).parse()
        val body = (wrapper.items.firstOrNull() as? TopLevel.Func)?.decl?.body
            ?: error("mixin did not produce any statements at line ${start.line}")
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
    private fun parseView(): TopLevel.View {
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
        return TopLevel.View(name, params, body, start.line, start.column)
    }

    /** `hook name { body }` — a lifecycle callback. */
    private fun parseHook(): TopLevel.Hook {
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
        return TopLevel.Hook(name, body, start.line, start.column)
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
     * `use ZoneName` — import all items from a named zone.
     * `use zone ZoneName` — import all items from a zone.
     * `use std.*` — import all stdlib modules.
     * `use std.{math, concurrency}` — import grouped child modules.
     * `use ZoneName::Item` — import a specific item.
     * `use ZoneName::*` — import all items (same as bare ZoneName).
     * `use ZoneName::{Item1, Item2}` — import grouped items.
     * `use ZoneName::Item1, Item2` — import multiple items.
     * Stored as a `TopLevel.UseImport` for the SymbolCollector to resolve.
     */
    private fun parseUse(): TopLevel {
        val start = consume(TokenType.USE, "Expected 'use'")
        // `use zone std` / `use scope std` — the optional marker reads naturally and is skipped.
        if (check(TokenType.ZONE) && peekNext()?.type == TokenType.IDENTIFIER) {
            advance()
        } else if (check(TokenType.IDENTIFIER) && peek().lexeme == "scope" && peekNext()?.type == TokenType.IDENTIFIER) {
            advance()
        }
        val imports = mutableListOf<Pair<String, String?>>() // (zoneName, itemName or null for all)
        do {
            val base = StringBuilder(consume(TokenType.IDENTIFIER, "Expected zone name after 'use'").lexeme)
            var completed = false
            usePath@ while (match(TokenType.DOT)) {
                when {
                    match(TokenType.STAR) -> {
                        imports.add(base.toString() to null)
                        completed = true
                        break@usePath
                    }
                    match(TokenType.L_BRACE) -> {
                        parseUseGroup { child -> imports.add("${base}.$child" to null) }
                        completed = true
                        break@usePath
                    }
                    else -> base.append('.').append(consume(TokenType.IDENTIFIER, "Expected name after '.'").lexeme)
                }
            }
            if (completed) continue

            val zoneName = base.toString()
            if (match(TokenType.DOUBLE_COLON)) {
                when {
                    match(TokenType.STAR) -> imports.add(zoneName to null)
                    match(TokenType.L_BRACE) -> parseUseGroup { selected -> imports.add(zoneName to selected) }
                    else -> {
                        val itemName = consume(TokenType.IDENTIFIER, "Expected item name after '::'").lexeme
                        imports.add(zoneName to itemName)
                        // Allow `ZoneName::Item1, Item2` (shorthand for multiple items in same zone).
                        while (match(TokenType.COMMA) && check(TokenType.IDENTIFIER)) {
                            val more = consume(TokenType.IDENTIFIER, "Expected item name").lexeme
                            imports.add(zoneName to more)
                        }
                    }
                }
            } else {
                imports.add(zoneName to null) // bare zone/module name -> import all
            }
        } while (match(TokenType.COMMA) && check(TokenType.IDENTIFIER))
        consumeNewline()
        return TopLevel.UseImport(imports, start.line, start.column)
    }

    private fun parseUseGroup(add: (String) -> Unit) {
        if (check(TokenType.R_BRACE)) {
            error("Expected at least one name inside use group at line ${peek().line}")
        }
        do {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            add(consume(TokenType.IDENTIFIER, "Expected name in use group").lexeme)
            skipNewlines()
        } while (match(TokenType.COMMA))
        consume(TokenType.R_BRACE, "Expected '}' after use group")
    }

    private fun parseTypeAlias(): TopLevel.TypeAlias {
        val start = peek()
        consume(TokenType.TYPEALIAS, "Expected 'typealias'")
        val name = consume(TokenType.IDENTIFIER, "Expected type alias name").lexeme
        consume(TokenType.EQUAL, "Expected '=' in typealias")
        val type = parseTypeName()
        consumeNewline()
        return TopLevel.TypeAlias(name, type, start.line, start.column)
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
            else -> error("Expected '{', 'zone', 'if', 'for', 'assert', 'trace', 'fin', 'var', 'let', or identifier after 'inline' at line ${peek().line}")
        }
    }

    /** `inline for x in a..b { body }` — a compile-time unrolled loop. */
    private fun parseInlineFor(): Stmt.InlineFor {
        val start = peek()
        consume(TokenType.INLINE, "Expected 'inline'")
        consume(TokenType.FOR, "Expected 'for'")
        val name = consume(TokenType.IDENTIFIER, "Expected loop variable name").lexeme
        consume(TokenType.IN, "Expected 'in' after loop variable")
        val iterable = parseExpr()
        consume(TokenType.L_BRACE, "Expected '{' after inline for iterable")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after inline for body")
        consumeNewline()
        return Stmt.InlineFor(name, iterable, body, start.line, start.column)
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
        val condition = parseExpr()
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
            TokenType.SLASH_EQUAL, TokenType.PERCENT_EQUAL -> {
                advance()
                val op = when (opTok.type) {
                    TokenType.PLUS_EQUAL -> TokenType.PLUS
                    TokenType.MINUS_EQUAL -> TokenType.MINUS
                    TokenType.STAR_EQUAL -> TokenType.STAR
                    TokenType.SLASH_EQUAL -> TokenType.SLASH
                    TokenType.PERCENT_EQUAL -> TokenType.PERCENT
                    else -> error("unreachable compound assignment")
                }
                val value = parseExpr()
                consumeNewline()
                // Desugar `target op= value` into `target = target op value`
                val rhs = Expr.Binary(expr, op, value, start.line, start.column, start.lexeme.length)
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
                val targetType = parseTypeName()
                e = Expr.Cast(e, targetType, e.line)
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
        while (match(TokenType.PIPE)) {
            val right = parseBitwiseXor()
            left = Expr.Binary(left, TokenType.PIPE, right, left.line)
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

    private fun parseUnary(): Expr {
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
                check(TokenType.DOT) -> {
                    val dot = advance()
                    if (check(TokenType.INT_LITERAL)) {
                        val idx = (advance().literal as NumericLiteral).value as Long
                        expr = Expr.TupleAccess(expr, idx.toInt(), expr.line, expr.column)
                    } else {
                        val name = consumeIdentifierLike("Expected member name after '.'")
                        expr = Expr.Member(expr, name, expr.line, expr.column, dot.lexeme.length + name.length)
                    }
                }
                check(TokenType.QMARK_DOT) -> {
                    advance()
                    val name = consumeIdentifierLike("Expected member name after '?.'")
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
                    val index = parseExpr()
                    consume(TokenType.R_BRACKET, "Expected ']' after index")
                    expr = Expr.Index(expr, index, expr.line, expr.column)
                }
                check(TokenType.LESS) && isGenericCallAhead() -> {
                    // `f<T, U>(args)` — capture explicit type arguments for the call
                    // (e.g. `tupleOf<Int, Real>(…)`) for monomorphization; the '(' is
                    // then handled by the call case below.
                    advance() // '<'
                    val tArgs = mutableListOf<TypeRef>()
                    if (!check(TokenType.GREATER) && !check(TokenType.SHIFT_RIGHT) && !pendingGreater) {
                        do {
                            tArgs.add(parseTypeName())
                        } while (match(TokenType.COMMA))
                        match(TokenType.ELLIPSIS) // tolerate `f<T...>(…)`
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
                        is Expr.Identifier -> Expr.Call(expr.name, args, expr.line, expr.column, expr.length, pendingCallTypeArgs).also { pendingCallTypeArgs = emptyList() }
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
                TokenType.COLON, TokenType.STAR -> {}
                TokenType.LESS -> depth++
                TokenType.GREATER -> {
                    depth--
                    if (depth == 0) return tokens.getOrNull(i + 1)?.type == TokenType.L_PAREN
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

    private enum class CollectionKind { ARRAY, SET, MAP, VARIANT }

    /** Parses `arrayOf(...)` / `setOf(...)` / `mapOf(...)` / `var(...)` collection constructors.
     *  `arrayOf/setOf/var` lower to the existing literal AST nodes; `mapOf` parses `key : value` pairs. */
    private fun parseCollectionCtor(start: Token, kind: CollectionKind): Expr {
        advance() // the ctor keyword/identifier
        consume(TokenType.L_PAREN, "Expected '(' after '${start.lexeme}'")
        if (kind == CollectionKind.MAP) {
            val entries = mutableListOf<Pair<Expr, Expr>>()
            if (!check(TokenType.R_PAREN)) {
                do {
                    val key = parseExpr()
                    consume(TokenType.COLON, "Expected ':' between map key and value in map(...)")
                    entries.add(key to parseExpr())
                } while (match(TokenType.COMMA))
            }
            consume(TokenType.R_PAREN, "Expected ')' after map(...)")
            return Expr.MapLit(entries, start.line, start.column, start.lexeme.length)
        }
        val elements = mutableListOf<Expr>()
        if (!check(TokenType.R_PAREN)) {
            do { elements += parseExpr() } while (match(TokenType.COMMA))
        }
        consume(TokenType.R_PAREN, "Expected ')' after ${start.lexeme}(...)")
        return when (kind) {
            CollectionKind.ARRAY -> Expr.ArrayLiteral(elements, start.line, start.column, start.lexeme.length)
            CollectionKind.SET -> Expr.SetLiteral(elements, start.line, start.column, start.lexeme.length)
            CollectionKind.VARIANT -> Expr.VariantLit(elements, start.line, start.column, start.lexeme.length)
            CollectionKind.MAP -> error("unreachable")
        }
    }

    private fun parsePrimary(): Expr {
        val tok = peek()
        // Collection constructor ctors — contextual: an identifier `arrayOf`/`setOf`/`mapOf` is the
        // collection constructor only when immediately followed by `(` in primary position. They
        // remain valid identifiers everywhere else (e.g. as method names after `.`).
        if (tok.type == TokenType.IDENTIFIER && peekNext()?.type == TokenType.L_PAREN) {
            when (tok.lexeme) {
                "arrayOf" -> return parseCollectionCtor(tok, CollectionKind.ARRAY)
                "setOf" -> return parseCollectionCtor(tok, CollectionKind.SET)
                "mapOf" -> return parseCollectionCtor(tok, CollectionKind.MAP)
                "arr", "set", "map", "tup" ->
                    error("'${tok.lexeme}(...)' constructor syntax was removed; use arrayOf(...), setOf(...), mapOf(...), or a tuple literal '(...)' at line ${tok.line}")
            }
        }
        // `var(...)` — variant constructor (`var` is otherwise the declaration keyword).
        if (tok.type == TokenType.VAR && peekNext()?.type == TokenType.L_PAREN) {
            return parseCollectionCtor(tok, CollectionKind.VARIANT)
        }
        // `<T...>{ … }` — variadic lambda (implicit `it` is the packed array of all args).
        if (tok.type == TokenType.LESS && isVariadicLambdaAhead()) {
            advance() // '<'
            consume(TokenType.IDENTIFIER, "Expected variadic type parameter").lexeme // T
            consume(TokenType.ELLIPSIS, "Expected '...' in variadic lambda type parameter")
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
            TokenType.IDENTIFIER, TokenType.SHARED, TokenType.WEAK, TokenType.GET, TokenType.SET,
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
                if (match(TokenType.COMMA)) {
                    val elements = mutableListOf(first)
                    do { elements.add(parseExpr()) } while (match(TokenType.COMMA))
                    consume(TokenType.R_PAREN, "Expected ')' after tuple")
                    Expr.TupleLit(elements, tok.line, tok.column)
                } else {
                    consume(TokenType.R_PAREN, "Expected ')'")
                    Expr.Grouping(first, tok.line, tok.column)
                }
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
            // `<T...>{ … }` — `it` is the array of all call arguments.
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

    /** True when `<T…>{` opens a variadic lambda (vs. a less-than comparison). */
    private fun isVariadicLambdaAhead(): Boolean {
        return tokens.getOrNull(current + 1)?.type == TokenType.IDENTIFIER &&
            tokens.getOrNull(current + 2)?.type == TokenType.ELLIPSIS &&
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
