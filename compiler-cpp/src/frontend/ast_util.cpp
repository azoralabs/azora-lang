#include "frontend/ast_util.h"

#include "base/chars.h"
#include "base/failure.h"
#include "base/kotlin_math.h"
#include "base/numbers.h"
#include "base/text.h"

namespace azora {

static std::string joinTypes(const std::vector<TypeRef>& types, const std::string& separator) {
    std::string out;
    for (size_t i = 0; i < types.size(); i++) {
        if (i > 0) out += separator;
        out += toString(types[i]);
    }
    return out;
}

static size_t distinctCount(const std::vector<std::string>& items) {
    OrderedSet<std::string> seen;
    for (const std::string& item : items) seen.add(item);
    return seen.size();
}

std::string toString(const TypeRef& type) {
    switch (type.kind()) {
        case TypeRefKind::None:
            return "null";
        case TypeRefKind::Named: {
            const TypeRefNamed& n = cast<TypeRefNamed>(type);
            if (TypeFunctionCall::isCall(n)) return TypeFunctionCall::name(n) + "<" + joinTypes(n.args, ", ") + ">";
            std::string qualified = n.qualifier ? *n.qualifier + "::" + n.name : n.name;
            if (n.args.empty()) return qualified;
            return qualified + "<" + joinTypes(n.args, ", ") + ">";
        }
        case TypeRefKind::Array:
            return "[" + toString(cast<TypeRefArray>(type).element) + "]";
        case TypeRefKind::Map: {
            const TypeRefMap& m = cast<TypeRefMap>(type);
            return "map[" + toString(m.key) + ", " + toString(m.value) + "]";
        }
        case TypeRefKind::Set:
            return "set[" + toString(cast<TypeRefSet>(type).element) + "]";
        case TypeRefKind::Function: {
            const TypeRefFunction& f = cast<TypeRefFunction>(type);
            std::string prefix = std::string(f.isForeign ? "foreign " : "") +
                                 (f.kind == CallableKind::FUNC ? std::string() : std::string(surfaceName(f.kind)) + " ");
            std::string context;
            if (f.receivers.size() == 1) context = toString(f.receivers[0]) + ".";
            else if (f.receivers.size() > 1) context = "(" + joinTypes(f.receivers, ", ") + ").";
            return prefix + context + "(" + joinTypes(f.params, ", ") + ") -> " + toString(f.ret);
        }
        case TypeRefKind::Tuple:
            return "(" + joinTypes(cast<TypeRefTuple>(type).elements, ", ") + ")";
        case TypeRefKind::Nullable:
            return toString(cast<TypeRefNullable>(type).inner) + "?";
        case TypeRefKind::Failable: {
            const TypeRefFailable& f = cast<TypeRefFailable>(type);
            if (f.errSets.size() == 1) return toString(f.ok) + "!" + f.errSets[0];
            return toString(f.ok) + "![" + join(f.errSets, ", ") + "]";
        }
        case TypeRefKind::Pointer:
            return toString(cast<TypeRefPointer>(type).inner) + "*";
        case TypeRefKind::Const: {
            const TypeRefConst& c = cast<TypeRefConst>(type);
            return c.label ? *c.label : str((long long)c.value);
        }
        case TypeRefKind::Reference: {
            const TypeRefReference& r = cast<TypeRefReference>(type);
            std::string sigil = r.kind == RefKind::MUTABLE ? "!" : "&";
            if (r.origins.empty()) return toString(r.inner) + sigil;
            return toString(r.inner) + sigil + "|" + join(r.origins, ", ") + "|";
        }
    }
    AZORA_UNREACHABLE();
}

std::string displayName(const TypeRef& type) {
    if (TypeRefNamed* n = as<TypeRefNamed>(type)) return n->name;
    return toString(type);
}

TypeRefFailable* makeFailable(Arena& arena, const TypeRef& ok, const std::vector<std::string>& errSets) {
    if (errSets.empty()) failArgument("A failable type requires at least one error set");
    if (distinctCount(errSets) != errSets.size()) failArgument("A failable type cannot repeat an error set");
    return make(arena, TypeRefFailable{ok, errSets});
}

bool isHole(const TypeRef& type) {
    TypeRefNamed* n = as<TypeRefNamed>(type);
    return n != nullptr && n->name == TYPE_HOLE;
}

std::vector<int64_t> LongProgression::values() const {
    std::vector<int64_t> out;
    if (isEmpty()) return out;
    for (int64_t v = first;; v += step) {
        out.push_back(v);
        if (v == last) break;
    }
    return out;
}

LongProgression progressionOf(const ExprRange& range, int64_t from, int64_t to) {
    // LongRange.EMPTY is 1..0.
    if (range.descending && from <= to) return LongProgression{1, 0, 1};
    // (from - 1) downTo to: last is `to` when the span is not empty.
    if (range.descending) return LongProgression{from - 1, to, -1};
    if (range.inclusive) return LongProgression{from, to, 1};
    // from until to
    if (to == INT64_MIN) return LongProgression{1, 0, 1};
    return LongProgression{from, to - 1, 1};
}

std::vector<int64_t> constantProgression(const ExprRange& range, int64_t from, int64_t to) {
    return progressionOf(range, from, to).values();
}

std::string numericValueText(const NumericLiteral& literal) {
    if (const int64_t* i = std::get_if<int64_t>(&literal.value)) return str((long long)*i);
    return javaDoubleToString(std::get<double>(literal.value));
}

bool firstIsUpperCase(const std::string& name) {
    std::u16string u = decodeWtf8(name);
    return !u.empty() && isUpperCase(u[0]);
}

bool firstIsLowerCase(const std::string& name) {
    std::u16string u = decodeWtf8(name);
    return !u.empty() && isLowerCase(u[0]);
}

std::string capitalizeFirst(const std::string& name) {
    std::u16string u = decodeWtf8(name);
    if (u.empty()) return name;
    std::string head = uppercase(encodeUtf8(u.substr(0, 1)));
    return head + encodeUtf8(u.substr(1));
}

std::string decapitalizeFirst(const std::string& name) {
    std::u16string u = decodeWtf8(name);
    if (u.empty()) return name;
    std::string head = lowercase(encodeUtf8(u.substr(0, 1)));
    return head + encodeUtf8(u.substr(1));
}

std::optional<std::pair<Expr, Expr>> asRepeatedConstruction(const ExprBinary& expr) {
    if (expr.op != TokenType::STAR) return std::nullopt;
    Expr left = expr.left;
    bool constructs = is<ExprCall>(left) || is<ExprInferredMember>(left);
    if (ExprAlloc* alloc = as<ExprAlloc>(left)) {
        constructs = constructs || is<ExprCall>(alloc->value) || is<ExprInferredMember>(alloc->value);
    }
    if (!constructs) return std::nullopt;
    return std::make_pair(left, expr.right);
}

std::string lambdaReceiverName(int32_t line, int32_t column, int32_t index) {
    return "__ctx" + str(index) + "_" + str(line) + "_" + str(column);
}

namespace TypeFunctionCall {
static const char* const PREFIX = "__azora_type_function__";
TypeRefNamed* create(Arena& arena, const std::string& name, const std::vector<TypeRef>& args,
                     const std::vector<Expr>& valueArgs) {
    TypeRefNamed ref;
    ref.name = PREFIX + name;
    ref.args = args;
    ref.valueArgs = valueArgs;
    return make(arena, ref);
}
bool isCall(const TypeRefNamed& ref) { return startsWith(ref.name, PREFIX); }
std::string name(const TypeRefNamed& ref) { return removePrefix(ref.name, PREFIX); }
}  // namespace TypeFunctionCall

namespace NamedTypeMacroCall {
static const char* const PREFIX = "__azora_named_type_macro__";
static const char* const SEPARATOR = "\x1f";
const char* formName(Form form) {
    switch (form) {
        case Form::Prefix: return "Prefix";
        case Form::List: return "List";
        case Form::Infix: return "Infix";
    }
    AZORA_UNREACHABLE();
}
TypeRefNamed* create(Arena& arena, const std::string& name, const std::vector<TypeRef>& args,
                     const std::string& modifier, Form form) {
    TypeRefNamed ref;
    ref.name = std::string(PREFIX) + modifier + SEPARATOR + formName(form) + SEPARATOR + name;
    ref.args = args;
    return make(arena, ref);
}
bool isCall(const TypeRefNamed& ref) { return startsWith(ref.name, PREFIX); }
std::string modifier(const TypeRefNamed& ref) { return substringBefore(removePrefix(ref.name, PREFIX), SEPARATOR); }
Form form(const TypeRefNamed& ref) {
    std::string text = substringBefore(substringAfter(removePrefix(ref.name, PREFIX), SEPARATOR), SEPARATOR);
    if (text == "Prefix") return Form::Prefix;
    if (text == "List") return Form::List;
    if (text == "Infix") return Form::Infix;
    failArgument("No enum constant org.azora.lang.frontend.NamedTypeMacroCall.Form." + text);
}
std::string name(const TypeRefNamed& ref) {
    return substringAfter(substringAfter(removePrefix(ref.name, PREFIX), SEPARATOR), SEPARATOR);
}
}  // namespace NamedTypeMacroCall

namespace ModuleQualifiedSymbol {
static const char* const PREFIX = "__azora_module_qualified__";
static const char* const SEPARATOR = "::";
std::string create(const std::string& module, const std::string& symbol) {
    return std::string(PREFIX) + module + SEPARATOR + symbol;
}
bool isQualified(const std::string& name) { return startsWith(name, PREFIX) && contains(name, SEPARATOR); }
std::string module(const std::string& name) { return substringBefore(removePrefix(name, PREFIX), SEPARATOR); }
std::string symbol(const std::string& name) { return substringAfter(removePrefix(name, PREFIX), SEPARATOR); }
}  // namespace ModuleQualifiedSymbol

ExprArrayLiteral* arrayCallLiteral(Arena& arena, const ExprCall& call) {
    ExprArrayLiteral literal;
    literal.elements = call.args;
    literal.line = call.line;
    literal.column = call.column;
    literal.length = call.length;
    if (!call.typeArgs.empty()) {
        TypeRefNamed ref;
        ref.name = call.callee;
        ref.args = call.typeArgs;
        literal.contextualType = make(arena, ref);
    }
    return make(arena, literal);
}

ExprTupleLit* tupleCallLiteral(Arena& arena, const ExprCall& call) {
    if (call.args.size() < 2) return nullptr;
    ExprTupleLit literal;
    literal.elements = call.args;
    literal.line = call.line;
    literal.column = call.column;
    return make(arena, literal);
}

}  // namespace azora
