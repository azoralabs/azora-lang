/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The JavaScript host the compiler generates with a WebAssembly module (TARGETS_DIP §4). */
class WasmHostTest {
    private val wat = """
        (module
          (import "env" "print_str" (func ${'$'}print_str (param i32)))
          (import "env" "write_i32" (func ${'$'}write_i32 (param i32)))
          (import "env" "hostClock" (func ${'$'}hostClock (result f64)))
          (memory (export "memory") 1)
          (func ${'$'}main))
    """.trimIndent()

    @Test fun itNamesEveryImportTheModuleDeclares() {
        assertEquals(listOf("print_str", "write_i32", "hostClock"), WasmHost.importsOf(wat))
        assertEquals(listOf("a\"b c"), WasmHost.importsOf("(import \"env\" \"a\\22b\\20c\" (func))"))
    }

    @Test fun printingIsImplementedAndOtherImportsComeFromTheHost() {
        val glue = WasmHost.glueFor(wat)
        assertTrue(glue.startsWith("(function azoraHost(write, provided) {"), glue)
        assertTrue("\"print_str\": (p) => write(text(p) + '\\n')," in glue, glue)
        assertTrue("\"write_i32\": (x) => write(String(x))," in glue, glue)
        assertTrue("\"hostClock\": provided[\"hostClock\"] || missing(\"hostClock\")," in glue, glue)
        // Only what the module imports: an unused printer is not in its host.
        assertTrue("print_i64" !in glue, glue)
    }

    /** A name can be any string; in the host it is only ever an escaped string literal. */
    @Test fun aNameCannotWriteCodeIntoTheHost() {
        val glue = WasmHost.glue(listOf("x\"] || alert(1) || [\"", "line\u2028break"))
        assertTrue("alert(1) || [\"" !in glue, glue)
        assertTrue("\"x\\u0022\\u005d\\u0020\\u007c\\u007c\\u0020alert\\u00281\\u0029" in glue, glue)
        assertTrue("\\u2028" in glue, glue)
    }
}
