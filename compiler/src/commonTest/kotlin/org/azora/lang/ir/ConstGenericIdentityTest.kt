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

package org.azora.lang.ir

import org.azora.lang.frontend.TypeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A const generic argument selects a layout, so it belongs to a type\'s identity.
 * A type argument still erases, and two references to one pack stay the same type.
 */
class ConstGenericIdentityTest {

    private fun vec(element: String, size: Long?): IrType =
        IrType.resolve(
            TypeRef.Named(
                "Vec",
                listOf(
                    TypeRef.Named(element),
                    size?.let { TypeRef.Const(it) } ?: TypeRef.Named("N"),
                ),
            ),
        )

    @Test
    fun differentConstArgumentsAreDifferentTypes() {
        assertNotEquals(vec("Int", 2), vec("Int", 3))
    }

    @Test
    fun theSameConstArgumentIsTheSameType() {
        assertEquals(vec("Int", 2), vec("Int", 2))
        assertEquals(vec("Int", 2).hashCode(), vec("Int", 2).hashCode())
    }

    @Test
    fun renderingDistinguishesConstArguments() {
        assertEquals("Vec<_, 2>", vec("Int", 2).toString())
        assertEquals("Vec<_, 3>", vec("Int", 3).toString())
        assertNotEquals(vec("Int", 2).toString(), vec("Int", 3).toString())
    }

    @Test
    fun anAbstractConstArgumentStaysAbstract() {
        // `Vec<Int, N>` has chosen no layout, so it neither renders nor compares as
        // a concrete specialization.
        val abstract = vec("Int", null)
        assertEquals("Vec", abstract.toString())
        assertNotEquals(abstract, vec("Int", 2))
        assertEquals(abstract, vec("Int", null))
    }

    @Test
    fun typeArgumentsStillErase() {
        // Identity stays nominal in type arguments: a pack with no const argument
        // compares equal however it was spelled.
        val bare = IrType.resolve(TypeRef.Named("Box"))
        val applied = IrType.resolve(TypeRef.Named("Box", listOf(TypeRef.Named("Real"))))
        assertEquals(bare, applied)
    }

    @Test
    fun nestedConstGenericsKeepTheirOwnIdentity() {
        fun holder(size: Long) = IrType.resolve(
            TypeRef.Named(
                "Holder",
                listOf(TypeRef.Named("Vec", listOf(TypeRef.Named("Int"), TypeRef.Const(size)))),
            ),
        )
        // The outer type has no const argument of its own, so it stays nominal…
        assertEquals(holder(2), holder(3))
        // …while the nested application keeps its own distinct identity.
        val inner2 = (holder(2) as IrType.Named).args.single()
        val inner3 = (holder(3) as IrType.Named).args.single()
        assertNotEquals(inner2, inner3)
        assertTrue(inner2 is IrType.Named && inner2.constArgs == listOf(null, 2L))
    }
}
