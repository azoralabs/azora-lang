/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A use of a `@Deprecated` declaration is a warning naming the replacement; a
 * use of an `@Experimental` one is reported when asked for (STABILITY_DIP §3-§4).
 */
class StabilityUseTest {

    private fun warnings(source: String, reportExperimental: Boolean = false): List<String> {
        val result = Compiler().compile(source.trimIndent(), release = false, reportExperimental = reportExperimental)
        assertIs<CompilationResult.Success>(result, "expected success, got ${(result as? CompilationResult.Failure)?.errors}")
        return result.warnings
    }

    private fun stability(source: String, reportExperimental: Boolean = false): List<String> =
        warnings(source, reportExperimental).filter { "deprecated" in it || "experimental" in it }

    @Test fun aCallToADeprecatedFunctionNamesItsReplacement() {
        val w = stability("""
            @Deprecated(since: "0.1", replacement: "fresh")
            func stale(): Int { return 1 }
            func fresh(): Int { return 2 }
            func main() {
                var total = 0
                for i in 0..2 { total = total + stale() }
                total = total + fresh()
            }
        """)
        assertEquals(listOf("warning: line 6: 'stale' is deprecated since 0.1 - use 'fresh' instead"), w)
    }

    @Test fun nothingIsReportedWhenNothingDeprecatedIsUsed() {
        val w = stability("""
            @Deprecated(since: "0.1", replacement: "fresh")
            func stale(): Int { return 1 }
            func fresh(): Int { return 2 }
            func main() { var total = fresh() }
        """)
        assertEquals(emptyList(), w)
    }

    @Test fun aCallFoldedAtCompileTimeIsStillAUse() {
        // `stale(2)` is pure and its argument constant, so compile-time
        // evaluation replaces it with 3 before types are resolved.
        val w = stability("""
            @Deprecated(since: "0.2")
            func stale(x: Int): Int = x + 1
            func main() {
                fin folded = stale(2)
            }
        """)
        assertEquals(listOf("warning: line 4: 'stale' is deprecated since 0.2"), w)
    }

    @Test fun aDeprecatedDeclarationMayUseItselfAndOtherDeprecatedOnes() {
        val w = stability("""
            @Deprecated(since: "0.1")
            func older(): Int { return 1 }
            @Deprecated(since: "0.1")
            func old(n: Int): Int = if n <= 0 then older() else old(n - 1)
            func main() {}
        """)
        assertEquals(emptyList(), w)
    }

    @Test fun supressDeprecatedOnTheUsingDeclarationSilencesIt() {
        val w = stability("""
            @Deprecated(since: "0.1")
            func stale(): Int { return 1 }
            @Supress(kind: .Deprecated)
            func quiet(): Int { return stale() }
            func loud(): Int { return stale() }
            func main() { var a = quiet() + loud() }
        """)
        assertEquals(listOf("warning: line 5: 'stale' is deprecated since 0.1"), w)
    }

    @Test fun supressDeprecatedAboveTheModuleSilencesTheUnit() {
        val w = stability("""
            @Supress(kind: .Deprecated)
            module app.main
            @Deprecated(since: "0.1")
            func stale(): Int { return 1 }
            func main() { var a = stale() }
        """)
        assertEquals(emptyList(), w)
    }

    @Test fun aDeprecatedTypeIsReportedWhereverItIsNamed() {
        val w = stability("""
            @Deprecated(since: "0.1", replacement: "Point")
            pack OldPoint { var x: Int = 0 }
            func make(p: OldPoint&): Int { return p.x }
            func main() {
                fin p: OldPoint = OldPoint(x: 1)
                var n = make(p.&)
            }
        """)
        assertEquals(
            listOf(
                "warning: line 3: 'OldPoint' is deprecated since 0.1 - use 'Point' instead",
                "warning: line 5: 'OldPoint' is deprecated since 0.1 - use 'Point' instead",
            ),
            w,
        )
    }

    @Test fun membersReachedThroughAValueAreReported() {
        val w = stability("""
            pack Box {
                var v: Int = 0
                @Deprecated(since: "0.1", replacement: "v")
                var legacy: Int = 0
            }
            impl Box {
                @Deprecated(since: "0.1", replacement: "get")
                func &.read(): Int { return self.v }
                func &.get(): Int { return self.v }
                @Deprecated(since: "0.1", replacement: "get")
                prop &.value: Int = self.v
            }
            func main() {
                fin b = Box(v: 1)
                var a = b.read()
                a = a + b.value
                a = a + b.legacy
                a = a + b.get()
            }
        """)
        assertEquals(
            listOf(
                "warning: line 15: 'Box.read' is deprecated since 0.1 - use 'get' instead",
                "warning: line 16: 'Box.value' is deprecated since 0.1 - use 'get' instead",
                "warning: line 17: 'Box.legacy' is deprecated since 0.1 - use 'v' instead",
            ),
            w,
        )
    }

    @Test fun aMarkOnAnImplBlockCoversItsMembers() {
        val w = stability("""
            pack Box { var v: Int = 0 }
            @Deprecated(since: "0.1")
            impl Box {
                func &.read(): Int { return self.v }
            }
            func main() {
                fin b = Box(v: 1)
                var a = b.read()
            }
        """)
        assertEquals(listOf("warning: line 8: 'Box.read' is deprecated since 0.1"), w)
    }

    @Test fun casesAreReportedQualifiedAndInferred() {
        val w = stability("""
            enum Mode {
                Fast
                Slow @Deprecated(since: "0.1", replacement: "Fast")
            }
            func pick(m: Mode): Int { return 1 }
            func main() {
                var a = pick(Mode.Slow)
                fin m: Mode = .Slow
                a = a + pick(Mode.Fast)
            }
        """)
        assertEquals(
            listOf(
                "warning: line 7: 'Mode.Slow' is deprecated since 0.1 - use 'Fast' instead",
                "warning: line 8: 'Mode.Slow' is deprecated since 0.1 - use 'Fast' instead",
            ),
            w,
        )
    }

    @Test fun aDeprecatedGlobalIsReportedAndALocalOfTheSameNameIsNot() {
        val w = stability("""
            @Deprecated(since: "0.1", replacement: "limit")
            fin cap = 10
            fin limit = 10
            func first(): Int { return cap }
            func second(): Int {
                fin cap = 3
                return cap
            }
            func main() { var a = first() + second() + limit }
        """)
        assertEquals(listOf("warning: line 4: 'cap' is deprecated since 0.1 - use 'limit' instead"), w)
    }

    @Test fun aSpecMemberReachedThroughASpecTypedValueIsReported() {
        val w = stability("""
            spec Shape {
                @Deprecated(since: "0.1", replacement: "area")
                func &.size(): Int
                func &.area(): Int
            }
            pack Square { var side: Int = 1 }
            impl Shape for Square {
                func &.size(): Int { return self.side }
                func &.area(): Int { return self.side * self.side }
            }
            func measure(s: Shape&): Int { return s.size() + s.area() }
            func main() { var a = measure(Square(side: 2).&) }
        """)
        assertEquals(listOf("warning: line 11: 'Shape.size' is deprecated since 0.1 - use 'area' instead"), w)
    }

    @Test fun aDecoratorWrittenOnASpecIsKept() {
        val w = stability("""
            @Deprecated(since: "0.1", replacement: "Shape")
            spec OldShape {
                func &.area(): Int
            }
            pack Square { var side: Int = 1 }
            impl OldShape for Square {
                func &.area(): Int { return self.side }
            }
            func main() {}
        """)
        assertEquals(listOf("warning: line 6: 'OldShape' is deprecated since 0.1 - use 'Shape' instead"), w)
    }

    @Test fun aDeprecatedConstructorIsReportedAtTheConstruction() {
        val w = stability("""
            pack Range2 { var lo: Int = 0
                var hi: Int = 0 }
            impl Range2 {
                @Deprecated(since: "0.1", replacement: "Range2(lo:, hi:)")
                ctor .(width: Int) {
                    self.lo = 0
                    self.hi = width
                }
            }
            func main() {
                fin r = Range2(4)
                fin s = Range2(lo: 1, hi: 2)
            }
        """)
        assertEquals(listOf("warning: line 11: 'Range2.ctor' is deprecated since 0.1 - use 'Range2(lo:, hi:)' instead"), w)
    }

    @Test fun warningsAsErrorsRefusesADeprecatedUse() {
        val result = Compiler().compile(
            """
            @Deprecated(since: "0.1")
            func stale(): Int { return 1 }
            func main() { var a = stale() }
            """.trimIndent(),
            warningsAsErrors = true,
            release = false,
        )
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "'stale' is deprecated since 0.1" in it }, result.errors.toString())
    }

    @Test fun experimentalUsesAreReportedOnlyWhenAskedFor() {
        val source = """
            @Experimental(since: "0.1")
            func trial(): Int { return 1 }
            func main() { var a = trial() }
        """
        assertEquals(emptyList(), stability(source))
        assertEquals(
            listOf("warning: line 3: 'trial' is experimental (since 0.1) and may change or be removed in any release"),
            stability(source, reportExperimental = true),
        )
    }

    @Test fun anExperimentalDeclarationMayUseOtherExperimentalOnes() {
        val w = stability(
            """
            @Experimental(since: "0.1")
            func trial(): Int { return 1 }
            @Experimental(since: "0.1")
            func wider(): Int { return trial() + 1 }
            func main() {}
            """,
            reportExperimental = true,
        )
        assertEquals(emptyList(), w)
    }

    @Test fun experimentalStandardLibraryUsesAreReportedWhenAskedFor() {
        val w = stability(
            """
            import std.container.list
            func main() {
                var xs: ArrayList<Int> = .()
                xs.add(1)
            }
            """,
            reportExperimental = true,
        )
        assertTrue(
            w.any { it.startsWith("warning: line 3: 'ArrayList' is experimental (since 0.1)") },
            w.joinToString("\n"),
        )
    }

    @Test fun theStandardLibrarysOwnCodeIsNeverReported() {
        // Every program reaches std; none of std's own uses is the program's.
        val w = stability(
            """
            import std.container.list
            func main() {}
            """,
            reportExperimental = true,
        )
        assertEquals(emptyList(), w)
    }
}
