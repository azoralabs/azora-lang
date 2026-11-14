/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A `solo pack` that a `graph` also registers has one factory, the
 * registration's: two of them were a redefinition on LLVM and WebAssembly.
 */
class SoloGraphExecTest {
    private val program = """
        import std.io

        solo pack Counter {
            var n: Int = 0
        }

        impl Counter {
            func !.inc(): Int {
                self.n = self.n + 1
                return self.n
            }
        }

        graph AppGraph {
            solo Counter(10)
        }

        func bump(): Int {
            var again = inject Counter
            return again.inc()
        }

        func main() {
            var counter = inject Counter
            println(counter.inc())
            println(counter.inc())
            println(bump())
            println(counter.n)
        }
    """.trimIndent()

    @Test fun aRegisteredSoloPackRunsOnEveryBackend() {
        val result = Compiler().compile(program)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors?.joinToString("\n"))
        assertEquals("11\n12\n13\n13", IrInterpreter().interpret(result.ir).trim())
        if (LlvmExec.available) assertEquals("11\n12\n13\n13", LlvmExec.run(program).trim())
        if (WasmExec.available) assertEquals("11\n12\n13\n13", WasmExec.run(program).trim())
    }
}
