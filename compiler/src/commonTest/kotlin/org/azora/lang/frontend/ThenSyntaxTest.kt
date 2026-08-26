/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.azora.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Parser coverage for brace-free control-flow bodies and for-expression breaks. */
class ThenSyntaxTest {

    private fun function(source: String): TopLevel.Func =
        Parser(Lexer("func f(): Int {\n$source\n}\n").tokenize()).parse()
            .items.filterIsInstance<TopLevel.Func>().single()

    @Test
    fun `then accepts one statement for every runtime loop head`() {
        val body = Parser(
            Lexer(
                """
                func f() {
                    if ready then return
                    while ready then break
                    for i in 0..<3 then continue
                    loop then break
                }
                """.trimIndent(),
            ).tokenize(),
        ).parse().items.filterIsInstance<TopLevel.Func>().single().decl.body

        assertIs<Stmt.If>(body[0]).also { assertIs<Stmt.Return>(it.thenBranch.single()) }
        assertIs<Stmt.While>(body[1]).also { assertIs<Stmt.Break>(it.body.single()) }
        assertIs<Stmt.For>(body[2]).also { assertIs<Stmt.Continue>(it.body.single()) }
        assertIs<Stmt.Loop>(body[3]).also { assertIs<Stmt.Break>(it.body.single()) }
    }

    @Test
    fun `then and else may both be single statements`() {
        val stmt = assertIs<Stmt.If>(
            function("if ready then result = 1 else result = 2").decl.body.single(),
        )
        assertIs<Stmt.Assignment>(stmt.thenBranch.single())
        assertIs<Stmt.Assignment>(stmt.elseBranch!!.single())
    }

    @Test
    fun `if expressions accept then`() {
        val declaration = assertIs<Stmt.FinDecl>(
            function("fin result: Int = if ready then 1 else 2").decl.body.single(),
        )
        assertIs<Expr.IfExpr>(declaration.initializer)
    }

    @Test
    fun `assert accepts panic message`() {
        val assertion = assertIs<Stmt.Assert>(
            function("assert it >= 0 panic \"count must return non-negative\"").decl.body.single(),
        )
        assertIs<Expr.StringLiteral>(assertion.message).also {
            assertEquals("count must return non-negative", it.value)
        }
    }

    @Test
    fun `inline assert accepts panic message`() {
        val assertion = assertIs<Stmt.InlineAssert>(
            function("inline assert ready panic \"not ready\"").decl.body.single(),
        )
        assertIs<Expr.StringLiteral>(assertion.message).also { assertEquals("not ready", it.value) }
    }

    @Test
    fun `member operation groups preserve order and expand nested brace arguments`() {
        val scope = assertIs<Stmt.Scope>(
            function("value.{enqueue({1, 2}), clear(), enqueue(8)}").decl.body.single(),
        )
        assertEquals(4, scope.body.size)
        val first = assertIs<Stmt.ExprStmt>(scope.body[0]).expr
        val second = assertIs<Stmt.ExprStmt>(scope.body[1]).expr
        val third = assertIs<Stmt.ExprStmt>(scope.body[2]).expr
        val fourth = assertIs<Stmt.ExprStmt>(scope.body[3]).expr
        assertIs<Expr.MethodCall>(first).also {
            assertEquals("enqueue", it.name)
            assertEquals("value", (it.target as Expr.Identifier).name)
            assertEquals(1L, (it.args.single() as Expr.IntLiteral).value)
        }
        assertIs<Expr.MethodCall>(second).also { assertEquals(2L, (it.args.single() as Expr.IntLiteral).value) }
        assertIs<Expr.MethodCall>(third).also {
            assertEquals("clear", it.name)
            assertEquals("value", (it.target as Expr.Identifier).name)
        }
        assertIs<Expr.MethodCall>(fourth).also { assertEquals(8L, (it.args.single() as Expr.IntLiteral).value) }
    }

    @Test
    fun `operation groups can nest`() {
        val scope = assertIs<Stmt.Scope>(
            function("value.{value.{enqueue({1, 2})}, clear()}").decl.body.single(),
        )
        assertEquals(3, scope.body.size)
    }

    @Test
    fun `operation group allows newline separators`() {
        val scope = assertIs<Stmt.Scope>(
            function(
                """
                value.{
                    enqueue(1)
                    clear()
                    enqueue(8)
                }
                """.trimIndent(),
            ).decl.body.single(),
        )
        assertEquals(3, scope.body.size)
    }

    @Test
    fun `complex sequence receiver is evaluated through one temporary`() {
        val scope = assertIs<Stmt.Scope>(
            function("make().{clear(), enqueue(1)}").decl.body.single(),
        )
        assertIs<Stmt.LetDecl>(scope.body.first())
        assertEquals(2, scope.body.drop(1).size)
    }

    @Test
    fun `contract scope accepts one unbraced grouped assignment`() {
        val implementation = Parser(
            Lexer(
                """
                impl Counter {
                    func !.reset() scope self.{offset, allocCount} = 0
                }
                """.trimIndent(),
            ).tokenize(),
        ).parse().items.filterIsInstance<TopLevel.Impl>().single()
        val declaration = implementation.methods.single()
        val body = assertIs<Stmt.Scope>(declaration.body.single()).body
        assertEquals(2, body.size)
        assertTrue(body.all { it is Stmt.MemberAssign })
    }

    @Test
    fun `contracted function accepts expression body after clauses`() {
        val declaration = Parser(
            Lexer(
                """
                func strSlice(s: String, start: Int, end: Int): String
                in {
                    assert start >= 0 panic "Start must be non-negative"
                    assert end >= start panic "End must be >= start"
                } = substring(s, start, end)
                """.trimIndent(),
            ).tokenize(),
        ).parse().items.filterIsInstance<TopLevel.Func>().single().decl
        assertEquals(3, declaration.body.size)
        assertIs<Stmt.Assert>(declaration.body[0])
        assertIs<Stmt.Assert>(declaration.body[1])
        assertIs<Stmt.Return>(declaration.body[2]).also { assertIs<Expr.Call>(it.value) }
    }

    @Test
    fun `expression body follows both in and out contracts`() {
        val declaration = Parser(
            Lexer(
                """
                func strSlice(s: String, start: Int, end: Int): String
                in {
                    assert start >= 0 panic "Start must be non-negative"
                } out {
                    assert it != "" panic "slice must not be empty"
                } = substring(s, start, end)
                """.trimIndent(),
            ).tokenize(),
        ).parse().items.filterIsInstance<TopLevel.Func>().single().decl
        assertTrue(declaration.body.any { it is Stmt.Assert })
        assertTrue(declaration.body.any { it is Stmt.Scope })
    }

    @Test
    fun `for expressions use break value rather than a bare tail expression`() {
        val result = function("return for i in 0..<3 then if i == 1 then break i else continue else { -1 }")
        val prelude = result.decl.body.dropLast(1)
        assertTrue(prelude.any { it is Stmt.VarDecl && it.name.startsWith("__for_value_") })
        val loop = prelude.filterIsInstance<Stmt.For>().single()
        val branch = assertIs<Stmt.If>(loop.body.single())
        val lowered = assertIs<Stmt.Scope>(branch.thenBranch.single())
        assertIs<Stmt.Assignment>(lowered.body[0])
        assertEquals(null, assertIs<Stmt.Break>(lowered.body[1]).value)
    }

    @Test
    fun `runtime range for exposes ordinal with index`() {
        val loop = assertIs<Stmt.For>(
            function("for i: Int in 0..<arr.size by 2 with index then consume(i, index)").decl.body.single(),
        )
        assertEquals("index", loop.indexName)
        assertEquals("i", loop.name)
        assertIs<Stmt.ExprStmt>(loop.body.single())
    }

    @Test
    fun `for expressions accept compact else value`() {
        val result = function("return for i in 0..<3 then break i else -1")
        assertTrue(result.decl.body.any { it is Stmt.VarDecl && it.name.startsWith("__for_value_") })
    }

    @Test
    fun `increment and decrement retain prefix and postfix position`() {
        val body = function("var x = 1\nfin before = ++x\nfin after = x++\nfin down = --x\nfin tail = x--").decl.body
        assertIs<Expr.IncDec>(assertIs<Stmt.FinDecl>(body[1]).initializer).also { assertTrue(it.prefix) }
        assertIs<Expr.IncDec>(assertIs<Stmt.FinDecl>(body[2]).initializer).also { assertTrue(!it.prefix) }
        assertIs<Expr.IncDec>(assertIs<Stmt.FinDecl>(body[3]).initializer).also { assertTrue(it.prefix) }
        assertIs<Expr.IncDec>(assertIs<Stmt.FinDecl>(body[4]).initializer).also { assertTrue(!it.prefix) }
    }

    @Test
    fun `generic functions accept expression bodies`() {
        val function = Parser(
            Lexer("func<T> identity(x: T): T = x\n").tokenize(),
        ).parse().items.filterIsInstance<TopLevel.Func>().single().decl
        assertEquals(listOf("T"), function.typeParams)
        assertIs<Stmt.Return>(function.body.single()).also { returned ->
            assertIs<Expr.Identifier>(returned.value)
            assertEquals("x", (returned.value as Expr.Identifier).name)
        }
    }

    @Test
    fun `conditionless when uses the branch brace rather than a lambda`() {
        assertIs<Stmt.When>(
            function("return when { ready -> 1 else -> 2 }").decl.body.single(),
        )
    }

    @Test
    fun groupedBindingsFanOutOrdinaryCalls() {
        val body = function("var {left, right}: Int = mergeSort{(left), (right)}").decl.body
        val first = assertIs<Stmt.VarDecl>(body[0])
        val second = assertIs<Stmt.VarDecl>(body[1])
        assertEquals("left", first.name)
        assertEquals("right", second.name)
        assertEquals(listOf("left"), assertIs<Expr.Call>(first.initializer).args.map { (it as Expr.Identifier).name })
        assertEquals(listOf("right"), assertIs<Expr.Call>(second.initializer).args.map { (it as Expr.Identifier).name })
    }

    @Test
    fun groupedMemberAccessAndTupleTypeAnnotatePositions() {
        val body = function("fin {base, name}: {Path, String} = self.{parent, stem}").decl.body
        val base = assertIs<Stmt.FinDecl>(body[0])
        val name = assertIs<Stmt.FinDecl>(body[1])
        assertEquals("Path", (base.type as TypeAnnotation.Explicit).ref.toString())
        assertEquals("String", (name.type as TypeAnnotation.Explicit).ref.toString())
        assertEquals("parent", assertIs<Expr.Member>(base.initializer).name)
        assertEquals("stem", assertIs<Expr.Member>(name.initializer).name)
    }

    @Test
    fun groupedReceiverPostfixesBroadcastInsideForThen() {
        val body = function("for i: Int in 0..<maxLen then if i < {a, b}.size then result[ri++] = {a, b}[i]").decl.body
        val loop = assertIs<Stmt.For>(body.single())
        assertEquals(2, loop.body.size)
        loop.body.forEachIndexed { index, raw ->
            val branch = assertIs<Stmt.If>(raw)
            val right = assertIs<Expr.Member>(assertIs<Expr.Binary>(branch.condition).right)
            assertEquals(if (index == 0) "a" else "b", assertIs<Expr.Identifier>(right.target).name)
            val assignment = assertIs<Stmt.IndexAssign>(branch.thenBranch.single())
            val value = assertIs<Expr.Index>(assignment.value)
            assertEquals(if (index == 0) "a" else "b", assertIs<Expr.Identifier>(value.target).name)
        }
    }

    @Test
    fun filesystemGroupedBindingsParse() {
        val body = function("fin {name, dot}: {String, Int} = {self.fileName, _lastDot(name)}").decl.body
        assertEquals(2, body.size)
    }
}
