# Azora Variadics - DIP Implementation Profile

> Status: **three separate features that share one spelling.** Variadic value
> parameters work. Variadic generics work and carry the standard library's
> `Tuple`. Variadic receivers parse and mean nothing yet. §7 is the defect list,
> and two of its entries are things that compile and then do the wrong thing.

`...` means "however many of these there are". It appears in three positions,
and they are genuinely different features that happen to agree on a sigil:

| written              | is                       | expands over           |
|----------------------|--------------------------|------------------------|
| `func f(...xs: Int)` | a variadic **parameter** | values, at the call    |
| `pack Tuple<...T>`   | a variadic **generic**   | types, at compile time |
| `[...Int]() -> Int`  | a variadic **receiver**  | contextual receivers   |

The first is a runtime array. The second is a compile-time type pack that
disappears during monomorphization. The third is a type the parser accepts and
nothing else honours.

**The prefix form is the only form.** `...T` is written before the name, never
after it, and the postfix spelling is refused by name rather than by a syntax
error:

```
Variadic type parameters use the prefix form '<...T>', not '<T...>'
Variadic type arguments use the prefix form '<...T>', not '<T...>'
```

---

## 1. Variadic parameters

```azora
func sum(...xs: Int): Int {
    var total = 0
    for x in xs { total = total + x }
    return total
}

sum(1, 2, 3)        // 6
sum()               // 0 - an empty pack is a pack
```

Inside the body the parameter **is an array**: `xs.length` is its arity, and
`for x in xs` iterates it. There is no separate pack type to learn, and nothing
to unpack before use - which is the whole reason the runtime form is an array
rather than a compile-time list.

A variadic parameter composes with fixed ones, which bind first:

```azora
func log(level: Int, ...parts: String) { … }
log(2, "a", "b")    // level = 2, parts = ["a", "b"]
```

It works on methods as well as free functions, and `func main(...args: String)`
is accepted.

- ⬜ **`main`'s arguments are never populated.** The signature is accepted and
  `args.length` is `0` under every backend, so the program cannot read its
  command line

### 1.1 The call-site spread

```azora
fin xs = @arr[1, 2, 3]
sum(...xs)          // intended: 6
```

`...expr` at a call site splats an array into individual arguments. The parser
builds an `Expr.Spread` for it, and the interpreter then fails:

```
Spread should be handled by evalCall, not evaluated directly
```

- ⬜ **The spread is parsed and unimplemented.** The evaluator's own message
  says a spread must be intercepted by the call path, and the call path does not
  intercept it. It is not a diagnostic a program can act on either - it names an
  internal invariant rather than anything the author wrote

---

## 2. Variadic generics

A variadic type parameter is a **type pack**: zero or more types, bound as one
name, expanded at compile time.

```azora
pack Tuple<...T> where (...T).length >= 2 {
    inline for Ty in ...T with index {
        inline "$index: $Ty"
    }
}
```

`inline for Ty in ...T with index { … }` is the field generator: it runs once per
type in the pack, with `Ty` bound to that type and `$index` to its position, and
each iteration contributes a field. The body is a **string fragment** rather than
a field declaration, because the field's *name* is being generated too - which is
what `$index` is for.

It works on a program's own packs exactly as it does on the standard library's:

```azora
pack Tup<...T> {
    inline for Ty in ...T with index {
        inline "f$index: $Ty"
    }
}

fin t = Tup<Int, Int>(1, 2)
t.f0 + t.f1                     // 3
```

### 2.1 Functions, type properties, impls

The same pack binds on every declaration that takes type parameters:

```azora
func count<...T>(): Int { … }

deepinline prop promote<...T>: Type where T.length >= 2 && T is Number {
    var result: Type = T.0
    for candidate: Type in T[1...] {
        if candidate::rank > result::rank { result = candidate }
    }
    return result
}

impl<...T> PrettyPrint for Tuple { … }
```

`std::promote` is the standard library's worked example, and it exercises the
whole query surface below.

---

## 3. Asking a pack about itself

| written         | is                       | where it works   |
|-----------------|--------------------------|------------------|
| `T.length`      | the number of types      | a type property  |
| `(...T).length` | the number of types      | a pack's `where` |
| `T.0`           | the type at an index     | a type property  |
| `T[1...]`       | every type from an index | a type property  |

`T[1...]` is a **type-pack slice**, and it is the only slicing form: the start
index is written, the end is not, and `...` closes it. It is what lets a fold
over a pack be written as an ordinary loop with an accumulator - `promote` above
seeds `result` from `T.0` and folds `T[1...]` into it.

- ⬜ **Two spellings for one question.** A pack's `where` writes
  `(...T).length`, a type property's writes `T.length`, and both mean the number
  of types in `T`. One of them should go
- ⬜ **The pack is not a value expression.** `(...T).length` inside a function
  body reports *undefined variable 'T'*, though the parser has a rule that reads
  `...T` in expression position as naming the pack. So the arity a program can
  branch on at compile time is not one it can read at run time

---

## 4. `where` on a pack is accepted and not enforced

```azora
pack P<...T> where (...T).length >= 2 {
    inline for Ty in ...T with index { inline "f$index: $Ty" }
}

fin p = P<Int>(1)       // compiles, runs
```

The constraint parses, is recorded, and is never checked: a one-element `P`
builds and its field reads. The same `where` on a **type property** does hold,
so the machinery exists - it is the pack declaration that skips it.

- ⬜ **This is the worst defect in the document.** A constraint that is written,
  read by a maintainer, and silently ignored is worse than no constraint: it
  states a guarantee the compiler does not provide, and `std::Tuple` is declared
  with one (`(...T).length >= 2`), so the standard library is currently making a
  promise it cannot keep

---

## 5. Variadic receivers

```azora
func apply(f: [...Int]() -> Int): Int { … }
```

A callable type's **receiver** list takes `...T` the same way its parameter list
does. Only a named type may be variadic there:

```
only a named type can be variadic in a callable type
```

### 5.1 All three at once

The simple form fixes the element type, which hides what the feature is for. The
general one is a callable that is **itself generic over a pack**, and spends that
pack in both of its lists:

```azora
func apply(f: <...T>[...T](...e: T) -> T)
```

Read left to right, that is every variadic position the language has, in one
signature:

| fragment    | is                                                              |
|-------------|-----------------------------------------------------------------|
| `<...T>`    | the callable's own type pack - `apply` does not fix its arity   |
| `[...T]`    | one contextual receiver per type in the pack                    |
| `(...e: T)` | a variadic value parameter over the pack's element type         |
| `-> T`      | the result                                                      |

The point of writing it this way is that the three lists are **linked by one
pack**: the number of receivers, the number of arguments, and the element type
are one fact stated once, rather than three that a caller has to keep in step.
`[Int, Int](Int, Int) -> Int` says the same thing for one arity and says nothing
about any other.

`T` is doing two jobs here, and they are worth separating: in `[...T]` the pack
is expanded - one receiver per member - while in `(...e: T)` the pack's *element
type* names what each argument must be. A pack that is expanded in one position
and named in another is the whole difficulty of the feature, and nothing in the
language currently writes it down.

### 5.2 Why `()` stays mandatory

A callable type writes its parameter list even when it is empty - `() -> T`,
`[Ctx]() -> T` - and the tempting shortcut is to let a missing `()` mean a
*property* instead: `[Ctx] -> T` read without parentheses, the way a `prop` is.

It does not work.

**A prop value cannot name itself.** A property is read without parentheses,
which works for a *member* because the receiver disambiguates - `v.area` reads
it, and no expression means "the property itself". A standalone binding has no
receiver:

```azora
fin area: [Vec2&] -> Int = …
area            // read it, or name it?
other(area)     // pass it on - no longer expressible
```

It would be the only value in the language that cannot be mentioned, only
triggered. And it adds nothing: `[Vec2&]() -> Int` already expresses the
computation, and `prop area[self: Vec2&]: Int` already expresses the call-free
read. `prop` is a **declaration** modifier, and what makes it work is the
receiver it is declared on - a type spelling has no receiver to lean on.

So: a callable type always writes `()`, and a program that wants a property
writes `prop` on the declaration.

A missing `()` is therefore an **error**, not a second meaning:

```
[Int, Int] -> Int       // rejected - write '[Int, Int]() -> Int'
```

This is a change to what parses. `[A, B] -> R` was accepted as a zero-parameter
function, so the empty list was optional in practice; requiring it makes the two
lists read the same way - a callable type states its receivers and its
parameters, and neither is inferred from being absent.

### 5.3 None of it means anything yet

Nothing supplies a variable number of contextual receivers: `with` takes a fixed
list, and the receiver-call forms (`2.scale(7)`, `[2, 3].add()`) each supply
exactly as many receivers as the type declares. So the type parses and no call
can satisfy it.

- ⬜ **Variadic receivers are a parsed type with no semantics.** No call form
  produces one, no lowering consumes one, and nothing rejects the declaration -
  so a signature can promise something the language cannot express. Either give
  `with` and the receiver call a variadic form, or refuse the type
- ⬜ **A callable type carrying its own `<...T>` is unmeasured.** §5.1 is the
  shape the feature wants; whether the parser takes type parameters on a callable
  *type* (as opposed to on the declaration around it) was not tested, and the
  answer decides whether §5.1 is a gap or a defect
- ⬜ **Expanding a pack and naming its element type are not distinguished.**
  `[...T]` wants the members and `(...e: T)` wants the element type, and both are
  spelled with the same `T`

---

## 6. Variadic lambdas

```azora
fin f = <...T>{ it.length }
f(1, 2, 3)          // 3
```

A variadic lambda packs **every** argument into `it`, typed as an array. This is
the one place `it` is not "the single parameter the lambda did not name" - it is
the whole argument list, which is why the variadic form declares itself in the
angle brackets rather than being inferred.

- ⬜ `it` means two different things depending on a marker written before the
  brace. It is defensible - a variadic lambda has no single parameter for `it` to
  be - but it is unstated anywhere a reader would look

---

## 7. Defects

Everything here was measured, not predicted.

- ⬜ **The spread does not run** (§1.1)
- ⬜ **A pack's `where` is not enforced** (§4), including `std::Tuple`'s
- ⬜ **`std::Tuple` cannot be constructed.** `std::Tuple<Int, Int>(1, 2)`
  reports *undefined function 'std__Tuple'*, so the standard library's flagship
  variadic pack is unreachable by its own name. A program's own variadic pack
  constructs correctly, which places the fault in how the specialized
  constructor is registered rather than in variadic packs themselves
- ⬜ **Two variadic parameters are accepted.** `func f(...a: Int, ...b: Int)`
  compiles. There is no arity that could fill both unambiguously
- ⬜ **A variadic parameter need not be last.** `func f(...a: Int, b: Int)`
  compiles. Every argument would have to be assigned to `a` or `b` by a rule
  that does not exist
- ⬜ **`main`'s variadic arguments are always empty** (§1)
- ⬜ **The pack has no run-time arity** (§3)
- ⬜ **Variadic receivers mean nothing** (§5)

The last four are all the same shape: a form the parser accepts because the
grammar allows it, with no pass downstream that either implements or refuses it.

---

## 8. What is not specified

- ⬜ **No pack-to-pack forwarding.** A function taking `...T` cannot pass its
  pack to another function taking `...U` - there is no spelling for "these,
  again" at a call
- ⬜ **No pack zipping or mapping.** `inline for` walks one pack; two packs of
  equal length cannot be walked together, which is what a `Zip<...A, ...B>`
  would need
- ⬜ **No constraint on the pack's members** beyond what a `where` can say about
  the whole of it. `where T is Number` is written on `promote` and holds; there
  is no per-member spelling
- ⬜ **Nothing states the empty-pack rule.** `sum()` works, `Tuple<>` is
  presumably not intended to, and only one of those is written down
- ⬜ **No interaction with the capture list.** A lambda's bracket list takes
  receivers before the `;` and captures after it; whether either half can be
  variadic is unstated. §5.1's `[...T]` is the receiver half of exactly that
  question, and a variadic *capture* - "everything in the pack, by reference" -
  has no spelling at all

---

## 9. Rules

These hold today.

1. `...` is written as a prefix, on the parameter name or the type parameter.
   The postfix form is refused by name.
2. A variadic parameter is an array inside the body: `xs.length` and
   `for x in xs`.
3. Fixed parameters bind before the variadic one.
4. A variadic type parameter is a compile-time type pack, expanded by
   `inline for Ty in ...T with index { … }`, whose body generates the member.
5. A type property asks a pack `T.length`, `T.0` and `T[1...]`; a pack's `where`
   asks `(...T).length`.
6. A variadic lambda `<...T>{ … }` binds every argument to `it` as an array.
7. Everything variadic in the type system is gone after monomorphization: a
   specialization is an ordinary pack, function or impl with a fixed arity.
8. A callable type always writes its parameter list, empty or not. `[Ctx] -> T`
   is rejected; `[Ctx]() -> T` is the zero-parameter function, and a program that
   wants a property writes `prop` on the declaration (§5.2).

---

## 10. Related

- `TYPE_DIP.MD` - type parameters, and the rest of the type language
- `CTCE_DIP.MD` - `inline for`, and the evaluator the pack queries fold in
- `GENERATORS_DIP.MD` - `inline for` in its other role
- `LAMBDA_CONTEXT_CAPTURE_DIP.MD` §2 - contextual receivers, which §5 would
  extend
- `PACK_IMPL_DIP.MD` - `pack`, `impl`, and the field list a variadic body
  generates
