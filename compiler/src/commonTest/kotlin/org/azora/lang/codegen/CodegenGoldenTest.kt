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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Golden (full-output) tests for the JavaScript, WASM and LLVM backends.
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
        import std.io
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
            std::println("sum = ${'$'}sum")
            var total = 0
            for i in 1..5 {
                total = total + i
            }
            std::println(total)
            while total > 10 {
                total = total - 4
            }
            std::println(total)
            std::println(classify(sum))
            let half = sum / 2
            std::println(half)
        }
    """.trimIndent()

    /** Program 2 — pack (struct), array literal/index, when with multi-pattern branch. */
    private val aggregateProgram = """
        import std.io
        pack Point {
            var x: Int
            var y: Int
        }

        func main() {
            let p = Point(3, 4)
            p.x = p.x + 1
            std::println(p.x)
            let nums = arr![10, 20, 30]
            nums[1] = 25
            std::println(nums[1])
            let grade = 2
            when grade {
                1 -> { std::println("one") }
                2, 3 -> { std::println("two or three") }
                else -> { std::println("other") }
            }
        }
    """.trimIndent()

    // -----------------------------------------------------------------------
    // JavaScript
    // -----------------------------------------------------------------------

    @Test
    fun javascriptFullOutputScalar() {
        val expected = """
            function classify(n) {
                if ((n < 0)) {
                    return "negative";
                } else if ((n === 0)) {
                    return "zero";
                }
                return "positive";
            }

            function main() {
                const sum = 5;
                console.log(`sum = ${'$'}{sum}`);
                let total = 0;
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
        assertEquals(expected, compile(scalarProgram).javascript)
    }

    @Test
    fun javascriptFullOutputAggregate() {
        val expected = """
            class Point {
                constructor(x, y) {
                    this.x = x;
                    this.y = y;
                }
            }

            function main() {
                const p = new Point(3, 4);
                p.x = (p.x + 1);
                console.log(p.x);
                const nums = arr![10, 20, 30];
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
        assertEquals(expected, compile(aggregateProgram).javascript)
    }

    // -----------------------------------------------------------------------
    // WASM (WAT) — structural assertions (full behaviour is covered by the
    // WasmCodegenExecTest end-to-end suite).
    // -----------------------------------------------------------------------

    @Test
    fun wasmScalarStructure() {
        val wat = compile(scalarProgram).wasm
        assertTrue(wat.startsWith("(module"), "should be a WAT module")
        assertTrue("(import \"env\" \"print_str\"" in wat, "imports print_str")
        assertTrue("(memory (export \"memory\") 16)" in wat, "exports memory")
        assertTrue("(func \$__str_concat" in wat, "emits the string-concat runtime")
        assertTrue("(func \$__int_to_str" in wat, "emits the int-to-string runtime")
        assertTrue("(func \$classify (param \$n i32) (result i32)" in wat, "lowers classify")
        assertTrue("(export \"main\" (func \$main))" in wat, "exports main")
        assertTrue("negative" in wat && "positive" in wat, "embeds string constants")
    }

    @Test
    fun wasmAggregateStructure() {
        val wat = compile(aggregateProgram).wasm
        assertTrue(wat.startsWith("(module"), "should be a WAT module")
        // Struct construction: alloc + field stores.
        assertTrue("(call \$__alloc (i32.const 8))" in wat, "allocates the 2-field pack")
        // Array construction: length-prefixed alloc of 3 i32 elements.
        assertTrue("(call \$__alloc (i32.const 16))" in wat, "allocates the 3-element array")
        assertTrue("(export \"main\" (func \$main))" in wat, "exports main")
    }

    @Test
    fun wasmDeclaresReferencedBridgeFunctionsAsTypedHostImports() {
        val wat = compile(
            """
            bridge WebGL {
                func webClear(r: Real, g: Real, b: Real): Unit
                func webWave(time: Real, speed: Real): Real
                func unused(value: Int): Unit
            }

            func frame(time: Real): Real {
                webClear(0.1, 0.2, 0.3)
                return webWave(time, 2.0)
            }

            func main() {
                frame(0.0)
            }
            """.trimIndent(),
        ).wasm

        assertTrue(
            "(import \"env\" \"webClear\" (func \$webClear (param f64) (param f64) (param f64)))" in wat,
            wat,
        )
        assertTrue(
            "(import \"env\" \"webWave\" (func \$webWave (param f64) (param f64) (result f64)))" in wat,
            wat,
        )
        assertTrue("\"unused\"" !in wat, "unused bridge declarations must not become required host imports")
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
              %3 = getelementptr arr![9 x i8], arr![9 x i8]* @.str.0, i64 0, i64 0
              ret i8* %3
            else.1:
              %4 = load i32, i32* %0
              %5 = icmp eq i32 %4, 0
              br i1 %5, label %then.3, label %merge.5
            then.3:
              %6 = getelementptr arr![5 x i8], arr![5 x i8]* @.str.1, i64 0, i64 0
              ret i8* %6
            merge.5:
              br label %merge.2
            merge.2:
              %7 = getelementptr arr![9 x i8], arr![9 x i8]* @.str.2, i64 0, i64 0
              ret i8* %7
            }

            define i32 @main() {
            entry:
              %loc0.sum = alloca i32
              %loc1.total = alloca i32
              %loc2.i = alloca i32
              store i32 5, i32* %loc0.sum
              %0 = getelementptr arr![7 x i8], arr![7 x i8]* @.str.3, i64 0, i64 0
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
              %14 = getelementptr arr![4 x i8], arr![4 x i8]* @.str.4, i64 0, i64 0
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
              %21 = getelementptr arr![4 x i8], arr![4 x i8]* @.str.4, i64 0, i64 0
              %22 = call i32 (i8*, ...) @printf(i8* %21, i32 %20)
              %23 = call i8* @classify(i32 5)
              %24 = call i32 @puts(i8* %23)
              %25 = getelementptr arr![4 x i8], arr![4 x i8]* @.str.4, i64 0, i64 0
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
              %fmt = getelementptr arr![5 x i8], arr![5 x i8]* @.str.5, i64 0, i64 0
              %r = call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 24, i8* %fmt, i64 %v)
              ret i8* %buf
            }

            ; String constants
            @.str.0 = private unnamed_addr constant arr![9 x i8] c"negative\00"
            @.str.1 = private unnamed_addr constant arr![5 x i8] c"zero\00"
            @.str.2 = private unnamed_addr constant arr![9 x i8] c"positive\00"
            @.str.3 = private unnamed_addr constant arr![7 x i8] c"sum = \00"
            @.str.4 = private unnamed_addr constant arr![4 x i8] c"%d\0A\00"
            @.str.5 = private unnamed_addr constant arr![5 x i8] c"%lld\00"
        """.trimIndent()
        assertEquals(expected, compile(scalarProgram).llvm)
    }
}
