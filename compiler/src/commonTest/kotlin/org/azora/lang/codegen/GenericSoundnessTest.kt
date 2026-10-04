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
 * What a generic type's arguments say is checked, and what a call's arguments
 * say is inferred.
 *
 * A `Named` type's identity leaves its type arguments out, so storage can stay
 * erased - and every check that compared types with `==` accepted a `Box<Int>`
 * as a `Box<String>`. Natively the program then read an `Int` as a string and
 * crashed. Type arguments are now invariant wherever a value is stored or
 * passed, through specs too: an `ArrayList<Int>` is a `List<Int>`, not a
 * `List<String>`.
 *
 * Inference read a type parameter only off a parameter written as exactly
 * `T`, so `first(b)` with `b: Box<Int>` was typed `T`, `Box(7)` was a `Box`
 * of nothing in particular, and a generic construction checked no field.
 */
class GenericSoundnessTest {
    private val box = """
        import std.io
        pack Box<T> {
            var value: T
        }
        pack Pair<A, B> {
            var a: A
            var b: Array<B>
        }
        func<T> first(b: Box<T>&): T { return b.value }
    """.trimIndent()

    private fun errors(body: String): List<String> =
        assertIs<CompilationResult.Failure>(Compiler().compile(box + "\n" + body.trimIndent())).errors

    private fun run(body: String): String {
        val result = Compiler().compile(box + "\n" + body.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun assertRefused(body: String, vararg expected: String) {
        val found = errors(body)
        for (message in expected) assertTrue(found.any { message in it }, "missing '$message' in $found")
    }

    @Test fun aGenericTypeIsNotAnotherInstanceOfItself() = assertRefused(
        """
        func show(b: Box<String>&): String { return b.value }
        func main() {
            fin a = Box<Int>(42)
            fin s: Box<String> = a
            println(show(a))
        }
        """,
        "type mismatch in 's': declared Box<String> but initializer is Box<Int>",
        "arg 1 of 'show': expected Box<String>, got Box<Int>",
    )

    @Test fun aSpecIsSeenWithTheArgumentsItsImplementationGivesIt() = assertRefused(
        """
        import std.container.list
        func main() {
            fin ys: List<String> = ArrayList<Int>()
        }
        """,
        "type mismatch in 'ys': declared List<String> but initializer is ArrayList<Int>",
    )

    @Test fun aRefiningSpecIsItsParent() = assertEquals("6\n3", run(
        """
        import std.container.list
        func total(xs: List<Int>&): Int {
            var t = 0
            for i in 0..<xs.size { t += xs[i] }
            return t
        }
        func main() {
            var m: MutableList<Int> = [1, 2]
            m.add(3)
            println(total(m))
            fin l: List<Int> = take m
            println(l.size)
        }
        """,
    ))

    @Test fun aTypeParameterIsReadThroughTheTypeHoldingIt() = assertEquals("9\n3.5", run(
        """
        func main() {
            fin b = Box<Int>(2)
            println(first(b) + first(Box(7)))
            println(first(Box(2.5)) + 1.0)
        }
        """,
    ))

    @Test fun aConstructionInfersItsArgumentsAndChecksItsFields() {
        assertEquals("1\ns", run(
            """
            func main() {
                fin p = Pair(1, ["s"])
                fin q: Pair<Int, String> = p
                println(q.a)
                println(q.b[0])
            }
            """,
        ))
        assertRefused(
            """
            func main() {
                fin x = Box<Int>("x")
                fin r: Pair<Int, Int> = Pair(1, ["s"])
            }
            """,
            "field 'value' of 'Box': expected Int, got String",
            "type mismatch in 'r': declared Pair<Int, Int> but initializer is Pair<Int, String>",
        )
    }

    @Test fun aVariadicParameterInfersThroughEachArgument() = assertEquals("2\na", run(
        """
        import std.container.map
        func main() {
            fin hm = hashMapOf(1 to "a", 2 to "b")
            println(hm.size)
            println(hm[1])
        }
        """,
    ))

    @Test fun anErasedElementTypeLetsTheElementsSayWhatTheyAre() = assertEquals("3", run(
        """
        import std.algorithm.sort
        func main() {
            fin xs = reverse([1, 2, 3])
            println(xs[0])
        }
        """,
    ))

    @Test fun aSpecCannotBeConstructed() = assertRefused(
        """
        import std.container.list
        func main() {
            var xs = List<Int>()
        }
        """,
        "'List' is a spec, and a spec cannot be constructed; construct a type that implements it, such as 'ArrayList'",
    )
}
