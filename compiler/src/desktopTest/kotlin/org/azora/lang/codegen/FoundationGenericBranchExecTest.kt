package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

class FoundationGenericBranchExecTest {
    @Test fun discardedInlineGenericResultDoesNotReturnFromItsCaller() {
        val source = """
            import std.io
            inline func<T> label(value: T): String { return T.typeName }
            func main() {
                label<Int>(3)
                println(7)
            }
        """.trimIndent()
        for (release in listOf(false, true)) {
            val result = kotlin.test.assertIs<org.azora.lang.CompilationResult.Success>(org.azora.lang.Compiler().compile(source, release = release))
            assertEquals("7", org.azora.lang.backend.IrInterpreter().interpret(result.ir).trim())
            if (LlvmExec.available) assertEquals("7", LlvmExec.run(source, release))
        }
    }
    @Test fun packMethodsSpecializeCompileTimeIntegerPredicatesEvenWithUniformFields() {
        if (!LlvmExec.available) return
        val source = """
            import std.io
            import std.math
            error MathError { DivisionByZero }
            pack Box<T> where T is Number { var value: T }
            impl Box<T> {
                oper/ &.(rhs: T&): Self
                inline if T is Integer { inline "?! MathError" } {
                    inline if T is Integer {
                        if rhs == 0 { error MathError.DivisionByZero }
                    }
                    return Self(self.value / rhs)
                }
            }
            func main() {
                fin box = Box<Long>(Long(84))
                fin result = (box / Long(2)) catch Box<Long>(Long(0))
                println(result.value)
                fin real = Box<Double>(84.0)
                println((real / 2.0).value)
            }
        """.trimIndent()
        for (release in listOf(false, true)) assertEquals("42\n42.0", LlvmExec.run(source, release))
    }
}
