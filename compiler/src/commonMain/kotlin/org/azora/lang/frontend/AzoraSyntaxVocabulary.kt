/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.azora.lang.frontend

/**
 * Canonical lexical vocabulary shared by the compiler and language tooling.
 *
 * Contextual words deliberately do not appear in [reservedKeywords]: the lexer
 * emits them as identifiers and the parser gives them meaning only in the
 * corresponding grammar position.
 */
object AzoraSyntaxVocabulary {
    val reservedKeywords: Map<String, TokenType> = mapOf(
        "var" to TokenType.VAR,
        "fin" to TokenType.FIN,
        "let" to TokenType.LET,
        "val" to TokenType.VAL,
        "func" to TokenType.FUNC,
        "return" to TokenType.RETURN,
        "if" to TokenType.IF,
        "else" to TokenType.ELSE,
        "inline" to TokenType.INLINE,
        "deepinline" to TokenType.DEEPINLINE,
        "noinline" to TokenType.NOINLINE,
        "realm" to TokenType.REALM,
        "scope" to TokenType.SCOPE,
        "test" to TokenType.TEST,
        "assert" to TokenType.ASSERT,
        "trace" to TokenType.TRACE,
        "panic" to TokenType.PANIC,
        "macro" to TokenType.MACRO,
        "for" to TokenType.FOR,
        "while" to TokenType.WHILE,
        "loop" to TokenType.LOOP,
        "in" to TokenType.IN,
        "break" to TokenType.BREAK,
        "continue" to TokenType.CONTINUE,
        "by" to TokenType.BY,
        "reverse" to TokenType.REVERSE,
        "with" to TokenType.WITH,
        "oper" to TokenType.OPER,
        "annot" to TokenType.ANNOT,
        "bind" to TokenType.BIND,
        "error" to TokenType.ERROR,
        "alloc" to TokenType.ALLOC,
        "purge" to TokenType.PURGE,
        "unsafe" to TokenType.UNSAFE,
        "take" to TokenType.TAKE,
        "await" to TokenType.AWAIT,
        "delay" to TokenType.DELAY,
        "bridge" to TokenType.BRIDGE,
        "solo" to TokenType.SOLO,
        "inject" to TokenType.INJECT,
        "wrap" to TokenType.WRAP,
        "rescue" to TokenType.RESCUE,
        "mem" to TokenType.MEM,
        "rem" to TokenType.REM,
        "ret" to TokenType.RET,
        "effect" to TokenType.EFFECT,
        "prop" to TokenType.PROP,
        "ctor" to TokenType.CTOR,
        "dtor" to TokenType.DTOR,
        "out" to TokenType.OUT,
        "expose" to TokenType.EXPOSE,
        "confine" to TokenType.CONFINE,
        "threadlocal" to TokenType.THREADLOCAL,
        "pack" to TokenType.PACK,
        "enum" to TokenType.ENUM,
        "when" to TokenType.WHEN,
        "throw" to TokenType.THROW,
        "try" to TokenType.TRY,
        "catch" to TokenType.CATCH,
        "impl" to TokenType.IMPL,
        "spec" to TokenType.SPEC,
        "defer" to TokenType.DEFER,
        "typealias" to TokenType.TYPEALIAS,
        "variant" to TokenType.VARIANT,
        "as" to TokenType.AS,
        "is" to TokenType.IS,
        "null" to TokenType.NULL,
        "import" to TokenType.IMPORT,
        "use" to TokenType.USE,
        "true" to TokenType.TRUE,
        "false" to TokenType.FALSE,
    )

    val contextualKeywords: Set<String> = setOf("module", "union", "async", "where", "without")
}
