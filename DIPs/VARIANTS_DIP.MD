# Azora Variants (Tagged Unions) — DIP Implementation Profile

`variant` is a **modifier**, not a declaration of its own. It says that the cases
of the `enum` or `error` it precedes may carry payload data — turning the
payload-free Kotlin-style form into a Rust-style tagged union.

That gives four declarations along two axes: does a case carry data, and can it
be thrown?

| | payload-free | carries a payload |
|---|---|---|
| **plain value** | `enum Color { Red }` | `variant enum Result { Ok(Int) }` |
| **throwable** | `error FileError { NotFound }` | `variant error NetworkError { Timeout(Duration) }` |

A payload on a plain `enum`/`error` is rejected, and the diagnostic names the
form to use — the payload is the only thing that distinguishes them, so it is
also where the choice can be pointed out.

For a union that does **not** record which case is live, see the Unions DIP.

## Grammar

```
@[DECORATORS] VISIBILITY variant (enum | error) NAME <...GENERICS>? {
    CASE
    …
}

CASE ::= Name                        // no payload
       | Name(Type, …)               // positional payload
       | Name(field: Type, …)        // named payload
```

Match on a variant with `when` and destructure the payload. A `variant error` is
additionally an error set: it can be thrown and named in a `?!` clause.

---

## 1. Declaration

```azora
variant enum Shape {
    Circle(Int)
    Rect(Int, Int)
    Empty
}
```

Named payloads (used by e.g. the serializer's `SerialValue`):

```azora
variant enum SerialValue {
    Null
    Bool(value: Bool)
    Number(value: String)
    Text(value: String)
    Array(elements: List<SerialValue>)
    Object(fields: List<SerialField>)
}
```

## 2. Construction

```azora
var s = Shape.Circle(5)
var r = Shape.Rect(4, 6)
var e = Shape.Empty
```

## 3. Matching & destructuring

```azora
func area(s: Shape): Int {
    when s {
        Shape.Circle(radius)   -> { return radius * radius * 3 }
        Shape.Rect(w, h)       -> { return w * h }
        Shape.Empty            -> { return 0 }
    }
}
```

- Each arm binds the payload fields positionally.
- `when` over a variant is exhaustive; the compiler expects every case (or an
  `else`).

## 4. Generic variants

```azora
variant enum Option<T> {
    Some(value: T)
    None
}
```

## 5. `variant error`

The same construct, spelled with `error` so it can be thrown and named in a `?!`
clause:

```azora
variant error NetworkError {
    Timeout(seconds: Int)
    Refused
}

func fetch(ms: Int): Int ?! NetworkError {
    return if ms > 100 { .Timeout(ms) } else { ms }
}
```

- A case is thrown with `return .Case(args)` (see the Error Handling DIP).
- Everything in §2–§4 applies unchanged: a `variant error` is constructed and
  matched exactly like a `variant enum`.

## 6. The five forms

| Declaration | Cases carry data | Throwable | Records which case is live |
|-------------|------------------|-----------|----------------------------|
| `enum Color { Red }` | no | no | yes — it *is* the value |
| `variant enum Result { Ok(Int) }` | yes | no | yes — a tag |
| `error FileError { NotFound }` | no | yes | yes — it *is* the value |
| `variant error NetworkError { Timeout(Int) }` | yes | yes | yes — a tag |
| `unsafe union Value { i: Int }` | — | no | **no** — see the Unions DIP |

- A `variant`'s runtime representation is a tag plus the boxed payload.
- A payload on a plain `enum`/`error` is a compile error naming the `variant`
  form; `variant` on its own (`variant Shape { … }`) is one naming both.
