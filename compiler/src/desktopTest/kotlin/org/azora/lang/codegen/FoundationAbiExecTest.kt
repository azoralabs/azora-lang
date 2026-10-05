package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Links generated code to separately compiled C, so LLVM's internal ABI cannot mask mistakes. */
class FoundationAbiExecTest {
    private fun clang(): String? {
        val paths = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { "$it/clang" } +
            listOf("/usr/bin/clang", "/opt/homebrew/opt/llvm/bin/clang")
        val result = paths.firstOrNull { File(it).canExecute() }
        check(result != null || System.getenv("AZORA_REQUIRE_NATIVE_TESTS") != "1") { "Native ABI qualification requires clang" }
        return result
    }

    private fun execute(expected: String, source: String, cSource: String) {
        val tool = clang() ?: return
        val directory = Files.createTempDirectory("azora-c-abi-").toFile()
        try {
            val c = directory.resolve("adapter.c").apply { writeText(cSource) }
            for (release in listOf(false, true)) {
                val compiled = Compiler().compile(source, release = release)
                val result = assertIs<CompilationResult.Success>(compiled, (compiled as? CompilationResult.Failure)?.errors.toString())
                assertTrue(result.llvm.isNotEmpty(), result.backendErrors.toString())
                val ir = directory.resolve("main.ll").apply { writeText(result.llvm) }
                val binary = directory.resolve("probe")
                run(directory, tool, ir.path, c.path, "-fsanitize=address,undefined", "-o", binary.path)
                assertEquals(expected, run(directory, binary.path).trim(), "release=$release")
            }
        } finally { directory.deleteRecursively() }
    }

    private fun run(directory: File, vararg arguments: String): String {
        val output = directory.resolve("stdout")
        val errors = directory.resolve("stderr")
        val process = ProcessBuilder(*arguments).redirectOutput(output).redirectError(errors).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            fail("native ABI probe timed out")
        }
        assertEquals(0, process.exitValue(), errors.readText())
        if (arguments.size == 1) assertEquals("", errors.readText(), "sanitizer diagnostics")
        return output.readText()
    }

    @Test fun scalarWidthsSignednessFloatingPointAndPointerStride() = execute(
        "-7\n250\n-1234\n60000\n4294967295\n18446744073709551615\n3.5\n6.25\ntrue\n42\n27",
        """
        import std.io
        bridge .C {
            func echoByte(value: Byte): Byte
            func echoUByte(value: UByte): UByte
            func echoShort(value: Short): Short
            func echoUShort(value: UShort): UShort
            func echoUInt(value: UInt): UInt
            func echoULong(value: ULong): ULong
            func echoFloat(value: Float): Float
            func echoDouble(value: Double): Double
            func echoBool(value: Bool): Bool
            func writeInts(values: Int^): Int
        }
        func main() {
            println(echoByte(Byte(-7)))
            println(echoUByte(UByte(250)))
            println(echoShort(Short(-1234)))
            println(echoUShort(UShort(60000)))
            println(echoUInt(UInt(4294967295)))
            println(echoULong(ULong(18446744073709551615)))
            println(echoFloat(Float(3.5)))
            println(echoDouble(6.25))
            println(echoBool(true))
            let values: Int^ = alloc Int^() * 3
            println(writeInts(values))
            println(values[2])
            purge values
        }
        """.trimIndent(),
        """
        #include <stdint.h>
        #include <stdbool.h>
        int8_t echoByte(int8_t v) { return v; }
        uint8_t echoUByte(uint8_t v) { return v; }
        int16_t echoShort(int16_t v) { return v; }
        uint16_t echoUShort(uint16_t v) { return v; }
        uint32_t echoUInt(uint32_t v) { return v; }
        uint64_t echoULong(uint64_t v) { return v; }
        float echoFloat(float v) { return v; }
        double echoDouble(double v) { return v; }
        bool echoBool(bool v) { return v; }
        int32_t writeInts(int32_t *v) { v[0] = 5; v[1] = 10; v[2] = 27; return v[0] + v[1] + v[2]; }
        """.trimIndent(),
    )

    @Test fun statelessCallbacksHaveTheCSignatureAndStaticLifetime() = execute(
        "42\n42.5\ntrue\n-5\ncallback\n47",
        """
        import std.io
        bridge .C {
            func callInt(callback: (Int) -> Int): Int
            func callDouble(callback: (Double) -> Double): Double
            func callBool(callback: (Bool) -> Bool): Bool
            func callNarrow(callback: (Byte, UByte) -> Byte): Byte
            func callUnit(callback: () -> Unit)
            func saveCallback(callback: escaping (Int) -> Int)
            func invokeSaved(): Int
        }
        func register() { saveCallback({ value: Int -> value + 5 }) }
        func main() {
            println(callInt({ value: Int -> value + 2 }))
            println(callDouble({ value: Double -> value + 2.5 }))
            println(callBool({ value: Bool -> !value }))
            println(callNarrow({ left: Byte, right: UByte -> left + (right as Byte) }))
            callUnit({ println("callback") })
            register()
            println(invokeSaved())
        }
        """.trimIndent(),
        """
        #include <stdint.h>
        #include <stdbool.h>
        static int32_t (*saved)(int32_t);
        int32_t callInt(int32_t (*f)(int32_t)) { return f(40); }
        double callDouble(double (*f)(double)) { return f(40.0); }
        bool callBool(bool (*f)(bool)) { return f(false); }
        int8_t callNarrow(int8_t (*f)(int8_t, uint8_t)) { return f(-7, 2); }
        void callUnit(void (*f)(void)) { f(); }
        void saveCallback(int32_t (*f)(int32_t)) { saved = f; }
        int32_t invokeSaved(void) { return saved(42); }
        """.trimIndent(),
    )

    @Test fun nativeTargetRejectsCapturedCallbacksAndUnspecifiedAggregateAbi() {
        for ((source, message) in listOf(
            """
            bridge .C { func callInt(callback: (Int) -> Int): Int }
            func main() { var offset = 2
                callInt([offset] { value: Int -> value + offset }) }
            """ to "cannot capture values",
            """
            pack Vector { fin x: Double
                fin y: Double }
            bridge .C { func consume(value: Vector): Int }
            func main() { consume(Vector(1.0, 2.0)) }
            """ to "native ABI does not support",
            """
            bridge .C { func echo(value: Cent): Cent }
            func main() { echo(Cent(42)) }
            """ to "native ABI does not support",
        )) {
            for (release in listOf(false, true)) {
                val compiled = Compiler().compile(source.trimIndent(), release = release)
                val result = assertIs<CompilationResult.Success>(compiled, (compiled as? CompilationResult.Failure)?.errors.toString())
                assertEquals("", result.llvm)
                assertTrue(result.backendErrors["llvm"].orEmpty().contains(message), result.backendErrors.toString())
            }
        }
    }

    @Test fun cancellationCannotEmitUnsafeNativeUnwinding() {
        val result = assertIs<CompilationResult.Success>(Compiler().compile("""
            import std.concurrency.async
            func main() {
                fin worker = async { delay 10 }
                concurrency::cancel(worker)
            }
        """.trimIndent()))
        assertEquals("", result.llvm)
        assertTrue(result.backendErrors["llvm"].orEmpty().contains("cancellation-safe ownership cleanup"), result.backendErrors.toString())
    }

    @Test fun unmangledLibmBridgesDoNotRedefineTheirExternalSymbols() {
        if (!LlvmExec.available) return
        val source = """
            import std.io
            bridge .C {
                func sqrt(value: Double): Double
                func cos(value: Double): Double
            }
            func main() { println(sqrt(16.0))
                println(cos(0.0)) }
        """.trimIndent()
        for (release in listOf(false, true)) assertEquals("4.0\n1.0", LlvmExec.run(source, release))
    }
}
