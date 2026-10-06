package org.azora.lang.diagnostics

import org.azora.lang.Compiler
import kotlin.test.*

class SemanticFactsTest {
    private fun unit(name: String, text: String) = SourceUnit(SourceId(name), "file:///workspace/$name.az", "$name.az", text, DocumentVersion(7))
    private fun analyze(source: SourceUnit, vararg libraries: SourceUnit): AnalysisSnapshot =
        Compiler().analyze(AnalysisRequest(listOf(source) + libraries, setOf(source.id)))
    private fun spanText(snapshot: AnalysisSnapshot, span: SourceSpan): String =
        snapshot.sources.units.getValue(span.source).text.substring(span.start.value, span.endExclusive.value)

    @Test fun inferredReceiverOffersRealFieldsAndTheirDefinitions() {
        val source = unit("main", """
            pack Box {
                fin value: Int
                fin label: String
            }
            func main() {
                fin box = Box(7, "item")
                fin result = box.value
            }
        """.trimIndent())
        val snapshot = analyze(source)
        assertTrue(snapshot.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, snapshot.diagnostics.toString())
        val at = TextOffset(source.text.lastIndexOf("value"))
        assertEquals("Int", snapshot.semanticFacts.hover(source.id, at)?.type)
        val definition = assertNotNull(snapshot.semanticFacts.definition(source.id, at))
        assertEquals("value", spanText(snapshot, definition))
        assertEquals(source.text.indexOf("value"), definition.start.value)
        assertEquals(setOf("value", "label"), snapshot.semanticFacts.complete(source.id, at).map { it.name }.toSet())
        assertEquals(listOf("value"), snapshot.semanticFacts.complete(source.id, at, "va").map { it.name })
    }

    @Test fun shadowedReferencesRetainTheirActualBinding() {
        val source = unit("shadow", """
            import std.io
            func main() {
                fin value = 17
                if true {
                    fin value = "inner"
                    println(value)
                }
                println(value)
            }
        """.trimIndent())
        val snapshot = analyze(source)
        assertTrue(snapshot.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, snapshot.diagnostics.toString())
        val inner = TextOffset(source.text.indexOf("println(value)") + "println(".length)
        val outer = TextOffset(source.text.lastIndexOf("println(value)") + "println(".length)
        assertEquals("String", snapshot.semanticFacts.hover(source.id, inner)?.type)
        assertEquals("Int", snapshot.semanticFacts.hover(source.id, outer)?.type)
        assertEquals(source.text.indexOf("value = \"inner\""), snapshot.semanticFacts.definition(source.id, inner)?.start?.value)
        assertEquals(source.text.indexOf("value = 17"), snapshot.semanticFacts.definition(source.id, outer)?.start?.value)
        assertEquals("String", snapshot.semanticFacts.complete(source.id, inner, "value").single().type)
        assertEquals("Int", snapshot.semanticFacts.complete(source.id, outer, "value").single().type)
    }

    @Test fun importedCallDefinitionPointsAtItsActualModule() {
        val main = unit("main", "import demo.math::plus\nfunc main() { fin result = plus(2, 3) }\n")
        val library = unit("math", "module demo.math\nfunc plus(a: Int, b: Int): Int { return a + b }\n")
        val snapshot = analyze(main, library)
        assertTrue(snapshot.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, snapshot.diagnostics.toString())
        val at = TextOffset(main.text.lastIndexOf("plus"))
        assertEquals("Int", snapshot.semanticFacts.hover(main.id, at)?.type)
        val definition = assertNotNull(snapshot.semanticFacts.definition(main.id, at))
        assertEquals(library.id, definition.source)
        assertEquals("plus", spanText(snapshot, definition))
        assertEquals(library.text.indexOf("plus"), definition.start.value)
    }

    @Test fun semanticFailureRetainsOnlySuccessfulTypesAndBindings() {
        val source = unit("partial", "import std.io\nfunc main() { fin value = 41; println(value); missing() }\n")
        val snapshot = analyze(source)
        assertEquals(AnalysisCompleteness.PARTIAL, snapshot.completeness)
        assertTrue(snapshot.diagnostics.any { it.severity == DiagnosticSeverity.ERROR })
        val at = TextOffset(source.text.indexOf("println(value)") + "println(".length)
        assertEquals("Int", snapshot.semanticFacts.hover(source.id, at)?.type)
        assertEquals(source.text.indexOf("value = 41"), snapshot.semanticFacts.definition(source.id, at)?.start?.value)
        val missing = TextOffset(source.text.indexOf("missing"))
        assertNull(snapshot.semanticFacts.hover(source.id, missing))
        assertNull(snapshot.semanticFacts.definition(source.id, missing))
        assertEquals("Int", snapshot.semanticFacts.complete(source.id, missing, "value").single().type)
    }

    @Test fun parserBlockedSnapshotHasNoSpeculativeFacts() {
        val source = unit("blocked", "func main() { fin value = Box(\n")
        val snapshot = analyze(source)
        assertEquals(AnalysisCompleteness.BLOCKED, snapshot.completeness)
        assertTrue(snapshot.semanticFacts.occurrences.isEmpty())
        assertNull(snapshot.semanticFacts.hover(source.id, TextOffset(18)))
        assertTrue(snapshot.semanticFacts.complete(source.id, TextOffset(18)).isEmpty())
    }
}
