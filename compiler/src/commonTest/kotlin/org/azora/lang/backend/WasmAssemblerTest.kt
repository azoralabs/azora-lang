/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.backend

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The WebAssembly text the backend writes, assembled into the binary format (TARGETS_DIP §4). */
class WasmAssemblerTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun assemble(wat: String) = WasmAssembler.assemble(wat.trimIndent(), names = false)

    private val header = bytes(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

    @Test fun aFunctionReturningAConstant() {
        val module = assemble(
            """
            (module
              (func ${'$'}answer (result i32) (i32.const 42))
              (export "answer" (func ${'$'}answer)))
            """,
        )
        assertContentEquals(
            header + bytes(
                0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F, // type: () -> i32
                0x03, 0x02, 0x01, 0x00, // function 0 has type 0
                0x07, 0x0A, 0x01, 0x06, 0x61, 0x6E, 0x73, 0x77, 0x65, 0x72, 0x00, 0x00, // export "answer" func 0
                0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x2A, 0x0B, // code: no locals, i32.const 42, end
            ),
            module,
        )
    }

    @Test fun foldedOperandsComeBeforeTheirInstruction() {
        val module = assemble(
            """
            (module
              (func ${'$'}add (param ${'$'}a i32) (param ${'$'}b i32) (result i32)
                (local ${'$'}t i64)
                (i32.add (local.get ${'$'}a) (local.get ${'$'}b))))
            """,
        )
        // one local run (1 x i64), local.get 0, local.get 1, i32.add, end
        assertContentEquals(
            bytes(0x01, 0x01, 0x7E, 0x20, 0x00, 0x20, 0x01, 0x6A, 0x0B),
            module.copyOfRange(module.size - 9, module.size),
        )
    }

    @Test fun labelsCountOutwardFromTheBranch() {
        val module = assemble(
            """
            (module
              (func ${'$'}f
                (block ${'$'}outer
                  (loop ${'$'}inner
                    (br_if ${'$'}outer (i32.const 1))
                    (br ${'$'}inner)))))
            """,
        )
        val body = module.takeLast(16)
        // block, loop, i32.const 1, br_if 1, br 0, end, end, end
        assertTrue(body.windowed(4).any { it == listOf(0x41, 0x01, 0x0D, 0x01).map(Int::toByte) }, body.toString())
        assertTrue(body.windowed(2).any { it == listOf(0x0C, 0x00).map(Int::toByte) }, body.toString())
    }

    @Test fun integersAreSignedLeb128FromTheirWidth() {
        val module = assemble(
            """
            (module (func (drop (i32.const 4294967295)) (drop (i64.const -129)) (drop (i32.const 0x7f))))
            """,
        )
        // 4294967295 as an i32 is -1: 0x7F. -129 is 0xFF 0x7E. 0x7f is 0xFF 0x00.
        val text = module.joinToString(" ") { (it.toInt() and 0xFF).toString(16) }
        assertTrue("41 7f 1a" in text, text)
        assertTrue("42 ff 7e 1a" in text, text)
        assertTrue("41 ff 0 1a" in text, text)
    }

    @Test fun floatsInEverySpelling() {
        val module = assemble(
            """
            (module (func
              (drop (f64.const 1.5)) (drop (f64.const -0x1.8p1)) (drop (f64.const inf))
              (drop (f32.const 3.0)) (drop (f64.const 1.0E-5))))
            """,
        )
        fun f64(value: Double) = (0 until 8).map { ((value.toRawBits() ushr (8 * it)) and 0xFF).toByte() }
        val list = module.toList()
        assertTrue(list.windowed(9).any { it == listOf(0x44.toByte()) + f64(1.5) })
        assertTrue(list.windowed(9).any { it == listOf(0x44.toByte()) + f64(-3.0) })
        assertTrue(list.windowed(9).any { it == listOf(0x44.toByte()) + f64(Double.POSITIVE_INFINITY) })
        assertTrue(list.windowed(9).any { it == listOf(0x44.toByte()) + f64(1.0E-5) })
        val three = 3.0f.toRawBits()
        assertTrue(list.windowed(5).any { it == listOf(0x43.toByte()) + (0 until 4).map { b -> ((three ushr (8 * b)) and 0xFF).toByte() } })
    }

    @Test fun dataStringsDecodeTheirEscapes() {
        val module = assemble(
            """
            (module (memory 1) (data (i32.const 8) "\06\00a\n\u{e9}"))
            """,
        )
        assertContentEquals(bytes(0x06, 0x00, 0x61, 0x0A, 0xC3, 0xA9), module.copyOfRange(module.size - 6, module.size))
    }

    @Test fun memoryAccessCarriesItsAlignmentAndOffset() {
        val module = assemble(
            """
            (module (memory 1) (func (i32.store offset=4 (i32.const 0) (i32.const 7)) (drop (i64.load (i32.const 8)))))
            """,
        )
        val text = module.joinToString(" ") { (it.toInt() and 0xFF).toString(16) }
        assertTrue("36 2 4" in text, text) // i32.store align=4 (2^2) offset=4
        assertTrue("29 3 0" in text, text) // i64.load align=8 (2^3) offset=0
    }

    @Test fun vectorsAndBulkMemory() {
        val module = assemble(
            """
            (module (memory 1) (func (param ${'$'}v v128) (result i64)
              (memory.copy (i32.const 0) (i32.const 16) (i32.const 4))
              (i64x2.extract_lane 1 (i64x2.replace_lane 1 (v128.const i64x2 0 0) (i64.const 1)))))
            """,
        )
        val text = module.joinToString(" ") { (it.toInt() and 0xFF).toString(16) }
        assertTrue("fc a 0 0" in text, text)
        assertTrue("fd 1e 1" in text, text) // i64x2.replace_lane 1
        assertTrue("fd 1d 1" in text, text) // i64x2.extract_lane 1
    }

    @Test fun mistakesNameTheirLine() {
        val error = assertFailsWith<WasmAssemblyError> { assemble("(module\n  (func (i32.frobnicate)))") }
        assertEquals("line 2: unknown instruction 'i32.frobnicate'", error.message)
        assertFailsWith<WasmAssemblyError> { assemble("(module (func (br ${'$'}nowhere)))") }
        assertFailsWith<WasmAssemblyError> { assemble("(module (func (drop (i32.const 4294967296))))") }
    }
}
