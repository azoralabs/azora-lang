package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for modules/packages: `use Zone::Item` imports, `use Zone` wildcard imports,
 * and visibility modifiers (`expose`/`confine`/`protect`).
 */
class ModulesTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    // -- use imports --------------------------------------------------------

    @Test fun useImportSpecificItem() {
        assertEquals("3\n14159", run("""
            zone Math {
                fin PI = 14159
                func triple(x: Int): Int {
                    return x * 3
                }
            }
            use Math::triple
            use Math::PI
            func main() {
                println(triple(1))
                println(PI)
            }
        """.trimIndent()))
    }

    @Test fun useImportAllItems() {
        assertEquals("hello\n42", run("""
            zone Utils {
                func greet(): String {
                    return "hello"
                }
                fin answer = 42
            }
            use Utils
            func main() {
                println(greet())
                println(answer)
            }
        """.trimIndent()))
    }

    @Test fun useImportStarWildcard() {
        assertEquals("5", run("""
            zone Const {
                fin five = 5
            }
            use Const::*
            func main() {
                println(five)
            }
        """.trimIndent()))
    }

    @Test fun useImportDotStarWildcard() {
        assertEquals("5", run("""
            zone Const {
                fin five = 5
            }
            use Const.*
            func main() {
                println(five)
            }
        """.trimIndent()))
    }

    @Test fun useZoneAliasImportsZone() {
        assertEquals("hello\n42", run("""
            zone Utils {
                func greet(): String {
                    return "hello"
                }
                fin answer = 42
            }
            use zone Utils
            func main() {
                println(greet())
                println(answer)
            }
        """.trimIndent()))
    }

    @Test fun useImportGroupedItems() {
        assertEquals("3\n14159", run("""
            zone Math {
                fin PI = 14159
                func triple(x: Int): Int {
                    return x * 3
                }
            }
            use Math::{triple, PI}
            func main() {
                println(triple(1))
                println(PI)
            }
        """.trimIndent()))
    }

    @Test fun friendZoneNamespaceCanBeSharedAcrossBlocks() {
        assertEquals("3\n42", run("""
            friend zone std {
                func triple(x: Int): Int {
                    return x * 3
                }
            }
            friend zone std {
                fin answer = 42
            }
            func main() {
                println(triple(1))
                println(answer)
            }
        """.trimIndent()))
    }

    @Test fun scopeIsJustAnIdentifierNotANamespaceKeyword() {
        assertEquals("7", run("""
            func main() {
                var scope = 7
                println(scope)
            }
        """.trimIndent()))

        assertFailsWith<IllegalStateException> {
            Compiler().compile("""
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
            expose func helper(): String {
                return "ok"
            }
            func main() {
                println(helper())
            }
        """.trimIndent()))
    }

    @Test fun confineFuncWorksInSameFile() {
        assertEquals("private", run("""
            confine func secret(): String {
                return "private"
            }
            func main() {
                println(secret())
            }
        """.trimIndent()))
    }

    @Test fun protectedMethodWorksThroughExposedMethod() {
        assertEquals("protected", run("""
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
                println(b.reveal())
            }
        """.trimIndent()))
    }

    @Test fun protectedMethodCannotBeCalledExternally() {
        val result = Compiler().compile("""
            node Base(x: Int) {
                protected func internal(): String {
                    return "protected"
                }
            }
            func main() {
                var b = Base(1)
                println(b.internal())
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "protected method 'internal'" in it }, "${result.errors}")
    }

    @Test fun confineZoneMemberIsNotImportedByWildcard() {
        val result = Compiler().compile("""
            zone Vault {
                confine func hidden(): Int {
                    return 1
                }
                expose func shown(): Int {
                    return 2
                }
            }
            use Vault
            func main() {
                println(shown())
                println(hidden())
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "hidden" in it }, "${result.errors}")
    }

    @Test fun confineZoneMemberCannotBeImportedDirectly() {
        val result = Compiler().compile("""
            zone Vault {
                confine func hidden(): Int {
                    return 1
                }
            }
            use Vault::hidden
            func main() {
                println(hidden())
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "confined function 'Vault::hidden'" in it }, "${result.errors}")
    }

    @Test fun confinePackFieldCannotBeReadExternally() {
        val result = Compiler().compile("""
            pack Secret {
                confine var value: Int
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
            func main() {
                println("ok")
            }
        """.trimIndent()))
    }

    @Test fun visibilityOnPack() {
        assertEquals("42", run("""
            expose pack Container {
                var v: Int
            }
            func main() {
                var c = Container(42)
                println(c.v)
            }
        """.trimIndent()))
    }
}
