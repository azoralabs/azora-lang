# Azora Operator Overloading - DIP

> Status: **the comparison family is implemented** in `0.0.5`; the rest of §12
> is proposed.
>
> | part | state |
> |------|-------|
> | `<=>`, its precedence, non-associativity (§5.1–5.3) | **shipped** |
> | `oper` members on a `spec` (§5.3, §6) | **shipped** |
> | `<` `<=` `>` `>=` from `<=>`, `!=` from `==` (§5.4–5.5) | **shipped** |
> | `Compare` / `PartialCompare` and their members (§4) | **shipped** |
> | `PartialEqual` / `Equal` / `PartialOrder` / `Order` / `Hash` (§6, §12.6) | **shipped** |
> | derivation from a bodyless `impl` (§7) | **shipped** |
> | `where T: Spec<Args>` bounds (§11) | **shipped** |
> | unary dispatch, `oper!` (§2.6, §12.5) | **shipped** |
> | in-place compound assignment (§12.4) | **shipped** |
> | `==` without a conformance is an error (§8) | **shipped** |
> | the operator families (§12.1) and partial implementation (§12.2) | **shipped** |
> | `Display`, and `"${v}"` routed through it (§12.7) | **shipped** |
> | the cast specs, and `impl as T for Type` retired (§12.9) | **shipped** |
> | `Iterator` (§12.8) | proposed |
> | closing the free-operator form language-wide (§14, phase 11) | proposed |
>
> Naming note: the specs shipped as `Order` / `PartialOrder`, not `Ordering` /
> `PartialOrdering`. `Hash`'s member is `prop hash`, not `oper#` - the `#`
> operator is gone, though the token survives as the container-macro sigil.
> Casts and conversions moved to `CAST_DIP.MD`.

Every operator in Azora becomes a member of a `spec`, and every overload is
written inside `impl <Spec> for <Type>`. §12 is the full inventory - arithmetic,
unary, bitwise, compound assignment, indexing, dereference, range, hash,
display, cast. §§2–11 work the comparison family out in detail, because it is
the one with real design questions and the one whose absence was a bug.

Azora already lets a type define what `+` means. What it does not have is any
notion of what it means for two values to be *the same*, or for one to come
*before* another. Six comparison operators exist as six unrelated members, no
capability names the group, and - the defect that matters - `a == b` on a pack
that never defined equality compiles anyway and gives a different answer on
every backend.

The claim this proposal makes:

> Comparison is stated once, per type, and everything else is derived from it.

`<=>` is how it is stated. Four specs are how it is required. The rest of this
document is the mechanism.

---

## 1. Scope

This DIP covers **every** operator Azora has, in two layers.

**The rule (§12).** Each operator symbol gets a `spec` that owns it, and an
overload is written inside `impl <Spec> for <Type>` - never as a free
declaration. That is thirty-odd specs across arithmetic, unary, bitwise,
compound assignment, comparison, indexing, dereference, range, `Hash`,
`Display` and `Cast`, listed in full at §12.1. Along the way it fixes two
things that are broken rather than missing: unary overloads never dispatch at
all (§2.6), and `oper!` cannot be written.

**The worked family (§§2–11).** Equality (`==`, `!=`), ordering
(`<`, `<=`, `>`, `>=`), the new three-way operator `<=>`, the result types
`Compare` and `PartialCompare`, the capabilities `PartialEqual`, `Equal`,
`PartialOrder` and `Order`, how the compiler derives them, and the
standard-library types that should have had them since `0.0.4`. Comparison gets
the detail because it is where the design questions are - total versus partial
order, what derives from what, and a silent miscompile (§2.4) that has been in
the language since packs were.

What does not change: how an operator is mangled (`Mangling.kt:22`), how
overloads are keyed by operand and receiver exclusivity (`Parser.kt:2151`), or
the bracketed-receiver surface from `FUNCTIONS_DIP.MD` §5. Operators keep the
shape they have; they gain a home.

---

## 2. Where the language actually is

Stating this plainly, because two of these are bugs rather than gaps.

### 2.1 The operators exist, individually

`parseOperatorName` (`Parser.kt:450`) recognises `==`, `!=`, `<`, `<=`, `>`,
`>=` and hands each to `parseFreeOperator`, which registers a member named
`oper==`, `oper<` and so on. Dispatch is `operOverloadName` +
`SymbolTable.lookupOperator`, duplicated in the resolver
(`TypeResolver.kt:2328`, `:2488`) and the lowerer (`IrGenerator.kt:2224`,
`:1466`). Overloads are keyed by operand type and, for non-mutating operators,
by receiver exclusivity (`Parser.kt:2151`).

So a type *can* say `oper< [self: Version&](rhs: Version&): Bool` today. It just
has to say all six.

### 2.2 The standard library shows the cost

Seven times in `std/container`, a type writes an `oper!=` whose entire body is
the negation of its `oper==`:

```azora
oper!= [self: HashSet<T>&](rhs: Set<T>): Bool {
    return !(self == rhs)
}
```

- `set.az:525`, `:725`, `:770`; `map.az:1418`, `:1444`, `:1468`;
`list.az:460`. Not one of them differs from the others.

`TreeMap` pays the other half of the bill. Having only `oper<` for its keys, a
descent through the tree calls it twice per node:

```azora
if key < self.keys[elem] {
    elem = self.left[elem]
} else if self.keys[elem] < key {
    elem = self.right[elem]
} else { … }
```

- `map.az:863`, and again at `:883`. And `<=` has to be spelled out of `<`:

```azora
fin beforeEnd = all[i].key < toKey || (inclusive && !(toKey < all[i].key))
```

- `map.az:1127`, `:1137`, `:1148`. For an `Int` key that is two integer
compares where one would do; for a `String` key it is two full lexicographic
walks per node visited, at every level of the tree.

### 2.3 There is no name for "comparable"

There is no `Equal`, no `Order`, no `Compare` anywhere in `std/`. `traits.az`
has `Clone`, `Copy` and `PrettyPrint`, and stops. Consequently:

- `func sort<T>(arr: Array<T>): Array<T>` (`sort.az:42`) accepts *any* `T` and
  then writes `result[k] > key`. It type-checks only because an erased generic
  resolves to `Any`, which the resolver lets compare against anything
  (`TypeResolver.kt:2567`). Sorting an array of a type with no ordering is a
  runtime question. Same for `sortDescending`, `isSorted`, `minOf`, `maxOf`
  (`search.az:89`, `:107`).
- `map.az`'s header comment states the real contract - "`TreeMap<K, V>` requires
  `oper<` for `K`" (`map.az:33`) - in a doc comment, because there is no way to
  write it in the signature.
- `SYNTAX.MD:81` asks for `typealias Comparable = [oper<, oper>, oper<=, oper>=]`
  as the way to say it. §11 explains why that is the right mechanism for the
  wrong problem.

### 2.4 `==` on a pack is undefined behaviour with a straight face

This is the important one.

`resolveBinaryType` reaches its `EQUAL_EQUAL` branch (`TypeResolver.kt:2548`)
after the overload lookup misses, and returns `Bool` for any `left == right`.
Two `Vec2`s with no `oper==` therefore **compile**. Lowering emits a raw
`IrBinaryOp.EQ`, and the backends do not agree on what that is:

- the interpreter evaluates Kotlin's `==` on its runtime representation
  (`IrInterpreter.kt:1069`) - structural, for a map-backed object;
- LLVM emits `icmp eq` on the value's representation
  (`LlvmCodegen.kt:3632`, `:3763`) - identity, for a struct behind a pointer.

The same program prints `true` under `az run` and `false` after `az build`.
Nothing warns.

Order does not have this problem: the relational branch
(`TypeResolver.kt:2565`) rejects any non-numeric, non-`Char` pair outright. So
`a < b` fails loudly on a pack and `a == b` fails silently. §8 fixes that.

### 2.5 A spec cannot require an operator - but an impl can supply one

Exactly one side of the pairing is missing, and it is worth being precise about
which, because it sets the size of this proposal.

**Missing.** `parseSpec` accepts `func`, `prop`, and a bare `self&` receiver
line, and nothing else - `consume(TokenType.FUNC, "Expected 'func' or 'prop' in spec")`
(`Parser.kt:3961`). A capability that means "this type has `oper<=>`" cannot be
written today.

**Already there.** `impl Spec for Type { oper<SYM> [self: Self&](…) { … } }`
parses now (`Parser.kt:2948`), with receiver, operands, return type, `where`
clause, overload suffix, expression body and bodyless form. Trait generic
arguments (`impl PartialEqual<Vec2I> for Vec2`) are read at `Parser.kt:2739`.
Bodyless grouped impls already queue conformances (`:2764`). And an impl member
already takes its return type from the spec it implements
(`SymbolCollector.kt:630`).

So the language can express "this type's `==` is its `PartialEqual`
implementation" - it simply has no `PartialEqual` to point at. §14 makes the
spec side Phase 1.

What *was* deliberately removed is a third thing: `impl oper<SYM> for Type`,
rejected at `Parser.kt:2718` with a message directing to the free
`oper<SYM> [self: Type&]` form. That form made the *operator* the impl head,
which leaves nowhere to name a capability. §5.3 puts the **spec** in the head
instead, which is the piece it was missing.

### 2.6 Unary operator overloads never run

Not a gap - a declaration the compiler accepts and then ignores.

`Expr.Unary` lowering maps the token straight to an `IrUnaryOp`
(`IrGenerator.kt:1426`):

```kotlin
val op = when (expr.op) {
    TokenType.MINUS -> IrUnaryOp.NEG
    TokenType.BANG  -> IrUnaryOp.NOT
    TokenType.TILDE -> IrUnaryOp.BIT_NOT
    else -> error("Unknown unary op: ${expr.op}")
}
```

There is no `lookupOperator` call anywhere on this path, and the resolver
rejects the operand before it is reached - "cannot negate $operandType" for any
non-numeric (`TypeResolver.kt:1172`), "'!' requires Bool" for anything else
(`:1180`). So:

- a declared unary `oper-` on a pack is **dead code**;
- `oper~` is in the dispatch table (`TypeResolver.kt:2340`,
  `IrGenerator.kt:2236`) but that table is read only by `resolveBinaryType`, so
  it is dead too;
- `oper!` cannot even be declared - `parseOperatorName` has no `BANG` case
  (`Parser.kt:450`–`:518`).

`-v` on a `Vec2` is therefore a compile error today no matter what the type
declares, and `~mask` on a `BitSet` likewise. §12.5 fixes all three.

---

## 3. What to take from where

C++20 and Rust each solved half of this. The halves are separable.

| question                          | C++20                           | Rust                       | Azora                             |
|-----------------------------------|---------------------------------|----------------------------|-----------------------------------|
| how is comparison stated?         | `operator<=>`                   | `cmp` / `partial_cmp`      | **`oper<=>`** - C++'s             |
| where is it written?              | free or member                  | in the trait `impl`        | **in the spec `impl`** - Rust's   |
| is the result a type or an int?   | a type (`strong_ordering`, …)   | a type (`Order`)           | **a type** - both agree           |
| how many result types?            | three (strong / weak / partial) | one, wrapped in `Option`   | **two** - neither's               |
| are `<` `<=` `>` `>=` derived?    | yes, rewritten from `<=>`       | yes, provided on the trait | **yes, rewritten** - C++'s        |
| is `!=` derived from `==`?        | yes                             | yes (default method)       | **yes**                           |
| is `==` derived from `<=>`?       | **no**, deliberately            | no (separate trait)        | **no** - both agree               |
| is comparison a named capability? | concepts, bolted on later       | traits, from the start     | **specs** - Rust's                |
| total vs partial?                 | a property of the return type   | a property of the trait    | **the impl header** - neither's   |
| reversed operands (`1 < obj`)?    | auto-generated                  | never                      | **never** - Rust's                |
| memberwise default?               | `= default`                     | `#[derive]`                | **a bodyless `impl`** - neither's |
| are floats ordered?               | partially                       | partially                  | **partially**                     |

Two of those rows deserve their reasoning stated, since they are where this
proposal declines to copy the obvious thing.

**Two result types, not three.** C++ separates `strong_ordering` from
`weak_ordering` to record whether `a <=> b == equal` implies that `a` and `b`
are substitutable. It is a real distinction and almost nobody uses it
correctly; Rust does not have it and has not suffered. What *is* worth a type is
the distinction C++ and Rust both make and both need: whether two values can be
*incomparable*. So: `Compare` for a total order, `PartialCompare` for a partial
one, and substitutability is a documented promise of `Equal` rather than a third
type.

**No reversed candidates.** C++ will silently try `b <=> a` when `a <=> b` does
not resolve, which is where most of the operator-lookup complexity in C++20
lives. Azora already has a narrower, explicit answer for the mixed-operand case:
an overload declared *on the left operand's type* naming the right operand
(`TypeResolver.kt:2473` handles exactly this for primitives). `1 < meters` is
`oper<=> [self: Int&](rhs: Meters&)`. Written where it happens, resolved where
it is written.

---

## 4. Two result types

Add to `std/traits/traits.az`:

```azora
/**
 * The result of `<=>` on a totally ordered type.
 *
 * Total means every pair of values relates: for any `a` and `b`, exactly one
 * of `Less`, `Equal`, `Greater` holds, and the relation is transitive. A type
 * whose `<=>` returns `Compare` is making that promise - see `Order`.
 */
@Since("0.1")
enum Compare {
    Less
    Equal
    Greater
}

/**
 * The result of `<=>` on a partially ordered type.
 *
 * `Unordered` is the fourth answer a total order does not have: the two values
 * are neither less, nor greater, nor equal. `Double.NaN` against anything is
 * the case that forces this type to exist.
 */
@Since("0.1")
enum PartialCompare {
    Less
    Equal
    Greater
    Unordered
}
```

Both are plain (payload-free) enums, so `==` on them is a tag comparison the
backends already emit, and `when` over them is exhaustive with no `else`:

```azora
when a <=> b {
    .Less    -> { … }
    .Equal   -> { … }
    .Greater -> { … }
}
```

That is the whole reason the result is an enum rather than an `Int`: the
compiler can tell you when you forgot a case. C++'s categories are comparable
against the literal `0` and nothing checks that you covered them; Rust's
`Option<Order>` nests one match inside another for the partial case, which is
what `Unordered` flattens.

> **Naming note.** `Compare.Equal` and `spec Equal` (§6) share a spelling.
> They do not collide: enum variants are registered under their enum
> (`SymbolTable.defineEnum`, `:262`), never globally, and are reached as
> `Compare.Equal` or as bare `.Equal` where the type is known.

### 4.1 `Compare` widens to `PartialCompare`

One implicit conversion, one direction. A total order *is* a partial order in
which nothing is unordered, so:

```azora
impl Into<PartialCompare> for Compare
```

is a bridge conversion the compiler inserts wherever a `PartialCompare` is
wanted and a `Compare` is supplied. The reverse never happens implicitly  - 
narrowing away `Unordered` has to be a decision someone wrote down.

This is what spares Azora the single worst piece of Rust boilerplate, where
every `Ord` type also writes

```rust
fn partial_cmp(&self, o: &Self) -> Option<Order> { Some(self.cmp(o)) }
```

In Azora a type declares `<=>` once; the widening makes it satisfy a
`PartialOrder` bound as well (§6.3).

### 4.2 Members

Both types carry the same three members, each over its own type:

```azora
inline for Ty in [Compare, PartialCompare] {
    impl Ty {
        /** The comparison with its operands swapped. `Less` ⇄ `Greater`. */
        prop reversed[self: Self&]: Ty

        /**
         * `self` if it is decisive, otherwise `next`. Lexicographic chaining.
         *
         * Decisive means anything but `Equal` - so on `PartialCompare` an
         * `Unordered` first field stops the chain and the whole comparison is
         * `Unordered`, which is what a lexicographic order over a partial one
         * has to do.
         */
        func then[self: Self&](next: Ty): Ty

        /** `then`, with the second comparison evaluated only if it is reached. */
        func thenBy[self: Self&](next: () -> Ty): Ty
    }
}
```

`Compare.then(PartialCompare)` also resolves, by §4.1's widening: a chain that
starts with a total field and continues into a partial one answers
`PartialCompare`, which is the correct result type for the mixture.

`then` is what makes a hand-written multi-field comparison read as one
expression:

```azora
impl Order for Person {
    oper<=> [self: Self&](rhs: Self&) {
        return (self.last <=> rhs.last)
            .thenBy { self.first <=> rhs.first }
            .thenBy { self.born <=> rhs.born }
    }
}
```

`reversed` is what a descending sort is, rather than a second copy of the sort
with the comparisons flipped (`sort.az:71` is currently that second copy).

---

## 5. The `<=>` operator

### 5.1 Lexing

`<=>` becomes a single token, `TokenType.SPACESHIP`, matched before `<=` by
maximal munch. Nothing else in the grammar produces the sequence `<`, `=`, `>`,
so this takes no existing spelling away.

### 5.2 Precedence

A new level between shift and relational, matching C++:

```
parseEquality      ==  !=
parseComparison    <  <=  >  >=
parseSpaceship     <=>            ← new
parseInfix         a plus b
parseShift         <<  >>
```

`parseComparison` (`Parser.kt:7197`) descends into `parseSpaceship`, which
descends into `parseInfix`. So `a <=> b == Compare.Less` groups as
`(a <=> b) == Compare.Less`, which is the reading that makes §5.4's rewriting
transparent.

**`<=>` is non-associative.** `a <=> b <=> c` is a compile error naming the
ambiguity, not a left fold. Chaining is `then`/`thenBy` (§4.2), which says what
it means.

### 5.3 Declaring it: inside the spec's `impl`

A comparison operator is **always** a member of the `impl` of the spec that
governs it. Never a free declaration:

```azora
@Since("0.1")
impl Order for Version {
    oper<=> [self: Self&](rhs: Self&) {
        return (self.major <=> rhs.major)
            .thenBy { self.minor <=> rhs.minor }
            .thenBy { self.patch <=> rhs.patch }
    }
}
```

This is Rust's shape - the operator lives in the trait impl, not loose beside
the type - written with Azora's `impl … for …` and Azora's bracketed receiver.

**No new syntax is needed.** `impl Spec for Type { oper<SYM> … }` already parses
today: `Parser.kt:2948` handles `oper` as an impl-body member with the whole
surface - receiver, operands, return type, `where` clause, overload suffix,
expression body, and the bodyless form. `impl PartialEqual<Vec2I> for Vec2`
parses too; trait generic arguments are read at `Parser.kt:2739` and kept as
`traitArgs`. The only lexical addition in this DIP is the `<=>` token itself,
which `parseOperatorName` picks up in one line
(`match(TokenType.SPACESHIP) -> "<=>"`); mangling already handles punctuation in
operator names (`Mangling.kt:22`).

> **This is not the form that was removed.** `Parser.kt:2718` rejects
> `impl oper<SYM> for Type` - the operator as the *impl head* - and directs to
> the free `oper<SYM> [self: Type&]` spelling. That form put the operator where
> the spec belongs and had nowhere to name a capability. Here the impl head is
> the **spec**, and the operator is an ordinary member of it, which is what the
> removed form was missing.

### 5.3.1 The spec chooses the return type

`oper<=>` above names no return type, and that is the point.

`SymbolCollector.specMemberReturnType` (`:630`) already reads an impl member's
return type off the spec it implements - the reason
`impl Clone for Vec2 { func clone[self: Self&]() { … } }` need not repeat
`: Vec2`. `Order` declares `oper<=> … : Compare` and `PartialOrder`
declares `oper<=> … : PartialCompare` (§6), so:

| the impl you write        | `<=>` returns    |
|---------------------------|------------------|
| `impl Order for T`        | `Compare`        |
| `impl PartialOrder for T` | `PartialCompare` |

**Choosing the spec is how a type says whether its order is total.** There is no
return type to get wrong, no way to write `impl Order` and return
`PartialCompare`, and no third place the totality claim could hide. The version
of this proposal that used free operators had to make `<=>` the one operator
required to name its result - that rule is now gone, and `FUNCTIONS_DIP.MD:154`'s
blanket exemption for operators holds without an exception.

Writing the return type explicitly is still allowed and must match the spec.

### 5.4 Rewriting

For `a OP b` with `OP` in `<`, `<=`, `>`, `>=`, and `a: T`:

1. If `T` declares `oper OP` accepting `b`'s type, call it. **An explicit
   operator always wins**, so a type that can answer `<` faster than it can
   answer `<=>` keeps that.
2. Otherwise, if `T` declares `oper<=>` accepting `b`'s type, rewrite. The
   receiver and operand are evaluated once each, `a <=> b` is called **once**,
   and its result is bound to a temporary the comparison reads.
3. Otherwise, the existing error (`TypeResolver.kt:2565`), with §13's wording.

The rewrite depends on the result type, and this is where `Unordered` earns its
place:

| `a OP b` | `<=>` returns `Compare` | `<=>` returns `PartialCompare`   |
|----------|-------------------------|----------------------------------|
| `a < b`  | `c == .Less`            | `c == .Less`                     |
| `a <= b` | `c != .Greater`         | `c == .Less \|\| c == .Equal`    |
| `a > b`  | `c == .Greater`         | `c == .Greater`                  |
| `a >= b` | `c != .Less`            | `c == .Greater \|\| c == .Equal` |

(where `c` is the bound result of `a <=> b`)

The `Compare` column uses the negated form because a total order has three
answers and negation is one tag test. The `PartialCompare` column cannot: with
four answers, `!= .Greater` would make `NaN <= 1.0` true. All four comparisons
must be **false** when the answer is `Unordered`, and the positive form is what
delivers that.

Getting this wrong is a classic floating-point bug. Writing it in the compiler
once means no user writes it again.

### 5.5 `!=` from `==`

`a != b` where `T` declares `oper==` but not `oper!=` rewrites to `!(a == b)`.
Same rules: explicit wins, operands evaluated once.

This deletes the seven bodies in §2.2 outright.

### 5.6 `==` is **not** derived from `<=>`

Deliberately, and this is the one place C++ got it right against the symmetry
argument.

`HashSet<T>.oper==` (`set.az:516`) opens with `if self.size != rhs.size { return false }`
and is O(1) for the common negative case. A lexicographic `<=>` over the same
set is O(n) always. Deriving `==` from `<=>` would silently replace the first
with the second everywhere a set is compared.

Beyond speed, the two are genuinely different questions for some types: a
case-insensitive string orders by folded form but may want `==` to mean exactly
equal, and a version with build metadata orders without it but compares with it.
A language that ties them together makes those types unwritable.

So: a type that declares only `oper<=>` gets `<`, `<=`, `>`, `>=` and nothing
else. `==` on it is the §8 error until it says. The exception is derivation  - 
`impl [Order] for T` (§7) generates *both*, because there the author asked
for the memberwise default of everything.

---

## 6. Four capabilities

Add to `std/traits/traits.az`, beside `Clone` and `Copy`.

### 6.1 The partial ones are generic in the right-hand operand

`==` and `<=>` are not questions a type asks only of itself. `Meters` against
`Feet`, a `String` against a `StringView`, a `Vec2` against a `Vec2I` - an
operator already accepts a foreign operand today (the overload table is keyed by
operand type, `Parser.kt:2151`), so the capability that names it has to be keyed
the same way. Otherwise the bound can describe half the operators a type has.

```azora
/**
 * A type whose values can be compared to `Rhs` for equality.
 *
 * `==` must be symmetric (`a == b` iff `b == a`, which for a foreign `Rhs`
 * means `Rhs` compares back the same way) and transitive. It need not be
 * reflexive: `Double.NAN == Double.NAN` is false, and that is what keeps this
 * capability separate from `Equal`.
 *
 * `Rhs` defaults to `Self`, so the homogeneous case - nearly all of them  - 
 * is written `PartialEqual` with no argument.
 */
@Since("0.1")
bridge spec PartialEqual<Rhs = Self> {
    oper== [self: Self&](rhs: Rhs&): Bool
}

/**
 * A type whose values can be ordered against `Rhs`, with some pairs possibly
 * not relating at all.
 *
 * Satisfied by an `oper<=>` returning either result type: a `Compare` widens
 * to `PartialCompare` (§4.1), so a totally ordered type meets this bound
 * without declaring a second operator.
 */
@Since("0.1")
bridge spec PartialOrder<Rhs = Self> requires PartialEqual<Rhs> {
    oper<=> [self: Self&](rhs: Rhs&): PartialCompare
}
```

`Rhs = Self` is a default type argument. `parseTypeParams` (`Parser.kt:4692`)
already reads `= <value>` defaults for *const* parameters; this extends the same
slot to a type. Without it every bound in the language would have to say
`PartialEqual<Self>`, which is noise on the 95% case.

### 6.2 The total ones are not generic

```azora
/**
 * A type whose equality is a full equivalence relation on its own values.
 *
 * Adds one promise to `PartialEqual<Self>`, and it is the promise the compiler
 * cannot check: `a == a` for every value. Everything that keys off equality  - 
 * hashing, a set's uniqueness, a map's lookup - is broken by a value that is
 * not equal to itself, so those require `Equal` and not `PartialEqual`.
 *
 * Two `Equal` values are also promised substitutable: interchangeable
 * everywhere the type is observed.
 */
@Since("0.1")
bridge spec Equal requires PartialEqual<Self>

/**
 * A type with a total order on its own values: every pair relates, and the
 * relation is transitive and antisymmetric.
 *
 * `requires Equal` because a total order and its equality have to agree  - 
 * `a <=> b` answering `Equal` while `a == b` answers false is the disagreement
 * that makes a sorted container lose elements.
 */
@Since("0.1")
bridge spec Order requires Equal {
    oper<=> [self: Self&](rhs: Self&): Compare
}
```

`Equal` and `Order` take no `Rhs`, and the reason is not economy - it is that
their extra promises are **statements about one type's value set**. Reflexivity
is `a == a`; there is no cross-type reading of it. Totality and antisymmetry are
properties of a relation on a set, and `Meters` ∪ `Feet` is not the set either
type is talking about. A generic `Equal<Rhs>` would be a promise with no
content. Rust reaches the same conclusion (`Eq: PartialEq<Self>`,
`Ord: Eq + PartialOrd<Self>`), and it is the one place its design is not a
compromise.

The heterogeneous case is therefore always spelled with the partial specs, and
loses nothing: a `Meters <=> Feet` that never returns `Unordered` still returns
`Compare`, and §4.1's widening lets it satisfy `PartialOrder<Feet>`. The
totality is in the return type, where the compiler can see it. Only the *name*
`Order` is reserved for the homogeneous case.

Note what carries the claim in each pair. For **ordering** it is the return
type - `Compare` has no `Unordered`, so the type system holds the promise. For
**equality** nothing in the signature can express reflexivity, so a marker spec
holds it. The asymmetry is the compiler taking over the half of the contract it
is able to.

### 6.3 `Order` satisfies a `PartialOrder` bound

`Order` does not `require PartialOrder<Self>` - the two declare the same
member at different return types, and requiring both would demand two `<=>`
declarations per type.

Instead the resolver treats `Order` as satisfying `PartialOrder<Self>`
directly, inserting the §4.1 widening at the call:

```azora
func clamp<T>(v: T, lo: T, hi: T): T where T: PartialOrder { … }

clamp(3, 0, 10)          // Int is Order - accepted, Compare widened
clamp(3.0, 0.0, 10.0)    // Double is PartialOrder - accepted directly
```

One `<=>` per type, both bounds satisfied. This is the concrete payoff of §3's
"two result types, not three, and one widening between them", and it is what
spares Azora Rust's `Some(self.cmp(other))` boilerplate.

### 6.4 Conformance is stated, never inferred

Because every comparison operator lives inside `impl <Spec> for <Type>` (§5.3),
there is no separate question of what a type conforms to. The `impl` header is
the conformance. A loose `oper==` beside a type does not quietly make it
`PartialEqual`, because after this DIP a loose comparison operator does not
exist (§6.5).

That leaves **one** axis, and it is the body:

| what you write                            | what you get                           |
|-------------------------------------------|----------------------------------------|
| `impl [Equal, Order] for Vec2I` - no body | the compiler writes the members (§7)   |
| `impl Order for Version { oper<=> … }`    | your members, checked against the spec |

Bodyless is derive. With a body is yours. One syntax, one rule, and it is
already the syntax `std/traits/core.az:31` uses for `impl [Clone, Copy] for Ty`.

Rust needs two constructs here - `#[derive(PartialOrd, Ord)]` above the type and
`impl Ord for T { fn cmp }` below it - and they look nothing alike. C++ needs
`auto operator<=>(const T&) const = default;` and a written one, which at least
share a declaration but differ by a keyword in the body position. Azora's
existing bodyless-impl rule collapses both into the presence or absence of
braces.

### 6.5 A comparison operator outside a spec impl is an error

```
line 12: 'oper==' must be declared inside the impl of the spec that governs it
  write:  impl PartialEqual for Vec2 { oper== [self: Self&](rhs: Self&) { … } }
```

The six comparison operators and `<=>` are covered by
`PartialEqual` / `Equal` / `PartialOrder` / `Order`, so each has exactly
one place to be written. The rule buys three things:

- the conformance can never disagree with the members, because they are the same
  declaration;
- the return type of `<=>` is decided by the header (§5.3.1);
- `oper!=`, `oper<`, `oper<=`, `oper>`, `oper>=` have **no** spec to belong to  - 
  they are rewrites (§5.4, §5.5), not members - so the rule is what makes
  "do not hand-write these" enforceable rather than advisory.

Operators with no spec behind them yet - `oper+`, `oper[]`, `oper as`
- keep the free form they have today. This DIP does not touch them; §15 says
why extending the rule to every operator is a separate proposal.

---

## 7. Derivation

Both specs are `bridge` for the reason `Clone` is: the compiler can generate the
member, and a type only writes one when the generated one is wrong.
`SymbolCollector` already derives `Clone` and `Copy` field-wise to a fixed point
(`SymbolCollector.kt:593`); this extends that pass.

### 7.1 What gets generated

```azora
pack Point {
    var x: Int
    var y: Int
}

@Since("0.1")
impl [Equal, Order] for Point
```

generates, for `Point`:

- `oper==` - field-wise `&&`, in declaration order, short-circuiting;
- `oper<=>: Compare` - lexicographic, in declaration order, returning at the
  first field that is not `Equal`;
- `hash` - the field-wise hash (§7.3).

`<`, `<=`, `>`, `>=` and `!=` come from §5.4 and §5.5 and are not generated as
members at all. One `<=>` and one `==` per type is the whole footprint.

### 7.2 When derivation is refused

- `impl Equal for T` where some field is not `Equal` - error naming the field
  and its type. A `Double` field is the common case, and the message says so.
- `impl Order for T` where some field is not `Order`, same.
- `impl Order for T` where the author also wrote `oper<=>` - the written
  one wins and the derivation is skipped, matching `Clone`'s rule
  (`SymbolCollector.kt:597`: "an explicit `impl` always wins").
- A `variant enum` derives both when every payload field of every case does;
  cases order by declaration position first, payload lexicographically second.
  A payload-free `enum` always derives both, ordering by declaration position  - 
  this is the rule that makes `Compare` and `PartialCompare` themselves
  comparable without special-casing.

**Nothing is derived silently.** Unlike `Clone`, which is derived for any pack
that did not say, comparison is derived only where `impl Equal` /
`impl Order` is written. Equality has semantics - a cache field, an
interned id, a floating-point tolerance all change what "the same" means - and a
compiler that guesses will be wrong for exactly the types where it matters most.
`Clone` can be derived because duplication has one correct answer. Equality does
not.

### 7.3 `Equal` and `Hash` are derived together

A type whose `==` and `#` disagree corrupts every hash container it is a key of:
`HashMap` looks in the bucket the hash names and finds nothing, and the entry is
lost rather than found. `map.az:31` already states the requirement - "`HashMap`
… require `Hash` and `oper==`" - with nothing enforcing it.

`Hash` is the spec that owns hashing (§12.6), and it `requires Equal` for
exactly this reason: the two are one contract, and a type whose equality is not
reflexive cannot promise that equal values hash equally. So:

- deriving `Equal` **also derives `Hash`**, over the same fields in the same
  order, unless the type wrote its own `hash`;
- writing `oper==` and `hash` by hand is allowed and unchecked - the compiler
  cannot verify that two hand-written bodies agree;
- a type that is `Equal` but whose hand-written `Hash` is missing cannot be used
  as a hash key, and the error names both members;
- a container that hashes says so in its signature - `where K: Hash` - rather
  than in a doc comment (§12.6).

Rust ties `Hash` and `Eq` by convention and a clippy lint. C++ ties them not at
all. Deriving both from one declaration, and putting `requires Equal` on the
spec so the pair cannot come apart, is strictly better than either.

### 7.4 Writing it by hand: `Vec2`

Derivation is the common case; this is the whole surface when a type does it
itself. `Vec2` is the useful example precisely because **it cannot derive**  - 
its fields are `Double`, and `Double` is not `Equal` (§9).

```azora
pack Vec2 {
    var x: Double
    var y: Double
}
```

**Equality.** One impl, one member:

```azora
@Since("0.1")
impl PartialEqual for Vec2 {
    oper== [self: Self&](rhs: Self&) {
        return self.x == rhs.x && self.y == rhs.y
    }
}
```

`PartialEqual` with no argument is `PartialEqual<Vec2>` by the `Rhs = Self`
default (§6.1). The `: Bool` is omitted because the spec declared it (§5.3.1).

That is the entire equality surface. `!=` comes from §5.5 - there is nowhere to
write an `oper!=` even if you wanted to, since no spec declares one (§6.5).
That is the seventh copy of §2.2's boilerplate made unwritable rather than
merely discouraged.

Note which impl is *absent*: `impl Equal for Vec2` would be **rejected**, and
correctly. `Vec2(Double.NAN, 0.0)` is not equal to itself, so `Vec2` cannot be a
`HashMap` key or a `HashSet` element. The compiler says so where the key is used
rather than losing the entry at runtime.

**Order.** A vector has no natural total order, so there are two honest
choices and the DIP does not pick for you.

*Lexicographic* - deterministic, which is what sorting and tree containers want:

```azora
@Since("0.1")
impl PartialOrder for Vec2 {
    oper<=> [self: Self&](rhs: Self&) {
        return (self.x <=> rhs.x).thenBy { self.y <=> rhs.y }
    }
}
```

`PartialOrder` is the header, so the result is `PartialCompare` and the body
never says so. That is not a convenience - it is the correctness point. `Vec2`
*must* be partial: a `NAN` component makes two vectors genuinely incomparable,
and §5.4's second column then makes `<`, `<=`, `>` and `>=` all false for that
pair. Writing `impl Order for Vec2` instead is caught at the header, before
any of the body is read.

Nothing else is written. `v1 < v2`, `v1 <= v2`, `v1 > v2`, `v1 >= v2` all work
from that one member, each calling it once.

*By magnitude* - if the domain wants it:

```azora
@Since("0.1")
impl PartialOrder for Vec2 {
    oper<=> [self: Self&](rhs: Self&) {
        return self.lengthSquared <=> rhs.lengthSquared
    }
}
```

with the caveat stated plainly: this orders distinct vectors as `Equal`, so it
is a *weak* order and `a <=> b == .Equal` no longer agrees with `a == b`. C++
has a type for that distinction (`weak_ordering`) and §3 declined to copy it, so
in Azora it is a comment on the impl and a reason not to also claim `Equal`.
Lexicographic is the safer default.

**The integer version derives everything.**

```azora
pack Vec2I {
    var x: Int
    var y: Int
}

@Since("0.1")
impl [Equal, Order] for Vec2I
```

No braces, so the compiler writes the members (§6.4) - `oper==`, `oper<=>`
returning `Compare`, and `hash` (§7.3). One line replaces all of the above, and
`Vec2I` is a legal hash key and a legal `TreeMap` key. This is the shape most
packs should have.

**Comparing the two - the generic specs doing work.** Two more impls on `Vec2`,
naming a foreign `Rhs`:

```azora
@Since("0.1")
impl PartialEqual<Vec2I> for Vec2 {
    oper== [self: Self&](rhs: Vec2I&) {
        return self.x == (rhs.x as Double) && self.y == (rhs.y as Double)
    }
}

@Since("0.1")
impl PartialOrder<Vec2I> for Vec2 {
    oper<=> [self: Self&](rhs: Vec2I&) {
        return (self.x <=> (rhs.x as Double))
            .thenBy { self.y <=> (rhs.y as Double) }
    }
}
```

`Vec2` now conforms to `PartialEqual<Vec2>`, `PartialEqual<Vec2I>`,
`PartialOrder<Vec2>` and `PartialOrder<Vec2I>` - and `v < grid` compiles.
Four impl headers, four conformances, nothing inferred. This is what §6.1's
`Rhs` parameter exists for: without it these two impls would have the same
header as the first two and collide, and the bound `where T: PartialEqual<U>`
could not be written at all (§11).

The operand type may also be spelled `Rhs`, which the header binds:

```azora
impl PartialEqual<Vec2I> for Vec2 {
    oper== [self: Self&](rhs: Rhs&) { … }     // Rhs = Vec2I
}
```

Symmetry is the author's responsibility. Azora does not synthesise
`impl PartialEqual<Vec2> for Vec2I` from the above (§3, "no reversed
candidates"), so `grid == v` needs its own impl. Explicit, and greppable.

---

## 8. `==` without a conformance becomes an error

The §2.4 defect, fixed:

```
line 42: cannot compare two 'Vec2' values - Vec2 does not implement PartialEqual
  add 'impl [Equal] for Vec2' to compare it field by field, or write it out:
    impl PartialEqual for Vec2 { oper== [self: Self&](rhs: Self&) { … } }
```

`resolveBinaryType`'s `EQUAL_EQUAL` branch stops returning `Bool` for two
identical `IrType.Named` types with no `PartialEqual` conformance. Everything it
currently accepts for good reasons - comparison against `null`, a nullable
against its inner type, an erased `Any` - is unaffected; the change is narrow,
and it is the difference between a program with a bug and a program that does
not build.

Primitives, `String`, `Char`, `Bool`, pointers and enums are unaffected: they
get their conformance in §10.1 and compare as they always have.

### Migration

Any pack currently compared with `==` and relying on the backend's accidental
answer now fails to build until it states `impl [Equal] for T`. That is the
intended outcome and the fix is one line per type. A `--warn-implicit-equality`
transition flag downgrades it to a warning for one release; `0.0.6` removes the
flag.

---

## 9. Floating point

`Double` and `Float` are `PartialEqual` and `PartialOrder`. They are never
`Equal` and never `Order`, because `NaN != NaN` and `NaN` relates to nothing.
`f <=> g` on two `Double`s returns `PartialCompare`, and §5.4's second column
makes all four relational operators false when either is `NaN` - which is what
IEEE 754 requires and what a `Compare`-based rewrite would silently get wrong.

The consequence is the one Rust is famously criticised for: `sort` requires
`Order`, so **`sort(arrayOfDoubles)` does not compile**. This DIP does not
pretend otherwise; it makes the fix a one-liner instead of a lookup.

`Float::` and `Double::` already exist as type-scoped blocks holding the limits
and special values (`primitive.az:341`, `:363`). Each gains one static, in the
form `Array::fill` uses (`array.az:87`) - a `bridge func`, because the ordering
is a bit-pattern operation the backend emits:

```azora
@Since("0.1")
impl Double:: {
    /**
     * The IEEE 754 totalOrder predicate: a genuine total order over every
     * `Double`, NaNs included, obtained by ordering the sign-magnitude form
     * of the bit pattern. -NaN < -Inf < … < -0.0 < +0.0 < … < +Inf < +NaN.
     *
     * This is what `sortBy` wants when the data really is `Double` and the
     * result has to be deterministic. It is not `oper<=>`: `Double` is
     * `PartialOrder`, and `<` must keep answering false at NaN.
     */
    bridge func totalCompare(a: Double&, b: Double&): Compare
}
```

so the working spelling is:

```azora
sortBy(prices) { a, b -> Double::totalCompare(a, b) }   // NaN to one end, deterministically
sortBy(samples) { a, b -> a.magnitude <=> b.magnitude } // or order by something total
```

and the type that wants to be sortable without a comparator wraps:

```azora
pack Price { fin cents: Long }        // Order, because Long is
```

Which is the honest advice anyway: money and measurements should not be
`Double`s, and a language whose sort refuses them is telling the truth about
a partial order rather than hiding it behind a comparison that lies at `NaN`.

`sortBy` already exists (`algorithm.az:94`) but takes a key function
`(T) -> T`; §10.2 changes it to take a comparator.

---

## 10. What the standard library gets

### 10.1 Primitives - `std/primitive.az`, `std/traits/core.az`

The type lists are already there (`primitive.az:403`–`:419`), and
`impl Spec for TypeList` already works (`:423`), so this is three lines:

```azora
@Since("0.1")
impl [PartialEqual, Equal, Order] for Integers

@Since("0.1")
impl [PartialEqual, PartialOrder] for FloatingPoints

@Since("0.1")
impl [PartialEqual, Equal, Order] for [Bool, Char, String]
```

Bodyless, so §6.4 applies - but for a primitive the "derivation" is the
backend's native comparison rather than a generated field walk. `Compare` for
the integers because `Order` is in the list; `PartialCompare` for the floats
because it is not (§5.3.1). The conformance is the entire declaration, exactly
as `impl [Clone, Copy] for Ty` is today (`traits/core.az:31`).

Note there is no `inline for Ty in …` loop and no `bridge oper<=>` line. An
earlier draft of this DIP wrote one, which was a leftover of the free-operator
model: with the operator inside the spec's impl, a bodyless
`impl Order for Integers` says *both* things at once - that the conformance
holds and that the compiler supplies the member.

`Bool` orders `false < true`. `Char` orders by code point. `String` orders
lexicographically by code point, which is `Order` and not a collation - a
locale-aware collation is a separate function and should never be what `<` does.

### 10.2 Algorithms - `std/algorithm/`

Signatures gain the bound the bodies always assumed:

```azora
func sort<T>(arr: Array<T>): Array<T> where T: Order
func sortDescending<T>(arr: Array<T>): Array<T> where T: Order
func isSorted<T>(arr: Array<T>): Bool where T: Order
func minOf<T>(arr: Array<T>): T where T: Order
func maxOf<T>(arr: Array<T>): T where T: Order
func binarySearch<T>(arr: Array<T>, target: T&): Int? where T: Order

func sortBy<T>(arr: Array<T>, cmp: (T&, T&) -> Compare): Array<T>
func sortByKey<T, K>(arr: Array<T>, key: (T&) -> K): Array<T> where K: Order
```

`sortDescending` becomes `sortBy(arr) { a, b -> (a <=> b).reversed }` rather
than a duplicated insertion sort with flipped comparisons (`sort.az:71`).

`sortBy`'s current key-function form (`algorithm.az:94`, typed `(T) -> T`, which
cannot express a key of a different type) becomes `sortByKey`.

### 10.3 Containers - `std/container/`

- **Move every free comparison operator into its spec's impl** (§6.5). The
  fourteen `oper==` / `oper!=` declarations at `set.az:516`–`:775`,
  `map.az:1406`–`:1473` and `list.az:451`–`:465` are all top-level today; each
  `oper==` becomes the body of an `impl PartialEqual<…> for …`:

  ```azora
  @Since("0.1")
  impl PartialEqual<Set<T>> for HashSet<T> {
      oper== [self: Self&](rhs: Rhs&) {
          if self.size != rhs.size { return false }
          for i in 0..<self.size {
              if !rhs.contains(self.data[i]) { return false }
          }
          return true
      }
  }
  ```

  Note the `Rhs` these already need: `HashSet<T>` compares against `Set<T>`, not
  against itself, so the conformance really is `PartialEqual<Set<T>>`. The
  standard library was already doing heterogeneous equality (`set.az:516`); it
  simply had no way to say so. This is the concrete case §6.1's type parameter
  exists for.
- Delete all seven `oper!=` bodies (§2.2). They are §5.5 now, and after §6.5
  there is no longer anywhere to put them.
- `HashMap`, `HashSet`, `LinkedHashMap`, `LinkedHashSet` declare
  `where K: Equal` / `where T: Equal` - not `PartialEqual`, because a key
  that is not equal to itself can be inserted and never found again.
- `TreeMap`, `TreeSet` declare `where K: Order`, replacing the doc comment
  at `map.az:33`.
- `TreeMap`'s descent becomes one call per node:

  ```azora
  when key <=> self.keys[elem] {
      .Less    -> { elem = self.left[elem] }
      .Greater -> { elem = self.right[elem] }
      .Equal   -> { return elem }
  }
  ```

  replacing `map.az:863`, `:883`, `:1072`, `:1099`; and the four
  `a < b || (inclusive && !(b < a))` spellings at `:1127`–`:1149` become
  ordinary `<=` / `>=`.
- `ArrayList<T>`, `HashSet<T>` and the maps gain `oper<=>` - lexicographic for
  the sequences, by sorted key order for `TreeMap` - under
  `where T: Order`, so a list of lists sorts.

### 10.4 `Compare` and the enums themselves

`Compare` and `PartialCompare` are payload-free enums, so §7.2's enum rule gives
them `Equal` and `Order` for free, ordered by declaration position. That is
what makes `(a <=> b) == .Less` a tag comparison rather than a special case in
the rewriter.

---

## 11. Constraints: `where T: Spec<Args>`

### 11.1 The bound form

A comparison bound is written with `:`, and carries type arguments:

```azora
func min<T>(a: T, b: T): T where T: Order {
    return if a < b { a } else { b }
}

func indexOf<T, U>(haystack: Array<T>, needle: U&): Int?
    where T: PartialEqual<U>
{
    for i in 0..<haystack.length {
        if haystack[i] == needle { return i }
    }
    return null
}

func clamp<T>(v: T, lo: T, hi: T): T where T: PartialOrder
```

The equivalent spelling in the parameter list is also accepted, and means
exactly the same thing:

```azora
func min<T: Order>(a: T, b: T): T
func indexOf<T: PartialEqual<U>, U>(haystack: Array<T>, needle: U&): Int?
```

Several bounds on one parameter use the bracket list Azora already uses for
`impl [A, B] for T`:

```azora
func sortedKeys<K>(m: Map<K, V>): Array<K> where K: [Order, Clone]
```

### 11.2 What this costs to implement

Less than it looks, because half of it parses today.

- `<T: Spec>` in a type-param list is already read and already discarded:
  `Parser.kt:4677` comments it as "a conformance constraint (**accepted, not yet
  enforced**)". This DIP enforces it. No grammar change.
- `= <default>` in the same list already exists for const parameters
  (`Parser.kt:4692`); §6.1 extends it to type parameters so `Rhs = Self` works.
- `where T: Spec` is the piece that does not exist. `parseWhereClause`
  (`Parser.kt:4718`) parses its clause as an ordinary expression, and `T: Spec`
  is not one. It needs a bound form recognised before the expression fallback,
  which is a small, local change to one function.

### 11.3 Why not `where T is Order`

Because `is` cannot say it. `Expr.IsCheck` holds `typeName: String`
(`Ast.kt:374`) and the parser fills it from a single identifier  - 
`consume(TokenType.IDENTIFIER, "Expected type name after 'is'")`
(`Parser.kt:7130`). There is nowhere for `<U>` to go. `where T is PartialEqual<U>`
is not a design preference this DIP is declining; it is unparseable.

So the two mechanisms divide by what they are:

- **`:` is a bound.** Declarative, on a type parameter, carries type arguments,
  composes with `requires` chains and with §6.3's widening. Every conformance
  constraint in this DIP is written this way.
- **`is` is a predicate.** It appears *inside* a constraint expression, where it
  composes with `&&`, `!` and const predicates - as `traits.az:46` does today
  with `where T.length >= 2 && T is Number`. It stays exactly as it is.

`where T: Order` and `where T: Order` are therefore not two spellings of
one thing; the second is the first embedded in a boolean expression, and you
reach for it only when there is a boolean expression to embed it in.

### 11.4 The `SYNTAX.MD` operator-list aliases are rejected

`SYNTAX.MD:81` proposes naming the operators directly:

```azora
typealias Comparable = [oper<, oper>, oper<=, oper>=]
typealias Equtable   = [oper==, oper!=]
```

This DIP does not adopt it, for comparison or for anything else:

- **It describes the wrong thing.** `[oper<, oper>, oper<=, oper>=]` is a type
  that declared four operators. After §5.4 a well-formed comparable type
  declares **none** of them - it declares `<=>`. The alias would reject exactly
  the types it is meant to accept.
- **It cannot hold the promises.** `Equal`'s reflexivity and `Order`'s
  transitivity are why a sorted container works. No list of member names says
  either. A `spec` says both, in its doc comment, and enforces what it can.
- **It cannot take arguments.** `oper==` in a list does not say *against what*.
  `PartialEqual<U>` does.
- **It duplicates `spec`.** A named group of required members with a contract
  attached is what `spec` already is. Two mechanisms for it is one too many.

The idea the alias was reaching for is right, though, and §12.3 keeps it  - 
scoped to a spec rather than floating free. `where T: Bitwise[<<, >>]` names
exactly the members `Shiftable` wanted to name, with a spec to anchor them, a
contract behind them, and arguments (`Bitwise<Rhs, Shift, Out>`) the alias could
never carry.

### 11.5 `typealias` over *specs* is a different thing, and is kept

What §11.4 rejects is aliasing bare operator symbols. Aliasing **specs** is
sound, because every element carries a contract:

```azora
@Since("0.1")
typealias FullCast<To> = [Cast<To>, CheckedCast<To>, BitCast<To>]

@Since("0.1")
typealias Numeric = [Arithmetic, Order, Display]
```

`where T: FullCast<String>` then means `where T: [Cast<String>, CheckedCast<String>, BitCast<String>]`,
and `where T: Numeric` means the three bounds written out. The alias is pure
shorthand: it introduces no new conformance, and a type satisfies it exactly
when it satisfies every element.

The difference from the rejected form is not cosmetic:

| `typealias Comparable = [oper<, oper>]` | `typealias Numeric = [Arithmetic, Order]` |
|-----------------------------------------|-------------------------------------------|
| elements are member names               | elements are specs                        |
| no contract behind any element          | each element carries its own              |
| cannot take arguments                   | `FullCast<To>` passes `To` through        |
| duplicates what `spec` does             | composes what `spec` already declares     |

A member-selector may appear inside one, so the two mechanisms compose:

```azora
typealias Addable = [Arithmetic[+], Clone]
```

Aliases do not nest into `requires` - a spec still requires specs, not aliases  - 
because `requires` is part of a contract and an alias is a spelling.

`Comparable` and `Equatable` are not defined as aliases of the four specs
either - a second name for `Order` is a second thing to explain.

---

## 12. Every operator has a spec

§6.5 requires a comparison operator to live inside the impl of the spec that
governs it. That rule is only defensible if **every** operator has one, so this
section names the rest. After this DIP there is no free operator declaration in
the language: `oper<SYM>` appears in a `spec` body (the requirement) or in an
`impl <Spec> for <Type>` body (the implementation), and nowhere else.

### 12.1 One spec per operator family

Not one spec per operator. `Add`, `Sub`, `Mul`, `Div`, `Rem` and their five
compound-assignment twins are ten specs describing one idea, and a numeric type
would open ten `impl` blocks to say it. The families are:

| spec                                            | operators                                                      | §    |
|-------------------------------------------------|----------------------------------------------------------------|------|
| `Arithmetic<Rhs = Self, Out = Self>`            | `+` `-` `*` `/` `%`, `+=` `-=` `*=` `/=` `%=`, unary `-`       | 12.4 |
| `Bitwise<Rhs = Self, Shift = UInt, Out = Self>` | `&` `\|` `^` `<<` `>>`, `&=` `\|=` `^=` `<<=` `>>=`, unary `~` | 12.4 |
| `Logical`                                       | unary `!`                                                      | 12.5 |
| `PartialEqual<Rhs = Self>`                      | `==`                                                           | 6.1  |
| `Equal`                                         | - marker, `requires PartialEqual<Self>`                        | 6.2  |
| `PartialOrder<Rhs = Self>`                      | `<=>` → `PartialCompare`                                       | 6.1  |
| `Order`                                         | `<=>` → `Compare`, `requires Equal`                            | 6.2  |
| `Indexable<Idx = Int, Out>`                     | `[]` `[]=` `[:]`                                               | 12.1 |
| `Deref<To>`                                     | `.*`                                                           | 12.1 |
| `DerefMut<To>`                                  | `.^`, `requires Deref`                                         | 12.1 |
| `Iterator<Rhs = Self, Out>`                     | `..` `reverse..`                                               | 12.8 |
| `Hash`                                          | `prop hash`, `requires Equal`                                  | 12.6 |
| `Display`                                       | `$`                                                            | 12.7 |
| `Cast<To>`                                      | `as`                                                           | 12.9 |
| `CheckedCast<To>`                               | `as?`                                                          | 12.9 |
| `BitCast<To>`                                   | `as*`                                                          | 12.9 |

Fifteen specs covering every symbol `parseOperatorName` accepts
(`Parser.kt:450`–`:518`), plus `<=>` and `!`.

The three casts are separate specs and the five arithmetic operators are not,
which looks inconsistent until the next paragraph gives the rule. `FullCast<To>`
(§11.5) is the alias for all three; it is not a sixteenth spec.

`Bitwise` carries a separate `Shift` parameter because a shift count is not a
value of the shifted type - `primitive.az:547` already declares
`bridge oper$op [self: Ty&](rhs: UInt): Ty` for exactly that reason, while `&`
takes a `Self`. One `Rhs` could not express both.

**The rule that decides how coarse a family is:**

> Group when only the *symbol* differs. Split when the *promise* differs.

`+` and `%` make the same promise about a type - closed, total, returns an
`Out` - so ten arithmetic specs collapse into one. `<=>` returning `Compare` and
`<=>` returning `PartialCompare` make different promises (§6.2), so comparison
stays four specs, one of which (`Equal`) declares no member at all. `as` is
total, `as?` can fail, `as*` is a bit reinterpretation with a size requirement  - 
three promises, three specs (§12.9).

The rule is what keeps this from being a taste question, and it is why
`FullCast` in §11.5 is an alias rather than a fourth spec: "all three casts" is a
convenient bound, not a claim about a type.

### 12.2 A type implements the part it wants

A spec normally requires all its members. For operator families that would be
worse than the ten-spec version - `impl Arithmetic for Matrix` would demand a
`%` on matrices.

So **operator-family members are individually optional**. An `impl` supplies a
subset; the compiler records exactly which:

```azora
impl Arithmetic for Matrix {
    oper+= [self: Self!](rhs: Self&) {
        for i in 0..<self.data.length { self.data[i] += rhs.data[i] }
    }
}
```

`Matrix` now has `+=`, and `+` by §12.4. It does not have `%`, and `m % n` is an
error naming the missing member - not a link failure, not a silent fallback:

```
line 88: 'Matrix' has no '%' - its 'impl Arithmetic' does not declare 'oper%'
```

This is the rule that makes one spec per family work. Without it, grouping
trades ten impl blocks for five unwanted operators.

It applies only to the operator families in §12.1. `Clone`, `Send`, `Order` and
every other spec keep the usual all-members rule; a capability that is a promise
cannot be half-kept.

### 12.3 Bounds name members when they need to

`where T: Arithmetic` means the whole family - every operator in the row. That
is what generic numeric code wants and it stays short.

When a function needs less, the bracket says which:

```azora
func sum<T>(xs: Array<T>): T where T: Arithmetic[+]
func scale<T>(v: T, k: Double): T where T: Arithmetic[*]
func midpoint<T>(a: T, b: T): T where T: Arithmetic[+, /]
```

`sum` accepts a `Matrix` that only implemented `+=`; `%` was never its business.
This is precise where Rust's `T: Add<Output = T>` is precise, without needing a
spec per operator to be precise *with*.

> **Two brackets, two meanings.** `where K: [Order, Clone]` is a list of
> *specs*; `where T: Arithmetic[+, /]` is a list of *members*, distinguished by
> following a spec name with no space. §11.4 rejected a free-floating
> `[oper<, oper>]` because it named operators with no spec to anchor them  - 
> anchored to one, the same idea is exactly right.

### 12.4 What derives from what

The point of the families is that a partial impl is still a complete type. Every
derivation is a rewrite at the use site, evaluating each operand once, and an
explicit member always wins.

| you write | you also get      | condition                      |
|-----------|-------------------|--------------------------------|
| `oper==`  | `!=`              | - (§5.5)                       |
| `oper<=>` | `<` `<=` `>` `>=` | - (§5.4)                       |
| `oper+`   | `+=`              | `Self` assignable              |
| `oper+=`  | `+`               | `Self` is `Clone`              |
| `oper[]`  | -                 | `[]=` is a different operation |
| `oper.^`  | `.*`              | `DerefMut` implies `Deref`     |

and the same for the other four arithmetic pairs and all five bitwise pairs.

**Compound assignment derives in both directions**, which is the piece that
makes §12.2 pay off. C++ convention writes `+=` and derives `+`; Rust makes you
write both. Neither direction is right on its own:

- `a += b` with only `oper+` becomes `a = a + b`. Good for a `Money` - nothing
  is allocated either way.
- `a + b` with only `oper+=` becomes `var t = a.clone(); t += b; t`. Good for a
  `Matrix` - the in-place loop stays in-place when it is used in place, and the
  clone happens only where a new value was actually asked for.

The `Clone` condition is not a footnote: a type with `oper+=` and no `Clone` has
`+=` and no `+`, and the diagnostic says which of the two to add.

Unary `-` is **not** derived from binary `-`, and neither is derived from the
other. `0 - x` is not negation for every type, and a type may negate without
subtracting.

### 12.5 Unary operators do not dispatch at all today

The §2.6 defect, and `Arithmetic`/`Bitwise`/`Logical` are what fix it.

Three things do not exist:

- **`oper!` is unwritable.** `parseOperatorName` has no `BANG` case
  (`Parser.kt:450`–`:518`), so logical negation cannot be overloaded even in
  principle. One line adds it, and `Logical` is its home.
- **Unary dispatch is missing.** `Expr.Unary` lowering maps the token straight
  to an `IrUnaryOp` (`IrGenerator.kt:1426`) with no `lookupOperator` call, and
  the resolver rejects any non-numeric `-` before that (`TypeResolver.kt:1172`).
  Both need the lookup the binary path has.
- **`oper~` is listed but unreachable.** `operOverloadName` maps
  `TILDE -> "oper~"` (`TypeResolver.kt:2340`, `IrGenerator.kt:2236`), but that
  table is consulted only from `resolveBinaryType`. A declared `oper~` is dead
  code today.

Unary and binary declarations of the same symbol are told apart by operand
count, which the existing overload suffix already handles
(`operatorOverloadSuffix`, `Parser.kt:2151`): `oper- [self: Self&]` registers as
`oper-`, `oper- [self: Self&](rhs: Vec2&)` as `oper-@Vec2`. Nothing new is
needed for the naming; only the lookup.

### 12.6 `Hash`

```azora
/**
 * A type that can be reduced to a hash value.
 *
 * `requires Equal` because the two are one contract: equal values must hash
 * equally, and a type whose equality is not even reflexive cannot promise it.
 * That is why `Hash` requires the *total* equality and not `PartialEqual`  - 
 * `Double` hashes fine in isolation and is useless as a key.
 */
@Since("0.1")
bridge spec Hash requires Equal {
    prop hash[self: Self&]: ULong
}
```

`bridge`, so a bodyless `impl [Hash] for Point` generates the field-wise hash;
an `impl Hash for Point { prop hash … }` with a body supplies it instead (§6.4).

**`impl [Equal] for T` also derives `Hash`**, over the same fields in the same
order, unless the type wrote its own. §7.3 gives the reasoning: a type whose
`==` and `#` disagree is inserted into a `HashMap` and never found again, and
generating both from one declaration is the only way to make that impossible.
Deriving `Equal` alone and *then* using the type as a key does not fail - the
hash is already there.

**Types that need a hash say `requires Hash`.** That is the point of naming the
capability: the requirement moves out of `map.az`'s header comment
(`map.az:31`, "`HashMap` … require `Hash` and `oper==`") and into the
signature.

```azora
pack HashMap<K, V> where K: Hash { … }
pack HashSet<T>    where T: Hash { … }
```

`Hash requires Equal` and `Equal requires PartialEqual<Self>`, so `where K: Hash`
is the whole key contract in one bound. `TreeMap` says `where K: Order` instead
and requires no hash at all, which is what `map.az:148` already documents in
prose.

### 12.7 `Display`

```azora
/**
 * A type with a canonical text form.
 *
 * The member is handed a `Formatter` to write into rather than asked to
 * return a `String`: writing into a buffer is what lets a composite render
 * its parts without each one allocating - a list of a thousand points
 * appends to one buffer instead of building a thousand intermediate strings.
 */
@Since("0.1")
bridge spec Display {
    func display[self: Self&](formatter: Formatter!)
}
```

`Formatter` and the one-shot `format(value)` live in `std/format.az`.
`"${value}"` calls `Display` and nothing else, so a type that has not said how
it prints does not print - the interpolation is a compile error naming the
missing implementation rather than a fallback that leaks a pack's internals
into output.

**This unifies mechanisms that currently overlap**, which is most of its value:

| today                              | who uses it                                 |
|------------------------------------|---------------------------------------------|
| `oper$`                            | the interpolation hook, undocumented        |
| `impl Into<String> for T`          | `set.az:506`, `list.az:441`, and three more |
| `spec PrettyPrint { prop pretty }` | `traits.az:86`, `tuple.az:53`               |
| `value as String`                  | enums (`ENUMS_DIP.MD` §5)                   |

Four spellings, four results, no rule for which one `"${v}"` picks. Proposed:

- **`Display` is what interpolation and `${…}` use.** One hook, named, and
  `oper$` retires into it - a sigil operator with no spec was the reason the
  hook was undocumented.
- **`Into<String>` stays**, because it answers a different question:
  `.toString` is a *conversion the caller asked for* (`CAST_DIP.MD` §1.4),
  while `display` is how the value renders inside a message. A type may have
  both and they may differ.
- **`PrettyPrint` is retired** into `Display`. It has one implementor
  (`tuple.az:53`) and its `prop pretty` is `Display`'s member under another
  name.
- **`value as String` on an enum** stays a cast (`CAST_DIP.MD` §1.1), derived
  for payload-free enums as the variant name - which is what it does now.

### 12.8 `Iterator`

```azora
/**
 * A type whose values bound a range: what `a..b` and `a reverse.. b` produce.
 */
@Since("0.1")
bridge spec Iterator<Rhs = Self, Out = Range<Self>> {
    oper..         [self: Self&](rhs: Rhs&): Out
    oper reverse.. [self: Self&](rhs: Rhs&): Out
}
```

Three notes, each from something the parser already does:

- **`..` and `..<` are one member.** `parseOperatorName` maps both to `".."`
  (`Parser.kt:468`–`:469`) because inclusivity is a flag on the range node, not
  a different operator. So `Iterator` has two members and not four, and
  `SYNTAX.MD:90`'s four-element `Iterable` alias would have been wrong.
- **`reverse..` is genuinely separate**, because descending is not the ascending
  range read backwards for every type.
- **The `by` step survives as metadata.** `bridge oper.. [self: Ty&](rhs: Ty&) by 1`
  (`traits/core.az:22`) parses its step and discards it (`Parser.kt:567`). This
  DIP does not change that; it just gives the declaration a spec to live in.

`Iterator` is not the loop protocol. `loop iterable { … }` lowers to
`reset()` then `hasNext()` (`IrGenerator.kt:1003`), which is a separate,
already-working mechanism over an existing object. `Iterator` is what *builds*
the thing to loop over from two endpoints.

`Range<Self>` does not exist in `std/` today - a range is a compiler node
(`Expr.Range`), not a type. §17 asks whether it should become one; until it
does, `Out` is supplied by the `bridge` and the spec is not usable with a
written body.

### 12.9 `Cast`, `CheckedCast`, `BitCast`

Three specs, by the §12.1 rule: they differ in what they promise, not only in
which symbol they spell.

```azora
/** Converts to `To`. Total: it cannot fail. */
@Since("0.1")
spec Cast<To>        { oper as  [self: Self&]: To  }

/** Converts to `To` when the value permits it, and answers null when not. */
@Since("0.1")
spec CheckedCast<To> { oper as? [self: Self&]: To? }

/** Reinterprets the bits as a `To`. Requires equal sizes; checked at lowering. */
@Since("0.1")
spec BitCast<To>     { oper as* [self: Self&]: To  }
```

`CheckedCast` does **not** `require Cast`: a conversion that can fail is
precisely the one with no infallible form. `BitCast` requires neither - a
reinterpretation is not a conversion, and `TYPE_DIP.MD:135` already says a type
states the three "independently".

```azora
impl Cast<Fahrenheit> for Celsius {
    oper as [self: Self&] { return Fahrenheit(self.degrees * 1.8 + 32.0) }
}
```

`c as Fahrenheit` works; `c as* Fahrenheit` is an error naming the missing
impl. A type writes only the ones it has, and the bound for all three is the
alias from §11.5:

```azora
typealias FullCast<To> = [Cast<To>, CheckedCast<To>, BitCast<To>]

func reinterpretable<T>(v: T) where T: FullCast<ULong> { … }
```

**The target moves from the operator to the impl.** `TYPE_DIP.MD:133` has it as
a parameter of the operator, so one declaration serves every target:

```azora
oper as<U> [self: Self&] = Fahrenheit(self.degrees * 1.8 + 32.0)   // today
```

That body has to be correct for every `U` while naming exactly one of them,
which is not checkable, and `oper as<U>` can never be a bound. `Cast<To>` is
both: `where T: Cast<String>` is expressible, and each target gets a body the
compiler can check.

### 12.9.1 `Into` and `From` are kept, and are not casts

An earlier draft retired `Into` / `From` into `Cast`. That was wrong, and the
distinction it missed is the one `CAST_DIP.MD` is built on:

> A **cast** is about representation. A **conversion** is about meaning.

`as` / `as?` / `as*` never take ownership and never allocate. `Into` and `From`
may do both, which is why they stay separate specs a type opts into rather than
operators the compiler can always attempt.

Both were rewritten in `0.0.5` rather than removed:

```azora
spec Into<T> {
    prop into<T>[self: Self&]: T
    use into<T> as "to${T.typeName}"
}

spec From<T> {
    func from(value: T): Self
}
```

- **`Into`** is a `prop`, so no parentheses. Its member keeps the canonical name
  `into`, and `use into<T> as "…"` - a line of its own, so the declaration above
  it stays a plain declaration - gives it the call-site name callers already
  write: `.toString` for `Into<String>`, `.toInt` for `Into<Int>`.
- **`From`** is a receiver-less `func`, which in a spec means **static**. It
  *constructs* rather than consumes, so there is no `self` to convert, and
  `impl From<String> for Username::` supplies it. It carries no `use as`:
  `Username::from(text)` is already the name callers write.

The five spellings and the rules relating them are `CAST_DIP.MD`; this section
exists only to record that the operator DIP does not own them.

### 12.10 `std/cast.az` is deleted

`as`, `as?` and `as*` become the only spelling. The three intrinsics they
duplicate go:

```azora
realm std {                                    // std/cast.az - deleted entirely
    bridge func cast<T, U>(value: U): T
    bridge func dyncast<T, U>(value: U): T?
    bridge func bitcast<T, U>(value: U): T
}
```

The file's own header calls them sugar for the operators  - 
"`cast<T>(x)` ≡ `x as T`", "`dyncast<T>(x)` ≡ `x as? T`",
"`bitcast<T>(x)` ≡ `x as* T`" - so they were never a second mechanism, only
a second spelling of the first.

Removal is clean:

- **No callers.** Nothing in `std/`, `examples/` or `app/` calls `cast`,
  `dyncast` or `bitcast`. The file is dead weight that ships in `AzStdlib`.
- **The compiler side is three lines.** `rewriteCastIntrinsic`
  (`Parser.kt:7338`) matches the callee name and rewrites to `Expr.Cast` with a
  `CastKind`; deleting the function and its call site removes the whole path.
  `Expr.Cast`, `CastKind` and every backend below them are untouched - they are
  what `as` already lowers to.
- **Nothing is lost.** A generic function that needs to convert writes
  `where T: Cast<U>` and `value as U`, which the intrinsic form could not
  express at all.

Two spellings for one operation is the same defect as §12.7's four ways to
stringify, caught earlier and cheaper to fix.

## 13. Diagnostics

The messages are most of the value of this proposal, so they are specified.

```
line 42: cannot compare two 'Vec2' values - Vec2 does not implement PartialEqual
  add 'impl [Equal] for Vec2' to compare it field by field, or write it out:
    impl PartialEqual for Vec2 { oper== [self: Self&](rhs: Self&) { … } }

line 61: 'Vec2' implements PartialOrder but not PartialEqual
  '<' '<=' '>' '>=' come from '<=>'; '==' does not, because a type may
  compare equal faster than it compares in order
  PartialOrder requires PartialEqual - add 'impl PartialEqual for Vec2'

line 88: cannot derive 'Order' for 'Sample': field 'value: Double' is not Order
  Double is PartialOrder - NaN does not relate to anything
  use 'impl [PartialOrder] for Sample', or store the value as a Long

line 104: sort<T> requires 'T: Order', but T = Double is only PartialOrder
  NaN does not relate to anything, so there is no order to sort by
  try: sortBy(values) { a, b -> Double::totalCompare(a, b) }

line 130: 'a <=> b <=> c' - '<=>' does not chain
  use '(a <=> b).thenBy { b <=> c }'

line 152: 'oper==' must be declared inside the impl of the spec that governs it
  write:  impl PartialEqual for Vec2 { oper== [self: Self&](rhs: Self&) { … } }

line 158: 'oper!=' cannot be declared - no spec has it
  '!=' is '!(a == b)'; declare 'oper==' in 'impl PartialEqual for Vec2'

line 164: 'impl Order for Vec2' declares '<=>' returning PartialCompare
  the spec fixes the result: Order gives Compare, PartialOrder gives
  PartialCompare - either drop the return type or implement PartialOrder

line 177: 'Key' implements PartialEqual but has no 'hash', and is a HashMap key
  a key whose equality and hash disagree is inserted and never found
  add 'prop hash[self: Key&]: ULong', or derive both with 'impl [Equal] for Key'

line 203: 'Matrix' has no '%' - its 'impl Arithmetic' does not declare 'oper%'
  an operator family implements the part it wants; add 'oper%' to that impl

line 211: 'Matrix' has 'oper+=' but no '+', and Matrix is not Clone
  '+' from '+=' needs a copy to add into
  add 'impl [Clone] for Matrix', or declare 'oper+' directly

line 224: sum<T> requires 'T: Arithmetic[+]', which 'Token' does not have
  Token implements Arithmetic, but its impl declares only 'oper*'

line 240: two impls of 'Arithmetic' for 'Vec2' differ only in Out
  'Arithmetic<Vec2, Vec2>' and 'Arithmetic<Vec2, Vec2Long>' both match 'a + b'
  a family's result type is fixed per Rhs - merge them or change the Rhs

line 258: 'cast' no longer exists - write 'value as T'
  'dyncast' is 'value as? T'; 'bitcast' is 'value as* T'

line 190: 'Order' requires 'Equal', which 'Version' does not state
  a total order and its equality have to agree
```

---

## 14. Implementation plan

**Phase 1 - specs can require operators.** `parseSpec` (`Parser.kt:3961`)
accepts `oper<SYM> [self: Self&](operands): Ret` requirement lines beside `func`
and `prop`, and `checkSpecRequirements` (`SymbolCollector.kt:652`) matches them
against a type's registered `oper` members. Nothing else in this DIP can be
written until this exists.

This is the **only** half of the impl/spec pairing that is missing. The other
half already works: `impl Spec for Type { oper<SYM> … }` parses today
(`Parser.kt:2948`), trait generic arguments are read and kept
(`Parser.kt:2739`, `traitArgs`), bodyless grouped impls queue conformances
(`:2764`), and an impl member already inherits its return type from the spec
(`SymbolCollector.kt:630`) - which is what §5.3.1 rests on. So the whole surface
in §5.3 costs one `when` branch in `parseSpec` plus the matching check.

**Phase 2 - the `<=>` token.** Lexer, `TokenType.SPACESHIP`,
`parseOperatorName`, `parseSpaceship` at the precedence in §5.2, and
non-associativity. The return-type check is a spec-conformance check, not a
parser check, and lands with Phase 5.

**Phase 3 - the types.** `Compare`, `PartialCompare`, their members
(`reversed`, `then`, `thenBy`), and the `Compare` → `PartialCompare` widening.

**Phase 4 - rewriting.** §5.4 and §5.5, in *one* place. The operator dispatch
logic is currently duplicated between `TypeResolver.kt:2468` and
`IrGenerator.kt:1437`, which is how the two would drift; the rewrite must be a
shared routine both call, and unifying the existing duplication is part of this
phase.

**Phase 5 - the specs.** `PartialEqual`, `Equal`, `PartialOrder`, `Order`
in `traits.az`, generic in `Rhs` with a `= Self` default; the `where T: Spec<Args>`
bound form (§11.2), enforcement of the already-parsed `<T: Spec>` list form, and
§6.3's widening rule.

**Phase 6 - derivation.** Extend `SymbolCollector`'s field-wise pass to
`impl [Equal]`, `impl [Order]` and `impl [Hash]`, over packs, enums and variant
enums, including the `Equal`→`Hash` pairing of §7.3.

**Phase 7 - the errors.** §8 (`==` with no conformance), behind
`--warn-implicit-equality` for one release; and §6.5 (a comparison operator
declared outside its spec's impl), which must land *after* Phase 8 moves the
standard library's own free operators, or the stdlib stops building.

**Phase 8 - the standard library.** §10, in order: primitives, then containers
(which moves fourteen operators into spec impls and deletes seven), then
algorithms (which gains the bounds).

Phases 1–8 deliver comparison end to end. The remaining three generalise it to
the rest of §12, and none of them blocks the others:

**Phase 9 - unary dispatch.** The §2.6 defect: add `BANG` to
`parseOperatorName`, give `Expr.Unary` the same `lookupOperator` path the binary
case has in both the resolver (`TypeResolver.kt:1169`) and the lowerer
(`IrGenerator.kt:1426`), and stop rejecting a non-numeric operand before the
lookup runs. Independently valuable - `-v` on a `Vec2` is a compile error today
whatever the type says.

**Phase 10 - the remaining families.** `Arithmetic`, `Bitwise`, `Logical`,
`Indexable`, `Deref`, `Iterator`, `Hash`, `Display`, `Cast`, `CheckedCast`
and `BitCast` in `traits.az`
(§12.1), plus the three mechanisms they need: optional members on an operator
family (§12.2), member-selecting bounds `T: Arithmetic[+, /]` (§12.3), and the
bidirectional compound-assignment rewrite (§12.4). Optional members are the
substantive change here - `checkSpecRequirements` (`SymbolCollector.kt:652`)
currently demands every member, and operator families need it to record the
subset instead.

**Phase 10a - delete `std/cast.az`.** §12.10. Independent of everything else:
remove the file and `rewriteCastIntrinsic` (`Parser.kt:7338`) with its call
site. No callers exist, so this can land first if convenient.

**Phase 11 - close the free form.** Once every operator has a spec and `std/`
has moved, a free `oper<SYM>` declaration becomes an error everywhere rather
than only for the comparison family. This is the phase that makes §12's opening
sentence true, and it must be last.

Phases 1–4 are the comparison language; 5–6 the model; 7–8 the payoff; 9–11 the
generalisation. Each is independently landable and each leaves the tree
building - with the two ordering constraints noted in Phases 7 and 11.

---

## 15. Cleanups this proposal takes on

Worth doing whether or not the rest lands:

- unify the operator dispatch duplicated between `TypeResolver.kt:2328/2468`
  and `IrGenerator.kt:2224/1437` - two copies of the same lookup table and the
  same resolution order, in two files, is the bug that has not happened yet;
- delete the legacy named-method operator path (`operatorMethodName` → `plus`,
  `minus`, `times`, `div`, `mod`, `equals`, `TypeResolver.kt:2317` and
  `IrGenerator.kt:2213`). It is a second way to overload arithmetic that
  predates `oper`, applies only when both operand types are identical, and is
  not documented in any DIP. Nothing in `std/` uses it;
- fix `sortBy`'s key type (`algorithm.az:94`), which is `(T) -> T` and cannot
  express a key of another type;
- give `binarySearch` (`search.az`) the `Order` bound it silently assumes;
- delete `std/cast.az` and `rewriteCastIntrinsic` (§12.10). Three `bridge func`s
  that the file's own header documents as sugar for `as` / `as?` / `as*`, with
  no callers anywhere in the tree, shipped inside `AzStdlib`.

---

## 16. What this does not propose

- **A weak ordering category.** §3 explains the choice. If substitutability ever
  needs to be a type rather than a promise, adding a third enum is backwards
  compatible; removing one is not.
- **Collation.** `String`'s `<` is code-point order, permanently. Locale-aware
  comparison is a function with a locale parameter, and it does not belong on an
  operator.
- **Reversed operand candidates.** §3.
- **`oper<=>` for `Pointer<T>`.** Pointers compare for identity today
  (`TypeResolver.kt:2514`) and ordering them is an allocator's question; it can
  come with the allocator DIP if it is wanted.
- **Deriving `Equal` silently, as `Clone` is derived.** §7.2.
- **A richer hashing story.** Seeded hashers, DoS
  resistance, `#` on a `variant enum` - and this proposal only ties its
  derivation to `Equal`'s.
- **Contracts on the operators.** `oper<=>` transitivity could be an `out`
  contract checked in debug builds. Interesting, and separable.
- **Associated types.** `Out` is a type parameter with a default, not an
  associated type. Two impls of one family differing only in `Out` are a
  conflict, reported at the second - which recovers the associated-type
  guarantee with what `SymbolTable` already records. Real associated types would
  tidy `Arithmetic`, `Indexable` and `Deref`, but they are a type-system feature
  of their own and should not arrive as a side effect of operators.
- **Optional members on ordinary specs.** §12.2 applies to operator families
  only. `Clone`, `Send`, `Order` and the rest keep the all-members rule; a
  capability that is a promise cannot be half-kept, and making every spec
  partial would turn `requires` into a suggestion.
- **Overloadable `&&`, `||`, `?:` and `=`.** Short-circuiting cannot be
  expressed by a member call that takes both operands evaluated, and assignment
  is not an operation on a value. C++ allows the first and nobody thanks it.
- **User-defined operator symbols.** The set in §12.1 is closed. A language
  where `<|>` can be introduced needs precedence declarations, and this DIP does
  not open that door.
- **`oper[]` returning a place.** `a[i] += 1` needs an lvalue-producing index or
  a read-modify-write rewrite; `Indexable` gives `[]` and `[]=` as separate
  members (§12.2) and leaves the compound case to whoever implements §12.4
  against them.

---

## 17. Open questions

1. **Should `String` be `Order` or only `Equal`?** Code-point order is a
   total order and is what every language's `<` does, but it puts `"Z" < "a"`
   and orders non-ASCII by encoding. The alternative is refusing `<` on strings
   entirely and forcing a named comparison. Every language that tried the
   second reverted. Proposed: `Order`, documented as code-point order.
2. **Should `impl [Order] for T` derive `==` when the author wrote
   `oper<=>` but not `oper==`?** §7.1 says both are generated; §5.6 says an
   explicit `<=>` derives nothing. The half-and-half case - explicit `<=>`,
   derived `==` - is currently an error, and it might reasonably generate a
   field-wise `==` instead. Erring toward the error, because a type that wrote
   its own ordering usually has a reason equality differs.
3. **Does `<=>` need an operand-keyed form in the first release?**
   `oper<=> [self: Version&](rhs: String&)` follows from the existing overload
   suffix mechanism (`Parser.kt:2151`) and costs nothing, but mixed-type
   ordering is where C++'s complexity came from. Shipping it is easy; shipping
   it and then constraining it is not.
4. **Should `PartialCompare.Unordered` be reachable from `<=>` on a type the
   author declared `Order` for?** It cannot be, by return type - but a
   generic function bounded on `PartialOrder` and instantiated at an
   `Order` type will have a `when` whose `.Unordered` arm is dead code.
   Whether that arm should warn is a question about exhaustiveness checking
   through the widening, and it belongs with whoever implements §6.3.
5. **Should a named function be passable as a comparator?** Every `sortBy` call
   in §9 and §10.2 wraps its comparator in a lambda, because there is no
   function-reference expression - `Double::totalCompare` is callable but not
   nameable as a value (no such node exists in `Ast.kt`). The wrapping lambda is
   free at runtime and noisy in source. Not this DIP's problem to solve, but
   this DIP is what makes it visible.
6. **Is `then` worth having beside `thenBy`?** The eager form is a footgun for
   an expensive second comparison and a convenience for a cheap one. Rust ships
   both (`then`, `then_with`) and the lazy one is what people should reach for.
   Possibly ship only `thenBy`.
7. **Should a fallible conversion exist?** `Into` and `From` are total, so a
   conversion that can fail has nowhere to go - a parse, a narrowing, a lookup.
   `TryInto` is the obvious answer and it needs the error model settled first:
   `?!` and `Result` are two different answers today. `CAST_DIP.MD` §8 records
   the same question.
8. **Should `Range<T>` become a real type?** §12.8's `Iterator` spec names
   `Range<Self>` as its output, but a range is a compiler node (`Expr.Range`)
   and not a `std` type today. Until it is one, `Iterator` can only be satisfied
   by a `bridge` impl, which means user types cannot define `..`. Making
   `Range<T>` a pack is the fix and is a small DIP of its own.
9. **Does `Display` need a format specifier?** `"$value"` calls `oper$` with no
   arguments, so there is no width, precision or alignment. Every language
   eventually grows this (`{:>8.2}`), and retrofitting it means either a second
   member on `Display` or a companion spec. Worth deciding the shape before
   `Display` ships, even if the feature does not.
10. **Should `Neg` and `Not` really be separate specs from their binary
    namesakes?** `oper-` unary and `oper-` binary are told apart by operand count
    (§12.5), so `Neg` and `Sub` are different specs owning the same symbol. That
    is correct but it is the one place in §12.1 where the symbol→spec mapping is
    not one-to-one, and it will surprise someone reading a diagnostic.