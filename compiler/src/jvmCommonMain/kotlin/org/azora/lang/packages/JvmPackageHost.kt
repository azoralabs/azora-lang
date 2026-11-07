/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.packages

import java.io.File

/** The package manager's view of this machine, through `java.io`. */
class JvmPackageHost : PackageHost {
    override fun exists(path: String) = File(path).exists()
    override fun isDirectory(path: String) = File(path).isDirectory
    override fun readText(path: String) = File(path).readText()
    override fun writeText(path: String, text: String) = File(path).writeText(text)
    override fun createDirectories(path: String) {
        File(path).mkdirs()
    }

    override fun azoraSources(dir: String): List<String> {
        val root = File(dir)
        if (!root.isDirectory) return emptyList()
        // Hidden directories hold staged copies and caches, never sources.
        return root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "az" }
            .map { it.canonicalPath }
            .sorted()
            .toList()
    }

    override fun canonical(path: String): String = File(path).canonicalPath

    override fun run(command: List<String>, directory: String?): ProcessResult {
        val process = try {
            ProcessBuilder(command).apply { directory?.let { directory(File(it)) } }.redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            return ProcessResult(127, "cannot run ${command.first()}: ${e.message}")
        }
        val output = process.inputStream.bufferedReader().readText()
        return ProcessResult(process.waitFor(), output)
    }

    override fun environment(name: String): String? = System.getenv(name)
    override val home: String get() = System.getProperty("user.home")
}
