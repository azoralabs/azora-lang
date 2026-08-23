# Azora Packs, Impls & Specs - DIP Implementation Profile

`pack` (structs), `impl` (methods & conformances), and `spec` (traits/interfaces),
plus the Rust-style dynamic dispatch that ties them together.

## Grammar

```
@[DECORATORS] VISIBILITY (opaque)? pack NAME <...GENERICS> [where CLAUSE] {
    FIELD_VISIBILITY (var | fin) NAME : Type [= default]
    …
}

impl NAME <...GENERICS>? { METHODS }                     // inherent methods
impl SPEC <ARGS>? for NAME { METHODS }                   // spec conformance
impl DECO(args)? for NAME { … }                          // decorator application
impl oper SYMBOL [by SPEC] for NAME { self&, rhs -> … }  // operator overload

spec NAME <...GENERICS> [: PARENT<ARGS>] {
    func GENERICS? RECEIVER_HEAD? NAME(...PARAMS): RET    // required function (no body)
    prop SHARED_RECEIVER_HEAD? NAME<...GENERICS>?: Type   // required observational property
}

ctor () { STATIC_STARTUP_BODY }
ctor .(...PARAMS) { INSTANCE_INITIALIZATION_BODY }
dtor () { STATIC_SHUTDOWN_BODY }
dtor .() { INSTANCE_DESTRUCTION_BODY }
```

- `VISIBILITY` ::= `expose` | `confine` | `protect` (default `expose`).
- `FIELD_VISIBILITY` per field ::= `expose` | `confine`.
- `opaque pack` forces every field to `confine`.
- `SHARED_RECEIVER_HEAD` has the normal receiver-head shape, but every receiver
  type must end in `&`; mutable `!` and owned receiver slots are invalid.

---

## 1. Packs (structs)

```azora
pack Point {
    var x: Int
    var y: Int
}

pack Config {
    fin name: String            // immutable field
    var retries: Int = 3        // defaulted field
    confine var secret: Int     // private to declaring file
}
```

Construction and access:

```azora
var p = Point(3, 4)
p.x = 10
println("${p.x}, ${p.y}")
```

### 1.1 Generic packs

```azora
pack Box<T> { var item: T }
pack Pair<A, B> { fin first: A; fin second: B }
pack Tuple<...T> where (...T).length >= 2 { … }   // variadic
var b = Box<Int>(42)
```

## 2. Impl blocks

### 2.1 Inherent methods

Methods declare a receiver head before the dot and method name. Inside an
`impl` or `spec`, `&.` and `!.` are the canonical shorthands for immutable and
mutable implicit `self`; `Self.` is the owned form:

```azora
impl Point {
    func &.magnitudeSquared(): Int {
        return self.x * self.x + self.y * self.y
    }
    func !.moveBy(dx: Int, dy: Int) {
        self.x = self.x + dx
        self.y = self.y + dy
    }
}
```

The receiver head may contain multiple explicitly typed receivers, and function
generics precede it: `func<T, U> (first: T&, second: U&).combine()`. Receiver
base types must be pairwise distinct after aliases and generic substitution;
`T` and `U` therefore carry an implicit distinctness requirement. Borrow versus
owned capability does not distinguish an otherwise equal type. A function
without receivers remains `func helper()` and has neither receiver syntax nor
dot.

### 2.2 Computed properties (`prop`)

Accessed without parentheses:

```azora
impl Duration {
    prop &.isZero: Bool = self.seconds == 0L
    prop &.inMillis: Long = self.seconds * 1000L
}
d.isZero        // no parentheses
```

Properties use only the observational subset of the function receiver-head
grammar. In an `impl` or `spec`, `prop &.name` introduces `self: Self&`.
`prop !.name` and owned `prop Self.name` are invalid: field-like access must
neither mutate nor consume its receiver. Such behavior is declared as
`func !.name()` or `func Self.name()` so its call site has parentheses. Every
receiver of a multi-receiver property must likewise be a shared `T&`. A
borrowed result names its origin with bars: `prop &.name: String&|self|`.

### 2.3 Constructors & destructors

```azora
pack Sheep {
    var naked: Bool = false
    fin name: String
}

impl Sheep {
    ctor .(name: String) {
        self.name = take name
    }

    dtor .() {
        // owned Sheep is being destroyed
    }
}
```

Azora has exactly two constructor modes and two matching destructor modes:

| Declaration | Meaning | Receiver |
|---|---|---|
| `ctor ()` | static startup hook, run once when the program initializes the type | none |
| `ctor .(...)` | construct a new owned instance | implicit initializing `self: Self` |
| `dtor ()` | static shutdown hook, run once after successful startup | none |
| `dtor .()` | destroy one owned instance | implicit destroying `self: Self` |

`ctor .(...)` is the only instance constructor. The dot denotes owned/move
construction; it is not a mutable-reference receiver. During its body, `self`
has **initialization privilege**:

- a previously uninitialized `fin` field may be assigned exactly once;
- a `var` field may be initialized;
- a field default is applied before the body unless the constructor supplies
  that field through the compiler's definite-initialization plan;
- an uninitialized field may not be read;
- every required field must be initialized on every successful exit.

Moving a named owned parameter into an owned field remains explicit:
`self.name = take name`. A literal or other temporary at the call site needs no
`take`, because it is already an owned rvalue:

```azora
var dolly: Sheep = .(name: "Dolly")
```

For a simple pack, a custom constructor is unnecessary. The generated owned
field constructor accepts every field without a default and applies defaults:

```azora
pack Sheep {
    var naked: Bool = false
    fin name: String
}

var dolly = Sheep(name: "Dolly")
```

`dtor .()` runs exactly once when an owned value reaches deterministic scope
exit or is explicitly dropped. It has destruction privilege but may not return,
store, or resurrect `self`. Initialized fields that the body does not consume
are destroyed exactly once afterward.

`ctor ()` has no parameters, no `self`, and runs once during normal program
startup before `main`, after the static constructors it depends on. `dtor ()`
has no parameters or `self`; it runs at normal shutdown only if the matching
static constructor completed, in reverse initialization order. Static
initialization cycles are compile-time errors.

The following forms are always rejected:

```azora
ctor &.()  // no reference constructors
ctor !.()  // no mutable-reference constructors
dtor &.()  // no reference destructors
dtor !.()  // no mutable-reference destructors
```

Ordinary shared and mutable borrows remain zero-cost lifetime operations and do
not execute user code. Reference-specific behavior belongs in `func &.name()` /
`func !.name()` or in a separate owned guard pack with its own `dtor .()`.

### 2.4 `impl pack` (privileged mutation)

`impl pack Type { … }` may mutate `confine` fields of a pack declared in
the same file.

## 3. Specs (traits)

```azora
spec Serializer<T> {
    func &.toValue(value: T&): SerialValue
    func &.fromValue(value: SerialValue&): T
}

spec MutableMap<K, V> : Map<K, V> {     // spec inheritance
    func !.put(key: K, value: V): V?
    func !.putAll(other: Map<K, V>&)
}
```

Conformance:

```azora
pack JsonWriter
impl Serializer<Value> for JsonWriter {
    func &.toValue(value: Value&): SerialValue { … }
    func &.fromValue(value: SerialValue&): Value { … }
}
```

## 4. Dynamic dispatch (`dyn`, Rust-style)

A `spec`-typed value carries its concrete type and dispatches at runtime - even
in the native LLVM backend (fat-pointer representation):

```azora
spec Shape { func &.area(): Double }
pack Circle { var r: Double }
pack Square { var s: Double }
impl Shape for Circle { func &.area(): Double { return 3.14159 * self.r * self.r } }
impl Shape for Square { func &.area(): Double { return self.s * self.s } }

func totalArea(shapes: Array<Shape>): Double {
    var t = 0.0
    for s in shapes { t = t + s.area() }   // dynamic dispatch
    return t
}

fin shapes: Array<Shape> = @arr[Circle(1.0) as Shape, Square(2.0) as Shape]
```

- Store a concrete value as a spec via `value as Spec`.
- `Array<Spec>` holds heterogeneous implementers; method calls dispatch to each
  concrete `impl`.

## 5. Singletons & DI

See the Dependency Injection DIP for `solo`, `wrap`, and `inject`.
