/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `foreign (…) -> R`: a C function at an address, called by the C ABI. */
class ForeignFunctionValueTest {
    private val program = """
        bridge .C {
            func @foreignName("dlsym")(library: Long, name: String): Long
        }
        func lookup(name: String): foreign (String) -> Int = dlsym(-2, name) as* foreign (String) -> Int
        func measure(text: String): Int {
            fin length = lookup("strlen")
            return length(text)
        }
        func main() {
            fin twice: foreign (Double, Double) -> Double = dlsym(-2, "pow") as* foreign (Double, Double) -> Double
            measure("azora")
            twice(2.0, 3.0)
        }
    """.trimIndent()

    @Test fun aForeignValueIsAnAddressCalledWithoutAnEnvironment() {
        val result = Compiler().compile(program, release = false)
        assertIs<CompilationResult.Success>(result)
        val llvm = result.llvm
        assertTrue("inttoptr i64" in llvm, llvm)
        assertTrue("to i32 (i8*)*" in llvm, llvm)
        assertTrue("to double (double, double)*" in llvm, llvm)
        assertFalse("%azora.closure" in llvm.substringAfter("define i32 @measure").substringBefore("\n}"),
            "a foreign call reads no closure")
    }

    @Test fun aLiteralBranchTakesTheOtherBranchesWidth() {
        val result = Compiler().compile("""
            bridge .C {
                func @foreignName("dlsym")(library: Long, name: String): Long
            }
            func find(library: Long): Long {
                fin entry = if library == 0 then 0 else dlsym(library, "strlen")
                return entry
            }
            func main() { find(-2) }
        """.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(result)
        val body = result.llvm.substringAfter("@find(").substringBefore("\n}")
        assertFalse("trunc i64" in body, "the address must keep its width:\n$body")
    }

    @Test fun foreignIsAnOrdinaryNameOutsideACallableType() {
        val result = Compiler().compile("func main() { fin foreign = 3\n foreign + 1 }", release = false)
        assertIs<CompilationResult.Success>(result)
    }
}
