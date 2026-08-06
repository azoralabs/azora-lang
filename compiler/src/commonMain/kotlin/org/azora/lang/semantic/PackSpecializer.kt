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
     * and the same arguments, so the rendered arguments are the identity - a
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
     * argument still erased to `Any`, means no layout has been chosen - the caller
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
     * An argument list with omitted const parameters filled from their defaults.
     *
     * `Mat<Double, 4, 4>` and `Mat<Double, 4, 4, .RowMajor>` name the same layout, so a
     * default is applied here rather than at every place that inspects arguments.
     */
    fun withDefaults(declaration: TopLevel.Pack, arguments: List<TypeRef>): List<TypeRef> {
        if (arguments.size >= declaration.typeParams.size) return arguments
        if (declaration.constDefaults.isEmpty()) return arguments
        val completed = arguments.toMutableList()
        for (index in arguments.size until declaration.typeParams.size) {
            completed.add(declaration.constDefaults[declaration.typeParams[index]] ?: return arguments)
        }
        return completed
    }

    /**
     * The key for an application written as source types, or null when abstract.
     *
     * The monomorphiser works in [TypeRef]s, the resolver in [IrType]s; both reach
     * the same cache so a layout is computed once however it was asked for.
     */
    fun keyForRefs(declaration: TopLevel.Pack, supplied: List<TypeRef>): Key? {
        val arguments = withDefaults(declaration, supplied)
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
        supplied: List<TypeRef>,
    ): Specialization = cache.getOrPut(key) {
        val arguments = withDefaults(declaration, supplied)
        val bindings = buildMap {
            declaration.typeParams.forEachIndexed { index, parameter ->
                arguments.getOrNull(index)?.let { argument ->
                    ConstraintEvaluator.bindingOf(argument)?.let { put(parameter, it) }
                }
            }
        }
        Specialization(key, concreteFields(declaration, bindings))
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
        Specialization(key, concreteFields(declaration, bindings))
    }

    /**
     * The layout one set of bindings selects: repetitions unrolled, conditions applied.
     *
     * Order matters - a repeated field is a family, and the family has to exist before
     * anything can ask whether a member of it is present.
     */
    private fun concreteFields(
        declaration: TopLevel.Pack,
        bindings: Map<String, ConstraintEvaluator.Binding>,
    ): List<PackField> = declaration.fields
        .flatMap { unroll(it, bindings) }
        .filter {
            ConstraintEvaluator.evaluate(it.condition, bindings, table) !is
                ConstraintEvaluator.Outcome.Violated
        }

    /**
     * One field template expanded over its compile-time loops.
     *
     * `inline for r in 0..<R { inline for c in 0..<C { var m$r$c: T = 0 } }` becomes
     * R×C fields named `m00`, `m01`, … Each loop value is substituted into the name
     * and into everything the field's declaration mentions, so an inner loop bound by
     * an outer variable works like any nested loop. A range that is not yet a known
     * span leaves the template alone, for the same reason an unresolved condition
     * does not reject: nothing has been chosen yet.
     */
    private fun unroll(
        field: PackField,
        bindings: Map<String, ConstraintEvaluator.Binding>,
    ): List<PackField> {
        if (field.repeats.isEmpty()) return listOf(field)
        var expanded = listOf(field.copy(repeats = emptyList()) to emptyMap<String, Long>())
        for (repeat in field.repeats) {
            val next = mutableListOf<Pair<PackField, Map<String, Long>>>()
            for ((current, values) in expanded) {
                val span = ConstraintEvaluator.rangeOf(repeat.range, bindings, values)
                    ?: return listOf(field)
                for (value in span) {
                    next.add(current to (values + (repeat.variable to value)))
                }
            }
            expanded = next
        }
        return expanded.map { (template, values) -> substitute(template, values) }
    }

    /** Renders a field template for one combination of loop values. */
    private fun substitute(field: PackField, values: Map<String, Long>): PackField {
        var name = field.name
        for ((variable, value) in values) {
            name = name.replace("$" + variable, value.toString())
        }
        return field.copy(
            name = name,
            condition = field.condition?.let { ConstraintEvaluator.substituteConsts(it, values) },
        )
    }

}
