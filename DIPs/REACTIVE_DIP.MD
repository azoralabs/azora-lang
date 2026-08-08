# Azora Reactivity - DIP Implementation Profile

> Status: **implemented, and entirely compile-time.** There is no reactive
> runtime: an effect's body is *inlined* after each assignment the compiler can
> see. §5 is what that costs, and it is the thing to read first.

A reactive function holds state that outlives a call, and effects that re-run
when that state changes.

```azora
react func counter(): Int {
    remember var value = 1
    var observed = 0

    effect { observed = value }                // runs here, and again on every change
    effect defer { std::println("finish") }

    value = 7
    return observed                            // 7
}
```

---

## 1. `react` - and it is contagious

Reactive state and effects are only legal inside a function marked `react`:

```
'remember' requires a 'react func' or 'react async func'
```

And a reactive function may only be called from another one:

```
reactive function 'f' can only be called from a 'react func' or 'react async func'
```

So reactivity colours the call graph the way `async` does. A program that uses
it marks `main` too, and the boundary between reactive and ordinary code is
visible in the source rather than implied.

`react` is a keyword rather than a decorator, and it qualifies a declaration the
way `async` does:

```azora
react func       counter(): Int { … }
react async func poll(): Int    { … }
```

Writing it as a keyword rather than a decorator is deliberate: it changes what
the body is allowed to contain, which is the language's business rather than a
library's, and nothing needs to be imported for it to mean something.

### 1.1 `react prop` and `react async prop`

A property is a declaration whose body runs on every read, which makes it the
other natural place to own reactive state. All four forms exist:

```azora
react func       counter(): Int { … }
react async func poll(): Int    { … }
react prop       total: Int     { … }
react async prop latest: Int    { … }
```

`react prop` is written in an `impl` block, where a property has a block body to
put state in:

```azora
pack Counter { var base: Int = 0 }

impl Counter {
    react prop total[self: Self&]: Int {
        remember var value = 1
        var observed = 0
        effect { observed = value }
        value = 7
        return observed              // 7
    }
}
```

The rules are the function's rules, unchanged: `remember` / `retain` /
`preserve` and `effect` are legal because the owner is `react`, and the same
property without it is rejected with the same message.

`async` and `react` are both qualifiers rather than replacements for `prop`, so
they compose, and they are written in that order - `react async prop`. `react`
qualifies exactly these four declarations, and anything else is refused where it
is written:

```
react ctor() { }
// Expected 'prop', 'func', 'async prop', or 'async func' after 'react'
```

- ⬜ **A reactive property's state is per-call, not per-receiver.** The lowering
  is the function lowering (§4), so `remember` in a property lives for one
  evaluation of the body. A reader who expects `total` to remember across two
  reads of the same `Counter` gets a fresh value each time, and nothing says so
  at the declaration

---

## 2. A lifetime plus a binding

A reactive declaration is an **ordinary binding with a lifetime in front of it**:

```azora
preserve var settings = Settings()
preserve val session  = Session()
preserve let history  = MutableHistory()
preserve fin identity = UserIdentity()
```

Both halves are written because both are needed, and they answer different
questions. The lifetime says how long the value outlives its owner; `var` /
`val` / `let` / `fin` say what they say anywhere else - whether the name rebinds
and whether the value can be written. A reactive binding is an ordinary binding
that survives longer, and the syntax says so.

Omitting the binding form is an error naming all four.

### 2.1 The ladder

Three lifetimes, weakest first. Each survives everything the one below it does:

| lifetime   | survives                                          |
|------------|---------------------------------------------------|
| `remember` | reactive reruns                                   |
| `retain`   | owner recreation in the same process              |
| `preserve` | serialization and restore across process restarts |

`std::ReactiveLifetime` names them `Remembered`, `Retained` and `Preserved`, and
`ReactiveSnapshot<T>` is the intended carrier - a key, a value and a lifetime.

They differ **only in declared intent today.** All three behave identically:
there is no snapshot store, nothing is written anywhere, and nothing survives
anything.

- ⬜ **`retain` and `preserve` do not persist.** The lifetimes are recorded and
  unimplemented, so a program relying on `preserve` surviving a restart is
  relying on a word. Durable storage adapters are the missing half

### 2.2 Not with `threadlocal`

```
'threadlocal' cannot be combined with 'preserve' - a reactive lifetime and
thread-local storage are different answers to where a value lives
```

Both say where a value lives, in ways that do not compose: per-thread storage
and a lifetime that outlives the owner are different axes, and a binding
claiming each says nothing coherent about either. Rejected in both orders.

---

## 3. Effects

Three forms, all implemented.

### 3.1 Automatic dependencies

```azora
effect { observed = value }
```

Every reactive name the body reads becomes a dependency. The body runs once
where it is written, and again after each assignment to one of them.

### 3.2 Explicit dependencies

```azora
effect x { observed = x + y }          // one
effect [x, y] { observed = x * 10 + y } // several
```

The listed names are the **triggers**; the body still reads whatever it likes.
That distinction is the point of the form:

```azora
remember var x = 1
retain var   y = 10
effect x { observed = x + y }
y = 20      // no re-run - y is read, not a trigger
x = 2       // re-runs, and now reads y == 20
return observed          // 22
```

The effect saw `y`'s new value without `y` being able to cause the run. That is
what an explicit dependency list is for, and it is the reason it is not just an
optimisation of §3.1.

### 3.3 `effect defer`

```azora
effect defer { … }
```

Cleanup: no dependencies, and it runs when the reactive owner exits rather than
on any change.

---

## 4. How it works

There is no observer, no subscription list, no dirty bit and no scheduler.

`IrGenerator` keeps the active effects as `(dependencies, body)` pairs. When it
lowers an assignment to a reactive name, it looks for effects that name it and
**emits their bodies immediately after the assignment**:

```azora
value = 7
```

lowers to

```
{ value = 7; …effect body… }
```

That is the whole mechanism. It has two consequences worth stating plainly,
because neither is guessable from the syntax.

---

## 5. What "compile-time" costs

### 5.1 Only visible assignments re-run an effect

The re-run is attached to an assignment statement the lowering can see, in the
same function. Anything else does not trigger:

```azora
react func f(): Int {
    remember var value = 1
    var observed = 0
    effect { observed = value }
    bump()                  // whatever this does, the effect does not re-run
    return observed         // 1
}
```

A write through a pointer, from a called function, from another task, or through
an aliasing binding is invisible to this. So reactivity works for state a
function owns and mutates itself, and silently does not for anything else.

- ⬜ Decide whether that is the intended model. If it is, say so - "an effect
  re-runs on assignments in the owner's body" is a defensible rule. If it is
  not, the mechanism needs a runtime and this is a rewrite rather than a fix

### 5.2 An effect's body is duplicated per assignment

Each triggering assignment gets its own copy of the body. Ten assignments to one
dependency means ten copies in the emitted code.

- ⬜ No bound on the expansion, and no diagnostic when it is large

### 5.3 There is no batching, ordering or cycle rule

- ⬜ An effect that assigns a dependency of another effect re-runs it, and a
  cycle between two effects is not detected
- ⬜ Assigning two dependencies runs the effect twice; there is no batching and
  no "run once at the end of the statement" rule
- ⬜ The order in which several effects on one name run is declaration order by
  accident, not by decision

---

## 6. What is missing

- ⬜ **Durable storage for `retain` and `preserve`** (§2) - the half that would make the
  three lifetimes different from each other
- ⬜ **A derived-value form.** There is state and there are effects, but nothing
  says "this value is a function of those" - the common case is written as an
  effect assigning a `var`, which is the imperative spelling of it
- ⬜ **No way to observe from outside.** A reactive function's state cannot be
  watched by anything but its own effects
- ⬜ **Interaction with tasks is unspecified.** `react` applies to `async func`,
  and what an effect means when the owner suspends is not stated
- ⬜ **Backend parity is unverified.** The mechanism is in `IrGenerator`, so it
  should reach every backend - but nothing tests reactivity under LLVM or Wasm
- ⬜ **`ReactiveSnapshot<T>` is declared and unused**

---

## 7. Rules

1. `remember`, `retain`, `preserve` and `effect` require a `react` owner.
2. A reactive function may only be called from another reactive function.
3. A reactive declaration is a lifetime followed by `var`, `val`, `let` or
   `fin`. Both are required.
4. The lifetimes form a ladder - `remember` < `retain` < `preserve` - and differ
   in declared intent only; none persists today.
5. A reactive lifetime cannot be combined with `threadlocal`.
6. An effect body runs once where it is written.
7. `effect { }` depends on every reactive name its body reads. `effect x { }`
   and `effect [x, y] { }` depend on exactly the names listed, and may read
   others without depending on them.
8. `effect defer { }` has no dependencies and runs when the owner exits.
9. An effect re-runs after an assignment to one of its dependencies **that the
   compiler can see in the owner's body**, and at no other time.

---

## 8. Related

- `CONCURRENCY_DIP.MD` - `react async func`, and the unresolved interaction
- `FUNCTIONS_DIP.MD` - the declaration qualifiers `react` sits beside
- `ROADMAPs/VERSION_0_1_ROADMAP.MD` §3.4
