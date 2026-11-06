/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * A named `Copy` value handed to something that keeps it must be copied, or the
 * keeper and the binding's scope cleanup both own one allocation. Each program
 * runs under AddressSanitizer: an alias shows up as a use-after-free or a
 * double free when the binding's scope ends and the keeper replaces the value.
 */
class FoundationArgumentOwnershipExecTest {
    @Test fun genericKeeperReceivesAnIndependentCopyOfANamedLocal() = sanitized("""
        import std.io
        import std.traits
        pack Size { var w: Double = 0.0 }
        pack Store<T> { var values: Array<T> }
        func<T> put(store: Store<T>!, index: Int, value: T) {
            if index < store.values.size {
                store.values[index] = value
                return
            }
            store.values.add(value)
        }
        func fill(store: Store<Size>!, index: Int, width: Double) {
            var size = Size()
            size.w = width
            put<Size>(store, index, size)
            size.w = 99.0
        }
        func main() {
            var store = Store<Size>(Array<Size>())
            fill(store, 0, 1.0)
            fill(store, 1, 2.0)
            fill(store, 0, 3.0)
            println("${'$'}{store.values[0].w} ${'$'}{store.values[1].w}")
        }
    """, "3.0 2.0")

    @Test fun genericForwardingKeepsTheCopyAtTheConcreteCallSite() = sanitized("""
        import std.io
        import std.traits
        pack Size { var w: Double = 0.0 }
        pack Store<T> { var values: Array<T> }
        func<T> put(store: Store<T>!, value: T) { store.values.add(value) }
        func<T> forward(store: Store<T>!, value: T) { put<T>(store, value) }
        func main() {
            var store = Store<Size>(Array<Size>())
            var size = Size()
            size.w = 4.0
            forward<Size>(store, size)
            forward<Size>(store, size)
            size.w = 5.0
            println("${'$'}{store.values[0].w} ${'$'}{store.values[1].w} ${'$'}{size.w}")
        }
    """, "4.0 4.0 5.0")

    @Test fun concreteKeeperCopiesItsBorrowedParameter() = sanitized("""
        import std.io
        import std.traits
        pack Size { var w: Double = 0.0 }
        pack Holder { var items: Array<Size> }
        func keep(holder: Holder!, value: Size) { holder.items.add(value) }
        func fill(holder: Holder!, width: Double) {
            var size = Size()
            size.w = width
            keep(holder, size)
        }
        func main() {
            var holder = Holder(Array<Size>())
            fill(holder, 6.0)
            fill(holder, 7.0)
            println("${'$'}{holder.items[0].w} ${'$'}{holder.items[1].w}")
        }
    """, "6.0 7.0")

    @Test fun storingANamedLocalIntoAContainerOrFieldCopiesIt() = sanitized("""
        import std.io
        import std.traits
        pack Size { var w: Double = 0.0 }
        pack Holder {
            var items: Array<Size>
            var one: Size
        }
        func fill(holder: Holder!, width: Double) {
            var size = Size()
            size.w = width
            holder.items.add(size)
            holder.one = size
            var other = Size()
            other.w = width + 0.5
            holder.items[0] = other
            size.w = 0.0
            other.w = 0.0
        }
        func main() {
            var holder = Holder(Array<Size>(), Size())
            fill(holder, 1.0)
            fill(holder, 2.0)
            println("${'$'}{holder.items[0].w} ${'$'}{holder.items[1].w} ${'$'}{holder.one.w}")
        }
    """, "2.5 2.0 2.0")

    @Test fun inlineCloneBoundCopiesAnErasedValueAsItsConcreteType() = sanitized("""
        import std.io
        import std.traits
        pack Size { var w: Double = 0.0 }
        pack Store<T> { var values: Array<T> }
        func<T: Clone> read(store: Store<T>&, index: Int, fallback: T): T {
            if index >= store.values.size { return take fallback }
            return store.values[index].clone()
        }
        func<T> put(store: Store<T>!, value: T) { store.values.add(value) }
        func bump(store: Store<Size>!) {
            var current = read(store, 0, Size())
            current.w = current.w + 1.0
            put<Size>(store, current)
        }
        func main() {
            var store = Store<Size>(Array<Size>())
            put<Size>(store, Size())
            bump(store)
            bump(store)
            println("${'$'}{store.values[0].w} ${'$'}{store.values[1].w} ${'$'}{store.values[2].w}")
        }
    """, "0.0 1.0 1.0")

    @Test fun returningAPlaceItDoesNotOwnGivesTheCallerACopy() = sanitized("""
        import std.io
        import std.traits
        pack Point { var x: Int = 0 }
        impl Point { fin origin: Point = .(0) }
        pack Holder { var point: Point }
        func pick(found: Bool, holder: Holder&): Point {
            if found { return holder.point }
            return Point::origin
        }
        func echo(point: Point): Point { return point }
        func main() {
            var holder = Holder(Point(5))
            var a = pick(true, holder)
            a.x = 9
            var b = pick(false, holder)
            b.x = 7
            var c = echo(holder.point)
            c.x = 3
            println("${'$'}{holder.point.x} ${'$'}{Point::origin.x} ${'$'}{a.x} ${'$'}{b.x} ${'$'}{c.x}")
        }
    """, "5 0 9 7 3")

    @Test fun exclusiveBorrowsWriteTheCallersOwnStorage() = sanitized("""
        import std.io
        pack Holder {
            var items: Array<Int>
            var count: Int = 0
        }
        func bump(n: Int!) { n += 1 }
        func push(xs: Array<Int>!, v: Int) { xs.add(v) }
        func rename(s: String!) { s = "after" }
        func main() {
            var count = 1
            bump(count)
            bump(count)
            var items = Array<Int>()
            var i = 0
            while i < 40 {
                push(items, i)
                i = i + 1
            }
            var holder = Holder(Array<Int>(), 0)
            push(holder.items, 7)
            push(holder.items, 8)
            bump(holder.count)
            var name = "before"
            rename(name)
            println("${'$'}{count} ${'$'}{items.size} ${'$'}{items[39]} ${'$'}{holder.items[1]} ${'$'}{holder.count} ${'$'}{name}")
        }
    """, "3 40 39 8 1 after")

    /**
     * A construction that takes a local owns it, and the local's cleanup no
     * longer frees it. Before, `return Layout(shape, strides)` left `strides`
     * registered for its scope's cleanup, so the caller received a pack whose
     * field had already been freed (std.gpu's `rowMajor` read zero strides).
     * The checker now asks for `take` (see `AggregateStoreOwnershipTest`).
     */
    @Test fun aConstructionThatTakesALocalOwnsIt() = sanitized("""
        import std.io
        pack Layout {
            var shape: Array<Int>
            var strides: Array<Int>
        }
        pack Wrapper {
            var layout: Layout
            var tag: Array<Int>
        }
        func rowMajor(shape: Array<Int>): Layout {
            var strides: Array<Int> = .() * shape.size
            var remaining = 24
            for i in 0..<shape.size {
                remaining = remaining / shape[i]
                strides[i] = remaining
            }
            return Layout(take shape, take strides)
        }
        func wrapped(): Wrapper {
            fin strides: Array<Int> = [5, 6]
            fin tag: Array<Int> = [7]
            return Wrapper(Layout([1, 2], take strides), take tag)
        }
        func main() {
            fin layout = rowMajor([2, 3, 4])
            fin w = wrapped()
            fin kept: Array<Int> = [8, 9]
            fin holder = Wrapper(Layout([3], take kept), [10])
            println("${'$'}{layout.strides[0]} ${'$'}{layout.strides[2]} ${'$'}{w.layout.strides[1]} ${'$'}{w.tag[0]} ${'$'}{holder.layout.strides[1]}")
        }
    """, "12 1 6 7 9")

    /** Values cloned into a construction stay independent of their source. */
    @Test fun aLocalStoredTwiceIsClonedNotShared() = sanitized("""
        import std.io
        pack Pair {
            var left: Array<Int>
            var right: Array<Int>
        }
        func both(): Int {
            fin xs: Array<Int> = [3, 4]
            fin pair = Pair(xs.clone(), xs.clone())
            return pair.left[0] + pair.right[1] + xs[0]
        }
        func main() { println(both()) }
    """, "10")

    /**
     * A derived `clone` copies every allocation the pack owns.
     *
     * It copied nested packs only; an `Array` field was shared, so the clone
     * and the original both freed one buffer when they went (Studio crashed on
     * exit once a dropped `World` destroyed its icon atlas and Studio's clone).
     */
    @Test fun aClonedPackOwnsCopiesOfItsArrays() = sanitized("""
        import std.io
        import std.traits
        pack Atlas derives Clone {
            var cell: Int = 64
            var names: Array<String> = Array<String>()
            var ids: Array<Int> = Array<Int>()
            var nested: Array<Array<Int>> = Array<Array<Int>>()
        }
        func main() {
            var a = Atlas()
            a.names.add("x")
            a.ids.add(1)
            a.nested.add([1, 2])
            var b = a.clone()
            b.names.add("y")
            b.ids[0] = 5
            b.nested[0][0] = 9
            println("${'$'}{a.names.size} ${'$'}{b.names.size} ${'$'}{a.ids[0]} ${'$'}{b.ids[0]} ${'$'}{a.nested[0][0]} ${'$'}{b.nested[0][0]}")
        }
    """, "1 2 1 5 1 9")

    /**
     * A by-value parameter owns what it is given (decision D-6, the ownership
     * DIP §10): the callee destroys it unless it moves it on, a moved-on value
     * is destroyed by its new owner, a temporary is destroyed by its callee and
     * a returned parameter belongs to the caller. Before, the callee treated
     * every by-value parameter as a borrow: a value given with `take` was
     * never destroyed.
     */
    @Test fun aByValueParameterOwnsWhatItIsGiven() = sanitized("""
        import std.io
        import std.traits
        pack Audit { var destroyed: Int = 0 }
        pack Res {
            var id: Int
            var audit: Audit!
        }
        impl Res {
            dtor .() { self.audit.destroyed += 1 }
        }
        pack Holder { var items: Array<Res> }
        func consume(r: Res): Int = r.id
        func keep(h: Holder!, r: Res) { h.items.add(take r) }
        func total(xs: Array<Int>): Int {
            var s = 0
            for x in xs { s += x }
            return s
        }
        func pass(r: Res): Res = take r
        func main() {
            var audit = Audit()
            fin a = Res(1, audit)
            fin first = consume(take a)
            fin afterConsume = audit.destroyed
            var h = Holder(Array<Res>())
            fin b = Res(2, audit)
            keep(h, take b)
            fin afterKeep = audit.destroyed
            fin xs: Array<Int> = [1, 2, 3]
            fin sum = total(take xs)
            fin third = consume(Res(3, audit))
            fin afterTemporary = audit.destroyed
            fin c = pass(Res(4, audit))
            println("${'$'}{first} ${'$'}{afterConsume} ${'$'}{afterKeep} ${'$'}{sum} ${'$'}{third} ${'$'}{afterTemporary} ${'$'}{audit.destroyed} ${'$'}{c.id}")
        }
    """, "1 1 1 6 3 2 2 4")

    /** A parameter stored with `take` belongs to its new owner after the call returns. */
    @Test fun aParameterStoredWithTakeOutlivesTheCall() = sanitized("""
        import std.io
        pack Holder { var items: Array<Int> }
        func setItems(h: Holder!, xs: Array<Int>) {
            h.items = take xs
        }
        func main() {
            var h = Holder([])
            fin xs: Array<Int> = [1, 2]
            setItems(h, take xs)
            setItems(h, [7, 8, 9])
            println(h.items.size)
        }
    """, "3")

    /**
     * A constructor's generated factory moves each owned argument into the
     * constructor. It passed them as plain names, so once a by-value parameter
     * owned its value the factory and the constructor both freed it.
     */
    @Test fun aConstructorArgumentIsFreedOnce() = sanitized("""
        import std.io
        pack Counter {
            var n: Int = 0
            var seen: Array<Int> = []
        }
        impl Counter {
            func !.bump(amount: Int) {
                self.n += amount
                self.seen.add(amount)
            }
            ctor .(...steps: Int) {
                for i in 0..<steps.size {
                    self.bump(steps[i])
                }
            }
        }
        func main() {
            fin c = Counter(1, 2, 3)
            fin arr: Array<Int> = [4, 5]
            fin d = Counter(...arr)
            println("${'$'}{c.n} ${'$'}{c.seen.size} ${'$'}{d.n}")
        }
    """, "6 3 9")

    private fun sanitized(source: String, expected: String) {
        val program = source.trimIndent()
        for (release in listOf(false, true)) {
            val compiled = Compiler().compile(program, release = release)
            val result = assertIs<CompilationResult.Success>(compiled, (compiled as? CompilationResult.Failure)?.errors.toString())
            assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter")
            val tool = clang() ?: continue
            val directory = Files.createTempDirectory("azora-argument-ownership-").toFile()
            try {
                val ir = directory.resolve("main.ll").apply { writeText(result.llvm) }
                val binary = directory.resolve("probe")
                run(directory, tool, ir.path, "-Wno-override-module", "-fsanitize=address,undefined", "-o", binary.path)
                assertEquals(expected, run(directory, binary.path).trim(), "LLVM release=$release")
            } finally { directory.deleteRecursively() }
        }
    }

    private fun clang(): String? = SanitizerClang.find("Native ownership qualification")

    private fun run(directory: File, vararg arguments: String): String {
        val output = directory.resolve("stdout")
        val errors = directory.resolve("stderr")
        val process = ProcessBuilder(*arguments).redirectOutput(output).redirectError(errors).start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            fail("native ownership probe timed out")
        }
        assertEquals(0, process.exitValue(), output.readText() + errors.readText())
        if (arguments.size == 1) assertEquals("", errors.readText(), "sanitizer diagnostics")
        return output.readText()
    }
}
