// Port of CallbackImplNormalizer.kt.
#include "frontend/callback_impl_normalizer.h"

#include "frontend/use_as_template.h"

namespace azora {
namespace CallbackImplNormalizer {

static TypeRef substituteType(const TypeRef& type, const std::vector<std::string>& typeParams,
                              const std::vector<TypeRef>& traitArgs) {
    TypeRefNamed* named = as<TypeRefNamed>(type);
    if (named == nullptr) return type;
    for (size_t i = 0; i < typeParams.size(); i++) {
        if (typeParams[i] == named->name) return i < traitArgs.size() ? traitArgs[i] : type;
    }
    return type;
}

static std::string callbackMethodName(const std::string& traitName, const std::vector<TypeRef>& traitArgs,
                                      const SpecCallback& callback) {
    if (callback.useAsTemplate) return UseAsTemplate::expand(*callback.useAsTemplate, callback.typeParams, traitArgs);
    return callbackMethodBaseName(traitName);
}

static TopLevel normalizeItem(Arena& arena, const TopLevel& item, const OrderedMap<std::string, SpecCallback>& callbacks) {
    TopLevelImpl* impl = as<TopLevelImpl>(item);
    if (impl == nullptr) return item;
    if (!impl->traitName) return item;
    const SpecCallback* callback = callbacks.get(*impl->traitName);
    if (callback == nullptr) return item;
    if (impl->methods.size() != 1) return item;
    FuncDecl method = impl->methods[0];
    method.name = callbackMethodName(*impl->traitName, impl->traitArgs, *callback);
    std::vector<Param> params;
    for (const Param& param : callback->params) {
        Param copy = param;
        copy.type = substituteType(param.type, callback->typeParams, impl->traitArgs);
        params.push_back(copy);
    }
    method.params = params;
    method.returnType = explicitType(substituteType(callback->returnType, callback->typeParams, impl->traitArgs));
    method.memberCallStyle = callback->requiresParens ? MemberCallStyle::METHOD : MemberCallStyle::PROPERTY;
    TopLevelImpl copy = *impl;
    copy.methods = {method};
    return make(arena, copy);
}

Program normalize(Arena& arena, const Program& program) {
    OrderedMap<std::string, SpecCallback> callbacks;
    for (const TopLevel& item : program.items) {
        TopLevelSpec* spec = as<TopLevelSpec>(item);
        if (spec && spec->callback) callbacks.set(spec->name, *spec->callback);
    }
    if (callbacks.empty()) return program;
    Program copy = program;
    for (TopLevel& item : copy.items) item = normalizeItem(arena, item, callbacks);
    return copy;
}

}  // namespace CallbackImplNormalizer
}  // namespace azora
