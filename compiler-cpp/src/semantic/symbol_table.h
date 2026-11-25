// Port of semantic/SymbolTable.kt - in part. The table grows with the stages
// that use it: stage 4 needs the type, spec, conformance and alias tables and
// the queries ConstraintEvaluator and Bounds make of them; functions, scopes,
// methods, lambdas and the recorded facts arrive with stage 5.
#pragma once

#include "base/ordered.h"
#include "frontend/ast.h"
#include "ir/ir_type.h"

#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace azora {

struct StructField {
    std::string name;
    IrType type;
    bool mutable_ = false;
    Visibility visibility = Visibility{};
    Expr default_;
    int32_t typeParamIndex = -1;
    bool isUnsafe = false;
    TypeRef typeRef;
};

struct StructType {
    std::string name;
    std::vector<StructField> fields;
    std::vector<std::string> typeParams;
    Visibility visibility = Visibility{};
    bool isBridge = false;
    bool isUnion = false;
    std::optional<std::string> literalKind;

    const StructField* field(const std::string& fieldName) const {
        for (const StructField& f : fields) {
            if (f.name == fieldName) return &f;
        }
        return nullptr;
    }
};

struct SpecSymbol {
    std::vector<std::string> methodNames;
    std::optional<SpecCallback> callback;
    bool isDecorator = false;
    std::vector<std::string> typeParams;
    OrderedSet<DecoTarget> decoratorTargets;
    std::vector<DecoratorBinding> decoratorBindings;
    OrderedMap<std::string, IrType> propTypes;
    std::vector<std::string> parentNames;
    std::vector<TypeRef> parents;
    std::vector<std::string> requiredSpecs;
    bool isBridge = false;
    OrderedMap<std::string, TypeRef> typeDefaults;
};

struct TraitConformance {
    std::string typeName;
    std::string contractName;
    std::vector<TypeRef> typeArgs;
    bool isDecorator = false;
    std::vector<std::string> implTypeParams;
};

struct SymbolTable {
    // -- Structs, enums, error sets, slots ----------------------------------
    void defineStruct(StructType structType);
    /** Accepts a scope-qualified name, falling back to the final segment. */
    const StructType* lookupStruct(const std::string& name) const;
    void defineEnum(const std::string& name, std::vector<std::string> variants);
    const std::vector<std::string>* lookupEnum(const std::string& name) const;
    void defineFail(const std::string& name, std::vector<std::string> variants);
    const std::vector<std::string>* lookupFail(const std::string& name) const;
    void defineSlot(const std::string& name, std::vector<std::pair<std::string, std::vector<IrType>>> variants);
    const std::vector<std::pair<std::string, std::vector<IrType>>>* lookupSlot(const std::string& name) const;
    /** The declared name a possibly scope-qualified type reference denotes. */
    std::string canonicalTypeName(const std::string& name) const;

    // -- Specs, decorators, conformances -------------------------------------
    void defineSpec(const std::string& name, SpecSymbol symbol);
    void defineDecorator(const std::string& name, const OrderedSet<DecoTarget>& targets,
                         const std::vector<DecoratorBinding>& bindings, bool isBridge);
    const SpecSymbol* lookupSpec(const std::string& name) const;
    /** [specName] and every spec it inherits from, breadth-first, each once. */
    std::vector<std::string> specAndAncestors(const std::string& specName) const;
    /** Records a conformance; false when the same one is already recorded. `Order` brings `PartialOrder`. */
    bool defineConformance(const TraitConformance& conformance);
    bool implements(const std::string& typeName, const std::string& contractName, const std::vector<TypeRef>& typeArgs) const;
    bool conformsTo(const std::string& typeName, const std::string& contractName) const;
    /** Whether a value of [type] may be duplicated with the compiler-supplied `clone`. */
    bool isClonable(const IrType& type) const;
    const std::vector<TraitConformance>& allConformances() const { return conformances; }

    // -- Type aliases ---------------------------------------------------------
    void defineAlias(const std::string& name, const TypeRef& type) { aliases.set(name, type); }
    const TypeRef* lookupAlias(const std::string& name) const { return aliases.get(name); }
    OrderedMap<std::string, std::string> aliasMap;

    OrderedMap<std::string, StructType> structs;
    OrderedMap<std::string, std::vector<std::string>> enums;
    OrderedMap<std::string, std::vector<std::string>> fails;
    OrderedMap<std::string, std::vector<std::pair<std::string, std::vector<IrType>>>> slots;
    OrderedMap<std::string, SpecSymbol> specs;
    std::vector<TraitConformance> conformances;
    OrderedMap<std::string, TypeRef> aliases;

  private:
    bool isDeclaredType(const std::string& name) const;
};

}  // namespace azora
