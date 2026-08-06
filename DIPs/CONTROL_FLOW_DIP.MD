# Azora Control Flow - DIP Implementation Profile

Conditionals, loops, pattern matching, and the alternating `flip`/`flop`
construct.

## Grammar

```
IF     ::= if COND { … } [else if COND { … }] [else { … }]
IFEXPR ::= if COND { EXPR } else { EXPR }              // as an expression
WHILE  ::= while COND { … }
FOR    ::= for NAME in ITERABLE { … }
LOOP   ::= loop { … }                                  // infinite; exit via break
WHEN   ::= when SCRUTINEE { PATTERN -> { … } … [else -> { … }] }
GUARD  ::= guard COND else { … }                       // early-exit
JUMP   ::= break [@label] | continue [@label]
LABEL  ::= @label for … / @label loop …                // labeled loops
FLIP   ::= flip { … } flop { … }                       // alternating execution
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

## 4. Guard

```azora
func process(x: Int?): Int {
    guard x != null else { return 0 }
    return x + 1
}
```

`guard COND else { … }` runs the `else` block (which must exit) when the condition
is false, and otherwise continues.

## 5. flip / flop

Alternating execution: successive evaluations run the `flip` block, then the
`flop` block, then `flip` again, and so on.

```azora
for i in 0..<4 {
    flip { std::println("A") } flop { std::println("B") }   // A B A B
}
```

## 6. Deferred execution

```azora
defer { cleanup() }          // runs at scope exit
```

See also the Error Handling DIP (`try`/`catch`/`throw`), the Flows DIP
(`yield`), and the Concurrency DIP (`await`).
