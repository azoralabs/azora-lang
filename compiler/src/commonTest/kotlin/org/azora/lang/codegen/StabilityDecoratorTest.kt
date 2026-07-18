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
            @Experimental(sinceAzora: "0.0.1")
            @Stable(sinceAzora: "1.0.0")
            func f(): Int { return 1 }
        """.trimIndent())
        assertTrue(e.any { it.contains("@experimental") && it.contains("@stable") }, e.toString())
    }

    @Test fun experimentalWithStringSinceIsAccepted() {
        compiles("""
            import std.io
            @Experimental(sinceAzora: "0.0.1")
            func f(): Int { return 1 }
        """.trimIndent())
    }

    @Test fun stableWithStringSinceIsAccepted() {
        compiles("""
            import std.io
            @Stable(sinceAzora: "1.0.0")
            func f(): Int { return 1 }
        """.trimIndent())
    }

    @Test fun nonStringSinceIsRejected() {
        val e = errors("""
            import std.io
            @Experimental(sinceAzora: 5)
            func f(): Int { return 1 }
        """.trimIndent())
        assertTrue(e.any { it.contains("string version") }, e.toString())
    }

    @Test fun sinceStandaloneIsAccepted() {
        compiles("""
            import std.io
            @SinceAzora("0.0.1")
            func f(): Int { return 1 }
        """.trimIndent())
    }

    @Test fun deprecatedIsAccepted() {
        compiles("""
            import std.io
            @Deprecated(sinceAzora: "0.4.0", replacement: "g")
            func f(): Int { return 1 }
        """.trimIndent())
    }

    @Test fun experimentalAndSinceStandaloneConflict() {
        val e = errors("""
            import std.io
            @Experimental(sinceAzora: "0.0.1")
            @SinceAzora("0.0.1")
            func f(): Int { return 1 }
        """.trimIndent())
        assertTrue(e.any { it.contains("@since is redundant") }, e.toString())
    }

    @Test fun failSetWithVariantAnnotationsAccepted() {
        compiles("""
            import std.io
            @SinceAzora("0.0.1")
            fail SearchError {
                NotFound @Deprecated(sinceAzora: "0.4.0", replacement: "EmptyResult")
                EmptyArray @SinceAzora("0.0.0")
                EmptyResult
            }
            func main() {}
        """.trimIndent())
    }

    @Test fun unknownDecoratorIsRejected() {
        val e = errors("""
            import std.io
            @experiemntal(sinceAzora: "0.0.1")
            func f(): Int { return 1 }
        """.trimIndent())
        assertTrue(e.any { it.contains("unknown decorator") && it.contains("@experiemntal") }, e.toString())
    }
}
