# Azora Enums - DIP Implementation Profile

`enum` declares a closed set of named cases, **none of which carries data**.
Prefixing it with the `variant` modifier - `variant enum` - is what allows
payloads; see the Variants DIP. A payload on a plain `enum` is a compile error
naming that form.

A plain enum may instead give every case one **fixed value** of a declared
type - `enum Key: Int { W(87) }` - read with `.value` (§6). A fixed value
belongs to the case, not to a value of the enum: every `Key.W` has the same
one, so the enum stays a plain enum.

## Grammar

```
DECORATORS* VISIBILITY enum NAME <...GENERICS>? (: VALUE_TYPE)? {
    VARIANT
    VARIANT
    …
}

VARIANT ::= Name                      // newline- or comma-separated
          | Name(LITERAL, …)          // with a VALUE_TYPE: the case's value

VALUE_TYPE ::= a number type, Bool, Char, String, or a tuple of them
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

## 6. Fixed case values

`enum Name: Type` gives each case one value of `Type`, written after the case
name. A tuple type takes one value per element:

```azora
enum KeyCode: Int {
    Backspace(8)
    W(87)
    Left(1001)
}

enum Planet: (Int, String) {
    Mercury(1, "One")
    Venus(2, "Two")
}
```

- **Read** a case's value with `.value`: `KeyCode.W.value` is `87`,
  `key.value` reads any `KeyCode`, and `Planet.Venus.value` is the tuple
  `(2, "Two")` (`.value.1`, or `fin (n, name) = Planet.Venus.value`).
- **Find** the case a value names with `as?`: `raw as? KeyCode` is
  `KeyCode?` - the case whose value is `raw`, or `null` when none is, so a
  fallback is written `raw as? KeyCode ?? .Escape`. A plain `as` to a valued
  enum is refused: a value may name no case.
- **Rules**, each a compile error naming the fix:
  - every case writes its value, exactly one per tuple element;
  - each value is a literal of its type (an integer or real literal, which may
    be negated, a `Char`, a `String`, `true`/`false`) and fits it
    (`Big(128)` does not fit `Byte`);
  - no two cases share a value (`W(87)` and `Up(0x57)` are the same value), so
    a value names at most one case;
  - the value type is one a literal is written in: a number type, `Bool`,
    `Char`, `String`, or a tuple of them.
- An enum case is not a number: `KeyCode.W as Int` is refused, pointing at
  `.value` (and, for an enum without values, at declaring them).
- Everything else is a plain enum's: a case prints as its name, `when` matches
  cases exhaustively, `.W` is inferred where a `KeyCode` is expected.
- Every backend reads a value through one generated function per enum (a case
  named in place, `KeyCode.W.value`, is its value written out), and `as?`
  through another that compares the value with each case's in turn.

Without `: Type`, `Name(…)` is still a payload, and the diagnostic names both
forms: `write 'variant enum Color { … }' for a tagged union, or
'enum Color: Type { Red(value) }' for a fixed value`.

Owner's decisions (2026-11-18): the declaration form `enum X: Int { a(1) }`
and `enum Y: (Int, String) { a(1, "One") }`; `.value` to read; values
distinct; `raw as? X` to find a case.

## 7. Payload-carrying variants

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
