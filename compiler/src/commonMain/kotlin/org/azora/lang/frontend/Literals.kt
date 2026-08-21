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

/**
 * The two literals the language writes numbers as, and the types written with
 * them.
 *
 * `4` is an [INT] and `4.0` a [REAL]; neither is a width. A width is a type
 * that says it is written as one of these - `bridge pack Byte(IntLiteral)` in
 * `std/primitive.az` - which is what makes `Byte(4)`, `Int(4)` and `4` the same
 * thing said with different precision about which width is meant.
 *
 * The set lives here rather than in the parser because three passes ask the
 * same question: the parser checks the declaration, the resolver reads a call
 * to one of these types as the literal it is, and code generation needs to
 * know which width was named.
 */
object Literals {

    /** `1`, `0b0101`, `0xFFFFFFFF` - signed. */
    const val INT = "__int"

    /** `1`, `0b0101`, `0xFFFFFFFF` - unsigned. */
    const val UINT = "__uint"

    /** `2.0`, `5.34`. */
    const val REAL = "__float"

    /** What a `bridge pack Name(…)` may say it is written as. */
    val KINDS = setOf(INT, UINT, REAL)
}
