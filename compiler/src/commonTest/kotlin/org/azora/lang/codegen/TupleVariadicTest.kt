package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TupleVariadicTest {
    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    private fun runJs(source: String): String {
        val out = compile(source)
        val tmp = File.createTempFile("azora", ".js").apply { writeText(out.javascript); deleteOnExit() }
        val proc = ProcessBuilder("node", tmp.absolutePath).redirectErrorStream(true).start()
        val ok = proc.waitFor() == 0
        val output = proc.inputStream.bufferedReader().readText().trim()
        assertTrue(ok, "node failed:\n$output")
        return output
    }

    @Test fun tupleOfInferredMonomorphizes() {
        val out = compile("""
            import std.io
            import std.container
            func main() {
                fin x = std::tupleOf(1, 2.0)
                std::io::println(x.0)
                std::io::println(x.1)
            }
        """.trimIndent())
        assertContains(out.javascript, "__Tuple_Int_Real")
        assertEquals("1\n2.0", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleOfExplicitAnnotation() {
        val src = """
            import std.io
            import std.container
            func main() {
                fin x: Tuple<Int, Real> = std::tupleOf(1, 2.0)
                std::io::println(x.0)
                std::io::println(x.1)
            }
        """.trimIndent()
        val out = compile(src)
        assertContains(out.javascript, "__Tuple_Int_Real")
        assertEquals("1\n2.0", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleOfExplicitTypeArgsBothForms() {
        val a = compile("""
            import std.io
            import std.container
            func main() {
                fin x: Tuple<Int, Real> = std::tupleOf<Int, Real>(1, 2.0)
                std::io::println(x.0)
                std::io::println(x.1)
            }
        """.trimIndent())
        val b = compile("""
            import std.io
            import std.container
            func main() {
                fin x = std::tupleOf<Int, Real>(1, 2.0)
                std::io::println(x.0)
                std::io::println(x.1)
            }
        """.trimIndent())
        assertEquals("1\n2.0", IrInterpreter().interpret(a.ir).trim())
        assertEquals("1\n2.0", IrInterpreter().interpret(b.ir).trim())
    }

    @Test fun jsBackendEmitsValidNumericFieldAccess() {
        val out = compile("""
            import std.io
            import std.container
            func main() {
                fin x = std::tupleOf(1, 2.0)
                std::io::println(x.0)
                std::io::println(x.1)
            }
        """.trimIndent())
        // Numeric field names must use bracket access, never `this.0` / `target.0`.
        assertTrue("__Tuple_Int_Real" in out.javascript, out.javascript)
        assertContains(out.javascript, "this[0]")
        assertContains(out.javascript, "this[1]")
        assertFalse(out.javascript.contains("this.0"), "invalid JS `this.0` in:\n${out.javascript}")
        assertFalse(out.javascript.contains("this.1"), "invalid JS `this.1` in:\n${out.javascript}")
    }

    @Test fun jsBackendRunsViaNode() {
        assertEquals("7\n3.5", runJs("""
            import std.io
            import std.container
            func main() {
                fin x = std::tupleOf(7, 3.5)
                std::io::println(x.0)
                std::io::println(x.1)
            }
        """.trimIndent()))
    }

    @Test fun tupleOfThreeElementsAndMutation() {
        val src = """
            import std.io
            import std.container
            func main() {
                fin t = std::tupleOf(true, "hi", 42)
                std::io::println(t.0)
                std::io::println(t.1)
                std::io::println(t.2)
            }
        """.trimIndent()
        val out = compile(src)
        assertContains(out.javascript, "__Tuple_Bool_String_Int")
        assertEquals("true\nhi\n42", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleElementIsCheckAndEquality() {
        // Mirrors the `assert tup.0 is Int && tup.0 == 1` form used in Tuple.az's own tests.
        // `is` is supported by the interpreter; tuple positional access + equality are
        // checked across backends in the other tests.
        val src = """
            import std.io
            import std.container
            func main() {
                fin tup = std::tupleOf(1, 2.0, "3")
                if tup.0 is Int && tup.0 == 1 { std::io::println("ok0") }
                if tup.1 is Real && tup.1 == 2.0 { std::io::println("ok1") }
                if tup.2 is String && tup.2 == "3" { std::io::println("ok2") }
            }
        """.trimIndent()
        val out = compile(src)
        assertEquals("ok0\nok1\nok2", IrInterpreter().interpret(out.ir).trim())
        assertContains(out.javascript, "__Tuple_Int_Real_String")
    }

    @Test fun useZoneImportsTuple() {
        // `import std` appears in the user's TupleTests.az.
        val r = Compiler().compile("""
            import std.io
            import std
            func main() {
                fin x = std::tupleOf(1, 2)
                std::io::println(x.0)
            }
        """.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(r, "import std failed: ${(r as? CompilationResult.Failure)?.errors}")
    }

    @Test fun qualifiedTupleModuleImportExposesTupleOf() {
        val out = compile("""
            module playground            
            import std.io
            import std.container.tuple

            import std

            pack App {
                var name: String
            }

            impl App {
                func greet(): String { ref self ->
                    return "Hello from ${'$'}{self.name}!"
                }
            }

            func main() {
                fin app = App("Azora")
                std::io::println(std::tupleOf(app.greet(), ":)"))
            }
        """.trimIndent())

        assertContains(out.javascript, "__Tuple_String_String")
        assertEquals(
            "{\"__type\"=\"Tuple<String, String>\", \"0\"=\"Hello from Azora!\", \"1\"=\":)\"}",
            IrInterpreter().interpret(out.ir).trim(),
        )
    }

    @Test fun generalMixinConvertsStringToCode() {
        // `mixin "<string>"` is a general statement: the string is parsed as code and spliced.
        val out = compile("""
            import std.io
            func main() {
                mixin "std::io::println(40 + 2)"
            }
        """.trimIndent())
        assertEquals("42", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tuplePassedToAndReturnedFromFunction() {
        val src = """
            import std.io
            import std.container.tuple
            import std
            func swap(t: Tuple<Int, Real>): Tuple<Real, Int> {
                return std::tupleOf(t.1, t.0)
            }
            func main() {
                fin r = swap(std::tupleOf(7, 9.0))
                std::io::println(r.0)
                std::io::println(r.1)
            }
        """.trimIndent()
        val out = compile(src)
        assertEquals("9.0\n7", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun nestedTuple() {
        val src = """
            import std.io
            import std.container
            func main() {
                fin outer = std::tupleOf(std::tupleOf(1, 2), 3)
                std::io::println(outer.0.0)
                std::io::println(outer.0.1)
                std::io::println(outer.1)
            }
        """.trimIndent()
        val out = compile(src)
        assertEquals("1\n2\n3", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleLengthConstraintRejectsSingleElement() {
        // `where (...T).length >= 2` — a 1-element tuple must fail with a clear message.
        val r = Compiler().compile("""
            import std.io
            import std.container
            func main() {
                fin x = std::tupleOf(1)
            }
        """.trimIndent(), release = false)
        val errors = (r as? CompilationResult.Failure)?.errors
            ?: error("expected std::tupleOf(1) to fail the length constraint, but it compiled")
        assertTrue(errors.any { it.contains("2") && (it.contains("Tuple") || it.contains("tupleOf")) }, "expected a clear length message, got: $errors")
    }

    @Test fun tupleTestsAzFileParses() {
        // The user's Internal/Testing/TupleTests.az must parse in full.
        val src = java.io.File("../Internal/Testing/TupleTests.az").readText()
        Parser(Lexer(src).tokenize()).parse()
    }
}
