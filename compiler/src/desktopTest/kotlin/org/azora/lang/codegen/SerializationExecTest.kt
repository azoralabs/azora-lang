/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.semantic.SerializationDeriverTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A derived codec runs natively as it runs in the interpreter.
 *
 * A variant's payload slot is an erased word, and a concrete pack stored where
 * the variant declares a spec - `SerialValue.Object(fields)` with an
 * `ArrayList<SerialField>` for a `List<SerialField>` - went in unboxed. Read
 * back through the spec, it was dispatched as a box it was not: LLVM printed
 * `0` for the field count and an empty string for a decoded field, and the
 * full codec crashed.
 */
class SerializationExecTest {
    @Test fun aSpecTypedPayloadIsReadBackThroughItsSpec() {
        if (!LlvmExec.available) return
        val program = """
            import std.io
            import std.container.list
            variant enum V {
                Null
                Text(value: String)
                Items(values: List<Int>)
            }
            func make(): V {
                var xs = ArrayList<Int>()
                xs.add(4)
                xs.add(5)
                return V.Items(take xs)
            }
            func main() {
                when make() {
                    V.Items(values) -> { println(values.size) }
                    else -> { println("wrong") }
                }
            }
        """.trimIndent()
        for (optimized in listOf(false, true)) {
            assertEquals("2", LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    /**
     * Numbers are encoded as their digits natively too. LLVM had no `toString`
     * at all and wrote every number as the empty string - silently, since no
     * native test printed an encoded number.
     */
    @Test fun numbersEncodeAsTheirDigitsOnLlvm() {
        if (!LlvmExec.available) return
        val program = """
            import std.io
            import std.serializer
            @Serializable
            pack P {
                fin n: Int = 0
                fin x: Long = Long(0)
            }
            func main() {
                fin tree = P().toSerialValue(P(42, Long(7))) catch SerialValue.Null
                println(encodeSerialValue(tree, serializerOptions()) catch "err")
            }
        """.trimIndent()
        for (optimized in listOf(false, true)) {
            assertEquals("{\"n\":42,\"x\":7}", LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun aDerivedCodecRoundTripsOnLlvm() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(
                SerializationDeriverTest.serializedUsersOutput,
                LlvmExec.run(SerializationDeriverTest.serializedUsers, optimized),
                "optimized=$optimized",
            )
        }
    }

    @Test fun decodedCollectionsOutliveTheDecoderAndLeaveTheInputTreeUsable() {
        if (!LlvmExec.available) return
        val program = """
            import std.io
            import std.serializer
            import std.container.list
            import std.container.set
            import std.container.map
            @Serializable
            pack Values {
                fin items: List<Int> = []
                fin tags: Set<String> = []
                fin counts: Map<String, Int> = [:]
            }
            @Serializable
            pack RequiredValues {
                fin items: List<Int>
                fin tags: Set<String>
                fin counts: Map<String, Int>
            }
            func main() {
                fin codec = Values()
                fin text = "{\"items\":[4,5],\"tags\":[\"x\",\"y\"],\"counts\":{\"a\":7}}"
                fin tree = decodeSerialValue(text, serializerOptions()) catch SerialValue.Null
                fin populated = codec.fromSerialValue(tree) catch Values()
                println(populated.items[0] + populated.items[1])
                println(populated.tags.size)
                println(populated.counts["a"])
                println(encodeSerialValue(tree, serializerOptions()) catch "input-invalid")
                fin missing = codec.fromAzon("{}", serializerOptions()) catch Values()
                println(missing.items.size + missing.tags.size + missing.counts.size)
                fin requiredCodec = RequiredValues([], [], [:])
                fin required = requiredCodec.fromSerialValue(tree) catch RequiredValues([], [], [:])
                println(required.items.size + required.tags.size + required.counts.size)
            }
        """.trimIndent()
        for (optimized in listOf(false, true)) {
            assertEquals("9\n2\n7\n{\"items\":[4,5],\"tags\":[\"x\",\"y\"],\"counts\":{\"a\":7}}\n0\n5",
                LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }
}
