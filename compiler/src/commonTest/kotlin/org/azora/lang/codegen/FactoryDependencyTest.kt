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
 * A literal needs its target's factory and whatever that factory builds, which
 * the program never names. Importing the target is enough to bring them in;
 * importing it does not make those dependencies nameable in source.
 */
class FactoryDependencyTest {
    private val libraries = listOf(
        LibrarySource("lib/seq.az", """
            module lib.seq
            spec Seq<T> {
                prop &.size: Int
                literal [...items: T]: Self { return Vector<T>(items.size) }
            }
            pack Vector<T> { var count: Int }
            impl Seq<T> for Vector<T> { prop &.size: Int = self.count }
            spec Table<K, V> {
                prop &.size: Int
                literal [...entries: (K, V)]: Self { return Rows<K, V>(entries.size) }
            }
            pack Rows<K, V> { var count: Int }
            impl Table<K, V> for Rows<K, V> { prop &.size: Int = self.count }
            pack Bag<T> { var count: Int }
            impl Bag<T> {
                literal [...items: T]: Bag<T> { return Bag<T>(items.size * 10) }
                literal [...entries: (String, T)]: Bag<T> { return Bag<T>(entries.size * 100) }
            }
            func unrelated(): Int { return 99 }
        """.trimIndent()),
    )

    private fun run(source: String): String {
        val result = Compiler(libraries).compile(source.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun aSelectedSpecBringsItsFactoryAndWhatItBuilds() = assertEquals("3\n2", run("""
        import std.io
        import lib.seq::{Seq, Table}
        func main() {
            fin values: Seq<Int> = [1, 2, 3]
            println(values.size)
            fin table: Table<String, Int> = ["a": 1, "b": 2]
            println(table.size)
        }
    """))

    @Test fun aSelectedPackBringsBothOfItsFactories() = assertEquals("20\n100", run("""
        import std.io
        import lib.seq::Bag
        func main() {
            fin items: Bag<Int> = [1, 2]
            println(items.count)
            fin named: Bag<Int> = ["one": 1]
            println(named.count)
        }
    """))

    @Test fun aWholeModuleImportReachesTheSame() = assertEquals("3\n20", run("""
        import std.io
        import lib.seq
        func main() {
            fin values: Seq<Int> = [1, 2, 3]
            println(values.size)
            fin items: Bag<Int> = [1, 2]
            println(items.count)
        }
    """))

    @Test fun anUnrelatedDeclarationStaysUnimported() {
        val result = Compiler(libraries).compile("""
            import lib.seq::Seq
            func main() {
                fin values: Seq<Int> = [1, 2, 3]
                fin other = unrelated()
            }
        """.trimIndent())
        val failure = assertIs<CompilationResult.Failure>(result)
        assertTrue(failure.errors.any { "is provided by 'lib.seq'" in it }, failure.errors.toString())
    }

    // Acceptance for 007/014: injecting a dependency must not make it nameable.
    // Today an injected declaration becomes visible by its short name.
    @Test fun aFactorysDependencyIsNotNameableInSource() {
        for (expression in listOf("Vector<Int>(3)", "Rows<String, Int>(1)")) {
            val result = Compiler(libraries).compile("""
                import lib.seq::{Seq, Table}
                func main() {
                    fin values: Seq<Int> = [1, 2, 3]
                    fin table: Table<String, Int> = ["a": 1]
                    fin other = $expression
                }
            """.trimIndent())
            val failure = assertIs<CompilationResult.Failure>(result, expression)
            assertTrue(failure.errors.any { "is provided by 'lib.seq'" in it }, failure.errors.toString())
        }
    }

    // Acceptance for 007/014: the library's factory means the library's Vector
    // whatever the program declares. Today the short name binds to the program's.
    @Test fun aProgramsOwnDeclarationDoesNotCaptureTheFactorysDependency() = assertEquals("3", run("""
        import std.io
        import lib.seq::Seq
        pack Vector<T> { var unrelated: Int }
        func main() {
            fin values: Seq<Int> = [1, 2, 3]
            println(values.size)
        }
    """))
}
