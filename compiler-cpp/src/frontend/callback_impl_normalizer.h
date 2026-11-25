// Port of CallbackImplNormalizer.kt.
#pragma once

#include "frontend/ast.h"

namespace azora {
namespace CallbackImplNormalizer {

/** Gives a compact-callback spec's single implementation member its real name and signature. */
Program normalize(Arena& arena, const Program& program);

}  // namespace CallbackImplNormalizer
}  // namespace azora
