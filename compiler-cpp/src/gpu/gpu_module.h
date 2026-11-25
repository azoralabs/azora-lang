// Port of gpu/GpuModule.kt: the source header that selects the GPU compiler
// for an ordinary Azora file.
#pragma once

#include <optional>
#include <string>

namespace azora {
namespace GpuModule {

/** Whether [source] starts (after whitespace and comments) with `@Execution("GPU") module name`. */
bool isGpuSource(const std::string& source);

/** The module such a header names, or none. */
std::optional<std::string> moduleName(const std::string& source);

}  // namespace GpuModule
}  // namespace azora
