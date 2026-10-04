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
 * Golden (full-output) tests for the WASM and LLVM backends.
 *
 * Unlike the substring assertions elsewhere, these compare the **entire**
 * generated source text, so any unintended codegen change - formatting,
 * ordering, register numbering, added/removed declarations - fails loudly.
 * If a change to a backend is intentional, regenerate the expected text and
 * update it here.
 */
class CodegenGoldenTest {

    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    /** Program 1 - functions, if/else-if, for, while, interpolation, int division. */
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

    /** Program 2 - pack (struct), array literal/index, when with multi-pattern branch. */
    private val aggregateProgram = """
        import std.io
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
    // WASM (WAT) - structural assertions (full behaviour is covered by the
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
            bridge .WebAssembly {
                func webClear(r: Double, g: Double, b: Double): Unit
                func webWave(time: Double, speed: Double): Double
                func unused(value: Int): Unit
            }

            func frame(time: Double): Double {
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
            declare void @abort() noreturn
            declare i32 @fflush(i8*)
            declare i8* @malloc(i64)
            declare void @free(i8*)
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
              %6 = sext i32 1 to i64
              %7 = sext i32 5 to i64
              %8 = sext i32 1 to i64
              %9 = icmp sgt i64 %8, 0
              br i1 %9, label %for_step_valid.0, label %for_step_invalid.1
            for_step_invalid.1:
              call void @__azora_abort()
              unreachable
            for_step_valid.0:
              br label %for_entry.6
            for_entry.6:
              br label %for_cond.2
            for_cond.2:
              %10 = phi i64 [ %6, %for_entry.6 ], [ %for_next.7, %for_inc.4 ]
              %11 = icmp sle i64 %10, %7
              br i1 %11, label %for_body.3, label %for_end.5
            for_body.3:
              %12 = trunc i64 %10 to i32
              store i32 %12, i32* %loc2.i
              %13 = load i32, i32* %loc1.total
              %14 = load i32, i32* %loc2.i
              %15 = add i32 %13, %14
              store i32 %15, i32* %loc1.total
              br label %for_inc.4
            for_inc.4:
              %for_next.7 = add i64 %10, %8
              br label %for_cond.2
            for_end.5:
              %16 = load i32, i32* %loc1.total
              %17 = getelementptr [4 x i8], [4 x i8]* @.str.4, i64 0, i64 0
              %18 = call i32 (i8*, ...) @printf(i8* %17, i32 %16)
              br label %while_cond.8
            while_cond.8:
              %19 = load i32, i32* %loc1.total
              %20 = icmp sgt i32 %19, 10
              br i1 %20, label %while_body.9, label %while_end.10
            while_body.9:
              %21 = load i32, i32* %loc1.total
              %22 = sub i32 %21, 4
              store i32 %22, i32* %loc1.total
              br label %while_cond.8
            while_end.10:
              %23 = load i32, i32* %loc1.total
              %24 = getelementptr [4 x i8], [4 x i8]* @.str.4, i64 0, i64 0
              %25 = call i32 (i8*, ...) @printf(i8* %24, i32 %23)
              %26 = call i8* @classify(i32 5)
              %27 = call i32 @puts(i8* %26)
              %28 = getelementptr [4 x i8], [4 x i8]* @.str.4, i64 0, i64 0
              %29 = call i32 (i8*, ...) @printf(i8* %28, i32 2)
              ret i32 0
            }

            ; runtime: checked native allocation
            define i8* @__azora_alloc_raw(i64 %size) {
            entry:
              %p = call i8* @malloc(i64 %size)
              %isnull = icmp eq i8* %p, null
              br i1 %isnull, label %oom, label %ok
            oom:
              call void @__azora_abort()
              unreachable
            ok:
              ret i8* %p
            }

            define i8* @__azora_alloc(i64 %size) {
            entry:
              %p = call i8* @__azora_alloc_raw(i64 %size)
              ret i8* %p
            }

            define void @__azora_free(i8* %ptr) {
            entry:
              %isnull = icmp eq i8* %ptr, null
              br i1 %isnull, label %end, label %free
            free:
              call void @free(i8* %ptr)
              br label %end
            end:
              ret void
            }

            ; runtime: string concatenation
            define i8* @__azora_str_concat(i8* %a, i8* %b) {
            entry:
              %la = call i64 @strlen(i8* %a)
              %lb = call i64 @strlen(i8* %b)
              %sum = add i64 %la, %lb
              %size = add i64 %sum, 1
              %buf = call i8* @__azora_alloc(i64 %size)
              %c1 = call i8* @strcpy(i8* %buf, i8* %a)
              %c2 = call i8* @strcat(i8* %buf, i8* %b)
              ret i8* %buf
            }

            ; runtime: integer to string
            define i8* @__azora_int_to_str(i64 %v) {
            entry:
              %buf = call i8* @__azora_alloc(i64 24)
              %fmt = getelementptr [5 x i8], [5 x i8]* @.str.5, i64 0, i64 0
              %r = call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 24, i8* %fmt, i64 %v)
              ret i8* %buf
            }

            ; runtime: stop the program, keeping what it printed
            define void @__azora_abort() noreturn {
            entry:
              %flushed = call i32 @fflush(i8* null)
              call void @abort()
              unreachable
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
