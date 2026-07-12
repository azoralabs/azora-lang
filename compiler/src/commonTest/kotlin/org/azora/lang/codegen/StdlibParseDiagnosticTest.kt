package org.azora.lang.codegen

import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import java.io.File
import kotlin.test.Test

/** Diagnostic: parse every stdlib .az file and report the first that fails (loadPrograms swallows these). */
class StdlibParseDiagnosticTest {
    @Test fun `all stdlib files parse`() {
        val root = File("../Internal/Std").canonicalFile
        val errors = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "az" }.sortedBy { it.path }.forEach { f ->
            try {
                val src = f.readText()
                Parser(Lexer(src).tokenize()).parse()
            } catch (e: Exception) {
                errors += "${f.path}: ${e.message}"
            }
        }
        check(errors.isEmpty()) { "stdlib parse failures:\n${errors.joinToString("\n")}" }
    }
}
