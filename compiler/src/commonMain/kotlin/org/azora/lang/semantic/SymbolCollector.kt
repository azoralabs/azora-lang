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
import org.azora.lang.frontend.MemberCallStyle
import org.azora.lang.frontend.NumericSuffix
import org.azora.lang.frontend.ParamModifier
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef
import org.azora.lang.frontend.UseAsTemplate
import org.azora.lang.frontend.Visibility
import org.azora.lang.ir.IrType
import org.azora.lang.ir.mangleMethodSymbol

/**
 * Semantic Pass 1 - Symbol Collection.
 *
 * Walks all top-level declarations and registers function signatures
 * in the [SymbolTable]. Does NOT look inside function bodies - that
 * happens in [TypeResolver] (Pass 2).
 */
/** A global binding being re-inferred once every pack is known. */
private data class Global(
    val name: String,
    val initializer: org.azora.lang.frontend.Expr,
    val annotated: org.azora.lang.frontend.TypeRef?,
    val mutable: Boolean,
)

class SymbolCollector {
    private var typeFunctions = emptyList<org.azora.lang.frontend.TypeFunctionDecl>()
    /** Set for the duration of [collect]; lets return-type inference resolve call/ctor types. */
    private var symbolTable: SymbolTable? = null

    /** The index of [typeParams] that [ref] names outright, or `-1`. */
    private fun typeParamIndexOf(ref: TypeRef, typeParams: List<String>): Int =
        typeParams.indexOf((ref as? TypeRef.Named)?.takeIf { it.args.isEmpty() }?.name ?: "")

    private fun resolveType(ref: TypeRef, typeParams: Set<String> = emptySet()): IrType =
        IrType.resolve(TypeFunctionEvaluator.resolve(ref, typeFunctions, unresolvedParams = typeParams), typeParams)

    /**
     * Whether [member] is an operator rather than a named member.
     *
     * Operators are registered as `oper<symbol>`, except the three reached by
     * their bare names - indexing, index-assignment and slicing.
     */
    private fun isOperatorMember(member: String): Boolean =
        member.startsWith("oper") || member in setOf("index", "indexSet", "slice")

    private fun callbackTraitMethodName(traitName: String, traitArgs: List<TypeRef>, callback: org.azora.lang.frontend.SpecCallback? = null): String {
        callback?.useAsTemplate?.let { template ->
            return UseAsTemplate.expand(template, callback.typeParams, traitArgs)
        }
        return if (traitName.isEmpty()) "callback" else traitName[0].lowercaseChar() + traitName.drop(1)
    }

    /**
     * Reports a name declared by two fields of the same type.
     *
     * Nothing downstream can tell them apart: a field is reached by name, so
     * the second is unreachable and every read of it silently goes to the
     * first - with the first's type, and at the first's offset. That is a
     * miscompile rather than a mistake the author sees, so it is refused here.
     *
     * A templated (`inline for`) or conditional field is exempt: its name is
     * not settled yet, and which of them exist is decided per specialization by
     * [PackSpecializer].
     */
    private fun reportDuplicateFields(
        owner: String,
        fields: List<org.azora.lang.frontend.PackField>,
        line: Int,
        errors: MutableList<String>,
    ) {
        val seen = mutableSetOf<String>()
        for (field in fields) {
            if (field.repeats.isNotEmpty() || field.condition != null) continue
            if (field.name.contains('$')) continue
            if (!seen.add(field.name)) {
                errors.add("line $line: '$owner' declares field '${field.name}' more than once")
            }
        }
    }

    private fun registerBuiltins(table: SymbolTable) {
        // `toString` lives in std as `std::convert::toString`, and `println` as
        // `std::println`; neither is a free builtin, so neither is registered
        // here.
        if (table.lookupFunction("channel") == null) {
            // `channel()` - creates a buffered channel for task-to-task communication.
            // NOTE: still a builtin - relocation to std::concurrency::channel is blocked
            // until Channel.az's Mutex/Queue dependencies are restored (Mutex is currently
            // undefined in the stdlib).
            table.defineFunction(FunctionSymbol("channel", emptyList(), IrType.Named("Channel")))
        }
        if (table.lookupFunction("__dbg") == null) {
            // Debug-build line marker (see frontend.DebugInstrumenter).
            table.defineFunction(FunctionSymbol("__dbg", listOf("line" to IrType.Int), IrType.Unit))
        }
        if (table.lookupFunction("__purge") == null) {
            table.defineFunction(FunctionSymbol("__purge", listOf("value" to IrType.Any), IrType.Unit))
        }
        if (table.lookupFunction("__delay") == null) {
            // `delay ms` suspends the current task for that many milliseconds.
            table.defineFunction(FunctionSymbol("__delay", listOf("ms" to IrType.Any), IrType.Unit))
        }
        if (table.lookupFunction("__launch") == null) {
            // `launch { body }` desugars to __launch(thunk); fire-and-forget, joined at end.
            table.defineFunction(FunctionSymbol("__launch", listOf("thunk" to IrType.Any), IrType.Unit))
        }
        if (table.lookupFunction("async") == null) {
            table.defineFunction(FunctionSymbol("async", listOf("thunk" to IrType.Any), IrType.Task(IrType.Any)))
        }
        // `cancel` lives in std as `std::concurrency::cancel`; it is not a free
        // builtin, so it is not registered here.
    }

    /**
     * Collects all function signatures from the program and registers them
     * in the given symbol table.
     *
     * Built-in functions (e.g. `println`) are registered first, followed by
     * user-defined functions. Return types are either taken from explicit
     * annotations or inferred from return statements.
     *
     * @param program the parsed AST to collect symbols from
     * @param table the symbol table to populate with function signatures
     * @return a list of error messages (empty if collection succeeded)
     */
    fun collect(program: Program, table: SymbolTable): List<String> {
        typeFunctions = program.typeFunctions
        symbolTable = table
        val errors = mutableListOf<String>()
        typeFunctions.groupBy { declaration ->
            declaration.name to declaration.params.map { it.variadic }
        }.values.filter { it.size > 1 }.forEach { duplicates ->
            errors.add("line ${duplicates[1].line}: type function '${duplicates[1].name.substringAfterLast("__")}' already has this overload")
        }
        if (errors.isNotEmpty()) return errors

        // Register built-in functions
        registerBuiltins(table)

        // Register aliases before packs/functions so imported aliases participate
        // in field layout and signature resolution regardless of source order.
        for (item in program.items) {
            if (item is TopLevel.TypeAlias) {
                val resolved = TypeFunctionEvaluator.resolve(item.type, typeFunctions)
                table.defineAlias(item.name, resolved)
                IrType.aliases[item.name] = resolved
            }
        }

        // Register global fin declarations as variables in a global scope
        table.pushScope()
        for (item in program.items) {
            when (item) {
                is TopLevel.FinDecl -> {
                    try {
                        val initType = inferExprType(item.initializer, emptyMap())
                        val type = if (item.type != null) resolveType(item.type)
                                   else initType ?: IrType.Int
                        table.defineVariable(VariableSymbol(item.name, type, mutable = false, visibility = item.visibility))
                    } catch (e: Exception) {
                        errors.add("line ${item.line}: ${e.message}")
                    }
                }
                is TopLevel.VarDecl -> {
                    if (item.threadlocal) {
                        try {
                            val initType = inferExprType(item.initializer, emptyMap())
                            val type = if (item.type != null) resolveType(item.type)
                                       else initType ?: IrType.Int
                            table.defineVariable(
                                VariableSymbol(item.name, type, mutable = true, visibility = item.visibility,
                                    valueMutable = item.valueMutable),
                            )
                        } catch (e: Exception) {
                            errors.add("line ${item.line}: ${e.message}")
                        }
                    }
                }
                is TopLevel.Bridge -> item.values.forEach { value ->
                    try {
                        val type = resolveType(value.type)
                        table.defineVariable(VariableSymbol(value.name, type, mutable = value.mutable))
                    } catch (e: Exception) {
                        errors.add("line ${value.line}: ${e.message}")
                    }
                }
                else -> {}
            }
        }

        // Infix operators declared via `meta .Infix("op")`: a free function named
        // `op` is then callable as `a op b`.
        val infixOps = program.infixOperators

        for (func in program.functions) {
            try {
                val tpSet = func.typeParams.toSet()
                val params = func.params.map { it.name to resolveType(it.type, tpSet) }
                val returnType = when (val rt = func.returnType) {
                    is TypeAnnotation.Explicit -> resolveType(rt.ref, tpSet)
                    // A declaration's return type is never inferred; see
                    // [undeclaredReturnType].
                    is TypeAnnotation.Inferred -> undeclaredReturnType(func, params)
                }
                val paramNames = func.params.map { it.name }
                val defaults = func.params.mapIndexedNotNull { i, p -> p.defaultValue?.let { i to it } }.toMap()
                // A `flow` generator's call returns a list of its (element-type) yields.
                val callReturnType = if (func.isFlow) IrType.Array(returnType) else returnType
                // Variadic only when declared with the `...T` syntax - a plain
                // trailing `[T]` parameter takes an array argument as-is.
                val isVariadic = func.params.lastOrNull()?.variadic == true
                val symbol = FunctionSymbol(
                    name = func.name,
                    params = params,
                    returnType = callReturnType,
                    returnTypeRef = (func.returnType as? TypeAnnotation.Explicit)?.ref,
                    isInline = func.isInline,
                    typeParams = func.typeParams,
                    paramNames = paramNames,
                    defaults = defaults,
                    isVariadic = isVariadic,
                    isTask = func.isTask,
                    isUnsafe = func.isUnsafe,
                    visibility = func.visibility,
                    exclusiveParams = func.params.indices
                        .filterTo(mutableSetOf()) { func.params[it].modifier == ParamModifier.EXCLUSIVE },
                    sharedParams = func.params.indices
                        .filterTo(mutableSetOf()) { func.params[it].modifier == ParamModifier.SHARED },
                    returnedParams = func.params.indices
                        .filterTo(mutableSetOf()) { func.params[it].returnsOwnership },
                )
                table.defineFunction(symbol)
                val shortName = func.name.substringAfterLast("__")
                // A free function named by a bodyless infix macro is callable as an infix
                // method on any receiver; record it under the (short) method name
                // written at call sites (`a to b`), pointing at the real function.
                if (func.isUniversalInfix || shortName in infixOps) table.defineUniversalInfix(shortName, func.name)
            } catch (e: Exception) {
                errors.add("line ${func.line}: ${e.message}")
            }
        }

        // Register bridge (FFI extern) function signatures
        for (item in program.items) {
            if (item is TopLevel.Bridge) {
                for (sig in item.funcs) {
                    try {
                        val tpSet = sig.typeParams.toSet()
                        val params = sig.params.map { it.name to resolveType(it.type, tpSet) }
                        val ret = resolveType(sig.returnType, tpSet)
                        val paramNames = sig.params.map { it.name }
                        table.defineFunction(FunctionSymbol(sig.name, params, ret, false, sig.typeParams, paramNames, emptyMap()))
                    } catch (e: Exception) {
                        errors.add("line ${sig.line}: ${e.message}")
                    }
                }
            }
        }

        // Register solo (singleton struct) declarations
        for (item in program.items) {
            if (item is TopLevel.Solo) {
                try {
                    val tpSet = emptySet<String>()
                    val fields = item.fields.map { field ->
                        // A solo is a singleton and has no type parameters.
                        StructField(
                            field.name,
                            resolveType(field.type, tpSet),
                            field.mutable,
                            field.visibility,
                            field.default,
                            isUnsafe = field.isUnsafe,
                        )
                    }
                    reportDuplicateFields(item.name, item.fields, item.line, errors)
                    table.defineStruct(StructType(item.name, fields, emptyList(), item.visibility))
                    // Register methods as Type_method (like impl)
                    for (method in item.methods) {
                        val mangled = "${item.name}_${method.name}"
                        val params = mutableListOf<Pair<String, IrType>>()
                        params.add(method.receiverName to IrType.Named(item.name))
                        for (p in method.params) params.add(p.name to resolveType(p.type))
                        val returnType = when (val rt = method.returnType) {
                            is TypeAnnotation.Explicit -> resolveType(rt.ref)
                            is TypeAnnotation.Inferred -> undeclaredReturnType(method, params)
                        }
                        table.defineFunction(FunctionSymbol(
                            mangled,
                            params,
                            returnType,
                            method.isInline,
                            paramNames = params.map { it.first },
                            // Offset by one: `self` is parameter 0.
                            defaults = method.params
                                .mapIndexedNotNull { i, p -> p.defaultValue?.let { (i + 1) to it } }
                                .toMap(),
                            visibility = method.visibility,
                            memberCallStyle = method.memberCallStyle,
                            returnTypeRef = (method.returnType as? TypeAnnotation.Explicit)?.ref,
                        ))
                        table.defineMethod(item.name, method.name, mangled)
                    }
                } catch (e: Exception) {
                    errors.add("line ${item.line}: ${e.message}")
                }
            }
        }

        // Register pack (struct) declarations
        for (item in program.items) {
            if (item is TopLevel.Pack) {
                try {
                    val tpSet = item.typeParams.toSet()
                    val fields = item.fields.map { field ->
                        StructField(
                            field.name,
                            resolveType(field.type, tpSet),
                            field.mutable,
                            field.visibility,
                            field.default,
                            typeParamIndexOf(field.type, item.typeParams),
                            isUnsafe = field.isUnsafe,
                        )
                    }
                    reportDuplicateFields(item.name, item.fields, item.line, errors)
                    table.defineStruct(
                        StructType(item.name, fields, item.typeParams, item.visibility, item.isBridge, item.isUnion),
                    )
                } catch (e: Exception) {
                    errors.add("line ${item.line}: ${e.message}")
                }
            }
        }

        // Globals were registered before the packs, so a `fin origin = Point(0, 0)`
        // could not yet see what `Point` is and fell back to Int. Now that every pack
        // is known, re-infer the ones that were left to inference.
        for (item in program.items) {
            val (name, initializer, annotated, mutable) = when {
                item is TopLevel.FinDecl -> Global(item.name, item.initializer, item.type, false)
                item is TopLevel.VarDecl && item.threadlocal ->
                    Global(item.name, item.initializer, item.type, true)
                else -> continue
            }
            if (annotated != null) continue
            val inferred = try {
                inferExprType(initializer, emptyMap())
            } catch (e: Exception) {
                null
            } ?: continue
            table.defineVariable(VariableSymbol(name, inferred, mutable = mutable))
        }

        // Register enum declarations
        for (item in program.items) {
            if (item is TopLevel.Enum) {
                try {
                    table.defineEnum(item.name, item.variants)
                } catch (e: Exception) {
                    errors.add("line ${item.line}: ${e.message}")
                }
            }
        }

        // Register fail (error-set) declarations
        for (item in program.items) {
            if (item is TopLevel.Fail) {
                try {
                    table.defineFail(item.name, item.variants)
                } catch (e: Exception) {
                    errors.add("line ${item.line}: ${e.message}")
                }
            }
        }

        // Register tagged unions (`variant enum` / `variant error`)
        for (item in program.items) {
            if (item is TopLevel.Slot) {
                try {
                    val variants = item.variants.map { v -> v.name to v.payloadTypes.map { resolveType(it) } }
                    table.defineSlot(item.name, variants)
                    // A `variant error` is also an error set: it can be thrown and
                    // named in a `?!` clause, which is the whole point of the
                    // `error` spelling.
                    if (item.isError) table.defineFail(item.name, item.variants.map { it.name })
                } catch (e: Exception) {
                    errors.add("line ${item.line}: ${e.message}")
                }
            }
        }

        // Register impl methods as functions `Type_method(self, ...)`
        val localPackNames = program.localPackNames
        for (item in program.items) {
            if (item is TopLevel.Impl) {
                val struct = table.lookupStruct(item.typeName)
                if (item.isPackImpl && item.typeName !in localPackNames) {
                    errors.add("line ${item.line}: 'impl pack ${item.typeName}' is only allowed in the file that declares pack ${item.typeName}")
                    continue
                }
                val implTypeParams = table.lookupStruct(item.typeName)?.typeParams?.toSet() ?: emptySet()
                for (method in item.methods) {
                    // A method may introduce type parameters of its own -
                    // `func map<R>[self: Self&](f: (T) -> R)`. Only the *type's*
                    // parameters were in scope here, so `R` resolved as an
                    // unknown named type and every use of it failed to match.
                    val tpSet = implTypeParams + method.typeParams
                    val mangled = mangleMethodSymbol("${item.typeName}_${method.name}")
                    try {
                        if (item.isExtension && struct != null && struct.fields.none { it.visibility == Visibility.PUBLIC && !it.name.startsWith("_") } && method.receiverModifier == ParamModifier.EXCLUSIVE) {
                            errors.add("line ${method.line}: pack '${item.typeName}' has no exposed fields, so extension '${method.name}' cannot use mut ref self")
                            continue
                        }
                        // Resolve so primitive impl targets (Int/Double/Char/Bool/…)
                        // lower to their native IR type (e.g. i32), not an erased
                        // Named/pointer type. Struct targets stay Named(<Type>).
                        // `impl<T, N: Int> Array<T, N>` records the type's *name*, not the
                        // arguments it was applied to, so the receiver is rebuilt by
                        // applying the declaration to its own parameters. A type whose
                        // shape depends on them - `Array<T, N>` - is otherwise resolved
                        // bare and rejected for having no arguments.
                        val declaredParams = table.lookupStruct(item.typeName)?.typeParams.orEmpty()
                        val selfRef = if (declaredParams.isEmpty()) {
                            TypeRef.Named(item.typeName)
                        } else {
                            TypeRef.Named(item.typeName, declaredParams.map { TypeRef.Named(it) })
                        }
                        val selfType = resolveType(selfRef, declaredParams.toSet())
                        val params = mutableListOf<Pair<String, IrType>>()
                        params.add(method.receiverName to selfType)
                        // An operator's `by <Spec>` clause names the operand type
                        // (`impl oper== by List<T> for ArrayList { ref self, rhs -> }`),
                        // so the operand param(s) - written without a type in the body
                        // header - take that spec type rather than erasing to Any.
                        val operandType = if (method.name.startsWith("oper") && item.traitName != null) {
                            resolveType(TypeRef.Named(item.traitName!!), tpSet)
                        } else null
                        for (p in method.params) params.add(p.name to (operandType ?: resolveType(p.type, tpSet)))
                        val returnType = when (val rt = method.returnType) {
                            is TypeAnnotation.Explicit -> resolveType(rt.ref, tpSet)
                            // `oper#` (hash) is ULong by contract; its body typically
                            // returns a local accumulator that return-type inference
                            // (params-only) cannot see.
                            is TypeAnnotation.Inferred ->
                                if (method.name == "oper#") IrType.ULong
                                else specMemberReturnType(item, method, program)
                                    ?: undeclaredReturnType(method, params)
                        }
                        // Bridge impls register the member name (so semantic gates like the
                        // range-operator check can find it) but define NO callable function -
                        // the backend lowers bridge operators natively.
                        // How a spec member is *called* is part of its contract,
                        // not of the implementation: a spec's `prop` is reached
                        // without parentheses and its `func` with them, whatever
                        // the impl wrote. Reading it from the declaration is what
                        // keeps `box.extractInt` and `box.extractInt()` from both
                        // resolving.
                        val declaredStyle = item.traitName?.let { traitName ->
                            program.items.filterIsInstance<TopLevel.Spec>()
                                .firstOrNull { it.name == traitName }
                                ?.methods?.firstOrNull { it.name == method.name }
                                ?.memberCallStyle
                        }
                        if (!item.isBridge) {
                            // `self` is parameter 0, so a method's own defaults and
                            // names sit one to the right of where they are written.
                            // Without these a method silently had no defaults at
                            // all, and every argument had to be positional.
                            val methodDefaults = method.params
                                .mapIndexedNotNull { i, p -> p.defaultValue?.let { (i + 1) to it } }
                                .toMap()
                            table.defineFunction(FunctionSymbol(
                                mangled,
                                params,
                                returnType,
                                method.isInline,
                                typeParams = method.typeParams,
                                paramNames = params.map { it.first },
                                defaults = methodDefaults,
                                visibility = method.visibility,
                                memberCallStyle = declaredStyle ?: method.memberCallStyle,
                                returnTypeRef = (method.returnType as? TypeAnnotation.Explicit)?.ref,
                                isBodyless = method.body.isEmpty(),
                            ))
                        }
                        table.defineMethod(item.typeName, method.name, mangled)
                        // `impl Cast<Fahrenheit> for Celsius { prop cast … }` -
                        // the target is a parameter of the impl, so the member is
                        // registered under it. Without the key a type could only
                        // ever convert to one thing: two `Cast` impls would both
                        // be called `cast` and the second would collide.
                        // `func into[…]: T use as "to${T.typeName}"` - the spec
                        // gives its member a second, call-site name expanded
                        // against this impl's type arguments. `Into<String>`
                        // makes `into` also reachable as `toString`, so the
                        // member keeps its canonical name and callers keep the
                        // one they already write.
                        item.traitName?.let { traitName ->
                            val spec = program.items.filterIsInstance<TopLevel.Spec>()
                                .firstOrNull { it.name == traitName }
                            val declared = spec?.methods?.firstOrNull { it.name == method.name }
                            declared?.useAsTemplate?.let { template ->
                                // The spec's own parameter names bind the
                                // template's holes; the impl's arguments fill
                                // them. Read from the declaration rather than
                                // the table, which has not seen this spec yet.
                                val alias = UseAsTemplate.expand(
                                    template,
                                    spec.typeParams,
                                    item.traitArgs,
                                )
                                if (alias.isNotEmpty() && alias != method.name) {
                                    table.defineMethod(item.typeName, alias, mangled)
                                }
                            }
                        }
                        // A declared `ctor` also gets a factory, so `Model(w, h)`
                        // can resolve to it instead of filling fields positionally.
                        // The IR generator emits the body; this is what lets the
                        // call site find it.
                        if (method.name == "ctor" && method.params.isNotEmpty() && !item.isBridge) {
                            table.defineFunction(FunctionSymbol(
                                "__ctor_${item.typeName}_${method.params.size}",
                                params.drop(1),
                                selfType,
                                false,
                                visibility = method.visibility,
                            ))
                        }
                    } catch (e: Exception) {
                        errors.add("line ${method.line}: ${e.message}")
                    }
                }
            }
        }

        // Specs are registered before decorators so bindings support forward
        // references regardless of declaration order.
        val contractNames = mutableSetOf<String>()
        for (item in program.items.filterIsInstance<TopLevel.Spec>()) {
            if (!contractNames.add(item.name)) {
                errors.add("line ${item.line}: duplicate spec or decorator '${item.name}'")
            } else {
                // Capture each requirement's declared return type so member access
                // on a spec-typed value (e.g. `map.size` on a `Map<K,V>`) resolves.
                val tpSet = item.typeParams.toSet()
                val ownPropTypes = item.methods.mapNotNull { m ->
                    val ref = (m.returnType as? TypeAnnotation.Explicit)?.ref
                    if (ref != null) m.name to IrType.resolve(ref, tpSet) else null
                }.toMap()
                // Full signatures so method calls on a spec-typed value type-check
                // and yield the declared (erased) return type.
                val ownMethodSigs = item.methods.associate { m ->
                    val ret = (m.returnType as? TypeAnnotation.Explicit)?.ref?.let { IrType.resolve(it, tpSet) } ?: IrType.Unit
                    val params = m.params.map { IrType.resolve(it.type, tpSet) }
                    m.name to SpecMethodSig(params, ret, m.memberCallStyle == MemberCallStyle.PROPERTY)
                }
                // Spec inheritance (`spec Mutable: Read`): store only own members
                // plus the parent name. Inherited members resolve by walking the
                // parent chain at query time, so registration order (which the
                // stdlib injector may reorder) does not matter. methodNames stays
                // own-only - the `impl … for Type` completeness check requires only
                // this spec's own methods; inherited ones are satisfied by the
                // separate `impl Parent for Type` block.
                val parentNames = item.parents.mapNotNull { (it as? TypeRef.Named)?.name }
                val requiredSpecs = item.requires.mapNotNull { (it as? TypeRef.Named)?.name }
                // A member with a body is *provided*: an implementation that does
                // not write it inherits the spec's, so requiring it would reject
                // exactly the implementations the body exists to serve.
                val methodNames = item.methods.filter { it.body.isEmpty() }.map { it.name }
                table.defineSpec(item.name, methodNames, item.callback, item.typeParams, ownPropTypes, ownMethodSigs, parentNames, requiredSpecs, item.isBridge)
            }
        }
        for (item in program.items.filterIsInstance<TopLevel.Deco>()) {
            if (!contractNames.add(item.name)) {
                errors.add("line ${item.line}: duplicate spec or decorator '${item.name}'")
            } else {
                table.defineDecorator(item.name, item.targets, item.bindings, item.isBridge)
            }
        }
        // `react func` marks a reactive owner. It is a declaration form rather
        // than a decorator, so the flag is on the declaration and no library
        // needs to exist for it to mean something.
        program.functions
            .filter { it.isReactive }
            .forEach { table.markFunctionReactive(it.name) }
        program.items.filterIsInstance<TopLevel.Impl>().forEach { impl ->
            impl.methods
                .filter { it.isReactive }
                .forEach { method -> table.markFunctionReactive("${impl.typeName}_${method.name}") }
        }

        // Validate impl Contract for Type. Every source implementation is manual
        // and therefore carries a body; only compiler-generated derive nodes are
        // bodyless. Specs require their declared methods, while decorators use an
        // explicitly empty body.
        for (item in program.items) {
            if (item is TopLevel.Impl && item.traitName != null) {
                if (!item.hasBody && !item.isDerived) {
                    errors.add(
                        "line ${item.line}: manual implementation 'impl ${item.traitName} for ${item.typeName}' " +
                            "requires a body; add '{ ... }' or request generation with " +
                            "'derive ${item.traitName} for ${item.typeName}'",
                    )
                    continue
                }
                // An oper overload with a `by <Type>` clause (`impl oper== by Map
                // for HashMap`) is not a spec conformance: the `by` type names the
                // operand, not the contract. What separates the two is whether the
                // name is a declared spec - `impl Deref<Int> for Box { oper.* … }`
                // is a real conformance and has to register one, or a spec that
                // declares only operators could never be required by another.
                val isOperOverload = item.methods.any {
                    it.name.startsWith("oper") || it.name in setOf("slice", "index", "indexSet")
                }
                val contract = table.lookupSpec(item.traitName)
                if (isOperOverload && contract == null) continue
                if (contract == null) {
                    // The traitName may be a `by <Type>` annotation on an operator
                    // overload (e.g. `impl oper+ by MapEntry for Type`), not a spec
                    // conformance - skip validation for oper-style methods.
                    val isOperOverload = item.methods.any {
                        it.name.startsWith("oper") || it.name in setOf("slice", "index", "indexSet")
                    }
                    if (!isOperOverload) {
                        errors.add("line ${item.line}: unknown spec or decorator '${item.traitName}'")
                    }
                } else if (contract.isDecorator) {
                    // Decorator impls are validated and expanded by DecoratorResolver.
                    continue
                } else {
                    if ('.' in item.typeName) {
                        errors.add(
                            "line ${item.line}: member and wildcard implementation targets are only allowed for decorators"
                        )
                        continue
                    }
                    if (item.decoratorArgs.isNotEmpty() || item.decoratorNamedArgs.isNotEmpty()) {
                        errors.add(
                            "line ${item.line}: implementation values are only allowed for decorators; " +
                                "'${item.traitName}' is a spec"
                        )
                        continue
                    }
                    val provided = item.methods.map { it.name }.toSet()
                    val required = if (contract.callback != null) {
                        listOf(callbackTraitMethodName(item.traitName, item.traitArgs, contract.callback))
                    } else {
                        contract.methodNames
                    }
                    var complete = true
                    for (req in required) {
                        // A `bridge spec` is compiler-provided: its members have a
                        // default lowering, so an implementor states the capability
                        // and only writes a member when the default is wrong.
                        //
                        // An *operator* member is optional for a different reason
                        // (the operator DIP, §12.2): a spec that groups a family -
                        // `Arithmetic` over `+ - * / %` - exists so a type opens
                        // one impl instead of five, and forcing all of them back
                        // would be worse than the five specs it replaced. The
                        // operators a type did not write simply do not resolve,
                        // and that is reported precisely where one is used, naming
                        // the member. A `func` or `prop` member stays required:
                        // its absence would surface as a worse error at a call.
                        if (req !in provided && !contract.isBridge && !isOperatorMember(req)) {
                            complete = false
                            errors.add("line ${item.line}: '${item.typeName}' does not implement '${item.traitName}.${req}'")
                        }
                    }
                    // An operator impl for a spec the type already conforms to
                    // supplies a member for that conformance rather than opening a
                    // second one: `derive Equal for Loose` then
                    // `impl Equal for Loose { oper== … }` is a derive plus the one
                    // operator written by hand, and the written one wins. Two
                    // duplicate derive requests for one spec are still an error.
                    val refinesExisting = isOperOverload && table.conformsTo(item.typeName, item.traitName)
                    if (complete && !refinesExisting && !table.defineConformance(
                            TraitConformance(item.typeName, item.traitName, item.traitArgs, contract.isDecorator)
                        )
                    ) {
                        errors.add("line ${item.line}: duplicate implementation of '${item.traitName}' for '${item.typeName}'")
                    }
                }
            }
        }

        deriveOwnershipCapabilities(program, table)
        errors.addAll(checkSpecRequirements(program, table))

        errors.addAll(DecoratorResolver().resolve(program, table))

        // `import` declarations act only as visibility gates for bundled-library
        // injection (read by StdlibInjector). They no longer create bare aliases:
        // realm members are reached via their qualified `Realm::name` path, which
        // the parser flattens to the mangled name (`std.math::abs` →
        // `std__math__abs`) registered for the injected item.

        return errors
    }

    /**
     * The return type of a declaration that omits `: Type`.
     *
     * For a `func` or a `prop` this is `Unit`: Azora does not infer a
     * declaration's return type, so an omitted annotation is not "work it out
     * for me" - it *is* the annotation (`DIPs/DO_NOT_INFER_RETURN_TYPE.MD`).
     * `ctor`/`dtor` and bodyless signatures never name a type either, and Unit
     * is already what they mean.
     *
     * An **operator overload** is the exception the rule deliberately leaves
     * out. Its result is fixed by the operator's contract rather than chosen by
     * the author, and some are not nameable at the declaration at all - the
     * deref of a variadic pack (`oper.* { return self.value }`) has no spelling
     * for the member's type - so an operator still reads its result from its
     * body. A lambda infers for the same reason: there is no declaration to read.
     */
    /**
     * Derives `Clone` and `Copy` for packs that did not say.
     *
     * The rules are the ones in the ownership model: a capability is derived
     * when every field already has it. An explicit `impl` always wins - deriving
     * never overrides what the author wrote, and `defineConformance` refuses a
     * duplicate anyway.
     *
     * `Clone` is the common case, so a pack of ordinary
     * fields gets them without ceremony; `Copy` is the restrictive one,
     * because it is what makes duplication *implicit*. A pack holding anything
     * not itself `Copy` - a list, a handle, another non-copyable pack - is
     * left out, so passing it by value asks for `take` or `clone`.
     *
     * Runs to a fixed point: `pack Outer { var inner: Inner }` can only be
     * judged once `Inner` has been.
     */
    /**
     * Enforces every spec's `requires` list against the types that implement it.
     *
     * `spec Copy requires [Clone]` does not make a `Copy`
     * type movable - it refuses to call it `Copy` until it separately is.
     * The capability is therefore always stated at the type, never inferred from
     * a sibling, which is the whole point of spelling it `requires` rather than
     * inheriting.
     *
     * Runs after derivation, so a derived `Clone` satisfies the requirement
     * exactly as a written one does.
     */
    /**
     * The return type a spec already declared for the member [method] satisfies.
     *
     * `impl Clone for Vec2 { func clone[self: Self&]() { … } }` does not
     * repeat `: Vec2`, because the contract said it once: `func clone[…](): Self`.
     * The type is still *declared* - on the spec - so this is not the return-type
     * inference the language rules out; it is reading the signature the
     * implementor is committing to. `Self` resolves to the implementing type.
     */
    private fun specMemberReturnType(
        item: TopLevel.Impl,
        method: FuncDecl,
        program: Program,
    ): IrType? {
        val spec = item.traitName ?: return null
        // Read the spec's own declaration rather than the symbol table: impl
        // members are registered before specs are, so the table does not have it
        // yet at this point.
        val declared = program.items.filterIsInstance<TopLevel.Spec>()
            .firstOrNull { it.name == spec }
            ?.methods?.firstOrNull { it.name == method.name }
            ?.returnType as? TypeAnnotation.Explicit ?: return null
        val ref = declared.ref
        // `Self` on the contract is the implementing type here.
        return if (ref is TypeRef.Named && ref.name == "Self") {
            resolveType(TypeRef.Named(item.typeName))
        } else {
            resolveType(ref)
        }
    }

    private fun checkSpecRequirements(program: Program, table: SymbolTable): List<String> {
        val errors = mutableListOf<String>()
        for (item in program.items) {
            if (item !is TopLevel.Impl || item.traitName == null) continue
            val required = table.lookupSpec(item.traitName)?.requiredSpecs.orEmpty()
            for (requirement in required) {
                if (table.conformsTo(item.typeName, requirement)) continue
                errors.add(
                    "line ${item.line}: '${item.typeName}' cannot implement '${item.traitName}' " +
                        "until it also implements '$requirement'",
                )
            }
        }
        return errors
    }

    private fun deriveOwnershipCapabilities(program: Program, table: SymbolTable) {
        // A plain `enum` case is a name and nothing else, so it has every
        // capability unconditionally.
        for (item in program.items.filterIsInstance<TopLevel.Enum>()) {
            for (capability in listOf("Clone", "Copy")) {
                table.defineConformance(TraitConformance(item.name, capability))
            }
        }
        // A union reinterprets its storage, so copying one field-wise would not
        // preserve what it holds; leave those to an explicit `impl`.
        val packs = program.items.filterIsInstance<TopLevel.Pack>()
            .filterNot { it.isBridge || it.isUnion }
        val slots = program.items.filterIsInstance<TopLevel.Slot>()
        if (packs.isEmpty() && slots.isEmpty()) return
        var changed = true
        while (changed) {
            changed = false
            for (capability in listOf("Clone", "Copy")) {
                for (pack in packs) {
                    if (table.conformsTo(pack.name, capability)) continue
                    if (!pack.fields.all { fieldHasCapability(it.type, capability, table, pack.typeParams.toSet()) }) continue
                    if (table.defineConformance(TraitConformance(pack.name, capability))) changed = true
                }
                // A tagged union carries whichever payload its live case holds,
                // so it has a capability exactly when every payload does.
                for (slot in slots) {
                    if (table.conformsTo(slot.name, capability)) continue
                    val payloads = slot.variants.flatMap { it.payloadTypes }
                    if (!payloads.all { fieldHasCapability(it, capability, table, emptySet()) }) continue
                    if (table.defineConformance(TraitConformance(slot.name, capability))) changed = true
                }
            }
        }
    }

    /**
     * Whether a field of type [ref] already carries [capability].
     *
     * The two capabilities are not equally demanding, and the difference is the
     * point of the model. A deep copy duplicates the whole value, so a field
     * that is a list or a buffer does not block `Clone`. `Copy` is the strict
     * one, because it makes duplication *implicit*: a pack holding a collection
     * is deliberately left out, so handing it over asks for `take` or
     * `.clone()` in writing.
     *
     * A named field always has to carry the capability itself, so a type that
     * opts out propagates that to everything holding it.
     */
    private fun fieldHasCapability(
        ref: TypeRef,
        capability: String,
        table: SymbolTable,
        typeParams: Set<String>,
    ): Boolean = when (ref) {
        // A borrow does not own what it points at, so it never blocks a derive.
        is TypeRef.Reference -> true
        is TypeRef.Nullable -> fieldHasCapability(ref.inner, capability, table, typeParams)
        // A field typed by the pack's own parameter says nothing until the pack
        // is instantiated, so it cannot be what withholds the capability.
        is TypeRef.Named if ref.name in typeParams -> true
        is TypeRef.Named -> when {
            table.conformsTo(ref.name, capability) -> true
            // A type this compilation declares and that does not carry the
            // capability blocks it - an opt-out has to propagate.
            isDeclaredType(ref.name, table) -> false
            // Anything else is a library type (`List<T>`, a handle, a spec).
            // Moving or deep-copying one is fine; making it duplicate
            // *implicitly* is the thing that would be surprising.
            else -> capability != "Copy"
        }
        else -> capability != "Copy"
    }

    /** Whether [name] is a pack, enum or tagged union declared in this compilation. */
    private fun isDeclaredType(name: String, table: SymbolTable): Boolean =
        table.lookupStruct(name) != null || table.lookupEnum(name) != null || table.lookupSlot(name) != null

    private fun undeclaredReturnType(func: FuncDecl, params: List<Pair<String, IrType>>): IrType =
        if (func.name.startsWith("oper")) inferReturnType(func, params) else IrType.Unit

    /**
     * Infer the return type from return statements in the function body.
     * If no return statements exist, the function returns Unit.
     */
    private fun inferReturnType(func: FuncDecl, params: List<Pair<String, IrType>>): IrType {
        val returnExprs = collectReturnExprs(func.body)
        if (returnExprs.isEmpty()) return IrType.Unit

        // Build a name→type map from params, plus top-level local bindings so a
        // `return <local>` (e.g. `var result = hashMapOf(); … ; return result`)
        // can be typed.
        val env = params.toMap().toMutableMap()
        val tpSet = func.typeParams.toSet()
        for (stmt in func.body) {
            val (name, ann, init) = when (stmt) {
                is Stmt.VarDecl -> Triple(stmt.name, stmt.type, stmt.initializer)
                is Stmt.FinDecl -> Triple(stmt.name, stmt.type, stmt.initializer)
                else -> continue
            }
            val t = (ann as? TypeAnnotation.Explicit)?.ref?.let { IrType.resolve(it, tpSet) }
                ?: inferExprType(init, env)
            if (t != null) env[name] = t
        }
        val types = returnExprs.mapNotNull { inferExprType(it, env) }
        if (types.isEmpty()) return IrType.Unit

        // All return types must agree
        val first = types.first()
        if (types.all { it == first }) return first
        error("conflicting return types in function '${func.name}'")
    }

    private fun collectReturnExprs(body: List<Stmt>): List<Expr> {
        val result = mutableListOf<Expr>()
        for (stmt in body) {
            when (stmt) {
                is Stmt.Return -> stmt.value?.let { result.add(it) }
                is Stmt.If -> {
                    result.addAll(collectReturnExprs(stmt.thenBranch))
                    stmt.elseBranch?.let { result.addAll(collectReturnExprs(it)) }
                }
                is Stmt.InlineIf -> {
                    result.addAll(collectReturnExprs(stmt.thenBranch))
                    stmt.elseBranch?.let { result.addAll(collectReturnExprs(it)) }
                }
                is Stmt.Scope -> result.addAll(collectReturnExprs(stmt.body))
                else -> {}
            }
        }
        return result
    }

    /**
     * Simple expression type inference for return type deduction.
     * Only needs to handle literal types and parameter references.
     */
    private fun inferExprType(expr: Expr, env: Map<String, IrType>): IrType? = when (expr) {
        // Its type is whatever context expects; nothing here states one.
        is Expr.InferredMember -> null
        // A keyed macro argument is consumed by the expander; it has no type of
        // its own and never reaches a program.
        is Expr.MapEntryArg -> null
        is Expr.InlineForArgs -> null
        is Expr.InCheck -> IrType.Bool
        is Expr.IntLiteral -> when (expr.suffix) {
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
        is Expr.DoubleLiteral -> when (expr.suffix) {
            NumericSuffix.DECIMAL -> IrType.Decimal
            NumericSuffix.FLOAT -> IrType.Float
            else -> IrType.Double // unsuffixed real literals default to Double
        }
        is Expr.StringLiteral -> IrType.String
        is Expr.BoolLiteral -> IrType.Bool
        is Expr.CharLiteral -> IrType.Char
        is Expr.Identifier -> env[expr.name]
        is Expr.Binary -> {
            when (expr.op) {
                TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL,
                TokenType.LESS, TokenType.LESS_EQUAL,
                TokenType.GREATER, TokenType.GREATER_EQUAL,
                TokenType.AND_AND, TokenType.OR_OR -> IrType.Bool
                TokenType.PLUS -> {
                    val left = inferExprType(expr.left, env)
                    if (left == IrType.String) IrType.String else left
                }
                TokenType.STAR -> {
                    val left = inferExprType(expr.left, env)
                    val right = inferExprType(expr.right, env)
                    if (left == IrType.String || right == IrType.String) IrType.String else left
                }
                else -> inferExprType(expr.left, env)
            }
        }
        is Expr.Unary -> when (expr.op) {
            TokenType.BANG -> IrType.Bool
            else -> inferExprType(expr.operand, env)
        }
        is Expr.Grouping -> inferExprType(expr.expr, env)
        // A struct constructor yields its named type; a known function yields its
        // (erased) declared return type.
        is Expr.Call -> symbolTable?.lookupStruct(expr.callee)?.let { IrType.Named(it.name) }
            ?: symbolTable?.lookupFunction(expr.callee)?.returnType
        is Expr.UpperScopeAccess -> null // can't infer type from upper scope access during symbol collection
        is Expr.Range -> null // ranges are not first-class values
        is Expr.ArrayLiteral -> expr.elements.firstOrNull()?.let { inferExprType(it, env) }?.let(IrType::Array)
        is Expr.SetLiteral -> expr.elements.firstOrNull()?.let { inferExprType(it, env) }?.let(IrType::Set)
        is Expr.MapLit -> expr.entries.firstOrNull()?.let { (key, value) ->
            val keyType = inferExprType(key, env)
            val valueType = inferExprType(value, env)
            if (keyType != null && valueType != null) IrType.Map(keyType, valueType) else null
        }
        is Expr.Member -> {
            val target = inferExprType(expr.target, env)
            when (target) {
                is IrType.Pointer -> (target.inner as? IrType.Named)
                    ?.let { symbolTable?.lookupStruct(it.name)?.field(expr.name)?.type }
                is IrType.Named -> symbolTable?.lookupStruct(target.name)?.field(expr.name)?.type
                else -> null
            }
        }
        is Expr.MethodCall -> {
            val target = inferExprType(expr.target, env) as? IrType.Named
            target?.let { owner ->
                symbolTable?.lookupMethod(owner.name, expr.name)
                    ?.let { symbolTable?.lookupFunction(it)?.returnType }
            }
        }
        is Expr.Index -> null
        is Expr.StringTemplate -> IrType.String
        is Expr.TupleLit, is Expr.TupleAccess, is Expr.VariantLit -> null
        is Expr.CatchExpr -> null
        is Expr.TryPropagate -> inferExprType(expr.expr, env)
        // The seal governs who may write the field, not what type it holds.
        is Expr.Seal -> inferExprType(expr.value, env)
        is Expr.IfExpr -> inferExprType(expr.thenExpr, env)
        is Expr.Lambda -> null
        is Expr.NamedArg -> null
        is Expr.NullLiteral -> IrType.Any
        is Expr.NullCoalesce, is Expr.SafeMember,
        is Expr.Cast, is Expr.IsCheck, is Expr.Alloc, is Expr.AllocBuffer, is Expr.Deref, is Expr.Isolated, is Expr.Await, is Expr.Inject, is Expr.Spread -> null
        // Macros are expanded before symbol collection; unreachable.
        is Expr.MetaInvoke -> null
        is Expr.Slice -> null
    }
}
