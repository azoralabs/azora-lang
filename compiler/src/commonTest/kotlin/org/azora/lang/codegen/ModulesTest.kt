package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for scopes and visibility under the scope/import model:
 *
 * - A named `scope X { ... }` namespaces its members (`X::member`). Members are
 *   reached via the qualified `X::name` path; bare access is rejected.
 * - `scope X { ... }` may be declared in multiple blocks (and across
 *   modules); the contributions merge into one logical scope.
 * - Visibility modifiers (`exposed`/`confined`/`protected`) still constrain access.
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
            import std.io
            pack Body {
                var mass: Int
            }
            func main() {
                println(Body(7).mass)
            }
        """.trimIndent()))
    }

    @Test fun aLeadingUnderscoreKeepsAMemberInsideItsType() {
        val result = Compiler().compile("""
            import std.io
            pack Body {
                var _cache: Int
            }
            func main() {
                println(Body(7)._cache)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { "private field '_cache' of Body" in it },
            "expected the underscore to make it private, got: ${'$'}{result.errors}",
        )
    }

    @Test fun aTypeReachesItsOwnPrivateMembers() {
        // The restriction is on other types, not on the declaring one - otherwise
        // a private field would be write-only.
        assertEquals("8", run("""
            import std.io
            pack Body {
                var _cache: Int
            }
            impl Body {
                func &.bumped(): Int {
                    return self._cache + 1
                }
            }
            func main() {
                println(Body(7).bumped())
            }
        """.trimIndent()))
    }

    // -- `mod` is contextual, not reserved ----

    @Test fun modDeclaresTheModuleAndIsStillAnOrdinaryName() {
        // `mod` opens a module declaration only where it is followed by a name
        // at the top of a file. Everywhere else it is just an identifier, so a
        // reasonable name for a modulus is not taken away by the module syntax.
        assertEquals("7\n2\n3", run("""
            module arithmetic
            import std.io

            pack Wheel {
                var mod: Int
            }

            func mod(a: Int, b: Int): Int {
                return a % b
            }

            func main() {
                var mod = 7
                println(mod)
                println(mod(17, 5))
                println(Wheel(3).mod)
            }
        """.trimIndent()))
    }

    @Test fun theModuleNameMayItselfBeMod() {
        assertEquals("ok", run("""
            module mod
            import std.io

            func main() {
                println("ok")
            }
        """.trimIndent()))
    }

    // -- qualified scope access (no bare aliases) ----

    @Test fun qualifiedScopeFunctionAndConstant() {
        assertEquals("3\n14159", run("""
            import std.io
            scope Math {
                fin PI = 14159
                func triple(x: Int): Int {
                    return x * 3
                }
            }
            func main() {
                println(Math::triple(1))
                println(Math::PI)
            }
        """.trimIndent()))
    }

    @Test fun qualifiedScopeAccessForFuncsAndFins() {
        assertEquals("hello\n42", run("""
            import std.io
            scope Utils {
                func greet(): String {
                    return "hello"
                }
                fin answer = 42
            }
            func main() {
                println(Utils::greet())
                println(Utils::answer)
            }
        """.trimIndent()))
    }

    @Test fun bareScopeAccessIsRejected() {
        val result = Compiler().compile("""
            import std.io
            scope Const {
                fin five = 5
            }
            func main() {
                println(five)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "five" in it }, "bare scope access should be rejected: ${'$'}{result.errors}")
    }

    @Test fun useDoesNotCreateBareAlias() {
        // `use Const` is a no-op for user scopes; bare `five` must still be rejected.
        val result = Compiler().compile("""
            import std.io
            scope Const {
                fin five = 5
            }
            import Const
            func main() {
                println(five)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "five" in it }, "${'$'}{result.errors}")
    }

    @Test fun aProgramsOwnScopeNamedStdMergesAcrossBlocks() {
        // `std` is a scope like any other here: its members merge across blocks
        // and are reached through it, not as if the library declared them.
        assertEquals("3\n42", run("""
            import std.io
            scope std {
                func triple(x: Int): Int {
                    return x * 3
                }
            }
            scope std {
                fin answer = 42
            }
            func main() {
                println(std::triple(1))
                println(std::answer)
            }
        """.trimIndent()))
    }

    @Test fun aBareScopeMemberIsNamedWithItsScope() {
        val result = Compiler().compile("""
            import std.io
            scope Const {
                fin five = 5
                func six(): Int { return 6 }
            }
            func main() {
                println(five)
                println(six())
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.contains("line 7: undefined value 'five'; 'five' is part of scope 'Const', use 'Const::five' instead"),
            result.errors.toString(),
        )
        assertTrue(
            result.errors.contains("line 8: undefined function 'six'; 'six' is part of scope 'Const', use 'Const::six' instead"),
            result.errors.toString(),
        )
    }

    @Test fun reopeningAScopeMergesItsContributions() {
        // A scope is a name a package agrees on, not a block one file owns, so
        // opening it twice adds to it rather than colliding.
        assertEquals("1\n2", run("""
            import std.io
            scope x {
                func a(): Int { return 1 }
            }
            scope x {
                func b(): Int { return 2 }
            }
            func main() {
                println(x::a())
                println(x::b())
            }
        """.trimIndent()))
    }

    @Test fun scopeIsAKeywordThatOpensANamespace() {
        assertEquals("1", run("""
            import std.io
            scope Old {
                func nope(): Int {
                    return 1
                }
            }
            func main() {
                println(Old::nope())
            }
        """.trimIndent()))

        val result = Compiler().compile("""
            func main() {
                var scope = 7
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { "got the keyword 'scope', which cannot be used as a name" in it },
            result.errors.toString(),
        )
    }

    // -- visibility modifiers -----------------------------------------------

    @Test fun exposeFuncWorks() {
        assertEquals("ok", run("""
            import std.io
            func helper(): String {
                return "ok"
            }
            func main() {
                println(helper())
            }
        """.trimIndent()))
    }

    @Test fun confineFuncWorksInSameFile() {
        assertEquals("private", run("""
            import std.io
            confined func secret(): String {
                return "private"
            }
            func main() {
                println(secret())
            }
        """.trimIndent()))
    }

    @Test fun confinePackFieldCannotBeReadExternally() {
        val result = Compiler().compile("""
            import std.io
            pack Secret {
                confined var value: Int
            }
            func main() {
                var s = Secret(7)
                println(s.value)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "confined field 'value'" in it }, "${result.errors}")
    }

    @Test fun moduleKeywordAsPackageAlias() {
        assertEquals("ok", run("""
            module myapp
            import std.io
            func main() {
                println("ok")
            }
        """.trimIndent()))
    }

    @Test fun visibilityOnPack() {
        assertEquals("42", run("""
            import std.io
            pack Container {
                var v: Int
            }
            func main() {
                var c = Container(42)
                println(c.v)
            }
        """.trimIndent()))
    }
}
