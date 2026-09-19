/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */

package org.azora.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeclarationBodyTest {
    private fun parse(source: String) = Parser(Lexer(source.trimIndent()).tokenize()).parse()

    @Test fun singleStatementContractsKeepEntryAndReturnChecks() {
        val function = parse("""
            func identity(n: Int): Int
            in assert n >= 0 panic "negative"
            out assert it == n panic "changed"
            scope return n
        """).functions.single()
        assertIs<Stmt.Assert>(function.body.first())
        val exit = assertIs<Stmt.Scope>(function.body.last()).body
        assertTrue(exit.any { it is Stmt.Assert })
        assertIs<Stmt.Return>(exit.last())
    }

    @Test fun memberBodiesShareTheSingleStatementScopeForm() {
        val methods = parse("""
            impl Counter {
                ctor .(value: Int)
                in assert value >= 0 panic "negative"
                scope self.value = value
                prop &.current: Int scope return self.value
                dtor .() scope cleanup()
            }
        """).items.filterIsInstance<TopLevel.Impl>().single().methods
        val ctor = methods.single { it.name == "ctor" }
        val prop = methods.single { it.name == "current" }
        val dtor = methods.single { it.name == "dtor" }
        assertIs<Stmt.Assert>(ctor.body.first())
        assertIs<Stmt.MemberAssign>(ctor.body.last())
        assertEquals(ParamModifier.EXCLUSIVE, ctor.receiverModifier)
        assertEquals(ParamModifier.SHARED, prop.receiverModifier)
        assertEquals(ParamModifier.EXCLUSIVE, dtor.receiverModifier)
        assertIs<Stmt.Return>(prop.body.single())
        assertIs<Stmt.ExprStmt>(dtor.body.single())
    }

    @Test fun scopeCanPutItsBraceOnTheNextLine() {
        val methods = parse("""
            impl Counter {
                ctor .() scope
                { self.value = 0 }
                prop &.current: Int scope
                { return self.value }
                dtor .() scope
                { cleanup() }
            }
        """).items.filterIsInstance<TopLevel.Impl>().single().methods
        assertEquals(3, methods.size)
        assertTrue(methods.all { it.body.size == 1 })
    }

    @Test fun aSingleBodyDoesNotConsumeTheFollowingDeclaration() {
        val functions = parse("""
            func first(): Int
            scope return 1
            func second(): Int { return 2 }
        """).functions
        assertEquals(listOf("first", "second"), functions.map { it.name })
        assertTrue(functions.all { it.body.size == 1 })
    }

    @Test fun contractBracesMayStartOnTheFollowingLine() {
        val function = parse("""
            func identity(n: Int): Int
            in
            { assert n >= 0 panic "negative" }
            scope return n
        """).functions.single()
        assertIs<Stmt.Assert>(function.body.first())
        assertIs<Stmt.Return>(function.body.last())
    }

    @Test fun mixingBodyFormsDoesNotAllowDuplicateContracts() {
        for (keyword in listOf("in", "out")) {
            val error = assertFailsWith<IllegalStateException> {
                parse("""
                    func identity(n: Int): Int
                    $keyword assert true panic "first"
                    $keyword { assert true panic "second" }
                    scope return n
                """)
            }
            assertTrue("one '$keyword' contract" in error.message.orEmpty(), error.message)
        }
    }

    @Test fun aLifecycleReceiverCannotMoveIntoASingleBody() {
        for (header in listOf("ctor .()", "dtor .()")) {
            val error = assertFailsWith<IllegalStateException> {
                parse("impl Counter { $header scope self! -> cleanup() }")
            }
            assertTrue("receiver" in error.message.orEmpty(), error.message)
        }
    }

    @Test fun assertionMessagesCanContinueOnTheNextLine() {
        val program = parse("""
            inline assert true panic
                "top-level"
            func main() {
                inline assert true panic
                    "compile-time"
                assert true panic
                    "runtime"
            }
        """)
        val top = program.items.filterIsInstance<TopLevel.InlineAssert>().single()
        assertEquals("top-level", assertIs<Expr.StringLiteral>(top.message).value)
        val body = program.functions.single().body
        assertEquals("compile-time", assertIs<Expr.StringLiteral>(assertIs<Stmt.InlineAssert>(body[0]).message).value)
        assertEquals("runtime", assertIs<Expr.StringLiteral>(assertIs<Stmt.Assert>(body[1]).message).value)
    }

    @Test fun thenCanContinueAfterAnIfCondition() {
        val function = parse("""
            func pick(ready: Bool): Int {
                if ready
                then notify()
                fin chosen = if ready
                then 1
                else 2
                return if ready
                then chosen
                else 0
            }
        """).functions.single()
        assertIs<Stmt.If>(function.body[0])
        assertIs<Expr.IfExpr>(assertIs<Stmt.FinDecl>(function.body[1]).initializer)
        assertIs<Stmt.If>(function.body[2])
    }

    @Test fun unsafeSingleStatementRetainsItsBoundary() {
        val function = parse("func main() { unsafe assert true panic \"checked\" }").functions.single()
        val scope = assertIs<Stmt.Scope>(function.body.single())
        assertTrue(scope.unsafe)
        assertIs<Stmt.Assert>(scope.body.single())
    }

    @Test fun groupedIfBindingsAcceptThenWithoutAddingAnOuterBlock() {
        val function = parse("""
            func main() {
                var {sign, index}: Int = if negative
                then {-1, 1}
                else {1, 0}
            }
        """).functions.single()
        val condition = assertIs<Stmt.FinDecl>(function.body[0])
        val bindings = function.body.filterIsInstance<Stmt.VarDecl>()
        assertEquals(listOf("sign", "index"), bindings.map { it.name })
        for (binding in bindings) {
            val value = assertIs<Expr.IfExpr>(binding.initializer)
            assertEquals(condition.name, assertIs<Expr.Identifier>(value.condition).name)
        }
    }

    @Test fun groupedIfBindingsStillCheckBranchArity() {
        val error = assertFailsWith<IllegalStateException> {
            parse("func main() { var {a, b}: Int = if ready then {1} else {2, 3} }")
        }
        assertTrue("one value per name" in error.message.orEmpty(), error.message)
    }
}
