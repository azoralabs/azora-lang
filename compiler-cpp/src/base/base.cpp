#include "base/arena.h"
#include "base/failure.h"

#include <cstdlib>
#include <cstring>

namespace azora {

// ---------------------------------------------------------------------------
// Arena
// ---------------------------------------------------------------------------

static constexpr size_t BLOCK_SIZE = 1 << 20;

Arena::~Arena() {
    for (size_t i = destructors.size(); i > 0; i--) destructors[i - 1].destroy(destructors[i - 1].object);
}

void* Arena::allocate(size_t size, size_t align) {
    uintptr_t at = (reinterpret_cast<uintptr_t>(cursor) + (align - 1)) & ~(uintptr_t)(align - 1);
    if (cursor == nullptr || at + size > reinterpret_cast<uintptr_t>(limit)) {
        size_t blockSize = size + align > BLOCK_SIZE ? size + align : BLOCK_SIZE;
        blocks.push_back(std::make_unique_for_overwrite<std::byte[]>(blockSize));
        cursor = blocks.back().get();
        limit = cursor + blockSize;
        at = (reinterpret_cast<uintptr_t>(cursor) + (align - 1)) & ~(uintptr_t)(align - 1);
    }
    std::byte* result = reinterpret_cast<std::byte*>(at);
    cursor = result + size;
    used += size;
    return result;
}

// ---------------------------------------------------------------------------
// Failures
// ---------------------------------------------------------------------------

void fail(std::string message) { throw CompileFailure{FailureKind::IllegalState, std::move(message)}; }

void failArgument(std::string message) { throw CompileFailure{FailureKind::IllegalArgument, std::move(message)}; }

void failWith(FailureKind kind, std::string message) { throw CompileFailure{kind, std::move(message)}; }

void unreachable(const char* where) {
    throw CompileFailure{FailureKind::IllegalState, std::string("unreachable: ") + where};
}

}  // namespace azora
