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
}
