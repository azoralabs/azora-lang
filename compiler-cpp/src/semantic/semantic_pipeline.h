// Port of semantic/SemanticPipeline.kt - growing pass by pass through stage 5.
// analyze() runs the passes in Kotlin's order and returns where Kotlin does;
// [stopAfter] ends it at one of stage 5's compare points (CPP_PORT_PLAN.md §9).
#pragma once

#include "base/ordered.h"
#include "frontend/ast.h"
#include "semantic/symbol_table.h"

#include <memory>
#include <string>
#include <vector>

namespace azora {

struct SemanticResult {
    Program program;
    std::shared_ptr<SymbolTable> table;
    std::vector<std::string> errors;
};

namespace SemanticPipeline {

/** Stage 5's compare points, in pipeline order. */
enum class Stop : uint8_t {
    /** After ImplParameterAligner and the top-level CtfeEvaluator pass (5.1). */
    TopLevel,
};

SemanticResult analyze(Arena& arena, const Program& program, const OrderedMap<std::string, std::string>& defines, Stop stopAfter);

}  // namespace SemanticPipeline
}  // namespace azora
