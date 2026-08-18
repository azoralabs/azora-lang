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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end `lli` tests for the LLVM backend's aggregate lowering:
 * struct (pack) construction / member access / member assignment, nested
 * structs, impl methods, operator overloading, and heap arrays with an i64
 * length header (literal, index read/write, `.size`).
 *
 * These paths back the Azora game engine, which compiles natively via LLVM.
 * Tests run both the debug (raw IR) and release (optimized IR) pipelines.
 */
class LlvmAggregateExecTest {

    private fun check(expected: String, source: String) {
        if (!LlvmExec.available) return
        assertEquals(expected, LlvmExec.run(source), "debug IR")
        assertEquals(expected, LlvmExec.run(source, optimized = true), "optimized IR")
    }

    // -----------------------------------------------------------------------
    // Structs (pack)
    // -----------------------------------------------------------------------

    @Test fun structConstructionAndMemberRead() = check(
        "1.5\n2.5",
        """
        import std.io
        pack Vec2 {
            var x: Double
            var y: Double
        }
        func main() {
            fin v = Vec2(1.5, 2.5)
            println(v.x)
            println(v.y)
        }
        """.trimIndent()
    )

    @Test fun structMemberAssignment() = check(
        "7",
        """
        import std.io
        pack Point {
            var x: Int
            var y: Int
        }
        func main() {
            var p = Point(1, 2)
            p.x = 7
            println(p.x)
        }
        """.trimIndent()
    )

    @Test fun nestedStructMemberAssignment() = check(
        "1.5\n90.0",
        """
        import std.io
        pack Vec3 {
            var x: Double
            var y: Double
            var z: Double
        }
        pack Camera {
            var pos: Vec3
            var yaw: Double
        }
        func main() {
            var cam = Camera(Vec3(0.0, 1.0, 5.0), 0.0)
            cam.pos.y = cam.pos.y + 0.5
            cam.yaw = 90.0
            println(cam.pos.y)
            println(cam.yaw)
        }
        """.trimIndent()
    )

    @Test fun structReturnedFromFunction() = check(
        "5",
        """
        import std.io
        pack Point {
            var x: Int
            var y: Int
        }
        func make(): Point {
            return Point(5, 6)
        }
        func main() {
            fin p = make()
            println(p.x)
        }
        """.trimIndent()
    )

    @Test fun implMethodWithImplicitSelf() = check(
        "38.0",
        """
        import std.io
        pack Vec3 {
            var x: Double
            var y: Double
            var z: Double
        }
        impl Vec3 {
            func lengthSq(): Double {
                return self.x * self.x + self.y * self.y + self.z * self.z
            }
        }
        func main() {
            fin v = Vec3(5.0, 2.0, 3.0)
            println(v.lengthSq())
        }
        """.trimIndent()
    )

    @Test fun operatorOverloadingOnStructs() = check(
        "22.0",
        """
        import std.io
        pack Vec2 {
            var x: Double
            var y: Double
        }
        impl Vec2 {
            func plus(o: Vec2): Vec2 {
                return Vec2(self.x + o.x, self.y + o.y)
            }
        }
        func main() {
            fin c = Vec2(1.0, 2.0) + Vec2(10.0, 20.0)
            println(c.y)
        }
        """.trimIndent()
    )

    // -----------------------------------------------------------------------
    // Arrays
    // -----------------------------------------------------------------------

    @Test fun arrayLiteralIndexAndLength() = check(
        "3\n4",
        """
        import std.io
        func main() {
            fin x = @arr[1, 2, 3, 4]
            println(x[2])
            println(x.size)
        }
        """.trimIndent()
    )

    @Test fun arrayIndexAssignment() = check(
        "9",
        """
        import std.io
        func main() {
            var x = @arr[1, 2, 3]
            x[1] = 9
            println(x[1])
        }
        """.trimIndent()
    )

    @Test fun subscriptOnASpecTypedValueDispatches() = check(
        "20",
        """
        import std.io
        import std.container.list
        func pick(xs: List<Int>&, i: Int): Int {
            return xs[i]
        }
        func main() {
            var xs = arrayListOf<Int>()
            xs.add(10)
            xs.add(20)
            println(pick(xs, 1))
        }
        """.trimIndent()
    )

    @Test fun arrayForEachSum() = check(
        "10",
        """
        import std.io
        func main() {
            var nums = @arr[1, 2, 3, 4]
            var sum = 0
            for n in nums {
                sum = sum + n
            }
            println(sum)
        }
        """.trimIndent()
    )

    @Test fun arrayForEachStrings() = check(
        "red\ngreen\nblue",
        """
        import std.io
        func main() {
            var names = @arr["red", "green", "blue"]
            for name in names {
                println(name)
            }
        }
        """.trimIndent()
    )

    @Test fun arrayAddUpdatesLengthAndStoresElement() = check(
        "4\n40",
        """
        import std.io
        func main() {
            var x = @arr[10, 20, 30]
            x.add(40)
            println(x.size)
            println(x[3])
        }
        """.trimIndent()
    )

    @Test fun arrayAddEvaluatesArgumentBeforeGrowth() = check(
        "3",
        """
        import std.io
        func main() {
            var x = @arr[10, 20, 30]
            x.add(x.size)
            println(x[3])
        }
        """.trimIndent()
    )

    @Test fun arrayAddWritesGrownBufferBackToPackField() = check(
        "3\n30",
        """
        import std.io
        pack Bucket {
            var values: Array<Int>
        }
        func main() {
            var bucket = Bucket(@arr[10, 20])
            bucket.values.add(30)
            println(bucket.values.size)
            println(bucket.values[2])
        }
        """.trimIndent()
    )

    /** `isEmpty` and `isNotEmpty` are properties, so they are read without parentheses. */
    @Test fun arrayEmptyProperties() = check(
        "false\ntrue",
        """
        import std.io
        func main() {
            var x = @arr[1]
            println(x.isEmpty)
            println(x.isNotEmpty)
        }
        """.trimIndent()
    )

    @Test fun arrayContainsScalarsAndStrings() = check(
        "true\nfalse\ntrue\nfalse",
        """
        import std.io
        func main() {
            var nums = @arr[1, 2, 3]
            var words = ["one", "two"]
            println(nums.contains(2))
            println(nums.contains(9))
            println(words.contains("two" + ""))
            println(words.contains("three"))
        }
        """.trimIndent()
    )

    /**
     * `remove` on an array takes an *index*, not a value - the split the
     * interpreter makes, which the native lowering has to make too. An index
     * outside the array removes nothing rather than trapping.
     */
    @Test fun arrayRemoveDropsByIndexAndIgnoresOutOfRange() = check(
        "10\n30\n40\n3\n3",
        """
        import std.io
        func main() {
            var xs = @arr[10, 20, 30, 40]
            xs.remove(1)
            var i = 0
            while i < xs.size {
                println(xs[i])
                i = i + 1
            }
            println(xs.size)
            xs.remove(99)
            println(xs.size)
        }
        """.trimIndent()
    )

    /**
     * `for [a, b] in rows` binds each name to the row element at its position,
     * so the header says what a row is made of and the body never indexes it.
     */
    @Test fun forInDestructuresATupleRow() = check(
        "1 a\n2 b",
        """
        import std.io
        import std.container.tuple
        func main() {
            fin rows = @arr[tupleOf(1, "a"), tupleOf(2, "b")]
            for [n, s] in rows {
                println("${'$'}{n} ${'$'}{s}")
            }
        }
        """.trimIndent()
    )

    @Test fun mapLiteralReadsStringKeys() = check(
        "1\n3",
        """
        import std.io
        func main() {
            var values = ["a": 1, "b": 2, "c": 3]
            println(values["a"])
            println(values["c"])
        }
        """.trimIndent()
    )

    @Test fun mapStringKeysCompareContents() = check(
        "2",
        """
        import std.io
        func main() {
            var values = ["ab": 2, "cd": 4]
            let key = "a" + "b"
            println(values[key])
        }
        """.trimIndent()
    )

    @Test fun mapLiteralReadsIntegerKeys() = check(
        "one\ntwo",
        """
        import std.io
        func main() {
            var values = @map[1: "one", 2: "two"]
            println(values[1])
            println(values[2])
        }
        """.trimIndent()
    )

    @Test fun mapUpdatesExistingEntry() = check(
        "99",
        """
        import std.io
        func main() {
            var values = ["a": 1, "b": 2]
            values["b"] = 99
            println(values["b"])
        }
        """.trimIndent()
    )

    @Test fun mapInsertsMissingEntry() = check(
        "3\n30",
        """
        import std.io
        func main() {
            var values = @map[1: 10, 2: 20]
            values[3] = 30
            println(values[3] / 10)
            println(values[3])
        }
        """.trimIndent()
    )

    @Test fun mapSupportsRealValues() = check(
        "2.5",
        """
        import std.io
        func main() {
            var values = ["pi": 3.14, "half": 0.5]
            values["half"] = 2.5
            println(values["half"])
        }
        """.trimIndent()
    )

    @Test fun mapLengthAndEmptyProperties() = check(
        "2\nfalse\ntrue\n0",
        """
        import std.io
        import std.container.map
        func main() {
            var values = @map!["a": 1, "b": 2]
            println(values.size)
            println(values.isEmpty)
            values.clear()
            println(values.isEmpty)
            println(values.size)
        }
        """.trimIndent()
    )

    @Test fun mapGetPutAndContainsKey() = check(
        "true\nfalse\n20\ntrue\n30\n3",
        """
        import std.io
        func main() {
            var values = ["a": 10, "b": 20]
            println(values.containsKey("a"))
            println(values.containsKey("z"))
            println(values.get("b"))
            values.put("c", 30)
            println(values.containsKey("c"))
            println(values.get("c"))
            println(values.size)
        }
        """.trimIndent()
    )

    @Test fun globalArrayInitializerRunsBeforeMain() = check(
        "6",
        """
        import std.io
        fin values = @arr[1, 2, 3]
        func main() {
            println(values[0] + values[1] + values[2])
        }
        """.trimIndent()
    )

    @Test fun globalMapInitializerRunsBeforeMain() = check(
        "42",
        """
        import std.io
        fin values = ["answer": 42]
        func main() {
            println(values["answer"])
        }
        """.trimIndent()
    )

    @Test fun setLiteralDeduplicatesElements() = check(
        "3\ntrue\nfalse",
        """
        import std.io
        func main() {
            var values = ![1, 2, 2, 3, 1]
            println(values.size)
            println(values.contains(2))
            println(values.contains(9))
        }
        """.trimIndent()
    )

    @Test fun typedSetLiteral() = check(
        "2",
        """
        import std.io
        func main() {
            var values: Set<Int> = ![10, 20]
            println(values.size)
        }
        """.trimIndent()
    )

    @Test fun typedStdlibCollectionLiteralsExposeSize() = check(
        "3\n2\n2",
        """
        import std.io
        func main() {
            var numbers: List<Int> = @arr[10, 20, 30]
            var unique: Set<Int> = ![1, 2, 2]
            var names: Map<String, Int> = ["a": 1, "b": 2]
            println(numbers.size)
            println(unique.size)
            println(names.size)
        }
        """.trimIndent()
    )

    @Test fun setAddReturnsWhetherInserted() = check(
        "true\nfalse\n3",
        """
        import std.io
        func main() {
            var values = ![1, 2]
            println(values.add(3))
            println(values.add(2))
            println(values.size)
        }
        """.trimIndent()
    )

    @Test fun setRemoveReturnsWhetherRemoved() = check(
        "true\nfalse\n2\n1\n3",
        """
        import std.io
        func main() {
            var values = ![1, 2, 3]
            println(values.remove(2))
            println(values.remove(9))
            println(values.size)
            for value in values {
                println(value)
            }
        }
        """.trimIndent()
    )

    @Test fun setClearAndEmptyProperties() = check(
        "false\ntrue\n0",
        """
        import std.io
        import std.container.set
        func main() {
            var values = @set![1, 2]
            println(values.isEmpty)
            values.clear()
            println(values.isEmpty)
            println(values.size)
        }
        """.trimIndent()
    )

    @Test fun setStringEqualityUsesContents() = check(
        "2\nfalse\ntrue",
        """
        import std.io
        func main() {
            var values = !["ab", "cd"]
            let dynamic = "a" + "b"
            println(values.size)
            println(values.add(dynamic))
            println(values.contains("c" + "d"))
        }
        """.trimIndent()
    )

    @Test fun setForEachSum() = check(
        "6",
        """
        import std.io
        func main() {
            var values = ![1, 2, 3, 2]
            var total = 0
            for value in values {
                total = total + value
            }
            println(total)
        }
        """.trimIndent()
    )

    @Test fun packedCollectionsSupportWideValues() = check(
        "3\n4\n2\ntrue",
        """
        import std.io
        func main() {
            var array = @arr[1c, 2c]
            array.add(3c)
            println(array[2])
            var map = ["value": 4c]
            println(map["value"])
            var set = ![1c, 1c, 2c]
            println(set.size)
            println(set.contains(2c))
        }
        """.trimIndent()
    )

    @Test fun globalSetInitializerRunsBeforeMain() = check(
        "3\ntrue",
        """
        import std.io
        fin values = ![1, 2, 2, 3]
        func main() {
            println(values.size)
            println(values.contains(3))
        }
        """.trimIndent()
    )

    @Test fun realArraySumViaLoop() = check(
        "4.5",
        """
        import std.io
        func main() {
            fin arr = @arr[0.5, 1.5, 2.5]
            var sum = 0.0
            var i = 0
            while i < arr.size {
                sum = sum + arr[i]
                i = i + 1
            }
            println(sum)
        }
        """.trimIndent()
    )

    @Test fun stringLength() = check(
        "5",
        """
        import std.io
        func main() {
            fin s = "hello"
            println(s.size)
        }
        """.trimIndent()
    )

    // -----------------------------------------------------------------------
    // Optimizer side-effect preservation
    // -----------------------------------------------------------------------

    @Test fun unusedLocalWithCallInitializerKeepsTheCall() = check(
        "called\ndone",
        """
        import std.io
        func sideEffect(): Int {
            println("called")
            return 1
        }
        func main() {
            fin unused = sideEffect()
            println("done")
        }
        """.trimIndent()
    )

    // -----------------------------------------------------------------------
    // Stack safety: allocas are hoisted to the entry block
    // -----------------------------------------------------------------------

    /** Locals declared in a hot loop must not grow the stack per iteration
     *  (2M iterations × per-iteration allocas would overflow an 8MB stack). */
    @Test fun loopLocalsDoNotGrowTheStack() = check(
        "3999998",
        """
        import std.io
        func main() {
            var last = 0
            var i = 0
            while i < 2000000 {
                fin doubled = i * 2
                fin nested = doubled + 0
                last = nested
                i = i + 1
            }
            println(last)
        }
        """.trimIndent()
    )

    /** Every alloca must be in the entry block, before any branch label. */
    @Test fun allAllocasLiveInTheEntryBlock() {
        val ir = LlvmExec.compile(
            """
            import std.io
            func main() {
                var i = 0
                while i < 3 {
                    fin x = i * 2
                    if x > 2 {
                        fin y = x + 1
                        println(y)
                    }
                    i = i + 1
                }
            }
            """.trimIndent()
        )
        val mainBody = ir.substringAfter("define i32 @main()").substringBefore("\n}")
        var inEntry = true
        for (line in mainBody.lines()) {
            val trimmed = line.trim()
            if (trimmed.endsWith(":") && trimmed != "entry:") inEntry = false
            if ("= alloca " in trimmed) {
                assertTrue(inEntry, "alloca outside the entry block: $trimmed")
            }
        }
    }
}
