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

/**
 * Web/WASM: there is no filesystem to search, so no disk root is offered.
 *
 * The standard library still arrives as the same `.az` files read through the
 * same reader - they ship inside the compiler artifact rather than beside it,
 * and [AzStdlib] falls through to that bundled tree when no disk root answers.
 * There is no second code path for this target.
 */
internal actual fun stdlibDiskRoots(): List<StdlibRoot> = emptyList()

internal actual fun readStdlibTree(root: String): List<StdlibFile>? = null

internal actual fun readStdlibPackageManifest(root: String): String? = null
