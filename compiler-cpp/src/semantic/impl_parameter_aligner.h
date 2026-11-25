// Port of semantic/ImplParameterAligner.kt: an implementation written with its
// own parameter names (`impl Box<U>` for `pack Box<T>`) is renamed onto its
// pack's, and a type written where a parameter belongs is refused.
#pragma once

#include "frontend/ast.h"

#include <string>
#include <vector>

namespace azora {
namespace ImplParameterAligner {

/** A type written as an implementation target's parameter (`impl Show for Box<Int>`): refused. */
std::vector<std::string> typesWrittenAsParameters(const Program& program);

/** Inherent implementations renamed onto their pack's own parameter names. */
Program align(Arena& arena, const Program& program);

}  // namespace ImplParameterAligner
}  // namespace azora
