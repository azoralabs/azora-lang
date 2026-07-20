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
        val field = applied.declaration.fields[index]
        val application = applied.directApplication
        application?.namedArgs?.firstOrNull { it.first == fieldName }?.second?.let { return it }
        application?.args?.getOrNull(index)?.let { return it }
        return field.default
    }

    private fun collectSites(program: Program): List<Site> {
        val sites = mutableListOf<Site>()
        fun addFunction(owner: String?, function: FuncDecl) {
            val identity = if (owner == null) function.name else "$owner.${function.name}"
            val target = when {
                function.memberCallStyle == MemberCallStyle.PROPERTY -> DecoTarget.Prop
                function.name == "ctor" -> DecoTarget.Ctor
                function.name == "dtor" -> DecoTarget.Dtor
                function.isTask -> DecoTarget.Task
                function.isFlow -> DecoTarget.Flow
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
                is TopLevel.Node -> {
                    sites.add(Site(item.name, DecoTarget.Node, item.annotations))
                    item.extraFields.forEach { sites.add(Site("${item.name}.${it.name}", DecoTarget.Field, it.annotations)) }
                    item.methods.forEach { addFunction(item.name, it) }
                }
                is TopLevel.Enum -> {
                    sites.add(Site(item.name, DecoTarget.Enum, item.annotations))
                    item.variants.forEachIndexed { i, name -> sites.add(Site("${item.name}.$name", DecoTarget.EnumValue, item.variantAnnotations[i])) }
                }
                is TopLevel.Fail -> {
                    sites.add(Site(item.name, DecoTarget.Fail, item.annotations))
                    item.variants.forEachIndexed { i, name -> sites.add(Site("${item.name}.$name", DecoTarget.FailValue, item.variantAnnotations[i])) }
                }
                is TopLevel.Deco -> sites.add(Site(item.name, DecoTarget.Deco, item.annotations))
                is TopLevel.Func -> addFunction(null, item.decl)
                is TopLevel.Impl -> {
                    val isOperBlock = item.methods.any {
                        it.name.startsWith("oper") || it.name in setOf("slice", "index", "indexSet")
                    }
                    if (isOperBlock) sites.add(Site(item.typeName, DecoTarget.ImplOper, item.annotations))
                    item.methods.forEach { addFunction(item.typeName, it) }
                }
                is TopLevel.Solo -> {
                    sites.add(Site(item.name, DecoTarget.Solo, item.annotations))
                    item.fields.forEach { sites.add(Site("${item.name}.${it.name}", DecoTarget.Field, it.annotations)) }
                    item.methods.forEach { addFunction(item.name, it) }
                }
                is TopLevel.Slot -> sites.add(Site(item.name, DecoTarget.Slot, item.annotations))
                is TopLevel.TypeAlias -> sites.add(Site(item.name, DecoTarget.TypeAlias, item.annotations))
                is TopLevel.Test -> sites.add(Site(item.name, DecoTarget.Test, item.annotations))
                is TopLevel.View -> sites.add(Site(item.name, DecoTarget.View, item.annotations))
                is TopLevel.Hook -> sites.add(Site(item.name, DecoTarget.Hook, item.annotations))
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
