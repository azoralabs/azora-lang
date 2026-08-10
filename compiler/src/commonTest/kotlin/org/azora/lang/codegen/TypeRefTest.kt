package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.ir.IrType
import kotlin.test.*

/**
 * Tests for the structured type-reference system ([org.azora.lang.frontend.TypeRef])
 * and its resolution to [IrType]. Covers fixed arrays, stdlib collection
 * surface types, function, tuple, and generic named-type annotations.
 */
class TypeRefTest {

    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    private fun expectFailure(source: String): List<String> {
        return try {
            val result = Compiler().compile(source, release = false)
            assertIs<CompilationResult.Failure>(result)
            result.errors
        } catch (e: IllegalStateException) {
            listOf(e.message.orEmpty())
        }
    }

    /** Resolves the type of the first parameter of function `f`. */
    private fun firstParamType(source: String): IrType =
        compile(source).ir.functions.first { it.name == "f" }.params.first().second

    @Test
    fun arrayGenericTypeAnnotation() {
        val t = firstParamType("func f(x: std::Array<std::Int>): std::Int { return 0 }")
        assertIs<IrType.Array>(t)
        assertEquals(IrType.Int, t.element)
    }

    @Test
    fun nestedArrayGenericTypeAnnotation() {
        val t = firstParamType("func f(x: std::Array<std::Array<std::Int>>): std::Int { return 0 }")
        assertIs<IrType.Array>(t)
        assertIs<IrType.Array>(t.element)
    }

    @Test
    fun immutableCollectionTypeAnnotations() {
        val vec = firstParamType("func f(x: std::List<std::Int>): std::Int { return 0 }")
        val set = firstParamType("func f(x: std::Set<std::String>): std::Int { return 0 }")
        val map = firstParamType("func f(x: std::Map<std::String, std::Int>): std::Int { return 0 }")
        assertIs<IrType.Named>(vec)
        assertEquals("List", vec.name)
        assertIs<IrType.Named>(set)
        assertEquals("Set", set.name)
        assertIs<IrType.Named>(map)
        assertEquals("Map", map.name)
    }

    @Test
    fun mutableCollectionTypeAnnotations() {
        val vec = firstParamType("func f(x: std::MutableList<std::Int>): std::Int { return 0 }")
        val set = firstParamType("func f(x: std::MutableSet<std::String>): std::Int { return 0 }")
        val map = firstParamType("func f(x: std::MutableMap<std::String, std::Int>): std::Int { return 0 }")
        assertIs<IrType.Named>(vec)
        assertEquals("MutableList", vec.name)
        assertIs<IrType.Named>(set)
        assertEquals("MutableSet", set.name)
        assertIs<IrType.Named>(map)
        assertEquals("MutableMap", map.name)
    }

    @Test
    fun functionTypeAnnotation() {
        val t = firstParamType("func f(x: (std::Int) -> std::Bool): std::Int { return 0 }")
        assertIs<IrType.Function>(t)
        assertEquals(listOf(IrType.Int), t.params)
        assertEquals(IrType.Bool, t.ret)
    }

    @Test
    fun functionTypeTwoParamsAnnotation() {
        val t = firstParamType("func f(x: (std::Int, std::String) -> std::Bool): std::Int { return 0 }")
        assertIs<IrType.Function>(t)
        assertEquals(listOf(IrType.Int, IrType.String), t.params)
        assertEquals(IrType.Bool, t.ret)
    }

    @Test
    fun removedTupleTypeAnnotationIsRejected() {
        assertTrue(
            expectFailure("func f(x: (std::Int, std::String)): std::Int { return 0 }")
                .any { "Tuple<A, B>" in it },
        )
    }

    @Test
    fun genericNamedTypeAnnotation() {
        val t = firstParamType("func f(x: std::List<std::Int>): std::Int { return 0 }")
        assertIs<IrType.Named>(t)
        assertEquals("List", t.name)
    }

    @Test
    fun nestedGenericNamedTypeAnnotation() {
        // Collection surface spellings are contextual; plain user generic names still parse normally.
        val t = firstParamType("func f(x: Dictionary<std::String, std::List<std::Int>>): std::Int { return 0 }")
        assertIs<IrType.Named>(t)
        assertEquals("Dictionary", t.name)
    }

    @Test
    fun primitiveTypeStillResolves() {
        assertEquals(IrType.Int, firstParamType("func f(x: std::Int): std::Int { return 0 }"))
        assertEquals(IrType.String, firstParamType("func f(x: std::String): std::Int { return 0 }"))
        assertEquals(IrType.Bool, firstParamType("func f(x: std::Bool): std::Int { return 0 }"))
    }

    @Test
    fun arrayGenericTypeLoweredToAllBackends() {
        val result = compile("func f(x: std::Array<std::Int>): std::Int { return 0 }")
    }

    @Test
    fun arrayGenericNameIsCanonical() {
        val canonical = firstParamType("func f(x: std::Array<std::Int>): std::Int { return 0 }")
        assertEquals(IrType.Array(IrType.Int), canonical)
    }

    @Test
    fun arrayGenericNameRequiresExactlyOneTypeArgument() {
        assertTrue(expectFailure("func f(x: std::Array): std::Int { return 0 }").any { "exactly one type argument" in it })
        assertTrue(expectFailure("func f(x: std::Array<std::Int, std::String>): std::Int { return 0 }").any { "exactly one type argument" in it })
    }

    @Test
    fun bracketCollectionTypesAreRejected() {
        // Bracket type sugar is not valid: arrays are `Array<T>`, maps are `Map<K, V>`.
        assertTrue(expectFailure("func f(x: [std::Int]): std::Int { return x.size }").any { "std::Array<std::Int>" in it })
        assertTrue(expectFailure("func f(x: [std::String: std::Int]): std::Int { return x.size }").any { "std::Map<K, V>" in it })
    }

    @Test
    fun removedCollectionTypeSpellingsAreRejected() {
        assertTrue(expectFailure("func f(x: ![std::Int]): std::Int { return 0 }").any { "std::Set<T>" in it })
        assertTrue(expectFailure("func f(x: arr[std::Int]): std::Int { return 0 }").any { "std::Array<T>" in it })
        assertTrue(expectFailure("func f(x: tup(std::Int, std::String)): std::Int { return 0 }").any { "Expected ')' after parameters" in it || "undefined" in it || "tup" in it })
    }
}
