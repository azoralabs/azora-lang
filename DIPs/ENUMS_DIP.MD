# Azora Enums - DIP Implementation Profile

`enum` declares a closed set of named cases, **none of which carries data**.
Prefixing it with the `variant` modifier - `variant enum` - is what allows
payloads; see the Variants DIP. A payload on a plain `enum` is a compile error
naming that form.

## Grammar

```
@[DECORATORS] VISIBILITY enum NAME <...GENERICS>? {
    VARIANT
    VARIANT
    …
}

VARIANT ::= Name          // newline- or comma-separated
```

Variants are referenced as `EnumName.Variant`, or with the bare-dot shorthand
`.Variant` where the enum type is known from context.

---

## 1. Basic enums

```azora
enum Weekday {
    Monday
    Tuesday
    Wednesday
    Thursday
    Friday
    Saturday
    Sunday
}
```

Comma separation is also accepted:

```azora
enum Target {
    Compiler, C, ObjectiveC, WebAssembly, Llvm
}
```

## 2. Using variants

```azora
var day = Weekday.Monday

func label(t: Target): String {
    when t {
        Target.Compiler -> { return "native" }
        Target.WebAssembly -> { return "wasm" }
        else -> { return "other" }
    }
}
```

## 3. Bare-dot shorthand

Where the expected type is an enum, a variant may be written `.Variant`:

```azora
@System(.Update)              // decorator argument
func step() { … }
```

## 4. Enums in `when`

`when` matches enum variants exhaustively (see the Control Flow DIP):

```azora
when day {
    Weekday.Saturday -> { … }
    Weekday.Sunday   -> { … }
    else -> { … }
}
```

## 5. Stringification

An enum value stringifies to its variant name via `${…}` interpolation or
`value as String`.

## 6. Payload-carrying variants

Enums hold no payload. For variants with associated data (tagged unions /
sum types), use `variant`:

```azora
variant enum Shape {
    Circle(Int)
    Rect(Int, Int)
    Empty
}
```

See the Variants DIP.
