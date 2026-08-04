# Azora Unions — DIP Implementation Profile

`unsafe union` declares a **C-style untagged union**: a fixed block of storage
that several members share. Nothing records which member is live, so no check can
establish that a read is meaningful — which is why both the declaration and every
use ask for `unsafe`. For a union that *does* record its live case, see the
Variants DIP.

## Grammar

```
@[DECORATORS] VISIBILITY unsafe union NAME <...GENERICS>? {
    NAME : Type
    …
}
```

- `unsafe` is **required** on the declaration, and every use must sit inside an
  `unsafe { … }` block (or an `unsafe func`).
- A member is written `name: Type`, with **no binding keyword**: the members
  share one slot, so `var`/`val`/`let`/`fin` could not mean anything, and writing
  one is an error.
- `union` is **contextual**, not reserved: it only opens a declaration when a type
  name and then a body (or type parameters) follow it. Everywhere else the word
  means what it says, so `Set.union(other)` and `fin union = …` keep working.

---

## 1. Declaration

```azora
unsafe union Value {
    i: Int
    d: Double
    b: Bool
}
```

- Every member starts at offset 0.
- The union is as wide as its widest member (`Double` above, so 8 bytes).
- A union must declare at least one member.
- A union cannot generate its members from a variadic template.

## 2. Construction

A union is built by naming **exactly one** member:

```azora
unsafe {
    var v = Value(i: 42)
    var d = Value(d: 1.5)
}
```

Naming more than one would be a contradiction (only one member can be live), and
naming none would leave the storage undefined — both are compile errors:

```azora
Value(i: 1, d: 2.0)   // error: union 'Value' is built from exactly one member
Value()               // error: union 'Value' is built from exactly one member
```

A single positional argument initializes the first member, matching how C
initializes a union from a bare initializer:

```azora
var v = Value(42)     // same as Value(i: 42)
```

## 3. Reading and writing

Members read and write with ordinary field syntax:

```azora
unsafe {
    v.i = 7
    std::println(v.i)
}
```

Because the members share one slot, writing one and reading another
reinterprets the same storage:

```azora
unsafe union Flag {
    raw: Int
    on: Bool
}

unsafe {
    var f = Flag(on: true)
    std::println(f.raw)    // 1 — the same byte, read as an Int
}
```

Nothing tracks which member is live. Reading a member other than the one last
written is defined by the target's representation, not by the language.

## 4. Mutability

A union member follows the same two-axis binding model as a pack field (see the
Base Syntax DIP). The binding the union is held in decides whether its storage
can be written at all:

```azora
unsafe {
    fin v = Value(i: 1)
    v.i = 2           // error: cannot assign to member 'i' through 'v'
}
```

A member itself is always writable — every member addresses the same slot, so
per-member mutability could not mean anything. Whether the *binding* permits the
write is the separate question the value axis answers.

## 5. Backends

| Backend | Representation |
|---------|----------------|
| LLVM | `%struct.U = type { <widest member> }`; every member addresses slot 0 through a `bitcast`, so the byte-level view is exact |
| Wasm | one 4-byte slot; every member is at offset 0 |
| Interpreter | one shared value slot (no memory model *yet* — see below) |

The interpreter does not model bytes **yet**: it holds values, so reading a
member other than the one last written currently yields that value rather than a
reinterpretation of its bits. That is a gap in the current interpreter, not a
property of the language — the self-hosted compiler will give the interpreter a
real memory model, and §3 will then hold on every backend. Until then, LLVM is
the reference for byte-level behaviour.

## 6. Relationship to `variant`

```azora
variant enum Shape {         // tagged: the tag says which case is live
    Circle(Int)
    Rect(Int, Int)
}

unsafe union Bits {          // untagged: nothing says which member is live
    asInt: Int
    asDouble: Double
}
```

Reach for `variant enum` by default. `unsafe union` exists for the cases where
the layout is the point: reinterpreting bytes, matching a foreign (C) struct, or
packing a value whose discriminant is already carried somewhere else. The
`unsafe` is the honest part — it marks exactly the obligation the compiler cannot
take on for you.
