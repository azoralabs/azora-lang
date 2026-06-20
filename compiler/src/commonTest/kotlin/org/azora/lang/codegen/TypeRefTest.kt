package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.ir.IrType
import kotlin.test.*

/**
 * Tests for the structured type-reference system ([org.azora.lang.frontend.TypeRef])
 * and its resolution to [IrType]. Covers array, map, set, function, tuple, and
 * generic named-type annotations.
 */
class TypeRefTest {

    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    /** Resolves the type of the first parameter of function `f`. */
    private fun firstParamType(source: String): IrType =
        compile(source).ir.functions.first { it.name == "f" }.params.first().second

    @Test
    fun arrayTypeAnnotation() {
        val t = firstParamType("func f(x: [Int]): Int { return 0 }")
        assertIs<IrType.Array>(t)
        assertEquals(IrType.Int, t.element)
    }

    @Test
    fun nestedArrayTypeAnnotation() {
        val t = firstParamType("func f(x: [[Int]]): Int { return 0 }")
        assertIs<IrType.Array>(t)
        assertIs<IrType.Array>(t.element)
    }

    @Test
    fun mapTypeAnnotation() {
        val t = firstParamType("func f(x: [String: Int]): Int { return 0 }")
        assertIs<IrType.Map>(t)
        assertEquals(IrType.String, t.key)
        assertEquals(IrType.Int, t.value)
    }

    @Test
    fun setTypeAnnotation() {
        val t = firstParamType("func f(x: ![Int]): Int { return 0 }")
        assertIs<IrType.Set>(t)
        assertEquals(IrType.Int, t.element)
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
        val t = firstParamType("func f(x: Map<String, List<Int>>): Int { return 0 }")
        assertIs<IrType.Named>(t)
        assertEquals("Map", t.name)
    }

    @Test
    fun primitiveTypeStillResolves() {
        assertEquals(IrType.Int, firstParamType("func f(x: Int): Int { return 0 }"))
        assertEquals(IrType.String, firstParamType("func f(x: String): Int { return 0 }"))
        assertEquals(IrType.Bool, firstParamType("func f(x: Bool): Int { return 0 }"))
    }

    @Test
    fun arrayTypeLoweredToAllBackends() {
        val result = compile("func f(x: [Int]): Int { return 0 }")
        // Kotlin backend maps [Int] -> MutableList<Int>
        assertTrue("MutableList<Int>" in result.kotlin, "Kotlin backend should emit MutableList<Int>, got:\n${result.kotlin}")
        // TypeScript backend maps [Int] -> number[]
        assertTrue("number[]" in result.typescript, "TypeScript backend should emit number[], got:\n${result.typescript}")
    }
}
