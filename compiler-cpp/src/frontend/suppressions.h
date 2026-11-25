// Port of Suppressions.kt: what `@Supress(kind: …)` silences.
#pragma once

#include "frontend/ast.h"

#include <string>
#include <vector>

namespace azora {
namespace Suppressions {

/** The decorator's name, as `std/core.az` spells it. */
inline const char* const DECORATOR = "Supress";

/** The `SupressKind` case that silences "is never used". */
inline const char* const UNUSED = "Unused";

/** Whether [annotations] carry `@Supress(kind: .<kind>)`. */
bool suppresses(const std::vector<Annotation>& annotations, const std::string& kind);

/** Whether [annotations] silence "is never used". */
inline bool suppressesUnused(const std::vector<Annotation>& annotations) { return suppresses(annotations, UNUSED); }

}  // namespace Suppressions
}  // namespace azora
