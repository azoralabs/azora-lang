/*
 * Copyright 2026 AzoraTech
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

    private var current = 0

    /** When `>>` (SHIFT_RIGHT) is split for nested generics, this flag signals a pending `>` for the enclosing type. */
    private var pendingGreater = false

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
        val packageName = if (check(TokenType.PACKAGE)) parsePackage() else null
        val items = mutableListOf<TopLevel>()
        while (!isAtEnd()) {
            skipNewlines()
            if (isAtEnd()) break
            if (check(TokenType.ZONE) && peekNext()?.type == TokenType.IDENTIFIER) {
                // `zone Name { … }` — a named zone is a namespace; it desugars to
                // mangled top-level items (`Name__member`). Anonymous `zone { … }`
                // (no name) keeps its block-scope meaning inside function bodies.
                items.addAll(parseNamedZone())
            } else {
                items.add(parseTopLevel())
            }
        }
        return Program(packageName, items)
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

    // -----------------------------------------------------------------------
    // Declarations
    // -----------------------------------------------------------------------

    private fun parseTopLevel(): TopLevel {
        val annotations = parseAnnotations()
        val start = peek()
        return when {
            check(TokenType.FUNC) -> TopLevel.Func(parseFuncDecl(annotations = annotations))
            check(TokenType.FLOW) -> TopLevel.Func(parseFuncDecl(annotations = annotations, isFlow = true))
            check(TokenType.INLINE) -> parseTopLevelInline()
            check(TokenType.DEEPINLINE) -> parseTopLevelDeepInline()
            check(TokenType.TEST) -> parseTestDecl()
            check(TokenType.DECO) -> parseDeco()
            check(TokenType.PACK) -> parsePack(annotations)
            check(TokenType.ENUM) -> parseEnumDecl()
            check(TokenType.FAIL) -> parseFailDecl()
            check(TokenType.IMPL) -> parseImpl()
            check(TokenType.INFX) -> parseInfx()
            check(TokenType.SPEC) -> parseSpec()
            check(TokenType.SLOT) -> parseSlot()
            check(TokenType.TYPEALIAS) -> parseTypeAlias()
            check(TokenType.USE) -> parseUse()
            check(TokenType.FIN) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.FinDecl(name, type, init, start.line, start.column, annotations) }
            check(TokenType.VAR) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.VarDecl(name, type, init, start.line, start.column, annotations) }
            check(TokenType.LET) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.LetDecl(name, type, init, start.line, start.column, annotations) }
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
            val args = if (match(TokenType.L_PAREN)) {
                val a = mutableListOf<Expr>()
                if (!check(TokenType.R_PAREN)) {
                    do { a.add(parseExpr()) } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_PAREN, "Expected ')' after annotation arguments")
                a
            } else emptyList()
            result.add(Annotation(name, args, target, at.line, at.column))
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
    private fun parsePack(annotations: List<Annotation> = emptyList()): TopLevel.Pack {
        val start = peek()
        consume(TokenType.PACK, "Expected 'pack'")
        val name = consume(TokenType.IDENTIFIER, "Expected pack name").lexeme
        val typeParams = parseTypeParams()
        consume(TokenType.L_BRACE, "Expected '{' after pack name")
        skipNewlines()
        val fields = mutableListOf<PackField>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            fields.add(parsePackField())
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after pack fields")
        consumeNewline()
        return TopLevel.Pack(name, fields, typeParams, start.line, start.column, annotations)
    }

    private fun parsePackField(): PackField {
        val mutable = when {
            check(TokenType.VAR) -> { advance(); true }
            check(TokenType.FIN) -> { advance(); false }
            check(TokenType.LET) -> { advance(); false }
            else -> false
        }
        val name = consume(TokenType.IDENTIFIER, "Expected field name").lexeme
        consume(TokenType.COLON, "Expected ':' after pack field name")
        val type = parseTypeName()
        val default = if (match(TokenType.EQUAL)) parseExpr() else null
        consumeNewline()
        return PackField(name, type, mutable, default)
    }

    /** `enum Name { Var1; Var2; ... }` — variants one per line. */
    private fun parseEnumDecl(): TopLevel.Enum {
        val start = peek()
        consume(TokenType.ENUM, "Expected 'enum'")
        val name = consume(TokenType.IDENTIFIER, "Expected enum name").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after enum name")
        skipNewlines()
        val variants = mutableListOf<String>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            variants.add(consume(TokenType.IDENTIFIER, "Expected variant name").lexeme)
            consumeNewline()
        }
        consume(TokenType.R_BRACE, "Expected '}' after enum variants")
        consumeNewline()
        return TopLevel.Enum(name, variants, start.line, start.column)
    }

    /** `fail ErrSet { V1, V2 }` — an error-set declaration (variants one per line, like enum). */
    private fun parseFailDecl(): TopLevel.Fail {
        val start = peek()
        consume(TokenType.FAIL, "Expected 'fail'")
        val name = consume(TokenType.IDENTIFIER, "Expected error-set name").lexeme
        consume(TokenType.L_BRACE, "Expected '{' after error-set name")
        skipNewlines()
        val variants = mutableListOf<String>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            variants.add(consume(TokenType.IDENTIFIER, "Expected variant name").lexeme)
            consumeNewline()
        }
        consume(TokenType.R_BRACE, "Expected '}' after error-set variants")
        consumeNewline()
        return TopLevel.Fail(name, variants, start.line, start.column)
    }

    /** `impl Type { methods }` or `impl Trait for Type { methods }`. */
    private fun parseImpl(): TopLevel.Impl {
        val start = peek()
        consume(TokenType.IMPL, "Expected 'impl'")
        val first = consume(TokenType.IDENTIFIER, "Expected type or trait name after 'impl'").lexeme
        var typeName = first
        var traitName: String? = null
        if (match(TokenType.FOR)) {
            traitName = first
            typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'for'").lexeme
        }
        consume(TokenType.L_BRACE, "Expected '{' after impl type")
        skipNewlines()
        val methods = mutableListOf<FuncDecl>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            skipNewlines()
            if (check(TokenType.R_BRACE)) break
            val methodStart = peek()
            val isInline = match(TokenType.INLINE)
            if (check(TokenType.OPER)) {
                methods.add(parseOperMethod(methodStart))
            } else {
                methods.add(parseFuncDecl(isInline))
            }
            skipNewlines()
        }
        consume(TokenType.R_BRACE, "Expected '}' after impl methods")
        consumeNewline()
        return TopLevel.Impl(typeName, methods, traitName, start.line, start.column)
    }

    /**
     * `oper[] (i: T): R { … }` or `oper[]= (i: T, v: R) { … }` — index-operator
     * overloads inside an impl. Registered as the methods `index` / `indexSet`, so
     * `target[i]` / `target[i] = v` resolve to `Type_index(self, i)` / `Type_indexSet(self, i, v)`.
     */
    private fun parseOperMethod(start: Token): FuncDecl {
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
        return FuncDecl(name, params, returnType, body, false, emptyList(), start.line, start.column)
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
                    do { types.add(parseTypeName()) } while (match(TokenType.COMMA))
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
            patterns.add(parseExpr())
            while (match(TokenType.COMMA)) patterns.add(parseExpr())
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
        val name = consume(TokenType.IDENTIFIER, "Expected package name").lexeme
        consumeNewline()
        return name
    }

    private fun parseFuncDecl(isInline: Boolean = false, annotations: List<Annotation> = emptyList(), isFlow: Boolean = false): FuncDecl {
        val start = peek()
        consume(if (isFlow) TokenType.FLOW else TokenType.FUNC, "Expected '${if (isFlow) "flow" else "func"}'")
        val typeParams = parseTypeParams()
        val name = consume(TokenType.IDENTIFIER, "Expected function name").lexeme
        consume(TokenType.L_PAREN, "Expected '(' after function name")
        val params = parseParams()
        consume(TokenType.R_PAREN, "Expected ')' after parameters")
        val returnType: TypeAnnotation = if (match(TokenType.COLON)) {
            TypeAnnotation.Explicit(parseTypeName())
        } else {
            TypeAnnotation.Inferred
        }

        val body: List<Stmt>
        if (match(TokenType.EQUAL)) {
            // func main() = inline { ... } or func main() = deepinline { ... }
            val blockStmt = when {
                check(TokenType.INLINE) -> parseInlineBlock()
                check(TokenType.DEEPINLINE) -> parseDeepInlineBlock()
                else -> error("Expected 'inline' or 'deepinline' after '=' at line ${peek().line}")
            }
            body = listOf(blockStmt)
        } else {
            consume(TokenType.L_BRACE, "Expected '{' before function body")
            skipNewlines()
            val stmts = mutableListOf<Stmt>()
            while (!check(TokenType.R_BRACE) && !isAtEnd()) {
                stmts.add(parseStmt())
                skipNewlines()
            }
            consume(TokenType.R_BRACE, "Expected '}' after function body")
            consumeNewline()
            body = stmts
        }
        return FuncDecl(name, params, returnType, body, isInline, typeParams, start.line, start.column, start.lexeme.length, annotations, isFlow)
    }

    private fun parseParams(): List<Param> {
        if (check(TokenType.R_PAREN)) return emptyList()
        val params = mutableListOf<Param>()
        do {
            val name = consume(TokenType.IDENTIFIER, "Expected parameter name").lexeme
            consume(TokenType.COLON, "Expected ':' after parameter name")
            val type = parseTypeName()
            val default = if (match(TokenType.EQUAL)) parseExpr() else null
            params.add(Param(name, type, default))
        } while (match(TokenType.COMMA))
        return params
    }

    /** `<T, U>` type-parameter list (returns empty list if none). */
    private fun parseTypeParams(): List<String> {
        if (!check(TokenType.LESS)) return emptyList()
        advance()
        val params = mutableListOf<String>()
        do { params.add(consume(TokenType.IDENTIFIER, "Expected type parameter name").lexeme) } while (match(TokenType.COMMA))
        consume(TokenType.GREATER, "Expected '>' after type parameters")
        return params
    }

    private fun parseTypeName(): TypeRef {
        var base = parseTypeAtom()
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
     * Supports: `[T]` (array), `[K: V]` (map), `![T]` (set),
     * `(A, B) -> R` (function), `(A, B)` (tuple), `(A)` (grouping),
     * `Name` and `Name<A, B>` (generic).
     */
    private fun parseTypeAtom(): TypeRef {
        return when {
            check(TokenType.L_BRACKET) -> {
                advance() // consume '['
                val first = parseTypeName()
                if (match(TokenType.COLON)) {
                    val value = parseTypeName()
                    consume(TokenType.R_BRACKET, "Expected ']' in map type")
                    TypeRef.Map(first, value)
                } else {
                    consume(TokenType.R_BRACKET, "Expected ']' in array type")
                    TypeRef.Array(first)
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
                val name = advance().lexeme
                val args = if (match(TokenType.LESS)) {
                    val a = mutableListOf<TypeRef>()
                    do { a.add(parseTypeName()) } while (match(TokenType.COMMA))
                    // Accept '>', or '>>' (which closes this and one enclosing generic)
                    when {
                        pendingGreater -> { pendingGreater = false }
                        check(TokenType.GREATER) -> { advance() }
                        check(TokenType.SHIFT_RIGHT) -> { advance(); pendingGreater = true }
                        else -> consume(TokenType.GREATER, "Expected '>' to close generic type arguments")
                    }
                    a
                } else emptyList()
                TypeRef.Named(name, args)
            }
            else -> error("Expected type name at line ${peek().line}, got '${peek().lexeme}'")
        }
    }

    // -----------------------------------------------------------------------
    // Statements
    // -----------------------------------------------------------------------

    private fun parseStmt(): Stmt {
        return when {
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
            check(TokenType.FAIL) && peekNext()?.type == TokenType.DEFER -> parseFailDefer()
            check(TokenType.FAIL) -> parseFailThrow()
            check(TokenType.UNSAFE) -> parseUnsafe()
            check(TokenType.DROP) -> parseDrop()
            check(TokenType.YIELD) -> parseYield()
            check(TokenType.TRY) -> parseTry()
            check(TokenType.DEFER) -> parseDefer()
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
        val condition = parseExpr()
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
        consume(TokenType.L_BRACE, "Expected '{' after 'loop'")
        skipNewlines()
        val body = parseBlock().toMutableList()
        consume(TokenType.R_BRACE, "Expected '}' after loop body")
        // do-while: `loop { body } while cond` — runs the body first, then repeats
        // while [cond] holds. Desugared to `loop { body; if (!cond) { break } }`.
        if (match(TokenType.WHILE)) {
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

    /** `fail <expr>` — sugar for `throw <expr>` (raise an error from a `T!E` function). */
    private fun parseFailThrow(): Stmt.Throw {
        val start = peek()
        consume(TokenType.FAIL, "Expected 'fail'")
        val value = parseExpr()
        consumeNewline()
        return Stmt.Throw(value, start.line, start.column)
    }

    /** `unsafe { body }` — an opt-in block; desugars to a plain `zone { body }`. */
    private fun parseUnsafe(): Stmt.Zone {
        val start = peek()
        consume(TokenType.UNSAFE, "Expected 'unsafe'")
        consume(TokenType.L_BRACE, "Expected '{' after 'unsafe'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}' after unsafe body")
        consumeNewline()
        return Stmt.Zone(body, start.line, start.column)
    }

    /** `drop <expr>` — release a heap value. Desugars to evaluating [expr] (advisory free under GC). */
    private fun parseDrop(): Stmt {
        val start = peek()
        consume(TokenType.DROP, "Expected 'drop'")
        val value = parseExpr()
        consumeNewline()
        return Stmt.ExprStmt(value, start.line, start.column)
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

    /** `use module.path`, `use scope std`, `use pages.*` — parsed and ignored (metadata for CLI resolution). */
    private fun parseUse(): TopLevel {
        val start = peek()
        consume(TokenType.USE, "Expected 'use'")
        while (!check(TokenType.NEWLINE) && !check(TokenType.EOF)) {
            advance()
        }
        consumeNewline()
        return TopLevel.Test("__use__", emptyList(), start.line, start.column)
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
        consume(TokenType.L_BRACE, "Expected '{' after 'zone'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.Zone(body, start.line, start.column)
    }

    private fun parseFriendZone(): Stmt.FriendZone {
        val start = peek()
        consume(TokenType.FRIEND, "Expected 'friend'")
        consume(TokenType.ZONE, "Expected 'zone' after 'friend'")
        consume(TokenType.L_BRACE, "Expected '{' after 'friend zone'")
        skipNewlines()
        val body = parseBlock()
        consume(TokenType.R_BRACE, "Expected '}'")
        consumeNewline()
        return Stmt.FriendZone(body, start.line, start.column)
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
        val name = consume(TokenType.IDENTIFIER, "Expected variable name").lexeme
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
        val name = consume(TokenType.IDENTIFIER, "Expected variable name").lexeme
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
        val name = consume(TokenType.IDENTIFIER, "Expected variable name").lexeme
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
        consume(TokenType.L_BRACE, "Expected '{' after assert condition")
        skipNewlines()
        val message = parseExpr()
        skipNewlines()
        consume(TokenType.R_BRACE, "Expected '}' after assert message")
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
        val condition = parseExpr()
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
        val name = consume(TokenType.IDENTIFIER, "Expected variable name").lexeme
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in declaration")
        val init = parseExpr()
        consumeNewline()
        return Stmt.VarDecl(name, type, init, start.line, start.column)
    }

    private fun parseFinDecl(): Stmt.FinDecl {
        val start = peek()
        advance() // consume 'fin'
        val name = consume(TokenType.IDENTIFIER, "Expected variable name").lexeme
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in declaration")
        val init = parseExpr()
        consumeNewline()
        return Stmt.FinDecl(name, type, init, start.line, start.column)
    }

    private fun parseLetDecl(): Stmt.LetDecl {
        val start = peek()
        advance() // consume 'let'
        val name = consume(TokenType.IDENTIFIER, "Expected variable name").lexeme
        val type: TypeAnnotation = if (match(TokenType.COLON)) TypeAnnotation.Explicit(parseTypeName()) else TypeAnnotation.Inferred
        consume(TokenType.EQUAL, "Expected '=' in let declaration")
        val init = parseExpr()
        consumeNewline()
        return Stmt.LetDecl(name, type, init, start.line, start.column)
    }

    private fun parseReturn(): Stmt.Return {
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
                val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'as'").lexeme
                e = Expr.Cast(e, TypeRef.Named(typeName), e.line)
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
        // `alloc <expr>` — heap allocation.
        if (check(TokenType.ALLOC)) {
            val at = advance()
            return Expr.Alloc(parseUnary(), at.line, at.column, at.lexeme.length)
        }
        // `isolated(expr)` — explicit deep copy.
        if (check(TokenType.ISOLATED)) {
            val at = advance()
            consume(TokenType.L_PAREN, "Expected '(' after 'isolated'")
            val value = parseExpr()
            consume(TokenType.R_PAREN, "Expected ')' after 'isolated(...'")
            return Expr.Isolated(value, at.line, at.column, at.lexeme.length)
        }
        // `*ptr` — pointer dereference.
        if (check(TokenType.STAR)) {
            val at = advance()
            return Expr.Deref(parseUnary(), at.line, at.column, at.lexeme.length)
        }
        // `await task` — suspend until the task completes.
        if (check(TokenType.AWAIT)) {
            val at = advance()
            return Expr.Await(parseUnary(), at.line, at.column, at.lexeme.length)
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
    private fun parsePostfix(): Expr {
        var expr = parsePrimary()
        while (true) {
            when {
                check(TokenType.DOT) -> {
                    val dot = advance()
                    if (check(TokenType.INT_LITERAL)) {
                        val idx = (advance().literal as NumericLiteral).value as Long
                        expr = Expr.TupleAccess(expr, idx.toInt(), expr.line, expr.column)
                    } else {
                        val name = consume(TokenType.IDENTIFIER, "Expected member name after '.'").lexeme
                        expr = Expr.Member(expr, name, expr.line, expr.column, dot.lexeme.length + name.length)
                    }
                }
                check(TokenType.QMARK_DOT) -> {
                    advance()
                    val name = consume(TokenType.IDENTIFIER, "Expected member name after '?.'").lexeme
                    expr = Expr.SafeMember(expr, name, expr.line, expr.column)
                }
                check(TokenType.DOUBLE_COLON) -> {
                    // Namespace member access `Name::member` → mangled identifier `Name__member`.
                    advance() // '::'
                    val member = consume(TokenType.IDENTIFIER, "Expected member name after '::'").lexeme
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
                check(TokenType.L_PAREN) -> {
                    advance()
                    val args = mutableListOf<Expr>()
                    if (!check(TokenType.R_PAREN)) {
                        do {
                            val first = parseExpr()
                            val arg = if (first is Expr.Identifier && check(TokenType.COLON)) {
                                advance()
                                Expr.NamedArg(first.name, parseExpr(), first.line, first.column, first.length)
                            } else first
                            args.add(arg)
                        } while (match(TokenType.COMMA))
                    }
                    consume(TokenType.R_PAREN, "Expected ')' after arguments")
                    // Trailing lambda: f(args) { x -> ... }
                    if (allowTrailingLambda && check(TokenType.L_BRACE)) {
                        val lb = peek()
                        args.add(parseLambda(lb.line, lb.column))
                    }
                    expr = when (expr) {
                        is Expr.Identifier -> Expr.Call(expr.name, args, expr.line, expr.column, expr.length)
                        is Expr.Member -> Expr.MethodCall(expr.target, expr.name, args, expr.line, expr.column)
                        else -> error("Invalid call target at line ${peek().line}")
                    }
                }
                else -> return expr
            }
        }
    }

    private fun parsePrimary(): Expr {
        val tok = peek()
        return when (tok.type) {
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
            TokenType.IDENTIFIER -> { advance(); Expr.Identifier(tok.lexeme, tok.line, tok.column, tok.lexeme.length) }
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
    private fun parseLambda(line: Int, column: Int): Expr.Lambda {
        consume(TokenType.L_BRACE, "Expected '{'")
        skipNewlines()
        val params = mutableListOf<Param>()
        // Detect typed params: IDENT COLON type (, ...)? ->
        val hasTypedParams = check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.COLON
        if (hasTypedParams) {
            do {
                val name = consume(TokenType.IDENTIFIER, "Expected lambda parameter name").lexeme
                consume(TokenType.COLON, "Expected ':' after lambda parameter name")
                val type = parseTypeName()
                params.add(Param(name, type))
            } while (match(TokenType.COMMA))
            consume(TokenType.ARROW, "Expected '->' in lambda")
        } else if (!check(TokenType.ARROW)) {
            // No params, no arrow → implicit `it`
            params.add(Param("it", TypeRef.Named("Any")))
        } else {
            consume(TokenType.ARROW, "Expected '->' in lambda")
        }
        skipNewlines()
        val body = parseBlock().toMutableList()
        if (body.isNotEmpty() && body.last() is Stmt.ExprStmt) {
            val last = body.removeAt(body.size - 1) as Stmt.ExprStmt
            body.add(Stmt.Return(last.expr, last.line, last.column, last.length))
        }
        consume(TokenType.R_BRACE, "Expected '}' after lambda body")
        return Expr.Lambda(params, body, line, column)
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
