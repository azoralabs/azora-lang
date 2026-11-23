/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `T.typeName` names the type a caller wrote, or is refused. Studio found an
 * explicit-type-argument call to a `Clone`-bounded generic whose argument was
 * an inline `store<T>(pass)` folding `T.typeName` to `"T"`, so a component
 * storage was looked up under the wrong key. Every shape of that call now
 * names the written type on each backend, and a generic body that forwards its
 * own erased `T` to a name lookup is a compile error that says what to do.
 */
class GenericTypeNameExecTest {
    private val declarations = """
        import std.io
        import std.traits::Clone
        pack Box {
            var n: Int
        }
        func named(name: String, tag: Int): String = name + "#" + "${'$'}{tag}"
        inline func<T> inner(tag: Int): String {
            return named(T.typeName, tag)
        }
        inline func<T> outer(tag: Int): String {
            return inner<T>(tag)
        }
        func<T> pick(name: String, fallback: T&): String where T: Clone {
            return name
        }
    """.trimIndent()

    @Test fun anInlineLookupInsideAnExplicitGenericCallNamesTheWrittenType() {
        val program = """
            $declarations
            func use(b: Box&): String {
                return pick<Box>(outer<Box>(1), b)
            }
            func main() {
                println(pick<Box>(outer<Box>(1), Box(1)))
                println(use(Box(2)))
                println(pick<Box>(inner<Box>(3), Box(3)))
            }
        """.trimIndent()
        val expected = "Box#1\nBox#1\nBox#3"
        val result = Compiler().compile(program)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter")
        if (LlvmExec.available) {
            assertEquals(expected, LlvmExec.run(program), "LLVM")
            assertEquals(expected, LlvmExec.run(program, optimized = true), "LLVM optimized")
        }
        if (WasmExec.available) assertEquals(expected, WasmExec.run(program), "WebAssembly")
    }

    @Test fun aNestedGenericArgumentTakesItsTypeFromTheMemberCall() {
        // Studio's `popupPaint.topmost<Pressable>(store(pass), x, y)`: the member
        // call's written type argument is what the inner `store` is for. The
        // parser dropped it, so `store` folded `T.typeName` to "T" and the
        // popup's rows were looked up in a storage nothing is ever put in.
        val program = """
            import std.io
            pack Tag {
                var x: Int
            }
            pack Holder {
                var x: Int
            }
            pack Named<T> {
                var name: String = ""
            }
            func<T> namedAs(name: String): Named<T> {
                var made: Named<T> = .()
                made.name = name
                return made
            }
            inline func<T> named(holder: Holder&): Named<T> {
                return namedAs<T>(T.typeName)
            }
            impl Holder {
                func<T> &.show(n: Named<T>&): String = n.name
            }
            func main() {
                fin holder = Holder(0)
                println(holder.show<Tag>(named(holder)))
            }
        """.trimIndent()
        val result = Compiler().compile(program)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("Tag", IrInterpreter().interpret(result.ir).trim(), "interpreter")
        if (LlvmExec.available) assertEquals("Tag", LlvmExec.run(program), "LLVM")
    }

    @Test fun aGenericBodyForwardingItsOwnTypeToANameLookupIsRefused() {
        val result = Compiler().compile(
            """
            $declarations
            func<T> forward(fallback: T&): String where T: Clone {
                return inner<T>(1)
            }
            func main() {
                println(forward<Box>(Box(1)))
            }
            """.trimIndent(),
        )
        assertIs<CompilationResult.Failure>(result, "forwarding an erased T to T.typeName must not fold to \"T\"")
        assertTrue(result.errors.any { "needs the name of 'T'" in it }, "got: ${result.errors}")
    }
}
