/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */

package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class AssertionNativeExecTest {
    @Test fun conditionRunsOnceAndPassingMessageIsLazyInLlvm() {
        assumeTrue("LLVM lli is required for native assertion execution", LlvmExec.available)
        for (optimized in listOf(false, true)) {
            val ir = AssertionSemanticsTest.lower(AssertionSemanticsTest.lazyMessageProgram, optimized)
            assertEquals("", LlvmExec.runIr(LlvmCodegen().generate(ir)))
        }
    }

    @Test fun singleStatementContractsAndMembersExecuteInLlvm() {
        assumeTrue("LLVM lli is required for native contract execution", LlvmExec.available)
        for (source in listOf(
            AssertionSemanticsTest.singleStatementContractProgram,
            AssertionSemanticsTest.singleStatementMemberProgram,
            AssertionSemanticsTest.groupedConditionProgram,
        )) {
            for (optimized in listOf(false, true)) {
                val ir = AssertionSemanticsTest.lower(source, optimized)
                assertEquals("", LlvmExec.runIr(LlvmCodegen().generate(ir)))
            }
        }
    }
}
