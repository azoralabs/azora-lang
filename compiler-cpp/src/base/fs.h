// Files and directories through POSIX calls.
#pragma once

#include <optional>
#include <string>
#include <vector>

namespace azora {

/** The bytes of [path], or nullopt when it cannot be read. */
std::optional<std::string> readFile(const std::string& path);

/** Writes [bytes] to [path], creating parent directories. Throws on failure. */
void writeFile(const std::string& path, const std::string& bytes);

/** mkdir -p. */
void makeDirectories(const std::string& path);

bool isDirectory(const std::string& path);
bool isFile(const std::string& path);

/** Names in [path] (no `.`/`..`), unsorted; empty when it is not a directory. */
std::vector<std::string> listDirectory(const std::string& path);

/** Monotonic seconds, for timings. */
double nowSeconds();

}  // namespace azora
