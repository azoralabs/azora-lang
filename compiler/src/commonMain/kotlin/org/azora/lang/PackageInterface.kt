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

package org.azora.lang

import org.azora.lang.backend.LlvmUnits
import org.azora.lang.frontend.CallableKind
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.TopLevel
import org.azora.lang.ir.IrOptimizer
import org.azora.lang.ir.IrType
import org.azora.lang.ir.OwnershipCleanup
import org.azora.lang.ir.memberSymbol
import org.azora.lang.ir.ctorCount
import org.azora.lang.stdlib.AzStdlib
import org.azora.lang.stdlib.programDeclaredNames

/**
 * The code of a program's packages, compiled once and kept, with what a program
 * built on them needs to know of it.
 *
 * A game's packages (the Engine's) are most of what it compiles, and they do
 * not change while the game is edited. The interface holds their functions as
 * LLVM parts ([parts]) and, for each, what the program around it must agree on:
 * its signature ([functions]), what its code needs the program to define
 * ([demands], [imports]), the numbers its code was compiled with (spec type ids,
 * witness descriptors), and the names the packages' declarations had.
 *
 * A program built on the interface is still given every declaration: its own
 * code is checked and compiled against them as before. A precompiled function's
 * body is neither analysed nor generated again (`FuncDecl.precompiled`); the
 * program declares it, and the interface's object is linked in its place.
 *
 * What depends on the program as a whole stays the program's to compile: a
 * function that enumerates `reflect<*>`, a specialization over a program's type,
 * the dispatch functions over every type's descriptor or spec implementation,
 * and every variable with its initializer. The interface's parts call those by
 * name.
 *
 * Nothing here relies on it being right by luck. Where the program disagrees
 * with the interface - a name, a signature, a type's layout, a spec's
 * implementations - [PackageBuild] compiles the whole program as before.
 */
class PackageInterface(
    val key: String,
    /** The packages' declarations compiled, as `module::name`. */
    val roots: Set<String>,
    /** The functions (canonical IR names) the packages' compile kept: those programs reached. */
    val keep: Set<String>,
    /** Each precompiled function's canonical name → its declaration (`declare …`). */
    val functions: Map<String, String>,
    /** Each precompiled function's name as lowered from the tree → its canonical name. */
    val lowered: Map<String, String>,
    /** What each precompiled function's code needs of the program (see [IrOptimizer.Demand]). */
    val demands: Map<String, IrOptimizer.Demand>,
    /** Each precompiled function's array parameters whose elements it reads (see `ElementReads`). */
    val elementReads: Map<String, Set<Int>>,
    /** The spec type ids its code stores in dynamic values. */
    val typeIds: Map<String, Int>,
    /** The spec implementations its code may hand to dynamic dispatch. */
    val specImpls: List<SpecImpl>,
    /** The witness descriptors its code passes, in descriptor order, with what each is used for. */
    val witnesses: List<Pair<IrType, Set<String>>>,
    /** The witness dispatch functions (as lowered) its code calls. */
    val witnessDispatchers: Set<String>,
    /** `module::name` → the name that declaration had in the packages' compile. */
    val naming: Map<String, String>,
    /** What its parts declare that the program must define → that declaration. */
    val imports: Map<String, String>,
    /** The named types its parts define → their definitions. */
    val types: Map<String, String>,
    /** Variables only its code uses that no initializer sets (reactive storage, the error slot) → their definitions. */
    val supplies: Map<String, String>,
    /** Its parts' file names, beside the interface. */
    val parts: List<String>,
    /** What each precompiled function keeps, hands back and allocates (see [OwnershipCleanup.Facts]). */
    val ownership: OwnershipCleanup.Facts,
    /** The same, as its code assumed them, of the program's functions it calls. */
    val expectations: OwnershipCleanup.Facts,
) {
    /** A type that implements a spec, and the function each of the spec's methods calls for it. */
    data class SpecImpl(val spec: String, val type: String, val methods: Map<String, String>)

    fun render(): String = buildString {
        fun record(vararg fields: String) = append(fields.joinToString("\t") { escape(it) }).append('\n')
        record(FORMAT, VERSION.toString())
        record("key", key)
        roots.sorted().forEach { record("root", it) }
        keep.sorted().forEach { record("keep", it) }
        for ((name, declaration) in sortedByKey(functions)) record("function", name, declaration)
        for ((name, canonical) in sortedByKey(lowered)) record("lowered", name, canonical)
        for ((name, demand) in sortedByKey(demands)) {
            record("demand", name, words(demand.names), words(demand.members), words(demand.operators), words(demand.types))
        }
        for ((name, reads) in sortedByKey(elementReads)) record("reads", name, reads.sorted().joinToString(","))
        for ((type, id) in sortedByKey(typeIds)) record("typeid", type, id.toString())
        for (impl in specImpls) {
            record("spec", impl.spec, impl.type, *sortedByKey(impl.methods).flatMap { listOf(it.first, it.second) }.toTypedArray())
        }
        for ((type, needs) in witnesses) record("witness", IrTypeCodec.encode(type), needs.sorted().joinToString(","))
        witnessDispatchers.sorted().forEach { record("dispatcher", it) }
        for ((identity, name) in sortedByKey(naming)) record("naming", identity, name)
        for ((name, line) in sortedByKey(imports)) record("import", name, line)
        for ((name, line) in sortedByKey(types)) record("type", name, line)
        for ((name, line) in sortedByKey(supplies)) record("supply", name, line)
        parts.forEach { record("part", it) }
        fun facts(kind: String, facts: OwnershipCleanup.Facts) {
            for (name in facts.kept.keys.sorted()) {
                record(
                    kind, name, facts.kept.getValue(name).sorted().joinToString(","),
                    facts.returned[name].orEmpty().sorted().joinToString(","), if (name in facts.fresh) "fresh" else "-",
                )
            }
        }
        facts("owns", ownership)
        facts("expects", expectations)
        record("end")
    }

    companion object {
        const val FORMAT = "azora-package-interface"
        const val VERSION = 1

        /** The interface [text] records, or null when it is not one (another version, or cut short). */
        fun parse(text: String): PackageInterface? {
            val lines = text.split('\n').filter { it.isNotEmpty() }.map { line -> line.split('\t').map(::unescape) }
            if (lines.firstOrNull() != listOf(FORMAT, VERSION.toString()) || lines.last() != listOf("end")) return null
            var key = ""
            val roots = LinkedHashSet<String>()
            val keep = LinkedHashSet<String>()
            val functions = LinkedHashMap<String, String>()
            val lowered = LinkedHashMap<String, String>()
            val demands = LinkedHashMap<String, IrOptimizer.Demand>()
            val reads = LinkedHashMap<String, Set<Int>>()
            val typeIds = LinkedHashMap<String, Int>()
            val specImpls = ArrayList<SpecImpl>()
            val witnesses = ArrayList<Pair<IrType, Set<String>>>()
            val dispatchers = LinkedHashSet<String>()
            val naming = LinkedHashMap<String, String>()
            val imports = LinkedHashMap<String, String>()
            val types = LinkedHashMap<String, String>()
            val supplies = LinkedHashMap<String, String>()
            val parts = ArrayList<String>()
            val facts = mapOf("owns" to Triple(LinkedHashMap<String, Set<Int>>(), LinkedHashMap<String, Set<Int>>(), LinkedHashSet<String>()),
                "expects" to Triple(LinkedHashMap<String, Set<Int>>(), LinkedHashMap<String, Set<Int>>(), LinkedHashSet<String>()))
            fun ints(text: String) = text.split(',').filter { it.isNotEmpty() }.mapTo(LinkedHashSet()) { it.toInt() }
            for (fields in lines.drop(1).dropLast(1)) {
                fun at(i: Int) = fields.getOrElse(i) { "" }
                when (fields[0]) {
                    "key" -> key = at(1)
                    "root" -> roots += at(1)
                    "keep" -> keep += at(1)
                    "function" -> functions[at(1)] = at(2)
                    "lowered" -> lowered[at(1)] = at(2)
                    "demand" -> demands[at(1)] = IrOptimizer.Demand(set(at(2)), set(at(3)), set(at(4)), set(at(5)))
                    "reads" -> reads[at(1)] = at(2).split(',').filter { it.isNotEmpty() }.mapTo(LinkedHashSet()) { it.toInt() }
                    "typeid" -> typeIds[at(1)] = at(2).toInt()
                    "spec" -> specImpls += SpecImpl(at(1), at(2), fields.drop(3).chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] })
                    "witness" -> witnesses += IrTypeCodec.decode(at(1)) to at(2).split(',').filter { it.isNotEmpty() }.toSet()
                    "dispatcher" -> dispatchers += at(1)
                    "naming" -> naming[at(1)] = at(2)
                    "import" -> imports[at(1)] = at(2)
                    "type" -> types[at(1)] = at(2)
                    "supply" -> supplies[at(1)] = at(2)
                    "part" -> parts += at(1)
                    "owns", "expects" -> facts.getValue(fields[0]).let { (kept, returned, fresh) ->
                        kept[at(1)] = ints(at(2))
                        returned[at(1)] = ints(at(3))
                        if (at(4) == "fresh") fresh += at(1)
                    }
                    else -> return null
                }
            }
            return PackageInterface(
                key, roots, keep, functions, lowered, demands, reads, typeIds, specImpls, witnesses, dispatchers,
                naming, imports, types, supplies, parts,
                facts.getValue("owns").let { OwnershipCleanup.Facts(it.first, it.second, it.third) },
                facts.getValue("expects").let { OwnershipCleanup.Facts(it.first, it.second, it.third) },
            )
        }

        private fun words(set: Set<String>): String = set.filter { it.isNotEmpty() }.sorted().joinToString(" ")

        /** [map]'s entries in key order. */
        internal fun <V> sortedByKey(map: Map<String, V>): List<Pair<String, V>> = map.entries.sortedBy { it.key }.map { it.key to it.value }
        private fun set(text: String): Set<String> = text.split(' ').filter { it.isNotEmpty() }.toSet()

        private fun escape(text: String): String = buildString {
            for (c in text) when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }

        private fun unescape(text: String): String {
            if ('\\' !in text) return text
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    out.append(when (text[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> text[i + 1] })
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        /**
         * [program] with the functions [lowered] names marked precompiled
         * (members by the name `memberSymbol` lowers them under), and the names
         * it marked.
         */
        internal fun mark(program: Program, lowered: Set<String>): Pair<Program, Set<String>> {
            if (lowered.isEmpty()) return program to emptySet()
            val marked = LinkedHashSet<String>()
            val overloaded = program.items.filterIsInstance<TopLevel.Impl>()
                .filter { ctorCount(it.methods.map { m -> m.name }) > 1 }
                .mapTo(HashSet()) { it.typeName }
            val items = program.items.map { item ->
                when (item) {
                    is TopLevel.Func -> if (!item.decl.isInline && item.decl.name in lowered) {
                        marked += item.decl.name
                        item.copy(decl = item.decl.copy(precompiled = true))
                    } else item
                    is TopLevel.Impl -> if (item.isBridge) item else item.copy(methods = item.methods.map { method ->
                        val name = memberSymbol(item.typeName, method, item.typeName in overloaded)
                        if (!method.isInline && name in lowered) {
                            marked += name
                            method.copy(precompiled = true)
                        } else method
                    })
                    is TopLevel.Solo -> item.copy(methods = item.methods.map { method ->
                        val name = memberSymbol(item.name, method, false)
                        if (!method.isInline && name in lowered) {
                            marked += name
                            method.copy(precompiled = true)
                        } else method
                    })
                    else -> item
                }
            }
            return program.copy(items = items) to marked
        }
    }
}

/**
 * What one compile takes from, and reports to, a [PackageBuild]: compiling the
 * packages themselves ([packages]), or a program on their interface ([use]), or
 * - with neither - the whole program, reporting what it reached.
 */
internal class InterfaceContext(
    val packages: Packages? = null,
    val use: PackageInterface? = null,
) {
    /**
     * Compiling the packages for a program: in the program's naming (its own
     * module, its imports of the packages, the names it declares), without
     * the program's own code, injecting [roots] and keeping [keep].
     */
    class Packages(
        val programModules: Set<String>,
        val shadowed: Set<String>,
        val roots: Set<Pair<String, String>>,
        val keep: Set<String>,
    )

    /** Library declarations injected, as `module::name`. */
    var injected: Set<String> = emptySet()
    /** `module::name` → the name each injected declaration took. */
    var naming: Map<String, String> = emptyMap()
    /** The functions (as lowered) whose code enumerates the program; see `ReflectDecoExpander.enumerating`. */
    var enumerating: Set<String> = emptySet()
    /** Each function's name as lowered → its canonical IR name. */
    var functionNames: Map<String, String> = emptyMap()
    var witnessTable: List<Pair<IrType, Set<String>>> = emptyList()
    var witnessDispatchers: Set<String> = emptySet()
    var ownership = OwnershipCleanup.Facts()
    var typeIds: Map<String, Int> = emptyMap()
    var elementReads: (String) -> Set<Int>? = { null }
    /** What [use] marked precompiled in the tree (as lowered). */
    var marked: Set<String> = emptySet()
    /** Why the program cannot be built on [use], found while compiling it. */
    var mismatch: String? = null
}

/**
 * Builds a program on its packages' interface, keeping the interface in [store]
 * under a key made of everything its code follows from: the packages' sources,
 * the standard library, the compiler ([compilerIdentity]), the defines, and the
 * program's naming of the packages (its module, its imports of them, the names
 * it declares).
 *
 * - With an interface for that key, the program is compiled on it; the result
 *   is checked against the interface and, where they disagree, the whole
 *   program is compiled instead.
 * - Without one, the whole program is compiled, and the interface is made from
 *   what it reached, for the builds after it.
 * - When the program comes to use a package declaration the interface lacks,
 *   it compiles that declaration itself, and the interface is made again with
 *   it.
 */
class PackageBuild(
    private val store: Store,
    private val compilerIdentity: String,
    /** Which library sources are packages (the rest are the program's own modules). */
    private val isPackageSource: (LibrarySource) -> Boolean,
) {
    /** Files under the interface cache's root, by relative path. */
    interface Store {
        fun read(path: String): String?
        /** Writes [text] at [path] whole: a reader never sees part of it. */
        fun write(path: String, text: String)
        /** The entries directly in [directory] (names, not paths); empty when there is none. */
        fun list(directory: String): List<String>
        /** Removes a file, or a directory with everything in it. */
        fun remove(path: String)
    }

    /**
     * A build's outcome: the program's [parts], the interface parts to link
     * with them ([link]) and those to compile now for later builds
     * ([prepare]), as paths under the store's root, and what happened
     * ([notes]).
     */
    class Outcome(
        val parts: List<LlvmUnits.Part>,
        val link: List<String>,
        val prepare: List<String>,
        val notes: List<String>,
    )

    fun compile(
        entry: String,
        libraries: List<LibrarySource>,
        parts: Int,
        defines: Map<String, String>,
        release: Boolean,
    ): Pair<Outcome?, CompilationResult.Failure?> {
        val notes = ArrayList<String>()
        val packageLibraries = libraries.filter(isPackageSource)
        val programLibraries = libraries.filterNot(isPackageSource)
        val programModules = programLibraries.mapNotNull { moduleOf(it.source) }.toSet()
        val packageModules = packageLibraries.mapNotNull { moduleOf(it.source) }.toSet()
        val program = try {
            AzStdlib.loadPrograms()
            Parser(
                Lexer(entry).tokenize(),
                AzStdlib.comptimeLists.toMutableMap(),
                AzStdlib.declaredEnums.toMutableMap(),
                typeListScope = AzStdlib.comptimeListScopes.toMutableMap(),
            ).parse()
        } catch (_: IllegalStateException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (program == null || packageLibraries.isEmpty()) return whole(entry, libraries, parts, defines, release, notes)
        // A name the program declares matters to the packages only where one of
        // theirs, or the standard library's, is spelled the same: that one then
        // takes its module's identity. Other names leave the interface as it is.
        val words = HashSet<String>()
        for (text in packageLibraries.map { it.source } + AzStdlib.tree().files.map { it.source }) identifiers(text, words)
        val declared = programDeclaredNames(program).filterTo(LinkedHashSet()) { it in words }
        val key = key(program, programModules, declared, packageLibraries, defines, release)
        // Interfaces made for other states of the packages are not used again.
        for (name in store.list("")) if (name != key) store.remove(name)

        val failed = store.read("$key/$FAILED")
        val existing = store.read("$key/$INTERFACE")?.let(PackageInterface::parse)?.takeIf { it.key == key }
        if (existing != null) {
            when (val built = onInterface(entry, libraries, existing, packageModules, programModules, programDeclaredNames(program), parts, defines, release)) {
                is OnInterface.Built -> {
                    notes += "package interface used: ${existing.functions.size} functions precompiled"
                    var prepare = emptyList<String>()
                    if (built.missing.isNotEmpty() && failed == null) {
                        // The program now uses package declarations the interface
                        // lacks; it compiled them itself, and the interface is made
                        // again with them for the next build.
                        notes += "package interface extended by ${built.missing.size} declarations"
                        val remade = makeInterface(
                            key, entry, packageLibraries, programModules, declared,
                            existing.roots.mapNotNull(::identity).toSet() + built.missing.mapNotNull(::identity),
                            existing.keep + built.functions, defines, release, parts,
                            keepFiles = existing.parts.toSet(), notes = notes,
                        )
                        if (remade != null) prepare = remade.parts.map { "$key/$it" }
                    }
                    return Outcome(built.parts, existing.parts.map { "$key/$it" }, prepare, notes) to null
                }
                is OnInterface.Refused -> notes += "package interface not used: ${built.reason}"
            }
            // Its objects stay compiled for the builds that can use it again.
            return whole(entry, libraries, parts, defines, release, notes, prepare = existing.parts.map { "$key/$it" })
        }

        // No interface yet: the whole program now, and the interface from what it reached.
        val context = InterfaceContext()
        val result = Compiler(libraries).compileWithContext(entry, defines, release, context)
        if (result !is CompilationResult.Success) return null to (result as CompilationResult.Failure)
        val llvmError = result.backendErrors["llvm"]
        if (llvmError != null) return null to CompilationResult.Failure(listOf("llvm target is unsupported for this program: $llvmError"))
        var prepare = emptyList<String>()
        if (failed == null) {
            val roots = context.injected.mapNotNull(::identity).filterTo(LinkedHashSet()) { it.first in packageModules }
            val made = makeInterface(
                key, entry, packageLibraries, programModules, declared, roots,
                result.optimizedIr.functions.mapTo(LinkedHashSet()) { it.name }, defines, release, parts,
                keepFiles = emptySet(), notes = notes,
            )
            if (made != null) prepare = made.parts.map { "$key/$it" }
        } else {
            notes += "package interface unavailable: ${failed.trim()}"
        }
        return Outcome(LlvmUnits.split(result.llvm, parts), emptyList(), prepare, notes) to null
    }

    private fun whole(
        entry: String,
        libraries: List<LibrarySource>,
        parts: Int,
        defines: Map<String, String>,
        release: Boolean,
        notes: List<String>,
        prepare: List<String> = emptyList(),
    ): Pair<Outcome?, CompilationResult.Failure?> {
        val result = Compiler(libraries).compileWithContext(entry, defines, release, null)
        if (result !is CompilationResult.Success) return null to (result as CompilationResult.Failure)
        result.backendErrors["llvm"]?.let {
            return null to CompilationResult.Failure(listOf("llvm target is unsupported for this program: $it"))
        }
        return Outcome(LlvmUnits.split(result.llvm, parts), emptyList(), prepare, notes) to null
    }

    private sealed class OnInterface {
        class Built(val parts: List<LlvmUnits.Part>, val missing: Set<String>, val functions: Set<String>) : OnInterface()
        class Refused(val reason: String) : OnInterface()
    }

    /** The program compiled on [iface], or why it cannot be. */
    private fun onInterface(
        entry: String,
        libraries: List<LibrarySource>,
        iface: PackageInterface,
        packageModules: Set<String>,
        programModules: Set<String>,
        declared: Set<String>,
        parts: Int,
        defines: Map<String, String>,
        release: Boolean,
    ): OnInterface {
        val context = InterfaceContext(use = iface)
        val result = Compiler(libraries).compileWithContext(entry, defines, release, context)
        if (result !is CompilationResult.Success) {
            return OnInterface.Refused("the program does not compile on it (${(result as CompilationResult.Failure).errors.firstOrNull().orEmpty().take(200)})")
        }
        result.backendErrors["llvm"]?.let { return OnInterface.Refused("llvm: $it") }
        context.mismatch?.let { return OnInterface.Refused(it) }

        // Names: the packages' declarations must be named as the interface named
        // them, and none of the program's own may take a name the interface's
        // code uses.
        for ((identity, name) in context.naming) {
            val module = identity.substringBefore("::")
            if (module in packageModules) {
                val expected = iface.naming[identity] ?: continue
                if (expected != name) return OnInterface.Refused("$identity is '$name' here, '$expected' in the interface")
            } else if (module in programModules && (name in iface.lowered || name in iface.functions || name in iface.imports)) {
                return OnInterface.Refused("the program's $identity takes the name '$name' the interface uses")
            }
        }
        for (name in declared) {
            if (name in iface.lowered || name in iface.functions) return OnInterface.Refused("the program's '$name' takes a name the interface uses")
        }

        // Spec implementations the interface's code may dispatch to must be the program's too.
        val tables = HashMap<Pair<String, String>, Map<String, String>>()
        for (table in result.optimizedIr.specTables) for (impl in table.impls) tables[table.specName to impl.typeName] = impl.methodFuncs
        for (impl in iface.specImpls) {
            val here = tables[impl.spec to impl.type]
                ?: return OnInterface.Refused("the program does not implement ${impl.spec} for ${impl.type} as the interface does")
            if (here != impl.methods) {
                return OnInterface.Refused("${impl.type}'s ${impl.spec} methods are ${PackageInterface.sortedByKey(here)} here, ${PackageInterface.sortedByKey(impl.methods)} in the interface")
            }
        }

        // What the interface's code assumed of the program's functions it calls.
        val facts = context.ownership
        for ((name, kept) in iface.expectations.kept) {
            val here = Triple(facts.kept[name], facts.returned[name], name in facts.fresh)
            val there = Triple(kept, iface.expectations.returned[name].orEmpty(), name in iface.expectations.fresh)
            if (here.first != null && here != Triple(there.first, there.second, there.third)) {
                return OnInterface.Refused("@$name keeps, returns or allocates otherwise than the interface's code assumes")
            }
        }

        // The module against the interface's parts: signatures, what they use, types.
        val listing = LlvmUnits.listing(result.llvm)
        for ((name, declaration) in iface.functions) {
            if (name in listing.functions) return OnInterface.Refused("the program defines @$name, which the interface precompiled")
            val here = listing.declarations[name] ?: continue
            if (here != declaration) return OnInterface.Refused("@$name is '$here' here, '$declaration' in the interface")
        }
        val supplied = StringBuilder()
        for ((name, line) in iface.imports) {
            val here = when {
                name in listing.functions && name !in listing.localFunctions -> listing.functions.getValue(name)
                name in listing.variables && !listing.variableDefinitions.getValue(name).contains(" = external ") -> listing.variables.getValue(name)
                else -> null
            }
            if (here == null) {
                val supply = iface.supplies[name]
                    ?: return OnInterface.Refused("the program does not define @$name, which the interface's code uses")
                supplied.append(supply).append('\n')
                continue
            }
            if (here != line) return OnInterface.Refused("@$name is '$here' here, '$line' in the interface's code")
        }
        for ((name, definition) in iface.types) {
            val here = listing.types[name] ?: continue
            if (here != definition) return OnInterface.Refused("%$name is laid out as '$here' here, '$definition' in the interface")
        }

        val module = if (supplied.isEmpty()) result.llvm else result.llvm + "\n" + supplied
        val missing = context.injected.filterTo(LinkedHashSet()) { identity ->
            identity.substringBefore("::") in packageModules && identity !in iface.roots
        }
        return OnInterface.Built(
            LlvmUnits.split(module, parts),
            missing,
            result.optimizedIr.functions.mapTo(LinkedHashSet()) { it.name },
        )
    }

    /**
     * Compiles the packages for the program [entry] names them in, keeps the
     * interface under [key], and returns it; null (with a note, and a mark so
     * later builds do not try again) when they do not compile on their own.
     */
    private fun makeInterface(
        key: String,
        entry: String,
        packageLibraries: List<LibrarySource>,
        programModules: Set<String>,
        declared: Set<String>,
        roots: Set<Pair<String, String>>,
        keep: Set<String>,
        defines: Map<String, String>,
        release: Boolean,
        parts: Int,
        keepFiles: Set<String>,
        notes: MutableList<String>,
    ): PackageInterface? {
        val context = InterfaceContext(packages = InterfaceContext.Packages(programModules, declared, roots, keep))
        val result = try {
            Compiler(packageLibraries).compileWithContext(entry, defines, release, context)
        } catch (error: IllegalStateException) {
            CompilationResult.Failure(listOf(error.message ?: "internal error"))
        }
        val failure = when {
            result is CompilationResult.Failure -> result.errors.firstOrNull() ?: "unknown error"
            (result as CompilationResult.Success).backendErrors["llvm"] != null -> "llvm: ${result.backendErrors["llvm"]}"
            else -> null
        }
        if (failure != null) {
            notes += "package interface not made: the packages do not compile on their own ($failure)"
            store.write("$key/$FAILED", failure + "\n")
            return null
        }
        val success = result as CompilationResult.Success
        val module = success.llvm
        val listing = LlvmUnits.listing(module)
        val canonical = context.functionNames
        // Left to the program: code that enumerates the program, the dispatchers
        // over every type, the runtime and the per-part copies.
        val programOwned = (context.enumerating + context.witnessDispatchers).mapNotNullTo(HashSet()) { canonical[it] }
        val precompiled = success.optimizedIr.functions.map { it.name }.filterTo(LinkedHashSet()) { name ->
            name in listing.functions && name !in listing.localFunctions && !LlvmUnits.isCopied(name) &&
                !name.startsWith("__azora_") && name != "main" && name !in programOwned
        }
        // What the code generator wrote beside the program's functions and the
        // same for any program - an intrinsic's body - goes with the parts. The
        // spec dispatchers cover every implementation, so they are the program's.
        val irFunctions = success.optimizedIr.functions.mapTo(HashSet()) { it.name }
        val helpers = listing.functions.keys.filterTo(HashSet()) { name ->
            name !in irFunctions && name !in listing.localFunctions && !LlvmUnits.isCopied(name) &&
                !name.startsWith("__azora_") && !name.startsWith(SPEC_DISPATCHER)
        }
        val packageParts = LlvmUnits.splitOnly(module, parts, precompiled, helpers).filter { it.name != LlvmUnits.GLOBALS }
        // What the parts define, declare and lay out.
        val defined = HashSet<String>()
        val used = LinkedHashMap<String, String>()
        val types = LinkedHashMap<String, String>()
        for (part in packageParts) {
            val partListing = LlvmUnits.listing(part.text)
            defined += partListing.functions.keys - partListing.localFunctions
            used.putAll(partListing.declarations)
            for ((name, definition) in partListing.variableDefinitions) if (" = external " in definition) used[name] = definition
            for ((name, definition) in partListing.types) if (!(name.startsWith("azora.") && NUMBERED.containsMatchIn(name))) types[name] = definition
        }
        val globals = success.optimizedIr.globals.mapNotNullTo(HashSet()) { stmt ->
            when (stmt) {
                is org.azora.lang.ir.IrStmt.VarDecl -> stmt.name
                is org.azora.lang.ir.IrStmt.FinDecl -> stmt.name
                is org.azora.lang.ir.IrStmt.LetDecl -> stmt.name
                else -> null
            }
        }
        val imports = LinkedHashMap<String, String>()
        val supplies = LinkedHashMap<String, String>()
        for ((name, line) in used) {
            if (name in defined) continue
            val ownFunction = name in listing.functions && name !in listing.localFunctions
            val ownVariable = name in listing.variables
            if (!ownFunction && !ownVariable) continue // the system's: the linker finds it
            imports[name] = if (line.startsWith("declare ")) line else listing.variables[name] ?: line
            if (ownVariable && name !in globals) supplies[name] = listing.variableDefinitions.getValue(name)
        }
        val lowered = canonical.filterValues { it in precompiled }
        val demands = IrOptimizer().demandsOf(success.optimizedIr, precompiled)
        val reads = precompiled.mapNotNull { name -> context.elementReads(name)?.let { name to it } }.toMap()
        val specImpls = success.optimizedIr.specTables.flatMap { table ->
            table.impls.map { PackageInterface.SpecImpl(table.specName, it.typeName, it.methodFuncs) }
        }
        val partFiles = packageParts.map { part -> "${Sha256.hex(part.text).take(24)}.ll" to part.text }
        for ((file, text) in partFiles) if (store.read("$key/$file") != text) store.write("$key/$file", text)
        val iface = PackageInterface(
            key = key,
            roots = roots.mapTo(LinkedHashSet()) { "${it.first}::${it.second}" },
            keep = keep,
            functions = precompiled.associateWith { listing.functions.getValue(it) },
            lowered = lowered,
            demands = demands,
            elementReads = reads,
            typeIds = context.typeIds,
            specImpls = specImpls,
            witnesses = context.witnessTable,
            witnessDispatchers = context.witnessDispatchers,
            naming = context.naming,
            imports = imports,
            types = types,
            supplies = supplies,
            parts = partFiles.map { it.first },
            ownership = context.ownership.let { all ->
                OwnershipCleanup.Facts(
                    all.kept.filterKeys { it in precompiled }, all.returned.filterKeys { it in precompiled }, all.fresh.filterTo(HashSet()) { it in precompiled },
                )
            },
            expectations = context.ownership.let { all ->
                OwnershipCleanup.Facts(
                    all.kept.filterKeys { it in imports }, all.returned.filterKeys { it in imports }, all.fresh.filterTo(HashSet()) { it in imports },
                )
            },
        )
        store.write("$key/$INTERFACE", iface.render())
        // Parts of an earlier interface for these sources, unless this build links them.
        val current = iface.parts.toSet() + keepFiles + setOf(INTERFACE, FAILED)
        for (name in store.list(key)) if (name !in current) store.remove("$key/$name")
        notes += "package interface made: ${precompiled.size} functions in ${iface.parts.size} parts"
        return iface
    }

    /** Everything the interface's code follows from, hashed. */
    private fun key(
        program: Program,
        programModules: Set<String>,
        declared: Set<String>,
        packages: List<LibrarySource>,
        defines: Map<String, String>,
        release: Boolean,
    ): String {
        val hash = Sha256()
        fun field(text: String) { hash.update(text); hash.update("\u0000") }
        field("${PackageInterface.FORMAT} ${PackageInterface.VERSION}")
        field(compilerIdentity)
        field(if (release) "release" else "debug")
        for (source in packages.sortedBy { it.path }) { field(source.path); field(source.source) }
        val std = AzStdlib.tree()
        field(std.version)
        for (file in std.files.sortedBy { it.path }) { field(file.path); field(file.source) }
        for ((name, value) in PackageInterface.sortedByKey(defines)) field("$name=$value")
        field(program.moduleName.orEmpty())
        for (module in programModules.sorted()) field("module $module")
        // What each import binds - not where it is written.
        for (item in program.items.filterIsInstance<TopLevel.UseImport>()) {
            val leaves = item.importSpecs.joinToString(";") { spec ->
                val selected = if (spec.selector is org.azora.lang.frontend.ImportSpec.Selector.All) "*" else ""
                "${spec.path}$selected-${spec.without.sorted()}=${spec.alias.orEmpty()}"
            }
            val condition = item.condition?.toString()?.replace(POSITION, "").orEmpty()
            field("import ${item.exported} $condition $leaves")
        }
        for (name in declared.sorted()) field("declares $name")
        return hash.hex()
    }

    private fun identity(text: String): Pair<String, String>? {
        val at = text.indexOf("::")
        return if (at <= 0) null else text.substring(0, at) to text.substring(at + 2)
    }

    companion object {
        const val INTERFACE = "interface.txt"
        const val FAILED = "failed.txt"
        /** The prefix of the spec dispatchers the code generator writes for a program's spec tables. */
        private const val SPEC_DISPATCHER = "__dyn_"
        private val NUMBERED = Regex("""\.[0-9]+$""")

        /** Every identifier-shaped word of [text], into [into]. */
        private fun identifiers(text: String, into: MutableSet<String>) {
            var at = 0
            while (at < text.length) {
                val c = text[at]
                if (c == '_' || c in 'A'..'Z' || c in 'a'..'z') {
                    val start = at
                    while (at < text.length && text[at].let { it == '_' || it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) at++
                    into += text.substring(start, at)
                } else if (c in '0'..'9') {
                    while (at < text.length && text[at].let { it == '_' || it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) at++
                } else {
                    at++
                }
            }
        }
        /** Where a node was written, in a tree's text: not what it means. */
        private val POSITION = Regex("(?:line|column|length)=[0-9]+,? ?")
        private val MODULE_HEADER = Regex("(?m)^\\s*(?:(?:export|exposed|confined|protected)\\s+)*module\\s+([A-Za-z_][\\w.]*)")

        /** The module a source declares, from its header. */
        fun moduleOf(source: String): String? = MODULE_HEADER.find(source)?.groupValues?.get(1)
    }
}

/** An `IrType` as text and back, for what a package interface records of types. */
internal object IrTypeCodec {
    fun encode(type: IrType): String = buildString { write(type, this) }

    fun decode(text: String): IrType {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        var at = 0
        fun next(): String = tokens[at++]
        fun read(): IrType {
            return when (val token = next()) {
                "S" -> IrType.String
                "B" -> IrType.Bool
                "U" -> IrType.Unit
                "N" -> IrType.Nothing
                "C" -> IrType.Char
                "Is" -> IrType.ISize
                "Us" -> IrType.USize
                "D" -> IrType.Double
                "H" -> IrType.Half
                "F" -> IrType.Float
                "Q" -> IrType.Quad
                "A" -> IrType.Any
                "i", "u" -> IrType.Integer(next().toInt(), signed = token == "i")
                "[" -> IrType.Array(read(), next().let { if (it == "_") null else it.toLong() })
                "M" -> IrType.Map(read(), read())
                "E" -> IrType.Set(read())
                "T" -> IrType.Task(read())
                "?" -> IrType.Nullable(read())
                "*" -> IrType.Pointer(read(), mutable = false)
                "^" -> IrType.Pointer(read(), mutable = true)
                "(" -> IrType.Tuple(List(next().toInt()) { read() })
                "V" -> IrType.Variant(List(next().toInt()) { read() })
                "fn" -> {
                    val kind = CallableKind.valueOf(next())
                    val flags = next().toInt()
                    val params = List(next().toInt()) { read() }
                    val ret = read()
                    val receivers = List(next().toInt()) { read() }
                    IrType.Function(
                        params, ret,
                        variadic = flags and 1 != 0, receivers = receivers, kind = kind,
                        isEscaping = flags and 2 != 0, isInline = flags and 4 != 0, isForeign = flags and 8 != 0,
                    )
                }
                "@" -> {
                    val name = unquote(next())
                    val args = List(next().toInt()) { read() }
                    val constArgs = List(next().toInt()) { next().let { if (it == "_") null else it.toLong() } }
                    IrType.Named(name, args, constArgs)
                }
                else -> error("unreadable type in a package interface: '$token'")
            }
        }
        val type = read()
        check(at == tokens.size) { "unreadable type in a package interface: '$text'" }
        return type
    }

    private fun write(type: IrType, out: StringBuilder) {
        fun put(text: String) { if (out.isNotEmpty()) out.append(' '); out.append(text) }
        when (type) {
            IrType.String -> put("S")
            IrType.Bool -> put("B")
            IrType.Unit -> put("U")
            IrType.Nothing -> put("N")
            IrType.Char -> put("C")
            IrType.ISize -> put("Is")
            IrType.USize -> put("Us")
            IrType.Double -> put("D")
            IrType.Half -> put("H")
            IrType.Float -> put("F")
            IrType.Quad -> put("Q")
            IrType.Any -> put("A")
            is IrType.Integer -> { put(if (type.signed) "i" else "u"); put(type.bits.toString()) }
            is IrType.Array -> { put("["); write(type.element, out); put(type.size?.toString() ?: "_") }
            is IrType.Map -> { put("M"); write(type.key, out); write(type.value, out) }
            is IrType.Set -> { put("E"); write(type.element, out) }
            is IrType.Task -> { put("T"); write(type.result, out) }
            is IrType.Nullable -> { put("?"); write(type.inner, out) }
            is IrType.Pointer -> { put(if (type.mutable) "^" else "*"); write(type.inner, out) }
            is IrType.Tuple -> { put("("); put(type.elements.size.toString()); type.elements.forEach { write(it, out) } }
            is IrType.Variant -> { put("V"); put(type.elements.size.toString()); type.elements.forEach { write(it, out) } }
            is IrType.Function -> {
                put("fn")
                put(type.kind.name)
                put(((if (type.variadic) 1 else 0) or (if (type.isEscaping) 2 else 0) or
                    (if (type.isInline) 4 else 0) or (if (type.isForeign) 8 else 0)).toString())
                put(type.params.size.toString())
                type.params.forEach { write(it, out) }
                write(type.ret, out)
                put(type.receivers.size.toString())
                type.receivers.forEach { write(it, out) }
            }
            is IrType.Named -> {
                put("@")
                put(quote(type.name))
                put(type.args.size.toString())
                type.args.forEach { write(it, out) }
                put(type.constArgs.size.toString())
                type.constArgs.forEach { put(it?.toString() ?: "_") }
            }
        }
    }

    /** A name with no space in it: `%` and space written as `%25` and `%20`. */
    private fun quote(name: String): String = name.replace("%", "%25").replace(" ", "%20").ifEmpty { "%00" }
    private fun unquote(text: String): String = if (text == "%00") "" else text.replace("%20", " ").replace("%25", "%")
}

/** SHA-256 (FIPS 180-4), for a package interface's key and its parts' names. */
internal class Sha256 {
    private val state = INITIAL.copyOf()
    private val block = ByteArray(64)
    private var filled = 0
    private var length = 0L
    private val words = IntArray(64)

    fun update(text: String) = update(text.encodeToByteArray())

    fun update(bytes: ByteArray) {
        for (byte in bytes) {
            block[filled++] = byte
            if (filled == 64) { compress(); filled = 0 }
        }
        length += bytes.size
    }

    /** The digest as lowercase hex; the hash is spent afterwards. */
    fun hex(): String {
        val bits = length * 8
        update(byteArrayOf(0x80.toByte()))
        while (filled != 56) update(byteArrayOf(0))
        for (shift in 56 downTo 0 step 8) {
            block[filled++] = (bits ushr shift).toByte()
        }
        compress()
        return state.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
    }

    private fun compress() {
        for (i in 0 until 16) {
            words[i] = ((block[i * 4].toInt() and 0xff) shl 24) or ((block[i * 4 + 1].toInt() and 0xff) shl 16) or
                ((block[i * 4 + 2].toInt() and 0xff) shl 8) or (block[i * 4 + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = words[i - 15].rotateRight(7) xor words[i - 15].rotateRight(18) xor (words[i - 15] ushr 3)
            val s1 = words[i - 2].rotateRight(17) xor words[i - 2].rotateRight(19) xor (words[i - 2] ushr 10)
            words[i] = words[i - 16] + s0 + words[i - 7] + s1
        }
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choose = (e and f) xor (e.inv() and g)
            val t1 = h + s1 + choose + K[i] + words[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + majority
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }

    companion object {
        fun hex(text: String): String = Sha256().apply { update(text) }.hex()

        private val INITIAL = longArrayOf(
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
        ).map { it.toInt() }.toIntArray()

        private val K = longArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
        ).map { it.toInt() }.toIntArray()
    }
}
