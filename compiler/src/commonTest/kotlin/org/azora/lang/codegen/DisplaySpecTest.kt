/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `Display`, `DIPs/OPERATOR_OVERLOADING_DIP.MD` §12.7.
 *
 * `"${value}"` calls `Display` and nothing else, so a type that has not said
 * how it prints does not print. Before this, interpolating a pack emitted the
 * backend's value representation - in the interpreter, the field map itself,
 * so `"${Vec2(1, 2)}"` produced `{__type=Vec2, x=1, y=2}` and a pack's private
 * layout appeared in program output.
 */
class DisplaySpecTest {
    private fun compile(source: String) = Compiler().compile(source, release = false)

    private fun run(source: String): String {
        val result = compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun interpolatingAPackWithNoDisplayIsAnError() {
        val result = compile("""
            import std.io
            pack Vec2 {
                var x: Int
                var y: Int
            }
            func main() {
                println("${'$'}{Vec2(1, 2)}")
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result, "a pack with no Display must not interpolate")
        assertTrue(
            result.errors.any { "does not implement Display" in it },
            "the error should name the fix, got: ${result.errors}",
        )
    }

    /**
     * An erased `T` carries no type when the program runs, so only a `Display`
     * bound can say how to show it. Unbounded, it printed an address on LLVM
     * and the interpreter's internal map (`{__type=P, n=3}`).
     */
    @Test fun showingAnUnboundedTypeParameterIsAnError() {
        val sources = listOf(
            "func<T> show(x: T): String { return \"<${'$'}{x}>\" }",
            "func<T> show(x: T): String { println(x)\n return \"\" }",
            "func<T> show(xs: Array<T>): String { return \"${'$'}xs\" }",
            "func<T> show(x: T?): String { return \"${'$'}x\" }",
            "func<T: Order> show(x: T): String { return \"${'$'}x\" }",
        )
        for (declaration in sources) {
            val result = compile("import std.io\nimport std.traits\n$declaration\nfunc main() {}")
            assertIs<CompilationResult.Failure>(result, "an unbounded T must not be shown: $declaration")
            assertTrue(
                result.errors.any { "'T' is not bounded by 'Display'; write 'where T: Display'" in it },
                "the error should name the fix, got: ${result.errors}",
            )
        }
    }

    /**
     * A function value has no text. Printed or interpolated, the interpreter
     * showed its Kotlin object, LLVM `<value>` or a raw address, and
     * WebAssembly a table index - three outputs for one program.
     */
    @Test fun showingAFunctionValueIsAnError() {
        val bodies = listOf(
            "println(f)",
            "println(\"f is ${'$'}f\")",
            "println([f, f])",
            "println((1, f))",
        )
        for (body in bodies) {
            val result = compile("import std.io\nfunc main() {\n    var n = 1\n    n = n + 1\n    fin f = [n] { x: Int -> x + n }\n    $body\n}")
            assertIs<CompilationResult.Failure>(result, "a function value must not be shown: $body")
            assertTrue(
                result.errors.any { "a function value - a function has no text; call it and show what it returns" in it },
                "the error should name the fix, got: ${result.errors}",
            )
        }
    }

    @Test fun aDisplayBoundedTypeParameterShowsItself() {
        assertEquals("<42> <hi> <true> [1, 2]", run("""
            import std.io
            import std.format
            func<T: Display> show(x: T): String { return "<${'$'}{x}>" }
            func<T: Display> all(xs: Array<T>): String { return "${'$'}xs" }
            func main() {
                println(show(42) + " " + show("hi") + " " + show(true) + " " + all([1, 2]))
            }
        """.trimIndent()))
    }

    @Test fun interpolationGoesThroughDisplay() {
        assertEquals("Vec2(1, 2)", run("""
            import std.io
            import std.format
            pack Vec2 {
                var x: Int
                var y: Int
            }
            impl Display for Vec2 {
                func &.display(formatter: Formatter!) {
                    formatter.write("Vec2(")
                    formatter.write("${'$'}{self.x}")
                    formatter.write(", ")
                    formatter.write("${'$'}{self.y}")
                    formatter.write(")")
                }
            }
            func main() {
                println("${'$'}{Vec2(1, 2)}")
            }
        """.trimIndent()))
    }

    /** One buffer for the whole render, not one string per part. */
    @Test fun aCompositeRendersThroughOneFormatter() {
        assertEquals("[Vec2(1, 2)]", run("""
            import std.io
            import std.format
            pack Vec2 {
                var x: Int
                var y: Int
            }
            pack Wrapper {
                var inner: Vec2
            }
            impl Display for Vec2 {
                func &.display(formatter: Formatter!) {
                    formatter.write("Vec2(")
                    formatter.write("${'$'}{self.x}")
                    formatter.write(", ")
                    formatter.write("${'$'}{self.y}")
                    formatter.write(")")
                }
            }
            impl Display for Wrapper {
                func &.display(formatter: Formatter!) {
                    formatter.write("[")
                    self.inner.display(formatter)
                    formatter.write("]")
                }
            }
            func main() {
                println("${'$'}{Wrapper(Vec2(1, 2))}")
            }
        """.trimIndent()))
    }

    /** Everything that already formatted itself is untouched. */
    @Test fun theDisplayRuleLeavesEverythingElseAlone() {
        assertEquals("1\ntrue\nx\nColour.Red", run("""
            import std.io
            enum Colour {
                Red
                Green
            }
            func main() {
                println("${'$'}{1}")
                println("${'$'}{true}")
                println("${'$'}{"x"}")
                println("${'$'}{Colour.Red}")
            }
        """.trimIndent()))
    }

    /** `format` is the one-shot form of the same thing. */
    @Test fun formatRendersThroughDisplay() {
        assertEquals("Vec2(3, 4)", run("""
            import std.io
            import std.format
            pack Vec2 {
                var x: Int
                var y: Int
            }
            impl Display for Vec2 {
                func &.display(formatter: Formatter!) {
                    formatter.write("Vec2(")
                    formatter.write("${'$'}{self.x}")
                    formatter.write(", ")
                    formatter.write("${'$'}{self.y}")
                    formatter.write(")")
                }
            }
            func main() {
                println(format(Vec2(3, 4)))
            }
        """.trimIndent()))
    }
}
