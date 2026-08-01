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

import org.azora.lang.frontend.PackField
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeRef
import org.azora.lang.ir.IrType

/**
 * Produces the concrete member list of a generic pack for one set of arguments.
 *
 * A generic declaration is a *template*: `pack Vec<T, N: Int>` with
 * `inline if N >= 3 { var z … }` describes a family of layouts, one per binding of
 * `T` and `N`. This resolves a member of that family and caches it, so
 * `Vec<Int, 2>` and `Vec<Int, 3>` are different layouts rather than one erased
 * struct carrying every field.
 *
 * The template is never modified. Specialization reads it and produces a new list,
 * which is what lets the same declaration serve every instantiation.
 *
 * This step wires only field lookup. Reflection, constructors, sizing and IR
 * emission still use the template layout, so a conditional field is visible to
 * those until they are migrated in turn.
 */
internal class PackSpecializer(private val table: SymbolTable) {

    /**
     * Identifies one member of a generic pack's layout family.
     *
     * Two applications share a layout exactly when they name the same declaration
     * and the same arguments, so the rendered arguments are the identity — a
     * `TypeRef` renders structurally, and `TypeRef.Const` renders its value.
     */
    data class Key(val packName: String, val arguments: List<String>) {
        override fun toString(): String = "$packName<${arguments.joinToString(", ")}>"
    }

    /** The concrete layout of one specialization. */
    data class Specialization(val key: Key, val fields: List<PackField>)

    private val cache = mutableMapOf<Key, Specialization>()

    /** How many distinct specializations have been produced; for tests. */
    val cachedCount: Int get() = cache.size

    /** True when [key] has already been specialized. */
    fun isCached(key: Key): Boolean = key in cache

    /**
     * The key for a resolved application, or null when it is not yet concrete.
     *
     * Reads the arguments straight off the resolved type: a const parameter takes
     * its value from [IrType.Named.constArgs], a type parameter its name from the
     * corresponding argument. A const parameter with no bound value, or a type
     * argument still erased to `Any`, means no layout has been chosen — the caller
     * falls back to the template.
     */
    fun keyFor(declaration: TopLevel.Pack, applied: IrType.Named): Key? {
        if (applied.args.size != declaration.typeParams.size) return null
        val rendered = declaration.typeParams.mapIndexed { index, parameter ->
            if (parameter in declaration.constParams) {
                applied.constArgs.getOrNull(index)?.toString() ?: return null
            } else {
                val argument = applied.args[index]
                if (argument == IrType.Any) return null
                argument.toString()
            }
        }
        return Key(declaration.name, rendered)
    }

    /**
     * The key for an application written as source types, or null when abstract.
     *
     * The monomorphiser works in [TypeRef]s, the resolver in [IrType]s; both reach
     * the same cache so a layout is computed once however it was asked for.
     */
    fun keyForRefs(declaration: TopLevel.Pack, arguments: List<TypeRef>): Key? {
        if (arguments.size != declaration.typeParams.size) return null
        val rendered = declaration.typeParams.mapIndexed { index, parameter ->
            val argument = arguments[index]
            if (parameter in declaration.constParams) {
                (argument as? TypeRef.Const)?.value?.toString() ?: return null
            } else {
                if (argument is TypeRef.Named && argument.name in declaration.typeParams) return null
                argument.toString()
            }
        }
        return Key(declaration.name, rendered)
    }

    /** The concrete layout for an application written as source types. */
    fun specializeForRefs(
        key: Key,
        declaration: TopLevel.Pack,
        arguments: List<TypeRef>,
    ): Specialization = cache.getOrPut(key) {
        val bindings = buildMap {
            declaration.typeParams.forEachIndexed { index, parameter ->
                arguments.getOrNull(index)?.let { argument ->
                    ConstraintEvaluator.bindingOf(argument)?.let { put(parameter, it) }
                }
            }
        }
        Specialization(
            key,
            declaration.fields.filter { field ->
                ConstraintEvaluator.evaluate(field.condition, bindings, table) !is
                    ConstraintEvaluator.Outcome.Violated
            },
        )
    }

    /** The bindings a resolved application supplies to a condition. */
    fun bindingsFor(
        declaration: TopLevel.Pack,
        applied: IrType.Named,
    ): Map<String, ConstraintEvaluator.Binding> = buildMap {
        declaration.typeParams.forEachIndexed { index, parameter ->
            if (parameter in declaration.constParams) {
                applied.constArgs.getOrNull(index)?.let { put(parameter, ConstraintEvaluator.Binding.Const(it)) }
            } else {
                (applied.args.getOrNull(index) as? IrType.Named)?.let {
                    put(parameter, ConstraintEvaluator.Binding.Type(it.name))
                } ?: applied.args.getOrNull(index)?.let {
                    put(parameter, ConstraintEvaluator.Binding.Type(it.toString()))
                }
            }
        }
    }

    /**
     * The concrete layout for a resolved application, cached per key.
     *
     * Uses the one shared condition evaluator; there is no second implementation of
     * what a field condition means.
     */
    fun specializeFor(
        key: Key,
        declaration: TopLevel.Pack,
        applied: IrType.Named,
    ): Specialization = cache.getOrPut(key) {
        val bindings = bindingsFor(declaration, applied)
        Specialization(
            key,
            declaration.fields.filter { field ->
                ConstraintEvaluator.evaluate(field.condition, bindings, table) !is
                    ConstraintEvaluator.Outcome.Violated
            },
        )
    }

}
