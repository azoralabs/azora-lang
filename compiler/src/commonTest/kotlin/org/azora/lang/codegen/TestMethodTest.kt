/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.azora.lang.codegen

import org.azora.lang.backend.IrInterpreter
import org.azora.lang.backend.JavaScriptCodegen
import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.frontend.AstValidator
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.TestMethod
import org.azora.lang.frontend.TopLevel
import org.azora.lang.ir.IrGenerator
import org.azora.lang.ir.IrStmt
import org.azora.lang.semantic.SemanticPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestMethodTest {
    private fun parse(source: String) = Parser(Lexer(source).tokenize()).parse()

    private fun lower(source: String) = parse(source).let { program ->
        val validationErrors = AstValidator().validate(program)
        check(validationErrors.isEmpty()) { validationErrors.joinToString("\n") }
        val semantic = SemanticPipeline().analyze(program)
        check(semantic.errors.isEmpty()) { semantic.errors.joinToString("\n") }
        IrGenerator(semantic.symbolTable).generate(semantic.program)
    }

    @Test fun testDefaultsToThis() {
        val test = parse("test \"one\" { assert true { \"ok\" } }")
            .items.filterIsInstance<TopLevel.Test>().single()
        assertEquals(TestMethod.This, test.method)
    }

    @Test fun explicitThisParses() {
        val test = parse("test .This \"one\" { assert true { \"ok\" } }")
            .items.filterIsInstance<TopLevel.Test>().single()
        assertEquals(TestMethod.This, test.method)
    }

    @Test fun allMayOmitBody() {
        val test = parse("test .All \"suite\"")
            .items.filterIsInstance<TopLevel.Test>().single()
        assertEquals(TestMethod.All, test.method)
        assertTrue(test.body.isEmpty())
    }

    @Test fun thisMayNotOmitBody() {
        val error = assertFailsWith<IllegalStateException> { parse("test .This \"one\"") }
        assertTrue("only 'test .All' may omit" in error.message.orEmpty(), error.message)
    }

    @Test fun unknownTestMethodIsRejected() {
        val error = assertFailsWith<IllegalStateException> { parse("test .File \"suite\"") }
        assertTrue("Unknown test method '.File'" in error.message.orEmpty(), error.message)
    }

    @Test fun allLowersToOneSuiteWithScopedChildren() {
        val ir = lower("""
            test "first" {
                fin value = 1
                assert value == 1 { "first" }
            }
            test "second" {
                fin value = 2
                assert value == 2 { "second" }
            }
            test .All "everything"
        """.trimIndent())

        assertEquals(listOf("everything"), ir.tests.map { it.name })
        assertEquals(2, ir.tests.single().body.filterIsInstance<IrStmt.Zone>().size)

        val javascript = JavaScriptCodegen().generate(ir)
        assertTrue("test(\"everything\"" in javascript, javascript)
        assertTrue("test(\"first\"" !in javascript, javascript)
        assertTrue("test(\"second\"" !in javascript, javascript)

        val llvm = LlvmCodegen().generate(ir)
        assertTrue("@test_everything" in llvm, llvm)
        assertTrue("@test_first" !in llvm, llvm)
        assertTrue("@test_second" !in llvm, llvm)
    }

    @Test fun allRunsEveryChildAsOnePassingResult() {
        val results = IrInterpreter().runTests(lower("""
            test "first" { assert true { "first" } }
            test "second" { assert 2 + 2 == 4 { "second" } }
            test .All "everything"
        """.trimIndent()))

        assertEquals(1, results.size)
        assertEquals("everything", results.single().name)
        assertTrue(results.single().passed, results.single().message)
    }

    @Test fun childFailureFailsTheAllSuite() {
        val results = IrInterpreter().runTests(lower("""
            test "passing" { assert true { "passing" } }
            test "failing" { assert false { "child failed" } }
            test .All "everything"
        """.trimIndent()))

        assertEquals(1, results.size)
        assertTrue(!results.single().passed)
        assertTrue("child failed" in results.single().message.orEmpty(), results.single().message)
    }

    @Test fun filesWithoutAllKeepIndependentTests() {
        val results = IrInterpreter().runTests(lower("""
            test "first" { assert true { "first" } }
            test "second" { assert true { "second" } }
        """.trimIndent()))

        assertEquals(listOf("first", "second"), results.map { it.name })
        assertTrue(results.all { it.passed })
    }
}
