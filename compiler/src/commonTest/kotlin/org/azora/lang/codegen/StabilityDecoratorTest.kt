package org.azora.lang.codegen

import org.azora.lang.Compiler
import org.azora.lang.CompilationResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StabilityDecoratorTest {

    private fun errors(source: String): List<String> {
        val r = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Failure>(r, "expected compile failure, got success")
        return (r as CompilationResult.Failure).errors
    }

    private fun compiles(source: String) {
        val r = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(r, "expected success, got ${(r as? CompilationResult.Failure)?.errors}")
    }

    @Test fun experimentalAndStableAreMutuallyExclusive() {
        val e = errors("""
            import std.io
            @Experimental(since: "0.1")
            @Stable(since: "0.1")
            func f(): Int { return 1 }
        """.trimIndent())
        assertTrue(e.any { it.contains("@experimental") && it.contains("@stable") }, e.toString())
    }

    @Test fun experimentalWithStringSinceIsAccepted() {
        compiles("""
            import std.io
            @Experimental(since: "0.1")
            func f(): Int { return 1 }
        """.trimIndent())
    }

    @Test fun stableWithStringSinceIsAccepted() {
        compiles("""
            import std.io
            @Stable(since: "0.1")
            func f(): Int { return 1 }
        """.trimIndent())
    }

    @Test fun nonStringSinceIsRejected() {
        val e = errors("""
            import std.io
            @Experimental(since: 5)
            func f(): Int { return 1 }
        """.trimIndent())
        assertTrue(e.any { it.contains("string version") }, e.toString())
    }

    @Test fun sinceStandaloneIsAccepted() {
        compiles("""
            import std.io
            @Since("0.1")
            func f(): Int { return 1 }
        """.trimIndent())
    }

    @Test fun deprecatedIsAccepted() {
        compiles("""
            import std.io
            @Deprecated(since: "0.1", replacement: "g")
            func f(): Int { return 1 }
        """.trimIndent())
    }

    @Test fun experimentalAndSinceStandaloneConflict() {
        val e = errors("""
            import std.io
            @Experimental(since: "0.1")
            @Since("0.1")
            func f(): Int { return 1 }
        """.trimIndent())
        assertTrue(e.any { it.contains("@since is redundant") }, e.toString())
    }

    @Test fun failSetWithVariantAnnotationsAccepted() {
        compiles("""
            import std.io
            @Since("0.1")
            error SearchError {
                NotFound @Deprecated(since: "0.1", replacement: "EmptyResult")
                EmptyArray @Since("0.1")
                EmptyResult
            }
            func main() {}
        """.trimIndent())
    }

    @Test fun unknownDecoratorIsRejected() {
        // A decorator is capitalised (a lowercase `@name` is a macro), so the
        // misspelling worth catching is of the capitalised name.
        val e = errors("""
            import std.io
            @Experiemntal(since: "0.1")
            func f(): Int { return 1 }
        """.trimIndent())
        assertTrue(
            e.any { "unknown decorator '@Experiemntal'" in it && "did you mean '@Experimental'?" in it },
            e.toString(),
        )
    }

    @Test fun aDecoratorNothingDeclaresSuggestsNothing() {
        val e = errors("""
            @Frobnicate
            func f(): Int { return 1 }
        """.trimIndent())
        assertTrue(e.any { it == "line 1: unknown decorator '@Frobnicate'" }, e.toString())
    }

    @Test fun aLibraryDecoratorNamesTheImportThatBringsItIn() {
        val e = errors("""
            @Serializable
            pack Point { fin x: Int = 0 }
        """.trimIndent())
        assertTrue(
            e.any { "'@Serializable' is provided by 'std.serializer': add 'import std.serializer::Serializable'" in it },
            e.toString(),
        )
    }
}
