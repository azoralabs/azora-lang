package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `isEmpty` and `isNotEmpty` are properties on every builtin collection, so the
 * call form is refused - the same rule a declared `prop` already follows.
 */
class PropCallTest {

    private fun errors(source: String): List<String> =
        assertIs<CompilationResult.Failure>(Compiler().compile(source)).errors

    private fun refusesCallForm(build: String, name: String) {
        val found = errors(
            """
            import std.io
            func main() {
                var x = $build
                println(x.$name())
            }
            """.trimIndent(),
        )
        assertTrue(
            found.any { "property '$name' must be accessed without parentheses" in it },
            "$build .$name() -> $found",
        )
    }

    @Test fun anArrayEmptinessPropertyIsNotCallable() {
        refusesCallForm("@arr[1]", "isEmpty")
        refusesCallForm("@arr[1]", "isNotEmpty")
    }

}
