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
 * The declaration forms that finished off the stdlib's parse.
 *
 * Each replaces something the `::` static block used to say, or relaxes a list to
 * separate its elements the way every other list in the language does.
 */
class ImplAndDeriveFormsTest {

    private fun parse(source: String): List<TopLevel> =
        Parser(Lexer(source).tokenize()).parse().items

    private fun impls(source: String) = parse(source).filterIsInstance<TopLevel.Impl>()

    // -- a binding declared beside its type ---------------------------------

    @Test fun animplHoldsBindingsThatBelongToTheType() {
        // `impl Byte { inline fin minValue: Int = -128 }` - no receiver, so it
        // belongs to the type. It leaves mangled, as a receiver-free member does.
        val items = parse("impl Byte {\n    inline fin minValue: Int = -128\n    inline fin maxValue: Int = 127\n}")
        assertEquals(
            listOf("Byte__minValue", "Byte__maxValue"),
            items.filterIsInstance<TopLevel.InlineFin>().map { it.name },
        )
    }

    @Test fun aRuntimeBindingIsMangledToo() {
        val items = parse("impl Byte {\n    fin label: String = \"byte\"\n}")
        assertEquals(listOf("Byte__label"), items.filterIsInstance<TopLevel.FinDecl>().map { it.name })
    }

    @Test fun everyMutabilityIsAccepted() {
        val items = parse("impl Counter {\n    var seen: Int = 0\n    let name: String = \"c\"\n}")
        assertEquals(listOf("Counter__seen"), items.filterIsInstance<TopLevel.VarDecl>().map { it.name })
        assertEquals(listOf("Counter__name"), items.filterIsInstance<TopLevel.LetDecl>().map { it.name })
    }

    @Test fun theErrorStillNamesWhatAnImplTakes() {
        val e = assertFailsWith<IllegalStateException> { parse("impl Byte {\n    enum Nested { A }\n}") }
        assertTrue("in impl block" in e.message.orEmpty(), e.message.orEmpty())
        assertTrue("'fin'" in e.message.orEmpty(), "the message lists bindings too: ${e.message}")
    }

    @Test fun trailingStaticImplSyntaxIsRejected() {
        val e = assertFailsWith<IllegalStateException> {
            parse("impl ReflectedParam:: {\n    func make(): Int { return 1 }\n}")
        }
        assertTrue("trailing '::' implementation form was removed" in e.message.orEmpty(), e.message.orEmpty())
        assertTrue("impl ReflectedParam" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun aStaticTypeComputationLivesInTheSingleOrdinaryImpl() {
        val source = """
            impl ReflectedParam {
                prop &.name: String = "parameter"

                deepinline func<T> DefaultValueType(hasDefault: Bool): Type {
                    return when {
                        hasDefault => T
                        else => Nothing
                    }
                }
            }
        """.trimIndent()
        val program = Parser(Lexer(source).tokenize()).parse()
        assertEquals(1, program.items.filterIsInstance<TopLevel.Impl>().size)
        assertEquals(listOf("ReflectedParam__DefaultValueType"), program.typeFunctions.map { it.name })
    }

    @Test fun currentGenericFunctionsAndBridgeReceiverPropertiesParseTogether() {
        val items = parse("""
            func<T> identity(value: T): T { return value }

            impl ReflectedParam {
                bridge prop &.name: String
            }
        """.trimIndent())

        assertEquals(listOf("identity"), items.filterIsInstance<TopLevel.Func>().map { it.decl.name })
        val reflected = items.filterIsInstance<TopLevel.Impl>().single()
        assertEquals(listOf("name"), reflected.methods.map { it.name })
        assertEquals(ParamModifier.SHARED, reflected.methods.single().receiverModifier)
    }

    // -- one body, several types --------------------------------------------

    @Test fun aTupleTargetListGivesEachTypeTheBody() {
        // `impl (Byte, UByte) { … }` - written once, given to each.
        val items = parse("impl (Byte, UByte) {\n    inline fin sizeBytes: Int = 1\n}")
        assertEquals(
            listOf("Byte__sizeBytes", "UByte__sizeBytes"),
            items.filterIsInstance<TopLevel.InlineFin>().map { it.name },
        )
    }

    @Test fun aTupleTargetListRepeatsMethodsToo() {
        val impls = impls("impl (A, B) {\n    func &.f(): Int { return 1 }\n}")
        assertEquals(listOf("A", "B"), impls.map { it.typeName })
        assertTrue(impls.all { it.methods.map { m -> m.name } == listOf("f") })
    }

    @Test fun aTupleBeforeForStillNamesDecorators() {
        // The brace is what says the list named targets; a `for` says it named
        // decorators, and that reading is untouched.
        val impls = impls("derive (Debug, Display) for Point")
        assertEquals(listOf("Debug", "Display"), impls.map { it.traitName })
        assertTrue(impls.all { it.typeName == "Point" })
    }

    // -- direct spec --------------------------------------------------------

    @Test fun aDirectSpecMarksItsMembersAsBelongingToTheType() {
        val spec = parse("direct spec Number {\n    prop rank: Int\n}")
            .filterIsInstance<TopLevel.Spec>().single()
        assertEquals("Number", spec.name)
        assertTrue(spec.isDirect, "'direct' marks the spec type-side")
        assertTrue(!spec.isBridge, "'direct' is not 'bridge'")
    }

    @Test fun anOrdinarySpecIsNotDirect() {
        val spec = parse("spec Number {\n    prop rank: Int\n}")
            .filterIsInstance<TopLevel.Spec>().single()
        assertTrue(!spec.isDirect)
    }

    // -- test scope ---------------------------------------------------------

    @Test fun aTestScopeFlattensItsDeclarations() {
        // A test scope is a visibility rule, not a namespace, so it does not
        // mangle what it holds.
        val items = parse("test scope {\n    pack Fixture {\n        fin value: String = \"\"\n    }\n}")
        assertEquals(listOf("Fixture"), items.filterIsInstance<TopLevel.Pack>().map { it.name })
    }

    @Test fun aTestScopeDoesNotNest() {
        val e = assertFailsWith<IllegalStateException> {
            parse("test scope {\n    test scope {\n        fin x: Int = 1\n    }\n}")
        }
        assertTrue("cannot nest" in e.message.orEmpty(), e.message.orEmpty())
    }

    // -- derive -------------------------------------------------------------

    @Test fun aDeriveMayCarryTheDecoratorsValues() {
        // `derive Serializable(ignoreUnknownFields: true) for T` says what
        // `@Serializable(ignoreUnknownFields: true)` says when applied by name.
        val impl = impls(
            """
            derive Serializable(
                ignoreUnknownFields: true,
                encodeDefaults: false
            ) for Fixture
            """.trimIndent(),
        ).single()
        assertEquals("Serializable", impl.traitName)
        assertEquals("Fixture", impl.typeName)
        assertEquals(
            listOf("ignoreUnknownFields", "encodeDefaults"),
            impl.decoratorNamedArgs.map { it.first },
        )
    }

    @Test fun aDeriveWithValuesNeedsASingleSpec() {
        val e = assertFailsWith<IllegalStateException> {
            parse("derive (A, B)(x: 1) for Fixture")
        }
        assertTrue("single spec" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun aPlainDeriveIsUnchanged() {
        val impls = impls("derive (Clone, Copy) for (A, B)")
        assertEquals(4, impls.size)
    }

    @Test fun targetsMayBeSeparatedByNewlinesAlone() {
        val impls = impls(
            """
            derive (SerialName) for (
                Fixture::name
                Fixture::password
            )
            """.trimIndent(),
        )
        assertEquals(2, impls.size)
    }

    // -- a `derives` clause on the declaration itself ------------------------

    @Test fun aDeclarationCarriesItsOwnConformances() {
        val impls = impls("bridge pack Char derives (PartialEqual, Equal, Order, Hash)")
        assertEquals(listOf("PartialEqual", "Equal", "Order", "Hash"), impls.map { it.traitName })
        assertTrue(impls.all { it.typeName == "Char" })
    }

    @Test fun aLongDeclarationPutsTheClauseOnTheNextLine() {
        // Too long for one line, so the clause opens the next one at the
        // declaration's own indent. `std/primitive.az` writes every width so.
        val impls = impls(
            """
            bridge pack Int<N: UInt = 32>(__int)
            derives (Integer, SignedInteger, SignedNumber)
            """.trimIndent(),
        )
        assertEquals(listOf("Integer", "SignedInteger", "SignedNumber"), impls.map { it.traitName })
    }

}
