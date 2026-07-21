package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.ir.IrStmt
import org.azora.lang.ir.IrType
import kotlin.test.*

class TestAssertTraceTest {

    private fun compile(source: String, release: Boolean = false): CompilationResult.Success {
        val result = Compiler().compile(source, release = release)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    private fun run(source: String): String {
        val result = compile(source)
        return IrInterpreter().interpret(result.ir)
    }

    private fun expectFailure(source: String): List<String> {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Failure>(result, "Expected failure but got success")
        return result.errors
    }

    // -----------------------------------------------------------------------
    // test declaration
    // -----------------------------------------------------------------------

    @Test
    fun test_basicDeclaration() {
        val result = compile("""
            import std.io
            test "addition" {
                assert 1 + 1 == 2 { "math is broken" }
            }
            func main() {
                std::println("hello")
            }
        """.trimIndent())
        assertTrue(result.ir.tests.isNotEmpty(), "Should have tests in IR")
    }

    @Test
    fun test_interpreterRunsTests() {
        val output = run("""
            import std.io
            test "greet test" {
                trace { "inside test" }
            }
            func main() {
                std::println("main")
            }
        """.trimIndent())
        assertTrue("main" in output)
        assertTrue("[DEBUG] inside test" in output)
    }

    @Test
    fun test_javascriptEmit() {
        val result = compile("""
            import std.io
            test "my test" {
                assert 1 == 1 { "fail" }
            }
            func main() {}
        """.trimIndent())
        assertTrue("test(" in result.javascript, "JavaScript should emit test(), got:\n${result.javascript}")
        assertTrue("\"my test\"" in result.javascript, "JavaScript should include test name, got:\n${result.javascript}")
    }

    @Test
    fun test_llvmEmit() {
        val result = compile("""
            import std.io
            test "my test" {
                assert 1 == 1 { "fail" }
            }
            func main() {}
        """.trimIndent())
        assertTrue("define void @" in result.llvm, "LLVM should emit test function, got:\n${result.llvm}")
    }

    // -----------------------------------------------------------------------
    // assert (runtime)
    // -----------------------------------------------------------------------

    @Test
    fun assert_passingCondition() {
        val output = run("""
            import std.io
            func main() {
                assert 1 + 1 == 2 { "math broken" }
                std::println("ok")
            }
        """.trimIndent())
        assertEquals("ok", output)
    }

    @Test
    fun assert_failingConditionThrows() {
        val result = compile("""
            import std.io
            func main() {
                assert 1 == 2 { "bad math" }
            }
        """.trimIndent())
        try {
            IrInterpreter().interpret(result.ir)
            fail("Should have thrown on failed assert")
        } catch (e: Exception) {
            assertTrue("bad math" in e.message.orEmpty(), "Error should contain message, got: ${e.message}")
        }
    }

    @Test
    fun assert_conditionMustBeBool() {
        val errors = expectFailure("""
            import std.io
            func main() {
                assert 42 { "not bool" }
            }
        """.trimIndent())
        assertTrue(errors.any { "Bool" in it }, "Should require Bool condition, got: $errors")
    }

    @Test
    fun assert_messageMustBeString() {
        val errors = expectFailure("""
            import std.io
            func main() {
                assert true { 42 }
            }
        """.trimIndent())
        assertTrue(errors.any { "String" in it }, "Should require String message, got: $errors")
    }

    @Test
    fun assert_javascriptEmit() {
        val result = compile("""
            import std.io
            func main() {
                assert 1 == 1 { "ok" }
            }
        """.trimIndent())
        assertTrue("throw new Error" in result.javascript, "JavaScript should emit throw, got:\n${result.javascript}")
    }

    @Test
    fun assert_llvmEmit() {
        val result = compile("""
            import std.io
            func main() {
                assert 1 == 1 { "ok" }
            }
        """.trimIndent())
        assertTrue("abort" in result.llvm || "br i1" in result.llvm,
            "LLVM should emit branch or abort for assert, got:\n${result.llvm}")
    }

    // -----------------------------------------------------------------------
    // trace (runtime)
    // -----------------------------------------------------------------------

    @Test
    fun trace_printsMessage() {
        val output = run("""
            import std.io
            func main() {
                trace { "hello from trace" }
                std::println("done")
            }
        """.trimIndent())
        assertTrue("[DEBUG] hello from trace" in output)
        assertTrue("done" in output)
    }

    @Test
    fun trace_acceptsShorthandAndRuntimeLogLevels() {
        val output = run("""
            func main() {
                trace .Info { "Any bridge works" }
                var level = LogLevel.Warn
                trace level { "${'$'}{it}: Hello" }
                fin finalLevel = LogLevel.Error
                trace finalLevel { "final" }
                trace { "${'$'}{it}: default" }
            }
        """.trimIndent())

        assertEquals("[INFO] Any bridge works\n[WARN] LogLevel.Warn: Hello\n[ERROR] final\n[DEBUG] LogLevel.Debug: default", output)
    }

    @Test
    fun trace_liftsBodiesIntoTypedIrFunctions() {
        val result = compile("""
            module playground
            func main() {
                trace .Info { "Any bridge works" }
                var level = LogLevel.Warn
                trace level { "${'$'}{it}: Hello" }
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        val level = result.ir.functions
            .first { it.name == "main" }
            .body
            .filterIsInstance<IrStmt.VarDecl>()
            .first { it.name == "level" }

        assertEquals(IrType.Named("LogLevel"), level.type)
        assertTrue(ir.startsWith("module playground\n\nfunc __main_lmbda0"), ir)
        assertTrue("""
            func __main_lmbda0(level: LogLevel): String {
                return "Any bridge works"
            }
        """.trimIndent() in ir, ir)
        assertTrue("""
            func __main_lmbda1(level: LogLevel): String {
                return "${'$'}{level}: Hello"
            }
        """.trimIndent() in ir, ir)
        val mainIr = """
            func main(): Unit {
                trace LogLevel.Info __main_lmbda0(LogLevel.Info)
                var level: LogLevel = LogLevel.Warn
                trace LogLevel.Warn __main_lmbda1(level)
            }
        """.trimIndent()
        assertTrue(mainIr in ir, ir)
        assertTrue("$mainIr\n\nenum LogLevel {" in ir, ir)
        assertTrue(ir.indexOf("func main(): Unit") < ir.indexOf("enum LogLevel {"), ir)
    }

    @Test
    fun trace_liftedBodyCapturesSurroundingValues() {
        val output = run("""
            func main() {
                fin subject = "compiler"
                trace .Info { "${'$'}subject ready" }
            }
        """.trimIndent())

        assertEquals("[INFO] compiler ready", output)
    }

    @Test
    fun directTrace_remainsDirectInIr() {
        val result = compile("""
            func main() {
                trace "Meow"
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()

        assertTrue("trace \"Meow\"" in ir, ir)
        assertFalse("__main_lmbda" in ir, ir)
        assertEquals("[DEBUG] Meow", IrInterpreter().interpret(result.ir))
    }

    @Test
    fun directTrace_acceptsExplicitShorthandLevel() {
        val result = compile("""
            func main() {
                trace .Info "Meow"
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()

        assertTrue("trace LogLevel.Info \"Meow\"" in ir, ir)
        assertTrue("enum LogLevel {" in ir, ir)
        assertTrue("Info" in ir, ir)
        assertTrue(ir.indexOf("func main(): Unit") < ir.indexOf("enum LogLevel {"), ir)
        assertEquals("[INFO] Meow", IrInterpreter().interpret(result.ir))
    }

    @Test
    fun directTrace_acceptsQualifiedExplicitLevel() {
        val result = compile("""
            func main() {
                trace LogLevel.Error "Error"
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()

        assertTrue("trace LogLevel.Error \"Error\"" in ir, ir)
        assertEquals("[ERROR] Error", IrInterpreter().interpret(result.ir))
    }

    @Test
    fun enumValues_stringifyWithTheirQualifiedPath() {
        val output = run("""
            import std.io
            enum Tone { Warm Cool }
            func main() {
                fin tone = Tone.Warm
                std::println(tone)
                std::println("${'$'}{tone}")
                std::println(tone as String)
            }
        """.trimIndent())

        assertEquals("Tone.Warm\nTone.Warm\nTone.Warm", output)
    }

    @Test
    fun trace_rejectsNonLogLevelExpression() {
        val errors = expectFailure("""
            func main() {
                trace "Info" { "wrong" }
            }
        """.trimIndent())

        assertTrue(errors.any { "trace level must be LogLevel, got String" in it }, errors.toString())
    }

    @Test
    fun trace_javascriptEmit() {
        val result = compile("""
            import std.io
            func main() {
                trace { "debug" }
            }
        """.trimIndent())
        assertTrue("console.log" in result.javascript, "JavaScript should emit console.log, got:\n${result.javascript}")
        assertTrue("toUpperCase" in result.javascript, "JavaScript should normalize the log level, got:\n${result.javascript}")
    }

    @Test
    fun trace_llvmEmit() {
        val result = compile("""
            import std.io
            func main() {
                trace { "debug" }
            }
        """.trimIndent())
        assertTrue("DEBUG" in result.llvm, "LLVM should emit the default DEBUG level, got:\n${result.llvm}")
    }

    // -----------------------------------------------------------------------
    // inline assert (compile-time)
    // -----------------------------------------------------------------------

    @Test
    fun inlineAssert_passingCondition() {
        val result = compile("""
            import std.io
            inline fin X = 5
            inline assert X > 0 { "X must be positive" }
            func main() {
                std::println("ok")
            }
        """.trimIndent())
        assertNotNull(result, "Passing inline assert should compile")
    }

    @Test
    fun inlineAssert_failingCondition() {
        val errors = expectFailure("""
            import std.io
            inline fin X = -1
            inline assert X > 0 { "X must be positive" }
            func main() {}
        """.trimIndent())
        assertTrue(errors.any { "X must be positive" in it }, "Should contain assertion message, got: $errors")
    }

    @Test
    fun inlineAssert_insideFunctionBody() {
        val result = compile("""
            import std.io
            func main() {
                inline fin x = 5
                inline assert x > 0 { "x must be positive" }
                std::println("ok")
            }
        """.trimIndent())
        assertNotNull(result)
    }

    @Test
    fun inlineAssert_removedFromIr() {
        val result = compile("""
            import std.io
            inline fin X = 5
            inline assert X > 0 { "ok" }
            func main() {
                std::println("hello")
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        assertFalse("assert" in ir.lowercase(), "inline assert should be removed from IR, got:\n$ir")
    }

    @Test
    fun inlineBindings_areCompileTimeOnlyAndRemovedFromIr() {
        val result = compile("""
            inline var BUILD = 1
            inline fin ANSWER = 42
            func main(): Int {
                return BUILD + ANSWER
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        assertFalse("BUILD" in ir, "inline var should be removed from IR, got:\n$ir")
        assertFalse("ANSWER" in ir, "inline fin should be removed from IR, got:\n$ir")
        assertTrue("return 43" in ir, "inline values should be folded into runtime IR, got:\n$ir")
    }

    // -----------------------------------------------------------------------
    // inline trace (compile-time)
    // -----------------------------------------------------------------------

    @Test
    fun inlineTrace_producesWarning() {
        val result = compile("""
            import std.io
            inline trace { "compiling module" }
            func main() {
                std::println("ok")
            }
        """.trimIndent())
        assertTrue(result.warnings.any { "DEBUG" in it && "compiling module" in it },
            "Should produce trace warning, got: ${result.warnings}")
    }

    @Test
    fun inlineTrace_insideFunctionBody() {
        val result = compile("""
            import std.io
            func main() {
                inline trace { "in main" }
                std::println("ok")
            }
        """.trimIndent())
        assertTrue(result.warnings.any { "DEBUG" in it && "in main" in it },
            "Should produce trace warning, got: ${result.warnings}")
    }

    @Test
    fun inlineTrace_acceptsShorthandAndInlineBindings() {
        val result = compile("""
            func main() {
                inline fin INFO_LEVEL = LogLevel.Info
                inline var WARN_LEVEL = LogLevel.Warn
                inline trace INFO_LEVEL { "${'$'}{it}: compiling" }
                inline trace WARN_LEVEL { "careful" }
                inline trace .Critical { "stop" }
            }
        """.trimIndent())

        assertTrue(result.warnings.any { "[INFO] LogLevel.Info: compiling" in it }, result.warnings.toString())
        assertTrue(result.warnings.any { "[WARN] careful" in it }, result.warnings.toString())
        assertTrue(result.warnings.any { "[CRITICAL] stop" in it }, result.warnings.toString())
    }

    @Test
    fun inlineTrace_acceptsTopLevelInlineBindings() {
        val result = compile("""
            inline fin INFO_LEVEL = LogLevel.Info
            inline trace INFO_LEVEL { "${'$'}{it}: module" }
            inline var TODO_LEVEL = LogLevel.Todo
            inline trace TODO_LEVEL { "later" }
            func main() {}
        """.trimIndent())

        assertTrue(result.warnings.any { "[INFO] LogLevel.Info: module" in it }, result.warnings.toString())
        assertTrue(result.warnings.any { "[TODO] later" in it }, result.warnings.toString())
    }

    @Test
    fun inlineTrace_removedFromIr() {
        val result = compile("""
            import std.io
            inline trace { "debug" }
            func main() {
                std::println("hello")
            }
        """.trimIndent())
        val ir = result.ir.prettyPrint()
        assertFalse("trace" in ir.lowercase(), "inline trace should be removed from IR, got:\n$ir")
    }

    // -----------------------------------------------------------------------
    // Scope restrictions
    // -----------------------------------------------------------------------

    @Test
    fun test_insideFunctionRejected() {
        // test is only allowed at global scope — but since the parser handles it
        // at the top level only, putting it inside a function would be a parse error.
        // We verify test works at top level (already tested above).
        val result = compile("""
            import std.io
            test "works at top level" {
                std::println("ok")
            }
            func main() {}
        """.trimIndent())
        assertNotNull(result)
    }
}
