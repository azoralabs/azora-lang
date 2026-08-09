package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
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

    @Test fun genericImplReflectedFieldLoopParses() {
        val program = Parser(Lexer("""
            spec PrettyPrint { prop pretty[self: std::Self&]: std::String }
            impl<...T> PrettyPrint for std::Tuple {
                prop pretty[self: std::Self&]: std::String {
                    inline for field in std::reflect<std::Self>.fields with index {
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
            import std.container.*
            func main() {
                fin x = std::tupleOf(1, 2.0)
                std::println(x.0)
                std::println(x.1)
            }
        """.trimIndent())
        assertEquals("1\n2.0", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleOfExplicitAnnotation() {
        val src = """
            import std.io
            import std.container.*
            func main() {
                fin x: std::Tuple<std::Int, std::Double> = std::tupleOf(1, 2.0)
                std::println(x.0)
                std::println(x.1)
            }
        """.trimIndent()
        val out = compile(src)
        assertEquals("1\n2.0", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleOfExplicitTypeArgsBothForms() {
        val a = compile("""
            import std.io
            import std.container.*
            func main() {
                fin x: std::Tuple<std::Int, std::Double> = std::tupleOf<std::Int, std::Double>(1, 2.0)
                std::println(x.0)
                std::println(x.1)
            }
        """.trimIndent())
        val b = compile("""
            import std.io
            import std.container.*
            func main() {
                fin x = std::tupleOf<std::Int, std::Double>(1, 2.0)
                std::println(x.0)
                std::println(x.1)
            }
        """.trimIndent())
        assertEquals("1\n2.0", IrInterpreter().interpret(a.ir).trim())
        assertEquals("1\n2.0", IrInterpreter().interpret(b.ir).trim())
    }

    @Test fun tupleOfThreeElementsAndMutation() {
        val src = """
            import std.io
            import std.container.*
            func main() {
                fin t = std::tupleOf(true, "hi", 42)
                std::println(t.0)
                std::println(t.1)
                std::println(t.2)
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
            import std.container.*
            func main() {
                fin tup = std::tupleOf(1, 2.0, "3")
                if std::tup.0 is std::Int && std::tup.0 == 1 { std::println("ok0") }
                if std::tup.1 is std::Double && std::tup.1 == 2.0 { std::println("ok1") }
                if std::tup.2 is std::String && std::tup.2 == "3" { std::println("ok2") }
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
                fin x = std::tupleOf(1, 2)
                std::println(x.0)
            }
        """.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(r, "import std failed: ${(r as? CompilationResult.Failure)?.errors}")
    }

    @Test fun qualifiedTupleModuleImportExposesTupleOf() {
        val out = compile("""
            module playground
            import std.io
            import std.container.tuple

            pack App {
                var name: std::String
            }

            impl App {
                func greet[self: std::Self&](): std::String {
                    return "Hello from ${'$'}{self.name}!"
                }
            }

            func main() {
                fin app = App("Azora")
                std::println(std::tupleOf(app.greet(), ":)"))
            }
        """.trimIndent())

        assertEquals(
            "std::Tuple<String, String>(\"Hello from Azora!\", \":)\")",
            IrInterpreter().interpret(out.ir).trim(),
        )
    }

    @Test fun tuplePrettyUsesReflectedFields() {
        val result = compile("""
            module playground
            import std.io
            import std.container.tuple

            func main() {
                fin value = std::tupleOf("left", "right")
                std::println(value.pretty)
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
                std::println(value)
            }
        """.trimIndent())

        assertEquals("count=7", IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun generalMixinConvertsStringToCode() {
        // `inline "<string>"` is a general statement: the string is parsed as code and spliced.
        val out = compile("""
            import std.io
            func main() {
                inline "std::println(40 + 2)"
            }
        """.trimIndent())
        assertEquals("42", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tuplePassedToAndReturnedFromFunction() {
        val src = """
            import std.io
            import std.container.tuple
            func swap(t: std::Tuple<std::Int, std::Double>): std::Tuple<std::Double, std::Int> {
                return std::tupleOf<std::Double, std::Int>(t.1, t.0)
            }
            func main() {
                fin r = swap(std::tupleOf(7, 9.0))
                std::println(r.0)
                std::println(r.1)
            }
        """.trimIndent()
        val out = compile(src)
        assertEquals("9.0\n7", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleElementTypesInferFromArithmeticExpressions() {
        val out = compile("""
            import std.io
            import std.container.tuple

            func divmod(a: std::Int, b: std::Int): std::Tuple<std::Int, std::Int> {
                return std::tupleOf(a / b, a % b)
            }

            func main() {
                fin result = divmod(17, 5)
                std::println(result.0)
                std::println(result.1)
            }
        """.trimIndent())

        assertEquals("3\n2", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleOutputIncludesItsFullRealmQualifiedSignature() {
        val out = compile("""
            import std.io
            import std.container.tuple

            func main() {
                std::println(std::tupleOf(17 / 5, 17 % 5))
            }
        """.trimIndent())

        assertEquals(
            "std::Tuple<Int, Int>(3, 2)",
            IrInterpreter().interpret(out.ir).trim(),
        )
    }

    @Test fun qualifiedSymbolsUseCanonicalIrNames() {
        val out = compile("""
            import std.io
            import std.container.tuple

            func main() {
                std::println(std::tupleOf(17 / 5, 17 % 5))
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

    @Test fun tupleTypeRequiresItsDeclaredRealm() {
        val result = Compiler().compile("""
            import std.container.tuple

            func divmod(a: std::Int, b: std::Int): std::Tuple<std::Int, std::Int> {
                return std::tupleOf(a / b, a % b)
            }
        """.trimIndent(), release = false)

        val failure = assertIs<CompilationResult.Failure>(result)
        assertEquals(
            listOf("line 3: undefined type 'Tuple'; 'Tuple' is part of realm 'std', use 'std::Tuple' instead"),
            failure.errors,
        )
    }

    @Test fun tupleRealmQualifierSurvivesParsingWithoutChangingTypeIdentity() {
        val program = Parser(Lexer("""
            func divmod(a: std::Int, b: std::Int): std::Tuple<std::Int, std::Int> {
                return std::tupleOf(a / b, a % b)
            }
        """.trimIndent()).tokenize()).parse()

        val returnType = assertIs<org.azora.lang.frontend.TypeAnnotation.Explicit>(
            program.functions.single().returnType,
        ).ref
        val tuple = assertIs<org.azora.lang.frontend.TypeRef.Named>(returnType)
        assertEquals("Tuple", tuple.name)
        assertEquals("std", tuple.qualifier)
        assertEquals(
            org.azora.lang.frontend.TypeRef.Named("Tuple", tuple.args),
            tuple,
            "source qualification must not create a different semantic type",
        )
    }

    @Test fun nestedTuple() {
        val src = """
            import std.io
            import std.container.*
            func main() {
                fin outer = std::tupleOf(std::tupleOf(1, 2), 3)
                std::println(outer.0.0)
                std::println(outer.0.1)
                std::println(outer.1)
            }
        """.trimIndent()
        val out = compile(src)
        assertEquals("1\n2\n3", IrInterpreter().interpret(out.ir).trim())
    }

    @Test fun tupleLengthConstraintRejectsSingleElement() {
        // `where (...T).length >= 2` - a 1-element tuple must fail with a clear message.
        val r = Compiler().compile("""
            import std.io
            import std.container.*
            func main() {
                fin x = std::tupleOf(1)
            }
        """.trimIndent(), release = false)
        val errors = (r as? CompilationResult.Failure)?.errors
            ?: error("expected std::tupleOf(1) to fail the length constraint, but it compiled")
        assertTrue(errors.any { it.contains("2") && (it.contains("Tuple") || it.contains("tupleOf")) }, "expected a clear length message, got: $errors")
    }

    @Test fun tupleTestsAzFileParses() {
        val src = java.io.File("../std/container/tuple.az").readText()
        Parser(Lexer(src).tokenize()).parse()
    }
}
