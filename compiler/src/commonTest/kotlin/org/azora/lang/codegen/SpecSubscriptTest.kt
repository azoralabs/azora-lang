/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `xs[i]` on a value typed by a spec is the spec's own `oper[]`.
 *
 * `List`, `Map` and their mutable forms declared no subscript, so indexing one
 * was typed `Any` and only LLVM read it, by calling a member that happened to
 * be named `get` - which returned an erased word the call site never
 * converted. `m[k] = v` on a `MutableMap` was refused outright. Any other pack
 * could be indexed too, with any key, and be typed `Any`.
 *
 * A map's `oper[]` returned its `get`, which is `V?`, as a `V`. It now panics
 * on a missing key; `get` is still how to ask whether one is there.
 */
class SpecSubscriptTest {
    private fun compile(source: String) = Compiler().compile(source.trimIndent(), release = false)

    private fun run(source: String): String {
        val result = compile(source)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun aListIsIndexedThroughItsSpec() = assertEquals("9\n7", run(
        """
        import std.io
        import std.container.list
        func total(xs: List<Int>&): Int {
            var t = 0
            for i in 0..<xs.size { t += xs[i] }
            return t
        }
        func main() {
            var xs: MutableList<Int> = [4, 5]
            println(total(xs))
            xs[1] = 3
            println(total(xs))
        }
        """,
    ))

    @Test fun aMapIsReadAndWrittenThroughItsSpec() = assertEquals("one\n30\n3", run(
        """
        import std.io
        import std.container.map
        func main() {
            fin names: Map<Int, String> = [1: "one", 2: "two"]
            println(names[1])
            var values: MutableMap<Int, Int> = [1: 10]
            values[3] = 30
            println(values[3])
            values[1] = values[1] / 10 + 2
            println(values[1])
        }
        """,
    ))

    @Test fun aMissingKeyPanics() {
        val result = compile(
            """
            import std.io
            import std.container.map
            func main() {
                fin names: Map<Int, String> = [1: "one"]
                println(names[2])
            }
            """,
        )
        assertIs<CompilationResult.Success>(result)
        val failure = assertFailsWith<IllegalStateException> { IrInterpreter().interpret(result.ir) }
        assertTrue("key not found in map" in failure.message.orEmpty(), failure.message)
    }

    @Test fun aTypeWithoutASubscriptCannotBeIndexed() {
        val errors = assertIs<CompilationResult.Failure>(
            compile(
                """
                pack Point { var x: Int }
                pack Bag { var data: Array<Int> }
                oper[] Bag&.(i: Int): Int { return self.data[i] }
                oper[]= Bag!.(i: Int, v: Int) { self.data[i] = v }
                func main() {
                    fin p = Point(1)
                    fin a = p[0]
                    var b = Bag([1, 2])
                    b["x"] = 3
                    b[0] = "y"
                }
                """,
            ),
        ).errors
        for (expected in listOf(
            "line 7: 'Point' cannot be indexed - it declares no 'oper[] Point&.(index: …): …'",
            "line 9: the index of a 'Bag' subscript must be Int, got String",
            "line 10: the value of a 'Bag' subscript must be Int, got String",
        )) {
            assertTrue(expected in errors, "missing '$expected' in $errors")
        }
    }
}
