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

    // -- `<=>` on the built-in types ---------------------------------------

    @Test fun spaceshipOnIntegersAnswersCompare() {
        assertEquals("Compare.Less\nCompare.Equal\nCompare.Greater", run("""
            import std.io
            import std.traits
            func main() {
                std::println("${'$'}{1 <=> 2}")
                std::println("${'$'}{2 <=> 2}")
                std::println("${'$'}{3 <=> 2}")
            }
        """.trimIndent()))
    }

    @Test fun spaceshipOnStringsAnswersCompare() {
        assertEquals("Compare.Less\nCompare.Equal", run("""
            import std.io
            import std.traits
            func main() {
                std::println("${'$'}{"a" <=> "b"}")
                std::println("${'$'}{"a" <=> "a"}")
            }
        """.trimIndent()))
    }

    /** Floating point is partially ordered, so its `<=>` answers the partial type. */
    @Test fun spaceshipOnDoublesAnswersPartialCompare() {
        assertEquals("PartialCompare.Less\nPartialCompare.Equal\nPartialCompare.Greater", run("""
            import std.io
            import std.traits
            func main() {
                std::println("${'$'}{1.0 <=> 2.0}")
                std::println("${'$'}{2.0 <=> 2.0}")
                std::println("${'$'}{3.0 <=> 2.0}")
            }
        """.trimIndent()))
    }

    // -- Phase 6: derivation -----------------------------------------------

    /** A bodyless `impl [Equal]` asks the compiler to write `==` field-wise. */
    @Test fun equalIsDerivedFieldWise() {
        assertEquals("true\nfalse\nfalse\ntrue", run("""
            import std.io
            import std.traits
            pack Point {
                var x: Int
                var y: Int
            }
            impl [Equal] for Point
            func main() {
                std::println(Point(1, 2) == Point(1, 2))
                std::println(Point(1, 2) == Point(9, 2))
                std::println(Point(1, 2) != Point(1, 2))
                std::println(Point(1, 2) != Point(1, 9))
            }
        """.trimIndent()))
    }

    /** `impl [Order]` gives `<=>`, and the four relational operators follow. */
    @Test fun orderIsDerivedLexicographically() {
        assertEquals("Compare.Less\ntrue\nfalse\ntrue", run("""
            import std.io
            import std.traits
            pack Version {
                var major: Int
                var minor: Int
            }
            impl [Order] for Version
            func main() {
                std::println("${'$'}{Version(1, 2) <=> Version(1, 9)}")
                std::println(Version(1, 2) < Version(1, 9))
                std::println(Version(2, 0) < Version(1, 9))
                std::println(Version(2, 0) >= Version(2, 0))
            }
        """.trimIndent()))
    }

    /** Later fields only decide when the earlier ones tie. */
    @Test fun derivedOrderComparesFieldsInDeclarationOrder() {
        assertEquals("Compare.Greater\nCompare.Less", run("""
            import std.io
            import std.traits
            pack Version {
                var major: Int
                var minor: Int
            }
            impl [Order] for Version
            func main() {
                std::println("${'$'}{Version(2, 0) <=> Version(1, 99)}")
                std::println("${'$'}{Version(1, 1) <=> Version(1, 2)}")
            }
        """.trimIndent()))
    }

    /** Deriving `Equal` derives `Hash` too, so the two can never disagree. */
    @Test fun equalAlsoDerivesHash() {
        assertEquals("true\nfalse", run("""
            import std.io
            import std.traits
            pack Key {
                var a: Int
                var b: Int
            }
            impl [Equal] for Key
            func main() {
                std::println(Key(1, 2).hash == Key(1, 2).hash)
                std::println(Key(1, 2).hash == Key(2, 1).hash)
            }
        """.trimIndent()))
    }

    /** An author's own member always wins over the derived one. */
    @Test fun aWrittenOperatorBeatsTheDerivedOne() {
        assertEquals("true", run("""
            import std.io
            import std.traits
            pack Loose {
                var a: Int
                var b: Int
            }
            impl [Equal] for Loose
            impl Equal for Loose {
                oper== [self: Self&](rhs: Self&): Bool {
                    return self.a == rhs.a
                }
            }
            func main() {
                std::println(Loose(1, 2) == Loose(1, 999))
            }
        """.trimIndent()))
    }

    // -- §8: `==` without a conformance is an error ------------------------

    /**
     * The defect the DIP was written for: this used to compile and answer
     * differently on each backend - structurally in the interpreter, by address
     * in LLVM.
     */
    @Test fun comparingAPackWithNoEqualityIsAnError() {
        val result = Compiler().compile("""
            import std.io
            pack Vec2 {
                var x: Int
                var y: Int
            }
            func main() {
                std::println(Vec2(1, 2) == Vec2(1, 2))
            }
        """.trimIndent(), release = false)
        assertIs<CompilationResult.Failure>(result, "a pack with no equality must not compare")
        assertTrue(
            result.errors.any { "does not implement PartialEqual" in it && "impl [Equal]" in it },
            "the error should name the fix, got: ${result.errors}",
        )
    }

    @Test fun statingEqualMakesItCompare() {
        assertEquals("true", run("""
            import std.io
            import std.traits
            pack Vec2 {
                var x: Int
                var y: Int
            }
            impl [Equal] for Vec2
            func main() {
                std::println(Vec2(1, 2) == Vec2(1, 2))
            }
        """.trimIndent()))
    }

    /** Enums, primitives and null comparisons are untouched by the rule. */
    @Test fun theEqualityRuleLeavesEverythingElseAlone() {
        assertEquals("true\ntrue\ntrue", run("""
            import std.io
            enum Colour {
                Red
                Green
            }
            pack Boxed {
                var v: Int
            }
            func main() {
                std::println(Colour.Red == Colour.Red)
                std::println(1 == 1)
                fin maybe: Boxed? = null
                std::println(maybe == null)
            }
        """.trimIndent()))
    }

    /**
     * A spec that declares only operators still registers a conformance, so
     * another spec can require it. Before this, `impl Deref<Int> for Box` was
     * skipped whole - the guard meant for `impl oper== by Map for HashMap`,
     * where the name is an operand type rather than a spec, caught every
     * operator impl including the real conformances.
     */
    @Test fun anOperatorOnlySpecRegistersItsConformance() {
        assertEquals("7", run("""
            import std.io
            import std.traits
            pack Box { var v: Int = 0 }
            impl Deref<Int> for Box {
                oper.* [self: Self&]: Int { return self.v }
            }
            impl DerefMut<Int> for Box {
                oper.^ [self: Self!]: Int { return self.v }
            }
            func main() {
                var b = Box(7)
                std::println(b.*)
            }
        """.trimIndent()))
    }

    @Test fun derefMutRequiresDeref() {
        val result = Compiler().compile("""
            import std.traits
            pack Box { var v: Int = 0 }
            impl DerefMut<Int> for Box {
                oper.^ [self: Self!]: Int { return self.v }
            }
            func main() {}
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { "cannot implement 'DerefMut' until it also implements 'Deref'" in it },
            result.errors.toString(),
        )
    }
}
