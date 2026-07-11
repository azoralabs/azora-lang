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
        // The other seven backends are produced too.
        assertTrue(r.swift.isNotBlank())
        assertTrue(r.dart.isNotBlank())
        assertTrue(r.csharp.isNotBlank())
        assertTrue(r.python.isNotBlank())
        assertTrue(r.rust.isNotBlank())
        assertTrue(r.wasm.isNotBlank())
        assertTrue(r.llvm.isNotBlank())
    }

    // ── Chapters 26–35 (modern language) ────────────────────────────────────

    @Test fun ch26_maps_read() = assertEquals("1\n3", run("""
        func main() {
            var m = ["a": 1, "b": 2, "c": 3]
            println(m["a"])
            println(m["c"])
        }
    """.trimIndent()))

    @Test fun ch26_maps_update() = assertEquals("99", run("""
        func main() {
            var m = ["a": 1, "b": 2, "c": 3]
            m["b"] = 99
            println(m["b"])
        }
    """.trimIndent()))

    @Test fun ch26_maps_int_keys() = assertEquals("30\n40", run("""
        func main() {
            var scores = [10: 10, 20: 20, 30: 30]
            scores[40] = 40
            println(scores[30])
            println(scores[40])
        }
    """.trimIndent()))

    @Test fun ch27_slot_destructure() = assertEquals("42", run("""
        slot Option {
            Some(Int)
            None
        }
        func main() {
            var o = Option.Some(42)
            when o {
                Option.Some(v) -> { println(v) }
                Option.None    -> { println("nothing") }
            }
        }
    """.trimIndent()))

    @Test fun ch27_slot_multi_payload() = assertEquals("7", run("""
        slot Shape {
            Circle(Int)
            Rect(Int, Int)
            Point
        }
        func main() {
            var s = Shape.Rect(3, 4)
            when s {
                Shape.Circle(r)   -> { println(r) }
                Shape.Rect(w, h)  -> { println(w + h) }
                Shape.Point       -> { println("0") }
            }
        }
    """.trimIndent()))

    @Test fun ch28_node_basic() = assertEquals("Rex\n...", run("""
        node Animal(name: String) {
            func speak(): String { return "..." }
            func describe(): String { return self.name }
        }
        func main() {
            var a = Animal("Rex")
            println(a.describe())
            println(a.speak())
        }
    """.trimIndent()))

    @Test fun ch28_leaf_override() = assertEquals("Rex\nWoof", run("""
        node Animal(name: String) {
            func speak(): String { return "generic" }
        }
        leaf Dog(name: String) : Animal(name) {
            repl func speak(): String { return "Woof" }
        }
        func main() {
            var d = Dog("Rex")
            println(d.name)
            println(d.speak())
        }
    """.trimIndent()))

    @Test fun ch28_dynamic_dispatch() = assertEquals("Woof", run("""
        node Animal(name: String) {
            func speak(): String { return "generic" }
        }
        leaf Dog(name: String) : Animal(name) {
            repl func speak(): String { return "Woof" }
        }
        func main() {
            var a: Animal = Dog("Rex")
            println(a.speak())
        }
    """.trimIndent()))

    @Test fun ch29_alloc_scalar() = assertEquals("42\n99", run("""
        func main() {
            var p: Int* = alloc 42
            println(*p)
            *p = 99
            println(*p)
        }
    """.trimIndent()))

    @Test fun ch29_pointer_arithmetic() = assertEquals("10\n20\n99\n3", run("""
        func main() {
            var p: Int* = alloc [10, 20, 30]
            println(*p)
            println(*(p + 1))
            *(p + 2) = 99
            println(*(p + 2))
            var q = p + 3
            println(q - p)
        }
    """.trimIndent()))

    @Test fun ch29_isolated() = assertEquals("1", run("""
        func main() {
            var original = [1, 2, 3]
            var copy = isolated(original)
            copy[0] = 99
            println(original[0])
        }
    """.trimIndent()))

    @Test fun ch30_error_set() = assertEquals("Bad", run("""
        fail E { Bad }
        func f(): Int!E {
            fail E.Bad
            return 0
        }
        func main() {
            try {
                println(f())
            } catch {
                e -> println(e)
            }
        }
    """.trimIndent()))

    @Test fun ch31_flow() = assertEquals("0\n1\n4\n9", run("""
        flow squares(n: Int): Int {
            for i in 0..<n { yield i * i }
        }
        func main() {
            for x in squares(4) { println(x) }
        }
    """.trimIndent()))

    @Test fun ch31_channel() = assertEquals("10\n20", run("""
        func produce(ch: Channel): Int {
            ch.send(10)
            ch.send(20)
            ch.close()
            return 0
        }
        func main() {
            var ch = channel()
            var p = task { produce(ch) }
            await p
            println(ch.receive())
            println(ch.receive())
        }
    """.trimIndent()))

    @Test fun ch32_solo_get() = assertEquals("42", run("""
        solo Config {
            var value: Int = 42
            func get(): Int { return self.value }
        }
        func main() {
            println(inject Config.get())
        }
    """.trimIndent()))

    @Test fun ch32_solo_shared_instance() = assertEquals("1\n2\n3", run("""
        solo Counter {
            var n: Int = 0
            func inc(): Int {
                self.n = self.n + 1
                return self.n
            }
        }
        func main() {
            var c1 = inject Counter
            println(c1.inc())
            println(c1.inc())
            var c2 = inject Counter
            println(c2.inc())
        }
    """.trimIndent()))

    @Test fun ch33_bridge_math() = assertEquals("4.0\n0.0", run("""
        bridge C {
            func sqrt(x: Real): Real
            func sin(x: Real): Real
        }
        func main() {
            println(sqrt(16.0))
            println(sin(0.0))
        }
    """.trimIndent()))

    @Test fun ch33_bridge_pow() = assertEquals("1024.0", run("""
        bridge C {
            func pow(val: Real, exp: Real): Real
        }
        func main() {
            println(pow(2.0, 10.0))
        }
    """.trimIndent()))

    @Test fun ch34_rem() = assertEquals("0\n42", run("""
        func main() {
            rem count: Int = 0
            println(count)
            count = 42
            println(count)
        }
    """.trimIndent()))

    @Test fun ch34_effect() = assertEquals("hello\ndone", run("""
        func main() {
            rem msg: String = "hello"
            effect {
                println(msg)
            }
            println("done")
        }
    """.trimIndent()))

    @Test fun ch34_view() = assertEquals("Hello, World!", run("""
        view Greet(name: String) {
            println("Hello, " + name + "!")
        }
        func main() {
            Greet("World")
        }
    """.trimIndent()))

    @Test fun ch35_variadic() = assertEquals("3\n10", run("""
        func<...T> variadicSum(first: Int, rest: ...T): Int {
            var total = first
            for x in rest { total = total + x }
            return total
        }
        func main() {
            println(variadicSum(1, 2))
            println(variadicSum(1, 2, 3, 4))
        }
    """.trimIndent()))

    @Test fun ch35_deco() = assertEquals("hi", run("""
        deco Log { msg: String }
        @Log("entry")
        func greet(): String { return "hi" }
        func main() {
            println(greet())
        }
    """.trimIndent()))

    @Test fun ch35_visibility() = assertEquals("ok\nprivate", run("""
        expose func helper(): String { return "ok" }
        confine func secret(): String { return "private" }
        func main() {
            println(helper())
            println(secret())
        }
    """.trimIndent()))

    @Test fun ch35_threadlocal() = assertEquals("42", run("""
        threadlocal fin answer = 42
        func main() {
            println(answer)
        }
    """.trimIndent()))
}
