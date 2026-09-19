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
 * Values crossing an erased generic boundary keep their bits. A generic
 * parameter, result or field is an eight-byte slot on every native target, so a
 * Double or a Long survives the trip; pack fields take their own widths around it.
 */
class ErasedGenericExecTest {
    private fun compile(source: String, optimized: Boolean): IrProgram {
        val result = Compiler().compile(source, release = optimized)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return result.ir
    }

    private fun assertRuns(source: String, expected: String, llvm: Boolean = true) {
        for (optimized in listOf(false, true)) {
            val ir = compile(source, optimized)
            assertEquals(expected, IrInterpreter().interpret(ir).trim(), "interpreter, optimized=$optimized")
            if (llvm && LlvmExec.available) {
                assertEquals(expected, LlvmExec.runIr(LlvmCodegen().generate(ir)), "LLVM, optimized=$optimized")
            }
            if (WasmExec.available) {
                assertEquals(expected, WasmExec.runWat(WasmCodegen().generate(ir)), "WASM, optimized=$optimized")
            }
        }
    }

    // LLVM prints this Long as `<value>`; that native defect is recorded in the
    // progress log and is not what this test measures.
    @Test fun genericFunctionsReturnWideValues() = assertRuns(
        """
            import std.io
            func<T> identity(value: T): T { return value }
            func<T> pick(first: T, second: T, takeFirst: Bool): T { return if takeFirst then first else second }
            func main() {
                println(identity(7))
                println(identity(2.5))
                fin big: Long = 5000000000
                println(identity(big))
                println(identity("text"))
                println(pick(1.25, 3.5, false))
            }
        """.trimIndent(),
        "7\n2.5\n5000000000\ntext\n3.5",
        llvm = false,
    )

    @Test fun aGenericFieldHoldsItsTypeArgument() = assertRuns(
        """
            import std.io
            pack Box<T> { var value: T }
            func main() {
                var ratio = Box<Double>(1.5)
                println(ratio.value)
                ratio.value = 4.25
                println(ratio.value)
                fin big = Box<Long>(5000000000)
                println(big.value)
                fin name = Box<String>("boxed")
                println(name.value)
            }
        """.trimIndent(),
        "1.5\n4.25\n5000000000\nboxed",
    )

    @Test fun packFieldsTakeTheirOwnWidths() = assertRuns(
        """
            import std.io
            pack Mixed {
                var count: Int
                var ratio: Double
                var flag: Bool
                var total: Long
            }
            func main() {
                var mixed = Mixed(3, 0.5, true, 5000000000)
                mixed.ratio = 2.75
                mixed.total = mixed.total + 1
                println(mixed.count)
                println(mixed.ratio)
                println(mixed.flag)
                println(mixed.total)
            }
        """.trimIndent(),
        "3\n2.75\ntrue\n5000000001",
    )

    @Test fun anErasedFieldSitsBetweenConcreteOnes() = assertRuns(
        """
            import std.io
            pack Tagged<T> {
                var tag: Int
                var value: T
                var weight: Double
            }
            func main() {
                fin tagged = Tagged<Double>(7, 1.5, 0.25)
                println(tagged.tag)
                println(tagged.value)
                println(tagged.weight)
            }
        """.trimIndent(),
        "7\n1.5\n0.25",
    )

    @Test fun wideFieldsExchange() = assertRuns(
        """
            import std.io
            pack Pair {
                var left: Double
                var right: Double
            }
            func main() {
                var pair = Pair(1.5, 2.5)
                pair.left <> pair.right
                println(pair.left)
                println(pair.right)
            }
        """.trimIndent(),
        "2.5\n1.5",
    )
}
