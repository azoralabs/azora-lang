// Port of semantic/SerializationDeriver.kt.
#include "semantic/serialization_deriver.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/numbers.h"
#include "base/ordered.h"
#include "base/text.h"
#include "frontend/ast_util.h"
#include "frontend/lexer.h"
#include "frontend/parser.h"
#include "semantic/decorator_metadata.h"

#include <algorithm>

namespace azora {
namespace SerializationDeriver {

namespace {

const OrderedSet<std::string>& integerTypes() {
    static const OrderedSet<std::string> names{"Byte", "UByte", "Short", "UShort", "Int", "UInt", "Long", "ULong", "Cent", "UCent"};
    return names;
}
const OrderedSet<std::string>& floatingTypes() {
    static const OrderedSet<std::string> names{"Float", "Double", "Quad"};
    return names;
}
bool isPrimitive(const std::string& name) {
    return integerTypes().contains(name) || floatingTypes().contains(name) || name == "String" || name == "Bool" || name == "Char";
}

struct Helpers {
    std::string provider;
    std::string conversionProvider;
    std::string providerModule;
    std::string conversionModule;
    /** The module declaring the list type generated code builds. */
    std::string listModule;
};

struct Roles {
    OrderedSet<std::string> all;
    OrderedSet<std::string> azon;
    OrderedSet<std::string> name;
    OrderedSet<std::string> ignore;
    OrderedSet<std::string> required;
    Helpers helpers;
    bool deferGeneric = false;

    OrderedSet<std::string> roots() const {
        OrderedSet<std::string> out = all;
        for (const std::string& a : azon.items) out.add(a);
        return out;
    }
};

struct FieldPlan {
    const PackField* field;
    std::string wireName;
    bool ignored = false;
    bool required = false;
    std::optional<std::string> defaultSource;
};

std::string qualifiedName(const std::string& provider, const std::string& name) {
    return provider.empty() ? name : provider + "::" + name;
}

std::string renderType(const TypeRef& type) {
    switch (type.kind()) {
        case TypeRefKind::Named: {
            const TypeRefNamed& named = cast<TypeRefNamed>(type);
            if (named.args.empty()) return named.name;
            std::vector<std::string> args;
            for (const TypeRef& a : named.args) args.push_back(renderType(a));
            return named.name + "<" + join(args, ", ") + ">";
        }
        case TypeRefKind::Array: return "[" + renderType(cast<TypeRefArray>(type).element) + "]";
        case TypeRefKind::Map:
            return "Map<" + renderType(cast<TypeRefMap>(type).key) + ", " + renderType(cast<TypeRefMap>(type).value) + ">";
        case TypeRefKind::Set: return "Set<" + renderType(cast<TypeRefSet>(type).element) + ">";
        case TypeRefKind::Tuple: {
            std::vector<std::string> parts;
            for (const TypeRef& e : cast<TypeRefTuple>(type).elements) parts.push_back(renderType(e));
            return "(" + join(parts, ", ") + ")";
        }
        case TypeRefKind::Nullable: return renderType(cast<TypeRefNullable>(type).inner) + "?";
        case TypeRefKind::Pointer: return renderType(cast<TypeRefPointer>(type).inner) + "*";
        case TypeRefKind::Function: return toString(type);
        case TypeRefKind::Failable: {
            const TypeRefFailable& f = cast<TypeRefFailable>(type);
            if (f.errSets.size() == 1) return renderType(f.ok) + " ?! " + f.errSets[0];
            return renderType(f.ok) + " ?! [" + join(f.errSets, ", ") + "]";
        }
        case TypeRefKind::Reference: {
            const TypeRefReference& r = cast<TypeRefReference>(type);
            std::string out = renderType(r.inner) + (r.kind == RefKind::MUTABLE ? "!" : "&");
            if (!r.origins.empty()) out += "|" + join(r.origins, ", ") + "|";
            return out;
        }
        case TypeRefKind::Const: return str(cast<TypeRefConst>(type).value);
        case TypeRefKind::None: return "null";
    }
    return "";
}

std::string quote(const std::string& value) {
    std::string out = "\"";
    for (char c : value) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c; break;
        }
    }
    return out + "\"";
}

std::string sourceOperator(TokenType type) {
    switch (type) {
        case TokenType::PLUS: return "+";
        case TokenType::MINUS: return "-";
        case TokenType::STAR: return "*";
        case TokenType::SLASH: return "/";
        case TokenType::PERCENT: return "%";
        case TokenType::EQUAL_EQUAL: return "==";
        case TokenType::BANG_EQUAL: return "!=";
        case TokenType::LESS: return "<";
        case TokenType::LESS_EQUAL: return "<=";
        case TokenType::GREATER: return ">";
        case TokenType::GREATER_EQUAL: return ">=";
        case TokenType::AND_AND: return "&&";
        case TokenType::OR_OR: return "||";
        case TokenType::BANG: return "!";
        case TokenType::PLUS_PLUS: return "++";
        case TokenType::MINUS_MINUS: return "--";
        default: fail(std::string("unsupported source operator ") + tokenTypeName(type));
    }
}

std::string escapeChar(int32_t value) {
    switch (value) {
        case '\\': return "\\\\";
        case '\'': return "\\'";
        case '\n': return "\\n";
        case '\r': return "\\r";
        case '\t': return "\\t";
        default: {
            std::string out;
            appendCodePoint(out, (uint32_t)value);
            return out;
        }
    }
}

std::optional<std::string> renderExpr(const Expr& expr);

std::optional<std::string> renderList(const std::vector<Expr>& items, const char* open, const char* close) {
    std::vector<std::string> parts;
    for (const Expr& e : items) {
        std::optional<std::string> r = renderExpr(e);
        if (!r) return std::nullopt;
        parts.push_back(*r);
    }
    return std::string(open) + join(parts, ", ") + close;
}

std::optional<std::string> renderExpr(const Expr& expr) {
    switch (expr.kind()) {
        case ExprKind::IntLiteral: return str(cast<ExprIntLiteral>(expr).value);
        case ExprKind::DoubleLiteral: return javaDoubleToString(cast<ExprDoubleLiteral>(expr).value);
        case ExprKind::StringLiteral: return quote(cast<ExprStringLiteral>(expr).value);
        case ExprKind::CharLiteral: return "'" + escapeChar(cast<ExprCharLiteral>(expr).value) + "'";
        case ExprKind::BoolLiteral: return std::string(cast<ExprBoolLiteral>(expr).value ? "true" : "false");
        case ExprKind::NullLiteral: return std::string("null");
        case ExprKind::Identifier: return cast<ExprIdentifier>(expr).name;
        case ExprKind::Grouping: {
            std::optional<std::string> inner = renderExpr(cast<ExprGrouping>(expr).expr);
            if (!inner) return std::nullopt;
            return "(" + *inner + ")";
        }
        case ExprKind::Unary: {
            std::optional<std::string> inner = renderExpr(cast<ExprUnary>(expr).operand);
            if (!inner) return std::nullopt;
            return sourceOperator(cast<ExprUnary>(expr).op) + *inner;
        }
        case ExprKind::IncDec: {
            const ExprIncDec& e = cast<ExprIncDec>(expr);
            std::optional<std::string> inner = renderExpr(e.target);
            if (!inner) return std::nullopt;
            return e.prefix ? sourceOperator(e.op) + *inner : *inner + sourceOperator(e.op);
        }
        case ExprKind::Binary: {
            const ExprBinary& b = cast<ExprBinary>(expr);
            std::optional<std::string> left = renderExpr(b.left);
            std::optional<std::string> right = renderExpr(b.right);
            if (!left || !right) return std::nullopt;
            return "(" + *left + " " + sourceOperator(b.op) + " " + *right + ")";
        }
        case ExprKind::Call: {
            const ExprCall& c = cast<ExprCall>(expr);
            std::optional<std::string> args = renderList(c.args, "(", ")");
            if (!args) return std::nullopt;
            return c.callee + *args;
        }
        case ExprKind::Member: {
            std::optional<std::string> target = renderExpr(cast<ExprMember>(expr).target);
            if (!target) return std::nullopt;
            return *target + "." + cast<ExprMember>(expr).name;
        }
        case ExprKind::ArrayLiteral: return renderList(cast<ExprArrayLiteral>(expr).elements, "[", "]");
        case ExprKind::SetLiteral: return renderList(cast<ExprSetLiteral>(expr).elements, "![", "]");
        // `[:]`, not `[]`: an empty map is spelled apart from an empty sequence.
        case ExprKind::MapLit: {
            const ExprMapLit& m = cast<ExprMapLit>(expr);
            if (m.entries.empty()) return std::string("[:]");
            std::vector<std::string> parts;
            for (const auto& [k, v] : m.entries) {
                std::optional<std::string> key = renderExpr(k);
                if (!key) return std::nullopt;
                std::optional<std::string> value = renderExpr(v);
                if (!value) return std::nullopt;
                parts.push_back(*key + ": " + *value);
            }
            return "[" + join(parts, ", ") + "]";
        }
        case ExprKind::TupleLit: return renderList(cast<ExprTupleLit>(expr).elements, "(", ")");
        case ExprKind::NamedArg: {
            std::optional<std::string> value = renderExpr(cast<ExprNamedArg>(expr).value);
            if (!value) return std::nullopt;
            return cast<ExprNamedArg>(expr).name + ": " + *value;
        }
        case ExprKind::NullCoalesce: {
            const ExprNullCoalesce& n = cast<ExprNullCoalesce>(expr);
            std::optional<std::string> left = renderExpr(n.left);
            std::optional<std::string> right = renderExpr(n.right);
            if (!left || !right) return std::nullopt;
            return "(" + *left + " ?? " + *right + ")";
        }
        default:
            return std::nullopt;
    }
}

bool comparesByValue(const TypeRef& type) {
    if (TypeRefNullable* n = as<TypeRefNullable>(type)) return comparesByValue(n->inner);
    if (TypeRefNamed* named = as<TypeRefNamed>(type)) {
        return named->args.empty() && (named->name == "String" || named->name == "Bool" || named->name == "Char" ||
                                       integerTypes().contains(named->name) || floatingTypes().contains(named->name));
    }
    return false;
}

bool isCollection(const TypeRef& type) {
    TypeRefNamed* named = as<TypeRefNamed>(type);
    if (named == nullptr) return false;
    return ((named->name == "List" || named->name == "Set") && named->args.size() == 1) ||
           (named->name == "Map" && named->args.size() == 2);
}

std::string encodeExpr(const std::string& value, const TypeRef& type, const Helpers& helpers) {
    if (TypeRefNullable* nullable = as<TypeRefNullable>(type)) {
        std::string present = value + " as " + renderType(nullable->inner);
        return "if " + value + " == null { SerialValue.Null } else { " + encodeExpr(present, nullable->inner, helpers) + " }";
    }
    const TypeRefNamed& named = cast<TypeRefNamed>(type);
    if (named.name == "String") return "SerialValue.Text(" + value + ")";
    if (named.name == "Bool") return "SerialValue.Bool(" + value + ")";
    if (named.name == "Char") return "SerialValue.Text(" + qualifiedName(helpers.conversionProvider, "toString") + "(" + value + "))";
    if (integerTypes().contains(named.name) || floatingTypes().contains(named.name)) {
        return "SerialValue.Number(" + qualifiedName(helpers.conversionProvider, "toString") + "(" + value + "))";
    }
    return "try " + value + ".toSerialValue(" + value + ")";
}

std::string decodeExpr(const std::string& raw, const TypeRef& type, const std::string& receiverValue, const Helpers& helpers) {
    if (TypeRefNullable* nullable = as<TypeRefNullable>(type)) {
        return "if " + raw + " == SerialValue.Null { null } else { " + decodeExpr(raw, nullable->inner, receiverValue, helpers) + " }";
    }
    const TypeRefNamed& named = cast<TypeRefNamed>(type);
    if (named.name == "String") return "try " + qualifiedName(helpers.provider, "serialAsText") + "(" + raw + ")";
    if (named.name == "Bool") return "try " + qualifiedName(helpers.provider, "serialAsBool") + "(" + raw + ")";
    if (named.name == "Char") return "try " + qualifiedName(helpers.provider, "serialAsChar") + "(" + raw + ")";
    if (integerTypes().contains(named.name)) return "try " + qualifiedName(helpers.provider, "serialAs" + named.name) + "(" + raw + ")";
    if (floatingTypes().contains(named.name)) {
        return "try " + qualifiedName(helpers.provider, "serialAsReal") + "(" + raw + ") as " + named.name;
    }
    return "try " + receiverValue + ".fromSerialValue(" + raw + ")";
}

struct Source {
    std::string text;
    void line(const std::string& s) { text += s + "\n"; }
    void line() { text += "\n"; }
};

std::string appendEncodedField(Source& out, const FieldPlan& plan, const Helpers& helpers) {
    const PackField& field = *plan.field;
    TypeRefNamed* type = as<TypeRefNamed>(field.type);
    if (type == nullptr) return encodeExpr("value." + field.name, field.type, helpers);
    if (type->name == "Map" && type->args.size() == 2) {
        const TypeRef& valueType = type->args[1];
        std::string fieldsName = "__encoded_" + field.name;
        out.line("        var " + fieldsName + " = ArrayList<SerialField>()");
        std::string keyName = "__key_" + field.name;
        out.line("        for " + keyName + " in value." + field.name + ".keys() {");
        std::string mapValue = "value." + field.name + "[" + keyName + "] as " + renderType(valueType);
        out.line("            " + fieldsName + ".add(SerialField(" + keyName + " as String, " + encodeExpr(mapValue, valueType, helpers) + "))");
        out.line("        }");
        return "SerialValue.Object(take " + fieldsName + ")";
    }
    if ((type->name != "List" && type->name != "Set") || type->args.size() != 1) {
        return encodeExpr("value." + field.name, field.type, helpers);
    }
    // Walked rather than indexed: a `Set` declares no subscript.
    const TypeRef& element = type->args[0];
    std::string valuesName = "__encoded_" + field.name;
    std::string elementName = "__element_" + field.name;
    out.line("        var " + valuesName + " = ArrayList<SerialValue>()");
    out.line("        for " + elementName + " in value." + field.name + " {");
    out.line("            " + valuesName + ".add(" + encodeExpr(elementName, element, helpers) + ")");
    out.line("        }");
    return "SerialValue.Array(take " + valuesName + ")";
}

void appendDecodedCollection(Source& out, const FieldPlan& plan, const Helpers& helpers) {
    const PackField& field = *plan.field;
    const TypeRefNamed& type = cast<TypeRefNamed>(field.type);
    std::string decodedName = "__decoded_" + field.name;
    std::string indexName = "__decode_" + field.name + "_index";
    if (type.name == "Map") {
        const TypeRef& valueType = type.args[1];
        std::string fieldsName = "__map_fields_" + field.name;
        out.line("                var " + decodedName + ": Mutable" + renderType(field.type) + " = [:]");
        out.line("                if __seen_" + field.name + " {");
        out.line("                    when __raw_" + field.name + " {");
        out.line("                        SerialValue.Object(" + fieldsName + ") -> {");
        out.line("                            var " + indexName + " = 0");
        out.line("                            while " + indexName + " < " + fieldsName + ".size {");
        std::string fieldName = "__map_field_" + field.name;
        out.line("                                fin " + fieldName + " = " + qualifiedName(helpers.provider, "serialFieldAt") + "(" +
                 fieldsName + ", " + indexName + ")");
        out.line("                                " + decodedName + ".put(" + fieldName + ".name, " +
                 decodeExpr(fieldName + ".value", valueType, "self." + field.name, helpers) + ")");
        out.line("                                " + indexName + " += 1");
        out.line("                            }");
        out.line("                        }");
        out.line("                        else -> { return .UnexpectedType }");
        out.line("                    }");
        out.line("                }");
        return;
    }
    const TypeRef& element = single(type.args);
    std::string valuesName = "__values_" + field.name;
    // The field's own spec, mutable, built by its literal.
    out.line("                var " + decodedName + ": Mutable" + renderType(field.type) + " = []");
    out.line("                if __seen_" + field.name + " {");
    out.line("                    when __raw_" + field.name + " {");
    out.line("                        SerialValue.Array(" + valuesName + ") -> {");
    out.line("                            var " + indexName + " = 0");
    out.line("                            while " + indexName + " < " + valuesName + ".size {");
    std::string rawElement = qualifiedName(helpers.provider, "serialValueAt") + "(" + valuesName + ", " + indexName + ")";
    out.line("                                " + decodedName + ".add(" + decodeExpr(rawElement, element, "self." + field.name, helpers) + ")");
    out.line("                                " + indexName + " += 1");
    out.line("                            }");
    out.line("                        }");
    out.line("                        else -> { return .UnexpectedType }");
    out.line("                    }");
    out.line("                }");
}

/** AZON is the only text format the standard library ships: one pair of methods. */
void appendTextMethods(Source& out, const std::string& typeName, const Helpers& helpers) {
    out.line();
    out.line("    func &.toAzon(value: " + typeName + "&, options: SerializerOptions&): String ?! SerializationError {");
    out.line("        fin __serialValue = try self.toSerialValue(value)");
    out.line("        return try " + qualifiedName(helpers.provider, "encodeSerialValue") + "(__serialValue, options)");
    out.line("    }");
    out.line();
    out.line("    func &.fromAzon(input: String, options: SerializerOptions&): " + typeName + " ?! SerializationError {");
    out.line("        fin __serialValue = try " + qualifiedName(helpers.provider, "decodeSerialValue") + "(input, options)");
    out.line("        return try self.fromSerialValue(__serialValue)");
    out.line("    }");
}

std::string generateImpl(const TopLevelPack& pack, const std::vector<FieldPlan>& fields, bool ignoreUnknown, bool encodeDefaults,
                         const Helpers& helpers) {
    Source out;
    out.line("impl " + pack.name + " {");
    out.line("    func &.toSerialValue(value: " + pack.name + "&): SerialValue ?! SerializationError {");
    out.line("        var __serialFields = ArrayList<SerialField>()");
    for (const FieldPlan& plan : fields) {
        if (plan.ignored) continue;
        std::string encoded = appendEncodedField(out, plan, helpers);
        if (!encodeDefaults && plan.defaultSource && !plan.required && comparesByValue(plan.field->type)) {
            out.line("        if value." + plan.field->name + " != " + *plan.defaultSource + " {");
            out.line("            __serialFields.add(SerialField(" + quote(plan.wireName) + ", " + encoded + "))");
            out.line("        }");
        } else {
            out.line("        __serialFields.add(SerialField(" + quote(plan.wireName) + ", " + encoded + "))");
        }
    }
    out.line("        return SerialValue.Object(take __serialFields)");
    out.line("    }");
    out.line();
    out.line("    func &.fromSerialValue(value: SerialValue&): " + pack.name + " ?! SerializationError {");
    out.line("        when value {");
    out.line("            SerialValue.Object(__serialFields) -> {");
    std::vector<const FieldPlan*> active;
    for (const FieldPlan& plan : fields) {
        if (plan.ignored) continue;
        active.push_back(&plan);
        out.line("                var __seen_" + plan.field->name + " = false");
        // Raw fields are views into the caller's tree: an explicit borrow.
        out.line("                var __raw_" + plan.field->name + ": SerialValue& = value");
    }
    out.line("                var __serialIndex = 0");
    out.line("                while __serialIndex < __serialFields.size {");
    out.line("                    fin __serialField = " + qualifiedName(helpers.provider, "serialFieldAt") + "(__serialFields, __serialIndex)");
    for (size_t index = 0; index < active.size(); index++) {
        const FieldPlan& plan = *active[index];
        std::string prefix = index == 0 ? "if" : "else if";
        out.line("                    " + prefix + " __serialField.name == " + quote(plan.wireName) + " {");
        out.line("                        if __seen_" + plan.field->name + " { return .DuplicateField }");
        out.line("                        __seen_" + plan.field->name + " = true");
        out.line("                        __raw_" + plan.field->name + " = __serialField.value");
        out.line("                    }");
    }
    if (active.empty()) {
        if (!ignoreUnknown) out.line("                    return .UnknownField");
    } else if (!ignoreUnknown) {
        out.line("                    else { return .UnknownField }");
    }
    out.line("                    __serialIndex += 1");
    out.line("                }");
    for (const FieldPlan* plan : active) {
        if (plan->required || !plan->field->default_) out.line("                if !__seen_" + plan->field->name + " { return .MissingField }");
    }
    for (const FieldPlan* plan : active) {
        if (isCollection(plan->field->type)) appendDecodedCollection(out, *plan, helpers);
    }
    out.line("                return " + pack.name + "(");
    for (size_t index = 0; index < fields.size(); index++) {
        const FieldPlan& plan = fields[index];
        const std::string& name = plan.field->name;
        std::string value;
        if (plan.ignored) {
            value = *plan.defaultSource;
        } else if (isCollection(plan.field->type) && plan.defaultSource) {
            value = "if __seen_" + name + " { take __decoded_" + name + " } else { " + *plan.defaultSource + " }";
        } else if (isCollection(plan.field->type)) {
            value = "take __decoded_" + name;
        } else if (plan.defaultSource) {
            value = "if __seen_" + name + " { " + decodeExpr("__raw_" + name, plan.field->type, "self." + name, helpers) +
                    " } else { " + *plan.defaultSource + " }";
        } else {
            value = decodeExpr("__raw_" + name, plan.field->type, "self." + name, helpers);
        }
        out.text += "                    " + name + ": " + value;
        out.line(index + 1 == fields.size() ? "" : ",");
    }
    out.line("                )");
    out.line("            }");
    out.line("            else -> { return .UnexpectedType }");
    out.line("        }");
    out.line("    }");
    appendTextMethods(out, pack.name, helpers);
    out.line("}");
    return out.text;
}

struct Deriver {
    Arena& arena;
    const Program& program;
    std::vector<std::string>& errors;

    std::optional<DecoratorMetadata::Applied> applied(const std::string& identity, const std::string& decorator) {
        Expr receiver = make(arena, ExprIdentifier{identity, 0});
        std::optional<DecoratorMetadata::Site> site = DecoratorMetadata::findSite(receiver, {}, program);
        if (!site) return std::nullopt;
        return DecoratorMetadata::findApplied(*site, decorator, program);
    }

    std::optional<DecoratorMetadata::Applied> appliedAny(const std::string& identity, const OrderedSet<std::string>& decorators) {
        for (const std::string& decorator : decorators.items) {
            if (std::optional<DecoratorMetadata::Applied> found = applied(identity, decorator)) return found;
        }
        return std::nullopt;
    }

    std::optional<bool> boolMetadata(const DecoratorMetadata::Applied& root, const std::string& field, int32_t line) {
        Expr value = DecoratorMetadata::fieldValue(arena, root, field);
        if (!value) return std::nullopt;
        if (ExprBoolLiteral* b = as<ExprBoolLiteral>(value)) return b->value;
        errors.push_back("line " + str(line) + ": serializer option '" + field + "' must be a compile-time Bool literal");
        return std::nullopt;
    }

    std::optional<std::string> stringMetadata(const DecoratorMetadata::Applied& root, const std::string& field, int32_t line) {
        Expr value = DecoratorMetadata::fieldValue(arena, root, field);
        if (!value) return std::nullopt;
        if (ExprStringLiteral* s = as<ExprStringLiteral>(value)) return s->value;
        errors.push_back("line " + str(line) + ": serializer option '" + field + "' must be a compile-time String literal");
        return std::nullopt;
    }

    FieldPlan fieldPlan(const TopLevelPack& pack, const PackField& field, const Roles& roles) {
        std::string identity = pack.name + "." + field.name;
        bool ignored = appliedAny(identity, roles.ignore).has_value();
        bool required = appliedAny(identity, roles.required).has_value();
        std::optional<DecoratorMetadata::Applied> serialName = appliedAny(identity, roles.name);
        std::string configuredName;
        if (serialName) {
            if (std::optional<std::string> configured = stringMetadata(*serialName, "value", pack.line)) configuredName = *configured;
        }
        std::optional<std::string> defaultSource;
        if (field.default_) {
            defaultSource = renderExpr(field.default_);
            if (!defaultSource) {
                errors.push_back("line " + str(pack.line) + ": default value for '" + pack.name + "::" + field.name +
                                 "' cannot be used by generated serialization");
            }
        }
        return FieldPlan{&field, configuredName.empty() ? field.name : configuredName, ignored, required, defaultSource};
    }

    void validateSupportedType(const TypeRef& type, const std::string& owner, const std::string& field,
                               const OrderedSet<std::string>& serializablePacks, int32_t line) {
        std::string where = "'" + owner + "::" + field + "'";
        if (TypeRefNamed* named = as<TypeRefNamed>(type)) {
            if ((named->name == "List" || named->name == "Set") && named->args.size() == 1) {
                TypeRefNamed* element = as<TypeRefNamed>(named->args[0]);
                if (element == nullptr || !element->args.empty() || !isPrimitive(element->name)) {
                    errors.push_back("line " + str(line) + ": generated serializer supports only primitive elements in " + where +
                                     " of type " + renderType(type));
                }
            } else if (named->name == "Map" && named->args.size() == 2) {
                TypeRefNamed* key = as<TypeRefNamed>(named->args[0]);
                TypeRefNamed* value = as<TypeRefNamed>(named->args[1]);
                if (key == nullptr || key->name != "String" || !key->args.empty()) {
                    errors.push_back("line " + str(line) + ": generated serializer requires String keys in " + where + " of type " +
                                     renderType(type));
                }
                if (value == nullptr || !value->args.empty() || !isPrimitive(value->name)) {
                    errors.push_back("line " + str(line) + ": generated serializer supports only primitive map values in " + where +
                                     " of type " + renderType(type));
                }
            } else if (!named->args.empty()) {
                errors.push_back("line " + str(line) + ": generated serializer does not yet support generic field " + where +
                                 " of type " + renderType(type));
            } else if (isPrimitive(named->name) || serializablePacks.contains(named->name)) {
                // supported
            } else {
                errors.push_back("line " + str(line) + ": field " + where + " has non-serializable type '" + named->name + "'");
            }
        } else if (TypeRefNullable* nullable = as<TypeRefNullable>(type)) {
            TypeRefNamed* inner = as<TypeRefNamed>(nullable->inner);
            if (inner == nullptr || !inner->args.empty() || !isPrimitive(inner->name)) {
                errors.push_back("line " + str(line) + ": generated serializer supports only nullable primitive field " + where +
                                 ", got " + renderType(type));
            }
        } else {
            errors.push_back("line " + str(line) + ": generated serializer does not yet support field " + where + " of type " +
                             renderType(type));
        }
    }
};

Roles discoverRoles(const Program& program) {
    OrderedMap<std::string, OrderedSet<std::string>> byRole;
    Roles roles;
    for (const TopLevel& item : program.items) {
        TopLevelDeco* declaration = as<TopLevelDeco>(item);
        if (declaration == nullptr) continue;
        for (const Annotation& annotation : declaration->annotations) {
            if (annotation.name != "Derive") continue;
            auto stringArgument = [&](const std::string& name, size_t position) -> std::optional<std::string> {
                for (const auto& [key, value] : annotation.namedArgs) {
                    if (key != name) continue;
                    if (ExprStringLiteral* s = as<ExprStringLiteral>(value)) return s->value;
                    break;
                }
                if (position < annotation.args.size()) {
                    if (ExprStringLiteral* s = as<ExprStringLiteral>(annotation.args[position])) return s->value;
                }
                return std::nullopt;
            };
            if (stringArgument("generator", 0) != std::optional<std::string>("serializer")) continue;
            std::optional<std::string> role = stringArgument("role", 1);
            if (!role) continue;
            byRole.getOrPut(*role, {}).add(declaration->name);
            if (*role == "all" || *role == "azon") {
                auto set = [&](const std::string& name, size_t position, std::string& into) {
                    std::optional<std::string> v = stringArgument(name, position);
                    if (v && !v->empty()) into = *v;
                };
                set("provider", 2, roles.helpers.provider);
                set("conversionProvider", 3, roles.helpers.conversionProvider);
                set("providerModule", 4, roles.helpers.providerModule);
                set("conversionModule", 5, roles.helpers.conversionModule);
                set("listModule", 7, roles.helpers.listModule);
                Expr genericArgument;
                for (const auto& [key, value] : annotation.namedArgs) {
                    if (key == "deferGeneric") {
                        genericArgument = value;
                        break;
                    }
                }
                if (!genericArgument && annotation.args.size() > 6) genericArgument = annotation.args[6];
                if (ExprBoolLiteral* b = as<ExprBoolLiteral>(genericArgument)) roles.deferGeneric = b->value;
            }
        }
    }
    auto roleSet = [&](const char* name) {
        const OrderedSet<std::string>* set = byRole.get(name);
        return set ? *set : OrderedSet<std::string>{};
    };
    roles.all = roleSet("all");
    roles.azon = roleSet("azon");
    roles.name = roleSet("name");
    roles.ignore = roleSet("ignore");
    roles.required = roleSet("required");
    return roles;
}

}  // namespace

Result derive(Arena& arena, const Program& program) {
    Roles roles = discoverRoles(program);
    OrderedSet<std::string> roots = roles.roots();
    if (roots.empty()) return Result{program, {}};
    std::vector<std::string> errors;
    Deriver deriver{arena, program, errors};
    std::vector<TopLevel> generated;
    std::vector<const TopLevelPack*> packs;
    for (const TopLevel& item : program.items) {
        if (TopLevelPack* pack = as<TopLevelPack>(item)) packs.push_back(pack);
    }
    OrderedSet<std::string> serializablePacks;
    for (const TopLevelPack* pack : packs) {
        if (deriver.appliedAny(pack->name, roots)) serializablePacks.add(pack->name);
    }
    for (const TopLevelPack* packPtr : packs) {
        const TopLevelPack& pack = *packPtr;
        size_t errorCountBeforePack = errors.size();
        std::optional<DecoratorMetadata::Applied> serializable = deriver.appliedAny(pack.name, roles.all);
        std::optional<DecoratorMetadata::Applied> azonOnly = deriver.appliedAny(pack.name, roles.azon);
        std::optional<DecoratorMetadata::Applied> root = serializable ? serializable : azonOnly;
        if (!root) continue;
        if (!pack.typeParams.empty()) {
            if (!roles.deferGeneric) {
                errors.push_back("line " + str(pack.line) + ": generated serialization for generic pack '" + pack.name +
                                 "' requires concrete type arguments");
            }
            continue;
        }
        bool ignoreUnknown = deriver.boolMetadata(*root, "ignoreUnknownFields", pack.line).value_or(false);
        bool encodeDefaults = deriver.boolMetadata(*root, "encodeDefaults", pack.line).value_or(true);
        std::vector<FieldPlan> fields;
        for (const PackField& field : pack.fields) fields.push_back(deriver.fieldPlan(pack, field, roles));

        // `groupBy { wireName }.entries.firstOrNull { size > 1 }`: the first wire name, by first use, used twice.
        std::optional<std::string> duplicate;
        {
            OrderedMap<std::string, int> counts;
            for (const FieldPlan& f : fields) {
                if (!f.ignored) counts.getOrPut(f.wireName, 0)++;
            }
            for (const auto& [name, count] : counts.entries) {
                if (count > 1) {
                    duplicate = name;
                    break;
                }
            }
        }
        if (duplicate) {
            errors.push_back("line " + str(pack.line) + ": serialized fields of '" + pack.name + "' share wire name '" + *duplicate + "'");
            continue;
        }
        for (const FieldPlan& f : fields) {
            if (f.ignored && !f.field->default_) {
                errors.push_back("line " + str(pack.line) + ": ignored field '" + pack.name + "::" + f.field->name +
                                 "' requires a default value");
            }
        }
        for (const FieldPlan& f : fields) {
            if (f.ignored && f.required) {
                errors.push_back("line " + str(pack.line) + ": field '" + pack.name + "::" + f.field->name +
                                 "' cannot be both SerialIgnore and SerialRequired");
            }
        }
        for (const FieldPlan& f : fields) {
            if (!f.ignored) deriver.validateSupportedType(f.field->type, pack.name, f.field->name, serializablePacks, pack.line);
        }
        if (errors.size() != errorCountBeforePack) continue;

        OrderedSet<std::string> existing;
        for (const TopLevel& item : program.items) {
            TopLevelImpl* impl = as<TopLevelImpl>(item);
            if (impl == nullptr || impl->typeName != pack.name) continue;
            for (const FuncDecl& m : impl->methods) existing.add(m.name);
        }
        std::optional<std::string> collision;
        for (const char* name : {"toSerialValue", "fromSerialValue", "toAzon", "fromAzon"}) {
            if (existing.contains(name)) {
                collision = name;
                break;
            }
        }
        if (collision) {
            errors.push_back("line " + str(pack.line) + ": generated serializer for '" + pack.name +
                             "' conflicts with existing method '" + *collision + "'");
            continue;
        }
        std::string source = generateImpl(pack, fields, ignoreUnknown, encodeDefaults, roles.helpers);
        try {
            Parser parser(arena, Lexer(source).tokenize(), nullptr, nullptr, true);
            Program parsed = parser.parse();
            generated.insert(generated.end(), parsed.items.begin(), parsed.items.end());
        } catch (const CompileFailure& e) {
            if (e.kind != FailureKind::IllegalState) throw;
            errors.push_back("line " + str(pack.line) + ": failed to derive serializer for '" + pack.name + "': " + e.message);
        }
    }
    if (!errors.empty()) return Result{program, distinct(errors)};
    // The generated bodies build their collections with `List`, so the module declaring it travels with them.
    std::vector<TopLevel> dependencyImports;
    if (!generated.empty()) {
        std::vector<std::string> modules;
        for (const std::string& m : {roles.helpers.providerModule, roles.helpers.conversionModule, roles.helpers.listModule}) {
            if (!m.empty()) modules.push_back(m);
        }
        for (const std::string& module : distinct(modules)) {
            ImportSpec spec;
            spec.path = module;
            spec.selector.kind = SelectorKind::Path;
            TopLevelUseImport use;
            use.specs = {std::move(spec)};
            use.line = 0;
            dependencyImports.push_back(make(arena, std::move(use)));
        }
    }
    Program out = program;
    out.items.insert(out.items.end(), dependencyImports.begin(), dependencyImports.end());
    out.items.insert(out.items.end(), generated.begin(), generated.end());
    return Result{std::move(out), {}};
}

}  // namespace SerializationDeriver
}  // namespace azora
