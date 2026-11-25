// Port of Compiler.kt - in part: compileLegacy from its start to the program
// the first SemanticPipeline.analyze is handed (the stage-4 compare point).
// Semantic analysis, IR and the backends join it as their stages are ported.
#pragma once

#include "base/ordered.h"
#include "frontend/ast.h"
#include "stdlib/stdlib_injector.h"

#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace azora {

/** The front half's outcome: the program to analyze, or the Failure's errors. */
struct FrontendResult {
    std::optional<Program> program;
    std::vector<std::string> errors;
    /** The injector whose arena the injected declarations live in. */
    std::shared_ptr<StdlibInjector> libraries;
};

struct Compiler {
    explicit Compiler(std::vector<LibrarySource> librarySources = {}, OrderedSet<std::string> exports = {});

    /** Kotlin filters out GPU sources: they go to the GPU compiler, not the library set. */
    std::vector<LibrarySource> librarySources;
    /** Functions a library build exports: reachability roots like `main`. */
    OrderedSet<std::string> exports;

    /** compileLegacy up to the first semantic analysis, for [source] (no package interface). */
    FrontendResult frontend(Arena& arena, const std::string& source, bool debug = false,
                            const OrderedMap<std::string, std::string>& defines = {});
};

}  // namespace azora
