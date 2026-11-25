// Port of DebugInstrumenter.kt.
#pragma once

#include "frontend/ast.h"

namespace azora {
namespace DebugInstrumenter {

/**
 * Debug-build instrumentation: inserts a `__dbg(<line>)` call before every
 * runtime statement in function/test bodies (recursively into nested blocks).
 * Compile-time (`inline`) constructs are left untouched.
 */
Program instrument(Arena& arena, const Program& program);

}  // namespace DebugInstrumenter
}  // namespace azora
