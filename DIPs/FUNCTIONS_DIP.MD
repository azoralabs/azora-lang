# Azora Functions - DIP Implementation Profile

A complete reference for **function declarations, calls, lambdas, generics,
variadics, contracts, receivers, and extensions** in Azora, reflecting the
current syntax (post July-2026 redesign).

Quick orientation on the sigils this document uses:

| Sigil | Meaning |
|-------|---------|
| `T&`  | immutable borrow of `T` |
| `T!`  | mutable borrow of `T` |
| `T?`  | nullable `T` |
| `T ?! E` | `T` on success, or fails with error set `E` (`T ?! [E1, E2]` for several) |
| `...T` | variadic type pack / spread |
| `name<T, R>` | generic parameters, **after** the name |
| `@name[…]` / `@name![…]` | macro invocation (immutable / mutable variant) |

## Grammar

```
@[DECORATORS] VISIBILITY SAFETY (func | task | flow) NAME <...GENERICS> (...PARAMS) [...RECEIVERS] : RETURN_TYPE ?! [...FAILS]
    where [...CLAUSE]
{ BODY }

DECORATORS   ::= Name | Name(args) | @[Name, Name(args), …]
VISIBILITY   ::= expose | confine | protect            (default: expose)
SAFETY       ::= inline | unsafe | (nothing)
GENERICS     ::= <T, R, N: Int, ...T>                  (after the name; optional)
PARAMS       ::= (name: Type [= default], out name: Type, …)
RECEIVERS    ::= [self: Type&]                          (bracketed extension receiver; optional)
RETURN_TYPE  ::= Type | (nothing → Unit; never inferred - see §1.4)
FAILS        ::= E | [E1, E2]                           (error sets; optional)
CLAUSE       ::= (...T).length >= N  |  T is Spec       (joined with &&; optional)
BODY         ::= { statements }  |  = expression  |  in {…} out {r -> …} scope {…}
```

Every part except `func`/`NAME`/`()` is optional. Method receivers use the block
form `{ self& -> … }` instead of the bracketed `[…]` form (see §5).

---

## 1. Function declarations

### 1.1 Basic form

```azora
func add(a: Int, b: Int): Int {
    return a + b
}
```

- `func` keyword, then the **name**, then optional generics, then `(params)`,
  then an optional `: ReturnType`, then the body `{ … }`.
- With no `: ReturnType`, the return type is **`Unit`**. It is not inferred  - 
  see §1.4.

### 1.2 Expression body

```azora
func square(x: Int): Int = x * x
```

An `=` after the signature makes the single expression the return value. The
return type still has to be written: the expression does not supply it.

### 1.3 Parameters

```azora
func greet(name: String, punctuation: String = "!"): String {
    return "Hello, " + name + punctuation
}
```

- Parameters are `name: Type`, comma-separated.
- A trailing `= expr` gives a **default value**; defaulted params may be omitted
  or passed by name at the call site (`greet("Ann", punctuation: ".")`).

#### Parameter borrows (in/out references)

The borrow lives on the **type**:

```azora
func read(x: Int&) { … }        // immutable borrow - cannot mutate x
func bump(x: Int!) { x = x + 1 } // mutable borrow - writes propagate back
func produce(out result: Int) { result = 42 } // write-only out param
```

- `x: T&` - caller keeps ownership; the callee may read but not write.
- `x: T!` - mutable borrow; assignments inside the callee are visible to the
  caller after the call.
- `out x: T` - write-only output parameter.
- The old `ref x`/`mut x`/`mut ref x` prefixes are **removed**.

### 1.4 Return types are declared, never inferred

A declaration's return type is not something the compiler works out. An omitted
return type is not a gap to be filled - it **is** the annotation, and it says
`Unit`:

```azora
func hello() {                        // : Unit
    std::println("Hello!")
}
```

Returning a value from such a function is an error, and the diagnostic says what
to write:

```azora
func add(a: Int, b: Int) {
    return a + b
}
// line 2: 'add' returns Int but declares no return type - an omitted return
// type means Unit, it is not inferred; declare it as ': Int'
```

```azora
func add(a: Int, b: Int): Int {       // the fix
    return a + b
}
```

A bare `return` (no value) is fine in a `Unit` function - it is an early exit,
not a result.

**A failable function may omit only its ok type.** `?! E` directly after the
parameter list means `Unit ?! E`: the function yields nothing but may fail.

```azora
func div(x: Int, y: Int) ?! MathError {          // Unit ?! MathError
    if y == 0 { return .DivisionByZero }
    std::println(x / y)
}

func div(x: Int, y: Int): Int ?! MathError {     // yields a value → declare it
    return if y == 0 { .DivisionByZero } else { x / y }
}
```

`return if` and `return when` desugar to their statement forms with every branch
returning (§1.5.1), which is why a branch accepts `.Variant`: failing is
something the function *does*, not a value a branch produces.

**The rule applies to `prop` as well** - a computed property is a declaration,
so it declares its type too (see the Packs, Impls & Specs DIP).

Two things are deliberately outside the rule, because neither has a declaration
to read:

| Form | Result type | Why |
|------|-------------|-----|
| **lambda** - `{ n: Int -> n * 2 }` | inferred from the body | there is no declaration; the body is all there is |
| **operator overload** - `oper.* [self: Self&] { … }` | inferred from the body | the result is fixed by the operator's contract, not chosen by the author, and some are not nameable at the declaration (the deref of a variadic pack has no spelling for its member's type) |

See `DIPs/DO_NOT_INFER_RETURN_TYPE.MD`.

### 1.5 Return type and errors

```azora
func parse(text: String): Int ?! ParseError {
    if !valid(text) {
        return .Invalid       // fail with the ParseError.Invalid variant
    }
    return 0                  // success value
}
func decode(b: Bytes): Value ?! [ParseError, IoError] { … }  // multiple error sets
```

- `T ?! E` - succeeds with `T` or fails with a variant of error set `E`.
- `return .Variant` **fails** the function with that error variant.
- `return value` yields the success value.
- The old `fail return .Variant` form is **removed** (`error X` still throws).

#### 1.5.1 Branching in return position

`return if …` and `return when …` are lowered to the statement forms, with each
branch returning its own value:

```azora
func sign(n: Int): Int {
    return if n < 0 { -1 } else if n == 0 { 0 } else { 1 }
}

func div(x: Int, y: Int): Int ?! MathError {
    return if y == 0 { .DivisionByZero } else { x / y }   // a branch may fail
}

func twice(n: Int): Int ?! MathError {
    return when n {
        0 -> .Bad
        else -> n * 2
    }
}
```

- Every branch carries a value; a `return if` needs its `else`.
- A branch may open with statements and end in its value:
  ```azora
  return if y == 0 { 0 } else {
      fin doubled = y * 2
      doubled + 1
  }
  ```
- A branch may instead be `.Variant`, which **fails** the function. That is why
  the lowering exists: as an if-*expression* a branch is a value, and failing is
  not a value.
- Nested if-expressions still read as values: `return if a { 1 } else { if b { -1 } else { 0 } }`.

### 1.6 Visibility

```azora
expose func api() { … }    // default; importable everywhere
confine func helper() { … } // private to this compilation unit
protect func internal() { … } // importable within the declaring folder
```

`expose` is the default and may be omitted. (`intern`/`shared`/`weak` keywords
were removed.)

### 1.7 Modifiers

```azora
inline func fastPath(x: Int): Int { … }   // inlined at each call site (CTCE)
unsafe func raw(p: Int*): Int { … }        // requires an unsafe calling context
```

---

## 2. Generics

Generic parameters go **after the name**, in `< >`:

```azora
func identity<T>(value: T): T { return value }
func convert<T, R>(value: T): R { … }
func pair<A, B>(a: A, b: B): Tuple<A, B> { … }
```

- The old `func<T> name` (generics before the name) is **removed** and rejected.
- The same after-the-name rule applies to every declaration: `pack Box<T>`,
  `spec Serializer<T>`, `enum Result<T>`, `variant Shape<T>`.

### 2.1 Const-generic value parameters

```azora
func fill<T, N: Int>(value: T): Array<T, N> { … }
```

A `name: Int` type parameter is a compile-time value, supplied as a
`TypeRef.Const` argument and folded into dependent types (`Array<T, N>`).

---

## 3. Variadics

The variadic pack uses the **prefix** `...T` spelling everywhere:

```azora
func tupleOf<...T>(...elements: ...T): Tuple<...T> { … }

func sumAll<...T>(first: Int, rest: ...T): Int {
    var total = first
    for x in rest { total = total + x }
    return total
}
```

- `<...T>` declares a variadic type pack.
- `...elements: ...T` is a variadic parameter (a homogeneous vararg is
  `...values: T`).
- `...arr` spreads an array into a variadic call: `sumAll(1, ...more)`.
- The suffix form `T...` is **rejected**; always use the `...T` prefix.

---

## 4. `where` clauses (constraints)

A `where` clause after the signature constrains type/variadic parameters. It sits
between the return type and the body (or after a variadic pack).

### 4.1 Variadic length

```azora
func tupleOf<...T>(...elements: ...T): Tuple<...T>
    where (...T).length >= 1 { … }

pack Single<...T> where (...T).length >= 1 { … }
```

- `where (...pack).length >= N` requires the variadic pack to have at least `N`
  elements (checked when the call is monomorphized).
- The older `where pack.length >= N` spelling (no parens) is still accepted.

### 4.2 Spec conformance (type properties)

A compile-time `deepinline prop` accepts both a length constraint and an
`is Spec` conformance bound, combined with `&&`:

```azora
deepinline prop Promote<...T>: Type where T.length >= 2 && T is Number {
    // choose the widest numeric type in the pack …
}
```

- `T is Spec` requires every type in the pack to conform to `Spec`.
- Multiple constraints are joined with `&&`.

(Ordinary runtime functions currently support the `.length` form; the `is Spec`
bound is evaluated for type properties - see the CTCE DIP.)

---

## 4. Contracts

Preconditions (`in`) and postconditions (`out`) precede a `scope { … }` body:

```azora
func alloc(size: Int): Int ?! AllocError
in {
    assert size > 0 { "Allocation size must be positive" }
} scope {
    // … the real body …
    return position
}
```

- `in { … }` runs before the body; each `assert cond { "msg" }` is checked.
- `out { result -> … }` binds the return value as `result` and checks
  postconditions after the body.
- When contracts are present, the body is introduced by `scope { … }`.

---

## 5. Receivers (methods, operators, context parameters)

### 5.1 Methods in `impl`

The receiver is always named **`self`**, and its borrow is a postfix sigil:

```azora
pack Counter { var value: Int }

impl Counter {
    func get(): Int      { self& -> return self.value }          // immutable self
    func bump()          { self! -> self.value = self.value + 1 } // mutable self
}
```

- `{ self& -> … }` - immutable receiver (cannot mutate fields).
- `{ self! -> … }` - mutable receiver.
- A bare `{ self -> … }` defaults to mutable.
- Mutating through `self&` is a compile error (use `self!`).
- The old `ref self` / `mut ref self` prefixes are **removed**.

### 5.2 Receiver operands (context parameters)

Extra operands may follow the receiver in the block header, each carrying its own
type and/or borrow:

```azora
impl Robot {
    func hit(): Int { self&, amount: Int, target: Animal& ->
        return self.power + amount + target.hp
    }
}
```

The header is `{ self<borrow>, name: Type, name: Type& -> … }`. Operands with a
`: Type` are typed; a bare `name&`/`name!` operand is `Any` with that borrow.

### 5.3 Operators

```azora
impl oper[] for ArrayList<T> { self&, index ->
    return self.data[index]
}
impl oper+ by List<T> for ArrayList { self&, rhs: List<T> -> … }
```

Operator overloads use the same receiver-block header inside a standalone
`impl oper<sym> for Type { … }`.

---

## 6. Extension functions (bracketed receiver)

A free function becomes an **extension method** by declaring a bracketed receiver
after the parameter list:

```azora
func peek()[self: Counter&]: Int {
    return self.value
}
func unwrap<T>()[self: Box<T>&]: T {
    return self.item
}
```

- The receiver goes in `[self: Type<…><borrow>]` **after `(params)`**, before the
  return type.
- The borrow lives on the receiver type: `Counter&` (immutable), `Counter!`
  (mutable).
- Generics still go on the function name (`unwrap<T>`).

Callable two ways:

```azora
counter.peek()               // method-call syntax
with counter { peek() }      // contextual: a bare call resolves to counter.peek()
```

The old `func Type.method()` extension form is **removed**.

---

## 7. Lambdas and function values

### 7.1 Lambda literals

```azora
var double = { x: Int -> x * 2 }
std::println(double(5))                    // 10
```

- Syntax: `{ param: Type[, …] -> body }`. The body's last expression (or a
  `return`) is the result.
- A lambda's result type **is** inferred from its body - the one place in the
  language where a result type is worked out rather than written, because a
  lambda has no declaration to read (§1.4).
- Lambdas capture surrounding variables (closures):
  ```azora
  func makeAdder(n: Int): (Int) -> Int {
      return { x: Int -> x + n }
  }
  ```

### 7.2 Function types

```azora
func apply(f: (Int) -> Int, x: Int): Int { return f(x) }
func forEach<T>(xs: Array<T>, action: (T) -> Unit) { … }
```

A function type is `(ParamTypes) -> ReturnType`. Zero-arg is `() -> R`.

### 7.3 Variadic lambdas

```azora
var sum = { ...xs: ...Int -> /* xs is an Array<Int> */ }
```

### 7.4 `task` / `flow` callables

```azora
task delayed(ms: Int): Int { … }   // structured async function; returns Task<Int>
flow squares(n: Int): Int {        // generator; `for x in squares(5)` iterates
    for i in 0..<n { yield i * i }
}
```

- `task name(…)` - an asynchronous function, awaited with `await`.
- `flow name(…)` - a generator; `yield` emits values, iterated by `for`.
- Both take generics after the name and the same parameter/receiver forms as
  `func`.

---

## 8. Decorators on functions

```azora
@Experimental(since: "0.1")
func api() { … }

@[Component, System(.Update)]        // grouped: applies both in one row
func moveSystem(world: World&) { … }
```

- `@Name` / `@Name(args)` applies a single decorator.
- `@[A, B(args), C]` applies several decorators at once - equivalent to stacking
  `@A` / `@B(args)` / `@C`.
- Decorator names must start with an uppercase letter.

---

## 9. Calls

```azora
add(1, 2)                       // positional
greet("Ann", punctuation: ".")  // named argument
sumAll(1, ...more)              // spread a variadic
value.method(x)                 // method / extension call
Type::path(x)                   // realm-qualified free function
@vec[1, 2, 3]                   // macro invocation (immutable)
@vec![1, 2, 3]                  // macro invocation (mutable variant)
```

### 9.1 Call-site borrows

An argument may carry an explicit borrow marker:

```azora
read(text&)     // pass an immutable borrow
write(text!)    // pass a mutable borrow
```

`x&` / `x!` at an argument position annotate the borrow (currently erased; the
binary `a & b` is unaffected because a borrow marker is always followed by a
delimiter).

---

## 10. Summary of removed / changed forms

| Old | New |
|-----|-----|
| `func<T> name(…)` | `func name<T>(…)` |
| `pack<T> Box` | `pack Box<T>` |
| `ref T` / `mut ref T` | `T&` / `T!` |
| `ref self` / `mut ref self` | `{ self& -> }` / `{ self! -> }` |
| `func Type.method()` | `func method()[self: Type&]` |
| `T!E` / `T![A, B]` | `T ?! E` / `T ?! [A, B]` |
| `fail return .X` | `return .X` |
| `name![…]` (macro) | `@name[…]`, mutable `@name![…]` |
| `mut @vec[…]` | `@vec![…]` |
| `intern` / `shared` / `weak` keywords | removed (memory kinds via `std.memory`, planned) |
| omitted return type → inferred | omitted return type → **`Unit`** (§1.4); lambdas and operators still infer |
