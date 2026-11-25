// The few Kotlin collection functions whose behaviour (or failure text) a
// port has to keep: first(), last(), single(), distinct(), and try/finally.
#pragma once

#include "base/failure.h"

#include <algorithm>
#include <functional>
#include <string>
#include <unordered_set>
#include <vector>

namespace azora {

/** Kotlin `list.first()`: NoSuchElementException "List is empty." */
template <class T>
decltype(auto) first(const std::vector<T>& items) {
    if (items.empty()) failWith(FailureKind::NoSuchElement, "List is empty.");
    return items.front();
}

/** Kotlin `list.last()`. */
template <class T>
decltype(auto) last(const std::vector<T>& items) {
    if (items.empty()) failWith(FailureKind::NoSuchElement, "List is empty.");
    return items.back();
}

/** Kotlin `list.single()`. */
template <class T>
const T& single(const std::vector<T>& items) {
    if (items.empty()) failWith(FailureKind::NoSuchElement, "List is empty.");
    if (items.size() > 1) failWith(FailureKind::IllegalArgument, "List has more than one element.");
    return items.front();
}

/** Kotlin `list[i]` on an ArrayList: IndexOutOfBoundsException "Index i out of bounds for length n". */
template <class T>
const T& at(const std::vector<T>& items, int64_t index) {
    if (index < 0 || index >= (int64_t)items.size()) {
        failWith(FailureKind::IndexOutOfBounds,
                 "Index " + std::to_string(index) + " out of bounds for length " + std::to_string(items.size()));
    }
    return items[(size_t)index];
}

/** Kotlin `list.distinct()` for strings: first occurrence wins, order kept. */
inline std::vector<std::string> distinct(const std::vector<std::string>& items) {
    std::vector<std::string> out;
    std::unordered_set<std::string> seen;
    for (const std::string& item : items) {
        if (seen.insert(item).second) out.push_back(item);
    }
    return out;
}

/** Kotlin `x in list` for strings. */
inline bool contains(const std::vector<std::string>& items, const std::string& item) {
    return std::find(items.begin(), items.end(), item) != items.end();
}

/** Kotlin `a + b` on lists. */
template <class T>
std::vector<T> concat(std::vector<T> a, const std::vector<T>& b) {
    a.insert(a.end(), b.begin(), b.end());
    return a;
}

/** Kotlin `try { … } finally { action }`: runs [action] however the scope ends. */
struct Finally {
    std::function<void()> action;
    ~Finally() { action(); }
};

}  // namespace azora
