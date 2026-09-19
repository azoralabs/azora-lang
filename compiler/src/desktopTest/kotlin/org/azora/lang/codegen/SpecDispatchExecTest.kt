/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.azora.lang.ir.IrProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A value seen through a spec reaches its own implementation. A pack passed
 * where a spec is expected is boxed with its type id, and each spec member
 * dispatches on that id.
 */
class SpecDispatchExecTest {
    private fun compile(source: String, optimized: Boolean): IrProgram {
        val result = Compiler().compile(source, release = optimized)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return result.ir
    }

    private fun assertRunsEverywhere(source: String, expected: String) {
        for (optimized in listOf(false, true)) {
            val ir = compile(source, optimized)
            assertEquals(expected, IrInterpreter().interpret(ir).trim(), "interpreter, optimized=$optimized")
            if (LlvmExec.available) {
                assertEquals(expected, LlvmExec.runIr(LlvmCodegen().generate(ir)), "LLVM, optimized=$optimized")
            }
            if (WasmExec.available) {
                assertEquals(expected, WasmExec.runWat(WasmCodegen().generate(ir)), "WASM, optimized=$optimized")
            }
        }
    }

    @Test fun eachImplementerAnswersForItself() = assertRunsEverywhere(
        """
            import std.io
            spec Shape {
                func &.area(): Double
                prop &.corners: Int
            }
            pack Square { var side: Double }
            pack Rect {
                var width: Double
                var height: Double
            }
            impl Shape for Square {
                func &.area(): Double { return self.side * self.side }
                prop &.corners: Int = 4
            }
            impl Shape for Rect {
                func &.area(): Double { return self.width * self.height }
                prop &.corners: Int = 5
            }
            func total(first: Shape, second: Shape): Double { return first.area() + second.area() }
            func main() {
                println(total(Square(2.0), Rect(1.5, 4.0)))
                fin shape: Shape = Rect(1.0, 3.0)
                println(shape.area())
                println(shape.corners)
            }
        """.trimIndent(),
        "10.0\n3.0\n5",
    )

    // `ArrayList ==` takes its right side as a `List<T>` and reads it through
    // the spec's `size` and `get`.
    @Test fun listEqualityReadsTheOtherListThroughItsSpec() = assertRunsEverywhere(
        """
            import std.container.list::ArrayList
            import std.io
            func main() {
                fin a = ArrayList<Int>(1, 2, 3)
                println(a == ArrayList<Int>(1, 2, 3))
                println(a == ArrayList<Int>(1, 2, 4))
                println(a == ArrayList<Int>(1, 2))
                println(a != ArrayList<Int>(1, 2, 4))
            }
        """.trimIndent(),
        "true\nfalse\nfalse\ntrue",
    )
}
