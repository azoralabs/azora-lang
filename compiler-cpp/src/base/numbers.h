// Number text as the JVM writes and reads it (CPP_PORT_PLAN.md §6.7).
#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace azora {

/** Java `Double.toString(d)` (JDK 19+: shortest, `1.0`, `1.0E10`, `1.0E-5`). */
std::string javaDoubleToString(double d);

/** Java `Float.toString(f)`. */
std::string javaFloatToString(float f);

/**
 * Java `Double.parseDouble(s)` restricted to the decimal forms the lexer
 * produces (digits, `.`, exponent). Throws NumberFormatException's text,
 * `For input string: "s"`, for anything it does not accept.
 */
double javaParseDouble(std::string_view s);

/** Kotlin `String.toLongOrNull(radix)`. */
std::optional<int64_t> toLongOrNull(std::string_view s, int radix);

/** Kotlin `String.toULongOrNull(radix)`, as its bit pattern. */
std::optional<uint64_t> toULongOrNull(std::string_view s, int radix);

/** Kotlin `String.toIntOrNull(radix)`. */
std::optional<int32_t> toIntOrNull(std::string_view s, int radix);

/** Kotlin `String.toInt(radix)`: Java's Integer.parseInt, throwing its NumberFormatException text. */
int32_t javaParseInt(std::string_view s, int radix);

/** Kotlin `String.toLong(radix)`: Java's Long.parseLong. */
int64_t javaParseLong(std::string_view s, int radix);

/** Raw IEEE bits, for dumps. */
uint64_t doubleBits(double d);
uint32_t floatBits(float f);

}  // namespace azora
