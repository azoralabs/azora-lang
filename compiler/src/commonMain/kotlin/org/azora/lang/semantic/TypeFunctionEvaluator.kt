/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.azora.lang.semantic

import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TypeFunctionCall
import org.azora.lang.frontend.TypeFunctionCondition
import org.azora.lang.frontend.TypeFunctionDecl
import org.azora.lang.frontend.TypeFunctionExpr
import org.azora.lang.frontend.TypeFunctionStmt
import org.azora.lang.frontend.TypeRef

/** Evaluates compile-time `type` functions. */
internal object TypeFunctionEvaluator {
    private val ranks = mapOf(
        "Bool" to 0,
        "Byte" to 1,
        "UByte" to 2,
        "Short" to 3,
        "UShort" to 4,
        "Int" to 5,
        "UInt" to 6,
        "Long" to 7,
        "Size" to 7,
        "ULong" to 8,
        "USize" to 8,
        "Cent" to 9,
        "UCent" to 10,
        "Float" to 11,
        "Real" to 12,
        "Decimal" to 13,
        "String" to 14,
    )

    /**
     * Resolves every type-function call reachable from [type]. A substitution
     * value is a list so a variadic generic parameter can expand into multiple
     * type-function arguments.
     */
    fun resolve(
        type: TypeRef,
        declarations: List<TypeFunctionDecl>,
        substitutions: Map<String, List<TypeRef>> = emptyMap(),
        unresolvedParams: Set<String> = emptySet(),
    ): TypeRef = Resolver(declarations, substitutions, unresolvedParams).resolve(type)

    private class Resolver(
        declarations: List<TypeFunctionDecl>,
        private val substitutions: Map<String, List<TypeRef>>,
        private val unresolvedParams: Set<String>,
    ) {
        private val declarations = declarations.groupBy { it.name }
        private val stack = mutableListOf<String>()

        fun resolve(type: TypeRef): TypeRef = when (type) {
            is TypeRef.Named -> when {
                TypeFunctionCall.isCall(type) -> resolveCall(type)
                type.name in substitutions && type.args.isEmpty() -> substitutions.getValue(type.name).singleOrNull() ?: type
                else -> type.copy(args = type.args.map(::resolve))
            }
            is TypeRef.Array -> type.copy(element = resolve(type.element))
            is TypeRef.Map -> type.copy(key = resolve(type.key), value = resolve(type.value))
            is TypeRef.Set -> type.copy(element = resolve(type.element))
            is TypeRef.Function -> type.copy(
                params = type.params.map(::resolve),
                ret = resolve(type.ret),
                receivers = type.receivers.map(::resolve),
            )
            is TypeRef.Tuple -> type.copy(elements = type.elements.map(::resolve))
            is TypeRef.Nullable -> type.copy(inner = resolve(type.inner))
            is TypeRef.Failable -> type.copy(ok = resolve(type.ok))
            is TypeRef.Pointer -> type.copy(inner = resolve(type.inner))
            is TypeRef.Reference -> type.copy(inner = resolve(type.inner))
            is TypeRef.Const -> type
        }

        private fun resolveCall(call: TypeRef.Named): TypeRef {
            val name = TypeFunctionCall.name(call)
            val args = call.args.flatMap { argument ->
                if (argument is TypeRef.Named && argument.args.isEmpty() && argument.name in substitutions) {
                    substitutions.getValue(argument.name).map(::resolve)
                } else {
                    listOf(resolve(argument))
                }
            }
            if (args.any(::containsUnresolvedParam)) return TypeFunctionCall.create(name, args)
            return evaluateCall(name, args)
        }

        private fun containsUnresolvedParam(type: TypeRef): Boolean = when (type) {
            is TypeRef.Named -> type.name in unresolvedParams || type.args.any(::containsUnresolvedParam)
            is TypeRef.Array -> containsUnresolvedParam(type.element)
            is TypeRef.Map -> containsUnresolvedParam(type.key) || containsUnresolvedParam(type.value)
            is TypeRef.Set -> containsUnresolvedParam(type.element)
            is TypeRef.Function ->
                type.params.any(::containsUnresolvedParam) ||
                    type.receivers.any(::containsUnresolvedParam) ||
                    containsUnresolvedParam(type.ret)
            is TypeRef.Tuple -> type.elements.any(::containsUnresolvedParam)
            is TypeRef.Nullable -> containsUnresolvedParam(type.inner)
            is TypeRef.Failable -> containsUnresolvedParam(type.ok)
            is TypeRef.Pointer -> containsUnresolvedParam(type.inner)
            is TypeRef.Reference -> containsUnresolvedParam(type.inner)
            is TypeRef.Const -> false
        }

        private fun evaluateCall(name: String, args: List<TypeRef>): TypeRef {
            val overloads = declarations[name].orEmpty()
            val fixed = overloads.firstOrNull { it.variadicParam == null && it.params.size == args.size }
            val variadics = overloads.filter { it.variadicParam != null }.sortedByDescending { declaration ->
                declaration.params.indexOfFirst { it.variadic }
            }
            val matchingVariadic = variadics.firstOrNull { candidate ->
                val fixedCount = candidate.params.indexOfFirst { it.variadic }
                val variadicCount = args.size - fixedCount
                args.size >= fixedCount && variadicCount >= (candidate.minVariadicLength ?: 0)
            }
            val declaration = fixed ?: matchingVariadic ?: variadics.firstOrNull()
            if (declaration == null) {
                if (overloads.isEmpty()) error("Unknown type function '$name'")
                val expected = overloads.filter { it.variadicParam == null }.map { it.params.size }.distinct().sorted()
                error("Type function '$name' has no overload for ${args.size} argument(s); expected ${expected.joinToString(" or ")}")
            }
            if (declaration.name in stack) {
                error("Recursive type-function call detected: ${(stack + declaration.name).joinToString(" -> ")}")
            }

            val variadicIndex = declaration.params.indexOfFirst { it.variadic }
            val fixedCount = if (variadicIndex < 0) declaration.params.size else variadicIndex
            if (args.size < fixedCount) {
                error("Type function '$name' expects at least $fixedCount argument(s), got ${args.size}")
            }
            val variadicArgs = if (variadicIndex < 0) emptyList() else args.drop(variadicIndex)
            declaration.minVariadicLength?.let { minimum ->
                if (variadicArgs.size < minimum) {
                    error("Type function '$name' requires ${declaration.variadicParam}.length >= $minimum, got ${variadicArgs.size}")
                }
            }

            val values = mutableMapOf<String, TypeRef>()
            val packs = mutableMapOf<String, List<TypeRef>>()
            declaration.params.forEachIndexed { index, param ->
                if (param.variadic) packs[param.name] = args.drop(index)
                else values[param.name] = args[index]
            }
            stack.add(declaration.name)
            return try {
                execute(declaration.body, values, packs)
                    ?: error("Type function '$name' completed without returning a type")
            } finally {
                stack.removeAt(stack.lastIndex)
            }
        }

        private fun execute(
            statements: List<TypeFunctionStmt>,
            values: MutableMap<String, TypeRef>,
            packs: Map<String, List<TypeRef>>,
        ): TypeRef? {
            for (statement in statements) {
                when (statement) {
                    is TypeFunctionStmt.Binding -> values[statement.name] = evaluate(statement.value, values, packs)
                    is TypeFunctionStmt.Assignment -> {
                        if (statement.name !in values) error("Unknown type binding '${statement.name}'")
                        values[statement.name] = evaluate(statement.value, values, packs)
                    }
                    is TypeFunctionStmt.ForEach -> {
                        val elements = packs[statement.packName]
                            ?: error("'${statement.packName}' is not a variadic type parameter")
                        if (statement.startIndex !in 0..elements.size) {
                            error("Type-pack slice starts at ${statement.startIndex}, but '${statement.packName}' has ${elements.size} element(s)")
                        }
                        for (element in elements.drop(statement.startIndex)) {
                            values[statement.name] = element
                            execute(statement.body, values, packs)?.let { return it }
                        }
                        values.remove(statement.name)
                    }
                    is TypeFunctionStmt.Return -> return evaluate(statement.value, values, packs)
                }
            }
            return null
        }

        private fun evaluate(
            expression: TypeFunctionExpr,
            values: Map<String, TypeRef>,
            packs: Map<String, List<TypeRef>>,
        ): TypeRef = when (expression) {
            is TypeFunctionExpr.Reference -> values[expression.name]
                ?: packs[expression.name]?.singleOrNull()
                ?: TypeRef.Named(expression.name)
            is TypeFunctionExpr.PackElement -> packs[expression.packName]?.getOrNull(expression.index)
                ?: error("Type pack '${expression.packName}' has no element ${expression.index}")
            is TypeFunctionExpr.Call -> {
                val args = expression.args.flatMap { argument ->
                    if (argument is TypeFunctionExpr.Reference && argument.name in packs) {
                        packs.getValue(argument.name)
                    } else {
                        listOf(evaluate(argument, values, packs))
                    }
                }
                evaluateCall(expression.name, args)
            }
            is TypeFunctionExpr.Conditional -> if (evaluateCondition(expression.condition, values, packs)) {
                evaluate(expression.thenValue, values, packs)
            } else {
                evaluate(expression.elseValue, values, packs)
            }
        }

        private fun evaluateCondition(
            condition: TypeFunctionCondition,
            values: Map<String, TypeRef>,
            packs: Map<String, List<TypeRef>>,
        ): Boolean {
            val left = evaluate(condition.left, values, packs)
            val right = evaluate(condition.right, values, packs)
            if (!condition.compareRank) {
                return when (condition.operator) {
                    TokenType.EQUAL_EQUAL -> left == right
                    TokenType.BANG_EQUAL -> left != right
                    else -> error("Type values support only == and != unless both operands use '.rank'")
                }
            }
            val leftRank = rankOf(left)
            val rightRank = rankOf(right)
            return when (condition.operator) {
                TokenType.GREATER -> leftRank > rightRank
                TokenType.GREATER_EQUAL -> leftRank >= rightRank
                TokenType.LESS -> leftRank < rightRank
                TokenType.LESS_EQUAL -> leftRank <= rightRank
                TokenType.EQUAL_EQUAL -> leftRank == rightRank
                TokenType.BANG_EQUAL -> leftRank != rightRank
                else -> error("Unsupported ranked type comparison '${condition.operator}'")
            }
        }

        private fun rankOf(type: TypeRef): Int {
            val name = (type as? TypeRef.Named)?.takeIf { it.args.isEmpty() }?.name
                ?: error("Type '$type' does not have a numeric promotion rank")
            return ranks[name] ?: error("Type '$name' does not have a numeric promotion rank")
        }
    }
}
