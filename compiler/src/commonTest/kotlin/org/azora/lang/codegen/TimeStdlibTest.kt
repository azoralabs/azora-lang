package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimeStdlibTest {
    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    @Test fun timeSourceAndAllEmbeddedTestsParse() {
        val source = java.io.File("../Internal/Std/Time.az").readText()
        val program = Parser(Lexer(source).tokenize()).parse()

        assertTrue(program.tests.size >= 30, "expected a broad Time.az suite, got ${program.tests.size}")
        assertTrue(program.tests.any { it.method.name == "All" && it.name == "time" })
    }

    @Test fun timeModuleCompilesWithGeneratedSerializableImplementations() {
        val output = compile("""
            import std.time
            import std.serializer
            import std.io
            import std

            func main() {
                fin value = std::parseIsoInstant("1970-01-01T00:00:00Z") catch Instant(-1L)
                std::io::println(value.epochSecond)
            }
        """.trimIndent())

        assertTrue(output.ir.functions.any { it.name.endsWith("Instant_toSerialValue") })
        assertTrue(output.ir.functions.any { it.name.endsWith("DateTime_fromSerialValue") })
    }

    @Test fun interpreterExecutesCalendarOffsetAndIsoRoundTrip() {
        val output = compile("""
            import std.time
            import std.io
            import std

            func main() {
                fin source = DateTime(LocalDate(2026, 7, 16), LocalTime(9, 5, 7, 123000000), UtcOffset(10800))
                fin encoded = std::formatIsoDateTime(source)
                std::io::println(encoded)
                try {
                    fin decoded = std::parseIsoDateTime(encoded)
                    std::io::println(decoded.date.year)
                    std::io::println(decoded.offset.totalSeconds)
                } catch { error ->
                    std::io::println("error:" + error)
                }
            }
        """.trimIndent())

        assertEquals(
            "2026-07-16T09:05:07.123+03:00\n2026\n10800",
            IrInterpreter().interpret(output.ir).trim(),
        )
    }

    @Test fun invalidOffsetMinuteUsesTypedFailurePath() {
        val output = compile("""
            import std.time
            import std.io
            import std

            func main() {
                fin fallback = DateTime(LocalDate(-1, 1, 1), LocalTime(0, 0, 0), UtcOffset(0))
                fin value = std::parseIsoDateTime("2026-07-16T09:05:07+01:99") catch fallback
                std::io::println(value.date.year)
            }
        """.trimIndent())

        assertEquals("-1", IrInterpreter().interpret(output.ir).trim())
    }
}
