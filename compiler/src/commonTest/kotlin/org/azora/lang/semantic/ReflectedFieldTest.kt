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

package org.azora.lang.semantic

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `inline for f in reflect<P>.fields` outside an impl.
 *
 * An editor that draws a component's fields, or a writer that saves them, is
 * written once over the reflected layout rather than once per type - and it is a
 * free function, not a member of the type it reads. It needs each field's name
 * and type as text, the field itself on some value, and the hints its
 * declaration was annotated with.
 */
class ReflectedFieldTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}"
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun aFreeFunctionReadsNamesTypesAndValues() {
        assertEquals("x Int 3\ny Int 4", run("""
            import std.io
            import std.reflection

            pack Point { var x: Int = 3
                var y: Int = 4 }

            func main() {
                fin p = Point()
                inline for f in reflect<Point>.fields {
                    println("${'$'}{f.name} ${'$'}{f.typeName} ${'$'}{p.${'$'}{f.name}}")
                }
            }
        """.trimIndent()))
    }

    @Test fun aFieldAnswersWhatItWasAnnotatedWith() {
        assertEquals("speed true 0 10\nlabel false 0 1", run("""
            import std.io
            import std.reflection

            annot @Range for .Field {
                fin min: Int = 0
                fin max: Int = 1
            }

            pack Settings {
                @Range(max: 10)
                var speed: Int = 2
                var label: String = "run"
            }

            func main() {
                inline for f in reflect<Settings>.fields {
                    println("${'$'}{f.name} ${'$'}{reflect<f>.hasAnnot<Range>} ${'$'}{reflect<f>.annotMeta<Range>.min} ${'$'}{reflect<f>.annotMeta<Range>.max}")
                }
            }
        """.trimIndent()))
    }

    @Test fun aLoopInsideALambdaExpands() {
        assertEquals("x\ny", run("""
            import std.io
            import std.reflection

            pack Point { var x: Int = 3
                var y: Int = 4 }

            func each(body: inline () -> Unit) { body() }

            func main() {
                each {
                    inline for f in reflect<Point>.fields {
                        println(f.name)
                    }
                }
            }
        """.trimIndent()))
    }
}
