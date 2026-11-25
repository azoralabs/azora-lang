// Kotlin Int/Long arithmetic (CPP_PORT_PLAN.md §6.7): two's-complement
// wrapping, shift counts masked to the width, and division that never traps.
#pragma once

#include <cstdint>
#include <limits>

namespace azora {

inline int64_t kadd(int64_t a, int64_t b) { return (int64_t)((uint64_t)a + (uint64_t)b); }
inline int64_t ksub(int64_t a, int64_t b) { return (int64_t)((uint64_t)a - (uint64_t)b); }
inline int64_t kmul(int64_t a, int64_t b) { return (int64_t)((uint64_t)a * (uint64_t)b); }
inline int64_t kneg(int64_t a) { return (int64_t)(0 - (uint64_t)a); }
/** Kotlin `a shl n` on Long: the count is masked to 0..63. */
inline int64_t kshl(int64_t a, int32_t n) { return (int64_t)((uint64_t)a << (n & 63)); }
/** Kotlin `a shr n` on Long (arithmetic). */
inline int64_t kshr(int64_t a, int32_t n) { return a >> (n & 63); }
/** Kotlin `a ushr n` on Long. */
inline int64_t kushr(int64_t a, int32_t n) { return (int64_t)((uint64_t)a >> (n & 63)); }
/** Kotlin `a / b` on Long, b != 0: MIN / -1 is MIN, as on the JVM. */
inline int64_t kdiv(int64_t a, int64_t b) {
    if (b == -1) return kneg(a);
    return a / b;
}
/** Kotlin `a % b` on Long, b != 0. */
inline int64_t krem(int64_t a, int64_t b) {
    if (b == -1) return 0;
    return a % b;
}
/** Kotlin `Long.toInt()`: the low 32 bits. */
inline int32_t ktoInt(int64_t a) { return (int32_t)(uint32_t)(uint64_t)a; }

}  // namespace azora
