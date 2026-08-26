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

import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Program

/**
 * The standard library, read from wherever this installation keeps it.
 *
 * `std/` is a tree of ordinary `.az` files, not a compiler implementation
 * detail. It is read from disk so that a user can open the source of what they
 * are calling, vendor a patched copy, or test a fix without rebuilding the
 * compiler - and so that a standard-library fix does not need a compiler
 * release.
 *
 * ## Resolution order
 *
 * The first root that exists wins. Every candidate carries the reason it was
 * considered, and [tree] reports the winner, so an unexpected standard library
 * can be traced back to what selected it.
 *
 *  1. [overrideRoot] - set programmatically by tools and tests.
 *  2. `AZORA_STDLIB` - an explicit path from the environment.
 *  3. The project's own `std/`, found by walking up from the working directory
 *     to the nearest workspace marker. A vendored standard library travels
 *     with the project that vendored it.
 *  4. The install prefix - `$AZORA_HOME/std`, else `std/` beside the running
 *     compiler. This is the ordinary case.
 *  5. The bundled tree. On the web there is no filesystem, so the same `.az`
 *     files ship with the compiler and are read through this in-memory
 *     filesystem - the same reader, not a second code path. On desktop it is
 *     the fallback that keeps a source checkout working before installation.
 *
 * ## Version handshake
 *
 * A disk root belongs to the package declared by `package.azon` beside it. The
 * manifest's `package.version` is checked against the standard library bundled
 * with this compiler. Package metadata therefore has one source of truth rather
 * than a second version-only marker hidden inside `std/`.
 */
object AzStdlib {

    /**
     * An explicit stdlib root, ahead of every other candidate.
     *
     * Setting it discards anything already parsed, so a tool can point the
     * compiler at a different standard library between compilations.
     */
    var overrideRoot: String? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Discards the cached tree and its parse, so the next use resolves again. */
    fun invalidate() {
        cachedTree = null
        cachedPrograms = null
        cachedLenientPrograms = null
        comptimeLists.clear()
        declaredEnums.clear()
        comptimeListScopes.clear()
    }

    private var cachedTree: StdlibTree? = null
    private var cachedPrograms: List<Program>? = null
    private var cachedLenientPrograms: List<Program>? = null

    /**
     * Compile-time lists bound by the standard library, e.g. `Numbers`.
     *
     * A list is bound while its file is parsed, so it has to outlive that parse
     * to be usable from a user module's `inline for`. Populated by
     * [loadPrograms]; empty until the standard library has been read.
     */
    val comptimeLists: MutableMap<String, List<String>> = mutableMapOf()

    /**
     * Enums declared by the standard library, and their variants.
     *
     * Shared for the same reason [comptimeLists] is: an enum-typed const
     * argument is resolved against every enum the compilation has seen.
     */
    val declaredEnums: MutableMap<String, List<String>> = mutableMapOf()

    /**
     * Compile-time list name -> the named scope that declared it.
     *
     * A list declares its elements the way they are written inside its own
     * scope (`Numbers` is `[Byte, …]`, bare). A consumer in another scope needs
     * them qualified, so the declaring scope travels with the list.
     */
    val comptimeListScopes: MutableMap<String, String> = mutableMapOf()

    /** The resolved standard library: origin, version and files. */
    fun tree(): StdlibTree = cachedTree ?: resolve().also { cachedTree = it }

    /** The standard library's source texts, in path order. */
    val sources: List<String> get() = tree().files.map { it.source }

    /**
     * Load the complete standard library. Compiler callers use strict mode so
     * a broken std source remains a real compilation error. Editor tooling can
     * use lenient mode so one migrating file does not disable all highlighting.
     */
    fun loadPrograms(strict: Boolean = true): List<Program> = if (strict) {
        cachedPrograms ?: parse(tree(), strict = true).also { cachedPrograms = it }
    } else {
        cachedLenientPrograms ?: parse(tree(), strict = false).also { cachedLenientPrograms = it }
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    private fun resolve(): StdlibTree {
        val candidates = buildList {
            overrideRoot?.let { add(StdlibRoot(it, "explicit override", explicit = true)) }
            addAll(stdlibDiskRoots())
        }
        for (candidate in candidates) {
            val files = readStdlibTree(candidate.path) ?: continue
            if (files.isEmpty()) continue
            val manifest = readStdlibPackageManifest(candidate.path)
                ?.let(::parseStdlibPackageManifest)
            if (manifest == null) {
                // A directory that merely happens to be called `std` is not one:
                // searched locations are inferred, and inferring wrongly must not
                // break a working install. A root the user *named* is different -
                // ignoring it silently would use a standard library they did not
                // ask for.
                if (!candidate.explicit) continue
                error(
                    "the standard library at '${candidate.path}' (${candidate.origin}) has no " +
                        "valid $STDLIB_PACKAGE_MANIFEST beside it; package.name and package.version " +
                        "are required - point AZORA_STDLIB at that package's std/ directory, or " +
                        "unset it to use the bundled standard library",
                )
            }
            if (manifest.name != AzStdlibBundle.PACKAGE_NAME) {
                if (!candidate.explicit) continue
                error(
                    "the standard library at '${candidate.path}' (${candidate.origin}) belongs to " +
                        "package '${manifest.name}', expected '${AzStdlibBundle.PACKAGE_NAME}'",
                )
            }
            checkVersion(manifest.version, "${candidate.origin} ('${candidate.path}')")
            return StdlibTree(candidate.origin, manifest.version, files)
        }
        return StdlibTree(
            origin = "bundled with the compiler",
            version = AzStdlibBundle.VERSION,
            files = AzStdlibBundle.files.map { (path, source) -> StdlibFile(path, source) },
        )
    }

    /**
     * A standard library built for another release is rejected here rather than
     * left to fail as an inexplicable error inside `std` later.
     */
    private fun checkVersion(found: String, where: String) {
        if (found == AzStdlibBundle.VERSION) return
        error(
            "standard library package version mismatch: this compiler bundles " +
                "${AzStdlibBundle.PACKAGE_NAME} ${AzStdlibBundle.VERSION}, but the standard " +
                "library from $where is $found",
        )
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /**
     * Parses every module against one shared compile-time environment.
     *
     * The environment is shared across files because a list bound in one
     * (`Numbers`, in `primitive.az`) is iterated by `inline for` in another. It
     * is rebuilt from empty on each parse so a re-resolved standard library
     * cannot inherit bindings from the one it replaced.
     */
    private fun parse(tree: StdlibTree, strict: Boolean): List<Program> {
        comptimeLists.clear()
        declaredEnums.clear()
        comptimeListScopes.clear()
        return tree.files.map { file ->
            try {
                Parser(
                    Lexer(file.source).tokenize(),
                    comptimeLists,
                    declaredEnums,
                    typeListScope = comptimeListScopes,
                ).parse()
            } catch (e: Exception) {
                // Keep one placeholder per source in lenient mode. AZLS uses
                // the parallel list to retain the correct file/documentation
                // association for every successfully parsed sibling.
                if (!strict) return@map Program(null, emptyList())
                error(
                    "Failed to parse standard library source ${file.path} " +
                        "(${tree.origin}): ${e.message}",
                )
            }
        }
    }
}
