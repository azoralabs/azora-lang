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

/**
 * The filesystem services `std.filesystem` is built on.
 *
 * `expect`s rather than C bridges for the reason `OsAccess` gives: `azora run`
 * executes through [IrInterpreter], which has no FFI, so a `bridge .C`
 * declaration compiles and then fails at the call.
 *
 * Every operation reports failure as a [FileError] *name* - the same spelling as
 * the Azora variant - so the mapping from a platform's errors to the language's
 * error set happens once, here, rather than being re-derived by each backend.
 */

/** Success, or the name of the `FileError` variant that describes the failure. */
internal data class FsOutcome<T>(val value: T?, val error: String?) {
    companion object {
        fun <T> of(value: T): FsOutcome<T> = FsOutcome(value, null)
        fun <T> failed(error: String): FsOutcome<T> = FsOutcome(null, error)
    }
}

/** What [fsStat] found: kind name, size in bytes, and modification time. */
internal data class FsInfo(
    val kind: String,
    val size: Long,
    val modifiedSeconds: Long,
    val modifiedNanoseconds: Long,
)

internal expect fun fsReadText(path: String): FsOutcome<String>

internal expect fun fsReadBytes(path: String): FsOutcome<ByteArray>

/** Writes [content]; replaces atomically unless [append]. */
internal expect fun fsWriteText(path: String, content: String, append: Boolean): String?

internal expect fun fsWriteBytes(path: String, bytes: ByteArray): String?

/** Entries under [path], full paths, sorted - so a directory walk is reproducible. */
internal expect fun fsList(path: String): FsOutcome<List<String>>

internal expect fun fsStat(path: String): FsOutcome<FsInfo>

/** A predicate, never failable: an unreadable path simply is not a file. */
internal expect fun fsExists(path: String): Boolean

/**
 * One of the mutating operations, named by [op].
 *
 * Gathered behind a single `expect` because each is two lines on every platform
 * and eight near-identical declarations obscure the two that are interesting
 * (`removeAll`, `rename`). [op] is one of `createDirectory`,
 * `createDirectories`, `remove`, `removeAll`, `copyFile`, `rename`.
 */
internal expect fun fsMutate(op: String, from: String, to: String): String?

/** The system temporary directory. */
internal expect fun fsTemporaryDirectory(): FsOutcome<String>

/** A directory that did not exist a moment ago, named after [prefix]. */
internal expect fun fsCreateTemporaryDirectory(prefix: String): FsOutcome<String>

/** [path] with symlinks resolved; needs the disk, unlike `Path.normalized`. */
internal expect fun fsCanonical(path: String): FsOutcome<String>
