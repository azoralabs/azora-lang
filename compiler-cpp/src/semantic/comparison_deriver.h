// Port of semantic/ComparisonDeriver.kt: field-wise `Equal`, `Order` and
// `Hash` for a pack that asked for them (`derives (Equal, Order)` or
// `derive (…) for T`), as ordinary AST. Nothing is derived silently.
#pragma once

#include "frontend/ast.h"

#include <string>
#include <vector>

namespace azora {
namespace ComparisonDeriver {

struct Result {
    Program program;
    std::vector<std::string> errors;
};

Result derive(Arena& arena, const Program& program);

}  // namespace ComparisonDeriver
}  // namespace azora
