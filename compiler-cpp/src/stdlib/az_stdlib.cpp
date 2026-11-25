// Port of stdlib/AzStdlibLoader.kt.
#include "stdlib/az_stdlib.h"

#include "base/failure.h"
#include "frontend/lexer.h"

#include <memory>

namespace azora {
namespace AzStdlib {

namespace {

struct State {
    std::optional<std::string> overrideRoot;
    std::optional<StdlibTree> cachedTree;
    std::optional<std::vector<Program>> cachedPrograms;
    std::optional<std::vector<Program>> cachedLenientPrograms;
    TypeListEnv comptimeLists;
    EnumEnv declaredEnums;
    TypeListScopes comptimeListScopes;
    Arena arena;
};

State& state() {
    static State* s = new State();
    return *s;
}

const StdlibPackageManifest& bundleManifest() {
    static const StdlibPackageManifest manifest = [] {
        std::optional<StdlibPackageManifest> m = parseStdlibPackageManifest(stdlibBundleManifest());
        if (!m) failArgument("Required value was null.");
        return *m;
    }();
    return manifest;
}

/** A standard library built for another release is rejected here. */
void checkVersion(const std::string& found, const std::string& where) {
    if (found == bundleVersion()) return;
    fail("standard library package version mismatch: this compiler bundles " + bundlePackageName() + " " +
         bundleVersion() + ", but the standard library from " + where + " is " + found);
}

}  // namespace

const std::string& bundlePackageName() { return bundleManifest().name; }
const std::string& bundleVersion() { return bundleManifest().version; }

const std::optional<std::string>& overrideRoot() { return state().overrideRoot; }

void setOverrideRoot(const std::optional<std::string>& root) {
    if (state().overrideRoot != root) {
        state().overrideRoot = root;
        invalidate();
    }
}

void invalidate() {
    State& s = state();
    s.cachedTree.reset();
    s.cachedPrograms.reset();
    s.cachedLenientPrograms.reset();
    s.comptimeLists.clear();
    s.declaredEnums.clear();
    s.comptimeListScopes.clear();
}

Arena& arena() { return state().arena; }
TypeListEnv& comptimeLists() { return state().comptimeLists; }
EnumEnv& declaredEnums() { return state().declaredEnums; }
TypeListScopes& comptimeListScopes() { return state().comptimeListScopes; }

const StdlibTree& tree() {
    State& s = state();
    if (!s.cachedTree) s.cachedTree = resolve();
    return *s.cachedTree;
}

const std::vector<Program>& loadPrograms(bool strict) {
    State& s = state();
    if (strict) {
        if (!s.cachedPrograms) s.cachedPrograms = parse(tree(), true);
        return *s.cachedPrograms;
    }
    if (!s.cachedLenientPrograms) s.cachedLenientPrograms = parse(tree(), false);
    return *s.cachedLenientPrograms;
}

StdlibTree resolve() {
    std::vector<StdlibRoot> candidates;
    if (state().overrideRoot) candidates.push_back({*state().overrideRoot, "explicit override", true});
    for (StdlibRoot& root : stdlibDiskRoots()) candidates.push_back(std::move(root));
    return resolve(candidates);
}

StdlibTree resolve(const std::vector<StdlibRoot>& candidates) {
    for (const StdlibRoot& candidate : candidates) {
        std::optional<std::vector<StdlibFile>> files = readStdlibTree(candidate.path);
        if (!files || files->empty()) continue;
        std::optional<std::string> manifestText = readStdlibPackageManifest(candidate.path);
        std::optional<StdlibPackageManifest> manifest;
        if (manifestText) manifest = parseStdlibPackageManifest(*manifestText);
        if (!manifest) {
            // A directory that merely happens to be called `std` is not one; a
            // root the user named must be used or complained about.
            if (!candidate.explicit_) continue;
            fail("the standard library at '" + candidate.path + "' (" + candidate.origin + ") has no valid " +
                 STDLIB_PACKAGE_MANIFEST +
                 " beside it; package.name and package.version are required - point AZORA_STDLIB at that "
                 "package's std/ directory, or unset it to use the bundled standard library");
        }
        if (manifest->name != bundlePackageName()) {
            if (!candidate.explicit_) continue;
            fail("the standard library at '" + candidate.path + "' (" + candidate.origin + ") belongs to package '" +
                 manifest->name + "', expected '" + bundlePackageName() + "'");
        }
        checkVersion(manifest->version, candidate.origin + " ('" + candidate.path + "')");
        return StdlibTree{candidate.origin, manifest->version, std::move(*files)};
    }
    return StdlibTree{"bundled with the compiler", bundleVersion(), stdlibBundleFiles()};
}

std::vector<Program> parse(const StdlibTree& tree, bool strict) {
    State& s = state();
    s.comptimeLists.clear();
    s.declaredEnums.clear();
    s.comptimeListScopes.clear();
    std::vector<Program> programs;
    programs.reserve(tree.files.size());
    for (const StdlibFile& file : tree.files) {
        try {
            Parser parser(s.arena, Lexer(file.source).tokenize(), &s.comptimeLists, &s.declaredEnums, false,
                          &s.comptimeListScopes);
            programs.push_back(parser.parse());
        } catch (const CompileFailure& e) {
            // Lenient mode keeps one placeholder per source, so file associations stay parallel.
            if (!strict) {
                programs.push_back(Program{});
                continue;
            }
            fail("Failed to parse standard library source " + file.path + " (" + tree.origin + "): " + e.message);
        }
    }
    return programs;
}

}  // namespace AzStdlib
}  // namespace azora
