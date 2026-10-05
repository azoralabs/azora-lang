@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.azora.lang.backend

import kotlinx.cinterop.*
import platform.posix.*

private fun fsError(): String = when (errno) {
    ENOENT -> "NotFound"
    EACCES, EPERM -> "PermissionDenied"
    EEXIST -> "AlreadyExists"
    ENOTDIR -> "NotADirectory"
    EISDIR -> "IsADirectory"
    ENOTEMPTY -> "DirectoryNotEmpty"
    else -> "ReadFailed"
}

internal actual fun fsReadBytes(path: String): FsOutcome<ByteArray> {
    val file = fopen(path, "rb") ?: return FsOutcome.failed(fsError())
    try {
        if (fseek(file, 0, SEEK_END) != 0) return FsOutcome.failed(fsError())
        val length = ftell(file)
        if (length < 0 || length > Int.MAX_VALUE) return FsOutcome.failed("ReadFailed")
        rewind(file)
        val bytes = ByteArray(length.toInt())
        if (bytes.isNotEmpty()) {
            val read = bytes.usePinned { fread(it.addressOf(0), 1uL, bytes.size.toULong(), file) }
            if (read != bytes.size.toULong()) return FsOutcome.failed("ReadFailed")
        }
        return FsOutcome.of(bytes)
    } finally { fclose(file) }
}

internal actual fun fsReadText(path: String): FsOutcome<String> {
    val bytes = fsReadBytes(path)
    return bytes.value?.let { FsOutcome.of(it.decodeToString()) } ?: FsOutcome.failed(bytes.error ?: "ReadFailed")
}

private fun writeFile(path: String, bytes: ByteArray, append: Boolean): String? {
    val file = fopen(path, if (append) "ab" else "wb") ?: return fsError()
    var error: String? = null
    if (bytes.isNotEmpty()) {
        val written = bytes.usePinned { fwrite(it.addressOf(0), 1uL, bytes.size.toULong(), file) }
        if (written != bytes.size.toULong()) error = "WriteFailed"
    }
    if (fclose(file) != 0) error = fsError()
    return error
}

internal actual fun fsWriteText(path: String, content: String, append: Boolean): String? =
    if (append) writeFile(path, content.encodeToByteArray(), true) else fsWriteBytes(path, content.encodeToByteArray())

internal actual fun fsWriteBytes(path: String, bytes: ByteArray): String? = memScoped {
    // Create in the destination directory so rename is atomic on one filesystem.
    val pattern = "$path.azora-XXXXXX".cstr.ptr
    val descriptor = mkstemp(pattern)
    if (descriptor < 0) return@memScoped fsError()
    close(descriptor)
    val temporary = pattern.toKString()
    val error = writeFile(temporary, bytes, false)
    if (error != null) { unlink(temporary); return@memScoped error }
    if (rename(temporary, path) != 0) { val failure = fsError(); unlink(temporary); return@memScoped failure }
    null
}

internal actual fun fsList(path: String): FsOutcome<List<String>> {
    val directory = opendir(path) ?: return FsOutcome.failed(fsError())
    try {
        val entries = mutableListOf<String>()
        while (true) {
            __error()!!.pointed.value = 0
            val entry = readdir(directory) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name != "." && name != "..") entries += "${path.trimEnd('/')}/$name"
        }
        if (errno != 0) return FsOutcome.failed(fsError())
        return FsOutcome.of(entries.sorted())
    } finally { closedir(directory) }
}

internal actual fun fsStat(path: String): FsOutcome<FsInfo> = memScoped {
    val info = alloc<stat>()
    if (lstat(path, info.ptr) != 0) return@memScoped FsOutcome.failed(fsError())
    val kind = when (info.st_mode.toInt() and S_IFMT) {
        S_IFREG -> "File"
        S_IFDIR -> "Directory"
        S_IFLNK -> "Symlink"
        else -> "Other"
    }
    FsOutcome.of(FsInfo(kind, info.st_size, info.st_mtimespec.tv_sec, info.st_mtimespec.tv_nsec))
}

internal actual fun fsExists(path: String): Boolean = access(path, F_OK) == 0

internal actual fun fsMutate(op: String, from: String, to: String): String? = when (op) {
    "createDirectory" -> if (mkdir(from, 0x1ed.convert()) == 0) null else fsError()
    "createDirectories" -> {
        var current = if (from.startsWith('/')) "/" else ""
        var error: String? = null
        for (part in from.split('/').filter { it.isNotEmpty() }) {
            current = if (current == "/") "/$part" else if (current.isEmpty()) part else "$current/$part"
            if (mkdir(current, 0x1ed.convert()) != 0 && errno != EEXIST) { error = fsError(); break }
            if (fsStat(current).value?.kind != "Directory") { error = "NotADirectory"; break }
        }
        error
    }
    "remove" -> if (remove(from) == 0) null else fsError()
    "removeAll" -> {
        val info = fsStat(from)
        if (info.error == "NotFound") null
        else if (info.value?.kind == "Directory") {
            val children = fsList(from)
            children.error ?: children.value.orEmpty().firstNotNullOfOrNull { fsMutate("removeAll", it, "") }
                ?: if (rmdir(from) == 0) null else fsError()
        } else if (unlink(from) == 0) null else fsError()
    }
    "copyFile" -> fsReadBytes(from).let { it.value?.let { data -> fsWriteBytes(to, data) } ?: it.error }
    "rename" -> if (rename(from, to) == 0) null else fsError()
    else -> "Unsupported"
}

internal actual fun fsTemporaryDirectory(): FsOutcome<String> =
    FsOutcome.of(getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp")

internal actual fun fsCreateTemporaryDirectory(prefix: String): FsOutcome<String> = memScoped {
    if ('/' in prefix || '\u0000' in prefix) return@memScoped FsOutcome.failed("InvalidPath")
    val pattern = "${fsTemporaryDirectory().value}/$prefix-XXXXXX".cstr.ptr
    mkdtemp(pattern)?.let { FsOutcome.of(it.toKString()) } ?: FsOutcome.failed(fsError())
}

internal actual fun fsCanonical(path: String): FsOutcome<String> {
    val resolved = realpath(path, null) ?: return FsOutcome.failed(fsError())
    return try { FsOutcome.of(resolved.toKString()) } finally { free(resolved) }
}
