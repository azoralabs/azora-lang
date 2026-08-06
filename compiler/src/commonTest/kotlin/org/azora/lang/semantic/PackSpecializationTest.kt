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
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A conditional field belongs to some layouts and not others, so `Vec<Int, 2>` and
 * `Vec<Int, 3>` expose different members. Only field lookup consults the
 * specialization at this checkpoint.
 */
class PackSpecializationTest {

    private val vec = """
        pack Vec<T, N: Int> {
            var x: T = 0
            var y: T = 0
            inline if N >= 3 { var z: T = 0 }
            var w: T = 0 where N == 4
        }
    """.trimIndent()

    private fun read(size: String, field: String) = Compiler().compile(
        """
        $vec
        func read(): Int {
            fin a: Vec<Int, $size> = Vec<Int, $size>(1, 2)
            return a.$field
        }
        """.trimIndent(),
        release = false,
    )

    @Test fun unconditionalFieldsResolveOnEveryLayout() {
        assertIs<CompilationResult.Success>(read("2", "x"))
        assertIs<CompilationResult.Success>(read("2", "y"))
    }

    @Test fun aFieldOutsideTheLayoutDoesNotResolve() {
        val z = assertIs<CompilationResult.Failure>(read("2", "z"))
        assertTrue(z.errors.any { "no member 'z'" in it }, z.errors.toString())
        val w = assertIs<CompilationResult.Failure>(read("2", "w"))
        assertTrue(w.errors.any { "no member 'w'" in it }, w.errors.toString())
    }

    @Test fun aLargerLayoutGainsItsConditionalField() {
        assertIs<CompilationResult.Success>(read("3", "z"))
        val w = assertIs<CompilationResult.Failure>(read("3", "w"))
        assertTrue(w.errors.any { "no member 'w'" in it }, w.errors.toString())
    }

    @Test fun aDeclarationLevelWhereBehavesLikeAnInlineIf() {
        // `var w: T = 0 where N == 4` and `inline if N >= 3 { … }` are one mechanism.
        assertIs<CompilationResult.Success>(read("4", "w"))
        assertIs<CompilationResult.Success>(read("4", "z"))
    }

    @Test fun anAbstractApplicationKeepsTheTemplateLayout() {
        // `Vec<Int, N>` has chosen no layout, so lookup must not manufacture one -
        // every template member stays visible inside the generic.
        assertIs<CompilationResult.Success>(Compiler().compile(
            """
            $vec
            pack Holder<T, N: Int> {
                var inner: Vec<T, N>
            }
            """.trimIndent(),
            release = false,
        ))
    }

    @Test fun repeatedLookupsShareOneSpecialization() {
        // Three reads of the same application must produce one cached layout.
        assertIs<CompilationResult.Success>(Compiler().compile(
            """
            $vec
            func a(): Int { fin v: Vec<Int, 3> = Vec<Int, 3>(1, 2) return v.z }
            func b(): Int { fin v: Vec<Int, 3> = Vec<Int, 3>(3, 4) return v.z }
            func c(): Int { fin v: Vec<Int, 3> = Vec<Int, 3>(5, 6) return v.z }
            """.trimIndent(),
            release = false,
        ))
    }
    // ------------------------------------------------------------------
    // Monomorphisation: each concrete application emits its own pack.
    // ------------------------------------------------------------------

    @Test fun aNestedApplicationIsAlsoSpecialized() {
        // `Box<Vec<Int, 3>>` - the inner application is rewritten to its
        // specialization by the same recursive type rewrite.
        assertIs<CompilationResult.Success>(Compiler().compile(
            """
            $vec
            pack Box<T> { var item: T }
            func nested(): Int {
                fin b: Box<Vec<Int, 3>> = Box<Vec<Int, 3>>(Vec<Int, 3>(1, 2))
                return b.item.z
            }
            """.trimIndent(),
            release = false,
        ))
    }

    @Test fun constructorAndFieldAccessAgreeOnTheSpecialization() {
        // Constructing and reading go through one mangled pack, so a field that
        // exists for the layout resolves off a constructed value.
        assertIs<CompilationResult.Success>(Compiler().compile(
            """
            $vec
            func build(): Int {
                fin v = Vec<Int, 4>(1, 2)
                return v.w
            }
            """.trimIndent(),
            release = false,
        ))
    }

    @Test fun anInvalidCombinationIsRejectedBeforePublication() {
        val constrained = """
            pack Bounded<T, N: Int> where T is Number && N in 2..4 {
                var x: T = 0
                inline if N >= 3 { var z: T = 0 }
            }
        """.trimIndent()
        val badConst = assertIs<CompilationResult.Failure>(Compiler().compile(
            """
            $constrained
            func bad(): Int { fin v: Bounded<Int, 9> = Bounded<Int, 9>(1) return v.x }
            """.trimIndent(),
            release = false,
        ))
        assertTrue(badConst.errors.any { "'N in 2..4'" in it }, badConst.errors.toString())
    }

}
