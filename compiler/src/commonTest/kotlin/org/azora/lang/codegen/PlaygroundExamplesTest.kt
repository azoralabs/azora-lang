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
func main() { println("Hello, world!") }"""))

    @Test fun variables() = assertEquals("Hello, Azora!" + "\n" + "count is 6", run("""module playground
func main() {
    var count = 0
    count = count + 1
    count += 5
    fin name = "Azora"
    let greeting = "Hello, ${'$'}{name}!"
    println(greeting)
    println("count is ${'$'}{count}")
}"""))

    @Test fun functions() = assertEquals("7" + "\n" + "120", run("""module playground
func add(a: Int, b: Int): Int { return a + b }
func factorial(n: Int): Int {
    if n <= 1 { return 1 }
    return n * factorial(n - 1)
}
func main() {
    println("${'$'}{add(3, 4)}")
    println("${'$'}{factorial(5)}")
}"""))

    @Test fun controlFlow() = assertEquals("sum 1..10 = 55" + "\n" + "stopped at 7" + "\n" + "even count = 5", run("""module playground
func main() {
    var sum = 0
    for i in 1..10 { sum += i }
    println("sum 1..10 = ${'$'}{sum}")
    var i = 0
    loop {
        i += 1
        if i == 7 { break }
    }
    println("stopped at ${'$'}{i}")
    var evens = 0
    for n in 0..<10 {
        if n % 2 != 0 { continue }
        evens += 1
    }
    println("even count = ${'$'}{evens}")
}"""))

    @Test fun arrays() = assertEquals("10" + "\n" + "3" + "\n" + "4" + "\n" + "99" + "\n" + "total = 189", run("""module playground
func main() {
    var nums = [10, 20, 30]
    println(nums[0])
    println(nums.length)
    nums.add(40)
    nums[0] = 99
    println(nums.length)
    println(nums[0])
    var total = 0
    for i in 0..<nums.length { total += nums[i] }
    println("total = ${'$'}{total}")
}"""))

    @Test fun strings() = assertEquals("Hello, Azora!" + "\n" + "3 x 3 = 9" + "\n" + "ababab" + "\n" + "length is 5", run("""module playground
func main() {
    var name = "Azora"
    var n = 3
    println("Hello, ${'$'}name!")
    println("${'$'}{n} x ${'$'}{n} = ${'$'}{n * n}")
    println("ab" * 3)
    println("length is ${'$'}{name.length}")
}"""))

    @Test fun structs() = assertEquals("3, 4" + "\n" + "10, 5" + "\n" + "last = 3, 3", run("""module playground
pack Point {
    var x: Int
    var y: Int
}
func main() {
    var p = Point(3, 4)
    println("${'$'}{p.x}, ${'$'}{p.y}")
    p.x = 10
    p.y += 1
    println("${'$'}{p.x}, ${'$'}{p.y}")
    var points = [Point(1, 1), Point(2, 2), Point(3, 3)]
    println("last = ${'$'}{points[2].x}, ${'$'}{points[2].y}")
}"""))

    @Test fun operators() = assertEquals("30" + "\n" + "3" + "\n" + "2" + "\n" + "sum 1..<5 = 10", run("""module playground
func main() {
    var n = 10
    n += 5
    n *= 2
    println(n)
    println(17 / 5)
    println(17 % 5)
    var sum = 0
    for i in 1..<5 { sum += i }
    println("sum 1..<5 = ${'$'}{sum}")
}"""))

    @Test fun scopes() = assertEquals("inner 2" + "\n" + "outer 1" + "\n" + "after 1", run("""module playground
func main() {
    var x = 1
    zone {
        var x = 2
        println("inner ${'$'}{x}")
        println("outer ${'$'}{::x}")
    }
    println("after ${'$'}{x}")
}"""))

    @Test fun ctce() = assertEquals("size: 8" + "\n" + "squared: 25", run("""module playground
inline func square(x: Int): Int { return x * x }
func main() {
    inline fin SIZE = 8
    println("size: ${'$'}{SIZE}")
    println("squared: ${'$'}{square(5)}")
}"""))

    @Test fun testing() = assertEquals("running tests...", run("""module playground
func factorial(n: Int): Int {
    if n <= 1 { return 1 }
    return n * factorial(n - 1)
}
test "factorial of 5 is 120" { assert factorial(5) == 120 { "5! should be 120" } }
test "factorial of 0 is 1" { assert factorial(0) == 1 { "0! should be 1" } }
func main() { println("running tests...") }"""))

    @Test fun codegenWorks() {
        // The bundle's generateJavaScript/generateLlvmIr/generateWasm wrap these.
        val r = Compiler().compile("func main() { println(42) }", release = false)
        assertIs<CompilationResult.Success>(r)
        assertTrue("function main()" in r.javascript, r.javascript)
        assertTrue("console.log" in r.javascript, r.javascript)
        assertTrue("ret" in r.llvm || "puts" in r.llvm, r.llvm)
    }

    // ── Modern-language examples (chapters 26–35 era) ───────────────────────

    @Test fun maps() = assertEquals("90\n75\n80", run("""module playground
func main() {
    var scores = ["alice": 90, "bob": 75]
    scores["carol"] = 88
    println(scores["alice"])
    println(scores["bob"])
    scores["bob"] = 80
    println(scores["bob"])
}"""))

    @Test fun taggedUnions() = assertEquals("75\n24\n0", run("""module playground
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
    println(area(Shape.Circle(5)))
    println(area(Shape.Rect(4, 6)))
    println(area(Shape.Empty))
}"""))

    @Test fun inheritance() = assertEquals("Woof", run("""module playground
node Animal(name: String) {
    func speak(): String { return "..." }
}
leaf Dog(name: String) : Animal(name) {
    repl func speak(): String { return "Woof" }
}
func main() {
    var d: Animal = Dog("Rex")
    println(d.speak())
}"""))

    @Test fun generators() = assertEquals("30", run("""module playground
flow squares(n: Int): Int {
    for i in 0..<n { yield i * i }
}
func main() {
    var sum = 0
    for x in squares(5) { sum += x }
    println(sum)
}"""))

    @Test fun dependencyInjection() = assertEquals("1\n2", run("""module playground
solo Counter {
    var n: Int = 0
    func inc(): Int {
        self.n = self.n + 1
        return self.n
    }
}
func main() {
    println(inject Counter.inc())
    println(inject Counter.inc())
}"""))

    @Test fun pointers() = assertEquals("10\n20\n99", run("""module playground
func main() {
    var p: Int* = alloc [10, 20, 30]
    println(*p)
    println(*(p + 1))
    *(p + 2) = 99
    println(*(p + 2))
}"""))

    @Test fun variadic() = assertEquals("6\n100", run("""module playground
func<...T> sumAll(first: Int, rest: ...T): Int {
    var total = first
    for x in rest { total = total + x }
    return total
}
func main() {
    println(sumAll(1, 2, 3))
    println(sumAll(10, 20, 30, 40))
}"""))
}
