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
    fun arrayTypeAnnotation() {
        val t = firstParamType("func f(x: Array<Int>): Int { return 0 }")
        assertIs<IrType.Array>(t)
        assertEquals(IrType.Int, t.element)
    }

    @Test
    fun nestedArrayTypeAnnotation() {
        val t = firstParamType("func f(x: Array<Array<Int>>): Int { return 0 }")
        assertIs<IrType.Array>(t)
        assertIs<IrType.Array>(t.element)
    }

    @Test
    fun immutableCollectionTypeAnnotations() {
        val vec = firstParamType("func f(x: List<Int>): Int { return 0 }")
        val set = firstParamType("func f(x: Set<String>): Int { return 0 }")
        val map = firstParamType("func f(x: Map<String, Int>): Int { return 0 }")
        assertIs<IrType.Named>(vec)
        assertEquals("List", vec.name)
        assertIs<IrType.Named>(set)
        assertEquals("Set", set.name)
        assertIs<IrType.Named>(map)
        assertEquals("Map", map.name)
    }

    @Test
    fun mutableCollectionTypeAnnotations() {
        val vec = firstParamType("func f(x: MutableList<Int>): Int { return 0 }")
        val set = firstParamType("func f(x: MutableSet<String>): Int { return 0 }")
        val map = firstParamType("func f(x: MutableMap<String, Int>): Int { return 0 }")
        assertIs<IrType.Named>(vec)
        assertEquals("MutableList", vec.name)
        assertIs<IrType.Named>(set)
        assertEquals("MutableSet", set.name)
        assertIs<IrType.Named>(map)
        assertEquals("MutableMap", map.name)
    }

    @Test
    fun functionTypeAnnotation() {
        val t = firstParamType("func f(x: (Int) -> Bool): Int { return 0 }")
        assertIs<IrType.Function>(t)
        assertEquals(listOf(IrType.Int), t.params)
        assertEquals(IrType.Bool, t.ret)
    }

    @Test
    fun functionTypeTwoParamsAnnotation() {
        val t = firstParamType("func f(x: (Int, String) -> Bool): Int { return 0 }")
        assertIs<IrType.Function>(t)
        assertEquals(listOf(IrType.Int, IrType.String), t.params)
        assertEquals(IrType.Bool, t.ret)
    }

    @Test
    fun tupleTypeAnnotation() {
        val t = firstParamType("func f(x: (Int, String)): Int { return 0 }")
        assertIs<IrType.Tuple>(t)
        assertEquals(listOf(IrType.Int, IrType.String), t.elements)
    }

    @Test
    fun genericNamedTypeAnnotation() {
        val t = firstParamType("func f(x: List<Int>): Int { return 0 }")
        assertIs<IrType.Named>(t)
        assertEquals("List", t.name)
    }

    @Test
    fun nestedGenericNamedTypeAnnotation() {
        // Collection surface spellings are contextual; plain user generic names still parse normally.
        val t = firstParamType("func f(x: Dictionary<String, List<Int>>): Int { return 0 }")
        assertIs<IrType.Named>(t)
        assertEquals("Dictionary", t.name)
    }

    @Test
    fun primitiveTypeStillResolves() {
        assertEquals(IrType.Int, firstParamType("func f(x: Int): Int { return 0 }"))
        assertEquals(IrType.String, firstParamType("func f(x: String): Int { return 0 }"))
        assertEquals(IrType.Bool, firstParamType("func f(x: Bool): Int { return 0 }"))
    }

    @Test
    fun arrayTypeLoweredToAllBackends() {
        val result = compile("func f(x: Array<Int>): Int { return 0 }")
        // JavaScript is untyped, so the array parameter carries no type annotation.
        assertTrue("function f(x)" in result.javascript, "JavaScript backend should emit function f(x), got:\n${result.javascript}")
    }

    @Test
    fun bracketCollectionTypesAreAccepted() {
        // `[T]` (array) and `[K: V]` (map) bracket type syntax is supported.
        compile("func f(x: [Int]): Int { return x.length }")
        compile("func f(x: [String: Int]): Int { return x.length }")
    }

    @Test
    fun removedCollectionTypeSpellingsAreRejected() {
        assertTrue(expectFailure("func f(x: ![Int]): Int { return 0 }").any { "Set<T>" in it })
        assertTrue(expectFailure("func f(x: arr[Int]): Int { return 0 }").any { "Array<T>" in it })
        assertTrue(expectFailure("func f(x: tup(Int, String)): Int { return 0 }").any { "Expected ')' after parameters" in it || "undefined" in it || "tup" in it })
    }
}
