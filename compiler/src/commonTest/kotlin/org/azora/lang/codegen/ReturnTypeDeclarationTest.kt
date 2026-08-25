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

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A declaration's return type is never inferred (`DIPs/DO_NOT_INFER_RETURN_TYPE.MD`).
 *
 * An omitted return type is not a request to work it out - it *is* the
 * annotation, and it says `Unit`. Two things are deliberately left out of the
 * rule: a **lambda**, which has no declaration to read, and an **operator
 * overload**, whose result is fixed by the operator's contract.
 */
class ReturnTypeDeclarationTest {
    private fun compile(source: String): CompilationResult = Compiler().compile(source.trimIndent())

    private fun run(source: String): String {
        val result = compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun rejects(source: String, needle: String) {
        val failure = assertIs<CompilationResult.Failure>(compile(source))
        assertTrue(failure.errors.any { needle in it }, failure.errors.toString())
    }

    // -- func ----------------------------------------------------------------

    @Test fun anOmittedReturnTypeMeansUnit() = assertEquals("Hello!", run("""
        import std.io
        func hello() {
            println("Hello!")
        }
        func main() { hello() }
    """))

    @Test fun returningAValueWithoutDeclaringOneIsRejected() = rejects("""
        func add(a: Int, b: Int) {
            return a + b
        }
    """, "'add' returns Int but declares no return type")

    @Test fun theDiagnosticNamesTheFix() = rejects("""
        func add(a: Int, b: Int) {
            return a + b
        }
    """, "declare it as ': Int'")

    @Test fun declaringTheReturnTypeAcceptsIt() = assertEquals("7", run("""
        import std.io
        func add(a: Int, b: Int): Int {
            return a + b
        }
        func main() { println(add(3, 4)) }
    """))

    @Test fun aBareReturnIsStillFineInAUnitFunction() = assertEquals("early", run("""
        import std.io
        func report(quiet: Bool) {
            println("early")
            if quiet { return }
            println("loud")
        }
        func main() { report(true) }
    """))

    // -- failable shorthand --------------------------------------------------

    @Test fun aFailableFunctionMayOmitTheOkTypeAndIsUnit() = assertEquals("3", run("""
        import std.io
        error MathError { DivisionByZero }
        func div(x: Int, y: Int) ?! MathError {
            if y == 0 { return .DivisionByZero }
            println(x / y)
        }
        func main() {
            try { div(9, 3) } catch { e -> println("failed") }
        }
    """))

    @Test fun aFailableFunctionThatYieldsAValueMustDeclareIt() = rejects("""
        error MathError { DivisionByZero }
        func div(x: Int, y: Int) ?! MathError {
            return x / y
        }
    """, "declares no return type")

    @Test fun aDeclaredFailableReturnTypeIsAccepted() = assertEquals("3", run("""
        import std.io
        error MathError { DivisionByZero }
        func div(x: Int, y: Int): Int ?! MathError {
            if y == 0 { return .DivisionByZero }
            return x / y
        }
        func main() {
            try { println(div(9, 3)) } catch { e -> println("failed") }
        }
    """))

    // -- branching in return position ---------------------------------------
    //
    // `return if` and `return when` desugar to the statement forms with each
    // branch returning, so a branch accepts everything a `return` accepts -
    // including the `.Variant` error shorthand, which is not a value and so
    // cannot appear in an if-*expression*.

    @Test fun aReturnIfBranchTakesTheErrorShorthand() = assertEquals("4\ncaught", run("""
        import std.io
        error MathError { DivisionByZero }
        func div(x: Int, y: Int): Int ?! MathError {
            return if y == 0 { .DivisionByZero } else { x / y }
        }
        func main() {
            try { println(div(8, 2)) } catch { e -> println("caught") }
            try { println(div(8, 0)) } catch { e -> println("caught") }
        }
    """))

    @Test fun aReturnWhenArmTakesTheErrorShorthand() = assertEquals("8\ncaught", run("""
        import std.io
        error MathError { Bad }
        func twice(n: Int): Int ?! MathError {
            return when n { 0 -> .Bad
                else -> n * 2 }
        }
        func main() {
            try { println(twice(4)) } catch { e -> println("caught") }
            try { println(twice(0)) } catch { e -> println("caught") }
        }
    """))

    @Test fun aReturnIfStillCarriesPlainValues() = assertEquals("1\n2", run("""
        import std.io
        func pick(c: Bool): Int {
            return if c { 1 } else { 2 }
        }
        func main() {
            println(pick(true))
            println(pick(false))
        }
    """))

    @Test fun aReturnIfChainsThroughElseIf() = assertEquals("0\n1\n2", run("""
        import std.io
        func sign(n: Int): Int {
            return if n < 0 { 0 } else if n == 0 { 1 } else { 2 }
        }
        func main() {
            println(sign(-4))
            println(sign(0))
            println(sign(9))
        }
    """))

    @Test fun aReturnIfBranchMayComputeWithLocals() = assertEquals("7", run("""
        import std.io
        func f(y: Int): Int {
            return if y == 0 { 0 } else {
                fin doubled = y * 2
                doubled + 1
            }
        }
        func main() { println(f(3)) }
    """))

    @Test fun aReturnIfNeedsBothBranches() = rejects("""
        func pick(c: Bool): Int {
            return if c { 1 }
        }
    """, "needs an 'else'")

    // -- prop ----------------------------------------------------------------

    @Test fun aPropFollowsTheSameRule() = rejects("""
        pack Counter { var n: Int }
        impl Counter {
            prop &.doubled {
                return self.n * 2
            }
        }
    """, "must declare its type")

    @Test fun aDeclaredPropIsAccepted() = assertEquals("10", run("""
        import std.io
        pack Counter { var n: Int }
        impl Counter {
            prop &.doubled: Int {
                return self.n * 2
            }
        }
        func main() { println(Counter(5).doubled) }
    """))

    // -- the two exceptions --------------------------------------------------

    @Test fun aLambdaStillInfersItsResult() = assertEquals("8", run("""
        import std.io
        func main() {
            fin twice = { n: Int -> n * 2 }
            println(twice(4))
        }
    """))

    @Test fun anOperatorStillInfersItsResult() = assertEquals("5", run("""
        import std.io
        pack Box { var value: Int }
        impl Box {
            oper.* &.() {
                return self.value
            }
        }
        func main() { println(*Box(5)) }
    """))
}
