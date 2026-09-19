package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GenericMemberSignatureTest {
    companion object {
        val declarations = """
            pack Cell<T> { var value: T }
            impl Cell<T> {
                ctor .(value: T) { self.value = value }
                func &.get(): T { return self.value }
                func !.set(value: T) { self.value = value }
            }
        """.trimIndent()
        val program = """
            $declarations
            func main() {
                var integer = Cell<Int>(7)
                fin first: Int = integer.get()
                assert first == 7 panic "constructor retains Int"
                integer.set(42)
                assert integer.get() == 42 panic "method preserves Int"
                var decimal = Cell<Double>(1.25)
                fin fraction: Double = decimal.get()
                assert fraction == 1.25 panic "return preserves floating bits"
                decimal.set(value: 2.5)
                assert decimal.get() == 2.5 panic "method preserves Double"
                var text = Cell<String>("first")
                fin word: String = text.get()
                assert word == "first" panic "return preserves string"
                text.set("second")
                assert text.get() == "second" panic "method preserves String"
            }
        """.trimIndent()
        fun compile(optimized: Boolean) = Compiler().compile(program, release = optimized).let {
            assertIs<CompilationResult.Success>(it, (it as? CompilationResult.Failure)?.errors.toString()).ir
        }

        /**
         * A member inherited from a parent spec, and an index operator, typed by
         * the receiver's arguments. `println` chooses by the argument's type, so
         * an erased result prints as a placeholder or a pointer.
         */
        val inherited = """
            import std.io
            spec Source<T> {
                func &.read(): T
            }
            spec Sink<T> : Source<T> {
                func !.write(value: T)
            }
            pack Slot<T> { var value: T }
            impl Source<T> for Slot<T> {
                func &.read(): T { return self.value }
            }
            impl Sink<T> for Slot<T> {
                func !.write(value: T) { self.value = value }
            }
            oper[] Slot<T>&.(index: Int): T { return self.value }
            func main() {
                var text: Sink<String> = Slot<String>("hi")
                println(text.read())
                text.write("bye")
                println(text.read())
                fin number = Slot<Double>(2.5)
                println(number[0])
                fin word = Slot<String>("indexed")
                println(word[0])
            }
        """.trimIndent()
        const val inheritedOutput = "hi\nbye\n2.5\nindexed"

        fun compileInherited(optimized: Boolean) = Compiler().compile(inherited, release = optimized).let {
            assertIs<CompilationResult.Success>(it, (it as? CompilationResult.Failure)?.errors.toString()).ir
        }
    }

    @Test fun inheritedMembersAndIndexingUseTheReceiversArguments() {
        for (optimized in listOf(false, true)) {
            assertEquals(inheritedOutput, IrInterpreter().interpret(compileInherited(optimized)).trim())
        }
    }

    @Test fun genericOwnerTypesSurviveConstructionAndCalls() {
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(compile(optimized)))
        }
    }

    @Test fun constructorAndMethodArgumentsUseOwnerTypes() {
        for (body in listOf(
            "fin value = Cell<Int>(\"wrong\")",
            "var value = Cell<Int>(7)\nvalue.set(\"wrong\")",
            "fin value = Cell<Int>(7)\nfin text: String = value.get()",
        )) {
            val source = "$declarations\nfunc main() { $body }"
            val result = assertIs<CompilationResult.Failure>(Compiler().compile(source), source)
            assertTrue(result.errors.any { "expected" in it || "mismatch" in it }, result.errors.toString())
        }
    }

    @Test fun constructorRejectsExtraArguments() {
        val result = assertIs<CompilationResult.Failure>(Compiler().compile(
            "$declarations\nfunc main() { fin value = Cell<Int>(1, 2) }",
        ))
        assertTrue(result.errors.any { "got 2" in it }, result.errors.toString())
    }

    @Test fun everyVariadicConstructorArgumentIsChecked() {
        for (arguments in listOf("\"wrong\"", "1, \"wrong\"", "1, 2, \"wrong\"")) {
            val result = Compiler().compile("""
                import std.container.list::ArrayList
                func main() { fin value = ArrayList<Int>($arguments) }
            """.trimIndent())
            val failure = assertIs<CompilationResult.Failure>(result, arguments)
            assertTrue(failure.errors.any { "expected" in it }, failure.errors.toString())
        }
    }
}
