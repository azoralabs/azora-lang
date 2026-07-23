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

package org.azora.lang.semantic

import org.azora.lang.frontend.AstValidator
import org.azora.lang.frontend.DecoTarget
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecoratorTargetTest {
    private fun parseDeco(source: String): TopLevel.Deco =
        Parser(Lexer("$source\nfunc main() {}").tokenize()).parse().items.filterIsInstance<TopLevel.Deco>().single()

    private fun analyze(source: String): SemanticResult {
        val program = Parser(Lexer(source).tokenize()).parse()
        val validationErrors = AstValidator().validate(program)
        check(validationErrors.isEmpty()) { validationErrors.joinToString("\n") }
        return SemanticPipeline().analyze(program)
    }

    @Test fun allRequestedBindingFormsParse() {
        assertEquals(setOf(DecoTarget.Pack), parseDeco("deco A for .Pack bind X").targets)
        assertEquals(setOf(DecoTarget.Pack, DecoTarget.Func), parseDeco("deco A for [.Pack, .Func] bind X").targets)
        assertTrue(parseDeco("deco A for [.Pack, .Func]").bindings.isEmpty())

        val filtered = parseDeco("deco A for [.Pack, .Func] bind X for .Pack")
        assertEquals(setOf(DecoTarget.Pack), filtered.bindings.single().targets)

        val list = parseDeco("deco A for [.Pack, .Func] bind [X for .Pack, Y for .Func]")
        assertEquals(listOf("X", "Y"), list.bindings.map { it.name })
        assertEquals(setOf(DecoTarget.Func), list.bindings[1].targets)

        assertEquals(setOf(DecoTarget.Pack), parseDeco("deco A bind X for .Pack").bindings.single().targets)
        assertEquals(2, parseDeco("deco A bind [X for .Pack, Y for .Func]").bindings.size)
        assertEquals("X", parseDeco("deco A bind X").bindings.single().name)
    }

    @Test fun bodylessDecoratorParses() {
        val decorator = parseDeco("deco SerialIgnore")
        assertTrue(decorator.fields.isEmpty())
        assertTrue(decorator.bindings.isEmpty())
    }

    @Test fun decoratorChainsAndMixedBindingsExpandTransitively() {
        val result = analyze("""
            spec X<T>
            spec Y<T>
            deco A bind X
            deco B bind Y
            deco C bind A
            deco D bind [A, B]
            deco E bind [X, B]
            @C pack P
            @D pack Q
            @E pack R
            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(result.symbolTable.implements("P", "X", listOf(TypeRef.Named("P"))))
        assertTrue(result.symbolTable.implements("Q", "X", listOf(TypeRef.Named("Q"))))
        assertTrue(result.symbolTable.implements("Q", "Y", listOf(TypeRef.Named("Q"))))
        assertTrue(result.symbolTable.implements("R", "X", listOf(TypeRef.Named("R"))))
        assertTrue(result.symbolTable.implements("R", "Y", listOf(TypeRef.Named("R"))))
    }

    @Test fun recursiveDecoratorBindingsAreRejectedWithoutApplication() {
        val result = analyze("""
            deco A bind B
            deco B bind A
            func main() {}
        """.trimIndent())
        assertTrue(result.errors.any { "recursive decorator binding" in it && "A -> B -> A" in it }, result.errors.toString())
    }

    @Test fun duplicateTransitiveBindingsAreRejected() {
        val result = analyze("""
            spec X<T>
            deco A bind X
            deco B bind X
            deco D bind [A, B]
            func main() {}
        """.trimIndent())
        assertTrue(result.errors.any { "duplicate decorator binding" in it }, result.errors.toString())
    }

    @Test fun duplicateDecoratorReachedThroughDiamondIsRejected() {
        val result = analyze("""
            deco Shared
            deco A bind Shared
            deco B bind Shared
            deco Root bind [A, B]
            func main() {}
        """.trimIndent())
        assertTrue(result.errors.any { "duplicate decorator binding 'Shared'" in it }, result.errors.toString())
    }

    @Test fun targetDisjointBackEdgesDoNotFormCycle() {
        val result = analyze("""
            deco A bind B for .Pack
            deco B bind A for .Func
            func main() {}
        """.trimIndent())
        assertTrue(result.errors.isEmpty(), result.errors.toString())
    }

    @Test fun decoratorApplicationHonorsTargetConstraint() {
        val result = analyze("""
            deco PackOnly for .Pack
            @PackOnly
            func wrong() {}
            func main() {}
        """.trimIndent())
        assertTrue(result.errors.any { "cannot target .Func" in it }, result.errors.toString())
    }

    @Test fun fieldAndParameterTargetsAreRecognized() {
        val result = analyze("""
            deco OnField for .Field
            deco OnParam for .Param
            pack P {
                @OnField
                fin value: Int
            }
            func read(value: @OnParam Int): Int { return value }
            func main() {}
        """.trimIndent())
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(result.symbolTable.implements("P.value", "OnField"))
        assertTrue(result.symbolTable.implements("read.value", "OnParam"))
    }

    @Test fun duplicateContractDeclarationIsRejected() {
        val result = analyze("""
            spec A<T>
            deco A
            func main() {}
        """.trimIndent())
        assertTrue(result.errors.any { "duplicate spec or decorator 'A'" in it }, result.errors.toString())
    }

    @Test fun unreachableBindingFilterIsRejected() {
        val result = analyze("""
            spec X<T>
            deco A for .Pack bind X for .Func
            func main() {}
        """.trimIndent())
        assertTrue(result.errors.any { "can never match" in it }, result.errors.toString())
    }
}
