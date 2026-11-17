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
 * Decides whether a declaration's `where` clause holds for one set of type
 * arguments.
 *
 * A clause is an ordinary expression - `T is Number && N in 2..4` is an `&&` over
 * an [Expr.IsCheck] and an [Expr.InCheck] - so this walks the same tree the parser
 * produced rather than a constraint language of its own. Anything the evaluator
 * cannot decide is reported as [Outcome.Unknown] and treated as satisfied by
 * callers: a constraint that cannot be evaluated must not reject valid code.
 *
 * Two rules are fixed by design:
 *  - `T is Spec` is **nominal**: it holds only where an `impl Spec for T` is
 *    declared. Structural satisfaction does not count. A type no `impl` can
 *    name - a tuple, a nullable, a pointer, a function - has the capabilities
 *    the language gives it, and those only ([structural]).
 *  - Evaluation happens once per monomorphised combination, not per instantiation
 *    site, so [bindings] name concrete arguments and never other parameters.
 */
internal object ConstraintEvaluator {

    /** `Int` or `UInt` for a width of either (`Byte`, `Long`, `ULong`, …), which states its conformances once. */
    private fun integerFamily(name: String): String? {
        if (!IrType.isPrimitiveName(name)) return null
        val type = runCatching { IrType.fromName(name) }.getOrNull() as? IrType.Integer ?: return null
        return if (type.signed) "Int" else "UInt"
    }

    /** What a clause evaluated to, and why when it failed. */
    sealed class Outcome {
        object Satisfied : Outcome()

        /** [reason] names the conjunct that failed, for the diagnostic. */
        data class Violated(val reason: String) : Outcome()

        /** The clause used something not yet evaluable; callers accept it. */
        data class Unknown(val what: String) : Outcome()
    }

    /**
     * One type argument, as bound for a monomorphised combination.
     *
     * A type parameter binds to a type name; a const parameter (`N: Int`) binds to
     * an integer.
     */
    sealed class Binding {
        /** A named type, with the arguments it was applied to (`Box<Int>` is `Box` and `[Int]`). */
        data class Type(val name: String, val args: List<TypeRef> = emptyList()) : Binding()
        data class Const(val value: Long) : Binding()

        /**
         * A type written with structure rather than a name - `(A, B)`, `T?`,
         * `T*`, `(A) -> B` - which no `impl` can name. It is decided by
         * [structural], from what it is built of.
         */
        data class Structural(val ref: TypeRef) : Binding()

        /**
         * A variadic pack's cardinality and the types present in it. Repeated
         * elements from one spread need only one entry for universal bounds.
         * A null entry denotes an unresolved element; a null list means none
         * of the elements are known. Runtime cardinality may also be unknown.
         */
        data class Pack(val length: Long?, val elementTypes: List<Binding?>? = null) : Binding()
    }

    /**
     * Evaluates [clause] under [bindings].
     *
     * Returns [Outcome.Satisfied] when there is no clause at all.
     */
    fun evaluate(
        clause: Expr?,
        bindings: Map<String, Binding>,
        table: SymbolTable?,
        /**
         * Type parameters of the code doing the asking, which a binding may
         * mention (`(U, Int)`): each with the specs its declaration implies, or
         * null when nothing is to be assumed and the question stays open.
         */
        assumed: Map<String, Set<Bounds.Bound>?> = emptyMap(),
    ): Outcome {
        if (clause == null) return Outcome.Satisfied
        return Evaluation(table, assumed).eval(clause, bindings)
    }

    /**
     * The values a compile-time range spans, or null when it is not yet decidable.
     *
     * [extra] carries loop variables bound by enclosing repetitions, so an inner
     * `0..<r` sees the `r` an outer loop is currently on.
     */
    fun rangeOf(range: Expr, bindings: Map<String, Binding>, extra: Map<String, Long>): List<Long>? {
        val env = bindings + extra.mapValues { (_, value) -> Binding.Const(value) }
        val span = rangeOf(range, env) ?: return null
        return span.toList()
    }

    /** Replaces each named value with the number it is bound to. */
    fun substituteConsts(expr: Expr, values: Map<String, Long>): Expr = when (expr) {
        is Expr.Identifier -> values[expr.name]?.let { Expr.IntLiteral(it, expr.line, expr.column) } ?: expr
        is Expr.Binary -> expr.copy(
            left = substituteConsts(expr.left, values),
            right = substituteConsts(expr.right, values),
        )
        is Expr.Unary -> expr.copy(operand = substituteConsts(expr.operand, values))
        is Expr.IncDec -> expr.copy(target = substituteConsts(expr.target, values))
        is Expr.Grouping -> expr.copy(expr = substituteConsts(expr.expr, values))
        is Expr.InCheck -> expr.copy(
            value = substituteConsts(expr.value, values),
            collection = substituteConsts(expr.collection, values),
        )
        is Expr.Range -> expr.copy(
            from = substituteConsts(expr.from, values),
            to = substituteConsts(expr.to, values),
        )
        else -> expr
    }

    /** Converts a [TypeRef] argument into the binding it stands for. */
    fun bindingOf(arg: TypeRef): Binding? = when (arg) {
        is TypeRef.Const -> Binding.Const(arg.value)
        // `Array<T>` is a container of `T`s, decided by what it holds.
        is TypeRef.Named -> if (arg.name == ARRAY && arg.args.isNotEmpty()) Binding.Structural(TypeRef.Array(arg.args[0]))
            else Binding.Type(arg.name, arg.args)
        // A borrow is of the type it borrows.
        is TypeRef.Reference -> bindingOf(arg.inner)
        else -> Binding.Structural(arg)
    }

    private const val ARRAY = "Array"
    private const val TUPLE = "Tuple"

    /** Names the language provides as types without a declaration a table would hold. */
    private val BUILTIN_TYPES = setOf("Any", "Self", "Array", "Tuple", "Task", "Map", "Set", "Nothing", "Unit")

    /** Equality and hashing: a compound value has them when its parts do. */
    private val partWise = setOf("Equal", "PartialEqual", "Hash")

    /** Bind fixed parameters individually and the variadic tail by its own size. */
    fun bindingsFor(
        params: List<String>,
        variadicParam: String?,
        args: List<TypeRef>,
    ): Map<String, Binding> = buildMap {
        for ((index, param) in params.withIndex()) {
            if (param == variadicParam) {
                val tail = args.drop(index)
                put(param, Binding.Pack(tail.size.toLong(), tail.map(::bindingOf)))
            } else {
                args.getOrNull(index)?.let { bindingOf(it) }?.let { put(param, it) }
            }
        }
    }

    /** One evaluation: the symbol table and what the asking code's type parameters are known to be. */
    private class Evaluation(val table: SymbolTable?, val assumed: Map<String, Set<Bounds.Bound>?>) {

    fun eval(expr: Expr, env: Map<String, Binding>): Outcome = when (expr) {
        is Expr.Grouping -> eval(expr.expr, env)

        // `T is Number` - nominal conformance: an `impl Number for T` must exist.
        is Expr.IsCheck -> {
            val subject = nameOf(expr.expr)
            val bound = subject?.let { env[it] }
            // `T: Conv<U>` asks for `Conv` with the arguments as this combination
            // binds them; one naming a parameter left open stays open.
            val wanted = Bounds.Bound(expr.typeName, expr.typeArgs.map { substitute(it, env) })
            when {
                bound == null -> Outcome.Unknown("'${subject ?: "expression"} is ${expr.typeName}'")
                bound is Binding.Pack -> {
                    val elements = bound.elementTypes
                    if (elements == null) Outcome.Unknown("'$subject is ${expr.typeName}' for an unresolved pack")
                    else {
                        val outcomes = elements.map { conformance(subject, it, wanted) }
                        outcomes.firstOrNull { it is Outcome.Violated }
                            ?: outcomes.firstOrNull { it is Outcome.Unknown }
                            ?: Outcome.Satisfied // The empty pack satisfies every element-wise bound.
                    }
                }
                else -> conformance(subject, bound, wanted)
            }
        }

        // `N in 2..4` - membership. Only an integer subject over a literal range is
        // decidable here; a list or set collection is left to the caller.
        // TODO: evaluate membership in array/set literal collections.
        is Expr.InCheck -> {
            val subject = constOf(expr.value, env)
            val range = rangeOf(expr.collection, env)
            when {
                subject == null || range == null -> Outcome.Unknown("membership constraint")
                (!range.isEmpty() && subject >= minOf(range.first, range.last) && subject <= maxOf(range.first, range.last)) != expr.negated -> Outcome.Satisfied
                else -> Outcome.Violated(
                    "'${render(expr.value)} in ${range.first}..${range.last}': " +
                        "${render(expr.value)} is $subject",
                )
            }
        }

        is Expr.Unary -> when (expr.op) {
            TokenType.BANG -> when (val inner = eval(expr.operand, env)) {
                is Outcome.Satisfied -> Outcome.Violated("negated constraint held")
                is Outcome.Violated -> Outcome.Satisfied
                is Outcome.Unknown -> inner
            }
            // TODO: other unary operators in constraint position.
            else -> Outcome.Unknown("unary ${expr.op}")
        }

        is Expr.Binary -> when (expr.op) {
            TokenType.AND_AND -> both(expr, env, requireAll = true)
            TokenType.OR_OR -> both(expr, env, requireAll = false)
            else -> compare(expr, env)
        }

        // TODO: calls, member access other than the variadic `.size` reading, and
        // any other expression a clause might legitimately contain.
        else -> Outcome.Unknown(render(expr))
    }

    private fun conformance(subject: String?, bound: Binding?, wanted: Bounds.Bound): Outcome = when (bound) {
        is Binding.Structural -> when (val outcome = structuralWith(bound.ref, wanted)) {
            is Outcome.Violated -> Outcome.Violated("'$subject is ${Bounds.shown(wanted)}': ${outcome.reason}")
            else -> outcome
        }
        is Binding.Type -> when (val outcome = named(bound.name, wanted, bound.args)) {
            is Outcome.Violated -> Outcome.Violated("'$subject is ${Bounds.shown(wanted)}': ${outcome.reason}")
            else -> outcome
        }
        else -> Outcome.Unknown("'$subject is ${wanted.spec}' for an unresolved type binding")
    }

    /** [ref] with each parameter [env] binds to a type replaced by that type. */
    private fun substitute(ref: TypeRef, env: Map<String, Binding>): TypeRef {
        if (env.isEmpty()) return ref
        val types = env.mapNotNull { (param, binding) ->
            when (binding) {
                is Binding.Type -> param to TypeRef.Named(binding.name, binding.args)
                is Binding.Structural -> param to binding.ref
                is Binding.Const -> param to TypeRef.Const(binding.value)
                is Binding.Pack -> null
            }
        }.toMap()
        return substituteMemberType(ref, types)
    }

    /** Whether the type called [name] is [spec]; the reason names the type when it is not. */
    private fun named(name: String, spec: String): Outcome = named(name, Bounds.Bound(spec), emptyList())

    /**
     * Whether the type called [name], applied to [typeArgs], is [wanted]. A
     * spec wanted with arguments is satisfied only by an `impl` of it with the
     * same arguments, read in the type's own (`impl Indexed<T> for
     * ArrayList<T>` makes an `ArrayList<Int>` an `Indexed<Int>`).
     */
    private fun named(name: String, written: Bounds.Bound, typeArgs: List<TypeRef>): Outcome {
        val spec = written.spec
        // An erased slot: which type it holds is decided where it was filled.
        if (name == "Any") return Outcome.Unknown("'$name is $spec' for an erased value")
        // `PartialEqual` asked of `P` is `PartialEqual<P>`, by its `Rhs = Self`.
        val self = TypeRef.Named(name, typeArgs)
        val wanted = Bounds.withDefaults(written, self, table)
        if (name in assumed) {
            val has = assumed[name] ?: return Outcome.Unknown("'$name is $spec' for a type parameter")
            if (wanted.args.isNotEmpty() && wanted.args.any(::mentionsOpen)) {
                return if (has.any { Bounds.sameSpec(it.spec, spec) }) Outcome.Unknown("'$name is ${Bounds.shown(wanted)}'")
                else Outcome.Violated("'$name' is not declared to be ${Bounds.shown(spec)}; add 'where $name: ${Bounds.shown(wanted)}'")
            }
            return if (Bounds.includes(has, wanted, table)) Outcome.Satisfied
            else Outcome.Violated("'$name' is not declared to be ${Bounds.shown(wanted)}; add 'where $name: ${Bounds.shown(wanted)}'")
        }
        val table = table ?: return Outcome.Unknown("'$name is $spec' without a symbol table")
        // `Long` is `Int<64>`: a width answers with its family's conformances -
        // those the family states for every width. A spec implemented width by
        // width (`Step`, whose `Cent` has none) answers for each width alone.
        val family = integerFamily(name)
        val owner = when {
            table.conformsTo(name, spec) -> name
            family != null && table.conformsTo(family, spec) && !perWidth(family, spec, table) -> family
            else -> return Outcome.Violated("$name does not implement ${Bounds.shown(spec)}")
        }
        if (wanted.args.isEmpty()) return Outcome.Satisfied
        if (wanted.args.any(::mentionsOpen)) return Outcome.Unknown("'$name is ${Bounds.shown(wanted)}'")
        val implemented = table.allConformances().filter { it.typeName == owner && Bounds.sameSpec(it.contractName, spec) }
        var open = false
        for (conformance in implemented) {
            val args = implementedArgs(conformance, self, typeArgs, table)
            when (Bounds.sameArgs(args.orEmpty(), wanted.args, table)) {
                true -> return Outcome.Satisfied
                null -> open = true
                false -> if (args!!.any(::mentionsOpen)) open = true
            }
        }
        if (open) return Outcome.Unknown("'$name is ${Bounds.shown(wanted)}'")
        val has = implemented.mapNotNull { implementedArgs(it, self, typeArgs, table) }
            .joinToString(" and ") { "${Bounds.shown(spec)}<${it.joinToString(", ")}>" }
        return Outcome.Violated("$name implements $has, not ${Bounds.shown(wanted)}")
    }

    /** Whether [spec] is implemented for some width of [family] under the width's own name. */
    private fun perWidth(family: String, spec: String, table: SymbolTable): Boolean =
        table.allConformances().any {
            it.typeName != family && Bounds.sameSpec(it.contractName, spec) && integerFamily(it.typeName) == family
        }

    /**
     * The arguments [conformance] implements its spec with, for the type
     * [self] (applied to [typeArgs]): the impl's own spelled in the type's,
     * or the spec's defaults (`Rhs = Self`) when it wrote none. Null when they
     * cannot be read off the type - an impl over parameters the type was not
     * applied to here.
     */
    private fun implementedArgs(
        conformance: TraitConformance,
        self: TypeRef.Named,
        typeArgs: List<TypeRef>,
        table: SymbolTable,
    ): List<TypeRef>? {
        val params = conformance.implTypeParams
        val bindings = HashMap<String, TypeRef>()
        bindings["Self"] = self
        if (params.isNotEmpty() && conformance.typeArgs.isNotEmpty()) {
            if (params.size != typeArgs.size) return null
            params.zip(typeArgs).forEach { (param, arg) -> bindings[param] = arg }
        }
        val written = Bounds.Bound(conformance.contractName, conformance.typeArgs.map { substituteMemberType(it, bindings) })
        return Bounds.withDefaults(written, self, table).args.ifEmpty { null }
    }

    /**
     * Whether [ref] names something this evaluation cannot identify: a
     * parameter of the declaration no binding fills. A type parameter of the
     * asking code is known (by name), and so is every declared type.
     */
    private fun mentionsOpen(ref: TypeRef): Boolean = when (ref) {
        is TypeRef.Named -> (ref.args.isEmpty() && !isKnownTypeName(ref.name)) || ref.args.any(::mentionsOpen)
        is TypeRef.Reference -> mentionsOpen(ref.inner)
        is TypeRef.Nullable -> mentionsOpen(ref.inner)
        is TypeRef.Pointer -> mentionsOpen(ref.inner)
        is TypeRef.Array -> mentionsOpen(ref.element)
        is TypeRef.Tuple -> ref.elements.any(::mentionsOpen)
        is TypeRef.Function -> ref.params.any(::mentionsOpen) || mentionsOpen(ref.ret)
        else -> false
    }

    private fun isKnownTypeName(name: String): Boolean {
        if (name in assumed || IrType.isPrimitiveName(name) || name in BUILTIN_TYPES) return true
        val table = table ?: return false
        return table.lookupStruct(name) != null || table.lookupEnum(name) != null ||
            table.lookupSlot(name) != null || table.lookupSpec(name) != null ||
            table.lookupFail(name) != null || table.lookupAlias(name) != null
    }

    /** A structured type against a spec wanted with arguments: what the language gives it, it has with itself only. */
    private fun structuralWith(ref: TypeRef, wanted: Bounds.Bound): Outcome {
        if (wanted.args.isNotEmpty()) {
            if (wanted.args.any(::mentionsOpen)) return Outcome.Unknown("'$ref is ${Bounds.shown(wanted)}'")
            val itself = Bounds.canonical(ref, table)
            if (wanted.args.any { Bounds.canonical(it, table) != itself }) {
                return Outcome.Violated("${shown(ref)} has ${Bounds.shown(wanted.spec)} only with itself")
            }
        }
        return structural(ref, wanted.spec)
    }

    /**
     * Whether a type built with structure is [spec]. No `impl` can name one, so
     * its capabilities are the ones the language gives it - the same rule the
     * compiler derives a pack's `Clone` and `Copy` by:
     *
     *  - **equality and hashing** (`Equal`, `PartialEqual`, `Hash`): a tuple
     *    or an array when every element has them, a nullable when its value
     *    does, a pointer always (by address);
     *  - **`Clone`**: what [SymbolTable.isClonable] says - parts that clone;
     *  - **`Copy`**: a nullable when its value is `Copy`; a tuple, array,
     *    pointer or function never, as for a pack field of that type;
     *  - **ordering** (`Order`, `PartialOrder`): none - `<` does not apply to
     *    a tuple or an array, and a nullable has no place for `null` in an order;
     *  - anything else: a tuple has what `Tuple` declares (`PrettyPrint`), an
     *    array what `Array` does; nothing else has any.
     */
    private fun structural(ref: TypeRef, spec: String): Outcome {
        val bare = Bounds.shown(spec)
        fun not(): Outcome = Outcome.Violated("${shown(ref)} does not implement $bare")
        fun parts(types: List<TypeRef>): Outcome {
            val outcomes = types.map { part ->
                when (val binding = bindingOf(part)) {
                    is Binding.Structural -> structural(binding.ref, spec)
                    is Binding.Type -> named(binding.name, spec)
                    else -> Outcome.Unknown("'$part is $bare'")
                }
            }
            return outcomes.firstOrNull { it is Outcome.Violated }
                ?.let { Outcome.Violated("${shown(ref)} does not implement $bare: ${(it as Outcome.Violated).reason}") }
                ?: outcomes.firstOrNull { it is Outcome.Unknown }
                ?: Outcome.Satisfied
        }
        if (bare == "Clone") {
            val table = table ?: return Outcome.Unknown("'$ref is Clone' without a symbol table")
            // A part naming a type parameter of the asking code answers for itself.
            if (mentionsAssumed(ref)) return clonableParts(ref)
            val type = runCatching { IrType.resolve(ref) }.getOrNull() ?: return Outcome.Unknown("'$ref is Clone'")
            return if (table.isClonable(type)) Outcome.Satisfied else not()
        }
        return when (ref) {
            is TypeRef.Reference -> conformanceOf(ref.inner, spec)
            is TypeRef.Named -> conformanceOf(ref, spec)
            is TypeRef.Array -> when {
                bare in partWise -> parts(listOf(ref.element))
                bare == "Copy" || bare == "Order" || bare == "PartialOrder" -> not()
                else -> if (named(ARRAY, spec) is Outcome.Satisfied) Outcome.Satisfied else not()
            }
            is TypeRef.Nullable -> when {
                bare in partWise || bare == "Copy" -> parts(listOf(ref.inner))
                else -> not()
            }
            is TypeRef.Tuple -> when {
                bare in partWise -> parts(ref.elements)
                bare == "Copy" -> not()
                bare == "Order" || bare == "PartialOrder" -> not()
                else -> if (named(TUPLE, spec) is Outcome.Satisfied) Outcome.Satisfied else not()
            }
            is TypeRef.Pointer -> if (bare in partWise) Outcome.Satisfied else not()
            is TypeRef.Const -> Outcome.Unknown("'$ref is $bare'")
            else -> not()
        }
    }

    /** [ref] as source writes it: `Array<P>` rather than the internal `[P]`. */
    private fun shown(ref: TypeRef): String = when (ref) {
        is TypeRef.Array -> "Array<${shown(ref.element)}>"
        is TypeRef.Tuple -> ref.elements.joinToString(", ", "(", ")") { shown(it) }
        is TypeRef.Nullable -> "${shown(ref.inner)}?"
        is TypeRef.Reference -> shown(ref.inner)
        else -> ref.toString()
    }

    private fun conformanceOf(ref: TypeRef, spec: String): Outcome = when (val binding = bindingOf(ref)) {
        is Binding.Structural -> structural(binding.ref, spec)
        is Binding.Type -> named(binding.name, spec)
        else -> Outcome.Unknown("'$ref is $spec'")
    }

    /** `Clone` part by part, for a compound type that names a type parameter of the asking code. */
    private fun clonableParts(ref: TypeRef): Outcome = when (ref) {
        is TypeRef.Named -> if (ref.args.isEmpty() && ref.name in assumed) named(ref.name, "Clone") else conformanceOf(ref, "Clone")
        is TypeRef.Reference -> clonableParts(ref.inner)
        is TypeRef.Nullable -> clonableParts(ref.inner)
        is TypeRef.Array -> clonableParts(ref.element)
        is TypeRef.Tuple -> ref.elements.map(::clonableParts).let { outcomes ->
            outcomes.firstOrNull { it is Outcome.Violated } ?: outcomes.firstOrNull { it is Outcome.Unknown } ?: Outcome.Satisfied
        }
        else -> Outcome.Satisfied
    }

    private fun mentionsAssumed(ref: TypeRef): Boolean = when (ref) {
        is TypeRef.Named -> (ref.args.isEmpty() && ref.name in assumed) || ref.args.any(::mentionsAssumed)
        is TypeRef.Reference -> mentionsAssumed(ref.inner)
        is TypeRef.Nullable -> mentionsAssumed(ref.inner)
        is TypeRef.Array -> mentionsAssumed(ref.element)
        is TypeRef.Tuple -> ref.elements.any(::mentionsAssumed)
        is TypeRef.Pointer -> mentionsAssumed(ref.inner)
        else -> false
    }

    /** `&&` needs both sides; `||` needs either. Unknown on one side is not fatal. */
    private fun both(
        expr: Expr.Binary,
        env: Map<String, Binding>,
        requireAll: Boolean,
    ): Outcome {
        val left = eval(expr.left, env)
        val right = eval(expr.right, env)
        val sides = listOf(left, right)
        return if (requireAll) {
            sides.firstOrNull { it is Outcome.Violated }
                ?: sides.firstOrNull { it is Outcome.Unknown }
                ?: Outcome.Satisfied
        } else {
            when {
                sides.any { it is Outcome.Satisfied } -> Outcome.Satisfied
                sides.any { it is Outcome.Unknown } -> sides.first { it is Outcome.Unknown }
                else -> Outcome.Violated(
                    sides.filterIsInstance<Outcome.Violated>().joinToString(" and ") { it.reason },
                )
            }
        }
    }

    }

    /** A comparison between two integers, e.g. `N == 4` or `(...T).size >= 2`. */
    private fun compare(expr: Expr.Binary, env: Map<String, Binding>): Outcome {
        val left = constOf(expr.left, env) ?: return Outcome.Unknown(render(expr))
        val right = constOf(expr.right, env) ?: return Outcome.Unknown(render(expr))
        val holds = when (expr.op) {
            TokenType.EQUAL_EQUAL -> left == right
            TokenType.BANG_EQUAL -> left != right
            TokenType.LESS -> left < right
            TokenType.LESS_EQUAL -> left <= right
            TokenType.GREATER -> left > right
            TokenType.GREATER_EQUAL -> left >= right
            else -> return Outcome.Unknown("comparison ${expr.op}")
        }
        return if (holds) {
            Outcome.Satisfied
        } else {
            Outcome.Violated("'${render(expr.left)} ${spell(expr.op)} ${render(expr.right)}': $left vs $right")
        }
    }

    /** The integer an expression denotes, or null when it is not a known constant. */
    private fun constOf(expr: Expr, env: Map<String, Binding>): Long? = when (expr) {
        is Expr.IntLiteral -> expr.value.toString().toLongOrNull()
        is Expr.Grouping -> constOf(expr.expr, env)
        is Expr.Identifier -> (env[expr.name] as? Binding.Const)?.value
        // `(...T).size` - the pack's argument count, bound per combination.
        // Keep `.length` for existing source that used the older spelling.
        is Expr.Member ->
            if (expr.name == "size" || expr.name == "length") {
                (nameOf(expr.target)?.let { env[it] } as? Binding.Pack)?.length
            } else {
                null
            }
        else -> null
    }

    /** The inclusive bounds of a literal range expression, or null. */
    private fun rangeOf(expr: Expr, env: Map<String, Binding>): LongProgression? {
        val range = expr as? Expr.Range ?: return null
        val from = constOf(range.from, env) ?: return null
        val to = constOf(range.to, env) ?: return null
        return range.constantProgression(from, to)
    }

    /** The identifier an expression names, seeing through `(...)` grouping. */
    private fun nameOf(expr: Expr): String? = when (expr) {
        is Expr.Identifier -> expr.name
        is Expr.Grouping -> nameOf(expr.expr)
        else -> null
    }

    private fun spell(op: TokenType): String = when (op) {
        TokenType.EQUAL_EQUAL -> "=="
        TokenType.BANG_EQUAL -> "!="
        TokenType.LESS -> "<"
        TokenType.LESS_EQUAL -> "<="
        TokenType.GREATER -> ">"
        TokenType.GREATER_EQUAL -> ">="
        else -> op.toString()
    }

    /** A short rendering of a constraint fragment, for diagnostics only. */
    private fun render(expr: Expr): String = when (expr) {
        is Expr.Identifier -> expr.name
        is Expr.IntLiteral -> expr.value.toString()
        is Expr.Grouping -> render(expr.expr)
        is Expr.Member -> "${render(expr.target)}.${expr.name}"
        else -> "constraint"
    }
}
