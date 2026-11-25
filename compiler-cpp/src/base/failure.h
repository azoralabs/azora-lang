// Kotlin's error(), require() and check(), and the runtime exceptions whose
// message can reach output. One plain struct, thrown by value; it inherits
// from nothing (CPP_PORT_PLAN.md §6.6).
#pragma once

#include <string>

namespace azora {

enum class FailureKind {
    IllegalState,       // Kotlin error() / check()
    IllegalArgument,    // Kotlin require()
    NumberFormat,       // java.lang.NumberFormatException
    IndexOutOfBounds,   // java.lang.IndexOutOfBoundsException
    NoSuchElement,      // java.util.NoSuchElementException (first(), single(), ...)
};

struct CompileFailure {
    FailureKind kind;
    std::string message;
};

/** Kotlin `error(message)`. */
[[noreturn]] void fail(std::string message);

/** Kotlin `require(false) { message }`. */
[[noreturn]] void failArgument(std::string message);

/** A failure of a given kind, for runtime exceptions whose text reaches output. */
[[noreturn]] void failWith(FailureKind kind, std::string message);

/** Code that Kotlin's exhaustive `when` proves unreachable. */
[[noreturn]] void unreachable(const char* where);

#define AZORA_UNREACHABLE() ::azora::unreachable(__func__)

}  // namespace azora
