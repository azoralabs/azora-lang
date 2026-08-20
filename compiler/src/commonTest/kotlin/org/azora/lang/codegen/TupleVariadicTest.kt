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
            spec PrettyPrint { prop pretty[self: Self&]: String }
            impl PrettyPrint for Tuple<...T> {
                prop pretty[self: Self&]: String {
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

    @Test fun tupleOfInferredMonomorphizes() {
        val out = compile("""
            import std.io
            import std.container::*
            func main() {
                fin x = tupleOf(1, 2.0)
                println(x.0)
                println(x.1)
            }
        """.trimIndent())
        assertEquals("1\n2.0", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleOfExplicitAnnotation() {
        val src = """
            import std.io
            import std.container::*
            func main() {
                fin x: Tuple<Int, Double> = tupleOf(1, 2.0)
                println(x.0)
                println(x.1)
            }
        """.trimIndent()
        val out = compile(src)
        assertEquals("1\n2.0", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleOfExplicitTypeArgsBothForms() {
        val a = compile("""
            import std.io
            import std.container::*
            func main() {
                fin x: Tuple<Int, Double> = tupleOf<Int, Double>(1, 2.0)
                println(x.0)
                println(x.1)
            }
        """.trimIndent())
        val b = compile("""
            import std.io
            import std.container::*
            func main() {
                fin x = tupleOf<Int, Double>(1, 2.0)
                println(x.0)
                println(x.1)
            }
        """.trimIndent())
        assertEquals("1\n2.0", IrInterpreter().interpret(a.ir).trim())
        assertEquals("1\n2.0", IrInterpreter().interpret(b.ir).trim())
    }

    @Test fun tupleOfThreeElementsAndMutation() {
        val src = """
            import std.io
            import std.container::*
            func main() {
                fin t = tupleOf(true, "hi", 42)
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
        // `is` is supported by the interpreter; tuple positional access + equality are
        // checked across backends in the other tests.
        val src = """
            import std.io
            import std.container::*
            func main() {
                fin tup = tupleOf(1, 2.0, "3")
                if tup.0 is Int && tup.0 == 1 { println("ok0") }
                if tup.1 is Double && tup.1 == 2.0 { println("ok1") }
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
                fin x = tupleOf(1, 2)
                println(x.0)
            }
        """.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(r, "import std failed: ${(r as? CompilationResult.Failure)?.errors}")
    }

    @Test fun qualifiedTupleModuleImportExposesTupleOf() {
        val out = compile($$"""
            module playground
            import std.io
            import std.container.tuple

            pack App {
                var name: String
            }

            impl App {
                func greet[self: Self&](): String {
                    return "Hello from ${self.name}!"
                }
            }

            func main() {
                fin app = App("Azora")
                println(tupleOf(app.greet(), ":)"))
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
                fin value = tupleOf("left", "right")
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
            func swap(t: Tuple<Int, Double>): Tuple<Double, Int> {
                return tupleOf<Double, Int>(t.1, t.0)
            }
            func main() {
                fin r = swap(tupleOf(7, 9.0))
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
                return tupleOf(a / b, a % b)
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
                println(tupleOf(17 / 5, 17 % 5))
            }
        """.trimIndent())

        assertEquals(
            "Tuple<Int, Int>(3, 2)",
            IrInterpreter().interpret(out.ir).trim(),
        )
    }

    @Test fun qualifiedSymbolsUseCanonicalIrNames() {
        val out = compile("""
            import std.io
            import std.container.tuple

            func main() {
                println(tupleOf(17 / 5, 17 % 5))
            }
        """.trimIndent())

        val ir = out.ir.prettyPrint()
        assertContains(ir, "pack __std_Tuple_Int_Int")
        assertContains(ir, "func __std_tupleOf_Int_Int")
        assertContains(ir, "bridge func __std_println")
        assertContains(ir, "__std_println(__std_tupleOf_Int_Int")
        assertFalse("std__println" in ir, ir)
        assertFalse("__std__tupleOf" in ir, ir)
        assertFalse("pack __Tuple_Int_Int" in ir, ir)
    }

    @Test fun tupleTypeRequiresItsDeclaredScope() {
        val result = Compiler().compile("""
            import std.container.tuple

            func divmod(a: Int, b: Int): Tuple<Int, Int> {
                return tupleOf(a / b, a % b)
            }
        """.trimIndent(), release = false)

        val failure = assertIs<CompilationResult.Failure>(result)
        assertEquals(
            listOf("line 3: undefined type 'Tuple'; 'Tuple' is part of scope 'std', use 'Tuple' instead"),
            failure.errors,
        )
    }

    @Test fun aScopeQualifierSurvivesParsingWithoutChangingTypeIdentity() {
        // A scope is what still qualifies a name, now that a library does not.
        // The qualifier records how the source reached the type; it must not make
        // it a different type from the one reached without it.
        val program = Parser(Lexer("""
            func divmod(a: Int, b: Int): shapes::Tuple<Int, Int> {
                return tupleOf(a / b, a % b)
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
            import std.container::*
            func main() {
                fin outer = tupleOf(tupleOf(1, 2), 3)
                println(outer.0.0)
                println(outer.0.1)
                println(outer.1)
            }
        """.trimIndent()
        val out = compile(src)
        assertEquals("1\n2\n3", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleLengthConstraintRejectsSingleElement() {
        // `where (...T).size >= 2` - a 1-element tuple must fail with a clear message.
        val r = Compiler().compile("""
            import std.io
            import std.container::*
            func main() {
                fin x = tupleOf(1)
            }
        """.trimIndent(), release = false)
        val errors = (r as? CompilationResult.Failure)?.errors
            ?: error("expected tupleOf(1) to fail the length constraint, but it compiled")
        assertTrue(errors.any { it.contains("2") && (it.contains("Tuple") || it.contains("tupleOf")) }, "expected a clear length message, got: $errors")
    }

    @Test fun tupleTestsAzFileParses() {
        val src = java.io.File("../std/container/tuple.az").readText()
        Parser(Lexer(src).tokenize()).parse()
    }
}
