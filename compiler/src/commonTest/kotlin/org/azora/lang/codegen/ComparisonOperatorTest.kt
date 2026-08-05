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
 * `DIPs/OPERATOR_OVERLOADING_DIP.MD` end to end: a type states `<=>` once and
 * `==` once, and the other five comparison operators are rewrites.
 */
class ComparisonOperatorTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    private val version = """
        import std.io
        import std.traits
        pack Version {
            var major: Int
            var minor: Int
        }
        oper<=> [self: Version&](rhs: Version&): Compare {
            if self.major < rhs.major { return Compare.Less }
            if self.major > rhs.major { return Compare.Greater }
            if self.minor < rhs.minor { return Compare.Less }
            if self.minor > rhs.minor { return Compare.Greater }
            return Compare.Equal
        }
    """.trimIndent()

    @Test fun spaceshipItselfReturnsTheEnum() {
        assertEquals("Compare.Less\nCompare.Equal\nCompare.Greater", run("""
            $version
            func main() {
                std::println("${'$'}{Version(1, 0) <=> Version(2, 0)}")
                std::println("${'$'}{Version(1, 5) <=> Version(1, 5)}")
                std::println("${'$'}{Version(2, 0) <=> Version(1, 9)}")
            }
        """.trimIndent()))
    }

    /** The whole point: four operators the type never declared. */
    @Test fun relationalOperatorsComeFromSpaceship() {
        assertEquals("true\nfalse\ntrue\nfalse", run("""
            $version
            func main() {
                std::println(Version(1, 0) < Version(1, 1))
                std::println(Version(1, 0) > Version(1, 1))
                std::println(Version(1, 0) <= Version(1, 0))
                std::println(Version(2, 0) <= Version(1, 0))
            }
        """.trimIndent()))
    }

    @Test fun greaterOrEqualComesFromSpaceship() {
        assertEquals("true\ntrue\nfalse", run("""
            $version
            func main() {
                std::println(Version(2, 0) >= Version(1, 0))
                std::println(Version(2, 0) >= Version(2, 0))
                std::println(Version(1, 0) >= Version(2, 0))
            }
        """.trimIndent()))
    }

    /** `!=` is `!(a == b)`; no type declares both. */
    @Test fun notEqualsComesFromEquals() {
        assertEquals("true\nfalse\nfalse\ntrue", run("""
            import std.io
            import std.traits
            pack Point {
                var x: Int
                var y: Int
            }
            oper== [self: Point&](rhs: Point&): Bool {
                return self.x == rhs.x && self.y == rhs.y
            }
            func main() {
                std::println(Point(1, 2) == Point(1, 2))
                std::println(Point(1, 2) == Point(1, 3))
                std::println(Point(1, 2) != Point(1, 2))
                std::println(Point(1, 2) != Point(1, 3))
            }
        """.trimIndent()))
    }

    /** An explicitly declared operator wins over the rewrite. */
    @Test fun explicitOperatorBeatsTheRewrite() {
        assertEquals("always", run("""
            import std.io
            import std.traits
            pack Odd {
                var v: Int
            }
            oper<=> [self: Odd&](rhs: Odd&): Compare {
                return Compare.Equal
            }
            oper< [self: Odd&](rhs: Odd&): Bool {
                return true
            }
            func main() {
                if Odd(1) < Odd(1) { std::println("always") } else { std::println("rewritten") }
            }
        """.trimIndent()))
    }

    /**
     * A `PartialCompare` makes **all four** relational operators false for an
     * unordered pair. A `Compare`-shaped rewrite would have made `<=` true.
     */
    @Test fun unorderedMakesEveryRelationalOperatorFalse() {
        assertEquals("false\nfalse\nfalse\nfalse", run("""
            import std.io
            import std.traits
            pack Maybe {
                var v: Int
                var known: Bool
            }
            oper<=> [self: Maybe&](rhs: Maybe&): PartialCompare {
                if !self.known || !rhs.known { return PartialCompare.Unordered }
                if self.v < rhs.v { return PartialCompare.Less }
                if self.v > rhs.v { return PartialCompare.Greater }
                return PartialCompare.Equal
            }
            func main() {
                fin a = Maybe(1, true)
                fin unknown = Maybe(0, false)
                std::println(a < unknown)
                std::println(a <= unknown)
                std::println(a > unknown)
                std::println(a >= unknown)
            }
        """.trimIndent()))
    }

    @Test fun partialOrderStillComparesKnownValues() {
        assertEquals("true\ntrue\nfalse", run("""
            import std.io
            import std.traits
            pack Maybe {
                var v: Int
                var known: Bool
            }
            oper<=> [self: Maybe&](rhs: Maybe&): PartialCompare {
                if !self.known || !rhs.known { return PartialCompare.Unordered }
                if self.v < rhs.v { return PartialCompare.Less }
                if self.v > rhs.v { return PartialCompare.Greater }
                return PartialCompare.Equal
            }
            func main() {
                std::println(Maybe(1, true) < Maybe(2, true))
                std::println(Maybe(2, true) <= Maybe(2, true))
                std::println(Maybe(3, true) < Maybe(2, true))
            }
        """.trimIndent()))
    }

    /**
     * `<=>` is called **once** per comparison.
     *
     * `a <= b` on a partial order asks two questions of one answer
     * (`isLess || isEqual`). Inlining the call at each question would run the
     * body twice, which the single `cmp` line here rules out.
     */
    @Test fun spaceshipIsEvaluatedOncePerComparison() {
        assertEquals("cmp\ntrue", run("""
            import std.io
            import std.traits
            pack Counted {
                var v: Int
            }
            oper<=> [self: Counted&](rhs: Counted&): PartialCompare {
                std::println("cmp")
                if self.v < rhs.v { return PartialCompare.Less }
                if self.v > rhs.v { return PartialCompare.Greater }
                return PartialCompare.Equal
            }
            func main() {
                std::println(Counted(1) <= Counted(2))
            }
        """.trimIndent()))
    }

    // -- The Compare/PartialCompare members --------------------------------

    @Test fun compareReversedFlipsLessAndGreater() {
        assertEquals("Compare.Greater\nCompare.Less\nCompare.Equal", run("""
            import std.io
            import std.traits
            func main() {
                std::println("${'$'}{Compare.Less.reversed}")
                std::println("${'$'}{Compare.Greater.reversed}")
                std::println("${'$'}{Compare.Equal.reversed}")
            }
        """.trimIndent()))
    }

    @Test fun thenChainsLexicographically() {
        assertEquals("Compare.Less\nCompare.Greater", run("""
            import std.io
            import std.traits
            func main() {
                std::println("${'$'}{Compare.Equal.then(Compare.Less)}")
                std::println("${'$'}{Compare.Greater.then(Compare.Less)}")
            }
        """.trimIndent()))
    }

    @Test fun partialCompareUnorderedIsDecisiveInAChain() {
        assertEquals("PartialCompare.Unordered", run("""
            import std.io
            import std.traits
            func main() {
                std::println("${'$'}{PartialCompare.Unordered.then(PartialCompare.Less)}")
            }
        """.trimIndent()))
    }
}
