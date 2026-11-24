/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.gpu

import org.azora.lang.backend.*
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
    /** Every entry point a Metal library holds (one for the other targets). */
    val entries: List<String> = listOf(entry),
)

enum class GpuStage(val annotation: String, val glslStage: String, val shaderModelPrefix: String) {
    VERTEX("Vertex", "vert", "vs"),
    FRAGMENT("Fragment", "frag", "ps"),
    COMPUTE("Compute", "comp", "cs"),
}

object GpuCompiler {
    /**
     * Compiles [entry] (or the only entry point) of [source]. Imports other than `gpustdlib.*` name
     * the `@Execution("GPU")` module header of an Azora file found under [file]'s directory, [importPaths], or the
     * folders listed in AZORA_GPU_PATH (separated by ':').
     */
    fun compile(source: String, file: String, target: GpuTarget, entry: String? = null, importPaths: List<String> = emptyList()): GpuShaderSource {
        val program = GpuProgram(source, file, importPaths)
        val selected = program.selectEntry(entry)
        check(program)
        return GpuEmitter(program, target).emit(listOf(selected))
    }

    /**
     * Every entry point of [source]: for Metal one library holding them all, for SPIR-V and
     * DXIL one module per entry point (those formats hold a single stage each).
     */
    fun compileAll(source: String, file: String, target: GpuTarget, importPaths: List<String> = emptyList()): List<GpuShaderSource> {
        val program = GpuProgram(source, file, importPaths)
        val entries = program.entries()
        if (entries.isEmpty()) GpuSite(file, 1).fail("add a @Vertex, @Fragment or @Compute entry point")
        check(program)
        return if (target == GpuTarget.METAL) listOf(GpuEmitter(program, target).emit(entries))
        else entries.map { GpuEmitter(program, target).emit(listOf(it)) }
    }

    fun check(source: String, file: String, importPaths: List<String> = emptyList()) {
        check(GpuProgram(source, file, importPaths))
    }

    private fun check(program: GpuProgram) {
        GpuEmitter(program, GpuTarget.METAL).check()
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

/** The shapes a texture comes in, and how a coordinate addresses one. */
internal enum class GpuTextureShape(val coordinates: Int, val metal: String, val glsl: String, val hlsl: String) {
    PLANE(2, "texture2d", "2D", "Texture2D"),
    VOLUME(3, "texture3d", "3D", "Texture3D"),
    CUBE(3, "texturecube", "Cube", "TextureCube"),
    LAYERS(2, "texture2d_array", "2DArray", "Texture2DArray"),
}

/** What a shader may do with a texture: sample it (colour or depth), write it, or read and write it. */
internal enum class GpuTextureAccess { SAMPLE, DEPTH, WRITE, READ_WRITE }

internal sealed interface GpuType {
    data class Numeric(val scalar: GpuScalar, val width: Int = 1) : GpuType {
        override fun toString(): String = scalar.sourceName + if (width > 1) width.toString() else ""
    }
    /** A square Float matrix of [size] columns, each a Float[size] (column-major on every target). */
    data class Matrix(val size: Int) : GpuType {
        val column: Numeric get() = Numeric(GpuScalar.FLOAT, size)
        override fun toString(): String = "Float${size}x$size"
    }
    /** `Array<Element, Size>`: a fixed number of values, laid out in a row. */
    data class FixedArray(val element: GpuType, val size: Int) : GpuType {
        override fun toString(): String = "Array<$element, $size>"
    }
    data class Texture(val name: String, val shape: GpuTextureShape, val access: GpuTextureAccess) : GpuType {
        val writable: Boolean get() = access == GpuTextureAccess.WRITE || access == GpuTextureAccess.READ_WRITE
        val depth: Boolean get() = access == GpuTextureAccess.DEPTH
        override fun toString(): String = name
    }
    data class Sampler(val comparison: Boolean) : GpuType {
        override fun toString(): String = if (comparison) "CompareSampler" else "Sampler"
    }
    /** An Int or UInt in a storage buffer or workgroup array changed only by atomic operations. */
    data class Atomic(val scalar: GpuScalar) : GpuType {
        val value: Numeric get() = Numeric(scalar)
        override fun toString(): String = "Atomic${scalar.sourceName}"
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

/**
 * A function parameter. [resource] says how a value that is not copied reaches it - a buffer, a
 * uniform block, a texture, a sampler, a workgroup array - and is null for plain values.
 */
internal data class GpuParameter(val name: String, val type: GpuType, val port: GpuPort?) {
    val resource: GpuResource? get() = when (port) {
        is GpuPort.Storage -> when {
            port.writable -> GpuResource.STORAGE
            port.constant -> GpuResource.CONSTANT
            else -> GpuResource.READ_STORAGE
        }
        is GpuPort.Uniform -> GpuResource.UNIFORM
        is GpuPort.Texture -> GpuResource.TEXTURE
        is GpuPort.SamplerSlot -> GpuResource.SAMPLER
        GpuPort.Workgroup -> GpuResource.WORKGROUP
        else -> when (type) {
            is GpuType.Texture -> GpuResource.TEXTURE
            is GpuType.Sampler -> GpuResource.SAMPLER
            else -> null
        }
    }
}

/** How a resource is reached: never copied, always passed on by reference (Metal) or bound globally (SPIR-V, DXIL). */
internal enum class GpuResource { STORAGE, READ_STORAGE, CONSTANT, UNIFORM, TEXTURE, SAMPLER, WORKGROUP }

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

/** A sampler declared once at the top level: `fin linear = Sampler(.Linear, .Repeat, .Linear)`. */
internal data class GpuStaticSampler(
    val name: String,
    val comparison: Boolean,
    val filter: String,
    val address: String,
    val mip: String,
    val compare: String,
    val slot: Int,
)

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
    /**
     * A storage buffer ([binding] on an entry point, none on a function it is passed to).
     * [constant]: `@Constant`, a small read-only buffer Metal keeps in its constant address space.
     */
    data class Storage(val binding: Int?, val writable: Boolean, val constant: Boolean = false) : GpuPort
    data class Uniform(val binding: Int?) : GpuPort
    /** A texture; [format] is the texel format a written texture declares for SPIR-V. */
    data class Texture(val binding: Int?, val format: String) : GpuPort
    data class SamplerSlot(val binding: Int?) : GpuPort
    /** Memory shared by a compute workgroup (an entry point's parameter, passed on to functions). */
    data object Workgroup : GpuPort
    /** A fragment entry's input pack: its fields carry the vertex stage's outputs. */
    data object StageInput : GpuPort
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
        FRONT_FACING("FrontFacing", GpuStage.FRAGMENT, gpuBool, "gl_FrontFacing", "SV_IsFrontFace", "front_facing"),
    }
}

/** Texel formats a written texture may declare (Metal and DXIL ignore them; SPIR-V images need one). */
internal val gpuTextureFormats = mapOf(
    "Rgba32Float" to "rgba32f", "Rgba16Float" to "rgba16f", "Rgba8Unorm" to "rgba8",
    "Rg32Float" to "rg32f", "Rg16Float" to "rg16f", "Rg8Unorm" to "rg8", "R32Float" to "r32f", "R16Float" to "r16f",
)

internal class GpuProgram(source: String, private val file: String, private val importPaths: List<String> = emptyList()) {
    private val root = GpuSite(file, 1)
    private val units = linkedMapOf(file to parse(source, file))
    private val aggregateDeclarations = linkedMapOf<String, Pair<TopLevel, GpuSite>>()
    val aggregates = linkedMapOf<String, GpuAggregate>()
    val functions = linkedMapOf<String, GpuFunction>()
    val constants = linkedMapOf<String, GpuConstant>()
    val samplers = linkedMapOf<String, GpuStaticSampler>()
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
            is TopLevel.FinDecl -> registerConstant(item, path)
            is TopLevel.InlineFin -> registerConstant(
                TopLevel.FinDecl(item.name, item.type, item.initializer, item.line, item.column), path,
            )
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

    private fun registerConstant(item: TopLevel.FinDecl, path: String) {
        val site = GpuSite(path, item.line)
        site.require(!item.threadlocal && item.annotations.isEmpty(), "unsupported global fin modifier")
        site.require(item.name !in constants && item.name !in samplers && !isType(item.name),
            "duplicate declaration '${item.name}'")
        val sampler = staticSampler(item, site)
        if (sampler != null) samplers[item.name] = sampler
        else constants[item.name] = GpuConstant(item, path)
    }

    private fun loadImports(path: String, visiting: MutableSet<String>) {
        val unit = units.getValue(path)
        visiting.add(path)
        for (item in unit.items.filterIsInstance<TopLevel.UseImport>()) {
            val site = GpuSite(path, item.line)
            site.require(!item.exported && item.condition == null, "unsupported import modifier")
            for (spec in item.importSpecs) {
                site.require(spec.alias == null && spec.without.isEmpty(), "import a GPU module by its full name")
                val bundled = GpuStdlibBundle.modules[spec.path]
                val libraryPath = if (bundled != null) spec.path.replace('.', '/') + ".az"
                    else localModules()[spec.path] ?: site.fail("unknown GPU module '${spec.path}'")
                site.require(libraryPath !in visiting, "cyclic import '${spec.path}'")
                if (libraryPath !in units) {
                    val text = bundled ?: fsReadText(libraryPath).value ?: site.fail("cannot read '$libraryPath'")
                    val library = parse(text, libraryPath)
                    site.require(library.moduleName == spec.path, "module '${spec.path}' has a mismatched header")
                    units[libraryPath] = library
                    loadImports(libraryPath, visiting)
                }
            }
        }
        visiting.remove(path)
    }

    private var modules: Map<String, String>? = null

    /** The GPU source files under the search folders, by the module their header declares. */
    private fun localModules(): Map<String, String> = modules ?: buildMap {
        val roots = buildList {
            add(file.substringBeforeLast('/', "."))
            addAll(importPaths)
            osEnvVar("AZORA_GPU_PATH")?.split(':')?.filter { it.isNotBlank() }?.let(::addAll)
        }.distinct()
        val header = Regex("""^\s*module\s+([A-Za-z_][A-Za-z0-9_.]*)""", RegexOption.MULTILINE)
        fun visit(directory: String, depth: Int) {
            if (depth > 8) return
            for (entry in fsList(directory).value.orEmpty()) {
                val name = entry.substringAfterLast('/')
                if (name.startsWith('.') || name == "build") continue
                val kind = fsStat(entry).value?.kind
                if (kind == "Directory") visit(entry, depth + 1)
                else if ((name.endsWith(".az") || name.endsWith(".azgpu")) && entry != file) {
                    val source = fsReadText(entry).value ?: continue
                    val module = if (name.endsWith(".azgpu")) header.find(source)?.groupValues?.get(1)
                        else GpuModule.moduleName(source)
                    if (module == null) continue
                    val known = get(module)
                    if (known != null && known != entry && fsCanonical(known).value != fsCanonical(entry).value) {
                        root.fail("GPU module '$module' is declared by both '$known' and '$entry'")
                    }
                    if (known == null) put(module, entry)
                }
            }
        }
        for (root in roots) visit(root, 0)
    }.also { modules = it }

    private fun declareType(name: String, item: TopLevel, path: String, line: Int) {
        val site = GpuSite(path, line)
        site.require(!isType(name), "duplicate type '$name'")
        aggregateDeclarations[name] = item to site
    }

    fun type(ref: TypeRef, site: GpuSite, allowUnit: Boolean = false): GpuType {
        val named = ref as? TypeRef.Named ?: site.fail("GPU types must be concrete value types")
        site.require(!named.variadic && named.qualifier == null && named.valueArgs.isEmpty(),
            "GPU types cannot have qualifiers")
        if (named.name == "Array") {
            // Array<Element, Size>: a fixed array.
            site.require(named.args.size == 2, "a GPU array is Array<Element, Size>")
            val element = type(named.args[0], site)
            val size = (named.args[1] as? TypeRef.Const)?.value ?: site.fail("a GPU array's size is an integer literal")
            site.require(size in 1..65536, "a GPU array holds 1 to 65536 values")
            site.require(element !is GpuType.FixedArray && element !is GpuType.Texture && element !is GpuType.Sampler,
                "a GPU array holds values, not arrays or resources")
            return GpuType.FixedArray(element, size.toInt())
        }
        site.require(named.args.isEmpty(), "GPU types cannot have generic arguments")
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
                val type = type(field.type, site)
                site.require(valueType(type), "field '${field.name}' must hold a value, not a resource")
                GpuField(field.name, type, field.mutable, port(field.annotations, site))
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
                GpuCase(case.name, index, case.payloadTypes.map { type(it, site).also { payload ->
                    site.require(payload is GpuType.Numeric || payload is GpuType.Matrix || payload is GpuType.Named,
                        "a variant payload holds a scalar, vector, matrix or pack")
                } })
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
        site.require(owner != null || decl.name !in gpuIntrinsicNames, "'${decl.name}' is a GPU built-in function")
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
            val type = type(parameter.type, site)
            var port = port(parameter.annotations, site)
            // A fragment entry may take the vertex stage's whole output pack.
            if (port == null && stage == GpuStage.FRAGMENT && type is GpuType.Named &&
                aggregates[type.name]?.let { !it.tagged && !it.plainEnum && it.fields.any { field -> field.port != null } } == true) port = GpuPort.StageInput
            GpuParameter(parameter.name, type, port)
        }
        val returnRef = (decl.returnType as? TypeAnnotation.Explicit)?.ref
        val result = returnRef?.let { type(it, site, allowUnit = true) } ?: gpuUnit
        site.require(result == gpuUnit || valueType(result) && result !is GpuType.FixedArray,
            "a GPU function returns a scalar, vector, matrix, pack or enum")
        val resultPort = when (stage) {
            GpuStage.VERTEX -> GpuPort.Builtin.POSITION.takeIf { result == gpuFloat4 }
            GpuStage.FRAGMENT -> GpuPort.Location(0).takeIf { result == gpuFloat4 }
            else -> null
        }
        val function = GpuFunction(decl, path, owner, parameters, result, stage, workgroup, resultPort)
        if (stage == null) validateHelper(function)
        else {
            site.require(externalEntryName.matches(decl.name), "GPU entry points require an ASCII identifier")
            validateInterface(function)
        }
        site.require(function.displayName !in functions && (owner != null || !isType(decl.name)),
            "duplicate function '${function.displayName}'")
        functions[function.displayName] = function
    }

    private fun validateHelper(function: GpuFunction) {
        val site = function.site
        for (parameter in function.parameters) {
            when (val port = parameter.port) {
                null -> site.require(valueType(parameter.type) && parameter.type !is GpuType.FixedArray ||
                    parameter.type is GpuType.Texture || parameter.type is GpuType.Sampler,
                    "parameter '${parameter.name}' cannot take ${parameter.type}")
                is GpuPort.Storage -> site.require(port.binding == null && storageElement(parameter.type, port.writable),
                    "a function's @Storage parameter names no binding and holds a storable element")
                is GpuPort.Uniform -> site.require(port.binding == null && uniformType(parameter.type),
                    "a function's @Uniform parameter names no binding and holds a pack, matrix or number")
                GpuPort.Workgroup -> site.require(parameter.type is GpuType.FixedArray, "@Workgroup holds an Array")
                else -> site.fail("only entry points take bound or built-in GPU inputs ('${parameter.name}')")
            }
        }
    }

    private fun validateInterface(function: GpuFunction) {
        val site = function.site
        val stage = function.stage!!
        val locations = mutableSetOf<Int>()
        val buffers = mutableSetOf<Int>()
        val textures = mutableSetOf<Int>()
        val samplerSlots = mutableSetOf<Int>()
        val builtins = mutableSetOf<GpuPort.Builtin>()
        for (parameter in function.parameters) {
            when (val port = parameter.port) {
                null -> site.fail("entry parameter '${parameter.name}' needs a GPU interface attribute")
                is GpuPort.Builtin -> site.require(port.stage == stage && parameter.type == port.type && builtins.add(port),
                    "@${port.annotation} requires ${port.type} and is unique to ${port.stage.annotation} inputs")
                is GpuPort.Location -> site.require(stage != GpuStage.COMPUTE && interfaceType(parameter.type) &&
                    locations.add(port.index), "input @Location requires a unique graphics location and a numeric scalar or vector")
                is GpuPort.Storage -> site.require(port.binding != null && storageElement(parameter.type, port.writable) &&
                    buffers.add(port.binding), "storage requires a unique buffer binding and a storable element")
                is GpuPort.Uniform -> site.require(port.binding != null && uniformType(parameter.type) &&
                    buffers.add(port.binding), "@Uniform requires a unique buffer binding and a pack, matrix or number")
                is GpuPort.Texture -> site.require(port.binding != null && parameter.type is GpuType.Texture &&
                    textures.add(port.binding), "@Texture requires a unique texture binding and a texture type")
                is GpuPort.SamplerSlot -> site.require(port.binding != null && parameter.type is GpuType.Sampler &&
                    samplerSlots.add(port.binding), "@Sampler requires a unique sampler binding and a sampler type")
                GpuPort.Workgroup -> site.require(stage == GpuStage.COMPUTE && parameter.type is GpuType.FixedArray,
                    "@Workgroup arrays belong to compute entry points")
                GpuPort.StageInput -> {
                    val pack = aggregates.getValue((parameter.type as GpuType.Named).name)
                    site.require(pack.fields.isNotEmpty(), "the fragment input pack needs fields")
                    for (field in pack.fields) when (val fieldPort = field.port) {
                        is GpuPort.Location -> site.require(interfaceType(field.type) && locations.add(fieldPort.index),
                            "fragment input field '${field.name}' needs a unique @Location and a numeric scalar or vector")
                        GpuPort.Builtin.POSITION -> site.require(field.type == gpuFloat4 && builtins.add(GpuPort.Builtin.POSITION),
                            "the fragment's @Position is a single Float4")
                        else -> site.fail("fragment input field '${field.name}' needs @Location or @Position")
                    }
                }
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

    fun entries(): List<GpuFunction> = functions.values.filter { it.stage != null }

    fun selectEntry(entry: String?): GpuFunction {
        val entries = entries()
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
            val execution = program.moduleAnnotations.singleOrNull()
            val gpu = execution?.name == "Execution" && execution.qualifier == null &&
                execution.namedArgs.isEmpty() && (execution.args.singleOrNull() as? Expr.StringLiteral)?.value == "GPU"
            site.require(!path.endsWith(".az") || gpu && program.moduleName != null,
                "GPU .az source requires @Execution(\"GPU\") on its module")
            site.require(!program.usesMacros && program.typeFunctions.isEmpty() && program.typeMacroRules.isEmpty() &&
                program.scopes.isEmpty() && program.scopeTypeNamespaces.isEmpty() && (gpu || program.moduleAnnotations.isEmpty()) &&
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
            "Storage", "ReadOnlyStorage", "Constant" ->
                GpuPort.Storage(binding(attribute, site), attribute.name == "Storage", attribute.name == "Constant")
            "Uniform" -> GpuPort.Uniform(binding(attribute, site))
            "Sampler" -> GpuPort.SamplerSlot(binding(attribute, site))
            "Workgroup" -> {
                integers(attribute, 0, site)
                GpuPort.Workgroup
            }
            "Texture" -> {
                site.require(attribute.args.size in 1..2, "@Texture takes a binding and an optional texel format")
                val binding = integers(attribute.copy(args = attribute.args.take(1)), 1, site).single()
                val format = attribute.args.getOrNull(1)?.let { argument ->
                    val name = (argument as? Expr.Identifier)?.name ?: (argument as? Expr.InferredMember)?.name
                    gpuTextureFormats[name] ?: site.fail("unknown texel format; use ${gpuTextureFormats.keys.joinToString()}")
                } ?: "rgba32f"
                GpuPort.Texture(binding, format)
            }
            else -> {
                val builtin = GpuPort.Builtin.entries.firstOrNull { it.annotation == attribute.name }
                    ?: site.fail("unknown GPU interface attribute @${attribute.name}")
                integers(attribute, 0, site)
                builtin
            }
        }
    }

    private fun binding(attribute: Annotation, site: GpuSite): Int? {
        site.require(attribute.args.size <= 1, "@${attribute.name} takes at most a binding number")
        return if (attribute.args.isEmpty()) null else integers(attribute, 1, site).single()
    }

    /** `fin name = Sampler(.Linear, .Repeat[, .Linear])` or `CompareSampler(.Linear, .ClampToEdge, .Less)`. */
    private fun staticSampler(item: TopLevel.FinDecl, site: GpuSite): GpuStaticSampler? {
        val call = item.initializer as? Expr.Call ?: return null
        if (call.callee != "Sampler" && call.callee != "CompareSampler") return null
        val comparison = call.callee == "CompareSampler"
        val names = call.args.map { (it as? Expr.InferredMember)?.takeIf { member -> member.ctorArgs == null }?.name
            ?: site.fail("${call.callee} takes cases such as .Linear") }
        site.require(names.size in 2..3, "${call.callee} takes a filter, an address mode and ${if (comparison) "a comparison" else "an optional mip filter"}")
        val filter = names[0].also { site.require(it in setOf("Nearest", "Linear"), "the filter is .Nearest or .Linear") }
        val address = names[1].also {
            site.require(it in setOf("Repeat", "ClampToEdge", "MirroredRepeat", "ClampToZero"),
                "the address mode is .Repeat, .ClampToEdge, .MirroredRepeat or .ClampToZero")
        }
        val third = names.getOrNull(2)
        val mip = if (comparison) "None" else (third ?: "None").also {
            site.require(it in setOf("None", "Nearest", "Linear"), "the mip filter is .None, .Nearest or .Linear")
        }
        val compare = if (!comparison) "Never" else (third ?: site.fail("CompareSampler needs a comparison")).also {
            site.require(it in setOf("Less", "LessEqual", "Greater", "GreaterEqual", "Equal", "Always", "Never"),
                "the comparison is .Less, .LessEqual, .Greater, .GreaterEqual, .Equal, .Always or .Never")
        }
        return GpuStaticSampler(item.name, comparison, filter, address, mip, compare, samplers.size)
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

    /** A value that can be copied, stored in a local or a field: no resources, no atomics. */
    fun valueType(type: GpuType): Boolean = when (type) {
        is GpuType.Numeric, is GpuType.Matrix, is GpuType.Named -> true
        is GpuType.FixedArray -> valueType(type.element)
        else -> false
    }

    private fun packType(type: GpuType): Boolean =
        type is GpuType.Named && aggregates[type.name]?.let { !it.tagged && !it.plainEnum } == true

    private fun uniformType(type: GpuType): Boolean = packType(type) || type is GpuType.Matrix ||
        type is GpuType.Numeric && type.scalar != GpuScalar.BOOL && type.scalar != GpuScalar.UNIT

    private fun storageElement(type: GpuType, writable: Boolean): Boolean = when (type) {
        is GpuType.Numeric -> type.scalar != GpuScalar.BOOL && type.scalar != GpuScalar.UNIT
        is GpuType.Matrix -> true
        is GpuType.Atomic -> writable
        is GpuType.Named -> packType(type)
        else -> false
    }

    companion object {
        private val primitiveTypes: Map<String, GpuType> = buildMap {
            for (scalar in GpuScalar.entries) {
                val widths = if (scalar == GpuScalar.UNIT) 1..1 else 1..4
                for (width in widths) GpuType.Numeric(scalar, width).let { put(it.toString(), it) }
            }
            for (size in 2..4) GpuType.Matrix(size).let { put(it.toString(), it) }
            for ((name, shape, access) in listOf(
                Triple("Texture2D", GpuTextureShape.PLANE, GpuTextureAccess.SAMPLE),
                Triple("Texture3D", GpuTextureShape.VOLUME, GpuTextureAccess.SAMPLE),
                Triple("TextureCube", GpuTextureShape.CUBE, GpuTextureAccess.SAMPLE),
                Triple("Texture2DArray", GpuTextureShape.LAYERS, GpuTextureAccess.SAMPLE),
                Triple("DepthTexture2D", GpuTextureShape.PLANE, GpuTextureAccess.DEPTH),
                Triple("DepthTexture2DArray", GpuTextureShape.LAYERS, GpuTextureAccess.DEPTH),
                Triple("WriteTexture2D", GpuTextureShape.PLANE, GpuTextureAccess.WRITE),
                Triple("WriteTexture3D", GpuTextureShape.VOLUME, GpuTextureAccess.WRITE),
                Triple("ReadWriteTexture2D", GpuTextureShape.PLANE, GpuTextureAccess.READ_WRITE),
                Triple("ReadWriteTexture3D", GpuTextureShape.VOLUME, GpuTextureAccess.READ_WRITE),
            )) put(name, GpuType.Texture(name, shape, access))
            put("Sampler", GpuType.Sampler(false))
            put("CompareSampler", GpuType.Sampler(true))
            put("AtomicUInt", GpuType.Atomic(GpuScalar.UINT))
            put("AtomicInt", GpuType.Atomic(GpuScalar.INT))
        }
        private val cpuOnlyTokens = setOf(
            TokenType.DEEPINLINE, TokenType.MACRO, TokenType.ASYNC, TokenType.AWAIT,
            TokenType.DELAY, TokenType.BRIDGE, TokenType.ALLOC, TokenType.PURGE, TokenType.SCOPE,
        )
        private fun interfaceType(type: GpuType): Boolean =
            type is GpuType.Numeric && type.scalar in setOf(GpuScalar.INT, GpuScalar.UINT, GpuScalar.FLOAT)
    }
}
