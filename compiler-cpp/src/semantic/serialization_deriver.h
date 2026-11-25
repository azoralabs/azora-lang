// Port of semantic/SerializationDeriver.kt: synthesizes the serializer
// methods promised by the standard serialization decorator contracts. The
// methods are written as Azora source and parsed, so every backend receives
// the same checked IR rather than backend-specific behaviour.
#pragma once

#include "frontend/ast.h"

#include <string>
#include <vector>

namespace azora {
namespace SerializationDeriver {

struct Result {
    Program program;
    std::vector<std::string> errors;
};

Result derive(Arena& arena, const Program& program);

}  // namespace SerializationDeriver
}  // namespace azora
