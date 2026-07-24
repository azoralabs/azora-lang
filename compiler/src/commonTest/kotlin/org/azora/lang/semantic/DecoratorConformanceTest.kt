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

package org.azora.lang.semantic

import org.azora.lang.frontend.AstValidator
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.TypeRef
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecoratorConformanceTest {
    private fun analyze(source: String): SemanticResult {
        val program = Parser(Lexer(source).tokenize()).parse()
        val validationErrors = AstValidator().validate(program)
        check(validationErrors.isEmpty()) { validationErrors.joinToString("\n") }
        return SemanticPipeline().analyze(program)
    }

    @Test fun bodylessDecoratorImplRecordsConformance() {
        val result = analyze("""
            deco Serializable {}
            pack UserId {
                fin value: Long
            }
            impl Serializable for UserId
            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(result.symbolTable.implements("UserId", "Serializable"))
        assertTrue(result.symbolTable.allConformances().single().isDecorator)
    }

    @Test fun decoratorImplBodyIsRejectedAndNotRecorded() {
        val result = analyze("""
            deco Serializable {}
            pack UserId {
                fin value: Long
            }
            impl Serializable for UserId {
                func generated(): Unit { self& ->
                    return
                }
            }
            func main() {}
        """.trimIndent())

        assertTrue(result.errors.any { "marker contract" in it && "without a body" in it }, result.errors.toString())
        assertFalse(result.symbolTable.implements("UserId", "Serializable"))
    }

    @Test fun duplicateDecoratorImplIsRejected() {
        val result = analyze("""
            deco Serializable {}
            pack UserId {
                fin value: Long
            }
            impl Serializable for UserId
            impl Serializable for UserId
            func main() {}
        """.trimIndent())

        assertTrue(result.errors.any { "duplicate decorator" in it }, result.errors.toString())
    }

    @Test fun appliedBoundDecoratorDerivesGenericSpecConformance() {
        val result = analyze("""
            @Experimental(sinceAzora: "0.0.4")
            deco Serializable bind Serializer {
                fin ignoreUnknownFields: Bool = false
                fin encodeDefaults: Bool = true
            }
            spec Serializer<T> {
                func encode(value: T&): String
            }
            @Serializable
            pack UserId {
                fin value: Long
            }
            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(result.symbolTable.implements("UserId", "Serializable"))
        assertTrue(
            result.symbolTable.implements("UserId", "Serializer", listOf(TypeRef.Named("UserId")))
        )
    }

    @Test fun decoratorImplAlsoDerivesBoundSpecConformance() {
        val result = analyze("""
            deco Serializable bind Serializer {}
            spec Serializer<T>
            pack UserId {
                fin value: Long
            }
            impl Serializable for UserId
            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(
            result.symbolTable.implements("UserId", "Serializer", listOf(TypeRef.Named("UserId")))
        )
    }

    @Test fun manualSerializerImplementationRecordsGenericConformance() {
        val result = analyze("""
            spec Serializer<T> {
                func encode(value: T&): String
                func decode(value: String): T
            }
            pack User {
                fin name: String
            }
            pack UserSerializer
            impl Serializer<User> for UserSerializer {
                func encode(value: User&): String { self& ->
                    return value.name
                }
                func decode(value: String): User { self& ->
                    return User(value)
                }
            }
            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(
            result.symbolTable.implements(
                "UserSerializer",
                "Serializer",
                listOf(TypeRef.Named("User")),
            )
        )
    }

    @Test fun ordinarySpecImplRejectsDecoratorMetadataValues() {
        val result = analyze("""
            spec Serializer<T>
            pack User
            pack UserSerializer
            impl Serializer<User>(enabled: true) for UserSerializer
            func main() {}
        """.trimIndent())

        assertTrue(
            result.errors.any { "implementation values are only allowed for decorators" in it },
            result.errors.toString(),
        )
        assertFalse(result.symbolTable.implements("UserSerializer", "Serializer", listOf(TypeRef.Named("User"))))
    }

    @Test fun ordinarySpecImplRejectsMemberSelectors() {
        val result = analyze("""
            spec Readable
            pack User { fin name: String = "" }
            impl Readable for User::name
            func main() {}
        """.trimIndent())

        assertTrue(
            result.errors.any { "member and wildcard implementation targets are only allowed for decorators" in it },
            result.errors.toString(),
        )
        assertFalse(result.symbolTable.implements("User.name", "Readable"))
    }

    @Test fun decoratorImplDerivesEverySerializerContract() {
        val result = analyze("""
            spec Serializer<T>
            spec JsonSerializer<T>
            spec AzonSerializer<T>
            deco Serializable for .Pack bind [Serializer, JsonSerializer, AzonSerializer] {
                fin ignoreUnknownFields: Bool = false
                fin encodeDefaults: Bool = true
            }
            pack User
            impl Serializable for User
            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val user = listOf(TypeRef.Named("User"))
        assertTrue(result.symbolTable.implements("User", "Serializable"))
        assertTrue(result.symbolTable.implements("User", "Serializer", user))
        assertTrue(result.symbolTable.implements("User", "JsonSerializer", user))
        assertTrue(result.symbolTable.implements("User", "AzonSerializer", user))
    }

    @Test fun genericTargetAndTrailingBindingArgumentsArePreserved() {
        val result = analyze("""
            spec Codec<T, Format>
            deco Json bind Codec<String> {}
            @Json
            pack<T> Box {
                fin value: T
            }
            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val boxOfT = TypeRef.Named("Box", listOf(TypeRef.Named("T")))
        assertTrue(
            result.symbolTable.implements("Box", "Codec", listOf(boxOfT, TypeRef.Named("String")))
        )
    }

    @Test fun boundDecoratorRequiresMatchingGenericArity() {
        val result = analyze("""
            spec Codec<T, Format>
            deco Serializable bind Codec {}
            @Serializable
            pack UserId {
                fin value: Long
            }
            func main() {}
        """.trimIndent())

        assertTrue(result.errors.any { "expects 1 trailing type argument" in it }, result.errors.toString())
        assertFalse(result.symbolTable.implements("UserId", "Codec"))
    }

    @Test fun boundDecoratorRejectsUnknownSpec() {
        val result = analyze("""
            deco Serializable bind MissingSpec {}
            @Serializable
            pack UserId {
                fin value: Long
            }
            func main() {}
        """.trimIndent())

        assertTrue(result.errors.any { "binds unknown spec or decorator 'MissingSpec'" in it }, result.errors.toString())
    }

    @Test fun unusedDecoratorBindingIsStillValidated() {
        val result = analyze("""
            deco Broken bind MissingSpec {}
            func main() {}
        """.trimIndent())

        assertTrue(result.errors.any { "decorator 'Broken' binds unknown spec or decorator 'MissingSpec'" in it }, result.errors.toString())
    }
}
