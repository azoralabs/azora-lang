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
 * - `friend zone X { ... }` may be declared in multiple blocks (and across
 *   modules); the contributions merge into one logical zone.
 * - Visibility modifiers (`expose`/`confine`/`protect`) still constrain access.
 */
class ModulesTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    // -- qualified zone access (no bare aliases) ----

    @Test fun qualifiedZoneFunctionAndConstant() {
        assertEquals("3\n14159", run("""
            import std.io
            zone Math {
                fin PI = 14159
                func triple(x: Int): Int {
                    return x * 3
                }
            }
            func main() {
                std::io::println(Math::triple(1))
                std::io::println(Math::PI)
            }
        """.trimIndent()))
    }

    @Test fun qualifiedZoneAccessForFuncsAndFins() {
        assertEquals("hello\n42", run("""
            import std.io
            zone Utils {
                func greet(): String {
                    return "hello"
                }
                fin answer = 42
            }
            func main() {
                std::io::println(Utils::greet())
                std::io::println(Utils::answer)
            }
        """.trimIndent()))
    }

    @Test fun bareZoneAccessIsRejected() {
        val result = Compiler().compile("""
            import std.io
            zone Const {
                fin five = 5
            }
            func main() {
                std::io::println(five)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "five" in it }, "bare zone access should be rejected: ${'$'}{result.errors}")
    }

    @Test fun importDoesNotCreateBareAlias() {
        // `import Const` is a no-op for user zones; bare `five` must still be rejected.
        val result = Compiler().compile("""
            import std.io
            zone Const {
                fin five = 5
            }
            import Const
            func main() {
                std::io::println(five)
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "five" in it }, "${'$'}{result.errors}")
    }

    @Test fun friendZoneMergesAcrossBlocks() {
        assertEquals("3\n42", run("""
            import std.io
            friend zone std {
                func triple(x: Int): Int {
                    return x * 3
                }
            }
            friend zone std {
                fin answer = 42
            }
            func main() {
                std::io::println(std::triple(1))
                std::io::println(std::answer)
            }
        """.trimIndent()))
    }

    @Test fun nonFriendZoneRedeclarationIsRejected() {
        // A non-friend `zone X` is exclusive: two declarations collide. The fix
        // is to make both `friend zone X` so they merge.
        val err = assertFailsWith<IllegalStateException> {
            Compiler().compile("""
                import std.io
                zone x {
                    func a(): Int { return 1 }
                }
                zone x {
                    func b(): Int { return 2 }
                }
                func main() { std::io::println(1) }
            """.trimIndent())
        }
        assertTrue(err.message.orEmpty().contains("zone 'x' is declared more than once"), err.message)
    }

    @Test fun scopeIsJustAnIdentifierNotANamespaceKeyword() {
        assertEquals("7", run("""
            import std.io
            func main() {
                var scope = 7
                std::io::println(scope)
            }
        """.trimIndent()))

        assertFailsWith<IllegalStateException> {
            Compiler().compile("""
                import std.io
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
            import std.io
            expose func helper(): String {
                return "ok"
            }
            func main() {
                std::io::println(helper())
            }
        """.trimIndent()))
    }

    @Test fun confineFuncWorksInSameFile() {
        assertEquals("private", run("""
            import std.io
            confine func secret(): String {
                return "private"
            }
            func main() {
                std::io::println(secret())
            }
        """.trimIndent()))
    }

    @Test fun protectedMethodWorksThroughExposedMethod() {
        assertEquals("protected", run("""
            import std.io
            node Base(x: Int) {
                protected func internal(): String {
                    return "protected"
                }
                func reveal(): String {
                    return self.internal()
                }
            }
            func main() {
                var b = Base(1)
                std::io::println(b.reveal())
            }
        """.trimIndent()))
    }

    @Test fun protectedMethodCannotBeCalledExternally() {
        val result = Compiler().compile("""
            import std.io
            node Base(x: Int) {
                protected func internal(): String {
                    return "protected"
                }
            }
            func main() {
                var b = Base(1)
                std::io::println(b.internal())
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "protected method 'internal'" in it }, "${result.errors}")
    }

    @Test fun confinePackFieldCannotBeReadExternally() {
        val result = Compiler().compile("""
            import std.io
            pack Secret {
                confine var value: Int
            }
            func main() {
                var s = Secret(7)
                std::io::println(s.value)
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
                std::io::println("ok")
            }
        """.trimIndent()))
    }

    @Test fun visibilityOnPack() {
        assertEquals("42", run("""
            import std.io
            expose pack Container {
                var v: Int
            }
            func main() {
                var c = Container(42)
                std::io::println(c.v)
            }
        """.trimIndent()))
    }
}
