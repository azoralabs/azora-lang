/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.backend

/**
 * One program's LLVM module as parts that compile on their own and link into
 * the same program, so that a build can keep each part's object file and
 * compile again only the parts an edit changed.
 *
 * Every function the program defines is in exactly one part, chosen by a hash
 * of its name: an edit that adds or changes functions changes the parts that
 * hold them and no other. The program's variables live in a part of their own
 * (`globals.ll`); the other parts declare what they use from elsewhere.
 *
 * A part's text depends only on what it holds. The code generator numbers
 * some names in one sequence over the whole program - string constants
 * (`@.str.N`), drop functions (`@__azora_drop_pack_N`), closure and task
 * bodies (`@__azora_lambda_body_N`) and their context types
 * (`%azora.lambda.ctx.N`) - so one new string in a game would renumber them in
 * every engine function after it. A part therefore holds its own copy of each
 * of these it uses, private to it and numbered again by where the part first
 * uses it. None of them is ever compared by address (a drop function is only
 * ever tested against null), so copies behave as the one did.
 *
 * The compiler's own runtime functions (`@__azora_alloc`, `@__azora_free`, ...)
 * are given to every part that calls them as `linkonce_odr`: each part can
 * inline them as the whole module did, and the linker keeps one.
 *
 * What is lost against one module is inlining from part to part. A release
 * build that ships keeps compiling the whole module.
 */
object LlvmUnits {
    /** One part: its file name and its LLVM module text. */
    data class Part(val name: String, val text: String)

    /** The file the program's variables are defined in. */
    const val GLOBALS = "globals.ll"

    /** [module], the code generator's output for a whole program, as [parts] function parts and [GLOBALS]. */
    fun split(module: String, parts: Int): List<Part> {
        require(parts >= 1) { "a program is split into at least one part" }
        return Splitter(module).split(parts)
    }

    /**
     * Only the functions of [module] that [placed] names, as [parts] parts, and
     * [GLOBALS] as the module defines it. Every other function the parts call
     * is declared, to be defined by another module: a package interface's
     * parts hold its precompiled functions, and the program built on it
     * defines the rest and every variable.
     */
    fun splitOnly(module: String, parts: Int, placed: Set<String>, helpers: Set<String> = emptySet()): List<Part> {
        require(parts >= 1) { "a program is split into at least one part" }
        return Splitter(module).also { it.helpers = helpers }.split(parts, placed)
    }

    /**
     * Numbered over the program and never compared by address: drop functions,
     * closure, task and C-callback bodies, and the generated `==`/`hash` and
     * valued-enum helpers. A part keeps its own copy of each it uses.
     */
    fun isCopied(name: String): Boolean =
        (name.startsWith("__azora_") || name.startsWith("__structural_") || name.startsWith("__enum_")) && LOCAL_FUNCTION.matches(name)

    /**
     * What one module defines and declares: each function's `declare` (what
     * another module that calls it writes), each variable's `external` line and
     * its definition as a part of its own would give it, each named type's
     * definition, and the module's own `declare`s.
     */
    class Listing(
        val functions: Map<String, String>,
        /** Functions the module keeps to itself (`internal`/`private`). */
        val localFunctions: Set<String>,
        val variables: Map<String, String>,
        val variableDefinitions: Map<String, String>,
        val types: Map<String, String>,
        val declarations: Map<String, String>,
    )

    fun listing(module: String): Listing = Splitter(module).listing()

    /** The part the function [name] is defined in: FNV-1a of its UTF-8 bytes. */
    fun partOf(name: String, parts: Int): Int {
        var hash = 2166136261u
        for (byte in name.encodeToByteArray()) hash = (hash xor (byte.toUByte().toUInt())) * 16777619u
        return (hash % parts.toUInt()).toInt()
    }

    private val LINKAGES = listOf("private ", "internal ", "linkonce_odr ", "linkonce ", "weak_odr ", "weak ", "external ")

    /** A `@` or `%` name used in a stretch of text: [start] is the sigil's offset. */
    private class Use(val start: Int, val end: Int, val name: String, val symbol: Boolean)

    /** A top-level definition: a whole `define … { … }` or one line. */
    private class Item(val name: String, val text: String, val uses: List<Use>)

    private class Splitter(private val module: String) {
        private val functions = LinkedHashMap<String, Item>()
        private val types = LinkedHashMap<String, Item>()
        private val declarations = LinkedHashMap<String, String>()
        /** Variables, `appending` arrays and aliases: one definition, in [GLOBALS]. */
        private val shared = LinkedHashMap<String, Item>()
        /** `private`/`internal` constants: copied into each part that uses them. */
        private val constants = LinkedHashMap<String, Item>()

        init {
            var at = 0
            while (at < module.length) {
                var end = module.indexOf('\n', at).let { if (it < 0) module.length else it }
                val line = module.substring(at, end)
                when {
                    line.isBlank() || line.startsWith(";") -> {}
                    line.startsWith("define ") -> {
                        // A body ends at the first `}` that starts a line, unless
                        // the whole definition is this one line.
                        if (!line.trimEnd().endsWith("}")) {
                            var close = end
                            while (close < module.length && !(module[close] == '\n' && module.startsWith("}", close + 1))) {
                                close = module.indexOf('\n', close + 1).let { if (it < 0) module.length else it }
                            }
                            check(close < module.length) { "cannot split the LLVM module: '${line.take(80)}' has no closing brace" }
                            end = module.indexOf('\n', close + 1).let { if (it < 0) module.length else it }
                        }
                        val text = module.substring(at, end) + "\n"
                        val name = definedName(line)
                        check(name !in functions) { "cannot split the LLVM module: @$name is defined twice" }
                        functions[name] = Item(name, text, uses(text))
                    }
                    line.startsWith("declare ") -> declarations[definedName(line)] = line + "\n"
                    line.startsWith("%") && " = type " in line -> {
                        val name = line.substring(1, line.indexOf(" = type "))
                        types[name] = Item(name, line + "\n", uses(line + "\n"))
                    }
                    line.startsWith("@") && " = " in line -> {
                        val name = line.substring(1, line.indexOf(" = "))
                        val rest = line.substringAfter(" = ")
                        val item = Item(name, line + "\n", uses(line + "\n"))
                        val local = rest.startsWith("private ") || rest.startsWith("internal ")
                        val constant = Regex("""^(?:(?:private|internal|unnamed_addr) )+constant """).containsMatchIn(rest)
                        if (local && constant) constants[name] = item else shared[name] = item
                    }
                    else -> error("cannot split the LLVM module: unexpected top-level line '${line.take(80)}'")
                }
                at = end + 1
            }
        }

        /** The `@name` a `define`/`declare` line introduces. */
        private fun definedName(line: String): String {
            val at = line.indexOf('@')
            var end = at + 1
            while (end < line.length && continuesName(line[end])) end++
            check(at >= 0 && end > at + 1) { "cannot split the LLVM module: no name in '${line.take(80)}'" }
            return line.substring(at + 1, end)
        }

        private fun continuesName(c: Char): Boolean = c.isLetterOrDigit() || c in "._$-"

        /** Every `@` and `%` name in [text], outside comments and quoted strings. */
        private fun uses(text: String): List<Use> {
            val found = ArrayList<Use>()
            var at = 0
            while (at < text.length) {
                when (val c = text[at]) {
                    ';' -> at = text.indexOf('\n', at).let { if (it < 0) text.length else it }
                    '"' -> at = text.indexOf('"', at + 1).let { if (it < 0) text.length else it + 1 }
                    '@', '%' -> {
                        var end = at + 1
                        while (end < text.length && continuesName(text[end])) end++
                        if (end > at + 1) found += Use(at, end, text.substring(at + 1, end), c == '@')
                        at = end
                    }
                    else -> at++
                }
            }
            return found
        }

        fun listing(): Listing {
            val local = functions.filterValues { item ->
                val rest = item.text.removePrefix("define ")
                rest.startsWith("internal ") || rest.startsWith("private ")
            }.keys
            return Listing(
                functions = functions.mapValues { declarationOf(it.value.text).trimEnd('\n') },
                localFunctions = local,
                variables = (shared + constants).mapValues { externalOf(it.value.text).trimEnd('\n') },
                variableDefinitions = (shared + constants).mapValues { sharedDefinition(it.value.text, it.key).trimEnd('\n') },
                types = types.mapValues { it.value.text.trimEnd('\n') },
                declarations = declarations.mapValues { it.value.trimEnd('\n') },
            )
        }

        /** Set while splitting only some functions out ([splitOnly]). */
        private var partial = false

        /**
         * Functions the code generator writes the same for any program (an
         * intrinsic's body): with [splitOnly], each part that calls one keeps
         * a `linkonce_odr` copy, as it does the runtime's, rather than leave it
         * to a program that may not write it.
         */
        var helpers: Set<String> = emptySet()

        /** Numbered by one sequence over the program: each part keeps its own copy, numbered again. */
        private fun copied(name: String): Boolean = isCopied(name)

        /**
         * The compiler's runtime: `linkonce_odr` in each part that calls it. The
         * global initializers are the whole program's, so the parts of a
         * package interface call the program's own rather than keep a copy.
         */
        private fun runtime(name: String): Boolean =
            (name.startsWith("__azora_") && !copied(name) && !(partial && name.startsWith("__azora_init_"))) ||
                (partial && name in helpers)

        /** A type the code generator numbers over the program (`%azora.lambda.ctx.7`). */
        private fun numberedType(name: String): Boolean = name.startsWith("azora.") && NUMBERED.containsMatchIn(name)

        fun split(parts: Int, placed: Set<String>? = null): List<Part> {
            partial = placed != null
            val owned = Array(parts) { ArrayList<String>() }
            for (name in functions.keys) {
                if (copied(name) || runtime(name)) continue
                if (placed != null && name !in placed) continue
                owned[partOf(name, parts)] += name
            }
            val result = ArrayList<Part>()
            for (index in 0 until parts) {
                val roots = owned[index].sorted()
                result += Part(
                    "part-${index.toString().padStart(2, '0')}.ll",
                    render("; Azora program part ${index + 1} of $parts", roots, includeShared = false),
                )
            }
            result += Part(GLOBALS, render("; Azora program variables", emptyList(), includeShared = true))
            // Nothing the module defined may be left out: every function in a
            // part of its own, or copied into the parts that use it.
            val held = owned.flatMap { it }.toSet()
            for (name in functions.keys) {
                check(name in held || copied(name) || runtime(name) || (placed != null && name !in placed)) {
                    "cannot split the LLVM module: @$name was not placed"
                }
            }
            return result
        }

        private fun render(title: String, roots: List<String>, includeShared: Boolean): String {
            val sharedItems = if (includeShared) shared.keys.sorted().map { shared.getValue(it) } else emptyList()
            // The functions this part holds: its own, then the copies they (and its variables) reach.
            val held = ArrayList<String>(roots)
            val holding = HashSet<String>(roots)
            fun reach(item: Item) {
                for (use in item.uses) {
                    if (use.symbol && use.name in functions && (copied(use.name) || runtime(use.name)) && holding.add(use.name)) {
                        held += use.name
                    }
                }
            }
            sharedItems.forEach(::reach)
            var next = 0
            while (next < held.size) reach(functions.getValue(held[next++]))

            // Names given again in this part, numbered in the order the part first uses them.
            val renamed = HashMap<String, String>()
            val counts = HashMap<String, Int>()
            fun needsRename(use: Use): Boolean =
                if (use.symbol) (copied(use.name) || use.name in constants) && use.name.last().isDigit()
                else use.name in types && numberedType(use.name)
            fun key(use: Use): String = "${if (use.symbol) '@' else '%'}${use.name}"
            fun rename(use: Use) {
                val key = key(use)
                if (key in renamed) return
                val family = key.trimEnd { it.isDigit() }
                val number = counts.getOrElse(family) { 0 }
                counts[family] = number + 1
                renamed[key] = "${use.name.trimEnd { it.isDigit() }}$number"
            }

            // What the part refers to: constants it copies, types, and what other parts define.
            val usedConstants = LinkedHashSet<String>()
            val usedTypes = LinkedHashSet<String>()
            val external = HashMap<String, Item>()
            fun declare(name: String, text: String) {
                if (name !in external) external[name] = Item(name, text, uses(text))
            }
            fun note(item: Item) {
                for (use in item.uses) {
                    if (needsRename(use)) rename(use)
                    val name = use.name
                    if (!use.symbol) {
                        if (name in types) usedTypes += name
                        continue
                    }
                    when {
                        name in holding || (includeShared && name in shared) -> {}
                        name in constants -> usedConstants += name
                        name in functions -> declare(name, declarationOf(functions.getValue(name).text))
                        name in declarations -> declare(name, declarations.getValue(name))
                        name in shared -> declare(name, externalOf(shared.getValue(name).text))
                        else -> error("cannot split the LLVM module: @$name is used but never declared")
                    }
                }
            }
            val body = held.map { functions.getValue(it) }
            body.forEach(::note)
            sharedItems.forEach(::note)
            var noted = 0
            while (noted < usedConstants.size) note(constants.getValue(usedConstants.elementAt(noted++)))
            // In name order, so the part's numbering never depends on a hash map's.
            for (name in external.keys.sorted()) note(external.getValue(name))
            val typeOrder = ArrayList(usedTypes)
            noted = 0
            while (noted < typeOrder.size) {
                for (use in types.getValue(typeOrder[noted]).uses) {
                    if (use.symbol || use.name !in types) continue
                    if (needsRename(use)) rename(use)
                    if (usedTypes.add(use.name)) typeOrder += use.name
                }
                noted++
            }

            // A name given again must not be one the part already uses as it is.
            val kept = HashSet<String>()
            val declared = external.keys.sorted().map { external.getValue(it) }
            for (item in body + sharedItems + declared + typeOrder.map { types.getValue(it) }) {
                for (use in item.uses) if (!needsRename(use)) kept += key(use)
            }
            for ((original, given) in renamed) {
                val spelled = "${original[0]}$given"
                check(spelled !in kept || spelled == original) { "cannot split the LLVM module: $original renamed to $spelled, a name the part already uses" }
            }

            /** [item] with this part's names. */
            fun written(item: Item): String {
                val out = StringBuilder(item.text.length + 16)
                var from = 0
                for (use in item.uses) {
                    if (!needsRename(use)) continue
                    out.append(item.text, from, use.start + 1).append(renamed.getValue(key(use)))
                    from = use.end
                }
                return out.append(item.text, from, item.text.length).toString()
            }

            val out = StringBuilder()
            out.append(title).append("\n\n")
            if (declared.isNotEmpty()) {
                for (item in declared) out.append(written(item))
                out.append('\n')
            }
            if (typeOrder.isNotEmpty()) {
                for (line in typeOrder.map { written(types.getValue(it)) }.sorted()) out.append(line)
                out.append('\n')
            }
            if (sharedItems.isNotEmpty()) {
                for (item in sharedItems) out.append(sharedDefinition(written(item), item.name))
                out.append('\n')
            }
            for (name in held) {
                val linkage = when {
                    copied(name) -> "internal "
                    runtime(name) -> "linkonce_odr "
                    else -> ""
                }
                out.append(withLinkage(written(functions.getValue(name)), linkage)).append('\n')
            }
            for (name in usedConstants) out.append(written(constants.getValue(name)))
            return out.toString()
        }

        /** A variable as [GLOBALS] defines it: module-local no longer, since functions in other parts use it. */
        private fun sharedDefinition(line: String, name: String): String {
            val head = "@$name = "
            var rest = line.removePrefix(head)
            for (linkage in listOf("private ", "internal ")) rest = rest.removePrefix(linkage)
            return head + rest
        }

        /** [definition] with its own linkage replaced by [linkage] (empty: the default, external). */
        private fun withLinkage(definition: String, linkage: String): String {
            var rest = definition.removePrefix("define ")
            for (existing in LINKAGES) if (rest.startsWith(existing)) { rest = rest.removePrefix(existing); break }
            return "define $linkage$rest"
        }

        /** The `declare` for a function another part defines, from its `define` line. */
        private fun declarationOf(definition: String): String {
            val header = definition.substring(0, definition.indexOf('\n'))
            var rest = header.removePrefix("define ")
            for (existing in LINKAGES) if (rest.startsWith(existing)) { rest = rest.removePrefix(existing); break }
            val at = rest.indexOf('@')
            val open = rest.indexOf('(', at)
            var depth = 0
            var close = open
            while (close < rest.length) {
                when (rest[close]) {
                    '(', '[', '{', '<' -> depth++
                    ')', ']', '}', '>' -> { depth--; if (depth == 0) break }
                }
                close++
            }
            val params = splitTopLevel(rest.substring(open + 1, close)).map { parameter ->
                val last = parameter.substringAfterLast(' ')
                if (parameter != "..." && last.startsWith("%") && ' ' in parameter) parameter.substringBeforeLast(' ') else parameter
            }
            return "declare ${rest.substring(0, open)}(${params.joinToString(", ")})\n"
        }

        /** `@x = external global T` for a variable [GLOBALS] defines. */
        private fun externalOf(definition: String): String {
            val name = definition.substringBefore(" = ")
            var rest = definition.substringAfter(" = ").trimEnd('\n')
            check(!rest.startsWith("alias ") && !rest.contains(" alias ")) { "cannot split the LLVM module: $name is an alias other parts use" }
            for (linkage in LINKAGES) if (rest.startsWith(linkage)) { rest = rest.removePrefix(linkage); break }
            val qualifiers = StringBuilder()
            while (true) {
                val word = listOf("thread_local ", "unnamed_addr ", "local_unnamed_addr ").firstOrNull { rest.startsWith(it) } ?: break
                if (word == "thread_local ") qualifiers.append(word)
                rest = rest.removePrefix(word)
            }
            val kind = when {
                rest.startsWith("global ") -> "global"
                rest.startsWith("constant ") -> "constant"
                else -> error("cannot split the LLVM module: cannot read the variable $name")
            }
            val type = readType(rest.substringAfter("$kind "))
            return "$name = external $qualifiers$kind $type\n"
        }

        /** The LLVM type at the start of [text] (`i8*`, `[3 x i8]`, `void (i8*, i64)*`). */
        private fun readType(text: String): String {
            var at = 0
            fun group() {
                var depth = 0
                while (at < text.length) {
                    when (text[at]) {
                        '(', '[', '{', '<' -> depth++
                        ')', ']', '}', '>' -> depth--
                    }
                    at++
                    if (depth == 0) return
                }
            }
            if (text[at] in "([{<") group() else while (at < text.length && text[at] != ' ' && text[at] != '(' && text[at] != '*') at++
            while (at < text.length) {
                when {
                    text[at] == '*' -> at++
                    text.startsWith(" (", at) -> { at++; group() }
                    text[at] == '(' -> group()
                    else -> break
                }
            }
            return text.substring(0, at)
        }

        private fun splitTopLevel(text: String): List<String> {
            val result = ArrayList<String>()
            var depth = 0
            var start = 0
            for ((index, c) in text.withIndex()) {
                when (c) {
                    '(', '[', '{', '<' -> depth++
                    ')', ']', '}', '>' -> depth--
                    ',' -> if (depth == 0) { result += text.substring(start, index).trim(); start = index + 1 }
                }
            }
            if (text.substring(start).isNotBlank()) result += text.substring(start).trim()
            return result
        }
    }

    /** Drop functions and closure, task and C-callback bodies: numbered over the program, never compared by address. */
    private val LOCAL_FUNCTION = Regex("""__azora_(?:drop_[a-z]+|[A-Za-z0-9_]*_body|[A-Za-z0-9_]*_entry)_[0-9]+|__structural_[a-z]+_[0-9]+|__enum_(?:value|case)_[0-9]+""")
    private val NUMBERED = Regex("""\.[0-9]+$""")
}
