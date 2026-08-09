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
            import std.*

            func main() {
                fin value = std::parseIsoInstant("1970-01-01T00:00:00Z") catch std::Instant(-1L)
                std::println(value.epochSecond)
            }
        """.trimIndent())

        assertTrue(output.ir.functions.any { it.name.endsWith("Instant_toSerialValue") })
        assertTrue(output.ir.functions.any { it.name.endsWith("DateTime_fromSerialValue") })
    }

    @Test fun interpreterExecutesCalendarOffsetAndIsoRoundTrip() {
        val output = compile("""
            import std.time
            import std.io
            import std.*

            func main() {
                fin source = std::DateTime(std::LocalDate(2026, 7, 16), std::LocalTime(9, 5, 7, 123000000), std::UtcOffset(10800))
                fin encoded = std::formatIsoDateTime(source)
                std::println(encoded)
                try {
                    fin decoded = std::parseIsoDateTime(encoded)
                    std::println(decoded.date.year)
                    std::println(decoded.offset.totalSeconds)
                } catch { e ->
                    std::println("error:" + e)
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
            import std.*

            func main() {
                fin fallback = std::DateTime(std::LocalDate(-1, 1, 1), std::LocalTime(0, 0, 0), std::UtcOffset(0))
                fin value = std::parseIsoDateTime("2026-07-16T09:05:07+01:99") catch fallback
                std::println(value.date.year)
            }
        """.trimIndent())

        assertEquals("-1", IrInterpreter().interpret(output.ir).trim())
    }
}
