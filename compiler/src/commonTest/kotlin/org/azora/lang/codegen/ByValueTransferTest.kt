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
 * Passing a non-`Copy` value by value transfers ownership
 * (OWNERSHIP_BORROWING_DIP §8, §10, §11): the receiver owns it and destroys
 * it, so the caller writes `take` or `.clone()`, or the receiver borrows with
 * `T&`. Decision D-6 (2026-11-06) made the compiler follow the DIP: arrays had
 * been exempt, fields and elements were never checked, method arguments and
 * the storing collection members were not checked at all, and a pack with a
 * destructor or an exclusive borrow counted as `Copy`.
 */
class ByValueTransferTest {
    private val prelude = """
        import std.io
        import std.traits
        pack Res {
            var id: Int
            var items: Array<Int>
        }
        pack Box { var res: Res }
        impl Res {
            func &.keep(other: Res): Int { return other.id }
        }
        func consume(r: Res): Int = r.id
        func total(xs: Array<Int>): Int = xs.size
        func peek(xs: Array<Int>&): Int = xs.size
    """.trimIndent()

    private fun compile(body: String) = Compiler().compile("$prelude\n${body.trimIndent()}")

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

    @Test fun aNamedArrayPassedByValueNeedsTakeOrClone() {
        val found = errors("""
            func main() {
                fin xs: Array<Int> = [1, 2]
                println(total(xs))
            }
        """)
        assertTrue(found.any {
            "cannot pass 'xs' by ownership" in it && "take xs" in it && "xs.clone()" in it && "Array<Int>&" in it
        }, found.toString())
    }

    @Test fun aFieldOrAnElementIsAPlaceToo() {
        val found = errors("""
            func main() {
                fin b = Box(Res(1, [1]))
                println(consume(b.res))
                fin all: Array<Array<Int>> = [[1], [2]]
                println(total(all[0]))
            }
        """)
        assertTrue(found.any { "cannot pass 'b.res' by ownership" in it }, found.toString())
        assertTrue(found.any { "cannot pass 'all[…]' by ownership" in it }, found.toString())
    }

    @Test fun methodArgumentsAndCollectionStoresAreChecked() {
        val found = errors("""
            func main() {
                fin a = Res(1, [1])
                fin b = Res(2, [2])
                println(a.keep(b))
                var list: Array<Res> = []
                list.add(a)
            }
        """)
        assertTrue(found.any { "cannot pass 'b' by ownership" in it }, found.toString())
        assertTrue(found.any { "cannot pass 'a' by ownership" in it }, found.toString())
    }

    @Test fun takeCloneBorrowAndTemporariesAreAccepted() {
        assertEquals("2\n2\n1\n3\n1", run("""
            func main() {
                fin xs: Array<Int> = [1, 2]
                println(peek(xs))
                println(total(xs.clone()))
                fin r = Res(1, [])
                println(consume(take r))
                println(total([1, 2, 3]))
                var list: Array<Res> = []
                list.add(Res(4, []))
                println(list.size)
            }
        """))
    }

    @Test fun aPackWithADestructorIsNotCopy() {
        val found = errors("""
            pack Handle { var id: Int }
            impl Handle {
                dtor .() { }
            }
            func close(h: Handle): Int = h.id
            func main() {
                fin h = Handle(3)
                println(close(h))
            }
        """)
        assertTrue(found.any { "cannot pass 'h' by ownership" in it }, found.toString())
    }

    @Test fun aPackHoldingAnExclusiveBorrowIsNotCopy() {
        val found = errors("""
            pack Counter { var n: Int }
            pack Cursor { var counter: Counter! }
            func advance(c: Cursor): Int = c.counter.n
            func main() {
                var counter = Counter(0)
                fin cursor = Cursor(counter)
                println(advance(cursor))
            }
        """)
        assertTrue(found.any { "cannot pass 'cursor' by ownership" in it }, found.toString())
    }

    /** §10: moving into a field, an element or an owning local is written too. */
    @Test fun storingIntoAFieldAnElementOrAnOwnerNeedsTake() {
        val found = errors("""
            pack Holder { var items: Array<Int> }
            func setItems(h: Holder!, xs: Array<Int>) {
                h.items = xs
            }
            func main() {
                fin r = Res(1, [])
                var list: Array<Res> = [Res(0, [])]
                list[0] = r
                var ys: Array<Int> = []
                fin zs: Array<Int> = [5]
                ys = zs
                var shown: Array<Int>& = zs
                shown = ys
            }
        """)
        assertTrue(found.any { "cannot store 'xs' in 'h.items'" in it }, found.toString())
        assertTrue(found.any { "cannot store 'r' in 'list[…]'" in it }, found.toString())
        assertTrue(found.any { "cannot store 'zs' in 'ys'" in it }, found.toString())
        // Re-pointing a borrow binding moves nothing.
        assertTrue(found.none { "in 'shown'" in it }, found.toString())
    }

    @Test fun printingBorrowsWhatItPrints() {
        assertEquals("[1, 2]\n2", run("""
            func main() {
                fin xs: Array<Int> = [1, 2]
                println(xs)
                println(xs.size)
            }
        """))
    }
}
