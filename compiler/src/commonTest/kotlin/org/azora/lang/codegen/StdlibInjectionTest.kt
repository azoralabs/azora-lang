package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.LibrarySource
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.AstValidator
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.TopLevel
import org.azora.lang.ir.IrTopLevel
import org.azora.lang.ir.IrType
import org.azora.lang.stdlib.AzStdlib
import org.azora.lang.stdlib.StdlibInjector
import org.azora.lang.semantic.SerializationDeriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [org.azora.lang.stdlib.StdlibInjector] under the realm/import model:
 *
 * - Library symbols live in realms (`realm std { ... }`) and are
 *   name-mangled (`std.math::abs` → `std__math__abs`).
 * - `use std.math` makes that module's symbols visible; references must use
 *   the qualified `Realm::name` form. Bare references are rejected.
 * - Qualified access without the matching import is rejected.
 */
class StdlibInjectionTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir)
    }

    // ---- qualified math access (requires import) ----

    @Test fun qualifiedMathFunctionsWork() =
        assertEquals("5\n7", run("import std.io\nimport std.math\nfunc main() {\n    std::println(std::abs(-5))\n    std::println(std::abs(7))\n}"))

    @Test fun printWritesWithoutNewline() =
        assertEquals("Hello, 7!", run("import std.io\nfunc main() {\n    std::print(\"Hello, \" )\n    std::print(7)\n    std::println(\"!\")\n}"))

    @Test fun stdlibRealmMemberCallsSiblingBare() {
        val result = Compiler().compile("import std.io\nfunc main() {\n    std::header(\"Title\", 4)\n}")
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
    }

    @Test fun printlnIsDeclaredAsCompilerBridge() {
        val source = AzStdlib.sources.single { Regex("(?m)^module std\\.io$").containsMatchIn(it) }
        val io = Parser(Lexer(source).tokenize()).parse()

        assertTrue(io.items.none { it is TopLevel.Func && it.decl.name == "std__println" })
        assertTrue(io.items.any {
            it is TopLevel.Bridge &&
                it.target == "Compiler" &&
                it.funcs.singleOrNull()?.name == "std__println"
        })
        assertTrue(io.items.any {
            it is TopLevel.Bridge &&
                it.target == "Compiler" &&
                it.funcs.singleOrNull()?.name == "std__print"
        })
    }

    @Test fun qualifiedMinMaxWork() =
        assertEquals("2\n9", run("import std.io\nimport std.math\nfunc main() {\n    std::println(std::min(2, 9))\n    std::println(std::max(2, 9))\n}"))

    @Test fun qualifiedFloorCeilRound() =
        assertEquals("3\n4\n4", run("import std.io\nimport std.math\nfunc main() {\n    std::println(std::floor(3.7))\n    std::println(std::ceil(3.2))\n    std::println(std::round(3.6))\n}"))

    @Test fun qualifiedFactorialGcd() =
        assertEquals("120\n6", run("import std.io\nimport std.math\nfunc main() {\n    std::println(std::factorial(5))\n    std::println(std::gcd(54, 24))\n}"))

    @Test fun qualifiedConstantInjects() {
        val out = run("import std.io\nimport std.math\nfunc main() {\n    std::println(std::PI)\n}")
        assertTrue(out.startsWith("3.14159"), out)
    }

    // ---- transitive + shadowing ----

    @Test fun transitiveStdlibCallsResolve() {
        // std::lcm uses std::gcd internally - both must inject.
        assertEquals("36", run("import std.io\nimport std.math\nfunc main() {\n    std::println(std::lcm(12, 18))\n}"))
    }

    @Test fun userDefinitionShadowsStdlib() =
        assertEquals("99", run("import std.io\nimport std.math\nfunc abs(x: Int): Int {\n    return 99\n}\nfunc main() {\n    std::println(abs(-5))\n}"))

    @Test fun programsWithoutStdlibAreUntouched() {
        val result = Compiler().compile("func main() {\n    var x = 1\n}")
        assertIs<CompilationResult.Success>(result)
        assertEquals(listOf("main"), result.ir.functions.map { it.name })
    }

    @Test fun rootModuleContainsCompilerPredefinedDeclarations() {
        val source = AzStdlib.sources.single { "module std.core" in it }
        val root = Parser(Lexer(source).tokenize()).parse()

        assertTrue(root.items.any { it is TopLevel.Pack && it.name == "Unit" && it.isBridge })
        assertTrue(root.items.any { it is TopLevel.Pack && it.name == "Any" && it.isBridge })
        assertTrue(root.items.any { it is TopLevel.Enum && it.name == "DecoTarget" })
        assertTrue(root.items.any { it is TopLevel.Enum && it.name == "TestMethod" })
        assertTrue(root.items.any { it is TopLevel.Enum && it.name == "BridgeTarget" })
        assertTrue(root.items.any { it is TopLevel.Spec && it.name == "HasDeco" })
        assertTrue(root.items.any { it is TopLevel.Spec && it.name == "DecoMetadata" })
        assertTrue(root.items.any { it is TopLevel.Deco && it.name == "Derive" })
        // `bridge func` string primitives become extern bridge sigs.
        assertTrue(root.items.any { it is TopLevel.Bridge && it.funcs.any { f -> f.name == "stringLength" } })
    }

    @Test fun anyBridgeMapsToErasedCompilerTypeWithoutRuntimeStruct() {
        val result = Compiler().compile("""
            func identity(value: std::Any): std::Any {
                return value
            }

            func main() {}
        """.trimIndent())

        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertTrue(result.ast.items.any { it is TopLevel.Pack && it.name == "Any" && it.isBridge })
        assertTrue(result.ir.items.none { it is IrTopLevel.Struct && it.name == "Any" })
        val identity = result.ir.functions.single { it.name == "identity" }
        assertEquals(IrType.Any, identity.params.single().second)
        assertEquals(IrType.Any, identity.returnType)
    }

    @Test fun anyBridgeCannotBeConstructedAsRuntimePack() {
        val result = Compiler().compile("func main() {\n    fin value = Any()\n}")

        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { "compiler bridge pack 'Any' cannot be constructed directly" in it },
            result.errors.toString(),
        )
    }

    @Test fun serializerSourceAndEmbeddedUnitTestsParse() {
        val source = AzStdlib.sources.single { "module std.serializer" in it }
        val serializer = Parser(Lexer(source).tokenize()).parse()
        val validationErrors = AstValidator().validate(serializer)

        assertTrue(validationErrors.isEmpty(), validationErrors.toString())
        assertEquals(56, serializer.items.count { it is TopLevel.Test })
        assertEquals(1, serializer.items.filterIsInstance<TopLevel.Test>().count { it.method.name == "All" })
        assertTrue(
            serializer.items.filterIsInstance<TopLevel.Deco>()
                .flatMap { it.fields }
                .all { !it.mutable }
        )
    }

    @Test fun serializerFixturesProduceGeneratedCodecMethods() {
        val source = AzStdlib.sources.single { "module std.serializer" in it }
        val serializer = Parser(Lexer(source).tokenize()).parse()
        val derived = SerializationDeriver.derive(serializer)

        assertTrue(derived.errors.isEmpty(), derived.errors.toString())
        val generated = derived.program.items.filterIsInstance<TopLevel.Impl>()
            .single { it.typeName == "SerializerMetadataFixture" && it.methods.isNotEmpty() }
        assertEquals(
            setOf("toSerialValue", "fromSerialValue", "toAzon", "fromAzon"),
            generated.methods.mapTo(mutableSetOf()) { it.name },
        )
        val deriveImports = derived.program.items.filterIsInstance<TopLevel.UseImport>()
            .flatMap { it.imports }
            .mapTo(mutableSetOf()) { it.first }
        assertTrue("std.serializer" in deriveImports)
        assertTrue("std.convert" in deriveImports)
    }

    @Test fun stdlibIndexExposesCollectionPacks() {
        assertEquals("std.container", StdlibInjector.moduleOf("List"))
        assertEquals("std.container", StdlibInjector.moduleOf("Map"))
        assertEquals("std.container", StdlibInjector.moduleOf("Set"))
    }

    @Test fun serializerImportSelectsDecoratorMarker() {
        val result = Compiler().compile("""
            module serializerMarkerTest

            import std.serializer

            pack UserId {
                fin value: std::Long
            }

            impl std::Serializable for UserId {}

            func main() {}
        """.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertTrue(result.ast.items.any { it is org.azora.lang.frontend.TopLevel.Deco && it.name == "Serializable" })
    }

    @Test fun importedSerializerDecoratorCanBeAppliedToPack() {
        val result = Compiler().compile("""
            module serializerDecoratorTest

            import std.serializer

            @std::Serializable
            pack UserId {
                fin value: std::Long
            }

            func main() {}
        """.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertTrue(result.ast.items.any {
            it is org.azora.lang.frontend.TopLevel.Deco && it.bindings.any { binding -> binding.name == "Serializer" }
        })
    }

    @Test fun importedPackCarriesItsFieldDecoratorImplementations() {
        val result = Compiler().compile("""
            module serializerFieldImplTest

            import std.serializer

            func decorated(): std::Int {
                inline if std::reflect<DirectFieldDecoratorFixture::name>.hasDeco<std::SerialName> {
                    return 1
                } else {
                    return 0
                }
            }

            func main() {}
        """.trimIndent())

        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        val fieldImpl = result.ast.items.filterIsInstance<TopLevel.Impl>().any {
            it.typeName == "DirectFieldDecoratorFixture.name" && it.traitName == "SerialName"
        }
        assertTrue(fieldImpl, "field decorator implementations must be injected with their owning pack")
    }

    @Test fun collectionTypeAnnotationsInjectPacksAndImpls() =
        assertEquals("3\n2\n2", run("""
            import std.io
            func main() {
                var xs: std::List<std::Int> = @std::arr[1, 2, 3]
                var entries: std::Map<std::String, std::Int> = ["a": 1, "b": 2]
                var seen: std::Set<std::Int> = ![1, 2, 2]
                std::println(xs.size)
                std::println(entries.size)
                std::println(seen.size)
            }
        """.trimIndent()))

    // ---- if-expressions (language feature the stdlib relies on) ----

    @Test fun ifExpressionInUserCode() =
        assertEquals("small", run("import std.io\nfunc main() {\n    let label = if 3 > 10 { \"big\" } else { \"small\" }\n    std::println(label)\n}"))

    @Test fun ifExpressionElseIfChain() =
        assertEquals("mid", run("import std.io\nfunc pick(x: Int): String = if x > 10 { \"big\" } else if x > 3 { \"mid\" } else { \"small\" }\nfunc main() {\n    std::println(pick(5))\n}"))

    @Test fun expressionBodiedFunction() =
        assertEquals("14", run("import std.io\nfunc twice(x: Int): Int = x * 2\nfunc main() {\n    std::println(twice(7))\n}"))

    // ---- bare access is rejected ----

    @Test fun bareStdlibAccessIsRejected() {
        val result = Compiler().compile("import std.io\nimport std.math\nfunc main() {\n    std::println(abs(-5))\n}")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "undefined" in it && "abs" in it }, "bare access should be rejected: ${'$'}{result.errors}")
    }

    @Test fun importedRealmMemberRequiresQualifiedAccess() {
        val result = Compiler().compile("""
            module playground

            import std.io

            func main() {
                println("Hello, world!")
            }
        """.trimIndent())

        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any {
                it == "line 6: undefined function 'println'; 'println' is part of realm 'std', use 'std::println' instead"
            },
            result.errors.toString(),
        )
    }

    @Test fun realmMembersRequireQualifiedAccess() =
        assertEquals("local\nshared", run("""
            import std.io

            realm local {
                func first(): std::String { return "local" }
            }

            realm merged {
                func second(): std::String { return "shared" }
            }

            func main() {
                std::println(local::first())
                std::println(merged::second())
            }
        """.trimIndent()))

    @Test fun plainRealmsDoNotExposeBareMembers() {
        for (declaration in listOf("realm local", "realm local")) {
            val result = Compiler().compile("""
                $declaration {
                    func hidden(): std::Int { return 1 }
                }

                func main() {
                    hidden()
                }
            """.trimIndent())

            assertIs<CompilationResult.Failure>(result)
            assertTrue(result.errors.any { "undefined function 'hidden'" in it }, result.errors.toString())
        }
    }

    @Test fun qualifiedAccessWithoutImportIsRejected() {
        val result = Compiler().compile("import std.io\nfunc main() {\n    std::println(std::abs(-5))\n}")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "abs" in it }, "qualified access without import should be rejected: ${'$'}{result.errors}")
    }

    @Test fun wrongRealmQualificationIsRejected() {
        // `abs` lives in realm `std`, so `std::math::abs` names a realm that does
        // not exist and must fail even though the module `std.math` is imported.
        val result = Compiler().compile("import std.io\nimport std.math\nfunc main() {\n    std::println(std::math::abs(-5))\n}")
        assertIs<CompilationResult.Failure>(result)
    }

    @Test fun importStdWildcardExposesAllModules() =
        assertEquals("5\n9", run("import std.io\nimport std.*\nfunc main() {\n    std::println(std::abs(-5))\n    std::println(std::max(2, 9))\n}"))

    @Test fun importStdNamespaceWithoutModuleIsRejected() {
        val result = Compiler().compile("import std\nfunc main() {}")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "'std' is a namespace" in it }, result.errors.toString())
    }

    // ---- import syntax errors ----

    @Test fun importRejectsDoubleColonSyntax() {
        val err = assertFailsWith<IllegalStateException> {
            Compiler().compile("import std.io\nimport std.math::abs\nfunc main() {\n    std::println(std::abs(-5))\n}")
        }
        assertTrue(err.message.orEmpty().contains("Use dotted import paths"), err.message)
    }

    @Test fun dottedStdAccessIsNotNamespaceAccess() {
        val result = Compiler().compile("import std.io\nfunc main() {\n    std::println(std.math.abs(-5))\n}")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "std" in it }, "${'$'}{result.errors}")
    }

    // -- underscore privacy across modules ----

    private val privateLibrary = LibrarySource(
        "lib/lib.az",
        """
            module lib.lib

            pack Body {
                var mass: std::Int
                var _cache: std::Int
            }

            func _helper(): std::Int { return 41 }
            func openHelper(): std::Int { return _helper() }

            realm Secret {
                func _hidden(): std::Int { return 7 }
                func shown(): std::Int { return _hidden() }
            }

            pack _Internal { var v: std::Int }
        """.trimIndent(),
    )

    private fun privateAccess(body: String): List<String> {
        val result = Compiler(listOf(privateLibrary)).compile(
            """
            import std.io
            import lib.lib
            func main() {
            $body
            }
            """.trimIndent()
        )
        assertIs<CompilationResult.Failure>(result, "expected the underscore to be enforced")
        return result.errors
    }

    private val modelLibrary = LibrarySource(
        "lib/model.az",
        """
            module lib.model

            pack Model {
                var width = 0.0
                var _secret = 42.0
            }

            impl Model {
                prop secret[self: std::Self&]: std::Double = self._secret
            }
        """.trimIndent(),
    )

    @Test fun anExtensionInAnotherModuleSeesOnlyThePublicSurface() {
        // The underscore is scoped to the declaring module, not to the type - an
        // `impl` written elsewhere names the same type but is not inside it.
        val result = Compiler(listOf(modelLibrary)).compile("""
            import std.io
            import lib.model
            prop leaked[self: Model&]: std::Double = self._secret
            func main() {
                std::println(Model(3.0, 1.0).leaked)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { "private field '_secret' of Model" in it },
            "${'$'}{result.errors}",
        )
    }

    @Test fun anExtensionInAnotherModuleStillReachesPublicFields() {
        val result = Compiler(listOf(modelLibrary)).compile("""
            import std.io
            import lib.model
            prop doubled[self: Model&]: std::Double = self.width * 2.0
            func main() {
                fin m = Model(3.0, 1.0)
                std::println(m.doubled)
                std::println(m.secret)
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("6.0\n1.0", IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun anotherModulesPrivateFunctionCannotBeNamed() {
        assertTrue(
            privateAccess("    std::println(_helper())").any { "'_helper' is private to module 'lib.lib'" in it },
            "expected _helper to be private",
        )
    }

    @Test fun anotherModulesPrivateRealmMemberCannotBeNamed() {
        // The mangled form is `Secret___hidden`; the message has to name it the
        // way the source does.
        assertTrue(
            privateAccess("    std::println(Secret::_hidden())").any { "'Secret::_hidden' is private to module 'lib.lib'" in it },
            "expected Secret::_hidden to be private",
        )
    }

    @Test fun anotherModulesPrivateTypeCannotBeNamed() {
        assertTrue(
            privateAccess("    std::println(_Internal(3).v)").any { "'_Internal' is private to module 'lib.lib'" in it },
            "expected _Internal to be private",
        )
    }

    @Test fun aModuleStillReachesItsOwnPrivateDeclarations() {
        // Privacy withholds the right to *name* it; the declaration is still
        // injected, or a public function that uses it would stop working.
        val result = Compiler(listOf(privateLibrary)).compile("""
            import std.io
            import lib.lib
            func main() {
                std::println(openHelper())
                std::println(Secret::shown())
                std::println(Body(1, 2).mass)
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("41\n7\n1", IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun compilerLoadsExternalLibraryModulesPerInstance() {
        val library = LibrarySource(
            "engine/render.az",
            """
                module engine.render

                realm engine {
                    func answer(): std::Int { return 42 }
                }
            """.trimIndent(),
        )
        val result = Compiler(listOf(library)).compile("""
            import std.io
            import engine.render
            func main() {
                std::println(engine::answer())
            }
        """.trimIndent())

        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("42", IrInterpreter().interpret(result.ir))
    }

    @Test fun externalRealmTypesRequireTheirDeclaredQualifier() {
        val library = LibrarySource(
            "engine/model.az",
            """
                module engine.model

                realm engine {
                    pack Handle {
                        fin id: std::Int
                    }
                }
            """.trimIndent(),
        )
        val compiler = Compiler(listOf(library))

        val bare = compiler.compile("""
            import engine.model
            func inspect(value: Handle): std::Int { return value.id }
        """.trimIndent())
        val failure = assertIs<CompilationResult.Failure>(bare)
        assertEquals(
            listOf("line 2: undefined type 'Handle'; 'Handle' is part of realm 'engine', use 'engine::Handle' instead"),
            failure.errors,
        )

        val qualified = compiler.compile("""
            import engine.model
            func inspect(value: engine::Handle): std::Int { return value.id }
            func main() {}
        """.trimIndent())
        assertIs<CompilationResult.Success>(
            qualified,
            "qualified external realm type failed: ${(qualified as? CompilationResult.Failure)?.errors}",
        )
    }

    @Test fun externalLibraryModulesResolveTheirOwnImports() {
        val libraries = listOf(
            LibrarySource(
                "engine/shaders.az",
                """
                    module engine.shaders

                    func shaderValue(): std::Int { return 7 }
                """.trimIndent(),
            ),
            LibrarySource(
                "engine/render.az",
                """
                    module engine.render

                    import engine.shaders
                    realm engine {
                        func shaderCount(): std::Int { return shaderValue() }
                    }
                """.trimIndent(),
            ),
        )
        val result = Compiler(libraries).compile("""
            import std.io
            import engine.render
            func main() { std::println(engine::shaderCount()) }
        """.trimIndent())

        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("7", IrInterpreter().interpret(result.ir))
    }
}
