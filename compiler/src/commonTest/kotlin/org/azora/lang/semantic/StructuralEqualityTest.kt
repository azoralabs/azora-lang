package org.azora.lang.semantic

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.ir.IrProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A tuple or an array has no `==` or `hash` of its own: it compares and hashes
 * part by part, each part by its own. Before this, `(P(1), 2) == (P(1), 2)`
 * compiled for a `P` with no equality while `P(1) == P(1)` did not, the
 * interpreter compared the `P`s field by field even when `P` declared its own
 * `oper==`, and LLVM refused tuple equality outright. `2.5.hash` compiled
 * although no floating-point type is `Hash`.
 */
class StructuralEqualityTest {
    companion object {
        /** A declared `oper==` decides inside a tuple as outside one, at any depth. */
        val customEquality = """
            import std.io
            pack P { var v: Int }
            impl P { oper== &.(rhs: P&): Bool = self.v % 2 == rhs.v % 2 }
            func main() {
                println(P(1) == P(3))
                println((P(1), 2) == (P(3), 2))
                println((P(1), 2) == (P(2), 2))
                println((P(1), 2) != (P(2), 2))
                println(((1, P(1)), 5) == ((1, P(3)), 5))
            }
        """.trimIndent()

        /** Hashing in order, part by part; arrays compare element by element and by length. */
        val hashingAndArrays = """
            import std.io
            import std.traits
            pack K { var a: Int }
            derive Equal for K
            derive Hash for K
            func main() {
                println((1, 2).hash == (1, 2).hash)
                println((1, 2).hash == (2, 1).hash)
                println((K(1), "x").hash == (K(1), "x").hash)
                fin a = [1, 2, 3]
                fin b = [1, 2, 3]
                fin c = [1, 2, 4]
                println(a == b)
                println(a == c)
                println(a != c)
                println(a.hash == b.hash)
                println(a.hash == c.hash)
                fin u: Array<Int> = [1, 2, 3]
                fin v: Array<Int> = [1, 2]
                println(u == v)
                println([K(1), K(2)] == [K(1), K(2)])
            }
        """.trimIndent()

        /** A tuple as a hashed set's element: the set's witness compares and hashes it part by part. */
        val tupleKeys = """
            import std.io
            import std.container.set
            func main() {
                var s = HashSet<(Int, Int)>()
                s.add((1, 2))
                s.add((1, 2))
                s.add((2, 1))
                println(s.size)
                println(s.contains((2, 1)))
                println(s.contains((3, 3)))
            }
        """.trimIndent()

        val programs = listOf(
            customEquality to "true\ntrue\nfalse\ntrue\ntrue",
            hashingAndArrays to "true\nfalse\ntrue\ntrue\nfalse\ntrue\ntrue\nfalse\nfalse\ntrue",
            tupleKeys to "2\ntrue\nfalse",
        )

        fun compile(source: String, optimized: Boolean): IrProgram {
            val result = Compiler().compile(source, release = optimized)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            return if (optimized) result.optimizedIr else result.ir
        }

        private fun rejected(source: String): List<String> =
            assertIs<CompilationResult.Failure>(Compiler().compile(source.trimIndent()), "the program compiled").errors
    }

    @Test fun tuplesAndArraysCompareAndHashByTheirParts() {
        for ((source, expected) in programs) for (optimized in listOf(false, true)) {
            assertEquals(expected, IrInterpreter().interpret(compile(source, optimized)).trim(), "optimized=$optimized\n$source")
        }
    }

    @Test fun aPartWithoutEqualityMakesTheWholeIncomparable() {
        val errors = rejected("""
            import std.io
            pack P { var v: Int }
            func main() {
                println((P(1), 2) == (P(1), 2))
                println([P(1)] == [P(1)])
                println(((1, P(1)), 2) != ((1, P(1)), 2))
            }
        """)
        for (line in 4..6) {
            assertTrue(
                errors.any { it.startsWith("line $line: cannot compare two '") && "its part 'P' does not implement PartialEqual" in it },
                "line $line in $errors",
            )
        }
    }

    @Test fun onlyWhatIsHashCanBeHashed() {
        val errors = rejected("""
            import std.io
            import std.traits
            pack P { var v: Int }
            func main() {
                println((P(1), 2).hash)
                println([P(1)].hash)
                println(2.5.hash)
            }
        """)
        assertTrue(errors.any { it.startsWith("line 5: cannot hash a '(P, Int)' value - (P, Int) does not implement Hash: P does not implement Hash") }, errors.toString())
        assertTrue(errors.any { it.startsWith("line 6: cannot hash a 'Array<P, 1>' value - Array<P> does not implement Hash") }, errors.toString())
        assertTrue(
            errors.any { it.startsWith("line 7: cannot hash a '") && "NaN is not equal to itself" in it },
            errors.toString(),
        )
    }

    @Test fun anArrayIsBoundedPartByPartAndHasNoOrder() {
        val accepted = Compiler().compile("""
            import std.container.set
            func main() {
                var s = HashSet<Array<Int>>()
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(accepted, (accepted as? CompilationResult.Failure)?.errors.toString())
        val errors = rejected("""
            import std.container.set
            func main() {
                var s = TreeSet<Array<Int>>()
            }
        """)
        assertTrue(errors.any { "'TreeSet<Array<Int>>' does not satisfy its 'where' clause: 'T is Order': Array<Int> does not implement Order" in it }, errors.toString())
    }

    @Test fun noOrderIsDerivedThroughATupleOrAnArray() {
        for (field in listOf("var t: (Int, Int)", "var xs: Array<Int>")) {
            val errors = rejected("""
                import std.traits
                pack P { $field }
                derive Equal for P
                derive Order for P
                func main() {}
            """)
            assertTrue(errors.any { "cannot derive 'Order' for 'P'" in it && "is not Order" in it }, "$field: $errors")
        }
    }
}
