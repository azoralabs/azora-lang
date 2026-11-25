// Insertion-ordered map and set: Kotlin's mutableMapOf/linkedMapOf/mapOf and
// mutableSetOf/setOf, which are LinkedHashMap/LinkedHashSet. Iteration follows
// first insertion; re-putting a key keeps its place; removing closes the gap.
#pragma once

#include <cstddef>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace azora {

template <class K, class V>
struct OrderedMap {
    std::vector<std::pair<K, V>> entries;
    std::unordered_map<K, size_t> index;

    size_t size() const { return entries.size(); }
    bool empty() const { return entries.empty(); }
    bool contains(const K& key) const { return index.count(key) != 0; }

    /** The value for [key], or nullptr: Kotlin `map[key]`. */
    V* get(const K& key) {
        auto it = index.find(key);
        return it == index.end() ? nullptr : &entries[it->second].second;
    }
    const V* get(const K& key) const {
        auto it = index.find(key);
        return it == index.end() ? nullptr : &entries[it->second].second;
    }

    /** Kotlin `map[key] = value`. */
    void set(const K& key, V value) {
        auto it = index.find(key);
        if (it != index.end()) {
            entries[it->second].second = std::move(value);
        } else {
            index.emplace(key, entries.size());
            entries.emplace_back(key, std::move(value));
        }
    }

    /** Kotlin `getOrPut`. */
    V& getOrPut(const K& key, V initial) {
        auto it = index.find(key);
        if (it != index.end()) return entries[it->second].second;
        index.emplace(key, entries.size());
        entries.emplace_back(key, std::move(initial));
        return entries.back().second;
    }

    /** Kotlin `remove(key)`; true when the key was present. */
    bool remove(const K& key) {
        auto it = index.find(key);
        if (it == index.end()) return false;
        size_t at = it->second;
        index.erase(it);
        entries.erase(entries.begin() + (std::ptrdiff_t)at);
        for (size_t i = at; i < entries.size(); i++) index[entries[i].first] = i;
        return true;
    }

    void clear() {
        entries.clear();
        index.clear();
    }

    auto begin() const { return entries.begin(); }
    auto end() const { return entries.end(); }
    auto begin() { return entries.begin(); }
    auto end() { return entries.end(); }

    bool operator==(const OrderedMap& other) const {
        // Kotlin map equality ignores order.
        if (entries.size() != other.entries.size()) return false;
        for (const auto& [key, value] : entries) {
            const V* found = other.get(key);
            if (found == nullptr || !(*found == value)) return false;
        }
        return true;
    }
};

template <class K>
struct OrderedSet {
    std::vector<K> items;
    std::unordered_map<K, size_t> index;

    OrderedSet() = default;
    OrderedSet(std::initializer_list<K> values) {
        for (const K& v : values) add(v);
    }

    size_t size() const { return items.size(); }
    bool empty() const { return items.empty(); }
    bool contains(const K& key) const { return index.count(key) != 0; }

    /** Kotlin `add`; true when [key] was not there yet. */
    bool add(const K& key) {
        if (index.count(key)) return false;
        index.emplace(key, items.size());
        items.push_back(key);
        return true;
    }

    bool remove(const K& key) {
        auto it = index.find(key);
        if (it == index.end()) return false;
        size_t at = it->second;
        index.erase(it);
        items.erase(items.begin() + (std::ptrdiff_t)at);
        for (size_t i = at; i < items.size(); i++) index[items[i]] = i;
        return true;
    }

    void clear() {
        items.clear();
        index.clear();
    }

    auto begin() const { return items.begin(); }
    auto end() const { return items.end(); }

    bool operator==(const OrderedSet& other) const {
        if (items.size() != other.items.size()) return false;
        for (const K& k : items) {
            if (!other.contains(k)) return false;
        }
        return true;
    }
};

}  // namespace azora
