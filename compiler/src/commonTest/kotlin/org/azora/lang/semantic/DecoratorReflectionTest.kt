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

    @Test fun hasAnnotSelectsTrueAndFalseBranches() {
        val result = analyze("""
            annot Marker for .Pack
            @Marker pack Marked
            pack Plain

            func marked(): Int {
                inline if reflect<Marked>.hasAnnot<Marker> { return 1 } else { return 0 }
            }

            func plain(): Int {
                inline if reflect<Plain>.hasAnnot<Marker> { return 1 } else { return 0 }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1L, (returnedExpression(result, "marked") as Expr.IntLiteral).value)
        assertEquals(0L, (returnedExpression(result, "plain") as Expr.IntLiteral).value)
    }

    @Test fun hasAnnotResolvesInferredAndExplicitValueTypes() {
        val result = analyze("""
            annot Marker for .Pack
            @Marker pack Marked

            func inferred(): Int {
                fin value = Marked()
                inline if reflect<value>.hasAnnot<Marker> { return 1 } else { return 0 }
            }

            func explicit(value: Marked&): Int {
                inline if reflect<value>.hasAnnot<Marker> { return 2 } else { return 0 }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.none { !it.startsWith("warning:") }, result.errors.toString())
        assertEquals(1L, (returnedExpression(result, "inferred") as Expr.IntLiteral).value)
        assertEquals(2L, (returnedExpression(result, "explicit") as Expr.IntLiteral).value)
    }

    @Test fun hasAnnotRecognizesDeclarationTargets() {
        val result = analyze("""
            annot Seen for [.Func, .Prop, .Field, .Param]

            pack Box {
                @Seen fin value: Int
            }

            @Seen
            func read(input: @Seen Int): Int { return input }

            pack Counter {}
            impl Counter {
                @Seen
                prop answer[self: Self&]: Int { return 42 }
            }

            func declarations(): Int {
                inline if reflect<read>.hasAnnot<Seen> &&
                    reflect<Box::value>.hasAnnot<Seen> &&
                    reflect<read::input>.hasAnnot<Seen> &&
                    reflect<Counter::answer>.hasAnnot<Seen> {
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

    @Test fun hasAnnotIncludesTransitiveDecoratorBindings() {
        val result = analyze("""
            annot Marker for .Pack
            annot Wrapped for .Pack binds Marker
            @Wrapped pack Marked

            func transitive(): Int {
                inline if reflect<Marked>.hasAnnot<Marker> { return 1 } else { return 0 }
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

            func probe(flag: Bool): Int {
                fin value = Outer()
                if flag {
                    fin value = Inner()
                }
                inline if reflect<value>.hasAnnot<Marker> { return 1 } else { return 0 }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.none { !it.startsWith("warning:") }, result.errors.toString())
        assertEquals(0L, (returnedExpression(result, "probe") as Expr.IntLiteral).value)
    }

    @Test fun decoratorMetadataReadsNamedValues() {
        val result = analyze("""
            annot Config for .Pack {
                fin enabled: Bool = false
                fin label: String = "default"
            }
            @Config(enabled: true, label: "selected") pack Feature

            func configured(): String {
                inline if reflect<Feature>.annotMeta<Config>.enabled {
                    inline fin label = reflect<Feature>.annotMeta<Config>.label
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
                fin enabled: Bool = false
                fin label: String = "default"
            }
            @Config(true) pack Feature

            func enabled(): Int {
                inline if reflect<Feature>.annotMeta<Config>.enabled { return 1 } else { return 0 }
            }

            func label(): String {
                inline fin value = reflect<Feature>.annotMeta<Config>.label
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
                fin enabled: Bool = true
            }
            annot Wrapped for .Pack binds Config
            @Wrapped pack Feature

            func configured(): Int {
                inline if reflect<Feature>.annotMeta<Config>.enabled { return 1 } else { return 0 }
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1L, (returnedExpression(result, "configured") as Expr.IntLiteral).value)
    }

    @Test fun emptyDecoratorImplBodyParticipatesInReflection() {
        val result = analyze("""
            annot Config for .Pack {
                fin enabled: Bool = true
            }
            pack Feature
            impl Config for Feature {}

            func configured(): Int {
                inline if reflect<Feature>.hasAnnot<Config> && reflect<Feature>.annotMeta<Config>.enabled {
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
                fin value: String
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
                fin enabled: Bool = true
                fin label: String = "default"
            }
            pack NamedFeature
            impl Config(enabled: false, label: "named") for NamedFeature {}
            pack PositionalFeature
            impl Config(true, "positional") for PositionalFeature {}

            func named(): String {
                inline if !reflect<NamedFeature>.annotMeta<Config>.enabled {
                    inline fin label = reflect<NamedFeature>.annotMeta<Config>.label
                    return label
                } else {
                    return "wrong"
                }
            }

            func positional(): String {
                inline if reflect<PositionalFeature>.annotMeta<Config>.enabled {
                    inline fin label = reflect<PositionalFeature>.annotMeta<Config>.label
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
                fin value: String
            }
            pack Feature
            impl Name(value: "configured") for Feature {}

            func name(): String {
                inline fin value = reflect<Feature>.annotMeta<Name>.value
                return value
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("configured", (returnedExpression(result, "name") as Expr.StringLiteral).value)
    }

    @Test fun decoratorImplMetadataUsesDecoratorValidation() {
        val unknown = analyze("""
            annot Config for .Pack { fin enabled: Bool = true }
            pack Feature
            impl Config(missing: true) for Feature {}
            func main() {}
        """.trimIndent())
        assertTrue(unknown.errors.any { "has no field 'missing'" in it }, unknown.errors.toString())

        val duplicate = analyze("""
            annot Config for .Pack { fin enabled: Bool = true }
            pack Feature
            impl Config(true, enabled: false) for Feature {}
            func main() {}
        """.trimIndent())
        assertTrue(duplicate.errors.any { "'enabled' is assigned more than once" in it }, duplicate.errors.toString())

        val wrongType = analyze("""
            annot Config for .Pack { fin enabled: Bool = true }
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

            pack Direct { fin name: String = "" }
            impl First for Direct::name {}

            pack DecoratorGroup { fin name: String = "" }
            impl [First, Second] for DecoratorGroup::name {}

            pack TargetGroup { fin name: String = "", fin password: String = "" }
            impl First for [TargetGroup::name, TargetGroup::password] {}

            pack CrossProduct { fin name: String = "", fin password: String = "" }
            impl [First, Second] for [CrossProduct::name, CrossProduct::password] {}

            pack OneWildcard { fin name: String = "", fin password: String = "" }
            impl First for OneWildcard::* {}

            pack GroupWildcard { fin name: String = "", fin password: String = "" }
            impl [First, Second] for GroupWildcard::* {}

            func covered(): Int {
                inline if reflect<Direct::name>.hasAnnot<First> &&
                    reflect<DecoratorGroup::name>.hasAnnot<First> && reflect<DecoratorGroup::name>.hasAnnot<Second> &&
                    reflect<TargetGroup::name>.hasAnnot<First> && reflect<TargetGroup::password>.hasAnnot<First> &&
                    reflect<CrossProduct::name>.hasAnnot<First> && reflect<CrossProduct::name>.hasAnnot<Second> &&
                    reflect<CrossProduct::password>.hasAnnot<First> && reflect<CrossProduct::password>.hasAnnot<Second> &&
                    reflect<OneWildcard::name>.hasAnnot<First> && reflect<OneWildcard::password>.hasAnnot<First> &&
                    reflect<GroupWildcard::name>.hasAnnot<First> && reflect<GroupWildcard::name>.hasAnnot<Second> &&
                    reflect<GroupWildcard::password>.hasAnnot<First> && reflect<GroupWildcard::password>.hasAnnot<Second> {
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
                fin value: String = ""
            }
            pack User { fin name: String = "", fin password: String = "" }
            impl SerialName(value: "login") for User::name {}
            impl SerialName for User::password {}

            func configured(): String {
                inline fin value = reflect<User::name>.annotMeta<SerialName>.value
                return value
            }

            func defaulted(): String {
                inline fin value = reflect<User::password>.annotMeta<SerialName>.value
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
            pack User { fin name: String = "" }
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
            pack User { fin name: String = "" }
            impl PackOnly for User::name {}
            func main() {}
        """.trimIndent())
        assertTrue(wrongTarget.errors.any { "cannot target .Field" in it }, wrongTarget.errors.toString())

        val duplicate = analyze("""
            annot Marker for .Field
            pack User { fin name: String = "", fin password: String = "" }
            impl Marker for User::* {}
            impl Marker for User::name {}
            func main() {}
        """.trimIndent())
        assertTrue(duplicate.errors.any { "duplicate decorator 'Marker'" in it }, duplicate.errors.toString())
    }

    @Test fun reflectionPropertiesAreRejectedAtRuntime() {
        val hasAnnot = analyze("""
            annot Marker for .Pack
            @Marker pack Feature
            func probe(): Bool { return reflect<Feature>.hasAnnot<Marker> }
            func main() {}
        """.trimIndent())
        assertTrue(hasAnnot.errors.any { "hasAnnot" in it && "compile-time-only" in it }, hasAnnot.errors.toString())

        val metadata = analyze("""
            annot Config for .Pack { fin enabled: Bool = true }
            @Config pack Feature
            func probe(): Bool { return reflect<Feature>.annotMeta<Config>.enabled }
            func main() {}
        """.trimIndent())
        assertTrue(metadata.errors.any { "annotMeta" in it && "compile-time-only" in it }, metadata.errors.toString())
    }

    @Test fun reflectionRequiresKeywordAndDeclarationMemberSyntax() {
        val direct = assertFailsWith<IllegalStateException> {
            Parser(Lexer("""
                annot Marker for .Pack
                @Marker pack Feature
                func probe(): Int {
                    inline if Feature.hasAnnot<Marker> { return 1 } else { return 0 }
                }
            """.trimIndent()).tokenize()).parse()
        }
        assertTrue("requires an explicit reflect<receiver>" in direct.message.orEmpty(), direct.message)

        val dottedField = assertFailsWith<IllegalStateException> {
            Parser(Lexer("""
                annot Marker for .Field
                pack Feature { @Marker fin value: Int = 0 }
                func probe(): Int {
                    inline if (reflect<Feature>.value).hasAnnot<Marker> { return 1 } else { return 0 }
                }
            """.trimIndent()).tokenize()).parse()
        }
        assertTrue("members use '::'" in dottedField.message.orEmpty(), dottedField.message)

        val reflected = analyze("""
            annot Marker for [.Pack, .Field]
            @Marker pack Feature { @Marker fin value: Int = 0 }
            func probe(): Int {
                inline if reflect<Feature>.hasAnnot<Marker> &&
                    reflect<Feature::value>.hasAnnot<Marker> { return 1 } else { return 0 }
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
            func probe(): Int {
                inline if reflect<Feature>.hasAnnot<Missing> { return 1 } else { return 0 }
            }
            func main() {}
        """.trimIndent())
        assertTrue(unknown.errors.any { "unknown decorator 'Missing'" in it }, unknown.errors.toString())

        val absent = analyze("""
            annot Config for .Pack { fin enabled: Bool = true }
            pack Feature
            func probe(): Int {
                inline if reflect<Feature>.annotMeta<Config>.enabled { return 1 } else { return 0 }
            }
            func main() {}
        """.trimIndent())
        assertTrue(absent.errors.any { "decorator 'Config' is not applied" in it }, absent.errors.toString())

        val missingField = analyze("""
            annot Config for .Pack { fin enabled: Bool = true }
            @Config pack Feature
            func probe(): Int {
                inline if reflect<Feature>.annotMeta<Config>.missing { return 1 } else { return 0 }
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
            annot Config for .Pack { fin enabled: Bool = true }
            @Config(missing: true) pack Feature
            func main() {}
        """.trimIndent())
        assertTrue(unknown.errors.any { "has no field 'missing'" in it }, unknown.errors.toString())

        val duplicate = analyze("""
            annot Config for .Pack { fin enabled: Bool = true }
            @Config(true, enabled: false) pack Feature
            func main() {}
        """.trimIndent())
        assertTrue(duplicate.errors.any { "'enabled' is assigned more than once" in it }, duplicate.errors.toString())

        val tooMany = analyze("""
            annot Config for .Pack { fin enabled: Bool = true }
            @Config(true, false) pack Feature
            func main() {}
        """.trimIndent())
        assertTrue(tooMany.errors.any { "expects at most 1 argument" in it }, tooMany.errors.toString())
    }

    @Test fun decoratorApplicationsValidateRequiredFieldsAndLiteralTypes() {
        val missing = analyze("""
            annot Name for .Pack { fin value: String }
            @Name pack Feature
            func main() {}
        """.trimIndent())
        assertTrue(missing.errors.any { "requires field 'value'" in it }, missing.errors.toString())

        val wrongType = analyze("""
            annot Config for .Pack { fin enabled: Bool }
            @Config("yes") pack Feature
            func main() {}
        """.trimIndent())
        assertTrue(wrongType.errors.any { "field 'enabled' expects Bool" in it }, wrongType.errors.toString())
    }
}
