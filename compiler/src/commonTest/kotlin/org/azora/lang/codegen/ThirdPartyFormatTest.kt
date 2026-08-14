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
 * needs through the `std::` qualifier, exactly as a third-party library would.
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
                fin x: std::Int = 0
                fin y: std::Int = 0
            }

            pack PointSerializer

            impl std::Serializer<Point> for PointSerializer {
                func toSerialValue[self: std::Self&](value: Point&): std::SerialValue ?! std::SerializationError {
                    var fields = std::ArrayList<std::SerialField>()
                    fields.add(std::SerialField("x", try std::serialNumber(std::convert::toString(value.x))))
                    fields.add(std::SerialField("y", try std::serialNumber(std::convert::toString(value.y))))
                    return std::SerialValue.Object(fields)
                }

                func fromSerialValue[self: std::Self&](value: std::SerialValue&): Point ?! std::SerializationError {
                    fin x = try std::serialAsInt(try std::serialField(value, "x"))
                    fin y = try std::serialAsInt(try std::serialField(value, "y"))
                    return Point(x, y)
                }
            }

            func main() {
                fin codec = PointSerializer()
                fin tree = codec.toSerialValue(Point(3, 4)) catch std::SerialValue.Null
                std::println(std::encodeSerialValue(tree, std::serializerOptions()) catch "err")
                fin back = codec.fromSerialValue(tree) catch Point(0, 0)
                std::println(back.x + back.y)
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

            @std::Serializable
            pack Session {
                fin user: std::String = ""
                fin attempts: std::Int = 0
            }

            pack Kv

            impl Kv {
                func encode[self: std::Self&](value: std::SerialValue&): std::String ?! std::SerializationError {
                    when value {
                        std::SerialValue.Object(fields) -> {
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

                func scalar[self: std::Self&](value: std::SerialValue&): std::String ?! std::SerializationError {
                    when value {
                        std::SerialValue.Text(t) -> { return t }
                        std::SerialValue.Number(n) -> { return n }
                        else -> { return .UnexpectedType }
                    }
                }

                /** Reads `key=value` lines back into the format-independent tree. */
                func decode[self: std::Self&](input: std::String): std::SerialValue ?! std::SerializationError {
                    var fields = std::ArrayList<std::SerialField>()
                    var name = ""
                    var raw = ""
                    var onValue = false
                    for i in 0..<std::stringLength(input) {
                        fin c = std::charAt(input, i)
                        if c == '\n' {
                            if !onValue { return .InvalidSyntax }
                            fields.add(std::SerialField(name, self.node(raw)))
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
                    return std::SerialValue.Object(fields)
                }

                /** Digits are a number node; anything else is text. */
                func node[self: std::Self&](raw: std::String): std::SerialValue {
                    if std::stringLength(raw) == 0 { return std::SerialValue.Text(raw) }
                    for i in 0..<std::stringLength(raw) {
                        if !isDigit(std::charAt(raw, i)) { return std::SerialValue.Text(raw) }
                    }
                    return std::SerialValue.Number(raw)
                }
            }

            func main() {
                fin session = Session("ada", 3)
                fin kv = Kv()

                fin tree = session.toSerialValue(session) catch std::SerialValue.Null
                fin text = kv.encode(tree) catch "err"
                std::print(text)

                fin decoded = kv.decode(text) catch std::SerialValue.Null
                fin back = session.fromSerialValue(decoded) catch Session("", 0)
                std::println(back.user)
                std::println(back.attempts)
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

            @std::Serializable(ignoreUnknownFields: true)
            pack Account {
                @std::SerialName("display_name")
                fin name: std::String = ""

                @std::SerialIgnore
                fin token: std::String = "unset"

                fin logins: std::Int = 0
            }

            pack Kv

            impl Kv {
                func encode[self: std::Self&](value: std::SerialValue&): std::String ?! std::SerializationError {
                    when value {
                        std::SerialValue.Object(fields) -> {
                            var text = ""
                            for i in 0..<fields.size {
                                fin field = fields[i]
                                when field.value {
                                    std::SerialValue.Text(t) -> { text = text + field.name + "=" + t + ";" }
                                    std::SerialValue.Number(n) -> { text = text + field.name + "=" + n + ";" }
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
                fin tree = account.toSerialValue(account) catch std::SerialValue.Null
                std::println(Kv().encode(tree) catch "err")

                // The ignored field is absent from the tree, so reading back
                // restores its declared default rather than the written value.
                fin back = account.fromSerialValue(tree) catch Account("", "", 0)
                std::println(back.token)
            }
            """.trimIndent(),
        )
        assertEquals("display_name=Ada;logins=7;\nunset", out)
    }
}
