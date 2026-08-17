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
                        react func calculate(): std::Int {
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
            pack Counter { var base: std::Int = 0 }
            impl Counter {
                react prop total[self: std::Self&]: std::Int {
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
            pack Counter { var base: std::Int = 0 }
            impl Counter {
                prop total[self: std::Self&]: std::Int {
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

    @Test fun reactMayOnlyQualifyAMember() {
        val found = errors("""
            pack P { var n: std::Int = 0 }
            impl P {
                react var n: std::Int = 0
            }
        """.trimIndent())
        assertTrue(
            found.any { "after 'react'" in it },
            found.toString(),
        )
    }

    /** A ctor builds, and one that builds by composing is reactive for the same
     * reason a `react func` is - so `react` qualifies it like any other member. */
    @Test fun reactQualifiesACtor() {
        assertEquals("3", run("""
            import std.io

            pack P { var n: std::Int = 0 }
            impl P {
                react ctor[self: Self!](n: std::Int) {
                    self.n = n
                }
            }

            func main() {
                fin p = P(3)
                std::println(p.n)
            }
        """.trimIndent()))
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
            func view(value: std::Int): std::Int = value * 2
            func main() {
                std::println(view(2))
            }
        """.trimIndent()))
    }

    @Test fun everyBindingFormTakesALifetime() {
        assertEquals("10", run("""
            import std.io
            import std.reactive
            pack Settings { var n: std::Int = 0 }
                        react func main() {
                preserve var a = Settings(1)
                preserve val b = Settings(2)
                preserve let c = Settings(3)
                preserve fin d = Settings(4)
                std::println(a.n + b.n + c.n + d.n)
            }
        """.trimIndent()))
    }

    @Test fun reactiveBindingKindsKeepTheirOrdinaryRebindingRules() {
        val failures = errors("""
            react func main() {
                remember fin fixed = 1
                retain let stable = 2
                fixed = 3
                stable = 4
            }
        """.trimIndent())
        assertTrue(failures.any { "cannot reassign immutable binding 'fixed'" in it }, failures.toString())
        assertTrue(failures.any { "cannot reassign immutable binding 'stable'" in it }, failures.toString())
    }

    @Test fun azoraIrPreservesReactiveLifetimeAndBindingKind() {
        val result = Compiler().compile("""
            react func main() {
                remember var a = 1
                retain val b = 2
                preserve let c = 3
                preserve fin d = 4
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
        val ir = result.ir.prettyPrint()
        assertTrue("react func main" in ir, ir)
        assertTrue("remember var a: Int" in ir, ir)
        assertTrue("retain val b: Int" in ir, ir)
        assertTrue("preserve let c: Int" in ir, ir)
        assertTrue("preserve fin d: Int" in ir, ir)
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

    @Test fun lazyFinEvaluatesOnlyOnFirstRead() {
        assertEquals("before\ninit\n42\n42", run("""
            import std.io
            func make(): std::Int {
                std::println("init")
                return 42
            }
            func main() {
                lazy fin answer = make()
                std::println("before")
                std::println(answer)
                std::println(answer)
            }
        """.trimIndent()))
    }

    @Test fun lazyRejectsRebindableBindings() {
        for (binding in listOf("var", "val")) {
            val failures = errors("func main() { lazy $binding value = 1 }")
            assertTrue(failures.any { "requires 'fin' or 'let'" in it }, failures.toString())
        }
    }

    @Test fun rememberedStateSurvivesRepeatedReactiveCalls() {
        assertEquals("1\n2\n3", run("""
            import std.io
            react func counter(): std::Int {
                remember var count = 0
                count = count + 1
                return count
            }
            react func main() {
                std::println(counter())
                std::println(counter())
                std::println(counter())
            }
        """.trimIndent()))
    }

    @Test fun parallelFirstReadsInitializeRememberedStateExactlyOnce() {
        assertEquals("init\n14", run("""
            import std.io
            async func build(): std::Int {
                delay 20
                std::println("init")
                return 7
            }
            react async func read(): std::Int {
                remember fin value = await build()
                return value
            }
            react async func main() {
                fin first = read()
                fin second = read()
                fin a = await first
                fin b = await second
                std::println(a + b)
            }
        """.trimIndent()))
    }

    @Test fun lazyFinInsideReactIsInvalidatedByReactiveReads() {
        assertEquals("2\n6\n6", run("""
            import std.io
            react func main() {
                remember var value = 1
                lazy fin doubled = value * 2
                std::println(doubled)
                value = 3
                std::println(doubled)
                std::println(doubled)
            }
        """.trimIndent()))
    }

    @Test fun explicitEffectCanDependOnLazyDerivedBinding() {
        assertEquals("2\n6", run("""
            import std.io
            react func main() {
                remember var value = 1
                lazy fin doubled = value * 2
                effect doubled { std::println(doubled) }
                value = 3
            }
        """.trimIndent()))
    }

    @Test fun stdStateObservationAndDisposalWork() {
        assertEquals("7:2\n7:2", run("""
            import std.io
            import std.reactive
            func main() {
                var source = std::state(1)
                var latest = 0
                var calls = 0
                var subscription = std::observe(source) [; latest.!, calls.!] { value: std::Int ->
                    latest = value
                    calls += 1
                }
                source.set(7)
                std::println("${'$'}{latest}:${'$'}{calls}")
                subscription.dispose()
                source.set(9)
                std::println("${'$'}{latest}:${'$'}{calls}")
            }
        """.trimIndent()))
    }
}
