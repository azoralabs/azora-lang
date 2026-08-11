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
 * `oper..`), so the naive mangling `Type_oper==` is not a valid LLVM/Wasm
 * symbol. Each punctuation character is replaced by a distinct token so that
 * distinct operators never collide (`oper==` → `oper_eq_eq`, `oper!=` →
 * `oper_bang_eq`). Letters, digits and `_` pass through unchanged, so ordinary
 * method names are unaffected.
 *
 * This is applied at the single points where a method's free-function symbol is
 * built (symbol registration and IR lowering), keeping definition and call sites
 * in agreement across all backends.
 */
/**
 * True when [symbol] denotes the declaration named [local], whatever realm it
 * was canonicalized into.
 *
 * A canonical symbol is `__<realm path>_<local>` (see `IrSymbolCanonicalizer`),
 * so matching on the tail lets a backend intercept a builtin without naming the
 * realm the standard library happens to declare it in. An unmangled symbol -
 * a compiler intrinsic like `__dbg`, or a program's own function - matches only
 * itself.
 */
fun symbolDenotes(symbol: String, local: String): Boolean =
    symbol == local || (symbol.startsWith("__") && symbol.endsWith("_$local"))

/**
 * [symbol] written the way the source writes it, for diagnostics.
 *
 * The frontend joins a realm path to a declaration with `__` (`std__println`);
 * that is an internal spelling, not one anyone typed, so it does not belong in a
 * message pointed at the author - `undefined function 'std__println'` names
 * something the language has no syntax for. It renders back as `std::println`.
 *
 * A leading `__` marks a compiler-generated symbol (`__dbg`, the canonical
 * `__std_println`, a specialization like `__Vec_Double_3`). Those have no source
 * spelling to restore, so they are left exactly as they are.
 */
fun sourceSymbol(symbol: String): String =
    if (!symbol.startsWith("__") && "__" in symbol) symbol.split("__").joinToString("::") else symbol

fun mangleMethodSymbol(name: String): String {
    // A name that needs no escaping is already whatever the rest of the compiler
    // registered and looks up, separators included; normalizing it here would
    // rename declarations that nothing else renames.
    if (name.all { it.isLetterOrDigit() || it == '_' }) return name
    val sb = StringBuilder(name.length + 8)
    for (c in name) {
        if (c.isLetterOrDigit() || c == '_') {
            sb.append(c)
        } else {
            sb.append(operatorToken(c))
        }
    }
    return collapseSeparators(sb.toString())
}

/**
 * [symbol] with one `_` between segments, and its leading `__` kept if it had one.
 *
 * A method symbol is built by joining names that may already be canonical - the
 * owner of `oper+` on a specialization is `__Vec_Double_3`, and the operand key
 * that distinguishes the overload can be one too. Joining them verbatim doubles a
 * separator mid-symbol (`..._u64__Vec_Int_2`), which no longer matches the one
 * spelling the ABI defines: `__` once, at the front, then single `_` throughout.
 */
private fun collapseSeparators(symbol: String): String {
    val leading = if (symbol.startsWith("__")) "__" else ""
    return leading + symbol.removePrefix(leading).replace(Regex("_{2,}"), "_")
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
    // The operand-type discriminator an overloaded operator carries in its member
    // name; without a mapping it fell through to the char-code escape (`_u64`).
    '@' -> "_at"
    else -> "_u${c.code}"
}
