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

package org.azora.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A test opens with what it needs.
 *
 * ```
 * test "queue serialization metadata is declared" {
 *     import std::{
 *         reflection.reflect
 *         serializer.Serializable
 *     }
 *     inline assert reflect<Queue>.hasAnnot<Serializable> panic "…"
 * }
 * ```
 *
 * Reading `reflect` is that test's business and nothing else's in the file, so
 * the import that brings it in is written beside the use and binds there: it
 * stays in the test's body rather than joining the file's imports.
 */
class TestScopedImportTest {

    private fun items(source: String): List<TopLevel> =
        Parser(Lexer(source).tokenize()).parse().items

    @Test fun aTestMayOpenWithItsOwnImports() {
        val parsed = items(
            """
            test "metadata is declared" {
                import std::{
                    reflection.reflect
                    serializer.Serializable
                }
                inline assert reflect<Queue>.hasAnnot<Serializable> panic "declared"
            }
            """.trimIndent()
        )

        val test = parsed.filterIsInstance<TopLevel.Test>().single()
        assertEquals("metadata is declared", test.name)
        val import = assertIs<Stmt.Import>(test.body.first(), "the import opens the test: ${test.body}")
        assertEquals(
            listOf("std.reflection.reflect", "std.serializer.Serializable"),
            import.use.importSpecs.flatMap { it.flatten() }.map { it.first },
        )
        assertEquals(2, test.body.size, "the assert survives the import above it")
        assertTrue(parsed.none { it is TopLevel.UseImport }, "the import binds in the test alone: $parsed")
    }

    @Test fun severalImportsMayOpenATest() {
        val parsed = items(
            """
            test "two imports" {
                import std.io
                import std.math
                assert true panic "ok"
            }
            """.trimIndent()
        )
        val body = parsed.filterIsInstance<TopLevel.Test>().single().body
        assertEquals(2, body.count { it is Stmt.Import })
        assertTrue(parsed.none { it is TopLevel.UseImport })
    }

    @Test fun aModuleKeepsItsBlockImportsAtModuleScope() {
        val parsed = items(
            """
            module lib.probe
            func helper() {
                import std.io
                println("hi")
            }
            """.trimIndent()
        )
        assertEquals(1, parsed.count { it is TopLevel.UseImport })
        assertTrue(parsed.filterIsInstance<TopLevel.Func>().single().decl.body.none { it is Stmt.Import })
    }

    @Test fun aTestWithoutImportsIsUnchanged() {
        val parsed = items("test \"plain\" {\n    assert true panic \"ok\"\n}")

        assertEquals(1, parsed.filterIsInstance<TopLevel.Test>().single().body.size)
        assertTrue(parsed.none { it is TopLevel.UseImport })
    }
}
