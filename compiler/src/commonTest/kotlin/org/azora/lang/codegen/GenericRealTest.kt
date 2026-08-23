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
 * Generic packs instantiated with a floating-point type.
 *
 * A generic pack erases every type parameter to a pointer slot. An `Int` rides
 * in that slot directly, but a `Double` does not fit the same way - it has to
 * travel as its own bit pattern, and the read has to know to convert it back.
 * The type arguments on the referring type are what carry that knowledge.
 */
class GenericRealTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}"
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun llvm(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}"
        )
        return result.llvm
    }

    private val box = """
        pack Box<T> {
            var value: T
        }
    """.trimIndent()

    @Test fun aRealSurvivesAGenericField() {
        assertEquals("2.5", run("""
            import std.io
            $box
            func main() {
                fin b = Box<Double>(2.5)
                println(b.value)
            }
        """.trimIndent()))
    }

    @Test fun aRealFieldCanBeReassigned() {
        assertEquals("7.25", run("""
            import std.io
            $box
            func main() {
                var b = Box<Double>(0.0)
                b.value = 7.25
                println(b.value)
            }
        """.trimIndent()))
    }

    @Test fun theOtherInstantiationsStillWork() {
        assertEquals("42\nok", run("""
            import std.io
            $box
            func main() {
                println(Box<Int>(42).value)
                println(Box<String>("ok").value)
            }
        """.trimIndent()))
    }

    @Test fun eachParameterIsSubstitutedInItsOwnPosition() {
        // Both orders, so a mixed pack cannot pass by substituting positionally
        // from the wrong end.
        assertEquals("1.5 3\n3 1.5", run("""
            import std.io
            pack Pair<A, B> {
                var first: A
                var second: B
            }
            func main() {
                fin p = Pair<Double, Int>(1.5, 3)
                println("${'$'}{p.first} ${'$'}{p.second}")
                fin q = Pair<Int, Double>(3, 1.5)
                println("${'$'}{q.first} ${'$'}{q.second}")
            }
        """.trimIndent()))
    }

    @Test fun aRealCrossesAGenericFunctionBoundary() {
        assertEquals("1.5", run("""
            import std.io
            func<T> identity(value: T): T {
                return value
            }
            func main() {
                println(identity<Double>(1.5))
            }
        """.trimIndent()))
    }

    @Test fun aGenericPackNests() {
        assertEquals("10.0 20.0", run("""
            import std.io
            import std.container.array
            pack Keyframe<T> {
                var time: Double
                var value: T
            }
            func main() {
                var keys: Array<Keyframe<Double>> = Array::fill<Keyframe<Double>>(2)
                keys[0] = Keyframe<Double>(0.0, 10.0)
                keys[1] = Keyframe<Double>(1.0, 20.0)
                fin a: Keyframe<Double> = keys[0]
                fin b: Keyframe<Double> = keys[1]
                println("${'$'}{a.value} ${'$'}{b.value}")
            }
        """.trimIndent()))
    }

    @Test fun theSlotIsErasedAndTheValueConverted() {
        val ir = llvm("""
            $box
            import std.io
            func main() {
                fin b = Box<Double>(2.5)
                println(b.value)
            }
        """.trimIndent())
        assertTrue("%struct.Box = type { i8* }" in ir, "the field must stay an erased slot:\n$ir")
        assertTrue("bitcast double" in ir, "the store must go through the bit pattern:\n$ir")
        assertTrue("inttoptr i64" in ir, "the store must land in the pointer slot:\n$ir")
        assertTrue("ptrtoint i8*" in ir, "the read must come back out of the slot:\n$ir")
    }

    @Test fun anOrdinaryRealFieldIsUntouched() {
        // The un-erasing must not fire for a plain pack, whose slot already has
        // the right type.
        assertEquals("3.5", run("""
            import std.io
            pack Plain {
                var value: Double
            }
            func main() {
                println(Plain(3.5).value)
            }
        """.trimIndent()))
    }
}
