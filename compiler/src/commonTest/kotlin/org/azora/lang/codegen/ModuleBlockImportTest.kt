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
 * A block's import binds in that block in a file that declares a module too,
 * whether the file is a library the program imports or the program itself.
 * [LexicalImportTest] covers a file without a module header. A library module
 * used to hoist every block import to module scope, so one function's import
 * reached its siblings.
 */
class ModuleBlockImportTest {
    private val providers = listOf(
        LibrarySource("lib/one.az", """
            module lib.one
            func answer(): Int { return 1 }
            pack Point {
                var x: Int
                var y: Int
            }
        """.trimIndent()),
        LibrarySource("lib/two.az", """
            module lib.two
            func answer(): Int { return 2 }
        """.trimIndent()),
    )

    private fun compile(source: String, vararg libraries: LibrarySource) =
        Compiler(providers + libraries).compile(source.trimIndent())

    private fun run(source: String, vararg libraries: LibrarySource): String {
        val result = compile(source, *libraries)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun rejects(source: String, fragment: String, vararg libraries: LibrarySource) {
        val result = compile(source, *libraries)
        assertIs<CompilationResult.Failure>(result, "the program compiled")
        assertTrue(result.errors.any { fragment in it }, "expected '$fragment' in ${result.errors}")
    }

    private fun library(module: String, body: String) =
        LibrarySource(module.replace('.', '/') + ".az", "module $module\n" + body.trimIndent())

    @Test fun siblingFunctionsReachTheirOwnProviders() = assertEquals("1\n2", run("""
        import std.io
        import lib.user
        func main() {
            println(one())
            println(two())
        }
    """, library("lib.user", """
        func one(): Int {
            import lib.one
            return answer()
        }
        func two(): Int {
            import lib.two
            return answer()
        }
    """)))

    @Test fun aLibraryBlockImportDoesNotReachItsSiblings() = rejects("""
        import std.io
        import lib.leak
        func main() { println(unimported()) }
    """, "answer", library("lib.leak", """
        func imported(): Int {
            import lib.one
            return answer()
        }
        func unimported(): Int { return answer() }
    """))

    @Test fun aLibraryBlockImportReachesEnclosedBlocksOnly() = assertEquals("2\n1", run("""
        import std.io
        import lib.nest
        func main() {
            println(inner())
            println(outer())
        }
    """, library("lib.nest", """
        import lib.one
        func inner(): Int {
            if true {
                import lib.two
                return answer()
            }
            return 0
        }
        func outer(): Int {
            if true {
                import lib.two
                fin unused = answer()
            }
            return answer()
        }
    """)))

    @Test fun aLibraryBlockImportsATypeThere() = assertEquals("3", run("""
        import std.io
        import lib.shape
        func main() { println(width()) }
    """, library("lib.shape", """
        func width(): Int {
            import lib.one
            fin point: Point = Point(3, 4)
            return point.x
        }
    """)))

    @Test fun aLibraryLocalShadowsItsBlockImport() = assertEquals("7", run("""
        import std.io
        import lib.local
        func main() { println(shadowed()) }
    """, library("lib.local", """
        func shadowed(): Int {
            import lib.one
            fin answer = 7
            return answer
        }
    """)))

    @Test fun whatALibraryBlockImportsIsNotTheProgramsToName() = rejects("""
        import std.io
        import lib.user
        func main() {
            println(one())
            println(answer())
        }
    """, "answer", library("lib.user", """
        func one(): Int {
            import lib.one
            return answer()
        }
    """))

    @Test fun aProgramThatDeclaresAModuleBindsBlockImportsLexically() = rejects("""
        module app
        import std.io
        func one(): Int {
            import lib.one
            return answer()
        }
        func main() { println(answer()) }
    """, "answer")

    @Test fun aProgramThatDeclaresAModuleReachesWhatABlockImports() = assertEquals("1\n2", run("""
        module app
        import std.io
        func one(): Int {
            import lib.one
            return answer()
        }
        func main() {
            println(one())
            import lib.two
            println(answer())
        }
    """))
}
