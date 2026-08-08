# Azora Dynamic Dispatch - DIP Implementation Profile

> Status: **implemented** in the interpreter and LLVM; **absent** in
> WebAssembly. The gap that matters most is §6.1: a collection of spec-typed
> values cannot be built, which is the main thing dispatch is for.

A value whose type is a **spec** carries its concrete type with it, and a call
on it picks the implementation at run time.

```azora
spec Shape {
    func area[self: Self&](): Int
}

pack Square { fin side: Int = 0 }
pack Circle { fin r: Int = 0 }

impl Shape for Square { func area[self: Self&](): Int { return self.side * self.side } }
impl Shape for Circle { func area[self: Self&](): Int { return 3 * self.r * self.r } }

func describe(s: Shape): Int {
    return s.area()          // dispatched on what `s` actually is
}
```

---

## 1. There is no keyword

Using a spec as a type is what selects dynamic dispatch. There is no `dyn`,
no `impl Trait`, and no boxing syntax:

```azora
fin s: Shape = Square(4)     // dispatched
fin q: Square = Square(4)    // direct
```

The distinction is already in the type, so a keyword would only repeat it.
Writing `dyn Shape` is an error - `dyn` is not a word the language knows.

Four positions accept a spec type, all implemented:

| position       | example                        |
|----------------|--------------------------------|
| a binding      | `fin s: Shape = Square(4)`     |
| a parameter    | `func describe(s: Shape): Int` |
| a return type  | `func pick(n: Int): Shape`     |
| a pack's field | `pack Holder { fin s: Shape }` |

---

## 2. The representation

A spec has no struct of its own. A spec-typed value is a heap-allocated **fat
pointer**:

```
{ i32 typeId, i8* data }
```

`typeId` is a stable non-zero integer assigned per concrete implementer;
`data` points at the value. Sixteen bytes, allocated when a concrete value is
first used where a spec is expected.

### 2.1 Boxing

Passing a `Square` where a `Shape` is expected boxes it: allocate, store the
type id, store the pointer. Handing a spec-typed value on to another spec-typed
slot passes the existing box through rather than re-boxing.

Boxing is therefore an **allocation per upcast**, and nothing pools or reuses
them.

- ⬜ The box is never freed. Every upcast leaks 16 bytes plus whatever the
  allocator rounds it to

### 2.2 The dispatch table

Each spec gets one `IrSpecTable`: its methods, and every concrete `pack` that
implements them. From it, LLVM emits one **dispatcher stub** per method:

```llvm
define i64 @__dyn_Shape_area(i8* %box) {
  %tid  = load i32, i32* bitcast(i8* %box to i32*)
  %data = load i8*,  i8** bitcast(getelementptr(i8, i8* %box, i64 8) to i8**)
  switch i32 %tid, label %default [ i32 1, label %case_1  i32 2, label %case_2 ]
case_1:
  %self_1 = bitcast i8* %data to %struct.Square*
  %r_1 = call i64 @Square_area(%struct.Square* %self_1)
  ret i64 %r_1
…
}
```

A **switch on the type id**, not a vtable pointer. Every implementer is known at
link time, so the branch table is built at compile time and there is no indirect
call - which is why a spec-typed call can still inline the callee's body under
LLVM's own optimiser, and why adding an implementer in another compilation unit
would not work.

### 2.3 Properties dispatch too

A spec's `prop` is a nullary method as far as the table is concerned, so
`value.size` on a spec-typed value goes through the same stub.

An implementer that satisfies a property with a plain **field** has no getter to
point at. That type drops out of *that property's* switch rather than out of the
table, so its ordinary method dispatch is unaffected - and reading the property
through the spec falls to the default case (§6.2).

---

## 3. What does not get a table

Three exclusions, each for a reason:

- **A spec with no methods.** Nothing to dispatch; it is a marker.
- **A bridge pack.** Boxing needs a struct to cast to, and `bridge pack Int`
  has none. A primitive conforming to a spec takes part in constraint checking
  and never in a table.
- **An incomplete implementer.** If a type is missing any callable method the
  spec declares, it is left out of the table entirely rather than half-entered.

---

## 4. Type tests and downcasting

Both implemented:

```azora
fin s: Shape = Square(4)

if s is Square { … }            // true - the box carries the type
fin sq = s as? Square           // Square?, null if it is not one
```

`is` and `as?` read the box's type id. This is the way back down from a spec to
a concrete type, and the only one - there is no unchecked downcast.

---

## 5. Per backend

|                | interpreter                         | LLVM                       | Wasm     |
|----------------|-------------------------------------|----------------------------|----------|
| representation | a `__type` key on the value's map   | `{ i32, i8* }` fat pointer | **none** |
| dispatch       | look up the concrete method by name | type-id switch stub        | **none** |
| `is` / `as?`   | reads `__type`                      | reads the id               | **none** |

The interpreter does not use the dispatch table at all - values carry their type
name and methods are found by name, so `IrSpecTable` is ignored there.

- ⬜ **WebAssembly has no spec dispatch whatsoever.** `specTable` and
  `specDispatch` appear zero times in `WasmCodegen`. A program using a
  spec-typed value compiles for Wasm and does something else

---

## 6. What is broken

### 6.1 A collection of spec-typed values cannot be built

This is the gap worth fixing first, because it is the reason dynamic dispatch
exists at all:

```azora
fin all = @arr[Square(2), Circle(1)]
// variadic arguments for 'std__arrayOf' must share a type, got Square and Circle
```

An explicit annotation does not help - `std::Array<Shape, 2>` still fails,
because the elements are not upcast to `Shape` before the shared-type check
runs. So:

- a spec-typed **binding** works,
- a spec-typed **parameter** works,
- a spec-typed **field** works,
- a spec-typed **collection** does not.

Every "a list of things that share a capability" program is unwritable, which is
most of what people reach for dispatch to do.

- ⬜ Upcast a literal's elements to the annotated element type before checking
  that they agree, so `std::Array<Shape, 2> = @arr[Square(2), Circle(1)]`
  compiles

### 6.2 The default case answers wrongly and silently

The dispatcher's `default:` branch returns the return type's default value - `0`
for an `Int`, a null pointer for a reference. It is reachable in two ways: a type
id with no case (a value boxed against a table that did not include its type),
and a property whose implementer satisfies it with a field (§2.3).

Both give a plausible wrong answer rather than a failure.

- ⬜ Make the default branch trap, or prove it unreachable and emit
  `unreachable`

### 6.3 Other gaps

- ⬜ **Boxes leak** (§2.1)
- ⬜ **No spec-typed value can cross a compilation unit.** The switch is closed
  over the implementers the compiler can see, so separate compilation and a
  package manager both need this rethought
- ⬜ **Generic specs are not dispatched.** `Serializer<T>` has a type parameter;
  what a table for it would even contain is unspecified
- ⬜ **No object safety rule.** Nothing says which specs may be used as a type.
  A spec with a static (receiver-less) `func`, or one returning `Self`, cannot
  be dispatched - and nothing rejects using it as a type
- ⬜ **Equality and hashing of a spec-typed value** are unspecified: whether
  they compare the box, the id, or the pointee
- ⬜ **No performance statement.** An upcast allocates and a call switches;
  neither cost is written down or measured

---

## 7. Rules

1. Using a spec as a type selects dynamic dispatch. There is no keyword.
2. A spec-typed value is a `{ typeId, data }` fat pointer, allocated at the
   upcast.
3. A spec→spec assignment passes the existing box; it does not re-box.
4. Dispatch is a switch on the type id, over the implementers visible at
   compile time.
5. A spec's `prop` dispatches as a nullary method.
6. A type missing any of a spec's callable methods is not in its table.
7. A bridge pack never enters a table - there is no struct to box.
8. `is` and `as?` read the box's type id, and are the only way back to a
   concrete type.

---

## 8. Related

- `PACK_IMPL_DIP.MD` - `spec` declarations and `impl … for …`
- `TYPE_DIP.MD` - `is` and the cast family
- `COMPILER_INTRINSIC_DIP.MD` §3.2 - `__isCheck` and `__dynCast`
- `ROADMAPs/VERSION_0_1_ROADMAP.MD` §2.3, §6.1
