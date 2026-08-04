package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/** Rendering-independent `@Reactive` state and effect semantics. */
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
            @Reactive
            func calculate(): Int {
                mem a = 1
                rem b = 2
                ret c = 3
                return a + b + c
            }
            @Reactive
            func main() {
                std::println(calculate())
            }
        """.trimIndent()))
    }

    @Test fun automaticEffectRunsInitiallyAndAfterChanges() {
        assertEquals("1\n7", run("""
            import std.io
            @Reactive
            func observe() {
                rem value = 1
                effect {
                    std::println(value)
                }
                value = 7
            }
            @Reactive
            func main() {
                observe()
            }
        """.trimIndent()))
    }

    @Test fun automaticEffectTracksOnlyReactiveValuesReadByItsBody() {
        assertEquals("1\n2", run("""
            import std.io
            @Reactive
            func observe() {
                rem observed = 1
                rem unrelated = 10
                effect {
                    std::println(observed)
                }
                unrelated = 11
                observed = 2
            }
            @Reactive
            func main() {
                observe()
            }
        """.trimIndent()))
    }

    @Test fun explicitSingleAndListDependenciesAreSelective() {
        assertEquals("11\n11\n12\n22\n22", run("""
            import std.io
            @Reactive
            func observe() {
                rem x = 1
                rem y = 1
                effect x { std::println(x * 10 + y) }
                effect [x, y] { std::println(x * 10 + y) }
                y = 2
                x = 2
            }
            @Reactive
            func main() {
                observe()
            }
        """.trimIndent()))
    }

    @Test fun deferredEffectRunsOnOwnerExit() {
        assertEquals("cleanup", run("""
            import std.io
            @Reactive
            func work() {
                effect defer {
                    std::println("cleanup")
                }
            }
            @Reactive
            func main() {
                work()
            }
        """.trimIndent()))
    }

    @Test fun reactiveKeywordsRequireDeclaredReactiveOwner() {
        val failures = errors("""
            func main() {
                rem value = 1
                effect { value = 2 }
            }
        """.trimIndent())
        assertTrue(failures.any { "@Reactive" in it })
    }

    @Test fun reactiveFunctionsRequireReactiveCallers() {
        val failures = errors("""
            @Reactive
            func stateOwner() { rem value = 1 }
            func main() {
                stateOwner()
            }
        """.trimIndent())
        assertTrue(failures.any { "can only be called from an @Reactive" in it })
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
}
