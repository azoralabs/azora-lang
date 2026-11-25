// The canonical dump format shared with the Kotlin oracle (CPP_PORT_PLAN.md §8,
// compiler-cpp/oracle/AstOracle.kt `Dump`).
//
//   (Kind                 a data class: one field per line, Kotlin's order
//     name value
//   )
//   [ items ] #{ items }  lists and sets, one item per line; [] when empty
//   { k v }               maps, one entry per line; {} when empty
//   "text"                strings, with \" \\ \n \r \t and \uXXXX escapes
//   1.0#3ff0000000000000  doubles: Kotlin's text, then the raw bits
//   nil                   null
#pragma once

#include "base/ordered.h"

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace azora {

struct Dumper {
    std::string out;

    void indent(int n) { out.append((size_t)n, ' '); }
    void raw(std::string_view text) { out.append(text); }
    void quoted(std::string_view text);
    void nil() { out.append("nil"); }

    /** `(Kind` - then fields, then close(). */
    void open(std::string_view kind) {
        out.push_back('(');
        out.append(kind);
    }
    /** Starts a field line: newline, indentation, name, space. */
    void field(std::string_view name, int ind) {
        out.push_back('\n');
        indent(ind + 2);
        out.append(name);
        out.push_back(' ');
    }
    void close(int ind) {
        out.push_back('\n');
        indent(ind);
        out.push_back(')');
    }
};

// Scalars. Each writes a value at the cursor; [ind] is the value's own
// indentation, used by multi-line values for their children and closer.
void dumpValue(Dumper& d, const std::string& v, int ind);
void dumpValue(Dumper& d, bool v, int ind);
void dumpValue(Dumper& d, int32_t v, int ind);
void dumpValue(Dumper& d, int64_t v, int ind);
void dumpValue(Dumper& d, double v, int ind);

template <class T>
void dumpValue(Dumper& d, const std::optional<T>& v, int ind) {
    if (v) dumpValue(d, *v, ind);
    else d.nil();
}

template <class T>
void dumpItems(Dumper& d, const char* open, const char* close, const std::vector<T>& items, int ind) {
    d.raw(open);
    if (!items.empty()) {
        for (const T& item : items) {
            d.out.push_back('\n');
            d.indent(ind + 2);
            dumpValue(d, item, ind + 2);
        }
        d.out.push_back('\n');
        d.indent(ind);
    }
    d.raw(close);
}

template <class T>
void dumpValue(Dumper& d, const std::vector<T>& v, int ind) { dumpItems(d, "[", "]", v, ind); }

template <class T>
void dumpValue(Dumper& d, const OrderedSet<T>& v, int ind) { dumpItems(d, "#{", "}", v.items, ind); }

template <class K, class V>
void dumpValue(Dumper& d, const OrderedMap<K, V>& v, int ind) {
    d.raw("{");
    if (!v.empty()) {
        for (const auto& [key, value] : v.entries) {
            d.out.push_back('\n');
            d.indent(ind + 2);
            dumpValue(d, key, ind + 2);
            d.out.push_back(' ');
            dumpValue(d, value, ind + 2);
        }
        d.out.push_back('\n');
        d.indent(ind);
    }
    d.raw("}");
}

/** Kotlin `Pair` as the data class it is. */
template <class A, class B>
void dumpValue(Dumper& d, const std::pair<A, B>& v, int ind) {
    d.open("Pair");
    d.field("first", ind);
    dumpValue(d, v.first, ind + 2);
    d.field("second", ind);
    dumpValue(d, v.second, ind + 2);
    d.close(ind);
}

}  // namespace azora
