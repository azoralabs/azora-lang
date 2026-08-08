package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * A lambda reaches the scope around it only through its capture list
 * (LAMBDA_CONTEXT_CAPTURE_DIP.MD §4). Capture is never implicit: `[=]`, `[&]`
 * and `[!]` are the three ways to ask for it, and writing no brackets asks for
 * none.
 */
class LambdaCaptureTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun errors(source: String): List<String> =
        assertIs<CompilationResult.Failure>(Compiler().compile(source)).errors

    @Test fun aBracketLessLambdaCannotReachTheScopeAroundIt() {
        val found = errors("""
            func main() {
                var n = 0
                fin cb = { n = n + 1 }
                cb()
            }
        """.trimIndent())
        assertTrue(
            found.any { "'n' is not in scope" in it && "no capture list" in it },
            found.toString(),
        )
    }

    /** The diagnostic names every way to fix it, in the spelling each one has. */
    @Test fun theDiagnosticNamesEveryCaptureItCouldHaveWritten() {
        val message = errors("""
            func main() {
                var n = 0
                fin cb = { n = n + 1 }
                cb()
            }
        """.trimIndent()).first { "not in scope" in it }
        for (fix in listOf("'[n.&]'", "'[n.!]'", "'[n]'", "'[&]'")) {
            assertTrue(fix in message, "$fix missing from: $message")
        }
    }

    @Test fun aSelfContainedLambdaNeedsNoBrackets() {
        assertEquals("hi", run("""
            import std.io
            func main() {
                fin cb = { std::println("hi") }
                cb()
            }
        """.trimIndent()))
    }

    /** A global belongs to the program, not to the scope the closure was made in. */
    @Test fun globalsAreNotCaptured() {
        assertEquals("5", run("""
            import std.io
            fin G = 5
            func main() {
                fin cb = { std::println(G) }
                cb()
            }
        """.trimIndent()))
    }

    @Test fun aParameterIsNotACapture() {
        assertEquals("6", run("""
            import std.io
            func main() {
                fin double: (Int) -> Int = { it * 2 }
                std::println(double(3))
            }
        """.trimIndent()))
    }

    @Test fun eachDefaultCapturesWhatTheBodyReads() {
        assertEquals("7\n3\n2", run("""
            import std.io
            func main() {
                var a = 7
                fin shared = [&] { std::println(a) }
                shared()

                var b = 3
                fin copied = [=] { std::println(b) }
                copied()

                var c = 1
                fin bumped = [!] { c = c + 1 }
                bumped()
                std::println(c)
            }
        """.trimIndent()))
    }

    @Test fun eachNamedCaptureFormWorks() {
        assertEquals("9\n5", run("""
            import std.io
            func main() {
                var read = 9
                fin show = [read.&] { std::println(read) }
                show()

                var count = 1
                fin bump = [count.!] { count = count + 4 }
                bump()
                std::println(count)
            }
        """.trimIndent()))
    }

    /** Naming one capture does not bring its siblings along. */
    @Test fun anUncapturedSiblingIsStillOutOfScope() {
        val found = errors("""
            import std.io
            func main() {
                var a = 1
                var b = 2
                fin cb = [a.&] { std::println(b) }
                cb()
            }
        """.trimIndent())
        assertTrue(found.any { "'b' is not in scope" in it }, found.toString())
    }

    /** A nested lambda has its own list; the outer one's captures are not inherited. */
    @Test fun aNestedLambdaNeedsItsOwnCaptureList() {
        val found = errors("""
            import std.io
            func main() {
                var n = 1
                fin outer = [n.&] {
                    fin inner = { std::println(n) }
                    inner()
                }
                outer()
            }
        """.trimIndent())
        assertTrue(found.any { "'n' is not in scope" in it }, found.toString())
    }

    @Test fun captureOfAnUnknownNameIsReported() {
        val found = errors("""
            func main() {
                fin cb = [nope.&] { 1 }
            }
        """.trimIndent())
        assertTrue(
            found.any { "cannot capture 'nope'" in it },
            found.toString(),
        )
    }

    @Test fun aLambdaHasOneCaptureDefault() {
        val found = errors("""
            func main() {
                var n = 1
                fin cb = [&, =] { n }
            }
        """.trimIndent())
        assertTrue(found.any { "one capture default" in it }, found.toString())
    }

    /** A copy needs `Copy`, and the diagnostic names each mode that would work. */
    @Test fun aCopyCaptureRequiresCopy() {
        val found = errors("""
            import std.io
            import std.traits
            func main() {
                var xs = std::listOf<Int>()
                fin cb = [xs] { std::println(xs.size) }
                cb()
            }
        """.trimIndent())
        val message = found.first { "cannot be captured by copy" in it }
        for (fix in listOf("'[xs.&]'", "'[xs.clone()]'", "'[take xs]'")) {
            assertTrue(fix in message, "$fix missing from: $message")
        }
    }

    @Test fun aBorrowAsksNothingOfTheValue() {
        assertEquals("0", run("""
            import std.io
            func main() {
                var xs = std::listOf<Int>()
                fin cb = [xs.&] { std::println(xs.size) }
                cb()
            }
        """.trimIndent()))
    }

    /** `take` moves the outer binding, and the existing use-after-take reports it. */
    @Test fun aMoveCaptureEmptiesTheOuterBinding() {
        val found = errors("""
            import std.io
            func main() {
                var s: String = "hi"
                fin cb = [take s] { std::println(s) }
                std::println(s)
            }
        """.trimIndent())
        assertTrue(found.any { "use of taken value 's'" in it }, found.toString())
    }

    /** Inside the closure the moved name is the closure's own binding, not the stale one. */
    @Test fun aMovedCaptureIsUsableInsideTheClosure() {
        assertEquals("hi", run("""
            import std.io
            func main() {
                var s: String = "hi"
                fin cb = [take s] { std::println(s) }
                cb()
            }
        """.trimIndent()))
    }

    /**
     * A closure that escapes must own what it captures (§4.6): a borrow may not
     * outlive its owner, and the scope it borrowed from is gone by the time an
     * escaped closure runs.
     */
    @Test fun aReturnedClosureMayNotBorrow() {
        val found = errors("""
            func make(): () -> Int {
                var n = 5
                return [n.&] { n }
            }
            func main() { }
        """.trimIndent())
        assertTrue(
            found.any { "is returned while borrowing 'n'" in it && "[take n]" in it },
            found.toString(),
        )
    }

    @Test fun aReturnedClosureMayOwnWhatItCaptures() {
        assertEquals("5", run("""
            import std.io
            func make(): () -> Int {
                var n = 5
                return [n] { n }
            }
            func main() { std::println(make()()) }
        """.trimIndent()))
    }

    @Test fun anEscapingDefaultIsRefusedOnAReturnedClosure() {
        val found = errors("""
            func make(): () -> Int {
                var n = 5
                return [&] { n }
            }
            func main() { }
        """.trimIndent())
        assertTrue(found.any { "cannot be its default" in it }, found.toString())
    }

    /** `escaping` marks a parameter that keeps the callable past the call. */
    @Test fun anEscapingParameterRequiresOwnedCaptures() {
        val found = errors("""
            func onEvent(handler: escaping (Int) -> Unit) { }
            func main() {
                var n = 1
                onEvent([n.&] { std::println(n) })
            }
        """.trimIndent())
        assertTrue(
            found.any { "passed to an escaping parameter while borrowing 'n'" in it },
            found.toString(),
        )
    }

    /**
     * `escaping` describes the position, not the value - a lambda has no opinion
     * about whether it will be kept, so it is assignable to either.
     */
    @Test fun escapingBelongsToTheTypeNotTheValue() {
        assertEquals("ok", run("""
            import std.io
            pack Button { var onClick: escaping (Int) -> Unit = { } }
            func onEvent(handler: escaping (Int) -> Unit) { }
            func twice(f: (Int) -> Unit) { }
            func main() {
                fin b = Button({ })
                onEvent({ })
                twice({ })
                std::println("ok")
            }
        """.trimIndent()))
    }

    /** Receivers and captures share the bracket list; a `:` is the whole distinction. */
    @Test fun receiversAndCapturesShareTheBracketList() {
        assertEquals("4", run("""
            import std.io
            pack Sink { var total: Int = 0 }
            impl Sink { func push[self: Self!](n: Int) { self.total = self.total + n } }
            func main() {
                var step = 4
                fin fill = [sink: Sink!, step.&] { sink.push(step) }
                var sink = Sink(0)
                with sink { fill() }
                std::println(sink.total)
            }
        """.trimIndent()))
    }
}
