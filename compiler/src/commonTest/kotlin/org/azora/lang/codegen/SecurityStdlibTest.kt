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
                    fin policy = defaultPasswordPolicy()
                    println(passwordMeetsPolicy("AzoraSecure1!", policy))
                    println(passwordMeetsPolicy("short", policy))
                    println(validPasswordHashOptions(PasswordHashOptions()))
                    fin weak = PasswordHashOptions(PasswordAlgorithm.Argon2id, 1024, 1, 0)
                    println(validPasswordHashOptions(weak))
                    println(redacted("token"))
                    fin event = auditEvent(
                        "read",
                        "service:payments",
                        SecurityLevel.Restricted,
                        AuditOutcome.Allowed,
                        redacted("detail"),
                    )
                    println(event.level == SecurityLevel.Restricted)
                    println(event.outcome == AuditOutcome.Allowed)
                }
                """,
            ),
        )
    }
}
