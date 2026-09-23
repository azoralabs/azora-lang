# Azora Macros - DIP Implementation Profile

`macro` declares a compile-time rewrite. There are exactly two kinds, and the
declaration says which one it is by its shape rather than by naming a kind:

- a **prefix** macro, `macro @name { … }`, invoked `@name[…]`
- an **infix** macro, `macro $a @name $b => …`, invoked `a @name b`

Both expand before semantic analysis.

## Grammar

```
PREFIX_MACRO ::= macro @NAME { ARM … }
ARM          ::= PATTERN => TEMPLATE_EXPR

INFIX_MACRO  ::= macro $HOLE @NAME $HOLE [=> TEMPLATE_EXPR]

NAME         ::= WORD [SIGIL]
SIGIL        ::= ! | ? | & | * | ^

INVOKE       ::= @NAME ( … ) | @NAME [ … ] | @NAME { … }
              |  EXPR @NAME EXPR

TYPE_ARM     ::= TYPE_PATTERN => TYPE             (a prefix arm whose holes are uppercase)
TYPE_INVOKE  ::= @NAME TYPE [CLAUSE …] | @NAME [ TYPE, … ] [CLAUSE …]
CLAUSE       ::= with OPERAND | without OPERAND
```

The `@` always leads. It leads on the declaration and it leads on the call, so
the two are spelled the same way round:

```azora
macro @vec { … }        //  declares
@vec[1, 2, 3]           //  calls
```

---

## 1. Prefix macros

```azora
macro @vec {
    [] => emptyList()
    [...$items] => listOf(...$items)
}

macro @vec! {
    [] => emptyMutableList()
    [...$items] => mutableListOf(...$items)
}
```

Invoked with any of the three delimiters - they are ergonomic only and feed the
same arms:

```azora
fin a = @vec[1, 2, 3]       // List
fin b = @vec![1, 2, 3]      // MutableList
fin c = @arr[1, 2, 3]       // Array
fin m = @map["a": 1]        // Map
fin t = @tup(1, "two")      // Tuple
```

`$item` and `...$items` are the macro's holes. An arm matches on the shape of
the invocation, so `@vec[]` and `@vec[1, 2]` reach different arms.

### 1.1 The name carries its sigil

`@vec` and `@vec!` are **two macros**, not one macro with a modifier. The sigil
is part of the name, which is why the declaration writes it too.

Only these may follow a macro's name:

| sigil |                                    |
|-------|------------------------------------|
| `!`   | conventionally the mutable variant |
| `?`   |                                    |
| `&`   |                                    |
| `*`   |                                    |
| `^`   |                                    |

The list is closed so that a macro name cannot swallow an operator sitting next
to it. Nothing else is permitted, and the meaning of each is a convention the
declaring library chooses - the compiler only knows they are part of the name.

### 1.2 Any word may be a macro name

Including one that is a keyword elsewhere: `@with`, `@to`, `@in`. The leading
`@` has already said that a name follows, so there is nothing to disambiguate.

### 1.3 Type arms

An arm whose holes are uppercase takes types, and is matched where a type is
written rather than where a call is:

```azora
macro @rows {
    $T          => Rows<$T>
    [...$ITEMS] => Rows<...$ITEMS>
}

fin r: @rows Int = .(4)                                   // Rows<Int>
func moving(bodies: @query [Position, Velocity] without Frozen)
```

- The `@` leads here as it does everywhere else. `fin r: rows Int` is an error
  that says to write `@rows Int` (decided 2026-09-22).
- The case of a hole is the whole distinction: `$T` is a type and `$t` a value.
- After its first operand, an arm may continue with `with` and `without`
  clauses. They are the arm's own grammar, matched as one pattern.
- The template is the declaring module's code. Its names mean what they mean in
  that module, whatever the use site imports or declares.
- A type macro is imported like a declaration: with its module, by name
  (`import lib.rows::rows`), or inside a block, where it binds only there.
- `.(…)` builds the type the macro expands to, as it builds any declared type.

---

## 2. Infix macros

The operand holes surround the name, so the declaration reads like the call it
enables:

```azora
macro $a @to $b => mapEntry($a, $b)

fin entry = "key" @to 42          // mapEntry("key", 42)
```

An infix macro is a single line: there is one pattern, so a block would hold
exactly one arm.

### 2.1 Registration without a rewrite

Dropping the `=>` registers the name without rewriting anything, so `a @op b`
calls the free function `op(a, b)`:

```azora
macro $a @combine $b

func combine(self: Box, other: Box): Int { return self.v * other.v }

fin n = first @combine second     // combine(first, second)
```

This is what a plain infix function wants - there is no template to rewrite to,
only a call to enable.

---

## 3. What was removed

### 3.1 The `Macro` kind enum

Declarations no longer name their kind:

```azora
macro .Prefix("vec") { … }          // removed
macro .Infix("to") { $a $b => … }   // removed
macro .Type { … }                   // removed
```

`bridge variant enum Macro { Prefix(String); Infix(String); Type }` is deleted
from `std/core.az`. The compiler reads the kind off the declaration's shape: a
`$hole` before the `@` means infix, and nothing before it means prefix. A kind
that can be seen does not need to be declared.

### 3.2 Built-in type grammars

`macro .Type { … }` is gone, with the grammars it built into the language:

| removed                      | write instead    |
|------------------------------|------------------|
| `[T]`                        | `List<T>`        |
| `[T]!`                       | `MutableList<T>` |
| `{T}`                        | `Set<T>`         |
| `[K: V]`                     | `Map<K, V>`      |
| `[T; N]`                     | `Array<T, N>`    |
| `(A, B)`                     | `Tuple<A, B>`    |
| `res $T`                     | `Resource<T>`, or a library's `@res T` (§1.3)   |
| `query [...$T]`              | `Query<…>`, or a library's `@query [...]` (§1.3) |
| `$A with $B` (type position) | `With<A, B>`     |

Those grammars let a library invent type syntax with nothing at the use site to
show it: a type's spelling depended on which modules happened to be imported. A
named type macro (§1.3) still depends on an import, but its `@` says so where it
is written.

### 3.3 The `#` token

`#` is removed from the language. It was the sigil selecting a container's
implementation - `# set@[…]` for a hash set, `!# set@[…]` for a linked-hash set,
`^ map@[…]` for a tree map - and those macros are gone with it.

The remaining container macros are the plain and mutable pair:

```azora
@set[1, 2, 3]     @set![1, 2, 3]
@map["k": v]      @map!["k": v]
@vec[1, 2, 3]     @vec![1, 2, 3]
@arr[1, 2, 3]
```

A specific implementation is reached by naming it, which is clearer than a
sigil anyway:

```azora
hashSetOf(1, 2, 3)
treeMapOf("a" @to 1)
linkedHashSetOf(1, 2)
```

### 3.4 The trailing `@`

`vec@[…]` became `@vec[…]`. A call now begins with the sigil that says it is a
macro, instead of ending its name with one.

---

## 4. Compile-time blocks

Unchanged, and covered by the CTCE DIP:

```azora
inline if DEBUG { … }             // kept when the condition holds
deepinline if DEBUG { … }         // folded away entirely, recursively
inline for X in Integers { … }    // unrolled
inline "code string"              // splice a string as source
```

---

## 5. Migration

| was                                 | is                        |
|-------------------------------------|---------------------------|
| `macro .Prefix("vec") { … }`        | `macro @vec { … }`        |
| `macro .Prefix("vec!") { … }`       | `macro @vec! { … }`       |
| `macro .Infix("to") { $a $b => T }` | `macro $a @to $b => T`    |
| `macro .Infix("op")` (bodyless)     | `macro $a @op $b`         |
| `macro .Type { … }`                 | removed; write the type   |
| `vec@[…]`                           | `@vec[…]`                 |
| `vec!@[…]`                          | `@vec![…]`                |
| `# set@[…]`                         | `hashSetOf(…)`       |
| `!# set@[…]`                        | `linkedHashSetOf(…)` |
| `^ map@[…]`                         | `treeMapOf(…)`       |
| `a to b`                            | `a @to b`                 |

The `infx` keyword is **removed**. It declared an extension method callable
infix, which is what an infix macro already does - two ways to spell one idea,
and the macro is the one that composes with the rest of the macro system. A
free infix operation is now written as a bodyless infix macro over an ordinary
function:

```azora
macro $a @scaledBy $b

func scaledBy(self: Int, factor: Int): Int { return self * factor }

fin n = 6 @scaledBy 7
```
