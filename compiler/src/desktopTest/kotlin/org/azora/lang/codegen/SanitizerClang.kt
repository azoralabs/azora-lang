/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import java.io.File

/**
 * The clang that native sanitizer probes compile and link with.
 *
 * A sanitizer runtime has to match the operating system it runs on. On macOS
 * the platform toolchain's does; a separately installed LLVM's may not, and a
 * Homebrew clang 21 build was seen to hang at startup before `main` in every
 * `-fsanitize=address` binary, which a probe reports as a timeout rather than
 * as anything about the program. So the order is: `AZORA_CLANG` when it is set
 * (the Engine's build honours the same variable), then the platform's
 * `/usr/bin/clang`, then whatever `clang` is first on `PATH`.
 */
internal object SanitizerClang {
    fun find(purpose: String): String? {
        val explicit = System.getenv("AZORA_CLANG")?.takeIf { it.isNotBlank() }
        if (explicit != null) {
            check(File(explicit).canExecute()) { "AZORA_CLANG=$explicit is not an executable" }
            return explicit
        }
        val candidates = listOf("/usr/bin/clang") +
            System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }.map { "$it/clang" }
        val result = candidates.firstOrNull { File(it).canExecute() }
        check(result != null || System.getenv("AZORA_REQUIRE_NATIVE_TESTS") != "1") { "$purpose requires clang" }
        return result
    }
}
