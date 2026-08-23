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
 * `receiver.[a, b, c] = …` assigns several members of one receiver at once.
 *
 * The bracket on the right is what tells the two forms apart: one expression is
 * written to every member, a bracketed list gives one value per member. Both
 * desugar to ordinary member assignments, so nothing past the parser learns a
 * new node - a group is the lines it stands for.
 *
 * These are parser tests because the bundled stdlib does not parse yet (see the
 * Baseline section of UPGRADE_PLAN.MD).
 */
class GroupedAssignTest {

    private fun body(source: String): List<Stmt> =
        Parser(Lexer("func f() {\n$source\n}").tokenize()).parse().items
            .filterIsInstance<TopLevel.Func>().single().decl.body

    private fun group(source: String): List<Stmt> =
        assertIs<Stmt.Scope>(body(source).single(), "a grouped assignment is one scope").body

    private fun assigned(stmt: Stmt): Pair<String, Expr> =
        assertIs<Stmt.MemberAssign>(stmt).let { it.name to it.value }

    // -- one value, to each member ------------------------------------------

    @Test fun oneValueReachesEveryMember() {
        // `self.{offset, allocCount} = 0` - the form the allocators reset with.
        val stmts = group("self.{offset, allocCount} = 0")
        assertEquals(listOf("offset", "allocCount"), stmts.map { assigned(it).first })
        assertTrue(stmts.all { assertIs<Expr.IntLiteral>(assigned(it).second).value == 0L })
    }

    @Test fun theExpressionIsWrittenToEveryMember() {
        // A group is the lines it stands for, so each member gets the
        // expression - not one shared result. `alloc .() * n` asks for a buffer
        // per member, and handing both the same one would alias them.
        val stmts = group("self.{keys, values, hashes} = next()")
        assertEquals(3, stmts.size)
        assertTrue(stmts.none { it is Stmt.FinDecl }, "nothing is bound in between")
        assertTrue(stmts.all { assigned(it).second is Expr.Call })
    }

    @Test fun fourMembersTakeTheSameValue() {
        val stmts = group("self.{offset, allocCount, peakUsage, totalAllocated} = 0")
        assertEquals(
            listOf("offset", "allocCount", "peakUsage", "totalAllocated"),
            stmts.map { assigned(it).first },
        )
    }

    // -- one value per member -----------------------------------------------

    @Test fun aBraceSourceGroupIsPositional() {
        val stmts = group("""self.{a, b, c} = {1, "2", true}""")
        assertEquals(listOf("a", "b", "c"), stmts.map { assigned(it).first })
        assertIs<Expr.IntLiteral>(assigned(stmts[0]).second)
        assertIs<Expr.StringLiteral>(assigned(stmts[1]).second)
        assertIs<Expr.BoolLiteral>(assigned(stmts[2]).second)
    }

    @Test fun aPositionalGroupNeedsNoTemporary() {
        // Each value lands in exactly one member, so there is nothing to share.
        val stmts = group("self.{a, b} = {1, 2}")
        assertEquals(2, stmts.size)
        assertTrue(stmts.none { it is Stmt.FinDecl })
    }

    // -- commas are optional across lines -----------------------------------

    @Test fun namesMayBeSeparatedByNewlinesAlone() {
        val stmts = group(
            """
            self.{
                offset
                allocCount
                peakUsage
            } = 0
            """.trimIndent(),
        )
        assertEquals(listOf("offset", "allocCount", "peakUsage"), stmts.map { assigned(it).first })
    }

    @Test fun valuesMayBeSeparatedByNewlinesAlone() {
        val stmts = group(
            """
            self.{a, b} = {
                1
                2
            }
            """.trimIndent(),
        )
        assertEquals(listOf("a", "b"), stmts.map { assigned(it).first })
    }

    // -- any receiver, not just self ----------------------------------------

    @Test fun theReceiverIsWhateverWasWritten() {
        val stmts = group("config.{width, height} = 0")
        assertEquals("config", assertIs<Expr.Identifier>(assertIs<Stmt.MemberAssign>(stmts[0]).target).name)
    }

    @Test fun aNestedReceiverWorks() {
        val stmts = group("self.window.{width, height} = 0")
        assertIs<Expr.Member>(assertIs<Stmt.MemberAssign>(stmts[0]).target)
    }

    // -- successive groups stay independent ---------------------------------

    @Test fun twoGroupsInOneBodyStayApart() {
        val stmts = body("self.{a, b} = 0\nself.{c, d} = 1")
        assertEquals(listOf("a", "b"), assertIs<Stmt.Scope>(stmts[0]).body.map { assigned(it).first })
        assertEquals(listOf("c", "d"), assertIs<Stmt.Scope>(stmts[1]).body.map { assigned(it).first })
    }

    // -- what is rejected ---------------------------------------------------

    @Test fun aPositionalGroupMustMatchInArity() {
        val e = assertFailsWith<IllegalStateException> { body("self.{a, b, c} = {1, 2}") }
        assertTrue(
            "one value per member" in e.message.orEmpty() && "3 named" in e.message.orEmpty(),
            e.message.orEmpty(),
        )
    }

    @Test fun aMemberMayNotBeNamedTwice() {
        val e = assertFailsWith<IllegalStateException> { body("self.{a, b, a} = 0") }
        assertTrue("more than once" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun anEmptyGroupIsRejected() {
        val e = assertFailsWith<IllegalStateException> { body("self.{} = 0") }
        assertTrue("at least one member name" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun aGroupIsATargetAndNotAValue() {
        // `.[…]` reads several members at once, which is not a value any type has.
        val e = assertFailsWith<IllegalStateException> { body("fin x = self.{a, b}") }
        assertTrue("not a value" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun aGroupMustBeAssigned() {
        val e = assertFailsWith<IllegalStateException> { body("self.{a, b}") }
        assertTrue("Expected '='" in e.message.orEmpty(), e.message.orEmpty())
    }

    // -- neighbouring forms are untouched -----------------------------------

    @Test fun ordinaryMemberAssignmentStillWorks() {
        val stmt = assertIs<Stmt.MemberAssign>(body("self.offset = 0").single())
        assertEquals("offset", stmt.name)
    }

    @Test fun indexingIsNotAGroup() {
        // `self.slots[i] = 0` has no dot before the bracket, so it stays an index.
        assertIs<Stmt.IndexAssign>(body("self.slots[i] = 0").single())
    }
}
