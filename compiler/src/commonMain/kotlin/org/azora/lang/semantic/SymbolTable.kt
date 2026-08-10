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
import org.azora.lang.frontend.CaptureMode
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
    val visibility: Visibility = Visibility.PUBLIC,
    val memberCallStyle: MemberCallStyle = MemberCallStyle.NORMAL,
    /**
     * True when the declaration has no Azora body - `bridge func`, `bridge prop`.
     *
     * The backend supplies the implementation, so a use site must not lower to a
     * call: there is no function to call.
     */
    val isBodyless: Boolean = false,
    /** Source return type retained for generic compile-time type-function evaluation. */
    val returnTypeRef: TypeRef? = null,
    val isReactive: Boolean = false,
    /**
     * Indices of parameters declared `x!` - an exclusive borrow the callee may
     * write through. Only a binding whose value is mutable can be passed to one.
     */
    val exclusiveParams: Set<Int> = emptySet(),
    /** Indices of parameters declared `x&` - a shared borrow, which owns nothing. */
    val sharedParams: Set<Int> = emptySet(),
    /**
     * Indices of parameters declared `x: return T` - ownership the caller gets
     * back when the call ends. Their arguments are written `lend x`.
     */
    val returnedParams: Set<Int> = emptySet(),
)

/**
 * A local variable registered during type resolution.
 *
 * Mutability has two independent axes, one per binding keyword:
 *
 * | keyword | [mutable] (rebind) | [valueMutable] (mutate through) |
 * |---------|--------------------|---------------------------------|
 * | `var`   | true               | true                            |
 * | `let`   | false              | true                            |
 * | `val`   | true               | false                           |
 * | `fin`   | false              | false                           |
 *
 * @property name the variable name
 * @property type the resolved type of the variable
 * @property mutable whether the *name* can be rebound (`var`/`val`)
 * @property valueMutable whether the *value* can be mutated or borrowed mutably
 *   through this name (`var`/`let`)
 */
data class VariableSymbol(
    val name: String,
    val type: IrType,
    val mutable: Boolean = true,
    val visibility: Visibility = Visibility.PUBLIC,
    val valueMutable: Boolean = true,
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
    val visibility: Visibility = Visibility.PUBLIC,
    /** Default initializer (for `Pack<T>()` construction with omitted fields). */
    val default: org.azora.lang.frontend.Expr? = null,
    /**
     * Index of the owning pack's type parameter this field was declared as, or
     * `-1`. A parameter resolves to `Any`, so which one it was cannot be
     * recovered from [type]; it is recorded here so `Box<Double>.value` can be
     * typed as `Double` rather than as an opaque word.
     */
    val typeParamIndex: Int = -1,
    /** `unsafe fin data: T*` - readable only inside an unsafe scope. */
    val isUnsafe: Boolean = false
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
    val visibility: Visibility = Visibility.PUBLIC,
    /** Compiler-provided type declaration with no ordinary runtime constructor. */
    val isBridge: Boolean = false,
    /** True for a `union`: the members share one storage slot (see `TopLevel.Pack.isUnion`). */
    val isUnion: Boolean = false,
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
    /** Property/requirement name → return type, for member access on a spec-typed value. */
    val propTypes: Map<String, org.azora.lang.ir.IrType> = emptyMap(),
    /** Method name → signature, for method calls on a spec-typed value. */
    val methodSigs: Map<String, SpecMethodSig> = emptyMap(),
    /** Parent spec inherited from (`spec Mutable: Read`), resolved at query time. */
    val parentNames: List<String> = emptyList(),
    /** Specs an implementor must also implement; see `TopLevel.Spec.requires`. */
    val requiredSpecs: List<String> = emptyList(),
    val isBridge: Boolean = false,
)

/** A spec method's erased signature, used to type-check calls on a spec-typed value. */
data class SpecMethodSig(
    val paramTypes: List<org.azora.lang.ir.IrType>,
    val returnType: org.azora.lang.ir.IrType,
    val isProperty: Boolean,
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
 * scopes (one per function body, block, or realm) to support lexical scoping
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
    private val lambdaTypes = mutableMapOf<Pair<Int, Int>, IrType.Function>()
    /** Exact source bindings and modes selected after default-capture inference. */
    private val lambdaCaptures = mutableMapOf<Pair<Int, Int>, Map<String, CaptureMode>>()
    // slot name → list of (variant name → payload types)
    private val slots = mutableMapOf<String, List<Pair<String, List<IrType>>>>()
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
    /**
     * A member declared on [owner] as a type-scoped constant or function -
     * `impl Array:: { bridge fin size }` - whatever realm declares the type.
     *
     * The realm is part of the symbol but not of the question being asked, so it
     * is matched on the tail rather than reconstructed.
     */
    fun lookupTypeStatic(owner: String, name: String): FunctionSymbol? {
        val local = "${owner}__$name"
        functions[local]?.let { return it }
        val suffix = "__$local"
        return functions.entries.firstOrNull { it.key.endsWith(suffix) }?.value
    }

    fun lookupFunction(name: String): FunctionSymbol? = functions[name]

    fun markFunctionReactive(name: String) {
        val canonical = functions[name]?.name ?: name
        functions.entries.forEach { entry ->
            if (entry.value.name == canonical) entry.setValue(entry.value.copy(isReactive = true))
        }
    }

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

    /** Looks up a struct by name. Accepts a realm-qualified name (`std::Deque`
     *  lowers to `std__Deque`); types are not realm-mangled, so fall back to the
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

    /** Returns the variants of an enum, or `null` if no such enum exists.
     *  Accepts a realm-qualified name, like [lookupStruct]. */
    fun lookupEnum(name: String): List<String>? =
        enums[name] ?: if ("__" in name) enums[name.substringAfterLast("__")] else null

    // -- Fail (error-set) declarations -------------------------------------

    private val fails = mutableMapOf<String, List<String>>()

    /** Registers an error-set's variants. */
    fun defineFail(name: String, variants: List<String>) {
        if (fails.containsKey(name)) error("Error-set '$name' already defined")
        fails[name] = variants
    }

    /** Returns the variants of an error-set, or `null` if no such error-set exists. */
    fun lookupFail(name: String): List<String>? =
        fails[name] ?: if ("__" in name) fails[name.substringAfterLast("__")] else null

    /**
     * The declared name a possibly realm-qualified type reference denotes.
     *
     * `Realm::Type` reaches the semantic layer as `Realm__Type`, because `::`
     * is the same namespace operator that mangles `std::println` into
     * `std__println`. Types are not realm-mangled, so the qualified spelling
     * has to be mapped back before the name is used as a type identity.
     * A name that is already declared is returned untouched, which leaves
     * genuinely mangled function names alone.
     */
    fun canonicalTypeName(name: String): String {
        if (isDeclaredType(name)) return name
        val bare = name.substringAfterLast("__")
        return if (bare != name && isDeclaredType(bare)) bare else name
    }

    private fun isDeclaredType(name: String): Boolean =
        structs.containsKey(name) || enums.containsKey(name) ||
            slots.containsKey(name) || fails.containsKey(name)

    // -- Impl methods ------------------------------------------------------

    /** Registers that [typeName] has a method [methodName] implemented by mangled [funcName]. */
    fun defineMethod(typeName: String, methodName: String, funcName: String) {
        methods.getOrPut(typeName) { mutableMapOf() }[methodName] = funcName
    }

    /** Looks up the mangled function name for a method, or `null`. */
    fun lookupMethod(typeName: String, methodName: String): String? = methods[typeName]?.get(methodName)

    /**
     * Resolves an operator overload on [typeName] for a right-hand operand.
     *
     * Overloads are registered as `oper+@Self`, `oper+@T`, … so one operator can be
     * declared once per operand type. A declaration with a single overload keeps the
     * bare `oper+` name and is found unchanged.
     *
     * Resolution prefers the overload naming the operand's own type. Failing that it
     * takes an overload keyed by a type *parameter* - generics are erased, so
     * `(rhs: T&)` is registered under `T` and matches whatever `T` became. Only when
     * that leaves exactly one candidate is it chosen, so a genuine ambiguity resolves
     * to nothing rather than to an arbitrary overload.
     */
    /**
     * A *unary* operator's member - the bare name only.
     *
     * [lookupOperator] falls back to an operand-keyed candidate when the bare
     * name misses, which is right for a binary operator written once. For a
     * unary one it is wrong: `-x` would find the binary `oper-@Vec2` and answer
     * subtraction with one operand missing.
     */
    fun lookupUnaryOperator(typeName: String, operName: String): String? =
        methods[typeName]?.get(operName)

    fun lookupOperator(typeName: String, operName: String, operandKey: String?): String? {
        val table = methods[typeName] ?: return null
        if (operandKey != null) table["$operName@$operandKey"]?.let { return it }
        table[operName]?.let { return it }
        val prefix = "$operName@"
        val candidates = table.keys.filter { it.startsWith(prefix) }
        if (candidates.isEmpty()) return null
        val generic = candidates.filter { !isKnownType(it.removePrefix(prefix)) }
        val chosen = generic.singleOrNull() ?: candidates.singleOrNull() ?: return null
        return table[chosen]
    }

    /** True when [name] names a declared type rather than a type parameter. */
    private fun isKnownType(name: String): Boolean =
        name in structs || name in enums || name in specs || name == "Self"

    // -- Universal infix calls (`macro $a @to $b`) ------------------------
    /** Infix method name → the generic free function it desugars to (`self` first). */
    private val universalInfix = mutableMapOf<String, String>()

    fun defineUniversalInfix(methodName: String, funcName: String) { universalInfix[methodName] = funcName }

    fun lookupUniversalInfix(methodName: String): String? = universalInfix[methodName]

    // -- Specs (traits) ---------------------------------------------------

    fun defineSpec(
        name: String,
        methodNames: List<String>,
        callback: org.azora.lang.frontend.SpecCallback? = null,
        typeParams: List<String> = emptyList(),
        propTypes: Map<String, org.azora.lang.ir.IrType> = emptyMap(),
        methodSigs: Map<String, SpecMethodSig> = emptyMap(),
        parentNames: List<String> = emptyList(),
        requiredSpecs: List<String> = emptyList(),
        isBridge: Boolean = false,
    ) {
        specs[name] = SpecSymbol(methodNames, callback, isDecorator = false, typeParams = typeParams, propTypes = propTypes, methodSigs = methodSigs, parentNames = parentNames, requiredSpecs = requiredSpecs, isBridge = isBridge)
    }

    /**
     * Resolves a spec method signature, walking the parent chain
     * (`spec Mutable: Read`) so inherited members resolve regardless of the
     * order specs were registered in.
     */
    fun lookupSpecMethod(specName: String, methodName: String): SpecMethodSig? {
        for (name in specAndAncestors(specName)) {
            specs[name]?.methodSigs?.get(methodName)?.let { return it }
        }
        return null
    }

    /** [specName] and every spec it inherits from, breadth-first, each once. */
    fun specAndAncestors(specName: String): List<String> {
        val order = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(specName))
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!seen.add(name)) continue
            order.add(name)
            specs[name]?.parentNames?.forEach { queue.addLast(it) }
        }
        return order
    }

    /** Resolves a spec property's type, walking the parent chain. */
    fun lookupSpecProp(specName: String, propName: String): org.azora.lang.ir.IrType? {
        for (name in specAndAncestors(specName)) {
            specs[name]?.propTypes?.get(propName)?.let { return it }
        }
        return null
    }

    /** Registers a `deco` as a marker contract usable by `impl Deco for Type`. */
    fun defineDecorator(
        name: String,
        targets: Set<DecoTarget> = emptySet(),
        bindings: List<org.azora.lang.frontend.DecoratorBinding> = emptyList(),
        isBridge: Boolean = false,
    ) {
        specs[name] = SpecSymbol(
            emptyList(),
            isDecorator = true,
            decoratorTargets = targets,
            decoratorBindings = bindings,
            isBridge = isBridge,
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

    /**
     * Returns whether [typeName] implements [contractName], ignoring type
     * arguments - used for subtype checks (a pack usable as a spec it implements)
     * where the spec's generic arguments are erased.
     */
    fun conformsTo(typeName: String, contractName: String): Boolean =
        conformances.any { it.typeName == typeName && it.contractName == contractName }

    /** Returns all validated conformances. */
    fun allConformances(): List<TraitConformance> = conformances.toList()

    /** Retains the fully inferred callable type for IR lowering. */
    fun defineLambdaType(line: Int, column: Int, type: IrType.Function) {
        lambdaTypes[line to column] = type
    }

    fun lookupLambdaType(line: Int, column: Int): IrType.Function? =
        lambdaTypes[line to column]

    fun defineLambdaCaptures(line: Int, column: Int, captures: Map<String, CaptureMode>) {
        lambdaCaptures[line to column] = captures.toMap()
    }

    fun lookupLambdaCaptures(line: Int, column: Int): Map<String, CaptureMode> =
        lambdaCaptures[line to column].orEmpty()


    // -- Type aliases -----------------------------------------------------
    private val aliases = mutableMapOf<String, org.azora.lang.frontend.TypeRef>()
    fun defineAlias(name: String, type: org.azora.lang.frontend.TypeRef) { aliases[name] = type }
    fun lookupAlias(name: String): org.azora.lang.frontend.TypeRef? = aliases[name]

    fun lookupSpec(name: String): SpecSymbol? = specs[name]

    // -- Slots (tagged unions) --------------------------------------------
    fun defineSlot(name: String, variants: List<Pair<String, List<IrType>>>) { slots[name] = variants }
    fun lookupSlot(name: String): List<Pair<String, List<IrType>>>? =
        slots[name] ?: if ("__" in name) slots[name.substringAfterLast("__")] else null

    // -- Local variable scopes ----------------------------------------------

    /**
     * Pushes a new empty scope onto the scope stack. Called when entering
     * a function body, block, or realm.
     */
    fun pushScope() { scopes.addLast(mutableMapOf()) }

    /**
     * Pops the top scope from the scope stack. Called when exiting
     * a function body, block, or realm.
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
     * Used by friend realms to persist their shared scope between blocks.
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
    /**
     * Every variable name visible from *outside* the innermost scope.
     *
     * A loop's own bindings live in the innermost scope and are fresh on each
     * iteration, so they are exactly what a move-in-a-loop check must ignore.
     */
    fun enclosingVariableNames(): Set<String> =
        scopes.toList().dropLast(1).flatMapTo(mutableSetOf()) { it.keys }

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

    /** Scope index selected by [lookupVariableInUpperScope], for boundary checks. */
    fun variableScopeIndexInUpperScope(name: String, depth: Int = 1): Int? {
        val startIndex = scopes.size - 1 - depth
        if (startIndex < 0) return null
        for (i in startIndex downTo 0) {
            if (scopes[i].containsKey(name)) return i
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

    /** How many scopes are currently open. Scope 0 holds the program's globals. */
    fun scopeDepth(): Int = scopes.size

    /**
     * The index of the scope a variable is declared in, or null when it is not a
     * scoped variable at all.
     *
     * A lambda uses this to tell a binding it owns from one belonging to the
     * scope around it - the distinction its capture list is about.
     */
    fun variableScopeIndex(name: String): Int? {
        for (i in scopes.indices.reversed()) {
            if (scopes[i].containsKey(name)) return i
        }
        return null
    }
}
