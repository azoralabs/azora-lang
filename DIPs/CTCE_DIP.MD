# Azora Compile-Time Code Evaluation (CTCE) - DIP Implementation Profile

CTCE (a.k.a. CTFE) folds constants, evaluates `inline` constructs, runs `type`
functions, and specializes generics/variadics before backend lowering.

## Grammar

```
CONST     ::= (inline | deepinline) (var | fin | let) NAME [: Type] = EXPR
INLINE_IF ::= (inline | deepinline) if COND { DECLS/STMTS } [else { … }]
INLINE_FN ::= inline func NAME(…): Ret { … }
INLINE_FOR::= inline for NAME in ...PACK { … }        // compile-time unroll
INLINE_BLK::= inline { … } | deepinline { … }
TYPE_FN   ::= type NAME(PARAM: Type, PACK: ...Type) [where CLAUSE] { TYPE_STMTS; return TYPE }
SPLICE    ::= inline "source string"          // splice a string as source
```

- `inline` - evaluated at compile time; results are kept in the program.
- `deepinline` - like `inline` but recurses into nested inline constructs and
  folds them away entirely (nothing remains if the branch is not taken).

---

## 1. Compile-time constants

```azora
inline fin SIZE = 8
inline fin AREA = SIZE * SIZE      // folded to 64
var buf = Array<Int, SIZE>          // used in a dependent type
```

Constants (Bool/Int/etc.) are visible both at compile time (`inline if DEBUG`)
and at run time (`fin d = DEBUG`).

## 2. Inline functions

```azora
inline func square(x: Int): Int { return x * x }
println(square(5))            // folded to 25 at the call site
```

## 3. Conditional compilation

```azora
inline if DEBUG {
    func debugHook() { … }         // present in the IR iff DEBUG
}
deepinline if RELEASE {
    // fully removed when RELEASE is false
}
```

## 4. Compile-time loops

```azora
inline for Field in ...T {
    // unrolled once per pack element at compile time
}
```

## 5. Type properties (`deepinline prop`)

A declaration that computes **a type from types**, evaluated entirely by CTCE:

```azora
@Since("0.1")
deepinline prop promote<...T>: Type where T.length >= 2 && T is Number {
    var result: Type = T.0
    for candidate: Type in T[1...] {
        if candidate::rank > result::rank {
            result = candidate
        }
    }
    return result
}

func<A, B> add(a: A, b: B): promote<A, B> { … }   // used as a type
```

- The name is capitalized, because a use site reads it as the type it produces.
- The result type is always `Type`; returning anything else is an error.
- `...T` is a variadic type pack; `T.length`, `T.0`, `T[1...]` and `Type::rank`
  inspect the pack and its type metadata.
- `where … is Spec` bounds every pack member to a spec.
- Every binding is written out (`var result: Type = …`): nothing is inferred.
- All four binding keywords apply - `var`/`val` rebind, `let`/`fin` do not.
- The body may branch with a statement `if`, loop with `for`, and call another
  type property by name (`NumericResult<A, B>`).
- A use site spells it exactly like a generic type: `promote<Int, Double>`.
  There is no separate call syntax - the older `@promote(…)` form was removed.

## 6. Source splicing (`inline "…"`)

```azora
inline "func generated() { return 1 }"    // parse a string as source and splice it
```

Constant strings are expanded at compile time; `$var` interpolation requires an
`inline for … with index` context. (The `mixin` keyword was removed - use
`inline "…"`.)

## 7. Runtime intrinsics

Certain functions (`println`, `toString`, `channel`, `async`, `cancel`) are
runtime intrinsics that CTCE never folds - see the Compiler Intrinsic DIP.
