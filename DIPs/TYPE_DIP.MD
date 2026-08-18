# Azora Types - DIP Implementation Profile

The type grammar: named types, generics, nullability, borrows, error types,
collections, functions, pointers, tuples, `typealias`, and casts.

## Grammar

```
TYPE ::= NAME
       | NAME < TYPE, … >             // generic instantiation
       | NAME < TYPE, N >             // const-generic (value) argument
       | TYPE ?                        // nullable
       | TYPE &                        // immutable borrow
       | TYPE !                        // mutable borrow
       | TYPE *                        // pointer
       | TYPE ?! ERRSET                // failable (ERRSET = E | [E1, E2])
       | ...T                          // variadic pack (prefix)
       | ( TYPE, … ) -> TYPE           // function type
       | Array<T> | Array<T, N>        // arrays (sized / unsized)
       | List<T> | Set<T> | Map<K, V>  // collections
       | Tuple<A, B, …>                // tuple
       | TYPE with FILTER              // type-filter composition (ECS)
       | TYPE without FILTER

TYPEALIAS ::= typealias NAME <...GENERICS>? = TYPE
```

---

## 1. Named & generic types

```azora
var a: Int
var xs: List<Int>
var m: Map<String, Int>
var b: Box<Double>
```

Const-generic (value) arguments:

```azora
var fixed: Array<Int, 3>      // 3 is a compile-time value parameter
```

## 2. Nullable types

```azora
var maybe: Int? = null
fin v = maybe ?? 0            // null-coalescing
fin x = obj?.field           // safe access → T?
```

## 3. Borrows

```azora
func read(x: Int&)  { … }    // immutable borrow
func write(x: Int!) { x = 1 } // mutable borrow (propagates back)
fin borrowed: Buffer& = owned
var exclusive: Buffer! = owned
```

(`shared`/`weak` reference kinds are planned via `std.memory`.)

## 4. Error (failable) types

```azora
func parse(s: String): Int ?! ParseError { … }
func decode(b: Bytes): Value ?! [ParseError, IoError] { … }
```

See the Error Handling DIP.

## 5. Collections

```azora
Array<T>           // fixed/unsized array (Array<T> == Array<T, *>)
Array<T, 4>        // sized array
List<T>            // immutable list (spec)
MutableList<T>     // mutable list
Set<T> / MutableSet<T>
Map<K, V> / MutableMap<K, V>
Tuple<A, B>
```

## 6. Function types

```azora
Func(Int) -> Int
Func(String, Int) -> Bool
Func() -> Unit
```

## 7. Pointers

```azora
var p: Int* = alloc 42
println(*p)             // deref
*p = 99
```

See the Allocators & Memory DIP.

## 8. `typealias`

```azora
typealias Vector3 = Vec3
typealias Array<T> = Array<T, *>
typealias Handler = Func(Event) -> Unit
```

## 9. Casts

```azora
x as T       // static / converting cast (e.g. 5 as String)
x as? T      // dynamic (checked) downcast → T?
x as* T      // reinterpret (bit) cast
```

- `x as String` stringifies; numeric `as` converts.
- `x as? T` yields the value if it is a `T`, otherwise `null`.
- These desugar to `cast<T>(x)` / `dyncast<T>(x)` / `bitcast<T>(x)`.

A type says how it converts by declaring the matching operator:

```azora
oper as     // converting / static conversion
oper as?    // checked runtime conversion
oper as*    // bit reinterpretation
```

```azora
impl Celsius {
    oper as<U> [self: Self&] = Fahrenheit(self.degrees * 1.8 + 32.0)
    oper as?<U> [self: Self&] = if self.valid { Fahrenheit(…) } else { null }
    oper as*<U> [self: Self&] = Fahrenheit(self.degrees)
}
```

Each form is its own member, so a type may define any subset and the others keep
the built-in behaviour. Because the surface syntax and the `` intrinsics are
the same expression, an overload answers both spellings: with `oper as?` declared,
`c as? Fahrenheit` and `dyncast<Fahrenheit>(c)` both call it. A checked cast
with no overload still falls back to the runtime type test, yielding `null` when
the value is not a `T`.

## 10. Type checks

```azora
if x is Int { … }
fin ok = value is Serializer
```

## 11. Type-level functions

Compile-time `type` functions are covered in the CTCE DIP.

Type macros are gone: a library can no longer invent type syntax, so a type is
written with generics and always means the same thing regardless of what is
imported. `List<T>` rather than `[T]`, `Map<K, V>` rather than `[K: V]`,
`Tuple<A, B>` rather than `(A, B)`. See the Macro DIP for the full mapping.
