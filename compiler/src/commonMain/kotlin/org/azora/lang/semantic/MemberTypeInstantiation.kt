package org.azora.lang.semantic

import org.azora.lang.frontend.TypeRef
import org.azora.lang.ir.IrType

/** Call-site types; the registered function retains its physical/erased signature. */
internal fun instantiateMember(
    table: SymbolTable,
    receiver: IrType.Named,
    function: FunctionSymbol,
): FunctionSymbol {
    // A spec owns members too: the literal factory it designates.
    val ownerParams = table.lookupStruct(receiver.name)?.typeParams
        ?: table.lookupSpec(receiver.name)?.typeParams
        ?: return function
    if (receiver.args.isEmpty() || ownerParams.isEmpty()) return function
    val bindings = ownerParams.mapIndexedNotNull { index, name ->
        if (name in function.typeParams) return@mapIndexedNotNull null
        val argument = receiver.args.getOrNull(index) ?: return@mapIndexedNotNull null
        name to (receiver.constArgs.getOrNull(index)?.let { TypeRef.Const(it) } ?: typeRefOf(argument))
    }.toMap()
    val unbound = (ownerParams.toSet() - bindings.keys) + function.typeParams
    fun resolve(ref: TypeRef, registered: IrType): IrType {
        val substituted = substituteMemberType(ref, bindings)
        // Keep resolved aliases/type functions when this position does not use
        // an owner parameter. Only dependent positions need instantiation.
        return if (substituted == ref) registered else IrType.resolve(substituted, unbound)
    }
    return function.copy(
        params = function.params.mapIndexed { i, (name, type) ->
            name to (function.paramTypeRefs.getOrNull(i)?.let { resolve(it, type) } ?: type)
        },
        returnType = function.returnTypeRef?.let { resolve(it, function.returnType) } ?: function.returnType,
    )
}

/**
 * A spec member's signature where the receiver is [receiver]: `Stack<Double>`
 * reads `top(): T` as returning Double. A member inherited from a parent spec
 * names that parent's parameters, so it is read where the receiver is seen as
 * that parent: `MutableList<String>` is `List<String>` to `List`'s `get(): T`.
 */
internal fun instantiateSpecMember(table: SymbolTable, receiver: IrType.Named, sig: SpecMethodSig): SpecMethodSig {
    val owner = asAncestorSpec(table, receiver, sig.owner, mutableSetOf()) ?: return sig
    val typed = instantiateMember(
        table,
        owner,
        FunctionSymbol(
            name = sig.owner,
            params = sig.paramTypes.mapIndexed { i, type -> "p$i" to type },
            returnType = sig.returnType,
            paramTypeRefs = sig.paramTypeRefs,
            returnTypeRef = sig.returnTypeRef,
        ),
    )
    return sig.copy(paramTypes = typed.params.map { it.second }, returnType = typed.returnType)
}

/** [receiver] seen as its ancestor spec [owner], its arguments passed along each parent. */
private fun asAncestorSpec(table: SymbolTable, receiver: IrType.Named, owner: String, seen: MutableSet<String>): IrType.Named? {
    if (receiver.name == owner) return receiver
    if (!seen.add(receiver.name)) return null
    val spec = table.lookupSpec(receiver.name) ?: return null
    if (receiver.args.size != spec.typeParams.size) return null
    val bindings = spec.typeParams.zip(receiver.args).associate { (name, argument) -> name to typeRefOf(argument) }
    for (parent in spec.parents) {
        val passed = substituteMemberType(parent, bindings) as? TypeRef.Named ?: continue
        val seenAs = IrType.resolve(passed, emptySet()) as? IrType.Named ?: continue
        asAncestorSpec(table, seenAs, owner, seen)?.let { return it }
    }
    return null
}

/**
 * What a sequence literal factory takes and builds for [target]: the element
 * type of its variadic parameter and its result, with the target's type
 * arguments in place. The factory is lifted with its owner's parameters as its
 * own, so they are released here to be bound by the target.
 */
internal fun literalFactoryTypes(table: SymbolTable, target: IrType.Named, factory: FunctionSymbol): Pair<IrType, IrType> {
    val physical = factory.params.single().second
    val element = (physical as? IrType.Array)?.element ?: physical
    val typed = instantiateMember(
        table,
        target,
        factory.copy(
            params = listOf("element" to element),
            paramTypeRefs = factory.paramTypeRefs.take(1).map { (it as? TypeRef.Array)?.element ?: it },
            typeParams = emptyList(),
        ),
    )
    return typed.params.single().second to typed.returnType
}

private fun substituteMemberType(ref: TypeRef, bindings: Map<String, TypeRef>): TypeRef = when (ref) {
    is TypeRef.Named -> if (ref.args.isEmpty() && ref.name in bindings) bindings.getValue(ref.name)
        else ref.copy(args = ref.args.map { substituteMemberType(it, bindings) })
    is TypeRef.Array -> ref.copy(element = substituteMemberType(ref.element, bindings))
    is TypeRef.Map -> ref.copy(key = substituteMemberType(ref.key, bindings), value = substituteMemberType(ref.value, bindings))
    is TypeRef.Set -> ref.copy(element = substituteMemberType(ref.element, bindings))
    is TypeRef.Tuple -> ref.copy(elements = ref.elements.map { substituteMemberType(it, bindings) })
    is TypeRef.Nullable -> ref.copy(inner = substituteMemberType(ref.inner, bindings))
    is TypeRef.Pointer -> ref.copy(inner = substituteMemberType(ref.inner, bindings))
    is TypeRef.Reference -> ref.copy(inner = substituteMemberType(ref.inner, bindings))
    is TypeRef.Failable -> ref.copy(ok = substituteMemberType(ref.ok, bindings))
    is TypeRef.Function -> ref.copy(
        params = ref.params.map { substituteMemberType(it, bindings) },
        ret = substituteMemberType(ref.ret, bindings),
        receivers = ref.receivers.map { substituteMemberType(it, bindings) },
    )
    is TypeRef.Const -> ref
}

/** Converts an inferred IR type back to the source type form used by type functions. */
internal fun typeRefOf(type: IrType): TypeRef = when (type) {
    // A width writes itself: a named one by its name, an unnamed one as the
    // `Int<N>` it is.
    is IrType.Integer -> IrType.NAMED_WIDTHS[type]?.let { TypeRef.Named(it) }
        ?: TypeRef.Named(if (type.signed) "Int" else "UInt", listOf(TypeRef.Const(type.bits.toLong())))
    IrType.Double -> TypeRef.Named("Double")
    IrType.String -> TypeRef.Named("String")
    IrType.Bool -> TypeRef.Named("Bool")
    IrType.Unit -> TypeRef.Named("Unit")
    IrType.Nothing -> TypeRef.Named("Nothing")
    IrType.Char -> TypeRef.Named("Char")
    IrType.Byte -> TypeRef.Named("Byte")
    IrType.UByte -> TypeRef.Named("UByte")
    IrType.Short -> TypeRef.Named("Short")
    IrType.UShort -> TypeRef.Named("UShort")
    IrType.Long -> TypeRef.Named("Long")
    IrType.ULong -> TypeRef.Named("ULong")
    IrType.ISize -> TypeRef.Named("ISize")
    IrType.USize -> TypeRef.Named("USize")
    IrType.Cent -> TypeRef.Named("Cent")
    IrType.UCent -> TypeRef.Named("UCent")
    // The floats added beside the integers: each is written as it is
    // named, so the name is the whole conversion.
    IrType.Half -> TypeRef.Named("Half")
    IrType.Float -> TypeRef.Named("Float")
    IrType.Quad -> TypeRef.Named("Quad")
    IrType.Any -> TypeRef.Named("Any", synthesized = true)
    is IrType.Array -> type.size?.let {
        TypeRef.Named("Array", listOf(typeRefOf(type.element), TypeRef.Const(it)))
    } ?: TypeRef.Array(typeRefOf(type.element))
    is IrType.Map -> TypeRef.Map(typeRefOf(type.key), typeRefOf(type.value))
    is IrType.Set -> TypeRef.Set(typeRefOf(type.element))
    is IrType.Function -> TypeRef.Function(
        type.params.map(::typeRefOf),
        typeRefOf(type.ret),
        type.receivers.map(::typeRefOf),
        type.kind,
        type.isEscaping,
        type.isInline,
    )
    is IrType.Task -> TypeRef.Named("Task", listOf(typeRefOf(type.result)))
    is IrType.Tuple -> TypeRef.Tuple(type.elements.map(::typeRefOf))
    is IrType.Variant -> TypeRef.Named("Var", type.elements.map(::typeRefOf))
    is IrType.Nullable -> TypeRef.Nullable(typeRefOf(type.inner))
    is IrType.Pointer -> TypeRef.Pointer(typeRefOf(type.inner), type.mutable)
    is IrType.Named -> TypeRef.Named(type.name, type.args.mapIndexed { index, argument ->
        type.constArgs.getOrNull(index)?.let { TypeRef.Const(it) } ?: typeRefOf(argument)
    })
}
