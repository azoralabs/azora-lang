/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A variadic parameter's array belongs to the call: the call site frees a
 * packed temporary, and a spread hands on an array its caller still owns. A
 * bracket literal's constructor passes its pack on to the type's
 * `ctor .(...entries: (K, V))`, which a generic pack's factory passes on in
 * turn - one array, freed once.
 *
 * A tuple written in place is built at the layout of the slot it is passed
 * to, so a generic callee reading erased `(K, V)` components finds them.
 */
class VariadicPackExecTest {
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

    @Test fun aMapLiteralHandsItsEntriesToTheConstructorOnce() = everywhere(
        "true\n20\n3\n40\n2",
        """
        import std.io
        import std.container.map::{LinkedHashMap, HashMap}
        func key(i: Int): String = "k${'$'}{i}"
        func main() {
            var values = [key(1): 10, key(2): 20]
            println(values.containsKey("k1"))
            println(values.get("k2"))
            values.put(key(3), 30)
            println(values.size)
            fin hashed: HashMap<String, Int> = [key(4): 40, key(5): 50]
            println(hashed.get("k4"))
            fin built = LinkedHashMap<String, Int>((key(6), 60), (key(7), 70))
            println(built.size)
        }
        """,
    )

    @Test fun aConstructorReadsTuplesWrittenAtTheCall() = everywhere(
        "20\n10\ntrue",
        """
        import std.io
        import std.container.map::{LinkedHashMap}
        func main() {
            fin m = LinkedHashMap<Int, Int>((1, 10), (2, 20))
            println(m.get(2))
            println(m.get(1))
            println(m.containsKey(2))
        }
        """,
    )

    @Test fun aGenericFunctionReadsTuplesWrittenAtTheCall() = everywhere(
        "10\n30",
        """
        import std.io
        func<K, V> second(p: (K, V)): V = p.1
        func<K, V> secondOf(...ps: (K, V)): V = ps[0].1
        func main() {
            println(second((1, 10)))
            println(secondOf((3, 30)))
        }
        """,
    )
}
