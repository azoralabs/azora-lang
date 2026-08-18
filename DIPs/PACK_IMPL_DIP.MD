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
    func METHOD(...PARAMS): RET                          // required method (no body)
    prop NAME: Type                                       // required property
}
```

- `VISIBILITY` ::= `expose` | `confine` | `protect` (default `expose`).
- `FIELD_VISIBILITY` per field ::= `expose` | `confine`.
- `opaque pack` forces every field to `confine`.

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

The receiver is `self`; its borrow is a postfix sigil (`self&` immutable,
`self!` mutable):

```azora
impl Point {
    func magnitudeSquared(): Int { self& -> return self.x * self.x + self.y * self.y }
    func moveBy(dx: Int, dy: Int)  { self! -> self.x = self.x + dx; self.y = self.y + dy }
}
```

### 2.2 Computed properties (`prop`)

Accessed without parentheses:

```azora
impl Duration {
    prop isZero: Bool = self.seconds == 0L
    prop inMillis: Long = self.seconds * 1000L
}
d.isZero        // no parentheses
```

### 2.3 Constructors & destructors

```azora
impl Resource {
    ctor(path: String) { … }     // secondary constructor
    dtor { … }                    // destructor, runs on `drop`
}
```

### 2.4 `impl pack` (privileged mutation)

`impl pack Type { … }` may mutate `confine` fields of a pack declared in
the same file.

## 3. Specs (traits)

```azora
spec Serializer<T> {
    func toValue(value: T&): SerialValue
    func fromValue(value: SerialValue&): T
}

spec MutableMap<K, V> : Map<K, V> {     // spec inheritance
    func put(key: K, value: V): V?
    func putAll(other: Map<K, V>&)
}
```

Conformance:

```azora
pack JsonWriter
impl Serializer<Value> for JsonWriter {
    func toValue(value: Value&): SerialValue { self& -> … }
    func fromValue(value: SerialValue&): Value { self& -> … }
}
```

## 4. Dynamic dispatch (`dyn`, Rust-style)

A `spec`-typed value carries its concrete type and dispatches at runtime - even
in the native LLVM backend (fat-pointer representation):

```azora
spec Shape { func area(): Double }
pack Circle { var r: Double }
pack Square { var s: Double }
impl Shape for Circle { func area(): Double { self& -> return 3.14159 * self.r * self.r } }
impl Shape for Square { func area(): Double { self& -> return self.s * self.s } }

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
