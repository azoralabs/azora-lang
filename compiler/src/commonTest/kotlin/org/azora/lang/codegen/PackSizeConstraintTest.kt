/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.ir.IrProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The canonical `.size` query counts the type pack, excluding fixed parameters. */
class PackSizeConstraintTest {
    companion object {
        val valid = """
            import std.io
            deepinline prop<Head, ...Tail> First: Type where Tail.size == 2 { return Head }
            pack Bundle<Head, ...Tail> where (...Tail).size == 2 { var value: Int }
            func<Head, ...Tail> count(head: Head, rest: ...Tail): Int where Tail.size == 2 {
                return rest.size
            }
            func<Head> forward(head: Head): Int { return count(head, 20, 30) }
            func main() {
                fin value: First<Int, String, Bool> = 42
                println(value)
                println(Bundle<Int, String, Bool>(7).value)
                println(forward(10))
            }
        """.trimIndent()

        fun compile(source: String, optimized: Boolean): IrProgram {
            val result = Compiler().compile(source.trimIndent(), release = optimized)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            return if (optimized) result.optimizedIr else result.ir
        }
    }

    private fun rejects(source: String, fragment: String) {
        for (optimized in listOf(false, true)) {
            val result = Compiler().compile(source.trimIndent(), release = optimized)
            assertIs<CompilationResult.Failure>(result, "invalid specialization compiled; optimized=$optimized")
            assertTrue(result.errors.any { fragment in it && "where" in it }, result.errors.toString())
        }
    }

    @Test fun aTypePropertyChecksGroupedAndBarePackSize() {
        for (query in listOf("Types.size", "(...Types).size", "Types.length")) {
            rejects("""
                deepinline prop<...Types> First: Type where $query >= 2 { return Types.0 }
                func invalid(): First<Int> { return 1 }
            """, "Types.${query.substringAfterLast('.')} >= 2")
        }
    }

    @Test fun aPackChecksBothSizeBoundaries() {
        for (arguments in listOf("Int", "Int, String, Bool")) {
            rejects("""
                pack Pair<...Types> where (...Types).size == 2 { var value: Int }
                func invalid(value: Pair<$arguments>): Int { return value.value }
            """, "Types.size == 2")
        }
    }

    @Test fun aFunctionChecksEmptySingletonAndOversizedPacks() {
        for (arguments in listOf("", "1", "1, 2, 3")) {
            rejects("""
                func<...Types> count(values: ...Types): Int where Types.size == 2 { return values.size }
                func main() { count($arguments) }
            """, "Types.size == 2")
        }
    }

    @Test fun fixedParametersAreNotCountedInThePack() {
        rejects("""
            pack Bundle<Head, ...Tail> where Tail.size == 2 { var value: Int }
            func invalid(value: Bundle<Int, String>): Int { return value.value }
        """, "Tail.size == 2")
        rejects("""
            func<Head, ...Tail> count(head: Head, rest: ...Tail): Int where Tail.size == 2 { return rest.size }
            func main() { count(1, 2) }
        """, "Tail.size == 2")
        rejects("""
            func<Head, ...Tail> count(head: Head, rest: ...Tail): Int where Tail.size == 2 { return rest.size }
            func<Head> invalid(head: Head): Int { return count(head, 1) }
        """, "Tail.size == 2")
    }

    @Test fun aSatisfiedConstraintSelectsTheApplicableTypeProperty() {
        val source = """
            import std.io
            deepinline prop<Head, ...Tail> Pick: Type where Tail.size > 2 { return Tail.1 }
            deepinline prop<...Types> Pick: Type where Types.size == 2 { return Types.1 }
            func main() {
                fin value: Pick<Int, String> = "chosen"
                println(value)
            }
        """
        for (optimized in listOf(false, true)) {
            assertEquals("chosen", IrInterpreter().interpret(compile(source, optimized)).trim())
        }
    }

    @Test fun validSizesRunOnTheInterpreter() {
        for (optimized in listOf(false, true)) {
            assertEquals("42\n7\n2", IrInterpreter().interpret(compile(valid, optimized)).trim())
        }
    }

    @Test fun aMemberExistsOnlyForTheMatchingTailSize() {
        val declarations = """
            pack Bundle<Head, ...Tail> { var value: Int }
            impl Bundle<Head, ...Tail> {
                func &.single(): Int where Tail.size == 1 { return self.value }
                func &.pair(): Int where Tail.size == 2 { return self.value }
            }
        """
        for (optimized in listOf(false, true)) {
            val source = """
                import std.io
                $declarations
                func main() {
                    fin value = Bundle<Int, String, Bool>(42)
                    println(value.pair())
                }
            """
            assertEquals("42", IrInterpreter().interpret(compile(source, optimized)).trim())
            val failure = assertIs<CompilationResult.Failure>(Compiler().compile("""
                $declarations
                func main() { Bundle<Int, String, Bool>(42).single() }
            """.trimIndent(), release = optimized))
            assertTrue(failure.errors.any { "single" in it && "method" in it }, failure.errors.toString())
        }
    }

    @Test fun aRuntimeSpreadDoesNotReceiveTheWrittenArgumentCount() {
        val source = """
            import std.io
            func<...Types> count(values: ...Types): Int where Types.size == 2 { return values.size }
            func main() {
                fin values = [10, 20]
                println(count(...values))
            }
        """
        for (optimized in listOf(false, true)) {
            assertEquals("2", IrInterpreter().interpret(compile(source, optimized)).trim())
        }
    }
}
