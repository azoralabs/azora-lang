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
import kotlin.test.fail

/**
 * Tests for the `meta` macro system: declaration parsing, the three invocation
 * delimiters, spread-capture splicing, nested macro expansion, and the stdlib
 * `vec`/`set`/`tuple`/`arr` macros.
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
    fun vecMacroExpandsToListOf() {
        val out = run(
            """
            import std.macro
            import std.container
            import std.io

            func main() {
                fin x = vec![1, 2, 3]
                std::println(x.size)
                std::println(x[0])
                std::println(x[2])
            }
            """,
        )
        assertEquals("3\n1\n3", out)
    }

    @Test
    fun vecMacroEmptyArmExpandsToEmptyList() {
        val out = run(
            """
            import std.macro
            import std.container
            import std.io

            func main() {
                fin empty: List<Int> = vec![]
                std::println(empty.size)
                std::println(empty.isEmpty)
            }
            """,
        )
        assertEquals("0\ntrue", out)
    }

    @Test
    fun allThreeDelimitersAreEquivalent() {
        val src = """
            import std.macro
            import std.container
            import std.io

            func main() {
                fin a = vec!(1, 2)
                fin b = vec![1, 2]
                fin c = vec!{1, 2}
                std::println(a.size)
                std::println(b.size)
                std::println(c.size)
            }
        """
        val expected = "2\n2\n2"
        assertEquals(expected, run(src))
        // Sanity: the three forms compile to identical programs.
        assertEquals(run(src.replace("vec!(1, 2)", "vec![1, 2]")), run(src.replace("vec!(1, 2)", "vec!{1, 2}")))
    }

    @Test
    fun setTupleArrMacrosExpandToConstructors() {
        val out = run(
            """
            import std.macro
            import std.container
            import std.io

            func main() {
                fin s = set![1, 2, 3]
                fin a = arr![10, 20, 30]
                std::println(s.size)
                std::println(a.length)
                std::println(a[2])
            }
            """,
        )
        assertEquals("3\n3\n30", out)
    }

    @Test
    fun userMacroSplicesCaptureIntoMultiplePositions() {
        val out = run(
            """
            import std.container
            import std.io

            meta dup {
                [...${'$'}xs] => std::listOf(...${'$'}xs, ...${'$'}xs)
            }

            func main() {
                fin d = dup![1, 2]
                std::println(d.size)
                std::println(d[0])
                std::println(d[3])
            }
            """,
        )
        // std::listOf(1, 2, 1, 2)
        assertEquals("4\n1\n2", out)
    }

    @Test
    fun nestedMacroExpandsRecursively() {
        val out = run(
            """
            import std.macro
            import std.container
            import std.io

            // `box` expands to a `vec!` invocation, which must then itself expand.
            meta box {
                [...${'$'}xs] => vec![...${'$'}xs]
            }

            func main() {
                fin b = box![4, 5, 6]
                std::println(b.size)
                std::println(b[1])
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
                fin x = nope![1, 2, 3]
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
            """
            import std.macro
            import std.container

            meta needsArgs {
                [...${'$'}xs] => std::listOf(...${'$'}xs)
            }

            func main() {
                fin x = needsArgs![]
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
            import std.macro
            import std.container

            func main() {
                fin x = vec![1, 2, 3]
            }
            """,
        )
        val program = result.ast
        // No TopLevel.Meta survives expansion.
        assertTrue(
            program.items.none { it is TopLevel.Meta },
            "TopLevel.Meta should be removed after expansion",
        )
        // The `vec![1,2,3]` site lowered to a concrete `std__listOf` call, with
        // no residual MetaInvoke anywhere in the program.
        var metaInvokeCount = 0
        var listOfCallCount = 0
        for (item in program.items) {
            val decl = (item as? TopLevel.Func)?.decl ?: continue
            if (decl.name != "main") continue
            for (stmt in decl.body) {
                val init = (stmt as? Stmt.FinDecl)?.initializer
                    ?: (stmt as? Stmt.VarDecl)?.initializer
                    ?: (stmt as? Stmt.LetDecl)?.initializer
                if (init is Expr.Call && init.callee == "std__listOf") listOfCallCount++
                countMetaInvokes(init) { metaInvokeCount++ }
            }
        }
        assertEquals(1, listOfCallCount, "expected the vec! site to lower to std__listOf")
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
