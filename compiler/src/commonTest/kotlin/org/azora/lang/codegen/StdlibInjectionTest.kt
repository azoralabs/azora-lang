package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.stdlib.StdlibInjector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [org.azora.lang.stdlib.StdlibInjector] under the zone/import model:
 *
 * - Library symbols live in zones (`friend zone std::math { ... }`) and are
 *   name-mangled (`std.math::abs` → `std__math__abs`).
 * - `import std.math` makes that module's symbols visible; references must use
 *   the qualified `Zone::name` form. Bare references are rejected.
 * - Qualified access without the matching import is rejected.
 */
class StdlibInjectionTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir)
    }

    // ---- qualified math access (requires import) ----

    @Test fun qualifiedMathFunctionsWork() =
        assertEquals("5\n7", run("import std.io\nimport std.math\nfunc main() {\n    std::io::println(std::math::abs(-5))\n    std::io::println(std::math::abs(7))\n}"))

    @Test fun qualifiedMinMaxWork() =
        assertEquals("2\n9", run("import std.io\nimport std.math\nfunc main() {\n    std::io::println(std::math::min(2, 9))\n    std::io::println(std::math::max(2, 9))\n}"))

    @Test fun qualifiedFloorCeilRound() =
        assertEquals("3\n4\n4", run("import std.io\nimport std.math\nfunc main() {\n    std::io::println(std::math::floor(3.7))\n    std::io::println(std::math::ceil(3.2))\n    std::io::println(std::math::round(3.6))\n}"))

    @Test fun qualifiedFactorialGcd() =
        assertEquals("120\n6", run("import std.io\nimport std.math\nfunc main() {\n    std::io::println(std::math::factorial(5))\n    std::io::println(std::math::gcd(54, 24))\n}"))

    @Test fun qualifiedConstantInjects() {
        val out = run("import std.io\nimport std.math\nfunc main() {\n    std::io::println(std::math::PI)\n}")
        assertTrue(out.startsWith("3.14159"), out)
    }

    // ---- transitive + shadowing ----

    @Test fun transitiveStdlibCallsResolve() {
        // std::math::lcm uses std::math::gcd internally — both must inject.
        assertEquals("36", run("import std.io\nimport std.math\nfunc main() {\n    std::io::println(std::math::lcm(12, 18))\n}"))
    }

    @Test fun userDefinitionShadowsStdlib() =
        assertEquals("99", run("import std.io\nimport std.math\nfunc abs(x: Int): Int {\n    return 99\n}\nfunc main() {\n    std::io::println(abs(-5))\n}"))

    @Test fun programsWithoutStdlibAreUntouched() {
        val result = Compiler().compile("func main() {\n    var x = 1\n}")
        assertIs<CompilationResult.Success>(result)
        assertEquals(listOf("main"), result.ir.functions.map { it.name })
    }

    @Test fun stdlibIndexExposesCollectionPacks() {
        assertEquals("std.container", StdlibInjector.moduleOf("List"))
        assertEquals("std.container", StdlibInjector.moduleOf("Map"))
        assertEquals("std.container", StdlibInjector.moduleOf("Set"))
    }

    @Test fun collectionTypeAnnotationsInjectPacksAndImpls() =
        assertEquals("3\n2\n2", run("""
            import std.io
            func main() {
                var xs: List<Int> = [1, 2, 3]
                var entries: Map<String, Int> = ["a": 1, "b": 2]
                var seen: Set<Int> = ![1, 2, 2]
                std::io::println(xs.size)
                std::io::println(entries.size)
                std::io::println(seen.size)
            }
        """.trimIndent()))

    // ---- if-expressions (language feature the stdlib relies on) ----

    @Test fun ifExpressionInUserCode() =
        assertEquals("small", run("import std.io\nfunc main() {\n    let label = if 3 > 10 { \"big\" } else { \"small\" }\n    std::io::println(label)\n}"))

    @Test fun ifExpressionElseIfChain() =
        assertEquals("mid", run("import std.io\nfunc pick(x: Int): String = if x > 10 { \"big\" } else if x > 3 { \"mid\" } else { \"small\" }\nfunc main() {\n    std::io::println(pick(5))\n}"))

    @Test fun expressionBodiedFunction() =
        assertEquals("14", run("import std.io\nfunc twice(x: Int): Int = x * 2\nfunc main() {\n    std::io::println(twice(7))\n}"))

    // ---- bare access is rejected ----

    @Test fun bareStdlibAccessIsRejected() {
        val result = Compiler().compile("import std.io\nimport std.math\nfunc main() {\n    std::io::println(abs(-5))\n}")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "undefined" in it && "abs" in it }, "bare access should be rejected: ${'$'}{result.errors}")
    }

    @Test fun qualifiedAccessWithoutImportIsRejected() {
        val result = Compiler().compile("import std.io\nfunc main() {\n    std::io::println(std::math::abs(-5))\n}")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "abs" in it }, "qualified access without import should be rejected: ${'$'}{result.errors}")
    }

    @Test fun wrongZoneQualificationIsRejected() {
        // `abs` lives in std.math, not std — `std::abs` must fail.
        val result = Compiler().compile("import std.io\nimport std.math\nfunc main() {\n    std::io::println(std::abs(-5))\n}")
        assertIs<CompilationResult.Failure>(result)
    }

    @Test fun importStdExposesAllModules() =
        assertEquals("5\n9", run("import std.io\nimport std\nfunc main() {\n    std::io::println(std::math::abs(-5))\n    std::io::println(std::math::max(2, 9))\n}"))

    // ---- import syntax errors ----

    @Test fun importRejectsDoubleColonSyntax() {
        val err = assertFailsWith<IllegalStateException> {
            Compiler().compile("import std.io\nimport std.math::abs\nfunc main() {\n    std::io::println(std::math::abs(-5))\n}")
        }
        assertTrue(err.message.orEmpty().contains("Use dotted import paths"), err.message)
    }

    @Test fun dottedStdAccessIsNotNamespaceAccess() {
        val result = Compiler().compile("import std.io\nfunc main() {\n    std::io::println(std.math.abs(-5))\n}")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "std" in it }, "${'$'}{result.errors}")
    }
}
