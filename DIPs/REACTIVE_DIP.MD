# Azora Reactivity - DIP Implementation Profile

> Status: **implemented for the interpreter, LLVM, and WebAssembly.** The
> interpreter tracks runtime reads. Native backends preserve owner state without
> a garbage collector and lower effects from compiler-visible dependencies.
> Conditional effects (§3.3) agree across the interpreter and LLVM. Section 7
> records the remaining differences.

Azora reactivity is state management, not rendering. The language owns reactive
lifetimes and effect semantics; `std.reactive` supplies observable values,
snapshots, subscriptions, and host integration without depending on a DOM,
widget toolkit, or scene graph.

```azora
react func counter(): Int {
    remember var count = 0
    lazy fin doubled = count * 2

    effect count {
        trace { "count = ${count}" }
    }

    count += 1
    return doubled
}
```

## 1. Reactive owners

`react` qualifies a callable declaration:

```azora
react func counter(): Int { ... }
react async func poll(): Result { ... }
react prop total: Int { ... }
react async prop latest: Int { ... }
```

A reactive function may only be called from another reactive scope. This makes
the lifetime boundary visible in the call graph, much like `async` makes
suspension visible.

```azora
react func child() { }

func invalid() {
    child() // error: reactive function called from a non-reactive scope
}

react func valid() {
    child()
}
```

Each reactive callable is an owner. Its reactive bindings survive ordinary
invocations according to their declared lifetime. Effects are registered for
the duration of an invocation and deterministically disposed when it exits.

## 2. Lifetime and mutability

A reactive declaration combines one lifetime with one ordinary binding form:

```azora
remember var count = 0
remember let buffer = Buffer()
retain val session = Session()
preserve fin identity = UserId(7)
```

The lifetime and binding answer independent questions:

| lifetime | purpose |
|---|---|
| `remember` | survives reruns of the current owner |
| `retain` | survives owner recreation in the current process |
| `preserve` | participates in explicit snapshot and restoration |

| binding | rebind name | mutate value |
|---|---:|---:|
| `var` | yes | yes |
| `let` | no | yes |
| `val` | yes | no |
| `fin` | no | no |

The compiler enforces the ordinary binding rules. A `remember fin` cannot be
reassigned merely because it is reactive. Reactive lifetimes cannot be combined
with `threadlocal`; they describe owner lifetime, while `threadlocal` describes
execution-local storage.

The interpreter keeps separate stores for the three lifetimes. LLVM and Wasm
currently represent all three with allocation-free module storage and an
initialization flag. Durable `preserve` storage is explicit through
`ReactiveSnapshot<T>` and a host-provided `PreserveStore`.

## 3. Effects

### 3.1 Automatic reads

```azora
effect {
    trace { "${firstName} ${lastName}" }
}
```

The effect runs immediately. In the interpreter, each reactive cell actually
read becomes a dependency. On a later write, the effect unsubscribes, runs, and
collects its dependencies again. Conditional reads therefore change the active
dependency set correctly.

### 3.2 Explicit dependencies

```azora
effect name { validate(name) }
effect [name, locale] { rebuildLabel(name, locale) }
```

Only the listed values trigger the effect. The body may read other values
without subscribing to them. Effects run synchronously in declaration order.

### 3.3 Conditions

An unbracketed expression that is **not** a bare name is a condition, and the
body runs on its rising edge:

```azora
effect closing == true {
    shutdown()
}
```

1. the condition is evaluated in the owner;
2. the body runs when it changes from false to true;
3. it does not repeat while the condition stays true;
4. if the condition goes false and later true again, the body runs again;
5. the edge is owner state, so it survives reruns exactly as `remember` does.

A bare name keeps its old meaning. `effect ready { … }` is a dependency - it
says *when to reconsider* - while `effect ready == true { … }` also says
*whether to act*. One spelling cannot mean both, so the two are distinguished by
whether anything is asked of the value.

The condition is not polled. `effect [closing] { if closing { … } }` remains
available and is clearer when several conditions combine.

### 3.3 Cleanup

```azora
effect defer {
    connection.close()
}
```

This form has no dependencies and runs when its owner exits. It uses ordinary
LIFO defer ordering.

An effect cannot `return`, `break`, or `continue`. Re-entering an effect while it
is already running is diagnosed as a reactive cycle by the interpreter.

## 4. Lazy bindings and derived values

`lazy` always modifies a binding. It never modifies an expression:

```azora
lazy fin config = loadConfig()
lazy let cache = Cache()
```

Only `fin` and `let` are accepted. `lazy var` and `lazy val` are rejected because
a rebindable name makes the boundary between initialization and reassignment
ambiguous.

Outside a reactive owner, the initializer runs once on the first read. Inside a
reactive owner, the binding is a cached derived value: reactive cells read by its
initializer are versioned, a write invalidates the cache, and the next read
recomputes it.

```azora
react func invoice(): Decimal {
    remember var subtotal = 100.0
    remember var taxRate = 0.19
    lazy fin total = subtotal + subtotal * taxRate

    subtotal = 120.0
    return total // calculated here from the latest inputs
}
```

Dependency injection follows the same rule:

```azora
lazy fin cache = inject Cache
```

The old `fin cache = lazy inject Cache` form is rejected with a migration
diagnostic. `inject Cache` remains an ordinary expression.

## 5. `std.reactive`

The standard module exposes lower-case operations and small, typed contracts:

```azora
import std.reactive

var count = std::state(0)
var subscription = std::observe(count, { value: Int ->
    trace { "count = ${value}" }
})

count.set(1)
subscription.dispose()
```

The core surface is:

| API | purpose |
|---|---|
| `Read<T>` | read-only value, version, and subscription contract |
| `Write<T>` | `Read<T>` plus `set` and `update` |
| `State<T>` | synchronous observable mutable state |
| `Derived<T>` | value produced by a callable |
| `Subscription` | idempotent deterministic cancellation |
| `state` | create observable state |
| `computed` | create a library-level derived value |
| `observe` | subscribe and immediately receive the current value |
| `snapshot` / `restore` | explicit preserved-state transport |
| `batch` | explicit mutation boundary for adapters |
| `untracked` | calculation that introduces no language effect |
| `combine` / `@combine` | typed two-value composition |

`State<T>` notifications are synchronous and preserve registration order.
Observers added during notification participate in the next write. Disposal is
idempotent.

For a cached language-level derived value, prefer `lazy fin`. `computed` is a
library value useful when a derived producer must be passed or stored.

### 5.1 One reactive graph beyond UI

`std.reactive` is the shared state graph for applications, games, editors,
services, and UI. It does not import a renderer and does not assign special
meaning to frames, widgets, entities, or HTTP requests. Domain packages connect
their lifecycles through adapters:

- a UI observes `Read<T>` and writes through `Write<T>`
- an ECS publishes owned snapshots at deterministic phase boundaries
- an editor records committed attempts for undo and redo
- a service observes configuration or connection state

An ECS adapter must batch publication after its phase completes. Publishing
after each component write would expose partially updated worlds and make
parallel scheduling visible to observers. Reactive consumers never retain ECS
query borrows; they receive owned snapshots or stable handles.

Reactive owners used by engine entities must have explicit owner keys. Until
the owner ABI supports entity, scene, and application identities, a `react`
callable must not pretend to provide per-entity state: process-wide storage by
callable identity would alias every entity's `remember` values.

### 5.2 Transactional attempts

The proposed transaction surface extends `std.reactive` without adding a
keyword:

```azora
spec AttemptSource

func attempt<T, E, S>(
    source: S!,
    body: (Attempt<S>!) -> T ?! E
): T ?! E
where S: AttemptSource
```

`std::attempt(source) { attempt -> ... }` opens a journal through the source's
implementation. A successful body commits. A typed failure rolls back before
it propagates. `defer` always runs. The transaction view is the only mutable
view passed to the body, preventing accidental writes around the journal.

```azora
fin updated = std::attempt(store) { attempt ->
    fin count = attempt.write(counter)
    count.set(count.value + 1)
    return count.value
}
```

Reactive writes invalidate derived values and notify observers only after the
outermost successful commit. Rollback restores values and versions without
publishing. Nested attempts use savepoints when the source supports them and
otherwise fail before running the inner body.

`batch` and `attempt` are deliberately different:

| operation | rollback | publication |
|---|---:|---|
| `std::batch(source) { ... }` | no | once after the body |
| `std::attempt(source) { ... }` | on typed failure | once after commit only |

Language `try` remains typed-error propagation. It neither creates a journal
nor changes reactive publication. This avoids making error handling depend on
an ambient transaction.

Worlds, reactive stores, editor documents, and future database sessions may
implement `AttemptSource`. The compiler understands typed failure and callable
effects, not those concrete source types. Synchronous attempts reject
`Suspend`, external `Io`, and any effect for which the source has no rollback
adapter. A future asynchronous database transaction should be a distinct
overload with an explicit capability, not a silent relaxation of these rules.

Structured concurrency remains in `std.concurrency`:

```azora
fin winner = await std::race<Value> {
    std::branch { return await localValue() }
    std::branch { return await remoteValue() }
}
```

Reactive owners dispose subscriptions when cancelled, but `std::race` is not a
reactive primitive. `std::branch` is the required namespaced branch constructor;
it is not a keyword. No `scope` or `std::block` wrapper is needed because each
branch lambda already supplies the sequential task body.

## 6. Lowering and memory

### Interpreter

Reactive declarations become owner-backed cells. A cell stores its value,
version, and subscribed effects. Lazy bindings remember the dependency versions
seen during evaluation. Owner exit removes effect subscriptions, so cells do not
retain finished invocation scopes.

No source body is copied after assignments. Effect execution is a runtime node
in Azora IR.

### LLVM

Reactive owner cells lower to internal globals with explicit initialization
flags. Lazy locals lower to stack slots with one-byte initialization state.
There is no tracing garbage collector and no heap allocation for these language
bindings. Reactive async bodies retain the source owner identity when LLVM
creates their scheduler entry wrappers.

### WebAssembly

Reactive owner cells lower to module globals with explicit initialization flags.
Lazy locals use function locals and first-read guards. Like LLVM, the core
language bindings do not require a garbage collector.

## 7. Current boundaries

These are explicit implementation limits, not implied guarantees:

1. Interpreter effects track actual reactive reads and can react through runtime
   aliases. LLVM and Wasm currently trigger from dependencies and assignments
   visible in Azora IR. Mutation hidden behind an arbitrary call or pointer does
   not yet notify a native effect.
2. Native owner storage is process-wide per callable and binding. Per-receiver
   and keyed call-site owners require an explicit owner identity ABI.
3. Native reactive globals are not yet atomic. Sharing one reactive owner across
   parallel tasks requires synchronization supplied by the owner or a future
   atomic cell lowering.
4. `retain` has a distinct interpreter store but no engine lifecycle adapter is
   installed automatically.
5. `preserve` exposes snapshots and `PreserveStore`; persistence is not silently
   performed by the compiler.
6. The supported execution targets are the interpreter, LLVM, and WebAssembly.
   All three must consume the same resolved reactive Azora IR semantics; a
   backend-specific source model is not part of this design.

## 8. Rules

1. `react` qualifies `func`, `async func`, `prop`, and `async prop`.
2. Reactive callables may only be invoked from a reactive scope.
3. `remember`, `retain`, and `preserve` require `var`, `let`, `val`, or `fin`.
4. Reactive lifetimes cannot be combined with `threadlocal`.
5. Effects run once when declared and synchronously after their dependency is
   written.
6. Automatic effects track body reads; explicit effects track only their list.
7. `effect defer` runs at owner exit.
8. `lazy` accepts `fin` and `let`, evaluates on first read, and is cached.
9. Inside a reactive owner, a lazy binding is invalidated by reactive inputs.
10. `std::attempt` publishes reactive changes only after commit and publishes
    nothing after rollback.
11. ECS and other domain adapters publish owned values at explicit deterministic
    boundaries; reactive code never retains domain-internal borrows.

## 9. Related

- `BASE_SYNTAX_DIP.md` for ordinary binding mutability
- `CONCURRENCY_DIP.md` for async execution and structured task lifetimes
- `DEPENDENCY_INJECTION_DIP.md` for `lazy fin service = inject Service`
- `LAMBDA_CONTEXT_CAPTURE_DIP.md` for stored producers and observers
