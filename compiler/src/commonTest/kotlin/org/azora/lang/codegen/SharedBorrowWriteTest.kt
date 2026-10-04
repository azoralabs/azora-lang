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
 * A shared borrow (`p: T&`, or the receiver of a `&.` member) promises whoever
 * lent the value that it will look the same afterwards. Only a direct field
 * assignment through `self&` used to be refused: through a `&` parameter every
 * write compiled, and through `self&` an element write or a call to a member
 * that changes its receiver did. A `fin` binding could call such a member too.
 *
 * A spec states how each member takes its receiver, and an implementation may
 * promise more but not less: an `!.` member cannot implement a `&.`
 * requirement, and a member of the type cannot implement a member of a value.
 */
class SharedBorrowWriteTest {
    private val counter = """
        import std.io
        pack Counter {
            var n: Int
            var data: Array<Int>
        }
        impl Counter {
            func !.inc() {
                self.n += 1
            }
            func &.read(): Int {
                return self.n
            }
        }
    """.trimIndent()

    private fun errors(body: String): List<String> =
        assertIs<CompilationResult.Failure>(Compiler().compile(counter + "\n" + body.trimIndent())).errors

    private fun run(body: String): String {
        val result = Compiler().compile(counter + "\n" + body.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun assertRefused(body: String, vararg expected: String) {
        val found = errors(body)
        for (message in expected) assertTrue(found.any { message in it }, "missing '$message' in $found")
    }

    private val sharedParam = "'c' is a shared borrow, which may not be changed; borrow it exclusively ('!') to change it"

    @Test fun nothingChangesThroughASharedParameter() = assertRefused(
        """
        func look(c: Counter&) {
            c.n = 2
            c.n += 1
            c.data[0] = 5
            c.inc()
        }
        func main() {}
        """,
        "line 15: cannot assign to member 'n' through 'c' - $sharedParam",
        "line 16: cannot assign to member 'n' through 'c' - $sharedParam",
        "line 17: cannot assign by index through 'c' - $sharedParam",
        "line 18: cannot call 'inc', which changes its receiver, through 'c' - $sharedParam",
    )

    @Test fun nothingChangesThroughASharedReceiver() = assertRefused(
        """
        impl Counter {
            func &.poke() {
                self.data[0] = 9
            }
            func &.bump() {
                self.inc()
            }
        }
        func main() {}
        """,
        "cannot assign by index through 'self' - 'self' is a shared receiver ('self&'), which may not be changed; " +
            "declare the member with a '!.' receiver to change it",
        "cannot call 'inc', which changes its receiver, through 'self'",
    )

    @Test fun aSharedParameterCannotBeLentExclusively() = assertRefused(
        """
        func grow(c: Counter!) {
            c.inc()
        }
        func look(c: Counter&) {
            grow(c)
        }
        func main() {}
        """,
        "cannot borrow mutably for parameter 'c' through 'c' - $sharedParam",
    )

    @Test fun aFrozenBindingCannotCallAMemberThatChangesIt() = assertRefused(
        """
        func main() {
            fin c = Counter(0, [1])
            c.inc()
        }
        """,
        "cannot call 'inc', which changes its receiver, through 'c' - its value is immutable",
    )

    @Test fun exclusiveAccessStillChangesTheValue() = assertEquals("2\n3", run(
        """
        func grow(c: Counter!) {
            c.inc()
            c.data[0] = c.n
        }
        func look(c: Counter&): Int {
            return c.read() + c.data[0] - c.n
        }
        func main() {
            var c = Counter(1, [0])
            grow(c)
            c.inc()
            println(look(c))
            println(c.n)
        }
        """,
    ))

    @Test fun anExclusiveMemberCannotImplementASharedRequirement() = assertRefused(
        """
        spec Reader {
            func &.value(): Int
        }
        impl Reader for Counter {
            func !.value(): Int {
                self.n += 1
                return self.n
            }
        }
        func main() {}
        """,
        "'Counter.value' changes its receiver ('!.'), but 'Reader.value' promises callers it only reads it ('&.')",
    )

    @Test fun aSharedMemberImplementsAnExclusiveRequirement() = assertEquals("4", run(
        """
        spec Stepper {
            func !.step(): Int
        }
        impl Stepper for Counter {
            func &.step(): Int {
                return self.n + 1
            }
        }
        func main() {
            var c = Counter(3, [0])
            println(c.step())
        }
        """,
    ))

    @Test fun aMemberOfTheTypeCannotImplementAMemberOfAValue() = assertRefused(
        """
        spec Reader {
            func &.value(): Int
        }
        impl Reader for Counter {
            func value(): Int {
                return 7
            }
        }
        func main() {}
        """,
        "'Counter.value' is written without a receiver, so it is a member of the type, but 'Reader.value' is a " +
            "member of a value; write 'func &.value(…)'",
    )

    @Test fun aSpecTypedValueChangesOnlyThroughExclusiveAccess() = assertRefused(
        """
        spec Stepper {
            func !.step(): Int
        }
        impl Stepper for Counter {
            func !.step(): Int {
                self.n += 1
                return self.n
            }
        }
        func main() {
            fin s: Stepper = Counter(0, [0])
            s.step()
        }
        """,
        "cannot call 'step', which changes its receiver, through 's' - its value is immutable",
    )
}
