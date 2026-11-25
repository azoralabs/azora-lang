// Port of semantic/SemanticPipeline.kt (in part; see semantic_pipeline.h).
#include "semantic/semantic_pipeline.h"

#include "base/text.h"
#include "semantic/ctfe_evaluator.h"
#include "semantic/impl_parameter_aligner.h"

namespace azora {
namespace SemanticPipeline {

namespace {

bool hasHardErrors(const std::vector<std::string>& errors) {
    for (const std::string& e : errors) {
        if (!startsWith(e, "warning:")) return true;
    }
    return false;
}

}  // namespace

SemanticResult analyze(Arena& arena, const Program& program, const OrderedMap<std::string, std::string>& /*defines*/, Stop stopAfter) {
    SemanticResult result;
    result.table = std::make_shared<SymbolTable>();
    SymbolTable& table = *result.table;
    std::vector<std::string>& allErrors = result.errors;

    // Pass 0: an implementation's target declares parameters; a type written there is refused.
    std::vector<std::string> targetErrors = ImplParameterAligner::typesWrittenAsParameters(program);
    if (!targetErrors.empty()) {
        allErrors.insert(allErrors.end(), targetErrors.begin(), targetErrors.end());
        result.program = program;
        return result;
    }
    // An implementation written with its own parameter names is typed by its pack's.
    Program currentProgram = ImplParameterAligner::align(arena, program);
    CtfeEvaluator topLevelCtfe(arena, table);
    CtfeEvaluator::CtfeResult topResult = topLevelCtfe.evaluateTopLevel(currentProgram);
    allErrors.insert(allErrors.end(), topResult.errors.begin(), topResult.errors.end());
    if (hasHardErrors(topResult.errors)) {
        result.program = currentProgram;
        return result;
    }
    currentProgram = topResult.program;
    result.program = currentProgram;
    if (stopAfter == Stop::TopLevel) return result;
    return result;
}

}  // namespace SemanticPipeline
}  // namespace azora
