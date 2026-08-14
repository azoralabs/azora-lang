package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A type declared inside a realm keeps its bare name and is reached from
 * outside as `Realm::Type`. Functions are realm-mangled, so `std::println`
 * arrives as `std__println` and resolves directly; types are not, so every
 * place that reads a qualified name has to map it back to the declaration.
 *
 * These are the positions where that used to fail. Each one blocked writing a
 * `Serializer<T>` outside the standard library, and each is a general hole:
 * `std::Compare.Less` was unspellable for the same reason.
 */
class RealmQualifiedAccessTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        if (result is CompilationResult.Failure) {
            throw AssertionError("compilation failed:\n  " + result.errors.joinToString("\n  "))
        }
        return IrInterpreter().interpret((result as CompilationResult.Success).ir).trim()
    }

    @Test fun reflectionRequiresItsRealmOutsideTheRealm() {
        val result = Compiler().compile(
            """
            import std.reflection
            inline fin visible = reflect<std::Int>.hasAnnot<std::Experimental>
            func main() {}
            """.trimIndent(),
        )
        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { "std::reflect" in it },
            "Bare reflect must point to its realm-qualified spelling: ${result.errors}",
        )
    }

    @Test fun enumCaseIsReachableThroughItsRealm() {
        assertEquals(
            "less",
            run(
                """
                import std.io
                import std.traits
                func main() {
                    fin ordering = std::Compare.Less
                    when ordering {
                        std::Compare.Less -> { std::println("less") }
                        else -> { std::println("other") }
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun variantCaseConstructsAndMatchesThroughItsRealm() {
        assertEquals(
            "3",
            run(
                """
                import std.io
                realm shapes { variant enum Kind { Sized(n: std::Int) Empty } }
                func main() {
                    fin kind = shapes::Kind.Sized(3)
                    when kind {
                        shapes::Kind.Sized(n) -> { std::println(n) }
                        else -> { std::println("empty") }
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun realmScopedPackIsConstructedThroughItsRealm() {
        assertEquals(
            "\"x\"",
            run(
                """
                import std.io
                import std.serializer
                func main() {
                    fin node = std::SerialValue.Text("x")
                    std::println(std::encodeSerialValue(node, std::serializerOptions()) catch "err")
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun qualifiedTypeArgumentIsAcceptedInAGenericCall() {
        assertEquals(
            "1",
            run(
                """
                import std.io
                import std.serializer
                func main() {
                    var fields = std::ArrayList<std::SerialField>()
                    fields.add(std::SerialField("a", std::SerialValue.Null))
                    std::println(fields.size)
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * `?!` used to take a single identifier, so a function could not declare
     * that it fails with an error set owned by another realm - which every
     * `impl std::Serializer<T>` member has to do.
     */
    @Test fun failableTypeAcceptsAQualifiedErrorSet() {
        assertEquals(
            "7",
            run(
                """
                import std.io
                import std.serializer
                func seven(): std::Int ?! std::SerializationError {
                    return 7
                }
                func main() { std::println(seven() catch 0) }
                """.trimIndent(),
            ),
        )
    }

    /**
     * `impl Spec for Realm::Type` with a body. `Type::member` in the same
     * position is a decorator's field target, and the two share a spelling; a
     * member target is required to be bodyless, so the body settles it.
     */
    @Test fun implTargetIsReachableThroughItsRealmWithABody() {
        assertEquals(
            "16",
            run(
                """
                import std.io
                spec Area { func area[self: std::Self&](): std::Int }
                realm shapes { pack Square { fin side: std::Int = 0 } }
                impl Area for shapes::Square {
                    func area[self: std::Self&](): std::Int { return self.side * self.side }
                }
                func main() { std::println(shapes::Square(4).area()) }
                """.trimIndent(),
            ),
        )
    }

    /**
     * The same target with no body, where nothing structural settles it. It is
     * decided by what the names denote: `Square` is a declared type and
     * `shapes` has no member by that name, so the qualifier is a realm.
     */
    @Test fun bodylessDeriveTargetIsReachableThroughItsRealm() {
        assertEquals(
            "eq",
            run(
                """
                import std.io
                import std.traits
                realm shapes { pack Square { fin side: std::Int = 0 } }
                derive [std::Equal] for shapes::Square
                func main() {
                    std::println(if shapes::Square(2) == shapes::Square(2) { "eq" } else { "ne" })
                }
                """.trimIndent(),
            ),
        )
    }

    /** The field target keeps its meaning: `name` is a field, not a type. */
    @Test fun decoratorFieldTargetIsStillAFieldTarget() {
        assertEquals(
            "{\"display_name\":\"Ada\"}",
            run(
                """
                import std.io
                import std.serializer
                @std::Serializable
                pack User { fin name: std::String = "" }
                impl std::SerialName(value: "display_name") for User::name {}
                func main() {
                    fin user = User("Ada")
                    fin tree = user.toSerialValue(user) catch std::SerialValue.Null
                    std::println(std::encodeSerialValue(tree, std::serializerOptions()) catch "err")
                }
                """.trimIndent(),
            ),
        )
    }

    /** `Type::*` is a wildcard over fields and never a realm-qualified type. */
    @Test fun wildcardFieldTargetIsStillAWildcard() {
        assertEquals(
            "{}",
            run(
                """
                import std.io
                import std.serializer
                @std::Serializable
                pack User { fin name: std::String = "" fin token: std::String = "t" }
                impl std::SerialIgnore for User::* {}
                func main() {
                    fin user = User("Ada", "t")
                    fin tree = user.toSerialValue(user) catch std::SerialValue.Null
                    std::println(std::encodeSerialValue(tree, std::serializerOptions()) catch "err")
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * A field whose name collides with a declared type still reads as a field:
     * the owner declares a member by that name, which the realm reading cannot
     * claim.
     */
    @Test fun aFieldNamedLikeATypeIsStillAFieldTarget() {
        assertEquals(
            "{}",
            run(
                """
                import std.io
                import std.serializer
                pack Square { fin side: std::Int = 0 }
                @std::Serializable
                pack Holder { fin Square: std::Int = 0 }
                impl std::SerialIgnore for Holder::Square {}
                func main() {
                    fin holder = Holder(2)
                    fin tree = holder.toSerialValue(holder) catch std::SerialValue.Null
                    std::println(std::encodeSerialValue(tree, std::serializerOptions()) catch "err")
                }
                """.trimIndent(),
            ),
        )
    }

    /** `impl std::Spec<T> for LocalType` - the spec is named through its realm. */
    @Test fun specIsImplementedThroughItsRealm() {
        assertEquals(
            "ok",
            run(
                """
                import std.io
                realm caps { spec Named { func label[self: std::Self&](): std::String } }
                pack Tag
                impl caps::Named for Tag {
                    func label[self: std::Self&](): std::String { return "ok" }
                }
                func main() { std::println(Tag().label()) }
                """.trimIndent(),
            ),
        )
    }
}
