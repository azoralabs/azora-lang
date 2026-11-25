// Port of stdlib/AzStdlibLoader.kt (`object AzStdlib`): the standard library,
// read from wherever this installation keeps it.
//
// Resolution order, first existing root wins: overrideRoot, AZORA_STDLIB, the
// project's own std/ (nearest workspace marker up from the working directory),
// $AZORA_HOME/std, then the tree bundled into the compiler. A disk root must
// have a package.azon beside it whose name and version match the bundle's.
//
// Like the Kotlin object this is process-wide state; its programs live in a
// process-lifetime arena.
#pragma once

#include "frontend/parser.h"
#include "stdlib/stdlib_source.h"

#include <optional>
#include <string>
#include <vector>

namespace azora {
namespace AzStdlib {

/** The bundled standard library's package name and version (Kotlin AzStdlibBundle). */
const std::string& bundlePackageName();
const std::string& bundleVersion();

/** An explicit stdlib root ahead of every other candidate; setting a new one discards the cache. */
const std::optional<std::string>& overrideRoot();
void setOverrideRoot(const std::optional<std::string>& root);

/** Discards the cached tree and its parse, so the next use resolves again. */
void invalidate();

/** The arena the standard library's programs live in. */
Arena& arena();

/** Compile-time lists, enums and list scopes bound by the standard library (populated by loadPrograms). */
TypeListEnv& comptimeLists();
EnumEnv& declaredEnums();
TypeListScopes& comptimeListScopes();

/** The resolved standard library: origin, version and files. */
const StdlibTree& tree();

/** Load the complete standard library; strict mode fails on a broken source, lenient keeps placeholders. */
const std::vector<Program>& loadPrograms(bool strict = true);

/** Resolves a search path (defaults: the override, then the platform's disk roots). */
StdlibTree resolve(const std::vector<StdlibRoot>& candidates);
StdlibTree resolve();

/** Parses every module against one shared compile-time environment, rebuilt from empty. */
std::vector<Program> parse(const StdlibTree& tree, bool strict);

}  // namespace AzStdlib
}  // namespace azora
