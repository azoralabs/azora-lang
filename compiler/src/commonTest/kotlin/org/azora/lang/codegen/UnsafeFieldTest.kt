package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `unsafe fin data: T*` - a member whose obligation travels with it, so reading
 * it needs a scope that accepts one.
 */
class UnsafeFieldTest {

    private fun errors(source: String): List<String> =
        assertIs<CompilationResult.Failure>(Compiler().compile(source)).errors

    @Test fun anUnsafeMemberIsNotReadableFromSafeCode() {
        val found = errors(
            """
            import std.io
            func main() {
                var xs = @arr[1, 2]
                println(xs.data[0])
            }
            """.trimIndent(),
        )
        assertTrue(
            found.any { "'data' is an unsafe member" in it && "'unsafe'" in it },
            found.toString(),
        )
    }

    @Test fun anUnsafeMemberIsReadableInsideAnUnsafeBlock() {
        val result = Compiler().compile(
            """
            import std.io
            func main() {
                var xs = @arr[1, 2]
                unsafe {
                    println(xs.data[0])
                }
            }
            """.trimIndent(),
        )
        assertIs<CompilationResult.Success>(
            result,
            "an unsafe block must accept it: ${(result as? CompilationResult.Failure)?.errors}",
        )
    }

    @Test fun aSafeMemberBesideItIsUnaffected() {
        val result = Compiler().compile(
            """
            import std.io
            func main() {
                var xs = @arr[1, 2]
                println(xs.size)
            }
            """.trimIndent(),
        )
        assertIs<CompilationResult.Success>(
            result,
            "${(result as? CompilationResult.Failure)?.errors}",
        )
    }
}
