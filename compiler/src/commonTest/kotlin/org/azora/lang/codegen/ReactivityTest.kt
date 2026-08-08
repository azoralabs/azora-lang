package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/** Rendering-independent `react` state and effect semantics. */
class ReactivityTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun errors(source: String): List<String> {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Failure>(result)
        return result.errors
    }

    @Test fun reactiveStateKindsAreAvailableInsideReactiveFunctions() {
        assertEquals("6", run("""
            import std.io
                        react func calculate(): Int {
                preserve var a = 1
                remember var b = 2
                retain var c = 3
                return a + b + c
            }
                        react func main() {
                std::println(calculate())
            }
        """.trimIndent()))
    }

    @Test fun aReactivePropertyOwnsReactiveState() {
        assertEquals("7", run("""
            import std.io
            pack Counter { var base: Int = 0 }
            impl Counter {
                react prop total[self: Self&]: Int {
                    remember var value = 1
                    var observed = 0
                    effect { observed = value }
                    value = 7
                    return observed
                }
            }
            react func main() {
                fin c = Counter(0)
                std::println(c.total)
            }
        """.trimIndent()))
    }

    @Test fun anOrdinaryPropertyMayNotOwnReactiveState() {
        val found = errors("""
            pack Counter { var base: Int = 0 }
            impl Counter {
                prop total[self: Self&]: Int {
                    remember var value = 1
                    return value
                }
            }
            func main() { }
        """.trimIndent())
        assertTrue(
            found.any { "'remember' requires a 'react func' or 'react async func'" in it },
            found.toString(),
        )
    }

    @Test fun reactMayOnlyQualifyAFunctionOrProperty() {
        val found = errors("""
            pack P { var n: Int = 0 }
            impl P {
                react ctor() { }
            }
        """.trimIndent())
        assertTrue(
            found.any { "Expected 'prop', 'func', 'async prop', or 'async func' after 'react'" in it },
            found.toString(),
        )
    }

    @Test fun automaticEffectRunsInitiallyAndAfterChanges() {
        assertEquals("1\n7", run("""
            import std.io
                        react func observe() {
                remember var value = 1
                effect {
                    std::println(value)
                }
                value = 7
            }
                        react func main() {
                observe()
            }
        """.trimIndent()))
    }

    @Test fun automaticEffectTracksOnlyReactiveValuesReadByItsBody() {
        assertEquals("1\n2", run("""
            import std.io
                        react func observe() {
                remember var observed = 1
                remember var unrelated = 10
                effect {
                    std::println(observed)
                }
                unrelated = 11
                observed = 2
            }
                        react func main() {
                observe()
            }
        """.trimIndent()))
    }

    @Test fun explicitSingleAndListDependenciesAreSelective() {
        assertEquals("11\n11\n12\n22\n22", run("""
            import std.io
                        react func observe() {
                remember var x = 1
                remember var y = 1
                effect x { std::println(x * 10 + y) }
                effect [x, y] { std::println(x * 10 + y) }
                y = 2
                x = 2
            }
                        react func main() {
                observe()
            }
        """.trimIndent()))
    }

    @Test fun deferredEffectRunsOnOwnerExit() {
        assertEquals("cleanup", run("""
            import std.io
                        react func work() {
                effect defer {
                    std::println("cleanup")
                }
            }
                        react func main() {
                work()
            }
        """.trimIndent()))
    }

    @Test fun reactiveKeywordsRequireDeclaredReactiveOwner() {
        val failures = errors("""
            func main() {
                remember var value = 1
                effect { value = 2 }
            }
        """.trimIndent())
        assertTrue(failures.any { "requires a 'react func'" in it }, failures.toString())
    }

    @Test fun reactiveFunctionsRequireReactiveCallers() {
        val failures = errors("""
            react func stateOwner() { remember var value = 1 }
            func main() {
                stateOwner()
            }
        """.trimIndent())
        assertTrue(
            failures.any { "can only be called from a 'react func'" in it },
            failures.toString(),
        )
    }

    @Test fun viewIsAnOrdinaryIdentifierNotAKeyword() {
        assertEquals("4", run("""
            import std.io
            func view(value: Int): Int = value * 2
            func main() {
                std::println(view(2))
            }
        """.trimIndent()))
    }

    @Test fun everyBindingFormTakesALifetime() {
        assertEquals("10", run("""
            import std.io
            import std.reactive
            pack Settings { var n: Int = 0 }
                        react func main() {
                preserve var a = Settings(1)
                preserve val b = Settings(2)
                preserve let c = Settings(3)
                preserve fin d = Settings(4)
                std::println(a.n + b.n + c.n + d.n)
            }
        """.trimIndent()))
    }

    @Test fun theLifetimesAreALadder() {
        assertEquals("6", run("""
            import std.io
            import std.reactive
                        react func main() {
                remember var a = 1
                retain var b = 2
                preserve var c = 3
                std::println(a + b + c)
            }
        """.trimIndent()))
    }

    @Test fun aLifetimeNeedsABindingForm() {
        val result = Compiler().compile(
            """
            import std.reactive
                        react func main() { remember x = 1 }
            """.trimIndent(),
        )
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "needs a binding form" in it }, result.errors.toString())
    }

    @Test fun aLifetimeCannotBeCombinedWithThreadlocal() {
        // Both say where a value lives, in ways that do not compose. Rejected
        // whichever order they are written in.
        for (source in listOf(
            "threadlocal preserve var x = 1\nfunc main() {}",
            "import std.reactive\nreact func main() { preserve threadlocal var x = 1 }",
        )) {
            val result = Compiler().compile(source)
            assertIs<CompilationResult.Failure>(result)
            assertTrue(
                result.errors.any { "cannot be combined with" in it },
                result.errors.toString(),
            )
        }
    }
}
