// Port of semantic/ImplParameterAligner.kt.
#include "semantic/impl_parameter_aligner.h"

#include "base/collections.h"
#include "base/ordered.h"
#include "base/text.h"
#include "ir/ir_type.h"
#include "stdlib/declaration_renamer.h"

#include <map>
#include <unordered_map>
#include <unordered_set>

namespace azora {
namespace ImplParameterAligner {

namespace {

bool isBuiltinType(const std::string& name) { return name == "Array" || name == "Tuple" || name == "Task"; }

const std::vector<std::string>& suggestedNames() {
    static const std::vector<std::string> names = {"T", "U", "V", "W", "X", "Y", "Z"};
    return names;
}

}  // namespace

std::vector<std::string> typesWrittenAsParameters(const Program& program) {
    std::unordered_set<std::string> declared;
    // A library's packs by module: the unit's own types are not in scope in a library's impl.
    std::map<std::optional<std::string>, std::unordered_set<std::string>> packsByModule;
    std::unordered_map<std::string, std::vector<std::string>> packParams;
    for (const TopLevel& item : program.items) {
        std::optional<std::string> name;
        switch (item.kind()) {
            case TopLevelKind::Pack: {
                const TopLevelPack& pack = cast<TopLevelPack>(item);
                packsByModule[pack.declaringModule].insert(substringAfterLast(pack.name, "__"));
                packParams[pack.name] = pack.typeParams;
                name = pack.name;
                break;
            }
            case TopLevelKind::Slot: name = cast<TopLevelSlot>(item).name; break;
            case TopLevelKind::Enum: name = cast<TopLevelEnum>(item).name; break;
            case TopLevelKind::Fail: name = cast<TopLevelFail>(item).name; break;
            case TopLevelKind::Spec: name = cast<TopLevelSpec>(item).name; break;
            case TopLevelKind::TypeAlias: name = cast<TopLevelTypeAlias>(item).name; break;
            default: break;
        }
        if (name) declared.insert(substringAfterLast(*name, "__"));
    }
    OrderedSet<std::string> errors;
    for (const TopLevel& entry : program.items) {
        const TopLevelImpl* item = as<TopLevelImpl>(entry);
        if (!item) continue;
        bool own = !item->declaringModule || item->declaringModule == program.moduleName;
        auto isType = [&](const std::string& name) {
            if (IrTypes::isPrimitiveName(name) || isBuiltinType(name)) return true;
            if (own) return declared.count(name) != 0;
            auto packs = packsByModule.find(item->declaringModule);
            return packs != packsByModule.end() && packs->second.count(name) != 0;
        };
        auto found = packParams.find(item->typeName);
        std::vector<std::string> targetParams = found == packParams.end() ? std::vector<std::string>{} : found->second;
        for (size_t index = 0; index < item->typeParams.size(); index++) {
            const std::string& name = item->typeParams[index];
            // The target's own parameter, as its declaration names it, is one.
            if (index < targetParams.size() && targetParams[index] == name) continue;
            if (!isType(name)) continue;
            std::string owner = substringAfterLast(item->typeName, ".");
            std::string target = owner + "<" + join(item->typeParams, ", ") + ">";
            std::string head = item->traitName ? "impl " + substringAfterLast(*item->traitName, "__") + " for " + target : "impl " + target;
            std::vector<std::string> candidates;
            if (index < targetParams.size()) candidates.push_back(targetParams[index]);
            for (const std::string& s : suggestedNames()) candidates.push_back(s);
            std::string suggested = "P";
            for (const std::string& candidate : candidates) {
                if (!contains(item->typeParams, candidate) && !isType(candidate)) {
                    suggested = candidate;
                    break;
                }
            }
            errors.add("line " + str(item->line) + ": '" + head + "': '" + name +
                       "' is a type, but an implementation's target declares parameters, so this declares one called '" + name +
                       "' that hides it and implements every '" + owner + "'; write a parameter ('" + suggested +
                       "') - an implementation for one instantiation is not supported, since generics are erased");
        }
    }
    return std::vector<std::string>(errors.begin(), errors.end());
}

Program align(Arena& arena, const Program& program) {
    std::unordered_map<std::string, const TopLevelPack*> packs;
    for (const TopLevel& item : program.items) {
        if (const TopLevelPack* pack = as<TopLevelPack>(item)) packs[pack->name] = pack;
    }
    bool changed = false;
    std::vector<TopLevel> items;
    items.reserve(program.items.size());
    for (const TopLevel& item : program.items) {
        const TopLevelImpl* impl = as<TopLevelImpl>(item);
        if (!impl || impl->traitName || impl->variadicParam || impl->typeParams.empty()) {
            items.push_back(item);
            continue;
        }
        auto pack = packs.find(impl->typeName);
        if (pack == packs.end()) {
            items.push_back(item);
            continue;
        }
        const std::vector<std::string>& declared = pack->second->typeParams;
        if (declared.size() != impl->typeParams.size() || declared == impl->typeParams) {
            items.push_back(item);
            continue;
        }
        DeclarationRenamer::Names renames;
        for (size_t i = 0; i < declared.size(); i++) {
            if (impl->typeParams[i] != declared[i]) renames.set(impl->typeParams[i], declared[i]);
        }
        std::unordered_set<std::string> captured(declared.begin(), declared.end());
        for (const auto& [written, _] : renames) captured.insert(written);
        bool captures = false;
        for (const FuncDecl& method : impl->methods) {
            for (const std::string& p : method.typeParams) {
                if (captured.count(p)) captures = true;
            }
        }
        if (captures) {
            items.push_back(item);
            continue;
        }
        // Without its parameters in scope the renamer treats them as free names.
        TopLevelImpl bare = *impl;
        bare.typeParams = {};
        TopLevel renamed = DeclarationRenamer(arena, renames).item(make(arena, std::move(bare)));
        changed = true;
        TopLevelImpl result = cast<TopLevelImpl>(renamed);
        result.typeParams = declared;
        items.push_back(make(arena, std::move(result)));
    }
    if (!changed) return program;
    Program out = program;
    out.items = std::move(items);
    return out;
}

}  // namespace ImplParameterAligner
}  // namespace azora
