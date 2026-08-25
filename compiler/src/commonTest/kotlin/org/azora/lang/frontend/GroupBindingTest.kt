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
 * `fin {a, b, c} = …` declares several names at once.
 *
 * A group is the lines it stands for, and those lines belong to the block it
 * was written in - not to a scope of their own, where the names would die at
 * the closing brace. What each name takes is decided by what is on the right.
 */
class GroupBindingTest {

    private fun body(source: String): List<Stmt> =
        Parser(Lexer("func f() {\n$source\n}").tokenize()).parse().items
            .filterIsInstance<TopLevel.Func>().single().decl.body

    private fun bound(stmt: Stmt): Pair<String, Expr> = when (stmt) {
        is Stmt.FinDecl -> stmt.name to stmt.initializer
        is Stmt.VarDecl -> stmt.name to stmt.initializer
        else -> throw AssertionError("not a binding: $stmt")
    }

    private fun member(expr: Expr): Pair<String, String> =
        assertIs<Expr.Member>(expr).let { assertIs<Expr.Identifier>(it.target).name to it.name }

    // -- reading members off one value --------------------------------------

    @Test fun usingReadsEachMemberOfOneValue() {
        val stmts = body(
            """
            fin {oldKeys, oldValues, oldCapacity} = using self {
                {keys, values, capacity}
            }
            """.trimIndent(),
        )

        assertEquals(3, stmts.size)
        assertEquals(listOf("oldKeys", "oldValues", "oldCapacity"), stmts.map { bound(it).first })
        assertEquals(listOf("keys", "values", "capacity"), stmts.map { member(bound(it).second).second })
        assertTrue(stmts.all { member(bound(it).second).first == "self" })
    }

    @Test fun theBindingsBelongToTheBlockAroundThem() {
        // Not a scope of their own: a name declared here is readable below.
        val stmts = body("fin {a, b} = using self { {x, y} }\nreturn a")
        assertIs<Stmt.FinDecl>(stmts[0])
        assertIs<Stmt.FinDecl>(stmts[1])
        assertIs<Stmt.Return>(stmts[2])
    }

    @Test fun theReceiverIsWhateverWasWritten() {
        val stmts = body("fin {w, h} = using config.window { {width, height} }")
        assertIs<Expr.Member>(assertIs<Expr.Member>(bound(stmts[0]).second).target)
    }

    // -- one value per name -------------------------------------------------

    @Test fun aBraceSourceGroupIsPositional() {
        val stmts = body("""fin {a, b, c} = {1, "2", true}""")
        assertEquals(listOf("a", "b", "c"), stmts.map { bound(it).first })
        assertIs<Expr.IntLiteral>(bound(stmts[0]).second)
        assertIs<Expr.StringLiteral>(bound(stmts[1]).second)
        assertIs<Expr.BoolLiteral>(bound(stmts[2]).second)
    }

    // -- one expression, written to each ------------------------------------

    @Test fun oneExpressionIsWrittenToEveryName() {
        val stmts = body("fin {a, b} = next()")
        assertEquals(2, stmts.size)
        assertTrue(stmts.all { bound(it).second is Expr.Call })
    }

    // -- each name may state its type ---------------------------------------

    @Test fun everyNameMayStateItsOwnType() {
        val stmts = body(
            """
            let {
                newKeys: K*
                newValues: V*
            } = alloc .() * newCapacity
            """.trimIndent(),
        )

        assertEquals(2, stmts.size)
        assertTrue(stmts.all { it is Stmt.LetDecl }, "$stmts")
        assertEquals(
            listOf("K*", "V*"),
            stmts.map { assertIs<TypeAnnotation.Explicit>(assertIs<Stmt.LetDecl>(it).type).ref.displayName() },
        )
    }

    @Test fun oneTypeMayCoverTheWholeGroup() {
        val stmts = body("fin {a, b, c}: Int = 0")

        assertEquals(3, stmts.size)
        assertTrue(
            stmts.all { assertIs<TypeAnnotation.Explicit>(assertIs<Stmt.FinDecl>(it).type).ref.displayName() == "Int" },
            "$stmts",
        )
        assertTrue(stmts.all { assertIs<Expr.IntLiteral>(bound(it).second).value == 0L })
    }

    @Test fun oneTypeAndOneValuePerName() {
        val stmts = body("fin {a, b, c}: Int = {1, 2, 3}")

        assertEquals(listOf(1L, 2L, 3L), stmts.map { assertIs<Expr.IntLiteral>(bound(it).second).value })
    }

    @Test fun oneTypedVarGroupMayBeInitializedByConditionalSourceGroups() {
        val stmts = body(
            "var {sign, index}: Int = if charAt(text, 0) == '-' { {-1, 1} } else { {1, 0} }",
        )

        assertEquals(3, stmts.size, "$stmts")
        assertTrue(assertIs<Stmt.FinDecl>(stmts[0]).name.startsWith("__group_condition_"))
        val bindings = stmts.drop(1).map { assertIs<Stmt.VarDecl>(it) }
        assertEquals(listOf("sign", "index"), bindings.map { it.name })
        assertTrue(bindings.all { assertIs<TypeAnnotation.Explicit>(it.type).ref.displayName() == "Int" })
        assertTrue(bindings.all { it.initializer is Expr.IfExpr })
    }

    @Test fun everyConditionalGroupBranchMustMatchTheBindingArity() {
        val error = assertFailsWith<IllegalStateException> {
            body("var {a, b}: Int = if ready { {1} } else { {2, 3} }")
        }
        assertTrue("one value per name" in error.message.orEmpty(), error.message.orEmpty())
        assertTrue("'if' branch" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test fun theTypeIsStatedOnceOrPerName() {
        val e = assertFailsWith<IllegalStateException> { body("fin {a: Int, b}: Int = 0") }
        assertTrue("states its type once" in e.message.orEmpty(), e.message.orEmpty())
    }

    // -- var groups ---------------------------------------------------------

    @Test fun varBindsMutably() {
        val stmts = body("var {a, b} = using self { {x, y} }")
        assertTrue(stmts.all { it is Stmt.VarDecl }, "$stmts")
    }

    // -- what is rejected ---------------------------------------------------

    @Test fun theCountsMustAgree() {
        val e = assertFailsWith<IllegalStateException> { body("fin {a, b, c} = using self { {x, y} }") }
        assertTrue("one value per name" in e.message.orEmpty(), e.message.orEmpty())
        assertTrue("3 named" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun aNameMayNotBeBoundTwice() {
        val e = assertFailsWith<IllegalStateException> { body("fin {a, b, a} = {1, 2, 3}") }
        assertTrue("more than once" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun anEmptyGroupIsRejected() {
        val e = assertFailsWith<IllegalStateException> { body("fin {} = 0") }
        assertTrue("binds nothing" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun lazyTakesASingleBinding() {
        val e = assertFailsWith<IllegalStateException> { body("lazy fin {a, b} = next()") }
        assertTrue("single binding" in e.message.orEmpty(), e.message.orEmpty())
    }

    // -- neighbouring forms are untouched -----------------------------------

    @Test fun anOrdinaryBindingStillWorks() {
        assertEquals("x", bound(body("fin x = 1").single()).first)
    }

    @Test fun aLoopStillDestructuresItsRow() {
        // `for [a, b] in rows` is a different construct: it splits one row.
        val loop = assertIs<Stmt.For>(body("for [a, b] in rows { }").single())
        assertTrue(loop.body.take(2).all { it is Stmt.FinDecl }, "${loop.body}")
    }
}
