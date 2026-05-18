/*
 * Copyright 2026 AzoraTech
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

package org.azora.lang.semantic

/**
 * Semantic — Import / Dependency Resolution.
 *
 * Resolves cross-module references and builds a dependency graph.
 * In the current minimal language (single-file, no `use` keyword),
 * this is a no-op pass. When imports are added, this pass will:
 *
 *  1. Parse `use` declarations from the AST.
 *  2. Locate the referenced modules (files or packages).
 *  3. Build a dependency graph and detect cycles.
 *  4. Merge exported symbols from dependencies into the [SymbolTable].
 *
 * This runs AFTER [SymbolCollector] (so the current module's symbols are
 * registered) and BEFORE [TypeResolver] (so imported symbols are available
 * for type checking).
 */
class ImportResolver {

    /**
     * Modules available for import. In the future this would be populated
     * by the build system or a module loader.
     */
    private val modules = mutableMapOf<String, SymbolTable>()

    /**
     * Registers a module's symbol table so other modules can import it.
     *
     * @param name the module name (e.g. package name)
     * @param table the symbol table containing the module's exported symbols
     */
    fun registerModule(name: String, table: SymbolTable) {
        modules[name] = table
    }

    /**
     * Resolves imports for the given module. Currently a no-op since the
     * minimal language has no `use` keyword.
     *
     * @param packageName the package name of the module being resolved, or `null`
     * @param table the module's symbol table to merge imported symbols into
     * @return a list of error messages (empty if successful)
     */
    fun resolve(packageName: String?, table: SymbolTable): List<String> {
        // No imports in the minimal language — nothing to resolve.
        // When `use` is added:
        //   1. Walk AST for UseDecl nodes
        //   2. Look up each module in `modules` map
        //   3. Copy exported symbols into `table`
        //   4. Report errors for missing modules or cyclic imports
        return emptyList()
    }
}
