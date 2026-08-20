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

/**
 * Decides what `A::B` means as an implementation target.
 *
 * Two forms share the spelling. `impl SerialName(…) for User::name` targets a
 * *field*, and `impl Display for shapes::Circle` targets a *type* reached
 * through its scope. The parser cannot tell them apart: it emits `A.B` for
 * both. A non-empty spec implementation settles it because fields cannot own
 * implementation members; an explicitly empty implementation remains ambiguous.
 *
 * An empty decorator application or derive request has no such proof, so the decision is made here, once every
 * declaration is present - scopes the program declares and scopes the standard
 * library declares alike. `A::B` names a type when `B` is a declared type and
 * `A` has no member called `B`; a pack whose field happens to share a type's
 * name therefore still reads as a field target.
 */
internal object ScopeQualifiedImplTargets {

    fun resolve(program: Program): Program {
        val declaredTypes = program.items.mapNotNullTo(mutableSetOf()) { declaredTypeName(it) }
        if (declaredTypes.isEmpty()) return program
        val packFields = program.items.filterIsInstance<TopLevel.Pack>()
            .associate { pack -> pack.name to pack.fields.mapTo(mutableSetOf()) { it.name } }

        var rewrote = false
        val items = program.items.map { item ->
            if (item !is TopLevel.Impl) return@map item
            val resolved = scopeQualifiedType(item.typeName, declaredTypes, packFields) ?: return@map item
            rewrote = true
            item.copy(typeName = resolved)
        }
        return if (rewrote) program.copy(items = items) else program
    }

    /**
     * The type `target` names when it is a scope-qualified type, or `null` when
     * it is anything else - an unqualified target, a `Type::*` wildcard, or a
     * field.
     */
    private fun scopeQualifiedType(
        target: String,
        declaredTypes: Set<String>,
        packFields: Map<String, Set<String>>,
    ): String? {
        val dot = target.indexOf('.')
        if (dot <= 0 || dot != target.lastIndexOf('.')) return null
        val owner = target.substring(0, dot)
        val member = target.substring(dot + 1)
        if (member == "*" || member !in declaredTypes) return null
        if (packFields[owner]?.contains(member) == true) return null
        return member
    }

    private fun declaredTypeName(item: TopLevel): String? = when (item) {
        is TopLevel.Pack -> item.name
        is TopLevel.Enum -> item.name
        is TopLevel.Fail -> item.name
        is TopLevel.Slot -> item.name
        is TopLevel.Solo -> item.name
        is TopLevel.TypeAlias -> item.name
        else -> null
    }
}
