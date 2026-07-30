package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for zones and visibility under the zone/import model:
 *
 * - A named `zone X { ... }` namespaces its members (`X::member`). Members are
 *   reached via the qualified `X::name` path; bare access is rejected.
 * - `zone X { ... }` may be declared in multiple blocks (and across
 *   modules); the contributions merge into one logical zone.
 * - Visibility modifiers (`expose`/`confine`/`protect`) still constrain access.
 */
class ModulesTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    // -- visibility: public by default, `_` is private ----

    @Test fun everythingIsPublicWithoutSayingSo() {
        assertEquals("7", run("""
            use std.io
            pack Body {
                var mass: Int
            }
            func main() {
                std::println(Body(7).mass)
            }
        """.trimIndent()))
    }

    @Test fun aLeadingUnderscoreKeepsAMemberInsideItsType() {
        val result = Compiler().compile("""
            use std.io
            pack Body {
                var _cache: Int
            }
            func main() {
                std::println(Body(7)._cache)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { "private field '_cache' of Body" in it },
            "expected the underscore to make it private, got: ${'$'}{result.errors}",
        )
    }

    @Test fun aTypeReachesItsOwnPrivateMembers() {
        // The restriction is on other types, not on the declaring one — otherwise
        // a private field would be write-only.
        assertEquals("8", run("""
            use std.io
            pack Body {
                var _cache: Int
            }
            impl Body {
                func bumped(): Int {
                    return self._cache + 1
                }
            }
            func main() {
                std::println(Body(7).bumped())
            }
        """.trimIndent()))
    }

    // -- `mod` is contextual, not reserved ----

    @Test fun modDeclaresTheModuleAndIsStillAnOrdinaryName() {
        // `mod` opens a module declaration only where it is followed by a name
        // at the top of a file. Everywhere else it is just an identifier, so a
        // reasonable name for a modulus is not taken away by the module syntax.
        assertEquals("7\n2\n3", run("""
            mod arithmetic
            use std.io

            pack Wheel {
                var mod: Int
            }

            func mod(a: Int, b: Int): Int {
                return a % b
            }

            func main() {
                var mod = 7
                std::println(mod)
                std::println(mod(17, 5))
                std::println(Wheel(3).mod)
            }
        """.trimIndent()))
    }

    @Test fun theModuleNameMayItselfBeMod() {
        assertEquals("ok", run("""
            mod mod
            use std.io

            func main() {
                std::println("ok")
            }
        """.trimIndent()))
    }

    // -- qualified zone access (no bare aliases) ----

    @Test fun qualifiedZoneFunctionAndConstant() {
        assertEquals("3\n14159", run("""
            use std.io
            zone Math {
                fin PI = 14159
                func triple(x: Int): Int {
                    return x * 3
                }
            }
            func main() {
                std::println(Math::triple(1))
                std::println(Math::PI)
            }
        """.trimIndent()))
    }

    @Test fun qualifiedZoneAccessForFuncsAndFins() {
        assertEquals("hello\n42", run("""
            use std.io
            zone Utils {
                func greet(): String {
                    return "hello"
                }
                fin answer = 42
            }
            func main() {
                std::println(Utils::greet())
                std::println(Utils::answer)
            }
        """.trimIndent()))
    }

    @Test fun bareZoneAccessIsRejected() {
        val result = Compiler().compile("""
            use std.io
            zone Const {
                fin five = 5
            }
            func main() {
                std::println(five)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "five" in it }, "bare zone access should be rejected: ${'$'}{result.errors}")
    }

    @Test fun useDoesNotCreateBareAlias() {
        // `use Const` is a no-op for user zones; bare `five` must still be rejected.
        val result = Compiler().compile("""
            use std.io
            zone Const {
                fin five = 5
            }
            use Const
            func main() {
                std::println(five)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "five" in it }, "${'$'}{result.errors}")
    }

    @Test fun friendZoneMergesAcrossBlocks() {
        assertEquals("3\n42", run("""
            use std.io
            zone std {
                func triple(x: Int): Int {
                    return x * 3
                }
            }
            zone std {
                fin answer = 42
            }
            func main() {
                std::println(std::triple(1))
                std::println(std::answer)
            }
        """.trimIndent()))
    }

    @Test fun reopeningAZoneMergesItsContributions() {
        // A zone is a name a package agrees on, not a block one file owns, so
        // opening it twice adds to it rather than colliding.
        assertEquals("1\n2", run("""
            use std.io
            zone x {
                func a(): Int { return 1 }
            }
            zone x {
                func b(): Int { return 2 }
            }
            func main() {
                std::println(x::a())
                std::println(x::b())
            }
        """.trimIndent()))
    }

    @Test fun scopeIsJustAnIdentifierNotANamespaceKeyword() {
        assertEquals("7", run("""
            use std.io
            func main() {
                var scope = 7
                std::println(scope)
            }
        """.trimIndent()))

        assertFailsWith<IllegalStateException> {
            Compiler().compile("""
                use std.io
                scope Old {
                    func nope(): Int {
                        return 1
                    }
                }
            """.trimIndent())
        }
    }

    // -- visibility modifiers -----------------------------------------------

    @Test fun exposeFuncWorks() {
        assertEquals("ok", run("""
            use std.io
            func helper(): String {
                return "ok"
            }
            func main() {
                std::println(helper())
            }
        """.trimIndent()))
    }

    @Test fun confineFuncWorksInSameFile() {
        assertEquals("private", run("""
            use std.io
            confine func secret(): String {
                return "private"
            }
            func main() {
                std::println(secret())
            }
        """.trimIndent()))
    }

    @Test fun confinePackFieldCannotBeReadExternally() {
        val result = Compiler().compile("""
            use std.io
            pack Secret {
                confine var value: Int
            }
            func main() {
                var s = Secret(7)
                std::println(s.value)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "confined field 'value'" in it }, "${result.errors}")
    }

    @Test fun moduleKeywordAsPackageAlias() {
        assertEquals("ok", run("""
            mod myapp
            use std.io
            func main() {
                std::println("ok")
            }
        """.trimIndent()))
    }

    @Test fun visibilityOnPack() {
        assertEquals("42", run("""
            use std.io
            pack Container {
                var v: Int
            }
            func main() {
                var c = Container(42)
                std::println(c.v)
            }
        """.trimIndent()))
    }
}
