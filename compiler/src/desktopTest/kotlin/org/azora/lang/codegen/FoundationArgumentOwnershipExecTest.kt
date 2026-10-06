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

    private fun clang(): String? {
        val paths = listOf("/usr/bin/clang") + System.getenv("PATH").orEmpty().split(File.pathSeparator).map { "$it/clang" }
        val result = paths.firstOrNull { File(it).canExecute() }
        check(result != null || System.getenv("AZORA_REQUIRE_NATIVE_TESTS") != "1") { "Native ownership qualification requires clang" }
        return result
    }

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
