// Port of stdlib/StdlibInjector.kt, part 3: inject() and its dependency walk.
#include "stdlib/stdlib_injector.h"

#include "base/text.h"
#include "frontend/ast_rewriter.h"
#include "frontend/ast_util.h"
#include "frontend/scope_access_rewriter.h"

#include <algorithm>

namespace azora {

namespace {

const OrderedSet<std::string>& implicitCollectionTypes() {
    static const OrderedSet<std::string> names{"List", "MutableList", "Set", "MutableSet", "Map", "MutableMap"};
    return names;
}

/** What a literal builds when its context names no target: the standard map. */
const OrderedSet<std::string>& implicitLiteralTargets() {
    static const OrderedSet<std::string> names{"LinkedHashMap"};
    return names;
}

/** Non-bridge library types named by compiler code (the Display deriver, channels). */
const OrderedSet<std::string>& compilerNamedTypes() {
    static const OrderedSet<std::string> names{"Formatter", "Channel"};
    return names;
}

template <class T>
void append(std::vector<T>& into, const std::vector<T>& more) {
    into.insert(into.end(), more.begin(), more.end());
}

/** Kotlin `list.distinct()` on data-class values: first of each structurally equal group. */
template <class T>
std::vector<T> distinctByStructure(const std::vector<T>& items) {
    std::vector<T> out;
    std::unordered_set<std::string> seen;
    for (const T& item : items) {
        if (seen.insert(structuralKey(item)).second) out.push_back(item);
    }
    return out;
}

using ImportUses = std::vector<std::pair<const StmtImport*, OrderedMap<std::string, std::string>>>;

OrderedMap<std::string, std::string>& usesFor(ImportUses& uses, const StmtImport* import) {
    for (auto& [key, value] : uses) {
        if (key == import) return value;
    }
    uses.push_back({import, {}});
    return uses.back().second;
}

}  // namespace

/** A library declaration's identity; a declaration without a module keeps its name. */
std::string StdlibInjector::identityKey(const std::optional<std::string>& module, const std::string& name) {
    return module ? *module + "::" + name : name;
}

/**
 * A declaration the compiler itself refers to by name keeps that name: a
 * `bridge` type or capability, a collection spec, the types derivers name.
 */
bool StdlibInjector::isCompilerKnown(const TopLevel& item, const std::string& name) {
    if (is<TopLevelBridge>(item)) return true;
    if (TopLevelPack* pack = as<TopLevelPack>(item); pack && pack->isBridge) return true;
    if (TopLevelSpec* spec = as<TopLevelSpec>(item); spec && spec->isBridge) return true;
    if (TopLevelDeco* deco = as<TopLevelDeco>(item); deco && deco->isBridge) return true;
    return implicitCollectionTypes().contains(name) || compilerNamedTypes().contains(name);
}

std::string StdlibInjector::itemIdentity(const TopLevel& item) {
    switch (item.kind()) {
        case TopLevelKind::Func: return "func:" + cast<TopLevelFunc>(item).decl.name;
        case TopLevelKind::FinDecl: return "fin:" + cast<TopLevelFinDecl>(item).name;
        case TopLevelKind::LetDecl: return "let:" + cast<TopLevelLetDecl>(item).name;
        case TopLevelKind::VarDecl: return "var:" + cast<TopLevelVarDecl>(item).name;
        case TopLevelKind::Pack: return "pack:" + cast<TopLevelPack>(item).name;
        case TopLevelKind::Enum: return "enum:" + cast<TopLevelEnum>(item).name;
        case TopLevelKind::Fail: return "fail:" + cast<TopLevelFail>(item).name;
        case TopLevelKind::Spec: return "spec:" + cast<TopLevelSpec>(item).name;
        case TopLevelKind::Deco: return "deco:" + cast<TopLevelDeco>(item).name;
        case TopLevelKind::Slot: return "slot:" + cast<TopLevelSlot>(item).name;
        case TopLevelKind::Meta: return "meta:" + cast<TopLevelMeta>(item).name;
        // Two modules can each implement a type at the same line and column.
        case TopLevelKind::Impl: {
            const TopLevelImpl& impl = cast<TopLevelImpl>(item);
            return "impl:" + (impl.declaringModule ? *impl.declaringModule : std::string()) + ":" + impl.typeName + ":" +
                   (impl.traitName ? *impl.traitName : std::string()) + ":" + str(impl.line) + ":" + str(impl.column);
        }
        case TopLevelKind::Bridge: {
            const TopLevelBridge& bridge = cast<TopLevelBridge>(item);
            std::vector<std::string> funcs;
            for (const BridgeSig& f : bridge.funcs) funcs.push_back(f.localName ? *f.localName : f.name);
            std::vector<std::string> values;
            for (const BridgeValue& v : bridge.values) values.push_back(v.name);
            return "bridge:" + bridge.target + ":" + join(funcs, ",") + ":" + join(values, ",");
        }
        // Kotlin's data-class toString: equal exactly when the items are.
        default: return structuralKey(item);
    }
}

/** Top-level items grouped by the type whose `::` block declared them (`Vec__zero` → `Vec`). */
const OrderedMap<std::string, std::vector<std::string>>& StdlibInjector::staticMembersByOwner() {
    if (!staticMembersStorage) {
        OrderedMap<std::string, std::vector<std::string>> grouped;
        for (const auto& [name, item] : index().items.entries) {
            if (name.find("__") == std::string::npos) continue;
            grouped.getOrPut(substringBeforeLast(name, "__"), {}).push_back(name);
        }
        staticMembersStorage = std::move(grouped);
    }
    return *staticMembersStorage;
}

// ---------------------------------------------------------------------------
// inject
// ---------------------------------------------------------------------------

/**
 * Returns [program] with every library item it references appended. [roots]
 * are names a library build exports: nothing in the program may call them,
 * yet they belong in it.
 */
Program StdlibInjector::inject(const Program& program, const OrderedSet<std::string>& roots /*= {}*/) {
    Index& idx = index();
    if (idx.items.empty() && idx.externs.empty() && idx.typeFunctionsByModule.empty() && idx.typeMacrosByModule.empty()) {
        return program;
    }
    std::vector<TypeFunctionDecl> importedFunctions = idx.alwaysTypeFunctions;
    append(importedFunctions, importedTypeFunctions(program));

    OrderedSet<std::string> shadowed;
    for (const std::string& name : userDeclaredNames(program).items) {
        if (!program.injectedNames.contains(name)) shadowed.add(name);
    }
    for (const std::string& name : shadowedByProgram.items) shadowed.add(name);
    OrderedSet<std::string> reachable = reachableModules(program);

    OrderedMap<std::string, TopLevel> visible = idx.implicitRootItems;
    for (const auto& [name, item] : importedItems(program).entries) visible.set(name, item);

    OrderedSet<std::string> referenced;
    // What the program's items name, in the order first named.
    OrderedSet<std::string> programNames;
    for (const TopLevel& item : program.items) {
        OrderedSet<std::string> names;
        collectNamesFromItem(item, names);
        for (const std::string& n : names.items) {
            referenced.add(n);
            programNames.add(n);
        }
        // A user macro may expand directly to an imported scope macro.
        if (TopLevelMeta* meta = as<TopLevelMeta>(item)) {
            for (const MacroArm& arm : meta->arms) {
                collectNamesFromExpr(arm.template_, referenced);
                for (const Expr& tail : arm.templateTail) collectNamesFromExpr(tail, referenced);
            }
        }
    }
    // Exported/core blocks are always injected; pull in whatever they reference.
    std::vector<TopLevel> alwaysInjected = idx.alwaysInjectedFor(program.moduleName);
    for (const TopLevel& item : alwaysInjected) collectNamesFromItem(item, referenced);
    for (const std::string& root : roots.items) referenced.add(root);
    OrderedSet<std::string> implicitReferenced;
    for (const std::string& name : referenced.items) {
        if (implicitCollectionTypes().contains(name) || implicitLiteralTargets().contains(name)) implicitReferenced.add(name);
    }
    {
        OrderedSet<std::string> retained;
        for (const std::string& name : referenced.items) {
            if (visible.contains(name)) retained.add(name);
        }
        referenced = std::move(retained);
    }
    for (const std::string& name : implicitReferenced.items) referenced.add(name);

    // A reference resolves where it is written: in the program (no module), or
    // in the library module whose declaration contains it.
    Walk walk;
    walk.reachable = reachable;
    walk.ownModule = program.moduleName;
    std::vector<Ref> frontier;
    for (const std::string& name : referenced.items) frontier.push_back(Ref{name, std::nullopt});
    for (const auto& [module, name] : rootDeclarations) frontier.push_back(Ref{name, module});
    std::vector<std::pair<TopLevel, std::vector<BodyImportBlock>>> bodyImports = bodyImportBlocks(program);
    append(frontier, seedBodyImports(bodyImports, walk));
    std::vector<std::pair<std::string, TypeTypeArm>> fileTypeMacros = importedTypeMacros(program);
    std::vector<std::pair<std::string, TypeTypeArm>> programBlockMacros;
    for (const auto& [item, blocks] : bodyImports) {
        for (const BodyImportBlock& block : blocks) append(programBlockMacros, block.typeLevel.macros);
    }
    {
        std::vector<std::pair<std::string, TypeTypeArm>> macros = fileTypeMacros;
        append(macros, programBlockMacros);
        append(frontier, templateRefs(macros));
    }
    while (!frontier.empty()) {
        std::vector<Ref> next;
        for (const Ref& ref : frontier) {
            if (!walk.seen.insert(ref.key()).second) continue;
            const std::string& name = ref.name;
            const std::optional<std::string>& scopeModule = ref.module;
            if (!scopeModule && shadowed.contains(name)) continue;
            // A library names only what its scope binds, and what the compiler itself knows.
            std::optional<TopLevel> item;
            if (scopeModule) {
                if (const TopLevel* bound = libraryScope(*scopeModule).get(name)) {
                    item = *bound;
                } else if (const TopLevel* known = idx.items.get(name); known && isCompilerKnown(*known, name)) {
                    item = *known;
                }
            } else {
                if (const TopLevel* seen = visible.get(name)) item = *seen;
                else if (const TopLevel* any = idx.items.get(name)) item = *any;
            }
            if (item) {
                std::optional<std::string> module = declaringModule(*item, name);
                std::string key = identityKey(module, name);
                if (scopeModule) walk.bindings.getOrPut(*scopeModule, {}).set(name, key);
                if (walk.injected.contains(key)) {
                    // Already present, but this module may use extensions the earlier reference could not see.
                    attachImplsForType(*item, name, walk, next, scopeModule);
                    continue;
                }
                walkAdd(walk, key, *item, module, next);
                attachImplsForType(*item, name, walk, next, scopeModule);
                attachStaticMembersForType(name, module, walk, next);
                continue;
            }
            attachImplsForType(std::nullopt, name, walk, next, scopeModule);
            if (!walk.externs.contains(name)) {
                if (const TopLevel* external = idx.externs.get(name)) walk.externs.set(name, *external);
            }
        }
        frontier = std::move(next);
        // A `reflect<*>.withAnnot<D>` loop enumerates every declaration decorated `@D`.
        if (frontier.empty()) frontier = enumeratedDeclarations(program, walk);
    }
    for (const auto& [key, item] : walk.injected.entries) {
        auto module = walk.moduleOf.find(key);
        if (!is<TopLevelImpl>(item) && module != walk.moduleOf.end() && module->second) injectedDeclarations.add(key);
    }
    // What blocks import at the type level, the program's and the libraries'.
    std::vector<const BlockTypeLevel*> blockTypeLevels;
    for (const auto& [item, blocks] : bodyImports) {
        for (const BodyImportBlock& block : blocks) blockTypeLevels.push_back(&block.typeLevel);
    }

    // Runtime declarations may depend on private compile-time type functions.
    OrderedSet<std::string> dependencyNames;
    for (const auto& [key, item] : walk.injected.entries) {
        for (const std::string& n : walk.namesOf.at(key).items) dependencyNames.add(n);
    }
    for (const TopLevel& item : alwaysInjected) collectNamesFromItem(item, dependencyNames);
    std::vector<TypeFunctionDecl> dependencyTypeFunctions;
    for (const std::string& name : dependencyNames.items) {
        if (const std::vector<TypeFunctionDecl>* functions = idx.typeFunctionsByName.get(name)) {
            append(dependencyTypeFunctions, *functions);
        }
    }
    std::vector<TypeFunctionDecl> fullyQualifiedTypeFunctions;
    for (const std::string& encodedName : programNames.items) {
        if (!ModuleQualifiedSymbol::isQualified(encodedName)) continue;
        std::string module = ModuleQualifiedSymbol::module(encodedName);
        std::string symbol = ModuleQualifiedSymbol::symbol(encodedName);
        if (!isExternallyImportable(module)) continue;
        if (const std::vector<TypeFunctionDecl>* functions = idx.typeFunctionsByModule.get(module)) {
            for (const TypeFunctionDecl& f : *functions) {
                if (f.name != symbol) continue;
                TypeFunctionDecl copy = f;
                copy.name = encodedName;
                fullyQualifiedTypeFunctions.push_back(std::move(copy));
            }
        }
    }
    // Injection runs more than once; the same declaration is kept once.
    std::vector<TypeFunctionDecl> allFunctions = program.typeFunctions;
    append(allFunctions, importedFunctions);
    append(allFunctions, dependencyTypeFunctions);
    append(allFunctions, fullyQualifiedTypeFunctions);
    for (const BlockTypeLevel* level : blockTypeLevels) append(allFunctions, level->functions);
    append(allFunctions, walk.blockTypeFunctions);
    std::vector<TypeFunctionDecl> typeFunctions = distinctByStructure(allFunctions);
    bool typeFunctionsChanged = typeFunctions.size() != program.typeFunctions.size();
    Identified identified = identifiedDeclarations(walk, visible, shadowed);
    // A type macro's template is its module's code, under the identities it was injected with.
    std::vector<TypeTypeArm> allMacros = program.typeMacroRules;
    append(allMacros, idx.alwaysTypeMacros);
    {
        std::vector<std::pair<std::string, TypeTypeArm>> moduleMacros = fileTypeMacros;
        append(moduleMacros, programBlockMacros);
        append(moduleMacros, walk.blockTypeMacros);
        for (const auto& [module, arm] : moduleMacros) {
            auto renamer = identified.renamers.find(module);
            if (renamer == identified.renamers.end()) {
                allMacros.push_back(arm);
            } else {
                TypeTypeArm copy = arm;
                copy.template_ = renamer->second->type(arm.template_);
                allMacros.push_back(std::move(copy));
            }
        }
    }
    std::vector<TypeTypeArm> typeMacros = distinctByStructure(allMacros);
    bool typeMacrosChanged = typeMacros.size() != program.typeMacroRules.size();

    if (walk.injected.empty() && walk.externs.empty() && alwaysInjected.empty() && !typeFunctionsChanged &&
        !typeMacrosChanged) {
        return program;
    }
    // Deduplicated by the identity each declaration now has.
    std::unordered_set<std::string> existingIdentities;
    for (const TopLevel& item : program.items) existingIdentities.insert(itemIdentity(item));
    std::vector<std::pair<TopLevel, TopLevel>> kept;
    for (const auto& [original, renamed] : identified.declarations) {
        if (existingIdentities.insert(itemIdentity(renamed)).second) kept.push_back({original, renamed});
    }
    std::vector<TopLevel> programItems = rewriteBodyImports(program, bodyImports, identified.hidden);
    std::vector<TopLevel> declarations;
    for (const auto& [original, renamed] : kept) declarations.push_back(renamed);
    std::vector<TopLevel> externValues;
    for (const auto& [name, item] : walk.externs.entries) externValues.push_back(item);
    std::vector<TopLevel> externDeclarations;
    for (const TopLevel& item : distinctByStructure(externValues)) {
        if (existingIdentities.insert(itemIdentity(item)).second) externDeclarations.push_back(item);
    }
    // Exported/core compile-time blocks are injected unconditionally.
    std::vector<TopLevel> alwaysDeclarations;
    for (const TopLevel& item : alwaysInjected) {
        if (existingIdentities.insert(itemIdentity(item)).second) alwaysDeclarations.push_back(item);
    }
    OrderedMap<std::string, std::string> injectedScopeTypeNamespaces;
    {
        std::vector<TopLevel> sources;
        for (const auto& [original, renamed] : kept) sources.push_back(original);
        append(sources, alwaysDeclarations);
        for (const TopLevel& declaration : sources) {
            std::optional<std::string> name = typeDeclarationName(declaration);
            if (!name) continue;
            const std::vector<ScopeTypeExport>* exports = idx.scopeTypesByShortName.get(*name);
            if (exports == nullptr) continue;
            for (const ScopeTypeExport& e : *exports) {
                if (same(e.declaration, declaration)) {
                    injectedScopeTypeNamespaces.set(*name, e.qualifier);
                    break;
                }
            }
        }
    }
    if (declarations.empty() && externDeclarations.empty() && alwaysDeclarations.empty() && !typeFunctionsChanged &&
        !typeMacrosChanged) {
        return program;
    }
    Program out = program;
    std::vector<TopLevel> added = declarations;
    append(added, externDeclarations);
    append(added, alwaysDeclarations);
    out.items = programItems;
    append(out.items, added);
    out.typeFunctions = std::move(typeFunctions);
    out.typeMacroRules = std::move(typeMacros);
    for (const std::string& op : idx.allInfixOperators.items) out.infixOperators.add(op);
    append(out.infixMacros, idx.allInfixMacros);
    for (const auto& [name, qualifier] : injectedScopeTypeNamespaces.entries) out.scopeTypeNamespaces.set(name, qualifier);
    Program addedProgram;
    addedProgram.items = added;
    for (const std::string& name : userDeclaredNames(addedProgram).items) out.injectedNames.add(name);
    return ScopeAccessRewriter::rewrite(arena, out);
}

// ---------------------------------------------------------------------------
// The dependency walk
// ---------------------------------------------------------------------------

/** Kotlin `Walk.add`: records an injected declaration and queues what it names. */
void StdlibInjector::walkAdd(Walk& walk, const std::string& key, const TopLevel& item,
                             const std::optional<std::string>& module, std::vector<Ref>& next) {
    // The program holds its own module's declarations already.
    if (module && module == walk.ownModule) return;
    walk.injected.set(key, item);
    walk.moduleOf[key] = module;
    OrderedSet<std::string> names;
    collectNamesFromItem(item, names);
    for (const std::string& n : names.items) next.push_back(Ref{n, module});
    walk.namesOf[key] = std::move(names);
    if (module) bindLibraryBlockImports(item, *module, walk, next);
}

/** Decorators some `reflect<*>.withAnnot<D>` in [items] enumerates. */
void StdlibInjector::enumeratedDecorators(const std::vector<TopLevel>& items, OrderedSet<std::string>& into) {
    AstRewriter finder(arena);
    finder.onExpr = [&](AstRewriter& r, const Expr& e) -> Expr {
        if (ExprCall* call = as<ExprCall>(e); call && call->callee == "__withAnnot") {
            if (call->typeArgs.size() == 1) {
                if (TypeRefNamed* named = as<TypeRefNamed>(call->typeArgs[0])) into.add(substringAfterLast(named->name, "__"));
            }
        }
        return r.baseExpr(e);
    };
    for (const TopLevel& item : items) {
        switch (item.kind()) {
            case TopLevelKind::Func: finder.stmts(cast<TopLevelFunc>(item).decl.body); break;
            case TopLevelKind::Impl:
                for (const FuncDecl& m : cast<TopLevelImpl>(item).methods) finder.stmts(m.body);
                break;
            case TopLevelKind::Test: finder.stmts(cast<TopLevelTest>(item).body); break;
            default: break;
        }
    }
}

/** References to every declaration a `withAnnot` loop enumerates, from reachable modules, not yet brought in. */
std::vector<StdlibInjector::Ref> StdlibInjector::enumeratedDeclarations(const Program& program, Walk& walk) {
    if (!walk.programEnumerated) {
        enumeratedDecorators(program.items, walk.enumerated);
        walk.programEnumerated = true;
    }
    if (walk.enumeratedThrough < walk.injected.size()) {
        std::vector<TopLevel> fresh;
        for (size_t i = walk.enumeratedThrough; i < walk.injected.entries.size(); i++) {
            fresh.push_back(walk.injected.entries[i].second);
        }
        enumeratedDecorators(fresh, walk.enumerated);
        walk.enumeratedThrough = walk.injected.size();
    }
    std::vector<Ref> refs;
    for (const std::string& decorator : walk.enumerated.items) {
        const std::vector<std::pair<std::string, std::string>>* annotated = index().annotatedBy.get(decorator);
        if (annotated == nullptr) continue;
        for (const auto& [module, name] : *annotated) {
            if (!walk.reachable.contains(module) || module == walk.ownModule) continue;
            Ref ref{name, module};
            if (!walk.seen.count(ref.key())) refs.push_back(std::move(ref));
        }
    }
    return refs;
}

/**
 * A library block's imports bind in that block and the blocks it encloses.
 * What the block uses and they provide enters the walk, and Walk::blockUses
 * records what each name resolved to.
 */
void StdlibInjector::bindLibraryBlockImports(const TopLevel& item, const std::string& module, Walk& walk,
                                             std::vector<Ref>& next) {
    forEachImportingBlock(item, [&](const std::vector<StmtImport*>& imports, const std::vector<Stmt>& body) {
        std::vector<ImportRequest> requests;
        for (StmtImport* import : imports) append(requests, importRequests(*import->use));
        OrderedMap<std::string, TopLevel> bindings = libraryImportBindings(requests);
        BlockTypeLevel typeLevel = blockTypeLevel(typeLevelImportedBy(requests, [](const std::string&) { return true; }));
        append(walk.blockTypeFunctions, typeLevel.functions);
        append(walk.blockTypeMacros, typeLevel.macros);
        append(next, templateRefs(typeLevel.macros));
        {
            ImportUses& typeBlocks = walk.blockTypeRenames.getOrPut(module, {});
            for (StmtImport* import : imports) {
                OrderedMap<std::string, std::string>& renames = usesFor(typeBlocks, import);
                for (const auto& [name, identity] : typeLevel.renames.entries) renames.set(name, identity);
            }
        }
        OrderedSet<std::string> names;
        for (const Stmt& s : body) collectNamesFromStmt(s, names);
        OrderedMap<std::string, std::string> used;
        for (const std::string& name : names.items) {
            const TopLevel* bound = bindings.get(name);
            if (bound == nullptr) continue;
            TopLevel declaration = *bound;
            std::optional<std::string> home = declaringModule(declaration, name);
            std::string key = identityKey(home, name);
            used.set(name, key);
            if (walk.injected.contains(key)) continue;
            walkAdd(walk, key, declaration, home, next);
            attachImplsForType(declaration, name, walk, next, std::nullopt);
            attachStaticMembersForType(name, home, walk, next);
        }
        ImportUses& blocks = walk.blockUses.getOrPut(module, {});
        for (StmtImport* import : imports) {
            OrderedMap<std::string, std::string>& uses = usesFor(blocks, import);
            for (const auto& [name, key] : used.entries) uses.set(name, key);
        }
    });
}

/**
 * Gives each injected library declaration its identity: one the program can
 * name keeps its name; any other becomes `module__path__Name`, and every
 * reference its module made is renamed to match.
 */
StdlibInjector::Identified StdlibInjector::identifiedDeclarations(Walk& walk, const OrderedMap<std::string, TopLevel>& visible,
                                                                  const OrderedSet<std::string>& shadowed) {
    std::unordered_set<std::string> nameable;
    for (const auto& [name, item] : visible.entries) {
        if (shadowed.contains(name)) continue;
        if (std::optional<std::string> module = declaringModule(item, name)) nameable.insert(identityKey(module, name));
    }
    std::unordered_map<std::string, std::string> hidden;
    OrderedMap<std::string, OrderedMap<std::string, std::string>> renames;
    for (const auto& [key, item] : walk.injected.entries) {
        if (is<TopLevelImpl>(item)) continue;
        auto moduleOf = walk.moduleOf.find(key);
        if (moduleOf == walk.moduleOf.end() || !moduleOf->second) continue;
        const std::string module = *moduleOf->second;
        std::string name = substringAfter(key, "::");
        // A name the program declares is the program's, even for a compiler-known declaration.
        bool known = isCompilerKnown(item, name) && !shadowed.contains(name);
        if (name.find("__") != std::string::npos || nameable.count(key) || index().scopeTypesByShortName.contains(name) ||
            known) {
            continue;
        }
        std::string identity = replaceAll(module, ".", "__") + "__" + name;
        hidden[key] = identity;
        renames.getOrPut(module, {}).set(name, identity);
    }
    // A module's own declarations come first, as in its scope.
    for (const auto& [module, used] : walk.bindings.entries) {
        OrderedMap<std::string, std::string>& names = renames.getOrPut(module, {});
        for (const auto& [name, key] : used.entries) {
            auto identity = hidden.find(key);
            if (identity == hidden.end()) continue;
            if (!names.contains(name)) names.set(name, identity->second);
        }
    }
    // Within a library block, a name its imports bind means what it resolved to there.
    std::unordered_map<std::string, DeclarationRenamer::BodyImports> blockRenames;
    auto addBlockRenames = [&](const std::string& module) {
        if (blockRenames.count(module)) return;
        DeclarationRenamer::BodyImports bodyImports;
        const ImportUses* uses = walk.blockUses.get(module);
        const ImportUses* types = walk.blockTypeRenames.get(module);
        std::vector<const StmtImport*> imports;
        auto addImport = [&](const StmtImport* import) {
            if (std::find(imports.begin(), imports.end(), import) == imports.end()) imports.push_back(import);
        };
        if (uses) {
            for (const auto& [import, map] : *uses) addImport(import);
        }
        if (types) {
            for (const auto& [import, map] : *types) addImport(import);
        }
        for (const StmtImport* import : imports) {
            DeclarationRenamer::Names bound;
            if (types) {
                for (const auto& [key, map] : *types) {
                    if (key != import) continue;
                    for (const auto& [name, identity] : map.entries) bound.set(name, identity);
                }
            }
            if (uses) {
                for (const auto& [key, map] : *uses) {
                    if (key != import) continue;
                    for (const auto& [name, identityKeyed] : map.entries) {
                        auto identity = hidden.find(identityKeyed);
                        bound.set(name, identity != hidden.end() ? identity->second : name);
                    }
                }
            }
            bodyImports[import] = std::move(bound);
        }
        blockRenames[module] = std::move(bodyImports);
    };
    for (const auto& [module, uses] : walk.blockUses.entries) addBlockRenames(module);
    for (const auto& [module, types] : walk.blockTypeRenames.entries) addBlockRenames(module);
    Identified out;
    auto addRenamer = [&](const std::string& module) {
        if (out.renamers.count(module)) return;
        const OrderedMap<std::string, std::string>* names = renames.get(module);
        auto blocks = blockRenames.find(module);
        out.renamers[module] = std::make_shared<DeclarationRenamer>(
            arena, names ? *names : DeclarationRenamer::Names{},
            blocks != blockRenames.end() ? blocks->second : DeclarationRenamer::BodyImports{});
    };
    for (const auto& [module, names] : renames.entries) {
        if (!names.empty()) addRenamer(module);
    }
    for (const auto& [module, blocks] : blockRenames) addRenamer(module);
    out.hidden = hidden;
    for (const auto& [key, item] : walk.injected.entries) {
        auto moduleOf = walk.moduleOf.find(key);
        std::optional<std::string> module = moduleOf != walk.moduleOf.end() ? moduleOf->second : std::nullopt;
        TopLevel renamed = item;
        if (module) {
            auto renamer = out.renamers.find(*module);
            if (renamer != out.renamers.end()) renamed = renamer->second->item(item);
        }
        if (!is<TopLevelImpl>(item) && module) {
            if (std::optional<std::string> name = declaredNameOf(renamed)) injectedIdentities.set(key, *name);
        }
        out.declarations.push_back({item, renamed});
    }
    return out;
}

/** Every block in the program's own items that imports, with what it binds. */
std::vector<std::pair<TopLevel, std::vector<StdlibInjector::BodyImportBlock>>> StdlibInjector::bodyImportBlocks(
    const Program& program) {
    std::vector<std::pair<TopLevel, std::vector<BodyImportBlock>>> blocks;
    for (const TopLevel& item : program.items) {
        std::vector<BodyImportBlock> found;
        forEachImportingBlock(item, [&](const std::vector<StmtImport*>& imports, const std::vector<Stmt>& body) {
            std::vector<ImportRequest> requests;
            for (StmtImport* import : imports) append(requests, importRequests(*import->use));
            BodyImportBlock block;
            block.imports = imports;
            block.bindings = itemsImportedBy(requests, nullptr);
            block.typeLevel = blockTypeLevel(typeLevelImportedBy(requests, nullptr));
            OrderedSet<std::string> names;
            for (const Stmt& s : body) collectNamesFromStmt(s, names);
            for (const std::string& name : names.items) {
                if (const TopLevel* bound = block.bindings.get(name)) {
                    TopLevel declaration = *bound;
                    block.used.set(name, identityKey(declaringModule(declaration, name), name));
                }
            }
            found.push_back(std::move(block));
        });
        if (!found.empty()) blocks.push_back({item, std::move(found)});
    }
    return blocks;
}

/** What a block's imports bind and the block uses enters the walk as the program's own references would. */
std::vector<StdlibInjector::Ref> StdlibInjector::seedBodyImports(
    const std::vector<std::pair<TopLevel, std::vector<BodyImportBlock>>>& blocks, Walk& walk) {
    std::vector<Ref> next;
    for (const auto& [item, list] : blocks) {
        for (const BodyImportBlock& block : list) {
            for (const auto& [name, key] : block.used.entries) {
                if (walk.injected.contains(key)) continue;
                TopLevel declaration = *block.bindings.get(name);
                std::optional<std::string> module = declaringModule(declaration, name);
                walkAdd(walk, key, declaration, module, next);
                attachImplsForType(declaration, name, walk, next, std::nullopt);
                attachStaticMembersForType(name, module, walk, next);
            }
        }
    }
    return next;
}

/** The program's items with each body import resolved in place. */
std::vector<TopLevel> StdlibInjector::rewriteBodyImports(
    const Program& program, const std::vector<std::pair<TopLevel, std::vector<BodyImportBlock>>>& blocks,
    const std::unordered_map<std::string, std::string>& hidden) {
    if (blocks.empty()) return program.items;
    DeclarationRenamer::BodyImports bound;
    for (const auto& [item, list] : blocks) {
        for (const BodyImportBlock& block : list) {
            DeclarationRenamer::Names renames = block.typeLevel.renames;
            for (const auto& [name, key] : block.used.entries) {
                auto identity = hidden.find(key);
                if (identity != hidden.end()) renames.set(name, identity->second);
            }
            for (StmtImport* import : block.imports) bound[import] = renames;
        }
    }
    DeclarationRenamer renamer(arena, {}, std::move(bound));
    std::vector<TopLevel> out;
    for (const TopLevel& item : program.items) {
        bool importing = false;
        for (const auto& [blockItem, list] : blocks) {
            if (same(blockItem, item)) {
                importing = true;
                break;
            }
        }
        out.push_back(importing ? renamer.item(item) : item);
    }
    return out;
}

/** Pulls in the members a type's `::` block declared, from the owner's own module. */
void StdlibInjector::attachStaticMembersForType(const std::string& typeName, const std::optional<std::string>& module,
                                                Walk& walk, std::vector<Ref>& next) {
    if (!module) return;
    const OrderedMap<std::string, TopLevel>* declarations = index().modules.get(*module);
    if (declarations == nullptr) return;
    const TopLevel* owner = declarations->get(typeName);
    if (owner == nullptr || !(is<TopLevelPack>(*owner) || is<TopLevelEnum>(*owner) || is<TopLevelSpec>(*owner))) return;
    const std::vector<std::string>* members = staticMembersByOwner().get(typeName);
    if (members == nullptr) return;
    for (const std::string& member : *members) {
        const TopLevel* item = declarations->get(member);
        if (item == nullptr) continue;
        std::string key = identityKey(module, member);
        if (!walk.injected.contains(key)) walkAdd(walk, key, *item, module, next);
    }
}

/** Member and method names a library [module]'s own source uses. */
const OrderedSet<std::string>& StdlibInjector::memberNamesUsedBy(const std::string& module) {
    auto found = moduleMemberNames.find(module);
    if (found != moduleMemberNames.end()) return found->second;
    OrderedSet<std::string> names;
    OrderedSet<std::string>* saved = memberSink;
    memberSink = &names;
    try {
        for (const Program& program : programs) {
            if (program.moduleName != module) continue;
            for (const TopLevel& item : program.items) collectNamesFromItem(item, names);
        }
    } catch (...) {
        memberSink = saved;
        throw;
    }
    memberSink = saved;
    return moduleMemberNames.emplace(module, std::move(names)).first->second;
}

/** The modules a library [module]'s own imports reach. */
const OrderedSet<std::string>& StdlibInjector::importedModulesOf(const std::string& module) {
    auto found = moduleImportReach.find(module);
    if (found != moduleImportReach.end()) return found->second;
    OrderedSet<std::string> reach;
    if (const std::vector<ImportRequest>* requests = index().importsOfModule.get(module)) {
        for (const ImportRequest& request : *requests) {
            if (std::optional<std::pair<std::string, std::string>> selected = resolveSelectedLibraryPath(request.path)) {
                reach.add(selected->first);
            } else {
                for (const std::string& m : modulesForPath(request.path)) reach.add(m);
            }
        }
    }
    return moduleImportReach.emplace(module, std::move(reach)).first->second;
}

/**
 * Pulls in the impls on a type. An impl belongs to [type] when its own module
 * names that declaration by the impl's type name.
 */
void StdlibInjector::attachImplsForType(const std::optional<TopLevel>& type, const std::string& typeName, Walk& walk,
                                        std::vector<Ref>& next, const std::optional<std::string>& scope /*= std::nullopt*/) {
    OrderedSet<std::string> keys{typeName, normalizedTypeName(typeName)};
    // The type's own module implements it wherever the type goes.
    std::optional<std::string> home;
    if (type) home = declaringModule(*type, typeName);
    // A library module calls the free extensions of the modules it imports, but only the ones it names.
    OrderedSet<std::string> scopeImports;
    if (scope) scopeImports = importedModulesOf(*scope);
    for (const std::string& keyName : keys.items) {
        const std::vector<TopLevelImpl*>* found = index().implsByType.get(keyName);
        if (found == nullptr) continue;
        std::vector<TopLevelImpl*> impls = *found;
        auto cached = implKeysByType.find(keyName);
        if (cached == implKeysByType.end()) {
            std::vector<std::string> made;
            for (TopLevelImpl* impl : impls) made.push_back(implKey(*impl));
            cached = implKeysByType.emplace(keyName, std::move(made)).first;
        }
        std::vector<std::string> implKeys = cached->second;
        for (size_t at = 0; at < impls.size(); at++) {
            TopLevelImpl* impl = impls[at];
            const std::optional<std::string>& declaring = impl->declaringModule;
            bool inReach = !declaring || walk.reachable.contains(*declaring) || declaring == home;
            if (!inReach && scope && impl->isExtension && declaring &&
                (scopeImports.contains(*declaring) || *declaring == *scope)) {
                const OrderedSet<std::string>& used = memberNamesUsedBy(*scope);
                for (const FuncDecl& m : impl->methods) {
                    if (used.contains(m.name)) {
                        inReach = true;
                        break;
                    }
                }
            }
            bool attached = inReach;
            if (attached && type) {
                std::optional<TopLevel> target = implTarget(*impl);
                attached = !target || same(*target, *type);
            }
            if (!attached) continue;
            const std::string& key = implKeys[at];
            if (!walk.injected.contains(key)) walkAdd(walk, key, TopLevel(impl), declaring, next);
        }
    }
}

/** An impl's identity in a walk, member names included (a multi-operator impl shares one position). */
std::string StdlibInjector::implKey(const TopLevelImpl& impl) {
    std::vector<std::string> members;
    for (const FuncDecl& m : impl.methods) members.push_back(m.name);
    return "impl::" + (impl.declaringModule ? *impl.declaringModule : std::string("null")) + "::" +
           normalizedTypeName(impl.typeName) + "::" + (impl.traitName ? *impl.traitName : std::string()) + "::" +
           str(impl.line) + ":" + str(impl.column) + "::" + join(members, ",");
}

/** The declaration an impl's module means by the impl's type name. */
std::optional<TopLevel> StdlibInjector::implTarget(const TopLevelImpl& impl) {
    if (!impl.declaringModule) return std::nullopt;
    std::string typeName = substringBefore(impl.typeName, "<");
    if (const TopLevel* scoped = libraryScope(*impl.declaringModule).get(typeName)) return *scoped;
    if (const TopLevel* any = index().items.get(typeName)) return *any;
    return std::nullopt;
}

}  // namespace azora
