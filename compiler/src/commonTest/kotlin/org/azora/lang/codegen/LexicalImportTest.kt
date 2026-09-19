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
 * An import written inside a block binds in that block and the blocks it
 * encloses, and nowhere else. Two blocks may import different providers of the
 * same name; each reaches its own.
 */
class LexicalImportTest {
    private val libraries = listOf(
        LibrarySource("lib/one.az", """
            module lib.one
            func answer(): Int { return 1 }
            pack Point {
                var x: Int
                var y: Int
            }
            func origin(): Point { return Point(0, 0) }
        """.trimIndent()),
        LibrarySource("lib/two.az", """
            module lib.two
            func answer(): Int { return 2 }
        """.trimIndent()),
    )

    private fun compile(source: String) = Compiler(libraries).compile(source.trimIndent())

    private fun run(source: String): String {
        val result = compile(source)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun rejects(source: String, vararg fragments: String) {
        val result = compile(source)
        assertIs<CompilationResult.Failure>(result, "the program compiled")
        for (fragment in fragments) {
            assertTrue(result.errors.any { fragment in it }, "expected '$fragment' in ${result.errors}")
        }
    }

    @Test fun aFunctionReachesWhatItImports() = assertEquals("1", run("""
        import std.io
        func one(): Int {
            import lib.one
            return answer()
        }
        func main() { println(one()) }
    """))

    @Test fun anImportDoesNotReachPastItsBlock() = rejects("""
        import std.io
        func one(): Int {
            import lib.one
            return answer()
        }
        func main() { println(answer()) }
    """, "answer")

    @Test fun siblingBlocksReachTheirOwnProviders() = assertEquals("1\n2", run("""
        import std.io
        func one(): Int {
            import lib.one
            return answer()
        }
        func two(): Int {
            import lib.two
            return answer()
        }
        func main() {
            println(one())
            println(two())
        }
    """))

    @Test fun anEnclosedBlockSeesTheImport() = assertEquals("2", run("""
        import std.io
        func main() {
            import lib.two
            if true {
                println(answer())
            }
        }
    """))

    @Test fun anImportInAnEnclosedBlockStaysThere() = rejects("""
        import std.io
        func main() {
            if true {
                import lib.one
                println(answer())
            }
            println(answer())
        }
    """, "answer")

    @Test fun anImportedTypeBindsInTheBlock() = assertEquals("3\n0", run("""
        import std.io
        func main() {
            import lib.one
            fin point: Point = Point(3, 4)
            println(point.x)
            println(origin().y)
        }
    """))

    @Test fun anImportedTypeIsNotNameableElsewhere() = rejects("""
        import std.io
        func make(): Int {
            import lib.one
            return Point(3, 4).x
        }
        func main() {
            fin point: Point = Point(1, 2)
            println(make())
        }
    """, "Point")

    @Test fun aLocalShadowsWhatTheBlockImports() = assertEquals("7\n1", run("""
        import std.io
        func main() {
            import lib.one
            fin answer = 7
            println(answer)
            println(one())
        }
        func one(): Int {
            import lib.one
            return answer()
        }
    """))

    @Test fun aBlockMayImportWhatTheFileImports() = assertEquals("2\n2", run("""
        import std.io
        import lib.two
        func main() {
            import lib.two
            println(answer())
            println(two())
        }
        func two(): Int { return answer() }
    """))

    @Test fun aSelectedBlockImportBindsOnlyTheSelection() = rejects("""
        import std.io
        func main() {
            import lib.one::answer
            println(answer())
            println(origin().x)
        }
    """, "origin")

    @Test fun aBlockImportIsValidatedLikeAFileImport() = rejects("""
        import std.io
        func main() {
            import std
            println(1)
        }
    """, "'std' is a namespace, not a module")

    @Test fun aTestsImportDoesNotReachTheProgram() = rejects("""
        import std.io
        func main() { println(answer()) }
        test "the answer" {
            import lib.two
            assert answer() == 2 panic "wrong provider"
        }
    """, "answer")

    @Test fun aTestMayImportWhatOnlyItUses() {
        val result = Compiler(libraries).compile("""
            import std.io
            func main() { println(0) }
            test "the answer" {
                import lib.two
                assert answer() == 2 panic "wrong provider"
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
    }
}
