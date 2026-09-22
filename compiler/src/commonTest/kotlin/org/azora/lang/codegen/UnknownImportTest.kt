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
 * An import names something that exists: a module, a namespace for a wildcard,
 * or what a module declares. One that names nothing is an error at the clause
 * that wrote it, at file scope and in a block alike. It used to import nothing
 * and compile.
 */
class UnknownImportTest {
    private val libraries = listOf(
        LibrarySource("lib/one.az", """
            module lib.one
            func answer(): Int { return 1 }
            scope tools {
                func helper(): Int { return 3 }
            }
        """.trimIndent()),
        LibrarySource("lib/inner/deep.az", """
            module lib.inner.deep
            import lib.one
            func deep(): Int { return answer() + 1 }
        """.trimIndent()),
    )

    private fun compile(source: String) = Compiler(libraries).compile(source.trimIndent())

    private fun run(source: String): String {
        val result = compile(source)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun errors(source: String): List<String> {
        val result = compile(source)
        return assertIs<CompilationResult.Failure>(result, "the program compiled").errors
    }

    @Test fun aMissingStandardModuleIsRejected() = assertEquals(
        listOf("line 2: there is no module 'std.nothere' to import"),
        errors("""
            import std.io
            import std.nothere
            func main() { println(1) }
        """),
    )

    @Test fun aModuleUnderAnUnknownRootIsRejected() = assertEquals(
        listOf("line 2: there is no module 'app.missing' to import"),
        errors("""
            import std.io
            import app.missing
            func main() { println(1) }
        """),
    )

    @Test fun aMissingModuleUnderAKnownNamespaceIsRejected() = assertEquals(
        listOf("line 2: there is no module 'lib.missing' to import"),
        errors("""
            import std.io
            import lib.missing
            func main() { println(1) }
        """),
    )

    @Test fun theFirstMissingSegmentIsNamed() = assertEquals(
        listOf("line 2: there is no module 'std.nothere' to import"),
        errors("""
            import std.io
            import std.nothere::thing
            func main() { println(1) }
        """),
    )

    @Test fun aBlockImportIsRejectedWhereItIsWritten() = assertEquals(
        listOf("line 4: there is no module 'lib.missing' to import"),
        errors("""
            import std.io
            func main() {
                println(1)
                import lib.missing
            }
        """),
    )

    @Test fun aTestsImportIsRejectedToo() = assertEquals(
        listOf("line 4: there is no module 'std.nothere' to import"),
        errors("""
            import std.io
            func main() { println(1) }
            test "missing" {
                import std.nothere
                assert true panic "unreachable"
            }
        """),
    )

    @Test fun aWildcardNeedsAModuleOrNamespace() = assertEquals(
        listOf(
            "line 2: there is no module or namespace 'std.nothere' to import from",
            "line 3: there is no module or namespace 'nowhere' to import from",
        ),
        errors("""
            import std.io
            import std.nothere::*
            import nowhere::{*, without x}
            func main() { println(1) }
        """),
    )

    @Test fun eachGroupMemberIsChecked() = assertEquals(
        listOf(
            "line 2: there is no module 'std.nothere' to import",
            "line 4: module 'std.math' has nothing named 'nothere' to import",
        ),
        errors("""
            import std.io
            import std::{math, nothere}
            import std.math::{
                abs, nothere
            }
            func main() { println(abs(-1)) }
        """),
    )

    @Test fun aSelectionMustBeDeclaredByItsModule() = assertEquals(
        listOf(
            "line 2: module 'lib.one' has nothing named 'nothere' to import",
            "line 3: module 'std.math' has nothing named 'nothere' to import",
            "line 4: module 'lib.one' has nothing named 'tools::nothere' to import",
        ),
        errors("""
            import std.io
            import lib.one::nothere
            import std.math.nothere
            import lib.one.tools.nothere
            func main() { println(1) }
        """),
    )

    /** A file declaring a module is checked the same way. */
    @Test fun aModuleFileIsCheckedToo() = assertEquals(
        listOf("line 3: there is no module 'lib.missing' to import"),
        errors("""
            module app.main
            import std.io
            import lib.missing
            func main() { println(1) }
        """),
    )

    /** A module below the program's own is looked up like any other. */
    @Test fun aModuleBelowTheProgramsOwnIsChecked() = assertEquals(
        listOf("line 3: there is no module 'app.missing' to import"),
        errors("""
            module app
            import std.io
            import app.missing
            func main() { println(1) }
        """),
    )

    /** Naming a scope the program declares is a no-op, not a missing module. */
    @Test fun aScopeTheProgramDeclaresIsNotMissing() = assertEquals("5", run("""
        import std.io
        scope Const {
            fin five = 5
        }
        import Const
        func main() { println(Const::five) }
    """))

    @Test fun everyFormThatNamesSomethingStillCompiles() = assertEquals("1\n2\n2\n1\n3", run("""
        import std.io
        import std.math
        import std.math::abs
        import std.math.abs
        import std.traits::{Equal, promote}
        import lib.one
        import lib::*
        import lib.inner::*
        import lib.one::tools
        import lib.one.tools.helper
        func main() {
            println(answer())
            println(deep())
            import lib.inner.deep
            println(deep())
            println(abs(-1))
            println(tools::helper())
        }
    """))
}
