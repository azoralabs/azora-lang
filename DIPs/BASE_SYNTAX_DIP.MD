# Azora Base Syntax — DIP Implementation Profile

Bindings, literals, operators, string interpolation, and comments — the lexical
and expression foundation shared by every other construct.

## Grammar

```
BINDING   ::= (var | fin | let | mem | rem | ret) NAME [: Type] = EXPRESSION
CONST     ::= (inline | deepinline) (var | fin | let) NAME [: Type] = EXPRESSION   (compile-time)
ASSIGN    ::= LVALUE (= | += | -= | *= | /= | %= | ~=) EXPRESSION
LVALUE    ::= NAME | expr.field | expr[index] | *pointer
```

- `var` — mutable binding.
- `fin` — final (immutable) binding, value fixed after initialization.
- `let` — single-assignment binding (immutable reference).
- `mem` / `rem` / `ret` — reactive bindings (see the Reactive DIP).
- `inline var` / `inline fin` / `inline let` — compile-time bindings, folded at
  compile time; `deepinline …` recurses through nested inline constructs (see the
  CTCE DIP). `inline var` is a mutable compile-time binding used during folding.

---

## 1. Bindings

```azora
var count = 0            // mutable, type inferred as Int
fin name = "Azora"       // immutable
let greeting = "Hello"   // single-assignment
var total: Int = 0       // explicit type
```

Reassignment and compound assignment (only for `var`):

```azora
count = count + 1
count += 5
count -= 2
count *= 2
count /= 3
count %= 4
text ~= suffix           // ~= appends a stringified value
```

## 2. Primitive types

| Type | Meaning |
|------|---------|
| `Int` / `UInt` | 32-bit signed / unsigned |
| `Byte` / `UByte` | 8-bit |
| `Short` / `UShort` | 16-bit |
| `Long` / `ULong` | 64-bit |
| `Cent` / `UCent` | 128-bit |
| `Float` / `Real` / `Decimal` | 32-bit / 64-bit / 128-bit floating point |
| `Bool` | `true` / `false` |
| `Char` | a character |
| `String` | UTF-8 text |
| `Unit` | no meaningful value (void) |
| `Any` | dynamic / erased |

## 3. Literals

```azora
255            // Int
0xFF           // hex Int
3.14           // Real
1500.0f        // Float (suffix)
100L           // Long (suffix)
true / false   // Bool
'a'            // Char
"hello"        // String
null           // null literal (for nullable types)
```

Collection literals use macros (see the Meta DIP):

```azora
arr@[1, 2, 3]              // Array
vec@[1, 2, 3]              // immutable List
vec!@[1, 2, 3]             // mutable List
set@[1, 2, 3]              // Set
map@["a": 1, "b": 2]       // Map
["a": 1]                   // map literal (bracket sugar)
```

## 4. Operators

```azora
a + b   a - b   a * b   a / b   a % b        // arithmetic
-a                                            // negation
a == b  a != b  a < b  a <= b  a > b  a >= b  // comparison
a && b  a || b  !a                            // logical
a & b   a | b   a ^ b   ~a   a << b   a >> b  // bitwise
a ?? b                                        // null-coalescing (a if non-null else b)
a?.field                                      // safe member access
a to b                                        // universal infix pair
"ab" * 3                                      // string repetition
```

## 5. String interpolation

```azora
fin name = "Azora"
fin n = 3
std::println("Hello, ${name}!")        // braces
std::println("Hello, $name!")          // bare identifier
std::println("${n} x ${n} = ${n * n}") // expression
```

## 6. Ranges

```azora
for i in 1..10 { … }      // inclusive 1..10
for i in 0..<10 { … }     // half-open 0..9
```

## 7. Comments

```azora
// line comment
/* block comment */
/** doc comment (attached to the following declaration) */
```

## 8. Blocks and scopes

```azora
zone {
    var x = 2            // shadows outer x
    std::println(x)
    std::println(::x)    // ::name reaches the enclosing scope
}
```

A bare `zone { … }` introduces a nested lexical scope. `::name` accesses the
same name in the parent scope. (See the Modules & Zones DIP for named zones.)
