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
import org.azora.lang.frontend.TokenType

/**
 * What a declaration's bounds say about one of its type parameters.
 *
 * Shared by every place that asks whether generic code may put one of its own
 * type parameters where a bound is required: written types, constructions,
 * calls, and an implementation against the spec member it implements.
 */
internal object Bounds {

    /**
     * The specs [clause] requires of [param] outright: its `param is Spec`
     * conjuncts. A requirement under `||` or `!` binds no single spec, so it is
     * left to the concrete type that finally stands for the parameter.
     */
    fun required(clause: Expr?, param: String): Set<String> {
        if (clause == null) return emptySet()
        val found = LinkedHashSet<String>()
        fun visit(expr: Expr) {
            when (expr) {
                is Expr.Grouping -> visit(expr.expr)
                is Expr.Binary -> if (expr.op == TokenType.AND_AND) {
                    visit(expr.left)
                    visit(expr.right)
                }
                is Expr.IsCheck -> if (nameOf(expr.expr) == param) found.add(expr.typeName)
                else -> {}
            }
        }
        visit(clause)
        return found
    }

    /** Each of [typeParams] with what [clause] requires of it. */
    fun declared(typeParams: Collection<String>, clause: Expr?): Map<String, Set<String>> =
        typeParams.associateWith { required(clause, it) }

    /**
     * [bounds] and everything conforming to them entails: each spec's parents
     * and the specs it requires. `U: Order` may stand where `Equal` is asked.
     */
    fun implied(bounds: Set<String>, table: SymbolTable): Set<String> {
        val all = LinkedHashSet<String>()
        val pending = ArrayDeque(bounds)
        while (pending.isNotEmpty()) {
            val spec = pending.removeFirst()
            if (!all.add(spec)) continue
            val symbol = table.lookupSpec(spec) ?: continue
            pending.addAll(symbol.parentNames)
            pending.addAll(symbol.requiredSpecs)
        }
        return all
    }

    /**
     * Whether [has] includes [spec]. A standard-library spec reaches a program
     * under its module-qualified name (`std__traits__Hash`) or its own, so the
     * two are compared by the name the spec declares.
     */
    fun includes(has: Set<String>, spec: String): Boolean {
        val bare = spec.substringAfterLast("__")
        return has.any { it == spec || it.substringAfterLast("__") == bare }
    }

    /** How [spec] is shown in a diagnostic: the name its declaration uses. */
    fun shown(spec: String): String = spec.substringAfterLast("__")

    private fun nameOf(expr: Expr): String? = when (expr) {
        is Expr.Identifier -> expr.name
        is Expr.Grouping -> nameOf(expr.expr)
        else -> null
    }
}
