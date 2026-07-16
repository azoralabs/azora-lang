package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tier 3 — fail-set error model (foundation).
 *
 * `fail ErrSet { … }` declares an error set; `T!ErrSet` annotates a failable return
 * type; `fail ErrSet.Variant` (or `throw`) raises an error; `try/catch` and
 * `expr catch fallback` handle them. Errors propagate via the existing exception
 * machinery, so the IR type of `T!ErrSet` is just `T`.
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
            fail E {
                Bad
            }
            func f(): Int!E {
                fail E.Bad
                return 0
            }
            func main() {
                try {
                    std::io::println(f())
                } catch {
                    e -> std::io::println(e)
                }
            }
        """.trimIndent()))
    }

    @Test fun failableFunctionSucceedsNormally() {
        assertEquals("10", run("""
            import std.io
            fail E {
                Bad
            }
            func g(x: Int): Int!E {
                if x < 0 {
                    fail E.Bad
                }
                return x * 2
            }
            func main() {
                std::io::println(g(5))
            }
        """.trimIndent()))
    }

    @Test fun catchFallbackExpression() {
        assertEquals("-1\n5", run("""
            import std.io
            fail MathError {
                DivByZero
            }
            func divide(a: Int, b: Int): Int!MathError {
                if b == 0 {
                    fail MathError.DivByZero
                }
                return a / b
            }
            func main() {
                std::io::println(divide(10, 0) catch -1)
                try {
                    std::io::println(divide(10, 2))
                } catch {
                    e -> std::io::println(e)
                }
            }
        """.trimIndent()))
    }

    @Test fun errorVariantAccessibleAsString() {
        assertEquals("NotFound", run("""
            import std.io
            fail Lookup {
                NotFound
                OutOfRange
            }
            func main() {
                try {
                    fail Lookup.NotFound
                } catch {
                    e -> std::io::println(e)
                }
            }
        """.trimIndent()))
    }

    @Test fun failDeferRunsOnlyOnError() {
        assertEquals("only on fail\nalways\nBad", run("""
            import std.io
            fail E {
                Bad
            }
            func risky(): Int!E {
                defer { std::io::println("always") }
                fail defer { std::io::println("only on fail") }
                fail E.Bad
                return 0
            }
            func main() {
                try {
                    risky()
                } catch {
                    e -> std::io::println(e)
                }
            }
        """.trimIndent()))
    }

    @Test fun failDeferSkippedOnNormalReturn() {
        assertEquals("always\n5", run("""
            import std.io
            fail E {
                Bad
            }
            func ok(): Int!E {
                defer { std::io::println("always") }
                fail defer { std::io::println("only on fail") }
                return 5
            }
            func main() {
                std::io::println(ok())
            }
        """.trimIndent()))
    }

    @Test fun tFlagEnforcementRejectsWrongErrorSet() {
        // A `T!E` function may only fail with errors from set E.
        val result = Compiler().compile("""
            import std.io
            fail E {
                Bad
            }
            fail Other {
                X
            }
            func bad(): Int!E {
                fail Other.X
                return 0
            }
            func main() {
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result, "Expected compilation to fail due to T!E mismatch")
        val errors = (result as CompilationResult.Failure).errors.joinToString()
        assertTrue("'!E'" in errors || "Other" in errors, "Expected a T!E enforcement error, got: $errors")
    }

    @Test fun tFlagEnforcementAcceptsMatchingErrorSet() {
        assertEquals("ok", run("""
            import std.io
            fail E {
                Bad
            }
            func good(): Int!E {
                fail E.Bad
                return 0
            }
            func main() {
                try {
                    good()
                } catch {
                    e -> std::io::println("ok")
                }
            }
        """.trimIndent()))
    }

    @Test fun rescueSuppressesErrorAndContinues() {
        assertEquals("rescued!\nok", run("""
            import std.io
            fail E {
                Bad
            }
            func risky() {
                rescue { std::io::println("rescued!") }
                fail E.Bad
            }
            func main() {
                risky()
                std::io::println("ok")
            }
        """.trimIndent()))
    }
}
