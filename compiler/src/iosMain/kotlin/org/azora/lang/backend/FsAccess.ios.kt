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
 * iOS: reachable storage is the app sandbox, which this module does not model yet.
 *
 * Every operation raises `FileError.Unsupported` rather than emulating a
 * filesystem. A virtual one would let a program appear to save work that nobody
 * can retrieve; being told there is no disk is the more useful answer.
 *
 * `fsExists` is the exception, and answers `false` - a predicate always has a
 * defensible answer, and "there is no file here" is true.
 */
internal actual fun fsReadText(path: String): FsOutcome<String> = FsOutcome.failed("Unsupported")

internal actual fun fsReadBytes(path: String): FsOutcome<ByteArray> = FsOutcome.failed("Unsupported")

internal actual fun fsWriteText(path: String, content: String, append: Boolean): String? = "Unsupported"

internal actual fun fsWriteBytes(path: String, bytes: ByteArray): String? = "Unsupported"

internal actual fun fsList(path: String): FsOutcome<List<String>> = FsOutcome.failed("Unsupported")

internal actual fun fsStat(path: String): FsOutcome<FsInfo> = FsOutcome.failed("Unsupported")

internal actual fun fsExists(path: String): Boolean = false

internal actual fun fsMutate(op: String, from: String, to: String): String? = "Unsupported"

internal actual fun fsTemporaryDirectory(): FsOutcome<String> = FsOutcome.failed("Unsupported")

internal actual fun fsCreateTemporaryDirectory(prefix: String): FsOutcome<String> =
    FsOutcome.failed("Unsupported")

internal actual fun fsCanonical(path: String): FsOutcome<String> = FsOutcome.failed("Unsupported")
