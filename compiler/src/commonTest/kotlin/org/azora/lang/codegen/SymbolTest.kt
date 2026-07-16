package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.*

class SymbolTest {

    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    private fun expectFailure(source: String): List<String> {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Failure>(result, "Expected compilation failure but got success")
        return result.errors
    }

    // -----------------------------------------------------------------------
    // var
    // -----------------------------------------------------------------------

    @Test
    fun var_mutableBinding() {
        val result = compile("""
            import std.io
            func main() {
                var x = 5
                x = 10
                std::io::println(x)
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("var x: Int = 5" in ir)
        assertTrue("x = 10" in ir)
    }

    @Test
    fun var_explicitType() {
        val result = compile("""
            import std.io
            func main() {
                var x: Int = 5
                std::io::println(x)
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("var x: Int = 5" in ir)
    }

    @Test
    fun var_typeMismatch() {
        val errors = expectFailure("""
            import std.io
            func main() {
                var x: Int = "hello"
            }
        """.trimIndent())
        assertTrue(errors.any { "type mismatch" in it })
    }

    @Test
    fun var_redeclaration() {
        val errors = expectFailure("""
            import std.io
            func main() {
                var x = 5
                var x = 10
            }
        """.trimIndent())
        assertTrue(errors.any { "already declared" in it })
    }

    // -----------------------------------------------------------------------
    // fin
    // -----------------------------------------------------------------------

    @Test
    fun fin_deeplyImmutable() {
        val result = compile("""
            import std.io
            func main() {
                fin x = 42
                std::io::println(x)
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("fin x: Int = 42" in ir)
    }

    @Test
    fun fin_explicitType() {
        val result = compile("""
            import std.io
            func main() {
                fin x: String = "hello"
                std::io::println(x)
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("fin x: String = \"hello\"" in ir)
    }

    @Test
    fun fin_reassignmentRejected() {
        val errors = expectFailure("""
            import std.io
            func main() {
                fin x = 5
                x = 10
            }
        """.trimIndent())
        assertTrue(errors.any { "cannot reassign immutable" in it })
    }

    @Test
    fun fin_globalScope() {
        val result = compile("""
            import std.io
            fin x = 99
            func main() {
                std::io::println(x)
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("fin x: Int = 99" in ir)
    }

    @Test
    fun fin_redeclaration() {
        val errors = expectFailure("""
            import std.io
            func main() {
                fin x = 1
                fin x = 2
            }
        """.trimIndent())
        assertTrue(errors.any { "already declared" in it })
    }

    // -----------------------------------------------------------------------
    // let
    // -----------------------------------------------------------------------

    @Test
    fun let_immutableBinding() {
        val result = compile("""
            import std.io
            func main() {
                let x = 7
                std::io::println(x)
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("let x: Int = 7" in ir)
    }

    @Test
    fun let_explicitType() {
        val result = compile("""
            import std.io
            func main() {
                let x: Bool = true
                std::io::println(x)
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("let x: Bool = true" in ir)
    }

    @Test
    fun let_reassignmentRejected() {
        val errors = expectFailure("""
            import std.io
            func main() {
                let x = 5
                x = 10
            }
        """.trimIndent())
        assertTrue(errors.any { "cannot reassign immutable" in it })
    }

    @Test
    fun let_redeclaration() {
        val errors = expectFailure("""
            import std.io
            func main() {
                let x = 1
                let x = 2
            }
        """.trimIndent())
        assertTrue(errors.any { "already declared" in it })
    }

    // -----------------------------------------------------------------------
    // func
    // -----------------------------------------------------------------------

    @Test
    fun func_explicitReturnType() {
        val result = compile("""
            import std.io
            func add(a: Int, b: Int): Int {
                return a + b
            }
            func main() {
                std::io::println(add(3, 4))
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("func add(a: Int, b: Int): Int" in ir)
    }

    @Test
    fun func_inferredReturnType() {
        val result = compile("""
            import std.io
            func add(a: Int, b: Int) {
                return a + b
            }
            func main() {
                std::io::println(add(3, 4))
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("func add(a: Int, b: Int): Int" in ir)
    }

    @Test
    fun func_inferredUnitReturnType() {
        val result = compile("""
            import std.io
            func greet() {
                std::io::println("hi")
            }
            func main() {
                greet()
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("func greet(): Unit" in ir)
    }

    @Test
    fun func_duplicateName() {
        val errors = expectFailure("""
            import std.io
            func foo() {
                std::io::println("a")
            }
            func foo() {
                std::io::println("b")
            }
            func main() {
                foo()
            }
        """.trimIndent())
        assertTrue(errors.any { "duplicate" in it.lowercase() || "already defined" in it.lowercase() })
    }

    @Test
    fun func_undefinedCall() {
        val errors = expectFailure("""
            import std.io
            func main() {
                unknown()
            }
        """.trimIndent())
        assertTrue(errors.any { "undefined function" in it })
    }

    @Test
    fun func_argCountMismatch() {
        val errors = expectFailure("""
            import std.io
            func add(a: Int, b: Int): Int {
                return a + b
            }
            func main() {
                std::io::println(add(1))
            }
        """.trimIndent())
        assertTrue(errors.any { "expects" in it && "args" in it })
    }

    @Test
    fun func_returnTypeMismatch() {
        val errors = expectFailure("""
            import std.io
            func foo(): Int {
                return "hello"
            }
            func main() {
                std::io::println(foo())
            }
        """.trimIndent())
        assertTrue(errors.any { "return type mismatch" in it })
    }

    @Test
    fun func_unusedEliminatedInOptimizedIr() {
        val result = compile("""
            import std.io
            func unused() {
                std::io::println("never called")
            }
            func main() {
                std::io::println("hello")
            }
        """.trimIndent())
        val optimizedFuncs = result.optimizedIr.functions.map { it.name }
        assertTrue("main" in optimizedFuncs)
        assertFalse("unused" in optimizedFuncs, "Unused function should be eliminated from optimized IR")
    }

    // -----------------------------------------------------------------------
    // Scope resolution (::)
    // -----------------------------------------------------------------------

    @Test
    fun scopeResolution_accessesUpperScope() {
        val result = compile("""
            import std.io
            fin x = 9
            func main() {
                var x = 2
                std::io::println(x)
                std::io::println(::x)
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        // Local x is mangled (shadows global), ::x resolves to global x
        // The two println calls should reference different variable names
        val lines = ir.lines().filter { it.trim().startsWith("std__io__println(") }
        assertEquals(2, lines.size, "Should have 2 println calls, got: $lines")
        assertNotEquals(lines[0], lines[1], "Local and upper scope should resolve to different names in IR:\n$ir")
    }

    @Test
    fun scopeResolution_zoneScope() {
        val result = compile("""
            import std.io
            func main() {
                var x = 1
                zone {
                    var x = 2
                    std::io::println(::x)
                }
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        // ::x inside zone resolves to the function-scope x, which keeps its original name
        // The zone's x gets mangled since it shadows the outer one
        assertTrue("println(x)" in ir, "::x should resolve to outer x (not mangled), got:\n$ir")
    }

    @Test
    fun scopeResolution_undefinedInUpperScope() {
        val errors = expectFailure("""
            import std.io
            func main() {
                var x = 5
                std::io::println(::y)
            }
        """.trimIndent())
        assertTrue(errors.any { "not found" in it })
    }

    @Test
    fun scopeResolution_globalFin() {
        val result = compile("""
            import std.io
            fin greeting = "hello"
            func main() {
                var greeting = "bye"
                std::io::println(::greeting)
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        // ::greeting resolves to the global (original name), local gets mangled
        assertTrue("println(greeting)" in ir, "::greeting should resolve to global name, got:\n$ir")
    }

    // -----------------------------------------------------------------------
    // Shadowing (allowed across scopes)
    // -----------------------------------------------------------------------

    @Test
    fun shadowing_innerScopeAllowed() {
        val result = compile("""
            import std.io
            func main() {
                var x = 1
                zone {
                    var x = 2
                    std::io::println(x)
                }
                std::io::println(x)
            }
        """.trimIndent())
        assertNotNull(result)
    }

    @Test
    fun shadowing_functionParamShadowsGlobal() {
        val result = compile("""
            import std.io
            fin x = 99
            func foo(x: Int): Int {
                return x
            }
            func main() {
                std::io::println(foo(5))
            }
        """.trimIndent())
        assertNotNull(result)
    }

    // -----------------------------------------------------------------------
    // Top-level var/let rejected (not thread-safe)
    // -----------------------------------------------------------------------

    @Test
    fun topLevel_varRejected() {
        val errors = expectFailure("""
            import std.io
            var x = 5
            func main() {
                std::io::println(x)
            }
        """.trimIndent())
        assertTrue(errors.any { "not allowed" in it && "thread-safe" in it })
    }

    @Test
    fun topLevel_letRejected() {
        val errors = expectFailure("""
            import std.io
            let x = 5
            func main() {
                std::io::println(x)
            }
        """.trimIndent())
        assertTrue(errors.any { "not allowed" in it && "thread-safe" in it })
    }

    // -----------------------------------------------------------------------
    // Unused locals eliminated in optimized IR
    // -----------------------------------------------------------------------

    @Test
    fun unusedLocal_eliminatedInOptimizedIr() {
        val result = compile("""
            import std.io
            func main() {
                var unused = 42
                std::io::println("hello")
            }
        """.trimIndent())
        val optimizedIr = result.optimizedIr.prettyPrint()
        assertFalse("unused" in optimizedIr, "Unused local var should be eliminated from optimized IR")
        assertTrue("println" in optimizedIr)
    }

    @Test
    fun unusedGlobal_eliminatedInOptimizedIr() {
        val result = compile("""
            import std.io
            fin unused = 99
            func main() {
                std::io::println("hello")
            }
        """.trimIndent())
        val optimizedIr = result.optimizedIr.prettyPrint()
        assertFalse("unused" in optimizedIr, "Unused global fin should be eliminated from optimized IR")
    }
}
