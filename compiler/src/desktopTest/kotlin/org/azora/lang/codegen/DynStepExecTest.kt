/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A call through a spec-typed value (`s: Step<Double>`) reaches the
 * implementer with its operands at the implementer's own types. LLVM's
 * dispatch stub forwarded the spec's erased operand (`i8*`) to a member taking
 * a `double`, so the step arrived as garbage.
 */
class DynStepExecTest {
    private val program = """
        import std.io
        import std.traits
        pack Temp { var c: Double }
        derive PartialEqual for Temp
        derive PartialOrder for Temp
        impl Step<Double> for Temp {
            prop &.unit: Double = 1.0
            func &.offset(step: Double&, count: Long): Temp? {
                fin moved = self.c + step * (count as Double)
                if moved > 100.0 {
                    return null
                }
                return Temp(moved)
            }
        }
        pack Day { var n: Int }
        derive Order for Day
        impl Step<Int> for Day {
            prop &.unit: Int = 1
            func &.offset(step: Int&, count: Long): Day? {
                fin moved: Long = (self.n as Long) + (step as Long) * count
                if moved < 0 || moved > 365 {
                    return null
                }
                return Day(moved as Int)
            }
        }
        func warm(s: Step<Double>, amount: Double): Bool {
            return s.reaches(amount, 10)
        }
        func soon(s: Step<Int>, amount: Int): Bool {
            return s.reaches(amount, 100)
        }
        func main() {
            println(warm(Temp(3.0), 0.5))
            println(warm(Temp(3.0), 20.0))
            println(warm(Temp(99.5), 0.05))
            println(soon(Day(3), 2))
            println(soon(Day(3), 4))
        }
    """.trimIndent()

    private val expected = "true\nfalse\ntrue\ntrue\nfalse"

    @Test fun theInterpreterDispatchesAStep() {
        val result = Compiler().compile(program, release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(expected, IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun llvmDispatchesAStep() {
        if (!LlvmExec.available) return
        for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "optimized=$optimized")
        }
    }

    @Test fun webAssemblyDispatchesAStep() {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(program).trimEnd())
    }
}
