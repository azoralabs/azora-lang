// Port of diagnostics/Suggestions.kt: "did you mean" for misspelt names.
#pragma once

#include <functional>
#include <optional>
#include <string>
#include <vector>

namespace azora {

/**
 * The candidate closest to [name] by edit cost, within a budget of a third of
 * [name]'s length (at least one edit); the first one wins a tie. [name] itself
 * is never suggested.
 */
std::optional<std::string> closestName(const std::string& name, const std::vector<std::string>& candidates);

/** `" - did you mean 'X'?"` for the closest of [candidates], or nothing. */
std::string didYouMean(const std::string& name, const std::vector<std::string>& candidates,
                       const std::function<std::string(const std::string&)>& spell = nullptr);

}  // namespace azora
