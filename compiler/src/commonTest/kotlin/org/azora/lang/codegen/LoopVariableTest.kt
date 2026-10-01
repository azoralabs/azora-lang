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
 * A `for` loop's own bindings - its row and its `with` index - are bound afresh
 * on every iteration, on every backend. Writing one never changed what the loop
 * visits, yet a range loop's variable accepted `i += 2` and `i++` and ignored
 * them: the standard library's merge passes and digit parser were written that
 * way and silently did something else. Every write to one is now an error.
 */
class LoopVariableTest {
    private fun compile(source: String) = Compiler().compile(source.trimIndent(), release = false)

    private fun rejects(source: String, name: String, vararg lines: Int) {
        val result = assertIs<CompilationResult.Failure>(compile(source), "the program compiled")
        for (line in lines) {
            assertTrue(
                result.errors.any { it.startsWith("line $line: '$name' is the loop's variable, bound afresh") },
                "expected a write to '$name' on line $line in ${result.errors}",
            )
        }
    }

    private fun run(source: String): String {
        val result = compile(source)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun aRangeLoopCannotBeSteppedThroughItsVariable() = rejects("""
        import std.io
        func main() {
            for i: Int in 0..6 {
                println(i)
                i += 2
            }
        }
    """, "i", 5)

    @Test fun everyKindOfWriteIsRejected() = rejects("""
        import std.io
        func main() {
            for i in 0..<3 {
                i = 0
                i++
                --i
            }
        }
    """, "i", 4, 5, 6)

    @Test fun anArrayRowIsNotWritten() = rejects("""
        import std.io
        func main() {
            for value in [1, 2, 3] {
                value = value * 10
                value++
            }
        }
    """, "value", 4, 5)

    @Test fun theWithIndexIsNotWritten() = rejects("""
        import std.io
        func main() {
            for value in [1, 2, 3] with index {
                index += 1
                println(value)
            }
        }
    """, "index", 4)

    /** Reading the variable, and changing a copy of it, are what a body may do. */
    @Test fun aCopyOfTheVariableMayChange() = assertEquals("10 11 12", run("""
        import std.io
        func main() {
            var line = ""
            for i in 0..<3 {
                var shifted = i
                shifted += 10
                line = line + "${'$'}{shifted} "
            }
            println(line)
        }
    """))

    /** Stepping by hand is what a `while` loop is for. */
    @Test fun aWhileLoopStepsByHand() = assertEquals("0 3 6", run("""
        import std.io
        func main() {
            var line = ""
            var i = 0
            while i <= 6 {
                line = line + "${'$'}{i} "
                i += 3
            }
            println(line)
        }
    """))
}
