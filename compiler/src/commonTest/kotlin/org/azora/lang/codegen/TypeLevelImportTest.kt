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
 * Type functions (`deepinline prop<…> name: Type`) and named type macros are
 * imported like declarations: selected by name, and bound where the import is
 * written. A block's import used to bring neither, and a selection of one by
 * name brought nothing even at file scope.
 *
 * A type macro's template is its module's code, so the names in it mean what
 * they mean in that module, whatever the program imported or declares.
 */
class TypeLevelImportTest {
    private val rows = LibrarySource("lib/rows.az", """
        module lib.rows
        pack Rows<T> {
            var v: T
        }
        macro @rows {
            ${'$'}T => Rows<${'$'}T>
        }
        func makeRows(v: Int): Rows<Int> { return Rows(v) }
        deepinline prop<A, B> wider: Type {
            if B.rank > A.rank { return B }
            return A
        }
    """.trimIndent())

    private fun compile(source: String, vararg more: LibrarySource) =
        Compiler(listOf(rows) + more).compile(source.trimIndent())

    private fun run(source: String, vararg more: LibrarySource): String {
        val result = compile(source, *more)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun rejects(source: String, fragment: String) {
        val result = compile(source)
        assertIs<CompilationResult.Failure>(result, "the program compiled")
        assertTrue(result.errors.any { fragment in it }, "expected '$fragment' in ${result.errors}")
    }

    @Test fun aTypeFunctionImportedInABlockResolvesThere() = assertEquals("1.5", run("""
        import std.io
        func main() {
            import lib.rows
            fin x: wider<Int, Double> = 1.5
            println(x)
        }
    """))

    @Test fun aTypeFunctionImportedInABlockStaysThere() = rejects("""
        import std.io
        func other() {
            import lib.rows
        }
        func main() {
            fin x: wider<Int, Double> = 1.5
            println(x)
        }
    """, "wider")

    @Test fun aTypeMacroImportedInABlockExpandsThere() = assertEquals("4", run("""
        import std.io
        func main() {
            import lib.rows
            fin r: @rows Int = Rows(4)
            println(r.v)
        }
    """))

    @Test fun aTypeMacroImportedInABlockStaysThere() = rejects("""
        import std.io
        import lib.rows::Rows
        func other() {
            import lib.rows
        }
        func main() {
            fin r: @rows Int = Rows(4)
            println(r.v)
        }
    """, "undefined type macro 'rows'")

    /** A type macro is a macro, and a macro is invoked behind its `@`. */
    @Test fun aTypeMacroIsInvokedWithItsSigil() = rejects("""
        import std.io
        import lib.rows
        func main() {
            fin r: rows Int = Rows(4)
            println(r.v)
        }
    """, "write '@rows …' instead of 'rows …'")

    /** `.(…)` builds the type the macro expands to, as it builds any declared type. */
    @Test fun theInferredConstructorBuildsTheExpandedType() = assertEquals("4\n5", run("""
        import std.io
        import lib.rows
        func size(r: @rows Int): Int { return r.v }
        func main() {
            fin r: @rows Int = .(4)
            println(r.v)
            println(size(.(5)))
        }
    """))

    @Test fun aTypeFunctionAndATypeMacroMayBeSelectedByName() = assertEquals("1.5\n4", run("""
        import std.io
        import lib.rows::{Rows, rows, wider}
        func main() {
            fin x: wider<Int, Double> = 1.5
            println(x)
            fin r: @rows Int = Rows(4)
            println(r.v)
        }
    """))

    /** The template's `Rows` is the library's, which the program never imported. */
    @Test fun aTypeMacroTemplateReadsItsOwnModule() = assertEquals("4", run("""
        import std.io
        import lib.rows::{rows, makeRows}
        func size(r: @rows Int): Int { return r.v }
        func main() {
            fin r: @rows Int = makeRows(4)
            println(size(r))
        }
    """))

    /** A program's own `Rows` does not capture the template's. */
    @Test fun aTypeMacroTemplateIsNotCapturedByTheProgram() = assertEquals("4\n9", run("""
        import std.io
        import lib.rows::{rows, makeRows}
        pack Rows {
            var w: Int
        }
        func main() {
            fin r: @rows Int = makeRows(4)
            println(r.v)
            println(Rows(9).w)
        }
    """))

    @Test fun aLibraryBlockImportsTypeFunctionsAndMacros() = assertEquals("2.5\n6", run("""
        import std.io
        import lib.user
        func main() {
            println(half())
            println(count())
        }
    """, LibrarySource("lib/user.az", """
        module lib.user
        func half(): Double {
            import lib.rows
            fin x: wider<Int, Double> = 2.5
            return x
        }
        func count(): Int {
            import lib.rows
            fin r: @rows Int = Rows(6)
            return r.v
        }
    """.trimIndent())))
}
