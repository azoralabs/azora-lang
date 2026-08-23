package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * A lambda reaches the scope around it only through its capture list
 * (LAMBDA_CONTEXT_CAPTURE_DIP.MD §4). Capture is never implicit: `[=]`, `[&]`,
 * `[!]`, and `[take]` ask for it; writing no capture section asks for none.
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

    @Test fun aNamedMutableCaptureRequiresTheOwnershipDot() {
        val found = errors("""
            func main() {
                var n = 0
                fin bump = [n!] { n = n + 1 }
            }
        """.trimIndent())
        assertTrue(found.any { "write 'n.!'" in it }, found.toString())
    }

    @Test fun aSelfContainedLambdaNeedsNoBrackets() {
        assertEquals("hi", run("""
            import std.io
            func main() {
                fin cb = { println("hi") }
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
                fin cb = { println(G) }
                cb()
            }
        """.trimIndent()))
    }

    @Test fun aParameterIsNotACapture() {
        assertEquals("6", run("""
            import std.io
            func main() {
                fin double: (Int) -> Int = { it * 2 }
                println(double(3))
            }
        """.trimIndent()))
    }

    @Test fun eachDefaultCapturesWhatTheBodyReads() {
        assertEquals("7\n3\n2", run("""
            import std.io
            func main() {
                var a = 7
                fin shared = [&] { println(a) }
                shared()

                var b = 3
                fin copied = [=] { println(b) }
                copied()

                var c = 1
                fin bumped = [!] { c = c + 1 }
                bumped()
                println(c)
            }
        """.trimIndent()))
    }

    @Test fun eachNamedCaptureFormWorks() {
        assertEquals("9\n5", run("""
            import std.io
            func main() {
                var read = 9
                fin show = [read.&] { println(read) }
                show()

                var count = 1
                fin bump = [count.!] { count = count + 4 }
                bump()
                println(count)
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
                fin cb = [a.&] { println(b) }
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
                    fin inner = { println(n) }
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
            import std.container.list
            func main() {
                var xs = listOf<Int>()
                fin cb = [xs] { println(xs.size) }
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
            import std.container.list
            func main() {
                var xs = listOf<Int>()
                fin cb = [xs.&] { println(xs.size) }
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
                fin cb = [take s] { println(s) }
                println(s)
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
                fin cb = [take s] { println(s) }
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
            func main() { println(make()()) }
        """.trimIndent()))
    }

    @Test fun anEscapingDefaultRejectsTheBindingsItActuallyBorrows() {
        val found = errors("""
            func make(): () -> Int {
                var n = 5
                return [&] { n }
            }
            func main() { }
        """.trimIndent())
        assertTrue(found.any { "returned while borrowing 'n'" in it }, found.toString())
    }

    @Test fun anUnusedBorrowDefaultDoesNotCaptureTheSurroundingScope() {
        assertEquals("5", run("""
            import std.io
            func make(): () -> Int {
                var untouched = 9
                return [&] { 5 }
            }
            func main() { println(make()()) }
        """.trimIndent()))
    }

    /** `escaping` marks a parameter that keeps the callable past the call. */
    @Test fun anEscapingParameterRequiresOwnedCaptures() {
        val found = errors("""
            func onEvent(handler: escaping (Int) -> Unit) { }
            func main() {
                var n = 1
                onEvent([n.&] { println(n) })
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
                println("ok")
            }
        """.trimIndent()))
    }

    /** The semicolon separates receivers from captures without type-directed guessing. */
    @Test fun receiversAndCapturesShareTheBracketList() {
        assertEquals("4", run("""
            import std.io
            pack Sink { var total: Int = 0 }
            impl Sink { func !.push(n: Int) { self.total = self.total + n } }
            func main() {
                var step = 4
                fin fill = [!, step.&] sink: Sink { sink.push(step) }
                var sink = Sink(0)
                using sink { fill() }
                println(sink.total)
            }
        """.trimIndent()))
    }

    @Test fun aBareBracketNameIsAnExplicitCapture() {
        assertEquals("4", run("""
            import std.io
            func main() {
                var n = 4
                fin read = [n] { n }
                println(read())
            }
        """.trimIndent()))
    }

    @Test fun captureDefaultMayBeOverriddenByName() {
        assertEquals("7\n8", run("""
            import std.io
            func main() {
                var changed = 3
                var copied = 7
                fin update = [=, changed.!] {
                    changed = changed + 5
                    println(copied)
                }
                copied = 9
                update()
                println(changed)
            }
        """.trimIndent()))
    }

    @Test fun captureDefaultHonoursWithoutFence() {
        assertEquals("4", run("""
            import std.io
            func main() {
                var included = 4
                var excluded = 9
                fin read = [&, without excluded] { included }
                println(read())
            }
        """.trimIndent()))
    }

    @Test fun usingAnExcludedCaptureIsAnError() {
        val found = errors("""
            func main() {
                var secret = 9
                fin read = [&, without secret] { secret }
            }
        """.trimIndent())
        assertTrue(found.any { "excluded from this lambda's captures" in it && "without secret" in it }, found.toString())
    }

    @Test fun groupedCaptureExclusionsAreEquivalent() {
        val found = errors("""
            func main() {
                var secret = 9
                var key = 2
                fin read = [&, without (secret, key)] { key }
            }
        """.trimIndent())
        assertTrue(found.any { "excluded from this lambda's captures" in it && "without key" in it }, found.toString())
    }

    @Test fun explicitCaptureCannotOverrideWithoutFence() {
        val found = errors("""
            func main() {
                var secret = 9
                fin read = [&, secret.&, without secret] { secret }
            }
        """.trimIndent())
        assertTrue(found.any { "both captured and excluded" in it }, found.toString())
    }

    @Test fun defaultTakeMovesOnlyUsedFreeBindings() {
        assertEquals("used\nkept", run("""
            import std.io
            func main() {
                fin used = "used"
                fin untouched = "kept"
                fin consume = [take] { println(used) }
                consume()
                println(untouched)
            }
        """.trimIndent()))
    }

    @Test fun defaultTakeRejectsUseOfAnActuallyCapturedBindingAfterward() {
        val found = errors("""
            import std.io
            func main() {
                fin used = "used"
                fin consume = [take] { println(used) }
                println(used)
            }
        """.trimIndent())
        assertTrue(found.any { "use of taken value 'used'" in it }, found.toString())
    }

    @Test fun captureAliasesCoverEveryOwnedAndBorrowedMode() {
        assertEquals("2\ncopy\nclone\nmove", run("""
            import std.io
            func main() {
                var n = 1
                fin copied = "copy"
                fin cloned = "clone"
                fin moved = "move"
                fin consumeAll = [target = n.!, a = copied, b = cloned.clone(), c = take moved] {
                    target = target + 1
                    println(target)
                    println(a)
                    println(b)
                    println(c)
                }
                consumeAll()
            }
        """.trimIndent()))
    }

    @Test fun sharedCaptureCannotBeAssigned() {
        val found = errors("""
            func main() {
                var n = 1
                fin invalid = [n.&] { n = 2 }
            }
        """.trimIndent())
        assertTrue(found.any { "cannot reassign immutable binding 'n'" in it }, found.toString())
    }

    @Test fun mutableCaptureRespectsTheOuterBindingsNameMutability() {
        val found = errors("""
            func main() {
                fin n = 1
                fin invalid = [n.!] { n = 2 }
            }
        """.trimIndent())
        assertTrue(found.any { "cannot reassign immutable binding 'n'" in it }, found.toString())
    }

    @Test fun duplicateCaptureNamesAndSourcesAreRejected() {
        val duplicateName = errors("""
            func main() {
                var a = 1
                var b = 2
                fin invalid = [value = a, value = b] { value }
            }
        """.trimIndent())
        assertTrue(duplicateName.any { "duplicate lambda capture name 'value'" in it }, duplicateName.toString())

        val duplicateSource = errors("""
            func main() {
                var a = 1
                fin invalid = [first = a, second = a] { first + second }
            }
        """.trimIndent())
        assertTrue(duplicateSource.any { "'a' is captured more than once" in it }, duplicateSource.toString())
    }

    @Test fun receiverAndCaptureCannotDeclareTheSameBodyName() {
        val found = errors("""
            func main() {
                var outer = 1
                fin invalid = [&, value = outer] value: Int { value }
            }
        """.trimIndent())
        assertTrue(found.any { "cannot be both a receiver and a capture" in it }, found.toString())
    }

    @Test fun ordinaryAndVariadicGenericLambdasAreAccepted() {
        assertEquals("7\n3\n3", run("""
            import std.io
            func main() {
                fin identity = <T> { value: T -> value }
                fin homogeneous = <T> { ...values: T -> values.size }
                fin heterogeneous = <...T> { values: ...T -> values.size }
                println(identity(7))
                println(homogeneous(1, 2, 3))
                println(heterogeneous(1, "two", true))
            }
        """.trimIndent()))
    }

    @Test fun genericLambdaMayCombineReceiversCapturesAndParameters() {
        assertEquals("12", run("""
            import std.io
            func main() {
                var offset = 2
                fin add = <T>[&] context: T { value: Int -> value + offset }
                using 10 { println(add(10)) }
            }
        """.trimIndent()))
    }

    @Test fun variadicReceiversAreRejectedExplicitly() {
        val found = errors("""
            func main() {
                fin invalid = <T>[&] (...contexts) { 1 }
            }
        """.trimIndent())
        assertTrue(found.any { "contextual receivers cannot be variadic" in it }, found.toString())
    }

    @Test fun asyncLambdaUsesTheSameCaptureGrammar() {
        assertEquals("ready", run("""
            import std.io
            func main() {
                fin message = "ready"
                fin worker = async [take] { message }
                println(await worker)
            }
        """.trimIndent()))
    }

    @Test fun funcIsNeverALambdaIntroducer() {
        val ordinary = errors("""
            func main() { fin invalid = func { 1 } }
        """.trimIndent())
        assertTrue(ordinary.any { "lambdas do not use 'func'" in it }, ordinary.toString())

        val asynchronous = errors("""
            func main() { fin invalid = async func { 1 } }
        """.trimIndent())
        assertTrue(asynchronous.any { "async lambdas do not use 'func'" in it }, asynchronous.toString())
    }

    @Test fun oldMixedCaptureSyntaxHasAMigrationDiagnostic() {
        val found = errors("""
            func main() {
                var n = 1
                fin invalid = [;n.!] { n = 2 }
            }
        """.trimIndent())
        assertTrue(found.any { "'[receivers; captures]'" in it && "one ownership header" in it }, found.toString())
    }

    @Test fun upperScopeAccessCannotBypassCapture() {
        val found = errors("""
            func main() {
                var value = 1
                fin invalid = { ::value }
            }
        """.trimIndent())
        assertTrue(found.any { "cannot bypass a lambda capture boundary" in it }, found.toString())
    }

    @Test fun contextualCallableTypesAlwaysRequireParentheses() {
        val found = errors("""
            fin invalid: (Int, Int). -> Int = [take] (left, right) { left + right }
            func main() { }
        """.trimIndent())
        assertTrue(found.any { "Expected '(' after the callable receiver dot" in it }, found.toString())
    }

    @Test fun contextualCallableTypeWithEmptyParameterListIsAccepted() {
        assertEquals("3", run("""
            import std.io
            pack Left { fin value: Int }
            pack Right { fin value: Int }
            fin add: (Left&, Right&).() -> Int = [&] (left, right) { left.value + right.value }
            func main() {
                using (Left(1), Right(2)) { println(add()) }
            }
        """.trimIndent()))
    }
}
