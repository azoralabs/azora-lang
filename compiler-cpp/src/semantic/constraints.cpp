// Ports of semantic/Bounds.kt, semantic/ConstraintEvaluator.kt and
// substituteMemberType from MemberTypeInstantiation.kt.
#include "semantic/constraints.h"

#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"
#include "ir/ir_type.h"

#include <algorithm>
#include <deque>
#include <functional>

namespace azora {

TypeRef substituteMemberType(Arena& arena, const TypeRef& ref, const OrderedMap<std::string, TypeRef>& bindings) {
    auto each = [&](const std::vector<TypeRef>& refs) {
        std::vector<TypeRef> out;
        for (const TypeRef& r : refs) out.push_back(substituteMemberType(arena, r, bindings));
        return out;
    };
    switch (ref.kind()) {
        case TypeRefKind::Named: {
            const TypeRefNamed& named = cast<TypeRefNamed>(ref);
            if (named.args.empty()) {
                if (const TypeRef* bound = bindings.get(named.name)) return *bound;
            }
            TypeRefNamed c = named;
            c.args = each(named.args);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Array: {
            TypeRefArray c = cast<TypeRefArray>(ref);
            c.element = substituteMemberType(arena, c.element, bindings);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Map: {
            TypeRefMap c = cast<TypeRefMap>(ref);
            c.key = substituteMemberType(arena, c.key, bindings);
            c.value = substituteMemberType(arena, c.value, bindings);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Set: {
            TypeRefSet c = cast<TypeRefSet>(ref);
            c.element = substituteMemberType(arena, c.element, bindings);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Tuple: {
            TypeRefTuple c = cast<TypeRefTuple>(ref);
            c.elements = each(c.elements);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Nullable: {
            TypeRefNullable c = cast<TypeRefNullable>(ref);
            c.inner = substituteMemberType(arena, c.inner, bindings);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Pointer: {
            TypeRefPointer c = cast<TypeRefPointer>(ref);
            c.inner = substituteMemberType(arena, c.inner, bindings);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Reference: {
            TypeRefReference c = cast<TypeRefReference>(ref);
            c.inner = substituteMemberType(arena, c.inner, bindings);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Failable: {
            TypeRefFailable c = cast<TypeRefFailable>(ref);
            c.ok = substituteMemberType(arena, c.ok, bindings);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Function: {
            TypeRefFunction c = cast<TypeRefFunction>(ref);
            c.params = each(c.params);
            c.ret = substituteMemberType(arena, c.ret, bindings);
            c.receivers = each(c.receivers);
            return make(arena, std::move(c));
        }
        default:
            return ref;
    }
}

// ---------------------------------------------------------------------------
// Bounds
// ---------------------------------------------------------------------------

namespace Bounds {

namespace {

std::optional<std::string> nameOf(const Expr& expr) {
    if (ExprIdentifier* id = as<ExprIdentifier>(expr)) return id->name;
    if (ExprGrouping* g = as<ExprGrouping>(expr)) return nameOf(g->expr);
    return std::nullopt;
}

}  // namespace

std::string Bound::key() const {
    std::string out = spec + '\x1f';
    for (const TypeRef& a : args) out += structuralKey(a) + '\x1e';
    return out;
}

bool BoundSet::add(const Bound& bound) {
    if (!keys.insert(bound.key()).second) return false;
    items.push_back(bound);
    return true;
}

BoundSet required(const Expr& clause, const std::string& param) {
    BoundSet found;
    if (!clause) return found;
    std::function<void(const Expr&)> visit = [&](const Expr& expr) {
        if (ExprGrouping* g = as<ExprGrouping>(expr)) {
            visit(g->expr);
        } else if (ExprBinary* b = as<ExprBinary>(expr)) {
            if (b->op == TokenType::AND_AND) {
                visit(b->left);
                visit(b->right);
            }
        } else if (ExprIsCheck* check = as<ExprIsCheck>(expr)) {
            if (nameOf(check->expr) == param) found.add(Bound{check->typeName, check->typeArgs});
        }
    };
    visit(clause);
    return found;
}

Bound withDefaults(Arena& arena, const Bound& bound, const std::optional<std::string>& subject, const SymbolTable* table) {
    if (!subject) return bound;
    return withDefaults(arena, bound, TypeRef(make(arena, TypeRefNamed{*subject})), table);
}

Bound withDefaults(Arena& arena, const Bound& bound, const TypeRef& subject, const SymbolTable* table) {
    if (!subject || table == nullptr) return bound;
    const SpecSymbol* symbol = table->lookupSpec(bound.spec);
    if (symbol == nullptr) return bound;
    if (bound.args.size() >= symbol->typeParams.size()) return bound;
    std::vector<std::string> missing(symbol->typeParams.begin() + (std::ptrdiff_t)bound.args.size(), symbol->typeParams.end());
    for (const std::string& m : missing) {
        if (!symbol->typeDefaults.contains(m)) return bound;
    }
    OrderedMap<std::string, TypeRef> bindings;
    bindings.set("Self", subject);
    for (size_t i = 0; i < symbol->typeParams.size() && i < bound.args.size(); i++) bindings.set(symbol->typeParams[i], bound.args[i]);
    Bound out = bound;
    for (const std::string& param : missing) {
        TypeRef filled = substituteMemberType(arena, *symbol->typeDefaults.get(param), bindings);
        bindings.set(param, filled);
        out.args.push_back(filled);
    }
    return out;
}

BoundSet implied(Arena& arena, const BoundSet& bounds, const SymbolTable& table, const std::optional<std::string>& subject) {
    BoundSet all;
    std::deque<Bound> pending;
    for (const Bound& b : bounds.items) pending.push_back(withDefaults(arena, b, subject, &table));
    while (!pending.empty()) {
        Bound bound = pending.front();
        pending.pop_front();
        if (!all.add(bound)) continue;
        const SpecSymbol* symbol = table.lookupSpec(bound.spec);
        if (symbol == nullptr) continue;
        std::optional<OrderedMap<std::string, TypeRef>> passed;
        if (bound.args.size() == symbol->typeParams.size()) {
            OrderedMap<std::string, TypeRef> map;
            for (size_t i = 0; i < bound.args.size(); i++) map.set(symbol->typeParams[i], bound.args[i]);
            passed = std::move(map);
        }
        for (size_t index = 0; index < symbol->parentNames.size(); index++) {
            TypeRefNamed* written = index < symbol->parents.size() ? as<TypeRefNamed>(symbol->parents[index]) : nullptr;
            std::vector<TypeRef> args;
            if (written && !written->args.empty() && passed) {
                for (const TypeRef& a : written->args) args.push_back(substituteMemberType(arena, a, *passed));
            }
            pending.push_back(withDefaults(arena, Bound{symbol->parentNames[index], args}, subject, &table));
        }
        for (const std::string& spec : symbol->requiredSpecs) pending.push_back(withDefaults(arena, Bound{spec, {}}, subject, &table));
    }
    return all;
}

bool includes(Arena& arena, const std::vector<Bound>& has, const Bound& wanted, const SymbolTable* table) {
    for (const Bound& b : has) {
        if (!sameSpec(b.spec, wanted.spec)) continue;
        if (wanted.args.empty() || sameArgs(arena, b.args, wanted.args, table) == std::optional<bool>(true)) return true;
    }
    return false;
}

bool sameSpec(const std::string& a, const std::string& b) {
    return a == b || substringAfterLast(a, "__") == substringAfterLast(b, "__");
}

std::optional<bool> sameArgs(Arena& arena, const std::vector<TypeRef>& a, const std::vector<TypeRef>& b, const SymbolTable* table) {
    if (a.empty() || b.empty() || a.size() != b.size()) return std::nullopt;
    for (size_t i = 0; i < a.size(); i++) {
        if (!kotlinEquals(canonical(arena, a[i], table), canonical(arena, b[i], table))) return false;
    }
    return true;
}

TypeRef canonical(Arena& arena, const TypeRef& ref, const SymbolTable* table) {
    auto each = [&](const std::vector<TypeRef>& refs) {
        std::vector<TypeRef> out;
        for (const TypeRef& r : refs) out.push_back(canonical(arena, r, table));
        return out;
    };
    switch (ref.kind()) {
        case TypeRefKind::Named: {
            const TypeRefNamed& named = cast<TypeRefNamed>(ref);
            const TypeRef* alias = (named.args.empty() && table) ? table->lookupAlias(named.name) : nullptr;
            if (alias && !kotlinEquals(*alias, ref)) return canonical(arena, *alias, table);
            if (named.args.empty() && IrTypes::isPrimitiveName(named.name)) {
                return make(arena, TypeRefNamed{toString(IrTypes::fromName(named.name))});
            }
            // `Array<T>` and `[T]` are one type.
            if (named.name == "Array" && named.args.size() == 1) return make(arena, TypeRefArray{canonical(arena, named.args[0], table)});
            TypeRefNamed out{substringAfterLast(named.name, "__")};
            out.args = each(named.args);
            out.variadic = named.variadic;
            return make(arena, std::move(out));
        }
        case TypeRefKind::Reference: return canonical(arena, cast<TypeRefReference>(ref).inner, table);
        case TypeRefKind::Nullable: return make(arena, TypeRefNullable{canonical(arena, cast<TypeRefNullable>(ref).inner, table)});
        case TypeRefKind::Pointer: {
            TypeRefPointer c = cast<TypeRefPointer>(ref);
            c.inner = canonical(arena, c.inner, table);
            return make(arena, std::move(c));
        }
        case TypeRefKind::Array: return make(arena, TypeRefArray{canonical(arena, cast<TypeRefArray>(ref).element, table)});
        case TypeRefKind::Tuple: return make(arena, TypeRefTuple{each(cast<TypeRefTuple>(ref).elements)});
        case TypeRefKind::Function: {
            TypeRefFunction c = cast<TypeRefFunction>(ref);
            c.params = each(c.params);
            c.ret = canonical(arena, c.ret, table);
            c.receivers = each(c.receivers);
            return make(arena, std::move(c));
        }
        default:
            return ref;
    }
}

std::string shown(const std::string& spec) { return substringAfterLast(spec, "__"); }

std::string shown(const Bound& bound) {
    if (bound.args.empty()) return shown(bound.spec);
    std::vector<std::string> args;
    for (const TypeRef& a : bound.args) args.push_back(toString(a));
    return shown(bound.spec) + "<" + join(args, ", ") + ">";
}

}  // namespace Bounds

// ---------------------------------------------------------------------------
// ConstraintEvaluator
// ---------------------------------------------------------------------------

namespace ConstraintEvaluator {

namespace {

const char* const ARRAY = "Array";
const char* const TUPLE = "Tuple";

bool isBuiltinType(const std::string& name) {
    static const OrderedSet<std::string> names{"Any", "Self", "Array", "Tuple", "Task", "Map", "Set", "Nothing", "Unit"};
    return names.contains(name);
}

bool isPartWise(const std::string& spec) { return spec == "Equal" || spec == "PartialEqual" || spec == "Hash"; }

/** `Int` or `UInt` for a width of either, which states its conformances once. */
std::optional<std::string> integerFamily(const std::string& name) {
    if (!IrTypes::isPrimitiveName(name)) return std::nullopt;
    IrTypeInteger* type = irAs<IrTypeInteger>(IrTypes::fromName(name));
    if (type == nullptr) return std::nullopt;
    return std::string(type->isSigned ? "Int" : "UInt");
}

std::optional<std::string> nameOf(const Expr& expr) {
    if (ExprIdentifier* id = as<ExprIdentifier>(expr)) return id->name;
    if (ExprGrouping* g = as<ExprGrouping>(expr)) return nameOf(g->expr);
    return std::nullopt;
}

std::string render(const Expr& expr) {
    if (ExprIdentifier* id = as<ExprIdentifier>(expr)) return id->name;
    if (ExprIntLiteral* i = as<ExprIntLiteral>(expr)) return str(i->value);
    if (ExprGrouping* g = as<ExprGrouping>(expr)) return render(g->expr);
    if (ExprMember* m = as<ExprMember>(expr)) return render(m->target) + "." + m->name;
    return "constraint";
}

std::string spell(TokenType op) {
    switch (op) {
        case TokenType::EQUAL_EQUAL: return "==";
        case TokenType::BANG_EQUAL: return "!=";
        case TokenType::LESS: return "<";
        case TokenType::LESS_EQUAL: return "<=";
        case TokenType::GREATER: return ">";
        case TokenType::GREATER_EQUAL: return ">=";
        default: return tokenTypeName(op);
    }
}

/** The integer an expression denotes, or none when it is not a known constant. */
std::optional<int64_t> constOf(const Expr& expr, const Bindings& env) {
    if (ExprIntLiteral* i = as<ExprIntLiteral>(expr)) return i->value;
    if (ExprGrouping* g = as<ExprGrouping>(expr)) return constOf(g->expr, env);
    if (ExprIdentifier* id = as<ExprIdentifier>(expr)) {
        const Binding* b = env.get(id->name);
        if (b && b->kind == Binding::Kind::Const) return b->value;
        return std::nullopt;
    }
    // `(...T).size` - the pack's argument count (`.length` is the older spelling).
    if (ExprMember* m = as<ExprMember>(expr)) {
        if (m->name != "size" && m->name != "length") return std::nullopt;
        std::optional<std::string> name = nameOf(m->target);
        const Binding* b = name ? env.get(*name) : nullptr;
        if (b && b->kind == Binding::Kind::Pack) return b->length;
        return std::nullopt;
    }
    return std::nullopt;
}

std::optional<LongProgression> progression(const Expr& expr, const Bindings& env) {
    ExprRange* range = as<ExprRange>(expr);
    if (range == nullptr) return std::nullopt;
    std::optional<int64_t> from = constOf(range->from, env);
    if (!from) return std::nullopt;
    std::optional<int64_t> to = constOf(range->to, env);
    if (!to) return std::nullopt;
    return progressionOf(*range, *from, *to);
}

/** [ref] as source writes it: `Array<P>` rather than the internal `[P]`. */
std::string shownRef(const TypeRef& ref) {
    switch (ref.kind()) {
        case TypeRefKind::Array: return "Array<" + shownRef(cast<TypeRefArray>(ref).element) + ">";
        case TypeRefKind::Tuple: {
            std::vector<std::string> parts;
            for (const TypeRef& e : cast<TypeRefTuple>(ref).elements) parts.push_back(shownRef(e));
            return "(" + join(parts, ", ") + ")";
        }
        case TypeRefKind::Nullable: return shownRef(cast<TypeRefNullable>(ref).inner) + "?";
        case TypeRefKind::Reference: return shownRef(cast<TypeRefReference>(ref).inner);
        default: return toString(ref);
    }
}

Outcome firstOf(const std::vector<Outcome>& outcomes) {
    for (const Outcome& o : outcomes) {
        if (o.kind == Outcome::Kind::Violated) return o;
    }
    for (const Outcome& o : outcomes) {
        if (o.kind == Outcome::Kind::Unknown) return o;
    }
    return Outcome::satisfied();
}

/** One evaluation: the symbol table and what the asking code's type parameters are known to be. */
struct Evaluation {
    Arena& arena;
    const SymbolTable* table;
    const Assumed& assumed;

    Outcome eval(const Expr& expr, const Bindings& env) {
        if (ExprGrouping* g = as<ExprGrouping>(expr)) return eval(g->expr, env);
        // `T is Number` - nominal conformance.
        if (ExprIsCheck* check = as<ExprIsCheck>(expr)) {
            std::optional<std::string> subject = nameOf(check->expr);
            const Binding* bound = subject ? env.get(*subject) : nullptr;
            Bounds::Bound wanted{check->typeName, {}};
            for (const TypeRef& t : check->typeArgs) wanted.args.push_back(substitute(t, env));
            if (bound == nullptr) return Outcome::unknown("'" + (subject ? *subject : std::string("expression")) + " is " + check->typeName + "'");
            if (bound->kind == Binding::Kind::Pack) {
                if (!bound->elementTypes) return Outcome::unknown("'" + *subject + " is " + check->typeName + "' for an unresolved pack");
                std::vector<Outcome> outcomes;
                for (const std::shared_ptr<Binding>& element : *bound->elementTypes) outcomes.push_back(conformance(subject, element.get(), wanted));
                // The empty pack satisfies every element-wise bound.
                return firstOf(outcomes);
            }
            return conformance(subject, bound, wanted);
        }
        // `N in 2..4` - membership over a literal range.
        if (ExprInCheck* in = as<ExprInCheck>(expr)) {
            std::optional<int64_t> subject = constOf(in->value, env);
            std::optional<LongProgression> range = progression(in->collection, env);
            if (!subject || !range) return Outcome::unknown("membership constraint");
            bool inside = !range->isEmpty() && *subject >= std::min(range->first, range->last) &&
                          *subject <= std::max(range->first, range->last);
            if (inside != in->negated) return Outcome::satisfied();
            return Outcome::violated("'" + render(in->value) + " in " + str(range->first) + ".." + str(range->last) + "': " +
                                     render(in->value) + " is " + str(*subject));
        }
        if (ExprUnary* unary = as<ExprUnary>(expr)) {
            if (unary->op == TokenType::BANG) {
                Outcome inner = eval(unary->operand, env);
                if (inner.kind == Outcome::Kind::Satisfied) return Outcome::violated("negated constraint held");
                if (inner.kind == Outcome::Kind::Violated) return Outcome::satisfied();
                return inner;
            }
            return Outcome::unknown(std::string("unary ") + tokenTypeName(unary->op));
        }
        if (ExprBinary* binary = as<ExprBinary>(expr)) {
            if (binary->op == TokenType::AND_AND) return both(*binary, env, true);
            if (binary->op == TokenType::OR_OR) return both(*binary, env, false);
            return compare(*binary, env);
        }
        return Outcome::unknown(render(expr));
    }

    Outcome conformance(const std::optional<std::string>& subject, const Binding* bound, const Bounds::Bound& wanted) {
        std::string who = subject ? *subject : "null";
        if (bound && bound->kind == Binding::Kind::Structural) {
            Outcome outcome = structuralWith(bound->ref, wanted);
            if (outcome.kind == Outcome::Kind::Violated) return Outcome::violated("'" + who + " is " + Bounds::shown(wanted) + "': " + outcome.text);
            return outcome;
        }
        if (bound && bound->kind == Binding::Kind::Type) {
            Outcome outcome = named(bound->name, wanted, bound->args);
            if (outcome.kind == Outcome::Kind::Violated) return Outcome::violated("'" + who + " is " + Bounds::shown(wanted) + "': " + outcome.text);
            return outcome;
        }
        return Outcome::unknown("'" + who + " is " + wanted.spec + "' for an unresolved type binding");
    }

    /** [ref] with each parameter [env] binds to a type replaced by that type. */
    TypeRef substitute(const TypeRef& ref, const Bindings& env) {
        if (env.empty()) return ref;
        OrderedMap<std::string, TypeRef> types;
        for (const auto& [param, binding] : env.entries) {
            switch (binding.kind) {
                case Binding::Kind::Type: {
                    TypeRefNamed named{binding.name};
                    named.args = binding.args;
                    types.set(param, make(arena, std::move(named)));
                    break;
                }
                case Binding::Kind::Structural: types.set(param, binding.ref); break;
                case Binding::Kind::Const: types.set(param, make(arena, TypeRefConst{binding.value})); break;
                case Binding::Kind::Pack: break;
            }
        }
        return substituteMemberType(arena, ref, types);
    }

    Outcome named(const std::string& name, const std::string& spec) { return named(name, Bounds::Bound{spec, {}}, {}); }

    /** Whether the type called [name], applied to [typeArgs], is [written]. */
    Outcome named(const std::string& name, const Bounds::Bound& written, const std::vector<TypeRef>& typeArgs) {
        const std::string& spec = written.spec;
        // An erased slot: which type it holds is decided where it was filled.
        if (name == "Any") return Outcome::unknown("'" + name + " is " + spec + "' for an erased value");
        TypeRefNamed selfNamed{name};
        selfNamed.args = typeArgs;
        TypeRef self = make(arena, std::move(selfNamed));
        Bounds::Bound wanted = Bounds::withDefaults(arena, written, self, table);
        if (assumed.contains(name)) {
            const std::optional<std::vector<Bounds::Bound>>& has = *assumed.get(name);
            if (!has) return Outcome::unknown("'" + name + " is " + spec + "' for a type parameter");
            bool open = false;
            for (const TypeRef& a : wanted.args) open = open || mentionsOpen(a);
            if (!wanted.args.empty() && open) {
                bool same = false;
                for (const Bounds::Bound& h : *has) same = same || Bounds::sameSpec(h.spec, spec);
                if (same) return Outcome::unknown("'" + name + " is " + Bounds::shown(wanted) + "'");
                return Outcome::violated("'" + name + "' is not declared to be " + Bounds::shown(spec) + "; add 'where " + name + ": " +
                                         Bounds::shown(wanted) + "'");
            }
            if (Bounds::includes(arena, *has, wanted, table)) return Outcome::satisfied();
            return Outcome::violated("'" + name + "' is not declared to be " + Bounds::shown(wanted) + "; add 'where " + name + ": " +
                                     Bounds::shown(wanted) + "'");
        }
        if (table == nullptr) return Outcome::unknown("'" + name + " is " + spec + "' without a symbol table");
        // A width answers with its family's conformances, unless the spec is implemented width by width.
        std::optional<std::string> family = integerFamily(name);
        std::string owner;
        if (table->conformsTo(name, spec)) {
            owner = name;
        } else if (family && table->conformsTo(*family, spec) && !perWidth(*family, spec)) {
            owner = *family;
        } else {
            return Outcome::violated(name + " does not implement " + Bounds::shown(spec));
        }
        if (wanted.args.empty()) return Outcome::satisfied();
        for (const TypeRef& a : wanted.args) {
            if (mentionsOpen(a)) return Outcome::unknown("'" + name + " is " + Bounds::shown(wanted) + "'");
        }
        std::vector<const TraitConformance*> implemented;
        for (const TraitConformance& c : table->allConformances()) {
            if (c.typeName == owner && Bounds::sameSpec(c.contractName, spec)) implemented.push_back(&c);
        }
        bool open = false;
        for (const TraitConformance* c : implemented) {
            std::optional<std::vector<TypeRef>> args = implementedArgs(*c, self, typeArgs);
            std::optional<bool> same = Bounds::sameArgs(arena, args ? *args : std::vector<TypeRef>{}, wanted.args, table);
            if (same == std::optional<bool>(true)) return Outcome::satisfied();
            if (!same) {
                open = true;
            } else {
                for (const TypeRef& a : *args) open = open || mentionsOpen(a);
            }
        }
        if (open) return Outcome::unknown("'" + name + " is " + Bounds::shown(wanted) + "'");
        std::vector<std::string> has;
        for (const TraitConformance* c : implemented) {
            std::optional<std::vector<TypeRef>> args = implementedArgs(*c, self, typeArgs);
            if (!args) continue;
            std::vector<std::string> parts;
            for (const TypeRef& a : *args) parts.push_back(toString(a));
            has.push_back(Bounds::shown(spec) + "<" + join(parts, ", ") + ">");
        }
        return Outcome::violated(name + " implements " + join(has, " and ") + ", not " + Bounds::shown(wanted));
    }

    /** Whether [spec] is implemented for some width of [family] under the width's own name. */
    bool perWidth(const std::string& family, const std::string& spec) {
        for (const TraitConformance& c : table->allConformances()) {
            if (c.typeName != family && Bounds::sameSpec(c.contractName, spec) && integerFamily(c.typeName) == family) return true;
        }
        return false;
    }

    /** The arguments [conformance] implements its spec with, for [self]; none when not readable. */
    std::optional<std::vector<TypeRef>> implementedArgs(const TraitConformance& conformance, const TypeRef& self,
                                                        const std::vector<TypeRef>& typeArgs) {
        const std::vector<std::string>& params = conformance.implTypeParams;
        OrderedMap<std::string, TypeRef> bindings;
        bindings.set("Self", self);
        if (!params.empty() && !conformance.typeArgs.empty()) {
            if (params.size() != typeArgs.size()) return std::nullopt;
            for (size_t i = 0; i < params.size(); i++) bindings.set(params[i], typeArgs[i]);
        }
        Bounds::Bound written{conformance.contractName, {}};
        for (const TypeRef& t : conformance.typeArgs) written.args.push_back(substituteMemberType(arena, t, bindings));
        std::vector<TypeRef> args = Bounds::withDefaults(arena, written, self, table).args;
        if (args.empty()) return std::nullopt;
        return args;
    }

    /** Whether [ref] names something this evaluation cannot identify. */
    bool mentionsOpen(const TypeRef& ref) {
        switch (ref.kind()) {
            case TypeRefKind::Named: {
                const TypeRefNamed& n = cast<TypeRefNamed>(ref);
                if (n.args.empty() && !isKnownTypeName(n.name)) return true;
                for (const TypeRef& a : n.args) {
                    if (mentionsOpen(a)) return true;
                }
                return false;
            }
            case TypeRefKind::Reference: return mentionsOpen(cast<TypeRefReference>(ref).inner);
            case TypeRefKind::Nullable: return mentionsOpen(cast<TypeRefNullable>(ref).inner);
            case TypeRefKind::Pointer: return mentionsOpen(cast<TypeRefPointer>(ref).inner);
            case TypeRefKind::Array: return mentionsOpen(cast<TypeRefArray>(ref).element);
            case TypeRefKind::Tuple:
                for (const TypeRef& e : cast<TypeRefTuple>(ref).elements) {
                    if (mentionsOpen(e)) return true;
                }
                return false;
            case TypeRefKind::Function: {
                const TypeRefFunction& f = cast<TypeRefFunction>(ref);
                for (const TypeRef& p : f.params) {
                    if (mentionsOpen(p)) return true;
                }
                return mentionsOpen(f.ret);
            }
            default: return false;
        }
    }

    bool isKnownTypeName(const std::string& name) {
        if (assumed.contains(name) || IrTypes::isPrimitiveName(name) || isBuiltinType(name)) return true;
        if (table == nullptr) return false;
        return table->lookupStruct(name) || table->lookupEnum(name) || table->lookupSlot(name) || table->lookupSpec(name) ||
               table->lookupFail(name) || table->lookupAlias(name);
    }

    /** A structured type against a spec wanted with arguments: it has it with itself only. */
    Outcome structuralWith(const TypeRef& ref, const Bounds::Bound& wanted) {
        if (!wanted.args.empty()) {
            for (const TypeRef& a : wanted.args) {
                if (mentionsOpen(a)) return Outcome::unknown("'" + toString(ref) + " is " + Bounds::shown(wanted) + "'");
            }
            TypeRef itself = Bounds::canonical(arena, ref, table);
            for (const TypeRef& a : wanted.args) {
                if (!kotlinEquals(Bounds::canonical(arena, a, table), itself)) {
                    return Outcome::violated(shownRef(ref) + " has " + Bounds::shown(wanted.spec) + " only with itself");
                }
            }
        }
        return structural(ref, wanted.spec);
    }

    /** Whether a type built with structure is [spec]: the capabilities the language gives it. */
    Outcome structural(const TypeRef& ref, const std::string& spec) {
        std::string bare = Bounds::shown(spec);
        auto no = [&]() { return Outcome::violated(shownRef(ref) + " does not implement " + bare); };
        auto parts = [&](const std::vector<TypeRef>& types) {
            std::vector<Outcome> outcomes;
            for (const TypeRef& part : types) {
                std::optional<Binding> binding = bindingOf(arena, part);
                if (binding && binding->kind == Binding::Kind::Structural) outcomes.push_back(structural(binding->ref, spec));
                else if (binding && binding->kind == Binding::Kind::Type) outcomes.push_back(named(binding->name, spec));
                else outcomes.push_back(Outcome::unknown("'" + toString(part) + " is " + bare + "'"));
            }
            for (const Outcome& o : outcomes) {
                if (o.kind == Outcome::Kind::Violated) return Outcome::violated(shownRef(ref) + " does not implement " + bare + ": " + o.text);
            }
            for (const Outcome& o : outcomes) {
                if (o.kind == Outcome::Kind::Unknown) return o;
            }
            return Outcome::satisfied();
        };
        if (bare == "Clone") {
            if (table == nullptr) return Outcome::unknown("'" + toString(ref) + " is Clone' without a symbol table");
            // A part naming a type parameter of the asking code answers for itself.
            if (mentionsAssumed(ref)) return clonableParts(ref);
            IrType type;
            try {
                type = IrTypes::resolve(arena, ref);
            } catch (const CompileFailure&) {
                return Outcome::unknown("'" + toString(ref) + " is Clone'");
            }
            return table->isClonable(type) ? Outcome::satisfied() : no();
        }
        switch (ref.kind()) {
            case TypeRefKind::Reference: return conformanceOf(cast<TypeRefReference>(ref).inner, spec);
            case TypeRefKind::Named: return conformanceOf(ref, spec);
            case TypeRefKind::Array:
                if (isPartWise(bare)) return parts({cast<TypeRefArray>(ref).element});
                if (bare == "Copy" || bare == "Order" || bare == "PartialOrder") return no();
                return named(ARRAY, spec).kind == Outcome::Kind::Satisfied ? Outcome::satisfied() : no();
            case TypeRefKind::Nullable:
                if (isPartWise(bare) || bare == "Copy") return parts({cast<TypeRefNullable>(ref).inner});
                return no();
            case TypeRefKind::Tuple:
                if (isPartWise(bare)) return parts(cast<TypeRefTuple>(ref).elements);
                if (bare == "Copy" || bare == "Order" || bare == "PartialOrder") return no();
                return named(TUPLE, spec).kind == Outcome::Kind::Satisfied ? Outcome::satisfied() : no();
            case TypeRefKind::Pointer: return isPartWise(bare) ? Outcome::satisfied() : no();
            case TypeRefKind::Const: return Outcome::unknown("'" + toString(ref) + " is " + bare + "'");
            default: return no();
        }
    }

    Outcome conformanceOf(const TypeRef& ref, const std::string& spec) {
        std::optional<Binding> binding = bindingOf(arena, ref);
        if (binding && binding->kind == Binding::Kind::Structural) return structural(binding->ref, spec);
        if (binding && binding->kind == Binding::Kind::Type) return named(binding->name, spec);
        return Outcome::unknown("'" + toString(ref) + " is " + spec + "'");
    }

    /** `Clone` part by part, for a compound type that names a type parameter of the asking code. */
    Outcome clonableParts(const TypeRef& ref) {
        switch (ref.kind()) {
            case TypeRefKind::Named: {
                const TypeRefNamed& n = cast<TypeRefNamed>(ref);
                if (n.args.empty() && assumed.contains(n.name)) return named(n.name, "Clone");
                return conformanceOf(ref, "Clone");
            }
            case TypeRefKind::Reference: return clonableParts(cast<TypeRefReference>(ref).inner);
            case TypeRefKind::Nullable: return clonableParts(cast<TypeRefNullable>(ref).inner);
            case TypeRefKind::Array: return clonableParts(cast<TypeRefArray>(ref).element);
            case TypeRefKind::Tuple: {
                std::vector<Outcome> outcomes;
                for (const TypeRef& e : cast<TypeRefTuple>(ref).elements) outcomes.push_back(clonableParts(e));
                return firstOf(outcomes);
            }
            default: return Outcome::satisfied();
        }
    }

    bool mentionsAssumed(const TypeRef& ref) {
        switch (ref.kind()) {
            case TypeRefKind::Named: {
                const TypeRefNamed& n = cast<TypeRefNamed>(ref);
                if (n.args.empty() && assumed.contains(n.name)) return true;
                for (const TypeRef& a : n.args) {
                    if (mentionsAssumed(a)) return true;
                }
                return false;
            }
            case TypeRefKind::Reference: return mentionsAssumed(cast<TypeRefReference>(ref).inner);
            case TypeRefKind::Nullable: return mentionsAssumed(cast<TypeRefNullable>(ref).inner);
            case TypeRefKind::Array: return mentionsAssumed(cast<TypeRefArray>(ref).element);
            case TypeRefKind::Tuple:
                for (const TypeRef& e : cast<TypeRefTuple>(ref).elements) {
                    if (mentionsAssumed(e)) return true;
                }
                return false;
            case TypeRefKind::Pointer: return mentionsAssumed(cast<TypeRefPointer>(ref).inner);
            default: return false;
        }
    }

    /** `&&` needs both sides; `||` needs either. Unknown on one side is not fatal. */
    Outcome both(const ExprBinary& expr, const Bindings& env, bool requireAll) {
        Outcome left = eval(expr.left, env);
        Outcome right = eval(expr.right, env);
        std::vector<Outcome> sides{left, right};
        if (requireAll) return firstOf(sides);
        for (const Outcome& o : sides) {
            if (o.kind == Outcome::Kind::Satisfied) return Outcome::satisfied();
        }
        for (const Outcome& o : sides) {
            if (o.kind == Outcome::Kind::Unknown) return o;
        }
        std::vector<std::string> reasons;
        for (const Outcome& o : sides) {
            if (o.kind == Outcome::Kind::Violated) reasons.push_back(o.text);
        }
        return Outcome::violated(join(reasons, " and "));
    }

    /** A comparison between two integers, e.g. `N == 4` or `(...T).size >= 2`. */
    Outcome compare(const ExprBinary& expr, const Bindings& env) {
        std::optional<int64_t> left = constOf(expr.left, env);
        // Kotlin's render() of a comparison is "constraint".
        if (!left) return Outcome::unknown("constraint");
        std::optional<int64_t> right = constOf(expr.right, env);
        if (!right) return Outcome::unknown("constraint");
        bool holds;
        switch (expr.op) {
            case TokenType::EQUAL_EQUAL: holds = *left == *right; break;
            case TokenType::BANG_EQUAL: holds = *left != *right; break;
            case TokenType::LESS: holds = *left < *right; break;
            case TokenType::LESS_EQUAL: holds = *left <= *right; break;
            case TokenType::GREATER: holds = *left > *right; break;
            case TokenType::GREATER_EQUAL: holds = *left >= *right; break;
            default: return Outcome::unknown(std::string("comparison ") + tokenTypeName(expr.op));
        }
        if (holds) return Outcome::satisfied();
        return Outcome::violated("'" + render(expr.left) + " " + spell(expr.op) + " " + render(expr.right) + "': " + str(*left) +
                                 " vs " + str(*right));
    }
};

}  // namespace

Binding Binding::type(std::string name, std::vector<TypeRef> args) {
    Binding b;
    b.kind = Kind::Type;
    b.name = std::move(name);
    b.args = std::move(args);
    return b;
}

Binding Binding::constant(int64_t value) {
    Binding b;
    b.kind = Kind::Const;
    b.value = value;
    return b;
}

Binding Binding::structural(TypeRef ref) {
    Binding b;
    b.kind = Kind::Structural;
    b.ref = ref;
    return b;
}

Outcome evaluate(Arena& arena, const Expr& clause, const Bindings& bindings, const SymbolTable* table, const Assumed& assumed) {
    if (!clause) return Outcome::satisfied();
    Evaluation evaluation{arena, table, assumed};
    return evaluation.eval(clause, bindings);
}

std::optional<std::vector<int64_t>> rangeOf(const Expr& range, const Bindings& bindings, const OrderedMap<std::string, int64_t>& extra) {
    Bindings env = bindings;
    for (const auto& [name, value] : extra.entries) env.set(name, Binding::constant(value));
    std::optional<LongProgression> span = progression(range, env);
    if (!span) return std::nullopt;
    return span->values();
}

Expr substituteConsts(Arena& arena, const Expr& expr, const OrderedMap<std::string, int64_t>& values) {
    switch (expr.kind()) {
        case ExprKind::Identifier: {
            const ExprIdentifier& id = cast<ExprIdentifier>(expr);
            if (const int64_t* v = values.get(id.name)) return make(arena, ExprIntLiteral{*v, id.line, id.column});
            return expr;
        }
        case ExprKind::Binary: {
            ExprBinary c = cast<ExprBinary>(expr);
            c.left = substituteConsts(arena, c.left, values);
            c.right = substituteConsts(arena, c.right, values);
            return make(arena, std::move(c));
        }
        case ExprKind::Unary: {
            ExprUnary c = cast<ExprUnary>(expr);
            c.operand = substituteConsts(arena, c.operand, values);
            return make(arena, std::move(c));
        }
        case ExprKind::IncDec: {
            ExprIncDec c = cast<ExprIncDec>(expr);
            c.target = substituteConsts(arena, c.target, values);
            return make(arena, std::move(c));
        }
        case ExprKind::Grouping: {
            ExprGrouping c = cast<ExprGrouping>(expr);
            c.expr = substituteConsts(arena, c.expr, values);
            return make(arena, std::move(c));
        }
        case ExprKind::InCheck: {
            ExprInCheck c = cast<ExprInCheck>(expr);
            c.value = substituteConsts(arena, c.value, values);
            c.collection = substituteConsts(arena, c.collection, values);
            return make(arena, std::move(c));
        }
        case ExprKind::Range: {
            ExprRange c = cast<ExprRange>(expr);
            c.from = substituteConsts(arena, c.from, values);
            c.to = substituteConsts(arena, c.to, values);
            return make(arena, std::move(c));
        }
        default:
            return expr;
    }
}

std::optional<Binding> bindingOf(Arena& arena, const TypeRef& arg) {
    switch (arg.kind()) {
        case TypeRefKind::Const: return Binding::constant(cast<TypeRefConst>(arg).value);
        // `Array<T>` is a container of `T`s, decided by what it holds.
        case TypeRefKind::Named: {
            const TypeRefNamed& n = cast<TypeRefNamed>(arg);
            if (n.name == ARRAY && !n.args.empty()) return Binding::structural(make(arena, TypeRefArray{n.args[0]}));
            return Binding::type(n.name, n.args);
        }
        // A borrow is of the type it borrows.
        case TypeRefKind::Reference: return bindingOf(arena, cast<TypeRefReference>(arg).inner);
        case TypeRefKind::None: return std::nullopt;
        default: return Binding::structural(arg);
    }
}

Bindings bindingsFor(Arena& arena, const std::vector<std::string>& params, const std::optional<std::string>& variadicParam,
                     const std::vector<TypeRef>& args) {
    Bindings out;
    for (size_t index = 0; index < params.size(); index++) {
        const std::string& param = params[index];
        if (variadicParam && param == *variadicParam) {
            Binding pack;
            pack.kind = Binding::Kind::Pack;
            std::vector<std::shared_ptr<Binding>> elements;
            size_t tail = index < args.size() ? args.size() - index : 0;
            for (size_t i = index; i < args.size(); i++) {
                std::optional<Binding> b = bindingOf(arena, args[i]);
                elements.push_back(b ? std::make_shared<Binding>(*b) : nullptr);
            }
            pack.length = (int64_t)tail;
            pack.elementTypes = std::move(elements);
            out.set(param, std::move(pack));
        } else if (index < args.size()) {
            if (std::optional<Binding> b = bindingOf(arena, args[index])) out.set(param, *b);
        }
    }
    return out;
}

}  // namespace ConstraintEvaluator

}  // namespace azora
