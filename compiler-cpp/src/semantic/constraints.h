// Ports of semantic/Bounds.kt, semantic/ConstraintEvaluator.kt and
// MemberTypeInstantiation.kt's substituteMemberType: what a declaration's
// bounds say, and whether a `where` clause holds for one set of arguments.
#pragma once

#include "base/ordered.h"
#include "frontend/ast.h"
#include "semantic/symbol_table.h"

#include <memory>
#include <unordered_set>
#include <optional>
#include <string>
#include <vector>

namespace azora {

/** [ref] with each named type in [bindings] replaced (MemberTypeInstantiation.kt). */
TypeRef substituteMemberType(Arena& arena, const TypeRef& ref, const OrderedMap<std::string, TypeRef>& bindings);

namespace Bounds {

/** One spec a bound requires: `Conv<Int>` in `where T: Conv<Int>`. */
struct Bound {
    std::string spec;
    std::vector<TypeRef> args;
    /** Equality key (Kotlin data-class equality). */
    std::string key() const;
};

/** A set of bounds in first-added order (Kotlin LinkedHashSet<Bound>). */
struct BoundSet {
    std::vector<Bound> items;
    std::unordered_set<std::string> keys;
    bool add(const Bound& bound);
    bool contains(const Bound& bound) const { return keys.count(bound.key()) != 0; }
};

/** The specs [clause] requires of [param] outright. */
BoundSet required(const Expr& clause, const std::string& param);
/** [bound] with the arguments it leaves unwritten filled from the spec's defaults, when all have one. */
Bound withDefaults(Arena& arena, const Bound& bound, const TypeRef& subject, const SymbolTable* table);
Bound withDefaults(Arena& arena, const Bound& bound, const std::optional<std::string>& subject, const SymbolTable* table);
/** [bounds] and everything conforming to them entails (parents, required specs). */
BoundSet implied(Arena& arena, const BoundSet& bounds, const SymbolTable& table, const std::optional<std::string>& subject);
/** Whether [has] includes [wanted]. */
bool includes(Arena& arena, const std::vector<Bound>& has, const Bound& wanted, const SymbolTable* table);
/** Whether two spec names denote the same spec, qualified or not. */
bool sameSpec(const std::string& a, const std::string& b);
/** Whether two argument lists name the same types; none when a side has none to compare. */
std::optional<bool> sameArgs(Arena& arena, const std::vector<TypeRef>& a, const std::vector<TypeRef>& b, const SymbolTable* table);
/** [ref] in the one spelling every equivalent spelling of it shares. */
TypeRef canonical(Arena& arena, const TypeRef& ref, const SymbolTable* table);
/** How [spec] / [bound] is shown in a diagnostic. */
std::string shown(const std::string& spec);
std::string shown(const Bound& bound);

}  // namespace Bounds

namespace ConstraintEvaluator {

/** What a clause evaluated to, and why when it failed. */
struct Outcome {
    enum class Kind : uint8_t { Satisfied, Violated, Unknown };
    Kind kind = Kind::Satisfied;
    /** Violated: the conjunct that failed. Unknown: what could not be evaluated. */
    std::string text;
    static Outcome satisfied() { return {Kind::Satisfied, {}}; }
    static Outcome violated(std::string reason) { return {Kind::Violated, std::move(reason)}; }
    static Outcome unknown(std::string what) { return {Kind::Unknown, std::move(what)}; }
};

/** One type argument, as bound for a monomorphised combination. */
struct Binding {
    enum class Kind : uint8_t { Type, Const, Structural, Pack };
    Kind kind = Kind::Type;
    /** Type: the name and the arguments it was applied to. */
    std::string name;
    std::vector<TypeRef> args;
    /** Const. */
    int64_t value = 0;
    /** Structural. */
    TypeRef ref;
    /** Pack: the cardinality, and the element bindings (null entries unresolved; none: none known). */
    std::optional<int64_t> length;
    std::optional<std::vector<std::shared_ptr<Binding>>> elementTypes;

    static Binding type(std::string name, std::vector<TypeRef> args = {});
    static Binding constant(int64_t value);
    static Binding structural(TypeRef ref);
};

using Bindings = OrderedMap<std::string, Binding>;
/** A type parameter of the asking code and the bounds it is known to have (none: assume nothing). */
using Assumed = OrderedMap<std::string, std::optional<std::vector<Bounds::Bound>>>;

/** Evaluates [clause] under [bindings]; Satisfied when there is no clause. */
Outcome evaluate(Arena& arena, const Expr& clause, const Bindings& bindings, const SymbolTable* table, const Assumed& assumed = {});
/** The values a compile-time range spans, or none when not yet decidable. */
std::optional<std::vector<int64_t>> rangeOf(const Expr& range, const Bindings& bindings, const OrderedMap<std::string, int64_t>& extra);
/** Replaces each named value with the number it is bound to. */
Expr substituteConsts(Arena& arena, const Expr& expr, const OrderedMap<std::string, int64_t>& values);
/** Converts a TypeRef argument into the binding it stands for. */
std::optional<Binding> bindingOf(Arena& arena, const TypeRef& arg);
/** Bind fixed parameters individually and the variadic tail by its own size. */
Bindings bindingsFor(Arena& arena, const std::vector<std::string>& params, const std::optional<std::string>& variadicParam,
                     const std::vector<TypeRef>& args);

}  // namespace ConstraintEvaluator

}  // namespace azora
