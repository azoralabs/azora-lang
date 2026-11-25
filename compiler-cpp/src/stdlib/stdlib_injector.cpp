// Port of stdlib/StdlibInjector.kt, part 1: construction, the index, and
// what an import makes visible.
#include "stdlib/stdlib_injector.h"

#include "base/collections.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"
#include "frontend/lexer.h"
#include "frontend/macro_expander.h"
#include "frontend/parser.h"
#include "stdlib/az_stdlib.h"

#include <algorithm>
#include <deque>

namespace azora {

bool StdlibInjector::debugInject = false;

namespace {

bool isWordChar(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'; }

/** Kotlin `Regex("[a-z][a-z0-9]*(?:_[a-z][a-z0-9]*)+").matches(stem)`. */
bool isSnakeCaseStem(const std::string& stem) {
    size_t i = 0;
    auto word = [&]() {
        if (i >= stem.size() || !(stem[i] >= 'a' && stem[i] <= 'z')) return false;
        i++;
        while (i < stem.size() && ((stem[i] >= 'a' && stem[i] <= 'z') || (stem[i] >= '0' && stem[i] <= '9'))) i++;
        return true;
    };
    if (!word()) return false;
    int parts = 0;
    while (i < stem.size()) {
        if (stem[i] != '_') return false;
        i++;
        if (!word()) return false;
        parts++;
    }
    return parts >= 1;
}

/** Kotlin `Regex("_([a-z])").replace(stem) { it.groupValues[1].uppercase() }`. */
std::string camelFromSnake(const std::string& stem) {
    std::string out;
    for (size_t i = 0; i < stem.size(); i++) {
        if (stem[i] == '_' && i + 1 < stem.size() && stem[i + 1] >= 'a' && stem[i + 1] <= 'z') {
            out += (char)(stem[i + 1] - 'a' + 'A');
            i++;
        } else {
            out += stem[i];
        }
    }
    return out;
}

std::string joinWith(const std::vector<std::string>& parts, const std::string& separator, size_t from, size_t to) {
    std::string out;
    for (size_t i = from; i < to; i++) {
        if (i > from) out += separator;
        out += parts[i];
    }
    return out;
}

/** The sorted, comma-joined exclusions in a visited key (Kotlin `without.sorted().joinToString(",")`). */
std::string sortedJoined(const OrderedSet<std::string>& without) {
    std::vector<std::string> items = without.items;
    std::sort(items.begin(), items.end(), [](const std::string& a, const std::string& b) { return compareUtf16(a, b) < 0; });
    return join(items, ",");
}

std::string visitedKey(const StdlibInjector::ImportRequest& request) {
    return request.path + "::" + (request.selected ? *request.selected : std::string("*")) + "::" + sortedJoined(request.without);
}

void importLeaves(const ImportSpec& spec, std::vector<const ImportSpec*>& out) {
    if (spec.selector.kind == SelectorKind::Group) {
        for (const ImportSpec& member : spec.selector.members) importLeaves(member, out);
    } else {
        out.push_back(&spec);
    }
}

}  // namespace

// ---------------------------------------------------------------------------
// Dumps of the results the stage-3 comparison writes
// ---------------------------------------------------------------------------

void dumpValue(Dumper& d, const UnresolvedContractAccess& v, int ind) {
    d.open("UnresolvedContractAccess");
    d.field("name", ind), dumpValue(d, v.name, ind + 2);
    d.field("module", ind), dumpValue(d, v.module, ind + 2);
    d.field("line", ind), dumpValue(d, v.line, ind + 2);
    d.field("column", ind), dumpValue(d, v.column, ind + 2);
    d.field("length", ind), dumpValue(d, v.length, ind + 2);
    d.close(ind);
}

void dumpValue(Dumper& d, const UnresolvedTypeAccess& v, int ind) {
    d.open("UnresolvedTypeAccess");
    d.field("name", ind), dumpValue(d, v.name, ind + 2);
    d.field("module", ind), dumpValue(d, v.module, ind + 2);
    d.field("line", ind), dumpValue(d, v.line, ind + 2);
    d.field("column", ind), dumpValue(d, v.column, ind + 2);
    d.field("length", ind), dumpValue(d, v.length, ind + 2);
    d.close(ind);
}

void dumpValue(Dumper& d, const TypeAccessValidation& v, int ind) {
    d.open("TypeAccessValidation");
    d.field("errors", ind), dumpValue(d, v.errors, ind + 2);
    d.field("unresolved", ind), dumpValue(d, v.unresolved, ind + 2);
    d.close(ind);
}

// ---------------------------------------------------------------------------
// Construction
// ---------------------------------------------------------------------------

void StdlibInjector::initialize(std::vector<Program> rawPrograms, OrderedMap<std::string, std::string> overrides,
                                std::vector<SkippedSource> skippedSources) {
    configOverrides = std::move(overrides);
    skipped = std::move(skippedSources);
    // Declaration-name macros resolved first, so the index registers the
    // derived names rather than the unexpanded `@foreignName(…)`.
    programs = MacroExpander::expandDeclarationNames(arena, rawPrograms);
}

// static
std::shared_ptr<StdlibInjector> StdlibInjector::standard() {
    static std::shared_ptr<StdlibInjector> instance = [] {
        std::shared_ptr<StdlibInjector> injector = std::make_shared<StdlibInjector>();
        injector->initialize(AzStdlib::loadPrograms(), {}, {});
        return injector;
    }();
    return instance;
}

// static
std::shared_ptr<StdlibInjector> StdlibInjector::create(const std::vector<LibrarySource>& additionalSources /*= {}*/,
                                                       const OrderedMap<std::string, std::string>& overrides /*= {}*/) {
    if (additionalSources.empty() && overrides.empty()) return standard();
    // Seeded with the standard library's compile-time lists, so a package
    // source can iterate `Numbers` as a stdlib file does.
    AzStdlib::loadPrograms();
    TypeListEnv typeListEnv = AzStdlib::comptimeLists();
    EnumEnv enumEnv = AzStdlib::declaredEnums();
    TypeListScopes listScopes = AzStdlib::comptimeListScopes();
    std::shared_ptr<StdlibInjector> injector = std::make_shared<StdlibInjector>();
    // A source that cannot be read is not a library; its failure is kept and
    // reported by unusableLibraries() against what the entry reaches.
    std::vector<SkippedSource> skippedSources;
    std::vector<Program> additionalPrograms;
    for (const LibrarySource& library : additionalSources) {
        std::optional<Program> program;
        try {
            Parser parser(injector->arena, Lexer(library.source).tokenize(), &typeListEnv, &enumEnv, false, &listScopes);
            program = parser.parse();
        } catch (const CompileFailure& error) {
            skippedSources.push_back({library.path, declaredModuleIn(library.source),
                                      "Failed to parse library source '" + library.path + "': " + error.message});
            continue;
        }
        if (library.validateModulePath) {
            try {
                checkFileMatchesModule(library.path, program->moduleName);
            } catch (const CompileFailure& error) {
                skippedSources.push_back({library.path, program->moduleName, error.message});
                continue;
            }
        }
        additionalPrograms.push_back(std::move(*program));
    }
    std::vector<Program> all = AzStdlib::loadPrograms();
    all.insert(all.end(), additionalPrograms.begin(), additionalPrograms.end());
    injector->initialize(std::move(all), overrides, std::move(skippedSources));
    return injector;
}

/**
 * The module a source declares, read without parsing it: Kotlin's
 * `(?m)^[ \t]*(?:(?:export|exposed|confined|protected)[ \t]+)*module[ \t]+([A-Za-z_][\w.]*)`.
 */
// static
std::optional<std::string> StdlibInjector::declaredModuleIn(const std::string& source) {
    static const char* const MODIFIERS[] = {"export", "exposed", "confined", "protected"};
    auto blank = [&](size_t i) { return i < source.size() && (source[i] == ' ' || source[i] == '\t'); };
    auto word = [&](size_t i, const char* w) { return source.compare(i, std::strlen(w), w) == 0; };
    // Tries a match at line start [at]: modifiers are greedy but give back, so
    // try every count of them before `module`.
    std::function<std::optional<std::string>(size_t)> tryAt = [&](size_t i) -> std::optional<std::string> {
        if (word(i, "module") && blank(i + 6)) {
            size_t j = i + 6;
            while (blank(j)) j++;
            if (j < source.size() && (std::isalpha((unsigned char)source[j]) || source[j] == '_')) {
                size_t start = j++;
                while (j < source.size() && (isWordChar(source[j]) || source[j] == '.')) j++;
                return source.substr(start, j - start);
            }
        }
        for (const char* modifier : MODIFIERS) {
            size_t n = std::strlen(modifier);
            if (word(i, modifier) && blank(i + n)) {
                size_t j = i + n;
                while (blank(j)) j++;
                if (std::optional<std::string> found = tryAt(j)) return found;
            }
        }
        return std::nullopt;
    };
    size_t lineStart = 0;
    while (lineStart <= source.size()) {
        size_t i = lineStart;
        while (blank(i)) i++;
        if (std::optional<std::string> found = tryAt(i)) return found;
        // The next line start: after \n, \r (not inside \r\n), U+0085, U+2028, U+2029.
        size_t k = lineStart;
        size_t next = std::string::npos;
        while (k < source.size()) {
            unsigned char c = (unsigned char)source[k];
            if (c == '\n') {
                next = k + 1;
                break;
            }
            if (c == '\r') {
                next = (k + 1 < source.size() && source[k + 1] == '\n') ? k + 2 : k + 1;
                break;
            }
            if (c == 0xC2 && k + 1 < source.size() && (unsigned char)source[k + 1] == 0x85) {
                next = k + 2;
                break;
            }
            if (c == 0xE2 && k + 2 < source.size() && (unsigned char)source[k + 1] == 0x80 &&
                ((unsigned char)source[k + 2] == 0xA8 || (unsigned char)source[k + 2] == 0xA9)) {
                next = k + 3;
                break;
            }
            k++;
        }
        if (next == std::string::npos || next >= source.size()) break;
        lineStart = next;
    }
    return std::nullopt;
}

/**
 * Enforces that a source file's path agrees with its declared module. A `src`
 * directory is a module root and never appears in a module path; a snake_case
 * file stem may implement the camelCase module; a folder-index `b/b.az` stands
 * for `….b`. Fails with IllegalArgument when the path cannot denote the module.
 */
// static
void StdlibInjector::checkFileMatchesModule(const std::string& path, const std::optional<std::string>& moduleName) {
    if (!moduleName) return;
    std::string normalized = replaceAll(path, "\\", "/");
    normalized = removeSuffix(removeSuffix(normalized, ".azora"), ".az");
    std::vector<std::string> parts;
    for (const std::string& part : split(normalized, "/")) {
        if (!part.empty() && part != "." && part != "src") parts.push_back(part);
    }
    if (parts.empty()) return;
    const std::string& stem = parts.back();
    std::vector<std::string> segments = parts;
    if (isSnakeCaseStem(stem)) segments.back() = camelFromSnake(stem);
    std::vector<std::string> moduleSegments = split(*moduleName, ".");
    size_t s = segments.size();
    size_t m = moduleSegments.size();
    auto tailEquals = [](const std::vector<std::string>& a, size_t aFrom, const std::vector<std::string>& b, size_t bFrom,
                         size_t count) {
        for (size_t i = 0; i < count; i++) {
            if (a[aFrom + i] != b[bFrom + i]) return false;
        }
        return true;
    };
    // The path's tail spells the whole module.
    if (s >= m && tailEquals(segments, s - m, moduleSegments, 0, m)) return;
    // The path spells the end of the module; the rest is the package prefix.
    if (s <= m && tailEquals(segments, 0, moduleSegments, m - s, s)) return;
    // Folder-index `b/b.az` denotes `….b`.
    if (s >= 2 && s <= m + 1 && segments[s - 1] == segments[s - 2] &&
        tailEquals(segments, 0, moduleSegments, m - (s - 1), s - 1)) {
        return;
    }
    // Absolute folder-index form.
    if (s >= m + 1 && segments[s - 1] == segments[s - 2] && tailEquals(segments, s - 1 - m, moduleSegments, 0, m)) return;
    size_t take = std::min(s, m);
    std::vector<std::string> expected(moduleSegments.end() - (std::ptrdiff_t)take, moduleSegments.end());
    failArgument("file path '" + path + "' does not match module '" + *moduleName + "': a file declaring 'module " +
                 *moduleName + "' must be located at '" + join(expected, "/") + ".az' relative to its source root");
}

/**
 * Evaluates an `exposed if COND` condition against the boolean overrides: none
 * and `true` keep the export; anything else drops it.
 */
bool StdlibInjector::evalExportIf(const Expr& cond, const OrderedMap<std::string, bool>& boolOverrides) {
    if (!cond) return true;
    if (ExprBoolLiteral* literal = as<ExprBoolLiteral>(cond)) return literal->value;
    if (ExprIdentifier* identifier = as<ExprIdentifier>(cond)) {
        const bool* value = boolOverrides.get(identifier->name);
        return value ? *value : false;
    }
    return false;
}

std::vector<TopLevel> StdlibInjector::Index::alwaysInjectedFor(const std::optional<std::string>& module) const {
    if (!module) return alwaysInjectedItems;
    std::vector<TopLevel> out;
    for (size_t i = 0; i < alwaysInjectedItems.size(); i++) {
        if (alwaysInjectedModules[i] != *module) out.push_back(alwaysInjectedItems[i]);
    }
    return out;
}

std::vector<StdlibInjector::ImportRequest> StdlibInjector::importRequests(const TopLevelUseImport& item) {
    std::vector<const ImportSpec*> leaves;
    for (const ImportSpec& spec : item.specs) importLeaves(spec, leaves);
    std::vector<ImportRequest> out;
    for (const ImportSpec* spec : leaves) {
        ImportRequest request;
        request.path = spec->path;
        if (spec->selector.kind == SelectorKind::All) request.selected = "*";
        for (const std::string& w : spec->without) request.without.add(w);
        out.push_back(std::move(request));
    }
    return out;
}

std::string StdlibInjector::normalizedTypeName(const std::string& name) {
    return substringAfter(substringBefore(name, "<"), "__");
}

StdlibInjector::Index& StdlibInjector::index() {
    if (!indexStorage) indexStorage = std::make_unique<Index>(buildIndex());
    return *indexStorage;
}

/** The library module providing [name] ("std.math"), or none - used for error hints. */
std::optional<std::string> StdlibInjector::moduleOf(const std::string& name) {
    const std::string* module = index().moduleOfName.get(name);
    return module ? std::optional<std::string>(*module) : std::nullopt;
}

/** Every decorator the loaded libraries declare, by name. */
OrderedMap<std::string, LibraryDecorator> StdlibInjector::libraryDecorators() {
    OrderedMap<std::string, LibraryDecorator> out;
    Index& idx = index();
    for (const auto& [name, item] : idx.items.entries) {
        if (!is<TopLevelDeco>(item)) continue;
        const std::string* module = idx.moduleOfName.get(name);
        if (module == nullptr) continue;
        bool alwaysOn = std::find(idx.alwaysOnModules.begin(), idx.alwaysOnModules.end(), *module) != idx.alwaysOnModules.end();
        out.set(name, LibraryDecorator{*module, alwaysOn});
    }
    return out;
}

/** How [name] is spelled when it is a member of a scope an imported module declares: `shapes::area`. */
std::vector<std::string> StdlibInjector::importedScopeMembers(const std::string& name, const Program& program) {
    OrderedMap<std::string, TopLevel> visible = index().implicitRootItems;
    for (const auto& [key, item] : importedItems(program).entries) visible.set(key, item);
    std::vector<std::string> out;
    for (const auto& [key, item] : visible.entries) {
        std::string bare = removePrefix(key, "__");
        if (bare.find("__") == std::string::npos || substringAfterLast(key, "__") != name) continue;
        out.push_back(join(split(bare, "__"), "::"));
    }
    return distinct(out);
}

// ---------------------------------------------------------------------------
// Declarations and their names
// ---------------------------------------------------------------------------

/** The name a top-level item declares, whatever kind of declaration it is. */
std::optional<std::string> StdlibInjector::declaredNameOf(const TopLevel& item) {
    switch (item.kind()) {
        case TopLevelKind::Func: return cast<TopLevelFunc>(item).decl.name;
        case TopLevelKind::FinDecl: return cast<TopLevelFinDecl>(item).name;
        case TopLevelKind::LetDecl: return cast<TopLevelLetDecl>(item).name;
        case TopLevelKind::VarDecl: return cast<TopLevelVarDecl>(item).name;
        default: return typeDeclarationName(item);
    }
}

std::optional<std::string> StdlibInjector::typeDeclarationName(const TopLevel& item) {
    switch (item.kind()) {
        case TopLevelKind::Pack: return cast<TopLevelPack>(item).name;
        case TopLevelKind::Enum: return cast<TopLevelEnum>(item).name;
        case TopLevelKind::Fail: return cast<TopLevelFail>(item).name;
        case TopLevelKind::Spec: return cast<TopLevelSpec>(item).name;
        case TopLevelKind::Deco: return cast<TopLevelDeco>(item).name;
        case TopLevelKind::Slot: return cast<TopLevelSlot>(item).name;
        case TopLevelKind::Solo: return cast<TopLevelSolo>(item).name;
        case TopLevelKind::TypeAlias: return cast<TopLevelTypeAlias>(item).name;
        default: return std::nullopt;
    }
}

std::optional<std::string> StdlibInjector::itemScope(const TopLevel& item) {
    if (TopLevelImpl* impl = as<TopLevelImpl>(item)) {
        if (!impl->scopePrefix) return std::nullopt;
        return replaceAll(*impl->scopePrefix, "__", "::");
    }
    std::string mangledName;
    switch (item.kind()) {
        case TopLevelKind::Func: mangledName = cast<TopLevelFunc>(item).decl.name; break;
        case TopLevelKind::FinDecl: mangledName = cast<TopLevelFinDecl>(item).name; break;
        case TopLevelKind::LetDecl: mangledName = cast<TopLevelLetDecl>(item).name; break;
        case TopLevelKind::VarDecl: mangledName = cast<TopLevelVarDecl>(item).name; break;
        default: return std::nullopt;
    }
    size_t at = mangledName.rfind("__");
    if (at == std::string::npos || at == 0) return std::nullopt;
    return replaceAll(mangledName.substr(0, at), "__", "::");
}

// ---------------------------------------------------------------------------
// The index
// ---------------------------------------------------------------------------

StdlibInjector::Index StdlibInjector::buildIndex() {
    Index idx;
    OrderedMap<std::string, bool> boolOverrides;
    for (const auto& [key, value] : configOverrides.entries) boolOverrides.set(key, trim(value) == "true");
    for (const Program& program : programs) {
        if (!program.moduleName) continue;
        const std::string module = *program.moduleName;
        std::string root = substringBefore(module, ".");
        idx.roots.add(root);
        if (!idx.moduleVisibility.contains(module)) idx.moduleVisibility.set(module, program.moduleVisibility);
        idx.modules.getOrPut(module, {});
        {
            std::vector<TypeFunctionDecl>& functions = idx.typeFunctionsByModule.getOrPut(module, {});
            functions.insert(functions.end(), program.typeFunctions.begin(), program.typeFunctions.end());
            std::vector<TypeTypeArm>& macros = idx.typeMacrosByModule.getOrPut(module, {});
            macros.insert(macros.end(), program.typeMacroRules.begin(), program.typeMacroRules.end());
        }
        for (const std::string& op : program.infixOperators.items) idx.allInfixOperators.add(op);
        idx.allInfixMacros.insert(idx.allInfixMacros.end(), program.infixMacros.begin(), program.infixMacros.end());
        for (const TypeFunctionDecl& declaration : program.typeFunctions) {
            idx.typeFunctionsByName.getOrPut(declaration.name, {}).push_back(declaration);
            std::string shortName = substringAfterLast(declaration.name, "__");
            if (shortName != declaration.name) idx.typeFunctionsByName.getOrPut(shortName, {}).push_back(declaration);
        }
        auto moduleItems = [&]() -> OrderedMap<std::string, TopLevel>& { return *idx.modules.get(module); };
        auto registerName = [&](const std::string& name, const TopLevel& item) {
            if (moduleItems().get(name) == nullptr) idx.modulesDeclaring.getOrPut(name, {}).push_back(module);
            if (!moduleItems().contains(name)) moduleItems().set(name, item);
            if (!idx.items.contains(name)) idx.items.set(name, item);
            if (!idx.moduleOfName.contains(name)) idx.moduleOfName.set(name, module);
            for (const std::string& knownRoot : idx.roots.items) {
                std::string rootPrefix = knownRoot + "__";
                if (!startsWith(name, rootPrefix)) continue;
                std::string shortName = removePrefix(name, rootPrefix);
                if (moduleItems().get(shortName) == nullptr) idx.modulesDeclaring.getOrPut(shortName, {}).push_back(module);
                if (!moduleItems().contains(shortName)) moduleItems().set(shortName, item);
                if (!idx.items.contains(shortName)) idx.items.set(shortName, item);
                if (!idx.moduleOfName.contains(shortName)) idx.moduleOfName.set(shortName, module);
            }
        };
        // `export exposed module …` is auto-imported into every unit.
        bool alwaysOn = program.isExported && evalExportIf(program.exportCondition, boolOverrides) &&
                        program.moduleVisibility == ModuleVisibility::PUBLIC;
        for (const TopLevel& item : program.items) {
            const std::string* decoratedName = nullptr;
            const std::vector<Annotation>* decoratedBy = nullptr;
            switch (item.kind()) {
                case TopLevelKind::Pack:
                    decoratedName = &cast<TopLevelPack>(item).name;
                    decoratedBy = &cast<TopLevelPack>(item).annotations;
                    break;
                case TopLevelKind::Enum:
                    decoratedName = &cast<TopLevelEnum>(item).name;
                    decoratedBy = &cast<TopLevelEnum>(item).annotations;
                    break;
                case TopLevelKind::Fail:
                    decoratedName = &cast<TopLevelFail>(item).name;
                    decoratedBy = &cast<TopLevelFail>(item).annotations;
                    break;
                case TopLevelKind::Func:
                    decoratedName = &cast<TopLevelFunc>(item).decl.name;
                    decoratedBy = &cast<TopLevelFunc>(item).decl.annotations;
                    break;
                default:
                    break;
            }
            if (decoratedBy) {
                for (const Annotation& annotation : *decoratedBy) {
                    idx.annotatedBy.getOrPut(annotation.name, {}).push_back({module, *decoratedName});
                }
            }
            switch (item.kind()) {
                case TopLevelKind::Func: registerName(cast<TopLevelFunc>(item).decl.name, item); break;
                case TopLevelKind::FinDecl: registerName(cast<TopLevelFinDecl>(item).name, item); break;
                case TopLevelKind::LetDecl: registerName(cast<TopLevelLetDecl>(item).name, item); break;
                case TopLevelKind::VarDecl: registerName(cast<TopLevelVarDecl>(item).name, item); break;
                case TopLevelKind::InlineFin: registerName(cast<TopLevelInlineFin>(item).name, item); break;
                case TopLevelKind::InlineLet: registerName(cast<TopLevelInlineLet>(item).name, item); break;
                case TopLevelKind::InlineVar: registerName(cast<TopLevelInlineVar>(item).name, item); break;
                case TopLevelKind::Pack: registerName(cast<TopLevelPack>(item).name, item); break;
                case TopLevelKind::Enum: registerName(cast<TopLevelEnum>(item).name, item); break;
                case TopLevelKind::Fail: registerName(cast<TopLevelFail>(item).name, item); break;
                case TopLevelKind::Spec: registerName(cast<TopLevelSpec>(item).name, item); break;
                case TopLevelKind::Deco: registerName(cast<TopLevelDeco>(item).name, item); break;
                case TopLevelKind::Slot: registerName(cast<TopLevelSlot>(item).name, item); break;
                case TopLevelKind::TypeAlias: registerName(cast<TopLevelTypeAlias>(item).name, item); break;
                case TopLevelKind::Meta: registerName(cast<TopLevelMeta>(item).name, item); break;
                case TopLevelKind::Impl: {
                    TopLevelImpl* impl = as<TopLevelImpl>(item);
                    OrderedSet<std::string> keys{impl->typeName, normalizedTypeName(impl->typeName),
                                                 substringBefore(impl->typeName, ".")};
                    for (const std::string& key : keys.items) idx.implsByType.getOrPut(key, {}).push_back(impl);
                    break;
                }
                case TopLevelKind::Bridge: {
                    const TopLevelBridge& bridge = cast<TopLevelBridge>(item);
                    for (const BridgeSig& sig : bridge.funcs) {
                        TopLevel declaration =
                            make(arena, TopLevelBridge{bridge.target, {sig}, bridge.line, bridge.column, bridge.annotations});
                        std::string localName = sig.localName ? *sig.localName : sig.name;
                        if (!idx.externs.contains(localName)) idx.externs.set(localName, declaration);
                        registerName(localName, declaration);
                    }
                    for (const BridgeValue& value : bridge.values) {
                        TopLevel declaration = make(
                            arena, TopLevelBridge{bridge.target, {}, bridge.line, bridge.column, bridge.annotations, {value}});
                        registerName(value.name, declaration);
                    }
                    break;
                }
                // Compile-time blocks of always-on modules are injected whole.
                case TopLevelKind::InlineBlock:
                case TopLevelKind::DeepInlineBlock:
                case TopLevelKind::InlineIf:
                case TopLevelKind::DeepInlineIf:
                    if (alwaysOn) {
                        idx.alwaysInjectedItems.push_back(item);
                        idx.alwaysInjectedModules.push_back(module);
                    }
                    break;
                default:
                    break;
            }
        }
        for (const auto& [shortName, qualifier] : program.scopeTypeNamespaces.entries) {
            const TopLevel* declaration = moduleItems().get(shortName);
            if (declaration == nullptr) continue;
            ScopeTypeExport exported{shortName, qualifier, module, *declaration};
            std::string qualified = exported.qualifiedName();
            if (!idx.scopeTypesByQualifiedName.contains(qualified)) idx.scopeTypesByQualifiedName.set(qualified, exported);
            idx.scopeTypesByShortName.getOrPut(shortName, {}).push_back(exported);
        }
        for (const TopLevel& item : program.items) {
            if (TopLevelUseImport* use = as<TopLevelUseImport>(item)) {
                std::vector<ImportRequest> requests = importRequests(*use);
                std::vector<ImportRequest>& list = idx.importsOfModule.getOrPut(module, {});
                list.insert(list.end(), requests.begin(), requests.end());
            }
        }
        // `exposed import …` re-exports, for transitive import propagation.
        for (const TopLevel& item : program.items) {
            TopLevelUseImport* use = as<TopLevelUseImport>(item);
            if (use && use->exported && evalExportIf(use->condition, boolOverrides)) {
                std::vector<ImportRequest> requests = importRequests(*use);
                std::vector<ImportRequest>& list = idx.exportedImportsByModule.getOrPut(module, {});
                list.insert(list.end(), requests.begin(), requests.end());
            }
        }
        if (alwaysOn) {
            idx.alwaysOnModules.push_back(module);
            for (const auto& [name, item] : moduleItems().entries) {
                if (!idx.implicitRootItems.contains(name)) idx.implicitRootItems.set(name, item);
            }
            idx.alwaysTypeFunctions.insert(idx.alwaysTypeFunctions.end(), program.typeFunctions.begin(),
                                           program.typeFunctions.end());
            idx.alwaysTypeMacros.insert(idx.alwaysTypeMacros.end(), program.typeMacroRules.begin(),
                                        program.typeMacroRules.end());
        }
    }
    return idx;
}

// ---------------------------------------------------------------------------
// What imports make visible
// ---------------------------------------------------------------------------

/** Declarations made visible by the program's imports and the always-on modules' re-exports. */
OrderedMap<std::string, TopLevel> StdlibInjector::importedItems(const Program& program) {
    std::vector<ImportRequest> seeds;
    for (const TopLevel& item : program.items) {
        if (TopLevelUseImport* use = as<TopLevelUseImport>(item)) {
            std::vector<ImportRequest> requests = importRequests(*use);
            seeds.insert(seeds.end(), requests.begin(), requests.end());
        }
    }
    for (const std::string& module : index().alwaysOnModules) {
        if (const std::vector<ImportRequest>* exported = index().exportedImportsByModule.get(module)) {
            seeds.insert(seeds.end(), exported->begin(), exported->end());
        }
    }
    return itemsImportedBy(seeds, nullptr);
}

/**
 * What [requests] make nameable, expanded through the imported modules'
 * re-exports. [isImportable] null means isExternallyImportable.
 */
OrderedMap<std::string, TopLevel> StdlibInjector::itemsImportedBy(
    const std::vector<ImportRequest>& requests, const std::function<bool(const std::string&)>& isImportable) {
    OrderedMap<std::string, TopLevel> visible;
    std::deque<ImportRequest> seeds(requests.begin(), requests.end());
    std::unordered_set<std::string> visited;
    while (!seeds.empty()) {
        ImportRequest request = std::move(seeds.front());
        seeds.pop_front();
        if (!visited.insert(visitedKey(request)).second) continue;
        for (const auto& [name, declaration] : itemsVisibleFromImport(request.path, request.selected, isImportable).entries) {
            if (request.without.contains(name) || request.without.contains(substringAfterLast(name, "__"))) continue;
            if (!visible.contains(name)) visible.set(name, declaration);
        }
        for (const std::string& module : modulesForPath(request.path)) {
            const std::vector<ImportRequest>* reExports = index().exportedImportsByModule.get(module);
            if (reExports == nullptr) continue;
            for (const ImportRequest& reExport : *reExports) {
                ImportRequest next = reExport;
                for (const std::string& w : request.without.items) next.without.add(w);
                seeds.push_back(std::move(next));
            }
        }
    }
    return visible;
}

/** Every library module [program] can name: its imports through re-exports, plus the always-on modules. */
OrderedSet<std::string> StdlibInjector::reachableModules(const Program& program) {
    OrderedSet<std::string> reached;
    for (const std::string& module : index().alwaysOnModules) reached.add(module);
    std::deque<ImportRequest> seeds;
    // A block's import reaches its module for the whole program.
    for (TopLevelUseImport* use : writtenImportsOf(program)) {
        for (ImportRequest& request : importRequests(*use)) seeds.push_back(std::move(request));
    }
    for (const std::string& module : index().alwaysOnModules) {
        if (const std::vector<ImportRequest>* exported = index().exportedImportsByModule.get(module)) {
            seeds.insert(seeds.end(), exported->begin(), exported->end());
        }
    }
    std::unordered_set<std::string> visited;
    while (!seeds.empty()) {
        ImportRequest request = std::move(seeds.front());
        seeds.pop_front();
        if (!visited.insert(visitedKey(request)).second) continue;
        // A selected declaration retains its declaring module's impls.
        std::optional<std::pair<std::string, std::string>> owner = resolveSelectedLibraryPath(request.path);
        std::vector<std::string> modules = owner ? std::vector<std::string>{owner->first} : modulesForPath(request.path);
        for (const std::string& module : modules) {
            reached.add(module);
            if (const std::vector<ImportRequest>* exported = index().exportedImportsByModule.get(module)) {
                seeds.insert(seeds.end(), exported->begin(), exported->end());
            }
        }
    }
    return reached;
}

/** The library module(s) an import [path] reaches: itself if exact, else descendant modules. */
std::vector<std::string> StdlibInjector::modulesForPath(const std::string& path) {
    if (index().modules.contains(path)) return {path};
    std::vector<std::string> out;
    std::string prefix = path + ".";
    for (const auto& [module, items] : index().modules.entries) {
        if (startsWith(module, prefix)) out.push_back(module);
    }
    return out;
}

std::vector<TypeFunctionDecl> StdlibInjector::importedTypeFunctions(const Program& program) {
    std::vector<ImportRequest> requests;
    for (const TopLevel& item : program.items) {
        if (TopLevelUseImport* use = as<TopLevelUseImport>(item)) {
            std::vector<ImportRequest> more = importRequests(*use);
            requests.insert(requests.end(), more.begin(), more.end());
        }
    }
    std::vector<TypeFunctionDecl> out;
    for (auto& [module, declaration] : typeLevelImportedBy(requests, nullptr).functions) out.push_back(declaration);
    return out;
}

std::vector<std::pair<std::string, TypeTypeArm>> StdlibInjector::importedTypeMacros(const Program& program) {
    // Type macros obey the same transitive `export import` visibility as declarations.
    std::vector<ImportRequest> seeds;
    for (const TopLevel& item : program.items) {
        if (TopLevelUseImport* use = as<TopLevelUseImport>(item)) {
            std::vector<ImportRequest> more = importRequests(*use);
            seeds.insert(seeds.end(), more.begin(), more.end());
        }
    }
    for (const std::string& module : index().alwaysOnModules) {
        if (const std::vector<ImportRequest>* exported = index().exportedImportsByModule.get(module)) {
            seeds.insert(seeds.end(), exported->begin(), exported->end());
        }
    }
    return typeLevelImportedBy(seeds, nullptr).macros;
}

/**
 * The type functions and type macros [requests] bring: all of a module's, all
 * below a namespace, or the one a path selects; re-exports carry them on.
 */
StdlibInjector::TypeLevelImports StdlibInjector::typeLevelImportedBy(
    const std::vector<ImportRequest>& requests, const std::function<bool(const std::string&)>& isImportable) {
    auto importable = [&](const std::string& module) {
        return isImportable ? isImportable(module) : isExternallyImportable(module);
    };
    TypeLevelImports out;
    std::deque<ImportRequest> seeds(requests.begin(), requests.end());
    std::unordered_set<std::string> visited;
    while (!seeds.empty()) {
        ImportRequest request = std::move(seeds.front());
        seeds.pop_front();
        if (!visited.insert(visitedKey(request)).second) continue;
        std::vector<std::string> modules = modulesForPath(request.path);
        if (modules.empty() && !request.selected) {
            std::optional<std::pair<std::string, std::string>> selected = selectedTypeLevelPath(request.path);
            if (!selected) continue;
            const auto& [module, name] = *selected;
            if (!importable(module)) continue;
            auto named = [&](const std::optional<std::string>& declared) {
                return declared && (*declared == name || substringAfterLast(*declared, "__") == name);
            };
            if (const std::vector<TypeFunctionDecl>* functions = index().typeFunctionsByModule.get(module)) {
                for (const TypeFunctionDecl& f : *functions) {
                    if (named(f.name)) out.functions.push_back({module, f});
                }
            }
            if (const std::vector<TypeTypeArm>* macros = index().typeMacrosByModule.get(module)) {
                for (const TypeTypeArm& arm : *macros) {
                    if (named(arm.name)) out.macros.push_back({module, arm});
                }
            }
            continue;
        }
        auto kept = [&](const std::optional<std::string>& declared) {
            return !declared || (!request.without.contains(*declared) &&
                                 !request.without.contains(substringAfterLast(*declared, "__")));
        };
        for (const std::string& module : modules) {
            if (!importable(module)) continue;
            if (const std::vector<TypeFunctionDecl>* functions = index().typeFunctionsByModule.get(module)) {
                for (const TypeFunctionDecl& f : *functions) {
                    if (kept(f.name)) out.functions.push_back({module, f});
                }
            }
            if (const std::vector<TypeTypeArm>* macros = index().typeMacrosByModule.get(module)) {
                for (const TypeTypeArm& arm : *macros) {
                    if (kept(arm.name)) out.macros.push_back({module, arm});
                }
            }
            if (const std::vector<ImportRequest>* exported = index().exportedImportsByModule.get(module)) {
                for (const ImportRequest& reExport : *exported) {
                    ImportRequest next = reExport;
                    for (const std::string& w : request.without.items) next.without.add(w);
                    seeds.push_back(std::move(next));
                }
            }
        }
    }
    return out;
}

/** What each type macro's template names, as references written in its module. */
std::vector<StdlibInjector::Ref> StdlibInjector::templateRefs(const std::vector<std::pair<std::string, TypeTypeArm>>& macros) {
    std::vector<Ref> out;
    for (const auto& [module, arm] : macros) {
        OrderedSet<std::string> names;
        collectNamesFromTypeRef(arm.template_, names);
        for (const std::string& name : names.items) out.push_back(Ref{name, module});
    }
    return out;
}

/** `lib.rows.wider` → (`lib.rows`, `wider`): the module a dotted path selects from, and what it selects. */
std::optional<std::pair<std::string, std::string>> StdlibInjector::selectedTypeLevelPath(const std::string& path) {
    std::vector<std::string> segments = split(path, ".");
    for (size_t itemStart = segments.size() - 1; itemStart >= 1; itemStart--) {
        std::string module = joinWith(segments, ".", 0, itemStart);
        if (index().modules.contains(module)) return std::make_pair(module, joinWith(segments, "__", itemStart, segments.size()));
    }
    return std::nullopt;
}

/** A block's type functions and named type macros, renamed to their module-qualified spelling. */
StdlibInjector::BlockTypeLevel StdlibInjector::blockTypeLevel(const TypeLevelImports& imported) {
    BlockTypeLevel out;
    for (const auto& [module, declaration] : imported.functions) {
        std::string identity = ModuleQualifiedSymbol::create(module, declaration.name);
        out.renames.set(declaration.name, identity);
        TypeFunctionDecl copy = declaration;
        copy.name = identity;
        out.functions.push_back(std::move(copy));
    }
    for (const auto& [module, arm] : imported.macros) {
        if (!arm.name) continue;
        std::string identity = ModuleQualifiedSymbol::create(module, *arm.name);
        out.renames.set(*arm.name, identity);
        TypeTypeArm copy = arm;
        copy.name = identity;
        out.macros.push_back({module, std::move(copy)});
    }
    return out;
}

/** Only `exposed` (public) modules may be imported by an external unit; unknown modules may. */
bool StdlibInjector::isExternallyImportable(const std::string& module) {
    const ModuleVisibility* visibility = index().moduleVisibility.get(module);
    return visibility == nullptr || *visibility == ModuleVisibility::PUBLIC;
}

OrderedMap<std::string, TopLevel> StdlibInjector::itemsVisibleFromImport(
    const std::string& path, const std::optional<std::string>& selected,
    const std::function<bool(const std::string&)>& isImportable /*= nullptr*/) {
    auto importable = [&](const std::string& module) {
        return isImportable ? isImportable(module) : isExternallyImportable(module);
    };
    // `import path.*`: the module at `path` (if any) plus every descendant.
    if (selected && *selected == "*") {
        OrderedMap<std::string, TopLevel> result;
        if (importable(path)) {
            if (const OrderedMap<std::string, TopLevel>* module = index().modules.get(path)) {
                for (const auto& [name, declaration] : module->entries) {
                    if (!result.contains(name)) result.set(name, declaration);
                }
            }
        }
        std::string prefix = path + ".";
        for (const auto& [moduleName, module] : index().modules.entries) {
            if (!startsWith(moduleName, prefix) || !importable(moduleName)) continue;
            for (const auto& [name, declaration] : module.entries) {
                if (!result.contains(name)) result.set(name, declaration);
            }
        }
        return result;
    }
    if (selected) {
        if (!importable(path)) return {};
        const OrderedMap<std::string, TopLevel>* module = index().modules.get(path);
        if (module == nullptr) return {};
        const TopLevel* declaration = module->get(*selected);
        if (declaration == nullptr) return {};
        OrderedMap<std::string, TopLevel> one;
        one.set(*selected, *declaration);
        return one;
    }
    // `import path`: an actual module, or a single `module.item` selection.
    if (const OrderedMap<std::string, TopLevel>* module = index().modules.get(path); module && importable(path)) return *module;
    std::optional<std::pair<std::string, std::string>> resolved = resolveSelectedLibraryPath(path);
    if (!resolved) return {};
    if (!importable(resolved->first)) return {};
    const OrderedMap<std::string, TopLevel>* module = index().modules.get(resolved->first);
    const TopLevel* declaration = module ? module->get(resolved->second) : nullptr;
    if (declaration == nullptr) return {};
    OrderedMap<std::string, TopLevel> one;
    one.set(resolved->second, *declaration);
    return one;
}

/**
 * Everything library [module]'s own source can name: its declarations, then
 * what it imports (re-exports included), then the always-on root.
 */
const OrderedMap<std::string, TopLevel>& StdlibInjector::libraryScope(const std::string& module) {
    auto found = libraryScopes.find(module);
    if (found != libraryScopes.end()) return found->second;
    OrderedMap<std::string, TopLevel> scope;
    if (const OrderedMap<std::string, TopLevel>* own = index().modules.get(module)) scope = *own;
    std::vector<ImportRequest> requests;
    if (const std::vector<ImportRequest>* imports = index().importsOfModule.get(module)) requests = *imports;
    for (const auto& [name, declaration] : libraryImportBindings(requests).entries) {
        if (!scope.contains(name)) scope.set(name, declaration);
    }
    for (const auto& [name, declaration] : index().implicitRootItems.entries) {
        if (!scope.contains(name)) scope.set(name, declaration);
    }
    return libraryScopes.emplace(module, std::move(scope)).first->second;
}

/** What a library module's imports bind, re-exports included (its own library's confined modules too). */
OrderedMap<std::string, TopLevel> StdlibInjector::libraryImportBindings(const std::vector<ImportRequest>& requests) {
    return itemsImportedBy(requests, [](const std::string&) { return true; });
}

/** The module that declared [item] under [name]; none for a user declaration. */
std::optional<std::string> StdlibInjector::declaringModule(const TopLevel& item, const std::string& name) {
    if (TopLevelImpl* impl = as<TopLevelImpl>(item)) return impl->declaringModule;
    const std::vector<std::string>* modules = index().modulesDeclaring.get(name);
    if (modules == nullptr) return std::nullopt;
    for (const std::string& module : *modules) {
        const OrderedMap<std::string, TopLevel>* items = index().modules.get(module);
        const TopLevel* declared = items ? items->get(name) : nullptr;
        if (declared && same(*declared, item)) return module;
    }
    return std::nullopt;
}

std::optional<std::pair<std::string, std::string>> StdlibInjector::resolveSelectedLibraryPath(const std::string& path) {
    std::vector<std::string> segments = split(path, ".");
    if (segments.size() < 2) return std::nullopt;
    for (size_t itemStart = segments.size() - 1; itemStart >= 1; itemStart--) {
        std::string module = joinWith(segments, ".", 0, itemStart);
        const OrderedMap<std::string, TopLevel>* items = index().modules.get(module);
        if (items == nullptr) continue;
        std::string item = joinWith(segments, "__", itemStart, segments.size());
        if (items->contains(item)) return std::make_pair(module, item);
    }
    return std::nullopt;
}

/** Names declared at the top level of the user [program] (these shadow the library). */
OrderedSet<std::string> StdlibInjector::userDeclaredNames(const Program& program) { return programDeclaredNames(program); }

/** Kotlin `program.writtenImports()`: file-scope imports, then every block's. */
std::vector<TopLevelUseImport*> StdlibInjector::writtenImportsOf(const Program& program) {
    return writtenImports(arena, program);
}

OrderedSet<std::string> programDeclaredNames(const Program& program) {
    OrderedSet<std::string> names;
    for (const TopLevel& item : program.items) {
        switch (item.kind()) {
            case TopLevelKind::Func: names.add(cast<TopLevelFunc>(item).decl.name); break;
            case TopLevelKind::FinDecl: names.add(cast<TopLevelFinDecl>(item).name); break;
            case TopLevelKind::LetDecl: names.add(cast<TopLevelLetDecl>(item).name); break;
            case TopLevelKind::VarDecl: names.add(cast<TopLevelVarDecl>(item).name); break;
            case TopLevelKind::Pack: names.add(cast<TopLevelPack>(item).name); break;
            case TopLevelKind::Enum: names.add(cast<TopLevelEnum>(item).name); break;
            case TopLevelKind::Fail: names.add(cast<TopLevelFail>(item).name); break;
            case TopLevelKind::Deco: names.add(cast<TopLevelDeco>(item).name); break;
            case TopLevelKind::Spec: names.add(cast<TopLevelSpec>(item).name); break;
            case TopLevelKind::Slot: names.add(cast<TopLevelSlot>(item).name); break;
            case TopLevelKind::Bridge: {
                const TopLevelBridge& bridge = cast<TopLevelBridge>(item);
                for (const BridgeSig& f : bridge.funcs) names.add(f.localName ? *f.localName : f.name);
                for (const BridgeValue& v : bridge.values) names.add(v.name);
                break;
            }
            case TopLevelKind::Meta: names.add(cast<TopLevelMeta>(item).name); break;
            default: break;
        }
    }
    return names;
}

}  // namespace azora
