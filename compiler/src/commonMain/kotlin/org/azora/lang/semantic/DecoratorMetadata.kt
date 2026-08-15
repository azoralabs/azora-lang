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

import org.azora.lang.frontend.Annotation
import org.azora.lang.frontend.DecoTarget
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.MemberCallStyle
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TopLevel

/** Static decorator metadata used by compile-time reflection intrinsics. */
object DecoratorMetadata {
    data class Site(
        val identity: String,
        val target: DecoTarget,
        val annotations: List<Annotation>,
    )

    data class Applied(
        val declaration: TopLevel.Deco,
        /** Null when the decorator is inherited through another decorator. */
        val directApplication: Annotation?,
    )

    fun findSite(receiver: Expr, typeBindings: Map<String, String>, program: Program): Site? {
        val sites = collectSites(program).associateBy { it.identity }
        val rawIdentity = expressionIdentity(receiver)
        val identity = if (rawIdentity != null && rawIdentity.contains("__") && rawIdentity !in sites) {
            "${rawIdentity.substringBeforeLast("__")}.${rawIdentity.substringAfterLast("__")}"
        } else rawIdentity
        sites[identity]?.let { return it }
        val identifier = receiver as? Expr.Identifier
        identifier?.let { typeBindings[it.name] }?.let { return sites[it] }
        val constructor = receiver as? Expr.Call
        constructor?.let { sites[it.callee] }?.let { return it }
        return null
    }

    fun findApplied(site: Site, decoratorName: String, program: Program): Applied? {
        val decorators = program.items.filterIsInstance<TopLevel.Deco>().associateBy { it.name }
        val requested = decorators[decoratorName] ?: return null
        if (!allows(requested.targets, site.target)) return null

        fun reaches(currentName: String, seen: MutableSet<String>): Boolean {
            if (currentName == decoratorName) return true
            if (!seen.add(currentName)) return false
            val current = decorators[currentName] ?: return false
            if (!allows(current.targets, site.target)) return false
            return current.bindings.any { binding ->
                allows(binding.targets, site.target) && binding.name in decorators && reaches(binding.name, seen)
            }
        }

        data class RootApplication(val name: String, val annotation: Annotation?)
        val applications = buildList {
            site.annotations.forEach { add(RootApplication(it.name, it)) }
            program.items.filterIsInstance<TopLevel.Impl>()
                .filter { implementationTargetsSite(it.typeName, site) && it.methods.isEmpty() }
                .filter { it.traitName in decorators }
                .forEach { impl ->
                    val name = impl.traitName ?: return@forEach
                    add(
                        RootApplication(
                            name,
                            Annotation(
                                name = name,
                                args = impl.decoratorArgs,
                                line = impl.line,
                                column = impl.column,
                                namedArgs = impl.decoratorNamedArgs,
                            ),
                        )
                    )
                }
        }

        for (application in applications) {
            if (!reaches(application.name, mutableSetOf())) continue
            return Applied(
                requested,
                application.annotation?.takeIf { application.name == decoratorName },
            )
        }
        return null
    }

    fun fieldValue(applied: Applied, fieldName: String): Expr? {
        val index = applied.declaration.fields.indexOfFirst { it.name == fieldName }
        if (index < 0) return null
        val application = applied.directApplication
        application?.namedArgs?.firstOrNull { it.first == fieldName }?.second?.let { return it }
        application?.args?.getOrNull(index)?.let { return it }
        return chosenDefault(applied, index)?.value
    }

    /**
     * Whether [fieldName] is sealed at this application.
     *
     * Deliberately answered from the *default*, whatever the application
     * passed: the question is whether it was entitled to pass anything, and a
     * value already supplied is what makes it worth asking.
     */
    fun isFieldSealed(applied: Applied, fieldName: String): Boolean {
        val index = applied.declaration.fields.indexOfFirst { it.name == fieldName }
        if (index < 0) return false
        return chosenDefault(applied, index)?.isSealed == true
    }

    /** A default once its branch has been picked: the value, and whether that branch sealed it. */
    private data class Chosen(val value: Expr, val isSealed: Boolean)

    /**
     * The default of field [index], with the fields before it bound to what
     * this application chose.
     *
     * A default may be written as a `when` over the earlier fields, and until
     * an application fixes those there is no branch to take - which is why this
     * is answered here rather than at the declaration.
     */
    private fun chosenDefault(applied: Applied, index: Int): Chosen? {
        val default = applied.declaration.fields[index].default ?: return null
        val bindings = mutableMapOf<String, Expr>()
        for (earlier in 0 until index) {
            val name = applied.declaration.fields[earlier].name
            fieldValue(applied, name)?.let { bindings[name] = it }
        }
        return choose(default, bindings)
    }

    /** Takes the branch [bindings] select, carrying the seal off the branch that won. */
    private fun choose(expr: Expr, bindings: Map<String, Expr>): Chosen = when (expr) {
        is Expr.Sealed -> Chosen(choose(expr.value, bindings).value, true)
        is Expr.Grouping -> choose(expr.expr, bindings)
        is Expr.IfExpr -> when (decide(expr.condition, bindings)) {
            true -> choose(expr.thenExpr, bindings)
            false -> choose(expr.elseExpr, bindings)
            // Undecidable - hand back what was written. No branch was taken, so
            // no branch's seal applies.
            null -> Chosen(expr, isSealed = false)
        }
        else -> Chosen(expr, isSealed = false)
    }

    /**
     * Whether [condition] holds, or null when it cannot be decided here.
     *
     * Only the shapes a `when` over decorator fields produces are decided:
     * equalities against constants, and the `||` chain a multi-pattern branch
     * becomes. Anything else is left undecided rather than guessed at.
     */
    private fun decide(condition: Expr, bindings: Map<String, Expr>): Boolean? = when (condition) {
        is Expr.Grouping -> decide(condition.expr, bindings)
        is Expr.BoolLiteral -> condition.value
        is Expr.Binary -> when (condition.op) {
            TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL -> {
                val left = constantKey(condition.left, bindings)
                val right = constantKey(condition.right, bindings)
                if (left == null || right == null) null
                else (left == right) == (condition.op == TokenType.EQUAL_EQUAL)
            }
            TokenType.OR_OR -> {
                val left = decide(condition.left, bindings)
                val right = decide(condition.right, bindings)
                if (left == true || right == true) true
                else if (left == false && right == false) false
                else null
            }
            TokenType.AND_AND -> {
                val left = decide(condition.left, bindings)
                val right = decide(condition.right, bindings)
                if (left == false || right == false) false
                else if (left == true && right == true) true
                else null
            }
            else -> null
        }
        else -> null
    }

    /**
     * A constant's identity, for comparing two of them.
     *
     * `.Error` and `LogLevel.Error` name the same variant and answer the same,
     * which is what lets a pattern be written either way: both sides of the
     * comparison are the field's declared type, so the variant name is the
     * whole of the identity.
     */
    private fun constantKey(expr: Expr, bindings: Map<String, Expr>): String? = when (expr) {
        is Expr.Grouping -> constantKey(expr.expr, bindings)
        // A decorator argument written positionally keeps its leading dot in
        // the name - `@Log(.Error)` - where a named one has already become an
        // InferredMember. Both name a variant.
        is Expr.Identifier ->
            if (expr.name.startsWith(".")) "variant:${expr.name.removePrefix(".")}"
            else bindings[expr.name]?.let { constantKey(it, bindings) }
        is Expr.InferredMember -> if (expr.ctorArgs == null) "variant:${expr.name}" else null
        is Expr.Member -> "variant:${expr.name}"
        is Expr.IntLiteral -> "int:${expr.value}"
        is Expr.StringLiteral -> "str:${expr.value}"
        is Expr.BoolLiteral -> "bool:${expr.value}"
        is Expr.CharLiteral -> "char:${expr.value}"
        else -> null
    }

    private fun collectSites(program: Program): List<Site> {
        val sites = mutableListOf<Site>()
        fun addFunction(owner: String?, function: FuncDecl) {
            val identity = if (owner == null) function.name else "$owner.${function.name}"
            val target = when {
                function.isUniversalInfix -> DecoTarget.Func
                function.memberCallStyle == MemberCallStyle.PROPERTY -> DecoTarget.Prop
                function.name == "ctor" -> DecoTarget.Ctor
                function.name == "dtor" -> DecoTarget.Dtor
                function.isTask -> DecoTarget.AsyncFunc
                function.isFlow -> DecoTarget.AsyncFunc
                else -> DecoTarget.Func
            }
            sites.add(Site(identity, target, function.annotations))
            function.params.forEach { sites.add(Site("$identity.${it.name}", DecoTarget.Param, it.annotations)) }
        }

        for (item in program.items) {
            when (item) {
                is TopLevel.Pack -> {
                    sites.add(Site(item.name, DecoTarget.Pack, item.annotations))
                    item.fields.forEach { sites.add(Site("${item.name}.${it.name}", DecoTarget.Field, it.annotations)) }
                }
                is TopLevel.Enum -> {
                    sites.add(Site(item.name, DecoTarget.Enum, item.annotations))
                    item.variants.forEachIndexed { i, name -> sites.add(Site("${item.name}.$name", DecoTarget.EnumValue, item.variantAnnotations[i])) }
                }
                is TopLevel.Fail -> {
                    sites.add(Site(item.name, DecoTarget.Error, item.annotations))
                    item.variants.forEachIndexed { i, name -> sites.add(Site("${item.name}.$name", DecoTarget.ErrorValue, item.variantAnnotations[i])) }
                }
                is TopLevel.Deco -> sites.add(Site(item.name, DecoTarget.Annot, item.annotations))
                is TopLevel.Func -> addFunction(null, item.decl)
                is TopLevel.Impl -> {
                    val isOperBlock = item.methods.any {
                        it.name.startsWith("oper") || it.name in setOf("slice", "index", "indexSet")
                    }
                    if (isOperBlock) sites.add(Site(item.typeName, DecoTarget.Oper, item.annotations))
                    item.methods.forEach { addFunction(item.typeName, it) }
                }
                is TopLevel.Solo -> {
                    sites.add(Site(item.name, DecoTarget.Pack, item.annotations))
                    item.fields.forEach { sites.add(Site("${item.name}.${it.name}", DecoTarget.Field, it.annotations)) }
                    item.methods.forEach { addFunction(item.name, it) }
                }
                is TopLevel.Slot -> sites.add(Site(item.name, DecoTarget.VariantEnum, item.annotations))
                is TopLevel.TypeAlias -> sites.add(Site(item.name, DecoTarget.TypeAlias, item.annotations))
                is TopLevel.Test -> sites.add(Site(item.name, DecoTarget.Test, item.annotations))
                is TopLevel.Bridge -> sites.add(Site(item.target, DecoTarget.Bridge, item.annotations))
                is TopLevel.VarDecl -> sites.add(Site(item.name, DecoTarget.Var, item.annotations))
                is TopLevel.FinDecl -> sites.add(Site(item.name, DecoTarget.Fin, item.annotations))
                is TopLevel.LetDecl -> sites.add(Site(item.name, DecoTarget.Let, item.annotations))
                else -> {}
            }
        }
        return sites
    }

    private fun expressionIdentity(expr: Expr): String? = when (expr) {
        is Expr.Identifier -> expr.name
        is Expr.Member -> expressionIdentity(expr.target)?.let { "$it.${expr.name}" }
        else -> null
    }

    private fun allows(targets: Set<DecoTarget>, target: DecoTarget): Boolean =
        targets.isEmpty() || target in targets

    private fun implementationTargetsSite(target: String, site: Site): Boolean {
        if (target == site.identity) return true
        if (!target.endsWith(".*") || site.target != DecoTarget.Field) return false
        return site.identity.startsWith("${target.removeSuffix(".*")}.")
    }
}
