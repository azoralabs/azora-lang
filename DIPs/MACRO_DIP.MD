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
    [] => std::emptyList()
    [...$items] => std::listOf(...$items)
}

macro @vec! {
    [] => std::emptyMutableList()
    [...$items] => std::mutableListOf(...$items)
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

---

## 2. Infix macros

The operand holes surround the name, so the declaration reads like the call it
enables:

```azora
macro $a @to $b => std::mapEntry($a, $b)

fin entry = "key" @to 42          // std::mapEntry("key", 42)
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

### 3.2 Type macros

`macro .Type { … }` is gone entirely, with the grammars it supplied:

| removed                      | write instead    |
|------------------------------|------------------|
| `[T]`                        | `List<T>`        |
| `[T]!`                       | `MutableList<T>` |
| `{T}`                        | `Set<T>`         |
| `[K: V]`                     | `Map<K, V>`      |
| `[T; N]`                     | `Array<T, N>`    |
| `(A, B)`                     | `Tuple<A, B>`    |
| `res $T`                     | `Resource<T>`    |
| `query [...$T]`              | `Query<…>`       |
| `$A with $B` (type position) | `With<A, B>`     |

A type macro let a library invent type syntax, and the cost was that a type's
spelling depended on which modules happened to be imported. A generic type says
the same thing and always means it.

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
std::hashSetOf(1, 2, 3)
std::treeMapOf("a" @to 1)
std::linkedHashSetOf(1, 2)
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
| `# set@[…]`                         | `std::hashSetOf(…)`       |
| `!# set@[…]`                        | `std::linkedHashSetOf(…)` |
| `^ map@[…]`                         | `std::treeMapOf(…)`       |
| `a to b`                            | `a @to b`                 |

Earlier removals, kept here for the record: `name![…]` became `@name[…]`;
`mut @vec[…]` became `@vec![…]`; and `mixin "code"` became `inline "code"`.

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
