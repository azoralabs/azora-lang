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
 * A `when` in a type computation may name a scrutinee.
 *
 * ```
 * deepinline func Nullable<T>(b: Bool): Type {
 *     return when b {
 *         true  => Option<T?>
 *         false => Option<T>
 *     }
 * }
 * ```
 *
 * It reads in type position the way it reads in value position. Each arm becomes
 * the same [TypeFunctionCondition] the conditionless form already builds - a
 * `true`/`false` arm is the flag test, any other arm is a comparison against the
 * scrutinee - so the evaluator learns nothing new.
 *
 * `true` and `false` between them leave nothing over, so a `when` on a `Bool`
 * needs no `else`.
 */
class TypeComputationWhenTest {

    private fun typeFunction(source: String): TypeFunctionDecl =
        Parser(Lexer(source).tokenize()).parse().typeFunctions.single()

    private fun returnedValue(source: String): TypeFunctionExpr =
        assertIs<TypeFunctionStmt.Return>(typeFunction(source).body.single()).value

    // -- a Bool scrutinee ---------------------------------------------------

    @Test fun aBoolScrutineeNeedsNoElse() {
        // `std/core.az:90`.
        val value = returnedValue(
            """
            deepinline func Nullable<T>(b: Bool): Type {
                return when b {
                    true => Option<T?>
                    false => Option<T>
                }
            }
            """.trimIndent(),
        )
        val conditional = assertIs<TypeFunctionExpr.Conditional>(value)
        assertEquals(listOf("b"), conditional.condition.valueFlags)
        assertEquals(listOf(true), conditional.condition.flagsExpected)
    }

    @Test fun eachBranchKeepsTheTypeItNames() {
        val conditional = assertIs<TypeFunctionExpr.Conditional>(
            returnedValue(
                """
                deepinline func Nullable<T>(b: Bool): Type {
                    return when b {
                        true => Option<T?>
                        false => Option<T>
                    }
                }
                """.trimIndent(),
            ),
        )
        // The first written arm is the outermost test, so `true` is the then-branch
        // and the remaining case is the else-branch.
        assertIs<TypeFunctionExpr.Call>(conditional.thenValue)
        assertIs<TypeFunctionExpr.Call>(conditional.elseValue)
    }

    @Test fun aNegatedArmBecomesTheSameFlagTest() {
        // `false =>` is the flag expected false - what `!b =>` writes in the
        // conditionless form.
        val conditional = assertIs<TypeFunctionExpr.Conditional>(
            returnedValue(
                """
                deepinline func Pick<T>(b: Bool): Type {
                    return when b {
                        false => T
                        true => T?
                    }
                }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf(false), conditional.condition.flagsExpected)
    }

    @Test fun anExplicitElseIsStillAccepted() {
        val conditional = assertIs<TypeFunctionExpr.Conditional>(
            returnedValue(
                """
                deepinline func Pick<T>(b: Bool): Type {
                    return when b {
                        true => T?
                        else => T
                    }
                }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("b"), conditional.condition.valueFlags)
    }

    // -- a type scrutinee ---------------------------------------------------

    @Test fun aTypeScrutineeComparesAgainstEachArm() {
        val conditional = assertIs<TypeFunctionExpr.Conditional>(
            returnedValue(
                """
                deepinline prop Widen<T>: Type {
                    return when T {
                        Int => Long
                        else => T
                    }
                }
                """.trimIndent(),
            ),
        )
        assertEquals(TokenType.EQUAL_EQUAL, conditional.condition.operator)
        assertEquals("T", assertIs<TypeFunctionExpr.Reference>(conditional.condition.left).name)
        assertEquals("Int", assertIs<TypeFunctionExpr.Reference>(conditional.condition.right).name)
    }

    @Test fun armsAreTriedInWritingOrder() {
        val outer = assertIs<TypeFunctionExpr.Conditional>(
            returnedValue(
                """
                deepinline prop Widen<T>: Type {
                    return when T {
                        Int => Long
                        Float => Double
                        else => T
                    }
                }
                """.trimIndent(),
            ),
        )
        // First written arm is the outermost test.
        assertEquals("Int", assertIs<TypeFunctionExpr.Reference>(outer.condition.right).name)
        val inner = assertIs<TypeFunctionExpr.Conditional>(outer.elseValue)
        assertEquals("Float", assertIs<TypeFunctionExpr.Reference>(inner.condition.right).name)
    }

    // -- the conditionless form is unchanged --------------------------------

    @Test fun theConditionlessFormStillWorks() {
        val conditional = assertIs<TypeFunctionExpr.Conditional>(
            returnedValue(
                """
                deepinline prop Widest<A, B>: Type {
                    return when {
                        A.rank >= B.rank => A
                        else => B
                    }
                }
                """.trimIndent(),
            ),
        )
        assertTrue(conditional.condition.compareRank)
    }

    @Test fun theConditionlessFormStillRequiresElse() {
        val e = assertFailsWith<IllegalStateException> {
            typeFunction(
                """
                deepinline prop Widest<A, B>: Type {
                    return when {
                        A.rank >= B.rank => A
                    }
                }
                """.trimIndent(),
            )
        }
        assertTrue("must end with 'else'" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun aScrutineeWhichLeavesCasesOverStillNeedsElse() {
        // Only `true` and `false` together are exhaustive; one arm is not.
        val e = assertFailsWith<IllegalStateException> {
            typeFunction(
                """
                deepinline func Pick<T>(b: Bool): Type {
                    return when b {
                        true => T?
                    }
                }
                """.trimIndent(),
            )
        }
        assertTrue("must end with 'else'" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun aTypeScrutineeWithoutElseIsRejected() {
        val e = assertFailsWith<IllegalStateException> {
            typeFunction(
                """
                deepinline prop Widen<T>: Type {
                    return when T {
                        Int => Long
                    }
                }
                """.trimIndent(),
            )
        }
        assertTrue("must end with 'else'" in e.message.orEmpty(), e.message.orEmpty())
    }
}
