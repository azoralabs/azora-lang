/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Storing an owned array, set or map local in a constructed value is a
 * transfer, and the ownership DIP asks for it to be written: `Holder(take xs)`
 * or `Holder(xs.clone())`, in a `return` too (its own example is
 * `return App(database: take database)`).
 *
 * The checker asked this of packs but not of arrays, so `Holder(xs)` compiled
 * and the array had two owners, the field and the local, each freeing it: a
 * double free in native code when both left scope.
 */
class AggregateStoreOwnershipTest {
    private val holder = """
        import std.io
        import std.container.set::Set
        pack Holder { var xs: Array<Int> }
        pack Tags { var set: Set<Int> }
    """.trimIndent()

    private fun compile(body: String) = Compiler().compile("$holder\n${body.trimIndent()}")

    private fun errors(body: String): List<String> {
        val result = compile(body)
        assertIs<CompilationResult.Failure>(result, "expected a rejection:\n$body")
        return result.errors
    }

    private fun run(body: String): String {
        val result = compile(body)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun anOwnedLocalStoredWithoutTakeIsRejected() {
        val found = errors("""
            func main() {
                fin xs: Array<Int> = [1, 2]
                fin h = Holder(xs)
                println(h.xs[1])
            }
        """)
        assertTrue(found.any { "cannot store 'xs' in a constructed value" in it && "take xs" in it && "xs.clone()" in it }, found.toString())
    }

    @Test fun aReturnedConstructionNeedsTakeToo() {
        val found = errors("""
            func make(): Holder {
                var xs: Array<Int> = .() * 3
                return Holder(xs)
            }
            func main() { println(make().xs.size) }
        """)
        assertTrue(found.any { "cannot store 'xs'" in it }, found.toString())
    }

    @Test fun aNamedArgumentIsCheckedLikeAPositionalOne() {
        val found = errors("""
            func main() {
                fin kept: Array<Int> = [1, 2]
                fin h = Holder(xs: kept)
                println(h.xs.size)
            }
        """)
        assertTrue(found.any { "cannot store 'kept'" in it }, found.toString())
    }

    /** A standard `Set` is a pack, so the pack rule that already existed reports it. */
    @Test fun aStandardCollectionIsCheckedAsThePackItIs() {
        val found = errors("""
            func main() {
                fin seen: Set<Int> = [1, 2]
                fin t = Tags(set: seen)
                println(t.set.size)
            }
        """)
        assertTrue(found.any { "cannot pass 'seen' by ownership" in it && "take seen" in it }, found.toString())
    }

    @Test fun takeAndCloneAreAccepted() {
        assertEquals("2 2 1", run("""
            func main() {
                fin xs: Array<Int> = [1, 2]
                fin copied = Holder(xs.clone())
                fin moved = Holder(take xs)
                fin fresh = Holder([7])
                println("${'$'}{copied.xs[1]} ${'$'}{moved.xs[1]} ${'$'}{fresh.xs.size}")
            }
        """))
    }

    /** A name that only reads a place it does not own transfers nothing; it is not this rule's case. */
    @Test fun aBindingThatDoesNotOwnItsArrayIsNotReported() {
        val result = compile("""
            func first(h: Holder&): Holder {
                fin view = h.xs
                return Holder(view.clone())
            }
            func main() { println(first(Holder([4, 5])).xs[0]) }
        """)
        val failures = (result as? CompilationResult.Failure)?.errors.orEmpty()
        assertTrue(failures.none { "cannot store" in it }, failures.toString())
    }
}
