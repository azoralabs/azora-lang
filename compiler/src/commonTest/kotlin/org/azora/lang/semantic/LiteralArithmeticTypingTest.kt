/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.semantic

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Arithmetic on literals alone takes the type of the place it lands in, and
 * every literal in it must fit that type. Where nothing names a type it is an
 * `Int`, as a lone literal is; integer arithmetic never becomes a float
 * (`7 / 2` divides integers). `LiteralArithmeticExecTest` runs what compiles.
 */
class LiteralArithmeticTypingTest {
    private fun errors(body: String): List<String> {
        val result = Compiler().compile("import std.io\nfunc main() {\n$body\n}")
        return (result as? CompilationResult.Failure)?.errors.orEmpty()
    }

    private fun assertRejected(body: String, expected: String) {
        val found = errors(body)
        assertTrue(found.any { expected in it }, "expected \"$expected\" in $found")
    }

    private fun assertAccepted(body: String) {
        val result = Compiler().compile("import std.io\nfunc main() {\n$body\n}")
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
    }

    @Test fun aDeclaredWidthReadsEveryLiteralInIt() {
        assertAccepted("fin x: Cent = 4_611_686_018_427_387_904 * 8")
        assertAccepted("fin c: Byte = 100 + 27")
        assertAccepted("fin mask: UCent = (1 << 100) - 1")
        assertAccepted("fin seed: Cent = 3\nfin y = seed * (4_611_686_018_427_387_904 * 8)")
        assertAccepted("fin d: Double = (if true then 1.0 else 0.0) - 0.5")
    }

    @Test fun withNoWidthNamedTheLiteralsAreInts() {
        assertRejected("fin z = 4_611_686_018_427_387_904 * 8", "integer literal 4611686018427387904 does not fit Int")
        assertRejected("println(4_611_686_018_427_387_904 * 8)", "integer literal 4611686018427387904 does not fit Int")
    }

    @Test fun everyLiteralMustFitTheWidth() {
        assertRejected("fin c: Byte = 300 + 1", "integer literal 300 does not fit Byte")
        assertRejected("fin u: UInt = -1 + 0", "integer literal -1 does not fit UInt")
        assertRejected("fin small: Byte = 1\nfin s = small - (200 + 1)", "integer literal 200 does not fit Byte")
    }

    @Test fun integerArithmeticDoesNotBecomeAFloat() {
        assertRejected("fin d: Double = 7 / 2", "declared Double but initializer is Int")
        assertRejected("fin d: Double = (7 / 2) * 1.0", "declared Double but initializer is Float")
    }
}
