# Azora Meta / Macros — DIP Implementation Profile

`macro` declares macros — value macros (`vec@[…]`), type-sugar macros
(`meta type`), and named type macros — expanded at compile time before semantic
analysis.

## Grammar

```
VALUE_MACRO ::= meta [PREFIX] NAME [!] { ARM … }
ARM         ::= PATTERN => TEMPLATE_EXPR

TYPE_MACRO  ::= meta type { TYPE_ARM … }
TYPE_ARM    ::= TYPE_PATTERN [!] => TYPE
             |  [PREFIX] NAME [!] HOLES => TYPE       // named type macro

INVOKE      ::= NAME @( … ) | NAME @[ … ] | NAME @{ … }       // immutable
             |  NAME !@( … ) | NAME !@[ … ] | NAME !@{ … }    // mutable variant

PREFIX      ::= # | !# | ^
```

The trailing `!` (on a name, or after an anonymous pattern) always denotes the
**mutable** variant — declaration `meta vec!` matches invocation `vec!@[…]`.

---

## 1. Value macros

```azora
macro .Prefix("vec") {
    [] => std::emptyList()
    [...$items] => std::listOf(...$items)
}
macro .Prefix("vec!") {
    [] => std::emptyMutableList()
    [...$items] => std::mutableListOf(...$items)
}
```

Invocation (delimiter is ergonomic — `[]`, `()`, `{}` all feed the same arms):

```azora
fin a = vec@[1, 2, 3]       // immutable → List
fin b = vec!@[1, 2, 3]      // mutable → MutableList
fin c = arr@[1, 2, 3]       // Array
fin m = map@["a": 1]        // Map
```

- `$item` / `...$items` are the macro's holes.
- The **mutable** variant is always `name!@…`, never `mut name@…`.

## 2. Type-sugar macros (`meta type`)

```azora
macro .Type {
    [T] => std::List<T>
    [T]! => std::MutableList<T>       // trailing ! = mutable
    {T} => std::Set<T>
    {T}! => std::MutableSet<T>
    [K: V] => std::Map<K, V>
    [K: V]! => std::MutableMap<K, V>
}
```

## 3. Named type macros

```azora
macro .Type {
    res $T => Resource<$T>
    res! $T => MutResource<$T>              // ! on the name = mutable
    query [...$T] => Query<...$T>
}
```

- `res! $T` (mutable) mirrors the value convention `vec!@`.
- Holes must start with `$`.
- Infix type patterns (`$A op $B`) are **not** allowed here — they belong in
  `meta .Infix` (§3.1).

### 3.1 Infix macros (`meta .Infix`)

The operator name lives in `.Infix("op")`, so arms do **not** repeat it — they
are just the two operand holes:

```azora
macro .Infix("with") {
    $Base $Filter => With<$Base, $Filter>       // Base with Filter → With<Base, Filter>
}
macro .Infix("without") {
    $Base $Filter => Without<$Base, $Filter>
}
```

A value-level infix operator uses the **same arm form** — the template is an
expression instead of a type:

```azora
macro .Infix("to") {
    $a $b => std::mapEntry($a, $b)      // a to b → std::mapEntry(a, b)
}

fin entry = "key" to 42                 // expands to std::mapEntry("key", 42)
```

- The operand holes are `$a $b`; the operator (`to`) is never repeated.
- A `Type<…>` template makes the arm a type-infix rule; any other expression
  makes it a value-infix macro; a bare hole (`$Base`) works in both positions.
- This replaces the removed `infx` keyword. The macro kind is a `Macro` slot:
  `bridge variant Macro { Prefix(String); Infix(String); Type }`.

## 4. Compile-time inline blocks

```azora
inline if DEBUG {                 // kept in the IR when condition is true
    func debugOnly() { … }
}
deepinline if DEBUG { … }         // folded away entirely if false (recurses)
inline for X in ...T { … }        // compile-time unrolled loop
inline "code string"               // splice a string as source at compile time
```

## 5. Type functions

Compile-time functions over types (`type name(...)`) are covered in the CTCE DIP;
they compute types from types (e.g. `promote`).

## 6. Migration note

Removed forms:
- value macros `name![…]` → `name@[…]`; mutable `mut vec@[…]` → `vec!@[…]`.
- declaration `meta vec { }` → `meta .Prefix("vec") { }`; `meta type { }` →
  `meta .Type { }`.
- the `infx` keyword → `meta .Infix("op")` + a free `op(self, …)` function.
- `mixin "code"` → `inline "code"`.
