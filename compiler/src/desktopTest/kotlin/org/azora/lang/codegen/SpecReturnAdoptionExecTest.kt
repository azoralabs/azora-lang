/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * An implementation's member that writes no return type takes the one its
 * spec declares. `spec Order` declares `oper<=> …: Compare`; an `impl Order`
 * whose `oper<=>` omitted `: Compare` compiled, and `<` then failed at run time
 * with `no method 'isLess' on String` (LLVM refused the program).
 */
class SpecReturnAdoptionExecTest {
    private fun everywhere(expected: String, source: String) {
        val program = source.trimIndent()
        val result = Compiler().compile(program)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter")
        if (LlvmExec.available) {
            assertEquals(expected, LlvmExec.run(program), "LLVM")
            assertEquals(expected, LlvmExec.run(program, optimized = true), "LLVM optimized")
        }
        if (WasmExec.available) assertEquals(expected, WasmExec.run(program), "WebAssembly")
    }

    @Test fun aSpaceshipWithoutItsReturnTypeReturnsCompare() = everywhere(
        "true\nfalse\ntrue",
        """
        import std.io
        import std.traits::{Order, Equal}
        pack Version {
            var major: Int
        }
        derive Equal for Version
        impl Order for Version {
            oper<=> &.(other: Version&) {
                return self.major <=> other.major
            }
        }
        func main() {
            println(Version(1) < Version(2))
            println(Version(3) < Version(2))
            println(Version(3) >= Version(3))
        }
        """,
    )

    @Test fun anOperatorWithoutItsReturnTypeReturnsTheFamilysOut() = everywhere(
        "5\n6",
        """
        import std.io
        import std.traits
        pack N {
            var v: Int
        }
        impl Arithmetic for N {
            oper+ &.(rhs: Self&) { return N(self.v + rhs.v) }
        }
        impl Arithmetic<Int, N> for N {
            oper* &.(rhs: Int) { return N(self.v * rhs) }
        }
        func main() {
            println((N(3) + N(2)).v)
            println((N(3) * 2).v)
        }
        """,
    )
}
