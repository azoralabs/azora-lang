package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Temporary verification: compiles and runs the runnable code examples shown on the
 * book-website, ensuring every documented example actually works under the current compiler.
 */
class WebsiteExamplesTest {

    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    private fun run(source: String): String = IrInterpreter().interpret(compile(source).ir).trim()

    @Test fun ch1_hello() = assertEquals("Hello, Azora!", run("""func main() { println("Hello, Azora!") }"""))

    @Test fun ch2_program() = assertEquals("Hello, Azora!\nversion 1.0.0", run("""
        fin VERSION = "1.0.0"
        func greet(name: String): String {
            return "Hello, " + name + "!"
        }
        func main() {
            println(greet("Azora"))
            println("version " + VERSION)
        }
    """.trimIndent()))

    @Test fun ch3_bindings() = assertEquals("15\n100\n3", run("""
        func main() {
            var count = 0
            count = 10
            count = count + 5
            println(count)
            let limit = 100
            println(limit)
            fin pi = 3
            println(pi)
        }
    """.trimIndent()))

    @Test fun ch4_literals() = assertEquals("255\n63\n10\n1000000\n3.14\n1500.0", run("""
        func main() {
            println(0xFF)
            println(0o77)
            println(0b1010)
            println(1_000_000)
            println(3.14)
            println(1.5e3)
        }
    """.trimIndent()))

    @Test fun ch4_char() = assertEquals("true\ntrue", run("""
        func main() {
            fin grade = 'A'
            println(grade == 'A')
            println('a' < 'z')
        }
    """.trimIndent()))

    @Test fun ch5_arithmetic() = assertEquals("5\n6\n20\n3\n2\n-7", run("""
        func main() {
            println(2 + 3)
            println(10 - 4)
            println(4 * 5)
            println(17 / 5)
            println(17 % 5)
            println(-7)
        }
    """.trimIndent()))

    @Test fun ch5_string_ops() = assertEquals("hello world\nababab", run("""
        func main() {
            println("hello " + "world")
            println("ab" * 3)
        }
    """.trimIndent()))

    @Test fun ch5_compound() = assertEquals("3", run("""
        func main() {
            var n = 10
            n += 5
            n -= 2
            n *= 3
            n /= 3
            n %= 5
            println(n)
        }
    """.trimIndent()))

    @Test fun ch5_ranges() = assertEquals("15\n5", run("""
        func main() {
            var sum = 0
            for i in 1..5 { sum += i }
            println(sum)
            var count = 0
            for i in 0..<5 { count += 1 }
            println(count)
        }
    """.trimIndent()))

    @Test fun ch6_interpolation() = assertEquals("Hello, Azora!\n3 + 4 = 7\nfirst is 10\ncount is 3", run("""
        func main() {
            var name = "Azora"
            println("Hello, ${'$'}name!")
            var x = 3
            var y = 4
            println("${'$'}x + ${'$'}y = ${'$'}{x + y}")
            var items = [10, 20, 30]
            println("first is ${'$'}{items[0]}")
            println("count is ${'$'}{items.length}")
        }
    """.trimIndent()))

    @Test fun ch6_concat() = assertEquals("Hello, World!\n13", run("""
        func main() {
            var first = "Hello"
            var second = "World"
            var combined = first + ", " + second + "!"
            println(combined)
            println(combined.length)
        }
    """.trimIndent()))

    @Test fun ch7_arrays() = assertEquals("5\n5", run("""
        func main() {
            var a = [1, 2, 3]
            a.add(4)
            a.add(5)
            println(a.length)
            println(a[4])
        }
    """.trimIndent()))

    @Test fun ch7_iteration() = assertEquals("apple\nbanana\ncherry\nate 3 fruits", run("""
        func main() {
            var fruits = ["apple", "banana", "cherry"]
            var total = 0
            for i in 0..<fruits.length {
                println(fruits[i])
                total += 1
            }
            println("ate ${'$'}{total} fruits")
        }
    """.trimIndent()))

    @Test fun ch8_classify() = assertEquals("B\nF", run("""
        func classify(score: Int): String {
            if score >= 90 { return "A" }
            else if score >= 80 { return "B" }
            else if score >= 70 { return "C" }
            else { return "F" }
        }
        func main() {
            println(classify(85))
            println(classify(60))
        }
    """.trimIndent()))

    @Test fun ch8_loop_break() = assertEquals("8\n42", run("""
        func main() {
            var i = 0
            var found = -1
            loop {
                if i >= 100 { break }
                if i * i == 64 {
                    found = i
                    break
                }
                i += 1
            }
            println(found)
            var sum = 0
            for n in 0..<10 {
                if n == 3 { continue }
                sum += n
            }
            println(sum)
        }
    """.trimIndent()))

    @Test fun ch9_functions() = assertEquals("25\n42", run("""
        func square(n: Int) { println(n * n) }
        func double(n: Int) { return n * 2 }
        func main() {
            square(5)
            println(double(21))
        }
    """.trimIndent()))

    @Test fun ch9_recursion() = assertEquals("120", run("""
        func factorial(n: Int): Int {
            if n <= 1 { return 1 }
            return n * factorial(n - 1)
        }
        func main() { println(factorial(5)) }
    """.trimIndent()))

    @Test fun ch10_structs() = assertEquals("0\n15", run("""
        pack Counter {
            var count: Int
            fin label: String
        }
        func main() {
            var c = Counter(0, "hits")
            println(c.count)
            c.count = 10
            c.count += 5
            println(c.count)
        }
    """.trimIndent()))

    @Test fun ch10_structs_in_array() = assertEquals("3\n0", run("""
        pack Point {
            var x: Int
            var y: Int
        }
        func origin(): Point {
            return Point(0, 0)
        }
        func main() {
            var points = [Point(1, 2), Point(3, 4), origin()]
            println(points[1].x)
            println(points[2].y)
        }
    """.trimIndent()))

    @Test fun ch11_zone() = assertEquals("3\n1", run("""
        func main() {
            var x = 1
            zone {
                var y = 2
                println(x + y)
            }
            println(x)
        }
    """.trimIndent()))

    @Test fun ch11_shadowing() = assertEquals("2\n1\n1", run("""
        func main() {
            var x = 1
            zone {
                var x = 2
                println(x)
                println(::x)
            }
            println(x)
        }
    """.trimIndent()))

    @Test fun ch11_friend_zone() = assertEquals("15", run("""
        func main() {
            friend zone {
                var total = 0
                total += 10
            }
            friend zone {
                total += 5
                println(total)
            }
        }
    """.trimIndent()))

    @Test fun ch12_inline_fin() = assertEquals("8", run("""
        func main() {
            inline fin SIZE = 8
            println(SIZE)
        }
    """.trimIndent()))

    @Test fun ch12_inline_fin_computed() = assertEquals("64", run("""
        func main() {
            inline fin AREA = 8 * 8
            println(AREA)
        }
    """.trimIndent()))

    @Test fun ch12_inline_if() = assertEquals("== debug mode ==", run("""
        inline fin DEBUG = true

        deepinline if DEBUG {
            func debugBanner() {
                println("== debug mode ==")
            }
        }

        func main() {
            debugBanner()
        }
    """.trimIndent()))

    @Test fun ch12_inline_block() = assertEquals("hello", run("""
        func main() {
            inline {
                fin GREETING = "hello"
                println(GREETING)
            }
        }
    """.trimIndent()))

    @Test fun ch12_inline_func() = assertEquals("42", run("""
        inline func double(x: Int): Int {
            return x * 2
        }
        func main() { println(double(21)) }
    """.trimIndent()))

    @Test fun ch12_deepinline_noinline() = assertEquals("limit exceeded", run("""
        func main() {
            deepinline {
                fin LIMIT = 10
                if LIMIT > 5 {
                    noinline println("limit exceeded")
                }
            }
        }
    """.trimIndent()))

    @Test fun ch13_test_assert() {
        // Tests run after main; assert must pass. Output is "running".
        assertEquals("running", run("""
            func add(a: Int, b: Int): Int { return a + b }
            test "addition works" {
                fin result = add(2, 3)
                assert result == 5 { "2 + 3 should be 5" }
            }
            func main() { println("running") }
        """.trimIndent()))
    }

    @Test fun ch13_divide_assert() = assertEquals("5", run("""
        func divide(a: Int, b: Int): Int {
            assert b != 0 { "division by zero" }
            return a / b
        }
        func main() { println(divide(10, 2)) }
    """.trimIndent()))

    @Test fun ch13_trace() = assertEquals("[TRACE] processing value\n10", run("""
        func process(x: Int): Int {
            trace { "processing value" }
            return x * 2
        }
        func main() { println(process(5)) }
    """.trimIndent()))

    @Test fun ch13_inline_assert() = assertEquals("16", run("""
        func main() {
            inline fin SIZE = 16
            inline assert SIZE > 0 { "SIZE must be positive" }
            println(SIZE)
        }
    """.trimIndent()))

    @Test fun ch14_targets() {
        val r = compile("""
            func add(a: Int, b: Int): Int {
                return a + b
            }
            func main() {
                println(add(2, 3))
            }
        """.trimIndent())
        assertTrue("fun add(a: Int, b: Int): Int" in r.kotlin, r.kotlin)
        assertTrue("function add(a: number, b: number): number" in r.typescript, r.typescript)
    }
}
