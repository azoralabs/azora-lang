Custom allocators let code control where memory comes from, how it is organized, and when it is reclaimed, instead of always using one global heap allocator.

For Azora, I would make allocation depend on an Allocator interface rather than hard-coding malloc-style behavior into alloc.

What custom allocators are for

They are useful when the default heap is not ideal.

Common cases:

Arena allocation: allocate many objects quickly, then free all of them at once.
Pool allocation: efficiently allocate many objects of the same size.
Stack/bump allocation: extremely fast temporary allocations.
Game engines: separate frame, scene, audio, physics, and asset memory.
Double-time systems: avoid unpredictable pauses or heap fragmentation.
Debugging: track leaks, allocation sites, sizes, and double frees.
Embedded systems: allocate from fixed memory regions without an OS heap.
Shared or mapped memory: allocate inside memory visible to another process or device.
Alignment-sensitive data: SIMD, GPU buffers, cache-line alignment.

A custom allocator does not usually construct objects. It manages raw memory.

Minimal allocator interface

A low-level allocator should work with bytes, sizes, and alignment:

```azora
prot Allocator {
    unsafe func allocate(self: Self!, size: USize, align: USize): Byte^?
    
    unsafe func resize(self: Self!, memory: Byte^, oldSize: USize, newSize: USize, align: USize): Byte^?

    unsafe func deallocate(self: Self!, memory: Byte^, size: USize, align: USize)
}

pack ArenaAllocator {
    var _backing: Allocator!
    var _memory: Byte^
    var _capacity: USize
    var _offset: USize
}

impl Allocator for ArenaAllocator {
    // implement them
}
```

```azora
unsafe func allocWith<T>(allocator: Allocator!, value: T): T^ ?! AllocError {
    fin memory = try allocator.allocate(T.sizeof, T.alignof) catch { return .OutOfMemory }
    fin pointer = bitcast<T^>(memory)
    pointer.^ = mov value // you need to add "mov" keyword like rust "move"
    return pointer
}

unsafe func purgeWith<T>(allocator: Allocator!, pointer: T^) {
    drop pointer.^
    allocator.deallocate(bitcast<Byte^>(pointer), T.sizeof, T.alignof)
}
```

```azora
unsafe func main() {
    var allocator = SystemAllocator()

    fin user: User^ = allocWith(
        allocator!,
        User("Gabriel", 30)
    )

    defer purgeWith(allocator!, user)

    println(user.^.name)
}
```

Then also add allocators to smart pointers like:

```azora
pack Unique<T> {
    var _pointer: T^
    var _allocator: Allocator!
}

// Unique<User, ArenaAllocator>
```

// and to containers and so on