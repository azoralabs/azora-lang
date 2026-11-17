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
import org.azora.lang.frontend.TypeRef
import org.azora.lang.ir.IrType

/**
 * What a declaration's bounds say about one of its type parameters.
 *
 * Shared by every place that asks whether generic code may put one of its own
 * type parameters where a bound is required: written types, constructions,
 * calls, and an implementation against the spec member it implements.
 */
internal object Bounds {

    /**
     * One spec a bound requires: `Conv<Int>` in `where T: Conv<Int>`.
     *
     * [args] are the spec's arguments as written, empty for a spec written
     * bare (`T: Hash`) - which asks only that `T` implement the spec, with
     * whatever arguments. `Conv<String>` does not satisfy `Conv<Int>`.
     */
    data class Bound(val spec: String, val args: List<TypeRef> = emptyList()) {
        override fun toString(): String = shown(this)
    }

    /**
     * The specs [clause] requires of [param] outright: its `param is Spec`
     * conjuncts. A requirement under `||` or `!` binds no single spec, so it is
     * left to the concrete type that finally stands for the parameter.
     */
    fun required(clause: Expr?, param: String): Set<Bound> {
        if (clause == null) return emptySet()
        val found = LinkedHashSet<Bound>()
        fun visit(expr: Expr) {
            when (expr) {
                is Expr.Grouping -> visit(expr.expr)
                is Expr.Binary -> if (expr.op == TokenType.AND_AND) {
                    visit(expr.left)
                    visit(expr.right)
                }
                is Expr.IsCheck -> if (nameOf(expr.expr) == param) found.add(Bound(expr.typeName, expr.typeArgs))
                else -> {}
            }
        }
        visit(clause)
        return found
    }

    /** Each of [typeParams] with what [clause] requires of it. */
    fun declared(typeParams: Collection<String>, clause: Expr?): Map<String, Set<Bound>> =
        typeParams.associateWith { required(clause, it) }

    /**
     * [bounds], declared of [subject], and everything conforming to them
     * entails: each spec's parents and the specs it requires. `U: Order` may
     * stand where `Equal` is asked, and `U: MutableList<Int>` where `List<Int>`
     * is - a parent receives the arguments its child passes it. A spec written
     * bare whose parameters all have defaults means those defaults
     * (`U: PartialEqual` is `PartialEqual<U>`).
     */
    fun implied(bounds: Set<Bound>, table: SymbolTable, subject: String? = null): Set<Bound> {
        val all = LinkedHashSet<Bound>()
        val pending = ArrayDeque(bounds.map { withDefaults(it, subject, table) })
        while (pending.isNotEmpty()) {
            val bound = pending.removeFirst()
            if (!all.add(bound)) continue
            val symbol = table.lookupSpec(bound.spec) ?: continue
            val passed = if (bound.args.size == symbol.typeParams.size) symbol.typeParams.zip(bound.args).toMap() else null
            for ((index, parentName) in symbol.parentNames.withIndex()) {
                val written = symbol.parents.getOrNull(index) as? TypeRef.Named
                val args = when {
                    written == null || written.args.isEmpty() -> emptyList()
                    // What the child passes its parent, in the child's arguments;
                    // a child written bare passes arguments nothing names.
                    passed == null -> emptyList()
                    else -> written.args.map { substituteMemberType(it, passed) }
                }
                pending.add(withDefaults(Bound(parentName, args), subject, table))
            }
            symbol.requiredSpecs.forEach { pending.add(withDefaults(Bound(it), subject, table)) }
        }
        return all
    }

    /**
     * [bound] with the arguments it leaves unwritten, when each has a default:
     * `PartialEqual` declared of `U` is `PartialEqual<U>`, by `Rhs = Self`, and
     * `Index<T>` is `Index<T, U>` by `Out = Self` (OPERATOR_OVERLOADING_DIP
     * §7.4). A spec with an unwritten parameter that has no default stays as
     * written - which argument it has there is not known.
     */
    fun withDefaults(bound: Bound, subject: String?, table: SymbolTable?): Bound =
        withDefaults(bound, subject?.let { TypeRef.Named(it) }, table)

    fun withDefaults(bound: Bound, subject: TypeRef?, table: SymbolTable?): Bound {
        if (subject == null || table == null) return bound
        val symbol = table.lookupSpec(bound.spec) ?: return bound
        if (bound.args.size >= symbol.typeParams.size) return bound
        val missing = symbol.typeParams.drop(bound.args.size)
        if (missing.any { it !in symbol.typeDefaults }) return bound
        val bindings = HashMap<String, TypeRef>()
        bindings["Self"] = subject
        symbol.typeParams.zip(bound.args).forEach { (param, arg) -> bindings[param] = arg }
        val filled = missing.map { param ->
            substituteMemberType(symbol.typeDefaults.getValue(param), bindings).also { bindings[param] = it }
        }
        return bound.copy(args = bound.args + filled)
    }

    /** [bound] with the parameters [bindings] names replaced: `Conv<T>` asked of `twice<T>` with `T` as `U`. */
    fun substituted(bound: Bound, bindings: Map<String, TypeRef>): Bound =
        if (bound.args.isEmpty() || bindings.isEmpty()) bound
        else bound.copy(args = bound.args.map { substituteMemberType(it, bindings) })

    /**
     * Whether [has] includes [wanted]. A standard-library spec reaches a program
     * under its module-qualified name (`std__traits__Hash`) or its own, so the
     * two are compared by the name the spec declares. A spec wanted bare is
     * satisfied by any instantiation of it; one wanted with arguments only by
     * a declared bound with the same arguments.
     */
    fun includes(has: Set<Bound>, wanted: Bound, table: SymbolTable? = null): Boolean =
        has.any { sameSpec(it.spec, wanted.spec) && (wanted.args.isEmpty() || sameArgs(it.args, wanted.args, table) == true) }

    /** Whether two spec names denote the same spec, qualified or not. */
    fun sameSpec(a: String, b: String): Boolean = a == b || a.substringAfterLast("__") == b.substringAfterLast("__")

    /**
     * Whether two argument lists name the same types: true, false, or null
     * when a side has no arguments to compare. Names are compared as declared
     * (`std__core__Text` is `Text`), aliases by what they stand for, a borrow
     * by the type it borrows.
     */
    fun sameArgs(a: List<TypeRef>, b: List<TypeRef>, table: SymbolTable?): Boolean? {
        // A list missing an argument with no default says nothing about it.
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return null
        return a.zip(b).all { (x, y) -> canonical(x, table) == canonical(y, table) }
    }

    /** [ref] in the one spelling every equivalent spelling of it shares. */
    fun canonical(ref: TypeRef, table: SymbolTable?): TypeRef = when (ref) {
        is TypeRef.Named -> {
            val alias = if (ref.args.isEmpty()) table?.lookupAlias(ref.name) else null
            when {
                alias != null && alias != ref -> canonical(alias, table)
                ref.args.isEmpty() && IrType.isPrimitiveName(ref.name) ->
                    TypeRef.Named(runCatching { IrType.fromName(ref.name).toString() }.getOrDefault(ref.name))
                // `Array<T>` and `[T]` are one type.
                ref.name == "Array" && ref.args.size == 1 -> TypeRef.Array(canonical(ref.args[0], table))
                else -> TypeRef.Named(ref.name.substringAfterLast("__"), ref.args.map { canonical(it, table) }, ref.variadic)
            }
        }
        is TypeRef.Reference -> canonical(ref.inner, table)
        is TypeRef.Nullable -> TypeRef.Nullable(canonical(ref.inner, table))
        is TypeRef.Pointer -> ref.copy(inner = canonical(ref.inner, table))
        is TypeRef.Array -> TypeRef.Array(canonical(ref.element, table))
        is TypeRef.Tuple -> TypeRef.Tuple(ref.elements.map { canonical(it, table) })
        is TypeRef.Function -> ref.copy(
            params = ref.params.map { canonical(it, table) },
            ret = canonical(ref.ret, table),
            receivers = ref.receivers.map { canonical(it, table) },
        )
        else -> ref
    }

    /** How [spec] is shown in a diagnostic: the name its declaration uses. */
    fun shown(spec: String): String = spec.substringAfterLast("__")

    /** How [bound] is shown in a diagnostic: `Conv<Int>`, or `Hash`. */
    fun shown(bound: Bound): String =
        if (bound.args.isEmpty()) shown(bound.spec) else "${shown(bound.spec)}<${bound.args.joinToString(", ")}>"

    private fun nameOf(expr: Expr): String? = when (expr) {
        is Expr.Identifier -> expr.name
        is Expr.Grouping -> nameOf(expr.expr)
        else -> null
    }
}
