/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.semantic

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ENUMS_DIP §6: `enum Key: Int { W(87) }` gives each case a fixed value of the
 * declared type (a tuple type takes one value per element), read with
 * `key.value` and looked up with `raw as? Key`. These are the declarations and
 * uses the checker refuses; `EnumValueExecTest` runs the accepted ones on every
 * backend.
 */
class EnumValueTest {
    private fun output(source: String): String {
        val result = Compiler().compile(source.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun rejected(source: String): List<String> {
        val result = Compiler().compile(source.trimIndent())
        return assertIs<CompilationResult.Failure>(result, "the program compiled").errors
    }

    private fun assertRejected(source: String, expected: String) {
        val errors = rejected(source)
        assertTrue(errors.any { expected in it }, "expected \"$expected\" in $errors")
    }

    @Test fun aCaseReadsItsValueAndAValueFindsItsCase() {
        assertEquals(
            "87\n1001\ntrue\ntrue",
            output(
                """
                import std.io
                enum Key: Int {
                    W(87)
                    Left(1001)
                }
                func main() {
                    fin k = Key.Left
                    println(Key.W.value)
                    println(k.value)
                    println((87 as? Key) == Key.W)
                    println((88 as? Key) == null)
                }
                """,
            ),
        )
    }

    @Test fun aPlainEnumCaseStillCannotCarryAPayload() {
        assertRejected(
            "enum Color {\n    Red(1)\n}\nfunc main() {}",
            "write 'variant enum Color { … }' for a tagged union, or 'enum Color: Type { Red(value) }' for a fixed value",
        )
    }

    @Test fun everyCaseOfAValuedEnumWritesItsValue() {
        assertRejected("enum Key: Int {\n    W(87)\n    S\n}\nfunc main() {}", "case 'S' of enum 'Key' has no value - write 'S(…)' with its 'Int'")
    }

    @Test fun aTupleTypeTakesOneValuePerElement() {
        assertRejected(
            "enum Planet: (Int, String) {\n    Mercury(1)\n}\nfunc main() {}",
            "case 'Mercury' of enum 'Planet' has 1 value; '(Int, String)' takes 2",
        )
    }

    @Test fun aValueIsALiteralOfItsType() {
        assertRejected(
            "enum Key: Int {\n    W(\"w\")\n}\nfunc main() {}",
            "the value of case 'W' of enum 'Key' must be a 'Int' literal",
        )
        assertRejected(
            "fin base = 80\nenum Key: Int {\n    W(base)\n}\nfunc main() {}",
            "the value of case 'W' of enum 'Key' must be a 'Int' literal",
        )
    }

    @Test fun aValueFitsItsType() {
        assertRejected("enum Small: Byte {\n    Big(128)\n}\nfunc main() {}", "integer literal 128 does not fit Byte")
    }

    @Test fun twoCasesCannotShareAValue() {
        // `0x57` is 87 written differently; the value is what is compared.
        assertRejected(
            "enum Key: Int {\n    W(87)\n    Up(0x57)\n}\nfunc main() {}",
            "cases 'W' and 'Up' of enum 'Key' have the same value 87 - each case's value must be distinct",
        )
        assertRejected(
            "enum Planet: (Int, String) {\n    A(1, \"x\")\n    B(1, \"x\")\n}\nfunc main() {}",
            "cases 'A' and 'B' of enum 'Planet' have the same value",
        )
    }

    @Test fun onlyLiteralTypesCanBeValues() {
        assertRejected(
            "pack Box {\n    fin n: Int\n}\nenum Kind: Box {\n    A(1)\n}\nfunc main() {}",
            "enum 'Kind' cannot carry 'Box' values",
        )
    }

    @Test fun aPlainCastToAValuedEnumIsRefused() {
        assertRejected(
            "enum Key: Int {\n    W(87)\n}\nfunc main() {\n    fin k = 87 as Key\n}",
            "'Int' cannot be cast to 'Key' with 'as' - a value may name no case; write 'as? Key', which is 'Key?'",
        )
    }

    @Test fun aLookupTakesTheValueType() {
        assertRejected(
            "enum Key: Int {\n    W(87)\n}\nfunc main() {\n    fin k = \"w\" as? Key\n}",
            "'as? Key' finds a case by its 'Int' value, got 'String'",
        )
    }

    @Test fun anEnumCaseIsNotANumber() {
        assertRejected(
            "enum Key: Int {\n    W(87)\n}\nfunc main() {\n    fin n = Key.W as Int\n}",
            "an enum case is not a number - 'Key' cannot be cast to 'Int'; read its value with '.value'",
        )
        assertRejected(
            "enum Color {\n    Red\n}\nfunc main() {\n    fin n = Color.Red as Int\n}",
            "give the enum values with 'enum Color: Int { Case(value) }'",
        )
    }

    @Test fun bridgeTargetsAreListedInParentheses() {
        val accepted = Compiler().compile("bridge (.C, .WebAssembly) {\n    func hostTick(): Long\n}\nfunc main() {}")
        assertIs<CompilationResult.Success>(accepted, (accepted as? CompilationResult.Failure)?.errors.toString())
        assertRejected(
            "bridge [.C, .WebAssembly] {\n    func hostTick(): Long\n}\nfunc main() {}",
            "bridge targets are listed in parentheses",
        )
    }
}
