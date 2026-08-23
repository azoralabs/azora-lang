# Azora Functions - DIP Implementation Profile

A complete reference for **function declarations, calls, lambdas, generics,
variadics, contracts, receivers, and extensions** in Azora, reflecting the
normative syntax, including the August-2026 receiver-head redesign.

Quick orientation on the sigils this document uses:

| Sigil | Meaning |
|-------|---------|
| `T&`  | immutable borrow of `T` |
| `T!`  | mutable borrow of `T` |
| `T&|source|` / `T!|source|` | borrow whose origin is an input binding |
| `T?`  | nullable `T` |
| `T ?! E` | `T` on success, or fails with error set `E` (`T ?! [E1, E2]` for several) |
| `...T` | variadic type pack / spread |
| `func<T, R> name` | function generic parameters, immediately **after `func`** |
| `@name[…]` / `@name![…]` | macro invocation (immutable / mutable variant) |

## Grammar

```
@[DECORATORS] VISIBILITY SAFETY ASYNC? func GENERICS? RECEIVER_HEAD? NAME (...PARAMS) : RETURN_TYPE ?! [...FAILS]
    where [...CLAUSE]
{ BODY }

DECORATORS   ::= Name | Name(args) | @[Name, Name(args), …]
VISIBILITY   ::= expose | confine | protect            (default: expose)
SAFETY       ::= inline | unsafe | (nothing)
ASYNC        ::= async
GENERICS     ::= <T, R, N: Int, ...T>                  (immediately after func; optional)
PARAMS       ::= (name: Type [= default], out name: Type, …)
RECEIVER_HEAD ::= NAMED_RECEIVER_GROUP '.'
                | UNNAMED_RECEIVER_TYPE '.'
                | UNNAMED_RECEIVER_GROUP '.'
                | IMPL_CAPABILITY '.'
NAMED_RECEIVER_GROUP ::= '(' NAMED_RECEIVER (',' NAMED_RECEIVER)* ')'
UNNAMED_RECEIVER_GROUP ::= '(' TYPE ',' TYPE (',' TYPE)* ')'
NAMED_RECEIVER ::= NAME ':' TYPE                       (for example self: T&, owner: U!, value: V)
UNNAMED_RECEIVER_TYPE ::= TYPE                         (for example T&, Box<T>!, Request)
IMPL_CAPABILITY ::= '&' | '!'                          (inside impl/spec; means Self& or Self!)
RETURN_TYPE  ::= Type | (nothing → Unit; never inferred - see §1.4)
FAILS        ::= E | [E1, E2]                           (error sets; optional)
CLAUSE       ::= (...T).length >= N  |  T is Spec       (joined with &&; optional)
BODY         ::= { statements }  |  = expression  |  in {…} out {r -> …} scope {…}
```

Every part except `func`/`NAME`/`()` is optional. A receiver-free declaration
has no receiver head and no dot: `func hello() {}`. A named receiver group uses
parentheses and must be followed by the dot introducing the function name:
`func<T> (self: T&).hello() {}`. A single unnamed receiver omits the group:
`func<T> T&.hello() {}`.

Constructors and destructors are dedicated lifecycle declarations, not
ordinary functions using this receiver grammar. Their complete surface is
`ctor ()`, `ctor .(...)`, `dtor ()`, and `dtor .()`. Borrowed spellings such as
`ctor &.()` or `dtor !.()` are invalid; `ctor .(...)` receives initialization
privilege directly and therefore does not need a mutable-reference receiver.

---

## 1. Function declarations

### 1.1 Basic form

```azora
func add(a: Int, b: Int): Int {
    return a + b
}
```

- `func` keyword, then optional generics, then an optional receiver head, then
  the **name** and `(params)`,
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

#### How arguments reach parameters

Three rules, holding identically for free functions and for methods (a method's
`self` is parameter 0, and its own parameters follow):

1. **A named argument takes its own parameter.** Any parameter it skips takes
   that parameter's default - the argument does not slide left to fill the gap.

   ```azora
   func f(a: Int = 1, b: Int = 2): Int { return a * 10 + b }

   f(b: 5)   // 15 - a defaults to 1. NOT 52.
   ```

2. **A defaulted parameter may be omitted**, positionally or by name.

3. **A block written after the parentheses is the last parameter's argument**,
   whatever else the call supplied - it is not a positional argument competing
   for the leftmost free parameter.

   ```azora
   func k(a: Int = 3, body: () -> Unit): Int { … }

   k { … }              // a = 3,  body = the block
   k(7) { … }           // a = 7,  body = the block
   ```

   The rule applies only when the call is short an argument and the last
   parameter takes a function, so a lambda passed positionally in any other
   position is untouched. Writing `body:` explicitly disables it.

A method may also declare **its own type parameters**, independent of the
type's:

```azora
impl P {
    func<R> &.each(label: String = "x", body: () -> R): String { … }
}
```

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
    println("Hello!")
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
    println(x / y)
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

Function generic parameters go immediately **after `func`**, in `< >`:

```azora
func<T> identity(value: T): T { return value }
func<T, R> convert(value: T): R { … }
func<A, B> pair(a: A, b: B): Tuple<A, B> { … }
```

- The old `func name<T>` spelling is **removed** and rejected.
- This is a callable-declaration rule. Nominal declarations retain generics
  after the declared name: `pack Box<T>`, `spec Serializer<T>`,
  `enum Result<T>`, and `variant Shape<T>`.
- Modifiers remain before `func`: `async func<T> load(...)`,
  `unsafe func<T> read(...)`, and `bridge func<T> convert(...)`.

### 2.1 Const-generic value parameters

```azora
func<T, N: Int> fill(value: T): Array<T, N> { … }
```

A `name: Int` type parameter is a compile-time value, supplied as a
`TypeRef.Const` argument and folded into dependent types (`Array<T, N>`).

---

## 3. Variadics

The variadic pack uses the **prefix** `...T` spelling everywhere:

```azora
func<...T> tupleOf(...elements: ...T): Tuple<...T> { … }

func<...T> sumAll(first: Int, rest: ...T): Int {
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
func<...T> tupleOf(...elements: ...T): Tuple<...T>
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
deepinline prop promote<...T>: Type where T.length >= 2 && T is Number {
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

## 5. Receivers

### 5.1 Named function receiver heads

Named functions declare receivers between the optional generic list and the
function name. The dot is part of the receiver-head syntax:

```azora
func hello() {}                              // no receiver

func<T> (self: T&).hello() {}                // immutable borrow
func<T> (self: T!).hello() {}                // mutable borrow
func<T> (self: T).hello() {}                 // owned receiver
func<T, U> (first: T&, second: U&).hello() {} // multiple receivers
```

- `T&` borrows immutably. The function may read through that receiver but may
  neither mutate nor consume it.
- `T!` is an exclusive mutable borrow. The function may mutate through it, but
  may not retain it beyond the borrow's lifetime.
- Bare `T` transfers ownership into the call. Unless the function returns or
  stores the value in an allowed destination, it is consumed when the call
  finishes.
- The full form names and types every receiver inside `(...)`.
- `self` is conventional, not required. `(type: T&)` binds the receiver as
  `type`; it does not synthesize an additional `self` name.
- Multiple explicitly named receivers preserve their declared order and are
  available through those names.
- The receiver head is not part of the ordinary argument tuple. Receiver
  values are supplied by member/contextual resolution; `(...)` contains only
  the function's ordinary arguments.
- No-receiver functions do not write a receiver head, `.`, or synthetic `self`.

The capability belongs to each receiver independently. For example,
`(catalog: Catalog&, cache: Cache!, request: Request)` keeps `catalog` at the
caller, exclusively borrows `cache`, and moves `request` into the call. Failure
does not roll back a move or mutation: normal `?!` unwinding/drop rules apply.
An owned receiver cannot be silently copied merely because the same function
also has borrowed receivers.

### 5.2 Methods in `impl`

An `impl` method uses the same declaration grammar; it does not switch to a
receiver hidden in the body:

```azora
pack Counter { var value: Int }

impl Counter {
    func &.get(): Int {
        return self.value
    }

    func !.bump() {
        self.value = self.value + 1
    }
}
```

- `Self` denotes the implementing type.
- Inside an `impl` or `spec`, `func &.name()` means
  `func (self: Self&).name()` and `func !.name()` means
  `func (self: Self!).name()`. Both introduce implicit `self` in the body.
- There is no bare owned-capability spelling such as `func .consume()`. Write
  `func Self.consume()` for implicit owned `self`, or
  `func (self: Self).consume()` for the explicit form.
- A method with its own type parameters puts them after `func`, before the
  receiver head: `func<R> &.map(f: (Item&) -> R): List<R>`.
- Mutating through `Self&` is an error; declare `Self!` when mutation is needed.
- The old body receiver header (`{ self& -> ... }`) and the old trailing
  receiver (`func get()[self: Self&]`) are removed from named functions.

### 5.3 Unnamed receiver shorthands and implicit `self`

A receiver type may replace a one-entry named group. The compiler creates the
binding `self` automatically:

```azora
func<T> T&.read() {}       // full: func<T> (self: T&).read() {}
func<T> T!.update() {}     // full: func<T> (self: T!).update() {}
func<T> T.consume() {}     // full: func<T> (self: T).consume() {}
```

For two or more unnamed receivers, parentheses group the types and the compiler
creates one positional receiver view named `self`:

```azora
func<T, U> (T&, U&).merge() {
    read(self.0)       // T&
    read(self.1)       // U&
}
```

This `self` is not an allocated runtime tuple and is not an ordinary tuple
argument. It is a compiler-owned positional view over the resolved receivers.
Each slot keeps its own borrow/ownership capability, evaluation span, and
borrow origin. The view itself cannot be moved, stored, returned, or captured;
individual owned slots may be consumed explicitly when ordinary ownership rules
allow it.

A receiver group is either entirely named (`name: Type`) or entirely unnamed
(`Type`). Mixing the two forms is rejected. A one-entry unnamed receiver is
written without parentheses (`T&.read()`); the formatter diagnoses or rewrites
the redundant `(T&).read()` form. If the intended single receiver is itself a
tuple type, use the named form to remove ambiguity:
`func (self: (T, U)&).inspect()`.

### 5.4 Multiple and contextual receivers

A receiver head may list more than one value:

```azora
func<T, U> (source: T&, destination: U!).copyTo(limit: Int) {
    // source is shared-read; destination is exclusively mutable
}
```

Receiver base types must be pairwise distinct. Capability does not make a type
distinct, so all of these are duplicates of the same receiver type:

```azora
func (first: Logger&, second: Logger&).bad()       // rejected
func (read: Logger&, write: Logger!).alsoBad()    // rejected
func<T> (left: T&, right: T).stillBad()           // rejected
```

For this check the compiler removes the outer receiver capability (`&`, `!`, or
owned), expands type aliases, resolves `Self`/associated types, and compares the
canonical static types including their generic arguments. Thus `Box<Int>` and
`Box<String>` are distinct, while `typealias Audit = Logger` does not create a
second receiver type. Subtyping or overlapping runtime implementations do not
make two different declared types equal.

Independent generic parameters remain legal, as in `source: T&` and
`destination: U!`, but the declaration carries an implicit distinctness
requirement. After inference/substitution, an attempted specialization with
`T == U` is inapplicable and receives a duplicate-context-type diagnostic. This
prevents aliases or generic inference from bypassing the invariant without
requiring a user-visible `T != U` constraint syntax.

All receiver borrows are acquired before entry and released together when the
call finishes, unless a returned borrow is explicitly tied to one receiver by
the lifetime rules. Aliasing is checked across the entire group: the same
storage cannot simultaneously satisfy a mutable receiver and another live
receiver. Receiver order is stable for overload identity and diagnostics, but
does not imply evaluation sequencing beyond the normal call evaluation rules.

The `using`/context-resolution system may supply these receivers, but it must
find one unambiguous value for every declared receiver. This DIP does not use
the lambda/context capture form `[context: Ctx&] { ... }` as a function
declaration; that is a separate expression grammar.

A dot call supplies the sole receiver naturally: `counter.get()`. A declaration
with several receivers has two non-value call forms:

```azora
using (source, destination) {
    copyTo(10)                         // lexical receiver context
    copyTo(20)
}

{source, destination}.copyTo(10)      // one direct grouped-receiver call
```

The parentheses after `using` are a keyword-delimited receiver list, not a tuple
expression. The braces before `.copyTo` are source-level group syntax, not a
runtime value. Consequently `(source, destination).copyTo()` calls a method on
one tuple, while `[source, destination].copyTo()` calls a method on one
collection; neither supplies two receivers.

`using` evaluates each receiver expression once, left to right, when entering
the lexical context and keeps a stable receiver slot for the block. A direct
brace call evaluates each entry once, left to right, immediately before the
call. Neither form allocates or drops an intermediate tuple/group. Function
entry acquires each requested borrow; sequential calls in a `using` block borrow
sequentially. An owned slot becomes unavailable after the call that consumes it,
so a second consuming call in the same block is a normal use-after-move error.

If any receiver is owned, overload resolution must select a source
that can be moved, and the source becomes unusable after the call. Context
resolution never inserts `clone()`, `take`, or a borrow-strength conversion.

The same type-uniqueness rule applies to the active receiver list created by a
single `using` clause or direct brace receiver group. If two values have the
same canonical receiver base type, the clause is rejected rather than resolving
by name or position. Put them in a
single wrapper context, make one an ordinary argument, or introduce a genuinely
distinct nominal role type. A nested `using` of the same type shadows the outer
receiver for its lexical body; it does not create two simultaneously eligible
receivers.

A returned borrow identifies its origin as usual:

```azora
func (self: Buffer&).first(): Byte&|self|
func<A, B> (left: A&, right: B&).choose(flag: Bool): Item&|left, right|
```

The origin list limits the result lifetime to the selected receiver origins.
Returning a borrow rooted in an owned receiver is legal only when ownership of
the backing storage also escapes in a form that keeps that storage alive; a
plain borrowed return may not outlive a consumed receiver.

### 5.5 Properties and operators

Computed properties reuse the function receiver-head *shape*, but are
observational: every instance/context receiver must be a shared borrow. They
have no ordinary `(...)` argument list:

```azora
impl User {
    prop &.name: String&|self| = self.name.&
}

prop User&.displayName: String
prop (model: Model&, theme: Theme&).label: String
```

- Inside an `impl`/`spec`, `prop &.name` expands to
  `prop (self: Self&).name`. There is no `prop !.` or owned `prop Self.` form.
- Outside an implementing context, `prop Type&.name` introduces implicit
  `self: Type&`; the full named spelling is `prop (self: Type&).name`.
- Generic property parameters remain attached to the property name:
  `prop &.into<T>: T`.
- Multiple named or unnamed property receivers follow the same type-uniqueness,
  `using`, direct brace-group, and positional `self.N` rules as functions, but
  every receiver must be `T&`. A `T!` or owned `T` slot is rejected.
- A borrowed property result uses an origin fence, for example
  `String&|self|`; bracket-delimited origin fences are rejected.
- A receiver-free `prop name: T` is a static/type property and has no `self`.
- Reading a property may calculate a result and mutate local temporaries, but
  it cannot mutate or consume a receiver. Declare `func !.name()` for mutation
  and `func Self.name()` for consumption; the call parentheses make that effect
  visible.

Operators retain their dedicated declaration grammar:

```azora
impl oper[] for ArrayList<T> { self&, index ->
    return self.data[index]
}
impl oper+ by List<T> for ArrayList { self&, rhs: List<T> -> … }
```

This receiver-head decision is normative for `func`, `async func`, and `prop`.
It does not change `oper` receiver syntax.

---

## 6. Extension functions

A free function becomes an **extension method** by declaring a receiver head
before the dot and name:

```azora
func Counter&.peek(): Int {
    return self.value
}
func<T> Box<T>&.unwrap(): T {
    return self.item
}
```

- A named receiver goes in `(name: Type<…><borrow>)` immediately after optional
  function generics and is followed by `.` and the function name.
- A single unnamed receiver type may omit the name/group and synthesizes
  `self`: `func<T> Box<T>&.unwrap()`.
- The borrow lives on the receiver type: `Counter&` (immutable), `Counter!`
  (mutable), or bare `Counter` (owned).
- Function generics go immediately after `func`: `func<T>`, not `unwrap<T>`.

Callable two ways:

```azora
counter.peek()               // method-call syntax
using counter { peek() }     // contextual: a bare call resolves to counter.peek()
```

`func Type.method()` is no longer a separate static-extension grammar. It is the
owned unnamed-receiver shorthand and introduces implicit `self: Type`.
Borrowing extensions must state `Type&` or `Type!`.

---

## 7. Lambdas and function values

### 7.1 Lambda literals

```azora
var double = { x: Int -> x * 2 }
println(double(5))                    // 10
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
func<T> forEach(xs: Array<T>, action: (T) -> Unit) { … }
```

A function type is `(ParamTypes) -> ReturnType`. Zero-arg is `() -> R`.

### 7.3 Variadic lambdas

```azora
var sum = { ...xs: ...Int -> /* xs is an Array<Int> */ }
```

### 7.4 Async functions

```azora
async func delayed(ms: Int): Int { … }
```

- `async func name(…)` is the only asynchronous declaration form and is awaited
  with `await`.
- Async functions put generics after `func` and use the same receiver-head forms
  as synchronous functions: `async func<T> T&.load()`.
- `task name(...)` and `flow name(...)` are removed declaration syntaxes. A
  library type such as `Task<T>` or `Flow<T>`, and an ordinary builder expression
  named `flow { ... }`, do not create callable declaration keywords.

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
Type::path(x)                   // scope-qualified free function
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
| `func name<T>(…)` | `func<T> name(…)` |
| `pack<T> Box` | `pack Box<T>` |
| `ref T` / `mut ref T` | `T&` / `T!` |
| `ref self` / `mut ref self` | `(self: Self&)` / `(self: Self!)`, or `&.` / `!.` in an impl/spec |
| `func method()[self: Type&]` or `func [self: Type&].method()` | `func (self: Type&).method()` or `func Type&.method()` |
| `prop name[self: Self&]: T` | `prop &.name: T` |
| bracket-delimited borrow origins | `T&|self|` / `T!|source|` |
| `T!E` / `T![A, B]` | `T ?! E` / `T ?! [A, B]` |
| `fail return .X` | `return .X` |
| `name![…]` (macro) | `@name[…]`, mutable `@name![…]` |
| `@vec[…]` | `@vec![…]` |
| `intern` / `shared` / `weak` keywords | removed (memory kinds via `std.memory`, planned) |
| omitted return type → inferred | omitted return type → **`Unit`** (§1.4); lambdas and operators still infer |
| `ctor(args)` / `dtor { … }` (old instance forms) | `ctor .(args)` / `dtor .() { … }` |
| `ctor &.` / `ctor !.` / `dtor &.` / `dtor !.` | removed; lifecycle is static `()` or owned instance `.` only |
