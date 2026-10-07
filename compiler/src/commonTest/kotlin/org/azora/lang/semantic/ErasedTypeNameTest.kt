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
import kotlin.test.assertTrue

/**
 * `T.typeName` reached from an erased generic.
 *
 * An inline function that keys something by `T.typeName` folds at its call
 * site. Called from a generic function, `T` there is still a parameter, and the
 * one erased body would fold it to the string "T" for every instantiation - a
 * lookup that silently finds the wrong thing. It is reported instead.
 */
class ErasedTypeNameTest {

    private val nameOf = """
        import std.io

        pack Alpha { var v: Int = 0 }
        pack Beta { var v: Int = 0 }

        inline func<T> nameOf(): String {
            return T.typeName
        }

        inline func<T> nameThrough(): String {
            return nameOf<T>()
        }
    """.trimIndent()

    @Test fun aCallSiteThatNamesTheTypeFoldsItsName() {
        val result = Compiler().compile(nameOf + "\n" + """
            func main() {
                println("${'$'}{nameOf<Alpha>()} ${'$'}{nameThrough<Beta>()}")
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result, "${(result as? CompilationResult.Failure)?.errors}")
        assertEquals("Alpha Beta", IrInterpreter().interpret(result.ir).trim())
    }

    @Test fun anErasedGenericCannotFoldItsParametersName() {
        val result = Compiler().compile(nameOf + "\n" + """
            func<T> describe(): String {
                return nameThrough<T>()
            }

            func main() {
                println(describe<Alpha>())
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "generics are erased" in it }, "${result.errors}")
    }

    @Test fun anErasedGenericMemberCannotEither() {
        val result = Compiler().compile(nameOf + "\n" + """
            pack Finder { var n: Int = 0 }
            impl Finder {
                func <T> &.describe(): String {
                    return nameOf<T>()
                }
            }

            func main() {
                println(Finder().describe<Alpha>())
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(result.errors.any { "generics are erased" in it }, "${result.errors}")
    }
}
