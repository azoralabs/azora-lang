# Azora Parallelism - DIP Implementation Profile

> Status: **the runtime is real; the library is not.** Tasks run on a thread
> pool natively and on OS threads under LLVM. `std.parallelism` describes a
> synchronisation library that **does not compile** - `Mutex` and `Atomic` are
> named throughout and defined nowhere.

`CONCURRENCY_DIP.MD` covers tasks, `await` and channels - the *structure* of
concurrent work. This document covers what happens when that work genuinely runs
at the same time: threads, shared state, and the primitives for guarding it.

---

## 1. Parallelism is real

Azora's tasks are not cooperative-only. Under the interpreter they run on a
thread pool; under LLVM each is an OS thread.

| backend     | a task is                               | joined by      |
|-------------|-----------------------------------------|----------------|
| interpreter | a coroutine on the default dispatcher   | the scope      |
| LLVM        | a `pthread`, in an `%azora.task` handle | `pthread_join` |
| Wasm        | **inline** - it is not a task at all    | -              |

So two tasks can be on two cores, touching the same value, at the same
instant. Everything below follows from that.

### 1.1 Structured joining

LLVM emits a scope runtime - `__azora_scope_init`, `__azora_scope_attach`,
`__azora_scope_join_all` - so tasks spawned in a scope are joined when it ends.
A task cannot outlive the scope that started it.

That is the one piece of structured concurrency that exists. There is no
cancellation and no scope a user can name (`CONCURRENCY_DIP.MD` §8).

---

## 2. `std.parallelism` does not compile

Three modules. Importing any of them is fine, because injection is lazy;
**touching any type in two of them is a compile error.**

| module                    | using it                                    |
|---------------------------|---------------------------------------------|
| `std.parallelism.thread`  | ✅ compiles                                  |
| `std.parallelism.sync`    | ⬜ `undefined function 'Mutex'` / `'Atomic'` |
| `std.parallelism.channel` | ⬜ `undefined function 'Mutex'`              |

```azora
import std.parallelism.sync
func main() {
    fin s = SpinLock<Int>(0)   // line 123: undefined function 'Atomic'
}
```

This is the state to fix before anything else in this document matters. A
standard library that names a type it does not define is worse than one that
omits the feature: the omission is discoverable, the dangling reference is only
discoverable by using it.

---

## 3. What `sync.az` describes

The module is written as though `Mutex` and `Atomic` existed. Taking it as a
specification of the intended surface:

### 3.1 `Guard<T>` - a value behind a lock

```azora
pack Guard<T> {
    var _mutex: Mutex = Mutex(0)
    var _value: T
}
```

`read(action)`, `write(action)` and `get()` each take the lock, touch the value,
and release. The shape is right: the lock and the value it protects are one
object, so there is no way to reach the value without the lock.

- ⬜ `write` takes `(T) -> T` and replaces the value, which cannot express an
  in-place mutation of a large aggregate
- ⬜ Nothing stops `read`'s action from storing the value somewhere and using it
  after the lock is released. Guarding this needs the borrow checker to know the
  action's argument does not escape

### 3.2 `CountDownLatch<T>`

`countDown()` increments an `Atomic`; `isReached` compares it against a target.

- ⬜ The name says count *down* and the implementation counts up
- ⬜ There is no `await` - a latch nothing can wait on is a counter

### 3.3 `SpinLock<T>`

```azora
func acquire() { while !flag.cas(0, 1) { } }
```

A compare-and-swap spin. It needs `Atomic.cas`, and it needs a memory ordering
the language does not have.

- ⬜ A spin lock with no yield burns a core while it waits
- ⬜ `T` is unused in all three of `Guard`'s siblings - `CountDownLatch<T>` and
  `SpinLock<T>` are generic over nothing

---

## 4. What `thread.az` describes

Sixty-four lines, three functions, and none of them is about threads:

```azora
async func sleep<T>(ms: T) { delay ms }

async func spawnAsync<T>(ms: T, action: () -> Unit) {
    fin handle = async { action() }
    delay ms
    await(handle)
}

async func joinAll<T>(count: T) { delay count }
```

- `sleep` is `delay` with another name.
- `spawnAsync` spawns, then **sleeps a caller-supplied duration**, then joins -
  so it is `async { }` plus an arbitrary pause.
- `joinAll(count)` **joins nothing**. It sleeps for `count` milliseconds and
  returns. A program that calls it to wait for its tasks is relying on a
  coincidence.

- ⬜ Delete the module or implement it. `joinAll` is actively misleading: it has
  the name of a synchronisation primitive and the behaviour of a sleep

---

## 5. Two channels

There are two unrelated things called a channel:

|          | `channel()`        | `Channel<T>`         |
|----------|--------------------|---------------------------|
| where    | a compiler builtin | `std.parallelism.channel` |
| state    | ✅ works            | ⬜ does not compile        |
| blocking | suspends           | asserts and fails         |

The builtin is the one that works. The library one is a `Queue` behind a
`Mutex`, and `receive` on an empty channel **asserts** rather than waiting -
which is not a channel, it is a queue with a lock.

- ⬜ Decide which one survives. `CONCURRENCY_DIP.MD` §5 already says the builtin
  should move into the standard library once `Mutex` exists; that would make it
  the same object as this one

---

## 6. What has to be built

### 6.1 The primitives

- ⬜ **`Atomic<T>`** with `load`, `store`, `add`, `cas`, and a stated memory
  ordering. Every one of `sync.az`'s types depends on it
- ⬜ **`Mutex`** with `lock`, `unlock`, and a scoped form so a lock cannot be
  leaked by an early return
- ⬜ **A condition variable or equivalent**, so a latch can be waited on rather
  than polled
- ⬜ Backend implementations for all three. The interpreter can use the host's;
  LLVM needs the pthread equivalents; Wasm has no threads at all (§1)

### 6.2 The type system's part

This is the half that cannot be done in the library.

- ⬜ **`Send` / `Sync` capabilities.** Nothing currently stops a value that is
  unsafe to share from crossing a task boundary. Without them, "data-race free"
  is a hope
- ⬜ **What `Shared<T>` means across threads.** `Shared` and `SyncShared` both
  exist in `std.memory`; whether the plain one is rejected at a task boundary is
  unstated
- ⬜ **Whether a `solo pack`'s mutable state is protected.** Two tasks resolving
  one singleton share its fields, and nothing says whether that is the author's
  problem
- ⬜ **A memory model.** Ordering, visibility and what a data race *is* in
  Azora terms. Every atomic operation's meaning depends on this and none of it
  is written down

### 6.3 Testing the claim

- ⬜ **The data-race-freedom claim is untestable.** No stress test, no
  property test, nothing that would fail if a regression introduced a race
- ⬜ **No parallel benchmark.** Whether the thread pool actually scales is
  unmeasured

---

## 7. Rules

These hold today.

1. A task may run on another thread. Two tasks may touch one value at the same
   instant.
2. A task is joined by the scope that spawned it, and cannot outlive it.
3. Under Wasm there is no parallelism: a task runs inline at its creation.
4. Sharing mutable state between tasks is currently unguarded and unchecked.

---

## 8. Related

- `CONCURRENCY_DIP.MD` - tasks, `await`, channels, and the structure above this
- `MEMORY_MANAGEMENT_DIP.MD` - `Shared`, `SyncShared`, and what crosses threads
- `THREAD_SAFETY_DIP.MD` - the proposed safety story
- `ROADMAPs/VERSION_0_1_ROADMAP.MD` §3.3
