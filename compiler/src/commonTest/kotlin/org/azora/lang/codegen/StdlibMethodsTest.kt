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
                var fruits = @arr["apple", "banana", "cherry"]
                for fruit in fruits {
                    println(fruit)
                }
            }
        """.trimIndent()))
    }

    @Test fun forInArraySum() {
        assertEquals("6", run("""
            import std.io
            func main() {
                var nums = @arr[1, 2, 3]
                var sum = 0
                for n in nums {
                    sum = sum + n
                }
                println(sum)
            }
        """.trimIndent()))
    }

    @Test fun stringToUpperCase() {
        assertEquals("HELLO", run("""
            import std.io
            func main() {
                println("hello".toUpperCase())
            }
        """.trimIndent()))
    }

    @Test fun stringContains() {
        assertEquals("true\nfalse", run("""
            import std.io
            func main() {
                println("hello world".contains("world"))
                println("hello world".contains("xyz"))
            }
        """.trimIndent()))
    }

    @Test fun stringStartsEndsWith() {
        assertEquals("true\ntrue", run("""
            import std.io
            func main() {
                println("hello".startsWith("he"))
                println("hello".endsWith("lo"))
            }
        """.trimIndent()))
    }

    @Test fun stringTrim() {
        assertEquals("hi", run("""
            import std.io
            func main() {
                println("  hi  ".trim())
            }
        """.trimIndent()))
    }

    @Test fun stringReplace() {
        assertEquals("hxllo", run("""
            import std.io
            func main() {
                println("hello".replace("e", "x"))
            }
        """.trimIndent()))
    }

    @Test fun arrayInsertRemove() {
        assertEquals("[b, x, c]", run("""
            import std.io
            func main() {
                var items = @arr["a", "b", "c"]
                items.insert(2, "x")
                items.remove(0)
                var result = ""
                for i in 0..<items.size {
                    if i > 0 { result = result + ", " }
                    result = result + items[i]
                }
                println("[" + result + "]")
            }
        """.trimIndent()))
    }

    @Test fun arrayContains() {
        assertEquals("true\nfalse", run("""
            import std.io
            func main() {
                var nums = @arr[1, 2, 3]
                println(nums.contains(2))
                println(nums.contains(9))
            }
        """.trimIndent()))
    }

    @Test fun setOfDeduplicates() {
        assertEquals("3\ntrue", run("""
            import std.io
            import std.*

            func main() {
                fin nums = setOf(1, 2, 2, 3)
                println(nums.size)
                println(nums.contains(2))
            }
        """.trimIndent()))
    }

}
