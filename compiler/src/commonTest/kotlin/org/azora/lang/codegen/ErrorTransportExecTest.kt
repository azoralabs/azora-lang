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

package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `T ?! E` on the LLVM backend.
 *
 * A failable function keeps its success type, so a raised error travels in a
 * module-level slot and every call to such a function checks it. These assert
 * the shape of that lowering: that a `throw` records and returns rather than
 * aborting, that a call site checks, and that a handler clears the slot so a
 * handled error cannot leak into the next call.
 */
class ErrorTransportExecTest {

    private fun llvm(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}"
        )
        return result.llvm
    }

    private val failing = """
        fail MapError { OutOfBounds, BadSize }

        func at(x: Int): Int ?! MapError {
            if x < 0 {
                return .OutOfBounds
            }
            return x * 2
        }
    """.trimIndent()

    @Test fun aRaisedErrorIsRecordedRatherThanAborting() {
        val ir = llvm(
            """
            $failing
            func main() {
                fin v = at(1) catch 0
            }
            """.trimIndent()
        )
        assertTrue("@__azora_err = global i8* null" in ir, "the error slot must be declared:\n$ir")
        assertTrue("store i8* %" in ir && "@__azora_err" in ir, "a throw must record the error:\n$ir")
        assertFalse(
            "; throw — lowered to abort" in ir,
            "a throw must no longer be lowered to a bare abort:\n$ir"
        )
    }

    @Test fun aCallToAFailableFunctionChecksTheSlot() {
        val ir = llvm(
            """
            $failing
            func main() {
                fin v = at(1) catch 0
            }
            """.trimIndent()
        )
        assertTrue("load i8*, i8** @__azora_err" in ir, "the call site must read the slot:\n$ir")
        assertTrue("icmp ne i8* %" in ir, "the call site must test the slot:\n$ir")
    }

    @Test fun aCatchExpressionSelectsBetweenValueAndFallback() {
        val ir = llvm(
            """
            $failing
            func main() {
                fin v = at(1) catch 0
            }
            """.trimIndent()
        )
        assertTrue("phi i32" in ir, "a catch expression must merge both arms in a phi:\n$ir")
        assertTrue(
            "store i8* null, i8** @__azora_err" in ir,
            "handling an error must clear the slot so it cannot leak:\n$ir"
        )
    }

    @Test fun aTryBlockGetsACatchLabel() {
        val ir = llvm(
            """
            $failing
            func main() {
                try {
                    fin v = at(-1)
                } catch {
                }
            }
            """.trimIndent()
        )
        assertTrue("catch." in ir, "a try/catch must emit a handler block:\n$ir")
        assertFalse(
            "exception handling not lowered" in ir,
            "try/catch must no longer be left unlowered:\n$ir"
        )
    }

    @Test fun aFailableCallerPropagatesInsteadOfAborting() {
        val ir = llvm(
            """
            $failing
            func doubled(x: Int): Int ?! MapError {
                fin inner = at(x)
                return inner + 1
            }
            func main() {
                fin v = doubled(1) catch 0
            }
            """.trimIndent()
        )
        // `doubled` is failable, so its check returns rather than aborting; the
        // slot stays set for its own caller to observe.
        val body = ir.substringAfter("define i32 @doubled").substringBefore("define ")
        assertTrue("@__azora_err" in body, "the propagating call must check the slot:\n$body")
        assertFalse("call void @abort()" in body, "a failable caller must not abort:\n$body")
    }

    @Test fun anUnobservableErrorAborts() {
        val ir = llvm(
            """
            $failing
            func main() {
                fin v = at(-1)
            }
            """.trimIndent()
        )
        // `main` cannot fail and there is no handler, so the only honest thing
        // left is to stop.
        val body = ir.substringAfter("define i32 @main").substringBefore("declare ")
        assertTrue("call void @abort()" in body, "an unhandled error must abort:\n$body")
    }

    @Test fun aNonFailableCallIsNotBurdenedWithAnErrorCheck() {
        val ir = llvm(
            """
            func plain(x: Int): Int {
                return x * 2
            }
            func main() {
                fin v = plain(21)
            }
            """.trimIndent()
        )
        assertFalse(
            "@__azora_err" in ir,
            "a program with no failable function must not carry the error slot:\n$ir"
        )
    }
}
