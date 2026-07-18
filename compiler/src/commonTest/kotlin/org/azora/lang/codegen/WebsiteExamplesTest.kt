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

    @Test fun ch1_hello() = assertEquals("Hello, Azora!", run("""import std.io
func main() { std::println("Hello, Azora!") }"""))

    @Test fun ch2_program() = assertEquals("Hello, Azora!\nversion 1.0.0", run("""
        import std.io
        fin VERSION = "1.0.0"
        func greet(name: String): String {
            return "Hello, " + name + "!"
        }
        func main() {
            std::println(greet("Azora"))
            std::println("version " + VERSION)
        }
    """.trimIndent()))

    @Test fun ch3_bindings() = assertEquals("15\n100\n3", run("""
        import std.io
        func main() {
            var count = 0
            count = 10
            count = count + 5
            std::println(count)
            let limit = 100
            std::println(limit)
            fin pi = 3
            std::println(pi)
        }
    """.trimIndent()))

    @Test fun ch4_literals() = assertEquals("255\n63\n10\n1000000\n3.14\n1500.0", run("""
        import std.io
        func main() {
            std::println(0xFF)
            std::println(0o77)
            std::println(0b1010)
            std::println(1_000_000)
            std::println(3.14)
            std::println(1.5e3)
        }
    """.trimIndent()))

    @Test fun ch4_char() = assertEquals("true\ntrue", run("""
        import std.io
        func main() {
            fin grade = 'A'
            std::println(grade == 'A')
            std::println('a' < 'z')
        }
    """.trimIndent()))

    @Test fun ch5_arithmetic() = assertEquals("5\n6\n20\n3\n2\n-7", run("""
        import std.io
        func main() {
            std::println(2 + 3)
            std::println(10 - 4)
            std::println(4 * 5)
            std::println(17 / 5)
            std::println(17 % 5)
            std::println(-7)
        }
    """.trimIndent()))

    @Test fun ch5_string_ops() = assertEquals("hello world\nababab", run("""
        import std.io
        func main() {
            std::println("hello " + "world")
            std::println("ab" * 3)
        }
    """.trimIndent()))

    @Test fun ch5_compound() = assertEquals("3", run("""
        import std.io
        func main() {
            var n = 10
            n += 5
            n -= 2
            n *= 3
            n /= 3
            n %= 5
            std::println(n)
        }
    """.trimIndent()))

    @Test fun ch5_ranges() = assertEquals("15\n5", run("""
        import std.io
        func main() {
            var sum = 0
            for i in 1..5 { sum += i }
            std::println(sum)
            var count = 0
            for i in 0..<5 { count += 1 }
            std::println(count)
        }
    """.trimIndent()))

    @Test fun ch6_interpolation() = assertEquals("Hello, Azora!\n3 + 4 = 7\nfirst is 10\ncount is 3", run("""
        import std.io
        func main() {
            var name = "Azora"
            std::println("Hello, ${'$'}name!")
            var x = 3
            var y = 4
            std::println("${'$'}x + ${'$'}y = ${'$'}{x + y}")
            var items = [10, 20, 30]
            std::println("first is ${'$'}{items[0]}")
            std::println("count is ${'$'}{items.length}")
        }
    """.trimIndent()))

    @Test fun ch6_concat() = assertEquals("Hello, World!\n13", run("""
        import std.io
        func main() {
            var first = "Hello"
            var second = "World"
            var combined = first + ", " + second + "!"
            std::println(combined)
            std::println(combined.length)
        }
    """.trimIndent()))

    @Test fun ch7_arrays() = assertEquals("5\n5", run("""
        import std.io
        func main() {
            var a = [1, 2, 3]
            a.add(4)
            a.add(5)
            std::println(a.length)
            std::println(a[4])
        }
    """.trimIndent()))

    @Test fun ch7_iteration() = assertEquals("apple\nbanana\ncherry\nate 3 fruits", run("""
        import std.io
        func main() {
            var fruits = ["apple", "banana", "cherry"]
            var total = 0
            for i in 0..<fruits.length {
                std::println(fruits[i])
                total += 1
            }
            std::println("ate ${'$'}{total} fruits")
        }
    """.trimIndent()))

    @Test fun ch8_classify() = assertEquals("B\nF", run("""
        import std.io
        func classify(score: Int): String {
            if score >= 90 { return "A" }
            else if score >= 80 { return "B" }
            else if score >= 70 { return "C" }
            else { return "F" }
        }
        func main() {
            std::println(classify(85))
            std::println(classify(60))
        }
    """.trimIndent()))

    @Test fun ch8_loop_break() = assertEquals("8\n42", run("""
        import std.io
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
            std::println(found)
            var sum = 0
            for n in 0..<10 {
                if n == 3 { continue }
                sum += n
            }
            std::println(sum)
        }
    """.trimIndent()))

    @Test fun ch9_functions() = assertEquals("25\n42", run("""
        import std.io
        func square(n: Int) { std::println(n * n) }
        func double(n: Int) { return n * 2 }
        func main() {
            square(5)
            std::println(double(21))
        }
    """.trimIndent()))

    @Test fun ch9_recursion() = assertEquals("120", run("""
        import std.io
        func factorial(n: Int): Int {
            if n <= 1 { return 1 }
            return n * factorial(n - 1)
        }
        func main() { std::println(factorial(5)) }
    """.trimIndent()))

    @Test fun ch10_structs() = assertEquals("0\n15", run("""
        import std.io
        pack Counter {
            var count: Int
            fin label: String
        }
        func main() {
            var c = Counter(0, "hits")
            std::println(c.count)
            c.count = 10
            c.count += 5
            std::println(c.count)
        }
    """.trimIndent()))

    @Test fun ch10_structs_in_array() = assertEquals("3\n0", run("""
        import std.io
        pack Point {
            var x: Int
            var y: Int
        }
        func origin(): Point {
            return Point(0, 0)
        }
        func main() {
            var points = [Point(1, 2), Point(3, 4), origin()]
            std::println(points[1].x)
            std::println(points[2].y)
        }
    """.trimIndent()))

    @Test fun ch11_zone() = assertEquals("3\n1", run("""
        import std.io
        func main() {
            var x = 1
            zone {
                var y = 2
                std::println(x + y)
            }
            std::println(x)
        }
    """.trimIndent()))

    @Test fun ch11_shadowing() = assertEquals("2\n1\n1", run("""
        import std.io
        func main() {
            var x = 1
            zone {
                var x = 2
                std::println(x)
                std::println(::x)
            }
            std::println(x)
        }
    """.trimIndent()))

    @Test fun ch11_friend_zone() = assertEquals("15", run("""
        import std.io
        func main() {
            friend zone {
                var total = 0
                total += 10
            }
            friend zone {
                total += 5
                std::println(total)
            }
        }
    """.trimIndent()))

    @Test fun ch12_inline_fin() = assertEquals("8", run("""
        import std.io
        func main() {
            inline fin SIZE = 8
            std::println(SIZE)
        }
    """.trimIndent()))

    @Test fun ch12_inline_fin_computed() = assertEquals("64", run("""
        import std.io
        func main() {
            inline fin AREA = 8 * 8
            std::println(AREA)
        }
    """.trimIndent()))

    @Test fun ch12_inline_if() = assertEquals("== debug mode ==", run("""
        import std.io
        inline fin DEBUG = true

        deepinline if DEBUG {
            func debugBanner() {
                std::println("== debug mode ==")
            }
        }

        func main() {
            debugBanner()
        }
    """.trimIndent()))

    @Test fun ch12_inline_block() = assertEquals("hello", run("""
        import std.io
        func main() {
            inline {
                fin GREETING = "hello"
                std::println(GREETING)
            }
        }
    """.trimIndent()))

    @Test fun ch12_inline_func() = assertEquals("42", run("""
        import std.io
        inline func double(x: Int): Int {
            return x * 2
        }
        func main() { std::println(double(21)) }
    """.trimIndent()))

    @Test fun ch12_deepinline_noinline() = assertEquals("limit exceeded", run("""
        import std.io
        func main() {
            deepinline {
                fin LIMIT = 10
                if LIMIT > 5 {
                    noinline std::println("limit exceeded")
                }
            }
        }
    """.trimIndent()))

    @Test fun ch13_test_assert() {
        // Tests run after main; assert must pass. Output is "running".
        assertEquals("running", run("""
            import std.io
            func add(a: Int, b: Int): Int { return a + b }
            test "addition works" {
                fin result = add(2, 3)
                assert result == 5 { "2 + 3 should be 5" }
            }
            func main() { std::println("running") }
        """.trimIndent()))
    }

    @Test fun ch13_divide_assert() = assertEquals("5", run("""
        import std.io
        func divide(a: Int, b: Int): Int {
            assert b != 0 { "division by zero" }
            return a / b
        }
        func main() { std::println(divide(10, 2)) }
    """.trimIndent()))

    @Test fun ch13_trace() = assertEquals("[TRACE] processing value\n10", run("""
        import std.io
        func process(x: Int): Int {
            trace { "processing value" }
            return x * 2
        }
        func main() { std::println(process(5)) }
    """.trimIndent()))

    @Test fun ch13_inline_assert() = assertEquals("16", run("""
        import std.io
        func main() {
            inline fin SIZE = 16
            inline assert SIZE > 0 { "SIZE must be positive" }
            std::println(SIZE)
        }
    """.trimIndent()))

    @Test fun ch14_targets() {
        val r = compile("""
            import std.io
            func add(a: Int, b: Int): Int {
                return a + b
            }
            func main() {
                std::println(add(2, 3))
            }
        """.trimIndent())
        assertTrue("function add(a, b)" in r.javascript, r.javascript)
        // The other backends are produced too.
        assertTrue(r.wasm.isNotBlank())
        assertTrue(r.llvm.isNotBlank())
    }

    // ── Chapters 26–35 (modern language) ────────────────────────────────────

    @Test fun ch26_maps_read() = assertEquals("1\n3", run("""
        import std.io
        func main() {
            var m = ["a": 1, "b": 2, "c": 3]
            std::println(m["a"])
            std::println(m["c"])
        }
    """.trimIndent()))

    @Test fun ch26_maps_update() = assertEquals("99", run("""
        import std.io
        func main() {
            var m = ["a": 1, "b": 2, "c": 3]
            m["b"] = 99
            std::println(m["b"])
        }
    """.trimIndent()))

    @Test fun ch26_maps_int_keys() = assertEquals("30\n40", run("""
        import std.io
        func main() {
            var scores = [10: 10, 20: 20, 30: 30]
            scores[40] = 40
            std::println(scores[30])
            std::println(scores[40])
        }
    """.trimIndent()))

    @Test fun ch27_slot_destructure() = assertEquals("42", run("""
        import std.io
        slot Option {
            Some(Int)
            None
        }
        func main() {
            var o = Option.Some(42)
            when o {
                Option.Some(v) -> { std::println(v) }
                Option.None    -> { std::println("nothing") }
            }
        }
    """.trimIndent()))

    @Test fun ch27_slot_multi_payload() = assertEquals("7", run("""
        import std.io
        slot Shape {
            Circle(Int)
            Rect(Int, Int)
            Point
        }
        func main() {
            var s = Shape.Rect(3, 4)
            when s {
                Shape.Circle(r)   -> { std::println(r) }
                Shape.Rect(w, h)  -> { std::println(w + h) }
                Shape.Point       -> { std::println("0") }
            }
        }
    """.trimIndent()))

    @Test fun ch28_node_basic() = assertEquals("Rex\n...", run("""
        import std.io
        node Animal(name: String) {
            func speak(): String { return "..." }
            func describe(): String { return self.name }
        }
        func main() {
            var a = Animal("Rex")
            std::println(a.describe())
            std::println(a.speak())
        }
    """.trimIndent()))

    @Test fun ch28_leaf_override() = assertEquals("Rex\nWoof", run("""
        import std.io
        node Animal(name: String) {
            func speak(): String { return "generic" }
        }
        leaf Dog(name: String) : Animal(name) {
            repl func speak(): String { return "Woof" }
        }
        func main() {
            var d = Dog("Rex")
            std::println(d.name)
            std::println(d.speak())
        }
    """.trimIndent()))

    @Test fun ch28_dynamic_dispatch() = assertEquals("Woof", run("""
        import std.io
        node Animal(name: String) {
            func speak(): String { return "generic" }
        }
        leaf Dog(name: String) : Animal(name) {
            repl func speak(): String { return "Woof" }
        }
        func main() {
            var a: Animal = Dog("Rex")
            std::println(a.speak())
        }
    """.trimIndent()))

    @Test fun ch29_alloc_scalar() = assertEquals("42\n99", run("""
        import std.io
        func main() {
            var p: Int* = alloc 42
            std::println(*p)
            *p = 99
            std::println(*p)
        }
    """.trimIndent()))

    @Test fun ch29_pointer_arithmetic() = assertEquals("10\n20\n99\n3", run("""
        import std.io
        func main() {
            var p: Int* = alloc [10, 20, 30]
            std::println(*p)
            std::println(*(p + 1))
            *(p + 2) = 99
            std::println(*(p + 2))
            var q = p + 3
            std::println(q - p)
        }
    """.trimIndent()))

    @Test fun ch29_isolated() = assertEquals("1", run("""
        import std.io
        func main() {
            var original = [1, 2, 3]
            var copy = isolated(original)
            copy[0] = 99
            std::println(original[0])
        }
    """.trimIndent()))

    @Test fun ch30_error_set() = assertEquals("Bad", run("""
        import std.io
        fail E { Bad }
        func f(): Int!E {
            fail E.Bad
            return 0
        }
        func main() {
            try {
                std::println(f())
            } catch {
                e -> std::println(e)
            }
        }
    """.trimIndent()))

    @Test fun ch31_flow() = assertEquals("0\n1\n4\n9", run("""
        import std.io
        flow squares(n: Int): Int {
            for i in 0..<n { yield i * i }
        }
        func main() {
            for x in squares(4) { std::println(x) }
        }
    """.trimIndent()))

    @Test fun ch31_channel() = assertEquals("10\n20", run("""
        import std.io
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
            std::println(ch.receive())
            std::println(ch.receive())
        }
    """.trimIndent()))

    @Test fun ch32_solo_get() = assertEquals("42", run("""
        import std.io
        solo Config {
            var value: Int = 42
            func get(): Int { return self.value }
        }
        func main() {
            std::println(inject Config.get())
        }
    """.trimIndent()))

    @Test fun ch32_solo_shared_instance() = assertEquals("1\n2\n3", run("""
        import std.io
        solo Counter {
            var n: Int = 0
            func inc(): Int {
                self.n = self.n + 1
                return self.n
            }
        }
        func main() {
            var c1 = inject Counter
            std::println(c1.inc())
            std::println(c1.inc())
            var c2 = inject Counter
            std::println(c2.inc())
        }
    """.trimIndent()))

    @Test fun ch33_bridge_math() = assertEquals("4.0\n0.0", run("""
        import std.io
        bridge C {
            func sqrt(x: Real): Real
            func sin(x: Real): Real
        }
        func main() {
            std::println(sqrt(16.0))
            std::println(sin(0.0))
        }
    """.trimIndent()))

    @Test fun ch33_bridge_pow() = assertEquals("1024.0", run("""
        import std.io
        bridge C {
            func pow(val: Real, exp: Real): Real
        }
        func main() {
            std::println(pow(2.0, 10.0))
        }
    """.trimIndent()))

    @Test fun ch34_rem() = assertEquals("0\n42", run("""
        import std.io
        func main() {
            rem count: Int = 0
            std::println(count)
            count = 42
            std::println(count)
        }
    """.trimIndent()))

    @Test fun ch34_effect() = assertEquals("hello\ndone", run("""
        import std.io
        func main() {
            rem msg: String = "hello"
            effect {
                std::println(msg)
            }
            std::println("done")
        }
    """.trimIndent()))

    @Test fun ch34_view() = assertEquals("Hello, World!", run("""
        import std.io
        view Greet(name: String) {
            std::println("Hello, " + name + "!")
        }
        func main() {
            Greet("World")
        }
    """.trimIndent()))

    @Test fun ch35_variadic() = assertEquals("3\n10", run("""
        import std.io
        func<...T> variadicSum(first: Int, rest: ...T): Int {
            var total = first
            for x in rest { total = total + x }
            return total
        }
        func main() {
            std::println(variadicSum(1, 2))
            std::println(variadicSum(1, 2, 3, 4))
        }
    """.trimIndent()))

    @Test fun ch35_deco() = assertEquals("hi", run("""
        import std.io
        deco Log { fin msg: String }
        @Log("entry")
        func greet(): String { return "hi" }
        func main() {
            std::println(greet())
        }
    """.trimIndent()))

    @Test fun ch35_visibility() = assertEquals("ok\nprivate", run("""
        import std.io
        expose func helper(): String { return "ok" }
        confine func secret(): String { return "private" }
        func main() {
            std::println(helper())
            std::println(secret())
        }
    """.trimIndent()))

    @Test fun ch35_threadlocal() = assertEquals("42", run("""
        import std.io
        threadlocal fin answer = 42
        func main() {
            std::println(answer)
        }
    """.trimIndent()))
}
