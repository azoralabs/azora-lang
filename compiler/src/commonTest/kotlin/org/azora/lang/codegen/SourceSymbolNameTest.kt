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
import org.azora.lang.LibrarySource
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SourceSymbolNameTest {
    private fun compile(source: String): CompilationResult = Compiler().compile(source, release = false)

    private fun assertNameFailure(source: String, expected: String) {
        val result = assertIs<CompilationResult.Failure>(compile(source))
        assertTrue(result.errors.any { expected in it }, "expected '$expected' in ${result.errors}")
    }

    @Test fun doubleUnderscoreIsReservedForCompilerSymbols() {
        assertNameFailure("func __helper() {}", "reserved for compiler-generated declarations")
        assertNameFailure("fin __value = 1", "reserved for compiler-generated declarations")
        assertNameFailure("pack __Value", "reserved for compiler-generated declarations")
    }

    @Test fun underscoreMayNotAppearInsideASymbol() {
        assertNameFailure("func helper_value() {}", "'_' is allowed only once")
        assertNameFailure("func main() { fin local_value = 1 }", "'_' is allowed only once")
        assertNameFailure("pack Value_Name", "'_' is allowed only once")
        assertNameFailure("bridge .C { func clock_get_time(): std::Long }", "'_' is allowed only once")
    }

    @Test fun leadingUnderscoreIsRejectedWherePrivacyDoesNotApply() {
        assertNameFailure("module _private\nfunc main() {}", "cannot be private")
        assertNameFailure("realm _private { func value() {} }\nfunc main() {}", "cannot be private")
        assertNameFailure("func read(_value: std::Int) {}", "cannot be private")
        assertNameFailure("func main() { fin _value = 1 }", "cannot be private")
        assertNameFailure("func main<T_value>() {}", "'_' is allowed only once")
    }

    @Test fun leadingUnderscoreIsAcceptedForPrivateDeclarations() {
        val result = compile("""
            fin _answer = 42

            pack _Counter {
                var _value: std::Int
            }

            impl pack _Counter {
                prop _valuePlusAnswer[self: std::Self&]: std::Int = self._value + _answer

                func _readValue[self: std::Self&](): std::Int {
                    return self._value + _answer
                }

                func readValue[self: std::Self&](): std::Int {
                    return self._readValue()
                }
            }

            func main(): std::Int {
                return _Counter(1).readValue()
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
    }

    @Test fun realmManglingDoesNotLookLikeAUserUnderscore() {
        val result = compile("""
            realm tools {
                func answer(): std::Int { return 42 }
            }

            func main(): std::Int { return tools::answer() }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
    }

    @Test fun privateModuleStorageCannotBeNamedByAnImporter() {
        val library = LibrarySource(
            "lib/state.az",
            "module lib.state\nfin _secret = 42\nfunc answer(): std::Int { return _secret }",
        )
        val result = Compiler(listOf(library)).compile("""
            import lib.state
            func main(): std::Int { return _secret }
        """.trimIndent())
        val failure = assertIs<CompilationResult.Failure>(result)
        assertTrue(failure.errors.any { "'_secret' is private to module 'lib.state'" in it }, failure.errors.toString())
    }

    @Test fun compilerGeneratedDoubleUnderscoreNamesStillReachIr() {
        val result = compile("""
            realm tools {
                func generated(): std::Int { return 1 }
            }

            func main(): std::Int { return tools::generated() }
        """.trimIndent())
        val success = assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        assertTrue(success.ir.functions.any { it.name.startsWith("__") })
    }
}
