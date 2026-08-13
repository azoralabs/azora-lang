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

/** The file at a stdlib root that states which language version it is for. */
const val STDLIB_VERSION_FILE = "STDLIB_VERSION"

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

/** Reads one text file under a stdlib root, or null when it is absent. */
internal expect fun readStdlibFile(root: String, relativePath: String): String?
