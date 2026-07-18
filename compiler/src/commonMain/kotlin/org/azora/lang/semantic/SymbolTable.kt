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
import org.azora.lang.frontend.MemberCallStyle
import org.azora.lang.frontend.DecoTarget
import org.azora.lang.frontend.TypeRef
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
    val memberCallStyle: MemberCallStyle = MemberCallStyle.NORMAL,
    /** Source return type retained for generic compile-time type-function evaluation. */
    val returnTypeRef: TypeRef? = null,
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
    /** Default initializer (for `Pack<T>()` construction with omitted fields). */
    val default: org.azora.lang.frontend.Expr? = null,
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
    val shielded: Boolean = false,
) {
    /** Looks up a field by name. */
    fun field(name: String): StructField? = fields.find { it.name == name }
}

/** A registered spec signature. Callback specs use compact syntax such as `spec Into<T>: T { ref self } use as "to${T.typeName}"`. */
data class SpecSymbol(
    val methodNames: List<String>,
    val callback: org.azora.lang.frontend.SpecCallback? = null,
    /** Decorators are marker contracts and therefore never require methods. */
    val isDecorator: Boolean = false,
    val typeParams: List<String> = emptyList(),
    val decoratorTargets: Set<DecoTarget> = emptySet(),
    val decoratorBindings: List<org.azora.lang.frontend.DecoratorBinding> = emptyList(),
)

/** A validated `impl Contract for Type` conformance. */
data class TraitConformance(
    val typeName: String,
    val contractName: String,
    val typeArgs: List<org.azora.lang.frontend.TypeRef> = emptyList(),
    val isDecorator: Boolean = false,
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
    private val structs = mutableMapOf<String, StructType>()
    private val enums = mutableMapOf<String, List<String>>()
    // type name -> (method name -> mangled function name "Type_method")
    private val methods = mutableMapOf<String, MutableMap<String, String>>()
    private val specs = mutableMapOf<String, SpecSymbol>() // spec name → method names/callback
    private val conformances = mutableListOf<TraitConformance>()
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

    /** Registers an additional lookup name while preserving the emitted symbol name. */
    fun defineFunctionAlias(alias: String, symbol: FunctionSymbol) {
        if (functions.containsKey(alias)) {
            error("Function '$alias' already defined")
        }
        functions[alias] = symbol
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

    /** Looks up a struct by name. Accepts a zone-qualified name (`std::Deque`
     *  lowers to `std__Deque`); types are not zone-mangled, so fall back to the
     *  final segment when the mangled form is not found. */
    fun lookupStruct(name: String): StructType? =
        structs[name] ?: if ("__" in name) structs[name.substringAfterLast("__")] else null

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

    fun defineSpec(
        name: String,
        methodNames: List<String>,
        callback: org.azora.lang.frontend.SpecCallback? = null,
        typeParams: List<String> = emptyList(),
    ) {
        specs[name] = SpecSymbol(methodNames, callback, isDecorator = false, typeParams = typeParams)
    }

    /** Registers a `deco` as a marker contract usable by `impl Deco for Type`. */
    fun defineDecorator(
        name: String,
        targets: Set<DecoTarget> = emptySet(),
        bindings: List<org.azora.lang.frontend.DecoratorBinding> = emptyList(),
    ) {
        specs[name] = SpecSymbol(
            emptyList(),
            isDecorator = true,
            decoratorTargets = targets,
            decoratorBindings = bindings,
        )
    }

    /** Records a validated conformance for semantic consumers and future derives. */
    fun defineConformance(conformance: TraitConformance): Boolean {
        if (conformances.any {
                it.typeName == conformance.typeName &&
                    it.contractName == conformance.contractName &&
                    it.typeArgs == conformance.typeArgs
            }
        ) return false
        conformances.add(conformance)
        return true
    }

    /** Returns whether [typeName] implements the requested spec or decorator contract. */
    fun implements(
        typeName: String,
        contractName: String,
        typeArgs: List<org.azora.lang.frontend.TypeRef> = emptyList(),
    ): Boolean = conformances.any {
        it.typeName == typeName && it.contractName == contractName && it.typeArgs == typeArgs
    }

    /** Returns all validated conformances. */
    fun allConformances(): List<TraitConformance> = conformances.toList()


    // -- Type aliases -----------------------------------------------------
    private val aliases = mutableMapOf<String, org.azora.lang.frontend.TypeRef>()
    fun defineAlias(name: String, type: org.azora.lang.frontend.TypeRef) { aliases[name] = type }
    fun lookupAlias(name: String): org.azora.lang.frontend.TypeRef? = aliases[name]

    fun lookupSpec(name: String): SpecSymbol? = specs[name]

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
