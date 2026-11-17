# Azora Ranges - DIP

> Status: **implemented** (2026-11-17; approved 2026-11-16). Answers
> OPERATOR_OVERLOADING_DIP §17 Q8 ("Should `Range<T>` become a real type?")
> and closes the 0.1 roadmap §1.1 item "a range operator's `by` step is parsed
> and discarded". Replaces OPERATOR_OVERLOADING_DIP §12.8's range operators and
> the range parts of CONTROL_FLOW_DIP §2. The text below describes what was
> built; §12 lists where it departs from the approved text, and why. Tests:
> `RangeValueTest`, `RangeLoopTest` (interpreter) and `RangeExecTest` (the
> same programs on LLVM and WebAssembly), `WitnessTest`/`WitnessExecTest`,
> `StringOrderExecTest`, `BoundPlacementTest`, `RangeBoundaryTest`, AZLS
> `hoverShowsARangesType`.

`a..b` becomes a value: a `Range<T>` that any ordered type forms, that can be
stored, passed, returned, printed, compared and tested with `in`, and that
`for` walks over every integer width, `Char`, the floats and any type that says
how it steps - identically on the interpreter, LLVM and WebAssembly, and with
no cost added to the loops written today.

---

## 1. Why

What a range is today (measured 2026-11-16):

- **Only a loop header.** `a..b`, `a..<b` and `a>..b` are a compiler node
  (`Expr.Range`), legal in a `for` header, an `inline for` and a `where`
  clause (`N in 2..4`). Anywhere else: "ranges can only be used as for-loop
  iterables". So `x in 0..<n` at run time, `fin r = 0..10`, a parameter of a
  range type and a function returning one do not compile.
- **Only over `Int`.** The resolver refuses other bounds ("range start must be
  Int"): `0L..5L`, `'a'..'z'`, `0u..9u` and `0.0..1.0` do not compile.
- **Declarations that do nothing.** std declares `bridge oper.. Ty&.(rhs:
  Ty&) by 1` and `oper>..` for every numeric type. Lowering only checks that
  `Int` has one; the `by 1` is parsed and discarded. The Engine's `Vec`
  declares `oper.. &.(rhs: Self&) by Self::one`, which no use can reach.
- **A step only in the loop:** `for i in 0..<n by 2`, `Int`, positive, checked
  at run time even for an empty range.

352 range loops exist today (261 in std, 91 in the Engine), all over `Int`.

## 2. Goals, non-goals, anti-goals

**Goals**

1. A range is a value of a std type, `Range<T>`, usable wherever a value is.
2. Every ordered type forms ranges: all integer widths, `Char`, the floats,
   `String`, and any type with an order.
3. `for` walks a range of every integer width, `Char`, the floats, and any
   type that declares how it steps; ascending, descending, with or without
   `by`.
4. Generic code takes, tests and walks a `Range<T>`.
5. The same results on the interpreter, LLVM and WebAssembly.
6. Every misuse is a compile error naming the fix, or - for a step only known
   at run time - a failure naming the step.

**Non-goals** (each is a separate decision, not part of this DIP)

- **Slicing with a range** (`xs[1..<3]`). Slicing keeps its own form,
  `xs[a:b]` (`oper[:]`).
- **A step stored in a range value** (`fin evens = 0..<10 by 2`). `by` stays
  where it is written today, in a `for` header; making it an expression
  operator would be new syntax.
- **Ranges as hash keys or `Equal` conformers.** `Range<T>` compares with `==`
  where `T` does (§3.4), but declares no `Hash`/`Equal` conformance: that holds
  for some `T` and not others, and the language has no conditional
  conformance (GENERICS_DIP §6.3).
- **`..` building some other type.** The range operators are not overloadable;
  `a..b` always means `Range<T>`.

**Anti-goals** - each would make this a failure even if it ran:

- an allocation, a call or a range value per iteration of `for i in 0..<n`;
- a float walk that drifts (`0.0..1.0 by 0.1` visiting 10 rows on one target
  and 11 on another);
- a walk that wraps at a type's limit (`for b: Byte in 250..255` must stop);
- a step that makes a loop run forever or skip silently.

## 3. The type

```azora
/** The values between two bounds of an ordered type, walked in a direction. */
@Since("0.1")
pack Range<T> where T: PartialOrder {
    /** The bound written first: where a walk starts. */
    fin start: T
    /** The bound written last: where a walk ends. */
    fin end: T
    /** Whether the upper bound - [end] ascending, [start] descending - is in the range. */
    fin upperIncluded: Bool = true
    /** Whether a walk goes down, from [start] to [end]. */
    fin descending: Bool = false
}
```

`lower` and `upper` are properties: the smaller and the larger bound, read
from `start`/`end` by the direction.

### 3.1 What each literal builds

| Written | Built as | `lower` | `upper` | Holds |
|---|---|---|---|---|
| `a..b` | `Range(a, b, true, false)` | `a` | `b` | `a <= x <= b` |
| `a..<b` | `Range(a, b, false, false)` | `a` | `b` | `a <= x < b` |
| `a>..b` | `Range(a, b, false, true)` | `b` | `a` | `b <= x < a` |

The bounds are stored as written, so a literal's bounds are evaluated left to
right into the fields that hold them. Every range the literals and `reversed`
make has its lower bound included and at most its upper bound excluded:
`contains` and `isEmpty` read `lower` and `upper` and do not depend on the
direction, and `reversed` swaps the bounds and flips `descending`. `a>..b`
keeps today's meaning (CONTROL_FLOW_DIP §2): `5>..0` is `4, 3, 2, 1, 0`.

A range may be empty (`5..0`, `3..<3`); building one is never an error.

### 3.2 Members

| Member | Needs | Meaning |
|---|---|---|
| `start: T`, `end: T` | - | the bounds as written: `a` and `b` of `a..b`, `a..<b` and `a>..b` |
| `lower: T`, `upper: T` | - | the smaller and the larger bound (`end`/`start` when descending) |
| `isEmpty: Bool` | - | `!(lower < upper \|\| (upperIncluded && lower == upper))` - so a NaN bound makes a range empty |
| `contains(value: T&): Bool` | - | `lower <= value && (upperIncluded ? value <= upper : value < upper)`; false for NaN |
| `value in range`, `value !in range` | - | `range.contains(value)`, as `in` already lowers |
| `reversed: Range<T>` | - | the same values walked the other way (`(0..<5).reversed` is `5>..0`) |
| `size: Int` | `T: Step` | how many rows `for x in range` visits with the unit step; fails past `Int`'s maximum |
| `==`, `!=` | - | the bounds equal by `T`'s order (`a <= b && b <= a`) and the flags equal; NaN bounds are unequal |
| `"$range"` | - | `0..<10`, `0..10`, `10>..0`; a reversed closed range shows `(0..10).reversed`; bounds render as an array's elements do (`'a'..'z'`) |

`size` is a member with a `where` clause on `T`, which the compiler checks for
the receiver (GENERICS_DIP §5.3): `(0.0..1.0).size` is 2, `Double` being
`Step` (§5); `("a".."m").size` is refused, `String` not being `Step`. `size`
is found by doubling and halving over the row index, so a range of `Long`
bounds a billion apart answers without visiting its rows. `==` compares
through `PartialOrder`, which every `Range<T>` has, so it needs no second
bound. `"$range"` is rendered by the compiler, as arrays and tuples are:
there is no conditional conformance to make `Range<T>` a `Display` only when
`T` is one.

A range is not an `Iterator` or `Indexed`: `for` walks it directly (§6), so
walking needs no cursor in the range and two nested loops over one range do
not share one.

### 3.3 Order

`Range<T>` asks `T: PartialOrder`. Every `Order` type is a `PartialOrder`:
a type that implements `Order` is one without writing it - its `<=>` already
gives every comparison `PartialOrder` asks for - so `Range<Int>`,
`Range<Char>` and `Range<String>` need nothing more, and the floats are
`PartialOrder` already. Generic code with `T: Order` may form a `Range<T>`.

### 3.4 Where it lives

`Range` and `Step` (§5) are in an exposed std module, so `a..b` needs no
import, as an array literal needs none.

## 4. Forming a range

- `a..b`, `a..<b` and `a>..b` build a `Range<T>` wherever an expression may
  stand, for any `T: PartialOrder`. Nothing is declared to allow it, as `<`
  needs only `<=>`.
- **One type.** Both bounds have one type after an unsuffixed literal adopts
  the other's (`0..n` with `n: Long` is a `Range<Long>`). Otherwise:
  `range bounds must have one type, got Int and Long; convert one, as in 'a as Long'`.
- **An order is required:** `'Vec2' has no order, so 'a..b' cannot be a range
  of it; a range needs 'PartialOrder'`.
- **Compile-time ranges are unchanged.** In a `where` clause (`N in 2..4`)
  and an `inline for` (`inline for i in 0..<N`), a range is still evaluated by
  the compiler over integer constants; no value is built.
- **`oper..` and `oper>..` are no longer declared.** A declaration is refused:
  `'oper..' is not declared: every ordered type forms ranges; to walk them,
  implement 'Step' (RANGE_DIP §5)`. std's numeric declarations become `Step`
  implementations; the Engine's two on `Vec` are removed. `by` after an
  operator declaration is refused with them.

## 5. Stepping: `Step`

```azora
/**
 * A type whose ranges can be walked: what one step is, and how to move by it.
 */
@Since("0.1")
spec Step<S> requires PartialOrder {
    /** The step a walk takes when none is written: one unit. It does not depend on the value. */
    prop &.unit: S

    /**
     * This value moved [count] steps of [step] - forward for a positive
     * count, backward for a negative one - or null when no such value
     * exists (past the type's limits).
     */
    func &.offset(step: S&, count: Long): Self?

    /** Whether [offset] has a value: the move stays within the type. */
    func &.reaches(step: S&, count: Long): Bool { … }

    /** [offset]'s value, for a move [reaches] said stays within the type. */
    func &.moved(step: S&, count: Long): Self { … }
}
```

A type implements `unit` and `offset`; `reaches` and `moved` are provided,
and are what walks and generic code call - an erased `Self?` has no room for
its null on the native targets, so generic code asks `reaches` first and then
takes `moved`.

`T: Step` written bare means "steps by some `S`": it walks by its unit, so
`func<T: Step> rows(r: Range<T>)` takes `0..<4`, `0.0..1.0` and `'a'..'c'`
alike. Generic code that writes a step names `S`: `for x in r by 2` needs
`T: Step<Int>` (§7).

`unit` is read on a value - `day.unit` - as generic code reaches every member
through a value; it is one unit whatever the value. `offset` takes the whole
count at once so a float walk multiplies instead of adding up rounding errors,
and answers null so no walk wraps.

**What std implements** (in `std.range`, with Azora bodies):

| Type | `S` | `unit` | `offset(step, k)` |
|---|---|---|---|
| `Byte`, `Short`, `Int`, `Long`, `Cent`, `UByte`, `UShort`, `UInt`, `ULong`, `UCent` | itself | `1` | `self + step·k`, exactly; null outside the type |
| `Float`, `Double` | itself | `1.0` | `self + step·k`, one rounded multiply and one rounded add |
| `Char` | `Int` | `1` | the code point `step·k` places on, skipping U+D800-U+DFFF; null below U+0000 or past U+10FFFF |

`Half` and `Quad` form ranges but are not `Step` yet: their arithmetic is not
yet checked on every backend (§12).

**A user type:**

```azora
impl Step<Duration> for Date {
    prop &.unit: Duration = Duration.day
    func &.offset(step: Duration&, count: Long): Date? = self.plus(step.times(count))
}
for day in start..end by Duration.week { … }   // start, start + 1 week, …
for day in start..end { … }                    // by the unit, a day
```

A type implements `Step` once: a second `impl Step<…>` for it is refused
(`'Day' implements Step already; a type steps one way`), since a walk without
`by` would not know which unit to take.

## 6. Walking a range

`for x in range [by step] [with index] { … }` - `range` a literal or a value.

1. **Evaluate once, left to right:** the bounds (or the range value), then
   the step. With no `by`, the step is the start's `unit`.
2. **Requirements.** `T: Step<S>`, and a written step has type `S`:
   `cannot walk a 'Range<String>': 'String' does not implement 'Step'`;
   `'by 0.5' is a Double, but a 'Range<Int>' steps by Int`; both bounds of
   one type (`range bounds must have one type, got Int and Long; …`).
3. **The step moves forward.** Before the first row, even for an empty range:
   `lower.offset(step, 1)` must be null or greater than `lower`; otherwise the
   loop fails with `the step 'by 0' does not move the range forward`. (Today's
   rule for `Int`, stated for every type.) A NaN bound leaves nothing to check
   against: the walk is empty.
4. **The rows.**
   - Ascending: `v_k = start.offset(step, k)` for `k = 0, 1, 2, …`, while
     `v_k < upper` (or `<=` when `upperIncluded`).
   - Descending: the first row is `start` (the upper bound) when it is
     included, else `start.offset(unit, -1)` - the first value below it - and
     then `v_k = first.offset(step, -k)`, while `v_k >= lower`. So
     `5>..0 by 2` is `4, 2, 0`, as today.
   - A null `offset` ends the walk: `for b: UByte in 250..255` visits six
     rows and stops; nothing wraps.
5. **Each row advances.** A row that is not strictly beyond the one before it
   in the walk's direction fails the loop (`'offset' of 'Day' did not move
   past Day(n: 3)`), so a broken `offset` cannot loop forever. Every walk but
   the `Int` counter loop checks it - std types, user types and generic code
   alike - so a walk runs the same whether its type is known or erased.
6. `break`, `continue`, labels and `with index` (rows counted from zero)
   behave as today.

**How it compiles.** `for i in a..b` over `Int` is today's counter loop: no
value, no call, no declaration needed (it compiles without std). Every other
walk - a literal of another type, or a `Range<T>` value of any type - is
lowered to the loop step 4 defines, by row index: row `k` is
`first.moved(step, ±k)` after `first.reaches(step, ±k)`, each computed from the
first row rather than the one before, so a float walk carries two roundings
however long it is. A literal's direction is known when it compiles; a
value's is read from it. In generic code the same loop reaches `T`'s order
and steps through witnesses (§7). `x in r` is `r.contains(x)`.

## 7. Generic code

```azora
func<T: PartialOrder> clamp(x: T, r: Range<T>): T =
    if x < r.lower then r.lower else if r.contains(x) then x else r.upper

func<T: Step> rows(r: Range<T>): Int {
    var n = 0
    for x in r { n += 1 }
    return n
}
```

`Range<T>`'s members and a walk in generic code reach `T`'s order and steps
the way `Hash`, `Equal` and `Order` reach erased code (GENERICS_DIP §21.3,
roadmap item 022.1): a descriptor of the concrete type, passed with the call.
This DIP adds `PartialOrder`, `Display` and `Step` to those. A bound is passed
on as any bound is (`add 'where U: Step'`). Generic code walks only a bounded
`T` (`cannot walk a 'Range<T>': 'T' is not bounded by 'Step'; write 'where T:
Step'`), and a written step needs the bound to say what `T` steps by
(`'by s' is a Int, but 'T: Step' does not say what 'T' steps by; write 'where
T: Step<Int>'`).

## 8. What changes elsewhere

- **std:** `Range` and `Step` added (`std.range`, an exposed module); the
  numeric `oper..` / `oper>..` declarations removed and `Step` implemented for
  the integer widths, `Float`, `Double` and `Char`; `Order` is a
  `PartialOrder`; every primitive and `String` declares `Display`
  (`std.format`). `STABLE_SURFACE.txt` regenerated - the owner reviews the new
  declarations.
- **The Engine:** the two `oper..`/`oper>..` lines on `Vec`
  (`azora-math/src/vec.az`) removed.
- **DIPs:** CONTROL_FLOW_DIP §2 and OPERATOR_OVERLOADING_DIP §12.8 point here;
  §17 Q8 is answered; GENERICS_DIP lists `PartialOrder` and `Step` among the
  specs that reach erased code.
- **Tooling:** AZLS shows `Range<T>` as a range's type.

## 9. Approaches considered

- **A. Compiler-only ranges, generalised.** Loops over every width, `Char`,
  floats and declaring user types; `x in a..b` as an inline test. Smallest,
  but a range could still not be stored, passed or returned, and generic code
  could not take one - goal 1 and 4 fail.
- **B. `Range<T>` built by each type's `oper..`** (OPERATOR_OVERLOADING_DIP
  §12.8). Keeps the declarations, but an ordered type without one forms no
  interval, the operator never has a meaningful body (`..` and `..<` are one
  member, so a body could not know which was written), and generic code still
  needs a spec to bound on.
- **C. `Range<T>` for every ordered type; walking needs a step** - chosen.
  Order gives the interval, `Step` gives the walk (the split Swift's
  `Comparable`/`Strideable` and Rust's `Range`/`Step` make). It removes the
  `oper.. … by` form, which never worked.

## 10. Calls made in this design (the owner may overturn any)

- The names `Range`, `Step`, `lower`, `upper`, `upperIncluded`, `descending`,
  `start`, `end`, `unit`, `offset`, `reaches`, `moved`, `reversed`.
- `Range` and `Step` are `@Since("0.1")` (stable), being what a language
  literal builds.
- `..` is not overloadable; `oper..` declarations are removed, not kept as a
  second way to declare a step.
- A step stays in the `for` header; no stepped range value.
- `size` is an `Int`, failing past `Int`'s maximum, like a collection's.
- A descending range with an excluded upper bound starts one *unit* below it,
  then moves by the step (today's `5>..0 by 2` = `4, 2, 0`, kept for every
  type).
- Ranges are not `Hash`/`Equal` conformers until conditional conformance exists.

## 11. Left to the builder

- Which exposed module holds `Range` and `Step`.
- The descriptor layout for `PartialOrder` and `Step` in erased code, and how
  a stored `Range<T>` carries it.
- Each backend's counter loop per width (`Cent`'s 128 bits on WebAssembly
  included) and the exact-or-null arithmetic of integer `offset`.
- The wording of messages, within the meaning given here.
- Staging. A sensible order: (1) range values and `contains`/`in` for the std
  types; (2) `for` over every std type; (3) `Step` for user types and generic
  code; (4) removing `oper..`, the Engine change, the DIP updates. Each stage
  lands with interpreter, LLVM and WebAssembly tests.

## 12. Implemented differently from the approved text

Each was decided while building, for the reason given; the owner may
overturn any.

- **The bounds are stored as written** (`start`, `end`), with `lower`/`upper`
  derived, instead of storing `lower`/`upper`. The IR has no block
  expression, so `a>..b` could not evaluate `a` before `b` while building
  `(b, a, …)`. Same information; the constructor reads as the literal does.
- **`unit` is read on a value** (`prop &.unit`, `day.unit`), not as
  `Date::unit`: the language has no static dispatch through a type parameter,
  and witnesses reach instance members.
- **`Step<S>` has no default.** With `S = Self` a bare `T: Step` excluded
  `Char` (`Step<Int>`), and `size where T: Step` could not serve `Range<Char>`.
  A bare bound walks by its unit; generic code that writes a step names `S`.
- **`reaches` and `moved`** are provided members of `Step`, used by walks
  and generic code: an erased `T?` has no room for its null on LLVM and
  WebAssembly (a pre-existing limit), and `offset` returns `Self?`.
- **Every walk other than `Int`'s goes by row index** (one call per row)
  rather than a native counter at every width: exact at every limit and
  drift-free because std's `offset` is; only speed differs.
- **Range values walk through the same lowering**, reading their direction
  at run time - `Range<Int>` values included.
- **The advance check runs on every non-`Int` walk**, not only user types,
  so generic and concrete walks agree; a float walk whose rows stop moving
  (past 2^53 steps of its size) fails instead of repeating a row.
- **`==` compares by order and `"$range"` is rendered by the compiler**,
  rather than members bounded by `PartialEqual`/`Display`: a spec member may
  not bound the type's parameter, and there is no conditional conformance.
- **`Half` and `Quad` are not `Step` yet**: their arithmetic is not checked
  on every backend; their ranges are values and test membership but do not
  walk. `Cent` and `UCent` were left out at first, their arithmetic being
  wrong on every backend; it is 128 bits everywhere since 2026-11-18, and they
  step (`offset` measures room in a `UCent`).
- **`oper..<` is refused** alongside `oper..` and `oper>..`, each message
  naming what was written.
