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
            func main() {
                rem count: Int = 0
                println(count)
                count = 42
                println(count)
            }
        """.trimIndent()))
    }

    @Test fun effectRunsImmediately() {
        assertEquals("hello\ndone", run("""
            func main() {
                rem msg: String = "hello"
                effect {
                    println(msg)
                }
                println("done")
            }
        """.trimIndent()))
    }

    @Test fun viewIsCallable() {
        assertEquals("Hello, World!", run("""
            view Greet(name: String) {
                println("Hello, " + name + "!")
            }
            func main() {
                Greet("World")
            }
        """.trimIndent()))
    }

    @Test fun viewWithRemAndEffect() {
        // Effect runs once immediately (like the old interpreter; reactive re-runs are future work).
        assertEquals("count=0", run("""
            view Counter() {
                rem count: Int = 0
                effect {
                    println("count=" + count)
                }
            }
            func main() {
                Counter()
            }
        """.trimIndent()))
    }
}
