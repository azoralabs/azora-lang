// Port of LocalNames.kt.
#pragma once

#include "base/ordered.h"
#include "frontend/ast.h"

#include <string>
#include <vector>

namespace azora {

/**
 * Every name a body declares for its own use, in any nested block: bindings,
 * loop variables and a `catch` binding. A name declared here shadows a
 * declaration of the same spelling outside the body.
 */
OrderedSet<std::string> localNamesDeclaredIn(const std::vector<Stmt>& body);

}  // namespace azora
