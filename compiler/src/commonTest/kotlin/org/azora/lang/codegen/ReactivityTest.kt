package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for reactivity: `rem` (reactive state), `effect { }` (side-effect), `view Name() { }` (component).
 */
class ReactivityTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun remActsAsMutableVar() {
        assertEquals("0\n42", run("""
            import std.io
            func main() {
                rem count: Int = 0
                std::io::println(count)
                count = 42
                std::io::println(count)
            }
        """.trimIndent()))
    }

    @Test fun effectRunsImmediately() {
        assertEquals("hello\ndone", run("""
            import std.io
            func main() {
                rem msg: String = "hello"
                effect {
                    std::io::println(msg)
                }
                std::io::println("done")
            }
        """.trimIndent()))
    }

    @Test fun viewIsCallable() {
        assertEquals("Hello, World!", run("""
            import std.io
            view Greet(name: String) {
                std::io::println("Hello, " + name + "!")
            }
            func main() {
                Greet("World")
            }
        """.trimIndent()))
    }

    @Test fun viewWithRemAndEffect() {
        // Effect runs once immediately (like the old interpreter; reactive re-runs are future work).
        assertEquals("count=0", run("""
            import std.io
            view Counter() {
                rem count: Int = 0
                effect {
                    std::io::println("count=" + count)
                }
            }
            func main() {
                Counter()
            }
        """.trimIndent()))
    }
}
