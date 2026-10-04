package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FoundationNumericExecTest {
    private val source = """
        import std.io
        func main() {
            var n: Int = 2147483647
            println(n + 1)
            n++
            println(n)
            var b: Byte = 127
            println(b + Byte(1))
            var u: UByte = 255
            println(u + UByte(1))
            println((2147483647 + 1) < 0)
            fin min: Int = -2147483648
            println(-min)
        }
    """.trimIndent()
    private val expected = "-2147483648\n-2147483648\n-128\n0\ntrue\n-2147483648"

    @Test fun fixedWidthArithmeticAgreesBeforeAndAfterOptimization() {
        for (release in listOf(false, true)) {
            val r = assertIs<CompilationResult.Success>(Compiler().compile(source, release = release))
            assertEquals(expected, IrInterpreter().interpret(if (release) r.optimizedIr else r.ir).trim())
            if (LlvmExec.available) assertEquals(expected, LlvmExec.run(source, release))
            if (WasmExec.available) assertEquals(expected, WasmExec.runWat(r.wasm).trim())
        }
    }

    @Test fun integerLiteralsMustFitTheirPosition() {
        for (body in listOf(
            "fin x: Int = 2147483648",
            "fin x = 2147483648",
            "fin x: Byte = 128",
            "fin x = UByte(256)",
            "fin x: UInt = -1",
            "fin x: Long = 9223372036854775808",
            "fin x: ULong = 18446744073709551616",
        )) {
            val r = assertIs<CompilationResult.Failure>(Compiler().compile("func main() { $body }"), body)
            assertTrue(r.errors.any { "does not fit" in it }, r.errors.toString())
        }
        assertIs<CompilationResult.Success>(Compiler().compile("func main() { fin x: Long = 2147483648 }"))
    }

    @Test fun genericErasureDoesNotUnwrapNullableValues() {
        for (body in listOf(
            "func<T> bad(x: T?): T { return x }",
            "func<T> bad(): T { return null }",
            "func<T> bad() { fin value: T = null }",
            "func<T> bad(x: T) { var value: T = x\n value = null }",
            "func bad(): Int { return null }",
            "func bad(flag: Bool): Int { return if (flag) then null else 42 }",
            "func bad() { fin value: Int = null }",
            "func<T> bad(x: T?) { fin y: T = x }",
            "func<T> bad(x: T, opt: T?) { var y: T = x\n y = opt }",
        )) assertIs<CompilationResult.Failure>(Compiler().compile(body), body)
        assertIs<CompilationResult.Success>(Compiler().compile("func good(): Int { return null ?? 42 }"))
        assertIs<CompilationResult.Success>(Compiler().compile("func good(flag: Bool): Int? { return if (flag) then null else 42 }"))
        assertIs<CompilationResult.Success>(Compiler().compile("func<T> good(x: T): T? { return x }"))
        assertIs<CompilationResult.Success>(Compiler().compile("func<T> good(x: T?): T? { return x }"))
    }
}
