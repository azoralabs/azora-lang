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
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.NumericSuffix
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef
import org.azora.lang.frontend.UseAsTemplate
import org.azora.lang.frontend.Visibility
import org.azora.lang.ir.IrType

/**
 * Semantic Pass 1 — Symbol Collection.
 *
 * Walks all top-level declarations and registers function signatures
 * in the [SymbolTable]. Does NOT look inside function bodies — that
 * happens in [TypeResolver] (Pass 2).
 */
class SymbolCollector {
    private fun isImportable(visibility: Visibility): Boolean = visibility != Visibility.CONFINE

    private fun zoneMangle(path: String): String = path.split('.').joinToString("__")

    private fun splitSelectedImport(path: String): Pair<String, String>? {
        val dot = path.lastIndexOf('.')
        if (dot <= 0 || dot == path.lastIndex) return null
        return path.substring(0, dot) to path.substring(dot + 1)
    }

    private fun importSelectedZoneItem(
        table: SymbolTable,
        errors: MutableList<String>,
        line: Int,
        zonePath: String,
        itemName: String,
    ): Boolean {
        var imported = false
        val mangledZone = zoneMangle(zonePath)
        val mangled = "${mangledZone}__${itemName}"
        val display = zonePath.split('.').joinToString("::")
        val func = table.lookupFunction(mangled)
        if (func != null) {
            imported = true
            if (isImportable(func.visibility)) {
                table.aliasMap[itemName] = mangled
                table.defineFunction(func.copy(name = itemName))
            } else {
                errors.add("line $line: cannot import confined function '$display::$itemName'")
            }
        }
        val struct = table.lookupStruct(mangled)
        if (struct != null) {
            imported = true
            if (isImportable(struct.visibility)) {
                table.aliasMap[itemName] = mangled
                table.defineStruct(struct.copy(name = itemName))
            } else {
                errors.add("line $line: cannot import confined type '$display::$itemName'")
            }
        }
        val variable = table.lookupVariable(mangled)
        if (variable != null) {
            imported = true
            if (isImportable(variable.visibility)) {
                table.aliasMap[itemName] = mangled
                table.defineVariable(VariableSymbol(itemName, variable.type, variable.mutable, variable.visibility))
            } else {
                errors.add("line $line: cannot import confined variable '$display::$itemName'")
            }
        }
        return imported
    }

    private fun importZonePath(table: SymbolTable, path: String) {
        val mangledPath = zoneMangle(path)
        val prefix = "${mangledPath}__"
        val knownFuncs = table.allFunctionNames().filter { it.startsWith(prefix) }
        for (fn in knownFuncs) {
            val alias = fn.removePrefix(prefix).substringAfterLast("__")
            val func = table.lookupFunction(fn)!!
            if (!isImportable(func.visibility)) continue
            table.aliasMap[alias] = fn
            table.defineFunction(func.copy(name = alias))
        }
        val knownStructs = table.allStructNames().filter { it.startsWith(prefix) }
        for (st in knownStructs) {
            val alias = st.removePrefix(prefix).substringAfterLast("__")
            val struct = table.lookupStruct(st)!!
            if (!isImportable(struct.visibility)) continue
            table.aliasMap[alias] = st
            table.defineStruct(struct.copy(name = alias))
        }
        val knownVars = table.allVariableNames().filter { it.startsWith(prefix) }
        for (vn in knownVars) {
            val alias = vn.removePrefix(prefix).substringAfterLast("__")
            val variable = table.lookupVariable(vn)!!
            if (!isImportable(variable.visibility)) continue
            table.aliasMap[alias] = vn
            table.defineVariable(VariableSymbol(alias, variable.type, variable.mutable, variable.visibility))
        }
    }

    private fun callbackTraitMethodName(traitName: String, traitArgs: List<TypeRef>, callback: org.azora.lang.frontend.SpecCallback? = null): String {
        callback?.useAsTemplate?.let { template ->
            return UseAsTemplate.expand(template, callback.typeParams, traitArgs)
        }
        return if (traitName.isEmpty()) "callback" else traitName[0].lowercaseChar() + traitName.drop(1)
    }


    private fun registerBuiltins(table: SymbolTable) {
        // println accepts String — type checker will allow String args
        if (table.lookupFunction("println") == null) {
            table.defineFunction(FunctionSymbol("println", listOf("value" to IrType.String), IrType.Unit))
            table.defineFunction(FunctionSymbol("toString", listOf("value" to IrType.Any), IrType.String))
        }
        if (table.lookupFunction("channel") == null) {
            // `channel()` — creates a buffered channel for task-to-task communication.
            table.defineFunction(FunctionSymbol("channel", emptyList(), IrType.Named("Channel")))
        }
        if (table.lookupFunction("__dbg") == null) {
            // Debug-build line marker (see frontend.DebugInstrumenter).
            table.defineFunction(FunctionSymbol("__dbg", listOf("line" to IrType.Int), IrType.Unit))
        }
        if (table.lookupFunction("__drop") == null) {
            table.defineFunction(FunctionSymbol("__drop", listOf("value" to IrType.Any), IrType.Unit))
        }
        if (table.lookupFunction("__flipflop") == null) {
            table.defineFunction(FunctionSymbol("__flipflop", listOf("id" to IrType.Int), IrType.Bool))
        }
        if (table.lookupFunction("__launch") == null) {
            // `launch { body }` desugars to __launch(thunk); fire-and-forget, joined at end.
            table.defineFunction(FunctionSymbol("__launch", listOf("thunk" to IrType.Any), IrType.Unit))
        }
        if (table.lookupFunction("async") == null) {
            table.defineFunction(FunctionSymbol("async", listOf("thunk" to IrType.Any), IrType.Task(IrType.Any)))
        }
        if (table.lookupFunction("cancel") == null) {
            table.defineFunction(FunctionSymbol("cancel", listOf("task" to IrType.Any), IrType.Unit))
        }
    }

    /**
     * Collects all function signatures from the program and registers them
     * in the given symbol table.
     *
     * Built-in functions (e.g. `println`) are registered first, followed by
     * user-defined functions. Return types are either taken from explicit
     * annotations or inferred from return statements.
     *
     * @param program the parsed AST to collect symbols from
     * @param table the symbol table to populate with function signatures
     * @return a list of error messages (empty if collection succeeded)
     */
    fun collect(program: Program, table: SymbolTable): List<String> {
        val errors = mutableListOf<String>()

        // Register built-in functions
        registerBuiltins(table)

        // Register global fin declarations as variables in a global scope
        table.pushScope()
        for (item in program.items) {
            when (item) {
                is TopLevel.FinDecl -> {
                    try {
                        val initType = inferExprType(item.initializer, emptyMap())
                        val type = if (item.type != null) IrType.resolve(item.type)
                                   else initType ?: IrType.Int
                        table.defineVariable(VariableSymbol(item.name, type, mutable = false, visibility = item.visibility))
                    } catch (e: Exception) {
                        errors.add("line ${item.line}: ${e.message}")
                    }
                }
                is TopLevel.VarDecl -> {
                    if (item.threadlocal) {
                        try {
                            val initType = inferExprType(item.initializer, emptyMap())
                            val type = if (item.type != null) IrType.resolve(item.type)
                                       else initType ?: IrType.Int
                            table.defineVariable(VariableSymbol(item.name, type, mutable = true, visibility = item.visibility))
                        } catch (e: Exception) {
                            errors.add("line ${item.line}: ${e.message}")
                        }
                    }
                }
                else -> {}
            }
        }

        for (func in program.functions) {
            try {
                val tpSet = func.typeParams.toSet()
                val params = func.params.map { it.name to IrType.resolve(it.type, tpSet) }
                val returnType = when (val rt = func.returnType) {
                    is TypeAnnotation.Explicit -> IrType.resolve(rt.ref, tpSet)
                    is TypeAnnotation.Inferred -> inferReturnType(func, params)
                }
                val paramNames = func.params.map { it.name }
                val defaults = func.params.mapIndexedNotNull { i, p -> p.defaultValue?.let { i to it } }.toMap()
                // A `flow` generator's call returns a list of its (element-type) yields.
                val callReturnType = if (func.isFlow) IrType.Array(returnType) else returnType
                // Variadic only when declared with the `...T` syntax — a plain
                // trailing `[T]` parameter takes an array argument as-is.
                val isVariadic = func.params.lastOrNull()?.variadic == true
                table.defineFunction(
                    FunctionSymbol(
                        name = func.name,
                        params = params,
                        returnType = callReturnType,
                        isInline = func.isInline,
                        typeParams = func.typeParams,
                        paramNames = paramNames,
                        defaults = defaults,
                        isVariadic = isVariadic,
                        isTask = func.isTask,
                        isUnsafe = func.isUnsafe,
                        visibility = func.visibility,
                    )
                )
            } catch (e: Exception) {
                errors.add("line ${func.line}: ${e.message}")
            }
        }

        // Register view declarations (treated as functions for type-checking).
        for (item in program.items) {
            if (item is TopLevel.View) {
                val params = item.params.map { it.name to IrType.resolve(it.type) }
                table.defineFunction(FunctionSymbol(item.name, params, IrType.Any))
            }
            if (item is TopLevel.Hook) {
                table.defineFunction(FunctionSymbol("__hook_${item.name}", emptyList(), IrType.Any))
            }
        }

        // Register bridge (FFI extern) function signatures
        for (item in program.items) {
            if (item is TopLevel.Bridge) {
                for (sig in item.funcs) {
                    try {
                        val params = sig.params.map { it.name to IrType.resolve(it.type) }
                        val ret = IrType.resolve(sig.returnType)
                        val paramNames = sig.params.map { it.name }
                        table.defineFunction(FunctionSymbol(sig.name, params, ret, false, emptyList(), paramNames, emptyMap()))
                    } catch (e: Exception) {
                        errors.add("line ${sig.line}: ${e.message}")
                    }
                }
            }
        }

        // Register solo (singleton struct) declarations
        for (item in program.items) {
            if (item is TopLevel.Solo) {
                try {
                    val tpSet = emptySet<String>()
                    val fields = item.fields.map { field ->
                        StructField(field.name, IrType.resolve(field.type, tpSet), field.mutable, field.visibility, field.default)
                    }
                    table.defineStruct(StructType(item.name, fields, emptyList(), item.visibility))
                    // Register methods as Type_method (like impl)
                    for (method in item.methods) {
                        val mangled = "${item.name}_${method.name}"
                        val params = mutableListOf<Pair<String, IrType>>()
                        params.add("self" to IrType.Named(item.name))
                        for (p in method.params) params.add(p.name to IrType.resolve(p.type))
                        val returnType = when (val rt = method.returnType) {
                            is TypeAnnotation.Explicit -> IrType.resolve(rt.ref)
                            is TypeAnnotation.Inferred -> inferReturnType(method, params)
                        }
                        table.defineFunction(FunctionSymbol(mangled, params, returnType, method.isInline, visibility = method.visibility, memberCallStyle = method.memberCallStyle))
                        table.defineMethod(item.name, method.name, mangled)
                    }
                } catch (e: Exception) {
                    errors.add("line ${item.line}: ${e.message}")
                }
            }
        }

        // Register pack (struct) declarations
        for (item in program.items) {
            if (item is TopLevel.Pack) {
                try {
                    val tpSet = item.typeParams.toSet()
                    val fields = item.fields.map { field ->
                        StructField(field.name, IrType.resolve(field.type, tpSet), field.mutable, field.visibility, field.default)
                    }
                    table.defineStruct(StructType(item.name, fields, item.typeParams, item.visibility, item.shielded))
                } catch (e: Exception) {
                    errors.add("line ${item.line}: ${e.message}")
                }
            }
        }

        // Register node (inheritable type) declarations — flatten parent fields + methods.
        // Node registration requires a multi-pass approach: register fields first (for
        // parent field flattening), then register methods (for parent method inheritance).
        val nodeDeclMap = program.items.filterIsInstance<TopLevel.Node>().associateBy { it.name }
        for (item in nodeDeclMap.values) {
            try {
                // Flatten fields: parent's fields first (if not shadowed), then own ctor params + extra fields.
                val fields = mutableListOf<StructField>()
                val seenNames = mutableSetOf<String>()
                // Walk parent chain collecting inherited fields.
                var p: TopLevel.Node? = item
                val chain = mutableListOf<TopLevel.Node>()
                while (p != null) {
                    chain.add(0, p) // prepend so parent fields come first
                    p = p.parent?.let { nodeDeclMap[it] }
                }
                for (node in chain) {
                    for (np in node.params) {
                        if (np.name !in seenNames) {
                            fields.add(StructField(np.name, IrType.resolve(np.type), np.mutable))
                            seenNames.add(np.name)
                        }
                    }
                    for (ef in node.extraFields) {
                        if (ef.name !in seenNames) {
                            fields.add(StructField(ef.name, IrType.resolve(ef.type), ef.mutable, ef.visibility))
                            seenNames.add(ef.name)
                        }
                    }
                }
                table.defineStruct(StructType(item.name, fields, emptyList(), item.visibility))
                table.nodeTypes.add(item.name)
                // Record parent relationship for dynamic dispatch.
                if (item.parent != null) {
                    table.nodeParents[item.name] = item.parent!!
                }
                if (item.isLeaf) table.leafNodes.add(item.name)
            } catch (e: Exception) {
                errors.add("line ${item.line}: ${e.message}")
            }
        }
        // Register node methods: for each node, register own methods (as Type_method).
        // Inherited (non-overridden) methods from parents are also registered as aliases.
        for (item in nodeDeclMap.values) {
            try {
                // Register own methods.
                for (method in item.methods) {
                    val mangled = "${item.name}_${method.name}"
                    val params = mutableListOf<Pair<String, IrType>>()
                    params.add("self" to IrType.Named(item.name))
                    for (p in method.params) params.add(p.name to IrType.resolve(p.type))
                    val returnType = when (val rt = method.returnType) {
                        is TypeAnnotation.Explicit -> IrType.resolve(rt.ref)
                        is TypeAnnotation.Inferred -> inferReturnType(method, params)
                    }
                    table.defineFunction(FunctionSymbol(mangled, params, returnType, method.isInline, visibility = method.visibility, memberCallStyle = method.memberCallStyle))
                    table.defineMethod(item.name, method.name, mangled)
                }
                // Register inherited methods from parent chain (if not overridden by this node).
                val ownMethodNames = item.methods.map { it.name }.toSet()
                var parentName = item.parent
                while (parentName != null) {
                    val parentNode = nodeDeclMap[parentName] ?: break
                    for (method in parentNode.methods) {
                        if (method.name !in ownMethodNames && method.name !in item.methods.map { it.name }) {
                            // Inherited method: register an alias so lookupMethod(childName, methodName) finds it.
                            val parentMangled = "${parentNode.name}_${method.name}"
                            table.defineMethod(item.name, method.name, parentMangled)
                        }
                    }
                    parentName = parentNode.parent
                }
            } catch (e: Exception) {
                errors.add("line ${item.line}: ${e.message}")
            }
        }

        // Register enum declarations
        for (item in program.items) {
            if (item is TopLevel.Enum) {
                try {
                    table.defineEnum(item.name, item.variants)
                } catch (e: Exception) {
                    errors.add("line ${item.line}: ${e.message}")
                }
            }
        }

        // Register fail (error-set) declarations
        for (item in program.items) {
            if (item is TopLevel.Fail) {
                try {
                    table.defineFail(item.name, item.variants)
                } catch (e: Exception) {
                    errors.add("line ${item.line}: ${e.message}")
                }
            }
        }

        // Register slot (tagged union) declarations
        for (item in program.items) {
            if (item is TopLevel.Slot) {
                try {
                    val variants = item.variants.map { v -> v.name to v.payloadTypes.map { IrType.resolve(it) } }
                    table.defineSlot(item.name, variants)
                } catch (e: Exception) {
                    errors.add("line ${item.line}: ${e.message}")
                }
            }
        }

        // Register impl methods as functions `Type_method(self, ...)`
        val localPackNames = program.localPackNames
        for (item in program.items) {
            if (item is TopLevel.Impl) {
                val struct = table.lookupStruct(item.typeName)
                if (item.isPackImpl && item.typeName !in localPackNames) {
                    errors.add("line ${item.line}: 'impl pack ${item.typeName}' is only allowed in the file that declares pack ${item.typeName}")
                    continue
                }
                val tpSet = table.lookupStruct(item.typeName)?.typeParams?.toSet() ?: emptySet()
                for (method in item.methods) {
                    val mangled = "${item.typeName}_${method.name}"
                    try {
                        if (item.isExtension && struct != null && struct.fields.none { it.visibility == Visibility.EXPOSE } && method.receiverModifier == "mut ref") {
                            errors.add("line ${method.line}: pack '${item.typeName}' has no exposed fields, so extension '${method.name}' cannot use mut ref self")
                            continue
                        }
                        val selfType = IrType.Named(item.typeName)
                        val params = mutableListOf<Pair<String, IrType>>()
                        params.add("self" to selfType)
                        for (p in method.params) params.add(p.name to IrType.resolve(p.type, tpSet))
                        val returnType = when (val rt = method.returnType) {
                            is TypeAnnotation.Explicit -> IrType.resolve(rt.ref, tpSet)
                            is TypeAnnotation.Inferred -> inferReturnType(method, params)
                        }
                        table.defineFunction(FunctionSymbol(mangled, params, returnType, method.isInline, visibility = method.visibility, memberCallStyle = method.memberCallStyle))
                        table.defineMethod(item.typeName, method.name, mangled)
                    } catch (e: Exception) {
                        errors.add("line ${method.line}: ${e.message}")
                    }
                }
            }
        }

        // Register spec (trait) declarations
        for (item in program.items) {
            if (item is TopLevel.Spec) {
                table.defineSpec(item.name, item.methods.map { it.name }, item.callback)
            }
        }

// Register type aliases
        for (item in program.items) {
            if (item is TopLevel.TypeAlias) {
                table.defineAlias(item.name, item.type)
                IrType.aliases[item.name] = item.type
            }
        }

        // Validate impl Trait for Type — all spec methods must be present
        for (item in program.items) {
            if (item is TopLevel.Impl && item.traitName != null) {
                val spec = table.lookupSpec(item.traitName)
                if (spec == null) {
                    errors.add("line ${item.line}: unknown spec '${item.traitName}'")
                } else {
                    val provided = item.methods.map { it.name }.toSet()
                    val required = if (spec.callback != null) {
                        listOf(callbackTraitMethodName(item.traitName, item.traitArgs, spec.callback))
                    } else {
                        spec.methodNames
                    }
                    for (req in required) {
                        if (req !in provided) {
                            errors.add("line ${item.line}: '${item.typeName}' does not implement '${item.traitName}.${req}'")
                        }
                    }
                }
            }
        }

        // Resolve `use` imports — create aliases so zone items are accessible without prefix.
        for (item in program.items) {
            if (item !is TopLevel.UseImport) continue
            for ((zoneName, itemName) in item.imports) {
                if (itemName != null) {
                    importSelectedZoneItem(table, errors, item.line, zoneName, itemName)
                } else {
                    importZonePath(table, zoneName)
                    splitSelectedImport(zoneName)?.let { (path, selected) ->
                        importSelectedZoneItem(table, errors, item.line, path, selected)
                    }
                }
            }
        }

        return errors
    }

    /**
     * Infer the return type from return statements in the function body.
     * If no return statements exist, the function returns Unit.
     */
    private fun inferReturnType(func: FuncDecl, params: List<Pair<String, IrType>>): IrType {
        val returnExprs = collectReturnExprs(func.body)
        if (returnExprs.isEmpty()) return IrType.Unit

        // Build a simple name→type map from params for expression type inference
        val env = params.toMap()
        val types = returnExprs.mapNotNull { inferExprType(it, env) }
        if (types.isEmpty()) return IrType.Unit

        // All return types must agree
        val first = types.first()
        if (types.all { it == first }) return first
        error("conflicting return types in function '${func.name}'")
    }

    private fun collectReturnExprs(body: List<Stmt>): List<Expr> {
        val result = mutableListOf<Expr>()
        for (stmt in body) {
            when (stmt) {
                is Stmt.Return -> stmt.value?.let { result.add(it) }
                is Stmt.If -> {
                    result.addAll(collectReturnExprs(stmt.thenBranch))
                    stmt.elseBranch?.let { result.addAll(collectReturnExprs(it)) }
                }
                is Stmt.InlineIf -> {
                    result.addAll(collectReturnExprs(stmt.thenBranch))
                    stmt.elseBranch?.let { result.addAll(collectReturnExprs(it)) }
                }
                is Stmt.Zone -> result.addAll(collectReturnExprs(stmt.body))
                is Stmt.FriendZone -> result.addAll(collectReturnExprs(stmt.body))
                else -> {}
            }
        }
        return result
    }

    /**
     * Simple expression type inference for return type deduction.
     * Only needs to handle literal types and parameter references.
     */
    private fun inferExprType(expr: Expr, env: Map<String, IrType>): IrType? = when (expr) {
        is Expr.IntLiteral -> when (expr.suffix) {
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
        is Expr.RealLiteral -> when (expr.suffix) {
            NumericSuffix.DECIMAL -> IrType.Decimal
            NumericSuffix.FLOAT -> IrType.Float
            else -> IrType.Real // unsuffixed real literals default to Real
        }
        is Expr.StringLiteral -> IrType.String
        is Expr.BoolLiteral -> IrType.Bool
        is Expr.CharLiteral -> IrType.Char
        is Expr.Identifier -> env[expr.name]
        is Expr.Binary -> {
            when (expr.op) {
                TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL,
                TokenType.LESS, TokenType.LESS_EQUAL,
                TokenType.GREATER, TokenType.GREATER_EQUAL,
                TokenType.AND_AND, TokenType.OR_OR -> IrType.Bool
                TokenType.PLUS -> {
                    val left = inferExprType(expr.left, env)
                    if (left == IrType.String) IrType.String else left
                }
                TokenType.STAR -> {
                    val left = inferExprType(expr.left, env)
                    val right = inferExprType(expr.right, env)
                    if (left == IrType.String || right == IrType.String) IrType.String else left
                }
                else -> inferExprType(expr.left, env)
            }
        }
        is Expr.Unary -> when (expr.op) {
            TokenType.BANG -> IrType.Bool
            else -> inferExprType(expr.operand, env)
        }
        is Expr.Grouping -> inferExprType(expr.expr, env)
        is Expr.Call -> null // can't infer without full symbol table yet
        is Expr.UpperScopeAccess -> null // can't infer type from upper scope access during symbol collection
        is Expr.Range -> null // ranges are not first-class values
        is Expr.ArrayLiteral -> expr.elements.firstOrNull()?.let { inferExprType(it, env) }?.let(IrType::Array)
        is Expr.SetLiteral -> expr.elements.firstOrNull()?.let { inferExprType(it, env) }?.let(IrType::Set)
        is Expr.MapLit -> expr.entries.firstOrNull()?.let { (key, value) ->
            val keyType = inferExprType(key, env)
            val valueType = inferExprType(value, env)
            if (keyType != null && valueType != null) IrType.Map(keyType, valueType) else null
        }
        is Expr.Index, is Expr.Member, is Expr.MethodCall -> null
        is Expr.StringTemplate -> IrType.String
        is Expr.TupleLit, is Expr.TupleAccess, is Expr.VariantLit -> null
        is Expr.CatchExpr -> null
        is Expr.IfExpr -> inferExprType(expr.thenExpr, env)
        is Expr.Lambda -> null
        is Expr.NamedArg -> null
        is Expr.NullLiteral -> IrType.Any
        is Expr.NullCoalesce, is Expr.SafeMember,
        is Expr.Cast, is Expr.IsCheck, is Expr.Alloc, is Expr.AllocBuffer, is Expr.Deref, is Expr.Isolated, is Expr.Await, is Expr.Inject, is Expr.Spread -> null
    }
}
