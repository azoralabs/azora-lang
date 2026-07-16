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

import org.azora.lang.backend.IrInterpreter
import org.azora.lang.backend.JavaScriptCodegen
import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.TopLevel
import org.azora.lang.ir.IrGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SerializationDeriverTest {
    private fun derive(body: String): SerializationDeriver.Result {
        val prelude = """
            deco Derive for .Deco {
                fin generator: String
                fin role: String
                fin provider: String = ""
                fin conversionProvider: String = ""
                fin providerModule: String = ""
                fin conversionModule: String = ""
            }
            @Derive(generator: "serializer", role: "all", provider: "std", conversionProvider: "std::convert")
            deco Serializable for .Pack {
                fin ignoreUnknownFields: Bool = false
                fin encodeDefaults: Bool = true
            }
            @Derive(generator: "serializer", role: "json", provider: "std", conversionProvider: "std::convert")
            deco JsonSerializable for .Pack {
                fin ignoreUnknownFields: Bool = false
                fin encodeDefaults: Bool = true
            }
            @Derive(generator: "serializer", role: "azon", provider: "std", conversionProvider: "std::convert")
            deco AzonSerializable for .Pack {
                fin ignoreUnknownFields: Bool = false
                fin encodeDefaults: Bool = true
            }
            @Derive(generator: "serializer", role: "name")
            deco SerialName for .Field { fin value: String = "" }
            @Derive(generator: "serializer", role: "ignore")
            deco SerialIgnore for .Field
            @Derive(generator: "serializer", role: "required")
            deco SerialRequired for .Field
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
            setOf("toSerialValue", "fromSerialValue", "toJson", "fromJson", "toAzon", "fromAzon"),
            generated.methods.mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test fun formatSpecificDecoratorsGenerateOnlyTheirTextFormat() {
        val json = derive("@JsonSerializable pack JsonUser { fin name: String = \"\" }")
        val azon = derive("@AzonSerializable pack AzonUser { fin name: String = \"\" }")

        assertTrue(json.errors.isEmpty(), json.errors.toString())
        assertTrue(azon.errors.isEmpty(), azon.errors.toString())
        assertEquals(
            setOf("toSerialValue", "fromSerialValue", "toJson", "fromJson"),
            json.program.items.filterIsInstance<TopLevel.Impl>().single { it.typeName == "JsonUser" }
                .methods.mapTo(mutableSetOf()) { it.name },
        )
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
            impl SerialName(value: "display_name") for User::name
            impl SerialIgnore for User::password
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(result.program.items.filterIsInstance<TopLevel.Impl>().any {
            it.typeName == "User" && it.methods.any { method -> method.name == "toSerialValue" }
        })
    }

    @Test fun ignoreAndRequiredOnOneFieldAreRejected() {
        val result = derive("""
            @Serializable pack User { fin password: String = "" }
            impl [SerialIgnore, SerialRequired] for User::password
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
            deco Derive for .Deco {
                fin generator: String
                fin role: String
                fin provider: String = ""
                fin conversionProvider: String = ""
                fin providerModule: String = ""
                fin conversionModule: String = ""
            }
            @Derive(generator: "serializer", role: "all", provider: "std", conversionProvider: "std::convert")
            deco WireModel for .Pack {
                fin ignoreUnknownFields: Bool = false
                fin encodeDefaults: Bool = true
            }
            @Derive(generator: "serializer", role: "name")
            deco WireKey for .Field { fin value: String = "" }
            @Derive(generator: "serializer", role: "ignore")
            deco SkipWire for .Field
            @Derive(generator: "serializer", role: "required")
            deco NeedWire for .Field

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

    @Test fun generatedPrimitiveCodecBodiesPassSemanticAnalysis() {
        val source = """
            deco Derive for .Deco {
                fin generator: String
                fin role: String
                fin provider: String = ""
                fin conversionProvider: String = ""
                fin providerModule: String = ""
                fin conversionModule: String = ""
            }
            @Derive(generator: "serializer", role: "all", provider: "std", conversionProvider: "std::convert")
            deco Serializable for .Pack {
                fin ignoreUnknownFields: Bool = false
                fin encodeDefaults: Bool = true
            }
            @Derive(generator: "serializer", role: "json", provider: "std", conversionProvider: "std::convert")
            deco JsonSerializable for .Pack {
                fin ignoreUnknownFields: Bool = false
                fin encodeDefaults: Bool = true
            }
            @Derive(generator: "serializer", role: "azon", provider: "std", conversionProvider: "std::convert")
            deco AzonSerializable for .Pack {
                fin ignoreUnknownFields: Bool = false
                fin encodeDefaults: Bool = true
            }
            @Derive(generator: "serializer", role: "name")
            deco SerialName for .Field { fin value: String = "" }
            @Derive(generator: "serializer", role: "ignore")
            deco SerialIgnore for .Field
            @Derive(generator: "serializer", role: "required")
            deco SerialRequired for .Field

            fail SerializationError {
                InvalidNumber,
                UnexpectedType,
                DuplicateField,
                MissingField,
                UnknownField
            }
            enum SerializationFormat {
                Json
                Azon
            }
            pack SerializerOptions
            pack List<T> {
                shield var data: T* = alloc T[16]
                shield var size: Int = 0
            }
            impl List<T> {
                func add(element: T): Unit { mut ref self ->
                    self.data[self.size] = element
                    self.size += 1
                }
            }
            impl oper[] for List<T> { ref self, index -> return self.data[index] }
            pack Set<T> {
                shield var data: T* = alloc T[16]
                shield var size: Int = 0
            }
            impl Set<T> {
                func add(element: T): Bool { mut ref self ->
                    self.data[self.size] = element
                    self.size += 1
                    return true
                }
            }
            impl oper[] for Set<T> { ref self, index -> return self.data[index] }
            pack Map<K, V> {
                shield var keysData: K* = alloc K[16]
                shield var valuesData: V* = alloc V[16]
                shield var size: Int = 0
            }
            impl Map<K, V> {
                func put(key: K, value: V): Unit { mut ref self ->
                    self.keysData[self.size] = key
                    self.valuesData[self.size] = value
                    self.size += 1
                }
                func keys(): [K] { ref self ->
                    var result = [].fill<K>(self.size)
                    var index = 0
                    while index < self.size {
                        result[index] = self.keysData[index]
                        index += 1
                    }
                    return result
                }
            }
            impl oper[] for Map<K, V> { ref self, key ->
                var index = 0
                while index < self.size {
                    if self.keysData[index] == key { return self.valuesData[index] }
                    index += 1
                }
                panic { "missing map key" }
            }
            pack SerialField {
                fin name: String
                fin value: SerialValue
            }
            slot SerialValue {
                Null
                Bool(Bool)
                Number(String)
                Text(String)
                Array(List<SerialValue>)
                Object(List<SerialField>)
            }

            func std__serialFieldAt(fields: ref List<SerialField>, index: Int): SerialField {
                return fields[index]
            }
            func std__serialFieldCount(fields: ref List<SerialField>): Int { return fields.size }
            func std__serialValueAt(values: ref List<SerialValue>, index: Int): SerialValue { return values[index] }
            func std__serialAsText(value: ref SerialValue): String!SerializationError {
                when value {
                    SerialValue.Text(text) -> { return text }
                    else -> { fail return .UnexpectedType }
                }
            }
            func std__serialAsBool(value: ref SerialValue): Bool!SerializationError {
                when value {
                    SerialValue.Bool(boolean) -> { return boolean }
                    else -> { fail return .UnexpectedType }
                }
            }
            func std__serialAsChar(value: ref SerialValue): Char!SerializationError { return 'x' }
            func std__serialAsLong(value: ref SerialValue): Long!SerializationError { return 7L }
            func std__serialAsInt(value: ref SerialValue): Int!SerializationError { return 7 }
            func std__serialAsReal(value: ref SerialValue): Real!SerializationError { return 0.0 }
            func std__convert__toString(value: Any): String { return "" }
            func std__encodeSerialValue(value: ref SerialValue, format: SerializationFormat, options: ref SerializerOptions): String!SerializationError { return "" }
            func std__decodeSerialValue(input: String, format: SerializationFormat, options: ref SerializerOptions): SerialValue!SerializationError { return SerialValue.Null }
            func std__io__println(value: Any): Unit {}

            @Serializable
            pack Address { fin city: String = "" }

            @Serializable(ignoreUnknownFields: false, encodeDefaults: false)
            pack User {
                @SerialName("display_name") fin name: String = ""
                fin age: Int = 0
                @SerialRequired fin enabled: Bool = true
                fin tags: List<String> = List()
                fin scores: Set<Int> = Set()
                fin metrics: Map<String, Int> = Map()
                fin nickname: String? = null
                fin address: Address = Address()
                @SerialIgnore fin password: String = ""
            }

            @Serializable(ignoreUnknownFields: true, encodeDefaults: true)
            pack LenientUser { fin name: String = "" }

            func main() {
                fin prototype = User()
                var tags = List()
                tags.add("compiler")
                var scores = Set()
                scores.add(7)
                var metrics = Map()
                metrics.put("builds", 7)
                fin value = User("Alice", 0, true, tags, scores, metrics, "ally", Address("Bucharest"), "secret")
                fin tree = prototype.toSerialValue(value) catch SerialValue.Null
                when tree {
                    SerialValue.Object(fields) -> {
                        std__io__println(std__serialFieldCount(fields))
                        fin first = std__serialFieldAt(fields, 0)
                        std__io__println(first.name)
                        when first.value {
                            SerialValue.Text(text) -> { std__io__println(text) }
                            else -> { std__io__println("wrong-value") }
                        }
                    }
                    else -> { std__io__println("wrong-tree") }
                }

                var validFields = List()
                validFields.add(SerialField("display_name", SerialValue.Text("Bob")))
                validFields.add(SerialField("age", SerialValue.Number("7")))
                validFields.add(SerialField("enabled", SerialValue.Bool(false)))
                var encodedTags = List()
                encodedTags.add(SerialValue.Text("language"))
                validFields.add(SerialField("tags", SerialValue.Array(encodedTags)))
                var encodedScores = List()
                encodedScores.add(SerialValue.Number("7"))
                validFields.add(SerialField("scores", SerialValue.Array(encodedScores)))
                var encodedMetrics = List()
                encodedMetrics.add(SerialField("builds", SerialValue.Number("7")))
                validFields.add(SerialField("metrics", SerialValue.Object(encodedMetrics)))
                validFields.add(SerialField("nickname", SerialValue.Text("Bobby")))
                var encodedAddress = List()
                encodedAddress.add(SerialField("city", SerialValue.Text("Cluj")))
                validFields.add(SerialField("address", SerialValue.Object(encodedAddress)))
                fin decoded = prototype.fromSerialValue(SerialValue.Object(validFields)) catch User("decode-error", -1, false, List(), Set(), Map(), null, Address(), "fallback")
                std__io__println(decoded.name)
                std__io__println(decoded.age)
                std__io__println(decoded.enabled)
                std__io__println(decoded.tags.size)
                std__io__println(decoded.scores.size)
                std__io__println(decoded.metrics.size)
                std__io__println(decoded.nickname)
                std__io__println(decoded.address.city)
                std__io__println(decoded.password)

                validFields.add(SerialField("extra", SerialValue.Text("no")))
                fin rejected = prototype.fromSerialValue(SerialValue.Object(validFields)) catch User("unknown-rejected", -1, false, List(), Set(), Map(), null, Address(), "fallback")
                std__io__println(rejected.name)

                var missingFields = List()
                missingFields.add(SerialField("display_name", SerialValue.Text("No flag")))
                fin missing = prototype.fromSerialValue(SerialValue.Object(missingFields)) catch User("required-missing", -1, false, List(), Set(), Map(), null, Address(), "fallback")
                std__io__println(missing.name)

                fin lenientPrototype = LenientUser()
                var lenientFields = List()
                lenientFields.add(SerialField("name", SerialValue.Text("Accepted")))
                lenientFields.add(SerialField("extra", SerialValue.Text("ignored")))
                fin lenient = lenientPrototype.fromSerialValue(SerialValue.Object(lenientFields)) catch LenientUser("lenient-error")
                std__io__println(lenient.name)
            }
        """.trimIndent()
        val parsed = Parser(Lexer(source).tokenize()).parse()
        val derived = SerializationDeriver.derive(parsed)
        assertTrue(derived.errors.isEmpty(), derived.errors.toString())

        val semantic = SemanticPipeline().analyze(derived.program)
        assertTrue(semantic.errors.isEmpty(), semantic.errors.toString())

        val ir = IrGenerator(semantic.symbolTable).generate(semantic.program)
        val output = IrInterpreter().interpret(ir)
        assertEquals(
            "7\ndisplay_name\nAlice\nBob\n7\nfalse\n1\n1\n1\nBobby\nCluj\n\nunknown-rejected\nrequired-missing\nAccepted",
            output,
        )
        assertTrue("User_toSerialValue" in JavaScriptCodegen().generate(ir))
        assertTrue("User_toSerialValue" in WasmCodegen().generate(ir))
        assertTrue("User_toSerialValue" in LlvmCodegen().generate(ir))
    }
}
