/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The operator head uses the same receiver grammar as `func`, without a member name. */
class OperatorReceiverSyntaxTest {
    private fun parse(source: String): Program = Parser(Lexer(source.trimIndent()).tokenize()).parse()

    @Test
    fun sharedAndMutableShorthandsWorkInsideSpecsAndImpls() {
        val program = parse(
            """
            spec Bitwise<Rhs, Out> {
                oper& &.(rhs: Rhs&): Out
                oper&= !.(rhs: Rhs&)
            }
            pack Bits { var value: Int }
            impl Bits {
                oper& &.(rhs: Bits&): Bits { return self }
                oper&= !.(rhs: Bits&) { self.value = self.value & rhs.value }
            }
            """,
        )

        val spec = program.items.filterIsInstance<TopLevel.Spec>().single()
        assertEquals(ParamModifier.SHARED, spec.methods.first { it.name == "oper&" }.receiverModifier)
        assertEquals(ParamModifier.EXCLUSIVE, spec.methods.first { it.name == "oper&=" }.receiverModifier)

        val impl = program.items.filterIsInstance<TopLevel.Impl>().single { it.typeName == "Bits" }
        assertEquals(ParamModifier.SHARED, impl.methods.first { it.name.startsWith("oper&") && !it.name.startsWith("oper&=") }.receiverModifier)
        assertEquals(ParamModifier.EXCLUSIVE, impl.methods.first { it.name.startsWith("oper&=") }.receiverModifier)
    }

    @Test
    fun aFreeOperatorNamesItsReceiverType() {
        val impl = parse(
            """
            pack Bits { var value: Int }
            oper& Bits&.(rhs: Bits&): Bits { return self }
            """,
        ).items.filterIsInstance<TopLevel.Impl>().single()

        assertEquals("Bits", impl.typeName)
        assertEquals(ParamModifier.SHARED, impl.methods.single().receiverModifier)
    }

    @Test
    fun anOwnedOperatorUsesTheDotShorthandInsideAnImpl() {
        val impl = parse(
            """
            pack Token { var value: Int }
            impl Token {
                oper- .(): Token { return self }
            }
            """,
        ).items.filterIsInstance<TopLevel.Impl>().single()

        assertEquals(ParamModifier.NONE, impl.methods.single().receiverModifier)
    }

    @Test
    fun everyOperatorWritesAParameterListEvenWhenItIsEmpty() {
        val removedForms = listOf(
            "bridge oper- Int&.: Int",
            "spec Negate {\n    oper- &.: Self\n}",
            "pack Number { var value: Int }\nimpl Number {\n    oper- &.: Number { return self }\n}",
        )

        for (source in removedForms) {
            val failure = assertFailsWith<IllegalStateException> { parse(source) }
            assertTrue("requires an explicit parameter list" in failure.message.orEmpty(), failure.message.orEmpty())
            assertTrue("write '()'" in failure.message.orEmpty(), failure.message.orEmpty())
        }

        val bridge = parse("bridge oper- Int&.(): Int")
        assertEquals(1, bridge.items.filterIsInstance<TopLevel.Impl>().size)
    }

    @Test
    fun bracketedOperatorReceiversAreMigrationErrors() {
        val failure = assertFailsWith<IllegalStateException> {
            parse("spec Bitwise<Rhs, Out> {\n    oper& [self: Self&](rhs: Rhs&): Out\n}")
        }
        assertTrue("operator receivers no longer use brackets" in failure.message.orEmpty())
        assertTrue("oper& &.(…)" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun indexOperatorDiagnosticsUseTheSourceSpelling() {
        val failure = assertFailsWith<IllegalStateException> {
            parse("pack Box { var value: Int }\nimpl Box {\n    oper[](index: Int): Int { return self.value }\n}")
        }
        assertTrue("oper[] &.(…)" in failure.message.orEmpty(), failure.message.orEmpty())
        assertTrue("operindex" !in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun aSpecOperatorCannotSilentlyOmitItsReceiver() {
        val failure = assertFailsWith<IllegalStateException> {
            parse("spec Negate {\n    oper-: Self\n}")
        }
        assertTrue("oper- &.(…)" in failure.message.orEmpty(), failure.message.orEmpty())
    }
}
