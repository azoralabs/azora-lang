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
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The four binding keywords vary two independent axes:
 *
 * |         | rebind the name | mutate the value | `&` | `!` |
 * |---------|-----------------|------------------|-----|-----|
 * | `var`   | yes             | yes              | yes | yes |
 * | `let`   | no              | yes              | yes | yes |
 * | `val`   | yes             | no               | yes | no  |
 * | `fin`   | no              | no               | yes | no  |
 *
 * All four work in a runtime scope and in a compile-time (`inline`) scope.
 */
class BindingMutabilityTest {
    private fun compile(source: String): CompilationResult = Compiler().compile(source.trimIndent())

    private fun accepts(source: String) {
        assertIs<CompilationResult.Success>(compile(source))
    }

    private fun rejects(source: String, needle: String) {
        val failure = assertIs<CompilationResult.Failure>(compile(source))
        assertTrue(failure.errors.any { needle in it }, failure.errors.toString())
    }

    private val point = """
        pack Point {
            var x: Int
            var y: Int
        }
    """.trimIndent()

    // -- rebinding the name --------------------------------------------------

    @Test fun varRebinds() = accepts("func main() { var x = 1\nx = 2 }")

    @Test fun valRebinds() = accepts("func main() { val x = 1\nx = 2 }")

    @Test fun letDoesNotRebind() =
        rejects("func main() { let x = 1\nx = 2 }", "cannot reassign immutable binding 'x'")

    @Test fun finDoesNotRebind() =
        rejects("func main() { fin x = 1\nx = 2 }", "cannot reassign immutable binding 'x'")

    // -- mutating the value --------------------------------------------------

    @Test fun varMutatesItsValue() = accepts("""
        $point
        func main() {
            var p = Point(1, 2)
            p.x = 10
        }
    """)

    @Test fun letMutatesItsValue() = accepts("""
        $point
        func main() {
            let p = Point(1, 2)
            p.x = 10
        }
    """)

    @Test fun valDoesNotMutateItsValue() = rejects("""
        $point
        func main() {
            val p = Point(1, 2)
            p.x = 10
        }
    """, "cannot assign to member 'x' through 'p'")

    @Test fun finDoesNotMutateItsValue() = rejects("""
        $point
        func main() {
            fin p = Point(1, 2)
            p.x = 10
        }
    """, "cannot assign to member 'x' through 'p'")

    @Test fun immutabilityReachesThroughAWholeAccessChain() = rejects("""
        pack Inner { var v: Int }
        pack Outer { var inner: Inner }
        func main() {
            fin o = Outer(Inner(1))
            o.inner.v = 2
        }
    """, "cannot assign to member 'v' through 'o'")

    @Test fun indexAssignmentFollowsTheValueAxis() = rejects("""
        func main() {
            val xs = arr@[1, 2, 3]
            xs[0] = 9
        }
    """, "cannot assign by index through 'xs'")

    // -- borrows -------------------------------------------------------------

    private val borrowers = """
        func read(n: Int&): Int { return n }
        func bump(n: Int!) { n = n + 1 }
    """.trimIndent()

    @Test fun everyBindingSupportsASharedBorrow() = accepts("""
        $borrowers
        func main() {
            var a = 1
            let b = 1
            val c = 1
            fin d = 1
            read(a&)
            read(b&)
            read(c&)
            read(d&)
        }
    """)

    @Test fun varSupportsAMutableBorrow() = accepts("""
        $borrowers
        func main() {
            var a = 1
            bump(a!)
        }
    """)

    @Test fun letSupportsAMutableBorrow() = accepts("""
        $borrowers
        func main() {
            let b = 1
            bump(b!)
        }
    """)

    @Test fun valDoesNotSupportAMutableBorrow() = rejects("""
        $borrowers
        func main() {
            val c = 1
            bump(c!)
        }
    """, "cannot borrow mutably for parameter 'n' through 'c'")

    @Test fun finDoesNotSupportAMutableBorrow() = rejects("""
        $borrowers
        func main() {
            fin d = 1
            bump(d!)
        }
    """, "cannot borrow mutably for parameter 'n' through 'd'")

    // -- compile-time scope --------------------------------------------------

    @Test fun allFourBindingsWorkInACompileTimeScope() = accepts("""
        inline var first = 1
        inline let second = 2
        inline val third = 3
        inline fin fourth = 4
        func total(): Int {
            return first + second + third + fourth
        }
    """)

    // A global is shared, and what makes sharing unsafe is that the name can be
    // rebound — so `val` is excluded from top level for the same reason as `var`.
    @Test fun aTopLevelValIsRejectedLikeATopLevelVar() = rejects("""
        val counter: Int = 0
        func main() {
            counter = 1
        }
    """, "top-level 'val' is not allowed")
}
