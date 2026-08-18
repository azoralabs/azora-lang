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
 * `Into` and `From`: semantic conversion, as against the cast operators.
 *
 * `Into` converts a value it has; `From` constructs one it does not, so its
 * member is a static and the two cannot be the same spec.
 */
class ConvertSpecTest {
    private fun compile(source: String) = Compiler().compile(source, release = false)

    private fun run(source: String): String {
        val result = compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    /** `From` builds a value, so its member is reached through the type. */
    @Test fun fromIsAStaticReachedThroughTheType() {
        assertEquals("gabriel", run("""
            import std.io
            import std.convert
            pack Username {
                value: String
            }
            impl From<String> for Username:: {
                func from(value: String): Self {
                    return Username(value: value)
                }
            }
            func main() {
                fin username: Username = Username::from("gabriel")
                println(username.value)
            }
        """.trimIndent()))
    }

    /** `Into` converts a value it already has, through its receiver. */
    @Test fun intoConvertsThroughItsReceiver() {
        assertEquals("Label(ok)", run("""
            import std.io
            import std.convert
            pack Label {
                var value: String
            }
            impl Into<String> for Label {
                prop into[self: Self&]: String {
                    return "Label(" + self.value + ")"
                }
            }
            func main() {
                var label = Label("ok")
                println(label.toString)
            }
        """.trimIndent()))
    }

    /**
     * The in-brace receiver the bracket redesign replaced is rejected, and the
     * message names what to write instead.
     */
    @Test fun inBraceReceiverOnASpecImplIsRejected() {
        val result = compile("""
            import std.io
            import std.convert
            pack Label {
                var value: String
            }
            impl Into<String> for Label { self& ->
                return self.value
            }
            func main() {}
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result, "the in-brace receiver must not compile")
        assertTrue(
            result.errors.any { "in brackets" in it },
            "the error should name the replacement, got: ${result.errors}",
        )
    }
}
