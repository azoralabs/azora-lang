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
            items.add(parseTopLevel())
        }
        return Program(packageName, items)
    }

    // -----------------------------------------------------------------------
    // Declarations
    // -----------------------------------------------------------------------

    private fun parseTopLevel(): TopLevel {
        val start = peek()
        return when {
            check(TokenType.FUNC) -> TopLevel.Func(parseFuncDecl())
            check(TokenType.INLINE) -> parseTopLevelInline()
            check(TokenType.DEEPINLINE) -> parseTopLevelDeepInline()
            check(TokenType.TEST) -> parseTestDecl()
            check(TokenType.FIN) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.FinDecl(name, type, init, start.line, start.column) }
            check(TokenType.VAR) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.VarDecl(name, type, init, start.line, start.column) }
            check(TokenType.LET) -> { advance(); val name = consume(TokenType.IDENTIFIER, "Expected name").lexeme; val type = if (match(TokenType.COLON)) parseTypeName() else null; consume(TokenType.EQUAL, "Expected '='"); val init = parseExpr(); consumeNewline(); TopLevel.LetDecl(name, type, init, start.line, start.column) }
            else -> error("Expected 'func', 'fin', 'var', 'let', 'test', 'inline', or 'deepinline' at top level, got '${peek().lexeme}' at line ${peek().line}")
        }
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

    private fun parseFuncDecl(isInline: Boolean = false): FuncDecl {
        val start = peek()
        consume(TokenType.FUNC, "Expected 'func'")
        val name = consume(TokenType.IDENTIFIER, "Expected function name").lexeme
        consume(TokenType.L_PAREN, "Expected '(' after function name")
        val params = parseParams()
        consume(TokenType.R_PAREN, "Expected ')' after parameters")
        val returnType: TypeAnnotation = if (match(TokenType.COLON)) {
            TypeAnnotation.Explicit(consume(TokenType.IDENTIFIER, "Expected return type").lexeme)
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
        return FuncDecl(name, params, returnType, body, isInline, start.line, start.column)
    }

    private fun parseParams(): List<Param> {
        if (check(TokenType.R_PAREN)) return emptyList()
        val params = mutableListOf<Param>()
        do {
            val name = consume(TokenType.IDENTIFIER, "Expected parameter name").lexeme
            consume(TokenType.COLON, "Expected ':' after parameter name")
            val type = parseTypeName()
            params.add(Param(name, type))
        } while (match(TokenType.COMMA))
        return params
    }

    private fun parseTypeName(): String {
        // Handles simple types (Int, String) and function types ((Int, Int) -> Int)
        if (check(TokenType.L_PAREN)) {
            advance() // consume '('
            val paramTypes = mutableListOf<String>()
            if (!check(TokenType.R_PAREN)) {
                do {
                    paramTypes.add(parseTypeName())
                } while (match(TokenType.COMMA))
            }
            consume(TokenType.R_PAREN, "Expected ')' in function type")
            consume(TokenType.ARROW, "Expected '->' in function type")
            val returnType = parseTypeName()
            return "(${paramTypes.joinToString(", ")}) -> $returnType"
        }
        return consume(TokenType.IDENTIFIER, "Expected type name").lexeme
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
            check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.EQUAL -> parseAssignment()
            else -> parseExprStmt()
        }
    }

    private fun parseInline(): Stmt {
        return when (peekNext()?.type) {
            TokenType.L_BRACE -> parseInlineBlock()
            TokenType.ZONE -> parseInlineZoneBlock()
            TokenType.IF -> parseInlineIf()
            TokenType.ASSERT -> parseInlineAssertStmt()
            TokenType.TRACE -> parseInlineTraceStmt()
            TokenType.FIN -> parseInlineFin()
            TokenType.VAR -> parseInlineVar()
            TokenType.LET -> parseInlineLet()
            TokenType.IDENTIFIER -> parseInlineAssignment()
            else -> error("Expected '{', 'zone', 'if', 'assert', 'trace', 'fin', 'var', 'let', or identifier after 'inline' at line ${peek().line}")
        }
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

    private fun parseAssignment(): Stmt.Assignment {
        val start = peek()
        val name = consume(TokenType.IDENTIFIER, "Expected variable name").lexeme
        consume(TokenType.EQUAL, "Expected '='")
        val value = parseExpr()
        consumeNewline()
        return Stmt.Assignment(name, value, start.line, start.column)
    }

    private fun parseReturn(): Stmt.Return {
        val start = peek()
        consume(TokenType.RETURN, "Expected 'return'")
        val value = if (check(TokenType.NEWLINE) || check(TokenType.R_BRACE) || isAtEnd()) null
                    else parseExpr()
        consumeNewline()
        return Stmt.Return(value, start.line, start.column)
    }

    private fun parseExprStmt(): Stmt.ExprStmt {
        val start = peek()
        val expr = parseExpr()
        consumeNewline()
        return Stmt.ExprStmt(expr, start.line, start.column)
    }

    // -----------------------------------------------------------------------
    // Expressions (precedence climbing)
    // -----------------------------------------------------------------------

    private fun parseExpr(): Expr = parseOr()

    private fun parseOr(): Expr {
        var left = parseAnd()
        while (match(TokenType.OR_OR)) {
            val right = parseAnd()
            left = Expr.Binary(left, TokenType.OR_OR, right, left.line)
        }
        return left
    }

    private fun parseAnd(): Expr {
        var left = parseEquality()
        while (match(TokenType.AND_AND)) {
            val right = parseEquality()
            left = Expr.Binary(left, TokenType.AND_AND, right, left.line)
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
        var left = parseAddition()
        while (check(TokenType.LESS) || check(TokenType.LESS_EQUAL) ||
               check(TokenType.GREATER) || check(TokenType.GREATER_EQUAL)
        ) {
            val op = advance().type
            val right = parseAddition()
            left = Expr.Binary(left, op, right, left.line)
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
        var left = parseUnary()
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
            val op = advance().type
            val right = parseUnary()
            left = Expr.Binary(left, op, right, left.line)
        }
        return left
    }

    private fun parseUnary(): Expr {
        if (check(TokenType.BANG) || check(TokenType.MINUS)) {
            val op = advance()
            val operand = parseUnary()
            return Expr.Unary(op.type, operand, op.line, op.column, op.lexeme.length)
        }
        return parseCall()
    }

    private fun parseCall(): Expr {
        if (check(TokenType.IDENTIFIER) && peekNext()?.type == TokenType.L_PAREN) {
            val name = advance()
            advance() // consume '('
            val args = mutableListOf<Expr>()
            if (!check(TokenType.R_PAREN)) {
                do { args.add(parseExpr()) } while (match(TokenType.COMMA))
            }
            consume(TokenType.R_PAREN, "Expected ')' after arguments")
            return Expr.Call(name.lexeme, args, name.line, name.column, name.lexeme.length)
        }
        return parsePrimary()
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
            TokenType.CHAR_LITERAL -> { advance(); Expr.CharLiteral(tok.literal as Char, tok.line, tok.column, tok.lexeme.length) }
            TokenType.TRUE -> { advance(); Expr.BoolLiteral(true, tok.line, tok.column, tok.lexeme.length) }
            TokenType.FALSE -> { advance(); Expr.BoolLiteral(false, tok.line, tok.column, tok.lexeme.length) }
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
                val expr = parseExpr()
                consume(TokenType.R_PAREN, "Expected ')'")
                Expr.Grouping(expr, tok.line, tok.column)
            }
            else -> error("Unexpected token '${tok.lexeme}' at line ${tok.line}")
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
