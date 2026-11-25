// Port of stdlib/StdlibInjector.kt, part 2: the checks a compilation runs
// against the libraries before injecting them.
#include "stdlib/stdlib_injector.h"

#include "base/text.h"
#include "frontend/ast_util.h"
#include "stdlib/declaration_renamer.h"

#include <algorithm>
#include <deque>

namespace azora {

namespace {

const char* const INTRINSIC_ARRAY = "Array";

std::string joinRange(const std::vector<std::string>& parts, const std::string& separator, size_t from, size_t to) {
    std::string out;
    for (size_t i = from; i < to; i++) {
        if (i > from) out += separator;
        out += parts[i];
    }
    return out;
}

}  // namespace

/**
 * Rejects imports that name no module, or a namespace rather than a module,
 * and imports that name nothing a module declares.
 */
std::vector<std::string> StdlibInjector::validateImports(const Program& program) {
    if (index().modules.empty()) return {};
    std::vector<std::string> errors;
    // A scope the program declares is not a module, but naming it is not naming nothing.
    OrderedSet<std::string> declaredHere = userDeclaredNames(program);
    OrderedSet<std::string> scopesHere;
    for (const auto& [name, path] : program.scopeTypeNamespaces.entries) scopesHere.add(path);
    auto declaredByProgram = [&](const std::string& path) {
        std::string name = replaceAll(path, ".", "__");
        std::string qualified = replaceAll(path, ".", "::");
        if (declaredHere.contains(name)) return true;
        for (const std::string& declared : declaredHere.items) {
            if (startsWith(declared, name + "__")) return true;
        }
        for (const std::string& scope : scopesHere.items) {
            if (scope == qualified || startsWith(scope, qualified + "::")) return true;
        }
        return false;
    };
    auto knownStartsWith = [&](const std::string& path) {
        std::string prefix = path + ".";
        for (const auto& [module, items] : index().modules.entries) {
            if (startsWith(module, prefix)) return true;
        }
        return false;
    };
    for (TopLevelUseImport* item : writtenImportsOf(program)) {
        std::vector<const ImportSpec*> leaves;
        std::function<void(const ImportSpec&)> collect = [&](const ImportSpec& spec) {
            if (spec.selector.kind == SelectorKind::Group) {
                for (const ImportSpec& member : spec.selector.members) collect(member);
            } else {
                leaves.push_back(&spec);
            }
        };
        for (const ImportSpec& spec : item->specs) collect(spec);
        for (const ImportSpec* spec : leaves) {
            const std::string& path = spec->path;
            bool wildcard = spec->selector.kind == SelectorKind::All;
            OrderedSet<std::string> without;
            for (const std::string& w : spec->without) without.add(w);
            int32_t line = spec->line > 0 ? spec->line : item->line;
            // The program's own module and what it declares are not in the library index.
            const std::optional<std::string>& own = program.moduleName;
            bool namesProgram =
                declaredByProgram(path) ||
                (own && (path == *own || (startsWith(path, *own + ".") && declaredByProgram(removePrefix(path, *own + ".")))));
            bool reachesKnownModule = index().modules.contains(path) || knownStartsWith(path);
            if (wildcard) {
                if (!reachesKnownModule) {
                    if (!namesProgram) errors.push_back("line " + str(line) + ": there is no module or namespace '" + path + "' to import from");
                    continue;
                }
                if (without.empty()) continue;
                OrderedSet<std::string> available;
                for (const auto& [name, declaration] : itemsVisibleFromImport(path, std::string("*")).entries) {
                    available.add(substringAfterLast(name, "__"));
                }
                for (const std::string& module : modulesForPath(path)) {
                    if (const std::vector<TypeFunctionDecl>* functions = index().typeFunctionsByModule.get(module)) {
                        for (const TypeFunctionDecl& f : *functions) available.add(substringAfterLast(f.name, "__"));
                    }
                    if (const std::vector<TypeTypeArm>* macros = index().typeMacrosByModule.get(module)) {
                        for (const TypeTypeArm& arm : *macros) {
                            if (arm.name) available.add(substringAfterLast(*arm.name, "__"));
                        }
                    }
                }
                for (const std::string& name : without.items) {
                    if (!available.contains(name)) {
                        errors.push_back("cannot exclude '" + name + "' from 'import " + path + "::{*, without " + name +
                                         "}': the wildcard does not provide a symbol named '" + name + "' (line " +
                                         str(item->line) + ")");
                    }
                }
                continue;
            }
            bool isExactModule = index().modules.contains(path) && isExternallyImportable(path);
            bool isSelectedItem = false;
            if (std::optional<std::pair<std::string, std::string>> selected = resolveSelectedLibraryPath(path)) {
                const OrderedMap<std::string, TopLevel>* module = index().modules.get(selected->first);
                isSelectedItem = isExternallyImportable(selected->first) && module && module->contains(selected->second);
            }
            if (isExactModule || isSelectedItem) continue;
            if (knownStartsWith(path)) {
                errors.push_back("cannot 'import " + path + "': '" + path +
                                 "' is a namespace, not a module - import a specific module such as 'import " + path +
                                 ".<name>', or 'import " + path + ".*' to pull in every module below it (line " +
                                 str(item->line) + ")");
                continue;
            }
            // A module that is not importable from here is left to name resolution.
            if (index().modules.contains(path) || namesProgram) continue;
            if (std::optional<std::string> missing = missingImport(path)) errors.push_back("line " + str(line) + ": " + *missing);
        }
    }
    return errors;
}

/** Why the dotted import [path] names nothing, or none when a module declares it. */
std::optional<std::string> StdlibInjector::missingImport(const std::string& path) {
    std::vector<std::string> segments = split(path, ".");
    std::vector<std::string> prefixes;
    for (size_t n = segments.size() - 1; n >= 1; n--) prefixes.push_back(joinRange(segments, ".", 0, n));
    std::vector<std::string> modules;
    for (const std::string& prefix : prefixes) {
        if (index().modules.contains(prefix)) modules.push_back(prefix);
    }
    for (const std::string& module : modules) {
        if (moduleDeclares(module, replaceAll(removePrefix(path, module + "."), ".", "__"))) return std::nullopt;
    }
    if (!modules.empty()) {
        const std::string& module = modules[0];
        return "module '" + module + "' has nothing named '" + replaceAll(removePrefix(path, module + "."), ".", "::") +
               "' to import";
    }
    std::optional<std::string> namespace_;
    for (const std::string& prefix : prefixes) {
        bool any = false;
        for (const auto& [module, items] : index().modules.entries) {
            if (startsWith(module, prefix + ".")) {
                any = true;
                break;
            }
        }
        if (any) {
            namespace_ = prefix;
            break;
        }
    }
    std::string missing = path;
    if (namespace_) missing = joinRange(segments, ".", 0, std::min(segments.size(), split(*namespace_, ".").size() + 1));
    return "there is no module '" + missing + "' to import";
}

/** Whether [module] declares [name] - a declaration, type function or type macro, a scope member - or a scope called [name]. */
bool StdlibInjector::moduleDeclares(const std::string& module, const std::string& name) {
    const OrderedMap<std::string, TopLevel>* items = index().modules.get(module);
    if (items == nullptr) return false;
    if (items->contains(name)) return true;
    auto named = [&](const std::optional<std::string>& declared) {
        return declared && (*declared == name || substringAfterLast(*declared, "__") == name);
    };
    if (const std::vector<TypeFunctionDecl>* functions = index().typeFunctionsByModule.get(module)) {
        for (const TypeFunctionDecl& f : *functions) {
            if (named(f.name)) return true;
        }
    }
    if (const std::vector<TypeTypeArm>* macros = index().typeMacrosByModule.get(module)) {
        for (const TypeTypeArm& arm : *macros) {
            if (named(arm.name)) return true;
        }
    }
    for (const auto& [key, item] : items->entries) {
        if (startsWith(key, name + "__")) return true;
    }
    std::string qualified = replaceAll(name, "__", "::");
    for (const auto& [key, exported] : index().scopeTypesByQualifiedName.entries) {
        if (exported.module == module && (exported.qualifiedName() == qualified || exported.qualifier == qualified ||
                                          startsWith(exported.qualifier, qualified + "::"))) {
            return true;
        }
    }
    return false;
}

/** The modules a single unit imports, as edges in the dependency graph. */
OrderedSet<std::string> StdlibInjector::importedModulesOf(const Program& program, const OrderedSet<std::string>& known) {
    OrderedSet<std::string> edges;
    for (TopLevelUseImport* item : writtenImportsOf(program)) {
        for (const ImportRequest& request : importRequests(*item)) {
            if (request.selected && *request.selected == "*") {
                for (const std::string& module : known.items) {
                    if (startsWith(module, request.path + ".")) edges.add(module);
                }
            } else if (known.contains(request.path)) {
                edges.add(request.path);
            } else if (std::optional<std::pair<std::string, std::string>> selected = resolveSelectedLibraryPath(request.path)) {
                if (known.contains(selected->first)) edges.add(selected->first);
            }
        }
    }
    return edges;
}

/** Rejects import cycles between modules, the entry program included. */
std::vector<std::string> StdlibInjector::validateModuleCycles(const Program& program) {
    OrderedSet<std::string> known;
    for (const auto& [module, items] : index().modules.entries) known.add(module);
    if (program.moduleName) known.add(*program.moduleName);
    OrderedMap<std::string, OrderedSet<std::string>> graph;
    auto addEdges = [&](const std::string& module, const OrderedSet<std::string>& more) {
        OrderedSet<std::string> edges = graph.contains(module) ? *graph.get(module) : OrderedSet<std::string>{};
        for (const std::string& e : more.items) edges.add(e);
        graph.set(module, std::move(edges));
    };
    for (const Program& library : programs) {
        if (!library.moduleName) continue;
        addEdges(*library.moduleName, importedModulesOf(library, known));
    }
    if (program.moduleName) addEdges(*program.moduleName, importedModulesOf(program, known));

    // Iterative DFS: an edge back onto the current path closes a cycle.
    OrderedSet<std::string> errors;
    std::unordered_set<std::string> visited;
    std::unordered_set<std::string> onPath;
    std::vector<std::string> path;
    for (const auto& [start, startEdges] : graph.entries) {
        if (visited.count(start)) continue;
        std::vector<std::pair<std::string, size_t>> stack{{start, 0}};
        visited.insert(start);
        onPath.insert(start);
        path.push_back(start);
        while (!stack.empty()) {
            auto& [node, pending] = stack.back();
            const OrderedSet<std::string>& edges = *graph.get(node);
            if (pending >= edges.items.size()) {
                onPath.erase(node);
                stack.pop_back();
                path.pop_back();
                continue;
            }
            std::string next = edges.items[pending++];
            if (!graph.contains(next)) continue;
            if (onPath.count(next)) {
                size_t from = (size_t)(std::find(path.begin(), path.end(), next) - path.begin());
                std::vector<std::string> cycle(path.begin() + (std::ptrdiff_t)from, path.end());
                cycle.push_back(next);
                errors.add("circular dependency between modules: " + join(cycle, " -> ") +
                           " - modules may not import each other, directly or transitively; move the shared "
                           "declarations into a module both can import");
                continue;
            }
            if (visited.count(next)) continue;
            visited.insert(next);
            onPath.insert(next);
            path.push_back(next);
            stack.push_back({next, 0});
        }
    }
    return errors.items;
}

/** The unreadable library sources [program] actually depends on. */
std::vector<std::string> StdlibInjector::unusableLibraries(const Program& program) {
    if (skipped.empty()) return {};
    OrderedSet<std::string> reached = reachableImportPaths(program);
    std::vector<std::string> out;
    for (const SkippedSource& failure : skipped) {
        if (!failure.module) continue;
        const std::string& module = *failure.module;
        bool hit = false;
        for (const std::string& path : reached.items) {
            if (module == path || startsWith(module, path + ".") || startsWith(path, module + ".")) {
                hit = true;
                break;
            }
        }
        if (hit) out.push_back(failure.message);
    }
    return out;
}

/** Every skipped library source, for a build that wants to hear about all of them. */
std::vector<std::string> StdlibInjector::skippedLibraryNotices() {
    std::vector<std::string> out;
    for (const SkippedSource& s : skipped) out.push_back(s.message);
    return out;
}

/** The import paths this program reaches: its own, plus what the modules behind them re-export. */
OrderedSet<std::string> StdlibInjector::reachableImportPaths(const Program& program) {
    std::deque<ImportRequest> seeds;
    for (TopLevelUseImport* item : writtenImportsOf(program)) {
        for (ImportRequest& request : importRequests(*item)) seeds.push_back(std::move(request));
    }
    for (const std::string& module : index().alwaysOnModules) {
        if (const std::vector<ImportRequest>* exported = index().exportedImportsByModule.get(module)) {
            seeds.insert(seeds.end(), exported->begin(), exported->end());
        }
    }
    OrderedSet<std::string> visited;
    while (!seeds.empty()) {
        std::string path = seeds.front().path;
        seeds.pop_front();
        if (!visited.add(path)) continue;
        for (const std::string& module : modulesForPath(path)) {
            if (const std::vector<ImportRequest>* exported = index().exportedImportsByModule.get(module)) {
                seeds.insert(seeds.end(), exported->begin(), exported->end());
            }
        }
    }
    return visited;
}

/** Resolves every written `derives`/`impl … for …` contract independently. */
std::vector<UnresolvedContractAccess> StdlibInjector::unresolvedContracts(const Program& program) {
    std::unordered_set<const void*> visibleDeclarations;
    auto identity = [](const TopLevel& item) { return nodeIdentity(item); };
    for (const auto& [name, item] : index().implicitRootItems.entries) visibleDeclarations.insert(identity(item));
    for (const auto& [name, item] : importedItems(program).entries) visibleDeclarations.insert(identity(item));
    OrderedSet<std::string> localGlobalTypes;
    for (const TopLevel& item : program.items) {
        std::optional<std::string> name = typeDeclarationName(item);
        if (name && !program.scopeTypeNamespaces.contains(*name)) localGlobalTypes.add(*name);
    }
    std::vector<UnresolvedContractAccess> out;
    for (const TopLevel& item : program.items) {
        TopLevelImpl* impl = as<TopLevelImpl>(item);
        if (impl == nullptr || !impl->traitName) continue;
        const std::string& name = *impl->traitName;
        if (impl->traitQualifier || localGlobalTypes.contains(name)) continue;
        if (program.scopeTypeNamespaces.contains(name)) continue;
        const TopLevel* declaration = index().items.get(name);
        if (declaration == nullptr) continue;
        if (visibleDeclarations.count(identity(*declaration))) continue;
        const std::string* module = index().moduleOfName.get(name);
        if (module == nullptr) continue;
        UnresolvedContractAccess access{name, *module, impl->traitLine, impl->traitColumn, impl->traitLength};
        if (std::find(out.begin(), out.end(), access) == out.end()) out.push_back(std::move(access));
    }
    return out;
}

/**
 * Validates source-level type qualification: importing a module makes a type
 * visible, but a type declared in a named scope is written `Scope::Type`
 * outside that scope.
 */
TypeAccessValidation StdlibInjector::validateTypeAccess(const Program& program) {
    TypeAccessCheck check{*this, program};
    for (const auto& [name, item] : index().implicitRootItems.entries) check.visible.insert(nodeIdentity(item));
    for (const auto& [name, item] : importedItems(program).entries) check.visible.insert(nodeIdentity(item));
    for (const TopLevel& item : program.items) {
        std::optional<std::string> name = typeDeclarationName(item);
        if (name && !program.scopeTypeNamespaces.contains(*name)) check.localGlobalTypes.add(*name);
    }
    const OrderedSet<std::string> none;
    for (const TopLevel& item : program.items) {
        std::optional<std::string> currentScope = itemScope(item);
        if (!currentScope) {
            if (std::optional<std::string> typeName = typeDeclarationName(item)) {
                if (const std::string* scope = program.scopeTypeNamespaces.get(*typeName)) currentScope = *scope;
            }
        }
        switch (item.kind()) {
            case TopLevelKind::Func: check.function(cast<TopLevelFunc>(item).decl, currentScope); break;
            case TopLevelKind::FinDecl: {
                const TopLevelFinDecl& d = cast<TopLevelFinDecl>(item);
                if (d.type) check.type(d.type, d.line, none, currentScope);
                check.expression(d.initializer, none, currentScope);
                break;
            }
            case TopLevelKind::LetDecl: {
                const TopLevelLetDecl& d = cast<TopLevelLetDecl>(item);
                if (d.type) check.type(d.type, d.line, none, currentScope);
                check.expression(d.initializer, none, currentScope);
                break;
            }
            case TopLevelKind::VarDecl: {
                const TopLevelVarDecl& d = cast<TopLevelVarDecl>(item);
                if (d.type) check.type(d.type, d.line, none, currentScope);
                check.expression(d.initializer, none, currentScope);
                break;
            }
            case TopLevelKind::Test: check.block(cast<TopLevelTest>(item).body, none, currentScope); break;
            case TopLevelKind::Pack: {
                const TopLevelPack& p = cast<TopLevelPack>(item);
                for (const Annotation& a : p.annotations) check.appliedAnnotation(a, currentScope);
                if (p.nameMacro) check.expression(p.nameMacro, none, currentScope);
                OrderedSet<std::string> typeParams;
                for (const std::string& t : p.typeParams) typeParams.add(t);
                for (const PackField& f : p.fields) {
                    for (const Annotation& a : f.annotations) check.appliedAnnotation(a, currentScope);
                    check.type(f.type, p.line, typeParams, currentScope);
                    if (f.default_) check.expression(f.default_, typeParams, currentScope);
                }
                break;
            }
            case TopLevelKind::Solo: {
                const TopLevelSolo& s = cast<TopLevelSolo>(item);
                for (const PackField& f : s.fields) {
                    check.type(f.type, s.line, none, currentScope);
                    if (f.default_) check.expression(f.default_, none, currentScope);
                }
                for (const FuncDecl& m : s.methods) check.function(m, currentScope);
                break;
            }
            case TopLevelKind::Impl: {
                const TopLevelImpl& impl = cast<TopLevelImpl>(item);
                for (const Annotation& a : impl.annotations) check.appliedAnnotation(a, currentScope);
                if (impl.traitName) {
                    check.scopeSymbol(*impl.traitName, impl.traitQualifier, impl.line, currentScope, "spec or decorator", "");
                }
                OrderedSet<std::string> typeParams;
                for (const std::string& t : impl.typeParams) typeParams.add(t);
                for (const TypeRef& t : impl.traitArgs) check.type(t, impl.line, typeParams, currentScope);
                for (const Expr& e : impl.decoratorArgs) check.expression(e, typeParams, currentScope);
                for (const auto& [name, value] : impl.decoratorNamedArgs) check.expression(value, typeParams, currentScope);
                for (const FuncDecl& m : impl.methods) check.function(m, currentScope);
                break;
            }
            case TopLevelKind::Spec:
                for (const FuncDecl& m : cast<TopLevelSpec>(item).methods) check.function(m, currentScope);
                break;
            case TopLevelKind::Deco: {
                const TopLevelDeco& deco = cast<TopLevelDeco>(item);
                for (const Annotation& a : deco.annotations) check.appliedAnnotation(a, currentScope);
                for (const PackField& f : deco.fields) check.type(f.type, deco.line, none, currentScope);
                break;
            }
            case TopLevelKind::Slot: {
                const TopLevelSlot& slot = cast<TopLevelSlot>(item);
                for (const SlotVariant& variant : slot.variants) {
                    for (const TypeRef& t : variant.payloadTypes) check.type(t, slot.line, none, currentScope);
                }
                break;
            }
            case TopLevelKind::TypeAlias: {
                const TopLevelTypeAlias& alias = cast<TopLevelTypeAlias>(item);
                OrderedSet<std::string> typeParams;
                for (const std::string& t : alias.typeParams) typeParams.add(t);
                check.type(alias.type, alias.line, typeParams, currentScope);
                break;
            }
            case TopLevelKind::Bridge: {
                const TopLevelBridge& bridge = cast<TopLevelBridge>(item);
                for (const BridgeSig& signature : bridge.funcs) {
                    OrderedSet<std::string> typeParams;
                    for (const std::string& t : signature.typeParams) typeParams.add(t);
                    if (signature.nameMacro) check.expression(signature.nameMacro, typeParams, currentScope);
                    for (const Param& p : signature.params) check.type(p.type, signature.line, typeParams, currentScope);
                    check.type(signature.returnType, signature.line, typeParams, currentScope);
                }
                for (const BridgeValue& value : bridge.values) {
                    if (value.nameMacro) check.expression(value.nameMacro, none, currentScope);
                    check.type(value.type, value.line, none, currentScope);
                    check.expression(value.initializer, none, currentScope);
                }
                break;
            }
            default:
                break;
        }
    }
    return TypeAccessValidation{check.errors.items, check.unresolved};
}

/** `impl pack Name` declares `Name` only where no other `Name` is in sight. */
std::vector<std::string> StdlibInjector::validateImplPackDeclarations(const Program& program) {
    std::vector<TopLevelPack*> declared;
    for (const TopLevel& item : program.items) {
        TopLevelPack* pack = as<TopLevelPack>(item);
        if (pack && pack->declaredByImpl) declared.push_back(pack);
    }
    if (declared.empty()) return {};
    OrderedSet<std::string> visible;
    for (const auto& [name, item] : index().implicitRootItems.entries) visible.add(name);
    for (const auto& [name, item] : importedItems(program).entries) visible.add(name);
    std::vector<std::string> out;
    for (TopLevelPack* pack : declared) {
        if (!visible.contains(pack->name)) continue;
        const std::string* owner = index().moduleOfName.get(pack->name);
        out.push_back("line " + str(pack->line) + ": 'impl pack " + pack->name +
                      "' is only allowed in the file that declares pack " + pack->name + "; '" + pack->name +
                      "' already comes from " + (owner ? "module '" + *owner + "'" : std::string("an imported module")));
    }
    return out;
}

/** Rejects references to another module's private declarations (which are still injected). */
std::vector<std::string> StdlibInjector::validatePrivateAccess(const Program& program) {
    OrderedMap<std::string, TopLevel> imported = importedItems(program);
    OrderedSet<std::string> privateNames;
    for (const auto& [name, item] : imported.entries) {
        if (isPrivateName(name)) privateNames.add(name);
    }
    if (privateNames.empty()) return {};
    // The program's own declarations shadow imports.
    OrderedSet<std::string> ownNames;
    for (const TopLevel& item : program.items) {
        if (std::optional<std::string> name = declaredNameOf(item)) ownNames.add(*name);
    }
    OrderedSet<std::string> referenced;
    for (const TopLevel& item : program.items) collectNamesFromItem(item, referenced);
    OrderedSet<std::string> errors;
    for (const std::string& name : referenced.items) {
        if (!privateNames.contains(name) || ownNames.contains(name)) continue;
        const std::string* owner = index().moduleOfName.get(name);
        std::string shown = replaceAll(name, "__", "::");
        errors.add("'" + shown + "' is private to " +
                   (owner ? "module '" + *owner + "'" : std::string("the module that declares it")) +
                   " - a leading underscore keeps the declaration inside its owning module");
    }
    return errors.items;
}

/** True when [name] is a private declaration (judged on its member segment). */
bool StdlibInjector::isPrivateName(const std::string& name) { return startsWith(memberSegmentOf(name), "_"); }

/** The member part of a possibly scope-mangled name: `Secret___hidden` → `_hidden`. */
std::string StdlibInjector::memberSegmentOf(const std::string& name) {
    for (int64_t i = (int64_t)name.size() - 2; i >= 1; i--) {
        size_t k = (size_t)i;
        if (name[k] == '_' && name[k + 1] == '_' && name[k - 1] != '_') return name.substr(k + 2);
    }
    return name;
}

// ---------------------------------------------------------------------------
// validateTypeAccess's walk (Kotlin's local class Validator)
// ---------------------------------------------------------------------------

// static
const void* StdlibInjector::nodeIdentity(const TopLevel& item) {
    return std::visit(
        [](auto p) -> const void* {
            if constexpr (std::is_same_v<decltype(p), std::monostate>) {
                return nullptr;
            } else {
                return p;
            }
        },
        item.v);
}

void StdlibInjector::TypeAccessCheck::scopeSymbol(const std::string& name, const std::optional<std::string>& qualifier,
                                                  int32_t line, const std::optional<std::string>& currentScope,
                                                  const std::string& kind, const std::string& sigil) {
    auto sourceName = [&](const std::optional<std::string>& scope, const std::string& symbol) {
        return sigil + (scope ? *scope + "::" : std::string()) + symbol;
    };
    const std::string* localScope = program.scopeTypeNamespaces.get(name);
    std::vector<const ScopeTypeExport*> visibleExports;
    if (const std::vector<ScopeTypeExport>* exports = injector.index().scopeTypesByShortName.get(name)) {
        for (const ScopeTypeExport& e : *exports) {
            if (visible.count(nodeIdentity(e.declaration))) visibleExports.push_back(&e);
        }
    }
    if (localScope) {
        if (!qualifier && currentScope != *localScope) {
            errors.add("line " + str(line) + ": undefined " + kind + " '" + sourceName(std::nullopt, name) + "'; '" + name +
                       "' is part of scope '" + *localScope + "', use '" + sourceName(*localScope, name) + "' instead");
        } else if (qualifier && *qualifier != *localScope) {
            errors.add("line " + str(line) + ": undefined " + kind + " '" + sourceName(qualifier, name) + "'; '" + name +
                       "' is part of scope '" + *localScope + "'");
        }
    } else if (!qualifier) {
        if (!visibleExports.empty()) {
            const ScopeTypeExport& e = *visibleExports[0];
            errors.add("line " + str(line) + ": undefined " + kind + " '" + sourceName(std::nullopt, name) + "'; '" + name +
                       "' is part of scope '" + e.qualifier + "', use '" + sourceName(e.qualifier, name) + "' instead");
        }
    } else {
        std::string qualified = *qualifier + "::" + name;
        const ScopeTypeExport* exported = injector.index().scopeTypesByQualifiedName.get(qualified);
        if (exported && !visible.count(nodeIdentity(exported->declaration))) {
            errors.add("line " + str(line) + ": undefined " + kind + " '" + sourceName(qualifier, name) + "' - '" + name +
                       "' is provided by '" + exported->module + "': add 'import " + exported->module + "'");
        } else if (!exported && !visibleExports.empty()) {
            errors.add("line " + str(line) + ": undefined " + kind + " '" + sourceName(qualifier, name) + "'; '" + name +
                       "' is part of scope '" + visibleExports[0]->qualifier + "'");
        }
    }
}

void StdlibInjector::TypeAccessCheck::appliedAnnotation(const Annotation& annotation,
                                                        const std::optional<std::string>& currentScope) {
    scopeSymbol(annotation.name, annotation.qualifier, annotation.line, currentScope, "decorator", "@");
    const OrderedSet<std::string> none;
    for (const Expr& a : annotation.args) expression(a, none, currentScope);
    for (const auto& [name, value] : annotation.namedArgs) expression(value, none, currentScope);
}

void StdlibInjector::TypeAccessCheck::type(const TypeRef& ref, int32_t line, const OrderedSet<std::string>& typeParams,
                                           const std::optional<std::string>& currentScope) {
    switch (ref.kind()) {
        case TypeRefKind::Named: {
            const TypeRefNamed& named = cast<TypeRefNamed>(ref);
            // A synthesized reference was produced by the compiler; source-level
            // rules do not apply. An array is the compiler's own type.
            if (!named.synthesized && !TypeFunctionCall::isCall(named) && !typeParams.contains(named.name) &&
                named.name != INTRINSIC_ARRAY && !localGlobalTypes.contains(named.name)) {
                const TopLevel* found = injector.index().items.get(named.name);
                const TopLevel* libraryDeclaration =
                    (found && injector.typeDeclarationName(*found) == named.name) ? found : nullptr;
                if (!named.qualifier && libraryDeclaration && !visible.count(nodeIdentity(*libraryDeclaration)) &&
                    named.line > 0) {
                    if (const std::string* module = injector.index().moduleOfName.get(named.name)) {
                        UnresolvedTypeAccess access{named.name, *module, named.line, named.column,
                                                    named.length > 0 ? named.length : u16len(named.name)};
                        if (std::find(unresolved.begin(), unresolved.end(), access) == unresolved.end()) {
                            unresolved.push_back(std::move(access));
                        }
                    }
                }
                const std::string* localScope = program.scopeTypeNamespaces.get(named.name);
                std::vector<const ScopeTypeExport*> visibleExports;
                if (const std::vector<ScopeTypeExport>* exports = injector.index().scopeTypesByShortName.get(named.name)) {
                    for (const ScopeTypeExport& e : *exports) {
                        if (visible.count(nodeIdentity(e.declaration))) visibleExports.push_back(&e);
                    }
                }
                if (localScope) {
                    if (!named.qualifier && currentScope != *localScope) {
                        errors.add("line " + str(line) + ": undefined type '" + named.name + "'; '" + named.name +
                                   "' is part of scope '" + *localScope + "', use '" + *localScope + "::" + named.name +
                                   "' instead");
                    } else if (named.qualifier && *named.qualifier != *localScope) {
                        errors.add("line " + str(line) + ": undefined type '" + *named.qualifier + "::" + named.name +
                                   "'; '" + named.name + "' is part of scope '" + *localScope + "'");
                    }
                } else if (!named.qualifier) {
                    if (!visibleExports.empty()) {
                        const ScopeTypeExport& e = *visibleExports[0];
                        errors.add("line " + str(line) + ": undefined type '" + named.name + "'; '" + named.name +
                                   "' is part of scope '" + e.qualifier + "', use '" + e.qualifiedName() + "' instead");
                    }
                } else {
                    std::string qualified = *named.qualifier + "::" + named.name;
                    const ScopeTypeExport* exported = injector.index().scopeTypesByQualifiedName.get(qualified);
                    if (exported && !visible.count(nodeIdentity(exported->declaration))) {
                        errors.add("line " + str(line) + ": undefined type '" + qualified + "' - '" + named.name +
                                   "' is provided by '" + exported->module + "': add 'import " + exported->module + "'");
                    } else if (!exported && !visibleExports.empty()) {
                        errors.add("line " + str(line) + ": undefined type '" + qualified + "'; '" + named.name +
                                   "' is part of scope '" + visibleExports[0]->qualifier + "'");
                    }
                }
            }
            for (const TypeRef& arg : named.args) type(arg, line, typeParams, currentScope);
            return;
        }
        case TypeRefKind::Array: type(cast<TypeRefArray>(ref).element, line, typeParams, currentScope); return;
        case TypeRefKind::Map:
            type(cast<TypeRefMap>(ref).key, line, typeParams, currentScope);
            type(cast<TypeRefMap>(ref).value, line, typeParams, currentScope);
            return;
        case TypeRefKind::Set: type(cast<TypeRefSet>(ref).element, line, typeParams, currentScope); return;
        case TypeRefKind::Function: {
            const TypeRefFunction& f = cast<TypeRefFunction>(ref);
            for (const TypeRef& p : f.params) type(p, line, typeParams, currentScope);
            for (const TypeRef& r : f.receivers) type(r, line, typeParams, currentScope);
            type(f.ret, line, typeParams, currentScope);
            return;
        }
        case TypeRefKind::Tuple:
            for (const TypeRef& e : cast<TypeRefTuple>(ref).elements) type(e, line, typeParams, currentScope);
            return;
        case TypeRefKind::Nullable: type(cast<TypeRefNullable>(ref).inner, line, typeParams, currentScope); return;
        case TypeRefKind::Failable: type(cast<TypeRefFailable>(ref).ok, line, typeParams, currentScope); return;
        case TypeRefKind::Pointer: type(cast<TypeRefPointer>(ref).inner, line, typeParams, currentScope); return;
        case TypeRefKind::Reference: type(cast<TypeRefReference>(ref).inner, line, typeParams, currentScope); return;
        case TypeRefKind::None:
        case TypeRefKind::Const:
            return;
    }
}

void StdlibInjector::TypeAccessCheck::annotation(const TypeAnnotation& value, int32_t line,
                                                 const OrderedSet<std::string>& typeParams,
                                                 const std::optional<std::string>& currentScope) {
    if (value.isExplicit()) type(value.ref, line, typeParams, currentScope);
}

void StdlibInjector::TypeAccessCheck::function(const FuncDecl& func, const std::optional<std::string>& currentScope) {
    for (const Annotation& a : func.annotations) appliedAnnotation(a, currentScope);
    OrderedSet<std::string> typeParams;
    for (const std::string& t : func.typeParams) typeParams.add(t);
    for (const Param& p : func.params) {
        type(p.type, func.line, typeParams, currentScope);
        if (p.defaultValue) expression(p.defaultValue, typeParams, currentScope);
    }
    annotation(func.returnType, func.line, typeParams, currentScope);
    block(func.body, typeParams, currentScope);
}

void StdlibInjector::TypeAccessCheck::expression(const Expr& expr, const OrderedSet<std::string>& typeParams,
                                                 const std::optional<std::string>& currentScope) {
    auto ex = [&](const Expr& e) { expression(e, typeParams, currentScope); };
    switch (expr.kind()) {
        case ExprKind::Binary: ex(cast<ExprBinary>(expr).left); ex(cast<ExprBinary>(expr).right); return;
        case ExprKind::Unary: ex(cast<ExprUnary>(expr).operand); return;
        case ExprKind::Call: {
            const ExprCall& call = cast<ExprCall>(expr);
            for (const TypeRef& t : call.typeArgs) type(t, call.line, typeParams, currentScope);
            for (const Expr& a : call.args) ex(a);
            if (call.receiver) ex(call.receiver);
            return;
        }
        case ExprKind::Grouping: ex(cast<ExprGrouping>(expr).expr); return;
        case ExprKind::Range: ex(cast<ExprRange>(expr).from); ex(cast<ExprRange>(expr).to); return;
        case ExprKind::ArrayLiteral: for (const Expr& e : cast<ExprArrayLiteral>(expr).elements) ex(e); return;
        case ExprKind::SetLiteral: for (const Expr& e : cast<ExprSetLiteral>(expr).elements) ex(e); return;
        case ExprKind::Index: ex(cast<ExprIndex>(expr).target); ex(cast<ExprIndex>(expr).index); return;
        case ExprKind::Member: ex(cast<ExprMember>(expr).target); return;
        case ExprKind::MethodCall:
            ex(cast<ExprMethodCall>(expr).target);
            for (const Expr& a : cast<ExprMethodCall>(expr).args) ex(a);
            return;
        case ExprKind::StringTemplate:
            for (const StringTemplatePart& p : cast<ExprStringTemplate>(expr).parts) {
                if (!p.isLiteral) ex(p.expr);
            }
            return;
        case ExprKind::TupleLit: for (const Expr& e : cast<ExprTupleLit>(expr).elements) ex(e); return;
        case ExprKind::VariantLit: for (const Expr& e : cast<ExprVariantLit>(expr).elements) ex(e); return;
        case ExprKind::TupleAccess: ex(cast<ExprTupleAccess>(expr).target); return;
        case ExprKind::CatchExpr: ex(cast<ExprCatchExpr>(expr).expr); ex(cast<ExprCatchExpr>(expr).fallback); return;
        case ExprKind::TryPropagate: ex(cast<ExprTryPropagate>(expr).expr); return;
        case ExprKind::IfExpr:
            ex(cast<ExprIfExpr>(expr).condition);
            ex(cast<ExprIfExpr>(expr).thenExpr);
            ex(cast<ExprIfExpr>(expr).elseExpr);
            return;
        case ExprKind::Lambda: {
            const ExprLambda& lambda = cast<ExprLambda>(expr);
            for (const Param& p : lambda.params) {
                type(p.type, lambda.line, typeParams, currentScope);
                if (p.defaultValue) ex(p.defaultValue);
            }
            for (const Param& p : lambda.receivers) type(p.type, lambda.line, typeParams, currentScope);
            block(lambda.body, typeParams, currentScope);
            return;
        }
        case ExprKind::NamedArg: ex(cast<ExprNamedArg>(expr).value); return;
        case ExprKind::NullCoalesce: ex(cast<ExprNullCoalesce>(expr).left); ex(cast<ExprNullCoalesce>(expr).right); return;
        case ExprKind::SafeMember: ex(cast<ExprSafeMember>(expr).target); return;
        case ExprKind::Cast:
            ex(cast<ExprCast>(expr).expr);
            type(cast<ExprCast>(expr).targetType, expr.line(), typeParams, currentScope);
            return;
        case ExprKind::IsCheck:
            ex(cast<ExprIsCheck>(expr).expr);
            for (const TypeRef& t : cast<ExprIsCheck>(expr).typeArgs) type(t, expr.line(), typeParams, currentScope);
            return;
        case ExprKind::MapLit:
            for (const auto& [key, value] : cast<ExprMapLit>(expr).entries) {
                ex(key);
                ex(value);
            }
            return;
        case ExprKind::Alloc: ex(cast<ExprAlloc>(expr).value); return;
        case ExprKind::Deref: ex(cast<ExprDeref>(expr).target); return;
        case ExprKind::Isolated: ex(cast<ExprIsolated>(expr).value); return;
        case ExprKind::Await: ex(cast<ExprAwait>(expr).value); return;
        case ExprKind::Spread: ex(cast<ExprSpread>(expr).array); return;
        case ExprKind::MetaInvoke: for (const Expr& a : cast<ExprMetaInvoke>(expr).args) ex(a); return;
        // `.(args)` - the constructor's arguments are checked as any call's are.
        case ExprKind::InferredMember:
            if (cast<ExprInferredMember>(expr).ctorArgs) {
                for (const Expr& a : *cast<ExprInferredMember>(expr).ctorArgs) ex(a);
            }
            return;
        case ExprKind::Slice: {
            const ExprSlice& s = cast<ExprSlice>(expr);
            ex(s.target);
            if (s.start) ex(s.start);
            if (s.stop) ex(s.stop);
            if (s.step) ex(s.step);
            return;
        }
        default:
            return;
    }
}

/** A block's statements, with what its imports bring in visible there and in the blocks it encloses. */
void StdlibInjector::TypeAccessCheck::block(const std::vector<Stmt>& body, const OrderedSet<std::string>& typeParams,
                                            const std::optional<std::string>& currentScope) {
    std::vector<ImportRequest> requests;
    bool anyImport = false;
    for (const Stmt& s : body) {
        if (StmtImport* import = as<StmtImport>(s)) {
            anyImport = true;
            std::vector<ImportRequest> more = injector.importRequests(*import->use);
            requests.insert(requests.end(), more.begin(), more.end());
        }
    }
    std::unordered_set<const void*> outer = visible;
    if (anyImport) {
        for (const auto& [name, item] : injector.itemsImportedBy(requests, nullptr).entries) visible.insert(nodeIdentity(item));
    }
    try {
        for (const Stmt& s : body) statement(s, typeParams, currentScope);
    } catch (...) {
        visible = std::move(outer);
        throw;
    }
    visible = std::move(outer);
}

void StdlibInjector::TypeAccessCheck::statement(const Stmt& stmt, const OrderedSet<std::string>& typeParams,
                                                const std::optional<std::string>& currentScope) {
    auto ex = [&](const Expr& e) { expression(e, typeParams, currentScope); };
    auto bl = [&](const std::vector<Stmt>& b) { block(b, typeParams, currentScope); };
    auto ann = [&](const TypeAnnotation& a, int32_t line) { annotation(a, line, typeParams, currentScope); };
    switch (stmt.kind()) {
        case StmtKind::VarDecl: ann(cast<StmtVarDecl>(stmt).type, stmt.line()); ex(cast<StmtVarDecl>(stmt).initializer); return;
        case StmtKind::FinDecl: ann(cast<StmtFinDecl>(stmt).type, stmt.line()); ex(cast<StmtFinDecl>(stmt).initializer); return;
        case StmtKind::LetDecl: ann(cast<StmtLetDecl>(stmt).type, stmt.line()); ex(cast<StmtLetDecl>(stmt).initializer); return;
        case StmtKind::InlineVar:
            ann(cast<StmtInlineVar>(stmt).type, stmt.line());
            ex(cast<StmtInlineVar>(stmt).initializer);
            return;
        case StmtKind::InlineFin:
            ann(cast<StmtInlineFin>(stmt).type, stmt.line());
            ex(cast<StmtInlineFin>(stmt).initializer);
            return;
        case StmtKind::InlineLet:
            ann(cast<StmtInlineLet>(stmt).type, stmt.line());
            ex(cast<StmtInlineLet>(stmt).initializer);
            return;
        case StmtKind::RemDecl: ann(cast<StmtRemDecl>(stmt).type, stmt.line()); ex(cast<StmtRemDecl>(stmt).initializer); return;
        case StmtKind::InlineAssignment: ex(cast<StmtInlineAssignment>(stmt).value); return;
        case StmtKind::Assignment: ex(cast<StmtAssignment>(stmt).value); return;
        case StmtKind::Return:
            if (cast<StmtReturn>(stmt).value) ex(cast<StmtReturn>(stmt).value);
            return;
        case StmtKind::ExprStmt: ex(cast<StmtExprStmt>(stmt).expr); return;
        case StmtKind::If:
            ex(cast<StmtIf>(stmt).condition);
            bl(cast<StmtIf>(stmt).thenBranch);
            if (cast<StmtIf>(stmt).elseBranch) bl(*cast<StmtIf>(stmt).elseBranch);
            return;
        case StmtKind::InlineIf:
            ex(cast<StmtInlineIf>(stmt).condition);
            bl(cast<StmtInlineIf>(stmt).thenBranch);
            if (cast<StmtInlineIf>(stmt).elseBranch) bl(*cast<StmtInlineIf>(stmt).elseBranch);
            return;
        case StmtKind::DeepInlineIf:
            ex(cast<StmtDeepInlineIf>(stmt).condition);
            bl(cast<StmtDeepInlineIf>(stmt).thenBranch);
            if (cast<StmtDeepInlineIf>(stmt).elseBranch) bl(*cast<StmtDeepInlineIf>(stmt).elseBranch);
            return;
        case StmtKind::Assert: ex(cast<StmtAssert>(stmt).condition); ex(cast<StmtAssert>(stmt).message); return;
        case StmtKind::Trace:
            ex(cast<StmtTrace>(stmt).message);
            if (cast<StmtTrace>(stmt).level) ex(cast<StmtTrace>(stmt).level);
            return;
        case StmtKind::InlineAssert: ex(cast<StmtInlineAssert>(stmt).condition); ex(cast<StmtInlineAssert>(stmt).message); return;
        case StmtKind::InlineTrace:
            ex(cast<StmtInlineTrace>(stmt).message);
            if (cast<StmtInlineTrace>(stmt).level) ex(cast<StmtInlineTrace>(stmt).level);
            return;
        case StmtKind::While: ex(cast<StmtWhile>(stmt).condition); bl(cast<StmtWhile>(stmt).body); return;
        case StmtKind::For: {
            const StmtFor& f = cast<StmtFor>(stmt);
            if (f.declaredType) type(f.declaredType, f.line, typeParams, currentScope);
            ex(f.iterable);
            if (f.step) ex(f.step);
            bl(f.body);
            return;
        }
        case StmtKind::InlineFor: ex(cast<StmtInlineFor>(stmt).iterable); bl(cast<StmtInlineFor>(stmt).body); return;
        case StmtKind::Loop:
            if (cast<StmtLoop>(stmt).iterable) ex(cast<StmtLoop>(stmt).iterable);
            bl(cast<StmtLoop>(stmt).body);
            return;
        case StmtKind::Exchange: ex(cast<StmtExchange>(stmt).left); ex(cast<StmtExchange>(stmt).right); return;
        case StmtKind::IndexAssign:
            ex(cast<StmtIndexAssign>(stmt).target);
            ex(cast<StmtIndexAssign>(stmt).index);
            ex(cast<StmtIndexAssign>(stmt).value);
            return;
        case StmtKind::MemberAssign: ex(cast<StmtMemberAssign>(stmt).target); ex(cast<StmtMemberAssign>(stmt).value); return;
        case StmtKind::When: {
            const StmtWhen& w = cast<StmtWhen>(stmt);
            ex(w.scrutinee);
            for (const WhenBranch& branch : w.branches) {
                for (const Expr& p : branch.patterns) ex(p);
                bl(branch.body);
            }
            if (w.elseBranch) bl(*w.elseBranch);
            return;
        }
        case StmtKind::Throw: ex(cast<StmtThrow>(stmt).value); return;
        case StmtKind::Panic: ex(cast<StmtPanic>(stmt).message); return;
        case StmtKind::DerefAssign: ex(cast<StmtDerefAssign>(stmt).target); ex(cast<StmtDerefAssign>(stmt).value); return;
        case StmtKind::Yield: ex(cast<StmtYield>(stmt).value); return;
        case StmtKind::Try:
            bl(cast<StmtTry>(stmt).body);
            if (cast<StmtTry>(stmt).catchBody) bl(*cast<StmtTry>(stmt).catchBody);
            return;
        case StmtKind::Defer: bl(cast<StmtDefer>(stmt).body); return;
        case StmtKind::Scope: bl(cast<StmtScope>(stmt).body); return;
        case StmtKind::InlineBlock: bl(cast<StmtInlineBlock>(stmt).body); return;
        case StmtKind::DeepInlineBlock: bl(cast<StmtDeepInlineBlock>(stmt).body); return;
        case StmtKind::Effect:
            if (cast<StmtEffect>(stmt).dependencies) {
                for (const Expr& d : *cast<StmtEffect>(stmt).dependencies) ex(d);
            }
            bl(cast<StmtEffect>(stmt).body);
            return;
        case StmtKind::UsingContext:
            for (const Expr& v : cast<StmtUsingContext>(stmt).values) ex(v);
            bl(cast<StmtUsingContext>(stmt).body);
            return;
        case StmtKind::NoInline: statement(cast<StmtNoInline>(stmt).stmt, typeParams, currentScope); return;
        default:
            return;
    }
}

}  // namespace azora
