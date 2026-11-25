// Port of Suppressions.kt.
#include "frontend/suppressions.h"

#include "base/text.h"

namespace azora {
namespace Suppressions {

namespace {

/** The `SupressKind` case one argument names: `.Unused` or `Unused`. */
std::optional<std::string> caseName(const Expr& expr) {
    if (ExprIdentifier* identifier = as<ExprIdentifier>(expr)) return substringAfterLast(identifier->name, ".");
    if (ExprMember* member = as<ExprMember>(expr)) return member->name;
    return std::nullopt;
}

/** `kind:` is the only field, so a positional argument means the same as the named one. */
bool names(const Annotation& annotation, const std::string& kind) {
    for (const Expr& arg : annotation.args) {
        if (caseName(arg) == kind) return true;
    }
    for (const auto& [name, value] : annotation.namedArgs) {
        if (caseName(value) == kind) return true;
    }
    return false;
}

}  // namespace

bool suppresses(const std::vector<Annotation>& annotations, const std::string& kind) {
    for (const Annotation& annotation : annotations) {
        if (annotation.name == DECORATOR && names(annotation, kind)) return true;
    }
    return false;
}

}  // namespace Suppressions
}  // namespace azora
