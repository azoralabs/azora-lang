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

    // -- visibility modifiers (parsed, not enforced in single-file mode) ----

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

    @Test fun protectFuncWorksInSameFile() {
        assertEquals("protected", run("""
            node Base(x: Int) {
                protect func internal(): String {
                    return "protected"
                }
            }
            func main() {
                var b = Base(1)
                println(b.internal())
            }
        """.trimIndent()))
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
