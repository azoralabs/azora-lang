package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the `meta` macro system: declaration parsing, the three invocation
 * delimiters, spread-capture splicing, nested expansion, and rejection of removed
 * standard collection macros. User-defined macros remain supported.
 */
class MacroTest {

    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source.trimIndent(), release = false)
        return assertIs(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
    }

    private fun run(source: String): String =
        IrInterpreter().interpret(compile(source).ir).trim()

    @Test
    fun userMacroConstructsAnArray() {
        val out = run(
            """
            import std.container::*
            import std.io

            macro @batch {
                [] => []
                [...${'$'}xs] => Array(...${'$'}xs)
            }

            func main() {
                fin x = @batch[1, 2, 3]
                println(x.size)
                println(x[0])
                println(x[2])
            }
            """,
        )
        assertEquals("3\n1\n3", out)
    }

    @Test
    fun userMacroEmptyArmReceivesArrayContext() {
        val out = run(
            """
            import std.container::*
            import std.io

            macro @batch {
                [] => []
                [...${'$'}xs] => Array(...${'$'}xs)
            }

            func main() {
                fin empty: Array<Int> = @batch[]
                println(empty.size)
                println(empty.size == 0)
            }
            """,
        )
        assertEquals("0\ntrue", out)
    }

    @Test
    fun allThreeDelimitersAreEquivalent() {
        val src = """
            import std.container::*
            import std.io

            macro @batch {
                [] => []
                [...${'$'}xs] => Array(...${'$'}xs)
            }

            func main() {
                fin a = @batch(1, 2)
                fin b = @batch[1, 2]
                fin c = @batch{1, 2}
                println(a.size)
                println(b.size)
                println(c.size)
            }
        """
        val expected = "2\n2\n2"
        assertEquals(expected, run(src))
        // Sanity: the three forms compile to identical programs.
        assertEquals(run(src.replace("@batch(1, 2)", "@batch[1, 2]")), run(src.replace("@batch(1, 2)", "@batch{1, 2}")))
    }

    @Test
    fun standardCollectionMacrosAreRemoved() {
        for (name in listOf("arr", "vec", "map", "set")) {
            val result = Compiler().compile("""
                import std.container::*
                func main() { fin values = @$name[1, 2, 3] }
            """.trimIndent())
            val failure = assertIs<CompilationResult.Failure>(result)
            assertTrue(failure.errors.any { name in it && "not defined" in it }, failure.errors.toString())
        }
    }

    @Test
    fun userMacroSplicesCaptureIntoMultiplePositions() {
        val out = run(
            """
            import std.container::*
            import std.io

            macro @dup {
                [...${'$'}xs] => Array(...${'$'}xs, ...${'$'}xs)
            }

            func main() {
                fin d = @dup[1, 2]
                println(d.size)
                println(d[0])
                println(d[3])
            }
            """,
        )
        // Array(1, 2, 1, 2)
        assertEquals("4\n1\n2", out)
    }

    @Test
    fun nestedMacroExpandsRecursively() {
        val out = run(
            """
            import std.container::*
            import std.io

            macro @batch {
                [] => []
                [...${'$'}xs] => Array(...${'$'}xs)
            }


            // `box` expands to a user-defined `batch` invocation, which expands again.
            macro @box {
                [...${'$'}xs] => @batch[...${'$'}xs]
            }

            func main() {
                fin b = @box[4, 5, 6]
                println(b.size)
                println(b[1])
            }
            """,
        )
        assertEquals("3\n5", out)
    }

    @Test
    fun undefinedMacroIsCompileFailure() {
        val result = Compiler().compile(
            """
            func main() {
                fin x = @nope[1, 2, 3]
            }
            """.trimIndent(),
        )
        val failure = assertIs<CompilationResult.Failure>(result, "Expected a failure for an undefined macro")
        assertTrue(
            failure.errors.any { "nope" in it && "not defined" in it },
            "Expected an 'undefined macro' error, got: ${failure.errors}",
        )
    }

    @Test
    fun macroWithNoMatchingArmIsCompileFailure() {
        // A macro with only a spread arm cannot match an empty invocation.
        val result = Compiler().compile(
            $$"""
            import std.container::*

            macro @needsArgs {
                [...$xs] => Array(...$xs)
            }

            func main() {
                fin x = @needsArgs[]
            }
            """.trimIndent(),
        )
        val failure = assertIs<CompilationResult.Failure>(result, "Expected a failure for no matching arm")
        assertTrue(
            failure.errors.any { "needsArgs" in it && "matching arm" in it },
            "Expected a 'no matching arm' error, got: ${failure.errors}",
        )
    }

    @Test
    fun expandedProgramContainsNoMacroNodes() {
        val result = compile(
            """
            import std.container::*

            macro @batch {
                [] => []
                [...${'$'}xs] => Array(...${'$'}xs)
            }

            func main() {
                fin x = @batch[1, 2, 3]
            }
            """,
        )
        val program = result.ast
        // No TopLevel.Meta survives expansion.
        assertTrue(
            program.items.none { it is TopLevel.Meta },
            "TopLevel.Meta should be removed after expansion",
        )
        // The user-defined macro expands to an ordinary Array construction, with
        // no residual MetaInvoke anywhere in the program.
        var metaInvokeCount = 0
        var arrayConstructionCount = 0
        for (item in program.items) {
            val decl = (item as? TopLevel.Func)?.decl ?: continue
            if (decl.name != "main") continue
            for (stmt in decl.body) {
                val init = (stmt as? Stmt.FinDecl)?.initializer
                    ?: (stmt as? Stmt.VarDecl)?.initializer
                    ?: (stmt as? Stmt.LetDecl)?.initializer
                if (init is Expr.Call && init.callee == "Array") arrayConstructionCount++
                countMetaInvokes(init) { metaInvokeCount++ }
            }
        }
        assertEquals(1, arrayConstructionCount, "expected the user macro to expand to Array construction")
        assertEquals(0, metaInvokeCount, "no Expr.MetaInvoke should survive expansion")
    }

    private fun countMetaInvokes(expr: Expr?, onTap: () -> Unit) {
        if (expr == null) return
        if (expr is Expr.MetaInvoke) onTap()
        when (expr) {
            is Expr.Call -> expr.args.forEach { countMetaInvokes(it, onTap) }
            is Expr.MethodCall -> { countMetaInvokes(expr.target, onTap); expr.args.forEach { countMetaInvokes(it, onTap) } }
            is Expr.Binary -> { countMetaInvokes(expr.left, onTap); countMetaInvokes(expr.right, onTap) }
            else -> {}
        }
    }
}
