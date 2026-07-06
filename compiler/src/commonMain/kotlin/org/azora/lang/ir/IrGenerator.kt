/*
 * Copyright 2026 AzoraTech
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

import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.NumericSuffix
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.semantic.SymbolTable
import org.azora.lang.semantic.VariableSymbol
import kotlin.collections.iterator

/**
 * Lowers a type-checked AST into typed IR.
 *
 * Assumes semantic analysis has already validated the program -- all types
 * are known, all symbols are resolved, and all constraints are satisfied.
 * Inline functions are skipped since they were already substituted by the
 * CTFE evaluator.
 *
 * @param table the fully populated symbol table from semantic analysis
 */
class IrGenerator(private val table: SymbolTable) {

    /** Scope stack mapping original variable names to their mangled IR names. */
    private val nameScopes = ArrayDeque<MutableMap<String, String>>()
    private var mangledCounter = 0

    private fun pushNameScope() { nameScopes.addLast(mutableMapOf()) }
    private fun popNameScope() { nameScopes.removeLast() }

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
     * substituted at their call sites by the CTFE evaluator.
     *
     * @param program the CTFE-stabilized, type-checked AST
     * @return the lowered [IrProgram]
     */
    fun generate(program: Program): IrProgram {
        nameScopes.clear()
        mangledCounter = 0
        pushNameScope() // global scope

        // Register global names
        for (item in program.items) {
            when (item) {
                is TopLevel.FinDecl -> registerName(item.name)
                is TopLevel.VarDecl -> registerName(item.name)
                is TopLevel.LetDecl -> registerName(item.name)
                else -> {}
            }
        }

        // Lower top-level items in source order to preserve interleaving
        val items = program.items.flatMap { item ->
            when (item) {
                is TopLevel.Func -> {
                    if (item.decl.isInline) emptyList()
                    else listOf(IrTopLevel.Func(lowerFunction(item.decl)))
                }
                is TopLevel.FinDecl -> {
                    val init = lowerExpr(item.initializer)
                    val type = if (item.type != null) IrType.resolve(item.type) else init.type
                    listOf(IrTopLevel.Global(IrStmt.FinDecl(item.name, type, init)))
                }
                is TopLevel.LetDecl -> {
                    val init = lowerExpr(item.initializer)
                    val type = if (item.type != null) IrType.resolve(item.type) else init.type
                    listOf(IrTopLevel.Global(IrStmt.LetDecl(item.name, type, init)))
                }
                is TopLevel.VarDecl -> {
                    val init = lowerExpr(item.initializer)
                    val type = if (item.type != null) IrType.resolve(item.type) else init.type
                    listOf(IrTopLevel.Global(IrStmt.VarDecl(item.name, type, init)))
                }
                is TopLevel.Test -> {
                    table.pushScope()
                    pushNameScope()
                    val body = lowerBody(item.body)
                    popNameScope()
                    table.popScope()
                    listOf(IrTopLevel.Test(item.name, body))
                }
                is TopLevel.Pack -> {
                    val tpSet = item.typeParams.toSet()
                    val fields = item.fields.map { IrField(it.name, IrType.resolve(it.type, tpSet), it.mutable) }
                    listOf(IrTopLevel.Struct(item.name, fields))
                }
                is TopLevel.Solo -> {
                    val fields = item.fields.map { IrField(it.name, IrType.resolve(it.type), it.mutable) }
                    val result = mutableListOf<IrTopLevel>(IrTopLevel.Struct(item.name, fields))
                    // Lower methods as free functions Name_method (like impl).
                    for (method in item.methods) {
                        if (!method.isInline) result.add(IrTopLevel.Func(lowerMethod(item.name, method)))
                    }
                    // Emit a __singleton_Name factory that constructs the struct from field defaults.
                    val defaults = item.fields.map { f ->
                        if (f.default != null) lowerExpr(f.default)
                        else defaultValueForType(IrType.resolve(f.type))
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
                is TopLevel.Impl -> item.methods.mapNotNull { method ->
                    if (method.isInline) null
                    else IrTopLevel.Func(lowerMethod(item.typeName, method))
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
                else -> emptyList() // Inline constructs already resolved by CTFE
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
                val params = sig.params.map { it.name to IrType.resolve(it.type) }
                IrTopLevel.Extern(sig.name, params, IrType.resolve(sig.returnType))
            }
        }
        return IrProgram(program.packageName, items)
    }

    private fun lowerFunction(func: FuncDecl): IrFunction {
        val symbol = table.lookupFunction(func.name)!!
        // Collect ref/out param indices from the AST FuncDecl.
        val refParams = func.params.indices.filter { func.params[it].modifier == "ref" || func.params[it].modifier == "out" }.toSet()
        table.pushScope()
        pushNameScope()

        // Register parameters
        val mangledParams = symbol.params.map { (name, type) ->
            val mutable = func.params.getOrNull(symbol.params.indexOfFirst { it.first == name })?.modifier == "mut"
            val mangled = registerName(name)
            table.defineVariable(VariableSymbol(name, type, mutable = true)) // all params mutable for simplicity; mut is enforced at type level
            mangled to type
        }

        val body = lowerBody(func.body)
        popNameScope()
        table.popScope()

        return IrFunction(func.name, mangledParams, symbol.returnType, body, func.isFlow, refParams)
    }

    /** The current node type being lowered (for `base` resolution). Null outside a node method. */
    private var currentNodeType: String? = null

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
        table.pushScope()
        pushNameScope()
        val mangledParams = symbol.params.map { (name, type) ->
            val m = registerName(name)
            table.defineVariable(VariableSymbol(name, type))
            m to type
        }
        val body = lowerBody(method.body)
        popNameScope()
        table.popScope()
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
                IrStmt.VarDecl(mangled, type, init)
            }
            is Stmt.FinDecl -> {
                val init = lowerExpr(stmt.initializer)
                val type = resolveTypeAnnotation(stmt.type, init)
                val mangled = registerName(stmt.name)
                table.defineVariable(VariableSymbol(stmt.name, type, mutable = false))
                IrStmt.FinDecl(mangled, type, init)
            }
            is Stmt.LetDecl -> {
                val init = lowerExpr(stmt.initializer)
                val type = resolveTypeAnnotation(stmt.type, init)
                val mangled = registerName(stmt.name)
                table.defineVariable(VariableSymbol(stmt.name, type, mutable = false))
                IrStmt.LetDecl(mangled, type, init)
            }
            is Stmt.DeepInlineBlock -> error("DeepInlineBlock should have been resolved by CTFE before IR generation")
            is Stmt.NoInline -> lowerStmt(stmt.stmt)
            is Stmt.InlineBlock -> error("InlineBlock should have been resolved by CTFE before IR generation")
            is Stmt.InlineFor -> error("InlineFor should have been resolved by CTFE before IR generation")
            is Stmt.InlineFin -> error("InlineFin should have been resolved by CTFE before IR generation")
            is Stmt.InlineLet -> error("InlineLet should have been resolved by CTFE before IR generation")
            is Stmt.InlineVar -> error("InlineVar should have been resolved by CTFE before IR generation")
            is Stmt.InlineAssignment -> error("InlineAssignment should have been resolved by CTFE before IR generation")
            is Stmt.Assignment -> {
                val value = lowerExpr(stmt.value)
                IrStmt.Assignment(resolveName(stmt.name), value)
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
                table.pushScope()
                val thenBranch = lowerBody(stmt.thenBranch)
                table.popScope()
                val elseBranch = if (stmt.elseBranch != null) {
                    table.pushScope()
                    val branch = lowerBody(stmt.elseBranch)
                    table.popScope()
                    branch
                } else null
                IrStmt.If(cond, thenBranch, elseBranch)
            }
            is Stmt.InlineIf -> error("InlineIf should have been resolved by CTFE before IR generation")
            is Stmt.DeepInlineIf -> error("DeepInlineIf should have been resolved by CTFE before IR generation")
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
                val msg = lowerExpr(stmt.message)
                IrStmt.Trace(msg)
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
                    val elemType = (iterable.type as? IrType.Array)?.element ?: IrType.Any
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
                IrStmt.Loop(body, stmt.label)
            }
            is Stmt.Break -> IrStmt.Break(stmt.label)
            is Stmt.Continue -> IrStmt.Continue(stmt.label)
            is Stmt.Defer -> IrStmt.Defer(lowerBody(stmt.body), stmt.onFail, stmt.suppress)
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
                            IrExpr.SlotPattern(pat.target.name, pat.name, bindNames)
                        } else {
                            lowerExpr(pat)
                        }
                    }
                    if (slotBindings != null) {
                        table.pushScope()
                        pushNameScope()
                        for ((name, type) in slotBindings!!) {
                            val mangled = registerName(name)
                            table.defineVariable(VariableSymbol(name, type))
                        }
                    }
                    val body = lowerBody(b.body)
                    if (slotBindings != null) {
                        popNameScope()
                        table.popScope()
                    }
                    IrWhenBranch(irPatterns, body)
                }
                val elseBranch = stmt.elseBranch?.let { lowerBody(it) }
                IrStmt.When(scrutinee, branches, elseBranch)
            }
            is Stmt.Throw -> IrStmt.Throw(lowerExpr(stmt.value))
            is Stmt.Try -> {
                table.pushScope()
                pushNameScope()
                val body = lowerBody(stmt.body)
                popNameScope()
                table.popScope()
                val catchBody = if (stmt.catchBody != null) {
                    table.pushScope()
                    pushNameScope()
                    if (stmt.catchName != null) {
                        registerName(stmt.catchName)
                        table.defineVariable(VariableSymbol(stmt.catchName, IrType.Any, mutable = false))
                    }
                    val cb = lowerBody(stmt.catchBody)
                    popNameScope()
                    table.popScope()
                    cb
                } else null
                IrStmt.Try(body, stmt.catchName, catchBody)
            }
            is Stmt.InlineAssert -> error("InlineAssert should have been resolved by CTFE before IR generation")
            is Stmt.InlineTrace -> error("InlineTrace should have been resolved by CTFE before IR generation")
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
                val target = IrType.resolve(expr.targetType)
                // Numeric casts convert the value; all other casts (interface
                // upcasts, Any) are representation-preserving no-ops.
                val numeric = IrType.integerTypes + IrType.floatTypes
                if (target != inner.type && (target in numeric || target == IrType.Char) &&
                    (inner.type in numeric || inner.type == IrType.Char)) {
                    IrExpr.NumCast(inner, target)
                } else {
                    inner
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
                val sym = table.lookupVariable(expr.name)!!
                IrExpr.Var(resolveName(expr.name), sym.type)
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
                val left = lowerExpr(expr.left)
                val right = lowerExpr(expr.right)
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
                if (left.type is IrType.Named && left.type == right.type) {
                    val lt = left.type as IrType.Named
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
                val op = lowerBinaryOp(expr.op)
                val type = when (op) {
                    IrBinaryOp.EQ, IrBinaryOp.NEQ,
                    IrBinaryOp.LT, IrBinaryOp.LTE,
                    IrBinaryOp.GT, IrBinaryOp.GTE,
                    IrBinaryOp.AND, IrBinaryOp.OR -> IrType.Bool
                    IrBinaryOp.MUL -> {
                        if (left.type == IrType.String || right.type == IrType.String) IrType.String
                        else left.type
                    }
                    else -> left.type
                }
                IrExpr.Binary(left, op, right, type)
            }
            is Expr.Call -> {
                val struct = table.lookupStruct(expr.callee)
                if (struct != null) {
                    // Handle named arguments — reorder to field order
                    val args = if (expr.args.isNotEmpty() && expr.args[0] is Expr.NamedArg) {
                        val namedMap = expr.args.associate { (it as Expr.NamedArg).name to (it as Expr.NamedArg).value }
                        struct.fields.map { f -> lowerExpr(namedMap[f.name]!!) }
                    } else {
                        expr.args.map { lowerExpr(it) }
                    }
                    // Node types: prepend __type and __chain for dynamic dispatch.
                    if (expr.callee in table.nodeTypes) {
                        val chain = mutableListOf(expr.callee)
                        var p = table.nodeParents[expr.callee]
                        while (p != null) { chain.add(p); p = table.nodeParents[p] }
                        val chainLit = IrExpr.ArrayLiteral(chain.map { IrExpr.StringLiteral(it) }, IrType.Array(IrType.String))
                        return IrExpr.StructCtor(
                            expr.callee,
                            listOf("__type", "__chain") + struct.fields.map { it.name },
                            listOf(IrExpr.StringLiteral(expr.callee), chainLit) + args,
                            IrType.Named(expr.callee)
                        )
                    }
                    return IrExpr.StructCtor(expr.callee, struct.fields.map { it.name }, args, IrType.Named(expr.callee))
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
                    // Variadic: pack extra args into an array for the last param.
                    val hasSpread = args.any { it is IrExpr.Spread }
                    val effectiveArgs = if (func.isVariadic && args.size >= func.params.size - 1) {
                        val fixed = args.take(func.params.size - 1)
                        val rest = args.drop(func.params.size - 1)
                        val elemType = (func.params.last().second as? IrType.Array)?.element ?: IrType.Any
                        fixed + listOf(IrExpr.ArrayLiteral(rest, IrType.Array(elemType)))
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
                    return IrExpr.Call(expr.callee, effectiveArgs, func.returnType)
                }
                // Calling a lambda stored in a variable.
                val v = table.lookupVariable(expr.callee)
                if (v != null && v.type is IrType.Function) {
                    val args = expr.args.map { lowerExpr(it) }
                    return IrExpr.Call(resolveName(expr.callee), args, v.type.ret)
                }
                error("undefined function or variable '${expr.callee}'")
            }
            is Expr.Grouping -> lowerExpr(expr.expr)
            is Expr.Range -> error("range expressions can only be used as for-loop iterables")
            is Expr.ArrayLiteral -> {
                val elems = expr.elements.map { lowerExpr(it) }
                val elemType = if (elems.isEmpty()) IrType.Any else elems.first().type
                IrExpr.ArrayLiteral(elems, IrType.Array(elemType))
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
            is Expr.Deref -> {
                val target = lowerExpr(expr.target)
                val inner = (target.type as? IrType.Pointer)?.inner ?: IrType.Any
                IrExpr.Call("__deref", listOf(target), inner)
            }
            is Expr.Isolated -> {
                val value = lowerExpr(expr.value)
                IrExpr.Call("__isolated", listOf(value), value.type)
            }
            is Expr.Await -> {
                val task = lowerExpr(expr.value)
                val resultType = (task.type as? IrType.Function)?.ret ?: IrType.Any
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
                    is IrType.Map -> tt.value
                    else -> IrType.Any
                }
                IrExpr.Index(target, index, elemType)
            }
            is Expr.Member -> {
                // Slot no-payload construction: SlotName.Variant (no parens)
                if (expr.target is Expr.Identifier) {
                    val slotVariants = table.lookupSlot(expr.target.name)
                    if (slotVariants != null && slotVariants.any { it.first == expr.name && it.second.isEmpty() }) {
                        return IrExpr.StructCtor(expr.target.name, listOf("__tag"), listOf(IrExpr.StringLiteral(expr.name)), IrType.Named(expr.target.name))
                    }
                }
                // Enum variant `Color.Red` → string literal "Red"
                if (expr.target is Expr.Identifier && table.lookupEnum(expr.target.name) != null) {
                    return IrExpr.StringLiteral(expr.name)
                }
                // Error-set variant `ErrSet.Variant` → string literal "Variant"
                if (expr.target is Expr.Identifier && table.lookupFail(expr.target.name) != null) {
                    return IrExpr.StringLiteral(expr.name)
                }
                val target = lowerExpr(expr.target)
                // Check for a computed property (prop): `Type_name` zero-arg method.
                val tt2 = target.type
                if (tt2 is IrType.Named) {
                    val mangled = table.lookupMethod(tt2.name, expr.name)
                    if (mangled != null) {
                        val func = table.lookupFunction(mangled)
                        if (func != null && func.params.size == 1) {
                            // It's a prop — lower to a method call Type_name(self).
                            return IrExpr.Call(mangled, listOf(target), func.returnType)
                        }
                    }
                }
                val memberType = when {
                    expr.name == "length" && (target.type is IrType.Array || target.type == IrType.String) -> IrType.Int
                    (expr.name == "isEmpty" || expr.name == "isNotEmpty") && target.type is IrType.Array -> IrType.Bool
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
                        val args = expr.args.map { lowerExpr(it) }
                        // Node types use dynamic dispatch — keep as MethodCall.
                        if (tt.name in table.nodeTypes) {
                            return IrExpr.MethodCall(target, expr.name, args, func.returnType)
                        }
                        return IrExpr.Call(mangled, listOf(target) + args, func.returnType)
                    }
                }
                val args = expr.args.map { lowerExpr(it) }
                IrExpr.MethodCall(target, expr.name, args, builtinMethodReturnType(target.type, expr.name))
            }
            is Expr.StringTemplate -> {
                val parts = expr.parts.map { p ->
                    when (p) {
                        is Expr.StringTemplatePart.Literal -> IrExpr.IrTemplatePart.Literal(p.text)
                        is Expr.StringTemplatePart.Expr -> IrExpr.IrTemplatePart.Expr(lowerExpr(p.expr))
                    }
                }
                IrExpr.StringTemplate(parts)
            }
            is Expr.TupleLit -> {
                val elems = expr.elements.map { lowerExpr(it) }
                IrExpr.TupleLit(elems, IrType.Tuple(elems.map { it.type }))
            }
            is Expr.TupleAccess -> {
                val target = lowerExpr(expr.target)
                val tt = target.type
                val elemType = if (tt is IrType.Tuple && expr.index in tt.elements.indices) tt.elements[expr.index] else IrType.Any
                IrExpr.TupleAccess(target, expr.index, elemType)
            }
            is Expr.CatchExpr -> {
                val e = lowerExpr(expr.expr)
                val f = lowerExpr(expr.fallback)
                IrExpr.CatchExpr(e, f, e.type)
            }
            is Expr.NamedArg -> lowerExpr(expr.value)
            is Expr.Lambda -> {
                table.pushScope()
                pushNameScope()
                val irParams = expr.params.map { p ->
                    val t = IrType.resolve(p.type)
                    val m = registerName(p.name)
                    table.defineVariable(VariableSymbol(p.name, t))
                    m to t
                }
                val body = lowerBody(expr.body)
                popNameScope()
                table.popScope()
                val retType = body.mapNotNull { (it as? IrStmt.Return)?.value?.type }.firstOrNull() ?: IrType.Unit
                IrExpr.Lambda(irParams, body, IrType.Function(irParams.map { it.second }, retType))
            }
        }
    }

    /** Resolves the return type of a builtin method on a receiver of [receiverType]. */
    private fun builtinMethodReturnType(receiverType: IrType, name: String): IrType = when {
        receiverType is IrType.Array && name == "add" -> IrType.Unit
        receiverType is IrType.Array && (name == "isEmpty" || name == "isNotEmpty") -> IrType.Bool
        else -> IrType.Any
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
        is TypeAnnotation.Explicit -> IrType.resolve(ann.ref)
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
        is IrType.Char -> IrExpr.CharLiteral(' ')
        else -> IrExpr.Var("__null", IrType.Any)
    }
}
