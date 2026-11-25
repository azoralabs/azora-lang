// Port of ScopeAccessRewriter.kt.
#pragma once

#include "frontend/ast.h"

#include <optional>
#include <string>

namespace azora {
namespace ScopeAccessRewriter {

Program rewrite(Arena& arena, const Program& program);

}  // namespace ScopeAccessRewriter

/** `std__math__floor` → `std__math`; a bare name with no `__` → none (Kotlin `String.scopePrefix()`). */
std::optional<std::string> scopePrefix(const std::string& name);

/** How the source names a scope member mangled as [name]: `Const__five` → `Const::five`. */
std::optional<std::string> scopeQualifiedSpelling(const std::string& name);

}  // namespace azora
