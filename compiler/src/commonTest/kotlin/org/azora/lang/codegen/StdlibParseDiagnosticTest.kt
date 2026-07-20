package org.azora.lang.codegen

import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import java.io.File
import kotlin.test.Test

/** Diagnostic: parse every stdlib .az file and report the first that fails (loadPrograms swallows these). */
class StdlibParseDiagnosticTest {
    @Test fun `all stdlib files parse`() {
        val root = File("../std").canonicalFile
        val errors = mutableListOf<String>()
        // Mirror the production loader: one shared compile-time list env across files,
        // so a list bound in one file (e.g. `Numbers` in primitive.az) is visible to
        // `inline for` loops in later files (e.g. traits/core.az).
        val typeListEnv = mutableMapOf<String, List<String>>()
        root.walkTopDown().filter { it.isFile && it.extension == "az" }.sortedBy { it.path }.forEach { f ->
            try {
                val src = f.readText()
                Parser(Lexer(src).tokenize(), typeListEnv).parse()
            } catch (e: Exception) {
                errors += "${f.path}: ${e.message}"
            }
        }
        check(errors.isEmpty()) { "stdlib parse failures:\n${errors.joinToString("\n")}" }
    }
}
