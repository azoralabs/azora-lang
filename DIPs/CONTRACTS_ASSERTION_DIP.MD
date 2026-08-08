# Azora Contracts and Assertions - DIP Implementation Profile

> Status: **implemented**, except where marked. Contracts and assertions are
> checked in every build, including `--release`; §6 is the open question about
> whether that should stay true.

A contract states what a function requires of its caller and what it promises in
return. An assertion states something the author believes is true at a point in
the code. Azora has both, plus a compile-time form of the second that turns a
belief into a diagnostic.

```azora
func half(n: Int): Int
in {
    assert n >= 0 { "n must be non-negative" }
} out {
    assert it >= 0 { "result must be non-negative" }
} scope {
    return n / 2
}
```

---

## 1. `assert`

```azora
assert condition { message }
assert condition
```

The message block is optional; a bare `assert` uses an empty one, which is worth
avoiding since it is what the failure prints.

The `{ message }` is part of the assert, not a trailing lambda on the condition.
The parser suppresses trailing-lambda parsing for the duration of the condition,
so `assert list.add(v) { "could not add" }` means what it looks like rather than
handing the block to `add`.

### 1.1 Failure

| backend     | on failure                                       |
|-------------|--------------------------------------------------|
| interpreter | raises `Assertion failed: <message>`             |
| LLVM        | `puts(message)` then `abort()`, then unreachable |
| Wasm        | `unreachable` - **the message is lost**          |

- ⬜ Wasm discards the message, so a failed assertion there says only that the
  program trapped. Either route it to a host import or state that Wasm
  assertions are messageless

---

## 2. Contracts

Contract clauses sit between a function's signature and its body.

```azora
func f(a: Int, b: Int): Int
in {
    assert a > 0 { "a must be positive" }
    assert b > 0 { "b must be positive" }
} out {
    assert it > 0 { "result must be positive" }
    assert it == a + b { "result must be the sum" }
} scope {
    return a + b
}
```

`in { }` is a **precondition**, checked on entry. `out { }` is a
**postcondition**, checked on every return.

**A function has at most one of each.** Several conditions go inside the one
clause, in the order written; a second `in` or `out` is a parse error. Splitting
one requirement across two clauses would say nothing the single clause cannot,
and would raise a question about ordering that has no good answer.

A clause opens at the declaration's own indentation rather than under the
signature, and each closing brace carries the next keyword: `} out {`,
`} scope {`. The body is the last clause in that chain, so a contracted
function reads as one list of clauses rather than a signature with decoration
hanging off it.

A clause body is an ordinary block. `assert` is the usual thing to put in one,
but nothing requires it.

### 2.1 `it`, and naming the result

A postcondition needs the value being returned. By default it is bound to `it`:

```azora
out {
    assert it >= 0 { "result must be non-negative" }
}
```

Naming it explicitly is the other form:

```azora
func twice(n: Int): Int
out { r ->
    assert r == n * 2 { "must double" }
} scope {
    return n * 2
}
```

A postcondition also sees the function's parameters, which is what makes
`assert r == n * 2` expressible at all.

### 2.2 How a postcondition reaches every return

`out` is not appended to the end of the body - that would miss every early
return. Each `return expr` is rewritten into a scope that binds the value, runs
the postconditions against it, and then returns the binding:

```azora
return expr

// becomes

{
    fin it = expr
    …postconditions…
    return it
}
```

The rewrite descends into `if`, `while`, `for`, `loop`, `when`, `try` and nested
scopes, so a return anywhere in the body is covered. `yield` is rewritten the
same way where a generator's contract applies.

A bare `return` with no value is left alone: there is nothing to bind.

### 2.3 The body is a `scope { }` clause

`scope { }` closes the chain. It is the function's ordinary body - the keyword
is there so the body sits in the same list as the clauses before it, rather
than being a brace that happens to follow them.

A function with no contracts writes its body as a plain block, as usual. The
`scope` spelling is what the contracted form uses.

---

## 3. `inline assert` - a contract on the compiler

```azora
inline assert TARGET == Target.C { "this module is C-only" }
```

The condition is evaluated at compile time. If it folds to `false`, the message
becomes a **compile error**; if it folds to `true`, the statement is deleted. If
it does not fold at all, it survives to run time.

That last case is the one to know about: an `inline assert` over something the
evaluator cannot reduce silently degrades into a runtime check rather than
failing to compile. Nothing warns.

- ⬜ Decide whether a non-folding `inline assert` should be an error. Silently
  becoming a runtime check defeats the point of writing `inline`

Unlike a runtime `assert`, the message block is **required**.

Inside a `deepinline` block, an ordinary `assert` is treated as an
`inline assert` - the whole block is compile-time, so its assertions are too.

---

## 4. `inline trace` and `panic`

```azora
inline trace "reached the specialisation for $Ty"
inline trace .Warn "falling back"
panic "unrecoverable"
inline panic "unreachable at compile time"
```

`trace` prints `[LEVEL] message`. The level is a `std::LogLevel` - `Debug`
(the default), `Info`, `Warn`, `Error` - and `inline trace` emits at compile
time, which is how a metaprogram reports what it is generating.

`panic` is an unrecoverable abort, lowering to `__panic`.

---

## 5. `test` blocks

```azora
test "duration normalizes overflow" {
    fin value = std::duration(2L, 1500000000L)
    assert value.seconds == 3L { "overflow must carry into seconds" }
}

test .All "generators"
```

A `test` is a named block run by `azora test`. Assertions inside it are ordinary
assertions - the test framework is the `assert` statement plus a runner.

`.This` (the default) runs only that block. `.All` aggregates every `This` test
in the same source file into one, and is the only form allowed to omit its body.

The standard library uses these heavily: `std/serializer.az` alone carries 56.

---

## 6. Release builds - contracts are always checked

`IrOptimizer` carries an explicit rule: **asserts are never eliminated.** They
survive constant folding, dead-code elimination and unused-symbol elimination,
and `--release` does not drop them. A failing precondition aborts a release
build exactly as it aborts a debug one.

This is a real behaviour, verified, but it has never been stated as a decision.

- ⬜ **Decide and document.** The options are the usual three: always checked
  (what happens today), dropped in release, or controlled by a flag. Each is
  defensible; what is not defensible is leaving a program's release semantics
  to an optimiser comment
- ⬜ If contracts stay checked, say what that costs - a precondition on a hot
  function is a branch per call, and nothing measures it

---

## 7. What is not specified

- ⬜ **Contracts are not inherited.** An `impl` of a spec method does not pick
  up contracts from the spec's declaration, and nothing says whether it should.
  Without a rule, a spec cannot state a contract its implementers must honour
- ⬜ **No invariants.** There is no `pack`-level clause checked after every
  mutation, which is the third of the three classic contract forms
- ⬜ **`out` on a failable function is unaddressed.** Whether a postcondition
  runs on the error path of `T ?! E` is undefined
- ⬜ **Contracts and `defer` have no stated ordering.** Whether a postcondition
  runs before or after a deferred block is unspecified
- ⬜ **A contract can have side effects.** Nothing requires a clause to be pure,
  so a contract can change the behaviour it is meant to describe
- ⬜ **`assert` is not a type-narrowing operator.** `assert x != null` does not
  make `x` non-null afterwards, which is the thing most people expect
- ⬜ **Assertion failure is not catchable.** It is an abort, not an error in the
  `T ?! E` sense, and that distinction is not written down anywhere

---

## 8. Rules

1. `in { }` checks on entry; `out { }` checks on every return.
2. A clause opens at the declaration's indentation, and each closing brace
   carries the next keyword: `} out {`, `} scope {`.
3. A postcondition binds the returned value to `it`, or to a name given as
   `out { name -> … }`.
4. `out` is woven into each `return`, not appended to the body, so early returns
   are covered.
5. A function has at most one `in` and at most one `out`. Several conditions go
   inside the one clause.
6. A runtime `assert`'s message block is optional; an `inline assert`'s is
   required.
7. An `inline assert` that folds false is a compile error, one that folds true
   disappears, and one that does not fold becomes a runtime check.
8. Assertions and contracts are checked in every build, including `--release`.

---

## 9. Related

- `CTCE_DIP.MD` - what compile-time evaluation can fold, which decides whether
  an `inline assert` is a diagnostic or a runtime check
- `TESTING_TRACING_DIP.MD` - `test` blocks and `trace` in full (currently empty)
- `FUNCTIONS_DIP.MD` - where contract clauses sit in a declaration
- `ROADMAPs/VERSION_0_1_ROADMAP.MD` §3.2 - the release-build decision
