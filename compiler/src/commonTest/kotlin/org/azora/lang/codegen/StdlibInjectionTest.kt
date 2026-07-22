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
 * Tests for arr![org.azora.lang.stdlib.StdlibInjector] under the zone/import model:
 *
 * - Library symbols live in zones (`friend zone std::math { ... }`) and are
 *   name-mangled (`std.math::abs` → `std__math__abs`).
 * - `import std.math` makes that module's symbols visible; references must use
 *   the qualified `Zone::name` form. Bare references are rejected.
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
        assertEquals("5\n7", run("import std.io\nimport std.math\nfunc main() {\n    std::println(std::math::abs(-5))\n    std::println(std::math::abs(7))\n}"))

    @Test fun printWritesWithoutNewline() =
        assertEquals("Hello, 7!", run("import std.io\nfunc main() {\n    std::print(\"Hello, \" )\n    std::print(7)\n    std::println(\"!\")\n}"))

    @Test fun stdlibZoneMemberCallsSiblingBare() {
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
        assertEquals("2\n9", run("import std.io\nimport std.math\nfunc main() {\n    std::println(std::math::min(2, 9))\n    std::println(std::math::max(2, 9))\n}"))

    @Test fun qualifiedFloorCeilRound() =
        assertEquals("3\n4\n4", run("import std.io\nimport std.math\nfunc main() {\n    std::println(std::math::floor(3.7))\n    std::println(std::math::ceil(3.2))\n    std::println(std::math::round(3.6))\n}"))

    @Test fun qualifiedFactorialGcd() =
        assertEquals("120\n6", run("import std.io\nimport std.math\nfunc main() {\n    std::println(std::math::factorial(5))\n    std::println(std::math::gcd(54, 24))\n}"))

    @Test fun qualifiedConstantInjects() {
        val out = run("import std.io\nimport std.math\nfunc main() {\n    std::println(std::math::PI)\n}")
        assertTrue(out.startsWith("3.14159"), out)
    }

    // ---- transitive + shadowing ----

    @Test fun transitiveStdlibCallsResolve() {
        // std::math::lcm uses std::math::gcd internally — both must inject.
        assertEquals("36", run("import std.io\nimport std.math\nfunc main() {\n    std::println(std::math::lcm(12, 18))\n}"))
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
            func identity(value: Any): Any {
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
        assertEquals(59, serializer.items.count { it is TopLevel.Test })
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
            setOf("toSerialValue", "fromSerialValue", "toJson", "fromJson", "toAzon", "fromAzon"),
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
            module serializer_marker_test
            import std.serializer

            pack UserId {
                fin value: Long
            }

            impl Serializable for UserId

            func main() {}
        """.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertTrue(result.ast.items.any { it is org.azora.lang.frontend.TopLevel.Deco && it.name == "Serializable" })
    }

    @Test fun importedSerializerDecoratorCanBeAppliedToPack() {
        val result = Compiler().compile("""
            module serializer_decorator_test
            import std.serializer

            @Serializable
            pack UserId {
                fin value: Long
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
            module serializer_field_impl_test
            import std.serializer

            func decorated(): Int {
                inline if (std::reflect<DirectFieldDecoratorFixture::name>).hasDeco<SerialName> {
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
                var xs: List<Int> = arr![1, 2, 3]
                var entries: Map<String, Int> = ["a": 1, "b": 2]
                var seen: Set<Int> = ![1, 2, 2]
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

    @Test fun importedZoneMemberRequiresQualifiedAccess() {
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
                it == "line 6: undefined function 'println'; 'println' is part of zone 'std', use 'std::println' instead"
            },
            result.errors.toString(),
        )
    }

    @Test fun explicitlyUsedZonesExposeBareMembers() =
        assertEquals("local\nshared", run("""
            import std.io

            use zone local {
                func first(): String { return "local" }
            }

            use friend zone merged {
                func second(): String { return "shared" }
            }

            func main() {
                std::println(first())
                std::println(second())
            }
        """.trimIndent()))

    @Test fun plainZonesDoNotExposeBareMembers() {
        for (declaration in listOf("zone local", "friend zone local")) {
            val result = Compiler().compile("""
                $declaration {
                    func hidden(): Int { return 1 }
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
        val result = Compiler().compile("import std.io\nfunc main() {\n    std::println(std::math::abs(-5))\n}")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "abs" in it }, "qualified access without import should be rejected: ${'$'}{result.errors}")
    }

    @Test fun wrongZoneQualificationIsRejected() {
        // `abs` lives in std.math, not std — `std::abs` must fail.
        val result = Compiler().compile("import std.io\nimport std.math\nfunc main() {\n    std::println(std::abs(-5))\n}")
        assertIs<CompilationResult.Failure>(result)
    }

    @Test fun importStdWildcardExposesAllModules() =
        assertEquals("5\n9", run("import std.io\nimport std.*\nfunc main() {\n    std::println(std::math::abs(-5))\n    std::println(std::math::max(2, 9))\n}"))

    @Test fun importStdNamespaceWithoutModuleIsRejected() {
        val result = Compiler().compile("import std\nfunc main() {}")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "'std' is a namespace" in it }, result.errors.toString())
    }

    // ---- import syntax errors ----

    @Test fun importRejectsDoubleColonSyntax() {
        val err = assertFailsWith<IllegalStateException> {
            Compiler().compile("import std.io\nimport std.math::abs\nfunc main() {\n    std::println(std::math::abs(-5))\n}")
        }
        assertTrue(err.message.orEmpty().contains("Use dotted import paths"), err.message)
    }

    @Test fun dottedStdAccessIsNotNamespaceAccess() {
        val result = Compiler().compile("import std.io\nfunc main() {\n    std::println(std.math.abs(-5))\n}")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "std" in it }, "${'$'}{result.errors}")
    }

    @Test fun compilerLoadsExternalLibraryModulesPerInstance() {
        val library = LibrarySource(
            "engine/render.az",
            """
                module engine.render
                friend zone engine {
                    func answer(): Int { return 42 }
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

    @Test fun externalLibraryModulesResolveTheirOwnImports() {
        val libraries = listOf(
            LibrarySource(
                "engine/shaders.az",
                """
                    module engine.shaders
                    func shaderValue(): Int { return 7 }
                """.trimIndent(),
            ),
            LibrarySource(
                "engine/render.az",
                """
                    module engine.render
                    import engine.shaders
                    friend zone engine {
                        func shaderCount(): Int { return shaderValue() }
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
