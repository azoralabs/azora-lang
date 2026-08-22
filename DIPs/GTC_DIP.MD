# Groups, Typed Collection Literals, and Tuples

> **Status:** Proposal
>
> **Short name:** GTC DIP
>
> **Scope:** The roles of `{}`, `[]`, and `()` in grouping, collection literals,
> tuples, and destructuring.

## 1. Summary

This proposal gives the three delimiter pairs a consistent primary meaning:

| Delimiter | Primary meaning | Representative forms |
|---|---|---|
| `{ ... }` | A syntactic group or fan-out | `import a.b::{x, y}`, `fin {x, y} = 0`, `self.{x, y} = {1, 2}` |
| `[ ... ]` | A collection literal or collection access | `[1, 2, 3]`, `["a": 1]`, `items[i]` |
| `( ... )` | A tuple or positional product | `(1, "two")`, `(Int, String)`, `fin (x, y) = pair` |

These are primary meanings, not exclusive lexical ownership. Braces still
delimit blocks and lambda bodies, brackets still perform indexing and spell
index operators, and parentheses still delimit calls, parameters, conditions,
and a single grouped expression. Grammar context and commas disambiguate those
uses without lexer changes.

The central distinctions are:

- A **group** is syntax that expands one declaration or operation over several
  names or targets. It is not a runtime value.
- A **collection literal** is a contextual literal. Its target type defines how
  it is built through a static `literal` factory.
- A **tuple** is a runtime value with fixed arity and possibly heterogeneous
  element types.
- A **destructuring pattern** consumes, copies, or borrows one runtime value and
  binds or assigns its components.

This means the following are deliberately different:

```azora
fin {x, y} = next()       // repeated fan-out: next() runs twice
fin {x, y} = {nextX(), nextY()} // paired fan-out: one expression per binding
fin {x, y} <- next()      // broadcast fan-out: next() runs once, then its result is distributed
fin (x, y) = nextPair()   // destructuring: nextPair() runs once, then its components bind

self.{x, y} = alloc .()   // repeated fan-out: one allocation per target
self.{x, y} = {1, 2}      // paired fan-out: x receives 1, then y receives 2
self.{x, y} <- measure()  // broadcast fan-out: measure once, copy/borrow to both targets
(self.x, self.y) = pair   // parallel positional assignment from one tuple-like value
```

The two group operators intentionally have different evaluation semantics:

| Operator | RHS evaluations | Meaning |
|---|---:|---|
| `=` with one expression | once per group entry | Repeat one expression independently |
| `=` with `{e0, e1, ...}` | once per matching expression | Pair targets and sources by position |
| `<-` | exactly once total | Broadcast one result when ownership permits duplication or aliasing |

`<-` never grants an implicit `Copy`, never calls `.clone()`, never creates a
reference-counted owner, and never turns one exclusive owner into several.

## 2. Motivation

Azora currently overloads brackets for several unrelated concepts:

- grouped bindings and grouped assignment targets;
- positional right-hand-side groups;
- compile-time type lists and grouped implementation targets;
- contextual receiver lists;
- collection indexing and index operators;
- map literals, while list literals are provided by macros;
- receiver and capture syntax in lambdas.

At the same time, parentheses only group expressions even though the language
already has a `Tuple` pack and tuple types in its AST and IR. This creates an
unfortunate inversion: brackets often look like collection values but are not
values, while tuple values require `tupleOf(...)` or a macro.

The new design makes ordinary source read according to familiar expectations:

```azora
fin numbers: List<Int> = [1, 2, 3]
fin ages: Map<String, Int> = ["Ada": 36, "Grace": 85]
fin identity = (7, "azora", true)
fin (id, name, enabled) = identity
```

It also removes collection construction from the macro system. Lexing, parsing,
type inference, ownership checking, IR lowering, and diagnostics all understand
collection literals directly.

## 3. Goals

This proposal has the following goals:

1. Make bare `[...]` the native collection literal syntax.
2. Make `[:]` the unambiguous empty associative literal.
3. Make `(a, b, ...)` a native tuple literal and `(A, B, ...)` a tuple type.
4. Make tuple syntax type-identical to the standard `Tuple<...T>` bridge pack.
5. Use positional tuple patterns for declarations, assignments, loops, and
   other pattern positions.
6. Provide explicit name-based pack destructuring with aliases.
7. Move non-value grouping and fan-out from brackets to braces.
8. Let `List`, `Map`, `Set`, and custom types define how contextual literals
   create them.
9. Avoid allocating an intermediate list merely to pass a literal to another
   collection constructor.
10. Preserve Azora's ownership rules, evaluation order, and explicit move model.
11. Give malformed or ambiguous literals targeted diagnostics.
12. Provide a mechanical migration path from the existing grammar.
13. Distinguish repeated group initialization (`=`) from single-evaluation
    broadcast (`<-`) without implicit cloning or ownership conversion.

## 4. Non-goals

This proposal does not:

- add implementation inheritance;
- make every iterable constructible from a collection literal;
- make collection literals dynamically dispatched at runtime;
- require `List<T>([1, 2, 3])` or any other constructor wrapping a literal;
- replace ordinary vararg construction such as `List<T>(1, 2, 3)`;
- add list, set, or map comprehensions;
- define slicing syntax beyond preserving existing index/slice operators;
- redesign function parameter syntax, receiver declarations, or lambda capture
  lists except where they collide directly with a bare collection expression;
- make braces into an expression-precedence operator;
- infer a heterogeneous collection as `List<Any>` merely to make it compile.

## 5. Terminology

### 5.1 Group

A group is a source-level collection of names, targets, source expressions,
declarations, or import selectors. It expands at parsing or an early
normalization phase. A group has no runtime type, cannot be assigned to a
variable, cannot be returned, and cannot be passed to a function. A source
expression group is legal only as the right side of a brace binding/assignment
group and pairs with it by position.

### 5.2 Sequence literal

A sequence literal has the shape `[element, element, ...]`. It has an ordered
list of expressions and no key/value separators.

### 5.3 Associative literal

An associative literal has the shape `[key: value, key: value, ...]`. The empty
associative literal is `[:]`.

### 5.4 Tuple

A tuple is a fixed-arity runtime product. Its arity and each element type are
part of its static type. Tuples are ordered and may be heterogeneous.

### 5.5 Positional pattern

A positional pattern extracts components by index. It is written with
parentheses, for example `(first, second)`.

### 5.6 Name-based pattern

A name-based pattern extracts accessible fields or properties by name. In an
entry such as `mail = email`, `mail` is the local binding or assignment target
and `email` is the member read from the source.

### 5.7 Literal factory

A literal factory is a target-owned, static, compiler-recognized declaration
named `literal`. It tells the compiler how a sequence or associative literal is
materialized as that target type. It is not an implementation-inheritance hook,
and it is not dynamic dispatch.

`literal` is a reserved keyword and receives its own `LITERAL` token. It is not
a contextual identifier. It is legal only as a static member declaration of a
pack or spec (including a type-scoped `impl`) unless a later DIP deliberately
extends it.

## 6. Delimiter model

### 6.1 Braces

Braces retain all existing block roles:

```azora
if ready { run() }
fin transform = { it * 2 }
scope math { ... }
```

They additionally become the delimiter for syntactic groups in positions that
explicitly expect a group:

```azora
import a.b::{x, y}
fin {x, y, z} = 0
self.{width, height} = 0
self.{width, height} = {measuredWidth, measuredHeight}
pack Point derives (Equal, Hash, Display) { ... }
```

There is no general brace-group expression. A brace source group is recognized
only immediately after `=` whose left side is already a brace group. In ordinary
expression position, `{...}` continues to mean a lambda or a block according to
the surrounding construct. In particular, `{a + b} * c` is not expression
grouping.

### 6.2 Brackets

In primary-expression position, a bracket always begins one of these:

- a sequence literal;
- an associative literal;
- an empty sequence literal;
- an empty associative literal.

After an expression, a bracket remains collection access:

```azora
fin first = values[0]
table[key] = value
fin middle = values[start: stop]
```

The index operators retain their spellings:

```azora
oper[]  [self: Vector<T>&](index: Int): T
oper[]= [self: Vector<T>!](index: Int, value: T)
oper[:] [self: Vector<T>&](range: Range): Vector<T>
```

The receiver syntax shown above is outside this proposal. Its brackets occur in
a declaration-only position and do not compete with a primary expression.

### 6.3 Parentheses

Parentheses continue to serve calls, function parameters, conditions, and
single-expression grouping:

```azora
run(value)
func add(a: Int, b: Int): Int
if debug && ready { ... }
fin n = (a + b) * c
```

A top-level comma inside a parenthesized expression or type makes it a tuple:

```azora
(a, b)              // tuple value
(Int, String)        // tuple type
```

The parser therefore uses these rules:

| Form | Meaning |
|---|---|
| `()` | `Unit` value/type in the applicable grammar |
| `(x)` | grouped expression, type, or pattern |
| `(x, y)` | tuple expression, tuple type, or positional pattern |
| `(x,)` | rejected in version 1; one-element tuples are not introduced |

## 7. Groups with `{}`

### 7.1 Import selector groups

Symbol selector groups use `::` followed by braces:

```azora
import a.b::{x, y}
import std.format::{Display, Debug}
import std.container::{list::*, map::{Map, HashMap}}
```

The separator communicates intent:

- `.` continues a module path;
- `::` selects a symbol or symbol group from the resolved module/scope.

The grammar is conceptually:

```text
import-selector-group := "::" "{" import-selector (separator import-selector)* [separator] "}"
separator             := "," | NEWLINE+
```

An empty import selector group is invalid:

```azora
import a.b::{} // error: an import selector group must name at least one selector
```

Nested selector groups are allowed. Their base is the path immediately before
the `::{...}`:

```azora
import a::{b::{x, y}, c::*}
```

### 7.2 Fan-out binding groups

A binding keyword followed by braces declares several independent bindings:

```azora
fin {x, y, z} = 0
var {left, right}: Int = initial()
let {
    keys: K*
    values: V*
} = alloc .() * capacity
```

This is syntactic fan-out. It is equivalent to writing one declaration per
entry, in source order:

```azora
fin {x, y} = next()
```

is equivalent to:

```azora
fin x = next()
fin y = next()
```

Consequently, `next()` is evaluated twice. This rule is intentional. It makes
fan-out suitable for allocation and initialization:

```azora
let {keys: K*, values: V*} = alloc .() * capacity
```

allocates two independent buffers. Evaluating once would accidentally alias the
two bindings.

When each binding needs a different initializer, a brace source group pairs
expressions with bindings by position:

```azora
fin {x, y} = {nextX(), nextY()}
fin {name: String, count: Int} = {readName(), readCount()}
```

This expands in source order:

```azora
fin x = nextX()
fin y = nextY()
```

The target and source groups must have exactly the same arity. Each source is
resolved under only its matching target's expected type, evaluated exactly
once, and checked with its own effects and ownership mode. The source braces do
not construct a tuple, collection, lambda, or other runtime value.

The broadcast operator `<-` evaluates its right-hand expression exactly once:

```azora
fin {x, y}: Int <- next()
```

Conceptually, for a `Copy` result this is:

```azora
fin __groupValue: Int = next()
fin x: Int = __groupValue
fin y: Int = __groupValue
```

The hidden name is explanatory only; it is not visible to source code. The
compiler may keep the value in a register or SSA temporary. `<-` distributes
one value, so it is legal only when normal ownership and borrowing rules allow
every destination to receive that result. It does not imply cloning.

To evaluate once and split different components, use destructuring instead:

```azora
fin (x, y) = nextPair()
```

The distinction is:

```azora
fin {x, y} <- oneInt()       // x and y receive the same Copy value
fin (x, y) = oneIntPair()    // x and y receive different tuple components
```

Every entry may state its own type, or the group may state one shared type:

```azora
fin {x: Int, y: String} = defaults()
fin {x, y}: Int = 0
```

It is an error to combine per-entry types with a shared group type.

The group must be non-empty and may not declare the same local name twice.

`<-` with a one-entry group is allowed for grammar regularity, but the compiler
SHOULD warn that an ordinary declaration is clearer.

### 7.3 Fan-out assignment groups

A receiver followed by `.{...}` assigns the same expression independently to
several member-relative targets:

```azora
self.{offset, count} = 0
self.{keys, values, hashes} = alloc .() * capacity
self.{keys[i], values[i]} = defaultValue()
```

The right-hand expression is evaluated once per target, in target order. The
second example therefore performs three allocations.

A bare target group may assign variables or arbitrary assignable targets:

```azora
{left, right} = 0
{buffers[0], buffers[1]} = alloc .() * capacity
```

This is still fan-out. It is not positional assignment.

The same targets may use single-evaluation broadcast:

```azora
self.{width, height} <- measure()
{left, right} <- calculate()
```

Conceptually, broadcast assignment lowers to one source evaluation followed by
ordinary assignments in target order:

```azora
fin __groupValue = measure()
self.width = __groupValue
self.height = __groupValue
```

This is not a transaction. The RHS completes before any target is written, but
target evaluation and writes occur left to right. If a later runtime write can
fail, earlier writes remain observable and normal error cleanup applies.

Parallel positional assignment uses a tuple pattern and evaluates its source
once:

```azora
(left, right) = (right, left)
```

Different values for different targets use a brace source group:

```azora
self.{x, y} = {1, 2}
```

This is sequential group expansion:

```azora
self.x = 1
self.y = 2
```

Brackets never acquire this positional meaning. They always construct one
collection value:

```azora
self.{x, y} = [1, 2]
```

If `x` and `y` are collection-typed, this remains legal repeated fan-out and
constructs a separate collection for each target. If they are scalar `Int`
targets, it is a type error with a fix suggesting `self.{x, y} = {1, 2}`.

Paired brace assignment is not parallel. Source/target pairs execute from left
to right, so later source expressions may observe earlier writes:

```azora
self.{x, y} = {self.y, self.x} // second read may observe the new self.x
```

Use tuple assignment for swaps or any operation requiring all sources to be
captured before the first write:

```azora
(self.x, self.y) = (self.y, self.x)
```

`<-` accepts one ordinary RHS expression and therefore cannot take a brace
source group:

```azora
self.{x, y} <- {1, 2} // error: broadcast requires one value, not two grouped sources
```

### 7.4 Ownership and borrowing for group initialization

The two group operators share target syntax but do not create the same
ownership operation.

For repeated fan-out:

```azora
fin {a, b, c} = expression
```

the compiler behaves as if the source expression had been written separately
for every destination. Each expansion is type-checked and ownership-checked as
an ordinary declaration or assignment. Side effects, allocations, failures,
borrows, explicit clones, and explicit loans therefore happen independently in
source order.

For paired fan-out:

```azora
fin {a, b, c} = {expressionA, expressionB, expressionC}
```

each destination is ownership-checked only against its corresponding source.
There is no ownership duplication merely because the entries share braces:

```azora
fin {left, right} = {take leftSource, take rightSource}
```

moves two different owners once each. Taking the same owner in two entries is
still rejected, and a shared or exclusive borrow in one entry has its ordinary
lifetime relationship with subsequent entries.

For broadcast fan-out:

```azora
fin {a, b, c} <- expression
```

the compiler evaluates `expression` once, retains that result in an
unobservable temporary, and initializes the destinations from that one result.
The temporary has the expression's real type and ownership mode; it is not an
implicitly shared box. Ordinary implicit `Copy` and borrow rules apply when the
result is distributed.

Before emitting any group operation, the compiler MUST prove that every
destination is statically valid. A static error in the third destination must
not leave code that initializes the first two. Runtime failures follow the
evaluation and cleanup rules in §7.4.14.

A brace source group is unavailable with `<-`: paired sources already define
their own evaluation count, whereas broadcast is specifically one ordinary
source evaluation. Use `=` for pairing or a tuple value/pattern when the
expressions must first form one runtime product.

#### 7.4.1 Ownership matrix

For a group with more than one destination, the core cases are:

| RHS result or operation | Repeated `=` | Broadcast `<-` |
|---|---|---|
| `Copy` value | Valid; evaluate and copy independently | Valid; evaluate once and implicitly copy to each destination |
| Fresh non-`Copy` rvalue | Valid; construct one owner per destination | Invalid; one constructed owner cannot become several owners |
| Named non-`Copy` owner | Invalid if a destination requires ownership, unless the expression explicitly clones or otherwise returns ownership | Invalid if more than one destination requires ownership |
| `take owner` | Invalid for multiple destinations: the first expansion spends `owner` | Invalid for multiple destinations: one move has one destination |
| `owner.clone()` | Valid; calls `clone()` once per destination | Calls `clone()` once total; valid only if that returned value is itself `Copy` or all destinations borrow it |
| Shared borrow `owner.&` | Valid; creates a shared borrow per expansion | Valid; the single shared borrow may be copied/aliased while its origin remains live |
| Exclusive borrow `owner.!` | Invalid for multiple simultaneously live bindings | Invalid for multiple destinations |
| `Unique<T>` or another unique owner | Fresh construction with `=` may produce independent owners | Invalid unless the handle type actually implements sound `Copy`, which a unique owner must not |
| `Shared<T>`, `SyncShared<T>`, or `Weak<T>` | Explicit `.clone()` may create one handle per expansion | No implicit reference-count increment; use explicit clones or shared borrows |
| Tuple or pack | Depends on the ownership of the whole value | Broadcast only if the whole value is `Copy`, or destinations receive compatible borrows |
| Collection | Fresh literal/construction is repeated | Usually invalid because standard owning collections are not `Copy` |
| Optional `T?` | Depends on the optional's ownership operation | Valid when `T?` is `Copy`; the literal `null` is also valid because it carries no owner |
| Plain function value | Valid if its representation is `Copy` | Valid if its representation is `Copy` |
| Closure value | Depends on its capture-generated capabilities | Valid only if the closure value is `Copy` or destinations borrow it |
| Owning allocation expression | Produces one allocation per destination | Invalid for multiple owning destinations |

This table is not an independent list of privileged types. It is an application
of the normal capability and borrow system. A generic group is accepted only
when its constraints prove the required operation:

```azora
func broadcast<T>(value: T): (T, T)
where T: Copy {
    fin {left, right}: T <- value
    return (left, right)
}
```

The compiler MUST check semantic ownership, not only representation. A box,
pointer, descriptor, or reference-counted handle may occupy one machine word and
still be non-`Copy` because duplicating its bits would duplicate an ownership
obligation.

#### 7.4.2 `Copy` values

`<-` is the concise form for broadcasting a value that implements `Copy`:

```azora
fin {minimum, maximum}: Int <- limit()
fin {enabled, visible} <- settings.defaultFlag()
```

The source is evaluated once. Each binding then receives the ordinary implicit
copy it would receive from a named `Copy` local. The conceptual temporary is
dropped after the final initialization if its type has any permitted cleanup.

For composite types, `Copy` is a property of the entire result:

```azora
pack Point derives (Clone, Copy) {
    fin x: Int
    fin y: Int
}

fin {start, end}: Point <- origin()
```

This is valid only because `Point` soundly implements `Copy`. A pack does not
become broadcastable merely because some fields are `Copy`; all fields and the
pack's destruction and identity rules must permit the declared capability.

#### 7.4.3 Non-`Copy` owned values and moves

One owned non-`Copy` value cannot initialize several owning destinations:

```azora
fin {primary, backup}: File <- File.open("data.bin")
// error: broadcast creates one File owner but 2 destinations require ownership
```

Use repeated construction when independent values are intended:

```azora
fin {primary, backup}: File = File.open("data.bin")
```

or spell the ownership policy explicitly. For an existing cloneable owner:

```azora
fin source: Config = loadConfig()
fin {first, second}: Config = source.clone()
```

Because `=` repeats the expression, this invokes `clone()` twice and leaves
`source` owned by its original binding. If consuming the original is desired,
tuple construction makes the asymmetric operation visible:

```azora
fin source: Config = loadConfig()
fin (first, second) = (source.clone(), take source)
```

There is deliberately no `<- clone source` shorthand. In particular:

```azora
fin {first, second}: Config <- source.clone()
```

calls `clone()` once and then tries to distribute one new `Config`; it is still
invalid when `Config` is non-`Copy`.

Likewise, `take` does not manufacture ownership:

```azora
fin {first, second}: File = take source  // error: source was taken by first expansion
fin {first, second}: File <- take source // error: one moved File has two destinations
```

A single-entry group may move because there is only one owner destination, but
an ordinary binding is clearer:

```azora
fin {only}: File <- take source // valid, warning: prefer `fin only = take source`
```

#### 7.4.4 Shared borrows

Several shared borrows may coexist, so broadcasting a shared borrow is valid:

```azora
fin owner: Document = loadDocument()
fin {leftView, rightView}: Document& <- owner.&
```

Both borrows retain `owner` as their origin. They do not own `Document`, and
neither may outlive, move, or destroy `owner`. The broadcast does not create a
new lifetime region and does not erase the origin recorded by an API return
type such as `T&[source]`.

Repeated creation is also valid:

```azora
fin {leftView, rightView}: Document& = owner.&
```

The difference remains evaluation count: `=` performs the borrow expression
once per binding, while `<-` obtains the source result once and aliases that
shared borrow. Both forms are subject to any exclusive borrow already live for
the same origin.

A shared borrow returned from a call keeps its declared or inferred origin:

```azora
fin {first, second}: Node& <- tree.root()
```

This is valid only if `tree.root()` returns a shared borrow whose origin remains
live for both bindings. The bindings do not extend `tree`'s ownership lifetime.

#### 7.4.5 Borrowing temporaries

The hidden broadcast temporary must not silently extend an owner's lifetime.
This is rejected:

```azora
fin {first, second}: Document& <- loadDocument().&
// error: shared borrows would escape a temporary owner; bind the owner first
```

Write the owner explicitly:

```azora
fin document = loadDocument()
fin {first, second}: Document& <- document.&
```

This rule keeps destruction visible and makes later moves of `document`
checkable. An implementation must not accept the first form by secretly storing
the temporary until the end of the surrounding scope. Ordinary call-scoped
temporary borrowing that does not escape the call remains governed by the
general borrow rules and is not changed by this DIP.

#### 7.4.6 Exclusive borrows

An exclusive borrow cannot be distributed to multiple live destinations:

```azora
fin {left, right}: Buffer! <- buffer.!
// error: an exclusive borrow has exactly one live access path
```

Repeated fan-out is not an escape hatch:

```azora
fin {left, right}: Buffer! = buffer.!
// error: the first exclusive borrow is still live when the second is created
```

The fact that declarations are emitted left to right does not make them
non-overlapping; both bindings remain live according to ordinary lexical and
last-use analysis. The same rule applies when different syntactic expressions
are proven to have the same origin.

If operations must happen sequentially, use scopes or calls whose exclusive
borrow ends before the next one begins:

```azora
mutateFirst(buffer.!)
mutateSecond(buffer.!)
```

The compiler may accept multiple exclusive borrows of provably disjoint
subobjects only if Azora's general borrow checker already supports that proof.
`<-` itself never establishes disjointness because it broadcasts the same
source.

#### 7.4.7 Lending and `return` parameters

`lend` is a call-site ownership operation paired with a `return` parameter. It
is not a general value expression for creating several bindings and is not a
broadcast mechanism:

```azora
fin {a, b}: Handle <- lend handle // error: `lend` is valid only for a matching call argument
```

Repeated fan-out may invoke a function that accepts a loan:

```azora
fin {first, second}: Info = inspect(lend handle)
```

This expands into two calls. The first call must return ownership of `handle`
before the second call begins, so the form is valid when `inspect`'s parameter
is marked `return` and both calls otherwise type-check.

Broadcasting such a call invokes it once:

```azora
fin {first, second}: Int <- inspect(lend handle)
```

The loan is returned once when that call completes, and the returned `Int` is
then copied. If the function instead returns a non-`Copy` owned value, the usual
one-owner restriction rejects the broadcast. `lend` cannot be applied to a
borrow, and group syntax does not relax that rule.

#### 7.4.8 Unique, shared, weak, and raw pointer-like values

`Unique<T>` and any raw owning allocation pointer represent one destruction
obligation. Broadcasting one into several owning bindings would create double
release or use-after-free behavior and MUST be rejected:

```azora
fin {front, back}: Unique<Image> <- uniqueOf(Image()) // error
let {a: UByte*, b: UByte*} <- alloc .() * 1024       // error: one allocation, two owners
```

Use `=` for genuinely independent allocations:

```azora
let {a: UByte*, b: UByte*} = alloc .() * 1024
```

`Shared<T>`, `SyncShared<T>`, and `Weak<T>` should remain non-`Copy` when
duplicating a handle changes a reference count. Group syntax never inserts that
operation. Create handles explicitly:

```azora
fin root: Shared<Image> = sharedOf(Image())
fin (front, back) = (root.clone(), take root)
```

or borrow one owner:

```azora
fin root: Shared<Image> = sharedOf(Image())
fin {front, back}: Shared<Image>& <- root.&
```

If a future pointer or smart-pointer type soundly implements `Copy`, broadcast
follows that capability. A non-owning raw address may be bitwise duplicable in
`unsafe` code, but duplicating an address does not duplicate or extend the
pointee's lifetime. The compiler must preserve provenance, aliasing, thread
safety, and mutability restrictions even when the address bits are `Copy`.

#### 7.4.9 Tuples, packs, optionals, specs, and collections

Broadcast does not recursively invent a strategy for aggregates. A tuple or
pack may be sent to all destinations only when the aggregate itself implements
`Copy`:

```azora
fin {a, b}: (Int, Bool) <- (statusCode(), true) // valid if this tuple is Copy
```

If any owned component is non-`Copy`, broadcasting the aggregate by ownership
is invalid. The compiler must not copy some fields, move another field into the
first target, and leave later targets partially initialized. Use explicit
tuples of clones/moves or borrow the aggregate instead.

An optional follows the same compositional rule. The literal `null` contains no
owned payload and may be broadcast into compatible nullable destinations. A
named `T?` value, however, is broadcastable only when `T?` implements `Copy`;
the operation must not change according to a runtime present/empty state or
duplicate a possibly present non-`Copy` payload.

A value erased or boxed behind a spec is broadcastable only when its semantic
spec value supports `Copy`. Copying the machine-word box pointer is insufficient
if doing so would duplicate ownership or destruction. Dynamic dispatch must not
weaken this check.

Owning collections are normally non-`Copy`:

```azora
fin {left, right}: List<Int> <- [1, 2, 3] // error: one List cannot have two owners
fin {left, right}: List<Int> = [1, 2, 3]  // valid: constructs two Lists
```

To observe one collection through multiple read-only paths, name it and
broadcast a shared borrow. To obtain independent mutable collections, repeat
construction or clone explicitly.

#### 7.4.10 Functions and closures

A plain function value may be broadcast when its function-value representation
implements `Copy`. A closure's capability is derived from its environment and
representation, not merely from its callable signature:

```azora
fin {p, q} <- pureFunction
```

is valid for a copyable function value. A closure that owns a socket, unique
handle, collection, or another non-`Copy` capture cannot be broadcast by
ownership. A closure that holds an exclusive borrow also cannot be duplicated.
Shared-borrow captures may be duplicated only when the general closure rules
declare the resulting closure value `Copy` and every captured origin outlives
all destinations.

Escaping and asynchronous closures retain the normal stronger capture rules.
`<-` does not convert a borrowed closure into an owned `'static` closure, make
captures thread-safe, or grant `Send`/`Sync`-like capabilities.

#### 7.4.11 Per-target types and contextual expressions

With `=`, every expansion receives the expected type of its own destination:

```azora
fin {numbers: List<Int>, unique: Set<Int>} = [1, 2, 2]
```

This constructs a list under `List<Int>` context and then a set under
`Set<Int>` context. The bracket expression is resolved and evaluated twice.

With `<-`, the RHS must have one resolution and one runtime result. When all
destinations provide the same expected type, that type supplies source context:

```azora
fin {left, right}: Int <- calculate()
```

When destination types differ, the compiler first resolves the RHS using its
intrinsic context and then applies ordinary per-target conversions. If the RHS
requires incompatible contextual types, the compiler rejects it:

```azora
fin {numbers: List<Int>, unique: Set<Int>} <- [1, 2, 2]
// error: one collection literal cannot simultaneously resolve as List<Int>
// and Set<Int>; name one concrete source or use repeated `=`
```

Even when conversions are available, they run once per destination after the
single RHS evaluation. Each conversion must state and satisfy its ownership
mode. Numeric widening of a `Copy` value is straightforward; two consuming
conversions of a non-`Copy` source are not. A conversion that clones or shares
must be explicit in source unless the general conversion rules already define
it as a sound implicit operation.

A broadcast has one source ownership mode, so a destination list must not ask
the compiler to treat one non-`Copy` result as both an owner and a borrow:

```azora
fin {owned: Document, view: Document&} <- loadDocument()
// error: moving the temporary into `owned` would leave `view` without a live origin
```

Name the owner, then create all views from that stable origin:

```azora
fin owned: Document = loadDocument()
fin {leftView, rightView}: Document& <- owned.&
```

For a `Copy` type, multiple owning destinations receive copies as usual. A
borrow destination must still be produced by an explicit borrow expression and
must have an origin that outlives it; target typing does not implicitly turn an
owned expression into `.&` or `.!`.

#### 7.4.12 Target aliases, old values, and mutation

Binding mutability and ownership capability remain separate. `var`, `let`,
`val`, and `fin` apply their ordinary rebinding/mutation policy to every group
entry, but none changes whether the source is `Copy`, `Clone`, borrowed, or
owned. In particular, an immutable `fin` may still receive a moved value in a
one-destination declaration, and a mutable `var` does not make a non-`Copy`
value broadcastable.

Duplicate written targets should be rejected when detectable:

```azora
self.{count, count} <- nextCount() // error: duplicate group target
```

For indexed or computed lvalues whose aliasing is known only at runtime, target
expressions and writes occur left to right. The same storage may therefore be
assigned more than once. The compiler should warn when it can prove such an
alias, but it need not add a runtime distinctness check.

In assignment form, each overwritten non-`Copy` value is destroyed according
to the ordinary assignment rules. Broadcasting a new `Copy` value does not
combine or delay destruction of the old values. Broadcasting a borrow must also
respect any borrows of the old and new origins that remain live.

Moving a member in repeated fan-out records an ordinary partial move after the
first expansion:

```azora
fin {first, second}: Connection = take session.connection
// error at `second`: `session.connection` was already taken for `first`
```

Broadcast rejects the same source before code generation because one moved
field still has one owner. If different fields should move to different names,
use tuple or pack destructuring, or an explicit tuple of `take` expressions;
brace groups always apply the same source expression to every target.

#### 7.4.13 Concurrency and suspension

Evaluation-once is not synchronization. `<-` does not make a value safe to
share between tasks, threads, actors, GPU invocations, or async continuations.
Every destination retains the same concurrency constraints that an equivalent
ordinary assignment would have.

A borrow produced by the RHS may cross `await` or escape into a closure only if
the ordinary borrow checker proves its origin remains live and unmoved for that
duration. Broadcast does not extend the origin. An exclusive borrow remains
exclusive across suspension and consequently cannot be broadcast.

#### 7.4.14 Evaluation, failure, and cleanup order

For repeated `=`:

1. Process destinations in source order.
2. Evaluate the RHS for the current destination using that destination's type
   context.
3. Complete its conversion, initialization, or assignment.
4. Continue to the next destination.

If the third evaluation fails at runtime, effects and completed writes from the
first two remain observable. Successfully initialized locals are cleaned up by
normal scope/error unwinding. An allocation or loan acquired by a failing
expansion follows the ordinary cleanup contract of that expression.

For paired `= {e0, e1, ...}` the order is the same, except destination `i`
evaluates only expression `ei`; no expression is cloned. If `ei` fails, earlier
pairs remain completed and later pairs do not run. Every source expression has
its own target context, ownership event, borrow origin, and cleanup edge.

For broadcast `<-`:

1. Determine one source type and validate every destination statically.
2. Evaluate the RHS exactly once. If this fails, no destination is initialized
   or written.
3. Retain the result and its borrow origin in an internal temporary.
4. Process destination conversions and writes left to right.
5. Destroy any remaining owned temporary after the last successful transfer or
   during failure cleanup.

If a later runtime conversion or write fails, earlier writes remain observable;
`<-` is not transactional. The compiler must track which destinations and which
portion of a source have been initialized so every owned value is destroyed
exactly once. In practice, a multi-destination owned non-`Copy` broadcast is
rejected before this phase, so partial moves cannot be used to smuggle one owner
into multiple targets.

The source expression is sequenced before all target writes. Target receiver and
index expressions are evaluated in target order immediately before their write,
unless the language's ordinary assignment rules require them to be retained
earlier. This differs from tuple assignment, which retains all extracted
components and target information needed for parallel assignment.

### 7.5 Type tuples in capability positions

Capabilities, contracts, decorators, requirements, and other type collections
use tuples. Their elements are types, so they follow the same parenthesized
syntax as every other tuple of types:

```azora
pack Point derives (Equal, Order, Hash, Display) { ... }
impl (Serializable, Display) for User { ... }
```

The receiving construct may interpret the tuple as a set for validation:

- source order is retained for deterministic diagnostics and generation;
- duplicates are errors;
- semantic meaning may not depend on their written order.

The token following the type tuple disambiguates grouped contracts from grouped
implementation targets:

```azora
impl (Serializable, Display) for User { ... } // contracts applied to User

impl (Int, UInt) {
    inline fin bitWidth: Int = 64
}
```

Here `(Int, UInt)` is an ordered compile-time tuple of targets over which the
implementation body is instantiated.

### 7.6 Groups are never values

The following are invalid:

```azora
fin g = {a, b}      // lambda/block grammar applies; this is not a group value
return {a, b}       // no group value exists
consume({a, b})     // no group argument exists
```

The paired form is legal only because the already recognized brace target group
creates a grammar position that consumes another syntactic group:

```azora
fin {x, y} = {a, b} // legal pairwise expansion; neither brace group is a value
```

When a repeated initializer is itself a lambda, parentheses distinguish the
ordinary expression from a paired source group:

```azora
fin {first, second} = ({ it + 1 }) // construct the lambda independently twice
fin {first, second} <- ({ it + 1 }) // construct once, then broadcast if Copy
```

Use a tuple for a heterogeneous product and a bracket literal for a collection.

### 7.7 Compile-time tuples of types

Parentheses also replace bracket type lists in grammar positions that enumerate
types rather than name one runtime tuple target:

```azora
impl (Int, UInt) { ... }
inline for T in (Byte, Short, Int, Long) { ... }
```

In these positions, the enclosing construct consumes the tuple's elements at
compile time. It does not implement or iterate one runtime `Tuple` value.

When the tuple type itself is the implementation target, use its canonical
nominal identity to remove the ambiguity:

```azora
impl Tuple<Int, UInt> { ... } // implementation on the two-element tuple type
impl (Int, UInt) { ... }      // the same implementation body on Int and UInt
```

A named alias remains a normal type unless explicitly spread by a construct
that accepts a type tuple:

```azora
typealias IntegerPair = (Int, UInt)

impl IntegerPair { ... }      // implementation on the tuple type
impl (...IntegerPair) { ... } // implementation on Int and UInt individually
```

The same explicit-spread rule applies to compile-time loops when the source is a
named alias. This prevents an alias from changing meaning merely because it was
substituted for written tuple syntax.

## 8. Native collection literals with `[]`

### 8.1 Sequence literals

The sequence form is:

```azora
[element0, element1, element2]
```

Examples:

```azora
fin numbers = [1, 2, 3]
fin names: List<String> = ["Ada", "Grace"]
fin unique: Set<Int> = [1, 2, 2, 3]
fin queue: Queue<Job> = [first, second, third]
```

Commas are required between elements. Newlines are whitespace, not implicit
element separators. A trailing comma is allowed:

```azora
fin names = [
    "Ada",
    "Grace",
]
```

This rule avoids ambiguity between two adjacent expressions split by a newline.

### 8.2 Associative literals

The associative form is:

```azora
[key0: value0, key1: value1]
```

Examples:

```azora
fin ages = ["Ada": 36, "Grace": 85]
fin ages: Map<String, Int> = ["Ada": 36, "Grace": 85]
fin ordered: TreeMap<String, Int> = ["b": 2, "a": 1]
```

All top-level entries in one literal must use the same shape. Mixing keyed and
unkeyed elements is invalid:

```azora
[1, "two": 2] // error: a collection literal cannot mix elements and entries
```

### 8.3 Empty literals

The empty forms are deliberately different:

```azora
[]   // empty sequence literal
[:]  // empty associative literal
```

An empty literal normally requires an expected type:

```azora
fin names: List<String> = []
fin ages: Map<String, Int> = [:]
```

Without an expected type, the compiler cannot infer element types:

```azora
fin values = [] // error: cannot infer the element type of an empty sequence literal
fin table = [:] // error: cannot infer key and value types of an empty associative literal
```

The language does not default these to `List<Nothing>` or `Map<Nothing,
Nothing>` in version 1.

### 8.4 Default inferred types

Without an expected target type, a non-empty literal uses these defaults:

| Literal shape | Inferred public type | Standard backing implementation |
|---|---|---|
| `[e0, e1, ...]` | `List<E>` | `ArrayList<E>` |
| `[k0: v0, ...]` | `Map<K, V>` | `LinkedHashMap<K, V>` |

The public type is the collection spec, not the backing pack. Code may request a
specific implementation through an annotation:

```azora
fin dense: ArrayList<Int> = [1, 2, 3]
fin hashed: HashMap<String, Int> = ["a": 1]
fin sorted: TreeMap<String, Int> = ["a": 1]
```

`Set` has no separate punctuation. Context chooses set semantics:

```azora
fin set: Set<Int> = [1, 2, 2, 3]
```

The target's literal factory decides duplicate handling. For the standard set,
duplicates collapse according to `Equal` and `Hash`/`Order`. For maps, the
standard factories use last-written-value-wins unless the target type documents
a stricter policy.

### 8.5 Contextual typing

A collection literal is initially represented as an unresolved literal shape,
not as an `ArrayList` AST constructor call. Expected type flows into it from:

- a binding annotation;
- an assignment target;
- a return type;
- a function parameter;
- a pack field;
- a cast or explicit contextual type expression;
- an enclosing literal whose own target is known.

Examples:

```azora
fin xs: Set<Int> = [1, 2, 3]
consumeQueue([first, second]) // parameter type is Queue<Job>

func defaults(): Map<String, Int> {
    return [:]
}
```

If there is no expected type, default inference happens after all element
expressions are typed.

### 8.6 Element type inference

For a sequence literal without an expected element type:

1. Resolve every element expression independently.
2. Compute their least common assignable type.
3. Apply ordinary numeric promotion where the language already permits it.
4. Apply concrete-to-spec upcasts before reporting incompatible types.
5. Reject the literal if no sound common type exists.

The compiler MUST NOT silently choose `Any` merely because the elements differ:

```azora
[1, "two"] // error unless a contextual target explicitly accepts Any
```

An explicit context may request a spec type:

```azora
fin shapes: List<Shape> = [Square(2), Circle(1)]
```

Each element is converted to `Shape` before the common-element check. This rule
also closes the current gap where collections of spec-typed values cannot be
built.

Associative literals infer keys and values independently using the same rules.

### 8.7 Literal factories belong to the target type

There is no `List<T>([1, 2, 3])` form in this proposal. Explicit construction
uses ordinary varargs:

```azora
fin xs = List<Int>(1, 2, 3)
fin concrete = ArrayList<Int>(1, 2, 3)
```

Because `List<T>` is a spec rather than a concrete pack, its ordinary vararg
constructor is itself a normal type-owned bridge factory selecting the standard
implementation. That facility is independent of literal syntax: it receives
ordinary call arguments, participates in ordinary call diagnostics, and may be
used even if this DIP is not involved. Conceptually:

```azora
spec List<T> {
    bridge ctor(...elements: T): Self {
        return ArrayList<T>(...elements)
    }
}
```

The bracket-specific `literal` factory exists so contextual source can say
`fin xs: List<Int> = [1, 2, 3]` without inventing a constructor call or an
intermediate collection.

Contextual bracket construction is defined by a new static declaration named
`literal`. A sequence literal factory has this conceptual syntax:

```azora
spec List<T> {
    bridge literal [...elements: T]: Self {
        return ArrayList<T>(...elements)
    }
}
```

An associative literal factory receives variadic entry tuples:

```azora
spec Map<K, V> {
    bridge literal [...entries: (K, V)]: Self {
        return LinkedHashMap<K, V>(...entries)
    }
}
```

For a concrete custom type:

```azora
pack SmallVec<T> {
    // storage...
}

impl SmallVec<T>:: {
    literal [...elements: T]: SmallVec<T> {
        return SmallVec<T>(...elements)
    }
}

fin values: SmallVec<Int> = [1, 2, 3]
```

The exact body may use a capacity-aware private constructor or builder. The
surface protocol does not require the compiler to know those details.

Normative properties of `literal` are:

- it is static and has no receiver;
- it is selected at compile time from the expected target type;
- it is not ordinary overload resolution by the name `literal`;
- it cannot be called as `List.literal(...)` in version 1;
- a target may define at most one sequence factory per element arity pattern;
- a target may define at most one associative factory per key/value pattern;
- its result must be the containing target type or a concrete value assignable
  to that target when the containing target is a spec;
- it may be generic over the containing type's parameters;
- it participates in ordinary `where` constraints;
- failure types declared by the factory become failure requirements of the
  literal expression;
- literal construction is direct dispatch; it never requires a runtime spec
  method table.

For a spec such as `List<T>`, the body designates the standard concrete value
that is returned as that spec. For a concrete pack, the body normally returns
that pack directly.

### 8.8 No intermediate collection

The source:

```azora
fin xs: SmallVec<Int> = [a(), b(), c()]
```

lowers conceptually to:

```azora
fin xs: SmallVec<Int> = SmallVec<Int>.__literal(a(), b(), c())
```

It does not lower to:

```azora
SmallVec<Int>(List<Int>(a(), b(), c()))
```

The literal's elements become direct variadic arguments to the selected factory.
The backend may further specialize the standard factories into capacity
allocation plus ordered insertion.

### 8.9 Evaluation order

Elements and entries are evaluated exactly once, from left to right.

For a map entry, the key is evaluated before its value. The next entry begins
only after both have completed:

```azora
[key0(): value0(), key1(): value1()]
```

has the order:

1. `key0()`
2. `value0()`
3. `key1()`
4. `value1()`

The literal factory receives the resulting arguments in that order. A factory
may organize storage differently, but it may not cause an expression to be
evaluated again.

### 8.10 Ownership and borrowing

Literal elements follow ordinary argument ownership:

```azora
fin copied = [copyValue]
fin moved: List<Resource> = [take resource]
fin borrowed: List<Resource&> = [resource.&]
```

- `Copy` elements may be passed implicitly.
- A named, non-`Copy` owned value requires `take` when the factory takes it by
  value.
- A literal may contain shared or exclusive borrows only when the target
  element type permits those borrows and their inferred lifetime does not
  outlive the literal result.
- The literal factory may not retain a borrow beyond the result type's declared
  borrow origins.
- Moving one element does not move any other element.
- If evaluation of a later element fails, already-evaluated owned temporaries
  must be destroyed according to the language's normal partial-construction
  rules once deterministic destruction is implemented.

### 8.11 Spread elements

Sequence literals may contain a spread:

```azora
fin all = [0, ...middle, 100]
```

The spread expression must be iterable with an element type assignable to the
literal element type. It is consumed or borrowed according to the written
ownership mode and the iterable protocol.

Associative literals may spread another entry iterable:

```azora
fin merged: Map<String, Int> = [...defaults, "port": 8080]
```

Spread order is insertion order. Duplicate behavior remains target-defined.

Because a spread has runtime length, a factory receiving one cannot always be
lowered to a single fixed variadic call. The compiler instead uses the target's
builder form. Version 1 may reject spread literals until that builder hook is
implemented; it must not silently materialize an unrelated temporary list.

### 8.12 Arrays and fixed-size targets

An expected fixed-size array may consume a sequence literal:

```azora
fin bytes: Array<UByte, 4> = [0, 1, 2, 3]
```

When the size is a compile-time constant, arity must match exactly. The compiler
diagnoses the mismatch before code generation.

Without an expected array type, `[0, 1, 2, 3]` remains a `List<Int>`. Literal
syntax never infers a fixed-size array merely from the number of elements.

## 9. Builtin tuples with `()`

### 9.1 Tuple literals

A comma at the top level of parentheses constructs a tuple:

```azora
fin t = (1, "2", true)
```

Its inferred type is:

```azora
(Int, String, Bool)
```

Tuple elements are evaluated exactly once from left to right.

### 9.2 Tuple types

Tuple type syntax is native:

```azora
fin t: (Int, String, Bool) = (1, "2", true)

func swap(value: (Int, String)): (String, Int) {
    return (value.1, value.0)
}
```

Parenthesized function types remain unambiguous:

```azora
(Int, String) -> Bool   // function taking Int and String
((Int, String)) -> Bool // function taking one tuple argument
```

The second spelling contains an inner tuple type and an outer function-parameter
list.

### 9.3 Identity with `Tuple<...T>`

The standard library keeps `Tuple`, but it becomes a compiler bridge pack:

```azora
bridge pack Tuple<...T>
```

The following type pairs are identical, not merely convertible:

```azora
(Int, String)
Tuple<Int, String>
```

Likewise, the literal and explicit constructor create the same runtime type:

```azora
(1, "two")
Tuple<Int, String>(1, "two")
```

The compiler owns tuple layout, arity, positional field access, and lowering.
The standard library owns behavior layered on that representation, including
`Clone`, `Equal`, `Order`, `Hash`, `Display`, and conversion helpers.

`tupleOf(...)` may remain temporarily as a compatibility function, but it is
not the canonical syntax and should eventually be deprecated.

### 9.4 Tuple arity

Version 1 adopts these arity rules:

- `()` is `Unit`, preserving the existing unit value.
- `(x)` is grouping and has the type of `x`.
- `(x,)` is rejected with a diagnostic explaining that one-element tuples are
  not supported.
- `(x, y)` and larger are tuples.

This matches the current semantic model in which tuples have at least two
elements and avoids introducing `Tuple<T>` as a type distinct from `T`.

### 9.5 Tuple access

Tuple elements use zero-based positional access:

```azora
fin first = value.0
fin second = value.1
```

The index must be a compile-time integer within the tuple's arity. Dynamic
indexing uses collections, not tuples.

### 9.6 Tuple ownership

A tuple owns its owned elements. Constructing a tuple follows ordinary argument
ownership:

```azora
fin pair = (take socket, address)
```

Borrow tuples preserve an origin per element:

```azora
func names(a: User&, b: User&): (String&[a], String&[b]) {
    return (a.name.&, b.name.&)
}
```

Moving one tuple element leaves that element spent without spending unrelated
elements, following Azora's field-move rules.

## 10. Destructuring with `()`

### 10.1 Positional binding destructuring

A binding keyword followed by a parenthesized pattern destructures one value by
position:

```azora
fin (x, y) = point
val (email, username) = user
var (left, right): (Int, Int) = pair
```

The right-hand expression is evaluated exactly once. Conceptually, the compiler
binds it to a hidden temporary and then extracts components in order.

The outer binding keyword applies to every local binding in the pattern.

### 10.2 Assignment destructuring

Without a binding keyword, a positional pattern assigns existing targets:

```azora
(x, y) = pair
(left, right) = (right, left)
(self.width, self.height) = dimensions
```

The source is fully evaluated before the first target is written. Components
are also extracted before assignment begins, so swap-like forms are safe and do
not observe earlier writes.

This differs from a brace group, whose destination writes execute one by one.
With `=`, its source is also evaluated separately for every write; with `<-`,
one retained whole value supplies all writes.

### 10.3 Loop destructuring

Loop headers use positional patterns:

```azora
for (key, value) in entries {
    println(key + ": " + value)
}

for ((x, y), weight) in weightedPoints {
    ...
}
```

Loop bindings are `fin` by default. If mutable per-iteration bindings are ever
needed, that should be an explicit loop-binding modifier rather than inferred
from the pattern delimiter.

### 10.4 Name-based binding destructuring

Name-based destructuring uses `local = member` entries inside parentheses:

```azora
val user = User("alice", "alice@example.com")

val (mail = email, name = username) = user
```

This binds:

- local `mail` from member `user.email`;
- local `name` from member `user.username`.

The order is explicit and need not match declaration order.

When one outer keyword is insufficient, each entry may declare its own binding
kind and the pattern begins the statement:

```azora
(val mail = email, fin name = username, var n = loginCount) = user
```

The two forms may not be mixed. Either:

- an outer keyword supplies the kind for all entries; or
- every newly declared entry states its own kind.

### 10.5 Name-based assignment destructuring

Existing variables may receive selected members:

```azora
(mail = email, name = username) = user
```

Here `mail` and `name` must already be assignable targets. As with positional
assignment, the source and selected components are evaluated before writes
begin.

### 10.6 Pattern classification

At one parenthesis level, a pattern is either positional or name-based:

```azora
fin (x, y) = value                    // positional
fin (x = width, y = height) = value  // name-based
```

Mixing the two at the same level is rejected:

```azora
fin (x, y = height) = value // error
```

Nested patterns may independently choose their mode:

```azora
fin ((x, y), label = name) = value // allowed only if the outer representation
                                   // explicitly defines these two components;
                                   // ordinary packs should prefer one mode per level
```

For version 1, the compiler SHOULD conservatively reject a mixed-mode outer
pattern unless the source's destructuring bridge describes it exactly. This
keeps diagnostics predictable.

### 10.7 Ignoring components

An underscore discards a positional component:

```azora
fin (first, _, third) = triple
```

The component is still extracted far enough to run required ownership and
destruction semantics. `_` does not suppress a move, failure, or destructor.

In a name-based pattern, omission already ignores a member, so `_ = member` is
allowed only when forcing that member's evaluation or move is meaningful. It
should otherwise receive an “omit the entry” warning.

### 10.8 Nested destructuring

Positional patterns may nest:

```azora
fin ((x, y), (width, height)) = geometry
```

Name-based patterns may bind a nested member through a nested pattern:

```azora
val ((street, city) = address, name = username) = user
```

The right side of `=` in a name-based entry is a source member selector, not an
arbitrary expression. Version 1 permits one accessible identifier. Member paths
may be added later if they can produce better diagnostics than nested patterns.

### 10.9 Destructurable sources

Tuples are always positionally destructurable.

Packs support name-based destructuring through accessible stored fields or
properties. Normal visibility rules apply; destructuring cannot bypass
`protected`, `confined`, or private access.

Positional destructuring of a non-tuple pack requires explicit opt-in:

```azora
pack User derives Destructure {
    fin username: String
    fin email: String
}
```

`derives Destructure` fixes the positional order to stored-field declaration
order and makes changing that order a source-compatibility change. Computed,
static, and hidden fields do not participate unless an explicit implementation
defines a custom order.

The initial implementation may make `Destructure` compiler-known. A later
library-facing bridge may expose the equivalent of:

```azora
bridge spec Destructure<...Elements>
```

but the bridge must preserve moves and borrows rather than materializing a
copied tuple unconditionally.

### 10.10 Arity and type checking

Positional pattern arity must exactly equal source arity. Diagnostics report
both counts:

```text
cannot destructure (Int, String, Bool) into 2 positions: source has 3 elements
```

Every binding annotation is checked against the corresponding component:

```azora
fin (id: Int, name: String) = value
```

A shared annotation on the whole pattern is permitted only when it is a tuple
type of matching arity:

```azora
fin (id, name): (Int, String) = value
```

### 10.11 Ownership during destructuring

Destructuring follows the ownership mode of the source expression:

```azora
fin (a, b) = copyTuple       // copies only if the required components are Copy
fin (a, b) = take ownedPair  // moves owned components
fin (a, b) = pair.&          // binds shared borrows where component types permit
fin (a, b) = pair.!          // binds exclusive borrows where permitted
```

Rules:

1. The source expression is evaluated exactly once.
2. A named non-`Copy` source is not implicitly consumed.
3. `take source` permits moving non-`Copy` fields/components.
4. Moving components records partial moves on the source.
5. Borrowed destructuring produces component borrows rooted in the source's
   origin.
6. No component borrow may outlive that origin.
7. Name-based extraction observes the same access and ownership rules as an
   ordinary member expression.
8. Assignment validates all target writes and source moves before performing
   any write, preventing half-applied assignments caused by a static error.
9. Runtime failure during extraction follows normal partial-initialization and
   destruction rules.

## 11. Grouping versus destructuring

The language must teach this distinction explicitly:

| Intent | Syntax | Source evaluations |
|---|---|---:|
| Declare several independent values from the same expression text | `fin {a, b} = make()` | 2 |
| Pair different initializers with different bindings | `fin {a, b} = {makeA(), makeB()}` | each paired expression once |
| Evaluate once and broadcast one copyable/borrowed result | `fin {a, b} <- make()` | 1 |
| Split one positional result | `fin (a, b) = makePair()` | 1 |
| Assign the same expression independently | `self.{a, b} = make()` | 2 |
| Pair different expressions with different targets | `self.{a, b} = {left(), right()}` | each paired expression once |
| Evaluate once and broadcast to existing targets | `self.{a, b} <- make()` | 1 |
| Assign components in parallel | `(self.a, self.b) = makePair()` | 1 |
| Construct one homogeneous collection | `fin xs = [a, b]` | each element once |
| Construct one heterogeneous product | `fin pair = (a, b)` | each element once |

This distinction removes the current special case where a bracket list on the
right of a grouped assignment is not a collection expression.

## 12. Grammar sketch

The following EBNF is illustrative. Existing precedence productions remain in
force around it.

```text
primary-expression
    := literal
     | identifier
     | call-or-member-expression
     | parenthesized-or-tuple-expression
     | collection-literal
     | lambda-expression

parenthesized-or-tuple-expression
    := "(" ")"
     | "(" expression ")"
     | "(" expression "," expression ("," expression)* [","] ")"

collection-literal
    := sequence-literal
     | associative-literal

sequence-literal
    := "[" "]"
     | "[" sequence-element ("," sequence-element)* [","] "]"

sequence-element
    := expression
     | "..." expression

associative-literal
    := "[" ":" "]"
     | "[" map-entry ("," map-entry)* [","] "]"

map-entry
    := expression ":" expression
     | "..." expression

binding-statement
    := binding-kind identifier [":" type] "=" expression
     | binding-kind group-binding [":" type] group-source
     | binding-kind destructure-pattern [":" type] "=" expression
     | explicit-binding-pattern "=" expression

group-source
    := "=" expression
     | "=" source-expression-group
     | "<-" broadcast-expression

broadcast-expression
    := expression  // must not begin with an unparenthesized "{"

source-expression-group
    := "{" expression (separator expression)* [separator] "}"

group-binding
    := "{" group-binding-entry (separator group-binding-entry)* [separator] "}"

group-binding-entry
    := identifier [":" type]

destructure-pattern
    := "(" positional-pattern-entry "," positional-pattern-entry
        ("," positional-pattern-entry)* [","] ")"
     | "(" named-pattern-entry "," named-pattern-entry
        ("," named-pattern-entry)* [","] ")"

positional-pattern-entry
    := "_"
     | assignable-pattern [":" type]
     | destructure-pattern

named-pattern-entry
    := [binding-kind] assignable-pattern [":" type] "=" identifier

group-assignment
    := [expression "."] "{" group-target (separator group-target)* [separator] "}"
       group-source

tuple-assignment
    := destructure-pattern assignment-operator expression

import-selector-group
    := path "::" "{" import-selector (separator import-selector)* [separator] "}"
```

Parser context distinguishes a brace group from a body:

- after a binding keyword, `{` starts a binding group;
- after `.` in an assignable target, `{` starts a member target group;
- after a completed brace binding or target group, `=` selects repeated fan-out
  and `<-` selects single-evaluation broadcast;
- after a brace group followed by `=`, an immediate `{` starts a paired source
  group; an immediate `[` starts one collection expression as usual;
- after `::` in an import, `{` starts a selector group;
- after `derives` or another type-collection keyword, `(` starts a type tuple;
- where a statement body is expected, `{` starts a body;
- where a primary expression is expected, `{` starts a lambda according to the
  existing lambda grammar.

## 13. Ambiguity resolution

### 13.1 Parenthesized expression versus tuple

The first top-level comma makes a tuple. Nested commas do not count:

```azora
(f(a, b))    // grouped call
(f(a, b), c) // tuple
```

### 13.2 Tuple versus function type

A parenthesized type followed by `->` is the function's parameter list. To make
one tuple the sole parameter, add the tuple's own parentheses:

```azora
(A, B) -> R   // two parameters
((A, B)) -> R // one tuple parameter
```

### 13.3 Collection literal versus index access

A bracket starting a primary expression is a literal. A bracket following a
completed postfix expression is access:

```azora
[1, 2][0] // literal, then access
```

### 13.4 Sequence versus map

At the top bracket depth, the first `:` makes the literal associative. Colons
inside nested calls, lambdas, types, or nested literals do not affect the outer
shape.

The parser SHOULD diagnose a later shape mismatch at the mismatching element,
not reinterpret the entire literal.

### 13.5 Empty map versus slice

`[:]` in primary position is an empty associative literal. `value[:]` remains a
slice/access operation because it follows a completed expression.

### 13.6 Collection literal versus contextual receiver list

The current expression form `[a, b].method()` is used as a contextual-receiver
call. Under this proposal it unambiguously means “construct a list, then call
`method` on that list.” The contextual-receiver shorthand is removed.

Its replacement is the already structured form:

```azora
with (a, b) {
    method()
}
```

Here `(a, b)` is a tuple of contextual values supplied to the `with` block.

Declaration-only receiver lists and lambda capture lists remain parseable
because their grammar positions are not primary expressions. A later callable
syntax DIP may migrate them, but collection literals must not wait on that
unrelated redesign.

### 13.7 Group versus lambda/body

There is no general `{a, b}` group expression. A brace group is recognized only
where the grammar explicitly expects grouped syntax. Everywhere else the
existing body/lambda grammar wins.

### 13.8 Broadcast left arrow

`<-` receives a dedicated `LEFT_ARROW` token. The lexer recognizes it by
maximal munch before emitting separate `LESS` and `MINUS` tokens. It is not a
comparison operator and cannot be confused with `<=` or `>=`:

```azora
fin {x, y} <- next()     // broadcast group initialization
self.{x, y} <- next()    // broadcast group assignment
{x, y} <- next()         // broadcast bare-target assignment

x <= limit               // less-than-or-equal comparison
x >= minimum             // greater-than-or-equal comparison
```

The parser accepts `LEFT_ARROW` only immediately after a completed brace
binding group or brace target group. Broadcast is deliberately unavailable for
a simple declaration or assignment:

```azora
fin x <- next() // error: `<-` broadcast requires a brace binding group
x <- next()     // error: `<-` is not a general expression or assignment operator
```

It is also unavailable for tuple destructuring, imports, type tuples, and
arbitrary group-valued expressions. Tuple patterns use `=` because they already
evaluate one source once and split its components.

Azora does not introduce a symmetric `RIGHT_ARROW` broadcast. Existing `->`
continues to serve return types, function types, lambdas, `when` arms, and other
established grammar. `<-` reads directionally: the brace targets on its left
receive one result from the expression on its right. It may not be chained or
used as a compound assignment. The formatter SHOULD place one space on each
side.

### 13.9 Paired source group versus lambda

Immediately after `=` for a brace binding/target group, a bare `{...}` is a
paired source-expression group:

```azora
fin {x, y} = {makeX(), makeY()}
self.{x, y} = {1, 2}
```

In that one grammar position it is not a lambda. To repeat a lambda expression,
parenthesize it:

```azora
fin {first, second} = ({ it + 1 })
```

After `<-`, broadcast still requires one expression. A parenthesized lambda can
be that expression; an immediate brace is rejected rather than interpreted as
multiple broadcast sources:

```azora
fin {first, second} <- ({ it + 1 }) // one closure value, ownership permitting
fin {x, y} <- {1, 2}                // error: use `=` for paired sources
```

Brackets never participate in this disambiguation. `self.{x, y} = [1, 2]`
repeats one collection literal; it is not position-matched assignment.

## 14. Type system integration

### 14.1 New frontend nodes

The frontend should preserve literal intent instead of immediately rewriting to
stdlib calls:

```text
Expr.CollectionLiteral(
    shape = Sequence(elements) | Associative(entries),
    expectedTarget = unresolved,
    sourceSpan,
)

Expr.TupleLiteral(elements, sourceSpan)

Pattern.Positional(elements, sourceSpan)
Pattern.Named(entries, sourceSpan)

Stmt.GroupOperation(
    mode = Repeated | Paired | Broadcast,
    bindingKind,
    targets,
    sharedType,
    sources,
    sourceSpan,
)
```

The existing `Expr.ArrayLiteral`, `Expr.MapLit`, and `Expr.TupleLit` may be
adapted, but `ArrayLiteral` should not imply that the eventual result is a fixed
array. `SequenceLiteral` is a more accurate frontend name.

Groups never become expression nodes. Imports and compile-time type groups may
expand early. Runtime binding/assignment groups SHOULD remain a statement-level
node until contextual typing and ownership validation are complete:

- `Repeated` resolves a logically fresh RHS under each destination's context;
- `Paired` requires equal target/source arity and resolves source `i` only under
  target `i`'s context;
- `Broadcast` resolves exactly one RHS and records one source ownership mode,
  borrow origin, and list of destination conversions.

After validation, normalization may replace the group statement with explicit
ordinary operations and compiler-owned temporaries. This ordering prevents
early AST cloning from losing source evaluation policy or allowing backends to
guess whether one or several values exist.

### 14.2 Literal resolution result

Resolving a collection literal produces:

- target type;
- element type, or key and value types;
- selected literal factory;
- required conversions for each element/entry;
- ownership mode for every argument;
- spread/builder plan if applicable;
- resulting failure set.

This result should be stored on the typed AST or passed explicitly to IR
generation. IR generation must not rediscover overload or factory selection.

### 14.3 Generic contexts

Expected type parameters constrain literals normally:

```azora
func singleton<T>(value: T): List<T> {
    return [value]
}
```

For an unconstrained generic target, the literal defaults before generic
inference completes:

```azora
func accept<C>(value: C) { ... }
accept([1, 2, 3]) // C is List<Int>
```

A generic API that wants an arbitrary literal-constructible target should state
a literal-factory constraint. The exact constraint spelling can expose the
compiler-known `FromSequenceLiteral<T>` capability once literal factories are
available to generic constraints.

### 14.4 Nullability

`null` participates only when the expected element type is nullable or the
other elements establish a nullable common type:

```azora
fin xs: List<String?> = ["a", null]
```

The compiler must not silently discard nullability when choosing the literal
factory.

### 14.5 Constant evaluation

A tuple of compile-time values is compile-time evaluable component-wise.

A collection literal is compile-time evaluable only when:

- every element/entry is compile-time evaluable;
- the selected literal factory is permitted during compile-time execution;
- the target representation is supported by CTCE.

Otherwise it remains a runtime construction even if all written elements are
literals.

## 15. IR and backend integration

### 15.1 Tuple IR

Tuple literals lower to the existing tuple/product IR, with an explicit element
type vector. Backends must agree on:

- field order;
- alignment and padding;
- value versus pointer representation;
- copying and moving elements;
- return ABI;
- nested tuple layout;
- zero-sized element behavior.

`Tuple<...T>` and tuple syntax must reach identical IR.

### 15.2 Collection literal IR

After semantic resolution, a collection literal lowers to one of:

1. a direct call to the selected literal factory with fixed variadic arguments;
2. a capacity-aware builder sequence when spreads are present;
3. a fixed aggregate constructor for a builtin array target.

There must be no backend-specific guessing about whether `[...]` means list,
set, map, or array.

### 15.3 Group and destructuring IR

Repeated fan-out lowers to an ordered sequence of ordinary statements, each
with its own resolved RHS instance. A compiler may internally clone source AST,
but each clone needs its own contextual type-resolution result, effect instance,
failure edge, and ownership event.

Paired fan-out lowers to an ordered sequence of ordinary statements by zipping
target `i` with source expression `i`. It does not build a temporary tuple or
collection and does not pre-evaluate later sources. Every expression has one
evaluation, one target context, and its own ownership/failure edge.

Broadcast fan-out lowers to:

1. one source evaluation into an internal SSA value or temporary;
2. one borrow-origin/ownership record for that source;
3. one checked conversion and write per destination in source order;
4. exact-once cleanup of the source temporary on every control-flow edge.

The IR must distinguish `CopySource`, `SharedBorrowSource`, and any other
permitted distribution plan. A backend must not lower an abstract
`BroadcastSource` by bit-copying an owning representation. Non-`Copy` owned and
exclusive-borrowed multi-target broadcasts must already have been rejected by
semantic analysis, but the IR verifier SHOULD reject them as defense in depth.

Destructuring lowers in two phases:

1. evaluate and retain the source plus every extracted component;
2. initialize or assign targets in source order.

This guarantees parallel assignment semantics. Repeated braces lower to
separate source evaluations; broadcast braces retain one whole source value but
do not extract positional components.

### 15.4 Backend parity

Interpreter, LLVM, and Wasm must either implement the same resolved construction
plan or reject it for that target. A backend must never degrade:

- a tuple to its first element;
- a collection literal to a default pointer;
- a map literal to a sequence;
- a destructuring assignment to sequential reads that observe its own writes;
- a repeated group to one RHS evaluation;
- a paired source group to a tuple, collection, broadcast, or reordered writes;
- a broadcast group to repeated RHS evaluations or an unchecked bit-copy.

Native tuple and collection literals should be added to the backend conformance
suite before macro forms are removed.

## 16. Diagnostics

Diagnostics should name the semantic category, not just the unexpected token.

Recommended messages include:

```text
cannot infer the element type of empty collection literal '[]'; add a target
type such as 'List<Int>'

cannot infer the key and value types of empty associative literal '[:]'; add a
target type such as 'Map<String, Int>'

type 'Widget' does not define a sequence literal factory; define
'literal [...elements: T]' or construct Widget explicitly

collection literal cannot mix sequence elements and key/value entries; entry 3
uses ':' but earlier elements do not

collection elements have no common assignable type: element 1 is Int, element 2
is String; add an explicit target type if both implement a shared spec

tuple literal requires at least two elements; '(value)' is grouping and
'(value,)' is not supported

cannot destructure (Int, String, Bool) into 2 positions: source has 3 elements

pack 'User' does not opt into positional destructuring; use name-based
destructuring '(mail = email, name = username)' or derive Destructure

brace binding group repeats name 'x'

'fin {x, y} = expr' evaluates expr once per binding; use
'fin (x, y) = tupleExpr' to split one result

group has 3 targets but its brace source group has 2 expressions; target and
source groups must have equal arity

'[1, 2]' is one collection literal, not a grouped right side; to pair values
with targets write 'self.{x, y} = {1, 2}'

broadcast '<-' requires one source expression and cannot consume a brace source
group; use '=' for paired sources

a bare brace after a grouped '=' is a source group, not a lambda; wrap the
lambda in parentheses: 'fin {a, b} = ({ it + 1 })'

'<-' broadcasts only after a brace group; write 'fin x = expr' for one binding

cannot broadcast owned value of type 'File' to 2 owning destinations: 'File'
is not Copy; use repeated '=' to construct independently, explicit '.clone()'
expressions, shared borrows, or a shared-owner type

cannot broadcast 'take source' to 2 destinations: one ownership transfer has
exactly one destination

'source.clone()' is evaluated once under '<-' and returns one non-Copy Config;
use repeated '= source.clone()' to clone once per destination

cannot broadcast exclusive borrow 'buffer.!' to 2 destinations; an exclusive
borrow has exactly one live access path

cannot create 2 exclusive borrows of 'buffer' with repeated fan-out: the borrow
stored in 'left' is still live when 'right' is initialized

shared borrow broadcast would outlive temporary owner from 'loadDocument()';
bind the owner first, then broadcast 'owner.&'

one broadcast collection literal cannot use incompatible target contexts
'List<Int>' and 'Set<Int>'; use repeated '=' or name one concrete source value

cannot broadcast owning allocation to 2 pointer bindings; use repeated '=' for
independent allocations or create one owner and broadcast shared borrows
```

During migration, removed macro spellings should provide fixes:

```text
'@arr[1, 2, 3]' is obsolete; write '[1, 2, 3]'
'tupleOf(a, b)' is obsolete; write '(a, b)'
'fin [a, b] = ...' is obsolete; use braces for fan-out or parentheses for
destructuring
```

## 17. Migration from current syntax

### 17.1 Mechanical replacements

| Current form | New form | Meaning |
|---|---|---|
| `@arr[1, 2]` / `@vec[1, 2]` | `[1, 2]` | default sequence literal |
| `mapOf(entry(...))` where literal | `[k: v]` | associative literal |
| `tupleOf(a, b)` / tuple macro | `(a, b)` | tuple literal |
| `Tuple<A, B>` | `(A, B)` preferred | tuple type; old identity remains |
| `import a.b::[x, y]` | `import a.b::{x, y}` | symbol selector group |
| `import a.b.{x, y}` | `import a.b::{x, y}` | symbol selector group |
| `fin [a, b] = expr` | `fin {a, b} = expr` | repeated fan-out evaluation |
| `self.[a, b] = expr` | `self.{a, b} = expr` | repeated fan-out assignment |
| `[a, b] = expr` | `{a, b} = expr` | repeated bare-target assignment |
| `self.[a, b] = [left, right]` | `self.{a, b} = {left, right}` | paired sequential assignment |
| `fin [a, b] = [left, right]` | `fin {a, b} = {left, right}` | paired binding initialization |
| `for [a, b] in rows` | `for (a, b) in rows` | positional destructuring |
| `impl [A, B] { ... }` | `impl (A, B) { ... }` | grouped implementation targets |
| `inline for T in [A, B]` | `inline for T in (A, B)` | compile-time type tuple |
| `derives [A, B]` | `derives (A, B)` | capability type tuple |
| `impl [A, B] for T` | `impl (A, B) for T` | contract type tuple |

### 17.2 Grouped right sides

The current grammar gives brackets on the right of a grouped target a special
positional meaning:

```azora
self.[a, b] = [left, right]
```

Because both sides are syntactic groups, the direct migration replaces both
bracket pairs with braces:

```azora
self.{a, b} = {left, right}
```

It must not be migrated to:

```azora
self.{a, b} = [left, right]
```

because that constructs two lists and assigns one list to each target.

The brace form is sequential: it performs `self.a = left` and then
`self.b = right`. If the intended operation must capture all sources before any
write, deliberately migrate to parallel tuple assignment instead:

```azora
(self.a, self.b) = (left, right)
```

Similarly:

```azora
fin [a, b] = [left, right]
```

becomes:

```azora
fin {a, b} = {left, right}
```

Use `fin (a, b) = pair` only when the RHS is one tuple/destructurable runtime
value rather than two grouped initializer expressions.

### 17.3 `with` member extraction

Current grouped extraction:

```azora
fin [oldKeys, oldValues, oldCapacity] = with self {
    [keys, values, capacity]
}
```

becomes direct name-based destructuring:

```azora
fin (
    oldKeys = keys,
    oldValues = values,
    oldCapacity = capacity,
) = self
```

This is shorter, gives the operation a first-class semantic model, and removes
the special `with receiver { [members] }` parser path.

### 17.4 Choosing `=` or `<-`

Migration preserves current evaluation count. An old bracket fan-out that
evaluates its initializer once per target becomes a brace group with `=`:

```azora
fin [left, right] = createBuffer() // old repeated form
fin {left, right} = createBuffer() // new repeated form
```

A migration tool MUST NOT rewrite this to `<-`, because doing so changes side
effects, failure count, allocation identity, and ownership. Broadcast is an
explicit opt-in semantic change made by the programmer:

```azora
fin {minimum, maximum}: Int <- calculateLimit()
```

Review a proposed `<-` using this sequence:

1. Confirm that the intention is one RHS evaluation, not one result component
   per name. If components differ, use tuple destructuring.
2. Determine whether every destination owns, shares, or exclusively borrows its
   result.
3. If several destinations own the whole result, require `Copy`; otherwise use
   explicit clones, independent construction, a shared-owner type, or borrows.
4. Reject escaping borrows of a temporary and multiple live exclusive borrows.
5. Preserve the single source's failure and borrow origin in lowering.

Changing `=` to `<-` is normally safe for a pure `Copy` expression when its
value is intentionally identical for all destinations. It is observable even
then if the expression reads changing state, consumes randomness, logs, fails,
or performs synchronization, so formatters and automatic migrations must never
make that choice.

### 17.5 Compatibility window

A safe migration has three stages:

1. **Introduce and prefer.** New syntax parses; old forms still parse and warn.
2. **Fix automatically.** Formatter or migration tool rewrites mechanical forms
   and flags grouped RHS cases for semantic review.
3. **Remove.** Old macro and bracket-group forms become targeted errors.

Tuple syntax and native collection literals should not become the only forms
until interpreter and LLVM parity tests pass. Wasm must either implement them or
reject them explicitly.

## 18. Implementation plan

### Phase 1: AST and parser foundations

1. Add `Expr.SequenceLiteral` or rename the misleading `ArrayLiteral` frontend
   node.
2. Preserve `Expr.MapLit` as an associative literal or unify both shapes under
   `Expr.CollectionLiteral`.
3. Re-enable `Expr.TupleLit` parsing for two or more elements.
4. Add explicit pattern AST nodes rather than immediately desugaring every
   destructuring form.
5. Parse brace import groups and brace fan-out groups.
6. Parse paired brace source groups after a grouped `=`, enforce equal arity,
   and require parentheses when the repeated RHS is a lambda.
7. Add `LEFT_ARROW` for `<-`, lexed before separate `<` and `-`, and preserve
   `=` versus `<-` as `Repeated` versus `Broadcast` on a group statement node.
8. Accept `LEFT_ARROW` only after a completed brace binding/target group and
   provide targeted errors elsewhere.
9. Parse `[]` and `[:]`, including empty and trailing-comma forms.
10. Remove the primary-expression receiver-list call interpretation of
   `[a, b].method()`.

### Phase 2: Type resolution

1. Implement expected-type propagation into collection literals.
2. Resolve literal element/key/value types before selecting factories.
3. Add target-owned `literal` declarations to symbols and validation.
4. Define standard literal factories on `List`, `Map`, `Set`, mutable variants,
   concrete implementations, arrays, queues, and deques as desired.
5. Upcast elements to contextual spec element types before homogeneous-type
   checking.
6. Resolve tuple syntax and `Tuple<...T>` to one canonical type identity.
7. Type and ownership-check positional and name-based patterns.
8. For repeated groups, resolve each RHS instance under its destination's
   expected type and record separate effect/failure events.
9. For paired groups, resolve each source expression under only its matching
   target and record left-to-right ownership, borrow, effect, and failure state.
10. For broadcast groups, resolve one source type, then check every destination
   conversion against the same ownership mode.
11. Require `Copy` for multiple owned broadcast destinations; preserve shared
    borrow origins; reject exclusive-borrow duplication, implicit cloning,
    repeated ownership transfer, and escaped temporary borrows.
12. Run the general smart-pointer, optional, closure-capture, async, and generic
    capability checks on the resolved source rather than special-casing machine
    representations.

### Phase 3: Normalization and IR

1. Normalize repeated brace fan-out into ordinary declarations/assignments with
   explicit repeated evaluation and independent contextual resolution.
2. Normalize paired brace fan-out by zipping targets and sources without
   constructing a collection/tuple or pre-evaluating later source expressions.
3. Normalize broadcast brace fan-out into one compiler-owned source temporary
   plus checked left-to-right destination operations.
4. Carry the broadcast source's ownership mode, borrow origin, cleanup flags,
   and failure edges into typed IR; verify that no owning non-`Copy` or exclusive
   borrow is multiply distributed.
5. Lower fixed collection literals to direct literal-factory calls.
6. Lower tuple values to canonical aggregate IR.
7. Lower destructuring through hidden source/component temporaries so assignment
   is parallel.
8. Record partial moves and borrow origins per extracted component.

### Phase 4: Standard library

1. Convert `Tuple` to a bridge pack while preserving behavior impls.
2. Define default literal factories:
   - `List<T>` -> `ArrayList<T>`;
   - `MutableList<T>` -> `ArrayList<T>`;
   - `Map<K,V>` -> `LinkedHashMap<K,V>`;
   - `MutableMap<K,V>` -> `LinkedHashMap<K,V>`;
   - `Set<T>` -> an explicitly chosen deterministic default;
   - `MutableSet<T>` -> its mutable default.
3. Add literal factories to concrete collection packs.
4. Replace stdlib uses of collection macros.
5. Replace grouped bracket syntax according to the migration table.

### Phase 5: Backends and conformance

1. Add parser golden tests for every delimiter ambiguity.
2. Add type-inference tests with and without context.
3. Add group ownership tests for `Copy`, non-`Copy`, `take`, `.clone()`, shared
   and exclusive borrows, `lend`, temporaries, smart pointers, closures,
   optionals, aggregates, and allocation pointers.
4. Add side-effect and failure tests proving `=` evaluates once per destination
   while `<-` evaluates once total, and proving target writes remain left to
   right.
5. Add cleanup tests for source failure, later conversion/write failure,
   overwritten owners, and partially initialized binding groups.
6. Run identical tuple, collection, destructuring, repeated-group, and
   broadcast-group programs under the
   interpreter and LLVM.
7. Make unsupported Wasm paths explicit compile errors until implemented.

### Phase 6: Deprecation

1. Warn on `@arr`, `@vec`, tuple macros, and bracket grouping.
2. Provide syntax-aware automatic fixes.
3. Remove the old parsing branches after the compatibility window.
4. Remove obsolete collection macros from the prelude only after all repository
   sources and examples migrate.

## 19. Required test matrix

At minimum, tests must cover:

### Groups

- import groups, nested imports, wildcard members, and empty-group errors;
- group binding per-entry and shared types;
- `=` evaluating side effects once per binding/target and in source order;
- paired `= {e0, e1}` evaluating each matching expression exactly once and in
  target order;
- equal target/source arity and empty paired-source rejection;
- `self.{x, y} = {1, 2}` pairing versus `self.{x, y} = [1, 2]` repeating one
  collection construction;
- later paired expressions observing earlier writes, contrasted with parallel
  tuple assignment;
- target-contextual typing and independent ownership/borrow state for each
  paired source expression;
- paired moves from different owners and rejection when two entries take the
  same owner;
- paired source group versus lambda parsing, including parenthesized lambdas;
- rejection of a brace source group after `<-`;
- `<-` evaluating the RHS exactly once before all target writes;
- one-entry `<-` warning and empty-group rejection;
- `LEFT_ARROW` maximal-munch lexing and separation from `<`, `-`, `<=`, `>=`,
  and existing `->`;
- rejection of `<-` for simple bindings, tuples, imports, and type groups;
- nested member/index targets;
- duplicate names, duplicate static targets, and dynamically aliasing targets;
- distinction between brace fan-out and tuple destructuring;
- per-target contextual typing under `=` and incompatible contexts under `<-`;
- implicit broadcast of primitive and user-defined `Copy` values;
- broadcast of all-`Copy` tuples, packs, function values, and optionals;
- rejection of a tuple, pack, spec box, optional, closure, or collection with a
  non-`Copy` owned component;
- repeated fresh non-`Copy` construction versus rejected owned broadcast;
- repeated explicit `.clone()` calls and proof that `<- value.clone()` clones
  only once;
- repeated and broadcast `take` errors for more than one destination;
- shared-borrow broadcast with origin propagation and owner move prevention;
- rejection of shared borrows escaping a temporary owner;
- rejection of repeated and broadcast exclusive borrows with overlapping
  lifetimes;
- valid sequential exclusive-borrow operations after the earlier borrow ends;
- repeated `lend` calls receiving ownership back before the next expansion;
- broadcast of a copyable result from one `lend` call;
- `Unique`, `Shared`, `SyncShared`, and `Weak` behavior without implicit handle
  cloning;
- owning `alloc` with repeated independent allocation and rejected broadcast;
- raw non-owning pointer provenance and unsafe alias restrictions;
- closure capture capability, escaping closures, async suspension, and thread
  sharing restrictions;
- RHS failure before any broadcast write, later target failure after earlier
  writes, and exact-once cleanup;
- interpreter, LLVM, and Wasm parity or explicit target rejection.

### Collection literals

- inferred list and map types;
- explicit list, mutable list, set, map, array, and custom targets;
- empty `[]` and `[:]` with and without context;
- trailing commas and multiline formatting;
- mixed-shape rejection;
- numeric promotion;
- nullable elements;
- spec-typed heterogeneous concrete elements;
- duplicate set/map behavior;
- evaluation order and exactly-once evaluation;
- `Copy`, `take`, borrowed elements, and failure cleanup;
- direct literal-factory lowering without intermediate collections;
- fixed array arity mismatch;
- nested collection literals;
- spreads when enabled.

### Tuples

- value and type inference for every supported arity;
- identity with `Tuple<...T>`;
- nested tuples;
- `.0`, `.1`, and out-of-range errors;
- function-type ambiguity;
- unit, grouping, and rejected one-tuples;
- ownership, partial moves, clone, equality, hash, and display;
- interpreter/LLVM ABI parity.

### Destructuring

- positional declarations and assignments;
- name-based aliases;
- mixed binding kinds;
- loop patterns;
- ignored and nested components;
- arity/type mismatches;
- access control;
- pack opt-in for positional destructuring;
- name-based property extraction;
- source evaluation exactly once;
- parallel target writes;
- Copy, move, partial move, shared borrow, exclusive borrow, and borrow origins.

## 20. Decisions made by this proposal

This proposal settles the following points:

1. Bare non-empty `[...]` defaults to `List<E>`.
2. Bare keyed `[...]` defaults to `Map<K,V>`.
3. `[:]` is the empty associative literal.
4. Set literals use the same sequence shape and require set context.
5. `List<T>([...])` is unnecessary and not introduced.
6. Ordinary explicit construction remains varargs: `List<T>(a, b, c)`.
7. Contextual literal construction is defined by a target-owned static
   `literal` factory.
8. Literal factory lowering does not allocate an intermediate collection.
9. Tuple syntax and `Tuple<...T>` have identical type identity.
10. Tuple arity starts at two; `()` remains Unit and `(x)` remains grouping.
11. Brace groups with `= expression` fan out and evaluate that expression once
    per destination.
12. Brace groups with `= {e0, e1, ...}` pair targets and sources by position,
    evaluate pairs sequentially, and never construct a collection or tuple.
13. Brackets always denote collection syntax; `self.{x, y} = [1, 2]` is not
    pairwise assignment, while `self.{x, y} = {1, 2}` is.
14. Brace groups with `<-` evaluate the RHS exactly once and broadcast the same
    whole result only when ordinary ownership and borrowing permit it.
15. Broadcast never implies clone, reference counting, lifetime extension, or
    duplication of an exclusive owner/borrow.
16. `<-` has a dedicated `LEFT_ARROW` token and is legal only after a brace
    binding/target group; `->` retains all existing meanings.
17. Parenthesized patterns destructure one source evaluated once.
18. Tuple assignment is parallel; brace assignment processes targets in source
    order, whether its source is repeated, paired, or broadcast.
19. Packs require opt-in for positional destructuring but allow accessible
    name-based destructuring.
20. The old `[a, b].method()` contextual-receiver call shorthand is removed so
    a primary `[...]` always means a collection literal.
21. Every collection of types uses tuple syntax, including `derives (...)`,
    grouped `impl (...) for T`, implementation targets, and compile-time type
    iteration. Braces are not used for type collections.

## 21. Open implementation details

The surface design does not require these questions to delay parsing and type
checking, but they must be resolved before stabilization:

1. Whether the default public type of an uncontextualized literal remains a
   spec value (`List<E>`/`Map<K,V>`) or uses a dedicated non-boxed standard
   representation with an implicit view as the spec. The observable source type
   should remain the one documented here.
2. The exact ABI used when a spec-owned literal factory returns a concrete
   default implementation.
3. The builder hook required for runtime-length spreads.
4. Automatic destruction of partially built collections after a failable
   element or insertion.
5. Whether a custom literal factory may overload by constraints or whether one
   unique factory per shape is permanently required.
6. Whether `Destructure` becomes a public bridge spec or remains a compiler
   derivation contract.
7. Whether name-based destructuring may select multi-segment member paths in a
   future revision.
8. Whether one-element tuples are worth adding later. Doing so would require
   `(x,)` and must not change `(x)`.

None of these open details changes the source-level distinction among groups,
collections, and tuples.

## 22. Final examples

```azora
import std.container::{List, Map, Set}
import app.model::{User, Session}

pack User derives (Equal, Hash, Destructure) {
    fin username: String
    fin email: String
}

func main() {
    // Group: three independent declarations, with the initializer repeated.
    var {min, current, max}: Int = 0

    // Paired group: each target receives its corresponding brace expression.
    {min, max} = {readMinimum(), readMaximum()}

    // Broadcast group: calculate once, then copy one Int into both bindings.
    fin {softLimit, hardLimit}: Int <- calculateLimit()

    // Native collections: target type selects the target-owned literal factory.
    fin ids: List<Int> = [1, 2, 3]
    fin unique: Set<Int> = [1, 2, 2, 3]
    fin scores: Map<String, Int> = [
        "alice": 10,
        "bob": 20,
    ]

    // Builtin tuple, type-identical to Tuple<Int, String, Bool>.
    fin state: (Int, String, Bool) = (7, "ready", true)
    fin (code, label, enabled) = state

    // Positional pack destructuring is opt-in through derives Destructure.
    val user = User("alice", "alice@example.com")
    val (username, email) = user

    // One owner, two read-only views. Both borrow origins remain `user`.
    val {auditView, displayView}: User& <- user.&

    // Name-based destructuring is explicit and independent of declaration order.
    val (mail = email, name = username) = user

    // One source, parallel assignment.
    (min, max) = (max, min)

    // Fan-out binding, one allocation per declared pointer.
    let {leftBuffer: UByte*, rightBuffer: UByte*} = alloc .() * 1024

    // Positional loop destructuring.
    for (name, score) in scores {
        println(name + ": " + score)
    }
}
```

The intended reading is simple:

- braces say “apply this syntax to this group”; `= expression` repeats one
  source, `= {e0, e1}` pairs sources by position, and `<-` evaluates one source
  once and broadcasts only when ownership permits;
- brackets say “build or access a collection”;
- parentheses say “form or split a positional product.”

## 23. Companion operator vocabulary

This section records the operator vocabulary selected alongside the delimiter
design. It prevents these spellings from acquiring unrelated meanings in
separate features and defines how they interact with ownership, borrowing,
effects, evaluation order, and the lexer.

The proposed companion features are `===`, `!==`, `<>`, `???`, `<-`, `|>`,
`<|`, `<|>`, and `///`. This proposal deliberately assigns no meaning and makes
no reservation for `<==`, `==>`, or `<==>`.

### 23.1 Equality, identity, and type tests

Azora distinguishes three different questions:

```azora
left == right       // do these values compare equal?
left === right      // do these handles/views identify the same object or storage?
value is User       // does this runtime value conform to type User?
```

Their negations are correspondingly different:

```azora
left != right
left !== right
value !is User
```

`==` and `!=` use value semantics and the ordinary `Equal` contract. They may
run type-defined equality logic and may report whatever effects the equality
contract permits.

`is` and `!is` test runtime type conformance. Their right operand is a type or
type pattern, not a value. They answer whether a boxed/spec value, variant, or
other dynamically typed representation can be viewed as that type. They say
nothing about whether two values have equal contents or share storage. `!is`
may lex as `BANG` followed by `IS`; it does not require a combined token.

#### 23.1.1 Identity equality with `===` and `!==`

`===` asks whether two identity-bearing values refer to the same semantic
identity. `!==` is exactly its logical negation. These operators are
compiler-defined and cannot be overloaded; user code must not redefine what
“same allocation” means.

Identity is defined by category:

| Operand category | `a === b` means |
|---|---|
| Shared borrows | both borrows have the same storage address and compatible provenance |
| Exclusive borrows | both designate the same storage; ordinary exclusivity normally prevents two independently live operands |
| `Shared<T>` / `SyncShared<T>` | both handles use the same ownership/control block |
| `Weak<T>` | both weak handles refer to the same control block, even if the object has expired |
| `Unique<T>` views | both views designate the allocation owned by the same unique handle |
| Boxed spec/object value | both boxes designate the same dynamic object identity |
| Raw pointer in `unsafe` code | equal address and provenance according to the pointer model |
| Nullable identity value | `null === null` is true; `null === nonNull` is false |

Scalar numbers, booleans, characters, ordinary unboxed tuples, and ordinary
value packs do not have identity merely because they occupy memory during one
compilation. Comparing them with `===` is a type error:

```azora
fin a = 42
fin b = 42

a == b  // true
a === b // error: Int is a value type and has no observable identity
```

Likewise, two independent values can be equal without being identical:

```azora
fin first: Shared<Image> = sharedOf(loadImage())
fin second: Shared<Image> = first.clone()
fin independent: Shared<Image> = sharedOf(first.value.clone())

first === second      // true: same control block
first === independent // false: different allocations
first == independent  // may be true if Image equality compares equal contents
```

Both operands are evaluated exactly once, left to right. Identity comparison
observes them through shared access; it never moves, clones, retains, releases,
or extends the lifetime of either operand. A non-`Copy` smart pointer therefore
does not need to be copied merely to compare its identity.

For generics, identity comparison is accepted only when the constraints prove
that `T` has identity-bearing representation. The compiler may expose this as a
compiler-known `Identity` capability, but implementing that capability cannot
override the comparison algorithm.

Identity is not equality, ordering, hashing, or synchronization. In particular:

- `===` does not imply `==`;
- identity does not supply a stable hash after an object is destroyed;
- comparing identities does not make concurrent access safe;
- raw address equality does not prove that dereferencing is valid;
- identity comparison does not keep a weak target alive.

Recommended diagnostics include:

```text
operator '===' requires identity-bearing operands; type 'Point' is an unboxed
value type. Use '==' for value equality.

cannot compare identities of unrelated types 'Shared<Image>' and
'Shared<Window>'
```

### 23.2 Ownership-aware exchange with `<>`

`<>` is a statement-level primitive that exchanges the values stored in two
assignable locations:

```azora
left <> right
self.front <> self.back
items[i] <> items[j]
```

It is conceptually similar to:

```azora
(left, right) = (right, left)
```

but gives exchange a direct ownership model and permits a backend to use an
efficient swap without constructing a tuple-shaped temporary.

The normative rules are:

1. Both operands must be assignable mutable locations of the same static type.
2. Resolve and evaluate the left location once, then the right location once,
   before moving either stored value.
3. Acquire the exclusive access required for both locations.
4. Move the left value into compiler-owned temporary storage.
5. Move the right value into the left location.
6. Move the temporary into the right location.
7. Do not copy, clone, retain, release, or run a destructor for the values merely
   because they were exchanged.

Consequently, exchange works for move-only values:

```azora
var primary: Unique<Socket> = openPrimary()
var fallback: Unique<Socket> = openFallback()

primary <> fallback // valid: owners exchange locations; neither is duplicated
```

Binding mutability still applies. A `fin`, immutable field, shared borrow, or
read-only property cannot participate as a destination. An exclusive borrow may
participate when it grants access to the complete stored location.

The compiler must handle aliasing explicitly:

- provably disjoint locations are exchanged;
- provably identical complete locations make the operation a no-op after both
  location expressions have been evaluated;
- provably partially overlapping locations are rejected;
- safe indexed/container locations whose equality is known only at runtime may
  perform one equality/disjointness check before moving values;
- raw-pointer overlap that cannot be proven safe requires `unsafe` and remains
  subject to the pointer provenance model.

Active borrows are checked as if both locations were exclusively borrowed for
the duration of the operation. A live shared/exclusive borrow that conflicts
with either location rejects the exchange. Exchanging two handles does not move
their pointees, but it does move which binding owns each handle.

Location evaluation may fail before the exchange begins—for example, a checked
index can be out of bounds. In that case no stored value is moved. After both
locations and their exclusive access have been established, the primitive
exchange itself is non-failable and indivisible with respect to language-level
destruction. It is not automatically atomic between threads; use an atomic
container operation when inter-thread atomicity is required.

`<>` is not a general expression and produces `Unit` only as a statement. It
cannot be overloaded because custom implementations could accidentally clone,
drop, or partially exchange unique owners.

### 23.3 Typed compiler holes with `???`

`???` is a deliberate missing-expression marker used during development:

```azora
func loadUser(id: UserId): User {
    return ???
}
```

It receives a dedicated `TYPED_HOLE` token and may appear wherever an expression
is required. It does not appear in a type, declaration name, pattern, or target
position. The lexer must recognize `???` before `??` and `?`.

A typed hole participates in constraint collection so the compiler can continue
checking its surroundings. It adopts the expected type, ownership mode, failure
set, and effect requirements of its position without claiming that an actual
implementation exists. For example:

```azora
func transform<T, R>(value: T): R {
    return ???
}
```

may produce:

```text
typed hole has expected type 'R'
required ownership: return an owned R
available values:
  value: T (owned, not Copy under current constraints)
no conversion from T to R is available
```

A useful hole diagnostic SHOULD include:

- the inferred expected type;
- whether the position needs an owned value, shared borrow, or exclusive borrow;
- the required borrow origin when returning a borrow;
- allowed and required failure/effect sets;
- visible locals and whether each is available, borrowed, or already moved;
- callable candidates whose result is compatible;
- missing generic constraints that would make a candidate valid.

The hole itself performs no operation. It does not move a local, create a
borrow, acquire a resource, emit an effect, or generate cleanup. Ownership
analysis treats those events as hypothetical only so later diagnostics do not
cascade from a nonexistent move.

Every reachable or unreachable `???` is a compilation error in all production
and development builds. It never silently lowers to `null`, zero, `Unit`, an
uninitialized value, or `panic`. Programmers who want an executable stub must
write an explicit `panic("TODO")` or other failure expression.

Multiple holes should each receive a stable diagnostic identifier so IDEs can
display and request completions for them independently. Named holes and automatic
code synthesis are possible future IDE features but do not change the source
semantics.

### 23.4 One-shot group broadcast with `<-`

`<-` is the group-only, one-evaluation broadcast operator defined throughout
§7. It has a dedicated `LEFT_ARROW` token and is legal only after a brace binding
or target group:

```azora
fin {x, y}: Int <- calculate()
self.{width, height} <- measure()
```

Its invariant is:

```text
one RHS evaluation + one retained result + ordinary checked distribution
```

It is not ordinary assignment, tuple destructuring, event subscription, or
channel receive. It never performs an implicit clone, reference
count increment, ownership split, or borrow-lifetime extension.

The three group forms remain visibly distinct:

```azora
fin {a, b} = make()          // make() runs twice
fin {a, b} = {makeA(), makeB()} // each paired source runs once
fin {a, b} <- make()         // make() runs once; result is broadcast if safe
```

The detailed `Copy`, move, clone, allocation, shared/exclusive borrow, lending,
closure, optional, aggregate, failure, and cleanup rules are normative in
§7.4.


### 23.6 Pipelines with `|>` and `<|`

Pipelines change the written order of an ordinary call; they do not introduce a
new calling, ownership, failure, or dispatch convention.

#### 23.6.1 Forward pipeline `|>`

The forward form sends the left value into the first argument position of the
right call target:

```azora
value |> transform
value |> transform(option)
```

These lower respectively to:

```azora
transform(value)
transform(value, option)
```

Pipelines associate left to right:

```azora
request
    |> validate
    |> normalize
    |> save(database.&)
```

is equivalent to:

```azora
save(normalize(validate(request)), database.&)
```

except that the typed AST should retain source spans for each pipeline stage so
errors point to the failing stage rather than to one synthesized nested call.

#### 23.6.2 Reverse application `<|`

The reverse form permits the callable to be written first while keeping a large
argument expression unparenthesized:

```azora
render <| buildPage(model)
save(database.&) <| normalize(request)
```

These mean:

```azora
render(buildPage(model))
save(normalize(request), database.&)
```

`<|` associates right to left. Mixing forward and reverse pipeline operators at
one level SHOULD require parentheses because otherwise visually plausible code
can have surprising grouping.

#### 23.6.3 Ownership and borrowing through a pipeline

Pipeline lowering preserves the exact argument expression. It never inserts
`take`, `lend`, `.&`, `.!`, or `.clone()`:

```azora
resource.&   |> inspect       // shared borrow
resource.!   |> mutate        // exclusive borrow
take resource |> consume      // explicit ownership transfer
resource.clone() |> retain    // explicit independent owner
```

If `consume` takes a non-`Copy` value by ownership, `resource |> consume` is
rejected for the same reason as `consume(resource)`. A pipeline stage may retain
a borrow only when the ordinary callable signature and borrow-origin rules allow
it. Async stages receive no special lifetime extension.

Every stage is evaluated once in written data-flow order. The callable and its
non-piped arguments follow the same evaluation order as the equivalent ordinary
call. Failures and effects propagate normally; `|>` and `<|` do not catch,
retry, roll back, or parallelize anything.

#### 23.6.4 Pipeline typing and precedence

The result type of one stage becomes the first-argument input type of the next.
Generic inference uses that input normally. Overload resolution is performed one
stage at a time and diagnostics should print the incoming pipeline type.

Version 1 does not introduce a placeholder syntax such as `_` inside a stage.
The piped value always occupies the first argument position. A lambda makes any
other placement explicit:

```azora
value |> { v -> combine(prefix, v) }
```

Pipeline precedence is lower than arithmetic, member access, calls, comparison,
and null coalescing, but higher than assignment and group operators. `|>` is
left-associative and `<|` is right-associative.

### 23.7 Alternative choice with `<|>`

`<|>` selects the first successful failable expression:

```azora
fin config = loadCachedConfig() <|> downloadConfig() <|> defaultConfig()
```

Its behavior is:

1. Evaluate the left expression exactly once.
2. If it succeeds, return its value and do not evaluate the right expression.
3. If it fails with a recoverable failure, clean up its partially initialized
   owned values and evaluate the right expression.
4. If both fail, combine/propagate their declared failure information according
   to the ordinary failable-expression model.

It does not catch panic, memory faults, cancellation that must propagate, or
failures outside the expression's declared recoverable set. Effects performed
before the left failure remain observable; `<|>` is lazy but not transactional.
APIs needing rollback must provide an explicit transaction/checkpoint scope.

Both alternatives must produce one common assignable result type. Ownership is
branch-local: only the successful branch transfers its result outward, while a
failed branch destroys anything it initialized. A named non-`Copy` value cannot
be consumed independently by both alternatives unless control-flow ownership
analysis proves that exactly one executed branch takes it:

```azora
fin output = first(take input) <|> second(take input)
```

This is valid only if failure from `first` guarantees that ownership of `input`
is restored or was never committed. If `first` can consume and then fail without
returning ownership, `second` cannot take the same value. `lend`/`return`, a
clone, or a shared owner makes the policy explicit.

`<|>` is not boolean OR (`||`) and not null coalescing (`??`). A nullable value
uses `??`; `<|>` is reserved for language-level recoverable failure/alternative
semantics. It associates left to right and has lower precedence than `|>`/`<|`
but higher precedence than assignment.

### 23.8 Documentation comments with `///`

`///` begins a documentation comment that runs to the end of the physical line:

```azora
/// Opens a file and returns its unique owner.
///
/// Fails with `NotFound` when the path does not exist.
func open(path: Path&): File
```

Consecutive `///` lines form one Markdown document block. The block attaches to
the next declaration when only whitespace, ordinary comments, and decorators
intervene. A blank undocumented source line ends the block unless the blank line
itself is written as `///`.

Documentation may attach to modules, imports intended for re-export, types,
variants, fields, constructors, functions, properties, specs, impl members,
macros, and other named declarations. A dangling documentation block at the end
of a scope is a warning.

The compiler retains normalized documentation and source spans in symbol
metadata for IDE hover, generated API documentation, reflection where enabled,
and diagnostics. Documentation comments do not enter runtime data unless a
reflection/build option explicitly requests them.

Markdown code fences are content, not Azora lexical delimiters. Documentation
tools may interpret tags or links, but the compiler should not give arbitrary
`@tag` text semantic effects. References to Azora symbols should be resolved by
the documentation tool using stored source context.

The lexer must recognize `///` before the ordinary `//` line-comment rule and
emit either a `DOC_COMMENT` trivia token or an equivalent attached trivia node.
The third slash is not part of the text. `//// text` is a documentation comment
whose content begins with `/ text`, unless a formatter chooses to warn.

Documentation comments have no ownership, borrowing, evaluation, or effect
semantics. They must not change whether code compiles except for documentation
validation configured as warnings/errors.

### 23.9 Lexical priority and token names

The lexer uses maximal munch. These spellings must be checked before any token
that is their prefix:

| Priority family, longest first | Suggested token |
|---|---|
| `<==>` | `REACTIVE_BOTH_ARROW` |
| `<==` | `REACTIVE_LEFT_ARROW` |
| `==>` | `REACTIVE_RIGHT_ARROW` |
| `===` | `IDENTICAL` |
| `!==` | `NOT_IDENTICAL` |
| `???` | `TYPED_HOLE` |
| `<|>` | `ALTERNATIVE` |
| `///` | `DOC_COMMENT` trivia |
| `<>` | `EXCHANGE` |
| `<-` | `LEFT_ARROW` |
| `|>` | `PIPE_FORWARD` |
| `<|` | `PIPE_REVERSE` |

Important prefix relationships include:

```text
<==> before <==, <=>, <=, <-, <
<==  before <= and <
==>  before ==, =>, and =
===  before == and =
!==  before != and !
???  before ?? and ?
<|>  before <|, |>, <, and |
///  before // and /
<>   before < and >
<-   before < and -
```

Whitespace does not change tokenization inside one ligature. For example,
`a < -b` remains `LESS` followed by unary `MINUS`, while `group <- value` uses
`LEFT_ARROW`. The formatter places one space around binary/statement operators
and preserves `///` at the beginning of its documentation line.

The reactive tokens may be emitted before the feature is implemented so the
parser can say “reserved for reactive binding” rather than misdiagnosing a
sequence of comparison and assignment tokens.

### 23.10 Overloading policy

These constructs are semantic syntax, not arbitrary overload names:

- `===`/`!==` use compiler-defined identity;
- `<>` performs compiler-verified storage exchange;
- `???` is a compiler hole;
- `<-` is brace-group broadcast;
- `<==`/`==>`/`<==>` are reserved graph relationships;
- `|>`/`<|` lower to ordinary calls;
- `<|>` uses the language's recoverable-failure model;
- `///` is documentation trivia.

None may be declared with `oper` in version 1. This keeps ownership and
evaluation guarantees independent of user code. Libraries customize behavior
through the ordinary call targets, failure contracts, reactive source/sink
interfaces, or identity-bearing standard types—not by redefining punctuation.

### 23.11 Diagnostics

Recommended operator-specific diagnostics include:

```text
type 'Point' has value equality but no identity; use '==' instead of '==='

cannot exchange 'left' and 'right': right is shared-borrowed by 'view'

cannot exchange immutable binding 'left'; declare mutable storage or use a new
binding

typed hole has expected type 'File' and must produce an owned value

'<-' broadcasts one value only after a brace group; it is not channel receive
or ordinary assignment

'<==' is reserved for future persistent reactive binding; use '<-' for a
one-shot brace-group broadcast

pipeline stage 'save' expects first argument 'Document' but incoming pipeline
value has type 'Document&'

left alternative may consume 'request' before failing, so the right alternative
cannot take it; lend, clone, or share the value explicitly

documentation comment does not attach to a declaration
```

### 23.12 Required tests

Before stabilization, tests must cover:

#### Identity

- value equality versus identity equality;
- identity of cloned shared/weak handles and independent allocations;
- borrow origins and pointer provenance;
- nullable identity operands;
- rejection for scalars, unboxed tuples, and value packs;
- generic identity constraints;
- exactly-once left-to-right operand evaluation;
- proof that comparison does not change retain/release counts.

#### Exchange

- `Copy`, non-`Copy`, unique-owner, tuple, pack, and smart-pointer storage;
- member and checked-index locations evaluated once;
- immutable targets and conflicting live borrows;
- disjoint, identical, partially overlapping, and dynamically equal locations;
- exact-once destruction and no clone/retain/release during exchange;
- failure during location evaluation before mutation;
- explicit lack of cross-thread atomicity.

#### Typed holes

- expected types from return, argument, collection, tuple, and generic context;
- owned/shared/exclusive expected modes and returned borrow origins;
- effect/failure requirements and missing generic constraints;
- moved/unavailable local reporting;
- multiple independent holes and unreachable holes;
- mandatory compilation failure and absence of generated IR;
- maximal munch against `??` and `?`.

#### Broadcast and reactive reservations

- all group broadcast tests required by §19;
- lexical distinction among `<-`, `<==`, `<==>`, `<=>`, and `<=`;
- targeted reserved-feature errors for every reactive spelling;
- no accidental parsing as comparison plus assignment.

When the reactive runtime is implemented, add lifecycle, dependency capture,
coalescing, ordering, cycles, conflicts, backpressure, async ownership, thread
affinity, and exact-once teardown tests before enabling the tokens.

#### Pipelines and alternatives

- forward and reverse associativity and precedence;
- calls with zero and several written arguments;
- generic inference and overload diagnostics at each stage;
- explicit `take`, `lend`, shared borrow, exclusive borrow, and clone inputs;
- failure/effect propagation without hidden catch or concurrency;
- left-success laziness and right-side non-evaluation for `<|>`;
- both-fail diagnostics and cleanup of failed alternatives;
- ownership restoration requirements when retrying with the same value;
- separation from `||` and `??`;
- maximal munch against `|`, `<`, and `>`.

#### Documentation

- single and consecutive documentation lines;
- intentional blank lines written as `///`;
- attachment through decorators and ordinary comments;
- dangling blocks and unsupported attachment targets;
- Markdown fences and symbol-reference source spans;
- metadata retention/removal configuration;
- lexical distinction from `//`, `/`, and division.
