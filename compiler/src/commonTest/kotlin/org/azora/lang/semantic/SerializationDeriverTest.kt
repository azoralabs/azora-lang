/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.azora.lang.semantic

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.TopLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SerializationDeriverTest {
    private fun derive(body: String): SerializationDeriver.Result {
        val prelude = """
            annot @Derive for .Annot {
                fin generator: String
                fin role: String
                fin provider: String = ""
                fin conversionProvider: String = ""
                fin providerModule: String = ""
                fin conversionModule: String = ""
            }
            @Derive(generator: "serializer", role: "all", provider: "std", conversionProvider: "convert")
            annot @Serializable for .Pack {
                fin ignoreUnknownFields: Bool = false
                fin encodeDefaults: Bool = true
            }
            @Derive(generator: "serializer", role: "azon", provider: "std", conversionProvider: "convert")
            annot @AzonSerializable for .Pack {
                fin ignoreUnknownFields: Bool = false
                fin encodeDefaults: Bool = true
            }
            @Derive(generator: "serializer", role: "name")
            annot @SerialName for .Field { fin value: String = "" }
            @Derive(generator: "serializer", role: "ignore")
            annot @SerialIgnore for .Field
            @Derive(generator: "serializer", role: "required")
            annot @SerialRequired for .Field
        """.trimIndent()
        val program = Parser(Lexer("$prelude\n$body").tokenize()).parse()
        return SerializationDeriver.derive(program)
    }

    @Test fun serializableGeneratesEverySerializerMethod() {
        val result = derive("""
            @Serializable(ignoreUnknownFields: true, encodeDefaults: false)
            pack User {
                fin name: String = ""
                fin age: Int = 0
                fin enabled: Bool = true
            }
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val generated = result.program.items.filterIsInstance<TopLevel.Impl>()
            .single { it.typeName == "User" && it.methods.isNotEmpty() }
        assertEquals(
            setOf("toSerialValue", "fromSerialValue", "toAzon", "fromAzon"),
            generated.methods.mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test fun textOnlyDecoratorGeneratesTheSameMethodSet() {
        val azon = derive("@AzonSerializable pack AzonUser { fin name: String = \"\" }")

        assertTrue(azon.errors.isEmpty(), azon.errors.toString())
        assertEquals(
            setOf("toSerialValue", "fromSerialValue", "toAzon", "fromAzon"),
            azon.program.items.filterIsInstance<TopLevel.Impl>().single { it.typeName == "AzonUser" }
                .methods.mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test fun fieldImplementationMetadataParticipatesInDerivation() {
        val result = derive("""
            @Serializable pack User {
                fin name: String = ""
                fin password: String = ""
            }
            impl SerialName(value: "display_name") for User::name {}
            impl SerialIgnore for User::password {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(result.program.items.filterIsInstance<TopLevel.Impl>().any {
            it.typeName == "User" && it.methods.any { method -> method.name == "toSerialValue" }
        })
    }

    @Test fun ignoreAndRequiredOnOneFieldAreRejected() {
        val result = derive("""
            @Serializable pack User { fin password: String = "" }
            impl SerialIgnore for User::password {}
            impl SerialRequired for User::password {}
        """.trimIndent())

        assertTrue(result.errors.any { "both SerialIgnore and SerialRequired" in it }, result.errors.toString())
    }

    @Test fun ignoredFieldWithoutDefaultIsRejected() {
        val result = derive("""
            @Serializable pack User { @SerialIgnore fin password: String }
        """.trimIndent())

        assertTrue(result.errors.any { "ignored field 'User::password' requires a default" in it }, result.errors.toString())
    }

    @Test fun duplicateWireNamesAreRejected() {
        val result = derive("""
            @Serializable pack User {
                @SerialName("value") fin first: String = ""
                @SerialName("value") fin second: String = ""
            }
        """.trimIndent())

        assertTrue(result.errors.any { "share wire name 'value'" in it }, result.errors.toString())
    }

    @Test fun nonSerializableNestedFieldIsRejected() {
        val result = derive("""
            pack Address { fin city: String = "" }
            @Serializable pack User { fin address: Address = Address() }
        """.trimIndent())

        assertTrue(result.errors.any { "non-serializable type 'Address'" in it }, result.errors.toString())
    }

    @Test fun nestedSerializablePackIsAccepted() {
        val result = derive("""
            @Serializable pack Address { fin city: String = "" }
            @Serializable pack User { fin address: Address = Address() }
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(2, result.program.items.filterIsInstance<TopLevel.Impl>().count { it.methods.isNotEmpty() })
    }

    @Test fun deriveRolesDoNotDependOnDecoratorNames() {
        val source = """
            annot @Derive for .Annot {
                fin generator: String
                fin role: String
                fin provider: String = ""
                fin conversionProvider: String = ""
                fin providerModule: String = ""
                fin conversionModule: String = ""
            }
            @Derive(generator: "serializer", role: "all", provider: "std", conversionProvider: "convert")
            annot @WireModel for .Pack {
                fin ignoreUnknownFields: Bool = false
                fin encodeDefaults: Bool = true
            }
            @Derive(generator: "serializer", role: "name")
            annot @WireKey for .Field { fin value: String = "" }
            @Derive(generator: "serializer", role: "ignore")
            annot @SkipWire for .Field
            @Derive(generator: "serializer", role: "required")
            annot @NeedWire for .Field

            @WireModel pack User {
                @WireKey("display_name") fin name: String = ""
                @SkipWire fin password: String = ""
            }
        """.trimIndent()
        val parsed = Parser(Lexer(source).tokenize()).parse()
        val result = SerializationDeriver.derive(parsed)

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(result.program.items.filterIsInstance<TopLevel.Impl>().any {
            it.typeName == "User" && it.methods.any { method -> method.name == "toSerialValue" }
        })
    }

    /**
     * The codec the deriver writes for every kind of field - text, numbers,
     * flags, lists, sets, maps, optionals, nested packs, and renamed, required
     * and ignored fields - compiles against the real `std.serializer` and
     * round-trips.
     *
     * This ran against a hand-written imitation of the library, which the
     * generator outgrew; against the real one, a `Set` field indexed a type with
     * no subscript, a `Map` field read `.length`, decoding constructed the `List`
     * spec, and `encodeDefaults: false` compared a nested pack that has no `==`.
     */
    @Test fun generatedPrimitiveCodecBodiesPassSemanticAnalysis() {
        val result = Compiler().compile(serializedUsers, release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(serializedUsersOutput, IrInterpreter().interpret(result.ir).trimEnd())
        assertTrue("User_toSerialValue" in result.llvm, "LLVM lowers the generated codec")
    }

    companion object {
        /** Exercises each field kind the serializer generates code for. */
        val serializedUsers = """
            import std.io
            import std.serializer
            import std.container.list
            import std.container.set
            import std.container.map

            @Serializable
            pack Address { fin city: String = "" }

            @Serializable(ignoreUnknownFields: false, encodeDefaults: false)
            pack User {
                @SerialName("display_name") fin name: String = ""
                fin age: Int = 0
                @SerialRequired fin enabled: Bool = true
                fin tags: List<String> = []
                fin scores: Set<Int> = []
                fin metrics: Map<String, Int> = [:]
                fin nickname: String? = null
                fin address: Address = Address()
                @SerialIgnore fin password: String = ""
            }

            @Serializable(ignoreUnknownFields: true, encodeDefaults: true)
            pack LenientUser { fin name: String = "" }

            func fallback(name: String): User = User(name, -1, false, [], [], [:], null, Address(), "fallback")

            func main() {
                fin prototype = User()
                var tags: MutableList<String> = []
                tags.add("compiler")
                var scores: MutableSet<Int> = []
                scores.add(7)
                var metrics: MutableMap<String, Int> = ["builds": 7]
                fin value = User("Alice", 0, true, tags, scores, metrics, "ally", Address("Bucharest"), "secret")
                fin tree = prototype.toSerialValue(value) catch SerialValue.Null
                when tree {
                    SerialValue.Object(fields) -> {
                        println(fields.size)
                        fin first = fields[0]
                        println(first.name)
                        when first.value {
                            SerialValue.Text(text) -> { println(text) }
                            else -> { println("wrong-value") }
                        }
                    }
                    else -> { println("wrong-tree") }
                }

                var validFields: MutableList<SerialField> = []
                validFields.add(SerialField("display_name", SerialValue.Text("Bob")))
                validFields.add(SerialField("age", SerialValue.Number("7")))
                validFields.add(SerialField("enabled", SerialValue.Bool(false)))
                var encodedTags: MutableList<SerialValue> = []
                encodedTags.add(SerialValue.Text("language"))
                validFields.add(SerialField("tags", SerialValue.Array(encodedTags)))
                var encodedScores: MutableList<SerialValue> = []
                encodedScores.add(SerialValue.Number("7"))
                validFields.add(SerialField("scores", SerialValue.Array(encodedScores)))
                var encodedMetrics: MutableList<SerialField> = []
                encodedMetrics.add(SerialField("builds", SerialValue.Number("7")))
                validFields.add(SerialField("metrics", SerialValue.Object(encodedMetrics)))
                validFields.add(SerialField("nickname", SerialValue.Text("Bobby")))
                var encodedAddress: MutableList<SerialField> = []
                encodedAddress.add(SerialField("city", SerialValue.Text("Cluj")))
                validFields.add(SerialField("address", SerialValue.Object(encodedAddress)))
                fin decoded = prototype.fromSerialValue(SerialValue.Object(validFields)) catch fallback("decode-error")
                println(decoded.name)
                println(decoded.age)
                println(decoded.enabled)
                println(decoded.tags.size)
                println(decoded.scores.size)
                println(decoded.metrics.size)
                println(decoded.nickname)
                println(decoded.address.city)
                println(decoded.password)

                validFields.add(SerialField("extra", SerialValue.Text("no")))
                fin rejected = prototype.fromSerialValue(SerialValue.Object(validFields)) catch fallback("unknown-rejected")
                println(rejected.name)

                var missingFields: MutableList<SerialField> = []
                missingFields.add(SerialField("display_name", SerialValue.Text("No flag")))
                fin missing = prototype.fromSerialValue(SerialValue.Object(missingFields)) catch fallback("required-missing")
                println(missing.name)

                fin lenientPrototype = LenientUser()
                var lenientFields: MutableList<SerialField> = []
                lenientFields.add(SerialField("name", SerialValue.Text("Accepted")))
                lenientFields.add(SerialField("extra", SerialValue.Text("ignored")))
                fin lenient = lenientPrototype.fromSerialValue(SerialValue.Object(lenientFields)) catch LenientUser("lenient-error")
                println(lenient.name)
            }
        """.trimIndent()

        const val serializedUsersOutput =
            "7\ndisplay_name\nAlice\nBob\n7\nfalse\n1\n1\n1\nBobby\nCluj\n\nunknown-rejected\nrequired-missing\nAccepted"
    }
}
