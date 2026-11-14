/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.stdlib

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the stable-surface listing includes, and how it writes each declaration. */
class StdSurfaceRenderTest {

    private fun surface(text: String): List<String> =
        StdSurface.render(listOf(StdSurface.Source("demo.az", text.trimIndent())))
            .removePrefix(StdSurface.HEADER)
            .lines()
            .filter { it.isNotBlank() }

    @Test fun experimentalAndPrivateDeclarationsAreLeftOut() {
        val lines = surface("""
            module std.demo
            @Since("0.1")
            func shown(a: Int, b: String& = "x"): Int { return a }
            @Experimental(since: "0.1")
            func trial(): Int { return 1 }
            func _helper(): Int { return 2 }
            fin limit: Int = 3
            fin _cache: Int = 4
        """)
        assertEquals(
            listOf(
                "std.demo  (demo.az)",
                "  func shown(a: Int, b: String& = \"x\"): Int  @Since(\"0.1\")",
                "  fin limit: Int",
            ),
            lines,
        )
    }

    @Test fun membersOfAnExperimentalTypeAreLeftOutWhereverTheyAreWritten() {
        val lines = surface("""
            module std.demo
            @Experimental(since: "0.1")
            pack Trial { var v: Int = 0 }
            impl Trial {
                func &.get(): Int { return self.v }
            }
            pack Kept {
                var v: Int = 0
                var _hidden: Int = 0
            }
            impl Kept {
                func &.get(): Int { return self.v }
                func !.set(v: Int) { self.v = v }
                @Experimental(since: "0.1")
                func &.peek(): Int { return self.v }
                prop &.twice: Int = self.v * 2
            }
        """)
        assertEquals(
            listOf(
                "std.demo  (demo.az)",
                "pack Kept",
                "  var v: Int = 0",
                "impl Kept",
                "  func &.get(): Int",
                "  func !.set(v: Int)",
                "  prop &.twice: Int",
            ).map { if (it.startsWith("std.")) it else "  $it" },
            lines,
        )
    }

    @Test fun deprecationIsShownBesideTheDeclaration() {
        val lines = surface("""
            module std.demo
            @Deprecated(since: "0.2", replacement: "fresh")
            func stale(): Int { return 1 }
            enum Mode {
                Fast
                Slow @Deprecated(since: "0.2")
            }
            error Fault {
                Lost
                Broken
            }
        """)
        assertEquals(
            listOf(
                "std.demo  (demo.az)",
                "  func stale(): Int  @Deprecated(since: \"0.2\", replacement: \"fresh\")",
                "  enum Mode { Fast, Slow  @Deprecated(since: \"0.2\") }",
                "  error Fault { Lost, Broken }",
            ),
            lines,
        )
    }

    @Test fun specsAndConformancesAreListedWithTheirMembers() {
        val lines = surface("""
            module std.demo
            spec Shape {
                func &.area(): Int
                @Experimental(since: "0.1")
                func &.sketch(): Int
            }
            pack Square { var side: Int = 1 }
            impl Shape for Square {
                func &.area(): Int { return self.side * self.side }
                func &.sketch(): Int { return 0 }
            }
        """)
        assertEquals(
            listOf(
                "std.demo  (demo.az)",
                "  spec Shape",
                "    func &.area(): Int",
                "  pack Square",
                "    var side: Int = 1",
                "  impl Shape for Square",
                "    func &.area(): Int",
                "    func &.sketch(): Int",
            ),
            lines,
        )
    }
}
