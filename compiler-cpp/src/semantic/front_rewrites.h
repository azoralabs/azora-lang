// Small program rewrites that run between injection and analysis (stage 4):
// ports of ScopeQualifiedImplTargets.kt, SpecDefaults.kt, CastDeriver.kt and
// DisplayDeriver.kt, one namespace each as the Kotlin objects.
#pragma once

#include "frontend/ast.h"

namespace azora {

/** Decides what `A::B` means as an implementation target: a scope-qualified type, or a field. */
namespace ScopeQualifiedImplTargets {
Program resolve(Arena& arena, const Program& program);
}

/** Gives an implementation the spec members it did not write, and the return types it left out. */
namespace SpecDefaults {
Program apply(Arena& arena, const Program& program);
}

/** Gives a cast impl's member the name its target makes unique (`operas@Fahrenheit`). */
namespace CastDeriver {
Program rewrite(Arena& arena, const Program& program);
}

/** The `String` an interpolated value renders to, for a type that implements `Display`. */
namespace DisplayDeriver {
/** The generated member's name; the IR generator calls it. */
inline const char* const MEMBER = "__displayString";
Program derive(Arena& arena, const Program& program);
}

}  // namespace azora
