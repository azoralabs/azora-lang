/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * String indices are `Int`, as in Kotlin, Java, C#, Swift and Go (user decision,
 * 2026-10-01). `strCharAt` and `strSlice` took `UInt` while `strLength`,
 * `charAt` and `substring` took and returned `Int`. So `strSlice` could not pass
 * its indices on to `substring`, and no caller could hand it a length - which
 * stopped `std.decimal` from compiling.
 */
class StdStringIndexTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun aLengthIsAnIndex() = assertEquals("ello\nhe\ne\nl", run("""
        import std.io
        import std.string
        func main() {
            fin text = "hello"
            println(strSlice(text, 1, strLength(text)))
            println(strSlice(text, 0, 2))
            println(strCharAt(text, 1) catch '?')
            println(strCharAt(text, strLength(text) - 2) catch '?')
        }
    """))

    @Test fun anIndexOutsideTheStringIsOutOfBounds() = assertEquals("?\n?\n?", run("""
        import std.io
        import std.string
        func main() {
            println(strCharAt("hello", -1) catch '?')
            println(strCharAt("hello", 5) catch '?')
            println(strCharAt("", 0) catch '?')
        }
    """))
}
