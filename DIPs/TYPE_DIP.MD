# Azora Types — DIP Implementation Profile

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
var b: Box<Real>
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
std::println(*p)             // deref
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
- These desugar to `std::cast<T>(x)` / `std::dyncast<T>(x)` / `std::bitcast<T>(x)`.

## 10. Type checks

```azora
if x is Int { … }
fin ok = value is Serializer
```

## 11. Type-level macros & functions

Named type sugar (`vec[T]`), type-macro blocks (`meta type { … }`), and
compile-time `type` functions are covered in the Meta and CTCE DIPs.
