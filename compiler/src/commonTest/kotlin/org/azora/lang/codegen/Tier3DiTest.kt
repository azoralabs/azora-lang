package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for DI: `solo` singletons + `inject` resolution.
 */
class Tier3DiTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun singletonIsSharedAcrossInjects() {
        // `inject` returns the SAME singleton instance every time.
        assertEquals("1\n2\n3", run("""
            import std.io
            solo pack Counter {
                var count: std::Int = 0
            }
            impl Counter {
                func inc[self: std::Self!](): std::Int {
                    self.count = self.count + 1
                    return self.count
                }
            }
            func main() {
                var c1 = inject Counter
                std::println(c1.inc())
                std::println(c1.inc())
                var c2 = inject Counter
                std::println(c2.inc())
            }
        """.trimIndent()))
    }

    @Test fun singletonMethodsCallableViaInject() {
        assertEquals("42", run("""
            import std.io
            solo pack Config {
                var value: std::Int = 42
            }
            impl Config {
                func get[self: std::Self!](): std::Int {
                    return self.value
                }
            }
            func main() {
                std::println(inject Config.get())
            }
        """.trimIndent()))
    }

    @Test fun singletonFieldsAccessible() {
        assertEquals("hello", run("""
            import std.io
            solo pack Greeting {
                var msg: std::String = "hello"
            }
            func main() {
                var g = inject Greeting
                std::println(g.msg)
            }
        """.trimIndent()))
    }

    @Test fun wrapContainerConstructsSingletonsWithArgs() {
        assertEquals("APP\npostgres://localhost", run("""
            import std.io
            pack Logger {
                var prefix: std::String
            }
            pack DB {
                var url: std::String
            }
            graph App {
                solo Logger("APP")
                solo DB("postgres://localhost")
            }
            func main() {
                var l = inject Logger
                std::println(l.prefix)
                var d = inject DB
                std::println(d.url)
            }
        """.trimIndent()))
    }

    @Test fun wrapSingletonsAreShared() {
        assertEquals("same", run("""
            import std.io
            pack Logger {
                var prefix: std::String
            }
            graph App {
                solo Logger("test")
            }
            func main() {
                var a = inject Logger
                var b = inject Logger
                // Sharing is observed through the value, not asserted with `==`:
                // two injections of a `solo` are one object, so a write through
                // one is visible through the other. Comparing them with `==`
                // would have asked whether `Logger` is equal to itself, which is
                // a different question and one the pack never answered.
                a.prefix = "written through a"
                if b.prefix == "written through a" {
                    std::println("same")
                }
            }
        """.trimIndent()))
    }

    @Test
    fun lazyBindingCanDeferInjection() {
        assertEquals("64", run("""
            import std.io
            solo pack Cache { fin size: std::Int = 64 }
            func main() {
                lazy fin c = inject Cache
                std::println(c.size)
            }
        """.trimIndent()))
    }

    @Test
    fun oldLazyInjectExpressionIsRejectedWithMigrationHelp() {
        val result = Compiler().compile("func main() { fin x = lazy inject Cache }")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { "'lazy' modifies a binding" in it },
            result.errors.toString(),
        )
    }

    @Test
    fun aWrapEntryStatesItsLifetime() {
        // The lifetime is the entry's first word, and it is the whole
        // difference between the forms.
        val result = Compiler().compile(
            """
            pack Service { fin url: std::String = "" }
            graph AppGraph { Service("https://x") }
            func main() {}
            """.trimIndent(),
        )
        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { "starts with its lifetime" in it },
            result.errors.toString(),
        )
    }

    @Test
    fun everyProviderLifetimeAndGraphCompositionParse() {
        assertEquals("https://api", run("""
            import std.io
            spec Api { func host[self: std::Self&](): std::String }
            pack Config { fin url: std::String = "" }
            pack HttpClient { fin url: std::String = "" }
            impl Api for HttpClient { func host[self: std::Self&](): std::String { return self.url } }
            pack LoginViewModel { fin tag: std::String = "" }

            graph NetworkGraph {
                solo Config("https://api")
                solo HttpClient("https://api") binds Api
            }

            graph AppGraph includes [NetworkGraph] {
                factory LoginViewModel("transient")
                scope LoginViewModel("per-scope")
            }

            func main() { std::println(inject Config.url) }
        """.trimIndent()))
    }

    /**
     * `includes` composes graphs, and is contextual rather than reserved: it is an
     * ordinary word that programs already use as a name - `std/serializer.az`
     * has a local called `includes` - and reserving it would take that name from
     * every one of them.
     */
    @Test
    fun includeComposesGraphsWithoutTakingTheNameFromPrograms() {
        assertEquals("kept\n42", run("""
            import std.io
            pack Config { fin url: std::String = "" }
            graph BaseGraph { solo Config("u") }
            graph AppGraph includes BaseGraph { }

            func includes(n: std::Int): std::Int { return n }

            func main() {
                var includes = true
                fin used = if includes { "kept" } else { "dropped" }
                std::println(used)
                std::println(includes(42))
            }
        """.trimIndent()))
    }
}
