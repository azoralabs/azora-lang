/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Decision D-5 on WebAssembly: a `String` a host import returns is copied into
 * Azora's memory at the call. The host here writes its text into a page of
 * its own and overwrites it afterwards; the program still holds what it read.
 */
class ForeignStringWasmExecTest {
    private val host = """
        hostText: () => {
          const ptr = mem.grow(1) * 65536;
          const bytes = new TextEncoder().encode("host text");
          new DataView(mem.buffer).setInt32(ptr, bytes.length, true);
          new Uint8Array(mem.buffer, ptr + 4, bytes.length).set(bytes);
          globalThis.hostPtr = ptr;
          return ptr;
        },
        hostClear: () => { new Uint8Array(mem.buffer, globalThis.hostPtr + 4, 9).fill(120); },
        hostNull: () => 0,
    """.trimIndent()

    @Test fun aHostStringResultIsCopiedAtTheCall() {
        if (!WasmExec.available) return
        val output = WasmExec.runWat(
            WasmExec.compile(
                """
                import std.io
                bridge .C {
                    func hostText(): String
                    func hostClear()
                }
                func main() {
                    fin t = hostText()
                    hostClear()
                    println(t)
                }
                """.trimIndent(),
            ),
            host,
        )
        assertEquals("host text", output.trim())
    }

    /** The generated host supplies printing; a `bridge` the host does not provide stops the program, by name. */
    @Test fun anImportTheHostDoesNotProvideIsNamed() {
        if (!WasmExec.available) return
        val output = WasmExec.runWatExpectingError(
            WasmExec.compile(
                """
                import std.io
                bridge .C {
                    func hostSecret(): Int
                }
                func main() {
                    println("before")
                    println(hostSecret())
                }
                """.trimIndent(),
            ),
            "the host provides no 'hostSecret', which this program imports",
        )
        assertEquals("before", output.trim())
    }

    @Test fun aNullHostStringTraps() {
        if (!WasmExec.available) return
        val output = WasmExec.runWatExpectingTrap(
            WasmExec.compile(
                """
                import std.io
                bridge .C {
                    func hostNull(): String
                }
                func main() {
                    println("before")
                    fin t = hostNull()
                    println(t)
                }
                """.trimIndent(),
            ),
            hostImports = host,
        )
        assertEquals("before", output.trim())
    }
}
