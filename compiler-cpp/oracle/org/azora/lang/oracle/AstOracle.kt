/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.oracle

import org.azora.lang.frontend.InferredSites
import org.azora.lang.frontend.AstValidator
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.MacroExpander
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Program
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test

/**
 * The Kotlin side of the C++ port's differential checks (CPP_PORT_PLAN.md §8).
 *
 * Does nothing unless `AZORA_ORACLE_JOB` names a job file. A job is a list of
 * commands, one per line, which `azora __parse-job` runs the same way:
 *
 *     reset                      forget fragment macros, set InferredSites to 0
 *     chars <file>               write base/char_tables.cpp (the JVM's answers)
 *     std <root> <outDir>        parse every std file in path order, one shared
 *                                compile-time environment (AzStdlib.parse)
 *     libraries <list>           parse each file with one shared copy of the std
 *                                environment; fragment macros accumulate
 *                                (StdlibInjector.create)
 *     entries <list>             parse each file with a fresh copy of the std
 *                                environment, as a compilation's entry is; the
 *                                fragment macros are restored after each file
 *     lex <list>                 write each file's token list
 *     stage parse|macros|validate
 *                                what std/libraries/entries dump from here on: the
 *                                parsed program (default), that program after
 *                                MacroExpander.expand, or the expanded program's
 *                                AstValidator errors (no library decorators known)
 *
 * A list file holds `<input>\t<output>` per line. Each output is the generic
 * reflective dump of the result ([Dump]), or `(Failure <Exception> "message")`.
 * Parse time, excluding reading and dump writing, is printed per command.
 */
class AstOracle {
    @Test
    fun runJob() {
        val job = System.getenv("AZORA_ORACLE_JOB") ?: return
        val repeat = System.getenv("AZORA_ORACLE_REPEAT")?.toIntOrNull() ?: 1
        val lines = File(job).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        for (round in 1..repeat) {
            val runner = JobRunner(writeOutputs = round == 1)
            for (line in lines) runner.run(line)
            println("oracle round $round: parse ${"%.3f".format(runner.parseSeconds)} s")
        }
    }
}

private class JobRunner(private val writeOutputs: Boolean) {
    var parseSeconds = 0.0

    private var stdLists: MutableMap<String, List<String>> = mutableMapOf()
    private var stdEnums: MutableMap<String, List<String>> = mutableMapOf()
    private var stdListScopes: MutableMap<String, String> = mutableMapOf()
    private var stage = "parse"

    /** What a std/libraries/entries command dumps for [program], for the current [stage]. */
    private fun staged(program: Program): Any = when (stage) {
        "parse" -> program
        "macros" -> MacroExpander.expand(program)
        "validate" -> AstValidator(emptyMap()).validate(MacroExpander.expand(program))
        else -> error("unknown stage '$stage'")
    }

    fun run(line: String) {
        val words = line.split(Regex("\\s+"))
        when (words[0]) {
            "reset" -> {
                Parser.resetFragmentMacros()
                setInferredSites(0)
            }
            "chars" -> if (writeOutputs) File(words[1]).also { it.parentFile.mkdirs() }.writeText(CharTables.generate())
            "std" -> std(File(words[1]), File(words[2]))
            "libraries" -> libraries(readList(words[1]))
            "entries" -> entries(readList(words[1]))
            "lex" -> lex(readList(words[1]))
            "stage" -> stage = words[1]
            else -> error("unknown oracle command '${words[0]}'")
        }
    }

    private fun readList(path: String): List<Pair<File, File>> = File(path).readLines()
        .filter { it.isNotBlank() }
        .map { it.split('\t').let { parts -> File(parts[0]) to File(parts[1]) } }

    /** AzStdlib.parse: every file in path order, one environment rebuilt from empty. */
    private fun std(root: File, out: File) {
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "az" }
            .map { it.relativeTo(root).path.replace('\\', '/') to it }
            .sortedBy { it.first }
            .toList()
        stdLists = mutableMapOf()
        stdEnums = mutableMapOf()
        stdListScopes = mutableMapOf()
        for ((relative, file) in files) {
            val source = file.readText()
            val result = timed { staged(Parser(Lexer(source).tokenize(), stdLists, stdEnums, typeListScope = stdListScopes).parse()) }
            write(File(out, "$relative.ast"), result)
        }
    }

    /** StdlibInjector.create: one shared copy of the std environment for every library. */
    private fun libraries(files: List<Pair<File, File>>) {
        val lists = stdLists.toMutableMap()
        val enums = stdEnums.toMutableMap()
        val scopes = stdListScopes.toMutableMap()
        for ((input, output) in files) {
            val source = input.readText()
            val result = timed { staged(Parser(Lexer(source).tokenize(), lists, enums, typeListScope = scopes).parse()) }
            write(output, result)
        }
    }

    /** Compiler.compile's entry parse: a fresh copy of the std environment per file. */
    private fun entries(files: List<Pair<File, File>>) {
        for ((input, output) in files) {
            val source = input.readText()
            val saved = FragmentMacros.snapshot()
            val result = timed {
                staged(
                    Parser(
                        Lexer(source).tokenize(),
                        stdLists.toMutableMap(),
                        stdEnums.toMutableMap(),
                        typeListScope = stdListScopes.toMutableMap(),
                    ).parse(),
                )
            }
            FragmentMacros.restore(saved)
            write(output, result)
        }
    }

    private fun lex(files: List<Pair<File, File>>) {
        for ((input, output) in files) {
            val source = input.readText()
            write(output, timed { Lexer(source).tokenize() })
        }
    }

    private fun timed(block: () -> Any): Any {
        val start = System.nanoTime()
        val result = try {
            block()
        } catch (e: Exception) {
            Failure(e.javaClass.simpleName, e.message ?: "null")
        } catch (e: StackOverflowError) {
            Failure("StackOverflowError", "")
        }
        parseSeconds += (System.nanoTime() - start) / 1e9
        return result
    }

    private fun write(file: File, result: Any) {
        if (!writeOutputs) return
        file.parentFile.mkdirs()
        val text = if (result is Failure) {
            "(Failure ${result.kind} ${Dump.quote(result.message)})\n"
        } else {
            Dump.of(result) + "\n"
        }
        file.writeText(text)
    }

    private fun setInferredSites(value: Int) {
        val field = InferredSites::class.java.getDeclaredField("last")
        field.isAccessible = true
        field.setInt(InferredSites, value)
    }
}

private class Failure(val kind: String, val message: String)

/** The parser's process-wide fragment-macro tables, saved and restored around an entry. */
private object FragmentMacros {
    private val names = listOf("fragmentMacros", "fragmentMacroParams", "infixFragmentMacros")

    @Suppress("UNCHECKED_CAST")
    private fun table(name: String): MutableMap<Any?, Any?> {
        val field = Parser::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(null) as MutableMap<Any?, Any?>
    }

    fun snapshot(): List<Map<Any?, Any?>> = names.map { LinkedHashMap(table(it)) }

    fun restore(saved: List<Map<Any?, Any?>>) {
        names.zip(saved).forEach { (name, map) -> table(name).apply { clear(); putAll(map) } }
    }
}

/**
 * The canonical dump (CPP_PORT_PLAN.md §8): every constructor property of every
 * data class, in declaration order, found by reflection so that no field can be
 * skipped by accident.
 */
object Dump {
    fun of(value: Any?): String = StringBuilder().also { write(value, it, 0) }.toString()

    private fun indent(sb: StringBuilder, n: Int) {
        repeat(n) { sb.append(' ') }
    }

    fun write(value: Any?, sb: StringBuilder, ind: Int) {
        when (value) {
            null -> sb.append("nil")
            is String -> sb.append(quote(value))
            is Boolean -> sb.append(value.toString())
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> sb.append(value.toString()).append('#').append(java.lang.Long.toHexString(value.toRawBits()))
            is Float -> sb.append(value.toString()).append("f#").append(Integer.toHexString(value.toRawBits()))
            is Char -> sb.append("'").append(value.code).append("'")
            is Enum<*> -> sb.append(value.name)
            is Set<*> -> collection("#{", "}", value, sb, ind)
            is Collection<*> -> collection("[", "]", value, sb, ind)
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                } else {
                    sb.append('{')
                    for ((k, v) in value) {
                        sb.append('\n')
                        indent(sb, ind + 2)
                        write(k, sb, ind + 2)
                        sb.append(' ')
                        write(v, sb, ind + 2)
                    }
                    sb.append('\n')
                    indent(sb, ind)
                    sb.append('}')
                }
            }
            else -> node(value, sb, ind)
        }
    }

    private fun collection(open: String, close: String, items: Collection<*>, sb: StringBuilder, ind: Int) {
        if (items.isEmpty()) {
            sb.append(open).append(close)
            return
        }
        sb.append(open)
        for (item in items) {
            sb.append('\n')
            indent(sb, ind + 2)
            write(item, sb, ind + 2)
        }
        sb.append('\n')
        indent(sb, ind)
        sb.append(close)
    }

    private fun node(value: Any, sb: StringBuilder, ind: Int) {
        val cls = value.javaClass
        val name = displayName(cls)
        val components = generateSequence(1) { it + 1 }
            .map { n -> cls.methods.firstOrNull { it.name == "component$n" && it.parameterCount == 0 } }
            .takeWhile { it != null }
            .map { it!! }
            .toList()
        if (components.isEmpty()) {
            val isObject = cls.declaredFields.any { it.name == "INSTANCE" && Modifier.isStatic(it.modifiers) }
            check(isObject) { "the oracle cannot dump ${cls.name}: it is neither a data class nor an object" }
            sb.append(name)
            return
        }
        val fields = cls.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        check(fields.size >= components.size) { "${cls.name}: fewer fields than components" }
        sb.append('(').append(name)
        components.forEachIndexed { i, component ->
            sb.append('\n')
            indent(sb, ind + 2)
            sb.append(fields[i].name).append(' ')
            write(component.invoke(value), sb, ind + 2)
        }
        sb.append('\n')
        indent(sb, ind)
        sb.append(')')
    }

    private fun displayName(cls: Class<*>): String = cls.name.substringAfterLast('.').replace('$', '.')

    fun quote(text: String): String {
        val sb = StringBuilder("\"")
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 || c.code == 0x7F -> sb.append("\\u").append(hex4(c.code))
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    sb.append(c).append(text[i + 1])
                    i++
                }
                c.isSurrogate() -> sb.append("\\u").append(hex4(c.code))
                else -> sb.append(c)
            }
            i++
        }
        return sb.append('"').toString()
    }

    private fun hex4(code: Int): String = code.toString(16).uppercase().padStart(4, '0')
}
