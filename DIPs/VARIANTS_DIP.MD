# Azora Slots (Tagged Unions) — DIP Implementation Profile

`variant` declares a tagged union / sum type: a value that is exactly one of several
variants, each of which may carry payload data.

## Grammar

```
@[DECORATORS] VISIBILITY variant NAME <...GENERICS>? {
    VARIANT
    …
}

VARIANT ::= Name                        // no payload
          | Name(Type, …)               // positional payload
          | Name(field: Type, …)        // named payload
```

Match on a variant with `when` and destructure the payload.

---

## 1. Declaration

```azora
variant Shape {
    Circle(Int)
    Rect(Int, Int)
    Empty
}
```

Named payloads (used by e.g. the serializer's `SerialValue`):

```azora
variant SerialValue {
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

## 4. Generic slots

```azora
variant Option<T> {
    Some(value: T)
    None
}
```

## 5. Slots vs enums

- `enum` — closed set of names, **no payload** (see the Enums DIP).
- `variant` — variants **with payloads**; the runtime representation is a tag plus
  the boxed payload.
