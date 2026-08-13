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

package org.azora.lang.backend

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Maps one platform failure to the `FileError` variant that describes it.
 *
 * The mapping lives in one place so that "what does this exception mean to a
 * program" is answered once. [fallback] is what an unrecognised failure becomes
 * - `ReadFailed` or `WriteFailed` depending on what was being attempted, since
 * those are the two the error set folds the long tail into.
 */
private fun classify(error: Throwable, fallback: String): String = when (error) {
    is NoSuchFileException -> "NotFound"
    is java.io.FileNotFoundException -> "NotFound"
    is AccessDeniedException -> "PermissionDenied"
    is SecurityException -> "PermissionDenied"
    is FileAlreadyExistsException -> "AlreadyExists"
    is DirectoryNotEmptyException -> "DirectoryNotEmpty"
    is java.nio.file.NotDirectoryException -> "NotADirectory"
    is java.nio.file.InvalidPathException -> "InvalidPath"
    else -> fallback
}

private fun pathOf(path: String): Path = File(path).toPath()

internal actual fun fsReadText(path: String): FsOutcome<String> = try {
    val file = File(path)
    // A directory read yields a confusing IOException; naming it is more useful.
    if (file.isDirectory) FsOutcome.failed("IsADirectory")
    else FsOutcome.of(file.readText())
} catch (error: Throwable) {
    FsOutcome.failed(classify(error, "ReadFailed"))
}

internal actual fun fsReadBytes(path: String): FsOutcome<ByteArray> = try {
    val file = File(path)
    if (file.isDirectory) FsOutcome.failed("IsADirectory")
    else FsOutcome.of(file.readBytes())
} catch (error: Throwable) {
    FsOutcome.failed(classify(error, "ReadFailed"))
}

/**
 * Writes through a temporary file and renames it into place.
 *
 * A write interrupted half-way leaves the previous contents intact rather than a
 * truncated file - the behaviour an editor's save depends on. Appending cannot
 * work that way, by definition, so it writes directly.
 */
internal actual fun fsWriteText(path: String, content: String, append: Boolean): String? = try {
    if (append) {
        File(path).appendText(content)
    } else {
        atomicallyReplace(path) { it.writeText(content) }
    }
    null
} catch (error: Throwable) {
    classify(error, "WriteFailed")
}

internal actual fun fsWriteBytes(path: String, bytes: ByteArray): String? = try {
    atomicallyReplace(path) { it.writeBytes(bytes) }
    null
} catch (error: Throwable) {
    classify(error, "WriteFailed")
}

private fun atomicallyReplace(path: String, write: (File) -> Unit) {
    val target = File(path)
    val temporary = File(target.absolutePath + ".azora-tmp")
    write(temporary)
    try {
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (unsupported: Throwable) {
        // Not every filesystem can move atomically; a plain replace is still
        // better than having written into the target directly.
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

internal actual fun fsList(path: String): FsOutcome<List<String>> = try {
    val directory = File(path)
    when {
        !directory.exists() -> FsOutcome.failed("NotFound")
        !directory.isDirectory -> FsOutcome.failed("NotADirectory")
        else -> FsOutcome.of(
            directory.listFiles().orEmpty().map { it.path }.sorted(),
        )
    }
} catch (error: Throwable) {
    FsOutcome.failed(classify(error, "ReadFailed"))
}

internal actual fun fsStat(path: String): FsOutcome<FsInfo> = try {
    val file = File(path)
    if (!file.exists()) {
        FsOutcome.failed("NotFound")
    } else {
        val kind = when {
            Files.isSymbolicLink(pathOf(path)) -> "Symlink"
            file.isDirectory -> "Directory"
            file.isFile -> "File"
            else -> "Other"
        }
        val millis = file.lastModified()
        FsOutcome.of(
            FsInfo(
                kind = kind,
                size = if (file.isDirectory) 0L else file.length(),
                modifiedSeconds = millis / 1000L,
                modifiedNanoseconds = (millis % 1000L) * 1_000_000L,
            ),
        )
    }
} catch (error: Throwable) {
    FsOutcome.failed(classify(error, "ReadFailed"))
}

internal actual fun fsExists(path: String): Boolean = try {
    File(path).exists()
} catch (error: Throwable) {
    false
}

internal actual fun fsMutate(op: String, from: String, to: String): String? = try {
    val source = File(from)
    when (op) {
        "createDirectory" -> when {
            source.exists() -> "AlreadyExists"
            source.mkdir() -> null
            else -> "WriteFailed"
        }
        "createDirectories" -> if (source.isDirectory || source.mkdirs()) null else "WriteFailed"
        "remove" -> when {
            !source.exists() -> "NotFound"
            source.isDirectory && source.list().orEmpty().isNotEmpty() -> "DirectoryNotEmpty"
            source.delete() -> null
            else -> "WriteFailed"
        }
        "removeAll" -> when {
            !source.exists() -> "NotFound"
            source.deleteRecursively() -> null
            else -> "WriteFailed"
        }
        "copyFile" -> when {
            !source.exists() -> "NotFound"
            source.isDirectory -> "IsADirectory"
            else -> {
                Files.copy(source.toPath(), pathOf(to), StandardCopyOption.REPLACE_EXISTING)
                null
            }
        }
        "rename" -> when {
            !source.exists() -> "NotFound"
            else -> {
                Files.move(source.toPath(), pathOf(to), StandardCopyOption.REPLACE_EXISTING)
                null
            }
        }
        else -> "Unsupported"
    }
} catch (error: Throwable) {
    classify(error, "WriteFailed")
}

internal actual fun fsTemporaryDirectory(): FsOutcome<String> = try {
    FsOutcome.of(System.getProperty("java.io.tmpdir") ?: "/tmp")
} catch (error: Throwable) {
    FsOutcome.failed("Unsupported")
}

internal actual fun fsCreateTemporaryDirectory(prefix: String): FsOutcome<String> = try {
    FsOutcome.of(Files.createTempDirectory(prefix).toFile().path)
} catch (error: Throwable) {
    FsOutcome.failed(classify(error, "WriteFailed"))
}

internal actual fun fsCanonical(path: String): FsOutcome<String> = try {
    val file = File(path)
    if (!file.exists()) FsOutcome.failed("NotFound") else FsOutcome.of(file.canonicalPath)
} catch (error: IOException) {
    FsOutcome.failed(classify(error, "ReadFailed"))
}
