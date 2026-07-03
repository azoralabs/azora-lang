package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tier 1 "core frontend polish" features ported from azora-lang-old:
 *
 * - `is! Type` negated type check (desugars to `!(e is Type)`)
 * - do-while: `loop { body } while cond` (desugars to `loop { body; if (!cond) break }`)
 * - null-conditional assignment family:
 *   `?=` (coalescing-assign), `?+=` `?-= `?*=` `?/=` `?%=`, and `?++` `?--`
 *
 * Each desugars to existing IR nodes, so all four backends support them.
 */
class Tier1PolishTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    // -- is! ----------------------------------------------------------------

    @Test fun isNotTrueBranch() {
        assertEquals("yes", run("""
            func main() {
                var x = 5
                var y = "hello"
                if (x is Int && y is! Int) {
                    println("yes")
                }
            }
        """.trimIndent()))
    }

    @Test fun isNotFalseBranch() {
        assertEquals("not int", run("""
            func main() {
                var s = "hello"
                if (s is! Int) {
                    println("not int")
                }
            }
        """.trimIndent()))
    }

    // -- do-while -----------------------------------------------------------

    @Test fun doWhileRepeatsWhileConditionHolds() {
        assertEquals("1\n2\n3", run("""
            func main() {
                var i = 0
                loop {
                    i++
                    println(i)
                } while i < 3
            }
        """.trimIndent()))
    }

    @Test fun doWhileRunsBodyAtLeastOnce() {
        assertEquals("once", run("""
            func main() {
                var i = 10
                loop {
                    println("once")
                } while i < 5
            }
        """.trimIndent()))
    }

    // -- ?= coalescing assignment ------------------------------------------

    @Test fun nullCoalescingAssignSetsWhenNull() {
        assertEquals("10", run("""
            func main() {
                var x: Int? = null
                x ?= 10
                println(x)
            }
        """.trimIndent()))
    }

    @Test fun nullCoalescingAssignKeepsWhenNonNull() {
        assertEquals("5", run("""
            func main() {
                var y: Int? = 5
                y ?= 99
                println(y)
            }
        """.trimIndent()))
    }

    // -- ?+= / ?-= / ?*= / ?/= / ?%= ---------------------------------------

    @Test fun nullConditionalPlusEqualsWhenNonNull() {
        assertEquals("15", run("""
            func main() {
                var x: Int? = 5
                x ?+= 10
                println(x)
            }
        """.trimIndent()))
    }

    @Test fun nullConditionalPlusEqualsWhenNullIsNoOp() {
        assertEquals("null", run("""
            func main() {
                var y: Int? = null
                y ?+= 10
                println(y)
            }
        """.trimIndent()))
    }

    @Test fun nullConditionalCompoundOps() {
        assertEquals("8\n4\n16\n2", run("""
            func main() {
                var a: Int? = 4
                a ?+= 4
                println(a)
                var b: Int? = 8
                b ?-= 4
                println(b)
                var c: Int? = 4
                c ?*= 4
                println(c)
                var d: Int? = 8
                d ?/= 4
                println(d)
            }
        """.trimIndent()))
    }

    // -- ?++ / ?-- ----------------------------------------------------------

    @Test fun nullConditionalIncrementWhenNonNull() {
        assertEquals("6", run("""
            func main() {
                var x: Int? = 5
                x ?++
                println(x)
            }
        """.trimIndent()))
    }

    @Test fun nullConditionalDecrementWhenNullIsNoOp() {
        assertEquals("null", run("""
            func main() {
                var y: Int? = null
                y ?--
                println(y)
            }
        """.trimIndent()))
    }

    // -- raw triple-quoted strings -----------------------------------------

    @Test fun rawStringPreservesNewlines() {
        val src = "func main() {\n    var s = \"\"\"line1\nline2\nline3\"\"\"\n    println(s)\n}\n"
        assertEquals("line1\nline2\nline3", run(src))
    }

    @Test fun rawStringKeepsQuotesAndBackslashesLiteral() {
        val src = "func main() {\n    var s = \"\"\"a \"quote\" and a back\\slash\"\"\"\n    println(s)\n}\n"
        assertEquals("a \"quote\" and a back\\slash", run(src))
    }

    // -- for-step and reverse for ------------------------------------------

    @Test fun forWithStep() {
        assertEquals("0\n2\n4", run("""
            func main() {
                for x by 2 in 0..<6 {
                    println(x)
                }
            }
        """.trimIndent()))
    }

    @Test fun forWithStepInclusive() {
        assertEquals("0\n2\n4\n6\n8\n10", run("""
            func main() {
                for x by 2 in 0..10 {
                    println(x)
                }
            }
        """.trimIndent()))
    }

    @Test fun reverseFor() {
        assertEquals("4\n3\n2\n1", run("""
            func main() {
                reverse for x in 1..4 {
                    println(x)
                }
            }
        """.trimIndent()))
    }

    @Test fun reverseForWithStep() {
        assertEquals("6\n4\n2\n0", run("""
            func main() {
                reverse for x by 2 in 0..6 {
                    println(x)
                }
            }
        """.trimIndent()))
    }

    @Test fun forStepContinueDoesNotSkipIncrement() {
        assertEquals("0\n2\n6\n8\n10", run("""
            func main() {
                for x by 2 in 0..10 {
                    if x == 4 {
                        continue
                    }
                    println(x)
                }
            }
        """.trimIndent()))
    }

    // -- for / while / loop … else -----------------------------------------

    @Test fun whileElseRunsWhenLoopCompletes() {
        assertEquals("done", run("""
            func main() {
                var i = 0
                while i < 3 {
                    i++
                } else {
                    println("done")
                }
            }
        """.trimIndent()))
    }

    @Test fun whileElseSkippedOnBreak() {
        assertEquals("0\n1", run("""
            func main() {
                var i = 0
                while i < 10 {
                    if i == 2 {
                        break
                    }
                    println(i)
                    i++
                } else {
                    println("else")
                }
            }
        """.trimIndent()))
    }

    @Test fun forElseRunsWhenNoBreak() {
        assertEquals("0\n1\n2\nfordone", run("""
            func main() {
                for x in 0..<3 {
                    println(x)
                } else {
                    println("fordone")
                }
            }
        """.trimIndent()))
    }

    @Test fun forElseBreakInsideIfIsCounted() {
        assertEquals("0\n1", run("""
            func main() {
                for x in 0..<5 {
                    if x == 2 {
                        break
                    }
                    println(x)
                } else {
                    println("else")
                }
            }
        """.trimIndent()))
    }

    @Test fun forElseIgnoresBreakInNestedLoop() {
        assertEquals("outer completed", run("""
            func main() {
                for x in 0..<3 {
                    for y in 0..<3 {
                        break
                    }
                } else {
                    println("outer completed")
                }
            }
        """.trimIndent()))
    }

    // -- labeled loops: break @label / continue @label ---------------------

    @Test fun labeledBreakExitsOuterLoop() {
        assertEquals("4", run("""
            func main() {
                var count = 0
                @outer for x in 0..<3 {
                    for y in 0..<3 {
                        if x == 1 && y == 1 {
                            break @outer
                        }
                        count++
                    }
                }
                println(count)
            }
        """.trimIndent()))
    }

    @Test fun labeledContinueSkipsToOuterLoop() {
        assertEquals("10", run("""
            func main() {
                var total = 0
                @outer for x in 0..<4 {
                    for y in 0..<4 {
                        if y > x {
                            continue @outer
                        }
                        total = total + 1
                    }
                }
                println(total)
            }
        """.trimIndent()))
    }

    @Test fun unlabeledBreakAndContinueStillWork() {
        assertEquals("12", run("""
            func main() {
                var i = 0
                var sum = 0
                while i < 10 {
                    i++
                    if i == 3 {
                        continue
                    }
                    if i == 6 {
                        break
                    }
                    sum = sum + i
                }
                println(sum)
            }
        """.trimIndent()))
    }

    // -- infx extension functions ------------------------------------------

    @Test fun infxMethodCalledInfix() {
        assertEquals("15", run("""
            pack Box {
                var v: Int
            }
            infx Box.combine(other: Box): Int {
                return self.v * other.v
            }
            func main() {
                var a = Box(3)
                var b = Box(5)
                println(a combine b)
            }
        """.trimIndent()))
    }

    @Test fun infxMethodReturnsStruct() {
        assertEquals("4\n6", run("""
            pack Vec {
                var x: Int
                var y: Int
            }
            infx Vec.add(other: Vec): Vec {
                return Vec(self.x + other.x, self.y + other.y)
            }
            func main() {
                var a = Vec(1, 2)
                var b = Vec(3, 4)
                var c = a add b
                println(c.x)
                println(c.y)
            }
        """.trimIndent()))
    }

    @Test fun infxMethodAlsoCallableWithDotSyntax() {
        assertEquals("8", run("""
            pack Pair {
                var a: Int
                var b: Int
            }
            infx Pair.merged(other: Pair): Int {
                return self.a + other.b
            }
            func main() {
                var p = Pair(2, 3)
                var q = Pair(5, 6)
                println(p.merged(q))
            }
        """.trimIndent()))
    }

    // -- oper[] index-operator overloading ---------------------------------

    @Test fun operIndexGetAndSet() {
        assertEquals("20\n99", run("""
            pack IntBag {
                var data: [Int]
            }
            impl IntBag {
                oper[](i: Int): Int {
                    return self.data[i]
                }
                oper[]=(i: Int, v: Int) {
                    self.data[i] = v
                }
            }
            func main() {
                var b = IntBag([10, 20, 30])
                println(b[1])
                b[1] = 99
                println(b[1])
            }
        """.trimIndent()))
    }

    // -- `it` type inference for single-arg lambdas ------------------------

    @Test fun itTypeInferredFromExpectedFunctionType() {
        assertEquals("6", run("""
            func apply(f: (Int) -> Int, x: Int): Int {
                return f(x)
            }
            func main() {
                println(apply({ it + 1 }, 5))
            }
        """.trimIndent()))
    }

    @Test fun itInferenceComposes() {
        assertEquals("12", run("""
            func twice(f: (Int) -> Int, x: Int): Int {
                return f(f(x))
            }
            func main() {
                println(twice({ it * 2 }, 3))
            }
        """.trimIndent()))
    }

    // -- real map runtime ---------------------------------------------------

    @Test fun mapLiteralAndAccessWithStringKeys() {
        assertEquals("1\n3\n99", run("""
            func main() {
                var m = ["a": 1, "b": 2, "c": 3]
                println(m["a"])
                println(m["c"])
                m["b"] = 99
                println(m["b"])
            }
        """.trimIndent()))
    }

    @Test fun mapLiteralWithIntKeys() {
        assertEquals("one\ntwo", run("""
            func main() {
                var m = [1: "one", 2: "two"]
                println(m[1])
                println(m[2])
            }
        """.trimIndent()))
    }

    @Test fun mapWithExplicitTypeAnnotation() {
        assertEquals("red", run("""
            func main() {
                var colors: [Int: String] = [1: "red", 2: "green"]
                println(colors[1])
            }
        """.trimIndent()))
    }
}
