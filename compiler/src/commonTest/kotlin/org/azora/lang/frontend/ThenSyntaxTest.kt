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
    fun `contract scope accepts a block of explicit assignments`() {
        val implementation = Parser(
            Lexer(
                """
                impl Counter {
                    func !.reset() scope {
                        self.offset = 0
                        self.allocCount = 0
                    }
                }
                """.trimIndent(),
            ).tokenize(),
        ).parse().items.filterIsInstance<TopLevel.Impl>().single()
        val declaration = implementation.methods.single()
        val body = declaration.body
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
    fun explicitBindingsCallIndependently() {
        val body = function("var left: Int = mergeSort(left)\nvar right: Int = mergeSort(right)").decl.body
        val first = assertIs<Stmt.VarDecl>(body[0])
        val second = assertIs<Stmt.VarDecl>(body[1])
        assertEquals("left", first.name)
        assertEquals("right", second.name)
        assertEquals(listOf("left"), assertIs<Expr.Call>(first.initializer).args.map { (it as Expr.Identifier).name })
        assertEquals(listOf("right"), assertIs<Expr.Call>(second.initializer).args.map { (it as Expr.Identifier).name })
    }

    @Test
    fun explicitMemberBindingsRetainTheirTypes() {
        val body = function("fin base: Path = self.parent\nfin name: String = self.stem").decl.body
        val base = assertIs<Stmt.FinDecl>(body[0])
        val name = assertIs<Stmt.FinDecl>(body[1])
        assertEquals("Path", (base.type as TypeAnnotation.Explicit).ref.toString())
        assertEquals("String", (name.type as TypeAnnotation.Explicit).ref.toString())
        assertEquals("parent", assertIs<Expr.Member>(base.initializer).name)
        assertEquals("stem", assertIs<Expr.Member>(name.initializer).name)
    }

    @Test
    fun explicitBranchesInsideForRetainTheirReceivers() {
        val body = function("for i: Int in 0..<maxLen {\nif i < a.size then result[ri++] = a[i]\nif i < b.size then result[ri++] = b[i]\n}").decl.body
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
    fun filesystemExplicitBindingsParse() {
        val body = function("fin name: String = self.fileName\nfin dot: Int = _lastDot(name)").decl.body
        assertEquals(2, body.size)
    }
}
