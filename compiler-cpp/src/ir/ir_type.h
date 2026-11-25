// The IR's resolved types: port of `sealed class IrType` and its companion
// from ir/IrNode.kt.
//
// As with the AST, IrType is a handle holding a std::variant of pointers to
// one struct per kind (index 0 = none, Kotlin null). The kinds Kotlin writes as
// `object` (String, Bool, …) are process-wide singletons; the rest live in an
// arena. Equality is Kotlin's: data classes compare their fields, and a Named
// type compares its name and its concrete const arguments only (irEquals).
#pragma once

#include "base/arena.h"
#include "frontend/ast.h"

#include <cstdint>
#include <optional>
#include <string>
#include <variant>
#include <vector>

namespace azora {

struct IrType;

struct IrTypeInteger {
    int32_t bits = 32;
    bool isSigned = true;
};
struct IrTypeString {};
struct IrTypeBool {};
struct IrTypeUnit {};
struct IrTypeNothing {};
struct IrTypeChar {};
struct IrTypeISize {};
struct IrTypeUSize {};
struct IrTypeDouble {};
struct IrTypeHalf {};
struct IrTypeFloat {};
struct IrTypeQuad {};
struct IrTypeArray;
struct IrTypeMap;
struct IrTypeSet;
struct IrTypeFunction;
struct IrTypeTask;
struct IrTypeTuple;
struct IrTypeVariant;
struct IrTypeNullable;
struct IrTypePointer;
struct IrTypeNamed;
struct IrTypeAny {};

enum class IrTypeKind : uint8_t {
    None,
    Integer,
    String,
    Bool,
    Unit,
    Nothing,
    Char,
    ISize,
    USize,
    Double,
    Half,
    Float,
    Quad,
    Array,
    Map,
    Set,
    Function,
    Task,
    Tuple,
    Variant,
    Nullable,
    Pointer,
    Named,
    Any,
};

struct IrType {
    std::variant<std::monostate, IrTypeInteger*, IrTypeString*, IrTypeBool*, IrTypeUnit*, IrTypeNothing*, IrTypeChar*,
                 IrTypeISize*, IrTypeUSize*, IrTypeDouble*, IrTypeHalf*, IrTypeFloat*, IrTypeQuad*, IrTypeArray*,
                 IrTypeMap*, IrTypeSet*, IrTypeFunction*, IrTypeTask*, IrTypeTuple*, IrTypeVariant*, IrTypeNullable*,
                 IrTypePointer*, IrTypeNamed*, IrTypeAny*>
        v;

    IrType() = default;
    IrType(std::nullptr_t) {}
    template <class T>
    IrType(T* node) {
        if (node) v = node;
    }
    explicit operator bool() const { return v.index() != 0; }
    IrTypeKind kind() const { return (IrTypeKind)v.index(); }
};

struct IrTypeArray {
    IrType element;
    /** The compile-time element count when known. */
    std::optional<int64_t> size;
};
struct IrTypeMap {
    IrType key;
    IrType value;
};
struct IrTypeSet {
    IrType element;
};
/** First-class callable type with ordinary and contextual parameters. */
struct IrTypeFunction {
    std::vector<IrType> params;
    IrType ret;
    bool variadic = false;
    std::vector<IrType> receivers;
    CallableKind kind = CallableKind::FUNC;
    bool isEscaping = false;
    bool isInline = false;
    bool isForeign = false;
};
struct IrTypeTask {
    IrType result;
};
struct IrTypeTuple {
    std::vector<IrType> elements;
};
struct IrTypeVariant {
    std::vector<IrType> elements;
};
struct IrTypeNullable {
    IrType inner;
};
struct IrTypePointer {
    IrType inner;
    bool mutable_ = false;
};
/** A user-declared type, with the type arguments it was written with and the concrete const ones. */
struct IrTypeNamed {
    std::string name;
    std::vector<IrType> args;
    /** Parallel to args: the value of a concrete const argument, none for a type or unbound one. */
    std::vector<std::optional<int64_t>> constArgs;
};

/** Kotlin `is`/`as?`/`as` for IrType, as for AST handles. */
template <class K>
bool irIs(const IrType& t) {
    return std::holds_alternative<K*>(t.v);
}
template <class K>
K* irAs(const IrType& t) {
    K* const* node = std::get_if<K*>(&t.v);
    return node ? *node : nullptr;
}
template <class K>
K& irCast(const IrType& t) {
    K* const* node = std::get_if<K*>(&t.v);
    if (node == nullptr) failCast("IrType");
    return **node;
}

namespace IrTypes {
// The `object` kinds.
IrType string();
IrType boolean();
IrType unit();
IrType nothing();
IrType charType();
IrType isize();
IrType usize();
IrType doubleType();
IrType half();
IrType floatType();
IrType quad();
IrType any();

// The named widths (companion `Int`, `UInt`, …).
IrType intType();
IrType uintType();
IrType byteType();
IrType ubyteType();
IrType shortType();
IrType ushortType();
IrType longType();
IrType ulongType();
IrType cent();
IrType ucent();

IrType integer(Arena& arena, int32_t bits, bool isSigned);

/** The name a width has (`Byte`), or none (`Int<7>`). */
const char* namedWidth(int32_t bits, bool isSigned);

/** Type aliases registered by the compiler (Kotlin `IrType.aliases`; cleared per compilation). */
OrderedMap<std::string, TypeRef>& aliases();

/** Kotlin `IrType.fromName`: fails with IllegalState "Unknown type: name". */
IrType fromName(const std::string& name);
/** Whether [name] is a built-in primitive resolvable via fromName. */
bool isPrimitiveName(const std::string& name);

bool isInteger(const IrType& type);
bool isNumeric(const IrType& type);
bool isFloat(const IrType& type);

/** The width an integer / real literal is read at where nothing says which. */
IrType defaultInt();
IrType defaultFloat();

/** Kotlin `IrType.resolve(ref, typeParams)`. */
IrType resolve(Arena& arena, const TypeRef& ref, const OrderedSet<std::string>& typeParams = {});
}  // namespace IrTypes

/** Kotlin `IrType.toString()`. */
std::string toString(const IrType& type);
/** Kotlin `IrType.shown()`: the type as a diagnostic names it, type arguments included. */
std::string shown(const IrType& type);
/** Kotlin `a == b` for IrTypes. */
bool irEquals(const IrType& a, const IrType& b);
/** A string equal exactly when irEquals is: for sets and map keys. */
std::string irKey(const IrType& type);
/** Kotlin `IrType.isClosure`: a callable that owns an environment. */
bool isClosure(const IrType& type);

}  // namespace azora
