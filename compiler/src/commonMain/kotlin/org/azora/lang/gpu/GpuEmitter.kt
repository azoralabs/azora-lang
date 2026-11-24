/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.gpu

import org.azora.lang.frontend.*

internal class GpuEmitter(val program: GpuProgram, val target: GpuTarget) {
    val constants = linkedMapOf<String, GpuValue>()
    val calls = linkedMapOf<GpuFunction, MutableSet<GpuFunction>>()

    fun emit(entry: GpuFunction): GpuShaderSource {
        val (orderedAggregates, constantSource, orderedFunctions, bodies) = prepare()
        val entryName = when (target) {
            GpuTarget.DIRECTX -> "az_entry"
            else -> entry.decl.name
        }
        val source = buildString {
            when (target) {
                GpuTarget.METAL -> appendLine("#include <metal_stdlib>\nusing namespace metal;")
                GpuTarget.VULKAN -> appendLine("#version 450")
                GpuTarget.DIRECTX -> Unit
            }
            orderedAggregates.filterNot { it.plainEnum }.forEach { appendAggregate(it) }
            append(constantSource)
            appendStorage(entry)
            for (function in orderedFunctions) {
                if (function.stage == null || function == entry) append(bodies.getValue(function))
            }
            appendEntry(entry, entryName)
        }
        return GpuShaderSource(source, entry.decl.name, checkNotNull(entry.stage), entryName)
    }

    fun check() {
        prepare()
    }

    private data class ShaderDeclarations(
        val aggregates: List<GpuAggregate>,
        val constants: String,
        val functions: List<GpuFunction>,
        val bodies: Map<GpuFunction, String>,
    )

    private fun prepare(): ShaderDeclarations {
        val aggregates = dependencyOrder(program.aggregates.values, { aggregate ->
            aggregate.dependencies.filterIsInstance<GpuType.Named>().map { program.aggregates.getValue(it.name) }
        }) { it.site.fail("recursive GPU type '${it.name}' has no finite layout") }
        val constantSource = emitConstants()
        val bodies = program.functions.values.associateWith { GpuBody(this, it).emit() }
        val functions = dependencyOrder(program.functions.values, { calls[it].orEmpty() }) {
            it.site.fail("recursive GPU function '${it.displayName}'")
        }
        return ShaderDeclarations(aggregates, constantSource, functions, bodies)
    }

    fun type(type: GpuType): String = when (type) {
        is GpuType.Named -> if (program.aggregates.getValue(type.name).plainEnum) "uint" else "az_t_${gpuName(type.name)}"
        is GpuType.Numeric -> if (type.width == 1) type.scalar.shaderName else when (target) {
            GpuTarget.VULKAN -> when (type.scalar) {
                GpuScalar.FLOAT -> "vec"
                GpuScalar.INT -> "ivec"
                GpuScalar.UINT -> "uvec"
                else -> error("invalid vector type")
            } + type.width
            else -> type.scalar.shaderName + type.width
        }
    }

    fun constructor(type: GpuType, arguments: List<String>): String {
        val name = if (type is GpuType.Named) "az_make_${gpuName(type.name)}" else type(type)
        return "$name(${arguments.joinToString()})"
    }

    fun field(name: String) = "az_field_${gpuName(name)}"

    fun caseConstructor(aggregate: GpuAggregate, case: GpuCase, args: List<String>): String {
        if (!aggregate.tagged) return "${case.index}u"
        return "az_case_${gpuName(aggregate.name)}_${case.index}(${args.joinToString()})"
    }

    private fun zero(type: GpuType): String = when (type) {
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
    }

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
        for ((field, type) in fields) appendLine("    ${type(type)} $field;")
        appendLine("};")
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
            appendLine("$qualifier ${type(value.type)} $symbol = ${value.code};")
            constants[name] = GpuValue(value.type, symbol)
        }
    }

    fun parameter(index: Int) = "az_p_$index"
    fun storage(index: Int): String = when (target) {
        GpuTarget.VULKAN -> "az_storage_$index.az_elements"
        GpuTarget.DIRECTX -> "az_storage_$index"
        GpuTarget.METAL -> parameter(index)
    }

    fun storageType(parameter: GpuParameter): String {
        val port = parameter.port as GpuPort.Storage
        return "device ${if (port.writable) "" else "const "}${type(parameter.type)}*"
    }

    fun signature(function: GpuFunction): String {
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
                if (parameter.port !is GpuPort.Storage) add("${type(parameter.type)} ${parameter(index)}")
                else if (target == GpuTarget.METAL) add("${storageType(parameter)} ${parameter(index)}")
            }
        }
        return "${type(function.result)} ${function.symbol}(${parameters.joinToString()})"
    }

    private fun StringBuilder.appendStorage(entry: GpuFunction) {
        entry.parameters.forEachIndexed { index, parameter ->
            val port = parameter.port as? GpuPort.Storage ?: return@forEachIndexed
            val type = type(parameter.type)
            when (target) {
                GpuTarget.VULKAN -> {
                    val access = if (port.writable) "" else "readonly "
                    appendLine("layout(std430, set = 0, binding = ${port.binding}) ${access}buffer az_block_$index {")
                    appendLine("    $type az_elements[];")
                    appendLine("} az_storage_$index;")
                }
                GpuTarget.DIRECTX -> {
                    val buffer = if (port.writable) "RWStructuredBuffer" else "StructuredBuffer"
                    val register = if (port.writable) "u" else "t"
                    appendLine("$buffer<$type> az_storage_$index : register($register${port.binding}, space0);")
                }
                GpuTarget.METAL -> Unit
            }
        }
    }

    private fun StringBuilder.appendEntry(entry: GpuFunction, entryName: String) {
        val stage = entry.stage!!
        val outputs = when {
            stage == GpuStage.COMPUTE -> emptyList()
            entry.resultPort != null -> listOf(Output(entry.result, entry.resultPort, null))
            else -> program.aggregates.getValue((entry.result as GpuType.Named).name).fields.map {
                Output(it.type, it.port!!, field(it.name))
            }
        }
        val inputs = entry.parameters.withIndex().filter { it.value.port is GpuPort.Location }
        val inputStruct = target == GpuTarget.METAL && inputs.isNotEmpty()
        if (inputStruct) {
            appendLine("struct az_stage_in {")
            for ((index, param) in inputs) {
                val port = param.port as GpuPort.Location
                val attr = if (stage == GpuStage.VERTEX) "attribute(${port.index})" else metalVarying(port, param.type)
                appendLine("    ${type(param.type)} az_in_$index [[$attr]];")
            }
            appendLine("};")
        }
        if (target == GpuTarget.VULKAN) {
            for ((index, param) in inputs) {
                val port = param.port as GpuPort.Location
                val flat = if (stage == GpuStage.FRAGMENT && isInteger(param.type)) "flat " else ""
                appendLine("layout(location = ${port.index}) ${flat}in ${type(param.type)} az_in_$index;")
            }
            outputs.forEachIndexed { index, output ->
                val port = output.port as? GpuPort.Location ?: return@forEachIndexed
                val flat = if (stage == GpuStage.VERTEX && isInteger(output.type)) "flat " else ""
                appendLine("layout(location = ${port.index}) ${flat}out ${type(output.type)} az_out_$index;")
            }
        } else if (outputs.isNotEmpty()) {
            appendLine("struct az_stage_out {")
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
        if (inputStruct) declarations += "az_stage_in az_inputs [[stage_in]]"
        entry.parameters.forEachIndexed { index, param ->
            val spelling = type(param.type)
            when (val port = param.port!!) {
                is GpuPort.Storage -> if (target == GpuTarget.METAL) {
                    declarations += "${storageType(param)} az_in_$index [[buffer(${port.binding})]]"
                    arguments += "az_in_$index"
                }
                is GpuPort.Builtin -> {
                    arguments += when (target) {
                        GpuTarget.VULKAN -> "$spelling(${port.glsl})"
                        GpuTarget.DIRECTX -> "az_in_$index".also { declarations += "$spelling $it : ${port.hlsl}" }
                        GpuTarget.METAL -> "az_in_$index".also { declarations += "$spelling $it [[${port.metal}]]" }
                    }
                }
                is GpuPort.Location -> arguments += when (target) {
                    GpuTarget.VULKAN -> "az_in_$index"
                    GpuTarget.METAL -> "az_inputs.az_in_$index"
                    GpuTarget.DIRECTX -> "az_in_$index".also {
                        val flat = if (stage == GpuStage.FRAGMENT && isInteger(param.type)) "nointerpolation " else ""
                        declarations += "$flat$spelling $it : TEXCOORD${port.index}"
                    }
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
        val result = if (outputs.isEmpty()) "void" else "az_stage_out"
        when (target) {
            GpuTarget.VULKAN -> appendLine("void main() {")
            GpuTarget.DIRECTX -> appendLine("$result $entryName(${declarations.joinToString()}) {")
            GpuTarget.METAL -> {
                val qualifier = if (stage == GpuStage.COMPUTE) "kernel" else stage.annotation.lowercase()
                appendLine("[[host_name(\"$entryName\")]] $qualifier $result az_entry(${declarations.joinToString()}) {")
            }
        }
        val call = "${entry.symbol}(${arguments.joinToString()})"
        if (outputs.isEmpty()) {
            appendLine("    $call;")
        } else {
            appendLine("    ${type(entry.result)} az_result = $call;")
            if (target != GpuTarget.VULKAN) appendLine("    az_stage_out az_output;")
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

    private data class Output(val type: GpuType, val port: GpuPort, val member: String?)
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
