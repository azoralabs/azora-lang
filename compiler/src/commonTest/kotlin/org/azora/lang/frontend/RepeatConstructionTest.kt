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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `.(args) * count` builds `count` of something.
 *
 * The ctor it selects declares the repetition on its signature, and calls it
 * `count` - a fixed name, not a chosen one, so every such ctor reads alike and a
 * body that uses it needs no legend. A repetition of none builds nothing, so the
 * ctor carries a precondition saying so.
 *
 * Everything after the `*` is the count: `.() * a.size + b.size` asks for as many
 * elements as the two lists together. The form builds a value rather than
 * multiplying one, so it does not share multiplication's precedence.
 */
class RepeatConstructionTest {

    private fun parse(source: String): List<TopLevel> =
        Parser(Lexer(source).tokenize()).parse().items

    private fun ctors(source: String): List<FuncDecl> =
        parse(source).filterIsInstance<TopLevel.Impl>().flatMap { it.methods }.filter { it.name == "ctor" }

    private fun initializerOf(source: String): Expr =
        assertIs<Stmt.VarDecl>(
            parse("func f() {\n$source\n}")
                .filterIsInstance<TopLevel.Func>().single().decl.body.first(),
        ).initializer

    // -- the declaration ----------------------------------------------------

    @Test fun aRepeatedCtorTakesItsCountAsAParameter() {
        val ctor = ctors(
            """
            impl ArrayList<T> {
                ctor[self: Self!]() * count {
                    self._size = count
                }
            }
            """.trimIndent(),
        ).single()
        assertEquals(listOf("count"), ctor.params.map { it.name })
        assertEquals("Int", (ctor.params.single().type as? TypeRef.Named)?.name)
    }

    @Test fun theCountArrivesAfterTheDeclaredParameters() {
        val ctor = ctors(
            """
            impl ArrayList<T> {
                ctor[self: Self!](fill: T) * count {
                    self._size = count
                }
            }
            """.trimIndent(),
        ).single()
        assertEquals(listOf("fill", "count"), ctor.params.map { it.name })
    }

    @Test fun aRepeatedCtorRequiresAtLeastOne() {
        val ctor = ctors(
            """
            impl ArrayList<T> {
                ctor[self: Self!]() * count {
                    self._size = count
                }
            }
            """.trimIndent(),
        ).single()
        val assert = assertIs<Stmt.Assert>(ctor.body.first(), "the precondition leads the body")
        val cond = assertIs<Expr.Binary>(assert.condition)
        assertEquals(TokenType.GREATER_EQUAL, cond.op)
        assertEquals("count", assertIs<Expr.Identifier>(cond.left).name)
        assertEquals(1L, assertIs<Expr.IntLiteral>(cond.right).value)
    }

    @Test fun theRepetitionMustBeCalledCount() {
        val e = assertFailsWith<IllegalStateException> {
            parse("impl A {\n    ctor[self: Self!]() * n {\n        self.x = n\n    }\n}")
        }
        assertTrue("binds its repetition as 'count'" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun anOrdinaryCtorIsUnchanged() {
        val ctor = ctors("impl A {\n    ctor[self: Self!](x: Int) {\n        self.x = x\n    }\n}").single()
        assertEquals(listOf("x"), ctor.params.map { it.name })
        assertTrue(ctor.body.none { it is Stmt.Assert })
    }

    @Test fun aRepeatedCtorKeepsItsOwnContracts() {
        val ctor = ctors(
            """
            impl A {
                ctor[self: Self!](fill: Int) * count
                in {
                    assert fill > 0 { "fill must be positive" }
                } scope {
                    self.x = count
                }
            }
            """.trimIndent(),
        ).single()
        // Two preconditions now lead the body: the repetition's and the written one.
        assertEquals(2, ctor.body.count { it is Stmt.Assert })
    }

    // -- the call site ------------------------------------------------------

    @Test fun aRepeatedConstructionIsOneExpression() {
        // The bug this replaced: the initializer ended at `.()`, and `* 3` opened a
        // new statement where it read as a dereference of 3 - so the repetition was
        // silently dropped and the declaration built one element.
        val stmts = parse("func f() {\n    var x: Array<Int> = .() * 3\n}")
            .filterIsInstance<TopLevel.Func>().single().decl.body
        assertEquals(1, stmts.size, "the repetition stayed part of the declaration")
    }

    @Test fun theCountIsTheWholeExpressionAfterTheStar() {
        // `std/algorithm/algorithm.az` asks for `a.size + b.size` elements.
        val repeat = assertIs<Expr.Binary>(initializerOf("var r: Array<Int> = .() * a.size + b.size"))
        assertEquals(TokenType.STAR, repeat.op)
        val count = assertIs<Expr.Binary>(repeat.right)
        assertEquals(TokenType.PLUS, count.op)
    }

    @Test fun aFilledRepetitionKeepsItsArgument() {
        val repeat = assertIs<Expr.Binary>(initializerOf("var r: Array<Int> = .(5) * 10"))
        val call = assertIs<Expr.Call>(repeat.left)
        assertEquals("Array", call.callee)
        assertEquals(1, call.args.size)
        assertEquals(10L, assertIs<Expr.IntLiteral>(repeat.right).value)
    }

    @Test fun aPlainLeadingDotConstructionStillWorks() {
        val call = assertIs<Expr.Call>(initializerOf("var p: Point = .(1, 2)"))
        assertEquals("Point", call.callee)
        assertEquals(2, call.args.size)
    }

    // -- alloc ---------------------------------------------------------------

    @Test fun allocPutsTheSigilOnTheType() {
        // `alloc T*(…)` - the sigil sits where it sits when the type is written out.
        val alloc = assertIs<Expr.Alloc>(initializerOf("var p: Int* = alloc Int*()"))
        assertEquals(false, alloc.mutable)
        assertEquals("Int", assertIs<Expr.Call>(alloc.value).callee)
    }

    @Test fun aCaretOnTheTypeAllocatesAnOwningPointer() {
        val alloc = assertIs<Expr.Alloc>(initializerOf("var p: Int^ = alloc Int^()"))
        assertEquals(true, alloc.mutable)
    }

    @Test fun allocOnTheKeywordStillParses() {
        // The older spelling, on its way out.
        assertIs<Expr.Alloc>(initializerOf("var p: Int^ = alloc^ Int()"))
    }

    @Test fun anAllocatedRepetitionIsOneExpression() {
        val repeat = assertIs<Expr.Binary>(initializerOf("var p: Int* = alloc .() * 4"))
        assertEquals(TokenType.STAR, repeat.op)
        assertIs<Expr.Alloc>(repeat.left)
    }

    @Test fun anAllocatedTypedRepetitionIsOneExpression() {
        val repeat = assertIs<Expr.Binary>(initializerOf("var p: Int* = alloc Int*() * 4"))
        assertIs<Expr.Alloc>(repeat.left)
        assertEquals(4L, assertIs<Expr.IntLiteral>(repeat.right).value)
    }

    @Test fun theBracketBufferFormIsGone() {
        // `alloc T[n]` said "a buffer of n" a second way. A buffer is a repeated
        // allocation, so it is spelled like one.
        val e = assertFailsWith<IllegalStateException> {
            initializerOf("var p: Int* = alloc Int[8]")
        }
        assertTrue("was removed" in e.message.orEmpty(), e.message.orEmpty())
        assertTrue("alloc Int*() * <count>" in e.message.orEmpty(), e.message.orEmpty())
    }
}
