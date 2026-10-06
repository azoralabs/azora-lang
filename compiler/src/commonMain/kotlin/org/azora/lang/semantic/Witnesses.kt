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

import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.PackField
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeRef

/**
 * What generic code reaches through a witness.
 *
 * A generic value is erased to an eight-byte slot, which says nothing about
 * what it holds. `key.hash` inside `HashMap<K, V>` has to hash a `String` by
 * its content and a pack by its own `hash`, so the code needs to be told which
 * `K` it is running for. A type parameter bounded by one of these specs carries
 * a descriptor (a small integer naming the concrete type) and its operations
 * dispatch on it; see `IrGenerator`'s witness lowering.
 */
internal object Witnesses {
    const val HASH = "Hash"
    const val EQUAL = "Equal"
    const val ORDER = "Order"
    const val CLONE = "Clone"

    /** The one spec a set of needs names in a diagnostic: what the bound must say. */
    fun named(needs: Set<String>): String = when {
        HASH in needs -> HASH
        ORDER in needs -> ORDER
        EQUAL in needs -> EQUAL
        CLONE in needs -> CLONE
        else -> EQUAL
    }

    /** The name of the hidden field or parameter holding [param]'s descriptor. */
    fun slot(param: String): String = "__witness_$param"

    /**
     * The witness specs [clause] requires of each of [typeParams]. `Hash` and
     * `Order` require `Equal` (equal values hash equally and sort together),
     * so either bound gives `==` as well.
     */
    fun bounds(clause: Expr?, typeParams: Collection<String>): Map<String, Set<String>> {
        if (clause == null || typeParams.isEmpty()) return emptyMap()
        val found = LinkedHashMap<String, MutableSet<String>>()
        fun visit(expr: Expr) {
            when (expr) {
                is Expr.Binary -> if (expr.op == TokenType.AND_AND) {
                    visit(expr.left)
                    visit(expr.right)
                }
                is Expr.IsCheck -> {
                    val param = (expr.expr as? Expr.Identifier)?.name ?: return
                    if (param !in typeParams) return
                    when (expr.typeName.substringAfterLast("__")) {
                        HASH -> found.getOrPut(param) { linkedSetOf() }.addAll(listOf(HASH, EQUAL))
                        ORDER -> found.getOrPut(param) { linkedSetOf() }.addAll(listOf(ORDER, EQUAL))
                        EQUAL -> found.getOrPut(param) { linkedSetOf() }.add(EQUAL)
                        // An erased slot is a shared pointer; only the concrete
                        // type knows how to make an independent copy of it.
                        CLONE -> found.getOrPut(param) { linkedSetOf() }.add(CLONE)
                    }
                }
                is Expr.Grouping -> visit(expr.expr)
                else -> {}
            }
        }
        visit(clause)
        return found
    }

    /** The witness bounds a pack declares on its own type parameters. */
    fun bounds(pack: TopLevel.Pack): Map<String, Set<String>> = bounds(pack.whereClause, pack.typeParams)

    /**
     * Gives every pack with a witness bound one descriptor field per bounded
     * parameter. The field is an ordinary `Int`, so layout, construction and
     * copying treat it like any other; `-1` is "not told", which dispatch
     * reports rather than guesses from. A name that starts with two
     * underscores cannot be written in source, so no program reads or writes
     * one.
     */
    fun addDescriptorFields(program: Program): Program {
        var changed = false
        val items = program.items.map { item ->
            if (item !is TopLevel.Pack || item.isBridge) return@map item
            val bounded = bounds(item).keys.filter { param -> item.fields.none { it.name == slot(param) } }
            if (bounded.isEmpty()) return@map item
            changed = true
            item.copy(
                fields = item.fields + bounded.map { param ->
                    PackField(slot(param), TypeRef.Named("Int"), mutable = true, default = Expr.IntLiteral(-1, item.line, 0))
                },
            )
        }
        return if (changed) program.copy(items = items) else program
    }
}
