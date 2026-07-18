package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

class PrimitiveTypeTest {

    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    private fun run(source: String): String {
        val result = compile(source)
        return IrInterpreter().interpret(result.ir)
    }

    private fun expectFailure(source: String): List<String> {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Failure>(result, "Expected compilation failure but got success")
        return result.errors
    }

    // -----------------------------------------------------------------------
    // Int (default, 32-bit signed)
    // -----------------------------------------------------------------------

    @Test
    fun int_defaultLiteral() {
        val output = run("import std.io\nfunc main() { std::println(42) }".trimIndent())
        assertEquals("42", output)
    }

    @Test
    fun int_arithmetic() {
        val output = run("import std.io\nfunc main() { std::println(10 + 20) }".trimIndent())
        assertEquals("30", output)
    }

    @Test
    fun int_hexLiteral() {
        val result = compile("import std.io\nfunc main() { var x: Int = 0xFF }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("255" in ir, "0xFF should parse to 255, got:\n$ir")
    }

    @Test
    fun int_binaryLiteral() {
        val result = compile("import std.io\nfunc main() { var x: Int = 0b1010 }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("10" in ir, "0b1010 should parse to 10, got:\n$ir")
    }

    @Test
    fun int_octalLiteral() {
        val result = compile("import std.io\nfunc main() { var x: Int = 0o77 }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("63" in ir, "0o77 should parse to 63, got:\n$ir")
    }

    @Test
    fun int_underscoreSeparator() {
        val result = compile("import std.io\nfunc main() { var x: Int = 1_000_000 }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("1000000" in ir, "1_000_000 should parse to 1000000, got:\n$ir")
    }

    // -----------------------------------------------------------------------
    // Char
    // -----------------------------------------------------------------------

    @Test
    fun char_simpleLiteral() {
        val result = compile("import std.io\nfunc main() { var c: Char = 'a' }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("'a'" in ir, "Char literal should be 'a', got:\n$ir")
    }

    @Test
    fun char_escapedNewline() {
        val result = compile("""import std.io
func main() { var c: Char = '\n' }""".trimIndent())
        assertNotNull(result)
    }

    @Test
    fun char_escapedBackslash() {
        val result = compile("""import std.io
func main() { var c: Char = '\\' }""".trimIndent())
        assertNotNull(result)
    }

    @Test
    fun char_unicodeEscape() {
        val result = compile("""import std.io
func main() { var c: Char = '\u0041' }""".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("'A'" in ir, "\\u0041 should be 'A', got:\n$ir")
    }

    @Test
    fun char_javascriptEmit() {
        val result = compile("import std.io\nfunc main() { var c: Char = 'z' }".trimIndent())
        assertTrue("\"z\"" in result.javascript, "JavaScript should emit char as string \"z\", got:\n${result.javascript}")
    }

    // -----------------------------------------------------------------------
    // Short (16-bit signed, suffix: s)
    // -----------------------------------------------------------------------

    @Test
    fun short_suffixLiteral() {
        val result = compile("import std.io\nfunc main() { var x: Short = 42s }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("Short" in ir, "Type should be Short, got:\n$ir")
    }

    @Test
    fun short_arithmetic() {
        val result = compile("""
            import std.io
            func main() {
                var a: Short = 10s
                var b: Short = 20s
                std::println(a + b)
            }
        """.trimIndent())
        assertNotNull(result)
    }

    // -----------------------------------------------------------------------
    // UShort (16-bit unsigned, suffix: us)
    // -----------------------------------------------------------------------

    @Test
    fun ushort_suffixLiteral() {
        val result = compile("import std.io\nfunc main() { var x: UShort = 42us }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("UShort" in ir, "Type should be UShort, got:\n$ir")
    }

    // -----------------------------------------------------------------------
    // Long (64-bit signed, suffix: L)
    // -----------------------------------------------------------------------

    @Test
    fun long_suffixLiteral() {
        val result = compile("import std.io\nfunc main() { var x: Long = 42L }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("Long" in ir, "Type should be Long, got:\n$ir")
    }

    @Test
    fun long_javascriptEmit() {
        val result = compile("import std.io\nfunc main() { var x: Long = 42L }".trimIndent())
        assertTrue("42n" in result.javascript, "JavaScript should emit 42n (bigint), got:\n${result.javascript}")
    }

    @Test
    fun long_llvmEmit() {
        val result = compile("import std.io\nfunc main() { var x: Long = 42L }".trimIndent())
        assertTrue("i64" in result.llvm, "LLVM should use i64 for Long, got:\n${result.llvm}")
    }

    // -----------------------------------------------------------------------
    // ULong (64-bit unsigned, suffix: uL)
    // -----------------------------------------------------------------------

    @Test
    fun ulong_suffixLiteral() {
        val result = compile("import std.io\nfunc main() { var x: ULong = 42uL }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("ULong" in ir, "Type should be ULong, got:\n$ir")
    }

    // -----------------------------------------------------------------------
    // Cent (128-bit signed, suffix: c)
    // -----------------------------------------------------------------------

    @Test
    fun cent_suffixLiteral() {
        val result = compile("import std.io\nfunc main() { var x: Cent = 42c }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("Cent" in ir, "Type should be Cent, got:\n$ir")
    }

    @Test
    fun cent_llvmEmit() {
        val result = compile("import std.io\nfunc main() { var x: Cent = 42c }".trimIndent())
        assertTrue("i128" in result.llvm, "LLVM should use i128 for Cent, got:\n${result.llvm}")
    }

    // -----------------------------------------------------------------------
    // UCent (128-bit unsigned, suffix: uc)
    // -----------------------------------------------------------------------

    @Test
    fun ucent_suffixLiteral() {
        val result = compile("import std.io\nfunc main() { var x: UCent = 42uc }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("UCent" in ir, "Type should be UCent, got:\n$ir")
    }

    // -----------------------------------------------------------------------
    // Float (32-bit, suffix: f)
    // -----------------------------------------------------------------------

    @Test
    fun float_suffixLiteral() {
        val result = compile("import std.io\nfunc main() { var x: Float = 3.14f }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("Float" in ir, "Type should be Float, got:\n$ir")
    }

    @Test
    fun float_llvmEmit() {
        val result = compile("import std.io\nfunc main() { var x: Float = 3.14f }".trimIndent())
        assertTrue("float" in result.llvm, "LLVM should use float for Float, got:\n${result.llvm}")
    }

    // -----------------------------------------------------------------------
    // Real (default float, 64-bit double)
    // -----------------------------------------------------------------------

    @Test
    fun real_defaultLiteral() {
        val result = compile("import std.io\nfunc main() { var x: Real = 3.14 }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("Real" in ir, "Type should be Real, got:\n$ir")
    }

    @Test
    fun real_llvmEmit() {
        val result = compile("import std.io\nfunc main() { var x: Real = 3.14 }".trimIndent())
        assertTrue("double" in result.llvm, "LLVM should use double for Real, got:\n${result.llvm}")
    }

    // -----------------------------------------------------------------------
    // Decimal (128-bit, suffix: D)
    // -----------------------------------------------------------------------

    @Test
    fun decimal_suffixLiteral() {
        val result = compile("import std.io\nfunc main() { var x: Decimal = 3.14D }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("Decimal" in ir, "Type should be Decimal, got:\n$ir")
    }

    @Test
    fun decimal_llvmEmit() {
        val result = compile("import std.io\nfunc main() { var x: Decimal = 3.14D }".trimIndent())
        assertTrue("fp128" in result.llvm, "LLVM should use fp128 for Decimal, got:\n${result.llvm}")
    }

    // -----------------------------------------------------------------------
    // Hex literals with type suffixes
    // -----------------------------------------------------------------------

    @Test
    fun hex_withLongSuffix() {
        val result = compile("import std.io\nfunc main() { var x: Long = 0xFFL }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("Long" in ir, "0xFFL should be Long, got:\n$ir")
        assertTrue("255" in ir, "0xFF should be 255, got:\n$ir")
    }

    @Test
    fun hex_withShortSuffix() {
        val result = compile("import std.io\nfunc main() { var x: Short = 0xFFs }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("Short" in ir, "0xFFs should be Short, got:\n$ir")
    }

    @Test
    fun hex_noSuffix() {
        val result = compile("import std.io\nfunc main() { var x: Int = 0xFF }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("Int" in ir, "0xFF without suffix should be Int, got:\n$ir")
    }

    // -----------------------------------------------------------------------
    // Binary/Octal literals
    // -----------------------------------------------------------------------

    @Test
    fun binary_literal() {
        val result = compile("import std.io\nfunc main() { var x: Int = 0b11111111 }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("255" in ir, "0b11111111 should be 255, got:\n$ir")
    }

    @Test
    fun binary_withUnderscore() {
        val result = compile("import std.io\nfunc main() { var x: Int = 0b1111_0000 }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("240" in ir, "0b1111_0000 should be 240, got:\n$ir")
    }

    @Test
    fun octal_literal() {
        val result = compile("import std.io\nfunc main() { var x: Int = 0o777 }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("511" in ir, "0o777 should be 511, got:\n$ir")
    }

    // -----------------------------------------------------------------------
    // Type mismatch errors
    // -----------------------------------------------------------------------

    @Test
    fun typeMismatch_intToShort() {
        val errors = expectFailure("import std.io\nfunc main() { var x: Short = 42 }".trimIndent())
        assertTrue(errors.any { "type mismatch" in it }, "Should get type mismatch, got: $errors")
    }

    @Test
    fun typeMismatch_longToInt() {
        val errors = expectFailure("import std.io\nfunc main() { var x: Int = 42L }".trimIndent())
        assertTrue(errors.any { "type mismatch" in it }, "Should get type mismatch, got: $errors")
    }

    @Test
    fun typeMismatch_floatToReal() {
        val errors = expectFailure("import std.io\nfunc main() { var x: Real = 3.14f }".trimIndent())
        assertTrue(errors.any { "type mismatch" in it }, "Should get type mismatch, got: $errors")
    }

    @Test
    fun typeMismatch_charToInt() {
        val errors = expectFailure("import std.io\nfunc main() { var x: Int = 'a' }".trimIndent())
        assertTrue(errors.any { "type mismatch" in it }, "Should get type mismatch, got: $errors")
    }

    // -----------------------------------------------------------------------
    // Type inference from suffix
    // -----------------------------------------------------------------------

    @Test
    fun inference_shortFromSuffix() {
        val result = compile("import std.io\nfunc main() { var x = 42s\n std::println(x) }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("Short" in ir, "Should infer Short from 42s, got:\n$ir")
    }

    @Test
    fun inference_longFromSuffix() {
        val result = compile("import std.io\nfunc main() { var x = 42L\n std::println(x) }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("Long" in ir, "Should infer Long from 42L, got:\n$ir")
    }

    @Test
    fun inference_floatFromSuffix() {
        val result = compile("import std.io\nfunc main() { var x = 3.14f\n std::println(x) }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("Float" in ir, "Should infer Float from 3.14f, got:\n$ir")
    }

    @Test
    fun inference_charFromLiteral() {
        val result = compile("import std.io\nfunc main() { var c = 'x'\n std::println(c) }".trimIndent())
        val ir = result.ir.prettyPrint()
        assertTrue("Char" in ir, "Should infer Char from 'x', got:\n$ir")
    }
}
