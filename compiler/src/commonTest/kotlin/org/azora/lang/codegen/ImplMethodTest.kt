package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImplMethodTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun methodReturnsField() {
        assertEquals("3", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            impl Point {
                func getX(): Int {
                    return self.x
                }
            }
            func main() {
                fin p = Point(3, 4)
                println(p.getX())
            }
        """.trimIndent()))
    }

    @Test fun methodComputes() {
        assertEquals("25", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            impl Point {
                func lengthSquared(): Int {
                    return self.x * self.x + self.y * self.y
                }
            }
            func main() {
                fin p = Point(3, 4)
                println(p.lengthSquared())
            }
        """.trimIndent()))
    }

    @Test fun methodMutatesSelf() {
        assertEquals("13\n37", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            impl Point {
                func moveBy(dx: Int, dy: Int) {
                    self.x = self.x + dx
                    self.y = self.y + dy
                }
                func sum(): Int {
                    return self.x + self.y
                }
            }
            func main() {
                var p = Point(3, 4)
                p.moveBy(10, 20)
                println(p.x)
                println(p.sum())
            }
        """.trimIndent()))
    }

    @Test fun methodInInterpolation() {
        assertEquals("len=25", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            impl Point {
                func lengthSquared(): Int {
                    return self.x * self.x + self.y * self.y
                }
            }
            func main() {
                fin p = Point(3, 4)
                println("len=${'$'}{p.lengthSquared()}")
            }
        """.trimIndent()))
    }

    @Test fun methodOnStructInArray() {
        assertEquals("25", run("""
            pack Point {
                var x: Int
                var y: Int
            }
            impl Point {
                func lengthSquared(): Int {
                    return self.x * self.x + self.y * self.y
                }
            }
            func main() {
                fin pts = [Point(1, 1), Point(3, 4), Point(5, 5)]
                println(pts[1].lengthSquared())
            }
        """.trimIndent()))
    }

    @Test fun methodLoweredToBackends() {
        val result = Compiler().compile("""
            pack Point {
                var x: Int
                var y: Int
            }
            impl Point {
                func lengthSquared(): Int {
                    return self.x * self.x + self.y * self.y
                }
            }
            func main() {
                fin p = Point(3, 4)
                println(p.lengthSquared())
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
        // Method desugars to Point_lengthSquared(self)
        assertTrue("Point_lengthSquared" in result.kotlin, result.kotlin)
        assertTrue("Point_lengthSquared(p)" in result.kotlin, result.kotlin)
    }
}
