# Azora Thread Safety and Multithreading - DIP Proposal

> Status: **proposed**. Nothing in this document is implemented in `0.0.5`.
> Target: `0.0.6`.

Azora already decides *who owns a value* and *who may look at it* - that is
`OWNERSHIP_BORROWING_DIP.md`. This proposal answers the question that model
leaves open: **on which thread**.

The claim it makes is the one worth making:

> A program that compiles without `unsafe` has no data races.

Everything below exists to earn that sentence.

---

## 1. Scope

Azora has two kinds of concurrency and they are not the same thing.

| aspect        | tasks (`async func`, `await`, `delay`, `launch`) | threads (this DIP)  |
|---------------|--------------------------------------------------|---------------------|
| unit          | coroutine                                        | OS thread           |
| suspends with | `await`, `delay`                                 | nothing - it blocks |
| parallel?     | not necessarily                                  | yes                 |
| owned by      | the scheduler                                    | `JoinHandle`        |
| governed by   | §15 of the ownership DIP                         | `Send` / `Sync`     |

`async` is about *not blocking*. Threads are about *running at the same time*.
A task that never leaves its thread can share whatever it likes with the tasks
beside it; a value that crosses to another thread cannot. That distinction is
why thread safety needs its own capabilities rather than reusing the async
rules.

This DIP covers the `Send` and `Sync` capabilities, `std.thread`, `std.mutex`,
atomics, how channels get their type, and what the compiler must check. It does
not change the task model.

---

## 2. Where the standard library actually is

Stating this plainly, because the gap is larger than the file listing suggests.

`std/parallelism/` contains three files, all embedded into `AzStdlib` (the build
walks `std/` and takes every `.az`), and all of them lean on types that do not
exist:

- **`std/parallelism/sync.az`** declares `Guard<T>`, `CountDownLatch<T>`,
  `SpinLock<T>` and `withMutex`. Every one of them names `Mutex` or `Atomic`.
  **Neither type is defined anywhere** - not in `std/`, not as a compiler
  builtin. The module also declares itself `module std.pareallelism.sync`,
  which is a typo, so it cannot be imported under the name its path implies.
- **`std/parallelism/channel.az`** declares `Channel<T>` over `Queue<T>` and
  `Mutex`. Because `Mutex` is missing, `channel()` is still a compiler builtin
  registered in `SymbolCollector`, whose own comment says relocation into std is
  "blocked until Channel.az's Mutex/Queue dependencies are restored".
- **`std/parallelism/thread.az`** declares `sleep`, `spawnAsync` and `joinAll`  - 
  but all three are `async func` over `delay`, so they are *task* helpers
  wearing thread names. `joinAll(count)` waits `count` milliseconds and joins
  nothing.

So there is no threading today. There is a set of names that look like it. The
first deliverable of this DIP is therefore not new API but making the existing
names honest, and §12 sets out that migration.

What *is* real and can be built on:

- `SyncShared<T>` (`std/memory/syncshared.az`) - a reference-counted pointer
  whose count is documented as synchronised, and which is deliberately `Clone`
  but never `Copy`.
- `Shared<T>`, `Unique<T>`, `Weak<T>` - the single-thread counterparts.
- The capability machinery itself: `bridge spec`, `requires`,
  `impl [A, B] for T`, field-wise derivation, and `where T is C` as a generic
  constraint.

---

## 3. Two capabilities

Add to `std/traits/traits.az`, beside `Clone` and `Copy`:

```azora
/**
 * A value that may be given to another thread.
 *
 * Sending is a move across a thread boundary: exactly one thread owns the
 * value at a time, so what `Send` rules out is not sharing but *unsynchronised
 * bookkeeping* - a reference count two threads could bump at once, or a cache
 * that assumes a single writer.
 */
@Since("0.1")
bridge spec Send
 
/**
 * A value that may be *borrowed* from more than one thread at a time.
 *
 * `Sync` is the stronger promise: several threads holding `T&` simultaneously
 * must be sound. It is defined by that sentence and nothing else - `T` is
 * `Sync` exactly when `T&` is `Send`.
 */
@Since("0.1")
bridge spec Sync
```

Both are `bridge` for the same reason `Clone` is: the compiler derives them
field-wise, and a type only writes something down when the derivation is wrong.
Neither has members. They are claims about a type, not operations on it.

### Why `Sync` does not `require` `Send`

`Copy requires Clone` because implicit duplication is explicit duplication the
compiler inserted - one really is the other. `Send` and `Sync` are independent,
and this DIP is explicit about it so nobody adds the `requires` on symmetry
grounds:

- A **`Mutex<T>`** whose `T` cannot be shared is `Sync` - the lock is what makes
  concurrent borrows safe - while remaining `Send` only if `T` is.
- A **lock guard** is `Sync` (borrowing it from another thread is harmless) but
  not `Send`: some platforms require the thread that locked to be the thread
  that unlocks.
- A **single-writer cell** is `Send` (move it, then it has one owner again) but
  not `Sync` (two threads writing at once is the race).

Three shapes, three different answers. One `requires` could not express them.

---

## 4. Derivation

Both capabilities derive structurally, the way `Copy` already does in §21 of the
ownership DIP:

| form                                           | `Send` when             | `Sync` when             |
|------------------------------------------------|-------------------------|-------------------------|
| primitive (`Int`, `Bool`, `Char`, `String`, …) | always                  | always                  |
| `pack`                                         | every field is `Send`   | every field is `Sync`   |
| `enum` / `variant enum`                        | every payload is `Send` | every payload is `Sync` |
| `unsafe union`                                 | never                   | never                   |
| `T*` (raw pointer)                             | never                   | never                   |
| `T&`                                           | `T` is `Sync`           | `T` is `Sync`           |
| `T!`                                           | `T` is `Send`           | never                   |
| `List<T>`, `Set<T>`, `Map<K, V>`               | elements are `Send`     | elements are `Sync`     |

Three lines there carry the weight:

- **`unsafe union` derives nothing**, exactly as it derives no `Clone` today.
  Reading a member no one wrote is already undefined; doing it from two threads
  does not need a separate rule.
- **`T&` : `Send` when `T` is `Sync`** - this *is* the definition of `Sync`
  from §3, written as a derivation rule so the checker has something to apply.
- **`T!` : never `Sync`.** An exclusive borrow is exclusive; handing it to two
  threads contradicts the word.

A type carrying a field it should not - a raw pointer it manages itself, a
handle whose thread-affinity the compiler cannot see - simply fails to derive,
and the diagnostic names the field (§11).

### What the existing types get

| type            | `Send`                           | `Sync`            | why                                                                                                    |
|-----------------|----------------------------------|-------------------|--------------------------------------------------------------------------------------------------------|
| `Unique<T>`     | when `T` : `Send`                | when `T` : `Sync` | one owner; moving it moves the only one                                                                |
| `Shared<T>`     | **no**                           | **no**            | the count is a plain `Int`; two threads retaining at once loses an increment                           |
| `SyncShared<T>` | when `T` : `Send` **and** `Sync` | same              | many owners across threads means many borrowers across threads, so the pointee needs both              |
| `Weak<T>`       | **no**                           | **no**            | it upgrades to a `Shared`                                                                              |
| `Mutex<T>`      | when `T` : `Send`                | when `T` : `Send` | the lock supplies the synchronisation; the contents only ever need to *move* into the critical section |
| `Guard<T>` (§7) | **no**                           | when `T` : `Sync` | unlock belongs to the locking thread                                                                   |
| `Channel<T>`    | when `T` : `Send`                | when `T` : `Send` | it exists to move values between threads                                                               |
| `Atomic<T>`     | always                           | always            | that is the point of it                                                                                |

`Shared` and `SyncShared` being different rows is the whole reason both exist.
Today the difference is a comment in `std/memory/shared.az`; under this proposal
it is a compiler error, which is what a reader of that comment already assumes.

`SyncShared<T>` needing `T: [Send, Sync]` rather than just `Send` is the row
people get wrong: several owners on several threads can all borrow the pointee
at once, so the pointee is genuinely shared, not merely moved.

---

## 5. `std.thread`

```azora
module std.parallelism.thread

realm std {
    /**
     * A running thread and the value it will produce.
     *
     * The handle owns the thread: dropping one without `join` is a leak the
     * compiler cannot see, so `JoinHandle` is never `Copy` and `join` takes
     * ownership.
     */
    @Since("0.1")
    pack JoinHandle<T> { var _id: Int }

    impl JoinHandle<T> {
        /** Waits for the thread and returns what its body returned. */
        @Since("0.1")
        func join(self: Self): T ?! ThreadPanic

        /** Whether the thread has finished, without waiting. */
        @Since("0.1")
        prop isFinished[self: Self&]: Bool
    }

    /**
     * Runs `body` on a new OS thread.
     *
     * Everything `body` captures leaves this thread with it, so every capture
     * must be `Send`; the result comes back the same way, so `T` must be too.
     */
    @Since("0.1")
    func spawn<T>(body: () -> T): JoinHandle<T> where T is Send

    /** Blocks the calling thread. Not `delay` - nothing here suspends. */
    @Since("0.1")
    func sleepMillis(ms: Int)

    /** How many threads the platform can genuinely run at once. */
    @Since("0.1")
    prop hardwareThreads: Int
}
```

`join` taking `self` by value rather than `self&` is deliberate: joining twice
is not a thing, and the ownership model already reports the second attempt as
use-after-take with a diagnostic that says so.

Note what is missing: there is no `Thread` object with `start()`. A thread that
exists but is not running is a state with no use and two ways to get it wrong.

### Scoped threads

`spawn` requires `Send` captures, which rules out lending a thread a borrow of
something on the stack - correct in general, needlessly strict when the threads
are joined before the borrow ends. A scope makes that provable:

```azora
@Since("0.1")
pack ThreadScope { var _threads: List<Int> }

impl ThreadScope {
    /** Spawns inside the scope. Captures need only outlive the scope. */
    @Since("0.1")
    func spawn<T>[self: Self!](body: () -> T): JoinHandle<T> where T is Send
}

/** Runs `body`, then joins every thread it spawned before returning. */
@Since("0.1")
func withThreads<R>(body: (ThreadScope!) -> R): R
```

Used:

```azora
var data = @arr[1, 2, 3, 4]

std::withThreads { s ->
    fin left  = s.spawn { sum(data.&[0..2]) }
    fin right = s.spawn { sum(data.&[2..4]) }
    trace left.join() + right.join()
}

// every spawned thread has been joined by here, so the borrows of `data`
// ended before `data` did
```

The name is `withThreads` and not `scope` because `scope` is a keyword in Azora.

`withThreads` is where §13's borrow origins pay off: the guarantee is exactly
"a borrow taken inside the scope does not outlive it", and the origin syntax
already says that - §7 spells a signature with one.

---

## 6. Captures that cross a thread

This is §16 of the ownership DIP with a thread boundary in the middle, and it is
where most real diagnostics will come from.

The ownership DIP already established that a closure captures by borrow, copy,
clone, or `take`, and that a `take` inside a closure moves the outer binding  - 
that part is implemented. Threading adds one rule:

> A closure passed to `spawn` may capture a value by borrow only inside
> `withThreads`. Everywhere else, every capture must be `Send` and must be
> owned: taken, cloned, or `Copy`.

```azora
var config = Config.production()

// rejected - `spawn` outlives `config`, and the capture is a borrow
fin h = std::spawn { validate(config.&) }

// take: the thread owns it, and `config` is unusable here afterwards
fin h = std::spawn { validate(take config) }

// clone: both sides have one
fin h = std::spawn {
    fin mine = config.clone()
    validate(mine.&)
}

// Copy: `Int` is copied into the closure, and `attempts` stays usable
var attempts = 3
fin h = std::spawn { retry(attempts) }
```

Which mirrors §15's async rules on purpose. A borrow held across an `await` and
a borrow shipped to another thread fail for the same reason - the owner may be
gone - so they should be learned once and the diagnostics should rhyme.

---

## 7. `std.mutex`

The single most important decision in this DIP: **a mutex owns its data.**

```azora
module std.parallelism.mutex

realm std {
    inline prop mutexCond<T>: Bool = T is Send
    
    /**
     * A value reachable only by locking.
     *
     * Owning the value rather than sitting beside it is what makes the lock
     * unforgettable: there is no path to the `T` that does not go through
     * `lock`, so "I forgot to take the mutex" stops being a class of bug.
     */
    @Since("0.1")
    pack Mutex<T> where mutexCond<T> {
        var _value: T
        var _handle: Int
    }

    /**
     * Proof that the lock is held, and the only way to reach the value.
     *
     * The guard borrows from the mutex - `[self]` - so it cannot outlive it,
     * and unlocking happens when the guard's scope ends rather than when
     * someone remembers.
     */
    @Since("0.1")
    pack Guard<T> derive [Deref, DerefMut] {
        var _owner: Mutex<T>*
    }

    impl Mutex<T> {
        /** Blocks until the lock is held. */
        @Since("0.1")
        func lock[self: Self&](): Guard<T>&[self]

        /** Takes the lock if it is free, otherwise returns null. */
        @Since("0.1")
        func tryLock[self: Self&](): Guard<T>&?[self]

        /** The value, without locking - sound only because `self` is exclusive. */
        @Since("0.1")
        func get[self: Self!](): T![self]
    }
    
    impl Guard<T> {
        @Since("0.1")
        func value[self: Self!](): T![self]
    }

    @Since("0.1")
    func mutexOf<T>(value: T): Mutex<T> where mutexCond<T>
}
```

Three things fall out of that shape, each a bug class removed:

1. **The lock cannot be forgotten** - `_value` is private, so `lock()` is the
   only route in.
2. **The lock cannot be leaked** - `Guard<T>&[self]` borrows from the mutex, so
   §13's origin check rejects returning a guard from the function that locked.
3. **The lock is released on scope exit**, including on an early `return`.

`get[self: Self!]` is the exception that proves it: if you hold the *exclusive*
borrow of the mutex, no other thread can hold anything, so locking would be pure
ceremony. The type says why it is safe.

Compare against `std/parallelism/sync.az` as it stands today, where `Guard<T>`
holds `_mutex` and `_value` as two independent fields and exposes
`func get(): T` - a copy taken under lock and then used outside it. The callback
forms (`read(action:)`, `write(action:)`) are safe but cannot express "look at
it, decide, then modify it" without unlocking in between.

### The rest of `std.mutex`

```azora
/** Many readers or one writer. */
@Since("0.1")
pack RwLock<T> { var _value: T
    var _handle: Int }

impl RwLock<T> {
    @Since("0.1")
    func read[self: Self&](): ReadGuard<T>&[self]

    @Since("0.1")
    func write[self: Self&](): WriteGuard<T>&[self]
}

/** Runs its initializer exactly once, however many threads arrive. */
@Since("0.1")
pack Once { var _state: Atomic<Int> }

impl Once {
    @Since("0.1")
    func call[self: Self&](body: () -> Unit)
}

/** Waits until a count reaches zero. Replaces the `delay`-based CountDownLatch. */
@Since("0.1")
pack Latch { var _remaining: Atomic<Int> }
```

---

## 8. Atomics

`Atomic<T>` is the primitive everything above is built from, and the one place
where the compiler must emit real instructions rather than call into a runtime.

```azora
@Since("0.1")
pack Atomic<T> where T: Copy { var _value: T }

impl Atomic<T> {
    @Since("0.1")
    func load[self: Self&](order: Ordering = .Acquire): T

    @Since("0.1")
    func store[self: Self&](value: T, order: Ordering = .Release)

    @Since("0.1")
    func swap[self: Self&](value: T, order: Ordering = .AcqRel): T

    /** Compare-and-swap. Returns whether it happened. */
    @Since("0.1")
    func cas[self: Self&](expected: T, desired: T, order: Ordering = .AcqRel): Bool

    @Since("0.1")
    func add[self: Self&](delta: T, order: Ordering = .AcqRel): T where T: Number
}

@Since("0.1")
enum Ordering { Relaxed, Acquire, Release, AcqRel, SeqCst }
```

Every method takes `self&`, not `self!` - an atomic is precisely a value several
shared borrows may write, and that is what makes it `Sync`. It is the one type
in the language where a shared borrow mutates, which is why it is the one type
the compiler must know by name.

Defaulting the ordering to acquire/release rather than `SeqCst` is a deliberate
break from the usual default: `SeqCst` is what people choose when they have not
thought about ordering, and acquire/release is what they almost always meant.
`Relaxed` stays available and stays spelled out.

Backends: `LlvmCodegen` emits `load atomic` / `store atomic` / `cmpxchg` /
`atomicrmw` directly. `WasmCodegen` emits the threads-proposal instructions and
traps where the target lacks them. `IrInterpreter` runs one thread, so every
ordering is a plain read or write - the same kind of caveat already recorded for
byte reinterpretation.

---

## 9. Channels

With `Mutex` real, `Channel<T>` moves out of the compiler and into `std`,
closing the `SymbolCollector` note that has been waiting on it:

```azora
@Since("0.1")
pack Channel<T> where T: Send { … }

impl Channel<T> {
    /** Hands the value to the channel. The sender no longer owns it. */
    @Since("0.1")
    func send[self: Self&](value: T) ?! ChannelClosed

    /** Blocks until a value arrives, or the channel closes. */
    @Since("0.1")
    func receive[self: Self&](): T ?! ChannelClosed

    /** Takes a value if one is waiting. */
    @Since("0.1")
    func tryReceive[self: Self&](): T?
}
```

`send(value: T)` takes ownership, so the ownership model reports a use of the
sent value afterwards without any channel-specific rule - the existing "use of
taken value" diagnostic already says the right thing.

The `where T: Send` on the pack is what makes a channel a *thread* channel: it
is the type-level statement that values leave the sending thread.

---

## 10. The escape hatch

Some types are thread-safe for reasons the compiler cannot see - a handle whose
C library serialises internally, a pointer into memory the OS guarantees. For
those, and only those:

```azora
/**
 * `libfoo` documents every entry point as internally synchronised, and the
 * handle is opaque to us, so the derivation cannot see what makes this sound.
 */
@Since("0.1")
unsafe impl [Send, Sync] for FfiHandle
```

`unsafe impl` is new syntax and is the *only* way to claim either capability by
hand - an ordinary `impl Send for X` is rejected with a message pointing here.
The asymmetry with `Clone` is intended: writing a wrong `clone` produces a wrong
value, while writing a wrong `Send` produces a race, and races are not local to
the type that caused them.

Consistent with `unsafe union` and `unsafe func`, the marker sits on the
declaration and the reason belongs in the doc comment. A reviewer grepping
`unsafe impl` sees the complete list of thread-safety claims the program is
trusting.

---

## 11. Diagnostics

Same shape as the ownership diagnostics: name the value, say what is wrong, name
every fix.

```
line 12: cannot send 'config' to another thread - 'Config' is not Send because
         field 'cache' is a Shared<Entry>, whose reference count is not
         synchronised; use SyncShared<Entry>, or send a 'config.clone()'
```

```
line 8: closure passed to 'spawn' borrows 'data', which may not outlive the
        thread; transfer ownership with 'take data', create an independent value
        with 'data.clone()', or spawn inside 'std::withThreads { }' so the
        thread is joined before the borrow ends
```

```
line 31: 'guard' borrows from 'counter' and cannot be returned - the lock would
         outlive the mutex; return the value instead, or take the lock in the
         caller
```

```
line 5: 'impl Send for FfiHandle' cannot be written by hand - Send is derived
        from a type's fields. If the derivation is wrong because the safety
        comes from outside Azora, write 'unsafe impl Send for FfiHandle' and say
        why in the doc comment
```

The first one is the one that matters: **name the field**. "`Config` is not
`Send`" sends the reader searching a type they probably did not write. "field
`cache` is a `Shared<Entry>`" ends the search.

---

## 12. Implementation plan

Ordered so that each phase is useful on its own and nothing lands half-checked.

| phase | work                                                                                          | touches                                                                                     |
|-------|-----------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| 1     | `Atomic<T>` and `Ordering` as compiler-known types; atomic instructions in all three backends | `SymbolCollector`, `LlvmCodegen`, `WasmCodegen`, `IrInterpreter`                            |
| 2     | `Send` / `Sync` specs and field-wise derivation, no enforcement yet                           | `std/traits/traits.az`, `std/traits/core.az`, `SymbolCollector.deriveOwnershipCapabilities` |
| 3     | `Mutex<T>`, `Guard<T>`, `RwLock<T>`, `Once`, `Latch` in `std.parallelism.mutex`               | `std/parallelism/`                                                                          |
| 4     | `spawn` / `JoinHandle` / `withThreads`, with `where T: Send` enforced                         | `std/parallelism/thread.az`, `TypeResolver`                                                 |
| 5     | capture checking at `spawn`; borrow captures allowed only under `withThreads`                 | `TypeResolver`                                                                              |
| 6     | `unsafe impl`, and rejecting a plain `impl Send`                                              | `Parser`, `SymbolCollector`                                                                 |
| 7     | `Channel<T>` moves into std; drop the `channel()` builtin                                     | `std/parallelism/channel.az`, `SymbolCollector`                                             |

Phase 2 is where the interesting reuse is. Capability derivation is already a
fixed-point over a type's fields; `Send` and `Sync` are two more capabilities
running through the same pass, and the per-field rules in §4 are the only new
input.

### Cleanups this proposal takes on

Independent of any new feature, and worth doing whether or not the rest lands:

- fix `module std.pareallelism.sync` → `module std.parallelism.sync`;
- delete `joinAll`, which sleeps and calls it joining;
- rename `sleep` to `sleepMillis`, splitting the task version (`delay`) from the
  thread version (blocks);
- rename `spawnAsync` to `launchAfter`, since a scheduling delay is what it
  actually adds;
- reconcile the existing `Guard<T>` in `sync.az` with the mutex-owned guard in
  §7. They are different designs sharing one name, and §7's is the one to keep.

---

## 13. What this does not propose

- **Thread pools and work stealing.** A pool is built from `spawn`, `Channel`
  and `Latch`; it needs no language support, and shipping one before the
  primitives settle would fix the wrong shape.
- **`async` over multiple threads.** The task scheduler staying single-threaded
  keeps §15 as it is. A work-stealing scheduler would require every task's
  captured state to be `Send`, which is a separate and much larger proposal.
- **Structured concurrency for threads beyond `withThreads`.** Scopes and joins
  cover it; cancellation trees are a design of their own.
- **Thread-local storage.** Already handled by the existing per-coroutine copy
  semantics, and it deserves its own look before being extended to threads.
- **A memory model beyond the atomic orderings.** Azora inherits the C++/LLVM
  model. Writing down a different one is not work this language needs to do.

---

## 14. Open questions

1. **Should `String` be `Sync`?** It is `Copy` today on the grounds of being an
   immutable value; if that holds, `Sync` follows. If interning or
   copy-on-write ever appears behind it, both claims fail at once. Worth
   deciding once, deliberately, and recording next to the `Copy` decision.
2. **Is `Guard<T>` being `!Send` worth its cost?** It buys correctness on
   platforms with thread-affine mutexes and costs the ability to hand a locked
   guard to a helper thread. Most programs never notice; the ones that do,
   notice sharply.
3. **Does `spawn` need `where T: Send` on the closure type itself**, or is
   checking each capture (§6) enough? Capture checking gives better
   diagnostics; a bound on the closure type composes better through generics.
   Probably both, with the bound as the backstop.
4. **Should `Atomic<T>` be `Copy`?** Copying an atomic is almost always a bug  - 
   the copy is a different location - but field-wise derivation will make it
   `Copy` unless it is special-cased. It should be special-cased.
