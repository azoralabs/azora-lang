// Port of MacroExpander.kt.
//
// Expands `meta` macros (TopLevelMeta / ExprMetaInvoke) into ordinary
// expressions, then drops the macro declarations. Arms match
// delimiter-agnostically; patterns support empty arguments, typed single
// captures, sequence captures spliceable via `...$name` and parallel
// `[...${key: value}]` captures. Errors fail with IllegalState, as Kotlin's
// IllegalStateException.
//
// Like the Kotlin object, the expander keeps two pieces of process-wide state:
// the value-infix macros of the last expand() call, and the parallel captures
// of the arm being expanded.
#pragma once

#include "frontend/ast.h"

#include <vector>

namespace azora {
namespace MacroExpander {

/**
 * Resolves declaration-name macros (`func @foreignName("az_poke8")(…)`) across
 * a whole set of modules, leaving every other macro untouched. The macro table
 * is pooled from every module.
 */
std::vector<Program> expandDeclarationNames(Arena& arena, const std::vector<Program>& programs);

/**
 * Rewrites every ExprMetaInvoke in [program] into its matched arm's template
 * and removes all TopLevelMeta declarations. Returns [program] unchanged when
 * it declares and uses no macros.
 */
Program expand(Arena& arena, const Program& program);

}  // namespace MacroExpander
}  // namespace azora
