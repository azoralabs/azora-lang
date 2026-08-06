# Azora Error Handling - DIP Implementation Profile

Typed, recoverable errors via error sets (`error`), failable return types
(`T ?! E`), and the `return .Variant` / `try` / `catch` / `rescue` machinery.

The IR type of `T ?! E` is just `T` - errors travel on the existing exception
transport, so failable functions have no representational overhead on success.

## Grammar

```
ERRSET   ::= @[DECORATORS] VISIBILITY fail NAME { Variant, Variant, … }
FAILABLE ::= … : RETURN_TYPE ?! E             // one error set
           | … : RETURN_TYPE ?! [E1, E2]      // several
RAISE    ::= return .Variant                   // fail the current function
           | fail ErrSet.Variant               // raise an error explicitly
           | throw EXPRESSION
PROPAGATE::= try EXPRESSION                     // re-raise on failure
HANDLE   ::= try { … } catch { … }              // block form
           | EXPRESSION catch FALLBACK          // expression form (value on failure)
           | rescue { … }                       // suppress error, run block, continue
```

---

## 1. Declaring an error set

```azora
error SerializationError {
    InvalidSyntax,
    InvalidNumber,
    UnexpectedEnd,
    DuplicateField
}
```

## 2. Failable functions

```azora
func parse(text: String): Int ?! ParseError {
    if !valid(text) {
        return .Invalid        // fails with ParseError.Invalid
    }
    return 0                   // success value
}

func decode(b: Bytes): Value ?! [ParseError, IoError] { … }
```

- `return .Variant` **fails** the function with that variant (the primary form).
- `return value` yields the success value.

## 3. Raising

```azora
error ParseError.Invalid       // explicit raise
throw someError               // throw an error value
```

## 4. Propagation with `try`

`try expr` re-raises the error to the caller (the enclosing function must itself
be failable):

```azora
func outer(): Int ?! E {
    return try inner()        // if inner() fails, outer() fails with the same error
}
```

## 5. Handling

### 5.1 Block form

```azora
try {
    fin v = risky()
    use(v)
} catch {
    std::println("failed")
}
```

### 5.2 Expression form (fallback value)

```azora
fin days = std::daysInMonth(2024, 13) catch -1
fin date = std::localDate(2024, 2, 29) catch LocalDate(0, 1, 1)
```

`expr catch fallback` evaluates to `expr` on success or `fallback` on failure.

### 5.3 `rescue`

```azora
func step() {
    risky()
    rescue { std::println("rescued!") }   // on failure, run block and continue
    std::println("ok")
}
```

## 6. Panics vs. failures

- `panic "message"` / `panic(cond)` - unrecoverable programmer error (see the
  Contracts & Assertions DIP).
- `error` / `?!` - recoverable, typed errors handled by callers.
