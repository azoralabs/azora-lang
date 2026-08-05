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

import org.azora.lang.frontend.Param
import org.azora.lang.frontend.OPTIONAL_UNWRAP
import org.azora.lang.frontend.OwnershipOp
import org.azora.lang.frontend.ParamModifier
import org.azora.lang.frontend.lambdaReceiverName
import org.azora.lang.frontend.CastKind
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.MemberCallStyle
import org.azora.lang.frontend.TypeRef
import org.azora.lang.frontend.TypeFunctionDecl
import org.azora.lang.frontend.NumericSuffix
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TestMethod
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.semantic.ComparisonPlan
import org.azora.lang.semantic.SymbolTable
import org.azora.lang.semantic.TypeFunctionEvaluator
import org.azora.lang.semantic.comparisonPlan
import org.azora.lang.semantic.unaryOverloadName
import org.azora.lang.semantic.VariableSymbol
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
class IrGenerator(private val table: SymbolTable) {
    private data class ActiveEffect(val dependencies: Set<String>, val body: List<Stmt>)

    private var typeFunctions = emptyList<TypeFunctionDecl>()
    private var functionDecls = emptyMap<String, FuncDecl>()
    private val generatedTraceFunctions = mutableListOf<IrFunction>()
    private val traceLambdaIndices = mutableMapOf<String, Int>()
    private val knownEnumValues = mutableMapOf<String, IrExpr.EnumLiteral>()
    private var currentTraceOwner: String? = null
    private val contextualValues = ArrayDeque<List<IrExpr>>()
    private val reactiveNames = mutableSetOf<String>()
    private val activeEffects = mutableListOf<ActiveEffect>()
    private var loweringEffect = false
    private var currentGenericTypeParams = emptySet<String>()

    private fun resolveType(ref: TypeRef, typeParams: Set<String> = emptySet()): IrType =
        IrType.resolve(TypeFunctionEvaluator.resolve(ref, typeFunctions, unresolvedParams = typeParams), typeParams)

    /** Scope stack mapping original variable names to their mangled IR names. */
    private val nameScopes = ArrayDeque<MutableMap<String, String>>()
    private var mangledCounter = 0

    private fun pushNameScope() { nameScopes.addLast(mutableMapOf()) }
    private fun popNameScope() { nameScopes.removeLast() }

    private fun lowerScopedBody(stmts: List<Stmt>): List<IrStmt> {
        table.pushScope()
        pushNameScope()
        val effectCount = activeEffects.size
        val names = reactiveNames.toSet()
        return try {
            lowerBody(stmts)
        } finally {
            while (activeEffects.size > effectCount) activeEffects.removeLast()
            reactiveNames.retainAll(names)
            popNameScope()
            table.popScope()
        }
    }

    /** Register a variable name. If it shadows an outer one, mangle it. */
    private fun registerName(name: String): String {
        // Check if name already exists in any outer scope
        val shadows = nameScopes.any { name in it }
        val mangled = if (shadows) "__${name}${mangledCounter++}" else name
        nameScopes.last()[name] = mangled
        return mangled
    }

    /** Look up the mangled name for a variable in the current scope chain. */
    private fun resolveName(name: String): String {
        for (i in nameScopes.indices.reversed()) {
            nameScopes[i][name]?.let { return it }
        }
        return name // global — no mangling
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
        val available = contextualValues.asReversed().flatten()
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

    private fun collectReferencedNames(stmt: IrStmt, names: MutableSet<String>) {
        when (stmt) {
            is IrStmt.VarDecl -> collectReferencedNames(stmt.initializer, names)
            is IrStmt.FinDecl -> collectReferencedNames(stmt.initializer, names)
            is IrStmt.LetDecl -> collectReferencedNames(stmt.initializer, names)
            is IrStmt.Assignment -> collectReferencedNames(stmt.value, names)
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
            is IrExpr.SlotPattern -> Unit
        }
    }

    private fun lowerEffectStatements(body: List<Stmt>): List<IrStmt> {
        val saved = loweringEffect
        loweringEffect = true
        return try {
            lowerScopedBody(body)
        } finally {
            loweringEffect = saved
        }
    }

    private fun lowerEffectBody(body: List<Stmt>): IrStmt =
        IrStmt.Scope(lowerEffectStatements(body), alloc = false)

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
     * default what it reads as — a compile-time constant — and avoids emitting a
     * cross-module global that the unit never defines.
     */
    private val constantLiterals = mutableMapOf<String, IrExpr>()

    fun generate(program: Program): IrProgram {
        typeFunctions = program.typeFunctions
        functionDecls = program.functions.associateBy { it.name }
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
                else -> {}
            }
            literalConstant(item)?.let { (name, value) -> constantLiterals[name] = value }
        }

        // Register import aliases in the global name scope so `import Realm.Item` resolves.
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
                            val children = sourceTests
                                .filter { it.method == TestMethod.This }
                                .map { IrStmt.Scope(lowerTestBody(it.name, it.body)) }
                            listOf(IrTopLevel.Test(item.name, ownBody + children))
                        }
                        else -> listOf(IrTopLevel.Test(item.name, lowerTestBody(item.name, item.body)))
                    }
                }
                is TopLevel.Enum -> listOf(IrTopLevel.Enum(item.name, item.variants))
                is TopLevel.Pack -> {
                    // `bridge pack X` — a compiler-provided type (primitives, Reflected);
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
                                program.realmTypeNamespaces[item.name],
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
                        IrTopLevel.Struct(item.name, fields, program.realmTypeNamespaces[item.name]),
                    )
                    // Lower methods as free functions Name_method (like impl).
                    for (method in item.methods) {
                        if (!method.isInline) result.add(IrTopLevel.Func(lowerMethod(item.name, method)))
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
        // ctor against the fresh value, and hands it back — a plain function, so
        // every backend gets the behaviour without knowing about constructors.
        program.items.filterIsInstance<TopLevel.Impl>().flatMap { item ->
            val struct = table.lookupStruct(item.typeName)
            item.methods.filter { it.name == "ctor" && it.params.isNotEmpty() }.mapNotNull { ctor ->
                if (struct == null) return@mapNotNull null
                val type = IrType.Named(item.typeName)
                val params = ctor.params.map { it.name to resolveType(it.type) }
                val defaults = struct.fields.map { f ->
                    f.default?.let { coerceToFloat(lowerExpr(it), f.type) } ?: defaultValueForType(f.type)
                }
                IrTopLevel.Func(IrFunction(
                    ctorFactoryName(item.typeName, params.size),
                    params,
                    type,
                    listOf(
                        IrStmt.VarDecl("__self", type, IrExpr.StructCtor(item.typeName, struct.fields.map { it.name }, defaults, type)),
                        IrStmt.ExprStmt(IrExpr.Call(
                            "${item.typeName}_ctor",
                            listOf(IrExpr.Var("__self", type)) + params.map { IrExpr.Var(it.first, it.second) },
                            IrType.Unit,
                        )),
                        IrStmt.Return(IrExpr.Var("__self", type)),
                    ),
                ))
            }
        } +
        // Emit __singleton factories for `wrap` registrations (DI container wiring).
        program.items.filterIsInstance<TopLevel.Wrap>().flatMap { wrap ->
            wrap.registrations.mapNotNull { reg ->
                val struct = table.lookupStruct(reg.typeName) ?: return@mapNotNull null
                val loweredArgs = reg.args.map { lowerExpr(it) }
                // Pad with type-based defaults for fields not covered by the construction args.
                val fullArgs = loweredArgs + struct.fields.drop(loweredArgs.size).map { defaultValueForType(it.type) }
                val factory = IrFunction(
                    "__singleton_${reg.typeName}",
                    emptyList(),
                    IrType.Named(reg.typeName),
                    listOf(IrStmt.Return(IrExpr.StructCtor(reg.typeName, struct.fields.map { it.name }, fullArgs, IrType.Named(reg.typeName))))
                )
                IrTopLevel.Func(factory)
            }
        } +
        // Emit extern declarations for `bridge` (FFI) function signatures.
        program.items.filterIsInstance<TopLevel.Bridge>().flatMap { bridge ->
            bridge.funcs.map { sig ->
                val params = sig.params.map { it.name to resolveType(it.type) }
                IrTopLevel.Extern(sig.name, params, resolveType(sig.returnType))
            }
        }
        val enumItems = items.filterIsInstance<IrTopLevel.Enum>()
        val runtimeItems = items.filterNot { it is IrTopLevel.Enum }
        val mainIndex = runtimeItems.indexOfFirst {
            it is IrTopLevel.Func && it.function.name == "main"
        }
        val orderedItems = if (mainIndex >= 0) {
            runtimeItems.take(mainIndex + 1) + enumItems + runtimeItems.drop(mainIndex + 1)
        } else {
            runtimeItems + enumItems
        }
        val lowered = IrProgram(
            program.moduleName,
            generatedTraceFunctions.map { IrTopLevel.Func(it) } + orderedItems,
            buildSpecTables(),
        )
        return IrSymbolCanonicalizer.canonicalize(lowered, program.realmTypeNamespaces)
    }

    /**
     * Collects a dynamic-dispatch table for every spec that has at least one
     * concrete `pack` implementer whose `impl` methods all resolve to real
     * functions. Backends without native trait objects use this to emit a
     * type-id switch; the interpreter/JS ignore it. Decorator contracts and
     * marker specs (no method signatures) are skipped.
     */
    private fun buildSpecTables(): List<IrSpecTable> {
        val bySpec = table.allConformances()
            .filterNot { it.isDecorator }
            .groupBy { it.contractName }
        val tables = mutableListOf<IrSpecTable>()
        for ((specName, confs) in bySpec) {
            val spec = table.lookupSpec(specName) ?: continue
            val callable = spec.methodSigs
                .filterNot { it.value.isProperty }
                .map { (name, sig) -> IrSpecMethod(name, sig.paramTypes, sig.returnType) }
            // A spec property (`prop size[self: Self&]: Int`) dispatches exactly
            // like a nullary method — the backend reads `value.size` through the
            // same stub. An implementer that satisfies the property with a plain
            // field has no getter to point at; that one type drops out of the
            // property's switch rather than out of the table, so its ordinary
            // method dispatch is unaffected.
            val properties = spec.methodSigs
                .filter { it.value.isProperty }
                .map { (name, sig) -> IrSpecMethod(name, emptyList(), sig.returnType) }
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
        val savedEffects = activeEffects.toList()
        reactiveNames.clear()
        activeEffects.clear()

        // Register parameters
        val mangledParams = symbol.params.map { (name, type) ->
            val mangled = registerName(name)
            table.defineVariable(VariableSymbol(name, type, mutable = true)) // all params mutable for simplicity; mut is enforced at type level
            mangled to type
        }

        val body = try {
            lowerBody(func.body)
        } finally {
            reactiveNames.clear()
            reactiveNames.addAll(savedReactiveNames)
            activeEffects.clear()
            activeEffects.addAll(savedEffects)
            popNameScope()
            table.popScope()
            currentTraceOwner = previousOwner
            currentGenericTypeParams = previousTypeParams
        }

        return IrFunction(
            func.name,
            mangledParams,
            symbol.returnType,
            body,
            func.isFlow,
            refParams,
            func.isTask,
            func.isUnsafe,
            isFailable = declaredFailable(func)
        )
    }

    /** True when [func] declares a `T ?! E` return type. */
    private fun declaredFailable(func: FuncDecl): Boolean {
        val ref = (func.returnType as? TypeAnnotation.Explicit)?.ref
        return ref is TypeRef.Failable
    }

    /** The current node type being lowered (for `base` resolution). Null outside a node method. */
    private var currentNodeType: String? = null
    /** The current impl receiver type (for implicit-self field access: bare `size` → `self.size`). */
    private var currentReceiverType: String? = null

    /** Lowers an impl method into a free function `Type_method(self, ...)`. */
    private fun lowerMethod(typeName: String, method: FuncDecl): IrFunction {
        val savedNodeType = currentNodeType
        currentNodeType = typeName
        try {
            return lowerMethodInternal(typeName, method)
        } finally {
            currentNodeType = savedNodeType
        }
    }

    private fun lowerMethodInternal(typeName: String, method: FuncDecl): IrFunction {
        val mangled = mangleMethodSymbol("${typeName}_${method.name}")
        val symbol = table.lookupFunction(mangled)!!
        val previousOwner = currentTraceOwner
        currentTraceOwner = mangled
        knownEnumValues.clear()
        table.pushScope()
        pushNameScope()
        val savedReactiveNames = reactiveNames.toSet()
        val savedEffects = activeEffects.toList()
        reactiveNames.clear()
        activeEffects.clear()
        val mangledParams = symbol.params.map { (name, type) ->
            val m = registerName(name)
            val mutable = name != method.receiverName || method.receiverModifier != ParamModifier.SHARED
            table.defineVariable(VariableSymbol(name, type, mutable = mutable))
            m to type
        }
        contextualValues.addLast(listOf(IrExpr.Var(resolveName(method.receiverName), IrType.Named(typeName))))
        val body = try {
            lowerBody(method.body)
        } finally {
            reactiveNames.clear()
            reactiveNames.addAll(savedReactiveNames)
            activeEffects.clear()
            activeEffects.addAll(savedEffects)
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

    /** A shared friend name scope, or null if no friend realms encountered yet. */
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
     * Lowers a list of statements, handling friend realm blocks by sharing
     * a name scope across all friend realms in the same body.
     */
    private fun lowerBody(stmts: List<Stmt>): List<IrStmt> {
        val hasRealms = stmts.any { it is Stmt.Scope && it.shared }
        val savedFriendScope = friendNameScope

        if (hasRealms) {
            friendNameScope = mutableMapOf()
        }

        // Bindings that persist from one realm block to the next.
        val friendSymbols = mutableMapOf<String, VariableSymbol>()

        val result = mutableListOf<IrStmt>()
        for (stmt in stmts) {
            if (stmt is Stmt.Scope && stmt.shared) {
                // Push the shared realm name scope + symbol table scope
                table.pushScope()
                nameScopes.addLast(friendNameScope!!)
                // Restore bindings left by an earlier realm in this body
                for ((_, sym) in friendSymbols) table.defineVariable(sym)
                val lowered = stmt.body.map { lowerStmt(it) }
                // Hand them on to the next realm in this body
                table.exportCurrentScope(friendSymbols)
                nameScopes.removeLast()
                table.popScope()
                if (stmt.alloc) {
                    // `realm alloc { }` — arena scoping on top of the shared scope.
                    result.add(IrStmt.Scope(lowered, alloc = true))
                } else {
                    result.addAll(lowered)
                }
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
     * `var b = a` on a `Copy` pack has to leave `a` and `b` independent — that
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

    private fun lowerStmt(stmt: Stmt): IrStmt {
        return when (stmt) {
            is Stmt.VarDecl -> {
                val init = withImplicitCopy(
                    stmt.initializer,
                    coerceToFloat(lowerExpr(stmt.initializer), typeAnnotationOrNull(stmt.type)),
                )
                val type = resolveTypeAnnotation(stmt.type, init)
                val mangled = registerName(stmt.name)
                table.defineVariable(VariableSymbol(stmt.name, type, mutable = true))
                if (init is IrExpr.EnumLiteral) knownEnumValues[mangled] = init else knownEnumValues.remove(mangled)
                IrStmt.VarDecl(mangled, type, init)
            }
            is Stmt.FinDecl -> {
                val init = withImplicitCopy(
                    stmt.initializer,
                    coerceToFloat(lowerExpr(stmt.initializer), typeAnnotationOrNull(stmt.type)),
                )
                val type = resolveTypeAnnotation(stmt.type, init)
                val mangled = registerName(stmt.name)
                table.defineVariable(VariableSymbol(stmt.name, type, mutable = false))
                if (init is IrExpr.EnumLiteral) knownEnumValues[mangled] = init else knownEnumValues.remove(mangled)
                IrStmt.FinDecl(mangled, type, init)
            }
            is Stmt.LetDecl -> {
                val init = withImplicitCopy(
                    stmt.initializer,
                    coerceToFloat(lowerExpr(stmt.initializer), typeAnnotationOrNull(stmt.type)),
                )
                val type = resolveTypeAnnotation(stmt.type, init)
                val mangled = registerName(stmt.name)
                table.defineVariable(VariableSymbol(stmt.name, type, mutable = false))
                if (init is IrExpr.EnumLiteral) knownEnumValues[mangled] = init else knownEnumValues.remove(mangled)
                IrStmt.LetDecl(mangled, type, init)
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
                val assignment = IrStmt.Assignment(name, value)
                val triggered = if (!loweringEffect && stmt.name in reactiveNames) {
                    activeEffects.filter { stmt.name in it.dependencies }
                } else {
                    emptyList()
                }
                if (triggered.isEmpty()) assignment
                else IrStmt.Scope(listOf(assignment) + triggered.map { lowerEffectBody(it.body) })
            }
            is Stmt.IndexAssign -> {
                val target = lowerExpr(stmt.target)
                val tt = target.type
                // User-defined index-assign operator (`oper[]=`) on a struct → Type_indexSet(self, i, v).
                if (tt is IrType.Named) {
                    val mangled = table.lookupMethod(tt.name, "indexSet")
                    if (mangled != null) {
                        val index = lowerExpr(stmt.index)
                        val value = lowerExpr(stmt.value)
                        return IrStmt.ExprStmt(IrExpr.Call(mangled, listOf(target, index, value), IrType.Unit))
                    }
                }
                val index = lowerExpr(stmt.index)
                val value = lowerExpr(stmt.value)
                IrStmt.IndexAssign(target, index, value)
            }
            is Stmt.DerefAssign -> {
                val target = lowerExpr(stmt.target)
                val value = lowerExpr(stmt.value)
                IrStmt.ExprStmt(IrExpr.Call("__derefAssign", listOf(target, value), IrType.Unit))
            }
            is Stmt.MemberAssign -> {
                val target = autoDerefMemberTarget(lowerExpr(stmt.target), stmt.name, method = false)
                val fieldType = (target.type as? IrType.Named)
                    ?.let { table.lookupStruct(it.name) }
                    ?.fields?.firstOrNull { it.name == stmt.name }?.type
                val value = coerceToFloat(lowerExpr(stmt.value), fieldType ?: IrType.Any)
                IrStmt.MemberAssign(target, stmt.name, value)
            }
            is Stmt.Return -> IrStmt.Return(stmt.value?.let { coerceToFloat(lowerExpr(it), currentReturnType) })
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
                IrStmt.Scope(stmts, stmt.alloc)
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
                    val operName = if (stmt.reverse) "operreverse.." else "oper.."
                    if (table.lookupMethod(rangeTypeName, operName) == null) {
                        val sym = if (stmt.reverse) "reverse.." else ".."
                        error("type '$rangeTypeName' does not support the range operator '$sym' (declare 'impl oper $sym for $rangeTypeName')")
                    }
                    val step = stmt.step?.let { lowerExpr(it) }
                    table.pushScope()
                    pushNameScope()
                    val counter = registerName(stmt.name)
                    table.defineVariable(VariableSymbol(stmt.name, IrType.Int, mutable = true))
                    val body = lowerBody(stmt.body)
                    popNameScope()
                    table.popScope()
                    IrStmt.For(counter, start, end, range.inclusive, body, step = step, reverse = stmt.reverse, label = stmt.label)
                } else {
                    // For-in over a non-range iterable (array, flow, channel): for-each.
                    val iterable = lowerExpr(stmt.iterable)
                    val elemType = when (val type = iterable.type) {
                        is IrType.Array -> type.element
                        is IrType.Set -> type.element
                        else -> IrType.Any
                    }
                    table.pushScope()
                    pushNameScope()
                    val elem = registerName(stmt.name)
                    table.defineVariable(VariableSymbol(stmt.name, elemType, mutable = false))
                    val body = lowerBody(stmt.body)
                    popNameScope()
                    table.popScope()
                    IrStmt.ForEach(elem, iterable, body)
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
                    val reset = IrStmt.ExprStmt(IrExpr.MethodCall(iter, "reset", emptyList(), IrType.Unit))
                    val cond = IrExpr.MethodCall(iter, "hasNext", emptyList(), IrType.Bool)
                    IrStmt.Scope(listOf(reset, IrStmt.While(cond, body, stmt.label)), alloc = false)
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
                table.defineVariable(VariableSymbol(stmt.name, type, mutable = true))
                reactiveNames.add(stmt.name)
                IrStmt.VarDecl(mangled, type, init)
            }
            is Stmt.Effect -> {
                if (stmt.deferred) {
                    IrStmt.Defer(lowerEffectStatements(stmt.body))
                } else {
                    val loweredBody = lowerEffectStatements(stmt.body)
                    val dependencies = stmt.dependencies
                        ?.flatMap(::dependencyNames)
                        ?.toSet()
                        ?: referencedNames(loweredBody).intersect(reactiveNames)
                    activeEffects.add(ActiveEffect(dependencies, stmt.body))
                    IrStmt.Scope(loweredBody, alloc = false)
                }
            }
            is Stmt.WithContext -> {
                val values = stmt.values.map(::lowerExpr)
                contextualValues.addLast(values)
                val body = try {
                    lowerScopedBody(stmt.body)
                } finally {
                    contextualValues.removeLast()
                }
                IrStmt.Scope(body, alloc = false)
            }
            is Stmt.Yield -> IrStmt.Yield(lowerExpr(stmt.value))
            is Stmt.When -> {
                val scrutinee = lowerExpr(stmt.scrutinee)
                val branches = stmt.branches.map { b ->
                    var slotBindings: List<Pair<String, IrType>>? = null
                    val irPatterns = b.patterns.map { pat ->
                        if (pat is Expr.MethodCall && pat.target is Expr.Identifier &&
                            table.lookupSlot(pat.target.name) != null) {
                            val slotVariants = table.lookupSlot(pat.target.name)!!
                            val variant = slotVariants.find { it.first == pat.name }
                            val bindNames = pat.args.map { (it as Expr.Identifier).name }
                            if (variant != null) slotBindings = bindNames.zip(variant.second)
                            IrExpr.SlotPattern(pat.target.name, pat.name, bindNames, variant?.second ?: emptyList())
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
                IrStmt.ExprStmt(IrExpr.Call("__panic", listOf(lowerExpr(stmt.message)), IrType.Unit))
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

    private fun suffixToIntType(suffix: NumericSuffix): IrType = when (suffix) {
        NumericSuffix.NONE -> IrType.Int
        NumericSuffix.BYTE -> IrType.Byte
        NumericSuffix.UBYTE -> IrType.UByte
        NumericSuffix.SHORT -> IrType.Short
        NumericSuffix.USHORT -> IrType.UShort
        NumericSuffix.UINT -> IrType.UInt
        NumericSuffix.LONG -> IrType.Long
        NumericSuffix.ULONG -> IrType.ULong
        NumericSuffix.CENT -> IrType.Cent
        NumericSuffix.UCENT -> IrType.UCent
        NumericSuffix.FLOAT -> IrType.Float
        NumericSuffix.DECIMAL -> IrType.Decimal
    }

    private fun suffixToFloatType(suffix: NumericSuffix): IrType = when (suffix) {
        NumericSuffix.FLOAT -> IrType.Float
        NumericSuffix.DECIMAL -> IrType.Decimal
        else -> IrType.Double
    }

    private fun defaultTraceLevel(line: Int): Expr {
        val first = table.lookupEnum("LogLevel")?.firstOrNull() ?: "Debug"
        return Expr.Member(Expr.Identifier("LogLevel", line), first, line)
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
        val functionName = "__${owner}_lmbda$index"
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

    /** The factory that runs a declared `ctor` of the given arity. */
    private fun ctorFactoryName(typeName: String, arity: Int): String = "__ctor_${typeName}_$arity"

    private fun lowerExpr(expr: Expr): IrExpr {
        return when (expr) {
            is Expr.IntLiteral -> IrExpr.IntLiteral(expr.value, suffixToIntType(expr.suffix))
            is Expr.DoubleLiteral -> IrExpr.DoubleLiteral(expr.value, suffixToFloatType(expr.suffix))
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
                val numeric = IrType.integerTypes + IrType.floatTypes
                fun isNumericish(t: IrType) = t in numeric || t == IrType.Char
                fun isPointerish(t: IrType) =
                    t == IrType.String || t == IrType.Any || t is IrType.Array || t is IrType.Map || t is IrType.Set ||
                        t is IrType.Named || t is IrType.Pointer || t is IrType.Nullable || t is IrType.Tuple
                val innerType = inner.type
                // A user-declared `oper as<U> [self: T&]: U` takes precedence over the
                // built-in conversions: a type that says how it converts should be
                // asked, rather than reinterpreted. Each cast form asks its own
                // member, so `as`, `as?` and `as*` never stand in for one another.
                val castMember = when (expr.kind) {
                    CastKind.STATIC -> "operas"
                    CastKind.DYNAMIC -> "operas?"
                    CastKind.REINTERPRET -> "operas*"
                }
                val userCast = (innerType as? IrType.Named)
                    ?.let { table.lookupMethod(it.name, castMember) }
                if (userCast != null) {
                    val declared = table.lookupFunction(userCast)?.returnType
                    // The operator states what it returns; a checked cast that did not
                    // say so still yields `T?`, which is what `as?` means.
                    val result = declared?.takeUnless { it == IrType.Any || it == IrType.Unit }
                        ?: if (expr.kind == CastKind.DYNAMIC) IrType.Nullable(target) else target
                    return IrExpr.Call(userCast, listOf(inner), result)
                }
                when {
                    // `x as? T` / `std::dyncast<T>(x)` — runtime-checked downcast to `T?`:
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
                    // `x as String` / `std::cast<String>(x)` — converting cast: stringify
                    // the value via the single-part string-template machinery (equivalent
                    // to "${x}"), which every backend already supports. `as*` (reinterpret)
                    // never stringifies.
                    expr.kind == CastKind.STATIC && target == IrType.String ->
                        IrExpr.StringTemplate(listOf(IrExpr.IrTemplatePart.Expr(inner)))
                    target == innerType -> inner
                    // Upcast a concrete `pack` to a spec it implements: mark it as a
                    // representation coercion so native backends can box it into a fat
                    // pointer for dynamic dispatch. The interpreter/JS treat a NumCast
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
                IrExpr.Call("__isCheck", listOf(inner, IrExpr.StringLiteral(expr.typeName)), IrType.Bool)
            }
            is Expr.InlineForArgs ->
                error("'inline for' argument reached IR generation at line ${expr.line}")
            is Expr.InCheck -> {
                // `x in xs` — membership, the same shape a `contains` call has.
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
                val sym = table.lookupVariable(expr.name)
                // `Vec3f::zero` names a member of whatever `Vec3f` aliases, as it did
                // when the resolver typed it.
                val aliased = if (sym == null) throughTypeAlias(expr.name) else null
                val aliasedSym = aliased?.let { table.lookupVariable(it) }
                if (sym != null) {
                    IrExpr.Var(resolveName(expr.name), sym.type)
                } else if (aliasedSym != null) {
                    IrExpr.Var(resolveName(aliased), aliasedSym.type)
                } else {
                    // Implicit self: bare field name in an impl method → self.field
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
            is Expr.Binary -> {
                var left = lowerExpr(expr.left)
                var right = lowerExpr(expr.right)
                // Pointer arithmetic: ptr + n, ptr - n, ptr - ptr
                if (left.type is IrType.Pointer && right.type in IrType.integerTypes &&
                    (expr.op == TokenType.PLUS || expr.op == TokenType.MINUS)) {
                    val fn = if (expr.op == TokenType.PLUS) "__ptrAdd" else "__ptrSub"
                    return IrExpr.Call(fn, listOf(left, right), left.type)
                }
                if (left.type in IrType.integerTypes && right.type is IrType.Pointer && expr.op == TokenType.PLUS) {
                    return IrExpr.Call("__ptrAdd", listOf(right, left), right.type)
                }
                if (left.type is IrType.Pointer && right.type is IrType.Pointer && expr.op == TokenType.MINUS) {
                    return IrExpr.Call("__ptrDiff", listOf(left, right), IrType.Int)
                }
                // A primitive left operand may still have a declared operator, but only
                // one that names its operand — see the resolver for why.
                if (left.type !is IrType.Named && left.type in IrType.numericTypes) {
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
                        // means — `NaN` makes all four false without the compiler
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
                    else -> left.type // bitwise / shift — keep the left operand type
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
                // Value call `receiver(args)` — lower the receiver (a function value)
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
                // Resolve import aliases (`import Realm.Item` maps Item to Realm__Item).
                // `Self(…)` inside an impl builds the type the impl is on.
                val calleeName = if (expr.callee == "Self") currentReceiverType ?: expr.callee else expr.callee
                val actualCallee = table.aliasMap[calleeName] ?: calleeName
                val struct = table.lookupStruct(actualCallee) ?: table.lookupStruct(calleeName)
                if (struct != null && struct.isUnion) {
                    // `Value(f: 1.5)` — exactly one member is named, and it is the
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
                    // Handle named arguments — reorder to field order; omitted fields use their defaults.
                    val args = if (expr.args.any { it is Expr.NamedArg }) {
                        val slots = mapNamedArguments(expr.args, struct.fields.map { it.name })
                        struct.fields.mapIndexed { index, f ->
                            coerceToFloat(lowerExpr(slots[index] ?: f.default ?: Expr.NullLiteral), f.type)
                        }
                    } else {
                        // Positional — pad omitted trailing fields with their defaults (`Pack<T>()`).
                        val padded = expr.args.mapIndexed { i, a ->
                            struct.fields.getOrNull(i)?.let { coerceToFloat(lowerExpr(a), it.type) }
                                ?: lowerExpr(a)
                        }.toMutableList()
                        for (i in expr.args.size until struct.fields.size) {
                            padded.add(coerceToFloat(
                                lowerExpr(struct.fields[i].default ?: Expr.NullLiteral),
                                struct.fields[i].type,
                            ))
                        }
                        padded
                    }
                    // A declared `ctor` of the same arity takes precedence over
                    // filling fields positionally — it is the constructor the
                    // author wrote, and skipping it would leave its work undone.
                    val declaredCtor = table.lookupFunction(ctorFactoryName(actualCallee, expr.args.size))
                    if (declaredCtor != null && expr.args.none { it is Expr.NamedArg }) {
                        return IrExpr.Call(
                            declaredCtor.name,
                            expr.args.mapIndexed { i, a ->
                                declaredCtor.params.getOrNull(i)
                                    ?.let { coerceToFloat(lowerExpr(a), it.second) } ?: lowerExpr(a)
                            },
                            IrType.Named(actualCallee),
                        )
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
                    // Handle named arguments — reorder to param order (pre-spread only)
                    val args = if (expr.args.any { it is Expr.NamedArg } && func.paramNames.isNotEmpty()) {
                        val slots = mapNamedArguments(expr.args, func.paramNames)
                        func.paramNames.indices.mapNotNull { slots[it]?.let(::lowerExpr) }
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
                            expr.typeArgs.getOrNull(typeParamIndex)?.let(::resolveType)
                                ?: args.getOrNull(func.params.size - 1)?.type
                        }
                    // Variadic: pack extra args into an array for the last param.
                    val hasSpread = args.any { it is IrExpr.Spread }
                    val effectiveArgs = if (func.isVariadic && args.size >= func.params.size - 1) {
                        val fixed = args.take(func.params.size - 1)
                        val rest = args.drop(func.params.size - 1)
                        val elemType = homogeneousVariadicType
                            ?: (func.params.last().second as? IrType.Array)?.element
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
                    val callType = when {
                        expr.callee == "async" -> {
                            val result = (effectiveArgs.firstOrNull()?.type as? IrType.Function)?.ret ?: IrType.Any
                            IrType.Task(result)
                        }
                        func.isTask -> IrType.Task(func.returnType)
                        homogeneousVariadicType != null && func.returnType is IrType.Array ->
                            IrType.Array(homogeneousVariadicType)
                        funcDecl != null && expr.typeArgs.isNotEmpty() &&
                            expr.typeArgs.none { typeRefMentionsAny(it, currentGenericTypeParams) } -> {
                            val returnRef = (funcDecl.returnType as? TypeAnnotation.Explicit)?.ref
                            if (returnRef == null) {
                                func.returnType
                            } else {
                                val substitutions = func.typeParams
                                    .zip(expr.typeArgs)
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
                    val displayArgs = if (func.name == "std__println" || func.name == "std__print") {
                        effectiveArgs.map(::stringifyEnum)
                    } else {
                        // An integer literal passed where a float is declared becomes
                        // one here, so the callee never receives the wrong machine type.
                        effectiveArgs.mapIndexed { i, arg ->
                            func.params.getOrNull(i)?.let { coerceToFloat(arg, it.second) } ?: arg
                        }
                    }
                    return IrExpr.Call(func.name, displayArgs, callType)
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
                        expr.args.map { lowerExpr(it) } + contextualArguments(v.type, expr.args.size)
                    }
                    return IrExpr.Call(
                        "",
                        args,
                        v.type.ret,
                        receiver = IrExpr.Var(resolveName(expr.callee), v.type),
                    )
                }
                // Compiler builtin: `std::convert::toString(x)` stringifies any
                // value (implemented natively by CTCE and every backend).
                if (expr.callee == "std__convert__toString") {
                    val args = expr.args.map { stringifyEnum(lowerExpr(it)) }
                    return IrExpr.Call("std__convert__toString", args, IrType.String)
                }
                // Extension method reached through a `with value { … }` scope:
                // `with c { bump() }` lowers to `Counter_bump(c)`.
                // A realm-qualified call reaches its contextual receiver too, matching
                // how the resolver typed it: `std::yield(1)` names the member `yield`.
                val contextualName = expr.callee.substringAfterLast("__")
                for (ctx in contextualValues.asReversed().flatten()) {
                    val ct = ctx.type
                    if (ct is IrType.Named) {
                        val mangled = table.lookupMethod(ct.name, contextualName)
                        if (mangled != null) {
                            val func = table.lookupFunction(mangled)!!
                            val args = expr.args.map { lowerExpr(it) }
                            return IrExpr.Call(mangled, listOf(ctx) + args, func.returnType)
                        }
                    }
                }
                error("undefined function or variable '${expr.callee}'")
            }
            is Expr.Grouping -> lowerExpr(expr.expr)
            is Expr.Range -> error("range expressions can only be used as for-loop iterables")
            is Expr.ArrayLiteral -> {
                val elems = expr.elements.map { lowerExpr(it) }
                val elemType = if (elems.isEmpty()) IrType.Any else elems.first().type
                // A literal carries its compile-time element count as the array's size.
                val size: kotlin.Long? = if (elems.isEmpty()) null else elems.size.toLong()
                IrExpr.ArrayLiteral(elems, IrType.Array(elemType, size))
            }
            is Expr.SetLiteral -> {
                val elems = expr.elements.map { lowerExpr(it) }
                val elemType = if (elems.isEmpty()) IrType.Any else elems.first().type
                IrExpr.SetLit(elems, IrType.Set(elemType))
            }
            is Expr.MapLit -> {
                val entries = expr.entries.map { lowerExpr(it.first) to lowerExpr(it.second) }
                val keyType = entries.firstOrNull()?.first?.type ?: IrType.Any
                val valType = entries.firstOrNull()?.second?.type ?: IrType.Any
                IrExpr.MapLit(entries, IrType.Map(keyType, valType))
            }
            is Expr.Alloc -> {
                val value = lowerExpr(expr.value)
                // alloc [a, b, c] → pointer to element type (buffer for arithmetic).
                val pointee = (value.type as? IrType.Array)?.element ?: value.type
                IrExpr.Call("__alloc", listOf(value), IrType.Pointer(pointee))
            }
            is Expr.AllocBuffer -> {
                // alloc T(count) → buffer of `count` T's → T* (C++-style).
                val count = lowerExpr(expr.count)
                val elem = if (IrType.isPrimitiveName(expr.typeName)) IrType.fromName(expr.typeName) else IrType.Any
                IrExpr.Call("__allocBuffer", listOf(count), IrType.Pointer(elem))
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
                // `take opt.require()` — the primitive that moves a value out of
                // an optional. The optional is emptied; see [emptyAfterTake].
                val unwrapped = expr.value
                if (expr.op == OwnershipOp.TAKE && unwrapped is Expr.MethodCall &&
                    unwrapped.name == "require" && unwrapped.args.isEmpty()
                ) {
                    emptyAfterTake(unwrapped.target, lowerExpr(unwrapped.target))
                }
                val value = lowerExpr(expr.value)
                // `take` moves the value; there is nothing to copy. `clone` and
                // `isolated` both produce an independent one.
                // `lend` moves the value in exactly as `take` does; what differs
                // is only that the caller keeps the right to it afterwards.
                if (expr.op == OwnershipOp.TAKE || expr.op == OwnershipOp.LEND || expr.op.isBorrow) value
                else IrExpr.Call("__isolated", listOf(value), value.type)
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
                val target = lowerExpr(expr.target)
                val tt = target.type
                // User-defined index operator (`oper[]`) on a struct → Type_index(self, i).
                if (tt is IrType.Named) {
                    val mangled = table.lookupMethod(tt.name, "index")
                    if (mangled != null) {
                        val func = table.lookupFunction(mangled)!!
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
                // NOTE: `.size`/`.length` are left as runtime intrinsics (handled by
                // each backend) even for compile-time-sized arrays — existing dynamic
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
                            // It's a prop — lower to a method call Type_name(self).
                            return IrExpr.Call(mangled, listOf(target), func.returnType)
                        }
                    }
                    // A property required by a spec (`list.size` where `list: List<T>`)
                    // has neither a field nor a single impl to name here — the backend
                    // dispatches it. Carry the spec's declared type so arithmetic and
                    // comparisons on the result still lower.
                    val specProp = table.lookupSpecProp(tt2.name, expr.name)
                    if (specProp != null) return IrExpr.Member(target, expr.name, specProp)
                }
                val memberType = when {
                    expr.name in setOf("length", "size") && (target.type is IrType.Array || target.type is IrType.Map || target.type is IrType.Set || target.type == IrType.String) -> IrType.Int
                    expr.name == "data" && tt2 is IrType.Array -> IrType.Pointer(tt2.element)
                    (expr.name == "isEmpty" || expr.name == "isNotEmpty") && (target.type is IrType.Array || target.type is IrType.Map || target.type is IrType.Set) -> IrType.Bool
                    else -> {
                        val tt = target.type
                        if (tt is IrType.Named) table.lookupStruct(tt.name)?.field(expr.name)?.type ?: IrType.Any
                        else IrType.Any
                    }
                }
                IrExpr.Member(target, expr.name, memberType)
            }
            is Expr.MethodCall -> {
                // `x.clone()` with no written member — the compiler-provided
                // `Clone` default is an independent deep copy.
                if (expr.name == "clone" && expr.args.isEmpty()) {
                    val target = lowerExpr(expr.target)
                    // A pack registers its conformance under its name; a primitive
                    // under the spelling of its own type.
                    val name = (target.type as? IrType.Named)?.name ?: target.type.toString()
                    if (table.lookupMethod(name, "clone") == null && table.conformsTo(name, "Clone")) {
                        return IrExpr.Call("__isolated", listOf(target), target.type)
                    }
                }
                // `opt.require()` / `opt.take()` — dropping the optional off a
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
                    val args = expr.args.map { lowerExpr(it) }
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
                        val func = table.lookupFunction(mangled)!!
                        if (func.memberCallStyle == MemberCallStyle.PROPERTY) {
                            error("property '${expr.name}' must be accessed without parentheses")
                        }
                        val args = expr.args.map { lowerExpr(it) }
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
                        val args = expr.args.map { lowerExpr(it) }
                        return IrExpr.Call(mangled, listOf(target) + args, func.returnType)
                    }
                }
                // Call on a spec-typed value (`p.build()` where `p: Plugin`): no
                // concrete method exists on the spec name, so keep it as a
                // `MethodCall` for dynamic dispatch. The interpreter resolves it via
                // the receiver's `__type`; native backends via the spec table. We
                // still stamp the erased return type from the spec signature.
                if (tt is IrType.Named) {
                    val sig = table.lookupSpecMethod(tt.name, expr.name)
                    if (sig != null && !sig.isProperty) {
                        val args = expr.args.map { lowerExpr(it) }
                        return IrExpr.MethodCall(target, expr.name, args, sig.returnType)
                    }
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
                        is Expr.StringTemplatePart.Expr -> IrExpr.IrTemplatePart.Expr(stringifyEnum(lowerExpr(p.expr)))
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
            is Expr.IfExpr -> {
                val condition = lowerExpr(expr.condition)
                val thenExpr = lowerExpr(expr.thenExpr)
                val elseExpr = lowerExpr(expr.elseExpr)
                IrExpr.IfExpr(condition, thenExpr, elseExpr, thenExpr.type)
            }
            is Expr.CatchExpr -> {
                val e = lowerExpr(expr.expr)
                val f = lowerExpr(expr.fallback)
                IrExpr.CatchExpr(e, f, e.type)
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
                table.pushScope()
                pushNameScope()
                // `{ body }` carries the parser's implicit `it`. When the callable
                // type has no ordinary parameters there is nothing for it to stand
                // for, and keeping it would shift every contextual receiver by one
                // argument at the call site. The resolver drops it from the type;
                // the closure has to drop it from its parameter list to match.
                val ordinarySources = if (
                    callableType.params.isEmpty() &&
                    expr.params.size == 1 &&
                    expr.params[0].name == "it"
                ) emptyList() else expr.params
                val ordinaryParams = ordinarySources.mapIndexed { index, p ->
                    val t = callableType.params.getOrNull(index) ?: resolveType(p.type)
                    val m = registerName(p.name)
                    table.defineVariable(VariableSymbol(p.name, t))
                    m to t
                }
                // A lambda that inherits its receivers (`std::sequence { std::yield(1) }`)
                // declares none, so the bindings are synthesized here under the name the
                // resolver already used, and made available to calls in the body exactly
                // as a `with` block would.
                val inheritsReceivers = expr.receivers.isEmpty() && callableType.receivers.isNotEmpty()
                val receiverSources = if (inheritsReceivers) {
                    callableType.receivers.indices.map {
                        Param(lambdaReceiverName(expr.line, expr.column, it), TypeRef.Named("Any"))
                    }
                } else {
                    expr.receivers
                }
                val receiverParams = receiverSources.mapIndexed { index, p ->
                    val t = callableType.receivers.getOrNull(index) ?: resolveType(p.type)
                    val m = registerName(p.name)
                    table.defineVariable(VariableSymbol(p.name, t, mutable = false))
                    m to t
                }
                // A lambda's receivers are contextual values for its body whether it
                // named them or inherited them, matching how the resolver typed it —
                // otherwise a bare call that type-checked would fail to lower.
                if (receiverParams.isNotEmpty()) {
                    contextualValues.addLast(receiverParams.map { (name, t) -> IrExpr.Var(name, t) })
                }
                val body = lowerBody(expr.body)
                if (receiverParams.isNotEmpty()) contextualValues.removeLast()
                popNameScope()
                table.popScope()
                val retType = body.mapNotNull { (it as? IrStmt.Return)?.value?.type }.firstOrNull() ?: IrType.Unit
                val irParams = ordinaryParams + receiverParams
                IrExpr.Lambda(
                    irParams,
                    body,
                    callableType.copy(ret = retType),
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
            "contains", "isEmpty", "isNotEmpty" -> IrType.Bool
            "indexOf" -> IrType.Int
            "fill" -> receiverType
            else -> IrType.Any
        }
        is IrType.Set -> when (name) {
            "add", "remove", "contains", "isEmpty", "isNotEmpty" -> IrType.Bool
            "clear" -> IrType.Unit
            else -> IrType.Any
        }
        is IrType.Map -> when (name) {
            "get" -> receiverType.value
            "put", "clear" -> IrType.Unit
            "containsKey", "isEmpty", "isNotEmpty" -> IrType.Bool
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
        if (target in IrType.numericTypes && target != expr.type &&
            (expr.type in IrType.integerTypes || expr.type in IrType.floatTypes) &&
            isUntypedIntConstant(expr)
        ) {
            IrExpr.NumCast(expr, target)
        } else {
            expr
        }

    /** True for an integer constant the resolver would have let adopt another type. */
    private fun isUntypedIntConstant(expr: IrExpr): Boolean = when (expr) {
        is IrExpr.DoubleLiteral -> expr.type == IrType.Double
        is IrExpr.IntLiteral -> expr.type == IrType.Int
        is IrExpr.Unary -> expr.op == IrUnaryOp.NEG && isUntypedIntConstant(expr.operand)
        else -> false
    }

    private fun numericResultType(a: IrType, b: IrType): IrType {
        if (a == b) return a
        if (a !in IrType.numericTypes || b !in IrType.numericTypes) return a
        if (a in IrType.floatTypes || b in IrType.floatTypes) {
            if (a == IrType.Decimal || b == IrType.Decimal) return IrType.Decimal
            if (a == IrType.Double || b == IrType.Double) return IrType.Double
            return IrType.Float
        }
        val rank = mapOf(
            IrType.Byte to 1, IrType.UByte to 1, IrType.Short to 2, IrType.UShort to 2,
            IrType.Int to 3, IrType.UInt to 3, IrType.Long to 4, IrType.ULong to 4,
            IrType.ISize to 4, IrType.USize to 4,
            IrType.Cent to 5, IrType.UCent to 5,
        )
        return if ((rank[a] ?: 0) >= (rank[b] ?: 0)) a else b
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

    /** Provides a default (zero) value for solo fields without explicit defaults. */
    /**
     * Lowers a default-argument expression at a call site.
     *
     * A bare name that resolves to a literal constant becomes that literal, so
     * the value travels with the call rather than as a reference the caller's
     * module may not be able to see.
     */
    private fun lowerDefaultArgument(default: Expr, paramType: IrType): IrExpr {
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
            is Expr.DoubleLiteral -> IrExpr.DoubleLiteral(initializer.value)
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

    /** `-<literal>` folded to a literal, or null when it is anything else. */
    private fun negatedLiteral(expr: Expr.Unary): IrExpr? {
        if (expr.op != TokenType.MINUS) return null
        return when (val operand = expr.operand) {
            is Expr.IntLiteral -> IrExpr.IntLiteral(-operand.value)
            is Expr.DoubleLiteral -> IrExpr.DoubleLiteral(-operand.value)
            else -> null
        }
    }

    private fun defaultValueForType(type: IrType): IrExpr = when (type) {
        is IrType.Int -> IrExpr.IntLiteral(0, type)
        is IrType.Long -> IrExpr.IntLiteral(0, type)
        is IrType.Byte -> IrExpr.IntLiteral(0, type)
        is IrType.Short -> IrExpr.IntLiteral(0, type)
        is IrType.UInt -> IrExpr.IntLiteral(0, type)
        is IrType.ULong, is IrType.ISize, is IrType.USize -> IrExpr.IntLiteral(0, type)
        is IrType.Double -> IrExpr.DoubleLiteral(0.0, type)
        is IrType.Float -> IrExpr.DoubleLiteral(0.0, type)
        is IrType.String -> IrExpr.StringLiteral("")
        is IrType.Bool -> IrExpr.BoolLiteral(false)
        is IrType.Char -> IrExpr.CharLiteral('\u0000')
        else -> IrExpr.Var("__null", IrType.Any)
    }
}
