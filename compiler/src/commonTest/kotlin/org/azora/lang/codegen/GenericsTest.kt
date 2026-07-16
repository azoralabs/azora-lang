package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GenericsTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun genericIdentity() {
        assertEquals("5", run("""
            import std.io
            func<T> identity(x: T): T {
                return x
            }
            func main() {
                std::io::println(identity(5))
            }
        """.trimIndent()))
    }

    @Test fun genericIdentityString() {
        assertEquals("hello", run("""
            import std.io
            func<T> identity(x: T): T {
                return x
            }
            func main() {
                std::io::println(identity("hello"))
            }
        """.trimIndent()))
    }

    @Test fun genericTwoParams() {
        assertEquals("10", run("""
            import std.io
            func<T, U> first(a: T, b: U): T {
                return a
            }
            func main() {
                std::io::println(first(10, "hello"))
            }
        """.trimIndent()))
    }

    @Test fun genericStruct() {
        assertEquals("42", run("""
            import std.io
            pack Box<T> {
                var value: T
            }
            func main() {
                var b = Box(42)
                std::io::println(b.value)
            }
        """.trimIndent()))
    }

    @Test fun genericStructString() {
        assertEquals("hello", run("""
            import std.io
            pack Box<T> {
                var value: T
            }
            func main() {
                var b = Box("hello")
                std::io::println(b.value)
            }
        """.trimIndent()))
    }

    @Test fun genericUsedInArithmetic() {
        assertEquals("43", run("""
            import std.io
            func<T> identity(x: T): T {
                return x
            }
            func main() {
                var x = identity(42)
                std::io::println(x + 1)
            }
        """.trimIndent()))
    }
}
