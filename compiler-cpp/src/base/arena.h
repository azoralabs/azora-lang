// One compilation's node storage (CPP_PORT_PLAN.md §7).
//
// A bump allocator over 1 MB blocks. Every node of a compilation lives here
// and is freed with it; nodes point at each other with plain pointers and own
// nothing. Node structs hold std::string and std::vector fields (Kotlin's
// String and List), so the arena records a destructor for every object that
// needs one and runs them, newest first, when it is destroyed.
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <new>
#include <type_traits>
#include <utility>
#include <vector>

namespace azora {

struct Arena {
    Arena() = default;
    Arena(const Arena&) = delete;
    Arena& operator=(const Arena&) = delete;
    ~Arena();

    void* allocate(size_t size, size_t align);

    /** Bytes handed out so far, for the peak-size report. */
    size_t bytesUsed() const { return used; }

    struct Destructor {
        void* object;
        void (*destroy)(void*);
    };

    std::vector<std::unique_ptr<std::byte[]>> blocks;
    std::byte* cursor = nullptr;
    std::byte* limit = nullptr;
    size_t used = 0;
    std::vector<Destructor> destructors;
};

/** Moves [value] into [arena] and returns the arena's copy. */
template <class T>
T* make(Arena& arena, T value) {
    void* memory = arena.allocate(sizeof(T), alignof(T));
    T* object = new (memory) T(std::move(value));
    if constexpr (!std::is_trivially_destructible_v<T>) {
        arena.destructors.push_back({object, [](void* p) { static_cast<T*>(p)->~T(); }});
    }
    return object;
}

}  // namespace azora
