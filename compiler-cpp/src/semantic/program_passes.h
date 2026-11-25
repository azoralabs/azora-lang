// Program passes that run just before analysis (stage 4): ports of
// ReturnedVariants.kt, Witnesses.kt and InferredTypeArgs.kt.
#pragma once

#include "base/ordered.h"
#include "frontend/ast.h"

#include <string>
#include <vector>

namespace azora {

/**
 * `return .Variant` in a failable function: a variant of the declared error
 * set fails the function, a variant of the success type is that value
 * returned, and a name both declare (or neither does) is an error.
 */
namespace ReturnedVariants {
struct Result {
    Program program;
    std::vector<std::string> errors;
};
Result resolve(Arena& arena, const Program& program);
}  // namespace ReturnedVariants

/** What generic code reaches through a witness (descriptor fields on bounded packs). */
namespace Witnesses {
inline const char* const HASH = "Hash";
inline const char* const EQUAL = "Equal";
inline const char* const ORDER = "Order";
inline const char* const CLONE = "Clone";
inline const char* const COPY = "Copy";
inline const char* const PARTIAL_ORDER = "PartialOrder";
inline const char* const DISPLAY = "Display";
inline const char* const STEP = "Step";

/** The one spec a set of needs names in a diagnostic. */
std::string named(const OrderedSet<std::string>& needs);
/** The name of the hidden field or parameter holding [param]'s descriptor. */
std::string slot(const std::string& param);
/** The witness specs [clause] requires of each of [typeParams]. */
OrderedMap<std::string, OrderedSet<std::string>> bounds(const Expr& clause, const std::vector<std::string>& typeParams);
/** The witness bounds a pack declares on its own type parameters. */
OrderedMap<std::string, OrderedSet<std::string>> bounds(const TopLevelPack& pack);
/** Gives every pack with a witness bound one `Int` descriptor field per bounded parameter. */
Program addDescriptorFields(Arena& arena, const Program& program);
}  // namespace Witnesses

/** Fills in the type argument a nested call did not write, from the call around it. */
namespace InferredTypeArgs {
Program apply(Arena& arena, const Program& program);
}

}  // namespace azora
