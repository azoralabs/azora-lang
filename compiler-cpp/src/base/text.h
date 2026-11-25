// Text helpers with Kotlin's semantics (CPP_PORT_PLAN.md §6.7).
//
// Text is UTF-8 in std::string. Kotlin strings are UTF-16, so wherever a
// length or an index leaves a function (a column, a node's length) it is
// counted in UTF-16 units with u16len(). Index arithmetic that stays inside
// one function works on bytes, consistently, and needs no conversion.
//
// A lone surrogate, which a Kotlin string can hold, is kept as its 3-byte
// generalized UTF-8 form so that a round trip through UTF-16 is lossless.
#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace azora {

// ---------------------------------------------------------------------------
// UTF-8 <-> UTF-16
// ---------------------------------------------------------------------------

/** Decodes [bytes] as the JVM's UTF-8 decoder does, replacing malformed input with U+FFFD. */
std::u16string decodeUtf8(std::string_view bytes);

/**
 * Decodes text this program produced: like decodeUtf8, but a 3-byte encoded
 * surrogate (a lone one a Kotlin string could hold) is read back as itself.
 */
std::u16string decodeWtf8(std::string_view bytes);

/** Encodes UTF-16 text as UTF-8; a lone surrogate becomes its own 3-byte sequence. */
std::string encodeUtf8(std::u16string_view text);

/** Appends one UTF-16 unit or code point to [out] (code points above U+FFFF as 4 bytes). */
void appendCodePoint(std::string& out, uint32_t cp);

/** The number of UTF-16 units [text] holds: Kotlin's `String.length`. */
int32_t u16len(std::string_view text);

/** Kotlin `String.hashCode()`: Java's polynomial over UTF-16 units, wrapping. */
int32_t javaHash(std::string_view text);

/** Compares by UTF-16 unit, as Kotlin's `String.compareTo` does. */
int compareUtf16(std::string_view a, std::string_view b);

// ---------------------------------------------------------------------------
// Kotlin String functions on UTF-8 text
// ---------------------------------------------------------------------------

inline bool startsWith(std::string_view s, std::string_view prefix) {
    return s.size() >= prefix.size() && s.compare(0, prefix.size(), prefix) == 0;
}
inline bool endsWith(std::string_view s, std::string_view suffix) {
    return s.size() >= suffix.size() && s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}
inline bool contains(std::string_view s, std::string_view part) { return s.find(part) != std::string_view::npos; }
inline bool contains(std::string_view s, char c) { return s.find(c) != std::string_view::npos; }

std::string removePrefix(std::string_view s, std::string_view prefix);
std::string removeSuffix(std::string_view s, std::string_view suffix);
std::string substringBefore(std::string_view s, std::string_view delimiter);
std::string substringAfter(std::string_view s, std::string_view delimiter);
std::string substringBeforeLast(std::string_view s, std::string_view delimiter);
std::string substringAfterLast(std::string_view s, std::string_view delimiter);
std::string replaceAll(std::string_view s, std::string_view from, std::string_view to);
std::vector<std::string> split(std::string_view s, std::string_view delimiter);
std::string join(const std::vector<std::string>& parts, std::string_view separator);
std::string repeat(std::string_view s, int count);

/** Kotlin `trim()`: drops leading and trailing `Char.isWhitespace()` units. */
std::string trim(std::string_view s);
std::string trimStart(std::string_view s);
std::string trimEnd(std::string_view s);
bool isBlank(std::string_view s);

/** Kotlin `uppercase()` / `lowercase()` (per-unit mappings; see chars.h). */
std::string uppercase(std::string_view s);
std::string lowercase(std::string_view s);

/** The UTF-16 units of [s], for functions that walk text the way Kotlin does. */
inline std::u16string units(std::string_view s) { return decodeWtf8(s); }

/** Kotlin `Int.toString()` and `Long.toString(radix)`. */
std::string str(long long value);
std::string str(unsigned long long value);
inline std::string str(int value) { return str((long long)value); }
inline std::string str(long value) { return str((long long)value); }
inline std::string str(unsigned value) { return str((unsigned long long)value); }
inline std::string str(unsigned long value) { return str((unsigned long long)value); }
std::string toStringRadix(int64_t value, int radix);
inline std::string str(bool value) { return value ? "true" : "false"; }

}  // namespace azora
