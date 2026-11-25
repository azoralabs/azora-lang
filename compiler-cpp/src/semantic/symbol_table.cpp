// Port of semantic/SymbolTable.kt (the parts stage 4 uses).
#include "semantic/symbol_table.h"

#include "base/failure.h"
#include "base/text.h"

#include <deque>

namespace azora {

namespace {

const char* const ORDER = "Order";
const char* const PARTIAL_ORDER = "PartialOrder";

template <class V>
const V* lookupScoped(const OrderedMap<std::string, V>& map, const std::string& name) {
    if (const V* found = map.get(name)) return found;
    if (name.find("__") != std::string::npos) return map.get(substringAfterLast(name, "__"));
    return nullptr;
}

bool sameTypeArgs(const std::vector<TypeRef>& a, const std::vector<TypeRef>& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); i++) {
        if (!kotlinEquals(a[i], b[i])) return false;
    }
    return true;
}

}  // namespace

void SymbolTable::defineStruct(StructType structType) {
    if (structs.contains(structType.name)) fail("Struct '" + structType.name + "' already defined");
    std::string name = structType.name;
    structs.set(name, std::move(structType));
}

const StructType* SymbolTable::lookupStruct(const std::string& name) const { return lookupScoped(structs, name); }

void SymbolTable::defineEnum(const std::string& name, std::vector<std::string> variants) {
    if (enums.contains(name)) fail("Enum '" + name + "' already defined");
    enums.set(name, std::move(variants));
}

const std::vector<std::string>* SymbolTable::lookupEnum(const std::string& name) const { return lookupScoped(enums, name); }

void SymbolTable::defineFail(const std::string& name, std::vector<std::string> variants) {
    if (fails.contains(name)) fail("Error-set '" + name + "' already defined");
    fails.set(name, std::move(variants));
}

const std::vector<std::string>* SymbolTable::lookupFail(const std::string& name) const { return lookupScoped(fails, name); }

void SymbolTable::defineSlot(const std::string& name, std::vector<std::pair<std::string, std::vector<IrType>>> variants) {
    slots.set(name, std::move(variants));
}

const std::vector<std::pair<std::string, std::vector<IrType>>>* SymbolTable::lookupSlot(const std::string& name) const {
    return lookupScoped(slots, name);
}

std::string SymbolTable::canonicalTypeName(const std::string& name) const {
    if (isDeclaredType(name)) return name;
    std::string bare = substringAfterLast(name, "__");
    return (bare != name && isDeclaredType(bare)) ? bare : name;
}

bool SymbolTable::isDeclaredType(const std::string& name) const {
    return structs.contains(name) || enums.contains(name) || slots.contains(name) || fails.contains(name);
}

void SymbolTable::defineSpec(const std::string& name, SpecSymbol symbol) {
    symbol.isDecorator = false;
    specs.set(name, std::move(symbol));
}

void SymbolTable::defineDecorator(const std::string& name, const OrderedSet<DecoTarget>& targets,
                                  const std::vector<DecoratorBinding>& bindings, bool isBridge) {
    SpecSymbol symbol;
    symbol.isDecorator = true;
    symbol.decoratorTargets = targets;
    symbol.decoratorBindings = bindings;
    symbol.isBridge = isBridge;
    specs.set(name, std::move(symbol));
}

const SpecSymbol* SymbolTable::lookupSpec(const std::string& name) const { return specs.get(name); }

std::vector<std::string> SymbolTable::specAndAncestors(const std::string& specName) const {
    std::vector<std::string> order;
    OrderedSet<std::string> seen;
    std::deque<std::string> queue{specName};
    while (!queue.empty()) {
        std::string name = queue.front();
        queue.pop_front();
        if (!seen.add(name)) continue;
        order.push_back(name);
        if (const SpecSymbol* spec = specs.get(name)) {
            for (const std::string& parent : spec->parentNames) queue.push_back(parent);
        }
    }
    return order;
}

bool SymbolTable::defineConformance(const TraitConformance& conformance) {
    for (const TraitConformance& c : conformances) {
        if (c.typeName == conformance.typeName && c.contractName == conformance.contractName &&
            sameTypeArgs(c.typeArgs, conformance.typeArgs)) {
            return false;
        }
    }
    conformances.push_back(conformance);
    // A total order is a partial one: an `Order` type is `PartialOrder` without writing it.
    if (!conformance.isDecorator && substringAfterLast(conformance.contractName, "__") == ORDER) {
        std::string partial = removeSuffix(conformance.contractName, ORDER) + PARTIAL_ORDER;
        bool present = false;
        for (const TraitConformance& c : conformances) {
            if (c.typeName == conformance.typeName && substringAfterLast(c.contractName, "__") == PARTIAL_ORDER) present = true;
        }
        if (!present) {
            TraitConformance implied;
            implied.typeName = conformance.typeName;
            implied.contractName = partial;
            implied.implTypeParams = conformance.implTypeParams;
            conformances.push_back(std::move(implied));
        }
    }
    return true;
}

bool SymbolTable::implements(const std::string& typeName, const std::string& contractName,
                             const std::vector<TypeRef>& typeArgs) const {
    for (const TraitConformance& c : conformances) {
        if (c.typeName == typeName && c.contractName == contractName && sameTypeArgs(c.typeArgs, typeArgs)) return true;
    }
    return false;
}

bool SymbolTable::conformsTo(const std::string& typeName, const std::string& contractName) const {
    for (const TraitConformance& c : conformances) {
        if (c.typeName == typeName && c.contractName == contractName) return true;
    }
    return false;
}

bool SymbolTable::isClonable(const IrType& type) const {
    switch (type.kind()) {
        case IrTypeKind::Array: return isClonable(irCast<IrTypeArray>(type).element);
        case IrTypeKind::Set: return isClonable(irCast<IrTypeSet>(type).element);
        case IrTypeKind::Map: return isClonable(irCast<IrTypeMap>(type).key) && isClonable(irCast<IrTypeMap>(type).value);
        case IrTypeKind::Tuple:
            for (const IrType& e : irCast<IrTypeTuple>(type).elements) {
                if (!isClonable(e)) return false;
            }
            return true;
        case IrTypeKind::Nullable: return isClonable(irCast<IrTypeNullable>(type).inner);
        case IrTypeKind::Named: {
            const std::string& name = irCast<IrTypeNamed>(type).name;
            return conformsTo(name, "Clone") || (!lookupStruct(name) && !lookupEnum(name) && !lookupSlot(name));
        }
        default: return true;
    }
}

}  // namespace azora
