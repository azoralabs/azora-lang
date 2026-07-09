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
import org.azora.lang.frontend.Visibility
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
    private var unsafeContext = false
    private var currentReceiverType: String? = null

    private val errors = mutableListOf<String>()
    private var program: Program? = null

    fun resolve(program: Program): List<String> {
        this.program = program
        for (func in program.functions) {
            resolveFunction(func)
        }
        // Resolve test bodies
        for (test in program.tests) {
            table.pushScope()
            for (stmt in test.body) resolveStmt(stmt, IrType.Unit)
            table.popScope()
        }
        // Resolve impl method bodies (self + declared params in scope)
        for (item in program.items) {
            if (item is TopLevel.Impl) {
                for (method in item.methods) {
                    val mangled = "${item.typeName}_${method.name}"
                    val func = table.lookupFunction(mangled) ?: continue
                    table.pushScope()
                    for ((name, type) in func.params) {
                        table.defineVariable(VariableSymbol(name, type))
                    }
                    val savedReceiver = currentReceiverType
                    currentReceiverType = item.typeName
                    resolveBody(method.body, func.returnType)
                    currentReceiverType = savedReceiver
                    table.popScope()
                }
            }
        }
        return errors
    }

    private fun resolveFunction(func: FuncDecl) {
        val symbol = table.lookupFunction(func.name) ?: return
        table.pushScope()

        // Register parameters as local variables
        for (i in symbol.params.indices) {
            val (name, type) = symbol.params[i]
            val modifier = func.params.getOrNull(i)?.modifier.orEmpty()
            val mutable = modifier in setOf("mut", "out", "ref", "mut ref")
            table.defineVariable(VariableSymbol(name, type, mutable))
        }

        // `T!ErrSet` enforcement: track the function's declared error set so that
        // `fail`/`throw` of an error variant can be checked against it.
        val savedFailSet = declaredFailSet
        declaredFailSet = (func.returnType as? TypeAnnotation.Explicit)
            ?.ref?.let { (it as? TypeRef.Failable)?.errSet }
        val savedUnsafe = unsafeContext
        val savedReceiver = currentReceiverType
        unsafeContext = func.isUnsafe
        currentReceiverType = null
        resolveBody(func.body, symbol.returnType)
        currentReceiverType = savedReceiver
        unsafeContext = savedUnsafe
        declaredFailSet = savedFailSet

        table.popScope()
    }

    /** The declared error set of the function currently being resolved (`T!E`'s `E`), or null. */
    private var declaredFailSet: String? = null

    private fun canAccessMember(ownerType: String, visibility: Visibility): Boolean = when (visibility) {
        Visibility.EXPOSE -> true
        Visibility.CONFINE -> currentReceiverType == ownerType
        Visibility.PROTECT -> {
            var cursor = currentReceiverType
            while (cursor != null) {
                if (cursor == ownerType) return true
                cursor = table.nodeParents[cursor]
            }
            false
        }
    }

    private fun reportInaccessible(line: Int, kind: String, ownerType: String, name: String, visibility: Visibility) {
        val label = when (visibility) {
            Visibility.EXPOSE -> "exposed"
            Visibility.PROTECT -> "protected"
            Visibility.CONFINE -> "confined"
        }
        errors.add("line $line: cannot access $label $kind '$name' on $ownerType")
    }

    /**
     * Expected type for an implicit `it` lambda parameter, inferred from context (e.g. the
     * function-parameter type a lambda is passed as). Consumed by `Expr.Lambda` resolution.
     */
    private var expectedItType: IrType? = null

    /** If [expr] is `ErrSet.Variant`, returns the error-set name; otherwise null. */
    private fun failSetOf(expr: Expr): String? {
        val m = expr as? Expr.Member ?: return null
        val id = m.target as? Expr.Identifier ?: return null
        return if (table.lookupFail(id.name) != null) id.name else null
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
            is Stmt.RemDecl -> resolveBinding(stmt.name, stmt.type, stmt.initializer, stmt.line, mutable = true)
            is Stmt.Effect -> { resolveBody(stmt.body, returnType) }
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
                if (!isCompatible(varSym.type, valueType)) {
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
                    if (valueType != returnType && returnType != IrType.Any) {
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
            is Stmt.InlineFor -> errors.add("line ${stmt.line}: inline for range could not be evaluated at compile time")
            is Stmt.DeepInlineIf -> errors.add("line ${stmt.line}: deepinline if condition could not be evaluated at compile time")
            is Stmt.Zone -> {
                table.pushScope()
                val savedUnsafe = unsafeContext
                if (stmt.unsafe) unsafeContext = true
                resolveBody(stmt.body, returnType)
                unsafeContext = savedUnsafe
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
                        table.pushScope()
                        table.defineVariable(VariableSymbol(stmt.name, IrType.Int, mutable = true))
                        resolveBody(stmt.body, returnType)
                        table.popScope()
                    }
                    else -> {
                        val iterType = resolveExpr(iter)
                        if (iterType == null) return
                    if (iterType !is IrType.Array && iterType !is IrType.Set) {
                        errors.add("line ${stmt.line}: for loop iterable must be a range or array, got $iterType")
                        return
                    }
                    table.pushScope()
                    val elementType = when (iterType) {
                        is IrType.Array -> iterType.element
                        is IrType.Set -> iterType.element
                        else -> error("unreachable")
                    }
                    table.defineVariable(VariableSymbol(stmt.name, elementType, mutable = false))
                        resolveBody(stmt.body, returnType)
                        table.popScope()
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
                // User-defined index-assign operator (`oper[]=`) on a struct.
                if (targetType is IrType.Named) {
                    val mangled = table.lookupMethod(targetType.name, "indexSet")
                    if (mangled != null) {
                        resolveExpr(stmt.index) ?: return
                        resolveExpr(stmt.value) ?: return
                        return
                    }
                }
                // Map index-assign: `map[key] = value`.
                if (targetType is IrType.Map) {
                    resolveExpr(stmt.index) ?: return
                    resolveExpr(stmt.value) ?: return
                    return
                }
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
            is Stmt.DerefAssign -> {
                resolveExpr(stmt.target) ?: return
                resolveExpr(stmt.value) ?: return
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
                    if (!canAccessMember(targetType.name, field.visibility)) {
                        reportInaccessible(stmt.line, "field", targetType.name, stmt.name, field.visibility)
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
            is Stmt.When -> {
                resolveExpr(stmt.scrutinee) ?: return
                for (branch in stmt.branches) {
                    var handledBySlot = false
                    for (pattern in branch.patterns) {
                        if (pattern is Expr.MethodCall && pattern.target is Expr.Identifier) {
                            val slotVariants = table.lookupSlot(pattern.target.name)
                            if (slotVariants != null) {
                                val variant = slotVariants.find { it.first == pattern.name }
                                if (variant != null) {
                                    table.pushScope()
                                    for (i in pattern.args.indices) {
                                        val bindName = (pattern.args[i] as? Expr.Identifier)?.name
                                        if (bindName != null && i < variant.second.size) {
                                            table.defineVariable(VariableSymbol(bindName, variant.second[i], mutable = true))
                                        }
                                    }
                                    resolveBody(branch.body, returnType)
                                    table.popScope()
                                    handledBySlot = true
                                    break
                                }
                            }
                        }
                    }
                    if (handledBySlot) continue
                    for (pattern in branch.patterns) {
                        resolveExpr(pattern) ?: return
                    }
                    table.pushScope()
                    resolveBody(branch.body, returnType)
                    table.popScope()
                }
                if (stmt.elseBranch != null) {
                    table.pushScope()
                    resolveBody(stmt.elseBranch, returnType)
                    table.popScope()
                } else {
                    // Exhaustiveness check for enum/slot
                    val scrutType = resolveExpr(stmt.scrutinee)
                    if (scrutType != null) {
                        val allVariants = if (scrutType is IrType.Named) {
                            table.lookupSlot(scrutType.name)?.map { it.first }
                                ?: table.lookupEnum(scrutType.name)
                        } else null
                        if (allVariants != null && scrutType is IrType.Named) {
                            val covered = mutableSetOf<String>()
                            for (branch in stmt.branches) {
                                for (pattern in branch.patterns) {
                                    extractVariantName(pattern, table)?.let { covered.add(it) }
                                }
                            }
                            val missing = allVariants.filter { it !in covered && it != "_" }
                            if (missing.isNotEmpty()) {
                                errors.add("line ${stmt.line}: non-exhaustive when: missing variants ${missing.joinToString(", ")}")
                            }
                        }
                    }
                }
            }
            is Stmt.Throw -> {
                resolveExpr(stmt.value)
                // `T!E` enforcement: a thrown error variant must belong to the declared set E.
                val thrownSet = failSetOf(stmt.value)
                if (thrownSet != null && declaredFailSet != null && thrownSet != declaredFailSet) {
                    errors.add("line ${stmt.line}: function declares '!$declaredFailSet' but throws error from '$thrownSet'")
                }
            }
            is Stmt.Yield -> { resolveExpr(stmt.value) }
            is Stmt.Try -> {
                table.pushScope()
                resolveBody(stmt.body, returnType)
                table.popScope()
                if (stmt.catchBody != null) {
                    table.pushScope()
                    if (stmt.catchName != null) {
                        table.defineVariable(VariableSymbol(stmt.catchName, IrType.Any, mutable = false))
                    }
                    resolveBody(stmt.catchBody, returnType)
                    table.popScope()
                }
            }
            is Stmt.Defer -> {
                table.pushScope()
                resolveBody(stmt.body, returnType)
                table.popScope()
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
            is Expr.NullLiteral -> IrType.Any  // null is compatible with any nullable type
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
                        // Any (an erased generic T) negates at runtime.
                        if (operandType !in IrType.numericTypes && operandType != IrType.Any) {
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
                    TokenType.TILDE -> {
                        if (operandType !in IrType.integerTypes) {
                            errors.add("line ${expr.line}: '~' requires integer, got $operandType")
                            null
                        } else operandType
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
                    // Handle named arguments — reorder to field order
                    val effectiveArgs = if (expr.args.isNotEmpty() && expr.args[0] is Expr.NamedArg) {
                        if (!expr.args.all { it is Expr.NamedArg }) {
                            errors.add("line ${expr.line}: cannot mix named and positional arguments")
                            return null
                        }
                        val namedMap = expr.args.associate { (it as Expr.NamedArg).name to (it as Expr.NamedArg).value }
                        struct.fields.map { f -> namedMap[f.name]
                            ?: run { errors.add("line ${expr.line}: missing field '${f.name}' in '${expr.callee}'"); return null }
                        }
                    } else {
                        expr.args
                    }
                    if (effectiveArgs.size != struct.fields.size) {
                        errors.add("line ${expr.line}: '${expr.callee}' expects ${struct.fields.size} field arguments, got ${effectiveArgs.size}")
                        return null
                    }
                    for (i in effectiveArgs.indices) {
                        val argType = resolveExpr(effectiveArgs[i]) ?: return null
                        if (struct.typeParams.isEmpty()) {
                            val fieldType = struct.fields[i].type
                            if (!isCompatible(fieldType, argType)) {
                                errors.add("line ${expr.line}: field '${struct.fields[i].name}' of '${expr.callee}': expected $fieldType, got $argType")
                            }
                        }
                    }
                    return IrType.Named(struct.name)
                }
                val func = table.lookupFunction(expr.callee)
                if (func == null) {
                    // Maybe a lambda stored in a variable.
                    val v = table.lookupVariable(expr.callee)
                    if (v != null && v.type is IrType.Function) {
                        val fn = v.type
                        if (expr.args.size != fn.params.size) {
                            errors.add("line ${expr.line}: '${expr.callee}' expects ${fn.params.size} args, got ${expr.args.size}")
                            return null
                        }
                        for (i in expr.args.indices) {
                            val at = resolveExpr(expr.args[i]) ?: return null
                            if (!isCompatible(fn.params[i], at)) {
                                errors.add("line ${expr.line}: arg ${i + 1} of '${expr.callee}': expected ${fn.params[i]}, got $at")
                            }
                        }
                        return fn.ret
                    }
                    errors.add("line ${expr.line}: undefined function '${expr.callee}'")
                    return null
                }
                if (func.isUnsafe && !unsafeContext) {
                    errors.add("line ${expr.line}: call to unsafe '${expr.callee}' requires an unsafe block or unsafe function")
                    return null
                }
                // Handle named arguments — reorder to param order
                val effectiveArgs = if (expr.args.isNotEmpty() && expr.args[0] is Expr.NamedArg && func.paramNames.isNotEmpty()) {
                    val namedMap = expr.args.associate { (it as Expr.NamedArg).name to (it as Expr.NamedArg).value }
                    func.paramNames.map { pn -> namedMap[pn] ?: error("Missing arg '$pn'") }
                } else {
                    expr.args
                }
                // Check arg count.
                val hasSpread = effectiveArgs.any { it is Expr.Spread }
                if (func.isVariadic) {
                    // Variadic: min args = params - 1 (all but the variadic param).
                    val minArgs = func.params.size - 1
                    if (effectiveArgs.size < minArgs) {
                        errors.add("line ${expr.line}: '${expr.callee}' expects at least $minArgs args, got ${effectiveArgs.size}")
                        return null
                    }
                } else if (hasSpread) {
                    // A spread arg fills remaining params — skip the count check (runtime handles correctness).
                } else if (effectiveArgs.size > func.params.size) {
                    errors.add("line ${expr.line}: '${expr.callee}' expects ${func.params.size} args, got ${effectiveArgs.size}")
                    return null
                } else if (effectiveArgs.size < func.params.size) {
                    val minArgs = func.params.size - func.defaults.size
                    if (effectiveArgs.size < minArgs) {
                        errors.add("line ${expr.line}: '${expr.callee}' expects at least $minArgs args, got ${effectiveArgs.size}")
                        return null
                    }
                }
                val isBuiltin = expr.callee == "println"
                val isGeneric = func.typeParams.isNotEmpty()
                val argTypes = mutableListOf<IrType>()
                for (i in effectiveArgs.indices) {
                    // `it` inference: if this argument is an implicit-`it` lambda, seed `it`'s type
                    // from the corresponding function-parameter type before resolving it.
                    val arg = effectiveArgs[i]
                    val prevIt = expectedItType
                    if (arg is Expr.Lambda && arg.params.size == 1 && arg.params[0].name == "it" && i < func.params.size) {
                        val ptype = func.params[i].second
                        if (ptype is IrType.Function && ptype.params.isNotEmpty()) expectedItType = ptype.params[0]
                    }
                    val argType = resolveExpr(arg) ?: run { expectedItType = prevIt; return null }
                    expectedItType = prevIt
                    argTypes.add(argType)
                    if (!isBuiltin && !isGeneric) {
                        val paramType = func.params[i].second
                        if (!isCompatible(paramType, argType)) {
                            errors.add("line ${expr.line}: arg ${i + 1} of '${expr.callee}': expected $paramType, got $argType")
                        }
                    }
                }
                // Generic function: infer type params from args for a precise return type.
                if (isGeneric) {
                    val funcDecl = program?.functions?.find { it.name == expr.callee }
                    if (funcDecl != null) {
                        val bindings = mutableMapOf<String, IrType>()
                        for (i in funcDecl.params.indices) {
                            val paramRef = funcDecl.params[i].type
                            if (paramRef is TypeRef.Named && paramRef.name in func.typeParams && i < argTypes.size) {
                                bindings[paramRef.name] = argTypes[i]
                            }
                        }
                        val retRef = (funcDecl.returnType as? TypeAnnotation.Explicit)?.ref
                        if (retRef is TypeRef.Named && retRef.name in bindings) return bindings[retRef.name]!!
                    }
                }
                if (expr.callee == "async") {
                    val thunk = argTypes.firstOrNull()
                    val result = (thunk as? IrType.Function)?.ret ?: IrType.Any
                    IrType.Task(result)
                } else if (func.isTask) {
                    IrType.Task(func.returnType)
                } else {
                    func.returnType
                }
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
            is Expr.SetLiteral -> {
                if (expr.elements.isEmpty()) {
                    errors.add("line ${expr.line}: cannot infer element type of an empty set literal")
                    null
                } else {
                    val elemType = resolveExpr(expr.elements[0]) ?: return null
                    for (i in 1 until expr.elements.size) {
                        val type = resolveExpr(expr.elements[i]) ?: return null
                        if (type != elemType) {
                            errors.add("line ${expr.line}: set elements must share a type, got $elemType and $type")
                            return null
                        }
                    }
                    IrType.Set(elemType)
                }
            }
            is Expr.Index -> {
                val targetType = resolveExpr(expr.target) ?: return null
                // User-defined index operator (`oper[]`) on a struct.
                if (targetType is IrType.Named) {
                    val mangled = table.lookupMethod(targetType.name, "index")
                    if (mangled != null) {
                        resolveExpr(expr.index) ?: return null
                        return table.lookupFunction(mangled)?.returnType ?: IrType.Any
                    }
                }
                // Map indexing: `map[key]` — key may be any type.
                if (targetType is IrType.Map) {
                    resolveExpr(expr.index) ?: return null
                    return targetType.value
                }
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
                // Enum variant: `Color.Red` → Named type carrying the enum identity
                // (enables exhaustiveness checking in `when`; runtime value is still a string).
                if (expr.target is Expr.Identifier) {
                    val variants = table.lookupEnum(expr.target.name)
                    if (variants != null) {
                        if (expr.name in variants) return IrType.Named(expr.target.name)
                        errors.add("line ${expr.line}: enum '${expr.target.name}' has no variant '${expr.name}'")
                        return null
                    }
                }
                // Error-set variant: `ErrSet.Variant` → string value "Variant"
                if (expr.target is Expr.Identifier) {
                    val errs = table.lookupFail(expr.target.name)
                    if (errs != null) {
                        if (expr.name in errs) return IrType.String
                        errors.add("line ${expr.line}: error-set '${expr.target.name}' has no variant '${expr.name}'")
                        return null
                    }
                }
                // Slot no-payload construction: SlotName.Variant (no parens)
                if (expr.target is Expr.Identifier) {
                    val slotVariants = table.lookupSlot(expr.target.name)
                    if (slotVariants != null) {
                        val variant = slotVariants.find { it.first == expr.name }
                        if (variant != null && variant.second.isEmpty()) {
                            return IrType.Named(expr.target.name)
                        }
                    }
                }
                val targetType = resolveExpr(expr.target) ?: return null
                when {
                    expr.name == "length" && (targetType is IrType.Array || targetType is IrType.Map || targetType is IrType.Set || targetType == IrType.String) -> IrType.Int
                    (expr.name == "isEmpty" || expr.name == "isNotEmpty") && (targetType is IrType.Array || targetType is IrType.Map || targetType is IrType.Set) -> IrType.Bool
                    targetType is IrType.Named -> {
                        val struct = table.lookupStruct(targetType.name)
                        val field = struct?.field(expr.name)
                        if (field != null) {
                            if (!canAccessMember(targetType.name, field.visibility)) {
                                reportInaccessible(expr.line, "field", targetType.name, expr.name, field.visibility)
                                null
                            } else {
                                field.type
                            }
                        } else {
                            // Check for a computed property (prop): zero-arg method `Type_name`.
                            val mangled = table.lookupMethod(targetType.name, expr.name)
                            if (mangled != null) {
                                val func = table.lookupFunction(mangled)
                                // Only treat as a prop if it has exactly 1 param (self).
                                if (func != null && func.params.size == 1) {
                                    if (!canAccessMember(targetType.name, func.visibility)) {
                                        reportInaccessible(expr.line, "property", targetType.name, expr.name, func.visibility)
                                        null
                                    } else {
                                        func.returnType
                                    }
                                }
                                else { errors.add("line ${expr.line}: no field '${expr.name}' on struct ${targetType.name}"); null }
                            } else {
                                errors.add("line ${expr.line}: no field '${expr.name}' on struct ${targetType.name}")
                                null
                            }
                        }
                    }
                    else -> {
                        errors.add("line ${expr.line}: no member '${expr.name}' on $targetType")
                        null
                    }
                }
            }
            is Expr.MethodCall -> {
                // Slot construction: SlotName.Variant(args) — check BEFORE resolving target
                if (expr.target is Expr.Identifier) {
                    val slotVariants = table.lookupSlot(expr.target.name)
                    if (slotVariants != null) {
                        val variant = slotVariants.find { it.first == expr.name }
                        if (variant != null) {
                            if (expr.args.size != variant.second.size) {
                                errors.add("line ${expr.line}: '${expr.name}' expects ${variant.second.size} payload args, got ${expr.args.size}")
                                return null
                            }
                            for (i in expr.args.indices) {
                                val at = resolveExpr(expr.args[i]) ?: return null
                                if (!isCompatible(variant.second[i], at)) {
                                    errors.add("line ${expr.line}: payload ${i+1} of '${expr.name}': expected ${variant.second[i]}, got $at")
                                }
                            }
                            return IrType.Named(expr.target.name)
                        }
                    }
                }
                // User-defined method on a struct: obj.method(args) -> Type_method(self, args)
                val targetType = resolveExpr(expr.target) ?: return null
                if (targetType is IrType.Named) {
                    val mangled = table.lookupMethod(targetType.name, expr.name)
                    if (mangled != null) {
                        val func = table.lookupFunction(mangled)!!
                        if (!canAccessMember(targetType.name, func.visibility)) {
                            reportInaccessible(expr.line, "method", targetType.name, expr.name, func.visibility)
                            return null
                        }
                        val declared = func.params.size - 1 // exclude `self`
                        if (expr.args.size != declared) {
                            errors.add("line ${expr.line}: method '${expr.name}' expects $declared args, got ${expr.args.size}")
                            return null
                        }
                        for (i in expr.args.indices) {
                            val argType = resolveExpr(expr.args[i]) ?: return null
                            val paramType = func.params[i + 1].second
                            if (!isCompatible(paramType, argType)) {
                                errors.add("line ${expr.line}: arg ${i + 1} of '${expr.name}': expected $paramType, got $argType")
                            }
                        }
                        return func.returnType
                    }
                }
                // Builtin string methods
                if (targetType == IrType.String) {
                    return resolveStringMethod(expr.name, expr.args, expr.line)
                }
                // Builtin array methods
                if (targetType is IrType.Array) {
                    return resolveArrayMethod(expr.name, expr.args, targetType, expr.line)
                }
                if (targetType is IrType.Set) {
                    return resolveSetMethod(expr.name, expr.args, expr.line)
                }
                if (targetType is IrType.Map) {
                    return resolveMapMethod(expr.name, expr.args, targetType, expr.line)
                }
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
            is Expr.TupleLit -> {
                val types = expr.elements.map { resolveExpr(it) ?: return null }
                IrType.Tuple(types)
            }
            is Expr.TupleAccess -> {
                val targetType = resolveExpr(expr.target) ?: return null
                if (targetType !is IrType.Tuple) {
                    errors.add("line ${expr.line}: cannot use '.${expr.index}' on $targetType (not a tuple)")
                    return null
                }
                if (expr.index !in targetType.elements.indices) {
                    errors.add("line ${expr.line}: tuple index ${expr.index} out of bounds (tuple has ${targetType.elements.size} elements)")
                    return null
                }
                targetType.elements[expr.index]
            }
            is Expr.CatchExpr -> {
                val t1 = resolveExpr(expr.expr) ?: return null
                resolveExpr(expr.fallback) ?: return null
                t1
            }
            is Expr.IfExpr -> {
                resolveExpr(expr.condition) ?: return null
                val t1 = resolveExpr(expr.thenExpr) ?: return null
                resolveExpr(expr.elseExpr) ?: return null
                t1
            }
            is Expr.NamedArg -> resolveExpr(expr.value)
            is Expr.MapLit -> {
                var keyType: IrType? = null
                var valType: IrType? = null
                for ((k, v) in expr.entries) {
                    keyType = resolveExpr(k) ?: return null
                    valType = resolveExpr(v) ?: return null
                }
                IrType.Map(keyType ?: IrType.Any, valType ?: IrType.Any)
            }
            is Expr.Alloc -> {
                val inner = resolveExpr(expr.value) ?: return null
                // alloc [a, b, c] → pointer to the element type (buffer), not pointer to array.
                val pointee = (inner as? IrType.Array)?.element ?: inner
                IrType.Pointer(pointee)
            }
            is Expr.Deref -> {
                val target = resolveExpr(expr.target) ?: return null
                (target as? IrType.Pointer)?.inner ?: IrType.Any
            }
            is Expr.Isolated -> resolveExpr(expr.value) ?: IrType.Any
            is Expr.Await -> {
                val t = resolveExpr(expr.value) ?: return null
                when (t) {
                    is IrType.Task -> t.result
                    is IrType.Function -> t.ret // legacy `await task { ... }`
                    else -> {
                        errors.add("line ${expr.line}: await requires a task, got $t")
                        null
                    }
                }
            }
            is Expr.Inject -> IrType.Named(expr.typeName)
            is Expr.Spread -> { resolveExpr(expr.array) ?: return null; IrType.Any }
            is Expr.Cast -> {
                resolveExpr(expr.expr) ?: return null
                IrType.resolve(expr.targetType)
            }
            is Expr.IsCheck -> {
                resolveExpr(expr.expr) ?: return null
                IrType.Bool
            }
            is Expr.NullCoalesce -> {
                val leftType = resolveExpr(expr.left) ?: return null
                resolveExpr(expr.right) ?: return null
                // Result type is the non-nullable version of left, or right's type
                if (leftType is IrType.Nullable) leftType.inner else leftType
            }
            is Expr.SafeMember -> {
                val targetType = resolveExpr(expr.target) ?: return null
                val inner = if (targetType is IrType.Nullable) targetType.inner else targetType
                if (expr.name == "length" && (inner is IrType.Array || inner == IrType.String)) {
                    IrType.Nullable(IrType.Int)
                } else if (inner is IrType.Named) {
                    val field = table.lookupStruct(inner.name)?.field(expr.name)
                    if (field != null && !canAccessMember(inner.name, field.visibility)) {
                        reportInaccessible(expr.line, "field", inner.name, expr.name, field.visibility)
                        null
                    } else {
                        IrType.Nullable(field?.type ?: IrType.Any)
                    }
                } else {
                    IrType.Nullable(IrType.Any)
                }
            }
            is Expr.Lambda -> {
                // Infer the implicit `it` parameter's type from context when available.
                val paramTypes = expr.params.map { p ->
                    if (p.name == "it" && p.type == TypeRef.Named("Any") && expectedItType != null) expectedItType!!
                    else IrType.resolve(p.type)
                }
                table.pushScope()
                for (i in expr.params.indices) {
                    table.defineVariable(VariableSymbol(expr.params[i].name, paramTypes[i]))
                }
                var retType: IrType = IrType.Unit
                for (s in expr.body) {
                    if (s is Stmt.Return && s.value != null) { retType = resolveExpr(s.value) ?: IrType.Unit; break }
                }
                resolveBody(expr.body, retType)
                table.popScope()
                IrType.Function(paramTypes, retType)
            }
        }
    }

    /**
     * Type-checks a builtin method call on a receiver of [receiverType].
     * Currently supports a small set of array methods (`add`, `isEmpty`, `isNotEmpty`).
     */
    /** Type-checks a builtin string method. */
    private fun resolveStringMethod(name: String, args: List<Expr>, line: Int): IrType? {
        return when (name) {
            "toUpperCase", "toLowerCase", "trim" -> {
                if (args.isNotEmpty()) { errors.add("line $line: '$name' expects 0 arguments"); return null }
                IrType.String
            }
            "contains", "startsWith", "endsWith" -> {
                if (args.size != 1) { errors.add("line $line: '$name' expects 1 argument"); return null }
                resolveExpr(args[0]) ?: return null
                IrType.Bool
            }
            "replace" -> {
                if (args.size != 2) { errors.add("line $line: 'replace' expects 2 arguments"); return null }
                resolveExpr(args[0]) ?: return null; resolveExpr(args[1]) ?: return null
                IrType.String
            }
            "split" -> {
                if (args.size != 1) { errors.add("line $line: 'split' expects 1 argument"); return null }
                resolveExpr(args[0]) ?: return null
                IrType.Array(IrType.String)
            }
            "indexOf" -> {
                if (args.size != 1) { errors.add("line $line: 'indexOf' expects 1 argument"); return null }
                resolveExpr(args[0]) ?: return null
                IrType.Int
            }
            else -> { errors.add("line $line: no method '$name' on String"); null }
        }
    }

    /** Type-checks a builtin array method. */
    private fun resolveArrayMethod(name: String, args: List<Expr>, arrType: IrType.Array, line: Int): IrType? {
        return when (name) {
            "add" -> {
                if (args.size != 1) { errors.add("line $line: 'add' expects 1 argument"); return null }
                resolveExpr(args[0]) ?: return null
                IrType.Unit
            }
            "insert" -> {
                if (args.size != 2) { errors.add("line $line: 'insert' expects 2 arguments"); return null }
                resolveExpr(args[0]) ?: return null; resolveExpr(args[1]) ?: return null
                IrType.Unit
            }
            "remove" -> {
                if (args.size != 1) { errors.add("line $line: 'remove' expects 1 argument"); return null }
                resolveExpr(args[0]) ?: return null
                IrType.Unit
            }
            "contains" -> {
                if (args.size != 1) { errors.add("line $line: 'contains' expects 1 argument"); return null }
                resolveExpr(args[0]) ?: return null
                IrType.Bool
            }
            "indexOf" -> {
                if (args.size != 1) { errors.add("line $line: 'indexOf' expects 1 argument"); return null }
                resolveExpr(args[0]) ?: return null
                IrType.Int
            }
            "isEmpty", "isNotEmpty" -> {
                if (args.isNotEmpty()) { errors.add("line $line: '$name' expects 0 arguments"); return null }
                IrType.Bool
            }
            else -> { errors.add("line $line: no method '$name' on array"); null }
        }
    }

    private fun resolveBuiltinMethod(receiverType: IrType, name: String, args: List<Expr>, line: Int): IrType? {
        if (receiverType is IrType.Array) return resolveArrayMethod(name, args, receiverType, line)
        if (receiverType is IrType.Set) return resolveSetMethod(name, args, line)
        if (receiverType is IrType.Map) return resolveMapMethod(name, args, receiverType, line)
        if (receiverType == IrType.String) return resolveStringMethod(name, args, line)
        // `Channel` methods: `send`/`close` → Unit, `receive` → the element (typed as Any).
        if (receiverType is IrType.Named && receiverType.name == "Channel") {
            return when (name) {
                "send", "close" -> IrType.Unit
                "receive" -> IrType.Any
                else -> { errors.add("line $line: no method '$name' on Channel"); null }
            }
        }
        errors.add("line $line: no method '$name' on $receiverType")
        return null
    }

    private fun resolveSetMethod(name: String, args: List<Expr>, line: Int): IrType? = when (name) {
        "add", "remove", "contains" -> {
            if (args.size != 1) { errors.add("line $line: '$name' expects 1 argument"); null }
            else { resolveExpr(args[0]) ?: return null; IrType.Bool }
        }
        "clear" -> {
            if (args.isNotEmpty()) { errors.add("line $line: 'clear' expects 0 arguments"); null }
            else IrType.Unit
        }
        "isEmpty", "isNotEmpty" -> {
            if (args.isNotEmpty()) { errors.add("line $line: '$name' expects 0 arguments"); null }
            else IrType.Bool
        }
        else -> { errors.add("line $line: no method '$name' on set"); null }
    }

    private fun resolveMapMethod(name: String, args: List<Expr>, map: IrType.Map, line: Int): IrType? = when (name) {
        "get" -> {
            if (args.size != 1) { errors.add("line $line: 'get' expects 1 argument"); null }
            else { resolveExpr(args[0]); map.value }
        }
        "put" -> {
            if (args.size != 2) { errors.add("line $line: 'put' expects 2 arguments"); null }
            else { resolveExpr(args[0]); resolveExpr(args[1]); IrType.Unit }
        }
        "containsKey" -> {
            if (args.size != 1) { errors.add("line $line: 'containsKey' expects 1 argument"); null }
            else { resolveExpr(args[0]); IrType.Bool }
        }
        "clear" -> {
            if (args.isNotEmpty()) { errors.add("line $line: 'clear' expects 0 arguments"); null }
            else IrType.Unit
        }
        "isEmpty", "isNotEmpty" -> {
            if (args.isNotEmpty()) { errors.add("line $line: '$name' expects 0 arguments"); null }
            else IrType.Bool
        }
        else -> { errors.add("line $line: no method '$name' on map"); null }
    }

    /** Maps an operator token to the impl method name used for operator overloading. */
    private fun operatorMethodName(op: TokenType): String? = when (op) {
        TokenType.PLUS -> "plus"
        TokenType.MINUS -> "minus"
        TokenType.STAR -> "times"
        TokenType.SLASH -> "div"
        TokenType.PERCENT -> "mod"
        TokenType.EQUAL_EQUAL -> "equals"
        else -> null
    }

    /** Extracts the variant name from a when pattern expression (Color.Red → "Red"). */
    private fun extractVariantName(pattern: Expr, table: SymbolTable): String? {
        return when (pattern) {
            is Expr.Member -> pattern.name
            is Expr.MethodCall -> pattern.name
            is Expr.StringLiteral -> pattern.value
            else -> null
        }
    }

    /** Checks if an initializer type is compatible with a declared type (nullable widening, Any from null). */
    private fun isCompatible(declared: IrType, actual: IrType): Boolean {
        if (declared == actual) return true
        // An enum value (Named, known enum) is usable wherever a String is expected.
        if (declared == IrType.String && actual is IrType.Named && table.lookupEnum(actual.name) != null) return true
        // Node upcast: a child node is compatible with its parent (walk the parent chain).
        if (declared is IrType.Named && actual is IrType.Named) {
            var t: String? = actual.name
            while (t != null) {
                if (t == declared.name) return true
                t = table.nodeParents[t]
            }
        }
        // null (Any) is compatible with any Nullable type
        if (actual == IrType.Any && declared is IrType.Nullable) return true
        // non-nullable is compatible with its nullable version
        if (declared is IrType.Nullable && declared.inner == actual) return true
        // Any is compatible with anything
        if (declared == IrType.Any || actual == IrType.Any) return true
        return false
    }

    /** If [t] is a nullable wrapper around a numeric type, return its inner type; else [t]. */
    private fun unwrapNullableNumeric(t: IrType): IrType =
        if (t is IrType.Nullable && t.inner in IrType.numericTypes) t.inner else t

    /** Promotes two numeric types to their common supertype (wider wins). */
    private fun promote(a: IrType, b: IrType): IrType? {
        if (a == b) return a
        if (a in IrType.floatTypes || b in IrType.floatTypes) {
            // Float promotion: Float < Real < Decimal
            if (a == IrType.Decimal || b == IrType.Decimal) return IrType.Decimal
            if (a == IrType.Real || b == IrType.Real) return IrType.Real
            return IrType.Float
        }
        // Integer promotion: Byte < Short < Int < Long < Cent
        val rank = mapOf(IrType.Byte to 0, IrType.UByte to 0, IrType.Short to 1, IrType.UShort to 1,
            IrType.Int to 2, IrType.UInt to 2, IrType.Long to 3, IrType.ULong to 3,
            IrType.Cent to 4, IrType.UCent to 4)
        val ra = rank[a] ?: return null
        val rb = rank[b] ?: return null
        return if (ra >= rb) a else b
    }

    private fun resolveBinaryType(op: TokenType, left: IrType, right: IrType, line: Int): IrType? {
        // Operator overloading on user types
        if (left is IrType.Named && left == right) {
            val methodName = operatorMethodName(op)
            if (methodName != null) {
                val mangled = table.lookupMethod(left.name, methodName)
                if (mangled != null) return table.lookupFunction(mangled)?.returnType
            }
            if (op == TokenType.BANG_EQUAL) {
                val eqMangled = table.lookupMethod(left.name, "equals")
                if (eqMangled != null) return IrType.Bool
            }
        }
        // Pointer arithmetic: Pointer(T) + Int → Pointer(T), Pointer(T) - Int → Pointer(T),
        // Pointer(T) - Pointer(T) → Int, Pointer(T) ==/!= Pointer(T) → Bool.
        if (left is IrType.Pointer) {
            return when {
                op == TokenType.MINUS && right is IrType.Pointer -> IrType.Int // pointer distance
                op == TokenType.PLUS || op == TokenType.MINUS ->
                    if (right in IrType.integerTypes) left else { errors.add("line $line: pointer arithmetic requires Int offset, got $right"); null }
                op == TokenType.EQUAL_EQUAL || op == TokenType.BANG_EQUAL ->
                    if (right is IrType.Pointer) IrType.Bool else { errors.add("line $line: pointer comparison requires Pointer, got $right"); null }
                else -> { errors.add("line $line: unsupported pointer operation '$op'"); null }
            }
        }
        if (left in IrType.integerTypes && right is IrType.Pointer && op == TokenType.PLUS) {
            return right // Int + Pointer → Pointer
        }
        // Unwrap nullable numeric operands for primitive operations so that
        // e.g. `Int? + Int` type-checks (the null-conditional operators rely on this).
        val left = unwrapNullableNumeric(left)
        val right = unwrapNullableNumeric(right)
        return when (op) {
            TokenType.PLUS -> {
                if (left == IrType.String || right == IrType.String) IrType.String
                else if (left in IrType.numericTypes && right in IrType.numericTypes) promote(left, right)
                else if (left == IrType.Any || right == IrType.Any) IrType.Any // erased generics
                else { errors.add("line $line: cannot apply '$op' to $left and $right"); null }
            }
            TokenType.STAR -> {
                if ((left == IrType.String && right == IrType.Int) ||
                    (left == IrType.Int && right == IrType.String)) IrType.String
                else if (left in IrType.numericTypes && right in IrType.numericTypes) promote(left, right)
                else if (left == IrType.Any || right == IrType.Any) IrType.Any
                else { errors.add("line $line: cannot apply '$op' to $left and $right"); null }
            }
            TokenType.MINUS, TokenType.SLASH, TokenType.PERCENT -> {
                if (left in IrType.numericTypes && right in IrType.numericTypes) promote(left, right)
                else if (left == IrType.Any || right == IrType.Any) IrType.Any
                else { errors.add("line $line: cannot apply '$op' to $left and $right"); null }
            }
            TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL -> {
                // Equality is allowed between identical types, against null (which
                // resolves to Any), or between a nullable type and its inner type.
                val nullCompare = left == IrType.Any || right == IrType.Any
                val nullableMatch = (left is IrType.Nullable && left.inner == right) ||
                    (right is IrType.Nullable && right.inner == left)
                if (left == right || nullCompare || nullableMatch) {
                    IrType.Bool
                } else {
                    errors.add("line $line: cannot compare $left and $right")
                    null
                }
            }
            TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL -> {
                // Any (e.g. an erased generic T) compares at runtime.
                val anyInvolved = left == IrType.Any || right == IrType.Any
                if (!anyInvolved && (left != right || (left !in IrType.Companion.numericTypes && left != IrType.Char))) {
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
            TokenType.AMP, TokenType.PIPE, TokenType.CARET, TokenType.SHIFT_LEFT, TokenType.SHIFT_RIGHT -> {
                if (left !in IrType.integerTypes || right !in IrType.integerTypes) {
                    errors.add("line $line: '$op' requires integer operands, got $left and $right")
                    null
                } else left
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
                if (!isCompatible(declaredType, initType)) {
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
