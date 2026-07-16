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

import org.azora.lang.putIfAbsentCompat
import org.azora.lang.frontend.Annotation
import org.azora.lang.frontend.DecoTarget
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.MemberCallStyle
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef

/** Resolves decorator targets and transitive decorator/spec binding graphs. */
class DecoratorResolver {
    private data class Site(
        val identity: String,
        val target: DecoTarget,
        val type: TypeRef,
        val annotations: List<Annotation>,
    )

    private data class GraphValidation(
        val errors: List<String>,
        val invalidDecorators: Set<String>,
    )

    fun resolve(program: Program, table: SymbolTable): List<String> {
        val graph = validateGraphs(program, table)
        val errors = graph.errors.toMutableList()
        val sites = collectSites(program, table)
        val sitesByIdentity = sites.associateBy { it.identity }
        val decoratorDeclarations = program.items.filterIsInstance<TopLevel.Deco>().associateBy { it.name }

        for (impl in program.items.filterIsInstance<TopLevel.Impl>()) {
            val name = impl.traitName ?: continue
            val contract = table.lookupSpec(name) ?: continue
            if (!contract.isDecorator) continue
            if (impl.methods.isNotEmpty()) {
                errors.add("line ${impl.line}: decorator '$name' is a marker contract; use 'impl $name for ${impl.typeName}' without a body")
                continue
            }
            if (impl.traitArgs.isNotEmpty()) {
                errors.add("line ${impl.line}: decorator '$name' does not accept implementation type arguments")
                continue
            }
            val declaration = decoratorDeclarations[name] ?: continue
            val application = Annotation(
                name = name,
                args = impl.decoratorArgs,
                line = impl.line,
                column = impl.column,
                namedArgs = impl.decoratorNamedArgs,
            )
            if (!validateApplication(application, declaration, errors)) continue
            val targetSites = if (impl.typeName.endsWith(".*")) {
                val owner = impl.typeName.removeSuffix(".*")
                val ownerSite = sitesByIdentity[owner]
                if (ownerSite?.target != DecoTarget.Pack) {
                    errors.add("line ${impl.line}: decorator wildcard '$owner::*' requires a declared pack")
                    continue
                }
                sites.filter { it.target == DecoTarget.Field && it.identity.startsWith("$owner.") }
            } else {
                listOfNotNull(sitesByIdentity[impl.typeName])
            }
            if (targetSites.isEmpty() && !impl.typeName.endsWith(".*")) {
                errors.add("line ${impl.line}: cannot apply decorator '$name' to unknown target '${displayIdentity(impl.typeName)}'")
                continue
            }
            targetSites.forEach { site ->
                applyDecorator(site, name, impl.line, table, graph.invalidDecorators, errors)
            }
        }

        for (site in sites) {
            for (annotation in site.annotations) {
                val contract = table.lookupSpec(annotation.name) ?: continue
                if (!contract.isDecorator) continue
                val declaration = decoratorDeclarations[annotation.name] ?: continue
                if (!validateApplication(annotation, declaration, errors)) continue
                applyDecorator(site, annotation.name, annotation.line, table, graph.invalidDecorators, errors)
            }
        }
        return errors
    }

    private fun validateApplication(
        annotation: Annotation,
        declaration: TopLevel.Deco,
        errors: MutableList<String>,
    ): Boolean {
        var valid = true
        val fields = declaration.fields
        if (annotation.args.size > fields.size) {
            errors.add(
                "line ${annotation.line}: decorator '@${annotation.name}' expects at most ${fields.size} argument(s), " +
                    "got ${annotation.args.size}"
            )
            valid = false
        }

        val assigned = mutableSetOf<String>()
        annotation.args.take(fields.size).forEachIndexed { index, value ->
            val field = fields[index]
            assigned.add(field.name)
            if (!literalMatches(field.type, value)) {
                errors.add(
                    "line ${annotation.line}: decorator field '${field.name}' expects ${field.type.displayName()}"
                )
                valid = false
            }
        }

        for ((name, value) in annotation.namedArgs) {
            val field = fields.firstOrNull { it.name == name }
            if (field == null) {
                errors.add("line ${annotation.line}: decorator '@${annotation.name}' has no field '$name'")
                valid = false
                continue
            }
            if (!assigned.add(name)) {
                errors.add("line ${annotation.line}: decorator field '$name' is assigned more than once")
                valid = false
            }
            if (!literalMatches(field.type, value)) {
                errors.add(
                    "line ${annotation.line}: decorator field '$name' expects ${field.type.displayName()}"
                )
                valid = false
            }
        }

        for (field in fields) {
            if (field.default == null && field.name !in assigned) {
                errors.add("line ${annotation.line}: decorator '@${annotation.name}' requires field '${field.name}'")
                valid = false
            }
        }
        return valid
    }

    /** Reject obvious literal mistakes while leaving general CTFE expressions to type resolution. */
    private fun literalMatches(expected: TypeRef, value: Expr): Boolean {
        val name = (expected as? TypeRef.Named)?.name ?: return true
        return when (value) {
            is Expr.BoolLiteral -> name == "Bool"
            is Expr.StringLiteral, is Expr.StringTemplate -> name == "String"
            is Expr.CharLiteral -> name == "Char"
            is Expr.IntLiteral -> name in integerTypes || name in realTypes
            is Expr.RealLiteral -> name in realTypes
            is Expr.Unary -> literalMatches(expected, value.operand)
            else -> true
        }
    }

    private fun applyDecorator(
        site: Site,
        rootName: String,
        line: Int,
        table: SymbolTable,
        invalidDecorators: Set<String>,
        errors: MutableList<String>,
    ) {
        val root = table.lookupSpec(rootName) ?: return
        if (!root.isDecorator || rootName in invalidDecorators) return
        if (!allows(root.decoratorTargets, site.target)) {
            errors.add("line $line: decorator '@$rootName' cannot target .${site.target.name}")
            return
        }

        val visitedDecorators = mutableSetOf<String>()
        fun expand(name: String) {
            val decorator = table.lookupSpec(name) ?: return
            if (!visitedDecorators.add(name)) return
            if (!table.defineConformance(TraitConformance(site.identity, name, isDecorator = true))) {
                errors.add("line $line: duplicate decorator '$name' on '${site.identity}'")
                return
            }
            for (binding in decorator.decoratorBindings) {
                if (!allows(binding.targets, site.target)) continue
                val bound = table.lookupSpec(binding.name) ?: continue
                if (bound.isDecorator) {
                    expand(binding.name)
                } else {
                    val args = if (bound.typeParams.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(site.type) + binding.trailingTypeArgs
                    }
                    if (!table.defineConformance(
                            TraitConformance(site.identity, binding.name, args, isDecorator = true)
                        )
                    ) {
                        errors.add("line $line: duplicate derived implementation of '${binding.name}' for '${site.identity}'")
                    }
                }
            }
        }
        expand(rootName)
    }

    private fun validateGraphs(program: Program, table: SymbolTable): GraphValidation {
        val errors = linkedSetOf<String>()
        val invalid = mutableSetOf<String>()
        val declarations = program.items.filterIsInstance<TopLevel.Deco>()

        for (declaration in declarations) {
            for (binding in declaration.bindings) {
                if (declaration.targets.isNotEmpty() && binding.targets.isNotEmpty() &&
                    declaration.targets.intersect(binding.targets).isEmpty()
                ) {
                    errors.add("line ${declaration.line}: binding '${binding.name}' in decorator '${declaration.name}' can never match its declared targets")
                    invalid.add(declaration.name)
                }
            }

            val targets = if (declaration.targets.isEmpty()) DecoTarget.entries else declaration.targets
            for (target in targets) {
                val before = errors.size
                validateGraphForTarget(declaration.name, target, declaration.line, table, errors)
                if (errors.size != before) invalid.add(declaration.name)
            }
        }
        return GraphValidation(errors.toList(), invalid)
    }

    private fun validateGraphForTarget(
        root: String,
        target: DecoTarget,
        line: Int,
        table: SymbolTable,
        errors: MutableSet<String>,
    ) {
        val path = mutableListOf<String>()
        val leafOrigins = mutableMapOf<String, String>()
        val decoratorOrigins = mutableMapOf<String, String>()

        fun walk(name: String) {
            val decorator = table.lookupSpec(name)
            if (decorator == null || !decorator.isDecorator) return
            val cycleAt = path.indexOf(name)
            if (cycleAt >= 0) {
                val cycle = (path.drop(cycleAt) + name).joinToString(" -> ")
                errors.add("line $line: recursive decorator binding: $cycle")
                return
            }
            if (!allows(decorator.decoratorTargets, target)) {
                errors.add("line $line: decorator '$name' in '$root' cannot target .${target.name}")
                return
            }

            val decoratorOrigin = (path + name).joinToString(" -> ")
            decoratorOrigins.putIfAbsentCompat(name, decoratorOrigin)?.let { first ->
                errors.add("line $line: duplicate decorator binding '$name' via '$first' and '$decoratorOrigin'")
                return
            }

            path.add(name)
            for (binding in decorator.decoratorBindings) {
                if (!allows(binding.targets, target)) continue
                val bound = table.lookupSpec(binding.name)
                if (bound == null) {
                    errors.add("line $line: decorator '$name' binds unknown spec or decorator '${binding.name}'")
                    continue
                }
                if (bound.isDecorator) {
                    if (binding.trailingTypeArgs.isNotEmpty()) {
                        errors.add("line $line: decorator binding '${binding.name}' cannot have generic arguments")
                    } else {
                        walk(binding.name)
                    }
                } else {
                    val expectedTrailing = (bound.typeParams.size - 1).coerceAtLeast(0)
                    if (binding.trailingTypeArgs.size != expectedTrailing) {
                        errors.add(
                            "line $line: binding '${binding.name}' expects $expectedTrailing trailing type argument(s), " +
                                "got ${binding.trailingTypeArgs.size}"
                        )
                        continue
                    }
                    val key = "${binding.name}<${binding.trailingTypeArgs.joinToString(",")}>"
                    val origin = (path + binding.name).joinToString(" -> ")
                    leafOrigins.putIfAbsentCompat(key, origin)?.let { first ->
                        errors.add("line $line: duplicate decorator binding '$key' via '$first' and '$origin'")
                    }
                }
            }
            path.removeAt(path.lastIndex)
        }
        walk(root)
    }

    private fun collectSites(program: Program, table: SymbolTable): List<Site> {
        val sites = mutableListOf<Site>()
        fun namedType(name: String): TypeRef {
            val args = table.lookupStruct(name)?.typeParams.orEmpty().map { TypeRef.Named(it) }
            return TypeRef.Named(name, args)
        }
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
            val returnType = (function.returnType as? TypeAnnotation.Explicit)?.ref ?: TypeRef.Named("Any")
            val type = if (target == DecoTarget.Prop) returnType
                else TypeRef.Function(function.params.map { it.type }, returnType)
            sites.add(Site(identity, target, type, function.annotations))
            function.params.forEach { param ->
                sites.add(Site("$identity.${param.name}", DecoTarget.Param, param.type, param.annotations))
            }
        }

        for (item in program.items) {
            when (item) {
                is TopLevel.Pack -> {
                    sites.add(Site(item.name, DecoTarget.Pack, namedType(item.name), item.annotations))
                    item.fields.forEach { field ->
                        sites.add(Site("${item.name}.${field.name}", DecoTarget.Field, field.type, field.annotations))
                    }
                }
                is TopLevel.Node -> {
                    sites.add(Site(item.name, DecoTarget.Node, namedType(item.name), item.annotations))
                    item.extraFields.forEach { field ->
                        sites.add(Site("${item.name}.${field.name}", DecoTarget.Field, field.type, field.annotations))
                    }
                    item.methods.forEach { addFunction(item.name, it) }
                }
                is TopLevel.Enum -> {
                    sites.add(Site(item.name, DecoTarget.Enum, TypeRef.Named(item.name), item.annotations))
                    item.variants.forEachIndexed { index, variant ->
                        sites.add(Site("${item.name}.$variant", DecoTarget.EnumValue, TypeRef.Named(item.name), item.variantAnnotations[index]))
                    }
                }
                is TopLevel.Fail -> {
                    sites.add(Site(item.name, DecoTarget.Fail, TypeRef.Named(item.name), item.annotations))
                    item.variants.forEachIndexed { index, variant ->
                        sites.add(Site("${item.name}.$variant", DecoTarget.FailValue, TypeRef.Named(item.name), item.variantAnnotations[index]))
                    }
                }
                is TopLevel.Deco -> sites.add(Site(item.name, DecoTarget.Deco, TypeRef.Named(item.name), item.annotations))
                is TopLevel.Func -> addFunction(null, item.decl)
                is TopLevel.Impl -> item.methods.forEach { addFunction(item.typeName, it) }
                is TopLevel.Solo -> {
                    sites.add(Site(item.name, DecoTarget.Solo, namedType(item.name), item.annotations))
                    item.fields.forEach { field ->
                        sites.add(Site("${item.name}.${field.name}", DecoTarget.Field, field.type, field.annotations))
                    }
                    item.methods.forEach { addFunction(item.name, it) }
                }
                is TopLevel.Slot -> sites.add(Site(item.name, DecoTarget.Slot, TypeRef.Named(item.name), item.annotations))
                is TopLevel.TypeAlias -> sites.add(Site(item.name, DecoTarget.TypeAlias, item.type, item.annotations))
                is TopLevel.Test -> sites.add(Site(item.name, DecoTarget.Test, TypeRef.Named("Unit"), item.annotations))
                is TopLevel.View -> {
                    val type = TypeRef.Function(item.params.map { it.type }, TypeRef.Named("Any"))
                    sites.add(Site(item.name, DecoTarget.View, type, item.annotations))
                    item.params.forEach { param ->
                        sites.add(Site("${item.name}.${param.name}", DecoTarget.Param, param.type, param.annotations))
                    }
                }
                is TopLevel.Hook -> sites.add(Site(item.name, DecoTarget.Hook, TypeRef.Named("Unit"), item.annotations))
                is TopLevel.Bridge -> sites.add(Site(item.target, DecoTarget.Bridge, TypeRef.Named("Any"), item.annotations))
                is TopLevel.VarDecl -> sites.add(Site(item.name, DecoTarget.Var, item.type ?: TypeRef.Named("Any"), item.annotations))
                is TopLevel.FinDecl -> sites.add(Site(item.name, DecoTarget.Fin, item.type ?: TypeRef.Named("Any"), item.annotations))
                is TopLevel.LetDecl -> sites.add(Site(item.name, DecoTarget.Let, item.type ?: TypeRef.Named("Any"), item.annotations))
                else -> {}
            }
        }
        return sites.distinctBy { it.identity to it.target }
    }

    private fun allows(targets: Set<DecoTarget>, target: DecoTarget): Boolean =
        targets.isEmpty() || target in targets

    private fun displayIdentity(identity: String): String = identity.replace(".", "::")

    private companion object {
        val integerTypes = setOf(
            "Byte", "UByte", "Short", "UShort", "Cent", "UCent", "Int", "UInt", "Long", "ULong"
        )
        val realTypes = setOf("Float", "Real", "Decimal")
    }
}
