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

/**
 * Named and positional arguments mix freely, and a line break separates arguments
 * the same way a comma does. Everything true of a constructor is true of a call.
 */
class CallArgumentTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    private val decls = """
        import std.io
        pack Size { var width: Int  var height: Int }
        func area(width: Int, height: Int): Int { return width * height }
    """.trimIndent()

    private fun sized(arguments: String) = run(
        """
        $decls
        func main() {
            fin s = Size($arguments)
            println(s.width)
            println(s.height)
            println(area($arguments))
        }
        """.trimIndent()
    )

    @Test fun positional() = assertEquals("2\n3\n6", sized("2, 3"))
    @Test fun allNamed() = assertEquals("2\n3\n6", sized("width: 2, height: 3"))
    @Test fun namedThenPositional() = assertEquals("2\n3\n6", sized("width: 2, 3"))
    @Test fun positionalThenNamed() = assertEquals("2\n3\n6", sized("2, height: 3"))
    @Test fun namedOutOfOrder() = assertEquals("2\n3\n6", sized("height: 3, width: 2"))

    @Test fun aLineBreakSeparatesArguments() {
        // No comma: the newline is the separator, which is what lets a long call be
        // written a line per argument.
        assertEquals("2\n3", run("""
            $decls
            func main() {
                fin s = Size(
                    2
                    3
                )
                println(s.width)
                println(s.height)
            }
        """.trimIndent()))
    }

    @Test fun aLineBreakSeparatesNamedArguments() {
        assertEquals("2\n3", run("""
            $decls
            func main() {
                fin s = Size(
                    height: 3
                    width: 2
                )
                println(s.width)
                println(s.height)
            }
        """.trimIndent()))
    }

    @Test fun aLineBreakSeparatesMixedArguments() {
        assertEquals("2\n3\n2\n3", run("""
            $decls
            func main() {
                fin a = Size(
                    width: 2
                    3
                )
                fin b = Size(
                    2
                    height: 3
                )
                println(a.width)
                println(a.height)
                println(b.width)
                println(b.height)
            }
        """.trimIndent()))
    }
}
