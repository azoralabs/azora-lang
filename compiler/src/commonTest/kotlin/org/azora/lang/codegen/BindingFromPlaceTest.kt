/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Decision D-7 (OWNERSHIP_BORROWING_DIP): a binding initialised from a place
 * owns what it holds. A non-`Copy` value that a binding, field or element owns
 * is not bound to a second name silently - the program writes `take` to move
 * it, `.clone()` for an independent value, or a borrow (`x.&`, `x.!`).
 */
class BindingFromPlaceTest {
    private fun compile(source: String): CompilationResult = Compiler().compile(source.trimIndent())

    private fun accepts(source: String) {
        val result = compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
    }

    private fun rejects(source: String, vararg needles: String) {
        val failure = assertIs<CompilationResult.Failure>(compile(source))
        for (needle in needles) assertTrue(failure.errors.any { needle in it }, "missing '$needle' in ${failure.errors}")
    }

    private val handle = """
        import std.traits
        import std.container.list
        pack Handle { var tags: List<String> }
        pack Holder {
            var handle: Handle
            var handles: Array<Handle>
            var names: Array<String>
        }
    """.trimIndent()

    @Test fun aBindingFromALocalNeedsTakeCloneOrABorrow() = rejects("""
        $handle
        func main() {
            var a = Handle(listOf<String>())
            var b = a
        }
    """, "line 11: cannot initialise 'b' from 'a' - 'Handle' is not Copy; transfer ownership with 'take a', or create an independent value with 'a.clone()', or borrow it with 'a.&'")

    @Test fun aBindingFromAFieldIsRejected() = rejects("""
        $handle
        func main() {
            var h = Holder(Handle(listOf<String>()), Array<Handle>(), Array<String>())
            fin inner = h.handle
            fin all = h.names
        }
    """, "cannot initialise 'inner' from 'h.handle' - 'Handle' is not Copy", "cannot initialise 'all' from 'h.names' - 'Array<String>' is not Copy")

    @Test fun aBindingFromAnElementIsRejected() = rejects("""
        $handle
        func main() {
            var h = Holder(Handle(listOf<String>()), [Handle(listOf<String>())], Array<String>())
            fin first = h.handles[0]
        }
    """, "cannot initialise 'first' from 'h.handles[…]' - 'Handle' is not Copy")

    @Test fun aBorrowedRootOffersNoTake() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            $handle
            func peek(h: Holder&): Int {
                fin inner = h.handle
                return 0
            }
            func main() {}
        """))
        val error = failure.errors.single { "cannot initialise 'inner'" in it }
        assertTrue("take" !in error, error)
        assertTrue("borrow it with 'h.handle.&'" in error, error)
    }

    @Test fun takeCloneAndBorrowsAreAccepted() = accepts("""
        $handle
        func count(h: Handle&): Int = h.tags.size
        func main() {
            var a = Handle(listOf<String>())
            var b = take a
            fin c = b.clone()
            let r: Handle& = c.&
            fin n = count(r)
            var h = Holder(Handle(listOf<String>()), [Handle(listOf<String>())], ["x"])
            fin inner = take h.handle
            let first: Handle& = h.handles[0].&
            fin m = count(first)
        }
    """)

    @Test fun copyValuesAndTemporariesBindAsBefore() = accepts("""
        $handle
        pack Point {
            var x: Int
            var y: Int
        }
        func make(): Handle = Handle(listOf<String>())
        func main() {
            var h = Holder(Handle(listOf<String>()), Array<Handle>(), ["x"])
            fin name = h.names[0]
            fin p = Point(1, 2)
            fin q = p
            fin n = 3
            fin m = n
            fin fresh = make()
            fin built = Handle(listOf<String>())
        }
    """)

    @Test fun aTypeParameterIsLeftToItsInstantiation() = accepts("""
        $handle
        func<T> keep(value: T): Int {
            fin again = value
            return 1
        }
        func main() {}
    """)
}
