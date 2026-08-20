# Azora Dependency Injection - DIP

> Status: **partly implemented.** `solo`, `graph`, and `inject` work under the
> interpreter. General local `lazy fin` and `lazy let` bindings are implemented
> and can defer injection. Graph bindings, graph lifetime semantics, and
> exactly-once DI resolution remain proposed. Every section marks which it is.

A **graph** holds **definitions**; a definition may **bind** to the specs it
satisfies; a consumer **injects** by naming what it needs rather than how to
build it. There is no container object to thread through the program, no
registration call, and no reflection.

```azora
graph AppGraph {
    solo    Config("https://api.example.com")
    solo    HttpClient(inject Config) bind Api
    factory LoginViewModel(inject Api)
    scoped  ProfileViewModel(inject Api, inject Session)
}

func main() with AppGraph {
    fin api      = inject Api
    lazy fin cache = inject Cache
    fin allViews = inject Many<ViewModel>
}
```

---

## 1. What exists today

Implemented, interpreter only:

| form                             | meaning                                       |
|----------------------------------|-----------------------------------------------|
| `solo pack Name { fields }`      | a type with one instance, from field defaults |
| `graph Name { solo Type(args) }` | registers a `pack`, with construction args    |
| `inject Type`                    | resolves it, building on first use            |

Both declaration forms generate a `__singleton_Type` factory; `inject` lowers to
`__inject("Type")`. The interpreter keeps a `typeName → instance` map, calls the
factory on a miss, and stores the result.

```azora
solo pack Counter {
    var n: Int = 0
}

impl Counter {
    func bump[self: Self!]() { self.n += 1 }
}

fin a = inject Counter
a.bump()
fin b = inject Counter     // the same instance
println(b.n)          // 1
```

Everything below is what this has to become.

---

## 2. Graphs - `graph`

⬜ **Proposed.** `graph` parses and registers today, but its name is discarded
(§8).

A `graph` is a **graph**: a named group of definitions.

"Graph" rather than "module" because `module` already names a source file in
Azora, and because what a `graph` holds *is* a dependency graph - definitions
with edges to the definitions they inject.

```azora
graph NetworkGraph {
    solo Config("https://api.example.com")
    solo HttpClient(inject Config) bind Api
}
```

### 2.1 `include` - composition

`include` is contextual rather than reserved, for the same reason `replace` is:
it is an ordinary word that programs already use as a name - `std/serializer.az`
has a local called `include` - and reserving it would take that name from every
program that wants it.

✅ Parsed. A graph may contain another's definitions:

```azora
graph AppGraph include [NetworkGraph, DataGraph, UiGraph] {
    scoped ProfileViewModel(inject Api)
}
```

### 2.2 `replace` - explicit substitution

✅ Parsed. `replace` is contextual, not reserved (§8).

```azora
graph TestGraph replace AppGraph {
    solod FakeApi() bind Api
}
```

A replacement graph starts from another and overrides the definitions it names.
This is what makes a test double possible, and it is explicit: a definition is
only overridden where a graph says it is, not by declaration order or by which
file happened to load last.

### 2.3 Selecting a graph statically

⬜ **Proposed.** The entry point declares which graph it runs under:

```azora
func main() with AppGraph {
    …
}
```

Static rather than an `install AppGraph` statement executed inside `main`. A
declared graph is known to the compiler, so an unsatisfiable injection is a
compile error; an installed one is a runtime fact, and every check it could
support becomes a runtime check.

- ⬜ Two graphs defining the same type, without an explicit `replace`, is an
  error naming both

---

## 3. Definitions

### 3.1 `solo pack` - a type that is inherently singular

✅ Implemented. `solo` is a modifier on `pack`, not a declaration of its own:

```azora
solo pack Clock {
    fin startedAt: Long = 0L
}

impl Clock {
    func elapsed[self: Self&](): Long { … }
}
```

There is one `Clock` because that is what a clock is, not because a graph
decided so.

`solo pack` is a **pack**, and everything that follows from that holds: it
declares fields, its members live in an `impl` block, and its receiver is
`self`. A singleton is not different enough to earn its own declaration form,
its own member syntax, or its own receiver word.

### 3.2 Provider lifetimes - `solo`, `factory`, `scoped`

✅ Parsed. The lifetime is a `graph` entry's **first word**, and it is the whole
difference between the forms:

```azora
graph AppGraph {
    solo    Repository(inject Db)        // this graph owns one
    factory LoginViewModel(inject Api)   // a new owned value per resolution
    scoped   ProfileViewModel(inject Api) // one per active scoped
}
```

| lifetime  | one per      | owned by   |
|-----------|--------------|------------|
| `solo`    | graph        | the graph  |
| `factory` | resolution   | the caller |
| `scoped`  | active scope | the scope  |

Every entry used to be a singleton, which cannot model a view model, a request
object, a session, or any transient service. The three lifetimes are what let a
graph describe an application rather than a bag of globals.

Note the two `solo`s are different claims. `solo pack Clock` says the **type**
is singular. `solo Repository(…)` says **this graph** owns one - another graph
may own another, and the type itself is an ordinary `pack`.

```azora
solo pack Clock { fin startedAt: Long = 0L }

graph AppGraph {
    solo Config("https://api", 30)         // built with arguments
}
```

### 3.3 Dependencies between definitions

⬜ **Proposed.** A definition's arguments may themselves be injections:

```azora
graph AppGraph {
    solo Config("https://api", 30)
    solo HttpClient(inject Config)
}
```

Resolving `HttpClient` resolves `Config` first.

### 3.4 `bind` - satisfying a spec

⬜ **Proposed.** This is the missing feature: today `inject` names a concrete
type, so nothing can be substituted.

```azora
spec Api {
    func fetch[self: Self&](path: String): String
}

graph AppGraph {
    solo HttpClient(inject Config) bind Api
}

fin api: Api = inject Api        // resolves to the HttpClient
```

`bind` says the definition also answers injections of that spec. Several:

```azora
HttpClient(inject Config) bind [Api, Closeable]
```

`bind` is already a keyword and already means this. `annot Serializable bind
[Serializer, AzonSerializer]` says a decorator implies those conformances; a
definition binding to a spec says the same thing about a value. Reusing it costs
no new vocabulary.

- ⬜ Binding a type to a spec it does not implement is a compile error
- ⬜ Two definitions binding the same spec in one installed graph set is a
  compile error naming both

---

## 4. Resolution

### 4.1 `inject Type` - an expression

✅ Implemented. Resolves immediately, at the point written.

```azora
fin config = inject Config
println(inject Config.name)     // chains into member access
```

### 4.2 A field initialiser

`inject` is an expression, so a field takes its dependency by initialising with
one. There is no delegation form and no type annotation to repeat - `inject Api`
already says what the field holds:

```azora
pack Service {
    fin api = inject Api
}
```

This is where `bind` pays off: the field injects the **spec**, and which
implementation arrives is the module's decision.

### 4.3 `lazy` - resolved on first read

✅ **Implemented for local bindings.** `lazy` is the language's ordinary
first-read binding modifier; DI does not receive a special lazy expression.

```azora
pack Service {
    fin api   = inject Api            // resolved when the pack is built
    // Pack-field lazy storage is still proposed. A local uses:
    // lazy fin cache = inject Cache
}
```

`lazy` modifies the binding, never the injection. Plain `inject` resolves where
its expression is evaluated; a lazy binding delays evaluating its entire
initializer until the first read, so a dependency on a path never taken is
never built.

The distinction matters for a field of a long-lived `pack`: without `lazy`,
constructing the pack constructs every dependency it declares, used or not.

`lazy` on a **definition** is deliberately not proposed: a `solo` is already
built on first injection, so the word would say nothing there.

---

### 4.4 `Many<T>` - every matching binding

⬜ **Proposed.** `Many<T>` is an ordinary type in `std.di`, **not** a keyword.

Several definitions may bind the same spec. That is not an error - it is how you
register a family:

```azora
graph UiGraph {
    scoped LoginViewModel(inject Api)      bind ViewModel
    scoped ProfileViewModel(inject Api)    bind ViewModel
    scoped SettingsViewModel(inject Config) bind ViewModel
}
```

Three ways to ask, and they mean different things:

| written                  | resolves to                                        |
|--------------------------|----------------------------------------------------|
| `inject ViewModel`       | exactly one - **ambiguity is an error**            |
| `inject Many<ViewModel>` | every matching binding, in graph declaration order |
| `inject LoginViewModel`  | that concrete definition                           |

```azora
import std::di

fin viewModels: Many<ViewModel> = inject Many<ViewModel>

for viewModel in viewModels {
    viewModel.start()
}
```

`Many<T>` implements the collection and iteration specs, preserves declaration
order, and holds every binding assignable to `T`. Being a library type rather
than a language form, it can grow `.size`, filtering, qualifier selection and
conversion to `Array<T>` or `List<T>` without touching the grammar.

`lazy fin values = inject Many<T>` defers constructing the collection to first
access.

- ⬜ A **qualifier** selects one binding from several. Decide whether Azora
  wants qualifiers or whether graph selection and `Many<T>` cover it

### 4.5 Compile-time discovery

⬜ **Proposed.** Runtime resolution and compile-time discovery are separate
questions, and separate forms:

```azora
inline fin viewModelTypes = reflect<UiGraph>.implementations<ViewModel>

inline for VM in reflect<AppGraph>.implementations<ViewModel> {
    inline trace "registering ${reflect<VM>.declName}"
}
```

`inject Many<T>` gives **instances from the active scope**;
`reflect<Graph>.implementations<T>` gives **types registered in a graph**, with
no instance built and nothing resolved.

Nothing about view models is hardcoded into the compiler. `ViewModel` is a spec
declared by whichever library owns navigation:

```azora
spec ViewModel {
    func onCleared[self: Self!]()
}
```

The DI system knows about lifetimes and bindings; it does not know what a view
model is, and should not.

---

## 5. Ownership and destruction

⬜ **Proposed.**

A graph **owns** what it constructs with `solo`, and a scope owns what it
constructs with `scoped`. When a graph or scope ends, it destroys what it owns in
**reverse dependency order** - a definition is destroyed before anything it was
built from.

`factory` values are owned by whoever asked for them, and follow the ordinary
ownership rules from there.

That distinction decides the native representation:

| lifetime          | LLVM returns                                          |
|-------------------|-------------------------------------------------------|
| `solo` / `scoped` | `shared` ref `T` - the graph or scope keeps ownership |
| `factory`         | an owned `T` - the caller has it                      |

---

## 6. Thread and coroutine safety

⬜ **Proposed.** Today's implementation is thread-safe but not exactly-once,
and it is not coroutine-aware.

### 6.1 What happens today

The singleton map is guarded, and the factory runs **outside** the lock -
deliberately, because holding a lock across a user-supplied constructor lets one
singleton's construction deadlock on another's. `putIfAbsent` then decides which
instance wins.

The consequence: **two tasks racing the first injection both build an
instance**, and the loser's is discarded. That is wasted work if construction is
pure, and a defect if it is not - and nothing requires it to be pure.

### 6.2 The requirement

A singleton must have **one concurrent construction attempt and one successful
instance**, no matter how many threads or coroutines race for it, and a
suspending constructor must not block a thread while it runs.

Stated that way on purpose. A failed attempt leaves the cell empty so a later
resolution can try again, so "constructed exactly once" would be false - what is
guaranteed is that no two attempts run at the same time and that no two
successful instances are ever handed out.

Those two together rule out both easy answers. A plain lock held across
construction blocks a thread and deadlocks on a suspending factory. No lock at
all gives the duplicate construction above.

### 6.3 The design

Each definition gets a one-shot cell holding one of three states: **empty**,
**in progress** (carrying a handle the waiters await), or **ready**.

Resolution:

1. Read the cell. If **ready**, return the instance - the common path, with no
   locking after the first resolution.
2. Otherwise, atomically move **empty** to **in progress**, publishing a handle
   before running the factory.
   - The thread that made that transition runs the factory and completes the
     handle.
   - Every other thread or coroutine sees **in progress** and awaits the handle.
3. On completion the cell becomes **ready**.

The handle is awaited, not blocked on, so a coroutine waiting for a singleton
built by another coroutine yields its thread. A suspending factory therefore
works, and the pool is never starved by construction.

- ⬜ Publishing the handle before running the factory is what makes cycle
  detection free: if resolving `X` reaches `X` again on the same resolution
  path, the cell is **in progress** on that path and the resolver reports a
  cycle naming the chain, instead of awaiting a handle it is itself responsible
  for completing
- ⬜ A factory that throws leaves the cell **empty** again, not permanently
  **in progress**, so a retry is possible and a waiter sees the failure rather
  than hanging
- ⬜ The mutable state a `solo` holds is not protected by any of this. Two
  tasks sharing a singleton share its fields. Whether that requires `Sync` (§3.3
  of the roadmap) or is the author's problem is undecided

---

## 7. Rules

1. A `graph` is a **graph**: a named group of definitions. "Graph" rather than
   "module" because `module` already names a source file, and because what a
   `graph` holds *is* a dependency graph - definitions with edges to the
   definitions they inject.
2. `solo pack Name { }` declares a type that is inherently singular. It is a
   pack: it holds fields, its members live in an `impl` block, and its receiver
   is `self`.
3. Every `graph` entry states its lifetime first: `solo` (one per graph),
   `factory` (one per resolution), `scope` (one per active scope).
4. A definition's arguments may inject other definitions.
5. `bind Spec` makes the definition answer injections of that spec.
6. `inject Type` resolves where it is evaluated; `lazy fin value = inject Type`
   defers the whole initializer to the first read; `inject Many<T>` resolves
   every matching binding in graph declaration order.
7. `inject T` with several matching bindings is an error; `inject Many<T>` is
   how you ask for all of them.
8. A graph owns what it constructs, and destroys it in reverse dependency
   order.

---

## 8. Keywords

| keyword   | status                   | used for                            |
|-----------|--------------------------|-------------------------------------|
| `graph`   | ✅ reserved, implemented  | the declaration itself              |
| `solo`    | ✅ reserved, implemented  | `solo pack`, and the graph lifetime |
| `inject`  | ✅ reserved, implemented  | resolution                          |
| `bind`    | ✅ reserved, parsed       | binding a definition to a spec      |
| `factory` | ✅ reserved, parsed       | the per-resolution lifetime         |
| `scoped`  | ✅ reserved, parsed       | the per-scope lifetime              |
| `include` | ✅ **contextual**, parsed | graph composition                   |
| `lazy`    | ✅ reserved, implemented  | first-read `fin` and `let` bindings  |
| `replace` | ✅ **contextual**, parsed | graph substitution                  |
| `with`    | ✅ reserved               | `func main() with AppGraph`         |

`Many<T>` is deliberately **not** a keyword: it is a type in `std.di`, so it can
grow filtering, qualifiers and conversions without touching the grammar.

`bind` and `scoped` are reused in new positions with meanings consistent with
the ones they already carry, which is the reason to prefer them over inventing
words.

`replace` is **contextual**, not reserved: `replace` is the string
function, and a keyword would take that name from every program. It means graph
substitution only in `graph TestGraph replace AppGraph {`, where a name follows
it inside a graph header - nowhere else.

`by` is not used here. Injection is an expression, so a field initialises with
one rather than delegating to one - there is nothing for `by` to introduce.

---

## 9. What has to be built

The syntax is in: lifetimes, `bind`, `include` and `lazy` parse. Almost none of
it means anything yet - the parser records the shape and the rest of the
pipeline still behaves as if every entry were a singleton.

Ordered by how much they block.

- ⬜ **`inject` does not work outside the interpreter.** `__inject` has no
  lowering in `LlvmCodegen` or `WasmCodegen` - zero occurrences in either. A
  program using DI compiles for those targets and then does not resolve, with
  no warning
- ⬜ **Lifetimes are parsed and ignored** (§3.2). `factory` and `scope` behave
  as `solo`, so a view model is a singleton and a request object outlives its
  request. This is the change that makes the rest worth having
- ⬜ **`bind` is parsed and ignored** (§3.4). Without it `inject` names a
  concrete type and no test double can replace a real service
- ⬜ **`include` is parsed and ignored** (§2.1), and `replace` (§2.2) has no
  keyword. A `graph`'s name is still recorded and never read, so two graphs
  defining one type collide silently
- ⬜ **`func main() with AppGraph`** (§2.3) - static selection, so an
  unsatisfiable injection is a compile error rather than a runtime surprise
- ⬜ **Resolution is keyed by a string.** `__inject("TypeName")` looks the
  instance up in a `typeName → instance` map, which is why a scope-scoped
  `Config` collides with another scope's. Replace it with compiler-generated
  `DefinitionId` symbols: one per definition, resolved at compile time, so a
  missing definition and an ambiguous one are both diagnosed there
- ⬜ **One concurrent attempt, one successful instance** (§6.3) - today's race
  duplicates construction
- ⬜ **Ownership and reverse-order destruction** (§5), and the `shared`/owned
  distinction the native backend needs
- ⬜ **`Many<T>` in `std.di`** (§4.4), and making ambiguity an error for
  `inject T` rather than a collision
- ⬜ **`reflect<Graph>.implementations<T>`** (§4.5) - compile-time discovery,
  separate from runtime resolution
- ⬜ **Pack fields do not yet accept `lazy`.** Local `lazy fin` and `lazy let`
  use the general one-shot lazy cell and can defer an `inject` expression.
- ⬜ **Cycles are undetected** - mutually injecting definitions recurse until
  the stack ends. Publishing the in-progress handle before running the factory
  makes detecting them free
- ⬜ **Named qualifiers** - a way to tell two definitions of one type apart.
  Decide whether `Many<T>` and graph selection cover it
- ⬜ **Graph validation.** Every injection in a graph is satisfiable, no cycles,
  no ambiguity - checkable at compile time once definitions carry identity

---

## 10. Related

- `COMPILER_INTRINSIC_DIP.MD` §3.3 - `__inject`, and the registry gap that lets
  it be missing from two backends unnoticed
- `CONCURRENCY_DIP.MD` §7 - the task model §6.3's design has to work with
- `ANNOTATIONS_DIP.MD` - `bind`'s existing meaning on a decorator
- `ROADMAPs/VERSION_0_1_ROADMAP.MD` §3.5, §6.1
