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
                var count: Int = 0
            }
            impl Counter {
                func inc[self: Self!](): Int {
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
                var value: Int = 42
            }
            impl Config {
                func get[self: Self!](): Int {
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
                var msg: String = "hello"
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
                var prefix: String
            }
            pack DB {
                var url: String
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
                var prefix: String
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
    fun lazyInjectParsesAndResolves() {
        // `lazy` marks the injection as deferred. Nothing defers yet - both
        // forms resolve eagerly - so this pins the syntax, not the semantics.
        assertEquals("64", run("""
            import std.io
            solo pack Cache { fin size: Int = 64 }
            func main() {
                fin c = lazy inject Cache
                std::println(c.size)
            }
        """.trimIndent()))
    }

    @Test
    fun lazyOnItsOwnIsRejected() {
        val result = Compiler().compile("func main() { fin x = lazy 5 }")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { "'lazy' must be followed by 'inject'" in it },
            result.errors.toString(),
        )
    }

    @Test
    fun aWrapEntryStatesItsLifetime() {
        // The lifetime is the entry's first word, and it is the whole
        // difference between the forms.
        val result = Compiler().compile(
            """
            pack Service { fin url: String = "" }
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
            spec Api { func host[self: Self&](): String }
            pack Config { fin url: String = "" }
            pack HttpClient { fin url: String = "" }
            impl Api for HttpClient { func host[self: Self&](): String { return self.url } }
            pack LoginViewModel { fin tag: String = "" }

            graph NetworkGraph {
                solo Config("https://api")
                solo HttpClient("https://api") bind Api
            }

            graph AppGraph include [NetworkGraph] {
                factory LoginViewModel("transient")
                scope LoginViewModel("per-scope")
            }

            func main() { std::println(inject Config.url) }
        """.trimIndent()))
    }

    /**
     * `include` composes graphs, and is contextual rather than reserved: it is an
     * ordinary word that programs already use as a name - `std/serializer.az`
     * has a local called `include` - and reserving it would take that name from
     * every one of them.
     */
    @Test
    fun includeComposesGraphsWithoutTakingTheNameFromPrograms() {
        assertEquals("kept\n42", run("""
            import std.io
            pack Config { fin url: String = "" }
            graph BaseGraph { solo Config("u") }
            graph AppGraph include BaseGraph { }

            func include(n: Int): Int { return n }

            func main() {
                var include = true
                fin used = if include { "kept" } else { "dropped" }
                std::println(used)
                std::println(include(42))
            }
        """.trimIndent()))
    }
}
