# Azora Control Flow - DIP Implementation Profile

Conditionals, loops, pattern matching.

## Grammar

```
IF     ::= if COND { … } [else if COND { … }] [else { … }]
IFEXPR ::= if COND { EXPR } else { EXPR }              // as an expression
WHILE  ::= while COND { … }
FOR    ::= for NAME in ITERABLE { … }
LOOP   ::= loop { … }                                  // infinite; exit via break
WHEN   ::= when SCRUTINEE { PATTERN -> { … } … [else -> { … }] }
JUMP   ::= break [:label] | continue [:label]
LABEL  ::= label: (for … | while … | loop …)
```

---

## 1. Conditionals

```azora
if n <= 1 {
    return 1
} else if n < 10 {
    return n
} else {
    return 10
}
```

As an expression:

```azora
fin sign = if x >= 0 { 1 } else { -1 }
```

A one-statement branch uses `then`; an expression branch uses `then` followed
by its value. The marker may start on the next line after the condition.

```azora
if ready then start()
fin sign = if x >= 0
then 1
else -1
```

Grouped bindings use one source group per branch. Their condition is evaluated
once for all the names, and each branch must supply the same number of values
as there are names:

```azora
var {sign, index}: Int = if negative then {-1, 1} else {1, 0}
```

A braced branch can explicitly contain its source group (`{ {a, b} }`);
`else then {a, b}` is also explicit about using a single source group.

## 2. Loops

```azora
for i in 1..10 { sum += i }        // inclusive range
for i in 0..<10 { … }              // half-open range
for item in collection { … }        // iterable

var i = 0
while i < 7 { i += 1 }

loop {                              // infinite loop
    i += 1
    if i == 7 { break }
}
```

### Descending ranges

`for i in size>..0` visits `size - 1, size - 2, …, 0`. The left
bound is excluded and the right bound is included. Equal bounds or a left bound
below the right bound produce an empty range. `5>..0 by 2` visits `4, 2, 0`:
stepping starts at the first included value, not at an ascending-range alignment.

The two bounds and optional step are evaluated once, left to right, before the
loop starts. A step must be a positive `Int`; a zero or negative step fails even
for an empty range. `continue` advances normally; `with index` counts visited
rows from zero. An increment beyond the `Int` boundary terminates iteration
without wrapping the counter into a new row.

As directed on 2026-09-09, `reverse` is an ordinary identifier, not a keyword.
The former loop modifier and `reverse..` operator are removed. Declare descending
iteration with `oper>..`; ordinary library functions named `reverse` remain legal.
Current executable ranges require `Int` bounds; generic/user-defined iteration
and other numeric widths still need their own semantic and backend completion.

### 2.1 break / continue

```azora
for n in 0..<10 {
    if n % 2 != 0 { continue }
    if n == 8 { break }
}
```

### 2.2 Labeled loops

```azora
outer: for i in 0..<3 {
    for j in 0..<3 {
        if i + j == 2 { break:outer }
    }
}
```

## 3. Pattern matching (`when`)

```azora
when value {
    Shape.Circle(r)  -> { return r * r * 3 }
    Shape.Rect(w, h) -> { return w * h }
    Shape.Empty      -> { return 0 }
    else             -> { return -1 }
}
```

- Matches enum variants, variant cases (with destructuring), and value patterns.
- Multiple patterns per arm are comma-separated.
- Exhaustive over a slot/enum, or ends with `else`.

Boolean guard expressions use the following form, or `when true { ... }`:

```azora
fin chosen = when {
    condition -> value
    else -> fallback
}
```

Conditions may be calls
with arguments. They are checked as `Bool` and evaluated in written order until
the first match. Comma-separated guards use short-circuit logical OR, and only
the selected value is evaluated. Guard expressions require `else`: an arbitrary
last condition cannot prove exhaustiveness or become an unconditional fallback.

## 6. Deferred execution

```azora
defer { cleanup() }          // runs at scope exit
```

See also the Error Handling DIP (`try`/`catch`/`throw`) and the Concurrency DIP (`await`).

## 7. Single-statement unsafe scopes

`unsafe statement` creates the same safety boundary as `unsafe { statement }`.
The following statement is outside that boundary. Multiple statements require
the braced form; neither form changes ownership or permits a borrow to outlive
its owner.
