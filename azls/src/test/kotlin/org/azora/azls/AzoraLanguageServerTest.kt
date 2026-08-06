/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.azora.azls

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AzoraLanguageServerTest {

    private val azls = AzoraLanguageServer()
    private val json = Json { ignoreUnknownKeys = true }

    private fun spans(source: String): List<HighlightSpan> =
        json.decodeFromString(ListSerializer(HighlightSpan.serializer()), azls.highlight(source))

    private fun diags(source: String, prelude: String = ""): List<Diagnostic> =
        json.decodeFromString(ListSerializer(Diagnostic.serializer()), azls.diagnostics(source, prelude))

    private fun completions(source: String, offset: Int, prelude: String = ""): List<Completion> =
        json.decodeFromString(ListSerializer(Completion.serializer()), azls.complete(source, offset, prelude))

    private fun symbols(source: String): List<DocumentSymbol> =
        json.decodeFromString(ListSerializer(DocumentSymbol.serializer()), azls.symbols(source))

    private fun hover(source: String, offset: Int, prelude: String = ""): Hover? =
        azls.hover(source, offset, prelude).let { if (it == "null") null else json.decodeFromString(Hover.serializer(), it) }

    private fun definition(source: String, offset: Int, prelude: String = ""): Definition? =
        azls.definition(source, offset, prelude).let { if (it == "null") null else json.decodeFromString(Definition.serializer(), it) }

    // -----------------------------------------------------------------
    // Highlighting
    // -----------------------------------------------------------------

    @Test
    fun highlightsKeywordsStringsNumbersComments() {
        val source = """
            // greet the world
            func greet(name: String) {
                var count = 42
                fin label = "hi"
                greet(name)
                missing(count)
            }
        """.trimIndent()
        val all = spans(source)
        fun textOf(span: HighlightSpan) = source.substring(span.start, span.end)

        assertTrue(all.any { it.type == "comment" && textOf(it) == "// greet the world" })
        assertTrue(all.any { it.type == "keyword" && textOf(it) == "func" })
        assertTrue(all.any { it.type == "keyword" && textOf(it) == "var" })
        assertTrue(all.any { it.type == "number" && textOf(it) == "42" })
        assertTrue(all.any { it.type == "string" && textOf(it) == "\"hi\"" })
        assertEquals(2, all.count { it.type == "function" && textOf(it) == "greet" })
        assertTrue(all.any { it.type == "parameter" && textOf(it) == "name" })
        assertTrue(all.any { it.type == "variable" && textOf(it) == "count" })
        assertTrue(all.none { it.type == "function" && textOf(it) == "missing" })
    }

    @Test
    fun keywordColorsFollowCompilerVocabularyAndContext() {
        val source = """
            module playground

            func module(ref: Int, where: Int) {
                fin meta = ref + where
            }

            async func fetch() where true {
                return
            }
        """.trimIndent()
        val all = spans(source)
        fun spansFor(text: String) = all.filter { source.substring(it.start, it.end) == text }

        assertEquals("keyword", spansFor("module").first().type)
        assertEquals("function", spansFor("module").last().type)
        assertTrue(spansFor("ref").all { it.type != "keyword" })
        assertTrue(spansFor("meta").all { it.type != "keyword" })
        assertEquals("parameter", spansFor("where").first().type)
        assertEquals("keyword", spansFor("where").last().type)
        assertEquals("keyword", spansFor("async").single().type)
    }

    @Test
    fun bracketReceiverFunctionIsRecognized() {
        val source = """
            pack Language { fin name: String }

            impl Language {
                func greeting[self: Self&](): String {
                    return self.name
                }
            }

            func main() {
                fin language = Language("Azora")
                language.greeting()
            }
        """.trimIndent()
        val all = spans(source)
        fun textOf(span: HighlightSpan) = source.substring(span.start, span.end)

        assertEquals(2, all.count { it.type == "function" && textOf(it) == "greeting" })
        assertTrue(all.filter { textOf(it) == "self" }.all { it.type == "parameter" })
    }

    @Test
    fun declarationsInsideCommentsAndStringsDoNotCreateFunctions() {
        val source = """
            // func hidden() {}
            fin text = "func alsoHidden() {}"

            func main() {
                hidden()
                alsoHidden()
            }
        """.trimIndent()
        val all = spans(source)
        fun textOf(span: HighlightSpan) = source.substring(span.start, span.end)

        assertTrue(all.none { it.type == "function" && textOf(it) == "hidden" })
        assertTrue(all.none { it.type == "function" && textOf(it) == "alsoHidden" })
    }

    @Test
    fun importedFunctionsAreHighlightedButUnknownCallsAreNot() {
        val source = """
            import std.io
            func main() {
                std::println("known")
                notDeclared("unknown")
            }
        """.trimIndent()
        val all = spans(source)
        fun textOf(span: HighlightSpan) = source.substring(span.start, span.end)

        assertTrue(all.any { it.type == "function" && textOf(it) == "println" })
        assertTrue(all.none { it.type == "function" && textOf(it) == "notDeclared" })
    }

    @Test
    fun highlightsInterpolationInsideStrings() {
        val source = """
            func greet(name: String): String {
                return "Hello ${'$'}name from ${'$'}{self.name}!"
            }
        """.trimIndent()
        val all = spans(source)
        fun textOf(span: HighlightSpan) = source.substring(span.start, span.end)

        assertEquals(4, all.count { it.type == "interpolation-punctuation" })
        assertTrue(all.any { it.type == "parameter" && textOf(it) == "name" })
        assertTrue(all.any { it.type == "parameter" && textOf(it) == "self" })
        assertTrue(all.any { it.type == "variable" && textOf(it) == "name" })
        assertTrue(all.filter { it.type == "string" }.none { "self.name" in textOf(it) })
    }

    @Test
    fun highlightsCompilerMacrosAsOneToken() {
        val source = """fin tuple = @tup(1, 2)
fin array = @collect_all(tuple)"""
        val macros = spans(source).filter { it.type == "macro" }

        assertEquals(listOf("@tup", "@collect_all"), macros.map { source.substring(it.start, it.end) })
    }

    @Test
    fun highlighterSurvivesBrokenSource() {
        // Unterminated string + stray characters must not throw.
        val all = spans("func main( { \"unterminated\n    var x = @@@ 3..")
        assertTrue(all.isNotEmpty())
    }

    // -----------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------

    @Test
    fun cleanProgramHasNoDiagnostics() {
        val list = diags("func main() {\n    println(\"ok\")\n}")
        assertEquals(emptyList(), list)
    }

    @Test
    fun syntaxErrorIsReportedOnItsLine() {
        val list = diags("func main() {\n    var = 3\n}")
        assertTrue(list.isNotEmpty())
        assertEquals("error", list[0].severity)
        assertEquals(2, list[0].line)
    }

    @Test
    fun semanticErrorIsReported() {
        val list = diags("func main() {\n    println(missingVar)\n}")
        assertTrue(list.isNotEmpty(), "expected an undefined-variable diagnostic")
        assertTrue(list.any { it.line == 2 }, "diagnostic should be on line 2: $list")
    }

    @Test
    fun preludeShiftsLinesBack() {
        val prelude = "func helper(): Int {\n    return 1\n}"
        val list = diags("func main() {\n    println(brokenRef)\n}", prelude)
        assertTrue(list.isNotEmpty())
        assertEquals(2, list[0].line, "line must be mapped back to the document: $list")
    }

    @Test
    fun preludeSymbolsResolve() {
        val prelude = "func helper(): Int {\n    return 1\n}"
        val list = diags("func main() {\n    println(helper())\n}", prelude)
        assertEquals(emptyList(), list)
    }

    // -----------------------------------------------------------------
    // Completion
    // -----------------------------------------------------------------

    @Test
    fun completesUserFunctionsAndKeywords() {
        val source = "func attack(power: Int) {\n}\nfunc main() {\n    at\n}"
        val offset = source.indexOf("    at") + 6
        val list = completions(source, offset)
        assertTrue(list.any { it.label == "attack" && it.kind == "function" }, "$list")
        assertTrue(list.none { it.label == "println" }, "prefix 'at' should filter: $list")
    }

    @Test
    fun completesLocalsAndParams() {
        val source = "func update(delta: Double) {\n    var speed = 5\n    \n}"
        val offset = source.indexOf("    \n") + 4
        val list = completions(source, offset)
        assertTrue(list.any { it.label == "delta" && it.kind == "param" }, "$list")
        assertTrue(list.any { it.label == "speed" && it.kind == "variable" }, "$list")
    }

    @Test
    fun completesPackFieldsAfterDot() {
        val source = "pack Point {\n    var x: Int\n    var y: Int\n}\nfunc main() {\n    let p = Point(1, 2)\n    p.\n}"
        val offset = source.indexOf("    p.") + 6
        val list = completions(source, offset)
        assertTrue(list.any { it.label == "x" && it.kind == "field" }, "$list")
        assertTrue(list.any { it.label == "y" && it.kind == "field" }, "$list")
    }

    @Test
    fun completesEnumVariantsAfterDot() {
        val source = "enum Color {\n    Red\n    Green\n}\nfunc main() {\n    let c = Color.\n}"
        val offset = source.indexOf("Color.\n") + 6
        val list = completions(source, offset)
        assertTrue(list.any { it.label == "Red" && it.kind == "enumMember" }, "$list")
        assertTrue(list.any { it.label == "Green" && it.kind == "enumMember" }, "$list")
    }

    @Test
    fun completesStdlibFunctions() {
        val source = "import std.math\nfunc main() {\n    ab\n}"
        val offset = source.indexOf("    ab") + 6
        val list = completions(source, offset)
        assertTrue(list.any { it.label == "abs" && it.kind == "function" }, "stdlib abs should complete: $list")
        assertTrue(list.any { it.label == "abs" && "func abs" in it.detail && "std.math" in it.detail },
            "abs should carry its signature and module: $list")
    }

    @Test
    fun completesGroupedStdlibImports() {
        val source = "import std.{math, container}\nfunc main() {\n    ab\n}"
        val offset = source.indexOf("    ab") + 6
        val list = completions(source, offset)
        assertTrue(list.any { it.label == "abs" && it.kind == "function" && "std.math" in it.detail },
            "grouped std import should expose std.math completions: $list")
    }

    @Test
    fun completesSelectiveStdlibImportFromContainingModule() {
        val source = "import std.math.abs\nfunc main() {\n    ab\n}"
        val offset = source.indexOf("    ab") + 6
        val list = completions(source, offset)
        assertTrue(list.any { it.label == "abs" && it.kind == "function" && "std.math" in it.detail },
            "selective std import should expose its containing module for completion: $list")
    }

    @Test
    fun stdlibConstantsComplete() {
        val source = "import std\nfunc main() {\n    P\n}"
        val offset = source.indexOf("    P") + 5
        val list = completions(source, offset)
        assertTrue(list.any { it.label == "PI" && it.kind == "variable" }, "PI should complete: $list")
    }

    @Test
    fun stdlibCompletionsAreImportGated() {
        val source = "func main() {\n    ab\n}"
        val offset = source.indexOf("    ab") + 6
        val list = completions(source, offset)
        assertTrue(list.none { it.label == "abs" && it.kind == "function" },
            "abs must not complete without 'use std.math': $list")
    }

    @Test
    fun libraryModuleSectionsAreImportGated() {
        val prelude = "//@azora-module engine\nfunc appInit(w: Int): Int {\n    return w\n}\n\n//@azora-module\nfunc projectHelper(): Int {\n    return 1\n}"
        val bare = "func main() {\n    ap\n}"
        val bareOffset = bare.indexOf("    ap") + 6
        val without = completions(bare, bareOffset, prelude)
        assertTrue(without.none { it.label == "appInit" },
            "engine symbol must not complete without 'use engine': $without")

        val imported = "import engine\nfunc main() {\n    ap\n}"
        val importedOffset = imported.indexOf("    ap") + 6
        val with = completions(imported, importedOffset, prelude)
        assertTrue(with.any { it.label == "appInit" && "engine" in it.detail },
            "engine symbol should complete (tagged) with 'use engine': $with")

        // Unmarked (project) prelude symbols are never gated.
        val project = completions("func main() {\n    pro\n}", "func main() {\n    pro\n}".indexOf("    pro") + 7, prelude)
        assertTrue(project.any { it.label == "projectHelper" },
            "project symbol should complete without imports: $project")
    }

    @Test
    fun completesBuiltinFunctions() {
        val source = "func main() {\n    pr\n}"
        val offset = source.indexOf("    pr") + 6
        val list = completions(source, offset)
        assertTrue(list.any { it.label == "println" && it.kind == "function" }, "builtin println should complete: $list")
    }

    @Test
    fun completesWhileTypingBrokenLine() {
        // The current line doesn't parse - tolerant parsing must still offer symbols.
        val source = "func fire(power: Int) {\n}\nfunc main() {\n    fi(\n}"
        val offset = source.indexOf("    fi(") + 6
        val list = completions(source, offset)
        assertTrue(list.any { it.label == "fire" }, "$list")
    }

    @Test
    fun completesPreludeFunctions() {
        val prelude = "func gpuInit(): Bool {\n    return true\n}"
        val source = "func main() {\n    gp\n}"
        val offset = source.indexOf("    gp") + 6
        val list = completions(source, offset, prelude)
        assertTrue(list.any { it.label == "gpuInit" }, "$list")
    }

    // -----------------------------------------------------------------
    // Hover & symbols
    // -----------------------------------------------------------------

    @Test
    fun hoverShowsFunctionSignature() {
        val source = "func add(a: Int, b: Int): Int {\n    return a + b\n}\nfunc main() {\n    add(1, 2)\n}"
        val offset = source.lastIndexOf("add") + 1
        val hover = json.decodeFromString(Hover.serializer(), azls.hover(source, offset))
        assertEquals("func add(a: Int, b: Int): Int", hover.signature)
    }

    @Test
    fun hoverReturnsNullForUnknown() {
        assertEquals("null", azls.hover("func main() {\n}", 0))
    }

    @Test
    fun documentSymbolsListTopLevels() {
        val source = "pack Point {\n    var x: Int\n}\nenum Color {\n    Red\n}\nfunc main() {\n}"
        val list = symbols(source)
        assertEquals(listOf("Point", "Color", "main"), list.map { it.name })
        assertEquals(listOf("pack", "enum", "function"), list.map { it.kind })
    }

    @Test
    fun hoverIncludesDocComment() {
        val source = "/// Adds two integers.\n/// Returns their sum.\nfunc add(a: Int, b: Int): Int {\n    return a + b\n}\nfunc main() {\n    add(1, 2)\n}"
        val offset = source.lastIndexOf("add") + 1
        val h = hover(source, offset)!!
        assertEquals("func add(a: Int, b: Int): Int", h.signature)
        assertEquals("Adds two integers.\nReturns their sum.", h.doc)
    }

    @Test
    fun definitionFindsTopLevelFunctionInFile() {
        val source = "func helper(): Int {\n    return 1\n}\nfunc main() {\n    helper()\n}"
        val offset = source.lastIndexOf("helper") + 1
        val def = definition(source, offset)!!
        assertTrue(def.inCurrentFile)
        assertEquals(1, def.line)
    }

    @Test
    fun definitionFindsLocalVariable() {
        val source = "func main() {\n    var total = 0\n    total = total + 1\n}"
        val offset = source.lastIndexOf("total") + 1
        val def = definition(source, offset)!!
        assertTrue(def.inCurrentFile)
        assertEquals(2, def.line)
    }

    @Test
    fun definitionReportsExternalSymbolByName() {
        // abs lives in the stdlib, not this file → not in current file, named for search.
        val source = "func main() {\n    abs(3)\n}"
        val offset = source.indexOf("abs") + 1
        val def = definition(source, offset)!!
        assertFalse(def.inCurrentFile)
        assertEquals("abs", def.name)
    }

    @Test
    fun definitionReturnsNullForUnknownWord() {
        val source = "func main() {\n    nonexistentThing()\n}"
        val offset = source.indexOf("nonexistentThing") + 1
        assertEquals("null", azls.definition(source, offset))
    }

    @Test
    fun versionIsReported() {
        assertTrue(azls.version().isNotBlank())
    }
}
