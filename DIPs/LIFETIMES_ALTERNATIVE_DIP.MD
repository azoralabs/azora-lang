# Memory safety without lifetime annotations

## The problem lifetimes solve

Rust needs `'a` for one reason: a reference may be **stored** — put in a struct, returned
from a function, captured by a closure that outlives the call. Once a reference can escape
the expression that produced it, the compiler must prove the referent outlives it, and that
proof needs a name for "how long". Every lifetime annotation in Rust exists to carry that
name across a signature.

Take away the escape and the proof becomes trivial. That is the whole proposal.

## The rule

> **A borrow is an argument, never a field.**

Concretely, `&` and `!` may appear in exactly three places:

1. a parameter type — `func serialField(value: SerialValue&, name: String)`
2. a receiver — `prop width[self: Model&]: Double`, `func toJson[self&](value: T&)`
3. a local binding whose initialiser is one of the above

4. a return type, **when exactly one input borrow could have produced it** — see *Returning
   a borrow*, below

and in neither of these:

- a `pack` field — `var _backing: Allocator!` is **rejected**
- a capture in a lambda that outlives the call

A borrow therefore never outlives the call that received it, or — in the return case — the
borrow it was derived from. There is nothing to annotate and nothing to propagate across a
signature. The check is local to one function and needs no whole-program analysis.

## Returning a borrow

Returning a borrow is genuinely useful and the language already relies on it. `azora-app`
has three of them:

```azora
func setClearColor(r: Double, g: Double, b: Double): Engine! { self! ->
    self.clearR = r
    return self
}
```

Fluent chaining, an accessor handing back a mutable element, a view over one container —
these all want the reference to escape the call, and forbidding them outright would be a
worse language, not a safer one.

They are safe without annotation for one reason: **there is only one thing the returned
borrow could have come from.** When a function has exactly one borrow among its inputs
(receiver included), "how long does the result live" has a single possible answer — as long
as that input — so there is nothing to name. This is Rust's *lifetime elision*, which is
why most Rust functions carry no `'a` either. The proposal is to keep the elision and drop
the general case that made annotations necessary.

The rule:

- **exactly one input borrow** — a returned borrow is derived from it. Allowed, no syntax.
- **no input borrow** — there is nothing to derive from, so a returned borrow would point at
  a local. Rejected.
- **two or more input borrows** — ambiguous; the compiler will not guess. Rejected, with the
  suggestion to return a value or an index instead.

One local check backs it: the returned expression must actually be reachable from that input
borrow, not from a local or a temporary. `return self` passes; `return localNode&` does not.

The third case is the only expressiveness this proposal really gives up, and it is the rare
one — `fn longest(a: &str, b: &str) -> &str` is the textbook example precisely because it
takes effort to think of.

### This is already how Azora is written

Not one `pack` field in `std` or in the engine packages has a borrow type today. The rule
does not restrict existing code; it writes down what the code already does, and turns a
convention into a guarantee.

Return position is different: the engine does return borrows, and the elision rule below is
what keeps that working. The field restriction is the load-bearing one.

The one place the convention is currently violated is the allocator sketch in
CUSTOM_ALLOCATORS_DIP — `var _backing: Allocator!` and `var _allocator: Allocator!`. Under
this proposal those become owned values or type parameters, which is the better design
anyway.

## Ownership

Storage is by value, and a value has exactly one owner.

```azora
fin a = User("Andrew", 34)
fin b = mov a          // ownership transfers
std::println(a.name)   // error: 'a' was moved on line 2
```

`mov` transfers; the source binding is dead afterwards and naming it is an error. This is
the same dataflow the compiler already runs for `purge` — a moved-from binding and a purged
binding are both "no longer yours", and one analysis answers both.

Destruction is deterministic: a value's `dtor` runs when its owner goes out of scope, and
`purge` runs it early. No collector, no pauses.

## Escaping is possible, and it is spelled

Some code genuinely needs to store a pointer — an arena holding its backing store, an
intrusive list, a device mapping. Those use a **raw pointer** (`T*` / `T^`), which:

- may be stored in a field and returned,
- may only be created inside `unsafe`,
- carries no guarantee.

The safe subset never produces one. So the boundary is visible in the source: if a type
holds a `T^`, its invariants are hand-maintained, and that is exactly where a reader should
be looking.

## Data races

A data race needs two things: aliasing and mutation. Remove either across a thread boundary
and races are impossible without annotating anything.

> **A value crossing a thread boundary is either moved, or deeply immutable.**

- **Moved** — `mov` transfers exclusive ownership. The sender cannot touch it afterwards, so
  there is no second accessor and no race.
- **Deeply immutable** — a value with no `var` field, transitively, may be shared freely.
  `SyncShared<T>` is safe when `T` is immutable, and that is checkable structurally.

`Shared<T>` (non-atomic count) is *not* sendable and the compiler rejects it at a thread
boundary — which is the check that makes its cheap refcount sound rather than a footgun.

This is Pony's and Swift's `Sendable` answer rather than Rust's, and it needs no borrow
checker: the property is a fact about a type, computed once from its fields.

## Leaks

Deterministic destruction means the ordinary cases cannot leak:

- a value owned by a scope is destroyed at scope exit,
- `Unique<T>` destroys its pointee in its `dtor`,
- `scope alloc { … }` frees its whole region at exit, regardless of what was allocated in it.

**One hole, stated plainly: cycles among `Shared`/`SyncShared` leak.** Reference counting
cannot reclaim a cycle without a collector, and no annotation scheme fixes that — Rust has
the identical hole with `Rc`. Two mitigations, neither of which pretends to be a proof:

1. `Weak<T>` for back-edges, which is what breaks cycles by construction.
2. A debug allocator that tracks live allocations and reports what survived `main`, so a
   cycle shows up in a test run rather than in production.

## What this costs

The rule forbids one useful shape: **a type that holds a borrow**. There is no
`Iter<'a>`-style borrowing iterator, no view struct, no `&str`-like slice of a value the
struct does not own.

The workaround is to own or to index, and Azora already does this. `Query` in the engine
holds a `QueryCursor` — an index into storage — not a borrow of it. Iteration is
`hasNext()`/`next()` over a cursor, and the storage is passed in per call. That pattern
covers essentially every case a borrowing iterator would, at the cost of passing the
container along with the cursor.

Where a borrowing view really is wanted, `Slice<T>` over a raw pointer is available inside
`unsafe`, with the boundary visible in the source.

## What it buys

- No lifetime syntax anywhere in the language.
- No inference to explain when it fails, and no signature churn when it changes.
- A safety check that is **local to a single function** and cheap to run.
- Deterministic destruction, so no collector and no pauses.
- Data-race freedom from a structural property of types, not from an aliasing analysis.

## Implementation

Roughly in dependency order:

1. **Reject borrows in field position**, and apply the elision rule to returns. Parser and
   type resolver; the diagnostic should name the position (`a pack field cannot hold a
   borrow — store the value, or take it as a parameter`). The field rule costs nothing
   today; the return rule accepts the engine's existing `Engine!` chaining and rejects only
   the ambiguous multi-borrow case, of which there are none.

2. **`mov` and use-after-move.** Extend `AllocDropAnalyzer`, which already does liveness, to
   mark a binding dead after `mov` or `purge` and report any later use.

3. **Immutability and sendability as computed properties.** `T` is deeply immutable when it
   has no `var` field and every field type is deeply immutable; `T` is sendable when it is
   deeply immutable or is being moved. Compute once per type from the symbol table.

4. **Thread-boundary check.** At `async func` spawn points and channel sends, require the
   argument to be sendable or moved.

5. **Leak reporting in the debug allocator.** Track live allocations, report survivors.

Steps 1 and 2 are the safety core and are worth doing on their own; 3 and 4 depend on the
concurrency surface settling; 5 is a diagnostic and can land any time.

## Summary

Rust's lifetimes are the price of letting references escape. Azora does not need to pay it,
because Azora's borrows already do not escape — the engine and the standard library are
written that way today without anybody enforcing it. Write the rule down, add `mov` and its
use-after-move check, and make sendability a property of types rather than of references,
and you get memory safety and data-race freedom with no annotations at all.
