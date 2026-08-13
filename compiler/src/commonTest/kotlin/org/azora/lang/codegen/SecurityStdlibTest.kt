package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Verifies secure policy helpers without requiring a production crypto provider. */
class SecurityStdlibTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test
    fun policyRedactionAndAuditValuesRemainInspectable() {
        assertEquals(
            "true\nfalse\ntrue\nfalse\n[REDACTED:token]\ntrue\ntrue",
            run(
                """
                import std.security
                import std.io

                func main() {
                    fin policy = std::defaultPasswordPolicy()
                    std::println(std::passwordMeetsPolicy("AzoraSecure1!", policy))
                    std::println(std::passwordMeetsPolicy("short", policy))
                    std::println(std::validPasswordHashOptions(std::PasswordHashOptions()))
                    fin weak = std::PasswordHashOptions(std::PasswordAlgorithm.Argon2id, 1024, 1, 0)
                    std::println(std::validPasswordHashOptions(weak))
                    std::println(std::redacted("token"))
                    fin event = std::auditEvent(
                        "read",
                        "service:payments",
                        std::SecurityLevel.Restricted,
                        std::AuditOutcome.Allowed,
                        std::redacted("detail"),
                    )
                    std::println(event.level == std::SecurityLevel.Restricted)
                    std::println(event.outcome == std::AuditOutcome.Allowed)
                }
                """,
            ),
        )
    }
}
