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

package org.azora.lang.ir

/**
 * Maps a method symbol to an identifier that is legal in every backend.
 *
 * Operator methods carry punctuation in their member name (`oper==`, `oper[]=`,
 * `oper..`), so the naive mangling `Type_oper==` is not a valid LLVM/JS/Wasm
 * symbol. Each punctuation character is replaced by a distinct token so that
 * distinct operators never collide (`oper==` → `oper_eq_eq`, `oper!=` →
 * `oper_bang_eq`). Letters, digits and `_` pass through unchanged, so ordinary
 * method names are unaffected.
 *
 * This is applied at the single points where a method's free-function symbol is
 * built (symbol registration and IR lowering), keeping definition and call sites
 * in agreement across all backends.
 */
fun mangleMethodSymbol(name: String): String {
    if (name.all { it.isLetterOrDigit() || it == '_' }) return name
    val sb = StringBuilder(name.length + 8)
    for (c in name) {
        if (c.isLetterOrDigit() || c == '_') {
            sb.append(c)
        } else {
            sb.append(operatorToken(c))
        }
    }
    return sb.toString()
}

private fun operatorToken(c: Char): String = when (c) {
    '=' -> "_eq"
    '!' -> "_bang"
    '<' -> "_lt"
    '>' -> "_gt"
    '+' -> "_plus"
    '-' -> "_minus"
    '*' -> "_star"
    '/' -> "_slash"
    '%' -> "_pct"
    '~' -> "_tilde"
    '.' -> "_dot"
    '#' -> "_hash"
    '[' -> "_lb"
    ']' -> "_rb"
    '^' -> "_caret"
    '&' -> "_amp"
    '|' -> "_pipe"
    '?' -> "_q"
    ':' -> "_colon"
    else -> "_u${c.code}"
}
