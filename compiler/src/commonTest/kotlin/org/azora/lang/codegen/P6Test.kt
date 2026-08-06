package org.azora.lang.codegen
import org.azora.lang.*
import kotlin.test.Test
class P6Test {
    @Test fun probe() {
        val r = Compiler().compile("""
            import std.io
            import std.format
            pack Vec2 { var x: Int }
            impl Display for Vec2 {
                func display[self: Self&](formatter: std::Formatter!) { formatter.write("v") }
            }
            func main() { std::println("${'$'}{Vec2(1)}") }
        """.trimIndent(), release = false)
        if (r is CompilationResult.Success) {
            println("PROBE llvm has __display: " + r.llvm.contains("__display"))
            println("PROBE llvm decl: " + r.llvm.lines().filter { "__display" in it }.take(3))
        } else println("PROBE fail: " + (r as CompilationResult.Failure).errors)
    }
}
