/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.backend

/**
 * UTF-8 for the LLVM target.
 *
 * A native `String` is NUL-terminated UTF-8, and a `Char` is a Unicode code
 * point (`i32`). Length, indexing, slicing and searching count code points
 * (user decision, 2026-11-07), so they decode as they go. A byte that does
 * not begin a well-formed sequence - a stray continuation, an overlong form,
 * a surrogate, a value above U+10FFFF, a sequence cut short - decodes as
 * U+FFFD and advances one byte; a code point that is not a scalar value is
 * written as U+FFFD. Decoding never reads past the terminator: a sequence is
 * checked byte by byte, and the NUL is not a continuation byte.
 */
internal object LlvmUtf8Runtime {
    /** The helpers every code-point operation below uses. */
    val CORE = """
; runtime: UTF-8. Decodes the code point at %s + *%pos and advances *%pos past it.
define i32 @__azora_utf8_next(i8* %s, i64* %pos) {
entry:
  %at = load i64, i64* %pos
  %p0 = getelementptr i8, i8* %s, i64 %at
  %b0 = load i8, i8* %p0
  %u0 = zext i8 %b0 to i32
  %ascii = icmp ult i32 %u0, 128
  br i1 %ascii, label %one, label %lead2
one:
  %n1 = add i64 %at, 1
  store i64 %n1, i64* %pos
  ret i32 %u0
lead2:
  %ge2 = icmp uge i32 %u0, 194
  %le2 = icmp ule i32 %u0, 223
  %is2 = and i1 %ge2, %le2
  br i1 %is2, label %two, label %lead3
two:
  %c21 = call i32 @__azora_utf8_cont(i8* %s, i64 %at, i64 1)
  %bad21 = icmp slt i32 %c21, 0
  br i1 %bad21, label %invalid, label %two.ok
two.ok:
  %h2 = and i32 %u0, 31
  %h2s = shl i32 %h2, 6
  %cp2 = or i32 %h2s, %c21
  %n2 = add i64 %at, 2
  store i64 %n2, i64* %pos
  ret i32 %cp2
lead3:
  %ge3 = icmp uge i32 %u0, 224
  %le3 = icmp ule i32 %u0, 239
  %is3 = and i1 %ge3, %le3
  br i1 %is3, label %three, label %lead4
three:
  %c31 = call i32 @__azora_utf8_cont(i8* %s, i64 %at, i64 1)
  %bad31 = icmp slt i32 %c31, 0
  br i1 %bad31, label %invalid, label %three.b
three.b:
  %c32 = call i32 @__azora_utf8_cont(i8* %s, i64 %at, i64 2)
  %bad32 = icmp slt i32 %c32, 0
  br i1 %bad32, label %invalid, label %three.ok
three.ok:
  %h3 = and i32 %u0, 15
  %h3s = shl i32 %h3, 12
  %m3 = shl i32 %c31, 6
  %t3 = or i32 %h3s, %m3
  %cp3 = or i32 %t3, %c32
  %over3 = icmp ult i32 %cp3, 2048
  %sur3a = icmp uge i32 %cp3, 55296
  %sur3b = icmp ule i32 %cp3, 57343
  %sur3 = and i1 %sur3a, %sur3b
  %bad3 = or i1 %over3, %sur3
  br i1 %bad3, label %invalid, label %three.done
three.done:
  %n3 = add i64 %at, 3
  store i64 %n3, i64* %pos
  ret i32 %cp3
lead4:
  %ge4 = icmp uge i32 %u0, 240
  %le4 = icmp ule i32 %u0, 244
  %is4 = and i1 %ge4, %le4
  br i1 %is4, label %four, label %invalid
four:
  %c41 = call i32 @__azora_utf8_cont(i8* %s, i64 %at, i64 1)
  %bad41 = icmp slt i32 %c41, 0
  br i1 %bad41, label %invalid, label %four.b
four.b:
  %c42 = call i32 @__azora_utf8_cont(i8* %s, i64 %at, i64 2)
  %bad42 = icmp slt i32 %c42, 0
  br i1 %bad42, label %invalid, label %four.c
four.c:
  %c43 = call i32 @__azora_utf8_cont(i8* %s, i64 %at, i64 3)
  %bad43 = icmp slt i32 %c43, 0
  br i1 %bad43, label %invalid, label %four.ok
four.ok:
  %h4 = and i32 %u0, 7
  %h4s = shl i32 %h4, 18
  %m41 = shl i32 %c41, 12
  %m42 = shl i32 %c42, 6
  %t41 = or i32 %h4s, %m41
  %t42 = or i32 %t41, %m42
  %cp4 = or i32 %t42, %c43
  %lo4 = icmp ult i32 %cp4, 65536
  %hi4 = icmp ugt i32 %cp4, 1114111
  %bad4 = or i1 %lo4, %hi4
  br i1 %bad4, label %invalid, label %four.done
four.done:
  %n4 = add i64 %at, 4
  store i64 %n4, i64* %pos
  ret i32 %cp4
invalid:
  %ni = add i64 %at, 1
  store i64 %ni, i64* %pos
  ret i32 65533
}

; The six payload bits of byte %at + %k, or -1 when it is not a continuation byte.
define i32 @__azora_utf8_cont(i8* %s, i64 %at, i64 %k) {
entry:
  %i = add i64 %at, %k
  %p = getelementptr i8, i8* %s, i64 %i
  %b = load i8, i8* %p
  %u = zext i8 %b to i32
  %top = and i32 %u, 192
  %ok = icmp eq i32 %top, 128
  %bits = and i32 %u, 63
  %r = select i1 %ok, i32 %bits, i32 -1
  ret i32 %r
}

; How many code points %s holds.
define i32 @__azora_str_length(i8* %s) {
entry:
  %pos = alloca i64
  store i64 0, i64* %pos
  br label %loop
loop:
  %n = phi i32 [ 0, %entry ], [ %n1, %step ]
  %at = load i64, i64* %pos
  %p = getelementptr i8, i8* %s, i64 %at
  %b = load i8, i8* %p
  %end = icmp eq i8 %b, 0
  br i1 %end, label %done, label %step
step:
  %cp = call i32 @__azora_utf8_next(i8* %s, i64* %pos)
  %n1 = add i32 %n, 1
  br label %loop
done:
  ret i32 %n
}

; The byte offset of code point %index (the terminator's for the length), or -1 past it.
define i64 @__azora_utf8_offset(i8* %s, i32 %index) {
entry:
  %pos = alloca i64
  store i64 0, i64* %pos
  br label %loop
loop:
  %n = phi i32 [ 0, %entry ], [ %n1, %step ]
  %reached = icmp eq i32 %n, %index
  br i1 %reached, label %found, label %more
more:
  %at = load i64, i64* %pos
  %p = getelementptr i8, i8* %s, i64 %at
  %b = load i8, i8* %p
  %end = icmp eq i8 %b, 0
  br i1 %end, label %short, label %step
step:
  %cp = call i32 @__azora_utf8_next(i8* %s, i64* %pos)
  %n1 = add i32 %n, 1
  br label %loop
found:
  %r = load i64, i64* %pos
  ret i64 %r
short:
  ret i64 -1
}

; The code point index of byte offset %bytes, which starts a code point.
define i32 @__azora_utf8_index(i8* %s, i64 %bytes) {
entry:
  %pos = alloca i64
  store i64 0, i64* %pos
  br label %loop
loop:
  %n = phi i32 [ 0, %entry ], [ %n1, %step ]
  %at = load i64, i64* %pos
  %more = icmp slt i64 %at, %bytes
  br i1 %more, label %step, label %done
step:
  %cp = call i32 @__azora_utf8_next(i8* %s, i64* %pos)
  %n1 = add i32 %n, 1
  br label %loop
done:
  ret i32 %n
}

; How many bytes %cp takes in UTF-8; a value that is not a scalar takes U+FFFD's three.
define i64 @__azora_utf8_width(i32 %cp) {
entry:
  %v = call i32 @__azora_utf8_scalar(i32 %cp)
  %w1 = icmp ult i32 %v, 128
  %w2 = icmp ult i32 %v, 2048
  %w3 = icmp ult i32 %v, 65536
  %a = select i1 %w3, i64 3, i64 4
  %b = select i1 %w2, i64 2, i64 %a
  %r = select i1 %w1, i64 1, i64 %b
  ret i64 %r
}

; %cp when it is a Unicode scalar value, else U+FFFD.
define i32 @__azora_utf8_scalar(i32 %cp) {
entry:
  %neg = icmp slt i32 %cp, 0
  %big = icmp sgt i32 %cp, 1114111
  %sa = icmp sge i32 %cp, 55296
  %sb = icmp sle i32 %cp, 57343
  %sur = and i1 %sa, %sb
  %out = or i1 %neg, %big
  %bad = or i1 %out, %sur
  %r = select i1 %bad, i32 65533, i32 %cp
  ret i32 %r
}

; Writes %cp as UTF-8 at %dst and answers how many bytes it wrote.
define i64 @__azora_utf8_put(i32 %cp, i8* %dst) {
entry:
  %v = call i32 @__azora_utf8_scalar(i32 %cp)
  %is1 = icmp ult i32 %v, 128
  br i1 %is1, label %w1, label %c2
w1:
  %b10 = trunc i32 %v to i8
  store i8 %b10, i8* %dst
  ret i64 1
c2:
  %is2 = icmp ult i32 %v, 2048
  br i1 %is2, label %w2, label %c3
w2:
  %x20 = lshr i32 %v, 6
  %y20 = or i32 %x20, 192
  %b20 = trunc i32 %y20 to i8
  %x21 = and i32 %v, 63
  %y21 = or i32 %x21, 128
  %b21 = trunc i32 %y21 to i8
  store i8 %b20, i8* %dst
  %p21 = getelementptr i8, i8* %dst, i64 1
  store i8 %b21, i8* %p21
  ret i64 2
c3:
  %is3 = icmp ult i32 %v, 65536
  br i1 %is3, label %w3, label %w4
w3:
  %x30 = lshr i32 %v, 12
  %y30 = or i32 %x30, 224
  %b30 = trunc i32 %y30 to i8
  %x31a = lshr i32 %v, 6
  %x31 = and i32 %x31a, 63
  %y31 = or i32 %x31, 128
  %b31 = trunc i32 %y31 to i8
  %x32 = and i32 %v, 63
  %y32 = or i32 %x32, 128
  %b32 = trunc i32 %y32 to i8
  store i8 %b30, i8* %dst
  %p31 = getelementptr i8, i8* %dst, i64 1
  store i8 %b31, i8* %p31
  %p32 = getelementptr i8, i8* %dst, i64 2
  store i8 %b32, i8* %p32
  ret i64 3
w4:
  %x40 = lshr i32 %v, 18
  %y40 = or i32 %x40, 240
  %b40 = trunc i32 %y40 to i8
  %x41a = lshr i32 %v, 12
  %x41 = and i32 %x41a, 63
  %y41 = or i32 %x41, 128
  %b41 = trunc i32 %y41 to i8
  %x42a = lshr i32 %v, 6
  %x42 = and i32 %x42a, 63
  %y42 = or i32 %x42, 128
  %b42 = trunc i32 %y42 to i8
  %x43 = and i32 %v, 63
  %y43 = or i32 %x43, 128
  %b43 = trunc i32 %y43 to i8
  store i8 %b40, i8* %dst
  %p41 = getelementptr i8, i8* %dst, i64 1
  store i8 %b41, i8* %p41
  %p42 = getelementptr i8, i8* %dst, i64 2
  store i8 %b42, i8* %p42
  %p43 = getelementptr i8, i8* %dst, i64 3
  store i8 %b43, i8* %p43
  ret i64 4
}

; A new string holding the one code point %cp.
define i8* @__azora_char_to_str(i32 %cp) {
entry:
  %buf = call i8* @__azora_alloc(i64 5)
  %w = call i64 @__azora_utf8_put(i32 %cp, i8* %buf)
  %end = getelementptr i8, i8* %buf, i64 %w
  store i8 0, i8* %end
  ret i8* %buf
}

; Code point %i of %s, or the index panic.
define i32 @__azora_char_at(i8* %s, i32 %i) {
entry:
  %pos = alloca i64
  %off = call i64 @__azora_utf8_offset(i8* %s, i32 %i)
  %missing = icmp slt i64 %off, 0
  br i1 %missing, label %fail, label %check
check:
  %p = getelementptr i8, i8* %s, i64 %off
  %b = load i8, i8* %p
  %atEnd = icmp eq i8 %b, 0
  br i1 %atEnd, label %fail, label %read
read:
  store i64 %off, i64* %pos
  %cp = call i32 @__azora_utf8_next(i8* %s, i64* %pos)
  ret i32 %cp
fail:
  %len = call i32 @__azora_str_length(i8* %s)
  %i64 = sext i32 %i to i64
  %l64 = sext i32 %len to i64
  call void @__azora_index_fail(i64 %i64, i64 %l64)
  unreachable
}

; A slice that does not lie inside its string.
define void @__azora_substring_fail(i32 %start, i32 %end, i32 %size) noreturn {
entry:
  %fmt = getelementptr [${SUBSTRING_FAIL.length + 1} x i8], [${SUBSTRING_FAIL.length + 1} x i8]* @.azora.substring.fail, i64 0, i64 0
  %r = call i32 (i8*, ...) @printf(i8* %fmt, i32 %start, i32 %end, i32 %size)
  call void @__azora_abort()
  unreachable
}

@.azora.substring.fail = private unnamed_addr constant [${SUBSTRING_FAIL.length + 1} x i8] c"${SUBSTRING_FAIL.replace("\n", "\\0A")}\00"
""".trimIndent()

    /** The panic a slice outside its string prints, as the interpreter words it. */
    const val SUBSTRING_FAIL = "panic: substring %d..<%d out of bounds for size %d\n"

    /** The string bridges, each over [CORE]: code points in, code points out. */
    val BRIDGES = mapOf(
        "stringLength" to """
define i32 @stringLength(i8* %s) {
entry:
  %n = call i32 @__azora_str_length(i8* %s)
  ret i32 %n
}""",
        "charAt" to """
define i32 @charAt(i8* %s, i32 %i) {
entry:
  %cp = call i32 @__azora_char_at(i8* %s, i32 %i)
  ret i32 %cp
}""",
        "substring" to """
define i8* @substring(i8* %s, i32 %start, i32 %end) {
entry:
  %neg = icmp slt i32 %start, 0
  %rev = icmp sgt i32 %start, %end
  %bad = or i1 %neg, %rev
  br i1 %bad, label %fail, label %find
find:
  %from = call i64 @__azora_utf8_offset(i8* %s, i32 %start)
  %to = call i64 @__azora_utf8_offset(i8* %s, i32 %end)
  %miss = icmp slt i64 %to, 0
  br i1 %miss, label %fail, label %copy
copy:
  %len = sub i64 %to, %from
  %size = add i64 %len, 1
  %buf = call i8* @__azora_alloc(i64 %size)
  %src = getelementptr i8, i8* %s, i64 %from
  %cp = call i8* @memcpy(i8* %buf, i8* %src, i64 %len)
  %tp = getelementptr i8, i8* %buf, i64 %len
  store i8 0, i8* %tp
  ret i8* %buf
fail:
  %count = call i32 @__azora_str_length(i8* %s)
  call void @__azora_substring_fail(i32 %start, i32 %end, i32 %count)
  unreachable
}""",
        "indexOf" to """
define i32 @indexOf(i8* %s, i8* %sub) {
entry:
  %p = call i8* @strstr(i8* %s, i8* %sub)
  %isnull = icmp eq i8* %p, null
  br i1 %isnull, label %no, label %found
found:
  %pi = ptrtoint i8* %p to i64
  %si = ptrtoint i8* %s to i64
  %diff = sub i64 %pi, %si
  %r = call i32 @__azora_utf8_index(i8* %s, i64 %diff)
  ret i32 %r
no:
  ret i32 -1
}""",
        "ord" to """
define i32 @ord(i32 %c) {
entry:
  ret i32 %c
}""",
        "chr" to """
define i32 @chr(i32 %i) {
entry:
  ret i32 %i
}""",
        "isDigit" to """
define i1 @isDigit(i32 %c) {
entry:
  %ge = icmp sge i32 %c, 48
  %le = icmp sle i32 %c, 57
  %r = and i1 %ge, %le
  ret i1 %r
}""",
        "isAlpha" to """
define i1 @isAlpha(i32 %c) {
entry:
  %g1 = icmp sge i32 %c, 65
  %l1 = icmp sle i32 %c, 90
  %up = and i1 %g1, %l1
  %g2 = icmp sge i32 %c, 97
  %l2 = icmp sle i32 %c, 122
  %lo = and i1 %g2, %l2
  %r = or i1 %up, %lo
  ret i1 %r
}""",
        // `Array<Char>`: an i64 length, then one i32 code point per slot.
        "toChars" to """
define i8* @toChars(i8* %s) {
entry:
  %pos = alloca i64
  %count = call i32 @__azora_str_length(i8* %s)
  %c64 = sext i32 %count to i64
  %bytes = mul i64 %c64, 4
  %size = add i64 %bytes, 8
  %arr = call i8* @__azora_alloc(i64 %size)
  %lenp = bitcast i8* %arr to i64*
  store i64 %c64, i64* %lenp
  %data.raw = getelementptr i8, i8* %arr, i64 8
  %data = bitcast i8* %data.raw to i32*
  store i64 0, i64* %pos
  br label %loop
loop:
  %i = phi i64 [ 0, %entry ], [ %i1, %body ]
  %more = icmp slt i64 %i, %c64
  br i1 %more, label %body, label %done
body:
  %cp = call i32 @__azora_utf8_next(i8* %s, i64* %pos)
  %slot = getelementptr i32, i32* %data, i64 %i
  store i32 %cp, i32* %slot
  %i1 = add i64 %i, 1
  br label %loop
done:
  ret i8* %arr
}""",
        "fromChars" to """
define i8* @fromChars(i8* %c) {
entry:
  %lenp = bitcast i8* %c to i64*
  %len = load i64, i64* %lenp
  %data.raw = getelementptr i8, i8* %c, i64 8
  %data = bitcast i8* %data.raw to i32*
  br label %measure
measure:
  %i = phi i64 [ 0, %entry ], [ %i1, %mbody ]
  %bytes = phi i64 [ 0, %entry ], [ %bytes1, %mbody ]
  %more = icmp slt i64 %i, %len
  br i1 %more, label %mbody, label %alloc
mbody:
  %mslot = getelementptr i32, i32* %data, i64 %i
  %mcp = load i32, i32* %mslot
  %w = call i64 @__azora_utf8_width(i32 %mcp)
  %bytes1 = add i64 %bytes, %w
  %i1 = add i64 %i, 1
  br label %measure
alloc:
  %size = add i64 %bytes, 1
  %buf = call i8* @__azora_alloc(i64 %size)
  br label %write
write:
  %j = phi i64 [ 0, %alloc ], [ %j1, %wbody ]
  %at = phi i64 [ 0, %alloc ], [ %at1, %wbody ]
  %wmore = icmp slt i64 %j, %len
  br i1 %wmore, label %wbody, label %done
wbody:
  %wslot = getelementptr i32, i32* %data, i64 %j
  %wcp = load i32, i32* %wslot
  %dst = getelementptr i8, i8* %buf, i64 %at
  %wj = call i64 @__azora_utf8_put(i32 %wcp, i8* %dst)
  %at1 = add i64 %at, %wj
  %j1 = add i64 %j, 1
  br label %write
done:
  %endp = getelementptr i8, i8* %buf, i64 %at
  store i8 0, i8* %endp
  ret i8* %buf
}""",
    )
}
