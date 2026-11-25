// Port of UseAsTemplate.kt: a spec member's call-site alias template.
#pragma once

#include "frontend/ast.h"

#include <string>
#include <vector>

namespace azora {
namespace UseAsTemplate {

std::string expand(const std::string& templateText, const std::vector<std::string>& typeParams,
                   const std::vector<TypeRef>& traitArgs);

std::string typeMemberSuffix(const TypeRef& type);

}  // namespace UseAsTemplate

/** Kotlin `name[0].lowercaseChar() + name.drop(1)`, or "callback" for an empty name. */
std::string callbackMethodBaseName(const std::string& traitName);

}  // namespace azora
