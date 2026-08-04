# Azora Ownership & Borrowing — DIP Implementation Profile

Values are owned by their binding; **borrows** let functions read or mutate a
value without taking ownership. Borrows are postfix sigils on the type.

## Grammar

```
BORROW_TYPE  ::= Type &        // immutable borrow (read-only view)
               | Type !        // mutable borrow (writes propagate back)
OUT_PARAM    ::= out NAME : Type          // write-only output parameter
RECEIVER     ::= { self& -> … }           // immutable method receiver
               | { self! -> … }           // mutable method receiver
CALL_BORROW  ::= f(expr&)       // pass an immutable borrow
               | f(expr!)       // pass a mutable borrow
DECL_BORROW  ::= (fin | var) NAME : Type& = owner
               | (fin | var) NAME : Type! = owner
```

All borrows are the postfix `&` / `!` sigils. Shared/weak reference kinds will return via
`std.memory`.

---

## 1. Borrow kinds

| Form | Meaning |
|------|---------|
| `T&` | immutable borrow — read, cannot mutate |
| `T!` | mutable borrow — read + write, writes visible to the owner |

## 2. Borrow parameters

```azora
func read(x: Int&)  { return x }        // immutable — cannot assign x
func bump(x: Int!)  { x = x + 1 }        // mutable — propagates back
func produce(out r: Int) { r = 42 }      // write-only output
```

Call sites (borrow markers are optional; matched by the parameter type):

```azora
var v = 10
bump(v)          // implicit borrow
bump(v!)         // explicit mutable borrow marker
read(v&)         // explicit immutable borrow marker
```

## 3. Method receivers

The receiver `self` carries a borrow in the block header:

```azora
impl Counter {
    func get(): Int { self& -> return self.value }           // immutable
    func bump()     { self! -> self.value = self.value + 1 }   // mutable
}
```

Mutating a field through `self&` is a compile error:

```
cannot mutate 'value' through an immutable 'self&' receiver (use 'self!')
```

## 4. Borrow bindings

```azora
var owned: Buffer = Buffer(1)
fin borrowed: Buffer& = owned     // immutable view
var exclusive: Buffer! = owned    // mutable view
```

## 5. Interaction with `shield`

A `shield var` field is externally read-only but internally mutable — a caller
holding only a `T&` view cannot write it; an `impl`/`self!` in the declaring file
can.

## 6. Current status

Borrows are represented in the type system and enforce receiver mutability
(`self&` cannot mutate). Call-site borrow markers (`x&` / `x!`) parse and annotate
intent but are currently **erased** — full call-site borrow checking and the
`shared`/`weak` reference kinds are planned (`std.memory`).
