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
            import std.io
            hook start {
                std::io::println("hook:start")
            }
            hook stop {
                std::io::println("hook:stop")
            }
            func main() {
                std::io::println("main done")
            }
        """.trimIndent()))
    }

    @Test fun propComputesValue() {
        assertEquals("10", run("""
            import std.io
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
                std::io::println(b.doubled)
            }
        """.trimIndent()))
    }

    @Test fun propInNode() {
        assertEquals("42\n84", run("""
            import std.io
            node Container(v: Int) {
                prop doubled: Int {
                    return self.v + self.v
                }
            }
            func main() {
                var c = Container(42)
                std::io::println(c.v)
                std::io::println(c.doubled)
            }
        """.trimIndent()))
    }

    @Test fun dtorCalledOnDrop() {
        assertEquals("created\ndestroyed\ndropped", run("""
            import std.io
            node Resource(name: String) {
                dtor {
                    std::io::println("destroyed")
                }
            }
            func main() {
                var r = Resource("test")
                std::io::println("created")
                drop r
                std::io::println("dropped")
            }
        """.trimIndent()))
    }

    @Test fun flipFlopAlternates() {
        assertEquals("A\nB\nA\nB\nA", run("""
            import std.io
            func main() {
                for i in 0..<5 {
                    flip { std::io::println("A") } flop { std::io::println("B") }
                }
            }
        """.trimIndent()))
    }

    @Test fun flipWithoutFlop() {
        assertEquals("on\noff\non\noff", run("""
            import std.io
            func main() {
                for i in 0..<4 {
                    flip { std::io::println("on") } flop { std::io::println("off") }
                }
            }
        """.trimIndent()))
    }

    @Test fun flipFlopInFunctionCalls() {
        // By default, flip/flop alternates on each function call (not just loops).
        assertEquals("tick\ntock\ntick\ntock", run("""
            import std.io
            func metronome() {
                flip { std::io::println("tick") } flop { std::io::println("tock") }
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
            import std.io
            func main() {
                @a for i in 0..<3 {
                    flip@a { std::io::println("A") } flop@a { std::io::println("B") }
                }
            }
        """.trimIndent()))
    }

    @Test fun flipFlopTwoLabeledLoops() {
        // Two separate labeled loops, each with its own flip/flop — independent toggles.
        assertEquals("A\nB\nX\nY", run("""
            import std.io
            func main() {
                @a for i in 0..<2 {
                    flip@a { std::io::println("A") } flop@a { std::io::println("B") }
                }
                @b for j in 0..<2 {
                    flip@b { std::io::println("X") } flop@b { std::io::println("Y") }
                }
            }
        """.trimIndent()))
    }

    @Test fun flipFlopNestedLabeledLoops() {
        // Nested labeled loops: @a for outer (2x), @b for inner (2x each = 4 total).
        assertEquals("AiBi\nAjBj\nAiBi\nAjBj", run("""
            import std.io
            func main() {
                @a for i in 0..<2 {
                    @b for j in 0..<2 {
                        flip@b { std::io::println("AiBi") } flop@b { std::io::println("AjBj") }
                    }
                }
            }
        """.trimIndent()))
    }
}
