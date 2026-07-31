Custom allocators let code control where memory comes from, how it is organized, and when it is reclaimed, instead of always using one global heap allocator.

For Azora, I would make allocation depend on an Allocator interface rather than hard-coding malloc-style behavior into alloc.

What custom allocators are for

They are useful when the default heap is not ideal.

Common cases:

Arena allocation: allocate many objects quickly, then free all of them at once.
Pool allocation: efficiently allocate many objects of the same size.
Stack/bump allocation: extremely fast temporary allocations.
Game engines: separate frame, scene, audio, physics, and asset memory.
Real-time systems: avoid unpredictable pauses or heap fragmentation.
Debugging: track leaks, allocation sites, sizes, and double frees.
Embedded systems: allocate from fixed memory regions without an OS heap.
Shared or mapped memory: allocate inside memory visible to another process or device.
Alignment-sensitive data: SIMD, GPU buffers, cache-line alignment.

A custom allocator does not usually construct objects. It manages raw memory.

Minimal allocator interface

A low-level allocator should work with bytes, sizes, and alignment:

```azora
prot Allocator {
    unsafe func allocate(self: Self!, size: USize, align: USize)
}
```