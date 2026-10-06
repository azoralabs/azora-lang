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
 * out: `&.member` and `!.member` declare read-only and mutable borrows;
 * `(self: Self&).member` spells the receiver name and type explicitly. Outside
 * one, a single receiver is written as its type (`Type&.member`) and several as
 * an unnamed group (`(A&, B&).member`, read as `self.0`, `self.1`).
 *
 * ```
 * impl A {
 *     func &.x() {}   // borrowed, read-only
 *     func !.y() {}   // borrowed, mutable
 *     func .c() {}    // owned, consuming
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
            "func &.x() {}",
            "func !.y() {}",
            "func .c() {}",
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
        val short = impl("func &.x() {}", "func !.y() {}", "func .c() {}")
        val long = impl("func (self: Self&).x() {}", "func (self: Self!).y() {}", "func (self: Self).c() {}")

        for (name in listOf("x", "y", "c")) {
            assertEquals(
                member(long, name).receiverModifier,
                member(short, name).receiverModifier,
                name,
            )
        }
    }

    /**
     * The owned receiver has three spellings, all kept (FUNCTIONS_DIP §5.2,
     * decided 2026-09-22): `.c`, `Self.c` and `(self: Self).c`.
     */
    @Test fun theOwnedSpellingsAgree() {
        val source = impl("func .c() {}", "func Self.d() {}", "func (self: Self).e() {}")
        for (name in listOf("c", "d", "e")) {
            val decl = member(source, name)
            assertEquals(ParamModifier.NONE, decl.receiverModifier, name)
            assertEquals("self", decl.receiverName, name)
            assertTrue(decl.declaresReceiver, "$name declares a receiver")
        }
    }

    @Test fun aReceiverTheMemberDoesNotUseNeedsNoName() {
        val source = impl("func &.x() {}", "func !.y() {}")

        assertEquals("self", member(source, "x").receiverName)
        assertEquals(ParamModifier.SHARED, member(source, "x").receiverModifier)
        assertEquals("self", member(source, "y").receiverName)
        assertEquals(ParamModifier.EXCLUSIVE, member(source, "y").receiverModifier)
    }

    @Test fun aPropertyTakesTheShorthandToo() {
        val source = impl("prop &.isEmpty: Bool = self.size == 0")
        assertEquals(ParamModifier.SHARED, member(source, "isEmpty").receiverModifier)
        assertEquals("self", member(source, "isEmpty").receiverName)
    }

    @Test fun furtherReceiversStillNameThemselves() {
        val source = impl("func (self: Self&, scale: Double&).x() {}")
        val x = member(source, "x")

        assertEquals(ParamModifier.SHARED, x.receiverModifier)
        assertEquals("self", x.receiverName)
        assertEquals(listOf("scale"), x.params.take(x.contextualParams).map { it.name })
    }

    @Test fun aFreeFunctionWritesItsReceiverAsAType() {
        val items = Parser(
            Lexer(
                """
                func Gauge&.read(): Int { return 1 }
                func Gauge!.reset() {}
                func Ticket.redeem() {}
                func<T> Box<T>.unwrap(): T { return take self.value }
                func<T> Box<List<T>>&.depth(): Int { return 2 }
                func (A&, B&).merge() {}
                """.trimIndent(),
            ).tokenize(),
        ).parse().items.filterIsInstance<TopLevel.Impl>()
        val byName = items.associate { impl -> impl.methods.single().name to impl }
        assertEquals(ParamModifier.SHARED, byName.getValue("read").methods.single().receiverModifier)
        assertEquals(ParamModifier.EXCLUSIVE, byName.getValue("reset").methods.single().receiverModifier)
        assertEquals(ParamModifier.NONE, byName.getValue("redeem").methods.single().receiverModifier)
        assertEquals("Box", byName.getValue("unwrap").typeName)
        assertEquals(listOf("T"), byName.getValue("unwrap").typeParams, "the receiver's arguments parameterise the impl")
        assertEquals(ParamModifier.NONE, byName.getValue("unwrap").methods.single().receiverModifier)
        assertEquals("Box", byName.getValue("depth").typeName)
        for (name in listOf("read", "reset", "redeem", "unwrap", "depth")) {
            assertEquals("self", byName.getValue(name).methods.single().receiverName, name)
        }
        assertEquals("__receiver0", byName.getValue("merge").methods.single().receiverName)
        assertEquals(1, byName.getValue("merge").methods.single().contextualParams)
    }

    @Test fun removedBracketReceiversHaveAMigrationDiagnostic() {
        val message = assertFailsWith<Exception> {
            member(impl("func x[other&]() {}"), "x")
        }.message.orEmpty()
        assertTrue("receivers no longer use brackets" in message, message)
        assertTrue("func &.x" in message && "func Type&.x" in message, message)
    }

    @Test fun theShorthandIsOnlyForBodiesThatKnowTheirSelf() {
        // A free function has no `Self` to leave out.
        val message = assertFailsWith<Exception> {
            Parser(Lexer("func &.x() {}").tokenize()).parse()
        }.message.orEmpty()
        assertTrue("require an impl or spec" in message && "func Type&.x()" in message, message)
    }

    @Test fun aSpecMemberTakesTheShorthand() {
        // Inside a spec, `Self` is whatever implements it - as settled a
        // meaning as it has inside an `impl`.
        val spec = Parser(
            Lexer(
                """
                spec Map<K, V> {
                    prop &.size: Int
                    func &.firstKey(): K?
                }
                """.trimIndent(),
            ).tokenize(),
        ).parse().items.filterIsInstance<TopLevel.Spec>().single()

        assertEquals(listOf("size", "firstKey"), spec.methods.map { it.name })
        assertTrue(spec.methods.all { it.receiverName == "self" }, "${spec.methods}")
        assertTrue(spec.methods.all { it.receiverModifier == ParamModifier.SHARED }, "${spec.methods}")
    }
}
