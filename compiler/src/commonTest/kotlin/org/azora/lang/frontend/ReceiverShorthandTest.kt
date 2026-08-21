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
 * Inside an `impl` the receiver's type is never in question, so it may be left
 * out: `[self&]` says the one thing that varies where `[self: Self&]` spends
 * three tokens repeating the type being implemented.
 *
 * ```
 * impl A {
 *     func x[self&]() {}   // borrowed, read-only
 *     func y[self!]() {}   // borrowed, mutable
 *     func c[self]() {}    // owned, consuming
 *     func z() {}          // no receiver at all - a static, see S5.1
 * }
 * ```
 */
class ReceiverShorthandTest {

    private fun member(source: String, name: String): FuncDecl =
        Parser(Lexer(source).tokenize()).parse().items
            .filterIsInstance<TopLevel.Impl>()
            .flatMap { it.methods }
            .single { it.name == name }

    private fun impl(vararg members: String): String =
        "impl A {\n" + members.joinToString("\n") { "    $it" } + "\n}"

    @Test fun eachBorrowHasItsOwnShorthand() {
        val source = impl(
            "func x[self&]() {}",
            "func y[self!]() {}",
            "func c[self]() {}",
        )

        assertEquals(ParamModifier.SHARED, member(source, "x").receiverModifier)
        assertEquals(ParamModifier.EXCLUSIVE, member(source, "y").receiverModifier)
        assertEquals(ParamModifier.NONE, member(source, "c").receiverModifier)

        for (name in listOf("x", "y", "c")) {
            assertEquals("self", member(source, name).receiverName, name)
            assertTrue(member(source, name).declaresReceiver, "$name declares a receiver")
        }
    }

    @Test fun theShortAndLongSpellingsAgree() {
        val short = impl("func x[self&]() {}", "func y[self!]() {}")
        val long = impl("func x[self: Self&]() {}", "func y[self: Self!]() {}")

        for (name in listOf("x", "y")) {
            assertEquals(
                member(long, name).receiverModifier,
                member(short, name).receiverModifier,
                name,
            )
        }
    }

    @Test fun aReceiverTheMemberDoesNotUseNeedsNoName() {
        val source = impl("func x[&]() {}", "func y[!]() {}")

        assertEquals("_", member(source, "x").receiverName)
        assertEquals(ParamModifier.SHARED, member(source, "x").receiverModifier)
        assertEquals("_", member(source, "y").receiverName)
        assertEquals(ParamModifier.EXCLUSIVE, member(source, "y").receiverModifier)
    }

    @Test fun aPropertyTakesTheShorthandToo() {
        val source = impl("prop isEmpty[self&]: Bool = self.size == 0")
        assertEquals(ParamModifier.SHARED, member(source, "isEmpty").receiverModifier)
        assertEquals("self", member(source, "isEmpty").receiverName)
    }

    @Test fun furtherReceiversStillNameThemselves() {
        val source = impl("func x[self&, scale: Double&]() {}")
        val x = member(source, "x")

        assertEquals(ParamModifier.SHARED, x.receiverModifier)
        assertEquals("self", x.receiverName)
        assertEquals(listOf("scale"), x.params.take(x.contextualParams).map { it.name })
    }

    @Test fun onlySelfMayLeaveOutItsType() {
        val message = assertFailsWith<Exception> {
            member(impl("func x[other&]() {}"), "x")
        }.message.orEmpty()
        assertTrue("must name its type" in message, message)
    }

    @Test fun theShorthandIsOnlyForBodiesThatKnowTheirSelf() {
        // A free function has no `Self` to leave out.
        val message = assertFailsWith<Exception> {
            Parser(Lexer("func x[self&]() {}").tokenize()).parse()
        }.message.orEmpty()
        assertTrue("must name its type" in message, message)
    }

    @Test fun aSpecMemberTakesTheShorthand() {
        // Inside a spec, `Self` is whatever implements it - as settled a
        // meaning as it has inside an `impl`.
        val spec = Parser(
            Lexer(
                """
                spec Map<K, V> {
                    prop size[self&]: Int
                    func firstKey[self&](): K?
                }
                """.trimIndent(),
            ).tokenize(),
        ).parse().items.filterIsInstance<TopLevel.Spec>().single()

        assertEquals(listOf("size", "firstKey"), spec.methods.map { it.name })
        assertTrue(spec.methods.all { it.receiverName == "self" }, "${spec.methods}")
        assertTrue(spec.methods.all { it.receiverModifier == ParamModifier.SHARED }, "${spec.methods}")
    }
}
