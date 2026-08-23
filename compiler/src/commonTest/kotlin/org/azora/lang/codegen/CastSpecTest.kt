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
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The cast specs, `DIPs/CAST_DIP.MD` §4.
 *
 * `Cast` / `CheckedCast` / `BitCast` put the target on the *impl* rather than
 * on the operator, which is what makes each body checkable - the older
 * `oper as<U>` had to be correct for every `U` while naming exactly one - and
 * what lets a type convert to more than one thing.
 */
class CastSpecTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun castDispatchesToTheImplForItsTarget() {
        assertEquals("212", run("""
            import std.io
            import std::convert
            pack Celsius {
                var degrees: Int
            }
            pack Fahrenheit {
                var degrees: Int
            }
            impl Cast<Fahrenheit> for Celsius {
                prop &.castValue: Fahrenheit {
                    return Fahrenheit(self.degrees * 9 / 5 + 32)
                }
            }
            func main() {
                fin f = Celsius(100) as Fahrenheit
                println(f.degrees)
            }
        """.trimIndent()))
    }

    /** The target is on the impl, so a type may convert to several things. */
    @Test fun aTypeMayCastToMoreThanOneTarget() {
        assertEquals("212\n373", run("""
            import std.io
            import std::convert
            pack Celsius {
                var degrees: Int
            }
            pack Fahrenheit {
                var degrees: Int
            }
            pack Kelvin {
                var degrees: Int
            }
            impl Cast<Fahrenheit> for Celsius {
                prop &.castValue: Fahrenheit {
                    return Fahrenheit(self.degrees * 9 / 5 + 32)
                }
            }
            impl Cast<Kelvin> for Celsius {
                prop &.castValue: Kelvin {
                    return Kelvin(self.degrees + 273)
                }
            }
            func main() {
                println((Celsius(100) as Fahrenheit).degrees)
                println((Celsius(100) as Kelvin).degrees)
            }
        """.trimIndent()))
    }

    /** `as*` asks its own spec: the three never stand in for one another. */
    @Test fun bitCastIsItsOwnSpec() {
        assertEquals("7\n1", run("""
            import std.io
            import std::convert
            pack Handle {
                var raw: Int
            }
            pack Id {
                var v: Int
            }
            impl Cast<Id> for Handle {
                prop &.castValue: Id {
                    return Id(1)
                }
            }
            impl BitCast<Id> for Handle {
                prop &.castValue: Id {
                    return Id(7)
                }
            }
            func main() {
                println((Handle(0) as* Id).v)
                println((Handle(0) as Id).v)
            }
        """.trimIndent()))
    }
}
