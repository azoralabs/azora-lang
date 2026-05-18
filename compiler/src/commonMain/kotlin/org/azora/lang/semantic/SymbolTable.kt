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

import org.azora.lang.ir.IrType

/**
 * A function signature registered during symbol collection.
 *
 * @property name the function name
 * @property params the parameter list as name-type pairs
 * @property returnType the resolved return type
 * @property isInline whether the function is marked `inline` for compile-time substitution
 */
data class FunctionSymbol(
    val name: String,
    val params: List<Pair<String, IrType>>,
    val returnType: IrType,
    val isInline: Boolean = false
)

/**
 * A local variable registered during type resolution.
 *
 * @property name the variable name
 * @property type the resolved type of the variable
 * @property mutable whether this variable can be reassigned (`true` for `var`, `false` for `fin`/`let`)
 */
data class VariableSymbol(
    val name: String,
    val type: IrType,
    val mutable: Boolean = true
)

/**
 * Symbol table built in two stages:
 * 1. [SymbolCollector] populates function signatures (global scope).
 * 2. [TypeResolver] uses it to look up functions and manages local scopes.
 *
 * Functions are stored in a flat global namespace. Variables use a stack of
 * scopes (one per function body, block, or zone) to support lexical scoping
 * with inner scopes shadowing outer ones.
 */
class SymbolTable {

    private val functions = mutableMapOf<String, FunctionSymbol>()
    private val scopes = ArrayDeque<MutableMap<String, VariableSymbol>>()

    // -- Functions (global) -------------------------------------------------

    /**
     * Registers a function signature in the global namespace.
     *
     * @param symbol the function symbol to register
     * @throws IllegalStateException if a function with the same name is already defined
     */
    fun defineFunction(symbol: FunctionSymbol) {
        if (functions.containsKey(symbol.name)) {
            error("Function '${symbol.name}' already defined")
        }
        functions[symbol.name] = symbol
    }

    /**
     * Looks up a function by name in the global namespace.
     *
     * @param name the function name
     * @return the [FunctionSymbol] if found, or `null` if undefined
     */
    fun lookupFunction(name: String): FunctionSymbol? = functions[name]

    // -- Local variable scopes ----------------------------------------------

    /**
     * Pushes a new empty scope onto the scope stack. Called when entering
     * a function body, block, or zone.
     */
    fun pushScope() { scopes.addLast(mutableMapOf()) }

    /**
     * Pops the top scope from the scope stack. Called when exiting
     * a function body, block, or zone.
     */
    fun popScope() { scopes.removeLast() }

    /**
     * Defines a variable in the current (innermost) scope.
     *
     * @param symbol the variable symbol to register
     */
    fun defineVariable(symbol: VariableSymbol) {
        scopes.last()[symbol.name] = symbol
    }

    /**
     * Copies all variables from the current (innermost) scope into the given map.
     * Used by friend zones to persist their shared scope between blocks.
     */
    fun exportCurrentScope(target: MutableMap<String, VariableSymbol>) {
        scopes.lastOrNull()?.let { target.putAll(it) }
    }

    /**
     * Looks up a variable by name in the current (innermost) scope only.
     * Does not search outer scopes.
     *
     * @param name the variable name
     * @return the [VariableSymbol] if found in the current scope, or `null`
     */
    fun lookupVariableInCurrentScope(name: String): VariableSymbol? {
        return scopes.lastOrNull()?.get(name)
    }

    /**
     * Looks up a variable by name, skipping [depth] scopes from the innermost.
     * `depth=1` (::) skips the current scope.
     * `depth=2` (::::) skips the current and next outer scope.
     *
     * @param name the variable name
     * @param depth how many scopes to skip (default 1)
     * @return the [VariableSymbol] if found, or `null`
     */
    fun lookupVariableInUpperScope(name: String, depth: Int = 1): VariableSymbol? {
        val startIndex = scopes.size - 1 - depth
        if (startIndex < 0) return null
        for (i in startIndex downTo 0) {
            scopes[i][name]?.let { return it }
        }
        return null
    }

    /**
     * Looks up a variable by name, searching from the innermost scope outward.
     *
     * @param name the variable name
     * @return the [VariableSymbol] if found in any enclosing scope, or `null` if undefined
     */
    fun lookupVariable(name: String): VariableSymbol? {
        for (i in scopes.indices.reversed()) {
            scopes[i][name]?.let { return it }
        }
        return null
    }
}
