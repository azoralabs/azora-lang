package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * `realm test { }` - declarations that exist only for tests, and the
 * `exposed` / `protected` / `confined` ladder that bounds how far they reach.
 */
class TestRealmTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun errors(source: String): List<String> {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Failure>(result)
        return result.errors
    }

    @Test fun aTestMayUseWhatATestRealmDeclares() {
        assertEquals("", run("""
            realm test {
                func fixture(): std::Int { return 7 }
            }

            test "the fixture is reachable from a test" {
                assert fixture() == 7 { "fixture should be usable from a test" }
            }

            func main() { }
        """.trimIndent()))
    }

    @Test fun theProgramMayNotUseWhatATestRealmDeclares() {
        val found = errors("""
            realm test {
                func fixture(): std::Int { return 7 }
            }

            func main() { fin unused = fixture() }
        """.trimIndent())
        assertTrue(
            found.any { "'fixture' is declared in a 'realm test'" in it && "only be used from a test" in it },
            found.toString(),
        )
    }

    @Test fun aTestRealmMemberMayUseItsSiblings() {
        assertEquals("", run("""
            realm test {
                func base(): std::Int { return 6 }
                func fixture(): std::Int { return base() + 1 }
            }

            test "siblings compose" {
                assert fixture() == 7 { "a test-realm member may call another" }
            }

            func main() { }
        """.trimIndent()))
    }

    /** The reach a test realm names is reported back when it is violated. */
    @Test fun eachReachNamesItselfInTheDiagnostic() {
        val cases = mapOf(
            "realm test" to "a test in this file",
            "confined realm test" to "a test in this file",
            "protected realm test" to "a test in this folder",
            "exposed realm test" to "a test in any file",
        )
        for ((header, expected) in cases) {
            val found = errors("""
                $header {
                    func fixture(): std::Int { return 7 }
                }

                func main() { fin unused = fixture() }
            """.trimIndent())
            assertTrue(found.any { expected in it }, "$header -> $found")
        }
    }

    @Test fun aTestRealmMayNotNestInAnother() {
        val found = errors("""
            realm test {
                realm test {
                    func fixture(): std::Int { return 7 }
                }
            }

            func main() { }
        """.trimIndent())
        assertTrue(found.any { "cannot nest inside another" in it }, found.toString())
    }

    /**
     * `exposed` and the reach are independent axes, so they combine: `exposed`
     * publishes without an explicit import, and the reach bounds how far that
     * publication travels.
     */
    @Test fun exposeCombinesWithProtectAndConfine() {
        assertEquals("ok", run("""
            import std.io
            exposed func api(): std::Int { return 1 }
            protected func folderWide(): std::Int { return 2 }
            confined func unitLocal(): std::Int { return 3 }
            exposed protected func exposedInFolder(): std::Int { return 4 }
            exposed confined func exposedInUnit(): std::Int { return 5 }

            func main() {
                fin sum = api() + folderWide() + unitLocal() + exposedInFolder() + exposedInUnit()
                if sum == 15 { std::println("ok") } else { std::println("bad") }
            }
        """.trimIndent()))
    }
}
