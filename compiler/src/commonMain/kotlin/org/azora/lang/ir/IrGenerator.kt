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
        val items = program.items.mapNotNull { item ->
            when (item) {
                is TopLevel.Func -> {
                    if (item.decl.isInline) null // Skip inline functions — already substituted by CTFE
                    else IrTopLevel.Func(lowerFunction(item.decl))
                }
                is TopLevel.FinDecl -> {
                    val init = lowerExpr(item.initializer)
                    val type = if (item.type != null) IrType.resolve(item.type) else init.type
                    IrTopLevel.Global(IrStmt.FinDecl(item.name, type, init))
                }
                is TopLevel.LetDecl -> {
                    val init = lowerExpr(item.initializer)
                    val type = if (item.type != null) IrType.resolve(item.type) else init.type
                    IrTopLevel.Global(IrStmt.LetDecl(item.name, type, init))
                }
                is TopLevel.VarDecl -> {
                    val init = lowerExpr(item.initializer)
                    val type = if (item.type != null) IrType.resolve(item.type) else init.type
                    IrTopLevel.Global(IrStmt.VarDecl(item.name, type, init))
                }
                is TopLevel.Test -> {
                    table.pushScope()
                    pushNameScope()
                    val body = lowerBody(item.body)
                    popNameScope()
                    table.popScope()
                    IrTopLevel.Test(item.name, body)
                }
                is TopLevel.Pack -> {
                    val fields = item.fields.map { IrField(it.name, IrType.resolve(it.type), it.mutable) }
                    IrTopLevel.Struct(item.name, fields)
                }
                else -> null // Inline constructs already resolved by CTFE
            }
        }
        return IrProgram(program.packageName, items)
    }

    private fun lowerFunction(func: FuncDecl): IrFunction {
        val symbol = table.lookupFunction(func.name)!!
        table.pushScope()
        pushNameScope()

        // Register parameters
        val mangledParams = symbol.params.map { (name, type) ->
            val mangled = registerName(name)
            table.defineVariable(VariableSymbol(name, type))
            mangled to type
        }

        val body = lowerBody(func.body)
        popNameScope()
        table.popScope()

        return IrFunction(func.name, mangledParams, symbol.returnType, body)
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
                for (s in stmt.body) result.add(lowerStmt(s))
                // Save variables back for next friend zone
                table.exportCurrentScope(friendSymbols)
                nameScopes.removeLast()
                table.popScope()
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
                val index = lowerExpr(stmt.index)
                val value = lowerExpr(stmt.value)
                IrStmt.IndexAssign(target, index, value)
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
                IrStmt.Zone(stmts)
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
                IrStmt.While(cond, body)
            }
            is Stmt.For -> {
                val range = stmt.iterable as? Expr.Range
                    ?: error("for loop iterable must be a range (e.g. 0..10), got ${stmt.iterable::class.simpleName}")
                val start = lowerExpr(range.from)
                val end = lowerExpr(range.to)
                table.pushScope()
                pushNameScope()
                val counter = registerName(stmt.name)
                table.defineVariable(VariableSymbol(stmt.name, IrType.Int, mutable = true))
                val body = lowerBody(stmt.body)
                popNameScope()
                table.popScope()
                IrStmt.For(counter, start, end, range.inclusive, body)
            }
            is Stmt.Loop -> {
                table.pushScope()
                pushNameScope()
                val body = lowerBody(stmt.body)
                popNameScope()
                table.popScope()
                IrStmt.Loop(body)
            }
            is Stmt.Break -> IrStmt.Break
            is Stmt.Continue -> IrStmt.Continue
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
                    else -> error("Unknown unary op: ${expr.op}")
                }
                IrExpr.Unary(op, operand, operand.type)
            }
            is Expr.Binary -> {
                val left = lowerExpr(expr.left)
                val right = lowerExpr(expr.right)
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
                    val args = expr.args.map { lowerExpr(it) }
                    IrExpr.StructCtor(expr.callee, struct.fields.map { it.name }, args, IrType.Named(expr.callee))
                } else {
                    val func = table.lookupFunction(expr.callee)!!
                    val args = expr.args.map { lowerExpr(it) }
                    IrExpr.Call(expr.callee, args, func.returnType)
                }
            }
            is Expr.Grouping -> lowerExpr(expr.expr)
            is Expr.Range -> error("range expressions can only be used as for-loop iterables")
            is Expr.ArrayLiteral -> {
                val elems = expr.elements.map { lowerExpr(it) }
                val elemType = if (elems.isEmpty()) IrType.Any else elems.first().type
                IrExpr.ArrayLiteral(elems, IrType.Array(elemType))
            }
            is Expr.Index -> {
                val target = lowerExpr(expr.target)
                val index = lowerExpr(expr.index)
                val elemType = (target.type as? IrType.Array)?.element ?: IrType.Any
                IrExpr.Index(target, index, elemType)
            }
            is Expr.Member -> {
                val target = lowerExpr(expr.target)
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
                val target = lowerExpr(expr.target)
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
        }
    }

    /** Resolves the return type of a builtin method on a receiver of [receiverType]. */
    private fun builtinMethodReturnType(receiverType: IrType, name: String): IrType = when {
        receiverType is IrType.Array && name == "add" -> IrType.Unit
        receiverType is IrType.Array && (name == "isEmpty" || name == "isNotEmpty") -> IrType.Bool
        else -> IrType.Any
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
        else -> error("Unknown binary op: $op")
    }

    private fun resolveTypeAnnotation(ann: TypeAnnotation, init: IrExpr): IrType = when (ann) {
        is TypeAnnotation.Explicit -> IrType.resolve(ann.ref)
        is TypeAnnotation.Inferred -> init.type
    }
}
