/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.packages

/**
 * What the package manager needs from the machine: files, a process, the
 * environment. Each CLI supplies one (the JVM CLI through `java.io`, the native
 * one through POSIX), so resolution, locking and build planning are written
 * once. Paths are absolute and `/`-separated.
 */
interface PackageHost {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun readText(path: String): String
    fun writeText(path: String, text: String)
    fun createDirectories(path: String)

    /** The `.az` files under [dir], recursively, sorted by path. */
    fun azoraSources(dir: String): List<String>

    /** [path] with `.` and `..` resolved and symbolic links followed. */
    fun canonical(path: String): String

    /**
     * Runs [command] in [directory] and waits; stdout and stderr together. A
     * program that cannot be started answers exit code 127 and the reason.
     */
    fun run(command: List<String>, directory: String? = null): ProcessResult

    fun environment(name: String): String?

    /** The user's home directory. */
    val home: String
}

data class ProcessResult(val exitCode: Int, val output: String)

/** `/`-separated path arithmetic for [PackageHost] paths. */
object Paths {
    private val driveRoot = Regex("^[a-zA-Z]:/")
    private val shareRoot = Regex("^//[^/]+/[^/]+")

    private fun root(path: String): String =
        driveRoot.find(path)?.value ?: shareRoot.find(path)?.value ?: if (path.startsWith('/')) "/" else ""

    private fun isWindowsRoot(root: String): Boolean = root.endsWith(":/") || root.startsWith("//")

    fun join(base: String, relative: String): String {
        val path = if (isWindowsRoot(root(base))) relative.replace('\\', '/') else relative
        return if (root(path).isNotEmpty()) path else base.trimEnd('/') + "/" + path
    }

    fun parent(path: String): String {
        val root = root(path)
        return path.trimEnd('/').substringBeforeLast('/', "").let { if (it.length <= root.length) root else it }
    }

    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** [path] relative to [base], both canonical. Other drives or shares keep their absolute path. */
    fun relative(base: String, path: String): String {
        val baseRoot = root(base)
        val pathRoot = root(path)
        val windows = isWindowsRoot(baseRoot)
        if (!baseRoot.equals(pathRoot, ignoreCase = windows)) return path
        val from = base.drop(baseRoot.length).split('/').filter { it.isNotEmpty() }
        val to = path.drop(pathRoot.length).split('/').filter { it.isNotEmpty() }
        var common = 0
        while (common < from.size && common < to.size && from[common].equals(to[common], ignoreCase = windows)) common++
        val up = List(from.size - common) { ".." }
        return (up + to.drop(common)).joinToString("/").ifEmpty { "." }
    }
}
