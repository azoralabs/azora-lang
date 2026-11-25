// Port of the IrType part of ir/IrNode.kt.
#include "ir/ir_type.h"

#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"

#include <functional>

namespace azora {

namespace IrTypes {

namespace {

template <class T>
IrType singleton() {
    static T instance;
    return &instance;
}

IrType width(int32_t bits, bool isSigned) {
    // One process-wide node per named width, as Kotlin's companion values.
    static IrTypeInteger widths[] = {{8, true}, {8, false}, {16, true}, {16, false}, {32, true},
                                     {32, false}, {64, true}, {64, false}, {128, true}, {128, false}};
    for (IrTypeInteger& w : widths) {
        if (w.bits == bits && w.isSigned == isSigned) return &w;
    }
    AZORA_UNREACHABLE();
}

}  // namespace

IrType string() { return singleton<IrTypeString>(); }
IrType boolean() { return singleton<IrTypeBool>(); }
IrType unit() { return singleton<IrTypeUnit>(); }
IrType nothing() { return singleton<IrTypeNothing>(); }
IrType charType() { return singleton<IrTypeChar>(); }
IrType isize() { return singleton<IrTypeISize>(); }
IrType usize() { return singleton<IrTypeUSize>(); }
IrType doubleType() { return singleton<IrTypeDouble>(); }
IrType half() { return singleton<IrTypeHalf>(); }
IrType floatType() { return singleton<IrTypeFloat>(); }
IrType quad() { return singleton<IrTypeQuad>(); }
IrType any() { return singleton<IrTypeAny>(); }

IrType intType() { return width(32, true); }
IrType uintType() { return width(32, false); }
IrType byteType() { return width(8, true); }
IrType ubyteType() { return width(8, false); }
IrType shortType() { return width(16, true); }
IrType ushortType() { return width(16, false); }
IrType longType() { return width(64, true); }
IrType ulongType() { return width(64, false); }
IrType cent() { return width(128, true); }
IrType ucent() { return width(128, false); }

IrType integer(Arena& arena, int32_t bits, bool isSigned) {
    if (namedWidth(bits, isSigned)) return width(bits, isSigned);
    return make(arena, IrTypeInteger{bits, isSigned});
}

const char* namedWidth(int32_t bits, bool isSigned) {
    switch (bits) {
        case 8: return isSigned ? "Byte" : "UByte";
        case 16: return isSigned ? "Short" : "UShort";
        case 32: return isSigned ? "Int" : "UInt";
        case 64: return isSigned ? "Long" : "ULong";
        case 128: return isSigned ? "Cent" : "UCent";
        default: return nullptr;
    }
}

OrderedMap<std::string, TypeRef>& aliases() {
    static OrderedMap<std::string, TypeRef>* map = new OrderedMap<std::string, TypeRef>();
    return *map;
}

IrType fromName(const std::string& name) {
    // The compiler's own primitives first.
    if (name == "__int") return intType();
    if (name == "__uint") return uintType();
    if (name == "__float") return floatType();
    if (name == "Int") return intType();
    if (name == "UInt") return uintType();
    if (name == "Double") return doubleType();
    if (name == "String") return string();
    if (name == "Bool") return boolean();
    if (name == "Unit") return unit();
    if (name == "Nothing") return nothing();
    if (name == "Char") return charType();
    if (name == "Byte") return byteType();
    if (name == "UByte") return ubyteType();
    if (name == "Short") return shortType();
    if (name == "UShort") return ushortType();
    if (name == "Long") return longType();
    if (name == "ULong") return ulongType();
    if (name == "ISize") return isize();
    if (name == "USize") return usize();
    if (name == "Cent") return cent();
    if (name == "UCent") return ucent();
    if (name == "Half") return half();
    if (name == "Float") return floatType();
    if (name == "Quad") return quad();
    if (name == "Any") return any();
    fail("Unknown type: " + name);
}

bool isPrimitiveName(const std::string& name) {
    try {
        fromName(name);
        return true;
    } catch (const CompileFailure&) {
        return false;
    }
}

bool isInteger(const IrType& type) { return irIs<IrTypeInteger>(type) || irIs<IrTypeISize>(type) || irIs<IrTypeUSize>(type); }
bool isFloat(const IrType& type) {
    return irIs<IrTypeHalf>(type) || irIs<IrTypeFloat>(type) || irIs<IrTypeDouble>(type) || irIs<IrTypeQuad>(type);
}
bool isNumeric(const IrType& type) { return isInteger(type) || isFloat(type); }

IrType defaultInt() { return fromName(Literals::DEFAULT_INT); }
IrType defaultFloat() { return fromName(Literals::DEFAULT_FLOAT); }

IrType resolve(Arena& arena, const TypeRef& ref, const OrderedSet<std::string>& typeParams) {
    auto each = [&](const std::vector<TypeRef>& refs) {
        std::vector<IrType> out;
        out.reserve(refs.size());
        for (const TypeRef& r : refs) out.push_back(resolve(arena, r, typeParams));
        return out;
    };
    switch (ref.kind()) {
        case TypeRefKind::Named: {
            const TypeRefNamed& named = cast<TypeRefNamed>(ref);
            if (TypeFunctionCall::isCall(named)) return any();
            if (typeParams.contains(named.name)) return any();
            // Skip self-referential aliases (`typealias Array<T> = Array<T, *>`).
            if (const TypeRef* alias = aliases().get(named.name)) {
                TypeRefNamed* target = as<TypeRefNamed>(*alias);
                if (!target || target->name != named.name) return resolve(arena, *alias, typeParams);
            }
            // `Int<7>` / `UInt<10000>` - a width stated as a number.
            if ((named.name == "Int" || named.name == "UInt") && named.args.size() == 1) {
                TypeRefConst* c = as<TypeRefConst>(named.args[0]);
                int32_t bits = c ? (int32_t)c->value : 0;
                if (!c || bits <= 0) return integer(arena, 32, named.name == "Int");
                return integer(arena, bits, named.name == "Int");
            }
            if (named.name == "Array") {
                if (named.args.size() == 2 && is<TypeRefConst>(named.args[1])) {
                    return make(arena, IrTypeArray{resolve(arena, named.args[0], typeParams), cast<TypeRefConst>(named.args[1]).value});
                }
                TypeRefNamed* second = named.args.size() == 2 ? as<TypeRefNamed>(named.args[1]) : nullptr;
                if (second && (second->name == "*" || typeParams.contains(second->name))) {
                    return make(arena, IrTypeArray{resolve(arena, named.args[0], typeParams), std::nullopt});
                }
                if (named.args.size() != 1) {
                    fail("Array expects one type argument (Array<T>) or a const size (Array<T, N>), got " +
                         str((int64_t)named.args.size()));
                }
                return make(arena, IrTypeArray{resolve(arena, named.args[0], typeParams), std::nullopt});
            }
            if (named.name == "Var" && named.args.size() >= 2) return make(arena, IrTypeVariant{each(named.args)});
            // `Tuple<A, B>` is `(A, B)`; a variadic `Tuple<...T>` names the whole family.
            if (named.name == "Tuple" && !named.args.empty()) {
                bool anyVariadic = false;
                for (const TypeRef& a : named.args) {
                    TypeRefNamed* n = as<TypeRefNamed>(a);
                    if (n && n->variadic) anyVariadic = true;
                }
                TypeRefNamed* only = named.args.size() == 1 ? as<TypeRefNamed>(named.args[0]) : nullptr;
                bool singleParam = only && typeParams.contains(only->name);
                if (!anyVariadic && !singleParam) {
                    if (named.args.size() < 2) {
                        std::vector<std::string> shownArgs;
                        for (const TypeRef& a : named.args) shownArgs.push_back(toString(a));
                        fail("a tuple has at least two elements, so 'Tuple<" + join(shownArgs, ", ") +
                             ">' is not a tuple type; write the element type itself");
                    }
                    return make(arena, IrTypeTuple{each(named.args)});
                }
            }
            if (named.args.empty() && isPrimitiveName(named.name)) return fromName(named.name);
            // Keep type and const arguments: a const one chooses the layout.
            IrTypeNamed out;
            out.name = named.name;
            out.args = each(named.args);
            for (const TypeRef& a : named.args) {
                TypeRefConst* c = as<TypeRefConst>(a);
                out.constArgs.push_back(c ? std::optional<int64_t>(c->value) : std::nullopt);
            }
            return make(arena, std::move(out));
        }
        case TypeRefKind::Array:
            return make(arena, IrTypeArray{resolve(arena, cast<TypeRefArray>(ref).element, typeParams), std::nullopt});
        case TypeRefKind::Map:
            return make(arena, IrTypeMap{resolve(arena, cast<TypeRefMap>(ref).key, typeParams),
                                         resolve(arena, cast<TypeRefMap>(ref).value, typeParams)});
        case TypeRefKind::Set: return make(arena, IrTypeSet{resolve(arena, cast<TypeRefSet>(ref).element, typeParams)});
        case TypeRefKind::Function: {
            const TypeRefFunction& f = cast<TypeRefFunction>(ref);
            IrTypeFunction out;
            out.params = each(f.params);
            out.ret = resolve(arena, f.ret, typeParams);
            out.receivers = each(f.receivers);
            out.kind = f.kind;
            out.isEscaping = f.isEscaping;
            out.isInline = f.isInline;
            out.isForeign = f.isForeign;
            return make(arena, std::move(out));
        }
        case TypeRefKind::Tuple: return make(arena, IrTypeTuple{each(cast<TypeRefTuple>(ref).elements)});
        case TypeRefKind::Nullable: return make(arena, IrTypeNullable{resolve(arena, cast<TypeRefNullable>(ref).inner, typeParams)});
        case TypeRefKind::Pointer:
            return make(arena, IrTypePointer{resolve(arena, cast<TypeRefPointer>(ref).inner, typeParams), cast<TypeRefPointer>(ref).mutable_});
        // Checked references are erased after ownership analysis.
        case TypeRefKind::Reference: return resolve(arena, cast<TypeRefReference>(ref).inner, typeParams);
        // `T!ErrSet` is a T at runtime.
        case TypeRefKind::Failable: return resolve(arena, cast<TypeRefFailable>(ref).ok, typeParams);
        // A const-generic value standing alone is its Int value type.
        case TypeRefKind::Const: return intType();
        case TypeRefKind::None: break;
    }
    failArgument("Required value was null.");
}

}  // namespace IrTypes

namespace {

std::string joined(const std::vector<IrType>& types, const char* separator, const std::function<std::string(const IrType&)>& show) {
    std::string out;
    for (size_t i = 0; i < types.size(); i++) {
        if (i > 0) out += separator;
        out += show(types[i]);
    }
    return out;
}

std::string render(const IrType& type, const std::function<std::string(const IrType&)>& show, bool shownMode) {
    switch (type.kind()) {
        case IrTypeKind::None: return "null";
        case IrTypeKind::Integer: {
            const IrTypeInteger& i = irCast<IrTypeInteger>(type);
            if (const char* name = IrTypes::namedWidth(i.bits, i.isSigned)) return name;
            return std::string(i.isSigned ? "Int" : "UInt") + "<" + str(i.bits) + ">";
        }
        case IrTypeKind::String: return "String";
        case IrTypeKind::Bool: return "Bool";
        case IrTypeKind::Unit: return "Unit";
        case IrTypeKind::Nothing: return "Nothing";
        case IrTypeKind::Char: return "Char";
        case IrTypeKind::ISize: return "ISize";
        case IrTypeKind::USize: return "USize";
        case IrTypeKind::Double: return "Double";
        case IrTypeKind::Half: return "Half";
        case IrTypeKind::Float: return "Float";
        case IrTypeKind::Quad: return "Quad";
        case IrTypeKind::Any: return "Any";
        case IrTypeKind::Array: {
            const IrTypeArray& a = irCast<IrTypeArray>(type);
            if (a.size) return "Array<" + show(a.element) + ", " + str(*a.size) + ">";
            return "Array<" + show(a.element) + ">";
        }
        case IrTypeKind::Map: return "map[" + show(irCast<IrTypeMap>(type).key) + ", " + show(irCast<IrTypeMap>(type).value) + "]";
        case IrTypeKind::Set: return "set[" + show(irCast<IrTypeSet>(type).element) + "]";
        case IrTypeKind::Function: {
            const IrTypeFunction& f = irCast<IrTypeFunction>(type);
            std::string context;
            if (f.receivers.size() == 1) context = show(f.receivers[0]) + ".";
            else if (f.receivers.size() > 1) context = "(" + joined(f.receivers, ", ", show) + ").";
            std::string arguments = "(" + joined(f.params, ", ", show) + ")";
            std::string prefix = std::string(f.isForeign ? "foreign " : "") +
                                 (f.kind == CallableKind::FUNC ? std::string() : std::string(surfaceName(f.kind)) + " ");
            return prefix + context + arguments + " -> " + show(f.ret);
        }
        case IrTypeKind::Task: return "Task<" + show(irCast<IrTypeTask>(type).result) + ">";
        case IrTypeKind::Tuple: return "(" + joined(irCast<IrTypeTuple>(type).elements, ", ", show) + ")";
        case IrTypeKind::Variant: return "Var<" + joined(irCast<IrTypeVariant>(type).elements, ", ", show) + ">";
        case IrTypeKind::Nullable: return show(irCast<IrTypeNullable>(type).inner) + "?";
        case IrTypeKind::Pointer: {
            const IrTypePointer& p = irCast<IrTypePointer>(type);
            return show(p.inner) + (p.mutable_ ? "^" : "*");
        }
        case IrTypeKind::Named: {
            const IrTypeNamed& n = irCast<IrTypeNamed>(type);
            if (shownMode) {
                // `displayed()`: the arguments become part of the name.
                if (n.args.empty()) return n.name;
                std::string out = n.name + "<";
                for (size_t i = 0; i < n.args.size(); i++) {
                    if (i > 0) out += ", ";
                    if (i < n.constArgs.size() && n.constArgs[i]) out += str(*n.constArgs[i]);
                    else out += shown(n.args[i]);
                }
                return out + ">";
            }
            bool anyConst = false;
            for (const std::optional<int64_t>& c : n.constArgs) anyConst = anyConst || c.has_value();
            if (!anyConst) return n.name;
            std::string out = n.name + "<";
            for (size_t i = 0; i < n.constArgs.size(); i++) {
                if (i > 0) out += ", ";
                out += n.constArgs[i] ? str(*n.constArgs[i]) : std::string("_");
            }
            return out + ">";
        }
    }
    return "";
}

}  // namespace

std::string toString(const IrType& type) {
    return render(type, [](const IrType& t) { return toString(t); }, false);
}

std::string shown(const IrType& type) {
    return render(type, [](const IrType& t) { return shown(t); }, true);
}

bool irEquals(const IrType& a, const IrType& b) {
    if (a.kind() != b.kind()) return false;
    auto all = [](const std::vector<IrType>& x, const std::vector<IrType>& y) {
        if (x.size() != y.size()) return false;
        for (size_t i = 0; i < x.size(); i++) {
            if (!irEquals(x[i], y[i])) return false;
        }
        return true;
    };
    switch (a.kind()) {
        case IrTypeKind::Integer:
            return irCast<IrTypeInteger>(a).bits == irCast<IrTypeInteger>(b).bits &&
                   irCast<IrTypeInteger>(a).isSigned == irCast<IrTypeInteger>(b).isSigned;
        case IrTypeKind::Array:
            return irEquals(irCast<IrTypeArray>(a).element, irCast<IrTypeArray>(b).element) &&
                   irCast<IrTypeArray>(a).size == irCast<IrTypeArray>(b).size;
        case IrTypeKind::Map:
            return irEquals(irCast<IrTypeMap>(a).key, irCast<IrTypeMap>(b).key) &&
                   irEquals(irCast<IrTypeMap>(a).value, irCast<IrTypeMap>(b).value);
        case IrTypeKind::Set: return irEquals(irCast<IrTypeSet>(a).element, irCast<IrTypeSet>(b).element);
        case IrTypeKind::Function: {
            const IrTypeFunction& x = irCast<IrTypeFunction>(a);
            const IrTypeFunction& y = irCast<IrTypeFunction>(b);
            return all(x.params, y.params) && irEquals(x.ret, y.ret) && x.variadic == y.variadic && all(x.receivers, y.receivers) &&
                   x.kind == y.kind && x.isEscaping == y.isEscaping && x.isInline == y.isInline && x.isForeign == y.isForeign;
        }
        case IrTypeKind::Task: return irEquals(irCast<IrTypeTask>(a).result, irCast<IrTypeTask>(b).result);
        case IrTypeKind::Tuple: return all(irCast<IrTypeTuple>(a).elements, irCast<IrTypeTuple>(b).elements);
        case IrTypeKind::Variant: return all(irCast<IrTypeVariant>(a).elements, irCast<IrTypeVariant>(b).elements);
        case IrTypeKind::Nullable: return irEquals(irCast<IrTypeNullable>(a).inner, irCast<IrTypeNullable>(b).inner);
        case IrTypeKind::Pointer:
            return irEquals(irCast<IrTypePointer>(a).inner, irCast<IrTypePointer>(b).inner) &&
                   irCast<IrTypePointer>(a).mutable_ == irCast<IrTypePointer>(b).mutable_;
        // Nominal in its type arguments; the concrete const arguments are identity.
        case IrTypeKind::Named: {
            const IrTypeNamed& x = irCast<IrTypeNamed>(a);
            const IrTypeNamed& y = irCast<IrTypeNamed>(b);
            if (x.name != y.name) return false;
            std::vector<std::pair<size_t, int64_t>> xi;
            std::vector<std::pair<size_t, int64_t>> yi;
            for (size_t i = 0; i < x.constArgs.size(); i++) {
                if (x.constArgs[i]) xi.push_back({i, *x.constArgs[i]});
            }
            for (size_t i = 0; i < y.constArgs.size(); i++) {
                if (y.constArgs[i]) yi.push_back({i, *y.constArgs[i]});
            }
            return xi == yi;
        }
        default:
            return true;
    }
}

std::string irKey(const IrType& type) {
    auto list = [](const std::vector<IrType>& types) {
        std::string out = "[";
        for (const IrType& t : types) out += irKey(t) + ",";
        return out + "]";
    };
    switch (type.kind()) {
        case IrTypeKind::Integer: return "I" + str(irCast<IrTypeInteger>(type).bits) + (irCast<IrTypeInteger>(type).isSigned ? "s" : "u");
        case IrTypeKind::Array: {
            const IrTypeArray& a = irCast<IrTypeArray>(type);
            return "A(" + irKey(a.element) + "," + (a.size ? str(*a.size) : std::string("-")) + ")";
        }
        case IrTypeKind::Map: return "M(" + irKey(irCast<IrTypeMap>(type).key) + "," + irKey(irCast<IrTypeMap>(type).value) + ")";
        case IrTypeKind::Set: return "S(" + irKey(irCast<IrTypeSet>(type).element) + ")";
        case IrTypeKind::Function: {
            const IrTypeFunction& f = irCast<IrTypeFunction>(type);
            return "F(" + list(f.params) + irKey(f.ret) + (f.variadic ? "v" : "") + list(f.receivers) + str((int)f.kind) +
                   (f.isEscaping ? "e" : "") + (f.isInline ? "i" : "") + (f.isForeign ? "f" : "") + ")";
        }
        case IrTypeKind::Task: return "T(" + irKey(irCast<IrTypeTask>(type).result) + ")";
        case IrTypeKind::Tuple: return "U" + list(irCast<IrTypeTuple>(type).elements);
        case IrTypeKind::Variant: return "V" + list(irCast<IrTypeVariant>(type).elements);
        case IrTypeKind::Nullable: return "Q(" + irKey(irCast<IrTypeNullable>(type).inner) + ")";
        case IrTypeKind::Pointer:
            return "P(" + irKey(irCast<IrTypePointer>(type).inner) + (irCast<IrTypePointer>(type).mutable_ ? "^" : "*") + ")";
        case IrTypeKind::Named: {
            const IrTypeNamed& n = irCast<IrTypeNamed>(type);
            std::string out = "N(" + n.name;
            for (size_t i = 0; i < n.constArgs.size(); i++) {
                if (n.constArgs[i]) out += "|" + str((int64_t)i) + ":" + str(*n.constArgs[i]);
            }
            return out + ")";
        }
        default: return toString(type);
    }
}

bool isClosure(const IrType& type) {
    IrTypeFunction* f = irAs<IrTypeFunction>(type);
    return f && !f->isForeign;
}

}  // namespace azora
