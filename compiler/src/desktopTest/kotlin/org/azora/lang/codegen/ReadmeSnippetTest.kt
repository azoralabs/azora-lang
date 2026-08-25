package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.fail

/**
 * Compiles every `azora` block in the top-level `README.md`.
 *
 * "The README describes reality" is a release gate, and an audit only settles
 * it once. This settles it continuously: a snippet that stops compiling fails
 * the build, so the document cannot drift away from the language again.
 *
 * Most blocks are fragments rather than whole programs - an `impl` needs the
 * pack it is on, a spec needs an implementer. Each one is therefore paired with
 * the smallest context that makes it a program. The pairing is keyed on a
 * distinctive line of the block, so reordering the README does not silently
 * detach a snippet from its context; a block matching no key fails the test
 * rather than being skipped.
 */
class ReadmeSnippetTest {

    /**
     * A snippet's required context: text placed before it, and after it.
     *
     * [bodyFrom] marks a block that mixes a declaration with expressions using
     * it - a macro and its call sites. Lines from the first one containing it
     * are moved into `main`, because an expression is not a top-level form.
     */
    private data class Context(
        val prelude: String = "",
        val epilogue: String = "\nfunc main() {}",
        val bodyFrom: String? = null,
    )

    private val emptyMain = Context()

    /** Keyed on a line unique to the block it belongs to. */
    private val contexts: List<Pair<String, Context>> = listOf(
        // A complete program already.
        "Hello, Azora!" to Context(epilogue = ""),

        // Declarations that stand alone.
        "pack Point {" to emptyMain,
        "func add(a: Int, b: Int = 0): Int" to emptyMain,
        "macro @arr {" to Context(bodyFrom = "@arr[1, 2, 3]", epilogue = ""),
        "macro \$a @to \$b" to Context(
            prelude = "scope std { func<K, V> mapEntry(key: K, value: V): K { return key } }\n",
            bodyFrom = "\"key\" @to 42",
            epilogue = "",
        ),

        // Bindings shown at statement level.
        "var count = 0" to Context(prelude = "func main() {", epilogue = "}"),

        // An impl needs its pack.
        "impl Point {" to Context(prelude = "pack Point {\n    var x: Int\n    var y: Int\n}\n"),
        "spec Greet {" to Context(prelude = "pack Point {\n    var x: Int\n    var y: Int\n}\n"),

        // The comparison example carries its own pack; the derive and Display
        // examples need one.
        "impl Order for Version" to emptyMain,
        "derive (Equal, Order) for Point" to Context(
            prelude = "import std.traits\npack Point {\n    var x: Int\n    var y: Int\n}\n",
        ),
        "impl Arithmetic for Matrix" to Context(
            prelude = "import std.traits\nimport std.container.array\n" +
                "pack Matrix {\n    var data: Array<Int, 4>\n}\n",
        ),
        "impl Display for Point" to Context(
            prelude = "pack Point {\n    var x: Int\n    var y: Int\n}\n",
        ),

        // `inline for` generating declarations, and generating members.
        "inline for Ty in [A, B]" to Context(
            prelude = "import std.traits\npack A { var v: Int = 0 }\npack B { var v: Int = 0 }\n",
        ),
        "inline for axis in @arr" to Context(
            prelude = "pack Vec3 {\n    var x: Double = 0.0\n" +
                "    var y: Double = 0.0\n    var z: Double = 0.0\n}\n",
        ),

        // The module header block is a file preamble, not a body.
        "module std.math" to Context(epilogue = "\nconfined func unused() {}"),
    )

    /** Moves the lines from [bodyFrom] onward into a `main` body. */
    private fun splitBody(block: String, bodyFrom: String?): String {
        if (bodyFrom == null) return block
        val lines = block.lines()
        val at = lines.indexOfFirst { bodyFrom in it }
        if (at < 0) return block
        val declarations = lines.take(at).joinToString("\n")
        val body = lines.drop(at).joinToString("\n") { if (it.isBlank()) it else "    $it" }
        return "$declarations\nfunc main() {\n$body\n}\n"
    }

    @Test fun everyReadmeSnippetCompiles() {
        val readme = java.io.File("../README.md")
        if (!readme.exists()) fail("README.md not found at ${readme.absolutePath}")
        val blocks = Regex("```azora\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .findAll(readme.readText())
            .map { it.groupValues[1] }
            .toList()
        if (blocks.isEmpty()) fail("no azora snippets found in README.md")

        val failures = mutableListOf<String>()
        for ((index, block) in blocks.withIndex()) {
            val context = contexts.firstOrNull { (key, _) -> key in block }?.second
            if (context == null) {
                failures += "snippet #$index has no context entry in ReadmeSnippetTest; " +
                    "add one keyed on a line of it\n${block.trim().lines().first()}"
                continue
            }
            val program = context.prelude + splitBody(block, context.bodyFrom) + context.epilogue
            val result = Compiler().compile(program)
            if (result is CompilationResult.Failure) {
                failures += "snippet #$index does not compile:\n" +
                    program.lines().mapIndexed { i, l -> "  ${i + 1}| $l" }.joinToString("\n") +
                    "\n  errors: ${result.errors.joinToString("; ")}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }
}
