package org.azora.lang.stdlib

import org.azora.lang.backend.*

internal actual fun stdlibDiskRoots(): List<StdlibRoot> = buildList {
    osEnvVar("AZORA_STDLIB")?.takeIf { it.isNotBlank() }?.let { add(StdlibRoot(it, "AZORA_STDLIB", explicit = true)) }
    var directory = osCurrentDirectory()
    while (true) {
        if (listOf("workspace.azon", "azora.toml", "package.azon").any { fsExists("$directory/$it") }) {
            if (fsStat("$directory/std").value?.kind == "Directory") add(StdlibRoot("$directory/std", "project-local std/"))
            break
        }
        if (directory == "/") break
        directory = directory.substringBeforeLast('/').ifEmpty { "/" }
    }
    osEnvVar("AZORA_HOME")?.takeIf { it.isNotBlank() }?.let { add(StdlibRoot("$it/std", "AZORA_HOME")) }
}

internal actual fun readStdlibTree(root: String): List<StdlibFile>? {
    if (fsStat(root).value?.kind != "Directory") return null
    val result = mutableListOf<StdlibFile>()
    fun visit(path: String) {
        for (child in fsList(path).value ?: error("cannot read standard library directory '$path'")) {
            when (fsStat(child).value?.kind) {
                "Directory" -> visit(child)
                "File" -> if (child.endsWith(".az")) result += StdlibFile(child.removePrefix("${root.trimEnd('/')}/"), fsReadText(child).value ?: error("cannot read standard library '$child'"))
            }
        }
    }
    visit(root)
    return result.sortedBy { it.path }
}

internal actual fun readStdlibPackageManifest(root: String): String? =
    fsReadText("${root.trimEnd('/').substringBeforeLast('/')}/$STDLIB_PACKAGE_MANIFEST").value
