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
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.NumericSuffix
import org.azora.lang.frontend.PackField
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeRef

/**
 * Synthesizes the serializer methods promised by the standard serialization
 * decorator contracts. Generated methods are ordinary AST methods, so every
 * backend receives the same checked IR rather than backend-specific behavior.
 */
object SerializationDeriver {
    data class Result(val program: Program, val errors: List<String>)

    private data class FormatSet(val json: Boolean, val azon: Boolean)
    private data class Helpers(
        val provider: String,
        val conversionProvider: String,
        val providerModule: String,
        val conversionModule: String,
    )
    private data class Roles(
        val all: Set<String>,
        val json: Set<String>,
        val azon: Set<String>,
        val name: Set<String>,
        val ignore: Set<String>,
        val required: Set<String>,
        val provider: String,
        val conversionProvider: String,
        val providerModule: String,
        val conversionModule: String,
        val deferGeneric: Boolean,
    ) {
        val roots: Set<String> get() = all + json + azon
    }
    private data class FieldPlan(
        val field: PackField,
        val wireName: String,
        val ignored: Boolean,
        val required: Boolean,
        val defaultSource: String?,
    )

    private val integerTypes = setOf(
        "Byte", "UByte", "Short", "UShort", "Int", "UInt", "Long", "ULong", "Cent", "UCent"
    )
    private val realTypes = setOf("Float", "Real", "Decimal")
    fun derive(program: Program): Result {
        val roles = discoverRoles(program)
        if (roles.roots.isEmpty()) return Result(program, emptyList())

        val errors = mutableListOf<String>()
        val helpers = Helpers(
            roles.provider,
            roles.conversionProvider,
            roles.providerModule,
            roles.conversionModule,
        )
        val generated = mutableListOf<TopLevel>()
        val packs = program.items.filterIsInstance<TopLevel.Pack>()
        val serializablePacks = packs.filterTo(mutableSetOf()) { pack ->
            appliedAny(program, pack.name, roles.roots) != null
        }.mapTo(mutableSetOf()) { it.name }

        for (pack in packs) {
            val errorCountBeforePack = errors.size
            val serializable = appliedAny(program, pack.name, roles.all)
            val jsonOnly = appliedAny(program, pack.name, roles.json)
            val azonOnly = appliedAny(program, pack.name, roles.azon)
            val root = serializable ?: jsonOnly ?: azonOnly ?: continue
            if (pack.typeParams.isNotEmpty()) {
                if (!roles.deferGeneric) {
                    errors += "line ${pack.line}: generated serialization for generic pack '${pack.name}' requires concrete type arguments"
                }
                continue
            }

            val formats = FormatSet(json = serializable != null || jsonOnly != null, azon = serializable != null || azonOnly != null)
            val ignoreUnknown = boolMetadata(root, "ignoreUnknownFields", errors, pack.line) ?: false
            val encodeDefaults = boolMetadata(root, "encodeDefaults", errors, pack.line) ?: true
            val fields = pack.fields.map { field -> fieldPlan(program, pack, field, roles, errors) }

            val duplicateWireName = fields.filterNot { it.ignored }.groupBy { it.wireName }
                .entries.firstOrNull { it.value.size > 1 }
            if (duplicateWireName != null) {
                errors += "line ${pack.line}: serialized fields of '${pack.name}' share wire name '${duplicateWireName.key}'"
                continue
            }
            fields.filter { it.ignored && it.field.default == null }.forEach {
                errors += "line ${pack.line}: ignored field '${pack.name}::${it.field.name}' requires a default value"
            }
            fields.filter { it.ignored && it.required }.forEach {
                errors += "line ${pack.line}: field '${pack.name}::${it.field.name}' cannot be both SerialIgnore and SerialRequired"
            }
            fields.filterNot { it.ignored }.forEach {
                validateSupportedType(it.field.type, pack.name, it.field.name, serializablePacks, errors, pack.line)
            }
            if (errors.size != errorCountBeforePack) continue

            val generatedNames = buildSet {
                add("toSerialValue")
                add("fromSerialValue")
                if (formats.json) { add("toJson"); add("fromJson") }
                if (formats.azon) { add("toAzon"); add("fromAzon") }
            }
            val existing = program.items.filterIsInstance<TopLevel.Impl>()
                .filter { it.typeName == pack.name }
                .flatMap { it.methods }
                .mapTo(mutableSetOf()) { it.name }
            val collision = generatedNames.firstOrNull { it in existing }
            if (collision != null) {
                errors += "line ${pack.line}: generated serializer for '${pack.name}' conflicts with existing method '$collision'"
                continue
            }

            val source = generateImpl(pack, fields, ignoreUnknown, encodeDefaults, formats, helpers)
            try {
                generated += Parser(Lexer(source).tokenize()).parse().items
            } catch (e: IllegalStateException) {
                errors += "line ${pack.line}: failed to derive serializer for '${pack.name}': ${e.message}"
            }
        }

        if (errors.isNotEmpty()) return Result(program, errors.distinct())
        val dependencyImports = if (generated.isEmpty()) emptyList() else
            listOf(helpers.providerModule, helpers.conversionModule)
                .filter { it.isNotEmpty() }
                .distinct()
                .map { module -> TopLevel.UseImport(listOf(module to null), line = 0) }
        return Result(program.copy(items = program.items + dependencyImports + generated), emptyList())
    }

    private fun applied(program: Program, identity: String, decorator: String): DecoratorMetadata.Applied? {
        val site = DecoratorMetadata.findSite(Expr.Identifier(identity, 0), emptyMap(), program) ?: return null
        return DecoratorMetadata.findApplied(site, decorator, program)
    }

    private fun appliedAny(program: Program, identity: String, decorators: Set<String>): DecoratorMetadata.Applied? =
        decorators.firstNotNullOfOrNull { applied(program, identity, it) }

    private fun fieldPlan(
        program: Program,
        pack: TopLevel.Pack,
        field: PackField,
        roles: Roles,
        errors: MutableList<String>,
    ): FieldPlan {
        val identity = "${pack.name}.${field.name}"
        val ignored = appliedAny(program, identity, roles.ignore) != null
        val required = appliedAny(program, identity, roles.required) != null
        val serialName = appliedAny(program, identity, roles.name)
        val configuredName = serialName?.let { stringMetadata(it, "value", errors, pack.line) }.orEmpty()
        val defaultSource = field.default?.let {
            renderExpr(it) ?: run {
                errors += "line ${pack.line}: default value for '${pack.name}::${field.name}' cannot be used by generated serialization"
                null
            }
        }
        return FieldPlan(field, configuredName.ifEmpty { field.name }, ignored, required, defaultSource)
    }

    private fun discoverRoles(program: Program): Roles {
        val byRole = mutableMapOf<String, MutableSet<String>>()
        var provider = ""
        var conversionProvider = ""
        var providerModule = ""
        var conversionModule = ""
        var deferGeneric = false
        program.items.filterIsInstance<TopLevel.Deco>().forEach { declaration ->
            declaration.annotations.filter { it.name == "Derive" }.forEach { annotation ->
                fun stringArgument(name: String, position: Int): String? =
                    (annotation.namedArgs.firstOrNull { it.first == name }?.second as? Expr.StringLiteral)?.value
                        ?: (annotation.args.getOrNull(position) as? Expr.StringLiteral)?.value
                if (stringArgument("generator", 0) != "serializer") return@forEach
                val role = stringArgument("role", 1) ?: return@forEach
                byRole.getOrPut(role) { linkedSetOf() }.add(declaration.name)
                if (role in setOf("all", "json", "azon")) {
                    stringArgument("provider", 2)?.takeIf { it.isNotEmpty() }?.let { provider = it }
                    stringArgument("conversionProvider", 3)?.takeIf { it.isNotEmpty() }?.let { conversionProvider = it }
                    stringArgument("providerModule", 4)?.takeIf { it.isNotEmpty() }?.let { providerModule = it }
                    stringArgument("conversionModule", 5)?.takeIf { it.isNotEmpty() }?.let { conversionModule = it }
                    val genericArgument = annotation.namedArgs.firstOrNull { it.first == "deferGeneric" }?.second
                        ?: annotation.args.getOrNull(6)
                    (genericArgument as? Expr.BoolLiteral)?.let { deferGeneric = it.value }
                }
            }
        }
        return Roles(
            all = byRole["all"].orEmpty(),
            json = byRole["json"].orEmpty(),
            azon = byRole["azon"].orEmpty(),
            name = byRole["name"].orEmpty(),
            ignore = byRole["ignore"].orEmpty(),
            required = byRole["required"].orEmpty(),
            provider = provider,
            conversionProvider = conversionProvider,
            providerModule = providerModule,
            conversionModule = conversionModule,
            deferGeneric = deferGeneric,
        )
    }

    private fun boolMetadata(
        applied: DecoratorMetadata.Applied,
        field: String,
        errors: MutableList<String>,
        line: Int,
    ): Boolean? = when (val value = DecoratorMetadata.fieldValue(applied, field)) {
        is Expr.BoolLiteral -> value.value
        null -> null
        else -> {
            errors += "line $line: serializer option '$field' must be a compile-time Bool literal"
            null
        }
    }

    private fun stringMetadata(
        applied: DecoratorMetadata.Applied,
        field: String,
        errors: MutableList<String>,
        line: Int,
    ): String? = when (val value = DecoratorMetadata.fieldValue(applied, field)) {
        is Expr.StringLiteral -> value.value
        null -> null
        else -> {
            errors += "line $line: serializer option '$field' must be a compile-time String literal"
            null
        }
    }

    private fun validateSupportedType(
        type: TypeRef,
        owner: String,
        field: String,
        serializablePacks: Set<String>,
        errors: MutableList<String>,
        line: Int,
    ) {
        when (type) {
            is TypeRef.Named -> when {
                type.name in setOf("List", "Set") && type.args.size == 1 -> {
                    val element = type.args.single()
                    if (element !is TypeRef.Named || element.args.isNotEmpty() || element.name !in primitiveTypes) {
                        errors += "line $line: generated serializer supports only primitive elements in '$owner::$field' of type ${renderType(type)}"
                    }
                }
                type.name == "Map" && type.args.size == 2 -> {
                    val key = type.args[0]
                    val value = type.args[1]
                    if (key !is TypeRef.Named || key.name != "String" || key.args.isNotEmpty()) {
                        errors += "line $line: generated serializer requires String keys in '$owner::$field' of type ${renderType(type)}"
                    }
                    if (value !is TypeRef.Named || value.args.isNotEmpty() || value.name !in primitiveTypes) {
                        errors += "line $line: generated serializer supports only primitive map values in '$owner::$field' of type ${renderType(type)}"
                    }
                }
                type.args.isNotEmpty() -> errors += "line $line: generated serializer does not yet support generic field '$owner::$field' of type ${renderType(type)}"
                type.name in primitiveTypes -> Unit
                type.name in serializablePacks -> Unit
                else -> errors += "line $line: field '$owner::$field' has non-serializable type '${type.name}'"
            }
            is TypeRef.Nullable -> {
                val inner = type.inner
                if (inner !is TypeRef.Named || inner.args.isNotEmpty() || inner.name !in primitiveTypes) {
                    errors += "line $line: generated serializer supports only nullable primitive field '$owner::$field', got ${renderType(type)}"
                }
            }
            else -> errors += "line $line: generated serializer does not yet support field '$owner::$field' of type ${renderType(type)}"
        }
    }

    private fun generateImpl(
        pack: TopLevel.Pack,
        fields: List<FieldPlan>,
        ignoreUnknown: Boolean,
        encodeDefaults: Boolean,
        formats: FormatSet,
        helpers: Helpers,
    ): String = buildString {
        appendLine("impl ${pack.name} {")
        appendLine("    func toSerialValue(value: ref ${pack.name}): SerialValue ?! SerializationError { self& ->")
        appendLine("        var __serialFields = List()")
        fields.filterNot { it.ignored }.forEach { plan ->
            val encoded = appendEncodedField(plan, helpers)
            if (!encodeDefaults && plan.defaultSource != null && !plan.required) {
                appendLine("        if value.${plan.field.name} != ${plan.defaultSource} {")
                appendLine("            __serialFields.add(SerialField(${quote(plan.wireName)}, $encoded))")
                appendLine("        }")
            } else {
                appendLine("        __serialFields.add(SerialField(${quote(plan.wireName)}, $encoded))")
            }
        }
        appendLine("        return SerialValue.Object(__serialFields)")
        appendLine("    }")
        appendLine()
        appendLine("    func fromSerialValue(value: ref SerialValue): ${pack.name} ?! SerializationError { self& ->")
        appendLine("        when value {")
        appendLine("            SerialValue.Object(__serialFields) -> {")
        fields.filterNot { it.ignored }.forEach { plan ->
            appendLine("                var __seen_${plan.field.name} = false")
            appendLine("                var __raw_${plan.field.name} = SerialValue.Null")
        }
        appendLine("                var __serialIndex = 0")
        appendLine("                while __serialIndex < __serialFields.size {")
        appendLine("                    fin __serialField = ${qualified(helpers.provider, "serialFieldAt")}(__serialFields, __serialIndex)")
        val active = fields.filterNot { it.ignored }
        active.forEachIndexed { index, plan ->
            val prefix = if (index == 0) "if" else "else if"
            appendLine("                    $prefix __serialField.name == ${quote(plan.wireName)} {")
            appendLine("                        if __seen_${plan.field.name} { return .DuplicateField }")
            appendLine("                        __seen_${plan.field.name} = true")
            appendLine("                        __raw_${plan.field.name} = __serialField.value")
            appendLine("                    }")
        }
        if (active.isEmpty()) {
            if (!ignoreUnknown) appendLine("                    return .UnknownField")
        } else if (!ignoreUnknown) {
            appendLine("                    else { return .UnknownField }")
        }
        appendLine("                    __serialIndex += 1")
        appendLine("                }")
        active.filter { it.required || it.field.default == null }.forEach { plan ->
            appendLine("                if !__seen_${plan.field.name} { return .MissingField }")
        }
        active.filter { isCollection(it.field.type) }.forEach { plan ->
            appendDecodedCollection(plan, helpers)
        }
        appendLine("                return ${pack.name}(")
        fields.forEachIndexed { index, plan ->
            val value = when {
                plan.ignored -> plan.defaultSource!!
                isCollection(plan.field.type) && plan.defaultSource != null ->
                    "if __seen_${plan.field.name} { __decoded_${plan.field.name} } else { ${plan.defaultSource} }"
                isCollection(plan.field.type) -> "__decoded_${plan.field.name}"
                plan.defaultSource != null -> "if __seen_${plan.field.name} { ${decodeExpr("__raw_${plan.field.name}", plan.field.type, "self.${plan.field.name}", helpers)} } else { ${plan.defaultSource} }"
                else -> decodeExpr("__raw_${plan.field.name}", plan.field.type, "self.${plan.field.name}", helpers)
            }
            append("                    ${plan.field.name}: $value")
            appendLine(if (index == fields.lastIndex) "" else ",")
        }
        appendLine("                )")
        appendLine("            }")
        appendLine("            else -> { return .UnexpectedType }")
        appendLine("        }")
        appendLine("    }")
        if (formats.json) appendFormatMethods(pack.name, "Json", "Json", "toJson", "fromJson", helpers)
        if (formats.azon) appendFormatMethods(pack.name, "Azon", "Azon", "toAzon", "fromAzon", helpers)
        appendLine("}")
    }

    private fun StringBuilder.appendEncodedField(plan: FieldPlan, helpers: Helpers): String {
        val type = plan.field.type as? TypeRef.Named ?: return encodeExpr("value.${plan.field.name}", plan.field.type, helpers)
        if (type.name == "Map" && type.args.size == 2) {
            val valueType = type.args[1]
            val fieldsName = "__encoded_${plan.field.name}"
            val keysName = "__keys_${plan.field.name}"
            val indexName = "__encode_${plan.field.name}_index"
            appendLine("        var $fieldsName = List()")
            appendLine("        fin $keysName = value.${plan.field.name}.keys()")
            appendLine("        var $indexName = 0")
            appendLine("        while $indexName < $keysName.length {")
            val keyName = "__key_${plan.field.name}"
            appendLine("            fin $keyName = $keysName[$indexName] as String")
            val mapValue = "value.${plan.field.name}[$keyName] as ${renderType(valueType)}"
            appendLine("            $fieldsName.add(SerialField($keyName, ${encodeExpr(mapValue, valueType, helpers)}))")
            appendLine("            $indexName += 1")
            appendLine("        }")
            return "SerialValue.Object($fieldsName)"
        }
        if (type.name !in setOf("List", "Set") || type.args.size != 1) {
            return encodeExpr("value.${plan.field.name}", plan.field.type, helpers)
        }
        val element = type.args.single()
        val valuesName = "__encoded_${plan.field.name}"
        val indexName = "__encode_${plan.field.name}_index"
        appendLine("        var $valuesName = List()")
        appendLine("        var $indexName = 0")
        appendLine("        while $indexName < value.${plan.field.name}.size {")
        val elementValue = "value.${plan.field.name}[$indexName] as ${renderType(element)}"
        appendLine("            $valuesName.add(${encodeExpr(elementValue, element, helpers)})")
        appendLine("            $indexName += 1")
        appendLine("        }")
        return "SerialValue.Array($valuesName)"
    }

    private fun StringBuilder.appendDecodedCollection(plan: FieldPlan, helpers: Helpers) {
        val type = plan.field.type as TypeRef.Named
        if (type.name == "Map") {
            val valueType = type.args[1]
            val decodedName = "__decoded_${plan.field.name}"
            val fieldsName = "__map_fields_${plan.field.name}"
            val indexName = "__decode_${plan.field.name}_index"
            appendLine("                var $decodedName = Map()")
            appendLine("                if __seen_${plan.field.name} {")
            appendLine("                    when __raw_${plan.field.name} {")
            appendLine("                        SerialValue.Object($fieldsName) -> {")
            appendLine("                            var $indexName = 0")
            appendLine("                            while $indexName < $fieldsName.size {")
            val fieldName = "__map_field_${plan.field.name}"
            appendLine("                                fin $fieldName = ${qualified(helpers.provider, "serialFieldAt")}($fieldsName, $indexName)")
            appendLine("                                $decodedName.put($fieldName.name, ${decodeExpr("$fieldName.value", valueType, "self.${plan.field.name}", helpers)})")
            appendLine("                                $indexName += 1")
            appendLine("                            }")
            appendLine("                        }")
            appendLine("                        else -> { return .UnexpectedType }")
            appendLine("                    }")
            appendLine("                }")
            return
        }
        val element = type.args.single()
        val decodedName = "__decoded_${plan.field.name}"
        val valuesName = "__values_${plan.field.name}"
        val indexName = "__decode_${plan.field.name}_index"
        appendLine("                var $decodedName = ${type.name}()")
        appendLine("                if __seen_${plan.field.name} {")
        appendLine("                    when __raw_${plan.field.name} {")
        appendLine("                        SerialValue.Array($valuesName) -> {")
        appendLine("                            var $indexName = 0")
        appendLine("                            while $indexName < $valuesName.size {")
        val rawElement = "${qualified(helpers.provider, "serialValueAt")}($valuesName, $indexName)"
        appendLine("                                $decodedName.add(${decodeExpr(rawElement, element, "self.${plan.field.name}", helpers)})")
        appendLine("                                $indexName += 1")
        appendLine("                            }")
        appendLine("                        }")
        appendLine("                        else -> { return .UnexpectedType }")
        appendLine("                    }")
        appendLine("                }")
    }

    private fun StringBuilder.appendFormatMethods(
        typeName: String,
        formatName: String,
        methodSuffix: String,
        encodeMethod: String,
        decodeMethod: String,
        helpers: Helpers,
    ) {
        appendLine()
        appendLine("    func $encodeMethod(value: ref $typeName, options: ref SerializerOptions): String ?! SerializationError { self& ->")
        appendLine("        fin __serialValue = try self.toSerialValue(value)")
        appendLine("        return try ${qualified(helpers.provider, "encodeSerialValue")}(__serialValue, SerializationFormat.$formatName, options)")
        appendLine("    }")
        appendLine()
        appendLine("    func $decodeMethod(input: String, options: ref SerializerOptions): $typeName ?! SerializationError { self& ->")
        appendLine("        fin __serialValue = try ${qualified(helpers.provider, "decodeSerialValue")}(input, SerializationFormat.$methodSuffix, options)")
        appendLine("        return try self.fromSerialValue(__serialValue)")
        appendLine("    }")
    }

    private fun encodeExpr(value: String, type: TypeRef, helpers: Helpers): String {
        if (type is TypeRef.Nullable) {
            val inner = type.inner
            val present = "$value as ${renderType(inner)}"
            return "if $value == null { SerialValue.Null } else { ${encodeExpr(present, inner, helpers)} }"
        }
        val named = type as TypeRef.Named
        return when (named.name) {
            "String" -> "SerialValue.Text($value)"
            "Bool" -> "SerialValue.Bool($value)"
            "Char" -> "SerialValue.Text(${qualified(helpers.conversionProvider, "toString")}($value))"
            in integerTypes, in realTypes -> "SerialValue.Number(${qualified(helpers.conversionProvider, "toString")}($value))"
            else -> "try $value.toSerialValue($value)"
        }
    }

    private fun decodeExpr(raw: String, type: TypeRef, receiverValue: String, helpers: Helpers): String {
        if (type is TypeRef.Nullable) {
            return "if $raw == SerialValue.Null { null } else { ${decodeExpr(raw, type.inner, receiverValue, helpers)} }"
        }
        val named = type as TypeRef.Named
        return when (named.name) {
            "String" -> "try ${qualified(helpers.provider, "serialAsText")}($raw)"
            "Bool" -> "try ${qualified(helpers.provider, "serialAsBool")}($raw)"
            "Char" -> "try ${qualified(helpers.provider, "serialAsChar")}($raw)"
            in integerTypes -> "try ${qualified(helpers.provider, "serialAs${named.name}")}($raw)"
            in realTypes -> "try ${qualified(helpers.provider, "serialAsReal")}($raw) as ${named.name}"
            else -> "try $receiverValue.fromSerialValue($raw)"
        }
    }

    private fun isCollection(type: TypeRef): Boolean =
        type is TypeRef.Named && (
            type.name in setOf("List", "Set") && type.args.size == 1 ||
                type.name == "Map" && type.args.size == 2
            )

    private fun qualified(provider: String, name: String): String =
        if (provider.isEmpty()) name else "$provider::$name"

    private fun renderType(type: TypeRef): String = when (type) {
        is TypeRef.Named -> type.name + if (type.args.isEmpty()) "" else type.args.joinToString(", ", "<", ">") { renderType(it) }
        is TypeRef.Array -> "[${renderType(type.element)}]"
        is TypeRef.Map -> "Map<${renderType(type.key)}, ${renderType(type.value)}>"
        is TypeRef.Set -> "Set<${renderType(type.element)}>"
        is TypeRef.Tuple -> type.elements.joinToString(", ", "(", ")") { renderType(it) }
        is TypeRef.Nullable -> "${renderType(type.inner)}?"
        is TypeRef.Pointer -> "${renderType(type.inner)}*"
        is TypeRef.Function -> type.toString()
        is TypeRef.Failable -> if (type.errSets.size == 1) {
            "${renderType(type.ok)} ?! ${type.errSets.single()}"
        } else {
            "${renderType(type.ok)} ?! [${type.errSets.joinToString(", ")}]"
        }
        is TypeRef.Reference -> "${type.kind.spelling} ${renderType(type.inner)}"
        is TypeRef.Const -> type.value.toString()
    }

    private fun renderExpr(expr: Expr): String? = when (expr) {
        is Expr.IntLiteral -> expr.value.toString() + integerSuffix(expr.suffix)
        is Expr.RealLiteral -> expr.value.toString() + realSuffix(expr.suffix)
        is Expr.StringLiteral -> quote(expr.value)
        is Expr.CharLiteral -> "'${escapeChar(expr.value)}'"
        is Expr.BoolLiteral -> expr.value.toString()
        is Expr.NullLiteral -> "null"
        is Expr.Identifier -> expr.name
        is Expr.Grouping -> renderExpr(expr.expr)?.let { "($it)" }
        is Expr.Unary -> renderExpr(expr.operand)?.let { "${operator(expr.op)}$it" }
        is Expr.Binary -> {
            val left = renderExpr(expr.left)
            val right = renderExpr(expr.right)
            if (left == null || right == null) null else "($left ${operator(expr.op)} $right)"
        }
        is Expr.Call -> {
            val args = expr.args.map { renderExpr(it) ?: return null }
            expr.callee + args.joinToString(", ", "(", ")")
        }
        is Expr.Member -> renderExpr(expr.target)?.let { "$it.${expr.name}" }
        is Expr.ArrayLiteral -> expr.elements.map { renderExpr(it) ?: return null }.joinToString(", ", "[", "]")
        is Expr.SetLiteral -> expr.elements.map { renderExpr(it) ?: return null }.joinToString(", ", "![", "]")
        is Expr.MapLit -> expr.entries.map {
            val key = renderExpr(it.first) ?: return null
            val value = renderExpr(it.second) ?: return null
            "$key: $value"
        }.joinToString(", ", "[", "]")
        is Expr.TupleLit -> expr.elements.map { renderExpr(it) ?: return null }.joinToString(", ", "(", ")")
        is Expr.NamedArg -> renderExpr(expr.value)?.let { "${expr.name}: $it" }
        is Expr.NullCoalesce -> {
            val left = renderExpr(expr.left)
            val right = renderExpr(expr.right)
            if (left == null || right == null) null else "($left ?? $right)"
        }
        else -> null
    }

    private fun operator(type: TokenType): String = when (type) {
        TokenType.PLUS -> "+"
        TokenType.MINUS -> "-"
        TokenType.STAR -> "*"
        TokenType.SLASH -> "/"
        TokenType.PERCENT -> "%"
        TokenType.EQUAL_EQUAL -> "=="
        TokenType.BANG_EQUAL -> "!="
        TokenType.LESS -> "<"
        TokenType.LESS_EQUAL -> "<="
        TokenType.GREATER -> ">"
        TokenType.GREATER_EQUAL -> ">="
        TokenType.AND_AND -> "&&"
        TokenType.OR_OR -> "||"
        TokenType.BANG -> "!"
        else -> error("unsupported source operator $type")
    }

    private fun integerSuffix(suffix: NumericSuffix): String = when (suffix) {
        NumericSuffix.NONE -> ""
        NumericSuffix.BYTE -> "b"
        NumericSuffix.UBYTE -> "ub"
        NumericSuffix.SHORT -> "s"
        NumericSuffix.USHORT -> "us"
        NumericSuffix.UINT -> "u"
        NumericSuffix.LONG -> "L"
        NumericSuffix.ULONG -> "uL"
        NumericSuffix.CENT -> "c"
        NumericSuffix.UCENT -> "uc"
        NumericSuffix.FLOAT, NumericSuffix.DECIMAL -> ""
    }

    private fun realSuffix(suffix: NumericSuffix): String = when (suffix) {
        NumericSuffix.FLOAT -> "f"
        NumericSuffix.DECIMAL -> "D"
        else -> ""
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            append(
                when (char) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> char
                }
            )
        }
        append('"')
    }

    private fun escapeChar(value: Char): String = when (value) {
        '\\' -> "\\\\"
        '\'' -> "\\'"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        else -> value.toString()
    }

    private val primitiveTypes = integerTypes + realTypes + setOf("String", "Bool", "Char")
}
