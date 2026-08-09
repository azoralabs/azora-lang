package org.azora.lang.codegen

import org.azora.lang.frontend.ParamModifier
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
            async func loadUser(): std::Int { return 20 }
            async func loadPosts(): std::Int { return 22 }
            async func main() {
                fin user = loadUser()
                fin posts = loadPosts()
                fin userValue = await user
                fin postValue = await posts
                std::println(userValue + postValue)
            }
        """.trimIndent()

        assertEquals("42", run(source))

        val result = compile(source)
        assertTrue("define %azora.task* @loadUser" in result.llvm)
        assertTrue("define i32 @__azora_task_body_loadUser" in result.llvm)
    }

    @Test
    fun directAwaitOfTaskCall() {
        assertEquals("42", run("""
            import std.io
            async func answer(): std::Int { return 42 }
            async func main() {
                fin value = await answer()
                std::println(value)
            }
        """.trimIndent()))
    }

    @Test
    fun asyncBlockProducesTaskHandle() {
        val source = """
            import std.io
            async func main() {
                fin left = async { 19 }
                fin right = async { 23 }
                fin a = await left
                fin b = await right
                std::println(a + b)
            }
        """.trimIndent()

        assertEquals("42", run(source))
        val result = compile(source)
    }

    @Test
    fun taskAndUnsafeFlagsSurviveIntoIr() {
        val result = compile("""
            import std.io
            unsafe async func compute(): std::Int { return 7 }
            async func main() { std::println(7) }
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
            unsafe func raw(): std::Int { return 7 }
            func main() { std::println(raw()) }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(rejected)
        assertTrue(rejected.errors.any { "requires an unsafe block" in it })

        assertEquals("7", run("""
            import std.io
            unsafe func raw(): std::Int { return 7 }
            func main() {
                unsafe { std::println(raw()) }
            }
        """.trimIndent()))
    }

    @Test
    fun safeTaskRejectsBorrowAcrossSuspensionBoundary() {
        val result = Compiler().compile("""
            import std.io
            pack Buffer { var value: std::Int }
            async func inspect(input: Buffer&) {
                delay 100
                std::println(input.value)
            }
            async func main() { std::println(0) }
        """.trimIndent())

        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { "'input' is borrowed across a suspension point" in it },
            "${'$'}{result.errors}",
        )
    }

    // A borrow the task never holds across a suspension is an ordinary borrow.
    @Test
    fun safeTaskAcceptsABorrowThatDoesNotCrossSuspension() {
        val result = Compiler().compile("""
            import std.io
            pack Buffer { var value: std::Int }
            async func inspect(input: Buffer&): std::Int { return input.value }
            async func main() { std::println(0) }
        """.trimIndent())

        assertIs<CompilationResult.Success>(result)
    }

    @Test
    fun referenceParameterSpellingsAreNormalized() {
        val result = compile("""
            import std.io
            pack Buffer { var value: std::Int }
            func read(a: Buffer&, b: Buffer!): std::Int {
                return a.value
            }
            func update(state: Buffer!) {
                state.value = 42
            }
            func main() {
                var buffer = Buffer(1)
                update(buffer)
                std::println(buffer.value)
            }
        """.trimIndent())

        val read = result.ast.functions.first { it.name == "read" }
        assertEquals(listOf(ParamModifier.SHARED, ParamModifier.EXCLUSIVE), read.params.map { it.modifier })
        val update = result.ast.functions.first { it.name == "update" }
        assertEquals(ParamModifier.EXCLUSIVE, update.params.single().modifier)
        assertEquals("42", IrInterpreter().interpret(result.ir).trim())
    }

    @Test
    fun referenceDeclarationAnnotationsRetainOwnershipKind() {
        val result = compile("""
            import std.io
            pack Buffer { var value: std::Int }
            func main() {
                var owned: Buffer = Buffer(1)
                fin borrowed: Buffer& = owned
                var exclusive: Buffer! = owned
                std::println(borrowed.value + exclusive.value)
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
                TypeRef.RefKind.MUTABLE
            ),
            refs.map { it.kind }
        )
        assertEquals("2", IrInterpreter().interpret(result.ir).trim())
    }
}
