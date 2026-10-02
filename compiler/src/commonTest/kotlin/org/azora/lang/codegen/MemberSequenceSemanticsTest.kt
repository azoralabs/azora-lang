/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ordered mutable calls used in quantum.az's Grover circuit construction. */
class MemberSequenceSemanticsTest {
    companion object {
        internal val program = """
            bridge oper.. Int&.(rhs: Int&) by 1
            pack CircuitProbe { var order: Int }
            impl CircuitProbe {
                func !.h(target: Int) scope self.order = self.order * 10 + target
                func !.x(target: Int) scope self.order = self.order * 10 + target + 1
            }
            func main() {
                var result = CircuitProbe(0)
                for j: Int in 1..<2 {
                    result.h(j)
                    result.x(j)
                }
                assert result.order == 12 panic "H then X order changed"
                for j: Int in 1..<2 {
                    result.x(j)
                    result.h(j)
                }
                assert result.order == 1221 panic "X then H order changed"
            }
        """.trimIndent()
    }

    @Test fun orderedMutableCallsPreserveTheReceiverAndLoopArgument() {
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(AssertionSemanticsTest.lower(program, optimized)))
        }
    }
}
