/*
 * Copyright 2026 AzoraTech
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

/**
 * End-to-end `lli` tests for the LLVM backend's aggregate lowering:
 * struct (pack) construction / member access / member assignment, nested
 * structs, impl methods, operator overloading, and heap arrays with an i64
 * length header (literal, index read/write, `.length`).
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
        pack Vec2 {
            var x: Real
            var y: Real
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
        "1.5\n90",
        """
        pack Vec3 {
            var x: Real
            var y: Real
            var z: Real
        }
        pack Camera {
            var pos: Vec3
            var yaw: Real
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
        "38",
        """
        pack Vec3 {
            var x: Real
            var y: Real
            var z: Real
        }
        impl Vec3 {
            func lengthSq(): Real {
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
        "22",
        """
        pack Vec2 {
            var x: Real
            var y: Real
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
        func main() {
            fin arr = [1, 2, 3, 4]
            println(arr[2])
            println(arr.length)
        }
        """.trimIndent()
    )

    @Test fun arrayIndexAssignment() = check(
        "9",
        """
        func main() {
            var arr = [1, 2, 3]
            arr[1] = 9
            println(arr[1])
        }
        """.trimIndent()
    )

    @Test fun realArraySumViaLoop() = check(
        "4.5",
        """
        func main() {
            fin arr = [0.5, 1.5, 2.5]
            var sum = 0.0
            var i = 0
            while i < arr.length {
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
        func main() {
            fin s = "hello"
            println(s.length)
        }
        """.trimIndent()
    )
}
