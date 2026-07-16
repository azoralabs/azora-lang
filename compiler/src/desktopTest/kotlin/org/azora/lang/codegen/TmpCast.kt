package org.azora.lang.codegen

import org.azora.lang.Compiler
import org.azora.lang.CompilationResult
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals

class TmpCast {
    private fun run(src: String): String =
        IrInterpreter().interpret((Compiler().compile(src) as CompilationResult.Success).ir).trim()

    @Test fun castInt() = assertEquals("5", run("""
        import std.io
        func main() { std::io::println(cast 5 as String) }
    """.trimIndent()))

    @Test fun dotToString() = assertEquals("7", run("""
        import std.io
        func main() { std::io::println(7.toString) }
    """.trimIndent()))

    @Test fun castReal() = assertEquals("3.5", run("""
        import std.io
        func main() { std::io::println(cast 3.5 as String) }
    """.trimIndent()))
}
