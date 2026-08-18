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
 * All four case-carrying declarations take type parameters.
 *
 * ```
 * enum Color<T> { … }
 * variant enum Option<T> { Some(T); None }
 * error IoError<T> { … }
 * variant error ParseError<T> { Bad(T) }
 * ```
 *
 * The parameters go after the name they parametrize, where every other
 * declaration puts them - a tagged union is generic over what its cases carry, and
 * `Option<T>` says so in the one place that already had to name the type.
 *
 * This is the parse. What the parameters *mean* to name resolution is the
 * semantic passes' business, and generics are erased before IR either way.
 */
class GenericEnumDeclTest {

    private fun parse(source: String): List<TopLevel> =
        Parser(Lexer(source).tokenize()).parse().items

    private fun enumDecl(source: String) = parse(source).filterIsInstance<TopLevel.Enum>().single()
    private fun slot(source: String) = parse(source).filterIsInstance<TopLevel.Slot>().single()
    private fun fail(source: String) = parse(source).filterIsInstance<TopLevel.Fail>().single()

    // -- variant enum -------------------------------------------------------

    @Test fun aVariantEnumIsGenericOverWhatItsCasesCarry() {
        // `std/core.az:85`.
        val option = slot("variant enum Option<T> {\n    Some(T)\n    None\n}")
        assertEquals("Option", option.name)
        assertEquals(listOf("T"), option.typeParams)
        assertEquals(listOf("Some", "None"), option.variants.map { it.name })
    }

    @Test fun aCasePayloadMayNameTheParameter() {
        val option = slot("variant enum Option<T> {\n    Some(T)\n    None\n}")
        val some = option.variants.first { it.name == "Some" }
        assertEquals("T", (some.payloadTypes.single() as? TypeRef.Named)?.name)
    }

    @Test fun aVariantEnumTakesSeveralParameters() {
        val result = slot("variant enum Either<L, R> {\n    Left(L)\n    Right(R)\n}")
        assertEquals(listOf("L", "R"), result.typeParams)
    }

    // -- variant error ------------------------------------------------------

    @Test fun aVariantErrorIsGenericToo() {
        val parseError = slot("variant error ParseError<T> {\n    Bad(T)\n    Eof\n}")
        assertEquals(listOf("T"), parseError.typeParams)
        assertTrue(parseError.isError, "'variant error' still declares an error")
    }

    // -- enum ---------------------------------------------------------------

    @Test fun aPlainEnumTakesParameters() {
        val tag = enumDecl("enum Tag<T> {\n    First\n    Second\n}")
        assertEquals(listOf("T"), tag.typeParams)
        assertEquals(listOf("First", "Second"), tag.variants)
    }

    // -- error set ----------------------------------------------------------

    @Test fun anErrorSetTakesParameters() {
        val io = fail("error IoError<T> {\n    NotFound\n    Denied\n}")
        assertEquals(listOf("T"), io.typeParams)
        assertEquals(listOf("NotFound", "Denied"), io.variants)
    }

    @Test fun anErrorSetStillRefusesAPayload() {
        // A payload separates the two error forms exactly as it separates the two
        // enum forms, and parameters do not change that: data goes in
        // `variant error`.
        val e = assertFailsWith<IllegalStateException> { parse("error IoError<T> {\n    NotFound(T)\n}") }
        assertTrue("variant error IoError" in e.message.orEmpty(), e.message.orEmpty())
    }

    // -- the ungeneric forms are unchanged ----------------------------------

    @Test fun aDeclarationWithoutParametersHasNone() {
        assertEquals(emptyList(), enumDecl("enum Color {\n    Red\n    Green\n}").typeParams)
        assertEquals(emptyList(), slot("variant enum Shape {\n    Dot\n}").typeParams)
        assertEquals(emptyList(), fail("error IoError {\n    NotFound\n}").typeParams)
    }

    @Test fun aPlainEnumStillRefusesAPayload() {
        // A payload is what separates the two enum forms, and the message still
        // names the one that carries data.
        val e = assertFailsWith<IllegalStateException> { parse("enum Tag<T> {\n    First(T)\n}") }
        assertTrue("variant enum Tag" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun variantIsStillAModifier() {
        val e = assertFailsWith<IllegalStateException> { parse("variant Option<T> {\n    Some(T)\n}") }
        assertTrue("'variant' is a modifier" in e.message.orEmpty(), e.message.orEmpty())
    }
}
