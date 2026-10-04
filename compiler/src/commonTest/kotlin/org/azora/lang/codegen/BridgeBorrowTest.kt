/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A `bridge func`'s parameters borrow as a function's do.
 *
 * Bridge signatures were registered without their borrow modes, so every
 * parameter took ownership: `complete(model, chat)` in `std.ai` demanded
 * `take model`, although the signature says `model: AiModel&`, and one model
 * connection could serve a single request.
 */
class BridgeBorrowTest {
    // A pack that owns heap storage is not `Copy`, so passing it decides who owns it.
    private val declarations = """
        import std.container.list
        pack Session { var items: ArrayList<Int> }
        bridge func inspect(session: Session&): Int
        bridge func consume(session: Session): Int
        bridge func reset(session: Session!): Int
    """.trimIndent()

    private fun compile(body: String) = Compiler().compile(declarations + "\n" + body.trimIndent())

    @Test fun aSharedParameterBorrows() {
        val result = compile(
            """
            func main() {
                fin s = Session(ArrayList<Int>())
                fin a = inspect(s)
                fin b = inspect(s)
            }
            """,
        )
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
    }

    @Test fun anOwnedParameterStillTakes() {
        val errors = assertIs<CompilationResult.Failure>(
            compile(
                """
                func main() {
                    fin s = Session(ArrayList<Int>())
                    fin a = consume(s)
                }
                """,
            ),
        ).errors
        assertTrue(errors.any { "cannot pass 's' by ownership" in it }, errors.toString())
    }

    @Test fun anExclusiveParameterNeedsAValueThatMayChange() {
        val errors = assertIs<CompilationResult.Failure>(
            compile(
                """
                func main() {
                    fin s = Session(ArrayList<Int>())
                    fin a = reset(s)
                }
                """,
            ),
        ).errors
        assertTrue(errors.isNotEmpty(), "a fin binding passed to a '!' parameter must be refused")
    }
}
