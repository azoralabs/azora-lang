package org.azora.azls

import org.azora.lang.stdlib.AzStdlib
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The exact standard-library tree selected by the compiler must stay parseable.
 * Using [AzStdlib] keeps this test aligned with current lowercase paths, bundled
 * sources, version checks, and compile-time environments.
 */
class StdlibParseTest {
    @Test
    fun coreStdlibFilesParse() {
        val programs = AzStdlib.loadPrograms()
        val modules = programs.mapNotNull { it.moduleName }.toSet()

        assertTrue(programs.isNotEmpty(), "the resolved standard library is empty")
        assertTrue("std.math" in modules, "std.math is missing from $modules")
        assertTrue("std.io" in modules, "std.io is missing from $modules")
        assertTrue("std.reflection" in modules, "std.reflection is missing from $modules")
    }
}
