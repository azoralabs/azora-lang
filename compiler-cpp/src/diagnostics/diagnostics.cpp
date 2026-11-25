// Port of diagnostics/Diagnostics.kt - in part (see diagnostics.h).
#include "diagnostics/diagnostics.h"

#include "base/text.h"

namespace azora {
namespace DiagnosticRenderer {

std::string summary(const UndefinedSymbol& diagnostic) {
    const char* kind = "value";
    switch (diagnostic.namespace_) {
        case SymbolNamespace::SPEC_OR_DECORATOR: kind = "spec or decorator"; break;
        case SymbolNamespace::VALUE: kind = "value"; break;
        case SymbolNamespace::TYPE: kind = "type"; break;
        case SymbolNamespace::FUNCTION: kind = "function"; break;
        case SymbolNamespace::MODULE: kind = "module"; break;
        case SymbolNamespace::MEMBER: kind = "member"; break;
    }
    std::string out = std::string("undefined ") + kind + " '" + diagnostic.symbol + "'";
    if (diagnostic.providerModule) {
        const std::string& module = *diagnostic.providerModule;
        out += " - '" + diagnostic.symbol + "' is provided by '" + module + "': add 'import " + module + "::" + diagnostic.symbol + "'";
    }
    // A scope's member is reached through the scope, as a scope type is.
    if (!diagnostic.candidates.empty() && !diagnostic.providerModule) {
        out += "; '" + diagnostic.symbol + "' is part of scope '" + substringBeforeLast(diagnostic.candidates[0].name, "::") + "', use ";
        for (size_t i = 0; i < diagnostic.candidates.size(); i++) {
            if (i > 0) out += " or ";
            out += "'" + diagnostic.candidates[i].name + "'";
        }
        out += " instead";
    }
    return out;
}

}  // namespace DiagnosticRenderer
}  // namespace azora
