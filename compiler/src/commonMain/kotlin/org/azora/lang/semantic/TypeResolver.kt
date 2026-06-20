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
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef
import org.azora.lang.ir.IrType
import kotlin.collections.iterator

/**
 * Semantic Pass 2 -- Type Resolution and Checking.
 *
 * Walks every function body, resolves expression types, and verifies
 * that all type constraints are satisfied (assignments, returns, call args).
 *
 * This pass runs on the CTFE-stabilized AST, so all compile-time constructs
 * (`inline if`, `inline fin`, etc.) have already been evaluated and removed.
 * Any remaining inline constructs are reported as errors.
 *
 * @param table the symbol table populated by [SymbolCollector], used to look up
 *   function signatures and manage local variable scopes
 */
class TypeResolver(private val table: SymbolTable) {

    private val errors = mutableListOf<String>()

    /**
     * Type-checks all functions in the given program.
     *
     * @param program the CTFE-stabilized AST to type-check
     * @return a list of type error messages (empty if the program is well-typed)
     */
    fun resolve(program: Program): List<String> {
        for (func in program.functions) {
            resolveFunction(func)
        }
        // Resolve test bodies
        for (test in program.tests) {
            table.pushScope()
            for (stmt in test.body) resolveStmt(stmt, IrType.Unit)
            table.popScope()
        }
        return errors
    }

    private fun resolveFunction(func: FuncDecl) {
        val symbol = table.lookupFunction(func.name) ?: return
        table.pushScope()

        // Register parameters as local variables
        for ((name, type) in symbol.params) {
            table.defineVariable(VariableSymbol(name, type))
        }

        resolveBody(func.body, symbol.returnType)

        table.popScope()
    }

    /**
     * Resolves a list of statements, handling friend zones by sharing
     * a scope across all friend zones in the same body.
     */
    private fun resolveBody(stmts: List<Stmt>, returnType: IrType) {
        val hasFriendZones = stmts.any { it is Stmt.FriendZone }

        if (!hasFriendZones) {
            for (stmt in stmts) resolveStmt(stmt, returnType)
            return
        }

        // Create a shared friend scope that persists across friend zone blocks
        val friendScope = mutableMapOf<String, VariableSymbol>()

        for (stmt in stmts) {
            if (stmt is Stmt.FriendZone) {
                // Push the friend scope — inject saved variables
                table.pushScope()
                for ((_, sym) in friendScope) table.defineVariable(sym)
                // Resolve the friend zone body
                for (s in stmt.body) resolveStmt(s, returnType)
                // Save any new variables back to the friend scope
                table.exportCurrentScope(friendScope)
                table.popScope()
            } else {
                resolveStmt(stmt, returnType)
            }
        }
    }

    private fun resolveStmt(stmt: Stmt, returnType: IrType) {
        when (stmt) {
            is Stmt.VarDecl -> resolveBinding(stmt.name, stmt.type, stmt.initializer, stmt.line, mutable = true)
            is Stmt.FinDecl -> resolveBinding(stmt.name, stmt.type, stmt.initializer, stmt.line, mutable = false)
            is Stmt.Assignment -> {
                val varSym = table.lookupVariable(stmt.name)
                if (varSym == null) {
                    errors.add("line ${stmt.line}: undefined variable '${stmt.name}'")
                    return
                }
                if (!varSym.mutable) {
                    errors.add("line ${stmt.line}: cannot reassign immutable binding '${stmt.name}'")
                    return
                }
                val valueType = resolveExpr(stmt.value) ?: return
                if (varSym.type != valueType) {
                    errors.add("line ${stmt.line}: cannot assign $valueType to '${stmt.name}' of type ${varSym.type}")
                }
            }
            is Stmt.LetDecl -> resolveBinding(stmt.name, stmt.type, stmt.initializer, stmt.line, mutable = false)
            is Stmt.DeepInlineBlock -> errors.add("line ${stmt.line}: deepinline block could not be evaluated at compile time")
            is Stmt.NoInline -> resolveStmt(stmt.stmt, returnType)
            is Stmt.InlineBlock -> errors.add("line ${stmt.line}: inline block could not be evaluated at compile time")
            is Stmt.InlineFin -> errors.add("line ${stmt.line}: inline fin '${stmt.name}' could not be evaluated at compile time")
            is Stmt.InlineLet -> errors.add("line ${stmt.line}: inline let '${stmt.name}' could not be evaluated at compile time")
            is Stmt.InlineVar -> errors.add("line ${stmt.line}: inline var '${stmt.name}' could not be evaluated at compile time")
            is Stmt.InlineAssignment -> errors.add("line ${stmt.line}: inline assignment '${stmt.name}' could not be evaluated at compile time")
            is Stmt.Return -> {
                if (stmt.value == null) {
                    if (returnType != IrType.Unit) {
                        errors.add("line ${stmt.line}: missing return value, expected $returnType")
                    }
                } else {
                    val valueType = resolveExpr(stmt.value) ?: return
                    if (valueType != returnType) {
                        errors.add("line ${stmt.line}: return type mismatch: expected $returnType but got $valueType")
                    }
                }
            }
            is Stmt.ExprStmt -> resolveExpr(stmt.expr)
            is Stmt.If -> {
                val condType = resolveExpr(stmt.condition) ?: return
                if (condType != IrType.Bool) {
                    errors.add("line ${stmt.line}: if condition must be Bool, got $condType")
                }
                table.pushScope()
                for (s in stmt.thenBranch) resolveStmt(s, returnType)
                table.popScope()
                if (stmt.elseBranch != null) {
                    table.pushScope()
                    for (s in stmt.elseBranch) resolveStmt(s, returnType)
                    table.popScope()
                }
            }
            is Stmt.InlineIf -> errors.add("line ${stmt.line}: inline if condition could not be evaluated at compile time")
            is Stmt.DeepInlineIf -> errors.add("line ${stmt.line}: deepinline if condition could not be evaluated at compile time")
            is Stmt.Zone -> {
                table.pushScope()
                resolveBody(stmt.body, returnType)
                table.popScope()
            }
            is Stmt.FriendZone -> {
                // Handled by resolveBody — should not reach here in normal flow
                table.pushScope()
                resolveBody(stmt.body, returnType)
                table.popScope()
            }
            is Stmt.Assert -> {
                val condType = resolveExpr(stmt.condition) ?: return
                if (condType != IrType.Bool) {
                    errors.add("line ${stmt.line}: assert condition must be Bool, got $condType")
                }
                val msgType = resolveExpr(stmt.message) ?: return
                if (msgType != IrType.String) {
                    errors.add("line ${stmt.line}: assert message must be String, got $msgType")
                }
            }
            is Stmt.Trace -> {
                val msgType = resolveExpr(stmt.message) ?: return
                if (msgType != IrType.String) {
                    errors.add("line ${stmt.line}: trace message must be String, got $msgType")
                }
            }
            is Stmt.While -> {
                val condType = resolveExpr(stmt.condition)
                if (condType != null && condType != IrType.Bool) {
                    errors.add("line ${stmt.line}: while condition must be Bool, got $condType")
                }
                table.pushScope()
                resolveBody(stmt.body, returnType)
                table.popScope()
            }
            is Stmt.For -> {
                when (val iter = stmt.iterable) {
                    is Expr.Range -> {
                        val fromType = resolveExpr(iter.from)
                        val toType = resolveExpr(iter.to)
                        if (fromType != null && fromType != IrType.Int) {
                            errors.add("line ${stmt.line}: range start must be Int, got $fromType")
                        }
                        if (toType != null && toType != IrType.Int) {
                            errors.add("line ${stmt.line}: range end must be Int, got $toType")
                        }
                    }
                    else -> {
                        errors.add("line ${stmt.line}: for loop iterable must be an integer range (e.g. 0..10)")
                    }
                }
                table.pushScope()
                table.defineVariable(VariableSymbol(stmt.name, IrType.Int, mutable = true))
                resolveBody(stmt.body, returnType)
                table.popScope()
            }
            is Stmt.Loop -> {
                table.pushScope()
                resolveBody(stmt.body, returnType)
                table.popScope()
            }
            is Stmt.Break -> { /* no type constraint */ }
            is Stmt.Continue -> { /* no type constraint */ }
            is Stmt.IndexAssign -> {
                val targetType = resolveExpr(stmt.target) ?: return
                val indexType = resolveExpr(stmt.index) ?: return
                if (targetType !is IrType.Array) {
                    errors.add("line ${stmt.line}: cannot index-assign to $targetType (not an array)")
                    return
                }
                if (indexType != IrType.Int) {
                    errors.add("line ${stmt.line}: array index must be Int, got $indexType")
                    return
                }
                val valueType = resolveExpr(stmt.value) ?: return
                if (valueType != targetType.element) {
                    errors.add("line ${stmt.line}: cannot assign $valueType to array of ${targetType.element}")
                }
            }
            is Stmt.MemberAssign -> {
                val targetType = resolveExpr(stmt.target) ?: return
                val valueType = resolveExpr(stmt.value) ?: return
                if (targetType is IrType.Named) {
                    val field = table.lookupStruct(targetType.name)?.field(stmt.name)
                    if (field == null) {
                        errors.add("line ${stmt.line}: no field '${stmt.name}' on struct ${targetType.name}")
                        return
                    }
                    if (!field.mutable) {
                        errors.add("line ${stmt.line}: cannot assign to immutable field '${stmt.name}' of struct ${targetType.name}")
                        return
                    }
                    if (valueType != field.type) {
                        errors.add("line ${stmt.line}: cannot assign $valueType to field '${stmt.name}' of type ${field.type}")
                    }
                } else {
                    errors.add("line ${stmt.line}: cannot assign member '${stmt.name}' on $targetType (not a struct)")
                }
            }
            is Stmt.InlineAssert -> errors.add("line ${stmt.line}: inline assert could not be evaluated at compile time")
            is Stmt.InlineTrace -> errors.add("line ${stmt.line}: inline trace could not be evaluated at compile time")
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

    private fun resolveExpr(expr: Expr): IrType? {
        return when (expr) {
            is Expr.IntLiteral -> suffixToIntType(expr.suffix)
            is Expr.RealLiteral -> suffixToRealType(expr.suffix)
            is Expr.StringLiteral -> IrType.String
            is Expr.BoolLiteral -> IrType.Bool
            is Expr.CharLiteral -> IrType.Char
            is Expr.Identifier -> {
                val sym = table.lookupVariable(expr.name)
                if (sym == null) {
                    errors.add("line ${expr.line}: undefined variable '${expr.name}'")
                    null
                } else sym.type
            }
            is Expr.UpperScopeAccess -> {
                val sym = table.lookupVariableInUpperScope(expr.name, expr.depth)
                if (sym == null) {
                    val colons = "::".repeat(expr.depth)
                    errors.add("line ${expr.line}: '${expr.name}' not found at scope depth $colons")
                    null
                } else sym.type
            }
            is Expr.Unary -> {
                val operandType = resolveExpr(expr.operand) ?: return null
                when (expr.op) {
                    TokenType.MINUS -> {
                        if (operandType !in IrType.Companion.numericTypes) {
                            errors.add("line ${expr.line}: cannot negate $operandType")
                            null
                        } else operandType
                    }
                    TokenType.BANG -> {
                        if (operandType != IrType.Bool) {
                            errors.add("line ${expr.line}: '!' requires Bool, got $operandType")
                            null
                        } else IrType.Bool
                    }
                    else -> { errors.add("line ${expr.line}: unknown unary op ${expr.op}"); null }
                }
            }
            is Expr.Binary -> {
                val leftType = resolveExpr(expr.left) ?: return null
                val rightType = resolveExpr(expr.right) ?: return null
                resolveBinaryType(expr.op, leftType, rightType, expr.line)
            }
            is Expr.Call -> {
                // Struct construction: `Name(args)` where Name is a pack.
                val struct = table.lookupStruct(expr.callee)
                if (struct != null) {
                    if (expr.args.size != struct.fields.size) {
                        errors.add("line ${expr.line}: '${expr.callee}' expects ${struct.fields.size} field arguments, got ${expr.args.size}")
                        return null
                    }
                    for (i in expr.args.indices) {
                        val argType = resolveExpr(expr.args[i]) ?: return null
                        val fieldType = struct.fields[i].type
                        if (argType != fieldType) {
                            errors.add("line ${expr.line}: field '${struct.fields[i].name}' of '${expr.callee}': expected $fieldType, got $argType")
                        }
                    }
                    return IrType.Named(struct.name)
                }
                val func = table.lookupFunction(expr.callee)
                if (func == null) {
                    errors.add("line ${expr.line}: undefined function '${expr.callee}'")
                    return null
                }
                if (expr.args.size != func.params.size) {
                    errors.add("line ${expr.line}: '${expr.callee}' expects ${func.params.size} args, got ${expr.args.size}")
                    return null
                }
                val isBuiltin = expr.callee == "println"
                for (i in expr.args.indices) {
                    val argType = resolveExpr(expr.args[i]) ?: return null
                    val paramType = func.params[i].second
                    // Built-in println accepts any type
                    if (!isBuiltin && argType != paramType) {
                        errors.add("line ${expr.line}: arg ${i + 1} of '${expr.callee}': expected $paramType, got $argType")
                    }
                }
                func.returnType
            }
            is Expr.Grouping -> resolveExpr(expr.expr)
            is Expr.Range -> {
                errors.add("line ${expr.line}: ranges can only be used as for-loop iterables")
                null
            }
            is Expr.ArrayLiteral -> {
                if (expr.elements.isEmpty()) {
                    errors.add("line ${expr.line}: cannot infer element type of an empty array literal")
                    null
                } else {
                    val elemType = resolveExpr(expr.elements[0]) ?: return null
                    for (i in 1 until expr.elements.size) {
                        val t = resolveExpr(expr.elements[i]) ?: return null
                        if (t != elemType) {
                            errors.add("line ${expr.line}: array elements must share a type, got $elemType and $t")
                            return null
                        }
                    }
                    IrType.Array(elemType)
                }
            }
            is Expr.Index -> {
                val targetType = resolveExpr(expr.target) ?: return null
                val indexType = resolveExpr(expr.index) ?: return null
                if (indexType != IrType.Int) {
                    errors.add("line ${expr.line}: array index must be Int, got $indexType")
                    return null
                }
                if (targetType !is IrType.Array) {
                    errors.add("line ${expr.line}: cannot index into $targetType (not an array)")
                    return null
                }
                targetType.element
            }
            is Expr.Member -> {
                val targetType = resolveExpr(expr.target) ?: return null
                when {
                    expr.name == "length" && (targetType is IrType.Array || targetType == IrType.String) -> IrType.Int
                    (expr.name == "isEmpty" || expr.name == "isNotEmpty") && targetType is IrType.Array -> IrType.Bool
                    targetType is IrType.Named -> {
                        val field = table.lookupStruct(targetType.name)?.field(expr.name)
                        if (field == null) {
                            errors.add("line ${expr.line}: no field '${expr.name}' on struct ${targetType.name}")
                            null
                        } else field.type
                    }
                    else -> {
                        errors.add("line ${expr.line}: no member '${expr.name}' on $targetType")
                        null
                    }
                }
            }
            is Expr.MethodCall -> {
                val targetType = resolveExpr(expr.target) ?: return null
                resolveBuiltinMethod(targetType, expr.name, expr.args, expr.line)
            }
            is Expr.StringTemplate -> {
                for (part in expr.parts) {
                    if (part is Expr.StringTemplatePart.Expr) {
                        resolveExpr(part.expr) // any type is allowed; formatted at runtime
                    }
                }
                IrType.String
            }
        }
    }

    /**
     * Type-checks a builtin method call on a receiver of [receiverType].
     * Currently supports a small set of array methods (`add`, `isEmpty`, `isNotEmpty`).
     */
    private fun resolveBuiltinMethod(receiverType: IrType, name: String, args: List<Expr>, line: Int): IrType? {
        if (receiverType is IrType.Array) {
            return when (name) {
                "add" -> {
                    if (args.size != 1) {
                        errors.add("line $line: 'add' expects 1 argument, got ${args.size}")
                        return null
                    }
                    val argType = resolveExpr(args[0]) ?: return null
                    if (argType != receiverType.element) {
                        errors.add("line $line: 'add' expects ${receiverType.element}, got $argType")
                        return null
                    }
                    IrType.Unit
                }
                "isEmpty", "isNotEmpty" -> {
                    if (args.isNotEmpty()) {
                        errors.add("line $line: '$name' expects 0 arguments, got ${args.size}")
                        return null
                    }
                    IrType.Bool
                }
                else -> {
                    errors.add("line $line: no method '$name' on array")
                    null
                }
            }
        }
        errors.add("line $line: no method '$name' on $receiverType")
        return null
    }

    private fun resolveBinaryType(op: TokenType, left: IrType, right: IrType, line: Int): IrType? {
        return when (op) {
            TokenType.PLUS -> {
                if (left == IrType.String && right == IrType.String) IrType.String
                else if (left != right || left !in IrType.Companion.numericTypes) {
                    errors.add("line $line: cannot apply '$op' to $left and $right")
                    null
                } else left
            }
            TokenType.STAR -> {
                if ((left == IrType.String && right == IrType.Int) ||
                    (left == IrType.Int && right == IrType.String)) IrType.String
                else if (left != right || left !in IrType.Companion.numericTypes) {
                    errors.add("line $line: cannot apply '$op' to $left and $right")
                    null
                } else left
            }
            TokenType.MINUS, TokenType.SLASH, TokenType.PERCENT -> {
                if (left != right || left !in IrType.Companion.numericTypes) {
                    errors.add("line $line: cannot apply '$op' to $left and $right")
                    null
                } else left
            }
            TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL -> {
                if (left != right) {
                    errors.add("line $line: cannot compare $left and $right")
                    null
                } else IrType.Bool
            }
            TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL -> {
                if (left != right || (left !in IrType.Companion.numericTypes && left != IrType.Char)) {
                    errors.add("line $line: cannot compare $left and $right with '$op'")
                    null
                } else IrType.Bool
            }
            TokenType.AND_AND, TokenType.OR_OR -> {
                if (left != IrType.Bool || right != IrType.Bool) {
                    errors.add("line $line: '$op' requires Bool operands, got $left and $right")
                    null
                } else IrType.Bool
            }
            else -> { errors.add("line $line: unknown binary op $op"); null }
        }
    }

    private fun resolveBinding(name: String, typeAnn: TypeAnnotation, initializer: Expr, line: Int, mutable: Boolean) {
        if (table.lookupVariableInCurrentScope(name) != null) {
            errors.add("line $line: '$name' is already declared in this scope")
            return
        }
        val initType = resolveExpr(initializer) ?: return
        when (typeAnn) {
            is TypeAnnotation.Explicit -> {
                val declaredType = tryResolveType(typeAnn.ref, line) ?: return
                if (declaredType != initType) {
                    errors.add("line $line: type mismatch in '$name': declared $declaredType but initializer is $initType")
                }
                table.defineVariable(VariableSymbol(name, declaredType, mutable))
            }
            is TypeAnnotation.Inferred -> {
                table.defineVariable(VariableSymbol(name, initType, mutable))
            }
        }
    }

    private fun tryResolveType(ref: TypeRef, line: Int): IrType? {
        return try {
            IrType.resolve(ref)
        } catch (e: Exception) {
            errors.add("line $line: ${e.message}")
            null
        }
    }
}
