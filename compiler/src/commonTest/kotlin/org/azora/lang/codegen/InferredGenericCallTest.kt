/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.ir.IrProgram
import org.azora.lang.ir.IrStmt
import org.azora.lang.ir.IrTopLevel
import org.azora.lang.ir.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A generic call that writes no type arguments is typed by the ones the resolver
 * inferred: `wrap(4)` builds the `Box<Int>` that `wrap<Int>(4)` does, so a binding
 * that states no type holds the instantiated result rather than the erased one.
 */
class InferredGenericCallTest {
    companion object {
        /** The result of an inferred call, read through an unannotated binding. */
        val wrapped = """
            import std.io
            pack Box<T> { var item: T }
            func<T> wrap(value: T): Box<T> { return Box<T>(value) }
            func main() {
                fin b = wrap(4)
                println(b.item)
                fin s = wrap("hey")
                println(s.item)
                fin stated = wrap<Int>(5)
                println(stated.item + b.item)
            }
        """.trimIndent()

        /**
         * Library and user functions that build a `List<T>`.
         *
         * `listOf` packs its arguments at their own width and reads them back as
         * erased slots, so only a Long list reads past its first element on the
         * native targets (recorded in the progress log, not measured here). The
         * lists `twice` builds hold erased values and read back at any width.
         */
        val listed = """
            import std.io
            import std.container.list
            func<T> twice(v: T): List<T> { return listOf(v, v) }
            func main() {
                fin xs = listOf(1, 2, 3)
                println(xs.get(0))
                fin a: Long = 5000000000
                fin b: Long = 6000000000
                fin longs = listOf(a, b)
                println(longs.get(0) + longs.get(1))
                fin words = twice("echo")
                println(words.get(1))
                fin ns = twice(4)
                println(ns.get(0) + ns.get(1))
                fin ys = twice(2.5)
                println(ys.get(1) + 1.0)
            }
        """.trimIndent()

        /** Results wider than an Int, and a string, returned as `T`. */
        val wide = """
            import std.io
            func<T> identity(value: T): T { return value }
            func main() {
                fin big: Long = 5000000000
                fin copy = identity(big)
                println(copy)
                println(identity(2.5))
                println(identity("text"))
            }
        """.trimIndent()

        /** A generic function calling another: its own `U` stays erased inside. */
        val nested = """
            import std.io
            pack Box<T> { var item: T }
            func<T> wrap(value: T): Box<T> { return Box<T>(value) }
            func<U> rewrap(u: U): Box<U> {
                fin inner = wrap(u)
                fin stated = wrap<U>(u)
                return inner
            }
            func main() {
                fin b = rewrap(9)
                println(b.item)
                fin s = rewrap("deep")
                println(s.item)
            }
        """.trimIndent()

        /** A call that wrote a hole: written arguments keep their own rule. */
        val holed = """
            import std.io
            pack Pair<A, B> {
                var first: A
                var second: B
            }
            func<A, B> pairOf(a: A, b: B): Pair<A, B> { return Pair<A, B>(a, b) }
            func main() {
                fin p = pairOf<Int, _>(1, "two")
                fin q: Pair<Int, String> = pairOf<Int, _>(3, "four")
                println(q.second)
            }
        """.trimIndent()

        val programs = listOf(
            wrapped to "4\nhey\n9",
            listed to "1\n11000000000\necho\n8\n3.5",
            wide to "5000000000\n2.5\ntext",
            nested to "9\ndeep",
        )

        fun compile(source: String, optimized: Boolean): IrProgram {
            val result = Compiler().compile(source, release = optimized)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            return result.ir
        }
    }

    /**
     * [type] written out with its type arguments. `IrType.Named` identity ignores
     * them (`Box<Int>` equals `Box<Any>`), and they are what is measured here.
     */
    private fun shape(type: IrType): String = when (type) {
        is IrType.Named ->
            if (type.args.isEmpty()) type.name else "${type.name}<${type.args.joinToString(", ", transform = ::shape)}>"
        else -> type.toString()
    }

    /** The type of each `fin` declared directly in [function]'s body, by name. */
    private fun bindings(source: String, function: String = "main"): Map<String, String> =
        compile(source, optimized = false).items
            .filterIsInstance<IrTopLevel.Func>()
            .single { it.function.name == function }
            .function.body
            .filterIsInstance<IrStmt.FinDecl>()
            .associate { it.name to shape(it.type) }

    @Test fun anUnannotatedBindingHoldsTheInstantiatedResult() {
        val types = bindings(wrapped)
        assertEquals("Box<Int>", types["b"])
        assertEquals("Box<String>", types["s"])
        assertEquals(types["stated"], types["b"], "inferred and written type arguments agree")
    }

    @Test fun aListBuiltByAnInferredCallKeepsItsElementType() {
        val types = bindings(listed)
        assertEquals("List<Int>", types["xs"])
        assertEquals("List<Long>", types["longs"])
        assertEquals("List<String>", types["words"])
        assertEquals("List<Int>", types["ns"])
        assertEquals("List<Float>", types["ys"])
    }

    @Test fun aWideResultIsTypedAtTheCall() {
        assertEquals("Long", bindings(wide)["copy"])
    }

    // Inside `rewrap` the argument is a `U`, which erases; an inferred `wrap(u)`
    // is typed as the written `wrap<U>(u)` is. The call of `rewrap` itself is
    // concrete and is instantiated.
    @Test fun aCallInAGenericBodyIsTypedAsItsWrittenFormIs() {
        val inner = bindings(nested, function = "rewrap")
        assertEquals("Box<Any>", inner["stated"])
        assertEquals(inner["stated"], inner["inner"])
        assertEquals("Box<Int>", bindings(nested)["b"])
        assertEquals("Box<String>", bindings(nested)["s"])
    }

    // A call that wrote a hole is not completed here: the rule for written type
    // arguments is unchanged, and the result follows only when all of them were
    // written. Completing holes by inference belongs to plan item 023.
    @Test fun aHoledCallKeepsTheRuleForWrittenArguments() {
        val types = bindings(holed)
        assertEquals("Pair<Any, Any>", types["p"])
        assertEquals("Pair<Int, String>", types["q"])
    }

    @Test fun inferredCallsRunOnTheInterpreter() {
        for ((source, expected) in programs + (holed to "four")) for (optimized in listOf(false, true)) {
            assertEquals(expected, IrInterpreter().interpret(compile(source, optimized)).trim(), "optimized=$optimized")
        }
    }
}
