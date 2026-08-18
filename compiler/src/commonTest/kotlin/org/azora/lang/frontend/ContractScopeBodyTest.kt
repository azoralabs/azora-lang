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
import kotlin.test.assertTrue

/**
 * A member that writes contracts supplies its body as `scope { … }`, so the
 * `in { … }` above it reads as a clause and not as the body:
 *
 * ```
 * ctor[self: Self!](capacity: Int)
 * in {
 *     assert capacity > 0 { "capacity must be positive" }
 * } scope {
 *     self.capacity = capacity
 * }
 * ```
 *
 * The receiver is declared in brackets on the signature, where every other member
 * declares one. `scope { self! -> … }` named it in the one place that is not a
 * declaration, so that spelling is an error naming its replacement rather than a
 * second form that still works.
 *
 * These are parser tests rather than compile tests because the bundled stdlib does
 * not parse yet (see the Baseline section of UPGRADE_PLAN.MD), which would fail any
 * test that ran a whole compile for reasons that have nothing to do with this form.
 */
class ContractScopeBodyTest {

    private fun parse(source: String): List<TopLevel> =
        Parser(Lexer(source).tokenize()).parse().items

    private fun member(source: String, name: String): FuncDecl =
        parse(source).filterIsInstance<TopLevel.Impl>()
            .flatMap { it.methods }
            .single { it.name == name }

    // -- `scope { … }` is the body ------------------------------------------

    @Test fun aCtorTakesItsBodyFromScopeAfterAContract() {
        val ctor = member(
            """
            impl Arena {
                ctor[self: Self!](capacity: Int)
                in {
                    assert capacity > 0 { "Arena capacity must be positive" }
                } scope {
                    self.capacity = capacity
                }
            }
            """.trimIndent(),
            "ctor",
        )
        assertEquals(ParamModifier.EXCLUSIVE, ctor.receiverModifier)
        assertEquals(listOf("capacity"), ctor.params.map { it.name })
        // The precondition is spliced ahead of the body, so the body is not empty
        // and the assert survives as a statement.
        assertTrue(ctor.body.isNotEmpty(), "contract and body both reached the ctor")
    }

    @Test fun aCtorStillTakesAPlainBraceBody() {
        val ctor = member(
            """
            impl Arena {
                ctor[self: Self!](capacity: Int) {
                    self.capacity = capacity
                }
            }
            """.trimIndent(),
            "ctor",
        )
        assertEquals(1, ctor.body.size)
    }

    @Test fun aDtorTakesItsBodyFromScope() {
        val dtor = member(
            """
            impl Arena {
                dtor[self: Self&] scope {
                    self.offset = 0
                }
            }
            """.trimIndent(),
            "dtor",
        )
        assertEquals(1, dtor.body.size)
    }

    @Test fun aPropTakesItsBodyFromScopeAfterAContract() {
        val prop = member(
            """
            impl Arena {
                prop remaining[self: Self&]: Int
                in {
                    assert self.capacity > 0 { "capacity must be positive" }
                } scope {
                    return self.capacity - self.offset
                }
            }
            """.trimIndent(),
            "remaining",
        )
        assertTrue(prop.body.isNotEmpty())
    }

    @Test fun aFuncTakesItsBodyFromScopeAfterAContract() {
        val f = parse(
            """
            func allocate(size: Int): Int
            in {
                assert size > 0 { "Allocation size must be positive" }
            } scope {
                return size
            }
            """.trimIndent(),
        ).filterIsInstance<TopLevel.Func>().single().decl
        assertTrue(f.body.isNotEmpty())
    }

    // -- the in-brace receiver is gone --------------------------------------

    @Test fun aCtorRejectsAnInBraceReceiver() {
        val e = assertFailsWith<IllegalStateException> {
            parse(
                """
                impl Arena {
                    ctor[self: Self!](capacity: Int) scope { self! ->
                        self.capacity = capacity
                    }
                }
                """.trimIndent(),
            )
        }
        assertTrue(
            "in-brace receiver is not a declaration" in e.message.orEmpty() &&
                "ctor[self: Self!]" in e.message.orEmpty(),
            "expected the message to name the bracket spelling, got: ${e.message}",
        )
    }

    @Test fun aDtorRejectsAnInBraceReceiver() {
        val e = assertFailsWith<IllegalStateException> {
            parse(
                """
                impl Arena {
                    dtor[self: Self&] { self& ->
                        self.offset = 0
                    }
                }
                """.trimIndent(),
            )
        }
        assertTrue("in-brace receiver is not a declaration" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun aPropRejectsAnInBraceReceiver() {
        val e = assertFailsWith<IllegalStateException> {
            parse(
                """
                impl Arena {
                    prop used[self: Self&]: Int { self& ->
                        return self.offset
                    }
                }
                """.trimIndent(),
            )
        }
        assertTrue("in-brace receiver is not a declaration" in e.message.orEmpty(), e.message.orEmpty())
    }

    // -- `scope` keeps its other meaning ------------------------------------

    @Test fun aScopeBlockInsideABodyIsStillABlock() {
        // `scope` introduces a body only where a body is expected. Inside one it
        // is the block expression of the same name, and must not be eaten.
        val f = parse(
            """
            func f() {
                scope {
                    fin x = 1
                }
            }
            """.trimIndent(),
        ).filterIsInstance<TopLevel.Func>().single().decl
        assertEquals(1, f.body.size, "the inner scope block survived as a statement")
    }
}
