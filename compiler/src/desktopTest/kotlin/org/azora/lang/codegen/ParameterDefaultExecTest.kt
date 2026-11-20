/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.LibrarySource
import org.azora.lang.backend.LlvmCodegen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A parameter default that builds a specialized pack. `rotation: Vec3 =
 * Vec3(0.0, 0.0, 0.0)` was never rewritten by the monomorphizer, which rewrote
 * bodies but not defaults, so every call leaving it out lowered a call to the
 * alias - a name nothing defines by then - and code generation stopped with
 * "undefined function or variable 'Vec3'" and no line (2026-11-20). Defaults
 * were not resolved at their declaration either, so a genuinely undefined name
 * in one passed `azora check` and failed the same way.
 */
class ParameterDefaultExecTest {
    private val libraries = listOf(
        LibrarySource("shapes.az", """
            module shapes

            pack Grid<T, N: Int> where T is Number && N in 2..3 {
                var x: T = 0
                var y: T = 0
                var z: T = 0 where N == 3
            }

            typealias Grid3 = Grid<Double, 3>
        """.trimIndent()),
        LibrarySource("place.az", """
            module place

            import shapes::*

            func place(x: Double, at: Grid3 = Grid3(1.0, 2.0, 3.0)): Double = x + at.x + at.y + at.z

            func spread(x: Double, at: Grid<Double, 2> = Grid<Double, 2>(4.0, 5.0)): Double = x + at.x + at.y
        """.trimIndent()),
    )

    private val program = """
        import std.io
        import place::*

        func main() {
            println(place(1.0))
            println(spread(1.0))
        }
    """.trimIndent()

    @Test fun llvmBuildsTheDefaultInTheDeclaringModule() {
        for (optimized in listOf(false, true)) {
            val result = Compiler(libraries).compile(program, release = optimized)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            val ir = LlvmCodegen().generate(if (optimized) result.optimizedIr else result.ir)
            assertEquals("7.0\n10.0", LlvmExec.runIr(ir).trim(), "optimized=$optimized")
        }
    }

    @Test fun anUndefinedNameInADefaultIsReportedAtItsLine() {
        val result = Compiler(listOf(
            LibrarySource("lib.az", """
                module lib

                func shift(x: Double, amount: Double = missingValue(2.0)): Double = x + amount
            """.trimIndent()),
        )).compile("import std.io\nimport lib::*\nfunc main() { println(shift(1.0)) }")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { it.startsWith("line 3:") && "missingValue" in it },
            result.errors.joinToString("\n"),
        )
    }

    @Test fun aDefaultOfTheWrongTypeIsReported() {
        val result = Compiler().compile("""
            import std.io
            func shift(x: Double, amount: Double = "far"): Double = x + amount
            func main() { println(shift(1.0)) }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { it.startsWith("line 2:") && "amount" in it }, result.errors.joinToString("\n"))
    }
}
