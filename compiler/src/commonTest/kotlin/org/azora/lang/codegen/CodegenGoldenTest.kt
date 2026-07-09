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

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Golden (full-output) tests for the Kotlin, TypeScript and LLVM backends.
 *
 * Unlike the substring assertions elsewhere, these compare the **entire**
 * generated source text, so any unintended codegen change — formatting,
 * ordering, register numbering, added/removed declarations — fails loudly.
 * If a change to a backend is intentional, regenerate the expected text and
 * update it here.
 */
class CodegenGoldenTest {

    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    /** Program 1 — functions, if/else-if, for, while, interpolation, int division. */
    private val scalarProgram = """
        func add(a: Int, b: Int): Int {
            return a + b
        }

        func classify(n: Int): String {
            if n < 0 {
                return "negative"
            } else if n == 0 {
                return "zero"
            }
            return "positive"
        }

        func main() {
            let sum = add(2, 3)
            println("sum = ${'$'}sum")
            var total = 0
            for i in 1..5 {
                total = total + i
            }
            println(total)
            while total > 10 {
                total = total - 4
            }
            println(total)
            println(classify(sum))
            let half = sum / 2
            println(half)
        }
    """.trimIndent()

    /** Program 2 — pack (struct), array literal/index, when with multi-pattern branch. */
    private val aggregateProgram = """
        pack Point {
            var x: Int
            var y: Int
        }

        func main() {
            let p = Point(3, 4)
            p.x = p.x + 1
            println(p.x)
            let nums = [10, 20, 30]
            nums[1] = 25
            println(nums[1])
            let grade = 2
            when grade {
                1 -> { println("one") }
                2, 3 -> { println("two or three") }
                else -> { println("other") }
            }
        }
    """.trimIndent()

    // -----------------------------------------------------------------------
    // Kotlin
    // -----------------------------------------------------------------------

    @Test
    fun kotlinFullOutputScalar() {
        val expected = """
            fun classify(n: Int): String {
                if ((n < 0)) {
                    return "negative"
                } else if ((n == 0)) {
                    return "zero"
                }
                return "positive"
            }

            fun main(): Unit {
                val sum: Int = 5
                println("sum = ${'$'}{sum}")
                var total: Int = 0
                for (i in 1..5) {
                    total = (total + i)
                }
                println(total)
                while ((total > 10)) {
                    total = (total - 4)
                }
                println(total)
                println(classify(5))
                println(2)
            }
        """.trimIndent()
        assertEquals(expected, compile(scalarProgram).kotlin)
    }

    @Test
    fun kotlinFullOutputAggregate() {
        val expected = """
            class Point(var x: Int, var y: Int)

            fun main(): Unit {
                val p: Point = Point(3, 4)
                p.x = (p.x + 1)
                println(p.x)
                val nums: MutableList<Int> = mutableListOf(10, 20, 30)
                nums[1] = 25
                println(nums[1])
                when (2) {
                    1 -> {
                        println("one")
                    }
                    2, 3 -> {
                        println("two or three")
                    }
                    else -> {
                        println("other")
                    }
                }
            }
        """.trimIndent()
        assertEquals(expected, compile(aggregateProgram).kotlin)
    }

    // -----------------------------------------------------------------------
    // TypeScript
    // -----------------------------------------------------------------------

    @Test
    fun typescriptFullOutputScalar() {
        val expected = """
            function classify(n: number): string {
                if ((n < 0)) {
                    return "negative";
                } else if ((n === 0)) {
                    return "zero";
                }
                return "positive";
            }

            function main(): void {
                const sum: number = 5;
                console.log(`sum = ${'$'}{sum}`);
                let total: number = 0;
                for (let i = 1; i <= 5; i++) {
                    total = (total + i);
                }
                console.log(total);
                while ((total > 10)) {
                    total = (total - 4);
                }
                console.log(total);
                console.log(classify(5));
                console.log(2);
            }

            main()
        """.trimIndent()
        assertEquals(expected, compile(scalarProgram).typescript)
    }

    @Test
    fun typescriptFullOutputAggregate() {
        val expected = """
            class Point {
                x: number;
                y: number;
                constructor(x: number, y: number) {
                    this.x = x;
                    this.y = y;
                }
            }

            function main(): void {
                const p: Point = new Point(3, 4);
                p.x = (p.x + 1);
                console.log(p.x);
                const nums: number[] = [10, 20, 30];
                nums[1] = 25;
                console.log(nums[1]);
                switch (2) {
                    case 1: {
                        console.log("one");
                        break;
                    }
                    case 2:
                    case 3: {
                        console.log("two or three");
                        break;
                    }
                    default: {
                        console.log("other");
                    }
                }
            }

            main()
        """.trimIndent()
        assertEquals(expected, compile(aggregateProgram).typescript)
    }

    // -----------------------------------------------------------------------
    // Swift
    // -----------------------------------------------------------------------

    @Test
    fun swiftFullOutputScalar() {
        val expected = """
            // Swift 6.3 source generated by the Azora compiler

            func classify(_ n: Int) -> String {
                if (n < 0) {
                    return "negative"
                } else if (n == 0) {
                    return "zero"
                }
                return "positive"
            }

            func main() {
                let sum: Int = 5
                print("sum = \(sum)")
                var total: Int = 0
                for i in 1...5 {
                    total = (total + i)
                }
                print(total)
                while (total > 10) {
                    total = (total - 4)
                }
                print(total)
                print(classify(5))
                print(2)
            }

            main()
        """.trimIndent()
        assertEquals(expected, compile(scalarProgram).swift)
    }

    @Test
    fun swiftFullOutputAggregate() {
        val expected = """
            // Swift 6.3 source generated by the Azora compiler

            final class Point {
                var x: Int
                var y: Int
                init(_ x: Int, _ y: Int) {
                    self.x = x
                    self.y = y
                }
            }

            func main() {
                let p: Point = Point(3, 4)
                p.x = (p.x + 1)
                print(p.x)
                var nums: [Int] = [10, 20, 30]
                nums[1] = 25
                print(nums[1])
                switch 2 {
                case 1:
                    print("one")
                case 2, 3:
                    print("two or three")
                default:
                    print("other")
                }
            }

            main()
        """.trimIndent()
        assertEquals(expected, compile(aggregateProgram).swift)
    }

    // -----------------------------------------------------------------------
    // LLVM
    // -----------------------------------------------------------------------

    @Test
    fun llvmFullOutputScalar() {
        val expected = """
            ; LLVM IR generated by Azora compiler

            declare i32 @puts(i8*)
            declare i32 @printf(i8*, ...)
            declare i32 @snprintf(i8*, i64, i8*, ...)
            declare i8* @malloc(i64)
            declare i64 @strlen(i8*)
            declare i8* @strcpy(i8*, i8*)
            declare i8* @strcat(i8*, i8*)

            define i8* @classify(i32 %arg.n) {
            entry:
              %0 = alloca i32
              store i32 %arg.n, i32* %0
              %1 = load i32, i32* %0
              %2 = icmp slt i32 %1, 0
              br i1 %2, label %then.0, label %else.1
            then.0:
              %3 = getelementptr [9 x i8], [9 x i8]* @.str.0, i64 0, i64 0
              ret i8* %3
            else.1:
              %4 = load i32, i32* %0
              %5 = icmp eq i32 %4, 0
              br i1 %5, label %then.3, label %merge.5
            then.3:
              %6 = getelementptr [5 x i8], [5 x i8]* @.str.1, i64 0, i64 0
              ret i8* %6
            merge.5:
              br label %merge.2
            merge.2:
              %7 = getelementptr [9 x i8], [9 x i8]* @.str.2, i64 0, i64 0
              ret i8* %7
            }

            define i32 @main() {
            entry:
              %loc0.sum = alloca i32
              %loc1.total = alloca i32
              %loc2.i = alloca i32
              store i32 5, i32* %loc0.sum
              %0 = getelementptr [7 x i8], [7 x i8]* @.str.3, i64 0, i64 0
              %1 = load i32, i32* %loc0.sum
              %2 = sext i32 %1 to i64
              %3 = call i8* @__azora_int_to_str(i64 %2)
              %4 = call i8* @__azora_str_concat(i8* %0, i8* %3)
              %5 = call i32 @puts(i8* %4)
              store i32 0, i32* %loc1.total
              store i32 1, i32* %loc2.i
              br label %for_cond.0
            for_cond.0:
              %6 = load i32, i32* %loc2.i
              %7 = icmp sle i32 %6, 5
              br i1 %7, label %for_body.1, label %for_end.3
            for_body.1:
              %8 = load i32, i32* %loc1.total
              %9 = load i32, i32* %loc2.i
              %10 = add i32 %8, %9
              store i32 %10, i32* %loc1.total
              br label %for_inc.2
            for_inc.2:
              %11 = load i32, i32* %loc2.i
              %12 = add i32 %11, 1
              store i32 %12, i32* %loc2.i
              br label %for_cond.0
            for_end.3:
              %13 = load i32, i32* %loc1.total
              %14 = getelementptr [4 x i8], [4 x i8]* @.str.4, i64 0, i64 0
              %15 = call i32 (i8*, ...) @printf(i8* %14, i32 %13)
              br label %while_cond.4
            while_cond.4:
              %16 = load i32, i32* %loc1.total
              %17 = icmp sgt i32 %16, 10
              br i1 %17, label %while_body.5, label %while_end.6
            while_body.5:
              %18 = load i32, i32* %loc1.total
              %19 = sub i32 %18, 4
              store i32 %19, i32* %loc1.total
              br label %while_cond.4
            while_end.6:
              %20 = load i32, i32* %loc1.total
              %21 = getelementptr [4 x i8], [4 x i8]* @.str.4, i64 0, i64 0
              %22 = call i32 (i8*, ...) @printf(i8* %21, i32 %20)
              %23 = call i8* @classify(i32 5)
              %24 = call i32 @puts(i8* %23)
              %25 = getelementptr [4 x i8], [4 x i8]* @.str.4, i64 0, i64 0
              %26 = call i32 (i8*, ...) @printf(i8* %25, i32 2)
              ret i32 0
            }

            ; runtime: string concatenation
            define i8* @__azora_str_concat(i8* %a, i8* %b) {
            entry:
              %la = call i64 @strlen(i8* %a)
              %lb = call i64 @strlen(i8* %b)
              %sum = add i64 %la, %lb
              %size = add i64 %sum, 1
              %buf = call i8* @malloc(i64 %size)
              %c1 = call i8* @strcpy(i8* %buf, i8* %a)
              %c2 = call i8* @strcat(i8* %buf, i8* %b)
              ret i8* %buf
            }

            ; runtime: integer to string
            define i8* @__azora_int_to_str(i64 %v) {
            entry:
              %buf = call i8* @malloc(i64 24)
              %fmt = getelementptr [5 x i8], [5 x i8]* @.str.5, i64 0, i64 0
              %r = call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 24, i8* %fmt, i64 %v)
              ret i8* %buf
            }

            ; String constants
            @.str.0 = private unnamed_addr constant [9 x i8] c"negative\00"
            @.str.1 = private unnamed_addr constant [5 x i8] c"zero\00"
            @.str.2 = private unnamed_addr constant [9 x i8] c"positive\00"
            @.str.3 = private unnamed_addr constant [7 x i8] c"sum = \00"
            @.str.4 = private unnamed_addr constant [4 x i8] c"%d\0A\00"
            @.str.5 = private unnamed_addr constant [5 x i8] c"%lld\00"
        """.trimIndent()
        assertEquals(expected, compile(scalarProgram).llvm)
    }
}
