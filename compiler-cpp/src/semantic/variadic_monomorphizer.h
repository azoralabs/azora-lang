// Port of semantic/VariadicMonomorphizer.kt: every concrete use of a generic
// declaration whose layout depends on its arguments - a variadic pack, a pack
// with conditional fields, a function returning a variadic pack - becomes a
// declaration of its own (`Tuple<Int, String>` -> `pack __Tuple_Int_String`),
// the templates are dropped, and the impls of each are specialized with them.
#pragma once

#include "frontend/ast.h"

#include <string>
#include <vector>

namespace azora {

/** How a specialization is named (Kotlin `object MonoNames`). */
namespace MonoNames {
/** `Tuple` with `[Int, String]` is `__Tuple_Int_String`. */
std::string mangle(const std::string& templateName, const std::vector<TypeRef>& args);
/** [raw] with one leading `__` and no repeated separators anywhere after it. */
std::string canonicalSymbol(const std::string& raw);
std::string mangleType(const TypeRef& type);
}  // namespace MonoNames

namespace VariadicMonomorphizer {
/** The name a tuple of [shape]'s members are registered under. */
std::string tupleMemberOwner(const std::vector<TypeRef>& shape);
/** The program with `impl … for Tuple<...T>` specialized for each of [shapes]. */
Program specializeTuples(Arena& arena, const Program& program, const std::vector<std::vector<TypeRef>>& shapes);
/** The program with every layout-dependent generic replaced by its specializations. */
Program monomorphize(Arena& arena, const Program& program);
}  // namespace VariadicMonomorphizer

}  // namespace azora
