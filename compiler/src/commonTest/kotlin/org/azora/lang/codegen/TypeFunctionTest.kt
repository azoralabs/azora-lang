package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TypeFunctionStmt
import org.azora.lang.frontend.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Compile-time type properties: `deepinline prop Name<...T>: Type { … }`.
 *
 * A use site spells one exactly like a generic type (`promote<T, U>`), so most
 * of what is checked here is that the two are told apart correctly.
 */
class TypeFunctionTest {
    private fun compile(source: String): CompilationResult = Compiler().compile(source.trimIndent())

    @Test
    fun typeIsNoLongerAKeyword() {
        val tokens = Lexer("type").tokenize()
        assertEquals(TokenType.IDENTIFIER, tokens.first().type)
    }

    @Test
    fun parserRetainsStructuredTypePropertyDeclaration() {
        val program = Parser(Lexer("""
            deepinline prop Wider<A, B>: std::Type {
                return if A.rank >= B.rank { A } else { B }
            }
            func result(): Wider<std::Int, std::Double> { return 1.0 }
        """.trimIndent()).tokenize()).parse()

        val declaration = program.typeFunctions.single()
        assertEquals("Wider", declaration.name)
        assertEquals(listOf("A", "B"), declaration.params.map { it.name })
        assertIs<TypeFunctionStmt.Return>(declaration.body.single())
        // The call stays an ordinary named type through parsing: only the
        // declaration set knows the name belongs to a type property, and that set
        // is not complete until the stdlib has been injected.
        val returnType = assertIs<TypeRef.Named>(program.functions.single().returnType.let {
            (it as org.azora.lang.frontend.TypeAnnotation.Explicit).ref
        })
        assertEquals("Wider", returnType.name)
        assertEquals(listOf("Int", "Double"), returnType.args.map { (it as TypeRef.Named).name })
    }

    @Test
    fun aTypePropertyNameMayBeLowercase() {
        // A type property names a type, but it is not required to look like one:
        // `std::promote<T, U>` reads better than `std::Promote<T, U>` for
        // something that computes a type rather than declaring one.
        val program = Parser(Lexer("deepinline prop wider<A, B>: Type { return A }").tokenize()).parse()
        assertEquals("wider", program.typeFunctions.single().name)
    }

    @Test
    fun aTypePropertyMustDeclareTypeAsItsResult() {
        val failure = assertFailsWith<IllegalStateException> {
            Parser(Lexer("deepinline prop Wider<A, B>: Int { return A }").tokenize()).parse()
        }
        assertTrue("must declare ': Type'" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun aTypePropertyBindingNeedsAnExplicitTypeAnnotation() {
        // Azora never infers a declaration's type - least of all one that binds a type.
        val failure = assertFailsWith<IllegalStateException> {
            Parser(Lexer("""
                deepinline prop Widest<...T>: std::Type {
                    var result = T.0
                    return result
                }
            """.trimIndent()).tokenize()).parse()
        }
        assertTrue("explicit ': Type' annotation" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun theRemovedCallSyntaxIsRejectedWithAHint() {
        val failure = assertFailsWith<IllegalStateException> {
            Parser(Lexer("func result(): Wider@(Int, Double) { return 1.0 }").tokenize()).parse()
        }
        assertTrue("was removed" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun stdlibPromoteSelectsHighestRankedType() {
        assertIs<CompilationResult.Success>(compile("""
            import std.traits
            func result(): std::promote<std::Byte, std::Int, std::Long, std::Double> {
                return 1.0
            }
        """))
    }

    @Test
    fun stdlibPromoteRequiresTwoTypes() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            import std.traits
            func invalid(): std::promote<std::Int> { return 1 }
        """))
        assertTrue(failure.errors.any { "'T.size >= 2'" in it }, failure.errors.toString())
    }

    @Test
    fun fullyQualifiedStdlibPromoteDoesNotRequireImport() {
        assertIs<CompilationResult.Success>(compile("""
            func result(): std.traits.promote<std::Int, std::Double> { return 1.0 }
        """))
    }

    @Test
    fun fixedTypePropertyResolvesReturnType() {
        assertIs<CompilationResult.Success>(compile("""
            deepinline prop Wider<A, B>: std::Type {
                return if A.rank >= B.rank { A } else { B }
            }
            func result(): Wider<std::Int, std::Double> {
                return 2.5
            }
        """))
    }

    @Test
    fun exactOverloadWinsBeforeVariadicOverload() {
        assertIs<CompilationResult.Success>(compile("""
            deepinline prop Choose<A, B>: std::Type { return A }
            deepinline prop Choose<...Types>: std::Type where Types.size >= 2 {
                return Types.1
            }
            func result(): Choose<std::String, std::Int> {
                return "fixed"
            }
        """))
    }

    @Test
    fun typePropertiesCanCallOtherTypeProperties() {
        assertIs<CompilationResult.Success>(compile("""
            deepinline prop NumericResult<A, B>: std::Type {
                return if A.rank >= B.rank { A } else { B }
            }
            deepinline prop Forwarded<A, B>: std::Type { return NumericResult<A, B> }
            func result(): Forwarded<std::Int, std::Double> { return 4.5 }
        """))
    }

    @Test
    fun aVariadicTypePropertySupportsBindingsLoopsAndRank() {
        assertIs<CompilationResult.Success>(compile("""
            deepinline prop Widest<...Types>: std::Type where Types.size >= 2 {
                var result: std::Type = Types.0
                for candidate: std::Type in Types[1...] {
                    if candidate.rank > result.rank {
                        result = candidate
                    }
                }
                return result
            }
            func result(): Widest<std::Byte, std::Long, std::Double, std::Int> {
                return 3.5
            }
        """))
    }

    @Test
    fun aStatementIfWithoutAnElseFallsThrough() {
        // The branch is a statement, not an expression, so an unmatched condition
        // simply leaves the binding as it was.
        assertIs<CompilationResult.Success>(compile("""
            deepinline prop FirstUnlessWider<A, B>: std::Type {
                var result: std::Type = A
                if B.rank > A.rank {
                    result = B
                }
                return result
            }
            func result(): FirstUnlessWider<std::Int, std::Double> { return 1.5 }
        """))
    }

    @Test
    fun genericFunctionCallUsesTypePropertyForItsResult() {
        assertIs<CompilationResult.Success>(compile("""
            import std.traits
            func greater<T, U>(a: T, b: U): std::promote<T, U> {
                return a + b
            }
            func main() {
                fin result: std::Double = greater(1, 2.5)
            }
        """))
    }

    @Test
    fun variadicConstraintProducesDiagnostic() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            deepinline prop Widest<...Types>: std::Type where Types.size >= 2 {
                return Types.0
            }
            func invalid(): Widest<std::Int> { return 1 }
        """))
        assertTrue(failure.errors.any { "'Types.size >= 2'" in it }, failure.errors.toString())
    }

    @Test
    fun recursiveTypePropertyProducesDiagnostic() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            deepinline prop First<Value>: std::Type { return Second<Value> }
            deepinline prop Second<Value>: std::Type { return First<Value> }
            func invalid(): First<std::Int> { return 1 }
        """))
        assertTrue(failure.errors.any { "Recursive type-property call" in it }, failure.errors.toString())
    }

    @Test
    fun duplicateOverloadIsRejectedByParser() {
        assertFailsWith<IllegalStateException> {
            Parser(Lexer("""
                deepinline prop Same<Value>: std::Type { return Value }
                deepinline prop Same<Other>: std::Type { return Other }
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
        // One argument cannot satisfy `.size >= 2`, so selection must fall through
        // to the single-argument overload rather than report a constraint error.
        assertIs<CompilationResult.Success>(compile("""
            deepinline prop Pick<...Types>: std::Type where Types.size >= 2 {
                return Types.1
            }
            deepinline prop Pick<Only>: std::Type {
                return Only
            }
            func one(): Pick<std::Int> { return 1 }
            func two(): Pick<std::Int, std::Double> { return 1.0 }
        """))
    }

    @Test
    fun aConcreteInvalidSpecializationIsAnError() {
        // With no applicable alternative, the same violation is the user's error.
        val failure = assertIs<CompilationResult.Failure>(compile("""
            deepinline prop OnlyPairs<...Types>: std::Type where Types.size >= 2 {
                return Types.0
            }
            func invalid(): OnlyPairs<std::Int> { return 1 }
        """))
        assertTrue(
            failure.errors.any { "'Types.size >= 2'" in it },
            failure.errors.toString(),
        )
    }

    @Test
    fun variadicOverloadSelectionPrefersTheLongestFixedPrefix() {
        // Selection order is unchanged by the split: the candidate with the most
        // fixed parameters before its pack still wins.
        assertIs<CompilationResult.Success>(compile("""
            deepinline prop Tagged<First, ...Rest>: std::Type {
                return First
            }
            deepinline prop Tagged<...Rest>: std::Type {
                return Rest.0
            }
            func chosen(): Tagged<std::Int, std::Double> { return 1 }
        """))
    }

    // ------------------------------------------------------------------
    // Ordinary (non-variadic) generic instantiation
    //
    // Validated once per unique resolved combination, where a declared type is
    // resolved - never during parsing or overload filtering.
    // ------------------------------------------------------------------

    private val vecDecl = """
        pack Vec<T, N: std::Int> where T is std::Number && N in 2..4 {
            var x: T = 0
            var y: T = 0
        }
    """.trimIndent()

    @Test
    fun genericSatisfyingItsConstraintsIsAccepted() {
        assertIs<CompilationResult.Success>(compile("""
            $vecDecl
            func makeA(): std::Int { fin v: Vec<std::Int, 2> = Vec<std::Int, 2>(1, 2) return v.x }
        """))
    }

    @Test
    fun genericWithADifferentSatisfyingCombinationIsAccepted() {
        assertIs<CompilationResult.Success>(compile("""
            $vecDecl
            func makeB(): std::Float { fin v: Vec<std::Float, 4> = Vec<std::Float, 4>(1.0, 2.0) return v.x }
        """))
    }

    @Test
    fun genericViolatingNominalConformanceIsRejected() {
        val failure = assertIs<CompilationResult.Failure>(compile("""
            $vecDecl
            func makeC(): std::String { fin v: Vec<std::String, 2> = Vec<std::String, 2>("a", "b") return v.x }
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
            func makeD(): std::Int { fin v: Vec<std::Int, 9> = Vec<std::Int, 9>(1, 2) return v.x }
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
            func a(): std::Int { fin v: Vec<std::Int, 9> = Vec<std::Int, 9>(1, 2) return v.x }
            func b(): std::Int { fin v: Vec<std::Int, 9> = Vec<std::Int, 9>(3, 4) return v.x }
            func c(): std::Int { fin v: Vec<std::Int, 9> = Vec<std::Int, 9>(5, 6) return v.x }
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
            pack Holder<T, N: std::Int> {
                var inner: Vec<T, N>
            }
        """))
    }

}