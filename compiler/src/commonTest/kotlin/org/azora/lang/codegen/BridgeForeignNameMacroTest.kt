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
import org.azora.lang.frontend.TopLevel
import org.azora.lang.ir.IrStmt
import org.azora.lang.ir.IrTopLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BridgeForeignNameMacroTest {
    private fun compile(source: String, release: Boolean = false): CompilationResult.Success {
        val result = Compiler().compile(source.trimIndent(), release = release)
        return assertIs(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
    }

    @Test fun foreignNameExportsStorageWithDerivedLocalName() {
        val result = compile(
            """
            import std.io

            bridge .C {
                fin @foreignName("API_VERSION"): std::String = "1.2.3"
            }

            func version(): std::String {
                return apiVersion
            }

            func main() {
                std::println(version())
            }
            """,
            release = true,
        )

        val bridge = result.ast.items.filterIsInstance<TopLevel.Bridge>().single { it.values.isNotEmpty() }
        assertEquals("apiVersion", bridge.values.single().name)
        assertEquals("API_VERSION", bridge.values.single().foreignName)

        val global = result.ir.items.filterIsInstance<IrTopLevel.Global>().single {
            it.exportName == "API_VERSION"
        }
        assertEquals("API_VERSION", (global.stmt as IrStmt.FinDecl).name)
        assertTrue("@API_VERSION = global" in result.llvm, result.llvm)
        assertTrue("(export \"API_VERSION\" (global \$API_VERSION))" in result.wasm, result.wasm)
        assertEquals("1.2.3", IrInterpreter().interpret(result.ir))
    }

    @Test fun foreignNameCreatesAnAzoraFunctionWrapper() {
        val result = compile(
            """
            bridge .C {
                func @foreignName("clock_gettime")(): std::Long
            }

            func readClock(): std::Long {
                return clockGettime()
            }
            """,
        )

        assertTrue(result.ir.items.any { it is IrTopLevel.Extern && it.name == "clock_gettime" })
        assertTrue(result.ir.functions.any { it.name == "clockGettime" })
    }

    @Test fun declarationPositionExpansionIsNotHardcodedToForeignName() {
        val result = compile(
            """
            macro @abiName { target ->
                (${'$'}name: String) =>
                    ${'$'}name
                    inline if target.isType {
                        ${'$'}name.upperCamel
                    } else {
                        ${'$'}name.lowerCamel
                    }
            }

            bridge .C {
                fin @abiName("LIBRARY_REVISION"): std::Int = 7
            }

            func revision(): std::Int {
                return libraryRevision
            }
            """,
        )

        val bridge = result.ast.items.filterIsInstance<TopLevel.Bridge>().single { it.values.isNotEmpty() }
        assertEquals("libraryRevision", bridge.values.single().name)
        assertEquals("LIBRARY_REVISION", bridge.values.single().foreignName)
    }

    @Test fun generatedForeignAndLocalNameFormIsValidBridgeSyntax() {
        val result = compile(
            """
            bridge .C {
                fin "API_VERSION" apiVersion: std::String = "1.2.3"
                func "host_clock" hostClock(): std::Long
            }

            func version(): std::String { return apiVersion }
            func clock(): std::Long { return hostClock() }
            """,
        )

        val bridge = result.ast.items.filterIsInstance<TopLevel.Bridge>().single { it.values.isNotEmpty() }
        assertEquals("apiVersion", bridge.values.single().name)
        assertEquals("API_VERSION", bridge.values.single().foreignName)
        assertTrue(result.ir.items.any { it is IrTopLevel.Extern && it.name == "host_clock" })
        assertTrue(result.ir.functions.any { it.name == "hostClock" })
    }

    @Test fun declarationNameMacroMustProduceAString() {
        val result = Compiler().compile(
            """
            macro @badName {
                (${'$'}name: String) => 42
            }

            bridge .C {
                func @badName("ignored")(): std::Int
            }
            """.trimIndent(),
            release = false,
        )
        val failure = assertIs<CompilationResult.Failure>(result)
        assertTrue(failure.errors.any { "must expand to exactly two String fragments" in it }, failure.errors.toString())
    }

    @Test fun foreignNameUsesUpperCamelForBridgeTypes() {
        val result = compile(
            """
            bridge pack @foreignName("native_HTTP_client")
            """,
        )

        val pack = result.ast.items.filterIsInstance<TopLevel.Pack>().single { it.name == "NativeHttpClient" }
        assertEquals("NativeHttpClient", pack.name)
        assertEquals("native_HTTP_client", pack.foreignName)
    }

    @Test fun typedForeignNameCaptureRejectsNonStringArguments() {
        val result = Compiler().compile(
            "bridge .C { func @foreignName(42)(): std::Int }",
            release = false,
        )
        val failure = assertIs<CompilationResult.Failure>(result)
        assertTrue(failure.errors.any { "no matching arm" in it }, failure.errors.toString())
    }

    @Test fun derivedLocalNameCannotBeAReservedKeyword() {
        val result = Compiler().compile(
            "bridge .C { func @foreignName(\"FUNC\")(): std::Int }",
            release = false,
        )
        val failure = assertIs<CompilationResult.Failure>(result)
        assertTrue(failure.errors.any { "produces reserved Azora keyword 'func'" in it }, failure.errors.toString())
    }

    @Test fun bridgeUseAsIsRemovedFromTheGrammar() {
        val result = Compiler().compile(
            "bridge .C { func clock use as hostClock(): std::Long }",
            release = false,
        )
        val failure = assertIs<CompilationResult.Failure>(result)
        assertTrue(failure.errors.any { "Expected '('" in it }, failure.errors.toString())
    }
}
