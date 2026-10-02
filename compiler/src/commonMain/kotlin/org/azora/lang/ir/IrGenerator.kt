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

package org.azora.lang.ir

import org.azora.lang.frontend.asRepeatedConstruction
import org.azora.lang.frontend.Param
import org.azora.lang.frontend.OPTIONAL_UNWRAP
import org.azora.lang.frontend.OwnershipOp
import org.azora.lang.frontend.ParamModifier
import org.azora.lang.frontend.lambdaReceiverName
import org.azora.lang.frontend.CastKind
import org.azora.lang.frontend.BindingKind
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.Literals
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.MemberCallStyle
import org.azora.lang.frontend.TypeRef
import org.azora.lang.frontend.TypeFunctionDecl
import org.azora.lang.frontend.CaptureMode
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.ReactiveKind
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TestMethod
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.semantic.SlotPatterns
import org.azora.lang.semantic.ComparisonPlan
import org.azora.lang.semantic.instantiateMember
import org.azora.lang.semantic.literalFactoryTypes
import org.azora.lang.semantic.instantiateSpecMember
import org.azora.lang.semantic.StructType
import org.azora.lang.semantic.SymbolTable
import org.azora.lang.semantic.TypeFunctionEvaluator
import org.azora.lang.semantic.comparisonPlan
import org.azora.lang.semantic.unaryOverloadName
import org.azora.lang.semantic.VariableSymbol
import org.azora.lang.semantic.Witnesses
import kotlin.collections.iterator

/**
 * Lowers a type-checked AST into typed IR.
 *
 * Assumes semantic analysis has already validated the program -- all types
 * are known, all symbols are resolved, and all constraints are satisfied.
 * Inline functions are skipped since they were already substituted by the
 * CTCE evaluator.
 *
 * @param table the fully populated symbol table from semantic analysis
 */

/**
 * A program whose generic code cannot be given the descriptor a `Hash` or
 * `Equal` bound asks for: a type parameter without the bound, or a type the
 * call does not say. Reported as a compile error, not an internal one.
 */
class WitnessError(message: String) : IllegalStateException(message)

/** Hashes an erased value by the concrete type its descriptor names; see [Witnesses]. */
private const val WITNESS_HASH = "__witness_hash"

/** Compares two erased values by the concrete type their descriptor names; see [Witnesses]. */
private const val WITNESS_EQUAL = "__witness_equal"

/** Orders two erased values (-1, 0, 1) by the concrete type their descriptor names; see [Witnesses]. */
private const val WITNESS_COMPARE = "__witness_compare"

class IrGenerator(private val table: SymbolTable) {
    private var typeFunctions = emptyList<TypeFunctionDecl>()
    private var functionDecls = emptyMap<String, FuncDecl>()
    private val generatedTraceFunctions = mutableListOf<IrFunction>()
    private val traceLambdaIndices = mutableMapOf<String, Int>()
    private val knownEnumValues = mutableMapOf<String, IrExpr.EnumLiteral>()
    private var currentTraceOwner: String? = null
    /**
     * A value whose members are callable bare, and whether it was opened on purpose.
     *
     * [prefersMembers] mirrors what the resolver typed: a `with` block and a
     * receiver lambda name a receiver deliberately, so a member of it answers a
     * bare call before a pack of the same name. A method's own receiver is
     * implicit and never shadows, which is what leaves the pack's constructor
     * reachable from inside the member that shares its name.
     */
    private class ContextFrame(val values: List<IrExpr>, val prefersMembers: Boolean)

    private val contextualValues = ArrayDeque<ContextFrame>()
    private val reactiveNames = mutableSetOf<String>()
    private val lazyReactiveDependencies = mutableMapOf<String, Set<String>>()
    private var nextEffectId = 0
    private var currentGenericTypeParams = emptySet<String>()

    private fun resolveType(ref: TypeRef, typeParams: Set<String> = emptySet()): IrType =
        IrType.resolve(
            TypeFunctionEvaluator.resolve(selfToImplType(ref), typeFunctions, unresolvedParams = typeParams),
            typeParams,
        )

    /**
     * `Self` written inside an impl, replaced by the type that impl is on.
     *
     * A member's own body may name `Self` anywhere a type goes - `store<Self>(…)`
     * as readily as a return type - and a spec's provided body says `Self`
     * precisely so every implementation reads it as its own. By the time a type
     * is resolved the impl is known, so this is where the name is answered.
     */
    private fun selfToImplType(ref: TypeRef): TypeRef {
        val owner = currentReceiverType ?: return ref
        return when (ref) {
            is TypeRef.Named ->
                if (ref.name == "Self" && ref.args.isEmpty()) TypeRef.Named(owner)
                else ref.copy(args = ref.args.map(::selfToImplType))
            is TypeRef.Array -> ref.copy(element = selfToImplType(ref.element))
            is TypeRef.Nullable -> ref.copy(inner = selfToImplType(ref.inner))
            is TypeRef.Reference -> ref.copy(inner = selfToImplType(ref.inner))
            is TypeRef.Pointer -> ref.copy(inner = selfToImplType(ref.inner))
            else -> ref
        }
    }

    /** Scope stack mapping original variable names to their mangled IR names. */
    private val nameScopes = ArrayDeque<MutableMap<String, String>>()
    private var mangledCounter = 0

    private fun pushNameScope() { nameScopes.addLast(mutableMapOf()) }
    private fun popNameScope() { nameScopes.removeLast() }

    private fun lowerScopedBody(stmts: List<Stmt>): List<IrStmt> {
        table.pushScope()
        pushNameScope()
        val names = reactiveNames.toSet()
        val lazyDependencies = lazyReactiveDependencies.toMap()
        return try {
            lowerBody(stmts)
        } finally {
            reactiveNames.retainAll(names)
            lazyReactiveDependencies.clear()
            lazyReactiveDependencies.putAll(lazyDependencies)
            popNameScope()
            table.popScope()
        }
    }

    /** Register a variable name. If it shadows an outer one, mangle it. */
    private fun registerName(name: String): String {
        // Check if name already exists in any outer scope
        val shadows = nameScopes.any { name in it }
        // A shadowing binding needs a name of its own. There is one shape for a
        // generated symbol - `__` once at the front, then single `_` - and a
        // name that already carries the prefix has to keep it: prefixing again
        // puts a `__` mid-name, which canonicalization collapses back out
        // (`____ctx0_1_70` → `___ctx0_1_70`). The declaration keeps the long
        // spelling, the use site gets the collapsed one, and the receiver is
        // then read from a global that nothing defines. Same failure the
        // `lambdaReceiverName` doc records, reached from the other side.
        val mangled = when {
            !shadows -> name
            name.startsWith("__") -> "${name}_${mangledCounter++}"
            else -> "__${name}${mangledCounter++}"
        }
        nameScopes.last()[name] = mangled
        return mangled
    }

    /** Look up the mangled name for a variable in the current scope chain. */
    private fun resolveName(name: String): String {
        for (i in nameScopes.indices.reversed()) {
            nameScopes[i][name]?.let { return it }
        }
        return name // global - no mangling
    }

    /** Look up the mangled name skipping [depth] scopes. */
    private fun resolveUpperName(name: String, depth: Int): String {
        val startIndex = nameScopes.size - 1 - depth
        if (startIndex < 0) return name // global
        for (i in startIndex downTo 0) {
            nameScopes[i][name]?.let { return it }
        }
        return name // global
    }

    private fun contextualArguments(type: IrType.Function, explicitCount: Int): List<IrExpr> {
        val explicitReceivers = (explicitCount - type.params.size).coerceAtLeast(0)
        val missing = type.receivers.drop(explicitReceivers)
        if (missing.isEmpty()) return emptyList()
        val available = contextualValues.asReversed().flatMap { it.values }
        val used = mutableSetOf<Int>()
        return missing.map { expected ->
            val index = available.indices.firstOrNull {
                it !in used && (
                    available[it].type == expected ||
                        available[it].type == IrType.Any ||
                        expected == IrType.Any
                    )
            } ?: error("missing contextual receiver $expected after semantic validation")
            used.add(index)
            available[index]
        }
    }

    /**
     * Maps mixed named and positional arguments onto declared slot [names].
     *
     * A named argument takes its own slot; a positional one fills the leftmost slot
     * no name has claimed, so `Size(width: 2, 3)` and `Size(2, height: 3)` agree.
     * Returns null for a slot no argument supplied, leaving the default to the caller.
     */
    private fun mapNamedArguments(args: List<Expr>, names: List<String>): List<Expr?> {
        val slots = arrayOfNulls<Expr>(names.size)
        for (argument in args) {
            if (argument !is Expr.NamedArg) continue
            val index = names.indexOf(argument.name)
            if (index >= 0) slots[index] = argument.value
        }
        var next = 0
        for (argument in args) {
            if (argument is Expr.NamedArg) continue
            while (next < slots.size && slots[next] != null) next++
            if (next < slots.size) slots[next] = argument
        }
        return slots.toList()
    }

    /**
     * Positional arguments for [func], with every gap in [slots] replaced by
     * that parameter's default.
     *
     * [slots] is indexed by parameter, so a `null` means "not supplied here" -
     * which is only legal when the parameter has a default. Filling stops at
     * the first gap that has none, leaving the list short so the arity error
     * the caller already reports still fires.
     *
     * [selfOffset] is 1 for a method, whose `self` occupies parameter 0 and is
     * passed separately from the argument list.
     */
    private fun fillArgumentGaps(
        slots: List<Expr?>,
        func: org.azora.lang.semantic.FunctionSymbol,
        callee: String,
        selfOffset: Int = 0,
    ): List<IrExpr> {
        val filled = mutableListOf<IrExpr>()
        for (index in slots.indices) {
            val supplied = slots[index]
            if (supplied != null) {
                filled.add(lowerExpr(supplied))
                continue
            }
            val parameter = index + selfOffset
            // The symbol table's copy of a default was captured before
            // compile-time folding ran, so the declaration in the current AST
            // is the authority when both exist.
            val default = functionDecls[func.name]?.params?.getOrNull(parameter)?.defaultValue
                ?: functionDecls[callee]?.params?.getOrNull(parameter)?.defaultValue
                ?: func.defaults[parameter]
                ?: return filled
            filled.add(lowerDefaultArgument(default, func.params[parameter].second))
        }
        return filled
    }

    /**
     * A method call's arguments, in parameter order, with defaults supplied.
     *
     * `self` is parameter 0 and is passed separately, so the method's own
     * parameters start at 1 - that offset is the only thing separating this
     * from the free-function path.
     */
    private fun lowerMethodArguments(
        expr: Expr.MethodCall,
        func: org.azora.lang.semantic.FunctionSymbol,
        mangled: String,
    ): List<IrExpr> = lowerMethodArguments(expr.args, func, mangled)

    private fun lowerMethodArguments(
        rawArgs: List<Expr>,
        func: org.azora.lang.semantic.FunctionSymbol,
        mangled: String,
    ): List<IrExpr> {
        // A member may read receivers from the block the call sits in. They lead
        // the written arguments, so they are supplied here and the rest binds
        // against what is left - which is what the call site actually wrote.
        if (func.contextualParams > 0) {
            val cp = func.contextualParams
            val fromScope = func.params.subList(1, 1 + cp).map { (name, t) ->
                contextualValueOf(t) ?: error("no '$t' in scope for '$mangled' receiver '$name'")
            }
            val rest = func.copy(
                params = listOf(func.params[0]) + func.params.drop(1 + cp),
                paramNames = if (func.paramNames.isEmpty()) func.paramNames
                else listOf(func.paramNames[0]) + func.paramNames.drop(1 + cp),
                defaults = func.defaults.mapNotNull { (i, e) -> if (i > cp) (i - cp) to e else null }.toMap(),
                contextualParams = 0,
            )
            return fromScope + lowerMethodArguments(rawArgs, rest, mangled)
        }
        val declared = func.params.size - 1
        val supplied = bindTrailingLambda(rawArgs, func.params, offset = 1)
        val named = supplied.any { it is Expr.NamedArg }
        if (!named && (supplied.size == declared || func.defaults.isEmpty() || func.isVariadic)) {
            return supplied.mapIndexed { i, argument ->
                val value = lowerExpr(argument)
                func.params.getOrNull(i + 1)?.second?.let { coerceToFloat(value, it) } ?: value
            }
        }
        val slots = if (named) {
            mapNamedArguments(supplied, func.params.drop(1).map { it.first })
        } else {
            supplied + List((declared - supplied.size).coerceAtLeast(0)) { null }
        }
        return fillArgumentGaps(slots, func, mangled, selfOffset = 1).mapIndexed { i, value ->
            func.params.getOrNull(i + 1)?.second?.let { coerceToFloat(value, it) } ?: value
        }
    }

    /**
     * [args] with a trailing lambda moved onto the last parameter.
     *
     * The same rule the resolver applies: a block written after the parentheses
     * is the last parameter's argument, and any earlier parameters it appears
     * to skip are taking their defaults. Both stages must agree, or lowering
     * would build a call the checker never approved.
     */
    private fun bindTrailingLambda(
        args: List<Expr>,
        params: List<Pair<String, IrType>>,
        offset: Int,
    ): List<Expr> {
        val declared = params.size - offset
        if (args.size >= declared || args.isEmpty()) return args
        val lambda = args.last()
        if (lambda !is Expr.Lambda) return args
        // The name may already be taken by an explicit `body:` argument.
        if (args.any { it is Expr.NamedArg && it.name == params.last().first }) return args
        if (params.last().second !is IrType.Function) return args
        return args.dropLast(1) + Expr.NamedArg(params.last().first, lambda, lambda.line, lambda.column)
    }

    /** The method-table key a primitive receiver uses (`impl Int { … }`). */
    private fun primitiveOwnerName(type: IrType): String? = when (type) {
        IrType.Int, IrType.UInt, IrType.Long, IrType.ULong, IrType.Byte, IrType.UByte,
        IrType.Short, IrType.UShort, IrType.Float, IrType.Double, IrType.Quad,
        IrType.String, IrType.Char, IrType.Bool -> type.toString()
        else -> null
    }

    private fun dependencyNames(expr: Expr): Set<String> = when (expr) {
        is Expr.Identifier -> setOf(expr.name)
        is Expr.Grouping -> dependencyNames(expr.expr)
        is Expr.Member -> dependencyNames(expr.target)
        is Expr.SafeMember -> dependencyNames(expr.target)
        is Expr.Index -> dependencyNames(expr.target)
        is Expr.TupleAccess -> dependencyNames(expr.target)
        else -> emptySet()
    }

    private fun referencedNames(body: List<IrStmt>): Set<String> = buildSet {
        body.forEach { collectReferencedNames(it, this) }
    }

    private fun registerLazyReactiveDependencies(name: String, initializer: IrExpr) {
        val referenced = linkedSetOf<String>()
        collectReferencedNames(initializer, referenced)
        val reactive = reactiveNames.map(::resolveName).toSet()
        lazyReactiveDependencies[name] = buildSet {
            addAll(referenced.intersect(reactive))
            referenced.forEach { addAll(lazyReactiveDependencies[it].orEmpty()) }
        }
    }

    private fun automaticEffectDependencies(body: List<IrStmt>): Set<String> {
        val referenced = referencedNames(body)
        val reactive = reactiveNames.map(::resolveName).toSet()
        return buildSet {
            addAll(referenced.intersect(reactive))
            referenced.forEach { addAll(lazyReactiveDependencies[it].orEmpty()) }
        }
    }

    private fun collectReferencedNames(stmt: IrStmt, names: MutableSet<String>) {
        when (stmt) {
            is IrStmt.VarDecl -> collectReferencedNames(stmt.initializer, names)
            is IrStmt.FinDecl -> collectReferencedNames(stmt.initializer, names)
            is IrStmt.LetDecl -> collectReferencedNames(stmt.initializer, names)
            is IrStmt.Assignment -> collectReferencedNames(stmt.value, names)
            is IrStmt.Exchange -> { collectReferencedNames(stmt.left, names); collectReferencedNames(stmt.right, names) }
            is IrStmt.IndexAssign -> {
                collectReferencedNames(stmt.target, names)
                collectReferencedNames(stmt.index, names)
                collectReferencedNames(stmt.value, names)
            }
            is IrStmt.MemberAssign -> {
                collectReferencedNames(stmt.target, names)
                collectReferencedNames(stmt.value, names)
            }
            is IrStmt.Return -> stmt.value?.let { collectReferencedNames(it, names) }
            is IrStmt.ExprStmt -> collectReferencedNames(stmt.expr, names)
            is IrStmt.If -> {
                collectReferencedNames(stmt.condition, names)
                stmt.thenBranch.forEach { collectReferencedNames(it, names) }
                stmt.elseBranch?.forEach { collectReferencedNames(it, names) }
            }
            is IrStmt.Scope -> stmt.body.forEach { collectReferencedNames(it, names) }
            is IrStmt.Assert -> {
                collectReferencedNames(stmt.condition, names)
                collectReferencedNames(stmt.message, names)
            }
            is IrStmt.Trace -> {
                collectReferencedNames(stmt.level, names)
                collectReferencedNames(stmt.message, names)
            }
            is IrStmt.While -> {
                collectReferencedNames(stmt.condition, names)
                stmt.body.forEach { collectReferencedNames(it, names) }
            }
            is IrStmt.For -> {
                collectReferencedNames(stmt.start, names)
                collectReferencedNames(stmt.end, names)
                stmt.step?.let { collectReferencedNames(it, names) }
                stmt.body.forEach { collectReferencedNames(it, names) }
            }
            is IrStmt.ForEach -> {
                collectReferencedNames(stmt.iterable, names)
                stmt.body.forEach { collectReferencedNames(it, names) }
            }
            is IrStmt.Loop -> stmt.body.forEach { collectReferencedNames(it, names) }
            is IrStmt.When -> {
                collectReferencedNames(stmt.scrutinee, names)
                stmt.branches.forEach { branch ->
                    branch.patterns.forEach { collectReferencedNames(it, names) }
                    branch.body.forEach { collectReferencedNames(it, names) }
                }
                stmt.elseBranch?.forEach { collectReferencedNames(it, names) }
            }
            is IrStmt.Throw -> collectReferencedNames(stmt.value, names)
            is IrStmt.Try -> {
                stmt.body.forEach { collectReferencedNames(it, names) }
                stmt.catchBody?.forEach { collectReferencedNames(it, names) }
            }
            is IrStmt.Defer -> stmt.body.forEach { collectReferencedNames(it, names) }
            is IrStmt.Effect -> stmt.body.forEach { collectReferencedNames(it, names) }
            is IrStmt.Yield -> collectReferencedNames(stmt.value, names)
            is IrStmt.Break, is IrStmt.Continue -> Unit
        }
    }

    private fun collectReferencedNames(expr: IrExpr, names: MutableSet<String>) {
        when (expr) {
            is IrExpr.Var -> names.add(expr.name)
            is IrExpr.Binary -> {
                collectReferencedNames(expr.left, names)
                collectReferencedNames(expr.right, names)
            }
            is IrExpr.Unary -> collectReferencedNames(expr.operand, names)
            is IrExpr.IncDec -> collectReferencedNames(expr.target, names)
            is IrExpr.Call -> {
                expr.receiver?.let { collectReferencedNames(it, names) }
                expr.args.forEach { collectReferencedNames(it, names) }
            }
            is IrExpr.ArrayLiteral -> expr.elements.forEach { collectReferencedNames(it, names) }
            is IrExpr.MapLit -> expr.entries.forEach { (key, value) ->
                collectReferencedNames(key, names)
                collectReferencedNames(value, names)
            }
            is IrExpr.SetLit -> expr.elements.forEach { collectReferencedNames(it, names) }
            is IrExpr.Index -> {
                collectReferencedNames(expr.target, names)
                collectReferencedNames(expr.index, names)
            }
            is IrExpr.Member -> collectReferencedNames(expr.target, names)
            is IrExpr.MethodCall -> {
                collectReferencedNames(expr.target, names)
                expr.args.forEach { collectReferencedNames(it, names) }
            }
            is IrExpr.StructCtor -> expr.args.forEach { collectReferencedNames(it, names) }
            is IrExpr.StringTemplate -> expr.parts.forEach { part ->
                if (part is IrExpr.IrTemplatePart.Expr) collectReferencedNames(part.expr, names)
            }
            is IrExpr.TupleLit -> expr.elements.forEach { collectReferencedNames(it, names) }
            is IrExpr.VariantLit -> expr.elements.forEach { collectReferencedNames(it, names) }
            is IrExpr.TupleAccess -> collectReferencedNames(expr.target, names)
            is IrExpr.CatchExpr -> {
                collectReferencedNames(expr.expr, names)
                collectReferencedNames(expr.fallback, names)
            }
            is IrExpr.IfExpr -> {
                collectReferencedNames(expr.condition, names)
                collectReferencedNames(expr.thenExpr, names)
                collectReferencedNames(expr.elseExpr, names)
            }
            is IrExpr.NumCast -> collectReferencedNames(expr.value, names)
            is IrExpr.EnumToString -> collectReferencedNames(expr.value, names)
            is IrExpr.Lambda -> Unit
            is IrExpr.Await -> collectReferencedNames(expr.value, names)
            is IrExpr.Spread -> collectReferencedNames(expr.array, names)
            is IrExpr.IntLiteral,
            is IrExpr.DoubleLiteral,
            is IrExpr.StringLiteral,
            is IrExpr.EnumLiteral,
            is IrExpr.BoolLiteral,
            is IrExpr.CharLiteral,
            IrExpr.UnitLiteral,
            is IrExpr.SlotPattern -> Unit
        }
    }

    private fun lowerEffectStatements(body: List<Stmt>): List<IrStmt> = lowerScopedBody(body)

    /**
     * Generates a typed [IrProgram] from the given AST.
     *
     * Inline functions are filtered out since they have already been
     * substituted at their call sites by the CTCE evaluator.
     *
     * @param program the CTCE-stabilized, type-checked AST
     * @return the lowered [IrProgram]
     */
    /**
     * Top-level constants whose initializer is a literal.
     *
     * A default argument is lowered in the *caller's* scope, so a default that
     * names a constant declared in another module resolves to nothing there and
     * degrades to an untyped global reference. Substituting the literal keeps a
     * default what it reads as - a compile-time constant - and avoids emitting a
     * cross-module global that the unit never defines.
     */
    private val constantLiterals = mutableMapOf<String, IrExpr>()

    fun generate(program: Program): IrProgram {
        ctorOverloadedTypes.clear()
        program.items.filterIsInstance<TopLevel.Impl>()
            .filter { ctorCount(it.methods.map { m -> m.name }) > 1 }
            .forEach { ctorOverloadedTypes.add(it.typeName) }
        typeFunctions = program.typeFunctions
        functionDecls = program.functions.associateBy { it.name }
        initWitnesses(program)
        generatedTraceFunctions.clear()
        traceLambdaIndices.clear()
        knownEnumValues.clear()
        currentTraceOwner = null
        contextualValues.clear()
        nameScopes.clear()
        mangledCounter = 0
        pushNameScope() // global scope

        // Register global names
        constantLiterals.clear()
        for (item in program.items) {
            when (item) {
                is TopLevel.FinDecl -> if (item.threadlocal) nameScopes.last()[item.name] = "__tl__${item.name}" else registerName(item.name)
                is TopLevel.VarDecl -> if (item.threadlocal) nameScopes.last()[item.name] = "__tl__${item.name}" else registerName(item.name)
                is TopLevel.LetDecl -> registerName(item.name)
                is TopLevel.Bridge -> item.values.forEach { value ->
                    nameScopes.last()[value.name] = value.foreignName ?: value.name
                }
                else -> {}
            }
            literalConstant(item)?.let { (name, value) -> constantLiterals[name] = value }
        }

        // Register import aliases in the global name scope so `import Scope.Item` resolves.
        for ((alias, real) in table.aliasMap) {
            nameScopes.last()[alias] = real
        }

        val sourceTests = program.tests
        val hasAllTest = sourceTests.any { it.method == TestMethod.All }

        fun lowerTestBody(name: String, body: List<Stmt>): List<IrStmt> {
            val previousOwner = currentTraceOwner
            currentTraceOwner = "test_${name.replace(Regex("[^A-Za-z0-9_]"), "_")}"
            knownEnumValues.clear()
            table.pushScope()
            pushNameScope()
            return try {
                lowerBody(body)
            } finally {
                popNameScope()
                table.popScope()
                currentTraceOwner = previousOwner
            }
        }

        // Lower top-level items in source order to preserve interleaving
        val items = program.items.flatMap { item ->
            when (item) {
                is TopLevel.Func -> {
                    // Legacy runtime intrinsics with ordinary stdlib declarations
                    // have dead placeholder bodies and stay out of IR. Proper
                    // compiler intrinsics are represented by `bridge func` instead.
                    if (item.decl.isInline || item.decl.name in org.azora.lang.semantic.CtfeEvaluator.RUNTIME_INTRINSICS) emptyList()
                    else listOf(IrTopLevel.Func(lowerFunction(item.decl)))
                }
                is TopLevel.FinDecl -> {
                    val init = lowerExpr(item.initializer)
                    val type = if (item.type != null) resolveType(item.type) else init.type
                    val irName = if (item.threadlocal) "__tl__${item.name}" else item.name
                    listOf(IrTopLevel.Global(IrStmt.FinDecl(irName, type, init)))
                }
                is TopLevel.LetDecl -> {
                    val init = lowerExpr(item.initializer)
                    val type = if (item.type != null) resolveType(item.type) else init.type
                    listOf(IrTopLevel.Global(IrStmt.LetDecl(item.name, type, init)))
                }
                is TopLevel.VarDecl -> {
                    val init = lowerExpr(item.initializer)
                    val type = if (item.type != null) resolveType(item.type) else init.type
                    val irName = if (item.threadlocal) "__tl__${item.name}" else item.name
                    listOf(IrTopLevel.Global(IrStmt.VarDecl(irName, type, init)))
                }
                is TopLevel.Test -> {
                    when {
                        hasAllTest && item.method == TestMethod.This -> emptyList()
                        item.method == TestMethod.All -> {
                            val ownBody = lowerTestBody(item.name, item.body)
                            // Each gathered body keeps the name of the test it
                            // came from: an `.All` test is every `This` test in
                            // the file, and which is which is the first thing a
                            // reader of the lowered form needs.
                            val children = sourceTests
                                .filter { it.method == TestMethod.This }
                                .map { IrStmt.Scope(lowerTestBody(it.name, it.body), it.name) }
                            listOf(IrTopLevel.Test(item.name, ownBody + children, item.method))
                        }
                        else -> listOf(
                            IrTopLevel.Test(item.name, lowerTestBody(item.name, item.body), item.method),
                        )
                    }
                }
                is TopLevel.Enum -> listOf(IrTopLevel.Enum(item.name, item.variants))
                is TopLevel.Pack -> {
                    // `bridge pack X` - a compiler-provided type (primitives, Reflected);
                    // no struct is emitted.
                    if (item.isBridge) emptyList()
                    else {
                        val tpSet = item.typeParams.toSet()
                        val fields = item.fields.map { IrField(it.name, resolveType(it.type, tpSet), it.mutable) }
                        val slots = item.fields.map { field ->
                            item.typeParams.indexOf((field.type as? TypeRef.Named)?.name ?: "")
                        }
                        listOf(
                            IrTopLevel.Struct(
                                item.name,
                                fields,
                                program.scopeTypeNamespaces[item.name],
                                item.typeParams,
                                slots,
                                isUnion = item.isUnion,
                            )
                        )
                    }
                }
                is TopLevel.Solo -> {
                    val fields = item.fields.map { IrField(it.name, resolveType(it.type), it.mutable) }
                    val result = mutableListOf<IrTopLevel>(
                        IrTopLevel.Struct(item.name, fields, program.scopeTypeNamespaces[item.name]),
                    )
                    // Lower methods as free functions Name_method (like impl).
                    // The receiver type has to be in scope for the same reason it
                    // does in an impl: a bare field name in the body reads the
                    // receiver's field.
                    for (method in item.methods) {
                        if (method.isInline) continue
                        val saved = currentReceiverType
                        currentReceiverType = item.name
                        try { result.add(IrTopLevel.Func(lowerMethod(item.name, method))) }
                        finally { currentReceiverType = saved }
                    }
                    // Emit a __singleton_Name factory that constructs the struct from field defaults.
                    val defaults = item.fields.map { f ->
                        if (f.default != null) coerceToFloat(lowerExpr(f.default), resolveType(f.type))
                        else defaultValueForType(resolveType(f.type))
                    }
                    val factory = IrFunction(
                        "__singleton_${item.name}",
                        emptyList(),
                        IrType.Named(item.name),
                        listOf(IrStmt.Return(IrExpr.StructCtor(item.name, item.fields.map { it.name }, defaults, IrType.Named(item.name))))
                    )
                    result.add(IrTopLevel.Func(factory))
                    result
                }
                is TopLevel.Impl -> if (item.isBridge) emptyList() else item.methods.mapNotNull { method ->
                    if (method.isInline) null
                    else {
                        val saved = currentReceiverType
                        currentReceiverType = item.typeName
                        try { IrTopLevel.Func(lowerMethod(item.typeName, method)) }
                        finally { currentReceiverType = saved }
                    }
                }
                else -> emptyList() // Inline constructs already resolved by CTCE
            }
        } +
        // A declared `ctor` builds the value it is a member of, so calling
        // `Model(w, h)` must run it rather than filling fields positionally.
        // Each one gets a factory that allocates with field defaults, runs the
        // ctor against the fresh value, and hands it back - a plain function, so
        // every backend gets the behaviour without knowing about constructors.
        program.items.filterIsInstance<TopLevel.Impl>().flatMap { item ->
            val struct = table.lookupStruct(item.typeName)
            val ctorOverloaded = ctorCount(item.methods.map { it.name }) > 1
            item.methods.filter { it.name == "ctor" && it.params.isNotEmpty() }.flatMap { ctor ->
                if (struct == null) return@flatMap emptyList()
                val type = IrType.Named(item.typeName)
                val params = ctor.params.map { it.name to resolveType(it.type) }
                val fieldDefaults = struct.fields.map { f ->
                    f.default?.let { coerceToFloat(lowerExpr(it), f.type) } ?: defaultValueForType(f.type)
                }
                // One factory, taking every parameter. A default is filled at the
                // call site, where the parameter's type is known and an inferred
                // constructor - `modifier: Modifier& = .()` - can be read at all.
                val written = ctor.params.drop(ctor.contextualParams)
                listOf(written.size).map { arity ->
                    val taken = params
                    val filled = params.map { (name, t) -> IrExpr.Var(name, t) }
                    // A ctor that declared a return type yields that value; one
                    // that declared none has filled `__self`, which is the value.
                    val declared = (ctor.returnType as? TypeAnnotation.Explicit)?.let { resolveType(it.ref) }
                    val call = IrExpr.Call(
                        ctorSymbol(item.typeName, arity, ctor.isRepeated, ctorOverloaded),
                        listOf(IrExpr.Var("__self", type)) + filled,
                        declared ?: IrType.Unit,
                    )
                    val fresh = IrStmt.VarDecl(
                        "__self",
                        type,
                        IrExpr.StructCtor(item.typeName, struct.fields.map { it.name }, fieldDefaults, type),
                    )
                    IrTopLevel.Func(IrFunction(
                        ctorFactorySymbol(item.typeName, arity, ctor.isRepeated),
                        taken,
                        declared ?: type,
                        if (declared != null) {
                            listOf(fresh, IrStmt.Return(call))
                        } else {
                            listOf(fresh, IrStmt.ExprStmt(call), IrStmt.Return(IrExpr.Var("__self", type)))
                        },
                    ))
                }
            }
        } +
        // `ctor .()` takes no argument, so there is no factory to call.
        // `Type()` builds the value as any construction does and passes it
        // here, where the ctor runs on it before it is handed back.
        program.items.filterIsInstance<TopLevel.Impl>().mapNotNull { item ->
            if (item.isBridge || table.lookupStruct(item.typeName) == null) return@mapNotNull null
            if (item.methods.none { it.name == "ctor" && it.params.isEmpty() }) return@mapNotNull null
            val ctor = receiverOnlyCtorSymbol(item.typeName, table) ?: return@mapNotNull null
            val type = IrType.Named(item.typeName)
            val self = IrExpr.Var("__self", type)
            val run = IrExpr.Call(ctor, listOf(self), table.lookupFunction(ctor)!!.returnType)
            IrTopLevel.Func(IrFunction(
                ctorRunSymbol(item.typeName),
                listOf("__self" to type),
                type,
                listOf(IrStmt.ExprStmt(run), IrStmt.Return(self)),
            ))
        } +
        // Emit __singleton factories for `graph` registrations (DI wiring).
        program.items.filterIsInstance<TopLevel.Graph>().flatMap { graph ->
            graph.registrations.flatMap { reg ->
                val struct = table.lookupStruct(reg.typeName) ?: return@flatMap emptyList()
                // A field the registration leaves out takes its declared default,
                // as it would in `Name(args)`; only a field without one is zeroed.
                val fullArgs = loweredFieldValues(struct, reg.args).mapIndexed { i, value ->
                    if (i >= reg.args.size && struct.fields[i].default == null) defaultValueForType(struct.fields[i].type) else value
                }
                val type = IrType.Named(reg.typeName)
                val factory = IrFunction(
                    "__singleton_${reg.typeName}",
                    emptyList(),
                    type,
                    listOf(IrStmt.Return(IrExpr.StructCtor(reg.typeName, struct.fields.map { it.name }, fullArgs, type)))
                )
                // `binds Spec` lets `inject Spec` answer with this provider. The
                // spec's factory injects the type rather than building a second
                // one, so both names share the single instance.
                val bound = reg.bindSpecs.map { spec ->
                    IrFunction(
                        "__singleton_$spec",
                        emptyList(),
                        IrType.Named(spec),
                        listOf(IrStmt.Return(IrExpr.Call("__inject", listOf(IrExpr.StringLiteral(reg.typeName)), type)))
                    )
                }
                (listOf(factory) + bound).map { IrTopLevel.Func(it) }
            }
        } +
        // Emit extern declarations for `bridge` (FFI) function signatures.
        program.items.filterIsInstance<TopLevel.Bridge>().flatMap { bridge ->
            bridge.funcs.map { sig ->
                val params = sig.params.map { it.name to resolveType(it.type) }
                IrTopLevel.Extern(sig.name, params, resolveType(sig.returnType))
            } + bridge.values.map { value ->
                val irName = value.foreignName ?: value.name
                val init = lowerExpr(value.initializer)
                val type = resolveType(value.type)
                val declaration = when {
                    value.mutable -> IrStmt.VarDecl(irName, type, init)
                    value.isLet -> IrStmt.LetDecl(irName, type, init)
                    else -> IrStmt.FinDecl(irName, type, init)
                }
                IrTopLevel.Global(declaration, exportName = irName)
            }
        } +
        // Last, once every use has registered the types its descriptors name.
        witnessDispatchFunctions()
        // Source order, item for item. The IR is read as much as it is run -
        // a `pack`, the `func` under it and the `enum` between them come back in
        // the order they were written, because a reader comparing the two is
        // comparing them line by line. Nothing downstream depends on the shape
        // of this list: a backend that needs its own order sorts for itself.
        val lowered = IrProgram(
            program.moduleName,
            generatedTraceFunctions.map { IrTopLevel.Func(it) } + items,
            buildSpecTables(),
        )
        return IrSymbolCanonicalizer.canonicalize(lowered, program.scopeTypeNamespaces)
    }

    /**
     * Collects a dynamic-dispatch table for every spec that has at least one
     * concrete `pack` implementer whose `impl` methods all resolve to real
     * functions. Backends without native trait objects use this to emit a
     * type-id switch; the interpreter ignores it. Decorator contracts and
     * marker specs (no method signatures) are skipped.
     */
    private fun buildSpecTables(): List<IrSpecTable> {
        val bySpec = table.allConformances()
            .filterNot { it.isDecorator }
            .groupBy { it.contractName }
        val tables = mutableListOf<IrSpecTable>()
        for ((specName, confs) in bySpec) {
            val spec = table.lookupSpec(specName) ?: continue
            // A spec dispatches what it inherits as well as what it declares.
            // `MutableList<T> : List<T>` adds `add` and `removeAt`; `size` and
            // reading an element are `List`'s. A table built from the spec's own
            // signatures alone leaves a caller holding a `MutableList` unable to
            // ask its size - the backend finds no entry and emits nothing for it.
            // Nearest declaration wins, and `specAndAncestors` puts the spec
            // itself first, so an override is what an implementer is asked for.
            val callableByName = LinkedHashMap<String, IrSpecMethod>()
            val propertyByName = LinkedHashMap<String, IrSpecMethod>()
            for (ancestorName in table.specAndAncestors(specName)) {
                val ancestor = table.lookupSpec(ancestorName) ?: continue
                for ((methodName, sig) in ancestor.methodSigs) {
                    if (methodName in callableByName || methodName in propertyByName) continue
                    if (sig.isProperty) {
                        propertyByName[methodName] = IrSpecMethod(methodName, emptyList(), sig.returnType)
                    } else {
                        callableByName[methodName] = IrSpecMethod(methodName, sig.paramTypes, sig.returnType)
                    }
                }
            }
            val callable = callableByName.values.toList()
            // A spec property (`prop size&.: Int`) dispatches exactly
            // like a nullary method - the backend reads `value.size` through the
            // same stub. An implementer that satisfies the property with a plain
            // field has no getter to point at; that one type drops out of the
            // property's switch rather than out of the table, so its ordinary
            // method dispatch is unaffected.
            val properties = propertyByName.values.toList()
            val methods = callable + properties
            if (methods.isEmpty()) continue
            val impls = mutableListOf<IrSpecImpl>()
            for (typeName in confs.map { it.typeName }.distinct()) {
                // Dynamic dispatch boxes a concrete value behind a fat pointer, which
                // needs a struct to cast to. A primitive conforming to a spec (`Int is
                // Number`) has no struct, so it takes part in constraint checking but
                // never in a vtable.
                if (table.lookupStruct(typeName)?.isBridge != false) continue
                val methodFuncs = mutableMapOf<String, String>()
                var complete = true
                for (m in callable) {
                    val fn = table.lookupMethod(typeName, m.name)
                    if (fn == null) { complete = false; break }
                    methodFuncs[m.name] = fn
                }
                if (!complete) continue
                for (p in properties) {
                    table.lookupMethod(typeName, p.name)?.let { methodFuncs[p.name] = it }
                }
                impls.add(IrSpecImpl(typeName, methodFuncs))
            }
            if (impls.isNotEmpty()) tables.add(IrSpecTable(specName, methods, impls))
        }
        return tables
    }

    private fun lowerFunction(func: FuncDecl): IrFunction {
        val symbol = table.lookupFunction(func.name)!!
        val previousOwner = currentTraceOwner
        val previousTypeParams = currentGenericTypeParams
        currentTraceOwner = func.name
        currentGenericTypeParams = func.typeParams.toSet()
        knownEnumValues.clear()
        // Borrowed parameters are passed by reference.
        val refParams = func.params.indices.filter {
            func.params[it].modifier != ParamModifier.NONE
        }.toSet()
        table.pushScope()
        pushNameScope()
        val savedReactiveNames = reactiveNames.toSet()
        val savedLazyDependencies = lazyReactiveDependencies.toMap()
        reactiveNames.clear()
        lazyReactiveDependencies.clear()

        // Register parameters
        val mangledParams = symbol.params.map { (name, type) ->
            val mangled = registerName(name)
            table.defineVariable(VariableSymbol(name, type, mutable = true)) // all params mutable for simplicity; mut is enforced at type level
            mangled to type
        }
        // A bounded type parameter's descriptor follows the written parameters.
        val bounds = functionWitnessBounds[func.name].orEmpty()
        val witnessParams = bounds.keys.map { param ->
            val slot = Witnesses.slot(param)
            val mangled = registerName(slot)
            table.defineVariable(VariableSymbol(slot, IrType.Int, mutable = false))
            mangled to IrType.Int
        }
        val sources = bounds.keys.zip(witnessParams)
            .associateTo(LinkedHashMap<String, IrExpr>()) { (param, slot) -> param to IrExpr.Var(slot.first, IrType.Int) }
        val scopeBounds = LinkedHashMap(bounds)
        func.variadicParam?.let { pack ->
            Witnesses.bounds(func.whereClause, func.typeParams)[pack]?.let { scopeBounds[pack] = it }
        }
        // A parameter holding a bounded pack carries the descriptors of the type
        // arguments it was built at: `oper+ HashMap<K, V>&.(…)` knows its `K`.
        for (param in listOfNotNull(func.extensionReceiver) + func.params) {
            val named = stripRef(param.type) as? TypeRef.Named ?: continue
            val packBounds = packWitnessBounds[named.name] ?: continue
            val packParams = packTypeParams[named.name].orEmpty()
            for ((packParam, specs) in packBounds) {
                val arg = named.args.getOrNull(packParams.indexOf(packParam)) as? TypeRef.Named ?: continue
                if (arg.args.isNotEmpty() || arg.name !in func.typeParams || arg.name in scopeBounds) continue
                val holder = IrExpr.Var(resolveName(param.name), IrType.Named(named.name))
                sources[arg.name] = IrExpr.Member(holder, Witnesses.slot(packParam), IrType.Int)
                scopeBounds[arg.name] = specs
            }
        }

        val body = try {
            inWitnessScope(
                scopeBounds,
                sources,
                func.typeParams.toSet(),
                func.params.associate { it.name to it.type },
                (func.returnType as? TypeAnnotation.Explicit)?.ref,
            ) { lowerBody(func.body) }
        } finally {
            reactiveNames.clear()
            reactiveNames.addAll(savedReactiveNames)
            lazyReactiveDependencies.clear()
            lazyReactiveDependencies.putAll(savedLazyDependencies)
            popNameScope()
            table.popScope()
            currentTraceOwner = previousOwner
            currentGenericTypeParams = previousTypeParams
        }

        return IrFunction(
            func.name,
            mangledParams + witnessParams,
            symbol.returnType,
            body,
            refParams,
            func.isTask,
            func.isUnsafe,
            isFailable = declaredFailable(func),
            isReactive = func.isReactive,
        )
    }

    /** True when [func] declares a `T ?! E` return type. */
    private fun declaredFailable(func: FuncDecl): Boolean {
        val ref = (func.returnType as? TypeAnnotation.Explicit)?.ref
        return ref is TypeRef.Failable
    }

    /** The current node type being lowered (for `base` resolution). Null outside a node method. */
    /**
     * Types whose impls declare more than one `ctor`, so their ctors are
     * overloads and must be emitted under distinct symbols. See [ctorSymbol].
     */
    private val ctorOverloadedTypes = mutableSetOf<String>()

    private var currentNodeType: String? = null
    /** The current impl receiver type (for implicit-self field access: bare `size` → `self.size`). */
    private var currentReceiverType: String? = null


    /** The member being lowered, so a bare call to it is not read as itself. */
    private var currentMemberName: String? = null

    /** Lowers an impl method into a free function `Type_method(self, ...)`. */
    private fun lowerMethod(typeName: String, method: FuncDecl): IrFunction {
        val savedNodeType = currentNodeType
        val savedMemberName = currentMemberName
        currentNodeType = typeName
        currentMemberName = method.name
        try {
            return lowerMethodInternal(typeName, method)
        } finally {
            currentNodeType = savedNodeType
            currentMemberName = savedMemberName
        }
    }

    private fun lowerMethodInternal(typeName: String, method: FuncDecl): IrFunction {
        val mangled = if (method.name == "ctor") {
            ctorSymbol(
                typeName,
                method.params.size - method.contextualParams,
                method.isRepeated,
                overloaded = ctorOverloadedTypes.contains(typeName),
            )
        } else {
            mangleMethodSymbol("${typeName}_${method.name}")
        }
        val symbol = table.lookupFunction(mangled)!!
        val previousOwner = currentTraceOwner
        currentTraceOwner = mangled
        knownEnumValues.clear()
        table.pushScope()
        pushNameScope()
        val savedReactiveNames = reactiveNames.toSet()
        val savedLazyDependencies = lazyReactiveDependencies.toMap()
        reactiveNames.clear()
        lazyReactiveDependencies.clear()
        val mangledParams = symbol.params.map { (name, type) ->
            val m = registerName(name)
            val mutable = name != method.receiverName || method.receiverModifier != ParamModifier.SHARED
            table.defineVariable(VariableSymbol(name, type, mutable = mutable))
            m to type
        }
        val unnamedReceiverTuple = method.contextualParams > 0 && method.receiverName == "__receiver0"
        val receiverTupleType = if (unnamedReceiverTuple) {
            IrType.Tuple(symbol.params.take(1 + method.contextualParams).map { it.second })
        } else null
        val receiverTuplePrelude = if (receiverTupleType != null) {
            val selfName = registerName("self")
            table.defineVariable(VariableSymbol("self", receiverTupleType, mutable = false))
            listOf(
                IrStmt.FinDecl(
                    selfName,
                    receiverTupleType,
                    IrExpr.TupleLit(
                        symbol.params.take(1 + method.contextualParams).map { (name, type) ->
                            IrExpr.Var(resolveName(name), type)
                        },
                        receiverTupleType,
                    ),
                ),
            )
        } else emptyList()
        contextualValues.addLast(
            ContextFrame(
                listOf(
                    if (receiverTupleType != null) IrExpr.Var(resolveName("self"), receiverTupleType)
                    else IrExpr.Var(resolveName(method.receiverName), IrType.Named(typeName)),
                ),
                prefersMembers = false,
            ),
        )
        // A bounded pack's methods read its descriptors from the receiver.
        val packParams = packTypeParams[typeName].orEmpty()
        val bounds = packWitnessBounds[typeName].orEmpty()
        val receiver = IrExpr.Var(resolveName(method.receiverName), IrType.Named(typeName))
        val sources = bounds.keys.associateWith { param -> IrExpr.Member(receiver, Witnesses.slot(param), IrType.Int) }
        val refs = method.params.associate { it.name to it.type } +
            (method.receiverName to TypeRef.Named(typeName, packParams.map { TypeRef.Named(it) }))
        val body = try {
            inWitnessScope(
                bounds,
                sources,
                packParams.toSet() + method.typeParams,
                refs,
                (method.returnType as? TypeAnnotation.Explicit)?.ref,
            ) { receiverTuplePrelude + lowerBody(method.body) }
        } finally {
            reactiveNames.clear()
            reactiveNames.addAll(savedReactiveNames)
            lazyReactiveDependencies.clear()
            lazyReactiveDependencies.putAll(savedLazyDependencies)
            contextualValues.removeLast()
            popNameScope()
            table.popScope()
            currentTraceOwner = previousOwner
        }
        return IrFunction(
            mangled,
            mangledParams,
            symbol.returnType,
            body,
            isFailable = declaredFailable(method),
        )
    }

    /** A shared friend name scope, or null if no friend scopes encountered yet. */
    private var friendNameScope: MutableMap<String, String>? = null

    /** Guards a pending optional take needs in front of the statement holding it. */
    private val pendingBefore = mutableListOf<IrStmt>()

    /** Writes a pending optional take needs after that statement. */
    private val pendingAfter = mutableListOf<IrStmt>()

    /**
     * Records what moving out of an optional owes the optional (§17).
     *
     * `take opt.require()` and `opt.take()` hand the value to a new owner, so
     * the optional must not keep naming it. Emptying it rather than leaving it
     * pointing at a moved-from value is what keeps every optional either null
     * or valid. The guard goes in front because the read that follows is only
     * meaningful once the optional is known to hold something.
     */
    private fun emptyAfterTake(target: Expr, value: IrExpr) {
        if (value.type !is IrType.Nullable) return
        val owner = (target as? Expr.Identifier)?.name ?: return
        pendingBefore.add(
            IrStmt.Assert(
                IrExpr.Binary(value, IrBinaryOp.NEQ, IrExpr.Var("__null", IrType.Any), IrType.Bool),
                IrExpr.StringLiteral("took a value out of a null optional"),
            ),
        )
        pendingAfter.add(IrStmt.Assignment(resolveName(owner), IrExpr.Var("__null", IrType.Any)))
    }

    /**
     * Lowers a list of statements, handling friend scope blocks by sharing
     * a name scope across all friend scopes in the same body.
     */
    private fun lowerBody(stmts: List<Stmt>): List<IrStmt> {
        val hasScopes = stmts.any { it is Stmt.Scope && it.shared }
        val savedFriendScope = friendNameScope

        if (hasScopes) {
            friendNameScope = mutableMapOf()
        }

        // Bindings that persist from one scope block to the next.
        val friendSymbols = mutableMapOf<String, VariableSymbol>()

        val result = mutableListOf<IrStmt>()
        for (stmt in stmts) {
            if (stmt is Stmt.Scope && stmt.shared) {
                // Push the shared scope name scope + symbol table scope
                table.pushScope()
                nameScopes.addLast(friendNameScope!!)
                // Restore bindings left by an earlier scope in this body
                for ((_, sym) in friendSymbols) table.defineVariable(sym)
                val lowered = stmt.body.map { lowerStmt(it) }
                // Hand them on to the next scope in this body
                table.exportCurrentScope(friendSymbols)
                nameScopes.removeLast()
                table.popScope()
                result.addAll(lowered)
            } else {
                // A statement's own pending work must not fall into a block
                // nested inside it, so each body starts from an empty pair.
                val outerBefore = pendingBefore.toList()
                val outerAfter = pendingAfter.toList()
                pendingBefore.clear()
                pendingAfter.clear()
                val lowered = lowerStmt(stmt)
                result.addAll(pendingBefore)
                result.add(lowered)
                result.addAll(pendingAfter)
                pendingBefore.clear()
                pendingBefore.addAll(outerBefore)
                pendingAfter.clear()
                pendingAfter.addAll(outerAfter)
            }
        }

        friendNameScope = savedFriendScope
        return result
    }

    /**
     * Applies the implicit copy a `Copy` value gets when it is used by value.
     *
     * `var b = a` on a `Copy` pack has to leave `a` and `b` independent - that
     * is what `Copy` means. A primitive already copies because it is passed by
     * value; an aggregate is a pointer, so it needs one made.
     *
     * Only a *named* value is copied. A temporary has no other owner, so there
     * is nothing to duplicate away from.
     */
    private fun withImplicitCopy(source: Expr, lowered: IrExpr): IrExpr {
        if (source !is Expr.Identifier) return lowered
        val name = (lowered.type as? IrType.Named)?.name ?: return lowered
        if (!table.conformsTo(name, "Copy")) return lowered
        return IrExpr.Call("__isolated", listOf(lowered), lowered.type)
    }

    /**
     * The in-place call a `op=` should become, or null to keep the desugaring.
     *
     * `a += b` reaches the lowerer as `a = a + b` (the parser has no types to
     * decide with), with the original operator kept on the statement. When the
     * receiver's type declares `oper+=` this rebuilds the call it asked for, so
     * an in-place operator is not silently replaced by build-and-assign.
     */
    private fun inPlaceCompound(
        compoundOp: TokenType?,
        desugared: Expr,
        targetType: IrType?,
    ): IrStmt? {
        val op = compoundOp ?: return null
        val named = targetType as? IrType.Named ?: return null
        val binary = desugared as? Expr.Binary ?: return null
        val member = compoundAssignName(op) ?: return null
        val receiver = lowerExpr(binary.left)
        val operand = lowerExpr(binary.right)
        val mangled = table.lookupOperator(named.name, member, operandKeyOf(operand.type)) ?: return null
        val func = table.lookupFunction(mangled) ?: return null
        return IrStmt.ExprStmt(IrExpr.Call(mangled, listOf(receiver, operand), func.returnType))
    }

    /** `PLUS` → `oper+=`, and the rest of the compound-assignment family. */
    private fun compoundAssignName(op: TokenType): String? = when (op) {
        TokenType.PLUS -> "oper+="
        TokenType.MINUS -> "oper-="
        TokenType.STAR -> "oper*="
        TokenType.SLASH -> "oper/="
        TokenType.PERCENT -> "oper%="
        TokenType.AMP -> "oper&="
        TokenType.PIPE -> "oper|="
        TokenType.CARET -> "oper^="
        TokenType.SHIFT_LEFT -> "oper<<="
        TokenType.SHIFT_RIGHT -> "oper>>="
        else -> null
    }

    /**
     * Iterator-backed `for … with index` loops are represented as a reset plus
     * `while`, so their ordinal must be advanced even when the body continues.
     * Rewrite only continues targeting this generated loop; nested loops own
     * their own continue statements.
     */
    private fun rewriteContinuesForIndex(stmts: List<IrStmt>, index: String, label: String?): List<IrStmt> =
        stmts.map { stmt -> rewriteContinueForIndex(stmt, index, label) }

    private fun rewriteContinueForIndex(stmt: IrStmt, index: String, label: String?): IrStmt {
        val increment = IrStmt.Assignment(
            index,
            IrExpr.Binary(
                IrExpr.Var(index, IrType.Int),
                IrBinaryOp.ADD,
                IrExpr.IntLiteral(1),
                IrType.Int,
            ),
        )
        return when (stmt) {
            is IrStmt.Continue -> if (stmt.label == null || stmt.label == label) {
                IrStmt.Scope(listOf(increment, stmt))
            } else stmt
            // A nested loop consumes its own continue statements.
            is IrStmt.While, is IrStmt.For, is IrStmt.ForEach, is IrStmt.Loop -> stmt
            is IrStmt.If -> stmt.copy(
                thenBranch = rewriteContinuesForIndex(stmt.thenBranch, index, label),
                elseBranch = stmt.elseBranch?.let { rewriteContinuesForIndex(it, index, label) },
            )
            is IrStmt.Scope -> stmt.copy(body = rewriteContinuesForIndex(stmt.body, index, label))
            is IrStmt.When -> stmt.copy(
                branches = stmt.branches.map { branch ->
                    branch.copy(body = rewriteContinuesForIndex(branch.body, index, label))
                },
                elseBranch = stmt.elseBranch?.let { rewriteContinuesForIndex(it, index, label) },
            )
            is IrStmt.Try -> stmt.copy(
                body = rewriteContinuesForIndex(stmt.body, index, label),
                catchBody = stmt.catchBody?.let { rewriteContinuesForIndex(it, index, label) },
            )
            else -> stmt
        }
    }

    private fun lowerStmt(stmt: Stmt): IrStmt {
        return when (stmt) {
            // Injection removes every body import; the validator rejects any left.
            is Stmt.Import -> IrStmt.Scope(emptyList())
            is Stmt.VarDecl -> {
                val init = expecting((stmt.type as? TypeAnnotation.Explicit)?.ref) {
                    withImplicitCopy(
                        stmt.initializer,
                        literalAtDeclaredType(stmt.initializer, typeAnnotationOrNull(stmt.type))
                            ?: coerceToFloat(lowerExpr(stmt.initializer), typeAnnotationOrNull(stmt.type)),
                    )
                }
                declareRef(stmt.name, stmt.type, stmt.initializer)
                val type = resolveTypeAnnotation(stmt.type, init)
                val mangled = registerName(stmt.name)
                table.defineVariable(VariableSymbol(stmt.name, type, mutable = true))
                if (init is IrExpr.EnumLiteral) knownEnumValues[mangled] = init else knownEnumValues.remove(mangled)
                IrStmt.VarDecl(mangled, type, init, valueMutable = stmt.valueMutable, lazy = stmt.lazy)
            }
            is Stmt.FinDecl -> {
                val init = expecting((stmt.type as? TypeAnnotation.Explicit)?.ref) {
                    withImplicitCopy(
                        stmt.initializer,
                        literalAtDeclaredType(stmt.initializer, typeAnnotationOrNull(stmt.type))
                            ?: coerceToFloat(lowerExpr(stmt.initializer), typeAnnotationOrNull(stmt.type)),
                    )
                }
                declareRef(stmt.name, stmt.type, stmt.initializer)
                val type = resolveTypeAnnotation(stmt.type, init)
                val mangled = registerName(stmt.name)
                table.defineVariable(VariableSymbol(stmt.name, type, mutable = false))
                if (init is IrExpr.EnumLiteral) knownEnumValues[mangled] = init else knownEnumValues.remove(mangled)
                if (stmt.lazy) registerLazyReactiveDependencies(mangled, init)
                IrStmt.FinDecl(mangled, type, init, lazy = stmt.lazy)
            }
            is Stmt.LetDecl -> {
                val init = expecting((stmt.type as? TypeAnnotation.Explicit)?.ref) {
                    withImplicitCopy(
                        stmt.initializer,
                        literalAtDeclaredType(stmt.initializer, typeAnnotationOrNull(stmt.type))
                            ?: coerceToFloat(lowerExpr(stmt.initializer), typeAnnotationOrNull(stmt.type)),
                    )
                }
                declareRef(stmt.name, stmt.type, stmt.initializer)
                val type = resolveTypeAnnotation(stmt.type, init)
                val mangled = registerName(stmt.name)
                table.defineVariable(VariableSymbol(stmt.name, type, mutable = false))
                if (init is IrExpr.EnumLiteral) knownEnumValues[mangled] = init else knownEnumValues.remove(mangled)
                if (stmt.lazy) registerLazyReactiveDependencies(mangled, init)
                IrStmt.LetDecl(mangled, type, init, lazy = stmt.lazy)
            }
            is Stmt.DeepInlineBlock -> error("DeepInlineBlock should have been resolved by CTCE before IR generation")
            is Stmt.NoInline -> lowerStmt(stmt.stmt)
            is Stmt.InlineBlock -> error("InlineBlock should have been resolved by CTCE before IR generation")
            is Stmt.InlineFor -> error("InlineFor should have been resolved by CTCE before IR generation")
            is Stmt.InlineFin -> error("InlineFin should have been resolved by CTCE before IR generation")
            is Stmt.InlineLet -> error("InlineLet should have been resolved by CTCE before IR generation")
            is Stmt.InlineVar -> error("InlineVar should have been resolved by CTCE before IR generation")
            is Stmt.InlineAssignment -> error("InlineAssignment should have been resolved by CTCE before IR generation")
            is Stmt.Assignment -> {
                val name = resolveName(stmt.name)
                // `v += x` where `v`'s type declares an in-place `oper+=`: call it
                // instead of the `v = v + x` the parser desugared to. A `Matrix`
                // that adds into itself allocates nothing; without this its
                // declared operator would never run.
                inPlaceCompound(stmt.compoundOp, stmt.value, table.lookupVariable(stmt.name)?.type)
                    ?.let { return it }
                val value = coerceToFloat(
                    lowerExpr(stmt.value),
                    table.lookupVariable(stmt.name)?.type ?: IrType.Any,
                )
                knownEnumValues.remove(name)
                IrStmt.Assignment(name, value)
            }
            is Stmt.Exchange -> {
                val left = lowerExpr(stmt.left)
                val right = lowerExpr(stmt.right)
                for (place in listOf(left, right)) if (place is IrExpr.Var) knownEnumValues.remove(place.name)
                IrStmt.Exchange(left, right)
            }
            is Stmt.IndexAssign -> {
                // `p.*[i] = v` writes the i-th slot; see [bufferPointerTarget].
                val target = lowerExpr(bufferPointerTarget(stmt.target) ?: stmt.target)
                val tt = target.type
                // User-defined index-assign operator (`oper[]=`) on a struct → Type_indexSet(self, i, v).
                if (tt is IrType.Named) {
                    val mangled = table.lookupMethod(tt.name, "indexSet")
                    if (mangled != null) {
                        // The operator's parameters read the receiver's arguments,
                        // so `m[3] = 3.5` on a `HashMap<Int, Double>` passes a
                        // Double, not the literal's own width.
                        val typed = instantiateMember(table, tt, table.lookupFunction(mangled)!!)
                        val index = coerceToFloat(lowerExpr(stmt.index), typed.params.getOrNull(1)?.second ?: IrType.Any)
                        val value = coerceToFloat(lowerExpr(stmt.value), typed.params.getOrNull(2)?.second ?: IrType.Any)
                        return IrStmt.ExprStmt(IrExpr.Call(mangled, listOf(target, index, value), IrType.Unit))
                    }
                }
                val index = lowerExpr(stmt.index)
                val element = when (tt) {
                    is IrType.Array -> tt.element
                    is IrType.Pointer -> tt.inner
                    is IrType.Map -> tt.value
                    else -> IrType.Any
                }
                val value = coerceToFloat(lowerExpr(stmt.value), element)
                IrStmt.IndexAssign(target, index, value)
            }
            is Stmt.DerefAssign -> {
                val target = lowerExpr(stmt.target)
                val value = lowerExpr(stmt.value)
                IrStmt.ExprStmt(IrExpr.Call("__derefAssign", listOf(target, value), IrType.Unit))
            }
            is Stmt.MemberAssign -> {
                val target = autoDerefMemberTarget(lowerExpr(stmt.target), stmt.name, method = false)
                val owner = target.type as? IrType.Named
                val field = owner?.let { table.lookupStruct(it.name) }?.field(stmt.name)
                // A field declared as a type parameter holds the target's argument
                // for it, as the matching member read is typed.
                val fieldType = field?.let { owner.args.getOrNull(it.typeParamIndex) ?: it.type }
                val value = coerceToFloat(lowerExpr(stmt.value), fieldType ?: IrType.Any)
                IrStmt.MemberAssign(target, stmt.name, value)
            }
            is Stmt.Return -> IrStmt.Return(
                stmt.value?.let { expecting(currentReturnRef) { coerceToFloat(lowerExpr(it), currentReturnType) } },
            )
            is Stmt.ExprStmt -> IrStmt.ExprStmt(lowerExpr(stmt.expr))
            is Stmt.If -> {
                val cond = lowerExpr(stmt.condition)
                val thenBranch = lowerScopedBody(stmt.thenBranch)
                val elseBranch = stmt.elseBranch?.let { lowerScopedBody(it) }
                IrStmt.If(cond, thenBranch, elseBranch)
            }
            is Stmt.InlineIf -> error(
                "line ${stmt.line}: 'inline if' condition was not decidable at compile time" +
                    (currentTraceOwner?.let { " in '$it'" } ?: ""),
            )
            is Stmt.DeepInlineIf -> error("DeepInlineIf should have been resolved by CTCE before IR generation")
            is Stmt.Scope -> {
                table.pushScope()
                pushNameScope()
                val stmts = lowerBody(stmt.body)
                popNameScope()
                table.popScope()
                IrStmt.Scope(stmts)
            }
            is Stmt.Assert -> {
                val cond = lowerExpr(stmt.condition)
                val msg = lowerExpr(stmt.message)
                IrStmt.Assert(cond, msg)
            }
            is Stmt.Trace -> {
                val level = lowerExpr(stmt.level ?: defaultTraceLevel(stmt.line))
                val displayLevel = (level as? IrExpr.Var)?.let { knownEnumValues[it.name] } ?: level
                val msg = lowerExpr(stmt.message)
                if (stmt.liftBody) {
                    liftTrace(level, displayLevel, msg, table.lookupEnum("LogLevel").orEmpty())
                } else {
                    IrStmt.Trace(
                        level,
                        msg,
                        table.lookupEnum("LogLevel").orEmpty(),
                        direct = true,
                        showLevel = stmt.explicitLevel,
                        displayLevel = displayLevel,
                    )
                }
            }
            is Stmt.While -> {
                val cond = lowerExpr(stmt.condition)
                table.pushScope()
                pushNameScope()
                val body = lowerBody(stmt.body)
                popNameScope()
                table.popScope()
                IrStmt.While(cond, body, stmt.label)
            }
            is Stmt.For -> {
                val range = stmt.iterable as? Expr.Range
                if (range != null) {
                    val start = lowerExpr(range.from)
                    val end = lowerExpr(range.to)
                    // Range iteration requires the bound type to declare the range operator
                    // (e.g. `bridge impl oper .. for Int`); otherwise it is rejected.
                    val rangeTypeName = start.type.toString()
                    val operName = if (range.descending) "oper>.." else "oper.."
                    if (table.lookupMethod(rangeTypeName, operName) == null) {
                        val sym = if (range.descending) ">.." else ".."
                        error("type '$rangeTypeName' does not support the range operator '$sym' (declare 'impl oper $sym for $rangeTypeName')")
                    }
                    val step = stmt.step?.let { lowerExpr(it) }
                    table.pushScope()
                    pushNameScope()
                    val counter = registerName(stmt.name)
                    table.defineVariable(VariableSymbol(stmt.name, IrType.Int, mutable = true))
                    val index = stmt.indexName?.let { name ->
                        val registered = registerName(name)
                        table.defineVariable(VariableSymbol(name, IrType.Int, mutable = false))
                        registered
                    }
                    val body = lowerBody(stmt.body)
                    popNameScope()
                    table.popScope()
                    IrStmt.For(
                        counter,
                        start,
                        end,
                        range.inclusive,
                        body,
                        step = step,
                        descending = range.descending,
                        label = stmt.label,
                        indexName = index,
                    )
                } else {
                    // For-in over a non-range iterable (array, flow, channel): for-each.
                    val iterable = lowerExpr(stmt.iterable)
                    // An iterator walks itself: `for row in query` positions it,
                    // asks whether there is a row, and binds the one there is.
                    // This is how anything implementing `Iterator` is walked -
                    // `loop` is for repeating, not for iterating.
                    val iteratorElement = (iterable.type as? IrType.Named)?.name?.let { owner ->
                        table.lookupMethod(owner, "next")
                            ?.let { table.lookupFunction(it) }
                            ?.takeIf { table.lookupMethod(owner, "hasNext") != null }
                            ?.returnType
                    }
                    if (iteratorElement != null) {
                        // Walked here rather than as a `ForEach`, whose element
                        // comes from a collection this is not one of.
                        table.pushScope()
                        pushNameScope()
                        val row = registerName(stmt.name)
                        table.defineVariable(VariableSymbol(stmt.name, iteratorElement, mutable = false))
                        val index = stmt.indexName?.let { name ->
                            val registered = registerName(name)
                            table.defineVariable(VariableSymbol(name, IrType.Int, mutable = false))
                            registered
                        }
                        val bind = IrStmt.VarDecl(
                            row,
                            iteratorElement,
                            iteratorCall(iterable, "next", iteratorElement),
                        )
                        val body = lowerBody(stmt.body).let { lowered ->
                            if (index == null) lowered else rewriteContinuesForIndex(lowered, index, stmt.label)
                        }
                        popNameScope()
                        table.popScope()
                        val reset = IrStmt.ExprStmt(iteratorCall(iterable, "reset", IrType.Unit))
                        val cond = iteratorCall(iterable, "hasNext", IrType.Bool)
                        val indexDecl = index?.let {
                            IrStmt.VarDecl(it, IrType.Int, IrExpr.IntLiteral(0), valueMutable = true)
                        }
                        val indexIncrement = index?.let {
                            IrStmt.Assignment(
                                it,
                                IrExpr.Binary(
                                    IrExpr.Var(it, IrType.Int),
                                    IrBinaryOp.ADD,
                                    IrExpr.IntLiteral(1),
                                    IrType.Int,
                                ),
                            )
                        }
                        IrStmt.Scope(listOfNotNull(reset, indexDecl, IrStmt.While(cond, listOf(bind) + body + listOfNotNull(indexIncrement), stmt.label)))
                    } else {
                    val elemType = when (val type = iterable.type) {
                        is IrType.Array -> type.element
                        is IrType.Set -> type.element
                        else -> IrType.Any
                    }
                    table.pushScope()
                    pushNameScope()
                    val elem = registerName(stmt.name)
                    table.defineVariable(VariableSymbol(stmt.name, elemType, mutable = false))
                    val savedElementRef = declaredRefs[stmt.name]
                    val elementRef = elementRefOf(declaredRefOf(stmt.iterable))
                    if (elementRef == null) declaredRefs.remove(stmt.name) else declaredRefs[stmt.name] = elementRef
                    val index = stmt.indexName?.let { name ->
                        val registered = registerName(name)
                        table.defineVariable(VariableSymbol(name, IrType.Int, mutable = false))
                        registered
                    }
                    val body = lowerBody(stmt.body)
                    if (savedElementRef == null) declaredRefs.remove(stmt.name) else declaredRefs[stmt.name] = savedElementRef
                    popNameScope()
                    table.popScope()
                    IrStmt.ForEach(elem, iterable, body, indexName = index)
                    }
                }
            }
            is Stmt.Loop -> {
                table.pushScope()
                pushNameScope()
                val body = lowerBody(stmt.body)
                popNameScope()
                table.popScope()
                if (stmt.iterable != null) {
                    // `loop iterable { body }` → iterable.reset(); while iterable.hasNext() { body }
                    val iter = lowerExpr(stmt.iterable)
                    val reset = IrStmt.ExprStmt(iteratorCall(iter, "reset", IrType.Unit))
                    val cond = iteratorCall(iter, "hasNext", IrType.Bool)
                    IrStmt.Scope(listOf(reset, IrStmt.While(cond, body, stmt.label)))
                } else {
                    IrStmt.Loop(body, stmt.label)
                }
            }
            is Stmt.Break -> IrStmt.Break(stmt.label)
            is Stmt.Continue -> IrStmt.Continue(stmt.label)
            is Stmt.Defer -> IrStmt.Defer(lowerScopedBody(stmt.body), stmt.onFail, stmt.suppress)
            is Stmt.RemDecl -> {
                val init = lowerExpr(stmt.initializer)
                val type = resolveTypeAnnotation(stmt.type, init)
                val mangled = registerName(stmt.name)
                table.defineVariable(
                    VariableSymbol(
                        stmt.name,
                        type,
                        mutable = stmt.binding.nameRebindable,
                        valueMutable = stmt.binding.valueMutable,
                    ),
                )
                reactiveNames.add(stmt.name)
                val lifetime = when (stmt.kind) {
                    ReactiveKind.REMEMBER -> IrStmt.ReactiveLifetime.REMEMBER
                    ReactiveKind.RETAIN -> IrStmt.ReactiveLifetime.RETAIN
                    ReactiveKind.PRESERVE -> IrStmt.ReactiveLifetime.PRESERVE
                }
                when (stmt.binding) {
                    BindingKind.VAR -> IrStmt.VarDecl(mangled, type, init, lifetime, valueMutable = true)
                    BindingKind.VAL -> IrStmt.VarDecl(mangled, type, init, lifetime, valueMutable = false)
                    BindingKind.LET -> IrStmt.LetDecl(mangled, type, init, lifetime)
                    BindingKind.FIN -> IrStmt.FinDecl(mangled, type, init, lifetime)
                }
            }
            is Stmt.Effect -> {
                if (stmt.deferred) {
                    IrStmt.Defer(lowerEffectStatements(stmt.body))
                } else {
                    val loweredBody = lowerEffectStatements(stmt.body)
                    val automatic = stmt.dependencies == null
                    val dependencies = stmt.dependencies
                        ?.flatMap(::dependencyNames)
                        ?.toSet()
                        ?: automaticEffectDependencies(loweredBody)
                    IrStmt.Effect(
                        id = nextEffectId++,
                        dependencies = dependencies.map(::resolveName),
                        body = loweredBody,
                        automatic = automatic,
                        // A condition's dependencies are the names it reads, which
                        // `dependencies` above already collected from the same
                        // expression - so a conditional effect subscribes to what
                        // it asks about, not to what its body happens to touch.
                        condition = stmt.condition?.let(::lowerExpr),
                    )
                }
            }
            is Stmt.UsingContext -> {
                val values = stmt.values.map(::lowerExpr)
                contextualValues.addLast(ContextFrame(values, prefersMembers = true))
                val body = try {
                    lowerScopedBody(stmt.body)
                } finally {
                    contextualValues.removeLast()
                }
                IrStmt.Scope(body)
            }
            is Stmt.Yield -> IrStmt.Yield(lowerExpr(stmt.value))
            is Stmt.When -> {
                val scrutinee = lowerExpr(stmt.scrutinee)
                val scrutineeSlot = (scrutinee.type as? IrType.Named)?.name?.takeIf { table.lookupSlot(it) != null }
                val branches = stmt.branches.map { b ->
                    var slotBindings: List<Pair<String, IrType>>? = null
                    val irPatterns = b.patterns.map { pat ->
                        val parts = SlotPatterns.parts(pat, scrutineeSlot)
                        val slotVariants = parts?.let { table.lookupSlot(it.slot) }
                        if (parts != null && slotVariants != null) {
                            val variant = slotVariants.find { it.first == parts.variant }
                            val bindNames = parts.bindings.map { (it as Expr.Identifier).name }
                            if (variant != null) slotBindings = bindNames.zip(variant.second)
                            IrExpr.SlotPattern(parts.slot, parts.variant, bindNames, variant?.second ?: emptyList())
                        } else {
                            lowerExpr(pat)
                        }
                    }
                    val body = if (slotBindings != null) {
                        table.pushScope()
                        pushNameScope()
                        for ((name, type) in slotBindings) {
                            val mangled = registerName(name)
                            table.defineVariable(VariableSymbol(name, type))
                        }
                        try {
                            lowerBody(b.body)
                        } finally {
                            popNameScope()
                            table.popScope()
                        }
                    } else {
                        lowerScopedBody(b.body)
                    }
                    IrWhenBranch(irPatterns, body)
                }
                val elseBranch = stmt.elseBranch?.let { lowerScopedBody(it) }
                IrStmt.When(scrutinee, branches, elseBranch)
            }
            is Stmt.Throw -> IrStmt.Throw(lowerExpr(stmt.value))
            is Stmt.Panic -> {
                if (stmt.inlinePanic) error("inline panic should have been resolved by CTCE before IR generation")
                // Panic has no successful result. Marking it Nothing (rather
                // than Unit) preserves bottom-type flow through the backends.
                IrStmt.ExprStmt(IrExpr.Call("__panic", listOf(lowerExpr(stmt.message)), IrType.Nothing))
            }
            is Stmt.Try -> {
                table.pushScope()
                pushNameScope()
                val body = lowerBody(stmt.body)
                popNameScope()
                table.popScope()
                var catchIrName: String? = null
                val catchBody = if (stmt.catchBody != null) {
                    table.pushScope()
                    pushNameScope()
                    if (stmt.catchName != null) {
                        catchIrName = registerName(stmt.catchName)
                        table.defineVariable(VariableSymbol(stmt.catchName, IrType.Any, mutable = false))
                    }
                    val cb = lowerBody(stmt.catchBody)
                    popNameScope()
                    table.popScope()
                    cb
                } else null
                IrStmt.Try(body, catchIrName, catchBody)
            }
            is Stmt.InlineAssert -> error("InlineAssert should have been resolved by CTCE before IR generation")
            is Stmt.InlineTrace -> error("InlineTrace should have been resolved by CTCE before IR generation")
        }
    }

    /**
     * `Byte(4)` and `Double(2.5)` - the literal, at the width the type names.
     *
     * The named type has to be one written as a literal (`bridge pack
     * Byte(IntLiteral)`) and the single argument has to be that literal.
     * Anything else is an ordinary call and is lowered as one.
     */
    private fun literalAtWidth(expr: Expr.Call): IrExpr? {
        if (expr.receiver != null || expr.args.size != 1) return null
        // The name may be an alias: `Long` is `Int<64>`, and what says it is
        // written as a literal is the type the alias names.
        val named = IrType.aliases[expr.callee] as? TypeRef.Named
        val kind = table.lookupStruct(named?.name ?: expr.callee)?.literalKind ?: return null
        val type = IrType.aliases[expr.callee]?.let { IrType.resolve(it) }
            ?: if (IrType.isPrimitiveName(expr.callee)) IrType.fromName(expr.callee) else return null
        return when (val argument = expr.args.single()) {
            is Expr.IntLiteral ->
                if (kind == Literals.INT || kind == Literals.UINT) {
                    IrExpr.IntLiteral(argument.value, type, argument.text)
                } else {
                    null
                }
            is Expr.DoubleLiteral -> if (kind == Literals.REAL) IrExpr.DoubleLiteral(argument.value, type, argument.text) else null
            else -> null
        }
    }

    private fun defaultTraceLevel(line: Int): Expr {
        val first = table.lookupEnum("LogLevel")?.firstOrNull() ?: "Debug"
        return Expr.Member(Expr.Identifier("LogLevel", line), first, line)
    }

    /**
     * Routes an interpolated pack through its `Display`.
     *
     * A pack says how it prints by implementing `Display`, and this is where
     * that implementation is reached - not the backend's own idea of what the
     * value looks like: `format` builds the
     * `Formatter`, hands it to `display`, and returns what was written.
     *
     * Everything else formats as it always did.
     */
    private fun displayed(value: IrExpr): IrExpr {
        val named = value.type as? IrType.Named ?: return value
        if (table.lookupStruct(named.name) == null) return value
        if (!table.conformsTo(named.name, "Display")) return value
        // The generated member (`DisplayDeriver`) that builds the `Formatter`,
        // hands it to `display` and returns what was written. Generated rather
        // than made an intrinsic so it is an ordinary call every backend
        // already knows how to lower.
        val render = table.lookupMethod(named.name, "__displayString") ?: return value
        return IrExpr.Call(render, listOf(value), IrType.String)
    }

    /** Converts an enum value to its source-level qualified spelling. */
    private fun stringifyEnum(expr: IrExpr): IrExpr {
        val enumName = (expr.type as? IrType.Named)?.name
            ?.takeIf { table.lookupEnum(it) != null }
            ?: return expr
        return IrExpr.StringTemplate(
            listOf(
                IrExpr.IrTemplatePart.Literal("$enumName."),
                IrExpr.IrTemplatePart.Expr(IrExpr.EnumToString(expr)),
            ),
        )
    }

    /** Rewrites an expression tree, replacing one value with another. */
    private fun replaceExpr(expr: IrExpr, from: IrExpr, to: IrExpr): IrExpr {
        if (expr == from) return to
        return when (expr) {
            is IrExpr.Unary -> expr.copy(operand = replaceExpr(expr.operand, from, to))
            is IrExpr.IncDec -> expr.copy(target = replaceExpr(expr.target, from, to) as IrExpr.Var)
            is IrExpr.Binary -> expr.copy(left = replaceExpr(expr.left, from, to), right = replaceExpr(expr.right, from, to))
            is IrExpr.Call -> expr.copy(args = expr.args.map { replaceExpr(it, from, to) })
            is IrExpr.ArrayLiteral -> expr.copy(elements = expr.elements.map { replaceExpr(it, from, to) })
            is IrExpr.MapLit -> expr.copy(entries = expr.entries.map { replaceExpr(it.first, from, to) to replaceExpr(it.second, from, to) })
            is IrExpr.SetLit -> expr.copy(elements = expr.elements.map { replaceExpr(it, from, to) })
            is IrExpr.Index -> expr.copy(target = replaceExpr(expr.target, from, to), index = replaceExpr(expr.index, from, to))
            is IrExpr.Member -> expr.copy(target = replaceExpr(expr.target, from, to))
            is IrExpr.MethodCall -> expr.copy(
                target = replaceExpr(expr.target, from, to),
                args = expr.args.map { replaceExpr(it, from, to) },
            )
            is IrExpr.StructCtor -> expr.copy(args = expr.args.map { replaceExpr(it, from, to) })
            is IrExpr.StringTemplate -> expr.copy(parts = expr.parts.map { part ->
                if (part is IrExpr.IrTemplatePart.Expr) part.copy(expr = replaceExpr(part.expr, from, to)) else part
            })
            is IrExpr.TupleLit -> expr.copy(elements = expr.elements.map { replaceExpr(it, from, to) })
            is IrExpr.VariantLit -> expr.copy(elements = expr.elements.map { replaceExpr(it, from, to) })
            is IrExpr.TupleAccess -> expr.copy(target = replaceExpr(expr.target, from, to))
            is IrExpr.CatchExpr -> expr.copy(
                expr = replaceExpr(expr.expr, from, to),
                fallback = replaceExpr(expr.fallback, from, to),
            )
            is IrExpr.IfExpr -> expr.copy(
                condition = replaceExpr(expr.condition, from, to),
                thenExpr = replaceExpr(expr.thenExpr, from, to),
                elseExpr = replaceExpr(expr.elseExpr, from, to),
            )
            is IrExpr.NumCast -> expr.copy(value = replaceExpr(expr.value, from, to))
            is IrExpr.EnumToString -> expr.copy(value = replaceExpr(expr.value, from, to))
            is IrExpr.Await -> expr.copy(value = replaceExpr(expr.value, from, to))
            is IrExpr.Spread -> expr.copy(array = replaceExpr(expr.array, from, to))
            is IrExpr.Lambda,
            is IrExpr.IntLiteral, is IrExpr.DoubleLiteral, is IrExpr.StringLiteral,
            is IrExpr.EnumLiteral, is IrExpr.BoolLiteral, is IrExpr.CharLiteral,
            IrExpr.UnitLiteral,
            is IrExpr.Var, is IrExpr.SlotPattern -> expr
        }
    }

    /** Collects free runtime values captured by a lifted trace body. */
    private fun collectTraceCaptures(expr: IrExpr, captures: LinkedHashMap<String, IrType>) {
        when (expr) {
            is IrExpr.Var -> if (expr.name != "level" && expr.name !in captures) {
                captures[expr.name] = expr.type
            }
            is IrExpr.Unary -> collectTraceCaptures(expr.operand, captures)
            is IrExpr.IncDec -> collectTraceCaptures(expr.target, captures)
            is IrExpr.Binary -> {
                collectTraceCaptures(expr.left, captures)
                collectTraceCaptures(expr.right, captures)
            }
            is IrExpr.Call -> expr.args.forEach { collectTraceCaptures(it, captures) }
            is IrExpr.ArrayLiteral -> expr.elements.forEach { collectTraceCaptures(it, captures) }
            is IrExpr.MapLit -> expr.entries.forEach {
                collectTraceCaptures(it.first, captures)
                collectTraceCaptures(it.second, captures)
            }
            is IrExpr.SetLit -> expr.elements.forEach { collectTraceCaptures(it, captures) }
            is IrExpr.Index -> {
                collectTraceCaptures(expr.target, captures)
                collectTraceCaptures(expr.index, captures)
            }
            is IrExpr.Member -> collectTraceCaptures(expr.target, captures)
            is IrExpr.MethodCall -> {
                collectTraceCaptures(expr.target, captures)
                expr.args.forEach { collectTraceCaptures(it, captures) }
            }
            is IrExpr.StructCtor -> expr.args.forEach { collectTraceCaptures(it, captures) }
            is IrExpr.StringTemplate -> expr.parts.forEach {
                if (it is IrExpr.IrTemplatePart.Expr) collectTraceCaptures(it.expr, captures)
            }
            is IrExpr.TupleLit -> expr.elements.forEach { collectTraceCaptures(it, captures) }
            is IrExpr.VariantLit -> expr.elements.forEach { collectTraceCaptures(it, captures) }
            is IrExpr.TupleAccess -> collectTraceCaptures(expr.target, captures)
            is IrExpr.CatchExpr -> {
                collectTraceCaptures(expr.expr, captures)
                collectTraceCaptures(expr.fallback, captures)
            }
            is IrExpr.IfExpr -> {
                collectTraceCaptures(expr.condition, captures)
                collectTraceCaptures(expr.thenExpr, captures)
                collectTraceCaptures(expr.elseExpr, captures)
            }
            is IrExpr.NumCast -> collectTraceCaptures(expr.value, captures)
            is IrExpr.EnumToString -> collectTraceCaptures(expr.value, captures)
            is IrExpr.Await -> collectTraceCaptures(expr.value, captures)
            is IrExpr.Spread -> collectTraceCaptures(expr.array, captures)
            is IrExpr.Lambda,
            is IrExpr.IntLiteral, is IrExpr.DoubleLiteral, is IrExpr.StringLiteral,
            is IrExpr.EnumLiteral, is IrExpr.BoolLiteral, is IrExpr.CharLiteral,
            IrExpr.UnitLiteral,
            is IrExpr.SlotPattern -> Unit
        }
    }

    /** Lifts a trace body into a named function and leaves a typed call at the trace site. */
    private fun liftTrace(
        level: IrExpr,
        displayLevel: IrExpr,
        message: IrExpr,
        variants: List<String>,
    ): IrStmt.Trace {
        val owner = currentTraceOwner ?: "module"
        val index = traceLambdaIndices.getOrPut(owner) { 0 }
        traceLambdaIndices[owner] = index + 1
        val functionName = "__${owner}_lambda$index"
        val levelParam = IrExpr.Var("level", IrType.Named("LogLevel"))

        var bodyExpr = replaceExpr(message, level, levelParam)
        if (bodyExpr.type != IrType.String) {
            bodyExpr = IrExpr.StringTemplate(listOf(IrExpr.IrTemplatePart.Expr(stringifyEnum(bodyExpr))))
        }

        val captures = linkedMapOf<String, IrType>()
        collectTraceCaptures(bodyExpr, captures)
        val captureParams = mutableListOf<Pair<String, IrType>>()
        val captureArgs = mutableListOf<IrExpr>()
        for ((name, type) in captures) {
            val paramName = "__capture_$name"
            bodyExpr = replaceExpr(bodyExpr, IrExpr.Var(name, type), IrExpr.Var(paramName, type))
            captureParams += paramName to type
            captureArgs += IrExpr.Var(name, type)
        }

        generatedTraceFunctions += IrFunction(
            functionName,
            listOf("level" to IrType.Named("LogLevel")) + captureParams,
            IrType.String,
            listOf(IrStmt.Return(bodyExpr)),
        )
        val call = IrExpr.Call(functionName, listOf(level) + captureArgs, IrType.String)
        return IrStmt.Trace(level, call, variants, displayLevel = displayLevel)
    }

    // -- Witnesses -------------------------------------------------------------
    //
    // A type parameter bounded by `Hash` or `Equal` carries a descriptor: an
    // `Int` naming the concrete type, held in a hidden field of a bounded pack
    // and passed as a hidden parameter of a bounded function. `key.hash` and
    // `a == b` on such a parameter call a dispatch function that switches on it
    // and applies the concrete type's own operation. See [Witnesses].

    /** Pack name → the witness bounds on its type parameters. */
    private val packWitnessBounds = HashMap<String, Map<String, Set<String>>>()

    /** Pack name → its type parameters, in order. */
    private val packTypeParams = HashMap<String, List<String>>()

    /** Pack name → field name → the field's declared type. */
    private val packFieldRefs = HashMap<String, Map<String, TypeRef>>()

    /** Function name → the witness bounds it takes a descriptor for, in parameter order. */
    private val functionWitnessBounds = HashMap<String, Map<String, Set<String>>>()

    /** The concrete type each descriptor names, in descriptor order. */
    private val witnessTypes = LinkedHashMap<String, IrType>()

    /** Descriptor → the operations some use of it needs, so each dispatch covers only types that have its operation. */
    private val witnessNeeds = HashMap<Int, MutableSet<String>>()

    /** The dispatch functions lowered code calls, emitted even when no type reaches them. */
    private val witnessDispatchers = linkedSetOf<String>()

    /** A call to dispatch function [name], which is then emitted. */
    private fun witnessCall(name: String, args: List<IrExpr>, type: IrType): IrExpr.Call {
        witnessDispatchers.add(name)
        return IrExpr.Call(name, args, type)
    }

    /** In the code being lowered: bounded type parameter → its bounds. */
    private var witnessBounds: Map<String, Set<String>> = emptyMap()

    /** In the code being lowered: bounded type parameter → the expression holding its descriptor. */
    private var witnessSources: Map<String, IrExpr> = emptyMap()

    /** The type parameters in scope, bounded or not. */
    private var scopeTypeParams: Set<String> = emptySet()

    /** In the code being lowered: a parameter's or local's declared type, by source name. */
    private var declaredRefs: MutableMap<String, TypeRef> = mutableMapOf()

    /** The declared type an initializer or returned value is lowered against. */
    private var expectedRef: TypeRef? = null

    /** The declared return type of the function being lowered. */
    private var currentReturnRef: TypeRef? = null

    private fun initWitnesses(program: Program) {
        packWitnessBounds.clear(); packTypeParams.clear(); packFieldRefs.clear()
        functionWitnessBounds.clear(); witnessTypes.clear(); witnessNeeds.clear(); witnessDispatchers.clear()
        witnessBounds = emptyMap(); witnessSources = emptyMap(); scopeTypeParams = emptySet()
        declaredRefs = mutableMapOf(); expectedRef = null; currentReturnRef = null
        for (item in program.items) {
            if (item !is TopLevel.Pack || item.isBridge) continue
            packTypeParams[item.name] = item.typeParams
            packFieldRefs[item.name] = item.fields.associate { it.name to it.type }
            Witnesses.bounds(item).takeIf { it.isNotEmpty() }?.let { packWitnessBounds[item.name] = it }
        }
        for (decl in program.functions) {
            // A heterogeneous pack has one descriptor per element, rather
            // than the single descriptor used for a homogeneous parameter.
            // Pack-wide predicates are checked by the resolver; they do not
            // request a singular descriptor for the whole pack.
            val own = Witnesses.bounds(decl.whereClause, decl.typeParams)
                .filterKeys { it != decl.variadicParam }
            // A pack's type-scoped members (`literal`, lifted as `Pack__member`)
            // take its parameters as their own, and its bounds with them.
            val owner = packWitnessBounds[decl.name.substringBeforeLast("__", "")].orEmpty()
                .filterKeys { it in decl.typeParams }
            val merged = LinkedHashMap<String, Set<String>>()
            for (param in decl.typeParams) {
                val specs = own[param].orEmpty() + owner[param].orEmpty()
                if (specs.isNotEmpty()) merged[param] = specs
            }
            if (merged.isNotEmpty()) functionWitnessBounds[decl.name] = merged
        }
    }

    /** Runs [lower] with the witness scope of one function or method, restoring the enclosing one. */
    private fun <T> inWitnessScope(
        bounds: Map<String, Set<String>>,
        sources: Map<String, IrExpr>,
        typeParams: Set<String>,
        refs: Map<String, TypeRef>,
        returnRef: TypeRef?,
        lower: () -> T,
    ): T {
        val saved = listOf(witnessBounds, witnessSources, scopeTypeParams, declaredRefs, expectedRef, currentReturnRef)
        witnessBounds = bounds
        witnessSources = sources
        scopeTypeParams = typeParams
        declaredRefs = refs.toMutableMap()
        expectedRef = null
        currentReturnRef = returnRef
        try {
            return lower()
        } finally {
            @Suppress("UNCHECKED_CAST")
            witnessBounds = saved[0] as Map<String, Set<String>>
            @Suppress("UNCHECKED_CAST")
            witnessSources = saved[1] as Map<String, IrExpr>
            @Suppress("UNCHECKED_CAST")
            scopeTypeParams = saved[2] as Set<String>
            @Suppress("UNCHECKED_CAST")
            declaredRefs = saved[3] as MutableMap<String, TypeRef>
            expectedRef = saved[4] as TypeRef?
            currentReturnRef = saved[5] as TypeRef?
        }
    }

    /** Records what a local was declared as, or what its initializer says it holds. */
    private fun declareRef(name: String, type: TypeAnnotation, initializer: Expr) {
        val ref = (type as? TypeAnnotation.Explicit)?.ref ?: declaredRefOf(initializer)
        if (ref != null) declaredRefs[name] = ref else declaredRefs.remove(name)
    }

    /** Runs [lower] with [ref] as the type a construction without written arguments builds. */
    private fun <T> expecting(ref: TypeRef?, lower: () -> T): T {
        val saved = expectedRef
        expectedRef = ref
        try {
            return lower()
        } finally {
            expectedRef = saved
        }
    }

    private fun stripRef(ref: TypeRef?): TypeRef? = when (ref) {
        is TypeRef.Reference -> stripRef(ref.inner)
        else -> ref
    }

    /** What a pointer, array or `Array<T>` holds. */
    private fun elementRefOf(ref: TypeRef?): TypeRef? = when (val r = stripRef(ref)) {
        is TypeRef.Pointer -> r.inner
        is TypeRef.Array -> r.element
        is TypeRef.Named -> r.args.singleOrNull()?.takeIf { r.name == Intrinsics.ARRAY }
        else -> null
    }

    /**
     * The type [expr] was declared with, as far as declarations say: a
     * parameter or local, a field of a pack value, an element of either. Enough
     * to tell a value of a bounded type parameter, which lowers to an erased
     * slot and so cannot be told from its IR type.
     */
    private fun declaredRefOf(expr: Expr): TypeRef? = when (expr) {
        is Expr.Identifier -> declaredRefs[expr.name]
        is Expr.Grouping -> declaredRefOf(expr.expr)
        is Expr.Member -> fieldRefOf(expr.target, expr.name)
        is Expr.Index -> elementRefOf(declaredRefOf(expr.target))
        else -> null
    }

    private fun fieldRefOf(target: Expr, field: String): TypeRef? {
        val owner = stripRef(declaredRefOf(target)) as? TypeRef.Named ?: return null
        val ref = packFieldRefs[owner.name]?.get(field) ?: return null
        val params = packTypeParams[owner.name].orEmpty()
        if (owner.args.size != params.size) return ref
        return substituteTypeParams(ref, params.zip(owner.args).toMap())
    }

    private fun substituteTypeParams(ref: TypeRef, by: Map<String, TypeRef>): TypeRef = when (ref) {
        is TypeRef.Named -> if (ref.args.isEmpty() && ref.name in by) by.getValue(ref.name)
            else ref.copy(args = ref.args.map { substituteTypeParams(it, by) })
        is TypeRef.Pointer -> ref.copy(inner = substituteTypeParams(ref.inner, by))
        is TypeRef.Reference -> ref.copy(inner = substituteTypeParams(ref.inner, by))
        is TypeRef.Array -> ref.copy(element = substituteTypeParams(ref.element, by))
        is TypeRef.Tuple -> ref.copy(elements = ref.elements.map { substituteTypeParams(it, by) })
        else -> ref
    }

    /** `Array<T>` written as a named type, in the form a variadic parameter has. */
    private fun arrayForm(ref: TypeRef): TypeRef =
        if (ref is TypeRef.Named && ref.name == Intrinsics.ARRAY && ref.args.size == 1) TypeRef.Array(ref.args.single()) else ref

    /** The bounded type parameter [expr] holds a value of, if it holds one. */
    private fun witnessParamOf(expr: Expr): String? {
        val ref = (stripRef(declaredRefOf(expr)) as? TypeRef.Named)?.takeIf { it.args.isEmpty() } ?: return null
        if (ref.name in witnessBounds && ref.name !in witnessSources) {
            throw WitnessError(
                "line ${expr.line}: element-wise witness dispatch for heterogeneous pack '${ref.name}' " +
                    "is not supported yet",
            )
        }
        return ref.name.takeIf { it in witnessSources }
    }

    /**
     * Where [param] is bound to in [pattern], matched against [actual]:
     * `Array<(K, V)>` against `Array<(String, Int)>` binds `K` to `String`.
     */
    private fun bindingIn(pattern: TypeRef, actual: TypeRef, param: String): TypeRef? {
        val p = arrayForm(stripRef(pattern) ?: return null)
        val a = arrayForm(stripRef(actual) ?: return null)
        if (p is TypeRef.Named && p.args.isEmpty() && p.name == param) return a
        return when {
            p is TypeRef.Named && a is TypeRef.Named && p.args.size == a.args.size ->
                p.args.zip(a.args).firstNotNullOfOrNull { (x, y) -> bindingIn(x, y, param) }
            p is TypeRef.Array && a is TypeRef.Array -> bindingIn(p.element, a.element, param)
            p is TypeRef.Pointer && a is TypeRef.Pointer -> bindingIn(p.inner, a.inner, param)
            p is TypeRef.Tuple && a is TypeRef.Tuple && p.elements.size == a.elements.size ->
                p.elements.zip(a.elements).firstNotNullOfOrNull { (x, y) -> bindingIn(x, y, param) }
            else -> null
        }
    }

    /** The descriptor for [type], registering it on first use. */
    private fun descriptorOf(type: IrType, needs: Set<String>, what: String, line: Int): IrExpr {
        if (type == IrType.Any) throw WitnessError("line $line: cannot tell which type $what is here; write it")
        val key = type.toString()
        var id = witnessTypes.keys.indexOf(key)
        if (id < 0) {
            witnessTypes[key] = type
            id = witnessTypes.size - 1
        }
        witnessNeeds.getOrPut(id) { linkedSetOf() }.addAll(needs)
        return IrExpr.IntLiteral(id.toLong(), IrType.Int)
    }

    /**
     * The descriptor for a type argument written as [arg]: the enclosing
     * code's own descriptor when it names one of its type parameters, else the
     * concrete type's.
     */
    private fun descriptorOf(arg: TypeRef?, needs: Set<String>, what: String, line: Int): IrExpr {
        val ref = stripRef(arg) ?: throw WitnessError("line $line: cannot tell which type $what is here; write it")
        if (ref is TypeRef.Named && ref.args.isEmpty() && ref.name in scopeTypeParams) {
            val source = witnessSources[ref.name]
            val has = witnessBounds[ref.name].orEmpty()
            val spec = Witnesses.named(needs)
            if (source == null || !has.containsAll(needs)) {
                throw WitnessError(
                    "line $line: $what must be $spec, and '${ref.name}' is not declared to be; " +
                        "add 'where ${ref.name}: $spec'",
                )
            }
            return source
        }
        return descriptorOf(resolveType(ref, scopeTypeParams), needs, what, line)
    }

    /**
     * Fills a bounded pack's descriptor fields from the type arguments it is
     * built with: written (`HashSet<T>()`) or those of the declared type it is
     * built for (`var m: HashMap<K, V> = .()`).
     */
    private fun withDescriptors(pack: String, fieldNames: List<String>, values: List<IrExpr>, written: List<TypeRef>, line: Int): List<IrExpr> {
        val bounds = packWitnessBounds[pack] ?: return values
        val params = packTypeParams[pack].orEmpty()
        val args = written.ifEmpty {
            (stripRef(expectedRef) as? TypeRef.Named)?.takeIf { it.name == pack }?.args.orEmpty()
        }
        val filled = values.toMutableList()
        for ((param, needs) in bounds) {
            val index = fieldNames.indexOf(Witnesses.slot(param))
            if (index < 0) continue
            val arg = args.getOrNull(params.indexOf(param))
                ?: throw WitnessError("line $line: cannot tell which type '$param' of '$pack' is here; write the type arguments")
            filled[index] = descriptorOf(arg, needs, "'$param' of '$pack'", line)
        }
        return filled
    }

    /**
     * The descriptors a call to bounded [callee] passes, in its parameters'
     * order: from the written type arguments, else from what its arguments
     * were declared as, else from what the resolver inferred.
     */
    private fun descriptorArgs(callee: String, call: Expr.Call, typeParams: List<String>, arguments: List<IrExpr>): List<IrExpr> {
        val bounds = functionWitnessBounds[callee] ?: return emptyList()
        val decl = functionDecls[callee]
        return bounds.map { (param, needs) ->
            val index = typeParams.indexOf(param)
            val written = call.typeArgs.getOrNull(index)?.takeUnless { it.isHole }
            // A variadic parameter's element type is matched against each argument it takes.
            val fromArguments = decl?.params?.withIndex()?.firstNotNullOfOrNull { (i, p) ->
                val element = if (p.variadic) (arrayForm(p.type) as? TypeRef.Array)?.element ?: p.type else p.type
                val taken = if (p.variadic) call.args.drop(i) else listOfNotNull(call.args.getOrNull(i))
                taken.firstNotNullOfOrNull { arg -> declaredRefOf(arg)?.let { bindingIn(element, it, param) } }
            }
            val inferred = call.inferredTypeArgs?.getOrNull(index)
            val ref = written ?: fromArguments ?: inferred
            if (ref != null) descriptorOf(ref, needs, "'$param' of '$callee'", call.line)
            else {
                // A heterogeneous tail prevents a single inferredTypeArgs
                // list, but its fixed prefix still has ordinary concrete
                // types. Reuse those lowered arguments, without evaluating
                // or lowering their expressions again.
                val fixedIndex = decl?.params?.indexOfFirst { parameter ->
                    !parameter.variadic && (stripRef(parameter.type) as? TypeRef.Named)
                        ?.let { it.name == param && it.args.isEmpty() } == true
                } ?: -1
                val type = arguments.getOrNull(fixedIndex)?.type ?: IrType.Any
                descriptorOf(type, needs, "'$param' of '$callee'", call.line)
            }
        }
    }

    /** The descriptors a literal built for [target] passes to its bounded factory. */
    private fun factoryDescriptorArgs(factory: String, target: IrType.Named, line: Int): List<IrExpr> {
        val bounds = functionWitnessBounds[factory] ?: return emptyList()
        val params = functionDecls[factory]?.typeParams.orEmpty()
        val expected = (stripRef(expectedRef) as? TypeRef.Named)?.args.orEmpty()
        return bounds.map { (param, needs) ->
            val index = params.indexOf(param)
            val concrete = target.args.getOrNull(index)?.takeUnless { it == IrType.Any }
            if (concrete != null) descriptorOf(concrete, needs, "'$param' of this literal", line)
            else descriptorOf(expected.getOrNull(index), needs, "'$param' of this literal", line)
        }
    }

    /**
     * `__witness_hash(d, v)` and `__witness_equal(d, a, b)`: one branch per
     * type a descriptor names, each binding the erased slot at that type and
     * applying its own `hash` or `==`, as ordinary code on it would. A
     * descriptor nothing set (`-1`) panics rather than guessing.
     */
    private fun witnessDispatchFunctions(): List<IrTopLevel> {
        if (witnessDispatchers.isEmpty()) return emptyList()
        fun dispatch(name: String, need: String, operands: Int, result: IrType, apply: (List<Expr>) -> List<IrStmt>): IrTopLevel.Func {
            table.pushScope()
            pushNameScope()
            try {
                val descriptor = registerName("__descriptor")
                table.defineVariable(VariableSymbol("__descriptor", IrType.Int, mutable = false))
                val slots = (0 until operands).map { i ->
                    val mangled = registerName("__slot$i")
                    table.defineVariable(VariableSymbol("__slot$i", IrType.Any, mutable = false))
                    mangled
                }
                val branches = witnessTypes.values.withIndex().filter { (id, _) ->
                    need in witnessNeeds[id].orEmpty()
                }.map { (id, type) ->
                    val locals = (0 until operands).map { i ->
                        val source = "__typed${id}_$i"
                        val mangled = registerName(source)
                        table.defineVariable(VariableSymbol(source, type, mutable = false))
                        source to mangled
                    }
                    val bind = locals.mapIndexed { i, (_, mangled) ->
                        IrStmt.FinDecl(mangled, type, IrExpr.Var(slots[i], IrType.Any))
                    }
                    IrStmt.If(
                        IrExpr.Binary(IrExpr.Var(descriptor, IrType.Int), IrBinaryOp.EQ, IrExpr.IntLiteral(id.toLong()), IrType.Bool),
                        bind + apply(locals.map { (source, _) -> Expr.Identifier(source, 0) }),
                        null,
                    )
                }
                val missing = IrStmt.ExprStmt(
                    IrExpr.Call("__panic", listOf(IrExpr.StringLiteral("no $need witness for this generic value")), IrType.Nothing),
                )
                return IrTopLevel.Func(IrFunction(
                    name,
                    listOf(descriptor to IrType.Int) + slots.map { it to IrType.Any },
                    result,
                    branches + missing + IrStmt.Return(defaultValueForType(result)),
                ))
            } finally {
                popNameScope()
                table.popScope()
            }
        }
        fun returning(expr: Expr) = listOf(IrStmt.Return(lowerExpr(expr)))
        return listOf(
            dispatch(WITNESS_HASH, Witnesses.HASH, 1, IrType.ULong) { (v) -> returning(Expr.Member(v, "hash", 0)) },
            dispatch(WITNESS_EQUAL, Witnesses.EQUAL, 2, IrType.Bool) { (a, b) -> returning(Expr.Binary(a, TokenType.EQUAL_EQUAL, b, 0)) },
            // -1, 0 or 1 from the type's own `<`, asked both ways.
            dispatch(WITNESS_COMPARE, Witnesses.ORDER, 2, IrType.Int) { (a, b) ->
                listOf(
                    IrStmt.If(lowerExpr(Expr.Binary(a, TokenType.LESS, b, 0)), listOf(IrStmt.Return(IrExpr.IntLiteral(-1))), null),
                    IrStmt.If(lowerExpr(Expr.Binary(b, TokenType.LESS, a, 0)), listOf(IrStmt.Return(IrExpr.IntLiteral(1))), null),
                    IrStmt.Return(IrExpr.IntLiteral(0)),
                )
            },
        ).filter { it.function.name in witnessDispatchers }
    }

    /** The factory that runs a declared `ctor` of the given arity. */
    private fun ctorFactoryName(typeName: String, arity: Int): String = ctorFactorySymbol(typeName, arity)

    /**
     * A literal the resolver matched to its target's factory: one direct call,
     * the elements evaluated left to right into the factory's variadic slots at
     * their physical width - erased for a generic factory.
     */
    /**
     * An associative literal matched to its target's factory: one direct call
     * receiving a `(key, value)` entry per pair, each key evaluated before its
     * value and each entry before the next. Entries are built at the factory's
     * physical entry layout, erased for a generic factory.
     */
    private fun lowerEntriesFactoryLiteral(expr: Expr.MapLit, factoryName: String): IrExpr {
        val target = resolveType(expr.contextualType!!) as IrType.Named
        val factory = table.lookupFunction(factoryName) ?: error("literal factory '$factoryName' is not registered")
        val slots = factory.params.single().second as IrType.Array
        val (keyType, valueType) = (literalFactoryTypes(table, target, factory).first as IrType.Tuple).elements
        val entries = expr.entries.map { (key, value) ->
            IrExpr.TupleLit(
                listOf(coerceToFloat(lowerExpr(key), keyType), coerceToFloat(lowerExpr(value), valueType)),
                slots.element,
            )
        }
        return IrExpr.Call(
            factoryName,
            listOf(IrExpr.ArrayLiteral(entries, IrType.Array(slots.element, entries.size.toLong()))) +
                factoryDescriptorArgs(factoryName, target, expr.line),
            target,
        )
    }

    private fun lowerFactoryLiteral(expr: Expr.ArrayLiteral, factoryName: String): IrExpr {
        val target = resolveType(expr.contextualType!!) as IrType.Named
        val factory = table.lookupFunction(factoryName) ?: error("literal factory '$factoryName' is not registered")
        val slots = factory.params.single().second as IrType.Array
        val (element, _) = literalFactoryTypes(table, target, factory)
        val elements = expr.elements.map { coerceToFloat(lowerExpr(it), element) }
        return IrExpr.Call(
            factoryName,
            listOf(IrExpr.ArrayLiteral(elements, IrType.Array(slots.element, elements.size.toLong()))) +
                factoryDescriptorArgs(factoryName, target, expr.line),
            target,
        )
    }

    /**
     * The pointer behind `p.*` when what it points at cannot itself be indexed.
     *
     * Indexing that deref means reading a slot of the buffer, so the pointer is
     * what gets indexed. A pointer to something indexable in its own right - an
     * `Array<T>*` - is dereferenced and then indexed, as written.
     */
    private fun bufferPointerTarget(target: Expr): Expr? {
        val deref = target as? Expr.Deref ?: return null
        val pointer = lowerExpr(deref.target).type as? IrType.Pointer ?: return null
        return if (isIndexableType(pointer.inner)) null else deref.target
    }

    /** Whether `x[i]` means something for a value of this type on its own. */
    private fun isIndexableType(type: IrType): Boolean = when (type) {
        is IrType.Array, is IrType.Map, is IrType.Set, is IrType.Pointer -> true
        IrType.String -> true
        is IrType.Named -> table.lookupMethod(type.name, "index") != null
        else -> false
    }

    /** What `alloc .(…)` builds; see the resolver's twin. */
    private fun allocatedConstruction(value: Expr): Expr {
        val member = value as? Expr.InferredMember ?: return value
        val args = member.ctorArgs?.takeIf { member.name.isEmpty() } ?: return value
        // A bridge pack cannot be constructed, so `.()` into one is not a
        // construction: `T*` erases to `Any*`, and what the pointer holds is the
        // run of values, not one `Any`.
        val owner = table.lookupInferredMember(member.line, member.column)
        if (owner != null && table.lookupStruct(owner)?.isBridge == false) return value
        return Expr.ArrayLiteral(args, member.line, member.column, member.length)
    }

    /** What one slot of an allocated repetition holds; see the resolver's twin. */
    private fun repeatedElementType(construct: Expr): IrType? {
        val name = when (construct) {
            is Expr.InferredMember -> table.lookupInferredMember(construct.line, construct.column)
            is Expr.Call -> construct.callee
            else -> null
        } ?: return null
        // A type parameter names no type of its own; see the resolver's twin.
        return when {
            IrType.isPrimitiveName(name) -> IrType.fromName(name)
            table.lookupStruct(name) != null -> IrType.Named(name)
            else -> IrType.Any
        }
    }

    /**
     * `.(args) * count` - `count` values built by the same construction.
     *
     * An array is *filled*: `count` slots holding the element default, which is
     * what the `Array_fill` intrinsic every backend already implements does.
     * Anything else runs the `ctor` that declared it takes a repetition, with
     * the count arriving as its last argument - the same shape the declaration
     * put it in.
     *
     * Returns null when the left side names no type, leaving `f() * 2` to be the
     * multiplication it has always been.
     */
    private fun lowerRepeatedConstruction(construct: Expr, count: Expr): IrExpr? {
        // `alloc .(…) * count` - a buffer of `count` slots. One intrinsic, so
        // every backend gets the behaviour without a second spelling for it.
        if (construct is Expr.Alloc) {
            val element = repeatedElementType(construct.value) ?: return null
            return IrExpr.Call(
                "__allocBuffer",
                listOf(lowerExpr(count)),
                IrType.Pointer(element, mutable = construct.mutable),
            )
        }
        val call = construct as? Expr.Call ?: return null
        val struct = table.lookupStruct(call.callee) ?: return null
        val loweredCount = lowerExpr(count)
        if (struct.name == "Array") {
            // `var xs: Array<Int> = .() * 4` writes the element on the
            // declaration, not on the call; without it every slot is an `Any`.
            val elementRef = call.typeArgs.firstOrNull()
                ?: (stripRef(expectedRef) as? TypeRef.Named)?.takeIf { it.name == "Array" }?.args?.firstOrNull()
            val element = elementRef?.let { resolveType(it) } ?: IrType.Any
            return IrExpr.Call(Intrinsics.ARRAY_FILL, listOf(loweredCount), IrType.Array(element))
        }
        val args = call.args.map { lowerExpr(it) }
        val factory = ctorFactorySymbol(call.callee, args.size + 1, repeated = true)
        if (table.lookupFunction(factory) == null) return null
        return IrExpr.Call(factory, args + loweredCount, IrType.Named(call.callee))
    }

    /**
     * The symbol of a member declared in an `impl` on an aggregate builtin, or
     * null when [type] is not an aggregate or declares no such member.
     */
    private fun aggregateMemberSymbol(type: IrType, name: String): String? {
        val owner = when (type) {
            is IrType.Array -> "Array"
            is IrType.Map -> "Map"
            is IrType.Set -> "Set"
            is IrType.Tuple -> "Tuple"
            else -> return null
        }
        // A type-level constant (`impl Array { bridge fin size }`) is bodyless
        // too: the backend reads it from the value, so it stays a member access.
        if (table.lookupTypeStatic(owner, name) != null) return null
        val mangled = table.lookupMethod(owner, name) ?: return null
        // A bodyless member (`bridge prop size`) has no function to call - the
        // backend reads it from the value's own representation - so it stays a
        // member access and only its *declaration* is what makes it legal.
        if (table.lookupFunction(mangled)?.isBodyless != false) return null
        return mangled
    }

    /**
     * One step of an iterator walk, bound to the function that implements it.
     *
     * `for row in query` and `loop query { … }` are lowered here rather than
     * parsed, so nothing downstream resolves what they produce. An
     * `IrExpr.MethodCall` left standing is a name looked up at runtime, which
     * the interpreter can do and a native backend cannot - it reached
     * LlvmCodegen as `; method .hasNext - not lowered` and a default value, so
     * the loop never advanced and the query silently yielded nothing.
     *
     * Falls back to the unresolved form when the receiver's type has no such
     * method, which leaves the previous behaviour exactly where it was.
     */
    private fun iteratorCall(receiver: IrExpr, method: String, fallbackType: IrType): IrExpr {
        val owner = (receiver.type as? IrType.Named)?.name
        val mangled = owner?.let { table.lookupMethod(it, method) }
            ?: return IrExpr.MethodCall(receiver, method, emptyList(), fallbackType)
        val returnType = table.lookupFunction(mangled)?.returnType ?: fallbackType
        return IrExpr.Call(mangled, listOf(receiver), returnType)
    }

    /**
     * The call `expr` reads as a member of a value a `with` block opened, or null.
     *
     * `using c { bump() }` is `c.bump()`, and a scope-qualified call reaches its
     * contextual receiver the same way: `yield(1)` names the member `yield`,
     * and the scope only says where it was declared, not what it is called on.
     *
     * This is tried before construction, so a member and a pack may share a name:
     * the `with` block names a receiver on purpose, and inside it that member is
     * what was meant.
     */
    /** The nearest contextual value of [type], or null when none is in scope. */
    private fun contextualValueOf(type: IrType): IrExpr? =
        contextualValues.asReversed().flatMap { it.values }.firstOrNull { it.type == type }

    private fun contextualCall(expr: Expr.Call, deliberateOnly: Boolean = false): IrExpr? {
        val name = expr.callee.substringAfterLast("__")
        val frames = contextualValues.asReversed().filter { !deliberateOnly || it.prefersMembers }
        for (frame in frames) {
        for (ctx in frame.values) {
            val ct = ctx.type
            if (ct !is IrType.Named) continue
            // Mirrors what the resolver typed: a member answers before a free
            // function only when it is the call's own type or a `with` receiver.
            if (!frame.prefersMembers && ct.name != currentReceiverType) continue
            // The member the call is written inside must not shadow itself.
            if (ct.name == currentReceiverType && name == currentMemberName) continue
            val mangled = table.lookupMethod(ct.name, name) ?: continue
            val func = table.lookupFunction(mangled) ?: continue
            if (func.isBodyless || func.memberCallStyle == MemberCallStyle.PROPERTY) continue
            // Reached as a member, so its arguments bind as a member's do -
            // defaults, named arguments and a trailing block all apply, exactly
            // as they would had the receiver been written out.
            return IrExpr.Call(mangled, listOf(ctx) + lowerMethodArguments(expr.args, func, mangled), func.returnType)
        }
        }
        return null
    }

    private fun lowerExpr(expr: Expr): IrExpr {
        return when (expr) {
            // The resolver already decided which type the dot meant; lowering
            // reads that answer rather than deriving it again from a context it
            // no longer has.
            is Expr.InferredMember -> lowerInferredMember(
                expr,
                table.lookupInferredMember(expr.line, expr.column)
                    ?: error("line ${expr.line}: '.${expr.name}' was never resolved to a type"),
            )
            // Only a macro arm taking `[...${key: value}]` can consume one, and the
            // expander does so before lowering. Reaching here means no arm matched.
            is Expr.MapEntryArg -> error(
                "line ${expr.line}: 'key: value' is only an argument of a macro that takes " +
                    "'[...\${key: value}]' - no macro arm matched this invocation",
            )
            is Expr.IntLiteral -> IrExpr.IntLiteral(expr.value, IrType.defaultInt, expr.text)
            is Expr.DoubleLiteral -> IrExpr.DoubleLiteral(expr.value, IrType.defaultFloat, expr.text)
            is Expr.StringLiteral -> IrExpr.StringLiteral(expr.value)
            is Expr.BoolLiteral -> IrExpr.BoolLiteral(expr.value)
            is Expr.NullLiteral -> IrExpr.Var("__null", IrType.Any)
            is Expr.NamedArg -> lowerExpr(expr.value)
            is Expr.Cast -> {
                val inner = lowerExpr(expr.expr)
                val target = resolveType(expr.targetType)
                // Numeric casts convert the value. Pointer-carrying values
                // (String, arrays, packs, Any, pointers) cast to/from integer
                // types for FFI (`window as Long`); native backends lower these
                // to ptrtoint/inttoptr. All other casts (interface upcasts,
                // Any) are representation-preserving no-ops.
                fun isNumericish(t: IrType) = IrType.isNumeric(t) || t == IrType.Char
                fun isPointerish(t: IrType) =
                    t == IrType.String || t == IrType.Any || t is IrType.Array || t is IrType.Map || t is IrType.Set ||
                        t is IrType.Named || t is IrType.Pointer || t is IrType.Nullable || t is IrType.Tuple
                val innerType = inner.type
                // A user-declared `oper as<U> T&.(): U` takes precedence over the
                // built-in conversions: a type that says how it converts should be
                // asked, rather than reinterpreted. Each cast form asks its own
                // member, so `as`, `as?` and `as*` never stand in for one another.
                val castMember = when (expr.kind) {
                    CastKind.STATIC -> "operas"
                    CastKind.DYNAMIC -> "operas?"
                    CastKind.REINTERPRET -> "operas*"
                }
                // `impl Cast<Fahrenheit> for Celsius` registers its member under
                // the target, so a type may convert to more than one thing; the
                // bare name is the older target-agnostic `oper as<U>`.
                val targetKey = (target as? IrType.Named)?.name ?: target.toString()
                val userCast = (innerType as? IrType.Named)?.let {
                    table.lookupMethod(it.name, "$castMember@$targetKey")
                        ?: table.lookupMethod(it.name, castMember)
                }
                if (userCast != null) {
                    val declared = table.lookupFunction(userCast)?.returnType
                    // The operator states what it returns; a checked cast that did not
                    // say so still yields `T?`, which is what `as?` means.
                    val result = declared?.takeUnless { it == IrType.Any || it == IrType.Unit }
                        ?: if (expr.kind == CastKind.DYNAMIC) IrType.Nullable(target) else target
                    return IrExpr.Call(userCast, listOf(inner), result)
                }
                when {
                    // `x as? T` / `dyncast<T>(x)` - runtime-checked downcast to `T?`:
                    // the value if it is a `T`, otherwise null.
                    expr.kind == CastKind.DYNAMIC ->
                        IrExpr.Call(
                            "__dynCast",
                            listOf(inner, IrExpr.StringLiteral((expr.targetType as? TypeRef.Named)?.name ?: expr.targetType.displayName())),
                            IrType.Nullable(target),
                        )
                    // A custom `impl as String` conversion takes priority over the
                    // default stringify (so `label as String` calls the user method).
                    target == IrType.String && innerType is IrType.Named &&
                        table.lookupMethod(innerType.name, "asString") != null -> {
                        val mangled = table.lookupMethod(innerType.name, "asString")!!
                        IrExpr.Call(mangled, listOf(inner), IrType.String)
                    }
                    expr.kind == CastKind.STATIC && target == IrType.String &&
                        innerType is IrType.Named && table.lookupEnum(innerType.name) != null ->
                        stringifyEnum(inner)
                    // `x as String` / `cast<String>(x)` - converting cast: stringify
                    // the value via the single-part string-template machinery (equivalent
                    // to "${x}"), which every backend already supports. `as*` (reinterpret)
                    // never stringifies.
                    expr.kind == CastKind.STATIC && target == IrType.String ->
                        IrExpr.StringTemplate(listOf(IrExpr.IrTemplatePart.Expr(displayed(inner))))
                    target == innerType -> inner
                    // Upcast a concrete `pack` to a spec it implements: mark it as a
                    // representation coercion so native backends can box it into a fat
                    // pointer for dynamic dispatch. The interpreter treats a NumCast
                    // of a non-numeric as identity (the value keeps its `__type`).
                    expr.kind != CastKind.DYNAMIC && target is IrType.Named && innerType is IrType.Named &&
                        table.lookupSpec(target.name)?.isDecorator == false &&
                        table.conformsTo(innerType.name, target.name) ->
                        IrExpr.NumCast(inner, target)
                    isNumericish(target) && isNumericish(innerType) -> IrExpr.NumCast(inner, target)
                    // pointer → integer / integer → pointer (FFI)
                    isNumericish(target) && isPointerish(innerType) -> IrExpr.NumCast(inner, target)
                    isPointerish(target) && isNumericish(innerType) -> IrExpr.NumCast(inner, target)
                    else -> inner
                }
            }
            is Expr.IsCheck -> {
                val inner = lowerExpr(expr.expr)
                // `x is Int` carries the scope-qualified name; the runtime
                // compares against the type's own name, which is what a
                // declaration is registered under.
                val typeName = table.canonicalTypeName(expr.typeName)
                IrExpr.Call("__isCheck", listOf(inner, IrExpr.StringLiteral(typeName)), IrType.Bool)
            }
            is Expr.InlineForArgs ->
                error("'inline for' argument reached IR generation at line ${expr.line}")
            is Expr.InCheck -> {
                // `x in xs` - membership, the same shape a `contains` call has.
                val v = lowerExpr(expr.value)
                val c = lowerExpr(expr.collection)
                val call = IrExpr.MethodCall(c, "contains", listOf(v), IrType.Bool)
                if (expr.negated) IrExpr.Unary(IrUnaryOp.NOT, call, IrType.Bool) else call
            }
            is Expr.NullCoalesce -> {
                val left = lowerExpr(expr.left)
                val right = lowerExpr(expr.right)
                IrExpr.Call("__nullCoalesce", listOf(left, right), right.type)
            }
            is Expr.SafeMember -> {
                val target = lowerExpr(expr.target)
                IrExpr.Call("__safeMember", listOf(target, IrExpr.StringLiteral(expr.name)), IrType.Any)
            }
            is Expr.CharLiteral -> IrExpr.CharLiteral(expr.value)
            is Expr.Identifier -> {
                // A field default written in terms of an earlier field. What
                // that field holds is decided by the construction, not by the
                // declaration, so the value was bound just before this default
                // was lowered.
                constructionBindings?.get(expr.name)?.let { return it }
                // A member a `using` block opened stands closer than a global of
                // the same name, as the resolver typed it: inside
                // `using self { purge (keys, values) }`, `values` is the field.
                if (table.isGlobalOnly(expr.name)) {
                    contextualValues.asReversed().filter { it.prefersMembers }.forEach { frame ->
                        frame.values.forEach { value ->
                            val owner = (value.type as? IrType.Named)?.let { table.lookupStruct(it.name) }
                            owner?.field(expr.name)?.let { field ->
                                return IrExpr.Member(value, expr.name, field.type)
                            }
                        }
                    }
                }
                val sym = table.lookupVariable(expr.name)
                // `Vec3f::zero` names a member of whatever `Vec3f` aliases, as it did
                // when the resolver typed it.
                val aliased = if (sym == null) throughTypeAlias(expr.name) else null
                val aliasedSym = aliased?.let { table.lookupVariable(it) }
                if (sym != null) {
                    IrExpr.Var(resolveName(expr.name), sym.type)
                } else if (aliasedSym != null) {
                    IrExpr.Var(resolveName(aliased), aliasedSym.type)
                } else if (expr.name == "Unit") {
                    IrExpr.UnitLiteral
                } else {
                    // Implicit self: bare field name in a method reads the
                    // receiver's field.
                    val field = currentReceiverType?.let { table.lookupStruct(it)?.field(expr.name) }
                    if (field != null) {
                        val selfSym = table.lookupVariable("self")
                        if (selfSym != null) {
                            IrExpr.Member(IrExpr.Var(resolveName("self"), selfSym.type), expr.name, field.type)
                        } else {
                            IrExpr.Var(expr.name, IrType.Any)
                        }
                    } else {
                        IrExpr.Var(expr.name, IrType.Any)
                    }
                }
            }
            is Expr.UpperScopeAccess -> {
                val sym = table.lookupVariableInUpperScope(expr.name, expr.depth)!!
                IrExpr.Var(resolveUpperName(expr.name, expr.depth), sym.type)
            }
            is Expr.Unary -> {
                val operand = lowerExpr(expr.operand)
                // The declared operator, if the operand's type has one. Same
                // decision the resolver made, from the same table.
                (operand.type as? IrType.Named)?.let { named ->
                    unaryOverloadName(expr.op)?.let { operName ->
                        table.lookupUnaryOperator(named.name, operName)?.let { mangled ->
                            val func = table.lookupFunction(mangled)!!
                            return IrExpr.Call(mangled, listOf(operand), func.returnType)
                        }
                    }
                }
                val op = when (expr.op) {
                    TokenType.MINUS -> IrUnaryOp.NEG
                    TokenType.BANG -> IrUnaryOp.NOT
                    TokenType.TILDE -> IrUnaryOp.BIT_NOT
                    else -> error("Unknown unary op: ${expr.op}")
                }
                IrExpr.Unary(op, operand, operand.type)
            }
            is Expr.IncDec -> {
                val target = lowerExpr(expr.target)
                val variable = target as? IrExpr.Var
                    ?: error("increment expression target must lower to a variable")
                IrExpr.IncDec(
                    variable,
                    if (expr.op == TokenType.PLUS_PLUS) 1 else -1,
                    expr.prefix,
                )
            }
            is Expr.Binary -> {
                // `.(args) * count` - `count` values built the same way, not a
                // product. The resolver has already said this is a construction.
                asRepeatedConstruction(expr)?.let { (construct, count) ->
                    lowerRepeatedConstruction(construct, count)?.let { return it }
                }
                // Two values of a type parameter bounded by `Equal` compare as
                // the concrete type its descriptor names.
                if (expr.op == TokenType.EQUAL_EQUAL || expr.op == TokenType.BANG_EQUAL) {
                    val param = witnessParamOf(expr.left) ?: witnessParamOf(expr.right)
                    if (param != null && Witnesses.EQUAL in witnessBounds[param].orEmpty()) {
                        val equal = witnessCall(
                            WITNESS_EQUAL,
                            listOf(witnessSources.getValue(param), lowerExpr(expr.left), lowerExpr(expr.right)),
                            IrType.Bool,
                        )
                        return if (expr.op == TokenType.EQUAL_EQUAL) equal
                        else IrExpr.Unary(IrUnaryOp.NOT, equal, IrType.Bool)
                    }
                }
                // Values of a type parameter bounded by `Order` order as the
                // concrete type does: `a < b` is `compare(a, b) < 0`.
                val ordering = when (expr.op) {
                    TokenType.LESS -> IrBinaryOp.LT
                    TokenType.LESS_EQUAL -> IrBinaryOp.LTE
                    TokenType.GREATER -> IrBinaryOp.GT
                    TokenType.GREATER_EQUAL -> IrBinaryOp.GTE
                    else -> null
                }
                if (ordering != null) {
                    val param = witnessParamOf(expr.left) ?: witnessParamOf(expr.right)
                    if (param != null && Witnesses.ORDER in witnessBounds[param].orEmpty()) {
                        val compare = witnessCall(
                            WITNESS_COMPARE,
                            listOf(witnessSources.getValue(param), lowerExpr(expr.left), lowerExpr(expr.right)),
                            IrType.Int,
                        )
                        return IrExpr.Binary(compare, ordering, IrExpr.IntLiteral(0), IrType.Bool)
                    }
                }
                var left = lowerExpr(expr.left)
                var right = lowerExpr(expr.right)
                // Pointer arithmetic: ptr + n, ptr - n, ptr - ptr
                if (left.type is IrType.Pointer && IrType.isInteger(right.type) &&
                    (expr.op == TokenType.PLUS || expr.op == TokenType.MINUS)) {
                    val fn = if (expr.op == TokenType.PLUS) "__ptrAdd" else "__ptrSub"
                    return IrExpr.Call(fn, listOf(left, right), left.type)
                }
                if (IrType.isInteger(left.type) && right.type is IrType.Pointer && expr.op == TokenType.PLUS) {
                    return IrExpr.Call("__ptrAdd", listOf(right, left), right.type)
                }
                if (left.type is IrType.Pointer && right.type is IrType.Pointer && expr.op == TokenType.MINUS) {
                    return IrExpr.Call("__ptrDiff", listOf(left, right), IrType.Int)
                }
                // A primitive left operand may still have a declared operator, but only
                // one that names its operand - see the resolver for why.
                if (left.type !is IrType.Named && IrType.isNumeric(left.type)) {
                    operOverloadName(expr.op)?.let { operName ->
                        val key = operandKeyOf(right.type)
                        if (key != null && key != left.type.toString()) {
                            table.lookupMethod(left.type.toString(), "$operName@$key")?.let { mangled ->
                                val func = table.lookupFunction(mangled)!!
                                return IrExpr.Call(mangled, listOf(left, right), func.returnType)
                            }
                        }
                    }
                }
                // Operator overloading on user types
                if (left.type is IrType.Named) {
                    val lt = left.type as IrType.Named
                    // A declared operator wins; otherwise the comparison family
                    // rewrites (the DIP, §5.4/§5.5). The same decision the
                    // resolver made, from the same routine.
                    when (val plan = comparisonPlan(expr.op, lt.name, operandKeyOf(right.type), table)) {
                        is ComparisonPlan.Direct -> {
                            val func = table.lookupFunction(plan.mangled)!!
                            return IrExpr.Call(plan.mangled, listOf(left, right), func.returnType)
                        }
                        // `a < b` → `(a <=> b).isLess`. The `<=>` call is the
                        // receiver, so it happens once, and which of `Compare` or
                        // `PartialCompare` answers decides what the predicate
                        // means - `NaN` makes all four false without the compiler
                        // knowing anything about NaN.
                        //
                        // The predicate is reached as a direct call rather than a
                        // method call: a fieldless enum has no runtime object to
                        // dispatch on, so its members are ordinary functions over
                        // the value, and the result type of `<=>` is what names
                        // the enum they belong to.
                        is ComparisonPlan.Spaceship -> {
                            val cmp = table.lookupFunction(plan.mangled)!!
                            val threeWay = IrExpr.Call(plan.mangled, listOf(left, right), cmp.returnType)
                            val resultName = (cmp.returnType as? IrType.Named)?.name
                            val predicate = resultName?.let { table.lookupMethod(it, plan.predicate) }
                            if (predicate != null) {
                                return IrExpr.Call(predicate, listOf(threeWay), IrType.Bool)
                            }
                            return IrExpr.MethodCall(threeWay, plan.predicate, emptyList(), IrType.Bool)
                        }
                        is ComparisonPlan.NegatedEquals -> {
                            val func = table.lookupFunction(plan.mangled)!!
                            return IrExpr.Unary(
                                IrUnaryOp.NOT,
                                IrExpr.Call(plan.mangled, listOf(left, right), func.returnType),
                                IrType.Bool,
                            )
                        }
                        null -> Unit
                    }
                    // Legacy same-type named-method overloads.
                    if (left.type == right.type) {
                        val methodName = operatorMethodName(expr.op)
                        if (methodName != null) {
                            val mangled = table.lookupMethod(lt.name, methodName)
                            if (mangled != null) {
                                val func = table.lookupFunction(mangled)!!
                                return IrExpr.Call(mangled, listOf(left, right), func.returnType)
                            }
                        }
                        if (expr.op == TokenType.BANG_EQUAL) {
                            val eqMangled = table.lookupMethod(lt.name, "equals")
                            if (eqMangled != null) {
                                val func = table.lookupFunction(eqMangled)!!
                                return IrExpr.Unary(IrUnaryOp.NOT,
                                    IrExpr.Call(eqMangled, listOf(left, right), func.returnType), IrType.Bool)
                            }
                        }
                    }
                }
                // `a <=> b` on a built-in. There is no three-way machine
                // instruction and no let-binding in the IR, so it lowers to the
                // two tests a hand-written comparison makes. The floating-point
                // form needs the third: a NaN is not less, not greater and not
                // equal, and `Unordered` is the only honest answer.
                if (expr.op == TokenType.SPACESHIP) {
                    val partial = left.type in IrType.floatTypes || right.type in IrType.floatTypes ||
                        left.type == IrType.Any || right.type == IrType.Any
                    val enumName = if (partial) "PartialCompare" else "Compare"
                    val resultType = IrType.Named(enumName)
                    val less = IrExpr.Binary(left, IrBinaryOp.LT, right, IrType.Bool)
                    val greater = IrExpr.Binary(left, IrBinaryOp.GT, right, IrType.Bool)
                    val equal = IrExpr.Binary(left, IrBinaryOp.EQ, right, IrType.Bool)
                    val tail = if (partial) {
                        IrExpr.IfExpr(
                            equal,
                            IrExpr.EnumLiteral(enumName, "Equal"),
                            IrExpr.EnumLiteral(enumName, "Unordered"),
                            resultType,
                        )
                    } else {
                        IrExpr.EnumLiteral(enumName, "Equal")
                    }
                    return IrExpr.IfExpr(
                        less,
                        IrExpr.EnumLiteral(enumName, "Less"),
                        IrExpr.IfExpr(greater, IrExpr.EnumLiteral(enumName, "Greater"), tail, resultType),
                        resultType,
                    )
                }
                val op = lowerBinaryOp(expr.op)
                val type = when (op) {
                    IrBinaryOp.EQ, IrBinaryOp.NEQ,
                    IrBinaryOp.LT, IrBinaryOp.LTE,
                    IrBinaryOp.GT, IrBinaryOp.GTE,
                    IrBinaryOp.AND, IrBinaryOp.OR -> IrType.Bool
                    // Arithmetic widens to the common numeric type (`Int / Double` → Double,
                    // `Byte + Long` → Long) so backends emit one machine type; `*` also
                    // doubles as string repetition.
                    IrBinaryOp.ADD, IrBinaryOp.SUB, IrBinaryOp.MUL, IrBinaryOp.DIV, IrBinaryOp.MOD -> {
                        if (left.type == IrType.String || right.type == IrType.String) IrType.String
                        else numericResultType(left.type, right.type)
                    }
                    else -> left.type // bitwise / shift - keep the left operand type
                }
                if (type == IrType.String) {
                    left = stringifyEnum(left)
                    right = stringifyEnum(right)
                }
                left = coerceToFloat(left, right.type)
                right = coerceToFloat(right, left.type)
                IrExpr.Binary(left, op, right, type)
            }
            is Expr.Call -> {
                // `Array(a, b, c)` - the compiler's own aggregate; see the
                // resolver, which types it the same way and just as early.
                if (expr.callee == Intrinsics.ARRAY && expr.receiver == null) {
                    return lowerExpr(Expr.ArrayLiteral(expr.args, expr.line, expr.column, expr.length))
                }
                // `Byte(4)` - a literal read at a width. The type says it is
                // written as a literal, so what comes out is that literal, at
                // that width, and no call is made at all.
                literalAtWidth(expr)?.let { return it }
                // Value call `receiver(args)` - lower the receiver (a function value)
                // and emit an indirect call carrying it.
                expr.receiver?.let { recv ->
                    val target = lowerExpr(recv)
                    val callable = target.type as? IrType.Function
                    val ret = callable?.ret ?: IrType.Any
                    val args = expr.args.map { lowerExpr(it) } +
                        (callable?.let { contextualArguments(it, expr.args.size) } ?: emptyList())
                    return IrExpr.Call("", args, ret, receiver = target)
                }
                if (expr.callee == "__defaultLogLevel") {
                    val first = table.lookupEnum("LogLevel")?.firstOrNull()
                        ?: error("LogLevel must declare at least one variant")
                    return IrExpr.EnumLiteral("LogLevel", first)
                }
                // Resolve import aliases (`import Scope.Item` maps Item to Scope__Item).
                // `Self(…)` inside an impl builds the type the impl is on.
                val calleeName = if (expr.callee == "Self") currentReceiverType ?: expr.callee else expr.callee
                val actualCallee = table.aliasMap[calleeName] ?: calleeName
                // A pack and a member may share a name, and inside `with` the
                // member is what was meant - the same order the resolver typed.
                contextualCall(expr)?.let { return it }
                val struct = table.lookupStruct(actualCallee) ?: table.lookupStruct(calleeName)
                if (struct != null && struct.isUnion) {
                    // `Value(f: 1.5)` - exactly one member is named, and it is the
                    // one that initializes the shared slot. The checker has already
                    // rejected anything else.
                    val named = expr.args.filterIsInstance<Expr.NamedArg>().firstOrNull()
                    val member = named?.let { arg -> struct.fields.first { it.name == arg.name } }
                        ?: struct.fields.first()
                    val value = named?.value ?: expr.args.firstOrNull()
                    return IrExpr.StructCtor(
                        actualCallee,
                        listOf(member.name),
                        listOf(coerceToFloat(lowerExpr(value ?: Expr.NullLiteral), member.type)),
                        IrType.Named(actualCallee),
                    )
                }
                if (struct != null) {
                    // Named arguments are reordered to field order first; a
                    // positional list already is one. Either way every field
                    // ends up with either what was supplied or its own default.
                    val supplied = if (expr.args.any { it is Expr.NamedArg }) {
                        mapNamedArguments(expr.args, struct.fields.map { it.name })
                    } else {
                        expr.args
                    }
                    val args = withDescriptors(
                        actualCallee,
                        struct.fields.map { it.name },
                        loweredFieldValues(
                            struct,
                            supplied,
                            expr.typeArgs.map { resolveType(it, currentGenericTypeParams) },
                        ),
                        expr.typeArgs,
                        expr.line,
                    )
                    // `ctor .()` is the constructor of a call that writes no
                    // arguments, ahead of one whose parameters all have defaults.
                    // The value is built as any other construction builds it,
                    // then the ctor runs on it.
                    if (expr.args.isEmpty() && receiverOnlyCtorSymbol(actualCallee, table) != null) {
                        val built = IrExpr.StructCtor(
                            actualCallee,
                            struct.fields.map { it.name },
                            args,
                            IrType.Named(actualCallee, expr.typeArgs.map { resolveType(it, currentGenericTypeParams) }),
                        )
                        return IrExpr.Call(ctorRunSymbol(actualCallee), listOf(built), built.type)
                    }
                    // A declared `ctor` of the same arity takes precedence over
                    // filling fields positionally - it is the constructor the
                    // author wrote, and skipping it would leave its work undone.
                    // A named argument may name a later parameter, so binding one
                    // needs every slot - the factory that takes them all.
                    // Mirrors the resolver: any factory found says how many
                    // parameters there are, and the one taking them all binds a
                    // call whose named arguments or trailing block reach past a
                    // parameter left to its default.
                    val probeCtor = (expr.args.size..expr.args.size + 8).firstNotNullOfOrNull {
                        table.lookupFunction(ctorFactoryName(actualCallee, it))
                    // A variadic ctor answers a call wider than its own arity; see
                    // the resolver, which selects the same one.
                    } ?: (expr.args.size downTo 1).firstNotNullOfOrNull { arity ->
                        table.lookupFunction(ctorFactoryName(actualCallee, arity))?.takeIf { it.isVariadic }
                    }
                    val declaredCtor = probeCtor?.let {
                        table.lookupFunction(
                            ctorFactoryName(actualCallee, it.paramNames.size - it.contextualParams),
                        ) ?: it
                    }
                    val ownerType = IrType.Named(actualCallee,
                        expr.typeArgs.map { resolveType(it, currentGenericTypeParams) },
                        expr.typeArgs.map { (it as? TypeRef.Const)?.value })
                    val typedCtor = declaredCtor?.let { instantiateMember(table, ownerType, it) }
                    if (typedCtor != null && actualCallee in packWitnessBounds) {
                        error("'$actualCallee' has a Hash or Equal bound; a ctor with arguments cannot be given its descriptors yet")
                    }
                    if (typedCtor != null) {
                        val physicalCtor = declaredCtor!!
                        val declaredCtor = typedCtor
                        // A ctor that names receivers beyond its own takes them
                        // ahead of the written arguments, read from the scope the
                        // call sits in.
                        // Without one in scope the ctor does not apply, and the
                        // fields are filled as they would be for any other pack -
                        // which is what keeps such a ctor from taking over every
                        // construction of its own type.
                        val scopeArgs = declaredCtor.params.take(declaredCtor.contextualParams)
                            .map { (_, t) -> contextualValueOf(t) }
                        if (scopeArgs.none { it == null }) {
                            // Named and positional arguments bind as they do at any
                            // other call, and a parameter nobody wrote takes the
                            // default the ctor declared for it.
                            val written = declaredCtor.paramNames.drop(declaredCtor.contextualParams)
                            val slots = arrayOfNulls<Expr>(maxOf(written.size, expr.args.size))
                            var ctorArgs = expr.args
                            val trailing = written.size - 1
                            if (ctorArgs.isNotEmpty() && ctorArgs.last() is Expr.Lambda && trailing >= 0 &&
                                declaredCtor.params.getOrNull(
                                    declaredCtor.contextualParams + trailing,
                                )?.second is IrType.Function
                            ) {
                                slots[trailing] = ctorArgs.last()
                                ctorArgs = ctorArgs.dropLast(1)
                            }
                            for (argument in ctorArgs) {
                                if (argument !is Expr.NamedArg) continue
                                slots[written.indexOf(argument.name)] = argument.value
                            }
                            var next = 0
                            for (argument in ctorArgs) {
                                if (argument is Expr.NamedArg) continue
                                while (next < slots.size && slots[next] != null) next++
                                slots[next] = argument
                            }
                            // `.(1, 2, 3)` into `ctor .(...args: T)`: the fixed
                            // parameters take theirs and the rest become the one
                            // array the variadic slot holds, exactly as a variadic
                            // function call packs its own.
                            if (declaredCtor.isVariadic) {
                                val fixed = slots.take(written.size - 1).mapIndexed { i, argument ->
                                    val parameterIndex = declaredCtor.contextualParams + i
                                    val slot = declaredCtor.params[parameterIndex].second
                                    val value = argument ?: declaredCtor.defaults[parameterIndex]
                                        ?: error("'$actualCallee' has no value for a fixed parameter")
                                    coerceToFloat(lowerExpr(value), slot)
                                }
                                val logicalElement = (declaredCtor.params.last().second as IrType.Array).element
                                val rest = slots.drop(written.size - 1).filterNotNull().map {
                                    coerceToFloat(lowerExpr(it), logicalElement)
                                }
                                // Pack the callee's physical slots. A generic ctor
                                // reads erased elements even when the caller knows T.
                                val element = (physicalCtor.params.last().second as IrType.Array).element
                                val packed = IrExpr.ArrayLiteral(rest, IrType.Array(element, rest.size.toLong()))
                                return IrExpr.Call(
                                    declaredCtor.name,
                                    scopeArgs.filterNotNull() + fixed + packed,
                                    declaredCtor.returnType,
                                )
                            }
                            val bound = slots.take(written.size).mapIndexedNotNull { i, argument ->
                                val slot = declaredCtor.params.getOrNull(declaredCtor.contextualParams + i)?.second
                                val value = argument ?: declaredCtor.defaults[declaredCtor.contextualParams + i]
                                if (value == null) {
                                    error("'$actualCallee' has no value for '${written[i]}'")
                                }
                                slot?.let { coerceToFloat(lowerExpr(value), it) } ?: lowerExpr(value)
                            }
                            return IrExpr.Call(
                                declaredCtor.name,
                                scopeArgs.filterNotNull() + bound,
                                declaredCtor.returnType,
                            )
                        }
                    }
                    // Keep the explicit type arguments (`Box<Double>(…)`) on the
                    // result type: a generic pack erases its fields to pointer
                    // slots, and this is what later tells a read that the slot
                    // holds a Double.
                    val ctorArgs = expr.typeArgs.map { resolveType(it, currentGenericTypeParams) }
                    return IrExpr.StructCtor(
                        actualCallee,
                        struct.fields.map { it.name },
                        args,
                        IrType.Named(actualCallee, ctorArgs),
                    )
                }
                val func = table.lookupFunction(expr.callee)
                if (func != null) {
                    // Lower args, flattening any Spread expressions.
                    val loweredArgs = expr.args.flatMap { arg ->
                        if (arg is Expr.Spread) listOf(IrExpr.Spread(lowerExpr(arg.array)))
                        else listOf(lowerExpr(arg))
                    }
                    // Handle named arguments - reorder to param order (pre-spread only)
                    val callArgs = if (func.isVariadic) expr.args
                        else bindTrailingLambda(expr.args, func.params, offset = 0)
                    val args = if (callArgs.any { it is Expr.NamedArg } && func.paramNames.isNotEmpty()) {
                        val slots = mapNamedArguments(callArgs, func.paramNames)
                        if (func.isVariadic || callArgs.any { it is Expr.Spread }) {
                            func.paramNames.indices.mapNotNull { slots[it]?.let(::lowerExpr) }
                        } else {
                            // A named argument can leave an *earlier* parameter
                            // unfilled: `f(b: 5)` says nothing about `a`. That gap
                            // is `a`'s default - dropping it and closing up would
                            // slide `5` into `a` and silently call a different
                            // function than the one written.
                            fillArgumentGaps(slots, func, expr.callee)
                        }
                    } else {
                        loweredArgs
                    }
                    val funcDecl = functionDecls[func.name] ?: functionDecls[expr.callee]
                    val homogeneousVariadicType = funcDecl
                        ?.takeIf { it.variadicParam == null && it.params.lastOrNull()?.variadic == true }
                        ?.params
                        ?.lastOrNull()
                        ?.type
                        ?.let { it as? TypeRef.Array }
                        ?.element
                        ?.let { it as? TypeRef.Named }
                        ?.takeIf { it.name in func.typeParams }
                        ?.let { element ->
                            val typeParamIndex = func.typeParams.indexOf(element.name)
                            expr.typeArgs.getOrNull(typeParamIndex)?.takeUnless { it.isHole }?.let(::resolveType)
                                ?: args.getOrNull(func.params.size - 1)?.type
                        }
                    // Variadic: pack extra args into an array for the last param.
                    val hasSpread = args.any { it is IrExpr.Spread }
                    val effectiveArgs = if (func.isVariadic && args.size >= func.params.size - 1) {
                        val fixed = args.take(func.params.size - 1)
                        val rest = args.drop(func.params.size - 1)
                        // Pack physical slots, even when inference knows the
                        // logical T. A generic callee reads erased elements.
                        val elemType = (func.params.last().second as? IrType.Array)?.element
                            ?: homogeneousVariadicType
                            ?: IrType.Any
                        val restSize: kotlin.Long? = if (hasSpread) null else rest.size.toLong()
                        fixed + listOf(IrExpr.ArrayLiteral(rest, IrType.Array(elemType, restSize)))
                    } else if (hasSpread) {
                        // Non-variadic with spread: keep spread args for evalCall to splice.
                        args
                    } else if (args.size < func.params.size && func.defaults.isNotEmpty()) {
                        val result = args.toMutableList()
                        for (i in args.size until func.params.size) {
                            // The symbol table's copy of a default was captured
                            // before compile-time folding ran, so it can still
                            // name an `inline` constant that no longer exists.
                            // The declaration in the current AST is the folded
                            // one, and is the authority here.
                            val default = functionDecls[expr.callee]?.params?.getOrNull(i)?.defaultValue
                                ?: func.defaults[i]
                            result.add(
                                if (default != null) lowerDefaultArgument(default, func.params[i].second)
                                else error("Missing arg ${func.params[i].first} of '${expr.callee}'")
                            )
                        }
                        result
                    } else args
                    // What the call wrote, or what the resolver inferred when it
                    // wrote nothing: `wrap(4)` builds a `Box<Int>` as `wrap<Int>(4)` does.
                    val typeArgs = expr.typeArgs.ifEmpty { expr.inferredTypeArgs.orEmpty() }
                    val callType = when {
                        expr.callee == "async" -> {
                            val result = (effectiveArgs.firstOrNull()?.type as? IrType.Function)?.ret ?: IrType.Any
                            IrType.Task(result)
                        }
                        func.isTask -> IrType.Task(func.returnType)
                        homogeneousVariadicType != null && func.returnType is IrType.Array ->
                            IrType.Array(homogeneousVariadicType)
                        // A hole was never a type argument: `add<Short, _, Long>`
                        // said two of three, and the return type follows only
                        // when every one of them was said.
                        funcDecl != null && typeArgs.isNotEmpty() &&
                            typeArgs.none { it.isHole } &&
                            typeArgs.none { typeRefMentionsAny(it, currentGenericTypeParams) } -> {
                            val returnRef = (funcDecl.returnType as? TypeAnnotation.Explicit)?.ref
                            if (returnRef == null) {
                                func.returnType
                            } else {
                                val substitutions = func.typeParams
                                    .zip(typeArgs)
                                    .associate { (name, argument) -> name to listOf(argument) }
                                resolveType(
                                    TypeFunctionEvaluator.resolve(
                                        returnRef,
                                        typeFunctions,
                                        substitutions = substitutions,
                                    ),
                                )
                            }
                        }
                        else -> func.returnType
                    }
                    val displayArgs = if (symbolDenotes(func.name, Intrinsics.PRINTLN) || symbolDenotes(func.name, Intrinsics.PRINT)) {
                        effectiveArgs.map(::stringifyEnum)
                    } else {
                        // An integer literal passed where a float is declared becomes
                        // one here, so the callee never receives the wrong machine type.
                        effectiveArgs.mapIndexed { i, arg ->
                            func.params.getOrNull(i)?.let { coerceToFloat(arg, it.second) } ?: arg
                        }
                    }
                    // A bounded type parameter's descriptor follows the written arguments.
                    val descriptors = descriptorArgs(func.name, expr, funcDecl?.typeParams ?: func.typeParams, args)
                    return IrExpr.Call(func.name, displayArgs + descriptors, callType)
                }
                // Calling a lambda stored in a variable.
                val v = table.lookupVariable(expr.callee)
                // A function value whose type erased to `Any` (e.g. a loop variable
                // over `Array<(Int) -> Int>`): emit an indirect call by variable name.
                if (v != null && v.type !is IrType.Function && v.type == IrType.Any) {
                    val args = expr.args.map { lowerExpr(it) }
                    return IrExpr.Call(resolveName(expr.callee), args, IrType.Any)
                }
                if (v != null && v.type is IrType.Function) {
                    // Variadic lambda (`<...T>{ … }`): pack all args into the single `it` array.
                    val args = if (v.type.variadic) {
                        val elems = expr.args.map { lowerExpr(it) }
                        val elemType = if (elems.isEmpty()) IrType.Any else elems.first().type
                        listOf(IrExpr.ArrayLiteral(elems, IrType.Array(elemType)))
                    } else {
                        // The lambda states its parameter widths, so a literal
                        // written at the call takes them - the same adoption a
                        // named function's arguments get just above.
                        expr.args.mapIndexed { i, arg ->
                            v.type.params.getOrNull(i)?.let { coerceToFloat(lowerExpr(arg), it) } ?: lowerExpr(arg)
                        } + contextualArguments(v.type, expr.args.size)
                    }
                    return IrExpr.Call(
                        "",
                        args,
                        v.type.ret,
                        receiver = IrExpr.Var(resolveName(expr.callee), v.type),
                    )
                }
                // Compiler builtin: `convert::toString(x)` stringifies any
                // value (implemented natively by CTCE and every backend).
                if (symbolDenotes(expr.callee, Intrinsics.TO_STRING)) {
                    val args = expr.args.map { stringifyEnum(lowerExpr(it)) }
                    // Keep the name the source used; only the behaviour is the
                    // compiler's, not the spelling.
                    return IrExpr.Call(expr.callee, args, IrType.String)
                }
                error("undefined function or variable '${expr.callee}'")
            }
            is Expr.Grouping -> lowerExpr(expr.expr)
            is Expr.Range -> error("range expressions can only be used as for-loop iterables")
            is Expr.ArrayLiteral -> {
                val factory = expr.literalFactory
                if (factory != null) {
                    lowerFactoryLiteral(expr, factory)
                } else {
                    val target = expr.contextualType?.let(::resolveType) as? IrType.Array
                    val elems = expr.elements.map { value ->
                        val lowered = lowerExpr(value)
                        target?.let { coerceToFloat(lowered, it.element) } ?: lowered
                    }
                    val elemType = target?.element ?: if (elems.isEmpty()) IrType.Any else elems.first().type
                    // A literal carries its compile-time element count as the array's size.
                    val size = elems.size.toLong()
                    IrExpr.ArrayLiteral(elems, IrType.Array(elemType, size))
                }
            }
            is Expr.SetLiteral -> {
                val elems = expr.elements.map { lowerExpr(it) }
                val elemType = if (elems.isEmpty()) IrType.Any else elems.first().type
                IrExpr.SetLit(elems, IrType.Set(elemType))
            }
            is Expr.MapLit -> {
                val factory = expr.literalFactory
                if (factory != null) {
                    lowerEntriesFactoryLiteral(expr, factory)
                } else {
                    val target = expr.contextualType?.let(::resolveType) as? IrType.Map
                    val entries = expr.entries.map { (key, value) ->
                        val k = lowerExpr(key)
                        val v = lowerExpr(value)
                        (target?.let { coerceToFloat(k, it.key) } ?: k) to
                            (target?.let { coerceToFloat(v, it.value) } ?: v)
                    }
                    val keyType = target?.key ?: entries.firstOrNull()?.first?.type ?: IrType.Any
                    val valType = target?.value ?: entries.firstOrNull()?.second?.type ?: IrType.Any
                    IrExpr.MapLit(entries, IrType.Map(keyType, valType))
                }
            }
            is Expr.Alloc -> {
                val value = lowerExpr(allocatedConstruction(expr.value))
                // alloc [a, b, c] → pointer to element type (buffer for arithmetic).
                val pointee = (value.type as? IrType.Array)?.element ?: value.type
                IrExpr.Call("__alloc", listOf(value), IrType.Pointer(pointee))
            }
            is Expr.Deref -> {
                val target = lowerExpr(expr.target)
                val targetType = target.type
                if (targetType is IrType.Named) {
                    val mangled = table.lookupMethod(targetType.name, "operDeref")
                    if (mangled != null) {
                        val func = table.lookupFunction(mangled)!!
                        return IrExpr.Call(mangled, listOf(target), func.returnType)
                    }
                }
                val inner = (targetType as? IrType.Pointer)?.inner ?: IrType.Any
                IrExpr.Call("__deref", listOf(target), inner)
            }
            is Expr.Isolated -> {
                // `take opt.require()` - the primitive that moves a value out of
                // an optional. The optional is emptied; see [emptyAfterTake].
                val unwrapped = expr.value
                if (expr.op == OwnershipOp.TAKE && unwrapped is Expr.MethodCall &&
                    unwrapped.name == "require" && unwrapped.args.isEmpty()
                ) {
                    emptyAfterTake(unwrapped.target, lowerExpr(unwrapped.target))
                }
                // Every ownership operation moves or borrows; none duplicates,
                // so the value passes through unchanged. Duplication is
                // `v.clone()`, an ordinary method call.
                lowerExpr(expr.value)
            }
            is Expr.Await -> {
                val task = lowerExpr(expr.value)
                val resultType = when (val type = task.type) {
                    is IrType.Task -> type.result
                    is IrType.Function -> type.ret
                    else -> IrType.Any
                }
                IrExpr.Await(task, resultType)
            }
            is Expr.Inject -> {
                IrExpr.Call("__inject", listOf(IrExpr.StringLiteral(expr.typeName)), IrType.Named(expr.typeName))
            }
            is Expr.Spread -> {
                IrExpr.Spread(lowerExpr(expr.array))
            }
            is Expr.Index -> {
                // `p.*[i]` on a `T*` reads the i-th slot of the buffer, so the
                // pointer is indexed directly - dereferencing first would read
                // slot zero and index *that*. The resolver types it the same way.
                val target = lowerExpr(bufferPointerTarget(expr.target) ?: expr.target)
                val tt = target.type
                // User-defined index operator (`oper[]`) on a struct → Type_index(self, i).
                if (tt is IrType.Named) {
                    val mangled = table.lookupMethod(tt.name, "index")
                    if (mangled != null) {
                        val func = instantiateMember(table, tt, table.lookupFunction(mangled)!!)
                        val index = lowerExpr(expr.index)
                        return IrExpr.Call(mangled, listOf(target, index), func.returnType)
                    }
                }
                val index = lowerExpr(expr.index)
                val elemType = when (tt) {
                    is IrType.Array -> tt.element
                    is IrType.Set -> tt.element
                    is IrType.Map -> tt.value
                    is IrType.Pointer -> tt.inner
                    IrType.String -> IrType.Char
                    else -> IrType.Any
                }
                IrExpr.Index(target, index, elemType)
            }
            is Expr.Member -> {
                // `Hash`'s member on a value of a type parameter bounded by it
                // hashes as the concrete type its descriptor names.
                if (expr.name == "hash") {
                    val param = witnessParamOf(expr.target)
                    if (param != null && Witnesses.HASH in witnessBounds[param].orEmpty()) {
                        return witnessCall(
                            WITNESS_HASH,
                            listOf(witnessSources.getValue(param), lowerExpr(expr.target)),
                            IrType.ULong,
                        )
                    }
                }
                // NOTE: `.size`/`.length` are left as runtime intrinsics (handled by
                // each backend) even for compile-time-sized arrays - existing dynamic
                // arrays (`var a = [1,2,3]; a.add(4); a.length`) rely on the runtime
                // length. The const `N` drives type identity (`Array<T,3>` ≠
                // `Array<T,5>`) and future bound checks, not the live element count.
                // Slot no-payload construction: SlotName.Variant (no parens)
                if (expr.target is Expr.Identifier) {
                    val slotVariants = table.lookupSlot(expr.target.name)
                    if (slotVariants != null && slotVariants.any { it.first == expr.name && it.second.isEmpty() }) {
                        return IrExpr.StructCtor(expr.target.name, listOf("__tag"), listOf(IrExpr.StringLiteral(expr.name)), IrType.Named(expr.target.name))
                    }
                }
                // Enum variants retain their nominal identity in IR while backends
                // keep the compact variant-only runtime representation.
                if (expr.target is Expr.Identifier && table.lookupEnum(expr.target.name) != null) {
                    return IrExpr.EnumLiteral(expr.target.name, expr.name)
                }
                // Error-set variant `ErrSet.Variant` → string literal "Variant"
                if (expr.target is Expr.Identifier && table.lookupFail(expr.target.name) != null) {
                    return IrExpr.StringLiteral(expr.name)
                }
                val target = autoDerefMemberTarget(lowerExpr(expr.target), expr.name, method = false)
                val tt2 = target.type
                val position = expr.name.toIntOrNull()
                if (tt2 is IrType.Tuple && position != null && position in tt2.elements.indices) {
                    return IrExpr.TupleAccess(target, position, tt2.elements[position])
                }
                // `5.seconds` - a member declared on a primitive by an `impl Int`.
                // The receiver lowers to a builtin rather than a Named pack, so
                // there is no struct to take a field from: the member is the
                // function the impl declared, called with the value.
                if (tt2 !is IrType.Named) {
                    val owner = primitiveOwnerName(tt2)
                    if (owner != null) {
                        val mangled = table.lookupMethod(owner, expr.name)
                        if (mangled != null) {
                            val func = table.lookupFunction(mangled)!!
                            return IrExpr.Call(mangled, listOf(target), func.returnType)
                        }
                    }
                }
                if (tt2 is IrType.Named) {
                    // Concrete pack fields win over property-style callbacks. This matters
                    // for stdlib containers that expose field-backed storage and also define
                    // methods such as keys()/values().
                    val owner = table.lookupStruct(tt2.name)
                    val field = owner?.field(expr.name)
                    if (field != null) {
                        // A field declared as a type parameter resolves to `Any`;
                        // the referring type still carries the argument, which is
                        // what the value really is.
                        val slot = field.typeParamIndex
                        val concrete = if (slot >= 0 && slot < tt2.args.size) tt2.args[slot] else field.type
                        return IrExpr.Member(target, expr.name, concrete)
                    }
                    // Check for a computed property (prop): `Type_name` zero-arg method.
                    val mangled = table.lookupMethod(tt2.name, expr.name)
                    if (mangled != null) {
                        val func = table.lookupFunction(mangled)
                        if (func != null && func.params.size == 1 && func.memberCallStyle != MemberCallStyle.METHOD) {
                            // It's a prop - lower to a method call Type_name(self).
                            return IrExpr.Call(mangled, listOf(target), func.returnType)
                        }
                    }
                    // A property required by a spec (`list.size` where `list: List<T>`)
                    // has neither a field nor a single impl to name here - the backend
                    // dispatches it. Carry the spec's declared type so arithmetic and
                    // comparisons on the result still lower.
                    val specProp = table.lookupSpecProp(tt2.name, expr.name)
                    if (specProp != null) {
                        val typed = table.lookupSpecMethod(tt2.name, expr.name)
                            ?.let { instantiateSpecMember(table, tt2, it).returnType } ?: specProp
                        return IrExpr.Member(target, expr.name, typed)
                    }
                }
                // A member declared in an `impl` on an aggregate builtin -
                // `impl<T, N: Int> Array<T, N> { prop isEmpty … }` - is an ordinary
                // method registered under the type constructor, so it lowers to a
                // call rather than to a field read.
                aggregateMemberSymbol(target.type, expr.name)?.let { mangled ->
                    val func = table.lookupFunction(mangled)
                    return IrExpr.Call(mangled, listOf(target), func?.returnType ?: IrType.Any)
                }
                // A type-scoped constant (`impl Array { bridge fin size }`) is
                // typed by its declaration wherever the receiver came from.
                val typeOwner = (target.type as? IrType.Named)?.name
                    ?: when (target.type) {
                        is IrType.Array -> "Array"
                        is IrType.Map -> "Map"
                        is IrType.Set -> "Set"
                        is IrType.Tuple -> "Tuple"
                        else -> null
                    }
                val typeStatic = typeOwner?.let { table.lookupTypeStatic(it, expr.name) }
                val memberType = when {
                    typeStatic != null -> typeStatic.returnType
                    expr.name in setOf("length", "size") &&
                        (
                            target.type is IrType.Array || target.type is IrType.Map ||
                                target.type is IrType.Set || target.type == IrType.String
                            ) -> IrType.Int
                    expr.name == "data" && tt2 is IrType.Array -> IrType.Pointer(tt2.element)
                    // `Hash`'s member answers a ULong whatever it is read on. A
                    // pack with its own `hash` resolves to that member and never
                    // reaches here, so this is the built-in one - and typing it
                    // is what lets an expression mixing it with an Int widen.
                    expr.name == "hash" && target.type !is IrType.Named -> IrType.ULong
                    else -> {
                        val tt = target.type
                        if (tt is IrType.Named) table.lookupStruct(tt.name)?.field(expr.name)?.type ?: IrType.Any
                        else IrType.Any
                    }
                }
                IrExpr.Member(target, expr.name, memberType)
            }
            is Expr.MethodCall -> {
                // `x.clone()` with no written member - the compiler-provided
                // `Clone` default is an independent deep copy.
                if (expr.name == "clone" && expr.args.isEmpty()) {
                    val target = lowerExpr(expr.target)
                    // A pack registers its conformance under its name, a
                    // primitive under the spelling of its own type, and an
                    // aggregate builtin under its type constructor - matching
                    // TypeResolver.cloneConformanceName.
                    val name = when (val t = target.type) {
                        is IrType.Named -> t.name
                        is IrType.Array -> "Array"
                        is IrType.Map -> "Map"
                        is IrType.Set -> "Set"
                        is IrType.Tuple -> "Tuple"
                        else -> t.toString()
                    }
                    val conforms = table.conformsTo(name, "Clone") ||
                        (target.type !is IrType.Named && table.isClonable(target.type))
                    if (table.lookupMethod(name, "clone") == null && conforms) {
                        return IrExpr.Call("__isolated", listOf(target), target.type)
                    }
                }
                // `opt.require()` / `opt.take()` - dropping the optional off a
                // value is a change of type, not of representation, so it lowers
                // to the value itself. What makes the read safe is the guard the
                // parser puts in front of it.
                if (expr.name in OPTIONAL_UNWRAP && expr.args.isEmpty()) {
                    val target = lowerExpr(expr.target)
                    val inner = (target.type as? IrType.Nullable)?.inner
                    if (inner != null) {
                        // The shorthand moves as well as reads, so it owes the
                        // optional the same emptying the keyword form does.
                        if (expr.name == "take") emptyAfterTake(expr.target, target)
                        return IrExpr.NumCast(target, inner)
                    }
                }
                // Slot construction: SlotName.Variant(args)
                if (expr.target is Expr.Identifier && table.lookupSlot(expr.target.name) != null) {
                    // An unsuffixed literal takes its payload's width, as the
                    // resolver typed it: `Shape.Circle(2.0)` stores a Double.
                    val payloads = table.lookupSlot(expr.target.name)!!.find { it.first == expr.name }?.second.orEmpty()
                    val args = expr.args.mapIndexed { i, arg ->
                        val value = lowerExpr(arg)
                        payloads.getOrNull(i)?.let { coerceToFloat(value, it) } ?: value
                    }
                    val fieldNames = listOf("__tag") + args.indices.map { "__$it" }
                    val allArgs = listOf(IrExpr.StringLiteral(expr.name)) + args
                    return IrExpr.StructCtor(expr.target.name, fieldNames, allArgs, IrType.Named(expr.target.name))
                }
                val target = autoDerefMemberTarget(lowerExpr(expr.target), expr.name, method = true)
                val tt = target.type
                // User method on a struct: obj.method(args) -> Type_method(obj, args)
                if (tt is IrType.Named) {
                    val mangled = table.lookupMethod(tt.name, expr.name)
                    if (mangled != null) {
                        val func = instantiateMember(table, tt, table.lookupFunction(mangled)!!)
                        if (func.memberCallStyle == MemberCallStyle.PROPERTY) {
                            error("property '${expr.name}' must be accessed without parentheses")
                        }
                        val args = lowerMethodArguments(expr, func, mangled)
                        return IrExpr.Call(mangled, listOf(target) + args, func.returnType)
                    }
                    val callableField = table.lookupStruct(tt.name)?.field(expr.name)?.type as? IrType.Function
                    if (callableField != null) {
                        val member = IrExpr.Member(target, expr.name, callableField)
                        val args = expr.args.map(::lowerExpr) +
                            contextualArguments(callableField, expr.args.size)
                        return IrExpr.Call("", args, callableField.ret, receiver = member)
                    }
                }
                if (tt !is IrType.Named) {
                    val mangled = table.lookupMethod(tt.toString(), expr.name)
                    if (mangled != null) {
                        val func = table.lookupFunction(mangled)!!
                        val args = lowerMethodArguments(expr, func, mangled)
                        return IrExpr.Call(mangled, listOf(target) + args, func.returnType)
                    }
                }
                // Call on a spec-typed value (`p.build()` where `p: Plugin`): no
                // concrete method exists on the spec name, so keep it as a
                // `MethodCall` for dynamic dispatch. The interpreter resolves it via
                // the receiver's `__type`; native backends via the spec table. We
                // still stamp the erased return type from the spec signature.
                if (tt is IrType.Named) {
                    val sig = table.lookupSpecMethod(tt.name, expr.name)?.let { instantiateSpecMember(table, tt, it) }
                    if (sig != null && !sig.isProperty) {
                        val args = expr.args.mapIndexed { i, arg ->
                            sig.paramTypes.getOrNull(i)?.let { coerceToFloat(lowerExpr(arg), it) } ?: lowerExpr(arg)
                        }
                        return IrExpr.MethodCall(target, expr.name, args, sig.returnType)
                    }
                }
                // `2.scale(7)` - the receiver call. `scale` is a callable whose one
                // contextual receiver the target supplies, so it lowers like any
                // other callable-value call with the receiver appended: the closure's
                // convention is parameters first, receivers after.
                val receiverCallable = table.lookupVariable(expr.name)?.type as? IrType.Function
                if (receiverCallable != null && receiverCallable.receivers.size == 1) {
                    val args = expr.args.map { lowerExpr(it) } + target
                    return IrExpr.Call(
                        "",
                        args,
                        receiverCallable.ret,
                        receiver = IrExpr.Var(resolveName(expr.name), receiverCallable),
                    )
                }
                // Universal infix (`a to b`) → call the generic free function.
                val infixMangled = table.lookupUniversalInfix(expr.name)
                if (infixMangled != null) {
                    val func = table.lookupFunction(infixMangled)!!
                    val args = expr.args.map { lowerExpr(it) }
                    return IrExpr.Call(infixMangled, listOf(target) + args, func.returnType)
                }
                val args = expr.args.map { lowerExpr(it) }
                IrExpr.MethodCall(target, expr.name, args, builtinMethodReturnType(target.type, expr.name))
            }
            is Expr.StringTemplate -> {
                val parts = expr.parts.map { p ->
                    when (p) {
                        is Expr.StringTemplatePart.Literal -> IrExpr.IrTemplatePart.Literal(p.text)
                        is Expr.StringTemplatePart.Expr ->
                            IrExpr.IrTemplatePart.Expr(stringifyEnum(displayed(lowerExpr(p.expr))))
                    }
                }
                IrExpr.StringTemplate(parts)
            }
            is Expr.TupleLit -> {
                val elems = expr.elements.map { lowerExpr(it) }
                IrExpr.TupleLit(elems, IrType.Tuple(elems.map { it.type }))
            }
            is Expr.VariantLit -> {
                val elems = expr.elements.map { lowerExpr(it) }
                IrExpr.VariantLit(elems, IrType.Variant(elems.map { it.type }))
            }
            is Expr.TupleAccess -> {
                val target = lowerExpr(expr.target)
                val tt = target.type
                when {
                    // Structural tuple `(a, b).0` → positional list/array access.
                    tt is IrType.Tuple && expr.index in tt.elements.indices ->
                        IrExpr.TupleAccess(target, expr.index, tt.elements[expr.index])
                    // Nominal tuple pack `__Tuple_<types>` → numeric-named field read.
                    tt is IrType.Named -> {
                        val fieldType = table.lookupStruct(tt.name)?.field(expr.index.toString())?.type ?: IrType.Any
                        IrExpr.Member(target, expr.index.toString(), fieldType)
                    }
                    else -> IrExpr.TupleAccess(target, expr.index, IrType.Any)
                }
            }
            // A seal is a rule about decorator applications, checked before
            // lowering; what runs is the value it wraps.
            is Expr.Seal -> lowerExpr(expr.value)
            is Expr.IfExpr -> {
                val condition = lowerExpr(expr.condition)
                val thenExpr = lowerExpr(expr.thenExpr)
                val elseExpr = lowerExpr(expr.elseExpr)
                val type = when {
                    thenExpr.type == IrType.Nothing -> elseExpr.type
                    elseExpr.type == IrType.Nothing -> thenExpr.type
                    else -> thenExpr.type
                }
                IrExpr.IfExpr(condition, thenExpr, elseExpr, type)
            }
            is Expr.CatchExpr -> {
                val e = lowerExpr(expr.expr)
                val f = lowerExpr(expr.fallback)
                val type = when {
                    e.type == IrType.Nothing -> f.type
                    f.type == IrType.Nothing -> e.type
                    else -> e.type
                }
                IrExpr.CatchExpr(e, f, type)
            }
            // Runtime failure transport already propagates when uncaught.
            is Expr.TryPropagate -> lowerExpr(expr.expr)
            is Expr.Lambda -> {
                val callableType = table.lookupLambdaType(expr.line, expr.column)
                    ?: IrType.Function(
                        expr.params.map { resolveType(it.type) },
                        IrType.Unit,
                        variadic = expr.variadic,
                        receivers = expr.receivers.map { resolveType(it.type) },
                        kind = expr.kind,
                    )
                // Clone capture is an operation, not a bitwise environment copy.
                // Lower it while the source binding still resolves in the creation
                // scope; every backend evaluates this expression exactly once.
                val captureInitializers = expr.captures
                    .filter { it.mode == CaptureMode.CLONE }
                    .associate { capture ->
                        resolveName(capture.source) to lowerExpr(
                            Expr.MethodCall(
                                Expr.Identifier(capture.source, capture.line, capture.column),
                                "clone",
                                emptyList(),
                                capture.line,
                                capture.column,
                            ),
                        )
                    }
                table.pushScope()
                pushNameScope()
                // An alias is a real lambda-local binding backed by the captured
                // source. Register it in both name and symbol scopes so every use
                // lowers with the source's IR name and concrete type.
                for (capture in expr.captures) {
                    val source = table.lookupVariable(capture.source) ?: continue
                    val sourceIrName = resolveName(capture.source)
                    nameScopes.last()[capture.name] = sourceIrName
                    val valueMutable = when (capture.mode) {
                        CaptureMode.SHARED -> false
                        CaptureMode.MUTABLE -> source.valueMutable
                        CaptureMode.COPY, CaptureMode.CLONE, CaptureMode.MOVE -> true
                    }
                    val nameMutable = when (capture.mode) {
                        CaptureMode.SHARED -> false
                        CaptureMode.MUTABLE -> source.mutable
                        CaptureMode.COPY, CaptureMode.CLONE, CaptureMode.MOVE -> true
                    }
                    table.defineVariable(
                        VariableSymbol(
                            capture.name,
                            source.type,
                            mutable = nameMutable,
                            valueMutable = valueMutable,
                        ),
                    )
                }
                // `{ body }` carries the parser's implicit `it`. When the callable
                // type has no ordinary parameters there is nothing for it to stand
                // for, and keeping it would shift every contextual receiver by one
                // argument at the call site. The resolver drops it from the type;
                // the closure has to drop it from its parameter list to match.
                val ordinarySources = when {
                    callableType.params.isEmpty() &&
                        expr.params.size == 1 &&
                        expr.params[0].name == "it" -> emptyList()
                    // A bare lambda that reads no `it` declares no parameters; the
                    // expected callable's are supplied so the closure's arity still
                    // matches what the call site passes. The resolver decides the
                    // same thing - this keeps the lowering in step with it.
                    !expr.paramsWritten && expr.params.isEmpty() && callableType.params.isNotEmpty() ->
                        callableType.params.indices.map { i ->
                            Param(
                                if (callableType.params.size == 1) "it" else "__arg$i",
                                TypeRef.Named("Any", synthesized = true),
                            )
                        }
                    else -> expr.params
                }
                val ordinaryParams = ordinarySources.mapIndexed { index, p ->
                    val t = callableType.params.getOrNull(index) ?: resolveType(p.type)
                    val m = registerName(p.name)
                    table.defineVariable(VariableSymbol(p.name, t))
                    m to t
                }
                // A lambda that inherits its receivers (`sequence { yield(1) }`)
                // declares none, so the bindings are synthesized here under the name the
                // resolver already used, and made available to calls in the body exactly
                // as a `with` block would.
                val bracketReceivers = expr.receivers
                val inheritsReceivers = bracketReceivers.isEmpty() && callableType.receivers.isNotEmpty()
                val receiverSources = if (inheritsReceivers) {
                    callableType.receivers.indices.map {
                        Param(
                            if (callableType.receivers.size == 1) "self"
                            else lambdaReceiverName(expr.line, expr.column, it),
                            TypeRef.Named("Any", synthesized = true),
                        )
                    }
                } else {
                    bracketReceivers
                }
                val receiverParams = receiverSources.mapIndexed { index, p ->
                    val t = callableType.receivers.getOrNull(index) ?: resolveType(p.type)
                    val m = registerName(p.name)
                    table.defineVariable(
                        VariableSymbol(
                            p.name,
                            t,
                            mutable = false,
                            valueMutable = p.modifier != ParamModifier.SHARED,
                        ),
                    )
                    m to t
                }
                val positionalSelf = if (inheritsReceivers && receiverParams.size > 1) {
                    val selfType = IrType.Tuple(receiverParams.map { it.second })
                    val selfName = registerName("self")
                    table.defineVariable(VariableSymbol("self", selfType, mutable = false, valueMutable = false))
                    IrStmt.FinDecl(
                        selfName,
                        selfType,
                        IrExpr.TupleLit(receiverParams.map { (name, type) -> IrExpr.Var(name, type) }, selfType),
                    )
                } else null
                // A lambda's receivers are contextual values for its body whether it
                // named them or inherited them, matching how the resolver typed it -
                // otherwise a bare call that type-checked would fail to lower.
                if (receiverParams.isNotEmpty()) {
                    contextualValues.addLast(
                        ContextFrame(receiverParams.map { (name, t) -> IrExpr.Var(name, t) }, prefersMembers = true),
                    )
                }
                val loweredBody = lowerBody(expr.body)
                val body = if (positionalSelf == null) loweredBody else listOf(positionalSelf) + loweredBody
                if (receiverParams.isNotEmpty()) contextualValues.removeLast()
                popNameScope()
                table.popScope()
                val retType = body.mapNotNull { (it as? IrStmt.Return)?.value?.type }.firstOrNull() ?: IrType.Unit
                val irParams = ordinaryParams + receiverParams
                // A referenced capture (`[n.&]`, `[n.!]`) is the original binding,
                // so the backends put its address in the environment rather than a
                // copy. Defaults have already been expanded to the exact free
                // variables they cover by the resolver; lowering never guesses.
                val resolvedCaptures = table.lookupLambdaCaptures(expr.line, expr.column)
                val byRef = resolvedCaptures
                    .filterValues { it == CaptureMode.SHARED || it == CaptureMode.MUTABLE }
                    .keys.mapTo(mutableSetOf(), ::resolveName)
                val byValue = resolvedCaptures
                    .filterValues { it != CaptureMode.SHARED && it != CaptureMode.MUTABLE }
                    .keys.mapTo(mutableSetOf(), ::resolveName)
                val cloned = resolvedCaptures
                    .filterValues { it == CaptureMode.CLONE }
                    .keys.mapTo(mutableSetOf(), ::resolveName)
                IrExpr.Lambda(
                    irParams,
                    body,
                    callableType.copy(ret = retType),
                    byRefCaptures = byRef,
                    valueCaptures = byValue,
                    cloneCaptures = cloned,
                    captureInitializers = captureInitializers,
                )
            }
            // Macros are expanded before IR generation; a MetaInvoke here is a bug.
            is Expr.Slice -> {
                // `a[start:stop:step]` → `a.slice(start, stop, step)`; null bounds use
                // 0 / -1 (to-end sentinel) / 1 defaults the slice method interprets.
                val target = lowerExpr(expr.target)
                val start = expr.start?.let { lowerExpr(it) } ?: IrExpr.IntLiteral(0)
                val stop = expr.stop?.let { lowerExpr(it) } ?: IrExpr.IntLiteral(-1)
                val step = expr.step?.let { lowerExpr(it) } ?: IrExpr.IntLiteral(1)
                IrExpr.MethodCall(target, "slice", listOf(start, stop, step), IrType.Any)
            }
            is Expr.MetaInvoke -> error("MetaInvoke reached IR generation at line ${expr.line}")
        }
    }

    private fun typeRefMentionsAny(ref: TypeRef, names: Set<String>): Boolean = when (ref) {
        is TypeRef.Named -> ref.name in names || ref.args.any { typeRefMentionsAny(it, names) }
        is TypeRef.Array -> typeRefMentionsAny(ref.element, names)
        is TypeRef.Map -> typeRefMentionsAny(ref.key, names) || typeRefMentionsAny(ref.value, names)
        is TypeRef.Set -> typeRefMentionsAny(ref.element, names)
        is TypeRef.Function ->
            ref.params.any { typeRefMentionsAny(it, names) } ||
                typeRefMentionsAny(ref.ret, names) ||
                ref.receivers.any { typeRefMentionsAny(it, names) }
        is TypeRef.Tuple -> ref.elements.any { typeRefMentionsAny(it, names) }
        is TypeRef.Nullable -> typeRefMentionsAny(ref.inner, names)
        is TypeRef.Pointer -> typeRefMentionsAny(ref.inner, names)
        is TypeRef.Reference -> typeRefMentionsAny(ref.inner, names)
        is TypeRef.Failable -> typeRefMentionsAny(ref.ok, names)
        is TypeRef.Const -> false
    }

    /**
     * Applies one user-defined `impl deref` when the wrapper does not own the
     * requested member but its dereference target does.
     */
    private fun autoDerefMemberTarget(target: IrExpr, name: String, method: Boolean): IrExpr {
        val wrapper = target.type as? IrType.Named ?: return target
        val wrapperStruct = table.lookupStruct(wrapper.name)
        val direct = if (method) {
            table.lookupMethod(wrapper.name, name) != null ||
                wrapperStruct?.field(name)?.type is IrType.Function
        } else {
            wrapperStruct?.field(name) != null || table.lookupMethod(wrapper.name, name) != null
        }
        if (direct) return target

        val derefName = table.lookupMethod(wrapper.name, "operDeref") ?: return target
        val deref = table.lookupFunction(derefName) ?: return target
        val inner = deref.returnType as? IrType.Named ?: return target
        if (inner.name == wrapper.name) return target
        val innerStruct = table.lookupStruct(inner.name)
        val exists = if (method) {
            table.lookupMethod(inner.name, name) != null ||
                innerStruct?.field(name)?.type is IrType.Function
        } else {
            innerStruct?.field(name) != null || table.lookupMethod(inner.name, name) != null
        }
        return if (exists) IrExpr.Call(derefName, listOf(target), inner) else target
    }

    /** Resolves the return type of a builtin method on a receiver of [receiverType]. */
    private fun builtinMethodReturnType(receiverType: IrType, name: String): IrType {
        // `Hash`'s member answers a ULong whatever the receiver is.
        if (name == "hash") return IrType.ULong
        return when (receiverType) {
        is IrType.Array -> when (name) {
            "add", "insert", "remove" -> IrType.Unit
            "contains" -> IrType.Bool
            "indexOf" -> IrType.Int
            "fill" -> receiverType
            else -> IrType.Any
        }
        is IrType.Set -> when (name) {
            "add", "remove", "contains" -> IrType.Bool
            "clear" -> IrType.Unit
            else -> IrType.Any
        }
        is IrType.Map -> when (name) {
            "get" -> receiverType.value
            "put", "clear" -> IrType.Unit
            "containsKey" -> IrType.Bool
            else -> IrType.Any
        }
        IrType.String -> when (name) {
            "toUpperCase", "toLowerCase", "trim", "replace" -> IrType.String
            "contains", "startsWith", "endsWith" -> IrType.Bool
            "split" -> IrType.Array(IrType.String)
            "indexOf" -> IrType.Int
            else -> IrType.Any
        }
        else -> IrType.Any
        }
    }

    /** Maps an operator token to the impl method name for operator overloading. */
    /** The operand-type key an operator overload is registered under. */
    private fun operandKeyOf(type: IrType): String? = when (type) {
        is IrType.Named -> type.name
        is IrType.Pointer -> operandKeyOf(type.inner)
        is IrType.Nullable -> operandKeyOf(type.inner)
        else -> type.toString()
    }

    private fun operatorMethodName(op: TokenType): String? = when (op) {
        TokenType.PLUS -> "plus"
        TokenType.MINUS -> "minus"
        TokenType.STAR -> "times"
        TokenType.SLASH -> "div"
        TokenType.PERCENT -> "mod"
        TokenType.EQUAL_EQUAL -> "equals"
        else -> null
    }

    /** The `impl oper<OP> for Type` method name for [op] (e.g. PLUS → "oper+"). */
    private fun operOverloadName(op: TokenType): String? = when (op) {
        TokenType.PLUS -> "oper+"
        TokenType.MINUS -> "oper-"
        TokenType.STAR -> "oper*"
        TokenType.SLASH -> "oper/"
        TokenType.PERCENT -> "oper%"
        TokenType.EQUAL_EQUAL -> "oper=="
        TokenType.BANG_EQUAL -> "oper!="
        TokenType.LESS -> "oper<"
        TokenType.LESS_EQUAL -> "oper<="
        TokenType.GREATER -> "oper>"
        TokenType.GREATER_EQUAL -> "oper>="
        TokenType.TILDE -> "oper~"
        else -> null
    }

    /** The common type of two numeric operands (wider float wins, else wider int). */
    /**
     * Emits [expr] as [target] when it is an integer that a float position wants.
     *
     * The resolver lets an untyped integer literal stand for a floating-point value;
     * this is where that becomes a real float, so backends never see a comparison or
     * a store mixing an integer with a float.
     */
    /** `Alias__member` under the type the alias names, or null. See the resolver. */
    private fun throughTypeAlias(name: String): String? {
        val separator = name.indexOf("__")
        if (separator <= 0) return null
        val owner = name.substring(0, separator)
        val target = (table.lookupAlias(owner) as? TypeRef.Named)?.name ?: return null
        if (target == owner) return null
        return target + name.substring(separator)
    }

    /** The return type of the function being lowered; [IrType.Any] outside one. */
    private val currentReturnType: IrType
        get() = currentTraceOwner?.let { table.lookupFunction(it)?.returnType } ?: IrType.Any

    /** The annotated type, or [IrType.Any] when the declaration infers it. */
    private fun typeAnnotationOrNull(ann: TypeAnnotation): IrType =
        (ann as? TypeAnnotation.Explicit)?.let { runCatching { resolveType(it.ref) }.getOrNull() } ?: IrType.Any

    private fun coerceToFloat(expr: IrExpr, target: IrType): IrExpr =
        if (IrType.isNumeric(target) && target != expr.type &&
            (IrType.isInteger(expr.type) || expr.type in IrType.floatTypes) &&
            isUntypedIntConstant(expr)
        ) {
            when {
                // Context selects the literal's representation before emission.
                // Casting an i32 constant afterwards would already truncate a
                // value such as 4294967296 intended for a Long element.
                expr is IrExpr.IntLiteral && IrType.isInteger(target) -> expr.copy(type = target)
                expr is IrExpr.DoubleLiteral && target in IrType.floatTypes -> expr.copy(type = target)
                expr is IrExpr.Unary && expr.op == IrUnaryOp.NEG ->
                    expr.copy(operand = coerceToFloat(expr.operand, target), type = target)
                else -> IrExpr.NumCast(expr, target)
            }
        } else {
            expr
        }

    /**
     * True for a numeric constant the resolver would have let adopt another type.
     *
     * A literal still sitting at the default is one nothing has said a width
     * for; one that carries any other width was told what it is, and coercing
     * it would be discarding what the source said.
     */
    private fun isUntypedIntConstant(expr: IrExpr): Boolean = when (expr) {
        is IrExpr.DoubleLiteral -> expr.type == IrType.defaultFloat
        is IrExpr.IntLiteral -> expr.type == IrType.defaultInt
        is IrExpr.Unary -> expr.op == IrUnaryOp.NEG && isUntypedIntConstant(expr.operand)
        else -> false
    }

    private fun numericResultType(a: IrType, b: IrType): IrType {
        if (a == b) return a
        if (!IrType.isNumeric(a) || !IrType.isNumeric(b)) return a
        if (a in IrType.floatTypes || b in IrType.floatTypes) {
            if (a == IrType.Quad || b == IrType.Quad) return IrType.Quad
            if (a == IrType.Double || b == IrType.Double) return IrType.Double
            return IrType.Float
        }
        val aBits = (a as? IrType.Integer)?.bits ?: 64
        val bBits = (b as? IrType.Integer)?.bits ?: 64
        return if (aBits >= bBits) a else b
    }

    private fun lowerBinaryOp(op: TokenType): IrBinaryOp = when (op) {
        TokenType.PLUS -> IrBinaryOp.ADD
        TokenType.MINUS -> IrBinaryOp.SUB
        TokenType.STAR -> IrBinaryOp.MUL
        TokenType.SLASH -> IrBinaryOp.DIV
        TokenType.PERCENT -> IrBinaryOp.MOD
        TokenType.EQUAL_EQUAL -> IrBinaryOp.EQ
        TokenType.BANG_EQUAL -> IrBinaryOp.NEQ
        TokenType.LESS -> IrBinaryOp.LT
        TokenType.LESS_EQUAL -> IrBinaryOp.LTE
        TokenType.GREATER -> IrBinaryOp.GT
        TokenType.GREATER_EQUAL -> IrBinaryOp.GTE
        TokenType.AND_AND -> IrBinaryOp.AND
        TokenType.OR_OR -> IrBinaryOp.OR
        TokenType.AMP -> IrBinaryOp.BIT_AND
        TokenType.PIPE -> IrBinaryOp.BIT_OR
        TokenType.CARET -> IrBinaryOp.BIT_XOR
        TokenType.SHIFT_LEFT -> IrBinaryOp.SHL
        TokenType.SHIFT_RIGHT -> IrBinaryOp.SHR
        else -> error("Unknown binary op: $op")
    }

    private fun resolveTypeAnnotation(ann: TypeAnnotation, init: IrExpr): IrType = when (ann) {
        is TypeAnnotation.Explicit -> resolveType(ann.ref)
        is TypeAnnotation.Inferred -> init.type
    }

    /**
     * The fields of the construction being lowered, bound to their values.
     *
     * Non-null only while a field *default* is being lowered, so an ordinary
     * name is never shadowed by a field of some struct being built nearby.
     */
    private var constructionBindings: Map<String, IrExpr>? = null

    /**
     * The value of every field of [struct], in field order.
     *
     * Each field takes what [supplied] gave it, or its own default when that
     * position is empty. A default is lowered with the fields before it bound
     * to the values just computed, which is what lets one be written in terms
     * of another:
     *
     *     pack Pen {
     *         color: Color = .Black
     *         width: Width = when color { .Black -> .Thin  else -> .Thick }
     *     }
     *
     * `Pen(.Red)` gets `.Thick` - the `color` the construction chose, not the
     * one the declaration wrote. Only defaults see these bindings; a supplied
     * argument is lowered in the caller's own scope.
     *
     * A supplied value read by a later default is emitted again where the
     * default reads it, so an argument with side effects would run once per
     * reader. Defaults referring to earlier fields are declarations of shape
     * rather than of work, which is what makes that acceptable here.
     */
    private fun loweredFieldValues(
        struct: StructType,
        supplied: List<Expr?>,
        typeArgs: List<IrType> = emptyList(),
    ): List<IrExpr> {
        val saved = constructionBindings
        val bindings = mutableMapOf<String, IrExpr>()
        val values = mutableListOf<IrExpr>()
        try {
            for (i in struct.fields.indices) {
                val field = struct.fields[i]
                val given = supplied.getOrNull(i)
                constructionBindings = if (given != null) saved else bindings
                // A field declared as a type parameter takes the call's argument
                // for it, so `Box<Long>(5000000000)` is a Long literal rather than
                // an Int one that no longer fits once it reaches the erased slot.
                val type = typeArgs.getOrNull(field.typeParamIndex) ?: field.type
                val value = coerceToFloat(lowerExpr(given ?: field.default ?: Expr.NullLiteral), type)
                values.add(value)
                bindings[field.name] = value
            }
        } finally {
            constructionBindings = saved
        }
        return values
    }

    /**
     * `.Name`, `.name(…)` or `.(…)` once [owner] is known to be the type the dot
     * meant: a member of it, a call of that member, or a construction of it.
     */
    private fun lowerInferredMember(expr: Expr.InferredMember, owner: String): IrExpr {
        val ownerRef = Expr.Identifier(owner, expr.line, expr.column, owner.length)
        val args = expr.ctorArgs
        return when {
            args == null -> lowerExpr(Expr.Member(ownerRef, expr.name, expr.line, expr.column, expr.length))
            expr.name.isEmpty() -> lowerExpr(Expr.Call(owner, args, expr.line, expr.column, owner.length))
            else -> lowerExpr(Expr.MethodCall(ownerRef, expr.name, args, expr.line, expr.column))
        }
    }

    /** Provides a default (zero) value for solo fields without explicit defaults. */
    /**
     * Lowers a default-argument expression at a call site.
     *
     * A bare name that resolves to a literal constant becomes that literal, so
     * the value travels with the call rather than as a reference the caller's
     * module may not be able to see.
     */
    private fun lowerDefaultArgument(default: Expr, paramType: IrType): IrExpr {
        // `phase: Phase = .Render` - the parameter's own type is what the dot
        // meant, and it is right here. Defaults are lowered on this path rather
        // than resolved with the rest of the body, so the answer is derived
        // here instead of read back from the resolver.
        if (default is Expr.InferredMember) {
            val owner = when (paramType) {
                is IrType.Named -> paramType.name
                is IrType.Nullable -> (paramType.inner as? IrType.Named)?.name
                else -> null
            }
            if (owner != null) return lowerInferredMember(default, owner)
        }
        if (default is Expr.Identifier) {
            constantLiterals[default.name]?.let { return it }
        }
        val lowered = lowerExpr(default)
        // A default that still came out untyped would be emitted as an opaque
        // pointer; the parameter's own type is the authority here.
        if (lowered is IrExpr.Var && lowered.type == IrType.Any && paramType != IrType.Any) {
            constantLiterals[lowered.name]?.let { return it }
        }
        return lowered
    }

    /** A top-level `fin`/`let`/`inline fin` bound directly to a literal. */
    private fun literalConstant(item: TopLevel): Pair<String, IrExpr>? {
        val (name, initializer) = when (item) {
            is TopLevel.FinDecl -> if (item.threadlocal) return null else item.name to item.initializer
            is TopLevel.LetDecl -> item.name to item.initializer
            is TopLevel.InlineFin -> item.name to item.initializer
            else -> return null
        }
        val value = when (initializer) {
            is Expr.IntLiteral -> IrExpr.IntLiteral(initializer.value)
            is Expr.DoubleLiteral -> IrExpr.DoubleLiteral(initializer.value, text = initializer.text)
            is Expr.BoolLiteral -> IrExpr.BoolLiteral(initializer.value)
            is Expr.CharLiteral -> IrExpr.CharLiteral(initializer.value)
            is Expr.StringLiteral -> IrExpr.StringLiteral(initializer.value)
            // `fin X = -1` parses as a negation of a literal, which is still a
            // constant and is by far the most common non-trivial default.
            is Expr.Unary -> negatedLiteral(initializer)
            else -> null
        } ?: return null
        return name to value
    }

    /**
     * A literal lowered at the width its declaration states, or null.
     *
     * `var x: Cent = 170…727` is an `IntLiteral` read as a `Cent`. Lowering it
     * as an `Int` and widening afterwards is a different program - and for a
     * value wider than 64 bits it is not even the same number, because the
     * `Int` it went through cannot hold one.
     */
    private fun literalAtDeclaredType(expr: Expr, declared: IrType?): IrExpr? {
        val type = declared ?: return null
        return when {
            expr is Expr.IntLiteral && IrType.isInteger(type) ->
                IrExpr.IntLiteral(expr.value, type, expr.text)
            expr is Expr.DoubleLiteral && type in IrType.floatTypes ->
                IrExpr.DoubleLiteral(expr.value, type, expr.text)
            expr is Expr.Unary && expr.op == TokenType.MINUS ->
                when (val inner = literalAtDeclaredType(expr.operand, type)) {
                    is IrExpr.IntLiteral -> IrExpr.IntLiteral(-inner.value, type, inner.text?.let { "-$it" })
                    is IrExpr.DoubleLiteral -> IrExpr.DoubleLiteral(-inner.value, type, inner.text?.let { "-$it" })
                    else -> null
                }
            else -> null
        }
    }

    /** `-<literal>` folded to a literal, or null when it is anything else. */
    private fun negatedLiteral(expr: Expr.Unary): IrExpr? {
        if (expr.op != TokenType.MINUS) return null
        return when (val operand = expr.operand) {
            // A 128-bit literal is negated on its digits: the value beside them
            // is only the low 64 bits, and negating that would fold `Cent`'s
            // minimum into a different number entirely.
            is Expr.IntLiteral -> IrExpr.IntLiteral(
                -operand.value,
                text = operand.text?.let { "-$it" },
            )
            is Expr.DoubleLiteral -> IrExpr.DoubleLiteral(-operand.value, text = operand.text?.let { "-$it" })
            else -> null
        }
    }

    private fun defaultValueForType(type: IrType): IrExpr = when (type) {
        // Every integer starts at zero, whatever its width.
        is IrType.Integer -> IrExpr.IntLiteral(0, type)
        is IrType.ISize, is IrType.USize -> IrExpr.IntLiteral(0, type)
        is IrType.Double -> IrExpr.DoubleLiteral(0.0, type)
        is IrType.Float -> IrExpr.DoubleLiteral(0.0, type)
        is IrType.String -> IrExpr.StringLiteral("")
        is IrType.Bool -> IrExpr.BoolLiteral(false)
        is IrType.Char -> IrExpr.CharLiteral('\u0000')
        else -> IrExpr.Var("__null", IrType.Any)
    }
}
