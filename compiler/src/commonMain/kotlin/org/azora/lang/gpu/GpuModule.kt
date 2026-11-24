/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.gpu

/** The source header that selects the GPU compiler for an ordinary Azora file. */
object GpuModule {
    private const val trivia = """(?:[\s\uFEFF]|//[^\r\n]*|/\*[\s\S]*?\*/)*"""
    private val header = Regex("""\A${trivia}@Execution\s*\(\s*"GPU"\s*\)${trivia}module\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)""")

    fun isGpuSource(source: String): Boolean = moduleName(source) != null

    internal fun moduleName(source: String): String? =
        if ("@Execution" in source) header.find(source)?.groupValues?.get(1) else null
}
