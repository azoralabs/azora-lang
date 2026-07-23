package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the code examples shipped in the playground (`azora-lang-code-website`) compile and run
 * through the same path the WASM bundle exposes (Compiler.compile + IrInterpreter.interpret).
 */
class PlaygroundExamplesTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun hello() = assertEquals("Hello, world!", run("""module playground
import std.io
func main() { std::println("Hello, world!") }"""))

    @Test fun variables() = assertEquals("Hello, Azora!" + "\n" + "count is 6", run("""module playground
import std.io
func main() {
    var count = 0
    count = count + 1
    count += 5
    fin name = "Azora"
    let greeting = "Hello, ${'$'}{name}!"
    std::println(greeting)
    std::println("count is ${'$'}{count}")
}"""))

    @Test fun functions() = assertEquals("7" + "\n" + "120", run("""module playground
import std.io
func add(a: Int, b: Int): Int { return a + b }
func factorial(n: Int): Int {
    if n <= 1 { return 1 }
    return n * factorial(n - 1)
}
func main() {
    std::println("${'$'}{add(3, 4)}")
    std::println("${'$'}{factorial(5)}")
}"""))

    @Test fun controlFlow() = assertEquals("sum 1..10 = 55" + "\n" + "stopped at 7" + "\n" + "even count = 5", run("""module playground
import std.io
func main() {
    var sum = 0
    for i in 1..10 { sum += i }
    std::println("sum 1..10 = ${'$'}{sum}")
    var i = 0
    loop {
        i += 1
        if i == 7 { break }
    }
    std::println("stopped at ${'$'}{i}")
    var evens = 0
    for n in 0..<10 {
        if n % 2 != 0 { continue }
        evens += 1
    }
    std::println("even count = ${'$'}{evens}")
}"""))

    @Test fun arrays() = assertEquals("10" + "\n" + "3" + "\n" + "4" + "\n" + "99" + "\n" + "total = 189", run("""module playground
import std.io
func main() {
    var nums = arr![10, 20, 30]
    std::println(nums[0])
    std::println(nums.length)
    nums.add(40)
    nums[0] = 99
    std::println(nums.length)
    std::println(nums[0])
    var total = 0
    for i in 0..<nums.length { total += nums[i] }
    std::println("total = ${'$'}{total}")
}"""))

    @Test fun strings() = assertEquals("Hello, Azora!" + "\n" + "3 x 3 = 9" + "\n" + "ababab" + "\n" + "length is 5", run("""module playground
import std.io
func main() {
    var name = "Azora"
    var n = 3
    std::println("Hello, ${'$'}name!")
    std::println("${'$'}{n} x ${'$'}{n} = ${'$'}{n * n}")
    std::println("ab" * 3)
    std::println("length is ${'$'}{name.length}")
}"""))

    @Test fun structs() = assertEquals("3, 4" + "\n" + "10, 5" + "\n" + "last = 3, 3", run("""module playground
import std.io
pack Point {
    var x: Int
    var y: Int
}
func main() {
    var p = Point(3, 4)
    std::println("${'$'}{p.x}, ${'$'}{p.y}")
    p.x = 10
    p.y += 1
    std::println("${'$'}{p.x}, ${'$'}{p.y}")
    var points = [Point(1, 1), Point(2, 2), Point(3, 3)]
    std::println("last = ${'$'}{points[2].x}, ${'$'}{points[2].y}")
}"""))

    @Test fun operators() = assertEquals("30" + "\n" + "3" + "\n" + "2" + "\n" + "sum 1..<5 = 10", run("""module playground
import std.io
func main() {
    var n = 10
    n += 5
    n *= 2
    std::println(n)
    std::println(17 / 5)
    std::println(17 % 5)
    var sum = 0
    for i in 1..<5 { sum += i }
    std::println("sum 1..<5 = ${'$'}{sum}")
}"""))

    @Test fun scopes() = assertEquals("inner 2" + "\n" + "outer 1" + "\n" + "after 1", run("""module playground
import std.io
func main() {
    var x = 1
    zone {
        var x = 2
        std::println("inner ${'$'}{x}")
        std::println("outer ${'$'}{::x}")
    }
    std::println("after ${'$'}{x}")
}"""))

    @Test fun ctce() = assertEquals("size: 8" + "\n" + "squared: 25", run("""module playground
import std.io
inline func square(x: Int): Int { return x * x }
func main() {
    inline fin SIZE = 8
    std::println("size: ${'$'}{SIZE}")
    std::println("squared: ${'$'}{square(5)}")
}"""))

    @Test fun testing() = assertEquals("running tests...", run("""module playground
import std.io
func factorial(n: Int): Int {
    if n <= 1 { return 1 }
    return n * factorial(n - 1)
}
test "factorial of 5 is 120" { assert factorial(5) == 120 { "5! should be 120" } }
test "factorial of 0 is 1" { assert factorial(0) == 1 { "0! should be 1" } }
func main() { std::println("running tests...") }"""))

    @Test fun codegenWorks() {
        // The bundle's generateJavaScript/generateLlvmIr/generateWasm wrap these.
        val r = Compiler().compile("import std.io\nfunc main() { std::println(42) }", release = false)
        assertIs<CompilationResult.Success>(r)
        assertTrue("function main()" in r.javascript, r.javascript)
        assertTrue("console.log" in r.javascript, r.javascript)
        assertTrue("ret" in r.llvm || "puts" in r.llvm, r.llvm)
    }

    // ── Modern-language examples (chapters 26–35 era) ───────────────────────

    @Test fun maps() = assertEquals("90\n75\n80", run("""module playground
import std.io
func main() {
    var scores = ["alice": 90, "bob": 75]
    scores["carol"] = 88
    std::println(scores["alice"])
    std::println(scores["bob"])
    scores["bob"] = 80
    std::println(scores["bob"])
}"""))

    @Test fun taggedUnions() = assertEquals("75\n24\n0", run("""module playground
import std.io
slot Shape {
    Circle(Int)
    Rect(Int, Int)
    Empty
}
func area(s: Shape): Int {
    when s {
        Shape.Circle(r) -> { return r * r * 3 }
        Shape.Rect(w, h) -> { return w * h }
        Shape.Empty -> { return 0 }
    }
}
func main() {
    std::println(area(Shape.Circle(5)))
    std::println(area(Shape.Rect(4, 6)))
    std::println(area(Shape.Empty))
}"""))

    @Test fun generators() = assertEquals("30", run("""module playground
import std.io
flow squares(n: Int): Int {
    for i in 0..<n { yield i * i }
}
func main() {
    var sum = 0
    for x in squares(5) { sum += x }
    std::println(sum)
}"""))

    @Test fun dependencyInjection() = assertEquals("1\n2", run("""module playground
import std.io
solo Counter {
    var n: Int = 0
    func inc(): Int {
        self.n = self.n + 1
        return self.n
    }
}
func main() {
    std::println(inject Counter.inc())
    std::println(inject Counter.inc())
}"""))

    @Test fun pointers() = assertEquals("10\n20\n99", run("""module playground
import std.io
func main() {
    var p: Int* = alloc arr![10, 20, 30]
    std::println(*p)
    std::println(*(p + 1))
    *(p + 2) = 99
    std::println(*(p + 2))
}"""))

    @Test fun variadic() = assertEquals("6\n100", run("""module playground
import std.io
func<...T> sumAll(first: Int, rest: ...T): Int {
    var total = first
    for x in rest { total = total + x }
    return total
}
func main() {
    std::println(sumAll(1, 2, 3))
    std::println(sumAll(10, 20, 30, 40))
}"""))
}
