# Azora Base Syntax - DIP Implementation Profile

Bindings, literals, operators, string interpolation, and comments - the lexical
and expression foundation shared by every other construct.

## Grammar

```
BINDING          ::= (var | let | val | fin) NAME [: Type] = EXPRESSION
LAZY_BINDING     ::= lazy (let | fin) NAME [: Type] = EXPRESSION
REACTIVE_BINDING ::= (remember | retain | preserve) (var | let | val | fin) NAME [: Type] = EXPRESSION
CONST            ::= (inline | deepinline) (var | let | val | fin) NAME [: Type] = EXPRESSION   (compile-time)
ASSIGN           ::= LVALUE (= | += | -= | *= | /= | %= | ~=) EXPRESSION
LVALUE           ::= NAME | expr.field | expr[index] | *pointer
```

Four keywords vary two independent axes - whether the **name** can be rebound,
and whether the **value** can be mutated through it:

| keyword | rebind the name | mutate the value | `&` (shared borrow) | `!` (mutable borrow) |
|---------|-----------------|------------------|---------------------|----------------------|
| `var`   | yes             | yes              | yes                 | yes                  |
| `let`   | no              | yes              | yes                 | yes                  |
| `val`   | yes             | no               | yes                 | no                   |
| `fin`   | no              | no               | yes                 | no                   |

- `remember` / `retain` / `preserve` - a reactive lifetime written in front of
  one of the four bindings (see the Reactive DIP).
- `lazy fin` / `lazy let` evaluate their initializer on first read. Inside a
  reactive owner they are cached derived values and are invalidated by reactive
  inputs. `lazy var` and `lazy val` are rejected.
- All four work in a runtime scope and in a compile-time one: `inline var`,
  `inline let`, `inline val`, `inline fin` are folded at compile time, and
  `deepinline …` recurses through nested inline constructs (see the CTCE DIP).
- Only `fin` may appear at top level. A global is shared, and what makes sharing
  unsafe is the name being rebindable - so `var`, `let` and `val` are all
  rejected there; use `fin`, `threadlocal`, or the `inline …` forms.

---

## 1. Bindings

```azora
var count = 0            // rebindable, mutable value
let buffer = alloc Int[8]  // fixed name, writable buffer
val config = loadConfig()  // rebindable name, frozen value
fin name = "Azora"       // fixed name, frozen value
var total: Int = 0       // explicit type
```

The two axes are independent, so each keyword rejects exactly one thing:

```azora
var p = Point(1, 2)
p = Point(3, 4)          // ok - var rebinds
p.x = 10                 // ok - var mutates

let q = Point(1, 2)
q = Point(3, 4)          // error: cannot reassign immutable binding 'q'
q.x = 10                 // ok

val r = Point(1, 2)
r = Point(3, 4)          // ok
r.x = 10                 // error: cannot assign to member 'x' through 'r'

fin s = Point(1, 2)
s = Point(3, 4)          // error
s.x = 10                 // error
```

Immutability of the value reaches through a whole access chain, and covers
index writes and mutable borrows as well as field writes:

```azora
fin o = Outer(Inner(1))
o.inner.v = 2            // error - the write lands on `o`

func bump(n: Int!) { n = n + 1 }
val c = 1
bump(c!)                 // error: cannot borrow mutably … through 'c'
```

Reassignment and compound assignment (only for `var` and `val`):

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
| `Float` / `Double` / `Decimal` | 32-bit / 64-bit / 128-bit floating point |
| `Bool` | `true` / `false` |
| `Char` | a character |
| `String` | UTF-8 text |
| `Unit` | no meaningful value (void) |
| `Any` | dynamic / erased |

## 3. Literals

```azora
255            // Int
0xFF           // hex Int
3.14           // Double
1500.0f        // Float (suffix)
100L           // Long (suffix)
true / false   // Bool
'a'            // Char
"hello"        // String
null           // null literal (for nullable types)
```

Collection literals use macros (see the Meta DIP):

```azora
@arr[1, 2, 3]              // Array
@vec[1, 2, 3]              // immutable List
@vec![1, 2, 3]             // mutable List
@set[1, 2, 3]              // Set
@map["a": 1, "b": 2]       // Map
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
a @to b                                       // universal infix pair
"ab" * 3                                      // string repetition
```

## 5. String interpolation

```azora
fin name = "Azora"
fin n = 3
println("Hello, ${name}!")        // braces
println("Hello, $name!")          // bare identifier
println("${n} x ${n} = ${n * n}") // expression
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
scope {
    var x = 2            // shadows outer x
    println(x)
    println(::x)    // ::name reaches the enclosing scope
}
```

A bare `scope { … }` introduces a nested lexical scope. `::name` accesses the
same name in the parent scope. (See the Modules & Realms DIP for named realms.)
