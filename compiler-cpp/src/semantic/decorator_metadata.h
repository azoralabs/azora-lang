// Port of semantic/DecoratorMetadata.kt: static decorator metadata used by
// compile-time reflection and the derivers - where a declaration sits (its
// site), which decorator applies there (directly or through another
// decorator), and what an application says for each field.
#pragma once

#include "frontend/ast.h"

#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

namespace azora {
namespace DecoratorMetadata {

struct Site {
    std::string identity;
    DecoTarget target = DecoTarget::Pack;
    std::vector<Annotation> annotations;
};

struct Applied {
    TopLevelDeco* declaration = nullptr;
    /** None when the decorator is inherited through another decorator. */
    std::optional<Annotation> directApplication;
};

std::optional<Site> findSite(const Expr& receiver, const std::unordered_map<std::string, std::string>& typeBindings,
                             const Program& program);
std::optional<Applied> findApplied(const Site& site, const std::string& decoratorName, const Program& program);
/** What [applied] gives [fieldName]: the application's argument, else the (chosen) default; none when absent. */
Expr fieldValue(Arena& arena, const Applied& applied, const std::string& fieldName);
/** Whether [fieldName] is sealed at this application (answered from the default). */
bool isFieldSealed(Arena& arena, const Applied& applied, const std::string& fieldName);

}  // namespace DecoratorMetadata
}  // namespace azora
