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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `target++` and `target--` desugar to `target = target ± 1`.
 *
 * Every assignable target takes one - a local, a field, an element, a pointee -
 * which is the same set `target += 1` and `target ?++` already take. Increment is
 * a statement: it has no expression form, because a value-producing side effect
 * would need an IR node all four backends do not have.
 *
 * These are parser tests because the bundled stdlib does not parse yet (see the
 * Baseline section of UPGRADE_PLAN.MD). The desugaring emits exactly the nodes
 * `self.x += 1` already emits, so what the backends do with them is already covered.
 */
class IncrementTest {

    private fun body(source: String): List<Stmt> =
        Parser(Lexer("func f() {\n$source\n}").tokenize()).parse().items
            .filterIsInstance<TopLevel.Func>().single().decl.body

    private fun only(source: String): Stmt = body(source).single()

    /** The `± 1` an increment desugars to, as `(operator, literal)`. */
    private fun step(value: Expr): Pair<TokenType, Long> {
        val binary = assertIs<Expr.Binary>(value, "an increment desugars to a binary")
        val one = assertIs<Expr.IntLiteral>(binary.right, "the step is a literal")
        return binary.op to one.value
    }

    // -- every assignable target --------------------------------------------

    @Test fun aLocalIncrements() {
        val stmt = assertIs<Stmt.Assignment>(only("x++"))
        assertEquals("x", stmt.name)
        assertEquals(TokenType.PLUS to 1L, step(stmt.value))
    }

    @Test fun aLocalDecrements() {
        val stmt = assertIs<Stmt.Assignment>(only("x--"))
        assertEquals(TokenType.MINUS to 1L, step(stmt.value))
    }

    @Test fun aFieldIncrements() {
        // `self.allocCount++` - the form the allocators are written in, and the one
        // that used to fall through to an expression statement and fail on the `++`.
        val stmt = assertIs<Stmt.MemberAssign>(only("self.allocCount++"))
        assertEquals("allocCount", stmt.name)
        assertEquals(TokenType.PLUS to 1L, step(stmt.value))
    }

    @Test fun aFieldDecrements() {
        val stmt = assertIs<Stmt.MemberAssign>(only("self.freeCount--"))
        assertEquals("freeCount", stmt.name)
        assertEquals(TokenType.MINUS to 1L, step(stmt.value))
    }

    @Test fun aNestedFieldIncrements() {
        val stmt = assertIs<Stmt.MemberAssign>(only("self.stats.hits++"))
        assertEquals("hits", stmt.name)
        assertIs<Expr.Member>(stmt.target)
    }

    @Test fun anElementIncrements() {
        val stmt = assertIs<Stmt.IndexAssign>(only("counts[i]++"))
        assertEquals(TokenType.PLUS to 1L, step(stmt.value))
    }

    @Test fun aPointeeIncrements() {
        val stmt = assertIs<Stmt.DerefAssign>(only("slot.*++"))
        assertEquals(TokenType.PLUS to 1L, step(stmt.value))
    }

    // -- what it desugars to ------------------------------------------------

    @Test fun theTargetIsReadBackAsTheLeftOperand() {
        // `self.n++` must become `self.n = self.n + 1`, not `self.n = <something> + 1`.
        val stmt = assertIs<Stmt.MemberAssign>(only("self.n++"))
        val binary = assertIs<Expr.Binary>(stmt.value)
        val left = assertIs<Expr.Member>(binary.left)
        assertEquals("n", left.name)
    }

    @Test fun anIncrementIsNotACompoundAssignment() {
        // `+=` carries its operator so a type declaring `oper+=` gets the in-place
        // call. `++` does not, so it keeps lowering to build-and-assign exactly as
        // it did before it reached targets beyond a local.
        assertNull(assertIs<Stmt.Assignment>(only("x++")).compoundOp)
        assertNull(assertIs<Stmt.MemberAssign>(only("self.n++")).compoundOp)
        assertEquals(TokenType.PLUS, assertIs<Stmt.Assignment>(only("x += 1")).compoundOp)
    }

    @Test fun incrementsSequenceWithoutSeparators() {
        val stmts = body("x++\nself.n--\ncounts[0]++")
        assertEquals(3, stmts.size)
        assertIs<Stmt.Assignment>(stmts[0])
        assertIs<Stmt.MemberAssign>(stmts[1])
        assertIs<Stmt.IndexAssign>(stmts[2])
    }

    // -- neighbouring forms still work --------------------------------------

    @Test fun theNullConditionalFormStillGuards() {
        // `target ?++` is `if target != null { target = target + 1 }` and keeps its
        // guard - the plain form must not have swallowed it.
        val stmt = assertIs<Stmt.If>(only("self.n ?++"))
        assertIs<Stmt.MemberAssign>(stmt.thenBranch.single())
    }

    @Test fun compoundAssignmentIsUntouched() {
        val stmt = assertIs<Stmt.MemberAssign>(only("self.total += size"))
        assertEquals(TokenType.PLUS, stmt.compoundOp)
    }

    // -- increment is a statement -------------------------------------------

    @Test fun thereIsNoExpressionForm() {
        // `a[i++]` would need a value-producing side effect. Rejecting it beats
        // parsing it into something that silently drops either the value or the
        // increment.
        assertFailsWith<IllegalStateException> { body("counts[i++] = 1") }
    }

    @Test fun anIncrementTargetMustBeAssignable() {
        val e = assertFailsWith<IllegalStateException> { body("f()++") }
        assertTrue("assignment target" in e.message.orEmpty(), e.message.orEmpty())
    }
}
