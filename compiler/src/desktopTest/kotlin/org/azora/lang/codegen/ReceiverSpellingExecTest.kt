/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.LibrarySource
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FUNCTIONS_DIP §5.3: one unnamed receiver is written as its type, and the
 * compiler names it `self`; two or more unnamed receivers are a parenthesised
 * group whose positional view is `self.0`, `self.1`, … Both run the same in the
 * interpreter and in LLVM, and an extension declared in one module is a member
 * of its type wherever that module is imported.
 */
class ReceiverSpellingExecTest {
    private fun run(source: String, libraries: List<LibrarySource> = emptyList()): String {
        val program = source.trimIndent()
        val compiled = Compiler(libraries).compile(program)
        val result = assertIs<CompilationResult.Success>(compiled, (compiled as? CompilationResult.Failure)?.errors.toString())
        val interpreted = IrInterpreter().interpret(result.ir).trim()
        if (libraries.isEmpty() && LlvmExec.available) {
            assertEquals(interpreted, LlvmExec.run(program, false), "LLVM agrees with the interpreter")
        }
        return interpreted
    }

    @Test fun aSingleUnnamedReceiverIsSelf() = assertEquals("12.0\n5\n9 1", run("""
        import std.io
        pack Gauge { var level: Double = 0.0 }
        pack Ticket { var id: Int = 0 }
        pack Box<T> { var value: T }
        func Gauge&.doubled(): Double { return self.level * 2.0 }
        func Gauge!.raise(step: Double) { self.level = self.level + step }
        func Ticket.redeem(): Int { return self.id }
        func<T> Box<T>.unwrap(): T { return take self.value }
        func<T> Box<T>!.replace(value: T) { self.value = value }
        func<T> Box<T>&.present(): Int { return 1 }
        func main() {
            var gauge = Gauge(1.0)
            gauge.raise(5.0)
            println(gauge.doubled())
            println(Ticket(5).redeem())
            var box = Box<Int>(4)
            box.replace(9)
            println("${'$'}{box.present() + 8} ${'$'}{box.present()}")
        }
    """))

    @Test fun anOwnedGenericReceiverMovesItsValue() = assertEquals("7", run("""
        import std.io
        pack Box<T> { var value: T }
        func<T> Box<T>.unwrap(): T { return take self.value }
        func main() { println(Box<Int>(7).unwrap()) }
    """))

    @Test fun aGenericReceiverInstantiatesTheResultFromItsArguments() = assertEquals("8\n9\n10\nab", run("""
        import std.io
        pack Box<T> { var value: T }
        pack Pair<A, B> { var first: A
            var second: B }
        func<U> Box<U>&.peek(): U { return self.value }
        func<U> Box<U>!.swap(value: U): U {
            fin old = self.value
            self.value = value
            return old
        }
        func<X, Y> Pair<X, Y>&.right(): Y { return self.second }
        func main() {
            var box = Box<Int>(8)
            println(box.peek())
            println(box.swap(9) + 1)
            println(box.peek() + 1)
            println("a" + Pair<Int, String>(1, "b").right())
        }
    """))

    @Test fun unnamedReceiversArePositionsOfSelf() = assertEquals("30\n21", run("""
        import std.io
        pack A { var a: Int = 1 }
        pack B { var b: Int = 2 }
        func (A&, B&).sum(): Int { return self.0.a + self.1.b }
        func (A!, B&).absorb() { self.0.a = self.0.a + self.1.b }
        func main() {
            var a = A(10)
            var b = B(20)
            using b {
                println(a.sum())
                var small = A(1)
                small.absorb()
                println(small.a)
            }
        }
    """))

    @Test fun anExtensionIsAMemberWhereverItsModuleIsImported() = assertEquals("4.0", run("""
        import std.io
        import lib.kinds
        import lib.style
        func main() {
            fin m = Measure(2.0).twice()
            println(m.amount)
        }
    """, listOf(
        LibrarySource("lib/kinds.az", """
            module lib.kinds
            pack Measure { var amount: Double = 0.0 }
            impl Measure { func &.plus(v: Double): Measure { return Measure(self.amount + v) } }
        """.trimIndent()),
        LibrarySource("lib/style.az", """
            module lib.style
            import lib.kinds
            func Measure&.twice(): Measure { return self.plus(self.amount) }
        """.trimIndent()),
    )))

    @Test fun aLibraryModuleCallsTheExtensionsItImports() = assertEquals("6.0", run("""
        import std.io
        import lib.panel
        func main() { println(sized()) }
    """, listOf(
        LibrarySource("lib/kinds.az", """
            module lib.kinds
            pack Measure { var amount: Double = 0.0 }
            impl Measure { func &.plus(v: Double): Measure { return Measure(self.amount + v) } }
        """.trimIndent()),
        LibrarySource("lib/style.az", """
            module lib.style
            import lib.kinds
            func Measure&.tripled(): Measure { return self.plus(self.amount * 2.0) }
        """.trimIndent()),
        LibrarySource("lib/panel.az", """
            module lib.panel
            import lib.kinds
            import lib.style
            func sized(): Double { return Measure(2.0).tripled().amount }
        """.trimIndent()),
    )))

    @Test fun aFreeShorthandOutsideAnImplPointsAtTheTypedSpelling() {
        val compiled = Compiler().compile("func &.x() {}")
        val errors = (compiled as? CompilationResult.Failure)?.errors.toString()
        assertTrue("Type&.x()" in errors, errors)
    }
}
