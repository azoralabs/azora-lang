package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StdlibMethodsTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun forInArray() {
        assertEquals("apple\nbanana\ncherry", run("""
            import std.io
            func main() {
                var fruits = ["apple", "banana", "cherry"]
                for fruit in fruits {
                    std::println(fruit)
                }
            }
        """.trimIndent()))
    }

    @Test fun forInArraySum() {
        assertEquals("6", run("""
            import std.io
            func main() {
                var nums = arr![1, 2, 3]
                var sum = 0
                for n in nums {
                    sum = sum + n
                }
                std::println(sum)
            }
        """.trimIndent()))
    }

    @Test fun stringToUpperCase() {
        assertEquals("HELLO", run("""
            import std.io
            func main() {
                std::println("hello".toUpperCase())
            }
        """.trimIndent()))
    }

    @Test fun stringContains() {
        assertEquals("true\nfalse", run("""
            import std.io
            func main() {
                std::println("hello world".contains("world"))
                std::println("hello world".contains("xyz"))
            }
        """.trimIndent()))
    }

    @Test fun stringStartsEndsWith() {
        assertEquals("true\ntrue", run("""
            import std.io
            func main() {
                std::println("hello".startsWith("he"))
                std::println("hello".endsWith("lo"))
            }
        """.trimIndent()))
    }

    @Test fun stringTrim() {
        assertEquals("hi", run("""
            import std.io
            func main() {
                std::println("  hi  ".trim())
            }
        """.trimIndent()))
    }

    @Test fun stringReplace() {
        assertEquals("hxllo", run("""
            import std.io
            func main() {
                std::println("hello".replace("e", "x"))
            }
        """.trimIndent()))
    }

    @Test fun arrayInsertRemove() {
        assertEquals("[b, x, c]", run("""
            import std.io
            func main() {
                var items = ["a", "b", "c"]
                items.insert(2, "x")
                items.remove(0)
                var result = ""
                for i in 0..<items.length {
                    if i > 0 { result = result + ", " }
                    result = result + items[i]
                }
                std::println("[" + result + "]")
            }
        """.trimIndent()))
    }

    @Test fun arrayContains() {
        assertEquals("true\nfalse", run("""
            import std.io
            func main() {
                var nums = arr![1, 2, 3]
                std::println(nums.contains(2))
                std::println(nums.contains(9))
            }
        """.trimIndent()))
    }

    @Test fun setOfDeduplicates() {
        assertEquals("3\ntrue", run("""
            import std.io
            import std.*

            func main() {
                fin nums = std::setOf(1, 2, 2, 3)
                std::println(nums.size)
                std::println(nums.contains(2))
            }
        """.trimIndent()))
    }

}
