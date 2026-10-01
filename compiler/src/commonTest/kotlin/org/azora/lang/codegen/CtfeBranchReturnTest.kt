/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A call with constant arguments is folded only when the evaluator can follow
 * the branch it takes.
 *
 * The evaluator answered `null` both when a block ran off its end and when it
 * met something it could not evaluate. A taken branch it could not follow -
 * `return null`, an `assert` - therefore read as falling through, and the call
 * folded to the `return` after the `if`: `findName(0) ?? "anonymous"` printed
 * the name.
 */
class CtfeBranchReturnTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun anEarlyReturnOfNullIsTaken() = assertEquals("anonymous\n-1\nAda", run("""
        import std.io
        func findName(id: Int): String? {
            if id == 0 { return null }
            return "Ada"
        }
        func findCount(id: Int): Int? {
            if id == 0 {
                return null
            }
            return 5
        }
        func main() {
            println(findName(0) ?? "anonymous")
            println(findCount(0) ?? -1)
            println(findName(1) ?? "anonymous")
        }
    """))

    @Test fun aBranchTheEvaluatorCannotFollowIsLeftForRuntime() = assertEquals("checked", run("""
        import std.io
        func label(n: Int): String {
            if n == 0 {
                assert n == 0 panic "unreachable"
                return "checked"
            }
            return "plain"
        }
        func main() {
            println(label(0))
        }
    """))

    @Test fun aConstantBranchStillFolds() = assertEquals("none\nAda", run("""
        import std.io
        func pick(id: Int): String {
            if id == 0 { return "none" }
            return "Ada"
        }
        func main() {
            println(pick(0))
            println(pick(1))
        }
    """))
}
