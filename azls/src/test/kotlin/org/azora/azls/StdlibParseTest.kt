package org.azora.azls

import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The scripting core of the standard library must stay parseable — these are
 * the files [org.azora.lang.stdlib.StdlibInjector] serves symbols from.
 * (Container/Concurrency/Parallelism/Traits sources use compile-time
 * machinery the parser doesn't support yet and are intentionally excluded.)
 */
class StdlibParseTest {

    private val coreFiles = listOf(
        "Math/Math.az", "Math/Extra.az", "Math/Trig.az",
        "Algorithm/Sort.az", "Algorithm/Search.az", "Algorithm/AlgorithmExtra.az",
        "Functional/Functional.az", "Functional/FunctionalExtra.az",
        "String/String.az", "String/StringExtra.az",
        "Char/Char.az",
        "IO/IO.az", "IO/Convert.az",
        "Random/Random.az",
    )

    @Test
    fun coreStdlibFilesParse() {
        val stdDir = java.io.File("../Internal/Std")
        val failures = mutableListOf<String>()
        for (rel in coreFiles) {
            val source = stdDir.resolve(rel).readText()
            try {
                Parser(Lexer(source).tokenize()).parse()
            } catch (e: Exception) {
                failures.add("$rel: ${e.message?.lineSequence()?.first()}")
            }
        }
        assertTrue(failures.isEmpty(), "stdlib core files no longer parse:\n${failures.joinToString("\n")}")
    }
}
