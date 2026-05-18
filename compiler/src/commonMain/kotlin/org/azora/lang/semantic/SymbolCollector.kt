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

package org.azora.lang.semantic

import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.NumericSuffix
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.ir.IrType

/**
 * Semantic Pass 1 — Symbol Collection.
 *
 * Walks all top-level declarations and registers function signatures
 * in the [SymbolTable]. Does NOT look inside function bodies — that
 * happens in [TypeResolver] (Pass 2).
 */
class SymbolCollector {

    private fun registerBuiltins(table: SymbolTable) {
        // println accepts String — type checker will allow String args
        if (table.lookupFunction("println") == null) {
            table.defineFunction(FunctionSymbol("println", listOf("value" to IrType.String), IrType.Unit))
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
                        val type = if (item.typeName != null) IrType.Companion.fromName(item.typeName)
                                   else initType ?: IrType.Int
                        table.defineVariable(VariableSymbol(item.name, type, mutable = false))
                    } catch (e: Exception) {
                        errors.add("line ${item.line}: ${e.message}")
                    }
                }
                else -> {}
            }
        }

        for (func in program.functions) {
            try {
                val params = func.params.map { it.name to IrType.Companion.fromName(it.typeName) }
                val returnType = when (val rt = func.returnType) {
                    is TypeAnnotation.Explicit -> IrType.Companion.fromName(rt.name)
                    is TypeAnnotation.Inferred -> inferReturnType(func, params)
                }
                table.defineFunction(FunctionSymbol(func.name, params, returnType, func.isInline))
            } catch (e: Exception) {
                errors.add("line ${func.line}: ${e.message}")
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
            else -> IrType.Float
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
    }
}
