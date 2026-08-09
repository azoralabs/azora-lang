package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import org.azora.lang.ir.IrExpr
import org.azora.lang.ir.IrGenerator
import org.azora.lang.ir.IrStmt
import org.azora.lang.semantic.SemanticPipeline
import kotlin.test.*

/**
 * Tier 3 - fail-set error model (foundation).
 *
 * `fail ErrSet { … }` declares an error set; `T ?! ErrSet` annotates a failable return
 * type; `fail ErrSet.Variant` (or `throw`) raises an error; `try/catch` and
 * `expr catch fallback` handle them. Errors propagate via the existing exception
 * machinery, so the IR type of `T ?! ErrSet` is just `T`.
 */
class Tier3ErrorModelTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun failableFunctionThrowsAndCaught() {
        assertEquals("Bad", run("""
            import std.io
            error E {
                Bad
            }
            func f(): std::Int ?! E {
                error E.Bad
                return 0
            }
            func main() {
                try {
                    std::println(f())
                } catch {
                    e -> std::println(e)
                }
            }
        """.trimIndent()))
    }

    @Test fun failableFunctionSucceedsNormally() {
        assertEquals("10", run("""
            import std.io
            error E {
                Bad
            }
            func g(x: std::Int): std::Int ?! E {
                if x < 0 {
                    error E.Bad
                }
                return x * 2
            }
            func main() {
                std::println(g(5))
            }
        """.trimIndent()))
    }

    @Test fun tryExpressionPropagatesFailureToCaller() {
        val program = Parser(Lexer("""
            error E {
                Bad
            }
            func inner(): std::Int ?! E {
                error E.Bad
                return 0
            }
            func outer(): std::Int ?! E {
                return try inner()
            }
            func main() {}
        """.trimIndent()).tokenize())
            .parse()

        val outerAst = program.items.filterIsInstance<TopLevel.Func>().single { it.decl.name == "outer" }
        val returned = outerAst.decl.body.filterIsInstance<Stmt.Return>().single().value
        assertIs<Expr.TryPropagate>(returned)

        val semantic = SemanticPipeline().analyze(program)
        assertTrue(semantic.errors.isEmpty(), semantic.errors.toString())
        val ir = IrGenerator(semantic.symbolTable).generate(semantic.program)
        val outerIr = ir.functions.single { it.name == "outer" }
        val call = outerIr.body.filterIsInstance<IrStmt.Return>().single().value
        assertIs<IrExpr.Call>(call)
        assertEquals("inner", call.name)
    }

    @Test fun catchFallbackExpression() {
        assertEquals("-1\n5", run("""
            import std.io
            error MathError {
                DivByZero
            }
            func divide(a: std::Int, b: std::Int): std::Int ?! MathError {
                if b == 0 {
                    error MathError.DivByZero
                }
                return a / b
            }
            func main() {
                std::println(divide(10, 0) catch -1)
                try {
                    std::println(divide(10, 2))
                } catch {
                    e -> std::println(e)
                }
            }
        """.trimIndent()))
    }

    @Test fun errorVariantAccessibleAsString() {
        assertEquals("NotFound", run("""
            import std.io
            error Lookup {
                NotFound
                OutOfRange
            }
            func main() {
                try {
                    error Lookup.NotFound
                } catch {
                    e -> std::println(e)
                }
            }
        """.trimIndent()))
    }

    @Test fun failDeferRunsOnlyOnError() {
        assertEquals("only on fail\nalways\nBad", run("""
            import std.io
            error E {
                Bad
            }
            func risky(): std::Int ?! E {
                defer { std::println("always") }
                error defer { std::println("only on fail") }
                error E.Bad
                return 0
            }
            func main() {
                try {
                    risky()
                } catch {
                    e -> std::println(e)
                }
            }
        """.trimIndent()))
    }

    @Test fun failDeferSkippedOnNormalReturn() {
        assertEquals("always\n5", run("""
            import std.io
            error E {
                Bad
            }
            func ok(): std::Int ?! E {
                defer { std::println("always") }
                error defer { std::println("only on fail") }
                return 5
            }
            func main() {
                std::println(ok())
            }
        """.trimIndent()))
    }

    @Test fun tFlagEnforcementRejectsWrongErrorSet() {
        // A `T ?! E` function may only fail with errors from set E.
        val result = Compiler().compile("""
            import std.io
            error E {
                Bad
            }
            error Other {
                X
            }
            func bad(): std::Int ?! E {
                error Other.X
                return 0
            }
            func main() {
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result, "Expected compilation to fail due to T ?! E mismatch")
        val errors = (result as CompilationResult.Failure).errors.joinToString()
        assertTrue("'!E'" in errors || "Other" in errors, "Expected a T ?! E enforcement error, got: $errors")
    }

    @Test fun tFlagEnforcementAcceptsMatchingErrorSet() {
        assertEquals("ok", run("""
            import std.io
            error E {
                Bad
            }
            func good(): std::Int ?! E {
                error E.Bad
                return 0
            }
            func main() {
                try {
                    good()
                } catch {
                    e -> std::println("ok")
                }
            }
        """.trimIndent()))
    }

    @Test fun bracketedErrorSetAcceptsEveryDeclaredSet() {
        assertEquals("Q\nS", run("""
            import std.io
            error A { Q W E }
            error B { S D F }

            func choose(first: std::Bool): std::Unit ?! [A, B] {
                if first { error A.Q }
                error B.S
            }

            func main() {
                try { choose(true) } catch { e -> std::println(e) }
                try { choose(false) } catch { e -> std::println(e) }
            }
        """.trimIndent()))
    }

    @Test fun bracketedErrorSetRejectsUndeclaredSet() {
        val result = Compiler().compile("""
            error A { Q }
            error B { S }
            error C { Z }

            func invalid(): std::Unit ?! [A, B] {
                error C.Z
            }
        """.trimIndent())

        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "![A, B]" in it && "C" in it }, result.errors.toString())
    }

    @Test fun duplicateErrorSetInBracketListIsRejected() {
        val result = Compiler().compile("""
            error A { Q }
            func invalid(): std::Unit ?! [A, A] {}
        """.trimIndent())

        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "Duplicate error set" in it }, result.errors.toString())
    }

    @Test fun rescueSuppressesErrorAndContinues() {
        assertEquals("rescued!\nok", run("""
            import std.io
            error E {
                Bad
            }
            func risky() {
                rescue { std::println("rescued!") }
                error E.Bad
            }
            func main() {
                risky()
                std::println("ok")
            }
        """.trimIndent()))
    }
    @Test
    fun anErrorVariantCanCarryAPayload() {
        assertEquals("1\ncaught", run("""
            import std.io

            variant error IndexError {
                OutOfBounds(index: std::Int, size: std::Int)
                Empty
            }

            func at(i: std::Int, n: std::Int): std::Int ?! IndexError {
                if i >= n { return .OutOfBounds(i, n) }
                return i
            }

            func main() {
                fin ok = try at(1, 3)
                std::println(ok)
                try {
                    fin bad = try at(9, 3)
                    std::println(bad)
                } catch { e ->
                    std::println("caught")
                }
            }
        """.trimIndent()))
    }

    @Test
    fun aPayloadVariantIsAlsoConstructibleDirectly() {
        // A `variant error` is a tagged union, which is where construction and
        // `when` matching come from rather than a parallel implementation.
        assertEquals("made", run("""
            import std.io

            variant error IndexError { OutOfBounds(index: std::Int, size: std::Int) }

            func main() {
                fin e = IndexError.OutOfBounds(9, 3)
                std::println("made")
            }
        """.trimIndent()))
    }

}
