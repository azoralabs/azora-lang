// Port of diagnostics/Diagnostics.kt - in part: the UndefinedSymbol variant
// and DiagnosticRenderer.summary for it, which is what a Failure's error lines
// carry for an unresolved spec, decorator or type. Spans, codes and fixes
// arrive with the AZLS-facing diagnostics (stage 9).
#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace azora {

enum class SymbolNamespace : uint8_t { VALUE, TYPE, FUNCTION, MODULE, MEMBER, SPEC_OR_DECORATOR };

struct SymbolCandidate {
    std::string name;
};

struct UndefinedSymbol {
    std::string symbol;
    SymbolNamespace namespace_ = SymbolNamespace::VALUE;
    std::optional<std::string> providerModule;
    std::vector<SymbolCandidate> candidates;
};

namespace DiagnosticRenderer {
std::string summary(const UndefinedSymbol& diagnostic);
}  // namespace DiagnosticRenderer

}  // namespace azora
