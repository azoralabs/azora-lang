/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.gpu

/**
 * Renders a [GpuProgram] in one shader dialect.
 *
 * Bindings: Metal uses `[[buffer(n)]]`, `[[texture(n)]]` and `[[sampler(n)]]` as written. SPIR-V puts
 * buffers (uniform and storage) in descriptor set 0, textures in set 1, samplers in set 2 and
 * top-level `fin` samplers in set 3 (binding = declaration order). DXIL uses `b`/`t`/`u` registers
 * in space 0 for buffers, `t`/`u` in space 1 for textures, `s` in space 0 for samplers and space 3
 * for top-level samplers.
 */
internal class GpuEmitter(val program: GpuProgram, val target: GpuTarget) {
    val constants = linkedMapOf<String, GpuValue>()

    private data class InstanceKey(val function: GpuFunction, val resources: List<String?>)
    private val instances = linkedMapOf<InstanceKey, String>()
    private val active = mutableSetOf<InstanceKey>()
    private val bodies = mutableListOf<String>()
    private val variants = mutableMapOf<GpuFunction, Int>()
    private val helpers = linkedMapOf<String, String>()
    private var constantSource = ""

    fun emit(entries: List<GpuFunction>): GpuShaderSource {
        constantSource = emitConstants()
        val wrappers = StringBuilder()
        val globals = StringBuilder()
        for (entry in entries) {
            val bound = entry.parameters.mapIndexed { index, parameter ->
                if (target == GpuTarget.METAL || parameter.resource == null) null else globals.global(parameter, index)
            }
            val symbol = instance(entry, bound)
            wrappers.appendEntry(entry, symbol, multiple = entries.size > 1)
        }
        val single = entries.singleOrNull()
        val source = buildString {
            when (target) {
                GpuTarget.METAL -> appendLine("#include <metal_stdlib>\nusing namespace metal;")
                GpuTarget.VULKAN -> appendLine("#version 450\n#extension GL_EXT_samplerless_texture_functions : require")
                GpuTarget.DIRECTX -> appendLine("#pragma pack_matrix(row_major)")
            }
            aggregateOrder().filterNot { it.plainEnum }.forEach { appendAggregate(it) }
            append(constantSource)
            appendSamplers()
            append(globals)
            helpers.values.forEach { append(it) }
            bodies.forEach { append(it) }
            append(wrappers)
        }
        val first = entries.first()
        return GpuShaderSource(
            source, first.decl.name, checkNotNull(first.stage),
            backendEntry = when {
                single == null -> first.decl.name
                target == GpuTarget.DIRECTX -> "az_entry"
                else -> single.decl.name
            },
            entries = entries.map { it.decl.name },
        )
    }

    /** Type-checks every declaration of the program, used or not. */
    fun check() {
        aggregateOrder()
        constantSource = emitConstants()
        for (function in program.functions.values) instance(function, emptyList())
    }

    private fun aggregateOrder(): List<GpuAggregate> = dependencyOrder(program.aggregates.values, { aggregate ->
        aggregate.dependencies.mapNotNull { (it as? GpuType.Named) ?: ((it as? GpuType.FixedArray)?.element as? GpuType.Named) }
            .map { program.aggregates.getValue(it.name) }
    }) { it.site.fail("recursive GPU type '${it.name}' has no finite layout") }

    /**
     * The symbol of [function] rendered with [resources] bound to its resource parameters (SPIR-V and
     * DXIL only; Metal passes resources as arguments, so it renders each function once).
     */
    fun instance(function: GpuFunction, resources: List<GpuValue?>): String {
        val key = InstanceKey(function, if (target == GpuTarget.METAL) emptyList() else resources.map { it?.code })
        instances[key]?.let { return it }
        if (key in active) function.site.fail("recursive GPU function '${function.displayName}'")
        active += key
        val variant = variants.getOrPut(function) { 0 }
        variants[function] = variant + 1
        val symbol = if (variant == 0) function.symbol else "${function.symbol}_v$variant"
        val body = GpuBody(this, function, resources = resources, symbol = symbol).emit()
        active -= key
        instances[key] = symbol
        bodies += body
        return symbol
    }

    // ── Types and values ──

    fun type(type: GpuType): String = when (type) {
        is GpuType.Named -> if (program.aggregates.getValue(type.name).plainEnum) "uint" else "az_t_${gpuName(type.name)}"
        is GpuType.Numeric -> if (type.width == 1) type.scalar.shaderName else when (target) {
            GpuTarget.VULKAN -> when (type.scalar) {
                GpuScalar.FLOAT -> "vec"
                GpuScalar.INT -> "ivec"
                GpuScalar.UINT -> "uvec"
                GpuScalar.BOOL -> "bvec"
                else -> error("invalid vector type")
            } + type.width
            else -> type.scalar.shaderName + type.width
        }
        is GpuType.Matrix -> if (target == GpuTarget.VULKAN) "mat${type.size}" else "float${type.size}x${type.size}"
        is GpuType.FixedArray -> type(type.element)
        is GpuType.Atomic -> when (target) {
            GpuTarget.METAL -> "atomic_${type.scalar.shaderName}"
            else -> type.scalar.shaderName
        }
        is GpuType.Texture -> textureType(type, null)
        is GpuType.Sampler -> when (target) {
            GpuTarget.METAL -> "sampler"
            GpuTarget.VULKAN -> if (type.comparison) "samplerShadow" else "sampler"
            GpuTarget.DIRECTX -> if (type.comparison) "SamplerComparisonState" else "SamplerState"
        }
    }

    private fun textureType(type: GpuType.Texture, format: String?): String = when (target) {
        GpuTarget.METAL -> {
            val base = if (type.depth) type.shape.metal.replace("texture", "depth") else type.shape.metal
            when (type.access) {
                GpuTextureAccess.WRITE -> "$base<float, access::write>"
                GpuTextureAccess.READ_WRITE -> "$base<float, access::read_write>"
                else -> "$base<float>"
            }
        }
        GpuTarget.VULKAN -> when (type.access) {
            GpuTextureAccess.WRITE -> "writeonly image${type.shape.glsl}"
            GpuTextureAccess.READ_WRITE -> "image${type.shape.glsl}"
            else -> "texture${type.shape.glsl}"
        }.let { if (format != null && type.writable) "layout($format) $it".replace("layout($format) writeonly", "writeonly") else it }
        GpuTarget.DIRECTX -> {
            val texel = if (type.depth) "float" else "float4"
            "${if (type.writable) "RW" else ""}${type.shape.hlsl}<$texel>"
        }
    }

    /** `float name[4]` for arrays, `float3 name` otherwise. */
    fun declaration(type: GpuType, name: String): String =
        if (type is GpuType.FixedArray) "${type(type.element)} $name[${type.size}]" else "${type(type)} $name"

    fun arrayLiteral(type: GpuType.FixedArray, elements: List<String>): String =
        if (target == GpuTarget.VULKAN) "${type(type.element)}[${type.size}](${elements.joinToString()})"
        else "{${elements.joinToString()}}"

    fun constructor(type: GpuType, arguments: List<String>): String {
        val name = if (type is GpuType.Named) "az_make_${gpuName(type.name)}" else type(type)
        return "$name(${arguments.joinToString()})"
    }

    fun field(name: String) = "az_field_${gpuName(name)}"

    fun caseConstructor(aggregate: GpuAggregate, case: GpuCase, args: List<String>): String {
        if (!aggregate.tagged) return "${case.index}u"
        return "az_case_${gpuName(aggregate.name)}_${case.index}(${args.joinToString()})"
    }

    fun zero(type: GpuType): String = when (type) {
        is GpuType.Named -> {
            val aggregate = program.aggregates.getValue(type.name)
            when {
                aggregate.tagged -> caseConstructor(aggregate, aggregate.cases.first(), aggregate.cases.first().payload.map(::zero))
                aggregate.plainEnum -> "0u"
                else -> constructor(type, aggregate.fields.map { zero(it.type) })
            }
        }
        is GpuType.Numeric -> {
            val scalar = when (type.scalar) {
                GpuScalar.FLOAT -> "0.0f"
                GpuScalar.UINT -> "0u"
                GpuScalar.INT -> "0"
                GpuScalar.BOOL -> "false"
                GpuScalar.UNIT -> error("cannot zero Unit")
            }
            if (type.width == 1) scalar else constructor(type, List(type.width) { scalar })
        }
        is GpuType.Matrix -> constructor(type, List(type.size) { zero(type.column) })
        is GpuType.FixedArray -> arrayLiteral(type, List(type.size) { zero(type.element) })
        else -> error("cannot zero $type")
    }

    fun parameter(index: Int) = "az_p_$index"

    /** A function's signature; Metal lists resources as reference parameters, SPIR-V and DXIL leave them out. */
    fun signature(function: GpuFunction, symbol: String): String {
        val parameters = buildList {
            if (function.receiver) {
                val receiverType = type(function.owner!!.type)
                add(when {
                    !function.mutableReceiver -> "$receiverType az_self"
                    target == GpuTarget.METAL -> "thread $receiverType& az_self"
                    else -> "inout $receiverType az_self"
                })
            }
            function.parameters.forEachIndexed { index, parameter ->
                val resource = parameter.resource
                when {
                    resource == null -> add(declaration(parameter.type, parameter(index)))
                    target == GpuTarget.METAL -> add("${metalResource(parameter, resource)} ${parameter(index)}")
                }
            }
        }
        return "${type(function.result)} $symbol(${parameters.joinToString()})"
    }

    private fun metalResource(parameter: GpuParameter, resource: GpuResource): String = when (resource) {
        GpuResource.STORAGE -> "device ${type(parameter.type)}*"
        GpuResource.READ_STORAGE -> "device const ${type(parameter.type)}*"
        GpuResource.CONSTANT -> "constant ${type(parameter.type)}*"
        GpuResource.UNIFORM -> "constant ${type(parameter.type)}&"
        GpuResource.TEXTURE -> textureType(parameter.type as GpuType.Texture, null)
        GpuResource.SAMPLER -> "sampler"
        GpuResource.WORKGROUP -> "threadgroup ${type((parameter.type as GpuType.FixedArray).element)}*"
    }

    /** Emits a helper once (fmod for SPIR-V) and answers its name. */
    fun helper(name: String, type: GpuType.Numeric): String {
        val spelled = type(type)
        val symbol = "az_${name}_$spelled"
        helpers.getOrPut(symbol) {
            when (name) {
                "fmod" -> "$spelled $symbol($spelled a, $spelled b) { return a - b * trunc(a / b); }\n"
                else -> error("unknown helper $name")
            }
        }
        return symbol
    }

    /** DXIL answers a texture's size through out parameters: one small function per texture type. */
    fun sizeHelper(type: GpuType.Texture): String {
        val spelled = textureType(type, null)
        val symbol = "az_size_${type.name}"
        helpers.getOrPut(symbol) {
            when (type.shape) {
                GpuTextureShape.VOLUME -> "uint3 $symbol($spelled t) { uint w, h, d; t.GetDimensions(w, h, d); return uint3(w, h, d); }\n"
                GpuTextureShape.LAYERS -> "uint2 $symbol($spelled t) { uint w, h, n; t.GetDimensions(w, h, n); return uint2(w, h); }\n"
                else -> "uint2 $symbol($spelled t) { uint w, h; t.GetDimensions(w, h); return uint2(w, h); }\n"
            }
        }
        return symbol
    }

    // ── Declarations ──

    private fun StringBuilder.appendAggregate(aggregate: GpuAggregate) {
        val fields = if (!aggregate.tagged) {
            aggregate.fields.map { field(it.name) to it.type }.ifEmpty { listOf("az_empty" to gpuUInt) }
        } else {
            listOf("az_tag" to gpuUInt) + aggregate.cases.flatMap { case ->
                case.payload.mapIndexed { index, type -> case.field(index) to type }
            }
        }
        val name = type(aggregate.type)
        appendLine("struct $name {")
        for ((field, type) in fields) appendLine("    ${declaration(type, field)};")
        appendLine("};")
        // Packs with arrays are read from buffers, not built in shader code.
        if (fields.any { it.second is GpuType.FixedArray }) return
        val signature = fields.mapIndexed { index, (_, type) -> "${type(type)} az_a_$index" }.joinToString()
        val factory = "az_make_${gpuName(aggregate.name)}"
        val empty = !aggregate.tagged && aggregate.fields.isEmpty()
        appendLine("$name $factory(${if (empty) "" else signature}) {")
        appendLine("    $name az_value;")
        for ((index, pair) in fields.withIndex()) {
            val initializer = if (empty) "0u" else "az_a_$index"
            appendLine("    az_value.${pair.first} = $initializer;")
        }
        appendLine("    return az_value;")
        appendLine("}")
        for (case in aggregate.cases) {
            val payload = case.payload.mapIndexed { index, type -> "${type(type)} az_p_$index" }.joinToString()
            val args = listOf("${case.index}u") + aggregate.cases.flatMap { other ->
                other.payload.mapIndexed { index, type -> if (other == case) "az_p_$index" else zero(type) }
            }
            val caseName = "az_case_${gpuName(aggregate.name)}_${case.index}"
            appendLine("$name $caseName($payload) { return $factory(${args.joinToString()}); }")
        }
    }

    private fun emitConstants(): String = buildString {
        constants.clear()
        for ((name, sampler) in program.samplers) {
            constants[name] = GpuValue(GpuType.Sampler(sampler.comparison), "az_s_${gpuName(name)}", resource = GpuResource.SAMPLER)
        }
        for ((name, constant) in program.constants) {
            val decl = constant.decl
            val site = GpuSite(constant.file, decl.line)
            val expected = decl.type?.let { program.type(it, site) }
            val value = GpuBody(this@GpuEmitter, null, constant.file).constant(decl.initializer, expected)
            val symbol = "az_c_${gpuName(name)}"
            val qualifier = when (target) {
                GpuTarget.METAL -> "constant"
                GpuTarget.DIRECTX -> "static const"
                GpuTarget.VULKAN -> "const"
            }
            appendLine("$qualifier ${declaration(value.type, symbol)} = ${value.code};")
            constants[name] = GpuValue(value.type, symbol)
        }
    }

    private fun StringBuilder.appendSamplers() {
        for (sampler in program.samplers.values) {
            val symbol = "az_s_${gpuName(sampler.name)}"
            when (target) {
                GpuTarget.METAL -> {
                    val address = when (sampler.address) {
                        "Repeat" -> "repeat"
                        "ClampToEdge" -> "clamp_to_edge"
                        "MirroredRepeat" -> "mirrored_repeat"
                        else -> "clamp_to_zero"
                    }
                    val options = buildList {
                        add("coord::normalized")
                        add("filter::${sampler.filter.lowercase()}")
                        add("address::$address")
                        if (sampler.mip != "None") add("mip_filter::${sampler.mip.lowercase()}")
                        if (sampler.comparison) add("compare_func::${snake(sampler.compare)}")
                    }
                    appendLine("constexpr sampler $symbol(${options.joinToString()});")
                }
                GpuTarget.VULKAN -> appendLine("layout(set = 3, binding = ${sampler.slot}) uniform ${if (sampler.comparison) "samplerShadow" else "sampler"} $symbol;")
                GpuTarget.DIRECTX -> appendLine("${if (sampler.comparison) "SamplerComparisonState" else "SamplerState"} $symbol : register(s${sampler.slot}, space3);")
            }
        }
    }

    private fun snake(name: String) = name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

    /** Declares entry resource [parameter] as a SPIR-V / DXIL global and answers the value code reaches it through. */
    private fun StringBuilder.global(parameter: GpuParameter, index: Int): GpuValue {
        val resource = parameter.resource!!
        val spelled = type(parameter.type)
        return when (val port = parameter.port) {
            is GpuPort.Storage -> {
                val binding = port.binding!!
                val writable = port.writable
                if (target == GpuTarget.VULKAN) {
                    appendLine("layout(std430, set = 0, binding = $binding) ${if (writable) "" else "readonly "}buffer az_block_$binding {")
                    appendLine("    $spelled az_elements[];")
                    appendLine("} az_buffer_$binding;")
                    GpuValue(parameter.type, "az_buffer_$binding.az_elements", writable, resource)
                } else {
                    val buffer = if (writable) "RWStructuredBuffer" else "StructuredBuffer"
                    appendLine("$buffer<$spelled> az_buffer_$binding : register(${if (writable) "u" else "t"}$binding, space0);")
                    GpuValue(parameter.type, "az_buffer_$binding", writable, resource)
                }
            }
            is GpuPort.Uniform -> {
                val binding = port.binding!!
                if (target == GpuTarget.DIRECTX && parameter.type !is GpuType.Named) {
                    // ConstantBuffer<T> needs a struct: a lone number or matrix is a cbuffer member.
                    appendLine("cbuffer az_cblock_$binding : register(b$binding, space0) { $spelled az_uniform_$binding; };")
                    GpuValue(parameter.type, "az_uniform_$binding", false, resource)
                } else if (target == GpuTarget.VULKAN) {
                    appendLine("layout(std140, set = 0, binding = $binding) uniform az_ublock_$binding { $spelled az_value; } az_uniform_$binding;")
                    GpuValue(parameter.type, "az_uniform_$binding.az_value", false, resource)
                } else {
                    appendLine("ConstantBuffer<$spelled> az_uniform_$binding : register(b$binding, space0);")
                    GpuValue(parameter.type, "az_uniform_$binding", false, resource)
                }
            }
            is GpuPort.Texture -> {
                val binding = port.binding!!
                val texture = parameter.type as GpuType.Texture
                if (target == GpuTarget.VULKAN) {
                    val access = when (texture.access) {
                        GpuTextureAccess.WRITE -> "writeonly "
                        else -> ""
                    }
                    if (texture.writable) appendLine("layout(set = 1, binding = $binding, ${port.format}) uniform ${access}image${texture.shape.glsl} az_texture_$binding;")
                    else appendLine("layout(set = 1, binding = $binding) uniform texture${texture.shape.glsl} az_texture_$binding;")
                } else {
                    appendLine("${textureType(texture, null)} az_texture_$binding : register(${if (texture.writable) "u" else "t"}$binding, space1);")
                }
                GpuValue(parameter.type, "az_texture_$binding", false, resource)
            }
            is GpuPort.SamplerSlot -> {
                val binding = port.binding!!
                if (target == GpuTarget.VULKAN) appendLine("layout(set = 2, binding = $binding) uniform $spelled az_sampler_$binding;")
                else appendLine("$spelled az_sampler_$binding : register(s$binding, space0);")
                GpuValue(parameter.type, "az_sampler_$binding", false, resource)
            }
            GpuPort.Workgroup -> {
                val array = parameter.type as GpuType.FixedArray
                appendLine("${if (target == GpuTarget.VULKAN) "shared" else "groupshared"} ${declaration(array, "az_shared_$index")};")
                GpuValue(parameter.type, "az_shared_$index", true, resource)
            }
            else -> error("not a resource port")
        }
    }

    // ── Entry points ──

    private data class Output(val type: GpuType, val port: GpuPort, val member: String?)
    private data class Input(val name: String, val type: GpuType, val port: GpuPort)

    private fun StringBuilder.appendEntry(entry: GpuFunction, symbol: String, multiple: Boolean) {
        val stage = entry.stage!!
        val tag = if (multiple) "_${entry.decl.name}" else ""
        val entryName = when (target) {
            GpuTarget.DIRECTX -> "az_entry"
            else -> entry.decl.name
        }
        val outputs = when {
            stage == GpuStage.COMPUTE -> emptyList()
            entry.resultPort != null -> listOf(Output(entry.result, entry.resultPort, null))
            else -> program.aggregates.getValue((entry.result as GpuType.Named).name).fields.map {
                Output(it.type, it.port!!, field(it.name))
            }
        }
        // Interpolated inputs: @Location parameters and the fields of a fragment's input pack.
        val inputs = buildList {
            entry.parameters.forEachIndexed { index, parameter ->
                when (val port = parameter.port) {
                    is GpuPort.Location -> add(Input("az_in_$index", parameter.type, port))
                    GpuPort.StageInput -> program.aggregates.getValue((parameter.type as GpuType.Named).name).fields
                        .forEachIndexed { field, it -> add(Input("az_in_${index}_$field", it.type, it.port!!)) }
                    else -> Unit
                }
            }
        }
        val inputStruct = target == GpuTarget.METAL && inputs.isNotEmpty()
        if (inputStruct) {
            appendLine("struct az_stage_in$tag {")
            for (input in inputs) {
                val attr = when (val port = input.port) {
                    is GpuPort.Location -> if (stage == GpuStage.VERTEX) "attribute(${port.index})" else metalVarying(port, input.type)
                    else -> "position"
                }
                appendLine("    ${type(input.type)} ${input.name} [[$attr]];")
            }
            appendLine("};")
        }
        if (target == GpuTarget.VULKAN) {
            for (input in inputs) {
                val port = input.port as? GpuPort.Location ?: continue
                val flat = if (stage == GpuStage.FRAGMENT && isInteger(input.type)) "flat " else ""
                appendLine("layout(location = ${port.index}) ${flat}in ${type(input.type)} ${input.name};")
            }
            outputs.forEachIndexed { index, output ->
                val port = output.port as? GpuPort.Location ?: return@forEachIndexed
                val flat = if (stage == GpuStage.VERTEX && isInteger(output.type)) "flat " else ""
                appendLine("layout(location = ${port.index}) ${flat}out ${type(output.type)} az_out_$index;")
            }
        } else if (outputs.isNotEmpty()) {
            appendLine("struct az_stage_out$tag {")
            outputs.forEachIndexed { index, output ->
                val decoration = when (val port = output.port) {
                    is GpuPort.Location -> when (target) {
                        GpuTarget.METAL -> if (stage == GpuStage.FRAGMENT) "[[color(${port.index})]]"
                            else "[[${metalVarying(port, output.type)}]]"
                        else -> if (stage == GpuStage.FRAGMENT) ": SV_Target${port.index}" else ": TEXCOORD${port.index}"
                    }
                    else -> if (target == GpuTarget.METAL) "[[position]]" else ": SV_Position"
                }
                val flat = if (target == GpuTarget.DIRECTX && stage == GpuStage.VERTEX && isInteger(output.type)) "nointerpolation " else ""
                appendLine("    $flat${type(output.type)} az_out_$index $decoration;")
            }
            appendLine("};")
        }
        val arguments = mutableListOf<String>()
        val declarations = mutableListOf<String>()
        val prelude = mutableListOf<String>()
        if (inputStruct) declarations += "az_stage_in$tag az_inputs [[stage_in]]"
        fun inputCode(input: Input): String = when (target) {
            GpuTarget.METAL -> "az_inputs.${input.name}"
            GpuTarget.VULKAN -> if (input.port == GpuPort.Builtin.POSITION) "gl_FragCoord" else input.name
            GpuTarget.DIRECTX -> input.name.also {
                val semantic = when (val port = input.port) {
                    is GpuPort.Location -> "TEXCOORD${port.index}"
                    else -> "SV_Position"
                }
                val flat = if (stage == GpuStage.FRAGMENT && isInteger(input.type)) "nointerpolation " else ""
                declarations += "$flat${type(input.type)} $it : $semantic"
            }
        }
        entry.parameters.forEachIndexed { index, param ->
            val spelling = type(param.type)
            when (val port = param.port!!) {
                is GpuPort.Builtin -> arguments += when (target) {
                    GpuTarget.VULKAN -> "$spelling(${port.glsl})"
                    GpuTarget.DIRECTX -> "az_in_$index".also { declarations += "$spelling $it : ${port.hlsl}" }
                    GpuTarget.METAL -> "az_in_$index".also { declarations += "$spelling $it [[${port.metal}]]" }
                }
                is GpuPort.Location -> arguments += inputCode(inputs.first { it.name == "az_in_$index" })
                GpuPort.StageInput -> {
                    val fields = inputs.filter { it.name.startsWith("az_in_${index}_") }.map(::inputCode)
                    arguments += constructor(param.type, fields)
                }
                else -> if (target == GpuTarget.METAL) {
                    val name = "az_r_$index"
                    when (port) {
                        is GpuPort.Storage, is GpuPort.Uniform ->
                            declarations += "${metalResource(param, param.resource!!)} $name [[buffer(${(port as? GpuPort.Storage)?.binding ?: (port as GpuPort.Uniform).binding})]]"
                        is GpuPort.Texture -> declarations += "${textureType(param.type as GpuType.Texture, null)} $name [[texture(${port.binding})]]"
                        is GpuPort.SamplerSlot -> declarations += "sampler $name [[sampler(${port.binding})]]"
                        GpuPort.Workgroup -> prelude += "threadgroup ${declaration(param.type, name)};"
                        else -> error("unexpected port")
                    }
                    arguments += name
                }
            }
        }
        if (stage == GpuStage.COMPUTE) {
            val (x, y, z) = entry.workgroup
            when (target) {
                GpuTarget.VULKAN -> appendLine("layout(local_size_x = $x, local_size_y = $y, local_size_z = $z) in;")
                GpuTarget.DIRECTX -> appendLine("[numthreads($x, $y, $z)]")
                GpuTarget.METAL -> appendLine("[[max_total_threads_per_threadgroup(${x.toLong() * y * z})]]")
            }
        }
        val result = if (outputs.isEmpty()) "void" else "az_stage_out$tag"
        when (target) {
            GpuTarget.VULKAN -> appendLine("void main() {")
            GpuTarget.DIRECTX -> appendLine("$result $entryName(${declarations.joinToString()}) {")
            GpuTarget.METAL -> {
                val qualifier = if (stage == GpuStage.COMPUTE) "kernel" else stage.annotation.lowercase()
                appendLine("[[host_name(\"$entryName\")]] $qualifier $result az_entry$tag(${declarations.joinToString()}) {")
            }
        }
        prelude.forEach { appendLine("    $it") }
        val call = "$symbol(${arguments.joinToString()})"
        if (outputs.isEmpty()) {
            appendLine("    $call;")
        } else {
            appendLine("    ${type(entry.result)} az_result = $call;")
            if (target != GpuTarget.VULKAN) appendLine("    $result az_output;")
            for ((index, output) in outputs.withIndex()) {
                val destination = when {
                    target != GpuTarget.VULKAN -> "az_output.az_out_$index"
                    output.port == GpuPort.Builtin.POSITION -> "gl_Position"
                    else -> "az_out_$index"
                }
                val source = output.member?.let { "az_result.$it" } ?: "az_result"
                appendLine("    $destination = $source;")
            }
            if (target != GpuTarget.VULKAN) appendLine("    return az_output;")
        }
        appendLine("}")
    }

    private fun metalVarying(port: GpuPort.Location, type: GpuType): String {
        val flat = if (isInteger(type)) ", flat" else ""
        return "user(locn${port.index})$flat"
    }

    private fun isInteger(type: GpuType): Boolean =
        type is GpuType.Numeric && type.scalar in setOf(GpuScalar.INT, GpuScalar.UINT)
}

private fun <T> dependencyOrder(roots: Collection<T>, edges: (T) -> Iterable<T>, cycle: (T) -> Nothing): List<T> {
    val active = mutableSetOf<T>()
    val visited = mutableSetOf<T>()
    return buildList {
        fun visit(node: T) {
            if (node in visited) return
            if (!active.add(node)) cycle(node)
            for (edge in edges(node)) visit(edge)
            active.remove(node)
            visited.add(node)
            add(node)
        }
        for (root in roots) visit(root)
    }
}
