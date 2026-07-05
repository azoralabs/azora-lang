package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for the object model: `hook`, `prop`, `ctor`, `dtor`.
 */
class ObjectModelTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun hookRunsAfterMain() {
        assertEquals("main done\nhook:start\nhook:stop", run("""
            hook start {
                println("hook:start")
            }
            hook stop {
                println("hook:stop")
            }
            func main() {
                println("main done")
            }
        """.trimIndent()))
    }

    @Test fun propComputesValue() {
        assertEquals("10", run("""
            pack Box {
                var v: Int
            }
            impl Box {
                prop doubled: Int {
                    return self.v + self.v
                }
            }
            func main() {
                var b = Box(5)
                println(b.doubled)
            }
        """.trimIndent()))
    }

    @Test fun propInNode() {
        assertEquals("42\n84", run("""
            node Container(v: Int) {
                prop doubled: Int {
                    return self.v + self.v
                }
            }
            func main() {
                var c = Container(42)
                println(c.v)
                println(c.doubled)
            }
        """.trimIndent()))
    }

    @Test fun dtorCalledOnDrop() {
        assertEquals("created\ndestroyed\ndropped", run("""
            node Resource(name: String) {
                dtor {
                    println("destroyed")
                }
            }
            func main() {
                var r = Resource("test")
                println("created")
                drop r
                println("dropped")
            }
        """.trimIndent()))
    }

    @Test fun flipFlopAlternates() {
        assertEquals("A\nB\nA\nB\nA", run("""
            func main() {
                for i in 0..<5 {
                    flip { println("A") } flop { println("B") }
                }
            }
        """.trimIndent()))
    }

    @Test fun flipWithoutFlop() {
        assertEquals("on\noff\non\noff", run("""
            func main() {
                for i in 0..<4 {
                    flip { println("on") } flop { println("off") }
                }
            }
        """.trimIndent()))
    }

    @Test fun flipFlopInFunctionCalls() {
        // By default, flip/flop alternates on each function call (not just loops).
        assertEquals("tick\ntock\ntick\ntock", run("""
            func metronome() {
                flip { println("tick") } flop { println("tock") }
            }
            func main() {
                metronome()
                metronome()
                metronome()
                metronome()
            }
        """.trimIndent()))
    }

    @Test fun flipFlopLabeledLoop() {
        // `@a for ... { flip@a { } flop@a { } }` — label declared on the loop, referenced by flip/flop.
        assertEquals("A\nB\nA", run("""
            func main() {
                @a for i in 0..<3 {
                    flip@a { println("A") } flop@a { println("B") }
                }
            }
        """.trimIndent()))
    }

    @Test fun flipFlopTwoLabeledLoops() {
        // Two separate labeled loops, each with its own flip/flop — independent toggles.
        assertEquals("A\nB\nX\nY", run("""
            func main() {
                @a for i in 0..<2 {
                    flip@a { println("A") } flop@a { println("B") }
                }
                @b for j in 0..<2 {
                    flip@b { println("X") } flop@b { println("Y") }
                }
            }
        """.trimIndent()))
    }

    @Test fun flipFlopNestedLabeledLoops() {
        // Nested labeled loops: @a for outer (2x), @b for inner (2x each = 4 total).
        assertEquals("AiBi\nAjBj\nAiBi\nAjBj", run("""
            func main() {
                @a for i in 0..<2 {
                    @b for j in 0..<2 {
                        flip@b { println("AiBi") } flop@b { println("AjBj") }
                    }
                }
            }
        """.trimIndent()))
    }
}
