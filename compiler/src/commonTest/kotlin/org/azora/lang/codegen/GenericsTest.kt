package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse

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
                std::println(identity(5))
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
                std::println(identity("hello"))
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
                std::println(first(10, "hello"))
            }
        """.trimIndent()))
    }

    @Test fun genericStruct() {
        assertEquals("42", run("""
            import std.io
            pack<T> Box {
                var value: T
            }
            func main() {
                var b = Box(42)
                std::println(b.value)
            }
        """.trimIndent()))
    }

    @Test fun genericStructString() {
        assertEquals("hello", run("""
            import std.io
            pack<T> Box {
                var value: T
            }
            func main() {
                var b = Box("hello")
                std::println(b.value)
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
                std::println(x + 1)
            }
        """.trimIndent()))
    }

    @Test fun explicitGenericReturnKeepsConcretePackTypeInLlvm() {
        val result = Compiler().compile(
            """
            import std.io

            pack<T> Store {
                var value: T
            }

            pack Player {
                var health: Int
            }

            func<T> get(store: Store<T>): T {
                return store.value
            }

            func main() {
                var store = Store<Player>(Player(3))
                fin player = get<Player>(store)
                std::println(player.health)
            }
            """.trimIndent(),
            release = false,
        )
        val success = assertIs<CompilationResult.Success>(result)
        assertFalse(
            success.llvm.contains("member .health on Any"),
            "Explicit generic return type was erased before LLVM lowering:\n${success.llvm}",
        )
    }
}
