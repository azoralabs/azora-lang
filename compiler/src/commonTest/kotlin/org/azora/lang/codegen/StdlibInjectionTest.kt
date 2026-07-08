package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [org.azora.lang.stdlib.StdlibInjector]: standard-library functions
 * are injected on demand and run through the interpreter like user code.
 */
class StdlibInjectionTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir)
    }

    // ---- math ----

    @Test fun absWorks() =
        assertEquals("5\n7", run("use std\nfunc main() {\n    println(abs(-5))\n    println(abs(7))\n}"))

    @Test fun minMaxWork() =
        assertEquals("2\n9", run("use std\nfunc main() {\n    println(min(2, 9))\n    println(max(2, 9))\n}"))

    @Test fun clampWorks() =
        assertEquals("5\n0\n10", run("use std\nfunc main() {\n    println(clamp(5, 0, 10))\n    println(clamp(-3, 0, 10))\n    println(clamp(42, 0, 10))\n}"))

    @Test fun signWorks() =
        assertEquals("1\n-1\n0", run("use std\nfunc main() {\n    println(sign(9))\n    println(sign(-4))\n    println(sign(0))\n}"))

    @Test fun piConstantInjects() {
        val out = run("use std\nfunc main() {\n    println(PI)\n}")
        assertTrue(out.startsWith("3.14159"), out)
    }

    @Test fun floorCeilRound() =
        assertEquals("3\n4\n4", run("use std\nfunc main() {\n    println(floor(3.7))\n    println(ceil(3.2))\n    println(round(3.6))\n}"))

    @Test fun powAndFactorial() =
        assertEquals("8\n120", run("use std\nfunc main() {\n    println(pow(2, 3))\n    println(factorial(5))\n}"))

    @Test fun gcdWorks() =
        assertEquals("6", run("use std\nfunc main() {\n    println(gcd(54, 24))\n}"))

    // ---- transitive + shadowing ----

    @Test fun transitiveStdlibCallsResolve() {
        // lcm uses gcd internally — both must inject.
        assertEquals("36", run("use std\nfunc main() {\n    println(lcm(12, 18))\n}"))
    }

    @Test fun userDefinitionShadowsStdlib() =
        assertEquals("99", run("use std.math\nfunc abs(x: Int): Int {\n    return 99\n}\nfunc main() {\n    println(abs(-5))\n}"))

    @Test fun programsWithoutStdlibAreUntouched() {
        val result = Compiler().compile("func main() {\n    println(1)\n}")
        assertIs<CompilationResult.Success>(result)
        assertEquals(listOf("main"), result.ir.functions.map { it.name })
    }

    // ---- if-expressions (new language feature the stdlib relies on) ----

    @Test fun ifExpressionInUserCode() =
        assertEquals("small", run("func main() {\n    let label = if 3 > 10 { \"big\" } else { \"small\" }\n    println(label)\n}"))

    @Test fun ifExpressionElseIfChain() =
        assertEquals("mid", run("func pick(x: Int): String = if x > 10 { \"big\" } else if x > 3 { \"mid\" } else { \"small\" }\nfunc main() {\n    println(pick(5))\n}"))

    @Test fun expressionBodiedFunction() =
        assertEquals("14", run("func twice(x: Int): Int = x * 2\nfunc main() {\n    println(twice(7))\n}"))

    // ---- import gating ----

    @Test fun unimportedStdlibIsInvisible() {
        val result = Compiler().compile("func main() {\n    println(abs(-5))\n}")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "use std.math" in it }, "error should hint at the import: ${'$'}{result.errors}")
    }

    @Test fun moduleImportScopesVisibility() {
        // std.math imported, std.algorithm not.
        val result = Compiler().compile("use std.math\nfunc main() {\n    println(abs(-5))\n    println(isSorted([1, 2]))\n}")
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "use std.algorithm" in it }, "${'$'}{result.errors}")
    }

    @Test fun selectiveImportOnlyExposesListedNames() {
        assertEquals("5", run("use std.math::abs\nfunc main() {\n    println(abs(-5))\n}"))
        val gated = Compiler().compile("use std.math::abs\nfunc main() {\n    println(min(1, 2))\n}")
        assertIs<CompilationResult.Failure>(gated)
    }

    @Test fun useScopeStdImportsEverything() {
        assertEquals("5", run("use scope std\nfunc main() {\n    println(abs(-5))\n}"))
    }

    @Test fun qualifiedAccessNeedsNoImport() {
        assertEquals("5\n2", run("func main() {\n    println(std.math.abs(-5))\n    println(std.min(2, 9))\n}"))
    }

    @Test fun qualifiedConstantAccess() {
        val out = run("func main() {\n    println(std.math.PI)\n}")
        assertTrue(out.startsWith("3.14159"), out)
    }
}
