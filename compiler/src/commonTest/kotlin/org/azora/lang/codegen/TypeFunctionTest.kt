package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TypeFunctionCall
import org.azora.lang.frontend.TypeFunctionStmt
import org.azora.lang.frontend.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TypeFunctionTest {
    private fun compile(source: String): CompilationResult = Compiler().compile(source.trimIndent())

    @Test
    fun typeIsAKeyword() {
        val tokens = Lexer("type wider(a: Type, b: Type) { return a }").tokenize()
        assertEquals(TokenType.TYPE, tokens.first().type)
    }

    @Test
    fun parserRetainsStructuredTypeFunctionDeclaration() {
        val program = Parser(Lexer("""
            type wider(a: Type, b: Type) {
                return if a.rank >= b.rank { a } else { b }
            }
            func result(): wider@(Int, Real) { return 1.0 }
        """.trimIndent()).tokenize()).parse()

        val declaration = program.typeFunctions.single()
        assertEquals("wider", declaration.name)
        assertEquals(listOf("a", "b"), declaration.params.map { it.name })
        assertIs<TypeFunctionStmt.Return>(declaration.body.single())
        val returnType = assertIs<TypeRef.Named>(program.functions.single().returnType.let {
            (it as org.azora.lang.frontend.TypeAnnotation.Explicit).ref
        })
        assertTrue(TypeFunctionCall.isCall(returnType))
        assertEquals("wider", TypeFunctionCall.name(returnType))
    }

    @Test
    fun stdlibPromoteSelectsHighestRankedType() {
        assertIs<CompilationResult.Success>(compile("""
            use std.traits
            func result(): std::promote@(Byte, Int, Long, Real) {
                return 1.0
            }
        """))
    }

    @Test
    fun stdlibPromoteRequiresTwoTypes() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            use std.traits
            func invalid(): std::promote@(Int) { return 1 }
        """))
        assertTrue(failure.errors.any { "'T.length >= 2'" in it }, failure.errors.toString())
    }

    @Test
    fun stdlibPromoteRequiresImport() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            func invalid(): std::promote@(Int, Real) { return 1.0 }
        """))
        assertTrue(failure.errors.any { "Unknown type function 'std__promote'" in it }, failure.errors.toString())
    }

    @Test
    fun fullyQualifiedStdlibPromoteDoesNotRequireImport() {
        assertIs<CompilationResult.Success>(compile("""
            func result(): std.traits.std::promote@(Int, Real) { return 1.0 }
        """))
    }

    @Test
    fun importingModuleDoesNotExposeBareZoneMember() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            use std.traits
            func invalid(): promote@(Int, Real) { return 1.0 }
        """))
        assertTrue(failure.errors.any { "Unknown type function 'promote'" in it }, failure.errors.toString())
    }

    @Test
    fun fixedTypeFunctionResolvesReturnType() {
        assertIs<CompilationResult.Success>(compile("""
            type wider(a: Type, b: Type) {
                return if a.rank >= b.rank { a } else { b }
            }
            func result(): wider@(Int, Real) {
                return 2.5
            }
        """))
    }

    @Test
    fun exactOverloadWinsBeforeVariadicOverload() {
        assertIs<CompilationResult.Success>(compile("""
            type choose(a: Type, b: Type) { return a }
            type choose(types: ...Type) where types.length >= 2 {
                return types.1
            }
            func result(): choose@(String, Int) {
                return "fixed"
            }
        """))
    }

    @Test
    fun typeFunctionsCanCallOtherTypeFunctions() {
        assertIs<CompilationResult.Success>(compile("""
            type numericResult(a: Type, b: Type) {
                return if a.rank >= b.rank { a } else { b }
            }
            type forwarded(a: Type, b: Type) { return numericResult@(a, b) }
            func result(): forwarded@(Int, Real) { return 4.5 }
        """))
    }

    @Test
    fun variadicTypeFunctionSupportsBindingsLoopsAndRank() {
        assertIs<CompilationResult.Success>(compile("""
            type widest(types: ...Type) where types.length >= 2 {
                let Result: Type = types.0
                for Candidate in types[1...] {
                    Result = if Candidate.rank > Result.rank { Candidate } else { Result }
                }
                return Result
            }
            func result(): widest@(Byte, Long, Real, Int) {
                return 3.5
            }
        """))
    }

    @Test
    fun genericFunctionCallUsesTypeFunctionForItsResult() {
        assertIs<CompilationResult.Success>(compile("""
            use std.traits
            func greater<T, U>(a: T, b: U): std::promote@(T, U) {
                return a + b
            }
            func main() {
                fin result: Real = greater(1, 2.5)
            }
        """))
    }

    @Test
    fun variadicConstraintProducesDiagnostic() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            type widest(types: ...Type) where types.length >= 2 {
                return types.0
            }
            func invalid(): widest@(Int) { return 1 }
        """))
        assertTrue(failure.errors.any { "'types.length >= 2'" in it }, failure.errors.toString())
    }

    @Test
    fun unknownTypeFunctionProducesDiagnostic() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            func invalid(): missing@(Int) { return 1 }
        """))
        assertTrue(failure.errors.any { "Unknown type function 'missing'" in it }, failure.errors.toString())
    }

    @Test
    fun recursiveTypeFunctionProducesDiagnostic() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            type first(value: Type) { return second@(value) }
            type second(value: Type) { return first@(value) }
            func invalid(): first@(Int) { return 1 }
        """))
        assertTrue(failure.errors.any { "Recursive type-function call" in it }, failure.errors.toString())
    }

    @Test
    fun duplicateOverloadIsRejectedByParser() {
        assertFailsWith<IllegalStateException> {
            Parser(Lexer("""
                type same(value: Type) { return value }
                type same(other: Type) { return other }
            """.trimIndent()).tokenize()).parse()
        }
    }
    // ------------------------------------------------------------------
    // Applicability vs. validation
    //
    // A constraint decides two different questions: "is this the right
    // overload" during selection, and "is this specialization legal" after it.
    // The first must stay silent so another overload gets its turn.
    // ------------------------------------------------------------------

    @Test
    fun aConstraintViolatingCandidateIsSkippedWhenAnotherApplies() {
        // One argument cannot satisfy `.length >= 2`, so selection must fall through
        // to the single-argument overload rather than report a constraint error.
        assertIs<CompilationResult.Success>(compile("""
            type pick(types: ...Type) where types.length >= 2 {
                return types.1
            }
            type pick(only: Type) {
                return only
            }
            func one(): pick@(Int) { return 1 }
            func two(): pick@(Int, Real) { return 1.0 }
        """))
    }

    @Test
    fun aConcreteInvalidSpecializationIsAnError() {
        // With no applicable alternative, the same violation is the user's error.
        val failure = assertIs<CompilationResult.Failure>(compile("""
            type onlyPairs(types: ...Type) where types.length >= 2 {
                return types.0
            }
            func invalid(): onlyPairs@(Int) { return 1 }
        """))
        assertTrue(
            failure.errors.any { "'types.length >= 2'" in it },
            failure.errors.toString(),
        )
    }

    @Test
    fun variadicOverloadSelectionPrefersTheLongestFixedPrefix() {
        // Selection order is unchanged by the split: the candidate with the most
        // fixed parameters before its pack still wins.
        assertIs<CompilationResult.Success>(compile("""
            type tagged(first: Type, rest: ...Type) {
                return first
            }
            type tagged(rest: ...Type) {
                return rest.0
            }
            func chosen(): tagged@(Int, Real) { return 1 }
        """))
    }

    // ------------------------------------------------------------------
    // Ordinary (non-variadic) generic instantiation
    //
    // Validated once per unique resolved combination, where a declared type is
    // resolved — never during parsing or overload filtering.
    // ------------------------------------------------------------------

    private val vecDecl = """
        pack Vec<T, N: Int> where T is Number && N in 2..4 {
            var x: T = 0
            var y: T = 0
        }
    """.trimIndent()

    @Test
    fun genericSatisfyingItsConstraintsIsAccepted() {
        assertIs<CompilationResult.Success>(compile("""
            $vecDecl
            func makeA(): Int { fin v: Vec<Int, 2> = Vec<Int, 2>(1, 2) return v.x }
        """))
    }

    @Test
    fun genericWithADifferentSatisfyingCombinationIsAccepted() {
        assertIs<CompilationResult.Success>(compile("""
            $vecDecl
            func makeB(): Float { fin v: Vec<Float, 4> = Vec<Float, 4>(1.0, 2.0) return v.x }
        """))
    }

    @Test
    fun genericViolatingNominalConformanceIsRejected() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            $vecDecl
            func makeC(): String { fin v: Vec<String, 2> = Vec<String, 2>("a", "b") return v.x }
        """))
        assertTrue(
            failure.errors.any { "String does not implement Number" in it },
            failure.errors.toString(),
        )
    }

    @Test
    fun genericViolatingAConstRangeIsRejected() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            $vecDecl
            func makeD(): Int { fin v: Vec<Int, 9> = Vec<Int, 9>(1, 2) return v.x }
        """))
        assertTrue(
            failure.errors.any { "'N in 2..4'" in it },
            failure.errors.toString(),
        )
    }

    @Test
    fun repeatedUseOfOneCombinationReportsOnce() {
        // The violation is cached with the specialization, so many uses give one error.
        val failure = assertIs<CompilationResult.Failure>(compile("""
            $vecDecl
            func a(): Int { fin v: Vec<Int, 9> = Vec<Int, 9>(1, 2) return v.x }
            func b(): Int { fin v: Vec<Int, 9> = Vec<Int, 9>(3, 4) return v.x }
            func c(): Int { fin v: Vec<Int, 9> = Vec<Int, 9>(5, 6) return v.x }
        """))
        assertEquals(
            1,
            failure.errors.count { "'N in 2..4'" in it },
            failure.errors.toString(),
        )
    }

    @Test
    fun aGenericContainingAConstrainedTypeStaysUnresolved() {
        // `Vec<T, N>` inside a generic says nothing until T and N are concrete;
        // validating it there would reject every generic mentioning a constrained type.
        assertIs<CompilationResult.Success>(compile("""
            $vecDecl
            pack Holder<T, N: Int> {
                var inner: Vec<T, N>
            }
        """))
    }

}
