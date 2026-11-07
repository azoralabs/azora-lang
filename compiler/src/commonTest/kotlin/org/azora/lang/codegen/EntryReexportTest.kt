/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.LibrarySource
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `exposed import` imports a module and re-exports it. What it imports is
 * nameable in the module that writes it whether that module is a library or the
 * entry: a module compiled on its own (`azora check`, a package's tests) sees
 * the same names it sees when another program imports it.
 */
class EntryReexportTest {
    private val point = LibrarySource(
        "lib/point.az",
        """
        module lib.point

        pack Point {
            var x: Int = 0
            var y: Int = 0
        }
        """.trimIndent(),
    )

    private val facade = """
        module lib

        exposed import lib.point
        import std.io

        func origin(): Point = Point(3, 4)

        func main() {
            println(origin().x + origin().y)
        }
    """.trimIndent()

    @Test
    fun anEntryNamesWhatItsExposedImportsImport() {
        val result = Compiler(listOf(point)).compile(facade)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors?.joinToString("\n"))
        assertEquals("7", IrInterpreter().interpret(result.ir).trim())
    }

    @Test
    fun theSameModuleAsALibraryStillReexports() {
        val program = """
            import std.io
            import lib

            func main() {
                val p = Point(1, 2)
                println(origin().x + p.y)
            }
        """.trimIndent()
        val result = Compiler(listOf(point, LibrarySource("lib/lib.az", facade.replace(Regex("(?s)func main\\(\\).*"), "")))).compile(program)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors?.joinToString("\n"))
        assertEquals("5", IrInterpreter().interpret(result.ir).trim())
    }
}
