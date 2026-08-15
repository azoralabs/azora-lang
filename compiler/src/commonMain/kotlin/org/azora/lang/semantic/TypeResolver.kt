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

import org.azora.lang.frontend.lambdaReceiverName
import org.azora.lang.frontend.OPTIONAL_UNWRAP
import org.azora.lang.frontend.OwnershipOp
import org.azora.lang.frontend.Param
import org.azora.lang.frontend.ParamModifier
import org.azora.lang.frontend.Capture
import org.azora.lang.frontend.CaptureMode
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
import org.azora.lang.ir.sourceSymbol
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

    /** The module the impl under resolution was written in, for the privacy check. */
    private var currentModule: String? = null

    /** Where each pack was declared, so a private member can tell same-module from not. */
    private var packModules: Map<String, String?> = emptyMap()
    private var reactiveContext = false
    /** True while resolving an `async func` body; `loop … by …` requires one. */
    private var asyncContext = false

    private val errors = mutableListOf<String>()
    private var program: Program? = null

    fun resolve(program: Program): List<String> {
        this.program = program
        typePropertyNames = program.typeFunctions.mapTo(mutableSetOf()) { it.name }
        packModules = program.items.filterIsInstance<TopLevel.Pack>()
            .associate { it.name to it.declaringModule }
        // A top-level `fin f: (Int) -> Int = { … }` declares a lambda too, and its
        // declared type is what supplies the lambda's parameter and receiver types.
        // Only the annotation is read when the symbol is collected, so the
        // initializer is resolved here - otherwise the lambda records no type and
        // the lowering has nothing to shape it with.
        for (item in program.items) {
            val declaration: Triple<TypeRef?, Expr, Int> = when (item) {
                is TopLevel.FinDecl -> Triple(item.type, item.initializer, item.line)
                is TopLevel.VarDecl -> Triple(item.type, item.initializer, item.line)
                is TopLevel.LetDecl -> Triple(item.type, item.initializer, item.line)
                else -> continue
            }
            val (annotation, initializer, line) = declaration
            if (initializer !is Expr.Lambda) continue
            val declared = annotation?.let { tryResolveType(it, line) } as? IrType.Function ?: continue
            val savedParams = expectedLambdaParamTypes
            val savedReceivers = expectedLambdaReceiverTypes
            expectedLambdaParamTypes = declared.params
            expectedLambdaReceiverTypes = declared.receivers
            resolveExpr(initializer)
            expectedLambdaParamTypes = savedParams
            expectedLambdaReceiverTypes = savedReceivers
        }
        for (bridge in program.items.filterIsInstance<TopLevel.Bridge>()) {
            for (value in bridge.values) {
                val declared = tryResolveType(value.type, value.line) ?: continue
                val actual = resolveExpr(value.initializer) ?: continue
                if (!isCompatible(declared, actual)) {
                    errors.add(
                        "line ${value.line}: bridge value '${value.name}' has type $declared but initializer is $actual",
                    )
                }
            }
        }
        for (func in program.functions) {
            resolveFunction(func)
        }
        // Resolve test bodies
        for (test in program.tests) {
            table.pushScope()
            val savedTestContext = testContext
            testContext = true
            for (stmt in test.body) resolveStmt(stmt, IrType.Unit)
            testContext = savedTestContext
            table.popScope()
        }
        // Pack defaults are executable expressions too. Callable fields need
        // their declared type as the lambda's inference context.
        for (item in program.items) {
            if (item !is TopLevel.Pack) continue
            val struct = table.lookupStruct(item.name) ?: continue
            table.pushScope()
            for (i in item.fields.indices) {
                val initializer = item.fields[i].default ?: continue
                val declared = struct.fields[i].type
                val savedParams = expectedLambdaParamTypes
                val savedReceivers = expectedLambdaReceiverTypes
                if (initializer is Expr.Lambda && declared is IrType.Function) {
                    expectedLambdaParamTypes = declared.params
                    expectedLambdaReceiverTypes = declared.receivers
                }
                val actual = resolveExpr(initializer)
                expectedLambdaParamTypes = savedParams
                expectedLambdaReceiverTypes = savedReceivers
                if (actual != null && !isCompatible(declared, adoptLiteralType(initializer, actual, declared))) {
                    errors.add(
                        "line ${item.line}: default for '${item.name}.${item.fields[i].name}' " +
                            "expects $declared, got $actual",
                    )
                }
            }
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
                        val mutable = name != "self" || method.receiverModifier != ParamModifier.SHARED
                        table.defineVariable(VariableSymbol(name, type, mutable = mutable))
                    }
                    val savedReceiver = currentReceiverType
                    val savedReactive = reactiveContext
                    val savedModule = currentModule
                    currentReceiverType = item.typeName
                    currentModule = item.declaringModule
                    reactiveContext = method.isReactive
                    val savedAsync = asyncContext
                    asyncContext = method.isTask
                    contextualValues.addLast(
                        listOf(Expr.Identifier("self", method.line, method.column) to IrType.Named(item.typeName)),
                    )
                    val savedUndeclaredReturn = undeclaredReturnOf
                    val savedOrigins = declaredOrigins
                    undeclaredReturnOf = undeclaredReturnName(method)
                    checkBorrowOrigins(method, receiver = method.receiverName)
                    declaredOrigins = returnedBorrowOrigins((method.returnType as? TypeAnnotation.Explicit)?.ref)
                    val savedSuspendable = enterSuspendable(method)
                    val savedBorrowed = borrowedNames
                    borrowedNames = borrowedNamesOf(
                        method,
                        receiver = method.receiverName.takeIf { method.receiverModifier != ParamModifier.NONE },
                    )
                    val savedShared = sharedBorrowedNames
                    sharedBorrowedNames = sharedBorrowedNamesOf(method, receiver = method.receiverName)
                    resolveBody(method.body, func.returnType)
                    borrowedNames = savedBorrowed
                    sharedBorrowedNames = savedShared
                    leaveSuspendable(savedSuspendable)
                    declaredOrigins = savedOrigins
                    undeclaredReturnOf = savedUndeclaredReturn
                    contextualValues.removeLast()
                    currentReceiverType = savedReceiver
                    currentModule = savedModule
                    reactiveContext = savedReactive
                    asyncContext = savedAsync
                    table.popScope()
                }
            }
        }
        return errors
    }

    /**
     * `Alias__member` under the type the alias names, or null.
     *
     * `Vec3f::zero` reaches here as `Vec3f__zero`, and the member lives on the
     * specialization `Vec3f` is an alias for. The alias table is the only place that
     * correspondence exists by this point - the aliases are generated too late for
     * monomorphization to have rewritten the name.
     */
    private fun throughTypeAlias(name: String): String? {
        val separator = name.indexOf("__")
        if (separator <= 0) return null
        val owner = name.substring(0, separator)
        val target = (table.lookupAlias(owner) as? TypeRef.Named)?.name ?: return null
        if (target == owner) return null
        return target + name.substring(separator)
    }

    /** `Self` inside an impl names the type the impl is on. */
    private fun selfToReceiver(name: String): String =
        if (name == "Self") currentReceiverType ?: name else name

    /** True when [type] names a compile-time type property (`deepinline prop`). */
    /**
     * The type of [expr] as far as a local lookup can tell.
     *
     * Only used to notice that something is a union before its member access is
     * resolved, so an identifier is enough - and resolving the expression here
     * would report its errors twice.
     */
    private fun inferredTargetType(expr: Expr): IrType? =
        (expr as? Expr.Identifier)?.let { table.lookupVariable(it.name)?.type }

    /** Names the union [type] refers to, if it is one. */
    private fun unionNameOf(type: IrType?): String? =
        (type as? IrType.Named)?.name?.takeIf { table.lookupStruct(it)?.isUnion == true }

    private fun isTypeProperty(type: TypeRef.Named): Boolean =
        TypeFunctionEvaluator.declarationNameFor(type, typePropertyNames) != null

    /** Names of every declared type property; filled in when [resolve] starts. */
    private var typePropertyNames: Set<String> = emptySet()

    private fun validateSignatureType(ref: TypeRef, line: Int, typeParams: Set<String>): Boolean {
        var valid = true

        fun visit(type: TypeRef) {
            when (type) {
                is TypeRef.Named -> {
                    val known = org.azora.lang.frontend.TypeFunctionCall.isCall(type) ||
                        type.name == "*" ||
                        type.name in typeParams ||
                        // `promote<T, U>` reads as a generic type but names a
                        // `deepinline prop`; only the declaration set can tell.
                        isTypeProperty(type) ||
                        IrType.isPrimitiveName(type.name) ||
                        table.lookupStruct(type.name) != null ||
                        table.lookupEnum(type.name) != null ||
                        table.lookupFail(type.name) != null ||
                        table.lookupSpec(type.name) != null ||
                        table.lookupAlias(type.name) != null ||
                        table.lookupSlot(type.name) != null
                    if (!known) {
                        errors.add("line $line: undefined type '${type.displayName()}'")
                        valid = false
                    }
                    type.args.forEach(::visit)
                }
                is TypeRef.Array -> visit(type.element)
                is TypeRef.Map -> {
                    visit(type.key)
                    visit(type.value)
                }
                is TypeRef.Set -> visit(type.element)
                is TypeRef.Function -> {
                    type.params.forEach(::visit)
                    type.receivers.forEach(::visit)
                    visit(type.ret)
                }
                is TypeRef.Tuple -> type.elements.forEach(::visit)
                is TypeRef.Nullable -> visit(type.inner)
                is TypeRef.Failable -> visit(type.ok)
                is TypeRef.Pointer -> visit(type.inner)
                is TypeRef.Reference -> visit(type.inner)
                is TypeRef.Const -> {}
            }
        }

        visit(ref)
        return valid
    }

    private fun resolveFunction(func: FuncDecl) {
        val symbol = table.lookupFunction(func.name) ?: return
        val typeParams = func.typeParams.toSet()
        var signatureValid = true
        for (param in func.params) {
            if (!validateSignatureType(param.type, func.line, typeParams)) signatureValid = false
        }
        val explicitReturn = func.returnType as? TypeAnnotation.Explicit
        if (explicitReturn != null &&
            !validateSignatureType(explicitReturn.ref, func.line, typeParams)
        ) {
            signatureValid = false
        }
        if (!signatureValid) return

        table.pushScope()

        // A const type parameter (`func axis<I: Int>`) is an integer the body may
        // read. Its value is only known at a call site, but its type is not, so it is
        // in scope here - otherwise the declaration cannot be checked at all.
        for (constParam in func.constParams) {
            table.defineVariable(VariableSymbol(constParam, IrType.Int, mutable = false))
        }

        // Register parameters as local variables
        for (i in symbol.params.indices) {
            val (name, type) = symbol.params[i]
            // Only an exclusive borrow (`x!`) lets the callee write through it.
            val modifier = func.params.getOrNull(i)?.modifier ?: ParamModifier.NONE
            val mutable = modifier.writable
            table.defineVariable(VariableSymbol(name, type, mutable))
        }

        // `T ?! ErrSet` enforcement: track the function's declared error set so that
        // `fail`/`throw` of an error variant can be checked against it.
        val savedFailSets = declaredFailSets
        declaredFailSets = (func.returnType as? TypeAnnotation.Explicit)
            ?.ref?.let { (it as? TypeRef.Failable)?.errSets }
        val savedUnsafe = unsafeContext
        val savedReceiver = currentReceiverType
        val savedFuncTypeParams = currentFuncTypeParams
        val savedReactive = reactiveContext
        unsafeContext = func.isUnsafe
        currentReceiverType = null
        currentFuncTypeParams = func.typeParams.toSet()
        reactiveContext = func.isReactive
        asyncContext = func.isTask
        val savedTestContext = testContext
        // A test-realm declaration may use its siblings: it is already test-only.
        if (program?.testRealmMembers?.containsKey(func.name) == true) testContext = true
        val savedUndeclaredReturn = undeclaredReturnOf
        val savedOrigins = declaredOrigins
        val savedMoves = movedBindings.toMap()
        movedBindings.clear()
        undeclaredReturnOf = undeclaredReturnName(func)
        checkBorrowOrigins(func, receiver = null)
        declaredOrigins = returnedBorrowOrigins((func.returnType as? TypeAnnotation.Explicit)?.ref)
        val savedSuspendable = enterSuspendable(func)
        val savedBorrowed = borrowedNames
        borrowedNames = borrowedNamesOf(func, receiver = null)
        val savedShared = sharedBorrowedNames
        sharedBorrowedNames = sharedBorrowedNamesOf(func, receiver = null)
        resolveBody(func.body, symbol.returnType)
        borrowedNames = savedBorrowed
        sharedBorrowedNames = savedShared
        leaveSuspendable(savedSuspendable)
        declaredOrigins = savedOrigins
        undeclaredReturnOf = savedUndeclaredReturn
        movedBindings.clear()
        movedBindings.putAll(savedMoves)
        currentReceiverType = savedReceiver
        currentFuncTypeParams = savedFuncTypeParams
        unsafeContext = savedUnsafe
        reactiveContext = savedReactive
        testContext = savedTestContext
        declaredFailSets = savedFailSets

        table.popScope()
    }

    /** Error sets declared by the function currently being resolved. */
    private var declaredFailSets: List<String>? = null

    /**
     * The borrow origins the current function's return type names, or null when
     * it returns no borrow or names no origin.
     *
     * `func first(a: String&, b: String&): String&[a]` promises the caller that
     * what comes back is borrowed from `a` and nothing else, so a `return b`
     * breaks the signature even though both have the same type.
     */
    private var declaredOrigins: List<String>? = null

    /** The `[a, b]` on a returned borrow, looking through `?!` and `?`. */
    private fun returnedBorrowOrigins(ref: TypeRef?): List<String>? = when (ref) {
        is TypeRef.Reference -> ref.origins.takeIf { it.isNotEmpty() }
        is TypeRef.Failable -> returnedBorrowOrigins(ref.ok)
        is TypeRef.Nullable -> returnedBorrowOrigins(ref.inner)
        else -> null
    }

    /** Every origin named anywhere in [ref], including inside a tuple or generic. */
    private fun allBorrowOrigins(ref: TypeRef?): List<String> = when (ref) {
        null -> emptyList()
        is TypeRef.Reference -> ref.origins + allBorrowOrigins(ref.inner)
        is TypeRef.Failable -> allBorrowOrigins(ref.ok)
        is TypeRef.Nullable -> allBorrowOrigins(ref.inner)
        is TypeRef.Array -> allBorrowOrigins(ref.element)
        is TypeRef.Set -> allBorrowOrigins(ref.element)
        is TypeRef.Map -> allBorrowOrigins(ref.key) + allBorrowOrigins(ref.value)
        is TypeRef.Tuple -> ref.elements.flatMap { allBorrowOrigins(it) }
        is TypeRef.Named -> ref.args.flatMap { allBorrowOrigins(it) }
        is TypeRef.Pointer -> allBorrowOrigins(ref.inner)
        is TypeRef.Function -> ref.params.flatMap { allBorrowOrigins(it) } + allBorrowOrigins(ref.ret)
        else -> emptyList()
    }

    /**
     * Checks that every origin a signature names is something it can borrow from.
     *
     * An origin is a promise about *which input* the result points into, so it
     * has to name one - a parameter passed by borrow, or the receiver. A name
     * that is not there, or one passed by value, cannot outlive the call and so
     * cannot be an origin.
     */
    private fun checkBorrowOrigins(decl: FuncDecl, receiver: String?) {
        val declared = allBorrowOrigins((decl.returnType as? TypeAnnotation.Explicit)?.ref)
        if (declared.isEmpty()) return
        val borrowed = decl.params
            .filter { it.type is TypeRef.Reference || it.modifier != ParamModifier.NONE }
            .map { it.name }.toSet() + listOfNotNull(receiver)
        for (origin in declared.distinct()) {
            if (origin in borrowed) continue
            val byValue = decl.params.any { it.name == origin }
            errors.add(
                if (byValue) {
                    "line ${decl.line}: '${decl.name}' cannot borrow from '$origin' - it is passed by value, " +
                        "so it does not outlive the call; declare it as a borrow to return one"
                } else {
                    "line ${decl.line}: '${decl.name}' names borrow origin '$origin', which is not one of " +
                        "its borrowed parameters" + (if (borrowed.isEmpty()) "" else " (${borrowed.joinToString(", ")})")
                },
            )
        }
    }

    /**
     * The binding a returned borrow reads from, or null when it is not rooted in
     * one - a temporary, a call result, or anything else with no named source.
     */
    private fun borrowRoot(expr: Expr): String? = when (expr) {
        is Expr.Identifier -> expr.name
        is Expr.Isolated -> if (expr.op.isBorrow) borrowRoot(expr.value) else null
        is Expr.Member -> borrowRoot(expr.target)
        is Expr.Index -> borrowRoot(expr.target)
        else -> null
    }

    /**
     * Checks a `return` against the origins its signature named.
     *
     * Only a return rooted in a named binding is judged: anything else has no
     * origin to compare, and rejecting it here would be guessing.
     */
    private fun checkReturnedOrigin(value: Expr, line: Int) {
        val declared = declaredOrigins ?: return
        for (branch in returnedBranches(value)) {
            val root = borrowRoot(branch) ?: continue
            if (root in declared) continue
            if (table.lookupVariable(root) == null) continue
            errors.add(
                "line $line: returns a borrow of '$root', but the signature says the result is " +
                    "borrowed from ${declared.joinToString(" or ") { "'$it'" }}",
            )
        }
    }

    /**
     * Names bound to a borrow by the current signature - borrowed parameters and
     * a borrowed receiver.
     *
     * A borrow owns nothing, so these are the names that cannot give a value
     * away. A borrow held by a local binding is tracked separately, in
     * [activeBorrows], because that one has a lifetime to reason about.
     */
    private var borrowedNames: Set<String> = emptySet()

    /** The subset of [borrowedNames] bound by a *shared* borrow, which cannot be written. */
    private var sharedBorrowedNames: Set<String> = emptySet()

    /**
     * Borrowed parameters of the suspendable function being resolved.
     *
     * Empty for an ordinary function: nothing there suspends, so a borrow lives
     * for the whole call and needs no tracking.
     */
    private var suspendableBorrows: Set<String> = emptySet()

    /** Whether the walk has passed a suspension point in the current body. */
    private var pastSuspension = false

    /** Borrows already reported past a suspension, so each is named once. */
    private val reportedSuspended = mutableSetOf<String>()

    /**
     * Records that the walk has reached an `await` or a `delay` (§15).
     *
     * Everything after one runs at a later time, on the far side of a
     * suspension, which is what makes a borrow taken before it unprovable.
     */
    private fun noteSuspension() {
        if (suspendableBorrows.isNotEmpty()) pastSuspension = true
    }

    /**
     * Rejects reading a borrowed parameter after a suspension (§15).
     *
     * The caller's value has to stay alive and unmoved for as long as the task
     * stays suspended, and nothing in the signature says it does. Rather than
     * assume it, the three ways to make the lifetime explicit are offered.
     */
    private fun checkBorrowAcrossSuspension(name: String, line: Int) {
        if (!pastSuspension || name !in suspendableBorrows) return
        if (!reportedSuspended.add(name)) return
        errors.add(
            "line $line: '$name' is borrowed across a suspension point - the caller's value " +
                "may not outlive it; transfer ownership with 'take $name', create an independent " +
                "value with '$name.clone()', or end the borrow before the suspension",
        )
    }

    /** Saved suspension state, so a nested body restores what enclosed it. */
    private data class SuspensionState(val borrows: Set<String>, val past: Boolean, val reported: Set<String>)

    private fun enterSuspendable(decl: FuncDecl): SuspensionState {
        val saved = SuspensionState(suspendableBorrows, pastSuspension, reportedSuspended.toSet())
        suspendableBorrows = suspendableBorrowsOf(decl)
        pastSuspension = false
        reportedSuspended.clear()
        return saved
    }

    private fun leaveSuspendable(saved: SuspensionState) {
        suspendableBorrows = saved.borrows
        pastSuspension = saved.past
        reportedSuspended.clear()
        reportedSuspended.addAll(saved.reported)
    }

    /** Every name [decl] binds to a borrow: its borrowed parameters and receiver. */
    private fun sharedBorrowedNamesOf(decl: FuncDecl, receiver: String?): Set<String> =
        decl.params.filter { it.modifier == ParamModifier.SHARED || it.type is TypeRef.Reference }
            .filter { it.modifier != ParamModifier.EXCLUSIVE }
            .mapTo(mutableSetOf()) { it.name }
            .also { names -> if (decl.receiverModifier == ParamModifier.SHARED) receiver?.let(names::add) }

    private fun borrowedNamesOf(decl: FuncDecl, receiver: String?): Set<String> =
        decl.params.filter { it.type is TypeRef.Reference || it.modifier != ParamModifier.NONE }
            .mapTo(mutableSetOf()) { it.name }
            .also { names -> receiver?.let(names::add) }

    /** The borrowed parameters [decl] suspends with, or none when it cannot suspend. */
    private fun suspendableBorrowsOf(decl: FuncDecl): Set<String> =
        if (!decl.isTask) emptySet()
        else decl.params
            .filter { it.type is TypeRef.Reference || it.modifier != ParamModifier.NONE }
            .map { it.name }
            .toSet()

    /** The values a `return` may actually yield, looking through `if`/`else`. */
    private fun returnedBranches(value: Expr): List<Expr> = when (value) {
        is Expr.IfExpr -> returnedBranches(value.thenExpr) + returnedBranches(value.elseExpr)
        else -> listOf(value)
    }

    /** Type parameters of the function currently being resolved (erased to `Any` in types). */
    private var currentFuncTypeParams: Set<String> = emptySet()

    /**
     * The type of the member [name] declared on the meta-type, or null.
     *
     * `Type` is the type of a type, so its members are what a type parameter can
     * be asked. Read from the declaration rather than from the method table: a
     * `bridge` member has no body, so it registers a name and no callable, and
     * the declared result is the only place its type is written down.
     */
    private fun typeMetaMember(name: String): IrType? {
        val declaring = program?.items?.filterIsInstance<TopLevel.Impl>()?.firstOrNull { impl ->
            (impl.typeName == "Type" || impl.typeName.endsWith("__Type")) &&
                impl.methods.any { it.name == name }
        }
        val member = declaring?.methods?.first { it.name == name }
            // A realm's members are lifted out of their impl, so the meta-type's
            // are reached the same way any other realm member is.
            ?: program?.functions?.firstOrNull {
                it.memberCallStyle == MemberCallStyle.PROPERTY &&
                    (it.name == name || it.name.endsWith("__$name"))
            }
            ?: return null
        return (member.returnType as? TypeAnnotation.Explicit)?.let { tryResolveType(it.ref, 0) }
    }

    /**
     * The result of a meta-type member when its declaration did not reach here.
     *
     * `impl Type` lives in the standard library, and injection is driven by what
     * a program refers to - nothing refers to the meta-type by name, so the
     * declaration is not pulled in and [typeMetaMember] finds nothing to read.
     * The member's own contract is what stands in until it is.
     */
    private fun typeMetaMemberFallback(name: String): IrType? = when (name) {
        "typeName" -> IrType.String
        else -> null
    }

    /**
     * True while resolving something a test-realm declaration may be used from:
     * a `test` block, or another declaration that is itself in a test realm.
     */
    private var testContext = false

    /**
     * A `realm test` member is ordinary code that only tests may refer to.
     *
     * Enforcing it at the call site is what makes the realm a visibility rule
     * rather than a naming convention - the fixture is emitted like any other
     * declaration, and cannot be reached by the program it exists to test.
     */
    /**
     * One open lambda body, and what it is allowed to see of the scope around it.
     *
     * @property floor the scope depth the lambda's own bindings start at; anything
     *   declared below it (but above the globals in scope 0) belongs to the
     *   enclosing scope and needs a capture
     * @property defaultMode the mode after `;`, applied lazily to used free variables
     * @property inferred source names actually selected by that default
     */
    private data class LambdaFrame(
        val floor: Int,
        val defaultMode: CaptureMode?,
        val inferred: MutableMap<String, CaptureMode> = linkedMapOf(),
    )

    private val lambdaFrames = mutableListOf<LambdaFrame>()

    /**
     * A lambda reaches the scope around it only through its capture list.
     *
     * Capture is never implicit: `[; =]`, `[; &]`, `[; !]`, and `[; take]` ask
     * for it, and writing no capture section asks for none (LAMBDA_CONTEXT_CAPTURE_DIP.MD
     * §4.5). Globals are not captured - they belong to the program, not to the
     * scope the closure was made in - so scope 0 is always visible.
     */
    private fun checkCapture(name: String, line: Int): CaptureMode? {
        if (lambdaFrames.isEmpty()) return null
        val index = table.variableScopeIndex(name) ?: return null
        if (index == 0) return null
        val outer = table.lookupVariable(name) ?: return null
        // Outermost first, and *every* frame the binding has to cross - not just
        // the innermost. A value read two closures deep passes two boundaries,
        // and each closure can only hand on what it captured itself. Recording
        // it on the innermost frame alone leaves the outer closure without the
        // binding, so the inner environment is built where the name does not
        // exist: the read then falls back to a module global, which either
        // fails to link or silently yields nothing.
        var mode: CaptureMode? = null
        for (frame in lambdaFrames) {
            if (index >= frame.floor) continue
            val already = frame.inferred[name]
            if (already != null) {
                mode = already
                continue
            }
            val default = frame.defaultMode
            if (default == null) {
                errors.add(
                    "line $line: '$name' is not in scope - a lambda with no capture list cannot " +
                        "reach the scope around it; write '[; $name.&]' to reference it, " +
                        "'[; $name.!]' to write it, '[; $name]' to copy it, or '[; &]' to capture " +
                        "what the body reads",
                )
                return null
            }
            val capture = Capture(name, name, default, line)
            checkCaptureMode(capture, outer.type)
            frame.inferred[name] = default
            mode = default
        }
        val selected = mode ?: return null
        table.defineVariable(capturedSymbol(Capture(name, name, selected, line), outer))
        return selected
    }

    private fun capturedSymbol(capture: Capture, outer: VariableSymbol): VariableSymbol = when (capture.mode) {
        CaptureMode.SHARED -> VariableSymbol(capture.name, outer.type, mutable = false, valueMutable = false)
        CaptureMode.MUTABLE -> VariableSymbol(
            capture.name,
            outer.type,
            mutable = outer.mutable,
            valueMutable = outer.valueMutable,
        )
        CaptureMode.COPY, CaptureMode.CLONE, CaptureMode.MOVE ->
            VariableSymbol(capture.name, outer.type, mutable = true, valueMutable = true)
    }

    private fun requireTestCaller(name: String, line: Int): Boolean {
        if (testContext) return true
        val visibility = program?.testRealmMembers?.get(name) ?: return true
        val reach = when (visibility.reach) {
            Visibility.Reach.CONFINE -> "this file"
            Visibility.Reach.PROTECT -> "this folder"
            Visibility.Reach.PUBLIC -> "any file"
        }
        errors.add(
            "line $line: '${name.substringAfterLast("__")}' is declared in a 'realm test' " +
                "and can only be used from a test in $reach",
        )
        return false
    }

    /**
     * [args] with a trailing lambda moved onto the last parameter.
     *
     * `k { … }` where `k(a: Int = 3, body: () -> R)` writes the lambda in the
     * position of `a`. Read positionally that is what it is - but a block after
     * the parentheses is the *body* argument by construction, and the earlier
     * parameters are exactly what defaults are for. Naming it here lets the
     * ordinary named-argument path fill the gap, so the rule lives in one place
     * and is the same for free functions and methods.
     *
     * Only applies when the call is short an argument and the last parameter
     * actually takes a function, so a genuinely positional lambda is untouched.
     */
    private fun bindTrailingLambda(
        args: List<Expr>,
        params: List<Pair<String, IrType>>,
        offset: Int,
    ): List<Expr> {
        val declared = params.size - offset
        if (args.size >= declared || args.isEmpty()) return args
        if (args.last() !is Expr.Lambda) return args
        if (params.last().second !is IrType.Function) return args
        if (args.any { it is Expr.NamedArg && it.name == params.last().first }) return args
        val lambda = args.last()
        return args.dropLast(1) + Expr.NamedArg(params.last().first, lambda, lambda.line, lambda.column)
    }

    /**
     * A method call's arguments placed against the method's own parameters.
     *
     * The result is indexed by parameter, excluding `self`, and holds `null`
     * wherever the call supplied nothing - which the caller then accepts or
     * rejects depending on whether that parameter has a default. Named and
     * positional arguments mix as they do at a free call: a named one takes its
     * parameter, a positional one fills the leftmost parameter no name claimed.
     *
     * Returns `null` when the call is malformed and an error has been reported.
     */
    private fun positionMethodArguments(
        expr: Expr.MethodCall,
        func: FunctionSymbol,
        declared: Int,
    ): List<Expr?>? {
        val supplied = bindTrailingLambda(expr.args, func.params, offset = 1)
        if (supplied.none { it is Expr.NamedArg }) {
            if (supplied.size > declared) return supplied
            return supplied + List(declared - supplied.size) { null }
        }
        val names = func.params.drop(1).map { it.first }
        val slots = arrayOfNulls<Expr>(declared)
        for (argument in supplied) {
            if (argument !is Expr.NamedArg) continue
            val index = names.indexOf(argument.name)
            if (index < 0) {
                errors.add("line ${expr.line}: method '${expr.name}' has no parameter '${argument.name}'")
                return null
            }
            if (slots[index] != null) {
                errors.add("line ${expr.line}: parameter '${argument.name}' given twice")
                return null
            }
            slots[index] = argument.value
        }
        var next = 0
        for (argument in supplied) {
            if (argument is Expr.NamedArg) continue
            while (next < slots.size && slots[next] != null) next++
            if (next >= slots.size) {
                errors.add("line ${expr.line}: too many arguments for method '${expr.name}'")
                return null
            }
            slots[next] = argument
        }
        return slots.toList()
    }

    /**
     * Records [expected] for a `.`-headed receiver chain, if that is what
     * [argument] is.
     */
    private fun seedInferredReceiver(argument: Expr, expected: IrType?) {
        val owner = when (expected) {
            is IrType.Named -> expected.name
            is IrType.Nullable -> (expected.inner as? IrType.Named)?.name
            else -> null
        } ?: return
        var head: Expr = argument
        while (true) {
            head = when (head) {
                is Expr.MethodCall -> head.target
                is Expr.Member -> head.target
                else -> break
            }
        }
        if (head is Expr.InferredMember) {
            table.defineInferredMember(head.line, head.column, owner)
        }
    }

    /**
     * Resolves `.Name` against the type expected where it was written.
     *
     * Records the type it chose so lowering reads the same answer rather than
     * deriving it a second time from a context it no longer has.
     */
    private fun resolveInferredMember(expr: Expr.InferredMember, expected: IrType?): IrType? {
        val owner = when (expected) {
            is IrType.Named -> expected.name
            is IrType.Nullable -> (expected.inner as? IrType.Named)?.name
            // An annotation argument was matched to its field before this pass,
            // so the type it chose is already recorded.
            else -> table.lookupInferredMember(expr.line, expr.column)
        }
        if (owner == null) {
            errors.add(
                "line ${expr.line}: cannot tell what '.${expr.name}' belongs to here - " +
                    "nothing states the type at this position, so write it: 'Type.${expr.name}'",
            )
            return null
        }
        table.defineInferredMember(expr.line, expr.column, owner)
        expr.ctorArgs?.let { args ->
            // `.(a, b)` builds the type; `.Variant(a)` builds one of its variants.
            return if (expr.name.isEmpty()) {
                resolveExpr(Expr.Call(owner, args, expr.line, expr.column, owner.length))
            } else {
                resolveExpr(
                    Expr.MethodCall(
                        Expr.Identifier(owner, expr.line, expr.column, owner.length),
                        expr.name, args, expr.line, expr.column,
                    ),
                )
            }
        }
        return resolveExpr(
            Expr.Member(
                Expr.Identifier(owner, expr.line, expr.column, owner.length),
                expr.name,
                expr.line,
                expr.column,
                expr.length,
            ),
        )
    }

    private fun requireReactiveCaller(function: FunctionSymbol, line: Int): Boolean {
        if (!function.isReactive || reactiveContext) return true
        errors.add(
            "line $line: reactive function '${function.name.substringAfterLast("__")}' " +
                "can only be called from a 'react func' or 'react async func'",
        )
        return false
    }

    /**
     * The type a field really has, given the arguments on the referring type.
     *
     * A field declared as a type parameter resolves to `Any`, so `Box<Double>.value`
     * would otherwise type as `Any` and be consumed as an opaque word. The
     * referring type still carries `Double`, which is the answer.
     */
    private fun substituteFieldType(
        struct: StructType,
        fieldName: String,
        referring: IrType.Named,
        declared: IrType,
    ): IrType {
        if (referring.args.isEmpty() || struct.typeParams.isEmpty()) return declared
        val position = struct.field(fieldName)?.typeParamIndex ?: -1
        if (position < 0 || position >= referring.args.size) return declared
        return referring.args[position]
    }

    /**
     * Whether [name] on [ownerType] is reachable from where we are.
     *
     * A leading underscore makes a member private to the type and module that
     * declare it.
     */
    private fun canAccessMember(ownerType: String, name: String, visibility: Visibility): Boolean = when {
        // A leading underscore keeps a member inside the module that declares
        // the type. An `impl` written elsewhere - an extension, or another
        // package's addition - sees only the public surface.
        name.startsWith("_") -> currentReceiverType == ownerType && inOwningModule(ownerType)
        visibility.reach == Visibility.Reach.CONFINE -> currentReceiverType == ownerType
        else -> true
    }

    private fun inOwningModule(ownerType: String): Boolean {
        val owner = packModules[ownerType] ?: return true // unknown provenance: don't invent a rule
        return owner == currentModule
    }

    private fun reportInaccessible(line: Int, kind: String, ownerType: String, name: String, visibility: Visibility) {
        val reason =
            if (name.startsWith("_")) "private $kind '$name' of $ownerType - the leading underscore keeps the member inside the type and module that declare $ownerType"
            else "confined $kind '$name' of $ownerType"
        errors.add("line $line: cannot access $reason")
    }

    /**
     * Expected parameter types for a lambda argument, inferred from context (the
     * function-parameter type the lambda is passed as). Each untyped lambda
     * parameter - the implicit `it`, or an explicitly named `{ it -> … }` /
     * `{ a, b -> … }` - is seeded from the entry at its position. Consumed by
     * `Expr.Lambda` resolution.
     */
    private var expectedLambdaParamTypes: List<IrType>? = null
    private var expectedLambdaReceiverTypes: List<IrType>? = null
    private val contextualValues = ArrayDeque<List<Pair<Expr, IrType>>>()

    /**
     * When non-null, return-value types inside the body being resolved are appended here
     * (used to infer a lambda's return type from its body, after locals are in scope).
     * When null, `return` statements are validated against the enclosing function's declared type.
     */
    private var lambdaReturnTypes: MutableList<IrType>? = null

    /**
     * The `func`/`prop` being checked, when it omitted its return type.
     *
     * An omitted return type means `Unit`, so a `return <value>` inside is a type
     * error either way - but the useful thing to say is "declare the return
     * type", not "expected Unit". Null when the declaration wrote one.
     */
    private var undeclaredReturnOf: String? = null

    /**
     * [undeclaredReturnOf] for [decl] - its name when it wrote no return type.
     *
     * An operator is left out: its result is fixed by the operator's contract
     * rather than written by the author, so it still reads its type from its
     * body (see SymbolCollector.undeclaredReturnType).
     */
    private fun undeclaredReturnName(decl: FuncDecl): String? =
        decl.name.takeIf { !decl.returnTypeDeclared && !it.startsWith("oper") }

    /**
     * The synthetic name of an inherited lambda receiver.
     *
     * A lambda that inherits its receivers never names them, but every later pass
     * has to agree on what they are called; deriving the name from the lambda's
     * own position keeps the frontend and IR generator in step without threading
     * a counter between them.
     */
    private fun lambdaReceiverRef(lambda: Expr.Lambda, index: Int): Expr.Identifier =
        Expr.Identifier(lambdaReceiverName(lambda.line, lambda.column, index), lambda.line, lambda.column)

    private fun inferredContexts(expected: List<IrType>): List<Pair<Expr, IrType>>? {
        if (expected.isEmpty()) return emptyList()
        val available = contextualValues.asReversed().flatten()
        val used = mutableSetOf<Int>()
        return expected.map { wanted ->
            val index = available.indices.firstOrNull {
                it !in used && isCompatible(wanted, available[it].second)
            } ?: return null
            used.add(index)
            available[index]
        }
    }

    private fun resolveCallableArguments(
        label: String,
        function: IrType.Function,
        args: List<Expr>,
        line: Int,
        explicitReceivers: List<IrType> = emptyList(),
    ): IrType? {
        if (function.variadic) {
            args.forEachIndexed { index, argument ->
                val expected = function.params.getOrNull(index) ?: function.params.lastOrNull()
                resolveContextualArgument(argument, expected) ?: return null
            }
            return function.ret
        }
        // A contextual receiver is not an argument. A call's arguments are its
        // parameters; receivers come from a `with` block or from the receiver call
        // syntax (`2.scale(7)`, `[2, 3].add()`), never from the argument list.
        if (args.size != function.params.size) {
            val receiverNote = if (function.receivers.isEmpty()) {
                ""
            } else {
                " - its ${function.receivers.size} contextual receiver(s) are supplied by " +
                    "'with' or by a receiver call, not as arguments"
            }
            errors.add(
                "line $line: '$label' expects ${function.params.size} argument(s), " +
                    "got ${args.size}$receiverNote",
            )
            return null
        }
        for (i in args.indices) {
            val actual = resolveContextualArgument(args[i], function.params[i]) ?: return null
            if (!isCompatible(function.params[i], actual)) {
                errors.add("line $line: arg ${i + 1} of '$label': expected ${function.params[i]}, got $actual")
            }
        }
        // Receivers written at the call - `2.scale(7)` supplies one, `[2, 3].add()`
        // supplies as many as the brackets hold.
        if (explicitReceivers.isNotEmpty()) {
            if (explicitReceivers.size != function.receivers.size) {
                errors.add(
                    "line $line: '$label' takes ${function.receivers.size} contextual receiver(s), " +
                        "got ${explicitReceivers.size}",
                )
                return null
            }
            for (i in function.receivers.indices) {
                if (!isCompatible(function.receivers[i], explicitReceivers[i])) {
                    errors.add(
                        "line $line: receiver ${i + 1} of '$label': " +
                            "expected ${function.receivers[i]}, got ${explicitReceivers[i]}",
                    )
                }
            }
            return function.ret
        }
        if (function.receivers.isNotEmpty() && inferredContexts(function.receivers) == null) {
            errors.add(
                "line $line: '$label' requires contextual receiver(s) " +
                    function.receivers.joinToString(", ") +
                    "; supply them with 'with', or write them as a receiver call",
            )
            return null
        }
        return function.ret
    }

    /** Resolves one argument with the callable type expected at that position. */
    /**
     * The capture default a block gets from the position it is passed to.
     *
     * `MUTABLE` while resolving an argument to an `inline` callable: an inlined
     * body reads and writes what surrounds it, which is what a capture list
     * would otherwise have to spell out.
     */
    private var inlineCallableDefault: CaptureMode? = null

    private fun resolveContextualArgument(argument: Expr, expected: IrType?): IrType? {
        // `.Name` takes the type it is being passed to.
        if (argument is Expr.InferredMember) {
            return resolveInferredMember(argument, expected)
        }
        // `.().height(64.0)` - the dot heads a chain, so the expected type has
        // to reach past the calls to the receiver they are called on. A builder
        // chain returns its own type, so the head is the type expected at the
        // end; if a call in between returns something else, the ordinary check
        // on the result still catches it.
        seedInferredReceiver(argument, expected)
        val savedParams = expectedLambdaParamTypes
        val savedReceivers = expectedLambdaReceiverTypes
        val savedInline = inlineCallableDefault
        if (argument is Expr.Lambda && expected is IrType.Function) {
            expectedLambdaParamTypes = expected.params
            expectedLambdaReceiverTypes = expected.receivers
            if (expected.isInline) inlineCallableDefault = CaptureMode.MUTABLE
        }
        return try {
            val resolved = resolveExpr(argument)
            // The body has now expanded a capture default to the exact free
            // bindings it selected, so escaping is checked without treating a
            // default as though it captured the whole surrounding scope.
            if (expected is IrType.Function && expected.isEscaping && argument is Expr.Lambda) {
                checkEscapingCaptures(argument, "is passed to an escaping parameter")
            }
            resolved
        } finally {
            expectedLambdaParamTypes = savedParams
            expectedLambdaReceiverTypes = savedReceivers
            inlineCallableDefault = savedInline
        }
    }

    /**
     * Whether [type] names a type parameter that nothing bound.
     *
     * A parameter left over from a declaration is not a type any value has; it
     * stands for one the caller has yet to choose.
     */
    private fun isUnboundTypeParam(type: IrType): Boolean {
        val named = type as? IrType.Named ?: return false
        if (named.args.isNotEmpty()) return false
        return table.lookupStruct(named.name) == null && table.lookupEnum(named.name) == null
    }

    /** If [expr] is `ErrSet.Variant`, returns the error-set name; otherwise null. */
    private fun failSetOf(expr: Expr): String? {
        val m = expr as? Expr.Member ?: return null
        val id = m.target as? Expr.Identifier ?: return null
        return if (table.lookupFail(id.name) != null) id.name else null
    }

    /**
     * Resolves a list of statements, sharing one scope across every realm block
     * in the body.
     *
     * Sibling realms see each other's bindings; the ordinary code between them
     * does not. That is what makes a realm a place to group related work rather
     * than just an extra pair of braces.
     */
    private fun resolveBody(stmts: List<Stmt>, returnType: IrType) {
        val hasRealms = stmts.any { it is Stmt.Scope && it.shared }

        if (!hasRealms) {
            for (stmt in stmts) resolveStmt(stmt, returnType)
            return
        }

        // Bindings that persist from one realm block to the next.
        val realmScope = mutableMapOf<String, VariableSymbol>()

        for (stmt in stmts) {
            if (stmt is Stmt.Scope && stmt.shared) {
                table.pushScope()
                for ((_, sym) in realmScope) table.defineVariable(sym)
                // `realm unsafe { }` is still the boundary it was; sharing the
                // scope must not quietly drop the opt-in.
                val savedUnsafe = unsafeContext
                if (stmt.unsafe) unsafeContext = true
                for (s in stmt.body) resolveStmt(s, returnType)
                unsafeContext = savedUnsafe
                table.exportCurrentScope(realmScope)
                table.popScope()
            } else {
                resolveStmt(stmt, returnType)
            }
        }
    }

    private fun resolveStmt(stmt: Stmt, returnType: IrType) {
        // A borrow passed straight to a call ends when the statement does.
        // Bindings claim theirs first, in resolveBinding.
        pendingBorrows.clear()
        resolveStmtInner(stmt, returnType)
        pendingBorrows.clear()
    }

    private fun resolveStmtInner(stmt: Stmt, returnType: IrType) {
        when (stmt) {
            // `var` and `val` both rebind; only `var` may be mutated through.
            is Stmt.VarDecl ->
                resolveBinding(stmt.name, stmt.type, stmt.initializer, stmt.line, mutable = true, valueMutable = stmt.valueMutable)
            is Stmt.RemDecl -> {
                if (!reactiveContext) {
                    errors.add("line ${stmt.line}: '${stmt.kind.spelling}' requires a 'react func' or 'react async func'")
                }
                resolveBinding(
                    stmt.name,
                    stmt.type,
                    stmt.initializer,
                    stmt.line,
                    mutable = stmt.binding.nameRebindable,
                    valueMutable = stmt.binding.valueMutable,
                )
            }
            is Stmt.Effect -> {
                if (!reactiveContext) {
                    errors.add("line ${stmt.line}: 'effect' requires a 'react func' or 'react async func'")
                }
                stmt.dependencies?.forEach(::resolveExpr)
                resolveBody(stmt.body, returnType)
            }
            is Stmt.WithContext -> {
                val values = stmt.values.mapNotNull { value ->
                    resolveExpr(value)?.let { value to it }
                }
                contextualValues.addLast(values)
                table.pushScope()
                resolveBody(stmt.body, returnType)
                table.popScope()
                contextualValues.removeLast()
            }
            is Stmt.FinDecl ->
                resolveBinding(stmt.name, stmt.type, stmt.initializer, stmt.line, mutable = false, valueMutable = false)
            is Stmt.Assignment -> {
                checkCapture(stmt.name, stmt.line)
                val varSym = table.lookupVariable(stmt.name)
                if (varSym == null) {
                    errors.add("line ${stmt.line}: undefined variable '${sourceSymbol(stmt.name)}'")
                    return
                }
                if (!varSym.mutable) {
                    // A `let`/`fin` that gave its value away can never receive
                    // another, so it stays unusable for the rest of its scope.
                    errors.add("line ${stmt.line}: cannot reassign immutable binding '${stmt.name}'")
                    return
                }
                // `var`/`val` may be rebound after a move, which makes the name
                // usable again - the ownership model's whole distinction between
                // the rebindable and fixed keywords after a `take`.
                if (!isBoundInsideCurrentLambda(stmt.name)) movedBindings.remove(stmt.name)
                // `a += b` on a type that declares an in-place `oper+=` never
                // becomes `a = a + b`, so checking the desugaring would demand
                // an `oper+` the type is entitled not to have. The lowerer makes
                // the same decision from the same table.
                if (resolvesInPlace(stmt.compoundOp, stmt.value, varSym.type)) return
                val valueType = resolveExpr(stmt.value) ?: return
                if (!isCompatible(varSym.type, adoptLiteralType(stmt.value, valueType, varSym.type))) {
                    errors.add("line ${stmt.line}: cannot assign $valueType to '${stmt.name}' of type ${varSym.type}")
                }
            }
            is Stmt.LetDecl ->
                resolveBinding(stmt.name, stmt.type, stmt.initializer, stmt.line, mutable = false, valueMutable = true)
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
                    // `return .()` / `return .Name` - the function's declared
                    // return type is what the dot means, and it is already
                    // written above the body.
                    if (stmt.value is Expr.InferredMember) {
                        resolveInferredMember(stmt.value as Expr.InferredMember, returnType)
                    } else {
                        seedInferredReceiver(stmt.value!!, returnType)
                    }
                    checkReturnedOrigin(stmt.value!!, stmt.line)
                    // Returning a closure is one of the four ways it escapes: the
                    // scope it captured from is gone by the time it is called.
                    val valueType = resolveExpr(stmt.value) ?: return
                    (stmt.value as? Expr.Lambda)?.let {
                        if (lambdaReturnTypes == null) checkEscapingCaptures(it, "is returned")
                    }
                    val capturing = lambdaReturnTypes
                    if (capturing != null) {
                        // Inferring a lambda's return type - record it, skip declared-type checking.
                        capturing.add(valueType)
                    } else if (!isCompatible(returnType, adoptLiteralType(stmt.value!!, valueType, returnType))) {
                        val undeclared = undeclaredReturnOf
                        if (undeclared != null && returnType == IrType.Unit) {
                            errors.add(
                                "line ${stmt.line}: '$undeclared' returns $valueType but declares no return type - " +
                                    "an omitted return type means Unit, it is not inferred; " +
                                    "declare it as ': $valueType'",
                            )
                        } else {
                            errors.add("line ${stmt.line}: return type mismatch: expected $returnType but got $valueType")
                        }
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
                val thenMoves = inBranch { for (s in stmt.thenBranch) resolveStmt(s, returnType) }
                table.popScope()
                if (stmt.elseBranch != null) {
                    table.pushScope()
                    val elseMoves = inBranch { for (s in stmt.elseBranch) resolveStmt(s, returnType) }
                    table.popScope()
                    // A value every path gives away is gone whichever path ran,
                    // so unlike a one-sided move this one outlives the branch.
                    for ((name, line) in thenMoves) elseMoves[name]?.let { movedBindings[name] = minOf(line, it) }
                }
            }
            is Stmt.InlineIf -> errors.add("line ${stmt.line}: inline if condition could not be evaluated at compile time")
            is Stmt.InlineFor -> errors.add("line ${stmt.line}: inline for range could not be evaluated at compile time")
            is Stmt.DeepInlineIf -> errors.add("line ${stmt.line}: deepinline if condition could not be evaluated at compile time")
            is Stmt.Scope -> {
                table.pushScope()
                val savedUnsafe = unsafeContext
                if (stmt.unsafe) unsafeContext = true
                resolveBody(stmt.body, returnType)
                unsafeContext = savedUnsafe
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
                val level = stmt.level ?: defaultTraceLevel(stmt.line)
                val levelType = resolveExpr(level) ?: return
                if (levelType != IrType.Named("LogLevel")) {
                    errors.add("line ${stmt.line}: trace level must be LogLevel, got $levelType")
                }
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
                inLoop { resolveBody(stmt.body, returnType) }
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
                        inLoop { resolveBody(stmt.body, returnType) }
                        table.popScope()
                    }
                    else -> {
                        val iterType = resolveExpr(iter)
                        if (iterType == null) return
                    // An iterator walks itself: it says when there is a row and
                    // what the row is, which is all `for` needs.
                    val walked = (iterType as? IrType.Named)?.name?.let { owner ->
                        table.lookupMethod(owner, "next")
                            ?.let { table.lookupFunction(it) }
                            ?.takeIf { table.lookupMethod(owner, "hasNext") != null }
                            ?.returnType
                    }
                    if (walked != null) {
                        table.pushScope()
                        table.defineVariable(VariableSymbol(stmt.name, walked, mutable = false))
                        inLoop { resolveBody(stmt.body, returnType) }
                        table.popScope()
                        return
                    }
                    if (iterType !is IrType.Array && iterType !is IrType.Set) {
                        errors.add(
                            "line ${stmt.line}: for loop iterable must be a range, an array, or an " +
                                "iterator, got $iterType",
                        )
                        return
                    }
                    table.pushScope()
                    val elementType = when (iterType) {
                        is IrType.Array -> iterType.element
                        is IrType.Set -> iterType.element
                        else -> error("unreachable")
                    }
                    table.defineVariable(VariableSymbol(stmt.name, elementType, mutable = false))
                        inLoop { resolveBody(stmt.body, returnType) }
                        table.popScope()
                    }
                }
            }
            is Stmt.Loop -> {
                // `loop xs by 5.seconds { … }` waits between passes, and waiting
                // means suspending. In a plain function there is nothing to
                // suspend - the wait would block whoever called it - so the form
                // is only meaningful where suspension is.
                if (stmt.everySeconds != null && !asyncContext) {
                    errors.add(
                        "line ${stmt.line}: 'loop … by …' waits between passes, which suspends - " +
                            "declare the function 'async func' (or 'react async func')",
                    )
                }
                stmt.everySeconds?.let { resolveExpr(it) }
                table.pushScope()
                inLoop { resolveBody(stmt.body, returnType) }
                table.popScope()
            }
            is Stmt.Break -> { /* no type constraint */ }
            is Stmt.Continue -> { /* no type constraint */ }
            is Stmt.IndexAssign -> {
                if (!checkValueMutable(stmt.target, stmt.line, "assign by index")) return
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
                val target = resolveExpr(stmt.target) ?: return
                resolveExpr(stmt.value) ?: return
                // `p.^ = v` writes through the pointer, which a `T*` does not permit.
                // The sigil at the write site already says which one this is.
                if (target is IrType.Pointer && !target.mutable) {
                    errors.add(
                        "line ${stmt.line}: cannot write through '$target' - " +
                            "a read-only pointer. Allocate it with 'alloc^' to get a '${target.inner}^'",
                    )
                }
            }
            is Stmt.MemberAssign -> {
                unionNameOf(inferredTargetType(stmt.target))?.let { requireUnsafeForUnion(it, stmt.line) }
                if (!checkValueMutable(stmt.target, stmt.line, "assign to member '${stmt.name}'")) return
                val resolvedTarget = resolveExpr(stmt.target) ?: return
                // Auto-deref: assigning through a pointer writes through it (`p.v = x` == `(*p).v = x`).
                var targetType = if (resolvedTarget is IrType.Pointer) resolvedTarget.inner else resolvedTarget
                val valueType = resolveExpr(stmt.value) ?: return
                if (targetType is IrType.Named) {
                    var field = table.lookupStruct(targetType.name)?.field(stmt.name)
                    if (field == null) {
                        val dereferenced = userDerefType(targetType)
                        if (dereferenced != null) {
                            targetType = dereferenced
                            field = table.lookupStruct(targetType.name)?.field(stmt.name)
                        }
                    }
                    if (field == null) {
                        errors.add("line ${stmt.line}: no member '${stmt.name}' on pack ${sourcePackTypeName(targetType.name)}")
                        return
                    }
                    val selfTarget = stmt.target as? Expr.Identifier
                    if (selfTarget?.name == "self" && table.lookupVariable("self")?.mutable == false) {
                        errors.add("line ${stmt.line}: cannot mutate '${stmt.name}' through an immutable 'self&' receiver (use 'self!')")
                        return
                    }
                    if (!canAccessMember(targetType.name, stmt.name, field.visibility)) {
                        reportInaccessible(stmt.line, "field", targetType.name, stmt.name, field.visibility)
                        return
                    }
                    if (!field.mutable) {
                        errors.add("line ${stmt.line}: cannot assign to immutable field '${stmt.name}' of struct ${targetType.name}")
                        return
                    }
                    if (!isCompatible(field.type, adoptLiteralType(stmt.value, valueType, field.type))) {
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
                                    inBranch { resolveBody(branch.body, returnType) }
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
                    inBranch { resolveBody(branch.body, returnType) }
                    table.popScope()
                }
                if (stmt.elseBranch != null) {
                    table.pushScope()
                    inBranch { resolveBody(stmt.elseBranch, returnType) }
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

    private fun defaultTraceLevel(line: Int): Expr {
        val first = table.lookupEnum("LogLevel")?.firstOrNull() ?: "Debug"
        return Expr.Member(Expr.Identifier("LogLevel", line), first, line)
    }

    private fun suffixToFloatType(suffix: NumericSuffix): IrType = when (suffix) {
        NumericSuffix.FLOAT -> IrType.Float
        NumericSuffix.DECIMAL -> IrType.Decimal
        else -> IrType.Double
    }

    private fun resolveExpr(expr: Expr): IrType? {
        return when (expr) {
            // Reached with no expected type: `.Name` on its own says nothing.
            is Expr.InferredMember -> resolveInferredMember(expr, null)
            is Expr.MapEntryArg -> {
                errors.add(
                    "line ${expr.line}: 'key: value' is only an argument of a macro that takes " +
                        "'[...\${key: value}]' - no macro arm matched this invocation",
                )
                null
            }
            is Expr.IntLiteral -> suffixToIntType(expr.suffix)
            is Expr.DoubleLiteral -> suffixToFloatType(expr.suffix)
            is Expr.StringLiteral -> IrType.String
            is Expr.BoolLiteral -> IrType.Bool
            is Expr.NullLiteral -> IrType.Any  // null is compatible with any nullable type
            is Expr.CharLiteral -> IrType.Char
            is Expr.Identifier -> {
                checkNotMoved(expr.name, expr.line)
                checkNotMutablyBorrowed(expr.name, expr.line)
                checkBorrowAcrossSuspension(expr.name, expr.line)
                checkCapture(expr.name, expr.line)
                val sym = table.lookupVariable(expr.name)
                    ?: throughTypeAlias(expr.name)?.let { table.lookupVariable(it) }
                if (sym == null) {
                    // Implicit self: bare field name in an impl method → self.field
                    val receiverField = currentReceiverType?.let { table.lookupStruct(it)?.field(expr.name) }
                    if (receiverField != null) receiverField.type
                    else {
                        errors.add("line ${expr.line}: undefined variable '${sourceSymbol(expr.name)}'")
                        null
                    }
                } else sym.type
            }
            is Expr.UpperScopeAccess -> {
                val selectedScope = table.variableScopeIndexInUpperScope(expr.name, expr.depth)
                val crossedLambda = selectedScope != null && selectedScope != 0 &&
                    lambdaFrames.any { selectedScope < it.floor }
                if (crossedLambda) {
                    errors.add(
                        "line ${expr.line}: upper-scope access cannot bypass a lambda capture boundary; " +
                            "capture '${expr.name}' after ';' and use a capture alias when it is shadowed",
                    )
                    return null
                }
                val sym = table.lookupVariableInUpperScope(expr.name, expr.depth)
                if (sym == null) {
                    val colons = "::".repeat(expr.depth)
                    errors.add("line ${expr.line}: '${expr.name}' not found at scope depth $colons")
                    null
                } else sym.type
            }
            is Expr.Unary -> {
                val operandType = resolveExpr(expr.operand) ?: return null
                // A user type answers `-x`, `!x` and `~x` through the operator it
                // declared. Checked before the built-in rules, which reject any
                // non-numeric operand outright - that rejection is what made a
                // declared unary operator dead code.
                if (operandType is IrType.Named) {
                    unaryOverloadName(expr.op)?.let { operName ->
                        table.lookupUnaryOperator(operandType.name, operName)?.let { mangled ->
                            return table.lookupFunction(mangled)?.returnType ?: operandType
                        }
                    }
                }
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
                resolveBinaryType(
                    expr.op,
                    adoptLiteralType(expr.left, leftType, rightType),
                    adoptLiteralType(expr.right, rightType, leftType),
                    expr.line,
                )
            }
            is Expr.Call -> {
                // `delay <ms>` parses as a call; it suspends like an `await`.
                if (expr.callee == "__delay") noteSuspension()
                // Value call `receiver(args)` - the receiver must be a function value.
                expr.receiver?.let { recv ->
                    val recvType = resolveExpr(recv)
                    if (recvType is IrType.Function) {
                        return resolveCallableArguments("callable value", recvType, expr.args, expr.line)
                    }
                    for (arg in expr.args) resolveExpr(arg)
                    if (recvType != null && recvType != IrType.Any) {
                        errors.add("line ${expr.line}: value of type '$recvType' is not callable")
                    }
                    return IrType.Any
                }
                if (expr.callee == "__defaultLogLevel") return IrType.Named("LogLevel")
                if (expr.callee == "__reflect") {
                    errors.add("line ${expr.line}: reflect is compile-time-only and must be followed by .hasAnnot<D> or .annotMeta<D>")
                    return null
                }
                if (expr.callee == "__hasAnnot" || expr.callee == "__annotMeta") {
                    errors.add("line ${expr.line}: '${if (expr.callee == "__hasAnnot") "hasAnnot" else "annotMeta"}' is a compile-time-only property and must be used inside inline code")
                    return null
                }
                // Struct construction: `Name(args)` where Name is a pack. Inside an
                // impl, `Self(…)` builds the type the impl is on - the same meaning
                // `Self` already has in a signature, now in expression position.
                val calleeName = selfToReceiver(expr.callee)
                val struct = table.lookupStruct(calleeName)
                if (struct != null) {
                    if (struct.isBridge) {
                        errors.add("line ${expr.line}: compiler bridge pack '${expr.callee}' cannot be constructed directly")
                        return null
                    }
                    if (struct.isUnion) {
                        requireUnsafeForUnion(calleeName, expr.line)
                        return resolveUnionCtor(expr, struct)
                    }
                    if (expr.args.size > struct.fields.size) {
                        errors.add("line ${expr.line}: '${expr.callee}' has ${struct.fields.size} fields, got ${expr.args.size} arguments")
                        return null
                    }
                    // Named and positional arguments mix freely: a named argument goes
                    // to its own field, and a positional one fills the leftmost field
                    // no name has claimed. `Size(width: 2, 3)` and `Size(2, height: 3)`
                    // both mean the same thing.
                    val effectiveArgs = if (expr.args.any { it is Expr.NamedArg }) {
                        val slots = arrayOfNulls<Expr>(struct.fields.size)
                        for (argument in expr.args) {
                            if (argument !is Expr.NamedArg) continue
                            val index = struct.fields.indexOfFirst { it.name == argument.name }
                            if (index < 0) {
                                errors.add("line ${expr.line}: '${expr.callee}' has no field '${argument.name}'")
                                return null
                            }
                            if (slots[index] != null) {
                                errors.add("line ${expr.line}: field '${argument.name}' given twice in '${expr.callee}'")
                                return null
                            }
                            slots[index] = argument.value
                        }
                        var next = 0
                        for (argument in expr.args) {
                            if (argument is Expr.NamedArg) continue
                            while (next < slots.size && slots[next] != null) next++
                            if (next >= slots.size) {
                                errors.add("line ${expr.line}: too many arguments for '${expr.callee}'")
                                return null
                            }
                            slots[next] = argument
                        }
                        struct.fields.mapIndexed { index, field ->
                            slots[index] ?: field.default
                                ?: run {
                                    errors.add(
                                        "line ${expr.line}: missing field '${field.name}' in " +
                                            "'${expr.callee}' (no default)",
                                    )
                                    return null
                                }
                        }
                    } else {
                        // Positional - pad omitted trailing fields with their defaults (`Pack<T>()`).
                        val padded = expr.args.toMutableList()
                        for (i in expr.args.size until struct.fields.size) {
                            val d = struct.fields[i].default
                                ?: run { errors.add("line ${expr.line}: missing field '${struct.fields[i].name}' in '${expr.callee}' (no default)"); return null }
                            padded.add(d)
                        }
                        padded
                    }
                    for (i in effectiveArgs.indices) {
                        val argument = effectiveArgs[i]
                        val fieldType = struct.fields[i].type
                        // Storing a closure in a field escapes it - but only where
                        // the field says it keeps the callable. A callable type is
                        // non-escaping by default (§4.7), and a field that merely
                        // holds one for the life of a value it is itself part of
                        // outlives nothing the closure borrowed.
                        val argType = resolveContextualArgument(argument, fieldType) ?: return null
                        if (struct.typeParams.isEmpty()) {
                            if (!isCompatible(fieldType, adoptLiteralType(argument, argType, fieldType))) {
                                errors.add("line ${expr.line}: field '${struct.fields[i].name}' of '${expr.callee}': expected $fieldType, got $argType")
                            }
                        }
                    }
                    // Keep the explicit type arguments so a later field read
                    // knows what a generic pack's erased slot actually holds.
                    return IrType.Named(
                        struct.name,
                        expr.typeArgs.map { IrType.resolve(it) },
                    )
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
                        return resolveCallableArguments(expr.callee, v.type, expr.args, expr.line)
                    }
                    // A function value whose concrete type was erased to `Any` (e.g. a
                    // loop variable over `Array<(Int) -> Int>`, whose element type
                    // erases). Trust it is callable; the result type is unknown.
                    if (v != null && (v.type == IrType.Any || v.type is IrType.Function)) {
                        for (arg in expr.args) { resolveExpr(arg) ?: return null }
                        return IrType.Any
                    }
                    // Inside `with value { … }`, a bare call may be an extension method
                    // on one of the contextual values: `with c { bump() }` == `c.bump()`.
                    // A realm-qualified call reaches its contextual receiver too:
                    // `std::yield(1)` names the member `yield`, and the realm only
                    // says where it was declared, not what it is called on.
                    val contextualName = expr.callee.substringAfterLast("__")
                    for ((ctxExpr, ctxType) in contextualValues.asReversed().flatten()) {
                        if (ctxType is IrType.Named && table.lookupMethod(ctxType.name, contextualName) != null) {
                            return resolveExpr(Expr.MethodCall(ctxExpr, contextualName, expr.args, expr.line, expr.column))
                        }
                    }
                    errors.add("line ${expr.line}: undefined function '${sourceSymbol(expr.callee)}'")
                    return null
                }
                if (func.isUnsafe && !unsafeContext) {
                    errors.add("line ${expr.line}: call to unsafe '${expr.callee}' requires an unsafe block or unsafe function")
                    return null
                }
                if (!requireReactiveCaller(func, expr.line)) return null
                if (!requireTestCaller(func.name, expr.line)) return null
                // Handle named arguments - reorder to param order
                // Named and positional arguments mix freely at a call, as they do at a
                // constructor: a named one takes its parameter, a positional one fills
                // the leftmost parameter no name has claimed.
                val callArgs = if (func.isVariadic) expr.args
                    else bindTrailingLambda(expr.args, func.params, offset = 0)
                val effectiveArgs = if (callArgs.any { it is Expr.NamedArg } && func.paramNames.isNotEmpty()) {
                    val slots = arrayOfNulls<Expr>(func.paramNames.size)
                    for (argument in callArgs) {
                        if (argument !is Expr.NamedArg) continue
                        val index = func.paramNames.indexOf(argument.name)
                        if (index < 0) {
                            errors.add("line ${expr.line}: '${expr.callee}' has no parameter '${argument.name}'")
                            return null
                        }
                        if (slots[index] != null) {
                            errors.add("line ${expr.line}: parameter '${argument.name}' given twice")
                            return null
                        }
                        slots[index] = argument.value
                    }
                    var next = 0
                    for (argument in callArgs) {
                        if (argument is Expr.NamedArg) continue
                        while (next < slots.size && slots[next] != null) next++
                        if (next >= slots.size) {
                            errors.add("line ${expr.line}: too many arguments for '${expr.callee}'")
                            return null
                        }
                        slots[next] = argument
                    }
                    // A gap is the parameter's default, not a missing argument.
                    // Substituting the default expression keeps the list
                    // positional, so every check below still lines argument `i`
                    // up with parameter `i`. Gaps with no default stay null and
                    // are caught by the arity check.
                    slots.indices.mapNotNull { slots[it] ?: func.defaults[it] }
                } else {
                    callArgs
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
                    // A spread arg fills remaining params - skip the count check (runtime handles correctness).
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
                    val arg = effectiveArgs[i]
                    val declaredType = func.params.getOrNull(i)?.second
                        ?: func.params.lastOrNull()?.second
                        ?: IrType.Any
                    val paramType = if (func.isVariadic && i >= func.params.lastIndex) {
                        (declaredType as? IrType.Array)?.element ?: declaredType
                    } else {
                        declaredType
                    }
                    val argType = resolveContextualArgument(arg, paramType) ?: return null
                    argTypes.add(argType)
                    // `f(x)` where the parameter is `p!` borrows x exclusively, so
                    // the callee may write through it - which a `val`/`fin` binding
                    // does not permit.
                    if (i in func.exclusiveParams || i in func.sharedParams) {
                        checkNotGivenToBorrow(arg, i, func, expr.callee, expr.line)
                    }
                    if (i in func.exclusiveParams) {
                        checkValueMutable(
                            arg,
                            expr.line,
                            "borrow mutably for parameter '${func.paramNames.getOrNull(i) ?: (i + 1).toString()}'",
                        )
                    } else if (i !in func.sharedParams) {
                        checkLend(arg, i, func, expr.callee, expr.line)
                        if (!isLend(arg)) checkByValueTransfer(arg, argType, expr.line)
                    }
                    if (!isGeneric) {
                        if (!isCompatible(paramType, adoptLiteralType(effectiveArgs[i], argType, paramType))) {
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
                                bindings.getOrPut(paramRef.name) { listOf(typeRefOf(argTypes[i])) }
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
                                        bindings.getOrPut(element.name) { listOf(typeRefOf(inferred)) }
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
                    // Empty array literal `arr()` - element type unknown; defaults to Any (erased).
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
                // Map indexing: `map[key]` - key may be any type.
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
                // `T.member` where `T` is a type parameter: the member is one of
                // `Type`'s, declared in the standard library. What is readable and
                // what it yields come from that declaration, not from a list here.
                // Checked before anything looks at the target, which is a type
                // rather than an expression and would not resolve as one.
                if ((expr.target as? Expr.Identifier)?.name in currentFuncTypeParams) {
                    (typeMetaMember(expr.name) ?: typeMetaMemberFallback(expr.name))?.let { return it }
                }
                movablePath(expr)?.let { checkNotMoved(it, expr.line) }
                // A union member read reinterprets storage; see requireUnsafeForUnion.
                unionNameOf(inferredTargetType(expr.target))?.let { requireUnsafeForUnion(it, expr.line) }
                // Enum variant: `Color.Red` → Named type carrying the enum identity
                // (enables exhaustiveness checking in `when`; runtime value is still a string).
                if (expr.target is Expr.Identifier) {
                    val variants = table.lookupEnum(expr.target.name)
                    if (variants != null) {
                        if (expr.name in variants) return IrType.Named(table.canonicalTypeName(expr.target.name))
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
                            return IrType.Named(table.canonicalTypeName(expr.target.name))
                        }
                    }
                }
                val resolvedTarget = resolveExpr(expr.target) ?: return null
                // Auto-deref: member access on a pointer reads through it (`p.v` == `(*p).v`).
                val targetType = if (resolvedTarget is IrType.Pointer) resolvedTarget.inner else resolvedTarget
                when {
                    expr.name in setOf("length", "size") &&
                        (targetType is IrType.Map || targetType is IrType.Set || targetType == IrType.String) -> IrType.Int
                    declaredAggregateMember(targetType, expr.name) != null -> {
                        if (aggregateFieldIsUnsafe(targetType, expr.name) && !unsafeContext) {
                            errors.add(
                                "line ${expr.line}: '${expr.name}' is an unsafe member of " +
                                    "'$targetType' and is only readable inside an 'unsafe' " +
                                    "block or an 'unsafe func'",
                            )
                            return null
                        }
                        declaredAggregateMember(targetType, expr.name)
                    }
                    targetType is IrType.Named -> {
                        // Spec-typed value: a property/requirement declared by the spec
                        // (e.g. `map.size` where `map: Map<K,V>` - a spec) resolves to
                        // the spec's declared prop type and dispatches to the impl.
                        val specProp = table.lookupSpecProp(targetType.name, expr.name)
                        if (specProp != null) return specProp
                        val struct = table.lookupStruct(targetType.name)
                        // A conditional field belongs to some layouts and not others,
                        // so membership is asked of the specialization. An application
                        // that is not yet concrete falls back to the template.
                        if (!specializedLayoutHasField(targetType, expr.name)) {
                            errors.add(
                                "line ${expr.line}: no member '${expr.name}' on $targetType",
                            )
                            return null
                        }
                        val field = struct?.field(expr.name)
                        // `unsafe fin data: T*` - the obligation the field carries
                        // travels with it, so reading it needs the scope that
                        // accepts one.
                        if (field?.isUnsafe == true && !unsafeContext) {
                            errors.add(
                                "line ${expr.line}: '${expr.name}' is an unsafe member of " +
                                    "'${targetType.name}' and is only readable inside an " +
                                    "'unsafe' block or an 'unsafe func'",
                            )
                            return null
                        }
                        if (field != null) {
                            if (!canAccessMember(targetType.name, expr.name, field.visibility)) {
                                reportInaccessible(expr.line, "field", targetType.name, expr.name, field.visibility)
                                null
                            } else {
                                substituteFieldType(struct, expr.name, targetType, field.type)
                            }
                        } else {
                            // Check for a computed property/callback member.
                            val mangled = table.lookupMethod(targetType.name, expr.name)
                            if (mangled != null) {
                                val func = table.lookupFunction(mangled)
                                if (func != null && func.params.size == 1 && func.memberCallStyle != MemberCallStyle.METHOD) {
                                    if (!canAccessMember(targetType.name, expr.name, func.visibility)) {
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
                                val dereferenced = userDerefType(targetType)
                                val derefField = dereferenced?.let { table.lookupStruct(it.name)?.field(expr.name) }
                                val derefMethod = dereferenced?.let { table.lookupMethod(it.name, expr.name) }
                                when {
                                    derefField != null -> derefField.type
                                    derefMethod != null -> {
                                        val func = table.lookupFunction(derefMethod)
                                        if (func != null && func.params.size == 1 &&
                                            func.memberCallStyle != MemberCallStyle.METHOD
                                        ) func.returnType
                                        else {
                                            errors.add("line ${expr.line}: member '${expr.name}' on ${dereferenced.name} requires a method call")
                                            null
                                        }
                                    }
                                    else -> {
                                        errors.add("line ${expr.line}: no member '${expr.name}' on pack ${sourcePackTypeName(targetType.name)}")
                                        null
                                    }
                                }
                            }
                        }
                    }
                    // An erased generic is `Any`, and which concrete type it will
                    // be is not known here - `element.hash` inside a container
                    // over `T` is the case that matters. A method call on `Any`
                    // already defers to runtime and so does a comparison
                    // (`resolveBinaryType`); a property has to, for the same
                    // reason and on the same grounds.
                    targetType == IrType.Any -> IrType.Any
                    // `Hash`'s member on a built-in. `impl [… Hash] for Integers`
                    // states the conformance in std; the value comes from the
                    // backend rather than from a written member, so there is
                    // nothing in the method table to find.
                    expr.name == "hash" && targetType !is IrType.Named -> IrType.ULong
                    else -> {
                        // A primitive receiver keys the method table by its source
                        // type name (`impl Int { prop seconds[self&] }`), the same
                        // way a method call on one does. Without this a property
                        // extension on Int would parse and lower but never resolve.
                        val mangled = table.lookupMethod(targetType.toString(), expr.name)
                        val prop = mangled?.let { table.lookupFunction(it) }
                        if (prop != null && prop.params.size == 1 && prop.memberCallStyle != MemberCallStyle.METHOD) {
                            prop.returnType
                        } else if (prop != null) {
                            errors.add("line ${expr.line}: member '${expr.name}' on $targetType requires a method call")
                            null
                        } else {
                            errors.add("line ${expr.line}: no member '${expr.name}' on $targetType")
                            null
                        }
                    }
                }
            }
            is Expr.MethodCall -> {
                // `[2, 3].add()` - a receiver call supplying several contextual
                // receivers. A bare bracket list is not a value anywhere else, so
                // the only thing it can be here is the receivers `add` declares.
                val bracketTarget = expr.target as? Expr.ArrayLiteral
                if (bracketTarget != null) {
                    val callable = table.lookupVariable(expr.name)?.type as? IrType.Function
                    if (callable != null && callable.receivers.isNotEmpty()) {
                        val given = bracketTarget.elements.map { resolveExpr(it) ?: return null }
                        return resolveCallableArguments(
                            expr.name,
                            callable,
                            expr.args,
                            expr.line,
                            explicitReceivers = given,
                        )
                    }
                }
                // `#expr` (oper#) - hash; returns ULong regardless of operand type
                // (the operand may be a generic K erased to Any, whose concrete type
                // supplies oper# at runtime).
                if (expr.name == "oper#") {
                    resolveExpr(expr.target)
                    return IrType.ULong
                }
                // `x.clone()` - the `Clone` member. Compiler-provided for any
                // conforming type that does not write one, so it resolves here
                // rather than needing a body per type. A clone of a T is a T.
                if (expr.name == "clone" && expr.args.isEmpty()) {
                    resolveDefaultClone(expr)?.let { return it }
                }
                // `opt.require()` / `opt.take()` - the optional's value, without
                // the optional. Both are the read half of moving out of an
                // optional (§17); the IR generator adds the guard and the write
                // that empties it. Typed here rather than looked up so that
                // every optional has them, whatever it wraps.
                if (expr.name in OPTIONAL_UNWRAP && expr.args.isEmpty()) {
                    val targetType = inferredTargetType(expr.target)
                    if (targetType is IrType.Nullable) {
                        resolveExpr(expr.target)
                        return targetType.inner
                    }
                    // `take` is an ordinary method name a type may declare, so
                    // anything that is not an optional goes to the usual lookup.
                    // Nothing else declares `require`, so say what went wrong.
                    if (expr.name == "require" && targetType != null) {
                        errors.add(
                            "line ${expr.line}: 'require()' needs an optional, but " +
                                "${(expr.target as? Expr.Identifier)?.name ?: "the value"} is $targetType",
                        )
                        return targetType
                    }
                }
                // Slot construction: SlotName.Variant(args) - check BEFORE resolving target
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
                                val at = resolveContextualArgument(expr.args[i], variant.second[i]) ?: return null
                                if (!isCompatible(variant.second[i], at)) {
                                    errors.add("line ${expr.line}: payload ${i+1} of '${expr.name}': expected ${variant.second[i]}, got $at")
                                }
                            }
                            return IrType.Named(table.canonicalTypeName(expr.target.name))
                        }
                    }
                }
                // User-defined method on a struct: obj.method(args) -> Type_method(self, args)
                val targetType = resolveExpr(expr.target) ?: return null
                if (targetType is IrType.Named) {
                    val mangled = table.lookupMethod(targetType.name, expr.name)
                    if (mangled != null) {
                        val func = table.lookupFunction(mangled)!!
                        if (!requireReactiveCaller(func, expr.line)) return null
                if (!requireTestCaller(func.name, expr.line)) return null
                        if (func.memberCallStyle == MemberCallStyle.PROPERTY) {
                            errors.add("line ${expr.line}: property '${expr.name}' must be accessed without parentheses")
                            return null
                        }
                        if (!canAccessMember(targetType.name, expr.name, func.visibility)) {
                            reportInaccessible(expr.line, "method", targetType.name, expr.name, func.visibility)
                            return null
                        }
                        val declared = func.params.size - 1 // exclude `self`
                        // Methods take named arguments and defaults on the same
                        // terms as free functions: `self` is parameter 0, so a
                        // method's own parameters are offset by one throughout.
                        val positioned = positionMethodArguments(expr, func, declared) ?: return null
                        if (positioned.size > declared) {
                            errors.add("line ${expr.line}: method '${expr.name}' expects $declared args, got ${positioned.size}")
                            return null
                        }
                        // A method's own type parameters are inferred from the
                        // call, exactly as a generic free function's are, so a
                        // declared type naming one cannot be compared literally.
                        val methodIsGeneric = func.typeParams.isNotEmpty()
                        for (i in positioned.indices) {
                            val argument = positioned[i] ?: continue
                            val paramType = func.params[i + 1].second
                            val argType = resolveContextualArgument(argument, paramType) ?: return null
                            if (!methodIsGeneric && !isCompatible(paramType, argType)) {
                                errors.add("line ${expr.line}: arg ${i + 1} of '${expr.name}': expected $paramType, got $argType")
                            }
                        }
                        for (i in positioned.indices) {
                            if (positioned[i] == null && !func.defaults.containsKey(i + 1)) {
                                errors.add(
                                    "line ${expr.line}: method '${expr.name}' has no argument for " +
                                        "'${func.params[i + 1].first}' and it has no default",
                                )
                                return null
                            }
                        }
                        return func.returnType
                    }
                    val dereferenced = userDerefType(targetType)
                    if (dereferenced != null) {
                        val derefMethod = table.lookupMethod(dereferenced.name, expr.name)
                        if (derefMethod != null) {
                            val func = table.lookupFunction(derefMethod)!!
                            if (func.memberCallStyle == MemberCallStyle.PROPERTY) {
                                errors.add("line ${expr.line}: property '${expr.name}' must be accessed without parentheses")
                                return null
                            }
                            for (i in expr.args.indices) {
                                resolveContextualArgument(expr.args[i], func.params.getOrNull(i + 1)?.second)
                                    ?: return null
                            }
                            return func.returnType
                        }
                    }
                    val callableField = table.lookupStruct(targetType.name)?.field(expr.name)
                        ?.type as? IrType.Function
                    if (callableField != null) {
                        return resolveCallableArguments(
                            "${sourcePackTypeName(targetType.name)}.${expr.name}",
                            callableField,
                            expr.args,
                            expr.line,
                        )
                    }
                    // Spec-typed value: dispatch to a method declared by the spec
                    // (e.g. `list.get(0)` where `list: List<T>`). The concrete impl
                    // is selected at runtime; here we type-check against the spec.
                    val specMethod = table.lookupSpecMethod(targetType.name, expr.name)
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
                            val paramType = specMethod.paramTypes[i]
                            val argType = resolveContextualArgument(expr.args[i], paramType) ?: return null
                            if (!isCompatible(paramType, argType)) {
                                errors.add("line ${expr.line}: arg ${i + 1} of '${expr.name}': expected $paramType, got $argType")
                            }
                        }
                        return specMethod.returnType
                    }
                }
                // Primitive impl targets use their source type name as the
                // method-table key (`impl Int { … }`). They are native
                // IR types rather than Named packs, but otherwise follow the
                // same call contract: an implicit receiver followed by args.
                if (targetType !is IrType.Named) {
                    val mangled = table.lookupMethod(targetType.toString(), expr.name)
                    if (mangled != null) {
                        val func = table.lookupFunction(mangled)!!
                        if (!requireReactiveCaller(func, expr.line)) return null
                if (!requireTestCaller(func.name, expr.line)) return null
                        val declared = func.params.size - 1
                        if (expr.args.size != declared) {
                            errors.add("line ${expr.line}: method '${expr.name}' expects $declared args, got ${expr.args.size}")
                            return null
                        }
                        for (i in expr.args.indices) {
                            val paramType = func.params[i + 1].second
                            val argType = resolveContextualArgument(expr.args[i], paramType) ?: return null
                            if (!isCompatible(paramType, argType)) {
                                errors.add("line ${expr.line}: arg ${i + 1} of '${expr.name}': expected $paramType, got $argType")
                            }
                        }
                        return func.returnType
                    }
                }
                // Universal infix (`a @to b` → `to(a, b)`): a free function that
                // applies to any receiver. Checked after real methods so those win.
                val infixFn = table.lookupUniversalInfix(expr.name)?.let { table.lookupFunction(it) }
                if (infixFn != null) {
                    if (!requireReactiveCaller(infixFn, expr.line)) return null
                    if (!requireTestCaller(infixFn.name, expr.line)) return null
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
                        // Primitives, enums and collections format themselves at
                        // runtime. A pack does not: interpolating one that never
                        // said how it prints used to emit its internal
                        // representation - `{__type=Vec2, x=1, y=2}` - which is a
                        // pack's private layout appearing in program output.
                        val partType = resolveExpr(part.expr)
                        val named = partType as? IrType.Named
                        if (named != null && table.lookupStruct(named.name) != null &&
                            !table.conformsTo(named.name, "Display")
                        ) {
                            errors.add(
                                "line ${expr.line}: cannot interpolate a '${named.name}' - " +
                                    "${named.name} does not implement Display; add " +
                                    "'impl Display for ${named.name} { " +
                                    "func display[self: Self&](formatter: std::Formatter!) { … } }'",
                            )
                        }
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
                IrType.Pointer(pointee, mutable = expr.mutable)
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
                        val mangled = table.lookupMethod(target.name, "operDeref")
                        table.lookupFunction(mangled ?: "")?.returnType ?: IrType.Any
                    }
                    else -> IrType.Any
                }
            }
            is Expr.Isolated -> resolveOwnershipOp(expr)
            is Expr.Await -> {
                val t = resolveExpr(expr.value).also { noteSuspension() } ?: return null
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
            is Expr.InlineForArgs -> {
                errors.add("line ${expr.line}: 'inline for' argument was not expanded at compile time")
                null
            }
            is Expr.InCheck -> {
                resolveExpr(expr.value) ?: return null
                resolveExpr(expr.collection) ?: return null
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
                    if (field != null && !canAccessMember(inner.name, expr.name, field.visibility)) {
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
                val savedLambdaTypeParams = currentFuncTypeParams
                currentFuncTypeParams = currentFuncTypeParams + expr.typeParams
                // Infer the implicit `it` parameter's type from context when available.
                val expectedParams = expectedLambdaParamTypes
                val expectedReceivers = expectedLambdaReceiverTypes
                val bracketReceivers = expr.receivers
                val bracketCaptures = expr.captures
                val duplicateReceiver = bracketReceivers.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
                if (duplicateReceiver != null) {
                    errors.add("line ${expr.line}: duplicate lambda receiver '${duplicateReceiver.key}'")
                }
                val duplicateCaptureName = bracketCaptures.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
                if (duplicateCaptureName != null) {
                    errors.add("line ${expr.line}: duplicate lambda capture name '${duplicateCaptureName.key}'")
                }
                val duplicateCaptureSource = bracketCaptures.groupBy { it.source }.entries.firstOrNull { it.value.size > 1 }
                if (duplicateCaptureSource != null) {
                    errors.add("line ${expr.line}: binding '${duplicateCaptureSource.key}' is captured more than once")
                }
                val receiverNames = bracketReceivers.mapTo(mutableSetOf()) { it.name }
                bracketCaptures.firstOrNull { it.name in receiverNames }?.let {
                    errors.add("line ${it.line}: '${it.name}' cannot be both a receiver and a capture")
                }
                val paramTypes = expr.params.mapIndexed { i, p ->
                    val expected = expectedParams?.getOrNull(i)
                    if (p.type == TypeRef.Named("Any") && expected != null) expected
                    else resolveDeclaredType(p.type)
                }
                val declaredReceivers = bracketReceivers.mapIndexed { i, p ->
                    val expected = expectedReceivers?.getOrNull(i)
                    if (p.type == TypeRef.Named("Any") && expected != null) expected
                    else resolveDeclaredType(p.type)
                }
                // `std::sequence { std::yield(1) }` - a lambda passed where contextual
                // receivers are expected inherits them even though it names none. The
                // body cannot refer to them by name, but a call inside it can take them
                // as its own contextual receivers, which is what the builder APIs rely on.
                val inheritsReceivers = bracketReceivers.isEmpty() && !expectedReceivers.isNullOrEmpty()
                val receiverTypes = if (inheritsReceivers) expectedReceivers!! else declaredReceivers
                // `{ body }` with no parameter list carries the parser's implicit `it`.
                // Where the expected callable takes no parameters there is nothing for
                // `it` to stand for, so it is dropped rather than reported as an arity
                // mismatch against a lambda the author never gave parameters to.
                val implicitItOnly = expr.params.size == 1 &&
                    expr.params[0].name == "it" &&
                    expr.params[0].type == TypeRef.Named("Any")
                val dropsImplicitIt = implicitItOnly && expectedParams != null && expectedParams.isEmpty()
                // A bare `{ body }` that never reads `it` declares no parameters. If
                // the expected callable takes some, they are its parameters - the
                // body simply ignores them - so they are supplied here rather than
                // reported as an arity mismatch against a list the author never
                // wrote. `it` is the name when there is exactly one, so a body that
                // does read `it` has already been given it by the parser.
                val suppliedParams: List<Param> = if (
                    !expr.paramsWritten && expr.params.isEmpty() && !expectedParams.isNullOrEmpty()
                ) {
                    expectedParams.indices.map { i ->
                        Param(if (expectedParams.size == 1) "it" else "__arg$i", TypeRef.Named("Any", synthesized = true))
                    }
                } else emptyList()
                val effectiveParams = when {
                    dropsImplicitIt -> emptyList()
                    suppliedParams.isNotEmpty() -> suppliedParams
                    else -> expr.params
                }
                val effectiveParamTypes = when {
                    dropsImplicitIt -> emptyList()
                    suppliedParams.isNotEmpty() -> expectedParams!!
                    else -> paramTypes
                }
                // Only the outer call seeds these; nested lambdas resolve on their own.
                expectedLambdaParamTypes = null
                expectedLambdaReceiverTypes = null
                // Each capture is resolved against the scope around the lambda, then
                // bound inside it under the name the body uses - the alias where one
                // was written, the source name otherwise.
                val captureTypes = bracketCaptures.map { capture ->
                    val outer = table.lookupVariable(capture.source)
                    if (outer == null) {
                        errors.add(
                            "line ${capture.line}: cannot capture '${capture.source}' - " +
                                "no such binding in the scope around this lambda",
                        )
                    }
                    if (outer != null) checkCaptureMode(capture, outer.type)
                    capture to (outer?.type ?: IrType.Any)
                }
                table.pushScope()
                // The index of the scope just pushed - the lambda's own bindings live
                // here, and anything below it belongs to the scope around the lambda.
                val captureFloor = table.scopeDepth() - 1
                for ((capture, type) in captureTypes) {
                    val outer = table.lookupVariable(capture.source)
                    table.defineVariable(
                        if (outer != null) capturedSymbol(capture, outer)
                        else VariableSymbol(capture.name, type),
                    )
                }
                for (i in effectiveParams.indices) {
                    table.defineVariable(VariableSymbol(effectiveParams[i].name, effectiveParamTypes[i]))
                }
                for (i in bracketReceivers.indices) {
                    table.defineVariable(VariableSymbol(bracketReceivers[i].name, receiverTypes[i], mutable = false))
                }
                if (inheritsReceivers) {
                    receiverTypes.forEachIndexed { i, t ->
                        table.defineVariable(
                            VariableSymbol(lambdaReceiverName(expr.line, expr.column, i), t, mutable = false),
                        )
                    }
                }
                // Resolve the body with locals in scope, capturing return-value types to infer retType.
                val captured = mutableListOf<IrType>()
                val savedReturns = lambdaReturnTypes
                lambdaReturnTypes = captured
                // A lambda's receivers are in scope for its body the same way `with`
                // makes a value's extensions callable bare, so `[sk: Sink!]{ push(1) }`
                // and an inherited receiver both reach `push` without naming it.
                val bodyContexts = receiverTypes.mapIndexed { i, t ->
                    val ref = if (inheritsReceivers) {
                        lambdaReceiverRef(expr, i)
                    } else {
                        Expr.Identifier(bracketReceivers[i].name, expr.line, expr.column)
                    }
                    ref as Expr to t
                }
                if (bodyContexts.isNotEmpty()) contextualValues.addLast(bodyContexts)
                lambdaFrames.add(
                    LambdaFrame(
                        floor = captureFloor,
                        // A block passed to an `inline` callable is not a
                        // closure: it is substituted where it was written, so it
                        // reaches the scope around it the way a loop body does
                        // and needs no capture list to say so.
                        defaultMode = expr.captureDefault ?: inlineCallableDefault,
                    ),
                )
                resolveBody(expr.body, IrType.Unit)
                val frame = lambdaFrames.removeAt(lambdaFrames.size - 1)
                if (bodyContexts.isNotEmpty()) contextualValues.removeLast()
                lambdaReturnTypes = savedReturns
                table.popScope()
                val retType = captured.firstOrNull() ?: IrType.Unit
                val type = IrType.Function(
                    effectiveParamTypes,
                    retType,
                    variadic = expr.variadic,
                    receivers = receiverTypes,
                    kind = expr.kind,
                )
                table.defineLambdaType(expr.line, expr.column, type)
                val resolvedCaptures = linkedMapOf<String, CaptureMode>()
                bracketCaptures.forEach { resolvedCaptures[it.source] = it.mode }
                frame.inferred.forEach { (name, mode) ->
                    if (name !in resolvedCaptures) resolvedCaptures[name] = mode
                }
                table.defineLambdaCaptures(expr.line, expr.column, resolvedCaptures)
                currentFuncTypeParams = savedLambdaTypeParams
                type
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
            is Expr.MetaInvoke -> error("MetaInvoke '${expr.name}' reached TypeResolver at line ${expr.line}")
        }
    }

    /** Return type of a user-defined `impl deref`, guarded against cycles/erasure. */
    private fun userDerefType(type: IrType.Named): IrType.Named? {
        val method = table.lookupMethod(type.name, "operDeref") ?: return null
        val result = table.lookupFunction(method)?.returnType as? IrType.Named ?: return null
        return result.takeIf { it.name != type.name && it != IrType.Named("Any") }
    }

    private fun sourcePackTypeName(internalName: String, visiting: Set<String> = emptySet()): String {
        if (!internalName.startsWith("__Tuple_") || internalName in visiting) return internalName
        val tuple = table.lookupStruct(internalName) ?: return internalName
        val nextVisiting = visiting + internalName
        return tuple.fields.joinToString(", ", "Tuple<", ">") { field ->
            sourceTypeName(field.type, nextVisiting)
        }
    }

    private fun sourceTypeName(type: IrType, visiting: Set<String>): String = when (type) {
        is IrType.Named -> sourcePackTypeName(type.name, visiting)
        else -> type.toString()
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
                // `arr().fill<T>(count)` - pre-allocates `count` slots; returns the array.
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
            else -> {
                // A member declared as a property on the aggregate is read without
                // parentheses, so say that rather than "no method".
                if (declaredAggregateProperty(arrType, name)) {
                    errors.add("line $line: property '$name' must be accessed without parentheses")
                } else {
                    errors.add("line $line: no method '$name' on array")
                }
                null
            }
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
        // An erased generic is `Any`, and which concrete type it will be is not
        // known here - `value.display(formatter)` inside `format<T>` is the case
        // that matters. Property access on `Any` already defers to runtime and
        // so does comparison; a method call has to, on the same grounds, and the
        // interpreter dispatches it from the `__type` the value carries.
        if (receiverType == IrType.Any) {
            args.forEach { resolveExpr(it) }
            return IrType.Any
        }
        // `2.scale(7)` - the receiver call. `scale` is not a method on Int; it is a
        // callable whose contextual receiver the target supplies. This is the same
        // spelling an extension method uses, and it reads the same way, which is
        // why a receiver is written here rather than passed as a leading argument.
        val callable = table.lookupVariable(name)?.type as? IrType.Function
        if (callable != null && callable.receivers.size == 1) {
            return resolveCallableArguments(
                name,
                callable,
                args,
                line,
                explicitReceivers = listOf(receiverType),
            )
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
        else -> {
            if (declaredAggregateProperty(IrType.Set(IrType.Any), name)) {
                errors.add("line $line: property '$name' must be accessed without parentheses")
            } else {
                errors.add("line $line: no method '$name' on set")
            }
            null
        }
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
        else -> {
            if (declaredAggregateProperty(map, name)) {
                errors.add("line $line: property '$name' must be accessed without parentheses")
            } else {
                errors.add("line $line: no method '$name' on map")
            }
            null
        }
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
        // `Array<T>` against `Array<Int>`, where `T` is a type parameter nothing
        // bound. A generic function with no arguments has nothing to infer from
        // - `emptyArray<T>()` is the whole point of being empty - so the
        // position it is used in is what decides, and an unbound parameter
        // unifies with whatever is expected there.
        if (declared is IrType.Array && actual is IrType.Array && isUnboundTypeParam(actual.element)) {
            return true
        }
        if (declared is IrType.Named && actual is IrType.Named &&
            declared.name == actual.name && declared.args.size == actual.args.size &&
            actual.args.isNotEmpty() && actual.args.any(::isUnboundTypeParam)
        ) {
            return declared.args.indices.all { i ->
                isUnboundTypeParam(actual.args[i]) || isCompatible(declared.args[i], actual.args[i])
            }
        }
        // `escaping` describes the *position*, not the value: a lambda has no
        // opinion about whether it will be kept, and it is the parameter or field
        // that says so. Comparing the two would reject every closure written for
        // an escaping position, which is the only way one is ever supplied.
        // `escaping` and `inline` describe the *position*, not the value: a block
        // has no opinion about whether it will be kept or substituted, and it is
        // the parameter that says so. Comparing them would reject every block
        // written for such a position, which is the only way one is supplied.
        if (declared is IrType.Function && actual is IrType.Function &&
            (declared.isEscaping != actual.isEscaping || declared.isInline != actual.isInline)
        ) {
            return isCompatible(
                declared.copy(isEscaping = false, isInline = false),
                actual.copy(isEscaping = false, isInline = false),
            )
        }
        // `react (…) -> R` says the callable *may* use reactive state, so a plain
        // callable satisfies it: a block that reads no `remember` is a valid
        // reactive block. The converse does not hold - a reactive callable cannot
        // be passed where a plain one is wanted, because the caller would run it
        // outside any owner.
        if (declared is IrType.Function && actual is IrType.Function &&
            declared.kind != actual.kind && declared.kind.accepts(actual.kind)
        ) {
            return isCompatible(
                declared.copy(kind = actual.kind),
                actual,
            )
        }
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
            if (actual.name == declared.name) return true
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
            // Float promotion: Float < Double < Decimal
            if (a == IrType.Decimal || b == IrType.Decimal) return IrType.Decimal
            if (a == IrType.Double || b == IrType.Double) return IrType.Double
            return IrType.Float
        }
        // Integer promotion: Byte < Short < Int < Long < Cent
        val rank = mapOf(IrType.Byte to 0, IrType.UByte to 0, IrType.Short to 1, IrType.UShort to 1,
            IrType.Int to 2, IrType.UInt to 2, IrType.Long to 3, IrType.ULong to 3,
            IrType.ISize to 3, IrType.USize to 3,
            IrType.Cent to 4, IrType.UCent to 4)
        val ra = rank[a] ?: return null
        val rb = rank[b] ?: return null
        return if (ra >= rb) a else b
    }

    /** Converts an inferred IR type back to the source type form used by type functions. */
    private fun typeRefOf(type: IrType): TypeRef = when (type) {
        IrType.Int -> TypeRef.Named("Int")
        IrType.UInt -> TypeRef.Named("UInt")
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
        IrType.Float -> TypeRef.Named("Float")
        IrType.Decimal -> TypeRef.Named("Decimal")
        IrType.Any -> TypeRef.Named("Any", synthesized = true)
        is IrType.Array -> TypeRef.Array(typeRefOf(type.element))
        is IrType.Map -> TypeRef.Map(typeRefOf(type.key), typeRefOf(type.value))
        is IrType.Set -> TypeRef.Set(typeRefOf(type.element))
        is IrType.Function -> TypeRef.Function(
            type.params.map(::typeRefOf),
            typeRefOf(type.ret),
            type.receivers.map(::typeRefOf),
            type.kind,
        )
        is IrType.Task -> TypeRef.Named("Task", listOf(typeRefOf(type.result)))
        is IrType.Tuple -> TypeRef.Tuple(type.elements.map(::typeRefOf))
        is IrType.Variant -> TypeRef.Named("Var", type.elements.map(::typeRefOf))
        is IrType.Nullable -> TypeRef.Nullable(typeRefOf(type.inner))
        is IrType.Pointer -> TypeRef.Pointer(typeRefOf(type.inner))
        is IrType.Named -> TypeRef.Named(type.name)
    }

    /** The operand-type key an operator overload is registered under. */
    private fun operandKeyOf(type: IrType): String? = when (type) {
        is IrType.Named -> type.name
        is IrType.Pointer -> operandKeyOf(type.inner)
        is IrType.Nullable -> operandKeyOf(type.inner)
        else -> type.toString()
    }

    /**
     * True when a `op=` will be answered by the type's in-place operator.
     *
     * The operand is still resolved, because it is a real expression whatever
     * happens to the assignment; only the desugared binary operation is skipped.
     */
    private fun resolvesInPlace(compoundOp: TokenType?, desugared: Expr, targetType: IrType): Boolean {
        val op = compoundOp ?: return false
        val named = targetType as? IrType.Named ?: return false
        val binary = desugared as? Expr.Binary ?: return false
        val member = compoundAssignMemberName(op) ?: return false
        val operandType = resolveExpr(binary.right) ?: return false
        return table.lookupOperator(named.name, member, operandKeyOf(operandType)) != null
    }

    /** `PLUS` → `oper+=`, and the rest of the compound-assignment family. */
    private fun compoundAssignMemberName(op: TokenType): String? = when (op) {
        TokenType.PLUS -> "oper+="
        TokenType.MINUS -> "oper-="
        TokenType.STAR -> "oper*="
        TokenType.SLASH -> "oper/="
        TokenType.PERCENT -> "oper%="
        TokenType.AMP -> "oper&="
        TokenType.PIPE -> "oper|="
        TokenType.CARET -> "oper^="
        TokenType.SHIFT_LEFT -> "oper<<="
        TokenType.SHIFT_RIGHT -> "oper>>="
        else -> null
    }

    /**
     * Whether [typeName] has said what equality means for it.
     *
     * Satisfied by a declared `oper==` (against itself or anything else), by a
     * stated `PartialEqual`/`Equal` conformance, or by being an enum - a
     * payload-free case is a name and compares as one. Everything not a pack
     * keeps the built-in comparison.
     */
    private fun comparesEqual(typeName: String): Boolean {
        if (table.lookupEnum(typeName) != null) return true
        if (table.lookupSlot(typeName) != null) return true
        // The member itself, not the conformance. An operator member of a spec
        // is optional (`SymbolCollector.isOperatorMember`), so `impl PartialEqual
        // for Vec2` with an empty body records the capability while supplying no
        // `==` - and accepting that here would put back the silent fallback this
        // rule exists to remove. Derivation generates a real member, so
        // `derive Equal for Vec2` still passes.
        if (table.lookupOperator(typeName, "oper==", null) != null) return true
        // A pack the resolver has never seen declared is not this rule's business.
        return table.lookupStruct(typeName) == null
    }

    private fun resolveBinaryType(op: TokenType, left: IrType, right: IrType, line: Int): IrType? {
        // An operator may also be declared with a primitive on the left - `2 * vec`
        // is `impl oper* for Int` taking a Vec. Such an overload must name its
        // operand, so only the operand-keyed member is consulted: nothing here can
        // shadow the built-in arithmetic of `2 * 3`.
        if (left !is IrType.Named && left in IrType.numericTypes) {
            operOverloadName(op)?.let { operName ->
                val key = operandKeyOf(right)
                if (key != null && key != left.toString()) {
                    table.lookupMethod(left.toString(), "$operName@$key")?.let { mangled ->
                        return table.lookupFunction(mangled)?.returnType
                    }
                }
            }
        }
        // Operator overloading on user types.
        if (left is IrType.Named) {
            // A declared operator wins; otherwise `<` `<=` `>` `>=` come from
            // `<=>` and `!=` from `==` (the comparison DIP, §5.4/§5.5). All the
            // rewrites answer Bool.
            when (val plan = comparisonPlan(op, left.name, operandKeyOf(right), table)) {
                is ComparisonPlan.Direct -> return table.lookupFunction(plan.mangled)?.returnType
                is ComparisonPlan.Spaceship, is ComparisonPlan.NegatedEquals -> return IrType.Bool
                null -> Unit
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
                // A pack that never said what equal means used to compare anyway,
                // and the backends did not agree on what that meant: the
                // interpreter compared structurally and LLVM compared addresses,
                // so the same program answered differently under `az run` and
                // `az build`. It is an error now, and the message says how to fix
                // it. Everything else the branch accepts is unaffected.
                val bareNamed = leftBare as? IrType.Named
                if (left == right && bareNamed != null && !nullCompare && !comparesEqual(bareNamed.name)) {
                    errors.add(
                        "line $line: cannot compare two '${bareNamed.name}' values - " +
                            "${bareNamed.name} does not implement PartialEqual; add " +
                            "'derive Equal for ${bareNamed.name}' to compare it field by field, " +
                            "or declare 'oper== [self: ${bareNamed.name}&](rhs: ${bareNamed.name}&): Bool'",
                    )
                    return null
                }
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
            // `a <=> b` on a built-in: the integers, `Char`, `Bool` and `String`
            // are totally ordered and answer `Compare`; the floating-point types
            // are not - a NaN relates to nothing - and answer `PartialCompare`.
            // Which one comes back is the whole totality claim, so it is decided
            // here rather than left to the operand types to agree on.
            TokenType.SPACESHIP -> {
                val comparable = left == right &&
                    (left in IrType.numericTypes || left == IrType.Char ||
                        left == IrType.Bool || left == IrType.String)
                when {
                    left == IrType.Any || right == IrType.Any -> IrType.Named("PartialCompare")
                    !comparable -> {
                        errors.add("line $line: cannot compare $left and $right with '<=>'")
                        null
                    }
                    left in IrType.floatTypes -> IrType.Named("PartialCompare")
                    else -> IrType.Named("Compare")
                }
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

    /**
     * The type an expression takes on when it is an untyped numeric literal.
     *
     * `0` is written the same whether it means an `Int`, a `UInt` or a `Double`, so a
     * literal in a position that wants a numeric type is that type - `var x: Double = 0`
     * and `x == 0` both read naturally. Only literals are adopted this way: a typed
     * `Int` value still needs an explicit `as`, so the conversion stays where it can
     * be seen in the source.
     */
    private fun adoptLiteralType(expr: Expr, own: IrType, wanted: IrType): IrType {
        // `1.0` is written the same whether it means a `Double` or a `Float`, so an
        // unsuffixed real literal takes the floating-point type asked for.
        if (own in IrType.floatTypes && wanted in IrType.floatTypes && isUntypedDoubleLiteral(expr)) return wanted
        if (own !in IrType.integerTypes || wanted !in IrType.numericTypes) return own
        untypedIntLiteral(expr) ?: return own
        // Only a floating-point target adopts an integer literal. A sized integer
        // keeps the language's rule that its width is stated, by suffix or by cast.
        return if (wanted in IrType.floatTypes) wanted else own
    }

    /** True for a real literal written without a width suffix. */
    private fun isUntypedDoubleLiteral(expr: Expr): Boolean = when (expr) {
        is Expr.DoubleLiteral -> expr.suffix == NumericSuffix.NONE
        is Expr.Grouping -> isUntypedDoubleLiteral(expr.expr)
        is Expr.Unary ->
            expr.op in setOf(TokenType.MINUS, TokenType.PLUS) && isUntypedDoubleLiteral(expr.operand)
        else -> false
    }

    /**
     * The value of an unsuffixed integer literal, or null.
     *
     * A suffix (`3L`, `7u`) states a type, so such a literal is not untyped and keeps
     * what it was written as.
     */
    private fun untypedIntLiteral(expr: Expr): Long? = when (expr) {
        is Expr.IntLiteral -> if (expr.suffix == NumericSuffix.NONE) expr.value else null
        is Expr.Grouping -> untypedIntLiteral(expr.expr)
        is Expr.Unary -> when (expr.op) {
            TokenType.MINUS -> untypedIntLiteral(expr.operand)?.let { -it }
            TokenType.PLUS -> untypedIntLiteral(expr.operand)
            else -> null
        }
        else -> null
    }

    /**
     * The variable a write ultimately lands on, if the target is rooted in one.
     *
     * `p.pos.x = 1` and `xs[0] = 1` both write through `p` / `xs`, so a write is
     * checked against the binding at the root of the access chain. An expression
     * with no such root (a call result, a literal) yields null and is left alone.
     */
    private fun writeRoot(target: Expr): VariableSymbol? = when (target) {
        is Expr.Identifier -> table.lookupVariable(target.name)
        // A borrow points at its operand, so a write through one lands there.
        is Expr.Isolated if target.op.isBorrow -> writeRoot(target.value)
        is Expr.Member -> writeRoot(target.target)
        is Expr.SafeMember -> writeRoot(target.target)
        is Expr.Index -> writeRoot(target.target)
        is Expr.Grouping -> writeRoot(target.expr)
        else -> null
    }

    /**
     * Rejects a write through a binding whose *value* is immutable (`val`/`fin`).
     *
     * Reassigning the name is a separate question - `val` allows it, `fin` does
     * not - and is checked where the assignment itself is resolved.
     */
    private fun checkValueMutable(target: Expr, line: Int, what: String): Boolean {
        val rootName = pathRoot(target)?.name
        if (rootName != null) checkCapture(rootName, line)
        val root = writeRoot(target) ?: return true
        if (root.valueMutable) return true
        errors.add(
            "line $line: cannot $what through '${root.name}' - its value is immutable; " +
                "declare it 'var' (or 'let' to fix only the name)",
        )
        return false
    }

    /**
     * Rejects touching a union outside an `unsafe` block.
     *
     * Reading a union member the program did not last write reinterprets bytes,
     * and nothing in the type records which member is live - so no check can
     * establish that a read is meaningful. `unsafe` is where the author takes
     * that on, which is why both the declaration and every use ask for it.
     */
    private fun requireUnsafeForUnion(name: String, line: Int) {
        if (unsafeContext) return
        errors.add(
            "line $line: union '$name' can only be used inside an 'unsafe { … }' block - " +
                "reading a member the program did not last write reinterprets its bytes",
        )
    }

    /**
     * `Value(i: 42)` - a union is built by naming exactly one of its members.
     *
     * Only one member can be live at a time, so initializing more than one would
     * be a contradiction and initializing none would leave the storage
     * undefined. Naming the member also says which one the author means to be
     * live, which is the only record of that anywhere.
     */
    private fun resolveUnionCtor(expr: Expr.Call, union: StructType): IrType? {
        val result = IrType.Named(expr.callee)
        if (expr.args.size != 1) {
            errors.add(
                "line ${expr.line}: union '${expr.callee}' is built from exactly one member, " +
                    "e.g. '${expr.callee}(${union.fields.first().name}: …)' - got ${expr.args.size} arguments",
            )
            return result
        }
        val argument = expr.args[0]
        val member = when (argument) {
            is Expr.NamedArg -> union.fields.firstOrNull { it.name == argument.name } ?: run {
                errors.add("line ${expr.line}: union '${expr.callee}' has no member '${argument.name}'")
                return result
            }
            // A single positional argument initializes the first member, matching
            // how C initializes a union from a bare initializer.
            else -> union.fields.first()
        }
        val value = (argument as? Expr.NamedArg)?.value ?: argument
        val valueType = resolveExpr(value) ?: return result
        if (!isCompatible(member.type, adoptLiteralType(value, valueType, member.type))) {
            errors.add(
                "line ${expr.line}: member '${member.name}' of union '${expr.callee}': " +
                    "expected ${member.type}, got $valueType",
            )
        }
        return result
    }

    /**
     * A borrow that is still live: which binding it points at, and how.
     *
     * @property owner the binding borrowed from
     * @property exclusive true for `x.!`, false for `x.&`
     * @property line where the borrow was created, for diagnostics
     */
    private data class ActiveBorrow(val owner: String, val exclusive: Boolean, val line: Int)

    /**
     * Borrows held by a *binding*, keyed by the binding's name.
     *
     * Only a borrow bound to a name (`let m: User! = user.!`) outlives the
     * expression it appears in; one passed straight to a call
     * (`rename(user.!)`) ends when the call returns, so it is checked against
     * what is already live and then dropped. That is the whole of the borrow's
     * "active lifetime" the model asks about, without a region analysis the rest
     * of the pass has no use for.
     */
    private val activeBorrows = mutableMapOf<String, ActiveBorrow>()

    /** Borrows created while resolving the current expression, dropped after it. */
    private var pendingBorrows = mutableListOf<ActiveBorrow>()

    /**
     * Every borrow live right now - bound ones plus those in this expression.
     *
     * A bound borrow ends when its holder does, so one whose holder is no longer
     * in scope is dropped here rather than at every `popScope`.
     */
    private fun liveBorrowsOf(owner: String): List<ActiveBorrow> {
        activeBorrows.keys.filter { table.lookupVariable(it) == null }.forEach(activeBorrows::remove)
        return activeBorrows.values.filter { it.owner == owner } +
            pendingBorrows.filter { it.owner == owner }
    }

    /**
     * Checks a new borrow of [owner] against the ones already live.
     *
     * A mutable borrow must be exclusive for its lifetime, so it conflicts with
     * every other borrow; shared borrows coexist freely and only conflict with a
     * mutable one.
     */
    private fun checkBorrowConflict(owner: String, exclusive: Boolean, line: Int) {
        val conflict = liveBorrowsOf(owner).firstOrNull { exclusive || it.exclusive } ?: return
        val existing = if (conflict.exclusive) "a mutable borrow" else "a shared borrow"
        val attempted = if (exclusive) "mutably" else "immutably"
        errors.add(
            "line $line: cannot borrow '$owner' $attempted - $existing of it is still active " +
                "from line ${conflict.line}; a mutable borrow must be exclusive for its lifetime",
        )
    }

    /**
     * Rejects using the owner itself while a mutable borrow of it is live.
     *
     * The borrow is exclusive, so for as long as it lasts it is the only way to
     * reach the value - reading through the owner would be a second path to it.
     */
    private fun checkNotMutablyBorrowed(name: String, line: Int) {
        val exclusive = liveBorrowsOf(name).firstOrNull { it.exclusive } ?: return
        errors.add(
            "line $line: cannot use '$name' while a mutable borrow of it is active " +
                "from line ${exclusive.line}; the borrow is exclusive for its lifetime",
        )
    }

    /**
     * Bindings whose value has been given away by `take`, and where.
     *
     * Tracking is per-function and flows in statement order. A branch is checked
     * with its own copy, and the moves it makes are *not* merged back: a value
     * taken on only one path is still taken on that path, but reporting it
     * afterwards would blame code that may never run. That keeps the check
     * sound for the straight-line case, which is where `take` is actually
     * written, without inventing a phi-merge the rest of the pass has no use for.
     */
    private val movedBindings = mutableMapOf<String, Int>()

    /**
     * Checks a conditionally executed body without letting its moves escape.
     *
     * A `take` on one path really does move the value on that path, so the body
     * is checked with the moves it makes; but blaming the code *after* the
     * branch would fault a program for something that may never run. Moves made
     * before the branch still apply inside it.
     */
    private fun inBranch(body: () -> Unit): Map<String, Int> {
        val before = movedBindings.toMap()
        body()
        val made = movedBindings.filterKeys { it !in before }
        movedBindings.clear()
        movedBindings.putAll(before)
        return made
    }

    /**
     * Checks a loop body, where every move happens again on the next iteration.
     *
     * A binding the loop did not declare is moved once and read again on the
     * second pass, which is a use-after-move the branch rule would let through:
     * one iteration looks exactly like one conditional path.
     */
    private fun inLoop(body: () -> Unit) {
        val enclosing = table.enclosingVariableNames()
        for ((name, line) in inBranch(body)) {
            if (name !in enclosing) continue
            errors.add(
                "line $line: '$name' is moved inside a loop, so the next iteration would use a value that is " +
                    "gone; move it after the loop, or create an independent value with '$name.clone()'",
            )
        }
    }

    /** Records that [target]'s value was taken at [line], if it names a binding. */
    /**
     * Reports handing ownership to a parameter that only borrows.
     *
     * `look(take h)` where `look` takes `H&` costs the caller the value and buys
     * the callee nothing - it wanted to look. Left unchecked the move is still
     * recorded, so the failure surfaces at the next innocent use of `h` rather
     * than here.
     */
    private fun checkNotGivenToBorrow(arg: Expr, index: Int, func: FunctionSymbol, callee: String, line: Int) {
        val op = (arg as? Expr.Isolated)?.op?.takeIf { it == OwnershipOp.TAKE || it == OwnershipOp.LEND } ?: return
        val name = func.paramNames.getOrNull(index) ?: (index + 1).toString()
        val sigil = if (index in func.exclusiveParams) "!" else "&"
        val operand = (arg.value as? Expr.Identifier)?.name
        val fix = operand?.let { "'$it.$sigil'" } ?: "it with '.$sigil'"
        errors.add(
            "line $line: cannot ${op.spelling} ${operand?.let { "'$it'" } ?: "a value"} to parameter '$name' " +
                "of '$callee' - the parameter borrows, so it never takes ownership and the value would be " +
                "given away for nothing; write $fix to borrow it for the call",
        )
    }

    /** Whether an argument was written `lend x`. */
    private fun isLend(arg: Expr): Boolean =
        arg is Expr.Isolated && arg.op == OwnershipOp.LEND

    /**
     * Pairs `lend` at the call with `return` at the declaration.
     *
     * The two are one contract seen from its two ends, so neither reads
     * correctly alone: a `lend` the callee never gives back is a move written
     * as a loan, and a `return` parameter fed by anything else never got
     * ownership to return.
     */
    private fun checkLend(arg: Expr, index: Int, func: FunctionSymbol, callee: String, line: Int) {
        val name = func.paramNames.getOrNull(index) ?: (index + 1).toString()
        val returns = index in func.returnedParams
        if (isLend(arg) && !returns) {
            errors.add(
                "line $line: cannot lend to parameter '$name' of '$callee' - it does not give ownership back; " +
                    "declare it '$name: return …' to lend to it, or write 'take' to give the value away",
            )
        } else if (returns && !isLend(arg)) {
            errors.add(
                "line $line: parameter '$name' of '$callee' gives ownership back, so its argument is lent; " +
                    "write 'lend' before it",
            )
        }
    }

    private fun recordMove(target: Expr, line: Int) {
        val name = movableRootName(target) ?: return
        movedBindings[name] = line
    }

    /** The binding a `take` moves out of, or null when the operand owns nothing named. */
    /**
     * The dotted path a `take` moves out of - `a.db`, `self.buffer` - or null
     * when the operand is not a field of a named binding.
     *
     * A field is moved independently of the value holding it, so it needs a name
     * of its own: `take a.db` spends `a.db` and leaves the rest of `a` alone.
     */
    private fun movablePath(target: Expr): String? = when (target) {
        is Expr.Identifier -> target.name.takeIf { table.lookupVariable(it) != null }
        is Expr.Member -> movablePath(target.target)?.let { "$it.${target.name}" }
        is Expr.Grouping -> movablePath(target.expr)
        else -> null
    }

    /** The binding at the root of a field path, for reporting who owns it. */
    private fun pathRoot(target: Expr): Expr.Identifier? = when (target) {
        is Expr.Identifier -> target
        is Expr.Member -> pathRoot(target.target)
        is Expr.Grouping -> pathRoot(target.expr)
        else -> null
    }

    /**
     * Reports moving a field out through something that only borrows its owner.
     *
     * Taking a field away changes the value it belonged to, so it asks the same
     * access a write does. A shared borrow does not grant that, and an owner
     * that is itself only borrowed never had the field to give.
     */
    private fun checkFieldMoveAccess(target: Expr, op: OwnershipOp, line: Int) {
        if (target !is Expr.Member) return
        val root = pathRoot(target) ?: return
        val owner = root.name
        val reason = when {
            owner in sharedBorrowedNames -> "'$owner' is a shared borrow, which may not be changed"
            owner in borrowedNames -> null
            activeBorrows[owner]?.exclusive == false -> "'$owner' is a shared borrow of " +
                "'${activeBorrows.getValue(owner).owner}', which may not be changed"
            else -> null
        } ?: return
        errors.add(
            "line $line: cannot ${op.spelling} '${movablePath(target)}' - $reason; " +
                "take an exclusive borrow of '$owner' to move a field out of it",
        )
    }

    private fun movableRootName(target: Expr): String? = when (target) {
        is Expr.Identifier -> target.name.takeIf { table.lookupVariable(it) != null }
        // `take self.database` moves the field, not the owner; the owner stays
        // usable, so nothing is recorded against it (see the ownership model's
        // "moving out of fields" - the field's own state is left to a later pass).
        is Expr.Grouping -> movableRootName(target.expr)
        else -> null
    }

    /**
     * Reports a read of a binding whose value was taken.
     *
     * Reassigning re-establishes a value, which is why `var`/`val` may be used
     * again after a move and `let`/`fin` may not - the difference falls out of
     * the binding keyword rather than being a separate rule.
     */
    /**
     * Checks one capture against the mode it wrote.
     *
     * Each mode asks something different of the value (§4.2): a copy needs
     * `Copy`, a clone needs `Clone`, and a move needs nothing but takes the
     * binding with it - so `take` records the move and the existing
     * use-after-take machinery reports the rest. A borrow asks nothing.
     */
    /** True when [name] resolves to a binding the innermost open lambda owns. */
    private fun isBoundInsideCurrentLambda(name: String): Boolean {
        val frame = lambdaFrames.lastOrNull() ?: return false
        if (name in frame.inferred) return true
        val index = table.variableScopeIndex(name) ?: return false
        return index >= frame.floor
    }

    /**
     * A closure that escapes must own everything it captures (§4.6).
     *
     * A borrow may not outlive its owner, so a closure that outlives the scope it
     * was made in cannot hold one - which is the same rule as "a borrow may not
     * survive a suspension", and offers the same three fixes.
     */
    private fun checkEscapingCaptures(lambda: Expr.Lambda, what: String) {
        val borrowed = table.lookupLambdaCaptures(lambda.line, lambda.column).orEmpty().filterValues {
            it == CaptureMode.SHARED || it == CaptureMode.MUTABLE
        }
        for ((source, mode) in borrowed) {
            val captureLine = lambda.captures.firstOrNull { it.source == source }?.line ?: lambda.line
            errors.add(
                "line $captureLine: this closure $what while borrowing " +
                    "'$source' through '${mode.spelling}', which does not outlive it; write " +
                    "'[; $source.clone()]' or '[; take $source]'",
            )
        }
    }

    private fun checkCaptureMode(capture: Capture, type: IrType) {
        when (capture.mode) {
            CaptureMode.SHARED, CaptureMode.MUTABLE -> {}
            CaptureMode.MOVE -> movedBindings[capture.source] = capture.line
            CaptureMode.COPY -> {
                // Only meaningful once the capability lattice is in scope - see
                // checkByValueTransfer for why.
                if (!table.conformsTo("Int", "Copy")) return
                val name = conformanceName(type) ?: return
                if (currentFuncTypeParams.contains(name)) return
                if (table.conformsTo(name, "Copy")) return
                val fixes = buildList {
                    add("'[; ${capture.source}.&]'")
                    if (table.conformsTo(name, "Clone")) add("'[; ${capture.source}.clone()]'")
                    add("'[; take ${capture.source}]'")
                }
                errors.add(
                    "line ${capture.line}: '${capture.source}' cannot be captured by copy - " +
                        "'$name' is not Copy; write one of ${fixes.joinToString(", ")}",
                )
            }
            CaptureMode.CLONE -> {
                if (!table.conformsTo("Int", "Copy")) return
                val name = cloneConformanceName(type) ?: return
                if (currentFuncTypeParams.contains(name)) return
                if (table.conformsTo(name, "Clone")) return
                errors.add(
                    "line ${capture.line}: '${capture.source}' cannot be captured by clone - " +
                        "'$name' is not Clone; write '[; ${capture.source}.&]' to reference it, " +
                        "or '[; take ${capture.source}]' to move it",
                )
            }
        }
    }

    private fun checkNotMoved(name: String, line: Int) {
        val movedAt = movedBindings[name] ?: return
        // `[; take s] { … s … }` - inside the closure, `s` is the closure's own
        // binding, not the one the move emptied. Only the outer name is stale.
        if (isBoundInsideCurrentLambda(name)) return
        errors.add(
            "line $line: use of taken value '$name' - its ownership transferred at line $movedAt; " +
                "use '$name.clone()' instead when both owners need a value",
        )
    }

    /**
     * Rejects handing a named non-`Copy` value to a by-value parameter.
     *
     * A `Copy` type duplicates implicitly, so passing one is a copy and the
     * caller keeps its value. Anything else would be giving the value away, and
     * the ownership model asks for that to be written: `take` to transfer, or
     * `.clone()` for a second independent value.
     *
     * Only a *named* binding is checked. A temporary - a call result, a literal,
     * a freshly constructed value - has no other owner, so passing it transfers
     * nothing that anyone could observe.
     */
    private fun checkByValueTransfer(arg: Expr, argType: IrType, line: Int) {
        // Only meaningful once the capability lattice is actually in scope. A
        // program that declares its own bare `Copy` - or never imports
        // `std.traits` - has no conformances to judge against, and rejecting
        // every by-value argument on that basis would be nonsense.
        if (!table.conformsTo("Int", "Copy")) return
        val name = (arg as? Expr.Identifier)?.name ?: return
        if (table.lookupVariable(name) == null) return
        val typeName = conformanceName(argType) ?: return
        if (currentFuncTypeParams.contains(typeName)) return
        if (table.conformsTo(typeName, "Copy")) return
        // Every value can be given away, so `take` is always an answer; an
        // independent copy is only one when the type can produce it.
        val fixes = buildList {
            add("transfer ownership with 'take $name'")
            if (table.conformsTo(typeName, "Clone")) {
                add("create an independent value with '$name.clone()'")
            }
        }
        errors.add(
            "line $line: cannot pass '$name' by ownership - '$typeName' is not Copy" +
                if (fixes.isEmpty()) "" else "; ${fixes.joinToString(", or ")}",
        )
    }

    /**
     * `x.clone()` where the compiler supplies the member.
     *
     * Returns null when this is not that call - the receiver is not `Clone`,
     * or the type writes its own `clone`, in which case the ordinary method
     * lookup takes it from here. Reporting a missing `Clone` is left to that
     * path too, so the diagnostic stays the usual "no method" one.
     */
    private fun resolveDefaultClone(expr: Expr.MethodCall): IrType? {
        val receiver = resolveExpr(expr.target) ?: return null
        val name = cloneConformanceName(receiver) ?: return null
        if (table.lookupMethod(name, "clone") != null) return null
        if (!table.conformsTo(name, "Clone")) return null
        return receiver
    }


    /**
     * The name a conformance is registered under for [type].
     *
     * A pack is a `Named`; a primitive lowers to its own IrType, whose spelling
     * is the name `impl Clone for Int` used.
     */
    private fun conformanceName(type: IrType): String? = when (type) {
        is IrType.Named -> type.name
        IrType.Int, IrType.UInt, IrType.Long, IrType.ULong, IrType.Byte, IrType.UByte,
        IrType.Short, IrType.UShort, IrType.Float, IrType.Double, IrType.Decimal,
        IrType.String, IrType.Char, IrType.Bool, IrType.Unit -> type.toString()
        else -> null
    }

    /**
     * The name a `Clone` conformance is registered under for [type].
     *
     * Wider than [conformanceName]: the aggregate builtins have no nominal
     * declaration, but a conformance can still be written against their type
     * constructor - `impl Clone for Array` lives in `std.container.array`.
     * Element types are erased on purpose; the conformance is on the container.
     *
     * This is deliberately separate from [conformanceName], which also feeds
     * the `Copy` and ownership checks. Naming the aggregates there would make
     * passing an array by value an error everywhere.
     */
    /**
     * The return type of a member declared in an `impl` on an aggregate builtin.
     *
     * `impl<T, N: Int> Array<T, N> { prop isEmpty … }` registers under the type
     * constructor, so a receiver of `IrType.Array` finds it by that name. This is
     * what lets the aggregates declare their members in the standard library
     * instead of the compiler hardcoding them.
     */
    /** True when the aggregate declares [name] as a property rather than a method. */
    private fun declaredAggregateProperty(type: IrType, name: String): Boolean {
        if (type is IrType.Named) return false
        val owner = cloneConformanceName(type) ?: return false
        val mangled = table.lookupMethod(owner, name) ?: return false
        return table.lookupFunction(mangled)?.memberCallStyle == MemberCallStyle.PROPERTY
    }

    /** True when the aggregate's declaration marks [name] `unsafe`. */
    private fun aggregateFieldIsUnsafe(type: IrType, name: String): Boolean {
        val owner = cloneConformanceName(type) ?: return false
        return table.lookupStruct(owner)?.field(name)?.isUnsafe == true
    }

    private fun declaredAggregateMember(type: IrType, name: String): IrType? {
        // A type-scoped constant answers for every value of the type, so it is
        // reachable from a receiver of any shape - `Array` inside its own `impl`
        // is a Named receiver, and `self.size` must find it there too.
        if (type is IrType.Named) return table.lookupTypeStatic(type.name, name)?.returnType
        val owner = cloneConformanceName(type) ?: return null
        // A field the declaration carries (`fin size: Int` on the Array pack) is a
        // member as much as an `impl` prop is; the receiver just happens to lower
        // to a builtin type rather than a Named one.
        table.lookupStruct(owner)?.field(name)?.let { return it.type }
        // A constant declared on the *type* - `impl Array:: { bridge fin size }` -
        // answers for every value of it, so a value reaches it too. The member is
        // realm-mangled with the type that owns it, so the realm is asked for.
        table.lookupTypeStatic(owner, name)?.let { return it.returnType }
        val mangled = table.lookupMethod(owner, name) ?: return null
        return table.lookupFunction(mangled)?.returnType
    }

    private fun cloneConformanceName(type: IrType): String? = when (type) {
        is IrType.Array -> "Array"
        is IrType.Map -> "Map"
        is IrType.Set -> "Set"
        is IrType.Tuple -> "Tuple"
        else -> conformanceName(type)
    }

    /**
     * `take v` / `lend v` / a borrow - records the move.
     *
     * None of these duplicates the operand, so none asks a capability of its
     * type: every value can be given away or lent. What is checked here is the
     * caller's claim on it afterwards.
     */
    private fun resolveOwnershipOp(expr: Expr.Isolated): IrType? {
        val type = resolveExpr(expr.value) ?: return null
        if (expr.op == OwnershipOp.TAKE || expr.op == OwnershipOp.LEND) {
            checkFieldMoveAccess(expr.value, expr.op, expr.line)
            // A borrow owns nothing, so it has nothing to give away. Without
            // this, `take h` inside `func f(h: H&)` hands the caller's value to
            // someone else permanently and never tells the owner.
            movableRootName(expr.value)?.let { name ->
                val borrowed = when {
                    name in borrowedNames ->
                        "'$name' is borrowed, so this function does not own it"
                    activeBorrows.containsKey(name) ->
                        "'$name' is a borrow of '${activeBorrows.getValue(name).owner}', which owns nothing"
                    else -> null
                }
                if (borrowed != null) {
                    errors.add(
                        "line ${expr.line}: cannot ${expr.op.spelling} '$name' - $borrowed; " +
                            "${expr.op.spelling} the owner instead, or declare '$name' by ownership so " +
                            "there is something to give away",
                    )
                    return type
                }
            }
            // The owner may not be moved while borrowed: a borrow points at
            // storage the move would take away.
            movableRootName(expr.value)?.let { owner ->
                liveBorrowsOf(owner).firstOrNull()?.let { borrow ->
                    errors.add(
                        "line ${expr.line}: cannot take '$owner' while it is borrowed " +
                            "from line ${borrow.line}; the borrow would outlive the value",
                    )
                }
            }
            // `lend` hands ownership over for the length of the call and gets it
            // back, so the binding is never left without a value to name.
            if (expr.op == OwnershipOp.TAKE) {
                recordMove(expr.value, expr.line)
                // A field is spent on its own: `take a.db` leaves the rest of
                // `a` usable, and only `a.db` unreadable.
                if (expr.value is Expr.Member) {
                    movablePath(expr.value)?.let { path ->
                        checkNotMoved(path, expr.line)
                        movedBindings[path] = expr.line
                    }
                }
            }
        }
        if (expr.op.isBorrow) {
            val exclusive = expr.op == OwnershipOp.BORROW
            // `val`/`fin` fix the value, so it cannot be lent out for writing.
            if (exclusive) checkValueMutable(expr.value, expr.line, "borrow mutably")
            movableRootName(expr.value)?.let { owner ->
                checkBorrowConflict(owner, exclusive, expr.line)
                pendingBorrows.add(ActiveBorrow(owner, exclusive, expr.line))
            }
        }
        return type
    }

    private fun resolveBinding(
        name: String,
        typeAnn: TypeAnnotation,
        initializer: Expr,
        line: Int,
        mutable: Boolean,
        valueMutable: Boolean = true,
    ) {
        // A fresh binding owns a value, whatever happened to an earlier one of
        // the same name. Cleared after the initializer, so `var x = take x`
        // still reports the read.
        if (table.lookupVariableInCurrentScope(name) != null) {
            errors.add("line $line: '$name' is already declared in this scope")
            return
        }
        val declaredType = if (typeAnn is TypeAnnotation.Explicit) {
            tryResolveType(typeAnn.ref, line) ?: return
        } else {
            null
        }
        val savedParams = expectedLambdaParamTypes
        val savedReceivers = expectedLambdaReceiverTypes
        if (initializer is Expr.Lambda && declaredType is IrType.Function) {
            expectedLambdaParamTypes = declaredType.params
            expectedLambdaReceiverTypes = declaredType.receivers
        }
        val initType = resolveExpr(initializer) ?: return
        expectedLambdaParamTypes = savedParams
        expectedLambdaReceiverTypes = savedReceivers
        when (typeAnn) {
            is TypeAnnotation.Explicit -> {
                if (!isCompatible(declaredType!!, adoptLiteralType(initializer, initType, declaredType))) {
                    errors.add("line $line: type mismatch in '$name': declared $declaredType but initializer is $initType")
                }
                table.defineVariable(VariableSymbol(name, declaredType, mutable, valueMutable = valueMutable))
            }
            is TypeAnnotation.Inferred -> {
                table.defineVariable(VariableSymbol(name, initType, mutable, valueMutable = valueMutable))
            }
        }
        movedBindings.remove(name)
        // `let m: User! = user.!` - the borrow now lives as long as `m` does,
        // rather than ending with the expression that made it.
        bindPendingBorrow(name)
    }

    /**
     * Attaches a borrow made by an initializer to the binding that holds it.
     *
     * Only one borrow can be the value of a binding, so the last one made while
     * resolving the initializer is the one that outlives it; any others were
     * intermediate and end there.
     */
    private fun bindPendingBorrow(name: String) {
        pendingBorrows.lastOrNull()?.let { activeBorrows[name] = it }
        pendingBorrows.clear()
    }

    private fun tryResolveType(ref: TypeRef, line: Int): IrType? {
        return try {
            // Type params (e.g. `T`) erase to `Any` - those of the enclosing function
            // and, inside an impl method, those of the receiver struct.
            val tpSet = currentFuncTypeParams +
                (currentReceiverType?.let { table.lookupStruct(it)?.typeParams?.toSet() } ?: emptySet())
            resolveDeclaredType(ref, tpSet)
        } catch (e: Exception) {
            errors.add("line $line: ${e.message}")
            null
        }
    }

    private fun resolveDeclaredType(ref: TypeRef, typeParams: Set<String> = currentFuncTypeParams): IrType {
        validateGenericInstantiation(ref, typeParams)
        return IrType.resolve(
            TypeFunctionEvaluator.resolve(
                ref,
                program?.typeFunctions.orEmpty(),
                unresolvedParams = typeParams,
            ),
            typeParams,
        )
    }



    /** One specializer per resolver, so its cache spans the whole resolution. */
    internal val packSpecializer by lazy { PackSpecializer(table) }

    /**
     * Whether [name] belongs to this application's specialized layout.
     *
     * Returns true for anything not governed by a condition - an ordinary struct, a
     * not-yet-concrete application, or an unconditional field - so this only ever
     * removes members a concrete layout genuinely excludes.
     */
    private fun specializedLayoutHasField(targetType: IrType.Named, name: String): Boolean {
        val declaration = genericDeclarations[targetType.name] ?: return true
        if (declaration.fields.none { it.condition != null }) return true
        if (declaration.fields.none { it.name == name }) return true
        val key = packSpecializer.keyFor(declaration, targetType) ?: return true
        return packSpecializer.specializeFor(key, declaration, targetType).fields.any { it.name == name }
    }

    /** Generic declarations by name, for reading their `where` clauses. */
    private val genericDeclarations: Map<String, TopLevel.Pack> by lazy {
        program?.items.orEmpty().filterIsInstance<TopLevel.Pack>().associateBy { it.name }
    }

    /** Combinations already validated, so a repeated use costs one set lookup. */
    private val validatedSpecializations = mutableSetOf<String>()

    /**
     * Rejects a generic type application whose `where` clause does not hold.
     *
     * This is the ordinary-generic counterpart of variadic specialization
     * validation, and shares its evaluator. It runs where a declared type is
     * resolved - after parsing, and never during overload filtering - and only once
     * per unique combination of arguments.
     *
     * A type application stays unvalidated while any argument is still a parameter
     * of the enclosing declaration: `Vec<T, N>` inside a generic says nothing until
     * `T` and `N` are concrete, and rejecting it there would reject every generic
     * that mentions a constrained type.
     */
    private fun validateGenericInstantiation(ref: TypeRef, typeParams: Set<String>) {
        val named = ref as? TypeRef.Named ?: return
        if (named.args.isEmpty()) return
        named.args.forEach { validateGenericInstantiation(it, typeParams) }
        val declaration = genericDeclarations[named.name] ?: return
        val clause = declaration.whereClause ?: return
        if (declaration.typeParams.size != named.args.size) return

        val bindings = mutableMapOf<String, ConstraintEvaluator.Binding>()
        for ((index, param) in declaration.typeParams.withIndex()) {
            val arg = named.args[index]
            // Still abstract - the combination is not known yet.
            if (arg is TypeRef.Named && (arg.name in typeParams || arg.name in declaration.typeParams)) return
            bindings[param] = ConstraintEvaluator.bindingOf(arg) ?: return
        }

        val key = "${named.name}<${named.args.joinToString(",")}>"
        if (!validatedSpecializations.add(key)) return
        val outcome = ConstraintEvaluator.evaluate(clause, bindings, table)
        if (outcome is ConstraintEvaluator.Outcome.Violated) {
            errors.add("line 0: '$key' does not satisfy its 'where' clause: ${outcome.reason}")
        }
    }
}
