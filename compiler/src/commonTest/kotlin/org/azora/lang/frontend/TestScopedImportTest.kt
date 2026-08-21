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
import kotlin.test.assertTrue

/**
 * A test opens with what it needs.
 *
 * ```
 * test "queue serialization metadata is declared" {
 *     import std.[
 *         reflection::reflect
 *         serializer::Serializable
 *     ]
 *     inline assert reflect<Queue>.hasAnnot<Serializable> { … }
 * }
 * ```
 *
 * Reading `reflect` is that test's business and nothing else's in the file, so
 * the import that brings it in is written beside the use rather than at the top
 * with everything the module needs.
 */
class TestScopedImportTest {

    private fun items(source: String): List<TopLevel> =
        Parser(Lexer(source).tokenize()).parse().items

    @Test fun aTestMayOpenWithItsOwnImports() {
        val parsed = items(
            """
            test "metadata is declared" {
                import std.[
                    reflection::reflect
                    serializer::Serializable
                ]
                inline assert reflect<Queue>.hasAnnot<Serializable> { "declared" }
            }
            """.trimIndent()
        )

        val test = parsed.filterIsInstance<TopLevel.Test>().single()
        assertEquals("metadata is declared", test.name)
        assertTrue(test.body.isNotEmpty(), "the assert survives the imports above it")
        assertTrue(
            parsed.any { it is TopLevel.UseImport },
            "the import reaches the resolver: $parsed",
        )
    }

    @Test fun severalImportsMayOpenATest() {
        val parsed = items(
            """
            test "two imports" {
                import std.io
                import std.math
                assert true { "ok" }
            }
            """.trimIndent()
        )
        assertEquals(2, parsed.count { it is TopLevel.UseImport })
    }

    @Test fun aTestWithoutImportsIsUnchanged() {
        val parsed = items("test \"plain\" {\n    assert true { \"ok\" }\n}")

        assertEquals(1, parsed.filterIsInstance<TopLevel.Test>().single().body.size)
        assertTrue(parsed.none { it is TopLevel.UseImport })
    }
}
