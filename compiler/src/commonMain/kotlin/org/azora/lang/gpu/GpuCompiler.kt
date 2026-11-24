/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.gpu

import org.azora.lang.frontend.*
import org.azora.lang.frontend.Annotation

enum class GpuTarget(val extension: String, val defaultProfile: String) {
    METAL("metallib", "macosx"),
    VULKAN("spv", "vulkan1.2"),
    DIRECTX("dxil", "6.0");

    companion object {
        fun fromArgument(argument: String): GpuTarget? =
            entries.firstOrNull { it.extension == argument.removePrefix(".") || it.name.equals(argument, ignoreCase = true) }
    }
}

class GpuCompileException(message: String) : IllegalArgumentException(message)

data class GpuShaderSource(
    val text: String,
    val entry: String,
    val stage: GpuStage,
    val backendEntry: String = entry,
)

enum class GpuStage(val annotation: String, val glslStage: String, val shaderModelPrefix: String) {
    VERTEX("Vertex", "vert", "vs"),
    FRAGMENT("Fragment", "frag", "ps"),
    COMPUTE("Compute", "comp", "cs"),
}

object GpuCompiler {
    fun compile(source: String, file: String, target: GpuTarget, entry: String? = null): GpuShaderSource {
        val program = GpuProgram(source, file)
        val selected = program.selectEntry(entry)
        return GpuEmitter(program, target).emit(selected)
    }

    fun check(source: String, file: String) {
        GpuEmitter(GpuProgram(source, file), GpuTarget.VULKAN).check()
    }
}

internal data class GpuSite(val file: String, val line: Int) {
    fun fail(message: String): Nothing = throw GpuCompileException("$file:$line: $message")
    fun require(condition: Boolean, message: String) {
        if (!condition) fail(message)
    }
}

internal enum class GpuScalar(val sourceName: String, val shaderName: String) {
    BOOL("Bool", "bool"), INT("Int", "int"), UINT("UInt", "uint"), FLOAT("Float", "float"), UNIT("Unit", "void");
}

internal sealed interface GpuType {
    data class Numeric(val scalar: GpuScalar, val width: Int = 1) : GpuType {
        override fun toString(): String = scalar.sourceName + if (width > 1) width.toString() else ""
    }
    data class Named(val name: String) : GpuType {
        override fun toString(): String = name
    }
}

internal val gpuBool = GpuType.Numeric(GpuScalar.BOOL)
internal val gpuInt = GpuType.Numeric(GpuScalar.INT)
internal val gpuUInt = GpuType.Numeric(GpuScalar.UINT)
internal val gpuFloat = GpuType.Numeric(GpuScalar.FLOAT)
internal val gpuUnit = GpuType.Numeric(GpuScalar.UNIT)
internal val gpuFloat4 = GpuType.Numeric(GpuScalar.FLOAT, 4)
internal val gpuUInt3 = GpuType.Numeric(GpuScalar.UINT, 3)

internal data class GpuField(val name: String, val type: GpuType, val mutable: Boolean, val port: GpuPort?)
internal data class GpuCase(val name: String, val index: Int, val payload: List<GpuType>) {
    fun field(indexInPayload: Int) = "p${index}_$indexInPayload"
}

internal data class GpuAggregate(
    val name: String,
    val site: GpuSite,
    val fields: List<GpuField> = emptyList(),
    val cases: List<GpuCase> = emptyList(),
    val tagged: Boolean = false,
) {
    val type = GpuType.Named(name)
    val plainEnum: Boolean get() = cases.isNotEmpty() && !tagged
    val dependencies: List<GpuType> get() = fields.map { it.type } + cases.flatMap { it.payload }
}

internal data class GpuParameter(val name: String, val type: GpuType, val port: GpuPort?)
internal class GpuFunction(
    val decl: FuncDecl,
    val file: String,
    val owner: GpuAggregate?,
    val parameters: List<GpuParameter>,
    val result: GpuType,
    val stage: GpuStage?,
    val workgroup: List<Int>,
    val resultPort: GpuPort?,
) {
    val site = GpuSite(file, decl.line)
    val receiver: Boolean get() = owner != null && decl.declaresReceiver
    val mutableReceiver: Boolean get() = receiver && decl.receiverModifier == ParamModifier.EXCLUSIVE
    val displayName: String get() = listOfNotNull(owner?.name, decl.name).joinToString(".")
    val symbol: String get() = owner?.let { "az_m_${gpuName(it.name)}_${gpuName(decl.name)}" } ?: "az_f_${gpuName(decl.name)}"
}

internal data class GpuConstant(val decl: TopLevel.FinDecl, val file: String)

// Length-prefixed code points avoid ambiguities between source components and
// make both Unicode and identifiers reserved by a target safe in generated code.
internal fun gpuName(name: String): String = buildString {
    append(name.length)
    append('_')
    for (character in name) {
        if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9') append(character)
        else append('_').append(character.code.toString(16)).append('_')
    }
}

internal sealed interface GpuPort {
    data class Location(val index: Int) : GpuPort
    data class Storage(val binding: Int, val writable: Boolean) : GpuPort
    enum class Builtin(
        val annotation: String,
        val stage: GpuStage,
        val type: GpuType,
        val glsl: String,
        val hlsl: String,
        val metal: String,
    ) : GpuPort {
        VERTEX_INDEX("VertexIndex", GpuStage.VERTEX, gpuUInt, "gl_VertexIndex", "SV_VertexID", "vertex_id"),
        INSTANCE_INDEX("InstanceIndex", GpuStage.VERTEX, gpuUInt, "gl_InstanceIndex", "SV_InstanceID", "instance_id"),
        GLOBAL_ID("GlobalInvocationId", GpuStage.COMPUTE, gpuUInt3, "gl_GlobalInvocationID", "SV_DispatchThreadID", "thread_position_in_grid"),
        LOCAL_ID("LocalInvocationId", GpuStage.COMPUTE, gpuUInt3, "gl_LocalInvocationID", "SV_GroupThreadID", "thread_position_in_threadgroup"),
        GROUP_ID("WorkgroupId", GpuStage.COMPUTE, gpuUInt3, "gl_WorkGroupID", "SV_GroupID", "threadgroup_position_in_grid"),
        LOCAL_INDEX("LocalInvocationIndex", GpuStage.COMPUTE, gpuUInt, "gl_LocalInvocationIndex", "SV_GroupIndex", "thread_index_in_threadgroup"),
        POSITION("Position", GpuStage.FRAGMENT, gpuFloat4, "gl_FragCoord", "SV_Position", "position"),
    }
}

internal class GpuProgram(source: String, private val file: String) {
    private val root = GpuSite(file, 1)
    private val units = linkedMapOf(file to parse(source, file))
    private val aggregateDeclarations = linkedMapOf<String, Pair<TopLevel, GpuSite>>()
    val aggregates = linkedMapOf<String, GpuAggregate>()
    val functions = linkedMapOf<String, GpuFunction>()
    val constants = linkedMapOf<String, GpuConstant>()
    private val externalEntryName = Regex("[A-Za-z_][A-Za-z0-9_]*")

    init {
        loadImports(file, mutableSetOf())
        for ((path, unit) in units) for (item in unit.items) when (item) {
            is TopLevel.Pack -> declareType(item.name, item, path, item.line)
            is TopLevel.Enum -> declareType(item.name, item, path, item.line)
            is TopLevel.Slot -> declareType(item.name, item, path, item.line)
            else -> Unit
        }
        for ((item, site) in aggregateDeclarations.values) {
            val aggregate = aggregate(item, site)
            aggregates[aggregate.name] = aggregate
        }
        for ((path, unit) in units) for (item in unit.items) when (item) {
            is TopLevel.Pack, is TopLevel.Enum, is TopLevel.Slot, is TopLevel.UseImport -> Unit
            is TopLevel.Func -> {
                val declaration = item.decl
                val separator = declaration.name.indexOf("__")
                if (separator <= 0) {
                    registerFunction(declaration, path, null)
                } else {
                    // The parser lifts static impl methods into qualified top-level functions.
                    val owner = aggregates[declaration.name.substring(0, separator)]
                        ?: GpuSite(path, declaration.line).fail("GPU static function requires a declared type")
                    registerFunction(declaration.copy(name = declaration.name.substring(separator + 2)), path, owner)
                }
            }
            is TopLevel.Impl -> {
                val site = GpuSite(path, item.line)
                site.require(item.traitName == null && item.typeParams.isEmpty() && !item.isExtension &&
                    !item.isBridge && item.annotations.isEmpty() && item.scopePrefix == null,
                    "only inherent, non-generic impl blocks are available on the GPU")
                val owner = aggregates[item.typeName] ?: site.fail("unknown type '${item.typeName}'")
                for (method in item.methods) registerFunction(method, path, owner)
            }
            is TopLevel.FinDecl -> {
                val site = GpuSite(path, item.line)
                site.require(!item.threadlocal && item.annotations.isEmpty(), "unsupported global fin modifier")
                site.require(item.name !in constants && !isType(item.name), "duplicate declaration '${item.name}'")
                constants[item.name] = GpuConstant(item, path)
            }
            else -> {
                val (line, kind) = when (item) {
                    is TopLevel.VarDecl -> item.line to "global var"
                    else -> 1 to (item::class.simpleName ?: "declaration")
                }
                GpuSite(path, line).fail("$kind is unavailable on the GPU")
            }
        }
        for ((name, constant) in constants) {
            GpuSite(constant.file, constant.decl.line).require(name !in functions, "duplicate declaration '$name'")
        }
    }

    private fun loadImports(path: String, visiting: MutableSet<String>) {
        val unit = units.getValue(path)
        visiting.add(path)
        for (item in unit.items.filterIsInstance<TopLevel.UseImport>()) {
            val site = GpuSite(path, item.line)
            site.require(!item.exported && item.condition == null, "unsupported import modifier")
            for (spec in item.importSpecs) {
                site.require(spec.alias == null && spec.without.isEmpty(), "import a gpustdlib module by its full name")
                val module = GpuStdlibBundle.modules[spec.path] ?: site.fail("unknown GPU module '${spec.path}'")
                val libraryPath = spec.path.replace('.', '/') + ".azgpu"
                site.require(libraryPath !in visiting, "cyclic import '${spec.path}'")
                if (libraryPath !in units) {
                    val library = parse(module, libraryPath)
                    site.require(library.moduleName == spec.path, "module '${spec.path}' has a mismatched header")
                    units[libraryPath] = library
                    loadImports(libraryPath, visiting)
                }
            }
        }
        visiting.remove(path)
    }

    private fun declareType(name: String, item: TopLevel, path: String, line: Int) {
        val site = GpuSite(path, line)
        site.require(!isType(name), "duplicate type '$name'")
        aggregateDeclarations[name] = item to site
    }

    fun type(ref: TypeRef, site: GpuSite, allowUnit: Boolean = false): GpuType {
        val named = ref as? TypeRef.Named ?: site.fail("GPU types must be concrete value types")
        site.require(named.args.isEmpty() && !named.variadic && named.qualifier == null && named.valueArgs.isEmpty(),
            "GPU types cannot have generic arguments or qualifiers")
        val type = findType(named.name) ?: site.fail("unknown GPU type '${named.name}'")
        site.require(allowUnit || type != gpuUnit, "Unit is only valid as a function result")
        return type
    }

    fun findType(name: String): GpuType? = primitiveTypes[name]
        ?: GpuType.Named(name).takeIf { name in aggregateDeclarations }

    private fun isType(name: String): Boolean = findType(name) != null

    private fun aggregate(item: TopLevel, site: GpuSite): GpuAggregate = when (item) {
        is TopLevel.Pack -> {
            site.require(item.typeParams.isEmpty() && item.whereClause == null && !item.isBridge &&
                !item.isUnion && item.annotations.isEmpty() && item.fieldTemplate == null,
                "unsupported pack modifier")
            distinct(item.fields.map { it.name }, site, "field")
            val fields = item.fields.map { field ->
                site.require(field.default == null && !field.isUnsafe && field.repeats.isEmpty() &&
                    field.condition == null && field.visibility == Visibility.PUBLIC, "unsupported field modifier for '${field.name}'")
                GpuField(field.name, type(field.type, site), field.mutable, port(field.annotations, site))
            }
            GpuAggregate(item.name, site, fields)
        }
        is TopLevel.Enum -> {
            site.require(item.typeParams.isEmpty() && item.whereClause == null && item.valueType == null &&
                item.annotations.isEmpty() && item.variantAnnotations.all { it.isEmpty() }, "unsupported enum modifier")
            site.require(item.variants.isNotEmpty(), "enum '${item.name}' needs a case")
            distinct(item.variants, site, "case")
            GpuAggregate(item.name, site, cases = item.variants.mapIndexed { index, name -> GpuCase(name, index, emptyList()) })
        }
        is TopLevel.Slot -> {
            site.require(item.typeParams.isEmpty() && item.whereClause == null && !item.isError && item.annotations.isEmpty(),
                "unsupported variant enum modifier")
            site.require(item.variants.isNotEmpty(), "variant enum '${item.name}' needs a case")
            distinct(item.variants.map { it.name }, site, "case")
            GpuAggregate(item.name, site, cases = item.variants.mapIndexed { index, case ->
                GpuCase(case.name, index, case.payloadTypes.map { type(it, site) })
            }, tagged = true)
        }
        else -> error("unexpected aggregate")
    }

    private fun registerFunction(decl: FuncDecl, path: String, owner: GpuAggregate?) {
        val site = GpuSite(path, decl.line)
        site.require(decl.typeParams.isEmpty() && decl.whereClause == null && !decl.isInline &&
            !decl.isDeepInline && !decl.isTask && !decl.isReactive && !decl.isVirtual && !decl.isOverride &&
            !decl.isUnsafe && decl.extensionReceiver == null && decl.contextualParams == 0 &&
            decl.memberCallStyle == MemberCallStyle.NORMAL && decl.visibility == Visibility.PUBLIC,
            "unsupported function modifier for '${decl.name}'")
        val stageAnnotation = decl.annotations.singleOrNull()
        if (stageAnnotation != null) checkAttribute(stageAnnotation, site)
        val stage = stageAnnotation?.let { annotation ->
            GpuStage.entries.firstOrNull { it.annotation == annotation.name } ?: site.fail("unknown GPU stage @${annotation.name}")
        }
        site.require(decl.annotations.size <= 1 && (stage == null || owner == null && path == file),
            "only top-level shader functions may declare a GPU stage")
        val workgroup = if (stage == GpuStage.COMPUTE) {
            integers(checkNotNull(stageAnnotation), 3, site).also { dimensions ->
                validateWorkgroup(dimensions, site)
            }
        } else {
            stageAnnotation?.let { integers(it, 0, site) }
            emptyList()
        }
        distinct(decl.params.map { it.name } + listOfNotNull(decl.receiverName.takeIf { owner != null && decl.declaresReceiver }),
            site, "parameter")
        val parameters = decl.params.map { parameter ->
            site.require(parameter.defaultValue == null && parameter.modifier == ParamModifier.NONE &&
                !parameter.variadic && !parameter.returnsOwnership, "unsupported parameter modifier for '${parameter.name}'")
            GpuParameter(parameter.name, type(parameter.type, site), port(parameter.annotations, site))
        }
        val returnRef = (decl.returnType as? TypeAnnotation.Explicit)?.ref
        val result = returnRef?.let { type(it, site, allowUnit = true) } ?: gpuUnit
        val resultPort = when (stage) {
            GpuStage.VERTEX -> GpuPort.Builtin.POSITION.takeIf { result == gpuFloat4 }
            GpuStage.FRAGMENT -> GpuPort.Location(0).takeIf { result == gpuFloat4 }
            else -> null
        }
        val function = GpuFunction(decl, path, owner, parameters, result, stage, workgroup, resultPort)
        if (stage == null) {
            site.require(parameters.all { it.port == null }, "GPU interface attributes require an entry point")
        } else {
            site.require(externalEntryName.matches(decl.name), "GPU entry points require an ASCII identifier")
            validateInterface(function)
        }
        site.require(function.displayName !in functions && (owner != null || !isType(decl.name)),
            "duplicate function '${function.displayName}'")
        functions[function.displayName] = function
    }

    private fun validateInterface(function: GpuFunction) {
        val site = function.site
        val stage = function.stage!!
        val locations = mutableSetOf<Int>()
        val bindings = mutableSetOf<Int>()
        val builtins = mutableSetOf<GpuPort.Builtin>()
        for (parameter in function.parameters) {
            when (val port = parameter.port) {
                null -> site.fail("entry parameter '${parameter.name}' needs a GPU interface attribute")
                is GpuPort.Builtin -> site.require(port.stage == stage && parameter.type == port.type && builtins.add(port),
                    "@${port.annotation} requires ${port.type} and is unique to ${port.stage.annotation} inputs")
                is GpuPort.Location -> site.require(stage != GpuStage.COMPUTE && interfaceType(parameter.type) &&
                    locations.add(port.index), "input @Location requires a unique graphics location and a numeric scalar or vector")
                is GpuPort.Storage -> site.require(stage == GpuStage.COMPUTE && parameter.type in scalarStorageTypes &&
                    bindings.add(port.binding), "storage requires a unique compute binding and an Int, UInt or Float element")
            }
        }
        if (stage == GpuStage.COMPUTE) {
            site.require(function.result == gpuUnit, "compute entry points return Unit")
            return
        }
        if (function.resultPort != null) return
        val pack = (function.result as? GpuType.Named)?.let { aggregates[it.name] }
            ?: site.fail("graphics entry points return Float4 or a pack with explicit output attributes")
        val outputs = mutableSetOf<Int>()
        var hasPosition = false
        site.require(pack.fields.isNotEmpty(), "graphics output pack must have fields")
        for (field in pack.fields) when (val port = field.port) {
            is GpuPort.Location -> site.require(interfaceType(field.type) && outputs.add(port.index),
                "output @Location requires a unique location and a numeric scalar or vector")
            GpuPort.Builtin.POSITION -> {
                site.require(stage == GpuStage.VERTEX && field.type == gpuFloat4 && !hasPosition,
                    "only vertex output may have a single @Position Float4")
                hasPosition = true
            }
            else -> site.fail("output field '${field.name}' needs @Location or @Position")
        }
        site.require(stage != GpuStage.VERTEX || hasPosition, "vertex output requires @Position")
    }

    private fun validateWorkgroup(dimensions: List<Int>, site: GpuSite) {
        // Shader Model 6 compute limits also bound the shared stage interface.
        val (x, y, z) = dimensions
        site.require(x in 1..1024 && y in 1..1024 && z in 1..64 && x.toLong() * y * z <= 1024,
            "workgroup dimensions exceed the compute profile limit")
    }

    fun selectEntry(entry: String?): GpuFunction {
        val entries = functions.values.filter { it.stage != null }
        if (entry != null) return entries.firstOrNull { it.decl.name == entry }
            ?: root.fail("unknown GPU entry point '$entry'")
        return entries.singleOrNull() ?: root.fail(
            if (entries.isEmpty()) "add a @Vertex, @Fragment or @Compute entry point"
            else "select a GPU entry point with --entry (${entries.joinToString { it.decl.name }})",
        )
    }

    private fun parse(source: String, path: String): Program {
        val site = GpuSite(path, 1)
        try {
            val tokens = Lexer(source).tokenize()
            tokens.firstOrNull { it.type in cpuOnlyTokens }?.let {
                GpuSite(path, it.line).fail("'${it.lexeme}' is unavailable on the GPU")
            }
            val program = Parser(tokens, allowWhenExpressions = false).parse()
            site.require(!program.usesMacros && program.typeFunctions.isEmpty() && program.typeMacroRules.isEmpty() &&
                program.scopes.isEmpty() && program.scopeTypeNamespaces.isEmpty() && program.moduleAnnotations.isEmpty() &&
                program.exportCondition == null && !program.isExported && program.moduleVisibility == ModuleVisibility.PUBLIC,
                "unsupported GPU module declaration or metaprogramming construct")
            return program
        } catch (error: GpuCompileException) {
            throw error
        } catch (error: IllegalStateException) {
            throw GpuCompileException("$path: ${error.message}")
        } catch (error: IllegalArgumentException) {
            throw GpuCompileException("$path: ${error.message}")
        }
    }

    private fun port(annotations: List<Annotation>, site: GpuSite): GpuPort? {
        if (annotations.isEmpty()) return null
        val attribute = annotations.singleOrNull() ?: site.fail("expected a single GPU interface attribute")
        checkAttribute(attribute, site)
        return when (attribute.name) {
            "Location" -> GpuPort.Location(integers(attribute, 1, site).single())
            "Storage", "ReadOnlyStorage" -> GpuPort.Storage(integers(attribute, 1, site).single(), attribute.name == "Storage")
            else -> {
                val builtin = GpuPort.Builtin.entries.firstOrNull { it.annotation == attribute.name }
                    ?: site.fail("unknown GPU interface attribute @${attribute.name}")
                integers(attribute, 0, site)
                builtin
            }
        }
    }

    private fun checkAttribute(attribute: Annotation, site: GpuSite) {
        site.require(attribute.qualifier == null && attribute.namedArgs.isEmpty(), "GPU attributes use positional arguments")
    }

    private fun integers(attribute: Annotation, count: Int, site: GpuSite): List<Int> {
        site.require(attribute.args.size == count, "@${attribute.name} requires $count integer argument(s)")
        return attribute.args.map {
            val value = (it as? Expr.IntLiteral)?.takeIf { literal -> literal.magnitude == null }?.value
            if (value == null || value !in 0..Int.MAX_VALUE) site.fail("@${attribute.name} requires nonnegative 32-bit integers")
            value.toInt()
        }
    }

    private fun distinct(names: List<String>, site: GpuSite, description: String) {
        val seen = mutableSetOf<String>()
        names.firstOrNull { !seen.add(it) }?.let { site.fail("duplicate $description '$it'") }
    }

    companion object {
        private val primitiveTypes = buildMap {
            for (scalar in GpuScalar.entries) {
                val widths = if (scalar == GpuScalar.BOOL || scalar == GpuScalar.UNIT) 1..1 else 1..4
                for (width in widths) GpuType.Numeric(scalar, width).let { put(it.toString(), it) }
            }
        }
        private val scalarStorageTypes = setOf(gpuInt, gpuUInt, gpuFloat)
        private val cpuOnlyTokens = setOf(
            TokenType.INLINE, TokenType.DEEPINLINE, TokenType.MACRO, TokenType.ASYNC, TokenType.AWAIT,
            TokenType.DELAY, TokenType.BRIDGE, TokenType.ALLOC, TokenType.PURGE, TokenType.SCOPE,
        )
        private fun interfaceType(type: GpuType): Boolean =
            type is GpuType.Numeric && type.scalar in setOf(GpuScalar.INT, GpuScalar.UINT, GpuScalar.FLOAT)
    }
}
