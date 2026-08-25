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

package org.azora.lang.stdlib

/** One standard-library source file: its path relative to the `std/` root, and its text. */
data class StdlibFile(val path: String, val source: String)

/**
 * A resolved standard library: where it came from, what version it claims, and
 * its files.
 *
 * [origin] is shown in diagnostics so a surprising standard library is
 * traceable to the thing that selected it - an environment variable and a
 * vendored copy in a project read very differently when a build misbehaves.
 */
data class StdlibTree(
    val origin: String,
    val version: String,
    val files: List<StdlibFile>,
)

/** The package manifest beside a `std/` root. */
const val STDLIB_PACKAGE_MANIFEST = "package.azon"

/** The standard-library fields read from `package.azon`. */
internal data class StdlibPackageManifest(val name: String, val version: String)

/**
 * Reads `package.name` and `package.version` from AZON without treating the
 * manifest as JSON or matching source text with regular expressions.
 *
 * The small tokenizer is intentionally tolerant of comments, quoted keys,
 * additional fields, and nested objects. Package discovery must continue to
 * work as the manifest grows; only these two fields belong to stdlib identity.
 */
internal fun parseStdlibPackageManifest(source: String): StdlibPackageManifest? {
    data class ManifestToken(val kind: Char, val text: String)

    val tokens = mutableListOf<ManifestToken>()
    var index = 0
    while (index < source.length) {
        val character = source[index]
        when {
            character.isWhitespace() || character == ',' -> index++
            character == '#' -> {
                while (index < source.length && source[index] != '\n') index++
            }
            character == '/' && source.getOrNull(index + 1) == '/' -> {
                index += 2
                while (index < source.length && source[index] != '\n') index++
            }
            character == '/' && source.getOrNull(index + 1) == '*' -> {
                index += 2
                while (index + 1 < source.length &&
                    !(source[index] == '*' && source[index + 1] == '/')
                ) index++
                index = (index + 2).coerceAtMost(source.length)
            }
            character == '"' -> {
                index++
                val value = StringBuilder()
                while (index < source.length && source[index] != '"') {
                    if (source[index] == '\\' && index + 1 < source.length) {
                        index++
                        value.append(
                            when (val escaped = source[index]) {
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                else -> escaped
                            },
                        )
                    } else {
                        value.append(source[index])
                    }
                    index++
                }
                if (index < source.length) index++
                tokens += ManifestToken('s', value.toString())
            }
            character.isLetter() || character == '_' -> {
                val start = index++
                while (index < source.length &&
                    (source[index].isLetterOrDigit() || source[index] in setOf('_', '-'))
                ) index++
                tokens += ManifestToken('i', source.substring(start, index))
            }
            character in setOf(':', '{', '}', '[', ']', '(', ')') -> {
                tokens += ManifestToken(character, character.toString())
                index++
            }
            else -> index++
        }
    }

    val packageIndex = tokens.indices.firstOrNull { at ->
        tokens[at].text == "package" && tokens.getOrNull(at + 1)?.kind == ':' &&
            tokens.getOrNull(at + 2)?.kind == '{'
    } ?: return null

    var depth = 1
    var name: String? = null
    var version: String? = null
    var cursor = packageIndex + 3
    while (cursor < tokens.size && depth > 0) {
        val token = tokens[cursor]
        when (token.kind) {
            '{' -> depth++
            '}' -> depth--
            'i', 's' -> if (depth == 1 && tokens.getOrNull(cursor + 1)?.kind == ':' &&
                tokens.getOrNull(cursor + 2)?.kind == 's'
            ) {
                val value = tokens[cursor + 2].text
                when (token.text) {
                    "name" -> name = value
                    "version" -> version = value
                }
                cursor += 2
            }
        }
        cursor++
    }
    return if (!name.isNullOrBlank() && !version.isNullOrBlank()) {
        StdlibPackageManifest(name, version)
    } else {
        null
    }
}

/**
 * Candidate `std/` roots on this platform, most specific first.
 *
 * Ordering is the resolution policy, so it lives with the platform that can
 * actually see a filesystem. Targets without one (web) contribute nothing and
 * fall through to the bundled tree.
 */
internal expect fun stdlibDiskRoots(): List<StdlibRoot>

/**
 * A candidate root, why it was considered, and whether the user named it.
 *
 * [explicit] separates "you told me to use this" from "I went looking": a root
 * the user named must be used or complained about, never quietly skipped.
 */
internal data class StdlibRoot(
    val path: String,
    val origin: String,
    val explicit: Boolean = false,
)

/**
 * Reads every `.az` file under [root], or null when [root] is not a directory.
 *
 * Paths are returned relative to [root] with `/` separators, sorted, so a tree
 * read from disk and the bundled one are interchangeable.
 */
internal expect fun readStdlibTree(root: String): List<StdlibFile>?

/** Reads `package.azon` beside a stdlib root, or null when it is absent. */
internal expect fun readStdlibPackageManifest(root: String): String?
