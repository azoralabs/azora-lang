package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The standard library ships AZON and nothing else. Any other format - JSON,
 * CBOR, a private wire format - belongs outside `std`, written by implementing
 * `Serializer<T>` and walking `SerialValue`.
 *
 * These tests check that claim rather than asserting it. Every program here is
 * ordinary user code: it imports `std.serializer` and reaches everything it
 * needs through the `` qualifier, exactly as a third-party library would.
 * If the derive machinery were reachable only from inside `std`, dropping JSON
 * would have removed a capability instead of relocating it.
 */
class ThirdPartyFormatTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        if (result is CompilationResult.Failure) {
            throw AssertionError("compilation failed:\n  " + result.errors.joinToString("\n  "))
        }
        return IrInterpreter().interpret((result as CompilationResult.Success).ir).trim()
    }

    /**
     * `Serializer<T>` implemented by hand, for a pack carrying no decorator at
     * all. Nothing here is generated: the codec builds and reads the value tree
     * itself, which is what a library owning a foreign type has to do.
     */
    @Test fun serializerSpecIsImplementableOutsideTheStandardLibrary() {
        val out = run(
            """
            import std.io
            import std.serializer

            pack Point {
                fin x: Int = 0
                fin y: Int = 0
            }

            pack PointSerializer

            impl Serializer<Point> for PointSerializer {
                func &.toSerialValue(value: Point&): SerialValue ?! SerializationError {
                    var fields = ArrayList<SerialField>()
                    fields.add(SerialField("x", try serialNumber(convert::toString(value.x))))
                    fields.add(SerialField("y", try serialNumber(convert::toString(value.y))))
                    return SerialValue.Object(fields)
                }

                func &.fromSerialValue(value: SerialValue&): Point ?! SerializationError {
                    fin x = try serialAsInt(try serialField(value, "x"))
                    fin y = try serialAsInt(try serialField(value, "y"))
                    return Point(x, y)
                }
            }

            func main() {
                fin codec = PointSerializer()
                fin tree = codec.toSerialValue(Point(3, 4)) catch SerialValue.Null
                println(encodeSerialValue(tree, serializerOptions()) catch "err")
                fin back = codec.fromSerialValue(tree) catch Point(0, 0)
                println(back.x + back.y)
            }
            """.trimIndent(),
        )
        assertEquals("{\"x\":3,\"y\":4}\n7", out)
    }

    /**
     * A worked third-party text format. `KV` is line-oriented `key=value`, so it
     * shares no grammar with AZON and cannot pass by accident. It round trips a
     * pack whose codec the compiler generated, which is the property that makes
     * the standard library's single format a floor rather than a ceiling.
     */
    @Test fun aThirdPartyTextFormatRoundTripsAGeneratedCodec() {
        val out = run(
            """
            import std.io
            import std.serializer

            @Serializable
            pack Session {
                fin user: String = ""
                fin attempts: Int = 0
            }

            pack Kv

            impl Kv {
                func &.encode(value: SerialValue&): String ?! SerializationError {
                    when value {
                        SerialValue.Object(fields) -> {
                            var text = ""
                            for i in 0..<fields.size {
                                fin field = fields[i]
                                text = text + field.name + "=" + try self.scalar(field.value) + "\n"
                            }
                            return text
                        }
                        else -> { return .UnexpectedType }
                    }
                }

                func &.scalar(value: SerialValue&): String ?! SerializationError {
                    when value {
                        SerialValue.Text(t) -> { return t }
                        SerialValue.Number(n) -> { return n }
                        else -> { return .UnexpectedType }
                    }
                }

                /** Reads `key=value` lines back into the format-independent tree. */
                func &.decode(input: String): SerialValue ?! SerializationError {
                    var fields = ArrayList<SerialField>()
                    var name = ""
                    var raw = ""
                    var onValue = false
                    for i in 0..<stringLength(input) {
                        fin c = charAt(input, i)
                        if c == '\n' {
                            if !onValue { return .InvalidSyntax }
                            fields.add(SerialField(name, self.node(raw)))
                            name = ""
                            raw = ""
                            onValue = false
                        } else if c == '=' && !onValue {
                            onValue = true
                        } else if onValue {
                            raw = raw + c
                        } else {
                            name = name + c
                        }
                    }
                    return SerialValue.Object(fields)
                }

                /** Digits are a number node; anything else is text. */
                func &.node(raw: String): SerialValue {
                    if stringLength(raw) == 0 { return SerialValue.Text(raw) }
                    for i in 0..<stringLength(raw) {
                        if !isDigit(charAt(raw, i)) { return SerialValue.Text(raw) }
                    }
                    return SerialValue.Number(raw)
                }
            }

            func main() {
                fin session = Session("ada", 3)
                fin kv = Kv()

                fin tree = session.toSerialValue(session) catch SerialValue.Null
                fin text = kv.encode(tree) catch "err"
                print(text)

                fin decoded = kv.decode(text) catch SerialValue.Null
                fin back = session.fromSerialValue(decoded) catch Session("", 0)
                println(back.user)
                println(back.attempts)
            }
            """.trimIndent(),
        )
        assertEquals("user=ada\nattempts=3\nada\n3", out)
    }

    /**
     * The field decorators are a property of the generated value tree, not of
     * AZON, so a third-party format inherits them untouched: `@SerialName`
     * renames, `@SerialIgnore` omits, and an omitted field keeps its default
     * when the tree is read back.
     */
    @Test fun fieldDecoratorsApplyToAThirdPartyFormatToo() {
        val out = run(
            """
            import std.io
            import std.serializer

            @Serializable(ignoreUnknownFields: true)
            pack Account {
                @SerialName("display_name")
                fin name: String = ""

                @SerialIgnore
                fin token: String = "unset"

                fin logins: Int = 0
            }

            pack Kv

            impl Kv {
                func &.encode(value: SerialValue&): String ?! SerializationError {
                    when value {
                        SerialValue.Object(fields) -> {
                            var text = ""
                            for i in 0..<fields.size {
                                fin field = fields[i]
                                when field.value {
                                    SerialValue.Text(t) -> { text = text + field.name + "=" + t + ";" }
                                    SerialValue.Number(n) -> { text = text + field.name + "=" + n + ";" }
                                    else -> { return .UnexpectedType }
                                }
                            }
                            return text
                        }
                        else -> { return .UnexpectedType }
                    }
                }
            }

            func main() {
                fin account = Account("Ada", "s3cret", 7)
                fin tree = account.toSerialValue(account) catch SerialValue.Null
                println(Kv().encode(tree) catch "err")

                // The ignored field is absent from the tree, so reading back
                // restores its declared default rather than the written value.
                fin back = account.fromSerialValue(tree) catch Account("", "", 0)
                println(back.token)
            }
            """.trimIndent(),
        )
        assertEquals("display_name=Ada;logins=7;\nunset", out)
    }
}
