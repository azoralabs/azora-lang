package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OperatorOverloadingTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun plusOverload() {
        assertEquals("4\n6", run("""
            import std.io
            pack Vec2 {
                var x: Int
                var y: Int
            }
            impl Vec2 {
                func plus(other: Vec2): Vec2 {
                    return Vec2(self.x + other.x, self.y + other.y)
                }
            }
            func main() {
                var a = Vec2(1, 2)
                var b = Vec2(3, 4)
                var c = a + b
                std::io::println(c.x)
                std::io::println(c.y)
            }
        """.trimIndent()))
    }

    @Test fun minusOverload() {
        assertEquals("2\n2", run("""
            import std.io
            pack Vec2 {
                var x: Int
                var y: Int
            }
            impl Vec2 {
                func minus(other: Vec2): Vec2 {
                    return Vec2(self.x - other.x, self.y - other.y)
                }
            }
            func main() {
                var a = Vec2(5, 6)
                var b = Vec2(3, 4)
                var c = a - b
                std::io::println(c.x)
                std::io::println(c.y)
            }
        """.trimIndent()))
    }

    @Test fun timesOverload() {
        assertEquals("3\n6", run("""
            import std.io
            pack Vec2 {
                var x: Int
                var y: Int
            }
            impl Vec2 {
                func times(other: Vec2): Vec2 {
                    return Vec2(self.x * other.x, self.y * other.y)
                }
            }
            func main() {
                var a = Vec2(1, 2)
                var b = Vec2(3, 3)
                var c = a * b
                std::io::println(c.x)
                std::io::println(c.y)
            }
        """.trimIndent()))
    }

    @Test fun equalsOverload() {
        assertEquals("true\nfalse", run("""
            import std.io
            pack Vec2 {
                var x: Int
                var y: Int
            }
            impl Vec2 {
                func equals(other: Vec2): Bool {
                    return self.x == other.x && self.y == other.y
                }
            }
            func main() {
                var a = Vec2(1, 2)
                var b = Vec2(1, 2)
                var c = Vec2(3, 4)
                std::io::println(a == b)
                std::io::println(a == c)
            }
        """.trimIndent()))
    }

    @Test fun notEqualsOverload() {
        assertEquals("false\ntrue", run("""
            import std.io
            pack Vec2 {
                var x: Int
                var y: Int
            }
            impl Vec2 {
                func equals(other: Vec2): Bool {
                    return self.x == other.x && self.y == other.y
                }
            }
            func main() {
                var a = Vec2(1, 2)
                var b = Vec2(1, 2)
                var c = Vec2(3, 4)
                std::io::println(a != b)
                std::io::println(a != c)
            }
        """.trimIndent()))
    }

    @Test fun chainedOperatorOverloads() {
        assertEquals("6", run("""
            import std.io
            pack Vec2 {
                var x: Int
                var y: Int
            }
            impl Vec2 {
                func plus(other: Vec2): Vec2 {
                    return Vec2(self.x + other.x, self.y + other.y)
                }
            }
            func main() {
                var a = Vec2(1, 1)
                var b = Vec2(2, 2)
                var c = Vec2(3, 3)
                var d = a + b + c
                std::io::println(d.x)
            }
        """.trimIndent()))
    }
}
