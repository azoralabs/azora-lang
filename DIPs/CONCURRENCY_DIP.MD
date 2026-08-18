# Azora Concurrency - DIP Implementation Profile

> Status: **partly implemented.** The task and channel surface works under the
> interpreter with real parallelism. The claims this document corrects are
> marked; §7 is what has to be true before `0.1`.

Azora's concurrency is built from three things: a **async func**, which is work that
may run elsewhere; **`await`**, which is how you get its result; and a
**channel**, which is how two tasks talk. Everything else is a library type.

```azora
async func fetch(id: Int): Int {
    delay 10
    return id * 2
}

async func main() {
    fin handle = fetch(21)
    println(await handle)     // 42
}
```

---

## 1. `async` is contextual, not reserved

`async` is not a keyword. It names a task only when `func` follows it;
everywhere else it is an ordinary identifier:

```azora
var async = 5              // still a usable name
println(async)        // 5
```

That is deliberate and tested: adding a concurrency vocabulary should not take
a common word away from every program that never uses it. The parser decides by
looking one token ahead - `isAsyncFuncAt`.

---

## 2. Spawning

Two spellings, both producing a `Task<T>`:

```azora
async func compute(n: Int): Int { return n * 2 }   // a declaration
fin a = compute(21)                                 // calling it spawns

fin b = async { 21 }                                // an async lambda
```

An `async func` declaration is an ordinary function whose call yields a task
rather than a value. `func` is never written in a lambda expression.

A task begins running when it is created, not when it is awaited. A task whose
handle is discarded still runs: it is a structured child of the program's root
scope, and the program does not exit until it finishes.

### 2.1 `launch { }` does not exist

- ⬜ **The README and the roadmap both claim a `launch { }` fire-and-forget
  form. It does not parse.** `launch { … }` fails with
  `undefined variable 'launch'`. The `__launch` intrinsic behind it is real -
  registered in `SymbolCollector`, implemented in the interpreter and in LLVM,
  and callable as `__launch({ … })` - but no surface syntax produces it.
  A comment in `Parser.parsePrimary` describes the desugaring, sitting on the
  branch that raises "Unexpected token".

Either give it syntax or delete the intrinsic. Discarding an `async { }` handle
already covers the same intent, which is an argument for deleting it.

---

## 3. `await`

```azora
fin result = await handle
```

`await` suspends the current task until the awaited one completes and yields its
result. It is a prefix operator on a `Task<T>`, giving `T`.

Awaiting anything else is an error. Awaiting the same handle twice is not
specified (§7).

### 3.1 Target structured combinators

Structured competition is a standard-library API, not language syntax:

```azora
fin winner = await race<Result> {
    branch { return await localResult() }
    branch { return await remoteResult() }
}
```

`race`, `sync`, and `rush` are functions. Every child is declared
with the fully qualified `branch`; `race`, `branch`, `sync`, and `rush` are
not keywords.

The conceptual contracts are:

```azora
pack RaceBuilder<T>

async func race<T>(
    configure: [RaceBuilder<T>!]() -> Unit
): T

func branch<T>[builder: RaceBuilder<T>!](
    body: async () -> T
)
```

The expected async callable type permits the concise `branch { ... }`
trailing lambda. `branch` requires the contextual builder supplied by
`race`, `sync`, or `rush`; calling it elsewhere is a type error,
not an ambient registration side effect.

`race` starts every branch, returns the first completion, cancels losers,
and joins their cleanup. Source order breaks simultaneous completion ties.
Parent cancellation cancels and joins every branch.

`sync` waits for every branch and returns `Tuple<...T>` in source
order. A failing branch cancels and joins its siblings before propagation.
`rush` returns the first completion but leaves the other branches attached
to the surrounding async owner.

No `scope` or `block` wrapper is needed: each `branch` body is already
the sequential lexical block, and the parent combinator owns its lifetime.

---

## 4. `delay`

```azora
delay 10        // milliseconds
```

Suspends the current task. It is a statement-position prefix form, lowering to
`__delay(ms)`, and is a suspension point rather than a busy wait - other tasks
run while it is pending.

---

## 5. Channels

```azora
fin c = channel()
c.send(1)
println(c.receive())
c.close()
```

A channel is an unbounded queue two tasks can hand values through. `send` and
`receive` are suspension points: a `receive` on an empty channel waits for a
`send`.

`channel` is a **compiler builtin**, not a standard-library function - one of
only two concurrency names that is not `__`-prefixed. It is meant to live in
`std.concurrency` and is blocked there on `Mutex` and `Queue`, which the
standard library does not define.

- ⬜ Move `Channel<T>` out of the compiler once `Mutex` exists
- ⬜ Bounded channels, `trySend` / `tryReceive`, and select

---

## 6. Streams are library types

`Sequence<T>` and `Flow<T>` are ordinary packs in
`std.concurrency.generators`, not language constructs. A producer is an ordinary
`func` whose return type says which it is:

```azora
fin values = sequence([s: SequenceScope<Int>!]{
    yield(1)
    yield(2)
})
values.collect({ n -> println(n) })
```

The builder takes a lambda with a **bracketed contextual receiver**, and `yield`
is a free function on that receiver. `flow` is the same shape with `emit` and a
`FlowCollector`.

### 6.1 They are eager, and `Flow` is not asynchronous

- ⬜ **`sequence` runs its producer immediately** and collects into an
  `ArrayList`; the returned `Sequence<T>` is that list. Nothing is lazy, and
  nothing is computed on demand.
- ⬜ **`Flow<T>` is the same eager list.** It is not asynchronous, has no
  suspension points, and differs from `Sequence<T>` only in the names of its
  builder and its collector.

The names promise laziness and asynchrony; the implementation is a list
builder. Either the semantics or the names should change before `0.1`, because
a `Flow` that silently computes everything up front is worse than no `Flow`.

---

## 7. Execution model, per backend

The three backends do not agree, and the disagreement is not a detail - a
program's concurrency means something different on each.

|             | interpreter                     | LLVM             | Wasm                         |
|-------------|---------------------------------|------------------|------------------------------|
| task spawn  | `Dispatchers.Default` coroutine | `pthread_create` | runs inline                  |
| `await`     | suspends                        | `pthread_join`   | **no-op passthrough**        |
| `delay`     | suspends the coroutine          | supported        | **degrades - no host clock** |
| parallelism | real, thread pool               | real, OS threads | none                         |

Under Wasm, `await` lowers to its operand and tasks run synchronously at the
point of creation. A program that relies on interleaving behaves differently
there, and nothing warns about it.

- ⬜ **Wasm concurrency is a silent degradation.** Decide: implement it, or make
  a task a compile error for that target. Silently changing the meaning is the
  one option that should be off the table

---

## 8. What is missing

The blocking items. Each is something the language currently implies without
providing.

- ⬜ **`Mutex` and `Atomic` do not exist.** `std/parallelism/sync.az` declares
  `Guard`, `CountDownLatch`, `SpinLock` and `withMutex` against an `Atomic` that
  is not defined anywhere, so the module does not compile when used
- ⬜ **`std/parallelism/thread.az` is task helpers wearing thread names.**
  `joinAll(count)` sleeps and joins nothing
- ⬜ **No `Send` / `Sync` capabilities.** Nothing stops a value that is unsafe
  to share from crossing a task boundary, which makes the data-race-freedom
  claim unenforced
- ⬜ **No cancellation and no structured concurrency.** A task cannot be
  cancelled, a parent cannot cancel its children, and there is no scope that
  guarantees its children finish
- ⬜ **No timeout primitive.** `timeout` exists but starts a task, sleeps,
  and reports whether a flag was set - it does not cancel anything
- ⬜ **Awaiting twice is unspecified**, as is awaiting from more than one task
- ⬜ **Failure propagation is undefined.** What happens when a task throws, and
  where the error surfaces, is not stated
- ⬜ **No fairness or scheduling guarantee**, and no way to pin work
- ⬜ **The data-race-freedom claim is untestable.** There is no stress or
  property test that would catch a regression

---

## 9. Rules

1. `async` is contextual. It introduces a task only before `func`, and stays a
   usable identifier everywhere else.
2. Calling an `async func` spawns; it does not run inline.
3. A task starts when created, not when awaited.
4. A discarded handle still runs, and the program waits for it.
5. `await` takes a `Task<T>` and gives `T`. Anything else is an error.
6. `delay` and a channel's `send`/`receive` are suspension points.
7. Streams are library types. A producer is an ordinary function.

---

## 10. Related

- `PARALLELISM_DIP.MD` - threads, synchronisation and the `std.parallelism`
  surface (currently empty)
- `COMPILER_INTRINSIC_DIP.MD` §3.3 - `__launch`, `__delay`, `channel`, `async`
- `LAMBDA_CONTEXT_CAPTURE_DIP.MD` - the closure environment a task captures
- `ROADMAPs/VERSION_0_1_ROADMAP.MD` §3.3 - the release blockers above
