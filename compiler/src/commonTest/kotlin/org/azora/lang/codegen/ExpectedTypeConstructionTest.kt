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
 * A value built from the type its position states, wherever the position
 * states one.
 *
 * - `return .()` and `return .() * n` never compiled: only a typed binding's
 *   `.()` was read, by the parser, and an `Array<Int>` gave the resolver no
 *   owner to read it by. `Array<Int>()` dropped its type argument too.
 * - `fin s: V = .Text("x")` bound `V.Text` and left `("x")` behind as a
 *   statement of its own.
 * - A conditional choosing between literals took the literals' default width,
 *   so `quarter(if b then 3 else 5)` with `quarter(d: Double)` was rejected,
 *   and a `when` of `1.0` and `2.0` could not fill a `Double` field.
 * - A free function named with a keyword could be declared and never called:
 *   `take(3)` is the ownership operator and returned `3`.
 */
class ExpectedTypeConstructionTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun aReturnBuildsTheDeclaredType() = assertEquals("0\n0\n2\n1\n0\n3", run(
        """
        import std.io
        func a(n: Int): Array<Int> {
            if n == 0 then return .()
            return [n]
        }
        func b(n: Int): Array<Int> {
            if n == 0 {
                return .()
            }
            return .() * n
        }
        func<T> c(xs: Array<T>): Array<T> {
            if xs.size == 0 then return .()
            return xs
        }
        func main() {
            println(a(0).size)
            println(b(0).size)
            println(b(2).size)
            println(c([1]).size)
            fin empty = Array<Int>()
            println(empty.size)
            fin filled: Array<Int> = .() * 3
            println(filled.size)
        }
        """,
    ))

    @Test fun aVariantShorthandKeepsItsPayload() = assertEquals("z\nColor.Red\nColor.Green\ny", run(
        """
        import std.io
        enum Color { Red, Green }
        variant enum V {
            Null
            Text(value: String)
        }
        fin g: Color = .Green
        func show(v: V): String {
            when v {
                V.Text(s) -> { return s }
                else -> { return "?" }
            }
        }
        func main() {
            fin t: V = .Text("z")
            println(show(t))
            fin c: Color = .Red
            println(c)
            println(g)
            var n: V = .Null
            n = .Text("y")
            println(show(n))
        }
        """,
    ))

    @Test fun aConditionalOfLiteralsTakesTheTypeItLandsIn() = assertEquals("2.0\n0.75\n21", run(
        """
        import std.io
        pack G { var p: Double }
        func quarter(d: Double): Double { return d / 4.0 }
        func main() {
            fin b = 2
            fin g = G(when {
                b == 1 -> 1.0
                b == 2 -> 2.0
                else -> 0.0
            })
            println(g.p)
            println(quarter(if b == 2 then 3 else 5))
            fin l: Long = if b == 2 then 7 else 9
            println(l * 3)
        }
        """,
    ))

    @Test fun aFreeFunctionCannotBeNamedWithAKeyword() {
        for (keyword in listOf("take", "alloc", "async")) {
            val result = Compiler().compile("func $keyword(x: Int): Int { return x }\nfunc main() {}")
            val errors = assertIs<CompilationResult.Failure>(result).errors
            assertTrue(
                errors.any { "a function named '$keyword' could not be called by that name, which is a keyword" in it },
                "$keyword: $errors",
            )
        }
    }

    @Test fun aMemberMayStillBeNamedWithOne() = assertEquals("4", run(
        """
        import std.io
        pack Cell { var v: Int }
        impl Cell {
            func !.take(): Int {
                fin taken = self.v
                self.v = 0
                return taken
            }
        }
        func main() {
            var c = Cell(4)
            println(c.take())
        }
        """,
    ))
}
