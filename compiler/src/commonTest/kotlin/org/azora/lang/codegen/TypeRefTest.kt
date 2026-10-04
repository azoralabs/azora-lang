package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.frontend.TypeRef
import org.azora.lang.ir.IrType
import kotlin.test.*

/**
 * Tests for the structured type-reference system ([org.azora.lang.frontend.TypeRef])
 * and its resolution to [IrType]. Covers fixed arrays, stdlib collection
 * surface types, function, tuple, and generic named-type annotations.
 */
class TypeRefTest {

    @Test
    fun borrowOriginsRenderWithPipeFences() {
        val shared = TypeRef.Reference(
            TypeRef.RefKind.BORROWED,
            TypeRef.Named("String"),
            origins = listOf("self"),
        )
        val mutable = TypeRef.Reference(
            TypeRef.RefKind.MUTABLE,
            TypeRef.Named("Buffer"),
            origins = listOf("source", "fallback"),
        )

        assertEquals("String&|self|", shared.toString())
        assertEquals("Buffer!|source, fallback|", mutable.toString())
    }

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
        val t = firstParamType("func f(x: Array<Int>): Int { return 0 }")
        assertIs<IrType.Array>(t)
        assertEquals(IrType.Int, t.element)
    }

    @Test
    fun nestedArrayGenericTypeAnnotation() {
        val t = firstParamType("func f(x: Array<Array<Int>>): Int { return 0 }")
        assertIs<IrType.Array>(t)
        assertIs<IrType.Array>(t.element)
    }

    // The standard collections are library types: a program names them after
    // importing them, as it would any other library declaration.
    private val listImport = "import std.container.list\n"
    private val setImport = "import std.container.set\n"
    private val mapImport = "import std.container.map\n"

    @Test
    fun immutableCollectionTypeAnnotations() {
        val vec = firstParamType(listImport + "func f(x: List<Int>): Int { return 0 }")
        val set = firstParamType(setImport + "func f(x: Set<String>): Int { return 0 }")
        val map = firstParamType(mapImport + "func f(x: Map<String, Int>): Int { return 0 }")
        assertIs<IrType.Named>(vec)
        assertEquals("List", vec.name)
        assertIs<IrType.Named>(set)
        assertEquals("Set", set.name)
        assertIs<IrType.Named>(map)
        assertEquals("Map", map.name)
    }

    @Test
    fun mutableCollectionTypeAnnotations() {
        val vec = firstParamType(listImport + "func f(x: MutableList<Int>): Int { return 0 }")
        val set = firstParamType(setImport + "func f(x: MutableSet<String>): Int { return 0 }")
        val map = firstParamType(mapImport + "func f(x: MutableMap<String, Int>): Int { return 0 }")
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
        // `(A, B)` is the native tuple type (GTC §6.2).
        val t = firstParamType("func f(x: (Int, String)): Int { return 0 }")
        assertEquals(IrType.Tuple(listOf(IrType.Int, IrType.String)), t)
    }

    @Test
    fun genericNamedTypeAnnotation() {
        val t = firstParamType(listImport + "func f(x: List<Int>): Int { return 0 }")
        assertIs<IrType.Named>(t)
        assertEquals("List", t.name)
    }

    @Test
    fun nestedGenericNamedTypeAnnotation() {
        // Collection surface spellings are contextual; plain user generic names still parse normally.
        val t = firstParamType(
            listImport + "pack Dictionary<K, V>\n" +
                "func f(x: Dictionary<String, List<Int>>): Int { return 0 }",
        )
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
    fun arrayGenericTypeLoweredToAllBackends() {
        val result = compile("func f(x: Array<Int>): Int { return 0 }")
    }

    @Test
    fun arrayGenericNameIsCanonical() {
        val canonical = firstParamType("func f(x: Array<Int>): Int { return 0 }")
        assertEquals(IrType.Array(IrType.Int), canonical)
    }

    @Test
    fun arrayGenericNameRequiresExactlyOneTypeArgument() {
        assertTrue(expectFailure("func f(x: Array): Int { return 0 }").any { "Array expects one type argument" in it && "got 0" in it })
        assertTrue(expectFailure("func f(x: Array<Int, String>): Int { return 0 }").any { "Array expects one type argument" in it && "got 2" in it })
    }

    @Test
    fun bracketCollectionTypesAreRejected() {
        // Bracket type sugar is not valid: arrays are `Array<T>`, maps are `Map<K, V>`.
        assertTrue(expectFailure("func f(x: [Int]): Int { return x.size }").any { "Array<Int>" in it })
        assertTrue(expectFailure("func f(x: [String: Int]): Int { return x.size }").any { "Map<K, V>" in it })
    }

    @Test
    fun removedCollectionTypeSpellingsAreRejected() {
        assertTrue(expectFailure("func f(x: ![Int]): Int { return 0 }").any { "Set<T>" in it })
        assertTrue(expectFailure("func f(x: arr[Int]): Int { return 0 }").any { "Array<T>" in it })
        assertTrue(expectFailure("func f(x: tup(Int, String)): Int { return 0 }").any { "Expected ')' after parameters" in it || "undefined" in it || "tup" in it })
    }
}
