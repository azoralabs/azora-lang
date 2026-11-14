/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.backend

/**
 * WebAssembly text to the binary format, so a program the WebAssembly backend
 * compiles runs without an external assembler (TARGETS_DIP §4).
 *
 * It reads the text format as [WasmCodegen] writes it - a module of folded
 * S-expressions - and accepts the standard spellings around it: flat
 * instruction sequences, `block`/`loop`/`if` with labels and result types,
 * every core numeric, memory and control instruction, the bulk-memory and
 * saturating-truncation instructions (`0xFC`), and the SIMD instructions on
 * whole vectors and lanes (`0xFD`). What it does not know is an error that
 * names the line, never a guess.
 *
 * The output carries a `name` section with the function names, so a trap in
 * a browser or Node names the Azora function it happened in.
 */
object WasmAssembler {
    fun assemble(wat: String, names: Boolean = true): ByteArray {
        val root = Reader(wat).document()
        val module = (root as? Node.List)?.takeIf { it.head == "module" }
            ?: throw WasmAssemblyError("line ${root.line}: expected (module …)")
        return ModuleAssembler(module, names).assemble()
    }
}

class WasmAssemblyError(message: String) : RuntimeException(message)

// ── S-expressions ──────────────────────────────────────────────────

private sealed class Node {
    abstract val line: Int

    /** A keyword, `$name`, number or other bare token. */
    data class Atom(val text: String, override val line: Int) : Node()

    /** A string literal, as the bytes it denotes. */
    class Str(val bytes: ByteArray, override val line: Int) : Node()

    class List(val items: kotlin.collections.List<Node>, override val line: Int) : Node() {
        val head: String? get() = (items.firstOrNull() as? Atom)?.text
    }
}

private class Reader(private val s: String) {
    private var i = 0
    private var line = 1

    fun document(): Node {
        skip()
        val node = node()
        skip()
        if (i < s.length) fail("unexpected text after the module")
        return node
    }

    private fun fail(message: String): Nothing = throw WasmAssemblyError("line $line: $message")

    private fun skip() {
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\n' -> { line++; i++ }
                c.isWhitespace() -> i++
                c == ';' && s.getOrNull(i + 1) == ';' -> while (i < s.length && s[i] != '\n') i++
                c == '(' && s.getOrNull(i + 1) == ';' -> blockComment()
                else -> return
            }
        }
    }

    private fun blockComment() {
        var depth = 0
        while (i < s.length) {
            when {
                s[i] == '(' && s.getOrNull(i + 1) == ';' -> { depth++; i += 2 }
                s[i] == ';' && s.getOrNull(i + 1) == ')' -> { depth--; i += 2; if (depth == 0) return }
                else -> { if (s[i] == '\n') line++; i++ }
            }
        }
        fail("unterminated block comment")
    }

    private fun node(): Node {
        skip()
        if (i >= s.length) fail("unexpected end of text")
        val at = line
        return when (s[i]) {
            '(' -> {
                i++
                val items = mutableListOf<Node>()
                while (true) {
                    skip()
                    if (i >= s.length) fail("missing ')' for the list opened on line $at")
                    if (s[i] == ')') { i++; break }
                    items += node()
                }
                Node.List(items, at)
            }
            ')' -> fail("unexpected ')'")
            '"' -> string()
            else -> {
                val start = i
                while (i < s.length && !s[i].isWhitespace() && s[i] != '(' && s[i] != ')' && s[i] != '"' && s[i] != ';') i++
                Node.Atom(s.substring(start, i), at)
            }
        }
    }

    private fun string(): Node.Str {
        val at = line
        i++ // opening quote
        val out = ByteSink()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i]
            when {
                c == '"' -> { i++; return Node.Str(out.toByteArray(), at) }
                c == '\\' -> {
                    val e = s.getOrNull(i + 1) ?: fail("unterminated string")
                    when {
                        e == 'n' -> { out.byte(0x0A); i += 2 }
                        e == 't' -> { out.byte(0x09); i += 2 }
                        e == 'r' -> { out.byte(0x0D); i += 2 }
                        e == '\\' -> { out.byte(0x5C); i += 2 }
                        e == '\'' -> { out.byte(0x27); i += 2 }
                        e == '"' -> { out.byte(0x22); i += 2 }
                        e == 'u' -> {
                            if (s.getOrNull(i + 2) != '{') fail("expected '{' after \\u")
                            val close = s.indexOf('}', i + 3)
                            if (close < 0) fail("unterminated \\u{…}")
                            val code = s.substring(i + 3, close).replace("_", "").toIntOrNull(16) ?: fail("bad \\u{…} escape")
                            out.bytes(codePointUtf8(code))
                            i = close + 1
                        }
                        e.isHex() && s.getOrNull(i + 2)?.isHex() == true -> {
                            out.byte(s.substring(i + 1, i + 3).toInt(16))
                            i += 3
                        }
                        else -> fail("unknown string escape '\\$e'")
                    }
                }
                else -> {
                    if (c == '\n') line++
                    // Text outside an escape is UTF-8: one code point at a time.
                    val code = if (c.isHighSurrogate() && i + 1 < s.length) {
                        val pair = ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
                        i++
                        pair
                    } else c.code
                    out.bytes(codePointUtf8(code))
                    i++
                }
            }
        }
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun codePointUtf8(code: Int): ByteArray = when {
        code < 0x80 -> byteArrayOf(code.toByte())
        code < 0x800 -> byteArrayOf((0xC0 or (code shr 6)).toByte(), (0x80 or (code and 0x3F)).toByte())
        code < 0x10000 -> byteArrayOf(
            (0xE0 or (code shr 12)).toByte(), (0x80 or ((code shr 6) and 0x3F)).toByte(), (0x80 or (code and 0x3F)).toByte(),
        )
        else -> byteArrayOf(
            (0xF0 or (code shr 18)).toByte(), (0x80 or ((code shr 12) and 0x3F)).toByte(),
            (0x80 or ((code shr 6) and 0x3F)).toByte(), (0x80 or (code and 0x3F)).toByte(),
        )
    }
}

// ── Bytes ──────────────────────────────────────────────────────────

private class ByteSink {
    private var data = ByteArray(256)
    var size = 0
        private set

    fun byte(b: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = b.toByte()
    }

    fun bytes(b: ByteArray) = b.forEach { byte(it.toInt()) }

    fun u32(value: Long) {
        var v = value
        require(v >= 0) { "u32 $value" }
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            byte(b)
        } while (v != 0L)
    }

    fun u32(value: Int) = u32(value.toLong() and 0xFFFFFFFFL)

    fun s64(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v shr 7
            val done = (v == 0L && (b and 0x40) == 0) || (v == -1L && (b and 0x40) != 0)
            byte(if (done) b else b or 0x80)
            if (done) return
        }
    }

    fun name(text: String) {
        val utf8 = text.encodeToByteArray()
        u32(utf8.size)
        bytes(utf8)
    }

    fun section(id: Int, body: ByteSink) {
        byte(id)
        u32(body.size)
        bytes(body.toByteArray())
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
}

// ── Types ──────────────────────────────────────────────────────────

private val VALUE_TYPES = mapOf(
    "i32" to 0x7F, "i64" to 0x7E, "f32" to 0x7D, "f64" to 0x7C, "v128" to 0x7B,
    "funcref" to 0x70, "externref" to 0x6F,
)

private data class FuncType(val params: List<Int>, val results: List<Int>)

// ── Instructions ───────────────────────────────────────────────────

/** How an instruction's immediates are written after its opcode. */
private enum class Imm { NONE, LOCAL, GLOBAL, FUNC, LABEL, MEM, MEMORY_INDEX, I32, I64, F32, F64, LANE, V128, BULK_COPY, BULK_FILL, DATA, SELECT, BR_TABLE, CALL_INDIRECT }

/** An opcode: [prefix] (0 for a single-byte one) then [code], and its natural alignment for a memory access. */
private data class Op(val code: Int, val imm: Imm = Imm.NONE, val prefix: Int = 0, val align: Int = 0)

private val OPS: Map<String, Op> = buildMap {
    put("unreachable", Op(0x00)); put("nop", Op(0x01))
    put("br", Op(0x0C, Imm.LABEL)); put("br_if", Op(0x0D, Imm.LABEL)); put("br_table", Op(0x0E, Imm.BR_TABLE))
    put("return", Op(0x0F)); put("call", Op(0x10, Imm.FUNC)); put("call_indirect", Op(0x11, Imm.CALL_INDIRECT))
    put("drop", Op(0x1A)); put("select", Op(0x1B, Imm.SELECT))
    put("local.get", Op(0x20, Imm.LOCAL)); put("local.set", Op(0x21, Imm.LOCAL)); put("local.tee", Op(0x22, Imm.LOCAL))
    put("global.get", Op(0x23, Imm.GLOBAL)); put("global.set", Op(0x24, Imm.GLOBAL))
    val memory = listOf(
        "i32.load" to 2, "i64.load" to 3, "f32.load" to 2, "f64.load" to 3,
        "i32.load8_s" to 0, "i32.load8_u" to 0, "i32.load16_s" to 1, "i32.load16_u" to 1,
        "i64.load8_s" to 0, "i64.load8_u" to 0, "i64.load16_s" to 1, "i64.load16_u" to 1, "i64.load32_s" to 2, "i64.load32_u" to 2,
        "i32.store" to 2, "i64.store" to 3, "f32.store" to 2, "f64.store" to 3,
        "i32.store8" to 0, "i32.store16" to 1, "i64.store8" to 0, "i64.store16" to 1, "i64.store32" to 2,
    )
    memory.forEachIndexed { index, (name, align) -> put(name, Op(0x28 + index, Imm.MEM, align = align)) }
    put("memory.size", Op(0x3F, Imm.MEMORY_INDEX)); put("memory.grow", Op(0x40, Imm.MEMORY_INDEX))
    put("i32.const", Op(0x41, Imm.I32)); put("i64.const", Op(0x42, Imm.I64))
    put("f32.const", Op(0x43, Imm.F32)); put("f64.const", Op(0x44, Imm.F64))
    val plain = listOf(
        "i32.eqz", "i32.eq", "i32.ne", "i32.lt_s", "i32.lt_u", "i32.gt_s", "i32.gt_u", "i32.le_s", "i32.le_u", "i32.ge_s", "i32.ge_u",
        "i64.eqz", "i64.eq", "i64.ne", "i64.lt_s", "i64.lt_u", "i64.gt_s", "i64.gt_u", "i64.le_s", "i64.le_u", "i64.ge_s", "i64.ge_u",
        "f32.eq", "f32.ne", "f32.lt", "f32.gt", "f32.le", "f32.ge",
        "f64.eq", "f64.ne", "f64.lt", "f64.gt", "f64.le", "f64.ge",
        "i32.clz", "i32.ctz", "i32.popcnt", "i32.add", "i32.sub", "i32.mul", "i32.div_s", "i32.div_u", "i32.rem_s", "i32.rem_u",
        "i32.and", "i32.or", "i32.xor", "i32.shl", "i32.shr_s", "i32.shr_u", "i32.rotl", "i32.rotr",
        "i64.clz", "i64.ctz", "i64.popcnt", "i64.add", "i64.sub", "i64.mul", "i64.div_s", "i64.div_u", "i64.rem_s", "i64.rem_u",
        "i64.and", "i64.or", "i64.xor", "i64.shl", "i64.shr_s", "i64.shr_u", "i64.rotl", "i64.rotr",
        "f32.abs", "f32.neg", "f32.ceil", "f32.floor", "f32.trunc", "f32.nearest", "f32.sqrt",
        "f32.add", "f32.sub", "f32.mul", "f32.div", "f32.min", "f32.max", "f32.copysign",
        "f64.abs", "f64.neg", "f64.ceil", "f64.floor", "f64.trunc", "f64.nearest", "f64.sqrt",
        "f64.add", "f64.sub", "f64.mul", "f64.div", "f64.min", "f64.max", "f64.copysign",
        "i32.wrap_i64", "i32.trunc_f32_s", "i32.trunc_f32_u", "i32.trunc_f64_s", "i32.trunc_f64_u",
        "i64.extend_i32_s", "i64.extend_i32_u", "i64.trunc_f32_s", "i64.trunc_f32_u", "i64.trunc_f64_s", "i64.trunc_f64_u",
        "f32.convert_i32_s", "f32.convert_i32_u", "f32.convert_i64_s", "f32.convert_i64_u", "f32.demote_f64",
        "f64.convert_i32_s", "f64.convert_i32_u", "f64.convert_i64_s", "f64.convert_i64_u", "f64.promote_f32",
        "i32.reinterpret_f32", "i64.reinterpret_f64", "f32.reinterpret_i32", "f64.reinterpret_i64",
        "i32.extend8_s", "i32.extend16_s", "i64.extend8_s", "i64.extend16_s", "i64.extend32_s",
    )
    plain.forEachIndexed { index, name -> put(name, Op(0x45 + index)) }
    listOf(
        "i32.trunc_sat_f32_s", "i32.trunc_sat_f32_u", "i32.trunc_sat_f64_s", "i32.trunc_sat_f64_u",
        "i64.trunc_sat_f32_s", "i64.trunc_sat_f32_u", "i64.trunc_sat_f64_s", "i64.trunc_sat_f64_u",
    ).forEachIndexed { index, name -> put(name, Op(index, prefix = 0xFC)) }
    put("memory.init", Op(8, Imm.DATA, prefix = 0xFC)); put("data.drop", Op(9, Imm.DATA, prefix = 0xFC))
    put("memory.copy", Op(10, Imm.BULK_COPY, prefix = 0xFC)); put("memory.fill", Op(11, Imm.BULK_FILL, prefix = 0xFC))
    // SIMD: the vector loads and stores, constants, splats and lane access.
    put("v128.load", Op(0, Imm.MEM, prefix = 0xFD, align = 4)); put("v128.store", Op(11, Imm.MEM, prefix = 0xFD, align = 4))
    put("v128.const", Op(12, Imm.V128, prefix = 0xFD))
    listOf("i8x16.splat", "i16x8.splat", "i32x4.splat", "i64x2.splat", "f32x4.splat", "f64x2.splat")
        .forEachIndexed { index, name -> put(name, Op(15 + index, prefix = 0xFD)) }
    listOf(
        "i8x16.extract_lane_s", "i8x16.extract_lane_u", "i8x16.replace_lane",
        "i16x8.extract_lane_s", "i16x8.extract_lane_u", "i16x8.replace_lane",
        "i32x4.extract_lane", "i32x4.replace_lane", "i64x2.extract_lane", "i64x2.replace_lane",
        "f32x4.extract_lane", "f32x4.replace_lane", "f64x2.extract_lane", "f64x2.replace_lane",
    ).forEachIndexed { index, name -> put(name, Op(21 + index, Imm.LANE, prefix = 0xFD)) }
    put("v128.not", Op(77, prefix = 0xFD)); put("v128.and", Op(78, prefix = 0xFD)); put("v128.andnot", Op(79, prefix = 0xFD))
    put("v128.or", Op(80, prefix = 0xFD)); put("v128.xor", Op(81, prefix = 0xFD)); put("v128.any_true", Op(83, prefix = 0xFD))
    put("i64x2.eq", Op(214, prefix = 0xFD)); put("i64x2.ne", Op(215, prefix = 0xFD))
}

// ── The module ─────────────────────────────────────────────────────

private class ModuleAssembler(private val module: Node.List, private val withNames: Boolean) {
    private val types = mutableListOf<FuncType>()
    private val typeNames = mutableMapOf<String, Int>()

    private class Func(val name: String?, val type: Int, val node: Node.List?, val import: Pair<String, String>?)
    private val funcs = mutableListOf<Func>()
    private val funcNames = mutableMapOf<String, Int>()

    private class Global(val name: String?, val type: Int, val mutable: Boolean, val init: Node.List)
    private val globals = mutableListOf<Global>()
    private val globalNames = mutableMapOf<String, Int>()

    private data class Limits(val min: Long, val max: Long?)
    private var memory: Limits? = null
    private var table: Limits? = null
    private val tableNames = mutableMapOf<String, Int>()
    private val memoryNames = mutableMapOf<String, Int>()

    private data class Export(val name: String, val kind: Int, val ref: Node.Atom)
    private val exports = mutableListOf<Export>()
    private val elems = mutableListOf<Pair<Node.List, kotlin.collections.List<Node.Atom>>>()
    private val datas = mutableListOf<Pair<Node.List, ByteArray>>()
    private val dataNames = mutableMapOf<String, Int>()
    private var start: Node.Atom? = null
    private var usesDataCount = false

    fun fail(node: Node, message: String): Nothing = throw WasmAssemblyError("line ${node.line}: $message")

    fun assemble(): ByteArray {
        val fields = module.items.drop(1).map { it as? Node.List ?: fail(it, "expected a module field") }
        // Explicit types first: their indices are the ones `(type $t)` names.
        for (field in fields.filter { it.head == "type" }) declareType(field)
        for (field in fields) {
            when (field.head) {
                "type" -> {}
                "import" -> declareImport(field)
                "func" -> declareFunc(field)
                "global" -> declareGlobal(field)
                "memory" -> declareMemory(field)
                "table" -> declareTable(field)
                "export" -> declareExport(field)
                "elem" -> declareElem(field)
                "data" -> declareData(field)
                "start" -> start = field.items.getOrNull(1) as? Node.Atom ?: fail(field, "expected (start <func>)")
                else -> fail(field, "unknown module field '${field.head}'")
            }
        }
        val code = funcs.filter { it.import == null }.map { encodeBody(it) }
        return output(code)
    }

    // ── declarations ───────────────────────────────────────────────

    private fun valueType(node: Node): Int =
        VALUE_TYPES[(node as? Node.Atom)?.text] ?: fail(node, "unknown value type '${(node as? Node.Atom)?.text ?: "(…)"}'")

    private fun internType(type: FuncType): Int {
        val existing = types.indexOf(type)
        if (existing >= 0) return existing
        types += type
        return types.size - 1
    }

    private fun declareType(field: Node.List) {
        var rest = field.items.drop(1)
        val name = (rest.firstOrNull() as? Node.Atom)?.text?.takeIf { it.startsWith("$") }
        if (name != null) rest = rest.drop(1)
        val func = rest.singleOrNull() as? Node.List ?: fail(field, "expected (type (func …))")
        if (func.head != "func") fail(func, "only function types are supported")
        val (signature, _) = signature(func.items.drop(1), func)
        // Each explicit type gets its own index, even when it repeats one.
        types += signature
        if (name != null) typeNames[name] = types.size - 1
    }

    /**
     * Reads `(type $t)? (param …)* (result …)*` from the start of [items];
     * answers the signature and the parameter names (null where unnamed).
     */
    private fun signature(items: kotlin.collections.List<Node>, at: Node): Pair<FuncType, MutableList<String?>> {
        val params = mutableListOf<Int>()
        val paramNames = mutableListOf<String?>()
        val results = mutableListOf<Int>()
        var explicit: Int? = null
        for (item in items) {
            val list = item as? Node.List ?: continue
            when (list.head) {
                "type" -> explicit = typeIndex(list.items.getOrNull(1) ?: fail(list, "expected a type"))
                "param" -> {
                    val rest = list.items.drop(1)
                    val first = (rest.firstOrNull() as? Node.Atom)?.text
                    if (first != null && first.startsWith("$")) {
                        params += valueType(rest.getOrNull(1) ?: fail(list, "expected a parameter type"))
                        paramNames += first
                    } else {
                        rest.forEach { params += valueType(it); paramNames += null }
                    }
                }
                "result" -> list.items.drop(1).forEach { results += valueType(it) }
            }
        }
        val type = FuncType(params, results)
        if (explicit != null) {
            if (params.isEmpty() && results.isEmpty()) return types[explicit] to MutableList(types[explicit].params.size) { null }
            if (types[explicit] != type) fail(at, "the inline signature does not match the type it names")
        }
        return type to paramNames
    }

    private fun typeIndex(node: Node): Int {
        val text = (node as? Node.Atom)?.text ?: fail(node, "expected a type index")
        return typeNames[text] ?: text.toIntOrNull()?.takeIf { it in types.indices } ?: fail(node, "unknown type '$text'")
    }

    private fun declareImport(field: Node.List) {
        val module = field.items.getOrNull(1) as? Node.Str ?: fail(field, "expected an import module name")
        val name = field.items.getOrNull(2) as? Node.Str ?: fail(field, "expected an import field name")
        val desc = field.items.getOrNull(3) as? Node.List ?: fail(field, "expected an import description")
        if (desc.head != "func") fail(desc, "only function imports are supported")
        val rest = desc.items.drop(1)
        val id = (rest.firstOrNull() as? Node.Atom)?.text?.takeIf { it.startsWith("$") }
        val (signature, _) = signature(if (id != null) rest.drop(1) else rest, desc)
        if (funcs.any { it.import == null }) fail(field, "imports must come before the functions this module defines")
        if (id != null) funcNames[id] = funcs.size
        funcs += Func(id, internType(signature), null, module.bytes.decodeToString() to name.bytes.decodeToString())
    }

    private fun declareFunc(field: Node.List) {
        val rest = field.items.drop(1)
        val id = (rest.firstOrNull() as? Node.Atom)?.text?.takeIf { it.startsWith("$") }
        val (signature, _) = signature(rest, field)
        val index = funcs.size
        if (id != null) {
            if (id in funcNames) fail(field, "function $id is defined twice")
            funcNames[id] = index
        }
        for (item in rest) {
            val list = item as? Node.List ?: continue
            if (list.head == "export") exports += Export(exportName(list), 0, Node.Atom(id ?: index.toString(), list.line))
            if (list.head == "import") fail(list, "inline imports are not supported")
        }
        funcs += Func(id, internType(signature), field, null)
    }

    private fun exportName(list: Node.List): String =
        ((list.items.getOrNull(1) as? Node.Str) ?: fail(list, "expected an export name")).bytes.decodeToString()

    private fun declareGlobal(field: Node.List) {
        var rest = field.items.drop(1)
        val id = (rest.firstOrNull() as? Node.Atom)?.text?.takeIf { it.startsWith("$") }
        if (id != null) rest = rest.drop(1)
        val inlineExports = rest.filterIsInstance<Node.List>().filter { it.head == "export" }
        rest = rest.filterNot { it is Node.List && it.head == "export" }
        val typeNode = rest.getOrNull(0) ?: fail(field, "expected a global type")
        val (type, mutable) = if (typeNode is Node.List && typeNode.head == "mut") {
            valueType(typeNode.items.getOrNull(1) ?: fail(typeNode, "expected a type")) to true
        } else valueType(typeNode) to false
        val init = rest.getOrNull(1) as? Node.List ?: fail(field, "expected a global initializer")
        val index = globals.size
        if (id != null) globalNames[id] = index
        globals += Global(id, type, mutable, init)
        inlineExports.forEach { exports += Export(exportName(it), 3, Node.Atom(id ?: index.toString(), it.line)) }
    }

    private fun limits(items: kotlin.collections.List<Node>, at: Node): Limits {
        val numbers = items.filterIsInstance<Node.Atom>().filterNot { it.text.startsWith("$") || it.text == "funcref" || it.text == "externref" }
        val min = numbers.getOrNull(0)?.let { parseUnsigned(it) } ?: fail(at, "expected limits")
        val max = numbers.getOrNull(1)?.let { parseUnsigned(it) }
        return Limits(min, max)
    }

    private fun declareMemory(field: Node.List) {
        if (memory != null) fail(field, "one memory per module")
        val rest = field.items.drop(1)
        (rest.firstOrNull() as? Node.Atom)?.text?.takeIf { it.startsWith("$") }?.let { memoryNames[it] = 0 }
        rest.filterIsInstance<Node.List>().forEach { item ->
            when (item.head) {
                "export" -> exports += Export(exportName(item), 2, Node.Atom("0", item.line))
                else -> fail(item, "unsupported memory clause '${item.head}'")
            }
        }
        memory = limits(rest, field)
    }

    private fun declareTable(field: Node.List) {
        if (table != null) fail(field, "one table per module")
        val rest = field.items.drop(1)
        (rest.firstOrNull() as? Node.Atom)?.text?.takeIf { it.startsWith("$") }?.let { tableNames[it] = 0 }
        if (rest.none { it is Node.Atom && it.text == "funcref" }) fail(field, "only funcref tables are supported")
        rest.filterIsInstance<Node.List>().forEach { item ->
            if (item.head == "export") exports += Export(exportName(item), 1, Node.Atom("0", item.line)) else fail(item, "unsupported table clause")
        }
        table = limits(rest, field)
    }

    private fun declareExport(field: Node.List) {
        val name = exportName(field)
        val desc = field.items.getOrNull(2) as? Node.List ?: fail(field, "expected an export description")
        val ref = desc.items.getOrNull(1) as? Node.Atom ?: fail(desc, "expected what is exported")
        val kind = when (desc.head) {
            "func" -> 0
            "table" -> 1
            "memory" -> 2
            "global" -> 3
            else -> fail(desc, "unknown export kind '${desc.head}'")
        }
        exports += Export(name, kind, ref)
    }

    private fun declareElem(field: Node.List) {
        var rest = field.items.drop(1)
        if ((rest.firstOrNull() as? Node.Atom)?.text?.startsWith("$") == true) rest = rest.drop(1)
        if ((rest.firstOrNull() as? Node.List)?.head == "table") rest = rest.drop(1)
        val offset = rest.firstOrNull() as? Node.List ?: fail(field, "only active element segments with an offset are supported")
        val offsetExpr = if (offset.head == "offset") Node.List(offset.items.drop(1), offset.line) else offset
        val refs = rest.drop(1).filterNot { it is Node.Atom && it.text == "func" }
            .map { it as? Node.Atom ?: fail(it, "expected a function reference") }
        elems += offsetExpr to refs
    }

    private fun declareData(field: Node.List) {
        var rest = field.items.drop(1)
        (rest.firstOrNull() as? Node.Atom)?.text?.takeIf { it.startsWith("$") }?.let {
            dataNames[it] = datas.size
            rest = rest.drop(1)
        }
        if ((rest.firstOrNull() as? Node.List)?.head == "memory") rest = rest.drop(1)
        val offset = rest.firstOrNull() as? Node.List ?: fail(field, "only active data segments with an offset are supported")
        val offsetExpr = if (offset.head == "offset") Node.List(offset.items.drop(1), offset.line) else offset
        val sink = ByteSink()
        rest.drop(1).forEach { sink.bytes((it as? Node.Str ?: fail(it, "expected a string")).bytes) }
        datas += offsetExpr to sink.toByteArray()
    }

    // ── code ───────────────────────────────────────────────────────

    private fun funcIndex(node: Node): Int {
        val text = (node as? Node.Atom)?.text ?: fail(node, "expected a function")
        return funcNames[text] ?: text.toIntOrNull()?.takeIf { it in funcs.indices } ?: fail(node, "unknown function '$text'")
    }

    private fun globalIndex(node: Node): Int {
        val text = (node as? Node.Atom)?.text ?: fail(node, "expected a global")
        return globalNames[text] ?: text.toIntOrNull()?.takeIf { it in globals.indices } ?: fail(node, "unknown global '$text'")
    }

    private fun encodeBody(func: Func): ByteArray {
        val node = func.node!!
        val type = types[func.type]
        val locals = mutableListOf<Int>()
        val localNames = mutableMapOf<String, Int>()
        val body = mutableListOf<Node>()
        var paramIndex = 0
        var headerDone = false
        for (item in node.items.drop(1)) {
            if (item is Node.Atom && item.text.startsWith("$") && !headerDone && body.isEmpty()) continue
            val list = item as? Node.List
            when {
                list != null && !headerDone && list.head == "export" -> {}
                list != null && !headerDone && list.head == "type" -> {}
                list != null && !headerDone && list.head == "param" -> {
                    val rest = list.items.drop(1)
                    val first = (rest.firstOrNull() as? Node.Atom)?.text
                    if (first != null && first.startsWith("$")) {
                        localNames[first] = paramIndex++
                    } else paramIndex += rest.size
                }
                list != null && !headerDone && list.head == "result" -> {}
                list != null && !headerDone && list.head == "local" -> {
                    val rest = list.items.drop(1)
                    val first = (rest.firstOrNull() as? Node.Atom)?.text
                    if (first != null && first.startsWith("$")) {
                        if (first in localNames) fail(list, "local $first is declared twice")
                        localNames[first] = type.params.size + locals.size
                        locals += valueType(rest.getOrNull(1) ?: fail(list, "expected a local type"))
                    } else rest.forEach { locals += valueType(it) }
                }
                else -> { headerDone = true; body += item }
            }
        }
        val out = ByteSink()
        // Locals as runs of one type.
        val runs = mutableListOf<Pair<Int, Int>>()
        for (t in locals) {
            if (runs.isNotEmpty() && runs.last().second == t) runs[runs.size - 1] = runs.last().first + 1 to t
            else runs += 1 to t
        }
        out.u32(runs.size)
        runs.forEach { (count, t) -> out.u32(count); out.byte(t) }
        val code = CodeWriter(out, localNames, type.params.size + locals.size)
        code.sequence(body)
        out.byte(0x0B)
        val sized = ByteSink()
        sized.u32(out.size)
        sized.bytes(out.toByteArray())
        return sized.toByteArray()
    }

    /** Writes one function's (or one constant expression's) instructions. */
    private inner class CodeWriter(
        val out: ByteSink,
        val localNames: Map<String, Int>,
        val localCount: Int,
    ) {
        private val labels = ArrayDeque<String?>()

        fun sequence(items: kotlin.collections.List<Node>) {
            var i = 0
            while (i < items.size) i = instruction(items, i)
        }

        /** Writes the instruction starting at [items][[at]]; answers where the next one starts. */
        private fun instruction(items: kotlin.collections.List<Node>, at: Int): Int {
            val node = items[at]
            if (node is Node.List) {
                folded(node)
                return at + 1
            }
            val word = (node as? Node.Atom)?.text ?: fail(node, "expected an instruction")
            return when (word) {
                "block", "loop", "if" -> flatBlock(items, at, word)
                else -> {
                    val op = OPS[word] ?: fail(node, "unknown instruction '$word'")
                    var next = at + 1
                    next = immediates(op, word, node, items, next)
                    next
                }
            }
        }

        /** `block $l (result t) … end` and its `loop`/`if … else … end` siblings in flat form. */
        private fun flatBlock(items: kotlin.collections.List<Node>, at: Int, word: String): Int {
            var i = at + 1
            var label: String? = null
            (items.getOrNull(i) as? Node.Atom)?.text?.takeIf { it.startsWith("$") }?.let { label = it; i++ }
            val resultItems = mutableListOf<Node>()
            while ((items.getOrNull(i) as? Node.List)?.head.let { it == "result" || it == "type" || it == "param" }) resultItems += items[i++]
            out.byte(when (word) { "block" -> 0x02; "loop" -> 0x03; else -> 0x04 })
            blockType(resultItems, items[at])
            labels.addFirst(label)
            while (i < items.size) {
                val text = (items[i] as? Node.Atom)?.text
                if (text == "end") { i++; break }
                if (text == "else") { out.byte(0x05); i++; continue }
                i = instruction(items, i)
            }
            labels.removeFirst()
            out.byte(0x0B)
            // `end $label` may repeat the label.
            if ((items.getOrNull(i) as? Node.Atom)?.text == label && label != null) i++
            return i
        }

        private fun blockType(resultItems: kotlin.collections.List<Node>, at: Node) {
            val results = resultItems.filterIsInstance<Node.List>().filter { it.head == "result" }.flatMap { it.items.drop(1) }.map(::valueType)
            val params = resultItems.filterIsInstance<Node.List>().filter { it.head == "param" }.flatMap { it.items.drop(1) }.map(::valueType)
            val explicit = resultItems.filterIsInstance<Node.List>().firstOrNull { it.head == "type" }
            when {
                explicit != null -> out.s64(typeIndex(explicit.items.getOrNull(1) ?: fail(explicit, "expected a type")).toLong())
                params.isEmpty() && results.isEmpty() -> out.byte(0x40)
                params.isEmpty() && results.size == 1 -> out.byte(results.single())
                else -> out.s64(internType(FuncType(params, results)).toLong())
            }
        }

        private fun folded(node: Node.List) {
            val word = node.head ?: fail(node, "expected an instruction")
            val rest = node.items.drop(1)
            when (word) {
                "block", "loop" -> {
                    var i = 0
                    var label: String? = null
                    (rest.getOrNull(0) as? Node.Atom)?.text?.takeIf { it.startsWith("$") }?.let { label = it; i++ }
                    val resultItems = mutableListOf<Node>()
                    while ((rest.getOrNull(i) as? Node.List)?.head.let { it == "result" || it == "type" || it == "param" }) resultItems += rest[i++]
                    out.byte(if (word == "block") 0x02 else 0x03)
                    blockType(resultItems, node)
                    labels.addFirst(label)
                    sequence(rest.drop(i))
                    labels.removeFirst()
                    out.byte(0x0B)
                }
                "if" -> {
                    var i = 0
                    var label: String? = null
                    (rest.getOrNull(0) as? Node.Atom)?.text?.takeIf { it.startsWith("$") }?.let { label = it; i++ }
                    val resultItems = mutableListOf<Node>()
                    while ((rest.getOrNull(i) as? Node.List)?.head.let { it == "result" || it == "type" || it == "param" }) resultItems += rest[i++]
                    var thenNode: Node.List? = null
                    var elseNode: Node.List? = null
                    val condition = mutableListOf<Node>()
                    for (item in rest.drop(i)) {
                        val list = item as? Node.List
                        when {
                            list?.head == "then" -> thenNode = list
                            list?.head == "else" -> elseNode = list
                            else -> condition += item
                        }
                    }
                    sequence(condition)
                    out.byte(0x04)
                    blockType(resultItems, node)
                    labels.addFirst(label)
                    thenNode?.let { sequence(it.items.drop(1)) }
                    if (elseNode != null) {
                        out.byte(0x05)
                        sequence(elseNode.items.drop(1))
                    }
                    labels.removeFirst()
                    out.byte(0x0B)
                }
                else -> {
                    val op = OPS[word] ?: fail(node, "unknown instruction '$word'")
                    // Immediates come first in a folded instruction, operands after.
                    val all = rest
                    var operandStart = 0
                    while (operandStart < all.size && isImmediate(op, all, operandStart)) operandStart++
                    sequence(all.drop(operandStart))
                    val immediateItems = all.take(operandStart)
                    val consumed = immediates(op, word, node, immediateItems, 0)
                    if (consumed != immediateItems.size) fail(node, "unexpected operand to '$word'")
                }
            }
        }

        /** Whether [items][[index]] is still an immediate of [op] in folded form. */
        private fun isImmediate(op: Op, items: kotlin.collections.List<Node>, index: Int): Boolean {
            val node = items[index]
            if (node is Node.List) {
                // `call_indirect (type $t)` and `select (result t)` take their
                // first lists as immediates; every other list is an operand.
                return (op.imm == Imm.CALL_INDIRECT && node.head in setOf("type", "param", "result")) ||
                    (op.imm == Imm.SELECT && node.head == "result")
            }
            return node is Node.Atom
        }

        /** Writes [op] and its immediates, read from [items] starting at [from]; answers the index after them. */
        private fun immediates(op: Op, word: String, at: Node, items: kotlin.collections.List<Node>, from: Int): Int {
            var i = from
            fun nextAtom(what: String): Node.Atom = (items.getOrNull(i) as? Node.Atom ?: fail(at, "'$word' expects $what")).also { i++ }
            if (op.prefix != 0) {
                out.byte(op.prefix)
                out.u32(op.code)
            } else if (op.imm != Imm.SELECT) {
                out.byte(op.code)
            }
            when (op.imm) {
                Imm.NONE -> {}
                Imm.LOCAL -> {
                    val atom = nextAtom("a local")
                    val index = localNames[atom.text] ?: atom.text.toIntOrNull()?.takeIf { it in 0 until localCount }
                        ?: fail(atom, "unknown local '${atom.text}'")
                    out.u32(index)
                }
                Imm.GLOBAL -> out.u32(globalIndex(nextAtom("a global")))
                Imm.FUNC -> out.u32(funcIndex(nextAtom("a function")))
                Imm.LABEL -> out.u32(labelDepth(nextAtom("a label")))
                Imm.BR_TABLE -> {
                    val targets = mutableListOf<Int>()
                    while (i < items.size && items[i] is Node.Atom && isLabel((items[i] as Node.Atom).text)) targets += labelDepth(nextAtom("a label"))
                    if (targets.isEmpty()) fail(at, "br_table needs a default label")
                    out.u32(targets.size - 1)
                    targets.forEach { out.u32(it) }
                }
                Imm.CALL_INDIRECT -> {
                    var tableIndex = 0
                    (items.getOrNull(i) as? Node.Atom)?.let { atom ->
                        tableIndex = tableNames[atom.text] ?: atom.text.toIntOrNull() ?: fail(atom, "unknown table '${atom.text}'")
                        i++
                    }
                    val signatureItems = mutableListOf<Node>()
                    while ((items.getOrNull(i) as? Node.List)?.head.let { it == "type" || it == "param" || it == "result" }) signatureItems += items[i++]
                    val explicit = signatureItems.filterIsInstance<Node.List>().firstOrNull { it.head == "type" }
                    val typeIdx = if (explicit != null && signatureItems.size == 1) {
                        typeIndex(explicit.items.getOrNull(1) ?: fail(explicit, "expected a type"))
                    } else internType(signature(signatureItems, at).first)
                    out.u32(typeIdx)
                    out.u32(tableIndex)
                }
                Imm.SELECT -> {
                    val typed = (items.getOrNull(i) as? Node.List)?.takeIf { it.head == "result" }
                    if (typed != null) {
                        i++
                        out.byte(0x1C)
                        val ts = typed.items.drop(1).map(::valueType)
                        out.u32(ts.size)
                        ts.forEach(out::byte)
                    } else out.byte(0x1B)
                }
                Imm.MEM -> {
                    var offset = 0L
                    var align = 1L shl op.align
                    while (i < items.size) {
                        val text = (items[i] as? Node.Atom)?.text ?: break
                        when {
                            text.startsWith("offset=") -> offset = parseUnsigned(Node.Atom(text.removePrefix("offset="), items[i].line))
                            text.startsWith("align=") -> align = parseUnsigned(Node.Atom(text.removePrefix("align="), items[i].line))
                            else -> break
                        }
                        i++
                    }
                    if (align <= 0 || (align and (align - 1)) != 0L) fail(at, "alignment $align is not a power of two")
                    out.u32(63 - align.countLeadingZeroBits().toLong())
                    out.u32(offset)
                }
                Imm.MEMORY_INDEX -> {
                    (items.getOrNull(i) as? Node.Atom)?.takeIf { it.text.toIntOrNull() != null || it.text in memoryNames }?.let { i++ }
                    out.byte(0x00)
                }
                Imm.BULK_COPY -> out.bytes(byteArrayOf(0, 0))
                Imm.BULK_FILL -> out.byte(0)
                Imm.DATA -> {
                    usesDataCount = true
                    val atom = nextAtom("a data segment")
                    out.u32(dataNames[atom.text] ?: atom.text.toIntOrNull() ?: fail(atom, "unknown data segment '${atom.text}'"))
                    if (word == "memory.init") out.byte(0)
                }
                Imm.I32 -> out.s64(parseInt(nextAtom("an i32"), 32))
                Imm.I64 -> out.s64(parseInt(nextAtom("an i64"), 64))
                Imm.F32 -> writeFloat32(parseFloat(nextAtom("an f32"), single = true).toFloat())
                Imm.F64 -> writeFloat64(parseFloat(nextAtom("an f64"), single = false))
                Imm.LANE -> {
                    val lane = parseUnsigned(nextAtom("a lane index"))
                    if (lane > 15) fail(at, "lane $lane is out of range")
                    out.byte(lane.toInt())
                }
                Imm.V128 -> {
                    val shape = nextAtom("a vector shape").text
                    val (count, width) = when (shape) {
                        "i8x16" -> 16 to 1
                        "i16x8" -> 8 to 2
                        "i32x4" -> 4 to 4
                        "i64x2" -> 2 to 8
                        "f32x4" -> 4 to 4
                        "f64x2" -> 2 to 8
                        else -> fail(at, "unknown vector shape '$shape'")
                    }
                    repeat(count) {
                        val atom = nextAtom("$count lanes")
                        val bits: Long = when (shape) {
                            "f32x4" -> parseFloat(atom, single = true).toFloat().toRawBits().toLong()
                            "f64x2" -> parseFloat(atom, single = false).toRawBits()
                            else -> parseInt(atom, width * 8)
                        }
                        for (b in 0 until width) out.byte(((bits ushr (8 * b)) and 0xFF).toInt())
                    }
                }
            }
            return i
        }

        private fun isLabel(text: String) = text.startsWith("$") || text.toIntOrNull() != null

        private fun labelDepth(atom: Node.Atom): Int {
            atom.text.toIntOrNull()?.let { return it }
            val depth = labels.indexOf(atom.text)
            if (depth < 0) fail(atom, "unknown label '${atom.text}'")
            return depth
        }

        private fun writeFloat32(value: Float) {
            val bits = value.toRawBits()
            for (b in 0 until 4) out.byte((bits ushr (8 * b)) and 0xFF)
        }

        private fun writeFloat64(value: Double) {
            val bits = value.toRawBits()
            for (b in 0 until 8) out.byte(((bits ushr (8 * b)) and 0xFF).toInt())
        }
    }

    // ── numbers ────────────────────────────────────────────────────

    private fun digits(atom: Node.Atom): String = atom.text.replace("_", "")

    fun parseUnsigned(atom: Node.Atom): Long {
        val text = digits(atom)
        val value = if (text.startsWith("0x")) text.drop(2).toLongOrNull(16) else text.toLongOrNull()
        return value?.takeIf { it >= 0 } ?: fail(atom, "expected an unsigned number, found '${atom.text}'")
    }

    /** An integer of [bits] width, written signed or unsigned; answered sign-extended from that width. */
    fun parseInt(atom: Node.Atom, bits: Int): Long {
        var text = digits(atom)
        val negative = text.startsWith("-")
        if (negative || text.startsWith("+")) text = text.drop(1)
        val magnitude: ULong = (if (text.startsWith("0x")) text.drop(2).toULongOrNull(16) else text.toULongOrNull())
            ?: fail(atom, "expected an integer, found '${atom.text}'")
        val limit = if (bits == 64) ULong.MAX_VALUE else (1uL shl bits) - 1uL
        if (!negative && magnitude > limit) fail(atom, "${atom.text} does not fit in $bits bits")
        if (negative && magnitude > (1uL shl (bits - 1))) fail(atom, "${atom.text} does not fit in $bits bits")
        val raw = if (negative) (0uL - magnitude) else magnitude
        // Sign-extend from the width so the LEB128 encoding is the canonical one.
        val shift = 64 - bits
        return (raw.toLong() shl shift) shr shift
    }

    /** A float literal: decimal, hexadecimal (`0x1.8p3`), `inf` or `nan[:0x…]`. */
    fun parseFloat(atom: Node.Atom, single: Boolean): Double {
        var text = digits(atom)
        var negative = false
        if (text.startsWith("-")) { negative = true; text = text.drop(1) } else if (text.startsWith("+")) text = text.drop(1)
        val magnitude: Double = when {
            text == "inf" -> Double.POSITIVE_INFINITY
            text == "nan" -> Double.NaN
            text.startsWith("nan:0x") -> {
                val payload = text.removePrefix("nan:0x").toLongOrNull(16) ?: fail(atom, "bad NaN payload")
                if (single) return Float.fromBits((if (negative) 0xFF800000.toInt() else 0x7F800000) or payload.toInt()).toDouble()
                return Double.fromBits((if (negative) Long.MIN_VALUE else 0L) or 0x7FF0000000000000L or payload)
            }
            text.startsWith("0x") -> hexFloat(text.drop(2), atom)
            else -> text.toDoubleOrNull() ?: fail(atom, "expected a float, found '${atom.text}'")
        }
        if (single && text != "inf" && text != "nan" && !text.startsWith("0x")) {
            // Round once, from the decimal text, to single precision.
            val f = text.toFloatOrNull() ?: fail(atom, "expected a float, found '${atom.text}'")
            return (if (negative) -f else f).toDouble()
        }
        return if (negative) -magnitude else magnitude
    }

    private fun hexFloat(text: String, atom: Node.Atom): Double {
        val pIndex = text.indexOfFirst { it == 'p' || it == 'P' }
        val mantissa = if (pIndex >= 0) text.substring(0, pIndex) else text
        val exponent = if (pIndex >= 0) text.substring(pIndex + 1).toIntOrNull() ?: fail(atom, "bad exponent") else 0
        val dot = mantissa.indexOf('.')
        val whole = if (dot >= 0) mantissa.substring(0, dot) else mantissa
        val fraction = if (dot >= 0) mantissa.substring(dot + 1) else ""
        var value = 0.0
        for (c in whole) value = value * 16 + (c.digitToIntOrNull(16) ?: fail(atom, "bad hex float"))
        var scale = 1.0 / 16
        for (c in fraction) {
            value += (c.digitToIntOrNull(16) ?: fail(atom, "bad hex float")) * scale
            scale /= 16
        }
        var result = value
        var e = exponent
        while (e > 0) { result *= 2; e-- }
        while (e < 0) { result /= 2; e++ }
        return result
    }

    // ── output ─────────────────────────────────────────────────────

    private fun constantExpression(node: Node.List): ByteArray {
        val sink = ByteSink()
        CodeWriter(sink, emptyMap(), 0).sequence(listOf(node))
        sink.byte(0x0B)
        return sink.toByteArray()
    }

    private fun output(code: kotlin.collections.List<ByteArray>): ByteArray {
        val out = ByteSink()
        out.bytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00))
        // Instructions may have added block types while the code was written,
        // so the type section is built last but written first.
        val bodies = code
        val globalInits = globals.map { constantExpression(it.init) }
        val elemOffsets = elems.map { constantExpression(it.first) }
        val dataOffsets = datas.map { constantExpression(it.first) }

        ByteSink().apply {
            u32(types.size)
            for (t in types) {
                byte(0x60)
                u32(t.params.size); t.params.forEach(::byte)
                u32(t.results.size); t.results.forEach(::byte)
            }
        }.let { out.section(1, it) }

        val imports = funcs.filter { it.import != null }
        if (imports.isNotEmpty()) ByteSink().apply {
            u32(imports.size)
            imports.forEach { f -> name(f.import!!.first); name(f.import.second); byte(0x00); u32(f.type) }
        }.let { out.section(2, it) }

        val defined = funcs.filter { it.import == null }
        if (defined.isNotEmpty()) ByteSink().apply {
            u32(defined.size)
            defined.forEach { u32(it.type) }
        }.let { out.section(3, it) }

        table?.let { limits ->
            ByteSink().apply {
                u32(1)
                byte(0x70)
                limits(this, limits)
            }.let { out.section(4, it) }
        }

        memory?.let { limits ->
            ByteSink().apply {
                u32(1)
                limits(this, limits)
            }.let { out.section(5, it) }
        }

        if (globals.isNotEmpty()) ByteSink().apply {
            u32(globals.size)
            globals.forEachIndexed { index, g ->
                byte(g.type)
                byte(if (g.mutable) 1 else 0)
                bytes(globalInits[index])
            }
        }.let { out.section(6, it) }

        if (exports.isNotEmpty()) ByteSink().apply {
            u32(exports.size)
            for (e in exports) {
                name(e.name)
                byte(e.kind)
                val index = when (e.kind) {
                    0 -> funcIndex(e.ref)
                    3 -> globalIndex(e.ref)
                    else -> e.ref.text.toIntOrNull() ?: memoryNames[e.ref.text] ?: tableNames[e.ref.text] ?: fail(e.ref, "unknown export '${e.ref.text}'")
                }
                u32(index)
            }
        }.let { out.section(7, it) }

        start?.let { ByteSink().apply { u32(funcIndex(it)) }.let { s -> out.section(8, s) } }

        if (elems.isNotEmpty()) ByteSink().apply {
            u32(elems.size)
            elems.forEachIndexed { index, (_, refs) ->
                byte(0x00)
                bytes(elemOffsets[index])
                u32(refs.size)
                refs.forEach { u32(funcIndex(it)) }
            }
        }.let { out.section(9, it) }

        if (usesDataCount) ByteSink().apply { u32(datas.size) }.let { out.section(12, it) }

        if (bodies.isNotEmpty()) ByteSink().apply {
            u32(bodies.size)
            bodies.forEach(::bytes)
        }.let { out.section(10, it) }

        if (datas.isNotEmpty()) ByteSink().apply {
            u32(datas.size)
            datas.forEachIndexed { index, (_, bytes) ->
                byte(0x00)
                bytes(dataOffsets[index])
                u32(bytes.size)
                bytes(bytes)
            }
        }.let { out.section(11, it) }

        if (withNames) {
            val named = funcs.withIndex().filter { it.value.name != null }
            if (named.isNotEmpty()) {
                val functionNames = ByteSink().apply {
                    u32(named.size)
                    named.forEach { (index, f) -> u32(index); name(f.name!!.removePrefix("$")) }
                }
                val body = ByteSink().apply {
                    name("name")
                    byte(1)
                    u32(functionNames.size)
                    bytes(functionNames.toByteArray())
                }
                out.section(0, body)
            }
        }
        return out.toByteArray()
    }

    private fun limits(sink: ByteSink, limits: Limits) {
        if (limits.max == null) {
            sink.byte(0x00); sink.u32(limits.min)
        } else {
            sink.byte(0x01); sink.u32(limits.min); sink.u32(limits.max)
        }
    }
}
