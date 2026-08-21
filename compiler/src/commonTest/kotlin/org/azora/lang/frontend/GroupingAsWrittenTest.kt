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
 * Grouping as `std/container/map.az` writes it.
 *
 * The members of `TreeMap` and `HashMap` are where the feature is actually
 * used, and they use every form at once: a `let` group whose names each state
 * a type, a target group with no receiver spanning two lines, values read off
 * `self`, and a value list on the line after the `=`. Parsing them is not
 * enough - each has to mean the lines it stands for, which is what this
 * checks.
 */
class GroupingAsWrittenTest {

    private fun body(source: String): List<Stmt> =
        Parser(Lexer("impl TreeMap<K, V> {\n    func _grow[self!]() {\n$source\n    }\n}").tokenize())
            .parse().items.filterIsInstance<TopLevel.Impl>().single()
            .methods.single().body

    /** `self.<name>` under a receiver, as `[a, b] = with self { … }` produces. */
    private fun readsFromSelf(value: Expr): String {
        val index = assertIs<Expr.Index>(value)
        val member = assertIs<Expr.Member>(index.target)
        assertEquals("self", assertIs<Expr.Identifier>(member.target).name)
        return member.name
    }

    @Test fun theGrowBodyMeansTheLinesItStandsFor() {
        val stmts = body(
            """
            fin newCapacity = self.capacity * 2
            let [
                newKeys: K*
                newValues: V*
                newLeft: Int*
            ] = alloc .() * newCapacity

            for i in 0..<self.elemCount {
                [newKeys[i], newValues[i]
                newLeft[i]] = with self {
                    [keys[i], values[i], left[i]]
                }
            }
            with self purge [keys, values, left]
            self.[keys, values, left, capacity] =
                [newKeys, newValues, newLeft, newCapacity]
            """.trimIndent(),
        )

        // One `let` per name, each with the type it stated and an allocation of
        // its own: four buffers are four buffers.
        val declared = stmts.filterIsInstance<Stmt.LetDecl>()
        assertEquals(listOf("newKeys", "newValues", "newLeft"), declared.map { it.name })
        assertEquals(
            listOf("K*", "V*", "Int*"),
            declared.map { assertIs<TypeAnnotation.Explicit>(it.type).ref.displayName() },
        )
        assertTrue(
            declared.all { assertIs<Expr.Binary>(it.initializer).left is Expr.Alloc },
            "each name allocates for itself: ${declared.map { it.initializer }}",
        )

        // The loop body: a target group with no receiver, taking values read
        // off `self` at the same positions.
        val loop = stmts.filterIsInstance<Stmt.For>().single()
        val copies = assertIs<Stmt.Scope>(loop.body.single()).body
        assertEquals(3, copies.size)
        assertEquals(
            listOf("newKeys", "newValues", "newLeft"),
            copies.map { assertIs<Expr.Identifier>(assertIs<Stmt.IndexAssign>(it).target).name },
        )
        assertEquals(
            listOf("keys", "values", "left"),
            copies.map { readsFromSelf(assertIs<Stmt.IndexAssign>(it).value) },
        )

        // `with self purge [ … ]` is still the statement it was.
        assertIs<Stmt.WithContext>(stmts.first { it is Stmt.WithContext })

        // The value list on the line after the `=`, one value per member.
        val assigned = assertIs<Stmt.Scope>(stmts.last()).body
        assertEquals(
            listOf("keys", "values", "left", "capacity"),
            assigned.map { assertIs<Stmt.MemberAssign>(it).name },
        )
        assertEquals(
            listOf("newKeys", "newValues", "newLeft", "newCapacity"),
            assigned.map { assertIs<Expr.Identifier>(assertIs<Stmt.MemberAssign>(it).value).name },
        )
    }

    @Test fun theRehashBodyMeansTheLinesItStandsFor() {
        val stmts = body(
            """
            fin [oldKeys, oldValues, oldCapacity] = with self {
                [keys, values, capacity]
            }

            self.[keys, values, hashes] = alloc .() * newCapacity
            self.[capacity, _size] = [newCapacity, 0]
            """.trimIndent(),
        )

        // Read off `self`, bound under this scope's own names.
        val bound = stmts.filterIsInstance<Stmt.FinDecl>()
        assertEquals(listOf("oldKeys", "oldValues", "oldCapacity"), bound.map { it.name })
        assertEquals(
            listOf("keys", "values", "capacity"),
            bound.map { assertIs<Expr.Member>(it.initializer).name },
        )

        // The expression is written to each member, not evaluated once: three
        // members, three allocations.
        val allocated = assertIs<Stmt.Scope>(stmts[3]).body
        assertEquals(listOf("keys", "values", "hashes"), allocated.map { assertIs<Stmt.MemberAssign>(it).name })
        assertTrue(
            allocated.all { assertIs<Expr.Binary>(assertIs<Stmt.MemberAssign>(it).value).left is Expr.Alloc },
            "each member allocates for itself: $allocated",
        )
        assertTrue(allocated.none { it is Stmt.FinDecl }, "nothing is bound in between")

        // And the positional form beside it.
        val reset = assertIs<Stmt.Scope>(stmts[4]).body
        assertEquals(listOf("capacity", "_size"), reset.map { assertIs<Stmt.MemberAssign>(it).name })
    }

    @Test fun elementTargetsTakeAPositionalList() {
        // `_allocateNode` writes six slots of one node in one statement.
        val stmts = body(
            """
            self.[keys[elem], values[elem], parent[elem], heights[elem]] =
                [key, value, -1, 1]
            """.trimIndent(),
        )

        val assigned = assertIs<Stmt.Scope>(stmts.single()).body
        assertEquals(
            listOf("keys", "values", "parent", "heights"),
            assigned.map { assertIs<Expr.Member>(assertIs<Stmt.IndexAssign>(it).target).name },
        )
        assertTrue(
            assigned.all { assertIs<Expr.Identifier>(assertIs<Stmt.IndexAssign>(it).index).name == "elem" },
            "every slot is the same node",
        )
    }

    @Test fun theAssignmentsRunInTheOrderTheyAreWritten() {
        // `self.[freeNext[elem], freeHead] = [self.freeHead, elem]` relinks a
        // free list: the first line reads `freeHead` and the second overwrites
        // it, which only works because the group is those two lines and not a
        // pre-computed pair.
        val stmts = body("self.[freeNext[elem], freeHead] = [self.freeHead, elem]")

        val assigned = assertIs<Stmt.Scope>(stmts.single()).body
        assertEquals(2, assigned.size)
        val relink = assertIs<Stmt.IndexAssign>(assigned[0])
        assertEquals("freeNext", assertIs<Expr.Member>(relink.target).name)
        assertEquals("freeHead", assertIs<Expr.Member>(relink.value).name)
        val head = assertIs<Stmt.MemberAssign>(assigned[1])
        assertEquals("freeHead", head.name)
        assertEquals("elem", assertIs<Expr.Identifier>(head.value).name)
    }

    @Test fun aConstructorShorthandSurvivesTheGroup() {
        // `.()` reads its type from the member it lands in, exactly as it does
        // when the line is written on its own.
        val stmts = body("self.[parts, count] = [.() * 16, 0]")

        val assigned = assertIs<Stmt.Scope>(stmts.single()).body
        assertIs<Expr.InferredMember>(assertIs<Expr.Binary>(assertIs<Stmt.MemberAssign>(assigned[0]).value).left)
    }

    @Test fun theElementCopyReadsTheNextSlot() {
        // `self.[keys[i], …] = with self { [keys[i + 1], …] }` - the index on
        // the right is this scope's own expression, and only the head moves
        // onto the receiver.
        val stmts = body(
            """
            self.[keys[i], values[i]] = with self {
                [keys[i + 1], values[i + 1]]
            }
            """.trimIndent(),
        )

        val copies = assertIs<Stmt.Scope>(stmts.single()).body
        assertEquals(2, copies.size)
        for (copy in copies) {
            val assign = assertIs<Stmt.IndexAssign>(copy)
            // The target is a member of `self`, indexed by `i`.
            assertEquals("self", assertIs<Expr.Identifier>(assertIs<Expr.Member>(assign.target).target).name)
            assertEquals("i", assertIs<Expr.Identifier>(assign.index).name)
            // The value is the same member of `self`, indexed by `i + 1`.
            assertIs<Expr.Binary>(assertIs<Expr.Index>(assign.value).index)
        }
        assertEquals(
            listOf("keys", "values"),
            copies.map { assertIs<Expr.Member>(assertIs<Stmt.IndexAssign>(it).target).name },
        )
    }
}
