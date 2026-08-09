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
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DecoratorReflectionTest {
    private fun analyze(source: String): SemanticResult {
        val program = Parser(Lexer(source).tokenize()).parse()
        val validationErrors = AstValidator().validate(program)
        check(validationErrors.isEmpty()) { validationErrors.joinToString("\n") }
        return SemanticPipeline().analyze(program)
    }

    private fun returnedExpression(result: SemanticResult, functionName: String): Expr {
        val function = result.program.items.filterIsInstance<TopLevel.Func>()
            .single { it.decl.name == functionName }
        return function.decl.body.filterIsInstance<Stmt.Return>().single().value
            ?: error("Expected a return value")
    }

    @Test fun hasDecoSelectsTrueAndFalseBranches() {
        val result = analyze("""
            annot Marker for .Pack
            @Marker pack Marked
            pack Plain

            func marked(): std::Int {
                inline if std::reflect<Marked>.hasDeco<Marker> { return 1 } else { return 0 }
            }

            func plain(): std::Int {
                inline if std::reflect<Plain>.hasDeco<Marker> { return 1 } else { return 0 }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1L, (returnedExpression(result, "marked") as Expr.IntLiteral).value)
        assertEquals(0L, (returnedExpression(result, "plain") as Expr.IntLiteral).value)
    }

    @Test fun hasDecoResolvesInferredAndExplicitValueTypes() {
        val result = analyze("""
            annot Marker for .Pack
            @Marker pack Marked

            func inferred(): std::Int {
                fin value = Marked()
                inline if std::reflect<value>.hasDeco<Marker> { return 1 } else { return 0 }
            }

            func explicit(value: Marked&): std::Int {
                inline if std::reflect<value>.hasDeco<Marker> { return 2 } else { return 0 }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.none { !it.startsWith("warning:") }, result.errors.toString())
        assertEquals(1L, (returnedExpression(result, "inferred") as Expr.IntLiteral).value)
        assertEquals(2L, (returnedExpression(result, "explicit") as Expr.IntLiteral).value)
    }

    @Test fun hasDecoRecognizesDeclarationTargets() {
        val result = analyze("""
            annot Seen for [.Func, .Prop, .Field, .Param]

            pack Box {
                @Seen fin value: std::Int
            }

            @Seen
            func read(input: @Seen std::Int): std::Int { return input }

            pack Counter {}
            impl Counter {
                @Seen
                prop answer[self: std::Self&]: std::Int { return 42 }
            }

            func declarations(): std::Int {
                inline if std::reflect<read>.hasDeco<Seen> &&
                    std::reflect<Box::value>.hasDeco<Seen> &&
                    std::reflect<read::input>.hasDeco<Seen> &&
                    std::reflect<Counter::answer>.hasDeco<Seen> {
                    return 1
                } else {
                    return 0
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1L, (returnedExpression(result, "declarations") as Expr.IntLiteral).value)
    }

    @Test fun hasDecoIncludesTransitiveDecoratorBindings() {
        val result = analyze("""
            annot Marker for .Pack
            annot Wrapped for .Pack binds Marker
            @Wrapped pack Marked

            func transitive(): std::Int {
                inline if std::reflect<Marked>.hasDeco<Marker> { return 1 } else { return 0 }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1L, (returnedExpression(result, "transitive") as Expr.IntLiteral).value)
    }

    @Test fun reflectionTypeBindingsRespectLexicalShadowing() {
        val result = analyze("""
            annot Marker for .Pack
            @Marker pack Inner
            pack Outer

            func probe(flag: std::Bool): std::Int {
                fin value = Outer()
                if flag {
                    fin value = Inner()
                }
                inline if std::reflect<value>.hasDeco<Marker> { return 1 } else { return 0 }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.none { !it.startsWith("warning:") }, result.errors.toString())
        assertEquals(0L, (returnedExpression(result, "probe") as Expr.IntLiteral).value)
    }

    @Test fun decoratorMetadataReadsNamedValues() {
        val result = analyze("""
            annot Config for .Pack {
                fin enabled: std::Bool = false
                fin label: std::String = "default"
            }
            @Config(enabled: true, label: "selected") pack Feature

            func configured(): std::String {
                inline if std::reflect<Feature>.annotMeta<Config>.enabled {
                    inline fin label = std::reflect<Feature>.annotMeta<Config>.label
                    return label
                } else {
                    return "disabled"
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("selected", (returnedExpression(result, "configured") as Expr.StringLiteral).value)
    }

    @Test fun decoratorMetadataReadsPositionalAndDefaultValues() {
        val result = analyze("""
            annot Config for .Pack {
                fin enabled: std::Bool = false
                fin label: std::String = "default"
            }
            @Config(true) pack Feature

            func enabled(): std::Int {
                inline if std::reflect<Feature>.annotMeta<Config>.enabled { return 1 } else { return 0 }
            }

            func label(): std::String {
                inline fin value = std::reflect<Feature>.annotMeta<Config>.label
                return value
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1L, (returnedExpression(result, "enabled") as Expr.IntLiteral).value)
        assertEquals("default", (returnedExpression(result, "label") as Expr.StringLiteral).value)
    }

    @Test fun transitiveDecoratorMetadataUsesBoundDecoratorDefaults() {
        val result = analyze("""
            annot Config for .Pack {
                fin enabled: std::Bool = true
            }
            annot Wrapped for .Pack binds Config
            @Wrapped pack Feature

            func configured(): std::Int {
                inline if std::reflect<Feature>.annotMeta<Config>.enabled { return 1 } else { return 0 }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1L, (returnedExpression(result, "configured") as Expr.IntLiteral).value)
    }

    @Test fun emptyDecoratorImplBodyParticipatesInReflection() {
        val result = analyze("""
            annot Config for .Pack {
                fin enabled: std::Bool = true
            }
            pack Feature
            impl Config for Feature {}

            func configured(): std::Int {
                inline if std::reflect<Feature>.hasDeco<Config> && std::reflect<Feature>.annotMeta<Config>.enabled {
                    return 1
                } else {
                    return 0
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1L, (returnedExpression(result, "configured") as Expr.IntLiteral).value)
    }

    @Test fun emptyDecoratorImplBodyRequiresDefaultMetadata() {
        val result = analyze("""
            annot Name for .Pack {
                fin value: std::String
            }
            pack Feature
            impl Name for Feature {}
            func main() {}
        """.trimIndent())

        assertTrue(
            result.errors.any { "requires field 'value'" in it },
            result.errors.toString(),
        )
    }

    @Test fun decoratorImplAcceptsNamedAndPositionalMetadata() {
        val result = analyze("""
            annot Config for .Pack {
                fin enabled: std::Bool = true
                fin label: std::String = "default"
            }
            pack NamedFeature
            impl Config(enabled: false, label: "named") for NamedFeature {}
            pack PositionalFeature
            impl Config(true, "positional") for PositionalFeature {}

            func named(): std::String {
                inline if !std::reflect<NamedFeature>.annotMeta<Config>.enabled {
                    inline fin label = std::reflect<NamedFeature>.annotMeta<Config>.label
                    return label
                } else {
                    return "wrong"
                }
            }

            func positional(): std::String {
                inline if std::reflect<PositionalFeature>.annotMeta<Config>.enabled {
                    inline fin label = std::reflect<PositionalFeature>.annotMeta<Config>.label
                    return label
                } else {
                    return "wrong"
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("named", (returnedExpression(result, "named") as Expr.StringLiteral).value)
        assertEquals("positional", (returnedExpression(result, "positional") as Expr.StringLiteral).value)
    }

    @Test fun decoratorImplCanSupplyRequiredMetadata() {
        val result = analyze("""
            annot Name for .Pack {
                fin value: std::String
            }
            pack Feature
            impl Name(value: "configured") for Feature {}

            func name(): std::String {
                inline fin value = std::reflect<Feature>.annotMeta<Name>.value
                return value
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("configured", (returnedExpression(result, "name") as Expr.StringLiteral).value)
    }

    @Test fun decoratorImplMetadataUsesDecoratorValidation() {
        val unknown = analyze("""
            annot Config for .Pack { fin enabled: std::Bool = true }
            pack Feature
            impl Config(missing: true) for Feature {}
            func main() {}
        """.trimIndent())
        assertTrue(unknown.errors.any { "has no field 'missing'" in it }, unknown.errors.toString())

        val duplicate = analyze("""
            annot Config for .Pack { fin enabled: std::Bool = true }
            pack Feature
            impl Config(true, enabled: false) for Feature {}
            func main() {}
        """.trimIndent())
        assertTrue(duplicate.errors.any { "'enabled' is assigned more than once" in it }, duplicate.errors.toString())

        val wrongType = analyze("""
            annot Config for .Pack { fin enabled: std::Bool = true }
            pack Feature
            impl Config(enabled: "yes") for Feature {}
            func main() {}
        """.trimIndent())
        assertTrue(wrongType.errors.any { "field 'enabled' expects Bool" in it }, wrongType.errors.toString())
    }

    @Test fun decoratorImplSupportsFieldListsDecoratorListsAndWildcards() {
        val result = analyze("""
            annot First for .Field
            annot Second for .Field

            pack Direct { fin name: std::String = "" }
            impl First for Direct::name {}

            pack DecoratorGroup { fin name: std::String = "" }
            impl [First, Second] for DecoratorGroup::name {}

            pack TargetGroup { fin name: std::String = "", fin password: std::String = "" }
            impl First for [TargetGroup::name, TargetGroup::password] {}

            pack CrossProduct { fin name: std::String = "", fin password: std::String = "" }
            impl [First, Second] for [CrossProduct::name, CrossProduct::password] {}

            pack OneWildcard { fin name: std::String = "", fin password: std::String = "" }
            impl First for OneWildcard::* {}

            pack GroupWildcard { fin name: std::String = "", fin password: std::String = "" }
            impl [First, Second] for GroupWildcard::* {}

            func covered(): std::Int {
                inline if std::reflect<Direct::name>.hasDeco<First> &&
                    std::reflect<DecoratorGroup::name>.hasDeco<First> && std::reflect<DecoratorGroup::name>.hasDeco<Second> &&
                    std::reflect<TargetGroup::name>.hasDeco<First> && std::reflect<TargetGroup::password>.hasDeco<First> &&
                    std::reflect<CrossProduct::name>.hasDeco<First> && std::reflect<CrossProduct::name>.hasDeco<Second> &&
                    std::reflect<CrossProduct::password>.hasDeco<First> && std::reflect<CrossProduct::password>.hasDeco<Second> &&
                    std::reflect<OneWildcard::name>.hasDeco<First> && std::reflect<OneWildcard::password>.hasDeco<First> &&
                    std::reflect<GroupWildcard::name>.hasDeco<First> && std::reflect<GroupWildcard::name>.hasDeco<Second> &&
                    std::reflect<GroupWildcard::password>.hasDeco<First> && std::reflect<GroupWildcard::password>.hasDeco<Second> {
                    return 1
                } else {
                    return 0
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1L, (returnedExpression(result, "covered") as Expr.IntLiteral).value)
    }

    @Test fun fieldDecoratorImplPreservesConfiguredAndDefaultMetadata() {
        val result = analyze("""
            annot SerialName for .Field {
                fin value: std::String = ""
            }
            pack User { fin name: std::String = "", fin password: std::String = "" }
            impl SerialName(value: "login") for User::name {}
            impl SerialName for User::password {}

            func configured(): std::String {
                inline fin value = std::reflect<User::name>.annotMeta<SerialName>.value
                return value
            }

            func defaulted(): std::String {
                inline fin value = std::reflect<User::password>.annotMeta<SerialName>.value
                return value
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("login", (returnedExpression(result, "configured") as Expr.StringLiteral).value)
        assertEquals("", (returnedExpression(result, "defaulted") as Expr.StringLiteral).value)
    }

    @Test fun fieldDecoratorImplRejectsUnknownInvalidAndDuplicateSelectors() {
        val unknownField = analyze("""
            annot Marker for .Field
            pack User { fin name: std::String = "" }
            impl Marker for User::missing {}
            func main() {}
        """.trimIndent())
        assertTrue(unknownField.errors.any { "unknown target 'User::missing'" in it }, unknownField.errors.toString())

        val unknownWildcardOwner = analyze("""
            annot Marker for .Field
            impl Marker for Missing::* {}
            func main() {}
        """.trimIndent())
        assertTrue(
            unknownWildcardOwner.errors.any { "wildcard 'Missing::*' requires a declared pack" in it },
            unknownWildcardOwner.errors.toString(),
        )

        val wrongTarget = analyze("""
            annot PackOnly for .Pack
            pack User { fin name: std::String = "" }
            impl PackOnly for User::name {}
            func main() {}
        """.trimIndent())
        assertTrue(wrongTarget.errors.any { "cannot target .Field" in it }, wrongTarget.errors.toString())

        val duplicate = analyze("""
            annot Marker for .Field
            pack User { fin name: std::String = "", fin password: std::String = "" }
            impl Marker for User::* {}
            impl Marker for User::name {}
            func main() {}
        """.trimIndent())
        assertTrue(duplicate.errors.any { "duplicate decorator 'Marker'" in it }, duplicate.errors.toString())
    }

    @Test fun reflectionPropertiesAreRejectedAtRuntime() {
        val hasDeco = analyze("""
            annot Marker for .Pack
            @Marker pack Feature
            func probe(): std::Bool { return std::reflect<Feature>.hasDeco<Marker> }
            func main() {}
        """.trimIndent())
        assertTrue(hasDeco.errors.any { "hasDeco" in it && "compile-time-only" in it }, hasDeco.errors.toString())

        val metadata = analyze("""
            annot Config for .Pack { fin enabled: std::Bool = true }
            @Config pack Feature
            func probe(): std::Bool { return std::reflect<Feature>.annotMeta<Config>.enabled }
            func main() {}
        """.trimIndent())
        assertTrue(metadata.errors.any { "annotMeta" in it && "compile-time-only" in it }, metadata.errors.toString())
    }

    @Test fun reflectionRequiresKeywordAndDeclarationMemberSyntax() {
        val direct = assertFailsWith<IllegalStateException> {
            Parser(Lexer("""
                annot Marker for .Pack
                @Marker pack Feature
                func probe(): std::Int {
                    inline if Feature.hasDeco<Marker> { return 1 } else { return 0 }
                }
            """.trimIndent()).tokenize()).parse()
        }
        assertTrue("requires an explicit std::reflect<receiver>" in direct.message.orEmpty(), direct.message)

        val dottedField = assertFailsWith<IllegalStateException> {
            Parser(Lexer("""
                annot Marker for .Field
                pack Feature { @Marker fin value: std::Int = 0 }
                func probe(): std::Int {
                    inline if (std::reflect<Feature>.value).hasDeco<Marker> { return 1 } else { return 0 }
                }
            """.trimIndent()).tokenize()).parse()
        }
        assertTrue("members use '::'" in dottedField.message.orEmpty(), dottedField.message)

        val reflected = analyze("""
            annot Marker for [.Pack, .Field]
            @Marker pack Feature { @Marker fin value: std::Int = 0 }
            func probe(): std::Int {
                inline if std::reflect<Feature>.hasDeco<Marker> &&
                    std::reflect<Feature::value>.hasDeco<Marker> { return 1 } else { return 0 }
            }
            func main() {}
        """.trimIndent())
        assertTrue(reflected.errors.isEmpty(), reflected.errors.toString())
    }

    @Test fun decoratorNamesMustStartWithUppercaseLetter() {
        val declaration = assertFailsWith<IllegalStateException> {
            Parser(Lexer("annot marker for .Pack\npack Feature").tokenize()).parse()
        }
        assertTrue("must start with an uppercase letter" in declaration.message.orEmpty(), declaration.message)

        val application = assertFailsWith<IllegalStateException> {
            Parser(Lexer("annot Marker for .Pack\n@marker pack Feature").tokenize()).parse()
        }
        assertTrue("must start with an uppercase letter" in application.message.orEmpty(), application.message)
    }

    @Test fun reflectionReportsUnknownAndMissingMetadata() {
        val unknown = analyze("""
            pack Feature
            func probe(): std::Int {
                inline if std::reflect<Feature>.hasDeco<Missing> { return 1 } else { return 0 }
            }
            func main() {}
        """.trimIndent())
        assertTrue(unknown.errors.any { "unknown decorator 'Missing'" in it }, unknown.errors.toString())

        val absent = analyze("""
            annot Config for .Pack { fin enabled: std::Bool = true }
            pack Feature
            func probe(): std::Int {
                inline if std::reflect<Feature>.annotMeta<Config>.enabled { return 1 } else { return 0 }
            }
            func main() {}
        """.trimIndent())
        assertTrue(absent.errors.any { "decorator 'Config' is not applied" in it }, absent.errors.toString())

        val missingField = analyze("""
            annot Config for .Pack { fin enabled: std::Bool = true }
            @Config pack Feature
            func probe(): std::Int {
                inline if std::reflect<Feature>.annotMeta<Config>.missing { return 1 } else { return 0 }
            }
            func main() {}
        """.trimIndent())
        assertTrue(missingField.errors.any { "has no field 'missing'" in it }, missingField.errors.toString())
    }

    @Test fun decoratorFieldsMustBeExplicitlyFin() {
        for (field in listOf(
            "var enabled: Bool = true",
            "let enabled: Bool = true",
            "enabled: Bool = true",
        )) {
            val error = assertFailsWith<IllegalStateException> {
                Parser(Lexer("annot Config { $field }\nfunc main() {}").tokenize()).parse()
            }
            assertTrue("must be declared with 'fin'" in error.message.orEmpty(), error.message)
        }

        val decorator = Parser(Lexer("annot Config { fin enabled: Bool = true }\nfunc main() {}").tokenize())
            .parse().items.filterIsInstance<TopLevel.Deco>().single()
        assertEquals("enabled", decorator.fields.single().name)
        assertTrue(!decorator.fields.single().mutable)
    }

    @Test fun decoratorApplicationsValidateFieldAssignments() {
        val unknown = analyze("""
            annot Config for .Pack { fin enabled: std::Bool = true }
            @Config(missing: true) pack Feature
            func main() {}
        """.trimIndent())
        assertTrue(unknown.errors.any { "has no field 'missing'" in it }, unknown.errors.toString())

        val duplicate = analyze("""
            annot Config for .Pack { fin enabled: std::Bool = true }
            @Config(true, enabled: false) pack Feature
            func main() {}
        """.trimIndent())
        assertTrue(duplicate.errors.any { "'enabled' is assigned more than once" in it }, duplicate.errors.toString())

        val tooMany = analyze("""
            annot Config for .Pack { fin enabled: std::Bool = true }
            @Config(true, false) pack Feature
            func main() {}
        """.trimIndent())
        assertTrue(tooMany.errors.any { "expects at most 1 argument" in it }, tooMany.errors.toString())
    }

    @Test fun decoratorApplicationsValidateRequiredFieldsAndLiteralTypes() {
        val missing = analyze("""
            annot Name for .Pack { fin value: std::String }
            @Name pack Feature
            func main() {}
        """.trimIndent())
        assertTrue(missing.errors.any { "requires field 'value'" in it }, missing.errors.toString())

        val wrongType = analyze("""
            annot Config for .Pack { fin enabled: std::Bool }
            @Config("yes") pack Feature
            func main() {}
        """.trimIndent())
        assertTrue(wrongType.errors.any { "field 'enabled' expects Bool" in it }, wrongType.errors.toString())
    }
}
