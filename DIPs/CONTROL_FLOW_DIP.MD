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
JUMP   ::= break [@label] | continue [@label]
LABEL  ::= @label for … / @label loop …                // labeled loops
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

### 2.1 break / continue

```azora
for n in 0..<10 {
    if n % 2 != 0 { continue }
    if n == 8 { break }
}
```

### 2.2 Labeled loops

```azora
@outer for i in 0..<3 {
    for j in 0..<3 {
        if i + j == 2 { break @outer }
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

## 6. Deferred execution

```azora
defer { cleanup() }          // runs at scope exit
```

See also the Error Handling DIP (`try`/`catch`/`throw`) and the Concurrency DIP (`await`).
