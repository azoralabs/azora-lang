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
 * Two shorthands that name a variant without its type.
 *
 * `return .Variant` in a `T ?! E` function: a variant of `E` fails the
 * function, a variant of `T` is returned. Every such return used to be a
 * throw, so returning one of `T`'s own variants failed the function - which
 * is how `std.serializer`'s parser, returning `.Object(fields)`, stopped every
 * program that derived a serializer.
 *
 * `.Variant(binding)` in a `when` destructures as `Type.Variant(binding)` does.
 * The binding used to be left undefined.
 */
class VariantReturnAndPatternTest {
    private val declarations = """
        error ParseError {
            Bad
            Worse
        }
        variant enum Value {
            Nothing
            Flag(Bool)
            Num(Int)
        }
        func show(value: Value&): String {
            when value {
                .Nothing -> { return "nothing" }
                .Flag(on) -> { return if on { "on" } else { "off" } }
                .Num(n) -> { return "num ${'$'}{n}" }
            }
        }
    """.trimIndent()

    private fun compile(source: String, vararg libraries: LibrarySource) =
        Compiler(libraries.toList()).compile(source.trimIndent(), release = false)

    private fun run(source: String, vararg libraries: LibrarySource): String {
        val result = compile(source, *libraries)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun rejects(source: String, fragment: String) {
        val result = assertIs<CompilationResult.Failure>(compile(source), "the program compiled")
        assertTrue(result.errors.any { fragment in it }, "expected '$fragment' in ${result.errors}")
    }

    @Test fun aSuccessVariantIsReturnedAndAnErrorVariantFails() = assertEquals(
        "num 3\nnothing\non\nfailed",
        run("""
            import std.io
            $declarations
            func make(k: Int): Value ?! ParseError {
                if k < 0 { return .Bad }
                if k == 0 { return .Nothing }
                if k == 1 { return .Flag(true) }
                return .Num(k)
            }
            func main() {
                println(show(make(3) catch Value.Flag(false)))
                println(show(make(0) catch Value.Flag(false)))
                println(show(make(1) catch Value.Flag(false)))
                println(if (make(-1) catch Value.Num(-1)) == Value.Num(-1) { "failed" } else { "succeeded" })
            }
        """),
    )

    @Test fun aWhenArmReturnsEitherKind() = assertEquals(
        "num 1\nfailed",
        run("""
            import std.io
            $declarations
            func read(c: Char): Value ?! ParseError {
                when c {
                    'n' -> return .Num(1)
                    'x' -> return .Bad
                }
                return .Nothing
            }
            func main() {
                println(show(read('n') catch Value.Nothing))
                println(if (read('x') catch Value.Num(-1)) == Value.Num(-1) { "failed" } else { "succeeded" })
            }
        """),
    )

    /** `= expr` is `{ return expr }`, the shorthand included. */
    @Test fun anExpressionBodyFailsOrReturnsTheSameWay() = assertEquals("num 2\nfailed", run("""
        import std.io
        $declarations
        func at(k: Int): Value ?! ParseError = if k < 0 then .Bad else .Num(k)
        func main() {
            println(show(at(2) catch Value.Nothing))
            println(if (at(-2) catch Value.Num(-1)) == Value.Num(-1) { "failed" } else { "succeeded" })
        }
    """))

    @Test fun aNameBothDeclareIsAmbiguous() = rejects("""
        error Problem {
            Missing
        }
        enum Answer {
            Found
            Missing
        }
        func find(): Answer ?! Problem {
            return .Missing
        }
        func main() {}
    """, "'return .Missing' is ambiguous")

    @Test fun aNameNeitherDeclaresIsRejected() = rejects("""
        error Problem {
            Missing
        }
        enum Answer {
            Found
        }
        func find(): Answer ?! Problem {
            return .Gone
        }
        func main() {}
    """, "'return .Gone' names no variant of 'Problem' or 'Answer'")

    @Test fun aDotPatternBindsThePayload() = assertEquals("on\noff\nnum 7\nnothing", run("""
        import std.io
        $declarations
        func main() {
            println(show(Value.Flag(true)))
            println(show(Value.Flag(false)))
            println(show(Value.Num(7)))
            println(show(Value.Nothing))
        }
    """))

    /** Library code reads its patterns and returns the same way. */
    @Test fun aLibraryUsesBothShorthands() = assertEquals("num 4\nfailed", run("""
        import std.io
        import lib.values
        func main() {
            println(describe(4))
            println(describe(-4))
        }
    """, LibrarySource("lib/values.az", """
        module lib.values
        $declarations
        func make(k: Int): Value ?! ParseError {
            if k < 0 { return .Bad }
            return .Num(k)
        }
        func describe(k: Int): String {
            fin made = make(k) catch Value.Nothing
            return when made {
                .Nothing -> "failed"
                else -> show(made)
            }
        }
    """.trimIndent())))

    /** A `when` expression has nowhere to put a binding, whichever spelling asks for one. */
    @Test fun aWhenExpressionCannotDestructureEitherSpelling() = rejects("""
        $declarations
        func main() {
            fin value = Value.Num(1)
            fin text = when value {
                .Num(n) -> "n"
                else -> "other"
            }
        }
    """, "a `when` expression cannot destructure a slot payload")

    /**
     * A `.Variant(a, b) ->` arm starting a line is an arm, not a method call on
     * the arm above it. Only `.name ->` and `.name(x) ->` with one binding used
     * to be recognised, so a two-binding arm chained onto the previous value.
     */
    @Test fun aMultiBindingArmAfterANewlineStartsANewArm() = assertEquals("circle 5.0\nrect 3.0x4.0\npoint", run("""
        import std.io
        variant enum Shape {
            Circle(radius: Double)
            Rect(width: Double, height: Double)
            Point
        }
        func describe(shape: Shape): String {
            return when shape {
                .Circle(radius) -> "circle ${'$'}{radius}"
                .Rect(width, height) -> "rect ${'$'}{width}x${'$'}{height}"
                else -> "point"
            }
        }
        func main() {
            println(describe(Shape.Circle(5.0)))
            println(describe(Shape.Rect(3.0, 4.0)))
            println(describe(Shape.Point))
        }
    """))

    /** A float literal in a `Double` payload is a `Double`, as it is in a `Double` field. */
    @Test fun aFloatLiteralTakesItsPayloadsWidth() = assertEquals("2.5", run("""
        import std.io
        variant enum Size {
            Exact(Double)
        }
        func main() {
            fin size = Size.Exact(2.5)
            when size {
                .Exact(v) -> { println(v) }
            }
        }
    """))
}
