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

package org.azora.lang.ir

/**
 * Converts frontend-qualified symbol identities to the canonical Azora IR ABI:
 * one `__` prefix followed by namespace and symbol segments separated by `_`.
 *
 * The frontend deliberately uses `__` between source realm segments. Keeping
 * that representation out of IR prevents generated symbols from accumulating
 * separators during specialization (`__std__tupleOf`), and gives every backend
 * one stable spelling to consume.
 */
internal object IrSymbolCanonicalizer {
    fun canonicalize(program: IrProgram, typeNamespaces: Map<String, String>): IrProgram {
        val canonicalizer = Canonicalizer(program, typeNamespaces)
        return canonicalizer.program(program)
    }

    private class Canonicalizer(
        program: IrProgram,
        typeNamespaces: Map<String, String>,
    ) {
        private val typeNames = linkedMapOf<String, String>()
        private val symbolNames = linkedMapOf<String, String>()

        init {
            for (item in program.items) {
                when (item) {
                    is IrTopLevel.Struct -> {
                        val namespace = item.namespace ?: typeNamespaces[item.name]
                        typeNames[item.name] = canonicalTypeName(item.name, namespace)
                    }
                    is IrTopLevel.Enum -> {
                        typeNames[item.name] = canonicalTypeName(item.name, typeNamespaces[item.name])
                    }
                    else -> Unit
                }
            }
            for ((name, namespace) in typeNamespaces) {
                if (name !in typeNames) {
                    typeNames[name] = canonicalTypeName(name, namespace)
                }
            }

            for (item in program.items) {
                declarationName(item)?.let { symbolNames[it] = canonicalDeclarationName(it) }
            }
        }

        fun program(program: IrProgram): IrProgram = program.copy(
            items = program.items.map(::topLevel),
            specTables = program.specTables.map(::specTable),
        )

        private fun topLevel(item: IrTopLevel): IrTopLevel = when (item) {
            is IrTopLevel.Global -> item.copy(stmt = stmt(item.stmt))
            is IrTopLevel.Func -> item.copy(function = function(item.function))
            is IrTopLevel.Enum -> item.copy(name = symbol(item.name))
            is IrTopLevel.Test -> item.copy(body = item.body.map(::stmt))
            is IrTopLevel.Struct -> item.copy(
                name = symbol(item.name),
                fields = item.fields.map { it.copy(type = type(it.type)) },
            )
            is IrTopLevel.Extern -> item.copy(
                name = symbol(item.name),
                params = item.params.map { (name, type) -> name to type(type) },
                returnType = type(item.returnType),
            )
        }

        private fun function(function: IrFunction): IrFunction = function.copy(
            name = symbol(function.name),
            params = function.params.map { (name, type) -> name to type(type) },
            returnType = type(function.returnType),
            body = function.body.map(::stmt),
        )

        private fun specTable(table: IrSpecTable): IrSpecTable = table.copy(
            specName = symbol(table.specName),
            methods = table.methods.map { method ->
                method.copy(
                    paramTypes = method.paramTypes.map(::type),
                    returnType = type(method.returnType),
                )
            },
            impls = table.impls.map { impl ->
                impl.copy(
                    typeName = symbol(impl.typeName),
                    methodFuncs = impl.methodFuncs.mapValues { (_, function) -> symbol(function) },
                )
            },
        )

        private fun stmt(stmt: IrStmt): IrStmt = when (stmt) {
            is IrStmt.VarDecl -> stmt.copy(name = symbol(stmt.name), type = type(stmt.type), initializer = expr(stmt.initializer))
            is IrStmt.FinDecl -> stmt.copy(name = symbol(stmt.name), type = type(stmt.type), initializer = expr(stmt.initializer))
            is IrStmt.LetDecl -> stmt.copy(name = symbol(stmt.name), type = type(stmt.type), initializer = expr(stmt.initializer))
            is IrStmt.Assignment -> stmt.copy(name = symbol(stmt.name), value = expr(stmt.value))
            is IrStmt.IndexAssign -> stmt.copy(target = expr(stmt.target), index = expr(stmt.index), value = expr(stmt.value))
            is IrStmt.MemberAssign -> stmt.copy(target = expr(stmt.target), value = expr(stmt.value))
            is IrStmt.Return -> stmt.copy(value = stmt.value?.let(::expr))
            is IrStmt.ExprStmt -> stmt.copy(expr = expr(stmt.expr))
            is IrStmt.If -> stmt.copy(
                condition = expr(stmt.condition),
                thenBranch = stmt.thenBranch.map(::stmt),
                elseBranch = stmt.elseBranch?.map(::stmt),
            )
            is IrStmt.Scope -> stmt.copy(body = stmt.body.map(::stmt))
            is IrStmt.Assert -> stmt.copy(condition = expr(stmt.condition), message = expr(stmt.message))
            is IrStmt.Trace -> stmt.copy(
                level = expr(stmt.level),
                message = expr(stmt.message),
                displayLevel = expr(stmt.displayLevel),
            )
            is IrStmt.While -> stmt.copy(condition = expr(stmt.condition), body = stmt.body.map(::stmt))
            is IrStmt.For -> stmt.copy(
                counter = symbol(stmt.counter),
                start = expr(stmt.start),
                end = expr(stmt.end),
                body = stmt.body.map(::stmt),
                step = stmt.step?.let(::expr),
            )
            is IrStmt.Loop -> stmt.copy(body = stmt.body.map(::stmt))
            is IrStmt.Break -> stmt
            is IrStmt.Continue -> stmt
            is IrStmt.When -> stmt.copy(
                scrutinee = expr(stmt.scrutinee),
                branches = stmt.branches.map { branch ->
                    branch.copy(patterns = branch.patterns.map(::expr), body = branch.body.map(::stmt))
                },
                elseBranch = stmt.elseBranch?.map(::stmt),
            )
            is IrStmt.Throw -> stmt.copy(value = expr(stmt.value))
            is IrStmt.Try -> stmt.copy(body = stmt.body.map(::stmt), catchBody = stmt.catchBody?.map(::stmt))
            is IrStmt.Defer -> stmt.copy(body = stmt.body.map(::stmt))
            is IrStmt.Yield -> stmt.copy(value = expr(stmt.value))
            is IrStmt.ForEach -> stmt.copy(
                elem = symbol(stmt.elem),
                iterable = expr(stmt.iterable),
                body = stmt.body.map(::stmt),
            )
        }

        private fun expr(expr: IrExpr): IrExpr = when (expr) {
            is IrExpr.IntLiteral -> expr.copy(type = type(expr.type))
            is IrExpr.DoubleLiteral -> expr.copy(type = type(expr.type))
            is IrExpr.StringLiteral -> expr
            is IrExpr.EnumLiteral -> expr.copy(enumName = symbol(expr.enumName))
            is IrExpr.BoolLiteral -> expr
            is IrExpr.CharLiteral -> expr
            is IrExpr.Var -> expr.copy(name = symbol(expr.name), type = type(expr.type))
            is IrExpr.Binary -> expr.copy(
                left = expr(expr.left),
                right = expr(expr.right),
                type = type(expr.type),
            )
            is IrExpr.Unary -> expr.copy(operand = expr(expr.operand), type = type(expr.type))
            is IrExpr.Call -> {
                val callType = type(expr.type)
                val args = expr.args.map(::expr).toMutableList()
                if (expr.name == "__inject" && callType is IrType.Named && args.firstOrNull() is IrExpr.StringLiteral) {
                    args[0] = IrExpr.StringLiteral(callType.name)
                }
                expr.copy(
                    name = symbol(expr.name),
                    args = args,
                    type = callType,
                    receiver = expr.receiver?.let(::expr),
                )
            }
            is IrExpr.ArrayLiteral -> expr.copy(elements = expr.elements.map(::expr), type = type(expr.type))
            is IrExpr.MapLit -> expr.copy(
                entries = expr.entries.map { (key, value) -> expr(key) to expr(value) },
                type = type(expr.type),
            )
            is IrExpr.SetLit -> expr.copy(elements = expr.elements.map(::expr), type = type(expr.type))
            is IrExpr.Index -> expr.copy(target = expr(expr.target), index = expr(expr.index), type = type(expr.type))
            is IrExpr.Member -> expr.copy(target = expr(expr.target), type = type(expr.type))
            is IrExpr.MethodCall -> expr.copy(
                target = expr(expr.target),
                name = symbol(expr.name),
                args = expr.args.map(::expr),
                type = type(expr.type),
            )
            is IrExpr.StructCtor -> expr.copy(
                name = symbol(expr.name),
                args = expr.args.map(::expr),
                type = type(expr.type),
            )
            is IrExpr.StringTemplate -> expr.copy(parts = expr.parts.map { part ->
                if (part is IrExpr.IrTemplatePart.Expr) part.copy(expr = expr(part.expr)) else part
            })
            is IrExpr.TupleLit -> expr.copy(elements = expr.elements.map(::expr), type = type(expr.type))
            is IrExpr.VariantLit -> expr.copy(elements = expr.elements.map(::expr), type = type(expr.type))
            is IrExpr.TupleAccess -> expr.copy(target = expr(expr.target), type = type(expr.type))
            is IrExpr.CatchExpr -> expr.copy(
                expr = expr(expr.expr),
                fallback = expr(expr.fallback),
                type = type(expr.type),
            )
            is IrExpr.IfExpr -> expr.copy(
                condition = expr(expr.condition),
                thenExpr = expr(expr.thenExpr),
                elseExpr = expr(expr.elseExpr),
                type = type(expr.type),
            )
            is IrExpr.NumCast -> expr.copy(value = expr(expr.value), type = type(expr.type))
            is IrExpr.EnumToString -> expr.copy(value = expr(expr.value))
            is IrExpr.Lambda -> expr.copy(
                params = expr.params.map { (name, type) -> name to type(type) },
                body = expr.body.map(::stmt),
                type = type(expr.type),
            )
            is IrExpr.SlotPattern -> expr.copy(
                slotName = symbol(expr.slotName),
                bindingTypes = expr.bindingTypes.map(::type),
            )
            is IrExpr.Await -> expr.copy(value = expr(expr.value), type = type(expr.type))
            is IrExpr.Spread -> expr.copy(array = expr(expr.array))
        }

        private fun type(type: IrType): IrType = when (type) {
            is IrType.Array -> type.copy(element = type(type.element))
            is IrType.Map -> type.copy(key = type(type.key), value = type(type.value))
            is IrType.Set -> type.copy(element = type(type.element))
            is IrType.Function -> type.copy(
                params = type.params.map(::type),
                ret = type(type.ret),
                receivers = type.receivers.map(::type),
            )
            is IrType.Task -> type.copy(result = type(type.result))
            is IrType.Tuple -> type.copy(elements = type.elements.map(::type))
            is IrType.Variant -> type.copy(elements = type.elements.map(::type))
            is IrType.Nullable -> type.copy(inner = type(type.inner))
            is IrType.Pointer -> type.copy(inner = type(type.inner))
            is IrType.Named -> type.copy(name = symbol(type.name))
            else -> type
        }

        private fun symbol(name: String): String {
            if (name.isEmpty()) return name
            symbolNames[name]?.let { return it }
            typeNames[name]?.let { return it }
            canonicalTypeDerivedName(name)?.let { return it }
            return canonicalQualifiedName(name)
        }

        private fun canonicalDeclarationName(name: String): String =
            typeNames[name] ?: canonicalTypeDerivedName(name) ?: canonicalQualifiedName(name)

        private fun canonicalTypeDerivedName(name: String): String? {
            for ((oldType, canonicalType) in typeNames.entries.sortedByDescending { it.key.length }) {
                val singletonPrefix = "__singleton_$oldType"
                if (name.startsWith(singletonPrefix)) {
                    val suffix = collapseSeparators(name.removePrefix(singletonPrefix))
                    return "__singleton_${canonicalType.removePrefix("__")}$suffix"
                }
                if (name.startsWith("${oldType}_")) {
                    return canonicalType + collapseSeparators(name.removePrefix(oldType))
                }
            }
            return null
        }

        private fun canonicalTypeName(name: String, namespace: String?): String {
            if (namespace == null) return canonicalQualifiedName(name)
            val qualifier = canonicalNamespace(namespace)
            val base = collapseSeparators(name.removePrefix("__"))
            return if (base == qualifier || base.startsWith("${qualifier}_")) {
                "__$base"
            } else {
                "__${qualifier}_$base"
            }
        }

        private fun canonicalQualifiedName(name: String): String {
            if (name.startsWith("__")) {
                return "__" + collapseSeparators(name.removePrefix("__"))
            }
            if ("__" in name) {
                return "__" + collapseSeparators(name)
            }
            return name
        }

        private fun declarationName(item: IrTopLevel): String? = when (item) {
            is IrTopLevel.Global -> when (val declaration = item.stmt) {
                is IrStmt.VarDecl -> declaration.name
                is IrStmt.FinDecl -> declaration.name
                is IrStmt.LetDecl -> declaration.name
                else -> null
            }
            is IrTopLevel.Func -> item.function.name
            is IrTopLevel.Enum -> item.name
            is IrTopLevel.Struct -> item.name
            is IrTopLevel.Extern -> item.name
            is IrTopLevel.Test -> null
        }

        private fun canonicalNamespace(namespace: String): String =
            collapseSeparators(namespace.replace("::", "_").replace(".", "_"))

        private fun collapseSeparators(name: String): String {
            var result = name
            while ("__" in result) result = result.replace("__", "_")
            return result
        }
    }
}
