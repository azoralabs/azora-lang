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

import org.azora.lang.frontend.AstMapper
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef
import org.azora.lang.ir.IrType

/**
 * Checks every written generic type, and every call that writes its type
 * arguments, against the bounds its declaration states.
 *
 * A bound is one requirement however it is spelled - `pack Box<T: Shape>` or
 * `pack Box<T> where T: Shape` - and it holds wherever the type is named: a
 * parameter, a field, a local, a cast, a type argument, a construction.
 *
 * Two things can stand where a type parameter is expected:
 *  - a **type** - `Box<Int>`, `Box<(Sq, Sq)>` - decided by
 *    [ConstraintEvaluator] in every position the type is written;
 *  - a **type parameter of the code doing the naming**, or a type built from
 *    one (`Box<U>`, `HashSet<(U, Int)>`), which is only as good as its own
 *    declared bounds. Generic code that *builds* such a value, or calls a
 *    bounded function with such a type argument, has to say `where U: Shape`
 *    itself; without that a bound is checked once, at a boundary generic code
 *    can simply step around. Merely naming `Box<U>` - a parameter's or a
 *    field's type - asks nothing: the value it will hold was built under the
 *    rule.
 *
 * What a use infers rather than writes - `Box(value)`, `twice(x)`, a member's
 * own type parameters - is decided by `TypeResolver`, which knows the
 * argument types and what each one was declared as.
 */
internal class BoundChecker private constructor(
    private val table: SymbolTable,
    private val declared: Map<String, Bounded>,
    private val functions: Map<String, FuncDecl>,
    /** Every type the program declares by name, aliases included. */
    private val typeNames: Set<String>,
) : AstMapper() {

    /** A generic declaration that states bounds on its own parameters. */
    private class Bounded(val typeParams: List<String>, val clause: Expr)

    private val errors = LinkedHashSet<String>()

    /** Applications already reported. */
    private val violated = HashSet<String>()

    /** Type parameters in scope → the specs each is declared to conform to. */
    private var scope: Map<String, Set<String>> = emptyMap()

    /** The line of the statement being walked, for a type that carries none. */
    private var line = 0

    // ── declarations ──────────────────────────────────────────────

    private fun item(item: TopLevel) {
        line = lineOf(item)
        when (item) {
            // A member written without a receiver is lifted out of its `impl`
            // as `Type__member`; it still names the type's parameters.
            is TopLevel.Func -> within(staticOwner(item.decl.name)?.let { ownerScope(it) }.orEmpty()) {
                function(item.decl)
            }
            is TopLevel.Impl -> within(implScope(item)) {
                // `impl Holder<Int> for Box` names the type `Holder<Int>`.
                item.traitName?.takeIf { item.traitArgs.isNotEmpty() }
                    ?.let { application(it, item.traitArgs, item.line, builds = false) }
                item.traitArgs.forEach { typeRef(it) }
                item.methods.forEach { function(it) }
            }
            is TopLevel.Pack -> within(declaredScope(item.typeParams, item.whereClause)) {
                for (field in item.fields) {
                    typeRef(field.type)
                    field.default?.let { initialized(field.type, it) }
                }
            }
            is TopLevel.Slot -> within(declaredScope(item.typeParams, item.whereClause)) {
                item.variants.forEach { variant -> variant.payloadTypes.forEach { typeRef(it) } }
            }
            is TopLevel.Fail -> within(declaredScope(item.typeParams, item.whereClause)) {
                item.variantPayloads.forEach { payload -> payload.forEach { typeRef(it) } }
            }
            is TopLevel.Spec -> within(declaredScope(item.typeParams + item.assocNames, item.whereClause)) {
                item.parents.forEach { typeRef(it) }
                item.requires.forEach { typeRef(it) }
                item.methods.forEach { function(it) }
            }
            is TopLevel.TypeAlias -> within(declaredScope(item.typeParams, item.whereClause)) {
                typeRef(item.type)
            }
            is TopLevel.FinDecl -> { item.type?.let { typeRef(it) }; initialized(item.type, item.initializer) }
            is TopLevel.VarDecl -> { item.type?.let { typeRef(it) }; initialized(item.type, item.initializer) }
            is TopLevel.LetDecl -> { item.type?.let { typeRef(it) }; initialized(item.type, item.initializer) }
            is TopLevel.Test -> stmts(item.body)
            else -> {}
        }
    }

    private fun lineOf(item: TopLevel): Int = when (item) {
        is TopLevel.Func -> item.decl.line
        is TopLevel.Impl -> item.line
        is TopLevel.Pack -> item.line
        is TopLevel.Slot -> item.line
        is TopLevel.Fail -> item.line
        is TopLevel.Spec -> item.line
        is TopLevel.TypeAlias -> item.line
        is TopLevel.FinDecl -> item.line
        is TopLevel.VarDecl -> item.line
        is TopLevel.LetDecl -> item.line
        is TopLevel.Test -> item.line
        else -> 0
    }

    /**
     * An `impl` names the type's parameters again, in the type's order, so each
     * keeps the bounds the type declared for that position.
     */
    private fun implScope(item: TopLevel.Impl): Map<String, Set<String>> {
        val owner = declared[item.typeName]
        val ownerParams = paramsOf(item.typeName)
        val result = LinkedHashMap(ownerScope(item.typeName))
        for ((index, param) in item.typeParams.withIndex()) {
            val ownerParam = ownerParams.getOrNull(index)
            result[param] = if (owner == null || ownerParam == null) emptySet() else Bounds.required(owner.clause, ownerParam)
        }
        return result
    }

    private fun paramsOf(type: String): List<String> =
        table.lookupStruct(type)?.typeParams
            ?: table.lookupSpec(type)?.typeParams
            ?: declared[type]?.typeParams.orEmpty()

    /**
     * [type]'s own parameters under the names it declared them with, each with
     * its bounds. `impl Clone for HashMap` and `oper[:] HashMap<K, V>&.(…)`
     * write no parameter list of their own and use those names.
     */
    private fun ownerScope(type: String): Map<String, Set<String>> {
        val owner = declared[type]
        return paramsOf(type).associateWith { param -> Bounds.required(owner?.clause, param) }
    }

    /** The type a lifted member `Type__member` belongs to, when [function] is one. */
    private fun staticOwner(function: String): String? =
        function.substringBeforeLast("__", "").takeIf { it.isNotEmpty() && table.lookupStruct(it) != null }

    /**
     * [typeParams] with what [clause] requires of each. A member that names a
     * parameter of the type it belongs to keeps the type's bounds for it.
     */
    private fun declaredScope(typeParams: List<String>, clause: Expr?): Map<String, Set<String>> =
        typeParams.associateWith { param -> scope[param].orEmpty() + Bounds.required(clause, param) }

    /**
     * Whether [name] is a type the program declares or the language provides.
     * A bare name that is neither is a type parameter introduced some way this
     * walk does not model, and nothing is claimed about it.
     */
    private fun isDeclaredType(name: String): Boolean =
        IrType.isPrimitiveName(name) || name in typeNames ||
            table.lookupStruct(name) != null || table.lookupEnum(name) != null ||
            table.lookupSlot(name) != null || table.lookupSpec(name) != null || table.lookupFail(name) != null

    private inline fun within(added: Map<String, Set<String>>, block: () -> Unit) {
        val saved = scope
        scope = scope + added
        try {
            block()
        } finally {
            scope = saved
        }
    }

    private fun function(decl: FuncDecl) {
        val savedLine = line
        line = decl.line
        within(declaredScope(decl.typeParams, decl.whereClause)) {
            decl.params.forEach { param(it) }
            (decl.returnType as? TypeAnnotation.Explicit)?.let { typeRef(it.ref) }
            stmts(decl.body)
        }
        line = savedLine
    }

    // ── the walk ──────────────────────────────────────────────────

    override fun stmt(s: Stmt): Stmt {
        if (s.line > 0) line = s.line
        when (s) {
            is Stmt.VarDecl -> built(s.type, s.initializer)
            is Stmt.FinDecl -> built(s.type, s.initializer)
            is Stmt.LetDecl -> built(s.type, s.initializer)
            else -> {}
        }
        return super.stmt(s)
    }

    /** [value] stored where [type] is declared: walked, and checked as a build of it when it is `.(…)`. */
    private fun initialized(type: TypeRef?, value: Expr) {
        built(type, value)
        expr(value)
    }

    private fun built(type: TypeAnnotation, value: Expr) = built((type as? TypeAnnotation.Explicit)?.ref, value)

    /**
     * `.(…)` builds the type its position declares - `var t: Tally<U> = .()`,
     * a field's default - as surely as `Tally<U>()` does.
     */
    private fun built(type: TypeRef?, value: Expr) {
        val construct = ungrouped(value) as? Expr.InferredMember ?: return
        if (construct.name.isNotEmpty() || construct.ctorArgs == null) return
        val named = (if (type is TypeRef.Nullable) type.inner else type) as? TypeRef.Named ?: return
        if (named.args.isNotEmpty()) application(named.name, named.args, construct.line, builds = true)
    }

    override fun typeRef(ref: TypeRef): TypeRef {
        if (ref is TypeRef.Named && ref.args.isNotEmpty()) {
            application(ref.name, ref.args, if (ref.line > 0) ref.line else line, builds = false)
        }
        return super.typeRef(ref)
    }

    override fun expr(e: Expr): Expr {
        if (e.line > 0) line = e.line
        when (e) {
            is Expr.Call -> if (e.receiver == null && e.typeArgs.isNotEmpty()) {
                // `Box<U>(value)` builds the type `Box<U>`; `twice<U>(x)`
                // hands `twice` the type argument it wrote.
                application(e.callee, e.typeArgs, e.line, builds = true)
                functions[e.callee]?.let { passedOn(e, it) }
            }
            // `.(…)` builds the type its position expects - a field's default,
            // a binding's declared type - as the resolver decided it.
            is Expr.InferredMember -> table.inferredConstructionCall(e)?.let { call ->
                if (call.typeArgs.isNotEmpty()) application(call.callee, call.typeArgs, e.line, builds = true)
            }
            // A lambda's own type parameters carry no bounds.
            is Expr.Lambda -> return withTypeParams(e) { super.expr(e) }
            else -> {}
        }
        return super.expr(e)
    }

    private inline fun withTypeParams(lambda: Expr.Lambda, block: () -> Expr): Expr {
        val saved = scope
        scope = scope + lambda.typeParams.associateWith { emptySet() }
        try {
            return block()
        } finally {
            scope = saved
        }
    }

    // ── the checks ────────────────────────────────────────────────

    /**
     * `Name<args>`, written at [at]. A type is decided wherever it is written.
     * A type parameter of the surrounding code, or a type built from one,
     * answers only where a value is [builds] built: naming `Box<U>` promises
     * nothing a `Box` relies on, since every `Box` there is was built somewhere
     * that answered for its argument.
     */
    private fun application(name: String, args: List<TypeRef>, at: Int, builds: Boolean) {
        val declaration = declared[name] ?: return
        // A variadic declaration is checked per specialization, where its pack is known.
        if (declaration.typeParams.size != args.size) return
        val shown = "$name<${args.joinToString(",")}>"
        val bindings = mutableMapOf<String, ConstraintEvaluator.Binding>()
        for ((param, arg) in declaration.typeParams.zip(args)) {
            val passed = bare(arg)?.takeIf { it in scope }
            if (passed != null) {
                if (builds) requireDeclared(declaration.clause, param, passed, "'$param' of '$name'", at)
                continue
            }
            if (arg.isHole || (arg as? TypeRef.Named)?.name == "Any") continue
            if (arg is TypeRef.Named && arg.args.isEmpty() && !isDeclaredType(arg.name)) continue
            ConstraintEvaluator.bindingOf(arg)?.let { bindings[param] = it }
        }
        val outcome = ConstraintEvaluator.evaluate(declaration.clause, bindings, table, assumed(args, builds))
        // One combination is one finding, however many places write it.
        if (outcome is ConstraintEvaluator.Outcome.Violated && violated.add(shown)) {
            errors.add("line $at: '$shown' does not satisfy its 'where' clause: ${outcome.reason}")
        }
    }

    /**
     * `twice<U>(x)`, or `twice<(U, Int)>(x)`: a call from generic code that
     * writes one of its own type parameters, or a type built from one, as
     * [callee]'s type argument. A concrete type argument is the resolver's.
     */
    private fun passedOn(call: Expr.Call, callee: FuncDecl) {
        val clause = callee.whereClause ?: return
        if (scope.isEmpty()) return
        for ((param, arg) in callee.typeParams.zip(call.typeArgs)) {
            if (param == callee.variadicParam) continue
            val passed = bare(arg)?.takeIf { it in scope }
            if (passed != null) {
                requireDeclared(clause, param, passed, "'$param' of '${call.callee}'", call.line)
                continue
            }
            if (!mentionsScope(arg)) continue
            val binding = ConstraintEvaluator.bindingOf(arg) ?: continue
            val outcome = ConstraintEvaluator.evaluate(clause, mapOf(param to binding), table, assumed(listOf(arg), builds = true))
            if (outcome is ConstraintEvaluator.Outcome.Violated) {
                errors.add("line ${call.line}: '${call.callee}' does not satisfy its 'where' clause here: ${outcome.reason}")
            }
        }
    }

    /**
     * What the evaluator may assume of the type parameters in scope: where a
     * value is built, their declared bounds; where a type is only named,
     * nothing, so a question about them stays open. A bare name this walk does
     * not know as a type is a parameter it does not model, and stays open too.
     */
    private fun assumed(args: List<TypeRef>, builds: Boolean): Map<String, Set<String>?> {
        val result = HashMap<String, Set<String>?>()
        for ((param, bounds) in scope) result[param] = if (builds) Bounds.implied(bounds, table) else null
        fun visit(ref: TypeRef) {
            when (ref) {
                is TypeRef.Named -> {
                    if (ref.args.isEmpty() && ref.name !in scope && !isDeclaredType(ref.name)) result[ref.name] = null
                    ref.args.forEach(::visit)
                }
                is TypeRef.Reference -> visit(ref.inner)
                is TypeRef.Nullable -> visit(ref.inner)
                is TypeRef.Pointer -> visit(ref.inner)
                is TypeRef.Array -> visit(ref.element)
                is TypeRef.Tuple -> ref.elements.forEach(::visit)
                is TypeRef.Function -> { ref.params.forEach(::visit); visit(ref.ret) }
                else -> {}
            }
        }
        args.forEach(::visit)
        return result
    }

    private fun mentionsScope(ref: TypeRef): Boolean = when (ref) {
        is TypeRef.Named -> (ref.args.isEmpty() && ref.name in scope) || ref.args.any(::mentionsScope)
        is TypeRef.Reference -> mentionsScope(ref.inner)
        is TypeRef.Nullable -> mentionsScope(ref.inner)
        is TypeRef.Pointer -> mentionsScope(ref.inner)
        is TypeRef.Array -> mentionsScope(ref.element)
        is TypeRef.Tuple -> ref.elements.any(::mentionsScope)
        is TypeRef.Function -> ref.params.any(::mentionsScope) || mentionsScope(ref.ret)
        else -> false
    }

    /**
     * [source], a type parameter in scope, is put where [clause] bounds [param]:
     * every spec the clause requires of [param] must be one [source] declares.
     */
    private fun requireDeclared(clause: Expr, param: String, source: String, what: String, at: Int) {
        val has = Bounds.implied(scope[source].orEmpty(), table)
        for (spec in Bounds.required(clause, param)) {
            if (Bounds.includes(has, spec)) continue
            val shown = Bounds.shown(spec)
            errors.add(
                "line $at: $what must be $shown, and '$source' is not declared to be; " +
                    "add 'where $source: $shown'",
            )
        }
    }

    /** The name of a type written bare (`T`, `T&`), or null for anything with structure. */
    private fun bare(ref: TypeRef): String? = when (ref) {
        is TypeRef.Named -> ref.name.takeIf { ref.args.isEmpty() && ref.qualifier == null && !ref.variadic }
        is TypeRef.Reference -> bare(ref.inner)
        else -> null
    }

    private fun ungrouped(expr: Expr): Expr = if (expr is Expr.Grouping) ungrouped(expr.expr) else expr

    companion object {
        /** Every bound violation in [program], as diagnostics. */
        fun check(program: Program, table: SymbolTable): List<String> {
            val declared = HashMap<String, Bounded>()
            val functions = HashMap<String, FuncDecl>()
            val overloaded = HashSet<String>()
            for (item in program.items) {
                when (item) {
                    is TopLevel.Pack -> item.whereClause?.let { declared[item.name] = Bounded(item.typeParams, it) }
                    is TopLevel.Slot -> item.whereClause?.let { declared[item.name] = Bounded(item.typeParams, it) }
                    is TopLevel.Enum -> item.whereClause?.let { declared[item.name] = Bounded(item.typeParams, it) }
                    is TopLevel.Fail -> item.whereClause?.let { declared[item.name] = Bounded(item.typeParams, it) }
                    is TopLevel.Spec -> item.whereClause?.let { declared[item.name] = Bounded(item.typeParams, it) }
                    is TopLevel.TypeAlias -> item.whereClause?.let { declared[item.name] = Bounded(item.typeParams, it) }
                    is TopLevel.Func -> if (item.decl.whereClause != null && item.decl.typeParams.isNotEmpty()) {
                        // One name, two declarations: which one a call means is the
                        // resolver's to say, so neither is assumed here.
                        if (functions.put(item.decl.name, item.decl) != null) overloaded.add(item.decl.name)
                    }
                    else -> {}
                }
            }
            if (declared.isEmpty() && functions.isEmpty()) return emptyList()
            val typeNames = program.items.mapNotNullTo(HashSet()) { item ->
                when (item) {
                    is TopLevel.Pack -> item.name
                    is TopLevel.Slot -> item.name
                    is TopLevel.Enum -> item.name
                    is TopLevel.Fail -> item.name
                    is TopLevel.Spec -> item.name
                    is TopLevel.TypeAlias -> item.name
                    else -> null
                }
            }
            val checker = BoundChecker(table, declared, functions - overloaded, typeNames)
            program.items.forEach(checker::item)
            return checker.errors.toList()
        }
    }
}
