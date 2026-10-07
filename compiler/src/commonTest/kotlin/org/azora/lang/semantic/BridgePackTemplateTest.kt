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

package org.azora.lang.semantic

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A primitive stays a primitive when a library extends it at compile time.
 *
 * `Int` is `bridge pack Int<N: __uint = 32>`: it has a parameter, and an impl on
 * it with a compile-time choice made the monomorphiser treat it as a layout
 * template. Templates' impls are re-emitted per specialization and the
 * originals dropped - and `Int` has no specializations, so every impl on it
 * vanished, `oper ..` with them, and any `for i in 0..<n` in the program failed.
 * The Engine's math library is exactly such an extension.
 */
class BridgePackTemplateTest {

    @Test fun aCompileTimeExtensionOnIntKeepsItsRanges() {
        val result = Compiler().compile("""
            import std.io

            impl Int {
                func &.doubledIfWide(): Int {
                    inline if true {
                        return self * 2
                    } else {
                        return self
                    }
                }
            }

            func main() {
                for i in 0..<2 {
                    println(i + 2.doubledIfWide())
                }
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        assertEquals("4\n5", IrInterpreter().interpret(result.ir).trim())
    }
}
