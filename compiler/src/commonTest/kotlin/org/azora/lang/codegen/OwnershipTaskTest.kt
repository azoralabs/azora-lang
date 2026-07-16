package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.TypeRef
import org.azora.lang.ir.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OwnershipTaskTest {
    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}"
        )
        return result
    }

    private fun run(source: String): String {
        val result = compile(source)
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test
    fun namedTasksStartAndAwaitInTaskMain() {
        val source = """
            import std.io
            task loadUser(): Int { return 20 }
            task loadPosts(): Int { return 22 }
            task main() {
                fin user = loadUser()
                fin posts = loadPosts()
                fin userValue = await user
                fin postValue = await posts
                std::io::println(userValue + postValue)
            }
        """.trimIndent()

        assertEquals("42", run(source))

        val result = compile(source)
        assertTrue("async function loadUser" in result.javascript)
        assertTrue("await user" in result.javascript)
        assertTrue("define %azora.task* @loadUser" in result.llvm)
        assertTrue("define i32 @__azora_task_body_loadUser" in result.llvm)
    }

    @Test
    fun directAwaitOfTaskCall() {
        assertEquals("42", run("""
            import std.io
            task answer(): Int { return 42 }
            task main() {
                fin value = await answer()
                std::io::println(value)
            }
        """.trimIndent()))
    }

    @Test
    fun asyncBlockProducesTaskHandle() {
        val source = """
            import std.io
            task main() {
                fin left = async { 19 }
                fin right = async { 23 }
                fin a = await left
                fin b = await right
                std::io::println(a + b)
            }
        """.trimIndent()

        assertEquals("42", run(source))
        val result = compile(source)
        assertTrue("__azoraSpawn" in result.javascript)
    }

    @Test
    fun taskAndUnsafeFlagsSurviveIntoIr() {
        val result = compile("""
            import std.io
            unsafe task compute(): Int { return 7 }
            task main() { std::io::println(7) }
        """.trimIndent())

        val compute = result.ir.functions.first { it.name == "compute" }
        assertTrue(compute.isTask)
        assertTrue(compute.isUnsafe)
        assertEquals(IrType.Int, compute.returnType)
    }

    @Test
    fun unsafeCallsRequireExplicitBoundary() {
        val rejected = Compiler().compile("""
            import std.io
            unsafe func raw(): Int { return 7 }
            func main() { std::io::println(raw()) }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(rejected)
        assertTrue(rejected.errors.any { "requires an unsafe block" in it })

        assertEquals("7", run("""
            import std.io
            unsafe func raw(): Int { return 7 }
            func main() {
                unsafe { std::io::println(raw()) }
            }
        """.trimIndent()))
    }

    @Test
    fun safeTaskRejectsBorrowAcrossSuspensionBoundary() {
        val result = Compiler().compile("""
            import std.io
            pack Buffer { var value: Int }
            task inspect(input: ref Buffer): Int { return input.value }
            task main() { std::io::println(0) }
        """.trimIndent())

        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "cannot suspend with ref parameter" in it })
    }

    @Test
    fun referenceParameterSpellingsAreNormalized() {
        val result = compile("""
            import std.io
            pack Buffer { var value: Int }
            func read(a: ref Buffer, b: shared ref Buffer, c: weak ref Buffer): Int {
                return a.value
            }
            func update(state: mut ref Buffer) {
                state.value = 42
            }
            func main() {
                var buffer = Buffer(1)
                update(buffer)
                std::io::println(buffer.value)
            }
        """.trimIndent())

        val read = result.ast.functions.first { it.name == "read" }
        assertEquals(listOf("ref", "shared ref", "weak ref"), read.params.map { it.modifier })
        val update = result.ast.functions.first { it.name == "update" }
        assertEquals("mut ref", update.params.single().modifier)
        assertEquals("42", IrInterpreter().interpret(result.ir).trim())
    }

    @Test
    fun referenceDeclarationAnnotationsRetainOwnershipKind() {
        val result = compile("""
            import std.io
            pack Buffer { var value: Int }
            func main() {
                var owned: Buffer = Buffer(1)
                fin borrowed: ref Buffer = owned
                var exclusive: mut ref Buffer = owned
                fin shared: shared ref Buffer = owned
                fin weakRef: weak ref Buffer = owned
                std::io::println(borrowed.value + exclusive.value + shared.value + weakRef.value)
            }
        """.trimIndent())

        val main = result.ast.functions.first { it.name == "main" }
        val refs = main.body.mapNotNull { stmt ->
            val annotation = when (stmt) {
                is org.azora.lang.frontend.Stmt.VarDecl -> stmt.type
                is org.azora.lang.frontend.Stmt.FinDecl -> stmt.type
                else -> null
            }
            (annotation as? org.azora.lang.frontend.TypeAnnotation.Explicit)?.ref as? TypeRef.Reference
        }
        assertEquals(
            listOf(
                TypeRef.RefKind.BORROWED,
                TypeRef.RefKind.MUTABLE,
                TypeRef.RefKind.SHARED,
                TypeRef.RefKind.WEAK
            ),
            refs.map { it.kind }
        )
        assertEquals("4", IrInterpreter().interpret(result.ir).trim())
    }
}
