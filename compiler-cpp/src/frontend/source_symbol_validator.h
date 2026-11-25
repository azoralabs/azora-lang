// Port of SourceSymbolValidator.kt.
#pragma once

#include "frontend/ast.h"
#include "frontend/token.h"

#include <vector>

namespace azora {
namespace SourceSymbolValidator {

void validateTokens(const std::vector<Token>& tokens);
void validateProgram(const Program& program);

}  // namespace SourceSymbolValidator
}  // namespace azora
