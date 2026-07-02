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
        if (table.lookupFunction("channel") == null) {
            // `channel()` — creates a buffered channel for task-to-task communication.
            table.defineFunction(FunctionSymbol("channel", emptyList(), IrType.Named("Channel")))
        }
        if (table.lookupFunction("__launch") == null) {
            // `launch { body }` desugars to __launch(thunk); fire-and-forget, joined at end.
            table.defineFunction(FunctionSymbol("__launch", listOf("thunk" to IrType.Any), IrType.Unit))
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
                table.defineFunction(FunctionSymbol(func.name, params, callReturnType, func.isInline, func.typeParams, paramNames, defaults))
            } catch (e: Exception) {
                errors.add("line ${func.line}: ${e.message}")
            }
        }

        // Register pack (struct) declarations
        for (item in program.items) {
            if (item is TopLevel.Pack) {
                try {
                    val tpSet = item.typeParams.toSet()
                    val fields = item.fields.map { field ->
                        StructField(field.name, IrType.resolve(field.type, tpSet), field.mutable)
                    }
                    table.defineStruct(StructType(item.name, fields, item.typeParams))
                } catch (e: Exception) {
                    errors.add("line ${item.line}: ${e.message}")
                }
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
        for (item in program.items) {
            if (item is TopLevel.Impl) {
                for (method in item.methods) {
                    val mangled = "${item.typeName}_${method.name}"
                    try {
                        val selfType = IrType.Named(item.typeName)
                        val params = mutableListOf<Pair<String, IrType>>()
                        params.add("self" to selfType)
                        for (p in method.params) params.add(p.name to IrType.resolve(p.type))
                        val returnType = when (val rt = method.returnType) {
                            is TypeAnnotation.Explicit -> IrType.resolve(rt.ref)
                            is TypeAnnotation.Inferred -> inferReturnType(method, params)
                        }
                        table.defineFunction(FunctionSymbol(mangled, params, returnType, method.isInline))
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
                table.defineSpec(item.name, item.methods.map { it.name })
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
                val required = table.lookupSpec(item.traitName)
                if (required == null) {
                    errors.add("line ${item.line}: unknown spec '${item.traitName}'")
                } else {
                    val provided = item.methods.map { it.name }.toSet()
                    for (req in required) {
                        if (req !in provided) {
                            errors.add("line ${item.line}: '${item.typeName}' does not implement '${item.traitName}.${req}'")
                        }
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
        is Expr.Range -> null // ranges are not first-class values
        is Expr.ArrayLiteral, is Expr.Index, is Expr.Member, is Expr.MethodCall -> null
        is Expr.StringTemplate -> IrType.String
        is Expr.TupleLit, is Expr.TupleAccess -> null
        is Expr.CatchExpr -> null
        is Expr.Lambda -> null
        is Expr.NamedArg -> null
        is Expr.NullLiteral -> IrType.Any
        is Expr.NullCoalesce, is Expr.SafeMember,
        is Expr.Cast, is Expr.IsCheck, is Expr.MapLit, is Expr.Alloc, is Expr.Deref, is Expr.Isolated, is Expr.Await -> null
    }
}
