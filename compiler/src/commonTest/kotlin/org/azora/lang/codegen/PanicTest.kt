package org.azora.lang.codegen

import org.azora.lang.Compiler
import org.azora.lang.CompilationResult
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PanicTest {

    @Test fun panicNotReachedRunsNormally() {
        val r = Compiler().compile("""
            func main() {
                if false { panic "should not happen" }
                println("ok")
            }
        """.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(r)
        assertEquals("ok", IrInterpreter().interpret(r.ir).trim())
    }

    @Test fun runtimePanicAborts() {
        val r = Compiler().compile("""
            func main() {
                println("before")
                panic "boom"
            }
        """.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(r)
        // A reached runtime panic aborts the interpreter with "panic: boom".
        val ex = org.junit.Assert.assertThrows(RuntimeException::class.java) {
            IrInterpreter().interpret(r.ir)
        }
        assertContains(ex.message ?: "", "panic")
        assertContains(ex.message ?: "", "boom")
    }

    @Test fun inlinePanicAbortsCompilation() {
        // `inline panic` reached during CTFE aborts the compiler with its message.
        val thrown = org.junit.Assert.assertThrows(RuntimeException::class.java) {
            Compiler().compile("""
                func main() {
                    inline {
                        inline panic "compile-time boom"
                    }
                }
            """.trimIndent(), release = false)
        }
        assertContains(thrown.message ?: "", "inline panic")
        assertContains(thrown.message ?: "", "compile-time boom")
    }

    private fun assertEquals(expected: String, actual: String) = kotlin.test.assertEquals(expected, actual)
}
