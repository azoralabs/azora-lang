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
 * `reflect<T>` is written plain, everywhere.
 *
 * It used to need a qualifier in front of it outside a namespace, because
 * `reflect` lived in a realm. Realms are gone and with them that realm, so the
 * qualifier has nothing left to name and the bare spelling is the only one the
 * language has.
 *
 * What the qualifier never did was make the *receiver* optional: `X.hasAnnot<D>`
 * is still an error, because the annotation is asked of a reflection handle rather
 * than of the value.
 */
class BareReflectTest {

    private fun parse(source: String): List<TopLevel> =
        Parser(Lexer(source).tokenize()).parse().items

    private fun bodyOf(source: String, name: String = "probe"): List<Stmt> =
        parse(source).filterIsInstance<TopLevel.Func>().single { it.decl.name == name }.decl.body

    private fun intrinsicOf(source: String): String {
        val cond = assertIs<Stmt.InlineIf>(bodyOf(source).single()).condition
        return assertIs<Expr.Call>(cond).callee
    }

    // -- bare reflect, outside any namespace --------------------------------

    @Test fun bareReflectWorksAtTopLevel() {
        // `std/container/deque.az` is written this way, at file scope.
        assertEquals(
            "__hasAnnot",
            intrinsicOf(
                """
                func probe(): Int {
                    inline if reflect<Deque>.hasAnnot<Serializable> { return 1 } else { return 0 }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun bareReflectReachesADeclarationMember() {
        assertEquals(
            "__hasAnnot",
            intrinsicOf(
                """
                func probe(): Int {
                    inline if reflect<Feature::value>.hasAnnot<Marker> { return 1 } else { return 0 }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun bareReflectCarriesAnnotMetaAndWithAnnot() {
        assertEquals(
            "__withAnnot",
            intrinsicOf(
                """
                func probe(): Int {
                    inline if reflect<*>.withAnnot<Marker> { return 1 } else { return 0 }
                }
                """.trimIndent(),
            ),
        )
    }

    // -- the receiver is still required -------------------------------------

    @Test fun anAnnotationIsAskedOfAHandleAndNotOfAType() {
        val e = assertFailsWith<IllegalStateException> {
            parse(
                """
                func probe(): Int {
                    inline if Feature.hasAnnot<Marker> { return 1 } else { return 0 }
                }
                """.trimIndent(),
            )
        }
        // The message teaches the spelling that works, and names no qualifier.
        assertTrue("requires an explicit reflect<receiver>" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun aDeclarationMemberGoesInsideTheBrackets() {
        val e = assertFailsWith<IllegalStateException> {
            parse(
                """
                func probe(): Int {
                    inline if (reflect<Feature>.value).hasAnnot<Marker> { return 1 } else { return 0 }
                }
                """.trimIndent(),
            )
        }
        assertTrue("members use '::'" in e.message.orEmpty(), e.message.orEmpty())
        assertTrue("reflect<Type::value>" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun anOrdinaryNameCalledReflectIsUntouched() {
        // Only a name followed by a reflect target is the handle, so a function of
        // the same name keeps working.
        val body = bodyOf("func probe(): Int {\n    return reflect(1)\n}")
        val call = assertIs<Expr.Call>(assertIs<Stmt.Return>(body.single()).value)
        assertEquals("reflect", call.callee)
    }
}
