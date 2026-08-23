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
import org.azora.lang.frontend.ParamModifier
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineArchitectureSyntaxTest {

    private val facade = LibrarySource(
        "engine.az",
        """
        module engine

        exposed import engine::ecs
        """.trimIndent(),
    )

    private val ecs = LibrarySource(
        "engine/ecs.az",
        """
        module engine.ecs


        pack Resource<T> { fin value: T }
        pack MutResource<T> { var value: T }
        bridge pack Query<...T>
        pack Vec3 { var x: Double }
        typealias Vector3 = Vec3
        pack Transform { var translation: Vector3 }

        pack Single<...T> where (...T).size >= 1 {
            fin matched: Bool = true
            inline for Ty in ...T {
                value: Ty
            }
        }
        impl Single<...T> {
            oper.* [self: Self&] {
                return self.value
            }
        }

        enum SystemPhase { Update }
        annot @Component for [.Pack, .Enum]
        annot @System for .Func {
            fin phase: SystemPhase
        }
        """.trimIndent(),
    )

    @Test
    fun libraryDefinedResourceAndQueryTypesExpandAfterImportResolution() {
        val result = Compiler(listOf(facade, ecs)).compile(
            """
            import engine

            @Component
            pack Player {
                var speed = 8.0
            }

            @Component
            pack Health {
                var level = 5
            }

            pack Time
            @System(.Update)
            func move(
                time: Resource<Time>
                player: Single<Transform!>!
                query: Query<Transform!, Player&>!
            ) {
                player.translation.x = 2.0
            }
            """.trimIndent(),
            release = false,
        )
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors?.joinToString("\n"))

        val move = result.ast.items.filterIsInstance<TopLevel.Func>().single { it.decl.name == "move" }.decl
        assertEquals(listOf("time", "player", "query"), move.params.map { it.name })
        assertEquals(ParamModifier.NONE, move.params[0].modifier)
        assertEquals(ParamModifier.EXCLUSIVE, move.params[1].modifier)
        assertEquals(ParamModifier.EXCLUSIVE, move.params[2].modifier)
        assertEquals("Resource", assertIs<TypeRef.Named>(move.params[0].type).name)
        assertTrue(assertIs<TypeRef.Named>(move.params[1].type).name.startsWith("__Single_"))
        assertTrue(assertIs<TypeRef.Named>(move.params[2].type).name.startsWith("__Query_"))
        val player = result.ast.items.filterIsInstance<TopLevel.Pack>().single { it.name == "Player" }
        val health = result.ast.items.filterIsInstance<TopLevel.Pack>().single { it.name == "Health" }
        // A field inferred from a real literal takes the default float width.
        assertEquals("Float", assertIs<TypeRef.Named>(player.fields.single().type).name)
        assertEquals("Int", assertIs<TypeRef.Named>(health.fields.single().type).name)
        assertFalse(result.llvm.contains("__azora_named_type_macro__"))
        assertFalse(result.wasm.contains("__azora_named_type_macro__"))
    }
}
