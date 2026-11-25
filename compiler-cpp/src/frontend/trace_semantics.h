// Port of TraceSemantics.kt.
#pragma once

#include "base/failure.h"
#include "frontend/ast.h"

namespace azora {

/** Replaces free uses of a trace body's implicit `it` receiver (Kotlin `Expr.bindTraceReceiver`). */
Expr bindTraceReceiver(Arena& arena, const Expr& expr, const Expr& receiver);

}  // namespace azora
