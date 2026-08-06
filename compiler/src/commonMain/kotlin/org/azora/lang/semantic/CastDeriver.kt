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

import org.azora.lang.frontend.Program
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeRef

/**
 * Gives a cast impl's member the name its *target* makes unique.
 *
 * `DIPs/CAST_DIP.MD` §4 puts the target on the impl rather than on the
 * operator, which is what makes each body checkable — the older `oper as<U>`
 * had to be correct for every `U` while naming exactly one of them. The cost is
 * that every `Cast` impl for a type names its member `cast`:
 *
 * ```azora
 * impl Cast<Fahrenheit> for Celsius { prop _cast[self: Self&]: Fahrenheit { … } }
 * impl Cast<Kelvin>     for Celsius { prop _cast[self: Self&]: Kelvin     { … } }
 * ```
 *
 * so without the target in the name the second collides with the first. This
 * renames each to the operator member the cast dispatch looks for —
 * `operas@Fahrenheit` — before anything else sees it, which is why the symbol
 * collector and the IR generator need no rule of their own. It is also why
 * `_cast` is never callable by that name: a cast is written `value as To`, and
 * the member is gone by the time any call could resolve to it. Two places deciding
 * a member's name independently is how they drift.
 */
object CastDeriver {
    private val casts = mapOf(
        "Cast" to "operas",
        "CheckedCast" to "operas?",
        "BitCast" to "operas*",
    )

    fun rewrite(program: Program): Program {
        if (program.items.none { it is TopLevel.Impl && it.traitName in casts }) return program
        val items = program.items.map { item ->
            val impl = item as? TopLevel.Impl ?: return@map item
            val operator = casts[impl.traitName] ?: return@map item
            val target = (impl.traitArgs.firstOrNull() as? TypeRef.Named)?.name ?: return@map item
            impl.copy(methods = impl.methods.map { it.copy(name = "$operator@$target") })
        }
        return program.copy(items = items)
    }
}
