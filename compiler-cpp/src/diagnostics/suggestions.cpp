// Port of diagnostics/Suggestions.kt. Lengths and characters are UTF-16, as Kotlin's.
#include "diagnostics/suggestions.h"

#include "base/chars.h"
#include "base/text.h"

#include <algorithm>
#include <climits>

namespace azora {

namespace {

/** Kotlin `Char.equals(other, ignoreCase = true)`. */
bool equalsIgnoreCase(char16_t x, char16_t y) {
    if (x == y) return true;
    char16_t xu = uppercaseChar(x);
    char16_t yu = uppercaseChar(y);
    return xu == yu || lowercaseChar(xu) == lowercaseChar(yu);
}

/** Optimal string alignment distance, doubled so that a change of letter case alone costs one half-edit. */
int32_t editCost(const std::u16string& a, const std::u16string& b) {
    auto step = [](char16_t x, char16_t y) -> int32_t {
        if (x == y) return 0;
        if (equalsIgnoreCase(x, y)) return 1;
        return 2;
    };
    size_t n = a.size();
    size_t m = b.size();
    std::vector<std::vector<int32_t>> rows(n + 1, std::vector<int32_t>(m + 1, 0));
    for (size_t i = 0; i <= n; i++) rows[i][0] = (int32_t)i * 2;
    for (size_t j = 0; j <= m; j++) rows[0][j] = (int32_t)j * 2;
    for (size_t i = 1; i <= n; i++) {
        for (size_t j = 1; j <= m; j++) {
            int32_t cost = std::min({rows[i - 1][j] + 2, rows[i][j - 1] + 2, rows[i - 1][j - 1] + step(a[i - 1], b[j - 1])});
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                cost = std::min(cost, rows[i - 2][j - 2] + 2);
            }
            rows[i][j] = cost;
        }
    }
    return rows[n][m];
}

}  // namespace

std::optional<std::string> closestName(const std::string& name, const std::vector<std::string>& candidates) {
    std::u16string name16 = decodeWtf8(name);
    int32_t budget = std::max<int32_t>(1, (int32_t)name16.size() / 3) * 2;
    std::optional<std::string> best;
    int32_t bestCost = INT32_MAX;
    for (const std::string& candidate : candidates) {
        if (candidate == name) continue;
        int32_t cost = editCost(name16, decodeWtf8(candidate));
        if (cost <= budget && cost < bestCost) {
            best = candidate;
            bestCost = cost;
        }
    }
    return best;
}

std::string didYouMean(const std::string& name, const std::vector<std::string>& candidates,
                       const std::function<std::string(const std::string&)>& spell) {
    std::optional<std::string> closest = closestName(name, candidates);
    if (!closest) return "";
    return " - did you mean '" + (spell ? spell(*closest) : *closest) + "'?";
}

}  // namespace azora
