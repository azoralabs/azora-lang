package org.azora.lang.diagnostics

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StructuredDiagnosticsTest {
    @Test fun lineIndexRoundTripsUnicodeAndCrlfInEveryEncoding() {
        val text = "a😀\r\nβx\n"
        val index = StringLineIndex(text)
        for (encoding in PositionEncoding.entries) {
            for (offset in listOf(0, 1, 3, 5, 6, text.length)) {
                val position = index.position(TextOffset(offset), encoding)
                assertEquals(offset, index.offset(position, encoding)?.value, "$encoding at $offset")
            }
        }
        assertEquals(SourcePosition(0, 3), index.position(TextOffset(3), PositionEncoding.UTF16))
        assertEquals(SourcePosition(0, 5), index.position(TextOffset(3), PositionEncoding.UTF8))
        assertEquals(SourcePosition(0, 2), index.position(TextOffset(3), PositionEncoding.UTF32))
        assertEquals(SourcePosition(1, 0), index.position(TextOffset(5), PositionEncoding.UTF16))
    }

    @Test fun compilerResultsCarryStructuredUndefinedSymbolData() {
        val result = Compiler().compile("func main() { missing() }")
        val failure = assertIs<CompilationResult.Failure>(result)
        val diagnostic = assertIs<UndefinedSymbol>(failure.diagnostics.single())
        assertEquals(DiagnosticCode("AZ-SYM-0001"), diagnostic.code)
        assertEquals("missing", diagnostic.symbol)
        assertTrue(diagnostic.primary.span.endExclusive.value > diagnostic.primary.span.start.value)
    }

    @Test fun analysisUsesDocumentIdentityAndSkipsBackendArtifacts() {
        val source = SourceUnit(
            SourceId("main"),
            "file:///workspace/main.az",
            "/workspace/main.az",
            "func main() { missing() }",
            DocumentVersion(7),
        )
        val snapshot = Compiler().analyze(AnalysisRequest(listOf(source), setOf(source.id)))
        val diagnostic = assertNotNull(snapshot.diagnostics.singleOrNull())
        assertEquals(source.id, diagnostic.primary.span.source)
        assertEquals(AnalysisCompleteness.BLOCKED, snapshot.completeness)
    }

    @Test fun ideLibrariesTrustManifestResolvedModulesInsteadOfPhysicalPaths() {
        val main = SourceUnit(
            SourceId("main"),
            "file:///workspace/main.az",
            "main.az",
            "import azora.language.server::serve\nfunc main() { serve() }\n",
            DocumentVersion(1),
        )
        val library = SourceUnit(
            SourceId("server"),
            "file:///workspace/azls/src/main/azora/AzoraLanguageServer.az",
            "azls/src/main/azora/AzoraLanguageServer.az",
            "module azora.language.server\nfunc serve() {}\n",
            kind = SourceKind.WORKSPACE_LIBRARY,
        )

        val snapshot = Compiler().analyze(
            AnalysisRequest(listOf(main, library), setOf(main.id), mode = AnalysisMode.IDE),
        )

        assertTrue(
            snapshot.diagnostics.none { "does not match module" in DiagnosticRenderer.summary(it) },
            snapshot.diagnostics.joinToString { DiagnosticRenderer.summary(it) },
        )
    }

    @Test fun ideAnalysisDoesNotPublishUnreachableBrokenLibraryWarnings() {
        val main = SourceUnit(
            SourceId("main"), "file:///workspace/main.az", "main.az", "func main() {}\n", DocumentVersion(1),
        )
        val broken = SourceUnit(
            SourceId("broken"),
            "file:///workspace/examples/demo/src/App.az",
            "examples/demo/src/App.az",
            "package demo_invalid\nfunc (((",
            kind = SourceKind.WORKSPACE_LIBRARY,
        )

        val snapshot = Compiler().analyze(
            AnalysisRequest(listOf(main, broken), setOf(main.id), mode = AnalysisMode.IDE),
        )

        assertTrue(
            snapshot.diagnostics.none { "Failed to parse library source" in DiagnosticRenderer.summary(it) },
            snapshot.diagnostics.joinToString { DiagnosticRenderer.summary(it) },
        )
    }

    @Test fun diagnosticRegistryCodesAreUnique() {
        val codes = DiagnosticRegistry.descriptors.map { it.code.value }
        assertEquals(codes.size, codes.distinct().size)
    }

    @Test fun compilerOwnsContextualVariantShorthandHintsAndFixes() {
        val text = """
            enum FileKind {
                File
                Directory
            }
            func same(kind: FileKind): Bool {
                fin expected: FileKind = FileKind.File
                fin inferred = FileKind.Directory
                return FileKind.File == kind || kind == FileKind.Directory || expected == inferred
            }
        """.trimIndent() + "\n"
        val source = SourceUnit(
            SourceId("variant-shorthand"),
            "file:///workspace/variant-shorthand.az",
            "variant-shorthand.az",
            text,
            DocumentVersion(9),
        )

        val snapshot = Compiler().analyze(AnalysisRequest(listOf(source), setOf(source.id)))
        val diagnostics = snapshot.diagnostics.filterIsInstance<RedundantVariantQualifier>()

        assertEquals(3, diagnostics.size, snapshot.diagnostics.toString())
        assertEquals(AnalysisCompleteness.COMPLETE, snapshot.completeness)
        for (diagnostic in diagnostics) {
            assertEquals(
                "FileKind.",
                text.substring(diagnostic.primary.span.start.value, diagnostic.primary.span.endExclusive.value),
            )
            assertEquals(DiagnosticSeverity.HINT, diagnostic.severity)
            assertEquals(setOf(DiagnosticTag.UNNECESSARY), diagnostic.tags)
            val fix = diagnostic.fixes.single()
            assertEquals("Use inferred variant '.${diagnostic.variant}'", fix.title)
            assertEquals(".", fix.edits.single().replacement)
            assertEquals("FileKind.", fix.edits.single().expectedText)
            assertEquals(DocumentVersion(9), fix.edits.single().requiredVersion)
        }
        // The untyped binding needs its RHS to establish the type, so replacing
        // its qualifier alone would make `.Directory` ambiguous and is not offered.
        val inferredOffset = text.indexOf("FileKind.Directory")
        assertTrue(diagnostics.none { it.primary.span.start.value == inferredOffset })
    }

    @Test fun variantShorthandCoversEveryPersistentlyTypedValueContext() {
        val text = """
            enum FileKind {
                File
                Directory
            }
            pack Holder {
                var kind: FileKind
            }
            fin defaultKind: FileKind = FileKind.File
            func select(): FileKind {
                var current: FileKind = FileKind.File
                current = FileKind.Directory
                var kinds: Array<FileKind> = [FileKind.File, FileKind.Directory]
                var pair: (FileKind, FileKind) = (FileKind.File, FileKind.Directory)
                var holder = Holder(FileKind.File)
                holder.kind = FileKind.Directory
                return FileKind.File
            }
        """.trimIndent() + "\n"
        val source = SourceUnit(
            SourceId("variant-contexts"),
            "file:///workspace/variant-contexts.az",
            "variant-contexts.az",
            text,
            DocumentVersion(10),
        )

        val snapshot = Compiler().analyze(AnalysisRequest(listOf(source), setOf(source.id)))
        val diagnostics = snapshot.diagnostics.filterIsInstance<RedundantVariantQualifier>()
        assertEquals(10, diagnostics.size, snapshot.diagnostics.toString())
        assertEquals(AnalysisCompleteness.COMPLETE, snapshot.completeness)

        val shortened = diagnostics
            .flatMap { it.fixes.single().edits }
            .sortedByDescending { it.range.start.value }
            .fold(text) { current, edit ->
                current.replaceRange(edit.range.start.value, edit.range.endExclusive.value, edit.replacement)
            }
        val shortenedSource = source.copy(text = shortened, version = DocumentVersion(11))
        val shortenedSnapshot = Compiler().analyze(
            AnalysisRequest(listOf(shortenedSource), setOf(shortenedSource.id)),
        )
        assertEquals(AnalysisCompleteness.COMPLETE, shortenedSnapshot.completeness)
        assertTrue(
            shortenedSnapshot.diagnostics.none { it.severity == DiagnosticSeverity.ERROR },
            shortenedSnapshot.diagnostics.toString(),
        )
    }

    @Test fun variantFixesKeepOneTypeAnchorInSymmetricExpressions() {
        val text = """
            enum FileKind {
                File
                Directory
            }
            func same(): Bool {
                return FileKind.File == FileKind.Directory
            }
        """.trimIndent() + "\n"
        val source = SourceUnit(
            SourceId("variant-anchor"),
            "file:///workspace/variant-anchor.az",
            "variant-anchor.az",
            text,
            DocumentVersion(12),
        )

        val diagnostics = Compiler().analyze(AnalysisRequest(listOf(source), setOf(source.id)))
            .diagnostics
            .filterIsInstance<RedundantVariantQualifier>()

        assertEquals(1, diagnostics.size)
        assertEquals("Directory", diagnostics.single().variant)
        assertEquals(
            text.lastIndexOf("FileKind."),
            diagnostics.single().primary.span.start.value,
        )
    }

    @Test fun compilerResolverOwnsDistinctContractRangesAndImportFixes() {
        val text = "bridge pack Glyph derives (PartialEqual, Equal, Order, Hash)\n"
        val source = SourceUnit(
            SourceId("glyph"), "file:///workspace/glyph.az", "glyph.az", text, DocumentVersion(3),
        )
        val snapshot = Compiler().analyze(AnalysisRequest(listOf(source), setOf(source.id)))
        val diagnostics = snapshot.diagnostics.filterIsInstance<UndefinedSymbol>()
            .filter { it.namespace == SymbolNamespace.SPEC_OR_DECORATOR }
        assertEquals(listOf("PartialEqual", "Equal", "Order", "Hash"), diagnostics.map { it.symbol })
        for (diagnostic in diagnostics) {
            assertEquals(
                diagnostic.symbol,
                text.substring(diagnostic.primary.span.start.value, diagnostic.primary.span.endExclusive.value),
            )
            assertEquals("std.traits", diagnostic.providerModule)
            assertEquals("Import '${diagnostic.symbol}' from 'std.traits'", diagnostic.fixes.single().title)
        }
        assertEquals(4, diagnostics.map { it.primary.span }.distinct().size)
    }

    @Test fun groupedSelectiveImportDoesNotExposeAnOmittedContract() {
        val text = """
            exposed module std.char
            import std.traits::{Order, Hash, Equal}
            bridge pack Char derives (PartialEqual, Equal, Order, Hash)
        """.trimIndent() + "\n"
        val source = SourceUnit(
            SourceId("char"), "file:///workspace/std/char.az", "std/char.az", text, DocumentVersion(4),
        )

        val diagnostics = Compiler().analyze(AnalysisRequest(listOf(source), setOf(source.id)))
            .diagnostics
            .filterIsInstance<UndefinedSymbol>()
            .filter { it.namespace == SymbolNamespace.SPEC_OR_DECORATOR }

        assertEquals(listOf("PartialEqual"), diagnostics.map { it.symbol }, diagnostics.toString())
        assertEquals("Import 'PartialEqual' from 'std.traits'", diagnostics.single().fixes.single().title)
    }

    @Test fun semanticResolverOwnsMissingFunctionRangeAndProvider() {
        val text = "func main() {\n    abs(1)\n}\n"
        val source = SourceUnit(
            SourceId("missing-function"),
            "file:///workspace/missing-function.az",
            "missing-function.az",
            text,
            DocumentVersion(4),
        )

        val diagnostic = Compiler().analyze(AnalysisRequest(listOf(source), setOf(source.id)))
            .diagnostics
            .filterIsInstance<UndefinedSymbol>()
            .single { it.symbol == "abs" }

        assertEquals(SymbolNamespace.FUNCTION, diagnostic.namespace)
        assertEquals("std.math", diagnostic.providerModule)
        assertEquals(
            "abs",
            text.substring(diagnostic.primary.span.start.value, diagnostic.primary.span.endExclusive.value),
        )
        assertEquals("Import 'abs' from 'std.math'", diagnostic.fixes.single().title)
    }

    @Test fun typeResolverOwnsMissingLibraryTypeRangeAndProvider() {
        val text = "func main() {\n    fin values: List<Int> = []\n}\n"
        val source = SourceUnit(
            SourceId("missing-type"),
            "file:///workspace/missing-type.az",
            "missing-type.az",
            text,
            DocumentVersion(5),
        )

        val diagnostic = Compiler().analyze(AnalysisRequest(listOf(source), setOf(source.id)))
            .diagnostics
            .filterIsInstance<UndefinedSymbol>()
            .single { it.symbol == "List" }

        assertEquals(SymbolNamespace.TYPE, diagnostic.namespace)
        assertEquals("std.container.list", diagnostic.providerModule)
        assertEquals(
            "List",
            text.substring(diagnostic.primary.span.start.value, diagnostic.primary.span.endExclusive.value),
        )
        assertEquals("Import 'List' from 'std.container.list'", diagnostic.fixes.single().title)
    }
}
