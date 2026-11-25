// Port of semantic/PackSpecializer.kt.
#include "semantic/pack_specializer.h"

#include "base/text.h"
#include "frontend/ast_util.h"

#include <algorithm>

namespace azora {

std::string PackSpecializer::Key::text() const { return packName + "<" + join(arguments, ", ") + ">"; }

std::optional<PackSpecializer::Key> PackSpecializer::keyFor(const TopLevelPack& declaration, const IrTypeNamed& applied) const {
    if (applied.args.size() != declaration.typeParams.size()) return std::nullopt;
    Key key{declaration.name, {}};
    for (size_t index = 0; index < declaration.typeParams.size(); index++) {
        const std::string& parameter = declaration.typeParams[index];
        if (declaration.constParams.contains(parameter)) {
            if (index >= applied.constArgs.size() || !applied.constArgs[index]) return std::nullopt;
            key.arguments.push_back(str(*applied.constArgs[index]));
        } else {
            const IrType& argument = applied.args[index];
            if (irIs<IrTypeAny>(argument)) return std::nullopt;
            key.arguments.push_back(toString(argument));
        }
    }
    return key;
}

std::vector<TypeRef> PackSpecializer::withDefaults(const TopLevelPack& declaration, const std::vector<TypeRef>& arguments) const {
    if (arguments.size() >= declaration.typeParams.size()) return arguments;
    if (declaration.constDefaults.empty()) return arguments;
    std::vector<TypeRef> completed = arguments;
    for (size_t index = arguments.size(); index < declaration.typeParams.size(); index++) {
        const TypeRef* def = declaration.constDefaults.get(declaration.typeParams[index]);
        if (def == nullptr) return arguments;
        completed.push_back(*def);
    }
    return completed;
}

std::optional<PackSpecializer::Key> PackSpecializer::keyForRefs(const TopLevelPack& declaration,
                                                                const std::vector<TypeRef>& supplied) const {
    std::vector<TypeRef> arguments = withDefaults(declaration, supplied);
    if (arguments.size() != declaration.typeParams.size()) return std::nullopt;
    Key key{declaration.name, {}};
    for (size_t index = 0; index < declaration.typeParams.size(); index++) {
        const std::string& parameter = declaration.typeParams[index];
        const TypeRef& argument = arguments[index];
        if (declaration.constParams.contains(parameter)) {
            TypeRefConst* c = as<TypeRefConst>(argument);
            if (c == nullptr) return std::nullopt;
            key.arguments.push_back(str(c->value));
        } else {
            TypeRefNamed* named = as<TypeRefNamed>(argument);
            if (named && std::find(declaration.typeParams.begin(), declaration.typeParams.end(), named->name) !=
                             declaration.typeParams.end()) {
                return std::nullopt;
            }
            key.arguments.push_back(toString(argument));
        }
    }
    return key;
}

const PackSpecializer::Specialization& PackSpecializer::specializeForRefs(const Key& key, const TopLevelPack& declaration,
                                                                          const std::vector<TypeRef>& supplied) {
    std::string text = key.text();
    if (const Specialization* cached = cache.get(text)) return *cached;
    std::vector<TypeRef> arguments = withDefaults(declaration, supplied);
    ConstraintEvaluator::Bindings bindings;
    for (size_t index = 0; index < declaration.typeParams.size(); index++) {
        if (index >= arguments.size()) continue;
        if (std::optional<ConstraintEvaluator::Binding> b = ConstraintEvaluator::bindingOf(arena, arguments[index])) {
            bindings.set(declaration.typeParams[index], *b);
        }
    }
    Specialization made{key, concreteFields(declaration, bindings)};
    cache.set(text, std::move(made));
    return *cache.get(text);
}

ConstraintEvaluator::Bindings PackSpecializer::bindingsFor(const TopLevelPack& declaration, const IrTypeNamed& applied) const {
    ConstraintEvaluator::Bindings out;
    for (size_t index = 0; index < declaration.typeParams.size(); index++) {
        const std::string& parameter = declaration.typeParams[index];
        if (declaration.constParams.contains(parameter)) {
            if (index < applied.constArgs.size() && applied.constArgs[index]) {
                out.set(parameter, ConstraintEvaluator::Binding::constant(*applied.constArgs[index]));
            }
        } else if (index < applied.args.size()) {
            const IrType& argument = applied.args[index];
            if (IrTypeNamed* named = irAs<IrTypeNamed>(argument)) {
                out.set(parameter, ConstraintEvaluator::Binding::type(named->name));
            } else {
                out.set(parameter, ConstraintEvaluator::Binding::type(toString(argument)));
            }
        }
    }
    return out;
}

const PackSpecializer::Specialization& PackSpecializer::specializeFor(const Key& key, const TopLevelPack& declaration,
                                                                      const IrTypeNamed& applied) {
    std::string text = key.text();
    if (const Specialization* cached = cache.get(text)) return *cached;
    ConstraintEvaluator::Bindings bindings = bindingsFor(declaration, applied);
    Specialization made{key, concreteFields(declaration, bindings)};
    cache.set(text, std::move(made));
    return *cache.get(text);
}

/** The layout one set of bindings selects: repetitions unrolled, conditions applied. */
std::vector<PackField> PackSpecializer::concreteFields(const TopLevelPack& declaration,
                                                       const ConstraintEvaluator::Bindings& bindings) {
    std::vector<PackField> out;
    for (const PackField& field : declaration.fields) {
        for (PackField& f : unroll(field, bindings)) {
            ConstraintEvaluator::Outcome outcome = ConstraintEvaluator::evaluate(arena, f.condition, bindings, &table);
            if (outcome.kind != ConstraintEvaluator::Outcome::Kind::Violated) out.push_back(std::move(f));
        }
    }
    return out;
}

/** One field template expanded over its compile-time loops; an undecidable range leaves the template alone. */
std::vector<PackField> PackSpecializer::unroll(const PackField& field, const ConstraintEvaluator::Bindings& bindings) {
    if (field.repeats.empty()) return {field};
    PackField bare = field;
    bare.repeats.clear();
    std::vector<std::pair<PackField, OrderedMap<std::string, int64_t>>> expanded{{bare, {}}};
    for (const FieldRepeat& repeat : field.repeats) {
        std::vector<std::pair<PackField, OrderedMap<std::string, int64_t>>> next;
        for (const auto& [current, values] : expanded) {
            std::optional<std::vector<int64_t>> span = ConstraintEvaluator::rangeOf(repeat.range, bindings, values);
            if (!span) return {field};
            for (int64_t value : *span) {
                OrderedMap<std::string, int64_t> with = values;
                with.set(repeat.variable, value);
                next.push_back({current, std::move(with)});
            }
        }
        expanded = std::move(next);
    }
    std::vector<PackField> out;
    for (const auto& [tpl, values] : expanded) out.push_back(substitute(tpl, values));
    return out;
}

/** Renders a field template for one combination of loop values. */
PackField PackSpecializer::substitute(const PackField& field, const OrderedMap<std::string, int64_t>& values) {
    PackField c = field;
    for (const auto& [variable, value] : values.entries) c.name = replaceAll(c.name, "$" + variable, str(value));
    if (c.condition) c.condition = ConstraintEvaluator::substituteConsts(arena, c.condition, values);
    return c;
}

}  // namespace azora
