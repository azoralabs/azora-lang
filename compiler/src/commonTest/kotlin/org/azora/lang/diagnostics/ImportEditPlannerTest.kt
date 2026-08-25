package org.azora.lang.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportEditPlannerTest {
    private fun apply(source: String, module: String, symbol: String): String? {
        val edit = ImportEditPlanner.plan(source, module, symbol) ?: return null
        return source.replaceRange(edit.start, edit.endExclusive, edit.replacement)
    }

    @Test fun insertsAfterModuleHeader() {
        assertEquals(
            "module app.main\n\nimport std.io::println\n\nfunc main() {}",
            apply("module app.main\n\nfunc main() {}", "std.io", "println"),
        )
    }

    @Test fun joinsASelectionUsingGroupingBraces() {
        assertEquals(
            "import std.io::{print, println}\nfunc main() {}",
            apply("import std.io::print\nfunc main() {}", "std.io", "println"),
        )
    }

    @Test fun doesNotDuplicateAnImportedSymbol() {
        assertNull(ImportEditPlanner.plan("import std.io::{print, println}\n", "std.io", "println"))
    }
}
