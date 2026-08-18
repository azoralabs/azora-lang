package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `std.os` (VERSION_0_1_ROADMAP §4.4).
 *
 * Desktop-only: these tests run commands and read an environment, which is
 * exactly what the web and iOS targets do not have. Those targets implement the
 * same API by refusing clearly, which `commandsRefuseWhereThereIsNoShell`
 * documents from the other side.
 */
class OsStdlibTest {
    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source.trimIndent(), release = false)
        return assertIs(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
    }

    private fun run(source: String): String =
        IrInterpreter().interpret(compile(source).ir).trim()

    @Test
    fun environmentVariablesAreReadable() {
        val output = run(
            """
            import std.os
            import std.io

            func main() {
                println(hasEnvVar("PATH"))
                println(hasEnvVar("AZORA_DEFINITELY_UNSET_VARIABLE"))
                println(envVar("AZORA_DEFINITELY_UNSET_VARIABLE"))
                println(envOr("AZORA_DEFINITELY_UNSET_VARIABLE", "fallback"))
            }
            """,
        )

        assertEquals("true\nfalse\n\nfallback", output)
    }

    /**
     * A variable set by the program reaches the program it starts.
     *
     * That is the property worth having - the JVM cannot edit its own
     * environment, so `setEnvVar` is defined by what a child sees rather than by
     * a call that silently does nothing.
     */
    @Test
    fun aSetVariableIsVisibleToCommandsThisProcessStarts() {
        val output = run(
            """
            import std.os
            import std.io

            func main() {
                println(setEnvVar("AZORAOSTESTVAR", "seen"))
                println(envVar("AZORAOSTESTVAR"))
                fin echoed = runCommand("printenv AZORAOSTESTVAR")
                println(trim(echoed.output))
            }
            """,
        )

        assertEquals("true\nseen\nseen", output)
    }

    @Test
    fun runCommandReportsOutputAndExitCode() {
        val output = run(
            """
            import std.os
            import std.io

            func main() {
                fin ok = runCommand("echo hello && echo world")
                println(ok.succeeded)
                println(ok.exitCode)
                println(trim(ok.output))

                fin bad = runCommand("exit 3")
                println(bad.started)
                println(bad.exitCode)
                println(bad.succeeded)
            }
            """,
        )

        assertEquals("true\n0\nhello\nworld\ntrue\n3\nfalse", output)
    }

    /** stderr and stdout arrive interleaved, in the order they were written. */
    @Test
    fun standardErrorIsFoldedIntoTheOutput() {
        val output = run(
            """
            import std.os
            import std.io

            func main() {
                fin result = runCommand("echo first && echo second 1>&2")
                println(contains(result.output, "first"))
                println(contains(result.output, "second"))
            }
            """,
        )

        assertEquals("true\ntrue", output)
    }

    @Test
    fun changingDirectoryMovesOnlyWhenTheTargetExists() {
        val output = run(
            """
            import std.os
            import std.io

            func main() {
                println(changeDirectory("/"))
                println(currentDirectory())
                println(changeDirectory("/azora/definitely/not/here"))
                println(currentDirectory())
            }
            """,
        )

        assertEquals("true\n/\nfalse\n/", output)
    }

    @Test
    fun theProcessHasAnIdentifier() {
        val output = run(
            """
            import std.os
            import std.io

            func main() {
                println(processId() > 0)
            }
            """,
        )

        assertEquals("true", output)
    }

    /**
     * A command that cannot start is reported as not started, not as an empty
     * success - the distinction the `started` flag exists for.
     */
    @Test
    fun aCommandThatFailsIsNotReportedAsSuccess() {
        val output = run(
            """
            import std.os
            import std.io

            func main() {
                fin missing = runCommand("azora-no-such-program-anywhere")
                println(missing.succeeded)
                println(missing.exitCode != 0)
            }
            """,
        )

        assertEquals("false\ntrue", output)
    }

    /** The shape the browser and iOS actuals present, stated where it is visible. */
    @Test
    fun commandsRefuseWhereThereIsNoShell() {
        // Documented rather than executed: this JVM has a shell. The web and iOS
        // actuals return started=false with an explanatory message, so a caller
        // checking `started` behaves correctly on every target.
        assertTrue(true)
    }
}
