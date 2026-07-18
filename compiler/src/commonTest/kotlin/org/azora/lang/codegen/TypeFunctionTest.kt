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
            func result(): wider!(Int, Real) { return 1.0 }
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
            import std.traits
            func result(): std::promote!(Byte, Int, Long, Real) {
                return 1.0
            }
        """))
    }

    @Test
    fun stdlibPromoteRequiresTwoTypes() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            import std.traits
            func invalid(): std::promote!(Int) { return 1 }
        """))
        assertTrue(failure.errors.any { "requires T.length >= 2" in it }, failure.errors.toString())
    }

    @Test
    fun stdlibPromoteRequiresImport() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            func invalid(): std::promote!(Int, Real) { return 1.0 }
        """))
        assertTrue(failure.errors.any { "Unknown type function 'std__promote'" in it }, failure.errors.toString())
    }

    @Test
    fun fullyQualifiedStdlibPromoteDoesNotRequireImport() {
        assertIs<CompilationResult.Success>(compile("""
            func result(): std.traits.std::promote!(Int, Real) { return 1.0 }
        """))
    }

    @Test
    fun importingModuleDoesNotExposeBareZoneMember() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            import std.traits
            func invalid(): promote!(Int, Real) { return 1.0 }
        """))
        assertTrue(failure.errors.any { "Unknown type function 'promote'" in it }, failure.errors.toString())
    }

    @Test
    fun fixedTypeFunctionResolvesReturnType() {
        assertIs<CompilationResult.Success>(compile("""
            type wider(a: Type, b: Type) {
                return if a.rank >= b.rank { a } else { b }
            }
            func result(): wider!(Int, Real) {
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
            func result(): choose!(String, Int) {
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
            type forwarded(a: Type, b: Type) { return numericResult!(a, b) }
            func result(): forwarded!(Int, Real) { return 4.5 }
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
            func result(): widest!(Byte, Long, Real, Int) {
                return 3.5
            }
        """))
    }

    @Test
    fun genericFunctionCallUsesTypeFunctionForItsResult() {
        assertIs<CompilationResult.Success>(compile("""
            import std.traits
            func<T, U> greater(a: T, b: U): std::promote!(T, U) {
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
            func invalid(): widest!(Int) { return 1 }
        """))
        assertTrue(failure.errors.any { "requires types.length >= 2" in it }, failure.errors.toString())
    }

    @Test
    fun unknownTypeFunctionProducesDiagnostic() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            func invalid(): missing!(Int) { return 1 }
        """))
        assertTrue(failure.errors.any { "Unknown type function 'missing'" in it }, failure.errors.toString())
    }

    @Test
    fun recursiveTypeFunctionProducesDiagnostic() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            type first(value: Type) { return second!(value) }
            type second(value: Type) { return first!(value) }
            func invalid(): first!(Int) { return 1 }
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
}
