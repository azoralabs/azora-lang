/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.gpu

import org.azora.lang.backend.*

class GpuUsageException(message: String) : IllegalArgumentException(message)

/** File and toolchain adapter shared by the JVM and native command lines. */
object GpuCommand {
    const val usage = "azora compile <metallib|spv|dxil> <file.azgpu> [--entry <name>] [--profile <profile>] [-o <file>] [--emit-source]"

    fun handlesCompile(arguments: List<String>): Boolean {
        if (arguments.firstOrNull()?.let(GpuTarget::fromArgument) != null) return true
        val source = arguments.indices.drop(1).firstOrNull { index ->
            !arguments[index].startsWith('-') && arguments[index - 1] != "-o"
        }
        return source != null && arguments[source].endsWith(".azgpu")
    }

    fun handlesCheck(arguments: List<String>): Boolean = arguments.any { it.endsWith(".azgpu") }

    fun check(arguments: List<String>) {
        val path = arguments.singleOrNull() ?: throw GpuUsageException("azora check <file.azgpu>")
        GpuCompiler.check(read(path), path)
    }

    fun compile(arguments: List<String>, run: (List<String>) -> Int) {
        val options = parse(arguments)
        val shader = GpuCompiler.compile(read(options.path), options.path, options.target, options.entry)
        if (options.emitSource) {
            print(shader.text)
            return
        }

        val directory = fsCreateTemporaryDirectory("azora-gpu-").value
            ?: throw GpuCompileException("cannot create GPU compilation directory")
        try {
            val sourcePath = "$directory/shader.${sourceExtension(options.target)}"
            val artifact = "$directory/shader.${options.target.extension}"
            fsWriteText(sourcePath, shader.text, false)?.let { throw GpuCompileException("cannot prepare shader source ($it)") }
            val command = toolCommand(options, shader, sourcePath, artifact)
            val exit = try {
                run(command)
            } catch (failure: Exception) {
                throw GpuCompileException("cannot start '${command.first()}': ${failure.message}")
            }
            if (exit != 0) throw GpuCompileException(
                "${options.path}: ${options.target.extension} toolchain failed for '${shader.entry}' (exit $exit)",
            )
            val bytes = fsReadBytes(artifact).value
            if (bytes == null || !validArtifact(options.target, bytes)) {
                throw GpuCompileException("${options.path}: toolchain did not produce a ${options.target.extension} shader binary")
            }
            fsWriteBytes(options.output, bytes)?.let {
                throw GpuCompileException("cannot write '${options.output}' ($it)")
            }
        } finally {
            fsMutate("removeAll", directory, "")
        }
    }

    private data class Options(
        val target: GpuTarget, val path: String, val entry: String?, val profile: String,
        val output: String, val emitSource: Boolean, val metalStandard: String?,
    )

    private fun parse(arguments: List<String>): Options {
        val target = arguments.firstOrNull()?.let(GpuTarget::fromArgument)
            ?: throw GpuUsageException(usage)
        val flags = mutableMapOf<String, String>()
        val positional = mutableListOf<String>()
        var emitSource = false
        var index = 1
        var afterFlags = false
        while (index < arguments.size) {
            val argument = arguments[index++]
            when {
                afterFlags -> positional += argument
                argument == "--" -> afterFlags = true
                argument == "--emit-source" -> emitSource = true
                argument in valueFlags -> {
                    if (argument in flags) throw GpuUsageException("duplicate option '$argument'")
                    flags[argument] = arguments.getOrNull(index++)?.takeIf { !it.startsWith('-') }
                        ?: throw GpuUsageException("$argument needs a value")
                }
                argument.startsWith('-') -> throw GpuUsageException("unknown GPU option '$argument'")
                else -> positional += argument
            }
        }
        val path = positional.singleOrNull() ?: throw GpuUsageException(usage)
        if (!path.endsWith(".azgpu")) throw GpuUsageException("GPU compilation expects a .azgpu file")
        val profile = flags["--profile"] ?: target.defaultProfile
        val valid = when (target) {
            GpuTarget.VULKAN -> vulkanProfile.matches(profile)
            GpuTarget.DIRECTX -> shaderModel.matches(profile) && (profile.substringBefore('.').toIntOrNull() ?: 0) >= 6
            GpuTarget.METAL -> metalSdk.matches(profile)
        }
        if (!valid) throw GpuUsageException("invalid ${target.extension} profile '$profile'")
        val standard = flags["--metal-standard"]
        if (standard != null && (target != GpuTarget.METAL || !metalStandard.matches(standard))) {
            throw GpuUsageException("--metal-standard requires a Metal target and a version such as metal3.2")
        }
        if (emitSource && "-o" in flags) throw GpuUsageException("--emit-source writes to standard output")
        val entry = flags["--entry"]
        val output = flags["-o"] ?: buildString {
            append(path.removeSuffix(".azgpu"))
            if (entry != null) append('.').append(entry)
            append('.').append(target.extension)
        }
        if (!output.endsWith(".${target.extension}")) throw GpuUsageException("output path must end in .${target.extension}")
        return Options(target, path, entry, profile, output, emitSource, standard)
    }

    private fun sourceExtension(target: GpuTarget): String = when (target) {
        GpuTarget.METAL -> "metal"
        GpuTarget.VULKAN -> "glsl"
        GpuTarget.DIRECTX -> "hlsl"
    }

    private fun toolCommand(options: Options, shader: GpuShaderSource, source: String, output: String): List<String> =
        when (options.target) {
            GpuTarget.METAL -> buildList {
                addAll(listOf(osEnvVar("AZORA_XCRUN") ?: "xcrun", "-sdk", options.profile, "metal"))
                options.metalStandard?.let { add("-std=$it") }
                addAll(listOf("-o", output, source))
            }
            GpuTarget.VULKAN -> listOf(
                osEnvVar("AZORA_GLSLANG") ?: "glslangValidator", "--quiet", "--target-env", options.profile,
                "--spirv-val", "-S", shader.stage.glslStage, "--source-entrypoint", "main", "-e", shader.backendEntry,
                "-o", output, source,
            )
            GpuTarget.DIRECTX -> listOf(
                osEnvVar("AZORA_DXC") ?: "dxc", "-HV", "2021", "-Ges",
                "-T", "${shader.stage.shaderModelPrefix}_${options.profile.replace('.', '_')}", "-E", shader.backendEntry,
                "-Fo", output, source,
            )
        }

    private fun validArtifact(target: GpuTarget, bytes: ByteArray): Boolean = when (target) {
        GpuTarget.METAL -> bytes.hasSignature("MTLB")
        GpuTarget.VULKAN -> bytes.size >= 20 && bytes.size % 4 == 0 && bytes.wordAt(0) == 0x07230203
        GpuTarget.DIRECTX -> hasDxilPart(bytes)
    }

    private fun hasDxilPart(bytes: ByteArray): Boolean {
        // DXContainer header, followed by its table of offsets to four-CC parts.
        if (bytes.size < 32 || !bytes.hasSignature("DXBC") || bytes.wordAt(24) != bytes.size) return false
        val count = bytes.wordAt(28)
        if (count !in 1..(bytes.size - 32) / 4) return false
        val endOfTable = 32 + count * 4
        return (0 until count).any { index ->
            val offset = bytes.wordAt(32 + index * 4)
            offset in endOfTable..bytes.size - 8 && bytes.hasSignature("DXIL", offset) &&
                bytes.wordAt(offset + 4) in 1..bytes.size - offset - 8
        }
    }

    private fun ByteArray.hasSignature(signature: String, offset: Int = 0): Boolean =
        size - offset > signature.length && signature.indices.all { this[offset + it] == signature[it].code.toByte() }

    private fun ByteArray.wordAt(offset: Int): Int =
        (0..3).fold(0) { word, index -> word or ((this[offset + index].toInt() and 0xff) shl (index * 8)) }

    private fun read(path: String): String {
        if (!path.endsWith(".azgpu")) throw GpuUsageException("GPU compilation expects a .azgpu file")
        return fsReadText(path).value ?: throw GpuUsageException("cannot read '$path'")
    }

    private val valueFlags = setOf("--entry", "--profile", "--metal-standard", "-o")
    private val vulkanProfile = Regex("vulkan[0-9]+\\.[0-9]+")
    private val shaderModel = Regex("[0-9]+\\.[0-9]+")
    private val metalSdk = Regex("[a-z][a-z0-9.]*")
    private val metalStandard = Regex("metal[0-9]+\\.[0-9]+")
}
