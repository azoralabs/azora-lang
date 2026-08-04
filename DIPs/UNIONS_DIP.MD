# Azora Unions — DIP Implementation Profile

`union` declares a **C-style untagged union**: a fixed block of storage that
several members share. For a union that *does* record which member is live, see
the Variants DIP — that is the difference between the two, and the reason a
`union` is only as safe as the invariant its author keeps around it.

## Grammar

```
@[DECORATORS] VISIBILITY union NAME <...GENERICS>? {
    FIELD_VISIBILITY (var | val | fin | let) NAME : Type
    …
}
```

`union` is **contextual**, not reserved: it only opens a declaration when a type
name and then a body (or type parameters) follow it. Everywhere else the word
means what it says, so `Set.union(other)` and `fin union = …` keep working.

---

## 1. Declaration

```azora
union Value {
    var i: Int
    var d: Double
    var b: Bool
}
```

- Every member starts at offset 0.
- The union is as wide as its widest member (`Double` above, so 8 bytes).
- A union must declare at least one member.
- A union cannot generate its members from a variadic template.

## 2. Construction

A union is built by naming **exactly one** member:

```azora
var v = Value(i: 42)
var d = Value(d: 1.5)
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
v.i = 7
std::println(v.i)
```

Because the members share one slot, writing one and reading another
reinterprets the same storage:

```azora
union Flag {
    var raw: Int
    var on: Bool
}

var f = Flag(on: true)
std::println(f.raw)    // 1 — the same byte, read as an Int
```

Nothing tracks which member is live. Reading a member other than the one last
written is defined by the target's representation, not by the language.

## 4. Mutability

A union member follows the same two-axis binding model as a pack field (see the
Base Syntax DIP). The binding the union is held in decides whether its storage
can be written at all:

```azora
fin v = Value(i: 1)
v.i = 2               // error: cannot assign to member 'i' through 'v'
```

## 5. Backends

| Backend | Representation |
|---------|----------------|
| LLVM | `%struct.U = type { <widest member> }`; every member addresses slot 0 through a `bitcast`, so the byte-level view is exact |
| Wasm | one 4-byte slot; every member is at offset 0 |
| Interpreter | one shared value slot |

The interpreter holds values rather than bytes, so reading a member other than
the one last written yields that **value**, not a reinterpretation of its bits.
Programs that depend on the byte-level view are only meaningful on a real
target; the LLVM backend is the reference for that behaviour.

## 6. Relationship to `variant`

```azora
variant Shape {          // tagged: the payload says which case is live
    Circle(Int)
    Rect(Int, Int)
}

union Bits {             // untagged: nothing says which member is live
    var asInt: Int
    var asDouble: Double
}
```

Reach for `variant` by default. `union` exists for the cases where the layout is
the point: reinterpreting bytes, matching a foreign (C) struct, or packing a
value whose discriminant is already carried somewhere else.
