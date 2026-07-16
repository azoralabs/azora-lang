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
            solo Counter {
                var count: Int = 0
                func inc(): Int {
                    self.count = self.count + 1
                    return self.count
                }
            }
            func main() {
                var c1 = inject Counter
                std::io::println(c1.inc())
                std::io::println(c1.inc())
                var c2 = inject Counter
                std::io::println(c2.inc())
            }
        """.trimIndent()))
    }

    @Test fun singletonMethodsCallableViaInject() {
        assertEquals("42", run("""
            import std.io
            solo Config {
                var value: Int = 42
                func get(): Int {
                    return self.value
                }
            }
            func main() {
                std::io::println(inject Config.get())
            }
        """.trimIndent()))
    }

    @Test fun singletonFieldsAccessible() {
        assertEquals("hello", run("""
            import std.io
            solo Greeting {
                var msg: String = "hello"
            }
            func main() {
                var g = inject Greeting
                std::io::println(g.msg)
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
            wrap App {
                solo Logger("APP")
                solo DB("postgres://localhost")
            }
            func main() {
                var l = inject Logger
                std::io::println(l.prefix)
                var d = inject DB
                std::io::println(d.url)
            }
        """.trimIndent()))
    }

    @Test fun wrapSingletonsAreShared() {
        assertEquals("same", run("""
            import std.io
            pack Logger {
                var prefix: String
            }
            wrap App {
                solo Logger("test")
            }
            func main() {
                var a = inject Logger
                var b = inject Logger
                if a == b {
                    std::io::println("same")
                }
            }
        """.trimIndent()))
    }
}
