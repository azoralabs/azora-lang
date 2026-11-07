/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.backend

/**
 * UTF-8 for the WebAssembly target, as [LlvmUtf8Runtime] is for LLVM.
 *
 * A Wasm `String` is `[ i32 byte length | UTF-8 bytes ]` and a `Char` an `i32`
 * code point. Length, indexing, slicing and searching count code points; a
 * byte that does not begin a well-formed sequence decodes as U+FFFD and
 * advances one byte, and a code point that is not a scalar value is written as
 * U+FFFD. Decoding checks every byte against the length, so it never reads
 * past a string. An index outside a string traps, as an array index does.
 *
 * Written with `@` for WAT's `$`, which a Kotlin string would read as a
 * template; [text] puts the `$` back.
 */
internal object WasmUtf8Runtime {
    fun text(source: String): String = source.replace("@", "$")

    val CORE = text(
        """
  (global @__utf8_at (mut i32) (i32.const 0))
  (func @__utf8_cont (param @s i32) (param @len i32) (param @i i32) (result i32)
    (local @b i32)
    (if (i32.ge_u (local.get @i) (local.get @len)) (then (return (i32.const -1))))
    (local.set @b (i32.load8_u (i32.add (i32.add (local.get @s) (i32.const 4)) (local.get @i))))
    (if (result i32) (i32.eq (i32.and (local.get @b) (i32.const 192)) (i32.const 128))
      (then (i32.and (local.get @b) (i32.const 63)))
      (else (i32.const -1))))
  (func @__utf8_next (param @s i32) (param @pos i32) (result i32)
    (local @len i32) (local @b0 i32) (local @c1 i32) (local @c2 i32) (local @c3 i32) (local @cp i32)
    (local.set @len (i32.load (local.get @s)))
    (local.set @b0 (i32.load8_u (i32.add (i32.add (local.get @s) (i32.const 4)) (local.get @pos))))
    (if (i32.lt_u (local.get @b0) (i32.const 128)) (then
      (global.set @__utf8_at (i32.add (local.get @pos) (i32.const 1)))
      (return (local.get @b0))))
    (if (i32.and (i32.ge_u (local.get @b0) (i32.const 194)) (i32.le_u (local.get @b0) (i32.const 223))) (then
      (local.set @c1 (call @__utf8_cont (local.get @s) (local.get @len) (i32.add (local.get @pos) (i32.const 1))))
      (if (i32.ge_s (local.get @c1) (i32.const 0)) (then
        (global.set @__utf8_at (i32.add (local.get @pos) (i32.const 2)))
        (return (i32.or (i32.shl (i32.and (local.get @b0) (i32.const 31)) (i32.const 6)) (local.get @c1)))))))
    (if (i32.and (i32.ge_u (local.get @b0) (i32.const 224)) (i32.le_u (local.get @b0) (i32.const 239))) (then
      (local.set @c1 (call @__utf8_cont (local.get @s) (local.get @len) (i32.add (local.get @pos) (i32.const 1))))
      (if (i32.ge_s (local.get @c1) (i32.const 0)) (then
        (local.set @c2 (call @__utf8_cont (local.get @s) (local.get @len) (i32.add (local.get @pos) (i32.const 2))))
        (if (i32.ge_s (local.get @c2) (i32.const 0)) (then
          (local.set @cp (i32.or (i32.or (i32.shl (i32.and (local.get @b0) (i32.const 15)) (i32.const 12))
                                         (i32.shl (local.get @c1) (i32.const 6))) (local.get @c2)))
          (if (i32.eqz (i32.or (i32.lt_u (local.get @cp) (i32.const 2048))
                               (i32.and (i32.ge_u (local.get @cp) (i32.const 55296)) (i32.le_u (local.get @cp) (i32.const 57343))))) (then
            (global.set @__utf8_at (i32.add (local.get @pos) (i32.const 3)))
            (return (local.get @cp))))))))))
    (if (i32.and (i32.ge_u (local.get @b0) (i32.const 240)) (i32.le_u (local.get @b0) (i32.const 244))) (then
      (local.set @c1 (call @__utf8_cont (local.get @s) (local.get @len) (i32.add (local.get @pos) (i32.const 1))))
      (if (i32.ge_s (local.get @c1) (i32.const 0)) (then
        (local.set @c2 (call @__utf8_cont (local.get @s) (local.get @len) (i32.add (local.get @pos) (i32.const 2))))
        (if (i32.ge_s (local.get @c2) (i32.const 0)) (then
          (local.set @c3 (call @__utf8_cont (local.get @s) (local.get @len) (i32.add (local.get @pos) (i32.const 3))))
          (if (i32.ge_s (local.get @c3) (i32.const 0)) (then
            (local.set @cp (i32.or (i32.or (i32.or (i32.shl (i32.and (local.get @b0) (i32.const 7)) (i32.const 18))
                                                   (i32.shl (local.get @c1) (i32.const 12)))
                                           (i32.shl (local.get @c2) (i32.const 6))) (local.get @c3)))
            (if (i32.eqz (i32.or (i32.lt_u (local.get @cp) (i32.const 65536)) (i32.gt_u (local.get @cp) (i32.const 1114111)))) (then
              (global.set @__utf8_at (i32.add (local.get @pos) (i32.const 4)))
              (return (local.get @cp))))))))))))
    (global.set @__utf8_at (i32.add (local.get @pos) (i32.const 1)))
    (i32.const 65533))
  (func @__str_length (param @s i32) (result i32)
    (local @len i32) (local @pos i32) (local @n i32)
    (local.set @len (i32.load (local.get @s)))
    (block @done (loop @next
      (br_if @done (i32.ge_u (local.get @pos) (local.get @len)))
      (drop (call @__utf8_next (local.get @s) (local.get @pos)))
      (local.set @pos (global.get @__utf8_at))
      (local.set @n (i32.add (local.get @n) (i32.const 1)))
      (br @next)))
    (local.get @n))
  (func @__utf8_offset (param @s i32) (param @index i32) (result i32)
    (local @len i32) (local @pos i32) (local @n i32)
    (local.set @len (i32.load (local.get @s)))
    (block @done (loop @next
      (br_if @done (i32.eq (local.get @n) (local.get @index)))
      (if (i32.ge_u (local.get @pos) (local.get @len)) (then (return (i32.const -1))))
      (drop (call @__utf8_next (local.get @s) (local.get @pos)))
      (local.set @pos (global.get @__utf8_at))
      (local.set @n (i32.add (local.get @n) (i32.const 1)))
      (br @next)))
    (local.get @pos))
  (func @__utf8_index (param @s i32) (param @bytes i32) (result i32)
    (local @pos i32) (local @n i32)
    (block @done (loop @next
      (br_if @done (i32.ge_u (local.get @pos) (local.get @bytes)))
      (drop (call @__utf8_next (local.get @s) (local.get @pos)))
      (local.set @pos (global.get @__utf8_at))
      (local.set @n (i32.add (local.get @n) (i32.const 1)))
      (br @next)))
    (local.get @n))
  (func @__char_at (param @s i32) (param @i i32) (result i32)
    (local @off i32)
    (local.set @off (call @__utf8_offset (local.get @s) (local.get @i)))
    (if (i32.or (i32.lt_s (local.get @off) (i32.const 0)) (i32.ge_u (local.get @off) (i32.load (local.get @s)))) (then unreachable))
    (call @__utf8_next (local.get @s) (local.get @off)))
  (func @__bytes_eq (param @a i32) (param @b i32) (param @n i32) (result i32)
    (local @i i32)
    (block @end (loop @next
      (br_if @end (i32.ge_u (local.get @i) (local.get @n)))
      (if (i32.ne (i32.load8_u (i32.add (local.get @a) (local.get @i))) (i32.load8_u (i32.add (local.get @b) (local.get @i))))
        (then (return (i32.const 0))))
      (local.set @i (i32.add (local.get @i) (i32.const 1)))
      (br @next)))
    (i32.const 1))
  (func @__byte_find (param @s i32) (param @sub i32) (result i32)
    (local @ls i32) (local @lsub i32) (local @i i32)
    (local.set @ls (i32.load (local.get @s)))
    (local.set @lsub (i32.load (local.get @sub)))
    (block @none (loop @scan
      (br_if @none (i32.gt_s (local.get @i) (i32.sub (local.get @ls) (local.get @lsub))))
      (if (call @__bytes_eq (i32.add (i32.add (local.get @s) (i32.const 4)) (local.get @i)) (i32.add (local.get @sub) (i32.const 4)) (local.get @lsub))
        (then (return (local.get @i))))
      (local.set @i (i32.add (local.get @i) (i32.const 1)))
      (br @scan)))
    (i32.const -1))
  (func @__utf8_scalar (param @cp i32) (result i32)
    (if (result i32) (i32.or (i32.or (i32.lt_s (local.get @cp) (i32.const 0)) (i32.gt_s (local.get @cp) (i32.const 1114111)))
                             (i32.and (i32.ge_s (local.get @cp) (i32.const 55296)) (i32.le_s (local.get @cp) (i32.const 57343))))
      (then (i32.const 65533)) (else (local.get @cp))))
  (func @__utf8_width (param @cp i32) (result i32)
    (local @v i32)
    (local.set @v (call @__utf8_scalar (local.get @cp)))
    (if (result i32) (i32.lt_u (local.get @v) (i32.const 128)) (then (i32.const 1))
      (else (if (result i32) (i32.lt_u (local.get @v) (i32.const 2048)) (then (i32.const 2))
        (else (if (result i32) (i32.lt_u (local.get @v) (i32.const 65536)) (then (i32.const 3)) (else (i32.const 4))))))))
  (func @__utf8_put (param @cp i32) (param @dst i32) (result i32)
    (local @v i32)
    (local.set @v (call @__utf8_scalar (local.get @cp)))
    (if (i32.lt_u (local.get @v) (i32.const 128)) (then
      (i32.store8 (local.get @dst) (local.get @v))
      (return (i32.const 1))))
    (if (i32.lt_u (local.get @v) (i32.const 2048)) (then
      (i32.store8 (local.get @dst) (i32.or (i32.shr_u (local.get @v) (i32.const 6)) (i32.const 192)))
      (i32.store8 offset=1 (local.get @dst) (i32.or (i32.and (local.get @v) (i32.const 63)) (i32.const 128)))
      (return (i32.const 2))))
    (if (i32.lt_u (local.get @v) (i32.const 65536)) (then
      (i32.store8 (local.get @dst) (i32.or (i32.shr_u (local.get @v) (i32.const 12)) (i32.const 224)))
      (i32.store8 offset=1 (local.get @dst) (i32.or (i32.and (i32.shr_u (local.get @v) (i32.const 6)) (i32.const 63)) (i32.const 128)))
      (i32.store8 offset=2 (local.get @dst) (i32.or (i32.and (local.get @v) (i32.const 63)) (i32.const 128)))
      (return (i32.const 3))))
    (i32.store8 (local.get @dst) (i32.or (i32.shr_u (local.get @v) (i32.const 18)) (i32.const 240)))
    (i32.store8 offset=1 (local.get @dst) (i32.or (i32.and (i32.shr_u (local.get @v) (i32.const 12)) (i32.const 63)) (i32.const 128)))
    (i32.store8 offset=2 (local.get @dst) (i32.or (i32.and (i32.shr_u (local.get @v) (i32.const 6)) (i32.const 63)) (i32.const 128)))
    (i32.store8 offset=3 (local.get @dst) (i32.or (i32.and (local.get @v) (i32.const 63)) (i32.const 128)))
    (i32.const 4))
  (func @__char_to_str (param @cp i32) (result i32)
    (local @p i32)
    (local.set @p (call @__alloc (i32.const 8)))
    (i32.store (local.get @p) (call @__utf8_put (local.get @cp) (i32.add (local.get @p) (i32.const 4))))
    (local.get @p))
"""
    )

    /** Each string bridge's signature and body, over [CORE]. */
    val BRIDGES: Map<String, Pair<String, String>> = mapOf(
        "stringLength" to ("(param @s i32) (result i32)" to "(call @__str_length (local.get @s))"),
        "charAt" to ("(param @s i32) (param @i i32) (result i32)" to "(call @__char_at (local.get @s) (local.get @i))"),
        "substring" to (
            "(param @s i32) (param @start i32) (param @end i32) (result i32) (local @from i32) (local @to i32) (local @n i32) (local @p i32)" to
                """(if (i32.or (i32.lt_s (local.get @start) (i32.const 0)) (i32.gt_s (local.get @start) (local.get @end))) (then unreachable))
    (local.set @from (call @__utf8_offset (local.get @s) (local.get @start)))
    (local.set @to (call @__utf8_offset (local.get @s) (local.get @end)))
    (if (i32.lt_s (local.get @to) (i32.const 0)) (then unreachable))
    (local.set @n (i32.sub (local.get @to) (local.get @from)))
    (local.set @p (call @__alloc (i32.add (i32.const 4) (local.get @n))))
    (i32.store (local.get @p) (local.get @n))
    (memory.copy (i32.add (local.get @p) (i32.const 4)) (i32.add (i32.add (local.get @s) (i32.const 4)) (local.get @from)) (local.get @n))
    (local.get @p)"""
            ),
        "indexOf" to (
            "(param @s i32) (param @sub i32) (result i32) (local @at i32)" to
                """(local.set @at (call @__byte_find (local.get @s) (local.get @sub)))
    (if (result i32) (i32.lt_s (local.get @at) (i32.const 0)) (then (i32.const -1))
      (else (call @__utf8_index (local.get @s) (local.get @at))))"""
            ),
        "contains" to ("(param @s i32) (param @sub i32) (result i32)" to "(i32.ge_s (call @__byte_find (local.get @s) (local.get @sub)) (i32.const 0))"),
        "startsWith" to (
            "(param @s i32) (param @p i32) (result i32)" to
                """(if (result i32) (i32.gt_u (i32.load (local.get @p)) (i32.load (local.get @s))) (then (i32.const 0))
      (else (call @__bytes_eq (i32.add (local.get @s) (i32.const 4)) (i32.add (local.get @p) (i32.const 4)) (i32.load (local.get @p)))))"""
            ),
        "endsWith" to (
            "(param @s i32) (param @suf i32) (result i32)" to
                """(if (result i32) (i32.gt_u (i32.load (local.get @suf)) (i32.load (local.get @s))) (then (i32.const 0))
      (else (call @__bytes_eq
        (i32.add (i32.add (local.get @s) (i32.const 4)) (i32.sub (i32.load (local.get @s)) (i32.load (local.get @suf))))
        (i32.add (local.get @suf) (i32.const 4)) (i32.load (local.get @suf)))))"""
            ),
        "ord" to ("(param @c i32) (result i32)" to "(local.get @c)"),
        "chr" to ("(param @i i32) (result i32)" to "(local.get @i)"),
        "isDigit" to ("(param @c i32) (result i32)" to "(i32.and (i32.ge_s (local.get @c) (i32.const 48)) (i32.le_s (local.get @c) (i32.const 57)))"),
        "isAlpha" to (
            "(param @c i32) (result i32)" to
                "(i32.or (i32.and (i32.ge_s (local.get @c) (i32.const 65)) (i32.le_s (local.get @c) (i32.const 90))) " +
                "(i32.and (i32.ge_s (local.get @c) (i32.const 97)) (i32.le_s (local.get @c) (i32.const 122))))"
            ),
        // `Array<Char>`: an i32 length, then one i32 code point per slot.
        "toChars" to (
            "(param @s i32) (result i32) (local @n i32) (local @a i32) (local @pos i32) (local @i i32)" to
                """(local.set @n (call @__str_length (local.get @s)))
    (local.set @a (call @__alloc (i32.add (i32.const 4) (i32.mul (local.get @n) (i32.const 4)))))
    (i32.store (local.get @a) (local.get @n))
    (block @done (loop @next
      (br_if @done (i32.ge_s (local.get @i) (local.get @n)))
      (i32.store (i32.add (i32.add (local.get @a) (i32.const 4)) (i32.mul (local.get @i) (i32.const 4)))
        (call @__utf8_next (local.get @s) (local.get @pos)))
      (local.set @pos (global.get @__utf8_at))
      (local.set @i (i32.add (local.get @i) (i32.const 1)))
      (br @next)))
    (local.get @a)"""
            ),
        "fromChars" to (
            "(param @c i32) (result i32) (local @n i32) (local @i i32) (local @bytes i32) (local @p i32) (local @at i32)" to
                """(local.set @n (i32.load (local.get @c)))
    (block @measured (loop @measure
      (br_if @measured (i32.ge_s (local.get @i) (local.get @n)))
      (local.set @bytes (i32.add (local.get @bytes)
        (call @__utf8_width (i32.load (i32.add (i32.add (local.get @c) (i32.const 4)) (i32.mul (local.get @i) (i32.const 4)))))))
      (local.set @i (i32.add (local.get @i) (i32.const 1)))
      (br @measure)))
    (local.set @p (call @__alloc (i32.add (i32.const 4) (local.get @bytes))))
    (i32.store (local.get @p) (local.get @bytes))
    (local.set @i (i32.const 0))
    (block @written (loop @write
      (br_if @written (i32.ge_s (local.get @i) (local.get @n)))
      (local.set @at (i32.add (local.get @at)
        (call @__utf8_put (i32.load (i32.add (i32.add (local.get @c) (i32.const 4)) (i32.mul (local.get @i) (i32.const 4))))
                          (i32.add (i32.add (local.get @p) (i32.const 4)) (local.get @at)))))
      (local.set @i (i32.add (local.get @i) (i32.const 1)))
      (br @write)))
    (local.get @p)"""
            ),
    ).mapValues { (_, v) -> text(v.first) to text(v.second) }
}
