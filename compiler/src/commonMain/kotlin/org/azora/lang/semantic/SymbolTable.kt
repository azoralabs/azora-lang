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

import org.azora.lang.frontend.Visibility
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
    val isInline: Boolean = false,
    val typeParams: List<String> = emptyList(),
    val paramNames: List<String> = emptyList(),
    val defaults: Map<Int, org.azora.lang.frontend.Expr> = emptyMap(),
    val isVariadic: Boolean = false,
    val isTask: Boolean = false,
    val isUnsafe: Boolean = false,
    val visibility: Visibility = Visibility.EXPOSE,
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
    val mutable: Boolean = true,
    val visibility: Visibility = Visibility.EXPOSE,
)

/**
 * A field of a `pack` (struct) type.
 *
 * @property name the field name
 * @property type the resolved field type
 * @property mutable whether the field is `var` (mutable)
 */
data class StructField(
    val name: String,
    val type: IrType,
    val mutable: Boolean,
    val visibility: Visibility = Visibility.EXPOSE,
)

/**
 * A resolved `pack` (struct) type, with its ordered fields.
 *
 * @property name the struct name
 * @property fields the ordered list of fields
 */
data class StructType(
    val name: String,
    val fields: List<StructField>,
    val typeParams: List<String> = emptyList(),
    val visibility: Visibility = Visibility.EXPOSE,
) {
    /** Looks up a field by name. */
    fun field(name: String): StructField? = fields.find { it.name == name }
}

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
    private val structs = mutableMapOf<String, StructType>()
    private val enums = mutableMapOf<String, List<String>>()
    // type name -> (method name -> mangled function name "Type_method")
    private val methods = mutableMapOf<String, MutableMap<String, String>>()
    private val specs = mutableMapOf<String, List<String>>() // spec name → method names
    // slot name → list of (variant name → payload types)
    private val slots = mutableMapOf<String, List<Pair<String, List<IrType>>>>()
    /** Node inheritance: child node name → parent node name. */
    val nodeParents = mutableMapOf<String, String>()
    /** Leaf nodes that cannot be subclassed. */
    val leafNodes = mutableSetOf<String>()
    /** All registered node type names (for dynamic dispatch checks). */
    val nodeTypes = mutableSetOf<String>()
    /** Import aliases: alias name → real (mangled) name. Populated from `use` declarations. */
    val aliasMap = mutableMapOf<String, String>()

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

    /** Returns all registered function names (for import resolution). */
    fun allFunctionNames(): Set<String> = functions.keys.toSet()

    // -- Structs -----------------------------------------------------------

    // -- Structs -----------------------------------------------------------

    /**
     * Registers a struct type.
     *
     * @param struct the struct type to register
     * @throws IllegalStateException if a struct with the same name is already defined
     */
    fun defineStruct(struct: StructType) {
        if (structs.containsKey(struct.name)) {
            error("Struct '${struct.name}' already defined")
        }
        structs[struct.name] = struct
    }

    /** Looks up a struct by name. */
    fun lookupStruct(name: String): StructType? = structs[name]

    /** Returns all registered struct names (for import resolution). */
    fun allStructNames(): Set<String> = structs.keys.toSet()

    // -- Enums -------------------------------------------------------------

    /** Registers an enum's variants. */
    fun defineEnum(name: String, variants: List<String>) {
        if (enums.containsKey(name)) error("Enum '$name' already defined")
        enums[name] = variants
    }

    /** Returns the variants of an enum, or `null` if no such enum exists. */
    fun lookupEnum(name: String): List<String>? = enums[name]

    // -- Fail (error-set) declarations -------------------------------------

    private val fails = mutableMapOf<String, List<String>>()

    /** Registers an error-set's variants. */
    fun defineFail(name: String, variants: List<String>) {
        if (fails.containsKey(name)) error("Error-set '$name' already defined")
        fails[name] = variants
    }

    /** Returns the variants of an error-set, or `null` if no such error-set exists. */
    fun lookupFail(name: String): List<String>? = fails[name]

    // -- Impl methods ------------------------------------------------------

    /** Registers that [typeName] has a method [methodName] implemented by mangled [funcName]. */
    fun defineMethod(typeName: String, methodName: String, funcName: String) {
        methods.getOrPut(typeName) { mutableMapOf() }[methodName] = funcName
    }

    /** Looks up the mangled function name for a method, or `null`. */
    fun lookupMethod(typeName: String, methodName: String): String? = methods[typeName]?.get(methodName)

    // -- Specs (traits) ---------------------------------------------------

    fun defineSpec(name: String, methodNames: List<String>) {
        specs[name] = methodNames
    }


    // -- Type aliases -----------------------------------------------------
    private val aliases = mutableMapOf<String, org.azora.lang.frontend.TypeRef>()
    fun defineAlias(name: String, type: org.azora.lang.frontend.TypeRef) { aliases[name] = type }
    fun lookupAlias(name: String): org.azora.lang.frontend.TypeRef? = aliases[name]

    fun lookupSpec(name: String): List<String>? = specs[name]

    // -- Slots (tagged unions) --------------------------------------------
    fun defineSlot(name: String, variants: List<Pair<String, List<IrType>>>) { slots[name] = variants }
    fun lookupSlot(name: String): List<Pair<String, List<IrType>>>? = slots[name]

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

    /** Returns all variable names in the global (first) scope (for import resolution). */
    fun allVariableNames(): Set<String> = scopes.firstOrNull()?.keys?.toSet() ?: emptySet()

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
