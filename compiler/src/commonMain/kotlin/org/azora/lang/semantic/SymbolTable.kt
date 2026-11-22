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
import org.azora.lang.frontend.Expr
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
    /** Source parameter types before owner type parameters are erased. */
    val paramTypeRefs: List<TypeRef> = emptyList(),
    /** The declaration's `where` clause, for a use that binds its type parameters. */
    val whereClause: org.azora.lang.frontend.Expr? = null,
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
    /**
     * How many leading [params] the call site does not write.
     *
     * A contextual callable built from `func (Self&, Scope&).render()` takes
     * that scope ahead of the arguments anyone writes, and it is filled from the
     * `using` block or receiver lambda the call sits in. Zero for everything else.
     */
    val contextualParams: Int = 0,
    /**
     * Declared by `bridge func`: the backend or a foreign symbol supplies it.
     *
     * A compiler builtin such as `println` is recognised by this, not by name
     * alone, so a program's own function of that name stays an ordinary call.
     */
    val isBridge: Boolean = false,
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
    /** Lazy/reactive storage has observable access behavior, unlike a plain slot. */
    val hasStorageEffects: Boolean = false,
    /**
     * A `for` loop's own row or index binding. Each iteration binds it afresh,
     * so a write to it would change nothing the loop visits.
     */
    val loopVariable: Boolean = false,
    /**
     * Bound to a shared borrow - a `p&` parameter or the receiver of a `&.`
     * member. It owns nothing and may not change what it points at, through
     * any path: a field, an element, or a member that changes its receiver.
     */
    val sharedBorrow: Boolean = false,
    /** The receiver of the member being resolved, for saying how to make it writable. */
    val receiver: Boolean = false,
    /** A non-null T position retains this constraint after erasure to Any. */
    val nonNullGeneric: Boolean = false,
    /**
     * The type the binding was declared with, or its initializer's - `U`
     * where [type] has erased a type parameter to `Any`. What generic code
     * passes on is decided from this (`BoundChecker`, GENERICS_DIP §5.3).
     */
    val declaredRef: org.azora.lang.frontend.TypeRef? = null,
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
    val isUnsafe: Boolean = false,
    /**
     * The type as the pack wrote it, in terms of its type parameters -
     * `Array<T>` where [type] has only erased it. What a construction infers
     * its type arguments from, and checks its arguments against.
     */
    val typeRef: org.azora.lang.frontend.TypeRef? = null,
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
    /**
     * The literal this type is written as, from `bridge pack Byte(IntLiteral)`.
     *
     * Non-null makes `Byte(4)` the literal `4` read at this width rather than a
     * call - see `Literals`.
     */
    val literalKind: String? = null,
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
    /** The parents as written, with the arguments this spec passes them (`List<T>`). */
    val parents: List<org.azora.lang.frontend.TypeRef> = emptyList(),
    /** Specs an implementor must also implement; see `TopLevel.Spec.requires`. */
    val requiredSpecs: List<String> = emptyList(),
    val isBridge: Boolean = false,
    /** Type parameter → what an unwritten argument stands for (`Rhs = Self`). */
    val typeDefaults: Map<String, org.azora.lang.frontend.TypeRef> = emptyMap(),
)

/** A spec method's erased signature, used to type-check calls on a spec-typed value. */
data class SpecMethodSig(
    val paramTypes: List<org.azora.lang.ir.IrType>,
    val returnType: org.azora.lang.ir.IrType,
    val isProperty: Boolean,
    /** Source types, so a use can put the receiver's type arguments in place. */
    val paramTypeRefs: List<org.azora.lang.frontend.TypeRef> = emptyList(),
    val returnTypeRef: org.azora.lang.frontend.TypeRef? = null,
    /** The spec that declares the member, which is whose parameters it names. */
    val owner: String = "",
    /**
     * How the member takes its receiver - `&` shared, `!` exclusive, or owned -
     * or null for a member of the type rather than of a value. A call through a
     * spec-typed value is checked against this, as a call to a concrete member
     * is against its own declaration.
     */
    val receiver: org.azora.lang.frontend.ParamModifier? = null,
    /** The member's own type parameters (`func<U> fold`), which a call binds. */
    val typeParams: List<String> = emptyList(),
    /** The bounds on [typeParams], decided for the types a call binds them to. */
    val whereClause: org.azora.lang.frontend.Expr? = null,
)

/** A validated `impl Contract for Type` conformance. */
data class TraitConformance(
    val typeName: String,
    val contractName: String,
    val typeArgs: List<org.azora.lang.frontend.TypeRef> = emptyList(),
    val isDecorator: Boolean = false,
    /**
     * The implementing type's parameters as the `impl` wrote them - `[T]` for
     * `impl List<T> for ArrayList<T>` - which is what [typeArgs] are written in.
     * Not part of identity: one type implements one spec instantiation once.
     */
    val implTypeParams: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        other is TraitConformance && other.typeName == typeName && other.contractName == contractName &&
            other.typeArgs == typeArgs && other.isDecorator == isDecorator

    override fun hashCode(): Int = listOf(typeName, contractName, typeArgs, isDecorator).hashCode()
}

/**
 * Symbol table built in two stages:
 * 1. [SymbolCollector] populates function signatures (global scope).
 * 2. [TypeResolver] uses it to look up functions and manages local scopes.
 *
 * Functions are stored in a flat global namespace. Variables use a stack of
 * scopes (one per function body, block, or scope) to support lexical scoping
 * with inner scopes shadowing outer ones.
 */
class SymbolTable {
    /** Resolution observations retained before function-local scopes are popped. */
    val semanticFacts = SemanticFactRecorder()

    /**
     * What members the unit's own code resolved to, for reporting uses of
     * deprecated and experimental declarations (see [StabilityRecorder]). Records
     * nothing unless the pipeline was told which code is the unit's own.
     */
    var stability = StabilityRecorder(null)

    private val functions = mutableMapOf<String, FunctionSymbol>()
    private val scopes = ArrayDeque<MutableMap<String, VariableSymbol>>()
    private val structs = mutableMapOf<String, StructType>()

    /** Every declared pack, for diagnostics that know only part of a name. */
    fun structNames(): Set<String> = structs.keys
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
     * `impl Array { bridge fin size }` - whatever scope declares the type.
     *
     * The scope is part of the symbol but not of the question being asked, so it
     * is matched on the tail rather than reconstructed.
     */
    fun lookupTypeStatic(owner: String, name: String): FunctionSymbol? {
        val local = "${owner}__$name"
        functions[local]?.let { return it }
        val suffix = "__$local"
        return functions.entries.firstOrNull { it.key.endsWith(suffix) }?.value
    }

    fun lookupFunction(name: String): FunctionSymbol? = functions[name]

    /**
     * The static `name` of type [owner] - `Owner::name` - by the function name a
     * call uses, or null. A type declared in a scope is keyed by its qualified
     * name, which [owner] may or may not carry.
     */
    /** The global a static value `Owner::name` (`fin name` in `impl Owner`) is stored as, or null. */
    fun inferredStaticValue(owner: String, name: String): String? {
        val globals = scopes.firstOrNull() ?: return null
        val direct = "${owner}__$name"
        if (direct in globals) return direct
        val suffix = "__${owner.substringAfterLast("__")}__$name"
        return globals.keys.firstOrNull { it.endsWith(suffix) }
    }

    fun inferredStatic(owner: String, name: String): String? {
        val direct = "${owner}__$name"
        if (direct in functions) return direct
        return functions.keys.firstOrNull { it.endsWith("__${owner.substringAfterLast("__")}__$name") }
    }

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

    /** Looks up a struct by name. Accepts a scope-qualified name (`Deque`
     *  lowers to `std__Deque`); types are not scope-mangled, so fall back to the
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
     *  Accepts a scope-qualified name, like [lookupStruct]. */
    fun lookupEnum(name: String): List<String>? =
        enums[name] ?: if ("__" in name) enums[name.substringAfterLast("__")] else null

    /**
     * A valued enum's cases: `enum Key: Int { W(87) }` is [type] `Int` and the
     * case `W` with the value `87` as written. A tuple type has one written
     * value per element. The resolver checks the values; lowering reads them.
     */
    data class EnumValues(val type: IrType, val cases: List<Pair<String, List<Expr>>>)

    private val enumValues = mutableMapOf<String, EnumValues>()

    fun defineEnumValues(name: String, values: EnumValues) {
        enumValues[name] = values
    }

    /** The fixed values of a valued enum, or null for an enum whose cases carry none. */
    fun lookupEnumValues(name: String): EnumValues? =
        enumValues[name] ?: if ("__" in name) enumValues[name.substringAfterLast("__")] else null

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
     * The declared name a possibly scope-qualified type reference denotes.
     *
     * `Scope::Type` reaches the semantic layer as `Scope__Type`, because `::`
     * is the same namespace operator that mangles `println` into
     * `std__println`. Types are not scope-mangled, so the qualified spelling
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

    /** Every type that has members registered, for lookups that know only a name. */
    fun methodOwners(): Set<String> = methods.keys

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

    /**
     * [literal] says the operand is an untyped literal, which states no width:
     * `v * 0.5` is a `Float` only until something asks for another real. When no
     * overload names the literal's default type, the one overload a literal of
     * that kind can be read at is chosen, and the caller reads the literal there.
     *
     * The bare name is a binary overload's only when its member takes an operand.
     * `oper+ &.()` is registered bare too, and answering `p + q` with it returned
     * `p` (2026-11-20).
     */
    fun lookupOperator(
        typeName: String,
        operName: String,
        operandKey: String?,
        literal: LiteralOperand? = null,
    ): String? {
        val table = methods[typeName] ?: return null
        if (operandKey != null) table["$operName@$operandKey"]?.let { return it }
        val prefix = "$operName@"
        if (literal != null) {
            literalReading(table.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }, literal)
                ?.let { return table[prefix + it] }
        }
        table[operName]?.takeUnless { isUnaryMember(it) }?.let { return it }
        val candidates = table.keys.filter { it.startsWith(prefix) }
        if (candidates.isEmpty()) return null
        val generic = candidates.filter { !isKnownType(it.removePrefix(prefix)) }
        val chosen = generic.singleOrNull() ?: candidates.singleOrNull() ?: return null
        return table[chosen]
    }

    /**
     * An operator declared on a primitive and applied to an untyped literal:
     * `2.0 * v` where the operator lives on `Double`. [operandKey] is the right
     * operand's key; the result is the primitive the literal is read at and the
     * member, or null when no single primitive of the literal's kind has it.
     */
    fun lookupLiteralReceiverOperator(
        operName: String,
        operandKey: String,
        literal: LiteralOperand,
    ): Pair<String, String>? {
        val member = "$operName@$operandKey"
        val owners = methods.entries.filter { (owner, table) ->
            member in table && IrType.isPrimitiveName(owner) && IrType.isNumeric(IrType.fromName(owner))
        }.map { it.key }
        val owner = literalReading(owners, literal) ?: return null
        return owner to (methods[owner]?.get(member) ?: return null)
    }

    /**
     * The one name among [typeNames] an untyped [literal] can be read at: its
     * default type, else the single one of its kind. An integer literal prefers
     * an integer and falls back to a real (`v * 2` on a vector of `Double`); a
     * real is never read at an integer.
     */
    private fun literalReading(typeNames: List<String>, literal: LiteralOperand): String? {
        val types = typeNames.filter { IrType.isPrimitiveName(it) }.associateWith { IrType.fromName(it) }
        val default = if (literal == LiteralOperand.INTEGER) IrType.defaultInt else IrType.defaultFloat
        types.entries.firstOrNull { it.value == default }?.let { return it.key }
        val integers = types.filterValues { IrType.isInteger(it) }.keys
        val reals = types.filterValues { it in IrType.floatTypes }.keys
        val tier = when (literal) {
            LiteralOperand.INTEGER -> integers.ifEmpty { reals }
            LiteralOperand.REAL -> reals
        }
        return tier.singleOrNull()
    }

    /** True for a member that takes no operand besides its receiver. */
    private fun isUnaryMember(mangled: String): Boolean = functions[mangled]?.params?.size == 1

    /**
     * True when [name] names a declared type rather than a type parameter.
     *
     * A primitive is a declared type too: `oper*@Double` on a vector of `Double`
     * is that operand's overload, not an erased `T` that matches any operand.
     */
    private fun isKnownType(name: String): Boolean =
        name in structs || name in enums || name in specs || name == "Self" ||
            IrType.isPrimitiveName(name)

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
        parents: List<org.azora.lang.frontend.TypeRef> = emptyList(),
        typeDefaults: Map<String, org.azora.lang.frontend.TypeRef> = emptyMap(),
    ) {
        specs[name] = SpecSymbol(methodNames, callback, isDecorator = false, typeParams = typeParams, propTypes = propTypes, methodSigs = methodSigs, parentNames = parentNames, parents = parents, requiredSpecs = requiredSpecs, isBridge = isBridge, typeDefaults = typeDefaults)
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
        // A total order is a partial one (RANGE_DIP §3.3): an `Order` type
        // answers every comparison `PartialOrder` asks, so it is one without
        // writing it, and `Range<Int>` needs nothing more.
        if (!conformance.isDecorator && conformance.contractName.substringAfterLast("__") == ORDER) {
            val partial = conformance.contractName.removeSuffix(ORDER) + PARTIAL_ORDER
            if (conformances.none { it.typeName == conformance.typeName && it.contractName.substringAfterLast("__") == PARTIAL_ORDER }) {
                conformances.add(TraitConformance(conformance.typeName, partial, implTypeParams = conformance.implTypeParams))
            }
        }
        return true
    }

    private companion object {
        const val ORDER = "Order"
        const val PARTIAL_ORDER = "PartialOrder"
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

    /**
     * Whether a value of [type] may be duplicated with the compiler-supplied
     * `clone`. A declared type answers by its conformance. An array, set, map
     * or tuple answers by what it holds, as a pack's fields do: a value no
     * declared type opts out of is clonable. So `[1, 2, 3].clone()` needs no
     * import, as `Box(1).clone()` does not.
     */
    fun isClonable(type: IrType): Boolean = when (type) {
        is IrType.Array -> isClonable(type.element)
        is IrType.Set -> isClonable(type.element)
        is IrType.Map -> isClonable(type.key) && isClonable(type.value)
        is IrType.Tuple -> type.elements.all(::isClonable)
        is IrType.Nullable -> isClonable(type.inner)
        is IrType.Named -> conformsTo(type.name, "Clone") ||
            (lookupStruct(type.name) == null && lookupEnum(type.name) == null && lookupSlot(type.name) == null)
        else -> true
    }

    /** Returns all validated conformances. */
    fun allConformances(): List<TraitConformance> = conformances.toList()

    /** Retains the fully inferred callable type for IR lowering. */
    /**
     * The type a `.Name` resolved to, keyed by where it was written.
     *
     * The resolver knows the expected type; lowering does not re-derive it, it
     * reads what the resolver decided - so the two cannot disagree about which
     * type a leading dot meant.
     */
    // The name is part of the key: every module of a program numbers its lines
    // from 1, so a position alone names a dot in each of them - `.Digit5` in
    // one file and `.(key)` in another may both be written at 262:50.
    private val inferredMembers = mutableMapOf<Int, String>()

    fun defineInferredMember(member: org.azora.lang.frontend.Expr.InferredMember, typeName: String) {
        inferredMembers[member.site] = typeName
    }

    fun lookupInferredMember(member: org.azora.lang.frontend.Expr.InferredMember): String? =
        inferredMembers[member.site]

    /**
     * The whole type a `.(…)` builds, arguments included - `Array<Int>` where the
     * owner alone is `Array`. Recorded where the position states one, so the
     * construction is the `Type<…>(…)` call it stands for, as a typed binding's
     * `.(…)` already is from the parser.
     */
    private val inferredConstructions = mutableMapOf<Int, org.azora.lang.frontend.TypeRef>()

    fun defineInferredConstruction(member: org.azora.lang.frontend.Expr.InferredMember, type: org.azora.lang.frontend.TypeRef) {
        inferredConstructions[member.site] = type
    }

    fun lookupInferredConstruction(member: org.azora.lang.frontend.Expr.InferredMember): org.azora.lang.frontend.TypeRef? =
        inferredConstructions[member.site]

    /**
     * The bounds' type of each range literal that builds a value (`a..b` is a
     * `Range<T>`, RANGE_DIP §4): lowering builds the `Range<T>` the resolver
     * decided rather than working `T` out again. Keyed by the node, not its
     * position: a range inside a string template is positioned within the
     * template, so two templates on one line could share a position.
     */
    /**
     * The `T` the resolver decided for a range: a literal's bounds' type, or
     * the `T` of a `Range<T>` value a `for` walks - keyed by the expression.
     */
    private val rangeBounds = mutableMapOf<org.azora.lang.frontend.Expr, org.azora.lang.frontend.TypeRef>()

    fun defineRangeBounds(range: org.azora.lang.frontend.Expr, type: org.azora.lang.frontend.TypeRef) {
        rangeBounds[range] = type
    }

    fun lookupRangeBounds(range: org.azora.lang.frontend.Expr): org.azora.lang.frontend.TypeRef? = rangeBounds[range]

    /** The `S` a walked range steps by, keyed by the walked expression; absent when generic code walks by an unknown `S`'s unit. */
    private val walkStrides = mutableMapOf<org.azora.lang.frontend.Expr, org.azora.lang.frontend.TypeRef>()

    fun defineWalkStride(range: org.azora.lang.frontend.Expr, stride: org.azora.lang.frontend.TypeRef) {
        walkStrides[range] = stride
    }

    fun lookupWalkStride(range: org.azora.lang.frontend.Expr): org.azora.lang.frontend.TypeRef? = walkStrides[range]

    /**
     * `.(args)` as the `Type<…>(args)` call the resolver decided it stands for,
     * or null for anything else - a named `.Variant`, or a `.(…)` nothing typed.
     */
    fun inferredConstructionCall(expr: org.azora.lang.frontend.Expr.InferredMember): org.azora.lang.frontend.Expr.Call? {
        if (expr.name.isNotEmpty()) return null
        val args = expr.ctorArgs ?: return null
        val owner = lookupInferredMember(expr) ?: return null
        val typeArgs = (lookupInferredConstruction(expr) as? org.azora.lang.frontend.TypeRef.Named)
            ?.args.orEmpty()
        return org.azora.lang.frontend.Expr.Call(owner, args, expr.line, expr.column, owner.length, typeArgs)
    }

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

    fun specNames(): Set<String> = specs.keys

    fun lookupSpec(name: String): SpecSymbol? = specs[name]

    // -- Slots (tagged unions) --------------------------------------------
    fun defineSlot(name: String, variants: List<Pair<String, List<IrType>>>) { slots[name] = variants }
    fun lookupSlot(name: String): List<Pair<String, List<IrType>>>? =
        slots[name] ?: if ("__" in name) slots[name.substringAfterLast("__")] else null

    // -- Local variable scopes ----------------------------------------------

    /**
     * Pushes a new empty scope onto the scope stack. Called when entering
     * a function body, block, or scope.
     */
    fun pushScope() { scopes.addLast(mutableMapOf()) }

    /**
     * Pops the top scope from the scope stack. Called when exiting
     * a function body, block, or scope.
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

    /** An immutable snapshot with the innermost binding winning each spelling. */
    fun visibleVariables(): Map<String, VariableSymbol> = buildMap {
        scopes.forEach { putAll(it) }
    }

    /**
     * Copies all variables from the current (innermost) scope into the given map.
     * Used by friend scopes to persist their shared scope between blocks.
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

    /**
     * True when [name] is declared only at the outermost scope - a global. A
     * member a `using` block opens stands closer than one of those, and a
     * parameter or local closer still.
     */
    fun isGlobalOnly(name: String): Boolean {
        for (index in scopes.indices.reversed()) {
            if (scopes[index].containsKey(name)) return index == 0
        }
        return false
    }

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
