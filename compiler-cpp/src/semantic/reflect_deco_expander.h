// Port of semantic/ReflectDecoExpander.kt: expands
// `inline for X in reflect<*>.withAnnot<D> { body }` at compile time, once per
// declaration carrying decorator D, folding the `reflect<X>` queries
// (`declName`, `hasAnnot<D>`, `annotMeta<D>.field`, `params`) against it.
//
// `enumerating()` (which functions a package interface leaves to the program)
// arrives with package builds in stage 9.
#pragma once

#include "frontend/ast.h"

namespace azora {
namespace ReflectDecoExpander {

Program expand(Arena& arena, const Program& program);

}  // namespace ReflectDecoExpander
}  // namespace azora
