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
import org.azora.lang.semantic.SymbolTable
import org.azora.lang.semantic.TypeFunctionEvaluator
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
    private var typeFunctions = emptyList<TypeFunctionDecl>()
    private var functionDecls = emptyMap<String, FuncDecl>()
    private val generatedTraceFunctions = mutableListOf<IrFunction>()
    private val traceLambdaIndices = mutableMapOf<String, Int>()
    private val knownEnumValues = mutableMapOf<String, IrExpr.EnumLiteral>()
    private var currentTraceOwner: String? = null

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
        return try {
            lowerBody(stmts)
        } finally {
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

    /**
     * Generates a typed [IrProgram] from the given AST.
     *
     * Inline functions are filtered out since they have already been
     * substituted at their call sites by the CTCE evaluator.
     *
     * @param program the CTCE-stabilized, type-checked AST
     * @return the lowered [IrProgram]
     */
    fun generate(program: Program): IrProgram {
        typeFunctions = program.typeFunctions
        functionDecls = program.functions.associateBy { it.name }
        generatedTraceFunctions.clear()
        traceLambdaIndices.clear()
        knownEnumValues.clear()
        currentTraceOwner = null
        nameScopes.clear()
        mangledCounter = 0
        pushNameScope() // global scope

        // Register global names
        for (item in program.items) {
            when (item) {
                is TopLevel.FinDecl -> if (item.threadlocal) nameScopes.last()[item.name] = "__tl__${item.name}" else registerName(item.name)
                is TopLevel.VarDecl -> if (item.threadlocal) nameScopes.last()[item.name] = "__tl__${item.name}" else registerName(item.name)
                is TopLevel.LetDecl -> registerName(item.name)
                else -> {}
            }
        }

        // Register import aliases in the global name scope so `import Zone.Item` resolves.
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
                                .map { IrStmt.Zone(lowerTestBody(it.name, it.body)) }
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
                        listOf(IrTopLevel.Struct(item.name, fields))
                    }
                }
                is TopLevel.Solo -> {
                    val fields = item.fields.map { IrField(it.name, resolveType(it.type), it.mutable) }
                    val result = mutableListOf<IrTopLevel>(IrTopLevel.Struct(item.name, fields))
                    // Lower methods as free functions Name_method (like impl).
                    for (method in item.methods) {
                        if (!method.isInline) result.add(IrTopLevel.Func(lowerMethod(item.name, method)))
                    }
                    // Emit a __singleton_Name factory that constructs the struct from field defaults.
                    val defaults = item.fields.map { f ->
                        if (f.default != null) lowerExpr(f.default)
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
                is TopLevel.Node -> {
                    // Emit the struct (fields already flattened by SymbolCollector).
                    val struct = table.lookupStruct(item.name)!!
                    val fields = struct.fields.map { IrField(it.name, it.type, it.mutable) }
                    val result = mutableListOf<IrTopLevel>(IrTopLevel.Struct(item.name, fields))
                    // Lower own methods as free functions.
                    for (method in item.methods) {
                        if (!method.isInline) result.add(IrTopLevel.Func(lowerMethod(item.name, method)))
                    }
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
                is TopLevel.View -> {
                    val decl = FuncDecl(item.name, item.params, TypeAnnotation.Inferred, item.body, false, emptyList(), item.line, item.column)
                    listOf(IrTopLevel.Func(lowerFunction(decl)))
                }
                is TopLevel.Hook -> {
                    // A hook is lowered as a function `__hook_<name>` with no params.
                    val decl = FuncDecl("__hook_${item.name}", emptyList(), TypeAnnotation.Inferred, item.body, false, emptyList(), item.line, item.column)
                    listOf(IrTopLevel.Func(lowerFunction(decl)))
                }
                else -> emptyList() // Inline constructs already resolved by CTCE
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
        return IrProgram(
            program.moduleName,
            generatedTraceFunctions.map { IrTopLevel.Func(it) } + orderedItems,
        )
    }

    private fun lowerFunction(func: FuncDecl): IrFunction {
        val symbol = table.lookupFunction(func.name)!!
        val previousOwner = currentTraceOwner
        currentTraceOwner = func.name
        knownEnumValues.clear()
        // Collect ref/out param indices from the AST FuncDecl.
        val refParams = func.params.indices.filter {
            func.params[it].modifier in setOf("ref", "out", "mut ref")
        }.toSet()
        table.pushScope()
        pushNameScope()

        // Register parameters
        val mangledParams = symbol.params.map { (name, type) ->
            val mutable = func.params.getOrNull(symbol.params.indexOfFirst { it.first == name })?.modifier == "mut"
            val mangled = registerName(name)
            table.defineVariable(VariableSymbol(name, type, mutable = true)) // all params mutable for simplicity; mut is enforced at type level
            mangled to type
        }

        val body = try {
            lowerBody(func.body)
        } finally {
            popNameScope()
            table.popScope()
            currentTraceOwner = previousOwner
        }

        return IrFunction(
            func.name,
            mangledParams,
            symbol.returnType,
            body,
            func.isFlow,
            refParams,
            func.isTask,
            func.isUnsafe
        )
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
        val mangled = "${typeName}_${method.name}"
        val symbol = table.lookupFunction(mangled)!!
        val previousOwner = currentTraceOwner
        currentTraceOwner = mangled
        knownEnumValues.clear()
        table.pushScope()
        pushNameScope()
        val mangledParams = symbol.params.map { (name, type) ->
            val m = registerName(name)
            val mutable = name != "self" || method.receiverModifier != "ref"
            table.defineVariable(VariableSymbol(name, type, mutable = mutable))
            m to type
        }
        val body = try {
            lowerBody(method.body)
        } finally {
            popNameScope()
            table.popScope()
            currentTraceOwner = previousOwner
        }
        return IrFunction(mangled, mangledParams, symbol.returnType, body)
    }

    /** A shared friend name scope, or null if no friend zones encountered yet. */
    private var friendNameScope: MutableMap<String, String>? = null

    /**
     * Lowers a list of statements, handling friend zone blocks by sharing
     * a name scope across all friend zones in the same body.
     */
    private fun lowerBody(stmts: List<Stmt>): List<IrStmt> {
        val hasFriendZones = stmts.any { it is Stmt.FriendZone }
        val savedFriendScope = friendNameScope

        if (hasFriendZones) {
            friendNameScope = mutableMapOf()
        }

        // Shared symbol table scope for friend zones (persists variables between blocks)
        val friendSymbols = mutableMapOf<String, VariableSymbol>()

        val result = mutableListOf<IrStmt>()
        for (stmt in stmts) {
            if (stmt is Stmt.FriendZone) {
                // Push shared friend name scope + symbol table scope
                table.pushScope()
                nameScopes.addLast(friendNameScope!!)
                // Restore previously saved friend variables
                for ((_, sym) in friendSymbols) table.defineVariable(sym)
                val lowered = stmt.body.map { lowerStmt(it) }
                // Save variables back for next friend zone
                table.exportCurrentScope(friendSymbols)
                nameScopes.removeLast()
                table.popScope()
                if (stmt.alloc) {
                    // `friend zone alloc { }` — arena scoping on top of shared friend scope.
                    result.add(IrStmt.Zone(lowered, alloc = true))
                } else {
                    result.addAll(lowered)
                }
            } else {
                result.add(lowerStmt(stmt))
            }
        }

        friendNameScope = savedFriendScope
        return result
    }

    private fun lowerStmt(stmt: Stmt): IrStmt {
        return when (stmt) {
            is Stmt.VarDecl -> {
                val init = lowerExpr(stmt.initializer)
                val type = resolveTypeAnnotation(stmt.type, init)
                val mangled = registerName(stmt.name)
                table.defineVariable(VariableSymbol(stmt.name, type, mutable = true))
                if (init is IrExpr.EnumLiteral) knownEnumValues[mangled] = init else knownEnumValues.remove(mangled)
                IrStmt.VarDecl(mangled, type, init)
            }
            is Stmt.FinDecl -> {
                val init = lowerExpr(stmt.initializer)
                val type = resolveTypeAnnotation(stmt.type, init)
                val mangled = registerName(stmt.name)
                table.defineVariable(VariableSymbol(stmt.name, type, mutable = false))
                if (init is IrExpr.EnumLiteral) knownEnumValues[mangled] = init else knownEnumValues.remove(mangled)
                IrStmt.FinDecl(mangled, type, init)
            }
            is Stmt.LetDecl -> {
                val init = lowerExpr(stmt.initializer)
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
                val value = lowerExpr(stmt.value)
                val name = resolveName(stmt.name)
                knownEnumValues.remove(name)
                IrStmt.Assignment(name, value)
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
                val target = lowerExpr(stmt.target)
                val value = lowerExpr(stmt.value)
                IrStmt.MemberAssign(target, stmt.name, value)
            }
            is Stmt.Return -> IrStmt.Return(stmt.value?.let { lowerExpr(it) })
            is Stmt.ExprStmt -> IrStmt.ExprStmt(lowerExpr(stmt.expr))
            is Stmt.If -> {
                val cond = lowerExpr(stmt.condition)
                val thenBranch = lowerScopedBody(stmt.thenBranch)
                val elseBranch = stmt.elseBranch?.let { lowerScopedBody(it) }
                IrStmt.If(cond, thenBranch, elseBranch)
            }
            is Stmt.InlineIf -> error("InlineIf should have been resolved by CTCE before IR generation")
            is Stmt.DeepInlineIf -> error("DeepInlineIf should have been resolved by CTCE before IR generation")
            is Stmt.Zone -> {
                table.pushScope()
                pushNameScope()
                val stmts = lowerBody(stmt.body)
                popNameScope()
                table.popScope()
                IrStmt.Zone(stmts, stmt.alloc)
            }
            is Stmt.FriendZone -> {
                // Should be handled by lowerBody — if we get here, it's a bug
                error("FriendZone should be handled by lowerBody, not lowerStmt")
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
                    IrStmt.Zone(listOf(reset, IrStmt.While(cond, body, stmt.label)), alloc = false)
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
                IrStmt.VarDecl(mangled, type, init)
            }
            is Stmt.Effect -> {
                lowerBody(stmt.body).forEach { /* effects run as-is */ }
                lowerBody(stmt.body).firstOrNull() ?: IrStmt.ExprStmt(IrExpr.IntLiteral(0))
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

    private fun suffixToRealType(suffix: NumericSuffix): IrType = when (suffix) {
        NumericSuffix.FLOAT -> IrType.Float
        NumericSuffix.DECIMAL -> IrType.Decimal
        else -> IrType.Real
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
            is IrExpr.IntLiteral, is IrExpr.RealLiteral, is IrExpr.StringLiteral,
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
            is IrExpr.IntLiteral, is IrExpr.RealLiteral, is IrExpr.StringLiteral,
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

    private fun lowerExpr(expr: Expr): IrExpr {
        return when (expr) {
            is Expr.IntLiteral -> IrExpr.IntLiteral(expr.value, suffixToIntType(expr.suffix))
            is Expr.RealLiteral -> IrExpr.RealLiteral(expr.value, suffixToRealType(expr.suffix))
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
                if (sym != null) {
                    IrExpr.Var(resolveName(expr.name), sym.type)
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
                // Operator overloading on user types
                if (left.type is IrType.Named) {
                    val lt = left.type as IrType.Named
                    // `impl oper<OP>` overloads (method named `oper<OP>`); resolved
                    // regardless of operand type (a `by <Spec>` overload may differ).
                    val operName = operOverloadName(expr.op)
                    if (operName != null) {
                        val mangled = table.lookupMethod(lt.name, operName)
                        if (mangled != null) {
                            val func = table.lookupFunction(mangled)!!
                            return IrExpr.Call(mangled, listOf(left, right), func.returnType)
                        }
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
                val op = lowerBinaryOp(expr.op)
                val type = when (op) {
                    IrBinaryOp.EQ, IrBinaryOp.NEQ,
                    IrBinaryOp.LT, IrBinaryOp.LTE,
                    IrBinaryOp.GT, IrBinaryOp.GTE,
                    IrBinaryOp.AND, IrBinaryOp.OR -> IrType.Bool
                    // Arithmetic widens to the common numeric type (`Int / Real` → Real,
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
                IrExpr.Binary(left, op, right, type)
            }
            is Expr.Call -> {
                // Value call `receiver(args)` — lower the receiver (a function value)
                // and emit an indirect call carrying it.
                expr.receiver?.let { recv ->
                    val target = lowerExpr(recv)
                    val ret = (target.type as? IrType.Function)?.ret ?: IrType.Any
                    val args = expr.args.map { lowerExpr(it) }
                    return IrExpr.Call("", args, ret, receiver = target)
                }
                if (expr.callee == "__defaultLogLevel") {
                    val first = table.lookupEnum("LogLevel")?.firstOrNull()
                        ?: error("LogLevel must declare at least one variant")
                    return IrExpr.EnumLiteral("LogLevel", first)
                }
                // Resolve import aliases (`import Zone.Item` maps Item to Zone__Item).
                val realCallee = table.aliasMap[expr.callee] ?: expr.callee
                val struct = table.lookupStruct(realCallee) ?: table.lookupStruct(expr.callee)
                if (struct != null) {
                    // Handle named arguments — reorder to field order; omitted fields use their defaults.
                    val args = if (expr.args.isNotEmpty() && expr.args[0] is Expr.NamedArg) {
                        val namedMap = expr.args.associate { (it as Expr.NamedArg).name to (it as Expr.NamedArg).value }
                        struct.fields.map { f -> lowerExpr(namedMap[f.name] ?: f.default ?: Expr.NullLiteral) }
                    } else {
                        // Positional — pad omitted trailing fields with their defaults (`Pack<T>()`).
                        val padded = expr.args.map { lowerExpr(it) }.toMutableList()
                        for (i in expr.args.size until struct.fields.size) {
                            padded.add(lowerExpr(struct.fields[i].default ?: Expr.NullLiteral))
                        }
                        padded
                    }
                    // Node types: prepend __type and __chain for dynamic dispatch.
                    if (realCallee in table.nodeTypes) {
                        val chain = mutableListOf(realCallee)
                        var p = table.nodeParents[realCallee]
                        while (p != null) { chain.add(p); p = table.nodeParents[p] }
                        val chainLit = IrExpr.ArrayLiteral(chain.map { IrExpr.StringLiteral(it) }, IrType.Array(IrType.String))
                        return IrExpr.StructCtor(
                            realCallee,
                            listOf("__type", "__chain") + struct.fields.map { it.name },
                            listOf(IrExpr.StringLiteral(realCallee), chainLit) + args,
                            IrType.Named(realCallee)
                        )
                    }
                    return IrExpr.StructCtor(realCallee, struct.fields.map { it.name }, args, IrType.Named(realCallee))
                }
                val func = table.lookupFunction(expr.callee)
                if (func != null) {
                    // Lower args, flattening any Spread expressions.
                    val loweredArgs = expr.args.flatMap { arg ->
                        if (arg is Expr.Spread) listOf(IrExpr.Spread(lowerExpr(arg.array)))
                        else listOf(lowerExpr(arg))
                    }
                    // Handle named arguments — reorder to param order (pre-spread only)
                    val args = if (loweredArgs.isNotEmpty() && expr.args[0] is Expr.NamedArg && func.paramNames.isNotEmpty()) {
                        val namedMap = expr.args.associate { (it as Expr.NamedArg).name to (it as Expr.NamedArg).value }
                        func.paramNames.map { pn -> lowerExpr(namedMap[pn]!!) }
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
                            val default = func.defaults[i]
                            result.add(if (default != null) lowerExpr(default) else error("Missing arg ${func.params[i].first} of '${expr.callee}'"))
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
                        else -> func.returnType
                    }
                    val displayArgs = if (func.name == "std__println" || func.name == "std__print") {
                        effectiveArgs.map(::stringifyEnum)
                    } else {
                        effectiveArgs
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
                        expr.args.map { lowerExpr(it) }
                    }
                    return IrExpr.Call(resolveName(expr.callee), args, v.type.ret)
                }
                // Compiler builtin: `std::convert::toString(x)` stringifies any
                // value (implemented natively by CTCE and every backend).
                if (expr.callee == "std__convert__toString") {
                    val args = expr.args.map { stringifyEnum(lowerExpr(it)) }
                    return IrExpr.Call("std__convert__toString", args, IrType.String)
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
                    val mangled = table.lookupMethod(targetType.name, "deref")
                    if (mangled != null) {
                        val func = table.lookupFunction(mangled)!!
                        return IrExpr.Call(mangled, listOf(target), func.returnType)
                    }
                }
                val inner = (targetType as? IrType.Pointer)?.inner ?: IrType.Any
                IrExpr.Call("__deref", listOf(target), inner)
            }
            is Expr.Isolated -> {
                val value = lowerExpr(expr.value)
                IrExpr.Call("__isolated", listOf(value), value.type)
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
                val target = lowerExpr(expr.target)
                val tt2 = target.type
                if (tt2 is IrType.Named) {
                    // Concrete pack fields win over property-style callbacks. This matters
                    // for stdlib containers that expose field-backed storage and also define
                    // methods such as keys()/values().
                    val field = table.lookupStruct(tt2.name)?.field(expr.name)
                    if (field != null) {
                        return IrExpr.Member(target, expr.name, field.type)
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
                // Slot construction: SlotName.Variant(args)
                if (expr.target is Expr.Identifier && table.lookupSlot(expr.target.name) != null) {
                    val args = expr.args.map { lowerExpr(it) }
                    val fieldNames = listOf("__tag") + args.indices.map { "__$it" }
                    val allArgs = listOf(IrExpr.StringLiteral(expr.name)) + args
                    return IrExpr.StructCtor(expr.target.name, fieldNames, allArgs, IrType.Named(expr.target.name))
                }
                // `base.method(args)` — resolve to the parent node's method.
                if (expr.target is Expr.Identifier && expr.target.name == "__base__") {
                    val parent = currentNodeType?.let { table.nodeParents[it] }
                    if (parent != null) {
                        val mangled = "${parent}_${expr.name}"
                        val func = table.lookupFunction(mangled)
                        if (func != null) {
                            val args = expr.args.map { lowerExpr(it) }
                            val selfVar = IrExpr.Var(resolveName("self"), IrType.Named(parent))
                            return IrExpr.Call(mangled, listOf(selfVar) + args, func.returnType)
                        }
                    }
                    error("'base' used but current type has no parent with method '${expr.name}'")
                }
                val target = lowerExpr(expr.target)
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
                        // Node types use dynamic dispatch — keep as MethodCall.
                        if (tt.name in table.nodeTypes) {
                            return IrExpr.MethodCall(target, expr.name, args, func.returnType)
                        }
                        return IrExpr.Call(mangled, listOf(target) + args, func.returnType)
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
                table.pushScope()
                pushNameScope()
                val irParams = expr.params.map { p ->
                    val t = resolveType(p.type)
                    val m = registerName(p.name)
                    table.defineVariable(VariableSymbol(p.name, t))
                    m to t
                }
                val body = lowerBody(expr.body)
                popNameScope()
                table.popScope()
                val retType = body.mapNotNull { (it as? IrStmt.Return)?.value?.type }.firstOrNull() ?: IrType.Unit
                IrExpr.Lambda(irParams, body, IrType.Function(irParams.map { it.second }, retType, variadic = expr.variadic))
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

    /** Resolves the return type of a builtin method on a receiver of [receiverType]. */
    private fun builtinMethodReturnType(receiverType: IrType, name: String): IrType {
        // `#expr` (oper#) hashes its operand → ULong, regardless of receiver type.
        if (name == "oper#") return IrType.ULong
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
    private fun numericResultType(a: IrType, b: IrType): IrType {
        if (a == b) return a
        if (a !in IrType.numericTypes || b !in IrType.numericTypes) return a
        if (a in IrType.floatTypes || b in IrType.floatTypes) {
            if (a == IrType.Decimal || b == IrType.Decimal) return IrType.Decimal
            if (a == IrType.Real || b == IrType.Real) return IrType.Real
            return IrType.Float
        }
        val rank = mapOf(
            IrType.Byte to 1, IrType.UByte to 1, IrType.Short to 2, IrType.UShort to 2,
            IrType.Int to 3, IrType.UInt to 3, IrType.Long to 4, IrType.ULong to 4,
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
    private fun defaultValueForType(type: IrType): IrExpr = when (type) {
        is IrType.Int -> IrExpr.IntLiteral(0, type)
        is IrType.Long -> IrExpr.IntLiteral(0, type)
        is IrType.Byte -> IrExpr.IntLiteral(0, type)
        is IrType.Short -> IrExpr.IntLiteral(0, type)
        is IrType.UInt -> IrExpr.IntLiteral(0, type)
        is IrType.ULong -> IrExpr.IntLiteral(0, type)
        is IrType.Real -> IrExpr.RealLiteral(0.0, type)
        is IrType.Float -> IrExpr.RealLiteral(0.0, type)
        is IrType.String -> IrExpr.StringLiteral("")
        is IrType.Bool -> IrExpr.BoolLiteral(false)
        is IrType.Char -> IrExpr.CharLiteral('\u0000')
        else -> IrExpr.Var("__null", IrType.Any)
    }
}
