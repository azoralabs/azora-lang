/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Indexing an array outside its elements is a panic that names the index and
 * the size, on reads and on writes. The interpreter used to surface the host's
 * own exception.
 *
 * A program's output reaches [IrInterpreter.outputSink] as it is written, so
 * what it printed before failing is not lost with the failure.
 */
class IndexBoundsTest {
    private fun compile(source: String) =
        assertIs<CompilationResult.Success>(Compiler().compile(source.trimIndent(), release = false)).ir

    private fun panicOf(source: String): String =
        assertFailsWith<RuntimeException> { IrInterpreter().interpret(compile(source)) }.message.orEmpty()

    @Test fun aReadPastTheEndPanics() = assertEquals("panic: index 2 out of bounds for size 2", panicOf("""
        import std.io
        func main() {
            fin xs = [10, 20]
            fin at = 2
            println(xs[at])
        }
    """))

    @Test fun aNegativeIndexPanics() = assertEquals("panic: index -1 out of bounds for size 2", panicOf("""
        import std.io
        func main() {
            fin xs = [10, 20]
            fin at = -1
            println(xs[at])
        }
    """))

    @Test fun aWriteOutsideTheElementsPanics() = assertEquals("panic: index 3 out of bounds for size 2", panicOf("""
        func main() {
            var xs = [10, 20]
            fin at = 3
            xs[at] = 0
        }
    """))

    @Test fun theLastElementIsInBounds() {
        val output = IrInterpreter().interpret(compile("""
            import std.io
            func main() {
                var xs = [10, 20]
                xs[1] = 21
                println(xs[1])
                println(xs[0])
            }
        """))
        assertEquals("21\n10", output)
    }

    @Test fun outputBeforeAFailureReachesTheSink() {
        val written = StringBuilder()
        val interpreter = IrInterpreter().apply { outputSink = { written.append(it) } }
        val ir = compile("""
            import std.io
            func main() {
                print("a")
                println("b")
                fin xs = [1]
                fin at = 1
                println(xs[at])
            }
        """)
        val failure = assertFailsWith<RuntimeException> { interpreter.interpret(ir) }
        assertEquals("panic: index 1 out of bounds for size 1", failure.message)
        assertEquals("ab\n", written.toString())
    }
}
