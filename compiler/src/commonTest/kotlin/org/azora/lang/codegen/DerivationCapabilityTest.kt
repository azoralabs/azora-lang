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
 * OPERATOR_OVERLOADING_DIP §7.2: a derived `Equal`, `Order` or `Hash` is
 * field-wise, so it holds only when each field's type has it. `Double` is
 * `PartialOrder` and `PartialEqual` (NaN relates to nothing), so a pack of
 * doubles that derived `Order` had been accepted and `sort` then ordered NaN
 * as if it were ordered.
 */
class DerivationCapabilityTest {
    private fun compile(source: String) = Compiler().compile(source.trimIndent(), release = false)

    private fun refused(source: String, vararg words: String) {
        val result = compile(source)
        assertIs<CompilationResult.Failure>(result, "the derivation must be refused")
        assertTrue(
            result.errors.any { error -> words.all { it in error } },
            "expected an error naming ${words.toList()}, got: ${result.errors}",
        )
    }

    private fun run(source: String): String {
        val result = compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun orderOverADoubleFieldIsRefused() = refused(
        """
        import std.io
        import std.traits::{Order, Equal}
        pack Sample {
            var value: Double
        }
        derive Order for Sample
        func main() {
            println(Sample(1.0) < Sample(2.0))
        }
        """,
        "cannot derive 'Order' for 'Sample'", "field 'value: Double' is not Order", "PartialOrder",
    )

    @Test fun equalOverAFloatFieldIsRefused() = refused(
        """
        import std.traits::{Equal}
        pack Reading {
            var id: Int
            var level: Float
        }
        derive Equal for Reading
        func main() {}
        """,
        "cannot derive 'Equal' for 'Reading'", "field 'level: Float' is not Equal",
    )

    @Test fun aFieldWhosePackDoesNotDeriveItIsRefused() = refused(
        """
        import std.traits::{Order, Equal}
        pack Inner {
            var n: Int
        }
        pack Outer {
            var inner: Inner
        }
        derive Order for Outer
        func main() {}
        """,
        "cannot derive 'Order' for 'Outer'", "field 'inner: Inner' is not Order",
    )

    @Test fun fieldsThatHaveTheCapabilityDerive() {
        assertEquals("true\nfalse\ntrue", run("""
            import std.io
            import std.traits::{Order, Equal}
            pack Inner {
                var n: Int
            }
            derive Order for Inner
            pack Key {
                var name: String
                var inner: Inner
                var flag: Bool
                var mark: Char
            }
            derive Order for Key
            func main() {
                fin a = Key("a", Inner(1), true, 'x')
                fin b = Key("a", Inner(2), true, 'x')
                println(a < b)
                println(a == b)
                println(a == Key("a", Inner(1), true, 'x'))
            }
        """))
    }

    @Test fun aHandWrittenOrderOverDoublesIsTheAuthorsChoice() {
        assertEquals("true\nfalse", run("""
            import std.io
            import std.traits
            pack Sample {
                var value: Double
            }
            oper<=> Sample&.(rhs: Sample&): Compare {
                if self.value < rhs.value { return Compare.Less }
                if self.value > rhs.value { return Compare.Greater }
                return Compare.Equal
            }
            func main() {
                println(Sample(1.0) < Sample(2.0))
                println(Sample(3.0) < Sample(2.0))
            }
        """))
    }
}
