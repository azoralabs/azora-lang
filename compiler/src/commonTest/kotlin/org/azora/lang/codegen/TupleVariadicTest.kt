package org.azora.lang.codegen

import org.azora.lang.*
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.*
import java.io.File
import kotlin.test.*

class TupleVariadicTest {
    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    @Test fun genericImplReflectedFieldLoopParses() {
        val program = Parser(Lexer("""
            spec PrettyPrint { prop &.pretty: String }
            impl PrettyPrint for Tuple<...T> {
                prop &.pretty: String {
                    inline for field in reflect<Self>.fields with index {
                        trace { field.value }
                    }
                    return ""
                }
            }
        """.trimIndent()).tokenize()).parse()

        val impl = program.items.filterIsInstance<TopLevel.Impl>().single()
        assertEquals("T", impl.variadicParam)
        val loop = impl.methods.single().body.filterIsInstance<Stmt.InlineFor>().single()
        assertEquals("index", loop.indexName)
        val fields = assertIs<Expr.Member>(loop.iterable)
        assertEquals("fields", fields.name)
        val reflect = assertIs<Expr.Call>(fields.target)
        assertEquals("__reflect", reflect.callee)
        assertEquals("Self", assertIs<Expr.Identifier>(reflect.args.single()).name)
    }

    @Test fun aTupleLiteralTakesItsElementsTypes() {
        val out = compile("""
            import std.io
            func main() {
                fin x = (1, 2.0)
                println(x.0)
                println(x.1)
            }
        """.trimIndent())
        assertEquals("1\n2.0", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun aTupleLiteralTakesItsAnnotation() {
        val src = """
            import std.io
            func main() {
                fin x: Tuple<Int, Float> = (1, 2.0)
                println(x.0)
                println(x.1)
            }
        """.trimIndent()
        val out = compile(src)
        assertEquals("1\n2.0", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun aTupleIsBuiltByItsTypeInBothForms() {
        val a = compile("""
            import std.io
            func main() {
                fin x: (Int, Double) = Tuple<Int, Double>(1, 2.0)
                println(x.0)
                println(x.1)
            }
        """.trimIndent())
        val b = compile("""
            import std.io
            func main() {
                fin x = Tuple<Int, Double>(1, 2.0)
                println(x.0)
                println(x.1)
            }
        """.trimIndent())
        assertEquals("1\n2.0", IrInterpreter().interpret(a.ir).trim())
        assertEquals("1\n2.0", IrInterpreter().interpret(b.ir).trim())
    }

    @Test fun aTupleOfThreeElements() {
        val src = """
            import std.io
            func main() {
                fin t = (true, "hi", 42)
                println(t.0)
                println(t.1)
                println(t.2)
            }
        """.trimIndent()
        val out = compile(src)
        assertEquals("true\nhi\n42", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleElementIsCheckAndEquality() {
        // Mirrors the `assert tup.0 is Int && tup.0 == 1` form used in Tuple.az's own tests.
        // An unsuffixed real is a `Float` (`Literals.DEFAULT_FLOAT`).
        val src = """
            import std.io
            func main() {
                fin tup = (1, 2.0, "3")
                if tup.0 is Int && tup.0 == 1 { println("ok0") }
                if tup.1 is Float && tup.1 == 2.0 { println("ok1") }
                if tup.2 is String && tup.2 == "3" { println("ok2") }
            }
        """.trimIndent()
        val out = compile(src)
        assertEquals("ok0\nok1\nok2", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleModuleImportExposesTuple() {
        val r = Compiler().compile("""
            import std.io
            import std.container.tuple
            func main() {
                fin x: Tuple<Int, Int> = (1, 2)
                println(x.0)
            }
        """.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(r, "import std failed: ${(r as? CompilationResult.Failure)?.errors}")
    }

    @Test fun aTupleDisplaysWithItsType() {
        val out = compile($$"""
            module playground
            import std.io
            import std.container.tuple

            pack App {
                var name: String
            }

            impl App {
                func &.greet(): String {
                    return "Hello from ${self.name}!"
                }
            }

            func main() {
                fin app = App("Azora")
                println((app.greet(), ":)"))
            }
        """.trimIndent())

        assertEquals(
            "Tuple<String, String>(\"Hello from Azora!\", \":)\")",
            IrInterpreter().interpret(out.ir).trim(),
        )
    }

    @Test fun tuplePrettyUsesReflectedFields() {
        val result = compile("""
            module playground
            import std.io
            import std.container.tuple

            func main() {
                fin value = ("left", "right")
                println(value.pretty)
            }
        """.trimIndent())

        assertEquals("(left, right)", IrInterpreter().interpret(result.ir).trim())
        val irText = result.ir.toString()
        assertFalse("__reflect" in irText, irText)
        assertFalse("Self" in irText, irText)
        assertFalse("field.value" in irText, irText)
    }

    @Test fun stringAppendAssignmentConvertsItsOperand() {
        val result = compile("""
            import std.io

            func main() {
                var value = "count="
                value += 7
                println(value)
            }
        """.trimIndent())

        assertEquals("count=7", IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun generalMixinConvertsStringToCode() {
        // `inline "<string>"` is a general statement: the string is parsed as code and spliced.
        val out = compile("""
            import std.io
            func main() {
                inline "println(40 + 2)"
            }
        """.trimIndent())
        assertEquals("42", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tuplePassedToAndReturnedFromFunction() {
        val src = """
            import std.io
            import std.container.tuple
            func swap(t: Tuple<Int, Float>): Tuple<Float, Int> {
                return Tuple<Float, Int>(t.1, t.0)
            }
            func main() {
                fin r = swap((7, 9.0))
                println(r.0)
                println(r.1)
            }
        """.trimIndent()
        val out = compile(src)
        assertEquals("9.0\n7", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleElementTypesInferFromArithmeticExpressions() {
        val out = compile("""
            import std.io
            import std.container.tuple

            func divmod(a: Int, b: Int): Tuple<Int, Int> {
                return (a / b, a % b)
            }

            func main() {
                fin result = divmod(17, 5)
                println(result.0)
                println(result.1)
            }
        """.trimIndent())

        assertEquals("3\n2", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleOutputIncludesItsFullScopeQualifiedSignature() {
        val out = compile("""
            import std.io
            import std.container.tuple

            func main() {
                println((17 / 5, 17 % 5))
            }
        """.trimIndent())

        assertEquals(
            "Tuple<Int, Int>(3, 2)",
            IrInterpreter().interpret(out.ir).trim(),
        )
    }

    @Test fun aTupleIsTheCompilersOwnTypeInIr() {
        // `(Int, Int)` is one structural type (GTC §6.3): no pack is declared
        // for a shape, and the library's `println` keeps its bridge name.
        val out = compile("""
            import std.io

            func main() {
                println((17 / 5, 17 % 5).0)
            }
        """.trimIndent())

        val ir = out.ir.prettyPrint()
        assertFalse("pack __Tuple" in ir, ir)
        assertFalse("tupleOf" in ir, ir)
        assertContains(ir, "bridge func println")
        assertEquals("3", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun aTupleTypeNeedsNoImport() {
        // `Tuple<…>` names the built-in tuple, as `(…)` does; neither is a
        // library declaration a program has to import.
        val out = compile("""
            import std.io

            func divmod(a: Int, b: Int): Tuple<Int, Int> {
                return (a / b, a % b)
            }

            func main() {
                fin result: (Int, Int) = divmod(17, 5)
                println(result.1)
            }
        """.trimIndent())
        assertEquals("2", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun aScopeQualifierSurvivesParsingWithoutChangingTypeIdentity() {
        // A scope is what still qualifies a name, now that a library does not.
        // The qualifier records how the source reached the type; it must not make
        // it a different type from the one reached without it.
        val program = Parser(Lexer("""
            func divmod(a: Int, b: Int): shapes::Tuple<Int, Int> {
                return (a / b, a % b)
            }
        """.trimIndent()).tokenize()).parse()

        val returnType = assertIs<TypeAnnotation.Explicit>(
            program.functions.single().returnType,
        ).ref
        val tuple = assertIs<TypeRef.Named>(returnType)
        assertEquals("Tuple", tuple.name)
        assertEquals("shapes", tuple.qualifier)
        assertEquals(
            TypeRef.Named("Tuple", tuple.args),
            tuple,
            "source qualification must not create a different semantic type",
        )
    }

    @Test fun nestedTuple() {
        val src = """
            import std.io
            func main() {
                fin outer = ((1, 2), 3)
                println(outer.0.0)
                println(outer.0.1)
                println(outer.1)
            }
        """.trimIndent()
        val out = compile(src)
        assertEquals("1\n2\n3", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun aOneElementTupleIsRejected() {
        // GTC §6.4: a tuple has at least two elements; `(x)` groups.
        for (source in listOf(
            "func main() {\n    fin x = (1,)\n}",
            "func f(x: (Int,)): Int { return 0 }",
            "func f(x: Tuple<Int>): Int { return 0 }",
        )) {
            val errors = try {
                (Compiler().compile(source, release = false) as? CompilationResult.Failure)?.errors
            } catch (e: IllegalStateException) {
                listOf(e.message.orEmpty())
            } ?: error("expected '$source' to be rejected, but it compiled")
            assertTrue(errors.any { "a tuple has at least two elements" in it }, "$source: $errors")
        }
    }

    @Test fun tupleTestsAzFileParses() {
        val src = java.io.File("../std/container/tuple.az").readText()
        Parser(Lexer(src).tokenize()).parse()
    }
}
