// Port of semantic/PackSpecializer.kt: the concrete member list of a generic
// pack for one set of arguments, cached per specialization. The template is
// never modified; specialization reads it and produces a new list.
#pragma once

#include "base/ordered.h"
#include "frontend/ast.h"
#include "ir/ir_type.h"
#include "semantic/constraints.h"
#include "semantic/symbol_table.h"

#include <optional>
#include <string>
#include <vector>

namespace azora {

struct PackSpecializer {
    /** One member of a generic pack's layout family: the declaration and its rendered arguments. */
    struct Key {
        std::string packName;
        std::vector<std::string> arguments;
        std::string text() const;
    };

    /** The concrete layout of one specialization. */
    struct Specialization {
        Key key;
        std::vector<PackField> fields;
    };

    PackSpecializer(Arena& arena, const SymbolTable& table) : arena(arena), table(table) {}

    /** The key for a resolved application, or none when it is not yet concrete. */
    std::optional<Key> keyFor(const TopLevelPack& declaration, const IrTypeNamed& applied) const;
    /** An argument list with omitted const parameters filled from their defaults. */
    std::vector<TypeRef> withDefaults(const TopLevelPack& declaration, const std::vector<TypeRef>& arguments) const;
    /** The key for an application written as source types, or none when abstract. */
    std::optional<Key> keyForRefs(const TopLevelPack& declaration, const std::vector<TypeRef>& supplied) const;
    /** The concrete layout for an application written as source types. */
    const Specialization& specializeForRefs(const Key& key, const TopLevelPack& declaration, const std::vector<TypeRef>& supplied);
    /** The bindings a resolved application supplies to a condition. */
    ConstraintEvaluator::Bindings bindingsFor(const TopLevelPack& declaration, const IrTypeNamed& applied) const;
    /** The concrete layout for a resolved application, cached per key. */
    const Specialization& specializeFor(const Key& key, const TopLevelPack& declaration, const IrTypeNamed& applied);
    size_t cachedCount() const { return cache.size(); }
    bool isCached(const Key& key) const { return cache.contains(key.text()); }

    Arena& arena;
    const SymbolTable& table;
    OrderedMap<std::string, Specialization> cache;

  private:
    std::vector<PackField> concreteFields(const TopLevelPack& declaration, const ConstraintEvaluator::Bindings& bindings);
    std::vector<PackField> unroll(const PackField& field, const ConstraintEvaluator::Bindings& bindings);
    PackField substitute(const PackField& field, const OrderedMap<std::string, int64_t>& values);
};

}  // namespace azora
