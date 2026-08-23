package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A type declared inside a scope keeps its bare name and is reached from
 * outside as `Scope::Type`. Functions are scope-mangled, so `println`
 * arrives as `std__println` and resolves directly; types are not, so every
 * place that reads a qualified name has to map it back to the declaration.
 *
 * These are the positions where that used to fail. Each one blocked writing a
 * `Serializer<T>` outside the standard library, and each is a general hole:
 * `Compare.Less` was unspellable for the same reason.
 */
class ScopeQualifiedAccessTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        if (result is CompilationResult.Failure) {
            throw AssertionError("compilation failed:\n  " + result.errors.joinToString("\n  "))
        }
        return IrInterpreter().interpret((result as CompilationResult.Success).ir).trim()
    }

    @Test fun reflectionIsQualifiedFromOutsideItsScope() {
        val result = Compiler().compile(
            """
            import std.reflection
            inline fin visible = reflect<Int>.hasAnnot<Experimental>
            func main() {}
            """.trimIndent(),
        )
        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { "reflect" in it },
            "Bare reflect must point to its scope-qualified spelling: ${result.errors}",
        )
    }

    @Test fun enumCaseIsReachableThroughItsScope() {
        assertEquals(
            "less",
            run(
                """
                import std.io
                import std.traits
                func main() {
                    fin ordering = Compare.Less
                    when ordering {
                        Compare.Less -> { println("less") }
                        else -> { println("other") }
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun variantCaseConstructsAndMatchesThroughItsScope() {
        assertEquals(
            "3",
            run(
                """
                import std.io
                scope shapes { variant enum Kind { Sized(n: Int) Empty } }
                func main() {
                    fin kind = shapes::Kind.Sized(3)
                    when kind {
                        shapes::Kind.Sized(n) -> { println(n) }
                        else -> { println("empty") }
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun aScopedPackIsConstructedThroughItsScope() {
        assertEquals(
            "\"x\"",
            run(
                """
                import std.io
                import std.serializer
                func main() {
                    fin node = SerialValue.Text("x")
                    println(encodeSerialValue(node, serializerOptions()) catch "err")
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
                    var fields = ArrayList<SerialField>()
                    fields.add(SerialField("a", SerialValue.Null))
                    println(fields.size)
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * `?!` used to take a single identifier, so a function could not declare
     * that it fails with an error set owned by another scope - which every
     * `impl Serializer<T>` member has to do.
     */
    @Test fun failableTypeAcceptsAQualifiedErrorSet() {
        assertEquals(
            "7",
            run(
                """
                import std.io
                import std.serializer
                func seven(): Int ?! SerializationError {
                    return 7
                }
                func main() { println(seven() catch 0) }
                """.trimIndent(),
            ),
        )
    }

    /**
     * `impl Spec for Scope::Type` with a body. `Type::member` in the same
     * position is a decorator's field target, and the two share a spelling; a
     * member target is required to be bodyless, so the body settles it.
     */
    @Test fun implTargetIsReachableThroughItsScopeWithABody() {
        assertEquals(
            "16",
            run(
                """
                import std.io
                spec Area { func &.area(): Int }
                scope shapes { pack Square { fin side: Int = 0 } }
                impl Area for shapes::Square {
                    func &.area(): Int { return self.side * self.side }
                }
                func main() { println(shapes::Square(4).area()) }
                """.trimIndent(),
            ),
        )
    }

    /**
     * The same target with no body, where nothing structural settles it. It is
     * decided by what the names denote: `Square` is a declared type and
     * `shapes` has no member by that name, so the qualifier is a scope.
     */
    @Test fun bodylessDeriveTargetIsReachableThroughItsScope() {
        assertEquals(
            "eq",
            run(
                """
                import std.io
                import std.traits
                scope shapes { pack Square { fin side: Int = 0 } }
                derive [Equal] for shapes::Square
                func main() {
                    println(if shapes::Square(2) == shapes::Square(2) { "eq" } else { "ne" })
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
                @Serializable
                pack User { fin name: String = "" }
                impl SerialName(value: "display_name") for User::name {}
                func main() {
                    fin user = User("Ada")
                    fin tree = user.toSerialValue(user) catch SerialValue.Null
                    println(encodeSerialValue(tree, serializerOptions()) catch "err")
                }
                """.trimIndent(),
            ),
        )
    }

    /** `Type::*` is a wildcard over fields and never a scope-qualified type. */
    @Test fun wildcardFieldTargetIsStillAWildcard() {
        assertEquals(
            "{}",
            run(
                """
                import std.io
                import std.serializer
                @Serializable
                pack User { fin name: String = "" fin token: String = "t" }
                impl SerialIgnore for User::* {}
                func main() {
                    fin user = User("Ada", "t")
                    fin tree = user.toSerialValue(user) catch SerialValue.Null
                    println(encodeSerialValue(tree, serializerOptions()) catch "err")
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * A field whose name collides with a declared type still reads as a field:
     * the owner declares a member by that name, which the scope reading cannot
     * claim.
     */
    @Test fun aFieldNamedLikeATypeIsStillAFieldTarget() {
        assertEquals(
            "{}",
            run(
                """
                import std.io
                import std.serializer
                pack Square { fin side: Int = 0 }
                @Serializable
                pack Holder { fin Square: Int = 0 }
                impl SerialIgnore for Holder::Square {}
                func main() {
                    fin holder = Holder(2)
                    fin tree = holder.toSerialValue(holder) catch SerialValue.Null
                    println(encodeSerialValue(tree, serializerOptions()) catch "err")
                }
                """.trimIndent(),
            ),
        )
    }

    /** `impl Spec<T> for LocalType` - the spec is named through its scope. */
    @Test fun specIsImplementedThroughItsScope() {
        assertEquals(
            "ok",
            run(
                """
                import std.io
                scope caps { spec Named { func &.label(): String } }
                pack Tag
                impl caps::Named for Tag {
                    func &.label(): String { return "ok" }
                }
                func main() { println(Tag().label()) }
                """.trimIndent(),
            ),
        )
    }
}
