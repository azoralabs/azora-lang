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

import org.azora.lang.frontend.CastKind
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.MemberCallStyle
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
 * This pass runs on the CTCE-stabilized AST, so all compile-time constructs
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
                // `@UncheckedCast` opts the whole impl out of body type-checking.
                if (item.annotations.any { it.name == "UncheckedCast" }) continue
                for (method in item.methods) {
                    val mangled = "${item.typeName}_${method.name}"
                    val func = table.lookupFunction(mangled) ?: continue
                    table.pushScope()
                    for ((name, type) in func.params) {
                        val mutable = name != "self" || method.receiverModifier != "ref"
                        table.defineVariable(VariableSymbol(name, type, mutable = mutable))
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
        val savedFailSets = declaredFailSets
        declaredFailSets = (func.returnType as? TypeAnnotation.Explicit)
            ?.ref?.let { (it as? TypeRef.Failable)?.errSets }
        val savedUnsafe = unsafeContext
        val savedReceiver = currentReceiverType
        val savedFuncTypeParams = currentFuncTypeParams
        unsafeContext = func.isUnsafe
        currentReceiverType = null
        currentFuncTypeParams = func.typeParams.toSet()
        resolveBody(func.body, symbol.returnType)
        currentReceiverType = savedReceiver
        currentFuncTypeParams = savedFuncTypeParams
        unsafeContext = savedUnsafe
        declaredFailSets = savedFailSets

        table.popScope()
    }

    /** Error sets declared by the function currently being resolved. */
    private var declaredFailSets: List<String>? = null

    /** Type parameters of the function currently being resolved (erased to `Any` in types). */
    private var currentFuncTypeParams: Set<String> = emptySet()

    private fun canAccessMember(ownerType: String, visibility: Visibility): Boolean = when (visibility) {
        Visibility.EXPOSE -> true
        Visibility.SHIELD -> true // shielded fields are publicly readable (readonly-style)
        // `intern` bounds access to the declaring library; within one compilation
        // unit that is always satisfied, so it reads as accessible here.
        Visibility.INTERN -> true
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
            Visibility.SHIELD -> "shielded"
            Visibility.INTERN -> "internal"
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

    /**
     * When non-null, return-value types inside the body being resolved are appended here
     * (used to infer a lambda's return type from its body, after locals are in scope).
     * When null, `return` statements are validated against the enclosing function's declared type.
     */
    private var lambdaReturnTypes: MutableList<IrType>? = null

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
                    val capturing = lambdaReturnTypes
                    if (capturing != null) {
                        // Inferring a lambda's return type — record it, skip declared-type checking.
                        capturing.add(valueType)
                    } else if (!isCompatible(returnType, valueType)) {
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
                // Pointer index-assign: `ptr[i] = value` (C++-style *(ptr+i) = value).
                if (targetType is IrType.Pointer) {
                    resolveExpr(stmt.index) ?: return
                    resolveExpr(stmt.value) ?: return
                    return
                }
                // Primitive set index-assign: `s[i] = value` (list-backed, by position).
                if (targetType is IrType.Set) {
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
                val resolvedTarget = resolveExpr(stmt.target) ?: return
                // Auto-deref: assigning through a pointer writes through it (`p.v = x` == `(*p).v = x`).
                val targetType = if (resolvedTarget is IrType.Pointer) resolvedTarget.inner else resolvedTarget
                val valueType = resolveExpr(stmt.value) ?: return
                if (targetType is IrType.Named) {
                    val field = table.lookupStruct(targetType.name)?.field(stmt.name)
                    if (field == null) {
                        errors.add("line ${stmt.line}: no field '${stmt.name}' on struct ${targetType.name}")
                        return
                    }
                    val selfTarget = stmt.target as? Expr.Identifier
                    if (selfTarget?.name == "self" && table.lookupVariable("self")?.mutable == false) {
                        errors.add("line ${stmt.line}: cannot mutate '${stmt.name}' through ref self")
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
                    if (!isCompatible(field.type, valueType)) {
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
                if (thrownSet != null && declaredFailSets != null && thrownSet !in declaredFailSets.orEmpty()) {
                    val declared = declaredFailSets.orEmpty().let {
                        if (it.size == 1) "!${it.single()}" else "![${it.joinToString(", ")}]"
                    }
                    errors.add("line ${stmt.line}: function declares '$declared' but throws error from '$thrownSet'")
                }
            }
            is Stmt.Panic -> { resolveExpr(stmt.message) }
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
                    // Implicit self: bare field name in an impl method → self.field
                    val receiverField = currentReceiverType?.let { table.lookupStruct(it)?.field(expr.name) }
                    if (receiverField != null) receiverField.type
                    else {
                        errors.add("line ${expr.line}: undefined variable '${expr.name}'")
                        null
                    }
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
                if (expr.callee == "__reflect") {
                    errors.add("line ${expr.line}: reflect is compile-time-only and must be followed by .hasDeco<D> or .decoMeta<D>")
                    return null
                }
                if (expr.callee == "__hasDeco" || expr.callee == "__decoMeta") {
                    errors.add("line ${expr.line}: '${if (expr.callee == "__hasDeco") "hasDeco" else "decoMeta"}' is a compile-time-only property and must be used inside inline code")
                    return null
                }
                // Struct construction: `Name(args)` where Name is a pack.
                val struct = table.lookupStruct(expr.callee)
                if (struct != null) {
                    if (expr.callee in table.abstractNodes) {
                        errors.add("line ${expr.line}: abstract node '${expr.callee}' cannot be instantiated directly; use a leaf subclass")
                        return null
                    }
                    if (expr.args.size > struct.fields.size) {
                        errors.add("line ${expr.line}: '${expr.callee}' has ${struct.fields.size} fields, got ${expr.args.size} arguments")
                        return null
                    }
                    // Handle named arguments — reorder to field order
                    val effectiveArgs = if (expr.args.isNotEmpty() && expr.args[0] is Expr.NamedArg) {
                        if (!expr.args.all { it is Expr.NamedArg }) {
                            errors.add("line ${expr.line}: cannot mix named and positional arguments")
                            return null
                        }
                        val namedMap = expr.args.associate { (it as Expr.NamedArg).name to (it as Expr.NamedArg).value }
                        struct.fields.map { f -> namedMap[f.name] ?: f.default
                            ?: run { errors.add("line ${expr.line}: missing field '${f.name}' in '${expr.callee}' (no default)"); return null }
                        }
                    } else {
                        // Positional — pad omitted trailing fields with their defaults (`Pack<T>()`).
                        val padded = expr.args.toMutableList()
                        for (i in expr.args.size until struct.fields.size) {
                            val d = struct.fields[i].default
                                ?: run { errors.add("line ${expr.line}: missing field '${struct.fields[i].name}' in '${expr.callee}' (no default)"); return null }
                            padded.add(d)
                        }
                        padded
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
                // `std::convert::toString(x)` is a compiler builtin (special-cased in
                // CTCE and every backend); it stringifies any value.
                if (expr.callee == "std__convert__toString") {
                    expr.args.forEach { resolveExpr(it) ?: return null }
                    return IrType.String
                }
                val func = table.lookupFunction(expr.callee)
                if (func == null) {
                    // Maybe a lambda stored in a variable.
                    val v = table.lookupVariable(expr.callee)
                    if (v != null && v.type is IrType.Function) {
                        val fn = v.type
                        // A variadic lambda (`<T…>{ … }`) packs all args into its single `it` array.
                        if (fn.variadic) {
                            for (arg in expr.args) { resolveExpr(arg) ?: return null }
                            return fn.ret
                        }
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
                    if (!isGeneric) {
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
                        val bindings = mutableMapOf<String, List<TypeRef>>()
                        for ((index, typeParam) in func.typeParams.withIndex()) {
                            expr.typeArgs.getOrNull(index)?.let { bindings[typeParam] = listOf(it) }
                        }
                        for (i in funcDecl.params.indices) {
                            val paramRef = funcDecl.params[i].type
                            if (paramRef is TypeRef.Named && paramRef.name in func.typeParams && i < argTypes.size) {
                                bindings.putIfAbsent(paramRef.name, listOf(typeRefOf(argTypes[i])))
                            } else if (funcDecl.params[i].variadic && paramRef is TypeRef.Array) {
                                val element = paramRef.element as? TypeRef.Named
                                if (element != null && element.name in func.typeParams) {
                                    val variadicArgs = argTypes.drop(i)
                                    if (funcDecl.variadicParam == element.name) {
                                        // `func<...T>` preserves each argument type as a heterogeneous pack.
                                        bindings[element.name] = variadicArgs.map(::typeRefOf)
                                    } else if (variadicArgs.isNotEmpty()) {
                                        // `func<T>(...values: T)` is homogeneous: infer one T and
                                        // reject mixed arguments instead of leaving T unresolved.
                                        val explicit = bindings[element.name]
                                            ?.singleOrNull()
                                            ?.let { IrType.resolve(it) }
                                        val inferred = explicit ?: variadicArgs.first()
                                        val mismatch = variadicArgs.firstOrNull { !isCompatible(inferred, it) }
                                        if (mismatch != null) {
                                            errors.add(
                                                "line ${expr.line}: variadic arguments for '${expr.callee}' " +
                                                    "must share a type, got $inferred and $mismatch",
                                            )
                                            return null
                                        }
                                        bindings.putIfAbsent(element.name, listOf(typeRefOf(inferred)))
                                    }
                                }
                            }
                        }
                        val retRef = func.returnTypeRef
                        if (retRef != null) {
                            try {
                                val resolved = TypeFunctionEvaluator.resolve(
                                    retRef,
                                    program?.typeFunctions.orEmpty(),
                                    substitutions = bindings,
                                )
                                if (resolved !is TypeRef.Named || !org.azora.lang.frontend.TypeFunctionCall.isCall(resolved)) {
                                    return IrType.resolve(resolved)
                                }
                            } catch (error: IllegalStateException) {
                                errors.add("line ${expr.line}: ${error.message}")
                                return null
                            }
                        }
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
                    // Empty array literal `arr()` — element type unknown; defaults to Any (erased).
                    IrType.Array(IrType.Any)
                } else {
                    val elemType = resolveExpr(expr.elements[0]) ?: return null
                    for (i in 1 until expr.elements.size) {
                        val t = resolveExpr(expr.elements[i]) ?: return null
                        if (t != elemType) {
                            errors.add("line ${expr.line}: array elements must share a type, got $elemType and $t")
                            return null
                        }
                    }
                    // A non-empty literal carries its compile-time element count.
                    IrType.Array(elemType, expr.elements.size.toLong())
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
                // Pointer indexing: `ptr[i]` → the i-th element (C++-style *(ptr+i)).
                if (targetType is IrType.Pointer) {
                    resolveExpr(expr.index) ?: return null
                    return targetType.inner
                }
                // String indexing: `s[i]` → the i-th character.
                if (targetType == IrType.String) {
                    val idxType = resolveExpr(expr.index) ?: return null
                    if (idxType != IrType.Int) {
                        errors.add("line ${expr.line}: string index must be Int, got $idxType")
                        return null
                    }
                    return IrType.Char
                }
                // Named types (packs like Set/Map without injected oper[], or any struct):
                // allow indexing with any key type, returning Any (the runtime value may be indexable).
                if (targetType is IrType.Named) {
                    resolveExpr(expr.index) ?: return null
                    return IrType.Any
                }
                val indexType = resolveExpr(expr.index) ?: return null
                if (indexType != IrType.Int) {
                    errors.add("line ${expr.line}: array index must be Int, got $indexType")
                    return null
                }
                // Primitive sets are list-backed, so `s[i]` indexes by position (like an array).
                if (targetType !is IrType.Array && targetType !is IrType.Set) {
                    errors.add("line ${expr.line}: cannot index into $targetType (not an array)")
                    return null
                }
                if (targetType is IrType.Array) targetType.element else (targetType as IrType.Set).element
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
                val resolvedTarget = resolveExpr(expr.target) ?: return null
                // Auto-deref: member access on a pointer reads through it (`p.v` == `(*p).v`).
                val targetType = if (resolvedTarget is IrType.Pointer) resolvedTarget.inner else resolvedTarget
                when {
                    expr.name in setOf("length", "size") && (targetType is IrType.Array || targetType is IrType.Map || targetType is IrType.Set || targetType == IrType.String) -> IrType.Int
                    expr.name == "data" && targetType is IrType.Array -> IrType.Pointer(targetType.element)
                    (expr.name == "isEmpty" || expr.name == "isNotEmpty") && (targetType is IrType.Array || targetType is IrType.Map || targetType is IrType.Set) -> IrType.Bool
                    targetType is IrType.Named -> {
                        // Spec-typed value: a property/requirement declared by the spec
                        // (e.g. `map.size` where `map: Map<K,V>` — a spec) resolves to
                        // the spec's declared prop type and dispatches to the impl.
                        val specProp = table.lookupSpec(targetType.name)?.propTypes?.get(expr.name)
                        if (specProp != null) return specProp
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
                            // Check for a computed property/callback member.
                            val mangled = table.lookupMethod(targetType.name, expr.name)
                            if (mangled != null) {
                                val func = table.lookupFunction(mangled)
                                if (func != null && func.params.size == 1 && func.memberCallStyle != MemberCallStyle.METHOD) {
                                    if (!canAccessMember(targetType.name, func.visibility)) {
                                        reportInaccessible(expr.line, "property", targetType.name, expr.name, func.visibility)
                                        null
                                    } else {
                                        func.returnType
                                    }
                                }
                                else {
                                    errors.add("line ${expr.line}: member '${expr.name}' on ${targetType.name} requires a method call")
                                    null
                                }
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
                // `#expr` (oper#) — hash; returns ULong regardless of operand type
                // (the operand may be a generic K erased to Any, whose concrete type
                // supplies oper# at runtime).
                if (expr.name == "oper#") {
                    resolveExpr(expr.target)
                    return IrType.ULong
                }
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
                        if (func.memberCallStyle == MemberCallStyle.PROPERTY) {
                            errors.add("line ${expr.line}: property '${expr.name}' must be accessed without parentheses")
                            return null
                        }
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
                    // Spec-typed value: dispatch to a method declared by the spec
                    // (e.g. `list.get(0)` where `list: List<T>`). The concrete impl
                    // is selected at runtime; here we type-check against the spec.
                    val specMethod = table.lookupSpec(targetType.name)?.methodSigs?.get(expr.name)
                    if (specMethod != null) {
                        if (specMethod.isProperty) {
                            errors.add("line ${expr.line}: property '${expr.name}' must be accessed without parentheses")
                            return null
                        }
                        if (expr.args.size != specMethod.paramTypes.size) {
                            errors.add("line ${expr.line}: method '${expr.name}' expects ${specMethod.paramTypes.size} args, got ${expr.args.size}")
                            return null
                        }
                        for (i in expr.args.indices) {
                            val argType = resolveExpr(expr.args[i]) ?: return null
                            if (!isCompatible(specMethod.paramTypes[i], argType)) {
                                errors.add("line ${expr.line}: arg ${i + 1} of '${expr.name}': expected ${specMethod.paramTypes[i]}, got $argType")
                            }
                        }
                        return specMethod.returnType
                    }
                }
                // Universal infix (`a to b` → `to(a, b)`): a generic `infx` that
                // applies to any receiver. Checked after real methods so those win.
                val infixFn = table.lookupUniversalInfix(expr.name)?.let { table.lookupFunction(it) }
                if (infixFn != null) {
                    val declared = infixFn.params.size - 1 // exclude the receiver `self`
                    if (expr.args.size != declared) {
                        errors.add("line ${expr.line}: infix '${expr.name}' expects $declared operand(s), got ${expr.args.size}")
                        return null
                    }
                    for (i in expr.args.indices) {
                        val argType = resolveExpr(expr.args[i]) ?: return null
                        val paramType = infixFn.params[i + 1].second
                        if (!isCompatible(paramType, argType)) {
                            errors.add("line ${expr.line}: operand ${i + 1} of '${expr.name}': expected $paramType, got $argType")
                        }
                    }
                    return infixFn.returnType
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
            is Expr.VariantLit -> {
                val types = expr.elements.map { resolveExpr(it) ?: return null }
                IrType.Variant(types)
            }
            is Expr.TupleAccess -> {
                val targetType = resolveExpr(expr.target) ?: return null
                when (targetType) {
                    is IrType.Tuple -> {
                        if (expr.index !in targetType.elements.indices) {
                            errors.add("line ${expr.line}: tuple index ${expr.index} out of bounds (tuple has ${targetType.elements.size} elements)")
                            return null
                        }
                        targetType.elements[expr.index]
                    }
                    is IrType.Named -> {
                        // Nominal tuple pack (`__Tuple_<types>`): `.0`/`.1` access a
                        // numeric-named field, permitted via `@EnforceNumFields`.
                        val field = table.lookupStruct(targetType.name)?.field(expr.index.toString())
                        if (field != null) {
                            field.type
                        } else {
                            errors.add("line ${expr.line}: cannot use '.${expr.index}' on $targetType (no such field)")
                            null
                        }
                    }
                    else -> {
                        errors.add("line ${expr.line}: cannot use '.${expr.index}' on $targetType (not a tuple)")
                        null
                    }
                }
            }
            is Expr.CatchExpr -> {
                val t1 = resolveExpr(expr.expr) ?: return null
                resolveExpr(expr.fallback) ?: return null
                t1
            }
            is Expr.TryPropagate -> resolveExpr(expr.expr)
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
            is Expr.AllocBuffer -> {
                resolveExpr(expr.count) ?: return null
                val elem = if (IrType.isPrimitiveName(expr.typeName)) IrType.fromName(expr.typeName) else IrType.Any
                IrType.Pointer(elem)
            }
            is Expr.Deref -> {
                val target = resolveExpr(expr.target) ?: return null
                when (target) {
                    is IrType.Pointer -> target.inner
                    is IrType.Named -> {
                        val mangled = table.lookupMethod(target.name, "deref")
                        table.lookupFunction(mangled ?: "")?.returnType ?: IrType.Any
                    }
                    else -> IrType.Any
                }
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
                val target = resolveDeclaredType(expr.targetType)
                // A dynamic cast (`x as? T`) may fail, so its result is `T?`.
                if (expr.kind == CastKind.DYNAMIC && target !is IrType.Nullable) IrType.Nullable(target) else target
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
                if (expr.name in setOf("length", "size") && (inner is IrType.Array || inner == IrType.String)) {
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
                    else resolveDeclaredType(p.type)
                }
                table.pushScope()
                for (i in expr.params.indices) {
                    table.defineVariable(VariableSymbol(expr.params[i].name, paramTypes[i]))
                }
                // Resolve the body with locals in scope, capturing return-value types to infer retType.
                val captured = mutableListOf<IrType>()
                val savedReturns = lambdaReturnTypes
                lambdaReturnTypes = captured
                resolveBody(expr.body, IrType.Unit)
                lambdaReturnTypes = savedReturns
                table.popScope()
                val retType = captured.firstOrNull() ?: IrType.Unit
                IrType.Function(paramTypes, retType, variadic = expr.variadic)
            }
            // `a[start:stop:step]` → the target's `slice` method return type (or Any).
            is Expr.Slice -> {
                resolveExpr(expr.target)
                expr.start?.let { resolveExpr(it) }
                expr.stop?.let { resolveExpr(it) }
                expr.step?.let { resolveExpr(it) }
                IrType.Any
            }
            // Macros are expanded before type resolution; a MetaInvoke here is a bug.
            is Expr.MetaInvoke -> error("MetaInvoke reached TypeResolver at line ${expr.line}")
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
            "fill" -> {
                // `arr().fill<T>(count)` — pre-allocates `count` slots; returns the array.
                if (args.size != 1) { errors.add("line $line: 'fill' expects 1 argument"); return null }
                resolveExpr(args[0]) ?: return null
                arrType
            }
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
        // An unsized array slot (`[T]`) accepts any sized array of the same element
        // (`Array<T, N>`); a sized slot still requires an exact-size match (handled
        // by the `==` check above).
        if (declared is IrType.Array && actual is IrType.Array &&
            isCompatible(declared.element, actual.element) &&
            (declared.size == null || declared.size == actual.size)
        ) return true
        // Primitive literals bridge to std.container collection pack names.
        val setNames = setOf("Set", "MutableSet")
        val mapNames = setOf("Map", "MutableMap")
        val listNames = setOf("List", "MutableList")
        if (declared is IrType.Set && actual is IrType.Named && actual.name in setNames) return true
        if (declared is IrType.Named && declared.name in setNames && actual is IrType.Set) return true
        if (declared is IrType.Map && actual is IrType.Named && actual.name in mapNames) return true
        if (declared is IrType.Named && declared.name in mapNames && actual is IrType.Map) return true
        if (declared is IrType.Array && actual is IrType.Named && actual.name in listNames) return true
        if (declared is IrType.Named && declared.name in listNames && actual is IrType.Array) return true
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
        // Spec conformance: a pack that implements a spec is usable wherever that
        // spec type is expected (e.g. returning `ArrayList<T>` for `List<T>`, just
        // as a class implementing an interface is returned as the interface).
        if (declared is IrType.Named && actual is IrType.Named &&
            table.lookupSpec(declared.name) != null &&
            table.conformsTo(actual.name, declared.name)
        ) return true
        // null (Any) is compatible with any Nullable type
        if (actual == IrType.Any && declared is IrType.Nullable) return true
        // non-nullable is compatible with its nullable version
        if (declared is IrType.Nullable && declared.inner == actual) return true
        // Any is compatible with anything
        if (declared == IrType.Any || actual == IrType.Any) return true
        // A value of type T is assignable to a `Var<…>` (Variant) when T is one of its alternatives.
        if (declared is IrType.Variant && actual in declared.elements) return true
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

    /** Converts an inferred IR type back to the source type form used by type functions. */
    private fun typeRefOf(type: IrType): TypeRef = when (type) {
        IrType.Int -> TypeRef.Named("Int")
        IrType.UInt -> TypeRef.Named("UInt")
        IrType.Real -> TypeRef.Named("Real")
        IrType.String -> TypeRef.Named("String")
        IrType.Bool -> TypeRef.Named("Bool")
        IrType.Unit -> TypeRef.Named("Unit")
        IrType.Char -> TypeRef.Named("Char")
        IrType.Byte -> TypeRef.Named("Byte")
        IrType.UByte -> TypeRef.Named("UByte")
        IrType.Short -> TypeRef.Named("Short")
        IrType.UShort -> TypeRef.Named("UShort")
        IrType.Long -> TypeRef.Named("Long")
        IrType.ULong -> TypeRef.Named("ULong")
        IrType.Cent -> TypeRef.Named("Cent")
        IrType.UCent -> TypeRef.Named("UCent")
        IrType.Float -> TypeRef.Named("Float")
        IrType.Decimal -> TypeRef.Named("Decimal")
        IrType.Any -> TypeRef.Named("Any")
        is IrType.Array -> TypeRef.Array(typeRefOf(type.element))
        is IrType.Map -> TypeRef.Map(typeRefOf(type.key), typeRefOf(type.value))
        is IrType.Set -> TypeRef.Set(typeRefOf(type.element))
        is IrType.Function -> TypeRef.Function(type.params.map(::typeRefOf), typeRefOf(type.ret))
        is IrType.Task -> TypeRef.Named("Task", listOf(typeRefOf(type.result)))
        is IrType.Tuple -> TypeRef.Tuple(type.elements.map(::typeRefOf))
        is IrType.Variant -> TypeRef.Named("Var", type.elements.map(::typeRefOf))
        is IrType.Nullable -> TypeRef.Nullable(typeRefOf(type.inner))
        is IrType.Pointer -> TypeRef.Pointer(typeRefOf(type.inner))
        is IrType.Named -> TypeRef.Named(type.name)
    }

    private fun resolveBinaryType(op: TokenType, left: IrType, right: IrType, line: Int): IrType? {
        // Operator overloading on user types.
        if (left is IrType.Named) {
            // `impl oper<OP> [by <Spec>] for Type` overloads — method named `oper<OP>`
            // (e.g. `oper+`), resolved regardless of the operand type (the `by`
            // clause may declare a different operand, e.g. Map + MapEntry).
            operOverloadName(op)?.let { operName ->
                val mangled = table.lookupMethod(left.name, operName)
                if (mangled != null) return table.lookupFunction(mangled)?.returnType
            }
            // Legacy same-type named-method overloads (e.g. `func plus(ref self, …)`).
            if (left == right) {
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
        }
        // Pointer arithmetic: Pointer(T) + Int -> Pointer(T), Pointer(T) - Int -> Pointer(T),
        // Pointer(T) - Pointer(T) -> Int, Pointer(T) ==/!= Pointer(T)|null -> Bool.
        if (left is IrType.Pointer) {
            return when {
                op == TokenType.MINUS && right is IrType.Pointer -> IrType.Int // pointer distance
                op == TokenType.PLUS || op == TokenType.MINUS ->
                    if (right in IrType.integerTypes) left else { errors.add("line $line: pointer arithmetic requires Int offset, got $right"); null }
                op == TokenType.EQUAL_EQUAL || op == TokenType.BANG_EQUAL ->
                    if (right is IrType.Pointer || right == IrType.Any) IrType.Bool else { errors.add("line $line: pointer comparison requires Pointer or null, got $right"); null }
                else -> { errors.add("line $line: unsupported pointer operation '$op'"); null }
            }
        }
        if (left == IrType.Any && right is IrType.Pointer && (op == TokenType.EQUAL_EQUAL || op == TokenType.BANG_EQUAL)) {
            return IrType.Bool
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
                // An erased generic (e.g. `V?` from `map.get(k)`) is `Any` or
                // `Any?`; either compares against anything at runtime.
                val leftBare = if (left is IrType.Nullable) left.inner else left
                val rightBare = if (right is IrType.Nullable) right.inner else right
                val nullCompare = leftBare == IrType.Any || rightBare == IrType.Any
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
            // Type params (e.g. `T`) erase to `Any` — those of the enclosing function
            // and, inside an impl method, those of the receiver struct.
            val tpSet = currentFuncTypeParams +
                (currentReceiverType?.let { table.lookupStruct(it)?.typeParams?.toSet() } ?: emptySet())
            resolveDeclaredType(ref, tpSet)
        } catch (e: Exception) {
            errors.add("line $line: ${e.message}")
            null
        }
    }

    private fun resolveDeclaredType(ref: TypeRef, typeParams: Set<String> = currentFuncTypeParams): IrType =
        IrType.resolve(
            TypeFunctionEvaluator.resolve(
                ref,
                program?.typeFunctions.orEmpty(),
                unresolvedParams = typeParams,
            ),
            typeParams,
        )
}
